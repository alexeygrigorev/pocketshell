package com.pocketshell.app.composer

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
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
import com.pocketshell.app.snippets.SnippetPickerSheet
import com.pocketshell.app.voice.PendingTranscriptionItem
import com.pocketshell.core.storage.entity.PendingTranscriptionEntity
import com.pocketshell.uikit.components.MicButton
import com.pocketshell.uikit.model.MicButtonState
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
 *  5. Action row: `Snippets` (ghost), `Send`, `Send + ↵` (primary).
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
    onSend: (text: String, withEnter: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    hostId: Long? = null,
    viewModel: PromptComposerViewModel = hiltViewModel(),
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
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
            currentOnSend(request.text, request.withEnter)
            // Dismiss the sheet so the user lands back on the terminal
            // with the bytes already flying. Historic behaviour from
            // pre-#211; the dismiss + draft-clear now lives on the
            // ViewModel side of the surface so the sheet's role is just
            // to forward the request.
            currentOnDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = PocketShellColors.Surface,
        contentColor = PocketShellColors.Text,
        modifier = modifier,
    ) {
        SheetContent(
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
            onCancelRecording = viewModel::cancelRecording,
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
        // Issue #17 / #187: opens the snippet picker over the composer
        // sheet.
        //
        // Composer-specific invariant: a snippet pick in this context
        // appends to the live draft so the user can review/edit before
        // tapping the composer's own Send button. We deliberately do NOT
        // auto-fire the draft from here — that would let a stray tap
        // commit the wrong text and defeats the whole point of the
        // composer ("compose, then send").
        //
        // For issue #187 that means BOTH the row-body tap (legacy
        // `onSnippetPicked`) AND the explicit `Send` / `Send + ↵` chips
        // (`onSnippetSend`) route to the same draft-append path here.
        // The `withEnter` signal is intentionally ignored: the
        // composer's own `Send + ↵` button is the surface that submits
        // with Enter, so the snippet's chip becomes "paste this template
        // into the draft" in this context. The chip labels still read
        // "Send" / "Send + ↵" because they reflect what *would* happen
        // outside the composer; inside it the safety rule wins.
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
            onSnippetPicked = appendToDraft,
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
    // Issue #174: dispatched by the cancel `X` chip rendered next to
    // the mic FAB while [PromptComposerViewModel.RecordingState] is
    // [PromptComposerViewModel.RecordingState.Recording]. Defaults to a
    // no-op so existing previews and the legacy connected tests that
    // bypass the ViewModel keep compiling.
    onCancelRecording: () -> Unit = {},
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

        // The composer text area. `.composer-text` in the mockup is a
        // surface-elev fill with a single border and 12dp radius; we
        // mirror that with a Box wrapping a BasicTextField rather than
        // OutlinedTextField (whose label / supporting-text affordances
        // are not present in the mockup).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 110.dp)
                .background(
                    color = PocketShellColors.SurfaceElev,
                    shape = RoundedCornerShape(12.dp),
                )
                .border(
                    width = 1.dp,
                    color = PocketShellColors.Border,
                    shape = RoundedCornerShape(12.dp),
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            BasicTextField(
                value = state.draft,
                onValueChange = onDraftChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(COMPOSER_DRAFT_TAG),
                textStyle = TextStyle(
                    color = PocketShellColors.Text,
                    fontSize = 14.sp,
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(PocketShellColors.Accent),
                decorationBox = { inner ->
                    if (state.draft.isEmpty()) {
                        Text(
                            text = state.placeholderHint(),
                            color = PocketShellColors.TextMuted,
                            fontSize = 14.sp,
                        )
                    }
                    inner()
                },
            )
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

        // Mic row: waveform takes the slack on the left, status label
        // sits centre-right, and the mic FAB anchors the right edge so
        // the primary dictation control lives inside the right-thumb
        // reach arc (issue #208 right-hand ergonomics; design-system §9
        // already pins the tmux mic FAB bottom-right, the composer now
        // matches). The cancel chip from #174 travels with the mic and
        // renders immediately to its left during Recording, keeping the
        // abort affordance inside the same thumb arc.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Issue #195: the visual `Recording` state has two sub-states
            // keyed on `state.hasDetectedSpeech`. Before the first speech
            // amplitude crosses the threshold the waveform stays in its
            // idle (collapsed) rest pose so the user understands "the mic
            // is open but I haven't been heard yet"; once amplitude
            // crosses, the bars animate from the live amplitude and the
            // label flips to "CAPTURING". Anything that ties an animation
            // to `state.amplitude` should also gate on `hasDetectedSpeech`
            // — otherwise pre-speech sub-threshold mic noise would jiggle
            // the strip and contradict the "waiting for you" label.
            val isCapturing = state.recording == PromptComposerViewModel.RecordingState.Recording &&
                state.hasDetectedSpeech
            Waveform(
                amplitude = state.amplitude,
                active = isCapturing,
                modifier = Modifier
                    .weight(1f)
                    .height(32.dp)
                    .testTag(COMPOSER_WAVEFORM_TAG)
                    .semantics {
                        contentDescription = when {
                            state.recording == PromptComposerViewModel.RecordingState.Recording &&
                                state.hasDetectedSpeech ->
                                "Prompt composer capturing speech"
                            state.recording == PromptComposerViewModel.RecordingState.Recording ->
                                "Prompt composer waiting for speech"
                            state.recording == PromptComposerViewModel.RecordingState.Transcribing ->
                                "Prompt composer transcribing"
                            else ->
                                "Prompt composer idle waveform"
                        }
                    },
            )
            Text(
                // Issue #195: pre-speech vs active-speech sub-states under
                // the same `Recording` FSM node. "LISTENING" reads as
                // "system is open but waiting"; "CAPTURING" reads as
                // "system is hearing you right now". The flip happens
                // within one 50ms sampler poll of the first amplitude
                // crossing — well inside the 200ms responsiveness budget
                // called out in the issue acceptance criteria.
                text = when {
                    state.recording == PromptComposerViewModel.RecordingState.Recording &&
                        state.hasDetectedSpeech -> "CAPTURING"
                    state.recording == PromptComposerViewModel.RecordingState.Recording -> "LISTENING"
                    state.recording == PromptComposerViewModel.RecordingState.Transcribing -> "TRANSCRIBING"
                    else -> ""
                },
                color = PocketShellColors.Accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.testTag(COMPOSER_STATUS_TAG),
            )
            // Issue #174: small `X` discard chip rendered only while the
            // FSM is in Recording so the user can abort a dictation
            // without paying the Whisper round-trip. Hidden outside
            // Recording (no buffer to discard during Idle; the audio is
            // already in flight during Transcribing). The chip uses
            // `TextSecondary` (the design-system muted-secondary token
            // from #162) so it never competes for attention with the
            // accent-tinted mic FAB / waveform.
            //
            // Issue #208: the chip travels with the mic. The mic FAB now
            // anchors the right edge for right-thumb reach, so the
            // cancel chip renders immediately to its left — still inside
            // the same thumb arc, still adjacent to the affordance it
            // aborts.
            if (state.recording == PromptComposerViewModel.RecordingState.Recording) {
                CancelRecordingChip(onClick = onCancelRecording)
            }
            MicButton(
                state = when (state.recording) {
                    PromptComposerViewModel.RecordingState.Idle -> MicButtonState.Idle
                    PromptComposerViewModel.RecordingState.Recording -> MicButtonState.Recording
                    PromptComposerViewModel.RecordingState.Transcribing -> MicButtonState.Disabled
                },
                onClick = { if (!isTranscribing) onMicTap() },
            )
        }

        // Issue #185: silence-threshold hint. Rendered only while
        // RecordingState.Recording. PocketShellColors.TextMuted token.
        if (state.recording == PromptComposerViewModel.RecordingState.Recording &&
            state.silenceThresholdSeconds > 0f
        ) {
            Text(
                text = "Auto-stops after ${formatSilenceThresholdLabel(state.silenceThresholdSeconds)}s silence",
                color = PocketShellColors.TextMuted,
                fontSize = 11.sp,
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .testTag(COMPOSER_SILENCE_THRESHOLD_HINT_TAG),
            )
        }

        // Issue #211: Send remains tappable while the FSM is in
        // Recording or Transcribing — the ViewModel queues the send
        // and fires it after Whisper returns. Collapses the historic
        // three-tap "stop, wait, send" into a single tap.
        val isRecording = state.recording == PromptComposerViewModel.RecordingState.Recording
        val hasQueuedAffordance = isRecording || isTranscribing
        val sendEnabled = hasQueuedAffordance || state.draft.isNotEmpty()
        // Action row: Snippets (ghost) / Send / Send + Enter (primary).
        // Matches `.composer-actions` in the mockup. Snippets stays
        // disabled while a Whisper round-trip is in flight (browsing
        // snippets during transcription is at best a UX paper-cut and
        // at worst lands a stale snippet over the about-to-arrive
        // transcript) but the Send buttons stay live.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GhostButton(
                label = "Snippets",
                onClick = {
                    // Issue #17 wired the snippet picker. When the sheet
                    // is hosted from a context without a known host id
                    // (e.g. a non-session entry point), [onSnippets] is
                    // null and tapping the ghost button stays a no-op so
                    // the row's visual proportion still matches the mockup.
                    onSnippets?.invoke()
                },
                modifier = Modifier.weight(1f),
                enabled = !isTranscribing,
            )
            NeutralButton(
                // Issue #211: when a Send tap will queue behind the
                // Whisper round-trip, the label flips to "Send after
                // transcribe" so the user knows the tap was registered
                // but the bytes are not yet flying. The legacy Send
                // copy is preserved for the Idle case so nothing
                // changes for the common one-tap-with-typed-text path.
                label = if (hasQueuedAffordance) "Send after transcribe" else "Send",
                onClick = { onSend(false) },
                modifier = Modifier
                    .weight(1f)
                    .testTag(COMPOSER_SEND_TAG),
                enabled = sendEnabled,
            )
            PrimaryButton(
                label = if (hasQueuedAffordance) "Send + ↵ after" else "Send + ↵",
                onClick = { onSend(true) },
                modifier = Modifier
                    .weight(1f)
                    .testTag(COMPOSER_SEND_ENTER_TAG),
                enabled = sendEnabled,
            )
        }
    }
}

