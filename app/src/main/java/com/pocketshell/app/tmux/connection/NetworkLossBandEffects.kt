package com.pocketshell.app.tmux.connection

import android.util.Log
import com.pocketshell.app.connectivity.TerminalNetworkChange
import com.pocketshell.app.connectivity.networkDiagnosticFields
import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.app.diagnostics.ReconnectCauseTrail
import com.pocketshell.app.tmux.ISSUE_548_NETWORK_TAG
import com.pocketshell.app.tmux.TmuxConnectTrigger
import com.pocketshell.app.tmux.TmuxSessionViewModel.ConnectionTarget
import com.pocketshell.app.tmux.targetLogFields
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Issue #1522 (H1 + amortization): debounce AND back off the bare-network-loss
 * ("reconnecting") band.
 *
 * A single sub-second loss of `NET_CAPABILITY_VALIDATED` — which cellular drops
 * constantly at full signal (RAT handovers, periodic re-validation, v4↔v6 flips)
 * while the TCP / tmux `-CC` channel stays perfectly alive — used to paint the
 * calm "reconnecting" band IMMEDIATELY, then a `NetworkRestored` flipped it back:
 * the maintainer's "it connects, it disconnects, it connects, it disconnects"
 * flap on a stable link, RELOADING the window every ~1s with no amortization.
 *
 * Two levers:
 * - **Grace (debounce).** A loss schedules the band [baseDebounceMs] later, so a
 *   blip that clears within the window (restore → [cancel]) or that the keepalive
 *   can vouch for by fire time NEVER paints. Only a loss that OUTLASTS the grace
 *   AND that the keepalive cannot vouch for surfaces the honest band.
 * - **Backoff (amortization).** Each actual band paint escalates the NEXT grace by
 *   the connection's own auto-reconnect ladder ([backoffLadderMs]), so a
 *   persistently flaky link reloads progressively LESS often instead of every ~1s.
 *   A sustained quiet period (no losses for [quietResetMs] after a restore) resets
 *   the backoff to the base grace.
 *
 * The jobs live on the ViewModel's [scope] so they are virtual-clock driven in
 * tests and cancelled with the scope in production.
 */
internal class NetworkLossBandDebouncer(
    private val scope: CoroutineScope,
    private val baseDebounceMs: Long,
    private val backoffLadderMs: () -> List<Long>,
    private val quietResetMs: Long,
    private val transportKeepAliveProvenAlive: () -> Boolean,
) {
    private var job: Job? = null
    private var quietResetJob: Job? = null

    /**
     * How many times the band has painted in the current flap window. Drives the
     * backoff: 0 → base grace only; N>0 → base + ladder[N-1] (the Nth reload waits
     * longer). Reset to 0 after a [quietResetMs] lull.
     */
    private var reloadCount: Int = 0

    /** The grace the NEXT loss will wait before painting (base + escalating backoff). */
    fun currentGraceMs(): Long {
        if (reloadCount == 0) return baseDebounceMs
        val ladder = backoffLadderMs()
        if (ladder.isEmpty()) return baseDebounceMs
        return baseDebounceMs + ladder[(reloadCount - 1).coerceIn(0, ladder.size - 1)]
    }

    /**
     * Schedule the calm loss band after the current grace. Re-checks the keepalive
     * at fire time: if the transport has proven itself alive by then the blip is
     * suppressed ([onSuppressedByKeepAlive]); otherwise the band is painted
     * ([onPaintBand], passed the grace it waited so the band can surface it) and the
     * backoff escalates. A newer loss cancels and reschedules.
     */
    fun schedule(onSuppressedByKeepAlive: () -> Unit, onPaintBand: (graceMs: Long) -> Unit) {
        job?.cancel()
        quietResetJob?.cancel()
        quietResetJob = null
        val grace = currentGraceMs()
        job = scope.launch {
            delay(grace)
            if (transportKeepAliveProvenAlive()) {
                onSuppressedByKeepAlive()
            } else {
                onPaintBand(grace)
                val ladder = backoffLadderMs()
                if (ladder.isNotEmpty()) reloadCount = (reloadCount + 1).coerceAtMost(ladder.size)
            }
        }
    }

    /**
     * Drop any pending debounced band (a restore returned, or the keepalive proved
     * the socket alive immediately) and arm the quiet-period backoff reset. Returns
     * whether a band was pending — i.e. whether a blip was just swallowed before it
     * could paint.
     */
    fun cancel(): Boolean {
        val wasPending = job?.isActive == true
        job?.cancel()
        job = null
        if (reloadCount > 0) {
            quietResetJob?.cancel()
            quietResetJob = scope.launch {
                delay(quietResetMs)
                reloadCount = 0
            }
        }
        return wasPending
    }
}

