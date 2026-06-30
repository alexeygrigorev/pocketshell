package com.pocketshell.app.proof

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.tmux.TMUX_SESSION_ERROR_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_RECONNECT_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.app.tmux.TmuxSessionViewModel
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.termux.view.TerminalView
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #173 / #1098 (item 3) — regression test for the crash-on-resume AND the
 * "frozen-but-live screen on a genuinely DEAD host" symptom, on the deterministic
 * Docker `agents` fixture.
 *
 * Reproduces the user-reported sequence:
 *
 *  1. Attach to a seeded tmux session through the normal app journey
 *     (host picker -> session picker -> Attach), proving the live
 *     control channel + per-pane TerminalView is in place.
 *  2. Confirm the wire is alive end-to-end (visible terminal text).
 *  3. ARM the genuinely-unrecoverable-host seam
 *     ([TmuxSessionViewModel.forceUnrecoverableHostForTest]) + compress the
 *     reconnect timings. This is the #1098-item-3 fixture for the maintainer's
 *     real symptom: a kill-the-worker-only fixture leaves the sshd LISTENER up, so
 *     the items-1+2 silent fresh-transport re-dial RECOVERS (the calm ride-through
 *     — round-3 finding) and no band ever surfaces. The seam makes BOTH the
 *     silent-reattach grace loop AND the auto-reconnect ladder's fresh dial
 *     fail-fast, modelling a host that is truly gone, so the bounded ladder
 *     genuinely EXHAUSTS.
 *  4. `moveToState(Lifecycle.State.STARTED)` to mimic a pause-only interruption
 *     while keeping the process foregrounded.
 *  5. From a sidecar SSH session, identify the sshd worker that belongs to the
 *     app's tmux-CC connection and `kill -9` it — the real kernel-side
 *     ECONNABORTED sshj's `Reader.run` observes on the device (the #173 real
 *     SSHException). With the seam armed every recovery attempt fails, so the
 *     drop is genuinely unrecoverable instead of silently healing.
 *  6. `moveToState(Lifecycle.State.RESUMED)` to mimic the user returning.
 *
 * Note: this intentionally does not move to [Lifecycle.State.CREATED].
 * The issue #235 production behavior auto-detaches tmux `-CC` clients on
 * process background / `ON_STOP`; that full-background path has its own
 * E2E. This test needs the control socket to remain live while paused so
 * killing the remote sshd still exercises the issue #173 sshj
 * `SSHException` -> flow termination -> Failed connection state route.
 *
 * Acceptance:
 *
 *  - The activity must still be alive after resume (no crash; the #173 contract).
 *  - On GENUINE ladder exhaustion the visible Compose tree must show the
 *    disconnect band ([TMUX_SESSION_ERROR_TAG] `FailedConnectionRow` + a
 *    "Tap to reconnect" affordance) whose message reads the unified #145
 *    "Disconnected from <user>@<host>:<port>." wording — so the user sees a clear
 *    disconnected indicator instead of a frozen-but-live terminal.
 *  - NO false-alarm permanence: once the host is reachable again (seam disarmed)
 *    tapping Reconnect recovers the SAME session — proving the band is the honest,
 *    recoverable error and the fix did not turn a recoverable blip into a dead end.
 *
 * Artifact contract (see process.md "Terminal Artifact Review"):
 *
 *  - `issue173-01-attached-viewport.png` + `-visible-terminal.txt` — proof
 *    the tmux session attached cleanly before the test broke the socket.
 *  - `issue173-02-after-resume-viewport.png` +
 *    `-visible-terminal.txt` — proof the app survived the resume with no
 *    crash, with the disconnect band surfaced.
 *  - `timings.txt` — pause-to-kill, kill-to-resume, resume-to-failed-status
 *    timings so a reviewer can tell apart "responsiveness regressed" from
 *    "didn't crash but UI never updated".
 */
@RunWith(AndroidJUnit4::class)
class BackgroundResumeSocketDeathE2eTest {

    // The #173 reproduction needs MANUAL ActivityScenario lifecycle control (moveToState
    // STARTED -> kill -> RESUMED) to drive the pause-only socket-death sequence;
    // createAndroidComposeRule<MainActivity>() owns the launch and cannot drive that
    // pause/resume cycle, so the manual ActivityScenario harness is required here.
    @get:Rule
    // JOURNEY_HARNESS_JUSTIFIED: #173 pause/resume needs manual ActivityScenario (above).
    val compose = createEmptyComposeRule()

    // Issue #470 blocker #1: grant runtime permissions before the activity
    // launches so the system GrantPermissionsActivity never steals focus
    // from the Compose hierarchy ("No compose hierarchies found").
    @get:Rule
    val grantPermissions = PreGrantPermissionsRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null
    private val timings = mutableListOf<String>()

    @After
    fun closeLaunchedActivity() {
        launchedActivity?.close()
        launchedActivity = null
        runBlocking {
            runCatching { cleanupRemoteTmuxSession(readFixtureKey()) }
        }
    }

    @Test
    fun socketDeathDuringPauseDoesNotCrashOnResume() = runBlocking {
        val key = readFixtureKey()
        waitForSshFixtureReady(SshKey.Pem(key))

        // Seed the tmux session before the app attaches so the picker has
        // it ready and the test is hermetic against earlier runs.
        seedTmuxSession(key)

        // Snapshot the sshd PIDs that exist BEFORE the app connects, so we
        // can identify the app's worker PID by set-difference once it
        // attaches. The Docker fixture has exactly one persistent sshd
        // listener at boot; any new `sshd: testuser@...` worker is from a
        // login.
        val baselineSshdPids = listSshdPidsForTestuser(key).also {
            Log.i(LOG_TAG, "baseline sshd PIDs for testuser: $it")
        }

        val hostRowTag = seedDockerHost(key)
        // JOURNEY_HARNESS_JUSTIFIED: manual ActivityScenario is required for the #173
        // pause -> kill -> resume lifecycle sequence (see the class-level rule comment).
        launchedActivity = ActivityScenario.launch(MainActivity::class.java)

        // ---- (1) Tap host, then attach to the seeded session.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        waitForText(SEEDED_SESSION, timeoutMs = 20_000)
        compose.onNodeWithText(SEEDED_SESSION).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        waitForTerminalViewAttached()
        captureViewport("issue173-01-attached")

        // ---- (2) Sanity-check the wire is alive end-to-end before we
        // break it. We don't drive input via the IME path because the
        // crash repro is the SOCKET dying, not anything to do with input —
        // just observing that the terminal emulator has bytes from the
        // remote is enough to prove the reader path is running.
        compose.waitUntil(timeoutMillis = 20_000) {
            visibleTerminalText().isNotBlank()
        }
        val initialTerminal = visibleTerminalText()
        assertTrue(
            "expected visible terminal text after attach (was empty), got len=${initialTerminal.length}",
            initialTerminal.isNotBlank(),
        )

        // ---- (3) ARM the genuinely-unrecoverable-host fixture + compress the
        // reconnect timings (issue #1098 item 3). Without this, the kill-the-worker
        // step below leaves the sshd LISTENER up, so the items-1+2 silent fresh-
        // transport re-dial RECOVERS (calm ride-through) and no band ever surfaces
        // — the round-3 finding that the app is RIGHT but the fixture can't enter the
        // failing state. The seam makes BOTH the silent-reattach grace loop and the
        // auto-reconnect ladder's fresh dial fail-fast, so the bounded ladder
        // genuinely exhausts to the honest "Disconnected from …" band. The compressed
        // grace/ladder timings keep that exhaustion inside the test budget (production
        // keeps the 60s grace / backoff defaults).
        setUnrecoverableHostForTest(true)
        setReconnectTimingForTest()

        // ---- (4) Snapshot the sshd PIDs again. Any new PID compared to
        // [baselineSshdPids] is the app's tmux-CC connection.
        val attachedPids = listSshdPidsForTestuser(key)
        val appSshdPids = attachedPids - baselineSshdPids
        Log.i(LOG_TAG, "post-attach sshd PIDs: $attachedPids ; app PIDs = $appSshdPids")
        assertTrue(
            "expected at least one new sshd worker PID for the app's connection; baseline=$baselineSshdPids attached=$attachedPids",
            appSshdPids.isNotEmpty(),
        )

        // ---- (5) Pause the activity without stopping the process.
        // Moving to CREATED would now trigger the issue #235
        // ProcessLifecycleOwner.ON_STOP auto-detach path, intentionally
        // closing the tmux control socket before this test can kill it.
        // STARTED drives onPause while keeping the process foregrounded,
        // preserving the live socket for the issue #173 regression.
        val pausedAt = SystemClock.elapsedRealtime()
        launchedActivity?.moveToState(Lifecycle.State.STARTED)
        delay(LIFECYCLE_DRAIN_MS)
        recordTiming("pause_drain_ms", SystemClock.elapsedRealtime() - pausedAt)

        // ---- (6) Kill the app's sshd worker from a sidecar SSH session.
        // The Docker testuser is non-root, so we can only kill processes
        // we own — sshd worker processes are owned by the logged-in user,
        // which is exactly what we want. `kill -9` causes the TCP socket
        // to be released abruptly without a clean FIN, producing the
        // ECONNABORTED that sshj's Reader.run observes on the device.
        val killAt = SystemClock.elapsedRealtime()
        killRemoteSshdPids(key, appSshdPids)
        recordTiming("socket_kill_ms", SystemClock.elapsedRealtime() - killAt)
        Log.i(LOG_TAG, "killed app sshd PIDs: $appSshdPids")

        // Give the kernel and sshj's Reader thread a beat to notice the
        // socket tear-down while we're still paused. This is the window
        // where the Reader thread's blocking read throws SSHException; the
        // fix routes that exception into a clean flow termination and a
        // ConnectionStatus.Failed transition.
        delay(POST_KILL_DRAIN_MS)

        // ---- (7) Resume. Without the fix, the crashed coroutine's
        // exception would have already triggered the uncaught-exception
        // handler and the ActivityScenario would now be DESTROYED.
        val resumeAt = SystemClock.elapsedRealtime()
        launchedActivity?.moveToState(Lifecycle.State.RESUMED)
        recordTiming("resume_drain_ms", SystemClock.elapsedRealtime() - resumeAt)

        // ---- (8) Assert: no crash. The activity must still exist and be
        // navigable. Reaching findTerminalView via onActivity proves the
        // scenario is still alive.
        var activityAlive = false
        launchedActivity?.onActivity { _ ->
            activityAlive = true
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertTrue(
            "expected MainActivity to still be alive after resume from socket-death; the crash repro should be neutralized by the fix",
            activityAlive,
        )

        // ---- (9) Assert: on GENUINE ladder exhaustion the user sees the disconnect
        // BAND — the rendered [TMUX_SESSION_ERROR_TAG] FailedConnectionRow + the
        // "Tap to reconnect" affordance — and its message is the unified #145
        // "Disconnected from …" wording. This is the user-visible contract: a clear
        // disconnected indicator instead of a frozen-but-live terminal. (Asserting the
        // rendered band tag is the robust contract; the "Disconnected from" wording is
        // the maintainer's requested clarity. Both are checked.)
        val failedDeadline = SystemClock.elapsedRealtime() + FAILED_STATUS_TIMEOUT_MS
        var bandVisible = false
        while (SystemClock.elapsedRealtime() < failedDeadline) {
            bandVisible = compose.onAllNodesWithTag(TMUX_SESSION_ERROR_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
            if (bandVisible) break
            SystemClock.sleep(100)
        }
        recordTiming(
            "resume_to_failed_status_ms",
            SystemClock.elapsedRealtime() - resumeAt,
        )
        captureViewport("issue173-02-after-resume")
        assertTrue(
            "expected the disconnect band ([$TMUX_SESSION_ERROR_TAG] FailedConnectionRow) within " +
                "${FAILED_STATUS_TIMEOUT_MS}ms of resume; the fix should route the genuine ladder " +
                "exhaustion -> Failed connection state -> disconnect band",
            bandVisible,
        )
        val disconnectMarkerVisible = compose.onAllNodesWithText(
            CONNECTION_LOST_MARKER,
            substring = true,
            useUnmergedTree = true,
        ).fetchSemanticsNodes().isNotEmpty()
        assertTrue(
            "expected the disconnect band message to contain `$CONNECTION_LOST_MARKER` " +
                "(the unified #145 wording) so the user sees a clear disconnected indicator",
            disconnectMarkerVisible,
        )

        // ---- (10) NO false-alarm permanence: the band is the HONEST, recoverable
        // error — not a dead end. Disarm the unrecoverable seam (the host is reachable
        // again — the listener was always up) and tap Reconnect; the SAME session must
        // recover and the band must clear. This is the adjacency guarantee that the fix
        // surfaces the band ONLY on genuine exhaustion and a recoverable host still heals.
        setUnrecoverableHostForTest(false)
        val reconnectAt = SystemClock.elapsedRealtime()
        compose.onNodeWithTag(TMUX_SESSION_RECONNECT_TAG, useUnmergedTree = true).performClick()
        val recovered = runCatching {
            compose.waitUntil(timeoutMillis = RECOVER_TIMEOUT_MS) {
                compose.onAllNodesWithTag(TMUX_SESSION_ERROR_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isEmpty()
            }
            true
        }.getOrDefault(false)
        recordTiming("reconnect_recover_ms", SystemClock.elapsedRealtime() - reconnectAt)
        assertTrue(
            "expected the SAME session to recover (band cleared) within ${RECOVER_TIMEOUT_MS}ms of " +
                "tapping Reconnect once the host is reachable again — the disconnect band must be a " +
                "recoverable honest error, not a permanent dead end (no false-alarm regression)",
            recovered,
        )

        writeTimings()
        Unit
    }

    /** Arm/disarm the genuinely-unrecoverable-host seam on the live VM (main thread). */
    private fun setUnrecoverableHostForTest(value: Boolean) {
        launchedActivity?.onActivity { activity ->
            ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
                .forceUnrecoverableHostForTest = value
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    /** Compress the passive-disconnect grace + auto-reconnect ladder so a genuine
     *  exhaustion lands inside the test budget. Production keeps its defaults. */
    private fun setReconnectTimingForTest() {
        launchedActivity?.onActivity { activity ->
            val vm = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
            vm.setPassiveDisconnectRecoveryForTest(
                graceMs = GRACE_MS,
                silentReattachTimeoutMs = REATTACH_TIMEOUT_MS,
            )
            vm.setAutoReconnectDelaysForTest(listOf(0L, 0L, 0L, 0L))
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    // ---------------------------------------------------------------- Helpers

    private fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }

    private suspend fun seedDockerHost(key: String): String {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        return try {
            db.clearAllTables()
            val storedKey = SshKeyStorage.persistKey(
                context = appContext,
                sshKeyDao = db.sshKeyDao(),
                name = "issue173-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = "Issue173 SocketDeath",
                    hostname = DEFAULT_HOST,
                    port = DEFAULT_PORT,
                    username = DEFAULT_USER,
                    keyId = storedKey.id,
                    tmuxInstalled = true,
                    lastBootstrapAt = System.currentTimeMillis(),
                ),
            )
            HOST_ROW_TAG_PREFIX + hostId
        } finally {
            db.close()
        }
    }

    private suspend fun seedTmuxSession(key: String) {
        // Detached session with a long-running sleep so the session
        // survives across attach/detach without exiting. The marker line
        // gives us something to spot in the visible-terminal sidecar.
        val script = buildString {
            appendLine("set -eu")
            appendLine("tmux kill-session -t ${shellQuote(SEEDED_SESSION)} 2>/dev/null || true")
            appendLine(
                "tmux new-session -d -s ${shellQuote(SEEDED_SESSION)} " +
                    "${shellQuote("printf 'ISSUE173-READY\\n'; exec sleep 600")}",
            )
            appendLine("sleep 1")
            appendLine("tmux list-sessions")
        }
        val result = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session ->
            session.use { it.exec(script) }
        }
        val exec = result.getOrNull()
        assertTrue(
            "expected tmux seeding to succeed; exception=${result.exceptionOrNull()} stderr='${exec?.stderr}'",
            exec?.exitCode == 0,
        )
        Log.i(LOG_TAG, "seeded session: ${exec?.stdout?.trim()}")
    }

    private suspend fun cleanupRemoteTmuxSession(key: String) {
        runCatching {
            SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(key),
                knownHosts = KnownHostsPolicy.AcceptAll,
                timeoutMs = 15_000,
            ).mapCatching { session ->
                session.use {
                    it.exec("tmux kill-session -t ${shellQuote(SEEDED_SESSION)} 2>/dev/null || true")
                }
            }
        }
    }

    /**
     * List sshd worker PIDs that belong to a logged-in `testuser` session.
     * We use `pgrep -u testuser sshd` which returns every sshd process
     * owned by the user — for openssh that's one worker per active SSH
     * connection, post-privilege-drop. The privileged listener (pid 1's
     * child in the Docker image) is owned by `root` and does not show up
     * in this list.
     *
     * The sidecar SSH session this command runs over is itself one of the
     * PIDs returned. To identify the *app's* sshd later we subtract
     * `baselineSshdPids` (recorded before the app attached) from the
     * post-attach set.
     */
    private suspend fun listSshdPidsForTestuser(key: String): Set<Int> {
        val result = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session ->
            session.use {
                // `pgrep -u testuser sshd` is the simplest portable form
                // and works on the Alpine fixture (procps-ng).
                it.exec("pgrep -u testuser sshd 2>/dev/null || true")
            }
        }
        val out = result.getOrNull()?.stdout.orEmpty()
        return out.lines()
            .mapNotNull { it.trim().toIntOrNull() }
            .toSet()
    }

    /**
     * `kill -9` the given sshd worker PIDs from a sidecar SSH session.
     * The sshd workers are owned by testuser; testuser can kill them
     * without sudo. The forced kill causes the TCP socket to be closed
     * abruptly, producing the ECONNABORTED the on-device crash report
     * captured.
     */
    private suspend fun killRemoteSshdPids(key: String, pids: Set<Int>) {
        if (pids.isEmpty()) return
        val script = buildString {
            for (pid in pids) {
                appendLine("kill -9 $pid 2>/dev/null || true")
            }
        }
        runCatching {
            SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(key),
                knownHosts = KnownHostsPolicy.AcceptAll,
                timeoutMs = 15_000,
            ).mapCatching { session ->
                session.use { it.exec(script) }
            }
        }
    }

    private fun waitForText(text: String, timeoutMs: Long) {
        compose.waitUntil(timeoutMillis = timeoutMs) {
            compose.onAllNodesWithText(text, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun waitForTerminalViewAttached() {
        compose.waitUntil(timeoutMillis = 30_000) {
            var attached = false
            launchedActivity?.onActivity { activity ->
                val view = activity.window.decorView.findTerminalView()
                attached = view?.currentSession != null && view.mEmulator != null
            }
            attached
        }
    }

    private fun visibleTerminalText(): String {
        var text = ""
        launchedActivity?.onActivity { activity ->
            text = activity.window.decorView
                .findTerminalView()
                ?.currentSession
                ?.emulator
                ?.screen
                ?.transcriptText
                .orEmpty()
        }
        return text
    }

    private fun captureViewport(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)

        var bitmap: Bitmap? = null
        launchedActivity?.onActivity { activity ->
            val view = activity.window.decorView.findTerminalView() ?: return@onActivity
            if (view.width <= 0 || view.height <= 0) return@onActivity
            val b = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(b))
            bitmap = b
        }
        bitmap?.let { writeBitmap("$name-viewport", it) }
        writeText("$name-visible-terminal.txt", visibleTerminalText())
        bitmap?.recycle()
    }

    private fun writeBitmap(name: String, bitmap: Bitmap): File {
        val file = artifactFile("$name.png")
        FileOutputStream(file).use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                "failed to write bitmap to ${file.absolutePath}"
            }
        }
        println("ISSUE173_VIEWPORT ${file.absolutePath}")
        return file
    }

    private fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("ISSUE173_TEXT ${file.absolutePath}")
        return file
    }

    private fun writeTimings(): File {
        val file = artifactFile("timings.txt")
        file.writeText(timings.joinToString(separator = "\n", postfix = "\n"))
        println("ISSUE173_TIMINGS ${file.absolutePath}")
        return file
    }

    private fun artifactFile(name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/$DEVICE_DIR_NAME")
        check(dir.exists() || dir.mkdirs()) {
            "could not create artifact directory ${dir.absolutePath}"
        }
        return File(dir, name)
    }

    private fun recordTiming(name: String, value: Long) {
        val line = "$name=$value"
        timings += line
        println("ISSUE173_TIMING $line")
    }

    private fun View.findTerminalView(): TerminalView? {
        if (this is TerminalView) return this
        if (this !is ViewGroup) return null
        for (index in 0 until childCount) {
            val match = getChildAt(index).findTerminalView()
            if (match != null) return match
        }
        return null
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
        const val LOG_TAG: String = "Issue173SocketDeath"
        const val DEVICE_DIR_NAME: String = "issue173-background-resume-socket-death"
        // The picker reads its session list from the deterministic
        // `tmuxctl list` shim shipped with the Docker `agents` fixture,
        // not from `tmux list-sessions`. The shim only knows the three
        // pre-baked names (`claude-main`, `codex`, `opencode-lab`); any
        // other name we seed via real `tmux new-session` would not show
        // up in the picker. We pick `claude-main` because the existing
        // `TmuxSessionSwitchE2eTest` already uses it as its primary
        // attach target, so the surface is well-exercised.
        const val SEEDED_SESSION: String = "claude-main"

        /**
         * Substring rendered by [TmuxSessionViewModel] in the Failed
         * connection-status (disconnect band) message. Issue #145 unified the
         * disconnect wording to `"Disconnected from <user>@<host>:<port>. Tap
         * Reconnect to retry."`; issue #1098 item 3 restores that unified wording on
         * the genuine auto-reconnect-ladder exhaustion path (it had regressed to the
         * jargon-y "Transport EOF …; reconnecting. Auto reconnect failed after N
         * attempts."). The user sees a clear, actionable disconnected indicator
         * instead of a frozen-but-live terminal.
         */
        const val CONNECTION_LOST_MARKER: String = "Disconnected from"

        /**
         * After moveToState(STARTED) we wait this long for the lifecycle
         * dispatcher to drain (ON_PAUSE) so the kill lands on a
         * cleanly paused activity.
         */
        const val LIFECYCLE_DRAIN_MS: Long = 750L

        /**
         * After the sshd kill we wait this long while the activity is
         * still paused so the Reader thread's blocking read fully
         * observes the socket tear-down and propagates through the
         * coroutine layer. Empirically ~1 s is enough; we hold for 2 s to
         * stay comfortably above CI swiftshader noise.
         */
        const val POST_KILL_DRAIN_MS: Long = 2_000L

        /**
         * Compressed passive-disconnect grace window (issue #1098 item 3). Must
         * EXCEED nothing in particular — with the unrecoverable seam armed every
         * re-dial fails fast, so the grace loop bounds itself here and then hands off
         * to the auto-reconnect ladder. Kept a few seconds so the genuine exhaustion
         * lands well inside [FAILED_STATUS_TIMEOUT_MS]. Production keeps the 60s default.
         */
        val GRACE_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 3_000L else 2_000L

        /** Per-attempt reattach timeout — short so each failed re-dial returns fast. */
        const val REATTACH_TIMEOUT_MS: Long = 2_000L

        /**
         * Ceiling on how long we wait after resume for the disconnect band to become
         * visible. The grace loop (≤[GRACE_MS]) + the four-rung ladder (all failing
         * fast against the unrecoverable seam) settle well within this window; the
         * generous CI value absorbs swiftshader scheduling jitter.
         */
        val FAILED_STATUS_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 20_000L

        /** Ceiling on the post-disarm Reconnect recovery (the SAME session heals). */
        val RECOVER_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 45_000L else 30_000L
    }
}
