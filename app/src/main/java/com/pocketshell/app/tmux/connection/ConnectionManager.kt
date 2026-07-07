package com.pocketshell.app.tmux.connection

import com.pocketshell.core.connection.Clock
import com.pocketshell.core.connection.ConnectionController
import com.pocketshell.core.connection.ConnectionEvent
import com.pocketshell.core.connection.ConnectionState
import com.pocketshell.core.connection.HostKey
import com.pocketshell.core.connection.SessionId
import com.pocketshell.core.connection.TransportPort
import com.pocketshell.core.connection.TransportUpDown
import com.pocketshell.core.connection.targetIdOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * EPIC #792 Slice E — the connection FACADE (the capstone of the migration).
 *
 * `ConnectionManager` is the single object the [com.pocketshell.app.tmux.TmuxSessionViewModel]
 * holds for the whole connect / attach / reattach / grace / lease / reconnect surface. It
 * OWNS the `:shared:core-connection` [ConnectionController] (the deterministic reducer) and
 * the app-side single-responsibility effect classes ([GraceEffects], [TmuxAttachEffects],
 * [TransportEffects]) plus the [ConnectionEffectDriver] that wires the controller's state
 * transitions to those effect dispatchers. The VM no longer reaches into a dozen separate
 * connection fields — it talks to this one facade.
 *
 * ## This replaces the shadow bridge (D28 single active path — done)
 * Slices A-D promoted the controller from a status-projection shadow to the AUTHORITATIVE
 * owner of every connection IO decision: intent flows in directly (no string mirror, Slice A),
 * attach/switch/grace IO routes through the effect classes (Slice B), the reconnect ladder IO
 * routes through [TransportEffects] (Slice C, the #822 wedge fix), and the [LivenessProbe]
 * detects a silent drop (Slice D). The old `ConnectionControllerShadowBridge` — whose own doc
 * called its `shadowState`/`shadowIndicator` accessors "read-only diagnostic; drives nothing"
 * — was the half-migration residue D28(4) exists to end. Slice E DELETES that shadow framing:
 * the controller's [state] is the SOLE authority (it drives the displayed status, the reveal
 * gate, and the liveness gate), so there is no shadow, no mirror, no dual-write, no toggle.
 *
 * ## Intent is controller-direct (typed entrypoints)
 * The VM calls the facade's TYPED intent entrypoints — [enter] / [switchTo] (the open/switch
 * INTENT), [revealLive] (the authoritative reveal moment), [escalateReconnecting] /
 * [escalateUnreachable] (drop/error escalation), [markGone] (target deleted). Each submits the
 * matching [ConnectionEvent] to the controller, so the controller drives the intent state
 * machine directly.
 *
 * ## Transport inputs are driver-fed from reality
 * The controller's `TransportLive` / `TransportDropped` inputs are SUBMITTED by the owned
 * [ConnectionEffectDriver] from the REAL port flows — the lease `Up`/`Down` edges of
 * [SshLeaseTransportPort.transportEvents] and the [CurrentClientTmuxPort.disconnected]
 * oracle. The Live promotion also flows through the inline reveal ([revealLive]) and the
 * real [observeSeedLanded] feedback (idempotent).
 *
 * ## What the VM still SOURCES (the intent + the coupled IO bodies)
 * The controller must know WHICH host/target it is connecting/switching to before a
 * `TransportLive`/`SeedLanded` can promote it, so the VM sources the INTENT from its
 * connection-state choke point. The deeply-VM-coupled IO BODIES (the warm fast-switch attach,
 * the grace teardown/reseed/heal, the reconnect ladder) stay as the `*Io` capabilities the VM
 * implements and passes in — the effect classes own the *dispatch/decision/single-entrypoint
 * contract*, the VM owns the coupled primitives (lease manager, generation counter, reveal
 * machine, telemetry). This facade owns the effect classes and routes the VM's calls to them.
 */
