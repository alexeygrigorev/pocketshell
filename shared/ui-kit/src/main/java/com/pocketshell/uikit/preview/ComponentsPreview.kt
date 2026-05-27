package com.pocketshell.uikit.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketshell.uikit.components.Breadcrumb
import com.pocketshell.uikit.components.CommandChip
import com.pocketshell.uikit.components.HostCard
import com.pocketshell.uikit.components.KeyBar
import com.pocketshell.uikit.components.MicButton
import com.pocketshell.uikit.components.Pill
import com.pocketshell.uikit.components.ProgressBar
import com.pocketshell.uikit.components.SessionRow
import com.pocketshell.uikit.components.StatusDot
import com.pocketshell.uikit.model.ConnectionStatus
import com.pocketshell.uikit.model.Crumb
import com.pocketshell.uikit.model.HostStatus
import com.pocketshell.uikit.model.KeyBinding
import com.pocketshell.uikit.model.KeyKind
import com.pocketshell.uikit.model.MicButtonState
import com.pocketshell.uikit.model.PillKind
import com.pocketshell.uikit.model.ProgressKind
import com.pocketshell.uikit.model.Tag
import com.pocketshell.uikit.model.TagKind
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import com.pocketshell.uikit.theme.PocketShellThemeMode

/**
 * Visual `@Preview` for every ui-kit component, rendered together in a
 * single scrolling column on the app's dark background.
 *
 * Goals:
 *
 * 1. Give reviewers a one-glance pass over every component — spacing,
 *    radii, accent placement, status colours — without booting an
 *    emulator.
 * 2. Exercise every state variant we render today (status dots in all
 *    four states, pills in all four kinds, progress bars in all three
 *    kinds, the four mic-button states, etc.) so the previews catch
 *    drift if any of the underlying tokens move.
 * 3. Match the visual chrome of `docs/mockups/dashboard.html` and
 *    friends: cards sit on the body background with the right margins.
 *
 * Per-component `@Preview` aliases (`HostCardPreview`, etc.) sit below
 * the combined preview for the cases where a reviewer wants a tight
 * crop of a single component.
 */
