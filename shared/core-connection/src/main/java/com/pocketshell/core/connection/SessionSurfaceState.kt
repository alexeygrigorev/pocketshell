package com.pocketshell.core.connection

/**
 * Slice S3 (epic #1331, issue #1326): the SINGLE view-facing session state. The
 * session screen renders THREE regions — the header status pill, the surface
 * (terminal / loader / calm-failure placeholder), and the under-header band — and
 * before S3 each read a DIFFERENT source (`revealState`, `displayConnectionStatus`,
 * the legacy `_switchHidesTerminal` flag), so contradictory screens ("Disconnected"
 * pill + "Attaching…" spinner + a raw exception) were *representable* and recurred
 * (#641, #750×4, #1185, #1321).
 *
 * [SessionSurfaceState] fuses the id-keyed [RevealState] (the SURFACE authority —
 * it owns the drop-by-id rule, the hard-failure and the within-grace live-frame
 * hold) with the [ConnectionPhase] projected from the connection status (the PILL /
 * progress payload — host/port/user, reconnect attempt) into ONE coherent state.
 * The pill, the surface and the band all derive from a single `when (state)`, so a
 * contradictory (pill, surface, band) triple is no longer type-representable.
 *
 * This is the VIEW-axis consolidation only (S3). It deliberately does NOT collapse
 * the parallel projection authority (that is S7 / #766) — the inline machine keeps
 * feeding the [ConnectionPhase]; S3 just makes the RENDER read one state.
 */
sealed interface SessionSurfaceState {
    /** Before the first navigation — no target, empty surface. */
    data object Idle : SessionSurfaceState

    /** Nav arrived; nothing connecting yet. Surface = loader, pill = "Connecting". */
    data class Navigating(val targetId: SessionId, val targetName: String) : SessionSurfaceState

    /** A cold first-connect is in flight. Surface = centered loader, pill = "Reconnecting"/amber. */
    data class Connecting(
        val targetId: SessionId,
        val targetName: String,
        val host: String,
        val port: Int,
        val user: String,
    ) : SessionSurfaceState

    /**
     * A warm same-host switch / a Live lifecycle whose active pane is not yet
     * seeded — the terminal is held behind the centered "Attaching…" loader while
     * the pill stays green (the session is up; we are only revealing its pane).
     */
    data class Attaching(
        val targetId: SessionId,
        val targetName: String,
        val host: String,
        val port: Int,
        val user: String,
    ) : SessionSurfaceState

    /** A beyond-grace reconnect is in flight. Surface = centered hold, pill = "Reconnecting". */
    data class Reconnecting(
        val targetId: SessionId,
        val targetName: String,
        val host: String,
        val port: Int,
        val user: String,
        val attempt: Int,
        val maxAttempts: Int,
        val retryDelayMs: Long,
    ) : SessionSurfaceState

    /** The active pane is seeded for this target — the terminal is shown, pill hidden. */
    data class Live(
        val targetId: SessionId,
        val targetName: String,
        val panes: List<Seed>,
        val agentName: String? = null,
    ) : SessionSurfaceState

    /** The target session was deleted elsewhere (#666). Calm failure surface + "Disconnected" pill. */
    data class Gone(val targetId: SessionId, val targetName: String) : SessionSurfaceState

    /**
     * A settled honest failure. Carries the TYPED [FailureReason] — never a raw
     * exception string (#1326 AC-3). Renders the calm failure placeholder (NO
     * spinner), the "Disconnected" pill and the single "Tap to reconnect" band, all
     * in agreement.
     */
    data class Failed(
        val targetId: SessionId,
        val targetName: String,
        val reason: FailureReason,
    ) : SessionSurfaceState
}

/**
 * The connection-projection payload [sessionSurfaceState] needs, decoupled from the
 * app's `TmuxSessionViewModel.ConnectionStatus` (which lives in the app module and
 * carries the same host/port/user + reconnect payload). The screen maps its
 * `ConnectionStatus` to a [ConnectionPhase] at the single fusion point.
 */
sealed interface ConnectionPhase {
    data object Idle : ConnectionPhase
    data class Connecting(val host: String, val port: Int, val user: String) : ConnectionPhase

