package com.pocketshell.next.voice

import com.pocketshell.core.voice.SpeechAudioGuard
import com.pocketshell.next.voice.WhisperSpeechRecognitionProvider.Companion.EMPTY_TRANSCRIPTION_RETRY_MESSAGE
import com.pocketshell.next.voice.WhisperSpeechRecognitionProvider.Companion.NO_SPEECH_DETECTED_MESSAGE
import com.pocketshell.next.voice.WhisperSpeechRecognitionProvider.Companion.userFacingWhisperError

/**
 * The other half of the subway case (rewrite task P-2): the dictation you
 * recorded with no signal, transcribed and handed back once there is signal.
 *
 * [WhisperSpeechRecognitionProvider] parks the WAV in
 * [PendingTranscriptionStore] when the device is offline at stop time. This
 * class is what drains that queue — the composer calls [deliverQueued] when it
 * comes back to the foreground, and every transcript it returns is appended to
 * the draft exactly as a live dictation would have been.
 *
 * ## D21: foreground only, no scheduler
 *
 * There is no WorkManager, no alarm and no connectivity callback. The trigger
 * is the composer becoming visible again, which is also the only moment the
 * result has somewhere to go. A phone that never opens the app keeps its
 * recordings on disk, which is the correct outcome, not a missed deadline.
 *
 * ## Only "queued offline" rows are auto-retried
 *
 * A row that already failed a Whisper round trip
 * ([PendingTranscriptionItem.retryCount] > 0) is NOT retried automatically —
 * that is the old client's rule and the reason for it stands: a permanently
 * un-transcribable buffer would otherwise burn API quota on every foreground.
 * Those rows wait for [retry], which a surface can offer per row.
 */
class PendingTranscriptionDelivery(
    private val store: PendingTranscriptionStore,
    private val whisper: WhisperClientFactory,
    private val connectivity: ConnectivityProbe,
    private val languageHint: () -> String? = { null },
) {

    /**
     * Transcribes every recording that was queued while offline and returns the
     * transcripts, oldest recording first.
     *
     * Returns empty — without touching the network — when the device is still
     * offline, when no key is stored, or when the queue holds nothing that was
     * queued offline. A row that transcribes successfully is deleted (audio
     * included); a row that fails keeps its audio and gets its failure message
     * stamped, so nothing is lost by trying.
     */
    suspend fun deliverQueued(): List<String> {
        if (!connectivity.refresh()) return emptyList()
        val queued = runCatching { store.snapshot() }.getOrElse { return emptyList() }
            .filter { it.isWaitingForNetwork && !it.atRetryCap }
            .sortedBy { it.recordingTimestampMs }
        if (queued.isEmpty()) return emptyList()

        return queued.mapNotNull { item -> retry(item.id) }
    }

    /**
     * Re-runs ONE queued recording through Whisper. Returns its transcript, or
     * `null` when it could not be produced (offline, no key, orphaned row,
     * empty result, or a Whisper failure — the row survives in every one of
     * those cases except the orphan, which is swept).
     */
    suspend fun retry(id: String): String? {
        val client = whisper.create() ?: return null
        if (!connectivity.refresh()) return null

        val audio = runCatching { store.loadAudio(id) }.getOrNull()
        if (audio == null) {
            // Row without a file: the recording is genuinely gone, so leaving
            // the row would show a queue entry that can never resolve.
            runCatching { store.markSucceeded(id) }
            return null
        }

        return client.transcribe(audio, languageHint()).fold(
            onSuccess = { raw ->
                val text = raw.trim()
                when {
                    text.isEmpty() -> {
                        runCatching { store.markFailure(id, EMPTY_TRANSCRIPTION_RETRY_MESSAGE) }
                        null
                    }

                    SpeechAudioGuard.isLikelyHallucination(text) -> {
                        runCatching { store.markFailure(id, NO_SPEECH_DETECTED_MESSAGE) }
                        null
                    }

                    else -> {
                        // Re-check before handing the text over: a parallel
                        // drain (or a user discard) may have resolved this row
                        // while the round trip was in flight, and delivering
                        // twice is how the old client duplicated a dictated
                        // paragraph into the draft.
                        val stillQueued = runCatching { store.snapshot() }
                            .getOrDefault(emptyList())
                            .any { it.id == id }
                        if (!stillQueued) return@fold null
                        runCatching { store.markSucceeded(id) }
                        text
                    }
                }
            },
            onFailure = { failure ->
                runCatching { store.markFailure(id, userFacingWhisperError(failure)) }
                null
            },
        )
    }
}
