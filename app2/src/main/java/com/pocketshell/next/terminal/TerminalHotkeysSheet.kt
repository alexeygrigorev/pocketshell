package com.pocketshell.next.terminal

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.pocketshell.uikit.components.HotkeyLongPressAction
import com.pocketshell.uikit.components.TERMINAL_HOTKEYS_PANEL_TAG
import com.pocketshell.uikit.components.TerminalHotkeysPanel
import com.pocketshell.uikit.components.TerminalHotkeysPage
import com.pocketshell.uikit.model.KeyBinding
import com.pocketshell.uikit.model.KeyKind
import com.pocketshell.uikit.theme.PocketShellColors

/**
 * The dedicated terminal-hotkeys panel in its own [ModalBottomSheet] (#2521,
 * ported from v0.4.47 `TerminalHotkeysSheet`).
 *
 * Opened from the compact `⌨` launcher. Stays open after a key tap so the
 * user can fire several keys in a row; `×` / scrim / system back dismiss it.
 * The body is the ui-kit [TerminalHotkeysPanel]; this sheet owns Main/Ctrl
 * page state. Per-key bytes go through [keyBarBytes] via [onKey].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalHotkeysSheet(
    onKey: (KeyBinding) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {
    var page by remember { mutableStateOf(TerminalHotkeysPage.Main) }

    BackHandler(enabled = page == TerminalHotkeysPage.Ctrl) {
        page = TerminalHotkeysPage.Main
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = PocketShellColors.Surface,
        contentColor = PocketShellColors.Text,
        modifier = modifier,
        contentWindowInsets = {
            WindowInsets.safeDrawing.only(
                WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
            )
        },
    ) {
        Crossfade(
            targetState = page,
            animationSpec = tween(durationMillis = 150),
            label = "terminal-hotkeys-page",
        ) { currentPage ->
            TerminalHotkeysPanel(
                sections = if (currentPage == TerminalHotkeysPage.Main) {
                    HOTKEY_MAIN_SECTIONS
                } else {
                    HOTKEY_CTRL_SECTIONS
                },
                page = currentPage,
                onKey = onKey,
                onLongKey = { binding ->
                    when (binding.label) {
                        "^C" -> onKey(KeyBinding(KEY_LABEL_INTERRUPT_X2, KeyKind.Regular))
                        "^D" -> onKey(KeyBinding(KEY_LABEL_EOF_X2, KeyKind.Regular))
                    }
                },
                onOpenCtrlPage = {
                    if (enabled) page = TerminalHotkeysPage.Ctrl
                },
                onBackToMain = {
                    if (enabled) page = TerminalHotkeysPage.Main
                },
                onClose = onDismiss,
                enabled = enabled,
                ctrlFlowLabel = HOTKEY_CTRL_FLOW_LABEL,
                longPressActions = if (currentPage == TerminalHotkeysPage.Main) {
                    MainLongPressActions
                } else {
                    emptyMap()
                },
                modifier = Modifier
                    .navigationBarsPadding()
                    .testTag(TERMINAL_HOTKEYS_PANEL_TAG),
            )
        }
    }
}

private val MainLongPressActions: Map<String, HotkeyLongPressAction> = mapOf(
    "^C" to HotkeyLongPressAction(
        cue = "hold ×2",
        accessibilityLabel = "Send Ctrl-C twice",
    ),
    "^D" to HotkeyLongPressAction(
        cue = "hold ×2",
        accessibilityLabel = "Send Ctrl-D twice",
    ),
)
