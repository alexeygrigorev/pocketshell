package com.pocketshell.uikit.render

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.components.Badge
import com.pocketshell.uikit.components.BadgeRole
import com.pocketshell.uikit.components.HostCard
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.model.HostStatus
import com.pocketshell.uikit.model.PillKind
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellType

@Composable
internal fun HostListScreenRender() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScreenHeader(
            title = "Hosts",
            subtitle = "5 hosts · 4 sessions",
            trailing = {
                Badge(label = "4 active", role = BadgeRole.Active, mono = false)
            },
        )
        HostCard(
            name = "hetzner",
            subtitle = "alex@65.108.42.11",
            status = HostStatus.Attached,
            onClick = {},
        )
        HostCard(
            name = "gpu-box",
            subtitle = "alex@10.0.0.42",
            status = HostStatus.ActiveSessions(count = 3),
            onClick = {},
        )
        HostCard(
            name = "prod",
            subtitle = "deploy@prod.acme.io",
            status = HostStatus.NoActiveSessions,
            onClick = {},
        )
        HostCard(
            name = "edge",
            subtitle = "ci@edge.acme.io",
            status = HostStatus.ConnectionError,
            onClick = {},
        )
    }
}

@Composable
internal fun HostCardResumeAffordanceRender() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HostCard(
            name = "hetzner",
            subtitle = "alex@65.108.42.11",
            status = HostStatus.Attached,
            onClick = {},
        )
        ResumeLastSessionRowFacsimile(sessionName = "claude-main")
    }
    HostCard(
        name = "gpu-box",
        subtitle = "alex@10.0.0.42",
        status = HostStatus.NoActiveSessions,
        onClick = {},
    )
}

@Composable
internal fun UsageGlancePillRender() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ScreenHeader(
            title = "Hosts",
            subtitle = "5 hosts · 4 active",
            trailing = {
                UsageGlancePillFacsimile(percent = "72%", dot = PillKind.Warn)
                AppBarPillFacsimile {
                    Text(
                        "2",
                        color = PocketShellColors.Text,
                        style = PocketShellType.bodyDense,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                AppBarGearFacsimile()
            },
        )
        ScreenHeader(
            title = "Hosts",
            subtitle = "5 hosts · 4 active",
            trailing = {
                UsageGlancePillFacsimile(percent = "63%", dot = PillKind.Ok, staleClock = "13:40")
                AppBarGearFacsimile()
            },
        )
    }
}

/**
 * Issue #1241: ui-kit facsimile of the app-module app-bar usage glance pill
 * ([com.pocketshell.app.usage.UsageGlancePill]) for the fast JVM render.
 * Mirrors the app pill's chrome so the design read is faithful; the emulator
 * screenshot is the acceptance.
 */
@Composable
private fun UsageGlancePillFacsimile(percent: String, dot: PillKind, staleClock: String? = null) {
    val alpha = if (staleClock != null) 0.6f else 1f
    val dotColor = when (dot) {
        PillKind.Ok -> PocketShellColors.Green
        PillKind.Warn -> PocketShellColors.Amber
        PillKind.Blocked -> PocketShellColors.Red
        PillKind.Error -> PocketShellColors.TextMuted
    }
    Row(
        modifier = Modifier
            .height(32.dp)
            .background(color = PocketShellColors.SurfaceElev, shape = PocketShellShapes.large)
            .border(width = 1.dp, color = PocketShellColors.BorderSoft, shape = PocketShellShapes.large)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(dotColor.copy(alpha = alpha)))
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = percent,
            color = PocketShellColors.Text.copy(alpha = alpha),
            style = PocketShellType.bodyDense,
            fontWeight = FontWeight.SemiBold,
        )
        if (staleClock != null) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = staleClock, color = PocketShellColors.TextMuted, style = PocketShellType.bodyDense)
        }
    }
}

@Composable
private fun AppBarPillFacsimile(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .height(32.dp)
            .background(color = PocketShellColors.SurfaceElev, shape = PocketShellShapes.large)
            .border(width = 1.dp, color = PocketShellColors.BorderSoft, shape = PocketShellShapes.large)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun AppBarGearFacsimile() {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(color = PocketShellColors.SurfaceElev)
            .border(width = 1.dp, color = PocketShellColors.BorderSoft, shape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text("⚙", color = PocketShellColors.TextSecondary)
    }
}

/**
 * Issue #1239: JVM facsimile of the app-module `ResumeLastSessionRow` — the
 * one-tap "Resume last session" affordance under the matching host card. Same
 * chrome as the production row (accent play glyph, bright "Resume" label,
 * muted-mono session name, AccentSoft fill + 40%-accent hairline on the
 * `medium` card shape) so the fast render is a faithful design read; the
 * emulator screenshot is the acceptance.
 */
@Composable
private fun ResumeLastSessionRowFacsimile(sessionName: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(color = PocketShellColors.AccentSoft, shape = PocketShellShapes.medium)
            .border(
                width = 1.dp,
                color = PocketShellColors.Accent.copy(alpha = 0.4f),
                shape = PocketShellShapes.medium,
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("▶", color = PocketShellColors.Accent, style = PocketShellType.bodyDense)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "Resume",
            color = PocketShellColors.Accent,
            style = PocketShellType.bodyDense,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = sessionName,
            color = PocketShellColors.TextSecondary,
            style = PocketShellType.bodyMono,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
