package com.pocketshell.uikit.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketshell.uikit.theme.JetBrainsMonoFamily
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme

/**
 * Visual sanity checks for the design tokens. These previews exist so a
 * reviewer can render the swatches in Android Studio without booting the
 * app and compare them against `docs/mockups/index.html` side-by-side.
 *
 * Five previews, one per token group:
 *
 * - Surface ramp (background, surface, surface-elev, borders)
 * - Text ramp (primary / secondary / muted on background)
 * - Accent (accent, accent-soft, accent-dim, on-accent)
 * - Status (green, amber, red, purple)
 * - Terminal (term bg + prompt + comment colours)
 */

@Preview(name = "Surface ramp", showBackground = false)
@Composable
fun SurfaceSwatchesPreview() {
    PocketShellTheme {
        SwatchCard(title = "Surface") {
            Swatch(name = "Background", color = PocketShellColors.Background)
            Swatch(name = "Surface", color = PocketShellColors.Surface)
            Swatch(name = "SurfaceElev", color = PocketShellColors.SurfaceElev)
            Swatch(name = "Border", color = PocketShellColors.Border)
            Swatch(name = "BorderSoft", color = PocketShellColors.BorderSoft)
        }
    }
}

@Preview(name = "Text ramp", showBackground = false)
@Composable
fun TextSwatchesPreview() {
    PocketShellTheme {
        SwatchCard(title = "Text") {
            Swatch(name = "Text", color = PocketShellColors.Text)
            Swatch(name = "TextSecondary", color = PocketShellColors.TextSecondary)
            Swatch(name = "TextMuted", color = PocketShellColors.TextMuted)
        }
    }
}

@Preview(name = "Accent", showBackground = false)
@Composable
fun AccentSwatchesPreview() {
    PocketShellTheme {
        SwatchCard(title = "Accent") {
            Swatch(name = "Accent", color = PocketShellColors.Accent)
            Swatch(name = "AccentSoft (12% alpha)", color = PocketShellColors.AccentSoft)
            Swatch(name = "AccentDim", color = PocketShellColors.AccentDim)
            Swatch(name = "OnAccent", color = PocketShellColors.OnAccent)
        }
    }
}

@Preview(name = "Status", showBackground = false)
@Composable
fun StatusSwatchesPreview() {
    PocketShellTheme {
        SwatchCard(title = "Status") {
            Swatch(name = "Green", color = PocketShellColors.Green)
            Swatch(name = "Amber", color = PocketShellColors.Amber)
            Swatch(name = "Red", color = PocketShellColors.Red)
            Swatch(name = "Purple", color = PocketShellColors.Purple)
        }
    }
}

@Preview(name = "Terminal", showBackground = false)
@Composable
fun TerminalSwatchesPreview() {
    PocketShellTheme {
        SwatchCard(title = "Terminal") {
            Swatch(name = "TermBg", color = PocketShellColors.TermBg)
            Swatch(name = "TermText", color = PocketShellColors.TermText)
            Swatch(name = "TermPrompt", color = PocketShellColors.TermPrompt)
            Swatch(name = "TermComment", color = PocketShellColors.TermComment)
            // Tiny sample of mono text rendered with the alias so the
            // reviewer can see the family resolves to something monospaced
            // (today it falls back to the system mono; tomorrow it'll be
            // bundled JetBrains Mono).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 28.dp, height = 28.dp)
                        .background(PocketShellColors.TermBg, RoundedCornerShape(6.dp))
                        .border(
                            width = 1.dp,
                            color = PocketShellColors.Border,
                            shape = RoundedCornerShape(6.dp),
                        ),
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

// -----------------------------------------------------------------------------
// Private preview helpers — only used by the swatches above.
// -----------------------------------------------------------------------------

/**
 * Renders a card-shaped container with a title row and a vertical stack of
 * [Swatch]es. Mimics the `.host-row` / `.session-row` card style from the
 * mockups (1dp border on a 14dp-corner surface) so reviewers see swatches in
 * context, not floating on a void.
 */
@Composable
private fun SwatchCard(title: String, content: @Composable () -> Unit) {
    // Wrap in a full-bleed Surface so the preview canvas paints the app
    // background colour, not Compose's default Magic White.
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
    ) {
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
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = PocketShellColors.TextMuted,
            )
            content()
        }
    }
}

/**
 * One row in a swatch card: a 28dp colour chip + the token's name + the
 * ARGB hex string of the colour for cross-referencing against the spec.
 */
@Composable
private fun Swatch(name: String, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(color = color, shape = RoundedCornerShape(6.dp))
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline,
                    shape = RoundedCornerShape(6.dp),
                ),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = name,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = color.toArgbHexString(),
                color = PocketShellColors.TextMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        }
    }
    Spacer(modifier = Modifier.height(0.dp))
}

private fun Color.toArgbHexString(): String {
    // Compose's `Color.value` is a packed `ULong`. Use the Android-style
    // ARGB int and format as 0xAARRGGBB so reviewers can match against the
    // hex literals in Color.kt.
    val argb: Int = (toArgb())
    return "0x%08X".format(argb.toLong() and 0xFFFFFFFFL)
}

// Helper kept in this file (rather than pulled from compose-ui) so the preview
// module has zero dependency on `androidx.compose.ui.graphics.toArgb` shifting
// around between Compose versions. Same arithmetic as Compose's internal
// implementation.
private fun Color.toArgb(): Int {
    val a = (alpha * 255.0f + 0.5f).toInt() and 0xFF
    val r = (red * 255.0f + 0.5f).toInt() and 0xFF
    val g = (green * 255.0f + 0.5f).toInt() and 0xFF
    val b = (blue * 255.0f + 0.5f).toInt() and 0xFF
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}
