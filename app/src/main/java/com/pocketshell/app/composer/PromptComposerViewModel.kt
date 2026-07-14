package com.pocketshell.app.composer

import android.Manifest
import android.net.Uri
import androidx.annotation.RequiresPermission
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.app.diagnostics.DiagnosticEvents
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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
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
import kotlinx.coroutines.withContext
import javax.inject.Inject
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID

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
    // Issue #832: durable per-session draft store. Defaults to the no-op
    // stub so the rich existing unit/connected tests that build the
    // ViewModel directly keep compiling untouched; production wiring in
    // `VoiceModule` always provides the SharedPreferences-backed store so a
    // draft survives a session switch (and a process-death recreate) keyed
    // by the session it was authored in. See [ComposerDraftStore].
    internal val composerDraftStore: ComposerDraftStore = DisabledComposerDraftStore,
    // Issue #900: durable outbound queue surface. Defaults to a no-op store so
    // direct unit/connected constructors stay source-compatible; production
    // Hilt wiring provides the SharedPreferences-backed store.
    internal val outboundQueueStore: OutboundQueueStore = DisabledOutboundQueueStore,
    internal val outboundAttachmentSidecarStore: OutboundAttachmentSidecarStore? = null,
    internal val savedStateHandle: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {

    internal val _uiState: MutableStateFlow<UiState> = MutableStateFlow(
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

    private val _outboundQueueItems: MutableStateFlow<List<OutboundItem>> =
        MutableStateFlow(emptyList())

    /**
     * Issue #900: visible committed-send queue for the currently targeted
     * session. The store is intentionally synchronous/non-reactive, so this
     * StateFlow is refreshed at explicit session-change and delete boundaries.
     */
    public val outboundQueueItems: StateFlow<List<OutboundItem>> =
        _outboundQueueItems.asStateFlow()

    private var recordingJob: Job? = null
    private var transcribeJob: Job? = null

    /**
     * Issue #880: the wall-clock elapsed-timer ticker for the Android
     * SpeechRecognizer recording path. The Whisper path already ticks
     * [UiState.recordingElapsedMs] from inside its amplitude sampler loop
     * ([sampleAmplitudeAndAutoStopOnSilence]); the Android recognizer path has
     * no audio-capture source feeding that loop, so the timer used to sit
     * frozen at `00:00`. This job advances the timer from [recordingStartedAtMs]
     * on a fixed [SAMPLE_INTERVAL_MS] cadence, independent of any PCM clock, so
     * the MM:SS counter increments for the recognizer path too. Cancelled the
     * moment recording ends (stop / final / error / cancel / clear).
     */
    private var recordingTimerJob: Job? = null

    /**
     * Issue #746: the session/pane this composer instance is currently
     * targeting (`"<hostId>/<sessionName>"`). The composer ViewModel is
     * activity-scoped and shared across every session on a host, so a draft
     * (and especially a "Not sent" banner) authored in session A used to
     * bleed into session B on a switch. The host screen calls
     * [onComposerTargetChanged] whenever the focused session changes; when
     * the new target differs from the one that owns the current draft, the
     * stale draft is discarded so it never appears in a session it was not
     * authored in. Null until the host wires the first target.
     */
    internal var composerTarget: String? = null

    /**
     * Issue #688: ids of pending-transcription rows with a retry round-trip
     * currently in flight. A row's id is claimed synchronously when
     * [retryPending] is called (an atomic check-and-add) and released in the
     * coroutine's `finally`. In production every trigger arrives on the
     * single-threaded main dispatcher, but the set is a thread-safe
     * concurrent set so the claim is correct even if a caller ever dispatches
     * a retry off-Main — the dedupe must never lose to a data race, since a
     * lost claim is exactly the duplicate-insert bug this issue fixes.
     */
    private val inFlightRetryIds: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()
    private var activeProvider: VoiceTranscriptionProvider? = null

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
    internal val _sendRequests = Channel<SendRequest>(capacity = Channel.BUFFERED)
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
    private var pendingSendTarget: SendTargetSnapshot = SendTargetSnapshot()
    private var attachmentJob: Job? = null
    internal var outboundAttachmentUploader: (suspend (List<LocalAttachmentSidecarRef>) -> Result<List<String>>)? =
        null
    internal var outboundSidecarDispatchInFlight: Boolean = false
    internal var outboundQueueDispatcher: CoroutineDispatcher = Dispatchers.IO
    private val draftPersistence = ComposerDraftPersistence(
        store = composerDraftStore,
        scope = viewModelScope,
        dispatcher = { outboundQueueDispatcher },
    )

    /**
     * Issue #971: the [SendRequest] for the prompt currently in flight, captured
     * at handoff. Because the editable composer is now CLEARED on enqueue (the
     * queue row is the single representation), the live `_uiState.draft` is no
     * longer the source for restoring a wedged send. The non-delivering recovery
     * paths — the #891 overall-send watchdog ([onSendWatchdogExpired]), the #929
     * stranded-dispatch clears, and a structured-concurrency cancellation —
     * therefore reconstruct the prompt from THIS captured request so the exact
     * draft + attachment tiles still return to the (now-single) composer instead
     * of an empty one. Set the instant a send goes in-flight; cleared the instant
     * it resolves (delivered / failed / strand-cleared / discarded).
     */
    internal var inFlightSendRequest: SendRequest? = null

    internal val watchdogs = PromptComposerWatchdogs(
        scope = viewModelScope,
        sendTimeoutMs = OVERALL_SEND_TIMEOUT_MS,
        transcribeTimeoutMs = TRANSCRIBE_TIMEOUT_MS,
        onSendExpired = ::onSendWatchdogExpired,
        onTranscribeExpired = ::onTranscribeWatchdogExpired,
    )

    /**
     * Issue #891 test seam. `timeoutMs = null` disables the watchdog for unit
     * tests that are not about it (so they are unaffected); a small value drives
     * the watchdog deterministically under virtual time.
     */
    @androidx.annotation.VisibleForTesting
    internal fun setSendWatchdogTimeoutForTest(timeoutMs: Long?) {
        watchdogs.setSendTimeoutForTest(timeoutMs)
    }

    /**
     * Issue #939 test seam. `timeoutMs = null` disables the transcribe watchdog
     * for unit tests not about it; a small value drives it deterministically
     * under virtual time.
     */
    @androidx.annotation.VisibleForTesting
    internal fun setTranscribeWatchdogTimeoutForTest(timeoutMs: Long?) {
        watchdogs.setTranscribeTimeoutForTest(timeoutMs)
    }

    /**
     * Issue #1569 test seam: whether the attach-time staging job is still active.
     * Used to prove that removing the absolute 90s cap (U2) leaves no orphaned
     * "zombie" upload job after a failed/settled pick.
     */
    @androidx.annotation.VisibleForTesting
    internal fun isAttachmentJobActiveForTest(): Boolean = attachmentJob?.isActive == true

    /**
     * Issue #900: upload hook for durable outbound attachment sidecars. The host
     * owns the live SSH/session upload primitive; the ViewModel owns when queued
     * local bytes must be uploaded before a row is claimed for delivery.
     */
    public fun setOutboundAttachmentSidecarUploader(
        uploader: (suspend (List<LocalAttachmentSidecarRef>) -> Result<List<String>>)?,
    ) {
        outboundAttachmentUploader = uploader
    }

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

    private val androidSpeechRecognition = AndroidSpeechRecognitionDelegate(
        speechRecognitionProvider = speechRecognitionProvider,
        clock = { clock() },
        callbacks = object : AndroidSpeechRecognitionDelegate.Callbacks {
            override fun currentDraft(): String = _uiState.value.draft

            override fun updateUi(
                transform: (UiState) -> UiState,
            ) {
                _uiState.update { transform(it) }
            }

            override fun persistDraft(draft: String) {
                savedStateHandle[KEY_DRAFT] = draft
            }

            override fun onRecordingStarted(startedAtMs: Long) {
                recordingStartedAtMs = startedAtMs
            }

            override fun onSessionStarted() {
                activeProvider = VoiceTranscriptionProvider.AndroidSpeech
            }

            override fun onSessionCleared() {
                activeProvider = null
            }

            override fun setWasRecording(wasRecording: Boolean) {
                savedStateHandle[KEY_WAS_RECORDING] = wasRecording
            }

            override fun startRecordingTimerTicker() {
                this@PromptComposerViewModel.startRecordingTimerTicker()
            }

            override fun stopRecordingTimerTicker() {
                this@PromptComposerViewModel.stopRecordingTimerTicker()
            }

            override fun clearPendingTranscriptionSend() {
                this@PromptComposerViewModel.clearPendingTranscriptionSend()
            }

            override fun consumePendingTranscriptionSend(): AndroidSpeechRecognitionDelegate.PendingSend {
                val pending = AndroidSpeechRecognitionDelegate.PendingSend(
                    enabled = pendingSendOnTranscribeSuccess,
                    withEnter = pendingSendWithEnter,
                    target = pendingSendTarget,
                )
                clearPendingTranscriptionSend()
                return pending
            }

            override fun dispatchSendNow(withEnter: Boolean, target: SendTargetSnapshot) {
                this@PromptComposerViewModel.dispatchSendNow(withEnter, target)
            }
        },
    )

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
        // Issue #746: stamp the session that owns this draft so a later
        // session switch can tell whether the draft belongs to the newly
        // focused session (keep it) or was authored elsewhere (discard it).
        // An empty draft has no owner — clearing it releases the stamp.
        savedStateHandle[KEY_DRAFT_OWNER] = if (newText.isEmpty()) null else composerTarget
        // Issue #832: mirror the live draft into the durable per-session
        // store so it survives a session switch (the SavedStateHandle slot is
        // a single owner-stamped slot the #746 switch discards). Keyed by the
        // session that owns this draft; nothing to persist when no target is
        // wired yet (proof-of-life / pre-target restore).
        saveComposerDraft(composerTarget, newText)
        _uiState.update { it.copy(draft = newText, error = null) }
    }

    private fun loadComposerDraft(sessionKey: String): String? {
        return draftPersistence.load(sessionKey)
    }

    private fun saveComposerDraft(sessionKey: String?, draft: String) {
        draftPersistence.save(sessionKey, draft)
    }

    internal fun clearComposerDraft(sessionKey: String?) {
        draftPersistence.clear(sessionKey)
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
        if (count <= 0) return
        // #1531 (RC2): a pick while a prior upload stages used to drop SILENTLY.
        if (attachmentJob?.isActive == true) {
            _uiState.update { it.copy(error = ATTACHMENT_UPLOAD_BUSY_MESSAGE) }
            return
        }
        DiagnosticEvents.record("action", "attachment_stage_start", "count" to count)
        attachmentJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    attachmentUpload = AttachmentUploadState.Uploading(count),
                    error = null,
                )
            }
            val result: Result<List<String>> = try {
                // Issue #1569 (U2): the absolute 90s whole-batch cap
                // (ATTACHMENT_UPLOAD_TIMEOUT_MS) KILLED a progressing large upload
                // on a healthy link (a 52MB batch needs >90s) AND — because the cap
                // only cancelled the AWAIT, not the detached #1072 upload deferred —
                // left a ZOMBIE that kept uploading after the cap fired. Removed:
                // the staging is bounded by its inner budgets (the per-file SAF read
                // timeout + core-ssh's progress-based 60s-no-progress upload bound,
                // total unbounded while bytes move), so a progressing transfer is
                // never killed by wall-clock alone and no zombie is orphaned.
                stage()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                Result.failure(t)
            }
            result.fold(
                onSuccess = { paths ->
                    DiagnosticEvents.record(
                        "action",
                        "attachment_stage_success",
                        "requestedCount" to count,
                        "stagedCount" to paths.count { it.isNotBlank() },
                    )
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
                    mergeStagedPaths(paths, previews, error = null)
                },
                onFailure = { error ->
                    // Issue #570: a partial multi-file failure still carries
                    // the files that DID upload. Attach those survivors as
                    // tiles AND surface the failure, instead of discarding
                    // every upload because one image stalled/failed.
                    val partial = error as? PartialAttachmentUploadException
                    if (partial != null && partial.uploadedPaths.isNotEmpty()) {
                        DiagnosticEvents.record(
                            "action",
                            "attachment_stage_partial",
                            "requestedCount" to count,
                            "stagedCount" to partial.uploadedPaths.count { it.isNotBlank() },
                            "failedCount" to partial.failedCount,
                        )
                        mergeStagedPaths(
                            partial.uploadedPaths,
                            previews,
                            error = partial.message,
                        )
                        return@fold
                    }
                    DiagnosticEvents.record(
                        "action",
                        "attachment_stage_fail",
                        "requestedCount" to count,
                        "cause" to error.javaClass.simpleName,
                        "message" to error.message,
                    )
                    // Issue #1569 (U1 — P0 DATA LOSS): the picked files must NOT be
                    // dropped when the (attach-time) upload fails. Before #1569 this
                    // branch set Idle + an error and DISCARDED the picked URIs — no
                    // tile, no durable bytes, no queue row, no Retry — so a mid-stream
                    // teardown ("Stream closed") LOST the maintainer's attachment with
                    // the false "Your draft was kept" copy (true only for draft TEXT).
                    // Now we RETAIN the pick durably: stage the picked bytes as durable
                    // local sidecars and add persisted tiles backed by them, so the
                    // failed upload leaves a RETRYABLE representation — the send-time
                    // queue leg (#1540 write-ahead, #1531 badge/Retry, #1554
                    // exactly-once) uploads it on Send and auto-retries on reconnect.
                    if (!retainFailedPickDurably(previews, error)) {
                        _uiState.update {
                            it.copy(
                                attachmentUpload = AttachmentUploadState.Idle,
                                error = attachmentErrorMessage(error),
                            )
                        }
                    }
                },
            )
        }
    }

    /**
     * Issue #1569 (U1): retain a pick whose upload FAILED as a durable, retryable
     * representation instead of losing it. Stages the picked bytes as durable local
     * sidecars keyed to the composer draft (survives teardown / reconnect / process
     * death), adds a tile per retained file backed by those bytes, and persists the
     * tiles to the per-session [composerDraftStore]. A subsequent Send routes them
     * through the durable, auto-retrying, exactly-once send-time upload leg.
     *
     * Returns `true` when at least one file was durably retained (the caller shows
     * the "saved — will upload on Send" copy); `false` when nothing could be retained
     * (no local URIs / no sidecar store), so the caller falls back to the plain
     * error banner.
     */
    private suspend fun retainFailedPickDurably(
        previews: List<AttachmentPreview>,
        error: Throwable,
    ): Boolean {
        val sidecarStore = outboundAttachmentSidecarStore ?: return false
        val target = composerTarget?.takeIf { it.isNotBlank() } ?: return false
        val uris = previews.map { it.uri }
        if (uris.isEmpty()) return false
        val scope = draftAttachmentSidecarScope(target)
        // Fresh durable bytes for this draft scope: drop any stale draft sidecars
        // first so a re-pick after a prior failure does not accumulate orphans.
        sidecarStore.removeOutboundItem(scope)
        val sidecars = sidecarStore.stage(
            outboundItemId = scope,
            uris = uris,
            attachmentIndices = uris.indices.toList(),
        )
        if (sidecars.isEmpty()) return false
        val retainedTiles = sidecars.mapIndexed { index, ref ->
            StagedAttachment(
                // The provisional remote path is unique + non-blank so the tile
                // de-dupes and composes; the send-time sidecar upload REPLACES it
                // with the authoritative uploaded path (withUploadedSidecars), so it
                // never reaches the wire.
                remotePath = pendingAttachmentRemotePath(scope, ref.attachmentIndex ?: index, ref.displayName),
                displayName = ref.displayName,
                previewUri = previews.getOrNull(ref.attachmentIndex ?: index)?.uri
                    ?: android.net.Uri.fromFile(java.io.File(ref.localPath)),
                mimeType = ref.mimeType ?: previews.getOrNull(ref.attachmentIndex ?: index)?.mimeType,
            )
        }
        DiagnosticEvents.record(
            "action",
            "attachment_stage_fail_retained",
            "retainedCount" to retainedTiles.size,
            "cause" to error.javaClass.simpleName,
        )
        _uiState.update { current ->
            val merged = (current.attachments + retainedTiles)
                .distinctBy { it.remotePath }
            current.copy(
                attachments = merged,
                attachmentUpload = AttachmentUploadState.Idle,
                error = attachmentRetainedMessage(error),
            )
        }
        // Issue #746/#1569: stamp the draft owner so a later session switch treats
        // these retained tiles as BELONGING to this session (persist + reload on
        // return) rather than as an unowned orphan the FIRST switch adopts into the
        // wrong session (the #746 orphan-adopt path).
        savedStateHandle[KEY_DRAFT_OWNER] = target
        // Persist the retained tiles so they survive a session switch A→B→A AND a
        // process-death recreate (the durable draft sidecar bytes above survive the
        // process too; [rehydrateDraftAttachmentBytes] reconnects them on restore).
        composerDraftStore.saveAttachments(target, _uiState.value.attachments.toDurableRefs())
        return true
    }

    /**
     * Issue #1569 (U1): after a process-death restore or a session switch back, the
     * persisted tiles carry no live preview Uri ([ComposerDraftStore] intentionally
     * drops it). For a retained-on-failure tile (a provisional `pending-upload-…`
     * path) the durable BYTES still exist in the draft sidecar store, so reconnect
     * the tile to them (`previewUri` = the local sidecar file) — otherwise the next
     * Send would fall to the non-sidecar path and try to deliver the broken
     * provisional path. Best-effort + async: matches the retained (pending-upload,
     * previewUri-less) tiles to the ordered draft sidecars.
     */
    private fun rehydrateDraftAttachmentBytes(target: String) {
        val sidecarStore = outboundAttachmentSidecarStore ?: return
        if (target.isBlank()) return
        val hasRetainedTiles = _uiState.value.attachments.any {
            it.previewUri == null && isPendingUploadRemotePath(it.remotePath)
        }
        if (!hasRetainedTiles) return
        viewModelScope.launch(outboundQueueDispatcher) {
            val refs = sidecarStore.refsFor(draftAttachmentSidecarScope(target))
            if (refs.isEmpty()) return@launch
            withContext(Dispatchers.Main.immediate) {
                if (composerTarget != target) return@withContext
                _uiState.update { current ->
                    val queue = ArrayDeque(refs)
                    val rehydrated = current.attachments.map { tile ->
                        if (tile.previewUri != null || !isPendingUploadRemotePath(tile.remotePath)) {
                            return@map tile
                        }
                        val ref = queue.removeFirstOrNull() ?: return@map tile
                        tile.copy(previewUri = android.net.Uri.fromFile(java.io.File(ref.localPath)))
                    }
                    if (rehydrated == current.attachments) current else current.copy(attachments = rehydrated)
                }
            }
        }
    }

    /**
     * Issue #1569 (U1): drop the durable draft-sidecar bytes for [target] once the
     * retained tiles no longer need them (handed off to the queue on Send, or the
     * draft was discarded). Best-effort; a leftover set is also swept by the store's
     * periodic [OutboundAttachmentSidecarStore.reconcile].
     */
    internal fun clearDraftAttachmentSidecars(target: String?) {
        val sidecarStore = outboundAttachmentSidecarStore ?: return
        val key = target?.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch(outboundQueueDispatcher) {
            sidecarStore.removeOutboundItem(draftAttachmentSidecarScope(key))
        }
    }

    /**
     * Issue #544/#570: merge newly staged remote paths into the tile list,
     * de-duplicating by remote path. Used by both the all-success and the
     * partial-failure branches so survivors are attached identically. The
     * draft text is never touched; [error] is set verbatim (null on full
     * success, the partial-failure banner otherwise).
     */
    private fun mergeStagedPaths(
        paths: List<String>,
        previews: List<AttachmentPreview>,
        error: String?,
    ) {
        _uiState.update { current ->
            current.copy(
                attachments = mergeStagedAttachmentPaths(
                    currentAttachments = current.attachments,
                    paths = paths,
                    previews = previews,
                ),
                attachmentUpload = AttachmentUploadState.Idle,
                error = error,
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
        var seeded = false
        _uiState.update { current ->
            if (current.attachments.any { it.remotePath == trimmed }) {
                current
            } else {
                seeded = true
                current.copy(
                    attachments = current.attachments +
                        StagedAttachment(
                            remotePath = trimmed,
                            displayName = attachmentDisplayName(trimmed),
                        ),
                )
            }
        }
        if (seeded) {
            DiagnosticEvents.record(
                "action",
                "attachment_seeded_from_share",
                "attachmentCount" to _uiState.value.attachments.size,
            )
        }
    }

    /**
     * Issue #763: pre-load the composer draft with a ready prompt routed in from
     * the file viewer's "Attach to current session" action (e.g. "Apply the
     * PocketShell review at <path>"). The composer is activity-scoped, so the
     * file viewer round-trip and the back-navigation to the session preserve
     * this instance; seeding here lands the prompt in the field the user returns
     * to, ready to edit and Send.
     *
     * If the user already has a draft typed, the prompt is appended on its own
     * line so nothing they wrote is lost (mirrors the dictation-append rule);
     * an empty draft is replaced outright. A blank [prompt] is a no-op.
     */
    public fun seedDraftPrompt(prompt: String) {
        val combined = draftWithSeededPrompt(_uiState.value.draft, prompt) ?: return
        onDraftChange(combined)
        DiagnosticEvents.record("action", "review_prompt_seeded_into_composer")
    }

    /**
     * Issue #770: pre-fill the composer with an engine command the user tapped
     * in the terminal (e.g. Claude Code's `/clear`). The command is laid in as
     * the leading slash token exactly like an autocomplete pick — if the draft
     * already starts with a `/`-token it is REPLACED, otherwise the command is
     * prepended and any text the user already typed is preserved after it. The
     * sheet's [TextFieldValue] re-sync places the caret at the end of the draft,
     * so the user lands ready to review and Send. A blank [command] is a no-op.
     *
     * This mirrors [SlashCommandAutocomplete.insertCommandText] at the
     * draft-string level so the tap path and the dropdown pick share one
     * insert contract (the issue's "reuse the #767 insert path" requirement).
     */
    public fun prefillEngineCommand(command: String) {
        val updatedDraft = draftWithPrefilledEngineCommand(_uiState.value.draft, command) ?: return
        onDraftChange(updatedDraft)
        DiagnosticEvents.record("action", "engine_command_tapped_into_composer")
    }

    /**
     * Issue #544/#566: remove a single staged attachment tile by its remote path.
     * The draft text is untouched; removing every tile leaves the prompt
     * exactly as the user typed it, so the "Attached files:" suffix is only
     * composed at send time from whatever tiles remain.
     */
    public fun removeAttachment(remotePath: String) {
        var removed = false
        var beforeCount = 0
        _uiState.update { current ->
            beforeCount = current.attachments.size
            val filtered = current.attachments.filterNot { it.remotePath == remotePath }
            if (filtered.size == current.attachments.size) {
                current
            } else {
                removed = true
                current.copy(attachments = filtered)
            }
        }
        if (removed) {
            DiagnosticEvents.record(
                "action",
                "attachment_remove",
                "beforeCount" to beforeCount,
                "afterCount" to _uiState.value.attachments.size,
            )
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
            RecordingState.Transcribing -> DiagnosticEvents.record(
                "action",
                "composer_recording_tap_ignored",
                "provider" to (activeProvider?.name ?: "unknown"),
            )
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
    public fun requestSend(
        withEnter: Boolean,
        sendTarget: SendTargetSnapshot = SendTargetSnapshot(),
    ) {
        when (_uiState.value.recording) {
            RecordingState.Idle -> dispatchSendNow(withEnter, sendTarget)
            RecordingState.Recording -> {
                pendingSendOnTranscribeSuccess = true
                pendingSendWithEnter = withEnter
                pendingSendTarget = sendTarget
                stopAndTranscribe()
            }
            RecordingState.Transcribing -> {
                pendingSendOnTranscribeSuccess = true
                pendingSendWithEnter = withEnter
                pendingSendTarget = sendTarget
            }
        }
    }

    private fun clearPendingTranscriptionSend() {
        pendingSendOnTranscribeSuccess = false
        pendingSendWithEnter = false
        pendingSendTarget = SendTargetSnapshot()
    }

    /**
     * Issue #211 / #745: emit a [SendRequest] for the current draft and
     * mark the send IN-FLIGHT — the draft and any staged attachments are
     * deliberately NOT cleared here. The composer keeps the typed text
     * visible (with Send disabled + a "Sending…" affordance) until the
     * host confirms delivery via [markSendDelivered] or reports failure via
     * [restoreFailedSend]. This eliminates the pre-#745 clear-then-restore
     * flicker where the field emptied optimistically and only refilled
     * (async) once a degraded-connection send failed, leaving the user
     * staring at a blank composer with no idea anything was wrong.
     *
     * No-op when the composed text is empty — the FSM-Idle case where the
     * user has nothing to send, which the UI normally already gates via the
     * Send button's `enabled` predicate. Tests can still call this directly;
     * the empty guard means a hostile caller cannot fire a blank send. Also
     * a no-op when a send is already in flight so a double-tap can't queue a
     * second request while the first is still resolving.
     */
    private fun dispatchSendNow(
        withEnter: Boolean,
        sendTarget: SendTargetSnapshot = SendTargetSnapshot(),
    ) {
        if (_uiState.value.sendInFlight) return
        // Issue #872 (Part B reopen): a Send while an attachment upload is STILL in
        // flight must NOT cancel-and-drop into a text-only send (the silent
        // attachment loss). WAIT for the upload, then send WITH the staged tiles —
        // or, on upload failure, surface the error and keep the draft (no silent
        // text-only send). The user never loses the attachment.
        if (attachmentJob?.isActive == true) {
            dispatchSendAfterUpload(withEnter, sendTarget)
            return
        }
        emitSendRequest(withEnter, sendTarget)
    }

    /**
     * Issue #872: the user tapped Send while an attachment upload was still in
     * flight. Park the composer in the in-flight state immediately (so the Send
     * button disables + shows "Sending…" and a double-tap can't queue a second
     * send), then await the upload before dispatching. On upload success the
     * freshly-staged tiles are included in the send; on upload failure NO send
     * goes out — the error banner is left in place and the draft is kept so the
     * user can re-attach and retry. This is what closes the on-device silent
     * drop: there is no code path that emits a text-only send while an attachment
     * was being uploaded.
     */
    private fun dispatchSendAfterUpload(
        withEnter: Boolean,
        sendTarget: SendTargetSnapshot,
    ) {
        DiagnosticEvents.record("action", "composer_send_wait_upload")
        // Flip into the in-flight state up front so the UI reflects "Sending…"
        // and the in-flight guard blocks a double-tap while we await the upload.
        _uiState.update { it.copy(sendInFlight = true, error = null) }
        // Issue #891: arm the overall-send watchdog from the MOMENT we go
        // in-flight — this is the with-attachment path the maintainer hit, where
        // a wedged upload-await could otherwise leave "Sending…" stuck forever.
        // emitSendRequest re-arms it (idempotent) once the upload resolves.
        watchdogs.armSend()
        val job = attachmentJob ?: return emitSendRequest(withEnter, sendTarget)
        viewModelScope.launch {
            // Await the upload coroutine itself; `attachFiles` resolves the UI
            // state (tiles staged on success, error banner on failure) before it
            // completes, so once `join()` returns the state is settled.
            job.join()
            val staged = _uiState.value.attachments
            if (staged.isEmpty()) {
                // The upload failed (or staged nothing) — `attachFiles` has
                // already set the error banner / reset the progress. Do NOT fire a
                // text-only send that would silently drop the attachment the user
                // staged. Leave the draft + error so they can re-attach and retry.
                DiagnosticEvents.record("action", "composer_send_wait_upload_no_attachment")
                // Issue #891: the send resolved (to "no send") — disarm the
                // overall-send watchdog so it cannot later fire a spurious
                // "Send failed" on an already-settled composer.
                watchdogs.disarmSend()
                _uiState.update { it.copy(sendInFlight = false) }
                return@launch
            }
            // The upload completed and the tiles are staged — clear the in-flight
            // flag so `emitSendRequest` can re-set it on the real dispatch (its
            // own #745 in-flight contract), then dispatch WITH the attachment.
            _uiState.update { it.copy(sendInFlight = false) }
            emitSendRequest(withEnter, sendTarget)
        }
    }

    /**
     * Issue #745: the host confirmed the send was delivered. Now — and only
     * now — clear the draft, drop the staged attachment tiles, and leave the
     * in-flight state. The sheet's `sendRequests` collector calls this on the
     * SUCCESS branch right before it dismisses the composer, so the next
     * composer open is a fresh slate. Splitting the clear out of
     * [dispatchSendNow] is what removes the optimistic-empty flicker: the
     * field never empties until the bytes are actually delivered.
     */
    public fun markSendDelivered(request: SendRequest? = null) {
        // Issue #891: the send resolved successfully — disarm the overall-send
        // watchdog so it cannot fire a spurious "Send failed" afterwards.
        watchdogs.disarmSend()
        // Issue #971: the in-flight send resolved — drop the captured request so a
        // later watchdog/strand cannot restore a stale prompt.
        inFlightSendRequest = null
        markOutboundSendDelivered(request)
        val deliveryTarget = request?.sendTarget?.sessionKey?.takeIf { it.isNotBlank() } ?: composerTarget
        if (deliveryTarget != null && deliveryTarget != composerTarget) {
            clearComposerDraft(deliveryTarget)
            composerDraftStore.clearAttachments(deliveryTarget)
            _uiState.update {
                it.copy(
                    sendInFlight = false,
                    attachmentUpload = AttachmentUploadState.Idle,
                )
            }
            return
        }
        _uiState.update {
            it.copy(
                sendInFlight = false,
                attachments = emptyList(),
                attachmentUpload = AttachmentUploadState.Idle,
            )
        }
        // Issue #872: a delivered send must also drop the durable per-session
        // attachment refs (the sibling of clearing the draft text below) so a
        // later switch back does not resurrect already-sent tiles.
        composerTarget?.let { composerDraftStore.clearAttachments(it) }
        onDraftChange("")
    }

    /**
     * Issue #390 / #745: the host reported the target is disconnected or the
     * write failed (including a bounded-timeout hang — see the sheet's
     * `withTimeoutOrNull` wrap). Put the exact payload back in the draft,
     * leave the in-flight state, and keep a visible status message so the
     * user can retry after reconnecting or discard it intentionally. Because
     * [dispatchSendNow] no longer clears the draft optimistically, the field
     * never visibly emptied — this just stamps the "Not sent" banner and
     * restores the original draft + attachment tiles.
     *
     * Issue #872 — close the #832 AC2 gap: a failed send no longer collapses
     * the staged attachment into a paths-folded-into-text proxy. We restore the
     * CLEAN draft ([SendRequest.cleanDraft]) and re-show the actual attachment
     * TILES ([SendRequest.attachments]), and persist BOTH durably per-session,
     * so Retry re-sends the original text + attachment (the send path re-appends
     * the paths from the live tiles at send time) and a switch A→B→A reloads
     * them instead of finding them gone.
     */
    public fun restoreFailedSend(
        request: SendRequest,
        message: String = "Not sent. Reconnect, then send again or discard the draft.",
    ) {
        if (request.text.isEmpty()) {
            watchdogs.disarmSend()
            inFlightSendRequest = null
            _uiState.update { it.copy(sendInFlight = false, attachmentUpload = AttachmentUploadState.Idle) }
            return
        }
        // Issue #891: the send resolved (to failure) — disarm the overall-send
        // watchdog so the retryable failed state we are about to set is not
        // immediately re-stamped by a late watchdog firing.
        watchdogs.disarmSend()
        // Issue #971: the in-flight send resolved — drop the captured request so a
        // later watchdog/strand cannot restore it a second time.
        inFlightSendRequest = null
        markOutboundSendFailed(request, message)
        // Issue #872: restore the CLEAN draft (no appended "Attached files:"
        // block) so the editable text is not polluted with raw remote paths and
        // the tiles are NOT double-appended on Retry. Fall back to the composed
        // text only for legacy callers that constructed a bare SendRequest.
        val restoredDraft = request.cleanDraft.ifEmpty {
            if (request.attachments.isEmpty()) request.text else ""
        }
        val restoredAttachments = request.attachments
        val restoreTarget = request.sendTarget.sessionKey.takeIf { it.isNotBlank() } ?: composerTarget
        if (restoreTarget != null && restoreTarget != composerTarget) {
            saveComposerDraft(restoreTarget, restoredDraft)
            composerDraftStore.saveAttachments(restoreTarget, restoredAttachments.toDurableRefs())
            _uiState.update { current ->
                current.copy(
                    recording = RecordingState.Idle,
                    amplitude = 0f,
                    sendInFlight = false,
                    attachmentUpload = AttachmentUploadState.Idle,
                )
            }
            return
        }
        savedStateHandle[KEY_DRAFT] = restoredDraft
        // Issue #746: a restored "Not sent" draft belongs to the session it
        // was sent from. Stamp the owner so switching away discards it rather
        // than carrying the banner into an unrelated session.
        savedStateHandle[KEY_DRAFT_OWNER] = restoreTarget
        // Issue #832 / #872: the failed-send draft + staged attachments are the
        // EXACT case the maintainer reported losing. Persist BOTH to the durable
        // per-session store so switching away and back reloads them (the "Your
        // draft was kept" promise becomes recoverable, not just true for an
        // instant — and now covers the attachment, not just the text).
        restoreTarget?.let { target ->
            saveComposerDraft(target, restoredDraft)
            composerDraftStore.saveAttachments(target, restoredAttachments.toDurableRefs())
        }
        _uiState.update { current ->
            current.copy(
                draft = restoredDraft,
                error = message,
                recording = RecordingState.Idle,
                amplitude = 0f,
                sendInFlight = false,
                // Issue #872: re-show the actual attachment tiles so Retry
                // re-sends the original attachment, not just the text.
                attachments = restoredAttachments,
                attachmentUpload = AttachmentUploadState.Idle,
            )
        }
    }

    /**
     * Issue #971/#987 (maintainer decision — Option A): the canonical
     * drop/wedge-failure path for a send that owns a durable outbound queue row.
     * A send that fails because the connection dropped (or wedged past the
     * watchdog) must NOT return to the composer for a manual resend — it STAYS
     * QUEUED and auto-sends on reconnect (the #900 flush). This:
     *
     *  - re-arms the durable row to [OutboundState.Queued] and clears its error
     *    (the single "Will send when reconnected." status),
     *  - clears the in-flight gates + watchdog so the next foreground/reconnect
     *    flush ([retryNextOutboundItem]) can re-claim it,
     *  - leaves the composer EMPTY (the prompt lives in the queue row — the
     *    single representation; #971) and sets NO "Not sent / send again or
     *    discard" error banner.
     *
     * Falls back to [restoreFailedSend] only when the request has no durable
     * queue row ([SendRequest.outboundQueueItemId] is null — e.g. the composer
     * is wired to [DisabledOutboundQueueStore], or an upload-await wedge that
     * failed before the row was enqueued): there is then nothing to keep queued,
     * so the prompt must come back to the composer rather than be silently lost.
     * The user can still cancel/discard a queued item from the queue surface.
     */
    public fun markOutboundSendDeferred(
        request: SendRequest,
        // The message used ONLY when there is no durable queue row and we fall
        // back to [restoreFailedSend] (the prompt comes back to the composer
        // instead of being lost). The deferred (row-kept) path never sets an
        // error — its single status is the queue row's "Will send when
        // reconnected." Defaults to the calm reconnect copy.
        noRowFallbackMessage: String = "Not sent. Reconnect, then send again or discard the draft.",
    ) {
        // Issue #891: the send resolved (deferred to the queue) — disarm the
        // overall-send watchdog so it cannot re-stamp a stale "Send failed".
        watchdogs.disarmSend()
        // Issue #971: the in-flight send resolved — drop the captured request so a
        // later watchdog/strand cannot act on it again.
        inFlightSendRequest = null
        val id = request.outboundQueueItemId
        val requeued = id?.let { outboundQueueStore.requeueForRetry(it) }
            ?: request.sendTarget.sessionKey.takeIf { it.isNotBlank() }?.let { sessionKey ->
                outboundQueueStore.itemsFor(sessionKey).deferredRetryCandidateFor(request)
            }?.let {
                outboundQueueStore.requeueForRetry(it.id)
            }
        if (requeued == null) {
            // No durable row to keep queued — fall back to the composer-restore
            // path so the typed prompt is not silently lost.
            restoreFailedSend(request, message = noRowFallbackMessage)
            return
        }
        DiagnosticEvents.record(
            "action",
            "composer_send_deferred_to_queue",
            "attachmentCount" to request.attachments.size,
        )
        // Issue #1526 S1: clear the in-flight gate BEFORE the queue refresh —
        // the refresh emission triggers the #900 auto-flush, which self-gates
        // on `sendInFlight`; the old order made the flush skip the requeued
        // row and (the list never changing) it sat deferred forever.
        // The composer stays EMPTY; no error banner.
        _uiState.update { current ->
            current.copy(
                sendInFlight = false,
                attachmentUpload = AttachmentUploadState.Idle,
            )
        }
        request.sendTarget.sessionKey
            .takeIf { it.isNotBlank() }
            ?.let { refreshOutboundQueueItemsFor(it) }
    }

    /**
     * Issue #939: the transcribe watchdog fired — the FSM has been stuck on
     * [RecordingState.Transcribing] past its configured timeout without
     * resolving (a wedged Whisper call or an unguarded IO hang). Route back to
     * Idle, unlock the mic, and surface a retryable error so the user can record
     * again. A no-op if the FSM already left Transcribing (benign race where the
     * resolution won but the cancel had not been observed). The audio (if any) is
     * already on disk in the pending-transcription queue (#180), so the user can
     * still retry the persisted recording.
     */
    private fun onTranscribeWatchdogExpired() {
        if (_uiState.value.recording != RecordingState.Transcribing) return
        DiagnosticEvents.record(
            "action",
            "composer_transcribe_watchdog_timeout",
            "provider" to (activeProvider?.name ?: "unknown"),
        )
        transcribeJob?.cancel()
        transcribeJob = null
        clearPendingTranscriptionSend()
        activeProvider = null
        savedStateHandle[KEY_WAS_RECORDING] = false
        _uiState.update {
            it.copy(
                recording = RecordingState.Idle,
                amplitude = 0f,
                error = TRANSCRIBE_TIMEOUT_MESSAGE,
            )
        }
    }

    /**
     * Issue #929: clear the in-flight send state PROMPTLY when a send resolved
     * without delivering — the catch-all that ends the "attachment send wedges
     * all subsequent sends until restart" bug.
     *
     * #928 D5 found the sidecar-backed dispatch path has several internal
     * `return false` early-outs (queue row vanished, `markUploading` lost the
     * race, `claim` lost the race, `markAttachmentsUploaded` no longer Queued)
     * that left `sendInFlight = true` with ONLY the slow ~110s #891 watchdog to
     * clear it. While `sendInFlight` is stranded true, `dispatchSendNow` /
     * `emitSendRequest` reject EVERY subsequent send — including a plain
     * text-only one — so the user "can't send anything" until they restart.
     *
     * This clears all three in-flight gates at once: `sendInFlight`, the
     * `outboundSidecarDispatchInFlight` dispatch latch (the silent-drop window
     * at [emitSendRequest]), and the overall-send watchdog. Every non-delivering
     * exit of the dispatch path routes through here, so the pipeline self-heals
     * immediately instead of after the watchdog window. Idempotent; safe to call
     * even when no send was in flight.
     */
    internal fun clearStrandedSendInFlight(error: String? = null) {
        watchdogs.disarmSend()
        outboundSidecarDispatchInFlight = false
        // Issue #971/#987: the composer was cleared at handoff, so a non-delivering
        // strand must not silently lose the prompt. Per the maintainer's #987
        // decision (Option A) a drop/wedge keeps the prompt QUEUED and auto-retries
        // on reconnect rather than returning to the composer for manual resend.
        // When we hold the captured in-flight request, route through the deferred
        // path: a request with a durable queue row stays queued (single
        // representation, single "Will send when reconnected." status); a request
        // with no row (Disabled store / pre-enqueue wedge) falls back to the
        // composer-restore inside markOutboundSendDeferred so the prompt is not
        // lost. A passed-in [error] (a genuine non-retryable failure, e.g.
        // "could not preserve attachment bytes") still surfaces via the
        // composer-restore fallback so the user sees the reason.
        val pending = inFlightSendRequest
        if (pending != null && pending.text.isNotEmpty()) {
            if (error != null) {
                // A genuine non-retryable reason was given — surface it on the
                // composer (the bytes/attachment could not be preserved, not a
                // recoverable connection drop). This is the distinct
                // permanent-failure path from #987.
                restoreFailedSend(pending, message = error)
            } else {
                markOutboundSendDeferred(pending)
            }
            return
        }
        _uiState.update { current ->
            if (error != null) {
                current.copy(sendInFlight = false, error = error)
            } else {
                current.copy(sendInFlight = false)
            }
        }
    }

    /**
     * Issue #891: the overall-send watchdog fired — the send has been in-flight
     * past [OVERALL_SEND_TIMEOUT_MS] without resolving. This is the wedged-send
     * escape: route to the retryable failed-send state, preserving the CURRENT
     * draft + staged attachments so Retry re-sends without losing the message.
     * A no-op if the composer already left the in-flight state (a benign race
     * where the resolution callback won but the watchdog cancel had not yet
     * been observed).
     */
    private fun onSendWatchdogExpired() {
        if (!_uiState.value.sendInFlight) return
        // Issue #971: the editable composer is cleared on handoff, so prefer the
        // captured in-flight request to restore the EXACT prompt + tiles. Fall
        // back to the live state only for the upload-await wedge, where the send
        // went in-flight before the request was built and the draft is still on
        // screen.
        val request = inFlightSendRequest ?: run {
            val state = _uiState.value
            val composed = appendAttachmentPaths(state.draft, state.attachments.map { it.remotePath })
            SendRequest(
                text = composed,
                withEnter = false,
                cleanDraft = state.draft,
                attachments = state.attachments,
            )
        }
        DiagnosticEvents.record(
            "action",
            "composer_send_watchdog_timeout",
            "attachmentCount" to request.attachments.size,
        )
        // Issue #971/#987: a wedged send is a connection problem, not a permanent
        // rejection — defer it to the queue so it auto-retries on reconnect
        // (Option A). When the request owns a durable queue row this keeps it
        // queued + clears the composer; the upload-await wedge (no row) falls
        // back to the composer-restore path inside markOutboundSendDeferred with
        // the watchdog-specific copy so the typed prompt is not lost and the user
        // sees that the send timed out.
        requeueStaleOutboundInFlight(staleAfterMs = 0L)
        markOutboundSendDeferred(request, noRowFallbackMessage = SEND_TIMEOUT_MESSAGE)
    }

    /**
     * Issue #746: explicitly throw the current draft away. Wired to the
     * Discard action the "Not sent" banner instructs the user to use — and
     * the only control that actually clears a stale unsent prompt
     * (the sheet `×` deliberately preserves the draft for resend). Clears the
     * draft text, every staged attachment tile, the error/status banner, the
     * cancelled-upload state, and the persisted [SavedStateHandle] keys so the
     * next composer open is a clean slate that survives a process-death
     * recreate.
     */
    public fun discardDraft() {
        // Stop any in-flight attachment upload so it cannot write a late
        // result back into the now-cleared composer (mirrors the send path).
        attachmentJob?.cancel()
        attachmentJob = null
        // Issue #891: an explicit discard ends any in-flight send too — disarm
        // the watchdog so it cannot resurrect a "Send failed" banner over the
        // now-empty composer.
        watchdogs.disarmSend()
        // Issue #971: drop the captured in-flight request so a late
        // watchdog/strand cannot restore a prompt the user just discarded.
        inFlightSendRequest = null
        savedStateHandle[KEY_DRAFT] = ""
        savedStateHandle[KEY_DRAFT_OWNER] = null
        // Issue #832 / #872: discard is the explicit "throw it away" control, so
        // it must also drop the durable per-session draft AND attachment slots —
        // otherwise the next switch back would reload what the user just
        // discarded.
        composerTarget?.let {
            clearComposerDraft(it)
            composerDraftStore.clearAttachments(it)
            // Issue #1569 (U1): discard also drops the retained-on-failure durable
            // bytes — the user threw the prompt away, so nothing should linger.
            clearDraftAttachmentSidecars(it)
        }
        _uiState.update { current ->
            current.copy(
                draft = "",
                attachments = emptyList(),
                attachmentUpload = AttachmentUploadState.Idle,
                error = null,
            )
        }
        DiagnosticEvents.record("action", "composer_discard_draft")
    }

    /**
     * Issue #746 / #832: tell the composer which session/pane it is currently
     * targeting (`"<hostId>/<sessionName>"`). The host screen calls this when
     * the focused session changes.
     *
     * The composer ViewModel is activity-scoped and shared across every
     * session on a host, so the in-memory [UiState.draft] must only ever show
     * the CURRENT session's draft (#746 no-bleed). #832 makes that scoping
     * **durable**: instead of throwing away the outgoing session's draft on a
     * switch (the pre-#832 behaviour, which is exactly the maintainer's lost
     * failed-send draft), we
     *
     *  1. persist the outgoing session's draft to the per-session
     *     [composerDraftStore] (the live [onDraftChange] / [restoreFailedSend]
     *     writes already keep it current, so this is the safety net for the
     *     orphan-adopt case below), then
     *  2. load the INCOMING session's draft from the store into the in-memory
     *     state — empty when that session has none.
     *
     * So switching A→B→A reloads A's draft instead of finding it gone, while B
     * still never sees A's draft. A draft authored before any target was wired
     * (owner == null, e.g. a SavedStateHandle process-death restore) is
     * adopted by the first target so a legitimately-recovered prompt is not
     * dropped on the first switch callback.
     */
    public fun onComposerTargetChanged(targetKey: String?) {
        val previousTarget = composerTarget
        composerTarget = targetKey
        refreshOutboundQueueItems()
        if (targetKey == null || targetKey == previousTarget) return
        val draftOwner = savedStateHandle.get<String>(KEY_DRAFT_OWNER)
        val hasDraft = _uiState.value.draft.isNotEmpty() ||
            _uiState.value.attachments.isNotEmpty()

        // Issue #746 orphan-adopt: a draft authored before any target was
        // known (process-death restore) is attributed to the FIRST target so
        // the user is not robbed of a recovered prompt. Stamp + persist it
        // under the new owner, then keep it on screen (do not reload).
        if (hasDraft && draftOwner == null) {
            savedStateHandle[KEY_DRAFT_OWNER] = targetKey
            saveComposerDraft(targetKey, _uiState.value.draft)
            // Issue #872: the orphan draft may carry staged attachment tiles
            // (e.g. a restored failed send); adopt those under the new owner too
            // so they are recoverable on a later switch.
            composerDraftStore.saveAttachments(
                targetKey,
                _uiState.value.attachments.toDurableRefs(),
            )
            return
        }

        // Persist the outgoing session's draft + attachments under its owner so a
        // return switch can reload them. (Live writes already do this for text;
        // this covers any gap, e.g. a draft set without going through
        // [onDraftChange], and the staged-tile state #872 makes durable.)
        if (hasDraft && draftOwner != null && draftOwner != targetKey) {
            saveComposerDraft(draftOwner, _uiState.value.draft)
            composerDraftStore.saveAttachments(
                draftOwner,
                _uiState.value.attachments.toDurableRefs(),
            )
            DiagnosticEvents.record("action", "composer_persist_on_session_switch")
        }

        // Load the incoming session's durable draft + attachments (empty when
        // none) so the in-memory state shows ONLY this session's draft/tiles —
        // no bleed, but also no loss of the other session's draft (#832/#872).
        val incoming = loadComposerDraft(targetKey).orEmpty()
        val incomingAttachments = composerDraftStore.loadAttachments(targetKey)
            .toStagedAttachments()
        if (incoming == _uiState.value.draft &&
            incomingAttachments == _uiState.value.attachments &&
            _uiState.value.error == null
        ) {
            // Already showing exactly this session's draft + tiles (no banner) —
            // only (re)stamp the owner, no visual churn.
            savedStateHandle[KEY_DRAFT] = incoming
            savedStateHandle[KEY_DRAFT_OWNER] =
                if (incoming.isEmpty() && incomingAttachments.isEmpty()) null else targetKey
            // Issue #1569 (U1): a retained-on-failure tile that lost its live
            // preview Uri across process death still has durable bytes — reconnect.
            rehydrateDraftAttachmentBytes(targetKey)
            return
        }
        savedStateHandle[KEY_DRAFT] = incoming
        savedStateHandle[KEY_DRAFT_OWNER] =
            if (incoming.isEmpty() && incomingAttachments.isEmpty()) null else targetKey
        _uiState.update { current ->
            current.copy(
                draft = incoming,
                // Issue #872: restore the incoming session's durable attachment
                // tiles (not unconditionally dropped to empty). A session with
                // no stored tiles still shows none — but a failed-send session's
                // staged attachment survives the A→B→A round-trip now.
                attachments = incomingAttachments,
                attachmentUpload = AttachmentUploadState.Idle,
                error = null,
            )
        }
        // Issue #1569 (U1): reconnect any retained-on-failure tile to its durable
        // draft-sidecar bytes (the persisted tile dropped its live preview Uri), so
        // the next Send uploads the real bytes instead of the provisional path.
        rehydrateDraftAttachmentBytes(targetKey)
    }

    /**
     * Issue #900: user-visible queue cleanup. Only idle retryable rows can be
     * deleted from the composer surface; upload/in-flight rows stay visible and
     * owned by the delivery worker.
     */
    public fun discardOutboundItem(id: String) {
        val item = outboundQueueStore.item(id) ?: return
        if (!item.isComposerQueueRetryable()) return
        outboundQueueStore.remove(id)
        launchSidecarRemoval(id)
        refreshOutboundQueueItems()
    }

    /**
     * Issue #900: manually retry a durable outbound row without minting a new
     * queue id or reading the current composer draft. The row owns the original
     * payload and tap-time route; the active send dispatcher still owns
     * actual delivery and calls [markSendDelivered] / [restoreFailedSend].
     */
    public fun retryOutboundItem(id: String) {
        dispatchOutboundItem(id)
    }

    /**
     * Issue #900: foreground/reconnect queue flush. Claim one retryable row for
     * the current composer target and emit it through the same one-shot send
     * channel as manual retry. The screen calls this after each queue refresh;
     * sending one row at a time keeps [UiState.sendInFlight] as the single
     * delivery gate and prevents a failed row from spinning in a tight loop.
     */
    public fun retryNextOutboundItem(excludingIds: Set<String> = emptySet()): String? {
        if (_uiState.value.sendInFlight) return null
        val target = composerTarget?.takeIf { it.isNotBlank() } ?: return null
        val next = _outboundQueueItems.value.firstComposerQueueRetryable(
            sessionKey = target,
            excludingIds = excludingIds,
        ) ?: return null
        return if (dispatchOutboundItem(next.id)) next.id else null
    }

    /**
     * Issue #1308: re-arm EVERY resendable outbound row for the current composer
     * target back to [OutboundState.Queued] in ONE action — the batch equivalent
     * of tapping each row's Retry. After a drop/black-screen leaves several sends
     * `Failed`/queued, this lets the user re-send the whole backlog with a single
     * tap instead of one row at a time.
     *
     * Order + no-duplicate contract (reusing the existing store primitives):
     *  - Iterates [OutboundQueueStore.itemsFor], which is sorted oldest-first by
     *    [OutboundItem.createdAtMs], so the re-arm preserves original FIFO
     *    (oldest-composed-first) order.
     *  - Re-arms only the resendable states (`Queued`/`Failed`) via
     *    [OutboundQueueStore.requeueForRetry] — the SAME per-item primitive the
     *    single-row Retry uses — so no second deliverable row is ever minted.
     *  - Leaves `InFlight` rows untouched: they are actively being delivered, and
     *    re-arming one to `Queued` is exactly the double-send this must not cause.
     *  - Leaves `Uploading` rows untouched: their attachment upload is in
     *    progress. A `Delivered` row is already pruned and never resurrected.
     *
     * This does NOT itself force a send. Re-arming the rows to `Queued` and
     * refreshing the snapshot lets the SAME auto-send drain the screen already
     * runs ([retryNextOutboundItem] on every queue-snapshot change while the
     * session is live) deliver them one at a time in FIFO order — immediately
     * when the session is live, or on reconnect if currently disconnected (the
     * #900/#971 auto-send-on-reconnect path, unchanged).
     *
     * Returns the re-armed ids oldest-first, for observability/testing.
     */
    public fun resendAllQueued(): List<String> {
        val target = composerTarget?.takeIf { it.isNotBlank() } ?: return emptyList()
        val rearmedIds = outboundQueueStore.itemsFor(target)
            .composerQueueRetryableItems()
            .mapNotNull { outboundQueueStore.requeueForRetry(it.id)?.id }
        if (rearmedIds.isNotEmpty()) refreshOutboundQueueItemsFor(target)
        return rearmedIds
    }

    /**
     * Issue #900: process-death / lost-callback recovery. If a row was left
     * [OutboundState.InFlight] longer than the send watchdog window, move it
     * back to [OutboundState.Queued] so the next foreground/reconnect flush can
     * claim it exactly once.
     */
    public fun requeueStaleOutboundInFlight(
        staleAfterMs: Long = OUTBOUND_IN_FLIGHT_STALE_MS,
    ): List<OutboundItem> {
        val target = composerTarget?.takeIf { it.isNotBlank() } ?: return emptyList()
        val cutoffMs = clock() - staleAfterMs
        val requeued = outboundQueueStore.requeueStaleInFlight(target, cutoffMs)
        if (requeued.isNotEmpty()) refreshOutboundQueueItemsFor(target)
        return requeued
    }

    private fun dispatchOutboundItem(id: String): Boolean {
        if (_uiState.value.sendInFlight) return false
        if (outboundSidecarDispatchInFlight) return false
        val sidecarStore = outboundAttachmentSidecarStore
        if (sidecarStore != null) {
            outboundSidecarDispatchInFlight = true
            viewModelScope.launch(outboundQueueDispatcher) {
                try {
                    val sidecars = sidecarStore.refsFor(id)
                    if (sidecars.isNotEmpty()) {
                        dispatchPreparedOutboundItem(id)
                    } else {
                        claimAndEmitOutboundItem(id)
                    }
                } catch (cancelled: CancellationException) {
                    // Issue #929: clear the in-flight gates on cancellation so a
                    // recreated composer is not wedged, then rethrow.
                    clearStrandedSendInFlight()
                    throw cancelled
                } catch (t: Throwable) {
                    // Issue #929: an unexpected throw is a non-delivering exit —
                    // clear the strand so the next send is not wedged.
                    clearStrandedSendInFlight(
                        error = "Send failed: reconnect, then send again or discard the draft.",
                    )
                } finally {
                    outboundSidecarDispatchInFlight = false
                }
            }
            return true
        }
        outboundSidecarDispatchInFlight = true
        viewModelScope.launch(outboundQueueDispatcher) {
            try {
                claimAndEmitOutboundItem(id)
            } catch (cancelled: CancellationException) {
                clearStrandedSendInFlight()
                throw cancelled
            } catch (t: Throwable) {
                clearStrandedSendInFlight(
                    error = "Send failed: reconnect, then send again or discard the draft.",
                )
            } finally {
                outboundSidecarDispatchInFlight = false
            }
        }
        return true
    }

    internal suspend fun dispatchPreparedOutboundItem(id: String): Boolean {
        if (!uploadSidecarsForOutboundItem(id)) return false
        return claimAndEmitOutboundItem(id)
    }

    private suspend fun uploadSidecarsForOutboundItem(id: String): Boolean {
        val sidecarStore = outboundAttachmentSidecarStore ?: return true
        val sidecars = sidecarStore.refsFor(id)
        if (sidecars.isEmpty()) return true
        // Issue #929: the queue row vanished out from under this dispatch. That
        // is a non-delivering exit, so clear the in-flight gates instead of
        // stranding `sendInFlight = true` for the watchdog window.
        val item = outboundQueueStore.item(id) ?: run {
            clearStrandedSendInFlight()
            return false
        }
        val uploader = outboundAttachmentUploader
        if (uploader == null) {
            outboundQueueStore.requeueForRetry(id)
            refreshOutboundQueueItemsFor(item.sessionKey)
            clearStrandedSendInFlight()
            return false
        }
        // Issue #1588: a delivery retry used to re-transfer the FULL sidecar every
        // time (H5, #1562 audit — the #1563 52MB-re-upload pain in the send leg). Gate
        // the re-upload on the durable per-sidecar upload marker (recorded by
        // [OutboundAttachmentSidecarStore.markUploaded] ONLY after a full success): a
        // sidecar with a recorded path resumes to send; one whose upload never
        // completed re-uploads normally (exactly-once/#1554/#1569 untouched).
        val alreadyUploadedPaths = sidecars.recordedUploadPaths()
        val pendingSidecars = sidecars.filter { it.id !in alreadyUploadedPaths }
        // Whole batch already on the remote — only the DELIVERY leg failed. Skip
        // straight to send; the row stays Queued (no markUploading transition needed).
        if (pendingSidecars.isEmpty()) return true
        // Issue #929: `markUploading` lost the claim race (row no longer exactly
        // claimable). Non-delivering exit — clear the strand.
        val uploading = outboundQueueStore.markUploading(id, lastAttemptAtMs = clock())
            ?: run {
                clearStrandedSendInFlight()
                return false
            }
        refreshOutboundQueueItemsFor(uploading.sessionKey)
        // Issue #1569 (U2): DO NOT wrap the upload in the absolute 90s cap
        // (ATTACHMENT_UPLOAD_TIMEOUT_MS). A 52MB batch progressing on a healthy
        // link legitimately exceeds 90s, and the cap killed it (size-correlated
        // failure). The upload is bounded by core-ssh's progress-based budget
        // (60s no-progress, total unbounded while bytes move). The overall-send
        // watchdog ([armSend]/[OVERALL_SEND_TIMEOUT_MS]) is a wall-clock backstop
        // that would ALSO kill a progressing multi-minute upload, so disarm it for
        // the upload leg — the delivery leg re-arms it ([claimAndEmitOutboundItem]).
        // A genuinely stalled upload still fails within core-ssh's 60s no-progress
        // window and surfaces here as a [Result.failure] → requeueForRetry (durable,
        // retryable), so nothing wedges forever.
        watchdogs.disarmSend()
        // Issue #1588: upload ONLY the not-yet-uploaded sidecars.
        val result = try {
            uploader(pendingSidecars)
        } catch (cancelled: CancellationException) {
            outboundQueueStore.requeueForRetry(id)
            refreshOutboundQueueItemsFor(uploading.sessionKey)
            clearStrandedSendInFlight()
            throw cancelled
        } catch (t: Throwable) {
            Result.failure(t)
        }
        val uploadedPaths = result.getOrElse { error ->
            outboundQueueStore.requeueForRetry(id)
            refreshOutboundQueueItemsFor(uploading.sessionKey)
            clearStrandedSendInFlight()
            return false
        }.filter { it.isNotBlank() }
        if (uploadedPaths.size != pendingSidecars.size) {
            outboundQueueStore.requeueForRetry(id)
            refreshOutboundQueueItemsFor(uploading.sessionKey)
            clearStrandedSendInFlight()
            return false
        }
        // Issue #1588: durably stamp the freshly-uploaded sidecars so a later retry
        // SKIPS re-transferring them. Recorded only after a full success.
        val freshPathsBySidecarId = pendingSidecars.zip(uploadedPaths)
            .associate { (sidecar, path) -> sidecar.id to path }
        sidecarStore.markUploaded(freshPathsBySidecarId)
        val uploadedSidecarRefs = sidecars.mergedUploadedRefs(alreadyUploadedPaths, freshPathsBySidecarId)
        val updatedRefs = item.attachments.withUploadedSidecars(sidecars, uploadedSidecarRefs)
        val uploaded = outboundQueueStore.markAttachmentsUploaded(id, updatedRefs)
        if (uploaded?.state != OutboundState.Queued) {
            refreshOutboundQueueItemsFor(item.sessionKey)
            // Issue #929: post-upload state is no longer dispatchable — clear the
            // in-flight gates rather than stranding the pipeline.
            clearStrandedSendInFlight()
            return false
        }
        refreshOutboundQueueItemsFor(item.sessionKey)
        return true
    }

    private fun claimAndEmitOutboundItem(id: String): Boolean {
        // Issue #929: the claim lost the race (row already claimed/delivered/
        // gone). Non-delivering exit — clear the in-flight gates so the next
        // send is not wedged behind a stranded `sendInFlight`.
        val active = outboundQueueStore.claim(id) ?: run {
            clearStrandedSendInFlight()
            return false
        }
        val attachments = active.attachments.toStagedAttachments()
        val text = appendAttachmentPaths(active.cleanText, attachments.map { it.remotePath })
        if (text.isEmpty()) {
            outboundQueueStore.markFailed(id, lastError = "Nothing to send")
            refreshOutboundQueueItemsFor(active.sessionKey)
            clearStrandedSendInFlight()
            return false
        }
        refreshOutboundQueueItemsFor(active.sessionKey)
        val request = SendRequest(
            text = text,
            withEnter = active.withEnter,
            cleanDraft = active.cleanText,
            attachments = attachments,
            sendTarget = SendTargetSnapshot(
                sessionKey = active.sessionKey,
                paneId = active.paneId,
                route = active.route,
                agentKind = active.agentKind,
            ),
            outboundQueueItemId = active.id,
        )
        _uiState.update { it.copy(sendInFlight = true, error = null) }
        watchdogs.armSend()
        // Issue #971: remember the in-flight prompt for the wedged/cancelled
        // recovery paths (watchdog / strand-clear) so they restore the exact
        // payload rather than the cleared composer.
        inFlightSendRequest = request
        if (_sendRequests.trySend(request).isFailure) {
            // Issue #971/#987: transient dispatch failure on a queue-flush — keep
            // the row queued for the next auto-retry (Option A), don't return it
            // to the composer.
            markOutboundSendDeferred(request)
            return false
        }
        return true
    }

    private fun markOutboundSendDelivered(request: SendRequest?) {
        val id = request?.outboundQueueItemId ?: return
        outboundQueueStore.markDelivered(id)
        launchSidecarRemoval(id)
        request.sendTarget.sessionKey
            .takeIf { it.isNotBlank() }
            ?.let { refreshOutboundQueueItemsFor(it) }
    }

    private fun markOutboundSendFailed(request: SendRequest, message: String) {
        val id = request.outboundQueueItemId ?: return
        // Issue #971/#987: this is the GENUINE-permanent-failure path
        // ([restoreFailedSend]) — the prompt is returned to the composer as the
        // SINGLE representation, so REMOVE the queue row instead of leaving a
        // "Failed" duplicate next to the restored editor text (the maintainer's
        // exact complaint, and the leftover row would let the reconnect auto-flush
        // double-send what the user can now re-Send by hand). NOTE: the COMMON
        // drop/wedge case no longer reaches here — it routes through
        // [markOutboundSendDeferred], which KEEPS the row queued for auto-retry
        // (Option A). Drop any sidecar bytes the row owned so nothing is orphaned.
        outboundQueueStore.remove(id)
        launchSidecarRemoval(id)
        request.sendTarget.sessionKey
            .takeIf { it.isNotBlank() }
            ?.let { refreshOutboundQueueItemsFor(it) }
    }

    /**
     * Issue #971 leak fix: the single, GUARDED fire-and-forget for dropping the
     * durable sidecar bytes a resolved (delivered / failed / discarded) outbound
     * row owned. Cleanup is best-effort — a failed delete must NEVER surface as
     * an UNCAUGHT exception that escapes [viewModelScope].
     *
     * The bare `viewModelScope.launch { store.removeOutboundItem(id) }` callers
     * this replaces hopped to real `Dispatchers.IO` (inside
     * [OutboundAttachmentSidecarStore.removeOutboundItem]) with NO try/catch. A
     * `persistAll` write failure there threw on a real background thread, after
     * the test body returned, and kotlinx-coroutines-test re-surfaced it as
     * `UncaughtExceptionsBeforeTest` on the NEXT coroutine-using unit test — the
     * cross-test leak that reddened CI's full `:app:testDebugUnitTest`. Wrapping
     * in [runCatching] (mirroring the #180 reconcile sweep at construction) makes
     * the cleanup swallow its own failure, so it can never poison another test or
     * the on-device pipeline. Idempotent and a no-op when no sidecar store is
     * wired.
     */
    private fun launchSidecarRemoval(outboundItemId: String) {
        val store = outboundAttachmentSidecarStore
        val seam = sidecarRemovalForTest
        if (store == null && seam == null) return
        viewModelScope.launch {
            runCatching {
                if (seam != null) seam(outboundItemId) else store?.removeOutboundItem(outboundItemId)
            }
        }
    }

    /**
     * Issue #971 leak-fix test seam. When non-null, [launchSidecarRemoval] routes
     * the cleanup through this instead of the real store, so a unit test can
     * synthetically inject a THROW on the fire-and-forget cleanup path (the #780
     * synthetic-state model — the real `Dispatchers.IO` `persistAll` failure is
     * not deterministically reproducible under `runTest`) and prove the
     * [runCatching] guard contains it: `runTest` must finish with NO uncaught
     * exception, which is the exact cross-test leak being closed.
     */
    @androidx.annotation.VisibleForTesting
    internal var sidecarRemovalForTest: (suspend (String) -> Unit)? = null

    /**
     * Issue #1540 (L9) synthetic process-death seam (#780 model). When non-null,
     * [clearComposerForHandoff] invokes it the INSTANT the durable draft has been
     * cleared — the exact moment a crash would leave the durable draft empty. The
     * WRITE-AHEAD fix commits the durable queue row BEFORE this fires (in every
     * durable path), so a test can snapshot the durable stores here and prove the
     * prompt survived in the queue row (green); on the pre-fix base the clear ran
     * BEFORE the commit, so the snapshot showed the prompt gone from BOTH the
     * durable draft AND the durable queue (LOST → red). Production never sets it.
     */
    @androidx.annotation.VisibleForTesting
    internal var onDurableDraftClearedForHandoffTest: (() -> Unit)? = null

    private fun refreshOutboundQueueItems() {
        _outboundQueueItems.value = composerTarget
            ?.takeIf { it.isNotBlank() }
            ?.let { outboundQueueStore.itemsFor(it) }
            .orEmpty()
    }

    internal fun refreshOutboundQueueItemsFor(sessionKey: String) {
        if (composerTarget == sessionKey) refreshOutboundQueueItems()
    }

    /**
     * Issue #745: the host screen pushes the live connection liveness in here
     * whenever it changes. When the SSH/tmux control channel is known
     * degraded/lost, the composer surfaces a connection-lost indicator
     * IMMEDIATELY (in/near the Send row) instead of letting the user tap Send
     * and wait blind. The flag is advisory: Send is still allowed (the host's
     * send path connects-on-action, #548), but the user now knows the link is
     * down before they commit.
     */
    public fun setConnectionDegraded(degraded: Boolean) {
        if (_uiState.value.connectionDegraded == degraded) return
        _uiState.update { it.copy(connectionDegraded = degraded) }
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
            DiagnosticEvents.record(
                "action",
                "composer_recording_start_result",
                "provider" to VoiceTranscriptionProvider.OpenAiWhisper.name,
                "status" to "failure",
                "cause" to "MissingApiKey",
            )
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
            DiagnosticEvents.record(
                "action",
                "composer_recording_start_result",
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
        DiagnosticEvents.record(
            "action",
            "composer_recording_start_result",
            "provider" to VoiceTranscriptionProvider.OpenAiWhisper.name,
            "status" to "success",
            "silenceWindowMs" to windowMs,
        )

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
        androidSpeechRecognition.start(voiceSettings.recognitionLanguageTag())
    }

    /**
     * Amplitude / silence-watchdog loop. Runs in `viewModelScope` on the
     * sampler dispatcher. Polls [AudioRecorder.currentAmplitude] every
     * [SAMPLE_INTERVAL_MS] and triggers auto-stop if [SILENCE_WINDOW_MS]
     * elapse without the amplitude exceeding
     * [SILENCE_RESET_AMPLITUDE_THRESHOLD].
     *
     * The silence clock resets every time we see amplitude above the reset
     * floor, so a brief pause mid-sentence — or soft / far-mic speech below
     * the LISTENING->CAPTURING cue bar [SILENCE_AMPLITUDE_THRESHOLD] —
     * doesn't cut the user off (issue #587).
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
            // Issue #587: the silence-clock reset uses a LOWER floor than the
            // LISTENING -> CAPTURING UI cue. `currentAmplitude()` is a PEAK
            // reading; a user dictating softly / far from the mic can produce
            // peaks in `[0.015, 0.04)` whose RMS is still well above the audio
            // guard's speech floor. Resetting the auto-stop clock only on the
            // high `0.04` capture bar treated that real speech as silence and
            // auto-stopped mid-utterance, so the captured WAV failed
            // `hasSpeechEnergy` and the user saw a false "No speech detected".
            // Keep recording while soft-but-real signal is present; pure
            // silence / mic noise (below the reset floor) still auto-stops, so
            // the #452 hallucination guard is untouched.
            val hasLiveSignalNow = amp >= SILENCE_RESET_AMPLITUDE_THRESHOLD
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

            if (hasLiveSignalNow) {
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

    /**
     * Issue #880: drive [UiState.recordingElapsedMs] from a wall-clock tick
     * anchored at [recordingStartedAtMs], independent of any audio-capture/PCM
     * source. Used by the Android SpeechRecognizer recording path, whose
     * listener-driven flow never runs the Whisper amplitude sampler that
     * otherwise advances the timer. Runs on [samplerDispatcher] so tests drive
     * it under virtual time; cancelled by [stopRecordingTimerTicker] when
     * recording ends.
     */
    private fun startRecordingTimerTicker() {
        recordingTimerJob?.cancel()
        recordingTimerJob = viewModelScope.launch(samplerDispatcher) {
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                if (_uiState.value.recording != RecordingState.Recording) break
                val elapsedMs = (clock() - recordingStartedAtMs).coerceAtLeast(0L)
                _uiState.update { it.copy(recordingElapsedMs = elapsedMs) }
                delay(SAMPLE_INTERVAL_MS)
            }
        }
    }

    private fun stopRecordingTimerTicker() {
        recordingTimerJob?.cancel()
        recordingTimerJob = null
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
            clearPendingTranscriptionSend()
            activeProvider = null
            DiagnosticEvents.record(
                "action",
                "composer_recording_stop",
                "provider" to VoiceTranscriptionProvider.OpenAiWhisper.name,
                "status" to "failure",
                "durationMs" to (clock() - recordingStartedAtMs).coerceAtLeast(0L),
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
            // No bytes captured — never happens in practice because
            // [AudioRecorder.stop] returns the WAV header even for a
            // zero-PCM session, but guard anyway.
            savedStateHandle[KEY_WAS_RECORDING] = false
            // Issue #211: same reasoning as the AudioRecorderException
            // branch — no audio means no transcript to send.
            clearPendingTranscriptionSend()
            activeProvider = null
            DiagnosticEvents.record(
                "action",
                "composer_recording_stop",
                "provider" to VoiceTranscriptionProvider.OpenAiWhisper.name,
                "status" to "empty_audio",
                "durationMs" to (clock() - recordingStartedAtMs).coerceAtLeast(0L),
                "audioBytes" to 0,
            )
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
            clearPendingTranscriptionSend()
            val recoverableNoSpeech = SpeechAudioGuard.isRecoverableNoSpeechRejection(audio)
            DiagnosticEvents.record(
                "action",
                "composer_recording_stop",
                "provider" to VoiceTranscriptionProvider.OpenAiWhisper.name,
                "status" to "no_speech",
                "durationMs" to (clock() - recordingStartedAtMs).coerceAtLeast(0L),
                "audioBytes" to audio.size,
                "recoverable" to recoverableNoSpeech,
            )
            if (recoverableNoSpeech) {
                _uiState.update {
                    it.copy(recording = RecordingState.Transcribing, amplitude = 0f)
                }
                // Issue #939 (#935 S5-2): even this recoverable-no-speech path
                // flips the FSM to Transcribing then does an UNGUARDED Room write
                // (`enqueueAudio`). A throw there left the mic locked on
                // "Transcribing…" with no escape — so route it through the
                // guarded [launchTranscribe] like the main path.
                launchTranscribe {
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

        DiagnosticEvents.record(
            "action",
            "composer_recording_stop",
            "provider" to VoiceTranscriptionProvider.OpenAiWhisper.name,
            "status" to "transcribing",
            "durationMs" to (clock() - recordingStartedAtMs).coerceAtLeast(0L),
            "audioBytes" to audio.size,
        )
        _uiState.update {
            it.copy(recording = RecordingState.Transcribing, amplitude = 0f)
        }

        launchTranscribe {
            val client = whisperClientFactory.create()
            if (client == null) {
                savedStateHandle[KEY_WAS_RECORDING] = false
                // Issue #211: API key vanished between recording start
                // and transcribe — drop the queued send for the same
                // reason the offline path does. No transcript means
                // nothing to send.
                clearPendingTranscriptionSend()
                activeProvider = null
                DiagnosticEvents.record(
                    "action",
                    "composer_transcription_result",
                    "provider" to VoiceTranscriptionProvider.OpenAiWhisper.name,
                    "status" to "failure",
                    "cause" to "MissingApiKey",
                    "audioBytes" to audio.size,
                )
                _uiState.update {
                    it.copy(
                        recording = RecordingState.Idle,
                        error = "No OpenAI API key saved. Open settings to add one.",
                    )
                }
                return@launchTranscribe
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
                clearPendingTranscriptionSend()
                activeProvider = null
                DiagnosticEvents.record(
                    "action",
                    "composer_transcription_result",
                    "provider" to VoiceTranscriptionProvider.OpenAiWhisper.name,
                    "status" to "queued_offline",
                    "audioBytes" to audio.size,
                    "pendingQueued" to true,
                )
                _uiState.update {
                    it.copy(
                        recording = RecordingState.Idle,
                        error = PendingTranscriptionItem.NETWORK_WAITING_MESSAGE,
                    )
                }
                return@launchTranscribe
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
                        clearPendingTranscriptionSend()
                        activeProvider = null
                        DiagnosticEvents.record(
                            "action",
                            "composer_transcription_result",
                            "provider" to VoiceTranscriptionProvider.OpenAiWhisper.name,
                            "status" to "empty",
                            "audioBytes" to audio.size,
                            "pendingQueued" to (pendingId != null),
                        )
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
                        clearPendingTranscriptionSend()
                        activeProvider = null
                        DiagnosticEvents.record(
                            "action",
                            "composer_transcription_result",
                            "provider" to VoiceTranscriptionProvider.OpenAiWhisper.name,
                            "status" to "hallucination_filtered",
                            "audioBytes" to audio.size,
                            "pendingQueued" to (pendingId != null),
                        )
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
                    val pendingTarget = pendingSendTarget
                    clearPendingTranscriptionSend()

                    val combinedDraft = _uiState.updateAndReturnDraft { current ->
                        appendTranscript(current, trimmed)
                    }
                    // Mirror the appended draft into [SavedStateHandle]
                    // immediately so a recreate after a successful
                    // transcription still shows the dictated text.
                    savedStateHandle[KEY_DRAFT] = combinedDraft
                    DiagnosticEvents.record(
                        "action",
                        "composer_transcription_result",
                        "provider" to VoiceTranscriptionProvider.OpenAiWhisper.name,
                        "status" to "success",
                        "audioBytes" to audio.size,
                        "transcriptBytes" to trimmed.toByteArray(Charsets.UTF_8).size,
                        "pendingQueued" to (pendingId != null),
                        "queuedSend" to pendingSend,
                    )

                    if (pendingSend) {
                        // Issue #211: the user queued a Send while we
                        // were still recording / transcribing. Fire it
                        // now with the combined draft (existing text +
                        // freshly-transcribed text). The
                        // [dispatchSendNow] call also clears the draft
                        // so the next composer open starts blank.
                        dispatchSendNow(pendingWithEnter, pendingTarget)
                    }
                    activeProvider = null
                },
                onFailure = { t ->
                    val msg = userFacingWhisperError(t)
                    // Issue #211: drop the queued send — the round-trip
                    // failed, so we have no transcript to send. The user
                    // sees the error banner and can either retry the
                    // queued audio (#180) or type + send manually. The
                    // audio is still in the pending-transcription queue so
                    // the user can retry from the banner.
                    clearPendingTranscriptionSend()
                    activeProvider = null
                    // Issue #587: publish the retryable error state before
                    // updating the pending row. The audio was already queued
                    // before Whisper was called, so a slow DB/filesystem write
                    // must not keep the composer visually stuck on
                    // "Transcribing..." after the provider has returned a
                    // network timeout.
                    _uiState.update {
                        it.copy(
                            recording = RecordingState.Idle,
                            amplitude = 0f,
                            error = msg,
                        )
                    }
                    if (pendingId != null) {
                        runCatching { pendingTranscriptionStore.markFailure(pendingId, msg) }
                    }
                    DiagnosticEvents.record(
                        "action",
                        "composer_transcription_result",
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

    /**
     * Issue #939 (audit #935 S5-2): the guarded launcher for the OpenAI-Whisper
     * transcribe FSM. Before this, the transcribe body ran as a bare
     * `viewModelScope.launch {}` with NO surrounding `try/finally` and NO time
     * bound, so any throw OUTSIDE the inner `result.fold` — `enqueueAudio` (a
     * Room/filesystem write), `connectivity.refresh()`, or a WhisperClient impl
     * that threw instead of returning `Result.failure` — stranded the FSM on
     * [RecordingState.Transcribing] with the mic locked, recoverable only by
     * killing the app (the #891 send-wedge shape, but for voice).
     *
     * This launcher closes that whole class:
     *  - **Arm a watchdog** so even a Whisper round-trip
     *    that wedges (never returns, never throws) is bounded — the send path has
     *    the #891 watchdog; transcribe had none.
     *  - **`catch`** any non-Cancellation throw → drive the FSM back to Idle, drop
     *    the queued send, unlock the mic, and surface a RETRYABLE error banner so
     *    the user can record again. CancellationException (job cancel / onCleared)
     *    is rethrown to preserve structured cancellation.
     *  - **`finally`** disarms the watchdog and, as a belt, guarantees the FSM is
     *    never left on Transcribing on ANY exit (idempotent — the normal terminal
     *    arms already drove it to Idle).
     */
    private fun launchTranscribe(block: suspend () -> Unit) {
        watchdogs.armTranscribe()
        transcribeJob = viewModelScope.launch {
            try {
                block()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                DiagnosticEvents.record(
                    "action",
                    "composer_transcription_result",
                    "provider" to (activeProvider?.name ?: VoiceTranscriptionProvider.OpenAiWhisper.name),
                    "status" to "failure",
                    "cause" to t.javaClass.simpleName,
                    "unguarded" to true,
                )
                clearPendingTranscriptionSend()
                activeProvider = null
                savedStateHandle[KEY_WAS_RECORDING] = false
                _uiState.update {
                    it.copy(
                        recording = RecordingState.Idle,
                        amplitude = 0f,
                        error = userFacingWhisperError(t),
                    )
                }
            } finally {
                watchdogs.disarmTranscribe()
                // Belt: no normal terminal arm should leave the FSM on
                // Transcribing, but if a future edit forgets one, this guarantees
                // the mic is never stuck. Idempotent when already Idle.
                if (_uiState.value.recording == RecordingState.Transcribing) {
                    _uiState.update {
                        it.copy(
                            recording = RecordingState.Idle,
                            amplitude = 0f,
                        )
                    }
                }
            }
        }
    }

    private fun stopAndroidSpeechRecognition() {
        androidSpeechRecognition.stop()
    }

    private fun cancelAndroidSpeechRecognition() {
        androidSpeechRecognition.cancel()
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
        // Issue #688: idempotency guard. N overlapping retry triggers for
        // the SAME row — auto-retry on foreground-resume + one or more
        // manual Retry taps — must collapse to a SINGLE round-trip. Without
        // this guard every trigger ran a separate `client.transcribe` and
        // each appended the transcript on success, duplicating the inserted
        // text N times (the dogfood report). The first trigger claims the
        // id synchronously here, on the single-threaded viewModelScope main
        // dispatcher, so a racing tap that arrives before the coroutine
        // suspends still sees the id already in flight and short-circuits.
        if (!inFlightRetryIds.add(id)) return
        // Publish the visible "retrying" state immediately so the manual
        // Retry tap is never a silent no-op — the row shows feedback the
        // instant the tap lands, before the network round-trip even starts.
        _uiState.update { it.copy(retryingIds = it.retryingIds + id, error = null) }

        viewModelScope.launch {
            try {
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
                        // Issue #688: drop a LATE success for an
                        // already-resolved / superseded row. A timeout that
                        // actually succeeded server-side, or a straggler
                        // round-trip whose sibling already inserted (or whose
                        // row the user discarded) while this call was in
                        // flight, must NOT append a second copy. Re-check the
                        // store right before inserting: if the row is gone,
                        // the transcript was already handled (or intentionally
                        // dropped) and inserting again would duplicate it.
                        val stillPending = pendingTranscriptionStore.snapshot()
                            .any { it.id == id }
                        if (!stillPending) {
                            return@fold
                        }
                        runCatching { pendingTranscriptionStore.markSucceeded(id) }
                        _uiState.update {
                            val newDraft = appendTranscript(it.draft, trimmed)
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
            } finally {
                // Always release the id — success, failure, drop, or an
                // early return — so a later legitimate retry is not blocked.
                inFlightRetryIds.remove(id)
                _uiState.update { it.copy(retryingIds = it.retryingIds - id) }
            }
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
            requeueStaleOutboundInFlight()
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
        clearPendingTranscriptionSend()
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
        // Issue #939: the user cancelled — drop the transcribe watchdog so it
        // does not fire later and re-surface a spurious timeout banner.
        watchdogs.disarmTranscribe()
        clearPendingTranscriptionSend()
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
        watchdogs.cancelAll()
        // Issue #882: the #880 recording-elapsed ticker is an infinite
        // `while { delay() }` loop that only breaks when recording leaves
        // [RecordingState.Recording]. Cancel it explicitly on clear so it
        // never outlives the ViewModel (e.g. cleared mid-recording) — the
        // same robustness guarantee the other jobs above already have.
        stopRecordingTimerTicker()
        // If we were mid-recording, best-effort drop the mic. We swallow
        // any AudioRecorderException because there's no UI to surface it
        // to at this point in the lifecycle.
        if (_uiState.value.recording == RecordingState.Recording) {
            if (activeProvider == VoiceTranscriptionProvider.AndroidSpeech) {
                androidSpeechRecognition.cancelSession()
            } else {
                runCatching { audioRecorder.stop() }
            }
        }
        androidSpeechRecognition.cancelSession()
        viewModelScope.cancel()
        super.onCleared()
    }

    /**
     * Issue #882 test seam: drive the real [onCleared] teardown from unit
     * tests (which never trigger the Android `ViewModelStore` clear). A unit
     * test that starts recording and ends its `runTest` body without stopping
     * would otherwise leave the #880 recording ticker's infinite
     * `while { delay() }` loop alive, so `runTest`'s final `advanceUntilIdle`
     * spins virtual time forever and the whole suite hangs (the 35-min CI
     * timeout). Tearing the VM down in `@After` cancels the ticker and any
     * other lingering loop, mirroring the production lifecycle.
     */
    @androidx.annotation.VisibleForTesting
    internal fun clearForTest() {
        onCleared()
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
     * Issue #900: tap-time snapshot of the route the Send action targeted.
     * The composer captures this before any deferred work (dictation finish or
     * attachment-upload wait) so the eventual [SendRequest] still points at the
     * pane/session the user committed to when they tapped Send.
     *
     * Defaults are intentionally empty/safe so legacy callers can construct
     * sends without knowing about the outbound queue metadata yet.
     */
    public data class SendTargetSnapshot(
        val sessionKey: String = "",
        val paneId: String = "",
        val route: OutboundRoute = OutboundRoute.RawBytes,
        val agentKind: String? = null,
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
        /**
         * Issue #870 (reopen): the live, growing partial transcript for the
         * Android recognizer. The sheet renders it in a DEDICATED TWO-LINE area
         * ([com.pocketshell.app.composer.LiveTranscriptTwoLine]) that resolves the
         * visible tail width-aware at render time, so the newest recognized words
         * are always visible (the maintainer's design direction). The superseded
         * width-independent VM-side char budget (`liveTranscriptDisplay` /
         * `liveTranscriptTail`) was removed (D22 hard-cut) because it could not
         * know the panel width and so re-clipped the tail on device.
         */
        val liveTranscript: String? = null,
        /**
         * Issue #688: ids of pending-transcription rows that currently have
         * a retry round-trip in flight. The composer banner renders a
         * "Retrying…" state and disables the Retry button for these rows so
         * the user gets immediate feedback on tap and cannot stack a second
         * round-trip on top of an in-flight one. Cleared per id when its
         * retry resolves (success, failure, or drop).
         */
        val retryingIds: Set<String> = emptySet(),
        /**
         * Issue #745: true from the instant the user taps Send until the
         * host confirms delivery ([markSendDelivered]) or reports failure
         * ([restoreFailedSend], including a bounded timeout). The Send row
         * reads this to disable Send and render a "Sending…" spinner so the
         * user gets immediate in-flight feedback instead of an apparently
         * inert tap on a degraded connection.
         */
        val sendInFlight: Boolean = false,
        /**
         * Issue #745: true when the host screen has reported the live SSH/tmux
         * connection is degraded/lost. The composer surfaces a connection-lost
         * indicator near the Send row IMMEDIATELY (before the user taps Send)
         * so a send into a dead link is never a silent blind wait. Pushed in
         * via [setConnectionDegraded].
         */
        val connectionDegraded: Boolean = false,
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
        /**
         * Issue #872: the CLEAN draft (without the appended "Attached files:"
         * path block) and the staged attachment tiles at send time. On a
         * failed send ([restoreFailedSend]) these restore the original draft +
         * the actual attachment TILES — closing the #832 AC2 gap where only the
         * paths-folded-into-text survived. Defaulted so existing call sites /
         * tests that construct a bare [SendRequest] keep compiling; the real
         * dispatch path ([dispatchSendNow]) always populates them.
         */
        val cleanDraft: String = text,
        val attachments: List<StagedAttachment> = emptyList(),
        /**
         * Issue #900: route metadata captured at Send tap time. This is not
         * used to deliver the item in this slice, but it lets the durable queue
         * row and legacy host send path agree on the original tap-time target
         * rather than whatever pane is focused after a deferral.
         */
        val sendTarget: SendTargetSnapshot = SendTargetSnapshot(),
        val outboundQueueItemId: String? = null,
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
         * Issue #587: the floor at which the silence watchdog resets its
         * auto-stop clock — i.e. the level it treats as "the user is still
         * speaking, keep recording".
         *
         * This is deliberately LOWER than [SILENCE_AMPLITUDE_THRESHOLD]. The
         * `0.04` capture bar is tuned to flip the LISTENING -> CAPTURING UI
         * cue ([UiState.hasDetectedSpeech]) on confident, near-mic speech and
         * to feel snappy. But using that same high bar to decide *when to keep
         * recording* meant a user dictating softly / far from the mic — whose
         * `currentAmplitude()` (a PEAK reading, see
         * [com.pocketshell.core.voice.PcmCapturePump.peakAmplitude]) sits in
         * `[~0.015, 0.04)` — was treated as silence and auto-stopped
         * mid-utterance. The captured WAV then failed
         * [SpeechAudioGuard.hasSpeechEnergy] (which accepts RMS down to
         * `MIN_RMS_AMPLITUDE = 0.006f`) and the user saw a false
         * "No speech detected".
         *
         * `0.015` peak reconciles the watchdog with the audio guard: it sits
         * above the silence / mic-self-noise floor (a quiet room peaks around
         * `0.002-0.006` on the noise-suppressed `VOICE_RECOGNITION` source)
         * but below the `0.04` capture bar, so soft-but-real speech keeps the
         * recording alive while pure silence still auto-stops — keeping the
         * #452 silence-hallucination guard intact.
         */
        public const val SILENCE_RESET_AMPLITUDE_THRESHOLD: Float = 0.015f

        /**
         * Fallback silence window — once this much time passes without
         * amplitude crossing [SILENCE_RESET_AMPLITUDE_THRESHOLD], the recording
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
         * Issue #570 / #1569 (U2): the historical whole-batch attachment upload
         * budget.
         *
         * It is **no longer an absolute wall-clock cap on the upload** — the
         * `withTimeout(ATTACHMENT_UPLOAD_TIMEOUT_MS)` wrappers around the attach
         * and send upload legs were REMOVED because a 52MB batch progressing on a
         * healthy link legitimately exceeds 90s, and the cap both killed the
         * progressing transfer (size-correlated failure) and orphaned a zombie
         * deferred that kept uploading after it fired. Uploads are now bounded by
         * core-ssh's progress-based budget (60s no-progress, total unbounded while
         * bytes move); a genuinely stalled upload still fails within that window.
         *
         * The constant survives only as the upload term in the derived overall-send
         * watchdog budget below, so the #891 "watchdog ceiling stays above the
         * worst-case leg sum" invariant math is unchanged.
         */
        public const val ATTACHMENT_UPLOAD_TIMEOUT_MS: Long = 90_000L

        /** Issue #1532 (RC-A): headroom for delivery (paste, ack, Enter) atop the connect-wait. */
        internal const val SEND_DELIVERY_HEADROOM_MS: Long = 20_000L

        /**
         * Issue #745 / #1532 (RC-A, audit D1): bounded cap the sheet wraps host
         * `onSend` in (null/timeout = failed send). A flat 12s was shorter than the
         * connect-wait it gates ([com.pocketshell.app.tmux.SEND_SESSION_WAIT_TIMEOUT_MS],
         * 30s), so a 12-30s reconnect was always cancelled-and-deferred though the
         * inner wait would deliver. Now DERIVED so it can't drift shorter (guarded).
         */
        public const val SEND_TIMEOUT_MS: Long =
            com.pocketshell.app.tmux.SEND_SESSION_WAIT_TIMEOUT_MS + SEND_DELIVERY_HEADROOM_MS

        /** Issue #891/#1532 (Finding 2): headroom the watchdog sits above the leg sum. */
        internal const val OVERALL_SEND_WATCHDOG_HEADROOM_MS: Long = 20_000L

        /**
         * Issue #891 / #1532 (Finding 2): the whole-send watchdog — the backstop
         * so the composer can never sit on "Sending…" forever. The two legs run
         * SEQUENTIALLY: [ATTACHMENT_UPLOAD_TIMEOUT_MS] (90s) upload, then the sheet's
         * [SEND_TIMEOUT_MS] `withTimeoutOrNull` onSend. The #891 invariant is that
         * this ceiling stays strictly ABOVE the worst-case leg sum (`upload+onSend`)
         * so each per-leg banner fires first and the watchdog only fires when none
         * resolved (routing to [restoreFailedSend] with draft+attachment kept). RC-A
         * grew onSend to 50s ⇒ leg sum 140s, ABOVE the old flat 110s ceiling (that
         * inversion reopens the spurious-fail/duplicate window). Now DERIVED from the
         * leg sum + headroom so it can never invert again, whichever leg grows.
         */
        public const val OVERALL_SEND_TIMEOUT_MS: Long =
            ATTACHMENT_UPLOAD_TIMEOUT_MS + SEND_TIMEOUT_MS + OVERALL_SEND_WATCHDOG_HEADROOM_MS

        /**
         * Issue #900: an [OutboundState.InFlight] row older than the whole-send
         * watchdog was almost certainly abandoned by process death, collector
         * loss, or a callback that never returned. Requeue it only after that
         * window so an active foreground send is not duplicated.
         */
        public const val OUTBOUND_IN_FLIGHT_STALE_MS: Long = OVERALL_SEND_TIMEOUT_MS

        /**
         * Issue #891: the "Send failed — Retry" banner shown when the overall
         * send watchdog fires. Distinct copy from the #872 reconnect "Not sent"
         * banner so the user understands the send timed out (not a clean
         * disconnect), but the recovery is identical: the draft + attachment are
         * preserved and tapping Send retries.
         */
        internal const val SEND_TIMEOUT_MESSAGE: String =
            "Send failed — it took too long. Tap Send to retry, or discard the draft."

        /**
         * Issue #971/#987 (maintainer decision — Option A): the SINGLE coherent
         * status shown for a queued send that is waiting to auto-send on
         * reconnect. Replaces the contradictory stack the maintainer hit during a
         * drop ("Not sent. Reconnect, then send again or discard the draft." +
         * "1 unsent prompt — Failed — Not sent…" + "Connection lost — Send will
         * retry once reconnected."). On Send the message is queued, the composer
         * clears, and the row auto-sends on reconnect (#900 flush) — there is no
         * manual-resend affordance to contradict the auto-retry, just this one
         * status.
         */
        internal const val WILL_SEND_WHEN_RECONNECTED_MESSAGE: String =
            "Will send when reconnected." // #1531 (RC2) copy: ComposerSwallowMessages
        internal const val ATTACHMENT_UPLOAD_BUSY_MESSAGE: String = COMPOSER_ATTACHMENT_UPLOAD_BUSY_MESSAGE
        internal const val SEND_BUSY_UPLOADING_MESSAGE: String = COMPOSER_SEND_BUSY_UPLOADING_MESSAGE

        /**
         * Issue #939 (audit #935 S5-2): the end-to-end ceiling on the voice
         * transcribe FSM. The send path has the #891 [OVERALL_SEND_TIMEOUT_MS]
         * watchdog; transcribe had NO bound at all, so a wedged Whisper round-trip
         * (or an unguarded IO hang) locked the mic on "Transcribing…" until app
         * restart. This sits ABOVE a realistic Whisper round-trip (the network
         * call self-bounds in the OkHttp client) so the normal `result.fold`
         * failure surfaces its own banner first; the watchdog only fires when the
         * FSM failed to resolve at all.
         */
        public const val TRANSCRIBE_TIMEOUT_MS: Long = 90_000L

        /**
         * Issue #939: the banner shown when the transcribe watchdog fires. Calm,
         * retryable copy — the audio is still on disk in the pending-transcription
         * queue (#180) so the user can retry the persisted recording.
         */
        internal const val TRANSCRIBE_TIMEOUT_MESSAGE: String =
            "Transcription took too long. Tap the mic to try again."

        /**
         * Issue #169 Part 2: [SavedStateHandle] key for the current
         * composer draft text. Survives both configuration-change recreate
         * (ViewModel retained) and process death (ViewModel rebuilt from
         * the saved bundle) so a screen-lock-triggered tear-down does not
         * silently discard the dictated text.
         */
        internal const val KEY_DRAFT: String = "prompt-composer-draft"

        /**
         * Issue #746: [SavedStateHandle] key for the session/pane that owns
         * the current draft (`"<hostId>/<sessionName>"`). The composer
         * ViewModel is activity-scoped and shared across every session on a
         * host, so without an owner stamp a draft (and its "Not sent" banner)
         * authored in one session bleeds into another on a switch. The host
         * screen reports the focused session via
         * [onComposerTargetChanged]; a target change that does not match the
         * stamped owner discards the stale draft. Survives process death
         * alongside [KEY_DRAFT] so the restore path can still attribute a
         * recovered draft to its session.
         */
        internal const val KEY_DRAFT_OWNER: String = "prompt-composer-draft-owner"

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

        public const val ANDROID_SPEECH_NO_TEXT_MESSAGE: String =
            "Android speech recognizer did not return text. Try again or choose Whisper in settings."

        public const val ANDROID_SPEECH_FAILED_MESSAGE: String =
            "Android speech recognition failed. Try again or choose Whisper in settings."
    }
}
