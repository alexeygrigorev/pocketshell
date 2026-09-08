@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.pocketshell.next.files

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.activity.compose.BackHandler
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.pocketshell.uikit.components.Banner
import com.pocketshell.uikit.components.BannerRole
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.EmptyState
import com.pocketshell.uikit.components.KebabTrigger
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.components.SheetHeader
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellSpacing

/** Stable test tags for the viewer shell. */
const val VIEWER_TAG: String = "file-viewer"
const val VIEWER_LOADING_TAG: String = "file-viewer-loading"
const val VIEWER_ERROR_TAG: String = "file-viewer-error"
const val VIEWER_SAVED_TAG: String = "file-viewer-saved"
const val VIEWER_EDIT_TAG: String = "file-viewer-edit"
const val VIEWER_SAVE_TAG: String = "file-viewer-save"
const val VIEWER_CANCEL_TAG: String = "file-viewer-cancel"
const val VIEWER_MARKDOWN_TOGGLE_TAG: String = "file-viewer-markdown-toggle"
const val VIEWER_UNSAVED_TAG: String = "file-viewer-unsaved"
const val VIEWER_UNSAVED_SAVE_TAG: String = "file-viewer-unsaved-save"
const val VIEWER_UNSAVED_DISCARD_TAG: String = "file-viewer-unsaved-discard"
const val VIEWER_UNSAVED_KEEP_TAG: String = "file-viewer-unsaved-keep"
const val VIEWER_CONFLICT_TAG: String = "file-viewer-conflict"
const val VIEWER_CONFLICT_COPY_TAG: String = "file-viewer-conflict-copy"
const val VIEWER_CONFLICT_RELOAD_TAG: String = "file-viewer-conflict-reload"
const val VIEWER_CONFLICT_KEEP_TAG: String = "file-viewer-conflict-keep"
const val VIEWER_ACTIONS_TAG: String = "file-viewer-actions"
const val VIEWER_ACTIONS_SHEET_TAG: String = "file-viewer-actions-sheet"

/**
 * Route-level entry point: binds the Hilt-provided [ViewerViewModel] to the
 * stateless [ViewerScreen].
 *
 * `ON_START` drives the read so that returning from the background — or from
 * the explorer after an upload replaced the file — repaints what the host
 * actually holds. The ViewModel refuses to re-read while the user is editing,
 * so this cannot eat a buffer.
 */
@Composable
fun ViewerRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ViewerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.load() }

    BackHandler {
        if (viewModel.requestBack()) onBack()
    }

    ViewerScreen(
        state = state,
        onBack = {
            if (viewModel.requestBack()) onBack()
        },
        onEdit = viewModel::startEditing,
        onDraftChange = viewModel::onDraftChange,
        onSave = viewModel::save,
        onCancelEdit = {
            if (viewModel.requestBack()) viewModel.cancelEditing()
        },
        onToggleMarkdown = viewModel::toggleMarkdownRendering,
        onDismissSaved = viewModel::dismissSavedMessage,
        onSaveAndLeave = { viewModel.saveAndLeave(onBack) },
        onDiscardChanges = {
            viewModel.discardChanges()
            onBack()
        },
        onKeepEditing = viewModel::keepEditing,
        onSaveAsCopy = viewModel::saveAsCopy,
        onReloadRemote = viewModel::reloadRemoteVersion,
        onConflictKeepEditing = viewModel::keepEditing,
        modifier = modifier,
    )
}

