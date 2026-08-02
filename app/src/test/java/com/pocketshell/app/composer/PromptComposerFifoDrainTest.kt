package com.pocketshell.app.composer

import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptComposerFifoDrainTest {
    @Test
    fun temporarilySuppressedHeadBlocksTailInsteadOfInvertingFifo() {
        val session = "1/session-a"
        val first = OutboundItem(
            id = "first",
            sessionKey = session,
            cleanText = "first payload",
            createdAtMs = 1L,
            attemptCount = 1,
        )
        val second = OutboundItem(
            id = "second",
            sessionKey = session,
            cleanText = "second payload",
            createdAtMs = 2L,
        )

        val plan = listOf(first, second).planComposerAutoFlush(
            sessionKey = session,
            excludingIds = setOf(first.id),
        )

        assertNull(
            "a live-window backoff may pause the FIFO, never send a later row first",
            plan.nextId,
        )
    }

    @Test
    fun concurrentTriggersCannotAcquireTailWhileHeadOwnsDrain() {
        val ownership = OutboundDrainOwnership()

        val firstLease = requireNotNull(ownership.tryAcquire("first"))
        val outcomes = (1..32).map { index ->
            Thread {
                if (index % 2 == 0) {
                    ownership.tryAcquire("second")
                } else {
                    ownership.tryAcquire("first")
                }
            }.apply { start() }
        }
        outcomes.forEach(Thread::join)

        assertEquals("first", ownership.activeRowId())
        assertNull(ownership.tryAcquire("second"))
        assertTrue(ownership.release(firstLease))
        assertEquals("first", requireNotNull(ownership.tryAcquire("first")).rowId)
    }

    @Test
    fun promotedInFlightHeadBlocksQueuedTailUntilTerminalCallback() {
        val durable = "tmux:1:\$0:1944"
        val first = OutboundItem(
            id = "first",
            sessionKey = durable,
            cleanText = "first payload already on the pane wire",
            state = OutboundState.InFlight,
            createdAtMs = 1L,
        )
        val second = OutboundItem(
            id = "second",
            sessionKey = durable,
            cleanText = "second payload",
            state = OutboundState.Queued,
            createdAtMs = 2L,
        )

        assertNull(
            "promotion must not skip an older physical delivery and paste B into A's input",
            listOf(first, second).planComposerAutoFlush(durable).nextId,
        )
    }

    @Test
    fun cancellationOrStrandCleanupReleasesPhysicalOwner() {
        val ownership = OutboundDrainOwnership()
        requireNotNull(ownership.tryAcquire("first"))
        assertEquals("first", ownership.forceRelease())
        assertNull(ownership.activeRowId())
        val retryLease = ownership.tryAcquire("first")
        assertEquals("the same FIFO head can be retried after cleanup", "first", retryLease?.rowId)
        assertTrue(ownership.release(retryLease))
    }

    @Test
    fun staleSameRowTerminalCallbackCannotReleaseReplacementAttempt() {
        val ownership = OutboundDrainOwnership()
        val firstAttempt = requireNotNull(ownership.tryAcquire("first"))
        assertTrue(ownership.release(firstAttempt))
        val replacementAttempt = requireNotNull(ownership.tryAcquire("first"))

        assertFalse("row id alone must not create an ABA release", ownership.release(firstAttempt))
        assertEquals("first", ownership.activeRowId())
        assertTrue(ownership.release(replacementAttempt))
    }
}
