package com.pocketshell.core.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #2112 — the background-grace / lease-idle-TTL boundary CLASS.
 *
 * The #685 root cause was three disagreeing clocks, and the cure was to collapse the
 * decision onto ONE. The passive pair still pins that literally
 * (`TmuxSessionViewModelPassiveReconnectTest` asserts `PASSIVE_DISCONNECT_GRACE_MS ==
 * DEFAULT_IDLE_TTL_MILLIS`), but the BACKGROUND grace is 90 s
 * ([ConnectionController.DEFAULT_GRACE_MS], #1123 60 s -> 300 s, #1159 300 s -> 90 s)
 * against a 60 s `SshLeaseManager.DEFAULT_IDLE_TTL_MILLIS`, and nothing related them.
 *
 * The step-1 finding (recorded on #2112) is **(a)**: the divergence is deliberate and the
 * lease IS held for the whole window — during the App-level background grace the `-CC`
 * client still holds its lease (refCount >= 1), so the idle-TTL clock never starts; the
 * teardown that finally releases it runs with the process stopped, which closes the
 * transport IMMEDIATELY instead of parking it warm. The two constants therefore measure
 * DIFFERENT things and must not be collapsed to one number. What has to be true instead
 * is pinned here:
 *
 *  - the 60 s lease-TTL instant is NOT a grace boundary — a return at 59 999 / 60 000 /
 *    60 001 / 75 000 / 89 999 ms is equally within grace (the whole reported 60-90 s
 *    "divergence window" behaves as one region);
 *  - the ONLY boundary is the grace deadline itself (90 000 ms, exclusive);
 *  - the within-grace promise is CONJOINED with warmth, so if a lease ever did go cold
 *    inside the window the controller degrades honestly to `Reconnecting` rather than
 *    promising a zero-reconnect reattach it cannot keep.
 *
 * The mutations these are written to redden: collapsing `DEFAULT_GRACE_MS` back to
 * 60 000 (the "one clock" reflex) reddens every point past 60 s; dropping the
 * `consultWarm` conjunct from `onForeground` reddens the cold half; relaxing `now <
 * deadline` to `<=` reddens the exact-deadline case.
 *
 * The cross-module relation (this window vs the real lease pool) is guarded in `:app` by
 * `Issue2112GraceLeaseTtlRelationTest`, the only module that sees both constants.
 */
class Issue2112GraceLeaseTtlBoundaryTest {

    private val host = HostKey("alice@host:22")
    private val target = SessionId("A")

    /**
     * The instants that make up the class. The reported symptom (a return at 75 s) is ONE
     * member of it; the lease-TTL boundary on either side and the grace boundary on either
     * side are the rest.
     */
    private val leaseTtlMs = 60_000L
    private val justInsideLeaseTtl = leaseTtlMs - 1L
    private val exactlyLeaseTtl = leaseTtlMs
    private val justPastLeaseTtl = leaseTtlMs + 1L
    private val reportedDivergenceMidpoint = 75_000L
    private val justInsideGrace = ConnectionController.DEFAULT_GRACE_MS - 1L
    private val exactlyGrace = ConnectionController.DEFAULT_GRACE_MS
    private val justPastGrace = ConnectionController.DEFAULT_GRACE_MS + 1L

    @Test
    fun `the divergence window is real - the grace outruns the lease idle TTL`() {
        // The premise of the whole class. If these ever become equal the boundary cases
        // below stop testing anything, so state the premise instead of assuming it.
        assertTrue(
            "issue #2112 only exists while the background grace outruns the lease idle TTL; " +
                "grace=${ConnectionController.DEFAULT_GRACE_MS} ttl=$leaseTtlMs",
            ConnectionController.DEFAULT_GRACE_MS > leaseTtlMs,
        )
        assertNotEquals(
            "the 60s constant here mirrors SshLeaseManager.DEFAULT_IDLE_TTL_MILLIS",
            ConnectionController.DEFAULT_GRACE_MS,
            leaseTtlMs,
        )
    }

    // --- Within grace: the lease-TTL instant is NOT a boundary -------------

    @Test
    fun `a warm return just inside the lease idle TTL reattaches`() {
        assertEquals(
            ConnectionState.Reattaching(host, target),
            foregroundAfter(justInsideLeaseTtl, leaseWarmOnReturn = true),
        )
    }

    @Test
    fun `a warm return exactly at the lease idle TTL reattaches`() {
        assertEquals(
            ConnectionState.Reattaching(host, target),
            foregroundAfter(exactlyLeaseTtl, leaseWarmOnReturn = true),
        )
    }

    @Test
    fun `a warm return just past the lease idle TTL still reattaches`() {
        // The load-bearing point of #2112: crossing the LEASE clock changes nothing,
        // because that clock is not running during the background hold. Collapsing
        // DEFAULT_GRACE_MS to 60s reddens exactly here.
        assertEquals(
            ConnectionState.Reattaching(host, target),
            foregroundAfter(justPastLeaseTtl, leaseWarmOnReturn = true),
        )
    }

    @Test
    fun `a warm return at 75s - the reported divergence midpoint - reattaches`() {
        assertEquals(
            ConnectionState.Reattaching(host, target),
            foregroundAfter(reportedDivergenceMidpoint, leaseWarmOnReturn = true),
        )
    }

    @Test
    fun `a warm return just inside the grace deadline reattaches`() {
        assertEquals(
            ConnectionState.Reattaching(host, target),
            foregroundAfter(justInsideGrace, leaseWarmOnReturn = true),
        )
    }

    // --- The one real boundary: the grace deadline itself ------------------

    @Test
    fun `a warm return exactly at the grace deadline reconnects`() {
        // `now < deadline` is strict, so the deadline instant is already beyond grace.
        assertEquals(
            ConnectionState.Reconnecting(host, target, attempt = 1),
            foregroundAfter(exactlyGrace, leaseWarmOnReturn = true),
        )
    }

    @Test
    fun `a warm return just past the grace deadline reconnects`() {
        assertEquals(
            ConnectionState.Reconnecting(host, target, attempt = 1),
            foregroundAfter(justPastGrace, leaseWarmOnReturn = true),
        )
    }

    // --- The 75s journey makes zero reconnect, not merely one good state ---

    @Test
    fun `the 75s return heals without ever entering a reconnect or a cold connect`() {
        val clock = FakeClock()
        val transport = FakeTransportPort()
        val controller = controller(clock, transport).bringLive(transport)
        val seen = mutableListOf(controller.state.value)

        controller.submit(ConnectionEvent.Background)
        seen += controller.state.value
        clock.advanceBy(reportedDivergenceMidpoint)

        controller.submit(ConnectionEvent.Foreground)
        seen += controller.state.value
        controller.submit(ConnectionEvent.TransportLive)
        seen += controller.state.value
        controller.submit(ConnectionEvent.SeedLanded(target, "%0"))
        seen += controller.state.value

        assertEquals(
            "the 75s return must land back on the same live target",
            ConnectionState.Live(host, target),
            controller.state.value,
        )
        assertTrue(
            "a within-grace return must never reconnect or re-dial; saw $seen",
            seen.none { it is ConnectionState.Reconnecting || it is ConnectionState.Connecting },
        )
    }

    // --- The negative half: a cold lease inside the window stays honest ----
    //
    // Class coverage (G2): the within-grace promise is `deadline && warm`, never the
    // deadline alone. If some future change DOES release the lease at background time
    // (the counterfactual `:app` `Issue2112GraceLeaseTtlRelationTest` drives against the
    // real pool), the user must get an honest silent reconnect, not a promise of a
    // zero-reconnect reattach over a transport that is gone.

    @Test
    fun `a cold return just inside the lease idle TTL reconnects honestly`() {
        assertEquals(
            ConnectionState.Reconnecting(host, target, attempt = 1),
            foregroundAfter(justInsideLeaseTtl, leaseWarmOnReturn = false),
        )
    }

    @Test
    fun `a cold return just past the lease idle TTL reconnects honestly`() {
        assertEquals(
            ConnectionState.Reconnecting(host, target, attempt = 1),
            foregroundAfter(justPastLeaseTtl, leaseWarmOnReturn = false),
        )
    }

    @Test
    fun `a cold return at 75s reconnects honestly instead of promising a reattach`() {
        assertEquals(
            ConnectionState.Reconnecting(host, target, attempt = 1),
            foregroundAfter(reportedDivergenceMidpoint, leaseWarmOnReturn = false),
        )
    }

    @Test
    fun `a cold return just inside the grace deadline reconnects honestly`() {
        assertEquals(
            ConnectionState.Reconnecting(host, target, attempt = 1),
            foregroundAfter(justInsideGrace, leaseWarmOnReturn = false),
        )
    }

    // ---- harness ----

    /**
     * Live -> Background -> advance [elapsedMs] -> Foreground, with the lease warm or
     * cold at the moment of return. Returns the state the controller resolved to.
     */
    private fun foregroundAfter(elapsedMs: Long, leaseWarmOnReturn: Boolean): ConnectionState {
        val clock = FakeClock()
        val transport = FakeTransportPort()
        val controller = controller(clock, transport).bringLive(transport)

        controller.submit(ConnectionEvent.Background)
        assertTrue(
            "the fixture must actually be backgrounded before the return",
            controller.state.value is ConnectionState.Backgrounded,
        )
        transport.setWarm(host, leaseWarmOnReturn)
        clock.advanceBy(elapsedMs)

        controller.submit(ConnectionEvent.Foreground)
        return controller.state.value
    }

    /** Production shape: the REAL default grace window and the REAL default ladder. */
    private fun controller(clock: FakeClock, transport: FakeTransportPort) =
        ConnectionController(clock = clock, transport = transport)

    private fun ConnectionController.bringLive(
        transport: FakeTransportPort,
    ): ConnectionController {
        transport.setWarm(host, false)
        submit(ConnectionEvent.Enter(host, target))
        transport.setWarm(host, true)
        submit(ConnectionEvent.TransportLive)
        submit(ConnectionEvent.SeedLanded(target, "%0"))
        assertEquals(ConnectionState.Live(host, target), state.value)
        return this
    }
}