    /** A warm same-host switch (`ConnectionStatus.Switching`). */
    data class Warm(val host: String, val port: Int, val user: String) : ConnectionPhase

    /** The transport is live (`ConnectionStatus.Connected`). */
    data class Live(val host: String, val port: Int, val user: String) : ConnectionPhase

    data class Reconnecting(
        val host: String,
        val port: Int,
        val user: String,
        val attempt: Int,
        val maxAttempts: Int,
        val retryDelayMs: Long,
    ) : ConnectionPhase

    /** A settled `ConnectionStatus.Failed`. The typed reason flows via [RevealState.Error]. */
    data object Failed : ConnectionPhase
}

/**
 * Fuse the id-keyed [reveal] surface state with the [phase] pill/progress payload
 * into the SINGLE [SessionSurfaceState] the screen renders every region from.
 *
 * The reveal machine is the SURFACE authority: a [RevealState.Live]/[Gone]/[Error]
 * dominates (terminal / gone card / calm failure), and its within-grace live-frame
 * hold (#685/#1098) makes the surface stay [SessionSurfaceState.Live] with NO
 * overlay — the load-bearing suppression. Only the *loading* reveal states
 * ([Navigating]/[Seeding], and the never-emitted [Error]`(retrying = true)`) defer
 * to [phase] for their flavor (cold Connecting vs warm Attaching vs Reconnecting),
 * so the pill matches the surface hold.
 *
 * [targetId] guards the defensive case where the reveal machine holds a
 * [RevealState.Live] for a target the screen is no longer keyed to (a superseded
 * frame): it is treated as a hold, never painted as this screen's live terminal.
 */
fun sessionSurfaceState(
    reveal: RevealState,
    phase: ConnectionPhase,
    targetId: SessionId?,
): SessionSurfaceState = when (reveal) {
    is RevealState.Idle -> holdFromPhase(targetId = null, targetName = null, phase = phase)

    is RevealState.Navigating -> holdFromPhase(reveal.targetId, reveal.targetName, phase)

    is RevealState.Seeding -> holdFromPhase(reveal.targetId, reveal.targetName, phase)

    is RevealState.Live ->
        if (targetId == null || reveal.targetId == targetId) {
            SessionSurfaceState.Live(reveal.targetId, reveal.targetName, reveal.panes, reveal.agentName)
        } else {
            // Superseded live frame for another target — never paint it here; hold.
            holdFromPhase(reveal.targetId, reveal.targetName, phase, allowFailed = false)
        }

    is RevealState.Gone -> SessionSurfaceState.Gone(reveal.targetId, reveal.targetName)

    is RevealState.Error ->
        if (reveal.retrying) {
            // The calm healing/"reconnecting" window (never emitted today) — a HOLD,
            // never the settled failure surface.
            holdFromPhase(reveal.targetId, reveal.targetName, phase, allowFailed = false)
        } else {
            SessionSurfaceState.Failed(reveal.targetId, reveal.targetName, reveal.reason)
        }
}

/**
 * Build the coherent HOLD (loading / settled-failure) surface state for a reveal
 * that is not yet Live, choosing the flavor from the [phase] pill payload so the
 * pill matches the surface. A settled [ConnectionPhase.Failed] while the terminal
 * is held resolves to the calm [SessionSurfaceState.Failed] (a generic retryable
 * reason — the typed reason for a *reveal-driven* failure flows through
 * [RevealState.Error] instead). [allowFailed] is false when the caller already
 * knows the reveal is not a failure (a held Live for another target, or the calm
 * heal window).
 */