@Preview(name = "All components", showBackground = false, heightDp = 1800, widthDp = 412)
@Composable
fun ComponentsPreview() {
    PocketShellTheme(mode = PocketShellThemeMode.Dark) {
        Surface(
            color = PocketShellColors.Background,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Section("HostCard") {
                    // Issue #201: cycle through the new status vocabulary
                    // so reviewers see the full mapping in one place.
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
                    HostCard(
                        name = "fresh",
                        subtitle = "alex@new.acme.io",
                        status = HostStatus.Unknown,
                        onClick = {},
                    )
                }

                Section("SessionRow") {
                    // Issue #202: mixed-case labels and a distinct
                    // activity-state slot (Attached / Detached) so the
                    // preview reflects what users will see in v0.2.9+.
                    SessionRow(
                        badge = "A",
                        name = "agent-main",
                        host = "hetzner",
                        preview = "I'll check the deploy logs and find what...",
                        time = "2m",
                        tags = listOf(
                            Tag("Claude", TagKind.Agent),
                            Tag("Attached", TagKind.Attached),
                        ),
                        onClick = {},
                    )
                    SessionRow(
                        badge = "T",
                        name = "training",
                        host = "gpu-box",
                        preview = "epoch 14/50  loss=0.232  val=0.847",
                        time = "8m",
                        tags = listOf(
                            Tag("ML", TagKind.Ml),
                            Tag("Detached", TagKind.Detached),
                        ),
                        onClick = {},
                    )
                    SessionRow(
                        badge = "D",
                        name = "deploy-watch",
                        host = "hetzner",
                        preview = "waiting for CI to finish build #2031...",
                        time = "14m",
                        tags = listOf(
                            Tag("Deploy", TagKind.Deploy),
                            Tag("Detached", TagKind.Detached),
                        ),
                        onClick = {},
                    )
                }

                Section("Breadcrumb") {
                    Breadcrumb(
                        crumbs = listOf(
                            Crumb("hetzner", isCurrent = false, onClick = {}),
                            Crumb("agent-main", isCurrent = true, onClick = {}),
                            Crumb("pane 1", isCurrent = false, onClick = {}),
                        ),
                        onBack = {},
                        onMore = {},
                    )
                }

                Section("KeyBar") {
                    KeyBar(
                        keys = listOf(
                            KeyBinding("Esc", KeyKind.Regular),
                            KeyBinding("Tab", KeyKind.Regular),
                            KeyBinding("Ctrl", KeyKind.Modifier),
                            KeyBinding("Alt", KeyKind.Modifier),
                            KeyBinding("‹", KeyKind.Arrow),
                            KeyBinding("⌃", KeyKind.Arrow),
                            KeyBinding("⌄", KeyKind.Arrow),
                            KeyBinding("›", KeyKind.Arrow),
                        ),
                        onKey = {},
                    )
                }

                Section("MicButton (Idle / Recording / Disabled)") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        MicButton(state = MicButtonState.Idle, onClick = {})
                        MicButton(state = MicButtonState.Recording, onClick = {})
                        MicButton(state = MicButtonState.Disabled, onClick = {})
                    }
                }

                Section("StatusDot (Idle / Connecting / Connected / Error)") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        StatusDot(status = ConnectionStatus.Idle)
                        StatusDot(status = ConnectionStatus.Connecting)
                        StatusDot(status = ConnectionStatus.Connected)
                        StatusDot(status = ConnectionStatus.Error)
                    }
                }

                Section("Pill (Ok / Warn / Blocked / Error)") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Pill(label = "OK", kind = PillKind.Ok)
                        Pill(label = "WARN", kind = PillKind.Warn)
                        Pill(label = "BLOCKED", kind = PillKind.Blocked)
                        Pill(label = "ERROR", kind = PillKind.Error)
                    }
                }

                Section("ProgressBar (Default / Warn / Danger)") {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ProgressBar(progress = 0.45f)
                        ProgressBar(progress = 0.78f, kind = ProgressKind.Warn)
                        ProgressBar(progress = 1.0f, kind = ProgressKind.Danger)
                    }
                }

                Section("CommandChip (plain + icon variant)") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // icon = null -> mono `.chip` style.
                        CommandChip(label = "git status", onClick = {})
                        CommandChip(label = "tmux ls", onClick = {})
                        CommandChip(label = "k logs", onClick = {})
                        CommandChip(label = "clear", onClick = {})
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // icon != null -> `.chip.icon-chip` style: accent
                        // background, accent foreground, UI sans font.
                        // `Icons.Filled.PlayArrow` stands in for the
                        // mockup's `● dictate` glyph — we don't bundle a
                        // custom icon set yet, but the visual recipe
                        // (accent-tinted chip with leading mark) is what
                        // we're checking here.
                        CommandChip(
                            label = "dictate",
                            icon = Icons.Filled.PlayArrow,
                            onClick = {},
                        )
                        CommandChip(
                            label = "stop",
                            icon = Icons.Filled.PlayArrow,
                            onClick = {},
                        )
                    }
                }

                // Trailing spacer so the last section doesn't kiss the
                // bottom of the preview canvas.
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Tight per-component previews — useful for the case where a reviewer wants to
// see a single component in isolation (Android Studio's preview pane renders
// one annotated function at a time). Each delegates to the same sample data as
// the combined preview so they don't drift apart.
// -----------------------------------------------------------------------------

@Preview(name = "HostCard")
@Composable
fun HostCardPreview() {
    PocketShellTheme(mode = PocketShellThemeMode.Dark) {
        PreviewSurface {
            HostCard(
                name = "hetzner",
                subtitle = "alex@65.108.42.11",
                status = HostStatus.Attached,
                onClick = {},
            )
        }
    }
}

@Preview(name = "SessionRow")
@Composable
fun SessionRowPreview() {
    PocketShellTheme(mode = PocketShellThemeMode.Dark) {
        PreviewSurface {
            SessionRow(
                badge = "A",
                name = "agent-main",
                host = "hetzner",
                preview = "I'll check the deploy logs and find what...",
                time = "2m",
                tags = listOf(
                    Tag("Claude", TagKind.Agent),
                    Tag("Attached", TagKind.Attached),
                ),
                onClick = {},
            )
        }
    }
}

@Preview(name = "Breadcrumb")
@Composable
fun BreadcrumbPreview() {
    PocketShellTheme(mode = PocketShellThemeMode.Dark) {
        PreviewSurface(padding = 0.dp) {
            Breadcrumb(
                crumbs = listOf(
                    Crumb("hetzner", isCurrent = false, onClick = {}),
                    Crumb("agent-main", isCurrent = true, onClick = {}),
                    Crumb("pane 1", isCurrent = false, onClick = {}),
                ),
                onBack = {},
                onMore = {},
            )
        }
    }
}

