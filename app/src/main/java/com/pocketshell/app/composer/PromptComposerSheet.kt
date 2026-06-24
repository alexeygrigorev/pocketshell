package com.pocketshell.app.composer

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.WindowInsetsSides
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
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.ContentScale
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
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pocketshell.app.fileviewer.BoundedImageDecoder
import com.pocketshell.app.snippets.SnippetKind
import com.pocketshell.app.snippets.SnippetPickerSheet
import com.pocketshell.app.agentcommands.AgentCommand
import com.pocketshell.app.voice.DictateDotIcon
import com.pocketshell.app.voice.PendingTranscriptionItem
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.storage.entity.PendingTranscriptionEntity
import com.pocketshell.uikit.components.DisclosureIcon
import com.pocketshell.uikit.theme.LocalPocketShellSemantic
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import com.pocketshell.uikit.theme.PocketShellType
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * The Phase 1 prompt composer (issue #15). Visual target:
 * `docs/mockups/composer.html`.
 *
 * Layout, top to bottom inside the [ModalBottomSheet]:
 *
 *  1. Grabber bar (drawn by Material 3's `ModalBottomSheet` itself).
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
    onSend: suspend (PromptComposerViewModel.SendRequest) -> Boolean,
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
) {
    val state by viewModel.uiState.collectAsState()
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
                    mimeType = runCatching { context.contentResolver.getType(uri) }.getOrNull(),
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
    val currentOnSend by rememberUpdatedState(onSend)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val currentSendTargetSnapshotProvider by rememberUpdatedState(sendTargetSnapshotProvider)
    LaunchedEffect(viewModel) {
        viewModel.sendRequests.collect { request ->
            // Issue #745: bound the send so the in-flight state can never hang.
            // The host `onSend` is a connect-on-action call (#548) that may kick
            // a reconnect and await the live client; cap it at [SEND_TIMEOUT_MS]
            // so a truly dead link resolves to the "Not sent" banner promptly
            // instead of leaving the composer stuck on "Sending…". A null
            // (timeout) is treated exactly like a `false` (failed) send.
            val delivered = runCatching {
                withTimeoutOrNull(PromptComposerViewModel.SEND_TIMEOUT_MS) {
                    currentOnSend(request)
                } == true
            }.getOrDefault(false)
            if (delivered) {
                // Issue #745: only NOW clear the draft + staged attachments —
                // the bytes are confirmed delivered, so there is no flicker of
                // an optimistically-emptied field. Then dismiss the sheet so
                // the user lands back on the terminal.
                viewModel.markSendDelivered(request)
                currentOnDismiss()
            } else {
                viewModel.restoreFailedSend(request)
            }
        }
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

    ModalBottomSheet(
        onDismissRequest = dismissComposer,
        sheetState = sheetState,
        containerColor = PocketShellColors.Surface,
        contentColor = PocketShellColors.Text,
        modifier = modifier,
        // Issue #682: the composer is a CONTENT-HEIGHT (wrap-content) sheet that
        // sits directly above the soft keyboard, like a normal chat composer.
        //
        // #615 reworked this into a fully-expanded sheet + a host-window IME
        // inset read + an explicit `padding(bottom = hostImeBottomPx)` on the
        // sheet body. That over-sized the sheet and grew the content height by the
        // keyboard height — pushing the controls up to the top of the screen (the
        // jump-to-top + cut-off) and opening a keyboard-height empty void.
        //
        // Reserve only the TOP + horizontal safe area here. That keeps the status
        // bar / cutout clear at the top while letting the sheet's own dialog
        // window resize for the soft keyboard: when the IME shows, the window
        // (and so `BoxWithConstraints.maxHeight` inside `SheetContent`) shrinks by
        // the keyboard height and the sheet sits directly above the keyboard.
        //
        // Issue #567: `SheetContent` therefore caps the body to that ALREADY
        // IME-resized `maxHeight` and does NOT additionally `imePadding()` or
        // subtract `WindowInsets.ime` — doing either double-counted the keyboard
        // height and crushed the composer into a thin strip. See the long note in
        // `SheetContent`.
        contentWindowInsets = {
            WindowInsets.safeDrawing.only(
                WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
            )
        },
    ) {
        SheetContent(
            state = state,
            onClose = dismissComposer,
            onDraftChange = viewModel::onDraftChange,
            onMicTap = {
                // Three-step gate: permission, then key, then recorder.
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
            onLockRecording = viewModel::lockRecording,
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
                { attachmentLauncher.launch(arrayOf("*/*")) }
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
            outboundQueueExpanded = outboundQueueExpanded,
            onToggleOutboundQueue = { outboundQueueExpanded = !outboundQueueExpanded },
            onDeleteOutboundItem = viewModel::discardOutboundItem,
            onRetryOutboundItem = onRetryOutboundItem ?: viewModel::retryOutboundItem,
            agentKind = agentKind,
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
 * Pure-renderer content for the sheet body. Pulled out of
 * [PromptComposerSheet] so the `@Preview`s can render the layout without
 * Hilt and without an [androidx.activity.ComponentActivity] in scope —
 * `ModalBottomSheet` itself doesn't preview inside `@Preview` (it needs
 * a window decor view), so the previews target this composable directly.
 */
@OptIn(ExperimentalComposeUiApi::class)
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
    onLockRecording: () -> Unit = {},
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
    outboundQueueExpanded: Boolean = false,
    onToggleOutboundQueue: () -> Unit = {},
    onDeleteOutboundItem: (String) -> Unit = {},
    onRetryOutboundItem: (String) -> Unit = {},
    // Issue #767: detected engine for the focused pane — selects the
    // `AgentCommandCatalog` the `/`-autocomplete dropdown filters. Null on a
    // shell pane / preview, where the dropdown is never shown.
    agentKind: AgentKind? = null,
) {
    val isTranscribing = state.recording == PromptComposerViewModel.RecordingState.Transcribing
    val attachmentUploading =
        state.attachmentUpload as? PromptComposerViewModel.AttachmentUploadState.Uploading

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
    //  2. Clear focus + hide the IME SYNCHRONOUSLY, BEFORE dispatching. The
    //     dispatch (`onSend`) runs through the ViewModel -> `sendRequests` ->
    //     host `onDismiss`, which tears this sheet (and its dialog window) out
    //     of composition. If we hid the keyboard in a launched coroutine
    //     (the old #491 behaviour), that coroutine could run AFTER the sheet's
    //     `keyboardController` had already been disposed, so `hide()` no-op'd
    //     and the keyboard stayed/bounced up. Clearing focus first means no
    //     focused editor remains to re-request the IME on the way out; hiding
    //     synchronously means it actually fires while the controller is alive.
    //  3. Dispatch the send last.
    val focusManager = LocalFocusManager.current
    val commitAndSend: () -> Unit = {
        onDraftChange(draftFieldValue.text)
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        onSend(true)
    }
    val lockThresholdPx = with(LocalDensity.current) { MIC_LOCK_SWIPE_THRESHOLD_DP.dp.toPx() }
    val micStartSlopPx = with(LocalDensity.current) { MIC_GESTURE_START_SLOP_DP.dp.toPx() }
    val currentOnMicTap by rememberUpdatedState(onMicTap)
    val currentOnLockRecording by rememberUpdatedState(onLockRecording)
    val micGestureEnabled by rememberUpdatedState(
        state.recording == PromptComposerViewModel.RecordingState.Idle,
    )
    var micBoundsInControlsRow by remember { mutableStateOf<Rect?>(null) }

    // Issue #682 / #567: bound the composer body to the room ABOVE the keyboard.
    //
    // KEY FACT (measured on-device, issue #567): the `ModalBottomSheet`'s dialog
    // window does NOT reposition itself above the soft keyboard, and `imePadding()`
    // does NOT lift content inside this sheet's window. The sheet content stays
    // top-anchored in a window whose bottom sits at the screen bottom (partly
    // behind the keyboard). So the only reliable lever is a height CAP that clips
    // the body to the room left above the keyboard — see the `availableAboveKeyboard`
    // note below. The scrollable upper region then absorbs a long draft / a stack
    // of attachment tiles + banners so the sticky Send row always stays on-screen
    // above the keyboard (the #682 long-content cut-off).
    //
    // History (issue #567 squish): the prior code applied BOTH `heightIn(max =
    // maxHeight - ime)` AND `imePadding()` on the SAME Column. `.heightIn().
    // imePadding()` on one element makes the IME padding eat INTO the cap, so the
    // usable content area collapsed to `maxHeight - 2*ime`, crushing the 96dp
    // draft to ~36dp, clipping the "Prompt Composer" header, and pushing the
    // attachment tiles off-screen. Removing `imePadding()` and capping to exactly
    // the room above the keyboard subtracts the keyboard height once, so the
    // composer renders full-size above the keyboard.
    val density = LocalDensity.current
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // Room above the keyboard, MEASURED on a real pixel_7 inside this
        // `ModalBottomSheet` dialog window (issue #801 diagnostic):
        //
        //   sheet maxHeight       = 470dp     (the sheet content area, IME up)
        //   sheet content TOP     = ~1100px   (where the body is anchored)
        //   keyboard TOP          = 1499px    (decorHeight - ime)
        //   genuine room above kb = maxHeight - (ime - navBars) ≈ 175dp
        //
        // KEY FINDING: the `ModalBottomSheet` content area is only ~470dp tall when
        // the keyboard is up (the sheet window is IME-resized), and its body is
        // top-anchored LOW, so the usable room ABOVE the keyboard is only ~175dp —
        // and the sheet CANNOT be expanded past that (the Expanded anchor is itself
        // the IME-resized maxHeight; `expand()` is a no-op when the keyboard is up).
        // So ~175dp is genuinely all the vertical room there is. The earlier code
        // tried to fit the pinned header + a 96..220dp draft + the control row into
        // that 175dp and crushed — and every patch (#567 → #615 → #682 → #765 →
        // #790 → #784) was a different way of dividing too little room.
        //
        // The `maxHeight - (ime - navBars)` room formula is CORRECT (it lands on the
        // genuine ~175dp). What was wrong was trying to keep ALL the keyboard-DOWN
        // chrome in that tight budget. `WindowInsets.ime` is read here only to find
        // that room + the keyboard-up flag.
        val imeBottomDp = with(density) { WindowInsets.ime.getBottom(this).toDp() }
        val navBarsBottomDp = with(density) {
            WindowInsets.navigationBars.getBottom(this).toDp()
        }
        val keyboardIntrusionDp = (imeBottomDp - navBarsBottomDp).coerceAtLeast(0.dp)
        val keyboardUp = imeBottomDp > 0.dp
        val availableAboveKeyboard = (maxHeight - keyboardIntrusionDp).coerceAtLeast(0.dp)
        // Issue #801 (clean rebuild of the keyboard-up sizing; supersedes the
        // #790/#765 reserve-constant approach, D22 hard-cut):
        //
        // Given that only ~175dp is available above the keyboard, the layout is a
        // proper chat-composer: a WEIGHTED scrollable draft that takes exactly the
        // room left after the fixed control row, and — keyboard-up only — the
        // "Prompt Composer" header is DROPPED (it is not needed while actively
        // typing; the close affordance is swipe-down / back / scrim, and the header
        // returns the moment the keyboard hides). Dropping the ~58dp header reclaims
        // it for the draft, so in ~175dp the draft gets ~3 readable lines and the
        // full-size control row sits below it, above the keyboard — instead of the
        // old reserve-and-floor that crushed the field to one line and crammed the
        // controls into a sliver. The `weight(1f)` region replaces the brittle
        // hand-estimated `PROMPT_SCROLL_REGION_IME_CHROME_RESERVE`/floor pair (both
        // deleted): no constant to drift, the control row always lays out, and a
        // long draft scrolls WITHIN the weighted region (the #682 long-draft
        // invariant) rather than pushing the controls off-screen.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // The nav-bar clearance only matters when the keyboard is DOWN
                // (the keyboard covers the nav bar when up); it contributes 0
                // under the IME.
                .navigationBarsPadding()
                // Cap to the room above the keyboard (see note above). The sheet
                // content is top-anchored within its window and the window extends
                // behind the keyboard, so clipping the body to this room is what
                // keeps the sticky Send row above the keyboard. The scrollable
                // upper region absorbs a long draft / a stack of attachment tiles +
                // banners (the #682 long-content cut-off).
                .heightIn(max = availableAboveKeyboard)
                .background(PocketShellColors.Surface)
                .padding(horizontal = 18.dp)
                .padding(bottom = 26.dp),
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
            // Issue #801: keyboard-UP the header is HIDDEN. On a real Pixel only
            // ~175dp is available above the keyboard, and the header ("Prompt
            // Composer" title + close ×) costs ~58dp of it — the difference between
            // a one-line crushed draft and a usable ~3-line one. The header is not
            // needed while actively typing: the user can dismiss via swipe-down /
            // system back / scrim tap, and the header returns the instant the
            // keyboard hides. So it renders only when the keyboard is DOWN.
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
                    // Issue #682/#765: the scroll region (draft + status banners +
                    // attachment tiles) absorbs overflow so a long draft / a stack
                    // of tiles scrolls WITHIN the compact sheet instead of forcing
                    // the whole content taller (which would push the sticky action
                    // row off-screen / under the keyboard). The header is PINNED
                    // above this region (#765) so it never scrolls away.
                    //
                    // Issue #801: this region is WEIGHTED in both keyboard states so
                    // it takes exactly the space left after the pinned header, the
                    // connection-lost row, and the sticky control row are laid out —
                    // never a hand-tuned reserve constant (the #790 cap) that could
                    // undercut the draft's own 96dp min and crush it (the recurring
                    // squish). `fill = false` keeps the column wrap-content for a
                    // short/empty draft so the whole sheet sits compactly above the
                    // keyboard with no reserved dead band (the #790 symptom). As the
                    // draft lengthens the region grows up to its weighted share of
                    // the room above the keyboard, then the `verticalScroll` below
                    // absorbs the overflow so the sticky Send / attach row never
                    // leaves the area above the keyboard (the #682 long-draft
                    // invariant). When the keyboard is DOWN the extra
                    // `heightIn(max = …)` keeps the sheet compact at partial-expand
                    // (#234); when it is UP the outer
                    // `heightIn(max = availableAboveKeyboard)` is the only ceiling,
                    // so the field is bounded to the genuine room above the keyboard.
                    //
                    // Issue #873: `fill = false` keeps the column wrap-content, but
                    // it only ACTUALLY wraps once the draft field stops greedily
                    // filling its weighted share. That is handled in
                    // [ComposerDraftField]: keyboard-up the field's `heightIn(min,
                    // max)` is on the EDITOR (so a one-line draft wraps to ~one line
                    // and self-scrolls past `max` on a long draft) rather than a
                    // `fillMaxHeight` box that inflated a short draft to ~155dp — the
                    // ~1cm dead band the maintainer circled.
                    .weight(1f, fill = false)
                    .then(
                        if (keyboardUp) {
                            Modifier
                        } else {
                            Modifier.heightIn(max = PromptComposerScrollRegionMaxHeight)
                        },
                    )
                    .verticalScroll(rememberScrollState()),
            ) {
            // Issue #453: the composer's primary surface is state-driven.
            //  - Idle / Text-inserted: the editable input (`Compose prompt…`).
            //  - Recording: an amplitude-driven waveform + mm:ss timer + Stop.
            //  - Transcribing: a "Transcribing…" spinner row.
            // The mockup collapses these into one panel whose height adjusts to
            // the content, so we swap the surface in place rather than stacking
            // extra rows.
            when (state.recording) {
                PromptComposerViewModel.RecordingState.Recording -> {
                    RecordingSurface(
                        amplitude = state.amplitude,
                        capturing = state.hasDetectedSpeech,
                        elapsedLabel = formatElapsed(state.recordingElapsedMs),
                        // Issue #870 (reopen): pass the RAW growing partial; the
                        // dedicated two-line LiveTranscriptTwoLine area resolves
                        // the visible tail width-aware at render time so the
                        // newest words always stay visible.
                        liveTranscript = state.liveTranscript,
                        locked = state.recordingLocked,
                        onCancel = onCancelRecording,
                    )
                }

                PromptComposerViewModel.RecordingState.Transcribing -> {
                    TranscribingSurface()
                }

                PromptComposerViewModel.RecordingState.Idle -> {
                    // The composer text area. Issue #196: shared with the
                    // agent-pane composer via [ComposerDraftField] so both
                    // surfaces have an identical surface-elev fill, accent
                    // cursor, and muted placeholder. Issue #453: placeholder is
                    // the mockup's "Compose prompt…". After a dictation lands
                    // (Auto-send off) the transcript fills this same editable
                    // field for the user to review before Send.
                    ComposerDraftField(
                        value = draftFieldValue,
                        onValueChange = { newValue ->
                            // Mirror every edit into the ViewModel so the draft
                            // (and SavedStateHandle) stay in lockstep with the
                            // editor, then keep our local TextFieldValue as the
                            // selection-bearing source of truth.
                            draftFieldValue = newValue
                            if (newValue.text != state.draft) {
                                onDraftChange(newValue.text)
                            }
                        },
                        placeholder = COMPOSER_PLACEHOLDER,
                        fieldTag = COMPOSER_DRAFT_TAG,
                        // Issue #873: keyboard-UP the field WRAPS to its text content
                        // (the `heightIn(min, max)` bound lives on the EDITOR in
                        // [ComposerDraftField], not on a `fillMaxHeight` box), so a
                        // SHORT draft is as compact as its content — one line of text
                        // sits in a ~one-line-tall field, NOT centred in a tall box
                        // with ~1cm of empty space below it (the dead band the
                        // maintainer circled). `minHeight = 44.dp` keyboard-up is a
                        // single comfortable line so the field never reserves a
                        // multi-line void for a one-line draft; as the user types it
                        // grows line-by-line up to `maxHeight = 220.dp`, then the
                        // `BasicTextField` self-scrolls to the caret within that cap
                        // (the #765 long-draft invariant). Keyboard-DOWN it keeps the
                        // roomy 96dp min and fills its box (unchanged).
                        //
                        // `minHeight = 24.dp` keyboard-up is a single text line; with
                        // the box's 14dp top + 14dp bottom padding the empty/one-line
                        // field is ~one line tall — as compact as its content, with
                        // no reserved multi-line void. The box stays a comfortable
                        // tap target via that padding.
                        minHeight = if (keyboardUp) 24.dp else 96.dp,
                        maxHeight = 220.dp,
                        focusRequester = draftFocusRequester,
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

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

            attachmentUploading?.let { upload ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
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
                        .testTag(COMPOSER_ATTACHMENT_PROGRESS_TAG),
                ) {
                    Text(
                        text = "Uploading ${upload.count} attachment${if (upload.count == 1) "" else "s"}...",
                        color = PocketShellColors.Text,
                        fontSize = 12.sp,
                    )
                }
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

            // Issue #180: pending-transcription banner + expandable list.
            // Renders only when at least one row is queued; sits above the
            // mic row so the user notices it before re-recording.
            if (pendingItems.isNotEmpty()) {
                PendingTranscriptionsBanner(
                    items = pendingItems,
                    retryingIds = state.retryingIds,
                    expanded = pendingListExpanded,
                    onToggle = onTogglePendingList,
                    onRetry = onRetryPending,
                    onDiscard = onDiscardPending,
                    onSaveAsAudio = onSavePendingAsAudio,
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Issue #900: queued outbound prompts must be visible before send
            // is wired to enqueue. This read/delete surface is intentionally
            // foreground-only and scoped to the current composer target.
            if (outboundQueueItems.isNotEmpty()) {
                OutboundQueueBanner(
                    items = outboundQueueItems,
                    expanded = outboundQueueExpanded,
                    onToggle = onToggleOutboundQueue,
                    onDelete = onDeleteOutboundItem,
                    onRetry = onRetryOutboundItem,
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Issue #566: staged attachments render as compact square tiles at
            // the bottom of the composer (above the controls row), ChatGPT /
            // Claude style. The draft text stays clean while composing; remote
            // paths are folded into the outgoing prompt only at SEND.
            if (state.attachments.isNotEmpty()) {
                AttachmentTileGrid(
                    attachments = state.attachments,
                    onRemove = onRemoveAttachment,
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        // Issue #745: connection-lost indicator. Sits OUTSIDE the scrollable
        // upper region so it is part of the sticky bottom chrome and stays
        // visible directly above the Send row even when the soft keyboard is
        // up. Surfaced the moment the host reports the SSH/tmux link is
        // degraded/lost, BEFORE the user taps Send, so a send into a dead link
        // is never a silent blind wait.
        if (state.connectionDegraded) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(PocketShellColors.AccentSoft, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag(COMPOSER_CONNECTION_LOST_TAG),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.CloudOff,
                    contentDescription = null,
                    tint = PocketShellColors.Accent,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = "Connection lost — Send will retry once reconnected.",
                    color = PocketShellColors.Accent,
                    fontSize = 12.sp,
                )
            }
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .micSwipeUpLockGesture(
                    lockThresholdPx = lockThresholdPx,
                    startSlopPx = micStartSlopPx,
                    enabled = { micGestureEnabled },
                    startBounds = { micBoundsInControlsRow },
                    onPressStart = { currentOnMicTap() },
                    onLockRecording = { currentOnLockRecording() },
                )
                .padding(top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Issue #701: the left tools — 📎 attach + `{}` snippets — sit in a
            // single rounded `SurfaceElev` group rather than as two bare ghost
            // icons floating at the far edge. The earlier layout left the row
            // looking unbalanced: two lonely icons hard-left, a weak Send
            // hard-right, and a wide dead gap between them (the maintainer's
            // "big empty gap" complaint). Grouping the secondary tools into one
            // pill gives the row a deliberate left anchor that visually balances
            // the filled Send + mic cluster on the right; the `weight(1f)` space
            // between them now reads as intentional breathing room. Attachment
            // picking stays single-flight while a batch is uploading, but
            // typing, editing, dictation, and text-only Send remain live.
            Row(
                modifier = Modifier
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
                // Issue #787: the single consolidated `/` slash-command entry.
                // Disabled on shell panes (`agentKind == null` → empty catalog,
                // nothing to show), matching the prior key-bar / chip gating.
                SlashIconButton(
                    onClick = onSlashTap,
                    enabled = !isTranscribing && !attachmentBusy && agentKind != null,
                    modifier = Modifier.testTag(COMPOSER_SLASH_TAG),
                )
            }
            // Issue #453: the separate keyboard icon is removed from the Idle
            // row — it is not in the mockup and cluttered the clean idle. The
            // editable draft field itself raises the soft IME the moment it is
            // tapped/focused (ComposerDraftField), so there is a single,
            // obvious way to bring up the keyboard: tap the input. The
            // [draftFocusRequester] is still used to focus the field after a
            // dictation transcript lands.

            Spacer(modifier = Modifier.weight(1f))

            // Right cluster is state-driven.
            when (state.recording) {
                PromptComposerViewModel.RecordingState.Idle -> {
                    // Issue #453: match the mockup's compact toolbar order —
                    // `Send` (text + arrow) FIRST, then a small mic disc at the
                    // far right, both sitting tight together. The old order
                    // (mic-then-Send) was reversed versus the mockup.
                    //
                    // Issue #491: gate on the LIVE editor text, not the
                    // (possibly stale) ViewModel draft — the IME composing
                    // region lands in `draftFieldValue.text` immediately, so
                    // a short typed prompt enables Send without waiting for an
                    // IME commit. Staged attachment chips also enable Send
                    // for attachment-only prompts. [commitAndSend] flushes
                    // the live text into the ViewModel before dispatching, so
                    // the tap always delivers.
                    val sendEnabled =
                        (draftFieldValue.text.isNotEmpty() || state.attachments.isNotEmpty()) &&
                            // Issue #745: while a send is in flight the button is
                            // disabled and shows "Sending…" — a second tap can't
                            // queue another request on top of the one resolving.
                            !state.sendInFlight
                    SendButton(
                        onClick = commitAndSend,
                        enabled = sendEnabled,
                        sending = state.sendInFlight,
                        modifier = Modifier.testTag(COMPOSER_SEND_ENTER_TAG),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    // Small cyan mic disc at the far right (the mockup's mic).
                    // Sits AFTER Send so the row reads "type + send, or dictate".
                    MicTriggerButton(
                        onClick = onMicTap,
                        enabled = true,
                        modifier = Modifier
                            .testTag(COMPOSER_MIC_TAG)
                            .onGloballyPositioned { coordinates ->
                                micBoundsInControlsRow = coordinates.boundsInParent()
                            },
                    )
                }

                PromptComposerViewModel.RecordingState.Recording -> {
                    // Issue #508: two explicit stop actions replace the old
                    // persistent Auto-send toggle. The choice is made
                    // per-recording, at the moment of stopping:
                    //  - "Insert": stop + transcribe, drop the text into the
                    //    editable composer field (nothing sent). The user can
                    //    then attach a screenshot / edit before sending. This
                    //    is the historic [onMicTap] stop path — the transcript
                    //    appends to the draft and the FSM lands back in Idle.
                    //  - "Send": stop + transcribe + send immediately. Routes
                    //    through [onSend(true)] which (while Recording) queues
                    //    the send and stops the recorder; the queued send fires
                    //    once Whisper returns with the combined transcript.
                    // The non-transcribing discard action is intentionally in
                    // the recording panel itself, not this row, so it cannot be
                    // confused with either stop+transcribe choice.
                    ToFieldButton(
                        onClick = onMicTap,
                        modifier = Modifier.testTag(COMPOSER_TO_FIELD_TAG),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    StopSendButton(
                        onClick = { onSend(true) },
                        modifier = Modifier.testTag(COMPOSER_STOP_SEND_TAG),
                    )
                }

                PromptComposerViewModel.RecordingState.Transcribing -> {
                    // Issue #508: the audio is already captured and the Whisper
                    // round-trip is in flight, but the user can still pick where
                    // the transcript lands once it returns. "Insert" is a
                    // no-op on the in-flight request — the transcript appends to
                    // the draft by default — so the only extra action surfaced
                    // here is "Send" (arms the queued send) plus Cancel (aborts
                    // the round-trip). No persistent Auto-send toggle.
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
 * Issue #453 / #508: the Recording-state surface that replaces the editable
 * input — an amplitude-driven [Waveform] flanked by the elapsed mm:ss timer.
 * The animated waveform alone (plus the live ticking timer) conveys "we are
 * capturing"; there is no redundant "CAPTURING" text. The single in-surface
 * "Stop" label was removed in #508 — stopping is now done via the two
 * explicit bottom-row actions ("Insert" / "Send"), so a lone "Stop" here
 * would be ambiguous about where the transcript lands.
 */
@Composable
private fun RecordingSurface(
    amplitude: Float,
    capturing: Boolean,
    elapsedLabel: String,
    liveTranscript: String?,
    locked: Boolean,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            // Issue #870 (reopen): a MIN height (not a fixed one) so the dedicated
            // two-line live-transcript area is never vertically clipped — on a
            // large-font device the font-scaled two-line box grows the panel
            // instead of being cut off (the recording panel grows downward; the
            // sticky action row stays put).
            .heightIn(min = if (liveTranscript.isNullOrBlank()) 68.dp else 112.dp)
            .background(
                color = PocketShellColors.SurfaceElev,
                shape = RoundedCornerShape(12.dp),
            )
            .border(
                width = 1.dp,
                color = PocketShellColors.Border,
                shape = RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = elapsedLabel,
                color = PocketShellColors.Accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.testTag(COMPOSER_TIMER_TAG),
            )
            if (locked) {
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = PocketShellColors.Accent,
                    modifier = Modifier
                        .size(18.dp)
                        .testTag(COMPOSER_RECORDING_LOCKED_TAG)
                        .semantics { contentDescription = "Recording locked" },
                )
            }
            Waveform(
                amplitude = amplitude,
                active = capturing,
                modifier = Modifier
                    .weight(1f)
                    .height(32.dp)
                    .testTag(COMPOSER_WAVEFORM_TAG)
                    .semantics {
                        contentDescription = if (capturing) {
                            "Prompt composer capturing speech"
                        } else {
                            "Prompt composer waiting for speech"
                        }
                    },
            )
            DiscardRecordingButton(onClick = onCancel)
        }
        if (!liveTranscript.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            LiveTranscriptTwoLine(
                text = liveTranscript,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(COMPOSER_LIVE_TRANSCRIPT_TAG),
            )
        }
    }
}

/**
 * Issue #870 (reopen): the live partial-transcript area for the Android
 * recognizer. The maintainer's design direction is a DEDICATED TWO-LINE area
 * whose second line always holds the live, in-progress recognition — so the
 * newest words are always visible and the cut point is well-defined, instead of
 * clipping a single line.
 *
 * The previous fix anchored the tail with a width-INDEPENDENT character budget
 * ([liveTranscriptTail], 90 chars) and a trailing `TextOverflow.Ellipsis`. On a
 * real device the 90-char tail did not fit two lines at the panel width / font
 * scale, so the trailing ellipsis re-clipped the END — the newest words
 * scrolled out of view again (the exact reopen symptom). A character budget
 * cannot know the panel width, so it cannot guarantee the tail fits.
 *
 * This is width-AWARE: it measures the text with a [TextMeasurer] at the actual
 * available width + font scale, and if the full text needs more than
 * [LIVE_TRANSCRIPT_MAX_LINES] lines it drops leading characters (keeping the
 * TAIL), snaps to a word boundary, and prepends a leading `…`, iterating until
 * the kept tail fits the two-line box. The box is a fixed two-line height and
 * the text is bottom-anchored, so the most recent words always occupy the
 * visible lines.
 *
 * [onResolved] reports the text actually laid out (the trimmed tail, or the raw
 * text when it already fits) so a test can assert the on-screen visible content
 * keeps the newest words — not merely that the full string is present in the
 * semantics tree behind a trailing ellipsis.
 */
@Composable
private fun LiveTranscriptTwoLine(
    text: String,
    modifier: Modifier = Modifier,
    onResolved: (String) -> Unit = {},
) {
    val measurer = rememberTextMeasurer()
    val style = PocketShellType.bodyDense
    val density = LocalDensity.current
    val boxHeight = with(density) {
        (style.fontSize.toPx() * LIVE_TRANSCRIPT_LINE_HEIGHT_FACTOR * LIVE_TRANSCRIPT_MAX_LINES).toDp()
    }

    Box(
        modifier = modifier.height(boxHeight),
        contentAlignment = Alignment.BottomStart,
    ) {
        BoxWithConstraints {
            val widthPx = constraints.maxWidth
            // Recompute the visible tail whenever the text or the available
            // width / font scale changes — width-aware, so a tail that fits on a
            // wide panel but not a narrow one is trimmed correctly.
            val resolved = remember(text, widthPx, density.density, density.fontScale) {
                resolveLiveTranscriptTail(
                    text = text,
                    measurer = measurer,
                    style = style,
                    maxWidthPx = widthPx,
                    maxLines = LIVE_TRANSCRIPT_MAX_LINES,
                )
            }
            LaunchedEffect(resolved) { onResolved(resolved) }
            Text(
                text = resolved,
                color = PocketShellColors.Text,
                style = style,
                // The tail is pre-trimmed to fit MAX_LINES at this width, so the
                // newest words always render. maxLines is a belt-and-braces cap;
                // the leading ellipsis (not a trailing one) marks the dropped
                // head, so any residual overflow drops the OLDEST words, never
                // the newest.
                maxLines = LIVE_TRANSCRIPT_MAX_LINES,
                overflow = TextOverflow.Clip,
                modifier = Modifier.testTag(COMPOSER_LIVE_TRANSCRIPT_TEXT_TAG),
            )
        }
    }
}

/**
 * Issue #453: the Transcribing-state surface that replaces the editable
 * input — a "Transcribing…" label + spinner. The Cancel affordance lives in
 * the bottom controls row (so it stays inside the thumb arc, matching the
 * mockup), and cancels the in-flight transcription, restoring the composer.
 */
@Composable
private fun TranscribingSurface(
    modifier: Modifier = Modifier,
) {
    // Issue #453: center the spinner + label inside the panel. The old
    // left-aligned 20dp dot rendered as a tiny smudge at the left edge; the
    // mockup centers a clear spinner. The Row wraps its content and is then
    // centered horizontally via the parent Box.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(
                color = PocketShellColors.SurfaceElev,
                shape = RoundedCornerShape(12.dp),
            )
            .border(
                width = 1.dp,
                color = PocketShellColors.Border,
                shape = RoundedCornerShape(12.dp),
            )
            .testTag(COMPOSER_TRANSCRIBING_SPINNER_TAG)
            .semantics { contentDescription = "Prompt composer transcribing" },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Issue #453: a clearly visible spinner next to the centered label.
            // The M3 *indeterminate* indicator draws a sweep whose arc length is
            // animation-phase-dependent — in a captured still it frequently lands
            // on a near-zero sweep that reads as the "tiny dot" the reviewer
            // flagged. We instead draw a DETERMINATE 270° arc (always a thick,
            // clearly-visible three-quarter ring) and rotate the whole indicator
            // continuously so it still reads as an active spinner in the live app
            // while being deterministic in any single frame.
            val spinTransition = rememberInfiniteTransition(label = "transcribe-spin")
            val angle by spinTransition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 900, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "transcribe-spin-angle",
            )
            CircularProgressIndicator(
                progress = { 0.75f },
                modifier = Modifier
                    .size(28.dp)
                    .graphicsLayer { rotationZ = angle },
                strokeWidth = 3.5.dp,
                color = PocketShellColors.Accent,
                trackColor = Color.Transparent,
            )
            Text(
                text = "Transcribing…",
                color = PocketShellColors.Text,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/**
 * Issue #174: explicit recording-only discard control. It lives inside the
 * active recording panel, next to the waveform, so it reads as "discard this
 * captured audio" rather than the header close `×` that dismisses the whole
 * composer. Tapping it stops the mic/recognizer and drops the live buffer.
 */
@Composable
private fun DiscardRecordingButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Discard recording without transcribing" }
            .testTag(COMPOSER_CANCEL_RECORDING_TAG)
            .padding(horizontal = 8.dp),
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
            text = if (sending) "Sending…" else "Send",
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
            .height(44.dp)
            .background(
                color = PocketShellColors.SurfaceElev,
                shape = RoundedCornerShape(22.dp),
            )
            .border(
                width = 1.dp,
                color = PocketShellColors.Border,
                shape = RoundedCornerShape(22.dp),
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
            .height(44.dp)
            .background(
                color = PocketShellColors.Accent,
                shape = RoundedCornerShape(22.dp),
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
            .semantics {
                contentDescription = "Start dictation. Swipe up to lock recording"
                role = Role.Button
                if (enabled) {
                    onClick {
                        onClick()
                        true
                    }
                }
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

private fun Modifier.micSwipeUpLockGesture(
    lockThresholdPx: Float,
    startSlopPx: Float,
    enabled: () -> Boolean,
    startBounds: () -> Rect?,
    onPressStart: () -> Unit,
    onLockRecording: () -> Unit,
): Modifier {
    return pointerInput(lockThresholdPx, startSlopPx) {
        awaitEachGesture {
            // requireUnconsumed = false so we still see the down even when a
            // parent (the ModalBottomSheet drag-to-dismiss nested scroll) has
            // a claim on vertical motion; we then aggressively own the pointer
            // so a fast hold-and-pull-up cannot be reinterpreted as a sheet
            // drag before recording starts (#585).
            val down = awaitFirstDown(requireUnconsumed = false)
            val bounds = startBounds()
            // Issue #585: the mic disc is a small 44dp target at the far
            // bottom-right; a real hold-and-pull-up frequently lands the
            // contact point a few px outside the tight rect. Inflating the
            // start bounds by a touch-slop margin means an intentional press
            // on/near the mic still arms the gesture instead of silently
            // doing nothing (the maintainer's "it doesn't start recording"
            // symptom).
            if (!enabled() || !micGestureStartsAt(bounds, down.position, startSlopPx)) {
                return@awaitEachGesture
            }
            val tracker = MicSwipeUpLockGestureTracker(lockThresholdPx)
            if (tracker.onPressStart() == MicSwipeUpLockGestureEvent.StartRecording) {
                onPressStart()
            }
            // Consume the down so the bottom sheet's nested-scroll / drag
            // handle does not also treat this press as the start of a
            // drag-to-dismiss.
            down.consume()
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                val drag = change.position - down.position
                // Consume every move belonging to our pointer so the upward
                // pull stays with the mic gesture (and the sheet cannot win
                // the vertical drag). Without this, a quick swipe-up reads as
                // a sheet drag on a real device and the lock never fires.
                if (change.positionChanged()) {
                    change.consume()
                }
                if (tracker.onDrag(drag.x, drag.y) == MicSwipeUpLockGestureEvent.LockRecording) {
                    onLockRecording()
                }
                if (change.changedToUpIgnoreConsumed()) {
                    tracker.onRelease()
                    break
                }
            }
        }
    }
}

internal class MicSwipeUpLockGestureTracker(
    private val lockThresholdPx: Float,
) {
    var started: Boolean = false
        private set

    var locked: Boolean = false
        private set

    fun onPressStart(): MicSwipeUpLockGestureEvent {
        if (started) return MicSwipeUpLockGestureEvent.None
        started = true
        return MicSwipeUpLockGestureEvent.StartRecording
    }

    fun onDrag(dragX: Float, dragY: Float): MicSwipeUpLockGestureEvent {
        if (!started || locked) return MicSwipeUpLockGestureEvent.None
        if (!micSwipeCrossedLockThreshold(dragX, dragY, lockThresholdPx)) {
            return MicSwipeUpLockGestureEvent.None
        }
        locked = true
        return MicSwipeUpLockGestureEvent.LockRecording
    }

    fun onRelease(): MicSwipeUpLockGestureEvent = MicSwipeUpLockGestureEvent.None
}

internal enum class MicSwipeUpLockGestureEvent {
    None,
    StartRecording,
    LockRecording,
}

internal fun micSwipeCrossedLockThreshold(
    dragX: Float,
    dragY: Float,
    lockThresholdPx: Float,
): Boolean = dragY <= -lockThresholdPx && abs(dragY) >= abs(dragX)

/**
 * Issue #585: decide whether a pointer-down at [position] should arm the mic
 * swipe-up gesture. [bounds] is the tight mic-disc rect (relative to the
 * controls row); we accept a press within [startSlopPx] of that rect so a
 * slightly-off hold-and-pull-up on the small disc still starts recording
 * instead of silently doing nothing. Returns false when bounds are not yet
 * measured (mic not laid out / not in the Idle row).
 */
internal fun micGestureStartsAt(
    bounds: Rect?,
    position: Offset,
    startSlopPx: Float,
): Boolean {
    val rect = bounds ?: return false
    return rect.inflate(startSlopPx).contains(position)
}

/**
 * Issue #566: compact ChatGPT/Claude-style staged attachment tiles. Each
 * attachment is a fixed square: image selections get a thumbnail when the
 * local preview Uri is still readable; all other files get a typed file tile.
 * The full file name stays in accessibility text while the visible label is
 * constrained to the tile width, so long names cannot stretch the composer.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun AttachmentTileGrid(
    attachments: List<PromptComposerViewModel.StagedAttachment>,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .testTag(COMPOSER_ATTACHMENT_CHIPS_TAG),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        attachments.forEach { attachment ->
            AttachmentTile(
                attachment = attachment,
                onRemove = onRemove,
            )
        }
    }
}

@Composable
private fun AttachmentTile(
    attachment: PromptComposerViewModel.StagedAttachment,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    val isImage = attachment.isImageAttachment()
    val context = LocalContext.current
    val thumbnail by produceState<ImageBitmap?>(
        initialValue = null,
        key1 = attachment.previewUri,
        key2 = attachment.mimeType,
    ) {
        val uri = attachment.previewUri
        value = if (isImage && uri != null) {
            withContext(Dispatchers.IO) {
                decodeAttachmentThumbnail(context.contentResolver, uri)
            }
        } else {
            null
        }
    }

    Box(
        modifier = modifier
            .size(ATTACHMENT_TILE_SIZE)
            .clip(shape)
            .background(
                color = PocketShellColors.SurfaceElev,
                shape = shape,
            )
            .border(
                width = 1.dp,
                color = PocketShellColors.BorderSoft,
                shape = shape,
            )
            .semantics {
                contentDescription = "Attachment ${attachment.displayName}"
            }
            .testTag(composerAttachmentChipTestTag(attachment.remotePath)),
    ) {
        if (thumbnail != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(composerAttachmentThumbnailTestTag(attachment.remotePath)),
            ) {
                Image(
                    bitmap = thumbnail!!,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
            AttachmentTypeTile(
                attachment = attachment,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(composerAttachmentTypeTileTestTag(attachment.remotePath)),
            )
        }

        AttachmentTileLabel(
            label = attachment.displayName,
            imageBacked = thumbnail != null,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(),
        )

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(ATTACHMENT_TILE_REMOVE_TOUCH_SIZE)
                .clickable(role = Role.Button) { onRemove(attachment.remotePath) }
                .semantics { contentDescription = "Remove ${attachment.displayName}" }
                .testTag(composerAttachmentRemoveTestTag(attachment.remotePath)),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
                    .size(ATTACHMENT_TILE_REMOVE_SIZE)
                    .background(
                        color = PocketShellColors.Surface,
                        shape = RoundedCornerShape(11.dp),
                    )
                    .border(
                        width = 1.dp,
                        color = PocketShellColors.BorderSoft,
                        shape = RoundedCornerShape(11.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "×",
                    color = PocketShellColors.Text,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun AttachmentTypeTile(
    attachment: PromptComposerViewModel.StagedAttachment,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.padding(start = 6.dp, end = 6.dp, top = 8.dp, bottom = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = attachment.fileExtensionLabel(),
            color = PocketShellColors.Accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AttachmentTileLabel(
    label: String,
    imageBacked: Boolean,
    modifier: Modifier = Modifier,
) {
    val background = if (imageBacked) {
        PocketShellColors.TermBg.copy(alpha = 0.72f)
    } else {
        Color.Transparent
    }
    Box(
        modifier = modifier
            .background(background)
            .padding(start = 5.dp, end = 5.dp, bottom = 4.dp, top = 2.dp),
    ) {
        Text(
            text = label,
            color = if (imageBacked) PocketShellColors.TermText else PocketShellColors.TextSecondary,
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun decodeAttachmentThumbnail(
    resolver: android.content.ContentResolver,
    uri: Uri,
): ImageBitmap? {
    return runCatching {
        BoundedImageDecoder.decodeStream(
            openInputStream = {
                if (uri.scheme == "file") {
                    uri.path?.let { java.io.File(it).inputStream() }
                } else {
                    resolver.openInputStream(uri)
                }
            },
            maxPixels = ATTACHMENT_THUMBNAIL_MAX_PIXELS,
        )?.asImageBitmap()
    }.getOrNull()
}

private fun PromptComposerViewModel.StagedAttachment.isImageAttachment(): Boolean {
    if (mimeType?.startsWith("image/") == true) return true
    return displayName.substringAfterLast('.', missingDelimiterValue = "")
        .lowercase(Locale.ROOT) in IMAGE_ATTACHMENT_EXTENSIONS
}

private fun PromptComposerViewModel.StagedAttachment.fileExtensionLabel(): String {
    val extension = displayName.substringAfterLast('.', missingDelimiterValue = "")
        .takeIf { it.isNotBlank() }
        ?.uppercase(Locale.ROOT)
        ?.take(5)
    return extension ?: "FILE"
}

private val IMAGE_ATTACHMENT_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif")
private const val ATTACHMENT_THUMBNAIL_MAX_PIXELS = 192 * 192
internal val ATTACHMENT_TILE_SIZE = 64.dp
internal val ATTACHMENT_TILE_REMOVE_TOUCH_SIZE = 48.dp
private val ATTACHMENT_TILE_REMOVE_SIZE = 22.dp
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

/**
 * Issue #453: amplitude-driven waveform shown in the Recording state. The
 * animated strip alone conveys "we are capturing" — there is no redundant
 * status text any more (the maintainer's declutter request).
 *
 * Two visual modes:
 *  - **Active (capturing)** — [active] true: bars animate from the live
 *    amplitude, multiplied by the per-bar envelope and a per-bar phase
 *    offset so the bars ripple outward from the centre instead of moving
 *    as a flat block.
 *  - **Pre-speech (waiting)** — [active] false: the bars subtly pulse
 *    between 4dp and 6dp on a 750ms loop so the strip reads as "alive and
 *    waiting" rather than dormant (the static-indicator bug the maintainer
 *    reported on v0.3.19).
 *
 * When transcription starts the whole recording surface is replaced by the
 * "Transcribing…" spinner (see [TranscribingSurface]) — that is the
 * freeze/settle the #461 §5 motion guidance calls for; the waveform is not
 * rendered at all during transcription.
 */
@Composable
private fun Waveform(
    amplitude: Float,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    // Smooth amplitude transitions so a sudden spike doesn't jerk the
    // bars. 80ms is faster than the human eye's flicker fusion threshold
    // but slow enough to look organic.
    val smoothed by animateFloatAsState(
        targetValue = if (active) amplitude else 0f,
        animationSpec = tween(durationMillis = 80, easing = LinearEasing),
        label = "waveform-smooth",
    )

    // Continuously-running per-bar phase sweep that produces a flowing
    // wave across the 30 bars. Even at low amplitude the wave motion makes
    // the strip read as "alive and capturing" rather than a static block
    // of uniform bars — the maintainer's original complaint. The phase
    // completes one full cycle every ~1.4s (1400ms / 1000 ticks), which
    // is slow enough to feel organic but fast enough to be obviously
    // animated at a glance.
    val waveTransition = rememberInfiniteTransition(label = "waveform-wave-phase")
    val wavePhase by waveTransition.animateFloat(
        initialValue = 0f,
        targetValue = WAVEFORM_BARS.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = WAVEFORM_WAVE_PERIOD_MS,
                easing = LinearEasing,
            ),
            repeatMode = RepeatMode.Restart,
        ),
        label = "waveform-wave-phase-value",
    )

    // Pre-speech pulse: the bars rest at 4dp, which read as dormant. A
    // subtle 2dp pulse on a 750ms loop signals "the mic is open, speak"
    // without competing with the live-amplitude animation.
    val idlePulse: Float = if (!active) {
        val transition = rememberInfiniteTransition(label = "waveform-idle-pulse")
        val v by transition.animateFloat(
            initialValue = 0f,
            targetValue = 2f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 750, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "waveform-idle-pulse-value",
        )
        v
    } else {
        0f
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            for (i in 0 until WAVEFORM_BARS) {
                // Mockup-style envelope: a wide hump centred at index 15
                // with two smaller side lobes. Multiplied by the live
                // amplitude so a quiet user sees a flat strip and a loud
                // one sees full-height bars.
                val envelope = barEnvelopeHeightDp(i)
                // Per-bar phase offset: sine wave propagating outward
                // from the centre. The offset is added to the smoothed
                // amplitude so bars ripple rather than move in lockstep.
                val phaseOffset = waveformPhaseOffset(i, wavePhase)
                val h = when {
                    active -> (4f + (smoothed + phaseOffset).coerceIn(0f, 1f) * envelope)
                        .coerceIn(4f, envelope)
                    // Pre-speech: pulse 4..6dp so the strip reads as
                    // "alive and waiting".
                    else -> 4f + idlePulse
                }
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(h.dp)
                        .background(
                            color = PocketShellColors.Accent.copy(alpha = 0.85f),
                            shape = RoundedCornerShape(2.dp),
                        ),
                )
            }
        }
    }
}

/**
 * Per-bar phase offset for the waveform wave animation.
 *
 * Returns a sinusoidal offset in [-0.18, +0.18] for bar [index] at the
 * current [phase] tick. The sine argument wraps around via modulo so the
 * wave repeats smoothly. The offset is small enough that at high amplitude
 * the envelope shape dominates, but at low/zero amplitude it produces a
 * visible flowing ripple — the key to making the indicator read as "alive"
 * even when the mic is picking up only ambient noise.
 */
internal fun waveformPhaseOffset(index: Int, phase: Float): Float {
    val angle = ((index + phase) % WAVEFORM_BARS) / WAVEFORM_BARS * TWO_PI
    return (WAVEFORM_PHASE_AMPLITUDE * kotlin.math.sin(angle)).toFloat()
}

private const val WAVEFORM_BARS = 30
private const val WAVEFORM_WAVE_PERIOD_MS = 1400
private const val WAVEFORM_PHASE_AMPLITUDE = 0.18
private const val TWO_PI = 2.0 * kotlin.math.PI

/**
 * Per-bar envelope, in dp, used by [Waveform] to vary heights across the
 * 30 bars. Re-creates the visual rhythm of the mockup's hand-tuned bar
 * heights (a wide hump with smaller side-lobes). Lives at file scope so
 * it can be exercised without composing the whole waveform.
 */
internal fun barEnvelopeHeightDp(index: Int): Float {
    // Cosine envelope: tallest in the middle of the strip, tapering to
    // the edges. The constants are chosen to land between 6dp (edge bars)
    // and 28dp (centre bars) — visually identical to the mockup's
    // hand-tuned heights.
    val n = 30
    val centred = (index - (n - 1) / 2f) / ((n - 1) / 2f) // [-1, 1]
    // 1 - centred^2 -> 1 at centre, 0 at edges.
    val envelope = 1f - centred * centred
    return 6f + envelope * 22f // [6dp .. 28dp]
}

/**
 * Issue #900: committed outbound prompts for the current session. This is a
 * foreground visibility/action surface only; durable retry behavior lands in
 * the owning VM/store slice.
 */
@Composable
private fun OutboundQueueBanner(
    items: List<OutboundItem>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onDelete: (String) -> Unit,
    onRetry: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = PocketShellColors.SurfaceElev,
                shape = RoundedCornerShape(8.dp),
            )
            .border(
                width = 1.dp,
                color = PocketShellColors.BorderSoft,
                shape = RoundedCornerShape(8.dp),
            )
            .testTag(COMPOSER_OUTBOUND_QUEUE_BANNER_TAG),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onToggle)
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .testTag(COMPOSER_OUTBOUND_QUEUE_TOGGLE_TAG),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = outboundQueueHeadline(items),
                    color = PocketShellColors.Text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = outboundQueueSubline(items),
                    color = PocketShellColors.TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DisclosureIcon(
                expanded = expanded,
                tint = PocketShellColors.TextSecondary,
            )
        }

        if (expanded) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .height(1.dp)
                    .background(PocketShellColors.BorderSoft),
            )
            items.forEach { item ->
                OutboundQueueRow(
                    item = item,
                    onDelete = { onDelete(item.id) },
                    onRetry = { onRetry(item.id) },
                )
            }
        }
    }
}

@Composable
private fun OutboundQueueRow(
    item: OutboundItem,
    onDelete: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag(composerOutboundQueueItemRowTestTag(item.id)),
    ) {
        Text(
            text = formatRelativeTimestamp(item.createdAtMs, System.currentTimeMillis()),
            color = PocketShellColors.TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = outboundQueueStateLabel(item),
            color = PocketShellColors.TextSecondary,
            fontSize = 11.sp,
        )
        if (item.cleanText.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = item.cleanText,
                color = PocketShellColors.Text,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (item.attachments.isNotEmpty()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = outboundAttachmentCountLabel(item.attachments.size),
                color = PocketShellColors.TextSecondary,
                fontSize = 11.sp,
            )
        }
        if (item.state == OutboundState.Queued || item.state == OutboundState.Failed) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                PendingActionButton(
                    label = "Delete",
                    primary = false,
                    onClick = onDelete,
                    modifier = Modifier.testTag(composerOutboundQueueDeleteTestTag(item.id)),
                )
                PendingActionButton(
                    label = "Retry",
                    primary = true,
                    onClick = onRetry,
                    modifier = Modifier.testTag(composerOutboundQueueRetryTestTag(item.id)),
                )
            }
        }
    }
}

/**
 * Issue #180: banner + expandable list rendered above the mic row when
 * the failed/offline-queued transcription list is non-empty.
 *
 * Two visual states:
 *  - **Collapsed (default):** a single row showing "N pending
 *    transcription(s)" with a chevron. Tapping anywhere on the row
 *    toggles to the expanded state. The row also surfaces the first
 *    queued row's status ("Waiting for network" vs the Whisper error
 *    text) so the user can decide whether to expand before tapping.
 *  - **Expanded:** the collapsed row plus a vertical list of per-item
 *    rows. Each per-item row has:
 *      - The relative timestamp ("X minutes ago")
 *      - The status / error message
 *      - Retry / Discard buttons when below the retry cap
 *      - Save-as-audio / Discard buttons when at the cap
 *
 * The whole banner is wrapped in a single tap target for the
 * collapsed/expand toggle, and individual buttons stop propagation by
 * using `clickable(...)` directly on themselves.
 */
@Composable
private fun PendingTranscriptionsBanner(
    items: List<PendingTranscriptionItem>,
    retryingIds: Set<String>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onRetry: (String) -> Unit,
    onDiscard: (String) -> Unit,
    onSaveAsAudio: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = PocketShellColors.AccentSoft,
                shape = RoundedCornerShape(8.dp),
            )
            .border(
                width = 1.dp,
                color = PocketShellColors.Accent.copy(alpha = 0.4f),
                shape = RoundedCornerShape(8.dp),
            )
            .testTag(COMPOSER_PENDING_BANNER_TAG),
    ) {
        // Summary row — always rendered, always tappable.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onToggle)
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .testTag(COMPOSER_PENDING_TOGGLE_TAG),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = pendingSummaryHeadline(items),
                    color = PocketShellColors.Accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                val subline = pendingSummarySubline(items)
                if (subline.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subline,
                        color = PocketShellColors.Accent.copy(alpha = 0.85f),
                        fontSize = 11.sp,
                    )
                }
            }
            DisclosureIcon(
                expanded = expanded,
                tint = PocketShellColors.Accent,
            )
        }

        if (expanded) {
            // 1dp top divider so the per-item list reads as a sub-section
            // rather than running into the summary row.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .height(1.dp)
                    .background(PocketShellColors.Accent.copy(alpha = 0.25f)),
            )
            items.forEach { item ->
                PendingTranscriptionRow(
                    item = item,
                    retrying = item.id in retryingIds,
                    onRetry = { onRetry(item.id) },
                    onDiscard = { onDiscard(item.id) },
                    onSaveAsAudio = { onSaveAsAudio(item.id) },
                )
            }
        }
    }
}

