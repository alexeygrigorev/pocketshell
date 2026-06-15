package com.pocketshell.app.tmux.connection

import com.pocketshell.app.tmux.TmuxSessionViewModel.ConnectionStatus
import com.pocketshell.core.connection.ConnectionController
import com.pocketshell.core.connection.ConnectionState
import com.pocketshell.core.connection.HostKey
import com.pocketshell.core.connection.SessionId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * EPIC #687 slice 1c-iv-a CHARACTERIZATION TEST (#728): pin the pure projection seam
 * [ConnectionStatusProjection.project] — the SINGLE source of the view-facing
 * [ConnectionStatus] the user SEES (it is fed the SHADOW controller's core
 * [ConnectionState] for SHAPE plus the inline-projected display payload).
 *
 * This is the decision-logic class that produced #685 / #662 / #721. Before #728 it
 * lived as private methods on `TmuxSessionViewModel` with no direct unit test. These
 * tests are a pure truth-table (NO emulator, NO coroutines) that:
 *
 *  1. Cover EVERY controller [ConnectionState] value (Idle, Connecting, Attaching,
 *     Live, Backgrounded, Reattaching, Reconnecting, Gone, Unreachable).
 *  2. Exercise the `terminalOrInlineStatus` terminal/non-terminal guard BOTH ways
 *     (a controller-terminal Gone/Unreachable while the inline ladder is STILL
 *     recovering → calm `Reconnecting`, vs the inline ladder having TRULY exhausted
 *     → `Failed`; plus the over-exhaust case where inline already recovered).
 *  3. Pin the TWO approved #685 behaviors:
 *       (i)  a recoverable drop → CALM `Reconnecting`, NOT the scary `Failed`
 *            (the controller is `Reattaching`/`Reconnecting`, the inline state is
 *            `Failed`).
 *       (ii) a within-grace foreground → `Connected` (the controller stays `Live`).
 *
 * The seam is BEHAVIOR-IDENTICAL to the old inline `connectionStatusForController`
 * body; the VM now delegates to it. If the truth-table here ever drifts, a future
 * refactor of the seam broke a flagged behavior — that is exactly what this test
 * guards against.
 */
class ConnectionStatusProjectionTest {

    private val host = HostKey("example.com")
    private val sid = SessionId("sess-1")

    // --- inline-projected display-payload fixtures (the seam's `inlineStatus` input).
    // These mirror `TmuxSessionViewModel.connectionStatusFor(inlineState)` 1:1. ---
    private val inlineIdle = ConnectionStatus.Idle
    private val inlineConnecting = ConnectionStatus.Connecting(HOST, PORT, USER)
    private val inlineSwitching = ConnectionStatus.Switching(HOST, PORT, USER)
    private val inlineConnected = ConnectionStatus.Connected(HOST, PORT, USER)
    private val inlineReconnecting = ConnectionStatus.Reconnecting(
        host = HOST,
        port = PORT,
        user = USER,
        attempt = 3,
        maxAttempts = 7,
        retryDelayMs = 1_500L,
        reason = "Network changed",
    )
    private val inlineFailed = ConnectionStatus.Failed("Host unreachable")

    private val hpu = ConnectionStatusProjection.HostPortUser(HOST, PORT, USER)

    private fun project(
        controllerState: ConnectionState,
        inlineStatus: ConnectionStatus,
    ): ConnectionStatus =
        ConnectionStatusProjection.project(controllerState, inlineStatus, hpu)

    // ---------------------------------------------------------------------------
    // §1 SEAM TABLE — every controller ConnectionState value
    // ---------------------------------------------------------------------------

    @Test
    fun idleController_withIdleInline_projectsIdle() {
        assertEquals(
            ConnectionStatus.Idle,
            project(ConnectionState.Idle, inlineIdle),
        )
    }

    @Test
    fun idleController_butInlineMovedOn_followsInline_forTargetlessSeams() {
        // attachClientForTest fakes an inline Live with NO ConnectionTarget, so the
        // controller has nothing to track and stays Idle. The seam must fall back to
        // the inline projection there so those degenerate seams stay byte-identical.
        assertEquals(
            inlineConnected,
            project(ConnectionState.Idle, inlineConnected),
        )
    }

    @Test
    fun connectingController_withConnectingInline_projectsConnecting() {
        assertEquals(
            ConnectionStatus.Connecting(HOST, PORT, USER),
            project(ConnectionState.Connecting(host, sid), inlineConnecting),
        )
    }

