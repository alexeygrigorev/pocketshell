package com.pocketshell.app.tmux

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Test

class TmuxLifecycleDialogKeyboardLayoutTest {

    @Test
    fun createSessionDialogKeepsAutocompleteListBelowScrollableBodyHeight() {
        assertTrue(
            "Autocomplete suggestions must fit inside the scroll-bounded create-session dialog body.",
            TmuxCreateSessionStartFolderSuggestionsMaxHeight < TmuxCreateSessionDialogBodyMaxHeight,
        )
    }

    @Test
    fun createSessionDialogKeepsRoomForFieldsAndActionsWhenKeyboardIsOpen() {
        assertTrue(
            "Create-session body should stay compact enough for AlertDialog actions above IME.",
            TmuxCreateSessionDialogBodyMaxHeight <= 360.dp,
        )
    }
}