/**
 * Issue #180: per-item row inside the expanded queue banner.
 */
@Composable
private fun PendingTranscriptionRow(
    item: PendingTranscriptionItem,
    retrying: Boolean,
    onRetry: () -> Unit,
    onDiscard: () -> Unit,
    onSaveAsAudio: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag(composerPendingItemRowTestTag(item.id)),
    ) {
        Text(
            text = formatRelativeTimestamp(item.recordingTimestampMs, System.currentTimeMillis()),
            color = PocketShellColors.Accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
        // Issue #688: a retry in flight shows immediate "Retrying…" feedback
        // so the Retry tap is never a silent no-op. The status line takes
        // priority over the prior error/waiting text while the round-trip
        // is open.
        val statusText = when {
            retrying -> PENDING_RETRYING_MESSAGE
            item.isWaitingForNetwork -> PendingTranscriptionItem.NETWORK_WAITING_MESSAGE
            item.lastErrorMessage != null -> item.lastErrorMessage
            else -> "Queued — tap retry to send"
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = statusText,
            color = PocketShellColors.Accent.copy(alpha = 0.85f),
            fontSize = 11.sp,
        )
        if (item.retryCount > 0 && !item.atRetryCap) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Attempt ${item.retryCount + 1} of " +
                    "${PendingTranscriptionEntity.MAX_RETRY_ATTEMPTS}",
                color = PocketShellColors.Accent.copy(alpha = 0.7f),
                fontSize = 11.sp,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        // #208 follow-up move #2: right-align the action row so the
        // primary button (Retry or Save audio) lands closest to the
        // right thumb. Discard (secondary) renders to its left.
        // Maintainer feedback 2026-05-27 + audit comment-4554970633
        // call this out — Retry was previously leftmost under the
        // default Arrangement.Start.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            if (!item.atRetryCap) {
                // Order matters: with Arrangement.End the row fills
                // right-to-left visually but children still render in
                // declaration order. Discard first → ends up left of
                // Retry; Retry last → anchors the right edge.
                PendingActionButton(
                    label = "Discard",
                    primary = false,
                    onClick = onDiscard,
                    modifier = Modifier.testTag(composerPendingDiscardTestTag(item.id)),
                )
                PendingActionButton(
                    // Issue #688: the button label flips to "Retrying…" and
                    // the click is gated while a round-trip is in flight, so
                    // a second tap cannot stack a duplicate retry on top.
                    label = if (retrying) "Retrying…" else "Retry",
                    primary = true,
                    enabled = !retrying,
                    onClick = onRetry,
                    modifier = Modifier.testTag(composerPendingRetryTestTag(item.id)),
                )
            } else {
                // 3-retry cap hit — Whisper has repeatedly failed for
                // this audio. Offer Save (so the user can hand it to a
                // different transcription tool) or Discard. Save is the
                // primary action here and travels to the right edge.
                PendingActionButton(
                    label = "Discard",
                    primary = false,
                    onClick = onDiscard,
                    modifier = Modifier.testTag(composerPendingDiscardTestTag(item.id)),
                )
                PendingActionButton(
                    label = "Save audio",
                    primary = true,
                    onClick = onSaveAsAudio,
                    modifier = Modifier.testTag(composerPendingSaveTestTag(item.id)),
                )
            }
        }
    }
}

