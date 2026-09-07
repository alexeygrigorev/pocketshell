package com.pocketshell.uikit.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.icons.PocketShellIcons
import com.pocketshell.uikit.theme.PocketShellColors

/**
 * Shared drill-in/navigation affordance for rows that route to another screen,
 * sheet, or folder. This is deliberately separate from [DisclosureIcon], whose
 * contract is expand/collapse only.
 */
@Composable
@Suppress("UNUSED_PARAMETER")
fun NavigationChevron(
    modifier: Modifier = Modifier,
    tint: Color = PocketShellColors.TextSecondary,
    size: Dp = NavigationChevronDefaultSize,
    strokeWidth: Dp = NavigationChevronStrokeWidth,
) {
    Icon(
        imageVector = PocketShellIcons.Chevron,
        contentDescription = null,
        tint = tint,
        modifier = modifier.size(size),
    )
}

val NavigationChevronDefaultSize: Dp = 18.dp
val NavigationChevronStrokeWidth: Dp = 2.dp
