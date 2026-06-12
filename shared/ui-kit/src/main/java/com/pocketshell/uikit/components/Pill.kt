package com.pocketshell.uikit.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.pocketshell.uikit.model.PillKind
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellShapes

/**
 * Small uppercase status pill. Matches `.pill.ok` / `.pill.warn` /
 * `.pill.blocked` / `.pill.error` in `docs/mockups/styles.css` (the
 * provider state badges in `docs/mockups/usage.html`).
 *
 * Visual recipe (CSS reference + the #461 token migration that snaps it onto
 * the design ladder, matching the sibling [Badge] in the same pill family):
 * - shape: `PocketShellShapes.small` (8dp) — Pill reads as a small status chip
 *   (same chip vocabulary as `.chip`/`.key`/[Badge]), NOT a fully-rounded
 *   capsule, so it snaps to the chip rung rather than the CSS's off-ladder 10px.
 * - padding: [PocketShellDensity.chipPadH] (10dp) / [PocketShellDensity.chipPadV]
 *   (6dp) — the shared chip-padding rung, replacing the off-grid 9px/3px so Pill
 *   and [Badge] share one chip footprint.
 * - type: `labelSmall` (11sp Medium) — the nearest type-ladder rung to the CSS's
 *   off-ladder 10px caption. Bold weight + 0.6sp uppercase letter-spacing are
 *   kept as the component's intent on top of the ladder size/family.
 *
 * Colours come from [kind] — see [PillKind] for the four variants.
 * Background is always a 12%-alpha tint of the foreground (per the CSS
 * `rgba(..., 0.12)` rules), except [PillKind.Error] which uses the
 * surface-elev neutral colour.
 */
@Composable
fun Pill(
    label: String,
    kind: PillKind,
    modifier: Modifier = Modifier,
) {
    val (textColor: Color, bgColor: Color) = when (kind) {
        PillKind.Ok -> PocketShellColors.Green to PocketShellColors.Green.copy(alpha = 0.12f)
        PillKind.Warn -> PocketShellColors.Amber to PocketShellColors.Amber.copy(alpha = 0.12f)
        PillKind.Blocked -> PocketShellColors.Red to PocketShellColors.Red.copy(alpha = 0.12f)
        PillKind.Error -> PocketShellColors.TextMuted to PocketShellColors.SurfaceElev
    }

    Text(
        text = label.uppercase(),
        color = textColor,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.6.sp,
        modifier = modifier
            .background(color = bgColor, shape = PocketShellShapes.small)
            .padding(
                horizontal = PocketShellDensity.chipPadH,
                vertical = PocketShellDensity.chipPadV,
            ),
    )
}
