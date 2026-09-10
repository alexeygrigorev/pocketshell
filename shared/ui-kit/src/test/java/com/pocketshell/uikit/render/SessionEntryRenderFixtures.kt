package com.pocketshell.uikit.render

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.components.Kebab
import com.pocketshell.uikit.components.KebabItem
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.components.SectionHeader
import com.pocketshell.uikit.components.SessionTab
import com.pocketshell.uikit.components.SessionTabState
import com.pocketshell.uikit.components.SessionTabStrip
import com.pocketshell.uikit.components.WorkspaceRow
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType

/**
 * Issue #2632: the in-session tab strip, rendering the REAL
 * [SessionTabStrip] — not a facsimile. The component lives in the ui-kit
 * precisely so this render is the production widget under the production
 * theme; the app2 session screen composes exactly this.
 *
 * Four sibling sessions in one workspace, the second selected: a filled
 * `SurfaceElev` chip with an `Accent` underline, working/needs-input/idle dots
 * on the rest, then `+` (new session) and `⋯` (the full switcher sheet, which
 * still owns per-session status text and Stop).
 */
@Composable
internal fun SessionTabStripRender() {
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        // The strip as it sits on the session screen: under the header, above
        // the terminal.
        TerminalHeaderFacsimile(title = "pocketshell", subtitle = "hetzner · Connected")
        SessionTabStrip(
            tabs = listOf(
                SessionTab("pocketshell:main", "main", SessionTabState.Working),
                SessionTab("pocketshell:review", "review", SessionTabState.NeedsInput),
                SessionTab("pocketshell:shell", "Terminal", SessionTabState.Idle),
                SessionTab("pocketshell:renders", "renders", SessionTabState.Idle),
            ),
            selectedId = "pocketshell:review",
            onSelect = {},
            onNewTab = {},
            onOverflow = {},
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .background(PocketShellColors.TermBg),
        ) {
            Text(
                text = "$ claude --resume\n> waiting for your input…",
                color = PocketShellColors.TermText,
                style = PocketShellType.terminal,
                modifier = Modifier.padding(PocketShellSpacing.md),
            )
        }

        // The single-session case: one tab plus `+`, so the chrome does not
        // appear and disappear as sessions come and go.
        SectionHeader(label = "One session")
        SessionTabStrip(
            tabs = listOf(SessionTab("pocketshell:main", "main", SessionTabState.Idle)),
            selectedId = "pocketshell:main",
            onSelect = {},
            onNewTab = {},
            onOverflow = {},
        )
    }
}

/**
 * Issue #2632 (maintainer follow-up 2026-09-10): the whole tap-through, in one
 * image — "I don't want to have another screen".
 *
 * TOP: the host screen. Each row is a workspace with its live session summary.
 * BOTTOM: what ONE tap on `pocketshell` now lands on — the terminal itself,
 * with that workspace's other sessions as tabs. What used to sit between them,
 * a workspace screen listing the same sessions again as rows to tap a second
 * time, is gone from this path; it remains only for a workspace that has
 * nothing running yet.
 *
 * The workspace rows and the session tab strip are the REAL ui-kit components
 * ([WorkspaceRow], [SessionTabStrip]); only the app2 usage pill is mirrored.
 */
