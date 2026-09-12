package com.pocketshell.next

import android.app.Application
import android.os.Build
import com.pocketshell.next.crash.CrashReporter
import com.pocketshell.next.diagnostics.DiagnosticEvents
import com.pocketshell.next.diagnostics.DiagnosticRecorder
import com.pocketshell.next.ports.ForwardingResume
import com.pocketshell.next.release.UpdateCheckScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * app2's Application.
 *
 * Beyond the Hilt entry point (plan §M-1 non-goal: "no DI beyond
 * `@HiltAndroidApp`"), `onCreate` installs the diagnostics event sink, the
 * crash reporter's uncaught-exception handler, the foreground-only
 * GitHub-Releases update check (issue #2531), and the auto-forward resume
 * (issue #2654) on [androidx.lifecycle.ProcessLifecycleOwner]. Both the
 * update check and the resume are foreground-only — D21: they fire on
 * `ON_START`. No WorkManager, no AlarmManager.
 *
 * ## The Robolectric guard
 *
 * app2's Gradle setup (`isIncludeAndroidResources = true`, needed for
 * Robolectric to render real composables) makes Robolectric pick up the
 * merged manifest's declared Application (`.App`) for EVERY JVM unit test in
 * this module, regardless of a per-test `@Config(manifest = Config.NONE)` —
 * that config no longer controls Application selection once AGP-integrated
 * resource merging is on. Without this guard, `onCreate` would construct a
 * REAL [DiagnosticRecorder] and write to `context.filesDir` on every single
 * app2 unit test (most of which never touch diagnostics), and would race any
 * test that constructs its OWN [DiagnosticRecorder] over the same on-disk
 * path — confirmed empirically while writing `DiagnosticRecorderTest`: a
 * stray Application-installed recorder's async write collided with the
 * test's own recorder's sequence numbering, producing duplicate sequence
 * numbers. [Build.FINGERPRINT] is the standard Robolectric-provided signal
 * for "skip eager production side effects under a headless JVM test" — the
 * same category of guard `StrictModeInstaller.installIfDebuggable` used in
 * the old app for a different eager-init hazard.
 */
@HiltAndroidApp
class App : Application() {

    @Inject
    lateinit var diagnosticRecorder: DiagnosticRecorder

    @Inject
    lateinit var updateCheckScheduler: UpdateCheckScheduler

    @Inject
    lateinit var forwardingResume: ForwardingResume

    override fun onCreate() {
        super.onCreate()
        if (isRunningUnderRobolectric()) return
        DiagnosticEvents.install(diagnosticRecorder)
        DiagnosticEvents.record("app", "created")
        CrashReporter.install(this)
        // Foreground-only (D21 / #698 / #2654): ON_START of ProcessLifecycleOwner.
        // Skipped under Robolectric so JVM unit tests never poll GitHub or
        // attach a process-lifecycle observer that would leak across tests.
        updateCheckScheduler.observeProcessLifecycle()
        forwardingResume.observeProcessLifecycle()
    }

    private fun isRunningUnderRobolectric(): Boolean = Build.FINGERPRINT == "robolectric"
}
