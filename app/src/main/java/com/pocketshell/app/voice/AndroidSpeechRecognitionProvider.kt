package com.pocketshell.app.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
 * ## "Stops too quickly" — explicit-stop-only dictation (issues #590, #884)
 *
 * The maintainer's primary input method is the FREE Android `SpeechRecognizer`,
 * and the #1 complaint is that it cuts them off mid-thought. The Android
 * recognizer ends a recognition *turn* whenever its endpointer thinks the
 * speaker has finished (some OEMs after ~700ms of silence) or when any of a
 * dozen transient conditions fire (no-match, speech-timeout, recognizer-busy,
 * server hiccup, client churn...). On their own each of these would end
 * dictation. We make dictation **explicit-stop-only** — it runs until the user
 * taps stop — on three compounding levels:
 *
 *  1. **Endpointer extras (advisory).** We set generous
 *     `EXTRA_SPEECH_INPUT_*_SILENCE_LENGTH_MILLIS` / `MINIMUM_LENGTH` extras on
 *     the intent so a recognizer that honours them waits longer before
 *     declaring a turn complete (see [buildRecognizerIntent] /
 *     [silenceWindowMs]). The window is tunable from Settings → Voice and
 *     defaults to [DEFAULT_COMPLETE_SILENCE_MS]. These extras are *advisory* —
 *     many OEM recognizers ignore them entirely, which is why they are only the
 *     first line of defence.
 *
 *  2. **Exhaustive auto-restart across turns.** Because (1) is advisory, the
 *     [Session] treats EVERY recognizer-side turn end — end-of-speech, results,
 *     and every transient error (no-match / speech-timeout / busy / server /
 *     client / network / audio...) — as "the turn ended, keep going", NOT as
 *     "dictation finished". While the user is still in record mode (they have
 *     not tapped stop / cancel) the session restarts the recognizer and keeps
 *     accumulating text. A pause, a network blip, a busy recognizer: none of
 *     them ends dictation, they just roll into the next turn. Only an explicit
 *     [SpeechRecognitionSession.stopListening] / [cancel], or a genuinely fatal
 *     error (microphone permission denied), ends a dictation.
 *
 *  3. **Restart-storm backoff (safety valve).** A recognizer that fails its
 *     restart *instantly and repeatedly* (e.g. permanently misconfigured) would
 *     otherwise busy-loop. We count consecutive restarts that end almost
 *     immediately without capturing any speech; after [MAX_EMPTY_RESTART_BURST]
 *     of them we commit whatever text we have rather than spin forever. A turn
 *     that actually ran for a human-perceptible time, or produced any
 *     partial/final, resets the counter — so a normal long dictation with
 *     pauses never trips it.
 */
