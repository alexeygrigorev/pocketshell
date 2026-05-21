package com.pocketshell.uikit.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketshell.uikit.model.MicButtonState
import com.pocketshell.uikit.theme.PocketShellColors

/**
 * Round microphone button at the leading edge of the prompt composer.
 * Matches `.mic-btn` in `docs/mockups/styles.css` and the recording
 * button in `docs/mockups/composer.html`.
 *
 * Visual recipe (per the CSS):
 * - 56dp diameter, fully round (28dp radius)
 * - Accent (cyan) background, dark on-accent foreground glyph
 * - Soft accent-coloured drop shadow on the idle/recording states,
 *   approximating the CSS `box-shadow: 0 8px 26px rgba(34,211,238,0.45)`.
 *   The shadow is rendered via `Modifier.shadow` with an accent-tinted
 *   spot/ambient colour. Note: shadow tinting on API 26 falls back to a
 *   black shadow (the spot/ambient colour parameters require API 28+),
 *   which still gives the correct elevation cue. [MicButtonState.Disabled]
 *   renders without a shadow because the disabled state has no visual
 *   weight in the mockup.
 *
 * State behaviour:
 * - [MicButtonState.Idle] — accent fill with drop shadow, no animation.
 *   Tappable.
 * - [MicButtonState.Recording] — accent fill with drop shadow that
 *   pulses opacity (1.0 -> 0.55) on a 900ms cycle. Tappable to stop
 *   recording.
 * - [MicButtonState.Disabled] — surface-elev fill, muted glyph, no
 *   shadow, not tappable (callback is wired but `clickable` is disabled).
 *
 * The glyph is a filled circle (`●`) sized to look like a microphone
 * icon's body. When PocketShell bundles a proper icon set the glyph
 * will be replaced — the surface is structured to swap in an
 * `ImageVector` without touching the call sites.
 */
@Composable
fun MicButton(
    state: MicButtonState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled: Boolean = state != MicButtonState.Disabled

    val baseColor: Color = when (state) {
        MicButtonState.Disabled -> PocketShellColors.SurfaceElev
        MicButtonState.Idle, MicButtonState.Recording -> PocketShellColors.Accent
    }

    val glyphColor: Color = when (state) {
        MicButtonState.Disabled -> PocketShellColors.TextMuted
        MicButtonState.Idle, MicButtonState.Recording -> PocketShellColors.OnAccent
    }

    // Pulse only while recording — `rememberInfiniteTransition` adds
    // a permanent animation graph, so we gate the animateFloat call
    // behind the state branch.
    val pulseAlpha: Float = if (state == MicButtonState.Recording) {
        val transition = rememberInfiniteTransition(label = "mic-pulse")
        val v by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.55f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 450, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "mic-pulse-alpha",
        )
        v
    } else {
        1f
    }

    // CSS uses `box-shadow: 0 8px 26px rgba(34,211,238,0.45)`. Compose
    // expresses drop shadows via `Modifier.shadow(elevation, shape, ...)`.
    // 8dp elevation reproduces the y-offset / spread reasonably on the
    // platform shadow renderer, with the accent colour piped in as both
    // ambient and spot tint (API 28+). Disabled state skips the shadow.
    val shadowModifier: Modifier = if (state == MicButtonState.Disabled) {
        Modifier
    } else {
        Modifier.shadow(
            elevation = 8.dp,
            shape = CircleShape,
            ambientColor = PocketShellColors.Accent,
            spotColor = PocketShellColors.Accent,
        )
    }

    Box(
        modifier = modifier
            .size(56.dp)
            .then(shadowModifier)
            .background(
                color = baseColor.copy(alpha = baseColor.alpha * pulseAlpha),
                shape = CircleShape,
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "●",
            color = glyphColor,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
