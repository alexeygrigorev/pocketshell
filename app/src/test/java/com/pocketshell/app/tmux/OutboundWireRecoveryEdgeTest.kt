package com.pocketshell.app.tmux

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OutboundWireRecoveryEdgeTest {
    @Test
    fun initiallyWritableWireUnparksPersistedFailureOnceAfterRecreate() = runTest {
        val controller = OutboundQueueAutoFlushController.boundTo(
            outboundBudgetTestComposer(),
            clock = { testScheduler.currentTime },
        )
        controller.onConnectionWindowChanged(true, "1/issue2042") {}
        var unparkCalls = 0
        val job = launch {
            runOutboundQueueAutoFlush(
                sessionLive = true,
                outboundQueueItems = flow {
                    emit(Unit)
                    awaitCancellation()
                },
                controller = controller,
                retryNext = { null },
                transportWritable = { true },
                unparkTransportFailedRows = { unparkCalls++ },
            )
        }

        runCurrent()
        assertEquals("startup wire truth must recover a persisted parked row", 1, unparkCalls)
        advanceTimeBy(OUTBOUND_DEFERRED_REDISPATCH_BACKOFF_MS + 1L)
        runCurrent()
        assertEquals("sustained startup truth must not refill its budget again", 1, unparkCalls)
        job.cancelAndJoin()
    }

    @Test
    fun wireRecoveryUnparksOncePerFalseToTrueEdge() = runTest {
        val controller = OutboundQueueAutoFlushController.boundTo(
            outboundBudgetTestComposer(),
            clock = { testScheduler.currentTime },
        )
        controller.onConnectionWindowChanged(false, "1/issue2042") {}
        var writable = false
        var unparkCalls = 0
        val quietQueue = flow<Any?> {
            emit(Unit)
            awaitCancellation()
        }
        val job = launch {
            runOutboundQueueAutoFlush(
                sessionLive = false,
                outboundQueueItems = quietQueue,
                controller = controller,
                retryNext = { null },
                transportWritable = { writable },
                unparkTransportFailedRows = { unparkCalls++ },
            )
        }

        runCurrent()
        assertEquals("initially dead wire has no recovery edge", 0, unparkCalls)

        writable = true
        advanceTimeBy(OUTBOUND_DEFERRED_REDISPATCH_BACKOFF_MS + 1L)
        runCurrent()
        assertEquals("false→true unparks before the FIFO retry", 1, unparkCalls)

        advanceTimeBy(OUTBOUND_DEFERRED_REDISPATCH_BACKOFF_MS + 1L)
        runCurrent()
        assertEquals("sustained true never resets a poison row budget again", 1, unparkCalls)

        writable = false
        advanceTimeBy(OUTBOUND_DEFERRED_REDISPATCH_BACKOFF_MS + 1L)
        runCurrent()
        assertEquals(1, unparkCalls)

        writable = true
        advanceTimeBy(OUTBOUND_DEFERRED_REDISPATCH_BACKOFF_MS + 1L)
        runCurrent()
        assertEquals("a later independent recovery earns one new unpark", 2, unparkCalls)

        job.cancelAndJoin()
    }

    @Test
    fun enumEdgeAndWireEdgeCoexistWithoutRearmingAnAlreadyUnparkedRow() = runTest {
        val controller = OutboundQueueAutoFlushController.boundTo(
            outboundBudgetTestComposer(),
            clock = { testScheduler.currentTime },
        )
        controller.onConnectionWindowChanged(false, "1/issue2042") {}
        var writable = false
        var parked = true
        var effectiveUnparks = 0
        val idempotentUnpark = {
            if (parked) {
                parked = false
                effectiveUnparks++
            }
        }
        val job = launch {
            runOutboundQueueAutoFlush(
                sessionLive = false,
                outboundQueueItems = flow {
                    emit(Unit)
                    awaitCancellation()
                },
                controller = controller,
                retryNext = { null },
                transportWritable = { writable },
                unparkTransportFailedRows = idempotentUnpark,
            )
        }
        runCurrent()

        // The existing coarse-enum effect wins the race first.
        idempotentUnpark()
        assertEquals(1, effectiveUnparks)

        // The independent wire oracle then observes recovery. Its callback is safe:
        // the production VM's Failed→Queued operation is likewise idempotent.
        writable = true
        advanceTimeBy(OUTBOUND_DEFERRED_REDISPATCH_BACKOFF_MS + 1L)
        runCurrent()
        assertEquals("the same row was effectively unparked only once", 1, effectiveUnparks)

        job.cancelAndJoin()
    }
}
