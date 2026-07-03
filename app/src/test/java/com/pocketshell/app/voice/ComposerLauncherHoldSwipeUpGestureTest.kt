package com.pocketshell.app.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #585 (REOPENED — launcher ENTRY gesture): unit coverage for the pure
 * decision that commits the launcher's hold-and-pull-up "open the composer WITH
 * recording" gesture, [launcherSwipeCrossedUpThreshold]. This is the logic that
 * separates a deliberate upward pull (→ open-with-recording) from a plain tap /
 * small wiggle (→ open-only) and from a sideways scroll on the bottom control
 * row (→ never the entry gesture). The full on-device gesture journey lives in
 * the androidTest [ComposerLauncherHoldSwipeUpJourneyTest]; this pins the
 * decision boundary fast in the JVM lane.
 */
class ComposerLauncherHoldSwipeUpGestureTest {

    // A representative 40dp threshold in px (density 2.75 ~= Pixel-class mdpi*2.75).
    private val thresholdPx = 110f

    @Test
    fun upwardPullPastThresholdCommitsEntryGesture() {
        // A clear hold-and-pull-up well past the threshold fires.
        assertTrue(launcherSwipeCrossedUpThreshold(dragX = 0f, dragY = -thresholdPx - 40f, thresholdPx = thresholdPx))
    }

    @Test
    fun upwardPullExactlyAtThresholdCommits() {
        assertTrue(launcherSwipeCrossedUpThreshold(dragX = 0f, dragY = -thresholdPx, thresholdPx = thresholdPx))
    }

    @Test
    fun smallUpwardWiggleBelowThresholdDoesNotCommit() {
        // A tap or a tiny jitter (well under the threshold) must NOT open-with-
        // recording — it stays a plain open-only tap.
        assertFalse(launcherSwipeCrossedUpThreshold(dragX = 3f, dragY = -18f, thresholdPx = thresholdPx))
    }

    @Test
    fun downwardDragDoesNotCommit() {
        assertFalse(launcherSwipeCrossedUpThreshold(dragX = 0f, dragY = thresholdPx + 40f, thresholdPx = thresholdPx))
    }

    @Test
    fun sidewaysScrollDoesNotCommit() {
        // A horizontal scroll across the bottom control row (mostly-horizontal
        // travel) is never the vertical entry gesture, even if it drifts up a bit
        // past the threshold — guards the #568/#584/#801 bottom key-row controls.
        assertFalse(
            launcherSwipeCrossedUpThreshold(
                dragX = -300f,
                dragY = -(thresholdPx + 20f),
                thresholdPx = thresholdPx,
            ),
        )
    }

    @Test
    fun steeplyDiagonalUpwardPullCommits() {
        // More vertical than horizontal + past the threshold = a real upward pull.
        assertTrue(
            launcherSwipeCrossedUpThreshold(
                dragX = 40f,
                dragY = -(thresholdPx + 60f),
                thresholdPx = thresholdPx,
            ),
        )
    }
}
