package com.pocketshell.uikit.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.theme.LocalPocketShellSemantic
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellType

/**
 * Tappable monospace pill used in the in-session chip row. Matches
 * `.chip` and `.chip.icon-chip` from `docs/mockups/styles.css` and the
 * `chip-row` element in `docs/mockups/session.html` (`git status`,
 * `tmux ls`, etc., plus the accented `● dictate` icon chip).
 *
 * Tokens (#461 §3.5 chip/pill pattern): `small`(8) shape,
 * [PocketShellDensity.chipPadH]`(10)` / [PocketShellDensity.chipPadV]`(6)`
 * padding, [PocketShellType.bodyMono]`(13)` command text.
 *
 * Two visual modes selected automatically:
 *
 * - **No icon**: neutral `.chip` style — `Surface` background,
 *   `BorderSoft` 1dp border, monospace label, default text colour.
 * - **With icon**: `.chip.icon-chip` (active) style — `accentSoft`
 *   background, `accentDim` border, `accent` foreground, UI font (not
 *   mono). Used for the "dictate" entry point. The accent trio is sourced
 *   from [LocalPocketShellSemantic] so it follows the semantic role
 *   vocabulary rather than raw palette tokens.
 *
 * Touch floor (#461 Δ6): the visual padding is the compact chip density,
 * but the hit area is held at the [PocketShellDensity.tapTargetMin]`(48)`
 * a11y floor via `Modifier.minimumInteractiveComponentSize()`. That
 * primitive expands the *touch/semantics* bounds around the pill without
 * inflating the painted background, so shrinking the paint never drops the
 * tap target below the floor and the chip still renders as a compact pill.
 */
@Composable
fun CommandChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val hasIcon: Boolean = icon != null
    val semantic = LocalPocketShellSemantic.current

    // Neutral chips stay on the always-dark `Surface` / `BorderSoft` / `Text`
    // tokens (the chip row sits over the dark in-session surface and must not
    // flip with the system light theme); the active accent trio comes from the
    // semantic roles.
    val backgroundColor =
        if (hasIcon) semantic.accentSoft else PocketShellColors.Surface
    val borderColor =
        if (hasIcon) semantic.accentDim else PocketShellColors.BorderSoft
    val textColor =
        if (hasIcon) semantic.accent else PocketShellColors.Text

    Row(
        modifier = modifier
            // Touch floor (48dp): the chip paints at compact chip density but
            // reserves a 48dp minimum hit area so it stays above the a11y
            // floor without inflating the visible pill.
            .minimumInteractiveComponentSize()
            .background(color = backgroundColor, shape = PocketShellShapes.small)
            .border(
                border = BorderStroke(1.dp, borderColor),
                shape = PocketShellShapes.small,
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(
                horizontal = PocketShellDensity.chipPadH,
                vertical = PocketShellDensity.chipPadV,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(14.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text = label,
            color = textColor,
            // Mono `bodyDense`(13) for plain command chips; UI-sans `bodyDense`
            // for icon chips — the same font split the CSS makes (`.chip` uses
            // var(--font-mono), `.chip.icon-chip` overrides with var(--font-ui)).
            style = if (hasIcon) PocketShellType.bodyDense else PocketShellType.bodyMono,
            fontWeight = FontWeight.Medium,
        )
    }
}
