package com.pocketshell.app.proof

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import java.io.File
import java.io.FileOutputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Issue #724: the SINGLE source of truth for the connected-test SSH/tmux
 * fixture's host:port. Historically every journey/Docker test hard-coded
 * `10.0.2.2:2222`, so parallel lanes all targeted ONE shared `agents`
 * container and corrupted each other's tmux state. This object centralizes
 * the target so a per-lane allocator can point a run at its OWN fixture.
 *
 * The port (and, for completeness, the host) are read from instrumentation
 * runner arguments, DEFAULTING to `10.0.2.2:2222` (D22: one config source,
 * defaulted — there is no second "legacy" hard-coded path). The lane allocator
 * in `scripts/connected-test.sh --pool` threads its claimed agents port through
 * gradle as:
 *
 *   -Pandroid.testInstrumentationRunnerArguments.agentsPort=2243
 *
 * With no arg present every test behaves exactly as before (host `10.0.2.2`,
 * port `2222`, user `testuser`), so single-lane and CI runs are unchanged.
 *
 * NOTE (phase-2 follow-up, issue #724): the 5 load-bearing journey classes
 * (`DeepLinkSessionSwitchE2eTest`, `MultiSessionSwitchJourneyE2eTest`,
 * `ColdRestoreGoneSessionNoResurrectE2eTest`, `ReconnectRepaintE2eTest`,
 * `BackgroundGraceReconnectE2eTest`) plus every test that already imports the
 * `DEFAULT_HOST` / `DEFAULT_PORT` / `DEFAULT_USER` identifiers below now resolve
 * their target through this single helper automatically (the identifiers are
 * property-backed). The remaining ~15 `*DockerTest` files that STILL hard-code
 * their own `10.0.2.2` / `2222` string/int literals (e.g. the local
 * `const val DEFAULT_HOST` in `HostBootstrapScenarioSuiteTest` and
 * `DefaultHostLaunchE2eTest`) are an explicit phase-2 literal sweep, kept out
 * of this slice to avoid conflicting with in-flight androidTest work.
 */
object AgentsFixtureTarget {
    private const val HOST_ARG_KEY: String = "agentsHost"
    private const val PORT_ARG_KEY: String = "agentsPort"

    const val DEFAULT_FIXTURE_HOST: String = "10.0.2.2"
    const val DEFAULT_FIXTURE_PORT: Int = 2222
    const val DEFAULT_FIXTURE_USER: String = "testuser"

    /**
     * The fixture SSH host. The emulator reaches the host loopback at
     * `10.0.2.2`, so this is overridden only in exotic setups; it stays
     * defaulted for every normal run.
     */
    val host: String
        get() = InstrumentationRegistry.getArguments()
            .getString(HOST_ARG_KEY)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_FIXTURE_HOST

    /**
     * The fixture SSH host port. Defaults to 2222 (the long-standing single
     * shared `agents` fixture); the lane allocator overrides it to that lane's
     * claimed agents port (e.g. 2243) so parallel lanes hit DISTINCT fixtures.
     */
    val port: Int
        get() = InstrumentationRegistry.getArguments()
            .getString(PORT_ARG_KEY)
            ?.trim()
            ?.toIntOrNull()
            ?: DEFAULT_FIXTURE_PORT

    val user: String
        get() = DEFAULT_FIXTURE_USER
}

/**
 * The connected-test SSH fixture host. Backed by [AgentsFixtureTarget] so the
 * whole androidTest suite resolves ONE target; defaults to `10.0.2.2`.
 */
val DEFAULT_HOST: String get() = AgentsFixtureTarget.host

/**
 * The connected-test SSH fixture port. Backed by [AgentsFixtureTarget] so a
 * per-lane allocator can point this run at its own agents container via the
 * `agentsPort` instrumentation arg; defaults to `2222`.
 */
val DEFAULT_PORT: Int get() = AgentsFixtureTarget.port

val DEFAULT_USER: String get() = AgentsFixtureTarget.user

data class ShellHandle(
    val client: SSHClient,
    val sessionChannel: Session,
    val shell: Session.Shell,
)

suspend fun openShell(
    host: String,
    port: Int,
    user: String,
    key: SshKey.Pem,
): ShellHandle = withContext(Dispatchers.IO) {
    val client = SshConnection.createClient()
    try {
        client.addHostKeyVerifier(PromiscuousVerifier())
        client.connectTimeout = 15_000
        client.timeout = 15_000
        client.connect(host, port)
        val keyProvider = client.loadKeys(key.content, null, null)
        client.authPublickey(user, keyProvider)
        val session = client.startSession()
        session.allocateDefaultPTY()
        val shell = session.startShell()
        ShellHandle(client = client, sessionChannel = session, shell = shell)
    } catch (t: Throwable) {
        runCatching { client.disconnect() }
        throw t
    }
}

fun createStdoutFlow(shell: Session.Shell): Flow<ByteArray> = flow {
    val buffer = ByteArray(4096)
    while (true) {
        val read = shell.inputStream.read(buffer)
        if (read < 0) break
        if (read > 0) emit(buffer.copyOf(read))
    }
}.flowOn(Dispatchers.IO)

suspend fun waitForSshFixtureReady(
    key: SshKey.Pem,
    port: Int = DEFAULT_PORT,
    timeout: Duration = 45.seconds,
) {
    val deadline = SystemClock.elapsedRealtime() + timeout.inWholeMilliseconds
    val failures = mutableListOf<String>()
    var attempt = 0
    while (SystemClock.elapsedRealtime() < deadline) {
        attempt += 1
        val result = SshConnection.connect(
            host = DEFAULT_HOST,
            port = port,
            user = DEFAULT_USER,
            key = key,
            knownHosts = com.pocketshell.core.ssh.KnownHostsPolicy.AcceptAll,
            timeoutMs = 10_000,
        ).mapCatching { session ->
            session.use { it.exec("printf 'android ssh fixture ready '; tmux -V") }
        }
        val execResult = result.getOrNull()
        if (execResult?.exitCode == 0) {
            println("WALKTHROUGH_SSH_FIXTURE_READY attempt=$attempt output=${execResult.stdout.trim()}")
            return
        }
        failures += "attempt $attempt: " +
            (result.exceptionOrNull()?.toString() ?: "exit=${execResult?.exitCode} stderr=${execResult?.stderr}")
        SystemClock.sleep(1_000)
    }
    error("SSH fixture on $DEFAULT_HOST:$port was not ready after $attempt attempts:\n${failures.takeLast(10).joinToString("\n")}")
}

/**
 * Issue #532: connected-test remote setup (creating a deterministic tmux
 * session, seeding a script, resetting the tmux server) used to be a
 * single-shot `withTimeout(20_000) { SshConnection.connect(...).exec(...) }`
 * that asserted on the first attempt. On the GitHub Actions emulator the
 * Android device, the host JVM, and the Docker `agents` container share two
 * cores and a swiftshader GPU, so a single connect+auth+exec can either run
 * slow enough to blow the fixed 20 s window or hit a transient connect/auth
 * failure — and there was no retry, so the whole prep failed and surfaced as
 * "tmux claude-main prep times out".
 *
 * This helper replaces those single-shot prep calls with a poll-until loop:
 * it re-connects and re-runs the remote command until [isReady] is satisfied
 * (default: exit code 0), backing off [pollIntervalMs] between attempts,
 * inside a CI-aware deadline. This mirrors the already-robust
 * `cleanupRemoteWalkthroughArtifacts` loop in the smoke test and the
 * retry-until-ready shape of [waitForSshFixtureReady]. There are no new fixed
 * `sleep`s on the success path — every attempt early-exits as soon as the
 * remote command reports ready.
 */
suspend fun execRemoteSetupUntilReady(
    key: SshKey.Pem,
    command: String,
    description: String,
    host: String = DEFAULT_HOST,
    port: Int = DEFAULT_PORT,
    user: String = DEFAULT_USER,
    timeoutMs: Long = SshSetupTimeouts.remoteSetupTimeoutMs(),
    pollIntervalMs: Long = 500,
    isReady: (ExecResult) -> Boolean = { it.exitCode == 0 },
): ExecResult {
    var attempt = 0
    var lastFailure = "remote setup never ran"
    var lastResult: ExecResult? = null
    val satisfied = runCatching {
        withTimeout(timeoutMs) {
            while (true) {
                attempt += 1
                val outcome = SshConnection.connect(
                    host = host,
                    port = port,
                    user = user,
                    key = key,
                    knownHosts = KnownHostsPolicy.AcceptAll,
                    timeoutMs = 15_000,
                ).mapCatching { session ->
                    session.use { it.exec(command) }
                }
                val result = outcome.getOrNull()
                if (result != null && isReady(result)) {
                    lastResult = result
                    return@withTimeout result
                }
                lastFailure = "attempt $attempt: " +
                    (outcome.exceptionOrNull()?.toString()
                        ?: "exit=${result?.exitCode} stdout='${result?.stdout}' stderr='${result?.stderr}'")
                result?.let { lastResult = it }
                delay(pollIntervalMs)
            }
            @Suppress("UNREACHABLE_CODE")
            lastResult
        }
    }.getOrNull()
    return satisfied ?: error(
        "expected $description to become ready within ${timeoutMs}ms; " +
            "last failure after $attempt attempts: $lastFailure",
    )
}

/**
 * CI-aware deadline for connected-test remote SSH setup (issue #532). The
 * single-shot prep helpers previously used a flat `withTimeout(20_000)`,
 * which is comfortable on a dedicated dev emulator but too tight once the CI
 * emulator is sharing two cores and a swiftshader GPU with a parallel Docker
 * `agents` container. The CI window is widened while the local window stays
 * tight so a real dev-box regression still surfaces quickly. The
 * `pocketshellCi=true` instrumentation argument is set only from the CI
 * workflow, matching [TerminalTestTimeouts].
 */
object SshSetupTimeouts {
    private const val LOCAL_REMOTE_SETUP_TIMEOUT_MS: Long = 30_000L
    private const val CI_REMOTE_SETUP_TIMEOUT_MS: Long = 90_000L

    fun remoteSetupTimeoutMs(): Long =
        if (TerminalTestTimeouts.isRunningOnCi()) {
            CI_REMOTE_SETUP_TIMEOUT_MS
        } else {
            LOCAL_REMOTE_SETUP_TIMEOUT_MS
        }
}

/**
 * Issue #177: wipe the `last_session` fast-resume snapshot so a connected
 * test that backgrounds an active tmux session (saving a blob on the way
 * out via `MainActivity.onStop`) cannot leak that blob into a later test in
 * the same instrumentation JVM that expects a clean "close + relaunch lands
 * on the host list" start.
 *
 * Every emulator-run shares one app-data context (the instrumentation runs
 * in the target process — see `ColdInstallE2eTest.resetAppToColdInstallState`
 * for why we cannot `pm clear`), so `last_session` survives across test
 * classes unless explicitly cleared. The `MainActivity` route-restore is
 * already gated on `savedInstanceState != null` (it only fires on a genuine
 * process-death resume, never on a fresh `ActivityScenario.launch`), but
 * clearing the prefs in test setup is the belt-and-braces isolation the
 * reviewer requires so no test can depend on, or be polluted by, a sibling's
 * leftover snapshot.
 *
 * The prefs file name mirrors `LastSessionStore.PREFS_NAME` (`"last_session"`);
 * it is duplicated here rather than exposed as `internal` because the store
 * lives in `:app` main and this helper lives in androidTest — keeping the
 * constant private to the store preserves its encapsulation, and a one-line
 * string mirror is cheaper than widening the production API surface for a
 * test-only concern.
 */
fun clearLastSessionPrefs() {
    val ctx = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    ctx.getSharedPreferences("last_session", Context.MODE_PRIVATE)
        .edit()
        .clear()
        .commit()
}

/**
 * Issue #468 (blocker #1): pre-grant every runtime permission MainActivity
 * (or a downstream screen) may request so that the system
 * `GrantPermissionsActivity` never pops over MainActivity at launch and
 * steals window focus from the Compose hierarchy.
 *
 * On a freshly rebooted emulator the install-time grants survive but the
 * **runtime** dangerous permissions (`POST_NOTIFICATIONS`, `RECORD_AUDIO`,
 * `CAMERA`) are reset to "ask". MainActivity requests `POST_NOTIFICATIONS`
 * on the Android-13+ path at launch (`MainActivity.requestNotificationPermission`),
 * and voice (`RECORD_AUDIO`) / QR (`CAMERA`) screens request the other two.
 * If any of those system dialogs is on screen when the test queries the
 * Compose tree, the runner throws `IllegalStateException: No compose
 * hierarchies found in the app` (the reviewer's blocker #1). Granting them
 * up-front via `UiAutomation` mirrors `ForwardingIndicatorE2eTest` and
 * `TmuxDetectedPortForwardDockerTest` and removes that focus theft.
 *
 * Each grant is wrapped in `runCatching` so a device that has already
 * granted (or does not declare) a given permission is a no-op rather than a
 * hard failure. Must be called BEFORE `ActivityScenario.launch`.
 */
fun preGrantRuntimePermissions() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val packageName = instrumentation.targetContext.packageName
    val permissions = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        add(android.Manifest.permission.RECORD_AUDIO)
        add(android.Manifest.permission.CAMERA)
    }
    permissions.forEach { permission ->
        runCatching {
            instrumentation.uiAutomation.grantRuntimePermission(packageName, permission)
        }
    }
}

object WalkthroughScreenshotArtifacts {
    const val DEVICE_DIR_NAME: String = "walkthrough-visual-pass"

    fun capture(name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(300)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/$DEVICE_DIR_NAME")
        check(dir.exists() || dir.mkdirs()) {
            "Could not create screenshot directory: ${dir.absolutePath}"
        }
        val file = File(dir, "$name.png")
        FileOutputStream(file).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                "Could not write screenshot: ${file.absolutePath}"
            }
        }
        bitmap.recycle()
        println("WALKTHROUGH_SCREENSHOT ${file.absolutePath}")
        return file
    }
}
