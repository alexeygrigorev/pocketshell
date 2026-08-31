package com.pocketshell.core.connection

/**
 * EPIC #687 Phase-2 connection-core seam types.
 *
 * These are the ONE definition of the contract both #687 (transport lifecycle)
 * and #686 (view identity / id-tagged reveal) depend on. They are pure Kotlin:
 * no `android.*`, no Compose, no coroutines IO. The companion [ConnectionController]
 * reduces [ConnectionEvent]s into [ConnectionState] transitions, emits id-tagged
 * [Seed]s, and exposes a [RevealDecision] gate.
 *
 * Design references (issue #687 comments): the Phase-0 state-machine spec and the
 * Phase-2 ConnectionController extraction plan; the #686 Phase-0 identity/reveal
 * spec for the id-tagged-seed seam.
 */

/**
 * The host a connection lives on. Opaque identity — the VM will mint this from
 * the existing `ConnectionTarget` host coordinates (hostId/host/port/user). The
 * lease + control channel are warm per host, so the host key is what the
 * [TransportPort.isWarm] predicate keys on.
 */
data class HostKey(val value: String)

/**
 * A stable target session id. This is the #686 seam: every non-idle
 * [ConnectionState] carries the [SessionId] it is for, every [Seed] is tagged
 * with the [SessionId] it belongs to, and the reducer drops any event whose
 * target id != the current target. The id disambiguates two attaches to the
 * same tmux session name, so a late seed from a superseded switch is droppable
 * by id rather than by racing a generation counter.
 */
data class SessionId(val value: String)

/**
 * Issue #2338: a record that the reveal reducer re-keyed the CURRENT session
 * from one [SessionId] to another without renavigating.
 *
 * Identity adoption happens when the first authoritative `list-panes` proves the
 * navigation id incomplete (name-only) or stale (an exact generation that is no
 * longer the live one). The reducer, the connection controller and every seed
 * move to [to] at that moment, but the SCREEN still holds the route id it was
 * composed with — so without this record the fused surface state sees
 * `reveal.targetId != screen targetId` and holds the terminal forever (the
 * #2338 "terminal never attaches on the 2nd+ launch" wedge).
 *
 * [from] is the identity the screen was navigated with, so a screen may follow
 * [to] only when its own route id equals [from]. Any other screen keeps its own
 * identity and stays behind the #686 stale-id fence.
 */
data class RevealIdentityAdoption(
    val from: SessionId,
    val to: SessionId,
)

/**
 * An id-tagged pane capture produced by the connection core. The screen (#686)
 * drops any [Seed] whose [targetId] != the current target, so a late seed from a
 * superseded switch never paints. `frame` is the captured pane content as an
 * opaque payload (the VM owns the actual terminal-byte type; the controller only
 * shuttles it tagged with identity).
 */
data class Seed(
    val targetId: SessionId,
    val paneId: String,
    val frame: String,
)

/**
 * The reveal/input gate the view renders from. `Hold` means "do not paint this
 * target's surface yet / keep input disabled" (navigating or seeding);
 * `Reveal` means "the active pane is confirmed seeded for this id — paint it and
 * enable input". Carrying the [SessionId] lets the screen assert it is revealing
 * the id it currently shows, never a superseded one.
 */
sealed interface RevealDecision {
    val targetId: SessionId?

    /** Nothing to reveal (Idle / no target). */
    data object None : RevealDecision {
        override val targetId: SessionId? = null
    }

    /** Target known but content not yet confirmed for it — keep the surface held. */
    data class Hold(override val targetId: SessionId) : RevealDecision

    /** Active pane confirmed seeded for [targetId] — reveal content, enable input. */
    data class Reveal(
        override val targetId: SessionId,
        val inputEnabled: Boolean = true,
    ) : RevealDecision
}

/**
 * Events the [ConnectionController] consumes. Foreground/Background,
 * TransportDropped/Live, and Enter/Switch are the only real-world inputs;
 * identity adoption, target-gone, attach-ready, and seed-landed events are
 * feedback from the ports.
 */
sealed interface ConnectionEvent {
    /** User navigated to a host/session — a genuine (possibly cold) open. */
    data class Enter(
        val host: HostKey,
        val targetId: SessionId,
        /** A verified dead lease requires a cold dial even if the warm snapshot is stale. */
        val forceCold: Boolean = false,
    ) : ConnectionEvent

    /** Same-host fast switch to a different session (select-window + reseed). */
    data class Switch(val targetId: SessionId) : ConnectionEvent

    /**
     * The pane listing enriched a name-only target with tmux's exact generation.
     * This is an identity handoff for the same runtime, not a new open/switch
     * intent; the reducer preserves the current lifecycle phase while changing
     * the id used by subsequent target-tagged feedback.
     */
    data class TargetIdentityAdopted(
        val from: SessionId,
        val to: SessionId,
    ) : ConnectionEvent

