package com.pocketshell.uikit.render

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketshell.uikit.theme.JetBrainsMonoFamily
import com.pocketshell.uikit.theme.PocketShellColors

/**
 * Issue #1449: the design-token / hex swatch sheet. This is the ONE coverage gap
 * ported over when the redundant IDE-only `@Preview` composables in
 * `shared/ui-kit/src/main/.../preview/` were deleted (hard-cut D22; DesignRenders
 * / #555 is the sanctioned fast-render path). Every other preview was already
 * covered by DesignRenders' cases in richer contexts; the color-ramp swatch sheet
 * (Surface / Text / Accent / Status / Terminal groups, each swatch labelled with
 * its `0xAARRGGBB` hex) had no equivalent, so it lives here now.
 *
 * The render lets a reviewer cross-reference every palette token in
 * `theme/Color.kt` against the hex literals in `docs/mockups/` without booting
 * Android Studio's preview renderer.
 */
@Composable
internal fun DesignTokenSwatchesRender() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SwatchCard(title = "Surface") {
            Swatch(name = "Background", color = PocketShellColors.Background)
            Swatch(name = "Surface", color = PocketShellColors.Surface)
            Swatch(name = "SurfaceElev", color = PocketShellColors.SurfaceElev)
            Swatch(name = "Border", color = PocketShellColors.Border)
            Swatch(name = "BorderSoft", color = PocketShellColors.BorderSoft)
        }
        SwatchCard(title = "Text") {
            Swatch(name = "Text", color = PocketShellColors.Text)
            Swatch(name = "TextSecondary", color = PocketShellColors.TextSecondary)
            Swatch(name = "TextMuted", color = PocketShellColors.TextMuted)
        }
        SwatchCard(title = "Accent") {
            Swatch(name = "Accent", color = PocketShellColors.Accent)
            Swatch(name = "AccentSoft (12% alpha)", color = PocketShellColors.AccentSoft)
            Swatch(name = "AccentDim", color = PocketShellColors.AccentDim)
            Swatch(name = "OnAccent", color = PocketShellColors.OnAccent)
        }
        SwatchCard(title = "Status") {
            Swatch(name = "Green", color = PocketShellColors.Green)
            Swatch(name = "Amber", color = PocketShellColors.Amber)
            Swatch(name = "Red", color = PocketShellColors.Red)
            Swatch(name = "Purple", color = PocketShellColors.Purple)
        }
        SwatchCard(title = "Terminal") {
            Swatch(name = "TermBg", color = PocketShellColors.TermBg)
            Swatch(name = "TermText", color = PocketShellColors.TermText)
            Swatch(name = "TermPrompt", color = PocketShellColors.TermPrompt)
            Swatch(name = "TermComment", color = PocketShellColors.TermComment)
            // A tiny mono sample so the alias is seen to resolve to a monospace
            // family (today the system mono; a bundled JetBrains Mono later).
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .background(PocketShellColors.TermBg, RoundedCornerShape(6.dp))
                        .border(1.dp, PocketShellColors.Border, RoundedCornerShape(6.dp)),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "$ echo hi",
                    color = PocketShellColors.TermPrompt,
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/**
 * Card-shaped container with a title row and a vertical stack of [Swatch]es,
 * mirroring the mockups' `.host-row` / `.session-row` card style (1dp border on a
 * medium-corner surface) so swatches read in context, not floating on a void.
 */
@Composable
private fun SwatchCard(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.medium,
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = MaterialTheme.shapes.medium,
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = PocketShellColors.TextMuted,
        )
        content()
    }
}

/**
 * One row in a swatch card: a 20dp colour chip + the token's name + the
 * `0xAARRGGBB` hex string of the colour for cross-referencing against the spec.
 */
@Composable
private fun Swatch(name: String, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .background(color = color, shape = RoundedCornerShape(6.dp))
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline,
                    shape = RoundedCornerShape(6.dp),
                ),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = name,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = color.toArgbHexString(),
            color = PocketShellColors.TextMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )
    }
}

/**
 * Formats the colour as `0xAARRGGBB` so a reviewer can match it against the hex
 * literals in `theme/Color.kt`. Uses local ARGB arithmetic (same as Compose's
 * internal `toArgb`) to stay independent of any Compose-version symbol shift.
 */
private fun Color.toArgbHexString(): String {
    val a = (alpha * 255.0f + 0.5f).toInt() and 0xFF
    val r = (red * 255.0f + 0.5f).toInt() and 0xFF
    val g = (green * 255.0f + 0.5f).toInt() and 0xFF
    val b = (blue * 255.0f + 0.5f).toInt() and 0xFF
    val argb = (a shl 24) or (r shl 16) or (g shl 8) or b
    return "0x%08X".format(argb.toLong() and 0xFFFFFFFFL)
}
