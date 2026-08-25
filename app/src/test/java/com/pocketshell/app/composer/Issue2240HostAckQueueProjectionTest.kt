package com.pocketshell.app.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #2240: an unresolved HostAck head is held for an explicit user decision,
 * while held rows and the unknown row do not prevent a healthy tail from draining.
 */
class Issue2240HostAckQueueProjectionTest {

    @Test
    fun unknownHostAckRowIsExcludedFromOrdinaryRetrySelectionWhileNormalRowIsSelected() {
        val session = "tmux:2240/\$7/ordinary-retry"
        val unknown = OutboundItem(
            id = "row-2240-unknown-ordinary-retry",
            sessionKey = session,
            cleanText = "may already have landed",
            createdAtMs = 1L,
            state = OutboundState.Failed,
            hostAckOutcome = OutboundDeliveryOutcome.UnknownMayHaveLanded,
        )
        val ordinary = OutboundItem(
            id = "row-2240-normal-ordinary-retry",
            sessionKey = session,
            cleanText = "definitely failed",
            createdAtMs = 2L,
            state = OutboundState.Failed,
        )

        assertEquals(
            "G6: the production ordinary-retry selector must skip UnknownMayHaveLanded",
            listOf(ordinary.id),
            listOf(unknown, ordinary).composerQueueRetryableItems().map { it.id },
        )
    }

    @Test
    fun unknownAndHeldHeadsAreSkippedBeforeAHealthyTail() {
        val session = "tmux:2240/\$7/queue-plan"
        val unknown = OutboundItem(
            id = "row-2240-unknown-head",
            sessionKey = session,
            cleanText = "may already have landed",
            createdAtMs = 1L,
            hostAckOutcome = OutboundDeliveryOutcome.UnknownMayHaveLanded,
        )
        val held = OutboundItem(
            id = "row-2240-held-head",
            sessionKey = session,
            cleanText = "needs review",
            createdAtMs = 2L,
            state = OutboundState.HeldForReview,
        )
        val tail = OutboundItem(
            id = "row-2240-healthy-tail",
            sessionKey = session,
            cleanText = "safe to send",
            createdAtMs = 3L,
        )

        assertEquals(
            "unknown and held rows must not block an independent healthy tail",
            tail.id,
            listOf(unknown, held, tail).firstComposerAutoFlushable(
                sessionKey = session,
                maxAutoAttempts = OUTBOUND_MAX_AUTO_ATTEMPTS,
            )?.id,
        )
    }

    @Test
    fun unknownHostAckRowsAreExcludedFromAutoRetryExhaustionParking() {
        val session = "tmux:2240/\$7/auto-retry"
        val unknown = OutboundItem(
            id = "row-2240-unknown-exhausted",
            sessionKey = session,
            cleanText = "may already have landed",
            createdAtMs = 1L,
            attemptCount = OUTBOUND_MAX_AUTO_ATTEMPTS,
            hostAckOutcome = OutboundDeliveryOutcome.UnknownMayHaveLanded,
        )
        val ordinaryExhausted = OutboundItem(
            id = "row-2240-ordinary-exhausted",
            sessionKey = session,
            cleanText = "definitely failed",
            createdAtMs = 2L,
            attemptCount = OUTBOUND_MAX_AUTO_ATTEMPTS,
        )

        val parked = listOf(unknown, ordinaryExhausted).autoRetryExhaustedComposerRows(
            sessionKey = session,
            maxAutoAttempts = OUTBOUND_MAX_AUTO_ATTEMPTS,
        )

        assertEquals(
            "G6: removing autoRetryExhaustedComposerRows' HostAck guard would park the unknown row",
            listOf(ordinaryExhausted.id),
            parked.map { it.id },
        )
    }

    @Test
    fun unknownHostAckRowsDoNotBlockGenerationPromotion() {
        val session = "host:2240"
        val unknown = generationBoundRow(
            id = "row-2240-unknown-generation",
            sessionKey = session,
            hostAckOutcome = OutboundDeliveryOutcome.UnknownMayHaveLanded,
        )
        val ordinary = generationBoundRow(
            id = "row-2240-ordinary-generation",
            sessionKey = session,
        )

        assertFalse(
            "G6: removing hasGenerationBoundRowsAwaitingPromotion's HostAck guard would hold the unknown row",
            listOf(unknown).hasGenerationBoundRowsAwaitingPromotion(session),
        )
        assertTrue(
            "the same guard must not suppress an ordinary generation-bound row",
            listOf(ordinary).hasGenerationBoundRowsAwaitingPromotion(session),
        )
        assertTrue(
            "an ordinary row still blocks promotion when an unknown row is also present",
            listOf(unknown, ordinary).hasGenerationBoundRowsAwaitingPromotion(session),
        )
    }

    @Test
    fun unknownHostAckRowsAreExcludedFromDeferredRetryCandidate() {
        val unknown = OutboundItem(
            id = "row-2240-unknown-deferred",
            sessionKey = "tmux:2240/\$7/deferred",
            cleanText = "requested draft",
            createdAtMs = 1L,
            hostAckOutcome = OutboundDeliveryOutcome.UnknownMayHaveLanded,
        )
        val ordinary = OutboundItem(
            id = "row-2240-ordinary-deferred",
            sessionKey = unknown.sessionKey,
            cleanText = "different draft",
            createdAtMs = 2L,
        )
        val request = PromptComposerViewModel.SendRequest(
            text = "requested draft",
            withEnter = true,
            cleanDraft = "requested draft",
        )

        assertEquals(
            "G6: removing deferredRetryCandidateFor's HostAck guard would select the exact unknown row",
            ordinary.id,
            listOf(unknown, ordinary).deferredRetryCandidateFor(request)?.id,
        )
    }

    private fun generationBoundRow(
        id: String,
        sessionKey: String,
        hostAckOutcome: OutboundDeliveryOutcome = OutboundDeliveryOutcome.None,
    ): OutboundItem = OutboundItem(
        id = id,
        sessionKey = sessionKey,
        cleanText = id,
        createdAtMs = 1L,
        paneId = "%0",
        tmuxSessionId = "tmux-session-2240",
        tmuxSessionCreated = 2L,
        hostAckOutcome = hostAckOutcome,
    )
}
