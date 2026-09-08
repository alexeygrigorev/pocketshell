package com.pocketshell.next.workspaces

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.icons.PocketShellIcons
import com.pocketshell.uikit.theme.PocketShellColors

/** Quiet, monochrome session mark used as metadata beside a readable name. */
@Composable
fun SessionKindMark(
    agent: String?,
    modifier: Modifier = Modifier,
    showShell: Boolean = false,
) {
    val key = agent?.trim()?.lowercase()
    val icon = when (key) {
        "claude" -> PocketShellIcons.Hexagon
        "codex" -> PocketShellIcons.Code
        "opencode", "open_code", "open-code" -> PocketShellIcons.Terminal
        "grok" -> PocketShellIcons.Zap
        "shell", null, "", "unknown" -> if (showShell) PocketShellIcons.Terminal else null
        else -> null
    } ?: return
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = PocketShellColors.TextMuted,
        modifier = modifier.size(18.dp),
    )
}
