package com.pocketshell.app.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class UsageResetBannerTest {

    private val now = Instant.parse("2026-06-11T18:00:00Z")
    private val utc = ZoneId.of("UTC")

    private fun event(
        provider: String = "codex",
        resetKey: String = "codex|short_term|reset",
        detectedAt: Instant? = Instant.parse("2026-06-11T17:00:00Z"),
        newResetAt: Instant? = Instant.parse("2026-06-11T17:00:00Z"),
        timing: String? = "early",
        minutesEarly: Int? = 15,
    ) = UsageResetEvent(
        provider = provider,
        window = "short_term",
        detectedAt = detectedAt,
        statedResetAt = Instant.parse("2026-06-11T17:15:00Z"),
        newResetAt = newResetAt,
        timing = timing,
        minutesEarly = minutesEarly,
        resetKey = resetKey,
    )

    @Test
    fun noEvents_noBanner() {
        assertNull(usageResetBannerState(emptyList(), now = now, zoneId = utc))
    }

    @Test
    fun recentEarlyReset_buildsBannerWithProviderTimeAndEarlyClause() {
        val state = usageResetBannerState(listOf(event()), now = now, zoneId = utc)!!
        assertTrue(state.title.startsWith("Codex limits reset at "))
        assertTrue(state.detail.contains("~15m earlier than stated"))
        assertEquals("codex|short_term|reset", state.resetKey)
    }

    @Test
    fun claudeProvider_displaysAsClaude() {
        val state = usageResetBannerState(
            listOf(event(provider = "anthropic")),
            now = now,
            zoneId = utc,
        )!!
        assertTrue(state.title.startsWith("Claude limits reset at "))
    }

    @Test
    fun onOrAfterStated_hasNoEarlyClause() {
        val state = usageResetBannerState(
            listOf(event(timing = "on_or_after_stated", minutesEarly = null)),
            now = now,
            zoneId = utc,
        )!!
        assertEquals(false, state.detail.contains("earlier than stated"))
    }

    @Test
    fun staleReset_outsideRecencyWindow_isNotShown() {
        val old = event(detectedAt = now.minus(Duration.ofHours(20)))
        assertNull(usageResetBannerState(listOf(old), now = now, zoneId = utc))
    }

    @Test
    fun mostRecentResetWins() {
        val older = event(resetKey = "older", detectedAt = now.minus(Duration.ofHours(6)))
        val newer = event(resetKey = "newer", detectedAt = now.minus(Duration.ofMinutes(30)))
        val state = usageResetBannerState(listOf(older, newer), now = now, zoneId = utc)!!
        assertEquals("newer", state.resetKey)
    }

    @Test
    fun futureDetectedAt_isIgnored() {
        val future = event(detectedAt = now.plus(Duration.ofMinutes(5)))
        assertNull(usageResetBannerState(listOf(future), now = now, zoneId = utc))
    }

    @Test
    fun missingResetTime_fallsBackToJustReset() {
        val state = usageResetBannerState(
            listOf(event(newResetAt = null, detectedAt = now.minus(Duration.ofMinutes(10)))),
            now = now,
            zoneId = utc,
        )!!
        // With no new_reset_at, the detected time powers the "reset at <time>".
        assertTrue(state.title.startsWith("Codex limits reset at "))
    }
}
