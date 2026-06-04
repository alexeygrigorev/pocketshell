package com.pocketshell.uikit.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType

/**
 * The core compact list row — the one source of truth for every dense row in
 * the app (host list, sessions, settings, conversation list, port-forward
 * panel, …). Encodes the mockup's row pattern
 * (`folder-tree-target-20260604.png`) and the design language locked on #479:
 *
 * ```
 * ┌──────────────────────────────────────────────────────┐
 * │ ● │  agent-main                       [Claude]  [⋮]   │   <- leading / title / trailing
 * │   │  ~/proj/agent                                     │   <- subtitle (mono, muted)
 * └──────────────────────────────────────────────────────┘
 * ```
 *
 * Slots (every visual region is a caller-supplied lambda so screens compose
 * their own status dot / avatar / badge / kebab without re-encoding the row):
 *
 * - **[leading]** (optional) — status dot ([StatusDot]) / avatar / icon. Pass
 *   `null` for a flush-left title (e.g. settings rows).
 * - **title** — the primary scan target, [PocketShellType.bodyDense]`(13)`
 *   Medium on the bright text token.
 * - **[subtitle]** (optional) — paths / IDs / `user@host`, rendered
 *   [PocketShellType.bodyMono]`(13)` on the muted token. Single line, ellipsised.
 * - **[trailing]** (optional) — badge ([Badge]) / count / kebab ([Kebab]). One
 *   overflow affordance per row (design language: avoid multiple inline action
 *   buttons).
 *
 * ### Density vs. touch floor (#461 Δ6)
 *
 * The row **paints compact**: [PocketShellDensity.rowMinHeight]`(44)` floor,
 * [PocketShellDensity.rowPadV]`(8)` / [PocketShellDensity.rowPadH]`(12)` padding
 * — so more rows fit per screen. When [onClick] is set the whole row becomes the
 * tap target and its minimum height is raised to the
 * [PocketShellDensity.tapTargetMin]`(48)` a11y floor via
 * `Modifier.defaultMinSize`. **The floor is baked in here** so consuming screens
 * cannot regress the hit area below 48dp by accident — that is the whole point
 * of extracting this row into ui-kit. (The 44dp paint floor still applies for
 * the non-clickable / decorative case.)
 *
 * Colours stay on the always-dark raw tokens (#477 single dark scheme) so the
 * row never flips with the system light setting.
 */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    // Visual paint floor is the compact 44dp row height; when the row is
    // tappable the floor is raised to the 48dp a11y touch floor. Baking the
    // floor in here is the contract: screens consuming ListRow cannot drop the
    // hit area below 48dp.
    val minHeight =
        if (onClick != null) PocketShellDensity.tapTargetMin else PocketShellDensity.rowMinHeight

    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = minHeight)
            .then(
                if (onClick != null) {
                    Modifier.clickable(role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(
                horizontal = PocketShellDensity.rowPadH,
                vertical = PocketShellDensity.rowPadV,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            // A fixed-width leading box keeps every row's title left edge
            // aligned regardless of whether the leading slot is an 8dp dot or a
            // wider glyph, so a stacked list reads as a clean column.
            Box(contentAlignment = Alignment.Center) {
                leading()
            }
            Spacer(modifier = Modifier.width(PocketShellSpacing.md))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = PocketShellColors.Text,
                style = PocketShellType.bodyDense,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Spacer(modifier = Modifier.size(2.dp))
                Text(
                    text = subtitle,
                    color = PocketShellColors.TextMuted,
                    style = PocketShellType.bodyMono,
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
