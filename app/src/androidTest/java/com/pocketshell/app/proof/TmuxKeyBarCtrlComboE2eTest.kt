package com.pocketshell.app.proof

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.room.Room
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.App
import com.pocketshell.app.MainActivity
import com.pocketshell.app.diagnostics.DiagnosticEventFilter
import com.pocketshell.app.diagnostics.DiagnosticsEvent
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.proof.signals.FOREIGN_WINDOW_FOCUS_SIGNATURE
import com.pocketshell.app.proof.signals.awaitActivityWindowFocus
import com.pocketshell.app.proof.signals.repokeSessionPickerFromHostRow
import com.pocketshell.app.projects.FOLDER_LIST_PULL_TO_REFRESH_TAG
import com.pocketshell.app.proof.signals.waitForSessionInPicker
import com.pocketshell.app.proof.signals.waitForInputMethodVisible
import com.pocketshell.app.tmux.TERMINAL_HOTKEYS_LAUNCHER_TAG
import com.pocketshell.app.tmux.TMUX_CONVERSATION_DETECTING_TAG
import com.pocketshell.app.tmux.TMUX_CONVERSATION_PANE_TAG
import com.pocketshell.app.tmux.TMUX_COMPACT_CHROME_BACK_BUTTON_TAG
import com.pocketshell.app.tmux.TMUX_FULL_CHROME_BACK_BUTTON_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.app.tmux.TMUX_TERMINAL_TAB_TAG
import com.pocketshell.app.tmux.TmuxHotkeyEnterLabel
import com.pocketshell.uikit.components.TERMINAL_HOTKEYS_CTRL_FLOW_TAG
import com.pocketshell.uikit.components.TERMINAL_HOTKEYS_PANEL_BACK_TAG
import com.pocketshell.uikit.components.TERMINAL_HOTKEYS_PANEL_CLOSE_TAG
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import com.pocketshell.app.proof.signals.captureViewToBitmap

/**
 * Issue #1662 — connected E2E for the two-page terminal hotkeys sheet.
 *
 * The selected CI journey proves both keyboard-down and keyboard-up launcher
 * routes, fresh Main-page reset, real-IME containment, exact raw shell bytes
 * for Ctrl keys and atomic C/D holds, and the same controls in a deterministic
 * fake-Claude terminal.
 *
 * Authoritative artifacts (per the project's terminal-artifact rules) are
 * written to the device additional-test-output dir:
 *  - `*-viewport.png` direct terminal viewport renders
 *  - `*-visible-terminal.txt` visible transcript snapshots
 *  - `timings.txt`
 *
 * # CI compatibility
 *
 * Uses the default `agents` Docker service on port 2222, which the Tests
 * workflow already brings up for sibling tmux E2Es. No extra fixture is
 * required.
 */
@RunWith(AndroidJUnit4::class)
class TmuxKeyBarCtrlComboE2eTest {
    private lateinit var trustedHostKeySha256: String

    val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: RuleChain = RuleChain
        .outerRule(PreGrantPermissionsRule())
        .around(SeedBeforeLaunchRule { seedBeforeLaunch() })
        .around(compose)

    private var fixtureKey: String = ""
    private var hostRowTag: String = ""
    private val timings = mutableListOf<String>()

    @After
    fun closeLaunchedActivity() {
        runBlocking {
            fixtureKey.takeIf { it.isNotBlank() }?.let { key ->
                runCatching { cleanupSeededSession(key) }
            }
        }
    }

