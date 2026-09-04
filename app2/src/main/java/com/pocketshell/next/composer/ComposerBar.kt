package com.pocketshell.next.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
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

fun composerSlashRowTag(command: String): String = "composer-slash-row:$command"

/** The text a send that never left the device puts on screen. */
const val COMPOSER_UNDELIVERED_TEXT: String = "Not delivered — session offline. Your draft was kept."

internal const val COMPOSER_PLACEHOLDER: String = "Compose a message…"

/**
 * The composer, inline in the session screen's bottom chrome (rewrite task
 * P-1).
 *
 * ## Why inline rather than the old modal bottom sheet
 *
 * The old composer was a `ModalBottomSheet`, and two of its ported support
 * files (`ComposerSheetChrome`, `PromptComposerImeAnchorPolicy`) existed only
 * to fight Material's sheet anchors: a policy that measured the sheet Surface
 * against the IME boundary and re-anchored it, plus a hand-rolled drag handle
 * that clawed back the ~45dp of dead top inset Material charged a floating
 * sheet. Both are chrome-shaped bug fixes for a container this composer does
 * not use.
 *
 * An inline bar sits above the keyboard by construction — the screen carries
 * `imePadding()` and the terminal above it resizes — so the whole class of
 * anchor/dead-band defect the maintainer reported against the sheet cannot
 * occur, and 289 lines of correction machinery do not need to be maintained to
 * prevent it. This is the rewrite's premise applied to the composer: delete the
 * shim by removing the thing it was shimming.
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

        if (state.previewing) {
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

        ControlsRow(
            state = state,
            onSend = onSend,
            onAttach = onAttach,
            onMicTap = onMicTap,
            onCancelRecording = onCancelRecording,
            onToggleHistory = onToggleHistory,
            onTogglePreview = onTogglePreview,
            onDiscard = onDiscard,
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
            textStyle = TextStyle(color = PocketShellColors.Text, fontSize = 15.sp),
            cursorBrush = SolidColor(PocketShellColors.Accent),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = DRAFT_MIN_HEIGHT, max = DRAFT_MAX_HEIGHT)
                .testTag(COMPOSER_DRAFT_TAG),
            decorationBox = { inner ->
                if (value.text.isEmpty()) {
                    Text(text = COMPOSER_PLACEHOLDER, color = PocketShellColors.TextMuted, fontSize = 15.sp)
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
    onAttach: () -> Unit,
    onMicTap: () -> Unit,
    onCancelRecording: () -> Unit,
    onToggleHistory: () -> Unit,
    onTogglePreview: () -> Unit,
    onDiscard: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PocketShellSpacing.xs),
    ) {
        if (state.recording == RecordingState.Recording) {
            // Mid-dictation the editing tools are hidden: attach, history and
            // preview are text-composition tools, not usable while the mic is
            // live, and hiding them puts Discard next to the mic where the user
            // is already looking.
            PocketShellButton(
                text = "Discard",
                onClick = onCancelRecording,
                variant = ButtonVariant.Secondary,
                compact = true,
                modifier = Modifier.testTag(COMPOSER_DISCARD_RECORDING_TAG),
            )
            Text(
                text = "Listening…",
                style = MaterialTheme.typography.labelMedium,
                color = PocketShellColors.Accent,
            )
        } else {
            PocketShellButton(
                text = "📎",
                onClick = onAttach,
                variant = ButtonVariant.Text,
                compact = true,
                enabled = !state.busy,
                modifier = Modifier.testTag(COMPOSER_ATTACH_TAG),
            )
            // A word, not a glyph: the emulator screenshot showed the clock
            // pictograph rendering as a near-invisible hairline outline, which
            // is not an affordance anybody would find.
            PocketShellButton(
                text = "Recent",
                onClick = onToggleHistory,
                variant = ButtonVariant.Text,
                compact = true,
                modifier = Modifier.testTag(COMPOSER_HISTORY_TAG),
            )
            PocketShellButton(
                text = if (state.previewing) "Edit" else "Preview",
                onClick = onTogglePreview,
                variant = ButtonVariant.Text,
                compact = true,
                enabled = state.draft.isNotBlank(),
                modifier = Modifier.testTag(COMPOSER_PREVIEW_TAG),
            )
            if (state.canSend) {
                PocketShellButton(
                    text = "Clear",
                    onClick = onDiscard,
                    variant = ButtonVariant.Text,
                    compact = true,
                    modifier = Modifier.testTag(COMPOSER_DISCARD_TAG),
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        if (state.recording != RecordingState.Recording) {
            PocketShellButton(
                text = "Send",
                onClick = onSend,
                enabled = state.canSend && !state.busy,
                compact = true,
                modifier = Modifier.testTag(COMPOSER_SEND_TAG),
            )
            Spacer(modifier = Modifier.width(PocketShellSpacing.xs))
        }

        MicButton(
            state = when {
                state.recording == RecordingState.Recording -> MicButtonState.Recording
                state.micAvailable && state.recording == RecordingState.Idle -> MicButtonState.Idle
                else -> MicButtonState.Disabled
            },
            onClick = onMicTap,
            // No size override: `MicButton` pins its own 56dp disc after the
            // caller's modifier, so anything set here would be a silent no-op.
            modifier = Modifier.testTag(COMPOSER_MIC_TAG),
        )
    }
}

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
