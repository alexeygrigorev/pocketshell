package com.pocketshell.app.costs

import com.pocketshell.core.storage.dao.AiApiCallLogDao
import com.pocketshell.core.storage.entity.AiApiCallEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Unit tests for [CostsViewModel] (issue #181). The view model has two
 * jobs: (a) aggregate the log rows into windowed totals; (b) order the
 * per-feature breakdown. Both are exercised directly via
 * [CostsViewModel.computeState], which the production code path also
 * uses.
 */
class CostsViewModelTest {

    private val fakeDao = FakeDao()

    private fun viewModel(now: ZonedDateTime): CostsViewModel {
        val vm = CostsViewModel(fakeDao)
        vm.zone = now.zone
        vm.clock = { now }
        return vm
    }

    @Test
    fun aggregates_windowed_totals_correctly() {
        // Wednesday 2026-05-27 12:00 in UTC. That puts "this week" Mon
        // 2026-05-25 00:00 onwards, "this month" 2026-05-01 onwards.
        val now = ZonedDateTime.of(2026, 5, 27, 12, 0, 0, 0, ZoneId.of("UTC"))
        val vm = viewModel(now)

        val entries = listOf(
            // 200 millicents today, this week, this month, lifetime
            entry(millicents = 200L, at = ZonedDateTime.of(2026, 5, 27, 9, 0, 0, 0, ZoneId.of("UTC"))),
            // 50 millicents earlier today
            entry(millicents = 50L, at = ZonedDateTime.of(2026, 5, 27, 0, 1, 0, 0, ZoneId.of("UTC"))),
            // 80 millicents Monday of this week
            entry(millicents = 80L, at = ZonedDateTime.of(2026, 5, 25, 12, 0, 0, 0, ZoneId.of("UTC"))),
            // 120 millicents on the 2nd — counts toward month, not week.
            entry(millicents = 120L, at = ZonedDateTime.of(2026, 5, 2, 12, 0, 0, 0, ZoneId.of("UTC"))),
            // 999 millicents in April — counts only lifetime.
            entry(millicents = 999L, at = ZonedDateTime.of(2026, 4, 25, 12, 0, 0, 0, ZoneId.of("UTC"))),
        )

        val state = vm.computeState(entries)

        assertEquals(200 + 50 + 80 + 120 + 999L, state.lifetimeUsdMillicents)
        assertEquals(200 + 50 + 80 + 120L, state.monthUsdMillicents)
        assertEquals(200 + 50 + 80L, state.weekUsdMillicents)
        assertEquals(200 + 50L, state.todayUsdMillicents)
    }

    @Test
    fun groups_by_feature_descending() {
        val now = ZonedDateTime.of(2026, 5, 27, 12, 0, 0, 0, ZoneId.of("UTC"))
        val vm = viewModel(now)

        val entries = listOf(
            entry(millicents = 100L, provider = "openai", feature = "whisper", at = now.minusHours(1)),
            entry(millicents = 50L, provider = "openai", feature = "whisper", at = now.minusHours(2)),
            entry(millicents = 999L, provider = "openai", feature = "gpt4o", at = now.minusHours(3)),
        )

        val state = vm.computeState(entries)

        assertEquals(2, state.featureBreakdown.size)
        // gpt4o larger total → first.
        assertEquals("gpt4o", state.featureBreakdown[0].feature)
        assertEquals(999L, state.featureBreakdown[0].totalUsdMillicents)
        assertEquals("whisper", state.featureBreakdown[1].feature)
        assertEquals(150L, state.featureBreakdown[1].totalUsdMillicents)
    }

    @Test
    fun empty_log_renders_empty_state() {
        val now = ZonedDateTime.of(2026, 5, 27, 12, 0, 0, 0, ZoneId.of("UTC"))
        val vm = viewModel(now)

        val state = vm.computeState(emptyList())

        assertEquals(0L, state.lifetimeUsdMillicents)
        assertEquals(0L, state.todayUsdMillicents)
        assertTrue(state.featureBreakdown.isEmpty())
        assertTrue(state.recentCalls.isEmpty())
    }

    @Test
    fun groups_requests_by_local_day_newest_first_with_subtotals() {
        // "Now" = 2026-06-04 10:00 UTC -> today is 2026-06-04.
        val now = ZonedDateTime.of(2026, 6, 4, 10, 0, 0, 0, ZoneId.of("UTC"))
        val vm = viewModel(now)

        val entries = listOf(
            // Today: two requests -> subtotal 200 + 50 = 250.
            entry(millicents = 200L, at = ZonedDateTime.of(2026, 6, 4, 9, 0, 0, 0, ZoneId.of("UTC"))),
            entry(millicents = 50L, at = ZonedDateTime.of(2026, 6, 4, 8, 0, 0, 0, ZoneId.of("UTC"))),
            // Yesterday: one request -> subtotal 80.
            entry(millicents = 80L, at = ZonedDateTime.of(2026, 6, 3, 12, 0, 0, 0, ZoneId.of("UTC"))),
            // Two days ago: one request -> subtotal 120.
            entry(millicents = 120L, at = ZonedDateTime.of(2026, 6, 2, 12, 0, 0, 0, ZoneId.of("UTC"))),
        )

        val state = vm.computeState(entries)
        val groups = state.dailyGroups

        assertEquals(3, groups.size)

        // Newest day first.
        assertEquals(java.time.LocalDate.of(2026, 6, 4), groups[0].date)
        assertEquals(0L, groups[0].daysBeforeToday)
        assertEquals(2, groups[0].requests.size)
        assertEquals(250L, groups[0].subtotalUsdMillicents)
        // Within a day, newest request first.
        assertEquals(200L, groups[0].requests[0].costUsdMillicents)
        assertEquals(50L, groups[0].requests[1].costUsdMillicents)

        assertEquals(java.time.LocalDate.of(2026, 6, 3), groups[1].date)
        assertEquals(1L, groups[1].daysBeforeToday)
        assertEquals(80L, groups[1].subtotalUsdMillicents)

        assertEquals(java.time.LocalDate.of(2026, 6, 2), groups[2].date)
        assertEquals(2L, groups[2].daysBeforeToday)
        assertEquals(120L, groups[2].subtotalUsdMillicents)

        // Day subtotals sum to lifetime.
        assertEquals(state.lifetimeUsdMillicents, groups.sumOf { it.subtotalUsdMillicents })
    }

    @Test
    fun day_grouping_uses_local_zone_for_bucketing() {
        // 2026-06-04 01:30 in a +03:00 zone is still 2026-06-03 22:30 UTC.
        // The entry must bucket by the *local* date (the 4th), not UTC.
        val zone = ZoneId.of("+03:00")
        val now = ZonedDateTime.of(2026, 6, 4, 12, 0, 0, 0, zone)
        val vm = viewModel(now)

        val entries = listOf(
            entry(millicents = 70L, at = ZonedDateTime.of(2026, 6, 4, 1, 30, 0, 0, zone)),
        )

        val state = vm.computeState(entries)
        assertEquals(1, state.dailyGroups.size)
        assertEquals(java.time.LocalDate.of(2026, 6, 4), state.dailyGroups[0].date)
        assertEquals(0L, state.dailyGroups[0].daysBeforeToday)
    }

    @Test
    fun empty_log_has_no_day_groups() {
        val now = ZonedDateTime.of(2026, 6, 4, 12, 0, 0, 0, ZoneId.of("UTC"))
        val vm = viewModel(now)
        assertTrue(vm.computeState(emptyList()).dailyGroups.isEmpty())
    }

    @Test
    fun recent_calls_are_capped() {
        val now = ZonedDateTime.of(2026, 5, 27, 12, 0, 0, 0, ZoneId.of("UTC"))
        val vm = viewModel(now)
        // 60 entries — should be capped to MAX_RECENT_CALLS (50).
        val entries = (1..60).map { i ->
            entry(millicents = 10L, at = now.minusMinutes(i.toLong()))
        }
        val state = vm.computeState(entries)
        assertEquals(CostsViewModel.MAX_RECENT_CALLS, state.recentCalls.size)
        assertEquals(60, state.totalCallCount)
    }

    private var nextId = 1L
    private fun entry(
        millicents: Long,
        provider: String = "openai",
        feature: String = "whisper",
        at: ZonedDateTime,
    ) = AiApiCallEntry(
        id = nextId++,
        timestampMillis = at.toInstant().toEpochMilli(),
        provider = provider,
        feature = feature,
        inputUnits = 5,
        outputUnits = 5,
        unitCostUsdMillicents = 10,
        computedCostUsdMillicents = millicents,
        metadataJson = null,
    )

    /** Tiny DAO stub — only the bits the view model uses. */
    private class FakeDao : AiApiCallLogDao {
        val flow = MutableStateFlow<List<AiApiCallEntry>>(emptyList())
        override fun getAll(): Flow<List<AiApiCallEntry>> = flow
        override suspend fun insert(entry: AiApiCallEntry): Long = 0L
        override suspend fun deleteAll() {
            flow.value = emptyList()
        }
    }
}
