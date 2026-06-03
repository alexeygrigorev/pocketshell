package com.pocketshell.app.costs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.core.storage.dao.AiApiCallLogDao
import com.pocketshell.core.storage.entity.AiApiCallEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject

/**
 * Backs [CostsScreen] (issue #181). Collects the
 * [AiApiCallLogDao.getAll] flow, aggregates lifetime / today / this-week
 * / this-month totals per feature, and exposes a [CostsUiState] the
 * screen renders.
 *
 * Aggregation runs in Kotlin (not SQL) because the table is small (a few
 * rows per session) and the costs surface fits more naturally as a single
 * `StateFlow` than a fan-out of multiple windowed `Flow`s. The math is
 * also simpler to unit test that way.
 *
 * Time windows snap to the user's local calendar so "today" rolls over at
 * local midnight, "this week" starts Monday at 00:00, and "this month"
 * starts on the 1st. The [clock] indirection lets tests pin a fixed
 * "now" without touching the system clock.
 */
@HiltViewModel
open class CostsViewModel @Inject constructor(
    private val dao: AiApiCallLogDao,
) : ViewModel() {

    /** Test seam — defaults to `Instant.now()` in production. */
    internal var clock: () -> ZonedDateTime = { ZonedDateTime.now() }

    /** Tests can pin the calendar zone (the system default in production). */
    internal var zone: ZoneId = ZoneId.systemDefault()

    private val _state = MutableStateFlow(CostsUiState())
    val state: StateFlow<CostsUiState> = _state.asStateFlow()

    init {
        collectLog()
    }

    private fun collectLog() {
        viewModelScope.launch {
            dao.getAll().collect { entries ->
                _state.update { computeState(entries) }
            }
        }
    }

    /**
     * Wipe the log (issue #181 "Clear log" affordance). Caller is
     * expected to surface a confirmation dialog before calling this. The
     * subsequent DAO emission resets every aggregate to zero.
     */
    fun clearLog() {
        viewModelScope.launch {
            dao.deleteAll()
        }
    }

    /**
     * Compute the [CostsUiState] from a snapshot of the log. Exposed
     * `internal` so unit tests can drive it without scaffolding a
     * coroutine collector.
     */
    internal fun computeState(entries: List<AiApiCallEntry>): CostsUiState {
        val now = clock()
        val zoned = now.withZoneSameInstant(zone)
        val startOfToday = zoned.toLocalDate().atStartOfDay(zone)
        val startOfWeek = zoned.toLocalDate()
            .with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
            .atStartOfDay(zone)
        val startOfMonth = zoned.toLocalDate()
            .withDayOfMonth(1)
            .atStartOfDay(zone)

        val nowMillis = now.toInstant().toEpochMilli()
        val todayMillis = startOfToday.toInstant().toEpochMilli()
        val weekMillis = startOfWeek.toInstant().toEpochMilli()
        val monthMillis = startOfMonth.toInstant().toEpochMilli()

        var lifetime = 0L
        var today = 0L
        var week = 0L
        var month = 0L
        val byFeature = mutableMapOf<FeatureKey, Long>()

        entries.forEach { entry ->
            lifetime += entry.computedCostUsdMillicents
            if (entry.timestampMillis >= todayMillis) today += entry.computedCostUsdMillicents
            if (entry.timestampMillis >= weekMillis) week += entry.computedCostUsdMillicents
            if (entry.timestampMillis >= monthMillis) month += entry.computedCostUsdMillicents
            val key = FeatureKey(entry.provider, entry.feature)
            byFeature[key] = (byFeature[key] ?: 0L) + entry.computedCostUsdMillicents
        }

        val breakdown = byFeature.entries
            .map { (key, total) ->
                FeatureBreakdown(
                    provider = key.provider,
                    feature = key.feature,
                    totalUsdMillicents = total,
                )
            }
            .sortedByDescending { it.totalUsdMillicents }

        return CostsUiState(
            lifetimeUsdMillicents = lifetime,
            todayUsdMillicents = today,
            weekUsdMillicents = week,
            monthUsdMillicents = month,
            featureBreakdown = breakdown,
            dailyGroups = groupByDay(entries, zoned.toLocalDate()),
            recentCalls = entries.take(MAX_RECENT_CALLS),
            totalCallCount = entries.size,
            nowMillis = nowMillis,
        )
    }

    /**
     * Bucket the log into per-local-day groups for the day-grouped cost
     * view (issue #467). Newest day first; within a day each request is
     * listed newest-first with a per-day subtotal.
     *
     * The day is the entry's local calendar date in [zone]. [today] is the
     * "now" local date so the renderer can label buckets Today / Yesterday /
     * explicit date. The whole list is grouped (not just the recent slice)
     * because the maintainer deferred retention — every recorded request is
     * browsable per day.
     */
    internal fun groupByDay(
        entries: List<AiApiCallEntry>,
        today: LocalDate,
    ): List<DailyCostGroup> {
        if (entries.isEmpty()) return emptyList()
        // Group by the entry's local date.
        val byDate = entries.groupBy { entry ->
            java.time.Instant.ofEpochMilli(entry.timestampMillis)
                .atZone(zone)
                .toLocalDate()
        }
        return byDate.entries
            // Newest day first.
            .sortedByDescending { it.key }
            .map { (date, dayEntries) ->
                val requests = dayEntries
                    .sortedByDescending { it.timestampMillis }
                    .map { entry ->
                        DailyRequest(
                            id = entry.id,
                            timestampMillis = entry.timestampMillis,
                            provider = entry.provider,
                            feature = entry.feature,
                            inputUnits = entry.inputUnits,
                            costUsdMillicents = entry.computedCostUsdMillicents,
                        )
                    }
                DailyCostGroup(
                    date = date,
                    daysBeforeToday = java.time.temporal.ChronoUnit.DAYS
                        .between(date, today),
                    requests = requests,
                    subtotalUsdMillicents = requests.sumOf { it.costUsdMillicents },
                )
            }
    }

    private data class FeatureKey(val provider: String, val feature: String)

    companion object {
        /**
         * Cap the "Recent calls" list at 50 rows. Beyond that the list
         * becomes a scroll-trap; the user is better served by the
         * lifetime aggregate and the CSV export.
         */
        const val MAX_RECENT_CALLS: Int = 50
    }
}

