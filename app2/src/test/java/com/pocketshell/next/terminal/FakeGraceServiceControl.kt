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

    /** True between the most recent unmatched [start] and the next [stop]. */
    val isRunning: Boolean get() = startCount > stopCount

    override fun start(deadlineMs: Long) {
        startedDeadlines += deadlineMs
    }

    override fun stop() {
        stopCount += 1
    }
}