    @Test
    fun connectingController_withSwitchingInline_followsInlineSwitching() {
        // The cold/warm open distinction is the INLINE open path's authoritative call
        // (#437); the controller follows the inline open state for the pre-reveal pair.
        assertEquals(
            ConnectionStatus.Switching(HOST, PORT, USER),
            project(ConnectionState.Connecting(host, sid), inlineSwitching),
        )
    }

    @Test
    fun connectingController_withPastOpenInline_honorsControllerShape() {
        // Inline is past the open (e.g. mid-recovery): honor the controller's
        // pre-reveal Connecting shape.
        assertEquals(
            ConnectionStatus.Connecting(HOST, PORT, USER),
            project(ConnectionState.Connecting(host, sid), inlineReconnecting),
        )
    }

    @Test
    fun attachingController_withSwitchingInline_projectsSwitching() {
        assertEquals(
            ConnectionStatus.Switching(HOST, PORT, USER),
            project(ConnectionState.Attaching(host, sid), inlineSwitching),
        )
    }

    @Test
    fun attachingController_withConnectingInline_followsInlineConnecting() {
        assertEquals(
            ConnectionStatus.Connecting(HOST, PORT, USER),
            project(ConnectionState.Attaching(host, sid), inlineConnecting),
        )
    }

    @Test
    fun attachingController_withPastOpenInline_honorsControllerSwitchingShape() {
        assertEquals(
            ConnectionStatus.Switching(HOST, PORT, USER),
            project(ConnectionState.Attaching(host, sid), inlineReconnecting),
        )
    }

    @Test
    fun liveController_projectsConnected() {
        assertEquals(
            ConnectionStatus.Connected(HOST, PORT, USER),
            project(ConnectionState.Live(host, sid), inlineConnected),
        )
    }

    @Test
    fun liveController_withPreRevealConnectingInline_followsInlineConnecting() {
        // #178 (NEW path) dead-session-mid-switch guard: the fast-switch reused-SSH
        // lease was dead, so the VM dropped the stale frame, escalated the INLINE open
        // to `Connecting`, and is re-running a genuine FRESH handshake. The fresh
        // transport's `TransportLive` promotes the controller's silent-heal ladder
        // straight to `Live` WITHOUT a seed — but the frame is blanked, so projecting
        // `Connected` here would reveal a dead/blank pane. While the inline open is
        // still pre-reveal (`Connecting`), the full-screen Connecting overlay must stay
        // up; the seam follows the inline `Connecting` until the inline reveal gate
        // (panes seeded) flips the inline status to `Connected`/`Live`. This is the
        // OPEN-direction sibling of the terminal-state "no premature Failed" guard.
        assertEquals(
            ConnectionStatus.Connecting(HOST, PORT, USER),
            project(ConnectionState.Live(host, sid), inlineConnecting),
        )
    }

    @Test
    fun backgroundedController_followsInline() {
        // While backgrounded the user can't see the status; follow the inline state to
        // keep the backgrounded surface byte-identical.
        assertEquals(
            inlineConnected,
            project(ConnectionState.Backgrounded(host, sid, sinceMs = 0L), inlineConnected),
        )
    }

    @Test
    fun backgroundedController_pausedAutoReconnect_followsInlineFailedBand() {
        assertEquals(
            inlineFailed,
            project(ConnectionState.Backgrounded(host, sid, sinceMs = 0L), inlineFailed),
        )
    }

    // ---------------------------------------------------------------------------
    // Recovery states → calm Reconnecting (payload preserved when inline carries it)
    // ---------------------------------------------------------------------------

    @Test
    fun reattachingController_withReconnectingInline_preservesReconnectPayload() {
        assertEquals(
            ConnectionStatus.Reconnecting(
                host = HOST,
                port = PORT,
                user = USER,
                attempt = 3,
                maxAttempts = 7,
                retryDelayMs = 1_500L,
                reason = "Network changed",
            ),
            project(ConnectionState.Reattaching(host, sid), inlineReconnecting),
        )
    }

    @Test
    fun reconnectingController_withReconnectingInline_preservesReconnectPayload() {
        assertEquals(
            ConnectionStatus.Reconnecting(
                host = HOST,
                port = PORT,
                user = USER,
                attempt = 3,
                maxAttempts = 7,
                retryDelayMs = 1_500L,
                reason = "Network changed",
            ),
            project(ConnectionState.Reconnecting(host, sid, attempt = 3), inlineReconnecting),
        )
    }

    // ---------------------------------------------------------------------------
    // APPROVED #685 behavior #1: recoverable drop → CALM Reconnecting (not Failed)
    // ---------------------------------------------------------------------------

