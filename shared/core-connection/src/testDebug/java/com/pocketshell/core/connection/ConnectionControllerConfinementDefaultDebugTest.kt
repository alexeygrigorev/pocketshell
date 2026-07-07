package com.pocketshell.core.connection

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * DEBUG-variant half of the default-constructor confinement contract (item 6,
 * #1234). Lives in `src/testDebug/` so it compiles ONLY into
 * `testDebugUnitTest`, where `BuildConfig.DEBUG == true`.
 *
 * The default constructor leaves `confinementAssertionsEnabled` defaulting to
 * `BuildConfig.DEBUG`, so under the debug variant a default-constructed
 * controller is ARMED and trips on a genuine concurrent mutation — proving the
 * production wiring guards in debug builds, not only when a test forces the flag
 * on. The release-variant no-op counterpart is
 * `src/testRelease/.../ConnectionControllerConfinementDefaultReleaseTest.kt`.
 *
 * The variant split is why this is not in the common `src/test/` suite: a single
 * `assertTrue(BuildConfig.DEBUG)` there hard-fails under `testReleaseUnitTest`
 * (the CI regression this round fixes), and asserting "armed" against a
 * release-default (disabled) controller would be wrong. Keeping each half in its
 * own variant source set leaves BOTH unit-test tasks green with real coverage
 * and no `assumeTrue`/skip smell.
 */
class ConnectionControllerConfinementDefaultDebugTest {

    private val host = HostKey("h1")
    private val target = SessionId("s1")

    /**
     * Under the debug variant the default guard is armed: a genuine concurrent
     * mutation (a second thread entering a mutator while the first is still
     * inside one) hard-fails with an [IllegalStateException]. Same latch shape as
     * the common suite's concurrency tests, but exercising the DEFAULT constructor
     * (no explicit flag) so it proves the `BuildConfig.DEBUG` default is armed.
     */
    @Test
    fun `default guard follows BuildConfig DEBUG and is armed under debug tests`() {
        assertTrue("this suite must run under the debug variant", BuildConfig.DEBUG)
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
        // Default constructor: confinementAssertionsEnabled defaults to BuildConfig.DEBUG.
        val controller = ConnectionController(clock = blockingClock, transport = FakeTransportPort())
        controller.submit(ConnectionEvent.Enter(host, target))

        val threadA = Thread { controller.submit(ConnectionEvent.Background) }
        threadA.start()
        assertTrue(insideMutation.await(5, TimeUnit.SECONDS))

        val thrown = runCatching { controller.submit(ConnectionEvent.Foreground) }.exceptionOrNull()

        releaseMutation.countDown()
        threadA.join(5_000)

        assertNotNull("default (debug) guard should be armed", thrown)
        assertTrue(thrown is IllegalStateException)
    }
}
