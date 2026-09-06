package com.pocketshell.next.usage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.core.hostapi.Backend
import com.pocketshell.core.usage.UsageProviderRecord
import com.pocketshell.next.connect.ConnectionsRegistry
import com.pocketshell.next.hostcli.HostCliClientFactory
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Backs [UsageRoute] (rewrite task P-5, journey J12).
 *
 * Deliberately small: [UsageFetcher] does the one thing that used to need 564
 * (`UsageScheduler`) + 611 (the old `UsageViewModel`'s lease fan-out) lines —
 * dial nothing, ask every already-connected host once, in parallel — and this
 * class just turns the result into [UsageScreenState] and remembers whether a
 * refresh is in flight.
 *
 * Every visit gets a fresh instance (`hiltViewModel()` is nav-entry scoped),
 * and [UsageRoute] calls [refresh] from `ON_START`, so there is nothing to
 * warm on construction — the class does nothing until asked.
 */
@HiltViewModel
class UsageViewModel @Inject constructor(
    private val fetcher: UsageFetcher,
) : ViewModel() {

    private val _state = MutableStateFlow(UsageScreenState())
    val state: StateFlow<UsageScreenState> = _state.asStateFlow()

    /** Guards against a pull-to-refresh tap re-entering a fetch already in flight. */
    private var inFlight: Job? = null

    fun refresh() {
        if (inFlight?.isActive == true) return
        _state.value = _state.value.copy(isRefreshing = true)
        inFlight = viewModelScope.launch {
            val result = fetcher.fetchAll()
            _state.value = usageScreenState(
                snapshots = result.snapshots.values,
                connectedHostCount = result.connectedHostCount,
                isRefreshing = false,
                loaded = true,
                resetBanner = usageResetBannerState(result.resetEvents),
            )
        }
    }
}

/**
 * Backs the terminal top bar's usage glance pill (rewrite task P-5).
 *
 * A separate, smaller ViewModel rather than sharing [UsageViewModel]: the
 * pill lives on the SESSION screen, not the usage panel, and the two visits
 * are independent — opening a session must not depend on the usage panel
 * ever having been opened first. Like [UsageViewModel] it does its own
 * foreground-only [UsageFetcher] round on `ON_START`; there is no shared
 * cache between the two, matching the plan's "no stale-while-revalidate"
 * call for this whole feature.
 *
 * Issue #2579 gave it a second, OPTIONAL input: on the session screen the
 * caller says which session is open, and the pill then reports that session's
 * own agent instead of the worst provider anywhere. The listing that answers
 * "which agent" is asked for on the connection the screen already holds, in
 * parallel with the usage round, and its failure is not the pill's failure —
 * see [detectFocus].
 */
@HiltViewModel
class UsageGlanceViewModel @Inject constructor(
    private val fetcher: UsageFetcher,
    private val connections: ConnectionsRegistry,
    private val clients: HostCliClientFactory,
) : ViewModel() {

    private val _state = MutableStateFlow<UsageGlancePillState?>(null)
    val state: StateFlow<UsageGlancePillState?> = _state.asStateFlow()

    private var inFlight: Job? = null

    /**
     * One fetch round.
     *
     * With no arguments — the session tree's Usage affordance — the pill keeps
     * its original cross-provider meaning. With both [hostId] and
     * [sessionName] — the session screen — the round ALSO reads that host's
     * session listing to find the open session's aplexer-detected agent, and
     * focuses the pill on it when there is one.
     *
     * The two reads run concurrently: they are independent host round-trips on
     * the same connection, and serialising them would put a second CLI
     * round-trip in front of every session open.
     */
    fun refresh(hostId: Long? = null, sessionName: String? = null) {
        if (inFlight?.isActive == true) return
        inFlight = viewModelScope.launch {
            val (result, focus) = coroutineScope {
                val usage = async { fetcher.fetchAll() }
                val detected = async { detectFocus(hostId, sessionName) }
                usage.await() to detected.await()
            }
            _state.value = usageGlancePillState(
                snapshots = result.snapshots,
                warnPercent = UsageProviderRecord.DEFAULT_WARN_PERCENT,
                focus = focus,
            )
        }
    }

    /**
     * The open session's agent, as the HOST reported it — or null, meaning
     * "no focus, show the ordinary pill".
     *
     * Null on every unremarkable path, deliberately: no session named
     * (the tree), no live connection (D21 — the pill never dials), a tmux row
     * (aplexer is the only manager that can see inside a session), no agent
     * detected, or a host CLI too old to emit the field at all. The client
     * never inspects the session itself; the ONLY source is
     * `pocketshell sessions list --json`.
     *
     * A listing FAILURE is likewise not an error for the pill — it just means
     * no focus. The usage numbers are already in hand at that point, and
     * dropping the whole pill because a second, purely-decorative read failed
     * would be a worse regression than showing the cross-provider number.
     */
    private suspend fun detectFocus(hostId: Long?, sessionName: String?): GlanceFocus? {
        if (hostId == null || sessionName.isNullOrEmpty()) return null
        val connection = connections.current(hostId) ?: return null
        val listing = runCatching { clients.create(connection).listSessions() }
            .getOrNull()
            ?.getOrNull()
            ?: return null
        val row = listing.sessions.firstOrNull { it.name == sessionName } ?: return null
        if (row.backend != Backend.APLEXER) return null
        val agent = row.agent?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return GlanceFocus(hostId = hostId, provider = agent)
    }
}
