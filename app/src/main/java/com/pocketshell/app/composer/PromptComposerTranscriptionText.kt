package com.pocketshell.app.composer

import com.pocketshell.app.composer.PromptComposerViewModel.UiState
import com.pocketshell.core.voice.WhisperException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Issue #211 helper: apply [transform] to the current draft, update the
 * composer state, and return the new draft text so send-after-transcribe can
 * dispatch the exact combined prompt without a second StateFlow read.
 */
internal inline fun MutableStateFlow<UiState>.updateAndReturnDraft(
    transform: (String) -> String,
): String {
    var newDraft = ""
    update {
        newDraft = transform(it.draft)
        it.copy(
            recording = PromptComposerViewModel.RecordingState.Idle,
            draft = newDraft,
            error = null,
        )
    }
    return newDraft
}

/**
 * Map a Whisper exception onto the message shown in the composer.
 */
internal fun userFacingWhisperError(t: Throwable): String = when (t) {
    is WhisperException.Auth -> "API key was rejected. Check your OpenAI key in settings."
    is WhisperException.RateLimited -> "Rate limited by OpenAI. Try again in a moment."
    is WhisperException.Server -> "OpenAI server error. Try again."
    is WhisperException.Transport -> "Network error: ${t.message}"
    is WhisperException.Parse -> "Unexpected response from Whisper."
    else -> t.message ?: "Transcription failed"
}

internal fun appendTranscript(draft: String, transcript: String): String {
    if (transcript.isBlank()) return draft
    val sep = if (draft.isEmpty() || draft.endsWith(" ")) "" else " "
    return draft + sep + transcript
}
