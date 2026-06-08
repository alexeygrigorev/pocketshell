package com.pocketshell.app.portfwd

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.theme.PocketShellColors

@Composable
internal fun ForwardingGlyph(
    modifier: Modifier = Modifier.size(14.dp),
    color: Color = PocketShellColors.Accent,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w * 0.12f)
        // Top arrow pointing right.
        drawLine(
            color = color,
            start = Offset(0f, h * 0.3f),
            end = Offset(w, h * 0.3f),
            strokeWidth = stroke.width,
        )
        drawLine(
            color = color,
            start = Offset(w * 0.65f, h * 0.05f),
            end = Offset(w, h * 0.3f),
            strokeWidth = stroke.width,
        )
        drawLine(
            color = color,
            start = Offset(w * 0.65f, h * 0.55f),
            end = Offset(w, h * 0.3f),
            strokeWidth = stroke.width,
        )
        // Bottom arrow pointing left.
        drawLine(
            color = color,
            start = Offset(0f, h * 0.7f),
            end = Offset(w, h * 0.7f),
            strokeWidth = stroke.width,
        )
        drawLine(
            color = color,
            start = Offset(w * 0.35f, h * 0.45f),
            end = Offset(0f, h * 0.7f),
            strokeWidth = stroke.width,
        )
        drawLine(
            color = color,
            start = Offset(w * 0.35f, h * 0.95f),
            end = Offset(0f, h * 0.7f),
            strokeWidth = stroke.width,
        )
    }
}
