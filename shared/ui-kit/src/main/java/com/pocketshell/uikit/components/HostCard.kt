package com.pocketshell.uikit.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketshell.uikit.model.HostStatus
import com.pocketshell.uikit.theme.JetBrainsMonoFamily
import com.pocketshell.uikit.theme.PocketShellColors

/**
 * Row item for the "Hosts" section of the dashboard. Originally matched
 * `.host-row` in `docs/mockups/styles.css`; updated under issue #113 to:
 *
 * - Pull the deterministic avatar colour from [HostAvatarColor] so
 *   several hosts beginning with the same letter are visually
 *   distinguishable in the list.
 * - Replace the trailing 8dp [StatusDot] with a small colour + text
 *   "status chip" — easier to read at a glance, no learned-glyph
 *   knowledge required.
 * - Drop the fixed `maxLines = 1` on the `user@host:port` subtitle so
 *   the card grows vertically when the system font scale truncates the
 *   line — the disambiguating target string stays visible exactly for
 *   the users who need it most.
 * - Accept an optional [trailingContent] slot (used by the caller for
 *   the kebab overflow button) and an optional [onLongClick] callback
 *   used by the same caller to surface the overflow menu via long-press.
 *
 * Layout (top-to-bottom, left-to-right):
 * - 40dp circular avatar with the host's initial letter on a hashed
 *   per-host hue (`HostAvatarColor.colorFor(name)`).
 * - 12dp gap.
 * - Two-line info: bright name + muted monospace `user@host:port` that
 *   wraps to as many lines as the font scale needs.
 * - Trailing status chip (colour + label).
 * - Optional trailing slot for caller-supplied affordances (e.g. the
 *   overflow kebab in `HostListScreen`).
 *
 * The outer card keeps `surface` background, `border-soft` 1dp border,
 * 14dp corner radius. Vertical padding stays 14dp; horizontal margin is
 * the caller's choice.
 *
 * The primary tap target is the whole card. Long-press is forwarded to
 * the caller via [onLongClick] — there is no built-in menu, by design
 * the card is presentational and the caller wires the menu against the
 * trailing slot.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HostCard(
    name: String,
    subtitle: String,
    status: HostStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
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
            .combinedClickable(
                role = Role.Button,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Avatar: 40dp circle filled with the deterministic per-host
        // colour. `remember(name)` so we don't re-hash on every
        // recomposition.
        val avatarColor = remember(name) { HostAvatarColor.colorFor(name) }
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(color = avatarColor, shape = CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                // Empty-name safety: an empty avatar is fine; we don't
                // want to crash on a host with a blank label.
                text = name.take(1).uppercase(),
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.5).sp,
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Name + subtitle. Column takes the flex space between the
        // avatar and the trailing status chip / overflow slot.
        //
        // Issue #113: the subtitle used to be hard-capped at one line
        // with `TextOverflow.Ellipsis`. At the largest system font
        // scale this truncated the disambiguating target string (e.g.
        // "test@127.0.0..."). We drop both caps so the card grows
        // vertically and the full `user@host:port` is visible — the
        // outer LazyColumn handles the extra height.
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                color = PocketShellColors.Text,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.size(2.dp))
            Text(
                text = subtitle,
                color = PocketShellColors.TextMuted,
                fontFamily = JetBrainsMonoFamily,
                fontSize = 12.sp,
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        HostStatusChip(status = status)

        if (trailingContent != null) {
            Spacer(modifier = Modifier.width(8.dp))
            trailingContent()
        }
    }
}

/**
 * Small colour + text chip rendering the connection state. Replaces the
 * 8dp [StatusDot] previously used at the trailing edge — the dot relied
 * on learned colour conventions ("amber = connecting") and read as
 * decoration to non-technical users. The chip says the same thing in
 * words.
 *
 * Mapping (issue #113):
 *
 * - [HostStatus.Connected]  -> green, "connected"
 * - [HostStatus.Connecting] -> amber, "connecting"
 * - [HostStatus.Disconnected] -> muted grey, "idle"
 * - [HostStatus.Error] -> red, "error"
 *
 * The chip uses an `Accent`-style accent-soft fill at low alpha derived
 * from the same status colour so the colour reads on the dark surface
 * without painting a solid block that competes with the rest of the
 * row. Border, text colour, and an inner dot use the full-strength
 * colour for legibility.
 */
@Composable
private fun HostStatusChip(status: HostStatus) {
    val (color, label) = when (status) {
        HostStatus.Connected -> PocketShellColors.Green to "connected"
        HostStatus.Connecting -> PocketShellColors.Amber to "connecting"
        HostStatus.Disconnected -> PocketShellColors.TextMuted to "idle"
        HostStatus.Error -> PocketShellColors.Red to "error"
    }
    Row(
        modifier = Modifier
            .background(
                color = color.copy(alpha = 0.16f),
                shape = RoundedCornerShape(999.dp),
            )
            .border(
                width = 1.dp,
                color = color.copy(alpha = 0.5f),
                shape = RoundedCornerShape(999.dp),
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(color = color, shape = CircleShape),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
