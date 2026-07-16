package com.pocketshell.core.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1633 — the connect-then-die livelock, and the stability window that ends it.
 *
 * ## The reported defect (issue #1610, the maintainer's mobile-internet storm)
 * On mobile internet the app reconnects every ~5 seconds forever and never gives up.
 * The forensics (#1610) are unambiguous: across 15,456 log lines `retryDelayMs` is
 * non-zero only 24 times, ALL outside the bursts. Inside a burst it is **always**
 * `attempt=1 retryDelayMs=0`, and the burst only ever ends when the maintainer
 * backgrounds the app.
 *
 * ## The root cause this suite pins
 * The attempt counter's reset condition was **"a dial succeeded"**, not **"a
 * connection proved stable"**. A link that dies ~5s after every successful dial
 * therefore looks like an unbroken series of FIRST attempts: backoff never engages,
 * the attempt budget is never reached, and [ConnectionState.Unreachable] — the
 * terminal give-up — is structurally dead code. The fix is the CrashLoopBackOff /
 * gRPC verified-acceptance model: the episode counter commits only after the link
 * has been continuously [ConnectionState.Live] for [STABILITY_WINDOW_MS].
 *
 * ## Why these are pure virtual-clock reducer tests (no `assumeTrue`, no skip)
 * [ConnectionController] is a pure synchronous reducer over an injected [Clock] with
 * NO coroutine and NO IO, so the maintainer's exact logged event sequence — the one
 * `TmuxSessionViewModel.scheduleAutoReconnectBody` emits on every rung — is driven
 * here deterministically and hard-asserted. The load-bearing assertions never skip.
 *
 * ## This file deliberately compiles against the UNFIXED reducer (red -> green, G1)
 * It touches ONLY the pre-#1633 public API — the `(clock, transport)` constructor and
 * the existing events — and mirrors the two window constants locally rather than
 * importing them. That is what lets a reviewer revert `ConnectionController.kt` alone
 * and watch these tests fail RED with the maintainer's exact `attempt=1 retryDelayMs=0`
 * signature, then pass GREEN with the fix restored. The mirrors are pinned against the
 * production constants by `ConnectionControllerJitterTest.kt` so they cannot drift.
 */
class ConnectionControllerEpisodeStabilityTest {

    private val host = HostKey("alexey@135.181.114.209:22")
    private val a = SessionId("A")

    /** The #1331 design ladder: 8 rungs, `[0,1,2,5,10,20,30,30]s`. */
    private val ladder = listOf(0L, 1_000L, 2_000L, 5_000L, 10_000L, 20_000L, 30_000L, 30_000L)

    /** One observed ladder rung, as the VM effect reads it off `Reconnecting`. */
    private data class Rung(val attempt: Int, val maxAttempts: Int, val retryDelayMs: Long)

    private fun controller(clock: FakeClock): Pair<ConnectionController, FakeTransportPort> {
        val transport = FakeTransportPort()
        return ConnectionController(clock, transport) to transport
    }

    /** Cold Enter -> Connecting -> Attaching -> Live, the normal first connect. */
    private fun ConnectionController.bringLive(transport: FakeTransportPort) {
        transport.setWarm(host, false)
        submit(ConnectionEvent.Enter(host, a))
        transport.setWarm(host, true)
        submit(ConnectionEvent.TransportLive) // Connecting -> Attaching
        submit(ConnectionEvent.SeedLanded(a, "%0")) // Attaching -> Live
        check(state.value is ConnectionState.Live) { "fixture must start Live, was ${state.value}" }
    }

    /**
     * ONE connect-then-die cycle, replaying the EXACT production event sequence the VM
     * emits per burst cycle (`TmuxSessionViewModel.scheduleAutoReconnectBody`):
     * keepalive death -> silent heal fails -> install ladder -> enter ladder -> read the
     * rung -> wait the rung's backoff -> the dial SUCCEEDS -> the link dies [aliveMs] later.
     *
     * Returns the rung the VM would have dialled, or null once the controller has left the
     * ladder (the terminal give-up — the loop this test exists to prove is reachable).
     */
    private fun ConnectionController.driveConnectThenDieCycle(clock: FakeClock, aliveMs: Long): Rung? {
        submit(ConnectionEvent.TransportDropped("keepalive death")) // Live -> Reattaching
        submit(ConnectionEvent.TransportDropped("silent heal failed")) // -> Reconnecting
        setReconnectLadder(ladder) // the VM installs its autoReconnectDelaysMs
        submit(ConnectionEvent.ReconnectLadderEntered) // the VM enters the numbered ladder
        val recon = state.value as? ConnectionState.Reconnecting ?: return null
        val rung = Rung(recon.attempt, recon.maxAttempts, recon.retryDelayMs)
        clock.advanceBy(recon.retryDelayMs) // the VM's `if (delayMs > 0) delay(delayMs)`
        submit(ConnectionEvent.TransportLive) // the rung's dial SUCCEEDS
        submit(ConnectionEvent.SeedLanded(a, "%0")) // ... and the pane seeds -> Live
        check(state.value is ConnectionState.Live) { "the dial succeeded, expected Live, was ${state.value}" }
        clock.advanceBy(aliveMs) // ... and the link dies again ~5s later
        return rung
    }

    /** The ±20% jitter band around a ladder rung's base backoff. Rung 1 (0ms) stays exact. */
    private fun assertWithinJitterBand(baseMs: Long, actualMs: Long, label: String) {
        if (baseMs == 0L) {
            assertEquals("$label: the silent first rung must stay instant", 0L, actualMs)
            return
        }
        val low = (baseMs * 0.8).toLong()
        val high = (baseMs * 1.2).toLong()
        assertTrue(
            "$label: ${actualMs}ms outside the +/-20% jitter band [$low..$high] of ${baseMs}ms",
            actualMs in low..high,
        )
    }

    // ------------------------------------------------------------------
    // The reproduction (D33/G10): the maintainer's exact burst signature.
    // ------------------------------------------------------------------

    /**
     * THE load-bearing reproduction. Drives the maintainer's storm — a link that dies ~5s
     * after every successful dial — and requires that it TERMINATES.
     *
     * On the unfixed reducer this fails with every rung pinned at `attempt=1
     * retryDelayMs=0` and no terminal state after 40 cycles: the maintainer's exact logged
     * signature. With the stability window the episode survives each short-lived Live, the
     * counter advances, the ladder delays engage, and the machine gives up.
     */
    @Test
    fun `connect-then-die cycles escalate the ladder and reach terminal give-up`() {
        val clock = FakeClock()
        val (c, transport) = controller(clock)
        c.bringLive(transport)

        val rungs = mutableListOf<Rung>()
        var cycles = 0
        while (cycles < CYCLE_CAP && c.state.value !is ConnectionState.Unreachable) {
            c.driveConnectThenDieCycle(clock, aliveMs = LINK_LIFETIME_MS)?.let { rungs += it }
            cycles++
        }

        val trace = rungs.joinToString { "attempt=${it.attempt} retryDelayMs=${it.retryDelayMs}" }

        // 1. The loop TERMINATES. On base this is the failure: the burst runs forever and
        //    only the maintainer backgrounding the app ends it.
        assertTrue(
            "the connect-then-die loop never reached terminal give-up after $cycles cycles " +
                "(state=${c.state.value}); observed rungs: [$trace]",
            c.state.value is ConnectionState.Unreachable,
        )
        assertEquals(ConnectionState.Unreachable(host, a), c.state.value)

        // 2. The counter ADVANCES across cycles instead of pinning at 1 (the bad reset).
        assertTrue("expected several escalating rungs, got: [$trace]", rungs.size >= 4)
        assertEquals(
            "the attempt counter must advance once per uncommitted cycle, not pin at 1; " +
                "observed: [$trace]",
            (1..rungs.size).toList(),
            rungs.map { it.attempt },
        )

        // 3. The ladder's BACKOFF engages (base rungs 0,1s,2s,5s,... jittered +/-20%).
        rungs.forEach { rung ->
            assertWithinJitterBand(ladder[rung.attempt - 1], rung.retryDelayMs, "rung ${rung.attempt}")
        }
        assertTrue(
            "backoff never engaged — every rung still waited 0ms (the #1610 signature): [$trace]",
            rungs.any { it.retryDelayMs > 0L },
        )
        // The ladder CLIMBS in order and never re-arms at the bottom. Compare the BASE
        // sequence rather than the jittered samples: rungs 7 and 8 share a 30s base by
        // design, so two adjacent samples may legitimately cross inside their band. Each
        // sample is already pinned to its own base's band above, so base-order + band
        // together fully pin the climb.
        val bases = rungs.map { ladder[it.attempt - 1] }
        assertEquals(
            "the rungs must walk the ladder in order, not re-arm at the bottom: [$trace]",
            ladder.take(rungs.size),
            bases,
        )
        assertTrue("the ladder must actually climb: [$trace]", bases.last() > bases.first())

        // 4. Every rung reports the real 8-rung budget, so the user-visible "(n/8)" is honest.
        rungs.forEach { assertEquals(ladder.size, it.maxAttempts) }
    }

    /**
     * G3 — the give-up path is currently DEAD CODE, so a test that merely "passes" without
     * entering it is vacuous. This asserts [ConnectionState.Unreachable] is genuinely entered
     * AND is terminal: the VM's `enterReconnectLadder` cannot silently re-arm out of it (the
     * `while (state is Reconnecting)` ladder loop breaks), so the manual Reconnect affordance
     * is the only way forward.
     */
    @Test
    fun `terminal give-up is entered and is sticky against ladder re-entry`() {
        val clock = FakeClock()
        val (c, transport) = controller(clock)
        c.bringLive(transport)

        var cycles = 0
        while (cycles < CYCLE_CAP && c.state.value !is ConnectionState.Unreachable) {
            c.driveConnectThenDieCycle(clock, aliveMs = LINK_LIFETIME_MS)
            cycles++
        }
        assertEquals(
            "the give-up arm was never entered — the test would pass vacuously (G3)",
            ConnectionState.Unreachable(host, a),
            c.state.value,
        )

        // The VM's ladder body must not be able to climb back out on its own.
        c.setReconnectLadder(ladder)
        c.submit(ConnectionEvent.ReconnectLadderEntered)
        assertEquals(
            "ReconnectLadderEntered re-armed the ladder out of the terminal state — the " +
                "storm would resume forever",
            ConnectionState.Unreachable(host, a),
            c.state.value,
        )
        c.submit(ConnectionEvent.TransportDropped("late churn"))
        assertEquals(ConnectionState.Unreachable(host, a), c.state.value)

        // Explicit user intent (the manual Reconnect affordance) starts a FRESH episode.
        c.submit(ConnectionEvent.Enter(host, a))
        c.submit(ConnectionEvent.TransportLive)
        c.submit(ConnectionEvent.SeedLanded(a, "%0"))
        assertEquals(ConnectionState.Live(host, a), c.state.value)
        c.submit(ConnectionEvent.TransportDropped("d"))
        c.submit(ConnectionEvent.TransportDropped("d"))
        assertEquals(
            "a manual reconnect must start a fresh episode at attempt 1",
            1,
            (c.state.value as ConnectionState.Reconnecting).attempt,
        )
    }

    /**
     * The reducer must not assume the numbered ladder body is the SOLE submitter of
     * [ConnectionEvent.ReconnectFailed].
     *
     * Orchestrator arbitration on #1633: the passive-grace loop dials `sshLeaseManager.acquire`
     * directly and never submits `ReconnectFailed` today, which is why the counter stays
     * frozen; the sibling #1539 slice wires that loop's failures in. When it does, the event
     * arrives from a NEW caller and — because each mid-cycle handshake success fires
     * `TransportLive` — usually while the controller has ALREADY bounced back to
     * [ConnectionState.Live]. The episode must still escalate from there and terminate.
     *
     * The grace loop's plumbing is #1539's; modelled here SYNTHETICALLY at the controller
     * boundary, which is this slice's unit of proof.
     */
    @Test
    fun `ReconnectFailed fed from the grace loop while Live still escalates to give-up`() {
        val clock = FakeClock()
        val (c, transport) = controller(clock)
        c.setReconnectLadder(ladder)
        c.bringLive(transport)

        val attempts = mutableListOf<Int>()
        var cycles = 0
        while (cycles < CYCLE_CAP && c.state.value !is ConnectionState.Unreachable) {
            // The grace loop's rung failed — reported straight from Live (#1539's wiring).
            c.submit(ConnectionEvent.ReconnectFailed)
            (c.state.value as? ConnectionState.Reconnecting)?.let { attempts += it.attempt }
            // ... and its NEXT dial handshakes, bouncing the controller back to Live.
            c.submit(ConnectionEvent.TransportLive)
            c.submit(ConnectionEvent.SeedLanded(a, "%0"))
            clock.advanceBy(LINK_LIFETIME_MS) // ... and dies ~5s later, as always
            cycles++
        }

        assertEquals(
            "a grace-loop-fed ReconnectFailed must still reach terminal give-up " +
                "(attempts seen: $attempts)",
            ConnectionState.Unreachable(host, a),
            c.state.value,
        )
        assertEquals(
            "a handshake success between rungs must NOT wipe the walk (attempts: $attempts)",
            (2..attempts.size + 1).toList(),
            attempts,
        )
    }

    /**
     * The same new-caller path, but the link genuinely recovers between rungs: a Live that
     * outlasts the stability window COMMITS, so the next grace-loop failure starts over at
     * attempt 1 rather than inheriting the old episode (G6, new-caller variant).
     */
    @Test
    fun `a grace-loop rung after a proven-stable Live starts a fresh episode`() {
        val clock = FakeClock()
        val (c, transport) = controller(clock)
        c.setReconnectLadder(ladder)
        c.bringLive(transport)

        repeat(3) {
            c.submit(ConnectionEvent.ReconnectFailed)
            c.submit(ConnectionEvent.TransportLive)
            c.submit(ConnectionEvent.SeedLanded(a, "%0"))
            clock.advanceBy(LINK_LIFETIME_MS)
        }
        // The link finally holds, well past the stability window.
        clock.advanceBy(STABILITY_WINDOW_MS + 1_000L)
        c.submit(ConnectionEvent.TransportDropped("a much later, unrelated drop"))
        c.submit(ConnectionEvent.TransportDropped("heal failed"))
        assertEquals(
            "a proven-stable Live must commit the episode even when the rungs were " +
                "grace-loop-fed",
            1,
            (c.state.value as ConnectionState.Reconnecting).attempt,
        )
    }

    /**
     * G2 class coverage — the counter-laundering hole a validated network handoff opens.
     *
     * `NetworkChanged(validatedHandoff = true)` on a Live channel proactively re-dials, and
     * it used to hard-arm the ladder at attempt 1. That matters precisely in the
     * maintainer's own environment: #1610 is a flapping MOBILE link, and periodic RAT
     * handovers are exactly what fires this event — so a handoff landing on one of the
     * storm's brief Live moments would reset the counter and the ladder could never climb.
     * An UNCOMMITTED episode must survive a handoff; a proven-stable one must not.
     */
    @Test
    fun `a validated network handoff does not launder an uncommitted episode`() {
        val clock = FakeClock()
        val (c, transport) = controller(clock)
        c.setReconnectLadder(ladder)
        c.bringLive(transport)

        repeat(3) { c.driveConnectThenDieCycle(clock, aliveMs = LINK_LIFETIME_MS) }
        // Back to a short-lived Live, mid-episode.
        c.submit(ConnectionEvent.TransportDropped("d"))
        c.submit(ConnectionEvent.TransportDropped("d"))
        val mid = (c.state.value as ConnectionState.Reconnecting).attempt
        assertTrue("fixture precondition: an episode must be in flight", mid > 1)
        c.submit(ConnectionEvent.TransportLive)
        c.submit(ConnectionEvent.SeedLanded(a, "%0"))
        clock.advanceBy(LINK_LIFETIME_MS) // still well short of the stability window

        c.submit(ConnectionEvent.NetworkChanged(validatedHandoff = true))
        val afterHandoff = c.state.value as ConnectionState.Reconnecting
        assertEquals(
            "a RAT handoff during the storm reset the ladder to attempt 1 — the exact " +
                "environment #1610 was reported on, so the loop would never terminate",
            mid + 1,
            afterHandoff.attempt,
        )
        assertTrue("the resumed rung must carry real backoff", afterHandoff.retryDelayMs > 0L)
    }

    /** The same handoff on a link that PROVED stable still starts fresh at attempt 1 (G6). */
    @Test
    fun `a validated network handoff after a proven-stable Live starts fresh`() {
        val clock = FakeClock()
        val (c, transport) = controller(clock)
        c.setReconnectLadder(ladder)
        c.bringLive(transport)

        repeat(3) { c.driveConnectThenDieCycle(clock, aliveMs = LINK_LIFETIME_MS) }
        c.submit(ConnectionEvent.TransportDropped("d"))
        c.submit(ConnectionEvent.TransportDropped("d"))
        c.submit(ConnectionEvent.TransportLive)
        c.submit(ConnectionEvent.SeedLanded(a, "%0"))
        clock.advanceBy(STABILITY_WINDOW_MS + 1_000L) // the link genuinely recovered

        c.submit(ConnectionEvent.NetworkChanged(validatedHandoff = true))
        val fresh = c.state.value as ConnectionState.Reconnecting
        assertEquals("a handoff on a proven-stable link is a fresh episode", 1, fresh.attempt)
        assertEquals(0L, fresh.retryDelayMs)
    }

    /**
     * G2 class coverage — backgrounding is the maintainer's ONLY escape from the storm
     * today, so a beyond-grace return must be a clean restart, NOT a trip straight to a
     * dead end. The counter arms at 1 (the #1331 design) AND the episode clock restarts
     * with it: an inherited-but-exhausted episode clock would fire an instant, wrong
     * Unreachable on the very first rung failure after the user came back.
     */
    @Test
    fun `a beyond-grace foreground return starts a fresh episode with a clean clock`() {
        val clock = FakeClock()
        val (c, transport) = controller(clock)
        c.setReconnectLadder(ladder)
        c.bringLive(transport)

        // Storm until the episode clock is well past its budget, then escape by backgrounding.
        var cycles = 0
        while (cycles < CYCLE_CAP && c.state.value !is ConnectionState.Unreachable) {
            c.driveConnectThenDieCycle(clock, aliveMs = LINK_LIFETIME_MS)
            cycles++
        }
        c.submit(ConnectionEvent.Enter(host, a)) // the user re-opens after the give-up
        c.submit(ConnectionEvent.TransportLive)
        c.submit(ConnectionEvent.SeedLanded(a, "%0"))
        c.submit(ConnectionEvent.TransportDropped("d"))
        c.submit(ConnectionEvent.TransportDropped("d"))
        assertTrue(c.state.value is ConnectionState.Reconnecting)

        c.submit(ConnectionEvent.Background)
        clock.advanceBy(ConnectionController.DEFAULT_GRACE_MS + 60_000L) // away, beyond grace
        transport.setWarm(host, false)
        c.submit(ConnectionEvent.Foreground)

        val back = c.state.value as ConnectionState.Reconnecting
        assertEquals("a beyond-grace return arms at attempt 1", 1, back.attempt)
        assertEquals("... at the instant first rung", 0L, back.retryDelayMs)
        // The load-bearing half: the episode CLOCK restarted too, so the first rung failure
        // escalates normally instead of tripping a stale budget straight into Unreachable.
        c.submit(ConnectionEvent.ReconnectFailed)
        assertEquals(
            "the returning user inherited a stale, already-exhausted episode clock and was " +
                "dumped straight into Unreachable",
            2,
            (c.state.value as ConnectionState.Reconnecting).attempt,
        )
    }

    // ------------------------------------------------------------------
    // G6 — the LOAD-BEARING negative case: a stable link still resets.
    // ------------------------------------------------------------------

    /**
     * G6. If this is wrong, EVERY normal reconnect escalates spuriously and the app becomes
     * unusable in a new way. A connection that stays Live past the stability window COMMITS
     * the episode: a later, unrelated drop starts fresh at attempt 1 with the full budget and
     * the instant first rung.
     */
    @Test
    fun `a connection that proves stable resets the counter so a later drop starts fresh`() {
        val clock = FakeClock()
        val (c, transport) = controller(clock)
        c.bringLive(transport)

        // Burn three uncommitted cycles: the counter is now deep in the ladder.
        repeat(3) { c.driveConnectThenDieCycle(clock, aliveMs = LINK_LIFETIME_MS) }
        c.submit(ConnectionEvent.TransportDropped("d"))
        c.submit(ConnectionEvent.TransportDropped("d"))
        val escalated = c.state.value as ConnectionState.Reconnecting
        assertTrue(
            "fixture precondition: the counter must be escalated before the stable window",
            escalated.attempt > 1,
        )

        // This rung's dial succeeds and the link STAYS UP past the stability window.
        c.submit(ConnectionEvent.TransportLive)
        c.submit(ConnectionEvent.SeedLanded(a, "%0"))
        assertEquals(ConnectionState.Live(host, a), c.state.value)
        clock.advanceBy(STABILITY_WINDOW_MS + 1_000L)

        // A later, unrelated drop: a FRESH episode, attempt 1, instant first rung.
        c.submit(ConnectionEvent.TransportDropped("unrelated later drop"))
        assertEquals(
            "a proven-stable link must heal silently first, exactly as before",
            ConnectionState.Reattaching(host, a),
            c.state.value,
        )
        c.submit(ConnectionEvent.TransportDropped("heal failed"))
        val fresh = c.state.value as ConnectionState.Reconnecting
        assertEquals(
            "a stable connection did NOT commit the episode — a normal reconnect would " +
                "escalate spuriously (G6)",
            1,
            fresh.attempt,
        )
        assertEquals("the fresh episode must start at the instant first rung", 0L, fresh.retryDelayMs)
        assertEquals("the fresh episode must get the full budget back", ladder.size, fresh.maxAttempts)
    }

    /** G2 class coverage — die-just-before the window escalates; the boundary is exclusive. */
    @Test
    fun `a drop one millisecond before the stability window escalates the same episode`() {
        val clock = FakeClock()
        val (c, transport) = controller(clock)
        c.bringLive(transport)

        c.driveConnectThenDieCycle(clock, aliveMs = LINK_LIFETIME_MS) // attempt 1 burned
        c.submit(ConnectionEvent.TransportDropped("d"))
        c.submit(ConnectionEvent.TransportDropped("d"))
        val before = (c.state.value as ConnectionState.Reconnecting).attempt
        c.submit(ConnectionEvent.TransportLive)
        c.submit(ConnectionEvent.SeedLanded(a, "%0"))

        clock.advanceBy(STABILITY_WINDOW_MS - 1)
        c.submit(ConnectionEvent.TransportDropped("died just short of the window"))
        c.submit(ConnectionEvent.TransportDropped("heal failed"))
        assertEquals(
            "a link that died 1ms short of the stability window must RESUME the episode",
            before + 1,
            (c.state.value as ConnectionState.Reconnecting).attempt,
        )
    }

    /** G2 class coverage — die exactly AT the boundary commits (the window is inclusive). */
    @Test
    fun `a drop exactly at the stability window boundary commits the episode`() {
        val clock = FakeClock()
        val (c, transport) = controller(clock)
        c.bringLive(transport)

        c.driveConnectThenDieCycle(clock, aliveMs = LINK_LIFETIME_MS)
        c.submit(ConnectionEvent.TransportDropped("d"))
        c.submit(ConnectionEvent.TransportDropped("d"))
        assertTrue((c.state.value as ConnectionState.Reconnecting).attempt > 0)
        c.submit(ConnectionEvent.TransportLive)
        c.submit(ConnectionEvent.SeedLanded(a, "%0"))

        clock.advanceBy(STABILITY_WINDOW_MS)
        c.submit(ConnectionEvent.TransportDropped("died exactly at the boundary"))
        c.submit(ConnectionEvent.TransportDropped("heal failed"))
        assertEquals(
            "at exactly the stability window the connection has proven stable — commit",
            1,
            (c.state.value as ConnectionState.Reconnecting).attempt,
        )
    }

    /**
     * G2 class coverage — the episode is bounded by wall-clock too, not only by rung count.
     * A link that flaps slowly enough to stay under the rung budget still gives up within the
     * episode budget instead of grinding forever.
     */
    @Test
    fun `the episode wall-clock budget gives up even when rungs remain`() {
        val clock = FakeClock()
        val (c, transport) = controller(clock)
        // A long flat ladder: the rung budget alone would never exhaust in this window.
        val flat = List(100) { 1_000L }
        c.bringLive(transport)

        var cycles = 0
        while (cycles < CYCLE_CAP && c.state.value !is ConnectionState.Unreachable) {
            c.submit(ConnectionEvent.TransportDropped("d"))
            c.submit(ConnectionEvent.TransportDropped("d"))
            c.setReconnectLadder(flat)
            c.submit(ConnectionEvent.ReconnectLadderEntered)
            val recon = c.state.value as? ConnectionState.Reconnecting ?: break
            clock.advanceBy(recon.retryDelayMs)
            c.submit(ConnectionEvent.TransportLive)
            c.submit(ConnectionEvent.SeedLanded(a, "%0"))
            clock.advanceBy(LINK_LIFETIME_MS)
            cycles++
        }
        assertEquals(
            "the episode wall-clock budget must terminate a slow flap that never exhausts " +
                "the rung budget",
            ConnectionState.Unreachable(host, a),
            c.state.value,
        )
        assertTrue(
            "the episode must survive at least the budget before giving up",
            clock.nowMs() >= EPISODE_BUDGET_MS,
        )
    }

    /**
     * G2 class coverage — a user-initiated switch. DELIBERATE decision (issue #1633): a
     * [ConnectionEvent.Switch] is EXPLICIT USER INTENT and therefore starts a fresh episode,
     * preserving the pre-existing `onSwitch` reset semantics unchanged. Rationale: the #1331
     * design lists Enter/Switch/manual-Reconnect/Send-wake as the intent-reset set, the user
     * is watching and has asked for this target now, and `Switch` is only ever submitted from
     * a user tap — the storm's automatic cycle never emits it, so it cannot launder the
     * counter back to 1.
     */
    @Test
    fun `a user-initiated switch deliberately starts a fresh episode`() {
        val clock = FakeClock()
        val (c, transport) = controller(clock)
        val b = SessionId("B")
        c.bringLive(transport)

        repeat(3) { c.driveConnectThenDieCycle(clock, aliveMs = LINK_LIFETIME_MS) }
        c.submit(ConnectionEvent.TransportDropped("d"))
        c.submit(ConnectionEvent.TransportDropped("d"))
        assertTrue((c.state.value as ConnectionState.Reconnecting).attempt > 1)

        c.submit(ConnectionEvent.Switch(b))
        assertEquals(ConnectionState.Attaching(host, b), c.state.value)
        c.submit(ConnectionEvent.SeedLanded(b, "%1"))
        c.submit(ConnectionEvent.TransportDropped("d"))
        c.submit(ConnectionEvent.TransportDropped("d"))
        val afterSwitch = c.state.value as ConnectionState.Reconnecting
        assertEquals("a user switch is explicit intent — fresh episode at attempt 1", 1, afterSwitch.attempt)
        assertEquals(0L, afterSwitch.retryDelayMs)
    }

    private companion object {
        /**
         * Local MIRRORS of the production windows. Deliberately duplicated rather than
         * imported so this file still compiles against the unfixed reducer and can be run
         * red on base (see the class doc). `ConnectionControllerJitterTest` asserts these
         * equal `ConnectionController.DEFAULT_STABILITY_WINDOW_MS` /
         * `DEFAULT_EPISODE_BUDGET_MS`, so they cannot silently drift.
         */
        private const val STABILITY_WINDOW_MS = 30_000L
        private const val EPISODE_BUDGET_MS = 120_000L

        /** The maintainer's observed cadence: the link dies ~5s after each successful dial. */
        private const val LINK_LIFETIME_MS = 5_000L

        /**
         * A HARD cap on the reproduction loop. It is not a timeout — the reducer is
         * synchronous and cannot hang — it is the bound that turns "loops forever" into a
         * finite, readable failure carrying the observed rung trace.
         */
        private const val CYCLE_CAP = 40

        private const val JITTER_SAMPLES = 200
    }
}
