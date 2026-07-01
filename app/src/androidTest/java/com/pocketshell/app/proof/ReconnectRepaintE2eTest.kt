package com.pocketshell.app.proof

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.tmux.TMUX_CONNECTING_PROGRESS_TAG
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #553: after a reconnect on an already-open tmux session, the terminal
 * must restore the FULL visible pane content — not be left blank with only the
 * live incremental output (e.g. an agent's per-second status/timer line)
 * painting against black.
 *
 * ## Why this is a real regression, not theoretical
 *
 * PocketShell drives the terminal from tmux `-CC` control-mode incremental
 * `%output` events. tmux does NOT re-emit a pane's existing content when a
 * fresh control client attaches — verified directly against tmux: a reattach
 * yields only the subsequent `\033[..H` rewrites (the live timer), never the
 * static frame already on screen, and `refresh-client` does not force a
 * re-dump. So after a reconnect the local emulator is empty and only the live
 * deltas draw — the exact "blank except a timer" screenshot the maintainer
 * reported. The `capture-pane` re-seed is the only thing that can restore the
 * prior screen, and #553 makes that re-seed fire on EVERY reconnect.
 *
 * ## Scenario
 *
 *  1. Seed a tmux session running a tiny alternate-screen TUI: a static
 *     [REPAINT_MARKER] banner plus a live per-second timer line.
 *  2. Attach through the normal app journey. Assert the static banner is
 *     visible (baseline).
 *  3. `kill -9` the app's sshd worker from a sidecar SSH session — a genuine
 *     wire-level transport death. The remote tmux server (and the TUI) stay
 *     alive; only the app's `-CC` control socket dies, so the #444
 *     auto-reconnect path re-attaches from scratch (the same code path the
 *     manual Reconnect button reaches via `runConnect`).
 *  4. After auto-recovery, assert the static [REPAINT_MARKER] banner is
 *     REPAINTED into the viewport — proving the reconnect re-seeded the full
 *     pane content rather than leaving it blank.
 *
 * Full network-drop ride-through verification (a clean link cut rather than a
 * worker kill) is a follow-up once the #552 toxiproxy link-cut harness lands;
 * the worker-kill here is the deterministic reconnect this CI fixture allows.
 *
 * Uses the deterministic `agents` fixture (`agents:2222`, the standard CI
 * service — no extra fixture), mirroring [BackgroundResumeSocketDeathE2eTest].
 *
 * Artifact contract (see process.md "Terminal Artifact Review"):
 *
 *  - `issue553-01-attached-viewport.png` + `-visible-terminal.txt`
 *  - `issue553-02-after-reconnect-viewport.png` + `-visible-terminal.txt`
 *  - `timings.txt`
 */
@RunWith(AndroidJUnit4::class)
class ReconnectRepaintE2eTest {

    // Issue #788: createAndroidComposeRule<MainActivity>() (not
    // createEmptyComposeRule + hand-rolled ActivityScenario.launch) fixes the
    // #470 swiftshader interop-placement stall. The rule launches MainActivity in
    // its `before()`, so the DB host row + remote session are seeded BEFORE launch
    // by [SeedBeforeLaunchRule] in the chain.
    val compose = createAndroidComposeRule<MainActivity>()

    // Issue #470 blocker #1 (grant) + #788 seed-before-launch ordering:
    //   grant perms -> seed remote session + DB host row -> launch MainActivity.
    @get:Rule
    val ruleChain: org.junit.rules.RuleChain = org.junit.rules.RuleChain
        .outerRule(PreGrantPermissionsRule())
        .around(SeedBeforeLaunchRule { seedBeforeLaunch() })
        .around(compose)

    private lateinit var fixtureKey: String
    private lateinit var hostRowTag: String

    // Baseline sshd workers captured BEFORE the app connects (in the seed phase),
    // so the test body can identify the app's worker by set-difference.
    private var baselineSshdPids: Set<Int> = emptySet()

    private val timings = mutableListOf<String>()

    @After
    fun closeLaunchedActivity() {
        // Issue #788: restore RESUMED before the rule's auto-close so close()
        // does not crash if the body left the scenario in a non-RESUMED state.
        runCatching { compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED) }
        runBlocking {
            if (::fixtureKey.isInitialized) {
                runCatching { cleanupRemoteTmuxSession(fixtureKey) }
            }
        }
    }

    /**
     * Issue #788: seed remote tmux session + DB host row BEFORE MainActivity
     * launches (run by [SeedBeforeLaunchRule]). Also captures the baseline sshd
     * worker PIDs before the app connects so the body's set-difference is valid.
     */
    private suspend fun seedBeforeLaunch() {
        val key = readFixtureKey()
        fixtureKey = key
        waitForSshFixtureReady(SshKey.Pem(key))
        baselineSshdPids = listSshdPidsForTestuser(key)
        seedAltScreenSession(key)
        hostRowTag = seedDockerHost(key)
    }

    @Test
    fun reconnectRepaintsFullPaneContentNotJustLiveDeltas() { runBlocking {
        val key = fixtureKey

        // ---- (1) Tap host, attach to the seeded session.
        waitForHostRowPresent(hostRowTag)
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        waitForText(SEEDED_SESSION, timeoutMs = 20_000)
        compose.onNodeWithText(SEEDED_SESSION).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        waitForTerminalViewAttached()

        // ---- (2) Baseline: the static banner is on screen (the seed/initial
        // attach already restores the full content). This is the content that
        // must survive a reconnect.
        waitForVisibleTerminalText("initial attach banner") { REPAINT_MARKER in it }
        captureViewport("issue553-01-attached")

        // ---- (3) Identify and kill the app's sshd worker — a real transport
        // death. The tmux server + TUI stay alive on the remote.
        val attachedPids = listSshdPidsForTestuser(key)
        val appSshdPids = attachedPids - baselineSshdPids
        assertTrue(
            "expected at least one new sshd worker for the app connection; " +
                "baseline=$baselineSshdPids attached=$attachedPids",
            appSshdPids.isNotEmpty(),
        )
        val killAt = SystemClock.elapsedRealtime()
        killRemoteSshdPids(key, appSshdPids)
        Log.i(LOG_TAG, "killed app sshd PIDs: $appSshdPids")

        // The transport death must clear the cached emulator content (the
        // teardown drops the per-pane buffers), so we first wait for the
        // banner to disappear OR the reconnecting progress row to surface —
        // either proves the drop registered — then assert it REPAINTS.
        var sawDropOrReconnecting = false
        val dropDeadline = SystemClock.elapsedRealtime() + DROP_OBSERVED_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < dropDeadline) {
            val reconnecting = compose
                .onAllNodesWithTag(TMUX_CONNECTING_PROGRESS_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
            val bannerGone = REPAINT_MARKER !in visibleTerminalText()
            if (reconnecting || bannerGone) {
                sawDropOrReconnecting = true
                break
            }
            SystemClock.sleep(100)
        }
        recordTiming("drop_observed_ms", SystemClock.elapsedRealtime() - killAt)
        Log.i(LOG_TAG, "sawDropOrReconnecting=$sawDropOrReconnecting")

        // ---- (4) Auto-reconnect re-attaches. THE ASSERTION: the static banner
        // is REPAINTED into the viewport. Before the #553 fix the emulator
        // would stay blank (only the live timer delta painting), so this wait
        // would time out.
        val repaintAt = SystemClock.elapsedRealtime()
        waitForVisibleTerminalText(
            "banner repainted after reconnect",
            timeoutMillis = REPAINT_TIMEOUT_MS,
        ) { REPAINT_MARKER in it }
        recordTiming("repaint_after_reconnect_ms", SystemClock.elapsedRealtime() - repaintAt)
        captureViewport("issue553-02-after-reconnect")

        // The session screen must still be up (a cleared banner that also lost
        // the screen would be a teardown, not a repaint).
        assertTrue(
            "tmux session screen must still be up after the reconnect repaint",
            compose.onAllNodesWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty(),
        )

        writeTimings()
        writeSummary()
        Unit
    } }

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
                name = "issue553-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = "Issue553 ReconnectRepaint",
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

    /**
     * Seed a tmux session whose pane runs a tiny alternate-screen TUI: a
     * static [REPAINT_MARKER] banner plus a live per-second timer. The
     * alternate screen + in-place timer rewrite mirrors how a real agent CLI
     * repaints, which is the exact case that goes blank on reconnect without
     * a `capture-pane` re-seed.
     */
    private suspend fun seedAltScreenSession(key: String) {
        // Single-quoted heredoc body so the ANSI escapes reach the remote shell
        // literally; `printf` on the remote interprets the octal escapes.
        val tuiScript =
            "printf '\\033[?1049h'; " + // enter alternate screen
                "printf '\\033[2J\\033[H'; " + // clear + home
                "printf '$REPAINT_MARKER\\r\\n'; " +
                "printf 'static line two\\r\\n'; " +
                "printf 'static line three\\r\\n'; " +
                "i=0; while true; do printf '\\033[6;1Htimer tick %d   ' \"\$i\"; " +
                "i=\$((i+1)); sleep 1; done"
        val script = buildString {
            appendLine("set -eu")
            appendLine("tmux kill-session -t ${shellQuote(SEEDED_SESSION)} 2>/dev/null || true")
            appendLine(
                "tmux new-session -d -s ${shellQuote(SEEDED_SESSION)} -x 80 -y 24 " +
                    shellQuote(tuiScript),
            )
            appendLine("sleep 2")
            appendLine("tmux list-sessions")
        }
        val result = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session -> session.use { it.exec(script) } }
        val exec = result.getOrNull()
        assertTrue(
            "expected tmux seeding to succeed; exception=${result.exceptionOrNull()} " +
                "stderr='${exec?.stderr}'",
            exec?.exitCode == 0,
        )
        Log.i(LOG_TAG, "seeded alt-screen session: ${exec?.stdout?.trim()}")
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

    private suspend fun listSshdPidsForTestuser(key: String): Set<Int> {
        val result = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session ->
            session.use { it.exec("pgrep -u testuser sshd 2>/dev/null || true") }
        }
        val out = result.getOrNull()?.stdout.orEmpty()
        return out.lines().mapNotNull { it.trim().toIntOrNull() }.toSet()
    }

    private suspend fun killRemoteSshdPids(key: String, pids: Set<Int>) {
        if (pids.isEmpty()) return
        val script = buildString {
            for (pid in pids) appendLine("kill -9 $pid 2>/dev/null || true")
        }
        runCatching {
            SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(key),
                knownHosts = KnownHostsPolicy.AcceptAll,
                timeoutMs = 15_000,
            ).mapCatching { session -> session.use { it.exec(script) } }
        }
    }

    private fun waitForText(text: String, timeoutMs: Long) {
        compose.waitUntil(timeoutMillis = timeoutMs) {
            // Issue #788: tolerate the transient "No compose hierarchies found"
            // ISE on the first frames before composition registers.
            runCatching {
                compose.onAllNodesWithText(text, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }.getOrDefault(false)
        }
    }

    /**
     * Issue #788: cold-compose-aware host-row presence poll under
     * createAndroidComposeRule (MainActivity's cold compose can take ~28s on a
     * contended swiftshader emulator). Early-exits the instant the row appears.
     */
    private fun waitForHostRowPresent(hostRowTag: String) {
        compose.waitUntil(timeoutMillis = TerminalTestTimeouts.screenRenderPresenceTimeoutMs()) {
            runCatching {
                compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }.getOrDefault(false)
        }
    }

    private fun waitForTerminalViewAttached() {
        compose.waitUntil(timeoutMillis = 30_000) {
            var attached = false
            compose.activityRule.scenario.onActivity { activity ->
                val view = activity.window.decorView.findTerminalView()
                attached = view?.currentSession != null && view.mEmulator != null
            }
            attached
        }
    }

    private fun visibleTerminalText(): String {
        var text = ""
        compose.activityRule.scenario.onActivity { activity ->
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

    private fun waitForVisibleTerminalText(
        label: String,
        timeoutMillis: Long = INITIAL_TERMINAL_TEXT_TIMEOUT_MS,
        predicate: (String) -> Boolean,
    ): String {
        var last = ""
        val satisfied = runCatching {
            compose.waitUntil(timeoutMillis = timeoutMillis) {
                last = visibleTerminalText()
                predicate(last)
            }
            true
        }.getOrDefault(false)
        if (!satisfied) {
            writeText("failure-$label-visible-terminal.txt", last)
        }
        assertTrue(
            "expected visible terminal text for $label, got (len=${last.length}):\n$last",
            predicate(last),
        )
        return last
    }

    private fun captureViewport(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)

        var bitmap: Bitmap? = null
        compose.activityRule.scenario.onActivity { activity ->
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
        println("ISSUE553_VIEWPORT ${file.absolutePath}")
        return file
    }

    private fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("ISSUE553_TEXT ${file.absolutePath}")
        return file
    }

    private fun writeTimings(): File {
        val file = artifactFile("timings.txt")
        file.writeText(timings.joinToString(separator = "\n", postfix = "\n"))
        println("ISSUE553_TIMINGS ${file.absolutePath}")
        return file
    }

    private fun writeSummary(): File {
        val file = artifactFile("summary.txt")
        file.writeText(
            buildString {
                appendLine("scenario=reconnect-repaints-full-pane-content")
                appendLine("issue=553")
                appendLine("fixture=tests/docker (agents host port $DEFAULT_PORT)")
                appendLine("session=$SEEDED_SESSION")
                appendLine("repaint_marker=$REPAINT_MARKER")
                appendLine()
                appendLine("timings:")
                timings.forEach(::appendLine)
            },
        )
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
        println("ISSUE553_TIMING $line")
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
        const val LOG_TAG: String = "Issue553ReconnectRepaint"
        const val DEVICE_DIR_NAME: String = "issue553-reconnect-repaint"
        const val SEEDED_SESSION: String = "issue553-altscreen"

        /** The static banner that must repaint after a reconnect. */
        const val REPAINT_MARKER: String = "ISSUE553-REPAINT-MARKER"

        val INITIAL_TERMINAL_TEXT_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 15_000L

        val DROP_OBSERVED_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 15_000L

        /**
         * Ceiling for the auto-reconnect to re-attach AND re-seed the pane so
         * the static banner repaints. The default backoff is `[0, 1s, 2s, 5s]`
         * and each attempt re-runs the SSH handshake + tmux re-attach +
         * capture-pane re-seed against the Docker fixture, so CI needs
         * generous head-room.
         */
        val REPAINT_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 60_000L else 40_000L
    }
}
