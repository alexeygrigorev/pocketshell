package com.pocketshell.app.tmux

import com.pocketshell.core.connection.ConnectionPhase
import com.pocketshell.core.connection.RevealState
import com.pocketshell.core.connection.SessionId
import com.pocketshell.core.connection.SessionSurfaceState
import com.pocketshell.core.connection.sessionSurfaceState
import com.pocketshell.core.connection.surfaceOwnsPrimary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #822 — the chrome half of "honest in-place recovery after a within-grace socket
 * drop".
 *
 * ## The reported bug this pins
 *
 * The maintainer's `-CC` socket died mid-session; the within-grace reveal hold kept the
 * last pane frame painted, and the SCREEN read that retained frame as connection truth:
 * a green "Connected" dot, no band, no in-place action. Nothing told him the wire was
 * gone until a send wedged, and the only recovery was switching to another session and
 * back.
 *
 * Two chrome defects made that state unrecoverable in place, and both are gate-wired here:
 *
 *  1. **The pill lied.** [SessionSurfaceState.Live] maps to a green `Connected` pill
 *     ([toUiStatus]); with the reveal hold retaining the frame over a dead wire that is a
 *     false green. [connectionPillStatus] now follows the CONNECTION.
 *  2. **The reconnect band was structurally unreachable, so there was nothing to tap.**
 *     The old gate was `!surfaceOwnsPrimary && surfaceState is SessionSurfaceState.Reconnecting`
 *     — a contradiction, because a `SessionSurfaceState.Reconnecting` is by construction a
 *     HELD surface and a held surface always owns the primary indicator. The band's own
 *     KDoc promised to cover "a Reconnecting that keeps a live frame painted"; that case
 *     could never evaluate true. [shouldShowReconnectingProgressRow] is now gated on the
 *     connection.
 *  3. **Making the wire honestly not-live then DESTROYED the retained frame.** Once
 *     `sessionLive` goes false under the reveal hold, the #823 `PullToRefreshBox` mounts
 *     over the live `TerminalView` and its `verticalScroll` collapses the terminal to
 *     0x0 — the user loses the very frame this issue exists to preserve.
 *     [shouldMountPullToReconnect] withholds the box while a live frame is painted.
 *
 * The #750 half of the gate is unchanged and re-pinned below: while the surface owns the
 * centered "Attaching…" hold, the band stays suppressed for EVERY status, so a reconnect
 * can still never paint two loaders.
 */
class Issue822HonestReconnectChromeTest {

    private val sid = SessionId("1/work")

    private val reconnecting = TmuxSessionViewModel.ConnectionStatus.Reconnecting(
        host = "h",
        port = 22,
        user = "u",
        attempt = 1,
        maxAttempts = 3,
        retryDelayMs = 0L,
        reason = "Reconnecting…",
    )
    private val connected = TmuxSessionViewModel.ConnectionStatus.Connected("h", 22, "u")
    private val connecting = TmuxSessionViewModel.ConnectionStatus.Connecting("h", 22, "u")

    private fun liveFrame(): SessionSurfaceState =
        sessionSurfaceState(
            RevealState.Live(sid, "work", panes = emptyList()),
            ConnectionPhase.Reconnecting("h", 22, "u", 1, 3, 0L),
            sid,
        )

    private fun heldFrame(): SessionSurfaceState =
        sessionSurfaceState(
            RevealState.Seeding(sid, "work"),
            ConnectionPhase.Reconnecting("h", 22, "u", 1, 3, 0L),
            sid,
        )

    private fun calmFailureFrame(): SessionSurfaceState =
        sessionSurfaceState(
            RevealState.Error(sid, "work", retrying = false),
            ConnectionPhase.Failed,
            sid,
        )

    private fun goneFrame(): SessionSurfaceState =
        sessionSurfaceState(RevealState.Gone(sid, "work"), ConnectionPhase.Failed, sid)

