package com.pocketshell.app.usage

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.pocketshell.app.sessions.LeaseSessionExec
import com.pocketshell.app.sessions.LeaseSessionTarget
import com.pocketshell.app.startup.StartupTiming
import com.pocketshell.core.ssh.SshLeaseManager
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Application-singleton scheduler that periodically polls each
 * `pocketshell`-installed host for its usage payload (issue #117,
 * usage-panel Fix C; usage probe migrated to the unified `pocketshell
 * usage` CLI in issue #231).
 *
 * **No background work** (issue #161, decision D21).
 *
 * The scheduler hooks [ProcessLifecycleOwner] so the polling loop is
 * gated on the whole-process lifecycle state. Each tick only runs while
 * the process is at least [Lifecycle.State.STARTED] (at least one
 * Activity is in the started state — i.e. the user has the app
 * visible). When the user backgrounds the app the lifecycle drops to
 * [Lifecycle.State.CREATED] and the loop awaits the next `STARTED`
 * resume; nothing is polled in the meantime. On resume the loop
 * coalesces a single catch-up tick rather than replaying every missed
 * cadence.
 *
 * **Why a coroutine on a Singleton scope, not WorkManager?**
 *
 * `docs/usage-panel.md` calls for a 60-second cadence while the usage
 * panel is in the foreground. WorkManager's minimum periodic interval is
 * 15 minutes (`PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS`), so it
 * cannot model the foreground cadence. A long-running coroutine on an
 * application-singleton scope is the right shape: while the process is
 * alive AND foregrounded, it can switch between 60 s / 5 m cadence on
 * the fly by reading [foregroundActive]. When backgrounded it parks on
 * [ProcessLifecycleOwner] — no battery drain, no wakelocks, no doze
 * coordination required.
 *
 * **Inputs & outputs.**
 *
 * - Consumes [HostDao.getAll] to discover the set of saved hosts.
 * - Filters to hosts whose [HostEntity.pocketshellInstalled] cache says
 *   pocketshell is present. The cache is populated by the host bootstrap
 *   flow. Hosts that have never been probed are skipped here — the
 *   scheduler is intentionally passive; the bootstrap flow is the only
 *   entry point that mutates the pocketshell-cache columns.
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
    // Issue #699: borrow the host's WARM transport from the app-wide
    // @Singleton SshLeaseManager (the SAME instance the live session screens /
    // folder discovery use) for each usage poll instead of dialing a fresh
    // ~3-4s SSH handshake every cadence tick. The lease key is byte-identical
    // to those surfaces (`credentialId = "$hostId:$keyPath"`), so a poll reuses
    // the pooled connection a warm session already holds.
    //
    // Hilt injects the app-singleton binding on the production graph (the
    // default value is ignored when Hilt provides the parameter); the default
    // only serves the unit tests that construct the scheduler positionally and
    // never reach the live SSH path (they stub `fetchHost`).
    private val sshLeaseManager: SshLeaseManager = SshLeaseManager(
        connector = com.pocketshell.core.ssh.DefaultSshLeaseConnector(),
    ),
    private val usageNotifier: UsageNotifier = UsageNotifier.Noop,
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

    /**
     * Whole-process foreground signal driven by [ProcessLifecycleOwner].
     * `true` when the process is at least [Lifecycle.State.STARTED] —
     * i.e. an Activity is visible to the user. The polling loop reads
     * this flag and refuses to tick while it is `false`, satisfying the
     * no-background-work principle (D21 / issue #161).
     *
     * Visible-for-testing seam: connected tests flip the flag directly
     * via [setProcessStartedForTest] when they can't easily drive
     * [ProcessLifecycleOwner] from the instrumentation thread (the
     * standard `ActivityScenario.moveToState(CREATED)` path *does* drive
     * it, and is what the production wiring relies on).
     */
    private val _processStarted = MutableStateFlow(false)
    public val processStarted: StateFlow<Boolean> = _processStarted.asStateFlow()

    /**
     * Monotonically-increasing counter incremented at the start of each
     * fetch round (whether or not any hosts are eligible). Exposed so
     * the `NoBackgroundWorkE2eTest` instrumentation can prove the loop
     * did not tick while the app was backgrounded. Production code does
     * not read this — it is purely a test seam.
     */
    private val _tickCount = AtomicLong(0L)
    public val tickCount: Long
        get() = _tickCount.get()

    /**
     * Lifecycle observer kept as a field so a paired `removeObserver`
     * is safe even when the scheduler is reused across multiple
     * `observeProcessLifecycle()` calls (the second call is a no-op).
     */
    private val processLifecycleObserver = LifecycleEventObserver { _: LifecycleOwner, event ->
        when (event) {
            Lifecycle.Event.ON_START -> _processStarted.value = true
            Lifecycle.Event.ON_STOP -> _processStarted.value = false
            else -> Unit
        }
    }

    private var lifecycleAttached: Boolean = false

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
     * Attach a [ProcessLifecycleOwner] (or any [LifecycleOwner]) to the
     * scheduler so [processStarted] tracks the owner's `STARTED` /
     * `STOPPED` events. Called once from [com.pocketshell.app.App.onCreate];
     * subsequent calls are no-ops so it is safe to invoke from tests.
     *
     * The observer is registered on the main thread because
     * `Lifecycle.addObserver` requires it; the scheduler's polling
     * coroutine stays on [Dispatchers.IO] and only reads the resulting
     * [StateFlow].
     */
    public fun observeProcessLifecycle(
        owner: LifecycleOwner = ProcessLifecycleOwner.get(),
    ) {
        synchronized(this) {
            if (lifecycleAttached) return
            lifecycleAttached = true
        }
        scope.launch {
            withContext(Dispatchers.Main) {
                // Seed the current state so a scheduler attached late
                // (e.g. after the first `ON_START`) does not block at a
                // stale `false`.
                _processStarted.value = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                owner.lifecycle.addObserver(processLifecycleObserver)
            }
        }
    }

    /**
     * Visible-for-testing seam: flip the [processStarted] flag without
     * needing a real lifecycle owner. The production wiring uses
     * [observeProcessLifecycle] exclusively.
     */
    internal fun setProcessStartedForTest(started: Boolean) {
        _processStarted.value = started
    }

    /**
     * Start the polling loop. Idempotent — calling twice is a no-op so
     * a screen `LaunchedEffect` can call it on every recomposition.
     * Must be paired with [stop] or process death.
     *
     * The loop is gated on [processStarted] (D21 / issue #161): if the
     * process is currently `STOPPED`, ticks are suspended until the
     * next `ON_START`. No catch-up burst is fired on resume — a single
     * fetch happens once `processStarted` flips to `true`, then the
     * normal cadence resumes.
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

    /**
     * Merge externally-produced [UsageSnapshot]s into [snapshots]
     * (issue #116, usage-panel Fix B). The Usage panel's pull-to-
     * refresh path runs its own SSH fetch via [HostUsageFetcher] and
     * pushes the result through here so the host list strip + the
     * per-card / in-session badges pick up the same data without
     * waiting for the next 60 s tick.
     *
     * Snapshots for hosts that no longer have `pocketshellInstalled = true`
     * are silently dropped on the next [fetchOnce] tick — this method
     * only adds / overwrites entries, it does not reach into the
     * `pocketshellInstalled` cache.
     */
    public fun updateSnapshots(updates: Map<Long, UsageSnapshot>) {
        if (updates.isEmpty()) return
        val merged = _snapshots.value.toMutableMap()
        merged.putAll(updates)
        publishSnapshots(merged.toMap())
    }

    /**
     * Override hook for [NoBackgroundWorkE2eTest]: the test lowers the
     * cadence to a sub-second value so a regression that ignored the
     * lifecycle gate would burst many ticks during the 30 s background
     * window, making the "tick count did not move" assertion meaningful.
     * Production code leaves this at `null` and reads
     * [FOREGROUND_INTERVAL_MS] / [BACKGROUND_INTERVAL_MS] instead.
     */
    internal var loopIntervalOverrideMs: Long? = null

    /**
     * Per-host upper bound for one usage fetch. A single host can be slow,
     * wedged in SSH/exec, or stuck behind a transport lease; keep that failure
     * local so the scheduler-wide cadence/manual-refresh mutex is released.
     */
    internal var hostFetchTimeoutMs: Long = HOST_FETCH_TIMEOUT_MS

    private suspend fun runLoop() {
        try {
            while (true) {
                // Gate every tick on the whole-process lifecycle. If the
                // user has the app backgrounded we park here — `first { it }`
                // suspends until the StateFlow re-emits `true` on `ON_START`.
                // This is the core of the no-background-work principle
                // (D21 / issue #161).
                _processStarted.first { it }

                mutex.withLock { fetchOnce() }
                val intervalMs = loopIntervalOverrideMs
                    ?: if (_foregroundActive.value) FOREGROUND_INTERVAL_MS else BACKGROUND_INTERVAL_MS
                delay(intervalMs)
            }
        } catch (e: CancellationException) {
            throw e
        }
    }

    private suspend fun fetchOnce() {
        _tickCount.incrementAndGet()
        StartupTiming.markOnce("usage-fetch-once-start")
        val hosts = hostDao.getAll().first()
        val pocketshellHosts = hosts.filter { it.pocketshellInstalled == true && it.pocketshellVersionCompatible != false }
        StartupTiming.markOnce(
            "usage-fetch-hosts",
            "hostCount" to hosts.size,
            "pocketshellHostCount" to pocketshellHosts.size,
        )
        if (pocketshellHosts.isEmpty()) {
            // Drop any stale snapshots for hosts that no longer have pocketshell.
            if (_snapshots.value.isNotEmpty()) publishSnapshots(emptyMap())
            return
        }
        val next = _snapshots.value.toMutableMap()
        // Drop snapshots for hosts that have been removed or lost pocketshell.
        next.keys.retainAll(pocketshellHosts.map { it.id }.toSet())
        pocketshellHosts.forEach { host ->
            val snapshot = fetchHostWithTimeout(host)
            if (snapshot != null) {
                next[host.id] = snapshot
            }
        }
        publishSnapshots(next.toMap())
    }

    private suspend fun fetchHostWithTimeout(host: HostEntity): UsageSnapshot? {
        val completed = withTimeoutOrNull(hostFetchTimeoutMs) {
            HostFetchResult(fetchHost(host))
        }
        if (completed != null) return completed.snapshot
        return UsageSnapshot.Failed(
            hostId = host.id,
            hostName = host.name,
            reason = "Usage fetch timed out after ${hostFetchTimeoutMs} ms",
            fetchedAt = Instant.now(),
        )
    }

    private data class HostFetchResult(
        val snapshot: UsageSnapshot?,
    )

    private fun publishSnapshots(next: Map<Long, UsageSnapshot>) {
        _snapshots.value = next
        usageNotifier.onSnapshotsChanged(next)
    }

    private suspend fun fetchHostOverSsh(host: HostEntity): UsageSnapshot? {
        val key = sshKeyDao.getById(host.keyId) ?: return null
        val keyFile = File(key.privateKeyPath)
        if (!keyFile.exists()) return null
        // Skip passphrase-protected keys for the same reason Fix A's
        // UsageViewModel does — the scheduler can't prompt the user.
        if (key.hasPassphrase) return null

        StartupTiming.markOnce(
            "usage-ssh-connect-start",
            "hostId" to host.id,
            "host" to host.hostname,
            "port" to host.port,
        )
        // Issue #699: borrow the host's WARM transport from the app-wide
        // [SshLeaseManager] (reference-counted, released — never closed —
        // when the block returns) instead of dialing a fresh
        // [com.pocketshell.core.ssh.SshConnection] per poll. The lease key is
        // byte-identical to the live session screens' / folder discovery's, so
        // the poll reuses the pooled connection a warm session already holds.
        // Any failure (connect OR exec) yields null — best-effort, exactly as
        // the prior raw path treated a failed connect — so one bad host never
        // breaks the cadence round.
        return LeaseSessionExec.withSession(
            leaseManager = sshLeaseManager,
            target = LeaseSessionTarget(
                hostId = host.id,
                hostname = host.hostname,
                port = host.port,
                username = host.username,
                keyPath = key.privateKeyPath,
                passphrase = null,
            ),
        ) { session ->
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
        }.getOrNull()
    }

    public companion object {
        /** 60-second cadence while the usage panel is foregrounded. */
        public const val FOREGROUND_INTERVAL_MS: Long = 60_000L

        /**
         * 5-minute cadence while the process is foregrounded but the
         * usage panel itself is not (e.g. user is on the host list or
         * inside a session). The loop never ticks while the *process*
         * is `STOPPED` — see [processStarted] — so this is no longer
         * a "background while backgrounded" cadence (D21).
         */
        public const val BACKGROUND_INTERVAL_MS: Long = 5L * 60L * 1000L

        /** Bound each host fetch so one hung host cannot wedge the cadence. */
        public const val HOST_FETCH_TIMEOUT_MS: Long = 30_000L
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

/**
 * Pick the worst-case [UsageProviderRecord] for a host's
 * [UsageSnapshot] — the record that should drive the in-app blocked /
 * near-limit badge surfaced on the host card (issue #116, usage-panel
 * Fix B).
 *
 * Rules, ordered by severity:
 * - [UsageSnapshot.Records] — return the most-constrained record:
 *   first any record whose [UsageProviderRecord.isBlocked] is true
 *   (`status=Blocked` or a window at ≥100%), then any record whose
 *   [UsageProviderRecord.isNearLimit] is true (a window at
 *   ≥[UsageProviderRecord.WARN_PERCENT], i.e. 85% — well above the
 *   ≥90% trigger called out in the issue body, so a 90% window still
 *   surfaces the badge). When no record warrants a badge the function
 *   returns `null`, signalling "do not render the chip".
 * - [UsageSnapshot.ToolMissing] / [UsageSnapshot.Failed] — return
 *   `null`; the host card is already conveying the issue via the
 *   setup-state badge from #120 and the missing-tool empty state, and
 *   the usage chip would just double up the warning surface.
 *
 * Returning [UsageProviderRecord] (rather than a boolean) keeps the
 * caller in control of formatting: the badge composable
 * [com.pocketshell.app.usage.UsageSessionBlockedBadge] already maps an
 * `isBlocked` / `isNearLimit` record to "Blocked" / "Near limit"
 * copy with the matching colour.
 */
public fun UsageSnapshot.worstBadgeRecord(): UsageProviderRecord? {
    if (this !is UsageSnapshot.Records) return null
    val blocked = records.firstOrNull { it.isBlocked }
    if (blocked != null) return blocked
    return records.firstOrNull { it.isNearLimit }
}

/**
 * Threshold-aware variant of [worstBadgeRecord] (issue #214). Picks the
 * provider whose [UsageProviderRecord.thresholdState] is the most severe,
 * with ties broken in declared-order (Exceeded > Critical > Approaching
 * > Ok). Returns `null` when no provider in the snapshot warrants a
 * warning at the supplied [warnPercent].
 *
 * Why a second function rather than overloading the existing one: the
 * 85-percent constant on [UsageProviderRecord.WARN_PERCENT] is still
 * used by surfaces that haven't migrated yet (e.g. the pill kind on
 * the per-card progress bar), so the old function stays for callers
 * that explicitly want the legacy band.
 */
public fun UsageSnapshot.worstBadgeRecord(
    warnPercent: Double,
): com.pocketshell.core.usage.UsageProviderRecord? {
    if (this !is UsageSnapshot.Records) return null
    return records
        .map { it to it.thresholdState(warnPercent = warnPercent) }
        .filter { (_, state) -> state.warrantsWarning }
        .maxByOrNull { (_, state) -> state.ordinal }
        ?.first
}
