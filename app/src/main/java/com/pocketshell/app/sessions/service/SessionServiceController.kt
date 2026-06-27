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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * App-singleton owner for the live-session foreground-service hold.
 *
 * The tmux client registry remains the source of truth for whether a user has
 * an attached, live terminal session. This controller only translates that
 * signal into foreground-service start/stop intents and exposes the current
 * hold state to the process lifecycle grace path.
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
    private val _notificationStopRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    private val _sessionHoldEndedRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    private var observeJob: Job? = null
    private var holdStoppedByUser: Boolean = false
    private var foregroundServicePromoted: Boolean = false
    private var foregroundServiceStartRejected: Boolean = false

    fun flowOfSnapshot(): StateFlow<SessionConnectionSnapshot> = _snapshot.asStateFlow()

    fun notificationStopRequests(): SharedFlow<Unit> = _notificationStopRequests.asSharedFlow()

    fun sessionHoldEndedRequests(): SharedFlow<Unit> = _sessionHoldEndedRequests.asSharedFlow()

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
                        rawSnapshot
                    }
                    val wasHolding = _snapshot.value.isHoldingConnection
                    _snapshot.value = snapshot
                    when {
                        !wasHolding && snapshot.isHoldingConnection -> {
                            foregroundServiceStartRejected = !SessionConnectionService.start(appContext)
                            if (foregroundServiceStartRejected) {
                                foregroundServicePromoted = false
                            }
                        }
                        wasHolding && !snapshot.isHoldingConnection -> {
                            _sessionHoldEndedRequests.tryEmit(Unit)
                            foregroundServicePromoted = false
                            foregroundServiceStartRejected = false
                            SessionConnectionService.stop(appContext)
                        }
                    }
                }
        }
    }

    fun currentSnapshot(): SessionConnectionSnapshot =
        SessionConnectionSnapshot.fromEntries(activeTmuxClients.clients.value.values)

    fun isHoldingSessionConnection(): Boolean =
        !holdStoppedByUser &&
            !foregroundServiceStartRejected &&
            foregroundServicePromoted &&
            currentSnapshot().isHoldingConnection

    fun stopHoldingFromNotification(requestServiceStop: Boolean = true) {
        if (holdStoppedByUser) return
        if (!currentSnapshot().isHoldingConnection) return
        holdStoppedByUser = true
        foregroundServicePromoted = false
        foregroundServiceStartRejected = false
        _snapshot.value = SessionConnectionSnapshot.Empty
        _notificationStopRequests.tryEmit(Unit)
        if (requestServiceStop) {
            SessionConnectionService.stop(appContext)
        }
    }

    fun onForegroundServicePromoted() {
        foregroundServiceStartRejected = false
        foregroundServicePromoted = true
    }

    fun onForegroundServiceStartFailed() {
        foregroundServiceStartRejected = true
        foregroundServicePromoted = false
    }

    fun onForegroundServiceStopped() {
        foregroundServicePromoted = false
    }

    @VisibleForTesting
    internal fun stopObservingForTest() {
        observeJob?.cancel()
        observeJob = null
        holdStoppedByUser = false
        foregroundServicePromoted = false
        foregroundServiceStartRejected = false
        _snapshot.value = SessionConnectionSnapshot.Empty
    }
}
