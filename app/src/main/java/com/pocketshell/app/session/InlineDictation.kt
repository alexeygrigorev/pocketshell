package com.pocketshell.app.session

import android.Manifest
import androidx.annotation.RequiresPermission
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.app.composer.DisabledPendingTranscriptionQueue
import com.pocketshell.app.composer.PromptComposerViewModel
import com.pocketshell.app.composer.UnavailableSpeechRecognitionProvider
import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.app.settings.VoiceTranscriptionProvider
import com.pocketshell.app.voice.DictateDotIcon
import com.pocketshell.core.storage.entity.PendingTranscriptionEntity
import com.pocketshell.core.voice.AudioRecorderException
import com.pocketshell.core.voice.SpeechAudioGuard
import com.pocketshell.core.voice.WhisperException
import com.pocketshell.uikit.components.KeyBar
import com.pocketshell.uikit.components.LoadingIndicator
import com.pocketshell.uikit.components.SpinnerSize
import com.pocketshell.uikit.model.KeyBinding
import com.pocketshell.uikit.model.KeyModifierState
import com.pocketshell.uikit.theme.PocketShellColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.concurrent.thread

/**
 * Backs the key-bar mic slot ([KeyBarWithMic]). Issue #16: tap mic → stream
 * Whisper transcription directly into the terminal at cursor. No review
 * step, no sheet — that path is the prompt composer (issue #15).
 *
 * ## Design choice — sibling wrapper over extended KeyBar
 *
 * Per the brief: "Decide between extending `KeyBar` from `:shared:ui-kit`
 * with a mic slot, OR wrapping it in a sibling `KeyBarWithMic` composable
 * in `app/`. The brief recommends the sibling wrapper."
 *
 * We picked the sibling. Reasoning:
 *
 *  - `KeyBar`'s contract is "8 slots, sticky-modifier FSM, every tap is a
 *    [KeyBinding]". The mic is *not* a key — it owns a recording FSM and
 *    talks to network. Threading that through the existing modifier
 *    component would dilute it.
 *  - The mic state is owned by *this* ViewModel; `KeyBar` is a leaf in
 *    `:shared:ui-kit` with no Android lifecycle awareness. Pulling the
 *    state down through it would require either a `MicState` parameter on
 *    every `KeyBar` call site or a backchannel callback the ui-kit's tap
 *    handlers don't need.
 *  - The session screen is the natural owner of the dictation pipe — it
 *    already holds the [com.pocketshell.core.terminal.ui.TerminalSurfaceState]
 *    that receives the transcribed bytes. Keeping the wrapper in `app/`
 *    keeps the ui-kit Android-free at this level.
 *
 * Trade-off: the mic icon is a 9th slot rather than baked into the 8
 * existing slots — visually a bit wider on narrow screens. Acceptable on
 * a Pixel 7 viewport (the design target).
 *
 * ## State machine
 *
 * Mirrors [com.pocketshell.app.composer.PromptComposerViewModel]'s FSM but
 * strictly simpler — no draft, no editable area, no append-vs-replace
 * question. The transcription bytes are emitted via [transcriptions] and
 * the screen wires that to `terminalState.writeInput(...)`.
 *
 * ```
 *   Idle  ──tap mic──▶  Recording  ──tap mic / configured silence──▶  Transcribing
 *    ▲                                                              │
 *    └──────────────  Whisper success / failure  ◀──────────────────┘
 * ```
 *
 * - `Idle` — the mic slot renders in the secondary text colour. Tapping
 *   transitions to `Recording`.
 * - `Recording` — the mic slot fills with the accent colour. A 50ms
 *   amplitude poll loop drives the configured silence watchdog. Tapping
 *   again, or the configured duration of below-threshold amplitude, transitions to
 *   `Transcribing`.
 * - `Transcribing` — the mic slot shows a small inline spinner. When the
 *   Whisper round-trip resolves we emit the text into [transcriptions]
 *   (success) or [errors] (failure) and return to `Idle`.
 *
 * ## Hilt reuse
 *
 * The constructor consumes the same `MicCapture`, [WhisperClientFactory],
 * and `ApiKeyVault` bindings that issue #15 already declared in
 * [com.pocketshell.app.di.VoiceModule]. The vault and audio recorder are
 * `@Singleton`, so the inline path and the composer path share one mic
 * (only one recording can be in flight at a time on the device anyway)
 * and one stored API key.
 *
 * ## Why we don't write to `TerminalSurfaceState` directly
 *
 * Two reasons:
 *  - Unit-testability — the ViewModel stays pure-Kotlin without dragging
 *    in a terminal-surface dependency. The screen does the write.
 *  - Single source of truth — [SessionViewModel.terminalState] is the
 *    session screen's terminal handle; making both view models reach into
 *    it would put two writers on the same byte queue from different
 *    lifecycles. The screen-level glue keeps the pipe owned by exactly
 *    one place.
 *
 * @see KeyBarWithMic
 * @see com.pocketshell.app.composer.PromptComposerViewModel
 */
