package com.pocketshell.app.tmux

import com.pocketshell.app.composer.LocalAttachmentSidecarRef
import com.pocketshell.core.ssh.QueueSidecarResumableUploadRequest
import com.pocketshell.core.ssh.QueueSidecarResumableUploadResult
import com.pocketshell.core.ssh.QueueSidecarUploadProgress
import com.pocketshell.core.ssh.SshSession
import java.io.File

internal suspend fun SshSession.uploadQueuedSidecar(
    ref: LocalAttachmentSidecarRef,
    remotePath: String,
): QueueSidecarResumableUploadResult =
    uploadQueueSidecar(
        QueueSidecarResumableUploadRequest(
            localFile = File(ref.localPath),
            remotePath = remotePath,
            stableToken = ref.id,
            expectedBytes = ref.byteSize,
            displayName = ref.displayName,
        ),
        onProgress = { progress ->
            QueueSidecarUploadJourneySeam.onProgress?.invoke(ref, progress)
        },
    ).also { result ->
        QueueSidecarUploadJourneySeam.onResult?.invoke(ref, result)
    }

/**
 * Instrumentation-only synchronization seam for issue #1733's genuine wire cut.
 * Production leaves [onProgress] null.
 */
internal object QueueSidecarUploadJourneySeam {
    @Volatile
    var onProgress: ((LocalAttachmentSidecarRef, QueueSidecarUploadProgress) -> Unit)? = null

    @Volatile
    var onResult: ((LocalAttachmentSidecarRef, QueueSidecarResumableUploadResult) -> Unit)? = null

    fun reset() {
        onProgress = null
        onResult = null
    }
}
