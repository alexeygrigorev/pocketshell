package com.pocketshell.app.composer

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.pocketshell.app.session.InlineDictationViewModel
import com.pocketshell.app.session.UndeliveredTranscriptBanner
import com.pocketshell.app.snippets.SnippetKind
import com.pocketshell.app.snippets.SnippetPickerSheet
import com.pocketshell.app.agentcommands.AgentCommand
import com.pocketshell.app.voice.DictateDotIcon
import com.pocketshell.app.voice.PendingTranscriptionItem
import com.pocketshell.app.voice.UndeliveredTranscript
import dagger.hilt.internal.GeneratedComponentManagerHolder
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.uikit.theme.LocalPocketShellSemantic
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.supervisorScope

/**
 * The Phase 1 prompt composer (issue #15). Visual target:
 * `docs/mockups/composer.html`.
 *
 * Layout, top to bottom inside the [ModalBottomSheet]:
 *
 *  1. Grabber bar (issue #1622's compact [ComposerDragHandle], passed to
 *     Material 3's `ModalBottomSheet` in place of the 48dp-tall default).
 *  2. Header: title `Prompt Composer` + close `×` button.
 *  3. Editable text area (`composer-text`) — `OutlinedTextField` styled to
 *     match the mockup's surface-elev fill.
 *  4. Mic row: the [MicButton] from `:shared:ui-kit`, an animated
 *     amplitude waveform, and the "Listening" label that fades in while
 *     recording.
 *  5. Action row: `Snippets` (ghost), `Insert`, `Send` (primary).
 *
 * The composable is a pure renderer; everything stateful lives in
 * [PromptComposerViewModel]. The screen wires the viewmodel via
 * `hiltViewModel()` so the activity's `ViewModelStoreOwner` keeps the
 * draft alive across sheet open/close cycles.
 *
 * ## Permission flow
 *
 * `RECORD_AUDIO` is declared in the manifest and additionally requested
 * at runtime via [rememberLauncherForActivityResult] with the modern
 * [ActivityResultContracts.RequestPermission] contract. On a fresh
 * install:
 *
 *  1. User taps the mic button.
 *  2. We check the current permission state with [ContextCompat.checkSelfPermission].
 *  3. If denied, we launch the system prompt. On `granted=true` we
 *     immediately start recording; on `granted=false` we surface a
 *     message in the error banner and the mic stays Idle.
 *  4. If already granted (or after the user accepts), we route the tap
 *     into [PromptComposerViewModel.onMicTap].
 *
 * ## API key entry
 *
 * If the user has never stored an OpenAI API key, the first mic tap
 * shows a one-field dialog with `Save` / `Cancel`. A full settings
 * screen lives in a follow-up issue; the dialog is intentionally
 * minimal.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun PromptComposerSheet(
    onDismiss: () -> Unit,
    onSend: suspend (PromptComposerViewModel.SendRequest) -> ComposerSendResult,
    modifier: Modifier = Modifier,
    hostId: Long? = null,
    // Issue #745: live SSH/tmux connection liveness pushed from the host
    // screen. When true the composer surfaces a connection-lost indicator
    // near the Send row IMMEDIATELY, so a send into a degraded link is never
    // a silent blind wait. Defaults to false (connected) for previews / tests
    // that don't wire a host connection.
    connectionLost: Boolean = false,
    // Issue #746: a stable id for the session/pane this composer is acting on
    // (`"<hostId>/<sessionName>"`). The composer ViewModel is activity-scoped
    // and shared across every session on a host, so a draft (and its "Not
    // sent" banner) authored in one session bled into another on a switch.
    // The sheet forwards this to [PromptComposerViewModel.onComposerTargetChanged]
    // so a draft owned by a different session is discarded instead of shown
    // where it was never authored. Null leaves the draft un-scoped (callers
    // without a session context, e.g. previews / proof-of-life entry points).
    composerTargetKey: String? = null,
    onStageAttachments: (suspend (List<Uri>) -> Result<List<String>>)? = null,
    // Issue #767: the detected engine for the focused pane (the same
    // `paletteAgent` / `presumedAgentKind` the host screen already computes). It
    // selects which `AgentCommandCatalog` the `/`-autocomplete dropdown filters,
    // so typing `/` over a Claude Code pane offers Claude's commands and over a
    // Codex pane offers Codex's. Null on a plain shell pane / non-agent surface,
    // where the dropdown is simply never offered.
    agentKind: AgentKind? = null,
    // Issue #900: optional tap-time route metadata provider for the eventual
    // outbound queue. Called synchronously from the Send tap path before any
    // recording/transcription/upload deferral can shift the focused pane.
    sendTargetSnapshotProvider: (withEnter: Boolean) -> PromptComposerViewModel.SendTargetSnapshot = {
        PromptComposerViewModel.SendTargetSnapshot()
    },
    viewModel: PromptComposerViewModel = hiltViewModel(),
    // Issue #900: production tmux screens mount the one-shot send dispatcher at
    // screen scope so durable queue flushes can deliver while this sheet is
    // closed. Standalone harnesses keep the legacy in-sheet collector.
    collectSendRequests: Boolean = true,
    // Issue #234: the composer is partial-expand by default so the terminal
    // viewport behind it stays visible. With `skipPartiallyExpanded = false`,
    // Material 3 lands the sheet at `SheetValue.PartiallyExpanded` (≈ half the
    // screen height) on first composition; the user can still swipe upward
    // to reach `Expanded` if they want the keyboard + draft area centred.
    // Tapping the scrim above the sheet's top edge calls `onDismissRequest`
    // and routes back through the host's `onDismiss` lambda — that is the
    // "tap outside to dismiss" affordance D22 / #191 asked for.
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
    // Issue #900: UI/API plumbing for manual outbound retry. Tests may override
    // this seam, while production defaults to the owning VM.
    onRetryOutboundItem: ((String) -> Unit)? = null,
    // Issue #1308: batch "Resend all" for the expanded unsent-prompts surface.
    // Tests may override this seam; production defaults to the owning VM's
    // resendAllQueued, which re-arms every resendable row to Queued in FIFO order.
    onResendAllOutbound: (() -> Unit)? = null,
    // Issue #585: open the composer WITH recording already started. Set true only
    // when the session launcher's hold+swipe-up ENTRY gesture opened this sheet; a
    // plain-tap open leaves it false (no recording). The effect below fires ONCE
    // per sheet open (a remembered latch guards it) and starts recording through
    // the same permission/API-key gate as the mic tap. The recording then runs
    // (timer + waveform) until Discard / Insert / Send — issue #1245 removed the
    // separate hands-free "lock" concept.
    autoStartRecording: Boolean = false,
    // Issue #1272: override seam for the activity-scoped inline-dictation
    // ViewModel that owns the durable undelivered-transcript queue + delivery
    // channel. Production leaves it null — the sheet resolves the SAME
    // activity-scoped instance TmuxSessionScreen uses via `hiltViewModel()` (so a
    // Retry re-injects into the live delivery channel the session screen
    // collects). Tests pass a real instance directly so the retry surface renders
    // without a Hilt graph.
    inlineDictationViewModel: InlineDictationViewModel? = null,
) {
    val state by viewModel.uiState.collectAsState()
    // Issue #1272: surface the durable "couldn't deliver — retry" queue in the
    // composer's own bottom chrome so a voice command that was transcribed but
    // never reached a pane (permanent pane death / channel overflow) is
    // recoverable by the user instead of silently lost.
    val undelivered = rememberComposerUndeliveredBinding(inlineDictationViewModel)
    val pendingItems by viewModel.pendingItems.collectAsState()
    val outboundQueueItems by viewModel.outboundQueueItems.collectAsState()
    val context = LocalContext.current

    var showApiKeyDialog by remember { mutableStateOf(false) }
    // Issue #17: tracks whether the Snippets bottom sheet is currently
    // open. Null host id means the caller doesn't yet have a persisted
    // host (Phase 0 / proof-of-life entry point) — in that case the
    // Snippets button stays inert.
    var showSnippetPicker by remember { mutableStateOf(false) }
    // Issue #180: whether the user has tapped the queue summary to
    // expand the per-item list. Default collapsed so the banner stays
    // compact when multiple items are queued.
    var pendingListExpanded by remember { mutableStateOf(false) }
    var outboundQueueExpanded by remember { mutableStateOf(false) }

    // Issue #180: foreground-resume auto-retry. The composer is the
    // only surface that owns this queue today; observing the sheet's
    // lifecycle (rather than the activity's) means a sheet that is
    // currently visible re-attempts queued items on every `ON_RESUME`
    // and on first display. D21 compliance: there is no background
    // work — the observer is registered while the sheet is in
    // composition and removed when it leaves.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onForegroundResume()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        // Fire once on enter so a sheet that opens with the lifecycle
        // already in RESUMED gets the same auto-retry.
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            viewModel.onForegroundResume()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            // Re-check the API key gate before kicking the recorder. The
            // ViewModel itself also re-checks before calling start(), but
            // showing the key entry dialog up front beats a silent error
            // banner.
            if (viewModel.needsOpenAiKeyForMicTap()) {
                showApiKeyDialog = true
            } else {
                viewModel.onMicTap()
            }
        } else {
            // Denied — surface via the inline error banner. The sheet
            // doesn't host a Snackbar scaffold, and we don't want to be
            // pushy with a second OS dialog. The user can re-tap the mic
            // to be re-prompted, or proceed by typing.
            viewModel.surfacePermissionDenied()
        }
    }
    val attachmentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        val stageAttachments = onStageAttachments ?: return@rememberLauncherForActivityResult
        if (uris.isNotEmpty()) {
            val previews = uris.map { uri ->
                PromptComposerViewModel.AttachmentPreview(
                    uri = uri,
                    mimeType = null,
                )
            }
            viewModel.attachFiles(count = uris.size, previews = previews) {
                stageAttachments(uris)
            }
        }
    }

    // Issue #211: collect the ViewModel's one-shot Send dispatch flow
    // and route every emission into the host's `onSend` callback. The
    // ViewModel emits here either immediately (the user tapped Send in
    // Idle with a non-empty draft) or after a queued send fires on
    // transcription success (Send-while-Recording / Send-while-
    // Transcribing). Using `rememberUpdatedState` so a recomposition
    // that changes the host's `onSend` reference does not lose
    // already-queued sends.
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val currentSendTargetSnapshotProvider by rememberUpdatedState(sendTargetSnapshotProvider)
    if (collectSendRequests) {
        PromptComposerSendDispatcher(
            viewModel = viewModel,
            onSend = onSend,
            onDelivered = { currentOnDismiss() },
        )
    }

    // Issue #745: keep the ViewModel's connection-degraded flag in sync with
    // the host screen's live liveness so the composer can show the
    // connection-lost indicator the moment the link drops — including while
    // the sheet is already open and the user is mid-compose.
    LaunchedEffect(viewModel, connectionLost) {
        viewModel.setConnectionDegraded(connectionLost)
    }

    // Issue #746: report the targeted session to the ViewModel so it can
    // discard a draft that belongs to a different session (the activity-scoped
    // composer is shared across every session on a host). Keyed on the target
    // so a switch to a new session re-runs the check; a null key (no session
    // context) leaves the draft un-scoped.
    LaunchedEffect(composerTargetKey) {
        viewModel.onComposerTargetChanged(composerTargetKey)
    }

    // Reopening an empty sheet is user interaction too. Give it a fresh epoch
    // so an older row's later delivery can never close the new composer.
    LaunchedEffect(viewModel) {
        viewModel.onComposerOpened()
    }

    // Issue #585: the session launcher's hold+swipe-up ENTRY gesture opens this
    // sheet WITH recording already started — one gesture, not "open then tap the
    // mic". [autoStartRecording] carries that intent from the launcher; a
    // plain-tap open leaves it false. This effect fires ONCE per sheet open (a
    // remembered latch guards it against recomposition) and only starts from a
    // clean Idle composer so it never interrupts an in-flight capture.
    //
    // Recording start runs through the SAME permission + API-key gate as the mic
    // tap. On the common path (mic already granted, key present) it starts
    // synchronously; if the mic permission must be requested first, the grant
    // callback ([permissionLauncher]) starts the capture. Recording then simply
    // runs (timer + waveform) until the user taps Discard / Insert / Send — there
    // is no separate "lock" concept (issue #1245: the hands-free lock was removed).
    var autoStartRecordingConsumed by remember { mutableStateOf(false) }
    LaunchedEffect(autoStartRecording) {
        if (!autoStartRecording || autoStartRecordingConsumed) return@LaunchedEffect
        autoStartRecordingConsumed = true
        if (viewModel.uiState.value.recording !=
            PromptComposerViewModel.RecordingState.Idle
        ) {
            return@LaunchedEffect
        }
        viewModel.onMicStartIntent()
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            // The grant callback fires onMicTap() once the permission lands.
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return@LaunchedEffect
        }
        if (viewModel.needsOpenAiKeyForMicTap()) {
            // Surface the key dialog; the user adds the key, then records manually.
            showApiKeyDialog = true
            return@LaunchedEffect
        }
        viewModel.onMicTap()
    }

    // Issue #511 / #509: dismissing the composer (× button, scrim tap,
    // system back, swipe-down) must RELEASE the microphone — not merely
    // hide the sheet. This [PromptComposerViewModel] is scoped to the
    // session screen, NOT this sheet, so `onCleared()` (the only other
    // place the mic is released) does not run when the sheet is dismissed.
    // Without explicitly stopping here, a recording started in the sheet
    // keeps the AudioRecord — and the sampler timer — alive after the
    // sheet closes, holding the mic so the *next* recording captures
    // silence and Whisper returns "no speech detected" (the maintainer's
    // lost-recording data loss). [PromptComposerViewModel.cancelRecording]
    // cancels the sampler job, stops + releases the recorder, and discards
    // the partial buffer. It also expires Android speech after stop-listening
    // has moved the UI into Transcribing; Whisper dismissal behavior remains
    // unchanged.
    val dismissComposer = {
        viewModel.cancelRecording()
        onDismiss()
    }

    // Issue #682: the composer is a CONTENT-HEIGHT (wrap-content) sheet that sits
    // directly above the soft keyboard, like a normal chat composer. #615
    // reworked this into a fully-expanded sheet + a host-window IME inset read +
    // an explicit `padding(bottom = hostImeBottomPx)` on the sheet body; that
    // over-sized the sheet, pushed the controls to the top of the screen and
    // opened a keyboard-height void. It stays wrap-content.
    //
    // Issue #1622: the Material chrome (content insets, drag handle) now lives in
    // ONE place — `ComposerModalBottomSheet` — because every geometry proof used
    // to re-declare it by hand and the copies drifted. That file's block comment
    // carries the measured dead-band diagnosis.
    ComposerModalBottomSheet(
        onDismissRequest = dismissComposer,
        sheetState = sheetState,
        modifier = modifier,
    ) {
        SheetContent(
            state = state,
            onClose = dismissComposer,
            onDraftChange = viewModel::onDraftChange,
            onMicTap = {
                // Three-step gate: permission, then key, then recorder.
                viewModel.onMicStartIntent()
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    return@SheetContent
                }
                if (viewModel.needsOpenAiKeyForMicTap()) {
                    showApiKeyDialog = true
                    return@SheetContent
                }
                viewModel.onMicTap()
            },
            // Recording discard and transcription cancel are deliberately
            // separate. Discard stops the mic/recognizer and drops the live
            // buffer; cancel-transcription aborts the already-started
            // transcribe/final-result wait.
            onCancelRecording = viewModel::cancelRecording,
            onCancelTranscription = viewModel::cancelTranscription,
            // Issue #746: clears the stale "Not sent" draft + attachments +
            // banner so the user no longer has to delete it by hand.
            onDiscard = viewModel::discardDraft,
            onSend = { withEnter ->
                // Issue #211: route through the ViewModel so the FSM
                // decides whether to dispatch now (Idle) or queue for
                // transcription (Recording / Transcribing). The
                // ViewModel emits via `sendRequests` once the dispatch
                // is ready; the `LaunchedEffect` above forwards it into
                // the host's `onSend` + `onDismiss`.
                viewModel.requestSend(
                    withEnter = withEnter,
                    sendTarget = currentSendTargetSnapshotProvider(withEnter),
                )
            },
            onSnippets = if (hostId != null) {
                { showSnippetPicker = true }
            } else null,
            onAttachFiles = if (onStageAttachments != null) {
                {
                    viewModel.onAttachmentPickIntent()
                    attachmentLauncher.launch(arrayOf("*/*"))
                }
            } else null,
            onRemoveAttachment = viewModel::removeAttachment,
            pendingItems = pendingItems,
            pendingListExpanded = pendingListExpanded,
            onTogglePendingList = { pendingListExpanded = !pendingListExpanded },
            onRetryPending = viewModel::retryPending,
            onDiscardPending = viewModel::discardPending,
            onSavePendingAsAudio = viewModel::savePendingAsAudioFile,
            onAcknowledgeSavedAudio = viewModel::clearSavedAudioConfirmation,
            outboundQueueItems = outboundQueueItems,
            outboundRetryBlockedByHealthyOwner = viewModel.outboundRetryBlockedByHealthyOwner(),
            outboundWireWritable = viewModel.isSendTransportWritable(),
            outboundQueueExpanded = outboundQueueExpanded,
            onToggleOutboundQueue = { outboundQueueExpanded = !outboundQueueExpanded },
            onDeleteOutboundItem = viewModel::discardOutboundItem,
            onRetryOutboundItem = onRetryOutboundItem ?: viewModel::retryOutboundItem,
            onResendAllOutbound = onResendAllOutbound ?: { viewModel.resendAllQueuedOrApproveHeld(); Unit },
            agentKind = agentKind,
            // Issue #1272: the durable undelivered-transcript retry surface.
            undeliveredTranscripts = undelivered.transcripts,
            onRetryUndelivered = undelivered.onRetry,
            onDismissUndelivered = undelivered.onDismiss,
        )
    }

    if (showSnippetPicker && hostId != null) {
        // Issue #17 / #187 / #227: opens the snippet picker over the
        // composer sheet.
        //
        // Composer-specific invariant: a snippet pick in this context
        // appends to the live draft so the user can review/edit before
        // tapping the composer's own Send button. We deliberately do NOT
        // auto-fire the draft from here — that would let a stray tap
        // commit the wrong text and defeats the whole point of the
        // composer ("compose, then send").
        //
        // The `withEnter` signal from the picker chips is intentionally
        // ignored: the composer's own `Send` button is the surface
        // that submits with Enter, so the snippet's chip becomes "paste
        // this template into the draft" in this context. The chip labels
        // still read "Send" / "Send + ↵" because they reflect what
        // *would* happen outside the composer; inside it the safety rule
        // (compose-then-send) wins.
        val appendToDraft: (com.pocketshell.core.storage.entity.SnippetEntity) -> Unit = { snippet ->
            val current = viewModel.uiState.value.draft
            val separator = when {
                current.isEmpty() -> ""
                current.endsWith("\n") || current.endsWith(" ") -> ""
                else -> " "
            }
            viewModel.onDraftChange(current + separator + snippet.body)
            showSnippetPicker = false
        }
        SnippetPickerSheet(
            hostId = hostId,
            onDismiss = { showSnippetPicker = false },
            kindFilter = SnippetKind.Prompt,
            onSnippetSend = { snippet, _ -> appendToDraft(snippet) },
        )
    }

    if (showApiKeyDialog) {
        ApiKeyEntryDialog(
            onDismiss = { showApiKeyDialog = false },
            onSave = { key ->
                viewModel.saveApiKey(key)
                // Zero the local copy after the ViewModel has persisted
                // its own. ApiKeyStorage's save() makes a transient
                // String internally; that lifetime is bounded by the
                // apply() call.
                java.util.Arrays.fill(key, ' ')
                showApiKeyDialog = false
            },
        )
    }
}

