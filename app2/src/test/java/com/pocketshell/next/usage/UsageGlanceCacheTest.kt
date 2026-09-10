package com.pocketshell.next.usage

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.uikit.model.PillKind
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #2632: "usage/cost is visible from the landing screen".
 *
 * The Hosts list cannot fetch usage — nothing is connected there yet, and D21
 * forbids dialling to read it — so what it paints is the last reading. This
 * pins the two properties that makes that honest rather than misleading: the
 * reading survives a process restart, and its staleness is re-derived against
 * the CURRENT time, not frozen as it was when written.
 */
@RunWith(AndroidJUnit4::class)
class UsageGlanceCacheTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `an install that has never read usage has nothing to show`() {
        assertNull(UsageGlanceCache(context).last.value)
    }

    @Test
    fun `the last reading survives a new process`() {
        val fetchedAt = Instant.parse("2026-09-10T11:40:00Z")
        UsageGlanceCache(context).put(pill(percent = 63, provider = "Claude"), fetchedAt)

        // A second instance is what the next cold launch constructs.
        val reloaded = UsageGlanceCache(context).last.value

        assertEquals(63, reloaded?.percent)
        assertEquals("Claude", reloaded?.provider)
        assertEquals("7d", reloaded?.window)
        assertEquals(PillKind.Warn, reloaded?.kind)
        assertEquals(fetchedAt, reloaded?.fetchedAt)
    }

    @Test
    fun `a fresh reading renders as live`() {
        val now = Instant.parse("2026-09-10T12:00:00Z")
        val cached = CachedUsageGlance(38, "Claude", null, PillKind.Ok, now.minusSeconds(60))

        val state = cached.toPillState(now = now, zoneId = ZoneId.of("UTC"))

        assertFalse(state.stale)
        assertEquals("Claude 38%", state.label)
    }

    /**
     * The one that matters for a cold launch: yesterday's number must say
     * WHEN it was read rather than presenting itself as the current usage.
     */
    @Test
    fun `an old reading renders stale with the clock it was actually read at`() {
        val now = Instant.parse("2026-09-10T12:00:00Z")
        val cached = CachedUsageGlance(
            percent = 63,
            provider = "Claude",
            window = null,
            kind = PillKind.Ok,
            fetchedAt = now.minus(Duration.ofHours(22)),
        )

        val state = cached.toPillState(now = now, zoneId = ZoneId.of("UTC"))

        assertTrue(state.stale)
        assertEquals("14:00", state.fetchedClock)
        assertEquals("Usage Claude 63%, read at 14:00", state.contentDescription)
    }

    private fun pill(percent: Int, provider: String) = UsageGlancePillState(
        percent = percent,
        provider = provider,
        window = "7d",
        kind = PillKind.Warn,
        stale = false,
        fetchedClock = "11:40",
    )
}
