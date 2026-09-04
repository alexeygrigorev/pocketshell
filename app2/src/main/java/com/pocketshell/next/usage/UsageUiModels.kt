package com.pocketshell.next.usage

import com.pocketshell.core.usage.UsageProviderRecord
import com.pocketshell.core.usage.UsageStatus
import com.pocketshell.core.usage.UsageThresholdState
import java.time.Instant

/**
 * One host's answer to `pocketshell usage --json`, as of one foreground fetch
 * (rewrite task P-5).
 *
 * Ported from the pre-rewrite client's `UsageScheduler.UsageSnapshot`, but the
 * scheduler itself is NOT ported: there is no poll cadence, no active-host
 * tracking, and no lease fan-out any more. A snapshot is simply "what the host
 * said when [UsageFetcher] last asked", and [fetchedAt] is when that was.
 *
 * The three outcomes are deliberately distinguishable, for the same reason the
 * session tree distinguishes its three empty states: "the host has no usage
 * tooling" and "the usage read failed" must not render identically, or the
 * panel says nothing useful about either.
 */
sealed interface UsageSnapshot {
    val hostId: Long
    val hostName: String
    val fetchedAt: Instant

    /** The host answered; [records] is whatever it reported (possibly empty). */
    data class Records(
        override val hostId: Long,
        override val hostName: String,
        val records: List<UsageProviderRecord>,
        override val fetchedAt: Instant,
    ) : UsageSnapshot

    /** `pocketshell` is not installed on the host (exit 127). */
    data class ToolMissing(
        override val hostId: Long,
        override val hostName: String,
        override val fetchedAt: Instant,
    ) : UsageSnapshot

    /** The read reached the host and failed for a reason worth showing. */
    data class Failed(
        override val hostId: Long,
        override val hostName: String,
        val reason: String,
        override val fetchedAt: Instant,
    ) : UsageSnapshot
}

/** One host's provider cards on the usage panel. */
data class UsageHostSnapshot(
    val hostId: Long,
    val hostName: String,
    val records: List<UsageProviderRecord>,
    val lastSyncedAt: Instant?,
)

/** A host whose `pocketshell` binary is missing entirely. */
data class UsageMissingToolHost(
    val hostId: Long,
    val hostName: String,
    val toolName: String = "pocketshell",
)

/** A host whose usage read failed, with the host's own reason. */
data class UsageFailedHost(
    val hostId: Long,
    val hostName: String,
    val reason: String,
)

/**
 * Everything [UsageScreen] renders.
 *
 * The pre-rewrite state carried `capturedAt` / `staleSince` / `showingCached`
 * and a `cachedAt` derivation — the stale-while-revalidate provenance of the
 * server-side `pocketshell usage --capture` cache (#689). The rewrite plan
 * deletes that whole path ("audit bucket: accreted"), so those fields are gone:
 * what is on screen was fetched by THIS foreground view or by the last manual
 * refresh, and nothing else.
 *
 * [connectedHostCount] is what distinguishes "no host is connected, so there is
 * nothing to ask" from "hosts answered and reported no providers" — the panel
 * says which, rather than showing one blank screen for both.
 */
data class UsageScreenState(
    val hosts: List<UsageHostSnapshot> = emptyList(),
    val missingToolHosts: List<UsageMissingToolHost> = emptyList(),
    val failedHosts: List<UsageFailedHost> = emptyList(),
    val isRefreshing: Boolean = false,
    /** True once at least one fetch has completed, whatever it found. */
    val loaded: Boolean = false,
    /** How many connected hosts the last fetch had to ask. */
    val connectedHostCount: Int = 0,
    /** The "limits just reset" banner content, or null when nothing recent. */
    val resetBanner: UsageResetBannerState? = null,
) {
    val providerCount: Int
        get() = hosts.sumOf { it.records.size }

    val hostCount: Int
        get() = hosts.size

    val allRecords: List<UsageProviderRecord>
        get() = hosts.flatMap { it.records }

    /** Nothing to paint and nothing wrong: no host is connected right now. */
    val isEmptyWithNoConnectedHosts: Boolean
        get() = loaded && connectedHostCount == 0

    /** Hosts answered, but not one of them reported a provider. */
    val isEmptyWithConnectedHosts: Boolean
        get() = loaded &&
            connectedHostCount > 0 &&
            providerCount == 0 &&
            missingToolHosts.isEmpty() &&
            failedHosts.isEmpty()
}

/**
 * Folds the fetched [snapshots] into the screen's three buckets.
 *
 * One function so a card, an "not installed" panel and a failure panel can
 * never disagree about which bucket a host landed in — the pre-rewrite
 * ViewModel maintained the three lists by hand across a fan-out and a cache
 * merge, which is exactly where a host could end up in two of them.
 */
fun usageScreenState(
    snapshots: Collection<UsageSnapshot>,
    connectedHostCount: Int,
    isRefreshing: Boolean = false,
    loaded: Boolean = true,
    resetBanner: UsageResetBannerState? = null,
): UsageScreenState = UsageScreenState(
    hosts = snapshots.filterIsInstance<UsageSnapshot.Records>().map { snapshot ->
        UsageHostSnapshot(
            hostId = snapshot.hostId,
            hostName = snapshot.hostName,
            records = snapshot.records,
            lastSyncedAt = snapshot.fetchedAt,
        )
    },
    missingToolHosts = snapshots.filterIsInstance<UsageSnapshot.ToolMissing>().map { snapshot ->
        UsageMissingToolHost(hostId = snapshot.hostId, hostName = snapshot.hostName)
    },
    failedHosts = snapshots.filterIsInstance<UsageSnapshot.Failed>().map { snapshot ->
        UsageFailedHost(
            hostId = snapshot.hostId,
            hostName = snapshot.hostName,
            reason = snapshot.reason,
        )
    },
    isRefreshing = isRefreshing,
    loaded = loaded,
    connectedHostCount = connectedHostCount,
    resetBanner = resetBanner,
)

/**
 * One row in the cross-host usage summary strip.
 *
 * `blocked` / `nearLimit` ride alongside [thresholdState] because the record's
 * own two-state derivation is what the provider dot uses, while the tint ladder
 * is the four-state one.
 */
data class UsageDashboardRow(
    val provider: String,
    val status: UsageStatus,
    val percent: Double,
    val blocked: Boolean,
    val nearLimit: Boolean,
    val thresholdState: UsageThresholdState = UsageThresholdState.Ok,
    /** Soonest `reset_at` across the provider's windows; null when it reports none. */
    val soonestReset: Instant? = null,
) {
    /**
     * Device-facing summary copy. Keeping the explicit "used" suffix in the
     * model prevents compact surfaces from regressing to ambiguous bare
     * percentages.
     */
    val percentLabel: String
        get() = formatPercentUsed(percent)
}

/**
 * The summary rows for the panel's cross-host strip, sorted by provider so the
 * same provider keeps the same spatial slot across refreshes.
 *
 * A provider with no thresholdable window is dropped, EXCEPT when it is hard
 * blocked — a block with no windows still surfaces at 100%, so a blocked
 * provider is never invisible.
 */
fun UsageScreenState.dashboardRows(
    warnPercent: Double = UsageProviderRecord.DEFAULT_WARN_PERCENT,
): List<UsageDashboardRow> =
    allRecords
        .sortedBy { it.provider }
        .mapNotNull { record ->
            val window = record.mostConstrainedWindow
            val thresholdState = record.thresholdState(warnPercent = warnPercent)
            val percent = window?.percent
                ?: if (thresholdState == UsageThresholdState.Exceeded) {
                    100.0
                } else {
                    return@mapNotNull null
                }
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