/**
 * The file viewer shell (rewrite task P-3b).
 *
 * A thin dispatcher: header, banners, and ONE of the per-kind renderers
 * ([TextContent] / [TextEditor] / [MarkdownView] / [ImageContent] /
 * [BinaryContent]), each of which lives in its own file. The old client's
 * equivalent was a single 2,536-line screen holding every renderer, the tab
 * strip, the review/annotation surface and their gesture handlers inline; the
 * split is what keeps each renderer readable and independently testable.
 *
 * ## What is not here
 *
 * - **PDF and audio.** The old viewer rendered both. They are the two least
 *   central kinds for a dev box (a PDF on a build server is rare; audio rarer
 *   still), each drags its own platform component — `PdfRenderer` with a page
 *   cache, `MediaPlayer` with a lifecycle and a transport bar — and neither is
 *   needed for the browse → open → edit → save journey this task exists to
 *   deliver. They are the first thing to add back if the maintainer misses them.
 * - **Review / annotation.** Cut from the rewrite entirely (maintainer
 *   decision, 2026-09-03: "I've never actually used it").
 * - **An open-file tab strip.** One route argument names one file; the back
 *   stack is the tab strip.
 *
 * Stateless, so it renders identically from a journey, a Robolectric test and a
 * design render.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    state: ViewerUiState,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancelEdit: () -> Unit,
    onToggleMarkdown: () -> Unit,
    onDismissSaved: () -> Unit,
    modifier: Modifier = Modifier,
    onSaveAndLeave: () -> Unit = onSave,
    onDiscardChanges: () -> Unit = onCancelEdit,
    onKeepEditing: () -> Unit = {},
    onSaveAsCopy: () -> Unit = {},
    onReloadRemote: () -> Unit = {},
    onConflictKeepEditing: () -> Unit = onKeepEditing,
) {
    var actionsOpen by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PocketShellColors.Background)
            // The editor is a full-height text field, so without this the soft
            // keyboard covers the bottom half of the buffer being edited.
            .imePadding()
            .testTag(VIEWER_TAG),
    ) {
        ScreenHeader(
            title = state.name.ifBlank { "File" },
            subtitle = state.path,
            onBack = onBack,
            trailing = {
                when {
                    state.editing && state.conflict == null -> PocketShellButton(
                        text = if (state.saving) "Saving…" else "Save",
                        onClick = onSave,
                        variant = ButtonVariant.Primary,
                        compact = true,
                        enabled = !state.saving,
                        modifier = Modifier.testTag(VIEWER_SAVE_TAG),
                    )

                    state.conflict == null && (state.markdownCapable || state.editable) ->
                        KebabTrigger(
                            contentDescription = "File actions",
                            onClick = { actionsOpen = true },
                            triggerTestTag = VIEWER_ACTIONS_TAG,
                        )
                }
            },
        )

        state.savedMessage?.let { saved ->
            Banner(
                text = saved,
                role = BannerRole.Info,
                trailingContent = {
                    PocketShellButton(
                        text = "Dismiss",
                        onClick = onDismissSaved,
                        variant = ButtonVariant.Text,
                        compact = true,
                    )
                },
                modifier = Modifier
                    .padding(horizontal = PocketShellSpacing.md)
                    .padding(bottom = PocketShellSpacing.sm)
                    .testTag(VIEWER_SAVED_TAG),
            )
        }

        state.failure?.let { failure ->
            Banner(
                text = failure,
                role = BannerRole.Error,
                maxLines = 5,
                modifier = Modifier
                    .padding(horizontal = PocketShellSpacing.md)
                    .padding(bottom = PocketShellSpacing.sm)
                    .testTag(VIEWER_ERROR_TAG),
            )
        }

        if (state.conflict != null) {
            FileConflictSurface(
                state = state,
                onSaveAsCopy = onSaveAsCopy,
                onReloadRemote = onReloadRemote,
                onKeepEditing = onConflictKeepEditing,
                modifier = Modifier.weight(1f),
            )
        } else {
            ViewerBody(
                state = state,
                onDraftChange = onDraftChange,
                modifier = Modifier.weight(1f),
            )
        }
    }

    if (state.unsavedChangesVisible) {
        UnsavedChangesSheet(
            state = state,
            onSave = onSaveAndLeave,
            onDiscard = onDiscardChanges,
            onKeepEditing = onKeepEditing,
        )
    }

    if (actionsOpen) {
        ViewerActionsSheet(
            state = state,
            onDismiss = { actionsOpen = false },
            onEdit = {
                actionsOpen = false
                onEdit()
            },
            onToggleMarkdown = {
                actionsOpen = false
                onToggleMarkdown()
            },
        )
    }
}

/**
 * The one renderer that matches the current state.
 *
 * Editing wins over every render mode — including Markdown — because the buffer
 * being edited is source, and formatting it while the user types would make the
 * caret meaningless.
 */
@Composable
private fun ViewerBody(
    state: ViewerUiState,
    onDraftChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        state.editing -> TextEditor(
            draft = state.draft,
            onDraftChange = onDraftChange,
            enabled = !state.saving,
            modifier = modifier,
        )

        state.loading && !state.loaded -> EmptyState(
            title = "Opening…",
            description = "Reading the file over SFTP.",
            modifier = modifier.testTag(VIEWER_LOADING_TAG),
        )

        !state.loaded -> EmptyState(
            title = if (state.failure != null) "Couldn't open this file" else "Nothing loaded",
            description = state.failure?.let { "See the message above." },
            modifier = modifier,
        )

        else -> when (val content = state.content) {
            ViewerContent.Empty -> EmptyState(title = "Nothing loaded", modifier = modifier)

            is ViewerContent.Text ->
                if (state.markdownCapable && state.renderMarkdown) {
                    val blocks = remember(content.text) { MarkdownParser.parse(content.text) }
                    Column(
                        modifier = modifier
                            .fillMaxSize()
                            .background(PocketShellColors.Background)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        MarkdownView(blocks = blocks)
                    }
                } else {
                    TextContent(text = content.text, modifier = modifier)
                }

            is ViewerContent.Image -> ImageContent(bytes = content.bytes, modifier = modifier)
            is ViewerContent.Binary -> BinaryContent(bytes = content.bytes, modifier = modifier)
        }
    }
}

