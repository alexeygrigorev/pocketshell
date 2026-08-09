package com.pocketshell.app.tmux

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.composer.OutboundRoute
import com.pocketshell.app.composer.OutboundState
import com.pocketshell.app.composer.SharedPrefsOutboundQueueStore
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.tmux.CommandResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #2056 — "the composer queue is not cleared after the payload was
 * delivered": the terminal acknowledgement of a durable outbound row is
 * TRANSCRIPT-ONLY, so a row whose submit was physically consumed by the agent
 * but which has no live transcript authority can never reach a terminal state.
 *
 * Two production facts combine into the maintainer's report:
 *
 *  1. [AgentTranscriptAuthority.baseline] returns `null` whenever the pane has
 *     no LIVE transcript source (an agent that has just been relaunched, a
 *     node-wrapped Claude the detector cannot bind, a `TmuxEnterAccepted`
 *     TUI command). The durable submit write-ahead is then persisted with a
 *     NULL transcript baseline, and #2037's transcript-only late-ack bridge
 *     (now [resolveLateAuthoritativeSubmitAck]'s first arm) bails on that null.
 *  2. The generic screen-turnover oracle that is supposed to cover that case
 *     ([agentInputSurfaceState]) cannot SEE Claude Code's input prompt, because
 *     Claude renders it inside a box (`│ > `) and the matcher only accepted a
 *     line whose FIRST non-blank character is `>` / `›`. So the oracle reports
 *     [AgentInputSurfaceState.Unknown] on every Claude frame, the 800 ms
 *     turnover wait always times out, and every send lands in the ambiguous
 *     `wireSubmitAttempted` state.
 *
 * Result: the payload ran (the maintainer's screenshot shows Claude launched
 * from `csp`), the row stays `Queued` with an enabled Retry, and no authority
 * that exists can ever resolve it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class Issue2056RouteCompleteSubmitAckTest : TmuxSessionViewModelTestBase() {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun claudeFrame(input: String): CommandResponse = CommandResponse(
        number = 0L,
        output = listOf(
            "╭──────────────────────────────────────────────╮",
            "│ > $input",
            "╰──────────────────────────────────────────────╯",
            "  ? for shortcuts",
        ),
        isError = false,
    )

    /**
     * The WORKING shape of the same agent: the box is drawn but the input marker is
     * replaced by the activity indicator, so no line carries a prompt glyph. Mirrors
     * the `issue1944-framed-input` fake-agent surface used by the connected journeys.
     * `capture-pane -p` strips trailing blanks, hence the bare frame row when the
     * buffer is empty.
     */
    private fun framedWorkingFrame(input: String): CommandResponse = CommandResponse(
        number = 0L,
        output = listOf(
            "FAKE-AGENT-READY",
            "✻ Working… (esc to interrupt)",
            if (input.isEmpty()) "┃" else "┃ $input",
        ),
        isError = false,
    )

    /**
     * The oracle that is supposed to prove "the agent consumed my submit"
     * cannot read Claude Code's boxed input prompt at all.
     */
    @Test
    fun claudeBoxedInputPromptIsReadableBySubmitOracle() {
        val payload = "run the migration now"

        assertEquals(
            "a Claude frame whose boxed input still holds the payload must read as " +
                "PendingPayload (the submit was NOT consumed)",
            AgentInputSurfaceState.PendingPayload,
            agentInputSurfaceState(claudeFrame(payload), payload),
        )
        assertEquals(
            "a Claude frame whose boxed input is empty must read as Ready — the agent " +
                "consumed the submit. On base the box glyph hides the prompt and this is " +
                "Unknown, so the turnover proof can NEVER succeed for Claude Code (#2056)",
            AgentInputSurfaceState.Ready,
            agentInputSurfaceState(claudeFrame(""), payload),
        )
    }

    /** The same blindness on the shell prompt glyph the maintainer's box uses. */
    @Test
    fun shellPromptGlyphIsReadableBySubmitOracle() {
        val payload = "csp"
        val pending = CommandResponse(
            number = 0L,
            output = listOf("~/git/pocketshell (main)", "❯ csp"),
            isError = false,
        )
        val consumed = CommandResponse(
            number = 0L,
            output = listOf("~/git/pocketshell (main)", "❯ "),
            isError = false,
        )
        assertEquals(AgentInputSurfaceState.PendingPayload, agentInputSurfaceState(pending, payload))
        assertEquals(
            "an empty shell prompt after the command ran must read as Ready (#2056)",
            AgentInputSurfaceState.Ready,
            agentInputSurfaceState(consumed, payload),
        )
    }

    /**
     * The reported journey, end to end on the real delivery path: an agent-route
     * prompt whose pane has NO live transcript authority. The paste is ack-proven,
     * Enter is written and accepted by tmux, the bounded turnover wait expires
     * (ambiguous), and the row is left `Queued` + `wireSubmitAttempted`.
     *
     * The agent then visibly consumes the submit (its input surface empties).
     * That is authoritative evidence of delivery — and the ONLY authority the
     * product has for this pane, because no transcript exists. On base the late
     * ack is transcript-only, so it returns false forever and the row keeps its
     * Retry with the payload already delivered.
     */
    @Test
    fun agentRowWithoutTranscriptAuthorityIsAcknowledgedFromPaneEvidence() = runTest(scheduler) {
        val payload = "run the migration now"
        val store = SharedPrefsOutboundQueueStore(context)
        val row = store.enqueue(
            sessionKey = "sessA",
            cleanText = payload,
            paneId = "%0",
            route = OutboundRoute.AgentPayload,
            agentKind = "claude",
            sendKey = "sk-2056",
            tmuxSessionId = "\$7",
            tmuxSessionCreated = 1700L,
        )
        val durableRow = DurableOutboundRowIdentity("sessA", row.id)

        val client = FakeTmuxClient()
        // The pane keeps showing the payload in Claude's boxed input for the whole
        // bounded turnover window: the agent has not turned over yet.
        client.defaultCaptureResponse = claudeFrame(payload)
        val vm = newVm(applicationContext = context, outboundQueueStore = store)
        vm.attachClientForTest(client)
        vm.setAgentSubmitEnterDelayForTest(0)
        vm.setAgentSubmitAckTimeoutForTest(50)

        val sent = async {
            vm.sendAgentPayloadToPaneResult(
                "%0",
                payload,
                AgentKind.ClaudeCode,
                sendToken = row.id,
                durableRow = durableRow,
            )
        }
        advanceUntilIdle()
        val outcome = sent.await()
        assertTrue(
            "the bounded turnover proof must expire, leaving the row ambiguous",
            outcome.exceptionOrNull() is AgentSubmitTurnoverNotProvenException,
        )
        assertTrue("Enter reached tmux", client.sentCommands.contains("send-keys -t %0 Enter"))
        val ambiguous = requireNotNull(store.item(row.id))
        assertTrue("the row is durably marked submit-attempted", ambiguous.wireSubmitAttempted)
        assertNull(
            "precondition: no transcript authority exists for this pane, so #2037's " +
                "transcript-only bridge has nothing to work with",
            ambiguous.wireSubmitTranscriptBaseline,
        )
        assertEquals(OutboundState.Queued, ambiguous.state)

        // The agent HAS consumed the submit: its input surface is empty again.
        client.defaultCaptureResponse = claudeFrame("")

        assertTrue(
            "a delivered row whose agent visibly consumed the submit must reach a " +
                "terminal acknowledgement even with no transcript authority (#2056)",
            vm.resolveLateAuthoritativeOutboundAck(ambiguous),
        )
        assertTrue(
            "the terminal ack prunes the durable row so the composer queue clears",
            store.acknowledgeLateDelivered(
                ambiguous.id,
                ambiguous.sendKey,
                ambiguous.wireAttemptGeneration,
            ),
        )
        assertNull("the acknowledged row is gone from the durable queue", store.item(row.id))
        assertTrue(
            "a freshly rebuilt store (reconnect / composer reopen / process recreate) " +
                "must not resurrect the acknowledged row",
            SharedPrefsOutboundQueueStore(context).itemsFor("sessA").isEmpty(),
        )
    }

    /**
     * Fail-closed negative (G6): the SAME ambiguous row, but the agent is still
     * holding the payload in its input. No authority exists, so no acknowledgement
     * may be produced — a false ack here would silently lose the prompt.
     */
    @Test
    fun paneStillHoldingThePayloadIsNeverAcknowledged() = runTest(scheduler) {
        val payload = "deploy the staging build now"
        val store = SharedPrefsOutboundQueueStore(context)
        val row = store.enqueue(
            sessionKey = "sessB",
            cleanText = payload,
            paneId = "%0",
            route = OutboundRoute.AgentPayload,
            agentKind = "claude",
            sendKey = "sk-2056b",
            tmuxSessionId = "\$8",
            tmuxSessionCreated = 1800L,
        )
        val durableRow = DurableOutboundRowIdentity("sessB", row.id)
        val client = FakeTmuxClient()
        client.defaultCaptureResponse = claudeFrame(payload)
        val vm = newVm(applicationContext = context, outboundQueueStore = store)
        vm.attachClientForTest(client)
        vm.setAgentSubmitEnterDelayForTest(0)
        vm.setAgentSubmitAckTimeoutForTest(50)

        val sent = async {
            vm.sendAgentPayloadToPaneResult(
                "%0",
                payload,
                AgentKind.ClaudeCode,
                sendToken = row.id,
                durableRow = durableRow,
            )
        }
        advanceUntilIdle()
        assertTrue(sent.await().isFailure)
        val ambiguous = requireNotNull(store.item(row.id))

        assertFalse(
            "the payload is STILL in the agent's input — nothing was consumed, so no " +
                "acknowledgement may be produced",
            vm.resolveLateAuthoritativeOutboundAck(ambiguous),
        )
        assertNotNull("the row must survive as a real, retryable prompt", store.item(row.id))
    }

    /**
     * Second fail-closed arm, and the UNIT-GATE guard for the connected journeys'
     * induced-ambiguity fixture (#2056 round 2).
     *
     * A working Claude/Codex draws its box WITHOUT a marker glyph — there is no `>` to
     * anchor on either before or after Enter. The product then genuinely cannot decide
     * whether the submit was consumed, so the pane arm must stay silent and only an
     * authoritative transcript turn may resolve the row.
     *
     * `OutboundExactlyOnceAcrossFlapE2eTest`'s three late-authority journeys depend on
     * exactly this: they seed the framed fake-agent surface
     * (`tests/docker/agent-bin/pocketshell-fake-agent`, `issue1944-framed-input`) so the
     * ambiguity they assert on is reachable at all. If a future change made that surface
     * decidable, those journeys would stop exercising the late arm and merely time out
     * on an empty store — protection lost, not merely red. This asserts the property in
     * the fast per-push Unit gate, next to the code that owns it.
     */
    @Test
    fun workingAgentBoxWithNoMarkerGlyphFailsClosedOnBothFrames() = runTest(scheduler) {
        val payload = "restart the worker pool"
        assertEquals(
            "a framed working surface still echoing the payload is undecidable",
            AgentInputSurfaceState.Unknown,
            agentInputSurfaceState(framedWorkingFrame(payload), payload),
        )
        assertEquals(
            "an EMPTY framed working surface is undecidable too — the box edge is not a " +
                "prompt marker, so 'the payload is gone' cannot be read as 'consumed'",
            AgentInputSurfaceState.Unknown,
            agentInputSurfaceState(framedWorkingFrame(""), payload),
        )

        val store = SharedPrefsOutboundQueueStore(context)
        val row = store.enqueue(
            sessionKey = "sessFramed",
            cleanText = payload,
            paneId = "%0",
            route = OutboundRoute.AgentPayload,
            agentKind = "claude",
            sendKey = "sk-2056framed",
            tmuxSessionId = "\$9",
            tmuxSessionCreated = 1900L,
        )
        val durableRow = DurableOutboundRowIdentity("sessFramed", row.id)
        val client = FakeTmuxClient()
        client.defaultCaptureResponse = framedWorkingFrame(payload)
        val vm = newVm(applicationContext = context, outboundQueueStore = store)
        vm.attachClientForTest(client)
        vm.setAgentSubmitEnterDelayForTest(0)
        vm.setAgentSubmitAckTimeoutForTest(50)

        val sent = async {
            vm.sendAgentPayloadToPaneResult(
                "%0",
                payload,
                AgentKind.ClaudeCode,
                sendToken = row.id,
                durableRow = durableRow,
            )
        }
        advanceUntilIdle()
        assertTrue("the bounded turnover proof must expire", sent.await().isFailure)
        val ambiguous = requireNotNull(store.item(row.id))
        assertTrue(ambiguous.wireSubmitAttempted)

        // The agent consumed the submit, but its working box still says nothing the
        // oracle can read. Fail closed: only a transcript turn may resolve this row.
        client.defaultCaptureResponse = framedWorkingFrame("")
        assertFalse(
            "an undecidable pane must never acknowledge — the late TRANSCRIPT authority " +
                "is the only resolver for a working agent (#2056)",
            vm.resolveLateAuthoritativeOutboundAck(ambiguous),
        )
        assertNotNull("the row must survive for the late authority", store.item(row.id))
    }

    /**
     * The evidence for a no-transcript pane arrives a second or two AFTER the bounded
     * turnover wait gave up, and nothing in the app changes in between — so a
     * one-shot evaluation per snapshot never sees it. The reconciler must re-ask the
     * authority on a bounded foreground cadence, and must terminate when it never
     * speaks (no unbounded loop — the #1517 class).
     */
    @Test
    fun lateAuthorityIsPolledUntilItSpeaksAndTerminatesWhenItNeverDoes() = runTest(scheduler) {
        val binding = TmuxOutboundQueueBinding(
            targetKey = "sessA",
            fallbackKey = "1/demo",
            durableKey = "sessA",
            tmuxSessionId = "\$7",
            sessionCreated = 1700L,
            generationPaneIds = setOf("%0"),
        )
        val row = com.pocketshell.app.composer.OutboundItem(
            id = "row-late",
            sessionKey = "sessA",
            cleanText = "run the migration now",
            createdAtMs = 1L,
            paneId = "%0",
            sendKey = "sk-late",
            wireSubmitAttempted = true,
            wireAttemptGeneration = 1,
            tmuxSessionId = "\$7",
            tmuxSessionCreated = 1700L,
        )

        var asked = 0
        val acknowledged = mutableListOf<String>()
        val resolvedLate = reconcileLateOutboundAcks(
            rows = listOf(row),
            binding = binding,
            resolveAuthoritativeAck = { asked += 1; asked >= 3 },
            acknowledge = { batch -> acknowledged += batch.map { it.id } },
        )
        assertEquals(listOf("row-late"), resolvedLate.map { it.id })
        assertEquals(listOf("row-late"), acknowledged)
        assertTrue("the authority must be re-asked, not evaluated once", asked >= 3)

        var silentAsks = 0
        val never = reconcileLateOutboundAcks(
            rows = listOf(row),
            binding = binding,
            resolveAuthoritativeAck = { silentAsks += 1; false },
        )
        assertTrue("a silent authority resolves nothing", never.isEmpty())
        assertEquals(
            "the poll is bounded — it must terminate instead of spinning (#1517 class)",
            OUTBOUND_LATE_ACK_MAX_POLLS,
            silentAsks,
        )
    }

    /**
     * Class coverage (G2) for the RAW-BYTES shell lane, which can never produce an
     * agent transcript turn: a shell row whose delivery IS confirmable on the pane
     * must reach a terminal acknowledged state without any transcript, and without
     * re-running the command.
     */
    @Test
    fun rawBytesShellRowReachesTerminalAckOnConfirmedDeliveryWithNoTranscript() = runTest(scheduler) {
        val payload = "tail -n 40 /var/log/syslog"
        val store = SharedPrefsOutboundQueueStore(context)
        val row = store.enqueue(
            sessionKey = "sessC",
            cleanText = payload,
            paneId = "%0",
            route = OutboundRoute.RawBytes,
            sendKey = "sk-2056c",
        )
        val durableRow = DurableOutboundRowIdentity("sessC", row.id)
        val ledger = outboundDeliveryLedgerFor(store)
        val client = FakeTmuxClient()
        client.defaultCaptureResponse = CommandResponse(0L, listOf("❯ "), isError = false)

        var writes = 0
        var enters = 0
        val bytes = "$payload\r".toByteArray(Charsets.UTF_8)

        // Attempt 1: the bytes LAND server-side, the exec result is lost.
        val first = deliverRawInputWithGuard(
            ledger = ledger,
            client = client,
            paneId = "%0",
            bytes = bytes,
            localRenderText = "",
            sendToken = row.id,
            durableRow = durableRow,
            send = { _, _, _ ->
                writes += 1
                client.defaultCaptureResponse =
                    CommandResponse(0L, listOf("❯ $payload", "Jan  1 syslog line", "❯ "), false)
                throw IllegalStateException("exec result lost after the bytes landed")
            },
            submitEnter = { _, _ -> enters += 1 },
            afterDelivered = { _, _, _ -> },
        )
        assertTrue("attempt 1 surfaces the ambiguous outcome", first.isFailure)
        assertEquals(1, writes)

        // The auto-flush retry must verify (not blindly re-run the shell command) and
        // terminally acknowledge the delivery.
        val second = deliverRawInputWithGuard(
            ledger = ledger,
            client = client,
            paneId = "%0",
            bytes = bytes,
            localRenderText = "",
            sendToken = row.id,
            durableRow = durableRow,
            send = { _, _, _ -> writes += 1 },
            submitEnter = { _, _ -> enters += 1 },
            afterDelivered = { _, _, _ -> },
        )
        assertTrue(
            "a RawBytes shell row whose delivery is confirmable on the pane must reach a " +
                "terminal acknowledged state with no transcript turn (#2056 arm 1)",
            second.isSuccess,
        )
        assertEquals("the shell command must not run twice", 1, writes)
        assertTrue("the terminal ack completes the submit", enters >= 1)
        assertTrue(store.markDelivered(row.id))
        assertNull(store.item(row.id))
    }
}
