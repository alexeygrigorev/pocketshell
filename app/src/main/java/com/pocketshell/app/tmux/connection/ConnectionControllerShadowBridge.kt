package com.pocketshell.app.tmux.connection

import com.pocketshell.core.connection.Clock
import com.pocketshell.core.connection.ConnectionController
import com.pocketshell.core.connection.ConnectionEvent
import com.pocketshell.core.connection.ConnectionIndicator
import com.pocketshell.core.connection.ConnectionState
import com.pocketshell.core.connection.HostKey
import com.pocketshell.core.connection.LeaseHandle
import com.pocketshell.core.connection.SessionId
import com.pocketshell.core.connection.TransportPort
import com.pocketshell.core.connection.TransportUpDown
import com.pocketshell.core.connection.targetIdOrNull
import com.pocketshell.core.connection.toIndicator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * EPIC #687 Phase-2, slice 1c-iii — the OBSERVE-ONLY EQUIVALENCE BRIDGE.
 *
 * A real `:shared:core-connection` [ConnectionController] runs in SHADOW: the VM
 * feeds it the same lifecycle inputs (Background / Foreground / NetworkChanged /
 * TransportDropped) the inline `reduceConnection` path already dispatches, PLUS
 * the connect/attach/seed transitions the inline write-path already produces
 * (mirrored from the single 1b `setConnectionState` choke point as
 * Enter/Switch/TransportLive/SeedLanded/TargetGone). The controller computes its
 * [ConnectionState] from those events — but its state **DRIVES NOTHING**.
 *
 * ## Observe-only contract (hard rule)
 * - The bridge NEVER calls back into the VM and NEVER mutates VM state.
 * - The bridge feeds events DERIVED FROM the inline transitions the VM already
 *   makes; it does NOT add new emissions to the attach/capture/disconnect
 *   write-path. So the write-path stays byte-identical — observation cannot leak
 *   into behavior.
 * - The controller's [shadowState] / [shadowStatusName] / [shadowIndicator] are
 *   read-only diagnostics. Nothing in the VM reads them to gate an effect, a job,
 *   `_connectionStatus`, or the header indicator. They exist ONLY so the slice
 *   1c-iii equivalence tests can prove the controller produces the right state
 *   from the real event stream BEFORE 1c-iv lets it drive.
 *
 * ## Why mirror the inline transitions instead of synthesising independent feedback
 * The inline VM has no discrete `SeedLanded`/`TransportLive` signal — those land
 * INLINE inside the successful attach path (the `setConnectionState(Live(...))`
 * choke point). Emitting fresh `SeedLanded`/`TransportLive` events from the
 * attach/capture code IS the write-path rewrite (slice W) and would risk a
 * behavior change. The observe-only bridge instead READS each inline transition
 * and mirrors it into the controller. This keeps the write-path untouched while
 * still proving the controller's reducer maps the real event sequence to the
 * right state — and pinpoints the exactly-two approved divergences (recoverable
 * drop, within-grace foreground) that 1c-iv will flip to.
 *
 * The single [ShadowTransportPort.isWarm] snapshot the controller consults
 * synchronously is supplied by the VM's existing live-lease-key set; no transport
 * IO is performed by the shadow controller (the bridge feeds explicit events).
 */
