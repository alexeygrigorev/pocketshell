package com.pocketshell.next.composer

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketshell.next.files.MarkdownParser
import com.pocketshell.next.files.MarkdownView
import com.pocketshell.uikit.components.Banner
import com.pocketshell.uikit.components.BannerRole
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.MicButton
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.model.MicButtonState
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellSpacing

/** Stable test tags for the composer surface. */
const val COMPOSER_TAG: String = "composer"
const val COMPOSER_DRAFT_TAG: String = "composer-draft"
const val COMPOSER_SEND_TAG: String = "composer-send"
const val COMPOSER_INSERT_TAG: String = "composer-insert"
const val COMPOSER_ATTACH_TAG: String = "composer-attach"
const val COMPOSER_MIC_TAG: String = "composer-mic"
const val COMPOSER_DISCARD_RECORDING_TAG: String = "composer-discard-recording"
const val COMPOSER_HISTORY_TAG: String = "composer-history"
const val COMPOSER_PREVIEW_TAG: String = "composer-preview"
const val COMPOSER_PREVIEW_VIEW_TAG: String = "composer-preview-view"
const val COMPOSER_DISCARD_TAG: String = "composer-discard"
const val COMPOSER_UNDELIVERED_TAG: String = "composer-undelivered"
const val COMPOSER_NOTICE_TAG: String = "composer-notice"
const val COMPOSER_STAGING_TAG: String = "composer-staging"
const val COMPOSER_SLASH_TAG: String = "composer-slash"
const val COMPOSER_SLASH_TRIGGER_TAG: String = "composer-slash-trigger"
const val COMPOSER_TIMER_TAG: String = "composer-timer"
const val COMPOSER_WAVEFORM_TAG: String = "composer-waveform"
const val COMPOSER_CONTROLS_ROW_TAG: String = "composer-controls-row"

fun composerSlashRowTag(command: String): String = "composer-slash-row:$command"

/** The text a send that never left the device puts on screen. */
const val COMPOSER_UNDELIVERED_TEXT: String = "Not delivered — session offline. Your draft was kept."

/** Shown when RECORD_AUDIO is denied; dictation is not started. */
const val COMPOSER_RECORD_AUDIO_DENIED_TEXT: String =
    "Microphone permission denied. You can still type."

internal const val COMPOSER_PLACEHOLDER: String = "Compose a message…"

/**
 * The Prompt Composer body (rewrite task P-1, restored as a sheet in #2521).
 *
 * Hosted inside [PromptComposerSheet], not inline in the session column. The
 * session column's compact launcher opens the sheet; this composable is the
 * draft, mic, Insert, and Send once it is open.
 *
 * ## Stateless
 *
 * Everything rendered is a function of [state], so a Robolectric composition
 * and the device journey see the same pixels. The only local state is the
 * [TextFieldValue] — the editor has to own the caret and the IME's composing
 * region (an old, expensive lesson: a `String`-backed field leaves
 * predictive-text in an uncommitted composing region the composer never sees,
 * so Send reads an empty draft and does nothing).
 */
