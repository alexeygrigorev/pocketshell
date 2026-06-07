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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.DataObject
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.ui.tooling.preview.Preview
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
import com.pocketshell.app.voice.DictateDotIcon
import com.pocketshell.app.voice.PendingTranscriptionItem
import com.pocketshell.core.storage.entity.PendingTranscriptionEntity
import com.pocketshell.uikit.theme.LocalPocketShellSemantic
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import com.pocketshell.uikit.theme.PocketShellType
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
            onRemoveAttachment = viewModel::removeAttachment,
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
    // Issue #491: IME show/hide is driven off the click frame via a coroutine
    // so a synchronous show()/hide() can never block the tap handler (or the
    // test framework's main-thread idling). The visible-text commit that makes
    // Send reliable does NOT depend on the IME call — it is the
    // [onDraftChange] flush below — so even if the keyboard animation is
    // mid-flight the send still carries the right text.
    val imeScope = rememberCoroutineScope()

    // Issue #491: the single Send path used by every Send affordance. Commit
    // the live editor text (composing region included) into the ViewModel
    // draft FIRST, then ask the ViewModel to dispatch. The commit is what
    // makes a Send tap deliver even when the keyboard was never raised / Enter
    // was never pressed; hiding the keyboard is a cosmetic follow-up dispatched
    // off-frame.
    val commitAndSend: () -> Unit = {
        onDraftChange(draftFieldValue.text)
        onSend(true)
        imeScope.launch { keyboardController?.hide() }
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
                    liveTranscript = state.liveTranscript,
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
                    minHeight = 96.dp,
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
                .padding(top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Left cluster: paperclip attach + `{}` snippets. Attachment
            // picking itself stays single-flight while a batch is uploading,
            // but typing, editing, dictation, and text-only Send remain live.
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
                        draftFieldValue.text.isNotEmpty() || state.attachments.isNotEmpty()
                    SendButton(
                        onClick = commitAndSend,
                        enabled = sendEnabled,
                        modifier = Modifier.testTag(COMPOSER_SEND_ENTER_TAG),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    // Small cyan mic disc at the far right (the mockup's mic).
                    // Sits AFTER Send so the row reads "type + send, or dictate".
                    MicTriggerButton(
                        onClick = onMicTap,
                        enabled = true,
                        modifier = Modifier.testTag(COMPOSER_MIC_TAG),
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
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(if (liveTranscript.isNullOrBlank()) 68.dp else 112.dp)
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
            DiscardRecordingButton(onClick = onCancel)
        }
        if (!liveTranscript.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = liveTranscript,
                color = PocketShellColors.Text,
                style = PocketShellType.bodyDense,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.testTag(COMPOSER_LIVE_TRANSCRIPT_TAG),
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
    // Issue #453: a borderless ghost text button — just "Send" + the send
    // arrow, no outline pill and no filled fill. The mockup renders Send as a
    // plain text+arrow control with the small cyan mic disc to its right as the
    // ONLY accent shape on the row, so any border/fill here reads as the
    // "large outlined Send pill" the reviewer flagged. Removing the outline
    // keeps the row compact and lets the mic carry the lone accent.
    val contentColor = if (enabled) PocketShellColors.Accent else PocketShellColors.TextMuted
    Row(
        modifier = modifier
            .height(44.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Send",
            color = contentColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.Send,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(17.dp),
        )
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
    val lockThresholdPx = with(LocalDensity.current) { MIC_LOCK_SWIPE_THRESHOLD_DP.dp.toPx() }
    Box(
        modifier = modifier
            .size(44.dp)
            .background(
                color = if (enabled) accent else PocketShellColors.SurfaceElev,
                shape = androidx.compose.foundation.shape.CircleShape,
            )
            .micSwipeUpLockGesture(
                enabled = enabled,
                lockThresholdPx = lockThresholdPx,
                onPressStart = onClick,
            )
            .semantics {
                contentDescription = "Start dictation"
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
    enabled: Boolean,
    lockThresholdPx: Float,
    onPressStart: () -> Unit,
): Modifier {
    if (!enabled) return this
    return pointerInput(enabled, lockThresholdPx, onPressStart) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val tracker = MicSwipeUpLockGestureTracker(lockThresholdPx)
            if (tracker.onPressStart() == MicSwipeUpLockGestureEvent.StartRecording) {
                onPressStart()
            }
            down.consume()
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                val drag = change.position - down.position
                if (tracker.onDrag(drag.x, drag.y) == MicSwipeUpLockGestureEvent.LockRecording) {
                    change.consume()
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
            Image(
                bitmap = thumbnail!!,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            AttachmentTypeTile(
                attachment = attachment,
                modifier = Modifier.fillMaxSize(),
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
            openInputStream = { resolver.openInputStream(uri) },
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
private val COMPOSER_ACTION_ICON_BUTTON_SIZE = 44.dp
private val COMPOSER_ACTION_ICON_SIZE = 22.dp

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
internal const val COMPOSER_WAVEFORM_TAG = "prompt-composer-waveform"
internal const val COMPOSER_MIC_TAG = "prompt-composer-mic"
internal const val COMPOSER_ATTACH_TAG = "prompt-composer-attach"
internal const val COMPOSER_SNIPPETS_TAG = "prompt-composer-snippets"
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

/** Issue #453: mockup placeholder text for the empty composer input. */
internal const val COMPOSER_PLACEHOLDER = "Compose prompt…"

/** Issue #453: elapsed mm:ss recording timer rendered next to the waveform. */
internal const val COMPOSER_TIMER_TAG = "prompt-composer-timer"
internal const val COMPOSER_LIVE_TRANSCRIPT_TAG = "prompt-composer-live-transcript"

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
private const val MIC_LOCK_SWIPE_THRESHOLD_DP = 40

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
