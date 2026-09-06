package com.pocketshell.next.connect

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Keeps the main looper's queue non-empty for a chosen wall-clock window.
 *
 * This is the wedge shape the journeys actually hit — a screen that keeps
 * posting work (a rotation, an IME animation, a terminal repainting a burst of
 * output) so Espresso's `Interrogator` never sees an empty queue and
 * `waitForIdle()` never completes. #2479 is the bound for it, and #2549 is what
 * the first attempt at that bound did wrong.
 *
 * It also picks the exact Espresso failure #2549 reported. Every Espresso idle
 * pass registers callbacks on any idle-notifier that is currently BUSY before
 * interrogating, so a wedge made of a busy `IdlingResource` makes the second,
 * colliding caller trip `IllegalStateException: Callback has already been
 * registered` first. With nothing busy except the queue itself, both callers
 * skip registration and go straight to `Interrogator.loopAndInterrogate`, and
 * the second one trips the guard the CI runs printed:
 * `IllegalStateException: Already interrogating!`.
 *
 * The re-post delay is deliberately inside `Interrogator.LOOKAHEAD_MILLIS` (15)
 * so the queue always looks like it has work due now, while still costing the
 * main thread ~200 wakeups a second rather than a hard spin — this runs on a
 * shared, contended emulator.
 */
class BusyMainLooper {

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var busyUntil: Long = 0L

    private val churn = object : Runnable {
        override fun run() {
            if (SystemClock.elapsedRealtime() < busyUntil) {
                handler.postDelayed(this, REPOST_DELAY_MS)
            }
        }
    }

    /** Keeps the queue occupied for the next [millis]. */
    fun beBusyFor(millis: Long) {
        val alreadyRunning = SystemClock.elapsedRealtime() < busyUntil
        busyUntil = SystemClock.elapsedRealtime() + millis
        if (!alreadyRunning) handler.post(churn)
    }

    /** Lets the queue drain again, for a teardown that must not wait it out. */
    fun release() {
        busyUntil = 0L
        handler.removeCallbacks(churn)
    }

    private companion object {
        const val REPOST_DELAY_MS = 5L
    }
}
