package com.pocketshell.next.voice

import com.pocketshell.next.composer.SpeechRecognitionListener
import com.pocketshell.next.composer.SpeechRecognitionProvider
import com.pocketshell.next.composer.SpeechRecognitionSession

/**
 * Picks which recognizer a mic tap uses (rewrite task P-2).
 *
 * ## The rule
 *
 * Whisper when an OpenAI API key is stored, Android's system recognizer
 * otherwise. Re-evaluated on every tap, so storing or clearing a key takes
 * effect on the next dictation rather than the next app start.
 *
 * ## Why a rule and not a setting
 *
 * The old client asked in Settings → Voice, and defaulted to Whisper — which
 * meant a fresh install's mic answered "no OpenAI API key saved" to the first
 * tap. app2 has no settings screen yet (that is task P-6), and a key is only
 * ever stored by someone deliberately turning Whisper on, so "a key is present"
 * IS the user's answer to the same question, with the failure mode inverted:
 * the free system recognizer is what a device with nothing configured gets, and
 * that is also the path the maintainer actually dictates through.
 *
 * When P-6 adds the settings surface it replaces this class with the explicit
 * choice; both arms are already here and neither changes.
 */
class RoutingSpeechRecognitionProvider(
    private val whisper: SpeechRecognitionProvider,
    private val android: SpeechRecognitionProvider,
) : SpeechRecognitionProvider {

    /** The mic is live if EITHER arm can run — that is what the tap can do. */
    override fun isAvailable(): Boolean = select().isAvailable()

    override fun start(
        language: String?,
        listener: SpeechRecognitionListener,
    ): SpeechRecognitionSession? = select().start(language, listener)

    /**
     * Whisper's `isAvailable()` is exactly "a key is stored", so asking it is
     * asking the routing question. No second source of truth to drift.
     */
    private fun select(): SpeechRecognitionProvider =
        if (whisper.isAvailable()) whisper else android
}
