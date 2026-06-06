package com.pocketshell.app.proof

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.proof.signals.waitForSessionInPicker
import com.pocketshell.app.tmux.TMUX_COMPACT_CHROME_MORE_BUTTON_TAG
import com.pocketshell.app.tmux.TMUX_FULL_CHROME_MORE_BUTTON_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_PAGER_OVERLAY_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_PAGER_PAGE_TAG_PREFIX
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.app.tmux.TMUX_WINDOW_STRIP_PILL_TAG_PREFIX
import com.pocketshell.app.tmux.TMUX_WINDOW_STRIP_TAG
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.termux.view.TerminalView
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #158 — regression test for tmux window + session navigation UX.
 *
 * The maintainer's v0.2.7 walkthrough pass reported that after creating
 * "new" inside a tmux session, the user landed on a cryptically-labeled
 * window (`@137`) with no obvious way back. #158 unifies four fixes:
 *
 *  1. **Disambiguated labels** — session-pager "New" vs
 *     in-strip "+ window", kebab grouped by scope.
 *  2. **WindowStrip hidden when there is only one window** — single-pane
 *     sessions no longer carry extra chrome.
 *  3. **Readable window labels** — "Window 1" / "Window 2" instead of
 *     `@137`, both on the strip and in the breadcrumb.
 *  4. **Reliable tap-to-switch** — selecting another window in the strip
 *     swaps the visible terminal content.
 *
 * This test drives the exact user journey on the deterministic Docker
 * `agents` fixture. The session-switch arm at the end depends on #151
 * (the reattach-without-crash fix) which landed in commit `5445203`.
 *
 * Notes on test shape:
 *  - We seed two tmux sessions fresh on every run so the test is
 *    hermetic against prior runs — same pattern as
 *    [TmuxSessionSwitchE2eTest].
 *  - We assert visible terminal content via
 *    [TerminalTextMatcher.containsWrapTolerant] because long-typed
 *    commands wrap at the on-screen grid's right margin after #102's
 *    `resize-window` propagation.
 *  - The new-window flow uses the in-strip `+ window` button (the test
 *    explicitly verifies that affordance; covering the kebab path too
 *    would duplicate ground without adding signal).
 *  - The session-create flow goes through the session pager → "New" →
 *    dialog → "Save", matching the user's discovery path. The dialog's
 *    confirm button is labelled "Save" (see
 *    [com.pocketshell.app.tmux.TmuxLifecycleDialog]).
 */
@RunWith(AndroidJUnit4::class)
class TmuxSessionWindowNavigationE2eTest {

