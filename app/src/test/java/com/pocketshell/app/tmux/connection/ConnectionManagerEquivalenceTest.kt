package com.pocketshell.app.tmux.connection

import com.pocketshell.core.connection.Clock
import com.pocketshell.core.connection.ConnectionState
import com.pocketshell.core.connection.HostKey
import com.pocketshell.core.connection.SessionId
import com.pocketshell.core.connection.TmuxPort
import com.pocketshell.core.connection.TransportPort
import com.pocketshell.core.connection.TransportUpDown
import com.pocketshell.core.ssh.SshLeaseKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EPIC #687 Phase-2, slice 1c-iv-b-B2 — the equivalence proof, now with the
 * controller's TRANSPORT inputs ORIGINATING from the [ConnectionEffectDriver] over
 * REAL port flows, NOT from the manager facade.s mirror feeds (deleted).
 *
 * Before B2 the manager exposed `observeTransportLive` / `observeTransportDropped`
 * mirror methods that the VM called from its inline lifecycle. B2 (#742) DELETED
 * those mirror feeds (D22 hard-cut): the driver now SUBMITS
 * [com.pocketshell.core.connection.ConnectionEvent.TransportLive] when the real
 * [TransportPort.transportEvents] emits an `Up` for the controller's current host,
 * and [com.pocketshell.core.connection.ConnectionEvent.TransportDropped] when the
 * real [TmuxPort.disconnected] oracle flips `true`. This suite drives those events
 * THROUGH the driver's real-flow collectors and asserts the SAME parity the pre-B2
 * suite pinned stays green — proving the substitution preserves behavior.
 *
 * The INTENT (Enter/Switch via the manager's TYPED intent entrypoints —
 * [ConnectionManager.enter] / [ConnectionManager.switchTo]
 * etc., epic #792 Slice A which deleted the string mirror), the SEED feedback
 * ([ConnectionManager.observeSeedLanded]), and the lifecycle
 * (background/foreground/network) still flow through the manager — those are not moved in
 * B2. Only the two transport events became driver-fed.
 *
 *  1. On every PRESERVED behavior (cold connect, switch, beyond-grace foreground,
 *     network change, target-gone, exhausted-reconnect), the controller.s
 *     projected `ConnectionStatus` NAME MATCHES the inline `connectionStatusFor`
 *     status name the suite pins.
 *
 *  2. On the TWO approved CHANGED paths the controller DIVERGES exactly as approved:
 *       (i)  a recoverable transport drop on a live channel → silent `Reconnecting`.
 *       (ii) a within-grace foreground → stays `Connected`/no-reconnect.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionManagerEquivalenceTest {

    /** A controllable virtual clock for the within/beyond-grace boundary. */
    private class TestClock(var now: Long = 0L) : Clock {
        override fun nowMs(): Long = now
    }

    private val host = HostKey("alice@example.com:22/7")
    private val sessionA = SessionId("7/main")
    private val sessionB = SessionId("7/build")

    /**
     * A [TransportPort] test double with an injectable [isWarm] snapshot (the
     * controller's grace predicate consults it synchronously) AND an emittable
     * [transportEvents] flow the driver collects.
     */
    private class FakeTransportPort(private val warm: (HostKey) -> Boolean) : TransportPort {
        val transportEventsFlow = MutableSharedFlow<TransportUpDown>(extraBufferCapacity = 32)
        override val transportEvents: Flow<TransportUpDown> = transportEventsFlow
        override fun isWarm(host: HostKey): Boolean = warm(host)
    }

    /** A [TmuxPort] test double whose [disconnected] oracle the driver collects. */
    private class FakeTmuxPort : TmuxPort {
        val disconnectedFlow = MutableSharedFlow<Boolean>(extraBufferCapacity = 32)
        override val disconnected: Flow<Boolean> = disconnectedFlow
    }

    /**
     * The manager + the driver, wired EXACTLY as production wires them (B2): the driver
     * observes the manager's real [com.pocketshell.core.connection.ConnectionController]
     * and submits the transport events from the fake ports' real flows. The harness
     * surfaces helpers that emit those flows so a test reads like the old mirror calls
     * but now drives the controller THROUGH the driver.
     */
    private class Harness(
        scope: CoroutineScope,
        clock: TestClock,
        warm: (HostKey) -> Boolean,
    ) {
        val transport = FakeTransportPort(warm)
        val tmux = FakeTmuxPort()
        val manager = ConnectionManager(clock = clock, transport = transport)
        val driver = ConnectionEffectDriver(
            controller = manager.connectionController,
            tmuxPort = tmux,
            transportPort = transport,
            scope = scope,
        ).also { it.start() }

        /** The displayed status name, projected from the controller's state. */
        fun statusName(): String = manager.statusName
        val state: ConnectionState get() = manager.state

        // --- the inline INTENT / lifecycle feeds (manager-driven; typed since #792 A) -
        // Epic #792 Slice A deleted the string `observeInlineTransition` mirror; the
        // manager now exposes TYPED intent entrypoints. This helper preserves the
        // suite's `inline("Name", …)` call shape by routing each name to the SAME typed
        // entrypoint the VM now calls (1:1 with the deleted string-mirror branches).
        fun inline(name: String, host: HostKey?, target: SessionId?) {
            when (name) {
                "Idle" -> Unit
                "Connecting" -> if (host != null && target != null) manager.enter(host, target)
                "Attaching" -> if (host != null && target != null) manager.switchTo(host, target)
                "Live" -> if (host != null && target != null) manager.revealLive(host, target)
                "Reconnecting" ->
                    if (host != null && target != null) manager.escalateReconnecting(host, target)
                "Unreachable" -> manager.escalateUnreachable()
                "Gone" -> if (target != null) manager.markGone(target)
                else -> error("unmapped inline name: $name")
            }
        }
        fun background() = manager.observeBackground()
        fun foreground() = manager.observeForeground()
        fun networkChanged(validated: Boolean) = manager.observeNetworkChanged(validated)
        fun seedLanded(host: HostKey, target: SessionId, paneId: String = "%0") =
            manager.observeSeedLanded(host, target, paneId)

        // --- the REAL transport flows the DRIVER now submits from (B2) ---------------
        /** Emit the real lease-`Up` edge → driver submits TransportLive (current host). */
        suspend fun transportUp(host: HostKey) =
            transport.transportEventsFlow.emit(TransportUpDown.Up(host))

        /** Emit the real control-channel drop oracle → driver submits TransportDropped. */
        suspend fun transportDropped() = tmux.disconnectedFlow.emit(true)

        /**
         * Land the controller Live for [target] from REAL FLOW feedback — the driver
         * submits TransportLive on the real lease-`Up` edge for the current host, then
         * the seed landing promotes Attaching → Live.
         */
        suspend fun landLiveFromRealFeedback(target: SessionId, host: HostKey) {
            transportUp(host)
            seedLanded(host, target)
        }
    }

    private fun runHarness(
        clock: TestClock = TestClock(),
        warm: (HostKey) -> Boolean = { true },
        body: suspend Harness.() -> Unit,
    ) = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val h = Harness(scope, clock, warm)
        h.body()
        scope.cancel()
    }

    // --- PRESERVED behaviors: controller MUST match the inline status name -----------

    @Test
    fun coldConnect_thenLive_matchesInline() = runHarness(warm = { false }) {
        // Cold host (not warm): inline emits Connecting; the controller reaches Live
        // from REAL feedback (driver submits TransportLive on the lease-Up edge:
        // Connecting → Attaching; active-pane SeedLanded promotes Attaching → Live).
        inline("Connecting", host, sessionA)
        assertEquals("Connecting", statusName())
        // The inline Live mirror is the AUTHORITATIVE reveal moment and promotes to
        // Live in lockstep (the VM only emits Live once the pane is seeded + revealed).
        inline("Live", host, sessionA)
        assertEquals("Connected", statusName())
        // The REAL flow feeds remain idempotent — they keep the controller Live.
        landLiveFromRealFeedback(sessionA, host)
        assertEquals("Connected", statusName())
    }

    @Test
    fun warmOpen_attaching_matchesInlineSwitching() = runHarness(warm = { true }) {
        // Warm host: inline emits Attaching (→ Switching). The warm predicate must be
        // TRUE so the open routes straight to Attaching (no Connecting overlay).
        inline("Attaching", host, sessionA)
        assertEquals("Switching", statusName())
        inline("Live", host, sessionA)
        assertEquals("Connected", statusName())
        landLiveFromRealFeedback(sessionA, host)
        assertEquals("Connected", statusName())
    }

    @Test
    fun sameHostSwitch_aToB_matchesInline() = runHarness(warm = { true }) {
        inline("Attaching", host, sessionA)
        inline("Live", host, sessionA)
        landLiveFromRealFeedback(sessionA, host)
        assertEquals("Connected", statusName())
        // Fast-switch to B: inline emits Attaching (→ Switching) for B.
        inline("Attaching", host, sessionB)
        assertEquals("Switching", statusName())
        // REAL seed feedback for B lands Live on B.
        inline("Live", host, sessionB)
        landLiveFromRealFeedback(sessionB, host)
        assertEquals("Connected", statusName())
        assertEquals(sessionB, (state as ConnectionState.Live).targetId)
    }

    @Test
    fun beyondGraceForeground_reconnect_matchesInline() {
        val clock = TestClock(now = 0L)
        runHarness(clock = clock, warm = { true }) {
            inline("Attaching", host, sessionA)
            inline("Live", host, sessionA)
            landLiveFromRealFeedback(sessionA, host)
            assertEquals("Connected", statusName())
            background()
            clock.now = 70_000L // past the 60s grace
            foreground()
            assertEquals("Reconnecting", statusName())
        }
    }

    @Test
    fun beyondGrace_leaseWentCold_reconnect_matchesInline() {
        val clock = TestClock(now = 0L)
        runHarness(clock = clock, warm = { false }) {
            inline("Connecting", host, sessionA)
            inline("Live", host, sessionA)
            landLiveFromRealFeedback(sessionA, host)
            assertEquals("Connected", statusName())
            background()
            clock.now = 10_000L // within 60s window, but lease is cold
            foreground()
            assertEquals("Reconnecting", statusName())
        }
    }

    @Test
    fun networkChange_nonValidated_isNoOp_matchesInline() = runHarness(warm = { true }) {
        inline("Attaching", host, sessionA)
        inline("Live", host, sessionA)
        landLiveFromRealFeedback(sessionA, host)
        networkChanged(validated = false)
        assertEquals("Connected", statusName())
    }

    @Test
    fun networkChange_validatedHandoff_reconnects_matchesInline() = runHarness(warm = { true }) {
        inline("Attaching", host, sessionA)
        inline("Live", host, sessionA)
        landLiveFromRealFeedback(sessionA, host)
        networkChanged(validated = true)
        assertEquals("Reconnecting", statusName())
    }

    @Test
    fun targetGone_matchesInlineFailed() = runHarness(warm = { true }) {
        inline("Attaching", host, sessionA)
        inline("Live", host, sessionA)
        landLiveFromRealFeedback(sessionA, host)
        inline("Gone", host, sessionA)
        assertEquals("Failed", statusName())
        assertEquals(true, state is ConnectionState.Gone)
    }

    @Test
    fun unreachable_afterExhaustedReconnect_matchesInlineFailed() = runHarness(warm = { true }) {
        inline("Connecting", host, sessionA)
        inline("Live", host, sessionA)
        landLiveFromRealFeedback(sessionA, host)
        inline("Unreachable", host, sessionA)
        assertEquals("Failed", statusName())
        assertEquals(true, state is ConnectionState.Unreachable)
    }

    // --- APPROVED DIVERGENCES: controller MUST differ (do NOT make them match) -------

    /**
     * APPROVED DIVERGENCE #1 (recoverable drop, #685): a transport drop on a LIVE
     * channel. The driver-submitted [com.pocketshell.core.connection.ConnectionEvent.TransportDropped]
     * (from the REAL control-channel oracle, NOT the deleted mirror) heals the
     * controller silently → calm `Reconnecting`, NEVER a `Failed` band.
     */
    @Test
    fun divergence1_recoverableDrop_inlineFailed_controllerSilentReconnecting() =
        runHarness(warm = { true }) {
            inline("Attaching", host, sessionA)
            inline("Live", host, sessionA)
            landLiveFromRealFeedback(sessionA, host)
            assertEquals("Connected", statusName())
            val inlineStatusOnDrop = "Failed"
            // The DRIVER submits TransportDropped from the real disconnect oracle.
            transportDropped()
            assertEquals(true, state is ConnectionState.Reattaching)
            assertEquals("Reconnecting", statusName())
            assertNotEquals(
                "controller must NOT show the inline scary Failed band on a recoverable drop",
                inlineStatusOnDrop,
                statusName(),
            )
        }

    /**
     * APPROVED DIVERGENCE #2 (within-grace foreground, #685 Bug-A): bg → fg WITHIN
     * the 60s grace window with the lease still warm. The controller rides the warm
     * lease → Reattaching (no probe), and a REAL reseed lands it back to Live →
     * Connected with NO reconnect.
     */
    @Test
    fun divergence2_withinGraceForeground_inlineProbeReconnect_controllerStaysLive() {
        val clock = TestClock(now = 0L)
        runHarness(clock = clock, warm = { true }) {
            inline("Attaching", host, sessionA)
            inline("Live", host, sessionA)
            landLiveFromRealFeedback(sessionA, host)
            assertEquals("Connected", statusName())
            background()
            clock.now = 5_000L // within grace, lease warm
            foreground()
            assertEquals(true, state is ConnectionState.Reattaching)
            val inlineStatusOnWithinGraceForeground = "Reconnecting"
            seedLanded(host, sessionA)
            assertEquals("Connected", statusName())
            assertNotEquals(
                "controller must NOT churn through the inline probe Reconnecting within grace",
                inlineStatusOnWithinGraceForeground,
                statusName(),
            )
        }
    }

    // --- B2: the controller's transport inputs ORIGINATE from the driver ---------

    /**
     * The driver reaches [ConnectionState.Live] from the REAL FLOW transport input —
     * the lease-`Up` edge it submits as TransportLive — with NO manager mirror feed at
     * all. A cold open (Connecting) + the driver-submitted TransportLive promotes to
     * Attaching; the seed landing promotes to Live. This is the B2 replacement for the
     * deleted `observeTransportLive` mirror, proven through the driver.
     */
    @Test
    fun reachesLive_fromDriverSubmittedTransportLive_overRealFlow() =
        runHarness(warm = { false }) {
            inline("Connecting", host, sessionA)
            assertEquals(true, state is ConnectionState.Connecting)
            // The DRIVER submits TransportLive on the real lease-Up edge for the host.
            transportUp(host)
            assertEquals(true, state is ConnectionState.Attaching)
            // The active-pane seed landing promotes Attaching → Live.
            seedLanded(host, sessionA)
            assertEquals(true, state is ConnectionState.Live)
            assertEquals(sessionA, (state as ConnectionState.Live).targetId)
        }

    /**
     * A lease-`Up` edge for an UNRELATED host must NOT spuriously promote the
     * controller. The driver only submits TransportLive when the edge host matches the
     * controller's current host — reproducing the deleted mirror's per-target gate.
     */
    @Test
    fun driverIgnoresTransportUp_forNonCurrentHost() = runHarness(warm = { false }) {
        inline("Connecting", host, sessionA)
        assertEquals(true, state is ConnectionState.Connecting)
        // An Up for a DIFFERENT host — the driver must NOT submit TransportLive.
        transportUp(HostKey("bob@other:22/9"))
        assertEquals(
            "a lease-up for an unrelated host must not promote the controller",
            true,
            state is ConnectionState.Connecting,
        )
        // The matching host's Up DOES promote.
        transportUp(host)
        assertEquals(true, state is ConnectionState.Attaching)
    }

    /**
     * The driver-submitted [com.pocketshell.core.connection.ConnectionEvent.TransportDropped]
     * (from the real disconnect oracle) is self-gated by the controller's reducer to a
     * live-ish state — reproducing the deleted inline `Connected` gate. A drop while
     * Idle is a no-op (the controller has no live channel to heal).
     */
    @Test
    fun driverTransportDropped_isNoOp_whenControllerIdle() = runHarness(warm = { true }) {
        assertEquals(true, state is ConnectionState.Idle)
        transportDropped()
        assertEquals(
            "a transport drop with no live channel is a controller no-op",
            true,
            state is ConnectionState.Idle,
        )
    }

    /**
     * A late seed for a SUPERSEDED target must NOT promote the controller (the #686
     * drop-by-id contract). After switching A → B, a stale SeedLanded for A is dropped;
     * only B's real feedback lands B Live.
     */
    @Test
    fun realSeedFeedback_forSupersededTarget_isDropped() = runHarness(warm = { true }) {
        inline("Attaching", host, sessionA)
        landLiveFromRealFeedback(sessionA, host)
        inline("Attaching", host, sessionB)
        assertEquals(true, state is ConnectionState.Attaching)
        seedLanded(host, sessionA) // stale seed for superseded A — dropped
        assertEquals(true, state is ConnectionState.Attaching)
        seedLanded(host, sessionB) // B's seed lands B Live
        assertEquals(sessionB, (state as ConnectionState.Live).targetId)
    }

    // --- Epic #792 Slice A: typed INTENT entrypoints drive the controller directly ---

    /**
     * The manager's TYPED [ConnectionManager.enter] drives the controller's
     * OPEN intent directly (no string mirror). A warm host routes straight to
     * [ConnectionState.Attaching]; a cold host to [ConnectionState.Connecting] — the
     * controller's own warm predicate decides, exactly as the deleted `"Connecting"`
     * string-mirror branch did.
     */
    @Test
    fun typedEnter_drivesControllerOpenIntent_directly() = runHarness(warm = { true }) {
        manager.enter(host, sessionA)
        assertEquals(true, state is ConnectionState.Attaching)
        assertEquals(sessionA, state.let { (it as ConnectionState.Attaching).targetId })
    }

    @Test
    fun typedEnter_coldHost_routesToConnecting_directly() = runHarness(warm = { false }) {
        manager.enter(host, sessionA)
        assertEquals(true, state is ConnectionState.Connecting)
    }

    /**
     * The manager's TYPED [ConnectionManager.switchTo] drives a same-host
     * SWITCH directly: from a live-ish state it re-targets to [ConnectionState.Attaching]
     * on the new id WITHOUT a re-handshake; from Idle (the warm same-host OPEN case) it is
     * an Enter the warm predicate routes straight to Attaching. Same routing the deleted
     * `"Attaching"` string-mirror branch produced.
     */
    @Test
    fun typedSwitchTo_fromLive_reTargetsToAttaching_directly() = runHarness(warm = { true }) {
        manager.enter(host, sessionA)
        manager.revealLive(host, sessionA)
        landLiveFromRealFeedback(sessionA, host)
        assertEquals(true, state is ConnectionState.Live)
        // Switch to B: re-target to Attaching on B (no Connecting, no re-handshake).
        manager.switchTo(host, sessionB)
        assertEquals(true, state is ConnectionState.Attaching)
        assertEquals(sessionB, state.let { (it as ConnectionState.Attaching).targetId })
    }

    @Test
    fun typedSwitchTo_fromIdle_warmOpensToAttaching_directly() = runHarness(warm = { true }) {
        assertEquals(true, state is ConnectionState.Idle)
        manager.switchTo(host, sessionA)
        assertEquals(true, state is ConnectionState.Attaching)
        assertEquals(sessionA, state.let { (it as ConnectionState.Attaching).targetId })
    }

    // --- Item 1: the HostKey encoding is ALIGNED so isWarm works -----------------

    @Test
    fun warmPredicate_true_forWarmHost_false_forColdHost() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val leaseKey = SshLeaseKey(
            host = "example.com",
            port = 22,
            user = "alice",
            credentialId = "7:/home/alice/.ssh/id_ed25519",
        )
        val warmHost = hostKeyFor(leaseKey)
        val target = SessionId("7/main")

        val warmHarness = Harness(scope, TestClock(), warm = { it == warmHost })
        warmHarness.inline("Connecting", warmHost, target)
        assertTrue(
            "aligned encoding: a warm host opens straight to Attaching",
            warmHarness.state is ConnectionState.Attaching,
        )

        val coldHarness = Harness(scope, TestClock(), warm = { false })
        coldHarness.inline("Connecting", warmHost, target)
        assertTrue(
            "cold host opens to Connecting",
            coldHarness.state is ConnectionState.Connecting,
        )

        assertEquals(warmHost, hostKeyFor(leaseKey))
        scope.cancel()
    }

    // --- The status-name projection mapper (§1 seam-table 1:1) -------------------

    @Test
    fun statusNameMapper_isThePinned1to1Projection() {
        assertEquals(
            "Idle",
            ConnectionManager.statusNameFor(ConnectionState.Idle),
        )
        assertEquals(
            "Connecting",
            ConnectionManager.statusNameFor(
                ConnectionState.Connecting(host, sessionA),
            ),
        )
        assertEquals(
            "Switching",
            ConnectionManager.statusNameFor(
                ConnectionState.Attaching(host, sessionA),
            ),
        )
        assertEquals(
            "Connected",
            ConnectionManager.statusNameFor(
                ConnectionState.Live(host, sessionA),
            ),
        )
        assertEquals(
            "Reconnecting",
            ConnectionManager.statusNameFor(
                ConnectionState.Reattaching(host, sessionA),
            ),
        )
        assertEquals(
            "Reconnecting",
            ConnectionManager.statusNameFor(
                ConnectionState.Reconnecting(host, sessionA, attempt = 1),
            ),
        )
        assertEquals(
            "Failed",
            ConnectionManager.statusNameFor(
                ConnectionState.Gone(host, sessionA),
            ),
        )
        assertEquals(
            "Failed",
            ConnectionManager.statusNameFor(
                ConnectionState.Unreachable(host, sessionA),
            ),
        )
    }
}
