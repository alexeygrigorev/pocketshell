package com.pocketshell.next.terminal

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.core.transport.ConnectResult
import com.pocketshell.core.transport.FakeHostConnection
import com.pocketshell.next.connect.ConnectionsRegistry
import com.pocketshell.next.connect.FakeHostConnectionFactory
import com.pocketshell.next.connect.RoomTrustStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Drives [GraceCoordinator] — the WHOLE of D21 — over the scripted
 * `core-transport` fake and a [FakeGraceServiceControl], on a virtual clock.
 *
 * No real timer, wake lock, notification or `Context` is involved: [enterBackground]
 * and [GraceCoordinator.enterForeground] ARE the `onStop`/`onStart` policy
 * (§C.4), so calling them directly is exactly what `ProcessLifecycleOwner`
 * dispatching those callbacks would do. The virtual clock comes from
 * `runTest`'s [TestScope] — [advanceTimeBy] drives [GraceCoordinator]'s own
 * expiry job exactly like a real 90 s wait would, in milliseconds of wall time.
 *
 * Room is real (same in-memory setup [com.pocketshell.next.connect.ConnectionsRegistryTest]
 * uses) because [ConnectionsRegistry] needs a host row to dial against; the
 * dial itself is the scripted [FakeHostConnectionFactory], never sshj.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class GraceCoordinatorTest {

    private lateinit var db: AppDatabase
    private var hostId: Long = 0
    private var keyId: Long = 0

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()

        keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "fixture", privateKeyPath = "/tmp/fixture_ed25519"),
        )
        hostId = db.hostDao().insert(
            HostEntity(name = "dev box", hostname = "dev.invalid", port = 2222, username = "tester", keyId = keyId),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun TestScope.registryWithOneLiveConnection(): Pair<ConnectionsRegistry, FakeHostConnection> {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val factory = FakeHostConnectionFactory()
        val registry = ConnectionsRegistry(
            factory = factory,
            trustStore = RoomTrustStore(db.hostDao(), dispatcher),
            hostDao = db.hostDao(),
            dispatcher = dispatcher,
        )
        val connected = registry.getOrConnect(hostId)
        check(connected is ConnectResult.Connected) { "fixture dial must succeed, got $connected" }
        return registry to factory.connections.single()
    }

    @Test
    fun `backgrounding with a live connection arms one grace close and starts the service`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val (registry, connection) = registryWithOneLiveConnection()
        val service = FakeGraceServiceControl()
        val nowMs = 1_000_000L
        val coordinator = GraceCoordinator(
            connections = registry,
            service = service,
            clock = { nowMs },
            graceMs = GRACE_MS,
            dispatcher = dispatcher,
        )

        coordinator.enterBackground()
        runCurrent()

        assertTrue("a background window must be open", coordinator.isHolding)
        assertEquals("exactly one grace close must be armed", 1, connection.graceHandles.size)
        assertTrue("the armed close must still be live", connection.graceHandles.single().isLive)
        // The connection's own grace deadline is computed by core-transport off
        // ITS clock, not GraceCoordinator's — only the deadline this coordinator
        // hands the SERVICE is derived from the injected clock (asserted below).
        assertEquals("the service must start exactly once", 1, service.startCount)
        assertEquals(
            "the service must be told the same deadline",
            nowMs + GRACE_MS,
            service.startedDeadlines.single(),
        )
        assertTrue(service.isRunning)
    }

    @Test
    fun `backgrounding with no live connection starts nothing`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val factory = FakeHostConnectionFactory()
        val registry = ConnectionsRegistry(
            factory = factory,
            trustStore = RoomTrustStore(db.hostDao(), dispatcher),
            hostDao = db.hostDao(),
            dispatcher = dispatcher,
        )
        val service = FakeGraceServiceControl()
        val coordinator = GraceCoordinator(
            connections = registry,
            service = service,
            clock = { 0L },
            graceMs = GRACE_MS,
            dispatcher = dispatcher,
        )

        coordinator.enterBackground()
        runCurrent()

        assertFalse(
            "a user who never opened a host must not get a background hold",
            coordinator.isHolding,
        )
        assertEquals(0, service.startCount)
    }

    @Test
    fun `returning inside the grace window cancels every pending close and stops the service`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val (registry, connection) = registryWithOneLiveConnection()
        val service = FakeGraceServiceControl()
        val coordinator = GraceCoordinator(
            connections = registry,
            service = service,
            clock = { 0L },
            graceMs = GRACE_MS,
            dispatcher = dispatcher,
        )

        coordinator.enterBackground()
        runCurrent()
        assertTrue(coordinator.isHolding)

        // Well inside the window.
        advanceTimeBy(GRACE_MS / 3)
        runCurrent()

        coordinator.enterForeground()
        runCurrent()

        assertFalse("the window must be closed", coordinator.isHolding)
        assertTrue(
            "the D21/#1123 contract: the armed close must be CANCELLED, not merely stale",
            connection.graceHandles.single().isCancelled,
        )
        assertEquals("the service must be stopped exactly once", 1, service.stopCount)
        assertFalse(service.isRunning)

        // The rest of the original 90 s must fire NOTHING — no second service
        // start, no second grace arm.
        advanceTimeBy(GRACE_MS)
        runCurrent()

        assertEquals(1, service.startCount)
        assertEquals(1, service.stopCount)
        assertEquals(1, connection.graceHandles.size)
    }

    @Test
    fun `foregrounding with no open window is a no-op`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val (registry, _) = registryWithOneLiveConnection()
        val service = FakeGraceServiceControl()
        val coordinator = GraceCoordinator(
            connections = registry,
            service = service,
            clock = { 0L },
            graceMs = GRACE_MS,
            dispatcher = dispatcher,
        )

        coordinator.enterForeground()
        runCurrent()

        assertFalse(coordinator.isHolding)
        assertEquals(0, service.stopCount)
        assertEquals(0, service.startCount)
    }

    @Test
    fun `the clock driven past the grace window stops the service and the armed close can still fire`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val (registry, connection) = registryWithOneLiveConnection()
        val service = FakeGraceServiceControl()
        val coordinator = GraceCoordinator(
            connections = registry,
            service = service,
            clock = { 0L },
            graceMs = GRACE_MS,
            dispatcher = dispatcher,
        )

        coordinator.enterBackground()
        runCurrent()
        assertTrue(coordinator.isHolding)
        assertEquals(1, service.startCount)
        assertEquals(0, service.stopCount)

        // Not yet: right up to (but not past) the deadline nothing has expired.
        advanceTimeBy(GRACE_MS - 1)
        runCurrent()
        assertTrue("still inside the window", coordinator.isHolding)
        assertEquals(0, service.stopCount)

        // Past it: this coordinator's own expiry job must take the service
        // down. No wake lock/service may still be "alive" per the acceptance
        // bar, which for this fake means stopCount catches up with startCount.
        advanceTimeBy(2)
        runCurrent()

        assertFalse("the window must have expired", coordinator.isHolding)
        assertEquals("the service must be stopped exactly once", 1, service.stopCount)
        assertFalse(service.isRunning)

        // Task T-5 owns the ACTUAL close timer on the transport; this fake
        // never self-fires it, so the test fires it explicitly to prove the
        // close this class exists to protect really does land at the deadline
        // GraceCoordinator armed.
        connection.fireGraceClose()
        assertTrue("close() must have run on the connection", connection.isClosed)
    }

    @Test
    fun `a second backgrounding call while already armed does not double-arm`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val (registry, connection) = registryWithOneLiveConnection()
        val service = FakeGraceServiceControl()
        val coordinator = GraceCoordinator(
            connections = registry,
            service = service,
            clock = { 0L },
            graceMs = GRACE_MS,
            dispatcher = dispatcher,
        )

        coordinator.enterBackground()
        runCurrent()
        coordinator.enterBackground()
        runCurrent()

        assertEquals("enterBackground is idempotent", 1, service.startCount)
        assertEquals(1, connection.graceHandles.size)
    }

    private companion object {
        const val GRACE_MS = GraceCoordinator.DEFAULT_GRACE_MS
    }
}
