package com.pocketshell.next

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner
import org.junit.runner.RunWith
import java.io.File

/**
 * Regression test (task P-10): app2's Gradle Robolectric setup
 * (`isIncludeAndroidResources = true`) makes Robolectric use the merged
 * manifest's declared Application (`.App`) for every JVM unit test in this
 * module, regardless of a per-test `@Config(manifest = Config.NONE)` — that
 * config does not suppress Application selection here (confirmed
 * empirically while writing `DiagnosticRecorderTest`: without a guard,
 * `App.onCreate()` constructed a real `DiagnosticRecorder` and wrote an
 * `app`/`created` event to `context.filesDir` on the FIRST
 * `ApplicationProvider.getApplicationContext()` call of every app2 unit
 * test, racing any test that builds its own `DiagnosticRecorder` over the
 * same on-disk path and producing duplicate sequence numbers).
 *
 * Reproduce-first (D33/G10): this asserts the fix — [App.onCreate] must
 * leave `filesDir/diagnostics` untouched under Robolectric (Build.FINGERPRINT
 * == "robolectric") — directly, rather than only observing it as a side
 * effect of `DiagnosticRecorderTest` staying green. Deleting the
 * `Build.FINGERPRINT` guard in `App.onCreate` turns this RED (a
 * `diagnostics/pocketshell-diagnostics.jsonl` file appears with an
 * `app`/`created` line) — reintroduced locally to confirm during this task.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class AppRobolectricSideEffectGuardTest {

    @Test
    fun `App onCreate does not write diagnostics under Robolectric`() {
        val context: Context = ApplicationProvider.getApplicationContext()

        val diagnosticsFile = File(context.filesDir, "diagnostics/pocketshell-diagnostics.jsonl")

        assertFalse(
            "App.onCreate() must not construct a live DiagnosticRecorder / write " +
                "diagnostics under Robolectric — see this file's class doc for the " +
                "cross-test pollution this caused before the Build.FINGERPRINT guard",
            diagnosticsFile.exists(),
        )
    }

    @Test
    fun `App onCreate does not install the crash reporter's exception handler under Robolectric`() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        ApplicationProvider.getApplicationContext<Context>()

        assertFalse(
            "App.onCreate() must not install CrashReporter's global uncaught-exception " +
                "handler under Robolectric — it is process-wide JVM state that would leak " +
                "across every other test sharing this JVM",
            Thread.getDefaultUncaughtExceptionHandler()?.javaClass?.simpleName ==
                "ReportingUncaughtExceptionHandler",
        )
        // Restore whatever was installed before this test touched the Application,
        // so a sibling test in the same JVM worker never observes this test's state.
        Thread.setDefaultUncaughtExceptionHandler(previous)
    }
}
