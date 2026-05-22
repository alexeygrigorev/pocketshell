package com.pocketshell.app.usage

import com.pocketshell.core.usage.UsageProviderRecord
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
    val toolName: String = "heru",
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

public fun UsageScreenState.dashboardRows(): List<UsageDashboardRow> =
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
            )
        }

public data class UsageDashboardRow(
    val provider: String,
    val status: com.pocketshell.core.usage.UsageStatus,
    val percent: Double,
    val blocked: Boolean,
    val nearLimit: Boolean,
)
