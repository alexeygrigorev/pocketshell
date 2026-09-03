package com.pocketshell.next.voice

import com.pocketshell.core.voice.WhisperClient

/**
 * The three one-method seams the Whisper dictation arm needs (rewrite task
 * P-2).
 *
 * They exist so [WhisperSpeechRecognitionProvider] and
 * [PendingTranscriptionDelivery] are plain constructor-injected classes a
 * host-JVM test can drive without a microphone, a network stack or an OpenAI
 * account — the plan's "testability comes from constructor injection only"
 * rule. Each one is the exact slice of an Android/`core-voice` API the voice
 * stack actually uses, ported from the equivalent nested interfaces the old
 * client hung off its composer ViewModel.
 */

/** "Is the device on the internet right now?" — [ConnectivityObserver] in production. */
fun interface ConnectivityProbe {

    /**
     * One-shot read, re-evaluated on every call. Never cached: the whole point
     * is that the answer changes between the tap that started a recording and
     * the tap that ended it.
     */
    fun refresh(): Boolean
}

/**
 * Builds a [WhisperClient] for the CURRENTLY stored API key, or `null` when no
 * key is stored.
 *
 * Not a `@Singleton` client, and deliberately re-created per call: the old
 * client shipped a stale-key footgun exactly because it snapshotted the key at
 * construction time, so a user who fixed a rejected key had to restart the app.
 */
fun interface WhisperClientFactory {
    fun create(): WhisperClient?
}

/**
 * The slice of [com.pocketshell.core.voice.AudioRecorder] the Whisper arm uses.
 *
 * `stop()` returns the captured WAV bytes; a failure to start throws
 * [com.pocketshell.core.voice.AudioRecorderException], which is the caller's
 * signal that the microphone is unavailable.
 */
interface MicCapture {

    /** Opens the microphone. Throws when it cannot be opened. */
    fun start()

    /** Closes the microphone and returns the recorded WAV. */
    fun stop(): ByteArray

    /** Current input level, 0..1. Drives nothing yet; kept for the recording UI. */
    fun currentAmplitude(): Float
}
