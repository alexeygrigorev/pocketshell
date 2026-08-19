package com.pocketshell.app.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentTransferProgressTest {

    @Test
    fun compactBytesMatchShareFlowUnits() {
        assertEquals("999 B", formatCompactBytes(999))
        assertEquals("1.5 KB", formatCompactBytes(1536))
        assertEquals("2.0 MB", formatCompactBytes(2L * 1024L * 1024L))
        assertEquals("2.5 MB", formatCompactBytes(2_621_440L))
        assertEquals("5.0 MB", formatCompactBytes(5L * 1024L * 1024L))
    }

    @Test
    fun knownTotalLabelIncludesBytesPercentAndOptionalFileCounter() {
        val single = progress(bytes = 2_621_440L, total = 5_242_880L)
        assertEquals("Uploading 2.5 / 5.0 MB · 50%", formatAttachmentTransferLabel(single))
        val multi = single.copy(fileIndex = 2, fileCount = 3)
        assertEquals("Uploading 2.5 / 5.0 MB · 50% · 2 of 3", formatAttachmentTransferLabel(multi))
    }

    @Test
    fun unknownTotalOmitsPercentAndUsesTransferredBytes() {
        val unknown = progress(bytes = 2_621_440L, total = 0L, batchTotal = 0L)
        assertEquals("Uploading 2.5 MB", formatAttachmentTransferLabel(unknown))
        assertNull(unknown.fraction)
    }

    @Test
    fun bannerFallsBackToStaticCountWhenProgressMissing() {
        assertEquals("Uploading 1 attachment...", attachmentUploadBannerText(1, null))
        assertEquals("Uploading 3 attachments...", attachmentUploadBannerText(3, null))
        assertEquals(
            "Uploading 2.5 / 5.0 MB · 50%",
            attachmentUploadBannerText(1, progress(2_621_440L, 5_242_880L)),
        )
    }

    @Test
    fun singleFileStartMidTerminalAdvance() {
        val clock = mutableListOf(0L)
        val aggregator = AttachmentTransferAggregator(
            files = listOf(AttachmentFileSpec("a", "report.zip", 5_242_880L)),
            nowMillis = { clock.first() },
        )
        val start = aggregator.beginAttempt()
        assertEquals(0L, start.bytesTransferred)
        assertEquals(0, start.percent)
        clock[0] = 250L
        val mid = aggregator.onFileProgress(aggregator.currentGeneration, 0, 2_621_440L, 5_242_880L)
        assertNotNull(mid)
        assertEquals(2_621_440L, mid!!.bytesTransferred)
        assertEquals(50, mid.percent)
        val done = aggregator.terminal(aggregator.currentGeneration)
        assertEquals(5_242_880L, done!!.batchBytesTransferred)
        assertEquals(100, done.percent)
        assertTrue(mid.bytesTransferred > start.bytesTransferred)
        assertTrue(done.bytesTransferred > mid.bytesTransferred)
    }

    @Test
    fun multiFileAggregatesCompletedPlusCurrent() {
        val aggregator = AttachmentTransferAggregator(
            files = listOf(
                AttachmentFileSpec("a", "one.bin", 10L),
                AttachmentFileSpec("b", "two.bin", 10L),
                AttachmentFileSpec("c", "three.bin", 10L),
            ),
        )
        aggregator.beginAttempt()
        aggregator.completeFile(aggregator.currentGeneration, 0)
        val second = aggregator.onFileProgress(
            aggregator.currentGeneration,
            fileIndex = 1,
            bytesTransferred = 5L,
            totalBytes = 10L,
            force = true,
        )
        assertEquals(15L, second!!.batchBytesTransferred)
        assertEquals(30L, second.batchTotalBytes)
        assertEquals(2, second.fileIndex)
        assertEquals(3, second.fileCount)
        assertEquals(50, second.percent)
    }

    @Test
    fun resumedOffsetStartsAboveZeroAndDoesNotReset() {
        val aggregator = AttachmentTransferAggregator(
            files = listOf(AttachmentFileSpec("a", "report.zip", 52L)),
        )
        val start = aggregator.beginAttempt(resumeBytes = 30L)
        assertEquals(30L, start.bytesTransferred)
        assertEquals(57, start.percent)
        val next = aggregator.onFileProgress(
            aggregator.currentGeneration,
            0,
            bytesTransferred = 40L,
            totalBytes = 52L,
            force = true,
        )
        assertEquals(40L, next!!.bytesTransferred)
        val regress = aggregator.onFileProgress(
            aggregator.currentGeneration,
            0,
            bytesTransferred = 10L,
            totalBytes = 52L,
            force = true,
        )
        assertEquals(40L, regress!!.bytesTransferred)
    }

    @Test
    fun clampRejectsNegativeAndOverTotal() {
        val aggregator = AttachmentTransferAggregator(
            files = listOf(AttachmentFileSpec("a", "a.bin", 100L)),
        )
        aggregator.beginAttempt()
        val over = aggregator.onFileProgress(
            aggregator.currentGeneration,
            0,
            bytesTransferred = 9_999L,
            totalBytes = 100L,
            force = true,
        )
        assertEquals(100L, over!!.bytesTransferred)
        val negative = aggregator.onFileProgress(
            aggregator.currentGeneration,
            0,
            bytesTransferred = -20L,
            totalBytes = 100L,
            force = true,
        )
        assertEquals(100L, negative!!.bytesTransferred)
    }

    @Test
    fun lateGenerationIsIgnored() {
        val aggregator = AttachmentTransferAggregator(
            files = listOf(AttachmentFileSpec("a", "a.bin", 100L)),
        )
        aggregator.beginAttempt(generation = 2L)
        assertNull(
            aggregator.onFileProgress(
                generation = 1L,
                fileIndex = 0,
                bytesTransferred = 50L,
                totalBytes = 100L,
                force = true,
            ),
        )
        val live = aggregator.onFileProgress(
            generation = 2L,
            fileIndex = 0,
            bytesTransferred = 50L,
            totalBytes = 100L,
            force = true,
        )
        assertEquals(50L, live!!.bytesTransferred)
    }

    @Test
    fun floodOfEightKibCallbacksStaysWithinThrottleBound() {
        val clock = mutableListOf(0L)
        val total = 5L * 1024L * 1024L
        val aggregator = AttachmentTransferAggregator(
            files = listOf(AttachmentFileSpec("a", "report.zip", total)),
            nowMillis = { clock.first() },
        )
        aggregator.beginAttempt()
        val chunk = 8L * 1024L
        var transferred = 0L
        while (transferred < total) {
            transferred = (transferred + chunk).coerceAtMost(total)
            clock[0] = transferred / 1_000L
            aggregator.onFileProgress(aggregator.currentGeneration, 0, transferred, total)
        }
        aggregator.terminal(aggregator.currentGeneration)
        assertTrue(
            "published ${aggregator.publishedUpdateCount} updates for ${total / chunk} chunks",
            aggregator.publishedUpdateCount <= 32,
        )
        assertTrue(aggregator.publishedUpdateCount >= 2)
    }

    @Test
    fun accessibilityMilestonesAreCoarse() {
        assertTrue(isAccessibilityMilestone(0))
        assertTrue(isAccessibilityMilestone(25))
        assertTrue(isAccessibilityMilestone(50))
        assertTrue(isAccessibilityMilestone(75))
        assertTrue(isAccessibilityMilestone(100))
        assertTrue(!isAccessibilityMilestone(42))
        assertEquals(
            "Uploading 50 percent",
            accessibilityProgressDescription(progress(2_621_440L, 5_242_880L)),
        )
    }

    @Test
    fun noCallbackMutantLeavesAdvancingOracleRed() {
        val aggregator = AttachmentTransferAggregator(
            files = listOf(AttachmentFileSpec("a", "report.zip", 5_242_880L)),
        )
        val start = aggregator.beginAttempt()
        val later = aggregator.terminal(aggregator.currentGeneration)
        assertNotEquals(
            "a transfer with no intermediate callback cannot show advancing mid-progress",
            2_621_440L,
            start.bytesTransferred,
        )
        assertEquals(0L, start.bytesTransferred)
        assertEquals(5_242_880L, later!!.bytesTransferred)
    }

    private fun progress(
        bytes: Long,
        total: Long,
        batchTotal: Long = total,
    ): AttachmentTransferProgress = AttachmentTransferProgress(
        fileIndex = 1,
        fileCount = 1,
        fileName = "report.zip",
        bytesTransferred = bytes,
        totalBytes = total,
        batchBytesTransferred = bytes,
        batchTotalBytes = batchTotal,
    )
}
