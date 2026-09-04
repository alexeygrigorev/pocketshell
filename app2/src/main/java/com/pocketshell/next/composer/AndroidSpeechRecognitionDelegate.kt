package com.pocketshell.next.composer

/**
 * The composer half of dictation (rewrite task P-1, ported from the old
 * client's `AndroidSpeechRecognitionDelegate`).
 *
 * Owns exactly one thing: how a live transcript becomes draft text, and how a
 * start / stop / cancel moves the composer between [RecordingState]s. The
 * recognizer itself is behind [SpeechRecognitionProvider] (task P-2's lift).
 *
 * ## The rule that makes live dictation usable
 *
 * A recognizer emits its transcript as a series of REPLACEMENTS, not
 * increments: "run the", then "run the tests", then "run the tests now". So the
 * delegate remembers the draft as it was when the mic opened ([baseDraft]) and
 * rewrites `base + latest transcript` on every partial — appending each partial
 * instead would produce "run the run the tests run the tests now".
 *
 * It also re-reads the draft on every partial. If the user typed while
 * dictating, the current draft no longer equals `base + lastTranscript`, and
 * the delegate adopts what is on screen as the new base rather than
 * overwriting the typing on the next partial.
 *
 * ## Ported minus the parts that belonged to other features
 *
 * The old delegate also drove diagnostic events, a settings-selected provider
 * name, an elapsed-recording ticker and a "queued send" that fired a dispatch
 * when transcription resolved. The first two are cut surfaces, the ticker is
 * P-2's recording panel, and the queued send was the outbound queue's. What is
 * left is the transcript arithmetic and the state machine, which is the part
 * the composer genuinely owns.
 */
internal class AndroidSpeechRecognitionDelegate(
    private val provider: SpeechRecognitionProvider,
    private val callbacks: Callbacks,
) {

    /** What the delegate needs from the composer. */
    interface Callbacks {
        /** The draft as it is right now, typing included. */
        fun currentDraft(): String

        /** Replace the draft with [text]. */
        fun onDraft(text: String)

        fun onState(state: RecordingState)

        /** A dictation failed; [message] is shown to the user. */
        fun onError(message: String)
    }

    private var session: SpeechRecognitionSession? = null

    /**
     * Generation guard. A recognizer can deliver a callback after its session
     * was cancelled (the service call is asynchronous), and applying that late
     * partial would resurrect a dictation the user just discarded.
     */
    private var generation: Long = 0L

    private var baseDraft: String = ""
    private var lastTranscript: String = ""

    val isRecording: Boolean get() = session != null

    /** True when a mic tap can do anything at all. */
    fun isAvailable(): Boolean = provider.isAvailable()

    fun start(language: String? = null) {
        if (session != null) return
        if (!provider.isAvailable()) {
            callbacks.onError(UNAVAILABLE_MESSAGE)
            return
        }

        baseDraft = callbacks.currentDraft()
        lastTranscript = ""
        val current = ++generation

        val started = runCatching {
            provider.start(
                language = language,
                listener = object : SpeechRecognitionListener {
                    override fun onPartial(text: String) {
                        if (isCurrent(current)) applyTranscript(text)
                    }

                    override fun onFinal(text: String) {
                        if (isCurrent(current)) finish(text)
                    }

                    override fun onError(message: String) {
                        if (isCurrent(current)) fail(message)
                    }
                },
            )
        }.getOrNull()

        if (started == null) {
            generation += 1
            baseDraft = ""
            callbacks.onError(UNAVAILABLE_MESSAGE)
            return
        }

        session = started
        callbacks.onState(RecordingState.Recording)
    }

    /** The user tapped stop: transcribe what was said. */
    fun stop() {
        val running = session ?: return
        callbacks.onState(RecordingState.Transcribing)
        runCatching { running.stopListening() }.onFailure { failure ->
            fail(failure.message ?: UNAVAILABLE_MESSAGE)
        }
    }

    /** The user tapped discard: drop the recording AND everything it typed. */
    fun cancel() {
        if (session == null) return
        val restored = baseDraft
        clear()
        callbacks.onDraft(restored)
        callbacks.onState(RecordingState.Idle)
    }

    /** The screen went away. Silent — no state callbacks into a dead composer. */
    fun release() {
        val running = session
        session = null
        generation += 1
        runCatching { running?.cancel() }
    }

    private fun isCurrent(candidate: Long): Boolean = session != null && generation == candidate

    /**
     * Rewrites the draft as `base + transcript`, adopting whatever is on screen
     * as the new base when the user typed since the last partial.
     */
    private fun applyTranscript(rawText: String) {
        val text = rawText.trim()
        if (text.isEmpty()) return
        val onScreen = callbacks.currentDraft()
        if (onScreen != append(baseDraft, lastTranscript)) baseDraft = onScreen
        lastTranscript = text
        callbacks.onDraft(append(baseDraft, text))
    }

    private fun finish(rawText: String) {
        val text = rawText.trim().ifEmpty { lastTranscript.trim() }
        if (text.isEmpty()) {
            fail(NO_TEXT_MESSAGE)
            return
        }
        applyTranscript(text)
        clear()
        callbacks.onState(RecordingState.Idle)
    }

    /**
     * A failure keeps whatever was already transcribed.
     *
     * A recognizer that times out after the user spoke a paragraph has still
     * heard the paragraph; throwing it away because the terminal callback was
     * an error rather than a result is the behaviour that makes people stop
     * trusting dictation.
     */
    private fun fail(message: String) {
        if (message.isNoTextFailure() && lastTranscript.isNotBlank()) {
            finish(lastTranscript)
            return
        }
        clear()
        callbacks.onState(RecordingState.Idle)
        callbacks.onError(if (message.isBlank()) FAILED_MESSAGE else message)
    }

    private fun clear() {
        val running = session
        session = null
        generation += 1
        runCatching { running?.cancel() }
        baseDraft = ""
        lastTranscript = ""
    }

    private fun String.isNoTextFailure(): Boolean = isBlank() || this == NO_TEXT_MESSAGE

    private companion object {
        const val UNAVAILABLE_MESSAGE = SpeechMessages.UNAVAILABLE
        const val NO_TEXT_MESSAGE = SpeechMessages.NO_TEXT
        const val FAILED_MESSAGE = SpeechMessages.FAILED

        /** See [ComposerText.appendDictated]; shared with offline-queued delivery. */
        fun append(base: String, transcript: String): String =
            ComposerText.appendDictated(base, transcript)
    }
}
