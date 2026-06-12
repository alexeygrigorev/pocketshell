package com.pocketshell.app.tmux.connection

import com.pocketshell.core.connection.Clock
import com.pocketshell.core.connection.ConnectionState
import com.pocketshell.core.connection.HostKey
import com.pocketshell.core.connection.SessionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * EPIC #687 Phase-2, slice 1c-iii — the OBSERVE-ONLY EQUIVALENCE proof.
 *
 * These tests drive the [ConnectionControllerShadowBridge] (the shadow controller
 * the VM runs in observe-only mode) through representative real-world scenarios —
 * the SAME event sequences the VM feeds it from its inline lifecycle/transition
 * points — and assert:
 *
 *  1. On every PRESERVED behavior (cold connect, switch, beyond-grace foreground,
 *     network change, target-gone), the shadow controller's projected
 *     `ConnectionStatus` NAME MATCHES the inline `connectionStatusFor` status name
 *     the suite pins. This is the safety net that de-risks the 1c-iv flip: it
 *     proves the controller produces the right state from the real event stream
 *     BEFORE it is allowed to drive.
 *
 *  2. On the TWO approved CHANGED paths, the shadow controller DIVERGES exactly as
 *     approved (these are recorded as expected divergences, NOT made to match):
 *       (i)  a recoverable passive drop on a live channel: inline → scary `Failed`
 *            band; controller → silent `Reconnecting` (the approved silent
 *            recovery, #685).
 *       (ii) a within-grace foreground: inline rides the runtime probe and surfaces
 *            `Reconnecting`; controller stays effectively `Connected`/no-reconnect
 *            via the warm-lease grace predicate (no probe).
 *     These two are exactly what slice 1c-iv will flip the inline behavior TO.
 *
 * The inline status name for each scenario is the value the inline VM's
 * `connectionStatusFor` mapper produces (`Idle/Connecting/Switching/Connected/
 * Reconnecting/Failed`), per the §1 seam-reconciliation table.
 */
class ConnectionControllerShadowEquivalenceTest {

    /** A controllable virtual clock for the within/beyond-grace boundary. */
    private class TestClock(var now: Long = 0L) : Clock {
        override fun nowMs(): Long = now
    }

    private val host = HostKey("alice@example.com:22/7")
    private val sessionA = SessionId("7/main")
    private val sessionB = SessionId("7/build")

    private fun bridge(
        clock: TestClock = TestClock(),
        warm: Boolean = true,
    ) = ConnectionControllerShadowBridge(
        clock = clock,
        warmSnapshot = { it == host && warm },
    )

    // --- PRESERVED behaviors: shadow MUST match the inline status name -----------

    @Test
    fun coldConnect_thenLive_matchesInline() {
        // Cold host (not warm): inline emits Connecting then Live → Connected.
        val cold = bridge(warm = false)
        // Inline: Connecting → Connected.
        cold.observeInlineTransition("Connecting", host, sessionA)
        assertEquals("Connecting", cold.shadowStatusName) // inline = "Connecting"
        cold.observeInlineTransition("Live", host, sessionA)
        assertEquals(
            // PRESERVED: inline connectionStatusFor(Live) == "Connected".
            "Connected",
            cold.shadowStatusName,
        )
    }

    @Test
    fun warmOpen_attaching_matchesInlineSwitching() {
        // Warm host: inline emits Attaching (→ Switching) then Live (→ Connected).
        val warm = bridge(warm = true)
        warm.observeInlineTransition("Attaching", host, sessionA)
        assertEquals(
            // PRESERVED: inline connectionStatusFor(Attaching) == "Switching".
            "Switching",
            warm.shadowStatusName,
        )
        warm.observeInlineTransition("Live", host, sessionA)
        assertEquals("Connected", warm.shadowStatusName)
    }

    @Test
    fun sameHostSwitch_aToB_matchesInline() {
        val warm = bridge(warm = true)
        // Land session A live.
        warm.observeInlineTransition("Attaching", host, sessionA)
        warm.observeInlineTransition("Live", host, sessionA)
        assertEquals("Connected", warm.shadowStatusName)
        // Fast-switch to B: inline emits Attaching (→ Switching) for B, then Live.
        warm.observeInlineTransition("Attaching", host, sessionB)
        assertEquals(
            // PRESERVED: the no-overlay same-host switch is "Switching".
            "Switching",
            warm.shadowStatusName,
        )
        warm.observeInlineTransition("Live", host, sessionB)
        assertEquals("Connected", warm.shadowStatusName)
        // The controller is now targeting B, not the superseded A.
        assertEquals(sessionB, (warm.shadowState as ConnectionState.Live).targetId)
    }

    @Test
    fun beyondGraceForeground_reconnect_matchesInline() {
        // bg → fg BEYOND the 60s grace window: inline auto-reconnects silently
        // (→ Reconnecting); the controller does too (deadline passed).
        val clock = TestClock(now = 0L)
        val b = bridge(clock = clock, warm = true)
        b.observeInlineTransition("Attaching", host, sessionA)
        b.observeInlineTransition("Live", host, sessionA)
        assertEquals("Connected", b.shadowStatusName)
        // Background, then advance the clock PAST the 60s grace.
        b.observeBackground()
        clock.now = 70_000L
        b.observeForeground()
        assertEquals(
            // PRESERVED: beyond grace, both inline and controller silent-reconnect.
            "Reconnecting",
            b.shadowStatusName,
        )
    }

    @Test
    fun beyondGrace_leaseWentCold_reconnect_matchesInline() {
        // bg → fg within the 60s window but the lease went COLD: both reconnect.
        val clock = TestClock(now = 0L)
        val b = ConnectionControllerShadowBridge(
            clock = clock,
            warmSnapshot = { false }, // lease no longer warm
        )
        // Warm-enough to land live first (Attaching needs the controller non-idle).
        b.observeInlineTransition("Connecting", host, sessionA)
        b.observeInlineTransition("Live", host, sessionA)
        b.observeBackground()
        clock.now = 10_000L // within 60s window, but lease is cold
        b.observeForeground()
        assertEquals(
            // PRESERVED: cold lease → reconnect even inside the time window.
            "Reconnecting",
            b.shadowStatusName,
        )
    }

    @Test
    fun networkChange_nonValidated_isNoOp_matchesInline() {
        // A non-validated network blip: inline suppresses (#548) — status stays
        // Connected; the controller is a no-op too — stays Live → Connected.
        val b = bridge(warm = true)
        b.observeInlineTransition("Attaching", host, sessionA)
        b.observeInlineTransition("Live", host, sessionA)
        b.observeNetworkChanged(validatedHandoff = false)
        assertEquals(
            // PRESERVED: a blip never tears down a healthy channel.
            "Connected",
            b.shadowStatusName,
        )
    }

    @Test
    fun networkChange_validatedHandoff_reconnects_matchesInline() {
        // A real validated handoff: inline schedules a proactive reconnect
        // (→ Reconnecting); the controller silent-reconnects through the ladder.
        val b = bridge(warm = true)
        b.observeInlineTransition("Attaching", host, sessionA)
        b.observeInlineTransition("Live", host, sessionA)
        b.observeNetworkChanged(validatedHandoff = true)
        assertEquals(
            // PRESERVED: a validated handoff proactively reconnects (silently).
            "Reconnecting",
            b.shadowStatusName,
        )
    }

    @Test
    fun targetGone_matchesInlineFailed() {
        // Target deleted elsewhere (#666): inline projects Gone → "Failed"; the
        // controller's Gone projects to "Failed" too.
        val b = bridge(warm = true)
        b.observeInlineTransition("Attaching", host, sessionA)
        b.observeInlineTransition("Live", host, sessionA)
        b.observeInlineTransition("Gone", host, sessionA)
        assertEquals(
            // PRESERVED: the Gone → Failed §1 mapping.
            "Failed",
            b.shadowStatusName,
        )
        assertEquals(true, b.shadowState is ConnectionState.Gone)
    }

    @Test
    fun unreachable_afterExhaustedReconnect_matchesInlineFailed() {
        // The honest error after retries exhaust: inline projects Unreachable →
        // "Failed"; the controller drives its ladder to Unreachable → "Failed".
        val b = bridge(warm = true)
        b.observeInlineTransition("Connecting", host, sessionA)
        b.observeInlineTransition("Live", host, sessionA)
        b.observeInlineTransition("Unreachable", host, sessionA)
        assertEquals(
            // PRESERVED: the Unreachable → Failed §1 mapping (the only honest error).
            "Failed",
            b.shadowStatusName,
        )
        assertEquals(true, b.shadowState is ConnectionState.Unreachable)
    }

    // --- APPROVED DIVERGENCES: shadow MUST differ (do NOT make them match) -------

    /**
     * APPROVED DIVERGENCE #1 (recoverable drop, #685): a passive transport drop on
     * a LIVE channel.
     *  - INLINE today → a scary `Failed` "control channel closed… Tap Reconnect"
     *    band (or, on the within-grace path, a `Reconnecting`); the maintainer's
     *    daily-pain symptom is the scary band.
     *  - CONTROLLER → silent `Reattaching` → projects to a calm `Reconnecting`,
     *    NEVER a `Failed` band.
     * 1c-iv flips the inline behavior TO this calm silent recovery.
     */
    @Test
    fun divergence1_recoverableDrop_inlineFailed_controllerSilentReconnecting() {
        val b = bridge(warm = true)
        b.observeInlineTransition("Attaching", host, sessionA)
        b.observeInlineTransition("Live", host, sessionA)
        // The inline scary-band status the maintainer sees today on a live-channel
        // drop (the #685 pain). The controller must NOT reproduce it.
        val inlineStatusOnDrop = "Failed"
        b.observeTransportDropped(reason = "control channel closed")
        // The controller heals silently — Reattaching → calm Reconnecting.
        assertEquals(true, b.shadowState is ConnectionState.Reattaching)
        assertEquals("Reconnecting", b.shadowStatusName)
        // DOCUMENTED, EXPECTED divergence: calm recovery, not a scary band.
        assertNotEquals(
            "controller must NOT show the inline scary Failed band on a recoverable drop",
            inlineStatusOnDrop,
            b.shadowStatusName,
        )
    }

    /**
     * APPROVED DIVERGENCE #2 (within-grace foreground, #685 Bug-A): bg → fg WITHIN
     * the 60s grace window with the lease still warm.
     *  - INLINE today → rides the foreground RUNTIME PROBE (with a timeout
     *    ride-through), surfacing a transient `Reconnecting` working state while
     *    the probe verifies the channel.
     *  - CONTROLLER → the single grace predicate `now < deadline && isWarm` routes
     *    straight to `Reattaching` (no probe), and reseed lands it back to `Live`
     *    → projects to `Connected` with NO reconnect.
     * 1c-iv flips the inline behavior TO "within grace = warm lease," deleting the
     * probe. Recorded here as the expected divergence.
     */
    @Test
    fun divergence2_withinGraceForeground_inlineProbeReconnect_controllerStaysLive() {
        val clock = TestClock(now = 0L)
        val b = bridge(clock = clock, warm = true)
        b.observeInlineTransition("Attaching", host, sessionA)
        b.observeInlineTransition("Live", host, sessionA)
        b.observeBackground()
        // Foreground WITHIN the 60s grace window, lease still warm.
        clock.now = 5_000L
        b.observeForeground()
        // The controller rode the warm lease → Reattaching (no reconnect ladder,
        // no probe). attempt is irrelevant; it is NOT a Reconnecting(attempt).
        assertEquals(true, b.shadowState is ConnectionState.Reattaching)
        // The inline status on this path today (rides the probe → working state).
        val inlineStatusOnWithinGraceForeground = "Reconnecting"
        // A reseed lands the controller back to Live → Connected with no reconnect.
        b.observeInlineTransition("Live", host, sessionA)
        assertEquals("Connected", b.shadowStatusName)
        // DOCUMENTED, EXPECTED divergence: no probe-driven Reconnecting churn —
        // the controller treats within-grace as "warm lease, stay connected".
        assertNotEquals(
            "controller must NOT churn through the inline probe Reconnecting within grace",
            inlineStatusOnWithinGraceForeground,
            b.shadowStatusName,
        )
    }

    // --- The observe-only contract: the shadow never leaks into behavior ---------

    @Test
    fun statusNameMapper_isThePinned1to1Projection() {
        // The §1 seam-table mapping the equivalence comparison rests on.
        assertEquals(
            "Idle",
            ConnectionControllerShadowBridge.shadowStatusNameFor(ConnectionState.Idle),
        )
        assertEquals(
            "Connecting",
            ConnectionControllerShadowBridge.shadowStatusNameFor(
                ConnectionState.Connecting(host, sessionA),
            ),
        )
        assertEquals(
            "Switching",
            ConnectionControllerShadowBridge.shadowStatusNameFor(
                ConnectionState.Attaching(host, sessionA),
            ),
        )
        assertEquals(
            "Connected",
            ConnectionControllerShadowBridge.shadowStatusNameFor(
                ConnectionState.Live(host, sessionA),
            ),
        )
        assertEquals(
            "Reconnecting",
            ConnectionControllerShadowBridge.shadowStatusNameFor(
                ConnectionState.Reattaching(host, sessionA),
            ),
        )
        assertEquals(
            "Reconnecting",
            ConnectionControllerShadowBridge.shadowStatusNameFor(
                ConnectionState.Reconnecting(host, sessionA, attempt = 1),
            ),
        )
        assertEquals(
            "Failed",
            ConnectionControllerShadowBridge.shadowStatusNameFor(
                ConnectionState.Gone(host, sessionA),
            ),
        )
        assertEquals(
            "Failed",
            ConnectionControllerShadowBridge.shadowStatusNameFor(
                ConnectionState.Unreachable(host, sessionA),
            ),
        )
    }
}
