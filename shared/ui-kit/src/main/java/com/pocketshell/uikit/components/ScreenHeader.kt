package com.pocketshell.uikit.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType

/**
 * Shared screen header — the title block that sits atop the tree, host list,
 * sessions dashboard, settings, etc. Encodes the mockup's header pattern
 * (`folder-tree-target-20260604.png`) and the design language locked on #479:
 *
 * ```
 * ┌───────────────────────────────────────────────────────┐
 * │ Hosts                                      [⟳]  [+]    │
 * │ 4 hosts · 7 sessions                                   │
 * └───────────────────────────────────────────────────────┘
 * ```
 *
 * - **Title** rides [PocketShellType.bodyDense] bumped to SemiBold so it reads
 *   as the primary heading without pulling in the heavier M3 `headlineSmall`
 *   (the dev-tool density wants a tight header, not a marketing hero).
 * - **Subtitle** (optional) is the `N x · M y` facet line — muted, dense — the
 *   same count-subtitle vocabulary `ListRow`/`SectionHeader` use. Callers build
 *   the string (e.g. `"4 hosts · 7 sessions"`); this component does not invent
 *   pluralisation.
 * - **Trailing slot** (optional) is a right-aligned `@Composable` lambda for the
 *   header's toggles / actions (refresh, add, a density toggle, …). It lays out
 *   on the same baseline as the title so a row of icon buttons sits flush-right.
 *
 * Density follows [PocketShellDensity] horizontal/vertical row padding so the
 * header lines up with the rows beneath it. Colours stay on the always-dark raw
 * tokens (#477 single dark scheme) so the header never flips with the system
 * light setting.
 *
 * This is presentational only — no click handling on the header itself; wire
 * any affordance through [trailing].
 */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = PocketShellDensity.rowPadH,
                vertical = PocketShellDensity.rowPadV,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = PocketShellColors.Text,
                style = PocketShellType.bodyDense,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Spacer(modifier = Modifier.size(2.dp))
                Text(
                    text = subtitle,
                    color = PocketShellColors.TextMuted,
                    style = PocketShellType.bodyDense,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (trailing != null) {
            Spacer(modifier = Modifier.width(PocketShellSpacing.sm))
            Row(
                horizontalArrangement = Arrangement.spacedBy(PocketShellSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                trailing()
            }
        }
    }
}