/**
 * Issue #997 / #1522: record that a bare network LOSS was observed and the lease
 * is HELD (no teardown). Fires the `network_loss_hold` device signal that the
 * bare-loss journeys assert. [debounced] distinguishes the #1522 deferred-band
 * path (keepalive could not vouch, band scheduled) from the immediate
 * keepalive-suppressed path.
 */
internal fun recordNetworkLossHold(
    change: TerminalNetworkChange,
    target: ConnectionTarget,
    generation: Long,
    debounced: Boolean,
) {
    val reason = change.reason
    Log.i(
        ISSUE_548_NETWORK_TAG,
        "tmux-network-loss-hold reason=$reason cause=bare-network-loss " +
            "debounced=$debounced current=${change.current.logValue} " +
            targetLogFields(target),
    )
    DiagnosticEvents.record(
        "connection",
        "network_loss_hold",
        "source" to "network_observer",
        "trigger" to TmuxConnectTrigger.NetworkReconnect.logValue,
        "reason" to reason,
        "classification" to "bare_network_loss",
        "reconnect" to false,
        "debounced" to debounced,
        "leaseHeld" to true,
        "sequence" to change.sequence,
        "hostId" to target.hostId,
        "host" to target.host,
        "port" to target.port,
        "user" to target.user,
        "session" to target.sessionName,
        "generation" to generation,
        "deferredFromBackground" to change.deferredFromBackground,
        *change.networkDiagnosticFields(),
    )
    ReconnectCauseTrail.record(
        stage = "network_loss_decision",
        outcome = "hold",
        cause = "bare_network_loss",
        trigger = TmuxConnectTrigger.NetworkReconnect.logValue,
        "sequence" to change.sequence,
        "hostId" to target.hostId,
        "generation" to generation,
        "classification" to "bare_network_loss",
        "debounced" to debounced,
        "deferredFromBackground" to change.deferredFromBackground,
    )
}

/**
 * Issue #1522 (H1): the loss band was SUPPRESSED — the transport keepalive
 * vouches for the socket so the blip is not a real transport death. Either fired
 * immediately (keepalive vouched at the loss instant) or at debounce fire time
 * ([cause]). The session stays Live; no band, no churn.
 */
internal fun recordNetworkLossBandSuppressed(
    change: TerminalNetworkChange,
    target: ConnectionTarget,
    generation: Long,
    cause: String,
) {
    val reason = change.reason
    Log.i(
        ISSUE_548_NETWORK_TAG,
        "tmux-network-loss-suppress reason=$reason cause=$cause " +
            "current=${change.current.logValue} " + targetLogFields(target),
    )
    DiagnosticEvents.record(
        "connection",
        "network_loss_band_suppressed",
        "source" to "network_observer",
        "trigger" to TmuxConnectTrigger.NetworkReconnect.logValue,
        "reason" to reason,
        "cause" to cause,
        "classification" to "bare_network_loss_transport_alive",
        "reconnect" to false,
        "leaseHeld" to true,
        "sequence" to change.sequence,
        "hostId" to target.hostId,
        "generation" to generation,
        "deferredFromBackground" to change.deferredFromBackground,
        *change.networkDiagnosticFields(),
    )
    ReconnectCauseTrail.record(
        stage = "network_loss_decision",
        outcome = "suppress",
        cause = cause,
        trigger = TmuxConnectTrigger.NetworkReconnect.logValue,
        "sequence" to change.sequence,
        "hostId" to target.hostId,
        "generation" to generation,
        "classification" to "bare_network_loss_transport_alive",
        "deferredFromBackground" to change.deferredFromBackground,
    )
}

/**
 * Issue #1522: a bare loss OUTLASTED the (amortized) grace and the keepalive could
 * not vouch — surface the honest calm band. [graceMs] is the escalating grace this
 * paint waited (base + backoff), recorded so the amortization is visible in field
 * logs and a genuine sustained loss is not confused with the swallowed blip.
 */
internal fun recordNetworkLossBandPainted(
    change: TerminalNetworkChange,
    target: ConnectionTarget,
    generation: Long,
    graceMs: Long,
) {
    DiagnosticEvents.record(
        "connection",
        "network_loss_band_painted",
        "source" to "network_observer",
        "trigger" to TmuxConnectTrigger.NetworkReconnect.logValue,
        "reason" to change.reason,
        "classification" to "bare_network_loss_sustained",
        "graceMs" to graceMs,
        "sequence" to change.sequence,
        "hostId" to target.hostId,
        "generation" to generation,
        "deferredFromBackground" to change.deferredFromBackground,
    )
    ReconnectCauseTrail.record(
        stage = "network_loss_decision",
        outcome = "paint_band",
        cause = "bare_network_loss_sustained",
        trigger = TmuxConnectTrigger.NetworkReconnect.logValue,
        "sequence" to change.sequence,
        "hostId" to target.hostId,
        "generation" to generation,
        "graceMs" to graceMs,
        "classification" to "bare_network_loss",
        "deferredFromBackground" to change.deferredFromBackground,
    )
}