    @Test
    fun retainedLiveFrameOverADeadWireReadsTheHonestReconnectingPill() {
        val state = liveFrame()
        assertTrue("precondition: the reveal hold retains the live frame", state is SessionSurfaceState.Live)
        assertEquals(
            "the retained frame alone still maps to the green Connected pill — that is the lie",
            com.pocketshell.uikit.model.ConnectionStatus.Connected,
            state.toUiStatus(),
        )
        assertEquals(
            "#822: the pill/dot must follow the CONNECTION, so a confirmed-dead wire under " +
                "the reveal hold reads the amber Reconnecting",
            com.pocketshell.uikit.model.ConnectionStatus.Connecting,
            connectionPillStatus(state, reconnecting),
        )
    }

    @Test
    fun healthyStatesKeepTheirSurfaceDerivedPill() {
        // The override is MONOTONE: it only downgrades while the connection genuinely is
        // reconnecting, so no healthy state can be made to look dead by it.
        val live = liveFrame()
        assertEquals(
            com.pocketshell.uikit.model.ConnectionStatus.Connected,
            connectionPillStatus(live, connected),
        )
        assertEquals(
            com.pocketshell.uikit.model.ConnectionStatus.Connected,
            connectionPillStatus(live, connecting),
        )
        val held = heldFrame()
        assertEquals(
            "a held reconnect keeps its own amber pill",
            held.toUiStatus(),
            connectionPillStatus(held, reconnecting),
        )
    }

    @Test
    fun retainedLiveFrameOverADeadWireOffersTheInPlaceRetryBand() {
        val state = liveFrame()
        val ownsPrimary = state.surfaceOwnsPrimary(panesEmpty = false)
        assertFalse("precondition: a retained live frame owns no loader", ownsPrimary)
        assertTrue(
            "#822: the retained frame must carry the honest Reconnecting band, whose " +
                "`Retry now` is the in-place action the switch-away dance replaced",
            shouldShowReconnectingProgressRow(ownsPrimary, reconnecting),
        )
        assertTrue(
            "#822: the surface `Reconnect` affordance also stays available over the frame",
            surfaceReconnectButtonVisible(state),
        )
    }

    @Test
    fun healthyLiveFrameShowsNoReconnectBand() {
        val state = liveFrame()
        val ownsPrimary = state.surfaceOwnsPrimary(panesEmpty = false)
        listOf(connected, connecting, TmuxSessionViewModel.ConnectionStatus.Idle).forEach { status ->
            assertFalse(
                "no reconnect band for status=$status",
                shouldShowReconnectingProgressRow(ownsPrimary, status),
            )
        }
    }

    @Test
    fun heldSurfaceStillSuppressesTheBandForEveryStatus() {
        // The #750 single-indicator half, unchanged: the centered "Attaching…" hold is the
        // SOLE loader, so no status may raise the top band on top of it.
        val held = heldFrame()
        val ownsPrimary = held.surfaceOwnsPrimary(panesEmpty = false)
        assertTrue("precondition: a held surface owns the primary indicator", ownsPrimary)
        listOf(reconnecting, connected, connecting, TmuxSessionViewModel.ConnectionStatus.Idle)
            .forEach { status ->
                assertFalse(
                    "held surface must suppress the band for status=$status",
                    shouldShowReconnectingProgressRow(ownsPrimary, status),
                )
            }
    }

    // ------------------------------------------------------------------
    // #822 round 2 — the pull-to-reconnect mount gate.
    //
    // The on-device defect this pins (found by running the journey on the emulator):
    // with `sessionLive` false while the reveal hold still paints the user's last pane
    // frame, mounting the #823 `PullToRefreshBox` wraps the LIVE `TerminalView` in a
    // `verticalScroll`. Under that unbounded height constraint the terminal measures to
    // ZERO and the retained frame is destroyed — an empty pull area with a spinner where
    // the user's session was. The terminal EMULATOR BUFFER is untouched by that collapse,
    // which is precisely why a journey assertion reading `transcriptText` stayed green
    // while the screen was blank. These tests constrain the guard directly.
    // ------------------------------------------------------------------

