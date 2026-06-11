package com.pocketshell.app.jobs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshLease
import com.pocketshell.core.ssh.SshLeaseKey
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.inject.Inject

@HiltViewModel
public class RecurringJobsViewModel @Inject constructor(
    private val remoteSource: PocketshellJobsRemoteSource,
    private val connector: RecurringJobsSshConnector,
) : ViewModel() {

    // Issue #699: the connector now borrows ONE warm lease from the app-wide
    // @Singleton SshLeaseManager (keyed identically to the live session
    // screens, so it shares the same transport per host) instead of dialing a
    // fresh SSH connection. The lease is held for the screen's lifetime,
    // lease.session reused for every jobs read/write, and released in
    // onCleared().

    private val _state: MutableStateFlow<RecurringJobsScreenState> =
        MutableStateFlow(
            RecurringJobsScreenState(
                hostName = "",
                sessionName = null,
                jobs = emptyList(),
                loading = true,
            ),
        )
    public val state: StateFlow<RecurringJobsScreenState> = _state.asStateFlow()

    private var target: Target? = null
    private var lease: SshLease? = null

    public fun load(
        hostId: Long,
        hostName: String,
        hostname: String,
        port: Int,
        username: String,
        keyPath: String,
        passphrase: CharArray?,
        sessionName: String,
    ) {
        val next = Target(hostId, hostName, hostname, port, username, keyPath, passphrase, sessionName)
        if (next == target) return
        target = next
        releaseLease()
        _state.value = RecurringJobsScreenState(
            hostName = hostName,
            sessionName = sessionName,
            jobs = emptyList(),
            loading = true,
        )
        refresh()
    }

    public fun refresh() {
        val currentTarget = target ?: return
        viewModelScope.launch {
            val currentSession = ensureSession(currentTarget) ?: return@launch
            _state.value = _state.value.copy(loading = true, error = null)
            when (val result = remoteSource.list(currentSession, currentTarget.sessionName)) {
                is RecurringJobsCommandResult.Jobs -> {
                    _state.value = _state.value.copy(jobs = result.jobs, loading = false, error = null)
                }
                RecurringJobsCommandResult.Success -> {
                    _state.value = _state.value.copy(loading = false, error = null)
                }
                RecurringJobsCommandResult.ToolMissing -> {
                    _state.value = _state.value.copy(loading = false, error = "pocketshell is not installed on this host")
                }
                is RecurringJobsCommandResult.DaemonUnavailable -> {
                    _state.value = _state.value.copy(loading = false, error = jobsDaemonUnavailableMessage(result.reason))
                }
                is RecurringJobsCommandResult.Failed -> {
                    _state.value = _state.value.copy(loading = false, error = result.reason)
                }
            }
        }
    }

    public fun add(draft: RecurringJobDraft) {
        mutate { remoteSource.add(it, draft) }
    }

    public fun edit(jobId: Int, draft: RecurringJobDraft, enabled: Boolean) {
        mutate {
            remoteSource.edit(
                session = it,
                jobId = jobId,
                sessionName = draft.sessionName,
                every = draft.every,
                message = draft.message,
                enabled = enabled,
            )
        }
    }

    public fun remove(jobId: Int) {
        mutate { remoteSource.remove(it, jobId) }
    }

    private fun mutate(block: suspend (SshSession) -> RecurringJobsCommandResult) {
        val currentTarget = target ?: return
        viewModelScope.launch {
            val currentSession = ensureSession(currentTarget) ?: return@launch
            _state.value = _state.value.copy(loading = true, error = null)
            when (val result = block(currentSession)) {
                RecurringJobsCommandResult.Success -> refresh()
                is RecurringJobsCommandResult.Jobs -> {
                    _state.value = _state.value.copy(jobs = result.jobs, loading = false, error = null)
                }
                RecurringJobsCommandResult.ToolMissing -> {
                    _state.value = _state.value.copy(loading = false, error = "pocketshell is not installed on this host")
                }
                is RecurringJobsCommandResult.DaemonUnavailable -> {
                    _state.value = _state.value.copy(loading = false, error = jobsDaemonUnavailableMessage(result.reason))
                }
                is RecurringJobsCommandResult.Failed -> {
                    _state.value = _state.value.copy(loading = false, error = result.reason)
                }
            }
        }
    }

    /**
     * Issue #699: borrow the warm transport from the app-wide
     * [SshLeaseManager] (via [connector]) instead of dialing a fresh SSH
     * connection per screen. The lease is acquired once (keyed identically to
     * the live session screens via [Target.toLeaseTarget], so an already-warm
     * host transport is reused — no extra handshake) and its [SshSession] is
     * reused for every jobs read/write. Released in [onCleared] / [load].
     */
    private suspend fun ensureSession(target: Target): SshSession? {
        lease?.let { if (it.session.isConnected) return it.session }
        // A stale lease (transport dropped) is released before re-acquiring so
        // the next acquire opens or reuses a healthy transport.
        releaseLease()
        return try {
            connector.acquire(target).getOrElse { error ->
                _state.value = _state.value.copy(
                    loading = false,
                    error = "connect failed: ${error.message}",
                )
                return null
            }.also { lease = it }.session
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            _state.value = _state.value.copy(
                loading = false,
                error = "error: ${t.javaClass.simpleName}: ${t.message ?: "unknown error"}",
            )
            null
        }
    }

    /**
     * Release the held warm lease (refcount-- on the pooled transport) while
     * the screen is still alive — e.g. when [load] rebinds to a different
     * session. The viewModelScope is live here, so an async release is safe.
     */
    private fun releaseLease() {
        val toRelease = lease ?: return
        lease = null
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            runCatching { toRelease.release() }
        }
    }

    override fun onCleared() {
        // Issue #699: refcount-- the warm transport SYNCHRONOUSLY before the VM
        // dies. A viewModelScope.launch here would race the framework's
        // post-onCleared scope cancellation and could leak the refcount, so we
        // release on a bounded IO hop — mirroring TmuxSessionViewModel's
        // teardown. Releasing only decrements the pool refcount; the warm
        // transport itself stays pooled (idle-TTL) for the next surface.
        val toRelease = lease
        lease = null
        if (toRelease != null) {
            runCatching {
                runBlocking(Dispatchers.IO + NonCancellable) {
                    withTimeoutOrNull(LEASE_RELEASE_TIMEOUT_MS) { toRelease.release() }
                }
            }
        }
        super.onCleared()
    }

    private companion object {
        /** Bound the synchronous lease release at teardown (#699). */
        const val LEASE_RELEASE_TIMEOUT_MS: Long = 2_000L
    }

    public data class Target(
        val hostId: Long,
        val hostName: String,
        val hostname: String,
        val port: Int,
        val username: String,
        val keyPath: String,
        val passphrase: CharArray?,
        val sessionName: String,
    ) {
        /**
         * Issue #699: build the lease key the SAME way the live session screen
         * does — `credentialId = "$hostId:$keyPath"`, `knownHostsId =
         * "accept-all"` — so an already-warm host transport is reused rather
         * than a fresh handshake dialed.
         */
        public fun toLeaseTarget(): SshLeaseTarget =
            SshLeaseTarget(
                leaseKey = SshLeaseKey(
                    host = hostname,
                    port = port,
                    user = username,
                    credentialId = "$hostId:$keyPath",
                    knownHostsId = "accept-all",
                ),
                key = SshKey.Path(File(keyPath)),
                passphrase = passphrase?.copyOf(),
                knownHosts = KnownHostsPolicy.AcceptAll,
            )
    }

    /**
     * Issue #699: vends a warm [SshLease] from the shared pool. Replaces the
     * old fresh-dial connector (D22 hard-cut: no fallback connect path).
     */
    public interface RecurringJobsSshConnector {
        public suspend fun acquire(target: Target): Result<SshLease>
    }

    public class DefaultRecurringJobsSshConnector @Inject constructor(
        private val sshLeaseManager: SshLeaseManager,
    ) : RecurringJobsSshConnector {
        override suspend fun acquire(target: Target): Result<SshLease> =
            sshLeaseManager.acquire(target.toLeaseTarget())
    }
}

private fun jobsDaemonUnavailableMessage(reason: String): String =
    "Recurring jobs need the optional pocketshell jobs daemon. Enable it with `systemctl --user enable --now pocketshell-jobs.service`, then refresh. ${reason.trim()}"
