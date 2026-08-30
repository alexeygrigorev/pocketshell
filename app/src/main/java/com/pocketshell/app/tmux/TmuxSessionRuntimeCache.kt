package com.pocketshell.app.tmux

import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import com.pocketshell.app.AppTeardownScope
import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.core.connection.RuntimeHealthBinding
import com.pocketshell.core.connection.RuntimeHealthKey
import com.pocketshell.core.connection.RuntimeInstanceToken
import com.pocketshell.core.ssh.SshLease
import com.pocketshell.core.ssh.SshLeaseKey
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.tmux.TmuxClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.LinkedHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-scoped cache of warm tmux/UI runtimes keyed by host identity and the
 * exact tmux session generation when one is known. Picker prewarms may still
 * carry a name-only key until the live row supplies the generation.
 *
 * A cached runtime keeps the already-attached tmux control client and
 * TerminalSurfaceState graph alive while another same-host session is in the
 * foreground. Re-activation is therefore a pointer swap: the ViewModel
 * republishes the cached pane list and terminal states without opening SSH,
 * starting tmux -CC, listing panes, capturing panes, or creating terminal
 * state on the switch path.
 *
 * The cache is deliberately small: it retains only the most recently used
 * inactive runtimes per host. Expiry is owned by generation-keyed one-shot jobs
 * while the process is foregrounded; it never waits for another cache access.
 * The synchronized expiry claim and activation/removal operations form one
 * ownership boundary, so cleanup can never close a runtime that was reclaimed
 * or replaced under the same key.
 */
@Singleton
public class TmuxSessionRuntimeCache @Inject constructor() {
    private var maxEntriesPerHost: Int = DEFAULT_MAX_ENTRIES_PER_HOST
    private var ttlMs: Long = DEFAULT_TTL_MS
    private var nowMs: () -> Long = SystemClock::elapsedRealtime
    private var expiryScope: CoroutineScope = AppTeardownScope.scope
    private var processForeground: Boolean = false
    private var nextGeneration: Long = 0L
    private var nextCleanupSequence: Long = 0L
    private var cleanupInFlightCount: Int = 0
    private var lastCleanup: TmuxRuntimeCleanupDiagnostic? = null

    private val mutableExpiryClaims = MutableSharedFlow<RuntimeCacheExpiryClaim>(
        extraBufferCapacity = DEFAULT_MAX_ENTRIES_PER_HOST * 4,
    )
    internal val expiryClaims: SharedFlow<RuntimeCacheExpiryClaim> =
        mutableExpiryClaims.asSharedFlow()

    internal constructor(
        maxEntries: Int,
        ttlMs: Long = DEFAULT_TTL_MS,
        nowMs: () -> Long = SystemClock::elapsedRealtime,
        expiryScope: CoroutineScope = AppTeardownScope.scope,
    ) : this() {
        this.maxEntriesPerHost = maxEntries
        this.ttlMs = ttlMs
        this.nowMs = nowMs
        this.expiryScope = expiryScope
    }

    private val runtimes = object : LinkedHashMap<TmuxRuntimeKey, CacheEntry>(
        maxEntriesPerHost,
        0.75f,
        true,
    ) {}

    internal fun put(runtime: CachedTmuxRuntime): List<CachedTmuxRuntime> = synchronized(this) {
        val now = nowMs()
        val evicted = mutableListOf<CachedTmuxRuntime>()
        // Issue #681: prune any pre-existing entry for the SAME session
        // (same host + same tmux session name) before parking the fresh one,
        // even if its other key fields (keyPath/hostname/port/username)
        // drifted. Without this, a drifted twin of a session accumulates as a
        // second cache entry and shows up as a phantom pager page that routes
        // to a foreign session on settle. A session has exactly one live
        // runtime; the most recent put wins.
        evicted += pruneSameSessionTwinsLocked(runtime.key)
        runtimes.put(
            runtime.key,
            CacheEntry(
                runtime = runtime,
                cachedAtMs = now,
                generation = ++nextGeneration,
            ),
        )?.let { replaced ->
            replaced.expiryJob?.cancel()
            evicted += replaced.runtime
        }
        evicted += evictHostOverflowLocked(runtime.key.hostId)
        runtimes[runtime.key]?.let { scheduleExpiryLocked(runtime.key, it, now) }
        evicted
    }