/**
 * Keeps a saturated partial composer above its modal root's exact IME boundary.
 *
 * Material appends its anchored translation after the caller modifier. That
 * means [onGloballyPositioned] gives us the real Surface height and root size,
 * while [SheetState.requireOffset] is the authoritative displayed Surface top.
 * Combining those values avoids both a device-specific offset and the stale
 * local coordinates that originally hid the 28 px #1744 overlap.
 *
 * The effect is stable for the lifetime of [sheetState]. Its snapshot collector
 * serializes Material animations instead of restarting (and cancelling) them
 * whenever `currentValue` / `targetValue` changes during an animation.
 */
@OptIn(ExperimentalMaterial3Api::class)
internal fun Modifier.composerImeAnchorPolicy(
    sheetState: SheetState,
): Modifier = composed {
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    var geometry by remember(sheetState) {
        mutableStateOf<ComposerModalSurfaceGeometry?>(null)
    }
    var autoExpandedFromPartial by remember(sheetState) { mutableStateOf(false) }
    var preservePreImeExpanded by remember(sheetState) { mutableStateOf(false) }

    LaunchedEffect(sheetState, density) {
        snapshotFlow {
            val imeBottomPx = imeInsets.getBottom(density)
            val surfaceTopPx = runCatching { sheetState.requireOffset() }.getOrNull()
            ComposerImeAnchorSnapshot(
                imeVisible = imeBottomPx > 0,
                overlapsKeyboard = composerModalSurfaceOverlapsIme(
                    geometry = geometry,
                    surfaceTopPx = surfaceTopPx,
                    imeBottomPx = imeBottomPx,
                ),
                currentValue = sheetState.currentValue,
                targetValue = sheetState.targetValue,
                hasPartiallyExpandedState = sheetState.hasPartiallyExpandedState,
                autoExpandedFromPartial = autoExpandedFromPartial,
            )
        }.collect { snapshot ->
            preservePreImeExpanded = updateComposerPreImeExpanded(
                previous = preservePreImeExpanded,
                snapshot = snapshot,
            )
            when (decideComposerImeAnchorAction(snapshot)) {
                ComposerImeAnchorAction.None -> Unit
                ComposerImeAnchorAction.ExpandFromPartial -> {
                    try {
                        // Isolate Material's mutation job from this long-lived
                        // collector. A user drag cancels only this supervised
                        // animation, leaving the collector active; composition
                        // disposal still cancels both scopes.
                        supervisorScope { sheetState.expand() }
                        autoExpandedFromPartial = composerImeOwnsExpansionAfter(
                            ComposerImeExpansionOutcome.Completed,
                            preservePreImeExpanded = preservePreImeExpanded,
                        )
                    } catch (cancelled: CancellationException) {
                        val effectDisposed = !currentCoroutineContext().isActive
                        autoExpandedFromPartial = composerImeOwnsExpansionAfter(
                            if (effectDisposed) {
                                ComposerImeExpansionOutcome.EffectDisposed
                            } else {
                                ComposerImeExpansionOutcome.InterruptedByUser
                            },
                            preservePreImeExpanded = preservePreImeExpanded,
                        )
                        // Material's mutation mutex cancels expand() when a
                        // user drag wins. Continue observing that user-owned
                        // result, but never swallow real effect disposal.
                        if (effectDisposed) throw cancelled
                    }
                }
                ComposerImeAnchorAction.RestorePartial -> {
                    // Release ownership before animating down. If a user's
                    // gesture supersedes this animation, their resulting
                    // expanded state remains user-owned.
                    autoExpandedFromPartial = false
                    try {
                        supervisorScope { sheetState.partialExpand() }
                    } catch (cancelled: CancellationException) {
                        if (!currentCoroutineContext().isActive) throw cancelled
                    }
                }
                ComposerImeAnchorAction.ReleaseOwnership -> {
                    autoExpandedFromPartial = false
                }
            }
        }
    }

    onGloballyPositioned { coordinates ->
        geometry = ComposerModalSurfaceGeometry(
            rootBottomPx = coordinates.findRootCoordinates().size.height,
            surfaceHeightPx = coordinates.size.height,
        )
    }
}

