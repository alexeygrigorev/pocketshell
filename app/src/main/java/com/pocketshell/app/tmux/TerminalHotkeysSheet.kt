package com.pocketshell.app.tmux

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
import com.pocketshell.uikit.components.HotkeySection
import com.pocketshell.uikit.components.HotkeyLongPressAction
import com.pocketshell.uikit.components.TERMINAL_HOTKEYS_PANEL_TAG
import com.pocketshell.uikit.components.TerminalHotkeysPanel
import com.pocketshell.uikit.components.TerminalHotkeysPage
import com.pocketshell.uikit.model.KeyBinding
import com.pocketshell.uikit.model.KeyKind
import com.pocketshell.uikit.theme.PocketShellColors
import androidx.compose.ui.platform.testTag

/**
 * Issue #784: the dedicated terminal-hotkeys panel hosted in its OWN
 * [ModalBottomSheet] — not inside the Prompt Composer, not part of the soft
 * keyboard. Opened from the terminal bottom controls (the "⌨ Terminal hotkeys"
 * launcher). The sheet stays open after a key tap so the user can fire several
 * keys in a row (arrow navigation, `^B ^B`, …); `×` / scrim tap / system back
 * dismiss it.
 *
 * The body is the pure-renderer [TerminalHotkeysPanel] (ui-kit). This sheet owns
 * the Main/Ctrl page state, surface, and insets; the per-key wire mapping lives
 * in [TmuxSessionViewModel.onKeyBarKey] via the caller's [onKey].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TerminalHotkeysSheet(
    mainSections: List<HotkeySection>,
    ctrlSections: List<HotkeySection>,
    onKey: (KeyBinding) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    // Skip the half-expand stop: the panel is short content-height chrome, so
    // it should land fully open in one go like the agent-command palette.
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {
    // Issue #1662: sheet-local transient mode. Removing the sheet from
    // composition resets this to Main, so reopening can never inherit Ctrl mode.
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
                    mainSections
                } else {
                    ctrlSections
                },
                page = currentPage,
                onKey = onKey,
                onLongKey = { binding ->
                    when (binding.label) {
                        "^C" -> onKey(
                            KeyBinding(TmuxHotkeyInterruptX2Label, KeyKind.Regular),
                        )
                        "^D" -> onKey(
                            KeyBinding(TmuxHotkeyEofX2Label, KeyKind.Regular),
                        )
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
                ctrlFlowLabel = TmuxHotkeyCtrlFlowLabel,
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
