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
 * The track/fill corner radius. Intentionally below the smallest chip shape
 * rung (`PocketShellShapes.small` = 8dp): a 6dp-tall progress track is micro
 * geometry (design-system "micro role badges 3-6dp", local-only), so a single
 * named constant documents the deliberate sub-ladder value instead of a forced
 * over-rounding to the chip rung. Shared by the track and the fill so they
 * always match.
 */
private val TrackRadius = 4.dp

/**
 * Slim progress bar used by the usage panel cards. Matches
 * `.progress-track` / `.progress-fill` in `docs/mockups/styles.css`.
 *
 * Visual recipe per the CSS:
 * - Track: 6dp tall, `surface-elev` background, 4dp corner radius
 * - Fill: full-height, accent colour by default, 4dp corner radius
 *
 * #461 token note: the 6dp track height and the 4dp radii are intentional
 * **sub-ladder micro geometry** — the design system explicitly carves out
 * "micro role badges 3-6dp" as local-only values below the smallest chip rung
 * (`PocketShellShapes.small` = 8dp). Snapping a 6dp-tall bar to the 8dp chip
 * rung would over-round it into a pill, so the thin-track radius stays a named
 * local [TrackRadius] constant rather than a forced token. The colour, by
 * contrast, comes entirely from the [PocketShellColors] token layer.
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
                shape = RoundedCornerShape(TrackRadius),
            ),
    ) {
        // We draw the fill as a child box that takes a fraction of the
        // parent's width. Compose's `fillMaxWidth(fraction)` is
        // declarative and avoids any layout-pass math here.
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction = clamped)
                .background(color = fillColor, shape = RoundedCornerShape(TrackRadius)),
        )
    }
}
