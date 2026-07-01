package com.pocketshell.app.sessions.service

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.pocketshell.app.sessions.ActiveTmuxClients
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * App-singleton owner for the BOUNDED live-session foreground-service hold.
 *
 * The tmux client registry remains the source of truth for whether a user has an
 * attached, live terminal session. This controller only translates that signal into
 * foreground-service start/stop intents.
 *
 * Issue #1123 (bounded-grace D21 update): the hold is BOUNDED, not indefinite. Once the
 * App-level grace window elapses the terminal teardown unregisters the live client, which
 * drops the snapshot to empty and stops this service — so no wake-lock or hold persists in
 * the background beyond the grace window.
 *
 * Issue #1159 (Part 1 + Part 3):
 *  - **Part 1**: the FGS now runs ONLY while the app is BACKGROUNDED. In the foreground the
 *    Activity itself holds the SSH/tmux connection, so there is no need for the service —
 *    and no reason to sit a Stop-able "Session connected" notification in the tray where an
 *    accidental tap would kill the live connection. App lifecycle drives
 *    [onAppForegrounded]/[onAppBackgrounded]; the service only starts on background and
 *    stops on foreground. Starting/stopping the service never touches the connection itself
 *    (the service owns only Android process-survival mechanics).
 *  - **Part 3**: while a port-forward is active ([setPortForwardActive]) the notification
 *    reads "Port forwarding active" and shows NO count-down — the connection is pinned
 *    always-on (the App-level grace teardown is suppressed), so there is no disconnect
 *    deadline to count down to.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class SessionServiceController @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val activeTmuxClients: ActiveTmuxClients,
) {
    @VisibleForTesting
    internal var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _snapshot = MutableStateFlow(SessionConnectionSnapshot.Empty)
    private var observeJob: Job? = null
    private var holdStoppedByUser: Boolean = false

    /**
     * Issue #1159 (Part 1): whether the app is currently foregrounded. The FGS runs ONLY
     * when this is false. Defaults to `true` (a cold start opens into a foreground Activity),
     * so a session that connects in the foreground does NOT start the service until the app
     * is backgrounded.
     */
    private val appForegrounded = MutableStateFlow(true)

    /** Issue #1159 (Part 3): whether a port-forward is active (pins the notification wording). */
    private val portForwardActive = MutableStateFlow(false)

    // Issue #1123: the wall-clock disconnect deadline shown as the bounded notification's
    // live count-down while backgrounded. Set on background, cleared on foreground. Merged
    // into every emitted snapshot so a client-liveness update never drops the count-down
    // mid-window. Null while a port-forward pins the connection (#1159 Part 3 — no teardown).
    private val graceDisconnectAtWallClockMillis = MutableStateFlow<Long?>(null)

    fun flowOfSnapshot(): StateFlow<SessionConnectionSnapshot> = _snapshot.asStateFlow()

    fun observeActiveSessions() {
        if (observeJob?.isActive == true) return
        val rawSnapshots = activeTmuxClients.clients
            .flatMapLatest { clients ->
                val entries = clients.values.toList()
                if (entries.isEmpty()) {
                    flowOf(SessionConnectionSnapshot.Empty)
                } else {
                    combine(
                        entries.map { entry ->
                            entry.client.disconnected.map { disconnected -> entry to disconnected }
                        },
                    ) { states ->
                        val liveEntries = states
                            .filter { (_, disconnected) -> !disconnected }
                            .map { (entry, _) -> entry }
                        SessionConnectionSnapshot.fromEntries(liveEntries)
                    }
                }
            }
        observeJob = scope.launch {
            combine(
                rawSnapshots,
                appForegrounded,
                portForwardActive,
                graceDisconnectAtWallClockMillis,
            ) { rawSnapshot, foreground, pfActive, deadline ->
                EffectiveInputs(rawSnapshot, foreground, pfActive, deadline)
            }
                .distinctUntilChanged()
                .collect { (rawSnapshot, foreground, pfActive, deadline) ->
                    if (!rawSnapshot.isHoldingConnection) {
                        holdStoppedByUser = false
                    }
                    // Issue #1159 (Part 1): hold the FGS ONLY while backgrounded. In the
                    // foreground the Activity holds the connection — no service, no tray
                    // notification (and no Stop-action footgun).
                    val shouldHold = rawSnapshot.isHoldingConnection && !foreground
                    val snapshot = if (holdStoppedByUser || !shouldHold) {
                        SessionConnectionSnapshot.Empty
                    } else {
                        rawSnapshot.copy(
                            portForwardActive = pfActive,
                            // Part 3: a port-forward pins the connection always-on (no
                            // teardown) → no count-down deadline. Otherwise the bounded
                            // grace count-down.
                            disconnectAtWallClockMillis = if (pfActive) null else deadline,
                        )
                    }
                    val wasHolding = _snapshot.value.isHoldingConnection
                    _snapshot.value = snapshot
                    when {
                        !wasHolding && snapshot.isHoldingConnection ->
                            SessionConnectionService.start(appContext)
                        wasHolding && !snapshot.isHoldingConnection ->
                            SessionConnectionService.stop(appContext)
                    }
                }
        }
    }

    /**
     * Issue #1159 (Part 1): the app moved to the background. The FGS is (re)evaluated and
     * started if a live session is held. [disconnectAtWallClockMillis] stamps the bounded
     * count-down deadline (issue #1123) rendered by the system chronometer — no app-side
     * per-second wakeups. While a port-forward is active the deadline is ignored (Part 3).
     */
    fun onAppBackgrounded(disconnectAtWallClockMillis: Long) {
        graceDisconnectAtWallClockMillis.value = disconnectAtWallClockMillis
        appForegrounded.value = false
    }

    /**
     * Issue #1159 (Part 1): the app returned to the foreground. The Activity now holds the
     * connection, so the FGS + its tray notification are stopped and the count-down cleared.
     */
    fun onAppForegrounded() {
        graceDisconnectAtWallClockMillis.value = null
        appForegrounded.value = true
    }

    /**
     * Issue #1159 (Part 3): mark whether a port-forward is currently active. When true the
     * notification reads "Port forwarding active" with no count-down (the connection is
     * pinned always-on); when it drops back to false the normal bounded-grace count-down
     * applies again on the next background.
     */
    fun setPortForwardActive(active: Boolean) {
        portForwardActive.value = active
    }

    fun currentSnapshot(): SessionConnectionSnapshot =
        SessionConnectionSnapshot.fromEntries(activeTmuxClients.clients.value.values)

    /**
     * The notification "Stop" action: end the bounded background hold early. Stops the
     * foreground service + wake-lock now (the user explicitly asked to free resources);
     * the live tmux connection itself is left to the background grace teardown.
     */
    fun stopHoldingFromNotification(requestServiceStop: Boolean = true) {
        if (holdStoppedByUser) return
        if (!currentSnapshot().isHoldingConnection) return
        holdStoppedByUser = true
        graceDisconnectAtWallClockMillis.value = null
        _snapshot.value = SessionConnectionSnapshot.Empty
        if (requestServiceStop) {
            SessionConnectionService.stop(appContext)
        }
    }

    @VisibleForTesting
    internal fun stopObservingForTest() {
        observeJob?.cancel()
        observeJob = null
        holdStoppedByUser = false
        graceDisconnectAtWallClockMillis.value = null
        appForegrounded.value = true
        portForwardActive.value = false
        _snapshot.value = SessionConnectionSnapshot.Empty
    }

    private data class EffectiveInputs(
        val rawSnapshot: SessionConnectionSnapshot,
        val foreground: Boolean,
        val portForwardActive: Boolean,
        val disconnectDeadline: Long?,
    )
}
