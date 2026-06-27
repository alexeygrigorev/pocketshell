package com.pocketshell.uikit.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.theme.PocketShellColors

/**
 * Shared drill-in/navigation affordance for rows that route to another screen,
 * sheet, or folder. This is deliberately separate from [DisclosureIcon], whose
 * contract is expand/collapse only.
 */
@Composable
fun NavigationChevron(
    modifier: Modifier = Modifier,
    tint: Color = PocketShellColors.TextSecondary,
    size: Dp = NavigationChevronDefaultSize,
    strokeWidth: Dp = NavigationChevronStrokeWidth,
) {
    Canvas(modifier = modifier.size(size)) {
        val strokePx = strokeWidth.toPx()
        val lineStyle = Stroke(width = strokePx, cap = StrokeCap.Round)
        val startX = this.size.width * 0.38f
        val endX = this.size.width * 0.62f
        val topY = this.size.height * 0.26f
        val midY = this.size.height * 0.50f
        val bottomY = this.size.height * 0.74f

        drawLine(
            color = tint,
            start = Offset(startX, topY),
            end = Offset(endX, midY),
            strokeWidth = lineStyle.width,
            cap = lineStyle.cap,
        )
        drawLine(
            color = tint,
            start = Offset(endX, midY),
            end = Offset(startX, bottomY),
            strokeWidth = lineStyle.width,
            cap = lineStyle.cap,
        )
    }
}

val NavigationChevronDefaultSize: Dp = 18.dp
val NavigationChevronStrokeWidth: Dp = 2.dp
