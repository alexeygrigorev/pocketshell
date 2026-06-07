package com.pocketshell.app.composer

import org.junit.Assert.assertEquals
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

    @Test
    fun pressStartStartsRecordingOnlyOnce() {
        val tracker = MicSwipeUpLockGestureTracker(lockThresholdPx = 40f)

        assertEquals(MicSwipeUpLockGestureEvent.StartRecording, tracker.onPressStart())
        assertEquals(MicSwipeUpLockGestureEvent.None, tracker.onPressStart())

        assertTrue(tracker.started)
        assertFalse(tracker.locked)
    }

    @Test
    fun upwardDragLocksAfterRecordingHasStarted() {
        val tracker = MicSwipeUpLockGestureTracker(lockThresholdPx = 40f)

        assertEquals(MicSwipeUpLockGestureEvent.None, tracker.onDrag(dragX = 0f, dragY = -80f))
        assertFalse(tracker.locked)

        assertEquals(MicSwipeUpLockGestureEvent.StartRecording, tracker.onPressStart())
        assertEquals(MicSwipeUpLockGestureEvent.LockRecording, tracker.onDrag(dragX = 0f, dragY = -80f))
        assertTrue(tracker.locked)
    }

    @Test
    fun releaseAfterLockDoesNotToggleRecording() {
        val tracker = MicSwipeUpLockGestureTracker(lockThresholdPx = 40f)

        assertEquals(MicSwipeUpLockGestureEvent.StartRecording, tracker.onPressStart())
        assertEquals(MicSwipeUpLockGestureEvent.LockRecording, tracker.onDrag(dragX = 0f, dragY = -80f))

        assertEquals(MicSwipeUpLockGestureEvent.None, tracker.onRelease())
        assertTrue(tracker.started)
        assertTrue(tracker.locked)
    }

    @Test
    fun tapPressReleaseKeepsRegularStartSemantics() {
        val tracker = MicSwipeUpLockGestureTracker(lockThresholdPx = 40f)

        assertEquals(MicSwipeUpLockGestureEvent.StartRecording, tracker.onPressStart())
        assertEquals(MicSwipeUpLockGestureEvent.None, tracker.onDrag(dragX = 4f, dragY = -4f))
        assertEquals(MicSwipeUpLockGestureEvent.None, tracker.onRelease())

        assertTrue(tracker.started)
        assertFalse(tracker.locked)
    }
}
