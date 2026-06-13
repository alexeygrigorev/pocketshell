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
 * EPIC #687 Phase-2 — the bridge that adapts the VM's inline lifecycle to the real
 * `:shared:core-connection` [ConnectionController].
 *
 * A real [ConnectionController] runs behind this bridge: the VM feeds it the
 * lifecycle inputs the inline path dispatches (Background / Foreground /
 * NetworkChanged) PLUS the open/switch/reveal INTENT mirrored from the single 1b
 * `setConnectionState` choke point ([observeInlineTransition] → Enter/Switch/
 * Live-reveal/TargetGone/drop-ladder), and the real seed-landing feedback
 * ([observeSeedLanded] at the existing `capture-pane` success point).
 *
 * ## Status source (slice 1c-iv-a — the FLIP)
 * The controller's [ConnectionState] now DRIVES the view-facing `_connectionStatus`
 * (the VM's `projectStatusFromController`): the controller is the single status
 * source. The [shadowState] / [shadowStatusName] / [shadowIndicator] accessors
 * remain for the equivalence suite to compare against the pinned inline status names.
 *
 * ## Transport inputs (slice 1c-iv-b-B2 #742 — driver-fed from reality)
 * The controller's `TransportLive` / `TransportDropped` inputs are SUBMITTED by the
 * [ConnectionEffectDriver] from the REAL port flows — the lease-`Up` edge of
 * [SshLeaseTransportPort.transportEvents] and the [CurrentClientTmuxPort.disconnected]
 * oracle — so the bridge's former `observeTransportLive` / `observeTransportDropped`
 * mirror feeds are DELETED (D22 hard-cut). The Live PROMOTION still flows through
 * the inline reveal ([observeInlineTransition] `Live` → `promoteToLive`, the
 * authoritative "user is connected" moment) and the real [observeSeedLanded]
 * feedback (idempotent); the driver's real-flow TransportLive keeps the controller
 * Live across lease re-`Connected` events.
 *
 * ## Why the inline mirror still feeds the INTENT (Enter/Switch/reveal)
 * The controller must know WHICH host/target it is connecting/switching to before
 * a `TransportLive`/`SeedLanded` can promote it. The inline transition is the
 * cheapest place to read that intent (the VM already knows the active/connecting
 * target there), and mirroring Enter/Switch changes no write-path behavior.
 *
 * The single [TransportPort.isWarm] snapshot the controller consults synchronously
 * is supplied by the VM's existing live-lease-key set (via the real
 * [SshLeaseTransportPort.warmSnapshot] in production — slice 1c-iv-b-A2 #739).
 */
class ConnectionControllerShadowBridge(
    clock: Clock = SystemElapsedClock,
    /** The transport the shadow [ConnectionController] consults synchronously for
     *  its warm-lease grace predicate ([TransportPort.isWarm]) AND whose
     *  [TransportPort.transportEvents] the effect driver observes. In PRODUCTION
     *  this is the REAL [SshLeaseTransportPort] over the VM's `SshLeaseManager`
     *  (slice 1c-iv-b-A2 #739 — one source of truth, no stub `emptyFlow`); the
     *  controller still reaches Live from the bridge's explicit real-feedback
     *  feeds, so no transport IO is performed by the controller. */
    val transport: TransportPort,
) {
    private val controller = ConnectionController(clock = clock, transport = transport)

    /**
     * The real `:shared:core-connection` reducer the bridge runs in shadow.
     * Exposed READ-ONLY so the VM can wire the [ConnectionEffectDriver] to observe
     * its [ConnectionController.state] transitions (slice 1c-iv-b-A2 #739). The VM
     * NEVER [ConnectionController.submit]s through this accessor — every event still
     * flows through the bridge's `observe*` methods — and the driver only READS
     * [ConnectionController.state]. Pure observation; drives nothing.
     */
    val connectionController: ConnectionController
        get() = controller

    /**
     * Test/convenience constructor. Builds an inert [TransportPort] whose only live
     * behavior is the injected non-suspending [warmSnapshot] the controller's grace
     * predicate consults; its [TransportPort.transportEvents] is empty and its
     * suspend IO methods are never invoked by the reducer (the bridge feeds every
     * transport EVENT explicitly). This is a TEST DOUBLE for the equivalence suite,
     * not a production stub — production always injects the real
     * [SshLeaseTransportPort] via the primary [transport] constructor above.
     */
    constructor(clock: Clock = SystemElapsedClock, warmSnapshot: (HostKey) -> Boolean = { true }) :
        this(clock = clock, transport = WarmSnapshotTransportPort(warmSnapshot))

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

    /**
     * EPIC #687 slice 1c-iv-c (#754): within-grace foreground reattach promotion. The
     * warm `-CC` control channel was NEVER torn down across the brief background, so on
     * return the controller is [ConnectionState.Reattaching] (from its `onForeground`
     * grace predicate). Submitting [ConnectionEvent.TransportLive] promotes it back to
     * [ConnectionState.Live] (`onTransportLive(Reattaching) → Live`) WITHOUT any
     * handshake — the channel is the same live one. Idempotent: a `TransportLive` for an
     * already-[ConnectionState.Live] target is a no-op in the reducer.
     */
    fun observeForegroundReattachLive() {
        controller.submit(ConnectionEvent.TransportLive)
    }

    /**
     * EPIC #687 slice 1c-iv-b-B2 (#742): the controller's transport inputs
     * (`TransportLive` / `TransportDropped`) are now SUBMITTED by the
     * [ConnectionEffectDriver] from the REAL port flows
     * ([com.pocketshell.app.tmux.connection.SshLeaseTransportPort.transportEvents]
     * `Up` edge → `TransportLive`; [com.pocketshell.app.tmux.connection.CurrentClientTmuxPort.disconnected]
     * true-edge → `TransportDropped`), so the bridge's `observeTransportLive` /
     * `observeTransportDropped` mirror feeds are DELETED (D22 hard-cut — the driver
     * is the sole transport-event source, no fallback). The remaining `observe*`
     * methods feed the INTENT (Enter/Switch via [observeInlineTransition]) and the
     * lifecycle (background/foreground/network) until later slices move those too.
     */

    /**
     * Observe the REAL "seed/capture landed" signal — fed at the EXISTING point a
     * `capture-pane` lands for a pane (the VM's `seedPaneFromCaptureOnce` success,
     * after `panesSeededThisAttach.add`). Submits the real
     * [ConnectionEvent.SeedLanded] tagged with [targetId]. The controller's own
     * DROP-BY-ID check (`event.targetId != currentTargetId → ignore`) drops a seed
     * for a SUPERSEDED target, so a late capture from a session the user already
     * switched away from never promotes the wrong target. A landing for the current
     * target while attaching/reattaching/reconnecting promotes to
     * [ConnectionState.Live] — this is how the controller reaches Live from REAL
     * feedback, independently of the inline transition. No re-targeting is done here:
     * a SeedLanded carries its own [targetId] and the reducer drops it by id, so a
     * stale signal can never wrongly Switch the controller.
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
     * A minimal TEST-DOUBLE [TransportPort] for the equivalence suite. The shadow
     * controller only consults [isWarm] synchronously from its reducer; the bridge
     * feeds every transport EVENT explicitly, so [transportEvents] is empty and the
     * suspend IO methods are inert (never invoked by the reducer). No real lease IO
     * ever runs. PRODUCTION never uses this — the VM injects the real
     * [SshLeaseTransportPort] (slice 1c-iv-b-A2 #739).
     */
    private class WarmSnapshotTransportPort(
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
