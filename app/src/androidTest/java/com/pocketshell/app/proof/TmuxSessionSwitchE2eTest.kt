package com.pocketshell.app.proof

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
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
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.migrations.MIGRATION_1_2
import com.pocketshell.core.storage.migrations.MIGRATION_2_3
import com.pocketshell.core.storage.migrations.MIGRATION_3_4
import com.pocketshell.core.storage.migrations.MIGRATION_4_5
import com.pocketshell.core.storage.migrations.MIGRATION_5_6
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
 * Issue #151 — regression test for the v0.2.7 crash on tmux session
 * switch.
 *
 * The crash happened when the user, already attached to one tmux session
 * (`claude-main`), opened the in-session drawer and tapped "Attach" on
 * another seeded session (`codex`). The teardown-before-reattach race
 * inside `TmuxSessionViewModel.connect()` / `closeCurrentConnection()` and
 * the un-idempotent `RealSshSession.close()` produced a
 * `TransportException [BY_APPLICATION] Disconnected` that escaped to the
 * uncaught-exception handler and crashed the app.
 *
 * This test reproduces the exact sequence the user reported, on the
 * deterministic Docker `agents` fixture, and asserts:
 *
 *  1. Attach to `claude-main` succeeds.
 *  2. A command typed into `claude-main` shows up in the visible terminal.
 *  3. Opening the session drawer and tapping "Attach" on `codex` does not
 *     crash the app, the route swaps, and the new pane mounts.
 *  4. A command typed into `codex` shows up in the visible terminal.
 *  5. Attaching back to `claude-main` succeeds too — proves the reattach
 *     path also recovers, not just the one-way swap.
 *
 * Notes on test shape:
 *  - We use the More menu's "Switch session" entry to open the drawer
 *    rather than a swipe gesture, because Compose `pointerInput`-based
 *    swipes are flaky on emulator screens at this scale and the user
 *    journey from the More menu hits the SAME `showSessionDrawer = true`
 *    code path in `TmuxSessionScreen`.
 *  - The two tmux sessions are seeded fresh on every test run via a
 *    sidecar SSH session so the test is hermetic against prior runs.
 *  - We assert content via [TerminalTextMatcher.containsWrapTolerant]
 *    because after #102's `resize-window` propagation, commands wider
 *    than the Compose grid wrap with a real `\n` in the transcript text.
 */
