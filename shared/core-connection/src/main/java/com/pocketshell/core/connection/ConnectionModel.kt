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
 * TargetGone/SeedLanded are feedback from the ports.
 */
sealed interface ConnectionEvent {
    /** User navigated to a host/session — a genuine (possibly cold) open. */
    data class Enter(val host: HostKey, val targetId: SessionId) : ConnectionEvent

    /** Same-host fast switch to a different session (select-window + reseed). */
    data class Switch(val targetId: SessionId) : ConnectionEvent

    /** App moved to foreground. */
    data object Foreground : ConnectionEvent

    /** App moved to background. */
    data object Background : ConnectionEvent

    /**
     * Transport dropped: `TmuxClient.disconnected == true` or lease `Closed`.
     * `reason` is the human-readable cause for logging (never surfaced as a
     * scary band on its own — heal first).
     */
    data class TransportDropped(val reason: String) : ConnectionEvent

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

    /** Target session was deleted elsewhere (#666). Must NOT resurrect it. */
    data class TargetGone(val targetId: SessionId) : ConnectionEvent

    /** A capture completed for ([targetId], [paneId]) — feeds the reseed gate. */
    data class SeedLanded(val targetId: SessionId, val paneId: String) : ConnectionEvent
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
    data class Attaching(val host: HostKey, val targetId: SessionId) : ConnectionState

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
     * Foreground within grace OR a transient `disconnected` drop: heal-and-retry
     * silently, content preserved. NO error band.
     */
    data class Reattaching(val host: HostKey, val targetId: SessionId) : ConnectionState

    /**
     * Beyond grace / heal exhausted: silent auto-reconnect + reseed. Brief
     * loading only, NO manual "Tap Reconnect".
     */
    data class Reconnecting(
        val host: HostKey,
        val targetId: SessionId,
        val attempt: Int,
    ) : ConnectionState

    /**
     * Target session deleted elsewhere (#666 contract). The controller must NOT
     * issue an attach-create (`new-session -A`) from here.
     */
    data class Gone(val host: HostKey, val targetId: SessionId) : ConnectionState

    /** The ONLY honest error state — only after auto-retry truly exhausts. */
    data class Unreachable(val host: HostKey, val targetId: SessionId) : ConnectionState
}
