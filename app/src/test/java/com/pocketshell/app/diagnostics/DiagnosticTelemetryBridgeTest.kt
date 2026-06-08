package com.pocketshell.app.diagnostics

import com.pocketshell.app.startup.StartupTiming
import com.pocketshell.app.tmux.TmuxConnectTrigger
import com.pocketshell.app.tmux.TmuxSessionLatencyTelemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticTelemetryBridgeTest {
    @Test
    fun `startup timing marks are mirrored to diagnostic events`() {
        val diagnostics = installRecordingDiagnosticSink()
        try {
            StartupTiming.mark("test-startup-mark", "route" to "settings")

            val event = diagnostics.eventsNamed("startup_timing").single {
                it.fields["mark"] == "test-startup-mark"
            }
            assertEquals("app", event.category)
            assertEquals("settings", event.fields["route"])
            assertTrue((event.fields["processElapsedMs"] as Long) >= 0L)
            assertTrue((event.fields["elapsedRealtimeMs"] as Long) >= 0L)
        } finally {
            diagnostics.close()
        }
    }

    @Test
    fun `tmux latency telemetry is mirrored to diagnostic events`() {
        val diagnostics = installRecordingDiagnosticSink()
        TmuxSessionLatencyTelemetry.resetForTest()
        try {
            TmuxSessionLatencyTelemetry.record(
                name = "runtime_cache_activate",
                durationMs = 42L,
                sessionName = "work",
                paneId = "%1",
                trigger = TmuxConnectTrigger.FastSwitch,
                detail = "paneCount=2",
            )

            val event = diagnostics.eventsNamed("tmux_latency").single()
            assertEquals("connection", event.category)
            assertEquals("runtime_cache_activate", event.fields["operation"])
            assertEquals(42L, event.fields["durationMs"])
            assertEquals("work", event.fields["session"])
            assertEquals("%1", event.fields["paneId"])
            assertEquals(TmuxConnectTrigger.FastSwitch.logValue, event.fields["trigger"])
            assertEquals("paneCount=2", event.fields["detail"])
        } finally {
            TmuxSessionLatencyTelemetry.resetForTest()
            diagnostics.close()
        }
    }
}
