package com.pocketshell.next.connect

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.espresso.IdlingResource

/**
 * An Espresso [IdlingResource] that reports BUSY for a chosen wall-clock window.
 *
 * The harness tests for [BoundedWait] need "the app is not idle right now" to be
 * a fact they control, not a hope about emulator timing: a wedge produced by
 * hammering the UI is a coin flip, while a resource that is busy for exactly
 * four seconds makes "the idle sync cannot complete inside a one-second budget"
 * true on every run and on every machine.
 *
 * Deliberately busy on a TIMER rather than until something releases it. The
 * re-entrancy this exists to reproduce (#2549) only exists while an
 * interrogation is still outstanding, so a resource a teardown could release
 * would hide the bug — the interrogation has to outlive the caller that
 * abandoned it, exactly as a real wedge does.
 *
 * The idle transition is announced from a `postDelayed` on the main looper,
 * which is also how a real resource behaves: it both wakes the parked
 * `MessageQueue.next()` Espresso is sitting in and tells Espresso to re-check.
 */
class BusyIdlingResource(private val resourceName: String) : IdlingResource {

    @Volatile
    private var idleAt: Long = 0L

    @Volatile
    private var callback: IdlingResource.ResourceCallback? = null

    override fun getName(): String = resourceName

    override fun isIdleNow(): Boolean = SystemClock.elapsedRealtime() >= idleAt

    override fun registerIdleTransitionCallback(callback: IdlingResource.ResourceCallback) {
        this.callback = callback
    }

    /** Reports busy for the next [millis], then announces the transition to idle. */
    fun beBusyFor(millis: Long) {
        idleAt = SystemClock.elapsedRealtime() + millis
        Handler(Looper.getMainLooper()).postDelayed(
            { callback?.onTransitionToIdle() },
            millis,
        )
    }

    /** Reports idle from now on, for a teardown that must not leave Espresso waiting. */
    fun release() {
        idleAt = 0L
        Handler(Looper.getMainLooper()).post { callback?.onTransitionToIdle() }
    }
}
