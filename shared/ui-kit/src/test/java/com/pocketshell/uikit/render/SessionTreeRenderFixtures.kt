package com.pocketshell.uikit.render

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.components.SectionHeader
import com.pocketshell.uikit.components.StatusDot
import com.pocketshell.uikit.model.ConnectionStatus
import com.pocketshell.uikit.theme.PocketShellDensity

/**
 * Issue #2530: the phone session tree as `root → folder → session`, no
 * engine/agent chrome. `SessionTreeScreen` is app-module private, so this
 * mirrors it with the shared ui-kit primitives the screen composes
 * ([ScreenHeader], [SectionHeader], [ListRow], [StatusDot]). Fast first
 * visual check; the emulator journey is the acceptance.
 */
@Composable
internal fun SessionTreeDesktopStyleRender() {
    Column {
        ScreenHeader(
            title = "Sessions",
            subtitle = "4 sessions · 2 roots",
        )
        SectionHeader(label = "~/git", count = 3)
        SectionHeader(
            label = "pocketshell",
            count = 2,
            modifier = Modifier.padding(start = PocketShellDensity.treeIndent),
        )
        ListRow(
            title = "git-pocketshell",
            subtitle = "2m ago",
            leading = { StatusDot(status = ConnectionStatus.Connected) },
            onClick = {},
            modifier = Modifier.padding(start = PocketShellDensity.treeIndent * 2),
        )
        ListRow(
            title = "git-pocketshell-2",
            subtitle = "1h ago",
            leading = { StatusDot(status = ConnectionStatus.Idle) },
            onClick = {},
            modifier = Modifier.padding(start = PocketShellDensity.treeIndent * 2),
        )
        SectionHeader(
            label = "aplexer",
            modifier = Modifier.padding(start = PocketShellDensity.treeIndent),
        )
        ListRow(
            title = "git-aplexer",
            subtitle = "3h ago",
            leading = { StatusDot(status = ConnectionStatus.Idle) },
            onClick = {},
            modifier = Modifier.padding(start = PocketShellDensity.treeIndent * 2),
        )
        SectionHeader(label = "other", count = 1)
        ListRow(
            title = "stray",
            subtitle = "2d ago",
            leading = { StatusDot(status = ConnectionStatus.Idle) },
            onClick = {},
            modifier = Modifier.padding(start = PocketShellDensity.treeIndent),
        )
    }
}
