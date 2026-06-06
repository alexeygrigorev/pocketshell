package com.pocketshell.app.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresPermission
import com.pocketshell.app.composer.PromptComposerViewModel

/**
 * Prompt-composer speech provider backed by Android's system recognizer.
 *
 * Availability and behavior are device/service dependent. On most Google Play
 * devices this delegates to Google Speech Services; other devices may provide a
 * different recognizer or none at all. The recognizer service decides whether
 * recognition is local or network-backed for the selected language.
 */
internal class AndroidSpeechRecognitionProvider(
    context: Context,
) : PromptComposerViewModel.SpeechRecognitionProvider {

    private val appContext = context.applicationContext

    override fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(appContext)

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun start(
        language: String?,
        listener: PromptComposerViewModel.SpeechRecognitionListener,
    ): PromptComposerViewModel.SpeechRecognitionSession? {
        if (!isAvailable()) return null

        val recognizer = try {
            SpeechRecognizer.createSpeechRecognizer(appContext)
        } catch (_: Throwable) {
            return null
        }
        recognizer.setRecognitionListener(AndroidRecognitionListener(listener))
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            if (!language.isNullOrBlank()) {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            }
        }
        return try {
            recognizer.startListening(intent)
            Session(recognizer)
        } catch (_: Throwable) {
            runCatching { recognizer.destroy() }
            null
        }
    }

    private class Session(
        private val recognizer: SpeechRecognizer,
    ) : PromptComposerViewModel.SpeechRecognitionSession {
        override fun stopListening() {
            recognizer.stopListening()
        }

        override fun cancel() {
            recognizer.cancel()
            recognizer.destroy()
        }
    }

    private class AndroidRecognitionListener(
        private val listener: PromptComposerViewModel.SpeechRecognitionListener,
    ) : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            bestResult(partialResults)?.let(listener::onPartial)
        }

        override fun onResults(results: Bundle?) {
            listener.onFinal(bestResult(results).orEmpty())
        }

        override fun onError(error: Int) {
            listener.onError(errorMessage(error))
        }

        private fun bestResult(bundle: Bundle?): String? {
            val matches = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            return matches?.firstOrNull { it.isNotBlank() }
        }

        private fun errorMessage(error: Int): String = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                PromptComposerViewModel.NO_SPEECH_DETECTED_MESSAGE
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
    }
}