/**
 * Pure-renderer content for the sheet body. Pulled out of
 * [PromptComposerSheet] so the `@Preview`s can render the layout without
 * Hilt and without an [androidx.activity.ComponentActivity] in scope —
 * `ModalBottomSheet` itself doesn't preview inside `@Preview` (it needs
 * a window decor view), so the previews target this composable directly.
 */
@OptIn(ExperimentalComposeUiApi::class)
/**
 * Issue #1272: the composer's live binding to the durable undelivered-transcript
 * queue — the list to render plus the Retry / Dismiss callbacks.
 */
private data class ComposerUndeliveredBinding(
    val transcripts: List<UndeliveredTranscript>,
    val onRetry: (String) -> Unit,
    val onDismiss: (String) -> Unit,
)

/**
 * Issue #1272: resolve the ACTIVITY-scoped [InlineDictationViewModel] that owns
 * the durable undelivered-transcript queue and the delivery channel the session
 * screen collects.
 *
 * The composer sheet is composed inside the SAME activity-scoped
 * `ViewModelStoreOwner` as `TmuxSessionScreen`, so `hiltViewModel()` here returns
 * the SAME instance the session screen's `transcriptions` collector reads — a
 * Retry re-injects into that live channel and reaches the focused pane (or
 * re-persists if the pane is still gone). That is why the retry lives on the
 * shared inline-dictation VM, not a private composer copy.
 *
 * Guarded: composer render / connected tests mount [PromptComposerSheet] under a
 * plain (non-Hilt) `ComponentActivity` that has no `InlineDictationViewModel`
 * factory. Only a Hilt `@AndroidEntryPoint` activity (a
 * [GeneratedComponentManagerHolder]) can resolve the VM, so a non-Hilt owner
 * degrades to an inert empty surface instead of crashing. Tests that WANT the
 * surface pass [override] directly.
 */
@Composable
private fun rememberComposerUndeliveredBinding(
    override: InlineDictationViewModel?,
): ComposerUndeliveredBinding {
    val storeOwner = LocalViewModelStoreOwner.current
    val vm: InlineDictationViewModel? = override
        ?: if (storeOwner is GeneratedComponentManagerHolder) {
            hiltViewModel<InlineDictationViewModel>(storeOwner)
        } else {
            null
        }
    return if (vm != null) {
        val transcripts by vm.undeliveredTranscripts.collectAsState()
        ComposerUndeliveredBinding(
            transcripts = transcripts,
            onRetry = vm::retryUndelivered,
            onDismiss = vm::dismissUndelivered,
        )
    } else {
        ComposerUndeliveredBinding(
            transcripts = emptyList(),
            onRetry = {},
            onDismiss = {},
        )
    }
}

