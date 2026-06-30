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
 * foreground-service start/stop intents: the service runs while a live tmux client is
 * attached so the OS keeps the connection alive through Doze during the background grace
 * window, and stops as soon as the last live client unregisters.
 *
 * Issue #1123 (bounded-grace D21 update): the hold is BOUNDED, not indefinite. Once the
 * App-level grace window elapses the terminal teardown unregisters the live client, which
 * drops the snapshot to empty and stops this service — so no wake-lock or hold persists in
 * the background beyond the grace window. The old indefinite-hold
 * `isHoldingSessionConnection` preserve gate and the notification-Stop-after-grace
 * teardown plumbing are removed.
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

    // Issue #1123: the wall-clock disconnect deadline shown as the bounded notification's
    // live count-down while backgrounded. Set on background grace start, cleared on
    // foreground. Merged into every emitted snapshot so a client-liveness update never
    // drops the count-down mid-window.
    private var graceDisconnectAtWallClockMillis: Long? = null

    fun flowOfSnapshot(): StateFlow<SessionConnectionSnapshot> = _snapshot.asStateFlow()

    fun observeActiveSessions() {
        if (observeJob?.isActive == true) return
        observeJob = scope.launch {
            activeTmuxClients.clients
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
                .distinctUntilChanged()
                .collect { rawSnapshot ->
                    if (!rawSnapshot.isHoldingConnection) {
                        holdStoppedByUser = false
                    }
                    val snapshot = if (holdStoppedByUser) {
                        SessionConnectionSnapshot.Empty
                    } else {
                        rawSnapshot.withCurrentDeadline()
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
     * Issue #1123: the app backgrounded and the (5-min) grace window started. Stamp the
     * wall-clock disconnect deadline so the bounded foreground-service notification renders
     * a live count-down to teardown. The system chronometer (`setChronometerCountDown`)
     * does the per-second rendering — no app-side per-second wakeups.
     */
    fun onBackgroundGraceStarted(disconnectAtWallClockMillis: Long) {
        graceDisconnectAtWallClockMillis = disconnectAtWallClockMillis
        val current = _snapshot.value
        if (current.isHoldingConnection) {
            _snapshot.value = current.copy(disconnectAtWallClockMillis = disconnectAtWallClockMillis)
        }
    }

    /**
     * Issue #1123: the app returned to the foreground (within grace) — clear the count-down
     * so the notification goes back to its steady "connected" state. (Beyond grace the
     * teardown stops the service entirely, so this is the within-grace return path.)
     */
    fun onForegroundResumed() {
        graceDisconnectAtWallClockMillis = null
        val current = _snapshot.value
        if (current.disconnectAtWallClockMillis != null) {
            _snapshot.value = current.copy(disconnectAtWallClockMillis = null)
        }
    }

    private fun SessionConnectionSnapshot.withCurrentDeadline(): SessionConnectionSnapshot =
        if (isHoldingConnection && graceDisconnectAtWallClockMillis != null) {
            copy(disconnectAtWallClockMillis = graceDisconnectAtWallClockMillis)
        } else {
            this
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
        graceDisconnectAtWallClockMillis = null
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
        graceDisconnectAtWallClockMillis = null
        _snapshot.value = SessionConnectionSnapshot.Empty
    }
}
