package com.pocketshell.app.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptComposerSheetQueueHelperTest {

    @Test
    fun outboundQueueSummaryIsStatusLedForEverySingleRowState() {
        assertEquals(
            OutboundQueueSummary("Queued — sending next", "“queued prompt”"),
            outboundQueueSummary(listOf(item("queued prompt", OutboundState.Queued, 1L)), false),
        )
        assertEquals(
            OutboundQueueSummary("Queued — will send on reconnect", "“queued prompt”"),
            outboundQueueSummary(listOf(item("queued prompt", OutboundState.Queued, 1L)), true),
        )
        assertEquals(
            OutboundQueueSummary("Uploading attachments", "“uploading”"),
            outboundQueueSummary(listOf(item("uploading", OutboundState.Uploading, 1L)), false),
        )
        assertEquals(
            OutboundQueueSummary("Sending", "“in flight”"),
            outboundQueueSummary(listOf(item("in flight", OutboundState.InFlight, 1L)), false),
        )
        assertEquals(
            OutboundQueueSummary("Failed — tap Retry", "“failed”", attention = true),
            outboundQueueSummary(listOf(item("failed", OutboundState.Failed, 1L)), false),
        )
    }

    @Test
    fun outboundQueueSummaryNormalizesPreviewAndSupportsAttachmentOnlyRows() {
        val multiline = item("  \n first useful line \nsecond", OutboundState.InFlight, 1L)
        val attachmentOnly = item("", OutboundState.Failed, 2L).copy(
            attachments = listOf(
                DurableAttachmentRef("/tmp/a", "a", "text/plain"),
                DurableAttachmentRef("/tmp/b", "b", "text/plain"),
            ),
        )

        assertEquals("“first useful line”", outboundQueueSummary(listOf(multiline), false).preview)
        assertEquals("“2 attachments”", outboundQueueSummary(listOf(attachmentOnly), false).preview)
    }

    @Test
    fun outboundQueueSummaryUsesFailurePrecedenceAndTruthfulMultiRowCopy() {
        val uploading = item("oldest", OutboundState.Uploading, 1L)
        val failed = item("failed", OutboundState.Failed, 2L)
        assertEquals(
            OutboundQueueSummary("2 queued", "“oldest”", attentionSuffix = "1 failed"),
            outboundQueueSummary(listOf(uploading, failed), false),
        )
        assertEquals(
            OutboundQueueSummary("2 queued · uploading oldest first", "“oldest”"),
            outboundQueueSummary(listOf(uploading, item("next", OutboundState.Queued, 2L)), false),
        )
        assertEquals(
            OutboundQueueSummary("2 queued · will send on reconnect", "“oldest”"),
            outboundQueueSummary(listOf(uploading, item("next", OutboundState.Queued, 2L)), true),
        )
    }

    @Test
    fun resendAndProgressPresentationUseTheSameQueueFactsAsTheirActions() {
        val failed = item("failed", OutboundState.Failed, 1L)
        assertTrue(isComposerResendMode("", false, failed, sendInFlight = false))
        assertFalse(isComposerResendMode("draft B", false, failed, sendInFlight = false))
        assertFalse(isComposerResendMode("", true, failed, sendInFlight = false))
        assertFalse(isComposerResendMode("", false, failed, sendInFlight = true))

        assertFalse(showComposerSendProgress(true, listOf(item("active", OutboundState.InFlight, 1L))))
        assertFalse(showComposerSendProgress(true, listOf(item("upload", OutboundState.Uploading, 1L))))
        assertTrue(showComposerSendProgress(true, emptyList()))
        assertFalse(showComposerSendProgress(false, emptyList()))
    }

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
