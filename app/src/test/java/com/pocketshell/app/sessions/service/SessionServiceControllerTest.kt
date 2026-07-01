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
 * Issue #1159 (Part 1 + Part 3): the session foreground-service hold.
 *
 * Part 1: the FGS runs ONLY while the app is BACKGROUNDED. A live session in the
 * foreground does NOT start the service (the Activity holds the connection — no tray
 * notification, no Stop footgun); backgrounding starts it, foregrounding stops it, and
 * neither transition touches the connection itself.
 *
 * Part 3: while a port-forward is active the emitted snapshot is flagged `portForwardActive`
 * with NO count-down deadline (the connection is pinned always-on, so there is nothing to
 * count down to); dropping the forward restores the normal bounded-grace count-down.
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
    fun `live tmux client in the foreground does NOT start the service`() = runTest {
        val activeClients = ActiveTmuxClients()
        val controller = controller(activeClients, testScheduler)

        controller.observeActiveSessions()
        runCurrent()
        // Default state is foreground (a cold start opens into a foreground Activity).
        registerLive(activeClients, "alpha")
        runCurrent()

        assertNull(
            "a foregrounded live session must NOT start the FGS (#1159 Part 1)",
            shadow.nextStartedService,
        )
        assertFalse(
            "the snapshot must not hold while foregrounded",
            controller.flowOfSnapshot().value.isHoldingConnection,
        )
    }

    @Test
    fun `backgrounding a live session starts the service and foregrounding stops it`() = runTest {
        val activeClients = ActiveTmuxClients()
        val controller = controller(activeClients, testScheduler)

        controller.observeActiveSessions()
        runCurrent()
        registerLive(activeClients, "alpha")
        runCurrent()
        drainStartedServices()

        val deadline = 1_000L
        controller.onAppBackgrounded(disconnectAtWallClockMillis = deadline)
        runCurrent()

        val started = shadow.nextStartedService
        assertNotNull("backgrounding a live session must start SessionConnectionService", started)
        assertEquals(SessionConnectionService::class.java.name, started?.component?.className)
        assertEquals(SessionConnectionService.ACTION_START, started?.action)
        val bgSnapshot = controller.flowOfSnapshot().value
        assertTrue(bgSnapshot.isHoldingConnection)
        assertEquals("alpha", bgSnapshot.primaryHostName)
        assertEquals(
            "backgrounded hold must carry the bounded count-down deadline",
            deadline,
            bgSnapshot.disconnectAtWallClockMillis,
        )

        controller.onAppForegrounded()
        runCurrent()

        val stopped = shadow.nextStartedService
        assertNotNull("returning to the foreground must stop the FGS", stopped)
        assertEquals(SessionConnectionService.ACTION_STOP, stopped?.action)
        assertFalse(controller.flowOfSnapshot().value.isHoldingConnection)
    }

    @Test
    fun `port-forward active flags the snapshot with no count-down`() = runTest {
        val activeClients = ActiveTmuxClients()
        val controller = controller(activeClients, testScheduler)

        controller.observeActiveSessions()
        runCurrent()
        registerLive(activeClients, "alpha")
        runCurrent()
        controller.setPortForwardActive(true)
        controller.onAppBackgrounded(disconnectAtWallClockMillis = 5_000L)
        runCurrent()

        val snapshot = controller.flowOfSnapshot().value
        assertTrue("a port-forward-pinned session must still hold", snapshot.isHoldingConnection)
        assertTrue("port-forward-active must flag the snapshot", snapshot.portForwardActive)
        assertNull(
            "a port-forward pins the connection always-on — NO count-down deadline (#1159 Part 3)",
            snapshot.disconnectAtWallClockMillis,
        )
    }

    @Test
    fun `dropping the port-forward restores the bounded count-down while still backgrounded`() = runTest {
        val activeClients = ActiveTmuxClients()
        val controller = controller(activeClients, testScheduler)

        controller.observeActiveSessions()
        runCurrent()
        registerLive(activeClients, "alpha")
        controller.setPortForwardActive(true)
        controller.onAppBackgrounded(disconnectAtWallClockMillis = 5_000L)
        runCurrent()
        assertTrue(controller.flowOfSnapshot().value.portForwardActive)
        assertNull(controller.flowOfSnapshot().value.disconnectAtWallClockMillis)

        controller.setPortForwardActive(false)
        runCurrent()

        val snapshot = controller.flowOfSnapshot().value
        assertFalse("dropping the forward must clear the flag", snapshot.portForwardActive)
        assertEquals(
            "dropping the forward restores the bounded count-down deadline",
            5_000L,
            snapshot.disconnectAtWallClockMillis,
        )
    }

    @Test
    fun `disconnecting the last live tmux client stops the backgrounded service`() = runTest {
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
        controller.onAppBackgrounded(disconnectAtWallClockMillis = 1_000L)
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
    fun `notification stop stops the foreground service for the current backgrounded session`() = runTest {
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
        controller.onAppBackgrounded(disconnectAtWallClockMillis = 1_000L)
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
    }

    @Test
    fun `observing an empty registry does not start or stop the service`() = runTest {
        val controller = controller(ActiveTmuxClients(), testScheduler)

        controller.observeActiveSessions()
        runCurrent()
        controller.onAppBackgrounded(disconnectAtWallClockMillis = 1_000L)
        runCurrent()

        assertNull(shadow.nextStartedService)
        assertFalse(controller.flowOfSnapshot().value.isHoldingConnection)
    }

    private fun registerLive(activeClients: ActiveTmuxClients, hostName: String) {
        activeClients.register(
            hostId = 1L,
            hostName = hostName,
            hostname = "$hostName.example",
            port = 22,
            username = "alexey",
            keyPath = "/tmp/key",
            client = FakeTmuxClient(),
        )
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
