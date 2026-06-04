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
)

public data class UsageMissingToolHost(
    val hostId: Long,
    val hostName: String,
    val toolName: String = "pocketshell",
)

public data class UsageScreenState(
    val hosts: List<UsageHostSnapshot> = emptyList(),
    val missingToolHosts: List<UsageMissingToolHost> = emptyList(),
    val isRefreshing: Boolean = false,
) {
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
            val window = record.mostConstrainedWindow ?: return@mapNotNull null
            UsageDashboardRow(
                provider = record.displayName,
                status = record.status,
                percent = window.percent,
                blocked = record.isBlocked,
                nearLimit = record.isNearLimit,
                thresholdState = record.thresholdState(warnPercent = warnPercent),
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
)
