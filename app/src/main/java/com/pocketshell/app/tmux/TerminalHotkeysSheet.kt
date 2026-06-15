package com.pocketshell.app.tmux

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
import androidx.compose.ui.Modifier
import com.pocketshell.uikit.components.HotkeySection
import com.pocketshell.uikit.components.TERMINAL_HOTKEYS_PANEL_TAG
import com.pocketshell.uikit.components.TerminalHotkeysPanel
import com.pocketshell.uikit.model.KeyBinding
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
 * The body is the pure-renderer [TerminalHotkeysPanel] (ui-kit), which shows
 * EVERY key at once in a tidy grid. This sheet only owns the surface + insets;
 * the per-key wire mapping lives in [TmuxSessionViewModel.onKeyBarKey] via the
 * caller's [onKey].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TerminalHotkeysSheet(
    sections: List<HotkeySection>,
    onKey: (KeyBinding) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    // Skip the half-expand stop: the panel is short content-height chrome, so
    // it should land fully open in one go like the agent-command palette.
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {
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
        TerminalHotkeysPanel(
            sections = sections,
            onKey = onKey,
            onClose = onDismiss,
            enabled = enabled,
            modifier = Modifier
                .navigationBarsPadding()
                .testTag(TERMINAL_HOTKEYS_PANEL_TAG),
        )
    }
}
