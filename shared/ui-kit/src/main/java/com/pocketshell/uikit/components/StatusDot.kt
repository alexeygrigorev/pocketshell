package com.pocketshell.uikit.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.model.ConnectionStatus
import com.pocketshell.uikit.theme.PocketShellColors

/**
 * Small status indicator dot. Matches `.status-dot` and its variants
 * (`.connected`, `.connecting`, `.error`) in `docs/mockups/styles.css`.
 *
 * Sizing: 8dp circle (the CSS uses `width: 8px; height: 8px;`).
 *
 * Connected glow approximation: the CSS uses
 * `box-shadow: 0 0 7px rgba(34,197,94,0.7)`, which renders as a soft
 * radial falloff from the dot's edge. Compose's `Canvas` has no shadow
 * primitive, so we approximate the halo by stacking two translucent
 * companion discs around the solid core: an outer disc at ~radius + 3dp
 * with low alpha, and a middle disc at ~radius + 1dp with a mid alpha.
 * The total light footprint is a touch tighter than the CSS spec because
 * Compose discs have hard edges (no blur radius), but the visual is
 * close enough to read as "active / connected" at a glance. If we ever
 * need a true gaussian glow we can swap in a `RadialGradient` brush.
 *
 * Connecting animates opacity from 1.0 -> 0.3 -> 1.0 every 1.4s —
 * matching the CSS `@keyframes pulse { 0%, 100% { opacity: 1; }
 * 50% { opacity: 0.3; } }`.
 *
 * The dot is purely decorative — there's no `onClick` and no semantic
 * description. Wrap it in something labelled by its host name if you
 * need talkback support.
 */
@Composable
fun StatusDot(
    status: ConnectionStatus,
    modifier: Modifier = Modifier,
) {
    val baseColor: Color = when (status) {
        ConnectionStatus.Idle -> PocketShellColors.TextMuted
        ConnectionStatus.Connecting -> PocketShellColors.Amber
        ConnectionStatus.Connected -> PocketShellColors.Green
        ConnectionStatus.Error -> PocketShellColors.Red
    }

    // Drive the pulse animation only for the Connecting state — every
    // other state is static. `rememberInfiniteTransition` is cheap when
    // it has no children, but we still gate the call so the animation
    // graph doesn't run for the dominant case.
    val alpha: Float = if (status == ConnectionStatus.Connecting) {
        val transition = rememberInfiniteTransition(label = "status-dot-pulse")
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.3f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 700, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "status-dot-alpha",
        ).value
    } else {
        1f
    }

    // The Canvas takes the 8dp footprint that matches `.status-dot`'s
    // `width: 8px; height: 8px;` so callers' spacing stays correct.
    // Compose `Canvas` does NOT clip its drawing to its bounds, so we
    // can render the wider halo outside the 8dp box without changing
    // layout — same as CSS `box-shadow` extending past the element box.
    Canvas(modifier = modifier.size(8.dp)) {
        val coreRadius: Float = size.minDimension / 2f
        // Outer halo for Connected only, matching the CSS
        // `box-shadow: 0 0 7px rgba(34,197,94,0.7)`. Two stacked discs
        // give us a coarse radial falloff (outer dim, middle medium,
        // solid core on top) without needing a real blur shader.
        if (status == ConnectionStatus.Connected) {
            drawCircle(
                color = baseColor.copy(alpha = 0.18f),
                radius = coreRadius + 3.dp.toPx(),
            )
            drawCircle(
                color = baseColor.copy(alpha = 0.32f),
                radius = coreRadius + 1.5.dp.toPx(),
            )
        }
        drawCircle(
            color = baseColor.copy(alpha = alpha),
            radius = coreRadius,
        )
    }
}
