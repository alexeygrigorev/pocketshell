package com.pocketshell.app.composer

import android.Manifest
import android.net.Uri
import androidx.annotation.RequiresPermission
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.app.settings.AppSettings
import com.pocketshell.app.settings.SettingsRepository
import com.pocketshell.app.settings.VoiceTranscriptionProvider
import com.pocketshell.app.voice.ConnectivityObserver
import com.pocketshell.app.voice.PendingTranscriptionItem
import com.pocketshell.app.voice.PendingTranscriptionStore
import com.pocketshell.core.storage.entity.PendingTranscriptionEntity
import com.pocketshell.core.voice.AndroidKeystoreApiKeyStorage
import com.pocketshell.core.voice.AudioRecorderException
import com.pocketshell.core.voice.SpeechAudioGuard
import com.pocketshell.core.voice.WhisperClient
import com.pocketshell.core.voice.WhisperException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

/**
 * Backs [PromptComposerSheet]: the voice + text composer for agent
 * prompts. Owns the recording state machine, the live audio amplitude
 * sampling loop, and the Whisper round-trip.
 *
 * ## State machine
 *
 * ```
 *   Idle  ──tap mic──▶  Recording  ──tap mic / configured silence──▶  Transcribing
 *    ▲                                                              │
 *    └──────────────  Whisper success / failure  ◀──────────────────┘
 * ```
 *
 * - `Idle` is the initial state. The mic FAB shows the accent fill, no
 *   pulse. Tapping it transitions to `Recording`.
 * - `Recording` opens the mic via [AudioRecorder.start] and starts the
 *   amplitude / silence-watchdog loop. Tapping the mic again, or the
 *   configured silence window below [SILENCE_AMPLITUDE_THRESHOLD], transitions to
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
    internal val speechRecognitionProvider: SpeechRecognitionProvider =
        UnavailableSpeechRecognitionProvider,
    // Issue #180: optional dependencies for the failed-transcription
    // retry queue. Both default to no-op stubs so the rich library of
    // existing unit + connected tests that construct the ViewModel
    // directly (without Hilt) keep compiling without touching every
    // call site. Production wiring in `VoiceModule` always provides the
    // real implementations.
    internal val pendingTranscriptionStore: PendingTranscriptionQueue =
        DisabledPendingTranscriptionQueue,
    internal val connectivity: ConnectivityProbe = AlwaysOnlineConnectivityProbe,
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
    private var speechRecognitionSession: SpeechRecognitionSession? = null
    private var speechRecognitionGeneration: Long = 0L
    private var activeProvider: VoiceTranscriptionProvider? = null
    private var liveSpeechBaseDraft: String = ""
    private var liveSpeechLastTranscript: String = ""

    /**
     * Issue #211 / #254: one-shot Send dispatch surface. The composer
     * sheet collects this and invokes the host's `onSend` callback
     * whenever a send is ready to fire — either immediately (the FSM was
     * Idle when the user tapped Send) or after the in-flight Whisper
     * round-trip lands (the FSM was Recording or Transcribing when the
     * user tapped Send and the queued send fires on transcription
     * success).
     *
     * Backed by a [Channel] (consumed via [receiveAsFlow]) rather than a
     * `MutableSharedFlow`. The composer is a one-collector surface whose
     * collector is torn down and re-created every time the sheet is
     * dismissed and re-opened (the `LaunchedEffect` lives in the sheet's
     * composition). A `replay = 0` SharedFlow only delivers an emission
     * to subscribers present at emit time, so a send dispatched into the
     * SharedFlow's buffer while the previous sheet's collector had just
     * been cancelled — and the next sheet's collector had not yet
     * subscribed — was silently dropped. That is the exact #254
     * "Send works once after dictation, then not on subsequent
     * sends" bug: the dictated transcript fired `dispatchSendNow` from
     * the `viewModelScope` transcribe coroutine in that subscriber gap.
     *
     * A `Channel` does not have that gap: an item offered with no active
     * collector stays buffered in the channel until a collector (even a
     * brand-new one from a re-opened sheet) consumes it, and each item is
     * delivered to exactly one collector. `Channel.BUFFERED` gives a
     * small buffer so [trySend] never fails for the at-most-one pending
     * send the FSM allows.
     */
    private val _sendRequests = Channel<SendRequest>(capacity = Channel.BUFFERED)
    public val sendRequests: Flow<SendRequest> = _sendRequests.receiveAsFlow()

    /**
     * Issue #211: one-shot Send queued by the user while the FSM was
     * Recording or Transcribing. When non-null, the next successful
     * Whisper round-trip will fire the send via [_sendRequests] with the
     * combined draft (existing text + just-transcribed text). The flag
     * is cleared on:
     *
     *  - successful dispatch (one-shot semantics)
     *  - Whisper failure / API key missing / cancel — the send is dropped
     *    and an error banner explains why (the user's tap reached the
     *    pipeline but the round-trip did not succeed).
     *
     * Lives outside [UiState] because the sheet does not need to render
     * a separate visual cue beyond the after-transcribe button labels
     * already driven by `state.recording` + `state.draft`.
     */
    private var pendingSendOnTranscribeSuccess: Boolean = false
    private var pendingSendWithEnter: Boolean = false
    private var attachmentJob: Job? = null

    /**
     * Issue #180: live snapshot of the failed / offline-queued
     * transcriptions. The composer sheet collects this to render the
     * "Transcription failed — retry" banner + expandable list. Always
     * an empty list when the underlying [PendingTranscriptionQueue] is
     * the no-op stub (e.g. in unit tests that construct the ViewModel
     * directly).
     *
     * The flow is exposed as a [StateFlow] so the UI can `collectAsState`
     * without needing a default initial value at every call site. Cold
     * sources upstream would force a recomposition wobble while the
     * first emission lands; pinning to `WhileSubscribed(5_000L)` matches
     * the rest of the codebase's reactive surfaces.
     */
    public val pendingItems: StateFlow<List<PendingTranscriptionItem>> =
        pendingTranscriptionStore.items
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    init {
        // Sweep orphans on construction so a crash between "audio file
        // written" and "row inserted" never accumulates dead files in
        // the voice-pending dir. Best-effort: a reconcile failure is
        // logged at the store level and does not block the composer.
        viewModelScope.launch {
            runCatching { pendingTranscriptionStore.reconcile() }
        }
    }

    // Test seam: defaults to wall-clock System.currentTimeMillis at
    // production, but tests substitute a virtual clock so the silence
    // window is exercised deterministically.
    internal var clock: () -> Long = { System.currentTimeMillis() }

    // Issue #453: wall-clock timestamp (via [clock]) of the current
    // recording's start, used by the sampler loop to publish the mm:ss
    // elapsed timer onto [UiState.recordingElapsedMs]. Reset on each
    // [startRecording]; meaningless outside [RecordingState.Recording].
    private var recordingStartedAtMs: Long = 0L

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
     * Stage selected files through the host screen's uploader and add the
     * resulting remote paths to the structured [UiState.attachments] list
     * (issue #544/#566) — the draft text is NEVER mutated. Each staged file
     * becomes a removable tile; the "Attached files:" suffix is composed
     * from this list only at SEND time ([dispatchSendNow]). The existing
     * draft is never cleared before the upload finishes, so a failed upload
     * leaves the user's prompt intact and actionable error copy visible.
     */
    public fun attachFiles(
        count: Int,
        previews: List<AttachmentPreview> = emptyList(),
        stage: suspend () -> Result<List<String>>,
    ) {
        if (count <= 0 || attachmentJob?.isActive == true) return
        attachmentJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    attachmentUpload = AttachmentUploadState.Uploading(count),
                    error = null,
                )
            }
            val result: Result<List<String>> = try {
                withTimeout(ATTACHMENT_UPLOAD_TIMEOUT_MS) { stage() }
            } catch (timeout: TimeoutCancellationException) {
                Result.failure(timeout)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                Result.failure(t)
            }
            result.fold(
                onSuccess = { paths ->
                    if (paths.isEmpty()) {
                        _uiState.update {
                            it.copy(
                                attachmentUpload = AttachmentUploadState.Idle,
                                error = "No files were attached.",
                            )
                        }
                        return@fold
                    }
                    // Issue #544/#566: append the newly staged paths to the
                    // structured tile list, de-duplicating by remote path so
                    // re-attaching the same file does not double a tile. The
                    // draft text is intentionally left untouched.
                    _uiState.update { current ->
                        val existing = current.attachments.map { it.remotePath }.toSet()
                        val uniquePaths = paths
                            .filter { it.isNotBlank() && it !in existing }
                            .distinct()
                        val added = uniquePaths.map { path ->
                            val preview = previews.getOrNull(paths.indexOf(path))
                            StagedAttachment(
                                remotePath = path,
                                displayName = attachmentDisplayName(path),
                                previewUri = preview?.uri,
                                mimeType = preview?.mimeType,
                            )
                        }
                        current.copy(
                            attachments = current.attachments + added,
                            attachmentUpload = AttachmentUploadState.Idle,
                            error = null,
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            attachmentUpload = AttachmentUploadState.Idle,
                            error = attachmentErrorMessage(error),
                        )
                    }
                },
            )
        }
    }

    /**
     * Issue #560: pre-load an already-uploaded remote file as a composer
     * attachment tile, without re-running an upload. The share-into-session
     * flow uploads the shared file to the session's `.pocketshell/attachments`
     * scope via the exact #544 [PromptAttachmentStager] mechanic, then hands
     * the resulting remote path here so the user lands in the composer with
     * the file already staged as a removable tile — ready to type a message
     * and Send. The draft text is never touched.
     *
     * De-duplicates by remote path so a re-delivered intent (e.g. the
     * activity recreating across a configuration change) cannot double the
     * tile. No-op on a blank path.
     */
    public fun seedAttachment(remotePath: String) {
        val trimmed = remotePath.trim()
        if (trimmed.isEmpty()) return
        _uiState.update { current ->
            if (current.attachments.any { it.remotePath == trimmed }) {
                current
            } else {
                current.copy(
                    attachments = current.attachments +
                        StagedAttachment(
                            remotePath = trimmed,
                            displayName = attachmentDisplayName(trimmed),
                        ),
                )
            }
        }
    }

    /**
     * Issue #544/#566: remove a single staged attachment tile by its remote path.
     * The draft text is untouched; removing every tile leaves the prompt
     * exactly as the user typed it, so the "Attached files:" suffix is only
     * composed at send time from whatever tiles remain.
     */
    public fun removeAttachment(remotePath: String) {
        _uiState.update { current ->
            val filtered = current.attachments.filterNot { it.remotePath == remotePath }
            if (filtered.size == current.attachments.size) {
                current
            } else {
                current.copy(attachments = filtered)
            }
        }
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

    /**
     * Issue #211: the user tapped Send. Behaviour depends on the FSM
     * state when the tap arrives:
     *
     *  - `Idle`: dispatch the current draft immediately via
     *    [sendRequests]. The caller (the composer sheet) collects the
     *    flow and routes the text into the session bridge. This is the
     *    historic one-tap-to-send path.
     *  - `Recording`: queue the send and call [stopAndTranscribe]. The
     *    user's Send tap doubles as a "stop now" override of the silence
     *    window — the recorder is closed immediately, the buffer goes to
     *    Whisper, and the success path fires the send with the combined
     *    (existing-draft + transcript) text. One tap, instead of the
     *    legacy three-tap "stop, wait, send" sequence.
     *  - `Transcribing`: the audio is already in flight on the Whisper
     *    side; we just queue the send flag and let the transcribe
     *    success path dispatch it. The user pays no extra wait — the
     *    network round-trip is already happening.
     *
     * On Whisper failure (or any cancel) the queued send is dropped and
     * an error banner is surfaced through [UiState.error]. The user's
     * intent to send is not silently lost: the audio still lands in the
     * pending-transcription queue (#180) so retry-after-fix routes the
     * transcript into the draft.
     *
     * The empty-draft Idle case (no text typed, FSM Idle) is filtered
     * here so the sheet's Send button can stay enabled in Recording /
     * Transcribing without dispatching nonsense in Idle. Tests and
     * non-Hilt callers reading the flow get the exact same semantics.
     */
    public fun requestSend(withEnter: Boolean) {
        when (_uiState.value.recording) {
            RecordingState.Idle -> dispatchSendNow(withEnter)
            RecordingState.Recording -> {
                pendingSendOnTranscribeSuccess = true
                pendingSendWithEnter = withEnter
                stopAndTranscribe()
            }
            RecordingState.Transcribing -> {
                pendingSendOnTranscribeSuccess = true
                pendingSendWithEnter = withEnter
            }
        }
    }

    /**
     * Issue #211: emit a [SendRequest] for the current draft and clear
     * the draft so the next composer open is a fresh slate. No-op when
     * the draft is empty — the FSM-Idle case where the user has nothing
     * to send, which the UI normally already gates via the Send button's
     * `enabled` predicate. Tests can still call this directly; the empty
     * guard means a hostile caller cannot fire a blank send.
     */
    private fun dispatchSendNow(withEnter: Boolean) {
        val draft = _uiState.value.draft
        val attachments = _uiState.value.attachments
        val uploadInFlight = attachmentJob?.isActive == true
        // Issue #544: compose the outgoing prompt = the user's clean draft
        // + the "Attached files:" suffix appended at the END from whatever
        // tiles remain at send time. The draft stayed clean while composing;
        // the agent still receives the remote paths.
        val text = appendAttachmentPaths(draft, attachments.map { it.remotePath })
        // Send when there is either typed text or at least one attachment.
        // (A pure-attachment send still has a non-empty composed `text`.)
        if (text.isEmpty()) return
        // Clear the draft via the same code path the user's typing
        // takes so [SavedStateHandle] is mirrored. Order matters: we
        // emit the SendRequest BEFORE clearing the draft so a slow
        // collector that re-reads `state.draft` still sees the text
        // that produced the send.
        //
        // Issue #254: a `Channel.trySend` buffers the item until a
        // collector consumes it, so a send dispatched while the sheet's
        // collector is mid-recreate (dismiss → re-open) is delivered to
        // the next collector instead of being dropped.
        _sendRequests.trySend(SendRequest(text = text, withEnter = withEnter))
        // Issue #544/#566: clear the staged tiles now that they've been folded
        // into the dispatched prompt, so the next composer open is a clean
        // slate with no stale attachments.
        //
        // Issue #570: if the user sends while an attachment upload is still
        // stuck, treat that send as a text-only send and stop the unfinished
        // upload from writing a late error/result back into the now-sent
        // composer state.
        if (uploadInFlight) {
            attachmentJob?.cancel()
            attachmentJob = null
        }
        if (attachments.isNotEmpty() || uploadInFlight) {
            _uiState.update {
                it.copy(
                    attachments = emptyList(),
                    attachmentUpload = AttachmentUploadState.Idle,
                )
            }
        }
        onDraftChange("")
    }

    /**
     * Issue #390: the sheet only clears/dismisses optimistically while it
     * hands a SendRequest to the host. If the host reports that the target
     * is disconnected or the write failed, put the exact payload back in
     * the draft and keep a visible status message so the user can retry
     * after reconnecting or discard it intentionally.
     */
    public fun restoreFailedSend(
        request: SendRequest,
        message: String = "Not sent. Reconnect, then send again or discard the draft.",
    ) {
        if (request.text.isEmpty()) return
        savedStateHandle[KEY_DRAFT] = request.text
        _uiState.update { current ->
            current.copy(
                draft = request.text,
                error = message,
                recording = RecordingState.Idle,
                amplitude = 0f,
            )
        }
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

        activeProvider = VoiceTranscriptionProvider.OpenAiWhisper
        try {
            audioRecorder.start()
        } catch (e: AudioRecorderException) {
            activeProvider = null
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

        // Issue #185: snapshot the user-configured silence window once at
        // recording start. Both the watchdog (below) AND the sheet's
        // "auto-stops after Xs silence" hint read this value from
        // [UiState.silenceThresholdSeconds] so they are guaranteed to be
        // consistent — there is no way for the displayed value to drift
        // away from what the watchdog actually waits for. Rounded to one
        // decimal so the rendered label reads `5s` / `1.5s` rather than
        // `4.999s`.
        val windowMs = voiceSettings.silenceWindowMs()
        val thresholdSeconds = windowMs / 1000f

        // Issue #453: stamp the wall-clock recording start so the sampler
        // loop can publish a deterministic mm:ss elapsed timer onto
        // [UiState.recordingElapsedMs]. The clock seam keeps this testable.
        recordingStartedAtMs = clock()

        _uiState.update {
            it.copy(
                recording = RecordingState.Recording,
                amplitude = 0f,
                // Issue #195: every fresh recording starts in the
                // "listening, no speech yet" sub-state. The sampling loop
                // below flips this to true on the first amplitude crossing.
                hasDetectedSpeech = false,
                silenceThresholdSeconds = thresholdSeconds,
                recordingElapsedMs = 0L,
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
            _uiState.update {
                it.copy(error = ANDROID_SPEECH_UNAVAILABLE_MESSAGE)
            }
            return
        }

        liveSpeechBaseDraft = _uiState.value.draft
        liveSpeechLastTranscript = ""
        recordingStartedAtMs = clock()
        val generation = ++speechRecognitionGeneration

        val listener = object : SpeechRecognitionListener {
            override fun onPartial(text: String) {
                if (isCurrentAndroidSpeechRecognition(generation)) {
                    applyLiveSpeechTranscript(text)
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
        } catch (t: Throwable) {
            null
        }

        if (session == null) {
            speechRecognitionGeneration++
            liveSpeechBaseDraft = ""
            liveSpeechLastTranscript = ""
            _uiState.update {
                it.copy(error = ANDROID_SPEECH_UNAVAILABLE_MESSAGE)
            }
            return
        }

        speechRecognitionSession = session
        activeProvider = VoiceTranscriptionProvider.AndroidSpeech
        savedStateHandle[KEY_WAS_RECORDING] = true
        _uiState.update {
            it.copy(
                recording = RecordingState.Recording,
                amplitude = 0.12f,
                hasDetectedSpeech = false,
                silenceThresholdSeconds = 0f,
                recordingElapsedMs = 0L,
                liveTranscript = null,
                error = null,
            )
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
     * by the audio level itself, per the auto-stop contract: the timer
     * resets on amplitude change above a threshold.
     */
    private suspend fun sampleAmplitudeAndAutoStopOnSilence() {
        var lastLoudAtMs: Long = clock()
        var triggerAutoStop = false
        // Read the snapshot the FSM stamped onto [UiState] in
        // [startRecording]. Both this watchdog AND the sheet's
        // "auto-stops after Xs silence" hint share the same source so
        // the user-visible threshold and the actual auto-stop bound are
        // guaranteed to match — see [UiState.silenceThresholdSeconds]
        // for the rationale. The watchdog falls back to the raw
        // [VoiceSettingsSnapshot] read if the threshold field is unset
        // for any reason (defensive against a future caller that
        // bypasses [startRecording]).
        val silenceWindowMs = run {
            val stamped = _uiState.value.silenceThresholdSeconds
            if (stamped > 0f) (stamped * 1000f).toLong() else voiceSettings.silenceWindowMs()
        }
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
            // Issue #453: publish the elapsed mm:ss timer each tick. The
            // value is derived from [clock] so a fixed test clock yields a
            // deterministic timer.
            val elapsedMs = (clock() - recordingStartedAtMs).coerceAtLeast(0L)
            _uiState.update {
                if (crossedThresholdNow && !it.hasDetectedSpeech) {
                    it.copy(amplitude = amp, hasDetectedSpeech = true, recordingElapsedMs = elapsedMs)
                } else {
                    it.copy(amplitude = amp, recordingElapsedMs = elapsedMs)
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
        if (activeProvider == VoiceTranscriptionProvider.AndroidSpeech) {
            stopAndroidSpeechRecognition()
            return
        }

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
            // Issue #211: a queued send is dropped if the mic stop fails
            // — there is no audio buffer to transcribe and therefore no
            // transcript to send. The user sees the mic error and can
            // re-record or send manually.
            pendingSendOnTranscribeSuccess = false
            pendingSendWithEnter = false
            activeProvider = null
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
            // Issue #211: same reasoning as the AudioRecorderException
            // branch — no audio means no transcript to send.
            pendingSendOnTranscribeSuccess = false
            pendingSendWithEnter = false
            activeProvider = null
            _uiState.update {
                it.copy(recording = RecordingState.Idle, amplitude = 0f)
            }
            return
        }

        // Issue #452: silence guard. Whisper hallucinates a stock phrase
        // (e.g. the Korean `시청해주셔서 감사합니다!` from the report) when handed
        // silent / very short / low-energy audio. Detect that BEFORE the
        // network round-trip and skip Whisper. Issue #587 adds one narrow
        // recovery path: if the WAV is long and non-empty with real signal
        // below the conservative speech threshold, queue the recording for
        // retry/export instead of throwing it away as plain silence.
        if (!SpeechAudioGuard.hasSpeechEnergy(audio)) {
            pendingSendOnTranscribeSuccess = false
            pendingSendWithEnter = false
            if (SpeechAudioGuard.isRecoverableNoSpeechRejection(audio)) {
                _uiState.update {
                    it.copy(recording = RecordingState.Transcribing, amplitude = 0f)
                }
                transcribeJob = viewModelScope.launch {
                    val pendingId = pendingTranscriptionStore.enqueueAudio(
                        audio = audio,
                        destinationContext = PendingTranscriptionEntity.DESTINATION_COMPOSER,
                        initialError = SUSPICIOUS_NO_SPEECH_RETRY_MESSAGE,
                    )?.id
                    savedStateHandle[KEY_WAS_RECORDING] = false
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
            savedStateHandle[KEY_WAS_RECORDING] = false
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

        _uiState.update {
            it.copy(recording = RecordingState.Transcribing, amplitude = 0f)
        }

        transcribeJob = viewModelScope.launch {
            val client = whisperClientFactory.create()
            if (client == null) {
                savedStateHandle[KEY_WAS_RECORDING] = false
                // Issue #211: API key vanished between recording start
                // and transcribe — drop the queued send for the same
                // reason the offline path does. No transcript means
                // nothing to send.
                pendingSendOnTranscribeSuccess = false
                pendingSendWithEnter = false
                activeProvider = null
                _uiState.update {
                    it.copy(
                        recording = RecordingState.Idle,
                        error = "No OpenAI API key saved. Open settings to add one.",
                    )
                }
                return@launch
            }

            // Issue #180: persist the audio BEFORE the Whisper call so a
            // mid-flight network drop / process kill cannot lose the
            // recording. The store handles its own size cap + IO failure
            // — `enqueueAudio` returns null on cap/IO failure and the
            // call site below treats null `pendingId` as "no persisted
            // row to clean up on success/failure". The pending row
            // carries a sentinel "Waiting for network" message when we
            // know we are offline so the UI never has to guess.
            //
            // D22 cleanup (#228): the retry queue runs unconditionally —
            // the pre-#180 fail-fast / `persistFailedTranscriptions`
            // toggle is gone. There is no path that skips persistence.
            val offline = !connectivity.refresh()
            val initialError = if (offline) {
                PendingTranscriptionItem.NETWORK_WAITING_MESSAGE
            } else {
                null
            }
            val pendingId: String? = pendingTranscriptionStore.enqueueAudio(
                audio = audio,
                destinationContext = PendingTranscriptionEntity.DESTINATION_COMPOSER,
                initialError = initialError,
            )?.id

            // Recording + transcribe round-trip is complete: clear the
            // saved "was recording" flag whichever way it lands so we do
            // not falsely surface "recording interrupted" on the next
            // recreate. Done before the actual network call so the
            // saved blob is already coherent even if Whisper is slow.
            savedStateHandle[KEY_WAS_RECORDING] = false

            if (offline && pendingId != null) {
                // Skip the Whisper call entirely — there is no network.
                // The pending entry is already on disk; the user sees
                // the banner and the FSM returns to Idle so they can
                // keep typing while waiting for connectivity.
                //
                // Issue #211: a queued Send is dropped on the offline
                // path — there is no transcript to combine with. The
                // user keeps the typed draft (if any) and the audio is
                // safely on disk, so retry-after-online routes the
                // transcript back into the draft and the user can tap
                // Send manually then.
                pendingSendOnTranscribeSuccess = false
                pendingSendWithEnter = false
                activeProvider = null
                _uiState.update {
                    it.copy(
                        recording = RecordingState.Idle,
                        error = PendingTranscriptionItem.NETWORK_WAITING_MESSAGE,
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
            result.fold(
                onSuccess = { text ->
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
                        pendingSendOnTranscribeSuccess = false
                        pendingSendWithEnter = false
                        activeProvider = null
                        _uiState.update {
                            it.copy(
                                recording = RecordingState.Idle,
                                amplitude = 0f,
                                error = if (pendingId != null) {
                                    EMPTY_TRANSCRIPTION_RETRY_MESSAGE
                                } else {
                                    NO_SPEECH_DETECTED_MESSAGE
                                },
                            )
                        }
                        return@fold
                    }
                    // Issue #452: secondary backstop. Even when the audio
                    // cleared the energy bar, Whisper can still return a
                    // canned silence-hallucination phrase. If the whole
                    // transcript is one of the known stock phrases, treat
                    // it as "no speech": do NOT append it to the draft and
                    // drop any queued Send. Issue #587 keeps the persisted
                    // row when there is one, so a speech-like capture can be
                    // retried/exported instead of being irreversibly lost.
                    if (SpeechAudioGuard.isLikelyHallucination(trimmed)) {
                        if (pendingId != null) {
                            runCatching {
                                pendingTranscriptionStore.markFailure(
                                    pendingId,
                                    NO_SPEECH_DETECTED_MESSAGE,
                                )
                            }
                        }
                        pendingSendOnTranscribeSuccess = false
                        pendingSendWithEnter = false
                        activeProvider = null
                        _uiState.update {
                            it.copy(
                                recording = RecordingState.Idle,
                                amplitude = 0f,
                                error = NO_SPEECH_DETECTED_MESSAGE,
                            )
                        }
                        return@fold
                    }
                    if (pendingId != null) {
                        // Clean up the persisted audio + row now that the
                        // transcript is usable. Best-effort: a delete failure
                        // here leaves an orphan file that the next reconcile()
                        // will sweep up.
                        runCatching { pendingTranscriptionStore.markSucceeded(pendingId) }
                    }
                    // Issue #211: snapshot the queued-send flag BEFORE
                    // the state update clears the draft via the send
                    // dispatch. Reads happen on a single thread (the
                    // viewModelScope's main dispatcher) so this is
                    // race-free with `requestSend()` arriving here from
                    // a UI tap — the tap mutates the flag synchronously
                    // and the success block runs after that.
                    val pendingSend = pendingSendOnTranscribeSuccess
                    val pendingWithEnter = pendingSendWithEnter
                    pendingSendOnTranscribeSuccess = false
                    pendingSendWithEnter = false

                    val combinedDraft = _uiState.updateAndReturnDraft { current ->
                        val sep = if (current.isEmpty() || current.endsWith(" ")) "" else " "
                        current + sep + trimmed
                    }
                    // Mirror the appended draft into [SavedStateHandle]
                    // immediately so a recreate after a successful
                    // transcription still shows the dictated text.
                    savedStateHandle[KEY_DRAFT] = combinedDraft

                    if (pendingSend) {
                        // Issue #211: the user queued a Send while we
                        // were still recording / transcribing. Fire it
                        // now with the combined draft (existing text +
                        // freshly-transcribed text). The
                        // [dispatchSendNow] call also clears the draft
                        // so the next composer open starts blank.
                        dispatchSendNow(pendingWithEnter)
                    }
                    activeProvider = null
                },
                onFailure = { t ->
                    val msg = userFacingWhisperError(t)
                    if (pendingId != null) {
                        runCatching { pendingTranscriptionStore.markFailure(pendingId, msg) }
                    }
                    // Issue #211: drop the queued send — the round-trip
                    // failed, so we have no transcript to send. The user
                    // sees the error banner and can either retry the
                    // queued audio (#180) or type + send manually. The
                    // audio is still in the pending-transcription queue
                    // (markFailure above stamped it) so the user can
                    // retry from the banner.
                    pendingSendOnTranscribeSuccess = false
                    pendingSendWithEnter = false
                    activeProvider = null
                    _uiState.update {
                        it.copy(recording = RecordingState.Idle, error = msg)
                    }
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
            failAndroidSpeechRecognition(ANDROID_SPEECH_UNAVAILABLE_MESSAGE)
            return
        }
        runCatching { session.stopListening() }.onFailure {
            failAndroidSpeechRecognition(it.message ?: ANDROID_SPEECH_UNAVAILABLE_MESSAGE)
            return
        }
        _uiState.update {
            it.copy(
                recording = RecordingState.Transcribing,
                amplitude = 0f,
                error = null,
            )
        }
    }

    private fun applyLiveSpeechTranscript(rawText: String): String {
        val text = rawText.trim()
        if (text.isEmpty()) return _uiState.value.draft

        var newDraft = ""
        _uiState.update { current ->
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
        savedStateHandle[KEY_DRAFT] = newDraft
        return newDraft
    }

    private fun finishAndroidSpeechRecognition(rawText: String) {
        val text = rawText.trim()
        if (text.isEmpty()) {
            failAndroidSpeechRecognition(NO_SPEECH_DETECTED_MESSAGE)
            return
        }

        applyLiveSpeechTranscript(text)
        val pendingSend = pendingSendOnTranscribeSuccess
        val pendingWithEnter = pendingSendWithEnter
        pendingSendOnTranscribeSuccess = false
        pendingSendWithEnter = false
        clearAndroidSpeechSession()

        _uiState.update {
            it.copy(
                recording = RecordingState.Idle,
                amplitude = 0f,
                hasDetectedSpeech = false,
                liveTranscript = null,
                error = null,
            )
        }

        if (pendingSend) {
            dispatchSendNow(pendingWithEnter)
        }
    }

    private fun failAndroidSpeechRecognition(message: String) {
        pendingSendOnTranscribeSuccess = false
        pendingSendWithEnter = false
        clearAndroidSpeechSession()
        _uiState.update {
            it.copy(
                recording = RecordingState.Idle,
                amplitude = 0f,
                hasDetectedSpeech = false,
                liveTranscript = null,
                error = message.ifBlank { ANDROID_SPEECH_FAILED_MESSAGE },
            )
        }
    }

    private fun cancelAndroidSpeechRecognition() {
        val restoredDraft = liveSpeechBaseDraft
        pendingSendOnTranscribeSuccess = false
        pendingSendWithEnter = false
        clearAndroidSpeechSession()
        savedStateHandle[KEY_DRAFT] = restoredDraft
        _uiState.update {
            it.copy(
                recording = RecordingState.Idle,
                amplitude = 0f,
                hasDetectedSpeech = false,
                liveTranscript = null,
                draft = restoredDraft,
                error = null,
            )
        }
    }

    private fun clearAndroidSpeechSession() {
        val session = speechRecognitionSession
        speechRecognitionSession = null
        speechRecognitionGeneration++
        runCatching { session?.cancel() }
        activeProvider = null
        liveSpeechBaseDraft = ""
        liveSpeechLastTranscript = ""
        savedStateHandle[KEY_WAS_RECORDING] = false
    }

    /**
     * Issue #211 helper: apply [transform] to the current draft, update
     * [_uiState] (landing the FSM in Idle, clearing the amplitude, and
     * clearing the error banner), and return the new draft text. Lives
     * here as a private extension so the success branch of
     * [stopAndTranscribe] can both update state AND know the resulting
     * draft for the queued-send dispatch without a second read of the
     * StateFlow (which would race with another caller mutating between
     * the update and the read).
     */
    private inline fun MutableStateFlow<UiState>.updateAndReturnDraft(
        transform: (String) -> String,
    ): String {
        var newDraft = ""
        update {
            newDraft = transform(it.draft)
            it.copy(
                recording = RecordingState.Idle,
                draft = newDraft,
                error = null,
            )
        }
        return newDraft
    }

    /**
     * Map a Whisper exception onto a user-facing string. Extracted out
     * of [stopAndTranscribe] so the retry path can render the same
     * messages without duplicating the `when` block.
     */
    private fun userFacingWhisperError(t: Throwable): String = when (t) {
        is WhisperException.Auth -> "API key was rejected. Check your OpenAI key in settings."
        is WhisperException.RateLimited -> "Rate limited by OpenAI. Try again in a moment."
        is WhisperException.Server -> "OpenAI server error. Try again."
        is WhisperException.Transport -> "Network error: ${t.message}"
        is WhisperException.Parse -> "Unexpected response from Whisper."
        else -> t.message ?: "Transcription failed"
    }

    /**
     * Issue #180: re-send the persisted audio for [id] to Whisper.
     *
     * On success the transcript is appended to the current draft and
     * the queue entry + audio file are deleted. On failure the entry's
     * retry counter bumps and the latest error stamps onto the row;
     * once the counter hits
     * [PendingTranscriptionEntity.MAX_RETRY_ATTEMPTS] the UI hides the
     * Retry button and surfaces Delete / Save-as-audio instead (the
     * cap is enforced both here and in the UI gate).
     *
     * No-op when:
     *  - The row is already gone (race against a parallel discard).
     *  - The audio file is missing (orphaned row; reconcile sweeps it).
     *  - The user has not stored a Whisper API key (the UI normally
     *    prevents this state but the gate is defensive).
     *  - The row has already hit the retry cap (defensive gate; the
     *    UI hides the Retry button at the cap).
     */
    public fun retryPending(id: String) {
        viewModelScope.launch {
            val row = pendingTranscriptionStore.snapshot().firstOrNull { it.id == id }
                ?: return@launch
            if (row.atRetryCap) return@launch

            val client = whisperClientFactory.create() ?: run {
                _uiState.update {
                    it.copy(error = "No OpenAI API key saved. Open settings to add one.")
                }
                return@launch
            }
            val audio = pendingTranscriptionStore.loadAudio(id) ?: run {
                // Orphan row — clean it up and clear any stale banner.
                runCatching { pendingTranscriptionStore.markSucceeded(id) }
                return@launch
            }

            // Refuse to fire while offline — the user would just waste
            // another retry slot on a guaranteed-to-fail upload. Surface
            // the "waiting for network" hint as a no-op banner.
            if (!connectivity.refresh()) {
                _uiState.update {
                    it.copy(error = PendingTranscriptionItem.NETWORK_WAITING_MESSAGE)
                }
                return@launch
            }

            _uiState.update { it.copy(error = null) }
            val result = client.transcribe(audio, voiceSettings.whisperLanguageHint())
            result.fold(
                onSuccess = { text ->
                    val trimmed = text.trim()
                    if (trimmed.isEmpty()) {
                        runCatching {
                            pendingTranscriptionStore.markFailure(
                                id,
                                EMPTY_TRANSCRIPTION_RETRY_MESSAGE,
                            )
                        }
                        _uiState.update { it.copy(error = EMPTY_TRANSCRIPTION_RETRY_MESSAGE) }
                        return@fold
                    }
                    if (SpeechAudioGuard.isLikelyHallucination(trimmed)) {
                        runCatching {
                            pendingTranscriptionStore.markFailure(
                                id,
                                NO_SPEECH_DETECTED_MESSAGE,
                            )
                        }
                        _uiState.update { it.copy(error = NO_SPEECH_DETECTED_MESSAGE) }
                        return@fold
                    }
                    runCatching { pendingTranscriptionStore.markSucceeded(id) }
                    _uiState.update {
                        val sep = if (it.draft.isEmpty() || it.draft.endsWith(" ")) "" else " "
                        val newDraft = it.draft + sep + trimmed
                        savedStateHandle[KEY_DRAFT] = newDraft
                        it.copy(draft = newDraft, error = null)
                    }
                },
                onFailure = { t ->
                    val msg = userFacingWhisperError(t)
                    runCatching { pendingTranscriptionStore.markFailure(id, msg) }
                    _uiState.update { it.copy(error = msg) }
                },
            )
        }
    }

    /**
     * Issue #180: drop a queued transcription. Deletes the row + audio
     * file. Idempotent.
     */
    public fun discardPending(id: String) {
        viewModelScope.launch {
            runCatching { pendingTranscriptionStore.discard(id) }
        }
    }

    /**
     * Issue #180: copy the persisted audio into the app's
     * `voice-exports/` directory so the user can hand it to another
     * transcription tool. Surfaced after the 3-retry cap is hit — at
     * that point Whisper has consistently failed and saving the audio
     * is the user's escape hatch.
     *
     * On success the queue entry is removed (the source file moves into
     * exports) and the result path is exposed via [UiState.savedAudioPath]
     * so the sheet can show a confirmation. On failure (missing row,
     * IO error) the state is untouched.
     */
    public fun savePendingAsAudioFile(id: String) {
        viewModelScope.launch {
            val path = runCatching { pendingTranscriptionStore.saveAsAudioFile(id) }
                .getOrNull()
            if (path != null) {
                _uiState.update { it.copy(savedAudioPath = path) }
            }
        }
    }

    /**
     * Acknowledge the "saved to <path>" confirmation toast so it doesn't
     * keep redrawing on every recomposition. The sheet calls this after
     * surfacing the path to the user.
     */
    public fun clearSavedAudioConfirmation() {
        _uiState.update { it.copy(savedAudioPath = null) }
    }

    /**
     * Issue #180: foreground-resume auto-retry hook.
     *
     * Called by the composer sheet on a `Lifecycle.Event.ON_RESUME` (or
     * sheet-open / connectivity-returns transition). When the device
     * has network, every queued row that is BELOW the retry cap and was
     * "waiting for network" (retryCount = 0, sentinel message) is
     * re-attempted. Rows that already failed a Whisper round-trip
     * (retryCount > 0) are NOT auto-retried — only manual retry; the
     * user implicitly opted into the retry by tapping the button.
     *
     * D21 compliance: this method only runs while the composer is
     * foreground (the sheet's `LifecycleEventObserver` is what calls
     * it). There is no scheduler, no WorkManager, no Timer. If the
     * user backgrounds the app, queued rows stay put and auto-retry
     * runs the next time the sheet opens.
     */
    public fun onForegroundResume() {
        viewModelScope.launch {
            if (!connectivity.refresh()) return@launch
            val snapshot = runCatching { pendingTranscriptionStore.snapshot() }
                .getOrElse { return@launch }
            // Only the "queued offline, never tried Whisper" rows are
            // auto-retried. Rows with a prior Whisper failure require an
            // explicit user tap so a permanently-failing audio buffer
            // doesn't burn quota on every foreground.
            val toRetry = snapshot.filter { it.isWaitingForNetwork }
            for (item in toRetry) {
                retryPending(item.id)
            }
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
     * Called by the sheet's recording-state "Discard" control while the FSM
     * is in [RecordingState.Recording]. Behaviour:
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
     * No-op for Idle and for Whisper Transcribing, where dismiss historically
     * left the in-flight round-trip alone. Android speech is the exception:
     * once stop-listening moves the UI into [RecordingState.Transcribing],
     * the recognizer session still exists and can deliver late final/partial
     * callbacks, so dismiss uses this method to expire that session too.
     */
    public fun cancelRecording() {
        if (
            _uiState.value.recording == RecordingState.Transcribing &&
            activeProvider == VoiceTranscriptionProvider.AndroidSpeech
        ) {
            cancelAndroidSpeechRecognition()
            return
        }

        if (_uiState.value.recording != RecordingState.Recording) {
            return
        }

        if (activeProvider == VoiceTranscriptionProvider.AndroidSpeech) {
            cancelAndroidSpeechRecognition()
            return
        }

        recordingJob?.cancel()
        recordingJob = null

        // Issue #211: cancel any queued send that was racing the
        // recorder. A user who hits the X to discard the dictation has
        // explicitly chosen not to send the in-flight buffer; firing
        // the queued send anyway would be a hostile misread of the
        // cancel gesture (worse: it would re-introduce the
        // cancel-then-send bug #210 was filed to fix). The pre-existing
        // typed draft is still preserved verbatim below, so the user
        // can re-tap Send manually if they want to send just the typed
        // text without a fresh dictation.
        pendingSendOnTranscribeSuccess = false
        pendingSendWithEnter = false
        activeProvider = null

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
     * Issue #453: cancel the in-flight transcription.
     *
     * Called by the Transcribing-state "Cancel" affordance the sheet
     * renders (the mockup's Transcribing-state Cancel). Cancels the Whisper
     * round-trip coroutine, drops any queued auto-send, and restores the
     * composer to [RecordingState.Idle] with the existing typed draft
     * preserved. The persisted audio row (if one was enqueued via #180)
     * stays on disk so the user can still retry it from the pending-queue
     * banner — cancelling the live round-trip is not the same as discarding
     * the recording.
     *
     * No-op when the FSM is not in [RecordingState.Transcribing].
     */
    public fun cancelTranscription() {
        if (_uiState.value.recording != RecordingState.Transcribing) {
            return
        }
        if (activeProvider == VoiceTranscriptionProvider.AndroidSpeech) {
            cancelAndroidSpeechRecognition()
            return
        }
        transcribeJob?.cancel()
        transcribeJob = null
        pendingSendOnTranscribeSuccess = false
        pendingSendWithEnter = false
        activeProvider = null
        savedStateHandle[KEY_WAS_RECORDING] = false
        _uiState.update {
            it.copy(
                recording = RecordingState.Idle,
                amplitude = 0f,
                error = null,
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

    public fun needsOpenAiKeyForMicTap(): Boolean =
        voiceSettings.transcriptionProvider() == VoiceTranscriptionProvider.OpenAiWhisper &&
            !hasApiKey()

    override fun onCleared() {
        recordingJob?.cancel()
        transcribeJob?.cancel()
        attachmentJob?.cancel()
        // If we were mid-recording, best-effort drop the mic. We swallow
        // any AudioRecorderException because there's no UI to surface it
        // to at this point in the lifecycle.
        if (_uiState.value.recording == RecordingState.Recording) {
            if (activeProvider == VoiceTranscriptionProvider.AndroidSpeech) {
                runCatching { speechRecognitionSession?.cancel() }
            } else {
                runCatching { audioRecorder.stop() }
            }
        }
        runCatching { speechRecognitionSession?.cancel() }
        super.onCleared()
    }

    /** Coarse-grained recording state — drives both the mic FAB and the waveform. */
    public enum class RecordingState { Idle, Recording, Transcribing }

    public sealed interface AttachmentUploadState {
        public data object Idle : AttachmentUploadState
        public data class Uploading(val count: Int) : AttachmentUploadState
    }

    /**
     * Optional local preview metadata captured at selection time. The remote
     * path remains the stable attachment identity; this is only for rendering
     * a compact image thumbnail while the staged item is visible in the
     * composer.
     */
    public data class AttachmentPreview(
        val uri: Uri,
        val mimeType: String?,
    )

    /**
     * Issue #544: a single staged attachment held as structured state, not
     * folded into the draft text. The composer renders one removable tile
     * per entry; the full [remotePath] is appended to the outgoing prompt's
     * "Attached files:" suffix at SEND time so the agent still receives the
     * references.
     *
     * @property remotePath the absolute remote path the file was uploaded to
     *   (the stable identity used by [removeAttachment] and the send-time
     *   composition). Unique per staged file.
     * @property displayName the short file name shown on the tile — the last
     *   path segment, never the full remote path.
     * @property previewUri optional local content Uri for image thumbnails.
     * @property mimeType optional MIME type for thumbnail/icon selection.
     */
    public data class StagedAttachment(
        val remotePath: String,
        val displayName: String,
        val previewUri: Uri? = null,
        val mimeType: String? = null,
    )

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
     * @param savedAudioPath issue #180: when non-null, the absolute path
     *   to a `voice-exports/` file the user just saved via the
     *   Save-as-audio affordance. The sheet renders a one-shot
     *   confirmation surfacing the path; tapping it (or any other
     *   interaction) calls [clearSavedAudioConfirmation] to clear the
     *   field.
     * @param silenceThresholdSeconds issue #185: the silence window
     *   (seconds) that the watchdog will use for the *current* recording.
     *   Stamped on [startRecording] from
     *   [VoiceSettingsSnapshot.silenceWindowMs] so the value is stable
     *   across the recording even if the user drags the Settings slider
     *   while the mic is open. Used by the sheet to render the "auto-stops
     *   after Xs silence" hint so the user has a mental model. Zero while
     *   the FSM is in [RecordingState.Idle] / [RecordingState.Transcribing]
     *   so the hint is hidden outside an active recording.
     */
    public data class UiState(
        val draft: String = "",
        val recording: RecordingState = RecordingState.Idle,
        val amplitude: Float = 0f,
        val hasDetectedSpeech: Boolean = false,
        val error: String? = null,
        val savedAudioPath: String? = null,
        val silenceThresholdSeconds: Float = 0f,
        val attachmentUpload: AttachmentUploadState = AttachmentUploadState.Idle,
        /**
         * Issue #544/#566: staged attachments held as structured state and
         * rendered as compact removable tiles at the bottom of the composer.
         * Never folded into [draft]; the "Attached files:" suffix is composed
         * from this list only at SEND time. Cleared once a send dispatches.
         */
        val attachments: List<StagedAttachment> = emptyList(),
        /**
         * Issue #453: elapsed time (ms) of the current recording, published
         * by the sampler loop each tick. Drives the mm:ss timer rendered
         * next to the waveform in the Recording state. Zero outside
         * [RecordingState.Recording]. Render with [formatElapsed].
         */
        val recordingElapsedMs: Long = 0L,
        val liveTranscript: String? = null,
    )

    /**
     * Issue #211: one-shot Send dispatched via [sendRequests]. Carries
     * the exact text the user wanted to send + whether the Enter-key
     * suffix should be appended remotely. The sheet collects the flow
     * and forwards each request into the host's `onSend` callback.
     *
     * @property text the draft text at the moment the user tapped Send.
     *   For sends queued mid-recording / mid-transcribe this is the
     *   combined (existing-draft + just-transcribed) text — the Whisper
     *   round-trip completed before this request was emitted.
     * @property withEnter true when the user tapped the `Send`
     *   button; the session-bridge interprets this as "submit the
     *   prompt with a trailing newline so the agent processes it
     *   immediately". False for the `Insert` button (text reaches the
     *   prompt without submitting so the user is still composing).
     */
    public data class SendRequest(
        val text: String,
        val withEnter: Boolean,
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
         * long-dictation fallback when no preference is stored, so fresh
         * installs are conservative about stopping mid-speech.
         */
        public fun silenceWindowMs(): Long

        /**
         * ISO-639-1 language hint or `null` for "let Whisper detect".
         * The historic behaviour (no hint) is preserved by returning
         * `null` whenever the stored preference is the
         * [AppSettings.VOICE_LANGUAGE_AUTO] sentinel.
         */
        public fun whisperLanguageHint(): String?

        public fun recognitionLanguageTag(): String? = whisperLanguageHint()

        public fun transcriptionProvider(): VoiceTranscriptionProvider =
            VoiceTranscriptionProvider.OpenAiWhisper
    }

    public interface SpeechRecognitionProvider {
        public fun isAvailable(): Boolean

        @RequiresPermission(Manifest.permission.RECORD_AUDIO)
        public fun start(
            language: String?,
            listener: SpeechRecognitionListener,
        ): SpeechRecognitionSession?
    }

    public interface SpeechRecognitionSession {
        public fun stopListening()
        public fun cancel()
    }

    public interface SpeechRecognitionListener {
        public fun onPartial(text: String)
        public fun onFinal(text: String)
        public fun onError(message: String)
    }

    /**
     * Issue #180: thin seam around [PendingTranscriptionStore] so the
     * ViewModel can be unit-tested without filesystem / DB plumbing.
     * Production wiring binds onto the real store; tests substitute the
     * [DisabledPendingTranscriptionQueue] no-op (which makes the queue
     * invisible — the FSM behaves as it did pre-#180) or an in-memory
     * fake that scripts items.
     */
    public interface PendingTranscriptionQueue {
        /** Hot stream of queued items, newest first. Empty list when off. */
        public val items: kotlinx.coroutines.flow.Flow<List<PendingTranscriptionItem>>

        /** Persist audio + insert a row. Returns the new item, or null on cap / IO failure. */
        public suspend fun enqueueAudio(
            audio: ByteArray,
            destinationContext: String,
            initialError: String? = null,
        ): PendingTranscriptionItem?

        /** One-shot snapshot of the current rows. */
        public suspend fun snapshot(): List<PendingTranscriptionItem>

        /** Load the audio bytes for [id], or null if missing. */
        public suspend fun loadAudio(id: String): ByteArray?

        /** Delete row + audio file (success path). Idempotent. */
        public suspend fun markSucceeded(id: String)

        /** Bump retry count + stamp error message. Returns updated item, or null. */
        public suspend fun markFailure(id: String, errorMessage: String): PendingTranscriptionItem?

        /** Delete row + audio file (user-driven discard). Idempotent. */
        public suspend fun discard(id: String)

        /**
         * Copy the audio out to the exports dir + delete the queue
         * entry. Returns the exported file path on success.
         */
        public suspend fun saveAsAudioFile(id: String): String?

        /** Sweep orphan files / rows. Best-effort. */
        public suspend fun reconcile()
    }

    /**
     * Issue #180: thin seam around [ConnectivityObserver] so the
     * ViewModel can be unit-tested without
     * [android.net.ConnectivityManager]. Production wiring binds onto
     * the real observer; tests substitute the
     * [AlwaysOnlineConnectivityProbe] (legacy behaviour: every
     * recording fires Whisper) or a scriptable fake that flips between
     * online and offline.
     */
    public interface ConnectivityProbe {
        /** True if the device is online right now. Re-evaluates platform state. */
        public fun refresh(): Boolean
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
         * Fallback silence window — once this much time passes without
         * amplitude crossing [SILENCE_AMPLITUDE_THRESHOLD], the recording
         * is auto-stopped.
         *
         * Issue #125 made the window user-configurable from Settings →
         * Voice; issue #397 raised the fallback/default to 30s so natural
         * pauses and quieter distant speech do not stop recording
         * mid-thought. The live value comes from
         * [VoiceSettingsSnapshot.silenceWindowMs] and is sampled at the start
         * of each recording.
         */
        public const val SILENCE_WINDOW_MS: Long = 30_000L

        /**
         * Amplitude poll interval. 50ms is fast enough to drive a 20fps
         * waveform without monopolising the dispatcher. Lower than this
         * isn't perceptible to the user; higher makes the bar wiggle
         * feel laggy.
         */
        public const val SAMPLE_INTERVAL_MS: Long = 50L

        /**
         * Issue #570: attachment staging must be bounded. A hung content
         * provider, stalled SSH upload, or dead remote must return the
         * composer to editable Idle state instead of leaving the upload
         * banner and disabled attachment actions stuck forever.
         */
        public const val ATTACHMENT_UPLOAD_TIMEOUT_MS: Long = 90_000L

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

        /**
         * Issue #452: user-facing hint surfaced via [UiState.error] when a
         * recording is detected as silent / too short / too quiet (by
         * [SpeechAudioGuard.hasSpeechEnergy]) or when Whisper returns a known
         * silence-hallucination phrase ([SpeechAudioGuard.isLikelyHallucination]).
         * In both cases nothing is inserted into the draft — the user sees
         * this instead of a hallucinated "thanks for watching"-style line.
         */
        public const val NO_SPEECH_DETECTED_MESSAGE: String =
            "No speech detected — nothing to send. Tap the mic and speak."

        /**
         * Issue #587: used when the local guard rejects the first-pass
         * transcription but the WAV still looks like a long, non-empty
         * capture with real signal. The user gets a retry/export path instead
         * of losing the recording under a plain "no speech" banner.
         */
        public const val SUSPICIOUS_NO_SPEECH_RETRY_MESSAGE: String =
            "No speech detected, but the recording was saved for retry."

        /**
         * Issue #587: Whisper can occasionally return an empty text field for
         * a real recording. Treat that as a failed transcription and keep the
         * persisted audio row for retry/export.
         */
        public const val EMPTY_TRANSCRIPTION_RETRY_MESSAGE: String =
            "Whisper returned no text. Recording saved for retry."

        public const val ANDROID_SPEECH_UNAVAILABLE_MESSAGE: String =
            "Android speech recognition is not available on this device. Choose Whisper or install/enable a system speech service."

        public const val ANDROID_SPEECH_FAILED_MESSAGE: String =
            "Android speech recognition failed. Try again or choose Whisper in settings."
    }
}

internal object UnavailableSpeechRecognitionProvider :
    PromptComposerViewModel.SpeechRecognitionProvider {
    override fun isAvailable(): Boolean = false

    override fun start(
        language: String?,
        listener: PromptComposerViewModel.SpeechRecognitionListener,
    ): PromptComposerViewModel.SpeechRecognitionSession? = null
}

/**
 * Issue #453: format an elapsed-recording duration (milliseconds) as a
 * zero-padded `mm:ss` timer (e.g. `00:17`, `01:05`, `12:34`). Minutes are
 * not capped at 99 — a very long dictation renders `120:00` rather than
 * rolling over — but in practice the silence watchdog stops well before
 * that. Exposed at file scope so the unit tests can pin the formatting
 * without composing the sheet.
 */
internal fun formatElapsed(elapsedMs: Long): String {
    val totalSeconds = (elapsedMs.coerceAtLeast(0L)) / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%02d:%02d".format(minutes, seconds)
}

internal fun appendAttachmentPaths(draft: String, paths: List<String>): String {
    if (paths.isEmpty()) return draft
    val block = buildString {
        append("Attached files:")
        paths.forEach { path ->
            append('\n')
            append("- ")
            append(path)
        }
    }
    return when {
        draft.isBlank() -> block
        draft.endsWith("\n\n") -> draft + block
        draft.endsWith("\n") -> draft + "\n" + block
        else -> draft + "\n\n" + block
    }
}

private fun appendTranscript(draft: String, transcript: String): String {
    if (transcript.isBlank()) return draft
    val sep = if (draft.isEmpty() || draft.endsWith(" ")) "" else " "
    return draft + sep + transcript
}

/**
 * Issue #544/#566: derive the short tile label from a staged remote path — the
 * last path segment (file name), never the full remote path. Trailing
 * slashes are trimmed first; a path that is all slashes or blank falls back
 * to the original string so the tile is never empty.
 */
internal fun attachmentDisplayName(remotePath: String): String {
    val trimmed = remotePath.trimEnd('/')
    val segment = trimmed.substringAfterLast('/')
    return segment.ifBlank { remotePath }
}

private fun attachmentErrorMessage(error: Throwable): String {
    val detail = if (error is TimeoutCancellationException) {
        "upload timed out"
    } else {
        val raw = error.message?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        raw.ifBlank { error.javaClass.simpleName }
    }
    return "Attachment upload failed: $detail. Your draft was kept; reconnect or choose a smaller/readable file."
}

/**
 * Issue #180: no-op [PromptComposerViewModel.PendingTranscriptionQueue]
 * implementation used when the ViewModel is constructed without the
 * real store — exclusively by host-JVM unit tests and connected tests
 * that pre-date the queue. The flow stays empty so the composer banner
 * never appears; every suspend method short-circuits. Production wiring
 * via Hilt never sees this object.
 */
public object DisabledPendingTranscriptionQueue : PromptComposerViewModel.PendingTranscriptionQueue {
    override val items: kotlinx.coroutines.flow.Flow<List<PendingTranscriptionItem>> =
        kotlinx.coroutines.flow.flowOf(emptyList())

    override suspend fun enqueueAudio(
        audio: ByteArray,
        destinationContext: String,
        initialError: String?,
    ): PendingTranscriptionItem? = null

    override suspend fun snapshot(): List<PendingTranscriptionItem> = emptyList()
    override suspend fun loadAudio(id: String): ByteArray? = null
    override suspend fun markSucceeded(id: String) = Unit
    override suspend fun markFailure(id: String, errorMessage: String): PendingTranscriptionItem? = null
    override suspend fun discard(id: String) = Unit
    override suspend fun saveAsAudioFile(id: String): String? = null
    override suspend fun reconcile() = Unit
}

/**
 * Issue #180: always-online stub for
 * [PromptComposerViewModel.ConnectivityProbe]. Used by older tests that
 * predate #180 — every recording is treated as "device has network",
 * which matches the pre-#180 codepath (always try Whisper). Production
 * wiring always provides the real [ConnectivityObserver].
 */
public object AlwaysOnlineConnectivityProbe : PromptComposerViewModel.ConnectivityProbe {
    override fun refresh(): Boolean = true
}
