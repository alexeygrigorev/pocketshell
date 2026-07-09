package com.pocketshell.app.composer

import android.Manifest
import androidx.annotation.RequiresPermission
import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.app.settings.VoiceTranscriptionProvider

internal class AndroidSpeechRecognitionDelegate(
    private val speechRecognitionProvider: PromptComposerViewModel.SpeechRecognitionProvider,
    private val clock: () -> Long,
    private val callbacks: Callbacks,
) {
    private var speechRecognitionSession: PromptComposerViewModel.SpeechRecognitionSession? = null
    private var speechRecognitionGeneration: Long = 0L
    private var liveSpeechBaseDraft: String = ""
    private var liveSpeechLastTranscript: String = ""
    private var recordingStartedAtMs: Long = 0L

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(language: String?) {
        if (!speechRecognitionProvider.isAvailable()) {
            DiagnosticEvents.record(
                "action",
                "composer_recording_start_result",
                "provider" to VoiceTranscriptionProvider.AndroidSpeech.name,
                "status" to "failure",
                "cause" to "Unavailable",
            )
            callbacks.updateUi {
                it.copy(error = PromptComposerViewModel.ANDROID_SPEECH_UNAVAILABLE_MESSAGE)
            }
            return
        }

        liveSpeechBaseDraft = callbacks.currentDraft()
        liveSpeechLastTranscript = ""
        recordingStartedAtMs = clock()
        callbacks.onRecordingStarted(recordingStartedAtMs)
        val generation = ++speechRecognitionGeneration

        val listener = object : PromptComposerViewModel.SpeechRecognitionListener {
            override fun onPartial(text: String) {
                if (isCurrent(generation)) {
                    applyLiveTranscript(text)
                }
            }

            override fun onFinal(text: String) {
                if (isCurrent(generation)) {
                    finish(text)
                }
            }

            override fun onError(message: String) {
                if (isCurrent(generation)) {
                    fail(message)
                }
            }
        }

        val session = try {
            speechRecognitionProvider.start(
                language = language,
                listener = listener,
            )
        } catch (t: Throwable) {
            null
        }

        if (session == null) {
            speechRecognitionGeneration++
            liveSpeechBaseDraft = ""
            liveSpeechLastTranscript = ""
            DiagnosticEvents.record(
                "action",
                "composer_recording_start_result",
                "provider" to VoiceTranscriptionProvider.AndroidSpeech.name,
                "status" to "failure",
                "cause" to "StartFailed",
            )
            callbacks.updateUi {
                it.copy(error = PromptComposerViewModel.ANDROID_SPEECH_UNAVAILABLE_MESSAGE)
            }
            return
        }

        speechRecognitionSession = session
        callbacks.onSessionStarted()
        callbacks.setWasRecording(true)
        DiagnosticEvents.record(
            "action",
            "composer_recording_start_result",
            "provider" to VoiceTranscriptionProvider.AndroidSpeech.name,
            "status" to "success",
        )
        callbacks.updateUi {
            it.copy(
                recording = PromptComposerViewModel.RecordingState.Recording,
                amplitude = 0.12f,
                hasDetectedSpeech = false,
                silenceThresholdSeconds = 0f,
                recordingElapsedMs = 0L,
                liveTranscript = null,
                error = null,
            )
        }

        callbacks.startRecordingTimerTicker()
    }

    fun stop() {
        callbacks.stopRecordingTimerTicker()
        val session = speechRecognitionSession ?: run {
            fail(PromptComposerViewModel.ANDROID_SPEECH_UNAVAILABLE_MESSAGE)
            return
        }
        runCatching { session.stopListening() }.onFailure {
            DiagnosticEvents.record(
                "action",
                "composer_recording_stop",
                "provider" to VoiceTranscriptionProvider.AndroidSpeech.name,
                "status" to "failure",
                "durationMs" to elapsedRecordingMs(),
                "cause" to it.javaClass.simpleName,
            )
            fail(it.message ?: PromptComposerViewModel.ANDROID_SPEECH_UNAVAILABLE_MESSAGE)
            return
        }
        DiagnosticEvents.record(
            "action",
            "composer_recording_stop",
            "provider" to VoiceTranscriptionProvider.AndroidSpeech.name,
            "status" to "transcribing",
            "durationMs" to elapsedRecordingMs(),
        )
        callbacks.updateUi {
            it.copy(
                recording = PromptComposerViewModel.RecordingState.Transcribing,
                amplitude = 0f,
                error = null,
            )
        }
    }

    fun cancel() {
        val restoredDraft = liveSpeechBaseDraft
        callbacks.clearPendingTranscriptionSend()
        clear()
        callbacks.persistDraft(restoredDraft)
        callbacks.updateUi {
            it.copy(
                recording = PromptComposerViewModel.RecordingState.Idle,
                amplitude = 0f,
                hasDetectedSpeech = false,
                liveTranscript = null,
                draft = restoredDraft,
                error = null,
            )
        }
        DiagnosticEvents.record(
            "action",
            "composer_recording_cancel",
            "provider" to VoiceTranscriptionProvider.AndroidSpeech.name,
        )
    }

    fun cancelSession() {
        runCatching { speechRecognitionSession?.cancel() }
    }

    private fun isCurrent(generation: Long): Boolean =
        speechRecognitionSession != null && speechRecognitionGeneration == generation

    private fun applyLiveTranscript(rawText: String): String {
        val text = rawText.trim()
        if (text.isEmpty()) return callbacks.currentDraft()

        var newDraft = ""
        callbacks.updateUi { current ->
            val expectedCurrent = appendTranscript(liveSpeechBaseDraft, liveSpeechLastTranscript)
            val base = if (current.draft == expectedCurrent) {
                liveSpeechBaseDraft
            } else {
                current.draft
            }
            liveSpeechBaseDraft = base
            liveSpeechLastTranscript = text
            newDraft = appendTranscript(base, text)
            current.copy(
                draft = newDraft,
                amplitude = 0.35f,
                hasDetectedSpeech = true,
                liveTranscript = text,
                error = null,
            )
        }
        callbacks.persistDraft(newDraft)
        return newDraft
    }

    private fun finish(rawText: String) {
        val text = rawText.trim().ifEmpty { liveSpeechLastTranscript.trim() }
        if (text.isEmpty()) {
            fail(PromptComposerViewModel.ANDROID_SPEECH_NO_TEXT_MESSAGE)
            return
        }

        applyLiveTranscript(text)
        val pendingSend = callbacks.consumePendingTranscriptionSend()
        clear()

        callbacks.updateUi {
            it.copy(
                recording = PromptComposerViewModel.RecordingState.Idle,
                amplitude = 0f,
                hasDetectedSpeech = false,
                liveTranscript = null,
                error = null,
            )
        }

        if (pendingSend.enabled) {
            callbacks.dispatchSendNow(pendingSend.withEnter, pendingSend.target)
        }
        DiagnosticEvents.record(
            "action",
            "composer_transcription_result",
            "provider" to VoiceTranscriptionProvider.AndroidSpeech.name,
            "status" to "success",
            "transcriptBytes" to text.toByteArray(Charsets.UTF_8).size,
            "queuedSend" to pendingSend.enabled,
        )
    }

    private fun fail(message: String) {
        if (message.isAndroidNoTextFailure() && liveSpeechLastTranscript.isNotBlank()) {
            finish(liveSpeechLastTranscript)
            return
        }
        callbacks.clearPendingTranscriptionSend()
        clear()
        callbacks.updateUi {
            it.copy(
                recording = PromptComposerViewModel.RecordingState.Idle,
                amplitude = 0f,
                hasDetectedSpeech = false,
                liveTranscript = null,
                error = androidSpeechFailureMessage(message),
            )
        }
        DiagnosticEvents.record(
            "action",
            "composer_transcription_result",
            "provider" to VoiceTranscriptionProvider.AndroidSpeech.name,
            "status" to "failure",
            "cause" to "SpeechRecognitionError",
        )
    }

    private fun clear() {
        callbacks.stopRecordingTimerTicker()
        val session = speechRecognitionSession
        speechRecognitionSession = null
        speechRecognitionGeneration++
        runCatching { session?.cancel() }
        callbacks.onSessionCleared()
        liveSpeechBaseDraft = ""
        liveSpeechLastTranscript = ""
        callbacks.setWasRecording(false)
    }

    private fun elapsedRecordingMs(): Long =
        (clock() - recordingStartedAtMs).coerceAtLeast(0L)

    private fun String.isAndroidNoTextFailure(): Boolean =
        isBlank() ||
            this == PromptComposerViewModel.NO_SPEECH_DETECTED_MESSAGE ||
            this == PromptComposerViewModel.ANDROID_SPEECH_NO_TEXT_MESSAGE

    private fun androidSpeechFailureMessage(message: String): String = when {
        message.isBlank() -> PromptComposerViewModel.ANDROID_SPEECH_FAILED_MESSAGE
        message == PromptComposerViewModel.NO_SPEECH_DETECTED_MESSAGE ->
            PromptComposerViewModel.ANDROID_SPEECH_NO_TEXT_MESSAGE
        else -> message
    }

    interface Callbacks {
        fun currentDraft(): String
        fun updateUi(transform: (PromptComposerViewModel.UiState) -> PromptComposerViewModel.UiState)
        fun persistDraft(draft: String)
        fun onRecordingStarted(startedAtMs: Long)
        fun onSessionStarted()
        fun onSessionCleared()
        fun setWasRecording(wasRecording: Boolean)
        fun startRecordingTimerTicker()
        fun stopRecordingTimerTicker()
        fun clearPendingTranscriptionSend()
        fun consumePendingTranscriptionSend(): PendingSend
        fun dispatchSendNow(withEnter: Boolean, target: PromptComposerViewModel.SendTargetSnapshot)
    }

    data class PendingSend(
        val enabled: Boolean,
        val withEnter: Boolean,
        val target: PromptComposerViewModel.SendTargetSnapshot,
    )
}