@RunWith(AndroidJUnit4::class)
class TmuxSessionSwitchE2eTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null
    private val timings = mutableListOf<String>()

    @After
    fun closeLaunchedActivity() {
        launchedActivity?.close()
        launchedActivity = null
        runBlocking {
            runCatching {
                cleanupSeededSessions(readFixtureKey())
            }
        }
    }

    @Test
    fun switchingBetweenTmuxSessionsViaDrawerDoesNotCrash() = runBlocking {
        val key = readFixtureKey()
        waitForSshFixtureReady(SshKey.Pem(key))

        // Seed both sessions fresh on the fixture so the picker has them
        // ready and the test is hermetic against earlier runs.
        seedTmuxSessions(key)

        val hostRowTag = seedDockerHost(key, "Issue151 Tmux Switch")

        launchedActivity = ActivityScenario.launch(MainActivity::class.java)

        // ---- (1) Tap host, then attach to claude-main from the picker.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        val tapHostAt = SystemClock.elapsedRealtime()
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        waitForText(SESSION_CLAUDE, timeoutMs = 20_000)
        compose.onNodeWithText(SESSION_CLAUDE).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        waitForTerminalViewAttached()
        recordTiming("attach_claude_main_ms", SystemClock.elapsedRealtime() - tapHostAt)
        captureViewport("issue151-01-attached-claude-main")

        // ---- (2) Send a marker command into claude-main and wait for echo.
        val claudeMarker = "in-claude-main-$MARKER"
        sendCommandThroughTerminalInput("printf '$claudeMarker\\n'", "claude marker")
        waitForVisibleTerminal("claude marker effect") { transcript ->
            TerminalTextMatcher.containsWrapTolerant(
                transcript,
                claudeMarker,
                terminalCols = terminalGridSize().columns,
            )
        }
        captureViewport("issue151-02-claude-main-marker-visible")

        // ---- (3) Open the More menu, tap "Switch session", tap Attach on
        // codex. This is the exact code path the crash report came from.
        val switchTapAt = SystemClock.elapsedRealtime()
        compose.onNodeWithText("⋮").performClick()
        compose.onNodeWithText("Switch session").performClick()
        // The drawer fetches the session list asynchronously. Wait for both
        // session names to be in the drawer so the tap is unambiguous —
        // the More menu's "Switch session" already dismisses the menu.
        waitForText(SESSION_CODEX, timeoutMs = 20_000)
        // The Attach action is on the row labelled "Attach". The selected
        // (claude-main) row says "Open"; codex's row says "Attach" because
        // it is not the active session.
        compose.onAllNodesWithText("Attach")
            .fetchSemanticsNodes()
            .also {
                assertTrue(
                    "expected at least one Attach row in the drawer; got ${it.size}",
                    it.isNotEmpty(),
                )
            }
        // Tap the codex row directly (the row itself is clickable; row
        // label is the session name).
        compose.onNodeWithText(SESSION_CODEX).performClick()

        // ---- (4) Assert no crash: the route stays mounted and the
        // TerminalView re-mounts for the new pane. Without the fix this is
        // where the activity dies with TransportException.
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        waitForTerminalViewAttached()
        recordTiming(
            "switch_to_codex_ms",
            SystemClock.elapsedRealtime() - switchTapAt,
        )
        captureViewport("issue151-03-switched-to-codex")

        // ---- (5) Send a marker into codex and wait for echo. Proves the
        // new pane's input + output pipeline both work after the swap.
        val codexMarker = "in-codex-$MARKER"
        sendCommandThroughTerminalInput("printf '$codexMarker\\n'", "codex marker")
        waitForVisibleTerminal("codex marker effect") { transcript ->
            TerminalTextMatcher.containsWrapTolerant(
                transcript,
                codexMarker,
                terminalCols = terminalGridSize().columns,
            )
        }
        captureViewport("issue151-04-codex-marker-visible")

        // ---- (6) Attach back to claude-main. Proves the reattach path
        // doesn't crash either, and the original session is still alive on
        // the remote.
        val reattachTapAt = SystemClock.elapsedRealtime()
        compose.onNodeWithText("⋮").performClick()
        compose.onNodeWithText("Switch session").performClick()
        waitForText(SESSION_CLAUDE, timeoutMs = 20_000)
        compose.onNodeWithText(SESSION_CLAUDE).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        waitForTerminalViewAttached()
        recordTiming(
            "reattach_claude_main_ms",
            SystemClock.elapsedRealtime() - reattachTapAt,
        )
        captureViewport("issue151-05-reattached-claude-main")

        // The reattached claude-main pane must surface the marker we wrote
        // before the swap. This proves the original session was preserved
        // on the remote and the new attach is wired to the same pane.
        waitForVisibleTerminal("claude marker after reattach") { transcript ->
            TerminalTextMatcher.containsWrapTolerant(
                transcript,
                claudeMarker,
                terminalCols = terminalGridSize().columns,
            )
        }
        captureViewport("issue151-06-claude-main-marker-preserved")

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

    private suspend fun seedDockerHost(key: String, hostName: String): String {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
            .fallbackToDestructiveMigration(dropAllTables = false)
            .build()
        return try {
            db.clearAllTables()
            val storedKey = SshKeyStorage.persistKey(
                context = appContext,
                sshKeyDao = db.sshKeyDao(),
                name = "issue151-key-${System.currentTimeMillis()}",
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

    private suspend fun seedTmuxSessions(key: String) {
        // Kill any leftover sessions from a prior run, then create both
        // fresh. The two `exec sh` shells keep the sessions alive long
        // enough for the test to attach without tmux GC'ing them. The
        // outer shell concatenates the two new-session commands so a
        // single SSH exec round-trip seeds the full picker state.
        val script = buildString {
            appendLine("set -eu")
            appendLine("tmux kill-session -t ${shellQuote(SESSION_CLAUDE)} 2>/dev/null || true")
            appendLine("tmux kill-session -t ${shellQuote(SESSION_CODEX)} 2>/dev/null || true")
            appendLine(
                "tmux new-session -d -s ${shellQuote(SESSION_CLAUDE)} " +
                    shellQuote("printf 'CLAUDE-READY\\n'; exec sh"),
            )
            appendLine(
                "tmux new-session -d -s ${shellQuote(SESSION_CODEX)} " +
                    shellQuote("printf 'CODEX-READY\\n'; exec sh"),
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
        ).mapCatching { session ->
            session.use { it.exec(script) }
        }
        val exec = result.getOrNull()
        assertTrue(
            "expected tmux session seeding to succeed for #151, got " +
                "exception=${result.exceptionOrNull()} stderr='${exec?.stderr}'",
            exec?.exitCode == 0,
        )
        Log.i(LOG_TAG, "seeded sessions: ${exec?.stdout?.trim()}")
    }

    private suspend fun cleanupSeededSessions(key: String) {
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
                        it.exec(
                            "tmux kill-session -t ${shellQuote(SESSION_CLAUDE)} 2>/dev/null || true; " +
                                "tmux kill-session -t ${shellQuote(SESSION_CODEX)} 2>/dev/null || true",
                        )
                    }
                }
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

    private fun sendCommandThroughTerminalInput(command: String, label: String) {
        // Same chunked dispatch as EmulatorWorkflowE2eTest.realAppTmuxJourney —
        // splitting into 4-char chunks gives the remote pane time to redraw
        // between chunks on slow emulators and avoids the soft-wrap glitch
        // when the InputConnection ships the whole string in one packet.
        command.chunked(4).forEach { chunk ->
            val committed = terminalInputConnection().commitText(chunk, 1)
            assertTrue(
                "expected terminal input connection to commit `$chunk` for $label",
                committed,
            )
            SystemClock.sleep(35)
        }
        waitForVisibleTerminal("$label command echo", timeoutMillis = 10_000) { transcript ->
            TerminalTextMatcher.containsWrapTolerant(
                transcript,
                command,
                terminalCols = terminalGridSize().columns,
            )
        }
        val enterCommitted = terminalInputConnection().commitText("\n", 1)
        assertTrue("expected terminal input connection to submit $label", enterCommitted)
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

    private fun terminalViewBounds(): Rect {
        var bounds: Rect? = null
        launchedActivity?.onActivity { activity ->
            val view = activity.window.decorView.findTerminalView()
            if (view != null) {
                val location = IntArray(2)
                view.getLocationOnScreen(location)
                bounds = Rect(
                    location[0],
                    location[1],
                    location[0] + view.width,
                    location[1] + view.height,
                )
            }
        }
        return bounds ?: Rect(0, 0, 0, 0)
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
        println("ISSUE151_VIEWPORT ${file.absolutePath}")
        return file
    }

    private fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("ISSUE151_TEXT ${file.absolutePath}")
        return file
    }

    private fun writeTimings(): File {
        val file = artifactFile("timings.txt")
        file.writeText(timings.joinToString(separator = "\n", postfix = "\n"))
        println("ISSUE151_TIMINGS ${file.absolutePath}")
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
        println("ISSUE151_TIMING $line")
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

    private data class GridSize(val columns: Int, val rows: Int)

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
        const val LOG_TAG: String = "Issue151SessionSwitch"
        const val DEVICE_DIR_NAME: String = "issue151-session-switch"
        const val SESSION_CLAUDE: String = "claude-main"
        const val SESSION_CODEX: String = "codex"
        // Short marker that won't soft-wrap on the Pixel 7 Compose grid
        // (~63 cols after #102's resize-window). The wrap-tolerant matcher
        // would handle a longer one, but keeping it short means the
        // failure mode (if any) is unambiguously about routing, not
        // wrap-detection.
        val MARKER: String = System.currentTimeMillis().toString().takeLast(6)
    }
}
