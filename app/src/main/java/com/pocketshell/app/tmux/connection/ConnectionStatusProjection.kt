package com.pocketshell.app.tmux.connection

import com.pocketshell.app.tmux.TmuxSessionViewModel.ConnectionStatus
import com.pocketshell.core.connection.ConnectionController
import com.pocketshell.core.connection.ConnectionState

/**
 * EPIC #687 slice 1c-iv-a — the PURE projection seam (extracted for #728).
 *
 * The view-facing [ConnectionStatus] the user SEES is projected from the SHADOW
 * [ConnectionController]'s core [ConnectionState]. The coarse SHAPE comes from the
 * controller; the display PAYLOAD (host/port/user, reconnect attempt/reason, the
 * failure message) is read from the INLINE transition the VM constructed for this
 * step, because the controller's core state carries opaque `HostKey`/`SessionId`
 * (not the host/port/user the view renders).
 *
 * ## Why this object exists (#728)
 * The projection logic used to live as private methods on `TmuxSessionViewModel`
 * with no direct unit test — exactly the decision-logic class that produced
 * #685 / #662 / #721. This object is a **behavior-identical** extraction: the VM
 * delegates to [project], and the truth-table is pinned by
 * `ConnectionStatusProjectionTest`. The 1c-iv-b hard-cut refactors it green-to-green.
 *
 * ## The pure carrier
 * The projection's display payload is fully captured by the INLINE-projected
 * [ConnectionStatus] (the VM's `connectionStatusFor(inlineState)` 1:1 map): every
 * field the old helpers read off the private inline state — host/port/user,
 * attempt/maxAttempts/retryDelayMs/reason, the failure message — is present in that
 * projected status. The ONE thing the inline-projected status can't carry is the
 * host/port/user fallback the old `hostPortUserFor` derived for a payload-less
 * controller-terminal/idle case (it read the VM's active/connecting target), so
 * that [HostPortUser] is computed by the VM and passed in. No inline-state type
 * leaks across this seam.
 *
 * §1 seam table: `Connecting→Connecting`, `Attaching→Switching`, `Live→Connected`,
 * `Backgrounded→Connected` (inline-follow), `Reattaching/Reconnecting→Reconnecting`,
 * `Gone/Unreachable→Failed`. The two approved #685 divergences are intrinsic to the
 * controller and surface here as the calmer status: a recoverable drop reads
 * `Reconnecting` (the controller is `Reattaching`/`Reconnecting`, not the inline
 * `Unreachable`), and a within-grace foreground reads `Connected` (the controller
 * stays `Live`). #720: a controller `Unreachable` projects to a [ConnectionStatus.Failed]
 * rendered by the calm tappable "Tap to reconnect" band — never raw SSH text.
 */
internal object ConnectionStatusProjection {

    /** Host/port/user for the view — the VM derives this (from the inline payload,
     *  falling back to the active/connecting target, then blanks). Passed in so the
     *  projection stays pure. */
    data class HostPortUser(val host: String, val port: Int, val user: String)

