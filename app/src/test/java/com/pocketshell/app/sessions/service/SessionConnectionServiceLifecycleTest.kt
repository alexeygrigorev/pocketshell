package com.pocketshell.app.sessions.service

import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.settings.AppSettings
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.app.tmux.FakeTmuxClient
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager
import org.robolectric.shadows.ShadowSystemClock

/**
 * Issue #1757: lifecycle contract for the bounded session foreground-service envelope.
 *
 * The service cannot recreate an SSH/tmux transport after process death, so every delivered
 * start is non-sticky. A null-intent platform restart observes the process-local controller's
 * empty snapshot and stops. While a live snapshot exists, the foreground notification and
 * wake lock remain only until foreground handoff, last-client/grace teardown, destruction, or
 * the wake lock's platform safety timeout.
 *
 * All lifecycle transitions use StateFlow + a virtual coroutine scheduler. The wake-lock expiry
 * uses Robolectric's elapsed-realtime clock; no test waits on wall-clock time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class SessionConnectionServiceLifecycleTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ShadowPowerManager.clearWakeLocks()
    }

    @After
    fun tearDown() {
        ShadowPowerManager.clearWakeLocks()
    }

    @Test
    fun `live session start promotes observes and returns non sticky`() {
        val rig = serviceRig(withLiveSession = true)
        try {
            val result = rig.start(ACTION_START_INTENT)

            assertEquals(
                "a successful session hold cannot be recreated after process death",
                Service.START_NOT_STICKY,
                result,
            )
            val shadowService = shadowOf(rig.service)
            assertNotNull(
                "the service must promote before beginning its snapshot observation",
                shadowService.lastForegroundNotification,
            )
            assertFalse(
                "the live controller snapshot must keep the service running during grace",
                shadowService.isStoppedBySelf,
            )
            assertWakeLockHeld()
        } finally {
            rig.close()
        }
    }

    @Test
    fun `live start promotes before wake lock and snapshot work`() {
        val rig = serviceRig(withLiveSession = true)
        try {
            val startupEvents = mutableListOf<String>()
            rig.service.promoteForegroundForTest = {
                startupEvents += FOREGROUND_PROMOTED
            }
            rig.service.startupPhaseForTest = { phase ->
                startupEvents += phase.name
            }

            rig.start(ACTION_START_INTENT)

            assertEquals(
                "Android requires foreground promotion before the service acquires resources " +
                    "or begins controller-driven work",
                listOf(
                    FOREGROUND_PROMOTED,
                    SessionConnectionService.StartupPhase.WAKE_LOCK_ACQUIRED.name,
                    SessionConnectionService.StartupPhase.SNAPSHOT_OBSERVED.name,
                ),
                startupEvents,
            )
        } finally {
            rig.close()
        }
    }

    @Test
    fun `null intent restart with empty process snapshot is non sticky and stops`() {
        val rig = serviceRig(withLiveSession = false)
        try {
            var promoted = false
            rig.service.promoteForegroundForTest = { promoted = true }
            val result = rig.start(intent = null)

            assertEquals(
                "Android must not establish a sticky restart contract for an unrecoverable transport",
                Service.START_NOT_STICKY,
                result,
            )
            assertTrue(
                "a delivered FGS start still promotes before inspecting process-local state",
                promoted,
            )
            val shadowService = shadowOf(rig.service)
            assertTrue(
                "the empty controller snapshot must stop the ghost restart",
                shadowService.isStoppedBySelf,
            )
            assertFalse(
                "stopping the empty restart must remove the foreground notification",
                shadowService.isLastForegroundNotificationAttached,
            )
            assertWakeLockReleased()
        } finally {
            rig.close()
        }
    }

    @Test
    fun `wake lock timeout is authoritative maximum grace plus teardown margin`() {
        assertEquals(
            "the named maximum must stay aligned with the selectable grace options",
            AppSettings.BACKGROUND_GRACE_OPTIONS.maxOf { it.millis },
            AppSettings.MAX_BACKGROUND_GRACE_MILLIS,
        )
        assertEquals(
            EXPECTED_MAX_BACKGROUND_GRACE_MILLIS,
            AppSettings.MAX_BACKGROUND_GRACE_MILLIS,
        )
        assertEquals(
            EXPECTED_WAKE_LOCK_TEARDOWN_MARGIN_MILLIS,
            SessionConnectionService.WAKE_LOCK_TEARDOWN_MARGIN_MILLIS,
        )
        assertEquals(
            EXPECTED_WAKE_LOCK_TIMEOUT_MILLIS,
            SessionConnectionService.WAKE_LOCK_TIMEOUT_MILLIS,
        )

        val rig = serviceRig(withLiveSession = true)
        try {
            rig.start(ACTION_START_INTENT)
            val wakeLock = requireNotNull(ShadowPowerManager.getLatestWakeLock())
            assertTrue(wakeLock.isHeld)

            ShadowSystemClock.advanceBy(
                Duration.ofMillis(EXPECTED_WAKE_LOCK_TIMEOUT_MILLIS - 1L),
            )
            assertTrue(
                "the safety timeout must cover the full maximum grace plus almost all margin",
                wakeLock.isHeld,
            )

            // Robolectric considers the lock held at the exact deadline and expires it on the
            // first elapsed-realtime tick after it, matching the platform's at-most contract.
            ShadowSystemClock.advanceBy(Duration.ofMillis(2L))
            assertFalse(
                "the platform safety timeout must release a wedged ownership path",
                wakeLock.isHeld,
            )
        } finally {
            rig.close()
        }
    }

    @Test
    fun `foreground return releases wake lock before its timeout`() {
        val rig = serviceRig(withLiveSession = true)
        try {
            rig.start(ACTION_START_INTENT)
            assertWakeLockHeld()

            rig.controller.onAppForegrounded()
            rig.scheduler.runCurrent()

            assertTrue(shadowOf(rig.service).isStoppedBySelf)
            assertWakeLockReleased()
        } finally {
            rig.close()
        }
    }

    @Test
    fun `last client grace teardown releases wake lock before its timeout`() {
        val rig = serviceRig(withLiveSession = true)
        try {
            rig.start(ACTION_START_INTENT)
            assertWakeLockHeld()

            requireNotNull(rig.client).disconnectedSignal.value = true
            rig.scheduler.runCurrent()

            assertTrue(
                "the controller's empty snapshot after last-client teardown must stop the hold",
                shadowOf(rig.service).isStoppedBySelf,
            )
            assertWakeLockReleased()
        } finally {
            rig.close()
        }
    }

    @Test
    fun `service destruction releases wake lock before its timeout`() {
        val rig = serviceRig(withLiveSession = true)
        try {
            rig.start(ACTION_START_INTENT)
            assertWakeLockHeld()

            rig.destroyService()

            assertWakeLockReleased()
        } finally {
            rig.close()
        }
    }

    private fun serviceRig(withLiveSession: Boolean): ServiceRig {
        val scheduler = TestCoroutineScheduler()
        val activeClients = ActiveTmuxClients()
        val controller = SessionServiceController(context, activeClients).apply {
            scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
            nowMillis = { scheduler.currentTime }
            observeActiveSessions()
        }
        scheduler.runCurrent()

        val client = if (withLiveSession) FakeTmuxClient() else null
        val registration = client?.let {
            activeClients.register(
                hostId = 1L,
                hostName = "alpha",
                hostname = "alpha.example",
                port = 22,
                username = "alexey",
                keyPath = "/tmp/key",
                client = it,
            )
        }
        scheduler.runCurrent()
        if (withLiveSession) {
            controller.onAppBackgrounded(disconnectAtWallClockMillis = MAX_GRACE_DEADLINE_MS)
            scheduler.runCurrent()
            assertTrue(controller.flowOfSnapshot().value.isHoldingConnection)
        }

        val serviceController = Robolectric.buildService(SessionConnectionService::class.java)
        val service = serviceController.get().apply {
            this.controller = controller
            observeDispatcher = Dispatchers.Unconfined
            createNotificationChannel()
        }
        return ServiceRig(
            service = service,
            controller = controller,
            activeClients = activeClients,
            registration = registration,
            client = client,
            scheduler = scheduler,
        )
    }

    private fun assertWakeLockHeld() {
        val wakeLock = requireNotNull(ShadowPowerManager.getLatestWakeLock())
        assertEquals(WAKE_LOCK_TAG, shadowOf(wakeLock).tag)
        assertTrue(wakeLock.isHeld)
    }

    private fun assertWakeLockReleased() {
        val wakeLock = ShadowPowerManager.getLatestWakeLock()
        assertTrue(
            "the service must have acquired the session wake lock before releasing it",
            wakeLock != null,
        )
        assertFalse(wakeLock?.isHeld == true)
    }

    private class ServiceRig(
        val service: SessionConnectionService,
        val controller: SessionServiceController,
        val activeClients: ActiveTmuxClients,
        val registration: ActiveTmuxClients.Registration?,
        val client: FakeTmuxClient?,
        val scheduler: TestCoroutineScheduler,
    ) : AutoCloseable {
        private var serviceDestroyed = false

        fun start(intent: Intent?): Int = service.onStartCommand(intent, 0, 1)

        fun destroyService() {
            if (serviceDestroyed) return
            serviceDestroyed = true
            service.onDestroy()
        }

        override fun close() {
            destroyService()
            registration?.let(activeClients::unregister)
            controller.scope.cancel()
        }
    }

    private companion object {
        const val WAKE_LOCK_TAG = "PocketShell:session"
        const val FOREGROUND_PROMOTED = "FOREGROUND_PROMOTED"
        const val EXPECTED_MAX_BACKGROUND_GRACE_MILLIS = 10 * 60_000L
        const val EXPECTED_WAKE_LOCK_TEARDOWN_MARGIN_MILLIS = 30_000L
        const val EXPECTED_WAKE_LOCK_TIMEOUT_MILLIS =
            EXPECTED_MAX_BACKGROUND_GRACE_MILLIS + EXPECTED_WAKE_LOCK_TEARDOWN_MARGIN_MILLIS
        const val MAX_GRACE_DEADLINE_MS = EXPECTED_MAX_BACKGROUND_GRACE_MILLIS

        val ACTION_START_INTENT: Intent
            get() = Intent().apply { action = SessionConnectionService.ACTION_START }
    }
}
