package com.pocketshell.app

import android.app.Application
import com.pocketshell.app.crash.CrashReporter
import com.pocketshell.app.usage.UsageScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Hilt-managed [Application]. Beyond installing the crash reporter, this
 * class wires the singleton [UsageScheduler] to the
 * [androidx.lifecycle.ProcessLifecycleOwner] so the polling loop pauses
 * the moment the user backgrounds the app and resumes on the next
 * `ON_START` (issue #161, decision D21 — no background work).
 *
 * The scheduler is started here unconditionally. While the singleton
 * has no eligible hosts to poll (every saved host has
 * `quseInstalled != true`) `fetchOnce` is essentially a Room read and a
 * no-op snapshot update — cheap. The wire-up therefore costs nothing on
 * a fresh install and means the first time a user bootstraps quse on a
 * host their usage data starts flowing without an additional
 * screen-mount trigger.
 */
@HiltAndroidApp
class App : Application() {

    @Inject
    lateinit var usageScheduler: UsageScheduler

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        // No-background-work hook-up (issue #161 / D21). Attach the
        // ProcessLifecycleOwner observer before starting the loop so
        // the loop's `processStarted.first { it }` gate sees the
        // already-correct value rather than waiting for the first
        // ON_START event.
        usageScheduler.observeProcessLifecycle()
        usageScheduler.start()
    }
}
