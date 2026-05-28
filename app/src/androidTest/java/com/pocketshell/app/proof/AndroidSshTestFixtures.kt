package com.pocketshell.app.proof

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import kotlinx.coroutines.Dispatchers
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

const val DEFAULT_HOST: String = "10.0.2.2"
const val DEFAULT_PORT: Int = 2222
const val DEFAULT_USER: String = "testuser"

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

object WalkthroughScreenshotArtifacts {
    const val DEVICE_DIR_NAME: String = "walkthrough-visual-pass"

    fun capture(name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(300)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val mediaRoot = instrumentation.targetContext.externalMediaDirs
            .firstOrNull { it != null }
            ?: instrumentation.targetContext.getExternalFilesDir(null)
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
