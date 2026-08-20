package com.pocketshell.app.composer

import java.util.Locale

/**
 * Live attachment-transfer snapshot for attach-time staging and send-time
 * sidecar uploads (issue #1563). Durable queue state stays `Uploading`;
 * this is ephemeral operational progress only.
 */
internal data class AttachmentTransferProgress(
    val fileIndex: Int,
    val fileCount: Int,
    val fileName: String,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val batchBytesTransferred: Long,
    val batchTotalBytes: Long,
) {
    val fraction: Float?
        get() = if (batchTotalBytes > 0L) {
            (batchBytesTransferred.toDouble() / batchTotalBytes.toDouble())
                .toFloat()
                .coerceIn(0f, 1f)
        } else {
            null
        }

    val percent: Int?
        get() = fraction?.let { value -> (value * 100f).toInt().coerceIn(0, 100) }
}

internal data class AttachmentFileSpec(
    val id: String = "",
    val fileName: String,
    val byteSize: Long,
)

/**
 * Aggregates per-file byte callbacks into one UI snapshot.
 *
 * Totals come from prepared temp-file lengths / durable sidecar `byteSize`.
 * Malformed ticks are clamped to `[0, total]`, never regress within one
 * attempt, and a higher resumed starting offset is accepted on retry.
 * Publication is coalesced (start/terminal/file-boundary always; otherwise
 * ~4 Hz or a meaningful byte/percent delta) so an 8 KiB write loop cannot
 * recompose per chunk.
 */
internal class AttachmentTransferAggregator(
    private val files: List<AttachmentFileSpec>,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
    private val minDeltaBytes: Long = DEFAULT_MIN_DELTA_BYTES,
) {
    private var generation: Long = 0L
    private var lastPublishedAt: Long = Long.MIN_VALUE
    private var lastPublishedBatchBytes: Long = -1L
    private var lastPublishedPercent: Int = -1
    private val fileBytes: LongArray = LongArray(files.size)
    private var currentFileIndex: Int = 0

    val currentGeneration: Long
        get() = generation

    val publishedUpdateCount: Int
        get() = publishedCount

    private var publishedCount: Int = 0

    fun beginAttempt(
        generation: Long = this.generation + 1L,
        resumeFileIndex: Int = 0,
        resumeBytes: Long = 0L,
    ): AttachmentTransferProgress {
        this.generation = generation
        lastPublishedAt = Long.MIN_VALUE
        lastPublishedBatchBytes = -1L
        lastPublishedPercent = -1
        publishedCount = 0
        for (index in fileBytes.indices) fileBytes[index] = 0L
        currentFileIndex = resumeFileIndex.coerceIn(0, files.lastIndex.coerceAtLeast(0))
        if (files.isNotEmpty()) {
            fileBytes[currentFileIndex] = clamp(
                resumeBytes,
                files[currentFileIndex].byteSize,
                last = 0L,
            )
        }
        return requireNotNull(snapshot(force = true))
    }

    fun onFileProgress(
        generation: Long,
        fileIndex: Int,
        bytesTransferred: Long,
        totalBytes: Long,
        force: Boolean = false,
    ): AttachmentTransferProgress? {
        if (generation != this.generation) return null
        if (files.isEmpty() || fileIndex !in files.indices) return null
        currentFileIndex = fileIndex
        val knownTotal = when {
            totalBytes > 0L -> totalBytes
            else -> files[fileIndex].byteSize
        }
        fileBytes[fileIndex] = clamp(bytesTransferred, knownTotal, fileBytes[fileIndex])
        return snapshot(force)
    }

    fun completeFile(generation: Long, fileIndex: Int): AttachmentTransferProgress? {
        if (generation != this.generation) return null
        if (fileIndex !in files.indices) return null
        val size = files[fileIndex].byteSize
        if (size > 0L) fileBytes[fileIndex] = size
        currentFileIndex = fileIndex
        return snapshot(force = true)
    }

    fun terminal(generation: Long): AttachmentTransferProgress? {
        if (generation != this.generation) return null
        files.forEachIndexed { index, spec ->
            if (spec.byteSize > 0L) fileBytes[index] = spec.byteSize
        }
        return snapshot(force = true)
    }

    private fun clamp(bytes: Long, total: Long, last: Long): Long {
        val nonNegative = bytes.coerceAtLeast(0L)
        val capped = if (total > 0L) nonNegative.coerceAtMost(total) else nonNegative
        return maxOf(capped, last)
    }

    private fun snapshot(force: Boolean): AttachmentTransferProgress? {
        val progress = currentSnapshot()
        val now = nowMillis()
        val percent = progress.percent ?: -1
        val elapsed = now - lastPublishedAt
        val byteDelta = progress.batchBytesTransferred - lastPublishedBatchBytes
        val percentDelta = if (lastPublishedPercent < 0) Int.MAX_VALUE else percent - lastPublishedPercent
        val shouldPublish = force ||
            lastPublishedBatchBytes < 0L ||
            (elapsed >= minIntervalMs && (byteDelta >= minDeltaBytes || percentDelta >= 1)) ||
            crossedAccessibilityMilestone(lastPublishedPercent, percent)
        if (!shouldPublish) return null
        lastPublishedAt = now
        lastPublishedBatchBytes = progress.batchBytesTransferred
        lastPublishedPercent = percent
        publishedCount += 1
        return progress
    }

    private fun currentSnapshot(): AttachmentTransferProgress {
        val index = currentFileIndex.coerceIn(0, (files.size - 1).coerceAtLeast(0))
        val file = files.getOrNull(index)
        val completed = files.indices.sumOf { fileIndex ->
            if (fileIndex < index) {
                val size = files[fileIndex].byteSize
                if (size > 0L) size else fileBytes[fileIndex]
            } else {
                0L
            }
        }
        val current = if (files.isEmpty()) 0L else fileBytes[index]
        return AttachmentTransferProgress(
            fileIndex = if (files.isEmpty()) 0 else index + 1,
            fileCount = files.size,
            fileName = file?.fileName.orEmpty(),
            bytesTransferred = current,
            totalBytes = file?.byteSize ?: 0L,
            batchBytesTransferred = completed + current,
            batchTotalBytes = files.sumOf { spec -> spec.byteSize.coerceAtLeast(0L) },
        )
    }

    companion object {
        const val DEFAULT_MIN_INTERVAL_MS: Long = 250L
        const val DEFAULT_MIN_DELTA_BYTES: Long = 64L * 1024L
    }
}