    /** App moved to foreground. */
    data object Foreground : ConnectionEvent

    /** App moved to background. */
    data object Background : ConnectionEvent

    /**
     * Transport dropped: `TmuxClient.disconnected == true` or lease `Closed`.
     *
     * Issue #1666 (D28 Layer 3): the drop carries a TYPED [DropCause], derived once at
     * the app's single close authority (`SelfInflictedClose`) off the reason the emitter
     * named. [ConnectionController.onTransportDropped] REFUSES to advance the episode when
     * the cause is [DropCause.SelfInflicted] — our own teardown echo is not remote death —
     * so a self-inflicted close can never storm-feed the ladder, by construction (defense
     * in depth beneath the #1643 driver filter). A [DropCause.RemoteFailure] /
     * [DropCause.KeepaliveDead] / [DropCause.Unknown] still walks the honest recovery
     * ladder. The cause is never surfaced as a scary band on its own — heal first.
     */
    data class TransportDropped(val cause: DropCause) : ConnectionEvent

    /** Transport is live again: lease `Connected` / `-CC` attached. */
    data object TransportLive : ConnectionEvent

    /**
     * The device network changed (Wi-Fi <-> cellular, AP handoff, etc.). The
     * #548 suppression contract lives here: ONLY a `validatedHandoff` (a real,
     * VALIDATED network-identity change — a genuinely different validated
     * network, not a transient blip or a re-validation of the same one) is
     * allowed to proactively silent-reconnect a live channel. A non-validated
     * change is a NO-OP: the live channel is left alone (sshj 15s x 4 keepalive
     * remains the sole death oracle), so a network blip never tears down and
     * re-dials a healthy channel.
     *
     * Like every recoverable transition, a validated handoff routes through the
     * SILENT [ConnectionState.Reconnecting] ladder — never a scary error band.
     * A `NetworkChanged` outside a live-ish state is ignored.
     */
    data class NetworkChanged(val validatedHandoff: Boolean) : ConnectionEvent

    /**
     * Device network connectivity was lost entirely. This is distinct from
     * [NetworkChanged]: there is no validated replacement network to redial onto,
     * so Slice S4 holds the existing lease/content in [ConnectionState.NetworkLossSuspended].
     *
     * The VM submits this after its network-loss debounce, so the controller owns the
     * typed hold rather than a second VM-only status mirror.
     */
    data object NetworkLost : ConnectionEvent

    /**
     * Device network connectivity was restored after [NetworkLost]. The controller
     * decides between ride-through and silent reconnect with its injected [LivenessPort].
     *
     * The controller resolves this through its injected [LivenessPort] and either
     * rides through to [ConnectionState.Live] or enters the reconnect ladder.
     */
    data object NetworkRestored : ConnectionEvent

    /** Target session was deleted elsewhere (#666). Must NOT resurrect it. */
    data class TargetGone(val targetId: SessionId) : ConnectionEvent

    /** A capture completed for ([targetId], [paneId]) — feeds the reseed gate. */
    data class SeedLanded(val targetId: SessionId, val paneId: String) : ConnectionEvent

    /**
     * The current tmux attach completed its control/list-panes readiness fence.
     * This is deliberately separate from [SeedLanded]: a blank or failed
     * capture can leave the reveal surface held while the transport is still
     * live and the connection status is already ready.
     */
    data class AttachReady(val targetId: SessionId, val paneId: String) : ConnectionEvent

    /**
     * The VM reconnect effect ENTERED its numbered ladder (attempt 1). The reducer
     * arms the SINGLE churn-surviving reconnect counter and moves any live-ish /
     * recovering state to [ConnectionState.Reconnecting] attempt 1 (issue #1328, S5).
     *
     * A dedicated intent (not a raw drop) so the effect's re-dial IO — which
     * transiently walks the controller through Connecting/Attaching/Live before an
     * attach may still fail — cannot reset the counter: the count lives in the
     * controller, decoupled from the transient [ConnectionState] the dial churns.
     */
    data object ReconnectLadderEntered : ConnectionEvent

    /**
     * One reconnect ladder RUNG's real dial failed (retryably). The reducer advances
     * the SINGLE churn-surviving counter and re-asserts [ConnectionState.Reconnecting]
     * at the new attempt — REGARDLESS of the transient state the just-failed dial left
     * behind (Reattaching/Live/Attaching) — or, once the counter passes the ladder
     * budget, decides exhaustion itself → [ConnectionState.Unreachable] (issue #1328).
     * The VM never counts a parallel ladder; it only reports "this rung failed".
     */
    data object ReconnectFailed : ConnectionEvent

