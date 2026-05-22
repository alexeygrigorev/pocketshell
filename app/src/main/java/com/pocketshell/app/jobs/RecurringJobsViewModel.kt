package com.pocketshell.app.jobs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
public class RecurringJobsViewModel @Inject constructor(
    private val remoteSource: TmuxctlJobsRemoteSource,
    private val connector: RecurringJobsSshConnector,
) : ViewModel() {

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
    private var session: SshSession? = null

    public fun load(
        hostName: String,
        hostname: String,
        port: Int,
        username: String,
        keyPath: String,
        passphrase: CharArray?,
        sessionName: String,
    ) {
        val next = Target(hostName, hostname, port, username, keyPath, passphrase, sessionName)
        if (next == target) return
        target = next
        session?.let { runCatching { it.close() } }
        session = null
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
                    _state.value = _state.value.copy(loading = false, error = "tmuxctl is not installed on this host")
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
                    _state.value = _state.value.copy(loading = false, error = "tmuxctl is not installed on this host")
                }
                is RecurringJobsCommandResult.Failed -> {
                    _state.value = _state.value.copy(loading = false, error = result.reason)
                }
            }
        }
    }

    private suspend fun ensureSession(target: Target): SshSession? {
        session?.let { return it }
        return try {
            connector.connect(target).getOrElse { error ->
                _state.value = _state.value.copy(
                    loading = false,
                    error = "connect failed: ${error.message}",
                )
                return null
            }.also { session = it }
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

    override fun onCleared() {
        session?.let { runCatching { it.close() } }
        session = null
        super.onCleared()
    }

    public data class Target(
        val hostName: String,
        val hostname: String,
        val port: Int,
        val username: String,
        val keyPath: String,
        val passphrase: CharArray?,
        val sessionName: String,
    )

    public interface RecurringJobsSshConnector {
        public suspend fun connect(target: Target): Result<SshSession>
    }

    public class DefaultRecurringJobsSshConnector @Inject constructor() : RecurringJobsSshConnector {
        override suspend fun connect(target: Target): Result<SshSession> =
            SshConnection.connect(
                host = target.hostname,
                port = target.port,
                user = target.username,
                key = SshKey.Path(File(target.keyPath)),
                passphrase = target.passphrase?.copyOf(),
                knownHosts = KnownHostsPolicy.AcceptAll,
            )
    }
}
