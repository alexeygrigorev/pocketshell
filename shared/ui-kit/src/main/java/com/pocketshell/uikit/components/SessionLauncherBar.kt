package com.pocketshell.uikit.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellSpacing

const val SESSION_LAUNCHER_BAR_TAG: String = "session-launcher-bar"
const val SESSION_COMPOSER_LAUNCHER_TAG: String = "session-composer-launcher"
const val SESSION_HOTKEYS_LAUNCHER_TAG: String = "session-hotkeys-launcher"

const val SESSION_COMPOSER_LAUNCHER_LABEL: String = "Prompt Composer"
const val SESSION_HOTKEYS_LAUNCHER_LABEL: String = "⌨"

/**
 * Closed-session compact chrome (#2521): a thin chip row that opens the
 * Prompt Composer sheet and the terminal hotkeys panel.
 *
 * No draft field, no 4-key bar, no Send/mic. Those live in the floating
 * sheets this bar launches. The hotkeys chip is omitted when [onOpenHotkeys]
 * is null (no pane to receive control bytes).
 */
@Composable
fun SessionLauncherBar(
    onOpenComposer: () -> Unit,
    onOpenHotkeys: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(color = PocketShellColors.Surface)
            .border(width = 1.dp, color = PocketShellColors.BorderSoft)
            .padding(
                horizontal = PocketShellSpacing.sm,
                vertical = PocketShellSpacing.sm,
            )
            .testTag(SESSION_LAUNCHER_BAR_TAG),
        horizontalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CommandChip(
            label = SESSION_COMPOSER_LAUNCHER_LABEL,
            onClick = onOpenComposer,
            modifier = Modifier.testTag(SESSION_COMPOSER_LAUNCHER_TAG),
        )
        if (onOpenHotkeys != null) {
            CommandChip(
                label = SESSION_HOTKEYS_LAUNCHER_LABEL,
                onClick = onOpenHotkeys,
                modifier = Modifier.testTag(SESSION_HOTKEYS_LAUNCHER_TAG),
            )
        }
    }
}