    private fun pruneSameSessionTwinsLocked(key: TmuxRuntimeKey): List<CachedTmuxRuntime> {
        val removed = mutableListOf<CachedTmuxRuntime>()
        val iterator = runtimes.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key != key && entry.key.isSameRuntimeSessionAs(key)) {
                iterator.remove()
                entry.value.expiryJob?.cancel()
                removed += entry.value.runtime
            }
        }
        return removed
    }

    private fun TmuxRuntimeKey.isSameRuntimeSessionAs(other: TmuxRuntimeKey): Boolean {
        if (hostId != other.hostId) return false
        val leftDurable = durableSessionKey?.trim()?.takeIf { it.isNotEmpty() }
        val rightDurable = other.durableSessionKey?.trim()?.takeIf { it.isNotEmpty() }
        if (leftDurable != null && rightDurable != null) {
            return leftDurable == rightDurable
        }
        return leftDurable == null && rightDurable == null && sessionName == other.sessionName
    }

    internal fun activate(key: TmuxRuntimeKey): CacheActivation {
        var expiredClaim: ExpiryClaim? = null
        val activation = synchronized(this) {
            val evicted = mutableListOf<CachedTmuxRuntime>()
            val exact = runtimes[key]
            val runtime = if (exact != null && isExpired(exact, nowMs())) {
                runtimes.remove(key)
                exact.expiryJob?.cancel()
                expiredClaim = newExpiryClaimLocked(exact, nowMs())
                null
            } else {
                runtimes.remove(key)?.also { it.expiryJob?.cancel() }?.runtime
                    ?: removeNameOnlyPrewarmLocked(key)
            }
            // Issue #681: when a session becomes active, drop any key-drifted TWIN
            // of that same session still parked under a different key. Otherwise
            // the active session ends up with a duplicate cache entry that surfaces
            // as a phantom pager page and mis-routes on settle. activate() removing
            // only the exact key is exactly what let the twin survive.
            evicted += pruneSameSessionTwinsLocked(key)
            CacheActivation(
                runtime = runtime,
                evicted = evicted,
            )
        }
        expiredClaim?.let(::launchExpiryCleanup)
        return activation
    }

    /**
     * A picker prewarm is intentionally keyed only by host + session name,
     * because the picker callback does not provide durable tmux identity. When
     * the selected row subsequently supplies that identity, promote the single
     * name-only prewarm instead of opening a second control client. Never fall
     * back to another non-null durable identity: a killed/recreated same-name
     * session must remain a cache miss.
     */
    private fun removeNameOnlyPrewarmLocked(key: TmuxRuntimeKey): CachedTmuxRuntime? {
        if (key.durableSessionKey == null) return null
        val entry = runtimes.entries.firstOrNull { candidate ->
            candidate.key.hostId == key.hostId &&
                candidate.key.sessionName == key.sessionName &&
                candidate.key.durableSessionKey == null
        } ?: return null
        runtimes.remove(entry.key)?.expiryJob?.cancel()
        return entry.value.runtime
    }

    internal fun contains(key: TmuxRuntimeKey): Boolean = synchronized(this) {
        runtimes.containsKey(key)
    }

    /**
     * Session-picker prewarm receives only a host-scoped tmux session name, not
     * the durable tmux identity. Match that deliberately narrower namespace so
     * a parked runtime with a durable key is not prewarmed a second time.
     */
    internal fun containsSession(hostId: Long, sessionName: String): Boolean = synchronized(this) {
        runtimes.keys.any { it.hostId == hostId && it.sessionName == sessionName }
    }

    internal fun containsExact(binding: RuntimeHealthBinding): Boolean = synchronized(this) {
        runtimes.values.any { it.runtime.healthBinding == binding }
    }

    internal fun size(): Int = synchronized(this) { runtimes.size }

    internal fun diagnosticSnapshot(): TmuxRuntimeCacheDiagnostics = synchronized(this) {
        val values = runtimes.values.map { it.runtime }
        TmuxRuntimeCacheDiagnostics(
            cachedRuntimeCount = values.size,
            liveCachedRuntimeCount = values.count { runtime ->
                !runtime.client.disconnected.value &&
                    runtime.session?.isConnected != false
            },
            clientDisconnected = values.singleOrNull()?.client?.disconnected?.value,
            sessionConnected = values.singleOrNull()?.session?.isConnected,
            cleanupInFlightCount = cleanupInFlightCount,
            lastCleanup = lastCleanup,
        )
    }

    internal fun snapshotKeys(): List<TmuxRuntimeKey> = synchronized(this) {
        runtimes.keys.toList()
    }

    /**
     * Issue #626: return cached runtimes for a given host without removing them.
     * Used to build the unified pane list that spans all sessions.
     */
    internal fun cachedRuntimesForHost(hostId: Long): List<CachedTmuxRuntime> = synchronized(this) {
        runtimes.entries
            .filter { it.key.hostId == hostId }
            .map { it.value.runtime }
    }

    internal fun remove(key: TmuxRuntimeKey): CachedTmuxRuntime? = synchronized(this) {
        runtimes.remove(key)?.also { it.expiryJob?.cancel() }?.runtime
    }

    /**
     * Atomically remove only the runtime lifetime named by [binding].
     *
     * A logical host/session can already contain a newer replacement when an
     * old client's delayed EOF callback arrives. The old `removeSession`
     * operation erased that replacement. Exact compare-and-remove makes the
     * stale callback a no-op; there is deliberately no logical-key fallback.
     */
    internal fun removeExact(binding: RuntimeHealthBinding): CachedTmuxRuntime? =
        synchronized(this) {
            val iterator = runtimes.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.value.runtime.healthBinding == binding) {
                    iterator.remove()
                    entry.value.expiryJob?.cancel()
                    return@synchronized entry.value.runtime
                }
            }
            null
        }

    /** Remove only cached runtimes belonging to this exact tmux generation. */
    internal fun removeSession(
        hostId: Long,
        generation: TmuxSessionGeneration,
    ): List<CachedTmuxRuntime> =
        synchronized(this) {
            val removed = mutableListOf<CachedTmuxRuntime>()
            val iterator = runtimes.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key.hostId == hostId &&
                    entry.key.durableSessionKey == durableTmuxSessionKey(
                        hostId,
                        generation.sessionId,
                        generation.createdEpochSeconds,
                    )
                ) {
                    iterator.remove()
                    entry.value.expiryJob?.cancel()
                    removed += entry.value.runtime
                }
            }
            removed
        }

    internal fun removeHost(hostId: Long): List<CachedTmuxRuntime> = synchronized(this) {
        val removed = mutableListOf<CachedTmuxRuntime>()
        val iterator = runtimes.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key.hostId == hostId) {
                iterator.remove()
                entry.value.expiryJob?.cancel()
                removed += entry.value.runtime
            }
        }
        removed
    }

    internal fun removeLease(leaseKey: SshLeaseKey): List<CachedTmuxRuntime> = synchronized(this) {
        val removed = mutableListOf<CachedTmuxRuntime>()
        val iterator = runtimes.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.runtime.matchesLeaseKey(leaseKey)) {
                iterator.remove()
                entry.value.expiryJob?.cancel()
                removed += entry.value.runtime
            }
        }
        removed
    }

    internal fun clear(): List<CachedTmuxRuntime> = synchronized(this) {
        val removed = runtimes.values.map { it.runtime }
        runtimes.values.forEach { it.expiryJob?.cancel() }
        runtimes.clear()
        removed
    }

    /**
     * Accelerates the real one-shot expiry path for connected acceptance tests.
     *
     * This changes only the monotonic clock and TTL inputs used by production;
     * it does not claim, remove, or close a runtime on the test's behalf. The
     * returned handle restores the prior policy and re-arms any entry that the
     * journey left parked, preventing a process-wide singleton setting from
     * leaking into another instrumentation method.
     */
    @VisibleForTesting
    internal fun configureExpiryPolicyForTest(
        ttlMs: Long,
        nowMs: () -> Long,
    ): AutoCloseable {
        require(ttlMs > 0L) { "expiry TTL must be positive" }
        val previous = synchronized(this) {
            val snapshot = ExpiryPolicy(ttlMs = this.ttlMs, nowMs = this.nowMs)
            this.ttlMs = ttlMs
            this.nowMs = nowMs
            val now = nowMs()
            runtimes.forEach { (key, entry) -> scheduleExpiryLocked(key, entry, now) }
            snapshot
        }
        return AutoCloseable {
            synchronized(this) {
                this.ttlMs = previous.ttlMs
                this.nowMs = previous.nowMs
                val now = previous.nowMs()
                runtimes.forEach { (key, entry) -> scheduleExpiryLocked(key, entry, now) }
            }
        }
    }

    /**
     * D21 lifecycle gate. A foreground edge synchronously claims already-due
     * entries before the UI can reactivate them, then arms one one-shot job per
     * remaining cache generation. There is no periodic/background facility.
     */
    internal fun onProcessForegrounded() {
        val due = synchronized(this) {
            processForeground = true
            val now = nowMs()
            val claimed = mutableListOf<ExpiryClaim>()
            val iterator = runtimes.entries.iterator()
            while (iterator.hasNext()) {
                val (key, entry) = iterator.next()
                if (isExpired(entry, now)) {
                    iterator.remove()
                    entry.expiryJob?.cancel()
                    claimed += newExpiryClaimLocked(entry, now)
                } else {
                    scheduleExpiryLocked(key, entry, now)
                }
            }
            claimed
        }
        due.forEach(::launchExpiryCleanup)
    }

    /** Stop every expiry clock immediately while retaining absolute park age. */
    internal fun onProcessBackgrounded() = synchronized(this) {
        processForeground = false
        runtimes.values.forEach { entry ->
            entry.expiryJob?.cancel()
            entry.expiryJob = null
        }
    }

    private fun scheduleExpiryLocked(key: TmuxRuntimeKey, entry: CacheEntry, now: Long) {
        entry.expiryJob?.cancel()
        entry.expiryJob = null
        if (!processForeground || ttlMs == Long.MAX_VALUE) return
        val generation = entry.generation
        val delayMs = (ttlMs - (now - entry.cachedAtMs)).coerceAtLeast(0L)
        entry.expiryJob = expiryScope.launch {
            delay(delayMs)
            val claim = synchronized(this@TmuxSessionRuntimeCache) {
                val current = runtimes[key]
                if (!processForeground || current?.generation != generation) {
                    null
                } else {
                    runtimes.remove(key)
                    newExpiryClaimLocked(current, nowMs())
                }
            }
            claim?.let { cleanupExpiry(it) }
        }
    }

    private fun newExpiryClaimLocked(
        entry: CacheEntry,
        now: Long,
    ): ExpiryClaim {
        val sequence = ++nextCleanupSequence
        val ageMs = (now - entry.cachedAtMs).coerceAtLeast(0L)
        cleanupInFlightCount += 1
        lastCleanup = TmuxRuntimeCleanupDiagnostic(
            parkAgeMs = ageMs,
            reason = RuntimeCacheEvictionReason.TtlExpired,
            cleanupCompleted = false,
        )
        return ExpiryClaim(sequence, entry.runtime, ageMs)
    }

    private fun launchExpiryCleanup(claim: ExpiryClaim) {
        expiryScope.launch { cleanupExpiry(claim) }
    }

    private suspend fun cleanupExpiry(claim: ExpiryClaim) {
        // Resource ownership was already claimed atomically. Tear it down before
        // publishing the exact health-owner eviction: a Main-thread collector can
        // be stalled while more generations expire than SharedFlow can buffer,
        // but diagnostic/ledger backpressure must never postpone producer
        // cancellation, client close, or lease release. `emit` remains lossless
        // and exact after cleanup; it may wait for a slow live owner without
        // holding any runtime resource hostage.
        recordCleanupDiagnostic(claim, completed = false)
        try {
            claim.runtime.closeCachedRuntime()
        } finally {
            synchronized(this) {
                cleanupInFlightCount = (cleanupInFlightCount - 1).coerceAtLeast(0)
                if (nextCleanupSequence == claim.sequence) {
                    lastCleanup = TmuxRuntimeCleanupDiagnostic(
                        parkAgeMs = claim.parkAgeMs,
                        reason = RuntimeCacheEvictionReason.TtlExpired,
                        cleanupCompleted = true,
                    )
                }
            }
            recordCleanupDiagnostic(claim, completed = true)
        }
        mutableExpiryClaims.emit(RuntimeCacheExpiryClaim(claim.runtime.healthBinding))
    }

    private fun recordCleanupDiagnostic(claim: ExpiryClaim, completed: Boolean) {
        DiagnosticEvents.record(
            "tmux_runtime_cache",
            "parked_runtime_cleanup",
            "parkAgeMs" to claim.parkAgeMs,
            "reason" to RuntimeCacheEvictionReason.TtlExpired.logValue,
            "cleanupCompleted" to completed,
        )
    }

    private fun isExpired(entry: CacheEntry, now: Long): Boolean =
        ttlMs != Long.MAX_VALUE && now - entry.cachedAtMs >= ttlMs

    private fun evictHostOverflowLocked(hostId: Long): List<CachedTmuxRuntime> {
        val removed = mutableListOf<CachedTmuxRuntime>()
        while (runtimes.keys.count { it.hostId == hostId } > maxEntriesPerHost) {
            val eldestForHost = runtimes.entries.first { it.key.hostId == hostId }
            runtimes.remove(eldestForHost.key)
            eldestForHost.value.expiryJob?.cancel()
            removed += eldestForHost.value.runtime
        }
        return removed
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES_PER_HOST: Int = 2
        const val DEFAULT_TTL_MS: Long = 5 * 60 * 1000L
    }
}

