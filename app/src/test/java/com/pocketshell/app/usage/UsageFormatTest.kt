package com.pocketshell.app.usage

import com.pocketshell.core.usage.UsageProviderRecord
import com.pocketshell.core.usage.UsageStatus
import com.pocketshell.core.usage.UsageThresholdState
import com.pocketshell.core.usage.UsageWindow
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class UsageFormatTest {

    @Test
    fun formatResetRelative_usesCountdownForSameDayReset() {
        assertEquals(
            "in 2h 34m",
            formatResetRelative(
                now = Instant.parse("2026-05-21T11:49:00Z"),
                resetAt = Instant.parse("2026-05-21T14:23:00Z"),
                zoneId = ZoneId.of("UTC"),
            ),
        )
    }

    @Test
    fun formatResetRelative_normalizesRoundedMinutes() {
        assertEquals(
            "in 2h",
            formatResetRelative(
                now = Instant.parse("2026-05-21T00:00:00Z"),
                resetAt = Instant.parse("2026-05-21T01:59:59Z"),
                zoneId = ZoneId.of("UTC"),
            ),
        )
    }

    @Test
    fun formatPercentUsed_makesUsageExplicit() {
        assertEquals("65% used", formatPercentUsed(65.0))
        assertEquals("65.5% used", formatPercentUsed(65.5))
    }

    // --- Issue #501: relative "time until reset" across all buckets ---

    @Test
    fun formatResetRelative_underAMinuteReadsLessThanOneMinute() {
        assertEquals(
            "in <1m",
            formatResetRelative(
                now = Instant.parse("2026-06-04T12:00:00Z"),
                resetAt = Instant.parse("2026-06-04T12:00:30Z"),
                zoneId = ZoneId.of("UTC"),
            ),
        )
    }

    @Test
    fun formatResetRelative_minutesOnly() {
        assertEquals(
            "in 15m",
            formatResetRelative(
                now = Instant.parse("2026-06-04T12:00:00Z"),
                resetAt = Instant.parse("2026-06-04T12:15:00Z"),
                zoneId = ZoneId.of("UTC"),
            ),
        )
    }

    @Test
    fun formatResetRelative_hoursAndMinutes() {
        assertEquals(
            "in 2h 15m",
            formatResetRelative(
                now = Instant.parse("2026-06-04T11:00:00Z"),
                resetAt = Instant.parse("2026-06-04T13:15:00Z"),
                zoneId = ZoneId.of("UTC"),
            ),
        )
    }

    @Test
    fun formatResetRelative_oneDaySingular() {
        // Exactly 24h away rounds up to a single day bucket.
        assertEquals(
            "in 1 day",
            formatResetRelative(
                now = Instant.parse("2026-06-04T12:00:00Z"),
                resetAt = Instant.parse("2026-06-05T12:00:00Z"),
                zoneId = ZoneId.of("UTC"),
            ),
        )
    }

    @Test
    fun formatResetRelative_multipleDaysPlural() {
        assertEquals(
            "in 7 days",
            formatResetRelative(
                now = Instant.parse("2026-05-21T11:49:00Z"),
                resetAt = Instant.parse("2026-05-28T09:00:00Z"),
                zoneId = ZoneId.of("UTC"),
            ),
        )
    }

    @Test
    fun formatResetRelative_twentyEightHoursReadsOneDay() {
        // Issue #802: the maintainer's exact instants. Reset is ~28h out
        // (lands on tomorrow's date), so it must read "in 1 day", not the
        // "in 2 days" the old ceil produced.
        assertEquals(
            "in 1 day",
            formatResetRelative(
                now = Instant.parse("2026-06-17T15:28:00Z"),
                resetAt = Instant.parse("2026-06-18T19:28:00Z"),
                zoneId = ZoneId.of("UTC"),
            ),
        )
    }

    @Test
    fun formatResetRelative_fortySevenHoursReadsOneDay() {
        // 47h out but only one calendar day later (next day, late) → "in 1 day".
        assertEquals(
            "in 1 day",
            formatResetRelative(
                now = Instant.parse("2026-06-17T00:00:00Z"),
                resetAt = Instant.parse("2026-06-18T23:00:00Z"),
                zoneId = ZoneId.of("UTC"),
            ),
        )
    }

    @Test
    fun formatResetRelative_fortyNineHoursReadsTwoDays() {
        // 49h out crosses two calendar days → "in 2 days".
        assertEquals(
            "in 2 days",
            formatResetRelative(
                now = Instant.parse("2026-06-17T00:00:00Z"),
                resetAt = Instant.parse("2026-06-19T01:00:00Z"),
                zoneId = ZoneId.of("UTC"),
            ),
        )
    }

    @Test
    fun formatResetRelative_twentyFiveHoursReadsOneDay() {
        // 25h out, next calendar day → "in 1 day".
        assertEquals(
            "in 1 day",
            formatResetRelative(
                now = Instant.parse("2026-06-17T00:00:00Z"),
                resetAt = Instant.parse("2026-06-18T01:00:00Z"),
                zoneId = ZoneId.of("UTC"),
            ),
        )
    }

    @Test
    fun formatResetRelative_twentyThreeFiftyNineStaysOnHoursPath() {
        // 23h59m is still sub-24h, so it stays on the hours/minutes path.
        assertEquals(
            "in 23h 59m",
            formatResetRelative(
                now = Instant.parse("2026-06-17T00:00:00Z"),
                resetAt = Instant.parse("2026-06-17T23:59:00Z"),
                zoneId = ZoneId.of("UTC"),
            ),
        )
    }

    @Test
    fun formatResetRelative_pastResetReadsNow() {
        assertEquals(
            "now",
            formatResetRelative(
                now = Instant.parse("2026-06-04T12:00:00Z"),
                resetAt = Instant.parse("2026-06-04T11:59:00Z"),
                zoneId = ZoneId.of("UTC"),
            ),
        )
    }

    @Test
    fun formatResetRelative_nullReadsPlaceholder() {
        assertEquals(
            "—",
            formatResetRelative(
                now = Instant.parse("2026-06-04T12:00:00Z"),
                resetAt = null,
                zoneId = ZoneId.of("UTC"),
            ),
        )
    }

    @Test
    fun formatResetAbsolute_rendersLocalDateAndTime() {
        // 13:10 UTC is 15:10 in Berlin (CEST, +02:00) — proves zone-aware.
        // 2026-06-04 is a Thursday (issue #1565: short weekday prefix).
        assertEquals(
            "Thu Jun 4, 15:10",
            formatResetAbsolute(
                resetAt = Instant.parse("2026-06-04T13:10:00Z"),
                zoneId = ZoneId.of("Europe/Berlin"),
            ),
        )
    }

    @Test
    fun formatResetAbsolute_includesShortWeekday() {
        // Issue #1565: reset rows show the day of week at a glance.
        // 2026-07-15 18:00 UTC → 2026-07-16 03:00 in Tokyo (JST, +09:00),
        // which is a Thursday. Fixed instant + fixed zone = deterministic.
        assertEquals(
            "Thu Jul 16, 03:00",
            formatResetAbsolute(
                resetAt = Instant.parse("2026-07-15T18:00:00Z"),
                zoneId = ZoneId.of("Asia/Tokyo"),
            ),
        )
    }

    @Test
    fun formatAbsolute_nullReturnsNull() {
        assertEquals(
            null,
            formatResetAbsolute(resetAt = null, zoneId = ZoneId.of("UTC")),
        )
    }

    @Test
    fun soonestReset_picksEarliestNonNullWindow() {
        val record = UsageProviderRecord(
            provider = "claude",
            status = UsageStatus.Ok,
            rawStatus = "ok",
            windows = listOf(
                UsageWindow("5h", 10.0, 100.0, "percent", Instant.parse("2026-06-04T15:00:00Z")),
                UsageWindow("7d", 20.0, 100.0, "percent", Instant.parse("2026-06-04T13:10:00Z")),
            ),
        )
        assertEquals(Instant.parse("2026-06-04T13:10:00Z"), soonestReset(record))
    }

    @Test
    fun soonestReset_nullWhenNoWindowReportsReset() {
        val record = UsageProviderRecord(
            provider = "codex",
            status = UsageStatus.Ok,
            rawStatus = "ok",
            windows = listOf(UsageWindow("weekly", 10.0, 100.0, "percent", null)),
        )
        assertEquals(null, soonestReset(record))
    }

    @Test
    fun formatWindowFoot_nullResetStillShowsPlaceholder() {
        val window = UsageWindow("5h", 10.0, 100.0, "percent", null)
        assertEquals(
            "resets —",
            formatWindowFoot(
                window = window,
                now = Instant.parse("2026-06-04T12:00:00Z"),
                blockReason = null,
                zoneId = ZoneId.of("UTC"),
            ),
        )
    }

    @Test
    fun formatWindowFoot_sanitizesCodexWeeklyQuotaMessage() {
        val window = UsageWindow("7d", 100.0, 100.0, "percent", null)

        assertEquals(
            "resets — · Weekly quota exceeded",
            formatWindowFoot(
                window = window,
                now = Instant.parse("2026-06-04T12:00:00Z"),
                blockReason = "codex quota exhausted (weekly window at 80%)",
                zoneId = ZoneId.of("UTC"),
            ),
        )
    }

    @Test
    fun blockReasonForWindow_scopesWeeklyReasonToLongTermWindowOnly() {
        val record = UsageProviderRecord(
            provider = "codex",
            status = UsageStatus.Blocked,
            rawStatus = "quota_exhausted",
            blockReason = "codex quota exhausted (weekly window at 80%)",
            windows = listOf(
                UsageWindow("5h", 12.0, 100.0, "percent", null),
                UsageWindow("7d", 100.0, 100.0, "percent", Instant.parse("2026-06-15T10:00:00Z")),
            ),
        )

        assertEquals(null, blockReasonForWindow(record, record.windows[0]))
        assertEquals(
            "codex quota exhausted (weekly window at 80%)",
            blockReasonForWindow(record, record.windows[1]),
        )
    }

    @Test
    fun statusLabel_promotesNearLimitToWarning() {
        val record = UsageProviderRecord(
            provider = "claude",
            status = UsageStatus.Ok,
            rawStatus = "ok",
            windows = listOf(UsageWindow("5h", 90.0, 100.0, "percent", null)),
        )

        assertEquals("Warn", statusLabel(record))
    }

    @Test
    fun errorClaudeTelemetry_hasAuthSetupSummaryWithoutOkOrBlockedCopy() {
        val record = UsageProviderRecord(
            provider = "claude",
            status = UsageStatus.Error,
            rawStatus = "error",
            windows = emptyList(),
            lastError = "HTTP Error 401: Unauthorized",
        )

        assertEquals("Login required", statusLabel(record))
        assertEquals("Login required", usageProviderStateDescription(record))
        assertEquals(
            "Provider login needed on this host. " +
                "Sign in with the provider CLI on the host, then refresh usage.",
            usageTelemetryMessageForDisplay(record.lastError),
        )
        listOf(
            statusLabel(record),
            usageProviderStateDescription(record),
            usageTelemetryMessageForDisplay(record.lastError).orEmpty(),
        ).forEach { text ->
            assertEquals(false, text.equals("OK", ignoreCase = true))
            assertEquals(false, text.contains("claude " + "/login", ignoreCase = true))
            assertEquals(false, text.contains("authentication " + "failed", ignoreCase = true))
            assertEquals(false, text.contains("provider " + "blocked", ignoreCase = true))
            assertEquals(false, text.contains("HTTP Error 401", ignoreCase = true))
        }
    }

    @Test
    fun okCodexRecordWithAuthTelemetry_doesNotRenderAsOk() {
        val record = UsageProviderRecord(
            provider = "codex",
            status = UsageStatus.Ok,
            rawStatus = "ok",
            windows = emptyList(),
            lastError = "No auth token. Run codex login.",
        )

        val ui = usageProviderStatusUi(record)

        assertEquals("Login required", ui.label)
        assertEquals("Login required", ui.description)
        assertEquals(true, ui.needsAuthSetup)
        assertEquals(
            "Codex login needed on this host. " +
                "Run `codex login` in the host shell, then refresh usage.",
            usageTelemetryMessageForDisplay(record.lastError),
        )
    }

    @Test
    fun staleClaudeAuthTelemetryCopy_isSanitizedForDisplay() {
        val stale = "Claude Code authentication " + "failed on this host. Run `claude " +
            "/login` in the host shell."

        assertEquals(CLAUDE_USAGE_AUTH_SETUP_MESSAGE, usageTelemetryMessageForDisplay(stale))
    }

    @Test
    fun staleGeneric401TelemetryCopy_isMappedToAuthSetup() {
        assertEquals(
            "Provider login needed on this host. " +
                "Sign in with the provider CLI on the host, then refresh usage.",
            usageTelemetryMessageForDisplay("Usage data unavailable: HTTP Error 401: Unauthorized"),
        )
    }

    @Test
    fun exhaustedCodex_hasClearExceededLabels() {
        val record = UsageProviderRecord(
            provider = "codex",
            status = UsageStatus.Blocked,
            rawStatus = "quota_exhausted",
            blockReason = "Codex quota exhausted",
            windows = emptyList(),
        )

        assertEquals(UsageThresholdState.Exceeded, record.thresholdState())
        assertEquals("Exceeded", statusLabel(record))
        assertEquals("EXCEEDED", thresholdBadgeLabel(record.thresholdState()))
        assertEquals("Quota exceeded", thresholdRowDescription(record.thresholdState()))
        assertEquals(
            false,
            thresholdRowDescription(record.thresholdState()).contains("provider " + "blocked", ignoreCase = true),
        )
    }

    @Test
    fun dashboardRows_keepsBlockedCodexWithoutWindows() {
        val record = UsageProviderRecord(
            provider = "codex",
            status = UsageStatus.Blocked,
            rawStatus = "quota_exhausted",
            blockReason = "Codex quota exhausted",
            windows = emptyList(),
        )
        val state = UsageScreenState(
            hosts = listOf(
                UsageHostSnapshot(
                    hostId = 1L,
                    hostName = "agents",
                    records = listOf(record),
                    lastSyncedAt = Instant.parse("2026-06-07T12:00:00Z"),
                ),
            ),
        )

        val row = state.dashboardRows().single()

        assertEquals("Codex", row.provider)
        assertEquals(UsageStatus.Blocked, row.status)
        assertEquals(100.0, row.percent, 0.001)
        assertEquals("100% used", row.percentLabel)
        assertEquals(UsageThresholdState.Exceeded, row.thresholdState)
    }

    @Test
    fun dashboardRows_exposesExplicitPercentUsedLabel() {
        val record = UsageProviderRecord(
            provider = "openai",
            status = UsageStatus.Ok,
            rawStatus = "ok",
            windows = listOf(
                UsageWindow(
                    name = "7d",
                    used = 65.0,
                    limit = 100.0,
                    unit = "percent",
                    resetAt = Instant.parse("2026-06-11T00:27:17Z"),
                ),
            ),
        )
        val state = UsageScreenState(
            hosts = listOf(
                UsageHostSnapshot(
                    hostId = 1L,
                    hostName = "agents",
                    records = listOf(record),
                    lastSyncedAt = Instant.parse("2026-06-07T12:00:00Z"),
                ),
            ),
        )

        val row = state.dashboardRows().single()

        assertEquals("65% used", row.percentLabel)
        assertEquals(Instant.parse("2026-06-11T00:27:17Z"), row.soonestReset)
    }

    // -- issue #689: stale-while-revalidate provenance ----------------------

    private fun cachedHost(captured: Instant?, stale: Instant? = null) = UsageHostSnapshot(
        hostId = 1,
        hostName = "h",
        records = emptyList(),
        lastSyncedAt = captured,
        capturedAt = captured,
        staleSince = stale,
    )

    @Test
    fun usageProvenance_showsLastCapturedAndRefreshingWhileSyncing() {
        val state = UsageScreenState(
            hosts = listOf(cachedHost(Instant.parse("2026-06-11T09:00:00Z"))),
            isRefreshing = true,
            showingCached = true,
        )
        assertEquals(
            "Last captured 09:00 · refreshing…",
            usageProvenanceLabel(state, ZoneId.of("UTC")),
        )
    }

    @Test
    fun usageProvenance_showsHonestCachedNoteOnRefreshFailure() {
        val state = UsageScreenState(
            hosts = listOf(
                cachedHost(captured = null, stale = Instant.parse("2026-06-11T08:30:00Z")),
            ),
            isRefreshing = false,
            showingCached = false,
        )
        assertEquals(
            "Couldn't refresh — showing cached from 08:30",
            usageProvenanceLabel(state, ZoneId.of("UTC")),
        )
        assertEquals(true, state.refreshFailedShowingCached)
    }

    @Test
    fun usageProvenance_plainLiveStatusWhenFresh() {
        val fresh = UsageScreenState(
            hosts = listOf(
                UsageHostSnapshot(
                    hostId = 1,
                    hostName = "h",
                    records = emptyList(),
                    lastSyncedAt = Instant.parse("2026-06-11T10:00:00Z"),
                ),
            ),
            isRefreshing = false,
        )
        assertEquals("Last sync: host data", usageProvenanceLabel(fresh, ZoneId.of("UTC")))
    }

    @Test
    fun usageProvenance_syncingWhenNoCacheYet() {
        val state = UsageScreenState(isRefreshing = true)
        assertEquals("Syncing...", usageProvenanceLabel(state, ZoneId.of("UTC")))
    }
}