    @Test
    fun retainedLiveFrameOverADeadWireMustNotMountThePullToReconnectBox() {
        val state = liveFrame()
        val ownsPrimary = state.surfaceOwnsPrimary(panesEmpty = false)
        assertFalse("precondition: a retained live frame owns no placeholder", ownsPrimary)
        assertFalse(
            "#822: the pull box must NOT wrap a live retained TerminalView — its " +
                "verticalScroll collapses the terminal to 0x0 and destroys the very frame " +
                "the within-grace hold exists to preserve",
            shouldMountPullToReconnect(
                sessionLive = false,
                canReconnect = true,
                surfaceOwnsPrimary = ownsPrimary,
            ),
        )
    }

    @Test
    fun withholdingThePullBoxStillLeavesTheRetainedFrameAnInPlaceAction() {
        // The two gates are complementary: exactly one of them owns the recovery
        // affordance in every non-Connected state, so withholding the pull gesture over a
        // retained frame can never leave the user with nothing to tap (the #822 report).
        val state = liveFrame()
        val ownsPrimary = state.surfaceOwnsPrimary(panesEmpty = false)
        val pullBox = shouldMountPullToReconnect(
            sessionLive = false,
            canReconnect = true,
            surfaceOwnsPrimary = ownsPrimary,
        )
        val band = shouldShowReconnectingProgressRow(ownsPrimary, reconnecting)
        assertFalse("the pull box stays off over a live retained frame", pullBox)
        assertTrue("...and the honest Retry-now band takes over the action", band)
        assertTrue(
            "#822 invariant: a dropped wire always has exactly one recovery affordance",
            pullBox != band,
        )
    }

    @Test
    fun placeholderSurfacesStillMountThePullToReconnectBox() {
        // Class coverage: every state the #823 wrapper was actually written for — a
        // STATIC placeholder, never a live terminal — keeps the pull gesture.
        val placeholders = mapOf(
            "held Attaching hold" to heldFrame().surfaceOwnsPrimary(panesEmpty = false),
            "live reveal with no panes yet (waiting ring)" to
                liveFrame().surfaceOwnsPrimary(panesEmpty = true),
            "settled calm failure" to calmFailureFrame().surfaceOwnsPrimary(panesEmpty = false),
            "session gone elsewhere" to goneFrame().surfaceOwnsPrimary(panesEmpty = false),
        )
        placeholders.forEach { (label, ownsPrimary) ->
            assertTrue("precondition: $label owns the primary indicator", ownsPrimary)
            assertTrue(
                "#823: $label shows only a placeholder, so pull-to-reconnect stays mounted",
                shouldMountPullToReconnect(
                    sessionLive = false,
                    canReconnect = true,
                    surfaceOwnsPrimary = ownsPrimary,
                ),
            )
        }
    }

    @Test
    fun aHealthyOrUnreconnectableSessionNeverMountsThePullToReconnectBox() {
        // The other two terms of the gate, unchanged by #822 but pinned so a future
        // rewrite cannot silently drop them either.
        listOf(true, false).forEach { ownsPrimary ->
            assertFalse(
                "a live session is not reconnectable by pull (ownsPrimary=$ownsPrimary)",
                shouldMountPullToReconnect(
                    sessionLive = true,
                    canReconnect = true,
                    surfaceOwnsPrimary = ownsPrimary,
                ),
            )
            assertFalse(
                "no reconnect capability means no pull affordance (ownsPrimary=$ownsPrimary)",
                shouldMountPullToReconnect(
                    sessionLive = false,
                    canReconnect = false,
                    surfaceOwnsPrimary = ownsPrimary,
                ),
            )
        }
    }

    @Test
    fun emptyPaneSurfaceKeepsItsOwnLoaderAndSuppressesTheBand() {
        // Class coverage (#1322): a live reveal with NO panes yet owns the "waiting for
        // tmux panes…" ring, so even a Reconnecting connection must not stack the band.
        val state = liveFrame()
        val ownsPrimary = state.surfaceOwnsPrimary(panesEmpty = true)
        assertTrue("precondition: an empty-pane surface owns its ring", ownsPrimary)
        assertFalse(
            "the waiting ring stays the sole indicator",
            shouldShowReconnectingProgressRow(ownsPrimary, reconnecting),
        )
    }
}