/**
 * 30-bar animated waveform. Matches `.waveform` + `.bar` in the mockup,
 * animated by the live amplitude from [PromptComposerViewModel.uiState].
 *
 * Bar heights are derived from the latest amplitude scaled by a fixed
 * sine envelope so the strip wiggles even at steady speech levels. When
 * [active] is false the bars collapse to a baseline 4dp.
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
    Row(
        modifier = modifier,
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
            val h = if (active) {
                (4f + smoothed * envelope).coerceIn(4f, envelope)
            } else {
                4f
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!item.atRetryCap) {
                PendingActionButton(
                    label = "Retry",
                    primary = true,
                    onClick = onRetry,
                    modifier = Modifier.testTag(composerPendingRetryTestTag(item.id)),
                )
                PendingActionButton(
                    label = "Discard",
                    primary = false,
                    onClick = onDiscard,
                    modifier = Modifier.testTag(composerPendingDiscardTestTag(item.id)),
                )
            } else {
                // 3-retry cap hit — Whisper has repeatedly failed for
                // this audio. Offer Save (so the user can hand it to a
                // different transcription tool) or Discard.
                PendingActionButton(
                    label = "Save audio",
                    primary = true,
                    onClick = onSaveAsAudio,
                    modifier = Modifier.testTag(composerPendingSaveTestTag(item.id)),
                )
                PendingActionButton(
                    label = "Discard",
                    primary = false,
                    onClick = onDiscard,
                    modifier = Modifier.testTag(composerPendingDiscardTestTag(item.id)),
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

/**
 * Issue #174: 32dp circular "discard recording" affordance rendered next
 * to the mic FAB while the composer is in `Recording`. Tapping it
 * dispatches [onClick] which the ViewModel maps to
 * [PromptComposerViewModel.cancelRecording]: the recorder is stopped,
 * the captured audio buffer is discarded (no Whisper call, no API cost),
 * and the FSM lands back on `Idle` with any existing typed draft
 * preserved.
 *
 * Visual recipe:
 *  - 32dp circular tap target sized to land between the 56dp mic FAB
 *    and the waveform without crowding either.
 *  - `SurfaceElev` fill with a 1dp `Border` stroke so the chip reads as
 *    a secondary affordance, not a primary action — design-system #162
 *    keeps cancel / dismiss surfaces visually subordinate to accent
 *    actions like the mic itself.
 *  - Glyph rendered as a centred `×` in `TextSecondary` (the muted
 *    secondary token), matching the close `×` rendered in the sheet
 *    header so the two "dismiss this thing" gestures look consistent.
 *
 * A11y: an explicit `contentDescription` so TalkBack reads "Cancel
 * recording" instead of falling back to the bare glyph.
 */
