package com.pocketshell.next.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
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
import com.pocketshell.uikit.components.COMPOSER_ATTACH_TAG
import com.pocketshell.uikit.components.COMPOSER_MIC_TAG
import com.pocketshell.uikit.components.COMPOSER_PASTE_LABEL
import com.pocketshell.uikit.components.COMPOSER_SEND_TAG
import com.pocketshell.uikit.components.COMPOSER_TOOLS_TRIGGER_TAG
import com.pocketshell.uikit.components.ComposerIdleControls
import com.pocketshell.uikit.components.MicButton
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.SheetHeader
import com.pocketshell.uikit.icons.PocketShellIcons
import com.pocketshell.uikit.model.MicButtonState
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellSpacing

/** Stable test tags for the composer surface. */
const val COMPOSER_TAG: String = "composer"
const val COMPOSER_DRAFT_TAG: String = "composer-draft"
const val COMPOSER_INSERT_TAG: String = "composer-insert"

/**
 * The one name for paste-without-Enter (#2635 C3).
 *
 * It is the long-press label on Send, the accessibility action on Send, and the
 * title of the row in the "+" tools sheet — one verb, one word, three places
 * that must not drift apart.
 */

const val COMPOSER_DISCARD_RECORDING_TAG: String = "composer-discard-recording"
const val COMPOSER_STOP_RECORDING_TAG: String = "composer-stop-recording"
const val COMPOSER_HISTORY_TAG: String = "composer-history"
const val COMPOSER_PREVIEW_TAG: String = "composer-preview"
const val COMPOSER_PREVIEW_VIEW_TAG: String = "composer-preview-view"
const val COMPOSER_DISCARD_TAG: String = "composer-discard"
const val COMPOSER_UNDELIVERED_TAG: String = "composer-undelivered"
const val COMPOSER_DELIVERY_UNCERTAIN_TAG: String = "composer-delivery-uncertain"
const val COMPOSER_SESSION_ENDED_TAG: String = "composer-session-ended"
const val COMPOSER_NOTICE_TAG: String = "composer-notice"
const val COMPOSER_STAGING_TAG: String = "composer-staging"
const val COMPOSER_SLASH_TAG: String = "composer-slash-sheet"
const val COMPOSER_SLASH_TRIGGER_TAG: String = "composer-slash-trigger"
const val COMPOSER_TIMER_TAG: String = "composer-timer"
const val COMPOSER_WAVEFORM_TAG: String = "composer-waveform"
const val COMPOSER_TRANSCRIBING_TAG: String = "composer-transcribing"
const val COMPOSER_CONTROLS_ROW_TAG: String = "composer-controls-row"
const val COMPOSER_TOOLS_TAG: String = "composer-tools"
const val COMPOSER_REVIEW_TAG: String = "composer-delivery-review"
const val COMPOSER_REVIEW_DRAFT_TAG: String = "composer-delivery-review-draft"
const val COMPOSER_REVIEW_ACTION_TAG: String = "composer-delivery-review-action"
const val COMPOSER_DELIVERY_REVIEW_TITLE: String = "Delivery could not be confirmed"
const val COMPOSER_DELIVERY_REVIEW_TEXT: String =
    "The connection dropped while input was being sent. It may have reached the terminal. Your draft was kept."

fun composerSlashRowTag(command: String): String = "composer-slash-row:$command"

/** The text a send that never left the device puts on screen. */
const val COMPOSER_UNDELIVERED_TEXT: String = "Not delivered — session offline. Your draft was kept."
const val COMPOSER_DELIVERY_UNCERTAIN_TEXT: String =
    "Delivery uncertain — the session connection dropped. Review before resending."

/**
 * The Stop control's accessible name (#2598).
 *
 * Spelled out because "stop" alone reads like Discard's twin: this is the one
 * that KEEPS what was heard and hands it back as an editable draft.
 */
