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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pocketshell.app.snippets.SnippetKind
import com.pocketshell.app.snippets.SnippetPickerSheet
import com.pocketshell.app.voice.PendingTranscriptionItem
import com.pocketshell.core.storage.entity.PendingTranscriptionEntity
import com.pocketshell.uikit.theme.LocalPocketShellSemantic
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme

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
    onSend: suspend (text: String, withEnter: Boolean) -> Boolean,
    modifier: Modifier = Modifier,
    hostId: Long? = null,
    onStageAttachments: (suspend (List<Uri>) -> Result<List<String>>)? = null,
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
) {
    val state by viewModel.uiState.collectAsState()
    val pendingItems by viewModel.pendingItems.collectAsState()
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
            if (viewModel.hasApiKey()) {
                viewModel.onMicTap()
            } else {
                showApiKeyDialog = true
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
            viewModel.attachFiles(uris.size) {
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
    LaunchedEffect(viewModel) {
        viewModel.sendRequests.collect { request ->
            if (currentOnSend(request.text, request.withEnter)) {
                // Dismiss the sheet so the user lands back on the terminal
                // with the bytes already flying. Historic behaviour from
                // pre-#211; the dismiss + draft-clear now lives on the
                // ViewModel side of the surface so the sheet's role is just
                // to forward the request.
                currentOnDismiss()
            } else {
                viewModel.restoreFailedSend(request)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = PocketShellColors.Surface,
        contentColor = PocketShellColors.Text,
        modifier = modifier,
    ) {
        // Issue #234: force the sheet content to be tall enough that
        // Material 3 actually populates the `PartiallyExpanded` anchor.
        //
        // M3 1.3.2's `ModalBottomSheet` only assigns a partial-expand
        // anchor when the sheet's intrinsic content height is greater
        // than half the screen height; otherwise the partial state is
        // suppressed and the sheet lands at `Expanded` regardless of
        // `skipPartiallyExpanded`. The composer's natural content
        // (~300dp on a Pixel 7) sits well under that threshold, so
        // without an explicit minimum we keep the legacy "always
        // full-content / Expanded" behaviour and the AC #1 partial-
        // expand resting state never fires.
        //
        // `fillMaxHeight(0.65f)` makes the sheet content 65% of the
        // available height. That's tall enough on every phone in our
        // supported range (Pixel 4 at 800dp → 65% = 520dp; Pixel 7 at
        // 891dp → 65% = ~580dp) to trip the > halfHeight check, which
        // means M3 will create the PartiallyExpanded anchor at
        // `fullHeight / 2`. The sheet then rests at PartiallyExpanded
        // by default, occupying the bottom ~50% of the screen and
        // leaving the top ~50% scrim + visible terminal — the
        // composer-modal-inversion fix the #191 UX audit asked for.
        SheetContent(
            modifier = Modifier.fillMaxHeight(0.65f),
            state = state,
            onClose = onDismiss,
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
                if (!viewModel.hasApiKey()) {
                    showApiKeyDialog = true
                    return@SheetContent
                }
                viewModel.onMicTap()
            },
            // Issue #453: the only Cancel affordance in the redesigned
            // composer lives in the Transcribing state and cancels the
            // in-flight Whisper round-trip. (Recording is stopped via the
            // red Stop button, which always transcribes.)
            onCancelRecording = viewModel::cancelTranscription,
            onAutoSendChange = viewModel::setAutoSend,
            onSend = { withEnter ->
                // Issue #211: route through the ViewModel so the FSM
                // decides whether to dispatch now (Idle) or queue for
                // transcription (Recording / Transcribing). The
                // ViewModel emits via `sendRequests` once the dispatch
                // is ready; the `LaunchedEffect` above forwards it into
                // the host's `onSend` + `onDismiss`.
                viewModel.requestSend(withEnter)
            },
            onSnippets = if (hostId != null) {
                { showSnippetPicker = true }
            } else null,
            onAttachFiles = if (onStageAttachments != null) {
                { attachmentLauncher.launch(arrayOf("*/*")) }
            } else null,
            pendingItems = pendingItems,
            pendingListExpanded = pendingListExpanded,
            onTogglePendingList = { pendingListExpanded = !pendingListExpanded },
            onRetryPending = viewModel::retryPending,
            onDiscardPending = viewModel::discardPending,
            onSavePendingAsAudio = viewModel::savePendingAsAudioFile,
            onAcknowledgeSavedAudio = viewModel::clearSavedAudioConfirmation,
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
    // Issue #174: dispatched by the cancel `X` chip rendered next to
    // the mic FAB while [PromptComposerViewModel.RecordingState] is
    // [PromptComposerViewModel.RecordingState.Recording]. Defaults to a
    // no-op so existing previews and the legacy connected tests that
    // bypass the ViewModel keep compiling.
    onCancelRecording: () -> Unit = {},
    // Issue #453: flip the Auto-send toggle. Defaults to a no-op so
    // previews / legacy tests that don't exercise the toggle keep
    // compiling.
    onAutoSendChange: (Boolean) -> Unit = {},
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

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(PocketShellColors.Surface)
            .padding(horizontal = 18.dp)
            .padding(bottom = 26.dp),
    ) {
        // Header: title + close X. The Material 3 sheet draws its own
        // grabber above this, so we don't redraw it.
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
                color = PocketShellColors.Text,
            )
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "×",
                    color = PocketShellColors.TextSecondary,
                    fontSize = 20.sp,
                )
            }
        }

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
                    onStop = onMicTap,
                )
            }

            PromptComposerViewModel.RecordingState.Transcribing -> {
                TranscribingSurface(onCancel = onCancelRecording)
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
                    value = state.draft,
                    onValueChange = onDraftChange,
                    placeholder = COMPOSER_PLACEHOLDER,
                    fieldTag = COMPOSER_DRAFT_TAG,
                    minHeight = 96.dp,
                    maxHeight = 220.dp,
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Optional error / status banner above the mic row. Keeps the
        // user informed about permission / API-key / Whisper failures
        // without a Snackbar (the sheet doesn't host a scaffold).
        state.error?.let { msg ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = PocketShellColors.AccentSoft,
                        shape = RoundedCornerShape(8.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(text = msg, color = PocketShellColors.Accent, fontSize = 12.sp)
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
                expanded = pendingListExpanded,
                onToggle = onTogglePendingList,
                onRetry = onRetryPending,
                onDiscard = onDiscardPending,
                onSaveAsAudio = onSavePendingAsAudio,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Issue #453: the single decluttered controls row at the bottom of
        // the composer, matching all four mockup states:
        //  - Left: 📎 attach (paperclip) + `{}` snippets — always present.
        //  - Right, Idle / Text-inserted: a single primary Send button with
        //    a send-arrow glyph (the old Insert/Send pair collapses to one).
        //  - Right, Recording: an Auto-send toggle + a red record/stop FAB.
        //  - Right, Transcribing: an Auto-send toggle + a Cancel button.
        val isRecording = state.recording == PromptComposerViewModel.RecordingState.Recording
        val attachmentBusy = attachmentUploading != null
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Left cluster: paperclip attach + `{}` snippets. Disabled while
            // a Whisper round-trip / attachment upload is in flight so the
            // user can't land a stale snippet over the about-to-arrive
            // transcript.
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

            Spacer(modifier = Modifier.weight(1f))

            // Right cluster is state-driven.
            when (state.recording) {
                PromptComposerViewModel.RecordingState.Idle -> {
                    // Mic trigger to start a dictation (the entry point into
                    // the Recording state). Sits to the left of the primary
                    // Send so the Idle row reads "dictate, or type + send".
                    MicTriggerButton(
                        onClick = onMicTap,
                        enabled = !attachmentBusy,
                        modifier = Modifier.testTag(COMPOSER_MIC_TAG),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    val sendEnabled = state.draft.isNotEmpty() && !attachmentBusy
                    SendButton(
                        onClick = { onSend(true) },
                        enabled = sendEnabled,
                        modifier = Modifier.testTag(COMPOSER_SEND_ENTER_TAG),
                    )
                }

                PromptComposerViewModel.RecordingState.Recording -> {
                    AutoSendToggle(
                        checked = state.autoSend,
                        onCheckedChange = onAutoSendChange,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    // Red record/stop FAB: tapping it stops the recording
                    // and starts transcription (same path as the mockup's
                    // big red button at the bottom-right of the Recording
                    // state).
                    RecordStopButton(
                        onClick = onMicTap,
                        modifier = Modifier.testTag(COMPOSER_MIC_TAG),
                    )
                }

                PromptComposerViewModel.RecordingState.Transcribing -> {
                    AutoSendToggle(
                        checked = state.autoSend,
                        onCheckedChange = onAutoSendChange,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    GhostButton(
                        label = "Cancel",
                        onClick = onCancelRecording,
                        modifier = Modifier.testTag(COMPOSER_CANCEL_RECORDING_TAG),
                    )
                }
            }
        }
    }
}

/**
 * Issue #453: the Recording-state surface that replaces the editable input
 * — an amplitude-driven [Waveform] flanked by the elapsed mm:ss timer and a
 * "Stop" affordance. Matches the mockup's recording card. The animated
 * waveform alone (plus the live ticking timer) conveys "we are capturing"
 * — there is no redundant "CAPTURING" text label any more (the maintainer's
 * declutter request).
 */
@Composable
private fun RecordingSurface(
    amplitude: Float,
    capturing: Boolean,
    elapsedLabel: String,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
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
            .padding(horizontal = 14.dp),
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
        Waveform(
            amplitude = amplitude,
            active = capturing,
            transcribing = false,
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
        Text(
            text = "Stop",
            color = PocketShellColors.Accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clickable(role = Role.Button, onClick = onStop)
                .padding(horizontal = 6.dp, vertical = 6.dp)
                .testTag(COMPOSER_STOP_TAG),
        )
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
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // `onCancel` is consumed by the bottom-row Cancel button; this surface
    // only renders the status. Kept as a parameter so the surface owns the
    // full transcribing semantics for a11y.
    Row(
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
            .padding(horizontal = 14.dp)
            .testTag(COMPOSER_TRANSCRIBING_SPINNER_TAG)
            .semantics { contentDescription = "Prompt composer transcribing" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.5.dp,
            color = PocketShellColors.Accent,
        )
        Text(
            text = "Transcribing…",
            color = PocketShellColors.Text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * Issue #453: the single primary Send affordance for the Idle / Text-
 * inserted states. A pill with the "Send" label + a send-arrow glyph,
 * replacing the old Insert / Send button pair (declutter). Always submits
 * with Enter (`onSend(true)`), since the composer's job is to send a prompt.
 */
@Composable
private fun SendButton(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(44.dp)
            .background(
                color = if (enabled) PocketShellColors.Accent else Color.Transparent,
                shape = RoundedCornerShape(10.dp),
            )
            .border(
                width = 1.dp,
                color = if (enabled) PocketShellColors.Accent else PocketShellColors.Border,
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Send",
            color = if (enabled) PocketShellColors.OnAccent else PocketShellColors.TextMuted,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        SendArrowGlyph(
            color = if (enabled) PocketShellColors.OnAccent else PocketShellColors.TextMuted,
        )
    }
}

/**
 * Issue #453: the "Auto-send" toggle shown in the Recording / Transcribing
 * states. When ON, the completed dictation is sent immediately; when OFF,
 * the transcript lands in the editable input for review before Send.
 */
@Composable
private fun AutoSendToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clickable(role = Role.Switch) { onCheckedChange(!checked) }
            .testTag(COMPOSER_AUTO_SEND_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "Auto-send",
            color = if (checked) PocketShellColors.Accent else PocketShellColors.TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = PocketShellColors.OnAccent,
                checkedTrackColor = PocketShellColors.Accent,
                uncheckedThumbColor = PocketShellColors.TextSecondary,
                uncheckedTrackColor = PocketShellColors.SurfaceElev,
                uncheckedBorderColor = PocketShellColors.Border,
            ),
        )
    }
}

/**
 * Issue #453: the red record/stop FAB shown at the bottom-right of the
 * Recording state (the big red button in the mockup). Tapping it stops the
 * recording and starts transcription.
 */
@Composable
private fun RecordStopButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .background(color = PocketShellColors.Red, shape = androidx.compose.foundation.shape.CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Stop recording" },
        contentAlignment = Alignment.Center,
    ) {
        // White rounded square = the universal "stop" glyph.
        Box(
            modifier = Modifier
                .size(16.dp)
                .background(color = Color.White, shape = RoundedCornerShape(3.dp)),
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
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Start dictation" },
        contentAlignment = Alignment.Center,
    ) {
        // Filled circle glyph standing in for a microphone body (same
        // treatment as the shared MicButton until an icon set is bundled).
        Text(
            text = "●",
            color = if (enabled) PocketShellColors.OnAccent else PocketShellColors.TextMuted,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Issue #453: paperclip attach button (40dp tap target). Drawn with a
 * [Canvas] path so we don't pull in `material-icons-extended` or rely on an
 * emoji glyph that renders inconsistently across OEM fonts.
 */
@Composable
private fun AttachIconButton(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val tint = if (enabled) PocketShellColors.TextSecondary else PocketShellColors.TextMuted
    Box(
        modifier = modifier
            .size(40.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Attach files" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(22.dp)) {
            val w = size.width
            val h = size.height
            val stroke = Stroke(width = w * 0.10f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            // A single-stroke paperclip drawn on a diagonal, the canonical
            // "attach" glyph: an outer arm that hooks around the bottom and
            // back up, with the inner arm one notch shorter so the clip
            // reads as two nested rounded bends rather than a horseshoe.
            // Coordinates: the clip leans slightly right, top-open.
            val xLeft = w * 0.34f
            val xRight = w * 0.66f
            val top = h * 0.14f
            val bottom = h * 0.86f
            val rOuter = (xRight - xLeft) / 2f
            // Outer arm: starts near the top-right, runs down the right
            // side, loops the bottom (semicircle), runs up the left side,
            // and curves slightly over the top.
            val outer = Path().apply {
                moveTo(xRight, top + rOuter * 0.4f)
                lineTo(xRight, bottom - rOuter)
                arcTo(
                    rect = Rect(xLeft, bottom - 2 * rOuter, xRight, bottom),
                    startAngleDegrees = 0f,
                    sweepAngleDegrees = 180f,
                    forceMoveTo = false,
                )
                lineTo(xLeft, top + rOuter)
                arcTo(
                    rect = Rect(xLeft, top, xLeft + 2 * rOuter, top + 2 * rOuter),
                    startAngleDegrees = 180f,
                    sweepAngleDegrees = 90f,
                    forceMoveTo = false,
                )
            }
            drawPath(outer, color = tint, style = stroke)
            // Inner arm: the clip's shorter pin, offset inward and stopping
            // short of both ends so the two arms read as separate.
            val inLeft = xLeft + rOuter * 0.45f
            val inRight = xRight - rOuter * 0.0f
            val inBottom = bottom - rOuter * 0.55f
            val rInner = (inRight - inLeft) / 2f
            val inner = Path().apply {
                moveTo(inLeft, top + rOuter * 0.9f)
                lineTo(inLeft, inBottom - rInner)
                arcTo(
                    rect = Rect(inLeft, inBottom - 2 * rInner, inRight, inBottom),
                    startAngleDegrees = 180f,
                    sweepAngleDegrees = -180f,
                    forceMoveTo = false,
                )
                lineTo(inRight, top + rOuter * 1.4f)
            }
            drawPath(inner, color = tint, style = stroke)
        }
    }
}

/**
 * Issue #453: `{}` snippets button. Rendered as the literal curly-brace
 * glyphs (a stable monospace-friendly symbol, unlike an emoji), matching
 * the mockup's `{}` affordance.
 */
@Composable
private fun SnippetsIconButton(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val tint = if (enabled) PocketShellColors.TextSecondary else PocketShellColors.TextMuted
    Box(
        modifier = modifier
            .size(40.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Insert snippet" },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "{ }",
            color = tint,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Issue #453: the send-arrow glyph in the primary Send button. A small
 * paper-plane-style arrow drawn with a [Canvas] so it matches the mockup's
 * send icon without an icon dependency.
 */
@Composable
private fun SendArrowGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(16.dp)) {
        val w = size.width
        val h = size.height
        // Upward-right arrow (↗): the send/submit direction in the mockup.
        val stroke = Stroke(width = w * 0.14f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val path = Path().apply {
            // Shaft from bottom-left to top-right.
            moveTo(w * 0.18f, h * 0.82f)
            lineTo(w * 0.82f, h * 0.18f)
            // Arrow head.
            moveTo(w * 0.40f, h * 0.18f)
            lineTo(w * 0.82f, h * 0.18f)
            lineTo(w * 0.82f, h * 0.60f)
        }
        drawPath(path, color = color, style = stroke)
    }
}

/**
 * Issue #453: amplitude-driven waveform shown in the Recording state. The
 * animated strip alone conveys "we are capturing" — there is no redundant
 * status text any more (the maintainer's declutter request).
 *
 * Two visual modes:
 *  - **Active (capturing)** — [active] true: bars animate from the live
 *    amplitude, multiplied by the per-bar envelope.
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
    @Suppress("UNUSED_PARAMETER") transcribing: Boolean = false,
) {
    // Smooth amplitude transitions so a sudden spike doesn't jerk the
    // bars. 80ms is faster than the human eye's flicker fusion threshold
    // but slow enough to look organic.
    val smoothed by animateFloatAsState(
        targetValue = if (active) amplitude else 0f,
        animationSpec = tween(durationMillis = 80, easing = LinearEasing),
        label = "waveform-smooth",
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
            val bars = 30
            for (i in 0 until bars) {
                // Mockup-style envelope: a wide hump centred at index 15
                // with two smaller side lobes. Multiplied by the live
                // amplitude so a quiet user sees a flat strip and a loud
                // one sees full-height bars.
                val envelope = barEnvelopeHeightDp(i)
                val h = when {
                    active -> (4f + smoothed * envelope).coerceIn(4f, envelope)
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
            Text(
                text = if (expanded) "v" else ">",
                color = PocketShellColors.Accent,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
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
        val statusText = when {
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
                    label = "Retry",
                    primary = true,
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
) {
    Box(
        modifier = modifier
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .background(
                color = if (primary) PocketShellColors.Accent else PocketShellColors.SurfaceElev,
                shape = RoundedCornerShape(6.dp),
            )
            .border(
                width = 1.dp,
                color = if (primary) PocketShellColors.Accent else PocketShellColors.Border,
                shape = RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            color = if (primary) PocketShellColors.OnAccent else PocketShellColors.Text,
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
internal const val COMPOSER_WAVEFORM_TAG = "prompt-composer-waveform"
internal const val COMPOSER_MIC_TAG = "prompt-composer-mic"
internal const val COMPOSER_ATTACH_TAG = "prompt-composer-attach"
internal const val COMPOSER_SNIPPETS_TAG = "prompt-composer-snippets"
internal const val COMPOSER_ATTACHMENT_PROGRESS_TAG = "prompt-composer-attachment-progress"

/** Issue #453: mockup placeholder text for the empty composer input. */
internal const val COMPOSER_PLACEHOLDER = "Compose prompt…"

/** Issue #453: elapsed mm:ss recording timer rendered next to the waveform. */
internal const val COMPOSER_TIMER_TAG = "prompt-composer-timer"

/** Issue #453: the "Stop" affordance inside the recording surface. */
internal const val COMPOSER_STOP_TAG = "prompt-composer-stop"

/** Issue #453: the Auto-send toggle shown in Recording / Transcribing. */
internal const val COMPOSER_AUTO_SEND_TAG = "prompt-composer-auto-send"

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
 * Issue #174: test tag for the cancel-recording chip rendered next to
 * the mic FAB while the composer is in `Recording`. Connected tests use
 * this tag to locate the affordance, tap it, and assert the resulting
 * FSM transitions back to `Idle` without a Whisper call.
 */
internal const val COMPOSER_CANCEL_RECORDING_TAG = "prompt-composer-cancel-recording"

/**
 * Issue #180: test tags for the failed-transcription queue surface.
 * Connected tests assert that the banner appears when the queue is
 * non-empty and that the retry / discard / save affordances dispatch
 * into the ViewModel.
 */
internal const val COMPOSER_PENDING_BANNER_TAG = "prompt-composer-pending-banner"
internal const val COMPOSER_PENDING_TOGGLE_TAG = "prompt-composer-pending-toggle"
internal const val COMPOSER_PENDING_SAVED_BANNER_TAG = "prompt-composer-pending-saved"

internal fun composerPendingItemRowTestTag(id: String): String =
    "prompt-composer-pending-row:$id"

internal fun composerPendingRetryTestTag(id: String): String =
    "prompt-composer-pending-retry:$id"

internal fun composerPendingDiscardTestTag(id: String): String =
    "prompt-composer-pending-discard:$id"

internal fun composerPendingSaveTestTag(id: String): String =
    "prompt-composer-pending-save:$id"

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
 * Issue #453: Recording state — amplitude-driven waveform + the `00:17`
 * elapsed timer + Stop; below, the Auto-send toggle and the red record/stop
 * button. No redundant "CAPTURING" text — the animated indicator conveys it.
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
                    autoSend = false,
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
 * Issue #453: Transcribing state — "Transcribing…" + spinner + Cancel; the
 * Auto-send toggle is still shown.
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
                    autoSend = true,
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
