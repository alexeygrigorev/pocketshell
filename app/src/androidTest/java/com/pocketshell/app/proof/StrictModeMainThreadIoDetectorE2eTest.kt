package com.pocketshell.app.proof

import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.crash.CrashReportMetadata
import com.pocketshell.app.crash.CrashReporter
import com.pocketshell.app.diagnostics.CrashReportGate
import com.pocketshell.app.diagnostics.DiagnosticEventSink
import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.app.diagnostics.MainThreadResponsivenessAnalyzer
import com.pocketshell.app.diagnostics.StrictModeInstaller
import com.pocketshell.app.proof.signals.MainThreadResponsivenessProbe
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Issue #933 (#928 D9 / P1+P2+P3) — the ON-DEVICE end-to-end red→green for the
 * three detectors that form the freeze/ANR/crash safety net.
 *
 * The maintainer keeps hitting freezes/crashes that CI did NOT catch because the
 * per-PR journeys carried no freeze/ANR/crash detector. This test proves all
 * three detectors actually FIRE on a real device against the exact symptom
 * classes, each with a RED (symptom present) and a GREEN (symptom absent) case:
 *
 *  - **P1 StrictMode** — a real main-thread DISK READ (the #926/#928-D1 class
 *    `detectNetwork()` misses) trips the installed process-wide policy and
 *    records a `strictmode.violation`; the same read OFF the main thread records
 *    none. (`detectNetwork()`-only policies cannot do this.)
 *  - **P2 responsiveness probe** — a synthetically-BLOCKED main thread
 *    (`Thread.sleep(2000)` on Main, the freeze signature) is detected as a gap
 *    beyond budget; a responsive main thread passes.
 *  - **P3 zero-crash gate** — a persisted crash report FAILS the gate; a clean
 *    store passes.
 *
 * No Docker fixture, no SSH/tmux, no toxiproxy, no port — a pure on-device
 * detector exercise, deterministic on the CI swiftshader AVD — so it slots into
 * the per-push journey gate without any workflow service change. It does NOT
 * self-skip on CI (no assumeTrue / assumeFalse(isRunningOnCi()) on the
 * load-bearing assertions — process.md F3 / D33).
 */
@RunWith(AndroidJUnit4::class)
class StrictModeMainThreadIoDetectorE2eTest {

    private val app = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var diagnostics: RecordingDiagnosticSink

    @Before
    fun installRecordingSink() {
        diagnostics = RecordingDiagnosticSink().also { DiagnosticEvents.install(it) }
    }

    @After
    fun restoreSink() {
        DiagnosticEvents.install(DiagnosticEventSink.Noop)
        clearCrashReports()
    }

    // ---- P1: StrictMode main-thread disk-IO detector (red→green) ----

    @Test
    fun mainThreadDiskReadTripsStrictModeViolation_offMainThreadDoesNot() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val probeFile = File(app.cacheDir, "ps-d9-strictmode-probe.txt").apply {
            writeText("pocketshell-d9-strictmode-detector-probe\n")
        }

        // Install the production policy on the MAIN thread (this is what
        // App.onCreate does on a debuggable build; the androidTest APK is
        // debuggable, so installIfDebuggable would also install — but we install
        // explicitly on Main here so the policy is unambiguously active for the
        // probe and restored after).
        val original = arrayOfNulls<android.os.StrictMode.ThreadPolicy>(1)
        instrumentation.runOnMainSync {
            original[0] = android.os.StrictMode.getThreadPolicy()
            android.os.StrictMode.setThreadPolicy(
                StrictModeInstaller.buildThreadPolicy { it.run() },
            )
        }

        try {
            // GREEN control: read the file OFF the main thread — no violation.
            Thread { probeFile.readText() }.apply { start(); join() }
            Thread.sleep(150)
            assertEquals(
                "a disk read OFF the main thread must NOT trip the detector",
                0,
                diagnostics.eventsNamed(StrictModeInstaller.DIAGNOSTIC_EVENT).size,
            )

            // RED: the #926/#928-D1 class — a real disk read ON the main thread.
            instrumentation.runOnMainSync { probeFile.readText() }
            // Let the (synchronous-executor) violation listener land.
            Thread.sleep(200)

            val violations = diagnostics.eventsNamed(StrictModeInstaller.DIAGNOSTIC_EVENT)
            assertTrue(
                "a main-thread disk read MUST trip the StrictMode detector " +
                    "(the #926/#928-D1 freeze class). Recorded: $violations",
                violations.isNotEmpty(),
            )
        } finally {
            instrumentation.runOnMainSync {
                original[0]?.let { android.os.StrictMode.setThreadPolicy(it) }
            }
            probeFile.delete()
        }
    }

    // ---- P2: main-thread responsiveness probe (red→green) ----

    @Test
    fun blockedMainThreadIsDetected_responsiveMainThreadPasses() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        // GREEN: a responsive main thread over a ~700ms window.
        val greenProbe = MainThreadResponsivenessProbe(intervalMs = 50, budgetMs = 700)
        greenProbe.start()
        SystemClock.sleep(700)
        val green = greenProbe.stop(minExpectedSamples = 5)
        assertTrue(
            "a responsive main thread must pass the probe. ${green.message}",
            green.responsive,
        )

        // RED: block the main thread for 2000ms (the freeze signature) mid-window.
        val redProbe = MainThreadResponsivenessProbe(intervalMs = 50, budgetMs = 700)
        redProbe.start()
        SystemClock.sleep(200)
        instrumentation.runOnMainSync {
            // A blocking sleep on Main — exactly what an unbounded runBlocking
            // disk read / parked mutex wait does. The heartbeat queued behind it
            // cannot run, so the gap balloons past budget.
            SystemClock.sleep(2000)
        }
        SystemClock.sleep(300)
        val red = redProbe.stop(minExpectedSamples = 5)
        assertFalse(
            "a 2000ms-blocked main thread MUST be detected as a stall. ${red.message}",
            red.responsive,
        )
        assertTrue(
            "the detected gap must exceed the frame budget",
            red.maxGapMs > MainThreadResponsivenessAnalyzer.DEFAULT_FRAME_BUDGET_MS,
        )
    }

    // ---- P3: zero-crash gate (red→green) over the REAL on-device store ----

    @Test
    fun persistedCrashFailsZeroCrashGate_cleanStorePasses() {
        clearCrashReports()

        // GREEN: a clean store passes.
        val clean = CrashReportGate.evaluate(app)
        assertTrue("a clean crash store must pass the gate. ${clean.failureMessage}", clean.clean)

        // RED: persist a crash exactly as recordNonFatal / the uncaught handler
        // does, through the production store, then re-evaluate.
        CrashReporter.store(app).save(
            throwable = IllegalStateException("D9 detector self-test crash"),
            threadName = "main",
            metadata = CrashReportMetadata(
                appVersion = "test",
                androidRelease = "test",
                sdkInt = android.os.Build.VERSION.SDK_INT,
                device = "test",
            ),
        )
        val dirty = CrashReportGate.evaluate(app)
        assertFalse(
            "a persisted crash report MUST fail the zero-crash gate. ${dirty.failureMessage}",
            dirty.clean,
        )
        assertEquals(1, dirty.reportCount)
        assertTrue(dirty.failureMessage.contains("D9 detector self-test crash"))

        clearCrashReports()
        val cleanAgain = CrashReportGate.evaluate(app)
        assertTrue("clearing reports must restore a clean gate", cleanAgain.clean)
    }

    private fun clearCrashReports() {
        val store = CrashReporter.store(app)
        store.list().forEach { store.delete(it) }
    }
}
