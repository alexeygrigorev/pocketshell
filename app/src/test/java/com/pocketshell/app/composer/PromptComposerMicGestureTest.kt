package com.pocketshell.app.composer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptComposerMicGestureTest {

    // Issue #585: a small mic-disc rect like the real far-bottom-right target.
    private val micRect = Rect(left = 200f, top = 0f, right = 244f, bottom = 44f)

    @Test
    fun pressInsideMicRectStartsGesture() {
        assertTrue(
            micGestureStartsAt(micRect, Offset(x = 222f, y = 22f), startSlopPx = 24f),
        )
    }

    @Test
    fun pressSlightlyOutsideMicRectStillStartsGestureWithinSlop() {
        // 18px below/right of the disc — a realistic slightly-off hold on the
        // tiny target. Without slop this would silently do nothing; #585's
        // "it doesn't start recording" symptom.
        assertTrue(
            micGestureStartsAt(micRect, Offset(x = 262f, y = 62f), startSlopPx = 24f),
        )
    }

    @Test
    fun pressFarOutsideMicRectDoesNotStartGesture() {
        assertFalse(
            micGestureStartsAt(micRect, Offset(x = 20f, y = 22f), startSlopPx = 24f),
        )
    }

    @Test
    fun unmeasuredBoundsDoNotStartGesture() {
        assertFalse(
            micGestureStartsAt(bounds = null, position = Offset(x = 222f, y = 22f), startSlopPx = 24f),
        )
    }

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
