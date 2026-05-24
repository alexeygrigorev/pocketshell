package com.pocketshell.app.usage

import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.dao.SshKeyDao
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.usage.UsageProviderRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Application-singleton scheduler that periodically polls each
 * `heru`-installed host for its usage payload (issue #117, usage-panel
 * Fix C).
 *
 * **Why a coroutine on a Singleton scope, not WorkManager?**
 *
 * `docs/usage-panel.md` calls for a 60-second cadence while the usage
 * panel is in the foreground. WorkManager's minimum periodic interval is
 * 15 minutes (`PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS`), so it
 * cannot model the foreground cadence. A long-running coroutine on an
 * application-singleton scope is the right shape: it runs only while the
 * process is alive (which is exactly when the screens that consume it
 * are visible), and it can switch between 60 s / 5 m cadence on the fly
 * by reading [foregroundActive].
 *
 * The background 5-minute poll is *also* in-process — we do not attempt
 * to wake the device when PocketShell is fully backgrounded. This
 * matches the doc's guidance ("60s while open, 5m background") which the
 * planning notes describe as the in-app cadence, not a system-level
 * doze-respecting one. A WorkManager-driven daily refresh could be
 * layered on later if cold-start data freshness becomes a concern.
 *
 * **Inputs & outputs.**
 *
 * - Consumes [HostDao.getAll] to discover the set of saved hosts.
 * - Filters to hosts whose [HostEntity.heruInstalled] cache says heru is
 *   present. The cache is populated by [HostListViewModel.persistHeruResult]
 *   on host bootstrap. Hosts that have never been probed are skipped here
 *   — the scheduler is intentionally passive; the bootstrap flow is the
 *   only entry point that mutates the heru-cache columns.
 * - Forwards [HostEntity.usageCommandOverride] (or `null` for the
 *   default) to [UsageRemoteSource.fetchUsage].
 * - Exposes results via [snapshots] — a map from host id to the latest
 *   [UsageSnapshot]. The sibling Fix A `UsageViewModel` is the eventual
 *   consumer (see TODO note below); until that wire-up lands the data
 *   still proves the polling loop is working by being inspectable from
 *   tests.
 *
 * **Coordination with Fix A.**
 *
 * The sibling agent's `UsageViewModel` (issue #117 Fix A) currently
 * polls inline on every `refresh()` call. At merge time the orchestrator
 * should wire that view model to observe [snapshots] (or call
 * [refreshNow]) so the foreground 60s cadence runs through this
 * scheduler rather than being duplicated. A small TODO comment is left
 * in this file at [snapshots] so the integration is easy to spot.
 *
 * @see docs/usage-panel.md
 */
