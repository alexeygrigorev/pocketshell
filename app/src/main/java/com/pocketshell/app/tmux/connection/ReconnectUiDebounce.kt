package com.pocketshell.app.tmux.connection

import com.pocketshell.app.tmux.TmuxSessionViewModel.ConnectionStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/**
 * Issue #876 — suppress the sub-1s reconnect flash.
 *
 * A momentary connection drop that self-recovers fast should NOT flash the
 * "Reconnecting" UI. This is a PURE presentation-layer debounce on the OBSERVED
 * [ConnectionStatus] the view collects — it does NOT touch when reconnects fire,
 * how leases are acquired, or the grace window (those live in the frozen
 * reconnect/lease/grace core, D28 / epic #792). We only change WHEN the UI
 * *shows* the indicator: a [ConnectionStatus.Reconnecting] is held back for
 * [RECONNECT_UI_DEBOUNCE_MS]; if the status returns to a steady state within
 * that window, the reconnecting band is never surfaced.
 *
 * Behaviour:
 *  - Steady states (Idle, Connecting, Switching, Connected, Failed) surface
 *    IMMEDIATELY — a genuine cold connect / switch / terminal failure is never
 *    delayed, and an arriving steady status cancels any pending (held)
 *    Reconnecting.
 *  - A [ConnectionStatus.Reconnecting] is the only debounced state (it is the
 *    sole "drop / silent recovery" surface, per [ConnectionStatusProjection]).
 *    The FIRST Reconnecting after a steady state starts a single
 *    [RECONNECT_UI_DEBOUNCE_MS] timer; the view keeps showing the previous
 *    surfaced status meanwhile:
 *      - if a NON-Reconnecting status arrives before the timer fires, the timer
 *        is cancelled and the pending Reconnecting is dropped — a sub-1s blip
 *        shows NO reconnect UI.
 *      - if the timer fires while still Reconnecting, the (latest) Reconnecting
 *        payload is surfaced — a sustained drop shows the band exactly as today.
 *    Subsequent Reconnecting payload updates that arrive WHILE the band is still
 *    pending do NOT restart the timer (the clock runs from the FIRST drop), and
 *    once the band IS surfaced they re-emit immediately (no second debounce).
 *
 * Deterministic: the only suspension is [delay], which respects `runTest`
 * virtual time, so the debounce is tested with no wall-clock sleeps.
 */

/**
 * How long a connection drop must persist before the "Reconnecting" UI is
 * surfaced. A recovery faster than this never flashes the band. Tunable single
 * constant (issue #876, maintainer direction 2026-06-20: "~1 second").
 */
const val RECONNECT_UI_DEBOUNCE_MS: Long = 1_000L

/**
 * Debounce the surfacing of [ConnectionStatus.Reconnecting] by [debounceMs].
 *
 * @receiver the raw projected status flow (e.g. `_connectionStatus`).
 * @param debounceMs the hold window before a sustained drop surfaces the band.
 */
fun Flow<ConnectionStatus>.debounceReconnectUi(
    debounceMs: Long = RECONNECT_UI_DEBOUNCE_MS,
): Flow<ConnectionStatus> = channelFlow {
    // The last status actually SURFACED to the view (what stays on screen while a
    // Reconnecting is being held back). Seeded to Idle (the StateFlow's initial).
    var surfaced: ConnectionStatus = ConnectionStatus.Idle
    // The in-flight "hold the band back" timer, if a drop is currently pending.
    var pendingJob: Job? = null
    // The latest Reconnecting payload to surface when the hold timer fires.
    var pendingReconnecting: ConnectionStatus.Reconnecting? = null

    collect { incoming ->
        if (incoming is ConnectionStatus.Reconnecting) {
            when {
                // Band already on screen (sustained drop, payload churn): re-emit
                // the new payload immediately — the user is already looking at it.
                surfaced is ConnectionStatus.Reconnecting -> {
                    surfaced = incoming
                    send(incoming)
                }
                // A drop is already pending (timer running): just remember the
                // latest payload to surface WHEN the original timer fires. Do NOT
                // restart the timer — the clock runs from the FIRST drop, so a
                // long sustained drop surfaces after debounceMs of the first blip,
                // not perpetually reset by attempt-counter churn.
                pendingJob?.isActive == true -> {
                    pendingReconnecting = incoming
                }
                // A FRESH drop from a steady state: keep the previous surfaced
                // status on screen and start the single hold timer.
                else -> {
                    pendingReconnecting = incoming
                    pendingJob = launch {
                        delay(debounceMs)
                        val toSurface = pendingReconnecting
                        if (toSurface != null) {
                            surfaced = toSurface
                            send(toSurface)
                        }
                    }
                }
            }
        } else {
            // Any steady (non-Reconnecting) status surfaces immediately and
            // cancels a pending drop, so a sub-window recovery shows no band.
            pendingJob?.cancel()
            pendingJob = null
            pendingReconnecting = null
            surfaced = incoming
            send(incoming)
        }
    }
}.buffer(Channel.UNLIMITED)
