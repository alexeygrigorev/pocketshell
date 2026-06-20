package com.pocketshell.app.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresPermission
import androidx.annotation.VisibleForTesting
import com.pocketshell.app.composer.PromptComposerViewModel

/**
 * Prompt-composer speech provider backed by Android's system recognizer.
 *
 * Availability and behavior are device/service dependent. On most Google Play
 * devices this delegates to Google Speech Services; other devices may provide a
 * different recognizer or none at all. The recognizer service decides whether
 * recognition is local or network-backed for the selected language.
 *
 * ## "Stops too quickly" tuning (issue #590)
 *
 * The Android `SpeechRecognizer` ends a recognition turn as soon as its
 * endpointer thinks the speaker has finished — by default after a fairly short
 * pause (some OEMs end after ~1s of silence). For dictation that produces the
 * symptom the maintainer reported: a natural mid-sentence pause cuts the user
 * off. We address that on two levels:
 *
 *  1. **Endpointer extras.** We set generous
 *     `EXTRA_SPEECH_INPUT_*_SILENCE_LENGTH_MILLIS` / `MINIMUM_LENGTH` extras on
 *     the intent so the system endpointer waits longer before declaring the
 *     turn complete (see [buildRecognizerIntent] / [silenceLengthMillis]). These
 *     extras are *advisory* — Google's recognizer and most AOSP recognizers
 *     honour them, but some OEM recognizers ignore them entirely.
 *
 *  2. **Auto-restart across turns.** Because (1) is advisory, the [Session]
 *     does NOT treat a single end-of-speech / no-match / speech-timeout as the
 *     end of dictation. While the user is still in record mode (they have not
 *     tapped stop / cancel), the session restarts the recognizer and keeps
 *     accumulating text. Only an explicit [SpeechRecognitionSession.stopListening]
 *     (commit the final transcript) or a hard error (network / permission /
 *     busy / client) ends a dictation. This makes the provider robust even on
 *     recognizers that ignore the silence extras — a pause never terminates
 *     dictation, it just rolls into the next turn.
 */