    /**
     * Project the controller [controllerState] onto the displayed [ConnectionStatus].
     *
     * @param controllerState the SHADOW controller's core state (the SHAPE source).
     * @param inlineStatus the INLINE-projected view status for this transition
     *   (`connectionStatusFor(inlineState)`) — carries the display payload.
     * @param hpu the host/port/user the view renders, derived by the VM.
     */
    fun project(
        controllerState: ConnectionState,
        inlineStatus: ConnectionStatus,
        hpu: HostPortUser,
    ): ConnectionStatus {
        // The controller drives the status ONLY while it is tracking a target. When
        // the controller has no target yet (Idle) but the inline path has moved on,
        // there is no controller state to project — this happens only for degenerate,
        // target-less test seams (e.g. attachClientForTest, which fakes a Live with no
        // ConnectionTarget so the bridge has nothing to track). Fall back to the inline
        // projection there so those seams stay byte-identical. In every real open /
        // switch / recovery path the VM has set a target, so the controller is tracking
        // and is the single source.
        if (controllerState is ConnectionState.Idle && inlineStatus !is ConnectionStatus.Idle) {
            return inlineStatus
        }
        return when (controllerState) {
            is ConnectionState.Idle -> ConnectionStatus.Idle
            // OPEN states: the cold-dial (`Connecting`, overlay) vs warm-switch
            // (`Attaching`/`Switching`, no overlay) distinction is the INLINE open
            // path's authoritative decision (#437), NOT one of the two approved #685
            // divergences — which all live in the drop/foreground RECOVERY paths. The
            // controller's own `isWarm` predicate can read a different cold/warm signal
            // at the exact open instant (the lease may already be in liveLeaseKeys), so
            // for the pre-reveal Connecting/Attaching pair we follow the inline open
            // state. Everything from Live onward (and all recovery) is controller-driven.
            is ConnectionState.Connecting,
            is ConnectionState.Attaching ->
                when (inlineStatus) {
                    is ConnectionStatus.Connecting ->
                        ConnectionStatus.Connecting(hpu.host, hpu.port, hpu.user)
                    is ConnectionStatus.Switching ->
                        ConnectionStatus.Switching(hpu.host, hpu.port, hpu.user)
                    // The inline state is past the open (e.g. a recovery transition the
                    // controller is still walking to Live): honor the controller's
                    // pre-reveal shape.
                    else -> if (controllerState is ConnectionState.Connecting) {
                        ConnectionStatus.Connecting(hpu.host, hpu.port, hpu.user)
                    } else {
                        ConnectionStatus.Switching(hpu.host, hpu.port, hpu.user)
                    }
                }
            is ConnectionState.Live ->
                // #178 (NEW path): a `Live` controller normally reveals `Connected`.
                // The ONE exception is the dead-session-mid-switch fallback: the
                // fast-switch reused-SSH lease turned out dead, so the VM dropped the
                // now-stale frame, escalated the INLINE open to `Connecting`, and is
                // re-running a genuine FRESH handshake. The fresh transport's
                // `TransportLive` promotes the controller's silent-heal ladder
                // (`Reattaching`/`Reconnecting`) straight to `Live` WITHOUT a seed —
                // but there is no frame on screen (we blanked it), so revealing
                // `Connected` here would paint a dead/blank pane (the #178 violation).
                // The inline open path is still pre-reveal (`Connecting`) and owns the
                // authoritative "panes are seeded, safe to reveal" gate
                // (`awaitPanesReadyForAttach`/`awaitActivePaneSeededOrLoading`); follow
                // it so the full-screen Connecting overlay stays up until those panes
                // land. This is the OPEN-direction sibling of [terminalOrInlineStatus]'s
                // "don't show a premature scary Failed while the inline ladder is still
                // recovering" guard — here: don't show a premature blank Connected while
                // the inline open is still handshaking. Every normal cold open reaches
                // controller `Live` only via the SeedLanded feed (which lands AT the
                // inline reveal), so the inline status is already past `Connecting`
                // there and this guard is a no-op for them.
                if (inlineStatus is ConnectionStatus.Connecting) {
                    ConnectionStatus.Connecting(hpu.host, hpu.port, hpu.user)
                } else {
                    ConnectionStatus.Connected(hpu.host, hpu.port, hpu.user)
                }
            // BACKGROUNDED: the displayed status while the app is not visible is the
            // INLINE path's decision (keep the prior status, OR a paused
            // auto-reconnect → the manual-retry Failed band). This is NOT one of the
            // two approved #685 divergences (those are within-grace FOREGROUND and the
            // live-channel drop), and the user cannot see the status while backgrounded
            // anyway — so we follow the inline state to keep the backgrounded surface
            // byte-identical. The controller's grace deadline still governs the next
            // foreground reattach-vs-reconnect decision.
            is ConnectionState.Backgrounded -> inlineStatus
            // APPROVED #685 divergence #1 (silent recovery): a recoverable drop leaves
            // the controller Reattaching/Reconnecting → a CALM Reconnecting band, NOT
            // the scary Failed. The display payload (attempt/reason) is the inline
            // reconnect bookkeeping.
            is ConnectionState.Reattaching ->
                reconnectingStatusFor(inlineStatus, hpu)
            is ConnectionState.Reconnecting ->
                reconnectingStatusFor(inlineStatus, hpu)
            is ConnectionState.Gone ->
                terminalOrInlineStatus(inlineStatus, hpu)
            is ConnectionState.Unreachable ->
                // #720: the ONLY honest error. The CAUSE message is preserved (it is
                // already a curated, user-facing string — never raw
                // `TransportException`/SSH text); the CALM, tappable "Tap to reconnect"
                // affordance + the dropped "Open the session again" instruction live in
                // the screen band ([FailedConnectionRow]) and the calm
                // [ConnectionIndicator.Unreachable] indicator. "Failed only after retries
                // truly exhaust" tracks the INLINE ladder (see [terminalOrInlineStatus]).
                terminalOrInlineStatus(inlineStatus, hpu)
        }
    }

    /**
     * Project a controller TERMINAL state (Unreachable/Gone). The brief's approved
     * #685 change is "Failed/Unreachable only AFTER retries truly exhaust". The
     * authoritative "retries exhausted" signal is the INLINE reconnect ladder (the
     * effect machinery 1c-iv-b owns) — the controller's own drop-ladder counter can
     * over-exhaust because the bridge mirrors each inline reconnect transition as a
     * drop. So: surface Failed ONLY when the inline state is ALSO terminal
     * (the inline-projected status is `Failed`). While the inline ladder is still
     * recovering (the inline status is `Reconnecting`) OR back live, follow the inline
     * status — a calm Reconnecting band or Connected, never a premature scary Failed.
     */
    private fun terminalOrInlineStatus(
        inlineStatus: ConnectionStatus,
        hpu: HostPortUser,
    ): ConnectionStatus =
        when (inlineStatus) {
            is ConnectionStatus.Failed ->
                ConnectionStatus.Failed(inlineStatus.message)
            is ConnectionStatus.Reconnecting ->
                reconnectingStatusFor(inlineStatus, hpu)
            // Inline already recovered (Connected) or is mid-open: the controller
            // over-exhausted; follow the inline truth, not a premature Failed.
            else -> inlineStatus
        }

    /** Build the view [ConnectionStatus.Reconnecting], preserving the inline
     *  reconnect payload (attempt/maxAttempts/retryDelayMs/reason) when the inline
     *  status is itself a reconnect; otherwise (the approved recoverable-drop
     *  divergence, where the inline status is `Failed`/`Connected`) a calm default. */
    private fun reconnectingStatusFor(
        inlineStatus: ConnectionStatus,
        hpu: HostPortUser,
    ): ConnectionStatus.Reconnecting {
        val inlineReconnect = inlineStatus as? ConnectionStatus.Reconnecting
        return ConnectionStatus.Reconnecting(
            host = hpu.host,
            port = hpu.port,
            user = hpu.user,
            attempt = inlineReconnect?.attempt ?: 1,
            maxAttempts = inlineReconnect?.maxAttempts
                ?: ConnectionController.DEFAULT_MAX_RECONNECT_ATTEMPTS,
            retryDelayMs = inlineReconnect?.retryDelayMs ?: 0L,
            reason = inlineReconnect?.reason ?: "Reconnecting…",
        )
    }
}
