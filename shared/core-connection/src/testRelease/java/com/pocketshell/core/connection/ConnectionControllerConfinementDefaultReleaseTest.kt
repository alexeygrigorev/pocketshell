package com.pocketshell.core.connection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * RELEASE-variant half of the default-constructor confinement contract (item 6,
 * #1234). Lives in `src/testRelease/` so it compiles ONLY into
 * `testReleaseUnitTest`, where `BuildConfig.DEBUG == false`.
 *
 * The default constructor leaves `confinementAssertionsEnabled` defaulting to
 * `BuildConfig.DEBUG`, so under the release variant a default-constructed
 * controller is a pure NO-OP: even a genuine concurrent mutation does NOT throw,
 * and the guard adds zero overhead to shipped builds. This is the release
 * counterpart to `src/testDebug/.../ConnectionControllerConfinementDefaultDebugTest.kt`
 * (armed under debug); together the two halves keep BOTH unit-test variant tasks
 * meaningfully green with real coverage — no `assumeTrue`/skip on the load-bearing
 * assertion.
 */
class ConnectionControllerConfinementDefaultReleaseTest {

    private val host = HostKey("h1")
    private val target = SessionId("s1")

    /**
     * Under the release variant the default guard is disabled, so a concurrent
     * mutation on a DEFAULT-constructed controller (no explicit flag) does NOT
     * throw — the blocking mutation completes and the racing call reduces
     * normally. This proves the `BuildConfig.DEBUG` default is a no-op in shipped
     * builds, not merely when a test forces the flag off.
     */
    @Test
    fun `default guard follows BuildConfig DEBUG and is a no-op under release tests`() {
        assertFalse("this suite must run under the release variant", BuildConfig.DEBUG)
        val insideMutation = CountDownLatch(1)
        val releaseMutation = CountDownLatch(1)
        val blockingClock = object : Clock {
            @Volatile var blockOnce = false
            override fun nowMs(): Long {
                if (blockOnce) {
                    blockOnce = false
                    insideMutation.countDown()
                    releaseMutation.await(5, TimeUnit.SECONDS)
                }
                return 0L
            }
        }
        // Default constructor: confinementAssertionsEnabled defaults to BuildConfig.DEBUG.
        val controller = ConnectionController(clock = blockingClock, transport = FakeTransportPort())
        controller.submit(ConnectionEvent.Enter(host, target))
        blockingClock.blockOnce = true

        val threadA = Thread { controller.submit(ConnectionEvent.Background) }
        threadA.start()
        assertTrue(insideMutation.await(5, TimeUnit.SECONDS))

        val thrown = runCatching { controller.submit(ConnectionEvent.Foreground) }.exceptionOrNull()

        releaseMutation.countDown()
        threadA.join(5_000)

        assertNull("release-default guard must not assert on a concurrent call", thrown)
        // State still advanced across the (unguarded) concurrent hand-off.
        assertFalse("controller advanced past Idle", controller.state.value is ConnectionState.Idle)
    }
}
