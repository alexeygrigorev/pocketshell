package com.pocketshell.uikit.render

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.HotkeyLongPressAction
import com.pocketshell.uikit.components.HotkeySection
import com.pocketshell.uikit.components.LoadingIndicator
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.SpinnerSize
import com.pocketshell.uikit.components.TerminalHotkeysPanel
import com.pocketshell.uikit.components.TerminalHotkeysPage
import com.pocketshell.uikit.model.KeyBinding
import com.pocketshell.uikit.model.KeyKind
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellType

@Composable
internal fun TerminalHotkeysPanelRender() {
    Surface(color = PocketShellColors.Surface) {
        TerminalHotkeysPanel(
            sections = sampleMainHotkeySections(),
            page = TerminalHotkeysPage.Main,
            onKey = {},
            onLongKey = {},
            onOpenCtrlPage = {},
            onBackToMain = {},
            onClose = {},
            longPressActions = mapOf(
                "^C" to HotkeyLongPressAction("hold ×2", "Send Ctrl-C twice"),
                "^D" to HotkeyLongPressAction("hold ×2", "Send Ctrl-D twice"),
            ),
        )
    }
}

@Composable
internal fun TerminalHotkeysCtrlPageRender() {
    Surface(color = PocketShellColors.Surface) {
        TerminalHotkeysPanel(
            sections = sampleCtrlHotkeySections(),
            page = TerminalHotkeysPage.Ctrl,
            onKey = {},
            onLongKey = {},
            onOpenCtrlPage = {},
            onBackToMain = {},
            onClose = {},
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
            StaticLoadingIndicator.Spinner(
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
            StaticLoadingIndicator.Spinner(
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

/** Issue #1662 main hotkeys page: common controls plus the dedicated Ctrl-flow action. */
private fun sampleMainHotkeySections(): List<HotkeySection> =
    listOf(
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
        HotkeySection(
            title = "KEYS",
            keys = listOf(
                KeyBinding("Esc", KeyKind.Regular),
                KeyBinding("Tab", KeyKind.Regular),
                KeyBinding("⇧Tab", KeyKind.Regular),
                KeyBinding("Enter", KeyKind.Regular),
            ),
            columns = 4,
        ),
        HotkeySection(
            title = "CTRL",
            keys = listOf("^B", "^C", "^D", "^Q", "^X")
                .map { KeyBinding(it, KeyKind.Regular) },
            columns = 5,
        ),
    )

private fun sampleCtrlHotkeySections(): List<HotkeySection> {
    val rows = listOf("QWERT", "YUIOP", "ASDFG", "HJKL", "ZXCVB", "NM\\")
        .map { row -> row.map { KeyBinding("^$it", KeyKind.Regular) } }
    return listOf(
        HotkeySection(
            title = "CTRL + KEY",
            keys = rows.flatten(),
            columns = 5,
            rows = rows,
        ),
    )
}

@Composable
private fun TerminalLoadingLabel(text: String) {
    Text(
        text = text,
        color = PocketShellColors.TextMuted,
        style = PocketShellType.labelMono,
    )
}

/**
 * Faithful ui-kit-side replica of the app-private #1487 pill for the fast
 * Roborazzi check. The production-session connected journey remains the
 * acceptance proof.
 */
@Composable
internal fun ForwardingPillStatesRender() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TerminalLoadingLabel("Port forwarding — active and restoring")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(PocketShellColors.Background)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ForwardingPillReplica(label = ":8080", tint = PocketShellColors.Accent)
            ForwardingPillReplica(label = "3 ports", tint = PocketShellColors.Accent)
            ForwardingPillReplica(label = "…", tint = PocketShellColors.Amber)
        }
    }
}

@Composable
private fun ForwardingPillReplica(label: String, tint: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(color = tint.copy(alpha = 0.14f), shape = PocketShellShapes.small)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        ForwardingGlyphReplica(tint)
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            color = tint,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun ForwardingGlyphReplica(tint: Color) {
    Canvas(modifier = Modifier.size(12.dp)) {
        val w = size.width
        val h = size.height
        val strokeWidth = w * 0.12f
        drawLine(tint, Offset(0f, h * 0.3f), Offset(w, h * 0.3f), strokeWidth = strokeWidth)
        drawLine(tint, Offset(w * 0.65f, h * 0.05f), Offset(w, h * 0.3f), strokeWidth = strokeWidth)
        drawLine(tint, Offset(w * 0.65f, h * 0.55f), Offset(w, h * 0.3f), strokeWidth = strokeWidth)
        drawLine(tint, Offset(0f, h * 0.7f), Offset(w, h * 0.7f), strokeWidth = strokeWidth)
        drawLine(tint, Offset(w * 0.35f, h * 0.45f), Offset(0f, h * 0.7f), strokeWidth = strokeWidth)
        drawLine(tint, Offset(w * 0.35f, h * 0.95f), Offset(0f, h * 0.7f), strokeWidth = strokeWidth)
    }
}
