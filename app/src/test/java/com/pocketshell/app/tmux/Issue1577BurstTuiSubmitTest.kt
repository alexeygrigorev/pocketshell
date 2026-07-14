package com.pocketshell.app.tmux

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.core.agents.AgentDetection
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.tmux.CommandResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #1577 (REOPENED, D31/D33/G10) — the REAL-path paste-burst swallow that
 * v0.4.35 did NOT fix (it was verified only against a happy [FakeTmuxClient]).
 *
 * Reproduced on real Codex 0.144.1: when the literal text and the submit CR land
 * in the SAME Codex stdin read batch, Codex's paste-burst heuristic SWALLOWS the
 * CR — `/goal resume` sits UNSUBMITTED in the composer input. Two app paths put the
 * text+CR in one batch on a busy, goal-annotated Codex pane:
 *  - Route A (Terminal tab): the #869 ack gate presence-matched the needle over the
 *    WHOLE viewport, so Codex's permanent `Goal blocked (/goal resume)` footer
 *    confirmed "ingested" INSTANTLY and fired Enter at the bare floor — before Codex
 *    had actually read the paste.
 *  - Route B (Conversation tab): the slash command was sent as raw `text+"\r"`
 *    through the keystroke lane with NO floor / NO ack gate at all.
 *
 * [BurstTuiFakeTmuxClient] is the scripted burst-TUI stand-in the brief asks for (no
 * Codex creds, wired into the Unit CI gate): it renders a permanent goal footer, and
 * SUBMITS a command only when the CR arrives in a read SEPARATE from the text (i.e.
 * the app waited until the pane rendered the typed text before pressing Enter) —
 * mimicking Codex's heuristic. The fix (count-baseline ack gate + routing Route B
 * through the same gated submit) makes both routes wait for a REAL ingestion (the
 * needle COUNT increasing over the footer's permanent occurrence) before Enter, so
 * the CR lands in its own batch and the command SUBMITS even on a busy Codex.
 *
 * RED on base (fix reverted): the gated path fires Enter on the footer presence →
 * text+CR one batch → NOT submitted. GREEN: the count-baseline ack gate holds Enter
 * until the typed text renders → submitted. The raw ungated path (Route B's DELETED
 * behaviour) is shown to swallow, proving why it had to be replaced.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class Issue1577BurstTuiSubmitTest : TmuxSessionViewModelTestBase() {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val footer = "gpt-5.6-sol medium · Context 42% · Goal blocked (/goal resume)"

    private fun codexDetection(): AgentDetection = AgentDetection(
        agent = AgentKind.Codex,
        sourcePath = "/home/u/.codex/sessions/rollout.jsonl",
        sessionId = "rollout",
        confidence = AgentDetection.Confidence.ProcessConfirmed,
    )

    private fun newBurstVm(client: BurstTuiFakeTmuxClient): TmuxSessionViewModel {
        val vm = newVm(applicationContext = context)
        vm.attachClientForTest(client)
        vm.startAgentConversationForTest("%0", codexDetection())
        vm.setAgentSubmitEnterDelayForTest(0)
        vm.setAgentSubmitAckTimeoutForTest(2_000L)
        // Reproduce the ON-DEVICE state: the app IS rendering the Codex goal footer,
        // so the send path's baseline cost-gate captures the authoritative pre-paste
        // needle count (1) and the ack gate requires an INCREASE, not mere presence.
        vm.localRenderTextOverrideForTest["%0"] = footer
        return vm
    }

    /**
     * Route A (Terminal tab, gated submit) — the maintainer's Codex Send+Enter. On a
     * BUSY Codex whose footer permanently shows `(/goal resume)`, the command must
     * SUBMIT: the count-baseline ack gate holds the Enter until the typed text renders
     * (a read separate from the CR), so Codex does not swallow the CR.
     *
     * RED on base (presence-only ack): Enter fires on the footer occurrence before the
     * text renders → text+CR one batch → NOT submitted.
     */
    @Test
    fun routeAGatedSubmitDeliversSlashCommandOnBusyCodexWithFooter() = runTest(scheduler) {
        val client = BurstTuiFakeTmuxClient(footer = footer, busyReadDelayCaptures = 1)
        val vm = newBurstVm(client)

        val result = async { vm.sendAgentPayloadToPaneResult("%0", "/goal resume", AgentKind.Codex) }
        advanceUntilIdle()
        assertTrue(result.await().isSuccess)

        assertEquals(
            "Route A: `/goal resume` must SUBMIT on a busy goal-annotated Codex — the " +
                "count-baseline ack gate holds Enter until the text renders (RED on base: " +
                "presence-ack fires on the footer → text+CR one batch → swallowed → not submitted)",
            listOf("/goal resume"),
            client.submittedCommands,
        )
    }

    /**
     * Route B (Conversation tab) — the FIX routes the slash command through the SAME
     * gated agent submit ([TmuxSessionViewModel.sendAgentPayloadToPaneResult]); this
     * drives that chokepoint directly (the exact call the composable now makes) and
     * proves it SUBMITS on the busy footer pane.
     */
    @Test
    fun routeBGatedSubmitDeliversSlashCommandOnBusyCodex() = runTest(scheduler) {
        val client = BurstTuiFakeTmuxClient(footer = footer, busyReadDelayCaptures = 1)
        val vm = newBurstVm(client)

        // The Conversation-tab TuiCommandNoEcho path now delivers via this gated call.
        val result = async { vm.sendAgentPayloadToPaneResult("%0", "/goal resume", AgentKind.Codex) }
        advanceUntilIdle()
        assertTrue(result.await().isSuccess)

        assertEquals(listOf("/goal resume"), client.submittedCommands)
    }

    /**
     * The DELETED Route B behaviour (raw ungated `text+"\r"` through the keystroke
     * lane) SWALLOWS the CR on a busy Codex — proving why the fix replaced it with the
     * gated submit. This is the standing red for Route B's old path.
     */
    @Test
    fun rawUngatedKeystrokeSendSwallowsSlashCommandOnBusyCodex() = runTest(scheduler) {
        val client = BurstTuiFakeTmuxClient(footer = footer, busyReadDelayCaptures = 1)
        val vm = newBurstVm(client)

        // Route B's OLD delivery: raw text + CR, no floor, no ack gate.
        val result = async {
            vm.writeInputToPaneResult("%0", "/goal resume\r".toByteArray(Charsets.UTF_8))
        }
        advanceUntilIdle()
        result.await()

        assertTrue(
            "the raw ungated text+CR send lands both in Codex's SAME stdin read batch → " +
                "the paste-burst heuristic swallows the CR → `/goal resume` is NOT submitted " +
                "(exactly why the fix routes Route B through the gated submit instead)",
            client.submittedCommands.isEmpty(),
        )
        assertTrue("the swallowed text sits UNSUBMITTED in the input box", client.inputBoxShowsPending())
    }

    /**
     * Class coverage — IDLE Codex (renders the paste immediately). The gated submit
     * still delivers exactly once: the ack observes the count increase on the first
     * poll and submits.
     */
    @Test
    fun gatedSubmitDeliversOnIdleCodexWithFooter() = runTest(scheduler) {
        val client = BurstTuiFakeTmuxClient(footer = footer, busyReadDelayCaptures = 0)
        val vm = newBurstVm(client)

        val result = async { vm.sendAgentPayloadToPaneResult("%0", "/goal resume", AgentKind.Codex) }
        advanceUntilIdle()
        assertTrue(result.await().isSuccess)

        assertEquals(listOf("/goal resume"), client.submittedCommands)
    }

    /**
     * Class coverage — a NORMAL non-slash prompt whose text is NOT already on the pane
     * still submits unchanged (no over-gating). Baseline 0 ⇒ the ack fires on the first
     * capture that shows the rendered text (presence == count-increase over 0).
     */
    @Test
    fun normalPromptStillSubmitsWithoutOverGating() = runTest(scheduler) {
        val client = BurstTuiFakeTmuxClient(footer = footer, busyReadDelayCaptures = 0)
        val vm = newVm(applicationContext = context)
        vm.attachClientForTest(client)
        vm.startAgentConversationForTest("%0", codexDetection())
        vm.setAgentSubmitEnterDelayForTest(0)
        vm.setAgentSubmitAckTimeoutForTest(2_000L)
        // The prompt text is NOT on the local render ⇒ baseline 0 ⇒ presence is a valid
        // ingestion signal (our paste is the ONLY occurrence).
        vm.localRenderTextOverrideForTest["%0"] = footer

        val result = async { vm.sendAgentPayloadToPaneResult("%0", "deploy the staging build", AgentKind.Codex) }
        advanceUntilIdle()
        assertTrue(result.await().isSuccess)

        assertEquals(listOf("deploy the staging build"), client.submittedCommands)
    }

    /**
     * Class coverage — the footer-present FALSE-MATCH itself: when the pane NEVER
     * renders the typed input (a fully wedged busy Codex), the ack gate must NOT
     * confirm on the permanent footer occurrence. It holds Enter to the bounded
     * fallback floor instead of firing early on the footer — proving the count-baseline
     * gate is not satisfied by the pre-existing `(/goal resume)`.
     */
    @Test
    fun ackGateDoesNotFalseConfirmOnPermanentFooterOccurrence() = runTest(scheduler) {
        // neverRenders = true: Codex is wedged and never echoes the paste, so the needle
        // count stays at the footer's baseline of 1 for the whole ack window.
        val client = BurstTuiFakeTmuxClient(footer = footer, busyReadDelayCaptures = 1, neverRenders = true)
        val vm = newBurstVm(client)

        val result = async { vm.sendAgentPayloadToPaneResult("%0", "/goal resume", AgentKind.Codex) }
        advanceUntilIdle()
        // The send still completes (fallback floor), but it must have POLLED capture-pane
        // more than once — i.e. it did NOT accept the footer as an instant ack (RED on
        // base: presence-ack fires on the first poll → exactly one poll).
        assertTrue(result.await().isSuccess)
        assertTrue(
            "the count-baseline ack must NOT confirm on the permanent footer occurrence — " +
                "it polls to the bounded fallback (multiple polls), not a first-poll footer match " +
                "(pollCount=${client.capturePaneTextViaExecCalls.size})",
            client.capturePaneTextViaExecCalls.size > 2,
        )
        assertFalse(
            "a wedged Codex that never renders the paste must not report a swallowed-then-" +
                "spuriously-submitted state from the footer",
            client.submittedCommands.contains("/goal resume"),
        )
    }
}

/**
 * Issue #1577b: a scripted stand-in for Codex's stdin read-batch + paste-burst
 * heuristic — the deterministic CI tier the investigation designed (no OpenAI
 * creds). It renders a permanent goal [footer] and:
 *  - `send-keys -l … -- '<text>'` types <text> into a pending stdin buffer that
 *    Codex has NOT read yet (a busy event loop);
 *  - `capture-pane` models Codex catching up: after [busyReadDelayCaptures] captures
 *    since the paste, Codex reads the pending TEXT and RENDERS it in the input box
 *    (the needle count goes 1 → 2). [neverRenders] models a fully wedged Codex;
 *  - `send-keys … Enter` SUBMITS only when the text was ALREADY read+rendered (the
 *    CR arrives in a read SEPARATE from the text). If the text is still unread when
 *    the Enter arrives, the CR joins the text in ONE batch and the paste-burst
 *    heuristic SWALLOWS it — nothing is submitted (the on-device bug).
 */
internal class BurstTuiFakeTmuxClient(
    private val footer: String,
    private val busyReadDelayCaptures: Int,
    private val neverRenders: Boolean = false,
) : FakeTmuxClient() {

    private var pendingText: String? = null
    private var textRendered: Boolean = false
    private var capturesSincePaste: Int = 0
    private var submitted: Boolean = false
    val submittedCommands: MutableList<String> = mutableListOf()

    private val literalRegex = Regex("^send-keys -l -t \\S+ -- '(.*)'$")
    private val enterRegex = Regex("^send-keys -t \\S+ Enter$")

    fun inputBoxShowsPending(): Boolean = pendingText != null && !submitted

    private fun interpret(cmd: String) {
        literalRegex.find(cmd)?.let { m ->
            pendingText = m.groupValues[1].replace("'\\''", "'")
            textRendered = false
            submitted = false
            capturesSincePaste = 0
            return
        }
        if (enterRegex.matches(cmd)) {
            val text = pendingText
            if (text != null && textRendered && !submitted) {
                // The text was read+rendered in a PRIOR read ⇒ this CR is its own read
                // ⇒ Codex submits. If the text is still unread (textRendered == false),
                // the CR batches with it and the paste-burst heuristic swallows it.
                submitted = true
                submittedCommands.add(text)
            }
        }
    }

    private fun advanceCodexReadOnCapture() {
        if (neverRenders) return
        val text = pendingText ?: return
        if (textRendered) return
        capturesSincePaste += 1
        if (capturesSincePaste > busyReadDelayCaptures) {
            textRendered = true
        }
    }

    private fun renderLines(): List<String> {
        val lines = mutableListOf(footer)
        val text = pendingText
        if (submitted && text != null) {
            lines.add("■ submitted $text: thread/goal set")
        } else if (textRendered && text != null) {
            lines.add("› $text")
        }
        return lines
    }

    override suspend fun sendCommand(cmd: String): CommandResponse {
        interpret(cmd)
        return super.sendCommand(cmd)
    }

    override suspend fun sendBestEffortCommand(cmd: String): CommandResponse {
        interpret(cmd)
        return super.sendBestEffortCommand(cmd)
    }

    override suspend fun capturePaneTextViaExec(
        paneId: String,
        timeoutMs: Long?,
        scrollbackLines: Int,
    ): CommandResponse {
        capturePaneTextViaExecCalls += paneId
        capturePaneTextViaExecScrollbackLines += scrollbackLines
        advanceCodexReadOnCapture()
        return CommandResponse(number = 0L, output = renderLines(), isError = false)
    }
}
