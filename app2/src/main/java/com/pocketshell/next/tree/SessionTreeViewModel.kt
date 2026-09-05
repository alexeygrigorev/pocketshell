package com.pocketshell.next.tree

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.core.hostapi.BackendError
import com.pocketshell.core.hostapi.EngineInfo
import com.pocketshell.core.hostapi.HostCliError
import com.pocketshell.core.hostapi.ProfileInfo
import com.pocketshell.core.storage.dao.ProjectRootDao
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
import kotlinx.coroutines.flow.first
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
 * - **empty and healthy** — [loaded] with no sessions, no [errors], no
 *   [failure]. The host really has no sessions.
 * - **empty and broken** — [failure] set (the whole listing failed), or
 *   [errors] non-empty (one backend failed to enumerate while the other
 *   answered). Either way the screen says so instead of printing "No sessions".
 *
 * [failure] and [roots] coexist on purpose: a refresh that fails after a good
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
    /** At least one listing has succeeded, so [roots] is a real answer. */
    val loaded: Boolean = false,
    val roots: List<SessionRoot> = emptyList(),
    /** Backends that failed to enumerate. Non-empty ⇒ this list may be short. */
    val errors: List<BackendError> = emptyList(),
    /** The whole listing failed. Distinct from "empty and healthy". */
    val failure: String? = null,
    /** Everything the create-session sheet needs (task U-6). */
    val create: CreateSessionState = CreateSessionState(),
) {
    val sessionCount: Int get() = roots.sumOf { it.sessionCount }

    /** True when the screen should say "no sessions" rather than stay blank. */
    val isEmptyAndHealthy: Boolean
        get() = loaded && sessionCount == 0 && errors.isEmpty() && failure == null

    /**
     * What the create sheet's folder field is prefilled with: the workspace of
     * the most recently active session, when the host reported one.
     *
     * Tree *order* is creation, but the prefill is still "where you were last"
     * — a create affordance, not a reason to shuffle the list. The
     * [OTHER_ROOT_LABEL] bucket is not a path and is skipped; with nothing
     * usable the field starts empty, which means "no `--cwd`" and lets the
     * host's own default apply.
     */
    val suggestedFolder: String
        get() = roots.asSequence()
            .flatMap { it.folders }
            .flatMap { it.rows }
            .filter { it.workspace?.startsWith("/") == true }
            .maxByOrNull { it.activityEpoch ?: Long.MIN_VALUE }
            ?.workspace
            ?: ""
}

/**
 * The create-session sheet's state (task U-6, journey J04).
 *
 * [openRequest] is a one-shot navigation signal rather than a callback the
 * ViewModel holds: a created session is opened by the SCREEN, through the same
 * `onOpenSession` edge a row tap uses, so there is exactly one way to reach the
 * session route. It is cleared by [SessionTreeViewModel.consumeOpenRequest]
 * before the navigation runs, so coming Back to the tree cannot re-trigger it.
 *
 * [notice] carries the "that session already existed" message. The host CLI's
 * create is idempotent and reports `created: false` for a name that was already
 * there — a SUCCESS, per [com.pocketshell.core.hostapi.CreatedSession]. Treating
 * it as a failure would be the bug: the user asked for that session and now has
 * it, which is exactly what they wanted.
 */
data class CreateSessionState(
    /** The sheet is on screen. */
    val visible: Boolean = false,
    /** A create is in flight; the sheet stays open and its inputs are frozen. */
    val submitting: Boolean = false,
    /** The create failed. The sheet STAYS open so the user can edit and retry. */
    val failure: String? = null,
    /** "Opened the existing session X" — never an error. */
    val notice: String? = null,
    /** The session name the screen should open next, once. */
    val openRequest: String? = null,
    /** Host `engines list --json`, unfiltered; the sheet hides disabled/unavailable. */
    val engines: List<EngineInfo> = emptyList(),
    /** Host `profiles list --json`; the sheet filters these to the selected engine. */
    val profiles: List<ProfileInfo> = emptyList(),
    /** True while the first engines/profiles read for this sheet opening is in flight. */
    val enginesLoading: Boolean = false,
    /** Why engines could not be listed. Agent create is unavailable; Shell still works. */
    val enginesFailure: String? = null,
)