    /**
     * The reconnect effect GAVE UP early — a non-retryable failure (bad auth,
     * unknown host, missing key) that waiting/retrying cannot fix (#440), or an
     * explicit abort. The reducer surfaces the honest [ConnectionState.Unreachable]
     * from any live-ish / recovering state.
     *
     * This is DISTINCT from ladder EXHAUSTION: when the reconnect budget runs out,
     * the reducer decides that itself — a [TransportDropped] that pushes
     * [ConnectionState.Reconnecting.attempt] past `maxAttempts` transitions to
     * [ConnectionState.Unreachable] (issue #1328, S5 single-ladder). The VM effect
     * never counts attempts; it only feeds honest drops and this abort signal.
     */
    data object ReconnectGaveUp : ConnectionEvent
}

/**
 * The connection lifecycle state machine. Every non-idle state carries the
 * target [SessionId] it is for (the #686 seam). There is exactly ONE honest
 * error state ([Unreachable]) and exactly ONE "gone" state ([Gone]); a transient
 * drop heals through [Reattaching] without a scary band, and beyond-grace
 * resume reconnects silently through [Reconnecting].
 */
sealed interface ConnectionState {
    /** No connection in progress. */
    data object Idle : ConnectionState

    /** Genuine cold dial; a full-screen overlay is allowed in the UI. */
    data class Connecting(val host: HostKey, val targetId: SessionId) : ConnectionState

    /**
     * Warm: lease is up (or in-flight); opening / `select-window` + seeding the
     * target pane. NO full-screen overlay (this is the old `Switching`).
     */
    data class Attaching(
        val host: HostKey,
        val targetId: SessionId,
        /** True only for an attach over an already-warm carrier. */
        val warm: Boolean = true,
        /** True when this attach is completing an existing recovery episode. */
        val recovering: Boolean = false,
    ) : ConnectionState

    /** Attached, input enabled. */
    data class Live(val host: HostKey, val targetId: SessionId) : ConnectionState

    /**
     * App not foreground; the control client is detached but the lease stays
     * warm for the grace window. NO timers run while here (D21) — the grace
     * deadline is a stored value compared on the next [ConnectionEvent.Foreground].
     */
    data class Backgrounded(
        val host: HostKey,
        val targetId: SessionId,
        val sinceMs: Long,
    ) : ConnectionState

    /**
     * The device network is down while a session was live. Hold the lease/content
     * without redialing or surfacing an honest error; probes/effects can suspend
     * until [ConnectionEvent.NetworkRestored] resolves the state.
     *
     * [ConnectionController.submit] enters this state only after the VM's debounced
     * loss observation, preserving the existing lease and content without a redial.
     */
    data class NetworkLossSuspended(
        val host: HostKey,
        val targetId: SessionId,
        val sinceMs: Long,
    ) : ConnectionState

    /**
     * Foreground within grace OR a transient `disconnected` drop: heal-and-retry
     * silently, content preserved. A replacement transport remains in this typed
     * recovery phase until target-tagged attach readiness/seed feedback promotes it
     * to [Live]. NO error band.
     */
    data class Reattaching(val host: HostKey, val targetId: SessionId) : ConnectionState

    /**
     * Beyond grace / heal exhausted: silent auto-reconnect + reseed. Brief
     * loading only, NO manual "Tap Reconnect".
     *
     * Issue #1328 (S5): this is the SINGLE reconnect-attempt counter and the SINGLE
     * exhaustion point. [attempt] is the 1-based attempt number, [maxAttempts] the
     * ladder budget, and [retryDelayMs] the backoff the VM effect waits before this
     * attempt's dial. The reducer owns every increment and the
     * `attempt > maxAttempts → Unreachable` decision; the VM never counts a parallel ladder.
     *
     * Issue #1654: [maxAttempts] defaults to the REAL ladder's length, not a flat 4. The old
     * default was half of the bug — a `Reconnecting` built without an explicit budget
     * announced "4" while the ladder says 8. The controller always builds this from its
     * construction-time ladder ([ConnectionController.DEFAULT_RECONNECT_LADDER_MS]); the
     * default only serves the 3-arg test call sites, and it must agree with production.
     */
    data class Reconnecting(
        val host: HostKey,
        val targetId: SessionId,
        val attempt: Int,
        val maxAttempts: Int = ConnectionController.DEFAULT_RECONNECT_LADDER_MS.size,
        val retryDelayMs: Long = 0L,
    ) : ConnectionState

    /**
     * Target session deleted elsewhere (#666 contract). The controller must NOT
     * issue an attach-create (`new-session -A`) from here.
     */
    data class Gone(val host: HostKey, val targetId: SessionId) : ConnectionState

    /** The ONLY honest error state — only after auto-retry truly exhausts. */
    data class Unreachable(val host: HostKey, val targetId: SessionId) : ConnectionState
}
