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
 * Issue #1328/#1539: who reported a reconnect rung failure to the controller.
 *
 * The controller's single churn-surviving attempt counter takes exactly ONE reporter per real
 * rung failure — two reporters would advance it twice and exhaust the ladder at half its
 * intended patience. These are the only two reporters.
 *
 * ## Why the passive-grace loop's feed is NOT guarded on an active ladder (#1539 round 2)
 *
 * The two reporters are already mutually exclusive BY CONSTRUCTION, so no guard is needed:
 * `scheduleAutoReconnectBody` CANCELS `passiveDisconnectGraceJob` as its FIRST act, before it
 * ever assigns `autoReconnectJob` — a ladder in flight therefore means the grace loop is
 * already dead and cannot reach its feed. (The loop's own `inlineConnectionStatus is Connected`
 * exit enforces the same thing independently.)
 *
 * An `autoReconnectJob?.isActive != true` guard on that feed was carried in round 1, reasoned
 * a-priori from the #1328 contract. It was then MEASURED: the loop reported identical counts
 * with and without it (1 across 3-4 dials on the storm shape, 3 across 5 on the dial-abandon
 * shape) — it suppressed exactly ZERO feeds, in every scenario. It was a redundant second
 * expression of an invariant the job lifecycle already owns, i.e. the patches-on-patches shim
 * D28 exists to prevent, and it was deleted (D22 hard-cut) rather than left as a policy branch
 * that documents a hand-off which never happens.
 *
 * Do not re-add it without first measuring that it changes a count.
 */
internal enum class ReconnectRungFailureSource {
    /** The auto-reconnect ladder's own rung dial failed retryably (#1328). */
    Ladder,

    /** A passive-disconnect grace cycle failed to heal (#1610 Q3, #1539). */
    PassiveGraceLoop,
}

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
        // Issue #1328 (S5): a reveal is the authoritative "we ARE connected" moment. If a
        // prior failed attempt for this target left the controller in a TERMINAL state
        // (Unreachable/Gone), that state ABSORBS TransportLive/SeedLanded — so promoting
        // would leave the controller wrongly Unreachable while the pane is live on screen
        // (a stuck-Failed band the deleted projection over-exhaust guard used to mask).
        // A successful reveal RESURRECTS it: re-Enter resets it onto the live path first.
        val state = controller.state.value
        if (state is ConnectionState.Unreachable || state is ConnectionState.Gone) {
            controller.submit(ConnectionEvent.Enter(host, targetId))
        }
        ensureTargeting(host, targetId)
        promoteToLive(host, targetId)
    }

    /**
     * INTENT: ENTER the silent-recovery ladder (idempotently). Drives a live-ish
     * controller into [ConnectionState.Reattaching] so the displayed status becomes the
     * calm Reconnecting band, but does NOT advance the numbered attempt counter — issue
     * #1328 (S5) made the [ConnectionController] the SINGLE reconnect counter, so once it
     * is already Reattaching/Reconnecting this is a NO-OP. Attempt ADVANCEMENT is owned
     * solely by the reconnect effect via [advanceReconnectLadder]; this entrypoint (the
     * inline-transition mirror in `driveControllerIntent`) must never double-count.
     */
    fun escalateReconnecting(host: HostKey, targetId: SessionId) {
        ensureTargeting(host, targetId)
        val state = controller.state.value
        if (state is ConnectionState.Reattaching || state is ConnectionState.Reconnecting) return
        controller.submit(ConnectionEvent.TransportDropped("reconnect"))
    }

    /**
     * Issue #1328 (S5): install the reconnect backoff ladder into the controller — the
     * SINGLE source of the attempt budget + per-attempt backoff. Called by the VM effect
     * from its `autoReconnectDelaysMs` before it walks the ladder.
     */
    fun setReconnectLadder(delaysMs: List<Long>) {
        controller.setReconnectLadder(delaysMs)
    }

    /**
     * Issue #1328 (S5): the reconnect effect ENTERS the numbered ladder (attempt 1).
     * Arms the controller's SINGLE churn-surviving counter and moves the tracked target
     * to [ConnectionState.Reconnecting] attempt 1 via [ConnectionEvent.ReconnectLadderEntered]
     * — one honest intent, not a stack of synthetic drops.
     */
    fun enterReconnectLadder(host: HostKey, targetId: SessionId) {
        ensureTargeting(host, targetId)
        controller.submit(ConnectionEvent.ReconnectLadderEntered)
    }

    /**
     * Issue #1328 (S5): report that ONE reconnect ladder rung's real dial failed
     * (retryably). The reducer advances the single churn-surviving counter and re-asserts
     * [ConnectionState.Reconnecting] at the next attempt — even from the transient
     * Reattaching/Live/Attaching state the just-failed dial churned to — or, at the ladder
     * budget, decides exhaustion itself → [ConnectionState.Unreachable]. The VM never
     * counts a parallel ladder.
     */
    internal fun reconnectRungFailed(source: ReconnectRungFailureSource) {
        reconnectRungFailedCounts[source] = (reconnectRungFailedCounts[source] ?: 0) + 1
        controller.submit(ConnectionEvent.ReconnectFailed)
    }

    /**
     * Issue #1539/#1610 (Q3) test seam: how many rung failures each reporter has submitted to
     * the controller. Additive and production-neutral (a counter only).
     *
     * Counted PER SOURCE because the #1328 one-reporter contract is a relationship between the
     * two reporters, not a property of either alone. A bare total cannot tell the intended
     * "the loop fell silent because the ladder took over reporting" from the failure mode
     * "the loop was silently muzzled and nobody reports" — both look like a number that stops
     * climbing. Per source, the hand-off is directly observable: the loop stops AND the ladder
     * starts.
     *
     * What this counts is the SUBMISSION, which is the reporters' contract. The controller-side
     * question — whether the resulting attempt then SURVIVES to escalate/terminate — is #1633's
     * (a `TransportLive` on a cycle whose dial succeeded currently wipes the walk), so a test
     * that asserted the observable `Reconnecting.attempt` here would be asserting #1633's
     * behaviour through this slice, and would fail for reasons this slice does not own.
     */
    @androidx.annotation.VisibleForTesting
    internal val reconnectRungFailedCounts: MutableMap<ReconnectRungFailureSource, Int> =
        mutableMapOf()

    @androidx.annotation.VisibleForTesting
    internal fun reconnectRungFailedCount(source: ReconnectRungFailureSource): Int =
        reconnectRungFailedCounts[source] ?: 0

    /** INTENT: the honest give-up. The reconnect effect hit a non-retryable failure (or an
     *  explicit abort) — submit [ConnectionEvent.ReconnectGaveUp] so the reducer surfaces
     *  [ConnectionState.Unreachable]. NOT the exhaustion path — the reducer decides
     *  exhaustion itself in [advanceReconnectLadder] (#1328). */
    fun escalateUnreachable() {
        controller.submit(ConnectionEvent.ReconnectGaveUp)
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
            is ConnectionState.NetworkLossSuspended -> "Connected"
            is ConnectionState.Reattaching -> "Reconnecting"
            is ConnectionState.Reconnecting -> "Reconnecting"
            is ConnectionState.Gone -> "Failed"
            is ConnectionState.Unreachable -> "Failed"
        }
    }
}
