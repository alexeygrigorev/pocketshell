package com.pocketshell.app.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.core.tmux.TmuxClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the dashboard's "Sessions" section above the host list — the
 * issue #46 implementation per `docs/mockups/dashboard.html`.
 *
 * ## Aggregation
 *
 * Observes [ActiveTmuxClients.clients]: whenever a host registers a
 * live `tmux -CC` client, the dashboard spawns a per-host coroutine
 * that periodically issues
 * `list-sessions -F "#{session_name}::#{session_activity}::#{session_attached}"`
 * (the `::` separator chosen because session names can contain `\t`
 * and ` ` but cannot contain `:` — see `man tmux`'s NAMES, WINDOWS, AND
 * PANES section). The parsed rows are folded into [sessions], a
 * flat list across every host, sorted by [SessionSummary.lastActivity]
 * descending so the freshest activity floats to the top of the screen.
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
 * The view model is `@HiltViewModel` so its lifetime tracks the
 * dashboard host screen. The registry it observes is a singleton so it
 * survives navigation away and back. Per-host poller [Job]s are
 * cancelled and restarted as the registry's snapshot changes, and all
 * of them tear down when [onCleared] cancels `viewModelScope`.
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
) : ViewModel() {

    /** Override hook for tests — see [SessionsDashboardViewModelTest]. */
    internal var pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS

    private val _sessions: MutableStateFlow<List<SessionSummary>> =
        MutableStateFlow(emptyList())

    /**
     * Flat, sorted-by-recency-descending list of every tmux session
     * discovered across every registered host. The screen renders one
     * row per entry via [com.pocketshell.uikit.components.SessionRow].
     *
     * Empty when no live clients are registered or when none of the
     * registered hosts reports any sessions. The screen hides the
     * Sessions section entirely in that case — see
     * [com.pocketshell.app.hosts.HostListScreen] for the gate.
     */
    val sessions: StateFlow<List<SessionSummary>> = _sessions.asStateFlow()

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
     * Failures (transport drop, tmux error) leave the existing per-host
     * snapshot intact rather than blanking the row — the UI prefers a
     * slightly-stale row to a flicker. The next successful poll
     * overwrites the stale entry.
     */
    private suspend fun pollLoop(entry: ActiveTmuxClients.Entry) {
        // Immediate first poll so the section populates as fast as
        // possible after register; subsequent polls back off to the
        // configured cadence.
        while (currentCoroutineContext().isActive) {
            val rows = runCatching { fetchSessions(entry) }.getOrNull()
            if (rows != null) {
                perHostSnapshots[entry.hostId] = rows
                emitAggregate()
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
    }

    override fun onCleared() {
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

    fun createSession(entry: ActiveTmuxClients.Entry, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                entry.client.sendCommand("new-session -d -s '${escapeSingleQuoted(trimmed)}'")
            }
            refreshEntry(entry)
        }
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

    fun killSession(entry: ActiveTmuxClients.Entry, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                entry.client.sendCommand("kill-session -t '${escapeSingleQuoted(trimmed)}'")
            }
            refreshEntry(entry)
        }
    }

    /**
     * Parse one row of
     * `list-sessions -F "#{session_name}::#{session_activity}::#{session_attached}"`.
     *
     * `tmux` emits these tokens with no quoting — session names cannot
     * contain `:` (the colon is reserved in tmux's target-pane syntax),
     * so splitting on `::` is unambiguous. We split into at most three
     * pieces so a hypothetical future tmux that adds a stray `::` in
     * the name field still surfaces as a parseable row with a slightly
     * truncated name.
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
        if (line.isBlank()) return null
        val parts = line.split(SESSION_FIELD_SEP, limit = 3)
        if (parts.size < 3) return null
        val name = parts[0]
        if (name.isEmpty()) return null
        val activity = parts[1].trim().toLongOrNull() ?: return null
        val attached = (parts[2].trim().toLongOrNull() ?: 0L) > 0L
        return SessionSummary(
            hostId = hostId,
            hostName = hostName,
            sessionName = name,
            lastActivity = activity,
            attached = attached,
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
        val rows = fetchSessions(entry) ?: return
        perHostSnapshots[entry.hostId] = rows
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

        /** `::` is unambiguous because tmux session names cannot contain a colon. */
        const val SESSION_FIELD_SEP: String = "::"

        /**
         * `-F "..."` rather than newline-delimited multi-field output:
         * the format string keeps the parser scoped to a single line
         * per session and matches the issue body's contract verbatim.
         */
        const val LIST_SESSIONS_COMMAND: String =
            "list-sessions -F '#{session_name}::#{session_activity}::#{session_attached}'"
    }
}

private fun escapeSingleQuoted(input: String): String =
    input.replace("'", "'\\''")