/**
 * The session tree for one host (rewrite task U-3, journey J02).
 *
 * One refresh is one `pocketshell sessions list --json` over the host's live
 * connection, parsed by `core-hostapi` and bucketed by [groupSessionsIntoRoots].
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
    private val projectRootDao: ProjectRootDao,
) : ViewModel() {

    private val hostId: Long = requireNotNull(
        savedStateHandle.get<Long>(Destination.ARG_HOST_ID),
    ) { "SessionTreeViewModel needs a ${Destination.ARG_HOST_ID} argument" }

    private val _state = MutableStateFlow(SessionTreeUiState(hostId = hostId))
    val state: StateFlow<SessionTreeUiState> = _state.asStateFlow()

    private var inFlight: Job? = null
    private var createInFlight: Job? = null
    private var pickerInFlight: Job? = null

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

    // --- create (task U-6) -------------------------------------------------

    /** Raises the create sheet, with no stale failure or notice on it. */
    fun openCreateSheet() {
        updateCreate {
            it.copy(
                visible = true,
                failure = null,
                notice = null,
                enginesLoading = true,
                enginesFailure = null,
            )
        }
        pickerInFlight?.cancel()
        pickerInFlight = viewModelScope.launch { loadPickerOptions() }
    }

    /**
     * Closes the sheet without creating anything. Ignored while a create is in
     * flight: the session is already being made on the host, and closing the
     * sheet would leave the user with no sight of the result.
     */
    fun dismissCreateSheet() {
        updateCreate { current ->
            if (current.submitting) {
                current
            } else {
                pickerInFlight?.cancel()
                current.copy(visible = false, failure = null, enginesLoading = false)
            }
        }
    }

    /**
     * `pocketshell sessions create --json` for [request], then refresh the
     * listing and ask the screen to open it.
     *
     * A session that already existed comes back `created == false`, which is a
     * SUCCESS: the sheet closes, the tree refreshes and the screen opens that
     * session, with a notice saying it was already there. A FAILURE leaves the
     * sheet open with its text intact so the user can fix the folder and retry.
     *
     * [CreateSessionRequest.engine] / [CreateSessionRequest.profile] /
     * [CreateSessionRequest.backend] are forwarded when set and omitted when
     * null, so a Shell create with the host-default backend is still
     * `sessions create --json -- NAME`.
     */
    fun createSession(request: CreateSessionRequest) {
        if (createInFlight?.isActive == true) return
        val trimmedName = request.name.trim()
        if (trimmedName.isEmpty()) {
            // The host CLI takes NAME as a required positional argument, so a
            // blank name is answered here rather than sent for the host to
            // reject with a usage error.
            updateCreate { it.copy(failure = BLANK_NAME_MESSAGE) }
            return
        }
        updateCreate { it.copy(submitting = true, failure = null, notice = null) }
        createInFlight = viewModelScope.launch {
            runCreate(
                name = trimmedName,
                cwd = request.cwd?.trim()?.takeIf { it.isNotEmpty() },
                engine = request.engine?.trim()?.takeIf { it.isNotEmpty() },
                profile = request.profile?.trim()?.takeIf { it.isNotEmpty() },
                backend = request.backend?.trim()?.takeIf { it.isNotEmpty() },
            )
        }
    }

    /**
     * Clears the one-shot open signal. Called by the screen BEFORE it
     * navigates, so returning to the tree cannot re-open the session.
     */
    fun consumeOpenRequest() {
        updateCreate { it.copy(openRequest = null) }
    }

    private suspend fun loadPickerOptions() {
        val connection = when (val outcome = resolveConnection()) {
            is ConnectionOutcome.Ready -> outcome.connection
            is ConnectionOutcome.Unavailable -> {
                updateCreate {
                    it.copy(enginesLoading = false, enginesFailure = outcome.message)
                }
                return
            }
        }
        val client = clients.create(connection)
        val engines = client.listEngines()
        val profiles = client.listProfiles()
        updateCreate { current ->
            if (!current.visible) {
                current
            } else {
                current.copy(
                    enginesLoading = false,
                    engines = engines.getOrDefault(emptyList()),
                    profiles = profiles.getOrDefault(emptyList()),
                    enginesFailure = engines.exceptionOrNull()?.let { error ->
                        userMessage(error, "Could not list engines on the host: ")
                    },
                )
            }
        }
    }

    private suspend fun runCreate(
        name: String,
        cwd: String?,
        engine: String?,
        profile: String?,
        backend: String?,
    ) {
        val connection = when (val outcome = resolveConnection()) {
            is ConnectionOutcome.Ready -> outcome.connection
            is ConnectionOutcome.Unavailable -> return failCreate(outcome.message)
        }
        clients.create(connection)
            .createSession(
                name = name,
                cwd = cwd,
                engine = engine,
                profile = profile,
                backend = backend,
            )
            .fold(
                onSuccess = { created ->
                    updateCreate {
                        CreateSessionState(
                            visible = false,
                            submitting = false,
                            failure = null,
                            notice = if (created.created) {
                                null
                            } else {
                                "Session \"${created.name}\" already existed — opened it."
                            },
                            // The HOST's name for what it made, not the typed
                            // one: an aplexer-backed create answers with its own
                            // `workspace:tag` display name.
                            openRequest = created.name,
                        )
                    }
                    refresh()
                },
                onFailure = { error ->
                    failCreate(userMessage(error, "Could not create the session on the host: "))
                },
            )
    }

    /** A create that failed: the sheet stays open, carrying the reason. */
    private fun failCreate(message: String) {
        updateCreate { it.copy(visible = true, submitting = false, failure = message) }
    }

    private fun updateCreate(block: (CreateSessionState) -> CreateSessionState) {
        _state.update { current -> current.copy(create = block(current.create)) }
    }

    private suspend fun load() {
        when (val outcome = resolveConnection()) {
            is ConnectionOutcome.Ready -> applyListing(outcome.connection)
            is ConnectionOutcome.Unavailable -> fail(outcome.message)
        }
    }

    /**
     * The host's live connection, or the user-facing reason there isn't one.
     *
     * Shared by the listing and the create so both reach the host exactly one
     * way — through [ConnectionsRegistry.getOrConnect], which reuses U-2's
     * connection when it is still live and re-dials when it is not.
     */
    private suspend fun resolveConnection(): ConnectionOutcome =
        when (val result = registry.getOrConnect(hostId)) {
            is ConnectResult.Connected -> ConnectionOutcome.Ready(result.connection)
            is ConnectResult.NeedsTrust -> ConnectionOutcome.Unavailable(
                "This host's key still needs to be confirmed. Open it from the host " +
                    "list to review the key.",
            )

            is ConnectResult.Failed -> ConnectionOutcome.Unavailable(result.message)
        }

    private sealed interface ConnectionOutcome {
        data class Ready(val connection: HostConnection) : ConnectionOutcome
        data class Unavailable(val message: String) : ConnectionOutcome
    }

    private suspend fun applyListing(connection: HostConnection) {
        val registered = projectRootDao.getByHostId(hostId).first()
            .sortedWith(compareBy({ it.createdAt }, { it.id }))
            .map { it.path }
        clients.create(connection).listSessions().fold(
            onSuccess = { listing ->
                _state.update { current ->
                    current.copy(
                        loading = false,
                        refreshing = false,
                        loaded = true,
                        roots = groupSessionsIntoRoots(
                            sessions = listing.sessions,
                            registeredRoots = registered,
                        ),
                        errors = listing.errors,
                        // A successful listing clears a previous failure; the
                        // partial-backend banner is driven by `errors`, which
                        // this same read just replaced wholesale.
                        failure = null,
                    )
                }
            },
            onFailure = { error ->
                fail(userMessage(error, "Could not list sessions on the host: "))
            },
        )
    }

    /**
     * Records a hard failure WITHOUT clearing [SessionTreeUiState.roots] — see
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
     * it gets [fallbackPrefix] rather than a bare exception class name.
     */
    private fun userMessage(error: Throwable, fallbackPrefix: String): String = when (error) {
        is HostCliError -> error.userMessage
        else -> fallbackPrefix + (error.message ?: error::class.simpleName ?: "unknown error")
    }
}

/**
 * What the sheet says when a create is attempted with no name. `internal` so
 * the test asserts the SAME string the screen paints rather than a hand-copied
 * duplicate that can drift.
 */
internal const val BLANK_NAME_MESSAGE: String = "Enter a name for the session."