private fun holdFromPhase(
    targetId: SessionId?,
    targetName: String?,
    phase: ConnectionPhase,
    allowFailed: Boolean = true,
): SessionSurfaceState {
    if (targetId == null || targetName == null) {
        // No target yet (pre-navigation) — nothing to hold.
        return SessionSurfaceState.Idle
    }
    return when (phase) {
        is ConnectionPhase.Idle -> SessionSurfaceState.Navigating(targetId, targetName)
        is ConnectionPhase.Connecting ->
            SessionSurfaceState.Connecting(targetId, targetName, phase.host, phase.port, phase.user)
        is ConnectionPhase.Warm ->
            SessionSurfaceState.Attaching(targetId, targetName, phase.host, phase.port, phase.user)
        is ConnectionPhase.Live ->
            // Live lifecycle but the active pane has not seeded yet (never-reveal-black):
            // hold behind the loader with a green (Connected) pill.
            SessionSurfaceState.Attaching(targetId, targetName, phase.host, phase.port, phase.user)
        is ConnectionPhase.Reconnecting ->
            SessionSurfaceState.Reconnecting(
                targetId, targetName, phase.host, phase.port, phase.user,
                phase.attempt, phase.maxAttempts, phase.retryDelayMs,
            )
        is ConnectionPhase.Failed ->
            if (allowFailed) {
                SessionSurfaceState.Failed(targetId, targetName, FailureReason.Unreachable(retryable = true))
            } else {
                SessionSurfaceState.Navigating(targetId, targetName)
            }
    }
}

/** The target id of a non-idle [SessionSurfaceState], or null for [SessionSurfaceState.Idle]. */
fun SessionSurfaceState.targetIdOrNull(): SessionId? = when (this) {
    is SessionSurfaceState.Idle -> null
    is SessionSurfaceState.Navigating -> targetId
    is SessionSurfaceState.Connecting -> targetId
    is SessionSurfaceState.Attaching -> targetId
    is SessionSurfaceState.Reconnecting -> targetId
    is SessionSurfaceState.Live -> targetId
    is SessionSurfaceState.Gone -> targetId
    is SessionSurfaceState.Failed -> targetId
}

/** The header name of a non-idle [SessionSurfaceState], or null for [SessionSurfaceState.Idle]. */
fun SessionSurfaceState.targetNameOrNull(): String? = when (this) {
    is SessionSurfaceState.Idle -> null
    is SessionSurfaceState.Navigating -> targetName
    is SessionSurfaceState.Connecting -> targetName
    is SessionSurfaceState.Attaching -> targetName
    is SessionSurfaceState.Reconnecting -> targetName
    is SessionSurfaceState.Live -> targetName
    is SessionSurfaceState.Gone -> targetName
    is SessionSurfaceState.Failed -> targetName
}

/**
 * True while the terminal is HELD behind a loader / failure placeholder — i.e. the
 * surface is anything but the live terminal. Supersedes the deleted
 * `effectiveHidesTerminal` / `revealHoldsTerminal` screen signal (#1326 AC-6):
 * derived from the ONE state, never from `revealState`/`_switchHidesTerminal`.
 */
val SessionSurfaceState.terminalHeld: Boolean
    get() = this !is SessionSurfaceState.Live

/**
 * True when the surface paints the CALM failure placeholder ([Gone]/[Failed]) — a
 * settled failure that must render distinctly from the "Attaching…" hold: NO
 * spinner, agreeing with the "Disconnected" pill and the "Tap to reconnect" band
 * (the #1321/#1322 contradiction killer). Supersedes the old
 * `surfaceShowsCalmFailure(held, hardFailure, status)` — now a pure read of the one
 * state (the `status is Failed` arm is folded into [SessionSurfaceState.Failed]).
 */
val SessionSurfaceState.showsCalmFailure: Boolean
    get() = this is SessionSurfaceState.Gone || this is SessionSurfaceState.Failed

/**
 * True when the surface paints a centered LOADER spinner — the "Attaching…" hold
 * (terminal held) OR the "waiting for tmux panes…" ring (reveal live but no panes
 * yet, [panesEmpty]). A settled failure ([showsCalmFailure]) is never a loader.
 */
fun SessionSurfaceState.showsCenteredLoader(panesEmpty: Boolean): Boolean =
    !showsCalmFailure && (terminalHeld || panesEmpty)

/**
 * True whenever the surface owns the primary indicator (a loader OR the calm
 * failure placeholder), so the top connecting/reconnecting banner and the pull-box
 * spinner are the redundant duplicates and must be suppressed (the #750 single-
 * indicator gate). False only in the live-frame steady state.
 */
fun SessionSurfaceState.surfaceOwnsPrimary(panesEmpty: Boolean): Boolean =
    showsCalmFailure || showsCenteredLoader(panesEmpty)
