package com.pocketshell.app.tmux

import android.os.SystemClock
import com.pocketshell.core.ssh.SshLease
import com.pocketshell.core.ssh.SshLeaseKey
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.tmux.TmuxClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.LinkedHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-scoped cache of warm tmux/UI runtimes keyed by host identity and
 * tmux session name.
 *
 * A cached runtime keeps the already-attached tmux control client and
 * TerminalSurfaceState graph alive while another same-host session is in the
 * foreground. Re-activation is therefore a pointer swap: the ViewModel
 * republishes the cached pane list and terminal states without opening SSH,
 * starting tmux -CC, listing panes, capturing panes, or creating terminal
 * state on the switch path.
 *
 * The cache is deliberately small: it retains only the most recently used
 * inactive runtimes per host and expires entries by monotonic time on cache
 * operations. Callers own closing returned evictions off the visible switch
 * path.
 */
@Singleton
public class TmuxSessionRuntimeCache @Inject constructor() {
    private var maxEntriesPerHost: Int = DEFAULT_MAX_ENTRIES_PER_HOST
    private var ttlMs: Long = DEFAULT_TTL_MS
    private var nowMs: () -> Long = SystemClock::elapsedRealtime

    internal constructor(
        maxEntries: Int,
        ttlMs: Long = DEFAULT_TTL_MS,
        nowMs: () -> Long = SystemClock::elapsedRealtime,
    ) : this() {
        this.maxEntriesPerHost = maxEntries
        this.ttlMs = ttlMs
        this.nowMs = nowMs
    }

    private val runtimes = object : LinkedHashMap<TmuxRuntimeKey, CacheEntry>(
        maxEntriesPerHost,
        0.75f,
        true,
    ) {}

    internal fun put(runtime: CachedTmuxRuntime): List<CachedTmuxRuntime> = synchronized(this) {
        val now = nowMs()
        val evicted = mutableListOf<CachedTmuxRuntime>()
        evicted += evictExpiredLocked(now)
        // Issue #681: prune any pre-existing entry for the SAME session
        // (same host + same tmux session name) before parking the fresh one,
        // even if its other key fields (keyPath/hostname/port/username)
        // drifted. Without this, a drifted twin of a session accumulates as a
        // second cache entry and shows up as a phantom pager page that routes
        // to a foreign session on settle. A session has exactly one live
        // runtime; the most recent put wins.
        evicted += pruneSameSessionTwinsLocked(runtime.key)
        runtimes.put(runtime.key, CacheEntry(runtime, now))?.let { evicted += it.runtime }
        evicted += evictHostOverflowLocked(runtime.key.hostId)
        evicted
    }

