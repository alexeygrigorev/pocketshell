package com.pocketshell.uikit.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketshell.uikit.model.ConnectionStatus
import com.pocketshell.uikit.model.HostStatus
import com.pocketshell.uikit.theme.JetBrainsMonoFamily
import com.pocketshell.uikit.theme.PocketShellColors

/**
 * Row item for the "Hosts" section of the dashboard. Matches
 * `.host-row` in `docs/mockups/styles.css` and the rows under "Hosts"
 * in `docs/mockups/dashboard.html`.
 *
 * Layout (per the CSS):
 * - 40dp avatar with the host's initial letter (`.avatar`)
 * - 12dp gap
 * - Two-line info: bright name + muted monospace `user@host:port`
 * - 8dp status dot at the trailing edge
 * - Outer card: `surface` background, `border-soft` 1dp border,
 *   14dp corner radius, 14dp vertical / 16dp horizontal padding,
 *   12dp horizontal margin handled by the caller (we don't bake
 *   margin into a component — that's the caller's layout decision).
 *
 * `name` is rendered as a 15sp `Text` with the first letter pulled
 * uppercased into the avatar. `subtitle` is rendered as monospace
 * 12sp and ellipsises if it doesn't fit. The status dot maps
 * [HostStatus] -> [ConnectionStatus] for the inner `StatusDot`:
 *
 * - [HostStatus.Connected] -> [ConnectionStatus.Connected]
 * - [HostStatus.Connecting] -> [ConnectionStatus.Connecting]
 * - [HostStatus.Disconnected] -> [ConnectionStatus.Idle]
 * - [HostStatus.Error] -> [ConnectionStatus.Error]
 */
@Composable
fun HostCard(
    name: String,
    subtitle: String,
    status: HostStatus,
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
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Avatar: 40dp rounded square with the first letter.
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(
                    color = PocketShellColors.SurfaceElev,
                    shape = RoundedCornerShape(12.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                // Empty-name safety: an empty avatar is fine; we don't
                // want to crash on a host with a blank label.
                text = name.take(1).uppercase(),
                color = PocketShellColors.TextSecondary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.5).sp,
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Name + subtitle. `weight(1f)` would be more idiomatic but
        // `Row` weight requires `RowScope`; we use `weight(1f)` via
        // the Row scope on Column directly below.
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                color = PocketShellColors.Text,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.size(2.dp))
            Text(
                text = subtitle,
                color = PocketShellColors.TextMuted,
                fontFamily = JetBrainsMonoFamily,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        StatusDot(status = status.toConnectionStatus())
    }
}

/**
 * Maps the host-list status enum onto the more general connection
 * status enum used by [StatusDot]. `Disconnected` maps to `Idle`
 * because the dot doesn't have a "known but offline" visual — the
 * mockup uses the muted grey idle dot for that case.
 */
private fun HostStatus.toConnectionStatus(): ConnectionStatus = when (this) {
    HostStatus.Connected -> ConnectionStatus.Connected
    HostStatus.Connecting -> ConnectionStatus.Connecting
    HostStatus.Disconnected -> ConnectionStatus.Idle
    HostStatus.Error -> ConnectionStatus.Error
}
