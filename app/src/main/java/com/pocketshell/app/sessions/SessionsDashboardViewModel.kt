package com.pocketshell.app.sessions

import android.content.Context
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.app.systemsurfaces.ActiveSessionsWidgetProvider
import com.pocketshell.app.systemsurfaces.SystemSurfaceStateStore
import com.pocketshell.core.tmux.TmuxClient
import com.pocketshell.core.tmux.protocol.ControlEvent
import com.pocketshell.uikit.model.SessionAgentState
import com.pocketshell.uikit.model.resolveSessionAgentState
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Maintains the cross-host tmux session aggregate.
 *
 * ## Aggregation
 *
 * Observes [ActiveTmuxClients.clients]: whenever a host registers a
 * live `tmux -CC` client, the view model spawns a per-host coroutine
 * that periodically issues
 * `list-sessions -F "#{session_name}::#{session_created}::#{session_activity}::#{session_attached}"`.
 * The parsed rows are folded into [sessions], a
 * flat list across every host, sorted by [SessionSummary.lastActivity].
 *
 * Issue #298 removed the old flat all-host Sessions dashboard from the
 * landing screen. The aggregate still feeds host-card status derivation
 * and active-session system surfaces.
 *
 * ## Polling vs `%session-changed` subscription
 *
 * The issue's "OR" clause suggests we could subscribe to
 * `%session-changed` events instead of polling. We pick polling for v1
 * because:
 *
 *  - `%session-changed` only fires when the attached client's session
 *    pointer flips — it does NOT signal new-session creation or
 *    activity-timestamp updates inside the same session. To get the
 *    timestamps we'd still need to issue `list-sessions` periodically.
 *  - A 10s cadence is cheap (one short command per host per 10s) and
 *    matches the perceived freshness of the mockup's "2m / 8m / 14m"
 *    relative timestamps.
 *  - Polling keeps the implementation event-source agnostic — when
 *    Phase 3 wires the lifecycle UI in #48 we can layer event-driven
 *    refreshes on top without changing this contract.
 *
 * If the cadence becomes noisy on a many-host workspace, the next
 * iteration can subscribe to [TmuxClient.events] for
 * [com.pocketshell.core.tmux.protocol.ControlEvent.SessionsChanged]
 * and trigger an immediate refresh in addition to the periodic one.
 *
 * ## Lifecycle
 *
 * The view model is `@HiltViewModel` so its lifetime tracks the host
 * landing screen. The registry it observes is a singleton so it survives
 * navigation away and back. Per-host poller [Job]s are cancelled and
 * restarted as the registry's snapshot changes, and all of them tear
 * down when [onCleared] cancels `viewModelScope`.
 *
 * ## Foreground-gated polling (battery — issue #1164)
 *
 * The `list-sessions` poll only exists to keep the visible dashboard's
 * relative timestamps + agent chips fresh, so it must not run while the
 * app is backgrounded / the screen is off — a backgrounded dashboard
 * nobody is looking at that keeps firing an SSH `list-sessions` round-trip
 * per host every 10s is pure battery/heat waste (the audit's "clearest
 * small win", #1164). Each per-host [pollLoop] parks on [processStarted]
 * (`first { it }`) at the top of every iteration: while the whole process
 * is below [Lifecycle.State.STARTED] (the user has backgrounded the app,
 * `ON_STOP`) no poll fires; on `ON_START` the loop resumes with an
 * immediate fetch so the dashboard is never stale on return.
 *
 * [processStarted] is driven by [ProcessLifecycleOwner] via
 * [observeProcessLifecycle], which the host landing screen calls once on
 * composition. The mechanism mirrors
 * [com.pocketshell.app.usage.UsageScheduler] — the dashboard's sibling
 * periodic poll, already foreground-gated for the same D21 reason.
 *
 * ## Testability
 *
 * Tests build a real [ActiveTmuxClients], register
 * [FakeTmuxClient][com.pocketshell.app.tmux.FakeTmuxClient] instances
 * pre-loaded with canned `list-sessions` responses, and assert
 * against [sessions]. The polling interval is parameterised via
 * [pollIntervalMs] so tests can drive it tightly with `runTest`'s
 * virtual clock.
 */
@HiltViewModel
class SessionsDashboardViewModel @Inject constructor(
    private val activeClients: ActiveTmuxClients,
    private val sessionListParser: HostTmuxSessionListParser = HostTmuxSessionListParser(),
    @ApplicationContext private val appContext: Context? = null,
) : ViewModel() {

    /** Override hook for tests — see [SessionsDashboardViewModelTest]. */
    internal var pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS

    /**
     * Dispatcher the active-session-count persistence runs on. Defaults to
     * [Dispatchers.IO] so the [SystemSurfaceStateStore] disk read never lands
     * on the Main thread (issue #1086, freeze cause F5). Overridable for tests.
     */
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    private val _sessions: MutableStateFlow<List<SessionSummary>> =
        MutableStateFlow(emptyList())

    /**
     * Flat, sorted-by-recency-descending list of every tmux session
     * discovered across every registered host. Empty when no live
     * clients are registered or when none of the registered hosts
     * reports any sessions.
     */
    val sessions: StateFlow<List<SessionSummary>> = _sessions.asStateFlow()

    /**
     * One-shot user-facing message for a failed lifecycle operation
     * (issue #168 — kill failures must not be silently swallowed). The
     * screen renders this in a banner slot and clears it via
     * [clearKillError] once the user dismisses it.
     */
    private val _killError: MutableStateFlow<String?> = MutableStateFlow(null)
    val killError: StateFlow<String?> = _killError.asStateFlow()

    /**
     * One-shot user-facing message for a failed [createSession] attempt
     * (issue #204 — create failures must surface a visible banner so the
     * user can tell a "duplicate name" rejection apart from a silent
     * no-op). The screen renders this in a banner slot and clears it via
     * [clearCreateError] once the user dismisses it.
     */
    private val _createError: MutableStateFlow<String?> = MutableStateFlow(null)
    val createError: StateFlow<String?> = _createError.asStateFlow()

    /**
     * Per-host poller jobs. Keyed by host id so we can cancel the right
     * one when a host unregisters. Per-host snapshot caches let us
     * rebuild the flat list without re-polling every host on every
     * partial update.
     */
    private val pollerJobs: MutableMap<Long, Job> = mutableMapOf()
    private val pollerEntries: MutableMap<Long, ActiveTmuxClients.Entry> = mutableMapOf()
    private val perHostSnapshots: MutableMap<Long, List<SessionSummary>> = mutableMapOf()
    private var registryJob: Job? = null

    /**
     * Whole-process foreground signal (issue #1164). `true` while the
     * process is at least [Lifecycle.State.STARTED] — an Activity is
     * visible to the user. Each [pollLoop] parks on this flag so no
     * `list-sessions` round-trip fires while the app is backgrounded /
     * the screen is off.
     *
     * Defaults to `true` so a view model that is never attached to a
     * lifecycle (the unit-test seams that construct it directly) polls
     * exactly as before. Production wiring calls [observeProcessLifecycle]
     * from the host landing screen, which seeds the flag from the owner's
     * current state and flips it on every `ON_START` / `ON_STOP`.
     */
    private val _processStarted = MutableStateFlow(true)
    val processStarted: StateFlow<Boolean> = _processStarted.asStateFlow()

    /**
     * The attached process lifecycle + its observer. Kept as fields so
     * [onCleared] can detach cleanly and a second [observeProcessLifecycle]
     * with the same owner is a no-op (a different owner detaches the
     * previous one first — the [com.pocketshell.app.usage.UsageScheduler]
     * / [com.pocketshell.app.portfwd.PortForwardPanelViewModel] pattern).
     */
    private var attachedLifecycle: Lifecycle? = null
    private val lifecycleObserver = LifecycleEventObserver { _: LifecycleOwner, event ->
        when (event) {
            Lifecycle.Event.ON_START -> _processStarted.value = true
            Lifecycle.Event.ON_STOP -> _processStarted.value = false
            else -> Unit
        }
    }

    init {
        // One supervising coroutine watches the registry. When the
        // snapshot of clients changes we diff against [pollerJobs] and
        // start / stop pollers accordingly. The snapshot itself is a
        // plain Map so identity changes (a register / unregister) are
        // the only signal — values inside an entry never change.
        registryJob = viewModelScope.launch {
            activeClients.clients.collect { snapshot ->
                reconcilePollers(snapshot)
            }
        }
    }

    /**
     * Diff the current poller set against [snapshot]. Spin up a poller
     * for new entries, cancel pollers for removed entries, and rebuild
     * [sessions] from the per-host caches.
     *
     * Replaced entries (same host id, different client instance) get a
     * fresh poller — the old one is cancelled because the client
     * reference is no longer the one we should be talking to.
     */
    private fun reconcilePollers(snapshot: Map<Long, ActiveTmuxClients.Entry>) {
        // Cancel pollers for hosts that disappeared.
        val gone = pollerJobs.keys - snapshot.keys
        for (hostId in gone) {
            pollerJobs.remove(hostId)?.cancel()
            pollerEntries.remove(hostId)
            perHostSnapshots.remove(hostId)
        }

        // Start pollers for new hosts (or restart if the entry was
        // replaced — same key, different value).
        for ((hostId, entry) in snapshot) {
            val existing = pollerJobs[hostId]
            val existingEntry = pollerEntries[hostId]
            if (existing != null && existing.isActive && existingEntry == entry) {
                continue
            }
            existing?.cancel()
            perHostSnapshots.remove(hostId)
            pollerEntries[hostId] = entry
            pollerJobs[hostId] = viewModelScope.launch {
                pollLoop(entry)
            }
        }

        emitAggregate()
    }

    /**
     * Poll `list-sessions` against [entry]'s client on a fixed cadence.
     *
     * Failures (transport drop, tmux error) keep the existing per-host
     * snapshot visible, but mark it stale and clear attached/live hints
     * so the UI does not present an old snapshot as current. The next
     * successful poll overwrites the stale entry.
     */
    private suspend fun pollLoop(entry: ActiveTmuxClients.Entry) {
        // Immediate first poll so the section populates as fast as
        // possible after register; subsequent polls back off to the
        // configured cadence.
        while (currentCoroutineContext().isActive) {
            // Issue #1164 (battery/heat): gate every poll on the
            // whole-process foreground state. While the app is
            // backgrounded / screen off (`ON_STOP` -> processStarted
            // false) `first { it }` parks the loop, so no `list-sessions`
            // SSH round-trip fires — the dashboard nobody is looking at
            // stops draining battery. On `ON_START` the flag flips true,
            // the loop resumes here and immediately fetches, so the list
            // is never stale on return. When no lifecycle is attached
            // (unit-test seams) the flag defaults true and this returns
            // instantly — unchanged behaviour.
            processStarted.first { it }
            val rows = runCatching { fetchSessions(entry) }.getOrNull()
            if (rows != null) {
                perHostSnapshots[entry.hostId] = rows
                emitAggregate()
            } else {
                markHostSnapshotStale(entry.hostId)
            }
            delay(pollIntervalMs)
        }
    }

    /**
     * Issue one `list-sessions` call against [entry]'s client and parse
     * the response. Visible-for-test through [parseListSessionsRow].
     */
    private suspend fun fetchSessions(entry: ActiveTmuxClients.Entry): List<SessionSummary>? {
        val response = entry.client.sendCommand(LIST_SESSIONS_COMMAND)
        if (response.isError) return null
        return response.output.mapNotNull { line ->
            parseListSessionsRow(
                line = line,
                hostId = entry.hostId,
                hostName = entry.hostName,
            )
        }
    }

    /**
     * Rebuild [sessions] from [perHostSnapshots]. Flat-mapping across
     * the per-host map is O(total sessions) per emission — fine for the
     * expected workload (a handful of hosts × a handful of sessions
     * each).
     *
     * Sorted by [SessionSummary.lastActivity] descending; ties broken
     * by host name then session name so the order is deterministic
     * across emissions.
     */
    private fun emitAggregate() {
        val all = perHostSnapshots.values.flatten()
        val sorted = all.sortedWith(
            compareByDescending<SessionSummary> { it.lastActivity }
                .thenBy { it.hostName }
                .thenBy { it.sessionName },
        )
        _sessions.value = sorted
        persistActiveSessionCount(sorted.size)
    }

    /**
     * Persist the live active-session count to the cross-surface store and
     * refresh the home-screen widget.
     *
     * Issue #1086 (freeze cause F5): the [SystemSurfaceStateStore] prefs-file
     * read is a ~648ms first-touch disk read. This is reached from
     * [emitAggregate] on `viewModelScope` (Main), so it used to stall the
     * cold-launch UI thread. We dispatch the construct-and-write onto
     * [ioDispatcher] (default [Dispatchers.IO]) so neither the store build nor
     * the write ever blocks Main. The count is idempotent (last write wins) and
     * `apply()` writes are serialised by SharedPreferences, so dispatching each
     * emission is safe.
     */
    private fun persistActiveSessionCount(count: Int) {
        val context = appContext ?: return
        viewModelScope.launch(ioDispatcher) {
            SystemSurfaceStateStore(context).setActiveSessionCount(count)
            ActiveSessionsWidgetProvider.updateAll(context)
        }
    }

    /**
     * Attach the whole-process lifecycle so the `list-sessions` poll is
     * gated on foreground state (issue #1164). Called once from the host
     * landing screen's composition. Seeds [processStarted] from the
     * owner's current state so a screen that mounts while already visible
     * doesn't have to wait for the next `ON_START`, then flips it on every
     * subsequent `ON_START` / `ON_STOP`.
     *
     * Idempotent: a second call with the same owner is a no-op; a
     * different owner detaches the previous one first so tests can
     * re-attach to a fresh `LifecycleRegistry`. Mirrors
     * [com.pocketshell.app.usage.UsageScheduler.observeProcessLifecycle]
     * and [com.pocketshell.app.portfwd.PortForwardPanelViewModel.observeProcessLifecycle].
     */
    fun observeProcessLifecycle(owner: LifecycleOwner = ProcessLifecycleOwner.get()) {
        val lifecycle = owner.lifecycle
        if (attachedLifecycle === lifecycle) return
        attachedLifecycle?.removeObserver(lifecycleObserver)
        attachedLifecycle = lifecycle
        // `Lifecycle.addObserver` + reading `currentState` require the
        // main thread; hop there explicitly so a call from any dispatcher
        // is safe. The observer then runs on whatever thread the lifecycle
        // dispatches on (always Main in production).
        viewModelScope.launch {
            withContext(Dispatchers.Main) {
                _processStarted.value =
                    lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                lifecycle.addObserver(lifecycleObserver)
            }
        }
    }

    /**
     * Visible-for-testing seam: flip the foreground flag without a real
     * lifecycle owner. Production wiring uses [observeProcessLifecycle].
     */
    internal fun setProcessStartedForTest(started: Boolean) {
        _processStarted.value = started
    }

    override fun onCleared() {
        attachedLifecycle?.removeObserver(lifecycleObserver)
        attachedLifecycle = null
        stopPolling()
        super.onCleared()
    }

    internal fun stopForTest() {
        stopPolling()
    }

    internal fun cancelPollersForTest() {
        for ((_, job) in pollerJobs) {
            job.cancel()
        }
        pollerJobs.clear()
        pollerEntries.clear()
    }

    private fun stopPolling() {
        registryJob?.cancel()
        registryJob = null
        for ((_, job) in pollerJobs) {
            job.cancel()
        }
        pollerJobs.clear()
        pollerEntries.clear()
        perHostSnapshots.clear()
        _sessions.value = emptyList()
    }

    /**
     * Look up the registered entry for [hostId] — used by
     * [com.pocketshell.app.hosts.HostListScreen] at tap time so the
     * navigation can pull the resolved SSH parameters (key path, host
     * name, etc.) without a fresh DB read. Returns `null` if the host
     * unregistered between rendering the row and the user tapping it
     * (e.g. the SSH transport dropped).
     */
    fun entryFor(hostId: Long): ActiveTmuxClients.Entry? =
        activeClients.clients.value[hostId]

    /**
     * Create a new tmux session on [entry]'s host and refresh the
     * dashboard list once tmux acknowledges the creation — issue #204.
     *
     * ## Wire shape
     *
     * Runs `new-session -d -s <name> -c <startDirectory>`. The `-d` flag
     * keeps the new session detached so the create flow doesn't
     * implicitly attach the user's tmux client to the new session — the
     * dashboard list is the source of truth for "the session exists";
     * attaching is a separate, explicit affordance (tap the row).
     *
     * ## Error surfacing
     *
     * Failures (transport drop, tmux `%error` such as duplicate-name
     * rejection) surface as [createError] so the user can tell a real
     * failure apart from "the row didn't show up yet because the next
     * poll is 9.8s away". A successful create runs [refreshEntry]
     * synchronously so the new row appears well within the 2s
     * acceptance budget called out in issue #204 — we don't wait for the
     * 10s poll cycle.
     *
     * We do NOT subscribe to `%sessions-changed` here (unlike
     * [killSession]) because tmux emits the notification BEFORE the new
     * row is queryable in some edge cases — instead we rely on
     * sendCommand's response correlator: when `sendCommand` returns
     * non-error, the session exists on the server, so an immediate
     * list-sessions is guaranteed to see it.
     */
    fun createSession(
        entry: ActiveTmuxClients.Entry,
        name: String,
        startDirectory: String = DEFAULT_TMUX_START_DIRECTORY,
    ) {
        val creation = resolveTmuxSessionCreation(
            rawName = name,
            rawStartDirectory = startDirectory,
        )
        viewModelScope.launch {
            val client = entry.client
            val validator = entry.startDirectoryExists
            if (validator != null) {
                // Issue #296: choose clear-error behavior over auto
                // `mkdir -p`. Creating remote folders is a distinct
                // user intent, and validating before `new-session -c`
                // prevents tmux from silently landing in $HOME.
                val validationResult = runCatching {
                    validator(creation.startDirectory)
                }
                val exists = validationResult.getOrNull()
                if (validationResult.isFailure) {
                    val failure = validationResult.exceptionOrNull()
                    _createError.value = "Couldn't create ${creation.sessionName}: " +
                        "couldn't validate start folder ${creation.startDirectory}: " +
                        (failure?.message ?: failure?.javaClass?.simpleName ?: "unknown error")
                    return@launch
                }
                if (exists != true) {
                    _createError.value = startDirectoryMissingMessage(
                        sessionName = creation.sessionName,
                        startDirectory = creation.startDirectory,
                    )
                    return@launch
                }
            }
            val sendResult = runCatching {
                client.sendCommand(
                    "new-session -d -s '${escapeSingleQuoted(creation.sessionName)}' " +
                        "-c '${escapeSingleQuoted(creation.startDirectory)}'",
                )
            }
            val response = sendResult.getOrNull()
            val transportFailure = sendResult.exceptionOrNull()
            if (transportFailure != null) {
                _createError.value = "Couldn't create ${creation.sessionName}: " +
                    (transportFailure.message ?: "transport error")
                return@launch
            }
            if (response != null && response.isError) {
                val detail = response.output.joinToString(separator = " ").trim()
                _createError.value =
                    "Couldn't create ${creation.sessionName}" +
                        if (detail.isNotEmpty()) ": $detail" else ""
                return@launch
            }
            refreshEntry(entry)
        }
    }

    /**
     * Clear the create-error banner. Wired to the screen's banner
     * dismiss action — issue #204.
     */
    fun clearCreateError() {
        _createError.value = null
    }

    fun renameSession(entry: ActiveTmuxClients.Entry, oldName: String, newName: String) {
        val oldTrimmed = oldName.trim()
        val newTrimmed = newName.trim()
        if (oldTrimmed.isEmpty() || newTrimmed.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                entry.client.sendCommand(
                    "rename-session -t '${escapeSingleQuoted(oldTrimmed)}' " +
                        "'${escapeSingleQuoted(newTrimmed)}'",
                )
            }
            refreshEntry(entry)
        }
    }

    /**
     * Kill the tmux session named [name] on [entry]'s client and refresh
     * the dashboard list once tmux acknowledges the death.
     *
     * ## Why this looks different from [createSession] / [renameSession]
     *
     * Per issue #168, the original implementation wrapped `sendCommand`
     * in a plain [runCatching] and unconditionally refreshed afterwards.
     * That had two visible failure modes for the user:
     *
     *  1. **Silent failures.** A transport error (closed client, dropped
     *     channel) was swallowed by [runCatching] — `refreshEntry` then
     *     issued a list-sessions against the still-alive server, the row
     *     re-appeared, and the user concluded "kill is broken".
     *  2. **Refresh race.** Even on a successful kill, the immediate
     *     `refreshEntry` could land on the wire BEFORE tmux finished the
     *     session cleanup. The list-sessions response then still
     *     included the row that was about to disappear.
     *
     * The fix is twofold:
     *
     *  - If the kill command throws, OR tmux responds with `%error`,
     *    surface a user-visible [killError] and SKIP the refresh: a
     *    refresh after a failed kill would just re-render the still-alive
     *    row and look like the bug from #168.
     *  - On a successful kill, wait for tmux's deterministic
     *    [ControlEvent.SessionsChanged] notification (`%sessions-changed`
     *    on the wire) before refreshing. tmux emits it once the server
     *    has finished tearing the session down, so the subsequent
     *    list-sessions is guaranteed to see the post-death state.
     *  - As a last-resort fallback (e.g. tmux client lost the event
     *    while reconnecting), the refresh still runs after a 2s
     *    timeout. This preserves the old "best effort" behaviour for
     *    pathological cases without making it the default.
     *
     * Debug logging (`hostId` + `client.hashCode()`) covers the third
     * suspected failure mode from #168 — "wrong client" — so a future
     * reviewer can grep logcat and confirm the same client instance
     * issued the kill and the subsequent refresh.
     */
    fun killSession(entry: ActiveTmuxClients.Entry, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val client = entry.client
            val clientHash = System.identityHashCode(client)
            Log.i(
                ISSUE_168_DIAG_TAG,
                "kill-session-start host=${entry.hostId} name=$trimmed clientHash=$clientHash",
            )
            // Subscribe to the deterministic post-kill notification BEFORE
            // sending the command so we don't miss the event in the race
            // window between sendCommand returning and our collector
            // installing. `events` is a hot SharedFlow — late subscription
            // would drop the notification we care about.
            //
            // We use `withTimeoutOrNull` rather than blocking forever to
            // keep a degenerate tmux (e.g. server stuck before emitting
            // the event) from leaving the dashboard out of sync
            // indefinitely. 2s is generous compared to tmux's typical
            // sub-100ms session-cleanup latency on Docker / localhost.
            // `start = UNDISPATCHED` guarantees the inner coroutine runs
            // up to its first suspension point (the `events.first { … }`
            // collector install) before this launch call returns. Without
            // it the listener would only attach after the parent yields,
            // which on a fast tmux can be AFTER the `%sessions-changed`
            // notification has already flown past on the hot SharedFlow.
            val eventDeferred = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
                withTimeoutOrNull(KILL_EVENT_WAIT_MS) {
                    client.events.first { event ->
                        event is ControlEvent.SessionsChanged ||
                            (event is ControlEvent.SessionChanged && event.name == trimmed)
                    }
                }
                Unit
            }

            val sendResult = runCatching {
                client.sendCommand("kill-session -t '${escapeSingleQuoted(trimmed)}'")
            }
            val response = sendResult.getOrNull()
            val transportFailure = sendResult.exceptionOrNull()
            if (transportFailure != null) {
                eventDeferred.cancel()
                Log.w(
                    ISSUE_168_DIAG_TAG,
                    "kill-session-transport-failed host=${entry.hostId} name=$trimmed " +
                        "clientHash=$clientHash err=${transportFailure.javaClass.simpleName}: " +
                        transportFailure.message,
                )
                _killError.value = "Failed to kill $trimmed: ${transportFailure.message ?: "transport error"}"
                return@launch
            }
            if (response != null && response.isError) {
                eventDeferred.cancel()
                val detail = response.output.joinToString(separator = " ").trim()
                Log.w(
                    ISSUE_168_DIAG_TAG,
                    "kill-session-tmux-error host=${entry.hostId} name=$trimmed " +
                        "clientHash=$clientHash detail=$detail",
                )
                _killError.value = "Couldn't kill $trimmed${if (detail.isNotEmpty()) ": $detail" else ""}"
                return@launch
            }

            // Wait up to KILL_EVENT_WAIT_MS for the SessionsChanged /
            // matching SessionChanged event the tmux server emits on
            // session teardown. join() the launcher coroutine since
            // withTimeoutOrNull returns null on a timeout but the event
            // is what we actually care about; join() also propagates if
            // the scope is cancelled by onCleared.
            eventDeferred.join()
            Log.i(
                ISSUE_168_DIAG_TAG,
                "kill-session-refresh host=${entry.hostId} name=$trimmed clientHash=$clientHash",
            )
            refreshEntry(entry)
        }
    }

    /**
     * Clear the kill-error banner. Wired to the screen's banner dismiss
     * action.
     */
    fun clearKillError() {
        _killError.value = null
    }

    /**
     * Parse one row of
     * `list-sessions -F "#{session_name}::#{session_created}::#{session_activity}::#{session_attached}"`.
     *
     * Dashboard parsing delegates to [HostTmuxSessionListParser] so
     * picker and active-session surfaces share delimiter handling,
     * malformed-row behavior, and tmux fallback-output handling.
     *
     * `session_activity` is a Unix timestamp in **seconds** (a tmux
     * convention since 1.9 — see `format.c`). We keep it as seconds in
     * the [SessionSummary] for parity with the wire format; the UI
     * converts to a relative-time string at render.
     *
     * `session_attached` is the count of attached clients — non-zero
     * means at least one client is currently viewing the session.
     */
    internal fun parseListSessionsRow(
        line: String,
        hostId: Long,
        hostName: String,
    ): SessionSummary? {
        val row = sessionListParser.parseTmuxListSessionsRow(line) ?: return null
        val activity = row.lastActivity ?: return null
        return SessionSummary(
            hostId = hostId,
            hostName = hostName,
            sessionName = row.name,
            lastActivity = activity,
            attached = row.attached,
            // Issue #1237: resolve the raw @ps_agent_state option to a chip
            // state, dropping a resting state that has gone stale relative to
            // session activity (the hook fires only on stop/waiting).
            agentState = resolveSessionAgentState(
                rawState = row.agentStateRaw,
                stateUpdatedAtEpochSec = row.agentStateUpdatedAt,
                sessionActivityEpochSec = activity,
            ),
        )
    }

    /**
     * Visible-for-test seam — drive [fetchSessions] without running the
     * poll loop. Lets tests assert the parsing path independent of the
     * `delay`-driven loop.
     */
    internal suspend fun fetchSessionsForTest(entry: ActiveTmuxClients.Entry): List<SessionSummary> =
        fetchSessions(entry) ?: emptyList()

    internal suspend fun refreshEntryForTest(entry: ActiveTmuxClients.Entry) {
        refreshEntry(entry)
    }

    private suspend fun refreshEntry(entry: ActiveTmuxClients.Entry) {
        val rows = fetchSessions(entry) ?: run {
            markHostSnapshotStale(entry.hostId)
            return
        }
        perHostSnapshots[entry.hostId] = rows
        emitAggregate()
    }

    private fun markHostSnapshotStale(hostId: Long) {
        val existing = perHostSnapshots[hostId] ?: return
        perHostSnapshots[hostId] = existing.map { row ->
            // Issue #1237: a stale snapshot must not look current — drop the
            // agent-state chip to Unknown too, not just the attached hint.
            row.copy(attached = false, stale = true, agentState = SessionAgentState.Unknown)
        }
        emitAggregate()
    }

    /**
     * Visible-for-test seam — synthesise a per-host snapshot directly
     * (skipping the tmux round-trip) and force an aggregate emission.
     * Lets aggregation / sort tests run without spinning up the poller.
     */
    internal fun applyHostSnapshotForTest(hostId: Long, summaries: List<SessionSummary>) {
        perHostSnapshots[hostId] = summaries
        emitAggregate()
    }

    private companion object {
        /**
         * Default polling cadence — every 10 seconds. Picked to match
         * the freshness implied by the mockup's `2m / 8m / 14m`
         * relative timestamps; tighter polling would burn host CPU and
         * mobile network without a perceived benefit.
         */
        const val DEFAULT_POLL_INTERVAL_MS: Long = 10_000L

        /**
         * `-F "..."` rather than newline-delimited multi-field output:
         * the format string keeps the parser scoped to a single line
         * per session and matches the issue body's contract verbatim.
         */
        const val LIST_SESSIONS_COMMAND: String =
            "list-sessions -F '#{session_name}::#{session_created}::#{session_activity}::" +
                "#{session_attached}::#{@ps_agent_state}::#{@ps_agent_state_updated_at}'"

        /**
         * Max time we wait for tmux to emit the post-kill
         * `%sessions-changed` notification before falling back to an
         * unconditional refresh. 2s is the cap called out in issue
         * #168's acceptance criteria — tmux's actual cleanup latency on
         * a healthy localhost/Docker server is sub-100ms.
         */
        const val KILL_EVENT_WAIT_MS: Long = 2_000L

        /** Logcat tag for issue #168 kill diagnostics. */
        private const val ISSUE_168_DIAG_TAG: String = "issue168-kill"
    }
}

private fun escapeSingleQuoted(input: String): String =
    input.replace("'", "'\\''")
