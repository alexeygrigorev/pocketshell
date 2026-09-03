package com.pocketshell.next.tree

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.core.hostapi.BackendError
import com.pocketshell.core.hostapi.HostCliError
import com.pocketshell.core.transport.ConnectResult
import com.pocketshell.core.transport.HostConnection
import com.pocketshell.next.connect.ConnectionsRegistry
import com.pocketshell.next.hostcli.HostCliClientFactory
import com.pocketshell.next.nav.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Everything the session tree renders.
 *
 * The three "nothing is on screen" situations are deliberately distinguishable,
 * because conflating them is the exact bug the schema-2 `errors[]` contract
 * (#2426) was introduced to end:
 *
 * - **still loading** — [loading] is true and [loaded] is false.
 * - **empty and healthy** — [loaded] with no [groups], no [errors], no
 *   [failure]. The host really has no sessions.
 * - **empty and broken** — [failure] set (the whole listing failed), or
 *   [errors] non-empty (one backend failed to enumerate while the other
 *   answered). Either way the screen says so instead of printing "No sessions".
 *
 * [failure] and [groups] coexist on purpose: a refresh that fails after a good
 * listing keeps the last known sessions on screen under an error banner. A
 * screen that blanked itself on a transient SSH hiccup would be less useful and
 * less truthful than one that says "this list is from a minute ago, the refresh
 * failed".
 */
data class SessionTreeUiState(
    val hostId: Long = 0,
    /** First load, nothing to paint yet. */
    val loading: Boolean = false,
    /** A refresh over content that is already on screen. */
    val refreshing: Boolean = false,
    /** At least one listing has succeeded, so [groups] is a real answer. */
    val loaded: Boolean = false,
    val groups: List<WorkspaceGroup> = emptyList(),
    /** Backends that failed to enumerate. Non-empty ⇒ this list may be short. */
    val errors: List<BackendError> = emptyList(),
    /** The whole listing failed. Distinct from "empty and healthy". */
    val failure: String? = null,
) {
    val sessionCount: Int get() = groups.sumOf { it.rows.size }

    /** True when the screen should say "no sessions" rather than stay blank. */
    val isEmptyAndHealthy: Boolean
        get() = loaded && groups.isEmpty() && errors.isEmpty() && failure == null
}

/**
 * The session tree for one host (rewrite task U-3, journey J02).
 *
 * One refresh is one `pocketshell sessions list --json` over the host's live
 * connection, parsed by `core-hostapi` and bucketed by [groupSessionsByWorkspace].
 * There is no poll loop, no cache, no incremental reconcile and no per-session
 * probe: agent-state polling is U-9's problem, and the old client's tree cache —
 * with its hydrate/reconcile/staleness machinery and its own persisted registry —
 * is exactly the second source of truth the rewrite is deleting. What the host
 * said on the last read is what the screen shows.
 *
 * ## Where the connection comes from
 *
 * [ConnectionsRegistry.getOrConnect], not [ConnectionsRegistry.current]. The
 * registry hands back the SAME connection U-2's connect gate opened when it is
 * still live, so the normal path (tap host → tree) does no second dial. Asking
 * for `current()` instead would mean this screen breaks the moment it is
 * reachable any other way — a deep link, a process restore onto the tree route,
 * or a connection that died while the screen was backgrounded and must be
 * re-dialled on the next `ON_START`. `getOrConnect` covers all of those with the
 * same call.
 *
 * The one thing this screen will NOT do is answer a host-key question.
 * [ConnectResult.NeedsTrust] surfaces as a [SessionTreeUiState.failure] telling
 * the user to connect from the host list, because trust is a decision the user
 * makes once, on the screen that owns it (U-2's [
 * com.pocketshell.next.connect.TrustPromptSheet]). Raising a second prompt here
 * would mean two code paths can write the trust store.
 *
 * ## Refresh cadence
 *
 * [refresh] is called on `ON_START` (which covers first entry AND every return
 * to the foreground) and by the pull-to-refresh gesture. Concurrent calls
 * collapse: a refresh arriving while one is in flight is dropped rather than
 * queued, so a user who pulls during the `ON_START` load gets one listing, not
 * two, and cannot stack execs on the connection.
 */
@HiltViewModel
class SessionTreeViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val registry: ConnectionsRegistry,
    private val clients: HostCliClientFactory,
) : ViewModel() {

    private val hostId: Long = requireNotNull(
        savedStateHandle.get<Long>(Destination.ARG_HOST_ID),
    ) { "SessionTreeViewModel needs a ${Destination.ARG_HOST_ID} argument" }

    private val _state = MutableStateFlow(SessionTreeUiState(hostId = hostId))
    val state: StateFlow<SessionTreeUiState> = _state.asStateFlow()

    private var inFlight: Job? = null

    /**
     * Re-reads the host's session list. Safe to call from `ON_START` and from
     * the pull gesture; a call made while a read is in flight is ignored.
     */
    fun refresh() {
        if (inFlight?.isActive == true) return
        _state.update { current ->
            current.copy(
                loading = !current.loaded,
                refreshing = current.loaded,
            )
        }
        inFlight = viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val connection = when (val result = registry.getOrConnect(hostId)) {
            is ConnectResult.Connected -> result.connection
            is ConnectResult.NeedsTrust -> return fail(
                "This host's key still needs to be confirmed. Open it from the host " +
                    "list to review the key.",
            )

            is ConnectResult.Failed -> return fail(result.message)
        }
        applyListing(connection)
    }

    private suspend fun applyListing(connection: HostConnection) {
        clients.create(connection).listSessions().fold(
            onSuccess = { listing ->
                _state.update { current ->
                    current.copy(
                        loading = false,
                        refreshing = false,
                        loaded = true,
                        groups = groupSessionsByWorkspace(listing.sessions),
                        errors = listing.errors,
                        // A successful listing clears a previous failure; the
                        // partial-backend banner is driven by `errors`, which
                        // this same read just replaced wholesale.
                        failure = null,
                    )
                }
            },
            onFailure = { error -> fail(userMessage(error)) },
        )
    }

    /**
     * Records a hard failure WITHOUT clearing [SessionTreeUiState.groups] — see
     * the state doc for why the last good listing stays on screen.
     */
    private fun fail(message: String) {
        _state.update { current ->
            current.copy(loading = false, refreshing = false, failure = message)
        }
    }

    /**
     * `core-hostapi` already writes user-facing text for every failure it
     * returns ([HostCliError.userMessage] covers "the CLI is too old",
     * "exit 127: not found", "did not finish within 20000ms"). Anything else
     * reaching here is a transport-level throwable, which has no such text, so
     * it gets a generic prefix rather than a bare exception class name.
     */
    private fun userMessage(error: Throwable): String = when (error) {
        is HostCliError -> error.userMessage
        else -> "Could not list sessions on the host: " +
            (error.message ?: error::class.simpleName ?: "unknown error")
    }
}