internal val ACCESSIBILITY_MILESTONES: List<Int> = listOf(0, 25, 50, 75, 100)

internal fun isAccessibilityMilestone(percent: Int): Boolean =
    percent in ACCESSIBILITY_MILESTONES

internal fun crossedAccessibilityMilestone(previous: Int, current: Int): Boolean {
    if (current < 0 || previous == current) return false
    val low = minOf(previous, current)
    val high = maxOf(previous, current)
    return ACCESSIBILITY_MILESTONES.any { milestone -> milestone > low && milestone <= high }
}

/**
 * Compact byte units matching the share-flow formatter
 * (`ShareViewModel.formatRunningBytes`, issue #1037) so composer and share
 * use the same 1.5 KB / 2.1 MB copy.
 */
internal fun formatCompactBytes(bytes: Long): String {
    val value = bytes.coerceAtLeast(0L)
    return when {
        value < 1024L -> "$value B"
        value < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", value / 1024.0)
        value < 1024L * 1024L * 1024L ->
            String.format(Locale.US, "%.1f MB", value / (1024.0 * 1024.0))
        else -> String.format(Locale.US, "%.1f GB", value / (1024.0 * 1024.0 * 1024.0))
    }
}

internal fun formatByteRange(transferred: Long, total: Long): String {
    val transferredText = formatCompactBytes(transferred)
    val totalText = formatCompactBytes(total)
    val transferredUnit = transferredText.substringAfterLast(' ')
    val totalUnit = totalText.substringAfterLast(' ')
    val transferredNumber = if (transferredUnit == totalUnit) {
        transferredText.substringBeforeLast(' ')
    } else {
        transferredText
    }
    return "$transferredNumber / $totalText"
}

internal fun formatAttachmentTransferLabel(progress: AttachmentTransferProgress): String {
    val sizePart = when {
        progress.batchTotalBytes > 0L ->
            formatByteRange(progress.batchBytesTransferred, progress.batchTotalBytes)
        progress.batchBytesTransferred > 0L -> formatCompactBytes(progress.batchBytesTransferred)
        else -> formatCompactBytes(0L)
    }
    val percentPart = progress.percent?.let { percent -> " · $percent%" }.orEmpty()
    val filePart = if (progress.fileCount > 1) {
        " · ${progress.fileIndex} of ${progress.fileCount}"
    } else {
        ""
    }
    return "Uploading $sizePart$percentPart$filePart"
}

internal fun attachmentUploadFallbackLabel(count: Int): String =
    "Uploading $count attachment${if (count == 1) "" else "s"}..."

internal fun attachmentUploadBannerText(
    count: Int,
    progress: AttachmentTransferProgress?,
): String = if (progress != null) {
    formatAttachmentTransferLabel(progress)
} else {
    attachmentUploadFallbackLabel(count)
}

internal fun outboundUploadingLabel(progress: AttachmentTransferProgress?): String =
    if (progress != null) formatAttachmentTransferLabel(progress) else "Uploading attachments"

internal fun accessibilityProgressDescription(progress: AttachmentTransferProgress): String {
    val percent = progress.percent
    return if (percent != null && isAccessibilityMilestone(percent)) {
        "Uploading $percent percent"
    } else if (progress.fileCount > 1) {
        "Uploading file ${progress.fileIndex} of ${progress.fileCount}"
    } else {
        "Uploading"
    }
}
