package com.pocketshell.app.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PromptComposerSheetQueueHelperTest {

    @Test
    fun retryableOutboundQueueItemPicksOldestQueuedOrFailedRow() {
        val uploading = item("uploading", OutboundState.Uploading, createdAtMs = 1L)
        val failed = item("failed", OutboundState.Failed, createdAtMs = 2L)
        val queued = item("queued", OutboundState.Queued, createdAtMs = 3L)

        assertEquals(failed, retryableOutboundQueueItem(listOf(uploading, failed, queued)))
    }

    @Test
    fun retryableOutboundQueueItemIgnoresActiveAndDeliveredRows() {
        assertNull(
            retryableOutboundQueueItem(
                listOf(
                    item("uploading", OutboundState.Uploading, createdAtMs = 1L),
                    item("in-flight", OutboundState.InFlight, createdAtMs = 2L),
                    item("delivered", OutboundState.Delivered, createdAtMs = 3L),
                ),
            ),
        )
    }

    @Test
    fun retryableOutboundQueueItemTreatsRecoveredUploadingRowAsRetryableAfterRequeue() {
        val recoveredUpload = item("recovered-upload", OutboundState.Queued, createdAtMs = 1L)
        val freshUpload = item("fresh-upload", OutboundState.Uploading, createdAtMs = 2L)

        assertEquals(
            recoveredUpload,
            retryableOutboundQueueItem(listOf(recoveredUpload, freshUpload)),
        )
    }

    private fun item(id: String, state: OutboundState, createdAtMs: Long): OutboundItem =
        OutboundItem(
            id = id,
            sessionKey = "1/session-a",
            cleanText = id,
            state = state,
            createdAtMs = createdAtMs,
        )
}
