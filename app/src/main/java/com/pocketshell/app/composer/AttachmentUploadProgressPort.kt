package com.pocketshell.app.composer

import com.pocketshell.core.ssh.QueueSidecarUploadProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

internal data class AttachmentUploadProgressKey(
    val sessionKey: String,
    val rowId: String,
)

/**
 * Ephemeral attach-time + send-time upload progress (issue #1563).
 *
 * Durable queue state remains `Uploading`; byte ticks are live operational
 * state keyed by `(sessionKey, rowId)` and are never written to
 * SharedPreferences. A late callback from a cancelled/superseded attempt
 * generation is ignored.
 */
internal object AttachmentUploadProgressPort {
    private val lock = Any()
    private val attachGeneration = AtomicLong(0L)
    private var attachAggregator: AttachmentTransferAggregator? = null

    private val _attachProgress = MutableStateFlow<AttachmentTransferProgress?>(null)
    val attachProgress: StateFlow<AttachmentTransferProgress?> = _attachProgress.asStateFlow()

    private val queueGenerations = mutableMapOf<AttachmentUploadProgressKey, Long>()
    private val queueAggregators = mutableMapOf<AttachmentUploadProgressKey, AttachmentTransferAggregator>()
    private val queueFileIds = mutableMapOf<AttachmentUploadProgressKey, List<String>>()

    private val _queueProgress =
        MutableStateFlow<Map<AttachmentUploadProgressKey, AttachmentTransferProgress>>(emptyMap())
    val queueProgress: StateFlow<Map<AttachmentUploadProgressKey, AttachmentTransferProgress>> =
        _queueProgress.asStateFlow()

    @Volatile
    var nowMillis: () -> Long = { System.currentTimeMillis() }

    fun resetForTest() {
        synchronized(lock) {
            nowMillis = { System.currentTimeMillis() }
            attachAggregator = null
            _attachProgress.value = null
            queueGenerations.clear()
            queueAggregators.clear()
            queueFileIds.clear()
            _queueProgress.value = emptyMap()
        }
    }

    fun beginAttach(files: List<AttachmentFileSpec>): Long {
        val aggregator = AttachmentTransferAggregator(files, nowMillis = { nowMillis() })
        val generation = attachGeneration.incrementAndGet()
        synchronized(lock) {
            attachAggregator = aggregator
            _attachProgress.value = aggregator.beginAttempt(generation)
        }
        return generation
    }

    fun onAttachFileProgress(
        fileIndex: Int,
        bytesTransferred: Long,
        totalBytes: Long,
        force: Boolean = false,
    ) {
        val published = synchronized(lock) {
            val aggregator = attachAggregator ?: return
            aggregator.onFileProgress(
                generation = aggregator.currentGeneration,
                fileIndex = fileIndex,
                bytesTransferred = bytesTransferred,
                totalBytes = totalBytes,
                force = force,
            )
        }
        if (published != null) _attachProgress.value = published
    }

    fun completeAttachFile(fileIndex: Int) {
        val published = synchronized(lock) {
            val aggregator = attachAggregator ?: return
            aggregator.completeFile(aggregator.currentGeneration, fileIndex)
        }
        if (published != null) _attachProgress.value = published
    }

    fun endAttach() {
        synchronized(lock) {
            attachAggregator = null
            _attachProgress.value = null
        }
    }

    fun beginQueueUpload(
        sessionKey: String,
        rowId: String,
        files: List<AttachmentFileSpec>,
    ): Long {
        val key = AttachmentUploadProgressKey(sessionKey, rowId)
        val aggregator = AttachmentTransferAggregator(files, nowMillis = { nowMillis() })
        val generation: Long
        val start: AttachmentTransferProgress
        synchronized(lock) {
            generation = (queueGenerations[key] ?: 0L) + 1L
            queueGenerations[key] = generation
            queueAggregators[key] = aggregator
            queueFileIds[key] = files.map { spec -> spec.id }
            start = aggregator.beginAttempt(generation)
        }
        _queueProgress.update { current -> current + (key to start) }
        return generation
    }

    fun onSidecarProgress(ref: LocalAttachmentSidecarRef, progress: QueueSidecarUploadProgress) {
        val published: Pair<AttachmentUploadProgressKey, AttachmentTransferProgress>
        synchronized(lock) {
            val key = queueAggregators.keys.firstOrNull { candidate ->
                candidate.rowId == ref.outboundItemId
            } ?: return
            val aggregator = queueAggregators[key] ?: return
            val ids = queueFileIds[key] ?: return
            val index = ids.indexOf(ref.id)
            if (index < 0) return
            val next = aggregator.onFileProgress(
                generation = aggregator.currentGeneration,
                fileIndex = index,
                bytesTransferred = progress.bytesTransferred,
                totalBytes = progress.totalBytes,
            ) ?: return
            published = key to next
        }
        _queueProgress.update { current -> current + published }
    }

    fun progressFor(sessionKey: String, rowId: String): AttachmentTransferProgress? =
        _queueProgress.value[AttachmentUploadProgressKey(sessionKey, rowId)]

    fun endQueueUpload(sessionKey: String, rowId: String, generation: Long? = null) {
        val key = AttachmentUploadProgressKey(sessionKey, rowId)
        synchronized(lock) {
            if (generation != null && queueGenerations[key] != generation) return
            queueAggregators.remove(key)
            queueFileIds.remove(key)
        }
        _queueProgress.update { current -> current - key }
    }
}

internal suspend fun withQueuedSidecarUploadProgress(
    sessionKey: String,
    refs: List<LocalAttachmentSidecarRef>,
    upload: suspend (List<LocalAttachmentSidecarRef>) -> Result<List<String>>,
): Result<List<String>> {
    if (refs.isEmpty()) return upload(refs)
    val rowId = refs.first().outboundItemId
    val files = refs.map { ref ->
        AttachmentFileSpec(
            id = ref.id,
            fileName = ref.displayName,
            byteSize = ref.byteSize,
        )
    }
    val generation = AttachmentUploadProgressPort.beginQueueUpload(sessionKey, rowId, files)
    return try {
        upload(refs)
    } finally {
        AttachmentUploadProgressPort.endQueueUpload(sessionKey, rowId, generation)
    }
}
