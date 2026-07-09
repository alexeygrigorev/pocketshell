package com.pocketshell.uikit.render

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.SectionHeader
import com.pocketshell.uikit.components.SegmentedToggle
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellType

@Composable
internal fun SessionTypePickerRender() {
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

@Composable
internal fun NewWindowTypePickerRender() {
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

@Composable
internal fun HostDetailOverflowMenuRender() {
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

@Composable
internal fun SessionKebabMenuRender() {
    SessionKebabMenuPanel(
        sections = listOf(
            "This session" to listOf(
                "Rename session",
                "What is this session?",
                "Stop session",
                "Detach",
                // Issue #892: manual full-viewport reseed escape hatch.
                "Redraw",
                // Issue #993: manual reconnect-in-place escape hatch.
                "Reconnect",
            ),
            "Sessions" to listOf(
                "+ New session",
                "Switch session",
            ),
            "Files" to listOf(
                "Browse files…",
                "Open file…",
            ),
            "Connection" to listOf(
                "Port forwarding",
            ),
            "Host & app" to listOf(
                "Recurring jobs",
                "Usage",
                "Settings",
            ),
        ),
    )
}

/**
 * Static mirror of the grouped `TmuxMoreMenu` opened menu: a [SurfaceElev]
 * rounded panel where each section is a muted [DropdownMenuSectionHeader]-style
 * label, its items as [PocketShellType.bodyDense] rows, and a
 * [HorizontalDivider] between sections — matching the real menu chrome.
 */
@Composable
private fun SessionKebabMenuPanel(sections: List<Pair<String, List<String>>>) {
    Surface(
        color = PocketShellColors.SurfaceElev,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            sections.forEachIndexed { index, (header, items) ->
                if (index > 0) {
                    HorizontalDivider()
                }
                Text(
                    text = header,
                    color = PocketShellColors.TextSecondary,
                    style = PocketShellType.bodyDense,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                items.forEach { label ->
                    Text(
                        text = label,
                        color = PocketShellColors.Text,
                        style = PocketShellType.bodyDense,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
        }
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