const val COMPOSER_STOP_RECORDING_DESCRIPTION: String = "Stop dictating and keep the text"

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
@OptIn(ExperimentalMaterial3Api::class)
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
    deliveryEnabled: Boolean = true,
    deliveryDisabledMessage: String? = null,
    availableSlashCommands: List<SlashCommand> = SlashCommandAutocomplete.CATALOG,
) {
    var field by remember { mutableStateOf(TextFieldValue(state.draft, TextRange(state.draft.length))) }
    var toolsOpen by remember { mutableStateOf(false) }
    var slashSheetOpen by remember { mutableStateOf(false) }
    var slashDraftBeforeSheet by remember { mutableStateOf<TextFieldValue?>(null) }
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
        deliveryDisabledMessage?.let { message ->
            Banner(
                text = message,
                role = BannerRole.Warning,
                maxLines = 3,
                modifier = Modifier.testTag(COMPOSER_SESSION_ENDED_TAG),
            )
        }
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

        // BELOW the draft field, between the editor and the controls — where
        // the pre-0.5.0 composer put them (`Issue2057AttachmentTilesBelowDraftProofTest`)
        // and where #2630's maintainer review asked for them back. Above the
        // field they pushed the editor down the moment anything was staged.
        if (state.attachments.isNotEmpty()) {
            AttachmentTiles(attachments = state.attachments, onRemove = onRemoveAttachment)
        }

        ControlsRow(
            state = state,
            onSend = commitSend,
            deliveryEnabled = deliveryEnabled,
            onInsert = onInsert,
            onAttach = onAttach,
            onOpenTools = { toolsOpen = !toolsOpen },
            onMicTap = onMicTap,
            onCancelRecording = onCancelRecording,
        )
    }

    if (toolsOpen && state.recording == RecordingState.Idle) {
        ModalBottomSheet(
            onDismissRequest = { toolsOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = PocketShellColors.Surface,
            shape = PocketShellShapes.large,
        ) {
            ComposerToolsPanel(
                state = state,
                slashCommandsAvailable = availableSlashCommands.isNotEmpty(),
                onDismiss = { toolsOpen = false },
                onHistory = {
                    toolsOpen = false
                    onToggleHistory()
                },
                onSlash = {
                    toolsOpen = false
                    slashDraftBeforeSheet = field
                    val seeded = SlashCommandAutocomplete.insertText(field, "/")
                    field = seeded
                    onDraftChange(seeded.text)
                    slashSheetOpen = true
                },
                onInsert = {
                    toolsOpen = false
                    onInsert()
                },
                onClear = {
                    toolsOpen = false
                    onDiscard()
                },
            )
        }
    }

    if (slashSheetOpen && availableSlashCommands.isNotEmpty()) {
        SlashCommandSheet(
            commands = availableSlashCommands,
            onPick = { command ->
                val inserted = SlashCommandAutocomplete.insert(field, command)
                field = inserted
                slashDraftBeforeSheet = null
                onDraftChange(inserted.text)
                slashSheetOpen = false
            },
            onDismiss = {
                slashDraftBeforeSheet?.let { original ->
                    field = original
                    onDraftChange(original.text)
                }
                slashDraftBeforeSheet = null
                slashSheetOpen = false
            },
        )
    }
}

/**
 * The page shown when a PTY write completed without a trustworthy delivery
 * result. It keeps the draft locally editable, but makes reconnect-and-inspect
 * the only primary action so a user cannot accidentally execute the same input
 * twice after a connection drop.
 */
