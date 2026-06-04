package com.pocketshell.uikit.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.components.Badge
import com.pocketshell.uikit.components.BadgeRole
import com.pocketshell.uikit.components.Kebab
import com.pocketshell.uikit.components.KebabItem
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.components.SectionHeader
import com.pocketshell.uikit.components.StatusDot
import com.pocketshell.uikit.model.ConnectionStatus
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme

/**
 * Visual `@Preview` set for the shared ui-kit primitives added in Slice S1
 * (#480): [ScreenHeader], [ListRow], [Badge], [Kebab], [SectionHeader]. These
 * are the row/header/badge/overflow primitives every screen (Slices A–E of
 * #479) will consume, so the combined preview lets a reviewer see the whole
 * design language assembled the way a real screen stacks it.
 */
@Preview(name = "ui-kit primitives (S1)", showBackground = false, heightDp = 900, widthDp = 412)
@Composable
fun PrimitivesPreview() {
    PocketShellTheme {
        Surface(
            color = PocketShellColors.Background,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                ScreenHeader(
                    title = "Hosts",
                    subtitle = "4 hosts · 7 sessions",
                    trailing = {
                        Badge(label = "live", role = BadgeRole.Active, mono = false)
                    },
                )

                SectionHeader(label = "Sessions", count = 3)

                ListRow(
                    title = "agent-main",
                    subtitle = "~/proj/agent",
                    leading = { StatusDot(status = ConnectionStatus.Connected) },
                    trailing = {
                        Badge(label = "Claude", role = BadgeRole.Agent)
                        Kebab(
                            items = listOf(
                                KebabItem("Rename", onClick = {}),
                                KebabItem("Kill", onClick = {}),
                            ),
                        )
                    },
                    onClick = {},
                )
                ListRow(
                    title = "training",
                    subtitle = "~/ml/run",
                    leading = { StatusDot(status = ConnectionStatus.Idle) },
                    trailing = { Badge(label = "shell", role = BadgeRole.Shell) },
                    onClick = {},
                )
                ListRow(
                    title = "deploy-watch",
                    subtitle = "deploy@prod.acme.io",
                    leading = { StatusDot(status = ConnectionStatus.Error) },
                    trailing = { Badge(label = "error", role = BadgeRole.Error) },
                    onClick = {},
                )
                ListRow(
                    title = "Appearance",
                    onClick = {},
                )
            }
        }
    }
}

@Preview(name = "ScreenHeader")
@Composable
fun ScreenHeaderPreview() {
    PocketShellTheme {
        PrimitivesSurface(padding = 0.dp) {
            ScreenHeader(
                title = "Sessions",
                subtitle = "2 active · 1 detached",
                trailing = { Badge(label = "live", role = BadgeRole.Active, mono = false) },
            )
        }
    }
}

@Preview(name = "ListRow (clickable + decorative)")
@Composable
fun ListRowPreview() {
    PocketShellTheme {
        PrimitivesSurface(padding = 0.dp) {
            Column(modifier = Modifier.fillMaxWidth()) {
                ListRow(
                    title = "agent-main",
                    subtitle = "~/proj/agent",
                    leading = { StatusDot(status = ConnectionStatus.Connected) },
                    trailing = { Badge(label = "Claude", role = BadgeRole.Agent) },
                    onClick = {},
                )
                ListRow(
                    title = "read-only row",
                    subtitle = "no onClick → decorative",
                )
            }
        }
    }
}

@Preview(name = "Badge (roles)")
@Composable
fun BadgePreview() {
    PocketShellTheme {
        PrimitivesSurface {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Badge(label = "Claude", role = BadgeRole.Agent)
                Badge(label = "shell", role = BadgeRole.Shell)
                Badge(label = "active", role = BadgeRole.Active)
                Badge(label = "idle", role = BadgeRole.Idle)
                Badge(label = "error", role = BadgeRole.Error)
            }
        }
    }
}

@Preview(name = "Kebab")
@Composable
fun KebabPreview() {
    PocketShellTheme {
        PrimitivesSurface {
            Kebab(
                items = listOf(
                    KebabItem("Ports", onClick = {}),
                    KebabItem("Share", onClick = {}),
                    KebabItem("Re-check setup", onClick = {}),
                ),
            )
        }
    }
}

@Preview(name = "SectionHeader")
@Composable
fun SectionHeaderPreview() {
    PocketShellTheme {
        PrimitivesSurface(padding = 0.dp) {
            Column(modifier = Modifier.fillMaxWidth()) {
                SectionHeader(label = "Hosts", count = 4)
                SectionHeader(label = "Settings")
            }
        }
    }
}

/**
 * Local preview surface mirror of `ComponentsPreview`'s helper — kept private to
 * this file so the two preview files stay independent.
 */
@Composable
private fun PrimitivesSurface(
    padding: androidx.compose.ui.unit.Dp = 16.dp,
    content: @Composable () -> Unit,
) {
    Surface(
        color = PocketShellColors.Background,
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
