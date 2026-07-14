package com.pocketshell.app.sessions.service

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Issue #1595: drives the session foreground-service START off the ACTIVITY lifecycle so it
 * fires while the app is still FOREGROUND-ELIGIBLE.
 *
 * Why an activity observer and not the existing [androidx.lifecycle.ProcessLifecycleOwner]
 * observer: `ProcessLifecycleOwner` dispatches its `ON_STOP` from a single 700ms-delayed
 * runnable, so it fires ~700ms INTO the background — past the point where Android 12+ still
 * allows `startForegroundService()`. The device-log Fable audit (#1562) traced the
 * ~4.4s-after-background `-CC` transport death to exactly this: the FGS was started only from the
 * backgrounded `ON_STOP`, the OS rejected the start with
 * `ForegroundServiceStartNotAllowedException`, the network hold never came up, and the OS
 * destroyed the uid's sockets — so the foreground return was a full redial instead of a silent
 * reseed.
 *
 * Round 2 (reviewer finding — the anti-thrash gate): the background signal is keyed off the
 * activity STARTED-count going to zero (`onActivityStopped` → all activities stopped), NOT off
 * `onActivityPaused`. `onActivityPaused` fires on EVERY transient focus loss — a permission
 * dialog, a SAF/file picker sheet, the share sheet, the notification shade, a recents peek —
 * where the activity stays STARTED (visible). Starting the FGS on those flashed a Stop-able
 * "Session connected" notification into the tray and reintroduced the accidental-Stop footgun
 * [SessionServiceController] deliberately designs against. An overlay that keeps the activity
 * STARTED never drives `onActivityStopped` to a zero count, so it never starts the FGS — no
 * matter how long it lingers. A genuine background (home / recents / app-switch / a full-screen
 * picker that stops the activity) DOES drive the count to zero. The remaining transient — a
 * quick stop→start where the activity really stopped but the user returned immediately (a
 * recents peek) — is absorbed by the controller's short debounce
 * ([SessionServiceController.onAppPausing]) which the next `onActivityStarted` cancels before it
 * ever starts the FGS.
 *
 * The activity's `onStop` fires promptly (tens of ms after `onPause` for a home-press), FAR
 * earlier than the ProcessLifecycleOwner debounced `ON_STOP`, so the debounced FGS start still
 * lands while foreground-eligible and establishes the network hold BEFORE the OS can suspend
 * the socket — the transport survives the grace window.
 *
 * Config-change safety: a rotation / dark-mode flip stops+recreates the activity but is NOT a
 * real background, so the `onActivityStopped` with [Activity.isChangingConfigurations] == true
 * is skipped (the started-count is still balanced by the recreated `onActivityStarted`). A
 * genuine app-switch has `isChangingConfigurations == false`.
 *
 * The grace count-down deadline + teardown stay owned by the ProcessLifecycleOwner `ON_STOP` /
 * `ON_START` path ([SessionServiceController.onAppBackgrounded] / [onAppForegrounded]); this
 * observer only moves the FGS START earlier to the foreground-eligible boundary and cancels it
 * again on resume for the transient that never reached `ON_STOP`.
 *
 * No-leak: the observer holds only the app-singleton controller and an int counter.
 */
class SessionForegroundServiceActivityObserver(
    private val controller: SessionServiceController,
) : Application.ActivityLifecycleCallbacks {

    /** Number of currently STARTED (visible) activities. Zero ⇒ the app is backgrounded. */
    private var startedActivityCount = 0

    override fun onActivityStarted(activity: Activity) {
        val wasBackground = startedActivityCount == 0
        startedActivityCount++
        if (wasBackground) {
            // 0→1: the app (re)entered the foreground. Cancels a pending pause debounce (a
            // transient stop→start that never confirmed a real background) and stops any FGS the
            // debounce already started — no notification thrash.
            controller.onAppResumed()
        }
    }

    override fun onActivityStopped(activity: Activity) {
        // Decrement on EVERY stop so the count stays balanced across activity→activity
        // transitions and configuration-change recreate (old.onStop then new.onStart).
        if (startedActivityCount > 0) startedActivityCount--
        if (startedActivityCount == 0 && !activity.isChangingConfigurations) {
            // 1→0 and not a config change: every activity is stopped = a genuine background.
            // A mere overlay that keeps the activity STARTED (permission dialog / share sheet /
            // shade / a partial dialog) never reaches here, so it never flashes the FGS
            // notification. The controller debounces this hint so a quick stop→start (recents
            // peek) is suppressed too.
            controller.onAppPausing()
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
