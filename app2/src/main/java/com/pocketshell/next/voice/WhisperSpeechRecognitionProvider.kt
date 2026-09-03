package com.pocketshell.next.voice

import com.pocketshell.core.storage.entity.PendingTranscriptionEntity
import com.pocketshell.core.voice.AudioRecorderException
import com.pocketshell.core.voice.SpeechAudioGuard
import com.pocketshell.core.voice.WhisperException
import com.pocketshell.next.composer.SpeechRecognitionListener
import com.pocketshell.next.composer.SpeechRecognitionProvider
import com.pocketshell.next.composer.SpeechRecognitionSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The OpenAI-Whisper dictation arm (rewrite task P-2), behind P-1's
 * [SpeechRecognitionProvider] seam.
 *
 * ## What it is a port of
 *
 * The old client had no Whisper *provider*: the record → guard → queue →
 * transcribe → deliver sequence was ~350 lines inlined in a 3,585-line composer
 * ViewModel, and the inline-dictation screen had a second copy of it. This is
 * that one sequence, unchanged in behaviour and in every user-facing string,
 * expressed against the seam so BOTH callers are the same three-method
 * interface the Android recognizer already implements. Where the old code had a
 * branch it kept a branch; where it had a message it kept the message.
 *
 * ## Persist-before-Whisper (the subway case)
 *
 * The audio is written to [PendingTranscriptionStore] BEFORE the network call,
 * every time, not only when offline. That ordering is the whole feature: a
 * process kill, a tunnel, or a dropped connection mid-upload cannot lose a
 * recording, because the WAV is already on disk with a row pointing at it. On
 * success the row is deleted; on failure its retry counter is bumped and the
 * message stamped, so the user gets an honest "still here, tap to retry".
 *
 * When the device is KNOWN offline at stop time the network call is skipped
 * entirely — no 60-second OkHttp timeout to sit through — and the row is
 * stamped with [PendingTranscriptionItem.NETWORK_WAITING_MESSAGE].
 * [PendingTranscriptionDelivery] is what picks those rows up again.
 *
 * ## Why the listener contract still holds
 *
 * [SpeechRecognitionListener] promises main-thread callbacks, so every
 * callback here is dispatched from [scope], which production builds on the
 * main dispatcher. There are no partials: Whisper transcribes a finished
 * recording, so a dictation goes Idle → Recording → Transcribing → one
 * terminal callback.
 */
