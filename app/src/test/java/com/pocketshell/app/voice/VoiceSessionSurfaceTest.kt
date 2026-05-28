package com.pocketshell.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class VoiceSessionSurfaceTest {
    @Test
    fun defaultSessionChipsDoNotDuplicateDictation() {
        assertFalse(DefaultSessionChips.contains("dictate"))
    }

    @Test
    fun showKeyboardChipUsesExplicitActionLabel() {
        assertEquals("show keyboard", SHOW_KEYBOARD_CHIP_LABEL)
    }
}
