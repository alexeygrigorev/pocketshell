package com.pocketshell.app.tmux

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