/** File mode actions live in one native sheet so the header stays single-action. */
@Composable
private fun ViewerActionsSheet(
    state: ViewerUiState,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onToggleMarkdown: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = PocketShellShapes.large,
        containerColor = PocketShellColors.Surface,
        modifier = Modifier.testTag(VIEWER_ACTIONS_SHEET_TAG),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = PocketShellSpacing.lg)
                .padding(top = PocketShellSpacing.lg, bottom = PocketShellSpacing.lg),
        ) {
            SheetHeader(title = "File actions", onClose = onDismiss)
            if (state.markdownCapable) {
                ListRow(
                    title = if (state.renderMarkdown) "Show source" else "Show rendered",
                    onClick = onToggleMarkdown,
                    modifier = Modifier.testTag(VIEWER_MARKDOWN_TOGGLE_TAG),
                )
            }
            if (state.editable) {
                ListRow(
                    title = "Edit",
                    onClick = onEdit,
                    modifier = Modifier.testTag(VIEWER_EDIT_TAG),
                )
            }
        }
    }
}

/** The explicit state/action surface for a remote metadata conflict. */
@Composable
private fun FileConflictSurface(
    state: ViewerUiState,
    onSaveAsCopy: () -> Unit,
    onReloadRemote: () -> Unit,
    onKeepEditing: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val conflict = requireNotNull(state.conflict)
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PocketShellSpacing.lg, vertical = PocketShellSpacing.lg)
            .testTag(VIEWER_CONFLICT_TAG),
        verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.md),
    ) {
        Text(
            text = "File changed on host",
            color = PocketShellColors.Text,
            style = com.pocketshell.uikit.theme.PocketShellType.title,
        )
        Banner(
            text = "${RemotePath.nameOf(conflict.path)} has newer changes. Your edits have not overwritten the remote file.",
            role = BannerRole.Warning,
            maxLines = 5,
        )
        Text(
            text = "Choose how to preserve your draft. Reloading discards it; saving a copy leaves the newer host file intact.",
            color = PocketShellColors.TextSecondary,
            style = com.pocketshell.uikit.theme.PocketShellType.body,
        )
        PocketShellButton(
            text = if (state.saving) "Saving copy…" else "Save as a copy",
            onClick = onSaveAsCopy,
            variant = ButtonVariant.Primary,
            enabled = !state.saving,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(VIEWER_CONFLICT_COPY_TAG),
        )
        PocketShellButton(
            text = "Reload remote version",
            onClick = onReloadRemote,
            variant = ButtonVariant.Secondary,
            enabled = !state.saving,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(VIEWER_CONFLICT_RELOAD_TAG),
        )
        PocketShellButton(
            text = "Keep editing",
            onClick = onKeepEditing,
            variant = ButtonVariant.Text,
            enabled = !state.saving,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(VIEWER_CONFLICT_KEEP_TAG),
        )
    }
}

/** Three-way dirty-buffer confirmation from design-kit frame 59. */
@Composable
private fun UnsavedChangesSheet(
    state: ViewerUiState,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onKeepEditing: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onKeepEditing,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = PocketShellShapes.large,
        modifier = Modifier.testTag(VIEWER_UNSAVED_TAG),
        containerColor = PocketShellColors.Surface,
    ) {
        UnsavedChangesSheetContent(
            state = state,
            onSave = onSave,
            onDiscard = onDiscard,
            onKeepEditing = onKeepEditing,
        )
    }
}

@Composable
internal fun UnsavedChangesSheetContent(
    state: ViewerUiState,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onKeepEditing: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = PocketShellSpacing.lg)
            .padding(top = PocketShellSpacing.lg, bottom = PocketShellSpacing.lg)
            .testTag(VIEWER_UNSAVED_TAG),
        verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.md),
    ) {
        SheetHeader(title = "Keep your changes?", onClose = onKeepEditing)
        Text(
            text = "${state.name} has unsaved edits. Leaving now will not update the file on the host.",
            color = PocketShellColors.TextSecondary,
            style = com.pocketshell.uikit.theme.PocketShellType.body,
        )
        PocketShellButton(
            text = if (state.saving) "Saving…" else "Save to host",
            onClick = onSave,
            variant = ButtonVariant.Primary,
            enabled = !state.saving,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(VIEWER_UNSAVED_SAVE_TAG),
        )
        PocketShellButton(
            text = "Discard edits",
            onClick = onDiscard,
            variant = ButtonVariant.Destructive,
            enabled = !state.saving,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(VIEWER_UNSAVED_DISCARD_TAG),
        )
        PocketShellButton(
            text = "Keep editing",
            onClick = onKeepEditing,
            variant = ButtonVariant.Text,
            enabled = !state.saving,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(VIEWER_UNSAVED_KEEP_TAG),
        )
    }
}
