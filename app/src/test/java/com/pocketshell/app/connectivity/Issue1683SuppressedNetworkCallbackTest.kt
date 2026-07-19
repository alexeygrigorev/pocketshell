package com.pocketshell.app.connectivity

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.diagnostics.DiagnosticEventSink
import com.pocketshell.app.diagnostics.DiagnosticEvents
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #1683 (A3) / #1631 — a SUPPRESSED default-network callback (the detector
 * returns null: an idempotent already-lost repeat, or a same-identity change)
 * must be recorded as a rate-limited INPUT, so "the callback didn't happen" is
 * distinguishable from "it happened but wasn't recorded" (the #1631
 * unverifiable-by-construction gap).
 *
 * On base the suppression path (`?: return null`) records NOTHING, so
 * [firstSuppressionIsRecorded] fails; with the fix the first and every Nth
 * suppression carry the running total.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class Issue1683SuppressedNetworkCallbackTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val events = mutableListOf<Pair<String, Map<String, Any?>>>()

    @Before
    fun installSink() {
        events.clear()
        DiagnosticEvents.install(
            object : DiagnosticEventSink {
                override fun record(category: String, event: String, fields: Map<String, Any?>) {
                    synchronized(events) { events += "$category/$event" to fields }
                }
            },
        )
    }

    @After
    fun removeSink() {
        DiagnosticEvents.install(DiagnosticEventSink.Noop)
    }

    @Test
    fun firstSuppressionIsRecorded() {
        val observer = TerminalNetworkObserver(context)

        // First NoValidatedNetwork -> NetworkLost (a real change, NOT suppressed).
        observer.emitSyntheticSnapshotForTest(TerminalNetworkSnapshot.NoValidatedNetwork, "loss")
        // Second identical NoValidatedNetwork -> already lost -> detector returns
        // null -> SUPPRESSED.
        observer.emitSyntheticSnapshotForTest(TerminalNetworkSnapshot.NoValidatedNetwork, "loss-repeat")

        val suppressed = suppressedEvents()
        assertEquals("the first suppressed callback must be recorded", 1, suppressed.size)
        assertEquals(
            "the record carries the running suppression total",
            1L,
            (suppressed.single()["suppressedTotal"] as Number).toLong(),
        )
        assertTrue("the record carries a reason", suppressed.single()["reason"] != null)
    }

    @Test
    fun suppressionIsRateLimited() {
        val observer = TerminalNetworkObserver(context)

        // One real Lost, then 20 identical repeats that all suppress.
        observer.emitSyntheticSnapshotForTest(TerminalNetworkSnapshot.NoValidatedNetwork, "loss")
        repeat(20) {
            observer.emitSyntheticSnapshotForTest(TerminalNetworkSnapshot.NoValidatedNetwork, "repeat")
        }

        val totals = suppressedEvents().map { (it["suppressedTotal"] as Number).toLong() }
        // Rate limit is the first + every 10th (SUPPRESSED_LOG_INTERVAL=10): so 20
        // suppressions record at totals 1, 10, 20 — NOT one line per callback.
        assertEquals(
            "suppressed callbacks must be rate-limited to the 1st + every 10th",
            listOf(1L, 10L, 20L),
            totals,
        )
    }

    private fun suppressedEvents(): List<Map<String, Any?>> =
        synchronized(events) {
            events.filter { it.first == "connection/network_change_suppressed" }.map { it.second }
        }
}
