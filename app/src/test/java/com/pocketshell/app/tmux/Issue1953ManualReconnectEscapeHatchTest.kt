package com.pocketshell.app.tmux

import com.pocketshell.core.connection.ConnectionPhase
import com.pocketshell.core.connection.FailureReason
import com.pocketshell.core.connection.RevealState
import com.pocketshell.core.connection.SessionId
import com.pocketshell.core.connection.SessionSurfaceState
import com.pocketshell.core.connection.sessionSurfaceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1953 — **Manual Reconnect must preempt a WEDGED automatic ladder.**
 *
 * ## The reported defect
 *
 * In exact-main run 30787372084 `ReconnectKebabInPlaceJourneyE2eTest` failed BOTH attempts
 * stuck in `Reconnecting(attempt=1)`. The kebab item existed in the semantics tree, but
 * [reconnectKebabEnabled] disabled it for [SessionSurfaceState.Reconnecting], so
 * `performClick()` invoked nothing: no `reconnect_tapped`, no manual trigger, no transport
 * replacement. The action is documented as the escape hatch *for when automatic reconnect
 * does not fire* — and the wedged automatic state disabled that very escape hatch.
 *
 * ## Why `Reconnecting` is the wedged state (not "already recovering")
 *
 * The controller projects BOTH of its automatic-recovery states onto the SAME view state:
 * `ConnectionState.Reattaching` (the pre-numbered silent heal) and
 * `ConnectionState.Reconnecting(n)` (the numbered ladder) both project to
 * `ConnectionStatus.Reconnecting` (see `ConnectionStatusProjection`'s §1 seam table), which
 * fuses to [SessionSurfaceState.Reconnecting]. So "Reconnecting" on screen does NOT mean "a
 * dial is on the wire right now" — the ladder spends most of its life asleep in
 * `delay(retryDelayMs)` between rungs, and a wedged ladder can sit there indefinitely. The
 * old gate read that state as "a reconnect is already in flight, a tap would be redundant"
 * and locked the user out for the whole window.
 *
 * ## The contract this pins
 *
 *  - [SessionSurfaceState.Reconnecting] with a target → **ENABLED** at every attempt number
 *    (including the pre-numbered attempt-1 heal the controller's `Reattaching` projects to).
 *    This is the escape hatch; it may never be an indefinitely disabled state.
 *  - [SessionSurfaceState.Connecting] (the initial cold dial) → still disabled. There is no
 *    prior session to escape from; a tap would restart a handshake the user just started.
 *  - [SessionSurfaceState.Attaching] (an ACTIVE target switch / warm reveal) → still
 *    disabled. Issue #1953's acceptance explicitly permits keeping the switch protected from
 *    redundant taps, and yanking an in-flight switch is the #890 regression class.
 *  - No target ([canReconnect] false) → disabled everywhere, so a tap is never a silent
 *    no-op.
 *
 * The states are built through the REAL production fusion ([sessionSurfaceState] over a real
 * [RevealState] + [ConnectionPhase]) wherever the fusion is what produces the reported state,
 * so this is not a hand-built stand-in for a state production can't reach.
 */
class Issue1953ManualReconnectEscapeHatchTest {

    private val sid = SessionId("host/work")

    /**
     * THE REGRESSION (red on the unfixed gate): the maintainer's reported state — a known
     * target, the surface wedged in the automatic ladder — must expose an ENABLED escape
     * hatch. Covers the pre-numbered heal (attempt 1, the `Reattaching` projection) and every
     * numbered rung, because the ladder can wedge on any of them.
     */
    @Test
    fun reconnectKebabEnabledWhileTheAutomaticLadderIsWedged() {
        (1..MAX_ATTEMPTS).forEach { attempt ->
            val wedged = SessionSurfaceState.Reconnecting(
                targetId = sid,
                targetName = "work",
                host = "h",
                port = 22,
                user = "u",
                attempt = attempt,
                maxAttempts = MAX_ATTEMPTS,
                // A wedged ladder is ASLEEP between rungs — a long backoff is exactly the
                // window in which the user has no other way out.
                retryDelayMs = 30_000L,
            )
            assertTrue(
                "issue #1953: a wedged automatic ladder (attempt $attempt/$MAX_ATTEMPTS) must " +
                    "NOT disable the manual Reconnect escape hatch — that is the state the " +
                    "escape hatch exists for",
                reconnectKebabEnabled(canReconnect = true, surfaceState = wedged),
            )
        }
    }

    /**
     * Same regression, but the state is produced by the REAL production fusion from a
     * reveal-machine hold + the controller's Reconnecting phase — the exact
     * `(RevealState, ConnectionPhase) -> SessionSurfaceState` path the screen runs. This is
     * the "controller Reattaching OR Reconnecting" limb of the acceptance criterion: both
     * controller states project to [ConnectionPhase.Reconnecting], so both fuse here.
     */
    @Test
    fun reconnectKebabEnabledForTheFusedControllerRecoveryState() {
        listOf(
            // The pre-numbered silent heal (controller `Reattaching` → calm attempt-1).
            ConnectionPhase.Reconnecting("h", 22, "u", 1, MAX_ATTEMPTS, 0L),
            // The numbered ladder asleep on its backoff (controller `Reconnecting(2)`).
            ConnectionPhase.Reconnecting("h", 22, "u", 2, MAX_ATTEMPTS, 8_000L),
        ).forEach { phase ->
            val fused = sessionSurfaceState(
                reveal = RevealState.Seeding(sid, "work"),
                phase = phase,
                targetId = sid,
            )
            assertTrue(
                "precondition: the production fusion must produce the reported Reconnecting " +
                    "surface for $phase, got $fused",
                fused is SessionSurfaceState.Reconnecting,
            )
            assertTrue(
                "issue #1953: the fused controller-recovery surface must enable the manual " +
                    "Reconnect escape hatch ($phase)",
                reconnectKebabEnabled(canReconnect = true, surfaceState = fused),
            )
        }
    }

    /**
     * The other half of the contract (acceptance criterion 4): the initial cold dial and an
     * ACTIVE target switch stay protected from a redundant tap. Widening the gate must not
     * turn into "always enabled".
     */
    @Test
    fun reconnectKebabStillProtectedDuringInitialConnectAndActiveSwitch() {
        val protectedStates = mapOf(
            "initial cold dial" to SessionSurfaceState.Connecting(sid, "work", "h", 22, "u"),
            "active target switch / warm reveal" to
                SessionSurfaceState.Attaching(sid, "work", "h", 22, "u"),
        )
        protectedStates.forEach { (label, state) ->
            assertEquals(
                "issue #1953: $label must stay protected from a redundant manual re-dial",
                false,
                reconnectKebabEnabled(canReconnect = true, surfaceState = state),
            )
        }
        // And the same via the real fusion, so the protection is pinned on the states the
        // screen actually produces (a cold `Connecting` phase and a `Warm` switch phase).
        assertEquals(
            "the fused cold-dial surface must stay protected",
            false,
            reconnectKebabEnabled(
                canReconnect = true,
                surfaceState = sessionSurfaceState(
                    reveal = RevealState.Seeding(sid, "work"),
                    phase = ConnectionPhase.Connecting("h", 22, "u"),
                    targetId = sid,
                ),
            ),
        )
        assertEquals(
            "the fused warm-switch surface must stay protected",
            false,
            reconnectKebabEnabled(
                canReconnect = true,
                surfaceState = sessionSurfaceState(
                    reveal = RevealState.Seeding(sid, "work"),
                    phase = ConnectionPhase.Warm("h", 22, "u"),
                    targetId = sid,
                ),
            ),
        )
    }

    /**
     * Widening the gate must not make a targetless tap a silent no-op — including in the
     * newly-enabled Reconnecting state.
     */
    @Test
    fun reconnectKebabStillDisabledWithoutATarget() {
        listOf(
            SessionSurfaceState.Idle,
            SessionSurfaceState.Reconnecting(sid, "work", "h", 22, "u", 1, MAX_ATTEMPTS, 0L),
            SessionSurfaceState.Failed(sid, "work", FailureReason.Unreachable(retryable = true)),
            SessionSurfaceState.Gone(sid, "work"),
            SessionSurfaceState.Live(sid, "work", emptyList()),
        ).forEach { state ->
            assertEquals(
                "no target → Reconnect disabled for $state (a tap must never be a silent no-op)",
                false,
                reconnectKebabEnabled(canReconnect = false, surfaceState = state),
            )
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 4
    }
}
