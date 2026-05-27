package com.pocketshell.app.composer

import android.Manifest
import androidx.annotation.RequiresPermission
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.app.settings.AppSettings
import com.pocketshell.app.settings.SettingsRepository
import com.pocketshell.core.voice.AndroidKeystoreApiKeyStorage
import com.pocketshell.core.voice.AudioRecorderException
import com.pocketshell.core.voice.WhisperException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs [PromptComposerSheet]: the voice + text composer for agent
 * prompts. Owns the recording state machine, the live audio amplitude
 * sampling loop, and the Whisper round-trip.
 *
 * ## State machine
 *
 * ```
 *   Idle  ──tap mic──▶  Recording  ──tap mic / 5s silence──▶  Transcribing
 *    ▲                                                              │
 *    └──────────────  Whisper success / failure  ◀──────────────────┘
 * ```
 *
 * - `Idle` is the initial state. The mic FAB shows the accent fill, no
 *   pulse. Tapping it transitions to `Recording`.
 * - `Recording` opens the mic via [AudioRecorder.start] and starts the
 *   amplitude / silence-watchdog loop. Tapping the mic again, or 5s of
 *   silence below [SILENCE_AMPLITUDE_THRESHOLD], transitions to
 *   `Transcribing`.
 * - `Transcribing` calls [com.pocketshell.core.voice.WhisperClient.transcribe]
 *   on the captured WAV bytes. On success the transcription is appended
 *   to the existing draft text (never replaces); on failure the error is
 *   stashed in [UiState.error] for the screen to surface. Either way the
 *   FSM lands back in `Idle`.
 *
 * ## Draft preservation
 *
 * The composer's text area is the source of truth for the user's prompt.
 * [UiState.draft] survives sheet dismissal because the ViewModel is
 * activity-scoped (the screen retrieves it via `hiltViewModel()` against
 * the activity's `ViewModelStoreOwner`). When the user re-opens the sheet
 * within the same activity instance, the draft is still there.
 *
 * ## Why the WhisperClient comes from a factory
 *
 * The user can update their API key at any time via the one-field
 * settings dialog. Holding a single long-lived [com.pocketshell.core.voice.WhisperClient]
 * snapshot would freeze the first key forever. The Hilt-provided
 * [WhisperClientFactory] reloads from [AndroidKeystoreApiKeyStorage] on
 * each call, so the next transcription always uses the most recent
 * stored key.
 *
 * @see PromptComposerSheet
 */
@HiltViewModel
public class PromptComposerViewModel @Inject constructor(
    internal val audioRecorder: MicCapture,
    internal val whisperClientFactory: WhisperClientFactory,
    internal val apiKeyStorage: ApiKeyVault,
    internal val voiceSettings: VoiceSettingsSnapshot,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {

    private val _uiState: MutableStateFlow<UiState> = MutableStateFlow(
        UiState(
            // Issue #169 Part 2: restore the typed/dictated draft so a
            // process-death recreate (the worst-case "screen-lock kills
            // the activity" path) lands the user back on the same text
            // they had before. `SavedStateHandle` survives both
            // configuration-change recreate (where the ViewModel itself
            // is retained anyway) and process death (where it isn't), so
            // it's the single state holder that closes both gaps.
            draft = savedStateHandle.get<String>(KEY_DRAFT).orEmpty(),
            // If the saved state shows we were mid-recording (or mid-
            // transcribing) at the last state save, the audio buffer is
            // already gone — Whisper takes a complete buffer per request
            // and there is nothing to resume from. Surface an explicit
            // "interrupted" banner instead of silently dropping back to
            // Idle. The flag is cleared below so a second recreate does
            // not keep replaying the message.
            error = if (savedStateHandle.get<Boolean>(KEY_WAS_RECORDING) == true) {
                RECORDING_INTERRUPTED_MESSAGE
            } else {
                null
            },
        ),
    )

    init {
        // Consume the one-shot "was recording" flag now that we have read
        // it into [UiState.error]. Without this, every subsequent saved-
        // state restore (e.g. another recreate while the banner is still
        // visible) would re-stamp the same banner.
        if (savedStateHandle.get<Boolean>(KEY_WAS_RECORDING) == true) {
            savedStateHandle[KEY_WAS_RECORDING] = false
        }
    }

    /**
     * Current composer state — the screen `collectAsState`s this to drive
     * the text area, the mic button state, the waveform amplitude, and
     * the error banner.
     */
    public val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var recordingJob: Job? = null
    private var transcribeJob: Job? = null

    // Test seam: defaults to wall-clock System.currentTimeMillis at
    // production, but tests substitute a virtual clock so the 5s silence
    // window is exercised deterministically.
    internal var clock: () -> Long = { System.currentTimeMillis() }

    // The amplitude loop's dispatcher. Production wires this to
    // Dispatchers.Default (CPU-only sleep + amplitude poll). Tests
    // substitute a virtual TestDispatcher so `delay` advances under
    // `runTest`'s control without burning wall-clock time.
    internal var samplerDispatcher: CoroutineDispatcher = Dispatchers.Default

    /**
     * Replace the draft text. Called when the user edits the text area
     * directly (typing, deleting, pasting). The recording state is
     * unaffected.
     */
    public fun onDraftChange(newText: String) {
        // Issue #169 Part 2: mirror the live draft into [SavedStateHandle]
        // so it survives both configuration-change recreate and process
        // death. Configuration-change recreate would already keep the
        // ViewModel (and therefore [_uiState]) alive, but a long screen
        // lock + low-memory device can still tear the process down, and
        // the dictated text deserves to come back either way.
        savedStateHandle[KEY_DRAFT] = newText
        _uiState.update { it.copy(draft = newText, error = null) }
    }

    /**
     * Tap the mic button. Drives the FSM:
     *
     *  - `Idle` -> start recording (the caller must hold `RECORD_AUDIO`)
     *  - `Recording` -> stop and transcribe
     *  - `Transcribing` -> no-op (the user can't interrupt the round-trip;
     *    if it hangs, dismissing the sheet cancels the coroutine)
     *
     * On any [AudioRecorderException] the FSM returns to `Idle` and the
     * exception's message is exposed via [UiState.error].
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    public fun onMicTap() {
        when (_uiState.value.recording) {
            RecordingState.Idle -> startRecording()
            RecordingState.Recording -> stopAndTranscribe()
            RecordingState.Transcribing -> Unit // ignore — wait for Whisper
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startRecording() {
        // Guard: the user must have a stored API key before we burn cycles
        // on a recording the upload can't consume. The screen surfaces the
        // key entry dialog separately; here we just bail with an error so
        // the user is not surprised by a successful recording that fails
        // at upload time.
        if (whisperClientFactory.create() == null) {
            _uiState.update {
                it.copy(error = "No OpenAI API key saved. Open settings to add one.")
            }
            return
        }

        try {
            audioRecorder.start()
        } catch (e: AudioRecorderException) {
            _uiState.update {
                it.copy(
                    recording = RecordingState.Idle,
                    error = e.message ?: "Microphone unavailable",
                )
            }
            return
        }

        // Issue #169 Part 2: stamp the "was recording" flag into
        // [SavedStateHandle] now, *before* the user can possibly leave the
        // app. If a screen lock / low-memory kill happens between here
        // and [stopAndTranscribe] the next ViewModel instance will see
        // the flag and surface the "recording interrupted" banner.
        savedStateHandle[KEY_WAS_RECORDING] = true

        _uiState.update {
            it.copy(
                recording = RecordingState.Recording,
                amplitude = 0f,
                // Issue #195: every fresh recording starts in the
                // "listening, no speech yet" sub-state. The sampling loop
                // below flips this to true on the first amplitude crossing
                // so the status label can switch from "LISTENING" (waiting)
                // to "CAPTURING" (active speech) within one poll cycle.
                hasDetectedSpeech = false,
                error = null,
            )
        }

        recordingJob = viewModelScope.launch(samplerDispatcher) {
            sampleAmplitudeAndAutoStopOnSilence()
        }
    }

    /**
     * Amplitude / silence-watchdog loop. Runs in `viewModelScope` on the
     * sampler dispatcher. Polls [AudioRecorder.currentAmplitude] every
     * [SAMPLE_INTERVAL_MS] and triggers auto-stop if [SILENCE_WINDOW_MS]
     * elapse without the amplitude exceeding [SILENCE_AMPLITUDE_THRESHOLD].
     *
     * The silence clock resets every time we see amplitude above the
     * threshold, so a brief pause mid-sentence doesn't cut the user off.
     * It is *not* reset by user interactions (typing, scrolling) — only
     * by the audio level itself, per the issue's "5s silence auto-stop
     * (timer reset on amplitude change above a threshold)".
     */
    private suspend fun sampleAmplitudeAndAutoStopOnSilence() {
        var lastLoudAtMs: Long = clock()
        var triggerAutoStop = false
        // Snapshot the user-configured silence window at recording start.
        // Reading it once here (rather than on every loop iteration)
        // matches the issue's intent: the next recording picks up the
        // latest setting, but a slider drag mid-recording does not
        // shorten the current window underfoot.
        val silenceWindowMs = voiceSettings.silenceWindowMs()
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            val amp = audioRecorder.currentAmplitude()
            // Issue #195: flip `hasDetectedSpeech` on the first amplitude
            // sample that crosses [SILENCE_AMPLITUDE_THRESHOLD] so the
            // sheet's status label can move from "LISTENING" (waiting) to
            // "CAPTURING" (active speech). The flag is sticky for the
            // remainder of this recording — a mid-sentence pause should
            // not yank the user back to "speak when ready" while their
            // earlier speech is still in the captured buffer. `SAMPLE_INTERVAL_MS`
            // is 50ms, so the user-visible transition is well inside the
            // 200ms responsiveness budget called out in the issue.
            val crossedThresholdNow = amp >= SILENCE_AMPLITUDE_THRESHOLD
            _uiState.update {
                if (crossedThresholdNow && !it.hasDetectedSpeech) {
                    it.copy(amplitude = amp, hasDetectedSpeech = true)
                } else {
                    it.copy(amplitude = amp)
                }
            }

            if (crossedThresholdNow) {
                lastLoudAtMs = clock()
            } else if (clock() - lastLoudAtMs >= silenceWindowMs) {
                // Drop out of the loop *before* triggering the transcribe;
                // `stopAndTranscribe()` will null `recordingJob` itself.
                triggerAutoStop = true
                break
            }

            delay(SAMPLE_INTERVAL_MS)
        }
        // Only auto-trigger transcribe if we hit the silence threshold
        // (not if the loop exited because the user tapped the mic — in
        // that case `stopAndTranscribe()` is what cancelled the job).
        if (triggerAutoStop && _uiState.value.recording == RecordingState.Recording) {
            stopAndTranscribe()
        }
    }

    private fun stopAndTranscribe() {
        recordingJob?.cancel()
        recordingJob = null

        val audio = try {
            audioRecorder.stop()
        } catch (e: AudioRecorderException) {
            // Issue #169 Part 2: recording is over (even though it failed),
            // so the "was recording" flag must be cleared. Otherwise the
            // next recreate would replay a misleading "interrupted" banner
            // on top of the real microphone error the user is already
            // seeing.
            savedStateHandle[KEY_WAS_RECORDING] = false
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
            // No bytes captured — never happens in practice because
            // [AudioRecorder.stop] returns the WAV header even for a
            // zero-PCM session, but guard anyway.
            savedStateHandle[KEY_WAS_RECORDING] = false
            _uiState.update {
                it.copy(recording = RecordingState.Idle, amplitude = 0f)
            }
            return
        }

        _uiState.update {
            it.copy(recording = RecordingState.Transcribing, amplitude = 0f)
        }

        transcribeJob = viewModelScope.launch {
            val client = whisperClientFactory.create()
            if (client == null) {
                savedStateHandle[KEY_WAS_RECORDING] = false
                _uiState.update {
                    it.copy(
                        recording = RecordingState.Idle,
                        error = "No OpenAI API key saved. Open settings to add one.",
                    )
                }
                return@launch
            }
            // WhisperClient implementations are responsible for jumping
            // off the caller's dispatcher (OkHttpWhisperClient already
            // wraps the call in `withContext(Dispatchers.IO)`), so we
            // don't double-switch here. That also keeps the test scope's
            // virtual scheduler in charge — wrapping with a real
            // dispatcher would make `runTest`'s `advanceUntilIdle` hang.
            val result = client.transcribe(audio, voiceSettings.whisperLanguageHint())
            // Recording + transcribe round-trip is complete: clear the
            // saved "was recording" flag whichever way it lands so we do
            // not falsely surface "recording interrupted" on the next
            // recreate. Done before the state update so the saved blob is
            // already coherent if the process is killed mid-update.
            savedStateHandle[KEY_WAS_RECORDING] = false
            result.fold(
                onSuccess = { text ->
                    _uiState.update {
                        val sep = if (it.draft.isEmpty() || it.draft.endsWith(" ")) "" else " "
                        val newDraft = it.draft + sep + text
                        // Mirror the appended draft into [SavedStateHandle]
                        // immediately so a recreate after a successful
                        // transcription still shows the dictated text.
                        savedStateHandle[KEY_DRAFT] = newDraft
                        it.copy(
                            recording = RecordingState.Idle,
                            draft = newDraft,
                            error = null,
                        )
                    }
                },
                onFailure = { t ->
                    val msg = when (t) {
                        is WhisperException.Auth -> "API key was rejected. Check your OpenAI key in settings."
                        is WhisperException.RateLimited -> "Rate limited by OpenAI. Try again in a moment."
                        is WhisperException.Server -> "OpenAI server error. Try again."
                        is WhisperException.Transport -> "Network error: ${t.message}"
                        is WhisperException.Parse -> "Unexpected response from Whisper."
                        else -> t.message ?: "Transcription failed"
                    }
                    _uiState.update {
                        it.copy(recording = RecordingState.Idle, error = msg)
                    }
                },
            )
        }
    }

    /**
     * Clear any user-facing error banner. Called by the sheet when the
     * user dismisses the error chip or starts a new recording.
     */
    public fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Issue #174: abort the current recording without transcribing.
     *
     * Called by the cancel `X` chip the sheet renders next to the
     * waveform while the FSM is in [RecordingState.Recording]. Behaviour:
     *
     *  - Cancel the silence-watchdog / amplitude-sampler coroutine.
     *  - Stop the underlying [MicCapture] and discard the captured bytes
     *    — the audio buffer is never forwarded to Whisper, so there is
     *    no API cost and no latency wait for a transcription the user
     *    does not want.
     *  - Clear the saved-state "was recording" flag (Issue #169 Part 2)
     *    so a recreate after cancel does not falsely surface the
     *    [RECORDING_INTERRUPTED_MESSAGE] banner.
     *  - Land the FSM back on [RecordingState.Idle] with [UiState.draft]
     *    preserved verbatim — the user explicitly chose to abandon the
     *    new dictation, not the prompt they had already typed.
     *
     * No-op when the FSM is not in [RecordingState.Recording]: cancelling
     * during [RecordingState.Idle] has nothing to undo, and cancelling
     * during [RecordingState.Transcribing] is a separate UX surface that
     * this method intentionally does not handle (the audio has already
     * been sent — Whisper round-trip is in flight and the cost is paid).
     */
    public fun cancelRecording() {
        if (_uiState.value.recording != RecordingState.Recording) {
            return
        }

        recordingJob?.cancel()
        recordingJob = null

        // Stop the mic and drop whatever bytes came back. The capture
        // can fail (mic ripped away mid-record, audio focus loss); in
        // that case we still want to land on Idle and surface the error
        // rather than leaving the FSM stuck on Recording.
        val stopError: String? = try {
            audioRecorder.stop()
            null
        } catch (e: AudioRecorderException) {
            e.message ?: "Microphone error"
        }

        // Recording is over either way — clear the saved-state flag so
        // a process-death recreate after cancel does not replay the
        // "recording interrupted" banner. The user's explicit cancel is
        // not an interruption.
        savedStateHandle[KEY_WAS_RECORDING] = false

        _uiState.update {
            it.copy(
                recording = RecordingState.Idle,
                amplitude = 0f,
                // Existing typed draft must survive. The user's intent
                // here is "throw away the new dictation, keep what I
                // already typed" — wiping the draft would be a hostile
                // misread of the cancel gesture.
                draft = it.draft,
                error = stopError,
            )
        }
    }

    /**
     * Called by the sheet when the runtime `RECORD_AUDIO` prompt comes
     * back denied. Surfaces the message in [UiState.error] so the user
     * sees why nothing happened.
     */
    public fun surfacePermissionDenied() {
        _uiState.update {
            it.copy(
                recording = RecordingState.Idle,
                error = "Microphone permission denied. Grant it in system settings to use voice input.",
            )
        }
    }

    /**
     * Persist a new OpenAI API key. Called from the API key entry dialog
     * once the user enters and saves a key. The plaintext is copied into
     * [AndroidKeystoreApiKeyStorage] and the caller's [CharArray] is
     * untouched — the caller is responsible for zeroing it after.
     */
    public fun saveApiKey(key: CharArray) {
        apiKeyStorage.save(key)
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Whether an API key is currently saved. The screen reads this when
     * the mic FAB is tapped to decide between starting recording (key
     * present) and opening the key entry dialog (key absent).
     */
    public fun hasApiKey(): Boolean {
        val k = apiKeyStorage.load() ?: return false
        // Zero our peek copy so plaintext doesn't linger.
        java.util.Arrays.fill(k, ' ')
        return true
    }

    override fun onCleared() {
        recordingJob?.cancel()
        transcribeJob?.cancel()
        // If we were mid-recording, best-effort drop the mic. We swallow
        // any AudioRecorderException because there's no UI to surface it
        // to at this point in the lifecycle.
        if (_uiState.value.recording == RecordingState.Recording) {
            runCatching { audioRecorder.stop() }
        }
        super.onCleared()
    }

    /** Coarse-grained recording state — drives both the mic FAB and the waveform. */
    public enum class RecordingState { Idle, Recording, Transcribing }

    /**
     * UI state surfaced to [PromptComposerSheet].
     *
     * @param draft the editable text-area contents.
     * @param recording current state of the recording FSM.
     * @param amplitude latest peak amplitude in `[0, 1]`. Drives the
     *   waveform animation while [recording] is [RecordingState.Recording];
     *   `0f` otherwise.
     * @param hasDetectedSpeech issue #195: `true` once the amplitude sampling
     *   loop has seen at least one sample at or above
     *   [SILENCE_AMPLITUDE_THRESHOLD] since the current recording began.
     *   The composer splits the visual `Recording` state into two sub-states
     *   keyed on this flag: pre-speech ("LISTENING" + "speak when ready"
     *   hint) and active speech ("CAPTURING"). The flag is reset to `false`
     *   on every [startRecording] and is only meaningful while [recording]
     *   is [RecordingState.Recording] — readers should always pair it with
     *   the FSM state.
     * @param error transient user-facing error message; `null` clears
     *   the banner. The screen clears this on the next interaction.
     */
    public data class UiState(
        val draft: String = "",
        val recording: RecordingState = RecordingState.Idle,
        val amplitude: Float = 0f,
        val hasDetectedSpeech: Boolean = false,
        val error: String? = null,
    )

    /**
     * Thin abstraction over [com.pocketshell.core.voice.AndroidKeystoreApiKeyStorage]
     * so the ViewModel can be unit-tested without the Android KeyStore.
     *
     * The interface mirrors the slice of `AndroidKeystoreApiKeyStorage`
     * the ViewModel uses (`save` / `load` / `clear`). Production code
     * injects a delegate from [com.pocketshell.app.di.VoiceModule]; tests
     * supply a hand-rolled in-memory fake.
     */
    public interface ApiKeyVault {
        public fun save(key: CharArray)
        public fun load(): CharArray?
        public fun clear()
    }

    /**
     * Thin pull-on-demand seam over [SettingsRepository] so the ViewModel
     * can read the user-configured Whisper language and silence threshold
     * without holding a direct dependency on the repository (and without
     * dragging `SharedPreferences` into the unit tests).
     *
     * Issue #125 added the two knobs to Settings; this interface is the
     * narrow surface the composer ViewModel actually consumes. Production
     * wiring lives in [com.pocketshell.app.di.VoiceModule]; tests
     * substitute an in-line fake that returns scripted values.
     *
     * Both methods are called *at the start of each recording* — the
     * Settings → Voice slider drag during a live recording does not
     * shorten the in-flight window, but the next mic tap picks up the
     * latest stored value.
     */
    public interface VoiceSettingsSnapshot {
        /**
         * Auto-stop silence window in milliseconds. Defaults to the
         * historic 5-second constant when no preference is stored, so
         * fresh installs feel identical to the pre-#125 behaviour.
         */
        public fun silenceWindowMs(): Long

        /**
         * ISO-639-1 language hint or `null` for "let Whisper detect".
         * The historic behaviour (no hint) is preserved by returning
         * `null` whenever the stored preference is the
         * [AppSettings.VOICE_LANGUAGE_AUTO] sentinel.
         */
        public fun whisperLanguageHint(): String?
    }

    /**
     * Thin abstraction over [com.pocketshell.core.voice.AudioRecorder] so
     * the ViewModel can be unit-tested without an Android microphone.
     *
     * The interface mirrors the slice of `AudioRecorder` the ViewModel
     * actually uses (`start` / `stop` / `currentAmplitude`). Production
     * code injects the [AudioRecorderMicCapture] delegate from
     * [com.pocketshell.app.di.VoiceModule]; tests supply a hand-rolled
     * fake that scripts amplitudes and captured byte arrays.
     */
    public interface MicCapture {
        @RequiresPermission(Manifest.permission.RECORD_AUDIO)
        @Throws(AudioRecorderException::class)
        public fun start()

        @Throws(AudioRecorderException::class)
        public fun stop(): ByteArray

        public fun currentAmplitude(): Float
    }

    public companion object {
        /**
         * Amplitudes at or above this threshold count as "speech" for the
         * silence watchdog. 0.04 is empirically a good split between
         * normal speech and room noise on a Pixel 7 — Whisper's
         * pre-processing tolerates low-amplitude speech, but waiting for
         * it would make the auto-stop feel laggy.
         */
        public const val SILENCE_AMPLITUDE_THRESHOLD: Float = 0.04f

        /**
         * Default silence window — once this much time passes without
         * amplitude crossing [SILENCE_AMPLITUDE_THRESHOLD], the recording
         * is auto-stopped. Per D10: "auto-stop after 5s silence".
         *
         * Issue #125 made the window user-configurable from Settings →
         * Voice; this constant is now only the fallback used when no
         * preference has been stored yet. The live value comes from
         * [VoiceSettingsSnapshot.silenceWindowMs] and is sampled at the
         * start of each recording.
         */
        public const val SILENCE_WINDOW_MS: Long = 5_000L

        /**
         * Amplitude poll interval. 50ms is fast enough to drive a 20fps
         * waveform without monopolising the dispatcher. Lower than this
         * isn't perceptible to the user; higher makes the bar wiggle
         * feel laggy.
         */
        public const val SAMPLE_INTERVAL_MS: Long = 50L

        /**
         * Issue #169 Part 2: [SavedStateHandle] key for the current
         * composer draft text. Survives both configuration-change recreate
         * (ViewModel retained) and process death (ViewModel rebuilt from
         * the saved bundle) so a screen-lock-triggered tear-down does not
         * silently discard the dictated text.
         */
        internal const val KEY_DRAFT: String = "prompt-composer-draft"

        /**
         * Issue #169 Part 2: [SavedStateHandle] key for the one-shot
         * "was recording at last save" flag. The recording state machine
         * itself does not survive a recreate (the audio buffer is gone,
         * Whisper cannot resume from a partial capture), so the new
         * ViewModel reads this flag in its initialiser, surfaces the
         * [RECORDING_INTERRUPTED_MESSAGE] banner once, and resets it.
         */
        internal const val KEY_WAS_RECORDING: String = "prompt-composer-was-recording"

        /**
         * Issue #169 Part 2: user-facing message surfaced via
         * [UiState.error] when the composer is rebuilt and the saved-state
         * blob shows recording was in flight at the time the state was
         * last captured. The audio buffer cannot be resumed (Whisper takes
         * a complete buffer per request), so we explicitly tell the user
         * to record again rather than silently dropping back to Idle.
         */
        public const val RECORDING_INTERRUPTED_MESSAGE: String =
            "Recording was interrupted. Tap the mic to record again."
    }
}
