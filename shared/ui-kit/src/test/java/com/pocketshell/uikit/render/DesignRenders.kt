package com.pocketshell.uikit.render

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextButton
import com.github.takahirom.roborazzi.captureRoboImage
import com.pocketshell.uikit.components.Badge
import com.pocketshell.uikit.components.BadgeRole
import com.pocketshell.uikit.components.HostCard
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.components.SectionHeader
import com.pocketshell.uikit.components.SegmentedToggle
import com.pocketshell.uikit.components.StatusDot
import com.pocketshell.uikit.model.ConnectionStatus
import com.pocketshell.uikit.model.HostStatus
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import com.pocketshell.uikit.theme.PocketShellType
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Fast design-iteration render harness (#555).
 *
 * Renders real ui-kit composables under the **actual** [PocketShellTheme] — the
 * always-dark `PocketShellDarkColorScheme`, `PocketShellTypography`,
 * `PocketShellShapes`, and the `LocalPocketShellSemantic` provider — to PNGs on
 * the host JVM via Roborazzi/Robolectric. No emulator, no install: each render
 * is seconds, not the minutes the emulator `*ScreenshotTest`s cost. This is the
 * additive iteration loop, not a replacement for the emulator release gate.
 *
 * The `@Config` qualifiers pin a Pixel-7-class viewport
 * (`w412dp-h915dp-night-xxhdpi`) so the always-dark scheme renders true to
 * device. (Robolectric requires the `night` UI-mode qualifier before density.)
 * `GraphicsMode.NATIVE` gives Robolectric a real pixel buffer to snapshot.
 *
 * ### Per-tweak workflow
 *
 * 1. Edit a composable (here or in `shared/ui-kit/.../components`).
 * 2. Run `scripts/render.sh` (all renders) or
 *    `scripts/render.sh hostListScreen` (one render).
 * 3. Open the PNG under `build/renders/`.
 *
 * Each `@Test` writes a stable, predictably-named PNG into `build/renders/` so a
 * design tweak yields a fresh image at the same path every time. The
 * `captureRoboImage(filePath) { … }` overload launches its own headless
 * `ComponentActivity` and snapshots the composition, so no Compose test rule is
 * needed.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
class DesignRenders {

    /** Host-list header (`ScreenHeader`) with a trailing status pill. */
    @Test
    fun screenHeader() = render("screen-header") {
        ScreenHeader(
            title = "Hosts",
            subtitle = "4 hosts · 7 sessions",
            trailing = {
                Badge(label = "7 active", role = BadgeRole.Active, mono = false)
            },
        )
    }

    /** A single dense `ListRow` with a leading status dot and a trailing badge. */
    @Test
    fun listRow() = render("list-row") {
        ListRow(
            title = "agent-main",
            subtitle = "~/proj/agent",
            leading = { StatusDot(status = ConnectionStatus.Connected) },
            trailing = { Badge(label = "Claude", role = BadgeRole.Agent) },
            onClick = {},
        )
    }

    /**
     * One full screen: the host-list dashboard composed from the shared
     * `ScreenHeader` + a stack of `HostCard`s, exactly how a real screen builds
     * up from ui-kit primitives. Proves screen-level layout renders faithfully
     * on the JVM, not just isolated components.
     */
    @Test
    fun hostListScreen() = render("host-list-screen") {
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

    /**
     * Issue #603: the host-detail workspace tree with the redesigned
     * inactive / empty watched-root callout.
     *
     * `FolderListContent` lives in the app module, so this fixture mirrors the
     * real screen's rows using the SAME shared ui-kit primitives the screen
     * composes ([ScreenHeader], [SectionHeader] for the root header pattern,
     * [ListRow] + [StatusDot], and the subtle accent `+`). It puts an active
     * root group next to the inactive-root callout so the maintainer can compare
     * their chrome weight: the inactive callout now reads as ONE dense project
     * row (muted dot + count title + muted-mono context subtitle + a single
     * trailing accent `+`), no longer a heavier divergent "+ Review/Add" pill.
     */
    @Test
    fun hostDetailInactiveFolders() = render("host-detail-inactive-folders") {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Active root group — header + an active project row, the "what an
            // active row looks like" reference the inactive callout is matched to.
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

            // Inactive-root callout — has scanned candidate folders ("Review").
            TreeRootHeader(label = "archive", count = "3 projects")
            ListRow(
                title = "3 inactive folders",
                subtitle = "Tap to review folders under this root.",
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

            // Empty-root callout — no candidate folders yet ("Add").
            TreeRootHeader(label = "labs", count = "0 projects")
            ListRow(
                title = "No folders yet",
                subtitle = "Tap to add a folder under this root.",
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

    /**
     * Issue #675: the decluttered multi-window agent tree. A session with two
     * Claude windows (w0, w1) is broken out into per-window child rows. The
     * agent type is shown ONCE per window (the `w0 claude` / `w1 claude` titles
     * + the status dot) — NOT three times. So:
     *  - the host header counts agent WINDOWS ("2 agents", not "1"),
     *  - the parent session row carries NO inline `w0 Claude · idle · w1 Claude`
     *    summary and NO trailing agent badge,
     *  - the window child rows carry NO per-row agent badge.
     *
     * For contrast the fixture leads with a single-window agent session that
     * KEEPS its concise trailing badge — the redundancy collapse is specific to
     * multi-window expanded sessions.
     *
     * `FolderListScreen` and its `WorkspaceSessionRow` / `WorkspaceSessionWindowRow`
     * are app-module private composables, so this mirrors them with the shared
     * ui-kit primitives the screen composes ([ListRow] + [StatusDot] + [Badge]).
     */
    @Test
    fun hostDetailMultiWindowAgentTree() = render("host-detail-multi-window-agent-tree") {
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

            // Multi-window PARENT row: NO inline window summary, NO trailing
            // badge — the window child rows below carry the detail.
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

            // Window child rows: dot + `w<n> claude` title, NO per-row badge.
            DeclutteredWindowRow(title = "w0 claude")
            DeclutteredWindowRow(title = "w1 claude")
        }
    }

    /**
     * Mirror of the app's `WorkspaceSessionWindowRow` AFTER #675: an indented
     * window child row that leads with a status dot and the `w<n> claude` title
     * and carries NO trailing agent badge (the dot + the title already name the
     * agent — repeating it on a badge was the third duplication).
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

    /** Mirror of the app's `SubtleAddButton` — a bare accent `+`, no chrome. */
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

    /**
     * Issue #561: fast PNG target for the chat-style Conversation tab.
     * This fixture mirrors the mockup (docs/mockups/conversation.html):
     * full message blocks with role label header, multi-line body,
     * inline tool call cards, and right-aligned timestamps.
     */
    @Test
    fun conversationTimeline() = render("conversation-timeline") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            // USER message block
            ConversationChatBlock(
                roleLabel = "USER",
                roleColor = PocketShellColors.Accent,
                timeLabel = "· 4m ago",
                bodyContent = {
                    ConversationChatBody(
                        text = "check the deploy log and tell me what failed in the last run",
                    )
                },
            )

            // Spacer between message blocks (22dp per mockup .msg { margin-bottom })
            Spacer(modifier = Modifier.height(22.dp))

            // ASSISTANT message block with inline tool call card
            ConversationChatBlock(
                roleLabel = "ASSISTANT",
                roleColor = PocketShellColors.Purple,
                timeLabel = "· 3m ago",
                bodyContent = {
                    Text(
                        text = "I'll check the deploy logs.",
                        color = PocketShellColors.Text,
                        fontSize = 14.sp,
                        lineHeight = (14.sp * 1.55f),
                        fontFamily = FontFamily.SansSerif,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    ConversationToolCallCard(
                        toolName = "Bash",
                        command = "kubectl logs -n prod deploy-7d9",
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "The deploy failed because the database migration timed out at step 4. The query ALTER TABLE users ADD COLUMN... took longer than the 30s limit.",
                        color = PocketShellColors.Text,
                        fontSize = 14.sp,
                        lineHeight = (14.sp * 1.55f),
                        fontFamily = FontFamily.SansSerif,
                    )
                },
            )

            Spacer(modifier = Modifier.height(22.dp))

            // Second USER message
            ConversationChatBlock(
                roleLabel = "USER",
                roleColor = PocketShellColors.Accent,
                timeLabel = "· 1m ago",
                bodyContent = {
                    ConversationChatBody(
                        text = "show me the migration",
                    )
                },
            )

            Spacer(modifier = Modifier.height(22.dp))

            // Second ASSISTANT with streaming indicator
            ConversationChatBlock(
                roleLabel = "ASSISTANT",
                roleColor = PocketShellColors.Purple,
                timeLabel = "· streaming",
                bodyContent = {
                    Text(
                        text = "Here's the migration that timed out:",
                        color = PocketShellColors.Text,
                        fontSize = 14.sp,
                        lineHeight = (14.sp * 1.55f),
                        fontFamily = FontFamily.SansSerif,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    ConversationToolCallCard(
                        toolName = "Read",
                        command = "migrations/0042_add_users.sql",
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "The migration adds three new columns to a 50M-row users table without batching",
                        color = PocketShellColors.Text,
                        fontSize = 14.sp,
                        lineHeight = (14.sp * 1.55f),
                        fontFamily = FontFamily.SansSerif,
                    )
                    Text(
                        text = "▌",
                        color = PocketShellColors.Purple,
                        fontSize = 14.sp,
                    )
                },
            )
        }
    }

    /**
     * Issue #614: the "new session" type picker body, reconstructed from the
     * same ui-kit primitives the real [SessionTypePickerSheet] now composes
     * (`SectionHeader`, the shared cyan-fill `SegmentedToggle`, and a
     * `ListRow` skip-permissions row). The app-level sheet itself cannot be
     * imported into this ui-kit harness, so this fixture proves the
     * design-system look of the rebuilt picker — aligned full-width segments,
     * consistent section rhythm, no awkward vertical-list compression.
     */
    @Test
    fun sessionTypePicker() = render("session-type-picker") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "New session",
                color = PocketShellColors.Text,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "in ai-shipping-labs-workshops-raw",
                color = PocketShellColors.TextSecondary,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
            )

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionHeader(label = "Start folder")
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .background(PocketShellColors.SurfaceElev, RoundedCornerShape(10.dp))
                        .border(1.dp, PocketShellColors.BorderSoft, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = "/home/alexey/git/ai-shipping-labs-workshops-raw",
                        color = PocketShellColors.Text,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionHeader(label = "Session type")
                SegmentedToggle(
                    labels = listOf("Shell", "Agent"),
                    selectedIndex = 1,
                    onSelected = {},
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    fillSegments = true,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionHeader(label = "Agent CLI")
                SegmentedToggle(
                    labels = listOf("claude", "codex", "opencode"),
                    selectedIndex = 0,
                    onSelected = {},
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    fillSegments = true,
                )
                Text(
                    text = "The CLI will auto-start in the new pane.",
                    color = PocketShellColors.TextMuted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
                ListRow(
                    title = "Skip permissions",
                    subtitle = "No per-action approval prompts.",
                    leading = {
                        Checkbox(
                            checked = true,
                            onCheckedChange = {},
                            colors = CheckboxDefaults.colors(
                                checkedColor = PocketShellColors.Accent,
                                uncheckedColor = PocketShellColors.TextSecondary,
                            ),
                        )
                    },
                    onClick = {},
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, PocketShellColors.BorderSoft)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = {}) {
                    Text("Cancel", color = PocketShellColors.TextSecondary)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {},
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PocketShellColors.Accent,
                        contentColor = PocketShellColors.OnAccent,
                    ),
                ) {
                    Text("Create")
                }
            }
        }
    }

    /**
     * Chat-style message block: role header + body content.
     * Mirrors the .msg / .msg-head / .msg-body structure from conversation.html.
     */
    @Composable
    private fun ConversationChatBlock(
        roleLabel: String,
        roleColor: Color,
        timeLabel: String,
        bodyContent: @Composable () -> Unit,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // .msg-head: role label + time
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = roleLabel,
                    color = roleColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.SansSerif,
                    letterSpacing = 0.8.sp,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = timeLabel,
                    color = PocketShellColors.TextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                )
            }
            // .msg-body
            bodyContent()
        }
    }

    /** Simple body text block matching .msg-body from the mockup. */
    @Composable
    private fun ConversationChatBody(
        text: String,
    ) {
        Text(
            text = text,
            color = PocketShellColors.Text,
            fontSize = 14.sp,
            lineHeight = (14.sp * 1.55f),
            fontFamily = FontFamily.SansSerif,
        )
    }

    /** Inline tool call card matching .tool-call from the mockup. */
    @Composable
    private fun ConversationToolCallCard(
        toolName: String,
        command: String,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = PocketShellColors.Surface,
                    shape = RoundedCornerShape(10.dp),
                )
                .border(
                    width = 1.dp,
                    color = PocketShellColors.BorderSoft,
                    shape = RoundedCornerShape(10.dp),
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "›",
                color = PocketShellColors.TextMuted,
                fontSize = 14.sp,
            )
            Text(
                text = toolName,
                color = PocketShellColors.Accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = command,
                color = PocketShellColors.TextSecondary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    /**
     * Issue #647: fast PNG target for the Git Overview tab (branches, worktrees,
     * status). The real `OverviewPanel` lives in the `app` module so this fixture
     * approximates it from the same shared ui-kit primitives — the segmented
     * Overview|History switch, SectionHeaders, status row with a Dirty/Clean
     * badge, branch rows, and worktree rows — to verify the design holds under the
     * real theme. The emulator `GitHistoryDockerTest` is the acceptance check.
     */
    @Test
    fun gitOverviewTab() = render("git-overview-tab") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ScreenHeader(title = "Git history", subtitle = "agents")
            SegmentedToggle(
                labels = listOf("Overview", "History"),
                selectedIndex = 0,
                onSelected = {},
                fillSegments = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            // Issue #648: "Open on GitHub" appears first when origin is a GitHub repo.
            SectionHeader(label = "Remote")
            ListRow(title = "Open on GitHub", subtitle = "github.com/owner/repo", onClick = {})
            SectionHeader(label = "Status")
            ListRow(
                title = "main",
                subtitle = "↑1 vs origin/main · 2 uncommitted changes\na1b2c3d Add overview tab",
                trailing = { Badge(label = "Dirty", role = BadgeRole.Error, mono = false) },
            )
            SectionHeader(label = "Branches", count = 2)
            ListRow(
                title = "main",
                subtitle = "Add overview tab",
                trailing = { Badge(label = "Current", role = BadgeRole.Active, mono = false) },
            )
            ListRow(title = "feature/x", subtitle = "tracks origin/feature/x")
            SectionHeader(label = "Worktrees", count = 2)
            ListRow(title = "/home/u/git/proj", subtitle = "main")
            ListRow(title = "/home/u/git/proj-feature", subtitle = "feature/x")
        }
    }

    /**
     * Issue #649: fast PNG target for the Git Issues tab (`gh issue list`). The
     * real `IssuesPanel` lives in the `app` module, so this fixture approximates
     * it from the same shared ui-kit primitives — the three-way Overview|History|
     * Issues switch, a SectionHeader with a count, and issue rows carrying a
     * leading open/closed StatusDot, the `#number · labels` subtitle, and an
     * Open/Closed badge. The emulator `GitHistoryDockerTest` is the acceptance
     * check.
     */
    @Test
    fun gitIssuesTab() = render("git-issues-tab") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ScreenHeader(title = "Git history", subtitle = "pocketshell")
            SegmentedToggle(
                labels = listOf("Overview", "History", "Issues"),
                selectedIndex = 2,
                onSelected = {},
                fillSegments = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            SectionHeader(label = "GitHub issues", count = 3)
            ListRow(
                title = "view GitHub issues in-app (gh issue list)",
                subtitle = "#649 · enhancement",
                leading = { StatusDot(status = ConnectionStatus.Connected) },
                trailing = { Badge(label = "Open", role = BadgeRole.Active, mono = false) },
            )
            ListRow(
                title = "Open on GitHub action",
                subtitle = "#648 · enhancement, slice-4",
                leading = { StatusDot(status = ConnectionStatus.Idle) },
                trailing = { Badge(label = "Closed", role = BadgeRole.Idle, mono = false) },
            )
            ListRow(
                title = "Read-only repo overview tab",
                subtitle = "#647",
                leading = { StatusDot(status = ConnectionStatus.Idle) },
                trailing = { Badge(label = "Closed", role = BadgeRole.Idle, mono = false) },
            )
        }
    }

    /**
     * Issue #649: the gated "configure gh" hint shown on the Issues tab when gh
     * is NOT installed/authenticated on the remote (slice 1, #645).
     */
    @Test
    fun gitIssuesConfigureGhHint() = render("git-issues-configure-gh-hint") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ScreenHeader(title = "Git history", subtitle = "pocketshell")
            SegmentedToggle(
                labels = listOf("Overview", "History", "Issues"),
                selectedIndex = 2,
                onSelected = {},
                fillSegments = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            SectionHeader(label = "GitHub issues")
            ListRow(
                title = "Configure gh to see issues",
                subtitle = "install gh (https://cli.github.com) and run `gh auth login`",
                trailing = { Badge(label = "Setup", role = BadgeRole.Idle, mono = false) },
            )
        }
    }

    /**
     * Fast first-look render of the create-issue form (#650). The real form is an
     * app-level composable (`CreateIssueSheet` in `:app`) that the ui-kit harness
     * can't import, so this mirrors its structure with the same ui-kit/Material3
     * primitives under the real theme: title + body fields and a Cancel / Create
     * confirm row. Use it to eyeball spacing + the accent confirm button; the
     * emulator test drives the actual sheet.
     */
    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun gitCreateIssueForm() = render("git-create-issue-form") {
        val fieldColors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = PocketShellColors.Text,
            unfocusedTextColor = PocketShellColors.Text,
            focusedBorderColor = PocketShellColors.Accent,
            unfocusedBorderColor = PocketShellColors.BorderSoft,
            focusedLabelColor = PocketShellColors.Accent,
            unfocusedLabelColor = PocketShellColors.TextSecondary,
            cursorColor = PocketShellColors.Accent,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "New GitHub issue",
                color = PocketShellColors.Text,
                style = PocketShellType.bodyDense,
                fontWeight = FontWeight.SemiBold,
            )
            OutlinedTextField(
                value = "Voice: trailing words dropped",
                onValueChange = {},
                singleLine = true,
                label = { Text("Title") },
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = "Steps to reproduce:\n1. Open the composer\n2. Dictate a long note",
                onValueChange = {},
                label = { Text("Body (optional)") },
                colors = fieldColors,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(132.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = {}) {
                    Text(
                        "Cancel",
                        color = PocketShellColors.TextSecondary,
                        style = PocketShellType.bodyDense,
                    )
                }
                Button(
                    onClick = {},
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PocketShellColors.Accent,
                        contentColor = PocketShellColors.Background,
                    ),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Create issue", style = PocketShellType.bodyDense)
                }
            }
        }
    }

    /**
     * Issue #607: the host-detail overflow (kebab) menu, opened, showing the
     * manual `Refresh Sessions` action alongside the other header actions. The
     * real menu lives in the app module's `FolderListOverflowMenu`, anchored by
     * the shared [com.pocketshell.uikit.components.Kebab] [DropdownMenu]. A
     * `DropdownMenu` renders into a popup window that Roborazzi's single
     * composition snapshot does not capture, so this fixture mirrors the same
     * `SurfaceElev` panel + `bodyDense` rows the live `Kebab` paints, in the
     * exact item order, to give a fast visual check of the menu copy and the
     * in-flight `Refreshing Sessions` (disabled) variant.
     */
    @Test
    fun hostDetailOverflowMenu() = render("host-detail-overflow-menu") {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            KebabMenuPanel(
                title = "Idle",
                items = listOf(
                    "Host assistant" to true,
                    "Browse repos" to true,
                    "Refresh Sessions" to true,
                    "Usage" to true,
                    "App settings" to true,
                    "Workspace settings" to true,
                ),
            )
            KebabMenuPanel(
                title = "Refreshing",
                items = listOf(
                    "Host assistant" to true,
                    "Browse repos" to true,
                    "Refreshing Sessions" to false,
                    "Usage" to true,
                    "App settings" to true,
                    "Workspace settings" to true,
                ),
            )
        }
    }

    /**
     * Static mirror of the shared `Kebab` opened-menu chrome: a [SurfaceElev]
     * rounded panel of [PocketShellType.bodyDense] rows, the disabled row dimmed
     * the same way [DropdownMenuItem]`(enabled = false)` dims its text.
     */
    @Composable
    private fun KebabMenuPanel(title: String, items: List<Pair<String, Boolean>>) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = title,
                color = PocketShellColors.TextSecondary,
                style = PocketShellType.bodyDense,
                modifier = Modifier.padding(start = 12.dp),
            )
            Surface(
                color = PocketShellColors.SurfaceElev,
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    items.forEach { (label, enabled) ->
                        Text(
                            text = label,
                            color = if (enabled) {
                                PocketShellColors.Text
                            } else {
                                PocketShellColors.TextSecondary
                            },
                            style = PocketShellType.bodyDense,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                }
            }
        }
    }

    /**
     * Issue #690: the in-app "limits just reset" banner (the non-push fallback).
     *
     * Caveat (#555): the real `UsageResetBanner` lives in the `app` module, which
     * the ui-kit render harness can't import. This is a faithful static mirror of
     * its layout — a [SurfaceElev] rounded card with a [Green] border, the green
     * "<Provider> limits reset at <time>" title, and the muted detail line —
     * built from the same ui-kit theme tokens the real banner uses, so the design
     * (color, weight, spacing) is visually checkable here. The app-module
     * composable itself is validated on the emulator.
     */
    @Test
    fun usageResetBanner() = render("usage-reset-banner") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .background(PocketShellColors.SurfaceElev, RoundedCornerShape(10.dp))
                .border(width = 1.dp, color = PocketShellColors.Green, shape = RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Text(
                text = "Codex limits reset at 5:00 PM",
                color = PocketShellColors.Green,
                style = PocketShellType.bodyDense,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Heavy work can resume. · ~15m earlier than stated",
                color = PocketShellColors.TextSecondary,
                style = PocketShellType.bodyDense,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }

    /**
     * Issue #701: faithful static mirror of the polished prompt-composer sheet
     * chrome — the grabber, the `Prompt Composer` header + circular close chip,
     * the draft field, and (the focus of #701) the bottom controls row: the
     * grouped 📎/{} tools pill on the left, a weight gap, then the FILLED accent
     * Send pill + cyan mic disc on the right.
     *
     * Caveat (#555): the real `SheetContent` lives in the `app` module, which the
     * ui-kit render harness can't import, so this mirrors its layout with the
     * same ui-kit theme tokens (`Accent`, `OnAccent`, `SurfaceElev`, `Surface`,
     * `Border`, `TextMuted`). It is the fast first design check for the row
     * rebalance + Send prominence; the real app composable is validated on the
     * emulator with the keyboard up.
     */
    @Test
    fun composerControlsRow() = render("composer-controls-row") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(PocketShellColors.Surface, RoundedCornerShape(20.dp))
                .padding(horizontal = 18.dp, vertical = 14.dp),
        ) {
            // Grabber.
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(40.dp)
                    .height(4.dp)
                    .background(PocketShellColors.Border, RoundedCornerShape(2.dp)),
            )
            Spacer(Modifier.height(14.dp))
            // Header: title + circular close chip.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Prompt Composer",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = PocketShellColors.Text,
                )
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(PocketShellColors.SurfaceElev, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "×", color = PocketShellColors.TextSecondary, fontSize = 20.sp)
                }
            }
            Spacer(Modifier.height(14.dp))
            // Draft field with sample text.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .background(PocketShellColors.SurfaceElev, RoundedCornerShape(12.dp))
                    .border(1.dp, PocketShellColors.Border, RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Text(
                    text = "check the deploy log and tell me what failed in the last run",
                    color = PocketShellColors.Text,
                    fontSize = 14.sp,
                )
            }
            Spacer(Modifier.height(14.dp))
            // Controls row — the #701 focus.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Grouped left tools pill.
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(22.dp))
                        .background(PocketShellColors.SurfaceElev, RoundedCornerShape(22.dp))
                        .padding(horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    // Glyphs stand in for the Material AttachFile / DataObject
                    // icons (the icons-extended set isn't on the ui-kit render
                    // classpath); the real app row uses the proper icons.
                    Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        Text(text = "📎", color = PocketShellColors.TextSecondary, fontSize = 18.sp)
                    }
                    Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = "{ }",
                            color = PocketShellColors.TextSecondary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                // Filled accent Send pill.
                Row(
                    modifier = Modifier
                        .height(44.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(PocketShellColors.Accent, RoundedCornerShape(22.dp))
                        .padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Text(
                        text = "Send",
                        color = PocketShellColors.OnAccent,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(text = "➤", color = PocketShellColors.OnAccent, fontSize = 13.sp)
                }
                Spacer(Modifier.width(8.dp))
                // Cyan mic disc.
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(PocketShellColors.Accent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "●",
                        color = PocketShellColors.OnAccent,
                        fontSize = 18.sp,
                    )
                }
            }
        }
    }

    /**
     * #704: compact transcript tool-call rows. ui-kit cannot import the
     * app-level `ConversationToolCallChatCard`, so this replicates its layout
     * with the SAME design tokens (post-#704 compact: 6dp vertical padding,
     * 8dp inter-row margin, 8dp radius) so the density + the parsed/collapsed
     * image output can be eyeballed before the emulator. The expanded body
     * shows what `ToolPayloadFormatter.formatOutput(...)` produces — an
     * `[image …]` summary, NOT the multi-KB base64 blob.
     */
    @Test
    fun conversationToolRowsCompact() = render("conversation-tool-rows-compact") {
        Column(modifier = Modifier.padding(horizontal = 18.dp)) {
            CompactToolRow("Bash", "cd /home/alexey/git/pocketshell ec…", expanded = false)
            CompactToolRow("Read", "20260611-174904-01-Screenshot_2026…", expanded = false)
            CompactToolRow("Agent", "implementer: Restore #690 wiring l…", expanded = false)
            CompactToolRow(
                tool = "Read",
                summary = "shot.png",
                expanded = true,
                input = "{\n  \"file_path\": \"/home/alexey/.pocketshell/attachments/host/shot.png\"\n}",
                output = "[image image/png · 39124 chars]",
            )
        }
    }

    @Composable
    private fun CompactToolRow(
        tool: String,
        summary: String,
        expanded: Boolean,
        input: String? = null,
        output: String? = null,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PocketShellColors.Surface, RoundedCornerShape(8.dp))
                    .border(1.dp, PocketShellColors.BorderSoft, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(if (expanded) "v" else "›", color = PocketShellColors.TextMuted, style = PocketShellType.labelMono, fontSize = 14.sp)
                Text(tool, color = PocketShellColors.Accent, style = PocketShellType.bodyDense, fontWeight = FontWeight.SemiBold)
                Text(
                    summary,
                    color = PocketShellColors.TextSecondary,
                    style = PocketShellType.labelMono,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("✓", color = PocketShellColors.Green, style = PocketShellType.labelMono)
            }
            if (expanded) {
                Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    input?.let { CompactSection("input", it) }
                    output?.let { CompactSection("output", it) }
                }
            }
        }
    }

    @Composable
    private fun CompactSection(label: String, body: String) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(label, color = PocketShellColors.TextMuted, fontSize = 10.sp, modifier = Modifier.padding(bottom = 2.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PocketShellColors.TermBg, RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Text(body, color = PocketShellColors.TermText, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }

    /**
     * Renders [content] wrapped in the real [PocketShellTheme] on the app's dark
     * background and snapshots the composition to `build/renders/<name>.png`.
     */
    private fun render(name: String, content: @Composable () -> Unit) {
        captureRoboImage("build/renders/$name.png") {
            PocketShellTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = PocketShellColors.Background,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        content()
                    }
                }
            }
        }
    }
}
