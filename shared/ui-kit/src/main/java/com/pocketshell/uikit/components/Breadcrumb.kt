package com.pocketshell.uikit.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketshell.uikit.model.ConnectionStatus
import com.pocketshell.uikit.model.Crumb
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellSpacing

/**
 * Top-of-screen breadcrumb for the session view and similar
 * sub-screens. Matches `.breadcrumb` in `docs/mockups/styles.css` and
 * the `host > session > pane` strip at the top of
 * `docs/mockups/session.html`.
 *
 * Layout (per the CSS):
 * - 56dp tall, 4dp left / 8dp right padding
 * - 36dp circular "back" button at the leading edge (`‹`)
 * - Optional green live dot (`box-shadow` glow) when [liveDot] is true
 * - Crumb segments with `›` separators between them. Current crumb is
 *   bright text + medium weight; others are secondary
 * - 36dp circular "more" button at the trailing edge (`⋮`)
 *
 * Each [Crumb] is tappable via its own `onClick` so the user can jump
 * to any ancestor.
 */
@Composable
fun Breadcrumb(
    crumbs: List<Crumb>,
    onBack: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    liveDot: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(color = PocketShellColors.Background)
            .height(56.dp)
            .padding(start = PocketShellSpacing.xs, end = PocketShellSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(label = "‹", onClick = onBack)

        if (liveDot) {
            Spacer(modifier = Modifier.width(PocketShellSpacing.xs))
            // The CSS uses `.live-dot { width: 7px }`; on Android we
            // reuse `StatusDot` (8dp) here on purpose:
            // - 1dp delta is invisible at typical Pixel densities and
            //   any browser-rasterised CSS pixel rounds to a half-step
            //   anyway, so the mockup's 7px and Android's 8dp resolve
            //   to the same on-screen footprint within Pixel 7's px
            //   pitch.
            // - Reusing `StatusDot` keeps a single source of truth for
            //   the connected-state glow recipe — if we ever tune the
            //   halo opacity, every consumer picks it up.
            // - Bumping the breadcrumb to its own 6dp/7dp dot would
            //   force duplicating the glow-rendering canvas just to
            //   shave a pixel.
            StatusDot(status = ConnectionStatus.Connected)
            Spacer(modifier = Modifier.width(6.dp))
        } else {
            Spacer(modifier = Modifier.width(PocketShellSpacing.xs))
        }

        // The crumb segments themselves. `weight(1f)` pushes the
        // "more" affordance to the trailing edge.
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PocketShellSpacing.xs),
        ) {
            crumbs.forEachIndexed { index, crumb ->
                Text(
                    text = crumb.label,
                    color = if (crumb.isCurrent) PocketShellColors.Text else PocketShellColors.TextSecondary,
                    // #461: crumb label snaps onto the body type rung (14sp).
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (crumb.isCurrent) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clickable(onClick = crumb.onClick)
                        .padding(horizontal = 2.dp, vertical = PocketShellSpacing.xs),
                )
                if (index < crumbs.lastIndex) {
                    Text(
                        text = "›",
                        color = PocketShellColors.TextMuted,
                        // #461: separator snaps onto the caption type rung (11sp).
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 2.dp),
                    )
                }
            }
        }

        IconButton(label = "⋮", onClick = onMore)
    }
}

/**
 * 36dp circular tap target with a single glyph. Used for the back and
 * "more" affordances. Kept private — these are exactly the visual
 * recipe in the mockup (`.breadcrumb .back` / `.breadcrumb .more`) and
 * not a general primitive yet.
 */
@Composable
private fun IconButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .background(color = PocketShellColors.Background, shape = CircleShape)
            .clickable(onClick = onClick)
            .padding(PaddingValues(0.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = PocketShellColors.TextSecondary,
            fontSize = 20.sp,
        )
    }
}