    @get:Rule
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
            runCatching {
                cleanupSeededSessions(readFixtureKey())
            }
        }
    }

    @Test
    fun windowAndSessionNavigationIsReliableAndReadable() = runBlocking {
        val key = readFixtureKey()
        waitForSshFixtureReady(SshKey.Pem(key))

        // Hermetic seed: kill any leftovers, then create both sessions
        // fresh. Both run `exec sh` so the sessions stay alive long enough
        // for the test to attach and switch between them.
        seedTmuxSessions(key)

        val hostRowTag = seedDockerHost(key, "Issue158 Tmux Nav")

        launchedActivity = ActivityScenario.launch(MainActivity::class.java)

        // ===== Step 1 — Attach to claude-main =====
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        val attachAt = SystemClock.elapsedRealtime()
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        // Issue #470 blocker #2: shared session-picker readiness gate
        // (generous bound + one production Retry) instead of a bare
        // waitForText that flaked when the cold-AVD tmux list-sessions
        // SSH-exec probe landed on the connect-error panel.
        waitForSessionInPicker(rule = compose, sessionName = SESSION_CLAUDE, timeoutMs = 20_000)
        compose.onNodeWithText(SESSION_CLAUDE).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        waitForTerminalViewAttached()
        recordTiming("attach_claude_main_ms", SystemClock.elapsedRealtime() - attachAt)
        captureViewport("issue158-01-attached-claude-main")

        // ===== Step 2 — WindowStrip MUST be hidden in single-window session =====
        // Wait for the panes list to actually populate; without this the
        // assertion runs against the empty-window placeholder, which is a
        // false negative because the strip is *trivially* absent before
        // any pane shows up.
        waitForTerminalReady()
        SystemClock.sleep(SETTLE_MS)
        // Issue #192: the WindowStrip is the primary window switcher but
        // is only rendered when there is more than one window — a
        // single-window session has nothing to switch to, so the strip
        // would be pure chrome. claude-main starts with one window, so
        // the strip must be absent here.
        assertFalse(
            "WindowStrip must be hidden in a single-window session (#192)",
            compose.onAllNodesWithTag(TMUX_WINDOW_STRIP_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty(),
        )
        captureViewport("issue158-02-windowstrip-hidden")

        // ===== Step 3 — Add a window from the kebab + window menu =====
        // Single source of truth for the "+ New window" affordance — the
        // kebab is the discoverable path (the strip's "+ window" button
        // is exercised by the chrome-level UI test).
        openMoreMenu()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("+ New window", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        val newWindowAt = SystemClock.elapsedRealtime()
        compose.onNodeWithText("+ New window").performClick()

        // ===== Step 4 — WindowStrip becomes the primary switcher now that windows.size > 1 =====
        // Issue #192: with a second window the [WindowStrip] is rendered
        // and carries one pill per window. We assert both pills show up,
        // then switch by tapping the pill directly (the primary,
        // most-discoverable path) — not the kebab.
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithTag(TMUX_WINDOW_STRIP_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty() &&
                compose.onAllNodesWithTag(
                    "${TMUX_WINDOW_STRIP_PILL_TAG_PREFIX}1",
                    useUnmergedTree = true,
                ).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag(
                    "${TMUX_WINDOW_STRIP_PILL_TAG_PREFIX}2",
                    useUnmergedTree = true,
                ).fetchSemanticsNodes().isNotEmpty()
        }
        recordTiming("add_window_ms", SystemClock.elapsedRealtime() - newWindowAt)
        captureViewport("issue158-03-window-strip-two-pills")

        // Tap window 2's pill in the strip to swap the visible pane.
        performWindowStripPillClick(2)
        // The pill tap starts a local pager scroll. Waiting only for
        // "terminal is non-empty" races because Window 1 is already
        // non-empty (`CLAUDE-READY`). Wait until the visible transcript
        // is no longer Window 1 before sending input, or the marker can
        // be written into the wrong pane.
        waitForVisibleTerminal("switched to window 2 before typing") { transcript ->
            !TerminalTextMatcher.containsWrapTolerant(
                transcript,
                INITIAL_WINDOW_MARKER,
                terminalCols = terminalGridSize().columns,
            )
        }
        SystemClock.sleep(SETTLE_MS)

        // ===== Step 5 — Send marker into window 2 =====
        val winTwoMarker = "win-2-$MARKER"
        sendCommandThroughTerminalInput(
            command = "printf '$winTwoMarker\\n'",
            label = "window 2 marker",
        )
        waitForVisibleTerminal("window 2 marker visible") { transcript ->
            TerminalTextMatcher.containsWrapTolerant(
                transcript,
                winTwoMarker,
                terminalCols = terminalGridSize().columns,
            )
        }
        captureViewport("issue158-04-win2-marker-visible")

        // ===== Step 6 — Switch to window 1 via the strip, marker MUST NOT be visible =====
        val switchToWinOneAt = SystemClock.elapsedRealtime()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(
                "${TMUX_WINDOW_STRIP_PILL_TAG_PREFIX}1",
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }
        performWindowStripPillClick(1)
        // The pager animates to the first pane of window 1. Wait for the
        // visible transcript to flip away from the win-2 marker — the
        // terminalState attached to the visible pane is window 1's, which
        // has not seen the printf. Allowing up to the CI-aware visibility
        // timeout absorbs slow GPU repaint on swiftshader emulators.
        waitForVisibleTerminal("switched to window 1 (marker gone)") { transcript ->
            val columns = terminalGridSize().columns
            TerminalTextMatcher.containsWrapTolerant(
                transcript,
                INITIAL_WINDOW_MARKER,
                terminalCols = columns,
            ) &&
                !TerminalTextMatcher.containsWrapTolerant(
                    transcript,
                    winTwoMarker,
                    terminalCols = columns,
                )
        }
        recordTiming(
            "switch_to_window_1_ms",
            SystemClock.elapsedRealtime() - switchToWinOneAt,
        )
        captureViewport("issue158-05-switched-to-window-1")

        // ===== Step 7 — Switch back to window 2 via the strip, marker MUST reappear =====
        val switchBackAt = SystemClock.elapsedRealtime()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(
                "${TMUX_WINDOW_STRIP_PILL_TAG_PREFIX}2",
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }
        performWindowStripPillClick(2)
        waitForVisibleTerminal("switched back to window 2 (marker present)") { transcript ->
            val columns = terminalGridSize().columns
            TerminalTextMatcher.containsWrapTolerant(
                transcript,
                winTwoMarker,
                terminalCols = columns,
            ) &&
                !TerminalTextMatcher.containsWrapTolerant(
                    transcript,
                    INITIAL_WINDOW_MARKER,
                    terminalCols = columns,
                )
        }
        recordTiming(
            "switch_back_to_window_2_ms",
            SystemClock.elapsedRealtime() - switchBackAt,
        )
        captureViewport("issue158-06-switched-back-to-window-2")

        // ===== Step 8 — Open the session pager and create a new session =====
        // The strip's "+ window" affordance is *not* the same as the
        // pager's session-level "New" action. We tap the pager entry,
        // fill in the name field, and tap "Save" (the dialog's confirm
        // button).
        openMoreMenu()
        compose.onNodeWithText("Switch session").performClick()
        compose.onNodeWithTag(TMUX_SESSION_PAGER_OVERLAY_TAG, useUnmergedTree = true)
            .assertExists()
        val createSessionAt = SystemClock.elapsedRealtime()
        compose.onNodeWithText("New").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Session name").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodes(hasSetTextAction(), useUnmergedTree = true)
            .onFirst()
            .performTextInput(SESSION_SCRATCH)
        compose.onNodeWithText("Save").performClick()

        // The Save tap calls onOpenTmuxSession which re-launches the
        // route under the new session name. The route stays on the
        // tmux screen tag; only the session-name parameter changes.
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        waitForTerminalViewAttached()
        recordTiming("create_session_ms", SystemClock.elapsedRealtime() - createSessionAt)
        captureViewport("issue158-07-new-session-scratch")

        // Confirm the new session exists on the remote and is active.
        // Opening the pager surfaces the seed list including the new
        // session.
        openMoreMenu()
        compose.onNodeWithText("Switch session").performClick()
        compose.onNodeWithTag(TMUX_SESSION_PAGER_OVERLAY_TAG, useUnmergedTree = true)
            .assertExists()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(SESSION_SCRATCH, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        // Dismiss the pager without changing anything for the next step.
        compose.onNodeWithText("Close").performClick()

        // ===== Step 9 — Attach back to claude-main from the session pager =====
        // This is the #151 reattach path. The marker we sent into win-2
        // earlier MUST still be present because the original session is
        // alive on the remote.
        openMoreMenu()
        compose.onNodeWithText("Switch session").performClick()
        compose.onNodeWithTag(TMUX_SESSION_PAGER_OVERLAY_TAG, useUnmergedTree = true)
            .assertExists()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(SESSION_CLAUDE, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        val reattachAt = SystemClock.elapsedRealtime()
        performSessionPagerPageClick(SESSION_CLAUDE)
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        waitForTerminalViewAttached()
        recordTiming("reattach_claude_main_ms", SystemClock.elapsedRealtime() - reattachAt)
        captureViewport("issue158-08-reattached-claude-main")

        // The reattached session has two windows, so the [WindowStrip]
        // (#192) is rendered again. Wait for window 2's pill, then tap
        // it to land on window 2 deterministically.
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithTag(
                "${TMUX_WINDOW_STRIP_PILL_TAG_PREFIX}2",
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }
        // After reattach, the user could land on either window — what
        // matters is that the win-2 marker is reachable. Tap window 2's
        // strip pill explicitly to land on it deterministically.
        performWindowStripPillClick(2)
        waitForVisibleTerminal("marker preserved across reattach") { transcript ->
            TerminalTextMatcher.containsWrapTolerant(
                transcript,
                winTwoMarker,
                terminalCols = terminalGridSize().columns,
            )
        }
        captureViewport("issue158-09-marker-preserved-after-reattach")

        // ===== Bonus assertion — strip IS the multi-window switcher (#192) =====
        // Sanity: the reattached session has two windows, so the
        // [WindowStrip] must be present (it is the primary window
        // switcher). A regression that hides the strip in a multi-window
        // session would be caught here.
        assertTrue(
            "WindowStrip must be rendered for a multi-window session after reattach (#192)",
            compose.onAllNodesWithTag(TMUX_WINDOW_STRIP_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty(),
        )

        writeTimings()
        Unit
    }

    /**
     * Issue #192 regression — the per-pill long-press "Rename" action must
     * rename the window of the pill that was long-pressed, NOT whatever
     * window is currently active. (The bug renamed `currentWindowId`.)
     *
     * Journey: seed a fresh `claude-main`, add a second window so the
     * strip renders two pills, switch to window 2 so it is the *active*
     * window, then long-press window 1's pill and rename it. We then read
     * the remote tmux window names over SSH and assert window 1 (the
     * long-pressed, non-active one) carries the new name while window 2
     * (the active one) is untouched. Pre-fix, window 2 would have been
     * renamed instead.
     */
    @Test
    fun perPillRenameTargetsLongPressedWindowNotCurrent() = runBlocking {
        val key = readFixtureKey()
        waitForSshFixtureReady(SshKey.Pem(key))
        seedTmuxSessions(key)
        val hostRowTag = seedDockerHost(key, "Issue192 Per-Pill Rename")

        launchedActivity = ActivityScenario.launch(MainActivity::class.java)

        // Attach to claude-main.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        // Issue #470 blocker #2: shared session-picker readiness gate
        // (generous bound + one production Retry) instead of a bare
        // waitForText that flaked when the cold-AVD tmux list-sessions
        // SSH-exec probe landed on the connect-error panel.
        waitForSessionInPicker(rule = compose, sessionName = SESSION_CLAUDE, timeoutMs = 20_000)
        compose.onNodeWithText(SESSION_CLAUDE).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        waitForTerminalViewAttached()
        waitForTerminalReady()

        // Add a second window so the strip renders two pills.
        openMoreMenu()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("+ New window", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText("+ New window").performClick()
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithTag(
                "${TMUX_WINDOW_STRIP_PILL_TAG_PREFIX}1",
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag(
                    "${TMUX_WINDOW_STRIP_PILL_TAG_PREFIX}2",
                    useUnmergedTree = true,
                ).fetchSemanticsNodes().isNotEmpty()
        }

        // Make window 2 the *active* window — this is the trap: pre-fix
        // the rename targeted the active window (window 2).
        performWindowStripPillClick(2)
        waitForVisibleTerminal("switched to window 2 before rename") { transcript ->
            !TerminalTextMatcher.containsWrapTolerant(
                transcript,
                INITIAL_WINDOW_MARKER,
                terminalCols = terminalGridSize().columns,
            )
        }
        SystemClock.sleep(SETTLE_MS)

        // Record the remote window names BEFORE the rename so the
        // assertion compares against ground truth, not assumptions.
        val namesBefore = remoteWindowNames(key, SESSION_CLAUDE)
        assertTrue(
            "expected two windows on $SESSION_CLAUDE before rename, got $namesBefore",
            namesBefore.size == 2,
        )

        // Long-press window 1's pill (the NON-active window) and rename it.
        val renamed = "renamed-w1-$MARKER"
        compose.onNodeWithTag(
            "${TMUX_WINDOW_STRIP_PILL_TAG_PREFIX}1",
            useUnmergedTree = true,
        ).performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Rename Window 1", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText("Rename Window 1").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Window name", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        // The dialog prefills with the windowId; clear it then type the
        // new name so the field holds exactly the rename target.
        compose.onAllNodes(hasSetTextAction(), useUnmergedTree = true)
            .onFirst()
            .performTextClearance()
        compose.onAllNodes(hasSetTextAction(), useUnmergedTree = true)
            .onFirst()
            .performTextInput(renamed)
        compose.onNodeWithText("Save").performClick()

        // Poll the remote until exactly one window carries the new name.
        var namesAfter: List<String> = emptyList()
        val applied = runCatching {
            compose.waitUntil(timeoutMillis = 15_000) {
                namesAfter = runBlocking { remoteWindowNames(key, SESSION_CLAUDE) }
                namesAfter.contains(renamed)
            }
            true
        }.getOrDefault(false)

        assertTrue(
            "expected the rename to reach the remote tmux server; namesAfter=$namesAfter",
            applied,
        )
        // The long-pressed window is window 1 (index 0 in list-windows
        // order). It MUST carry the new name.
        assertEquals(
            "per-pill rename must target the long-pressed window (window 1), got $namesAfter",
            renamed,
            namesAfter.first(),
        )
        // The active window (window 2, index 1) MUST be untouched — this
        // is what the pre-fix mistarget would have renamed.
        assertEquals(
            "active window (window 2) must NOT be renamed by a per-pill rename of window 1, got $namesAfter",
            namesBefore[1],
            namesAfter[1],
        )

        Unit
    }

    /**
     * Reads the ordered window names of [sessionName] from the remote tmux
     * server over SSH. `list-windows` reports windows in index order, so
     * element 0 is window 1, element 1 is window 2, matching the strip
     * pill ordering.
     */
    private suspend fun remoteWindowNames(key: String, sessionName: String): List<String> {
        val result = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session ->
            session.use {
                it.exec(
                    "tmux list-windows -t ${shellQuote(sessionName)} -F '#{window_name}'",
                )
            }
        }
        val exec = result.getOrNull()
        assertTrue(
            "expected tmux list-windows to succeed, got exception=" +
                "${result.exceptionOrNull()} stderr='${exec?.stderr}'",
            exec?.exitCode == 0,
        )
        return exec?.stdout.orEmpty()
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
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
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        return try {
            db.clearAllTables()
            val storedKey = SshKeyStorage.persistKey(
                context = appContext,
                sshKeyDao = db.sshKeyDao(),
                name = "issue158-key-${System.currentTimeMillis()}",
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
        val script = buildString {
            appendLine("set -eu")
            appendLine("tmux kill-session -t ${shellQuote(SESSION_CLAUDE)} 2>/dev/null || true")
            appendLine("tmux kill-session -t ${shellQuote(SESSION_SCRATCH)} 2>/dev/null || true")
            appendLine(
                "tmux new-session -d -s ${shellQuote(SESSION_CLAUDE)} " +
                    shellQuote("printf '$INITIAL_WINDOW_MARKER\\n'; exec sh"),
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
            "expected tmux session seeding to succeed for #158, got " +
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
                                "tmux kill-session -t ${shellQuote(SESSION_SCRATCH)} 2>/dev/null || true",
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

    private fun openMoreMenu() {
        val tags = listOf(
            TMUX_COMPACT_CHROME_MORE_BUTTON_TAG,
            TMUX_FULL_CHROME_MORE_BUTTON_TAG,
        ).filter { tag ->
            compose.onAllNodesWithTag(tag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        tags.forEach { tag ->
            compose.onNodeWithTag(tag, useUnmergedTree = true).performClick()
            val opened = runCatching {
                compose.waitUntil(timeoutMillis = 1_000) {
                    compose.onAllNodesWithText("+ New window", useUnmergedTree = true)
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
            }.isSuccess
            if (opened) return
        }
        compose.onNodeWithTag(TMUX_FULL_CHROME_MORE_BUTTON_TAG, useUnmergedTree = true)
            .performClick()
    }

    private fun performWindowStripPillClick(window: Int) {
        compose.onNodeWithTag(
            "$TMUX_WINDOW_STRIP_PILL_TAG_PREFIX$window",
            useUnmergedTree = true,
        ).performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun performSessionPagerPageClick(sessionName: String) {
        val taggedSessionPage = hasAnyDescendant(hasText(sessionName)) and
            (1..8)
                .map { page -> hasTestTag("$TMUX_SESSION_PAGER_PAGE_TAG_PREFIX$page") }
                .reduce { left, right -> left or right }
        compose.onNode(taggedSessionPage, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.OnClick)
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

    /**
     * Wait for the terminal not only to be attached but to actually have
     * non-empty rendered text. This avoids racing the "WindowStrip
     * hidden" assertion against the empty-pane placeholder.
     */
    private fun waitForTerminalReady() {
        compose.waitUntil(timeoutMillis = 30_000) {
            visibleTerminalText().isNotBlank()
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
        command.chunked(4).forEach { chunk ->
            val committed = terminalInputConnection().commitText(chunk, 1)
            assertTrue(
                "expected terminal input connection to commit `$chunk` for $label",
                committed,
            )
            SystemClock.sleep(35)
        }
        waitForVisibleTerminal("$label command echo", timeoutMillis = 20_000) { transcript ->
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
        println("ISSUE158_VIEWPORT ${file.absolutePath}")
        return file
    }

    private fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("ISSUE158_TEXT ${file.absolutePath}")
        return file
    }

    private fun writeTimings(): File {
        val file = artifactFile("timings.txt")
        file.writeText(timings.joinToString(separator = "\n", postfix = "\n"))
        println("ISSUE158_TIMINGS ${file.absolutePath}")
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
        println("ISSUE158_TIMING $line")
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
        const val LOG_TAG: String = "Issue158WindowNav"
        const val DEVICE_DIR_NAME: String = "issue158-window-navigation"
        const val SESSION_CLAUDE: String = "claude-main"
        const val SESSION_SCRATCH: String = "scratch"
        const val INITIAL_WINDOW_MARKER: String = "CLAUDE-READY"
        /**
         * Settle delay after layout changes. The strip-hidden assertion
         * runs right after attach; the create-window assertion runs
         * after a `select-window` round-trip. Compose recomposition is
         * fast on host emulators but swiftshader CI emulators benefit
         * from this small grace period before sampling node existence.
         */
        const val SETTLE_MS: Long = 300L
        /**
         * Short marker that won't soft-wrap on the Pixel 7 Compose grid
         * (~63 cols after #102's resize-window). The wrap-tolerant matcher
         * would handle a longer one, but keeping it short means the
         * failure mode (if any) is unambiguously about routing, not
         * wrap-detection.
         */
        val MARKER: String = System.currentTimeMillis().toString().takeLast(6)
    }
}