class WhisperSpeechRecognitionProvider(
    private val mic: MicCapture,
    private val whisper: WhisperClientFactory,
    private val pending: PendingTranscriptionStore,
    private val connectivity: ConnectivityProbe,
    private val scope: CoroutineScope,
    private val languageHint: () -> String? = { null },
) : SpeechRecognitionProvider {

    /**
     * Whisper is usable only with a stored API key. Reported as
     * "unavailable" rather than as an error on tap: a mic that is visibly
     * disabled is a truthful "this is not set up", whereas one that accepts a
     * tap and then complains costs the user a recording to find out.
     */
    override fun isAvailable(): Boolean = whisper.create() != null

    override fun start(
        language: String?,
        listener: SpeechRecognitionListener,
    ): SpeechRecognitionSession? {
        if (whisper.create() == null) return null
        try {
            mic.start()
        } catch (_: AudioRecorderException) {
            return null
        }
        return Session(language ?: languageHint(), listener)
    }

    private inner class Session(
        private val language: String?,
        private val listener: SpeechRecognitionListener,
    ) : SpeechRecognitionSession {

        /**
         * Guards against a late second terminal callback. The delegate above
         * already fences on its own generation, but a provider that emitted
         * twice would still be lying about its contract.
         */
        private var finished = false

        override fun stopListening() {
            if (finished) return
            finished = true
            val audio = runCatching { mic.stop() }.getOrElse {
                listener.onError(MIC_FAILED_MESSAGE)
                return
            }
            scope.launch { transcribe(audio) }
        }

        override fun cancel() {
            if (finished) return
            finished = true
            // Stop the microphone and throw the bytes away: an abandoned
            // recording is never uploaded, so it costs nothing and queues
            // nothing.
            runCatching { mic.stop() }
        }

        private suspend fun transcribe(audio: ByteArray) {
            val client = whisper.create() ?: run {
                listener.onError(NO_API_KEY_MESSAGE)
                return
            }
            if (audio.isEmpty() || !SpeechAudioGuard.hasSpeechEnergy(audio)) {
                // The local guard catches a mic that heard nothing before it
                // costs an API call — and before it queues a WAV of silence
                // the user would later be asked to retry.
                listener.onError(NO_SPEECH_DETECTED_MESSAGE)
                return
            }

            val offline = !connectivity.refresh()
            val queued = runCatching {
                pending.enqueueAudio(
                    audio = audio,
                    destinationContext = PendingTranscriptionEntity.DESTINATION_COMPOSER,
                    initialError = if (offline) {
                        PendingTranscriptionItem.NETWORK_WAITING_MESSAGE
                    } else {
                        null
                    },
                )
            }.getOrNull()

            if (offline) {
                // Nothing to send it to. The WAV is on disk (unless it blew the
                // size cap, in which case `queued` is null and the message says
                // so), and the user gets their composer back immediately.
                listener.onError(
                    if (queued != null) {
                        PendingTranscriptionItem.NETWORK_WAITING_MESSAGE
                    } else {
                        OFFLINE_NOT_QUEUED_MESSAGE
                    },
                )
                return
            }

            client.transcribe(audio, language).fold(
                onSuccess = { raw ->
                    val text = raw.trim()
                    when {
                        text.isEmpty() -> failAndKeep(queued?.id, EMPTY_TRANSCRIPTION_RETRY_MESSAGE)
                        SpeechAudioGuard.isLikelyHallucination(text) ->
                            failAndKeep(queued?.id, NO_SPEECH_DETECTED_MESSAGE)

                        else -> {
                            queued?.id?.let { runCatching { pending.markSucceeded(it) } }
                            listener.onFinal(text)
                        }
                    }
                },
                onFailure = { failure ->
                    failAndKeep(queued?.id, userFacingWhisperError(failure))
                },
            )
        }

        /** A failed round trip keeps the audio queued and says why. */
        private suspend fun failAndKeep(id: String?, message: String) {
            id?.let { runCatching { pending.markFailure(it, message) } }
            listener.onError(message)
        }
    }

    companion object {

        const val NO_API_KEY_MESSAGE: String =
            "No OpenAI API key saved. Add one in settings to use Whisper dictation."

        const val NO_SPEECH_DETECTED_MESSAGE: String =
            "No speech detected — nothing to send. Tap the mic and speak."

        const val EMPTY_TRANSCRIPTION_RETRY_MESSAGE: String =
            "Whisper returned no text. Recording saved for retry."

        const val MIC_FAILED_MESSAGE: String = "The microphone stopped unexpectedly."

        /** Offline AND the recording could not be queued (size cap / disk failure). */
        const val OFFLINE_NOT_QUEUED_MESSAGE: String =
            "You're offline and the recording was too large to save. Try a shorter one."

        /**
         * Whisper failure → the sentence the user reads. Ported verbatim from
         * the old client's `userFacingWhisperError`.
         */
        fun userFacingWhisperError(t: Throwable): String = when (t) {
            is WhisperException.Auth -> "API key was rejected. Check your OpenAI key in settings."
            is WhisperException.RateLimited -> "Rate limited by OpenAI. Try again in a moment."
            is WhisperException.Server -> "OpenAI server error. Try again."
            is WhisperException.Transport -> "Network error: ${t.message}"
            is WhisperException.Parse -> "Unexpected response from Whisper."
            else -> t.message ?: "Transcription failed"
        }
    }
}
