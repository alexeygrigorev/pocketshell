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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketshell.uikit.model.ConnectionStatus
import com.pocketshell.uikit.model.Crumb
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellSpacing

/**
 * Top-of-screen `host > session > pane` breadcrumb for the session view and
 * similar sub-screens.
 *
 * Layout:
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
        LabeledGlyphButton(glyph = "‹", contentDescription = "Back", onClick = onBack)

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

        LabeledGlyphButton(glyph = "⋮", contentDescription = "More options", onClick = onMore)
    }
}

/**
 * Shared 48dp semantic glyph control for breadcrumb and drawer chrome.
 * The visible glyph stays decorative; assistive technology receives the
 * explicit [contentDescription] and button action instead of punctuation.
 */
@Composable
fun LabeledGlyphButton(
    glyph: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .background(color = PocketShellColors.Background, shape = CircleShape)
            .clickable(role = Role.Button, onClickLabel = contentDescription, onClick = onClick)
            .clearAndSetSemantics {
                this.contentDescription = contentDescription
                role = Role.Button
                onClick(label = contentDescription) {
                    onClick()
                    true
                }
            }
            .padding(PaddingValues(0.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            color = PocketShellColors.TextSecondary,
            fontSize = 20.sp,
        )
    }
}
