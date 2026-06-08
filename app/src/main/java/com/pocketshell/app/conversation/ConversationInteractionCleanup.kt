package com.pocketshell.app.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalTextToolbar

/**
 * Conversation rows expose selectable transcript text. If a user leaves the
 * pane while Compose selection or the search field owns focus, clear that
 * interaction state before the Terminal surface is mounted again.
 */
@Composable
internal fun ConversationInteractionCleanupEffect() {
    val textToolbar = LocalTextToolbar.current
    val focusManager = LocalFocusManager.current
    DisposableEffect(textToolbar, focusManager) {
        onDispose {
            textToolbar.hide()
            focusManager.clearFocus(force = true)
        }
    }
}
