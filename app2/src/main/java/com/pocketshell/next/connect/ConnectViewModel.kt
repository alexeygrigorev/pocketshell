package com.pocketshell.next.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.transport.ConnectResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The trust prompt as the screen needs it: the decision data M-3 produced, plus
 * the human label of the host it belongs to.
 *
 * [TrustPromptState] is keyed by `hostId` only — correct for the decision, but
 * a sheet that asks "trust this key?" without naming the host is asking a
 * question the user cannot answer, so the label is resolved here (once, from
 * the `hosts` row) rather than re-derived by the composable.
 */
data class TrustPrompt(
    val state: TrustPromptState,
    val hostLabel: String,
)

/** A dial that came back [ConnectResult.Failed], with the host to retry. */
data class ConnectError(
    val hostId: Long,
    val message: String,
)

/**
 * Everything the connect gate renders.
 *
 * At most ONE of [busyHostId] / [prompt] / [error] is ever set — a dial is
 * either in flight, waiting on the user, or finished. [navigateToHostId] is the
 * one-shot success signal, cleared by [ConnectViewModel.consumeNavigation] once
 * the navigation has actually been performed, so a recomposition (or a return
 * to this screen via Back) cannot re-fire it.
 */
data class ConnectUiState(
    val busyHostId: Long? = null,
    val prompt: TrustPrompt? = null,
    val error: ConnectError? = null,
    val navigateToHostId: Long? = null,
)

/**
 * Owns the tap-a-host-to-connect flow (rewrite task U-2).
 *
 * The whole flow is: ask [ConnectionsRegistry] for a connection, and render
 * whichever of the three [ConnectResult] arms comes back. There is no dial
 * logic, no retry policy and no trust bookkeeping here — those live in
 * core-transport and the registry; this class is the state machine that turns
 * their result into something a screen can paint.
 *
 * ## Why the connect lives in a ViewModel and not the composable
 *
 * A dial takes seconds and outlives a recomposition. Running it from a
 * composable would restart it on every recomposition (or need a `LaunchedEffect`
 * key dance to avoid that), and would lose the in-flight dial on a rotation.
 * [viewModelScope] is bound to the Hosts back-stack entry, so one tap produces
 * exactly one dial and the answer survives configuration changes.
 *
 * ## Trust
 *
 * On [ConnectResult.NeedsTrust] the result — including its `retry` lambda — is
 * held here and NOTHING is written. [trust] records the presented fingerprint
 * and then invokes that same `retry`, which re-runs the full dial through the
 * registry, so a successful post-trust connection is the registry's (and
 * therefore the app's) one connection for that host. [reject] drops the pending
 * result and writes nothing at all: rejecting must leave the host exactly as
 * untrusted as it was.
 */
@HiltViewModel
class ConnectViewModel @Inject constructor(
    private val registry: ConnectionsRegistry,
    private val hostDao: HostDao,
) : ViewModel() {

    private val _state = MutableStateFlow(ConnectUiState())
    val state: StateFlow<ConnectUiState> = _state.asStateFlow()

    /**
     * The unanswered trust result, held out of [ConnectUiState] because it
     * carries a suspend lambda: UI state should stay comparable/loggable data.
     */
    private var pending: ConnectResult.NeedsTrust? = null

    /** Dials [hostId], unless a dial or a prompt for some host is already open. */
    fun connect(hostId: Long) {
        val current = _state.value
        if (current.busyHostId != null || current.prompt != null) return
        pending = null
        _state.value = ConnectUiState(busyHostId = hostId)
        viewModelScope.launch { apply(hostId, registry.getOrConnect(hostId)) }
    }

    /**
     * Records the presented key as trusted for this host and re-dials.
     *
     * Order matters and is not interchangeable: the retry re-runs the host-key
     * verifier, so the fingerprint must already be stored when it does, or the
     * retry raises the very same prompt again.
     */
    fun trust() {
        val prompt = _state.value.prompt ?: return
        val retry = pending ?: return
        pending = null
        _state.value = ConnectUiState(busyHostId = prompt.state.hostId)
        viewModelScope.launch {
            registry.recordTrusted(prompt.state.hostId, prompt.state.fingerprintSha256)
            apply(prompt.state.hostId, retry.retry())
        }
    }

    /** Dismisses the prompt without recording anything. */
    fun reject() {
        pending = null
        _state.value = ConnectUiState()
    }

    /** Re-dials the host whose last attempt failed. */
    fun retry() {
        val hostId = _state.value.error?.hostId ?: return
        _state.value = ConnectUiState()
        connect(hostId)
    }

    /** Clears the error banner. */
    fun dismissError() {
        if (_state.value.error != null) _state.value = ConnectUiState()
    }

    /** Acknowledges [ConnectUiState.navigateToHostId] so it fires exactly once. */
    fun consumeNavigation() {
        _state.value = _state.value.copy(navigateToHostId = null)
    }

    private suspend fun apply(hostId: Long, result: ConnectResult) {
        when (result) {
            is ConnectResult.Connected ->
                _state.value = ConnectUiState(navigateToHostId = hostId)

            is ConnectResult.NeedsTrust -> {
                val promptState = TrustPromptState.from(hostId, result.decision)
                if (promptState == null) {
                    // NeedsTrust carrying a Trusted decision is a transport
                    // contract violation, not a user question. Surface it as a
                    // failure rather than raising a prompt with nothing to show
                    // (or, worse, silently dropping the tap).
                    _state.value = ConnectUiState(
                        error = ConnectError(
                            hostId = hostId,
                            message = "Host key check returned no decision to confirm.",
                        ),
                    )
                } else {
                    pending = result
                    _state.value = ConnectUiState(
                        prompt = TrustPrompt(
                            state = promptState,
                            hostLabel = hostLabel(hostId),
                        ),
                    )
                }
            }

            is ConnectResult.Failed ->
                _state.value = ConnectUiState(error = ConnectError(hostId, result.message))
        }
    }

    /**
     * `user@hostname`, matching the host-list subtitle so the sheet names the
     * host in the same words the row the user just tapped did. Falls back to
     * the id when the row is gone (a delete racing the dial).
     */
    private suspend fun hostLabel(hostId: Long): String {
        val host = hostDao.getById(hostId) ?: return "Host $hostId"
        return "${host.username}@${host.hostname}:${host.port}"
    }
}
