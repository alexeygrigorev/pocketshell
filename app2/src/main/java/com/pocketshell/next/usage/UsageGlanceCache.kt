package com.pocketshell.next.usage

import android.content.Context
import com.pocketshell.uikit.model.PillKind
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The last usage reading this device saw, with the instant it was read at. */
data class CachedUsageGlance(
    val percent: Int,
    val provider: String,
    val window: String?,
    val kind: PillKind,
    val fetchedAt: Instant,
)

/**
 * The last usage reading, kept so the LANDING screen has a number (issue #2632).
 *
 * The maintainer's first ask was "when I load I want to see the [usage] cost".
 * That could not be answered from live data alone: [UsageFetcher] only asks
 * hosts [com.pocketshell.next.connect.ConnectionsRegistry] already holds a live
 * connection to (D21 — usage never dials), and the Hosts list is by definition
 * a PRE-connection screen. So on a cold launch there is nothing to fetch and
 * the pill could never appear before the first dial finished.
 *
 * This closes that gap without weakening D21: whatever
 * [UsageGlanceViewModel] last computed is written here, and the landing screen
 * paints it immediately at launch — attributed, and honestly marked stale with
 * the clock time it was actually read at, exactly the way
 * [UsageGlancePillState] already renders an old reading on the session screen.
 * No fetch, no dial, no background work.
 *
 * A single global reading, not one per host: the pill's cross-provider meaning
 * is "how close am I to the nearest limit anywhere", so a second host's entry
 * would only ever be discarded by the same `maxByOrNull` that produced this one.
 */
@Singleton
class UsageGlanceCache @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private val _last = MutableStateFlow(read())

    /** Hot, so a fetch on the session screen updates a Hosts list behind it. */
    val last: StateFlow<CachedUsageGlance?> = _last.asStateFlow()

    /** Persists [state] as the reading the landing screen shows next launch. */
    fun put(state: UsageGlancePillState, fetchedAt: Instant) {
        val cached = CachedUsageGlance(
            percent = state.percent,
            provider = state.provider,
            window = state.window,
            kind = state.kind,
            fetchedAt = fetchedAt,
        )
        runCatching {
            preferences.edit()
                .putInt(KEY_PERCENT, cached.percent)
                .putString(KEY_PROVIDER, cached.provider)
                .putString(KEY_WINDOW, cached.window.orEmpty())
                .putString(KEY_KIND, cached.kind.name)
                .putLong(KEY_FETCHED_AT, cached.fetchedAt.toEpochMilli())
                .apply()
        }
        _last.value = cached
    }

    private fun read(): CachedUsageGlance? = runCatching {
        val provider = preferences.getString(KEY_PROVIDER, null)?.takeIf { it.isNotBlank() }
            ?: return@runCatching null
        val fetchedAtMillis = preferences.getLong(KEY_FETCHED_AT, 0L)
        if (fetchedAtMillis <= 0L) return@runCatching null
        CachedUsageGlance(
            percent = preferences.getInt(KEY_PERCENT, 0),
            provider = provider,
            window = preferences.getString(KEY_WINDOW, null)?.takeIf { it.isNotBlank() },
            kind = PillKind.entries
                .firstOrNull { it.name == preferences.getString(KEY_KIND, null) }
                ?: PillKind.Ok,
            fetchedAt = Instant.ofEpochMilli(fetchedAtMillis),
        )
    }.getOrNull()

    private companion object {
        const val PREFERENCES = "usage_glance_cache"
        const val KEY_PERCENT = "percent"
        const val KEY_PROVIDER = "provider"
        const val KEY_WINDOW = "window"
        const val KEY_KIND = "kind"
        const val KEY_FETCHED_AT = "fetched_at"
    }
}

/**
 * Renders a cached reading as a pill state, re-deriving staleness against
 * [now] rather than trusting whatever the reading looked like when it was
 * written. A launch hours later must show the muted "read at HH:mm" form, not
 * the fresh one it was cached as.
 */
fun CachedUsageGlance.toPillState(
    now: Instant = Instant.now(),
    staleAfter: Duration = USAGE_GLANCE_STALE_AFTER,
    zoneId: ZoneId = ZoneId.systemDefault(),
): UsageGlancePillState = UsageGlancePillState(
    percent = percent,
    provider = provider,
    window = window,
    kind = kind,
    stale = Duration.between(fetchedAt, now) > staleAfter,
    fetchedClock = formatClock(fetchedAt, zoneId),
)
