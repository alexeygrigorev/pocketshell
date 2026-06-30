package com.pocketshell.app.sessions.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.app.tmux.FakeTmuxClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowApplication

/**
 * Issue #1123 (bounded-grace D21 update): the controller is now a simple BOUNDED
 * keepalive — it starts the foreground service on the first live tmux client and stops it
 * when the last live client goes away. The old indefinite-hold gate
 * (`isHoldingSessionConnection` + the foreground-promotion tracking) and the
 * notification-Stop-after-grace event flows are removed, so these tests assert only the
 * start/stop transitions + the snapshot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class SessionServiceControllerTest {

    private lateinit var context: Context
    private lateinit var shadow: ShadowApplication

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shadow = Shadows.shadowOf(context.applicationContext as android.app.Application)
        drainStartedServices()
    }

    @Test
    fun `first live tmux client starts the session foreground service`() = runTest {
        val activeClients = ActiveTmuxClients()
        val controller = controller(activeClients, testScheduler)

        controller.observeActiveSessions()
        runCurrent()
        activeClients.register(
            hostId = 1L,
            hostName = "alpha",
            hostname = "alpha.example",
            port = 22,
            username = "alexey",
            keyPath = "/tmp/key",
            client = FakeTmuxClient(),
        )
        runCurrent()

        val started = shadow.nextStartedService
        assertNotNull("first live session must start SessionConnectionService", started)
        assertEquals(SessionConnectionService::class.java.name, started?.component?.className)
        assertEquals(SessionConnectionService.ACTION_START, started?.action)
        assertTrue(controller.flowOfSnapshot().value.isHoldingConnection)
        assertEquals("alpha", controller.flowOfSnapshot().value.primaryHostName)
    }

    @Test
    fun `disconnecting the last live tmux client stops the session foreground service`() = runTest {
        val activeClients = ActiveTmuxClients()
        val client = FakeTmuxClient()
        val controller = controller(activeClients, testScheduler)

        controller.observeActiveSessions()
        runCurrent()
        activeClients.register(
            hostId = 1L,
            hostName = "alpha",
            hostname = "alpha.example",
            port = 22,
            username = "alexey",
            keyPath = "/tmp/key",
            client = client,
        )
        runCurrent()
        drainStartedServices()

        client.disconnectedSignal.value = true
        runCurrent()

        val stopped = shadow.nextStartedService
        assertNotNull("last disconnected session must stop SessionConnectionService", stopped)
        assertEquals(SessionConnectionService::class.java.name, stopped?.component?.className)
        assertEquals(SessionConnectionService.ACTION_STOP, stopped?.action)
        assertFalse(controller.flowOfSnapshot().value.isHoldingConnection)
    }

    @Test
    fun `current snapshot ignores disconnected clients`() {
        val activeClients = ActiveTmuxClients()
        val connected = FakeTmuxClient()
        val disconnected = FakeTmuxClient().apply {
            disconnectedSignal.value = true
        }
        activeClients.register(
            hostId = 1L,
            hostName = "alpha",
            hostname = "alpha.example",
            port = 22,
            username = "alexey",
            keyPath = "/tmp/key",
            client = connected,
        )
        activeClients.register(
            hostId = 2L,
            hostName = "beta",
            hostname = "beta.example",
            port = 22,
            username = "alexey",
            keyPath = "/tmp/key",
            client = disconnected,
        )
        val controller = controller(activeClients, TestCoroutineScheduler())

        val snapshot = controller.currentSnapshot()

        assertEquals(1, snapshot.liveSessionCount)
        assertEquals("alpha", snapshot.primaryHostName)
    }

    @Test
    fun `notification stop stops the foreground service for the current live session set`() = runTest {
        val activeClients = ActiveTmuxClients()
        val client = FakeTmuxClient()
        val controller = controller(activeClients, testScheduler)

        controller.observeActiveSessions()
        runCurrent()
        activeClients.register(
            hostId = 1L,
            hostName = "alpha",
            hostname = "alpha.example",
            port = 22,
            username = "alexey",
            keyPath = "/tmp/key",
            client = client,
        )
        runCurrent()
        drainStartedServices()

        controller.stopHoldingFromNotification()
        runCurrent()

        assertFalse(
            "explicit notification Stop must drop the current live-session hold snapshot",
            controller.flowOfSnapshot().value.isHoldingConnection,
        )
        val stopped = shadow.nextStartedService
        assertNotNull(stopped)
        assertEquals(SessionConnectionService.ACTION_STOP, stopped?.action)

        controller.stopHoldingFromNotification()
        runCurrent()
        assertNull(
            "duplicate Stop delivery must not re-issue a service intent",
            shadow.nextStartedService,
        )

        // After an all-clear / re-connect transition the bounded hold may start again.
        client.disconnectedSignal.value = true
        runCurrent()
        client.disconnectedSignal.value = false
        runCurrent()

        val restarted = shadow.nextStartedService
        assertNotNull("a fresh live-client transition after all-clear may start the hold again", restarted)
        assertEquals(SessionConnectionService.ACTION_START, restarted?.action)
        assertTrue(controller.flowOfSnapshot().value.isHoldingConnection)
    }

    @Test
    fun `observing an empty registry does not start or stop the service`() = runTest {
        val controller = controller(ActiveTmuxClients(), testScheduler)

        controller.observeActiveSessions()
        runCurrent()

        assertNull(shadow.nextStartedService)
        assertFalse(controller.flowOfSnapshot().value.isHoldingConnection)
    }

    private fun controller(
        activeClients: ActiveTmuxClients,
        scheduler: TestCoroutineScheduler,
    ): SessionServiceController {
        return SessionServiceController(context, activeClients).apply {
            scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        }
    }

    private fun drainStartedServices() {
        while (shadow.nextStartedService != null) {
            // Drain all queued intents.
        }
    }
}
