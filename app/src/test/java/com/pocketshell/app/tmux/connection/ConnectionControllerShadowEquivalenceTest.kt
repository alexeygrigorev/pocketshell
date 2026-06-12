package com.pocketshell.app.tmux.connection

import com.pocketshell.core.connection.Clock
import com.pocketshell.core.connection.ConnectionState
import com.pocketshell.core.connection.HostKey
import com.pocketshell.core.connection.SessionId
import com.pocketshell.core.ssh.SshLeaseKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EPIC #687 Phase-2, slice 1c-iii — the OBSERVE-ONLY EQUIVALENCE proof.
 *
 * These tests drive the [ConnectionControllerShadowBridge] (the shadow controller
 * the VM runs in observe-only mode) through representative real-world scenarios —
 * the SAME event sequences the VM feeds it from its inline lifecycle/transition
 * points — and assert:
 *
 *  0. The shadow controller reaches [ConnectionState.Live] from REAL transport/seed
 *     FEEDBACK ([observeTransportLive] + [observeSeedLanded]) — the same emits the
 *     VM fires at the existing lease-`Connected` collector and the existing
 *     `seedPaneFromCaptureOnce` landing — NOT by mirroring the inline Live
 *     transition. The inline Live mirror only ensures targeting; on its own it no
 *     longer promotes to Live. This is the 1c-iv prerequisite proven here.
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

    /**
     * Land the shadow controller [ConnectionState.Live] for [target] the way the
     * VM does now — from REAL transport/seed FEEDBACK, NOT by mirroring the inline
     * Live transition. The inline Live mirror only ensures targeting; the real
     * [ConnectionControllerShadowBridge.observeTransportLive] +
     * [ConnectionControllerShadowBridge.observeSeedLanded] feeds are what promote
     * the controller to Live.
     */
    private fun ConnectionControllerShadowBridge.landLiveFromRealFeedback(
        target: SessionId,
        host: HostKey = this@ConnectionControllerShadowEquivalenceTest.host,
    ) {
        observeTransportLive(host, target)
        observeSeedLanded(host, target, paneId = "%0")
    }

    // --- PRESERVED behaviors: shadow MUST match the inline status name -----------

    @Test
    fun coldConnect_thenLive_matchesInline() {
        // Cold host (not warm): inline emits Connecting; the controller reaches
        // Live from REAL feedback (lease Connected → TransportLive promotes
        // Connecting → Attaching; active-pane SeedLanded promotes Attaching → Live).
        val cold = bridge(warm = false)
        cold.observeInlineTransition("Connecting", host, sessionA)
        assertEquals("Connecting", cold.shadowStatusName) // inline = "Connecting"
        // EPIC #687 slice 1c-iv-a (THE FLIP): the inline Live mirror is now the
        // AUTHORITATIVE reveal moment and promotes the controller to Live in lockstep
        // (the VM only emits Live once the active pane is seeded + revealed). So the
        // inline Live mirror alone reaches "Connected".
        cold.observeInlineTransition("Live", host, sessionA)
        assertEquals(
            // PRESERVED: inline connectionStatusFor(Live) == "Connected".
            "Connected",
            cold.shadowStatusName,
        )
        // The REAL feedback feeds remain idempotent — they keep the controller Live.
        cold.landLiveFromRealFeedback(sessionA)
        assertEquals("Connected", cold.shadowStatusName)
    }

    @Test
    fun warmOpen_attaching_matchesInlineSwitching() {
        // Warm host: inline emits Attaching (→ Switching); REAL seed feedback lands
        // Live (→ Connected). The warm predicate must be TRUE for the open to route
        // straight to Attaching (no Connecting overlay) — proving the aligned
        // HostKey encoding (item 1) makes isWarm work.
        val warm = bridge(warm = true)
        warm.observeInlineTransition("Attaching", host, sessionA)
        assertEquals(
            // PRESERVED: inline connectionStatusFor(Attaching) == "Switching".
            "Switching",
            warm.shadowStatusName,
        )
        warm.observeInlineTransition("Live", host, sessionA)
        // FLIP: the inline Live mirror (the authoritative reveal moment) promotes to
        // Live → Connected in lockstep.
        assertEquals("Connected", warm.shadowStatusName)
        // Real feedback remains idempotent.
        warm.landLiveFromRealFeedback(sessionA)
        assertEquals("Connected", warm.shadowStatusName)
    }

    @Test
    fun sameHostSwitch_aToB_matchesInline() {
        val warm = bridge(warm = true)
        // Land session A live from real feedback.
        warm.observeInlineTransition("Attaching", host, sessionA)
        warm.observeInlineTransition("Live", host, sessionA)
        warm.landLiveFromRealFeedback(sessionA)
        assertEquals("Connected", warm.shadowStatusName)
        // Fast-switch to B: inline emits Attaching (→ Switching) for B.
        warm.observeInlineTransition("Attaching", host, sessionB)
        assertEquals(
            // PRESERVED: the no-overlay same-host switch is "Switching".
            "Switching",
            warm.shadowStatusName,
        )
        // REAL seed feedback for B lands Live on B.
        warm.observeInlineTransition("Live", host, sessionB)
        warm.landLiveFromRealFeedback(sessionB)
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
        b.landLiveFromRealFeedback(sessionA)
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
        // Land live first via real feedback (cold lease only goes warm-cold AFTER
        // background; the open path still reaches Live from the seed landing).
        b.observeInlineTransition("Connecting", host, sessionA)
        b.observeInlineTransition("Live", host, sessionA)
        b.landLiveFromRealFeedback(sessionA)
        assertEquals("Connected", b.shadowStatusName)
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
        b.landLiveFromRealFeedback(sessionA)
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
        b.landLiveFromRealFeedback(sessionA)
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
        b.landLiveFromRealFeedback(sessionA)
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
        b.landLiveFromRealFeedback(sessionA)
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
        b.landLiveFromRealFeedback(sessionA)
        assertEquals("Connected", b.shadowStatusName)
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
        b.landLiveFromRealFeedback(sessionA)
        assertEquals("Connected", b.shadowStatusName)
        b.observeBackground()
        // Foreground WITHIN the 60s grace window, lease still warm.
        clock.now = 5_000L
        b.observeForeground()
        // The controller rode the warm lease → Reattaching (no reconnect ladder,
        // no probe). attempt is irrelevant; it is NOT a Reconnecting(attempt).
        assertEquals(true, b.shadowState is ConnectionState.Reattaching)
        // The inline status on this path today (rides the probe → working state).
        val inlineStatusOnWithinGraceForeground = "Reconnecting"
        // A REAL reseed landing (the active pane capture) lands the controller back
        // to Live → Connected with no reconnect.
        b.observeSeedLanded(host, sessionA, paneId = "%0")
        assertEquals("Connected", b.shadowStatusName)
        // DOCUMENTED, EXPECTED divergence: no probe-driven Reconnecting churn —
        // the controller treats within-grace as "warm lease, stay connected".
        assertNotEquals(
            "controller must NOT churn through the inline probe Reconnecting within grace",
            inlineStatusOnWithinGraceForeground,
            b.shadowStatusName,
        )
    }

    // --- REAL feedback: the controller reaches Live independently (1c-iv prereq) --

    /**
     * The REAL transport/seed feedback path STILL reaches [ConnectionState.Live]
     * independently — WITHOUT the inline Live mirror at all. EPIC #687 slice 1c-iv-a
     * (THE FLIP) makes the inline reveal the authoritative status moment that also
     * promotes to Live, but the genuine real-feedback promotion path the 1c-iv-prep
     * slice established remains intact and idempotent: a lease going Connected
     * (TransportLive: Connecting → Attaching) plus the active-pane capture landing
     * (SeedLanded: Attaching → Live) reaches Live with no inline Live mirror.
     */
    @Test
    fun reachesLive_fromRealFeedbackAlone_withoutInlineLiveMirror() {
        val cold = bridge(warm = false)
        cold.observeInlineTransition("Connecting", host, sessionA)
        assertEquals(true, cold.shadowState is ConnectionState.Connecting)

        // NO inline Live mirror is fed here — only the real feedback drives the promotion.
        // REAL transport-live feedback promotes Connecting → Attaching.
        cold.observeTransportLive(host, sessionA)
        assertEquals(true, cold.shadowState is ConnectionState.Attaching)

        // REAL active-pane seed-landed feedback promotes Attaching → Live.
        cold.observeSeedLanded(host, sessionA, paneId = "%0")
        assertEquals(true, cold.shadowState is ConnectionState.Live)
        assertEquals(sessionA, (cold.shadowState as ConnectionState.Live).targetId)
    }

    /**
     * A late seed for a SUPERSEDED target must NOT promote the controller (the
     * #686 drop-by-id contract). After switching A → B, a stale SeedLanded for A
     * is dropped; only B's real feedback lands B Live.
     */
    @Test
    fun realSeedFeedback_forSupersededTarget_isDropped() {
        val warm = bridge(warm = true)
        warm.observeInlineTransition("Attaching", host, sessionA)
        warm.landLiveFromRealFeedback(sessionA)
        // Switch to B (controller now targets B, Attaching).
        warm.observeInlineTransition("Attaching", host, sessionB)
        assertEquals(true, warm.shadowState is ConnectionState.Attaching)
        // A stale seed for the superseded A is dropped — controller stays Attaching(B).
        warm.observeSeedLanded(host, sessionA, paneId = "%0")
        assertEquals(true, warm.shadowState is ConnectionState.Attaching)
        // B's real seed lands B Live.
        warm.observeSeedLanded(host, sessionB, paneId = "%0")
        assertEquals(sessionB, (warm.shadowState as ConnectionState.Live).targetId)
    }

    // --- Item 1: the HostKey encoding is ALIGNED so isWarm works -----------------

    /**
     * Regression guard for the review follow-up #1 (the always-false warm
     * predicate). The VM mints the shadow [HostKey] via `hostKeyFor(leaseKey)` and
     * the warm snapshot is keyed on the SAME `hostKeyFor(leaseKey)`. So a warm host
     * routes a fresh open straight to [ConnectionState.Attaching] (warm), and a
     * cold host routes to [ConnectionState.Connecting]. Before the alignment the
     * two encodings differed and isWarm was ALWAYS FALSE — a warm open would have
     * wrongly shown the cold Connecting overlay.
     */
    @Test
    fun warmPredicate_true_forWarmHost_false_forColdHost() {
        val leaseKey = SshLeaseKey(
            host = "example.com",
            port = 22,
            user = "alice",
            credentialId = "7:/home/alice/.ssh/id_ed25519",
        )
        val warmHost = hostKeyFor(leaseKey)
        val target = SessionId("7/main")

        // WARM: the snapshot reports this exact hostKeyFor-minted host as warm.
        val warm = ConnectionControllerShadowBridge(
            warmSnapshot = { it == warmHost },
        )
        // A fresh open of the warm host → Attaching (warm predicate TRUE).
        warm.observeInlineTransition("Connecting", warmHost, target)
        assertTrue(
            "aligned encoding: a warm host opens straight to Attaching",
            warm.shadowState is ConnectionState.Attaching,
        )

        // COLD: the snapshot reports nothing warm.
        val cold = ConnectionControllerShadowBridge(
            warmSnapshot = { false },
        )
        cold.observeInlineTransition("Connecting", warmHost, target)
        assertTrue(
            "cold host opens to Connecting",
            cold.shadowState is ConnectionState.Connecting,
        )

        // The encoding the warm snapshot keys on and the host the bridge is fed are
        // the SAME hostKeyFor mint — the alignment that makes the predicate fire.
        assertEquals(warmHost, hostKeyFor(leaseKey))
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
