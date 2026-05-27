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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.pocketshell.app.snippets.SnippetPickerSheet
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
    val context = LocalContext.current

    var showApiKeyDialog by remember { mutableStateOf(false) }
    // Issue #17: tracks whether the Snippets bottom sheet is currently
    // open. Null host id means the caller doesn't yet have a persisted
    // host (Phase 0 / proof-of-life entry point) — in that case the
    // Snippets button stays inert.
    var showSnippetPicker by remember { mutableStateOf(false) }

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
                val text = state.draft
                if (text.isNotEmpty()) {
                    onSend(text, withEnter)
                    // Clear the draft after a successful send. Dismiss
                    // the sheet so the user lands back on the terminal
                    // with the bytes already flying.
                    viewModel.onDraftChange("")
                    onDismiss()
                }
            },
            onSnippets = if (hostId != null) {
                { showSnippetPicker = true }
            } else null,
        )
    }

    if (showSnippetPicker && hostId != null) {
        // Issue #17: opens the snippet picker over the composer sheet.
        // The chosen snippet's body is appended to the live draft so the
        // user can review / edit before tapping Send. We never auto-fire
        // the prompt — that would let a stray tap commit the wrong text.
        SnippetPickerSheet(
            hostId = hostId,
            onDismiss = { showSnippetPicker = false },
            onSnippetPicked = { snippet ->
                val current = viewModel.uiState.value.draft
                val separator = when {
                    current.isEmpty() -> ""
                    current.endsWith("\n") || current.endsWith(" ") -> ""
                    else -> " "
                }
                viewModel.onDraftChange(current + separator + snippet.body)
                showSnippetPicker = false
            },
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

        // Mic row: button on the left, waveform in the middle, label on
        // the right. Matches `.composer-mic-row` in the mockup.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            MicButton(
                state = when (state.recording) {
                    PromptComposerViewModel.RecordingState.Idle -> MicButtonState.Idle
                    PromptComposerViewModel.RecordingState.Recording -> MicButtonState.Recording
                    PromptComposerViewModel.RecordingState.Transcribing -> MicButtonState.Disabled
                },
                onClick = { if (!isTranscribing) onMicTap() },
            )
            // Issue #174: small `X` discard chip rendered only while the
            // FSM is in Recording so the user can abort a dictation
            // without paying the Whisper round-trip. Hidden outside
            // Recording (no buffer to discard during Idle; the audio is
            // already in flight during Transcribing). The chip uses
            // `TextSecondary` (the design-system muted-secondary token
            // from #162) so it never competes for attention with the
            // accent-tinted mic FAB / waveform.
            if (state.recording == PromptComposerViewModel.RecordingState.Recording) {
                CancelRecordingChip(onClick = onCancelRecording)
            }
            Waveform(
                amplitude = state.amplitude,
                active = state.recording == PromptComposerViewModel.RecordingState.Recording,
                modifier = Modifier
                    .weight(1f)
                    .height(32.dp)
                    .testTag(COMPOSER_WAVEFORM_TAG)
                    .semantics {
                        contentDescription = when (state.recording) {
                            PromptComposerViewModel.RecordingState.Recording ->
                                "Prompt composer recording waveform"
                            PromptComposerViewModel.RecordingState.Transcribing ->
                                "Prompt composer transcribing"
                            PromptComposerViewModel.RecordingState.Idle ->
                                "Prompt composer idle waveform"
                        }
                    },
            )
            Text(
                text = when (state.recording) {
                    PromptComposerViewModel.RecordingState.Recording -> "LISTENING"
                    PromptComposerViewModel.RecordingState.Transcribing -> "TRANSCRIBING"
                    PromptComposerViewModel.RecordingState.Idle -> ""
                },
                color = PocketShellColors.Accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.testTag(COMPOSER_STATUS_TAG),
            )
        }

        // Action row: Snippets (ghost) / Send / Send + Enter (primary).
        // Matches `.composer-actions` in the mockup.
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
                label = "Send",
                onClick = { onSend(false) },
                modifier = Modifier
                    .weight(1f)
                    .testTag(COMPOSER_SEND_TAG),
                enabled = !isTranscribing && state.draft.isNotEmpty(),
            )
            PrimaryButton(
                label = "Send + ↵",
                onClick = { onSend(true) },
                modifier = Modifier
                    .weight(1f)
                    .testTag(COMPOSER_SEND_ENTER_TAG),
                enabled = !isTranscribing && state.draft.isNotEmpty(),
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
private fun PromptComposerViewModel.UiState.placeholderHint(): String = when (recording) {
    PromptComposerViewModel.RecordingState.Idle -> "Tap the mic to dictate, or type a prompt..."
    PromptComposerViewModel.RecordingState.Recording -> "Listening — speak when ready"
    PromptComposerViewModel.RecordingState.Transcribing -> "Transcribing..."
}

internal const val COMPOSER_DRAFT_TAG = "prompt-composer-draft"
internal const val COMPOSER_SEND_TAG = "prompt-composer-send"
internal const val COMPOSER_SEND_ENTER_TAG = "prompt-composer-send-enter"
internal const val COMPOSER_STATUS_TAG = "prompt-composer-status"
internal const val COMPOSER_WAVEFORM_TAG = "prompt-composer-waveform"

/**
 * Issue #174: test tag for the cancel-recording chip rendered next to
 * the mic FAB while the composer is in `Recording`. Connected tests use
 * this tag to locate the affordance, tap it, and assert the resulting
 * FSM transitions back to `Idle` without a Whisper call.
 */
internal const val COMPOSER_CANCEL_RECORDING_TAG = "prompt-composer-cancel-recording"

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
 * Recording state — partial text in the area, mic pulsing,
 * waveform animated by a steady amplitude. Closest match to the
 * mockup's `.composer-text` + `Listening` strip.
 */
@Preview(name = "Composer · recording", widthDp = 412, heightDp = 360)
@Composable
private fun PromptComposerRecordingPreview() {
    PocketShellTheme {
        Box(modifier = Modifier.background(PocketShellColors.Surface)) {
            SheetContent(
                state = PromptComposerViewModel.UiState(
                    draft = "check the deploy log and tell me what failed",
                    recording = PromptComposerViewModel.RecordingState.Recording,
                    amplitude = 0.7f,
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
