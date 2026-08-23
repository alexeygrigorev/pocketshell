package com.pocketshell.app.composer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboundQueueRetentionPolicyTest {

    @Test
    fun ageAloneNeverAuthorizesDeletion() {
        val row = queued(createdAtMs = 1L)
        assertFalse(
            "G6 mutation: age must never replace explicit row authorization",
            OutboundQueueRetentionPolicy.mayDiscard(
                row = row,
                authorization = OutboundDisposalAuthorization.ExplicitDiscard("other-id"),
                nowMs = 1_000_000_000L,
                createdAtMs = 1L,
            ),
        )
    }

    @Test
    fun explicitDiscardRequiresTheExactRowId() {
        val row = queued()
        assertTrue(
            OutboundQueueRetentionPolicy.mayDiscard(
                row,
                OutboundDisposalAuthorization.ExplicitDiscard(row.id),
            ),
        )
        assertFalse(
            "G6: a different row id must not authorize this row",
            OutboundQueueRetentionPolicy.mayDiscard(
                row,
                OutboundDisposalAuthorization.ExplicitDiscard("different-row"),
            ),
        )
    }

    @Test
    fun explicitDiscardAllowsHeldRowsButNeverActiveRows() {
        val queued = queued()
        val failed = queued.copy(id = "failed", state = OutboundState.Failed)
        val held = queued.copy(id = "held", state = OutboundState.HeldForReview)
        val inFlight = queued.copy(id = "inflight", state = OutboundState.InFlight)
        val uploading = queued.copy(id = "uploading", state = OutboundState.Uploading)

        assertTrue(
            OutboundQueueRetentionPolicy.mayDiscard(
                queued,
                OutboundDisposalAuthorization.ExplicitDiscard(queued.id),
            ),
        )
        assertTrue(
            OutboundQueueRetentionPolicy.mayDiscard(
                failed,
                OutboundDisposalAuthorization.ExplicitDiscard(failed.id),
            ),
        )
        assertTrue(
            OutboundQueueRetentionPolicy.mayDiscard(
                held,
                OutboundDisposalAuthorization.ExplicitDiscard(held.id),
            ),
        )
        assertFalse(
            OutboundQueueRetentionPolicy.mayDiscard(
                inFlight,
                OutboundDisposalAuthorization.ExplicitDiscard(inFlight.id),
            ),
        )
        assertFalse(
            OutboundQueueRetentionPolicy.mayDiscard(
                uploading,
                OutboundDisposalAuthorization.ExplicitDiscard(uploading.id),
            ),
        )
    }

    @Test
    fun disposalPermitRefusesWhileDeliveryOwnsThePhysicalDrain() {
        val owner = OutboundDrainOwnership()
        val delivery = requireNotNull(owner.tryAcquire("row-a"))

        assertTrue("precondition: delivery owns row-a", owner.activeRowId() == "row-a")
        assertTrue(
            "G6: Delete must have no typed permit while delivery is live",
            owner.tryAcquireDisposal("row-a") == null,
        )

        assertTrue(owner.release(delivery))
        val permit = requireNotNull(owner.tryAcquireDisposal("row-a"))
        assertTrue(permit.isCurrent())
        assertTrue(owner.releaseDisposal(permit))
    }

    @Test
    fun draftScopesAreNeverQueueOrphans() {
        assertTrue(OutboundQueueRetentionPolicy.isDraftSidecarScope("draft/tmux:7:\$12:1"))
        assertFalse(
            OutboundQueueRetentionPolicy.isOrphanSidecar("draft/tmux:7:\$12:1", liveRowIds = emptySet()),
        )
        assertTrue(OutboundQueueRetentionPolicy.isOrphanSidecar("dead-row", liveRowIds = setOf("live-row")))
        assertFalse(OutboundQueueRetentionPolicy.isOrphanSidecar("live-row", liveRowIds = setOf("live-row")))
    }

    private fun queued(createdAtMs: Long = 10L): OutboundItem =
        OutboundItem(
            id = "row-a",
            sessionKey = "tmux:7:\$12:1700000000",
            cleanText = "parked",
            createdAtMs = createdAtMs,
        )
}
