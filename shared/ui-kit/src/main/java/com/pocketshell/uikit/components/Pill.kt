package com.pocketshell.uikit.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketshell.uikit.model.PillKind
import com.pocketshell.uikit.theme.PocketShellColors

/**
 * Small uppercase status pill. Matches `.pill.ok` / `.pill.warn` /
 * `.pill.blocked` / `.pill.error` in `docs/mockups/styles.css` (the
 * provider state badges in `docs/mockups/usage.html`).
 *
 * Visual recipe per the CSS:
 * - `padding: 3px 9px`
 * - `border-radius: 10px`
 * - `font-size: 10px`
 * - `font-weight: 700`
 * - `text-transform: uppercase`
 * - `letter-spacing: 0.6px`
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
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.6.sp,
        modifier = modifier
            .background(color = bgColor, shape = RoundedCornerShape(10.dp))
            .padding(horizontal = 9.dp, vertical = 3.dp),
    )
}
