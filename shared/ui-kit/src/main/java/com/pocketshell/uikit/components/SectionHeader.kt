package com.pocketshell.uikit.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType

/**
 * Muted section label that groups rows below it (Hosts / Sessions / Settings
 * groups, …) — the small label-row the mockup uses above each list block.
 *
 * - The **label** uses `labelSmall`(11) SemiBold on the muted token — the
 *   established section-label vocabulary (the count/caption rung of the design
 *   language) — uppercased so it reads as a quiet group divider rather than a
 *   row.
 * - The optional **count** sits right-aligned (e.g. `7`), [PocketShellType.labelMono]
 *   on the muted token, so a section can show its size inline without a full
 *   subtitle line.
 *
 * Horizontal padding matches [PocketShellDensity.rowPadH] so the label lines up
 * with the rows beneath it; the vertical padding leans on
 * [PocketShellSpacing.sm] for a tight group gap. Colours stay on the always-dark
 * raw tokens (#477 single dark scheme).
 *
 * Presentational only.
 */
@Composable
fun SectionHeader(
    label: String,
    modifier: Modifier = Modifier,
    count: Int? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = PocketShellDensity.rowPadH,
                vertical = PocketShellSpacing.sm,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label.uppercase(),
            color = PocketShellColors.TextMuted,
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        if (count != null) {
            Spacer(modifier = Modifier.width(PocketShellSpacing.sm))
            Text(
                text = count.toString(),
                color = PocketShellColors.TextMuted,
                style = PocketShellType.labelMono,
            )
        }
    }
}