internal data class CacheActivation(
    val runtime: CachedTmuxRuntime?,
    val evicted: List<CachedTmuxRuntime>,
)

internal data class TmuxRuntimeCacheDiagnostics(
    val cachedRuntimeCount: Int,
    val liveCachedRuntimeCount: Int,
    val clientDisconnected: Boolean?,
    val sessionConnected: Boolean?,
    val cleanupInFlightCount: Int,
    val lastCleanup: TmuxRuntimeCleanupDiagnostic?,
)

private data class CacheEntry(
    val runtime: CachedTmuxRuntime,
    val cachedAtMs: Long,
    val generation: Long,
    var expiryJob: Job? = null,
)

internal data class RuntimeCacheExpiryClaim(
    val healthBinding: RuntimeHealthBinding,
)

internal enum class RuntimeCacheEvictionReason(val logValue: String) {
    TtlExpired("ttl_expired"),
}

internal data class TmuxRuntimeCleanupDiagnostic(
    val parkAgeMs: Long,
    val reason: RuntimeCacheEvictionReason,
    val cleanupCompleted: Boolean,
)

private data class ExpiryClaim(
    val sequence: Long,
    val runtime: CachedTmuxRuntime,
    val parkAgeMs: Long,
)

private data class ExpiryPolicy(
    val ttlMs: Long,
    val nowMs: () -> Long,
)

