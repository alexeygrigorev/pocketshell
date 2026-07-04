package com.pocketshell.core.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * Item 6 (#1234): the [ConnectionController] Main/single-thread confinement
 * assertion. The controller's `submit`/`offerSeed` do unguarded read-modify-writes
 * of `_state.value` + the plain `graceDeadlineMs` var, safe ONLY because callers
 * are Main-confined. These tests prove the DEBUG-gated guard converts an
 * off-confinement call into a loud failure while leaving the confined happy path
 * (and release builds) behavior-identical.
 */
class ConnectionControllerConfinementTest {

    private val host = HostKey("h1")
    private val target = SessionId("s1")

    private fun controller(confinementEnabled: Boolean = true): ConnectionController =
        ConnectionController(
            clock = FakeClock(),
            transport = FakeTransportPort(),
            confinementAssertionsEnabled = confinementEnabled,
        )

    /** submit() on the confining (owner) thread never trips — the happy path. */
    @Test
    fun `submit on confined owner thread does not trip the guard`() {
        val controller = controller() // constructed + used on this test thread
        // Repeated same-thread calls: the first latches the owner, the rest match.
        val s1 = controller.submit(ConnectionEvent.Enter(host, target))
        val s2 = controller.submit(ConnectionEvent.Background)
        assertTrue("Enter -> Attaching/Connecting", s1 is ConnectionState.Attaching || s1 is ConnectionState.Connecting)
        assertTrue("Background -> Backgrounded", s2 is ConnectionState.Backgrounded)
    }

    /**
     * submit() from a DIFFERENT thread than the confined owner hard-fails in a
     * DEBUG build. The owner is latched by the first (test-thread) call; a second
     * call marshalled onto a worker thread must trip the assertion.
     */
    @Test
    fun `submit off the confined owner thread trips the guard`() {
        val controller = controller()
        // Latch the owner on the test thread.
        controller.submit(ConnectionEvent.Enter(host, target))

        val thrown = runOnOtherThread { controller.submit(ConnectionEvent.Background) }

        assertNotNull("expected the guard to throw off-thread", thrown)
        assertTrue(
            "expected IllegalStateException, got ${thrown!!::class.java}",
            thrown is IllegalStateException,
        )
        assertTrue(
            "message should name submit + issue: ${thrown.message}",
            thrown.message!!.contains("submit") && thrown.message!!.contains("#1234"),
        )
    }

    /** offerSeed() is a confined mutator too and trips off the owner thread. */
    @Test
    fun `offerSeed off the confined owner thread trips the guard`() {
        val controller = controller()
        controller.submit(ConnectionEvent.Enter(host, target)) // latch owner on test thread

        val thrown = runOnOtherThread { controller.offerSeed(Seed(target, "%0", "frame")) }

        assertNotNull("expected the guard to throw off-thread", thrown)
        assertTrue(thrown is IllegalStateException)
        assertTrue(thrown!!.message!!.contains("offerSeed"))
    }

    /**
     * Debug-gating: with the assertion DISABLED (release-build simulation,
     * `BuildConfig.DEBUG == false`), an off-thread submit does NOT throw and the
     * reduce result is identical to the confined path — the guard is a pure no-op.
     */
    @Test
    fun `disabled guard is a no-op off-thread (release build)`() {
        val controller = controller(confinementEnabled = false)
        controller.submit(ConnectionEvent.Enter(host, target))

        val thrown = runOnOtherThread { controller.submit(ConnectionEvent.Background) }

        assertNull("release build must not assert on off-thread calls", thrown)
        assertTrue("state still transitioned normally", controller.state.value is ConnectionState.Backgrounded)
    }

    /**
     * The default guard (no explicit flag) follows `BuildConfig.DEBUG`. Under
     * `testDebugUnitTest` that is true, so a default-constructed controller trips
     * off-thread — proving production wiring is armed in debug, not just when a
     * test forces the flag on.
     */
    @Test
    fun `default guard follows BuildConfig DEBUG and is armed under debug tests`() {
        assertTrue("this suite must run under the debug variant", BuildConfig.DEBUG)
        val controller = ConnectionController(clock = FakeClock(), transport = FakeTransportPort())
        controller.submit(ConnectionEvent.Enter(host, target))

        val thrown = runOnOtherThread { controller.submit(ConnectionEvent.Background) }

        assertNotNull("default (debug) guard should be armed", thrown)
        assertTrue(thrown is IllegalStateException)
    }

    /** The guard is state-touch-free: enabling it does not alter reduce outputs. */
    @Test
    fun `guarded and unguarded confined runs produce identical state`() {
        val guarded = controller(confinementEnabled = true)
        val unguarded = controller(confinementEnabled = false)
        val events = listOf(
            ConnectionEvent.Enter(host, target),
            ConnectionEvent.Background,
            ConnectionEvent.Foreground,
            ConnectionEvent.TransportLive,
        )
        events.forEach { guarded.submit(it); unguarded.submit(it) }
        assertEquals(unguarded.state.value, guarded.state.value)
        assertFalse(guarded.state.value is ConnectionState.Idle)
    }

    /** Run [block] on a fresh worker thread; return the throwable it raised, or null. */
    private fun runOnOtherThread(block: () -> Unit): Throwable? {
        val captured = AtomicReference<Throwable?>(null)
        val t = Thread {
            try {
                block()
            } catch (e: Throwable) {
                captured.set(e)
            }
        }
        t.start()
        t.join()
        return captured.get()
    }
}
