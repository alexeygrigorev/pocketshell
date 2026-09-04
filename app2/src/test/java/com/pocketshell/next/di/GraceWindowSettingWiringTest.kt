package com.pocketshell.next.di

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
import com.pocketshell.next.settings.AppSettings
import com.pocketshell.next.settings.SettingsRepository
import com.pocketshell.next.terminal.FakeGraceServiceControl
import com.pocketshell.next.terminal.GraceCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression for issue #2488 — the background-grace SETTING has to reach the
 * thing that implements it.
 *
 * ## What was broken
 *
 * [AppSettings.backgroundGraceMillis] is persisted by [SettingsRepository] and
 * rendered as a user-facing Settings row (task P-6), but
 * [AppModule.provideGraceCoordinator] built the coordinator as
 * `GraceCoordinator(connections, service)` — with no window argument at all. So
 * every background window was [GraceCoordinator.DEFAULT_GRACE_MS] no matter
 * which option the user picked: a control that changes nothing, which is worse
 * than no control, because the user believes their session is being held for
 * ten minutes when it is dropped after ninety seconds.
 *
 * ## Why the assertions are on the PROVIDER, not on a hand-built coordinator
 *
 * The bug was never in [GraceCoordinator] — it always honoured whatever window
 * it was handed, and `GraceCoordinatorTest` proved that with a literal. The
 * defect lived entirely in the wiring, so the only test that can fail on it is
 * one that goes through the REAL provider function with the REAL repository:
 * store a value the way the Settings screen does, then ask
 * [AppModule.provideGraceCoordinator] for the coordinator the app would get and
 * watch what window it actually arms.
 *
 * Everything below the provider is real too — the real [ConnectionsRegistry]
 * over `core-transport`'s scripted connection, the real
 * `SharedPreferences`-backed repository on Robolectric's on-disk file. Only the
 * Android foreground-service mechanics sit behind [FakeGraceServiceControl],
 * which is the seam that exists for exactly this (see its class doc).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class GraceWindowSettingWiringTest {

    private lateinit var db: AppDatabase
    private var hostId: Long = 0

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()

        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "fixture", privateKeyPath = "/tmp/fixture_ed25519"),
        )
        hostId = db.hostDao().insert(
            HostEntity(
                name = "dev box",
                hostname = "dev.invalid",
                port = 2222,
                username = "tester",
                keyId = keyId,
            ),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    /**
     * The bug itself: a user who chooses "10 min" must get a ten-minute window.
     *
     * Both halves of the window are asserted, because both are what the user
     * feels: the deadline the coordinator hands the foreground service is the
     * count-down the notification renders, and the delay it arms on the
     * transport is when the SSH session is actually dropped. Before the fix
     * both were 90 s.
     */
    @Test
    fun `the stored grace window is the one the provided coordinator arms`() = runBlocking {
        val settings = SettingsRepository(ApplicationProvider.getApplicationContext())
        // Exactly what tapping the Settings row does.
        settings.setBackgroundGraceMillis(AppSettings.BACKGROUND_GRACE_10_MINUTES_MS)

        val (registry, connection) = registryWithOneLiveConnection()
        val service = FakeGraceServiceControl()
        val coordinator = AppModule.provideGraceCoordinator(registry, service, settings)

        val armedAt = System.currentTimeMillis()
        coordinator.enterBackground()

        assertTrue("a background window must be open", coordinator.isHolding)
        assertDeadline(
            what = "the count-down the grace notification shows",
            expectedWindowMs = AppSettings.BACKGROUND_GRACE_10_MINUTES_MS,
            armedAt = armedAt,
            deadlineMs = service.startedDeadlines.single(),
        )
        assertDeadline(
            what = "the transport's own delayed close",
            expectedWindowMs = AppSettings.BACKGROUND_GRACE_10_MINUTES_MS,
            armedAt = armedAt,
            deadlineMs = connection.graceHandles.single().deadlineMs,
        )

        // Leaves nothing armed behind this test (the provider's coordinator
        // runs its expiry on Dispatchers.Default, not a test scheduler).
        coordinator.enterForeground()
    }

    /**
     * The half a value captured at graph-construction time would fail.
     *
     * [GraceCoordinator] and [SettingsRepository] are BOTH process-lifetime
     * `@Singleton`s, so a provider that read `settings.value.backgroundGraceMillis`
     * once, at injection time, would pin the window to whatever was stored when
     * the app launched — the user changes the setting, sees the row update, and
     * still gets the old window until they kill the app. That is the same inert
     * control this issue is about, so the provider passes a supplier and the
     * coordinator reads it per armed window.
     */
    @Test
    fun `changing the setting on a live coordinator changes the next window`() = runBlocking {
        val settings = SettingsRepository(ApplicationProvider.getApplicationContext())
        settings.setBackgroundGraceMillis(AppSettings.BACKGROUND_GRACE_30_SECONDS_MS)

        val (registry, connection) = registryWithOneLiveConnection()
        val service = FakeGraceServiceControl()
        val coordinator = AppModule.provideGraceCoordinator(registry, service, settings)

        val firstArmedAt = System.currentTimeMillis()
        coordinator.enterBackground()
        assertDeadline(
            what = "the first window",
            expectedWindowMs = AppSettings.BACKGROUND_GRACE_30_SECONDS_MS,
            armedAt = firstArmedAt,
            deadlineMs = service.startedDeadlines.single(),
        )
        coordinator.enterForeground()

        // The user opens Settings mid-session and picks a longer window.
        settings.setBackgroundGraceMillis(AppSettings.BACKGROUND_GRACE_5_MINUTES_MS)

        val secondArmedAt = System.currentTimeMillis()
        coordinator.enterBackground()

        assertEquals("the second background must arm a second window", 2, service.startCount)
        assertDeadline(
            what = "the window armed AFTER the setting changed",
            expectedWindowMs = AppSettings.BACKGROUND_GRACE_5_MINUTES_MS,
            armedAt = secondArmedAt,
            deadlineMs = service.startedDeadlines.last(),
        )
        assertDeadline(
            what = "the transport close armed AFTER the setting changed",
            expectedWindowMs = AppSettings.BACKGROUND_GRACE_5_MINUTES_MS,
            armedAt = secondArmedAt,
            deadlineMs = connection.graceHandles.last().deadlineMs,
        )

        coordinator.enterForeground()
    }

    /**
     * A value that survived a process restart is what the next window uses —
     * the same repository instance is not what production has (the graph is
     * rebuilt on every cold start), so the read has to come off the PERSISTED
     * file, not off the object that happened to write it.
     */
    @Test
    fun `a window stored by a previous process run is honoured after a restart`() = runBlocking {
        SettingsRepository(ApplicationProvider.getApplicationContext())
            .setBackgroundGraceMillis(AppSettings.BACKGROUND_GRACE_1_MINUTE_MS)

        // A cold start: a brand-new repository reading the file from scratch,
        // exactly as the next launch's Hilt graph would build it.
        val restarted = SettingsRepository(ApplicationProvider.getApplicationContext())
        val (registry, _) = registryWithOneLiveConnection()
        val service = FakeGraceServiceControl()
        val coordinator = AppModule.provideGraceCoordinator(registry, service, restarted)

        val armedAt = System.currentTimeMillis()
        coordinator.enterBackground()

        assertDeadline(
            what = "the window after a cold start",
            expectedWindowMs = AppSettings.BACKGROUND_GRACE_1_MINUTE_MS,
            armedAt = armedAt,
            deadlineMs = service.startedDeadlines.single(),
        )

        coordinator.enterForeground()
    }

    /**
     * A fresh install must behave exactly as it did before this wiring existed:
     * the settings default and the coordinator's own default are the same 90 s
     * (D21, #1159), so passing the setting through changes nothing for a user
     * who never opens Settings.
     */
    @Test
    fun `a fresh install still gets the ninety-second default`() = runBlocking {
        val settings = SettingsRepository(ApplicationProvider.getApplicationContext())
        assertEquals(
            "the settings default and the coordinator default must not drift apart",
            GraceCoordinator.DEFAULT_GRACE_MS,
            AppSettings.DEFAULT_BACKGROUND_GRACE_MILLIS,
        )

        val (registry, _) = registryWithOneLiveConnection()
        val service = FakeGraceServiceControl()
        val coordinator = AppModule.provideGraceCoordinator(registry, service, settings)

        val armedAt = System.currentTimeMillis()
        coordinator.enterBackground()

        assertDeadline(
            what = "an untouched install's window",
            expectedWindowMs = GraceCoordinator.DEFAULT_GRACE_MS,
            armedAt = armedAt,
            deadlineMs = service.startedDeadlines.single(),
        )

        coordinator.enterForeground()
    }

    // --- helpers -------------------------------------------------------------

    /**
     * Both deadlines under test are `wall-clock now + window`, computed inside
     * `enterBackground` off a real clock, so they are asserted as an interval
     * anchored on the instant the test armed them: no earlier than [armedAt] +
     * the window (the call cannot have happened before the test made it) and no
     * later than that plus a slack far smaller than the gap between ANY two
     * options in [AppSettings.BACKGROUND_GRACE_OPTIONS] — so a wrong option can
     * never satisfy the assertion.
     */
    private fun assertDeadline(
        what: String,
        expectedWindowMs: Long,
        armedAt: Long,
        deadlineMs: Long,
    ) {
        val actualWindow = deadlineMs - armedAt
        assertTrue(
            "$what must be the STORED window of ${expectedWindowMs}ms, but the coordinator " +
                "armed ${actualWindow}ms (issue #2488: the Settings row was inert and every " +
                "window was the ${GraceCoordinator.DEFAULT_GRACE_MS}ms default)",
            actualWindow >= expectedWindowMs && actualWindow <= expectedWindowMs + SLACK_MS,
        )
    }

    private suspend fun registryWithOneLiveConnection(): Pair<ConnectionsRegistry, FakeHostConnection> {
        val factory = FakeHostConnectionFactory()
        val registry = ConnectionsRegistry(
            factory = factory,
            trustStore = RoomTrustStore(db.hostDao(), Dispatchers.Unconfined),
            hostDao = db.hostDao(),
            dispatcher = Dispatchers.Unconfined,
        )
        val connected = registry.getOrConnect(hostId)
        check(connected is ConnectResult.Connected) { "fixture dial must succeed, got $connected" }
        return registry to factory.connections.single()
    }

    private companion object {
        /**
         * Head-room for the wall-clock milliseconds spent inside
         * `enterBackground`. 10 s is generous for a JVM test and still an order
         * of magnitude below the 30 s gap between the two closest offered
         * options, so it cannot let one option pass for another.
         */
        const val SLACK_MS = 10_000L
    }
}
