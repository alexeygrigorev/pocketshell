package com.pocketshell.app.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #2056 — the "genuinely unknown delivery outcome" state.
 *
 * A row whose last attempt could not be proven is neither "sending next" nor a
 * plain failure. It must (a) say so, (b) never be re-picked by the auto-flush
 * drain (it can only reproduce the same unknown answer while starving the tail),
 * (c) survive a process recreate, and (d) collapse through ONE identity so a
 * manual Retry racing a late success can never double-send.
 */
class Issue2056UnknownDeliveryOutcomeSurfaceTest {

    private val session = "tmux:1/\$7/1700"

    private fun unknownRow(id: String = "row-1", createdAtMs: Long = 1L) = OutboundItem(
        id = id,
        sessionKey = session,
        cleanText = "csp",
        createdAtMs = createdAtMs,
        state = OutboundState.Queued,
        paneId = "%0",
        sendKey = "sk-$id",
        wireAttempted = true,
        wireSubmitAttempted = true,
        wireAttemptGeneration = 1,
        wireOutcomeUnknown = true,
    )

    @Test
    fun unknownDeliveryOutcomeIsStatedAsUnknownNotAsQueuedSendingNext() {
        val unknown = unknownRow()

        val summary = outboundQueueSummary(listOf(unknown), connectionDegraded = false)
        assertEquals(
            "the launcher summary must state the unknown delivery outcome (#2056)",
            OUTBOUND_DELIVERY_UNCONFIRMED_SUMMARY,
            summary.primary,
        )
        assertTrue("an unconfirmed delivery needs the user's attention", summary.attention)
        assertEquals(
            "the row label must state the unknown delivery outcome (#2056)",
            OUTBOUND_DELIVERY_UNCONFIRMED_MESSAGE,
            outboundQueueStateLabel(unknown),
        )

        // A degraded link must not relabel it "will send on reconnect" either: the
        // payload has already been pushed, so that wording is equally wrong.
        assertEquals(
            OUTBOUND_DELIVERY_UNCONFIRMED_SUMMARY,
            outboundQueueSummary(listOf(unknown), connectionDegraded = true).primary,
        )

        val ordinary = unknown.copy(
            wireOutcomeUnknown = false,
            wireSubmitAttempted = false,
            wireAttempted = false,
        )
        assertEquals(
            "an ordinary queued row keeps its existing wording",
            "Queued — sending next",
            outboundQueueSummary(listOf(ordinary), connectionDegraded = false).primary,
        )
        assertEquals(
            PromptComposerViewModel.WILL_SEND_WHEN_RECONNECTED_MESSAGE,
            outboundQueueStateLabel(ordinary),
        )
    }

    @Test
    fun aMixedBacklogSurfacesTheUnconfirmedCount() {
        val rows = listOf(
            unknownRow(),
            unknownRow(id = "row-1b", createdAtMs = 2L).copy(wireOutcomeUnknown = false),
        )
        val summary = outboundQueueSummary(rows, connectionDegraded = false)
        assertTrue(summary.attention)
        assertEquals("1 unconfirmed", summary.attentionSuffix)
    }

    /** The auto-flush selection must skip the unconfirmed head and reach the tail. */
    @Test
    fun autoFlushSkipsAnUnconfirmedHeadAndSelectsTheTail() {
        val head = unknownRow()
        val tail = OutboundItem(
            id = "row-2",
            sessionKey = session,
            cleanText = "follow-up",
            createdAtMs = 2L,
            state = OutboundState.Queued,
            paneId = "%0",
            sendKey = "sk-row-2",
        )
        val plan = listOf(head, tail).planComposerAutoFlush(session)
        assertEquals("the deliverable tail must be selected", tail.id, plan.nextId)
        assertTrue(
            "an unconfirmed head is not a budget-exhausted park — it is surfaced as unknown",
            plan.parkIds.isEmpty(),
        )
        assertTrue(head.isComposerQueueDeliveryUnconfirmed())
        assertFalse(tail.isComposerQueueDeliveryUnconfirmed())
        assertNotNull(
            "the unconfirmed row is never dropped from the visible queue",
            listOf(head, tail).outboundLauncherBadge(session),
        )
    }

    /**
     * A genuinely InFlight head still blocks the tail — the #2056 skip must be
     * narrow (G6 negative), otherwise a live delivery could be overtaken.
     */
    @Test
    fun aLiveInFlightHeadStillBlocksTheTail() {
        val head = unknownRow().copy(state = OutboundState.InFlight, wireOutcomeUnknown = false)
        val tail = OutboundItem(
            id = "row-2",
            sessionKey = session,
            cleanText = "follow-up",
            createdAtMs = 2L,
            state = OutboundState.Queued,
        )
        assertNull(listOf(head, tail).planComposerAutoFlush(session).nextId)
    }

    @Test
    fun unknownVerdictSurvivesEncodingAndIsClearedByAFreshAttempt() {
        val row = unknownRow()
        val restored = decodeOutboundItems(session, encodeOutboundItems(listOf(row)))
        assertEquals(
            "the unknown verdict must survive a process recreate",
            listOf(row),
            restored,
        )

        val queue = InMemoryOutboundQueueStore()
        queue.enqueueExisting(row)
        val reArmed = requireNotNull(queue.requeueForRetry(row.id, resetAttempts = true))
        assertFalse(
            "an explicit user resend clears the unknown verdict so the row is re-driven",
            reArmed.wireOutcomeUnknown,
        )
        requireNotNull(queue.markDeliveryOutcomeUnknown(row.id))
        assertFalse(
            "claiming a fresh attempt clears the stale verdict so it decides for itself",
            requireNotNull(queue.claim(row.id)).wireOutcomeUnknown,
        )
    }

    /**
     * Exactly-once across the manual-Retry / late-success race: the terminal ack is a
     * compare-and-prune on the row's own identity, so a Retry that already owns the
     * row cannot be pruned out from under it, and a repeated ack is a no-op.
     */
    @Test
    fun manualRetryRacingALateSuccessCollapsesThroughOneIdentity() {
        val queue = InMemoryOutboundQueueStore()
        val row = queue.enqueue(sessionKey = session, cleanText = "csp", createdAtMs = 1, sendKey = "sk-head")
        requireNotNull(queue.markWireAttempted(session, row.id))
        val ambiguous = requireNotNull(queue.markWireSubmitAttempted(session, row.id, null))
        requireNotNull(queue.markDeliveryOutcomeUnknown(row.id))

        val claimed = requireNotNull(queue.claim(row.id))
        assertEquals(OutboundState.InFlight, claimed.state)
        assertFalse(
            "a late success must NOT prune a row a manual Retry already owns",
            queue.acknowledgeLateDelivered(row.id, ambiguous.sendKey, ambiguous.wireAttemptGeneration),
        )
        assertNotNull(queue.item(row.id))

        requireNotNull(queue.requeueForRetry(row.id))
        assertTrue(
            queue.acknowledgeLateDelivered(row.id, ambiguous.sendKey, ambiguous.wireAttemptGeneration),
        )
        assertFalse(
            "a repeated late ack for the same identity is a no-op",
            queue.acknowledgeLateDelivered(row.id, ambiguous.sendKey, ambiguous.wireAttemptGeneration),
        )
        assertNull(queue.item(row.id))
    }
}
