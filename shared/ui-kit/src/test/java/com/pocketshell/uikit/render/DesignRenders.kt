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
import androidx.compose.foundation.shape.RoundedCornerShape
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
