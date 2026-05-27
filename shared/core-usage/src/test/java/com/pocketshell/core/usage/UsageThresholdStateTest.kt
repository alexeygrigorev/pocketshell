package com.pocketshell.core.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [UsageProviderRecord.thresholdState] (issue #214).
 *
 * The threshold logic is intentionally testable in pure Kotlin so the
 * boundary cases — 79.9 / 80 / 94.9 / 95 / 99.9 / 100 — can be locked
 * down without a Robolectric run. The UI layer reads the state via the
 * same function so visual regressions for the host card / sessions
 * chip / Settings list / banner are downstream of these assertions.
 */
class UsageThresholdStateTest {

    private fun providerWithPercent(
        percent: Double,
        status: UsageStatus = UsageStatus.Ok,
    ): UsageProviderRecord = UsageProviderRecord(
        provider = "test",
        status = status,
        windows = listOf(
            UsageWindow(
                name = "5h",
                used = percent,
                limit = 100.0,
                unit = "percent",
                resetAt = null,
            ),
        ),
        rawStatus = status.name.lowercase(),
    )

    @Test
    fun below_warn_threshold_is_ok() {
        assertEquals(UsageThresholdState.Ok, providerWithPercent(0.0).thresholdState())
        assertEquals(UsageThresholdState.Ok, providerWithPercent(50.0).thresholdState())
        assertEquals(UsageThresholdState.Ok, providerWithPercent(79.9).thresholdState())
    }

    @Test
    fun at_80_percent_is_approaching() {
        assertEquals(UsageThresholdState.Approaching, providerWithPercent(80.0).thresholdState())
        assertEquals(UsageThresholdState.Approaching, providerWithPercent(85.0).thresholdState())
        assertEquals(UsageThresholdState.Approaching, providerWithPercent(94.9).thresholdState())
    }

    @Test
    fun at_95_percent_is_critical() {
        assertEquals(UsageThresholdState.Critical, providerWithPercent(95.0).thresholdState())
        assertEquals(UsageThresholdState.Critical, providerWithPercent(99.9).thresholdState())
    }

    @Test
    fun at_100_percent_is_exceeded() {
        assertEquals(UsageThresholdState.Exceeded, providerWithPercent(100.0).thresholdState())
        assertEquals(UsageThresholdState.Exceeded, providerWithPercent(120.0).thresholdState())
    }

    @Test
    fun blocked_status_is_exceeded_regardless_of_percent() {
        val record = providerWithPercent(10.0, status = UsageStatus.Blocked)
        assertEquals(UsageThresholdState.Exceeded, record.thresholdState())
    }

    @Test
    fun custom_warn_threshold_lowers_the_approaching_band() {
        val record = providerWithPercent(55.0)
        assertEquals(UsageThresholdState.Ok, record.thresholdState(warnPercent = 80.0))
        assertEquals(UsageThresholdState.Approaching, record.thresholdState(warnPercent = 50.0))
    }

    @Test
    fun warn_threshold_at_critical_collapses_the_approaching_band() {
        // Slider pulled all the way to 95 — there is no "approaching"
        // band any more; 95% jumps straight to critical.
        val record = providerWithPercent(93.0)
        assertEquals(UsageThresholdState.Ok, record.thresholdState(warnPercent = 95.0))
        val record96 = providerWithPercent(96.0)
        assertEquals(UsageThresholdState.Critical, record96.thresholdState(warnPercent = 95.0))
    }

    @Test
    fun record_with_no_windows_is_ok() {
        val record = UsageProviderRecord(
            provider = "gemini",
            status = UsageStatus.Unsupported,
            windows = emptyList(),
            rawStatus = "unsupported",
            lastError = "gemini does not expose a usage endpoint",
        )
        assertEquals(UsageThresholdState.Ok, record.thresholdState())
    }

    @Test
    fun ok_state_does_not_warrant_warning() {
        assertFalse(UsageThresholdState.Ok.warrantsWarning)
    }

    @Test
    fun every_non_ok_state_warrants_warning() {
        assertTrue(UsageThresholdState.Approaching.warrantsWarning)
        assertTrue(UsageThresholdState.Critical.warrantsWarning)
        assertTrue(UsageThresholdState.Exceeded.warrantsWarning)
    }

    @Test
    fun threshold_states_ordered_by_severity() {
        // The enum order is leveraged by the dashboard so the most
        // severe state wins when aggregating across providers.
        val ordered = UsageThresholdState.entries.toList()
        assertEquals(
            listOf(
                UsageThresholdState.Ok,
                UsageThresholdState.Approaching,
                UsageThresholdState.Critical,
                UsageThresholdState.Exceeded,
            ),
            ordered,
        )
    }

    @Test
    fun most_constrained_window_drives_state() {
        val record = UsageProviderRecord(
            provider = "claude",
            status = UsageStatus.Ok,
            windows = listOf(
                UsageWindow(name = "5h", used = 30.0, limit = 100.0, unit = "percent", resetAt = null),
                UsageWindow(name = "7d", used = 96.0, limit = 100.0, unit = "percent", resetAt = null),
            ),
            rawStatus = "ok",
        )
        assertEquals(UsageThresholdState.Critical, record.thresholdState())
    }
}
