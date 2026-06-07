package com.pocketshell.uikit.render

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.pocketshell.uikit.components.Badge
import com.pocketshell.uikit.components.BadgeRole
import com.pocketshell.uikit.components.HostCard
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.ScreenHeader
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
     * Issue #561: fast PNG target for the dense Conversation timeline before
     * spending emulator time. This fixture mirrors the production row grammar:
     * actor/tool badge, one-line truncated preview, and right-aligned timestamp.
     */
    @Test
    fun conversationTimeline() = render("conversation-timeline") {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ConversationTimelineRenderRow(
                badge = "USER",
                badgeColor = PocketShellColors.Accent,
                preview = "check the deploy log and tell me what failed in the last run",
                timestamp = "09:05",
            )
            ConversationTimelineRenderRow(
                badge = "ASSISTANT",
                badgeColor = PocketShellColors.Purple,
                preview = "I'll check the deploy logs and compare the failing revision with the last green run.",
                timestamp = "09:07",
            )
            ConversationTimelineRenderRow(
                badge = "TOOL",
                badgeColor = PocketShellColors.TextSecondary,
                preview = "Bash  kubectl logs -n prod deploy/api --tail=120",
                timestamp = "09:07",
                highlighted = true,
            )
            ConversationTimelineRenderRow(
                badge = "CLAUDE",
                badgeColor = PocketShellColors.TextSecondary,
                preview = "Claude resuming /loop wakeup (deploy watch)",
                timestamp = "09:08",
            )
            ConversationTimelineRenderRow(
                badge = "ASSISTANT",
                badgeColor = PocketShellColors.Purple,
                preview = "The deploy failed because the database migration timed out at step 4.",
                timestamp = "09:09",
            )
        }
    }

    @Composable
    private fun ConversationTimelineRenderRow(
        badge: String,
        badgeColor: Color,
        preview: String,
        timestamp: String,
        highlighted: Boolean = false,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp),
        ) {
            Text(
                text = badge,
                color = badgeColor,
                style = PocketShellType.labelMono,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .width(82.dp)
                    .background(
                        color = badgeColor.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(3.dp),
                    )
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = preview,
                color = if (highlighted) PocketShellColors.Text else PocketShellColors.TextSecondary,
                style = if (highlighted) PocketShellType.bodyMono else PocketShellType.bodyDense,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = timestamp,
                color = PocketShellColors.TextMuted,
                style = PocketShellType.labelMono,
                textAlign = TextAlign.End,
                modifier = Modifier.width(84.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