@Composable
internal fun WorkspaceTapToSessionRender() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader(
            title = "hetzner",
            subtitle = "12 workspaces · 4 sessions",
            onBack = {},
            trailing = { QuietUsageGlanceFacsimile("Claude 7d", 38) },
        )
        SectionHeader(label = "~/git")
        WorkspaceRow(
            title = "pocketshell",
            subtitleContent = {
                Text(
                    text = "Claude Code ×2 · Terminal",
                    color = PocketShellColors.TextMuted,
                    style = PocketShellType.metadata,
                )
            },
            onClick = {},
            testTag = "render-workspace-pocketshell",
        )
        WorkspaceRow(
            title = "aplexer",
            subtitleContent = {
                Text(
                    text = "No sessions",
                    color = PocketShellColors.TextMuted,
                    style = PocketShellType.metadata,
                )
            },
            onClick = {},
            testTag = "render-workspace-aplexer",
        )

        Text(
            text = "one tap on \"pocketshell\"  ↓  no screen in between",
            color = PocketShellColors.Accent,
            style = PocketShellType.metadata,
            modifier = Modifier.padding(horizontal = PocketShellSpacing.xl),
        )

        ScreenHeader(
            title = "pocketshell",
            subtitle = "hetzner · Connected",
            onBack = {},
            trailing = { QuietUsageGlanceFacsimile("Claude", 38) },
        )
        SessionTabStrip(
            tabs = listOf(
                SessionTab("pocketshell:main", "main", SessionTabState.Working),
                SessionTab("pocketshell:review", "review", SessionTabState.NeedsInput),
                SessionTab("pocketshell:shell", "Terminal", SessionTabState.Idle),
            ),
            selectedId = "pocketshell:review",
            onSelect = {},
            onNewTab = {},
            onOverflow = {},
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .background(PocketShellColors.TermBg),
        ) {
            Text(
                text = "› implement the workspace tap\n\n● Waiting for your input…",
                color = PocketShellColors.TermText,
                style = PocketShellType.terminal,
                modifier = Modifier.padding(PocketShellSpacing.md),
            )
        }
    }
}

/**
 * Issue #2632: usage/cost on the LANDING screen, before any tap.
 *
 * The production pill (`com.pocketshell.next.usage.UsageGlancePill`) is an
 * app2 composable this ui-kit harness cannot import, so this mirrors its exact
 * tokens — muted `TextSecondary` provider attribution, bright `Text` percent,
 * muted `TextMuted` read-at clock when the reading is stale, all on
 * `PocketShellType.metadata`, with no chip fill or border (Quiet keeps usage
 * readable without a coloured chip).
 *
 * Two headers, because the fix has two placements:
 *  - **Hosts** (the landing screen), showing the CACHED reading with its
 *    honest "read at 13:40" clock — the list is a pre-connection screen, so a
 *    live number is impossible there without dialling, which D21 forbids.
 *  - **Host workspaces** (where a resumed launch lands), showing a LIVE
 *    reading next to the host kebab instead of three taps down inside the
 *    Host tools sheet.
 */
@Composable
internal fun LandingUsageGlanceRender() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ScreenHeader(
            title = "Hosts",
            trailing = { QuietUsageGlanceFacsimile("Claude", 63, staleClock = "13:40") },
        )
        ListRow(title = "hetzner", subtitle = "alexey@135.181.114.209", onClick = {})
        ListRow(title = "gpu-box", subtitle = "alexey@10.0.0.42", onClick = {})

        SectionHeader(label = "After the host opens")
        ScreenHeader(
            title = "hetzner",
            subtitle = "12 workspaces · 4 sessions",
            onBack = {},
            trailing = {
                QuietUsageGlanceFacsimile("Claude 7d", 38)
                Kebab(
                    items = listOf(KebabItem(label = "Host tools", onClick = {})),
                    triggerTestTag = "render-host-actions",
                )
            },
        )
    }
}

/** Today's Quiet usage glance: attribution + percent, no chip, no dot. */
@Composable
private fun QuietUsageGlanceFacsimile(
    attribution: String,
    percent: Int,
    staleClock: String? = null,
) {
    Row(
        modifier = Modifier.padding(horizontal = PocketShellSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = attribution,
            color = PocketShellColors.TextSecondary,
            style = PocketShellType.metadata,
            maxLines = 1,
        )
        Spacer(modifier = Modifier.width(PocketShellSpacing.xs))
        Text(
            text = "$percent%",
            color = PocketShellColors.Text,
            style = PocketShellType.metadata,
            maxLines = 1,
        )
        if (staleClock != null) {
            Spacer(modifier = Modifier.width(PocketShellSpacing.xs))
            Text(
                text = staleClock,
                color = PocketShellColors.TextMuted,
                style = PocketShellType.metadata,
            )
        }
    }
}

/** The session screen's own header, mirrored so the strip has its real context. */
@Composable
private fun TerminalHeaderFacsimile(title: String, subtitle: String) {
    ScreenHeader(
        title = title,
        subtitle = subtitle,
        onBack = {},
        trailing = { QuietUsageGlanceFacsimile("Claude", 38) },
    )
}
