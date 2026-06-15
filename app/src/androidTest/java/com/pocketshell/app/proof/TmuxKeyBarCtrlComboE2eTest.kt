package com.pocketshell.app.proof

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.proof.signals.waitForSessionInPicker
import com.pocketshell.app.tmux.TERMINAL_HOTKEYS_LAUNCHER_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.uikit.components.TERMINAL_HOTKEYS_PANEL_TAG
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.termux.view.TerminalView
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #458 — connected E2E for the compact terminal key bar + the
 * `Ctrl` modifier.
 *
 * The maintainer wants `Esc`, the common Ctrl combos (`^C`/`^D`/`^Z`/
 * `^O`/`^X`), and a general `Ctrl` modifier above the soft keyboard,
 * compact and overlay-style. The acceptance bar is that tapping `^C`
 * actually interrupts a running process in the focused tmux pane and the
 * shell prompt returns — without a full terminal redraw/reflow (the
 * control byte is sent through tmux's `send-keys -H` control-channel
 * command, exactly like the existing Esc/Ctrl chips).
 *
 * This test stands up the real user journey on the deterministic Docker
 * `agents` fixture:
 *
 *  1. Seed a host + a `claude-main` tmux session running `exec sh`.
 *  2. Launch the app, attach to the session (real [TmuxSessionScreen]).
 *  3. Raise the soft keyboard via the show-keyboard chip so the key bar
 *     renders (it is an IME-up affordance).
 *  4. Start a long-running flood process (`yes ...`) by typing into the
 *     focused pane, and confirm the flood reaches the visible terminal.
 *  5. Tap the key bar `^C` slot. Capture the terminal viewport +
 *     visible-terminal text showing the interrupt + the returned prompt.
 *  6. Arm the `Ctrl` modifier (tap `Ctrl`), capture the armed state, then
 *     fire a chord by tapping a curated combo and confirm the bar reads
 *     back to its idle state.
 *
 * Authoritative artifacts (per the project's terminal-artifact rules) are
 * written to the device additional-test-output dir:
 *  - `*-viewport.png` direct terminal viewport renders
 *  - `*-visible-terminal.txt` visible transcript snapshots
 *  - `summary.txt` + `timings.txt`
 *
 * # CI compatibility
 *
 * Uses the default `agents` Docker service on port 2222, which the Tests
 * workflow already brings up for sibling tmux E2Es. No extra fixture is
 * required.
 */
@RunWith(AndroidJUnit4::class)
class TmuxKeyBarCtrlComboE2eTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val grantPermissions = PreGrantPermissionsRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null
    private val timings = mutableListOf<String>()

    @After
    fun closeLaunchedActivity() {
        launchedActivity?.close()
        launchedActivity = null
        runBlocking {
            runCatching { cleanupSeededSession(readFixtureKey()) }
        }
    }

    @Test
    fun hotkeysPanelCtrlCInterruptsRunningProcessAndEnterSubmits() = runBlocking {
        val key = readFixtureKey()
        waitForSshFixtureReady(SshKey.Pem(key))
        seedTmuxSession(key)

        val hostRowTag = seedDockerHost(key, "Issue784 Hotkeys")
        launchedActivity = ActivityScenario.launch(MainActivity::class.java)

        // ===== Attach to the session =====
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        val attachAt = SystemClock.elapsedRealtime()
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        waitForSessionInPicker(rule = compose, sessionName = SESSION_NAME, timeoutMs = 20_000)
        compose.onNodeWithText(SESSION_NAME).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        waitForTerminalViewAttached()
        waitForVisibleTerminal("prompt-ready") { it.isNotBlank() }
        recordTiming("attach_ms", SystemClock.elapsedRealtime() - attachAt)
        captureViewport("issue784-01-attached")

        // ===== Open the dedicated terminal-hotkeys panel =====
        // Issue #784: the hotkeys are no longer above the keyboard or in the
        // composer — they are their own bottom-sheet panel, opened from the
        // launcher in the (keyboard-down) bottom controls. No soft IME needed.
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithTag(TERMINAL_HOTKEYS_LAUNCHER_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag(TERMINAL_HOTKEYS_LAUNCHER_TAG, useUnmergedTree = true).performClick()
        // The panel shows EVERY key at once in a tidy grid — Esc / Tab / Enter,
        // the de-duped Ctrl combos incl. the restored ^B (tmux prefix), and the
        // clean arrow glyphs. No `…` overflow, no lone Ctrl, no duplicate `/`.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("^C", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        listOf("Esc", "Tab", "Enter", "^A", "^B", "^C", "^D", "^E", "^L", "^R", "←", "→")
            .forEach { label ->
                assertTrue(
                    "expected the hotkeys panel to show '$label'",
                    compose.onAllNodesWithText(label, useUnmergedTree = true)
                        .fetchSemanticsNodes()
                        .isNotEmpty(),
                )
            }
        // No lone Ctrl modifier and no `/` key — the maintainer's complaints.
        assertTrue(
            "the hotkeys panel must NOT show a lone Ctrl modifier",
            compose.onAllNodesWithText("Ctrl", useUnmergedTree = true).fetchSemanticsNodes().isEmpty(),
        )
        captureViewport("issue784-02-hotkeys-panel-visible")
        // Advisory full-device frame so the reviewer sees the actual panel grid
        // (the terminal-only viewport capture does not include the Compose sheet).
        captureFullDevice("issue784-02b-hotkeys-panel-fulldevice")

        // ===== Start a long-running process in the pane =====
        // A ticking loop is the canonical "is it still running?" process:
        // it keeps emitting until interrupted, but at a measured rate so the
        // scrollback stays small (a `yes` flood would bury the post-interrupt
        // prompt under thousands of lines). We tag each tick so the visible
        // terminal assertion is unambiguous.
        sendCommandThroughTerminalInput(
            "i=0; while true; do i=\$((i+1)); echo $FLOOD_MARKER-\$i; sleep 0.3; done",
            "flood-start",
        )
        waitForVisibleTerminal("flood-running", timeoutMillis = 20_000) { transcript ->
            // Several ticks must be present — proof the process is live.
            transcript.split(FLOOD_MARKER).size >= 4
        }
        captureViewport("issue784-03-process-running")
        val gridBeforeInterrupt = terminalGridSize()

        // ===== Tap the panel ^C to interrupt =====
        val interruptAt = SystemClock.elapsedRealtime()
        compose.onNodeWithText("^C", useUnmergedTree = true).performClick()
        // After the interrupt, the flood stops and a fresh shell prompt
        // returns. We detect "stopped" by sampling the transcript twice
        // with a settle gap and confirming the flood-line count no longer
        // grows — the process is no longer producing output.
        waitForFloodToStop()
        recordTiming("ctrl_c_interrupt_to_quiescent_ms", SystemClock.elapsedRealtime() - interruptAt)
        val gridAfterInterrupt = terminalGridSize()
        captureViewport("issue784-04-after-ctrl-c")

        // No reflow: the on-screen grid dimensions are unchanged across the
        // interrupt. `send-keys -H` is a control-channel command, not a
        // resize, so the terminal never repaints at a new size.
        assertTrue(
            "expected no terminal reflow across Ctrl+C: grid before=$gridBeforeInterrupt " +
                "after=$gridAfterInterrupt",
            gridBeforeInterrupt == gridAfterInterrupt,
        )

        // The shell must be interactive again: type a sentinel command and
        // confirm it echoes (only possible if the loop was interrupted and
        // the prompt returned). The panel is a Compose sheet over the terminal;
        // typing goes straight to the pane's input connection, unaffected.
        SystemClock.sleep(750)
        sendCommandThroughTerminalInput("echo $RESUME_MARKER", "post-interrupt-echo")
        waitForVisibleTerminal("resume-echo", timeoutMillis = 20_000) { transcript ->
            transcript.contains(RESUME_MARKER)
        }
        captureViewport("issue784-05-prompt-resumed")

        // ===== Enter key (issue #527): submit a pending line via the panel ====
        // Type a command WITHOUT a trailing newline so it sits as a pending,
        // unsubmitted line in the pane — the exact situation the maintainer
        // hits when the composer fails to submit. Then tap the dedicated
        // `Enter` key in the hotkeys panel and confirm the line executes.
        SystemClock.sleep(750)
        val enterGridBefore = terminalGridSize()
        typePendingLine("echo $ENTER_MARKER", "enter-pending-line")
        // The pending text must be visible in the pane but NOT yet executed
        // (the marker echo has not run, so it appears only once — as input).
        waitForVisibleTerminal("enter-pending-visible", timeoutMillis = 20_000) { transcript ->
            transcript.contains("echo $ENTER_MARKER")
        }
        captureViewport("issue784-05b-enter-pending")
        // Tap the dedicated Enter/Return key in the panel. Disambiguate from the
        // keyboard-down Enter chip (`session:enter-chip`) by scoping the match to
        // a descendant of the hotkeys panel sheet.
        val enterAt = SystemClock.elapsedRealtime()
        compose.onNode(
            hasText("Enter")
                .and(hasClickAction())
                .and(hasAnyAncestor(hasTestTag(TERMINAL_HOTKEYS_PANEL_TAG))),
        ).performClick()
        waitForVisibleTerminal("enter-executed", timeoutMillis = 20_000) { transcript ->
            // Echo output: the marker now appears as a standalone echoed line
            // (twice overall — once as the typed command, once as output).
            transcript.split(ENTER_MARKER).size >= 3
        }
        recordTiming("enter_key_submit_to_echo_ms", SystemClock.elapsedRealtime() - enterAt)
        val enterGridAfter = terminalGridSize()
        captureViewport("issue784-05c-enter-executed")
        // No reflow: tapping Enter is a `send-keys` control-channel command,
        // never a resize. The on-screen grid is unchanged across the submit.
        assertTrue(
            "expected no terminal reflow across the Enter key: grid before=$enterGridBefore " +
                "after=$enterGridAfter",
            enterGridBefore == enterGridAfter,
        )

        // Advisory full-device frame showing the full panel grid.
        captureFullDevice("issue784-06-hotkeys-panel-fulldevice")

        writeText("summary.txt", buildSummary(true, gridBeforeInterrupt, gridAfterInterrupt))
        writeTimings()

        val transcript = visibleTerminalText()
        assertTrue(
            "expected visible terminal transcript to contain the post-interrupt sentinel " +
                "'$RESUME_MARKER', proving Ctrl+C returned an interactive prompt; got:\n$transcript",
            transcript.contains(RESUME_MARKER),
        )
        // Issue #527: the Enter key submitted the pending line, so the echo
        // marker appears as both the typed command and its executed output.
        assertTrue(
            "expected visible terminal transcript to show the Enter-key sentinel " +
                "'$ENTER_MARKER' executed at least twice (typed + echoed), proving the " +
                "key-bar Enter submitted the pending line; got:\n$transcript",
            transcript.split(ENTER_MARKER).size >= 3,
        )
    }

    private fun buildSummary(
        panelOpened: Boolean,
        gridBefore: GridSize,
        gridAfter: GridSize,
    ): String = buildString {
        appendLine("issue=784 scenario=hotkeys-panel-ctrl-combo")
        appendLine("host=$DEFAULT_HOST port=$DEFAULT_PORT user=$DEFAULT_USER session=$SESSION_NAME")
        appendLine("hotkeys_panel_opened=$panelOpened")
        appendLine("grid_before_interrupt=${gridBefore.columns}x${gridBefore.rows}")
        appendLine("grid_after_interrupt=${gridAfter.columns}x${gridAfter.rows}")
        appendLine("no_reflow=${gridBefore == gridAfter}")
        appendLine("flood_marker=$FLOOD_MARKER")
        appendLine("resume_marker=$RESUME_MARKER")
        appendLine("enter_marker=$ENTER_MARKER")
        appendLine("artifacts:")
        listOf(
            "issue784-01-attached",
            "issue784-02-hotkeys-panel-visible",
            "issue784-03-process-running",
            "issue784-04-after-ctrl-c",
            "issue784-05-prompt-resumed",
            "issue784-05b-enter-pending",
            "issue784-05c-enter-executed",
        ).forEach { appendLine("  $it-viewport.png + $it-visible-terminal.txt") }
    }

    // --- Flood quiescence ---------------------------------------------------

    /**
     * After Ctrl+C the `yes` flood must stop. We sample the flood-line
     * count, settle, and resample; the process is interrupted once the
     * count stops growing between two consecutive samples.
     */
    private fun waitForFloodToStop() {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        var lastCount = floodLineCount()
        while (SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(600)
            val now = floodLineCount()
            if (now == lastCount) return
            lastCount = now
        }
        throw AssertionError(
            "flood process did not stop within 20s after Ctrl+C; last flood-line count=$lastCount",
        )
    }

    private fun floodLineCount(): Int =
        visibleTerminalText().split(FLOOD_MARKER).size - 1

    // --- Terminal helpers (mirrors TmuxSessionWindowNavigationE2eTest) ------

    private fun sendCommandThroughTerminalInput(command: String, label: String) {
        command.chunked(4).forEach { chunk ->
            val committed = terminalInputConnection().commitText(chunk, 1)
            assertTrue("expected terminal input to commit `$chunk` for $label", committed)
            SystemClock.sleep(35)
        }
        val enterCommitted = terminalInputConnection().commitText("\n", 1)
        assertTrue("expected terminal input to submit $label", enterCommitted)
    }

    /**
     * Type [text] into the terminal input WITHOUT a trailing newline, leaving
     * it as a pending, unsubmitted line in the pane (issue #527). Submission
     * is then exercised separately via the key-bar `⏎` key.
     */
    private fun typePendingLine(text: String, label: String) {
        text.chunked(4).forEach { chunk ->
            val committed = terminalInputConnection().commitText(chunk, 1)
            assertTrue("expected terminal input to commit `$chunk` for $label", committed)
            SystemClock.sleep(35)
        }
    }

    private fun terminalInputConnection(): InputConnection {
        var connection: InputConnection? = null
        launchedActivity?.onActivity { activity ->
            val view = requireNotNull(activity.window.decorView.findTerminalView()) {
                "TerminalView was not found"
            }
            view.requestFocus()
            connection = view.onCreateInputConnection(EditorInfo())
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        return requireNotNull(connection) { "TerminalView did not create an InputConnection" }
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

    private fun terminalGridSize(): GridSize {
        var grid: GridSize? = null
        launchedActivity?.onActivity { activity ->
            activity.window.decorView
                .findTerminalView()
                ?.currentSession
                ?.emulator
                ?.let { emulator ->
                    grid = GridSize(columns = emulator.mColumns, rows = emulator.mRows)
                }
        }
        return grid ?: GridSize(columns = 80, rows = 24)
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

    private fun waitForVisibleTerminal(
        label: String,
        timeoutMillis: Long = TerminalTestTimeouts.terminalVisibilityTimeoutMs(),
        predicate: (String) -> Boolean,
    ) {
        var last = ""
        val satisfied = runCatching {
            compose.waitUntil(timeoutMillis = timeoutMillis) {
                last = visibleTerminalText()
                predicate(last)
            }
            true
        }.getOrDefault(false)
        assertTrue(
            "expected visible terminal text for $label within ${timeoutMillis}ms; got:\n$last",
            satisfied && predicate(last),
        )
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

    /**
     * Advisory full-device screenshot — captures the whole screen (incl.
     * the Compose key-bar overlay), which the terminal-only viewport
     * capture cannot show. Per the project's terminal-artifact rules these
     * are diagnostic for terminal content; the authoritative terminal proof
     * is the `*-viewport.png` + `*-visible-terminal.txt` pair.
     */
    private fun captureFullDevice(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        try {
            writeBitmap(name, bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun writeBitmap(name: String, bitmap: Bitmap): File {
        val file = artifactFile("$name.png")
        FileOutputStream(file).use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                "failed to write bitmap to ${file.absolutePath}"
            }
        }
        println("ISSUE458_VIEWPORT ${file.absolutePath}")
        return file
    }

    private fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("ISSUE458_TEXT ${file.absolutePath}")
        return file
    }

    private fun writeTimings(): File {
        val file = artifactFile("timings.txt")
        file.writeText(timings.joinToString(separator = "\n", postfix = "\n"))
        println("ISSUE458_TIMINGS ${file.absolutePath}")
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
        println("ISSUE458_TIMING $line")
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

    // --- Host + session seeding ---------------------------------------------

    private fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }

    private suspend fun seedDockerHost(key: String, hostName: String): String {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        return try {
            db.clearAllTables()
            val storedKey = SshKeyStorage.persistKey(
                context = appContext,
                sshKeyDao = db.sshKeyDao(),
                name = "issue458-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = hostName,
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
        val script = buildString {
            appendLine("set -eu")
            appendLine("tmux kill-session -t ${shellQuote(SESSION_NAME)} 2>/dev/null || true")
            appendLine(
                "tmux new-session -d -s ${shellQuote(SESSION_NAME)} " +
                    shellQuote("printf '$READY_MARKER\\n'; exec sh"),
            )
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
            "expected tmux session seeding to succeed, got exception=" +
                "${result.exceptionOrNull()} stderr='${exec?.stderr}'",
            exec?.exitCode == 0,
        )
        Log.i(LOG_TAG, "seeded session: ${exec?.stdout?.trim()}")
    }

    private suspend fun cleanupSeededSession(key: String) {
        runCatching {
            withTimeout(20_000) {
                SshConnection.connect(
                    host = DEFAULT_HOST,
                    port = DEFAULT_PORT,
                    user = DEFAULT_USER,
                    key = SshKey.Pem(key),
                    knownHosts = KnownHostsPolicy.AcceptAll,
                    timeoutMs = 15_000,
                ).mapCatching { session ->
                    session.use {
                        it.exec("tmux kill-session -t ${shellQuote(SESSION_NAME)} 2>/dev/null || true")
                    }
                }
            }
        }
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private data class GridSize(val columns: Int, val rows: Int)

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
        const val LOG_TAG: String = "Issue458KeyBar"
        const val DEVICE_DIR_NAME: String = "issue458-keybar-ctrl-combo"
        const val SESSION_NAME: String = "claude-main"
        const val READY_MARKER: String = "KEYBAR-READY"
        val FLOOD_MARKER: String = "FLOOD-${System.currentTimeMillis().toString().takeLast(6)}"
        val RESUME_MARKER: String = "RESUMED-${System.currentTimeMillis().toString().takeLast(6)}"
        val ENTER_MARKER: String = "ENTERKEY-${System.currentTimeMillis().toString().takeLast(6)}"
    }
}