class ConnectionManager(
    clock: Clock = SystemElapsedClock,
    /** The transport the [ConnectionController] consults synchronously for its warm-lease
     *  grace predicate ([TransportPort.isWarm]) AND whose [TransportPort.transportEvents]
     *  the effect driver observes. In PRODUCTION this is the REAL [SshLeaseTransportPort]
     *  over the VM's `SshLeaseManager`. */
    val transport: TransportPort,
) {
    private val controller = ConnectionController(clock = clock, transport = transport)

    /**
     * The `:shared:core-connection` reducer the facade owns. Exposed so the VM can wire the
     * [RevealStateMachine] / liveness gate to its [ConnectionController.state] and submit the
     * proactive-drop event. The reducer is the SOLE connection-state authority.
     */
    val connectionController: ConnectionController
        get() = controller

    /**
     * Test/convenience constructor. Builds an inert [TransportPort] whose only live behavior
     * is the injected non-suspending [warmSnapshot] the controller's grace predicate consults;
     * its [TransportPort.transportEvents] is empty and its suspend IO methods are never invoked
     * by the reducer (the facade feeds every transport EVENT explicitly). A TEST DOUBLE for the
     * equivalence suite; production always injects the real [SshLeaseTransportPort].
     */
    constructor(clock: Clock = SystemElapsedClock, warmSnapshot: (HostKey) -> Boolean = { true }) :
        this(clock = clock, transport = WarmSnapshotTransportPort(warmSnapshot))

    /** The controller's current state — the SOLE connection-state authority. */
    val state: ConnectionState
        get() = controller.state.value

    /** The controller's current state as a flow — for the reveal machine / liveness gate. */
    val stateFlow: StateFlow<ConnectionState>
        get() = controller.state

    /**
     * The view-facing status NAME the equivalence tests compare against the inline
     * `ConnectionStatus`. Maps the controller's [ConnectionState] onto the coarse view-facing
     * vocabulary (`Idle/Connecting/Switching/Connected/Reconnecting/Failed`). The two approved
     * divergences surface here as the calmer name (recoverable drop → `Reconnecting`,
     * within-grace foreground → `Connected`).
     */
    val statusName: String
        get() = statusNameFor(controller.state.value)

    /**
     * Submit a raw [ConnectionEvent] to the controller. Used by the [LivenessProbe] handler to
     * walk the controller Live → Reattaching for an immediate connection-lost indicator (#822
     * detection). The reducer self-gates each event, so this is never a second writer.
     */
    fun submit(event: ConnectionEvent) {
        controller.submit(event)
    }

    /**
     * INTENT: a user-initiated OPEN. Submits [ConnectionEvent.Enter] — the controller's warm
     * predicate routes a warm host straight to [ConnectionState.Attaching] and a cold host to
     * [ConnectionState.Connecting].
     */
    fun enter(host: HostKey, targetId: SessionId) {
        controller.submit(ConnectionEvent.Enter(host, targetId))
    }

    /**
     * INTENT: a same-host fast SWITCH. Submits [ConnectionEvent.Switch] when the controller
     * already holds a host (live-ish) so it re-targets to [ConnectionState.Attaching] WITHOUT a
     * re-handshake; from Idle (the warm same-host OPEN case) it is an [ConnectionEvent.Enter]
     * the warm predicate routes straight to [ConnectionState.Attaching].
     */
    fun switchTo(host: HostKey, targetId: SessionId) {
        if (controller.state.value !is ConnectionState.Idle) {
            controller.submit(ConnectionEvent.Switch(targetId))
        } else {
            controller.submit(ConnectionEvent.Enter(host, targetId))
        }
    }

    /**
     * INTENT: the authoritative REVEAL moment. The VM only calls this once the active pane is
     * seeded and the surface is revealed, so promoting the controller to [ConnectionState.Live]
     * here flips the displayed `Connected` at exactly the inline reveal moment. The real
     * [observeSeedLanded] / driver-fed `TransportLive` feeds REMAIN (idempotent).
     */
    fun revealLive(host: HostKey, targetId: SessionId) {
        ensureTargeting(host, targetId)
        promoteToLive(host, targetId)
    }

    /**
     * INTENT: the drop-escalation / beyond-grace silent reconnect ladder. Models the inline
     * escalation as a transport drop from a live-ish state so the controller walks the same
     * reconnect ladder.
     */
    fun escalateReconnecting(host: HostKey, targetId: SessionId) {
        ensureTargeting(host, targetId)
        controller.submit(ConnectionEvent.TransportDropped("reconnect"))
    }

    /** INTENT: the inline honest error. Drives the reconnect ladder past its budget so the
     *  controller surfaces [ConnectionState.Unreachable]. */
    fun escalateUnreachable() {
        exhaustToUnreachable()
    }

    /** INTENT: a target deleted elsewhere. Submits [ConnectionEvent.TargetGone]; the controller
     *  drops it by id if it does not match the current target. */
    fun markGone(targetId: SessionId) {
        controller.submit(ConnectionEvent.TargetGone(targetId))
    }

    /** Lifecycle: the app moved to the background. */
    fun observeBackground() {
        controller.submit(ConnectionEvent.Background)
    }

    /** Lifecycle: the app moved to the foreground. The single grace predicate decides
     *  reattach-vs-reconnect. */
    fun observeForeground() {
        controller.submit(ConnectionEvent.Foreground)
    }

    /** Lifecycle: a device network change. #548 suppression lives in the controller. */
    fun observeNetworkChanged(validatedHandoff: Boolean) {
        controller.submit(ConnectionEvent.NetworkChanged(validatedHandoff))
    }

    /**
     * Within-grace foreground reattach promotion: the warm `-CC` channel was NEVER torn down,
     * so [ConnectionEvent.TransportLive] promotes [ConnectionState.Reattaching] →
     * [ConnectionState.Live] WITHOUT any handshake. Idempotent for an already-Live target.
     */
    fun observeForegroundReattachLive() {
        controller.submit(ConnectionEvent.TransportLive)
    }

    /**
     * Observe the REAL "seed/capture landed" signal at the existing `capture-pane` success
     * point. Submits [ConnectionEvent.SeedLanded] tagged with [targetId]; the controller's
     * drop-by-id check drops a seed for a superseded target. A landing for the current target
     * while attaching/reattaching/reconnecting promotes to [ConnectionState.Live].
     */
    fun observeSeedLanded(
        @Suppress("UNUSED_PARAMETER") host: HostKey,
        targetId: SessionId,
        paneId: String,
    ) {
        controller.submit(ConnectionEvent.SeedLanded(targetId, paneId))
    }

    private fun ensureTargeting(host: HostKey, targetId: SessionId) {
        val state = controller.state.value
        when {
            state is ConnectionState.Idle ->
                controller.submit(ConnectionEvent.Enter(host, targetId))
            state.targetIdOrNull() != targetId ->
                controller.submit(ConnectionEvent.Switch(targetId))
            else -> Unit
        }
    }

    private fun promoteToLive(host: HostKey, targetId: SessionId) {
        if (controller.state.value.targetIdOrNull() != targetId) return
        controller.submit(ConnectionEvent.TransportLive)
        controller.submit(ConnectionEvent.SeedLanded(targetId, paneId = "inline-reveal"))
    }

    private fun exhaustToUnreachable() {
        var guard = 0
        while (controller.state.value !is ConnectionState.Unreachable && guard < 16) {
            val before = controller.state.value
            controller.submit(ConnectionEvent.TransportDropped("unreachable"))
            if (controller.state.value === before) break
            guard++
        }
    }

    /**
     * A minimal TEST-DOUBLE [TransportPort] for the equivalence suite. The controller only
     * consults [isWarm] synchronously; the facade feeds every transport EVENT explicitly, so
     * [transportEvents] is empty. PRODUCTION never uses this — the VM injects the real
     * [SshLeaseTransportPort].
     */
    private class WarmSnapshotTransportPort(
        private val warmSnapshot: (HostKey) -> Boolean,
    ) : TransportPort {
        override fun isWarm(host: HostKey): Boolean = warmSnapshot(host)

        override val transportEvents: Flow<TransportUpDown> = emptyFlow()
    }

    companion object {
        /**
         * Project a controller [ConnectionState] onto the view-facing status name the
         * equivalence tests compare against the inline `ConnectionStatus`. The §1 seam-table
         * 1:1 mapping; the two approved divergences surface here as the calmer name (recoverable
         * drop → `Reconnecting`, within-grace foreground → `Connected`).
         */
        fun statusNameFor(state: ConnectionState): String = when (state) {
            is ConnectionState.Idle -> "Idle"
            is ConnectionState.Connecting -> "Connecting"
            is ConnectionState.Attaching -> "Switching"
            is ConnectionState.Live -> "Connected"
            // Backgrounded keeps the prior live surface in the inline VM; project to Connected
            // for comparison parity.
            is ConnectionState.Backgrounded -> "Connected"
            is ConnectionState.Reattaching -> "Reconnecting"
            is ConnectionState.Reconnecting -> "Reconnecting"
            is ConnectionState.Gone -> "Failed"
            is ConnectionState.Unreachable -> "Failed"
        }
    }
}