@Composable
fun ComposerBar(
    state: ComposerUiState,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onInsert: () -> Unit,
    onAttach: () -> Unit,
    onMicTap: () -> Unit,
    onCancelRecording: () -> Unit,
    onToggleHistory: () -> Unit,
    onTogglePreview: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onDismissNotice: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var field by remember { mutableStateOf(TextFieldValue(state.draft, TextRange(state.draft.length))) }
    // Re-seed only when the ViewModel's draft CHANGES to something the editor
    // did not produce: a send clearing it, a history tap replacing it,
    // dictation rewriting it. Keyed on `state.draft`, so a keystroke (which
    // makes the two agree) is a no-op and the caret is never disturbed.
    //
    // In a LaunchedEffect rather than inline: reseeding inline means writing
    // state during composition on every keystroke, which self-invalidates the
    // composition and can leave `waitForIdle` chasing a frame that never
    // settles — a hazard that costs a device journey a 60-second timeout with
    // no useful message.
    LaunchedEffect(state.draft) {
        if (state.draft != field.text) {
            field = TextFieldValue(state.draft, TextRange(state.draft.length))
        }
    }

    val slashQuery = SlashCommandAutocomplete.queryFor(field)
    val slashRows = slashQuery?.let { SlashCommandAutocomplete.filter(it) }.orEmpty()

    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val commitSend: () -> Unit = {
        commitComposerSend(
            flushDraft = { onDraftChange(field.text) },
            clearFocus = { focusManager.clearFocus(force = true) },
            hideKeyboard = { keyboardController?.hide() },
            dispatch = onSend,
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(PocketShellColors.Surface)
            .border(width = 1.dp, color = PocketShellColors.BorderSoft)
            .padding(horizontal = PocketShellSpacing.md, vertical = PocketShellSpacing.sm)
            .testTag(COMPOSER_TAG),
        verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm),
    ) {
        NoticeRow(state.notice, onDismissNotice)

        state.staging?.let { progress ->
            Text(
                text = "Uploading ${progress.index} of ${progress.count} · ${progress.name}",
                style = MaterialTheme.typography.labelMedium,
                color = PocketShellColors.TextSecondary,
                maxLines = 1,
                modifier = Modifier.testTag(COMPOSER_STAGING_TAG),
            )
        }

        if (state.attachments.isNotEmpty()) {
            AttachmentTiles(attachments = state.attachments, onRemove = onRemoveAttachment)
        }

        if (slashRows.isNotEmpty()) {
            SlashCommandDropdown(
                commands = slashRows,
                onPick = { command ->
                    val inserted = SlashCommandAutocomplete.insert(field, command)
                    field = inserted
                    onDraftChange(inserted.text)
                },
            )
        }

        when (state.recording) {
            RecordingState.Recording -> RecordingSurface(
                elapsedLabel = recordingElapsedLabel(),
                amplitude = 0.45f,
                capturing = true,
                liveTranscript = state.draft.takeIf { it.isNotBlank() },
            )
            RecordingState.Transcribing -> TranscribingSurface()
            RecordingState.Idle -> if (state.previewing) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = DRAFT_MIN_HEIGHT, max = DRAFT_MAX_HEIGHT)
                        .verticalScroll(rememberScrollState())
                        .background(color = PocketShellColors.SurfaceElev, shape = DRAFT_SHAPE)
                        .border(width = 1.dp, color = PocketShellColors.Border, shape = DRAFT_SHAPE)
                        .testTag(COMPOSER_PREVIEW_VIEW_TAG),
                ) {
                    MarkdownView(blocks = MarkdownParser.parse(state.draft))
                }
            } else {
                DraftField(
                    value = field,
                    onValueChange = { updated ->
                        field = updated
                        onDraftChange(updated.text)
                    },
                )
            }
        }

        ControlsRow(
            state = state,
            onSend = commitSend,
            onInsert = onInsert,
            onAttach = onAttach,
            onMicTap = onMicTap,
            onCancelRecording = onCancelRecording,
            onToggleHistory = onToggleHistory,
            onSlashTap = {
                val seeded = SlashCommandAutocomplete.insertText(field, "/")
                field = seeded
                onDraftChange(seeded.text)
            },
        )
    }
}

@Composable
private fun NoticeRow(notice: ComposerNotice?, onDismiss: () -> Unit) {
    when (notice) {
        null -> Unit

        ComposerNotice.Undelivered -> Banner(
            text = COMPOSER_UNDELIVERED_TEXT,
            role = BannerRole.Error,
            maxLines = 3,
            trailingContent = {
                PocketShellButton(
                    text = "Dismiss",
                    onClick = onDismiss,
                    variant = ButtonVariant.Text,
                    compact = true,
                )
            },
            modifier = Modifier.testTag(COMPOSER_UNDELIVERED_TAG),
        )

        is ComposerNotice.Problem -> Banner(
            text = notice.message,
            role = BannerRole.Error,
            maxLines = 3,
            trailingContent = {
                PocketShellButton(
                    text = "Dismiss",
                    onClick = onDismiss,
                    variant = ButtonVariant.Text,
                    compact = true,
                )
            },
            modifier = Modifier.testTag(COMPOSER_NOTICE_TAG),
        )

        is ComposerNotice.Info -> Banner(
            text = notice.message,
            role = BannerRole.Info,
            maxLines = 2,
            trailingContent = {
                PocketShellButton(
                    text = "Dismiss",
                    onClick = onDismiss,
                    variant = ButtonVariant.Text,
                    compact = true,
                )
            },
            modifier = Modifier.testTag(COMPOSER_NOTICE_TAG),
        )
    }
}