internal data class TmuxRuntimeKey(
    val hostId: Long,
    val hostname: String,
    val port: Int,
    val username: String,
    val keyPath: String,
    val sessionName: String,
    val durableSessionKey: String? = null,
)

private fun TmuxRuntimeKey.matchesLeaseKey(leaseKey: SshLeaseKey): Boolean =
    hostname == leaseKey.host &&
        port == leaseKey.port &&
        username == leaseKey.user &&
        "$hostId:$keyPath" == leaseKey.credentialId &&
        leaseKey.knownHostsId == "accept-all"

private fun CachedTmuxRuntime.matchesLeaseKey(leaseKey: SshLeaseKey): Boolean =
    lease?.key == leaseKey || key.matchesLeaseKey(leaseKey)

internal data class CachedTmuxRuntime(
    val key: TmuxRuntimeKey,
    val hostName: String,
    val startDirectory: String?,
    val session: SshSession?,
    val client: TmuxClient,
    val panes: List<TmuxPaneState>,
    val paneRows: Map<String, TmuxPaneState>,
    val paneProducerJobs: Map<String, Job>,
    val paneInputQueues: Map<String, TmuxPaneInputQueue>,
    val paneInputJobs: Map<String, Job>,
    // Issue #1206: background seed-recovery jobs (bounded capture retry + one
    // deferred reseed on first live %output) carried from a prewarmed runtime so
    // a promoted-but-still-parked recovery job is cancelled on cache eviction /
    // deactivate (closeCachedRuntime) instead of leaking until whole-VM teardown.
    val paneSeedRecoveryJobs: Map<String, Job> = emptyMap(),
    val paneAgentInputs: Map<String, Triple<String, String, String>>,
    val agentConversations: Map<String, com.pocketshell.app.session.AgentConversationUiState>,
    val remoteColumns: Int,
    val remoteRows: Int,
    val lease: SshLease? = null,
    /**
     * Opaque identity for this exact cached-runtime lifetime. Host/session is
     * intentionally insufficient because a replacement can reuse both before
     * the old client's disconnect callback is delivered.
     */
    val healthBinding: RuntimeHealthBinding = RuntimeHealthBinding(
        key = RuntimeHealthKey(hostId = key.hostId, sessionName = key.sessionName),
        token = RuntimeInstanceToken.create(),
    ),
)