@Composable
fun DeliveryUncertainReview(
    draft: String,
    onDraftChange: (String) -> Unit,
    onReconnectAndInspect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var field by remember(draft) {
        mutableStateOf(TextFieldValue(draft, TextRange(draft.length)))
    }

    LaunchedEffect(draft) {
        if (draft != field.text) {
            field = TextFieldValue(draft, TextRange(draft.length))
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PocketShellSpacing.lg, vertical = PocketShellSpacing.md)
            .testTag(COMPOSER_REVIEW_TAG),
        verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.md),
    ) {
        Banner(
            text = "$COMPOSER_DELIVERY_REVIEW_TITLE\n$COMPOSER_DELIVERY_REVIEW_TEXT",
            role = BannerRole.Warning,
            maxLines = 5,
        )
        Text(
            text = "Draft",
            color = PocketShellColors.Text,
            style = MaterialTheme.typography.titleMedium,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(PocketShellColors.SurfaceElev, DRAFT_SHAPE)
                .border(1.dp, PocketShellColors.Border, DRAFT_SHAPE)
                .padding(horizontal = PocketShellSpacing.md, vertical = PocketShellSpacing.sm),
        ) {
            BasicTextField(
                value = field,
                onValueChange = { updated ->
                    field = updated
                    onDraftChange(updated.text)
                },
                textStyle = TextStyle(color = PocketShellColors.Text, fontSize = ComposerDraftFontSize),
                cursorBrush = SolidColor(PocketShellColors.Accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = DRAFT_MIN_HEIGHT, max = DRAFT_MAX_HEIGHT)
                    .testTag(COMPOSER_REVIEW_DRAFT_TAG),
            )
        }
        Text(
            text = "Reconnect and inspect the terminal before sending again.",
            color = PocketShellColors.TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )
        PocketShellButton(
            text = "Reconnect and inspect",
            onClick = onReconnectAndInspect,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(COMPOSER_REVIEW_ACTION_TAG),
            variant = ButtonVariant.Primary,
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

        ComposerNotice.DeliveryUncertain -> Banner(
            text = COMPOSER_DELIVERY_UNCERTAIN_TEXT,
            role = BannerRole.Warning,
            maxLines = 3,
            trailingContent = {
                PocketShellButton(
                    text = "Dismiss",
                    onClick = onDismiss,
                    variant = ButtonVariant.Text,
                    compact = true,
                )
            },
            modifier = Modifier.testTag(COMPOSER_DELIVERY_UNCERTAIN_TAG),
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
    deliveryEnabled: Boolean,
    onInsert: () -> Unit,
    onAttach: () -> Unit,
    onOpenTools: () -> Unit,
    onMicTap: () -> Unit,
    onCancelRecording: () -> Unit,
) {
    // #2635: the IDLE row is the shared ui-kit component, not a local copy.
    // The recording row stays here — it is a different, transient mode with its
    // own controls (discard / paste / send / stop), and the kit has no reason
    // to know about `RecordingState`.
    if (state.recording == RecordingState.Idle) {
        ComposerIdleControls(
            onAttach = onAttach,
            onOpenTools = onOpenTools,
            onSend = onSend,
            onPaste = onInsert,
            onMicTap = onMicTap,
            attachEnabled = !state.busy,
            toolsEnabled = !state.busy,
            sendEnabled = deliveryEnabled && state.canSend && !state.busy,
            micEnabled = state.micAvailable,
            modifier = Modifier.testTag(COMPOSER_CONTROLS_ROW_TAG),
        )
        return
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(COMPOSER_CONTROLS_ROW_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Spacer(modifier = Modifier.weight(1f))
        when (state.recording) {
            // Handled above by the shared ui-kit control row.
            RecordingState.Idle -> Unit
            RecordingState.Recording -> {
                DiscardRecordingButton(
                    onClick = onCancelRecording,
                    modifier = Modifier.testTag(COMPOSER_DISCARD_RECORDING_TAG),
                )
                InsertButton(
                    onClick = onInsert,
                    enabled = deliveryEnabled && state.canSend && !state.busy,
                    recording = true,
                    modifier = Modifier.testTag(COMPOSER_INSERT_TAG),
                )
                SendButton(
                    onClick = onSend,
                    enabled = deliveryEnabled && state.canSend && !state.busy,
                    recording = true,
                    modifier = Modifier.testTag(COMPOSER_SEND_TAG),
                )
                // #2598: the way out that keeps the text. It sits in the mic's
                // own slot — the trailing edge — because that is where the
                // thumb that started the dictation already is, and a tap here
                // is the same mic toggle, now meaning "stop".
                StopRecordingButton(
                    onClick = onMicTap,
                    modifier = Modifier.testTag(COMPOSER_STOP_RECORDING_TAG),
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
                    enabled = deliveryEnabled && state.canSend && !state.busy,
                    recording = true,
                    modifier = Modifier.testTag(COMPOSER_SEND_TAG),
                )
            }
        }
    }
}

/**
 * Attach files — a direct control on the composer row, not a menu entry.
 *
 * Restored by #2630: "now the attach button is hidden under plus". Attaching a
 * screenshot or a log is a primary composer action on a phone, so it costs one
 * tap, like Send and the mic.
 */
// #2635: the paperclip, the "+" and the mic trigger moved into the kit's
// `ComposerIdleControls` with the row they belong to. Three private copies of a
// 48dp icon button were what made the render harness need a mirror.

@Composable
private fun ComposerToolsPanel(
    state: ComposerUiState,
    slashCommandsAvailable: Boolean,
    onDismiss: () -> Unit,
    onHistory: () -> Unit,
    onSlash: () -> Unit,
    onInsert: () -> Unit,
    onClear: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PocketShellColors.SurfaceElev)
            .navigationBarsPadding()
            .heightIn(max = 560.dp)
            .verticalScroll(rememberScrollState())
            .testTag(COMPOSER_TOOLS_TAG),
    ) {
        SheetHeader(
            title = "Add to input",
            onClose = onDismiss,
            closeContentDescription = "Close input tools",
        )
        ComposerToolRow(
            title = "Recent prompts",
            icon = PocketShellIcons.History,
            onClick = onHistory,
            testTag = COMPOSER_HISTORY_TAG,
        )
        if (slashCommandsAvailable) {
            ComposerToolRow(
                title = "Slash commands",
                subtitle = "Insert a supported command into the draft",
                icon = PocketShellIcons.Code,
                onClick = onSlash,
                testTag = COMPOSER_SLASH_TRIGGER_TAG,
            )
        }
        ComposerToolRow(
            title = COMPOSER_PASTE_LABEL,
            subtitle = "Write the draft into the terminal without pressing Enter",
            icon = PocketShellIcons.Upload,
            onClick = onInsert,
            testTag = COMPOSER_INSERT_TAG,
        )
        // #2635 C4: no "Terminal keys" row here. The hotkeys panel is one tap
        // from the floating launcher's keyboard button (#2631) — which is where
        // it belongs, because `^C` is something you reach for WHILE the
        // terminal is in front of you, not after opening the composer's tool
        // sheet. Two affordances onto one panel is a duplicate, and D22 says
        // delete the loser rather than keep both.
        ComposerToolRow(
            title = "Clear draft",
            subtitle = if (state.draft.isBlank() && state.attachments.isEmpty()) {
                "Nothing to clear"
            } else {
                "Remove the current text and attachments"
            },
            icon = PocketShellIcons.Close,
            onClick = onClear,
            testTag = COMPOSER_DISCARD_TAG,
        )
    }
}

@Composable
private fun ComposerToolRow(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    testTag: String,
    subtitle: String? = null,
) {
    ListRow(
        title = title,
        subtitle = subtitle,
        leading = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = PocketShellColors.TextSecondary,
                modifier = Modifier.size(20.dp),
            )
        },
        onClick = onClick,
        modifier = Modifier.testTag(testTag),
    )
}

