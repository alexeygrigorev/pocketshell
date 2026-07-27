package com.pocketshell.app.usage

import com.pocketshell.core.usage.UsageProviderRecord
import com.pocketshell.core.usage.UsageResetCredit
import com.pocketshell.core.usage.UsageResetCredits
import com.pocketshell.core.usage.UsageStatus
import com.pocketshell.core.usage.UsageWindow
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class UsageResetCreditIsolationTest {

    @Test
    fun creditExpiryDoesNotBecomeSoonestQuotaResetOrDashboardReset() {
        val quotaReset = Instant.parse("2026-08-08T12:00:00Z")
        val creditExpiry = Instant.parse("2026-07-28T12:00:00Z")
        val record = UsageProviderRecord(
            provider = "codex",
            status = UsageStatus.Ok,
            rawStatus = "ok",
            windows = listOf(
                UsageWindow(
                    name = "weekly",
                    used = 25.0,
                    limit = 100.0,
                    unit = "percent",
                    resetAt = quotaReset,
                ),
            ),
            resetCredits = UsageResetCredits(
                availableCount = 1,
                credits = listOf(UsageResetCredit("Included credit", creditExpiry)),
                unavailable = false,
            ),
        )
        val state = UsageScreenState(
            hosts = listOf(
                UsageHostSnapshot(
                    hostId = 1L,
                    hostName = "host",
                    records = listOf(record),
                    lastSyncedAt = null,
                ),
            ),
        )

        assertEquals(quotaReset, soonestReset(record))
        assertEquals(quotaReset, state.dashboardRows().single().soonestReset)
    }
}
