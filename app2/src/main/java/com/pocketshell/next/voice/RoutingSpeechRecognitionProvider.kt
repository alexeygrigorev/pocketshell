package com.pocketshell.next.voice

import com.pocketshell.next.composer.SpeechRecognitionListener
import com.pocketshell.next.composer.SpeechRecognitionProvider
import com.pocketshell.next.composer.SpeechRecognitionSession

/**
 * Composer-mic router (#2529).
 *
 * Prompt Composer dictation is **Android `SpeechRecognizer` only**. A stored
 * OpenAI key (surviving from a v0.4.x install after the #2520 package-id
 * restore) must not start Whisper / `AudioRecorder` — that path is what still
 * crashed the phone after #2521. The Whisper arm is kept as a constructor
 * argument so existing tests can prove it is never selected; production binds
 * [AndroidSpeechRecognitionProvider] directly.
 */
class RoutingSpeechRecognitionProvider(
    @Suppress("unused") private val whisper: SpeechRecognitionProvider,
    private val android: SpeechRecognitionProvider,
) : SpeechRecognitionProvider {

    /** Composer mic availability is the system recognizer's, not Whisper's. */
    override fun isAvailable(): Boolean = android.isAvailable()

    override fun start(
        language: String?,
        listener: SpeechRecognitionListener,
    ): SpeechRecognitionSession? = android.start(language, listener)
}