    private fun pruneSameSessionTwinsLocked(key: TmuxRuntimeKey): List<CachedTmuxRuntime> {
        val removed = mutableListOf<CachedTmuxRuntime>()
        val iterator = runtimes.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key != key && entry.key.isSameRuntimeSessionAs(key)) {
                iterator.remove()
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

    internal fun activate(key: TmuxRuntimeKey): CacheActivation = synchronized(this) {
        val now = nowMs()
        val evicted = mutableListOf<CachedTmuxRuntime>()
        evicted += evictExpiredLocked(now)
        val runtime = runtimes.remove(key)?.runtime
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

    internal fun contains(key: TmuxRuntimeKey): Boolean = synchronized(this) {
        runtimes.containsKey(key)
    }

    internal fun size(): Int = synchronized(this) { runtimes.size }

    internal fun hasLiveRuntime(): Boolean = synchronized(this) {
        runtimes.values.any { entry ->
            !entry.runtime.client.disconnected.value &&
                entry.runtime.session?.isConnected != false
        }
    }

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
        runtimes.remove(key)?.runtime
    }

    internal fun removeSession(hostId: Long, sessionName: String): List<CachedTmuxRuntime> =
        synchronized(this) {
            val trimmed = sessionName.trim()
            if (trimmed.isEmpty()) return@synchronized emptyList()
            val removed = mutableListOf<CachedTmuxRuntime>()
            val iterator = runtimes.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key.hostId == hostId && entry.key.sessionName == trimmed) {
                    iterator.remove()
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
                removed += entry.value.runtime
            }
        }
        removed
    }

    internal fun clear(): List<CachedTmuxRuntime> = synchronized(this) {
        val removed = runtimes.values.map { it.runtime }
        runtimes.clear()
        removed
    }

    private fun evictExpiredLocked(now: Long): List<CachedTmuxRuntime> {
        if (ttlMs == Long.MAX_VALUE) return emptyList()
        val removed = mutableListOf<CachedTmuxRuntime>()
        val iterator = runtimes.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.cachedAtMs >= ttlMs) {
                iterator.remove()
                removed += entry.value.runtime
            }
        }
        return removed
    }

    private fun evictHostOverflowLocked(hostId: Long): List<CachedTmuxRuntime> {
        val removed = mutableListOf<CachedTmuxRuntime>()
        while (runtimes.keys.count { it.hostId == hostId } > maxEntriesPerHost) {
            val eldestForHost = runtimes.entries.first { it.key.hostId == hostId }
            runtimes.remove(eldestForHost.key)
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
)

private data class CacheEntry(
    val runtime: CachedTmuxRuntime,
    val cachedAtMs: Long,
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
    val paneAgentJobs: Map<String, Job>,
    val paneAgentInputs: Map<String, Triple<String, String, String>>,
    val agentConversations: Map<String, com.pocketshell.app.session.AgentConversationUiState>,
    val remoteColumns: Int,
    val remoteRows: Int,
    val lease: SshLease? = null,
)

internal suspend fun CachedTmuxRuntime.closeCachedRuntime(
    detachTimeoutMs: Long = 1_000L,
) {
    // Issue #710: this teardown must be bounded so a pane job wedged in a
    // non-cooperative `-CC` socket read (which never honours cancellation)
    // cannot freeze the caller. `onCleared()` parks the runtime on the MAIN
    // thread via `runBlocking`, so an unbounded `cancelAndJoin()` or a
    // `NonCancellable` `lease.release()` would stall the main thread
    // indefinitely (activity never DESTROYED; real-device freeze on
    // background-mid-rapid-switch).
    //
    // Bound only the steps that can suspend forever: the three cancel/join
    // sweeps and the `lease.release()`. Each gets its own [detachTimeoutMs]
    // budget so the normal clean-close path (every step finishes in low
    // single-digit millis) is unaffected, while a single wedged job/lease can't
    // consume another step's budget. After a timeout we abandon the stuck
    // jobs/lease to the grace TTL / GC and return. `detachCleanly` is already
    // internally bounded and stays as-is.
    //
    // If a join times out we stop joining and fall through to the non-suspending
    // cleanup (queue close, producer detach, client close) so those still run.
    withTimeoutOrNull(detachTimeoutMs) {
        paneProducerJobs.values.forEach { it.cancelAndJoin() }
        paneInputJobs.values.forEach { it.cancelAndJoin() }
        paneAgentJobs.values.forEach { it.cancelAndJoin() }
    }
    paneInputQueues.values.forEach { runCatching { it.close() } }
    panes.forEach { pane ->
        runCatching { pane.terminalState.detachExternalProducer() }
    }
    runCatching { client.detachCleanly(timeoutMs = detachTimeoutMs) }
    runCatching { client.close() }
    withContext(NonCancellable) {
        // A wedged `lease.release()` (e.g. a transport stuck in a blocking
        // close) must not outlive the budget either: bound it so the main
        // thread is guaranteed to return. The abandoned lease falls to the
        // grace TTL / GC. NonCancellable keeps the release itself from being
        // cancelled by the parent runBlocking scope; withTimeoutOrNull adds
        // the wall-clock ceiling on top of that.
        withTimeoutOrNull(detachTimeoutMs) {
            runCatching { lease?.release() }
        }
    }
}
