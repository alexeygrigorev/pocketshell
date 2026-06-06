package com.pocketshell.app.composer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptComposerMicGestureTest {

    @Test
    fun upwardDragPastThresholdLocks() {
        assertTrue(
            micSwipeCrossedLockThreshold(
                dragX = 4f,
                dragY = -41f,
                lockThresholdPx = 40f,
            ),
        )
    }

    @Test
    fun upwardDragBelowThresholdDoesNotLock() {
        assertFalse(
            micSwipeCrossedLockThreshold(
                dragX = 0f,
                dragY = -39f,
                lockThresholdPx = 40f,
            ),
        )
    }

    @Test
    fun mostlyHorizontalDragDoesNotLock() {
        assertFalse(
            micSwipeCrossedLockThreshold(
                dragX = 60f,
                dragY = -45f,
                lockThresholdPx = 40f,
            ),
        )
    }
}
