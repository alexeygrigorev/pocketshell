package com.pocketshell.uikit.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.model.ProgressKind
import com.pocketshell.uikit.theme.PocketShellColors

/**
 * Slim progress bar used by the usage panel cards. Matches
 * `.progress-track` / `.progress-fill` in `docs/mockups/styles.css`.
 *
 * Visual recipe per the CSS:
 * - Track: 6px tall, `surface-elev` background, 4px corner radius
 * - Fill: full-height, accent colour by default, 4px corner radius
 *
 * The fill colour is picked by [kind]:
 * - [ProgressKind.Default] -> accent (cyan)
 * - [ProgressKind.Warn] -> amber
 * - [ProgressKind.Danger] -> red
 *
 * [progress] is clamped to `[0f, 1f]` before rendering — callers don't
 * have to round-trip through a coercion before passing arbitrary float
 * usage ratios in.
 */
@Composable
fun ProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    kind: ProgressKind = ProgressKind.Default,
) {
    val clamped: Float = progress.coerceIn(0f, 1f)

    val fillColor: Color = when (kind) {
        ProgressKind.Default -> PocketShellColors.Accent
        ProgressKind.Warn -> PocketShellColors.Amber
        ProgressKind.Danger -> PocketShellColors.Red
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .background(
                color = PocketShellColors.SurfaceElev,
                shape = RoundedCornerShape(4.dp),
            ),
    ) {
        // We draw the fill as a child box that takes a fraction of the
        // parent's width. Compose's `fillMaxWidth(fraction)` is
        // declarative and avoids any layout-pass math here.
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction = clamped)
                .background(color = fillColor, shape = RoundedCornerShape(4.dp)),
        )
    }
}