/**
 * The draft editor.
 *
 * `heightIn` is on the EDITOR, not the surrounding box, so a one-line draft
 * wraps to one line instead of inflating toward the maximum and centring the
 * text in a void — and a long draft self-scrolls to the caret, which a bounded
 * `BasicTextField` does natively and an external `verticalScroll` would break.
 */
@Composable
private fun DraftField(value: TextFieldValue, onValueChange: (TextFieldValue) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = PocketShellColors.SurfaceElev, shape = DRAFT_SHAPE)
            .border(width = 1.dp, color = PocketShellColors.Border, shape = DRAFT_SHAPE)
            .padding(horizontal = PocketShellSpacing.md, vertical = PocketShellSpacing.sm),
        contentAlignment = Alignment.TopStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = TextStyle(color = PocketShellColors.Text, fontSize = ComposerDraftFontSize),
            cursorBrush = SolidColor(PocketShellColors.Accent),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = DRAFT_MIN_HEIGHT, max = DRAFT_MAX_HEIGHT)
                .testTag(COMPOSER_DRAFT_TAG),
            decorationBox = { inner ->
                if (value.text.isEmpty()) {
                    Text(
                        text = COMPOSER_PLACEHOLDER,
                        color = PocketShellColors.TextMuted,
                        fontSize = ComposerDraftFontSize,
                    )
                }
                inner()
            },
        )
    }
}

@Composable
private fun ControlsRow(
    state: ComposerUiState,
    onSend: () -> Unit,
    onInsert: () -> Unit,
    onAttach: () -> Unit,
    onMicTap: () -> Unit,
    onCancelRecording: () -> Unit,
    onToggleHistory: () -> Unit,
    onSlashTap: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(COMPOSER_CONTROLS_ROW_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.recording == RecordingState.Idle) {
            ComposerEditingToolsGroup(
                enabled = !state.busy,
                onAttach = onAttach,
                onHistory = onToggleHistory,
                onSlashTap = onSlashTap,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        when (state.recording) {
            RecordingState.Idle -> {
                InsertButton(
                    onClick = onInsert,
                    enabled = state.canSend && !state.busy,
                    modifier = Modifier.testTag(COMPOSER_INSERT_TAG),
                )
                SendButton(
                    onClick = onSend,
                    enabled = state.canSend && !state.busy,
                    modifier = Modifier.testTag(COMPOSER_SEND_TAG),
                )
                MicTriggerButton(
                    onClick = onMicTap,
                    enabled = state.micAvailable,
                    modifier = Modifier.testTag(COMPOSER_MIC_TAG),
                )
            }
            RecordingState.Recording -> {
                DiscardRecordingButton(
                    onClick = onCancelRecording,
                    modifier = Modifier.testTag(COMPOSER_DISCARD_RECORDING_TAG),
                )
                InsertButton(
                    onClick = onInsert,
                    enabled = state.canSend && !state.busy,
                    recording = true,
                    modifier = Modifier.testTag(COMPOSER_INSERT_TAG),
                )
                SendButton(
                    onClick = onSend,
                    enabled = state.canSend && !state.busy,
                    recording = true,
                    modifier = Modifier.testTag(COMPOSER_SEND_TAG),
                )
            }
            RecordingState.Transcribing -> {
                DiscardRecordingButton(
                    onClick = onCancelRecording,
                    label = "Cancel",
                    modifier = Modifier.testTag(COMPOSER_DISCARD_RECORDING_TAG),
                )
                SendButton(
                    onClick = onSend,
                    enabled = state.canSend && !state.busy,
                    recording = true,
                    modifier = Modifier.testTag(COMPOSER_SEND_TAG),
                )
            }
        }
    }
}

/**
 * v0.4.47 left tools pill: 📎 attach, `{}` history, `/` slash (#701 / #787 / #2529).
 */
@Composable
private fun ComposerEditingToolsGroup(
    enabled: Boolean,
    onAttach: () -> Unit,
    onHistory: () -> Unit,
    onSlashTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(ComposerActionPillShape)
            .background(PocketShellColors.SurfaceElev, ComposerActionPillShape)
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ToolGlyphButton(
            glyph = "📎",
            contentDescription = "Attach files",
            onClick = onAttach,
            enabled = enabled,
            modifier = Modifier.testTag(COMPOSER_ATTACH_TAG),
        )
        ToolGlyphButton(
            glyph = "{}",
            contentDescription = "Message history",
            onClick = onHistory,
            enabled = enabled,
            modifier = Modifier.testTag(COMPOSER_HISTORY_TAG),
        )
        ToolGlyphButton(
            glyph = "/",
            contentDescription = "Slash commands",
            onClick = onSlashTap,
            enabled = enabled,
            modifier = Modifier.testTag(COMPOSER_SLASH_TRIGGER_TAG),
        )
    }
}