class ConnectionControllerShadowBridge(
    clock: Clock = SystemElapsedClock,
    /** Non-suspending warm-lease snapshot — the VM's `liveLeaseKeys`-backed read.
     *  Defaults to "warm" so a within-grace foreground in tests rides the lease;
     *  the VM injects its real snapshot. */
    warmSnapshot: (HostKey) -> Boolean = { true },
) {
    private val transport = ShadowTransportPort(warmSnapshot)

    private val controller = ConnectionController(clock = clock, transport = transport)

    /** The shadow controller's current state — read-only diagnostic; drives nothing. */
    val shadowState: ConnectionState
        get() = controller.state.value

    /** The shadow controller's header indicator — read-only diagnostic; drives nothing. */
    val shadowIndicator: ConnectionIndicator
        get() = controller.state.value.toIndicator()

    /**
     * The PRESERVED-behavior projection name the equivalence tests compare against
     * the inline `ConnectionStatus`. Maps the controller's [ConnectionState] onto
     * the same coarse view-facing vocabulary the inline `connectionStatusFor`
     * produces (`Idle/Connecting/Switching/Connected/Reconnecting/Failed`), via the
     * §1 seam-table 1:1 mapping. (`Attaching→Switching`, `Live→Connected`,
     * `Reattaching/Reconnecting→Reconnecting`, `Unreachable/Gone→Failed`.) The two
     * approved DIVERGENCES are intrinsic to the controller and surface here as the
     * calmer name: a recoverable drop reads `Reconnecting` (not `Failed`), and a
     * within-grace foreground reads `Connected` (not `Reconnecting`).
     */
    val shadowStatusName: String
        get() = shadowStatusNameFor(controller.state.value)

    /**
     * Observe an inline VM connection transition (the single 1b `setConnectionState`
     * choke point). Maps the inline state onto the controller's event vocabulary and
     * submits it. Pure observation: the caller's `_connectionStatus` write is
     * unaffected; the controller's resulting state is collected for equivalence only.
     *
     * @param inlineName the inline VM `ConnectionState` variant name
     *   (`Idle/Connecting/Attaching/Live/Reconnecting/Unreachable/Gone/...`).
     * @param host opaque host coordinates of the transition (null for Idle).
     * @param targetId opaque session id of the transition (null for Idle).
     */
    fun observeInlineTransition(inlineName: String, host: HostKey?, targetId: SessionId?) {
        when (inlineName) {
            "Idle" -> Unit // controller stays Idle; nothing to mirror.
            "Connecting" -> {
                if (host != null && targetId != null) {
                    controller.submit(ConnectionEvent.Enter(host, targetId))
                }
            }
            "Attaching" -> {
                // Warm same-host open / switch. If the controller already has a
                // host (live-ish), Switch; otherwise an Enter that the warm
                // predicate routes straight to Attaching.
                if (host != null && targetId != null) {
                    if (controller.state.value !is ConnectionState.Idle) {
                        controller.submit(ConnectionEvent.Switch(targetId))
                    } else {
                        controller.submit(ConnectionEvent.Enter(host, targetId))
                    }
                }
            }
            "Live" -> {
                if (host != null && targetId != null) {
                    // Ensure the controller is targeting this id, then land it.
                    ensureTargeting(host, targetId)
                    controller.submit(ConnectionEvent.TransportLive)
                    controller.submit(ConnectionEvent.SeedLanded(targetId, paneId = "active"))
                }
            }
            "Reconnecting" -> {
                if (host != null && targetId != null) {
                    ensureTargeting(host, targetId)
                    // Inline "Reconnecting" is the beyond-grace / drop-escalation
                    // silent ladder — model it as a transport drop from a live-ish
                    // state.
                    controller.submit(ConnectionEvent.TransportDropped("reconnect"))
                }
            }
            "Unreachable" -> {
                // Inline honest error: exhaust the controller's ladder so it
                // surfaces Unreachable too (drive it to the budget).
                exhaustToUnreachable()
            }
            "Gone" -> {
                if (targetId != null) {
                    controller.submit(ConnectionEvent.TargetGone(targetId))
                }
            }
        }
    }

    /** Observe the app moving to the background (the inline `onAppBackgrounded`
     *  dispatch point). */
    fun observeBackground() {
        controller.submit(ConnectionEvent.Background)
    }

    /** Observe the app moving to the foreground (the inline `onAppForegrounded`
     *  dispatch point). The single grace predicate decides reattach-vs-reconnect. */
    fun observeForeground() {
        controller.submit(ConnectionEvent.Foreground)
    }

    /** Observe a device network change (the inline `onNetworkChanged` dispatch
     *  point). #548 suppression lives in the controller. */
    fun observeNetworkChanged(validatedHandoff: Boolean) {
        controller.submit(ConnectionEvent.NetworkChanged(validatedHandoff))
    }

    /** Observe a passive transport drop (the inline `handlePassiveClientDisconnect`
     *  dispatch point). On a live channel the controller heals through
     *  [ConnectionState.Reattaching] silently — the approved divergence. */
    fun observeTransportDropped(reason: String) {
        controller.submit(ConnectionEvent.TransportDropped(reason))
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

    private fun exhaustToUnreachable() {
        // Drive the reconnect ladder past its budget so the controller reaches the
        // single honest error state, matching an inline `Unreachable`.
        var guard = 0
        while (controller.state.value !is ConnectionState.Unreachable && guard < 16) {
            val before = controller.state.value
            controller.submit(ConnectionEvent.TransportDropped("unreachable"))
            if (controller.state.value === before) break
            guard++
        }
    }

    /**
     * A minimal observe-only [TransportPort]. The shadow controller only consults
     * [isWarm] synchronously from its reducer; the bridge feeds every transport
     * EVENT explicitly, so [transportEvents] is empty and the suspend IO methods
     * are inert (never invoked by the reducer). No real lease IO ever runs.
     */
    private class ShadowTransportPort(
        private val warmSnapshot: (HostKey) -> Boolean,
    ) : TransportPort {
        override suspend fun ensureLease(host: HostKey): LeaseHandle =
            object : LeaseHandle {
                override val host: HostKey = host
            }

        override fun isWarm(host: HostKey): Boolean = warmSnapshot(host)

        override suspend fun evictStale(host: HostKey) = Unit

        override val transportEvents: Flow<TransportUpDown> = emptyFlow()
    }

    companion object {
        /**
         * Project a controller [ConnectionState] onto the inline view-facing
         * status name the equivalence tests compare against. The §1 seam-table
         * 1:1 mapping; the two approved divergences surface here as the calmer
         * name (recoverable drop → `Reconnecting`, within-grace foreground →
         * `Connected`).
         */
        fun shadowStatusNameFor(state: ConnectionState): String = when (state) {
            is ConnectionState.Idle -> "Idle"
            is ConnectionState.Connecting -> "Connecting"
            is ConnectionState.Attaching -> "Switching"
            is ConnectionState.Live -> "Connected"
            // Backgrounded keeps the prior live surface in the inline VM; project
            // to Connected for comparison parity.
            is ConnectionState.Backgrounded -> "Connected"
            is ConnectionState.Reattaching -> "Reconnecting"
            is ConnectionState.Reconnecting -> "Reconnecting"
            is ConnectionState.Gone -> "Failed"
            is ConnectionState.Unreachable -> "Failed"
        }
    }
}