@Composable
private fun CancelRecordingChip(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(32.dp)
            .background(
                color = PocketShellColors.SurfaceElev,
                shape = androidx.compose.foundation.shape.CircleShape,
            )
            .border(
                width = 1.dp,
                color = PocketShellColors.Border,
                shape = androidx.compose.foundation.shape.CircleShape,
            )
            .clickable(
                role = androidx.compose.ui.semantics.Role.Button,
                onClick = onClick,
            )
            .testTag(COMPOSER_CANCEL_RECORDING_TAG)
            .semantics { contentDescription = "Cancel recording" },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "×",
            color = PocketShellColors.TextSecondary,
            fontSize = 18.sp,
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

@Composable
private fun NeutralButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .height(44.dp),
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = PocketShellColors.SurfaceElev,
            contentColor = PocketShellColors.Text,
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, PocketShellColors.Border),
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .height(44.dp),
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = PocketShellColors.Accent,
            contentColor = PocketShellColors.OnAccent,
        ),
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
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

/**
 * Placeholder hint surfaced in the empty text area. Lives on [UiState]
 * as an extension so it stays close to the rest of the rendering logic
 * (it changes per recording state — Idle vs Recording — to subtly
 * affirm the recording is active).
 */
private fun PromptComposerViewModel.UiState.placeholderHint(): String = when {
    recording == PromptComposerViewModel.RecordingState.Idle ->
        "Tap the mic to dictate, or type a prompt..."
    // Issue #195: pre-speech sub-state — mic is open, no amplitude has
    // crossed the speech threshold yet. The "speak when ready" copy is
    // load-bearing here: it tells the user the system is open but has
    // not heard them, which is exactly the user complaint that motivated
    // the issue (label persisted while user was already speaking).
    recording == PromptComposerViewModel.RecordingState.Recording && !hasDetectedSpeech ->
        "Listening — speak when ready"
    // Issue #195: active-speech sub-state — at least one amplitude sample
    // crossed [PromptComposerViewModel.SILENCE_AMPLITUDE_THRESHOLD] since
    // recording started, so the system is actively hearing the user.
    recording == PromptComposerViewModel.RecordingState.Recording ->
        "Capturing speech…"
    else -> "Transcribing..."
}