@Composable
private fun ToolGlyphButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
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
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) PocketShellColors.TextSecondary else PocketShellColors.TextMuted,
            modifier = Modifier.size(18.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SendButton(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    recording: Boolean = false,
    /**
     * Paste-without-Enter (#2635 C3). Null keeps Send a plain button — the
     * recording row still has its own explicit Paste control.
     */
    onLongClick: (() -> Unit)? = null,
) {
    val height = if (recording) ComposerRecordingPillHeight else ComposerIdlePillHeight
    val containerColor = if (enabled) PocketShellColors.Accent else PocketShellColors.SurfaceElev
    val contentColor = if (enabled) PocketShellColors.OnAccent else PocketShellColors.TextMuted
    Row(
        modifier = modifier
            .height(height)
            .clip(ComposerActionPillShape)
            .background(color = containerColor, shape = ComposerActionPillShape)
            .combinedClickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = onLongClick?.let { COMPOSER_PASTE_LABEL },
            )
            .semantics {
                if (onLongClick != null) {
                    // TalkBack cannot long-press a custom target, so the second
                    // verb is published as a named accessibility action.
                    customActions = listOf(
                        CustomAccessibilityAction(COMPOSER_PASTE_LABEL) {
                            onLongClick()
                            true
                        },
                    )
                }
            }
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
        Icon(
            imageVector = PocketShellIcons.Send,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(18.dp),
        )
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
            .semantics { contentDescription = "Paste without submitting" }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Paste",
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

/**
 * Ends a dictation and keeps the transcript (#2598).
 *
 * A filled accent disc with a stop square, in the same slot and at the same
 * size as the idle mic: the mic turns into its own stop, which is the
 * idiom every voice recorder uses. Deliberately NOT the [DiscardRecordingButton]
 * outline — one of these two throws the user's words away and the other keeps
 * them, so they must not look alike.
 */
@Composable
private fun StopRecordingButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(ComposerIdlePillHeight)
            .clip(CircleShape)
            .background(color = PocketShellColors.Accent, shape = CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = COMPOSER_STOP_RECORDING_DESCRIPTION },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = PocketShellIcons.Stop,
            contentDescription = null,
            tint = PocketShellColors.OnAccent,
            modifier = Modifier.size(COMPOSER_STOP_GLYPH_SIZE),
        )
    }
}

/** Quiet field/button radius for composer controls. */
private val ComposerActionPillRadius = 12.dp
private val ComposerActionPillShape = RoundedCornerShape(ComposerActionPillRadius)
/** Quiet metadata/label rung for readable composer draft text. */
private val ComposerDraftFontSize = 16.sp
private val ComposerIdlePillHeight = 48.dp
private val ComposerRecordingPillHeight = 48.dp
private val COMPOSER_ACTION_ICON_BUTTON_SIZE = 48.dp
private val COMPOSER_STOP_GLYPH_SIZE = 15.dp

private val DRAFT_SHAPE = RoundedCornerShape(PocketShellSpacing.md)
private val DRAFT_MIN_HEIGHT = 40.dp
private val DRAFT_MAX_HEIGHT = 168.dp
