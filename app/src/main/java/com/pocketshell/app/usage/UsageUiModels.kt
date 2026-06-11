package com.pocketshell.app.usage

import com.pocketshell.core.usage.UsageProviderRecord
import com.pocketshell.core.usage.UsageThresholdState
import java.time.Instant

public data class UsageHostSnapshot(
    val hostId: Long,
    val hostName: String,
    val records: List<UsageProviderRecord>,
    val lastSyncedAt: Instant?,
    val command: String = UsageRemoteSource.defaultUsageCommand,
    /**
     * Issue #689: when these records came from the server-side cache (the
     * scheduled `pocketshell usage --capture`), the capture timestamp so the
     * UI can render "last captured at <time>". Null once a live refresh has
     * swapped in fresh data (provenance is then [lastSyncedAt]).
     */
    val capturedAt: Instant? = null,
    /**
     * Issue #689: set when a live refresh FAILED but a cached reading is
     * still being shown, carrying the cache's capture time for the honest
     * "couldn't refresh — showing cached from <time>" note.
     */
    val staleSince: Instant? = null,
)

public data class UsageMissingToolHost(
    val hostId: Long,
    val hostName: String,
    val toolName: String = "pocketshell",
)

public data class UsageFailedHost(
    val hostId: Long,
    val hostName: String,
    val reason: String,
)

public data class UsageScreenState(
    val hosts: List<UsageHostSnapshot> = emptyList(),
    val missingToolHosts: List<UsageMissingToolHost> = emptyList(),
    val failedHosts: List<UsageFailedHost> = emptyList(),
    val isRefreshing: Boolean = false,
    /**
     * Issue #689: true while the cached reading is shown and the live
     * foreground refresh is still running, so the UI can show a
     * "refreshing…" affordance over the populated cached value.
     */
    val showingCached: Boolean = false,
) {
    /**
     * Issue #689: the most recent capture timestamp across all hosts whose
     * shown records still come from the server-side cache. Drives the
     * screen-level "last captured at <time>" / "showing cached from <time>"
     * provenance label. Null once every host has live data.
     */
    public val cachedAt: Instant?
        get() = hosts.mapNotNull { it.capturedAt ?: it.staleSince }.maxOrNull()

    /**
     * Issue #689: true when at least one shown host's records are cached AND
     * its live refresh failed — the screen shows the honest "couldn't
     * refresh — showing cached from <time>" note instead of a hard error.
     */
    public val refreshFailedShowingCached: Boolean
        get() = hosts.any { it.staleSince != null }

    public val providerCount: Int
        get() = hosts.sumOf { it.records.size }

    public val hostCount: Int
        get() = hosts.size

    public val allRecords: List<UsageProviderRecord>
        get() = hosts.flatMap { it.records }
}

public fun UsageScreenState.dashboardRows(
    warnPercent: Double = UsageProviderRecord.DEFAULT_WARN_PERCENT,
): List<UsageDashboardRow> =
    allRecords
        .sortedWith(compareBy<UsageProviderRecord> { it.provider })
        .mapNotNull { record ->
            val window = record.mostConstrainedWindow
            val thresholdState = record.thresholdState(warnPercent = warnPercent)
            val percent = window?.percent
                ?: if (thresholdState == UsageThresholdState.Exceeded) 100.0 else return@mapNotNull null
            UsageDashboardRow(
                provider = record.displayName,
                status = record.status,
                percent = percent,
                blocked = record.isBlocked,
                nearLimit = record.isNearLimit,
                thresholdState = thresholdState,
                soonestReset = soonestReset(record),
            )
        }

/**
 * One row in the cross-host usage dashboard strip.
 *
 * Issue #214 added [thresholdState] so the row tint stays in sync with
 * the rest of the in-app warning surfaces. `blocked` and `nearLimit`
 * are kept on the data class for the existing call sites until they
 * migrate (the strip itself now reads [thresholdState]).
 */
public data class UsageDashboardRow(
    val provider: String,
    val status: com.pocketshell.core.usage.UsageStatus,
    val percent: Double,
    val blocked: Boolean,
    val nearLimit: Boolean,
    val thresholdState: UsageThresholdState = UsageThresholdState.Ok,
    /**
     * Issue #501: soonest `reset_at` across the provider's windows, so
     * the host-list strip can show who resets next. Null when the
     * provider reports no reset times.
     */
    val soonestReset: Instant? = null,
) {
    /**
     * Device-facing summary copy. Keeping the explicit "used" suffix in
     * the model prevents compact surfaces from regressing to ambiguous
     * bare percentages.
     */
    public val percentLabel: String
        get() = formatPercentUsed(percent)
}
