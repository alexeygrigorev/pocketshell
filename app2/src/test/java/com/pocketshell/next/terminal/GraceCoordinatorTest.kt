package com.pocketshell.next.terminal

import android.app.Activity
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
import org.robolectric.Robolectric
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
            graceMs = { GRACE_MS },
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
            graceMs = { GRACE_MS },
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
            graceMs = { GRACE_MS },
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
            graceMs = { GRACE_MS },
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
            graceMs = { GRACE_MS },
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
            graceMs = { GRACE_MS },
            dispatcher = dispatcher,
        )

        coordinator.enterBackground()
        runCurrent()
        coordinator.enterBackground()
        runCurrent()

        assertEquals("enterBackground is idempotent", 1, service.startCount)
        assertEquals(1, connection.graceHandles.size)
    }

    /**
     * Regression for issue #2477.
     *
     * Hilt's Android test harness builds a FRESH `@Singleton` component — and
     * therefore a fresh [GraceCoordinator] — for every `@HiltAndroidTest`
     * method, on top of the SAME real, process-wide [android.app.Application]
     * underneath every one of them (`am instrument` never restarts the
     * process between test methods; a real production process never rebuilds
     * its Hilt component either, which is why nothing before #2477 had to
     * account for a SECOND instance existing at all).
     *
     * Before the fix, [GraceCoordinator.register] never unregistered a
     * previous instance, so the FIRST coordinator stayed a live
     * `ActivityLifecycleCallbacks` forever: it kept reacting to every LATER
     * activity's stop/start, capable of independently arming the shared,
     * OS-level grace notification for a connection nothing else could reach
     * or clean up — exactly what stranded
     * `J06BackgroundGraceReturnJourney.backgroundingWithNoOpenSessionShowsNoHoldAndNoNotification`
     * on a full, unfiltered suite run (a leftover `J05` coordinator, still
     * registered, rearmed for `J05`'s own never-closed connection while
     * `J06`'s own test had opened none).
     *
     * This drives ONE real activity's stop through Robolectric after BOTH
     * coordinators have registered, and asserts only the SECOND (the one
     * that "won" the registration) reacts — the first must see NEITHER its
     * own hold flip nor its own service start.
     */
    @Test
    fun `registering a second coordinator unregisters the first from the process`() = runTest {
        val application = ApplicationProvider.getApplicationContext<android.app.Application>()
        val dispatcher = StandardTestDispatcher(testScheduler)

        val (registryOne, _) = registryWithOneLiveConnection()
        val serviceOne = FakeGraceServiceControl()
        val coordinatorOne = GraceCoordinator(
            connections = registryOne,
            service = serviceOne,
            clock = { 0L },
            graceMs = { GRACE_MS },
            dispatcher = dispatcher,
        )
        coordinatorOne.register(application)

        val (registryTwo, _) = registryWithOneLiveConnection()
        val serviceTwo = FakeGraceServiceControl()
        val coordinatorTwo = GraceCoordinator(
            connections = registryTwo,
            service = serviceTwo,
            clock = { 0L },
            graceMs = { GRACE_MS },
            dispatcher = dispatcher,
        )
        // Simulates a later test method's fresh Hilt component handing a NEW
        // MainActivity a NEW GraceCoordinator, on the same real process.
        coordinatorTwo.register(application)

        // The ONE real activity lifecycle transition an actual process ever
        // delivers: started+resumed, then paused+stopped (a real background,
        // not a config change).
        val controller = Robolectric.buildActivity(Activity::class.java)
        controller.create().start().resume()
        runCurrent()
        controller.pause().stop()
        runCurrent()

        assertFalse(
            "the SUPERSEDED coordinator must never react again — it is no longer registered",
            coordinatorOne.isHolding,
        )
        assertEquals(
            "the superseded coordinator's own service must never be told to start",
            0,
            serviceOne.startCount,
        )
        assertTrue(
            "the CURRENT coordinator must be the one that reacts to the real activity",
            coordinatorTwo.isHolding,
        )
        assertEquals(1, serviceTwo.startCount)
    }

    /**
     * Regression for issue #2483 — the half of the #2477 story that fix did
     * not cover.
     *
     * #2477 stopped a superseded [GraceCoordinator] from REACTING to later
     * lifecycle callbacks, but left everything it had already armed running:
     * its `armed` flag, its pending [com.pocketshell.core.transport.GraceHandle]s
     * and — the one that bites — its expiry [kotlinx.coroutines.Job], which
     * still fires up to [GraceCoordinator.DEFAULT_GRACE_MS] later and calls
     * `stop()` on a service it no longer owns. There is only ONE [GraceService]
     * in a process, so that late stop takes down whichever hold the CURRENT
     * coordinator has open — leaving `isHolding == true` with no foreground
     * service, no notification and no wake lock behind it. In the `app2`
     * journey lane that is a 90-second landmine planted by every test class
     * whose teardown backgrounds a live connection, and it detonated inside
     * `J06BackgroundGraceReturnJourney` (CI run 33888824496).
     *
     * The single [FakeGraceServiceControl] shared by both coordinators is the
     * point: production has one service, and the two Hilt components a test
     * process builds both drive it.
     */
    @Test
    fun `a superseded coordinator's expiry can no longer take down the current hold`() = runTest {
        val application = ApplicationProvider.getApplicationContext<android.app.Application>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val service = FakeGraceServiceControl()

        // BOTH dials happen before anything is armed: `runTest` advances the
        // virtual clock freely whenever the test body suspends, so a dial made
        // after a window is open would silently skip past its expiry.
        val (registryOne, connectionOne) = registryWithOneLiveConnection()
        val (registryTwo, _) = registryWithOneLiveConnection()

        val coordinatorOne = GraceCoordinator(
            connections = registryOne,
            service = service,
            clock = { 0L },
            graceMs = { GRACE_MS },
            dispatcher = dispatcher,
        )
        coordinatorOne.register(application)

        // A previous test method's Activity is destroyed while its connection
        // is still live: a REAL background, arming a REAL window.
        val first = Robolectric.buildActivity(Activity::class.java)
        first.create().start().resume()
        runCurrent()
        first.pause().stop()
        runCurrent()
        assertTrue("the first coordinator must own a real window", coordinatorOne.isHolding)
        assertTrue(service.isRunning)

        // The next test method's fresh Hilt component builds a new coordinator
        // over the same process and the same service.
        val coordinatorTwo = GraceCoordinator(
            connections = registryTwo,
            service = service,
            clock = { 0L },
            graceMs = { GRACE_MS },
            dispatcher = dispatcher,
        )
        coordinatorTwo.register(application)
        runCurrent()

        // Deliberately NOT asserting the old window is already closed here —
        // that is the sibling test below. This one has to reach the moment the
        // zombie expiry actually fires, so it asserts nothing until then.

        // Two thirds into the superseded coordinator's original window, the
        // CURRENT coordinator opens one of its own.
        advanceTimeBy(GRACE_MS * 2 / 3)
        runCurrent()
        val second = Robolectric.buildActivity(Activity::class.java)
        second.create().start().resume()
        runCurrent()
        second.pause().stop()
        runCurrent()
        assertTrue("the current coordinator must own the new window", coordinatorTwo.isHolding)
        assertTrue(service.isRunning)

        // Past the superseded coordinator's deadline, still well inside the
        // current one's. This is the instant the zombie expiry used to fire.
        advanceTimeBy(GRACE_MS / 2)
        runCurrent()

        assertTrue(
            "the current coordinator's window must still be open",
            coordinatorTwo.isHolding,
        )
        assertTrue(
            "issue #2483: a superseded coordinator's expiry must never take down the " +
                "process-global grace service the CURRENT coordinator is holding — that " +
                "leaves isHolding==true with no notification and no wake lock behind it",
            service.isRunning,
        )
        assertTrue(
            "and the superseded coordinator's own armed close must have been cancelled " +
                "when it was retired, not left to close a connection minutes later",
            connectionOne.graceHandles.single().isCancelled,
        )
    }

    /**
     * Regression for issue #2483: being superseded must END the retired
     * instance's window then and there, on the thread that retired it — so the
     * hand-over is ordered with respect to whatever the NEW coordinator does
     * next, rather than leaving a stop pending on a timer.
     */
    @Test
    fun `superseding a coordinator ends its window immediately`() = runTest {
        val application = ApplicationProvider.getApplicationContext<android.app.Application>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val service = FakeGraceServiceControl()

        val (registryOne, connectionOne) = registryWithOneLiveConnection()
        val (registryTwo, _) = registryWithOneLiveConnection()

        val coordinatorOne = GraceCoordinator(
            connections = registryOne,
            service = service,
            clock = { 0L },
            graceMs = { GRACE_MS },
            dispatcher = dispatcher,
        )
        coordinatorOne.register(application)
        coordinatorOne.enterBackground()
        runCurrent()
        assertTrue(coordinatorOne.isHolding)
        assertTrue(service.isRunning)

        val coordinatorTwo = GraceCoordinator(
            connections = registryTwo,
            service = service,
            clock = { 0L },
            graceMs = { GRACE_MS },
            dispatcher = dispatcher,
        )
        coordinatorTwo.register(application)
        runCurrent()

        assertFalse(
            "a superseded coordinator must not still be holding a window",
            coordinatorOne.isHolding,
        )
        assertTrue(
            "a superseded coordinator's armed close must be cancelled, not left to fire",
            connectionOne.graceHandles.single().isCancelled,
        )
        assertEquals(
            "the hand-over must take the shared service down exactly once, synchronously",
            1,
            service.stopCount,
        )
        assertFalse(service.isRunning)
    }

    /**
     * Regression for issue #2483: being superseded also has to stop the
     * instance from ever driving the shared service again, not merely stop it
     * reacting to lifecycle callbacks — a direct call (the app2 journeys make
     * them, and so does anything holding a reference to the old singleton)
     * must not be able to start a foreground service for a graph that no
     * longer owns one.
     */
    @Test
    fun `a superseded coordinator can no longer arm a window at all`() = runTest {
        val application = ApplicationProvider.getApplicationContext<android.app.Application>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val service = FakeGraceServiceControl()

        val (registryOne, _) = registryWithOneLiveConnection()
        val (registryTwo, _) = registryWithOneLiveConnection()

        val coordinatorOne = GraceCoordinator(
            connections = registryOne,
            service = service,
            clock = { 0L },
            graceMs = { GRACE_MS },
            dispatcher = dispatcher,
        )
        coordinatorOne.register(application)

        val coordinatorTwo = GraceCoordinator(
            connections = registryTwo,
            service = service,
            clock = { 0L },
            graceMs = { GRACE_MS },
            dispatcher = dispatcher,
        )
        coordinatorTwo.register(application)
        runCurrent()

        coordinatorOne.enterBackground()
        runCurrent()

        assertFalse(
            "a retired coordinator must not arm a window",
            coordinatorOne.isHolding,
        )
        assertEquals(
            "a retired coordinator must never start the shared service",
            0,
            service.startCount,
        )
        assertFalse(service.isRunning)
    }

    private companion object {
        const val GRACE_MS = GraceCoordinator.DEFAULT_GRACE_MS
    }
}
