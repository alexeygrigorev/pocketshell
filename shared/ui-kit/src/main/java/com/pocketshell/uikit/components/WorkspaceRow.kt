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
 */
@Composable
fun WorkspaceRow(
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null,
    subtitleContent: (@Composable () -> Unit)? = null,
) {
    ListRow(
        title = title,
        subtitle = subtitle,
        subtitleContent = subtitleContent,
        trailing = { NavigationChevron() },
        onClick = onClick,
        titleMaxLines = 2,
        subtitleMaxLines = 2,
        titleStyle = PocketShellType.workspace,
        subtitleStyle = PocketShellType.metadata,
        titleWeight = FontWeight.SemiBold,
        modifier = modifier
            .heightIn(min = PocketShellDensity.workspaceRowMinHeight)
            .let { base -> if (testTag == null) base else base.testTag(testTag) },
    )
}
