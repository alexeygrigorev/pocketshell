package com.pocketshell.next.files

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.pocketshell.uikit.components.Banner
import com.pocketshell.uikit.components.BannerRole
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.EmptyState
import com.pocketshell.uikit.components.FileTypeIcon
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.components.fileIconClassForName
import com.pocketshell.uikit.theme.PocketShellColors
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

    ViewerScreen(
        state = state,
        onBack = onBack,
        onEdit = viewModel::startEditing,
        onDraftChange = viewModel::onDraftChange,
        onSave = viewModel::save,
        onCancelEdit = viewModel::cancelEditing,
        onToggleMarkdown = viewModel::toggleMarkdownRendering,
        onDismissSaved = viewModel::dismissSavedMessage,
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
) {
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
            leading = { FileTypeIcon(iconClass = fileIconClassForName(state.name)) },
            trailing = { ViewerActions(state, onBack, onEdit, onSave, onCancelEdit, onToggleMarkdown) },
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

        ViewerBody(
            state = state,
            onDraftChange = onDraftChange,
            modifier = Modifier.weight(1f),
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

/**
 * Header actions. Three mutually exclusive sets rather than a kebab: the viewer
 * has at most three affordances at any moment, and a menu to reach two buttons
 * is a tap the user should not have to spend.
 */
@Composable
private fun ViewerActions(
    state: ViewerUiState,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onSave: () -> Unit,
    onCancelEdit: () -> Unit,
    onToggleMarkdown: () -> Unit,
) {
    Row {
        if (state.editing) {
            PocketShellButton(
                text = "Cancel",
                onClick = onCancelEdit,
                variant = ButtonVariant.Text,
                compact = true,
                enabled = !state.saving,
                modifier = Modifier.testTag(VIEWER_CANCEL_TAG),
            )
            PocketShellButton(
                text = if (state.saving) "Saving…" else "Save",
                onClick = onSave,
                variant = ButtonVariant.Primary,
                compact = true,
                enabled = !state.saving,
                modifier = Modifier.testTag(VIEWER_SAVE_TAG),
            )
        } else {
            PocketShellButton(
                text = "Back",
                onClick = onBack,
                variant = ButtonVariant.Text,
                compact = true,
            )
            if (state.markdownCapable) {
                PocketShellButton(
                    text = if (state.renderMarkdown) "Source" else "Rendered",
                    onClick = onToggleMarkdown,
                    variant = ButtonVariant.Text,
                    compact = true,
                    modifier = Modifier.testTag(VIEWER_MARKDOWN_TOGGLE_TAG),
                )
            }
            PocketShellButton(
                text = "Edit",
                onClick = onEdit,
                variant = ButtonVariant.Primary,
                compact = true,
                enabled = state.editable,
                modifier = Modifier.testTag(VIEWER_EDIT_TAG),
            )
        }
    }
}
