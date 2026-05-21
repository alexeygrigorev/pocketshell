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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketshell.uikit.theme.JetBrainsMonoFamily
import com.pocketshell.uikit.theme.PocketShellColors

/**
 * Tappable monospace pill used in the in-session chip row. Matches
 * `.chip` and `.chip.icon-chip` from `docs/mockups/styles.css` and the
 * `chip-row` element in `docs/mockups/session.html` (`git status`,
 * `tmux ls`, etc., plus the accented `● dictate` icon chip).
 *
 * Two visual modes selected automatically:
 *
 * - **No icon**: neutral `.chip` style — `surface` background,
 *   `border-soft` 1dp border, monospace label, default text colour.
 * - **With icon**: `.chip.icon-chip` style — accent-soft background,
 *   accent-dim border, accent foreground, UI font (not mono). Used
 *   for the "dictate" entry point.
 */
@Composable
fun CommandChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val hasIcon: Boolean = icon != null

    val backgroundColor = if (hasIcon) PocketShellColors.AccentSoft else PocketShellColors.Surface
    val borderColor = if (hasIcon) PocketShellColors.AccentDim else PocketShellColors.BorderSoft
    val textColor = if (hasIcon) PocketShellColors.Accent else PocketShellColors.Text

    Row(
        modifier = modifier
            .background(color = backgroundColor, shape = RoundedCornerShape(8.dp))
            .border(
                border = BorderStroke(1.dp, borderColor),
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
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
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            // Mono for plain chips, UI sans for icon chips — same split
            // the CSS makes (`.chip` uses var(--font-mono), `.chip.icon-chip`
            // overrides with var(--font-ui)).
            fontFamily = if (hasIcon) null else JetBrainsMonoFamily,
        )
    }
}