    @Test
    fun recoverableDrop_reattachingController_withFailedInline_isCalmReconnecting_notFailed() {
        // The live channel dropped: the INLINE path produced the scary Failed band, but
        // the controller stayed Reattaching → the user must see a CALM Reconnecting
        // band, with a calm default payload (no inline reconnect bookkeeping yet).
        val result = project(ConnectionState.Reattaching(host, sid), inlineFailed)
        assertEquals(
            ConnectionStatus.Reconnecting(
                host = HOST,
                port = PORT,
                user = USER,
                attempt = 1,
                maxAttempts = ConnectionController.DEFAULT_MAX_RECONNECT_ATTEMPTS,
                retryDelayMs = 0L,
                reason = "Reconnecting…",
            ),
            result,
        )
    }

    @Test
    fun recoverableDrop_reconnectingController_withFailedInline_isCalmReconnecting_notFailed() {
        val result = project(ConnectionState.Reconnecting(host, sid, attempt = 1), inlineFailed)
        assertEquals(
            ConnectionStatus.Reconnecting(
                host = HOST,
                port = PORT,
                user = USER,
                attempt = 1,
                maxAttempts = ConnectionController.DEFAULT_MAX_RECONNECT_ATTEMPTS,
                retryDelayMs = 0L,
                reason = "Reconnecting…",
            ),
            result,
        )
    }

    // ---------------------------------------------------------------------------
    // APPROVED #685 behavior #2: within-grace foreground → Connected
    // ---------------------------------------------------------------------------

    @Test
    fun withinGraceForeground_liveController_isConnected_evenIfInlineProbing() {
        // A within-grace foreground keeps the controller Live, so the user sees
        // Connected with no probe band — even though the inline runtime probe surfaced
        // a transient Reconnecting/Failed.
        assertEquals(
            ConnectionStatus.Connected(HOST, PORT, USER),
            project(ConnectionState.Live(host, sid), inlineReconnecting),
        )
        assertEquals(
            ConnectionStatus.Connected(HOST, PORT, USER),
            project(ConnectionState.Live(host, sid), inlineFailed),
        )
    }

    // ---------------------------------------------------------------------------
    // terminalOrInlineStatus guard — BOTH ways, for Gone AND Unreachable
    // ---------------------------------------------------------------------------

    @Test
    fun unreachableController_withFailedInline_surfacesFailed_onlyWhenInlineTrulyExhausted() {
        // The honest error: surface Failed ONLY when the inline ladder is ALSO terminal
        // (the inline-projected status is Failed). The curated cause message is
        // preserved (never raw SSH text).
        assertEquals(
            ConnectionStatus.Failed("Host unreachable"),
            project(ConnectionState.Unreachable(host, sid), inlineFailed),
        )
    }

    @Test
    fun unreachableController_withReconnectingInline_staysCalmReconnecting_notPrematureFailed() {
        // The controller's drop-ladder over-exhausted while the inline ladder is STILL
        // recovering: follow the inline reconnect, NOT a premature scary Failed.
        assertEquals(
            ConnectionStatus.Reconnecting(
                host = HOST,
                port = PORT,
                user = USER,
                attempt = 3,
                maxAttempts = 7,
                retryDelayMs = 1_500L,
                reason = "Network changed",
            ),
            project(ConnectionState.Unreachable(host, sid), inlineReconnecting),
        )
    }

    @Test
    fun unreachableController_withConnectedInline_followsInline_controllerOverExhausted() {
        // Inline already recovered (Connected): the controller over-exhausted; follow
        // the inline truth, not a premature Failed.
        assertEquals(
            inlineConnected,
            project(ConnectionState.Unreachable(host, sid), inlineConnected),
        )
    }

    @Test
    fun goneController_withFailedInline_surfacesFailed() {
        assertEquals(
            ConnectionStatus.Failed("Host unreachable"),
            project(ConnectionState.Gone(host, sid), inlineFailed),
        )
    }

    @Test
    fun goneController_withReconnectingInline_staysCalmReconnecting() {
        assertEquals(
            ConnectionStatus.Reconnecting(
                host = HOST,
                port = PORT,
                user = USER,
                attempt = 3,
                maxAttempts = 7,
                retryDelayMs = 1_500L,
                reason = "Network changed",
            ),
            project(ConnectionState.Gone(host, sid), inlineReconnecting),
        )
    }

    @Test
    fun goneController_withConnectedInline_followsInline() {
        assertEquals(
            inlineConnected,
            project(ConnectionState.Gone(host, sid), inlineConnected),
        )
    }

    private companion object {
        const val HOST = "example.com"
        const val PORT = 22
        const val USER = "root"
    }
}