@HiltViewModel
public class InlineDictationViewModel @Inject constructor(
    internal val audioRecorder: PromptComposerViewModel.MicCapture,
    internal val whisperClientFactory: WhisperClientFactory,
    internal val apiKeyStorage: PromptComposerViewModel.ApiKeyVault,
    internal val voiceSettings: PromptComposerViewModel.VoiceSettingsSnapshot,
    internal val speechRecognitionProvider: PromptComposerViewModel.SpeechRecognitionProvider =
        UnavailableSpeechRecognitionProvider,
    internal val pendingTranscriptionStore: PromptComposerViewModel.PendingTranscriptionQueue =
        DisabledPendingTranscriptionQueue,
) : ViewModel() {

    private val _uiState: MutableStateFlow<UiState> = MutableStateFlow(UiState())

    /** Current dictation state — drives the mic slot's visual treatment. */
    public val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // Issue #1226: a durable delivery Channel — NOT a `replay = 0`
    // MutableSharedFlow. A replay-0 SharedFlow drops any emit that lands
    // while there is no active collector, so a transcript produced during the
    // 1-3s Whisper round-trip was silently lost whenever the screen's
    // collector was momentarily torn down — e.g. the focused pane flips to
    // `null` or re-keys during a brief reconnect (common on mobile). The FSM
    // was already back at Idle with `error == null`, so the user believed
    // they had sent a command that never arrived.
    //
    // A Channel buffers emitted transcripts even when no collector is
    // subscribed, and hands each buffered value to exactly one collector
    // exactly once when the screen re-subscribes on re-key. That gives us the
    // "delivered on re-key, never dropped, never duplicated" contract:
    //  - UNLIMITED + `trySend` so emission never suspends or fails.
    //  - `receiveAsFlow()` distributes each element to a single collector and
    //    resumes across consecutive collections (the LaunchedEffect re-key),
    //    so nothing is replayed to a fresh collector (no double-write).
    private val deliveryChannel: Channel<String> = Channel(capacity = Channel.UNLIMITED)

    /**
     * Stream of successfully transcribed text. The session screen collects
     * this and writes the bytes into [com.pocketshell.core.terminal.ui.TerminalSurfaceState.writeInput].
     *
     * Emits the raw Whisper output — no newline appended, no trimming
     * beyond what the API already does. That matches the issue's "the
     * transcribed text becomes terminal stdin directly" — the user is
     * dictating a command name or fragment, not a complete line they want
     * submitted.
     *
     * Issue #1226: backed by [deliveryChannel] so a transcript emitted while
     * the collector is torn down (pane null / re-keying during a reconnect)
     * is buffered and delivered when the collector re-subscribes, rather than
     * being dropped into the collector gap.
     */
    public val transcriptions: Flow<String> = deliveryChannel.receiveAsFlow()

    private var recordingJob: Job? = null
    private var transcribeJob: Job? = null
    private var speechRecognitionSession: PromptComposerViewModel.SpeechRecognitionSession? = null
    private var speechRecognitionGeneration: Long = 0L
    private var liveSpeechLastTranscript: String = ""
    private var activeProvider: VoiceTranscriptionProvider? = null

    // Test seam — defaults to wall-clock, tests substitute a virtual clock
    // bound to the test scheduler so the 5s silence window advances
    // deterministically under `runTest`.
    internal var clock: () -> Long = { System.currentTimeMillis() }

    // Dispatcher for the amplitude / silence-watchdog loop. Production
    // wires to `Dispatchers.Default`; tests pass a `TestDispatcher` so
    // `delay` advances under `runTest`'s virtual scheduler.
    internal var samplerDispatcher: CoroutineDispatcher = Dispatchers.Default

    /**
     * Tap the mic slot. Drives the FSM:
     *
     *  - `Idle` → start recording (caller must hold `RECORD_AUDIO`)
     *  - `Recording` → stop and transcribe
     *  - `Transcribing` → no-op (we don't let the user interrupt the
     *    network round-trip; cancellation is via `onCleared`)
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    public fun onMicTap() {
        when (_uiState.value.recording) {
            RecordingState.Idle -> startRecording()
            RecordingState.Recording -> stopAndTranscribe()
            RecordingState.Transcribing -> DiagnosticEvents.record(
                "action",
                "inline_recording_tap_ignored",
            )
        }
    }

    /** Select how inline voice dictation should interpret captured speech. */
    public fun selectMode(mode: DictationMode) {
        _uiState.update { it.copy(mode = mode) }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startRecording() {
        when (voiceSettings.transcriptionProvider()) {
            VoiceTranscriptionProvider.OpenAiWhisper -> startWhisperRecording()
            VoiceTranscriptionProvider.AndroidSpeech -> startAndroidSpeechRecognition()
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startWhisperRecording() {
        // Guard: refuse to record if no API key is stored. The screen
        // surfaces the key-entry path; the inline path just reports the
        // gap rather than capturing audio that can't be uploaded.
        if (whisperClientFactory.create() == null) {
            DiagnosticEvents.record(
                "action",
                "inline_recording_start_result",
                "provider" to VoiceTranscriptionProvider.OpenAiWhisper.name,
                "status" to "failure",
                "cause" to "MissingApiKey",
            )
            _uiState.update {
                it.copy(error = "No OpenAI API key saved. Open the composer to add one.")
            }
            return
        }

        activeProvider = VoiceTranscriptionProvider.OpenAiWhisper
        try {
            audioRecorder.start()
        } catch (e: AudioRecorderException) {
            activeProvider = null
            DiagnosticEvents.record(
                "action",
                "inline_recording_start_result",
                "provider" to VoiceTranscriptionProvider.OpenAiWhisper.name,
                "status" to "failure",
                "cause" to e.javaClass.simpleName,
            )
            _uiState.update {
                it.copy(
                    recording = RecordingState.Idle,
                    error = e.message ?: "Microphone unavailable",
                )
            }
            return
        }

        DiagnosticEvents.record(
            "action",
            "inline_recording_start_result",
            "provider" to VoiceTranscriptionProvider.OpenAiWhisper.name,
            "status" to "success",
            "mode" to _uiState.value.mode.name,
            "silenceWindowMs" to voiceSettings.silenceWindowMs(),
        )
        _uiState.update {
            it.copy(
                recording = RecordingState.Recording,
                amplitude = 0f,
                error = null,
            )
        }

        recordingJob = viewModelScope.launch(samplerDispatcher) {
            sampleAmplitudeAndAutoStopOnSilence()
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startAndroidSpeechRecognition() {
        if (!speechRecognitionProvider.isAvailable()) {
            DiagnosticEvents.record(
                "action",
                "inline_recording_start_result",
                "provider" to VoiceTranscriptionProvider.AndroidSpeech.name,
                "status" to "failure",
                "cause" to "Unavailable",
            )
            _uiState.update { it.copy(error = PromptComposerViewModel.ANDROID_SPEECH_UNAVAILABLE_MESSAGE) }
            return
        }

        val generation = ++speechRecognitionGeneration
        val listener = object : PromptComposerViewModel.SpeechRecognitionListener {
            override fun onPartial(text: String) {
                val trimmed = text.trim()
                if (isCurrentAndroidSpeechRecognition(generation) && trimmed.isNotBlank()) {
                    liveSpeechLastTranscript = trimmed
                    _uiState.update {
                        if (it.recording == RecordingState.Recording) {
                            it.copy(amplitude = 0.35f, error = null)
                        } else {
                            it
                        }
                    }
                }
            }

            override fun onFinal(text: String) {
                if (isCurrentAndroidSpeechRecognition(generation)) {
                    finishAndroidSpeechRecognition(text)
                }
            }

            override fun onError(message: String) {
                if (isCurrentAndroidSpeechRecognition(generation)) {
                    failAndroidSpeechRecognition(message)
                }
            }
        }

        val session = try {
            speechRecognitionProvider.start(
                language = voiceSettings.recognitionLanguageTag(),
                listener = listener,
            )
        } catch (_: Throwable) {
            null
        }

        if (session == null) {
            speechRecognitionGeneration++
            DiagnosticEvents.record(
                "action",
                "inline_recording_start_result",
                "provider" to VoiceTranscriptionProvider.AndroidSpeech.name,
                "status" to "failure",
                "cause" to "StartFailed",
            )
            _uiState.update { it.copy(error = PromptComposerViewModel.ANDROID_SPEECH_UNAVAILABLE_MESSAGE) }
            return
        }

        speechRecognitionSession = session
        activeProvider = VoiceTranscriptionProvider.AndroidSpeech
        DiagnosticEvents.record(
            "action",
            "inline_recording_start_result",
            "provider" to VoiceTranscriptionProvider.AndroidSpeech.name,
            "status" to "success",
            "mode" to _uiState.value.mode.name,
        )
        _uiState.update {
            it.copy(
                recording = RecordingState.Recording,
                amplitude = 0.12f,
                error = null,
            )
        }
    }

    /**
     * Silence watchdog. Polls [PromptComposerViewModel.MicCapture.currentAmplitude]
     * every [SAMPLE_INTERVAL_MS] ms and auto-stops the recording after
     * [SILENCE_WINDOW_MS] of below-threshold amplitude. Mirrors the
     * composer's watchdog 1:1 so the two surfaces feel identical — see
     * [PromptComposerViewModel] for the rationale on the threshold and
     * sample interval values.
     */
    private suspend fun sampleAmplitudeAndAutoStopOnSilence() {
        var lastLoudAtMs: Long = clock()
        var triggerAutoStop = false
        // Snapshot the user-configured silence window once at recording
        // start. Issue #125 made the threshold user-configurable from
        // Settings → Voice; the snapshot read here keeps the running
        // window stable so a slider drag does not shorten the in-flight
        // recording underfoot.
        val silenceWindowMs = voiceSettings.silenceWindowMs()
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            val amp = audioRecorder.currentAmplitude()
            _uiState.update {
                if (it.recording == RecordingState.Recording) {
                    it.copy(amplitude = amp)
                } else {
                    it.copy(amplitude = 0f)
                }
            }
            if (_uiState.value.recording != RecordingState.Recording) {
                break
            }
            if (amp >= SILENCE_AMPLITUDE_THRESHOLD) {
                lastLoudAtMs = clock()
            } else if (clock() - lastLoudAtMs >= silenceWindowMs) {
                triggerAutoStop = true
                break
            }
            delay(SAMPLE_INTERVAL_MS)
        }
        if (triggerAutoStop && _uiState.value.recording == RecordingState.Recording) {
            stopAndTranscribe()
        }
    }

    private fun stopAndTranscribe() {
        if (activeProvider == VoiceTranscriptionProvider.AndroidSpeech) {
            stopAndroidSpeechRecognition()
            return
        }

        recordingJob?.cancel()
        recordingJob = null

        val audio = try {
            audioRecorder.stop()
        } catch (e: AudioRecorderException) {
            activeProvider = null
            DiagnosticEvents.record(
                "action",
                "inline_recording_stop",
                "provider" to VoiceTranscriptionProvider.OpenAiWhisper.name,
                "status" to "failure",
                "cause" to e.javaClass.simpleName,
            )
            _uiState.update {
                it.copy(
                    recording = RecordingState.Idle,
                    amplitude = 0f,
                    error = e.message ?: "Microphone error",
                )
            }
            return
        }

        if (audio.isEmpty()) {
            activeProvider = null
            DiagnosticEvents.record(
                "action",
                "inline_recording_stop",
                "provider" to VoiceTranscriptionProvider.OpenAiWhisper.name,
                "status" to "empty_audio",
                "audioBytes" to 0,
            )
            _uiState.update { it.copy(recording = RecordingState.Idle, amplitude = 0f) }
            return
        }

        // Issue #452: silence guard — skip Whisper on silent / too-short /
        // low-energy audio so the model can't hallucinate a stock phrase
        // (e.g. the Korean "thanks for watching") straight into the
        // terminal. Issue #587 mirrors the composer's recovery path for
        // inline dictation: if the WAV has enough captured signal to look
        // suspicious, save it for retry/export instead of dropping it.
        if (!SpeechAudioGuard.hasSpeechEnergy(audio)) {
            val recoverableNoSpeech = SpeechAudioGuard.isRecoverableNoSpeechRejection(audio)
            DiagnosticEvents.record(
                "action",
                "inline_recording_stop",
                "provider" to VoiceTranscriptionProvider.OpenAiWhisper.name,
                "status" to "no_speech",
                "audioBytes" to audio.size,
                "recoverable" to recoverableNoSpeech,
            )
            if (recoverableNoSpeech) {
                _uiState.update {
                    it.copy(recording = RecordingState.Transcribing, amplitude = 0f)
                }
                transcribeJob = viewModelScope.launch {
                    val pendingId = pendingTranscriptionStore.enqueueAudio(
                        audio = audio,
                        destinationContext = PendingTranscriptionEntity.DESTINATION_INLINE_DICTATION,
                        initialError = SUSPICIOUS_NO_SPEECH_RETRY_MESSAGE,
                    )?.id
                    _uiState.update {
                        it.copy(
                            recording = RecordingState.Idle,
                            amplitude = 0f,
                            error = if (pendingId != null) {
                                SUSPICIOUS_NO_SPEECH_RETRY_MESSAGE
                            } else {
                                NO_SPEECH_DETECTED_MESSAGE
                            },
                        )
                    }
                    activeProvider = null
                }
                return
            }
            activeProvider = null
            _uiState.update {
                it.copy(
                    recording = RecordingState.Idle,
                    amplitude = 0f,
                    error = NO_SPEECH_DETECTED_MESSAGE,
                )
            }
            return
        }

        DiagnosticEvents.record(
            "action",
            "inline_recording_stop",
            "provider" to VoiceTranscriptionProvider.OpenAiWhisper.name,
            "status" to "transcribing",
            "audioBytes" to audio.size,
            "mode" to _uiState.value.mode.name,
        )
        _uiState.update { it.copy(recording = RecordingState.Transcribing, amplitude = 0f) }

        transcribeJob = viewModelScope.launch {
            val client = whisperClientFactory.create()
            if (client == null) {
                activeProvider = null
                DiagnosticEvents.record(
                    "action",
                    "inline_transcription_result",
                    "provider" to VoiceTranscriptionProvider.OpenAiWhisper.name,
                    "status" to "failure",
                    "audioBytes" to audio.size,
                    "cause" to "MissingApiKey",
                )
                _uiState.update {
                    it.copy(
                        recording = RecordingState.Idle,
                        amplitude = 0f,
                        error = "No OpenAI API key saved. Open the composer to add one.",
                    )
                }
                return@launch
            }
            val pendingId = pendingTranscriptionStore.enqueueAudio(
                audio = audio,
                destinationContext = PendingTranscriptionEntity.DESTINATION_INLINE_DICTATION,
                initialError = null,
            )?.id
            val result = client.transcribe(audio, voiceSettings.whisperLanguageHint())
            result.fold(
                onSuccess = { text ->
                    // Empty / whitespace-only transcriptions get dropped —
                    // writing zero bytes is harmless but writing only a
                    // space-or-newline is a footgun on the shell side.
                    val trimmed = text.trim()
                    if (trimmed.isEmpty()) {
                        if (pendingId != null) {
                            runCatching {
                                pendingTranscriptionStore.markFailure(
                                    pendingId,
                                    EMPTY_TRANSCRIPTION_RETRY_MESSAGE,
                                )
                            }
                        }
                        _uiState.update {
                            it.copy(
                                recording = RecordingState.Idle,
                                amplitude = 0f,
                                error = if (pendingId != null) {
                                    EMPTY_TRANSCRIPTION_RETRY_MESSAGE
                                } else {
                                    null
                                },
                            )
                        }
                        activeProvider = null
                        DiagnosticEvents.record(
                            "action",
                            "inline_transcription_result",
                            "provider" to VoiceTranscriptionProvider.OpenAiWhisper.name,
                            "status" to "empty",
                            "audioBytes" to audio.size,
                            "pendingQueued" to (pendingId != null),
                        )
                        return@fold
                    }
                    // Issue #452: secondary backstop — if Whisper still
                    // returned a known silence-hallucination phrase despite
                    // the energy gate, do NOT write it to the terminal.
                    // Surface the "no speech" hint instead. Keep the
                    // persisted row when available so the same speech-like
                    // audio can be retried or exported.
                    if (SpeechAudioGuard.isLikelyHallucination(trimmed)) {
                        if (pendingId != null) {
                            runCatching {
                                pendingTranscriptionStore.markFailure(
                                    pendingId,
                                    NO_SPEECH_DETECTED_MESSAGE,
                                )
                            }
                        }
                        _uiState.update {
                            it.copy(
                                recording = RecordingState.Idle,
                                amplitude = 0f,
                                error = NO_SPEECH_DETECTED_MESSAGE,
                            )
                        }
                        activeProvider = null
                        DiagnosticEvents.record(
                            "action",
                            "inline_transcription_result",
                            "provider" to VoiceTranscriptionProvider.OpenAiWhisper.name,
                            "status" to "hallucination_filtered",
                            "audioBytes" to audio.size,
                            "pendingQueued" to (pendingId != null),
                        )
                        return@fold
                    }
                    if (pendingId != null) {
                        runCatching { pendingTranscriptionStore.markSucceeded(pendingId) }
                    }
                    _uiState.update {
                        it.copy(recording = RecordingState.Idle, amplitude = 0f, error = null)
                    }
                    activeProvider = null
                    DiagnosticEvents.record(
                        "action",
                        "inline_transcription_result",
                        "provider" to VoiceTranscriptionProvider.OpenAiWhisper.name,
                        "status" to "success",
                        "audioBytes" to audio.size,
                        "transcriptBytes" to trimmed.toByteArray(Charsets.UTF_8).size,
                        "pendingQueued" to (pendingId != null),
                        "mode" to _uiState.value.mode.name,
                    )
                    // Issue #1226: buffered send — survives a torn-down /
                    // re-keying collector so the transcript is not lost in the
                    // reconnect gap.
                    deliveryChannel.trySend(trimmed)
                },
                onFailure = { t ->
                    val msg = when (t) {
                        is WhisperException.Auth -> "API key was rejected. Check your OpenAI key."
                        is WhisperException.RateLimited -> "Rate limited by OpenAI. Try again."
                        is WhisperException.Server -> "OpenAI server error. Try again."
                        is WhisperException.Transport -> "Network error: ${t.message}"
                        is WhisperException.Parse -> "Unexpected response from Whisper."
                        else -> t.message ?: "Transcription failed"
                    }
                    if (pendingId != null) {
                        runCatching { pendingTranscriptionStore.markFailure(pendingId, msg) }
                    }
                    activeProvider = null
                    _uiState.update {
                        it.copy(recording = RecordingState.Idle, amplitude = 0f, error = msg)
                    }
                    DiagnosticEvents.record(
                        "action",
                        "inline_transcription_result",
                        "provider" to VoiceTranscriptionProvider.OpenAiWhisper.name,
                        "status" to "failure",
                        "audioBytes" to audio.size,
                        "pendingQueued" to (pendingId != null),
                        "cause" to t.javaClass.simpleName,
                    )
                },
            )
        }
    }

    private fun isCurrentAndroidSpeechRecognition(generation: Long): Boolean =
        activeProvider == VoiceTranscriptionProvider.AndroidSpeech &&
            speechRecognitionSession != null &&
            speechRecognitionGeneration == generation

    private fun stopAndroidSpeechRecognition() {
        val session = speechRecognitionSession ?: run {
            failAndroidSpeechRecognition(PromptComposerViewModel.ANDROID_SPEECH_UNAVAILABLE_MESSAGE)
            return
        }
        runCatching { session.stopListening() }.onFailure {
            DiagnosticEvents.record(
                "action",
                "inline_recording_stop",
                "provider" to VoiceTranscriptionProvider.AndroidSpeech.name,
                "status" to "failure",
                "cause" to it.javaClass.simpleName,
            )
            failAndroidSpeechRecognition(
                it.message ?: PromptComposerViewModel.ANDROID_SPEECH_UNAVAILABLE_MESSAGE,
            )
            return
        }
        DiagnosticEvents.record(
            "action",
            "inline_recording_stop",
            "provider" to VoiceTranscriptionProvider.AndroidSpeech.name,
            "status" to "transcribing",
            "mode" to _uiState.value.mode.name,
        )
        _uiState.update {
            it.copy(
                recording = RecordingState.Transcribing,
                amplitude = 0f,
                error = null,
            )
        }
    }

    private fun finishAndroidSpeechRecognition(rawText: String) {
        val text = rawText.trim().ifEmpty { liveSpeechLastTranscript.trim() }
        if (text.isEmpty()) {
            failAndroidSpeechRecognition(PromptComposerViewModel.ANDROID_SPEECH_NO_TEXT_MESSAGE)
            return
        }

        clearAndroidSpeechSession()
        _uiState.update {
            it.copy(recording = RecordingState.Idle, amplitude = 0f, error = null)
        }
        DiagnosticEvents.record(
            "action",
            "inline_transcription_result",
            "provider" to VoiceTranscriptionProvider.AndroidSpeech.name,
            "status" to "success",
            "transcriptBytes" to text.toByteArray(Charsets.UTF_8).size,
            "mode" to _uiState.value.mode.name,
        )
        // Issue #1226: buffered send (see [deliveryChannel]) — no coroutine
        // needed since `trySend` never suspends, and it survives a torn-down /
        // re-keying collector so the Android-speech transcript is not dropped
        // in a reconnect gap.
        deliveryChannel.trySend(text)
    }

    private fun failAndroidSpeechRecognition(message: String) {
        if (message.isAndroidNoTextFailure() && liveSpeechLastTranscript.isNotBlank()) {
            finishAndroidSpeechRecognition(liveSpeechLastTranscript)
            return
        }
        clearAndroidSpeechSession()
        _uiState.update {
            it.copy(
                recording = RecordingState.Idle,
                amplitude = 0f,
                error = androidSpeechFailureMessage(message),
            )
        }
        DiagnosticEvents.record(
            "action",
            "inline_transcription_result",
            "provider" to VoiceTranscriptionProvider.AndroidSpeech.name,
            "status" to "failure",
            "cause" to "SpeechRecognitionError",
        )
    }

    private fun cancelAndroidSpeechRecognition() {
        clearAndroidSpeechSession()
        _uiState.update {
            it.copy(recording = RecordingState.Idle, amplitude = 0f, error = null)
        }
        DiagnosticEvents.record(
            "action",
            "inline_recording_cancel",
            "provider" to VoiceTranscriptionProvider.AndroidSpeech.name,
        )
    }

    private fun clearAndroidSpeechSession() {
        val session = speechRecognitionSession
        speechRecognitionSession = null
        speechRecognitionGeneration++
        runCatching { session?.cancel() }
        activeProvider = null
        liveSpeechLastTranscript = ""
    }

    private fun String.isAndroidNoTextFailure(): Boolean =
        isBlank() ||
            this == NO_SPEECH_DETECTED_MESSAGE ||
            this == PromptComposerViewModel.ANDROID_SPEECH_NO_TEXT_MESSAGE

    private fun androidSpeechFailureMessage(message: String): String = when {
        message.isBlank() -> PromptComposerViewModel.ANDROID_SPEECH_FAILED_MESSAGE
        message == NO_SPEECH_DETECTED_MESSAGE -> PromptComposerViewModel.ANDROID_SPEECH_NO_TEXT_MESSAGE
        else -> message
    }

    /** Clear the inline error banner. */
    public fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Called by the screen when the runtime `RECORD_AUDIO` prompt comes
     * back denied. Mirrors [PromptComposerViewModel.surfacePermissionDenied].
     */
    public fun surfacePermissionDenied() {
        _uiState.update {
            it.copy(
                recording = RecordingState.Idle,
                amplitude = 0f,
                error = "Microphone permission denied. Grant it in system settings to use voice input.",
            )
        }
    }

    /** Whether an API key is currently saved — same semantics as the composer. */
    public fun hasApiKey(): Boolean {
        val k = apiKeyStorage.load() ?: return false
        java.util.Arrays.fill(k, ' ')
        return true
    }

    public fun needsOpenAiKeyForMicTap(): Boolean =
        voiceSettings.transcriptionProvider() == VoiceTranscriptionProvider.OpenAiWhisper &&
            !hasApiKey()

    override fun onCleared() {
        recordingJob?.cancel()
        transcribeJob?.cancel()
        if (_uiState.value.recording == RecordingState.Recording) {
            if (activeProvider == VoiceTranscriptionProvider.AndroidSpeech) {
                cancelAndroidSpeechRecognition()
            } else {
                stopAudioRecorderBounded()
                activeProvider = null
                DiagnosticEvents.record(
                    "action",
                    "inline_recording_cancel",
                    "provider" to VoiceTranscriptionProvider.OpenAiWhisper.name,
                )
            }
        } else {
            clearAndroidSpeechSession()
        }
        super.onCleared()
    }

    /**
     * Issue #957 (#928 D1 freeze sweep): tear down the Whisper-path audio
     * recorder WITHOUT ever blocking the Main thread unboundedly.
     *
     * The plain `audioRecorder.stop()` call transitively does an unbounded
     * `captureThread.join()` (`PcmCapturePump.stop`): if the platform capture
     * thread is wedged (a stalled `AudioRecord.read`, a driver hiccup), that
     * join — and therefore [onCleared], which runs on the Main thread during
     * ViewModel teardown — blocks for as long as the thread is stuck, i.e. an
     * ANR on teardown (e.g. navigating away mid-dictation).
     *
     * We hard-cut (D22) the unbounded join at this call site: run `stop()` on a
     * throwaway daemon worker, wait at most [ONCLEARED_STOP_BUDGET_MS] for it,
     * and if it does not finish in time, interrupt the worker and abandon it so
     * teardown returns immediately. On the normal (fast) path the worker
     * completes well within the budget and `stop()` runs exactly as before; the
     * captured audio is discarded here regardless because the recording is
     * being cancelled, not transcribed.
     *
     * Worker IO exceptions are swallowed — teardown must not throw — but a
     * timeout is recorded as a diagnostic so a wedged recorder stays visible.
     */
    private fun stopAudioRecorderBounded() {
        val done = CountDownLatch(1)
        val worker = thread(start = true, isDaemon = true, name = "inline-dictation-stop") {
            try {
                audioRecorder.stop()
            } catch (e: Throwable) {
                // Teardown path: never propagate. A wedged/erroring stop must
                // not crash ViewModel clearing. (AudioRecorderException and any
                // unexpected throw from the platform recorder are both caught.)
                DiagnosticEvents.record(
                    "action",
                    "inline_recording_cancel_stop_error",
                    "provider" to VoiceTranscriptionProvider.OpenAiWhisper.name,
                    "cause" to e.javaClass.simpleName,
                )
            } finally {
                done.countDown()
            }
        }
        val finishedInTime = done.await(oncClearedStopBudgetMs, TimeUnit.MILLISECONDS)
        if (!finishedInTime) {
            // The capture thread is wedged. Interrupt the worker (which in turn
            // interrupts the blocked join / read so it can unwind on its own)
            // and abandon it — teardown returns now instead of stalling Main.
            worker.interrupt()
            DiagnosticEvents.record(
                "action",
                "inline_recording_cancel_stop_timeout",
                "provider" to VoiceTranscriptionProvider.OpenAiWhisper.name,
                "budgetMs" to oncClearedStopBudgetMs,
            )
        }
    }

    // Test seam — the bounded-stop budget. Production uses the companion
    // default; tests shrink it so the wedged-stop path is exercised fast.
    internal var oncClearedStopBudgetMs: Long = ONCLEARED_STOP_BUDGET_MS

    /**
     * Test-only hook to drive [onCleared] (which is `protected` on
     * [ViewModel]). Production teardown still goes through the framework's
     * `clear()`; this only exposes the same code path to JVM unit tests.
     */
    internal fun clearForTest() {
        onCleared()
    }

    /** Coarse-grained recording state — drives the mic-slot visual treatment. */
    public enum class RecordingState { Idle, Recording, Transcribing }

    /** User-visible inline dictation mode. Command is UI/state-only for now. */
    public enum class DictationMode { Prompt, Command }

    /**
     * UI state surfaced to [KeyBarWithMic].
     *
     * @param mode selected voice interpretation mode. Prompt currently
     *   preserves the existing inline transcription behavior; Command is
     *   stored and displayed but does not plan shell commands yet.
     * @param recording the FSM phase — picks the slot's colour and the
     *   spinner visibility.
     * @param amplitude latest peak amplitude in `[0, 1]`. Drives the
     *   inline mic-slot bars while [recording] is [RecordingState.Recording];
     *   `0f` otherwise.
     * @param error transient user-facing error message; `null` clears
     *   the banner. The screen clears this on the next interaction or
     *   recording start.
     */
    public data class UiState(
        val mode: DictationMode = DictationMode.Prompt,
        val recording: RecordingState = RecordingState.Idle,
        val amplitude: Float = 0f,
        val error: String? = null,
    )

    public companion object {
        /** Same threshold as [PromptComposerViewModel.SILENCE_AMPLITUDE_THRESHOLD]. */
        public const val SILENCE_AMPLITUDE_THRESHOLD: Float = 0.04f

        /**
         * Fallback silence window. Issue #397 raised this to 30s so inline
         * dictation matches the prompt composer and is much less likely to
         * stop mid-speech. Issue #125 made the window user-configurable from
         * Settings → Voice; the live value comes from
         * [PromptComposerViewModel.VoiceSettingsSnapshot.silenceWindowMs]
         * sampled at the start of each recording.
         */
        public const val SILENCE_WINDOW_MS: Long = 30_000L

        /** Same poll interval as [PromptComposerViewModel.SAMPLE_INTERVAL_MS]. */
        public const val SAMPLE_INTERVAL_MS: Long = 50L

        /**
         * Issue #957 (#928 D1): the maximum time [onCleared] will wait for the
         * Whisper-path `audioRecorder.stop()` to complete before abandoning the
         * stuck worker. Generous enough that a healthy stop (which drains the
         * capture buffer and joins a live thread) always finishes inside it, but
         * small enough that a wedged capture thread cannot turn teardown into an
         * ANR. The Android ANR threshold is 5s; 750ms keeps Main responsive with
         * wide margin.
         */
        public const val ONCLEARED_STOP_BUDGET_MS: Long = 750L

        /**
         * Issue #452: hint surfaced via [UiState.error] when a recording is
         * detected as silent / too short / too quiet, or when Whisper
         * returns a known silence-hallucination phrase. Reuses the composer's
         * message so both voice surfaces show identical copy.
         */
        public const val NO_SPEECH_DETECTED_MESSAGE: String =
            PromptComposerViewModel.NO_SPEECH_DETECTED_MESSAGE

        /**
         * Issue #587: same recovery copy as the composer for no-speech
         * rejections that still contain enough captured signal to save.
         */
        public const val SUSPICIOUS_NO_SPEECH_RETRY_MESSAGE: String =
            PromptComposerViewModel.SUSPICIOUS_NO_SPEECH_RETRY_MESSAGE

        /**
         * Issue #587: empty Whisper text from speech-like inline audio is a
         * failed transcription, not proof that the user said nothing.
         */
        public const val EMPTY_TRANSCRIPTION_RETRY_MESSAGE: String =
            PromptComposerViewModel.EMPTY_TRANSCRIPTION_RETRY_MESSAGE
    }
}

/**
 * Key-bar variant that adds a mic slot at the trailing edge for inline
 * dictation (issue #16). The 8 key slots from [KeyBar] are unchanged —
 * sticky modifiers, arrows, Esc / Tab all behave identically — and the
 * mic slot is rendered as a sibling at the right edge so the existing
 * KeyBar contract stays pure.
 *
 * Visual states for the mic slot:
 *  - [InlineDictationViewModel.RecordingState.Idle] — outlined surface,
 *    secondary-text glyph. Mirrors the [KeyBar]'s idle key treatment so
 *    the slot reads as "part of the bar" rather than a foreign FAB.
 *  - [InlineDictationViewModel.RecordingState.Recording] — accent-soft
 *    fill with accent border and an accent glyph. Same active-state
 *    treatment as a sticky modifier in [KeyBar] for visual consistency.
 *  - [InlineDictationViewModel.RecordingState.Transcribing] — surface-elev
 *    fill with a small in-place [LoadingIndicator.Spinner] replacing
 *    the mic glyph. No FAB-style spinner; we keep everything within the
 *    bar's 38dp height.
 *
 * The slot is a fixed 56dp wide (slightly wider than a regular key) to
 * leave room for the spinner and the slightly larger mic glyph without
 * clipping. The KeyBar itself takes [Modifier.weight] so the keys still
 * stretch to fill the row.
 */
@Composable
public fun KeyBarWithMic(
    keys: List<KeyBinding>,
    onKey: (KeyBinding) -> Unit,
    modifierStates: Map<String, KeyModifierState>? = null,
    onModifierStateChange: (KeyBinding, KeyModifierState) -> Unit = { _, _ -> },
    micState: InlineDictationViewModel.RecordingState,
    micAmplitude: Float = 0f,
    dictationError: String? = null,
    dictationMode: InlineDictationViewModel.DictationMode,
    onDictationModeSelected: (InlineDictationViewModel.DictationMode) -> Unit,
    onMicTap: () -> Unit,
    modifier: Modifier = Modifier,
    // Issue #249: gate the key bar + mic on whether the SSH/tmux session
    // is live. While disconnected/reconnecting a key press routes through
    // the dead input bridge and is silently dropped; a mic tap would
    // dictate a prompt the user believes was delivered. When false we
    // swallow [onKey] / [onMicTap] and render the mic slot in its muted
    // (non-interactive) form so the user sees that input is unavailable.
    inputEnabled: Boolean = true,
) {
    androidx.compose.foundation.layout.Column(
        modifier = modifier
            .fillMaxWidth()
            .background(color = PocketShellColors.Surface)
            .border(border = BorderStroke(1.dp, PocketShellColors.Border))
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        InlineDictationModeSelector(
            selectedMode = dictationMode,
            onModeSelected = onDictationModeSelected,
        )
        InlineDictationStatusRow(
            state = micState,
            error = dictationError,
            inputEnabled = inputEnabled,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            // Hand the KeyBar the available space minus the mic slot. We let
            // KeyBar own its own background / border so the bar's seam looks
            // identical to the bare KeyBar — the wrapper's outer container
            // just gives us a place to anchor the trailing mic slot.
            //
            // KeyBar's outer Row already calls fillMaxWidth() + draws its own
            // surface; nesting it under our outer surface gets us the same
            // visual on the 8-key side. We pass `weight(1f)` so the KeyBar
            // claims the remaining row width after the mic slot is laid out.
            Box(modifier = Modifier.weight(1f)) {
                KeyBar(
                    keys = keys,
                    onKey = if (inputEnabled) onKey else { _ -> },
                    modifierStates = modifierStates,
                    onModifierStateChange = onModifierStateChange,
                )
            }
            InlineMicSlot(
                state = micState,
                amplitude = micAmplitude,
                onTap = onMicTap,
                enabled = inputEnabled,
            )
        }
    }
}

@Composable
private fun InlineDictationStatusRow(
    state: InlineDictationViewModel.RecordingState,
    error: String?,
    inputEnabled: Boolean,
) {
    val failed = error != null
    val label = when {
        failed -> "Failed"
        !inputEnabled -> "Idle"
        state == InlineDictationViewModel.RecordingState.Idle -> "Ready"
        state == InlineDictationViewModel.RecordingState.Recording -> "Recording"
        state == InlineDictationViewModel.RecordingState.Transcribing -> "Transcribing"
        else -> "Idle"
    }
    val detail = error ?: when {
        !inputEnabled -> "Session input unavailable"
        state == InlineDictationViewModel.RecordingState.Idle -> "Speech capture ready"
        state == InlineDictationViewModel.RecordingState.Recording -> "Listening..."
        state == InlineDictationViewModel.RecordingState.Transcribing -> "Transcribing dictation..."
        else -> ""
    }
    val accent = if (failed) PocketShellColors.Accent else when (state) {
        InlineDictationViewModel.RecordingState.Idle -> PocketShellColors.TextSecondary
        InlineDictationViewModel.RecordingState.Recording -> PocketShellColors.Accent
        InlineDictationViewModel.RecordingState.Transcribing -> PocketShellColors.Accent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(20.dp)
            .testTag(INLINE_DICTATION_STATUS_TAG),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = accent,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = detail,
            color = if (failed) PocketShellColors.Accent else PocketShellColors.TextMuted,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun InlineDictationModeSelector(
    selectedMode: InlineDictationViewModel.DictationMode,
    onModeSelected: (InlineDictationViewModel.DictationMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .background(color = PocketShellColors.SurfaceElev, shape = RoundedCornerShape(8.dp))
            .border(
                border = BorderStroke(1.dp, PocketShellColors.Border),
                shape = RoundedCornerShape(8.dp),
            )
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InlineDictationViewModel.DictationMode.entries.forEach { mode ->
            val selected = mode == selectedMode
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(28.dp)
                    .background(
                        color = if (selected) PocketShellColors.AccentSoft else Color.Transparent,
                        shape = RoundedCornerShape(6.dp),
                    )
                    .border(
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (selected) PocketShellColors.AccentDim else Color.Transparent,
                        ),
                        shape = RoundedCornerShape(6.dp),
                    )
                    .selectable(
                        selected = selected,
                        role = Role.RadioButton,
                        onClick = { onModeSelected(mode) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = mode.label,
                    color = if (selected) PocketShellColors.Accent else PocketShellColors.TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                )
            }
        }
    }
}

private val InlineDictationViewModel.DictationMode.label: String
    get() = when (this) {
        InlineDictationViewModel.DictationMode.Prompt -> "Prompt"
        InlineDictationViewModel.DictationMode.Command -> "Command"
    }

/**
 * The trailing mic slot in [KeyBarWithMic]. Pulled out as a private
 * composable so the state-driven colour / glyph switch stays declarative.
 *
 * The 56dp width matches a slightly wider square button (`MicButton` from
 * `:shared:ui-kit` is 56dp itself, but we don't reuse it here — its
 * round drop-shadowed FAB visual would clash with the bar's flat rounded
 * rectangles. The inline dictation mic *is* part of the bar, not a
 * floating action; the visual recipe follows [KeyBar]'s key slots
 * (38dp tall, 8dp radius) instead.)
 */
@Composable
private fun InlineMicSlot(
    state: InlineDictationViewModel.RecordingState,
    amplitude: Float,
    onTap: () -> Unit,
    // Issue #249: when false (session not live) the slot is rendered
    // muted and non-interactive so a tap can't kick off a dictation the
    // user believes was sent into a dead session.
    enabled: Boolean = true,
) {
    val (bg: Color, border: Color, glyph: Color) = when (state) {
        InlineDictationViewModel.RecordingState.Idle -> Triple(
            PocketShellColors.SurfaceElev,
            PocketShellColors.Border,
            PocketShellColors.TextSecondary,
        )

        InlineDictationViewModel.RecordingState.Recording -> Triple(
            PocketShellColors.AccentSoft,
            PocketShellColors.AccentDim,
            PocketShellColors.Accent,
        )

        InlineDictationViewModel.RecordingState.Transcribing -> Triple(
            PocketShellColors.SurfaceElev,
            PocketShellColors.AccentDim,
            PocketShellColors.Accent,
        )
    }

    // Transcribing is non-interactive — we don't let the user re-tap mid-
    // round-trip (avoids double-firing the recorder against an in-flight
    // Whisper job). Issue #249: also non-interactive when the session is
    // not live.
    val clickable: Boolean = enabled &&
        state != InlineDictationViewModel.RecordingState.Transcribing
    // Issue #249: a muted slot reads as "unavailable" the same way the
    // disabled mic FAB does — mute the resting glyph/border when gated.
    val effectiveGlyph: Color = if (enabled) glyph else PocketShellColors.TextMuted
    val effectiveBorder: Color = if (enabled) border else PocketShellColors.BorderSoft

    Box(
        modifier = Modifier
            .width(56.dp)
            .height(38.dp)
            .testTag(INLINE_DICTATION_MIC_SLOT_TAG)
            .semantics {
                contentDescription = when (state) {
                    InlineDictationViewModel.RecordingState.Idle -> "Inline dictation idle"
                    InlineDictationViewModel.RecordingState.Recording -> "Inline dictation recording waveform"
                    InlineDictationViewModel.RecordingState.Transcribing -> "Inline dictation transcribing"
                }
            }
            .background(color = bg, shape = RoundedCornerShape(8.dp))
            .border(
                border = BorderStroke(1.dp, effectiveBorder),
                shape = RoundedCornerShape(8.dp),
            )
            .then(
                if (clickable) {
                    Modifier.clickable(role = Role.Button, onClick = onTap)
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (state == InlineDictationViewModel.RecordingState.Transcribing) {
            LoadingIndicator.Spinner(
                modifier = Modifier.testTag(INLINE_DICTATION_TRANSCRIBING_TAG),
                size = SpinnerSize.Small,
            )
        } else if (state == InlineDictationViewModel.RecordingState.Recording) {
            InlineMicWaveform(amplitude = amplitude)
        } else {
            // Microphone glyph shared with the session voice surface
            // (`DictateDotIcon` in `voice/VoiceSessionSurface.kt`).
            // Replaces an earlier filled-dot `Text("●")` fallback that the
            // UI/UX audit (#108, re-flagged in #123) found ambiguous: the
            // dot did not read as "microphone" without the adjacent
            // dictate caption. Tinted to the same `glyph` colour the
            // state-driven Triple picks (Idle: text-secondary, Recording
            // and Transcribing: accent), so the state-colour story is
            // preserved.
            Icon(
                imageVector = DictateDotIcon,
                contentDescription = null,
                tint = effectiveGlyph,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun InlineMicWaveform(amplitude: Float) {
    val smoothed by animateFloatAsState(
        targetValue = amplitude.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 80, easing = LinearEasing),
        label = "inline-mic-waveform-smooth",
    )
    Row(
        modifier = Modifier.testTag(INLINE_DICTATION_WAVEFORM_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (i in 0 until INLINE_WAVEFORM_BARS) {
            val h = inlineWaveformBarHeightDp(i, smoothed)
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(h.dp)
                    .background(
                        color = PocketShellColors.Accent,
                        shape = RoundedCornerShape(2.dp),
                    ),
            )
        }
    }
}

internal fun inlineWaveformBarHeightDp(index: Int, amplitude: Float): Float {
    val centred = (index - (INLINE_WAVEFORM_BARS - 1) / 2f) / ((INLINE_WAVEFORM_BARS - 1) / 2f)
    val envelope = 1f - centred * centred
    val maxHeight = 8f + envelope * 18f
    return (4f + amplitude.coerceIn(0f, 1f) * maxHeight).coerceIn(4f, 26f)
}

private const val INLINE_WAVEFORM_BARS: Int = 8

internal const val INLINE_DICTATION_MIC_SLOT_TAG: String = "inline-dictation-mic-slot"
internal const val INLINE_DICTATION_WAVEFORM_TAG: String = "inline-dictation-waveform"
internal const val INLINE_DICTATION_TRANSCRIBING_TAG: String = "inline-dictation-transcribing"
internal const val INLINE_DICTATION_STATUS_TAG: String = "inline-dictation-status"
