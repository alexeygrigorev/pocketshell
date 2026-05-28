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
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
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
 * Issue #173 — regression test for the v0.2.7 crash on resume after a
 * pause-only lifecycle transition.
 *
 * Reproduces the user-reported sequence on the deterministic Docker `agents`
 * fixture:
 *
 *  1. Attach to a seeded tmux session through the normal app journey
 *     (host picker -> session picker -> Attach), proving the live
 *     control channel + per-pane TerminalView is in place.
 *  2. Send a marker command to confirm the wire is alive end-to-end.
 *  3. `ActivityScenario.moveToState(Lifecycle.State.STARTED)` to mimic a
 *     pause-only interruption (e.g. a transient overlay) while keeping the
 *     process foregrounded.
 *  4. From a sidecar SSH session, identify the sshd worker process that
 *     belongs to the app's tmux-CC connection and `kill -9` it. This
 *     reproduces the kernel-side ECONNABORTED ("Software caused connection
 *     abort") that sshj's `Reader.run` blocking `SocketInputStream.read`
 *     observes when Android (or any peer) tears down the TCP socket while
 *     a read is in flight. Killing the remote sshd is the closest
 *     deterministic local trigger to what Android did to the user.
 *  5. `ActivityScenario.moveToState(Lifecycle.State.RESUMED)` to mimic the
 *     user returning from the interruption.
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
 *  - The activity must still be alive after resume (no crash; no
 *    `Thread.UncaughtExceptionHandler` was driven). Without the fix the
 *    SSHException from `Reader.run` escapes the StandaloneCoroutine that
 *    owns `stdout.collect` in [TerminalSurfaceState.attachExternalProducer]
 *    and crashes the app exactly the way the maintainer reported on the
 *    Pixel 7a / Android 16 device.
 *  - The visible Compose tree must show a "Disconnected from ..." status
 *    line so the user can see the failure instead of staring at a frozen
 *    terminal. [TmuxSessionScreen] renders a `FailedConnectionRow` for
 *    `ConnectionStatus.Failed`; we assert the text contains the
 *    "Disconnected from" marker the TmuxSessionViewModel writes in its
 *    `attachClient` `client.disconnected` observer (unified by #145
 *    round-3 across the previous "connection lost: ..." wording).
 *
 * Artifact contract (see process.md "Terminal Artifact Review"):
 *
 *  - `issue173-01-attached-viewport.png` + `-visible-terminal.txt` — proof
 *    the tmux session attached cleanly before the test broke the socket.
 *  - `issue173-02-after-resume-viewport.png` +
 *    `-visible-terminal.txt` — proof the app survived the resume with no
 *    crash.
 *  - `timings.txt` — pause-to-kill, kill-to-resume, resume-to-failed-status
 *    timings so a reviewer can tell apart "responsiveness regressed" from
 *    "didn't crash but UI never updated".
 */
@RunWith(AndroidJUnit4::class)
class BackgroundResumeSocketDeathE2eTest {

    @get:Rule
    val compose = createEmptyComposeRule()

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
        val initialTerminal = visibleTerminalText()
        assertTrue(
            "expected visible terminal text after attach (was empty), got len=${initialTerminal.length}",
            initialTerminal.isNotBlank(),
        )

        // ---- (3) Snapshot the sshd PIDs again. Any new PID compared to
        // [baselineSshdPids] is the app's tmux-CC connection.
        val attachedPids = listSshdPidsForTestuser(key)
        val appSshdPids = attachedPids - baselineSshdPids
        Log.i(LOG_TAG, "post-attach sshd PIDs: $attachedPids ; app PIDs = $appSshdPids")
        assertTrue(
            "expected at least one new sshd worker PID for the app's connection; baseline=$baselineSshdPids attached=$attachedPids",
            appSshdPids.isNotEmpty(),
        )

        // ---- (4) Pause the activity without stopping the process.
        // Moving to CREATED would now trigger the issue #235
        // ProcessLifecycleOwner.ON_STOP auto-detach path, intentionally
        // closing the tmux control socket before this test can kill it.
        // STARTED drives onPause while keeping the process foregrounded,
        // preserving the live socket for the issue #173 regression.
        val pausedAt = SystemClock.elapsedRealtime()
        launchedActivity?.moveToState(Lifecycle.State.STARTED)
        delay(LIFECYCLE_DRAIN_MS)
        recordTiming("pause_drain_ms", SystemClock.elapsedRealtime() - pausedAt)

        // ---- (5) Kill the app's sshd worker from a sidecar SSH session.
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

        // ---- (6) Resume. Without the fix, the crashed coroutine's
        // exception would have already triggered the uncaught-exception
        // handler and the ActivityScenario would now be DESTROYED.
        val resumeAt = SystemClock.elapsedRealtime()
        launchedActivity?.moveToState(Lifecycle.State.RESUMED)
        recordTiming("resume_drain_ms", SystemClock.elapsedRealtime() - resumeAt)

        // ---- (7) Assert: no crash. The activity must still exist and be
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

        // ---- (8) Assert: the user sees a clear "connection lost" state.
        // [TmuxSessionViewModel] flips _connectionStatus to Failed on
        // eventsJob completion with cause; [TmuxSessionScreen] renders a
        // StatusLine for the Failed branch. We poll for the canonical
        // marker substring.
        val failedDeadline = SystemClock.elapsedRealtime() + FAILED_STATUS_TIMEOUT_MS
        var failedVisible = false
        while (SystemClock.elapsedRealtime() < failedDeadline) {
            failedVisible = compose.onAllNodesWithText(
                CONNECTION_LOST_MARKER,
                substring = true,
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty()
            if (failedVisible) break
            SystemClock.sleep(100)
        }
        recordTiming(
            "resume_to_failed_status_ms",
            SystemClock.elapsedRealtime() - resumeAt,
        )
        captureViewport("issue173-02-after-resume")
        assertTrue(
            "expected `$CONNECTION_LOST_MARKER` (or similar) status line within ${FAILED_STATUS_TIMEOUT_MS}ms of resume; " +
                "the fix should route the SSHException via flow termination -> Failed connection state",
            failedVisible,
        )

        writeTimings()
        Unit
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
        val mediaRoot = instrumentation.targetContext.externalMediaDirs
            .firstOrNull { it != null }
            ?: instrumentation.targetContext.getExternalFilesDir(null)
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
         * connection-status message. Issue #145 unified the disconnect
         * wording across `client.disconnected` observer paths to read
         * `"Disconnected from <user>@<host>:<port>. Tap Reconnect to
         * retry."` so this marker matches that prefix. The old
         * `"connection lost: tmux control channel closed"` wording was
         * replaced because the new one is actionable (the user knows
         * to tap Reconnect) while still being scope-faithful to the
         * #173 regression: the user sees a Failed status, not a crash.
         */
        const val CONNECTION_LOST_MARKER: String = "Disconnected from"

        /**
         * After moveToState(CREATED) we wait this long for the lifecycle
         * dispatcher to drain (ON_PAUSE + ON_STOP) so the kill lands on a
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
         * Ceiling on how long we wait after resume for the
         * ConnectionStatus.Failed StatusLine to become visible. The
         * eventsJob.invokeOnCompletion handler runs synchronously when
         * the flow ends, so the state should flip immediately and
         * Compose recomposition lands within a frame; 5 s leaves
         * generous head-room for the lifecycle resume path on a CI
         * emulator.
         */
        const val FAILED_STATUS_TIMEOUT_MS: Long = 5_000L
    }
}
