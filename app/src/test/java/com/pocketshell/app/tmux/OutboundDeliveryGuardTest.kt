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
        // First attempt reached the wire ambiguously (recorded) AND landed — the
        // pane shows the payload.
        ledger.recordWireAttempt(paneId, payload)
        val client = captureShowing("$ $payload")

        val outcome = verifyBeforeAgentResend(ledger, client, paneId, payload)

        assertEquals(
            "a re-dispatched already-landed payload must resolve AlreadyLanded so the send path " +
                "submits Enter only and never re-pastes (occurrence stays 1)",
            DeliveryProbeOutcome.AlreadyLanded,
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

        val outcome = verifyBeforeAgentResend(ledger, client, "%0", "a brand new prompt")

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
        ledger1.recordWireAttempt("%0", "durable payload")
        assertTrue(ledger1.hasAmbiguousAttempt("%0", "durable payload"))

        // Back-navigation clears the VM: its volatile ledger dies. A base ledger
        // with NO durable backing has no memory of the attempt (the P9 bug).
        val baseRebuilt = OutboundDeliveryLedger()
        assertFalse(
            "RED (base): a volatile-only rebuilt ledger loses the wire attempt, so " +
                "the reopened session blindly re-pastes (server occurrence 2)",
            baseRebuilt.hasAmbiguousAttempt("%0", "durable payload"),
        )

        // The fix: a ledger rebuilt with the SAME durable store re-reads the flag
        // and runs verify-before-resend (server occurrence 1).
        val rebuilt = OutboundDeliveryLedger(durable = durable)
        assertTrue(
            "GREEN (fix): the durable `wireAttempted` flag makes the rebuilt ledger " +
                "verify-before-resend instead of blindly re-pasting",
            rebuilt.hasAmbiguousAttempt("%0", "durable payload"),
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
        assertFalse(ledger.hasAmbiguousAttempt("%0", "a brand new prompt"))
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
        ledger.recordWireAttempt("%0", "unacked payload")
        ledger.clear("%0", "unacked payload")

        assertTrue(
            "clear() drops only the volatile entry; the durable row flag persists so " +
                "a rebuilt ledger still verifies",
            ledger.hasAmbiguousAttempt("%0", "unacked payload"),
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
        store.markWireAttempted(paneId, payload) // the actual wire push
        assertTrue(
            "the wire push must durably record the wire attempt on the row",
            store.hasWireAttempt(paneId, payload),
        )

        // VM churn / process death: the volatile ledger dies. A ledger REBUILT with
        // NO durable backing has no memory of the attempt (the pre-fix blind-repaste
        // trap) — verify-before-resend would be skipped.
        assertNull(
            "a volatile-only rebuilt ledger loses the orphaned attempt (would blind re-paste)",
            verifyBeforeAgentResend(OutboundDeliveryLedger(), captureShowing("$ $payload"), paneId, payload),
        )

        // The fix path: the ledger rebuilt from the SAME durable store re-reads the
        // claim-stamped flag → verify → the pane shows the payload → AlreadyLanded,
        // so the send path submits Enter only and never re-pastes (occurrence 1).
        val rebuilt = OutboundDeliveryLedger(durable = store.asWireAttemptDurableStore())
        val outcome = verifyBeforeAgentResend(rebuilt, captureShowing("$ $payload"), paneId, payload)
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

        ledger.recordWireAttemptWithBaseline(client, "%0", "/goal resume", localRenderText = "$ ")

        assertTrue(
            "no baseline capture round-trip when the payload is not already on the render",
            client.capturePaneTextViaExecCalls.isEmpty(),
        )
        assertEquals(0, ledger.needleBaseline("%0", "/goal resume"))
        assertEquals(
            "a resend that finds the payload (count 1 > baseline 0) is AlreadyLanded",
            DeliveryProbeOutcome.AlreadyLanded,
            verifyBeforeAgentResend(ledger, client, "%0", "/goal resume"),
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
        ledger.recordWireAttemptWithBaseline(client, "%0", "/goal resume", localRenderText = statusLine)
        assertEquals("the pre-send baseline is captured authoritatively", 1, ledger.needleBaseline("%0", "/goal resume"))
        assertEquals("exactly one baseline capture round-trip", 1, client.capturePaneTextViaExecCalls.size)

        // A genuine resend whose paste did NOT land: the pane still shows ONLY the
        // status line (count 1 == baseline 1) ⇒ NotLanded ⇒ re-send (no false drop).
        client.capturePaneResponses.addLast(CommandResponse(number = 0L, output = listOf(statusLine), isError = false))
        assertEquals(
            "a payload still only on the status line (count == baseline) must NOT be " +
                "falsely AlreadyLanded (the #1577 false-success drop)",
            DeliveryProbeOutcome.NotLanded,
            verifyBeforeAgentResend(ledger, client, "%0", "/goal resume"),
        )

        // A resend whose paste DID land: the pane now shows the status line AND the
        // submitted command (count 2 > baseline 1) ⇒ AlreadyLanded ⇒ deduped.
        client.capturePaneResponses.addLast(
            CommandResponse(number = 0L, output = listOf(statusLine, "> /goal resume"), isError = false),
        )
        assertEquals(
            "a payload whose occurrence count INCREASED (our paste landed) is AlreadyLanded",
            DeliveryProbeOutcome.AlreadyLanded,
            verifyBeforeAgentResend(ledger, client, "%0", "/goal resume"),
        )
    }

    @Test
    fun ledgerTracksRecordsClearsAndEvictsOldestBeyondCapacity() {
        val ledger = OutboundDeliveryLedger(maxEntries = 2)
        ledger.recordWireAttempt("%0", "first payload")
        assertTrue(ledger.hasAmbiguousAttempt("%0", "first payload"))
        assertFalse(ledger.hasAmbiguousAttempt("%1", "first payload"))

        ledger.clear("%0", "first payload")
        assertFalse(ledger.hasAmbiguousAttempt("%0", "first payload"))

        ledger.recordWireAttempt("%0", "a")
        ledger.recordWireAttempt("%0", "b")
        ledger.recordWireAttempt("%0", "c")
        assertFalse("oldest entry must be evicted", ledger.hasAmbiguousAttempt("%0", "a"))
        assertTrue(ledger.hasAmbiguousAttempt("%0", "b"))
        assertTrue(ledger.hasAmbiguousAttempt("%0", "c"))
    }
}
