package com.pocketshell.core.ssh

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * Issue #2112 — the lease-pool half of the "90 s background grace vs 60 s lease idle TTL"
 * question.
 *
 * The audit's hypothesis was that a background return at t = 60-90 s finds a lease that
 * has passed its idle TTL and gone cold, so the controller's within-grace contract
 * promises a zero-reconnect reattach the transport cannot honour. The step-1 finding
 * (recorded on #2112) is that the hypothesis does not hold, and this class pins the two
 * mechanisms that are the REASON it does not hold — the properties a future change would
 * have to break in order to open that 30-second dead zone:
 *
 *  1. **The idle-TTL clock only runs on a ZERO-REF entry.** Throughout the App-level
 *     background grace window the `-CC` control client still holds its lease (the VM
 *     acquires at connect and releases only in the teardown), so the 60 s TTL is not
 *     running at all — the transport is held, not parked.
 *  2. **The release that finally happens, at grace-elapsed, closes IMMEDIATELY.** The
 *     teardown runs alongside `onProcessStopped()`, and `release()` with the process
 *     stopped removes and closes the entry rather than parking it warm for another TTL.
 *     So the pool can never hold a warm-looking entry across a process-stop boundary.
 *
 * Together these are what make `DEFAULT_GRACE_MS` (90 s) > `DEFAULT_IDLE_TTL_MILLIS`
 * (60 s) SAFE rather than a dead zone: the two constants never measure the same interval.
 *
 * Mutations these are written to redden: making the idle reaper ignore `refCount`
 * (test 1); deleting the `!processStarted` disjunct from `release()` so a teardown-time
 * release parks warm on the 60 s TTL instead of closing (tests 3/4) — that one mutation
 * is precisely the change that would CREATE the reported dead zone. Test 2 is the
 * complementary POSITIVE: the TTL must still work for an ordinary foreground release, so
 * a fix for 3/4 cannot be "never park anything warm".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Issue2112BackgroundGraceLeaseHoldTest {

    /**
     * The controller's background grace window (`ConnectionController.DEFAULT_GRACE_MS`).
     * Duplicated as a literal because `:shared:core-ssh` does not depend on
     * `:shared:core-connection`; the cross-module equality of the two numbers is asserted
     * in `:app` by `Issue2112GraceLeaseTtlRelationTest`, which sees both.
     */
    private val controllerGraceMs = 90_000L

    @Test
    fun `a held lease never starts the idle TTL clock so it outlives the whole grace window`() {
        // Mechanism 1. The session is attached for the whole background hold, so its lease
        // refCount stays >= 1 and the idle reaper never gets an entry to reap. Advance well
        // past BOTH the 60s TTL and the 90s grace window: the transport is still live and
        // no second dial happened.
        runTest {
            val session = FakeSshSession()
            val connector = QueueLeaseConnector(session)
            val manager = leaseManager(connector)

            val held = manager.acquire(TARGET).getOrThrow()

            advanceTimeBy(SshLeaseManager.DEFAULT_IDLE_TTL_MILLIS + 1L)
            runCurrent()
            assertFalse(
                "a held lease must not be reaped when the idle TTL elapses",
                session.closed,
            )
            assertTrue(
                "the pool must still report the held lease as live at the TTL boundary",
                manager.hasLiveLease(TARGET.leaseKey),
            )

            advanceTimeBy(controllerGraceMs - SshLeaseManager.DEFAULT_IDLE_TTL_MILLIS)
            runCurrent()
            assertFalse(
                "a held lease must survive the whole 90s background grace window",
                session.closed,
            )
            assertTrue(
                "the warm snapshot the controller's grace predicate reads must still be warm",
                manager.hasLiveLease(TARGET.leaseKey),
            )
            assertEquals("the held lease must not have been re-dialled", 1, connector.connectCount)

            held.release()
            runCurrent()
        }
    }

    @Test
    fun `a partial release during the background hold does not start the idle clock`() {
        // Mechanism 1's live mutant: only the LAST release may start the idle clock. If a
        // partial release ever parked the entry (deleting the `refCount > 0` early return
        // in `release()`), the still-attached `-CC` client's transport would be reaped 60 s
        // into a 90 s hold — the dead zone, arriving from the refcount side instead of the
        // background side.
        runTest {
            val session = FakeSshSession()
            val connector = QueueLeaseConnector(session)
            val manager = leaseManager(connector)

            val first = manager.acquire(TARGET).getOrThrow()
            val second = manager.acquire(TARGET).getOrThrow()
            assertEquals("the two holders must share one dial", 1, connector.connectCount)

            second.release()
            runCurrent()

            advanceTimeBy(controllerGraceMs)
            runCurrent()
            assertFalse(
                "a remaining holder must keep the transport alive across the whole window",
                session.closed,
            )
            assertTrue(manager.hasLiveLease(TARGET.leaseKey))

            first.release()
            runCurrent()
        }
    }

    @Test
    fun `a foreground release parks the transport warm for exactly the idle TTL`() {
        // Mechanism 2, positive half. With the process STARTED (the app in the
        // foreground), the last release parks the transport warm so a host switch-back
        // reuses it. This is the behaviour the 60s TTL actually governs, and it must keep
        // working — the tests below must not be satisfiable by "close everything at once".
        runTest {
            val session = FakeSshSession()
            val connector = QueueLeaseConnector(session)
            val manager = leaseManager(connector)
            val events = collectStateEvents(manager)

            manager.acquire(TARGET).getOrThrow().release()
            runCurrent()
            assertFalse("the release must park the transport warm, not close it", session.closed)
            assertEquals(SshLeaseConnectionState.Idle, events.last().state)

            advanceTimeBy(SshLeaseManager.DEFAULT_IDLE_TTL_MILLIS - 1L)
            runCurrent()
            assertFalse("the warm transport must survive until the idle TTL", session.closed)

            advanceTimeBy(1L)
            runCurrent()
            assertTrue("the warm transport must close at the idle TTL", session.closed)
            assertEquals(SshLeaseConnectionState.Closed, events.last().state)
            assertEquals(SshLeaseCloseReason.IdleExpired, events.last().closeReason)
        }
    }

    @Test
    fun `a release after the process stopped closes immediately instead of parking warm`() {
        // Mechanism 2, load-bearing half. The grace-elapsed teardown releases the lease
        // with the process stopped. If that release parked the transport warm on the 60s
        // TTL, the pool would report a warm lease for another minute over a transport the
        // app has already torn down — the exact shape of the dead zone #2112 asks about.
        // It must close AT ONCE, at the same virtual instant.
        runTest {
            val session = FakeSshSession()
            val connector = QueueLeaseConnector(session)
            val manager = leaseManager(connector)
            val events = collectStateEvents(manager)

            val held = manager.acquire(TARGET).getOrThrow()
            manager.onProcessStopped()
            val releasedAtMs = testScheduler.currentTime

            held.release()
            runCurrent()

            assertTrue(
                "a release with the process stopped must close the transport immediately",
                session.closed,
            )
            assertEquals(
                "the close must land at the same instant as the release, not after the idle TTL",
                releasedAtMs,
                testScheduler.currentTime,
            )
            assertFalse(
                "the pool must not report a warm lease after the teardown release",
                manager.hasLiveLease(TARGET.leaseKey),
            )
            assertEquals(SshLeaseConnectionState.Closed, events.last().state)
            assertEquals(
                "the close must be attributed to the process stop, not an idle expiry",
                SshLeaseCloseReason.ProcessStopped,
                events.last().closeReason,
            )
        }
    }

    @Test
    fun `onProcessStopped closes an already-parked warm lease immediately`() {
        // The ordering sibling of the test above: the teardown's release can land BEFORE
        // the process-stop dispatch, leaving a briefly-parked warm entry. The process stop
        // must then collapse it at once rather than leaving it to serve out the rest of a
        // 60s TTL while the app is backgrounded.
        runTest {
            val session = FakeSshSession()
            val connector = QueueLeaseConnector(session)
            val manager = leaseManager(connector)
            val events = collectStateEvents(manager)

            manager.acquire(TARGET).getOrThrow().release()
            runCurrent()
            assertFalse("precondition: the release parked the transport warm", session.closed)

            val stoppedAtMs = testScheduler.currentTime
            manager.onProcessStopped()
            runCurrent()

            assertTrue("the process stop must close the parked warm transport", session.closed)
            assertEquals(
                "the close must land at the process stop, not at the remaining idle TTL",
                stoppedAtMs,
                testScheduler.currentTime,
            )
            assertFalse(manager.hasLiveLease(TARGET.leaseKey))
            assertEquals(SshLeaseCloseReason.ProcessStopped, events.last().closeReason)
        }
    }

    // ---- harness ----

    /**
     * Every owned background hop is pinned to the test scheduler (the dial bound, its
     * abort, the idle-reaper `scope`, and the manager's own clock), so the virtual clock
     * fully drives the pool — no real dispatcher, no wall-clock race.
     */
    private fun TestScope.leaseManager(connector: SshLeaseConnector): SshLeaseManager =
        SshLeaseManager(
            connector = connector,
            scope = this,
            // The REAL production default: this test is about that exact number.
            idleTtlMillis = SshLeaseManager.DEFAULT_IDLE_TTL_MILLIS,
            maxIdleLeases = SshLeaseManager.DEFAULT_MAX_IDLE_LEASES,
            connectTimeoutContext = StandardTestDispatcher(testScheduler),
            abortTimeoutContext = StandardTestDispatcher(testScheduler),
            nowMillis = { testScheduler.currentTime },
        )

    private fun TestScope.collectStateEvents(
        manager: SshLeaseManager,
    ): List<SshLeaseStateEvent> {
        val events = mutableListOf<SshLeaseStateEvent>()
        backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
            manager.stateEvents.collect { events += it }
        }
        runCurrent()
        return events
    }

    private class QueueLeaseConnector(
        private vararg val sessions: FakeSshSession,
    ) : SshLeaseConnector {
        var connectCount: Int = 0

        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
            val next = sessions.getOrNull(connectCount)
                ?: error("unexpected connect $connectCount for ${target.leaseKey}")
            connectCount += 1
            return Result.success(next)
        }
    }

    private class FakeSshSession : SshSession {
        var closed: Boolean = false
        var connected: Boolean = true

        override val isConnected: Boolean
            get() = connected && !closed

        override suspend fun exec(command: String): ExecResult =
            ExecResult(stdout = "", stderr = "", exitCode = 0)

        override fun tail(path: String, onLine: (String) -> Unit): Job = error("not used")

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = error("not used")

        override fun startShell(): SshShell = object : SshShell {
            override val stdin = ByteArrayOutputStream()
            override val stdout = ByteArrayInputStream(ByteArray(0))
            override val stderr = ByteArrayInputStream(ByteArray(0))
            override fun close() = Unit
        }

        override suspend fun uploadFile(file: File, remotePath: String): String = error("not used")

        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("not used")

        override fun close() {
            closed = true
        }
    }

    private companion object {
        val TARGET: SshLeaseTarget = SshLeaseTarget(
            leaseKey = SshLeaseKey(
                host = "10.0.2.2",
                port = 2222,
                user = "testuser",
                credentialId = "/tmp/key-a",
            ),
            key = SshKey.Path(File("/tmp/key-a")),
        )
    }
}
