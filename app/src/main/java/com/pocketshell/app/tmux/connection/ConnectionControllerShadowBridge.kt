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
 * EPIC #687 Phase-2, slice 1c-iv-prep — the OBSERVE-ONLY EQUIVALENCE BRIDGE,
 * now driven by REAL transport/seed feedback.
 *
 * A real `:shared:core-connection` [ConnectionController] runs in SHADOW: the VM
 * feeds it the same lifecycle inputs (Background / Foreground / NetworkChanged /
 * TransportDropped) the inline `reduceConnection` path already dispatches, PLUS
 * the open/switch INTENT mirrored from the single 1b `setConnectionState` choke
 * point ([observeInlineTransition] → Enter/Switch/TargetGone/drop-ladder). The
 * controller then reaches [ConnectionState.Live] from **REAL feedback**, not by
 * mirroring the inline Live transition:
 *  - [observeTransportLive] is fed at the EXISTING point a lease goes
 *    `Connected` (the VM's `SshLeaseManager.stateEvents` collector) — the real
 *    "transport became live" signal.
 *  - [observeSeedLanded] is fed at the EXISTING point a `capture-pane` lands for
 *    the active pane (the VM's `seedPaneFromCaptureOnce` success) — the real
 *    "seed/capture landed" signal.
 * The controller computes its [ConnectionState] from those events — but its
 * state **DRIVES NOTHING**.
 *
 * ## Observe-only contract (hard rule)
 * - The bridge NEVER calls back into the VM and NEVER mutates VM state.
 * - The real-feedback feeds ([observeTransportLive]/[observeSeedLanded]) are
 *   FIRE-AND-OBSERVE emits placed AFTER the existing write-path side effect they
 *   observe (the `liveLeaseKeys.add` / `panesSeededThisAttach.add` line). They
 *   add NO new control flow, NO new IO, and do not change the attach/seed timing
 *   or ordering — the write-path stays byte-identical. Observation cannot leak
 *   into behavior.
 * - The controller's [shadowState] / [shadowStatusName] / [shadowIndicator] are
 *   read-only diagnostics. Nothing in the VM reads them to gate an effect, a job,
 *   `_connectionStatus`, or the header indicator. They exist ONLY so the
 *   equivalence tests can prove the controller produces the right state from the
 *   real event stream BEFORE 1c-iv lets it drive.
 *
 * ## Why the inline mirror still feeds the INTENT (Enter/Switch)
 * The controller must know WHICH host/target it is connecting/switching to before
 * the real `TransportLive`/`SeedLanded` can promote it. The inline transition is
 * the cheapest place to read that intent (the VM already knows the
 * active/connecting target there), and mirroring Enter/Switch changes no
 * write-path behavior. What is NO LONGER synthesised from the inline mirror is
 * the Live promotion itself: [observeInlineTransition] for an inline `Live` only
 * ensures the controller is targeting the right id; the actual promotion to
 * [ConnectionState.Live] now comes from the REAL [observeTransportLive] +
 * [observeSeedLanded] feedback. This is the final 1c-iv prerequisite: the shadow
 * controller reaches Live independently, from genuine signals.
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
                // EPIC #687 slice 1c-iv-a (THE STATUS FLIP): the inline reveal choke
                // point is the AUTHORITATIVE "the user is now connected" moment — the
                // VM only calls `setConnectionState(Live)` once the active pane is
                // seeded and the surface is revealed. Now that the controller's state
                // DRIVES the view-facing `_connectionStatus`, the inline Live mirror
                // must promote the controller to [ConnectionState.Live] in lockstep so
                // the displayed `Connected` flips at exactly the inline moment (no
                // status-timing regression on any preserved path, including the test
                // seams that fake a seeded reveal).
                //
                // 1c-iv-prep had this mirror only `ensureTargeting` to PROVE the
                // controller can reach Live independently from the real
                // TransportLive/SeedLanded feedback (a de-risking proof). Those real
                // feeds REMAIN (idempotent — a TransportLive/SeedLanded for an
                // already-Live current target is a no-op in the reducer); the inline
                // reveal is simply the authoritative status moment for the flip.
                if (host != null && targetId != null) {
                    ensureTargeting(host, targetId)
                    promoteToLive(host, targetId)
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

    /**
     * Observe the REAL "transport became live" signal — fed at the EXISTING point
     * a lease transitions to `Connected` (the VM's `SshLeaseManager.stateEvents`
     * collector). Submits the real [ConnectionEvent.TransportLive]. On a cold dial
     * this promotes `Connecting → Attaching`; on a reattach/reconnect it lands
     * `Live`. [TransportLive] carries no target id, so the controller applies it to
     * its CURRENT target — the inline intent mirror (Enter/Switch) already
     * established that target, so no re-targeting is done here (re-targeting from a
     * feedback emit could wrongly Switch on a stale signal).
     *
     * @param host the host the lease came live for (diagnostic; the controller's
     *   current state already carries the host).
     * FIRE-AND-OBSERVE: the caller emits this AFTER the existing `liveLeaseKeys`
     * write, so it adds no write-path control flow — pure observation.
     */
    fun observeTransportLive(
        @Suppress("UNUSED_PARAMETER") host: HostKey,
        @Suppress("UNUSED_PARAMETER") targetId: SessionId,
    ) {
        controller.submit(ConnectionEvent.TransportLive)
    }

    /**
     * Observe the REAL "seed/capture landed" signal — fed at the EXISTING point a
     * `capture-pane` lands for a pane (the VM's `seedPaneFromCaptureOnce` success,
     * after `panesSeededThisAttach.add`). Submits the real
     * [ConnectionEvent.SeedLanded] tagged with [targetId]. The controller's own
     * DROP-BY-ID check (`event.targetId != currentTargetId → ignore`) drops a seed
     * for a SUPERSEDED target, so a late capture from a session the user already
     * switched away from never promotes the wrong target. A landing for the current
     * target while attaching/reattaching/reconnecting promotes to
     * [ConnectionState.Live] — this is how the shadow controller reaches Live from
     * REAL feedback, independently of the inline transition. No re-targeting is
     * done here for the same stale-signal reason as [observeTransportLive].
     *
     * @param host the host the seed came from (diagnostic).
     * FIRE-AND-OBSERVE: the caller emits this AFTER the existing seed side effect,
     * so it adds no write-path control flow — pure observation.
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

    /**
     * Walk the controller to [ConnectionState.Live] for [targetId] from whatever
     * pre-reveal state it is in. The reducer reaches Live via:
     *  - `Connecting --TransportLive--> Attaching --SeedLanded--> Live`
     *  - `Attaching --SeedLanded--> Live`
     *  - `Reattaching/Reconnecting --TransportLive--> Live`
     * so submitting `TransportLive` then `SeedLanded` covers every pre-reveal state.
     * Idempotent: each event is a no-op when the controller is already Live for this
     * target ([onTransportLive]/[onSeedLanded] return current for a Live state).
     */
    private fun promoteToLive(host: HostKey, targetId: SessionId) {
        if (controller.state.value.targetIdOrNull() != targetId) return
        controller.submit(ConnectionEvent.TransportLive)
        controller.submit(ConnectionEvent.SeedLanded(targetId, paneId = "inline-reveal"))
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
