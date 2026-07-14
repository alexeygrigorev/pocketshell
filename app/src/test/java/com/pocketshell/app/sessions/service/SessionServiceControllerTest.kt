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
    fun `onAppPausing starts the FGS foreground-eligibly after the debounce and onAppResumed stops it (issue 1595)`() = runTest {
        // Issue #1595: the foreground-eligible start path. onAppPausing() (driven by the ACTIVITY
        // going background — all activities stopped, the earliest point Android 12+ allows
        // startForegroundService) starts the FGS BEFORE the backgrounded onAppBackgrounded
        // (ON_STOP) ever runs; onAppResumed() stops it. On base the FGS could only start from
        // onAppBackgrounded (background) — this method did not exist.
        //
        // Round 2 (debounce): the start is DEFERRED by PAUSE_START_DEBOUNCE_MILLIS so a transient
        // stop→resume never flashes the FGS. A PERSISTED background — no resume before the window
        // elapses — must still start the FGS (well under the ~700ms ON_STOP, so eligible).
        val activeClients = ActiveTmuxClients()
        val controller = controller(activeClients, testScheduler)

        controller.observeActiveSessions()
        runCurrent()
        registerLive(activeClients, "alpha")
        runCurrent()
        drainStartedServices()

        controller.onAppPausing()
        runCurrent()
        // Within the debounce window the FGS has NOT started yet (a transient could still resume).
        assertNull(
            "the FGS must not start until the debounce elapses — a transient could still resume",
            shadow.nextStartedService,
        )
        assertFalse(controller.flowOfSnapshot().value.isHoldingConnection)

        // The background persisted past the debounce → the FGS starts, still foreground-eligible.
        testScheduler.advanceTimeBy(SessionServiceController.PAUSE_START_DEBOUNCE_MILLIS + 1)
        runCurrent()

        val started = shadow.nextStartedService
        assertNotNull(
            "a persisted background must start the FGS once the debounce elapses (#1595)",
            started,
        )
        assertEquals(SessionConnectionService.ACTION_START, started?.action)
        assertTrue(controller.flowOfSnapshot().value.isHoldingConnection)

        controller.onAppResumed()
        runCurrent()

        val stopped = shadow.nextStartedService
        assertNotNull("onAppResumed must stop the FGS (the Activity holds the connection)", stopped)
        assertEquals(SessionConnectionService.ACTION_STOP, stopped?.action)
        assertFalse(controller.flowOfSnapshot().value.isHoldingConnection)
    }

    @Test
    fun `a quick pause then resume within the debounce never starts the FGS — no thrash (issue 1595 round 2)`() = runTest {
        // Reviewer finding (round 2): onActivityPaused fired on EVERY transient focus loss and the
        // FGS promoted IMMEDIATELY, flashing a Stop-able notification into the tray on a routine
        // pause→resume (recents peek / shade / quick app-switch return). This is the RED→GREEN
        // regression: a quick stop→resume WITHIN the debounce must post NO notification and leave
        // NO running FGS — neither a START nor a STOP intent is issued.
        //
        // RED on the pre-debounce code: onAppPausing() flipped foreground=false synchronously, so
        // a START intent was issued at once (and onAppResumed then a STOP) — the start-then-stop
        // thrash. GREEN: the debounce is cancelled before it fires, so nothing is ever started.
        val activeClients = ActiveTmuxClients()
        val controller = controller(activeClients, testScheduler)

        controller.observeActiveSessions()
        runCurrent()
        registerLive(activeClients, "alpha")
        runCurrent()
        drainStartedServices()

        controller.onAppPausing()
        runCurrent()
        // Resume WELL within the debounce window (a permission dialog dismissed, picker returned,
        // shade closed, recents peek — a quick return).
        testScheduler.advanceTimeBy(SessionServiceController.PAUSE_START_DEBOUNCE_MILLIS / 2)
        runCurrent()
        controller.onAppResumed()
        runCurrent()
        // Advance well PAST the original debounce deadline — the cancelled debounce must not fire.
        testScheduler.advanceTimeBy(SessionServiceController.PAUSE_START_DEBOUNCE_MILLIS * 4)
        runCurrent()

        assertNull(
            "a quick pause→resume within the debounce must issue NO service intent — no START " +
                "(no notification flash) and no STOP (no thrash) (#1595 round 2)",
            shadow.nextStartedService,
        )
        assertFalse(
            "no FGS hold may linger after a transient pause→resume",
            controller.flowOfSnapshot().value.isHoldingConnection,
        )
    }

    @Test
    fun `onAppBackgrounded after onAppPausing stamps the deadline without a duplicate start (issue 1595)`() = runTest {
        // Issue #1595: onAppPausing already started the FGS foreground-eligibly, so the later
        // ON_STOP onAppBackgrounded is idempotent for the start (no duplicate intent) and only
        // stamps the bounded count-down deadline onto the already-held snapshot.
        val activeClients = ActiveTmuxClients()
        val controller = controller(activeClients, testScheduler)

        controller.observeActiveSessions()
        runCurrent()
        registerLive(activeClients, "alpha")
        runCurrent()
        drainStartedServices()

        controller.onAppPausing()
        runCurrent()
        // The background persisted past the debounce → the foreground-eligible start fires.
        testScheduler.advanceTimeBy(SessionServiceController.PAUSE_START_DEBOUNCE_MILLIS + 1)
        runCurrent()
        assertEquals(SessionConnectionService.ACTION_START, shadow.nextStartedService?.action)

        controller.onAppBackgrounded(disconnectAtWallClockMillis = 5_000L)
        runCurrent()

        assertNull(
            "ON_STOP after a foreground-eligible pause must NOT re-issue a start intent",
            shadow.nextStartedService,
        )
        val snapshot = controller.flowOfSnapshot().value
        assertTrue(snapshot.isHoldingConnection)
        assertEquals(
            "ON_STOP stamps the bounded count-down deadline onto the already-held snapshot",
            5_000L,
            snapshot.disconnectAtWallClockMillis,
        )
    }

    @Test
    fun `onAppBackgrounded still starts the FGS as a fallback when onAppPausing was skipped (issue 1595)`() = runTest {
        // Issue #1595: config-change pauses skip onAppPausing, so the background onAppBackgrounded
        // (ON_STOP) must still start the FGS exactly as before — the fix ADDS an earlier
        // foreground-eligible attempt, it never removes the background fallback.
        val activeClients = ActiveTmuxClients()
        val controller = controller(activeClients, testScheduler)

        controller.observeActiveSessions()
        runCurrent()
        registerLive(activeClients, "alpha")
        runCurrent()
        drainStartedServices()

        // No onAppPausing() call (e.g. the pause was a configuration change and was skipped).
        controller.onAppBackgrounded(disconnectAtWallClockMillis = 1_000L)
        runCurrent()

        val started = shadow.nextStartedService
        assertNotNull("onAppBackgrounded must remain a fallback FGS start (#1595)", started)
        assertEquals(SessionConnectionService.ACTION_START, started?.action)
        assertTrue(controller.flowOfSnapshot().value.isHoldingConnection)
    }

    @Test
    fun `port-forward active stops the session FGS so ForwardingService owns the single notification`() = runTest {
        // Issue #1202 + #1198 (reported on-device, v0.4.23): a hetzner session is held in the
        // background — the session FGS is up and its notification (reworded "Port forwarding
        // active", #1159) is on screen. A port-forward then goes active on the SAME host, so
        // the ForwardingService FGS posts its OWN "Port forwarding running" notification too:
        // TWO stacked notifications. Worse, the maintainer taps Stop on the session's "Port
        // forwarding active" notification (the one deliberately worded to represent the
        // forward) and it only ends the session hold — the tunnels keep running.
        //
        // The fix collapses to ONE notification whose Stop actually stops forwarding: the
        // ForwardingService FGS is the SINGLE owner (its Stop calls
        // ForwardingController.stopAllForwarding), and the session FGS is SUPPRESSED while a
        // port-forward is active. The ForwardingService FGS already keeps the process (and the
        // pinned connection) alive, so nothing is lost by stopping the session FGS here.
        val activeClients = ActiveTmuxClients()
        val controller = controller(activeClients, testScheduler)

        controller.observeActiveSessions()
        runCurrent()
        registerLive(activeClients, "hetzner")
        controller.onAppBackgrounded(disconnectAtWallClockMillis = 5_000L)
        runCurrent()
        // The session FGS is up and holding — its notification is on screen.
        assertEquals(SessionConnectionService.ACTION_START, shadow.nextStartedService?.action)
        drainStartedServices()
        assertTrue(controller.flowOfSnapshot().value.isHoldingConnection)

        // A port-forward goes active on the pinned host.
        controller.setPortForwardActive(true)
        runCurrent()

        val stopped = shadow.nextStartedService
        assertNotNull(
            "port-forward active must STOP the session FGS so its second, broken-Stop " +
                "notification disappears — ForwardingService is the single owner (#1202/#1198)",
            stopped,
        )
        assertEquals(SessionConnectionService::class.java.name, stopped?.component?.className)
        assertEquals(SessionConnectionService.ACTION_STOP, stopped?.action)
        assertFalse(
            "the session FGS must not keep holding a second port-forward notification",
            controller.flowOfSnapshot().value.isHoldingConnection,
        )
    }

    @Test
    fun `standalone forward with no live session never starts the session FGS`() = runTest {
        // Class coverage (G2): a pure port-forward with NO interactive tmux session. The
        // session FGS must never start (no live client to hold), so ForwardingService is
        // trivially the single owner even before/without the suppression.
        val activeClients = ActiveTmuxClients()
        val controller = controller(activeClients, testScheduler)

        controller.observeActiveSessions()
        runCurrent()
        controller.setPortForwardActive(true)
        controller.onAppBackgrounded(disconnectAtWallClockMillis = 5_000L)
        runCurrent()

        assertNull(
            "a standalone forward (no live tmux session) must not start the session FGS",
            shadow.nextStartedService,
        )
        assertFalse(controller.flowOfSnapshot().value.isHoldingConnection)
    }

    @Test
    fun `dropping the forward while still backgrounded restarts the session FGS with the count-down`() = runTest {
        // Class coverage (G2): the transition back. While the forward pins the connection the
        // session FGS is suppressed; when the last forward stops (activeHostCount 0 →
        // setPortForwardActive(false)) and the app is still backgrounded with a live session,
        // the session FGS restarts with the normal bounded-grace count-down restored.
        val activeClients = ActiveTmuxClients()
        val controller = controller(activeClients, testScheduler)

        controller.observeActiveSessions()
        runCurrent()
        registerLive(activeClients, "hetzner")
        controller.setPortForwardActive(true)
        controller.onAppBackgrounded(disconnectAtWallClockMillis = 5_000L)
        runCurrent()
        drainStartedServices()
        assertFalse(
            "while the forward pins the connection, the session FGS is suppressed",
            controller.flowOfSnapshot().value.isHoldingConnection,
        )

        controller.setPortForwardActive(false)
        runCurrent()

        val restarted = shadow.nextStartedService
        assertNotNull("dropping the forward while backgrounded must restart the session FGS", restarted)
        assertEquals(SessionConnectionService.ACTION_START, restarted?.action)
        val snapshot = controller.flowOfSnapshot().value
        assertTrue("the live session must hold again once the forward is gone", snapshot.isHoldingConnection)
        assertEquals(
            "dropping the forward restores the bounded count-down deadline",
            5_000L,
            snapshot.disconnectAtWallClockMillis,
        )
    }

    @Test
    fun `deadline elapsing while still backgrounded flips the snapshot to reconnecting (issue 1440)`() = runTest {
        // Issue #1440 (scope 3 + 4): the system count-down chronometer is fire-and-forget (#1123
        // posts it once). When the grace deadline passes while a live client is STILL registered
        // (reconnecting / hung), nothing re-posts — so the timer drifts past zero into a negative
        // value and the copy stays frozen on the grace-hold. The controller must arm a wakeup at
        // the deadline that re-emits a RECONNECTING snapshot (no live count-down deadline).
        val activeClients = ActiveTmuxClients()
        val controller = controller(activeClients, testScheduler)

        controller.observeActiveSessions()
        runCurrent()
        registerLive(activeClients, "alpha")
        val deadline = 5_000L
        controller.onAppBackgrounded(disconnectAtWallClockMillis = deadline)
        runCurrent()

        // Within grace: a live count-down, NOT reconnecting.
        val holding = controller.flowOfSnapshot().value
        assertTrue(holding.isHoldingConnection)
        assertFalse("within grace the snapshot must not be reconnecting", holding.reconnecting)
        assertEquals(
            SessionConnectionSnapshot.Phase.HOLDING_GRACE,
            holding.phaseAt(testScheduler.currentTime),
        )

        // The deadline elapses while the session is still held → re-post as reconnecting.
        testScheduler.advanceTimeBy(deadline + 1)
        runCurrent()

        val expired = controller.flowOfSnapshot().value
        assertTrue("the session is still held while reconnecting", expired.isHoldingConnection)
        assertTrue(
            "an elapsed grace deadline with a live client must flip the snapshot to reconnecting",
            expired.reconnecting,
        )
        assertEquals(
            SessionConnectionSnapshot.Phase.RECONNECTING,
            expired.phaseAt(testScheduler.currentTime),
        )
    }

    @Test
    fun `returning to the foreground before the deadline cancels the reconnecting flip (issue 1440)`() = runTest {
        // Class coverage (G2): if the app returns to the foreground within grace, the scheduled
        // deadline flip must be cancelled — the FGS stops (Activity holds the connection) and no
        // stale reconnecting notification is ever posted.
        val activeClients = ActiveTmuxClients()
        val controller = controller(activeClients, testScheduler)

        controller.observeActiveSessions()
        runCurrent()
        registerLive(activeClients, "alpha")
        controller.onAppBackgrounded(disconnectAtWallClockMillis = 5_000L)
        runCurrent()
        drainStartedServices()

        controller.onAppForegrounded()
        runCurrent()
        // Advance well past the original deadline — the cancelled flip must not fire.
        testScheduler.advanceTimeBy(10_000L)
        runCurrent()

        val snapshot = controller.flowOfSnapshot().value
        assertFalse("foreground handoff stops the hold", snapshot.isHoldingConnection)
        assertFalse("a cancelled deadline flip must not leave a reconnecting snapshot", snapshot.reconnecting)
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
            // Issue #1440: tie the deadline-flip clock to the virtual scheduler so the scheduled
            // `delay(deadline - now)` and the deadline value agree under virtual time.
            nowMillis = { scheduler.currentTime }
        }
    }

    private fun drainStartedServices() {
        while (shadow.nextStartedService != null) {
            // Drain all queued intents.
        }
    }
}
