package com.pocketshell.uikit.components

import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellType

/**
 * Quiet's primary workspace navigation row. The whole row is the drill-in
 * target; callers provide only the short label and a useful muted summary.
 *
 * ## [dense]: one line, the way a desktop session sidebar does it (#2630)
 *
 * The maintainer's density reference is PocketShell Desktop's sidebar, where a
 * workspace is a single line — name, a small session-count badge, a relative
 * time — with no subtitle row and no padding beyond what that line needs. In
 * dense mode the row drops [subtitle]/[subtitleContent] entirely, moves the
 * summary into [trailingContent] beside the chevron, and sits on the 48dp touch
 * floor instead of the 64dp navigation target.
 *
 * 48dp is the FLOOR, not a shrink below it: the visual height comes down, the
 * hit area does not.
 *
 * ## [leadingContent] and [chevron] (#2635, D1 remainder)
 *
 * [leadingContent] takes the desktop's "something live is in here" dot. The
 * [chevron] is opt-out because a chevron is a promise: it means "this opens a
 * list to choose from". A row that opens its terminal directly must not show
 * one — a glyph that lies about where a tap goes is worse than no glyph.
 */
@Composable
fun WorkspaceRow(
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null,
    subtitleContent: (@Composable () -> Unit)? = null,
    dense: Boolean = false,
    leadingContent: (@Composable () -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    chevron: Boolean = true,
) {
    val minHeight = if (dense) {
        PocketShellDensity.tapTargetMin
    } else {
        PocketShellDensity.workspaceRowMinHeight
    }
    ListRow(
        title = title,
        subtitle = subtitle.takeUnless { dense },
        subtitleContent = subtitleContent.takeUnless { dense },
        leading = leadingContent,
        trailing = if (trailingContent == null && !chevron) {
            null
        } else {
            {
                if (trailingContent != null) {
                    trailingContent()
                }
                if (chevron) {
                    NavigationChevron()
                }
            }
        },
        onClick = onClick,
        onLongClick = onLongClick,
        onLongClickLabel = onLongClickLabel,
        titleMaxLines = if (dense) 1 else 2,
        subtitleMaxLines = 2,
        titleStyle = PocketShellType.workspace,
        subtitleStyle = PocketShellType.metadata,
        titleWeight = FontWeight.SemiBold,
        minHeight = minHeight,
        modifier = modifier
            .heightIn(min = minHeight)
            .let { base -> if (testTag == null) base else base.testTag(testTag) },
    )
}
