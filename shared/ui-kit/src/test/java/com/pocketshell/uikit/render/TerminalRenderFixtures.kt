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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.HotkeySection
import com.pocketshell.uikit.components.LoadingIndicator
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.SpinnerSize
import com.pocketshell.uikit.components.TerminalHotkeysPanel
import com.pocketshell.uikit.model.KeyBinding
import com.pocketshell.uikit.model.KeyKind
import com.pocketshell.uikit.model.KeyModifierState
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellType

@Composable
internal fun TerminalHotkeysPanelRender() {
    Surface(color = PocketShellColors.Surface) {
        TerminalHotkeysPanel(
            sections = sampleHotkeySections(),
            onKey = {},
            onClose = {},
            // Issue #1091: render the sticky `Ctrl` modifier ARMED so the
            // accent treatment on the `Ctrl` key is visually checked.
            modifierState = KeyModifierState.OneShot,
            // Issue #1332: default-collapsed render shows the compact COMMON
            // set only (ARROWS first, then Esc/Tab/Enter/^C/^D) + the "Show
            // more keys" expander.
            initiallyExpanded = true,
        )
    }
}

@Composable
internal fun TerminalHotkeysPanelCollapsedRender() {
    Surface(color = PocketShellColors.Surface) {
        TerminalHotkeysPanel(
            sections = sampleHotkeySections(),
            onKey = {},
            onClose = {},
            initiallyExpanded = false,
        )
    }
}

@Composable
internal fun TmuxConnectingStatesRender() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        TerminalLoadingLabel("waiting for tmux panes… (#757 — connecting)")
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

        TerminalLoadingLabel("Attaching… (#750 — reattach, single indicator)")
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
 * Issue #1521: the DISCONNECTED session state — the fix for the maintainer's "there's
 * nowhere to tap" dead-end. `FailedConnectionRow` + `RevealFailurePlaceholder` are
 * app-only composables, so this fixture reproduces their visible chrome with ui-kit
 * primitives: the top disconnect band ("Disconnected from …. Tap Reconnect to retry.")
 * now carries a PROMINENT accent `PocketShellButton` ("Reconnect") instead of a
 * borderless "Tap to reconnect" text link, and the centered placeholder is the calm
 * "Disconnected." status (the misleading "tap to reconnect above." pointer is gone).
 * A reviewer can eyeball that the Reconnect control reads as an obvious, tappable CTA.
 */
@Composable
internal fun TmuxDisconnectedStateRender() {
    Column(modifier = Modifier.fillMaxWidth()) {
        // The disconnect band (reproduces FailedConnectionRow with the #1521 button).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(color = PocketShellColors.Surface)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Disconnected from alexey@135.181.114.209:22. Tap Reconnect to retry.",
                color = PocketShellColors.TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            PocketShellButton(
                text = "Reconnect",
                onClick = {},
                variant = ButtonVariant.Primary,
                compact = true,
            )
        }
        // The centered calm placeholder (reproduces RevealFailurePlaceholder).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(360.dp)
                .background(color = PocketShellColors.Background),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Disconnected.",
                color = PocketShellColors.TextSecondary,
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
    }
}

@Composable
internal fun TmuxSurfaceReconnectAffordanceRender() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(320.dp)
            .background(color = PocketShellColors.Background),
        contentAlignment = Alignment.Center,
    ) {
        LoadingIndicator.Spinner(
            size = SpinnerSize.Medium,
            label = "Attaching…",
        )
        PocketShellButton(
            text = "Reconnect",
            onClick = {},
            variant = ButtonVariant.Secondary,
            compact = true,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp),
        )
    }
}

/**
 * Issue #1091 + #1332: the EXPANDED hotkeys panel — `CTRL COMBOS` filled with
 * the nano keys (`^G`/`^J`/`^K`/`^O`/`^T`/`^U`/`^W`/`^X`/`^\`), the sticky
 * `Ctrl` modifier, and the a–z LETTERS grid — all revealed behind the
 * expander, with ARROWS still pinned at the top.
 */
private fun sampleHotkeySections(): List<HotkeySection> =
    listOf(
        // Issue #1332: ARROWS first (common, always shown).
        HotkeySection(
            title = "ARROWS",
            keys = listOf(
                KeyBinding("←", KeyKind.Arrow),
                KeyBinding("↑", KeyKind.Arrow),
                KeyBinding("↓", KeyKind.Arrow),
                KeyBinding("→", KeyKind.Arrow),
            ),
            columns = 4,
        ),
        // Issue #1332: the compact COMMON essentials row (always shown).
        HotkeySection(
            title = "COMMON",
            keys = listOf(
                KeyBinding("Esc", KeyKind.Regular),
                KeyBinding("Tab", KeyKind.Regular),
                KeyBinding("Enter", KeyKind.Regular),
                KeyBinding("^C", KeyKind.Regular),
                KeyBinding("^D", KeyKind.Regular),
            ),
            columns = 5,
        ),
        HotkeySection(
            title = "CTRL COMBOS",
            keys = listOf(
                KeyBinding("^A", KeyKind.Regular),
                KeyBinding("^B", KeyKind.Regular),
                KeyBinding("^C", KeyKind.Regular),
                KeyBinding("^D", KeyKind.Regular),
                KeyBinding("^E", KeyKind.Regular),
                KeyBinding("^G", KeyKind.Regular),
                KeyBinding("^J", KeyKind.Regular),
                KeyBinding("^K", KeyKind.Regular),
                KeyBinding("^L", KeyKind.Regular),
                KeyBinding("^O", KeyKind.Regular),
                KeyBinding("^R", KeyKind.Regular),
                KeyBinding("^T", KeyKind.Regular),
                KeyBinding("^U", KeyKind.Regular),
                KeyBinding("^W", KeyKind.Regular),
                KeyBinding("^X", KeyKind.Regular),
                KeyBinding("^Z", KeyKind.Regular),
                KeyBinding("^\\", KeyKind.Regular),
            ),
            columns = 4,
            extended = true,
        ),
        HotkeySection(
            title = "MORE KEYS",
            keys = listOf(KeyBinding("⇧Tab", KeyKind.Regular)),
            columns = 4,
            extended = true,
        ),
        HotkeySection(
            title = "INTERRUPT / EOF",
            keys = listOf(
                KeyBinding("^C×2", KeyKind.Regular),
                KeyBinding("^D×2", KeyKind.Regular),
            ),
            columns = 2,
            extended = true,
        ),
        HotkeySection(
            title = "CTRL + LETTER",
            keys = listOf(KeyBinding("Ctrl", KeyKind.Modifier)),
            columns = 4,
            extended = true,
        ),
        HotkeySection(
            title = "LETTERS",
            keys = ('a'..'z').map { KeyBinding(it.toString(), KeyKind.Regular) },
            columns = 7,
            extended = true,
        ),
    )

@Composable
private fun TerminalLoadingLabel(text: String) {
    Text(
        text = text,
        color = PocketShellColors.TextMuted,
        style = PocketShellType.labelMono,
    )
}
