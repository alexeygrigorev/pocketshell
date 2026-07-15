package com.pocketshell.app.tmux

import com.pocketshell.app.composer.asWireAttemptDurableStore
import com.pocketshell.core.tmux.CommandResponse
import com.pocketshell.core.tmux.TmuxClientException
import com.pocketshell.core.tmux.TmuxDisconnectEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #1526 — Slice S1, the KEYSTROKE lane (per-pane input queue) at the
 * delivery level.
 *
 * Audit failure mode B2: `sendDequeuedInputBatch` retried a failed batch
 * BLINDLY once — but attempt 1's failure is ambiguous (a `send-keys` exec whose
 * result was lost still ran server-side), so the blind attempt 2 doubled the
 * user's keystrokes. Same for the `client_superseded` requeue: the OLD client
 * may already have delivered the batch the NEW client then replays.
 *
 * [deliverDequeuedInputBatch] (the extracted production delivery loop the VM
 * delegates to) must PROBE before any re-send: bytes already visible in the
 * pane ⇒ record sent, occurrence stays 1 (RED on base: occurrence 2); not
 * landed ⇒ retry exactly as before; needle underivable (short/control-only
 * batch) ⇒ preserve base behaviour (no new silent drops in S1).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class OutboundDeliveryGuardTest {

    @After
    fun tearDown() {
        OutboundDeliverySeams.failSendResultLostBeforeSubmitEnter = false
        OutboundDeliverySeams.failInputSendResultLostOnce = false
    }

    private fun newQueue(): TmuxPaneInputQueue = TmuxPaneInputQueue(
        maxPendingBytes = TMUX_INPUT_MAX_PENDING_BYTES,
        maxBatchBytes = TMUX_INPUT_MAX_BATCH_BYTES,
    )

    private suspend fun batchOf(queue: TmuxPaneInputQueue, text: String): TmuxPaneInputBatch {
        val bytes = text.toByteArray(Charsets.UTF_8)
        queue.write(bytes, 0, bytes.size)
        return requireNotNull(queue.takeBatch()) { "expected a batch for '$text'" }
    }

    /** A send that LANDS server-side, then loses its result [failures] times. */
    private class AmbiguousServer(private var failures: Int) {
        val received = mutableListOf<String>()

        suspend fun send(bytes: ByteArray) {
            received += String(bytes, Charsets.UTF_8)
            if (failures > 0) {
                failures -= 1
                throw TmuxClientException("tmux send-keys (exec interactive send lane) timed out after 10000ms")
            }
        }
    }

    private fun captureShowing(vararg lines: String): FakeTmuxClient =
        FakeTmuxClient().apply {
            defaultCaptureResponse = CommandResponse(
                number = 0L,
                output = lines.toList(),
                isError = false,
            )
        }

    /**
     * THE lane-B exactly-once case: attempt 1 lands but its result is lost; the
     * probe sees the batch text in the pane and must SUPPRESS attempt 2 —
     * server occurrence stays 1. RED on base (blind retry ⇒ occurrence 2).
     */
    @Test
    fun ambiguousFailureWithLandedBytesDoesNotRetry() = runTest {
        val text = "exactly-once keystroke run"
        val client = captureShowing("$ $text")
        val server = AmbiguousServer(failures = 1)
        val queue = newQueue()
        val batch = batchOf(queue, text)

        val resolved = deliverDequeuedInputBatch(
            client = client,
            paneId = "%0",
            batch = batch,
            queue = queue,
            currentClient = { client },
            sendBytes = { _, _, b -> server.send(b) },
            onPersistentFailureOfCurrentClient = { },
        )

        assertTrue("the batch must resolve (already landed)", resolved)
        assertEquals(
            "the keystrokes must reach the server exactly ONCE across the " +
                "ambiguous failure (probe must suppress the blind attempt 2)",
            1,
            server.received.size,
        )
        assertEquals("the queue must account the batch as sent", 1L, queue.snapshot().sentBatchCount)
    }

    /** No over-suppression: probe says NOT landed ⇒ attempt 2 goes out. */
    @Test
    fun ambiguousFailureWithMissingBytesRetriesOnce() = runTest {
        val text = "retry me when absent please"
        val client = captureShowing("$ ")
        val server = AmbiguousServer(failures = 1)
        val queue = newQueue()
        val batch = batchOf(queue, text)

        val resolved = deliverDequeuedInputBatch(
            client = client,
            paneId = "%0",
            batch = batch,
            queue = queue,
            currentClient = { client },
            sendBytes = { _, _, b -> server.send(b) },
            onPersistentFailureOfCurrentClient = { },
        )

        assertTrue(resolved)
        assertEquals("a NOT-landed batch must still be retried", 2, server.received.size)
    }

    /**
     * A short/control-only batch has no probe-able needle: S1 preserves the
     * base retry behaviour (never introduces new silent drops — bounded-replay
     * tightening is slice S3).
     */
    @Test
    fun shortBatchWithoutNeedleKeepsBaseRetryBehaviour() = runTest {
        val text = "y"
        val client = captureShowing("$ y")
        val server = AmbiguousServer(failures = 1)
        val queue = newQueue()
        val batch = batchOf(queue, text)

        val resolved = deliverDequeuedInputBatch(
            client = client,
            paneId = "%0",
            batch = batch,
            queue = queue,
            currentClient = { client },
            sendBytes = { _, _, b -> server.send(b) },
            onPersistentFailureOfCurrentClient = { },
        )

        assertTrue(resolved)
        assertEquals("needle-less batches keep the pre-S1 retry", 2, server.received.size)
    }

    /**
     * The superseded half of B2: attempt 1 on the OLD client lands ambiguously;
     * before requeueing for the NEW client, the loop must probe the NEW client
     * and, seeing the bytes landed, resolve WITHOUT replaying them.
     */
    @Test
    fun supersededClientProbesBeforeRequeueAfterAmbiguousAttempt() = runTest {
        val text = "superseded replay guard text"
        val oldClient = FakeTmuxClient()
        val newClient = captureShowing("$ $text")
        val server = AmbiguousServer(failures = 1)
        val queue = newQueue()
        val batch = batchOf(queue, text)

        // A reconnect supersedes the client between attempt 1 and attempt 2 —
        // the exact window where the old code requeued a batch the OLD client
        // may already have delivered.
        var current: com.pocketshell.core.tmux.TmuxClient = oldClient
        val resolved = deliverDequeuedInputBatch(
            client = oldClient,
            paneId = "%0",
            batch = batch,
            queue = queue,
            currentClient = { current },
            sendBytes = { _, _, b ->
                current = newClient
                server.send(b)
            },
            onPersistentFailureOfCurrentClient = { },
        )

        assertTrue("the batch must resolve as already-landed, not requeue", resolved)
        assertEquals(1, server.received.size)
        assertEquals("no replay may be queued for the new client", 1L, queue.snapshot().sentBatchCount)
    }

    /** A clean success never probes and never duplicates. */
    @Test
    fun successfulFirstAttemptSendsOnceWithoutProbing() = runTest {
        val text = "clean first attempt payload"
        val client = FakeTmuxClient()
        val server = AmbiguousServer(failures = 0)
        val queue = newQueue()
        val batch = batchOf(queue, text)

        val resolved = deliverDequeuedInputBatch(
            client = client,
            paneId = "%0",
            batch = batch,
            queue = queue,
            currentClient = { client },
            sendBytes = { _, _, b -> server.send(b) },
            onPersistentFailureOfCurrentClient = { },
        )

        assertTrue(resolved)
        assertEquals(1, server.received.size)
        assertEquals(
            "the happy path must not pay a capture-pane probe round-trip",
            0,
            client.capturePaneTextViaExecCalls.size,
        )
    }

    /** Persistent (twice-failed, probe-NotLanded) batches still drop + report. */
    @Test
    fun persistentFailureStillDropsAndReportsDisconnectEvent() = runTest {
        val text = "persistently failing batch text"
        val client = captureShowing("$ ")
        val server = AmbiguousServer(failures = 2)
        val queue = newQueue()
        val batch = batchOf(queue, text)
        var reported: TmuxDisconnectEvent? = null

        val resolved = deliverDequeuedInputBatch(
            client = client,
            paneId = "%0",
            batch = batch,
            queue = queue,
            currentClient = { client },
            sendBytes = { _, _, b -> server.send(b) },
            onPersistentFailureOfCurrentClient = { reported = it },
        )

        assertTrue(resolved)
        assertEquals(2, server.received.size)
        assertEquals("pane_input_send", reported?.source)
    }

    // ---------------------------------------------------------------- needle + ledger

    @Test
    fun inputBatchProbeNeedleStripsControlAndEscapeSequences() {
        val needle = inputBatchProbeNeedle(
            "\u001b[200~fix the failing gate\u001b[201~\r".toByteArray(Charsets.UTF_8),
        )
        assertEquals("fixthefailinggate", needle)
    }

    @Test
    fun inputBatchProbeNeedleIsNullForShortOrControlOnlyBatches() {
        assertNull(inputBatchProbeNeedle("y".toByteArray(Charsets.UTF_8)))
        assertNull(inputBatchProbeNeedle("\u001b[A\u001b[B".toByteArray(Charsets.UTF_8)))
        assertNull(inputBatchProbeNeedle(byteArrayOf(0x03, 0x04)))
    }

    @Test
    fun inputBatchProbeNeedleUsesWhitespaceStrippedTail() {
        val longText = "please run the full integration suite on the staging box"
        val needle = requireNotNull(inputBatchProbeNeedle(longText.toByteArray(Charsets.UTF_8)))
        assertEquals(AGENT_SUBMIT_ACK_NEEDLE_TAIL_CHARS, needle.length)
        assertTrue(longText.filterNot { it.isWhitespace() }.endsWith(needle))
    }

    /**
     * Issue #1532 (RC-B no-duplicate guard): the RC-B silent-heal re-dispatch
     * (TmuxSessionScreenStateHelpers per-row backoff) funnels back into the SAME
     * composer-lane send that runs [verifyBeforeAgentResend] first. When the row's
     * earlier attempt ended ambiguously (recorded on the ledger) but actually
     * LANDED server-side, a re-dispatch MUST resolve `AlreadyLanded` so the send
     * path submits Enter only and never re-pastes — the payload reaches the agent
     * exactly ONCE. RED if the ledger were bypassed (a blind re-paste ⇒ occurrence
     * 2, the maintainer's "delivered twice" class the S1 slice fixed).
     */
    @Test
    fun reDispatchOfAlreadyLandedPayloadResolvesAlreadyLandedForExactlyOnce() = runTest {
        val ledger = OutboundDeliveryLedger()
        val paneId = "%0"
        val payload = "deliver this exactly once please"
        // First attempt reached the wire ambiguously (recorded WITH its pre-send
        // baseline 0 — the payload was NOT on the pane before the paste) AND landed:
        // the pane now shows the payload (count 1 > baseline 0). Issue #1577b: a
        // production wire push ALWAYS records a baseline (recordWireAttemptWithBaseline),
        // so a genuinely-landed row is baselined — this is the production-accurate state.
        ledger.recordWireAttempt(paneId, payload, payload, baselineCount = 0)
        val client = captureShowing("$ $payload")

        // Issue #1529: the resend re-uses the SAME send token as the recorded attempt.
        val outcome = verifyBeforeAgentResend(ledger, client, paneId, payload, payload)

        assertEquals(
            "a re-dispatched already-landed payload must resolve AlreadyLanded so the send path " +
                "submits Enter only and never re-pastes (occurrence stays 1)",
            DeliveryProbeOutcome.AlreadyLanded,
            outcome,
        )
    }

    /**
     * Issue #1577b: a NULL baseline (a legacy pre-baseline row, or a failed baseline
     * capture) is UNTRUSTWORTHY — a presence-only check would false-`AlreadyLanded` on
     * a permanent Codex `Goal blocked (/goal resume)` footer and submit a bare Enter,
     * silently dropping the payload (the reopened #1577 symptom). The kill-the-presence-
     * fallback fix resolves `NotLanded` instead, so the caller does a REAL gated resend
     * (which records a fresh baseline), never a bare Enter.
     *
     * RED on base: the presence-only fallback (`count >= 1`) matched the footer ⇒
     * AlreadyLanded. GREEN: null baseline ⇒ NotLanded (deliver-safe).
     */
    @Test
    fun nullBaselineUntrustworthyFlagResolvesNotLandedNotBareEnter() = runTest {
        val ledger = OutboundDeliveryLedger()
        val paneId = "%0"
        val payload = "/goal resume"
        // A legacy row carries the durable wire-attempt flag but NO baseline (null).
        ledger.recordWireAttempt(paneId, payload, payload) // 3-arg ⇒ baselineCount defaults to null
        // The pane permanently shows the payload in a status footer.
        val client = captureShowing("gpt-5.6-sol · Goal blocked (/goal resume)")

        val outcome = verifyBeforeAgentResend(ledger, client, paneId, payload, payload)

        assertEquals(
            "a null-baseline (untrustworthy) flag whose payload is already on the pane must NOT " +
                "resolve AlreadyLanded (that submits a bare Enter and silently drops the send) — " +
                "it must be NotLanded so the caller does a real gated resend",
            DeliveryProbeOutcome.NotLanded,
            outcome,
        )
    }

    /**
     * Issue #1532 (RC-B ordering/fresh-send guard): a FRESH send — no prior
     * ambiguous attempt on the ledger — must NOT probe (null ⇒ the caller does the
     * normal full send). The RC-B backoff never turns a fresh send into a
     * verify-gated one, so a fresh send is not slowed or reordered by the guard.
     */
    @Test
    fun freshSendWithNoPriorAttemptDoesNotProbe() = runTest {
        val ledger = OutboundDeliveryLedger()
        val client = FakeTmuxClient()

        val outcome = verifyBeforeAgentResend(ledger, client, "%0", "tok-fresh", "a brand new prompt")

        assertNull("a fresh send with no ambiguous prior attempt must not probe (null ⇒ normal send)", outcome)
        assertTrue(
            "a fresh send must not pay a capture-pane probe round-trip",
            client.capturePaneTextViaExecCalls.isEmpty(),
        )
    }

    /**
     * Issue #1541 (finding P9) — the durable-flag mechanism at the ledger level.
     * The volatile ledger dies on a plain VM-clear (back-navigation); a ledger
     * REBUILT after that clear must re-read the durable per-row `wireAttempted`
     * flag and still run verify-before-resend. RED on base: a volatile-only
     * rebuilt ledger has no memory ⇒ the reopened session blindly re-pastes
     * (server occurrence 2). GREEN: the durable-backed rebuild sees the attempt.
     */
    @Test
    fun rebuiltLedgerConsultsDurableFlagAfterVmClear() {
        val store = com.pocketshell.app.composer.InMemoryOutboundQueueStore()
        // The composer enqueued the row that the VM is delivering.
        store.enqueue("sessA", "durable payload", paneId = "%0")
        val durable = store.asWireAttemptDurableStore()

        // VM #1's ledger records the wire attempt (right before the paste).
        val ledger1 = OutboundDeliveryLedger(durable = durable)
        ledger1.recordWireAttempt("%0", "durable payload", "durable payload")
        assertTrue(ledger1.hasAmbiguousAttempt("%0", "durable payload", "durable payload"))

        // Back-navigation clears the VM: its volatile ledger dies. A base ledger
        // with NO durable backing has no memory of the attempt (the P9 bug).
        val baseRebuilt = OutboundDeliveryLedger()
        assertFalse(
            "RED (base): a volatile-only rebuilt ledger loses the wire attempt, so " +
                "the reopened session blindly re-pastes (server occurrence 2)",
            baseRebuilt.hasAmbiguousAttempt("%0", "durable payload", "durable payload"),
        )

        // The fix: a ledger rebuilt with the SAME durable store re-reads the flag
        // and runs verify-before-resend (server occurrence 1).
        val rebuilt = OutboundDeliveryLedger(durable = durable)
        assertTrue(
            "GREEN (fix): the durable `wireAttempted` flag makes the rebuilt ledger " +
                "verify-before-resend instead of blindly re-pasting",
            rebuilt.hasAmbiguousAttempt("%0", "durable payload", "durable payload"),
        )
    }

    /**
     * A FRESH send (no durable row / no prior attempt) never verifies, even with a
     * durable backing — the hot send path stays probe-free.
     */
    @Test
    fun freshSendWithDurableBackingButNoPriorAttemptDoesNotVerify() {
        val store = com.pocketshell.app.composer.InMemoryOutboundQueueStore()
        val ledger = OutboundDeliveryLedger(durable = store.asWireAttemptDurableStore())
        assertFalse(ledger.hasAmbiguousAttempt("%0", "tok-fresh", "a brand new prompt"))
    }

    /**
     * [OutboundDeliveryLedger.clear] clears only the VOLATILE set — the durable row
     * flag persists (dropped only when the row leaves the queue), so an as-yet-
     * un-acked in-flight row still verifies on a later rebuild. This is what closes
     * the `markDelivered`-lost corner: a cleared-but-not-yet-pruned row keeps
     * verifying.
     */
    @Test
    fun clearKeepsDurableRowFlagForAsYetUnackedRow() {
        val store = com.pocketshell.app.composer.InMemoryOutboundQueueStore()
        store.enqueue("sessA", "unacked payload", paneId = "%0")
        val durable = store.asWireAttemptDurableStore()
        val ledger = OutboundDeliveryLedger(durable = durable)
        ledger.recordWireAttempt("%0", "unacked payload", "unacked payload")
        ledger.clear("%0", "unacked payload")

        assertTrue(
            "clear() drops only the volatile entry; the durable row flag persists so " +
                "a rebuilt ledger still verifies",
            ledger.hasAmbiguousAttempt("%0", "unacked payload", "unacked payload"),
        )
        assertTrue(store.hasWireAttempt("%0", "unacked payload"))
    }

    /**
     * Issue #1542 (finding D7) — class-coverage limb: an orphaned InFlight row that
     * ACTUALLY landed. When a send reaches the wire it durably stamps `wireAttempted`
     * on the row (issue #1577: at the WIRE PUSH, not the claim); when that VM churns /
     * dies mid-send, the poll-lane orphan sweep re-arms the row and the retry lane
     * re-dispatches it through the SAME verify-before-resend send path with a ledger
     * REBUILT from the durable store. Because the earlier attempt landed server-side,
     * the probe must resolve `AlreadyLanded` — so the re-dispatch submits Enter only
     * and never re-pastes (occurrence stays 1, no duplicate). RED if the wire push did
     * not stamp the durable flag (rebuilt ledger has no memory ⇒ blind re-paste ⇒
     * occurrence 2). This composes with #1541 rather than adding a second flag.
     */
    @Test
    fun issue1542_D7_orphanedInFlightThatLandedVerifiesAlreadyLandedViaWirePushDurableFlag() = runTest {
        val store = com.pocketshell.app.composer.InMemoryOutboundQueueStore()
        val paneId = "%7"
        val payload = "orphaned prompt that already landed"
        // The prior VM enqueued and CLAIMED the row InFlight (its send began), then
        // PUSHED it to the wire — the push durably stamps `wireAttempted` (#1577: the
        // claim itself no longer stamps).
        val row = store.enqueue("sessA", payload, paneId = paneId)
        store.claim(row.id)
        assertEquals(
            com.pocketshell.app.composer.OutboundState.InFlight,
            store.item(row.id)!!.state,
        )
        assertFalse(
            "the claim alone must NOT record a wire attempt (#1577)",
            store.hasWireAttempt(paneId, payload),
        )
        store.markWireAttempted(paneId, payload, baselineCount = 0) // the actual wire push (#1577b: records a baseline)
        assertTrue(
            "the wire push must durably record the wire attempt on the row",
            store.hasWireAttempt(paneId, payload),
        )

        // VM churn / process death: the volatile ledger dies. A ledger REBUILT with
        // NO durable backing has no memory of the attempt (the pre-fix blind-repaste
        // trap) — verify-before-resend would be skipped.
        assertNull(
            "a volatile-only rebuilt ledger loses the orphaned attempt (would blind re-paste)",
            verifyBeforeAgentResend(OutboundDeliveryLedger(), captureShowing("$ $payload"), paneId, payload, payload),
        )

        // The fix path: the ledger rebuilt from the SAME durable store re-reads the
        // claim-stamped flag → verify → the pane shows the payload → AlreadyLanded,
        // so the send path submits Enter only and never re-pastes (occurrence 1).
        val rebuilt = OutboundDeliveryLedger(durable = store.asWireAttemptDurableStore())
        val outcome = verifyBeforeAgentResend(rebuilt, captureShowing("$ $payload"), paneId, payload, payload)
        assertEquals(
            "an orphaned InFlight row that actually landed must verify AlreadyLanded (occurrence 1)",
            DeliveryProbeOutcome.AlreadyLanded,
            outcome,
        )
    }

    /**
     * Issue #1577: when the payload is NOT already on the pane's LOCAL render at push
     * time (the common case — a fresh prompt), the wire attempt records baseline 0
     * with NO capture round-trip; a later probe finding the payload (count 1 > 0)
     * resolves `AlreadyLanded` (presence is unambiguous — our paste added it).
     */
    @Test
    fun baselineFromLocalRenderSkipsCaptureWhenPayloadNotAlreadyVisible() = runTest {
        val ledger = OutboundDeliveryLedger()
        val client = captureShowing("$ /goal resume") // the resend probe sees it landed

        ledger.recordWireAttemptWithBaseline(client, "%0", "/goal resume", "/goal resume", localRenderText = "$ ")

        assertTrue(
            "no baseline capture round-trip when the payload is not already on the render",
            client.capturePaneTextViaExecCalls.isEmpty(),
        )
        assertEquals(0, ledger.needleBaseline("%0", "/goal resume", "/goal resume"))
        assertEquals(
            "a resend that finds the payload (count 1 > baseline 0) is AlreadyLanded",
            DeliveryProbeOutcome.AlreadyLanded,
            verifyBeforeAgentResend(ledger, client, "%0", "/goal resume", "/goal resume"),
        )
    }

    /**
     * Issue #1577 — THE reported class at the guard level: the payload text is ALREADY
     * on the pane (a Codex `Goal blocked (/goal resume)` status line). The wire attempt
     * takes ONE authoritative capture to record the precise pre-send baseline, so a
     * genuine resend requires the occurrence count to INCREASE:
     *  - paste did NOT land (still only the status line, count == baseline) ⇒ NotLanded
     *    ⇒ the caller re-sends (NO false-`AlreadyLanded` silent drop — the #1577 bug), and
     *  - paste DID land (status line + submitted command, count > baseline) ⇒ AlreadyLanded
     *    ⇒ deduped to exactly-once (the #1526 guarantee is preserved).
     */
    @Test
    fun baselineCapturesPreSendCountWhenPayloadAlreadyOnRenderSoResendRequiresIncrease() = runTest {
        val ledger = OutboundDeliveryLedger()
        val client = FakeTmuxClient()
        val statusLine = "gpt-5.6-sol medium · Goal blocked (/goal resume)"

        // The payload is already visible on the local render ⇒ ONE authoritative
        // baseline capture: the status line shows the needle once ⇒ baseline 1.
        client.capturePaneResponses.addLast(CommandResponse(number = 0L, output = listOf(statusLine), isError = false))
        ledger.recordWireAttemptWithBaseline(client, "%0", "/goal resume", "/goal resume", localRenderText = statusLine)
        assertEquals("the pre-send baseline is captured authoritatively", 1, ledger.needleBaseline("%0", "/goal resume", "/goal resume"))
        assertEquals("exactly one baseline capture round-trip", 1, client.capturePaneTextViaExecCalls.size)

        // A genuine resend whose paste did NOT land: the pane still shows ONLY the
        // status line (count 1 == baseline 1) ⇒ NotLanded ⇒ re-send (no false drop).
        client.capturePaneResponses.addLast(CommandResponse(number = 0L, output = listOf(statusLine), isError = false))
        assertEquals(
            "a payload still only on the status line (count == baseline) must NOT be " +
                "falsely AlreadyLanded (the #1577 false-success drop)",
            DeliveryProbeOutcome.NotLanded,
            verifyBeforeAgentResend(ledger, client, "%0", "/goal resume", "/goal resume"),
        )

        // A resend whose paste DID land: the pane now shows the status line AND the
        // submitted command (count 2 > baseline 1) ⇒ AlreadyLanded ⇒ deduped.
        client.capturePaneResponses.addLast(
            CommandResponse(number = 0L, output = listOf(statusLine, "> /goal resume"), isError = false),
        )
        assertEquals(
            "a payload whose occurrence count INCREASED (our paste landed) is AlreadyLanded",
            DeliveryProbeOutcome.AlreadyLanded,
            verifyBeforeAgentResend(ledger, client, "%0", "/goal resume", "/goal resume"),
        )
    }

    // ---------------------------------------------------------------- issue #1587 H2 (viewport-bounded probe)

    /**
     * Issue #1587 (H2) — THE reported defect. A payload that LANDED but scrolled OFF
     * the visible viewport under a burst of agent output must still be found by the
     * verify-before-resend probe, so an ambiguous retry submits Enter only and does
     * NOT duplicate-paste.
     *
     * RED on base (visible-only `capture-pane -p`, no `-S`): the scrolled-off payload
     * is absent from the visible capture ⇒ NotLanded ⇒ the retry re-pastes (server
     * occurrence 2). GREEN (bounded `-S -N` scrollback capture): the payload is found
     * in scrollback (count 1 > baseline 0) ⇒ AlreadyLanded ⇒ no duplicate.
     */
    @Test
    fun landedThenScrolledOffPayloadIsFoundViaBoundedScrollbackProbeSoNoDuplicate() = runTest {
        val ledger = OutboundDeliveryLedger()
        val paneId = "%0"
        val payload = "run the full integration suite now"
        // A prior ambiguous wire attempt recorded with baseline 0 (fresh payload — it
        // was NOT on the pane at send time; production always records a baseline).
        ledger.recordWireAttempt(paneId, payload, payload, baselineCount = 0)

        val client = FakeTmuxClient().apply {
            // The paste LANDED, then a burst of agent output pushed it OFF the visible
            // viewport: a visible-only capture no longer shows it...
            defaultCaptureResponse =
                CommandResponse(number = 0L, output = listOf("...busy agent burst...", "$ "), isError = false)
            // ...but it is STILL within the bounded scrollback the probe now captures.
            scrollbackCaptureResponse = CommandResponse(
                number = 0L,
                output = listOf("> $payload", "...busy agent burst...", "$ "),
                isError = false,
            )
        }

        val outcome = verifyBeforeAgentResend(ledger, client, paneId, payload, payload)

        assertTrue(
            "the verify-before-resend probe must request BOUNDED SCROLLBACK (the H2 fix)",
            client.capturePaneTextViaExecScrollbackLines.any { it > 0 },
        )
        assertEquals(
            "a landed-then-scrolled-off payload must be found via bounded scrollback (AlreadyLanded) " +
                "so the retry submits Enter only — RED on base (visible-only probe ⇒ NotLanded ⇒ duplicate)",
            DeliveryProbeOutcome.AlreadyLanded,
            outcome,
        )
    }

    /**
     * Issue #1587 (H2) class-coverage — the payload still VISIBLE (never scrolled off)
     * remains found: the bounded-scrollback capture is a SUPERSET of the visible
     * viewport, so the common already-landed case is unchanged (AlreadyLanded).
     */
    @Test
    fun stillVisiblePayloadIsFoundByTheScrollbackProbeUnchanged() = runTest {
        val ledger = OutboundDeliveryLedger()
        val paneId = "%0"
        val payload = "deploy the staging build to the box"
        ledger.recordWireAttempt(paneId, payload, payload, baselineCount = 0)
        val client = FakeTmuxClient().apply {
            // The scrollback capture (what the probe now issues) includes the visible
            // payload — it is at the bottom of the buffer, still on screen.
            scrollbackCaptureResponse =
                CommandResponse(number = 0L, output = listOf("$ ", "> $payload"), isError = false)
        }

        assertEquals(
            DeliveryProbeOutcome.AlreadyLanded,
            verifyBeforeAgentResend(ledger, client, paneId, payload, payload),
        )
    }

    /**
     * Issue #1587 (H2) class-coverage — NO over-suppression. A payload GENUINELY absent
     * from the pane (not visible AND not in the bounded scrollback) must stay NotLanded
     * so the caller does a real gated resend — the scrollback probe must not invent a
     * false `AlreadyLanded` and silently drop the send.
     */
    @Test
    fun genuinelyAbsentPayloadStaysNotLandedNoOverSuppression() = runTest {
        val ledger = OutboundDeliveryLedger()
        val paneId = "%0"
        val payload = "please run the deployment checklist"
        ledger.recordWireAttempt(paneId, payload, payload, baselineCount = 0)
        val client = FakeTmuxClient().apply {
            // Neither the visible viewport nor the bounded scrollback contains the payload.
            scrollbackCaptureResponse =
                CommandResponse(number = 0L, output = listOf("$ ", "unrelated output line"), isError = false)
        }

        assertEquals(
            "a payload absent from the bounded scrollback must stay NotLanded (real resend), " +
                "never a false AlreadyLanded drop",
            DeliveryProbeOutcome.NotLanded,
            verifyBeforeAgentResend(ledger, client, paneId, payload, payload),
        )
    }

    /**
     * Issue #1587 (H2) consistency — the baseline snapshot and the resend probe scope
     * over the SAME bounded scrollback, so a needle that PRE-EXISTS in scrollback (a
     * prior scrolled-off occurrence) is counted in BOTH: a resend requires the count to
     * INCREASE, so a pre-existing occurrence is not over-suppressed as `AlreadyLanded`.
     *
     * If the baseline scoped over the visible viewport while the probe scoped over
     * scrollback, the pre-existing occurrence would inflate the probe count above a
     * (visible-derived) baseline of 0 and FALSELY resolve AlreadyLanded → silent drop.
     */
    @Test
    fun baselineAndProbeScopeOverSameScrollbackSoPreExistingOccurrenceIsNotOverSuppressed() = runTest {
        val ledger = OutboundDeliveryLedger()
        val paneId = "%0"
        val payload = "/goal resume"
        val client = FakeTmuxClient()
        // The needle already exists ONCE in the bounded scrollback (a prior scrolled-off
        // line). `localRenderText` carries the needle so the baseline is captured
        // AUTHORITATIVELY over the SAME bounded scrollback (not fast-pathed to 0).
        client.scrollbackCaptureResponse =
            CommandResponse(number = 0L, output = listOf("> $payload", "...later output...", "$ "), isError = false)
        ledger.recordWireAttemptWithBaseline(client, paneId, payload, payload, localRenderText = "> $payload")
        assertEquals(
            "the baseline is captured over the SAME bounded scrollback the probe uses (count 1)",
            1,
            ledger.needleBaseline(paneId, payload, payload),
        )

        // A resend whose paste did NOT land: scrollback still shows the ONE pre-existing
        // occurrence (count 1 == baseline 1) ⇒ NotLanded (no false AlreadyLanded).
        assertEquals(
            DeliveryProbeOutcome.NotLanded,
            verifyBeforeAgentResend(ledger, client, paneId, payload, payload),
        )

        // A resend whose paste DID land: scrollback now shows TWO (count 2 > baseline 1)
        // ⇒ AlreadyLanded ⇒ deduped to exactly-once.
        client.scrollbackCaptureResponse =
            CommandResponse(number = 0L, output = listOf("> $payload", "> $payload", "$ "), isError = false)
        assertEquals(
            DeliveryProbeOutcome.AlreadyLanded,
            verifyBeforeAgentResend(ledger, client, paneId, payload, payload),
        )
    }

    // ---------------------------------------------------------------- issue #1587 H3 (single-store)

    /**
     * Issue #1587 (H3) — structural single-store invariant. [outboundDeliveryLedgerFor]
     * threads the EXACT injected [OutboundQueueStore][com.pocketshell.app.composer.OutboundQueueStore]
     * (the SAME @Singleton instance the composer and the 15s orphan sweep use) as the
     * ledger's durable backing — it does NOT `new` up a second store. Proven by identity:
     * a wire attempt recorded through the ledger is visible via the SAME store object the
     * sweep reads (one instance ⇒ one lock ⇒ no lost-update race that erases the flag).
     */
    @Test
    fun outboundDeliveryLedgerForThreadsTheExactInjectedStoreNotASecondInstance() {
        val store = com.pocketshell.app.composer.InMemoryOutboundQueueStore()
        store.enqueue("sessA", "single-store payload", paneId = "%0")
        val ledger = outboundDeliveryLedgerFor(store)

        ledger.recordWireAttempt("%0", "single-store payload", "single-store payload")

        assertTrue(
            "the wire attempt recorded via the ledger must be visible through the SAME store " +
                "instance the orphan sweep reads (one lock — no lost-update race)",
            store.hasWireAttempt("%0", "single-store payload"),
        )
    }

    /** A null store (narrow unit-test VM constructors) ⇒ base S1 in-memory-only ledger. */
    @Test
    fun outboundDeliveryLedgerForWithNoStoreIsInMemoryOnly() {
        val ledger = outboundDeliveryLedgerFor(null)
        ledger.recordWireAttempt("%0", "in-memory only payload", "in-memory only payload")
        assertTrue(ledger.hasAmbiguousAttempt("%0", "in-memory only payload", "in-memory only payload"))
    }

    @Test
    fun ledgerTracksRecordsClearsAndEvictsOldestBeyondCapacity() {
        // Issue #1529: the volatile identity is (pane, sendToken). These tests use the payload
        // string as each attempt's token, so distinct payloads are distinct attempts.
        val ledger = OutboundDeliveryLedger(maxEntries = 2)
        ledger.recordWireAttempt("%0", "first payload", "first payload")
        assertTrue(ledger.hasAmbiguousAttempt("%0", "first payload", "first payload"))
        assertFalse(ledger.hasAmbiguousAttempt("%1", "first payload", "first payload"))

        ledger.clear("%0", "first payload")
        assertFalse(ledger.hasAmbiguousAttempt("%0", "first payload", "first payload"))

        ledger.recordWireAttempt("%0", "a", "a")
        ledger.recordWireAttempt("%0", "b", "b")
        ledger.recordWireAttempt("%0", "c", "c")
        assertFalse("oldest entry must be evicted", ledger.hasAmbiguousAttempt("%0", "a", "a"))
        assertTrue(ledger.hasAmbiguousAttempt("%0", "b", "b"))
        assertTrue(ledger.hasAmbiguousAttempt("%0", "c", "c"))
    }
}
