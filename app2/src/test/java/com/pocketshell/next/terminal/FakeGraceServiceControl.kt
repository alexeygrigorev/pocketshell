package com.pocketshell.next.terminal

/**
 * A [GraceServiceControl] a test drives and inspects by hand.
 *
 * [GraceCoordinator] is unit-testable at all only because the Android
 * mechanics ([GraceService], `PARTIAL_WAKE_LOCK`, the notification) sit behind
 * this seam — a test asserts the POLICY ("started once per background window
 * with the right deadline, stopped exactly once on return or expiry") without
 * a `Context`, a `ServiceController`, or Robolectric's service scheduler.
 */
class FakeGraceServiceControl : GraceServiceControl {

    /** Every [start] deadline, in call order. */
    val startedDeadlines: MutableList<Long> = mutableListOf()

    var stopCount: Int = 0
        private set

    val startCount: Int get() = startedDeadlines.size

    /**
     * Is the (ONE, process-global) service up right now?
     *
     * Modelled as a flip-flop rather than `startCount > stopCount` because that
     * is what the OS does: `Context.stopService()` takes [GraceService] down
     * unconditionally, however many `startForegroundService()` calls preceded
     * it. A counter-derived "running" cannot see the failure this fake exists
     * to expose (issue #2483) — a STALE owner's stop landing on top of a
     * LIVE hold reads as "still running" to a counter and as "gone" to the
     * notification tray.
     */
    var isRunning: Boolean = false
        private set

    override fun start(deadlineMs: Long) {
        startedDeadlines += deadlineMs
        isRunning = true
    }

    override fun stop() {
        stopCount += 1
        isRunning = false
    }
}