@Composable
internal fun SheetContent(
    state: PromptComposerViewModel.UiState,
    onClose: () -> Unit,
    onDraftChange: (String) -> Unit,
    onMicTap: () -> Unit,
    onSend: (withEnter: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onSnippets: (() -> Unit)? = null,
    onAttachFiles: (() -> Unit)? = null,
    // Issue #544/#566: remove a single staged attachment tile by remote path.
    // Defaults to a no-op so previews / legacy tests that don't stage
    // attachments keep compiling.
    onRemoveAttachment: (String) -> Unit = {},
    // Issue #174: dispatched by the "Discard" control rendered in the
    // recording panel while [PromptComposerViewModel.RecordingState] is
    // [PromptComposerViewModel.RecordingState.Recording]. Defaults to a no-op
    // so existing previews and legacy connected tests that bypass the
    // ViewModel keep compiling.
    onCancelRecording: () -> Unit = {},
    // Issue #453: dispatched by the "Cancel" control in Transcribing state.
    // Separate from recording discard because the audio/recognizer session
    // has already moved out of active capture.
    onCancelTranscription: () -> Unit = {},
    // Issue #746: throw away the current "Not sent" / unsent draft (text +
    // attachments + banner). Rendered as the Discard action inside the error
    // banner the maintainer reported had no way to clear a stale prompt.
    // Defaults to a no-op so previews / legacy tests keep compiling.
    onDiscard: () -> Unit = {},
    // Issue #180: queued failed / offline transcriptions. Defaults to
    // an empty list so older previews + tests that pre-date the queue
    // render the same composer shape they always did.
    pendingItems: List<PendingTranscriptionItem> = emptyList(),
    pendingListExpanded: Boolean = false,
    onTogglePendingList: () -> Unit = {},
    onRetryPending: (String) -> Unit = {},
    onDiscardPending: (String) -> Unit = {},
    onSavePendingAsAudio: (String) -> Unit = {},
    onAcknowledgeSavedAudio: () -> Unit = {},
    // Issue #900: visible committed-send queue for the current composer target.
    // Defaults keep previews and older render tests source-compatible.
    outboundQueueItems: List<OutboundItem> = emptyList(),
    outboundRetryBlockedByHealthyOwner: Boolean = false,
    outboundWireWritable: Boolean = true,
    outboundQueueExpanded: Boolean = false,
    onToggleOutboundQueue: () -> Unit = {},
    onDeleteOutboundItem: (String) -> Unit = {},
    onRetryOutboundItem: (String) -> Unit = {},
    onResendAllOutbound: () -> Unit = {},
    // Issue #767: detected engine for the focused pane — selects the
    // `AgentCommandCatalog` the `/`-autocomplete dropdown filters. Null on a
    // shell pane / preview, where the dropdown is never shown.
    agentKind: AgentKind? = null,
    // Issue #1272: the durable "couldn't deliver — retry" queue for voice
    // commands that were transcribed successfully but never reached a pane
    // (permanent pane death / channel overflow). Rendered in the sticky bottom
    // chrome so it stays visible above the soft keyboard. Empty default keeps
    // previews / legacy tests that don't wire it source-compatible.
    undeliveredTranscripts: List<UndeliveredTranscript> = emptyList(),
    onRetryUndelivered: (String) -> Unit = {},
    onDismissUndelivered: (String) -> Unit = {},
) {
    val isTranscribing = state.recording == PromptComposerViewModel.RecordingState.Transcribing
    val attachmentUploading =
        state.attachmentUpload as? PromptComposerViewModel.AttachmentUploadState.Uploading
    val retryableOutboundItem = retryableOutboundQueueItem(outboundQueueItems)

    // Issue #169 Part 1: hold the screen on while we are actively
    // capturing audio or waiting for Whisper. Without this, the system's
    // screen-timeout will fire mid-dictation, the lock screen tears down
    // audio focus, and the in-flight audio buffer is dropped before it
    // ever reaches Whisper. Lifecycle-bound via [DisposableEffect] so the
    // flag is *only* held during Recording / Transcribing — never
    // permanently, never after the sheet dismisses, and never while the
    // composer is just sitting idle waiting for the user to type.
    //
    // We toggle the View flag rather than the Window flag so the setting
    // is scoped to the composer's view subtree (a View owns its own
    // `keepScreenOn` bit which the WindowManager ORs across all attached
    // views to decide whether to suppress the screen-off timer). The
    // [onDispose] branch resets the bit regardless of why this
    // composition tore down — recreate, process kill, sheet dismiss, mic
    // tap that lands the FSM back in Idle — so we never leak the flag
    // past active capture.
    val keepScreenOnActive =
        state.recording == PromptComposerViewModel.RecordingState.Recording ||
            state.recording == PromptComposerViewModel.RecordingState.Transcribing
    val view = LocalView.current
    DisposableEffect(view, keepScreenOnActive) {
        view.keepScreenOn = keepScreenOnActive
        onDispose { view.keepScreenOn = false }
    }

    // Issue #491: own the composer's editor state as a [TextFieldValue] so
    // the Send button always sees the live visible text — including any
    // uncommitted IME composing region (predictive text / autocorrect
    // underline). With the old `String`-overload BasicTextField the composer
    // only saw text the IME had decided to *commit*; on a short prompt that
    // commit may not happen until the user manually presses Enter on the
    // soft keyboard, which is exactly why Send read an empty draft and did
    // nothing ("I had to summon the keyboard and hit Enter again").
    //
    // [draftFieldValue] is the source of truth for the editor; we mirror its
    // text into the ViewModel via [onDraftChange] on every edit, and we
    // re-sync from [state.draft] whenever the ViewModel pushes a draft change
    // we did not originate (dictation transcript append, send-clear, restore
    // of a failed send). The selection is clamped to the new length so the
    // cursor never lands out of bounds after an external append.
    var draftFieldValue by remember { mutableStateOf(TextFieldValue(state.draft)) }
    if (state.draft != draftFieldValue.text) {
        draftFieldValue = TextFieldValue(
            text = state.draft,
            selection = TextRange(state.draft.length),
        )
    }
    val draftFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    // Issue #767: the `/`-triggered inline command autocomplete. The dropdown is
    // OPEN when the draft's leading token starts with `/` and the caret sits in
    // it ([SlashCommandAutocomplete.slashQueryFor] is non-null), and is filtered
    // by everything typed after the `/`. The data comes from the existing
    // [AgentCommandCatalog] for the detected [agentKind] — no data work — so a
    // plain shell pane (null agent) yields an empty list and the dropdown stays
    // closed. Picking a row replaces the leading slash token with the chosen
    // command (a trailing space for arg-bearing commands so the caret lands ready
    // to type the argument) and keeps the field focused so the user can review +
    // Send. This is the SAME insert path #770 reuses (see [insertSlashCommand]).
    val slashQuery = SlashCommandAutocomplete.slashQueryFor(draftFieldValue)
    val commandSuggestions = if (slashQuery != null) {
        SlashCommandAutocomplete.filteredCommands(agentKind, slashQuery)
    } else {
        emptyList()
    }
    val showCommandDropdown = slashQuery != null && commandSuggestions.isNotEmpty()
    // Issue #767/#770: the single reusable insert entry point. Pre-fills the
    // composer field with a picked command (autocomplete) or any engine-command
    // string tapped in the terminal (#770) and re-focuses the field. Mirrors the
    // new value into the ViewModel draft so the source of truth stays in lockstep
    // (same contract as the editor's onValueChange).
    val insertSlashCommand: (AgentCommand) -> Unit = { command ->
        val updated = SlashCommandAutocomplete.insertCommand(draftFieldValue, command)
        draftFieldValue = updated
        onDraftChange(updated.text)
        draftFocusRequester.requestFocus()
    }

    // Issue #787: the `/` action-row button opens the SAME #767 dropdown — no
    // new picker. It seeds a leading `/` into the draft (idempotent: replaces an
    // existing leading slash token, otherwise prepends) and focuses the field,
    // which makes [SlashCommandAutocomplete.slashQueryFor] non-null with a blank
    // query so the full catalog appears. Mirrors into the ViewModel draft (same
    // contract as [insertSlashCommand]).
    val onSlashTap: () -> Unit = {
        val updated = SlashCommandAutocomplete.insertCommandText(draftFieldValue, "/")
        draftFieldValue = updated
        onDraftChange(updated.text)
        draftFocusRequester.requestFocus()
    }

    // Issue #491 / #682: the single Send path used by every Send affordance.
    //
    // Order matters for the #682 "Send opens the keyboard" regression:
    //
    //  1. Flush the live editor text (composing region included) into the
    //     ViewModel draft so the send always carries the visible text (#491).
    //  2. For an ordinary send, clear focus + hide the IME synchronously before
    //     dispatching so teardown cannot dispose the keyboard controller first.
    //     When enqueueing behind an active row, keep both: the sheet remains a
    //     live composer for the next prompt (#1621).
    //  3. Dispatch the send last.
    val focusManager = LocalFocusManager.current
    val commitAndSend: () -> Unit = {
        onDraftChange(draftFieldValue.text)
        // Issue #1621: enqueue-behind is still a composing workflow. Keep the
        // editor focused and the IME open while an older row owns delivery so
        // the user can continue with prompt C immediately. Preserve the
        // ordinary quiescent-send dismissal behaviour.
        if (!state.sendInFlight) {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
        }
        onSend(true)
    }
    val isResendMode = isComposerResendMode(
        draft = draftFieldValue.text,
        hasAttachments = state.attachments.isNotEmpty(),
        retryableItem = retryableOutboundItem,
        sendInFlight = state.sendInFlight,
    )
    val commitSendOrRetryQueued: () -> Unit = {
        if (isResendMode && retryableOutboundItem != null) {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
            onRetryOutboundItem(retryableOutboundItem.id)
        } else {
            commitAndSend()
        }
    }
    // Issue #682 / #567 / #1622: bound the composer body to the room this sheet's
    // host actually gives it.
    //
    // KEY FACT (issue #1622, replacing the #567-era one that stood here for six
    // rounds): **the host has ALREADY excluded the keyboard.** material3 1.3.2's
    // `ModalBottomSheet` wraps its whole window content in
    // `Box(Modifier.fillMaxSize().imePadding())` — verified in the 1.3.2 bytecode,
    // `ModalBottomSheetKt$ModalBottomSheet$3`, and visible on the maintainer's
    // device as a sheet whose bottom edge sits exactly on the keyboard top. So the
    // `maxHeight` this `BoxWithConstraints` receives is the room ABOVE the
    // keyboard already; there is nothing left to subtract.
    //
    // The comment this replaces asserted the opposite — that the dialog window
    // "does NOT reposition itself above the soft keyboard" and that a height cap
    // against `maxHeight - (ime - navBars)` was "the only reliable lever". That was
    // true of some older material3, but on 1.3.2 it made the code subtract the
    // keyboard TWICE, which is what held the body to ~173 dp of a genuinely
    // available ~485 dp. Its own recorded numbers already contained the
    // contradiction: a `maxHeight` of 470 dp on a 914 dp-tall Pixel 7 only
    // arithmetics if the container is IME-resized (un-resized it would have been
    // ~820 dp). Every patch in the chain (#567 -> #615 -> #682 -> #765 -> #790 ->
    // #801) was a different way of dividing too little room, and **the room was
    // never that little** — it was self-inflicted. Do not reintroduce an
    // `ime`-derived subtraction here; if a future material3 stops IME-padding its
    // container, `Issue1622ComposerSheetGeometryProofTest` goes red on the real
    // sheet and says so.
    //
    // History worth keeping (issue #567 squish): the code before that applied BOTH
    // `heightIn(max = maxHeight - ime)` AND `imePadding()` on the SAME Column.
    // `.heightIn().imePadding()` on one element makes the padding eat INTO the cap,
    // so the usable area collapsed to `maxHeight - 2*ime`. Neither is present now:
    // the cap below is not keyboard-derived at all, and there is no `imePadding()`
    // in this subtree (it would be a no-op anyway — the sheet consumed the inset;
    // issue #1812).
    val density = LocalDensity.current
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // `WindowInsets.ime` is read here for the keyboard-up FLAG only (which
        // chrome to show, which floor to use) — never to size the body. A raw
        // inset read is unaffected by the host's consumption, so the flag is
        // correct inside the IME-padded sheet.
        val imeBottomDp = with(density) { WindowInsets.ime.getBottom(this).toDp() }
        val keyboardUp = imeBottomDp > 0.dp
        // Issue #1622 (mechanism A's real requirement). Dropping `Top` from the
        // sheet's `contentWindowInsets` removed 45 dp of dead padding that was
        // charged in every state; what that padding was incidentally protecting is
        // a saturated sheet growing up UNDER the status bar / cutout. This cap is
        // that protection, and only that: it costs nothing until the body would
        // actually reach the unsafe strip, whereas the padding cost 45 dp always.
        val safeDrawingTopDp = with(density) {
            WindowInsets.safeDrawing.getTop(this).toDp()
        }
        val bodyMaxHeight = (maxHeight - safeDrawingTopDp).coerceAtLeast(0.dp)
        // Issue #801 (clean rebuild of the keyboard-up sizing; supersedes the
        // #790/#765 reserve-constant approach, D22 hard-cut):
        //
        // The layout is a proper chat-composer: a WEIGHTED scrollable draft that
        // takes exactly the room left after the fixed control row, and — keyboard-up
        // only — the "Prompt Composer" header is DROPPED (it is not needed while
        // actively typing; the close affordance is swipe-down / back / scrim, and
        // the header returns the moment the keyboard hides). The `weight(1f)` region
        // replaces the brittle hand-estimated
        // `PROMPT_SCROLL_REGION_IME_CHROME_RESERVE`/floor pair (both deleted): no
        // constant to drift, the control row always lays out, and a long draft grows
        // WITHIN the weighted region (the #682 long-content invariant) rather than
        // pushing the controls off-screen.
        //
        // Issue #1622 amends #801's PREMISE, not its shape. #801 reasoned from
        // "only ~175dp is available above the keyboard"; that budget was the
        // double-subtraction, not the device. With it gone the same weighted layout
        // has ~485dp keyboard-up, so the editor's designed 220dp ceiling is
        // reachable for the first time instead of being clipped at ~119dp. Hiding
        // the header keyboard-up stays — it is decoration while typing — but it is
        // no longer load-bearing triage.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // The nav-bar clearance only matters when the keyboard is DOWN. With
                // the keyboard up, Material's container `imePadding()` has already
                // consumed a bottom inset larger than the nav bar, so
                // `windowInsetsPadding` applies 0 here (issue #1812).
                //
                // Issue #1622 considered narrowing this to
                // `windowInsetsPadding(navigationBars.only(Bottom))` after a TEST
                // FIXTURE fabricated a nav-bar inset with a top edge and this call —
                // which pads every edge — turned it into a visible dead band. That
                // narrowing is deliberately NOT taken. No device reports a nav bar
                // above the content, and the horizontal edges (a landscape 3-button
                // bar) are already consumed by the sheet's
                // `contentWindowInsets = safeDrawing.only(Horizontal)`, so `.only(
                // Bottom)` is a no-op on every real device — production shaped
                // around a broken fixture, with no gate able to tell the two forms
                // apart. The fixture is fixed at the fixture, and
                // `dispatchSyntheticWindowInsets` now hard-fails any impossible
                // inset set before it reaches a window.
                .navigationBarsPadding()
                // Issue #1622: the ONLY height bound on the body, and it is not
                // keyboard-derived — `maxHeight` already excludes the keyboard (see
                // the KEY FACT above). This keeps a saturated sheet from growing up
                // under the status bar / cutout. The weighted region below absorbs a
                // long draft / a stack of tiles + banners so the sticky Send row is
                // never pushed off (the #682 long-content cut-off).
                .heightIn(max = bodyMaxHeight)
                .background(PocketShellColors.Surface)
                .padding(horizontal = 18.dp)
                // Keyboard-down keeps the visual breathing room above the nav
                // bar. Keyboard-up the IME itself is the bottom boundary; spending
                // another 26dp here would take a complete caret line away from the
                // weighted draft when status content is present (#1619).
                .padding(bottom = if (keyboardUp) 0.dp else 26.dp),
        ) {
            // Issue #767: the `/`-triggered inline command autocomplete dropdown.
            // It rides the top of this same inset-anchored column (so it is never
            // occluded by the keyboard — it sits above the field, which is above
            // the IME) and appears ONLY while the draft's leading token is a `/`
            // slash command. Tap a row to insert the command into the field.
            if (showCommandDropdown) {
                SlashCommandDropdown(
                    commands = commandSuggestions,
                    onPick = insertSlashCommand,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }

            // Header: title + close X. The Material 3 sheet draws its own
            // grabber above this, so we don't redraw it.
            //
            // Issue #765: the header is a FIXED top child of the parent Column —
            // it is NOT inside the scroll region below — so a focused draft can't
            // auto-scroll it off the top.
            //
            // Issue #801: keyboard-UP the header is HIDDEN. The header ("Prompt
            // Composer" title + close ×) costs ~58dp, and it is decoration while
            // actively typing: the user can dismiss via swipe-down / system back /
            // scrim tap, and the header returns the instant the keyboard hides. So
            // it renders only when the keyboard is DOWN.
            //
            // Issue #1622: #801 justified this by "only ~175dp is available above
            // the keyboard". That budget was a double-subtraction of the keyboard,
            // not a device limit, and is gone. The hide stays on the design
            // argument above — reclaiming ~58dp for the draft is still the right
            // trade while typing — but it is no longer triage.
            if (!keyboardUp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Prompt Composer",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.2).sp,
                        color = PocketShellColors.Text,
                    )
                    // Issue #701: the close target gets a subtle circular
                    // SurfaceElev chip (mockup `.sheet-close`, a 32dp 16dp-radius
                    // disc) so it reads as a deliberate dismiss affordance instead
                    // of a bare `×` glyph hanging in the corner.
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(
                                PocketShellColors.SurfaceElev,
                                androidx.compose.foundation.shape.CircleShape,
                            )
                            .clickable(role = Role.Button, onClick = onClose)
                            .testTag(COMPOSER_CLOSE_TAG)
                            .semantics { contentDescription = "Close composer" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "×",
                            color = PocketShellColors.TextSecondary,
                            fontSize = 20.sp,
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    // Issue #1619: status content owns a SEPARATE bounded scroll
                    // ABOVE the editor. It may scroll internally when several
                    // banners/tiles are present, but it can no longer make the
                    // 220dp editor a child of a smaller clipping viewport. 48dp
                    // keyboard-up is the standalone two-line Offline banner, whole.
                    // Keyboard-down keeps a roomier 96dp status history.
                    //
                    // Issue #1622 deliberately did NOT grow these caps with the
                    // reclaimed room: the whole point of the slice is that the
                    // reclaimed space goes to the DRAFT. A bounded status region is
                    // also the #1613 contract — banners stay a bounded strip above
                    // the field, never a growing stack that pushes it down.
                    .fillMaxWidth()
                    .heightIn(
                        max = if (keyboardUp) {
                            PromptComposerStatusRegionImeMaxHeight
                        } else {
                            PromptComposerStatusRegionMaxHeight
                        },
                    )
                    .testTag(COMPOSER_STATUS_VIEWPORT_TAG)
                    .verticalScroll(rememberScrollState()),
            ) {
            // Optional error / status banner above the mic row. Keeps the
            // user informed about permission / API-key / Whisper failures
            // without a Snackbar (the sheet doesn't host a scaffold).
            state.error?.let { msg ->
                // Issue #746: when the banner is shown while there is a draft
                // to clear (the "Not sent. …or discard the draft." case), give
                // the user the Discard action the copy refers to. Without it,
                // the maintainer had to delete the stale prompt character by
                // character. Errors with no draft (e.g. permission denied)
                // render the message alone.
                val canDiscard = state.draft.isNotEmpty() || state.attachments.isNotEmpty()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = PocketShellColors.AccentSoft,
                            shape = RoundedCornerShape(8.dp),
                        )
                        .padding(start = 12.dp, end = if (canDiscard) 4.dp else 12.dp)
                        .padding(vertical = if (canDiscard) 0.dp else 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = msg,
                        color = PocketShellColors.Accent,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .weight(1f)
                            .then(
                                if (canDiscard) Modifier.padding(vertical = 8.dp) else Modifier,
                            ),
                    )
                    if (canDiscard) {
                        TextButton(
                            onClick = onDiscard,
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .testTag(COMPOSER_DISCARD_TAG)
                                .semantics { contentDescription = "Discard draft" },
                        ) {
                            Text(
                                text = "Discard",
                                color = PocketShellColors.Accent,
                                fontSize = 13.sp,
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            val transferProgress by AttachmentUploadProgressPort.attachProgress.collectAsState()
            attachmentUploading?.let { upload ->
                AttachmentUploadProgressBanner(
                    count = upload.count,
                    progress = transferProgress,
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Issue #180: "saved to <path>" confirmation. One-shot — the
            // user taps it (or anything else) and the ViewModel clears the
            // field. Lives between the error banner and the queue banner
            // so the user sees the confirmation right next to the "save
            // as audio" affordance that produced it.
            state.savedAudioPath?.let { path ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onAcknowledgeSavedAudio)
                        .background(
                            color = PocketShellColors.SurfaceElev,
                            shape = RoundedCornerShape(8.dp),
                        )
                        .border(
                            width = 1.dp,
                            color = PocketShellColors.BorderSoft,
                            shape = RoundedCornerShape(8.dp),
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .testTag(COMPOSER_PENDING_SAVED_BANNER_TAG),
                ) {
                    Text(
                        text = "Audio saved to $path",
                        color = PocketShellColors.Text,
                        fontSize = 12.sp,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            PromptComposerQueueBanners(
                pendingItems = pendingItems,
                retryingIds = state.retryingIds,
                pendingListExpanded = pendingListExpanded,
                onTogglePendingList = onTogglePendingList,
                onRetryPending = onRetryPending,
                onDiscardPending = onDiscardPending,
                onSavePendingAsAudio = onSavePendingAsAudio,
                outboundQueueItems = outboundQueueItems,
                outboundRetryingIds = state.outboundRetryingIds,
                outboundRetryBlockedByHealthyOwner = outboundRetryBlockedByHealthyOwner,
                outboundWireWritable = outboundWireWritable,
                connectionDegraded = state.connectionDegraded,
                outboundQueueExpanded = outboundQueueExpanded,
                onToggleOutboundQueue = onToggleOutboundQueue,
                onDeleteOutboundItem = onDeleteOutboundItem,
                onRetryOutboundItem = onRetryOutboundItem,
                onResendAllOutbound = onResendAllOutbound,
            )

            // Issue #745: connection-lost indicator. Surfaced the moment the host
            // reports the SSH/tmux link is degraded/lost, BEFORE the user taps
            // Send, so a send into a dead link is never a silent blind wait.
            //
            // Issue #1613/#1619: this indicator lives in the independently bounded
            // status scroll ABOVE the draft, never in sticky bottom chrome and never
            // below a 220dp editor inside the editor's clipping viewport. This keeps
            // the warning visible while the weighted field owns the actual remaining
            // room and native caret-follow. Once Send queues a prompt, the mutually-
            // exclusive OutboundQueueBanner carries the ongoing status instead.
            //
            // Issue #971/#987 (Option A): once there is a queued outbound prompt
            // waiting, the OutboundQueueBanner already carries the SINGLE coherent
            // "Will send when reconnected." status. Showing this standalone
            // "Connection lost — Send will retry once reconnected." banner on top of
            // it is the exact duplicated/contradictory stack the maintainer reported
            // on #987 — so suppress it whenever the queue banner is present. With no
            // queued prompt the standalone indicator still warns BEFORE the first Send.
            if (state.connectionDegraded && outboundQueueItems.isEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            PocketShellColors.Amber.copy(alpha = 0.12f),
                            RoundedCornerShape(8.dp),
                        )
                        // Two explicit 16dp text lines plus 4dp vertical padding
                        // keep the whole Amber banner inside the calibrated 48dp
                        // keyboard-up status viewport.
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .testTag(COMPOSER_CONNECTION_LOST_TAG),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.CloudOff,
                        contentDescription = null,
                        tint = PocketShellColors.Amber,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = "Offline — prompts will be queued and sent on reconnect.",
                        color = PocketShellColors.Amber,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    )
                }
            }
        }

        // Issue #453/#1619/#2057: the FLEXIBLE compose area — the editor plus, when
        // attachments are staged, the tile strip directly under it. It is the ONLY
        // weighted child of the sheet body, so it takes exactly the room left after
        // the bounded status region and the sticky controls row, and it can never
        // push those controls under the keyboard (a Column measures its non-weighted
        // children FIRST, so an unweighted tile strip here would claim its 64dp
        // before the controls row is measured — the #567/#682/#1744 squish).
        //
        // `BoxWithConstraints` is what makes the split inside this area principled
        // instead of a hand-tuned constant: `maxHeight` IS the flexible room, so the
        // strip can be capped at exactly "whatever is left above the editor's
        // one-line floor". The editor is then the weighted child INSIDE, so it
        // absorbs the remainder and keeps BasicTextField's native caret-follow as
        // the only editor scroll (#1619). With no banners the strip shows a full
        // tile row and the editor takes the rest; with the Offline banner also up
        // the strip shrinks to a scrollable sliver rather than crushing the editor.
        // Neither case can move the controls row.
        //
        // Issue #1622: keyboard-up this area went from ~125dp of flexible room to
        // ~425dp once the double-subtracted keyboard was removed, so the editor's
        // designed 220dp ceiling is now reachable with the tile strip still at its
        // cap — the two no longer compete on a real draft.
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false),
        ) {
            val flexibleRoom = maxHeight
            val editorFloor = if (keyboardUp) {
                PromptComposerDraftImeOneLineFloor
            } else {
                PromptComposerDraftOneLineFloor
            }
            val attachmentStripCap = if (keyboardUp) {
                PromptComposerAttachmentRegionImeMaxHeight
            } else {
                PromptComposerAttachmentRegionMaxHeight
            }
            val attachmentStripMax =
                (flexibleRoom - editorFloor - PromptComposerAttachmentStripGap)
                    .coerceIn(0.dp, attachmentStripCap)

            Column(modifier = Modifier.fillMaxWidth()) {
                // The primary surface is state-driven. Idle makes the real editor
                // the weighted child of this area, so its effective max is the
                // actual room left beside the tile strip; no outer draft scroll
                // competes with or clips its caret-follow.
                when (state.recording) {
                    PromptComposerViewModel.RecordingState.Recording -> {
                        RecordingSurface(
                            amplitude = state.amplitude,
                            capturing = state.hasDetectedSpeech,
                            elapsedLabel = formatElapsed(state.recordingElapsedMs),
                            liveTranscript = state.liveTranscript,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }

                    PromptComposerViewModel.RecordingState.Transcribing -> {
                        Box(modifier = Modifier.weight(1f, fill = false)) {
                            TranscribingSurface()
                        }
                    }

                    PromptComposerViewModel.RecordingState.Idle -> {
                        ComposerDraftField(
                            value = draftFieldValue,
                            onValueChange = { newValue ->
                                draftFieldValue = newValue
                                if (newValue.text != state.draft) {
                                    onDraftChange(newValue.text)
                                }
                            },
                            placeholder = COMPOSER_PLACEHOLDER,
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .testTag(COMPOSER_DRAFT_VIEWPORT_TAG),
                            fieldTag = COMPOSER_DRAFT_TAG,
                            minHeight = if (keyboardUp) 24.dp else 96.dp,
                            maxHeight = 220.dp,
                            focusRequester = draftFocusRequester,
                        )
                    }
                }

                // Issue #566/#2057: staged attachments render as compact square
                // tiles BELOW the draft field — between the editor and the bottom
                // controls row (📎 · {} · / · Send · mic), ChatGPT / Claude style.
                // The draft text stays clean while composing; remote paths are
                // folded into the outgoing prompt only at SEND.
                //
                // #1619 had temporarily hoisted the tiles into the bounded status
                // scroll region ABOVE the editor, as a side effect of making the
                // editor the single weighted caret-follow child. The maintainer
                // reported that placement as a regression, so the strip is back
                // under the field. Only the tiles moved: the offline /
                // connection-lost and queue banners stay in the #1613 status
                // region above the draft.
                if (state.attachments.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(PromptComposerAttachmentStripGap))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = attachmentStripMax)
                            .testTag(COMPOSER_ATTACHMENT_VIEWPORT_TAG)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        AttachmentTileGrid(
                            attachments = state.attachments,
                            onRemove = onRemoveAttachment,
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(if (keyboardUp) 2.dp else 4.dp))

        // Issue #1272: the durable "couldn't deliver — retry" surface. A voice
        // command that was transcribed successfully but never reached a pane
        // (the session was permanently gone by the time Whisper resolved, or the
        // bounded delivery channel overflowed) is persisted and surfaced HERE, in
        // the composer's sticky bottom chrome — OUTSIDE the scrollable upper
        // region so it stays visible directly above the Send row even with the
        // soft keyboard up. Tapping Retry re-injects the text into the shared
        // inline-dictation delivery channel (re-delivered to the focused pane, or
        // re-persisted if the pane is still gone); Dismiss discards it. Before
        // this wiring the retry item had no user-visible surface at all, so the
        // maintainer's dictate-then-vanish symptom had no recovery affordance.
        if (undeliveredTranscripts.isNotEmpty()) {
            UndeliveredTranscriptBanner(
                items = undeliveredTranscripts,
                onRetry = onRetryUndelivered,
                onDismiss = onDismissUndelivered,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        // Issue #453 / #508: the single decluttered controls row at the
        // bottom of the composer, matching all four mockup states:
        //  - Left: 📎 attach (paperclip) + `{}` snippets — always present.
        //  - Right, Idle / Text-inserted: a single primary Send button with
        //    a send-arrow glyph (the old Insert/Send pair collapses to one).
        //  - Recording surface: explicit "Discard" stops the mic/recognizer
        //    and drops the buffer without transcription/final insertion.
        //  - Right, Recording: two explicit stop actions — "Insert"
        //    (stop + transcribe into the editable field, nothing sent) and
        //    "Send" (stop + transcribe + send). The old persistent Auto-send
        //    toggle is gone (#508): the choice is made per-recording.
        //  - Right, Transcribing: Cancel + "Send" (arms the queued send for
        //    the in-flight round-trip).
        val attachmentBusy = attachmentUploading != null
        // Issue #1152 / #1245: one bottom controls row. Idle / Transcribing anchor
        // the editing-tools group (📎 attach · {} snippets · / command) on the left,
        // then a weighted gap, then the state-driven right cluster.
        //
        // During Recording the row is a single BALANCED action row —
        // [Discard · Insert · Send] — right-aligned. Issue #1245 removed the
        // hands-free Lock entirely (both the pill AND the swipe-up-to-lock gesture),
        // so this row no longer carries a pointerInput and no longer needs the
        // four-pill two-row stack: the three stop actions fit comfortably on one
        // line at font scale 1.0 and 1.3 without clipping `Send` (the #1152 guard).
        // The editing tools are not shown mid-dictation (attach/snippets/slash are
        // text-composition tools, not usable while the mic is live), which is what
        // lets Discard sit right next to Insert and Send in one clean row.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.recording != PromptComposerViewModel.RecordingState.Recording) {
                ComposerEditingToolsGroup(
                    isTranscribing = isTranscribing,
                    attachmentBusy = attachmentBusy,
                    agentKind = agentKind,
                    onAttachFiles = onAttachFiles,
                    onSnippets = onSnippets,
                    onSlashTap = onSlashTap,
                )
            }
            Spacer(modifier = Modifier.weight(1f))

            when (state.recording) {
                PromptComposerViewModel.RecordingState.Idle -> {
                    // Issue #453/#701: single primary Send pill + the mic disc.
                    // Issue #491: gate Send on the LIVE editor text (or staged
                    // attachments), not the possibly-stale ViewModel draft, and
                    // disable while a send is in flight (#745). [commitAndSend]
                    // flushes the live text before dispatching so the tap delivers.
                    val hasComposition = draftFieldValue.text.isNotEmpty() ||
                        state.attachments.isNotEmpty()
                    val sendEnabled = !state.outboundHandoffInProgress &&
                        (hasComposition ||
                            (retryableOutboundItem != null && !state.sendInFlight))
                    SendButton(
                        onClick = commitSendOrRetryQueued,
                        enabled = sendEnabled,
                        sending = showComposerSendProgress(state.sendInFlight, outboundQueueItems),
                        label = if (isResendMode) "Resend" else "Send",
                        modifier = Modifier.testTag(COMPOSER_SEND_ENTER_TAG),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    // Small cyan mic disc at the far right (the mockup's mic). A tap
                    // starts a dictation (Idle → Recording).
                    MicTriggerButton(
                        onClick = onMicTap,
                        enabled = true,
                        modifier = Modifier.testTag(COMPOSER_MIC_TAG),
                    )
                }

                PromptComposerViewModel.RecordingState.Recording -> {
                    // Issue #1245: a single balanced action row — Discard sits right
                    // next to Insert and Send, so when the user records a voice
                    // message and decides not to include it, Discard is right there
                    // with the other stop actions. Every pill shares one 48dp
                    // baseline (audit D4). With the hands-free Lock removed there are
                    // only three pills, which fit on one right-aligned line without
                    // clipping `Send` at font scale 1.0 and 1.3 (the #1152 guard).
                    //  - [Discard]: drop this audio — an outlined secondary pill.
                    //  - [Insert]: stop + transcribe into the field (nothing sent).
                    //  - [Send]: stop + transcribe + send (the accent primary).
                    DiscardRecordingButton(
                        onClick = onCancelRecording,
                        modifier = Modifier.testTag(COMPOSER_CANCEL_RECORDING_TAG),
                    )
                    ToFieldButton(
                        onClick = onMicTap,
                        modifier = Modifier.testTag(COMPOSER_TO_FIELD_TAG),
                    )
                    StopSendButton(
                        onClick = { onSend(true) },
                        modifier = Modifier.testTag(COMPOSER_STOP_SEND_TAG),
                    )
                }

                PromptComposerViewModel.RecordingState.Transcribing -> {
                    // Issue #508: Cancel aborts the in-flight round-trip; Send arms
                    // the queued send. These two fit on one line next to the tools.
                    GhostButton(
                        label = "Cancel",
                        onClick = onCancelTranscription,
                        modifier = Modifier.testTag(COMPOSER_CANCEL_TRANSCRIPTION_TAG),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    StopSendButton(
                        onClick = { onSend(true) },
                        modifier = Modifier.testTag(COMPOSER_STOP_SEND_TAG),
                    )
                }
            }
        }
        }
    }
}

/**
 * Issue #900: single-consumer dispatcher for the composer's one-shot
 * [PromptComposerViewModel.sendRequests] channel. Production tmux screens mount
 * this at screen scope so reconnect/foreground queue flushes can deliver while
 * the sheet is closed; standalone sheet tests/previews can still mount it from
 * [PromptComposerSheet].
 */
@Composable
public fun PromptComposerSendDispatcher(
    viewModel: PromptComposerViewModel,
    onSend: suspend (PromptComposerViewModel.SendRequest) -> ComposerSendResult,
    // Historical name retained for source compatibility. Durable rows invoke
    // it at local acceptance; no-row fallback sends invoke it after delivery.
    onDelivered: () -> Unit = {},
) {
    val currentOnSend by rememberUpdatedState(onSend)
    val currentOnDelivered by rememberUpdatedState(onDelivered)
    LaunchedEffect(viewModel) {
        try {
            viewModel.handoffAcceptances.collect { acceptance ->
                try {
                    // Dismiss at local acceptance, before a potentially slow
                    // host callback. The epoch check keeps newer composition.
                    viewModel.beforeHandoffAutoCloseReductionForTest()
                    if (viewModel.consumeHandoffAcceptanceForAutoClose(acceptance)) {
                        currentOnDelivered()
                        // The state write above only schedules recomposition. Do not
                        // let a synchronous host write monopolise Main before the
                        // screen owner has removed the accepted empty sheet.
                        withFrameNanos { }
                    }
                } finally {
                    // Target changes and newer drafts intentionally reject close;
                    // that decision must release wire delivery immediately.
                    viewModel.completeHandoffAcceptanceReduction(acceptance)
                }
            }
        } finally {
            // Screen disposal cancels both collectors. Never strand a durable
            // row behind a reduction whose UI owner no longer exists.
            viewModel.completeAllHandoffAcceptanceReductions()
        }
    }
    LaunchedEffect(viewModel) {
        collectPromptComposerSendRequests(
            viewModel = viewModel,
            onSend = { currentOnSend(it) },
            onDelivered = { currentOnDelivered() },
        )
    }
}

/**
 * Issue #767: the `/`-triggered inline command autocomplete dropdown. A compact
 * floating list of [AgentCommand]s that rides the top of the composer's
 * inset-anchored column — the Slack / Discord / ChatGPT slash-command pattern
 * the maintainer asked for: type `/` and a filtered command list appears, tap a
 * row to insert it into the field, ready to review + Send.
 *
 * Issue #791 redesign: each row reads like a clean command palette — the
 * command token (`/clear`) LEADS in mono + agent-accent as the primary
 * affordance, an inline `<arg>` hint follows for argument-taking commands
 * (so `/goal <goal>` reads as one signature), and a short human description
 * wraps below (≤2 lines, no mid-word truncation). There is exactly ONE command
 * per row — the old duplicate right-side command badge is gone (D22 hard-cut).
 * The list is height-capped and self-scrolls so a long catalog never pushes the
 * field/controls off the screen.
 */
@Composable
internal fun SlashCommandDropdown(
    commands: List<AgentCommand>,
    onPick: (AgentCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Issue #791: a clean command-palette dropdown. Each row LEADS with the
    // command token (the thing the user is selecting) in mono, accent-coloured
    // text — argument-taking commands append an inline `<placeholder>` hint so
    // `/goal <goal>` reads as one signature instead of a bare `+arg` chip — with
    // a short human description below that WRAPS (≤2 lines) instead of truncating
    // mid-word. The old design's right-side `Badge` duplicated the token, so it
    // is gone (D22 hard-cut): the leading mono token is the single, prominent
    // command per row. Rows are tighter (8dp vertical padding) so more commands
    // fit above the keyboard.
    val agentAccent = LocalPocketShellSemantic.current.agentAccent
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = SLASH_DROPDOWN_MAX_HEIGHT)
            .clip(RoundedCornerShape(12.dp))
            .background(PocketShellColors.SurfaceElev, RoundedCornerShape(12.dp))
            .border(
                width = 1.dp,
                color = PocketShellColors.BorderSoft,
                shape = RoundedCornerShape(12.dp),
            )
            .verticalScroll(rememberScrollState())
            .testTag(COMPOSER_SLASH_DROPDOWN_TAG),
    ) {
        commands.forEach { command ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { onPick(command) }
                    .testTag(composerSlashCommandRowTag(command.command))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                // Primary affordance: the command token, mono + accent, with an
                // inline `<arg>` hint when the command takes one so the argument
                // is communicated as part of the command signature.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = command.command,
                        color = agentAccent,
                        style = PocketShellType.bodyMono,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    command.argument?.let {
                        Text(
                            text = slashArgumentHint(command.command),
                            color = PocketShellColors.TextMuted,
                            style = PocketShellType.bodyMono,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    text = command.description,
                    color = PocketShellColors.TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Issue #791: a short, lower-case inline argument hint for an argument-taking
 * slash command, e.g. `/goal` → `<goal>`. Derived from the command token (drop
 * the leading `/`) so the hint always matches the command and stays terse — the
 * verbose [AgentCommandArgument.placeholder] is the field-level prompt shown
 * once the argument is actually being typed, not the one-glance dropdown hint.
 */
private fun slashArgumentHint(command: String): String =
    "<${command.removePrefix("/")}>"


/**
 * Issue #174 / #1152: explicit recording-only discard control. It stops the
 * mic/recognizer and drops the live buffer, distinct from the header close `×`
 * that dismisses the whole composer.
 *
 * Issue #1152: pulled OUT of the waveform surface (where its bare muted text
 * collided with the amplitude bars and read as disabled — audit D2/D3) into the
 * bottom action row as a proper OUTLINED secondary pill on the shared recording
 * pill baseline (44dp visual / 48dp touch). The `testTag` is now supplied by the
 * caller via [modifier] (it moved from the surface to the row), so the tap-target
 * node measured by tests includes the touch inset.
 */
@Composable
private fun DiscardRecordingButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(ComposerActionPillHeight)
            .clip(ComposerActionPillShape)
            .background(PocketShellColors.SurfaceElev, ComposerActionPillShape)
            .border(1.dp, PocketShellColors.Border, ComposerActionPillShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Discard recording without transcribing" }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Discard",
            color = PocketShellColors.TextSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// Genuine sub-ladder component geometry: the composer's 44–48dp action pills
// (Send / Stop / To-field / Discard) read as fully-rounded "pills", which needs a
// half-height radius the named ladder rungs (8/14/20/28dp — design-system.md
// "Radius, Elevation, And Borders") don't provide. 22dp is that pill radius for
// this control height; named here (vs an inline literal) so it stays a single
// intentional token rather than off-ladder drift. See docs/design-system.md.
private val ComposerActionPillRadius = 22.dp
private val ComposerActionPillShape = RoundedCornerShape(ComposerActionPillRadius)

// Issue #1152: one baseline HEIGHT for EVERY recording-row pill (Discard /
// Insert / Send) so the row reads as a single deliberate control ladder instead
// of the old 40/44/48dp mix (audit D4). 48dp is the design-system tapTargetMin,
// so a single 48dp pill is both the visual baseline and a full touch target —
// simpler and more robust than a 44dp fill + touch-inset padding (the inset is
// not reflected in the tagged node's measured bounds, so it under-reports the
// touch target below 48dp — the PromptComposerCancelRecordingTest ≥48dp guard).
private val ComposerActionPillHeight = 48.dp

/**
 * Issue #1152: the editing tools group (📎 attach · {} snippets · / command) as a
 * single rounded `SurfaceElev` pill. It stays MOUNTED in every composer state
 * (Idle, Recording, Transcribing) — the maintainer's directive is to fit all
 * controls, never hide them — so it is factored out here and rendered from each
 * state branch of the bottom controls. Attachment picking stays single-flight
 * while a batch is uploading; the slash entry is disabled on shell panes
 * (`agentKind == null`, #787) and everything is disabled during transcription.
 */
@Composable
private fun ComposerEditingToolsGroup(
    isTranscribing: Boolean,
    attachmentBusy: Boolean,
    agentKind: AgentKind?,
    onAttachFiles: (() -> Unit)?,
    onSnippets: (() -> Unit)?,
    onSlashTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(PocketShellColors.SurfaceElev, RoundedCornerShape(22.dp))
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        AttachIconButton(
            onClick = { onAttachFiles?.invoke() },
            enabled = !isTranscribing && !attachmentBusy && onAttachFiles != null,
            modifier = Modifier.testTag(COMPOSER_ATTACH_TAG),
        )
        SnippetsIconButton(
            onClick = { onSnippets?.invoke() },
            enabled = !isTranscribing && !attachmentBusy && onSnippets != null,
            modifier = Modifier.testTag(COMPOSER_SNIPPETS_TAG),
        )
        SlashIconButton(
            onClick = onSlashTap,
            enabled = !isTranscribing && !attachmentBusy && agentKind != null,
            modifier = Modifier.testTag(COMPOSER_SLASH_TAG),
        )
    }
}

/**
 * Issue #453 / #701: the single primary Send affordance for the Idle / Text-
 * inserted states. Always submits with Enter (`onSend(true)`), since the
 * composer's job is to send a prompt.
 *
 * Issue #701 (visual polish): the earlier #453 iteration stripped Send down to
 * a borderless cyan text+arrow, which the maintainer found weak — it read as a
 * plain link, not the row's primary commit action, and left the controls row
 * looking unfinished. The composer mockup (`docs/mockups/composer.html`,
 * `.btn.primary`) renders the submit action as a FILLED accent pill. We restore
 * that here: an enabled Send is a solid `Accent` pill with `OnAccent` ink + the
 * send arrow, matching the recording-state [StopSendButton] (so the two Send
 * affordances finally look like the same button) and giving the row a clear
 * primary anchor. Disabled Send drops to a muted `SurfaceElev` fill so the row
 * never has a "dead" accent shape begging to be tapped on an empty draft.
 */
@Composable
private fun SendButton(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    // Issue #745: while true the button reads "Sending…" with an inline
    // spinner instead of the send arrow, and is non-interactive. This is the
    // immediate in-flight feedback the moment the user taps Send.
    sending: Boolean = false,
    label: String = "Send",
) {
    // While sending the pill keeps the accent fill (it is an active commit in
    // progress, not a disabled empty-draft state) but is non-interactive.
    val containerColor = when {
        sending -> PocketShellColors.Accent
        enabled -> PocketShellColors.Accent
        else -> PocketShellColors.SurfaceElev
    }
    val contentColor = when {
        sending -> PocketShellColors.OnAccent
        enabled -> PocketShellColors.OnAccent
        else -> PocketShellColors.TextMuted
    }
    Row(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(color = containerColor, shape = RoundedCornerShape(22.dp))
            .clickable(enabled = enabled && !sending, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(
            text = if (sending) "Sending…" else label,
            color = contentColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (sending) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(16.dp)
                    .testTag(COMPOSER_SEND_IN_FLIGHT_TAG),
                color = contentColor,
                strokeWidth = 2.dp,
            )
        } else {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * Issue #508 / #580: "Insert" stop action shown while Recording. Tapping it stops
 * the recording and transcribes, dropping the resulting text into the
 * editable composer field — nothing is sent. The user can then attach a
 * screenshot / edit before tapping Send manually. Rendered as a clear
 * outlined pill (secondary affordance) so it reads as a deliberate,
 * non-sending choice next to the accent-filled Send pill.
 */
@Composable
private fun ToFieldButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            // Issue #1152: shared 48dp recording pill baseline.
            .height(ComposerActionPillHeight)
            .background(
                color = PocketShellColors.SurfaceElev,
                shape = ComposerActionPillShape,
            )
            .border(
                width = 1.dp,
                color = PocketShellColors.Border,
                shape = ComposerActionPillShape,
            )
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Insert transcript into prompt" }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Insert",
            color = PocketShellColors.Text,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Issue #508: "Send" stop action shown while Recording / Transcribing.
 * Tapping it stops the recording (or arms the in-flight transcription) and
 * sends the dictation immediately once Whisper returns. Rendered as the
 * accent-filled primary pill with a send arrow so it reads as the
 * commit/submit action next to the secondary "Insert" choice.
 */
@Composable
private fun StopSendButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            // Issue #1152: shared 48dp recording pill baseline.
            .height(ComposerActionPillHeight)
            .background(
                color = PocketShellColors.Accent,
                shape = ComposerActionPillShape,
            )
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Stop and send the transcript" }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Send",
            color = PocketShellColors.OnAccent,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.Send,
            contentDescription = null,
            tint = PocketShellColors.OnAccent,
            modifier = Modifier.size(17.dp),
        )
    }
}

/**
 * Issue #453: the mic trigger shown in the Idle / Text-inserted states.
 * Tapping it starts a dictation (transitions the FSM to Recording). Cyan
 * accent FAB matching the design-system dictation accent (#461). Consumes
 * [LocalPocketShellSemantic] for the recording accent.
 */
@Composable
private fun MicTriggerButton(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val accent = LocalPocketShellSemantic.current.accent
    Box(
        modifier = modifier
            .size(44.dp)
            .background(
                color = if (enabled) accent else PocketShellColors.SurfaceElev,
                shape = androidx.compose.foundation.shape.CircleShape,
            )
            // Issue #1245 follow-up: a REAL touch handler. The mic disc's finger
            // tap used to be serviced by the outer Row's swipe-up-to-lock
            // pointerInput (onPressStart → onMicTap); that gesture was deleted
            // with the lock, so the mic needs its own `.clickable` — a bare
            // `.semantics { onClick }` is accessibility-only and never fires on a
            // real tap. `enabled = enabled` keeps the disabled state un-tappable.
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics {
                contentDescription = "Start dictation"
            },
        contentAlignment = Alignment.Center,
    ) {
        // Issue #453: render the proper Material-style filled microphone
        // glyph (the shared [DictateDotIcon] ImageVector reused from
        // VoiceSessionSurface) instead of the old `Text("●")` dot, which
        // read as a record / power button rather than a mic.
        Icon(
            imageVector = DictateDotIcon,
            contentDescription = null,
            tint = if (enabled) PocketShellColors.OnAccent else PocketShellColors.TextMuted,
            modifier = Modifier.size(22.dp),
        )
    }
}

// Issue #701: the secondary tool icons sit inside a grouped SurfaceElev pill,
// so each individual hit target shrinks slightly (40dp) to keep the group neat
// and the glyph trims to 20dp — still a comfortable touch target, but the
// cluster reads as one tidy control rather than two oversized ghost buttons.
private val COMPOSER_ACTION_ICON_BUTTON_SIZE = 40.dp
private val COMPOSER_ACTION_ICON_SIZE = 20.dp

/**
 * Issue #453: compact paperclip attach button. Uses the official Material
 * attach-file icon rather than a hand-traced path, so the glyph weight and
 * silhouette match the rest of the action row.
 */
@Composable
private fun AttachIconButton(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .size(COMPOSER_ACTION_ICON_BUTTON_SIZE)
            .semantics { contentDescription = "Attach files" },
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = PocketShellColors.TextSecondary,
            disabledContentColor = PocketShellColors.TextMuted,
        ),
    ) {
        Icon(
            imageVector = Icons.Outlined.AttachFile,
            contentDescription = null,
            modifier = Modifier.size(COMPOSER_ACTION_ICON_SIZE),
        )
    }
}

/**
 * Issue #453: snippets button. Uses Material's data-object/code icon instead
 * of a text `{ }` glyph, keeping the toolbar iconography consistent while
 * preserving the curly-brace mental model from the mockup.
 */
@Composable
private fun SnippetsIconButton(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .size(COMPOSER_ACTION_ICON_BUTTON_SIZE)
            .semantics { contentDescription = "Insert snippet" },
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = PocketShellColors.TextSecondary,
            disabledContentColor = PocketShellColors.TextMuted,
        ),
    ) {
        Icon(
            imageVector = Icons.Outlined.DataObject,
            contentDescription = null,
            modifier = Modifier.size(COMPOSER_ACTION_ICON_SIZE),
        )
    }
}

/**
 * Issue #787: the `/` slash-command button — the single consolidated entry
 * point for slash commands. A tap opens the SAME #767 autocomplete dropdown (it
 * seeds a leading `/` into the draft + focuses the field, which makes
 * [SlashCommandAutocomplete.slashQueryFor] non-null and renders the full
 * catalog); there is NO separate picker. Rendered as a literal `/` glyph to
 * match the maintainer's "add a `/`" mental model and the `/`-token it opens,
 * and sized/coloured identically to its 📎 / `{}` siblings.
 */
@Composable
private fun SlashIconButton(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .size(COMPOSER_ACTION_ICON_BUTTON_SIZE)
            .semantics { contentDescription = "Slash commands" },
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = PocketShellColors.TextSecondary,
            disabledContentColor = PocketShellColors.TextMuted,
        ),
    ) {
        Text(
            text = "/",
            // Sized to read at the same visual weight as the 20dp Material
            // glyphs in its 📎 / `{}` siblings; mono so the `/` matches the
            // slash-command token it opens.
            style = PocketShellType.bodyMono.copy(fontSize = 20.sp),
            fontWeight = FontWeight.SemiBold,
        )
    }
}


@Composable
private fun GhostButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier
            .height(44.dp),
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(contentColor = PocketShellColors.TextSecondary),
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * Minimal one-field dialog for entering an OpenAI API key. The real
 * settings screen is a follow-up — this dialog is just the first-tap
 * fallback so the user is not blocked out of recording.
 */
@Composable
internal fun ApiKeyEntryDialog(
    onDismiss: () -> Unit,
    onSave: (CharArray) -> Unit,
) {
    var keyText by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("OpenAI API key", color = PocketShellColors.Text) },
        text = {
            Column {
                Text(
                    text = "Paste your OpenAI API key. It's stored encrypted on this device " +
                        "and only sent in the Authorization header to api.openai.com.",
                    color = PocketShellColors.TextSecondary,
                    fontSize = 12.sp,
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = keyText,
                    onValueChange = { keyText = it },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val chars = keyText.toCharArray()
                    onSave(chars)
                    // Zero the editor-local copy. (Compose holds the
                    // String in `keyText`; nothing we can do about that
                    // without bypassing the framework.)
                    keyText = ""
                },
                enabled = keyText.isNotBlank(),
            ) {
                Text("Save", color = PocketShellColors.Accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = PocketShellColors.TextSecondary)
            }
        },
        containerColor = PocketShellColors.Surface,
        titleContentColor = PocketShellColors.Text,
        textContentColor = PocketShellColors.TextSecondary,
    )
}

internal const val COMPOSER_DRAFT_TAG = "prompt-composer-draft"

/**
 * Issue #746: the Discard action rendered inside the error/status banner.
 * Tagged so the discard-draft regression test can tap the real control that
 * clears a stale "Not sent" prompt (text + attachments + banner) — the
 * affordance the banner's "or discard the draft" copy refers to.
 */
internal const val COMPOSER_DISCARD_TAG = "prompt-composer-discard"

/**
 * Issue #511: the header `×` close button. Tagged so the dismiss-releases-
 * mic regression test can drive the real close affordance (the same path
 * the maintainer used when mid-recording dismiss leaked the microphone).
 */
internal const val COMPOSER_CLOSE_TAG = "prompt-composer-close"

/**
 * Issue #453: the single primary Send button (Idle / Text-inserted states).
 * Always submits with Enter — the Insert/Send pair collapsed to one. The
 * tag keeps the historic `…-send-enter` value so existing connected tests
 * that locate the Send affordance keep resolving the same node.
 */
internal const val COMPOSER_SEND_ENTER_TAG = "prompt-composer-send-enter"

/**
 * Issue #745: the inline spinner shown inside the Send button while a send is
 * in flight. Its presence is the immediate "Sending…" feedback the moment the
 * user taps Send; it disappears once the send resolves (delivered or failed).
 */
internal const val COMPOSER_SEND_IN_FLIGHT_TAG = "prompt-composer-send-in-flight"

/**
 * Issue #745: the connection-lost indicator above the Send row, shown whenever
 * the host reports the SSH/tmux link is degraded/lost so a send into a dead
 * link is never a silent blind wait.
 */
internal const val COMPOSER_CONNECTION_LOST_TAG = "prompt-composer-connection-lost"

/** Issue #1619: bounded viewport that owns the draft's keyboard-up geometry. */
internal const val COMPOSER_DRAFT_VIEWPORT_TAG = "prompt-composer-draft-viewport"

/** Issue #1619: independently scrollable status region above the draft. */
internal const val COMPOSER_STATUS_VIEWPORT_TAG = "prompt-composer-status-viewport"

/**
 * Issue #2057: the bounded, independently scrollable strip that hosts the staged
 * attachment tiles BELOW the draft field and above the sticky controls row.
 * Present only while at least one attachment is staged.
 */
internal const val COMPOSER_ATTACHMENT_VIEWPORT_TAG = "prompt-composer-attachment-viewport"

/**
 * Issue #767: test tags for the `/`-autocomplete dropdown. Connected tests
 * assert the dropdown appears when the draft starts with `/`, filters as more
 * characters are typed, and that tapping a row inserts the command into the
 * field.
 */
internal const val COMPOSER_SLASH_DROPDOWN_TAG = "prompt-composer-slash-dropdown"

internal fun composerSlashCommandRowTag(command: String): String =
    "prompt-composer-slash-row:$command"
internal const val COMPOSER_WAVEFORM_TAG = "prompt-composer-waveform"
internal const val COMPOSER_MIC_TAG = "prompt-composer-mic"
internal const val COMPOSER_ATTACH_TAG = "prompt-composer-attach"
internal const val COMPOSER_SNIPPETS_TAG = "prompt-composer-snippets"
// Issue #787: the `/` slash-command button in the left tools pill (📎 / `{}` /
// `/`). The single consolidated slash-command ENTRY point — taps open the same
// #767 autocomplete dropdown (no new picker). Disabled on shell panes (no
// agent → empty catalog).
internal const val COMPOSER_SLASH_TAG = "prompt-composer-slash"
internal const val COMPOSER_ATTACHMENT_PROGRESS_TAG = "prompt-composer-attachment-progress"

/** Issue #453: mockup placeholder text for the empty composer input. */
internal const val COMPOSER_PLACEHOLDER = "Compose prompt…"


/**
 * Issue #508: the two explicit stop actions shown in the Recording row,
 * replacing the old persistent Auto-send toggle.
 *  - [COMPOSER_TO_FIELD_TAG]: stop + transcribe into the editable field
 *    (nothing sent).
 *  - [COMPOSER_STOP_SEND_TAG]: stop + transcribe + send immediately. Also
 *    shown in Transcribing to arm the queued send for the in-flight clip.
 */
internal const val COMPOSER_TO_FIELD_TAG = "prompt-composer-to-field"
internal const val COMPOSER_STOP_SEND_TAG = "prompt-composer-stop-send"

/**
 * Issue #174: test tag for the recording discard control. It only appears in
 * `Recording`, where it stops the mic and drops the buffer before Whisper.
 */
internal const val COMPOSER_CANCEL_RECORDING_TAG = "prompt-composer-cancel-recording"

/**
 * Issue #174/#453: test tag for the transcribing cancel control. Kept
 * distinct from [COMPOSER_CANCEL_RECORDING_TAG] so tests can prove the active
 * recording discard affordance exists without conflating it with the
 * already-captured transcription cancel path.
 */
internal const val COMPOSER_CANCEL_TRANSCRIPTION_TAG = "prompt-composer-cancel-transcription"

// Issue #1619: status banners/tiles scroll independently above the weighted
// editor. 48dp keyboard-up is exactly the two-line Offline banner, whole.
// Keyboard-down can show two status rows before this region scrolls, without
// changing the editor's 220dp cap.
//
// Issue #1622 kept both numbers where they are. They were originally sized
// against the double-subtracted ~175dp budget, but they are not a share of a
// budget — they are the deliberate BOUND on how much of the sheet status chrome
// may ever own (#1613). The room #1622 reclaimed goes to the draft, so growing
// these would spend the fix on the thing the fix was taking room back from.
private val PromptComposerStatusRegionImeMaxHeight = 48.dp
private val PromptComposerStatusRegionMaxHeight = 96.dp

// Issue #2057: upper bound on the staged-attachment strip below the editor.
// Keyboard-DOWN there is room for two full 64dp tile rows plus the 8dp FlowRow
// gap; keyboard-UP the strip is deliberately smaller than one tile (the tile top
// with its remove control stays visible and the strip scrolls). Beyond these
// bounds the strip scrolls internally instead of growing.
//
// Issue #1622 kept the keyboard-up 40dp. It no longer has to contend with the
// editor at all — the flexible area is ~425dp keyboard-up, so 220dp of editor
// plus a 40dp strip both fit — but a taller strip would take the reclaimed room
// straight back off the draft, which is the opposite of this slice.
private val PromptComposerAttachmentRegionImeMaxHeight = 40.dp
private val PromptComposerAttachmentRegionMaxHeight = 144.dp

// Issue #2057: the gap between the editor and the tile strip below it.
private val PromptComposerAttachmentStripGap = 8.dp

// Issue #2057: the editor's guaranteed floor inside the flexible compose area —
// the room the attachment strip may never take. It is the ComposerDraftField box
// at its `minHeight`: the 24dp keyboard-up / 96dp keyboard-down editor minimum
// passed below, plus the draft box's 2 x 14dp vertical padding
// (`DraftFieldBox` in UnifiedComposer.kt). The strip's effective cap is
// `flexibleRoom - floor - gap` clamped to the bound above, so it yields to the
// editor rather than the other way round: with the Offline banner also up
// (the saturated keyboard-up case) the strip shrinks to a scrollable sliver, but
// it can never crush the editor below one complete caret line (#1619). The
// sticky controls row is not in the contest at all — the whole editor + strip
// area is the single weighted child of the sheet body.
// `Issue2057AttachmentTilesBelowDraftProofTest` fails if either value drifts
// away from the real draft-box geometry.
private val PromptComposerDraftImeOneLineFloor = 24.dp + 28.dp
private val PromptComposerDraftOneLineFloor = 96.dp + 28.dp

// Issue #767: cap the `/`-autocomplete dropdown so a long catalog scrolls
// internally instead of squeezing the draft field / pushing the controls under
// the keyboard. ~3.5 rows tall; the list self-scrolls past this.
private val SLASH_DROPDOWN_MAX_HEIGHT = 196.dp

/**
 * Issue #180: test tags for the failed-transcription queue surface.
 * Connected tests assert that the banner appears when the queue is
 * non-empty and that the retry / discard / save affordances dispatch
 * into the ViewModel.
 */
internal const val COMPOSER_PENDING_SAVED_BANNER_TAG = "prompt-composer-pending-saved"