internal const val COMPOSER_DRAFT_TAG = "prompt-composer-draft"
internal const val COMPOSER_SEND_TAG = "prompt-composer-send"
internal const val COMPOSER_SEND_ENTER_TAG = "prompt-composer-send-enter"
internal const val COMPOSER_STATUS_TAG = "prompt-composer-status"
internal const val COMPOSER_WAVEFORM_TAG = "prompt-composer-waveform"

/**
 * Issue #185: test tag for the inline "auto-stops after Xs silence"
 * hint rendered below the mic row while the composer is in `Recording`.
 * The hint surfaces the current threshold from
 * [PromptComposerViewModel.UiState.silenceThresholdSeconds] so the user
 * can see how long a silence will trigger the watchdog auto-stop.
 */
internal const val COMPOSER_SILENCE_THRESHOLD_HINT_TAG = "prompt-composer-silence-hint"

/**
 * Issue #185: pretty-format the silence threshold for the composer's
 * inline hint. Drops the trailing `.0` for whole-second values so the
 * hint reads `Auto-stops after 5s silence` rather than `5.0s`; renders
 * one decimal otherwise (`2.5s`). Mirrors the same rounding rule used
 * by `SettingsScreen.formatThresholdLabel` so the Settings slider value
 * and the composer hint never disagree by a rounding artifact.
 */
