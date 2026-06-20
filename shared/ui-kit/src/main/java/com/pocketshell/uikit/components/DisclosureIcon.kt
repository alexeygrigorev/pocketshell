package com.pocketshell.uikit.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.theme.PocketShellColors

/**
 * The single, canonical expand/collapse disclosure affordance (#840).
 *
 * Before this component each expandable surface rolled its own glyph: the
 * conversation tool-call card drew an ASCII letter `v` when expanded vs the
 * typographic right-guillemet `›` when collapsed; the composer pending-queue
 * toggle drew `v` vs a plain ASCII `>`; the folder tree drew two distinct
 * hand-built triangle `Path`s. Because those are different characters / paths
 * at different baselines, weights and optical centers, the collapsed and
 * expanded states read as **two different icons** rather than one icon
 * toggling — exactly the maintainer's report on the conversation cards.
 *
 * This is one filled triangle drawn ONCE (pointing right, ▶) and **rotated**
 * 90° to point down (▼) when expanded. Collapsed↔expanded is provably the
 * same shape turning, never a glyph swap. The rotation is animated (~120ms)
 * so the toggle reads as a single icon in motion.
 *
 * Use this for EVERY expand/collapse row. It is NOT for navigation chevrons
 * (drill-in `›` / back `‹`) or dropdown triggers (`▾`) — those are a
 * different, deliberate affordance (see the #840 audit, tables B and C).
 *
 * @param expanded whether the disclosed content is currently open.
 * @param tint the triangle colour; defaults to the quiet muted-secondary so
 *   the affordance stays unobtrusive. Surfaces that want it to match an
 *   accent (e.g. the composer queue) pass their own tint.
 * @param size the square footprint of the icon (default 14dp, matching the
 *   14sp glyphs it replaces on the conversation/composer rows).
 */
@Composable
fun DisclosureIcon(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    tint: Color = PocketShellColors.TextSecondary,
    size: Dp = DisclosureIconDefaultSize,
) {
    val angle: Float by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(durationMillis = DisclosureIconRotationMillis),
        label = "disclosure-icon-rotation",
    )

    Canvas(modifier = modifier.size(size)) {
        rotate(degrees = angle) {
            // A single right-pointing filled triangle (▶). When `angle` is 0
            // it reads as collapsed; rotated 90° clockwise it points down (▼)
            // for the expanded state. One Path, one shape, always.
            val w = this.size.width
            val h = this.size.height
            val triangle = Path().apply {
                moveTo(w * 0.34f, h * 0.22f)
                lineTo(w * 0.70f, h * 0.5f)
                lineTo(w * 0.34f, h * 0.78f)
                close()
            }
            drawPath(path = triangle, color = tint)
        }
    }
}

/** Default 14dp footprint — matches the 14sp glyphs the component replaces. */
val DisclosureIconDefaultSize: Dp = 14.dp

/** Rotation animation duration for the collapsed↔expanded toggle. */
const val DisclosureIconRotationMillis: Int = 120
