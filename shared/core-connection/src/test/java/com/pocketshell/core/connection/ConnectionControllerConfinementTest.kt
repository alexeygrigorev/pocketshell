package com.pocketshell.core.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Item 6 (#1234): the [ConnectionController] single-confining-dispatcher
 * assertion. The controller's `submit`/`offerSeed` do unguarded read-modify-writes
 * of `_state.value` + the plain `graceDeadlineMs` var, safe ONLY because a single
 * confining dispatcher SERIALIZES the mutators so none ever overlap. These tests
 * prove the DEBUG-gated guard converts a genuine CONCURRENT (off-confinement)
 * mutation into a loud failure, while leaving the confined path — including the
 * benign pool-thread migration a real dispatcher performs — behavior-identical.
 *
 * ## Round-2 modeling fix (CI regression)
 * Round 1 keyed the guard off the first raw `Thread.id` and hard-failed any later
 * call from a different thread. That tripped 5 `TmuxSessionViewModelTest` cases:
 * under an `UnconfinedTestDispatcher` a confined `collect { submit(...) }` resumes
 * inline on the foreign thread that emitted upstream, so a benign, still-serialized
 * hand-off looks like a "different thread". A confining dispatcher is a
 * *serialization* contract, not a fixed-thread one — so the guard now asserts the
 * invariant that actually matters (no two mutators overlap), which passes benign
 * migration and still catches a real racing mutation. See
 * [sequential cross-thread hand-off does not trip the guard] (the regression) and
 * [concurrent submit while a mutation is in progress trips the guard] (still armed).
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

    /** Repeated same-thread mutator calls never trip — the happy path. */
    @Test
    fun `submit on the confining thread does not trip the guard`() {
        val controller = controller()
        val s1 = controller.submit(ConnectionEvent.Enter(host, target))
        val s2 = controller.submit(ConnectionEvent.Background)
        assertTrue("Enter -> Attaching/Connecting", s1 is ConnectionState.Attaching || s1 is ConnectionState.Connecting)
        assertTrue("Background -> Backgrounded", s2 is ConnectionState.Backgrounded)
    }

    /**
     * REGRESSION (the CI break this round fixes): a real dispatcher serializes its
     * work but may run each task on a DIFFERENT pool thread. Driving the mutators
     * SEQUENTIALLY from three distinct threads (each completing before the next
     * starts — exactly what a serializing dispatcher, incl. an
     * `UnconfinedTestDispatcher` resuming inline on the emitter thread, does) must
     * NOT trip the guard. Round 1's raw-`Thread.id` latch failed here.
     */
    @Test
    fun `sequential cross-thread hand-off does not trip the guard`() {
        val controller = controller()
        // Three distinct threads, each joined before the next — no overlap.
        val t1 = runOnOtherThread { controller.submit(ConnectionEvent.Enter(host, target)) }
        val t2 = runOnOtherThread { controller.submit(ConnectionEvent.Background) }
        val t3 = runOnOtherThread { controller.submit(ConnectionEvent.Foreground) }
        assertNull("Enter on thread 1 must not trip", t1)
        assertNull("Background on thread 2 (different id) must not trip", t2)
        assertNull("Foreground on thread 3 (different id) must not trip", t3)
        // State still progressed normally across the hand-off.
        assertFalse("controller advanced past Idle", controller.state.value is ConnectionState.Idle)
    }

    /**
     * STILL ARMED: a genuinely off-confinement mutation — a second thread entering
     * a mutator while the first is still INSIDE one — is the real data race, and it
     * hard-fails in a DEBUG build. We hold thread A inside `submit(Background)` (its
     * reduce calls `clock.nowMs()`) via a latch, then race a second `submit` on this
     * thread; the guard must throw an [IllegalStateException] naming the concurrency.
     */
    @Test
    fun `concurrent submit while a mutation is in progress trips the guard`() {
        val insideMutation = CountDownLatch(1)
        val releaseMutation = CountDownLatch(1)
        // A clock whose nowMs() (called inside submit(Background)'s reduce) parks
        // thread A inside the guarded mutation until the racing call has happened.
        val blockingClock = object : Clock {
            @Volatile var blockOnce = true
            override fun nowMs(): Long {
                if (blockOnce) {
                    blockOnce = false
                    insideMutation.countDown()
                    releaseMutation.await(5, TimeUnit.SECONDS)
                }
                return 0L
            }
        }
        val controller = ConnectionController(
            clock = blockingClock,
            transport = FakeTransportPort(),
            confinementAssertionsEnabled = true,
        )
        // Establish a target so Background reduces down the grace path (calls nowMs).
        controller.submit(ConnectionEvent.Enter(host, target))

        val threadA = Thread { controller.submit(ConnectionEvent.Background) }
        threadA.start()
        assertTrue("thread A entered the mutation", insideMutation.await(5, TimeUnit.SECONDS))

        // Thread A now holds the latch INSIDE submit(Background); race a second call.
        val thrown = runCatching { controller.submit(ConnectionEvent.Foreground) }.exceptionOrNull()

        releaseMutation.countDown()
        threadA.join(5_000)

        assertNotNull("expected the guard to trip on the concurrent mutation", thrown)
        assertTrue(
            "expected IllegalStateException, got ${thrown!!::class.java}",
            thrown is IllegalStateException,
        )
        assertTrue(
            "message should name submit + concurrency + issue: ${thrown.message}",
            thrown.message!!.contains("submit") &&
                thrown.message!!.contains("concurrently") &&
                thrown.message!!.contains("#1234"),
        )
    }

    /**
     * The guard covers [ConnectionController.offerSeed] too: a concurrent offerSeed
     * entering while a submit mutation is in progress trips. Same latch shape as the
     * submit race, but the racing call is offerSeed.
     */
    @Test
    fun `concurrent offerSeed while a mutation is in progress trips the guard`() {
        val insideMutation = CountDownLatch(1)
        val releaseMutation = CountDownLatch(1)
        val blockingClock = object : Clock {
            @Volatile var blockOnce = true
            override fun nowMs(): Long {
                if (blockOnce) {
                    blockOnce = false
                    insideMutation.countDown()
                    releaseMutation.await(5, TimeUnit.SECONDS)
                }
                return 0L
            }
        }
        val controller = ConnectionController(
            clock = blockingClock,
            transport = FakeTransportPort(),
            confinementAssertionsEnabled = true,
        )
        controller.submit(ConnectionEvent.Enter(host, target))

        val threadA = Thread { controller.submit(ConnectionEvent.Background) }
        threadA.start()
        assertTrue("thread A entered the mutation", insideMutation.await(5, TimeUnit.SECONDS))

        val thrown = runCatching { controller.offerSeed(Seed(target, "%0", "frame")) }.exceptionOrNull()

        releaseMutation.countDown()
        threadA.join(5_000)

        assertNotNull("expected the guard to trip on the concurrent offerSeed", thrown)
        assertTrue(thrown is IllegalStateException)
        assertTrue(
            "message should name offerSeed: ${thrown!!.message}",
            thrown.message!!.contains("offerSeed"),
        )
    }

    /**
     * Debug-gating: with the assertion DISABLED (release-build simulation,
     * `BuildConfig.DEBUG == false`), even a genuine concurrent mutation does NOT
     * throw — the guard is a pure no-op. The blocking mutation still completes and
     * the racing call reduces normally.
     */
    @Test
    fun `disabled guard is a no-op under concurrency (release build)`() {
        val insideMutation = CountDownLatch(1)
        val releaseMutation = CountDownLatch(1)
        val blockingClock = object : Clock {
            @Volatile var blockOnce = true
            override fun nowMs(): Long {
                if (blockOnce) {
                    blockOnce = false
                    insideMutation.countDown()
                    releaseMutation.await(5, TimeUnit.SECONDS)
                }
                return 0L
            }
        }
        val controller = ConnectionController(
            clock = blockingClock,
            transport = FakeTransportPort(),
            confinementAssertionsEnabled = false,
        )
        controller.submit(ConnectionEvent.Enter(host, target))

        val threadA = Thread { controller.submit(ConnectionEvent.Background) }
        threadA.start()
        assertTrue(insideMutation.await(5, TimeUnit.SECONDS))

        val thrown = runCatching { controller.submit(ConnectionEvent.Foreground) }.exceptionOrNull()

        releaseMutation.countDown()
        threadA.join(5_000)

        assertNull("release build must not assert on a concurrent call", thrown)
    }

    // NOTE: the default-constructor path (confinementAssertionsEnabled defaults to
    // BuildConfig.DEBUG) is variant-DEPENDENT, so it cannot live in this common
    // `src/test/` suite (which compiles into BOTH testDebugUnitTest and
    // testReleaseUnitTest — a single `assertTrue(BuildConfig.DEBUG)` would hard-fail
    // under the release task, the CI break this round fixes). The two halves of that
    // contract are asserted in the variant-specific source sets so BOTH variants stay
    // meaningfully green with real coverage:
    //   - src/testDebug/.../ConnectionControllerConfinementDefaultDebugTest.kt
    //       proves the default guard is ARMED under debug (BuildConfig.DEBUG == true)
    //       and trips on a concurrent mutation — production wiring is armed in debug.
    //   - src/testRelease/.../ConnectionControllerConfinementDefaultReleaseTest.kt
    //       proves the default guard is a NO-OP under release (BuildConfig.DEBUG ==
    //       false) — zero overhead in shipped builds.

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

    // --- Direct guard-level unit checks (precise, no reducer plumbing) ---

    /** A reentrant same-thread guarded call (nested) must not trip. */
    @Test
    fun `reentrant same-thread guarded call is allowed`() {
        val guard = ThreadConfinementGuard(enabled = true)
        val result = guard.guarded("outer") {
            guard.guarded("inner") { "ok" }
        }
        assertEquals("ok", result)
        // And the latch was released: a fresh single call still works afterwards.
        assertEquals("again", guard.guarded("after") { "again" })
    }

    /** Two threads inside the guard at once trip; the guard releases after. */
    @Test
    fun `guard trips on true concurrent entry and recovers`() {
        val guard = ThreadConfinementGuard(enabled = true)
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = Thread {
            guard.guarded("holder") {
                inside.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        holder.start()
        assertTrue(inside.await(5, TimeUnit.SECONDS))

        val thrown = runCatching { guard.guarded("racer") { } }.exceptionOrNull()

        release.countDown()
        holder.join(5_000)

        assertTrue("racer must trip while holder is inside", thrown is IllegalStateException)
        // After the holder exits, the latch is free again — no permanent lock-out.
        assertEquals("free", guard.guarded("after") { "free" })
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