internal suspend fun CachedTmuxRuntime.closeCachedRuntime(
    detachTimeoutMs: Long = 1_000L,
) {
    // Issue #710: this teardown must be bounded so a pane job wedged in a
    // non-cooperative `-CC` socket read (which never honours cancellation)
    // cannot freeze the cleanup caller. Production eviction hands this
    // suspending teardown to the contained/application cleanup scopes; the
    // synchronous replacement seam uses the same bounded body only when its
    // caller explicitly needs completion before returning. An unbounded
    // `cancelAndJoin()` or `NonCancellable` `lease.release()` would otherwise
    // keep teardown alive indefinitely and stall lifecycle cleanup.
    //
    // Cancel every owned job first, then give this runtime one total
    // [detachTimeoutMs] deadline spanning joins, detach, and lease release.
    // Runtime batches close concurrently, so a wedged runtime cannot consume
    // another runtime's cleanup budget. After a timeout we abandon the stuck
    // jobs/lease to the grace TTL / GC and return.
    //
    // If a join times out we stop joining and fall through to the non-suspending
    // cleanup (queue close, producer detach, client close) so those still run.
    // Issue #1206: cancel any parked seed-recovery job FIRST (a promoted
    // prewarmed pane whose capture stayed empty parks on `outputFor().first()`).
    // `cancel()` (not `cancelAndJoin`) — the parked flow-collect returns
    // promptly on cancel and we must never block the bounded teardown on it.
    cancelCachedRuntimeJobs()
    val deadlineNanos = System.nanoTime() + detachTimeoutMs * 1_000_000L
    withTimeoutOrNull(remainingCleanupMillis(deadlineNanos)) {
        (paneProducerJobs.values + paneInputJobs.values + paneSeedRecoveryJobs.values).joinAll()
    }
    paneInputQueues.values.forEach { runCatching { it.close() } }
    panes.forEach { pane ->
        runCatching { pane.terminalState.detachExternalProducer() }
    }
    val detachBudgetMs = remainingCleanupMillis(deadlineNanos)
    withTimeoutOrNull(detachBudgetMs) {
        runCatching { client.detachCleanly(timeoutMs = detachBudgetMs) }
    }
    runCatching { client.close() }
    withContext(NonCancellable) {
        // A wedged `lease.release()` (e.g. a transport stuck in a blocking
        // close) must not outlive the budget either: bound it so the cleanup
        // scope can move on. The abandoned lease falls to the grace TTL / GC.
        // NonCancellable keeps the release itself from being cancelled by the
        // caller's scope; withTimeoutOrNull adds the wall-clock ceiling.
        withTimeoutOrNull(remainingCleanupMillis(deadlineNanos)) {
            runCatching { lease?.release() }
        }
    }
}

private fun CachedTmuxRuntime.cancelCachedRuntimeJobs() {
    paneSeedRecoveryJobs.values.forEach { it.cancel() }
    paneProducerJobs.values.forEach { it.cancel() }
    paneInputJobs.values.forEach { it.cancel() }
}

private fun remainingCleanupMillis(deadlineNanos: Long): Long =
    ((deadlineNanos - System.nanoTime()).coerceAtLeast(0L) / 1_000_000L).coerceAtLeast(1L)

/** Close every runtime independently so one wedged runtime cannot starve later owners. */
internal suspend fun closeCachedRuntimesConcurrently(
    runtimes: List<CachedTmuxRuntime>,
    detachTimeoutMs: Long,
) {
    // Cancellation ownership is transferred for the whole batch before any
    // join/detach can suspend.
    runtimes.forEach { it.cancelCachedRuntimeJobs() }
    coroutineScope {
        runtimes.map { runtime ->
            launch { runtime.closeCachedRuntime(detachTimeoutMs) }
        }.joinAll()
    }
}
