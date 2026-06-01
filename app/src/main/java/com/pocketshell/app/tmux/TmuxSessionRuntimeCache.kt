package com.pocketshell.app.tmux

import com.pocketshell.core.ssh.SshLease
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.tmux.TmuxClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
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
 */
@Singleton
public class TmuxSessionRuntimeCache @Inject constructor() {
    private var maxEntries: Int = DEFAULT_MAX_ENTRIES

    internal constructor(maxEntries: Int) : this() {
        this.maxEntries = maxEntries
    }

    private val runtimes = object : LinkedHashMap<TmuxRuntimeKey, CachedTmuxRuntime>(
        maxEntries,
        0.75f,
        true,
    ) {}

    internal fun put(runtime: CachedTmuxRuntime): CachedTmuxRuntime? = synchronized(this) {
        val evicted = mutableListOf<CachedTmuxRuntime>()
        runtimes.put(runtime.key, runtime)?.let { evicted += it }
        while (runtimes.size > maxEntries) {
            val eldest = runtimes.entries.iterator().next()
            runtimes.remove(eldest.key)
            evicted += eldest.value
        }
        evicted.firstOrNull()
    }

    internal fun activate(key: TmuxRuntimeKey): CachedTmuxRuntime? = synchronized(this) {
        runtimes.remove(key)
    }

    internal fun contains(key: TmuxRuntimeKey): Boolean = synchronized(this) {
        runtimes.containsKey(key)
    }

    internal fun size(): Int = synchronized(this) { runtimes.size }

    internal fun snapshotKeys(): List<TmuxRuntimeKey> = synchronized(this) {
        runtimes.keys.toList()
    }

    internal fun removeHost(hostId: Long): List<CachedTmuxRuntime> = synchronized(this) {
        val removed = mutableListOf<CachedTmuxRuntime>()
        val iterator = runtimes.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key.hostId == hostId) {
                iterator.remove()
                removed += entry.value
            }
        }
        removed
    }

    internal fun clear(): List<CachedTmuxRuntime> = synchronized(this) {
        val removed = runtimes.values.toList()
        runtimes.clear()
        removed
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES: Int = 4
    }
}

internal data class TmuxRuntimeKey(
    val hostId: Long,
    val hostname: String,
    val port: Int,
    val username: String,
    val keyPath: String,
    val sessionName: String,
)

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

internal suspend fun CachedTmuxRuntime.closeCachedRuntime() {
    paneProducerJobs.values.forEach { it.cancelAndJoin() }
    paneInputJobs.values.forEach { it.cancelAndJoin() }
    paneAgentJobs.values.forEach { it.cancelAndJoin() }
    paneInputQueues.values.forEach { it.close() }
    panes.forEach { pane ->
        runCatching { pane.terminalState.detachExternalProducer() }
    }
    runCatching { client.detachCleanly() }
    withContext(NonCancellable) {
        runCatching { lease?.release() }
    }
}