@Composable
private fun ToolGlyphButton(
    glyph: String,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(COMPOSER_ACTION_ICON_BUTTON_SIZE)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            color = if (enabled) PocketShellColors.TextSecondary else PocketShellColors.TextMuted,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SendButton(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    recording: Boolean = false,
) {
    val height = if (recording) ComposerRecordingPillHeight else ComposerIdlePillHeight
    val containerColor = if (enabled) PocketShellColors.Accent else PocketShellColors.SurfaceElev
    val contentColor = if (enabled) PocketShellColors.OnAccent else PocketShellColors.TextMuted
    Row(
        modifier = modifier
            .height(height)
            .clip(ComposerActionPillShape)
            .background(color = containerColor, shape = ComposerActionPillShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (recording) 16.dp else 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(
            text = "Send",
            color = contentColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(text = "➤", color = contentColor, fontSize = 13.sp)
    }
}

@Composable
private fun InsertButton(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    recording: Boolean = false,
) {
    val height = if (recording) ComposerRecordingPillHeight else ComposerIdlePillHeight
    Row(
        modifier = modifier
            .height(height)
            .clip(ComposerActionPillShape)
            .background(PocketShellColors.SurfaceElev, ComposerActionPillShape)
            .border(1.dp, PocketShellColors.Border, ComposerActionPillShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Insert without submitting" }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Insert",
            color = if (enabled) PocketShellColors.Text else PocketShellColors.TextMuted,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun DiscardRecordingButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Discard",
) {
    Row(
        modifier = modifier
            .height(ComposerRecordingPillHeight)
            .clip(ComposerActionPillShape)
            .background(PocketShellColors.SurfaceElev, ComposerActionPillShape)
            .border(1.dp, PocketShellColors.Border, ComposerActionPillShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Discard recording without transcribing" }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = PocketShellColors.TextSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun MicTriggerButton(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    MicButton(
        state = if (enabled) MicButtonState.Idle else MicButtonState.Disabled,
        onClick = onClick,
        modifier = modifier.size(ComposerIdlePillHeight),
    )
}

/** v0.4.47 tools-pill radius — between medium (14) and large (20) is too small; 22 matches the old chrome. */
private val ComposerActionPillRadius = 22.dp
private val ComposerActionPillShape = RoundedCornerShape(ComposerActionPillRadius)
/** Draft sits between bodyMedium (14) and titleMedium (16); the old field used 15. */
private val ComposerDraftFontSize = 15.sp
private val ComposerIdlePillHeight = 44.dp
private val ComposerRecordingPillHeight = 48.dp
private val COMPOSER_ACTION_ICON_BUTTON_SIZE = 40.dp

/**
 * The `/`-command list, rendered above the field so it never sits under the
 * keyboard. See [SlashCommandAutocomplete] for when it is open.
 */
@Composable
private fun SlashCommandDropdown(commands: List<SlashCommand>, onPick: (SlashCommand) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = SLASH_MAX_HEIGHT)
            .verticalScroll(rememberScrollState())
            .background(color = PocketShellColors.SurfaceElev, shape = DRAFT_SHAPE)
            .border(width = 1.dp, color = PocketShellColors.Border, shape = DRAFT_SHAPE)
            .testTag(COMPOSER_SLASH_TAG),
    ) {
        commands.forEach { command ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(composerSlashRowTag(command.command)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PocketShellButton(
                    onClick = { onPick(command) },
                    variant = ButtonVariant.Text,
                    compact = true,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = command.command,
                        color = PocketShellColors.Accent,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.width(PocketShellSpacing.sm))
                    Text(
                        text = command.description,
                        color = PocketShellColors.TextSecondary,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private val DRAFT_SHAPE = RoundedCornerShape(PocketShellSpacing.md)
private val DRAFT_MIN_HEIGHT = 40.dp
private val DRAFT_MAX_HEIGHT = 168.dp
private val SLASH_MAX_HEIGHT = 168.dp