@Preview(name = "KeyBar")
@Composable
fun KeyBarPreview() {
    PocketShellTheme(mode = PocketShellThemeMode.Dark) {
        PreviewSurface(padding = 0.dp) {
            KeyBar(
                keys = listOf(
                    KeyBinding("Esc", KeyKind.Regular),
                    KeyBinding("Tab", KeyKind.Regular),
                    KeyBinding("Ctrl", KeyKind.Modifier),
                    KeyBinding("Alt", KeyKind.Modifier),
                    KeyBinding("‹", KeyKind.Arrow),
                    KeyBinding("⌃", KeyKind.Arrow),
                    KeyBinding("⌄", KeyKind.Arrow),
                    KeyBinding("›", KeyKind.Arrow),
                ),
                onKey = {},
            )
        }
    }
}

@Preview(name = "MicButton")
@Composable
fun MicButtonPreview() {
    PocketShellTheme(mode = PocketShellThemeMode.Dark) {
        PreviewSurface {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MicButton(state = MicButtonState.Idle, onClick = {})
                MicButton(state = MicButtonState.Recording, onClick = {})
                MicButton(state = MicButtonState.Disabled, onClick = {})
            }
        }
    }
}

@Preview(name = "StatusDot")
@Composable
fun StatusDotPreview() {
    PocketShellTheme(mode = PocketShellThemeMode.Dark) {
        PreviewSurface {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                StatusDot(status = ConnectionStatus.Idle)
                StatusDot(status = ConnectionStatus.Connecting)
                StatusDot(status = ConnectionStatus.Connected)
                StatusDot(status = ConnectionStatus.Error)
            }
        }
    }
}

@Preview(name = "Pill")
@Composable
fun PillPreview() {
    PocketShellTheme(mode = PocketShellThemeMode.Dark) {
        PreviewSurface {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(label = "OK", kind = PillKind.Ok)
                Pill(label = "WARN", kind = PillKind.Warn)
                Pill(label = "BLOCKED", kind = PillKind.Blocked)
                Pill(label = "ERROR", kind = PillKind.Error)
            }
        }
    }
}

@Preview(name = "ProgressBar")
@Composable
fun ProgressBarPreview() {
    PocketShellTheme(mode = PocketShellThemeMode.Dark) {
        PreviewSurface {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ProgressBar(progress = 0.45f)
                ProgressBar(progress = 0.78f, kind = ProgressKind.Warn)
                ProgressBar(progress = 1.0f, kind = ProgressKind.Danger)
            }
        }
    }
}

@Preview(name = "CommandChip")
@Composable
fun CommandChipPreview() {
    PocketShellTheme(mode = PocketShellThemeMode.Dark) {
        PreviewSurface {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CommandChip(label = "git status", onClick = {})
                CommandChip(label = "tmux ls", onClick = {})
            }
        }
    }
}

/**
 * Tight preview for the icon-chip variant — exercises the
 * `.chip.icon-chip` CSS branch (accent-soft background, accent-dim
 * border, accent text, UI font, leading 14dp icon). `Icons.Filled.PlayArrow`
 * is a stand-in glyph until PocketShell ships its own icon set; the
 * structural recipe (accent-tinted chip with a leading mark) is what
 * this preview verifies.
 */
@Preview(name = "CommandChip (icon variant)")
@Composable
fun CommandChipIconPreview() {
    PocketShellTheme(mode = PocketShellThemeMode.Dark) {
        PreviewSurface {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CommandChip(
                    label = "dictate",
                    icon = Icons.Filled.PlayArrow,
                    onClick = {},
                )
                CommandChip(
                    label = "stop",
                    icon = Icons.Filled.PlayArrow,
                    onClick = {},
                )
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Private preview helpers
// -----------------------------------------------------------------------------

/**
 * Header row for a section inside the combined preview. Renders a
 * faint uppercase label above the section so reviewers can spot what
 * they're looking at while scrolling.
 */
@Composable
private fun Section(label: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label.uppercase(),
            color = PocketShellColors.TextMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
            modifier = Modifier.padding(start = 4.dp, top = 6.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            content()
        }
    }
}

/**
 * Wraps a single-component preview in a black background surface with
 * a sensible padding. Default 16dp; pass `0.dp` for full-bleed
 * components (`Breadcrumb`, `KeyBar`).
 */
@Composable
private fun PreviewSurface(
    padding: androidx.compose.ui.unit.Dp = 16.dp,
    content: @Composable () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(PocketShellColors.Background)
                .padding(PaddingValues(all = padding)),
        ) {
            content()
        }
    }
}