internal fun formatSilenceThresholdLabel(seconds: Float): String {
    val rounded = kotlin.math.round(seconds * 10f) / 10f
    val asInt = rounded.toInt()
    return if (rounded == asInt.toFloat()) asInt.toString() else "%.1f".format(rounded)
}

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
 * Recording state — partial text in the area, mic pulsing, waveform
 * idle because no speech has been detected yet. Status reads
 * "LISTENING" and the empty-area placeholder reads "Listening — speak
 * when ready". Issue #195 sub-state: pre-speech.
 */
@Preview(name = "Composer · recording (listening, pre-speech)", widthDp = 412, heightDp = 360)
@Composable
private fun PromptComposerRecordingListeningPreview() {
    PocketShellTheme {
        Box(modifier = Modifier.background(PocketShellColors.Surface)) {
            SheetContent(
                state = PromptComposerViewModel.UiState(
                    draft = "",
                    recording = PromptComposerViewModel.RecordingState.Recording,
                    amplitude = 0f,
                    hasDetectedSpeech = false,
                    // Issue #185: show the inline "auto-stops after 5s
                    // silence" hint so the design surface reflects what
                    // the user sees while the mic is open.
                    silenceThresholdSeconds = 5f,
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
 * Recording state — issue #195 sub-state: active speech. At least one
 * amplitude sample crossed the speech-detection threshold, so the
 * label reads "CAPTURING" and the waveform animates by the live
 * amplitude. This is the screen the user sees while actually
 * dictating.
 */
@Preview(name = "Composer · recording (capturing)", widthDp = 412, heightDp = 360)
@Composable
private fun PromptComposerRecordingCapturingPreview() {
    PocketShellTheme {
        Box(modifier = Modifier.background(PocketShellColors.Surface)) {
            SheetContent(
                state = PromptComposerViewModel.UiState(
                    draft = "check the deploy log and tell me what failed",
                    recording = PromptComposerViewModel.RecordingState.Recording,
                    amplitude = 0.7f,
                    hasDetectedSpeech = true,
                    // Issue #185: hint stays visible across the
                    // listening -> capturing sub-state flip so the user
                    // keeps the auto-stop mental model while speaking.
                    silenceThresholdSeconds = 5f,
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
 * Transcription-ready state — the recording finished and the
 * appended text is visible. The mic FAB is in `Disabled` while
 * the network round-trip is in flight; this preview shows the
 * post-transcribe Idle state with the final text.
 */
@Preview(name = "Composer · transcription ready", widthDp = 412, heightDp = 360)
@Composable
private fun PromptComposerTranscribedPreview() {
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
