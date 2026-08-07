package com.pocketshell.app.tmux

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.composer.InMemoryOutboundQueueStore
import com.pocketshell.app.composer.OutboundItem
import com.pocketshell.core.agents.AgentDetection
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.agents.ConversationRole
import com.pocketshell.core.agents.MessageSendState
import com.pocketshell.core.tmux.CommandResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
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
    private lateinit var durableStore: InMemoryOutboundQueueStore

    private fun codexDetection(): AgentDetection = AgentDetection(
        agent = AgentKind.Codex,
        sourcePath = "/home/u/.codex/sessions/rollout.jsonl",
        sessionId = "rollout",
        confidence = AgentDetection.Confidence.ProcessConfirmed,
    )

    private fun newBurstVm(client: BurstTuiFakeTmuxClient): TmuxSessionViewModel {
        durableStore = InMemoryOutboundQueueStore()
        val vm = newVm(applicationContext = context, outboundQueueStore = durableStore)
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

    private fun durableRow(id: String, payload: String): DurableOutboundRowIdentity {
        durableStore.enqueueExisting(
            OutboundItem(
                id = id,
                sessionKey = "session-a",
                cleanText = payload,
                paneId = "%0",
                createdAtMs = 1L,
            ),
        )
        return DurableOutboundRowIdentity("session-a", id)
    }

    private fun transcriptUserTurn(
        id: String,
        payload: String,
        sendState: MessageSendState = MessageSendState.Confirmed,
    ): ConversationEvent.Message =
        ConversationEvent.Message(
            id = id,
            agent = AgentKind.Codex,
            atMillis = 1L,
            role = ConversationRole.User,
            text = payload,
            sendState = sendState,
        )

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
     * Issue #1944: a successful tmux Enter write is not yet proof that the TUI has
     * consumed that Enter and reopened its input loop. The durable FIFO worker used
     * to prune row A and paste row B immediately. On a redraw/debounce boundary the
     * TUI can echo B (so the pre-Enter paste ack succeeds) while still consuming A;
     * B's Enter is then swallowed even though tmux reports success, and both durable
     * rows are pruned.
     *
     * This fake requires one clean post-submit observation before it accepts the next
     * turn's Enter. RED on the #1944 implementation before the submit-turnover gate:
     * both calls return success, but only the first command is submitted.
     */
    @Test
    fun consecutiveQueuedPromptsWaitForPreviousSubmitTurnover() = runTest(scheduler) {
        val client = BurstTuiFakeTmuxClient(
            footer = footer,
            busyReadDelayCaptures = 0,
            requirePostSubmitObservationBeforeNextEnter = true,
        )
        val vm = newBurstVm(client)

        assertTrue(
            vm.sendAgentPayloadToPaneResult(
                "%0", "first queued prompt", AgentKind.Codex,
                sendToken = "row-a",
                durableRow = durableRow("row-a", "first queued prompt"),
            ).isSuccess,
        )
        assertTrue(
            vm.sendAgentPayloadToPaneResult(
                "%0", "second queued prompt", AgentKind.Codex,
                sendToken = "row-b",
                durableRow = durableRow("row-b", "second queued prompt"),
            ).isSuccess,
        )

        assertEquals(
            "the FIFO worker must not prune B until the TUI has consumed A's submit and " +
                "reopened the input turn",
            listOf("first queued prompt", "second queued prompt"),
            client.submittedCommands,
        )
    }

    @Test
    fun transientNoPromptRedrawCannotAuthorizeNextQueuedPaste() = runTest(scheduler) {
        val client = BurstTuiFakeTmuxClient(
            footer = footer,
            busyReadDelayCaptures = 0,
            transientNoPromptBeforeEnterConsumption = true,
        )
        val vm = newBurstVm(client)

        val firstResult = vm.sendAgentPayloadToPaneResult(
                "%0", "first queued prompt", AgentKind.Codex,
                sendToken = "transient-row-a",
                durableRow = durableRow("transient-row-a", "first queued prompt"),
            )
        assertTrue(firstResult.exceptionOrNull()?.message, firstResult.isSuccess)
        assertTrue(
            vm.sendAgentPayloadToPaneResult(
                "%0", "second queued prompt", AgentKind.Codex,
                sendToken = "transient-row-b",
                durableRow = durableRow("transient-row-b", "second queued prompt"),
            ).isSuccess,
        )

        assertFalse(
            "row B must not be pasted while row A's Enter is still unconsumed",
            client.pastedBeforePreviousEnterConsumed,
        )
        assertEquals(listOf("first queued prompt", "second queued prompt"), client.submittedCommands)
    }

    @Test
    fun newConfirmedTranscriptTurnAcknowledgesFramedPromptlessSubmit() = runTest(scheduler) {
        val payload = "recorded prompt"
        lateinit var vm: TmuxSessionViewModel
        val client = BurstTuiFakeTmuxClient(
            footer = footer,
            busyReadDelayCaptures = 0,
            forceNoPromptFrames = true,
            onEnter = {
                vm.appendAgentEventsForTest("%0", listOf(transcriptUserTurn("confirmed-new", payload)))
            },
        )
        vm = newBurstVm(client)
        vm.setAgentTranscriptAuthorityForTest("%0", true)

        val result = vm.sendAgentPayloadToPaneResult(
            "%0", payload, AgentKind.Codex,
            sendToken = "transcript-row",
            durableRow = durableRow("transcript-row", payload),
        )

        assertTrue(result.exceptionOrNull()?.message, result.isSuccess)
    }

    @Test
    fun transcriptAuthorityMayBecomeActiveAfterSubmitBaseline() = runTest(scheduler) {
        val payload = "reconnect tail startup prompt"
        lateinit var vm: TmuxSessionViewModel
        val client = BurstTuiFakeTmuxClient(
            footer = footer,
            busyReadDelayCaptures = 0,
            forceNoPromptFrames = true,
            onEnter = {
                vm.setAgentTranscriptAuthorityForTest("%0", true)
                vm.appendAgentEventsForTest("%0", listOf(transcriptUserTurn("confirmed-after-start", payload)))
            },
        )
        vm = newBurstVm(client)
        vm.setAgentTranscriptAuthorityStartingForTest("%0", true)

        val result = vm.sendAgentPayloadToPaneResult(
            "%0", payload, AgentKind.Codex,
            sendToken = "tail-start-row",
            durableRow = durableRow("tail-start-row", payload),
        )

        assertTrue(result.exceptionOrNull()?.message, result.isSuccess)
    }

    @Test
    fun transcriptTurnAfterGenericCeilingStillAcknowledgesWithinTailBound() = runTest(scheduler) {
        val payload = "tail poll phase prompt"
        lateinit var vm: TmuxSessionViewModel
        val client = BurstTuiFakeTmuxClient(
            footer = footer,
            busyReadDelayCaptures = 0,
            forceNoPromptFrames = true,
            onEnter = {
                backgroundScope.launch {
                    delay(1_200L)
                    vm.appendAgentEventsForTest(
                        "%0",
                        listOf(transcriptUserTurn("confirmed-after-tail-poll", payload)),
                    )
                }
            },
        )
        vm = newBurstVm(client)
        vm.setAgentTranscriptAuthorityForTest("%0", true)
        val startedAt = testScheduler.currentTime

        val result = vm.sendAgentPayloadToPaneResult(
            "%0", payload, AgentKind.Codex,
            sendToken = "tail-poll-row",
            durableRow = durableRow("tail-poll-row", payload),
        )

        val elapsed = testScheduler.currentTime - startedAt
        assertTrue(result.exceptionOrNull()?.message, result.isSuccess)
        assertTrue("ack must arrive after the generic 800ms ceiling: elapsed=$elapsed", elapsed > 800L)
        assertTrue("ack must remain inside the 2s transcript ceiling: elapsed=$elapsed", elapsed < 2_000L)
    }

    /**
     * Issue #1526 recurrence after #1944: Enter can reach the agent while its
     * authoritative transcript tail lands just after the bounded turnover wait.
     * The durable submit write-ahead correctly prevents another blind Enter, but
     * it must not wedge the row forever: a later retry can safely complete from a
     * new confirmed turn on the exact source that was baselined before Enter.
     */
    @Test
    fun lateAuthoritativeTranscriptAckCompletesSubmitAttemptWithoutSecondEnter() = runTest(scheduler) {
        val payload = "late transcript acknowledgement"
        lateinit var vm: TmuxSessionViewModel
        val client = BurstTuiFakeTmuxClient(
            footer = footer,
            busyReadDelayCaptures = 0,
            forceNoPromptFrames = true,
            onEnter = {
                backgroundScope.launch {
                    delay(2_100L)
                    vm.appendAgentEventsForTest(
                        "%0",
                        listOf(transcriptUserTurn("confirmed-after-timeout", payload)),
                    )
                }
            },
        )
        vm = newBurstVm(client)
        vm.setAgentTranscriptAuthorityForTest("%0", true)
        val row = durableRow("late-ack-row", payload)

        val first = vm.sendAgentPayloadToPaneResult(
            "%0", payload, AgentKind.Codex,
            sendToken = "late-ack-row",
            durableRow = row,
        )
        assertTrue("the first bounded turnover wait must retain the row", first.isFailure)
        assertEquals(1, client.sentCommands.count { it.endsWith(" Enter") })

        advanceTimeBy(100L)
        runCurrent()
        val retry = vm.sendAgentPayloadToPaneResult(
            "%0", payload, AgentKind.Codex,
            sendToken = "late-ack-row",
            durableRow = row,
        )

        assertTrue(retry.exceptionOrNull()?.message, retry.isSuccess)
        assertEquals(
            "late transcript authority proves the original Enter; retry must not duplicate it",
            1,
            client.sentCommands.count { it.endsWith(" Enter") },
        )
    }

    /**
     * Issue #2037: the authoritative turn can arrive after the bounded send call
     * already returned failure. The exact durable row must be terminally
     * acknowledged by that late turn itself; requiring a user Retry leaves a
     * delivered prompt rendered as queued/retryable and makes the UI invite a
     * duplicate send.
     */
    @Test
    fun lateAuthoritativeTranscriptAckAutomaticallyPrunesExactDeliveredRowWithoutRetry() = runTest(scheduler) {
        val payload = "make sure the links are preserved"
        lateinit var vm: TmuxSessionViewModel
        val client = BurstTuiFakeTmuxClient(
            footer = footer,
            busyReadDelayCaptures = 0,
            forceNoPromptFrames = true,
        )
        vm = newBurstVm(client)
        vm.setAgentTranscriptAuthorityForTest("%0", true)
        durableStore.enqueueExisting(
            OutboundItem(
                id = "late-auto-ack-row",
                sessionKey = "session-a",
                cleanText = payload,
                paneId = "%0",
                sendKey = "send-key-late-auto-ack",
                tmuxSessionId = "tmux-session-a",
                tmuxSessionCreated = 11L,
                createdAtMs = 1L,
            ),
        )
        val claimed = requireNotNull(durableStore.claim("late-auto-ack-row"))
        val row = DurableOutboundRowIdentity(claimed.sessionKey, claimed.id)

        val first = vm.sendAgentPayloadToPaneResult(
            "%0", payload, AgentKind.Codex,
            sendToken = row.rowId,
            durableRow = row,
        )
        assertTrue("the bounded turnover wait must leave the exact row unresolved", first.isFailure)
        assertTrue(durableStore.hasWireSubmitAttempt(row.sessionKey, row.rowId))
        assertEquals(1, client.sentCommands.count { it.endsWith(" Enter") })
        durableStore.markFailed(row.rowId, "bounded acknowledgement pending", 1L)

        // This is the real JSONL-tail append boundary. No Retry/send call follows.
        vm.appendAgentEventsForTest(
            "%0",
            listOf(transcriptUserTurn("confirmed-late-authoritative", payload)),
        )
        runCurrent()

        val resolved = resolveLateOutboundAcks(
            rows = durableStore.itemsFor(row.sessionKey),
            binding = TmuxOutboundQueueBinding(
                targetKey = row.sessionKey,
                fallbackKey = "fallback",
                durableKey = row.sessionKey,
                tmuxSessionId = "tmux-session-a",
                sessionCreated = 11L,
                generationPaneIds = setOf("%0"),
            ),
            resolveAuthoritativeAck = vm::resolveLateAuthoritativeOutboundAck,
        )
        val stale = resolved.single()

        // Deterministic manual-Retry interleaving: authority was resolved from
        // attempt 1, then Retry advances the durable generation before the
        // compare-and-prune. The stale prune must reject, and (critically) the
        // transcript evidence must remain available because no prune owned it.
        durableStore.markFailed(stale.id, "manual retry", 2L)
        requireNotNull(durableStore.requeueForRetry(stale.id))
        val retry = requireNotNull(durableStore.claim(stale.id))
        durableStore.markWireSubmitAttempted(
            retry.sessionKey,
            retry.id,
            stale.wireSubmitTranscriptBaseline,
        )
        val stalePruned = durableStore.acknowledgeLateDelivered(
            stale.id,
            stale.sendKey,
            stale.wireAttemptGeneration,
        )
        if (stalePruned) vm.consumeLateAuthoritativeOutboundAck(stale)
        assertFalse("attempt-1 evidence cannot prune retry generation 2", stalePruned)
        assertEquals(2, requireNotNull(durableStore.item(stale.id)).attemptCount)

        val current = requireNotNull(durableStore.item(stale.id))
        val retryResult = vm.sendAgentPayloadToPaneResult(
            "%0", payload, AgentKind.Codex,
            sendToken = current.id,
            durableRow = DurableOutboundRowIdentity(current.sessionKey, current.id),
        )
        assertTrue(
            "manual Retry must reuse the retained late-success evidence",
            retryResult.isSuccess,
        )
        // AlreadyLanded is a normal successful callback result. Production's
        // collector therefore takes Delivered -> markSendDelivered; it does not
        // inject another pending/requeue step after success.
        assertTrue(durableStore.markDelivered(current.id))

        assertTrue(
            "the late confirmed exact-payload turn must prune the already-delivered durable row",
            durableStore.item(row.rowId) == null,
        )
        assertEquals(
            "late acknowledgement must never issue a second submit Enter",
            1,
            client.sentCommands.count { it.endsWith(" Enter") },
        )
        assertEquals(
            "late acknowledgement must never paste the payload a second time",
            1,
            client.sentCommands.count { it.startsWith("send-keys -l ") },
        )
    }

    @Test
    fun preExistingOrOptimisticTranscriptTurnsDoNotAcknowledgeSubmit() = runTest(scheduler) {
        val payload = "identical prompt"
        val client = BurstTuiFakeTmuxClient(
            footer = footer,
            busyReadDelayCaptures = 0,
            forceNoPromptFrames = true,
        )
        val vm = newBurstVm(client)
        vm.setAgentTranscriptAuthorityForTest("%0", true)
        vm.appendAgentEventsForTest(
            "%0",
            listOf(
                transcriptUserTurn("confirmed-existing", payload),
                transcriptUserTurn("pending-non-prefix", payload, MessageSendState.Pending),
            ),
        )

        val result = vm.sendAgentPayloadToPaneResult(
            "%0", payload, AgentKind.Codex,
            sendToken = "existing-row",
            durableRow = durableRow("existing-row", payload),
        )

        assertTrue("no new authoritative transcript turn must retain the row", result.isFailure)

        val retry = vm.sendAgentPayloadToPaneResult(
            "%0", payload, AgentKind.Codex,
            sendToken = "existing-row",
            durableRow = DurableOutboundRowIdentity("session-a", "existing-row"),
        )
        assertTrue("the persisted baseline must not accept an old identical turn", retry.isFailure)
        assertEquals(1, client.sentCommands.count { it.endsWith(" Enter") })
    }

    @Test
    fun confirmedTurnFromWrongPaneDoesNotAcknowledgeSubmit() = runTest(scheduler) {
        val payload = "pane-bound prompt"
        lateinit var vm: TmuxSessionViewModel
        val client = BurstTuiFakeTmuxClient(
            footer = footer,
            busyReadDelayCaptures = 0,
            forceNoPromptFrames = true,
            onEnter = {
                vm.appendAgentEventsForTest("%1", listOf(transcriptUserTurn("wrong-pane", payload)))
            },
        )
        vm = newBurstVm(client)
        vm.setAgentTranscriptAuthorityForTest("%0", true)

        val result = vm.sendAgentPayloadToPaneResult(
            "%0", payload, AgentKind.Codex,
            sendToken = "wrong-pane-row",
            durableRow = durableRow("wrong-pane-row", payload),
        )

        assertTrue("a foreign pane transcript must retain the row", result.isFailure)
    }

    @Test
    fun confirmedTurnAfterDetectionSourceChangeDoesNotAcknowledgeSubmit() = runTest(scheduler) {
        val payload = "source-bound prompt"
        lateinit var vm: TmuxSessionViewModel
        val client = BurstTuiFakeTmuxClient(
            footer = footer,
            busyReadDelayCaptures = 0,
            forceNoPromptFrames = true,
            onEnter = {
                vm.startAgentConversationForTest(
                    "%0",
                    codexDetection().copy(sourcePath = "/home/u/.codex/sessions/other.jsonl"),
                    listOf(transcriptUserTurn("wrong-source", payload)),
                )
            },
        )
        vm = newBurstVm(client)
        vm.setAgentTranscriptAuthorityForTest("%0", true)

        val result = vm.sendAgentPayloadToPaneResult(
            "%0", payload, AgentKind.Codex,
            sendToken = "wrong-source-row",
            durableRow = durableRow("wrong-source-row", payload),
        )

        assertTrue("a replacement detection/source must retain the row", result.isFailure)

        val retry = vm.sendAgentPayloadToPaneResult(
            "%0", payload, AgentKind.Codex,
            sendToken = "wrong-source-row",
            durableRow = DurableOutboundRowIdentity("session-a", "wrong-source-row"),
        )
        assertTrue("a late turn from a replacement source must not resolve the old submit", retry.isFailure)
        assertEquals(1, client.sentCommands.count { it.endsWith(" Enter") })
    }

    /**
     * Issue #1944's false-success discriminator: tmux accepts Enter, but the
     * agent never consumes it and its pane never advances. The send result must
     * remain ambiguous/failure so the composer cannot mark the durable row Sent
     * and prune it.
     */
    @Test
    fun enterWriteWithoutAgentTurnoverIsNotDeliverySuccess() = runTest(scheduler) {
        val client = BurstTuiFakeTmuxClient(
            footer = footer,
            busyReadDelayCaptures = 0,
            neverConsumesEnter = true,
        )
        val vm = newBurstVm(client)
        val startedAt = testScheduler.currentTime

        val result = vm.sendAgentPayloadToPaneResult(
            "%0",
            "durable prompt must remain queued",
            AgentKind.Codex,
            sendToken = "durable-row",
            durableRow = durableRow("durable-row", "durable prompt must remain queued"),
        )

        assertTrue("tmux write completion without pane turnover is ambiguous", result.isFailure)
        assertTrue("the fake must have accepted the Enter command", client.sentCommands.any { it.endsWith(" Enter") })
        assertTrue("the agent did not semantically submit the row", client.submittedCommands.isEmpty())
        val elapsed = testScheduler.currentTime - startedAt
        assertTrue("generic turnover must retain its 800ms ceiling: elapsed=$elapsed", elapsed < 1_000L)
    }

    /**
     * Issue #1944 / #1207: a catalog-approved TUI-only control has no transcript
     * turn by contract. Its acknowledged tmux Enter therefore completes the
     * durable control row without weakening the turnover oracle for prompts.
     */
    @Test
    fun tuiOnlyControlUsesAcknowledgedEnterInsteadOfImpossibleTranscriptTurn() = runTest(scheduler) {
        val client = BurstTuiFakeTmuxClient(
            footer = footer,
            busyReadDelayCaptures = 0,
            neverConsumesEnter = true,
        )
        val vm = newBurstVm(client)
        vm.setAgentTranscriptAuthorityForTest("%0", true)

        val result = vm.sendAgentPayloadToPaneResult(
            "%0",
            "/model",
            AgentKind.ClaudeCode,
            sendToken = "tui-control-row",
            durableRow = durableRow("tui-control-row", "/model"),
            deliveryProof = AgentSubmitDeliveryProof.TmuxEnterAccepted,
        )

        assertTrue(result.isSuccess)
        assertTrue(client.sentCommands.any { it.endsWith(" Enter") })
    }

    /** An animated spinner/footer is not row-correlated submit evidence. */
    @Test
    fun unrelatedFrameChangeWhilePayloadRemainsInInputIsNotDeliverySuccess() = runTest(scheduler) {
        val client = BurstTuiFakeTmuxClient(
            footer = footer,
            busyReadDelayCaptures = 0,
            neverConsumesEnter = true,
            animateUnrelatedFrame = true,
        )
        val vm = newBurstVm(client)

        val result = vm.sendAgentPayloadToPaneResult(
            "%0",
            "payload still visible in agent input",
            AgentKind.Codex,
            sendToken = "animated-row",
            durableRow = durableRow("animated-row", "payload still visible in agent input"),
        )

        assertTrue("unrelated animation must not authorize durable prune", result.isFailure)
        assertTrue(client.submittedCommands.isEmpty())
    }

    /**
     * Class coverage — the footer-present FALSE-MATCH itself: when the pane NEVER
     * renders the typed input (a fully wedged busy Codex), the ack gate must NOT
     * confirm on the permanent footer occurrence. It fails within the bounded
     * acknowledgement window without Enter — proving the count-baseline gate is
     * not satisfied by the pre-existing `(/goal resume)`.
     */
    @Test
    fun ackGateDoesNotFalseConfirmOnPermanentFooterOccurrence() = runTest(scheduler) {
        // neverRenders = true: Codex is wedged and never echoes the paste, so the needle
        // count stays at the footer's baseline of 1 for the whole ack window.
        val client = BurstTuiFakeTmuxClient(footer = footer, busyReadDelayCaptures = 1, neverRenders = true)
        val vm = newBurstVm(client)

        val result = async { vm.sendAgentPayloadToPaneResult("%0", "/goal resume", AgentKind.Codex) }
        advanceUntilIdle()
        // Issue #1739 hard cut: an unproven paste is retryable failure, never
        // "timeout then blind Enter".
        assertTrue(result.await().isFailure)
        assertTrue(
            "the count-baseline ack must NOT confirm on the permanent footer occurrence — " +
                "it polls to the bounded failure (multiple polls), not a first-poll footer match " +
                "(pollCount=${client.capturePaneTextViaExecCalls.size})",
            client.capturePaneTextViaExecCalls.size > 2,
        )
        assertFalse(
            "a wedged Codex that never renders the paste must not report a swallowed-then-" +
                "spuriously-submitted state from the footer",
            client.submittedCommands.contains("/goal resume"),
        )
        assertFalse(
            "an unproven paste must never receive a blind submit Enter",
            client.sentCommands.any { it == "send-keys -t %0 Enter" },
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
    private val requirePostSubmitObservationBeforeNextEnter: Boolean = false,
    private val neverConsumesEnter: Boolean = false,
    private val animateUnrelatedFrame: Boolean = false,
    private val transientNoPromptBeforeEnterConsumption: Boolean = false,
    private val forceNoPromptFrames: Boolean = false,
    private val onEnter: (() -> Unit)? = null,
) : FakeTmuxClient() {

    private var pendingText: String? = null
    private var textRendered: Boolean = false
    private var capturesSincePaste: Int = 0
    private var submitted: Boolean = false
    private var previousSubmitStillTurningOver: Boolean = false
    private var renderedFrame: Int = 0
    private var pendingEnter: Boolean = false
    private var transientNoPromptEmitted: Boolean = false
    var pastedBeforePreviousEnterConsumed: Boolean = false
        private set
    val submittedCommands: MutableList<String> = mutableListOf()

    private val literalRegex = Regex("^send-keys -l -t \\S+ -- '(.*)'$")
    private val enterRegex = Regex("^send-keys -t \\S+ Enter$")

    fun inputBoxShowsPending(): Boolean = pendingText != null && !submitted

    private fun interpret(cmd: String) {
        literalRegex.find(cmd)?.let { m ->
            val pasted = m.groupValues[1].replace("'\\''", "'")
            if (pendingEnter) {
                pastedBeforePreviousEnterConsumed = true
                pendingText = pendingText.orEmpty() + pasted
            } else {
                pendingText = pasted
            }
            textRendered = false
            submitted = false
            capturesSincePaste = 0
            return
        }
        if (enterRegex.matches(cmd)) {
            onEnter?.invoke()
            if (neverConsumesEnter) return
            if (transientNoPromptBeforeEnterConsumption) {
                pendingEnter = true
                transientNoPromptEmitted = false
                return
            }
            val text = pendingText
            if (text != null && textRendered && !submitted && !previousSubmitStillTurningOver) {
                // The text was read+rendered in a PRIOR read ⇒ this CR is its own read
                // ⇒ Codex submits. If the text is still unread (textRendered == false),
                // the CR batches with it and the paste-burst heuristic swallows it.
                submitted = true
                submittedCommands.add(text)
                previousSubmitStillTurningOver = requirePostSubmitObservationBeforeNextEnter
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
        if (forceNoPromptFrames && submitted) return listOf("agent framed input redraw")
        val lines = mutableListOf(
            if (animateUnrelatedFrame) "$footer · spinner ${renderedFrame++}" else footer,
        )
        val text = pendingText
        if (submitted && text != null) {
            lines.add("■ submitted $text: thread/goal set")
            lines.add("›")
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
        if (pendingEnter && !transientNoPromptEmitted) {
            transientNoPromptEmitted = true
            return CommandResponse(
                number = 0L,
                output = listOf("redrawing agent input surface"),
                isError = false,
            )
        }
        if (pendingEnter) {
            pendingEnter = false
            submitted = true
            pendingText?.let(submittedCommands::add)
        }
        advanceCodexReadOnCapture()
        val response = CommandResponse(number = 0L, output = renderLines(), isError = false)
        // A clean observation after Enter models the TUI finishing its submitted-turn
        // redraw and reopening the input loop. A capture performed only after the next
        // paste is too late: that next turn is already sharing the busy boundary.
        if (submitted && previousSubmitStillTurningOver) {
            previousSubmitStillTurningOver = false
        }
        return response
    }
}