internal class AndroidSpeechRecognitionProvider(
    context: Context,
    private val recognizerFactory: RecognizerFactory = RecognizerFactory { appCtx ->
        SpeechRecognizer.createSpeechRecognizer(appCtx)
    },
    /**
     * Per-recording silence window (ms) the endpointer extras request. Read
     * lazily on each [start] so a Settings → Voice change takes effect on the
     * next mic tap. Defaults to [DEFAULT_COMPLETE_SILENCE_MS] when no settings
     * source is wired (unit tests / safety fallback).
     */
    private val silenceWindowMsProvider: () -> Long = { DEFAULT_COMPLETE_SILENCE_MS },
    private val minimumLengthMs: Long = DEFAULT_MINIMUM_LENGTH_MS,
) : PromptComposerViewModel.SpeechRecognitionProvider {

    private val appContext = context.applicationContext

    /** Seam so unit tests can supply a fake recognizer (the real one needs a device). */
    internal fun interface RecognizerFactory {
        fun create(context: Context): SpeechRecognizer
    }

    override fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(appContext)

    /**
     * Resolve the silence window for a recording, clamped to a sane floor so a
     * mis-stored / zero setting can never reintroduce the short-default cut-off.
     */
    private fun silenceWindowMs(): Long =
        silenceWindowMsProvider().coerceAtLeast(MIN_COMPLETE_SILENCE_MS)

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
     * keep dictation from stopping after a brief pause (issues #590, #884).
     *
     * Visible for testing so the silence extras can be asserted without a
     * device — the values here are the load-bearing advisory fix.
     */
    @VisibleForTesting
    internal fun buildRecognizerIntent(language: String?): Intent {
        val silenceMs = silenceWindowMs()
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            // The endpointer-silence extras are the advisory first line of the
            // "doesn't stop too quickly" fix. The Session's exhaustive
            // auto-restart is the belt-and-braces backstop for recognizers that
            // ignore them.
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                silenceMs,
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                silenceMs,
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                minimumLengthMs,
            )
            if (!language.isNullOrBlank()) {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            }
        }
    }

    /**
     * A dictation session. Owns the recognizer and survives EVERY
     * recognizer-side turn ending (end-of-speech / results / any transient
     * error) by restarting and accumulating committed text, so nothing short of
     * an explicit stop ends dictation. The user ends dictation via
     * [stopListening] (commit) or [cancel] (discard); only a genuinely fatal
     * error (mic permission denied) ends it otherwise.
     */
    @VisibleForTesting
    internal class Session(
        private val appContext: Context,
        private val recognizerFactory: RecognizerFactory,
        private val intent: Intent,
        private val listener: PromptComposerViewModel.SpeechRecognitionListener,
        /** Elapsed-time source; overridable so tests can simulate turn duration. */
        private val elapsedRealtimeMs: () -> Long = { SystemClock.elapsedRealtime() },
        /** Posts the restart back onto the main thread; overridable for tests. */
        private val mainHandler: Handler = Handler(Looper.getMainLooper()),
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

        /** Wall-clock at which the current turn's recognizer began listening. */
        private var currentTurnStartedAtMs: Long = 0L

        /** True once the current turn produced any partial/final text. */
        private var currentTurnHadSpeech: Boolean = false

        /**
         * Consecutive restarts that ended almost immediately with no captured
         * speech. Reset by any turn that ran long enough OR produced text.
         * Trips the [MAX_EMPTY_RESTART_BURST] safety valve.
         */
        private var emptyRestartBurst: Int = 0

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
            currentTurnHadSpeech = false
            currentTurnStartedAtMs = elapsedRealtimeMs()
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

        /** Fold the current turn's best partial into committed text. */
        private fun commitCurrentTurn(explicit: String? = null) {
            val turnText = explicit?.takeIf { it.isNotBlank() }
                ?: currentTurnText.takeIf { it.isNotBlank() }
            if (!turnText.isNullOrBlank()) {
                committedText = listOf(committedText, turnText)
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                    .trim()
                currentTurnText = ""
            }
        }

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
         * end while the user is still dictating. Tracks the restart-storm
         * backoff: if the turn just ended almost immediately without any speech,
         * count it; otherwise reset the counter. After [MAX_EMPTY_RESTART_BURST]
         * empty restarts in a row, commit what we have rather than spin forever.
         *
         * @param producedSpeech whether the ending turn produced any partial /
         *   final text (true resets the storm counter).
         */
        @RequiresPermission(Manifest.permission.RECORD_AUDIO)
        private fun restartForNextTurn(producedSpeech: Boolean) {
            val turnDurationMs = elapsedRealtimeMs() - currentTurnStartedAtMs
            if (producedSpeech || turnDurationMs >= MIN_PRODUCTIVE_TURN_MS) {
                // A real turn — reset the safety valve.
                emptyRestartBurst = 0
            } else {
                emptyRestartBurst++
            }

            if (emptyRestartBurst >= MAX_EMPTY_RESTART_BURST) {
                // The recognizer is failing instantly and repeatedly with no
                // audio — stop spinning and keep whatever the user managed.
                userEnded = true
                emitFinalOrError()
                return
            }

            destroyRecognizer()
            // Post the restart so the just-finished recognizer fully tears down
            // before the next createSpeechRecognizer; an immediate in-callback
            // restart can race the service unbind on some OEMs. The post is
            // imperceptible to the user — the accumulated transcript already
            // shows their text across the gap.
            mainHandler.post {
                if (userEnded) return@post
                if (!startTurn()) {
                    // Could not restart — commit what we have so the user keeps
                    // their text instead of losing the dictation.
                    userEnded = true
                    emitFinalOrError()
                }
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
                    currentTurnHadSpeech = true
                    listener.onPartial(combinedTranscript(it))
                }
            }

            @RequiresPermission(Manifest.permission.RECORD_AUDIO)
            override fun onResults(results: Bundle?) {
                val turnText = bestResult(results)
                if (!turnText.isNullOrBlank()) {
                    currentTurnHadSpeech = true
                }
                commitCurrentTurn(turnText)
                if (userEnded) {
                    // The user already asked to finish — commit and stop.
                    emitFinalOrError()
                    return
                }
                // Recognizer ended a turn on its own (a pause). Keep dictating:
                // restart so the next phrase appends rather than terminating.
                restartForNextTurn(producedSpeech = currentTurnHadSpeech)
            }

            @RequiresPermission(Manifest.permission.RECORD_AUDIO)
            override fun onError(error: Int) {
                // Fold the current turn's best partial into committed text so a
                // turn-ending error never drops the last phrase.
                val hadSpeech = currentTurnHadSpeech
                commitCurrentTurn()

                if (userEnded) {
                    emitFinalOrError()
                    return
                }

                if (error.isFatalError()) {
                    // The ONLY non-restartable cases: the user revoked the mic
                    // permission, or there is no recognition service at all.
                    // Restarting can't help — surface the message and stop.
                    destroyRecognizer()
                    listener.onError(errorMessage(error))
                    return
                }

                // EVERY other end-reason while the user is still dictating is a
                // transient turn end (silence, no-match, busy, server, client,
                // network, audio...). Restart so it rolls into the next turn
                // instead of ending dictation — the #884 "don't stop until I
                // stop" contract.
                restartForNextTurn(producedSpeech = hadSpeech)
            }

            private fun bestResult(bundle: Bundle?): String? {
                val matches = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                return matches?.firstOrNull { it.isNotBlank() }
            }
        }

        private fun errorMessage(error: Int): String = when (error) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                "Microphone permission denied. Grant it in system settings to use voice input."
            else -> PromptComposerViewModel.ANDROID_SPEECH_FAILED_MESSAGE
        }

        /**
         * The only errors that genuinely END dictation rather than just ending
         * the current turn. Everything else restarts while the user is still in
         * record mode — a transient recognizer hiccup must never cut the
         * maintainer off mid-thought (#884).
         *
         * - [SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS]: the mic is not
         *   ours to use; restarting will only re-fail. Surface and stop.
         */
        private fun Int.isFatalError(): Boolean =
            this == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
    }

    companion object {
        /**
         * Default complete-silence window. The endpointer waits this long after
         * the speaker stops before declaring the turn finished. #884 raised it
         * to 4s after 2s was empirically still too aggressive on the
         * maintainer's device — 4s comfortably covers a natural multi-second
         * thinking pause. The Settings → Voice slider can tune it; the
         * exhaustive auto-restart is the real backstop either way.
         */
        const val DEFAULT_COMPLETE_SILENCE_MS: Long = 4_000L

        /**
         * Floor the silence window is clamped to so a mis-stored / zero setting
         * can never reintroduce the short-system-default cut-off.
         */
        const val MIN_COMPLETE_SILENCE_MS: Long = 2_000L

        /**
         * Minimum recognition length — keeps the recognizer listening for at
         * least this long before any endpointer decision so very short / slow
         * starts aren't clipped.
         */
        const val DEFAULT_MINIMUM_LENGTH_MS: Long = 2_000L

        /**
         * A turn shorter than this that produced no speech counts toward the
         * restart-storm safety valve. Real turns (a pause after speech, or any
         * turn the user actually spoke into) run far longer, so a normal long
         * dictation never trips the valve.
         */
        const val MIN_PRODUCTIVE_TURN_MS: Long = 400L

        /**
         * After this many consecutive empty, instantly-failing restarts we stop
         * spinning and commit what we have. High enough that a flaky recognizer
         * is retried persistently, low enough that a permanently-broken one
         * doesn't busy-loop the main thread forever.
         */
        const val MAX_EMPTY_RESTART_BURST: Int = 8
    }
}
