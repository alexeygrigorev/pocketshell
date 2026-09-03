package com.pocketshell.next.composer

/**
 * The composer's whole view of speech input (rewrite task P-1).
 *
 * ## Why the seam is here and the engine is not
 *
 * P-1 owns the composer; P-2 owns the voice stack (`app/.../voice/`, which the
 * plan lifts verbatim, plus its DI bindings). Declaring the seam here is what
 * lets the composer's mic affordance, its recording states and its
 * transcript-merging rules be written and TESTED now, over a fake provider,
 * without P-1 re-implementing the recognizer P-2 is going to lift wholesale.
 *
 * Until P-2 lands, production binds [UnavailableSpeechRecognitionProvider] and
 * the mic renders disabled — which is the honest state ("this device/app has no
 * recognizer wired"), not a button that silently does nothing.
 */
interface SpeechRecognitionProvider {

    /** False when no recognition service is reachable; the mic is disabled then. */
    fun isAvailable(): Boolean

    /**
     * Starts a dictation. Returns `null` when the session could not start at
     * all — the caller treats that exactly like [isAvailable] being false, so
     * there is one "no speech input" path rather than two.
     */
    fun start(language: String?, listener: SpeechRecognitionListener): SpeechRecognitionSession?
}

/**
 * Callbacks from a running dictation. Every one of them arrives on the main
 * thread; the delegate mutates composer state directly from them.
 */
interface SpeechRecognitionListener {

    /** The best transcript so far. Called repeatedly; each call REPLACES the last. */
    fun onPartial(text: String)

    /** The dictation finished with [text] as its transcript. Terminal. */
    fun onFinal(text: String)

    /** The dictation failed. Terminal. [message] is user-facing. */
    fun onError(message: String)
}

/** A running dictation. */
interface SpeechRecognitionSession {

    /** Finish and transcribe what was said. */
    fun stopListening()

    /** Abandon the recording; nothing is transcribed. */
    fun cancel()
}

/**
 * The user-facing strings a dictation can produce, in one place.
 *
 * They live on the SEAM rather than inside [AndroidSpeechRecognitionDelegate]
 * because the delegate's "a failure after speech keeps the text" rule matches
 * on [NO_TEXT] verbatim: a provider that invents its own wording for the same
 * condition would silently lose a transcribed paragraph. P-2's providers emit
 * these; the delegate reads them.
 */
object SpeechMessages {

    const val UNAVAILABLE: String = "Voice input isn't available on this device yet."

    /**
     * The recognizer finished without hearing anything. Load-bearing string:
     * see [AndroidSpeechRecognitionDelegate]'s failure handling.
     */
    const val NO_TEXT: String = "Nothing was heard — try again."

    const val FAILED: String = "Voice input failed — try again."
}

/** The no-recognizer binding. Replaced by task P-2's real provider. */
object UnavailableSpeechRecognitionProvider : SpeechRecognitionProvider {
    override fun isAvailable(): Boolean = false

    override fun start(
        language: String?,
        listener: SpeechRecognitionListener,
    ): SpeechRecognitionSession? = null
}

/** Where the composer is in a dictation. */
enum class RecordingState {
    Idle,

    /** The mic is live and partials are landing in the draft. */
    Recording,

    /** The user stopped; the recognizer is resolving the final transcript. */
    Transcribing,
}