@Composable
private fun PendingActionButton(
    label: String,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val containerColor = if (primary) PocketShellColors.Accent else PocketShellColors.SurfaceElev
    val borderColor = if (primary) PocketShellColors.Accent else PocketShellColors.Border
    val contentColor = if (primary) PocketShellColors.OnAccent else PocketShellColors.Text
    val alpha = if (enabled) 1f else 0.5f
    Box(
        modifier = modifier
            .clickable(
                enabled = enabled,
                role = androidx.compose.ui.semantics.Role.Button,
                onClick = onClick,
            )
            .background(
                color = containerColor.copy(alpha = alpha),
                shape = RoundedCornerShape(6.dp),
            )
            .border(
                width = 1.dp,
                color = borderColor.copy(alpha = alpha),
                shape = RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            color = contentColor.copy(alpha = alpha),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Issue #180 helper: headline string for the collapsed queue banner.
 * Singular vs plural reads naturally; exposed `internal` so the unit
 * tests can assert against the exact copy without re-implementing the
 * pluralisation logic.
 */
internal fun pendingSummaryHeadline(items: List<PendingTranscriptionItem>): String = when (items.size) {
    0 -> ""
    1 -> "1 pending transcription"
    else -> "${items.size} pending transcriptions"
}

/**
 * Issue #180 helper: secondary line summarising the most recent item's
 * status. Avoids cramming a multi-line description into the collapsed
 * row.
 */
internal fun pendingSummarySubline(items: List<PendingTranscriptionItem>): String {
    val first = items.firstOrNull() ?: return ""
    return when {
        first.isWaitingForNetwork -> "Waiting for network — tap to view"
        first.atRetryCap -> "Tap to save or discard"
        first.lastErrorMessage != null -> "Tap to retry"
        else -> "Tap to retry"
    }
}

internal fun outboundQueueHeadline(items: List<OutboundItem>): String = when (items.size) {
    0 -> ""
    1 -> "1 unsent prompt"
    else -> "${items.size} unsent prompts"
}

internal fun outboundQueueSubline(items: List<OutboundItem>): String {
    val first = items.firstOrNull() ?: return ""
    val preview = first.cleanText.trim().lineSequence().firstOrNull().orEmpty()
    val status = outboundQueueStateLabel(first)
    return when {
        preview.isBlank() -> status
        else -> "$status — $preview"
    }
}

internal fun outboundQueueStateLabel(item: OutboundItem): String = when (item.state) {
    OutboundState.Queued -> "Queued"
    OutboundState.Uploading -> "Uploading attachments"
    OutboundState.InFlight -> "Sending"
    OutboundState.Delivered -> "Delivered"
    OutboundState.Failed -> item.lastError?.takeIf { it.isNotBlank() }?.let { "Failed — $it" } ?: "Failed"
}

internal fun outboundAttachmentCountLabel(count: Int): String =
    "$count attachment${if (count == 1) "" else "s"}"

/**
 * Issue #180 helper: humanise an epoch-millis timestamp into "Just now"
 * / "X seconds/minutes/hours ago" relative to [nowMs]. Exposed `internal`
 * so unit tests can verify the breakpoints without standing up the
 * whole banner.
 */
internal fun formatRelativeTimestamp(timestampMs: Long, nowMs: Long): String {
    val delta = (nowMs - timestampMs).coerceAtLeast(0L)
    return when {
        delta < 30_000L -> "Just now"
        delta < 60_000L -> "${delta / 1_000L} seconds ago"
        delta < 60L * 60_000L -> {
            val minutes = delta / 60_000L
            if (minutes == 1L) "1 minute ago" else "$minutes minutes ago"
        }
        delta < 24L * 60L * 60_000L -> {
            val hours = delta / (60L * 60_000L)
            if (hours == 1L) "1 hour ago" else "$hours hours ago"
        }
        else -> {
            val days = delta / (24L * 60L * 60_000L)
            if (days == 1L) "1 day ago" else "$days days ago"
        }
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
 * Issue #196: the agent-pane Send button tag, shared with
 * [AgentComposerSurface] and consumed by the tmux / raw-SSH conversation
 * screens (`TmuxSessionScreen`, `SessionScreen`). The composer sheet itself
 * no longer renders a separate Insert button (#453 collapsed the pair), but
 * the agent surfaces still tag their single Send with this value.
 */
internal const val COMPOSER_SEND_TAG = "prompt-composer-send"

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

/**
 * Historic tag for the staged-attachment tile grid (FlowRow) at the bottom of
 * the composer. Present only while at least one attachment is staged.
 */
internal const val COMPOSER_ATTACHMENT_CHIPS_TAG = "prompt-composer-attachment-chips"

/** Historic per-tile tag, keyed by the attachment's remote path. */
internal fun composerAttachmentChipTestTag(remotePath: String): String =
    "prompt-composer-attachment-chip:$remotePath"

/** Historic per-tile `×` remove button tag, keyed by remote path. */
internal fun composerAttachmentRemoveTestTag(remotePath: String): String =
    "prompt-composer-attachment-remove:$remotePath"

/** Per-image tile thumbnail tag, keyed by remote path. */
internal fun composerAttachmentThumbnailTestTag(remotePath: String): String =
    "prompt-composer-attachment-thumbnail:$remotePath"

/** Per non-image typed tile tag, keyed by remote path. */
internal fun composerAttachmentTypeTileTestTag(remotePath: String): String =
    "prompt-composer-attachment-type-tile:$remotePath"

/** Issue #453: mockup placeholder text for the empty composer input. */
internal const val COMPOSER_PLACEHOLDER = "Compose prompt…"

/** Issue #453: elapsed mm:ss recording timer rendered next to the waveform. */
internal const val COMPOSER_TIMER_TAG = "prompt-composer-timer"

/**
 * Issue #870: the dedicated two-line live-transcript container (the fixed-height
 * box). [COMPOSER_LIVE_TRANSCRIPT_TEXT_TAG] tags the inner [Text] that holds the
 * resolved (tail-trimmed) content so a test can read what is actually laid out.
 */
internal const val COMPOSER_LIVE_TRANSCRIPT_TAG = "prompt-composer-live-transcript"
internal const val COMPOSER_LIVE_TRANSCRIPT_TEXT_TAG = "prompt-composer-live-transcript-text"
internal const val COMPOSER_RECORDING_LOCKED_TAG = "prompt-composer-recording-locked"

/**
 * Issue #870 (reopen): the dedicated live-transcript area holds exactly this
 * many lines — line two is the live, in-progress recognition (the maintainer's
 * design direction). A long partial keeps its TAIL within these lines.
 */
internal const val LIVE_TRANSCRIPT_MAX_LINES: Int = 2

/**
 * Issue #870: rough line-height multiple over the font size, used to size the
 * fixed two-line box. `bodyDense` does not set an explicit `lineHeight`, so the
 * platform default is ~1.2–1.4×; 1.5× gives the two-line panel a little breathing
 * room and matches the prior 112dp panel allowance without hard-coding a dp.
 */
internal const val LIVE_TRANSCRIPT_LINE_HEIGHT_FACTOR: Float = 1.5f

/**
 * Issue #870 (reopen): width-aware tail resolution for the live transcript.
 *
 * Returns the slice of [text] that fits within [maxLines] lines at [maxWidthPx]
 * for [style], measured with [measurer]. When the full text fits, it is returned
 * untouched. Otherwise the OLDEST words are dropped (keeping the TAIL/newest
 * words), snapped to a word boundary, with a leading `…` marking the cut — so the
 * latest recognized words are always the ones rendered, regardless of device
 * width or font scale. This is the property the width-independent character
 * budget in the superseded `liveTranscriptTail` could not guarantee.
 *
 * Pure (no Compose state) so it is unit-testable with a real [TextMeasurer].
 */
internal fun resolveLiveTranscriptTail(
    text: String,
    measurer: TextMeasurer,
    style: TextStyle,
    maxWidthPx: Int,
    maxLines: Int = LIVE_TRANSCRIPT_MAX_LINES,
): String {
    if (maxWidthPx <= 0 || maxLines <= 0 || text.isEmpty()) return text

    fun fits(candidate: String): Boolean {
        val result = measurer.measure(
            text = candidate,
            style = style,
            constraints = Constraints(maxWidth = maxWidthPx),
        )
        return result.lineCount <= maxLines
    }

    if (fits(text)) return text

    // Binary-search the smallest number of dropped LEADING characters such that
    // the remaining tail (with a leading "… ") fits maxLines. We drop from the
    // head, so the newest words at the tail are always kept.
    var lo = 1 // drop at least one char (full text already failed `fits`)
    var hi = text.length
    var bestStart = text.length
    while (lo <= hi) {
        val mid = (lo + hi) / 2
        val start = mid
        val candidate = "… " + text.substring(start).trimStart()
        if (fits(candidate)) {
            // This tail fits; try keeping MORE of the text (drop fewer chars).
            bestStart = start
            hi = mid - 1
        } else {
            lo = mid + 1
        }
    }

    // Snap the kept window forward to the next word boundary so the first
    // visible word is whole, not a fragment of the dropped head.
    var start = bestStart.coerceIn(0, text.length)
    val nextSpace = text.indexOf(' ', start)
    if (nextSpace in start until text.length) {
        start = nextSpace + 1
    }
    val tail = text.substring(start).trimStart()
    return if (tail.isEmpty()) text.takeLast(1) else "… $tail"
}

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
 * Issue #153 fix 2: test tag for the in-flight transcribing spinner
 * rendered over the centre of the (collapsed) waveform while the FSM is
 * in `Transcribing`. The spinner is the affordance that makes
 * Transcribing visually distinct from Listening / Capturing — connected
 * tests pin its presence so a future refactor cannot silently regress
 * the distinction.
 */
internal const val COMPOSER_TRANSCRIBING_SPINNER_TAG = "prompt-composer-transcribing-spinner"

/**
 * Issue #153 fix 3: long-press tooltip copy for the Send buttons. Kept
 * here as named constants so connected tests can assert against the
 * exact wording without re-implementing the copy and so future copy
 * tweaks live in one obvious place.
 */
internal const val SEND_TOOLTIP_LABEL: String =
    "Insert the draft into the prompt without submitting"
internal const val SEND_ENTER_TOOLTIP_LABEL: String =
    "Send the draft and submit it with Enter"

/**
 * Issue #153 fix 3: stable test tag for the long-press tooltip popup
 * surface. The tag derives from the tooltip copy so the OutlineSend and
 * Primary Send tooltips get distinct tags without an extra parameter
 * threaded through. Connected tests can long-press the button and
 * assert the popup surfaced with the expected copy.
 */
internal fun composerSendTooltipTestTag(label: String): String =
    "prompt-composer-send-tooltip:" + label.hashCode().toString(16)


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
// Issue #585: a deliberate upward pull should lock recording "from the
// gesture" — kept just above the system touch-slop (~18dp) so a tap doesn't
// accidentally lock, but small enough that any real swipe-up immediately
// transitions to live+locked rather than requiring a long drag.
private const val MIC_LOCK_SWIPE_THRESHOLD_DP = 24

// Issue #585: how far outside the tight mic-disc rect a press still counts as
// "on the mic" for arming the swipe-up gesture. The disc is 44dp; this slop
// makes a slightly-off hold-and-pull-up still start recording.
private const val MIC_GESTURE_START_SLOP_DP = 24

// Issue #682: cap the scrollable upper region (draft + status banners) so the
// content-height sheet never grows taller than this even with a long draft +
// several banners. Keeps the composer compact above the keyboard; the inner
// `verticalScroll` lets a long draft scroll within the cap instead of pushing
// the sticky action row off-screen. Used ONLY when the keyboard is DOWN; when it
// is UP the region is `weight(1f)`-sized to the genuine room above the keyboard
// (no fixed cap — issue #801).
private val PromptComposerScrollRegionMaxHeight = 360.dp

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
internal const val COMPOSER_PENDING_BANNER_TAG = "prompt-composer-pending-banner"
internal const val COMPOSER_PENDING_TOGGLE_TAG = "prompt-composer-pending-toggle"
internal const val COMPOSER_PENDING_SAVED_BANNER_TAG = "prompt-composer-pending-saved"
internal const val COMPOSER_OUTBOUND_QUEUE_BANNER_TAG = "prompt-composer-outbound-queue"
internal const val COMPOSER_OUTBOUND_QUEUE_TOGGLE_TAG = "prompt-composer-outbound-queue-toggle"

/**
 * Issue #688: status text shown on a pending row while its retry round-trip
 * is in flight, so a Retry tap gives immediate visible feedback instead of
 * looking like a no-op.
 */
internal const val PENDING_RETRYING_MESSAGE = "Retrying…"

internal fun composerPendingItemRowTestTag(id: String): String =
    "prompt-composer-pending-row:$id"

internal fun composerPendingRetryTestTag(id: String): String =
    "prompt-composer-pending-retry:$id"

internal fun composerPendingDiscardTestTag(id: String): String =
    "prompt-composer-pending-discard:$id"

internal fun composerPendingSaveTestTag(id: String): String =
    "prompt-composer-pending-save:$id"

internal fun composerOutboundQueueItemRowTestTag(id: String): String =
    "prompt-composer-outbound-queue-row:$id"

internal fun composerOutboundQueueDeleteTestTag(id: String): String =
    "prompt-composer-outbound-queue-delete:$id"

internal fun composerOutboundQueueRetryTestTag(id: String): String =
    "prompt-composer-outbound-queue-retry:$id"

// -- Previews -----------------------------------------------------------------

/**
 * Idle state — empty text area, mic in `Idle`, waveform collapsed.
 * Matches the cold-open look of the composer.
 */
@Preview(name = "Composer · idle", widthDp = 412, heightDp = 360)
@Composable
private fun PromptComposerIdlePreview() {
    PocketShellTheme {
        Box(modifier = Modifier.background(PocketShellColors.Surface)) {
            SheetContent(
                state = PromptComposerViewModel.UiState(
                    draft = "",
                    recording = PromptComposerViewModel.RecordingState.Idle,
                    amplitude = 0f,
                    error = null,
                ),
                onClose = {},
                onDraftChange = {},
                onMicTap = {},
                onSend = {},
            )
        }
    }
}

/**
 * Issue #453 / #508: Recording state — amplitude-driven waveform + the
 * `00:17` elapsed timer; below, the two explicit stop actions ("Insert" +
 * "Send"). No persistent Auto-send toggle, no redundant "CAPTURING" text.
 */
@Preview(name = "Composer · recording", widthDp = 412, heightDp = 360)
@Composable
private fun PromptComposerRecordingPreview() {
    PocketShellTheme {
        Box(modifier = Modifier.background(PocketShellColors.Surface)) {
            SheetContent(
                state = PromptComposerViewModel.UiState(
                    draft = "",
                    recording = PromptComposerViewModel.RecordingState.Recording,
                    amplitude = 0.7f,
                    hasDetectedSpeech = true,
                    recordingElapsedMs = 17_000L,
                    // Issue #870 (reopen): a long live partial — the dedicated
                    // two-line area keeps the TAIL (latest words) visible and
                    // marks the dropped head with a leading ellipsis.
                    liveTranscript = "open the deployment pipeline and check the " +
                        "logs then restart the worker and confirm the latest commit is deployed",
                    error = null,
                ),
                onClose = {},
                onDraftChange = {},
                onMicTap = {},
                onSend = {},
            )
        }
    }
}

/**
 * Issue #453 / #508: Transcribing state — "Transcribing…" + spinner; below,
 * Cancel + "Send" (arms the queued send for the in-flight round-trip). No
 * persistent Auto-send toggle.
 */
@Preview(name = "Composer · transcribing", widthDp = 412, heightDp = 360)
@Composable
private fun PromptComposerTranscribingPreview() {
    PocketShellTheme {
        Box(modifier = Modifier.background(PocketShellColors.Surface)) {
            SheetContent(
                state = PromptComposerViewModel.UiState(
                    draft = "",
                    recording = PromptComposerViewModel.RecordingState.Transcribing,
                    amplitude = 0f,
                    error = null,
                ),
                onClose = {},
                onDraftChange = {},
                onMicTap = {},
                onSend = {},
            )
        }
    }
}

/**
 * Issue #453: Text-inserted state — the transcript fills the editable input
 * and is editable before Send; 📎/`{}` left, Send + arrow right.
 */
@Preview(name = "Composer · text inserted", widthDp = 412, heightDp = 360)
@Composable
private fun PromptComposerTextInsertedPreview() {
    PocketShellTheme {
        Box(modifier = Modifier.background(PocketShellColors.Surface)) {
            SheetContent(
                state = PromptComposerViewModel.UiState(
                    draft = "check the deploy log and tell me what failed in the last run",
                    recording = PromptComposerViewModel.RecordingState.Idle,
                    amplitude = 0f,
                    error = null,
                ),
                onClose = {},
                onDraftChange = {},
                onMicTap = {},
                onSend = {},
            )
        }
    }
}
