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
import com.pocketshell.uikit.components.StatusDot
import com.pocketshell.uikit.icons.PocketShellIcons
import com.pocketshell.uikit.components.SESSION_TAB_NEW_DESCRIPTION
import com.pocketshell.uikit.components.HeaderIconAction
import com.pocketshell.uikit.model.ConnectionStatus
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
        // #2635 T2/D3: the steady header is a DOT and a one-line title, with
        // no "Connected" subtitle — this fixture used to show the state the
        // change removed.
        TerminalHeaderFacsimile(title = "pocketshell", subtitle = null)
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

        // #2635 D3: with ONE session the strip is not rendered at all — a
        // strip with a single item is 40dp of chrome offering no choice
        // (`ux-rules.md` rule 6) — and its `+` moves into the header instead.
        // That is what the header below shows, and it is why there is no
        // second strip in this render any more.
        SectionHeader(label = "One session: no strip, `+` in the header")
        TerminalHeaderFacsimile(
            title = "pocketshell",
            subtitle = null,
            trailingPlus = true,
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
        // #2630/#2635: the shipped row grammar — ONE dense line, a leading dot
        // when something in the workspace is attached, a bare muted count and
        // relative time, and no chevron (every row here navigates, so a glyph
        // repeating that a dozen times is chrome). This fixture drew the
        // pre-#2630 two-line row with a kind-summary subtitle until #2635; a
        // render of a shape the app does not ship is worse than no render.
        WorkspaceRow(
            title = "pocketshell",
            dense = true,
            leadingContent = { StatusDot(status = ConnectionStatus.Connected) },
            chevron = false,
            trailingContent = { WorkspaceGlanceFacsimile(count = 3, recency = "just now") },
            onClick = {},
            testTag = "render-workspace-pocketshell",
        )
        WorkspaceRow(
            title = "aplexer",
            dense = true,
            leadingContent = { StatusDot(status = ConnectionStatus.Idle) },
            chevron = false,
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
            // #2635 T2: dot, not the word.
            status = ConnectionStatus.Connected,
            statusDescription = "hetzner · Connected",
            titleMaxLines = 1,
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

/**
 * app2's `WorkspaceGlance`: a bare muted count and a relative time. Mirrored
 * because it lives in app2, which this harness cannot import — the ROW around
 * it is the real component.
 */
@Composable
private fun WorkspaceGlanceFacsimile(count: Int, recency: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm),
    ) {
        Text(
            text = count.toString(),
            color = PocketShellColors.TextMuted,
            style = PocketShellType.labelMono,
        )
        Text(
            text = recency,
            color = PocketShellColors.TextMuted,
            style = PocketShellType.metadata,
            maxLines = 1,
        )
    }
}

/** The session screen's own header, mirrored so the strip has its real context. */
@Composable
private fun TerminalHeaderFacsimile(
    title: String,
    subtitle: String? = null,
    trailingPlus: Boolean = false,
) {
    ScreenHeader(
        title = title,
        subtitle = subtitle,
        // #2635 T2: the steady transport state is the dot; the subtitle line is
        // reserved for "Reconnecting…" / "Offline", which a colour cannot say.
        status = ConnectionStatus.Connected,
        statusDescription = "hetzner · Connected",
        titleMaxLines = 1,
        onBack = {},
        trailing = {
            if (trailingPlus) {
                HeaderIconAction(
                    icon = PocketShellIcons.Plus,
                    contentDescription = SESSION_TAB_NEW_DESCRIPTION,
                    onClick = {},
                    testTag = "render-session-header-new",
                )
            }
            QuietUsageGlanceFacsimile("Claude", 38)
        },
    )
}
