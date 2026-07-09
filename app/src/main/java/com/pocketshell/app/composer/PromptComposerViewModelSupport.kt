package com.pocketshell.app.composer

import com.pocketshell.app.voice.PendingTranscriptionItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

internal object UnavailableSpeechRecognitionProvider :
    PromptComposerViewModel.SpeechRecognitionProvider {
    override fun isAvailable(): Boolean = false

    override fun start(
        language: String?,
        listener: PromptComposerViewModel.SpeechRecognitionListener,
    ): PromptComposerViewModel.SpeechRecognitionSession? = null
}

/**
 * Issue #453: format an elapsed-recording duration (milliseconds) as a
 * zero-padded `mm:ss` timer. Minutes are not capped at 99.
 */
internal fun formatElapsed(elapsedMs: Long): String {
    val totalSeconds = (elapsedMs.coerceAtLeast(0L)) / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%02d:%02d".format(minutes, seconds)
}

internal fun appendAttachmentPaths(draft: String, paths: List<String>): String {
    if (paths.isEmpty()) return draft
    val block = buildString {
        append("Attached files:")
        paths.forEach { path ->
            append('\n')
            append("- ")
            append(path)
        }
    }
    return when {
        draft.isBlank() -> block
        draft.endsWith("\n\n") -> draft + block
        draft.endsWith("\n") -> draft + "\n" + block
        else -> draft + "\n\n" + block
    }
}

/**
 * Issue #544/#566: derive the short tile label from a staged remote path.
 */
internal fun attachmentDisplayName(remotePath: String): String {
    val trimmed = remotePath.trimEnd('/')
    val segment = trimmed.substringAfterLast('/')
    return segment.ifBlank { remotePath }
}

/**
 * Issue #180: no-op [PromptComposerViewModel.PendingTranscriptionQueue]
 * implementation used when the ViewModel is constructed without the real store.
 */
public object DisabledPendingTranscriptionQueue : PromptComposerViewModel.PendingTranscriptionQueue {
    override val items: Flow<List<PendingTranscriptionItem>> = flowOf(emptyList())

    override suspend fun enqueueAudio(
        audio: ByteArray,
        destinationContext: String,
        initialError: String?,
    ): PendingTranscriptionItem? = null

    override suspend fun snapshot(): List<PendingTranscriptionItem> = emptyList()
    override suspend fun loadAudio(id: String): ByteArray? = null
    override suspend fun markSucceeded(id: String) = Unit
    override suspend fun markFailure(id: String, errorMessage: String): PendingTranscriptionItem? = null
    override suspend fun discard(id: String) = Unit
    override suspend fun saveAsAudioFile(id: String): String? = null
    override suspend fun reconcile() = Unit
}

/**
 * Issue #180: always-online stub for [PromptComposerViewModel.ConnectivityProbe].
 */
public object AlwaysOnlineConnectivityProbe : PromptComposerViewModel.ConnectivityProbe {
    override fun refresh(): Boolean = true
}
