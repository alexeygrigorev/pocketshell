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
import kotlinx.coroutines.delay
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
 *  - **Part 3 / issues #1202 + #1198 (hard-cut, D22)**: while a port-forward is active
 *    ([setPortForwardActive]) the session FGS is SUPPRESSED. The
 *    [com.pocketshell.app.portfwd.service.ForwardingService] FGS is the SINGLE owner of the
 *    port-forward notification (its Stop actually tears down the tunnels); running the session
 *    FGS in parallel posted a second notification whose Stop only ended the session hold and
 *    left the tunnels running (the #1202 bug). The ForwardingService FGS already keeps the
 *    process — and the pinned connection — alive, so nothing is lost by not holding here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class SessionServiceController @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val activeTmuxClients: ActiveTmuxClients,
) {
    @VisibleForTesting
    internal var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Issue #1440: wall clock used to compute the scheduled grace-expiry re-post delay. Injected
     * in tests so a virtual-time [kotlinx.coroutines.test.TestCoroutineScheduler] and the delay
     * math agree (a real [System.currentTimeMillis] vs a virtual `delay` would never line up).
     */
    @VisibleForTesting
    internal var nowMillis: () -> Long = { System.currentTimeMillis() }

    /**
     * Issue #1595 (round 2): debounce between the "app is going to the background" hint
     * ([onAppPausing]) and actually starting the session FGS. The activity observer only fires
     * that hint once EVERY activity is STOPPED (a genuine background — a mere overlay that keeps
     * the activity STARTED, e.g. a permission dialog / share sheet / notification shade, never
     * reaches here). A transient stop→start WITHIN this window — a recents peek, a quick
     * app-switch that returns — is cancelled by [onAppResumed] and NEVER starts the FGS, so no
     * Stop-able "Session connected" notification flashes into the tray on routine focus loss (the
     * accidental-Stop footgun the #1159 comment above designs against). Kept well UNDER the
     * ~700ms ProcessLifecycleOwner `ON_STOP` so a genuine background still starts the FGS while
     * it is still FOREGROUND-ELIGIBLE — before Android 12+'s background-FGS-start restriction
     * (`ForegroundServiceStartNotAllowedException`) applies.
     */
    @VisibleForTesting
    internal var pauseStartDebounceMillis: Long = PAUSE_START_DEBOUNCE_MILLIS

    private val _snapshot = MutableStateFlow(SessionConnectionSnapshot.Empty)
    private var observeJob: Job? = null
    private var deadlineFlipJob: Job? = null
    private var pauseDebounceJob: Job? = null
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

    // Issue #1440: flips true when the scheduled deadline fires while the session is still held —
    // the bounded grace window has elapsed but a live client is still registered (reconnecting /
    // hung), so the notification must re-post as RECONNECTING (no count-down) instead of freezing
    // on the grace-hold copy with a count-down that drifts past zero into a negative timer.
    private val graceExpired = MutableStateFlow(false)

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
                graceExpired,
            ) { rawSnapshot, foreground, pfActive, deadline, expired ->
                EffectiveInputs(rawSnapshot, foreground, pfActive, deadline, expired)
            }
                .distinctUntilChanged()
                .collect { (rawSnapshot, foreground, pfActive, deadline, expired) ->
                    if (!rawSnapshot.isHoldingConnection) {
                        holdStoppedByUser = false
                    }
                    // Issue #1159 (Part 1): hold the FGS ONLY while backgrounded. In the
                    // foreground the Activity holds the connection — no service, no tray
                    // notification (and no Stop-action footgun).
                    //
                    // Issue #1202 + #1198 (hard-cut, D22): while a port-forward is active the
                    // ForwardingService FGS is the SINGLE owner of the port-forward
                    // notification — its Stop actually tears down the tunnels
                    // ([com.pocketshell.app.portfwd.ForwardingController.stopAllForwarding]).
                    // The session FGS must NOT run in parallel: it would post a SECOND
                    // notification (the #1198 double-notification) whose Stop only ended the
                    // session hold and left the tunnels running (the #1202 reported bug). The
                    // ForwardingService FGS already keeps the process (and the pinned
                    // connection) alive, so suppressing the session hold here loses nothing
                    // while collapsing to exactly one notification with a working Stop.
                    val shouldHold = rawSnapshot.isHoldingConnection && !foreground && !pfActive
                    val snapshot = if (holdStoppedByUser || !shouldHold) {
                        SessionConnectionSnapshot.Empty
                    } else {
                        // pfActive is guaranteed false here (it gates shouldHold above), so the
                        // held session notification always shows the normal bounded-grace
                        // count-down — never the port-forward wording (that is now owned solely
                        // by ForwardingService).
                        //
                        // Issue #1440: [expired] flips true when the scheduled deadline fires while
                        // the session is still held, so the notification re-posts as RECONNECTING
                        // (no count-down) instead of freezing on the grace-hold copy with a
                        // negative-drifting timer.
                        rawSnapshot.copy(
                            disconnectAtWallClockMillis = deadline,
                            reconnecting = expired,
                        )
                    }
                    val wasHolding = _snapshot.value.isHoldingConnection
                    _snapshot.value = snapshot
                    when {
                        !wasHolding && snapshot.isHoldingConnection ->
                            // Issue #1595: pass holdActive so the connection-trail diagnostic
                            // records the hold state alongside the FGS start outcome.
                            SessionConnectionService.start(appContext, holdActive = snapshot.isHoldingConnection)
                        wasHolding && !snapshot.isHoldingConnection ->
                            SessionConnectionService.stop(appContext)
                    }
                }
        }
    }

    /**
     * Issue #1595: the app is going to the BACKGROUND (the activity observer fires this once
     * EVERY activity is STOPPED — a genuine background, not a mere overlay that keeps the
     * activity STARTED). This is the earliest FOREGROUND-ELIGIBLE moment — the last point at
     * which Android 12+ still permits a `startForegroundService()` before the background-FGS-start
     * restriction applies. Starting the session FGS here (rather than only at [onAppBackgrounded],
     * which is driven by the ProcessLifecycleOwner `ON_STOP` that fires ~700ms INTO the
     * background) establishes the network hold while the start is still allowed, BEFORE the OS can
     * suspend the uid's sockets.
     *
     * Root cause (device-log Fable audit on #1562): the FGS was started only from the
     * backgrounded `ON_STOP`, where Android 12+ rejects the start with
     * `ForegroundServiceStartNotAllowedException`; the hold never came up and the OS destroyed
     * the `-CC` transport ~4.4s later, so the foreground return was a full redial instead of a
     * silent reseed. Starting here keeps the transport alive across the grace window.
     *
     * Issue #1595 (round 2 — debounce): the foreground flip (and therefore the FGS start) is
     * DEFERRED by [pauseStartDebounceMillis]. A transient stop→start WITHIN that window — a
     * recents peek, a quick app-switch that returns — is cancelled by [onAppResumed] BEFORE the
     * flip fires, so it NEVER starts the FGS and NO Stop-able notification flashes into the tray
     * on routine focus loss. The delay is well under the ~700ms `ON_STOP`, so a genuine
     * background still flips (and starts the FGS) while foreground-eligible. Overlays that keep
     * the activity STARTED (permission dialog / share sheet / shade) never reach here at all (the
     * observer gates on all-activities-stopped), so they never start the FGS regardless of how
     * long they linger.
     *
     * Only schedules the foreground signal — the grace count-down deadline stays owned by
     * [onAppBackgrounded] (`ON_STOP`). If no live session is held the FGS start is a no-op (the
     * flow gates on `isHoldingConnection`). The wiring skips this on a configuration change so a
     * rotation / dark-mode flip does not thrash the FGS (see the activity observer).
     */
    fun onAppPausing() {
        // Already backgrounded (e.g. ON_STOP already flipped it) → nothing to re-arm.
        if (!appForegrounded.value) return
        pauseDebounceJob?.cancel()
        pauseDebounceJob = scope.launch {
            delay(pauseStartDebounceMillis)
            appForegrounded.value = false
        }
    }

    /**
     * Issue #1595: the app RESUMED to the foreground. Mirror of [onAppPausing] — the Activity
     * holds the connection again.
     *
     * Round-2 debounce: CANCELS the pending [onAppPausing] debounce so a transient stop→resume
     * that never persisted past the window never flips the foreground signal — the FGS is never
     * started and no notification is ever posted (no start→stop thrash). If the debounce had
     * already fired (a longer background that then returned), flipping the signal back to `true`
     * stops the FGS. This covers the transient that never reached `ON_STOP`, where no
     * [onAppForegrounded] `ON_START` fires. Does NOT touch the grace deadline — a transient that
     * never reached `ON_STOP` stamped none, and a real return clears it via [onAppForegrounded].
     */
    fun onAppResumed() {
        pauseDebounceJob?.cancel()
        pauseDebounceJob = null
        appForegrounded.value = true
    }

    /**
     * Issue #1159 (Part 1): the app moved to the background. The FGS is (re)evaluated and
     * started if a live session is held. [disconnectAtWallClockMillis] stamps the bounded
     * count-down deadline (issue #1123) rendered by the system chronometer — no app-side
     * per-second wakeups. While a port-forward is active the deadline is ignored (Part 3).
     *
     * Issue #1595: [onAppPausing] normally already flipped the foreground signal false (starting
     * the FGS foreground-eligibly), so the `appForegrounded.value = false` here is idempotent and
     * this call is primarily the grace-deadline stamp. It remains a fallback start trigger too:
     * if the earlier foreground-eligible start was skipped (e.g. a configuration change), the
     * background attempt still runs exactly as before.
     */
    fun onAppBackgrounded(disconnectAtWallClockMillis: Long) {
        // Confirmed background — the pending pause debounce is no longer needed (it either
        // already fired the foreground flip, or was skipped for a config change and this is the
        // fallback start). Cancel it and flip NOW so the start is idempotent / the fallback runs.
        pauseDebounceJob?.cancel()
        pauseDebounceJob = null
        graceDisconnectAtWallClockMillis.value = disconnectAtWallClockMillis
        // Issue #1440: a fresh grace window — clear any prior expiry and (re)schedule the flip.
        graceExpired.value = false
        appForegrounded.value = false
        scheduleDeadlineFlip(disconnectAtWallClockMillis)
    }

    /**
     * Issue #1159 (Part 1): the app returned to the foreground. The Activity now holds the
     * connection, so the FGS + its tray notification are stopped and the count-down cleared.
     */
    fun onAppForegrounded() {
        pauseDebounceJob?.cancel()
        pauseDebounceJob = null
        cancelDeadlineFlip()
        graceDisconnectAtWallClockMillis.value = null
        appForegrounded.value = true
    }

    /**
     * Issue #1440: schedule the re-post at the grace deadline. The system count-down chronometer
     * is fire-and-forget (issue #1123 posts it once), so nothing re-posts when the deadline
     * passes — the timer drifts past zero into a negative value and the copy stays frozen. This
     * arms a wakeup at the deadline that flips [graceExpired] true, forcing a fresh emission the
     * FGS re-posts as RECONNECTING (no count-down) at the right moment.
     */
    private fun scheduleDeadlineFlip(disconnectAtWallClockMillis: Long) {
        deadlineFlipJob?.cancel()
        deadlineFlipJob = scope.launch {
            val wait = (disconnectAtWallClockMillis - nowMillis()).coerceAtLeast(0L)
            delay(wait)
            graceExpired.value = true
        }
    }

    private fun cancelDeadlineFlip() {
        deadlineFlipJob?.cancel()
        deadlineFlipJob = null
        graceExpired.value = false
    }

    /**
     * Mark whether a port-forward is currently active.
     *
     * Issue #1202 + #1198 (hard-cut, D22): while a port-forward is active the session FGS is
     * SUPPRESSED — the [com.pocketshell.app.portfwd.service.ForwardingService] FGS is the
     * SINGLE owner of the port-forward notification, and its Stop actually tears down the
     * tunnels. Running the session FGS in parallel posted a second "Port forwarding active"
     * notification whose Stop only ended the session hold and left the tunnels running (the
     * reported bug). When the last forward drops back to false the normal bounded-grace
     * count-down hold applies again while still backgrounded.
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
        cancelDeadlineFlip()
        graceDisconnectAtWallClockMillis.value = null
        _snapshot.value = SessionConnectionSnapshot.Empty
        if (requestServiceStop) {
            SessionConnectionService.stop(appContext)
        }
    }

    private data class EffectiveInputs(
        val rawSnapshot: SessionConnectionSnapshot,
        val foreground: Boolean,
        val portForwardActive: Boolean,
        val disconnectDeadline: Long?,
        val graceExpired: Boolean,
    )

    companion object {
        /**
         * Issue #1595 (round 2): default debounce between the all-activities-stopped background
         * hint ([onAppPausing]) and starting the session FGS. 400ms is well UNDER the ~700ms
         * ProcessLifecycleOwner `ON_STOP` (so a genuine background still starts the FGS while
         * foreground-eligible) yet long enough to absorb a quick stop→resume — a recents peek /
         * app-switch that returns — so it never flashes a Stop-able notification into the tray.
         */
        const val PAUSE_START_DEBOUNCE_MILLIS: Long = 400L
    }
}
