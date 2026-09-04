package com.pocketshell.next

import android.app.Application
import android.os.Build
import com.pocketshell.next.crash.CrashReporter
import com.pocketshell.next.diagnostics.DiagnosticEvents
import com.pocketshell.next.diagnostics.DiagnosticRecorder
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * app2's Application.
 *
 * Beyond the Hilt entry point (plan §M-1 non-goal: "no DI beyond
 * `@HiltAndroidApp`"), `onCreate` does exactly two things (task P-10):
 * installs the generic diagnostics event sink and installs the crash
 * reporter's uncaught-exception handler, so an uncaught exception anywhere
 * in the app actually gets recorded to `filesDir/crash-reports` from process
 * start. No other eager initialisation, no process-wide schedulers, no
 * background work — D21 stands in the rewrite.
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

    override fun onCreate() {
        super.onCreate()
        if (isRunningUnderRobolectric()) return
        DiagnosticEvents.install(diagnosticRecorder)
        DiagnosticEvents.record("app", "created")
        CrashReporter.install(this)
    }

    private fun isRunningUnderRobolectric(): Boolean = Build.FINGERPRINT == "robolectric"
}