/**
 * UI state surfaced to [CostsScreen]. All cost fields are USD millicents
 * (1/1000 of a US cent) for the same integer-precision reasons that the
 * underlying entity uses. The screen converts to dollars+cents at render
 * time via [CostFormat.formatUsd].
 */
data class CostsUiState(
    val lifetimeUsdMillicents: Long = 0L,
    val todayUsdMillicents: Long = 0L,
    val weekUsdMillicents: Long = 0L,
    val monthUsdMillicents: Long = 0L,
    val featureBreakdown: List<FeatureBreakdown> = emptyList(),
    /**
     * Per-local-day groups for the day-grouped cost view (issue #467),
     * newest day first. Drives the "By day" section; the flat recent-calls
     * list was replaced by this grouping.
     */
    val dailyGroups: List<DailyCostGroup> = emptyList(),
    /**
     * Most-recent slice retained only as the CSV export source. The screen
     * renders [dailyGroups], not this list.
     */
    val recentCalls: List<AiApiCallEntry> = emptyList(),
    val totalCallCount: Int = 0,
    val nowMillis: Long = 0L,
)

data class FeatureBreakdown(
    val provider: String,
    val feature: String,
    val totalUsdMillicents: Long,
)

/**
 * One local-calendar day's worth of requests in the day-grouped cost view
 * (issue #467). [daysBeforeToday] is 0 for today, 1 for yesterday, and the
 * positive day delta for older days, so the renderer can choose the
 * Today / Yesterday / explicit-date header without re-deriving "now".
 */
data class DailyCostGroup(
    val date: LocalDate,
    val daysBeforeToday: Long,
    val requests: List<DailyRequest>,
    val subtotalUsdMillicents: Long,
)

/** A single request row inside a [DailyCostGroup]. */
data class DailyRequest(
    val id: Long,
    val timestampMillis: Long,
    val provider: String,
    val feature: String,
    val inputUnits: Long,
    val costUsdMillicents: Long,
)
