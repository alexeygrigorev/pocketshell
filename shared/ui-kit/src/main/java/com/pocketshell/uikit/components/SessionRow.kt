package com.pocketshell.uikit.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketshell.uikit.model.Tag
import com.pocketshell.uikit.model.TagKind
import com.pocketshell.uikit.theme.JetBrainsMonoFamily
import com.pocketshell.uikit.theme.PocketShellColors

/**
 * Row item for the "Sessions" section of the dashboard. Matches
 * `.session-row` in `docs/mockups/styles.css` and the rows under
 * "Sessions" in `docs/mockups/dashboard.html`.
 *
 * Layout (per the CSS, `align-items: flex-start` because the row is
 * taller than `HostCard`):
 * - 38dp accent badge with a mono letter (the session's initial)
 * - 12dp gap
 * - Body column:
 *   - Top row: name + ` · host` muted mono suffix, timestamp at trailing edge
 *   - Preview line: mono, secondary colour, ellipsis if too long
 *   - Tags row: 6dp-spaced [Tag] pills via [TagChip]
 *
 * The card chrome (surface, border-soft, 14dp radius, 16dp h / 14dp v
 * padding) matches `.session-row` directly.
 */
@Composable
fun SessionRow(
    badge: String,
    name: String,
    host: String,
    preview: String,
    time: String,
    tags: List<Tag>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(color = PocketShellColors.Surface, shape = RoundedCornerShape(14.dp))
            .border(
                width = 1.dp,
                color = PocketShellColors.BorderSoft,
                shape = RoundedCornerShape(14.dp),
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        // `flex-start` in the CSS: the badge sits at the top, not the
        // middle of the multi-line body.
        verticalAlignment = Alignment.Top,
    ) {
        // Accent badge — 38dp, accent-soft fill, accent foreground, mono.
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(
                    color = PocketShellColors.AccentSoft,
                    shape = RoundedCornerShape(10.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = badge.take(1).uppercase(),
                color = PocketShellColors.Accent,
                fontFamily = JetBrainsMonoFamily,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            // Top row: name+host on the left, time on the right.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = name,
                        color = PocketShellColors.Text,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = " · $host",
                        color = PocketShellColors.TextMuted,
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = time,
                    color = PocketShellColors.TextMuted,
                    fontSize = 11.sp,
                )
            }

            Spacer(modifier = Modifier.size(6.dp))

            // Preview — single-line monospace truncation.
            Text(
                text = preview,
                color = PocketShellColors.TextSecondary,
                fontFamily = JetBrainsMonoFamily,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            if (tags.isNotEmpty()) {
                Spacer(modifier = Modifier.size(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    tags.forEach { tag -> TagChip(tag = tag) }
                }
            }
        }
    }
}

/**
 * Uppercase tag pill rendered inside a `SessionRow`. Matches `.tag` and
 * its `.agent` / `.deploy` / `.ml` variants. Kept private to this file
 * because tags only render in this context — if a downstream caller
 * needs standalone tag rendering later, promote this.
 */
@Composable
private fun TagChip(tag: Tag) {
    val (textColor: Color, bgColor: Color) = when (tag.kind) {
        TagKind.Default -> PocketShellColors.TextMuted to PocketShellColors.SurfaceElev
        TagKind.Agent -> PocketShellColors.Accent to PocketShellColors.AccentSoft
        TagKind.Deploy -> PocketShellColors.Amber to PocketShellColors.Amber.copy(alpha = 0.12f)
        TagKind.Ml -> PocketShellColors.Purple to PocketShellColors.Purple.copy(alpha = 0.12f)
    }

    Text(
        text = tag.label.uppercase(),
        color = textColor,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.5.sp,
        modifier = Modifier
            .background(color = bgColor, shape = RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}
