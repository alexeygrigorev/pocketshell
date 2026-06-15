package com.pocketshell.uikit.render

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import com.pocketshell.uikit.components.Badge
import com.pocketshell.uikit.components.BadgeRole
import com.pocketshell.uikit.components.Banner
import com.pocketshell.uikit.components.BannerRole
import com.pocketshell.uikit.components.Breadcrumb
import com.pocketshell.uikit.components.HostCard
import com.pocketshell.uikit.components.KeyBar
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.LoadingIndicator
import com.pocketshell.uikit.components.SpinnerSize
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.Pill
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.ProgressBar
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.components.SectionHeader
import com.pocketshell.uikit.components.SegmentedToggle
import com.pocketshell.uikit.components.StatusDot
import com.pocketshell.uikit.model.ConnectionStatus
import com.pocketshell.uikit.model.Crumb
import com.pocketshell.uikit.model.HostStatus
import com.pocketshell.uikit.model.KeyBinding
import com.pocketshell.uikit.model.KeyKind
import com.pocketshell.uikit.model.PillKind
import com.pocketshell.uikit.model.ProgressKind
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellShapes
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

    /**
     * Issue #616: the terminal hotkey `KeyBar` (Ctrl/Tab/Esc/arrows) as it must
     * appear directly ABOVE the soft keyboard while the IME is up. The bug was
     * that on the maintainer's device the keybar collapsed into the IME-hidden
     * chip strip — only Gboard's toolbar showed. This render is the fast
     * JVM-level visual check that the full-height bar + every key renders;
     * the emulator keyboard-up screenshot is the acceptance.
     *
     * A grey block below the bar stands in for the soft keyboard so the
     * "directly above the keyboard" framing reads at a glance.
     */
    @Test
    fun keyBarAboveKeyboard() = render("keybar-above-keyboard") {
        Spacer(modifier = Modifier.height(420.dp))
        Surface(color = PocketShellColors.Surface) {
            KeyBar(
                keys = listOf(
                    KeyBinding("Esc", KeyKind.Regular),
                    KeyBinding("Ctrl", KeyKind.Modifier),
                    KeyBinding("^C", KeyKind.Regular),
                    KeyBinding("⏎", KeyKind.Regular),
                    KeyBinding("^D", KeyKind.Regular),
                    KeyBinding("Tab", KeyKind.Regular),
                    KeyBinding("⋯", KeyKind.Regular),
                ),
                onKey = {},
            )
        }
        // Soft-keyboard stand-in so the framing reads "keybar sits above it".
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .background(Color(0xFF202124)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "soft keyboard (system IME)",
                color = PocketShellColors.TextMuted,
                fontSize = 12.sp,
            )
        }
    }

    /**
     * Issue #677: the EXPANDED tmux key bar row (one `⋯` tap from the compact
     * row) now carries `^B` (Ctrl-B, raw 0x02) next to the other control keys.
     * Primary use is Claude Code's "ctrl-b ctrl-b to run in background" — the
     * key is tappable twice in succession to send `C-b C-b` — plus a
     * nested-tmux prefix passthrough. This render mirrors the app-side
     * `TmuxKeyBarLayoutExpanded` so the new key reads at a glance; the
     * emulator/real-agent run is the acceptance.
     */
    @Test
    fun tmuxKeyBarExpandedWithCtrlB() = render("tmux-keybar-expanded-ctrl-b") {
        Spacer(modifier = Modifier.height(420.dp))
        Surface(color = PocketShellColors.Surface) {
            KeyBar(
                keys = listOf(
                    KeyBinding("Esc", KeyKind.Regular),
                    KeyBinding("Ctrl", KeyKind.Modifier),
                    KeyBinding("⏎", KeyKind.Regular),
                    KeyBinding("^B", KeyKind.Regular),
                    KeyBinding("^Z", KeyKind.Regular),
                    KeyBinding("^O", KeyKind.Regular),
                    KeyBinding("^X", KeyKind.Regular),
                    KeyBinding("‹", KeyKind.Arrow),
                    KeyBinding("⌃", KeyKind.Arrow),
                    KeyBinding("⌄", KeyKind.Arrow),
                    KeyBinding("›", KeyKind.Arrow),
                    KeyBinding("×", KeyKind.Arrow),
                ),
                onKey = {},
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .background(Color(0xFF202124)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "soft keyboard (system IME)",
                color = PocketShellColors.TextMuted,
                fontSize = 12.sp,
            )
        }
    }

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
     * Issue #756: the canonical indeterminate [LoadingIndicator] — the single
     * shared loading affordance the maintainer's "sometimes a bar, sometimes a
     * spinning thing" complaint converges onto.
     *
     * This fixture renders all variants together so they read as ONE vocabulary:
     *  - the indeterminate [LoadingIndicator.Bar] (the "in flight" strip),
     *  - the [LoadingIndicator.Spinner] at both enumerated rungs
     *    ([SpinnerSize.Small] inline, [SpinnerSize.Medium] centered), and
     *  - the labelled medium spinner ("Attaching…") — the centered
     *    "whole area is loading" affordance, and
     *  - the [onAccent][LoadingIndicator.Spinner] small spinner shown ON an
     *    accent-filled CTA (the inverted on-accent arc that stays visible
     *    against the accent fill — e.g. a primary submit button mid-flight).
     *
     * Each is labelled in-render so a reviewer can eyeball that the bar height,
     * spinner diameters/strokes, and accent colour are consistent and
     * token-driven (no raw hex, no free spinner dp). The determinate
     * [ProgressBar] (usage quota) stays the percentage-known sibling and is
     * shown last for contrast.
     */
    @Test
    fun loadingIndicators() = render("loading-indicators") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            LoadingLabel("Bar — indeterminate linear (in flight)")
            LoadingIndicator.Bar()

            LoadingLabel("Spinner — Small (inline / on-row)")
            Row(verticalAlignment = Alignment.CenterVertically) {
                LoadingIndicator.Spinner(size = SpinnerSize.Small)
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Transcribing…",
                    color = PocketShellColors.TextSecondary,
                    style = PocketShellType.bodyDense,
                )
            }

            LoadingLabel("Spinner — Medium (centered, no label)")
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                LoadingIndicator.Spinner(size = SpinnerSize.Medium)
            }

            LoadingLabel("Spinner — Medium + label (whole-area loading)")
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                LoadingIndicator.Spinner(
                    size = SpinnerSize.Medium,
                    label = "Attaching…",
                )
            }

            LoadingLabel("Spinner — Small, onAccent (on an accent-filled CTA)")
            Box(
                modifier = Modifier
                    .background(PocketShellColors.Accent, PocketShellShapes.small)
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                LoadingIndicator.Spinner(size = SpinnerSize.Small, onAccent = true)
            }

            LoadingLabel("ProgressBar — determinate sibling (usage quota)")
            ProgressBar(progress = 0.62f, kind = ProgressKind.Default)
        }
    }

    /**
     * Issues #757 + #750: the two tmux connecting/attach states as the
     * `TmuxSessionScreen` now renders them. Both are app-only composables
     * (`EmptyPanesPlaceholder` and `SwitchingLoadingPlaceholder`), so this
     * fixture reproduces their EXACT body — a full-surface [Box] with a centered
     * [LoadingIndicator.Spinner] (Medium) + label — so a reviewer can eyeball the
     * design parity without the emulator.
     *
     *  - #757: the "waiting for tmux panes…" connecting state now shows the SAME
     *    canonical animated spinner instead of static text.
     *  - #750: the "Attaching…" reattach state shows EXACTLY this one centered
     *    spinner — the previous thin under-header progress line is gone, so the
     *    reattach screen no longer shows two indicators at once.
     */
    @Test
    fun tmuxConnectingStates() = render("tmux-connecting-states") {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            LoadingLabel("waiting for tmux panes… (#757 — connecting)")
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .background(color = PocketShellColors.Surface),
                contentAlignment = Alignment.Center,
            ) {
                LoadingIndicator.Spinner(
                    size = SpinnerSize.Medium,
                    label = "waiting for tmux panes…",
                )
            }

            LoadingLabel("Attaching… (#750 — reattach, single indicator)")
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .background(color = PocketShellColors.Background),
                contentAlignment = Alignment.Center,
            ) {
                LoadingIndicator.Spinner(
                    size = SpinnerSize.Medium,
                    label = "Attaching…",
                )
            }
        }
    }

    /**
     * Issue #756: the canonical [PocketShellButton] — the single shared button
     * the ~142 raw Material `Button`/`TextButton` call sites (and the 9 files
     * that hand-re-declared the same accent `ButtonDefaults.buttonColors` block)
     * converge onto.
     *
     * This fixture renders all four [ButtonVariant]s in both states (enabled +
     * disabled) so a reviewer can eyeball that:
     *  - **Primary** is the filled accent CTA (`OnAccent` SemiBold label),
     *  - **Secondary** is the outlined accent action (accent label + accentDim
     *    border, no fill),
     *  - **Text** is the muted chrome-less action (Cancel/Retry),
     *  - **Destructive** is the red-TEXT confirm (NOT a filled red slab), and
     *  - every disabled state collapses to the SAME muted treatment.
     *
     * Each variant sits next to its disabled twin; the bottom row shows the
     * canonical dialog action pairing (`Text` Cancel + `Primary` confirm) the
     * call sites should adopt.
     */
    @Test
    fun pocketShellButtons() = render("pocketshell-buttons") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ButtonRowLabelled("Primary — filled accent CTA") {
                PocketShellButton(text = "Add host", onClick = {}, variant = ButtonVariant.Primary)
                PocketShellButton(text = "Add host", onClick = {}, variant = ButtonVariant.Primary, enabled = false)
            }
            ButtonRowLabelled("Secondary — outlined accent") {
                PocketShellButton(text = "Browse", onClick = {}, variant = ButtonVariant.Secondary)
                PocketShellButton(text = "Browse", onClick = {}, variant = ButtonVariant.Secondary, enabled = false)
            }
            ButtonRowLabelled("Text — muted Cancel/Retry") {
                PocketShellButton(text = "Cancel", onClick = {}, variant = ButtonVariant.Text)
                PocketShellButton(text = "Cancel", onClick = {}, variant = ButtonVariant.Text, enabled = false)
            }
            ButtonRowLabelled("Destructive — red-text confirm") {
                PocketShellButton(text = "Delete key", onClick = {}, variant = ButtonVariant.Destructive)
                PocketShellButton(text = "Delete key", onClick = {}, variant = ButtonVariant.Destructive, enabled = false)
            }

            LoadingLabel("Canonical dialog action row (Text Cancel + Primary confirm)")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PocketShellButton(text = "Cancel", onClick = {}, variant = ButtonVariant.Text)
                Spacer(modifier = Modifier.width(8.dp))
                PocketShellButton(text = "Save changes", onClick = {}, variant = ButtonVariant.Primary)
            }
        }
    }

    /**
     * Issue #756 (compact-variant batch): the dense `compact = true` affordance —
     * the inline banner / dialog / dense-row action (`Dismiss`, `Retry`, `Update`,
     * `Copy keys from…`) that the 6 custom-typography screens previously hand-rolled
     * as raw `TextButton`s at `labelSmall`/`bodyDense`/`12.sp`.
     *
     * This fixture sits the compact treatment NEXT TO the standard one for each
     * variant so a reviewer can eyeball that compact keeps the SAME variant
     * colour/shape/disabled grammar, only at the smaller `bodyDense` (13sp) rung
     * with tighter padding — and that the standard (non-compact) buttons above are
     * visually unchanged. The bottom row reproduces a real migrated banner
     * (`message … Dismiss`) the way EnvScreen / HostListScreen now compose it.
     */
    @Test
    fun pocketShellButtonsCompact() = render("pocketshell-buttons-compact") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ButtonRowLabelled("Text — standard vs compact") {
                PocketShellButton(text = "Dismiss", onClick = {}, variant = ButtonVariant.Text)
                PocketShellButton(text = "Dismiss", onClick = {}, variant = ButtonVariant.Text, compact = true)
                PocketShellButton(
                    text = "Dismiss",
                    onClick = {},
                    variant = ButtonVariant.Text,
                    compact = true,
                    enabled = false,
                )
            }
            ButtonRowLabelled("Destructive — standard vs compact") {
                PocketShellButton(text = "Dismiss", onClick = {}, variant = ButtonVariant.Destructive)
                PocketShellButton(text = "Dismiss", onClick = {}, variant = ButtonVariant.Destructive, compact = true)
            }
            ButtonRowLabelled("Primary / Secondary — compact") {
                PocketShellButton(text = "Update", onClick = {}, variant = ButtonVariant.Primary, compact = true)
                PocketShellButton(text = "Browse", onClick = {}, variant = ButtonVariant.Secondary, compact = true)
            }

            LoadingLabel("Migrated banner — message + compact Text actions")
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Couldn't check for updates (timeout)",
                    color = PocketShellColors.TextSecondary,
                    style = PocketShellType.bodyDense,
                    modifier = Modifier.weight(1f),
                )
                PocketShellButton(text = "Retry", onClick = {}, variant = ButtonVariant.Text, compact = true)
                PocketShellButton(text = "Dismiss", onClick = {}, variant = ButtonVariant.Text, compact = true)
            }
        }
    }

    /** A labelled row pairing an enabled + disabled button for [pocketShellButtons]. */
    @Composable
    private fun ButtonRowLabelled(label: String, buttons: @Composable RowScope.() -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LoadingLabel(label)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                content = buttons,
            )
        }
    }

    /** Small muted caption used to label each loading variant in [loadingIndicators]. */
    @Composable
    private fun LoadingLabel(text: String) {
        Text(
            text = text,
            color = PocketShellColors.TextMuted,
            style = PocketShellType.labelMono,
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
     * their chrome weight: the inactive callout now reads as ONE dense,
     * SINGLE-LINE project row (muted idle dot + count title + a single trailing
     * accent `+`), no longer a heavier divergent "+ Review/Add" pill and no
     * longer carrying the truncated "Tap to …" mono subtitle (#603 / #679 D).
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
            // Single-line: the title names the state, the muted idle dot signals
            // inactive, and the trailing `+` is the affordance — no truncated
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

            // Empty-root callout — no candidate folders yet ("Add").
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

            // Issue #782: each window is now a `<session> [wN]` switcher entry —
            // a sibling of the single-window session rows, tapped to attach to
            // THAT window. The `[wN]` suffix disambiguates the window; the
            // command hint trails. (PocketShell no longer manages windows; these
            // entries only surface windows created OUTSIDE the app.)
            DeclutteredWindowRow(title = "git-ai-shipping-labs-workshops-raw [w0] claude")
            DeclutteredWindowRow(title = "git-ai-shipping-labs-workshops-raw [w1] claude")
        }
    }

    /**
     * Mirror of the app's `WorkspaceSessionWindowRow` AFTER #782: an indented
     * per-window switcher entry that leads with a status dot and the
     * `<session> [wN] claude` title (issue #782's `[wN]` suffix) and carries NO
     * trailing agent badge (the dot + the title already name the agent —
     * repeating it on a badge was the third duplication, #675).
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

                // Issue #718: the Profile selector now shows the HOST-DISCOVERED
                // Claude profiles (fetched via `pocketshell profiles list`),
                // e.g. the default "Claude" + a "Claude (Z.AI)" alias profile.
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SectionHeader(label = "Profile")
                    SegmentedToggle(
                        labels = listOf("Claude", "Claude (Z.AI)"),
                        selectedIndex = 0,
                        onSelected = {},
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        fillSegments = true,
                    )
                }
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
                // Mirrors the migrated SessionTypePickerSheet action row (#756):
                // Cancel = Text variant, Create = Primary variant.
                PocketShellButton(text = "Cancel", onClick = {}, variant = ButtonVariant.Text)
                Spacer(modifier = Modifier.width(8.dp))
                PocketShellButton(text = "Create", onClick = {}, variant = ButtonVariant.Primary)
            }
        }
    }

    /**
     * Issue #678: the `+ window` shell-vs-agent picker. It reuses the exact
     * same [com.pocketshell.app.projects.SessionTypePickerSheet] as the new
     * SESSION flow — the only visible difference is the heading ("New window"
     * instead of "New session") and that the start folder is pre-filled with
     * the active pane's cwd. This fixture proves the window-flavoured heading
     * reads correctly under the same design-system primitives. (The app-level
     * sheet itself cannot be imported into this ui-kit harness, so the body is
     * reconstructed from the same primitives, matching [sessionTypePicker].)
     */
    @Test
    fun newWindowTypePicker() = render("new-window-type-picker") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "New window",
                color = PocketShellColors.Text,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "in /home/alexey/git/pocketshell",
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
                        text = "/home/alexey/git/pocketshell",
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
                // Mirrors the migrated SessionTypePickerSheet action row (#756):
                // Cancel = Text variant, Create = Primary variant.
                PocketShellButton(text = "Cancel", onClick = {}, variant = ButtonVariant.Text)
                Spacer(modifier = Modifier.width(8.dp))
                PocketShellButton(text = "Create", onClick = {}, variant = ButtonVariant.Primary)
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
     * Issue #765: the LONG-draft, keyboard-up composer. The maintainer reported
     * that with a long multi-line message and the soft keyboard up the draft
     * text gets "cut off" and the caret / last line being typed is not visible.
     *
     * Caveat (#555): the real `SheetContent` + `BasicTextField` scroll/IME
     * behaviour lives in the `app` module, which this ui-kit render harness
     * cannot import, so this is a STATIC visual mirror of the intended end
     * state: a draft field that has internally scrolled to keep the LAST lines
     * + the caret visible, with the controls row still fully reachable below
     * it. It is the fast first design check; the actual caret-follow + IME
     * occlusion fix is validated on the emulator with the keyboard up.
     */
    @Test
    fun composerLongDraftCaretVisible() = render("composer-long-draft-caret-visible") {
        val lines = (1..14).map { "line $it of a long multi-line prompt I'm typing" }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(PocketShellColors.Surface, RoundedCornerShape(20.dp))
                .padding(horizontal = 18.dp, vertical = 14.dp),
        ) {
            // Header (fixed).
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
            // Draft field, internally scrolled to the LAST lines (caret at end).
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(PocketShellColors.SurfaceElev, RoundedCornerShape(12.dp))
                    .border(1.dp, PocketShellColors.Border, RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                contentAlignment = Alignment.BottomStart,
            ) {
                Column {
                    // Only the bottom slice of a long draft is visible — the
                    // field has scrolled to keep the caret in view.
                    lines.takeLast(7).forEach { line ->
                        Text(text = line, color = PocketShellColors.Text, fontSize = 14.sp)
                    }
                    // The caret on the last line being typed.
                    Row {
                        Text(
                            text = "line 14, still typing",
                            color = PocketShellColors.Text,
                            fontSize = 14.sp,
                        )
                        Box(
                            modifier = Modifier
                                .padding(start = 1.dp)
                                .width(2.dp)
                                .height(18.dp)
                                .background(PocketShellColors.Accent),
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            // Controls row — must stay fully reachable below the field.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(22.dp))
                        .background(PocketShellColors.SurfaceElev, RoundedCornerShape(22.dp))
                        .padding(horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
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
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(PocketShellColors.Accent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "●", color = PocketShellColors.OnAccent, fontSize = 18.sp)
                }
            }
        }
    }

    /**
     * Issue #755: the terminal hotkey [KeyBar] relocated INTO the composer's
     * inset-anchored column, as the sticky row directly ABOVE the action
     * controls (the #765 layout, now with a key bar). With the keyboard up the
     * key bar rides the same IME inset as the rest of the composer and can never
     * be occluded (the v0.4.0 "key bar completely hidden by the keyboard"
     * regression this fixes).
     *
     * Caveat (#555): the real `SheetContent` lives in `:app`, which this ui-kit
     * harness cannot import, so this is a STATIC visual mirror of the intended
     * layout — header, draft, then [KeyBar], then the action row, all above the
     * soft-keyboard stand-in. The emulator keyboard-up screenshot
     * (`PromptComposerKeyBarImeReachabilityTest`) is the acceptance.
     */
    @Test
    fun composerWithKeyBarAboveKeyboard() = render("composer-with-key-bar-above-keyboard") {
        Spacer(Modifier.height(220.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(PocketShellColors.Surface, RoundedCornerShape(20.dp))
                .padding(horizontal = 18.dp, vertical = 14.dp),
        ) {
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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(PocketShellColors.SurfaceElev, RoundedCornerShape(12.dp))
                    .border(1.dp, PocketShellColors.Border, RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Text(
                    text = "git status --short",
                    color = PocketShellColors.Text,
                    fontSize = 14.sp,
                )
            }
            Spacer(Modifier.height(12.dp))
            // Issue #755: the relocated key bar — sticky row directly above the
            // action controls, riding the composer's IME inset.
            KeyBar(
                keys = listOf(
                    KeyBinding("Esc", KeyKind.Regular),
                    KeyBinding("Ctrl", KeyKind.Modifier),
                    KeyBinding("^C", KeyKind.Regular),
                    KeyBinding("⏎", KeyKind.Regular),
                    KeyBinding("^D", KeyKind.Regular),
                    KeyBinding("Tab", KeyKind.Regular),
                    KeyBinding("⋯", KeyKind.Regular),
                ),
                onKey = {},
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(22.dp))
                        .background(PocketShellColors.SurfaceElev, RoundedCornerShape(22.dp))
                        .padding(horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
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
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(PocketShellColors.Accent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "●", color = PocketShellColors.OnAccent, fontSize = 18.sp)
                }
            }
        }
        // Soft-keyboard stand-in so "key bar sits above the keyboard" reads.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .background(Color(0xFF202124)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "soft keyboard (system IME)",
                color = PocketShellColors.TextMuted,
                fontSize = 12.sp,
            )
        }
    }

    /**
     * Issue #767: the `/`-triggered inline command autocomplete dropdown open in
     * the composer with the keyboard up. The maintainer asked for a Slack /
     * ChatGPT-style slash-command list that appears the moment the draft starts
     * with `/`, filters as you type, and inserts the chosen command into the
     * field. This mirrors that surface: a `/comp` draft, the filtered command
     * rows floating directly above the field, all riding the composer column
     * above the soft-keyboard stand-in.
     *
     * Caveat (#555): the real `SheetContent` + `SlashCommandDropdown` live in
     * `:app`, which this ui-kit harness cannot import, so this is a STATIC visual
     * mirror of the intended layout — dropdown, then header, then the `/comp`
     * field, then the action row, above the keyboard. The keyboard-up emulator
     * screenshot (PromptComposerSlashAutocompleteImeTest) is the acceptance.
     */
    @Test
    fun composerSlashAutocomplete() = render("composer-slash-autocomplete") {
        Spacer(Modifier.height(140.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(PocketShellColors.Surface, RoundedCornerShape(20.dp))
                .padding(horizontal = 18.dp, vertical = 14.dp),
        ) {
            // The autocomplete dropdown — rides the top of the composer column,
            // above the field, filtered to commands matching the typed `/comp`.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(PocketShellColors.SurfaceElev, RoundedCornerShape(12.dp))
                    .border(1.dp, PocketShellColors.BorderSoft, RoundedCornerShape(12.dp)),
            ) {
                SlashCommandMirrorRow("Compact context", "Summarise the conversation to free up context.", "/compact", arg = true)
            }
            Spacer(Modifier.height(12.dp))
            // Header.
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
            // The draft field showing the `/comp` slash query being typed.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(PocketShellColors.SurfaceElev, RoundedCornerShape(12.dp))
                    .border(1.dp, PocketShellColors.Border, RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Row {
                    Text(text = "/comp", color = PocketShellColors.Text, fontSize = 14.sp)
                    Box(
                        modifier = Modifier
                            .padding(start = 1.dp)
                            .width(2.dp)
                            .height(18.dp)
                            .background(PocketShellColors.Accent),
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            // Action row — stays reachable below the field.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(22.dp))
                        .background(PocketShellColors.SurfaceElev, RoundedCornerShape(22.dp))
                        .padding(horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
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
            }
        }
        // Soft-keyboard stand-in so "dropdown sits above the keyboard" reads.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .background(Color(0xFF202124)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "soft keyboard (system IME)",
                color = PocketShellColors.TextMuted,
                fontSize = 12.sp,
            )
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

    // Issue #767: a single mirror row of the `/`-autocomplete dropdown — label +
    // description + the command badge, matching the app-level
    // `SlashCommandDropdown` row visuals (which ui-kit can't import).
    @Composable
    private fun SlashCommandMirrorRow(
        label: String,
        description: String,
        command: String,
        arg: Boolean = false,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    color = PocketShellColors.Text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = description,
                    color = PocketShellColors.TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (arg) {
                Text(text = "+arg", color = PocketShellColors.TextSecondary, fontSize = 11.sp)
            }
            Badge(label = command, role = BadgeRole.Agent, mono = false)
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
     * Issue #714: the file-viewer review-mode commentable text panel. The real
     * `CommentableTextPanel` lives in the `app` module, which the ui-kit render
     * harness can't import, so this mirrors its per-line row layout with the
     * same theme tokens: a gutter (1-based line number, accent + a dot when
     * commented) plus the monospace source line, and a subtle row tint on a
     * commented line. Lines 4 and 9 carry comments here (gutter dots). The
     * emulator review-flow run is slice 2's acceptance; this is the fast first
     * design check for the gutter + dot affordance.
     */
    @Test
    fun reviewCommentableTextPanel() = render("review-commentable-text-panel") {
        val lines = listOf(
            "package com.example.hot",
            "",
            "class Loop(val n: Int) {",
            "    val cache = compute(n)   // commented",
            "",
            "    fun run(): Result {",
            "        for (i in 0 until n) {",
            "            step(i)",
            "            return null      // commented",
            "        }",
            "    }",
            "}",
        )
        val commented = setOf(4, 9)
        Column(modifier = Modifier.fillMaxWidth().background(PocketShellColors.TermBg)) {
            lines.forEachIndexed { index, text ->
                ReviewLineRow(lineNo = index + 1, text = text, commented = (index + 1) in commented)
            }
        }
    }

    /**
     * Issue #763 — post-Submit confirmation sheet content: the saved YAML path
     * (copyable row) plus the "Attach to current session" / "Done" actions.
     * Mirrors the app's `ReviewSubmittedSheet` so the design loop can inspect it
     * without the emulator (ui-kit can't import the app composable).
     */
    @Test
    fun reviewSubmittedSheet() = render("review-submitted-sheet") {
        val savedPath = "/home/alexey/inbox/pocketshell/reviews/README.md-20260614-025147.yaml"
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(PocketShellColors.Surface)
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Text(
                text = "Review saved",
                style = PocketShellType.bodyDense,
                fontWeight = FontWeight.SemiBold,
                color = PocketShellColors.Text,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Sent 2 comments to agents. It's in the reviews inbox and you " +
                    "can route it into this session.",
                style = PocketShellType.bodyDense,
                color = PocketShellColors.TextSecondary,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PocketShellColors.SurfaceElev, PocketShellShapes.small)
                    .border(1.dp, PocketShellColors.BorderSoft, PocketShellShapes.small)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = savedPath,
                    style = PocketShellType.bodyMono,
                    color = PocketShellColors.Text,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Copy",
                    style = PocketShellType.labelMono,
                    fontWeight = FontWeight.SemiBold,
                    color = PocketShellColors.Accent,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            PocketShellButton(
                text = "Attach to current session",
                onClick = {},
                variant = ButtonVariant.Primary,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            PocketShellButton(
                text = "Done",
                onClick = {},
                variant = ButtonVariant.Secondary,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    /** Mirror of the app's `CommentableLineRow` (#714): gutter + dot + line text. */
    @Composable
    private fun ReviewLineRow(lineNo: Int, text: String, commented: Boolean) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (commented) {
                        Modifier.background(PocketShellColors.SurfaceElev.copy(alpha = 0.4f))
                    } else {
                        Modifier
                    },
                ),
            verticalAlignment = Alignment.Top,
        ) {
            Row(
                modifier = Modifier
                    .width(56.dp)
                    .padding(start = 8.dp, top = 2.dp, bottom = 2.dp, end = 6.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.Top,
            ) {
                if (commented) {
                    Box(
                        modifier = Modifier
                            .padding(top = 4.dp, end = 4.dp)
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(PocketShellColors.Accent),
                    )
                }
                Text(
                    text = lineNo.toString(),
                    color = if (commented) PocketShellColors.Accent else PocketShellColors.TextMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            }
            Text(
                text = text,
                color = PocketShellColors.TermText,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f).padding(end = 12.dp, top = 2.dp, bottom = 2.dp),
            )
        }
    }

    /**
     * Issue #461 (slice 1): the shared [Banner] callout in all four semantic
     * roles. Proves each [BannerRole] paints its own foreground (text + icon +
     * border) + 12%-alpha fill purely from `LocalPocketShellSemantic` — Info
     * cyan, Warning amber, Error red, AgentHint purple — under the real theme.
     * Also shows the no-icon variant (Error here) so the icon slot is verified
     * as optional.
     */
    @Test
    fun bannerRoles() = render("banner-roles") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Banner(
                text = "Tap to review folders under this root.",
                role = BannerRole.Info,
                leadingIcon = Icons.Filled.Info,
            )
            Banner(
                text = "This host needs setup before sessions can start.",
                role = BannerRole.Warning,
                leadingIcon = Icons.Filled.Warning,
            )
            Banner(
                text = "Disconnected — could not reach the remote.",
                role = BannerRole.Error,
            )
            Banner(
                text = "Sends here route to the agent, not the shell.",
                role = BannerRole.AgentHint,
                leadingIcon = Icons.Filled.Info,
            )
        }
    }

    /**
     * Issue #461 (slice 2, G3): the four primitives migrated off off-ladder raw
     * literals onto the token layer — [Pill] (status badges), [SegmentedToggle]
     * (mode switch), [Breadcrumb] (path chrome), and [ProgressBar] (usage fill).
     * This is the fast visual check that the token migration is a no-/low-op:
     * Pill + the segment chips snap onto `PocketShellShapes.small` (8dp) and the
     * chip padding rung; the toggle/crumb labels snap onto the type ladder; the
     * progress track keeps its deliberate sub-ladder micro radius. Compare
     * against `docs/mockups/usage.html` (pills/progress) and `session.html`
     * (breadcrumb).
     */
    @Test
    fun migratedPrimitives() = render("migrated-primitives") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Pill — all four status kinds.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(label = "ok", kind = PillKind.Ok)
                Pill(label = "warn", kind = PillKind.Warn)
                Pill(label = "blocked", kind = PillKind.Blocked)
                Pill(label = "error", kind = PillKind.Error)
            }

            // SegmentedToggle — the canonical 2-up header switch + a 3-up.
            SegmentedToggle(
                labels = listOf("Terminal", "Conversation"),
                selectedIndex = 0,
                onSelected = {},
            )
            SegmentedToggle(
                labels = listOf("Overview", "History", "Issues"),
                selectedIndex = 1,
                onSelected = {},
                fillSegments = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // Breadcrumb — host > session > pane path chrome with the live dot.
            Breadcrumb(
                crumbs = listOf(
                    Crumb(label = "hetzner", isCurrent = false, onClick = {}),
                    Crumb(label = "agent-main", isCurrent = false, onClick = {}),
                    Crumb(label = "claude", isCurrent = true, onClick = {}),
                ),
                onBack = {},
                onMore = {},
            )

            // ProgressBar — the three usage fill levels.
            ProgressBar(progress = 0.35f, kind = ProgressKind.Default)
            ProgressBar(progress = 0.78f, kind = ProgressKind.Warn)
            ProgressBar(progress = 1.0f, kind = ProgressKind.Danger)
        }
    }

    /**
     * Renders [content] wrapped in the real [PocketShellTheme] on the app's dark
     * background and snapshots the composition to `build/renders/<name>.png`.
     */
    /**
     * Issue #612: the brand-aligned composer launcher glyph. The app's real
     * `ComposerLauncherIcon` (in `:app` `VoiceSessionSurface.kt`) can't be
     * imported here, so this mirrors its exact `>_` geometry + button styling
     * (enabled + disabled) so the maintainer can judge the glyph direction in
     * a fast JVM render. The on-emulator launcher is the acceptance check.
     */
    @Test
    fun composerLauncherBrandGlyph() = render("composer-launcher-brand-glyph") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Enabled state — cyan accent glyph in an elevated rounded button.
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(PocketShellColors.SurfaceElev, RoundedCornerShape(10.dp))
                    .border(1.dp, PocketShellColors.AccentDim, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = composerLauncherBrandIcon,
                    contentDescription = null,
                    tint = PocketShellColors.Accent,
                    modifier = Modifier.size(20.dp),
                )
            }
            // Disabled state.
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(PocketShellColors.Surface, RoundedCornerShape(10.dp))
                    .border(1.dp, PocketShellColors.Border, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = composerLauncherBrandIcon,
                    contentDescription = null,
                    tint = PocketShellColors.TextSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }
            // Large reference so the >_ motif is unambiguous at inspection size.
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .background(PocketShellColors.SurfaceElev, RoundedCornerShape(18.dp))
                    .border(1.dp, PocketShellColors.AccentDim, RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = composerLauncherBrandIcon,
                    contentDescription = null,
                    tint = PocketShellColors.Accent,
                    modifier = Modifier.size(44.dp),
                )
            }
        }
        Text(
            text = "Composer launcher: enabled · disabled · large reference",
            color = PocketShellColors.TextSecondary,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 18.dp),
        )
    }

    /**
     * Issue #612: a fast preview of the Android 13+ themed-icon silhouette. The
     * real silhouette is `drawable/ic_launcher_monochrome.xml` (the `>_` mark);
     * here it is the same geometry tinted to simulate Material You wallpaper
     * tinting (the system supplies the circular backdrop + tint at draw time).
     * This is a JVM approximation; the launcher themed render is the real check.
     */
    @Test
    fun themedIconSilhouettePreview() = render("themed-icon-silhouette") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Simulated Material You themed tiles: tinted circle backdrop +
            // tinted >_ silhouette. Two example wallpaper tints.
            listOf(
                Color(0xFF1C2B33) to PocketShellColors.Accent,
                Color(0xFF2A2433) to Color(0xFFCBB6F0),
            ).forEach { (backdrop, tint) ->
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(backdrop, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = launcherMonochromeIcon,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(48.dp),
                    )
                }
            }
        }
        Text(
            text = "Themed-icon silhouette (Material You) — simulated tints",
            color = PocketShellColors.TextSecondary,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 18.dp),
        )
    }

    private val launcherMonochromeIcon: ImageVector by lazy {
        // Mirror of drawable/ic_launcher_monochrome.xml (#612), remapped from
        // the 108-viewport launcher coords into a 24-viewport icon. Chevron `>`
        // + cursor `_`, the brand silhouette the themed icon tints.
        val builder = PathBuilder()
        // Chevron `>` (108-coords M43,43 L50,43 L63,57 L50,71 L43,71 L56,57 Z
        // scaled by 24/108 ~= 0.222, recentred).
        builder.moveTo(6.5f, 6.5f)
        builder.lineTo(9.5f, 6.5f)
        builder.lineTo(15f, 12f)
        builder.lineTo(9.5f, 17.5f)
        builder.lineTo(6.5f, 17.5f)
        builder.lineTo(12f, 12f)
        builder.close()
        // Cursor `_`.
        builder.moveTo(11.5f, 15f)
        builder.lineTo(17.5f, 15f)
        builder.lineTo(17.5f, 17.5f)
        builder.lineTo(11.5f, 17.5f)
        builder.close()
        ImageVector.Builder(
            name = "LauncherMonochrome",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply { addPath(pathData = builder.nodes, fill = SolidColor(Color.White)) }.build()
    }

    private val composerLauncherBrandIcon: ImageVector by lazy {
        // Mirror of app `ComposerLauncherIcon` (#612) — the `>_` brand motif.
        val builder = PathBuilder()
        // Prompt chevron `>`.
        builder.moveTo(6f, 6.5f)
        builder.lineTo(8.6f, 6.5f)
        builder.lineTo(13.6f, 12f)
        builder.lineTo(8.6f, 17.5f)
        builder.lineTo(6f, 17.5f)
        builder.lineTo(11f, 12f)
        builder.close()
        // Cursor block `_`.
        builder.moveTo(13.5f, 15.5f)
        builder.lineTo(18.5f, 15.5f)
        builder.lineTo(18.5f, 17.5f)
        builder.lineTo(13.5f, 17.5f)
        builder.close()
        ImageVector.Builder(
            name = "ComposerLauncher",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply { addPath(pathData = builder.nodes, fill = SolidColor(Color.White)) }.build()
    }

    /**
     * Issue #764 — the image-annotation mode toolbar + a mocked annotated image.
     * The real annotate UI is app-only (`FileViewerScreen.ImagePanel` + the
     * `AnnotationToolbar`) and can't be imported into this ui-kit test source set,
     * so this case REPRODUCES the toolbar with the same ui-kit tokens (tool pills,
     * the fixed swatch row, Undo/Done) over a mock image canvas with a Pen stroke
     * and an Arrow, as a fast first visual check of the layout + colour language.
     * The emulator run is the acceptance check for the live composable.
     */
    @Test
    fun imageAnnotateToolbar() = render("image-annotate-toolbar") {
        // Mock "image with markup": a dark canvas + a drawn red arrow + a freehand
        // circle, to convey what the user sees in annotate mode.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(360.dp)
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF010409)),
        ) {
            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                val red = Color(0xFFEF4444)
                // Freehand circle-ish stroke.
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(size.width * 0.30f, size.height * 0.40f)
                    cubicTo(
                        size.width * 0.20f, size.height * 0.20f,
                        size.width * 0.50f, size.height * 0.15f,
                        size.width * 0.55f, size.height * 0.35f,
                    )
                    cubicTo(
                        size.width * 0.60f, size.height * 0.55f,
                        size.width * 0.30f, size.height * 0.62f,
                        size.width * 0.30f, size.height * 0.40f,
                    )
                }
                drawPath(
                    path,
                    color = red,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 8f),
                )
                // Arrow pointing at the circled region.
                val s = androidx.compose.ui.geometry.Offset(size.width * 0.85f, size.height * 0.80f)
                val e = androidx.compose.ui.geometry.Offset(size.width * 0.58f, size.height * 0.45f)
                drawLine(red, s, e, strokeWidth = 8f)
                drawLine(red, e, androidx.compose.ui.geometry.Offset(e.x + 28f, e.y + 4f), strokeWidth = 8f)
                drawLine(red, e, androidx.compose.ui.geometry.Offset(e.x + 6f, e.y + 28f), strokeWidth = 8f)
            }
        }
        // The toolbar reproduction.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(PocketShellColors.Surface)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf("Pan" to false, "Pen" to true, "Arrow" to false).forEach { (label, active) ->
                    Box(
                        modifier = Modifier
                            .height(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (active) PocketShellColors.AccentSoft else PocketShellColors.SurfaceElev)
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            color = if (active) PocketShellColors.Accent else PocketShellColors.TextSecondary,
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp,
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                listOf(0xFFEF4444, 0xFFF59E0B, 0xFF22C55E, 0xFF22D3EE, 0xFFFFFFFF).forEachIndexed { i, c ->
                    Box(
                        modifier = Modifier.size(28.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(if (i == 0) 24.dp else 20.dp)
                                .clip(CircleShape)
                                .background(Color(c.toInt()))
                                .then(
                                    if (i == 0) Modifier.border(2.dp, PocketShellColors.Text, CircleShape) else Modifier,
                                ),
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Undo", color = PocketShellColors.Accent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(modifier = Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .height(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(PocketShellColors.Accent)
                        .padding(horizontal = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Done", color = PocketShellColors.OnAccent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
            }
        }
    }

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
