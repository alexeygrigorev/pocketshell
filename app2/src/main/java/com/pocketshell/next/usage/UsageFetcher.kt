package com.pocketshell.next.usage

import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.usage.PocketshellUsageJsonParser
import com.pocketshell.core.usage.UsageParseException
import com.pocketshell.next.connect.ConnectionsRegistry
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first

/**
 * One foreground read of `pocketshell usage --json` per CONNECTED host
 * (rewrite task P-5).
 *
 * This REPLACES the pre-rewrite client's `UsageScheduler` (564 lines of
 * cadence, active-host tracking and lease fan-out) and `UsageViewModel`'s own
 * lease fan-out. There is no poll loop, no cache and no notion of a "stale"
 * host beyond what [fetchAll]'s caller does with the timestamp on the result
 * — every call is a fresh round of execs, run in parallel, one per host that
 * [ConnectionsRegistry] already holds a LIVE connection for. A host with no
 * live connection is simply not asked (D21: no dial-just-to-read-usage; the
 * connection has to already exist for some other reason — the tree, a
 * session, a file browse).
 */
class UsageFetcher @Inject constructor(
    private val hostDao: HostDao,
    private val connections: ConnectionsRegistry,
    // No default value here: a default-arg `@Inject` constructor generates a
    // second constructor at the bytecode level that Hilt refuses to bind
    // (same trap the pre-rewrite client's `UsageRemoteSource` hit) — the
    // no-arg [PocketshellUsageJsonParser] is instead a plain `@Provides` in
    // `AppModule`.
    private val parser: PocketshellUsageJsonParser,
) {

    /** Everything one [fetchAll] round produced. */
    data class Result(
        val snapshots: Map<Long, UsageSnapshot>,
        val resetEvents: List<UsageResetEvent>,
        val connectedHostCount: Int,
    )

    suspend fun fetchAll(): Result = coroutineScope {
        val connectedHosts = hostDao.getAll().first()
            .filter { connections.current(it.id) != null }

        val snapshots = connectedHosts
            .map { host -> async { host.id to fetchHostUsage(host.id, host.name) } }
            .awaitAll()
            .toMap()

        val resetEvents = connectedHosts
            .map { host -> async { fetchResetEvents(host.id) } }
            .awaitAll()
            .flatten()

        Result(
            snapshots = snapshots,
            resetEvents = resetEvents,
            connectedHostCount = connectedHosts.size,
        )
    }

    private suspend fun fetchHostUsage(hostId: Long, hostName: String): UsageSnapshot {
        val connection = connections.current(hostId)
            ?: return UsageSnapshot.Failed(hostId, hostName, "not connected", Instant.now())

        val outcome = runCatching { connection.exec("$BINARY usage --json", TIMEOUT_MS) }
            .getOrElse { e ->
                return UsageSnapshot.Failed(
                    hostId,
                    hostName,
                    e.message ?: "usage command failed",
                    Instant.now(),
                )
            }
        // Exit 127 is the ONE unambiguous "binary not found" signal; a
        // non-zero exit with parseable stdout still counts as a read (the
        // host CLI resolved and answered, it just also reported an error
        // condition inline — see UsageProviderRecord.lastError).
        if (outcome.exitCode == 127) return UsageSnapshot.ToolMissing(hostId, hostName, Instant.now())

        val records = try {
            parser.parse(outcome.stdout)
        } catch (e: UsageParseException) {
            val reason = outcome.stderr.ifBlank { outcome.stdout }
                .ifBlank { "usage command exited ${outcome.exitCode}" }
            return UsageSnapshot.Failed(hostId, hostName, reason, Instant.now())
        }

        if (outcome.exitCode != 0 && records.isEmpty()) {
            val reason = outcome.stderr.ifBlank { outcome.stdout }
                .ifBlank { "usage command exited ${outcome.exitCode}" }
            return UsageSnapshot.Failed(hostId, hostName, reason, Instant.now())
        }
        return UsageSnapshot.Records(hostId, hostName, records, Instant.now())
    }

    /**
     * Best-effort — any failure collapses to an empty list so the reset
     * banner is simply absent rather than blocking the provider read it
     * rides alongside.
     */
    private suspend fun fetchResetEvents(hostId: Long): List<UsageResetEvent> {
        val connection = connections.current(hostId) ?: return emptyList()
        val outcome = runCatching { connection.exec("$BINARY usage --reset-events", TIMEOUT_MS) }
            .getOrNull() ?: return emptyList()
        if (outcome.exitCode != 0) return emptyList()
        return UsageResetEventsParser.parse(outcome.stdout)
    }

    private companion object {
        const val BINARY = "pocketshell"

        /**
         * Generous, matching [com.pocketshell.core.hostapi.HostCliClient]'s
         * read-verb budget: `usage` shells out to `quse`, which itself may
         * probe several provider CLIs.
         */
        const val TIMEOUT_MS: Long = 20_000
    }
}
