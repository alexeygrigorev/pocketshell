package com.pocketshell.uikit.render

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketshell.uikit.components.Badge
import com.pocketshell.uikit.components.BadgeRole
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.StatusDot
import com.pocketshell.uikit.model.ConnectionStatus
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellType

@Composable
internal fun HostDetailInactiveFoldersRender() {
    Column(
        modifier = Modifier.padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Active root group: header + an active project row, the "what an active
        // row looks like" reference the inactive callout is matched to.
        TreeRootHeader(label = "git", count = "10 projects · 14 sessions")
        ListRow(
            title = "pocketshell",
            subtitle = "~/git/pocketshell",
            leading = { StatusDot(status = ConnectionStatus.Connected) },
            trailing = { SubtleAccentPlus() },
            onClick = {},
            modifier = Modifier
                .background(
                    PocketShellColors.Surface.copy(alpha = 0.10f),
                    RoundedCornerShape(4.dp),
                )
                .padding(start = 16.dp),
        )

        // Inactive-root callout: has scanned candidate folders ("Review").
        // Single-line: the title names the state, the muted idle dot signals
        // inactive, and the trailing `+` is the affordance; no truncated
        // instructional subtitle (#603 / #679 Child D).
        TreeRootHeader(label = "archive", count = "3 projects")
        ListRow(
            title = "3 inactive folders",
            leading = { StatusDot(status = ConnectionStatus.Idle) },
            trailing = { SubtleAccentPlus() },
            onClick = {},
            modifier = Modifier
                .background(
                    PocketShellColors.Surface.copy(alpha = 0.10f),
                    RoundedCornerShape(4.dp),
                )
                .padding(start = 16.dp),
        )

        // Empty-root callout: no candidate folders yet ("Add").
        TreeRootHeader(label = "labs", count = "0 projects")
        ListRow(
            title = "No folders yet",
            leading = { StatusDot(status = ConnectionStatus.Idle) },
            trailing = { SubtleAccentPlus() },
            onClick = {},
            modifier = Modifier
                .background(
                    PocketShellColors.Surface.copy(alpha = 0.10f),
                    RoundedCornerShape(4.dp),
                )
                .padding(start = 16.dp),
        )
    }
}

@Composable
internal fun HostDetailProfiledSessionTreeRender() {
    Column(
        modifier = Modifier.padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TreeRootHeader(label = "git", count = "2 agents")

        // z.ai Claude: profile chip + agent badge (the #858 distinction).
        ListRow(
            title = "git-zai-app",
            leading = { StatusDot(status = ConnectionStatus.Connected) },
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProfileChipMirror(label = "Z.AI")
                    Spacer(modifier = Modifier.width(4.dp))
                    Badge(label = "Claude", role = BadgeRole.Agent)
                }
            },
            onClick = {},
            modifier = Modifier
                .background(
                    PocketShellColors.Surface.copy(alpha = 0.10f),
                    RoundedCornerShape(4.dp),
                )
                .padding(start = 16.dp),
        )

        // Default Claude: agent badge only, NO chip.
        ListRow(
            title = "git-default-app",
            leading = { StatusDot(status = ConnectionStatus.Connected) },
            trailing = { Badge(label = "Claude", role = BadgeRole.Agent) },
            onClick = {},
            modifier = Modifier
                .background(
                    PocketShellColors.Surface.copy(alpha = 0.10f),
                    RoundedCornerShape(4.dp),
                )
                .padding(start = 16.dp),
        )
    }
}

@Composable
internal fun HostDetailMultiWindowAgentTreeRender() {
    Column(
        modifier = Modifier.padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Header counts agent WINDOWS: this folder's one session has two
        // Claude windows, so "2 agents" (the bug showed "1 agent").
        TreeRootHeader(
            label = "ai-shipping-labs-workshops-raw",
            count = "2 agents",
        )

        // A single-window agent session KEEPS its concise trailing badge.
        ListRow(
            title = "git-pocketshell-c",
            leading = { StatusDot(status = ConnectionStatus.Connected) },
            trailing = { Badge(label = "Claude", role = BadgeRole.Agent) },
            onClick = {},
            modifier = Modifier
                .background(
                    PocketShellColors.Surface.copy(alpha = 0.10f),
                    RoundedCornerShape(4.dp),
                )
                .padding(start = 16.dp),
        )

        // Multi-window PARENT row: NO inline window summary, NO trailing badge;
        // the window child rows below carry the detail.
        ListRow(
            title = "git-ai-shipping-labs-workshops-raw",
            leading = { StatusDot(status = ConnectionStatus.Connected) },
            onClick = {},
            modifier = Modifier
                .background(
                    PocketShellColors.Surface.copy(alpha = 0.10f),
                    RoundedCornerShape(4.dp),
                )
                .padding(start = 16.dp),
        )

        // Issue #782: each window is now a `<session> [wN]` switcher entry: a
        // sibling of the single-window session rows, tapped to attach to THAT
        // window. The `[wN]` suffix disambiguates the window; the command hint
        // trails. (PocketShell no longer manages windows; these entries only
        // surface windows created OUTSIDE the app.)
        DeclutteredWindowRow(title = "git-ai-shipping-labs-workshops-raw [w0] claude")
        DeclutteredWindowRow(title = "git-ai-shipping-labs-workshops-raw [w1] claude")
    }
}

/** Mirror of the app's neutral-accent #858 `ProfileChip` for the render. */
@Composable
private fun ProfileChipMirror(label: String) {
    Box(
        modifier = Modifier
            .background(
                PocketShellColors.SurfaceElev.copy(alpha = 0.9f),
                RoundedCornerShape(6.dp),
            )
            .border(1.dp, PocketShellColors.BorderSoft, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            color = PocketShellColors.TextSecondary,
            style = PocketShellType.labelMono,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Mirror of the app's `WorkspaceSessionWindowRow` AFTER #782: an indented
 * per-window switcher entry that leads with a status dot and the
 * `<session> [wN] claude` title (issue #782's `[wN]` suffix) and carries NO
 * trailing agent badge (the dot + the title already name the agent; repeating
 * it on a badge was the third duplication, #675).
 */
@Composable
private fun DeclutteredWindowRow(title: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 40.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(status = ConnectionStatus.Connected)
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = title,
            color = PocketShellColors.Text,
            style = PocketShellType.bodyDense,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** Mirror of the app's tree-root header (title + muted-mono count subtitle). */
@Composable
private fun TreeRootHeader(label: String, count: String) {
    Column(modifier = Modifier.padding(horizontal = 2.dp)) {
        Text(
            text = label,
            color = PocketShellColors.Text,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = count,
            color = PocketShellColors.TextMuted,
            style = PocketShellType.labelMono,
        )
    }
}

/** Mirror of the app's `SubtleAddButton`: a bare accent `+`, no chrome. */
@Composable
private fun SubtleAccentPlus() {
    Box(
        modifier = Modifier.width(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "+",
            color = PocketShellColors.Accent,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