@Singleton
public class UsageScheduler @Inject constructor(
    private val hostDao: HostDao,
    private val sshKeyDao: SshKeyDao,
    private val remoteSource: UsageRemoteSource,
) {
    /**
     * Visible-for-testing seam: tests inject a fake fetch lambda so they
     * can exercise the scheduler's filter + state-flow plumbing without
     * standing up an SSH transport. Production code never reassigns it —
     * the default delegates to [fetchHostOverSsh], which is the real
     * SSH path.
     */
    internal var fetchHost: suspend (HostEntity) -> UsageSnapshot? = ::fetchHostOverSsh


    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var loopJob: Job? = null

    private val _foregroundActive = MutableStateFlow(false)
    public val foregroundActive: StateFlow<Boolean> = _foregroundActive.asStateFlow()

    private val _snapshots = MutableStateFlow<Map<Long, UsageSnapshot>>(emptyMap())

    /**
     * Per-host latest usage snapshot keyed by [HostEntity.id]. Updated
     * on each completed poll. The sibling Fix A `UsageViewModel` (see
     * file KDoc) should observe this flow instead of re-polling
     * separately so the cadence is centralised.
     */
    // TODO(#117 Fix A integration): UsageViewModel.refresh() currently
    //  iterates hosts itself; at merge time, rewire it to collect this
    //  StateFlow (and call refreshNow() to satisfy pull-to-refresh).
    public val snapshots: StateFlow<Map<Long, UsageSnapshot>> = _snapshots.asStateFlow()

    /**
     * Start the polling loop. Idempotent — calling twice is a no-op so
     * a screen `LaunchedEffect` can call it on every recomposition.
     * Must be paired with [stop] or process death.
     */
    public fun start() {
        synchronized(this) {
            if (loopJob?.isActive == true) return
            loopJob = scope.launch { runLoop() }
        }
    }

    /**
     * Stop the polling loop. Safe to call at any time; subsequent
     * [start] calls bring it back. Used in tests and as a back-stop for
     * shutdown sequences.
     *
     * Suspends until the in-flight tick (if any) finishes cleanly. Callers
     * that don't need to await completion can fire-and-forget by calling
     * [cancel] instead.
     */
    public suspend fun stop() {
        val current = synchronized(this) {
            val captured = loopJob
            loopJob = null
            captured
        }
        current?.cancelAndJoin()
    }

    /**
     * Synchronous best-effort cancel — does not wait for the in-flight
     * fetch to drain. Intended for shutdown paths that cannot suspend.
     */
    public fun cancel() {
        synchronized(this) {
            loopJob?.cancel()
            loopJob = null
        }
    }

    /**
     * Toggle the foreground-active flag. Callers (today: the Fix A
     * UsageViewModel via init/onCleared once integrated) call
     * `setForegroundActive(true)` when the usage screen mounts and
     * `false` when it leaves the foreground. The next loop tick reads
     * this flag and picks the matching cadence.
     */
    public fun setForegroundActive(active: Boolean) {
        _foregroundActive.value = active
    }

    /**
     * Trigger an immediate fetch round outside the cadence timer. Used
     * by pull-to-refresh in the usage view model and by tests that
     * don't want to wait the full delay.
     */
    public suspend fun refreshNow() {
        mutex.withLock { fetchOnce() }
    }

    private suspend fun runLoop() {
        try {
            while (true) {
                mutex.withLock { fetchOnce() }
                val intervalMs = if (_foregroundActive.value) FOREGROUND_INTERVAL_MS else BACKGROUND_INTERVAL_MS
                delay(intervalMs)
            }
        } catch (e: CancellationException) {
            throw e
        }
    }

    private suspend fun fetchOnce() {
        val hosts = hostDao.getAll().first()
        val heruHosts = hosts.filter { it.heruInstalled == true }
        if (heruHosts.isEmpty()) {
            // Drop any stale snapshots for hosts that no longer have heru.
            if (_snapshots.value.isNotEmpty()) _snapshots.value = emptyMap()
            return
        }
        val next = _snapshots.value.toMutableMap()
        // Drop snapshots for hosts that have been removed or lost heru.
        next.keys.retainAll(heruHosts.map { it.id }.toSet())
        heruHosts.forEach { host ->
            val snapshot = fetchHost(host)
            if (snapshot != null) {
                next[host.id] = snapshot
            }
        }
        _snapshots.value = next.toMap()
    }

    private suspend fun fetchHostOverSsh(host: HostEntity): UsageSnapshot? {
        val key = sshKeyDao.getById(host.keyId) ?: return null
        val keyFile = File(key.privateKeyPath)
        if (!keyFile.exists()) return null
        // Skip passphrase-protected keys for the same reason Fix A's
        // UsageViewModel does — the scheduler can't prompt the user.
        if (key.hasPassphrase) return null

        val session: SshSession = try {
            SshConnection.connect(
                host = host.hostname,
                port = host.port,
                user = host.username,
                key = SshKey.Path(keyFile),
                knownHosts = KnownHostsPolicy.AcceptAll,
            ).getOrNull() ?: return null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            return null
        }

        return try {
            when (val result = remoteSource.fetchUsage(session, commandOverride = host.usageCommandOverride)) {
                is UsageFetchResult.Success -> UsageSnapshot.Records(
                    hostId = host.id,
                    hostName = host.name,
                    records = result.records,
                    fetchedAt = Instant.now(),
                    command = host.usageCommandOverride ?: UsageRemoteSource.defaultUsageCommand,
                )

                UsageFetchResult.ToolMissing -> UsageSnapshot.ToolMissing(
                    hostId = host.id,
                    hostName = host.name,
                    fetchedAt = Instant.now(),
                )

                is UsageFetchResult.Failed -> UsageSnapshot.Failed(
                    hostId = host.id,
                    hostName = host.name,
                    reason = result.reason,
                    fetchedAt = Instant.now(),
                )
            }
        } finally {
            runCatching { session.close() }
        }
    }

    public companion object {
        /** 60-second cadence while the usage panel is foregrounded. */
        public const val FOREGROUND_INTERVAL_MS: Long = 60_000L

        /** 5-minute cadence when the panel is not foregrounded. */
        public const val BACKGROUND_INTERVAL_MS: Long = 5L * 60L * 1000L
    }
}

/**
 * Per-host outcome of one poll round. The scheduler emits this through
 * [UsageScheduler.snapshots] so consumers don't have to re-execute the
 * SSH probe.
 */
public sealed interface UsageSnapshot {
    public val hostId: Long
    public val hostName: String
    public val fetchedAt: Instant

    public data class Records(
        override val hostId: Long,
        override val hostName: String,
        val records: List<UsageProviderRecord>,
        override val fetchedAt: Instant,
        val command: String,
    ) : UsageSnapshot

    public data class ToolMissing(
        override val hostId: Long,
        override val hostName: String,
        override val fetchedAt: Instant,
    ) : UsageSnapshot

    public data class Failed(
        override val hostId: Long,
        override val hostName: String,
        val reason: String,
        override val fetchedAt: Instant,
    ) : UsageSnapshot
}