internal class AndroidSpeechRecognitionProvider(
    context: Context,
    private val recognizerFactory: RecognizerFactory = RecognizerFactory { appCtx ->
        SpeechRecognizer.createSpeechRecognizer(appCtx)
    },
    private val silenceLengthMs: Long = DEFAULT_COMPLETE_SILENCE_MS,
    private val possiblyCompleteSilenceLengthMs: Long = DEFAULT_POSSIBLY_COMPLETE_SILENCE_MS,
    private val minimumLengthMs: Long = DEFAULT_MINIMUM_LENGTH_MS,
) : PromptComposerViewModel.SpeechRecognitionProvider {

    private val appContext = context.applicationContext

    /** Seam so unit tests can supply a fake recognizer (the real one needs a device). */
    internal fun interface RecognizerFactory {
        fun create(context: Context): SpeechRecognizer
    }

    override fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(appContext)

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun start(
        language: String?,
        listener: PromptComposerViewModel.SpeechRecognitionListener,
    ): PromptComposerViewModel.SpeechRecognitionSession? {
        if (!isAvailable()) return null

        val intent = buildRecognizerIntent(language)
        val session = Session(
            appContext = appContext,
            recognizerFactory = recognizerFactory,
            intent = intent,
            listener = listener,
        )
        return if (session.startFirstTurn()) session else null
    }

    /**
     * Build the recognition intent with the generous endpointer extras that
     * keep dictation from stopping after a brief pause (issue #590).
     *
     * Visible for testing so the silence extras can be asserted without a
     * device — the values here are the load-bearing fix.
     */
    @VisibleForTesting
    internal fun buildRecognizerIntent(language: String?): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            // The endpointer-silence extras are the primary "doesn't stop too
            // quickly" fix. They are advisory (see class kdoc); the Session's
            // auto-restart is the belt-and-braces backstop for recognizers that
            // ignore them.
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                silenceLengthMs,
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                possiblyCompleteSilenceLengthMs,
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                minimumLengthMs,
            )
            if (!language.isNullOrBlank()) {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            }
        }

    /**
     * A dictation session. Owns the recognizer and survives recognizer-side
     * turn endings (end-of-speech / no-match / speech-timeout) by restarting
     * and accumulating committed text, so a pause never ends dictation. The
     * user ends dictation explicitly via [stopListening] (commit) or [cancel]
     * (discard); a hard error ends it too.
     */
    @VisibleForTesting
    internal class Session(
        private val appContext: Context,
        private val recognizerFactory: RecognizerFactory,
        private val intent: Intent,
        private val listener: PromptComposerViewModel.SpeechRecognitionListener,
    ) : PromptComposerViewModel.SpeechRecognitionSession {

        private var recognizer: SpeechRecognizer? = null

        /**
         * Text committed by previous turns in this dictation, joined with a
         * single space. Each restart's partial/final results are appended onto
         * this so the composer sees one continuous transcript across pauses.
         */
        private var committedText: String = ""

        /** Best partial seen in the current (not-yet-committed) turn. */
        private var currentTurnText: String = ""

        /** Set once the user explicitly stops/cancels — suppresses restarts. */
        private var userEnded: Boolean = false

        /** True while [stopListening] is waiting for the final results. */
        private var awaitingFinal: Boolean = false

        @RequiresPermission(Manifest.permission.RECORD_AUDIO)
        fun startFirstTurn(): Boolean = startTurn()

        @RequiresPermission(Manifest.permission.RECORD_AUDIO)
        private fun startTurn(): Boolean {
            val recognizer = try {
                recognizerFactory.create(appContext)
            } catch (_: Throwable) {
                return false
            }
            recognizer.setRecognitionListener(TurnListener())
            this.recognizer = recognizer
            currentTurnText = ""
            return try {
                recognizer.startListening(intent)
                true
            } catch (_: Throwable) {
                runCatching { recognizer.destroy() }
                this.recognizer = null
                false
            }
        }

        private fun destroyRecognizer() {
            recognizer?.let { r ->
                runCatching { r.destroy() }
            }
            recognizer = null
        }

        /** Combine committed turns with the current turn into one transcript. */
        private fun combinedTranscript(currentTurn: String = currentTurnText): String =
            listOf(committedText, currentTurn)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .trim()

        override fun stopListening() {
            // User asked to finish dictation. Ask the recognizer to wrap up the
            // current turn; the final results (or its terminal error) commit the
            // transcript. Suppress any further auto-restart.
            userEnded = true
            awaitingFinal = true
            val r = recognizer
            if (r == null) {
                emitFinalOrError()
                return
            }
            runCatching { r.stopListening() }.onFailure {
                emitFinalOrError()
            }
        }

        override fun cancel() {
            userEnded = true
            awaitingFinal = false
            recognizer?.let { runCatching { it.cancel() } }
            destroyRecognizer()
        }

        /** Commit the accumulated transcript, or surface "no text" if empty. */
        private fun emitFinalOrError() {
            destroyRecognizer()
            val text = combinedTranscript()
            if (text.isBlank()) {
                listener.onError(PromptComposerViewModel.ANDROID_SPEECH_NO_TEXT_MESSAGE)
            } else {
                listener.onFinal(text)
            }
        }

        /**
         * Restart the recognizer for the next turn after a recognizer-side turn
         * end while the user is still dictating. If the restart fails we commit
         * whatever we have rather than silently dropping the session.
         */
        @RequiresPermission(Manifest.permission.RECORD_AUDIO)
        private fun restartForNextTurn() {
            destroyRecognizer()
            if (!startTurn()) {
                // Could not restart — commit what we have so the user keeps their
                // text instead of losing the dictation.
                userEnded = true
                emitFinalOrError()
            }
        }

        private inner class TurnListener : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onPartialResults(partialResults: Bundle?) {
                bestResult(partialResults)?.let {
                    currentTurnText = it
                    listener.onPartial(combinedTranscript(it))
                }
            }

            @RequiresPermission(Manifest.permission.RECORD_AUDIO)
            override fun onResults(results: Bundle?) {
                val turnText = bestResult(results)
                    ?: currentTurnText.takeIf { it.isNotBlank() }
                if (!turnText.isNullOrBlank()) {
                    committedText = listOf(committedText, turnText)
                        .filter { it.isNotBlank() }
                        .joinToString(" ")
                        .trim()
                    currentTurnText = ""
                }
                if (userEnded) {
                    // The user already asked to finish — commit and stop.
                    emitFinalOrError()
                    return
                }
                // Recognizer ended a turn on its own (a pause). Keep dictating:
                // restart so the next phrase appends rather than terminating.
                restartForNextTurn()
            }

            @RequiresPermission(Manifest.permission.RECORD_AUDIO)
            override fun onError(error: Int) {
                // Fold the current turn's best partial into committed text so a
                // turn-ending error never drops the last phrase.
                if (currentTurnText.isNotBlank()) {
                    committedText = listOf(committedText, currentTurnText)
                        .filter { it.isNotBlank() }
                        .joinToString(" ")
                        .trim()
                    currentTurnText = ""
                }

                if (userEnded) {
                    emitFinalOrError()
                    return
                }

                if (error.isTurnEndError()) {
                    // A silence/no-match turn end while still dictating — this is
                    // exactly the "stops too quickly" case. Restart instead of
                    // ending so a pause rolls into the next turn.
                    restartForNextTurn()
                    return
                }

                // Hard error — end dictation and surface a message.
                destroyRecognizer()
                listener.onError(errorMessage(error))
            }

            private fun bestResult(bundle: Bundle?): String? {
                val matches = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                return matches?.firstOrNull { it.isNotBlank() }
            }
        }

        private fun errorMessage(error: Int): String = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                PromptComposerViewModel.ANDROID_SPEECH_NO_TEXT_MESSAGE
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                "Android speech recognizer network error. Try again or choose Whisper in settings."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                "Microphone permission denied. Grant it in system settings to use voice input."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                "Android speech recognizer is busy. Try again in a moment."
            SpeechRecognizer.ERROR_CLIENT,
            SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_AUDIO ->
                PromptComposerViewModel.ANDROID_SPEECH_FAILED_MESSAGE
            else -> PromptComposerViewModel.ANDROID_SPEECH_FAILED_MESSAGE
        }

        /**
         * Errors that mean "this recognition turn ended" rather than "dictation
         * has failed". Restart on these while the user is still in record mode.
         */
        private fun Int.isTurnEndError(): Boolean =
            this == SpeechRecognizer.ERROR_NO_MATCH ||
                this == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
    }

    companion object {
        /**
         * Complete-silence window. The endpointer waits this long after the
         * speaker stops before declaring the turn finished. 2s comfortably
         * covers a natural mid-sentence pause (the system default is often
         * ~700ms–1s, which is what cut the maintainer off).
         */
        const val DEFAULT_COMPLETE_SILENCE_MS: Long = 2_000L

        /**
         * Possibly-complete-silence window. Mirrors the complete-silence value
         * so the recognizer doesn't speculatively finalize early on a pause.
         */
        const val DEFAULT_POSSIBLY_COMPLETE_SILENCE_MS: Long = 2_000L

        /**
         * Minimum recognition length — keeps the recognizer listening for at
         * least this long before any endpointer decision so very short / slow
         * starts aren't clipped.
         */
        const val DEFAULT_MINIMUM_LENGTH_MS: Long = 2_000L
    }
}
