package com.pocketshell.app.fileviewer

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.components.FileTypeIcon
import com.pocketshell.uikit.components.fileIconClassForName
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellType

const val FILE_VIEWER_TAB_STRIP_TAG = "fileViewerTabStrip"
const val FILE_VIEWER_TAB_TAG_PREFIX = "fileViewerTab:"
const val FILE_VIEWER_TAB_CLOSE_TAG_PREFIX = "fileViewerTabClose:"
const val FILE_VIEWER_EMPTY_WORKSPACE_TAG = "fileViewerEmptyWorkspace"
const val FILE_VIEWER_WORKSPACE_UNAVAILABLE_TAG = "fileViewerWorkspaceUnavailable"
const val FILE_VIEWER_WORKSPACE_RETRY_TAG = "fileViewerWorkspaceRetry"
const val FILE_VIEWER_DIRTY_WORK_DIALOG_TAG = "fileViewerDirtyWorkDialog"
const val FILE_VIEWER_DIRTY_STAY_TAG = "fileViewerDirtyStay"
const val FILE_VIEWER_DIRTY_DISCARD_TAG = "fileViewerDirtyDiscard"
const val FILE_VIEWER_DIRTY_SUBMIT_TAG = "fileViewerDirtySubmit"
const val FILE_VIEWER_EMPTY_BROWSE_TAG = "fileViewerEmptyBrowse"
const val FILE_VIEWER_EMPTY_OPEN_PATH_TAG = "fileViewerEmptyOpenPath"
const val FILE_VIEWER_EMPTY_OPEN_PATH_FIELD_TAG = "fileViewerEmptyOpenPathField"
const val FILE_VIEWER_EMPTY_OPEN_PATH_CONFIRM_TAG = "fileViewerEmptyOpenPathConfirm"

private const val UNDERLINE_MS = 200

/**
 * Issue #1715 — one 48dp horizontal tab row. Active tab is auto-scrolled
 * fully into view. Each close affordance is its own 48dp hit box.
 */
@Composable
internal fun OpenFileTabStrip(
    tabs: List<OpenFileTab>,
    activePath: String?,
    labels: Map<String, String>,
    onSelect: (OpenFileTab) -> Unit,
    onClose: (OpenFileTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tabs.isEmpty()) return
    val listState = rememberLazyListState()
    LaunchedEffect(activePath, tabs) {
        val index = tabs.indexOfFirst { it.absolutePath == activePath }
        if (index >= 0) listState.animateScrollToItem(index)
    }
    LazyRow(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .height(PocketShellDensity.tapTargetMin)
            .background(PocketShellColors.Background)
            .testTag(FILE_VIEWER_TAB_STRIP_TAG),
        contentPadding = PaddingValues(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(tabs, key = { it.absolutePath }) { tab ->
            val active = tab.absolutePath == activePath
            val label = labels[tab.absolutePath] ?: tab.absolutePath.substringAfterLast('/')
            OpenFileTabChip(
                tab = tab,
                label = label,
                active = active,
                onSelect = { onSelect(tab) },
                onClose = { onClose(tab) },
            )
        }
    }
}

@Composable
private fun OpenFileTabChip(
    tab: OpenFileTab,
    label: String,
    active: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
) {
    val underline by animateColorAsState(
        targetValue = if (active) PocketShellColors.Accent else PocketShellColors.Background,
        animationSpec = tween(UNDERLINE_MS),
        label = "tabUnderline",
    )
    val textColor by animateColorAsState(
        targetValue = if (active) PocketShellColors.Accent else PocketShellColors.TextSecondary,
        animationSpec = tween(UNDERLINE_MS),
        label = "tabLabel",
    )
    Box(
        modifier = Modifier
            .widthIn(min = 120.dp, max = 168.dp)
            .height(PocketShellDensity.tapTargetMin),
    ) {
        androidx.compose.foundation.layout.Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(PocketShellDensity.tapTargetMin),
        ) {
            androidx.compose.foundation.layout.Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .height(PocketShellDensity.tapTargetMin)
                    .clickable(role = Role.Button, onClick = onSelect)
                    .testTag(FILE_VIEWER_TAB_TAG_PREFIX + tab.absolutePath)
                    .semantics { contentDescription = "Open file $label" }
                    .padding(start = 6.dp),
            ) {
                FileTypeIcon(
                    iconClass = fileIconClassForName(tab.absolutePath),
                    sizeDp = 16,
                )
                Text(
                    text = label,
                    color = textColor,
                    style = PocketShellType.bodyMono,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp),
                )
            }
            Box(
                modifier = Modifier
                    .size(PocketShellDensity.tapTargetMin)
                    .clickable(role = Role.Button, onClick = onClose)
                    .testTag(FILE_VIEWER_TAB_CLOSE_TAG_PREFIX + tab.absolutePath)
                    .semantics { contentDescription = "Close $label" },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "×",
                    color = PocketShellColors.TextSecondary,
                    style = PocketShellType.bodyDense,
                )
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(2.dp)
                .background(underline),
        )
    }
}
