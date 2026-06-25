package com.pocketshell.app.diagnostics

import com.pocketshell.app.crash.CrashReportMetadata
import com.pocketshell.app.crash.CrashReportStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Issue #933 (#928 D9 / P3) — JVM red→green for the post-journey ZERO-CRASH
 * gate ([CrashReportGate]).
 *
 * D9: `CrashReportStore` persists every crash + every `recordNonFatal`, but NO
 * journey asserted the store was empty, so a crash that landed during a journey
 * (the D2 process-death signature) sailed through green. This proves the gate:
 *
 *  - a clean store ⇒ `clean = true` (PASS), and
 *  - a store with ANY persisted report ⇒ `clean = false` (FAIL) with the
 *    report body inlined in the failure message for the journey artifact.
 *
 * Drives the REAL [CrashReportStore] (the same store the uncaught handler +
 * `recordNonFatal` write to) over a [TemporaryFolder], so this is the
 * production persistence path, not a stand-in.
 */
class CrashReportGateTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val metadata = CrashReportMetadata(
        appVersion = "0.4.16",
        androidRelease = "14",
        sdkInt = 34,
        device = "Google Pixel 7",
    )

    @Test
    fun cleanStorePassesTheGate() {
        val store = storeAt("2026-06-25T08:00:00Z")

        val result = CrashReportGate.evaluate(store.list(), readBody = { store.read(it) })

        assertTrue("an empty crash store must pass the zero-crash gate", result.clean)
        assertEquals(0, result.reportCount)
        assertTrue(result.reportBodies.isEmpty())
    }

    @Test
    fun storeWithAPersistedCrashFailsTheGate() {
        val store = storeAt("2026-06-25T08:00:00Z")
        // Persist a crash exactly the way the uncaught handler / recordNonFatal
        // does — through the real store.save path.
        store.save(
            throwable = IllegalStateException("attach freeze: runBlocking Room read on Main"),
            threadName = "main",
            metadata = metadata,
        )

        val result = CrashReportGate.evaluate(store.list(), readBody = { store.read(it) })

        assertFalse("a persisted crash report must FAIL the zero-crash gate", result.clean)
        assertEquals(1, result.reportCount)
        assertTrue(
            "the failure message must inline the crash so the journey can attach it",
            result.failureMessage.contains("attach freeze"),
        )
        assertEquals(1, result.reportBodies.size)
    }

    @Test
    fun multipleCrashesAreAllReportedInTheFailure() {
        val store = storeAt("2026-06-25T08:00:00Z")
        store.save(IllegalStateException("first crash"), "main", metadata)
        store.save(RuntimeException("second crash (non-fatal recorded)"), "io", metadata)

        val result = CrashReportGate.evaluate(store.list(), readBody = { store.read(it) })

        assertFalse(result.clean)
        assertEquals(2, result.reportCount)
        assertTrue(result.failureMessage.contains("first crash"))
        assertTrue(result.failureMessage.contains("second crash"))
    }

    @Test
    fun bodyReadFailureDoesNotMaskTheCrash() {
        val store = storeAt("2026-06-25T08:00:00Z")
        store.save(IllegalStateException("crash with unreadable body"), "main", metadata)

        // Even if reading the body throws, the gate still FAILS (the count is
        // the load-bearing signal) and notes the read failure.
        val result = CrashReportGate.evaluate(store.list()) { error("disk gone") }

        assertFalse(result.clean)
        assertEquals(1, result.reportCount)
        assertTrue(result.failureMessage.contains("body read failed"))
    }

    private fun storeAt(instant: String): CrashReportStore =
        CrashReportStore(
            directory = temporaryFolder.newFolder("crash-reports-${instant.hashCode()}"),
            clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC),
        )
}