    @Test
    fun hotkeysTwoPageFlowSendsExactShellAndAgentControlBytes() { runBlocking {
        // ===== Attach to the session =====
        attachSeededSession(
            readinessLabel = "prompt-ready",
            timingLabel = "attach_ms",
            captureLabel = "issue784-01-attached",
        )

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
        // The Main page shows one screenful of common controls and one explicit
        // `Ctrl+…` route. The Ctrl page owns the complete control alphabet, so
        // the old expander, sticky modifier, and duplicate grids stay absent.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("^C", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        // Issue #1662 reproduce-first contract: the production sheet must expose
        // one obvious Ctrl flow and must not retain the old progressive-disclosure
        // expander. Both assertions fail on the assigned base, where `Ctrl+…` is
        // absent and the duplicated grids still live behind this expander.
        compose.onNodeWithTag(TERMINAL_HOTKEYS_CTRL_FLOW_TAG).assertExists()
        listOf(
            "Esc", "Tab", "⇧Tab", "Enter",
            "^B", "^C", "^D", "^Q", "^X", "←", "→",
        ).forEach { label ->
                assertTrue(
                    "expected the hotkeys panel to show '$label'",
                    compose.onAllNodesWithText(label, useUnmergedTree = true)
                        .fetchSemanticsNodes()
                        .isNotEmpty(),
                )
            }
        assertSheetTargetsAboveImeOrImeDismissed(
            listOf("←", "↑", "↓", "→", "Esc", "Tab", "⇧Tab", "Enter", "^B", "^C", "^D", "^Q", "^X"),
        )
        listOf("Show more keys", "^C×2", "^D×2", "Ctrl", "a").forEach { obsolete ->
            compose.onNodeWithText(obsolete, useUnmergedTree = true).assertDoesNotExist()
        }
        compose.onNodeWithTag(TERMINAL_HOTKEYS_CTRL_FLOW_TAG).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Ctrl + …", useUnmergedTree = true).assertExists()
        listOf("^Q", "^U", "^A", "^L", "^Z", "^M", "^\\").forEach { label ->
            compose.onNodeWithText(label, useUnmergedTree = true).assertExists()
        }
        assertSheetTargetsAboveImeOrImeDismissed(
            TmuxCtrlPageLabels,
        )
        captureFullDevice("issue1662-ctrl-page-fulldevice")
        val backNoByteBaseline = actionDiagnosticBaseline()
        compose.onNodeWithTag(TERMINAL_HOTKEYS_PANEL_BACK_TAG).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(TERMINAL_HOTKEYS_CTRL_FLOW_TAG).assertExists()
        assertNoControlDiagnosticsSince(
            action = "back-to-main",
            baseline = backNoByteBaseline,
        )
        // Reopening is a fresh sheet lifecycle: Ctrl mode must never persist.
        compose.onNodeWithTag(TERMINAL_HOTKEYS_CTRL_FLOW_TAG).performClick()
        val closeNoByteBaseline = actionDiagnosticBaseline()
        compose.onNodeWithTag(TERMINAL_HOTKEYS_PANEL_CLOSE_TAG).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(TERMINAL_HOTKEYS_PANEL_TAG).assertDoesNotExist()
        assertNoControlDiagnosticsSince(
            action = "close-from-ctrl",
            baseline = closeNoByteBaseline,
        )

        // Exercise the second real entry route. With the sheet absent, focus
        // the terminal and raise the real IME; the keyboard-up launcher must
        // still open a fresh Main page without programmatically hiding the IME.
        selectTerminalSurfaceBeforeIme()
        showImeAndAssertVisible()
        compose.waitUntil(timeoutMillis = 6_000) {
            compose.onAllNodesWithTag(TERMINAL_HOTKEYS_LAUNCHER_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag(TERMINAL_HOTKEYS_LAUNCHER_TAG, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(TERMINAL_HOTKEYS_LAUNCHER_TAG, useUnmergedTree = true).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(TERMINAL_HOTKEYS_CTRL_FLOW_TAG).assertExists()
        compose.onNodeWithText("Ctrl + …", useUnmergedTree = true).assertDoesNotExist()
        assertSheetTargetsAboveImeOrImeDismissed(
            listOf("←", "↑", "↓", "→", "Esc", "Tab", "⇧Tab", "Enter", "^B", "^C", "^D", "^Q", "^X"),
        )
        compose.onNodeWithTag(TERMINAL_HOTKEYS_CTRL_FLOW_TAG).performClick()
        compose.onNodeWithText("Ctrl + …", useUnmergedTree = true).assertExists()
        assertSheetTargetsAboveImeOrImeDismissed(TmuxCtrlPageLabels)
        val imeBackNoByteBaseline = actionDiagnosticBaseline()
        compose.onNodeWithTag(TERMINAL_HOTKEYS_PANEL_BACK_TAG).performClick()
        compose.onNodeWithTag(TERMINAL_HOTKEYS_CTRL_FLOW_TAG).assertExists()
        assertNoControlDiagnosticsSince(
            action = "keyboard-up-back-to-main",
            baseline = imeBackNoByteBaseline,
        )
        assertSheetTargetsAboveImeOrImeDismissed(
            listOf("←", "↑", "↓", "→", "Esc", "Tab", "⇧Tab", "Enter", "^B", "^C", "^D", "^Q", "^X"),
        )

        // Raw mode prevents the tty line discipline from consuming these
        // controls. Each phase publishes a visible CRLF readiness marker before
        // `od` starts reading, so no control gesture can race reader setup.
        val observedRawPhases = mutableListOf<String>()
        sendCommandThroughTerminalInput(phaseOneRawReaderCommand(), "issue1662-raw-byte-reader-phase-1")
        waitForVisibleTerminal("issue1662-raw-phase-1-ready", timeoutMillis = 20_000) {
            it.hasExactTerminalLine(RAW_PHASE_ONE_READY)
        }
        val diagnosticBaseline = diagnosticRecorder().readEvents(
            DiagnosticEventFilter(category = "action"),
        )
            .maxOfOrNull(DiagnosticsEvent::sequence)
            ?: 0L
        val serverPaneId = serverPaneId()
        compose.onNodeWithTag(TERMINAL_HOTKEYS_CTRL_FLOW_TAG)
            .assertIsEnabled()
            .performClick()
        listOf("^Q", "^\\", "^B", "^B").forEach(::tapPanelKey)

        val phaseOneDiagnostics = diagnosticRecorder().readEvents(
            DiagnosticEventFilter(
                category = "action",
                sinceSequenceExclusive = diagnosticBaseline,
            ),
        ).filter { event ->
            event.name == "shortcut_sent" || event.name == "pane_control_input"
        }
        writePhaseOneDiagnostics(
            baseline = diagnosticBaseline,
            serverPaneId = serverPaneId,
            events = phaseOneDiagnostics,
        )
        assertPhaseOneDiagnostics(
            serverPaneId = serverPaneId,
            events = phaseOneDiagnostics,
        )

        val phaseOneTranscript = try {
            waitForVisibleTerminal(
                "issue1662-raw-phase-1",
                timeoutMillis = 20_000,
            ) { transcript ->
                RAW_PHASE_ONE_SLOTS.all { slot ->
                    transcript.contains("${slot.marker}=${slot.expectedHex}")
                } &&
                    transcript.hasExactTerminalLine(RAW_PHASE_ONE_DONE)
            }
        } catch (failure: AssertionError) {
            // The failure is the diagnostic result: persist the delivered prefix
            // before rethrowing, even though the four-byte contract did not finish.
            captureViewport("issue1662-exact-raw-bytes-phase-1-timeout")
            throw failure
        }
        val observedPhaseOneBytes = extractPhaseOneBytes(phaseOneTranscript)
        observedRawPhases += observedPhaseOneBytes
        captureViewport("issue1662-exact-raw-bytes-phase-1")

        val phaseCReaderStartedAt = SystemClock.elapsedRealtime()
        sendCommandThroughTerminalInput(
            rawReaderCommand(
                byteCount = 2,
                ready = RAW_PHASE_C_READY,
                done = RAW_PHASE_C_DONE,
            ),
            "issue1662-raw-byte-reader-phase-c",
        )
        try {
            waitForVisibleTerminal("issue1662-raw-phase-c-ready", timeoutMillis = 20_000) {
                it.hasExactTerminalLine(RAW_PHASE_C_READY)
            }
            recordTiming(
                "raw_phase_c_reader_ready_ms",
                SystemClock.elapsedRealtime() - phaseCReaderStartedAt,
            )
            compose.onNodeWithTag(TERMINAL_HOTKEYS_PANEL_BACK_TAG)
                .assertIsEnabled()
                .performClick()
            val phaseCBaseline = actionDiagnosticBaseline()
            val phaseCDispatchStartedAt = SystemClock.elapsedRealtime()
            longClickPanelKey("^C")
            val phaseCDiagnostics = doubledControlDiagnosticsSince(phaseCBaseline)
            writeDoubledControlDiagnostics(
                phase = "c",
                baseline = phaseCBaseline,
                serverPaneId = serverPaneId,
                events = phaseCDiagnostics,
                gesture = "physical-long-click",
            )
            assertDoubledControlDiagnostics(
                phase = "C",
                key = "^C×2",
                byte = 3,
                serverPaneId = serverPaneId,
                events = phaseCDiagnostics,
            )
            waitForVisibleTerminal("issue1662-raw-phase-c", timeoutMillis = 20_000) { transcript ->
                transcript.hasExactTerminalLine(RAW_PHASE_C_BYTES) &&
                    transcript.hasExactTerminalLine(RAW_PHASE_C_DONE)
            }
            recordTiming(
                "raw_phase_c_dispatch_to_result_ms",
                SystemClock.elapsedRealtime() - phaseCDispatchStartedAt,
            )
            observedRawPhases += RAW_PHASE_C_BYTES
            captureViewport("issue1662-exact-raw-bytes-phase-c")
        } catch (failure: AssertionError) {
            recordTiming(
                "raw_phase_c_failure_elapsed_ms",
                SystemClock.elapsedRealtime() - phaseCReaderStartedAt,
            )
            captureViewport("issue1662-exact-raw-bytes-phase-c-timeout")
            writeTimings()
            throw failure
        }

        val phaseDReaderStartedAt = SystemClock.elapsedRealtime()
        sendCommandThroughTerminalInput(
            rawReaderCommand(
                byteCount = 2,
                ready = RAW_PHASE_D_READY,
                done = RAW_PHASE_D_DONE,
            ),
            "issue1662-raw-byte-reader-phase-d",
        )
        try {
            waitForVisibleTerminal("issue1662-raw-phase-d-ready", timeoutMillis = 20_000) {
                it.hasExactTerminalLine(RAW_PHASE_D_READY)
            }
            recordTiming(
                "raw_phase_d_reader_ready_ms",
                SystemClock.elapsedRealtime() - phaseDReaderStartedAt,
            )
            val phaseDBaseline = actionDiagnosticBaseline()
            val phaseDDispatchStartedAt = SystemClock.elapsedRealtime()
            longClickPanelKey("^D")
            val phaseDDiagnostics = doubledControlDiagnosticsSince(phaseDBaseline)
            writeDoubledControlDiagnostics(
                phase = "d",
                baseline = phaseDBaseline,
                serverPaneId = serverPaneId,
                events = phaseDDiagnostics,
                gesture = "physical-long-click",
            )
            assertDoubledControlDiagnostics(
                phase = "D",
                key = "^D×2",
                byte = 4,
                serverPaneId = serverPaneId,
                events = phaseDDiagnostics,
            )
            waitForVisibleTerminal("issue1662-raw-phase-d", timeoutMillis = 20_000) { transcript ->
                transcript.hasExactTerminalLine(RAW_PHASE_D_BYTES) &&
                    transcript.hasExactTerminalLine(RAW_PHASE_D_DONE)
            }
            recordTiming(
                "raw_phase_d_dispatch_to_result_ms",
                SystemClock.elapsedRealtime() - phaseDDispatchStartedAt,
            )
            observedRawPhases += RAW_PHASE_D_BYTES
            captureViewport("issue1662-exact-raw-bytes-phase-d")
        } catch (failure: AssertionError) {
            recordTiming(
                "raw_phase_d_failure_elapsed_ms",
                SystemClock.elapsedRealtime() - phaseDReaderStartedAt,
            )
            captureViewport("issue1662-exact-raw-bytes-phase-d-timeout")
            writeTimings()
            throw failure
        }

        val phaseTapReaderStartedAt = SystemClock.elapsedRealtime()
        sendCommandThroughTerminalInput(
            rawReaderCommand(
                byteCount = 4,
                ready = RAW_PHASE_TAP_CD_READY,
                done = RAW_PHASE_TAP_CD_DONE,
            ),
            "issue1662-raw-byte-reader-phase-tap-cd",
        )
        try {
            waitForVisibleTerminal("issue1662-raw-phase-tap-cd-ready", timeoutMillis = 20_000) {
                it.hasExactTerminalLine(RAW_PHASE_TAP_CD_READY)
            }
            recordTiming(
                "raw_phase_tap_cd_reader_ready_ms",
                SystemClock.elapsedRealtime() - phaseTapReaderStartedAt,
            )
            val phaseTapBaseline = actionDiagnosticBaseline()
            val phaseTapDispatchStartedAt = SystemClock.elapsedRealtime()
            listOf("^C", "^C", "^D", "^D").forEach(::tapPanelKey)
            val phaseTapDiagnostics = doubledControlDiagnosticsSince(phaseTapBaseline)
            assertOrdinaryTwoTapDiagnostics(
                serverPaneId = serverPaneId,
                events = phaseTapDiagnostics,
            )
            writeDoubledControlDiagnostics(
                phase = "ordinary-two-tap-cd",
                baseline = phaseTapBaseline,
                serverPaneId = serverPaneId,
                events = phaseTapDiagnostics,
                gesture = "four-physical-taps",
                artifactName = "issue1662-ordinary-two-tap-cd-diagnostics.txt",
            )
            waitForVisibleTerminal(
                "issue1662-raw-phase-tap-cd",
                timeoutMillis = 20_000,
            ) { transcript ->
                transcript.hasExactTerminalLine(RAW_PHASE_TAP_CD_BYTES) &&
                    transcript.hasExactTerminalLine(RAW_PHASE_TAP_CD_DONE)
            }
            recordTiming(
                "raw_phase_tap_cd_dispatch_to_result_ms",
                SystemClock.elapsedRealtime() - phaseTapDispatchStartedAt,
            )
            observedRawPhases += RAW_PHASE_TAP_CD_BYTES
            captureViewport("issue1662-exact-raw-bytes-phase-tap-cd")
        } catch (failure: AssertionError) {
            recordTiming(
                "raw_phase_tap_cd_failure_elapsed_ms",
                SystemClock.elapsedRealtime() - phaseTapReaderStartedAt,
            )
            captureViewport("issue1662-exact-raw-bytes-phase-tap-cd-timeout")
            writeTimings()
            throw failure
        }

        val combinedRawBytes = observedRawPhases.joinToString(" ")
        assertTrue(
            "combined raw-byte oracle must remain the exact #1662 contract; " +
                "expected='$RAW_BYTES_EXPECTED' observed='$combinedRawBytes'",
            combinedRawBytes == RAW_BYTES_EXPECTED,
        )
        writeText(
            "issue1662-raw-byte-summary.txt",
            "phase1=$observedPhaseOneBytes ready=$RAW_PHASE_ONE_READY done=$RAW_PHASE_ONE_DONE\n" +
                RAW_PHASE_ONE_SLOTS.joinToString(separator = "", postfix = "") { slot ->
                    "${slot.marker}=${slot.expectedHex}\n"
                } +
                "phaseC=$RAW_PHASE_C_BYTES ready=$RAW_PHASE_C_READY done=$RAW_PHASE_C_DONE\n" +
                "phaseD=$RAW_PHASE_D_BYTES ready=$RAW_PHASE_D_READY done=$RAW_PHASE_D_DONE\n" +
                "phaseTapCD=$RAW_PHASE_TAP_CD_BYTES ready=$RAW_PHASE_TAP_CD_READY " +
                "done=$RAW_PHASE_TAP_CD_DONE\n" +
                "combined=$combinedRawBytes\n",
        )
        // Preserve the completed C/D readiness + dispatch timings even if a
        // later, independent fake-agent assertion fails.
        writeTimings()
        captureFullDevice("issue1662-main-page-after-byte-oracle")

        // No `/` key in the panel (the maintainer's "duplicate /" complaint).
        assertTrue(
            "the hotkeys panel must NOT show a `/` key",
            compose.onAllNodesWithText("/", useUnmergedTree = true).fetchSemanticsNodes().isEmpty(),
        )
        captureViewport("issue784-02-hotkeys-panel-visible")
        // Advisory full-device frame so the reviewer sees the actual panel grid
        // (the terminal-only viewport capture does not include the Compose sheet).
        captureFullDevice("issue784-02b-hotkeys-panel-fulldevice")

        // ===== Agent Terminal oracle ========================================
        // Reuse the same pane but respawn the deterministic fake-Claude input
        // box and publish the recorded agent kind. This proves agent chrome
        // reaches the identical production sheet and byte path.
        compose.onNodeWithTag(TERMINAL_HOTKEYS_PANEL_CLOSE_TAG).performClick()
        val singleCReadyMarker = respawnFakeClaudeAgent(FAKE_AGENT_SINGLE_C_OWNER_EXIT)
        waitForVisibleTerminal("fake-agent-ready-for-single-c", timeoutMillis = 20_000) {
            it.hasExactTerminalLine(singleCReadyMarker)
        }
        reattachRespawnedAgentSession(singleCReadyMarker)
        selectTerminalSurfaceBeforeIme()
        compose.onNodeWithTag(TERMINAL_HOTKEYS_LAUNCHER_TAG, useUnmergedTree = true).performClick()
        typePendingLine(AGENT_DRAFT, "fake-agent-draft")
        waitForRemotePane("fake-agent draft visible") { it.contains("> $AGENT_DRAFT") }

        compose.onNodeWithTag(TERMINAL_HOTKEYS_CTRL_FLOW_TAG).performClick()
        tapPanelKey("^U")
        waitForRemotePane("Ctrl-U cleared fake-agent input") { capture ->
            capture.lines().lastOrNull { it.trimStart().startsWith(">") }?.trim() == ">"
        }
        captureViewport("issue1662-agent-ctrl-u-cleared")

        compose.onNodeWithTag(TERMINAL_HOTKEYS_PANEL_BACK_TAG).performClick()
        tapPanelKey("^C")
        waitForVisibleTerminal("single Ctrl-C exits fake agent", timeoutMillis = 20_000) {
            it.hasExactTerminalLine(FAKE_AGENT_EXIT) &&
                it.hasExactTerminalLine(FAKE_AGENT_SINGLE_C_OWNER_EXIT)
        }
        assertNamedSessionAndPaneLiveAfterFakeAgentExit(serverPaneId)
        captureViewport("issue1662-agent-single-ctrl-c")

        val doubleCReadyMarker = respawnFakeClaudeAgent(FAKE_AGENT_DOUBLE_C_OWNER_EXIT)
        waitForVisibleTerminal("fake-agent-ready-for-double", timeoutMillis = 20_000) {
            it.hasExactTerminalLine(doubleCReadyMarker)
        }
        longClickPanelKey("^C")
        waitForVisibleTerminal("doubled Ctrl-C exits fake agent", timeoutMillis = 20_000) {
            it.hasExactTerminalLine(FAKE_AGENT_EXIT) &&
                it.hasExactTerminalLine(FAKE_AGENT_DOUBLE_C_OWNER_EXIT)
        }
        captureViewport("issue1662-agent-doubled-ctrl-c")

        // ^C×2 exits the fake child, so respawn the SAME real fake-agent path
        // before exercising ^D×2. The generation-specific owner marker is only
        // printed after this child exits, preventing stale prior exit text from
        // satisfying the EOF oracle.
        val doubleDReadyMarker = respawnFakeClaudeAgent(FAKE_AGENT_DOUBLE_D_OWNER_EXIT)
        waitForVisibleTerminal("fake-agent-ready-for-doubled-d", timeoutMillis = 20_000) {
            it.hasExactTerminalLine(doubleDReadyMarker)
        }
        val agentDoubleDBaseline = actionDiagnosticBaseline()
        val agentDoubleDStartedAt = SystemClock.elapsedRealtime()
        longClickPanelKey("^D")
        val agentDoubleDDiagnostics = doubledControlDiagnosticsSince(agentDoubleDBaseline)
        writeDoubledControlDiagnostics(
            phase = "agent-doubled-ctrl-d",
            baseline = agentDoubleDBaseline,
            serverPaneId = serverPaneId,
            events = agentDoubleDDiagnostics,
            gesture = "physical-long-click",
            artifactName = "issue1662-agent-doubled-ctrl-d-dispatch-diagnostics.txt",
        )
        assertDoubledControlDiagnostics(
            phase = "agent doubled Ctrl-D",
            key = "^D×2",
            byte = 4,
            serverPaneId = serverPaneId,
            events = agentDoubleDDiagnostics,
        )
        try {
            waitForVisibleTerminal("doubled Ctrl-D exits fake agent", timeoutMillis = 20_000) {
                it.hasExactTerminalLine(FAKE_AGENT_EXIT) &&
                    it.hasExactTerminalLine(FAKE_AGENT_DOUBLE_D_OWNER_EXIT)
            }
            recordTiming(
                "agent_doubled_ctrl_d_dispatch_to_exit_ms",
                SystemClock.elapsedRealtime() - agentDoubleDStartedAt,
            )
            captureViewport("issue1662-agent-doubled-ctrl-d")
        } catch (failure: AssertionError) {
            recordTiming(
                "agent_doubled_ctrl_d_failure_elapsed_ms",
                SystemClock.elapsedRealtime() - agentDoubleDStartedAt,
            )
            captureViewport("issue1662-agent-doubled-ctrl-d-timeout")
            writeTimings()
            throw failure
        }
        captureFullDevice("issue1662-agent-main-page-fulldevice")

        // Advisory full-device frame showing the full panel grid.
        captureFullDevice("issue784-06-hotkeys-panel-fulldevice")

        writeTimings()
    } }

    /**
     * Issue #1091 / AC1 — the maintainer was TRAPPED in `nano` (a fully
     * Ctrl-driven TUI) on a real dogfood session: none of the keys nano needs to
     * save/exit (`^O` Write Out, `^X` Exit) were reachable from PocketShell. This
     * is the end-to-end reproduction on the real path (emulator + Docker `agents`
     * shell, the `nano` fixture added to `Dockerfile.agents`): open a fresh nano
     * buffer, type a marker line, then **save and exit entirely from the hotkeys
     * panel** — tap `^O` (Write Out), tap `Enter` to confirm the filename, tap
     * `^X` (Exit) — and prove from the authoritative visible-terminal transcript
     * that nano actually wrote the file to disk and the shell prompt returned.
     *
     * Load-bearing assertions (NOT a byte-level proxy — the real nano TUI):
     *  - nano prints `Wrote` only on a successful disk write → `^O` + `Enter`
     *    reached nano and saved.
     *  - a shell sentinel echoed AFTER `^X` proves nano exited back to an
     *    interactive prompt.
     *  - `cat` of the saved file reprints the typed marker → the bytes the panel
     *    sent through `sendControlInputToPane` genuinely persisted to disk.
     *
     * Authoritative artifacts: `issue1091-nano-*-viewport.png` +
     * `*-visible-terminal.txt` + `nano-summary.txt` + `timings.txt`.
     */
    @Test
    fun hotkeysPanelCtrlOAndCtrlXSaveAndExitNanoFromPanelControls() { runBlocking {
        // ===== Attach to the session =====
        attachSeededSession(
            readinessLabel = "nano-prompt-ready",
            timingLabel = "nano_attach_ms",
            captureLabel = "issue1091-nano-01-attached",
        )

        // ===== Open the hotkeys panel =====
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithTag(TERMINAL_HOTKEYS_LAUNCHER_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag(TERMINAL_HOTKEYS_LAUNCHER_TAG, useUnmergedTree = true).performClick()
        compose.onNodeWithTag(TERMINAL_HOTKEYS_CTRL_FLOW_TAG).performClick()
        // Issue #1662 keeps nano's controls on the dedicated Ctrl page.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("^X", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        listOf("^G", "^J", "^K", "^O", "^T", "^U", "^W", "^X", "^\\").forEach { label ->
            assertTrue(
                "expected the hotkeys panel to expose the nano control key '$label'",
                compose.onAllNodesWithText(label, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty(),
            )
        }

        // ===== Open a fresh nano buffer in the pane =====
        // Absorb the one-time first-send stray fragment: the VERY FIRST
        // onCreateInputConnection/commitText after attach can prepend a `-t%0`
        // tmux control-mode fragment to the line (observed `~ $ -t%0nano … :
        // not found`). Fire a throwaway Enter first so that fragment errors
        // harmlessly on its own line and the nano command lands on a clean prompt.
        terminalInputConnection().commitText("\n", 1)
        SystemClock.sleep(1_000)
        val nanoFile = "/tmp/ps-nano-$NANO_MARKER.txt"
        sendCommandThroughTerminalInput("nano $nanoFile", "nano-launch")
        waitForVisibleTerminal("nano-loaded", timeoutMillis = 30_000) { transcript ->
            // Wait for nano's ACTUAL UI (alt-screen) — its title bar `GNU nano`
            // or the `Write Out` / `Exit` helpbar entries. Crucially NOT a bare
            // `contains("nano")`, which would match the echoed `nano …` command
            // line and pass vacuously even if nano never launched.
            transcript.contains("GNU nano") ||
                transcript.contains("Write Out") ||
                transcript.contains("^X Exit")
        }
        captureViewport("issue1091-nano-02-editor-open")

        // Type a unique marker line into nano's buffer (no trailing newline — it
        // becomes the buffer's first line). This is what must end up on disk.
        typePendingLine(NANO_CONTENT, "nano-type")
        waitForVisibleTerminal("nano-typed", timeoutMillis = 20_000) { transcript ->
            transcript.contains(NANO_CONTENT)
        }
        captureViewport("issue1091-nano-03-typed")

        // ===== Save: tap `^O` (Write Out) from the panel, confirm with Enter ====
        val saveAt = SystemClock.elapsedRealtime()
        tapPanelKey("^O")
        // nano prompts `File Name to Write: <nanoFile>` (pre-filled from the arg).
        waitForVisibleTerminal("nano-write-prompt", timeoutMillis = 20_000) { transcript ->
            transcript.contains("File Name to Write") || transcript.contains("Write")
        }
        captureViewport("issue1091-nano-04-write-prompt")
        // Enter lives on the common page; Back changes page without dismissing.
        compose.onNodeWithTag(TERMINAL_HOTKEYS_PANEL_BACK_TAG).performClick()
        tapPanelKey(TmuxHotkeyEnterLabel)
        waitForVisibleTerminal("nano-wrote", timeoutMillis = 20_000) { transcript ->
            // nano prints `[ Wrote N line(s) ]` ONLY on a successful disk write.
            transcript.contains("Wrote")
        }
        recordTiming("nano_ctrl_o_save_ms", SystemClock.elapsedRealtime() - saveAt)
        captureViewport("issue1091-nano-05-saved")

        // ===== Exit: tap `^X` from the panel =====
        val exitAt = SystemClock.elapsedRealtime()
        compose.onNodeWithTag(TERMINAL_HOTKEYS_CTRL_FLOW_TAG).performClick()
        tapPanelKey("^X")
        // Back at the shell — prove interactivity AND that the file persisted by
        // cat-ing it and echoing a post-exit sentinel.
        SystemClock.sleep(750)
        sendCommandThroughTerminalInput(
            "echo $NANO_CAT_TAG; cat $nanoFile; echo $NANO_DONE",
            "nano-verify",
        )
        waitForVisibleTerminal("nano-exited-verified", timeoutMillis = 20_000) { transcript ->
            transcript.contains(NANO_DONE)
        }
        recordTiming("nano_ctrl_x_exit_to_prompt_ms", SystemClock.elapsedRealtime() - exitAt)
        captureViewport("issue1091-nano-06-exited-verified")

        writeText("nano-summary.txt", buildNanoSummary(nanoFile))
        writeTimings()

        // NOTE on durability: nano's `[ Wrote 1 line ]` confirmation is HARD-
        // asserted in-flow above (`waitForVisibleTerminal("nano-wrote") { contains
        // ("Wrote") }` throws if it never appears) and captured durably in
        // `issue1091-nano-05-saved-visible-terminal.txt`. It lives on nano's
        // ALTERNATE screen, which `^X` discards — so the final post-exit transcript
        // (the MAIN screen) no longer contains it. The durable post-exit proof of
        // the save is the `cat` re-read below: the bytes are on disk only if `^O`
        // + Enter actually wrote them.
        val transcript = visibleTerminalText()
        // AC1 — `^X` Exit returned to an interactive shell (the post-exit sentinel
        // only runs if nano actually quit and handed the PTY back to `sh`).
        assertTrue(
            "expected the post-exit sentinel '$NANO_DONE' proving `^X` exited nano " +
                "back to the shell prompt; got:\n$transcript",
            transcript.contains(NANO_DONE),
        )
        // AC1 — the file on disk holds exactly what we typed (cat reprints it after
        // the LAST `$NANO_CAT_TAG`, i.e. the command's output, not its echo), so
        // the panel's `^O` + Enter genuinely persisted the buffer through the real
        // path — the durable equivalent of nano's transient `Wrote` message.
        val catOutput = transcript.substringAfterLast(NANO_CAT_TAG)
        assertTrue(
            "expected `cat $nanoFile` after the `^X` exit to reprint the typed marker " +
                "'$NANO_CONTENT' from disk, proving the `^O` save persisted; got tail:\n$catOutput",
            catOutput.contains(NANO_CONTENT),
        )
    } }

    private fun buildNanoSummary(nanoFile: String): String = buildString {
        appendLine("issue=1091 scenario=nano-save-exit-from-hotkeys-panel")
        appendLine("host=$DEFAULT_HOST port=$DEFAULT_PORT user=$DEFAULT_USER session=$SESSION_NAME")
        appendLine("nano_file=$nanoFile")
        appendLine("nano_marker=$NANO_CONTENT")
        appendLine("saved_via=^O(panel)+Enter(panel)")
        appendLine("exited_via=^X(panel)")
        appendLine("artifacts:")
        listOf(
            "issue1091-nano-01-attached",
            "issue1091-nano-02-editor-open",
            "issue1091-nano-03-typed",
            "issue1091-nano-04-write-prompt",
            "issue1091-nano-05-saved",
            "issue1091-nano-06-exited-verified",
        ).forEach { appendLine("  $it-viewport.png + $it-visible-terminal.txt") }
    }

    /**
     * Tap a key labelled [label] inside the hotkeys panel sheet (scoped to a
     * descendant of [TERMINAL_HOTKEYS_PANEL_TAG] so it never collides with a
     * same-text chip elsewhere — e.g. the keyboard-down Enter chip).
     */
    private fun tapPanelKey(label: String) {
        compose.onNode(
            hasText(label)
                .and(hasClickAction())
                .and(hasAnyAncestor(hasTestTag(TERMINAL_HOTKEYS_PANEL_TAG))),
        )
            .assertIsEnabled()
            .performClick()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        SystemClock.sleep(150)
    }

    private fun longClickPanelKey(label: String) {
        compose.onNode(
            hasText(label)
                .and(hasClickAction())
                .and(hasAnyAncestor(hasTestTag(TERMINAL_HOTKEYS_PANEL_TAG))),
        )
            .assertIsEnabled()
            .performTouchInput { longClick() }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        SystemClock.sleep(150)
    }

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

    private fun rawReaderCommand(byteCount: Int, ready: String, done: String): String =
        "stty raw -echo && printf '$ready\\r\\n' && od -An -tx1 -N$byteCount && " +
            "stty sane && echo $done"

    /**
     * Keep the four UI taps consecutive so this still exercises the overlapping,
     * fire-and-forget exec boundary. The pane reads one byte at a time and prints
     * each slot immediately, though, instead of buffering all four inside one
     * `od -N4`. A loss now leaves the successfully delivered prefix in the
     * authoritative transcript (for example Q=11, BACKSLASH=1c, B1=02 before a
     * missing B2), while a reorder prints the wrong byte against the exact slot.
     */
    private fun phaseOneRawReaderCommand(): String = buildString {
        append("stty raw -echo && printf '$RAW_PHASE_ONE_READY\\r\\n'")
        RAW_PHASE_ONE_SLOTS.forEach { slot ->
            append(" && printf '${slot.marker}='")
            append(" && od -An -tx1 -N1 | tr -d ' \\n'")
            append(" && printf '\\r\\n'")
        }
        append(" && stty sane && echo $RAW_PHASE_ONE_DONE")
    }

    private fun extractPhaseOneBytes(transcript: String): String =
        RAW_PHASE_ONE_SLOTS.joinToString(" ") { slot ->
            val observed = Regex("${Regex.escape(slot.marker)}=([0-9a-f]{2})")
                .findAll(transcript)
                .lastOrNull()
                ?.groupValues
                ?.get(1)
            assertEquals(
                "${slot.marker} must expose its isolated one-byte od result",
                slot.expectedHex,
                observed,
            )
            requireNotNull(observed)
        }

    private fun String.hasExactTerminalLine(expected: String): Boolean =
        lineSequence().any { line -> line.trim() == expected }

    private fun diagnosticRecorder() =
        (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as App)
            .also { app ->
                assertTrue(
                    "the connected fixture must keep diagnostics recording enabled",
                    app.settingsRepository.settings.value.diagnosticsRecordingEnabled,
                )
            }
            .diagnosticRecorder

    private suspend fun actionDiagnosticBaseline(): Long =
        diagnosticRecorder().readEvents(
            DiagnosticEventFilter(category = "action"),
        ).maxOfOrNull(DiagnosticsEvent::sequence) ?: 0L

    private suspend fun doubledControlDiagnosticsSince(baseline: Long): List<DiagnosticsEvent> =
        diagnosticRecorder().readEvents(
            DiagnosticEventFilter(
                category = "action",
                sinceSequenceExclusive = baseline,
            ),
        ).filter { event ->
            event.name == "shortcut_sent" || event.name == "pane_control_input"
        }

    private suspend fun assertNoControlDiagnosticsSince(action: String, baseline: Long) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        SystemClock.sleep(150)
        val events = doubledControlDiagnosticsSince(baseline)
        writeText(
            "issue1662-$action-no-byte-diagnostics.txt",
            buildString {
                appendLine("action=$action")
                appendLine("baseline_sequence=$baseline")
                appendLine("control_event_count=${events.size}")
                events.forEach { event ->
                    appendLine(
                        "sequence=${event.sequence} ${phaseOneDiagnosticSignature(event)}",
                    )
                }
            },
        )
        assertEquals(
            "$action must navigate/dismiss without dispatching a shortcut or control byte",
            emptyList<DiagnosticsEvent>(),
            events,
        )
    }

    private suspend fun serverPaneId(): String {
        val result = execFixture(
            "tmux display-message -p -t ${shellQuote(SESSION_NAME)} '#{pane_id}'",
        )
        assertEquals(
            "server pane-id lookup must succeed before the dispatch proof",
            0,
            result.exitCode,
        )
        return result.stdout.trim().also { paneId ->
            assertTrue(
                "server pane-id lookup must return a tmux pane target; stdout='${result.stdout}'",
                paneId.startsWith("%"),
            )
        }
    }

    private fun assertPhaseOneDiagnostics(
        serverPaneId: String,
        events: List<DiagnosticsEvent>,
    ) {
        val expected = listOf(
            "shortcut_sent key=^Q pane=$serverPaneId",
            "pane_control_input byte=17 repeat=1 pane=$serverPaneId",
            "shortcut_sent key=^\\ pane=$serverPaneId",
            "pane_control_input byte=28 repeat=1 pane=$serverPaneId",
            "shortcut_sent key=^B pane=$serverPaneId",
            "pane_control_input byte=2 repeat=1 pane=$serverPaneId",
            "shortcut_sent key=^B pane=$serverPaneId",
            "pane_control_input byte=2 repeat=1 pane=$serverPaneId",
        )
        val observed = events.map(::phaseOneDiagnosticSignature)
        assertEquals(
            "phase-1 UI intent must record the exact shortcut/control ordering",
            expected,
            observed,
        )

        val diagnosticTargets = events.map { event ->
            when (event.name) {
                "shortcut_sent" -> event.metadata["paneId"]?.toString()
                "pane_control_input" -> event.metadata["pane"]?.toString()
                else -> null
            }
        }
        assertEquals(
            "every phase-1 diagnostic target must match the authoritative server pane id",
            List(expected.size) { serverPaneId },
            diagnosticTargets,
        )
    }

    private fun assertDoubledControlDiagnostics(
        phase: String,
        key: String,
        byte: Int,
        serverPaneId: String,
        events: List<DiagnosticsEvent>,
    ) {
        assertEquals(
            "phase $phase doubled-control intent must be exact and ordered",
            listOf(
                "shortcut_sent key=$key pane=$serverPaneId",
                "pane_control_input byte=$byte repeat=2 pane=$serverPaneId",
            ),
            events.map(::phaseOneDiagnosticSignature),
        )
        assertEquals(
            "phase $phase diagnostic targets must match the authoritative server pane id",
            listOf(serverPaneId, serverPaneId),
            events.map { event ->
                when (event.name) {
                    "shortcut_sent" -> event.metadata["paneId"]?.toString()
                    "pane_control_input" -> event.metadata["pane"]?.toString()
                    else -> null
                }
            },
        )
    }

    private fun assertOrdinaryTwoTapDiagnostics(
        serverPaneId: String,
        events: List<DiagnosticsEvent>,
    ) {
        assertEquals(
            "ordinary two-tap fallback must remain four independent repeat=1 actions",
            listOf(
                "shortcut_sent key=^C pane=$serverPaneId",
                "pane_control_input byte=3 repeat=1 pane=$serverPaneId",
                "shortcut_sent key=^C pane=$serverPaneId",
                "pane_control_input byte=3 repeat=1 pane=$serverPaneId",
                "shortcut_sent key=^D pane=$serverPaneId",
                "pane_control_input byte=4 repeat=1 pane=$serverPaneId",
                "shortcut_sent key=^D pane=$serverPaneId",
                "pane_control_input byte=4 repeat=1 pane=$serverPaneId",
            ),
            events.map(::phaseOneDiagnosticSignature),
        )
    }

    private fun phaseOneDiagnosticSignature(event: DiagnosticsEvent): String =
        when (event.name) {
            "shortcut_sent" ->
                "shortcut_sent key=${event.metadata["key"]} pane=${event.metadata["paneId"]}"
            "pane_control_input" ->
                "pane_control_input byte=${event.metadata["byte"]} " +
                    "repeat=${event.metadata["repeatCount"]} pane=${event.metadata["pane"]}"
            else -> "${event.name} metadata=${event.metadata}"
        }

    private fun writeDoubledControlDiagnostics(
        phase: String,
        baseline: Long,
        serverPaneId: String,
        events: List<DiagnosticsEvent>,
        gesture: String,
        artifactName: String = "issue1662-phase-$phase-dispatch-diagnostics.txt",
    ) {
        writeText(
            artifactName,
            buildString {
                appendLine("baseline_sequence=$baseline")
                appendLine("server_pane_id=$serverPaneId")
                appendLine("gesture=$gesture")
                events.forEach { event ->
                    appendLine(
                        "sequence=${event.sequence} ${phaseOneDiagnosticSignature(event)}",
                    )
                }
            },
        )
    }

    private fun writePhaseOneDiagnostics(
        baseline: Long,
        serverPaneId: String,
        events: List<DiagnosticsEvent>,
    ) {
        writeText(
            "issue1662-phase1-dispatch-diagnostics.txt",
            buildString {
                appendLine("baseline_sequence=$baseline")
                appendLine("server_pane_id=$serverPaneId")
                events.forEach { event ->
                    appendLine(
                        "sequence=${event.sequence} ${phaseOneDiagnosticSignature(event)}",
                    )
                }
            },
        )
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

    private fun showImeAndAssertVisible() {
        compose.activityRule.scenario.onActivity { activity ->
            val terminal = requireNotNull(activity.window.decorView.findTerminalView())
            terminal.requestFocus()
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(terminal, 0)
        }
        val imeVisible = waitForInputMethodVisible(
            compose.activityRule.scenario,
            expected = true,
        )
        assertTrue(describeRealImeRaiseFailure(), imeVisible)
    }

    /**
     * This journey deliberately keeps one real-IME leg because it verifies the
     * production keyboard-up launcher, not only synthetic inset geometry.
     * Distinguish a stolen activity window from an app-owned IME failure so a
     * red shard does not launder environmental focus loss into product blame.
     */
    private fun describeRealImeRaiseFailure(): String {
        val focus = awaitActivityWindowFocus(
            scenario = compose.activityRule.scenario,
            timeoutMs = 0L,
        )
        val prefix = if (!focus.focused) "$FOREIGN_WINDOW_FOCUS_SIGNATURE " else ""
        return prefix +
            "IME-up state must be observable before using the keyboard-up hotkeys " +
            "launcher. If the app window is focused, the real IME service or raise " +
            "path failed. ${focus.diagnosis}"
    }

    /**
     * Agent/presumed-agent sessions expose a tagged Terminal segment; a foreign
     * exec-sh shell can collapse to a single Terminal surface with no tab.
     * Either shape is valid. Keep selecting the tagged segment when present and
     * wait until neither Conversation placeholder nor pane remains mounted.
     */
    private fun selectTerminalSurfaceBeforeIme() {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < deadline) {
            compose.waitForIdle()
            val conversationGone =
                !hasSemanticsTag(TMUX_CONVERSATION_DETECTING_TAG) &&
                    !hasSemanticsTag(TMUX_CONVERSATION_PANE_TAG)
            if (conversationGone) return
            if (hasSemanticsTag(TMUX_TERMINAL_TAB_TAG)) {
                compose.onNodeWithTag(
                    TMUX_TERMINAL_TAB_TAG,
                    useUnmergedTree = true,
                ).performClick()
            }
            SystemClock.sleep(250)
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            !hasSemanticsTag(TMUX_CONVERSATION_DETECTING_TAG) &&
                !hasSemanticsTag(TMUX_CONVERSATION_PANE_TAG)
        }
    }

    private fun hasSemanticsTag(tag: String): Boolean =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()

    private fun attachSeededSession(
        readinessLabel: String,
        timingLabel: String,
        captureLabel: String,
    ) {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        val attachAt = SystemClock.elapsedRealtime()
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        waitForSessionInPicker(
            rule = compose,
            sessionName = SESSION_NAME,
            onStateNote = { note -> Log.i(LOG_TAG, "session picker: $note") },
            onRepoke = {
                repokeSessionPickerFromHostRow(
                    rule = compose,
                    hostRowTag = hostRowTag,
                    onStateNote = { note -> Log.i(LOG_TAG, "session picker: $note") },
                )
            },
        )
        compose.onNodeWithText(SESSION_NAME).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        waitForTerminalViewAttached()
        waitForVisibleTerminal(readinessLabel) { it.isNotBlank() }
        recordTiming(timingLabel, SystemClock.elapsedRealtime() - attachAt)
        captureViewport(captureLabel)
    }

    /**
     * The fake agent and its recorded kind are created out-of-band after the
     * shell oracle. The already-mounted session and the held picker tree
     * intentionally do not poll the server for kind changes, so exercise a real
     * back, explicit picker refresh, and warm reattach to reconcile the new pane
     * identity before asserting agent chrome.
     */
    private fun reattachRespawnedAgentSession(readyMarker: String) {
        val reattachStartedAt = SystemClock.elapsedRealtime()
        clickTmuxBack()
        waitForSessionInPicker(
            rule = compose,
            sessionName = SESSION_NAME,
            onStateNote = { note -> Log.i(LOG_TAG, "session picker: $note") },
            onRepoke = {
                repokeSessionPickerFromHostRow(
                    rule = compose,
                    hostRowTag = hostRowTag,
                    onStateNote = { note -> Log.i(LOG_TAG, "session picker: $note") },
                )
            },
        )
        compose.onNodeWithTag(FOLDER_LIST_PULL_TO_REFRESH_TAG, useUnmergedTree = true)
            .performTouchInput { swipeDown() }
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithContentDescription("Claude", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText(SESSION_NAME).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        waitForTerminalViewAttached()
        waitForVisibleTerminal("fake-agent-ready-after-reattach", timeoutMillis = 20_000) {
            it.hasExactTerminalLine(readyMarker)
        }
        recordTiming(
            "fake_agent_kind_reattach_ms",
            SystemClock.elapsedRealtime() - reattachStartedAt,
        )
    }

    private fun clickTmuxBack() {
        val backTag = listOf(
            TMUX_COMPACT_CHROME_BACK_BUTTON_TAG,
            TMUX_FULL_CHROME_BACK_BUTTON_TAG,
        ).firstOrNull(::hasSemanticsTag)
            ?: TMUX_FULL_CHROME_BACK_BUTTON_TAG
        compose.onNodeWithTag(backTag, useUnmergedTree = true)
            .assertIsEnabled()
            .performClick()
    }

    private fun assertSheetTargetsAboveImeOrImeDismissed(labels: List<String>) {
        var keyboardTopPx: Float? = null
        compose.activityRule.scenario.onActivity { activity ->
            val decor = activity.window.decorView
            val insets = ViewCompat.getRootWindowInsets(decor)
            if (insets?.isVisible(WindowInsetsCompat.Type.ime()) == true) {
                val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                keyboardTopPx = decor.height - imeBottom.toFloat()
            }
        }
        val keyboardTop = keyboardTopPx ?: return
        labels.forEach { label ->
            val nodes = compose.onAllNodesWithText(label, useUnmergedTree = true)
                .fetchSemanticsNodes()
            assertTrue("$label must be present for the IME containment proof", nodes.isNotEmpty())
            nodes.forEach { node ->
                assertTrue(
                    "$label must remain fully above the visible IME: " +
                        "bottom=${node.boundsInRoot.bottom} keyboardTop=$keyboardTop",
                    node.boundsInRoot.bottom <= keyboardTop + compose.density.density * 2f,
                )
            }
        }
    }

    private fun terminalInputConnection(): InputConnection {
        var connection: InputConnection? = null
        compose.activityRule.scenario.onActivity { activity ->
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

    private fun waitForVisibleTerminal(
        label: String,
        timeoutMillis: Long = TerminalTestTimeouts.terminalVisibilityTimeoutMs(),
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
        assertTrue(
            "expected visible terminal text for $label within ${timeoutMillis}ms; got:\n$last",
            satisfied && predicate(last),
        )
        return last
    }

    private fun captureViewport(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)

        var bitmap: Bitmap? = null
        compose.activityRule.scenario.onActivity { activity ->
            bitmap = captureViewToBitmap(
                activity.window.decorView.findTerminalView(),
                name,
            )
        }
        val captured = checkNotNull(bitmap) {
            "activity was not available to capture viewport '$name' (#2135)"
        }
        writeBitmap("$name-viewport", captured)
        writeText("$name-visible-terminal.txt", visibleTerminalText())
        captured.recycle()
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

    private suspend fun seedBeforeLaunch() {
        fixtureKey = readFixtureKey()
        trustedHostKeySha256 = waitForSshFixtureReady(SshKey.Pem(fixtureKey))
        seedTmuxSession(fixtureKey)
        hostRowTag = seedDockerHost(fixtureKey, "Issue1662 Hotkeys")
    }

    private suspend fun respawnFakeClaudeAgent(ownerExitMarker: String): String {
        val readyMarker = "$ownerExitMarker-READY"
        val fakeAgentScript =
            "trap : INT; " +
                "POCKETSHELL_FAKE_AGENT_READY_MARKER=${shellQuote(readyMarker)} " +
                "/usr/local/bin/pocketshell-fake-agent; " +
                "printf '\\r\\n%s\\r\\n' ${shellQuote(ownerExitMarker)}; " +
                "trap - INT; exec sh"
        val command = buildString {
            appendLine(
                "tmux respawn-pane -k -t ${shellQuote(SESSION_NAME)} " +
                    shellQuote("sh -lc ${shellQuote(fakeAgentScript)}"),
            )
            appendLine(
                "tmux set-option -p -t ${shellQuote(SESSION_NAME)} @ps_agent_kind claude",
            )
        }
        val result = execFixture(command)
        assertTrue(
            "expected fake-Claude respawn to succeed; exit=${result.exitCode} " +
                "stderr='${result.stderr}'",
            result.exitCode == 0,
        )
        return readyMarker
    }

    /**
     * Ctrl-C must end only the fake child, never the pane-owning shell. Prove
     * that contract against tmux itself before a second respawn could conceal a
     * dead pane by creating replacement state.
     */
    private suspend fun assertNamedSessionAndPaneLiveAfterFakeAgentExit(expectedPaneId: String) {
        val command = "tmux display-message -p -t ${shellQuote(SESSION_NAME)} " +
            shellQuote("#{session_name}|#{pane_id}|#{pane_dead}")
        val expectedTuple = "$SESSION_NAME|$expectedPaneId|0"
        val result = execFixture(command)
        writeText(
            "issue1662-agent-single-ctrl-c-session-pane-live.txt",
            buildString {
                appendLine("session=$SESSION_NAME")
                appendLine("original_pane_id=$expectedPaneId")
                appendLine("command=$command")
                appendLine("expected_tuple=$expectedTuple")
                appendLine("exit_code=${result.exitCode}")
                appendLine("stdout=${result.stdout.trimEnd()}")
                appendLine("stderr=${result.stderr.trimEnd()}")
            },
        )
        assertEquals(
            "post-agent Ctrl-C tmux liveness query must succeed; " +
                "stdout='${result.stdout}' stderr='${result.stderr}'",
            0,
            result.exitCode,
        )
        assertEquals(
            "after fake-agent Ctrl-C the same named session and pane must remain live; " +
                "exit=${result.exitCode} stdout='${result.stdout}' stderr='${result.stderr}'",
            expectedTuple,
            result.stdout.trim(),
        )
    }

    private suspend fun waitForRemotePane(
        label: String,
        predicate: (String) -> Boolean,
    ): String {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        var last = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            last = execFixture(
                "tmux capture-pane -p -t ${shellQuote(SESSION_NAME)}",
            ).stdout
            if (predicate(last)) return last
            SystemClock.sleep(200)
        }
        throw AssertionError("$label: remote pane condition not met; capture:\n$last")
    }

    private suspend fun execFixture(command: String): com.pocketshell.core.ssh.ExecResult =
        SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(fixtureKey),
            knownHosts = com.pocketshell.testssh.TEST_ACCEPT_ALL_HOST_KEYS,
            timeoutMs = 15_000,
        ).getOrThrow().use { session ->
            session.exec(command)
        }

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
                    trustedHostKeySha256 = trustedHostKeySha256,
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
            appendLine(
                "tmux set-option -t ${shellQuote(SESSION_NAME)} @ps_agent_kind shell; echo kind_set",
            )
            appendLine("tmux list-sessions")
        }
        val result = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = com.pocketshell.testssh.TEST_ACCEPT_ALL_HOST_KEYS,
            timeoutMs = 15_000,
        ).mapCatching { session -> session.use { it.exec(script) } }
        val exec = result.getOrNull()
        assertTrue(
            "expected tmux session seeding to succeed, got exception=" +
                "${result.exceptionOrNull()} stderr='${exec?.stderr}'",
            exec?.exitCode == 0 && exec.stdout.contains("kind_set"),
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
                    knownHosts = com.pocketshell.testssh.TEST_ACCEPT_ALL_HOST_KEYS,
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

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
        const val LOG_TAG: String = "Issue458KeyBar"
        const val DEVICE_DIR_NAME: String = "issue458-keybar-ctrl-combo"
        const val SESSION_NAME: String = "claude-main"
        const val READY_MARKER: String = "KEYBAR-READY"
        private val RAW_ORACLE_SUFFIX = System.currentTimeMillis().toString().takeLast(6)
        val RAW_PHASE_ONE_READY = "RAW1-READY-$RAW_ORACLE_SUFFIX"
        val RAW_PHASE_ONE_DONE = "RAW1-DONE-$RAW_ORACLE_SUFFIX"
        val RAW_PHASE_ONE_SLOTS: List<RawByteSlot> = listOf(
            RawByteSlot("RAW1-Q-$RAW_ORACLE_SUFFIX", "11"),
            RawByteSlot("RAW1-BACKSLASH-$RAW_ORACLE_SUFFIX", "1c"),
            RawByteSlot("RAW1-B1-$RAW_ORACLE_SUFFIX", "02"),
            RawByteSlot("RAW1-B2-$RAW_ORACLE_SUFFIX", "02"),
        )
        val RAW_PHASE_C_READY = "RAWC-READY-$RAW_ORACLE_SUFFIX"
        val RAW_PHASE_C_DONE = "RAWC-DONE-$RAW_ORACLE_SUFFIX"
        val RAW_PHASE_D_READY = "RAWD-READY-$RAW_ORACLE_SUFFIX"
        val RAW_PHASE_D_DONE = "RAWD-DONE-$RAW_ORACLE_SUFFIX"
        val RAW_PHASE_TAP_CD_READY = "RAWTAPCD-READY-$RAW_ORACLE_SUFFIX"
        val RAW_PHASE_TAP_CD_DONE = "RAWTAPCD-DONE-$RAW_ORACLE_SUFFIX"
        const val RAW_PHASE_ONE_BYTES = "11 1c 02 02"
        const val RAW_PHASE_C_BYTES = "03 03"
        const val RAW_PHASE_D_BYTES = "04 04"
        const val RAW_PHASE_TAP_CD_BYTES = "03 03 04 04"
        const val RAW_BYTES_EXPECTED = "11 1c 02 02 03 03 04 04 03 03 04 04"
        const val FAKE_AGENT_EXIT: String = "FAKE-AGENT-EXIT"
        val FAKE_AGENT_SINGLE_C_OWNER_EXIT = "FAKE-AGENT-SINGLE-C-EXIT-$RAW_ORACLE_SUFFIX"
        val FAKE_AGENT_DOUBLE_C_OWNER_EXIT = "FAKE-AGENT-DOUBLE-C-EXIT-$RAW_ORACLE_SUFFIX"
        val FAKE_AGENT_DOUBLE_D_OWNER_EXIT = "FAKE-AGENT-DOUBLE-D-EXIT-$RAW_ORACLE_SUFFIX"
        const val AGENT_DRAFT: String = "issue1662-agent-draft"
        val TmuxCtrlPageLabels: List<String> =
            listOf("QWERT", "YUIOP", "ASDFG", "HJKL", "ZXCVB", "NM\\")
                .flatMap { row -> row.map { "^$it" } }
        // Issue #1091 nano leg.
        val NANO_MARKER: String = System.currentTimeMillis().toString().takeLast(6)
        val NANO_CONTENT: String = "ISSUE1091-NANO-$NANO_MARKER"
        val NANO_CAT_TAG: String = "NANOCAT-$NANO_MARKER"
        val NANO_DONE: String = "NANODONE-$NANO_MARKER"
    }

    private data class RawByteSlot(
        val marker: String,
        val expectedHex: String,
    )
}
