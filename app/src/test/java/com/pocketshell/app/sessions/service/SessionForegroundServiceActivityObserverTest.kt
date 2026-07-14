package com.pocketshell.app.sessions.service

import android.app.Activity
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowApplication

/**
 * Issue #1595 (lifecycle-fix slice, red→green + round-2 anti-thrash gate).
 *
 * The session FGS must be started while the app is still FOREGROUND-ELIGIBLE — NOT only from the
 * backgrounded ProcessLifecycleOwner `ON_STOP` (which fires ~700ms into the background, where
 * Android 12+ rejects the start and the OS kills the `-CC` socket ~4.4s later; device-log audit
 * #1562). Round 2 (reviewer finding): the background signal is keyed off the activity
 * STARTED-count reaching zero (`onActivityStopped`), NOT `onActivityPaused` — a transient overlay
 * that keeps the activity STARTED (permission dialog / share sheet / shade) must NEVER flash the
 * Stop-able notification, and a quick stop→start (recents peek) must be absorbed by the
 * controller debounce.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class SessionForegroundServiceActivityObserverTest {

    private lateinit var context: Context
    private lateinit var shadow: ShadowApplication

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shadow = Shadows.shadowOf(context.applicationContext as android.app.Application)
        drainStartedServices()
    }

    @Test
    fun `a genuine background (all activities stopped) starts the FGS foreground-eligibly after the debounce`() = runTest {
        val activeClients = ActiveTmuxClients()
        val controller = controller(activeClients, testScheduler)
        val observer = SessionForegroundServiceActivityObserver(controller)

        controller.observeActiveSessions()
        runCurrent()
        registerLive(activeClients, "alpha")
        runCurrent()
        // App is foreground (an activity is started): no FGS yet (#1159 Part 1).
        observer.onActivityStarted(activity())
        runCurrent()
        drainStartedServices()

        // The maintainer switches apps: the ONLY activity stops (all activities stopped = a real
        // background). onAppBackgrounded (ProcessLifecycleOwner ON_STOP) has NOT run yet.
        observer.onActivityStopped(activity())
        runCurrent()
        // Within the debounce window nothing has started (a transient could still resume).
        assertNull(
            "the FGS must not start within the debounce — a transient stop→start could resume",
            shadow.nextStartedService,
        )

        testScheduler.advanceTimeBy(debounce() + 1)
        runCurrent()

        val started = shadow.nextStartedService
        assertNotNull(
            "a persisted background must start the session FGS while foreground-eligible, " +
                "BEFORE the ~700ms ON_STOP Android 12+ rejects (#1595)",
            started,
        )
        assertEquals(SessionConnectionService::class.java.name, started?.component?.className)
        assertEquals(SessionConnectionService.ACTION_START, started?.action)
        assertTrueMsg(
            "the hold snapshot must be active once genuinely backgrounded with a live session",
            controller.flowOfSnapshot().value.isHoldingConnection,
        )
    }

    @Test
    fun `a transient overlay that keeps the activity STARTED never starts the FGS (permission dialog, share sheet, shade)`() = runTest {
        // Reviewer finding (round 2): a permission dialog / SAF picker sheet / share sheet /
        // notification shade only PAUSES the activity — it stays STARTED (visible). The observer
        // must NOT start the FGS on that, however long the overlay lingers, or a Stop-able
        // "Session connected" notification flashes into the tray (the #1159 lines 38–44 footgun).
        val activeClients = ActiveTmuxClients()
        val controller = controller(activeClients, testScheduler)
        val observer = SessionForegroundServiceActivityObserver(controller)

        controller.observeActiveSessions()
        runCurrent()
        registerLive(activeClients, "alpha")
        runCurrent()
        observer.onActivityStarted(activity())
        runCurrent()
        drainStartedServices()

        // A permission dialog pops over the activity: onActivityPaused fires, but the activity is
        // NEVER stopped (still STARTED behind the dialog). Wait far longer than the debounce.
        observer.onActivityPaused(activity())
        runCurrent()
        testScheduler.advanceTimeBy(debounce() * 10)
        runCurrent()

        assertNull(
            "an overlay that keeps the activity STARTED must NEVER start the FGS — no tray " +
                "notification flash on a permission dialog / picker / share sheet / shade (#1595)",
            shadow.nextStartedService,
        )
        assertFalse(controller.flowOfSnapshot().value.isHoldingConnection)
    }

    @Test
    fun `a quick stop then start within the debounce never starts the FGS — no thrash (recents peek)`() = runTest {
        // A recents peek / quick app-switch return: the activity really STOPS then STARTS again
        // within the debounce window. No FGS may be started (no START) and none left running
        // (no STOP) — no start→stop thrash.
        val activeClients = ActiveTmuxClients()
        val controller = controller(activeClients, testScheduler)
        val observer = SessionForegroundServiceActivityObserver(controller)

        controller.observeActiveSessions()
        runCurrent()
        registerLive(activeClients, "alpha")
        runCurrent()
        observer.onActivityStarted(activity())
        runCurrent()
        drainStartedServices()

        observer.onActivityStopped(activity())
        runCurrent()
        testScheduler.advanceTimeBy(debounce() / 2)
        runCurrent()
        // The user returns within the window.
        observer.onActivityStarted(activity())
        runCurrent()
        // Advance well past the original debounce deadline — the cancelled debounce must not fire.
        testScheduler.advanceTimeBy(debounce() * 4)
        runCurrent()

        assertNull(
            "a quick stop→start within the debounce must issue NO service intent — no START " +
                "(no notification) and no STOP (no thrash) (#1595 round 2)",
            shadow.nextStartedService,
        )
        assertFalse(controller.flowOfSnapshot().value.isHoldingConnection)
    }

    @Test
    fun `a configuration-change stop does NOT start the FGS (no rotation thrash)`() = runTest {
        val activeClients = ActiveTmuxClients()
        val controller = controller(activeClients, testScheduler)
        val observer = SessionForegroundServiceActivityObserver(controller)

        controller.observeActiveSessions()
        runCurrent()
        registerLive(activeClients, "alpha")
        runCurrent()
        observer.onActivityStarted(activity())
        runCurrent()
        drainStartedServices()

        // A rotation / dark-mode flip: the activity stops (count 1→0) but is a config-change
        // recreate, NOT a real background.
        observer.onActivityStopped(configChangeActivity())
        runCurrent()
        // The recreated instance starts again (count 0→1).
        observer.onActivityStarted(activity())
        runCurrent()
        // Advance past the debounce — nothing may have been armed.
        testScheduler.advanceTimeBy(debounce() * 4)
        runCurrent()

        assertNull(
            "a configuration-change stop must NOT start the FGS — it is not a real background " +
                "and would thrash the hold on every rotation (#1595)",
            shadow.nextStartedService,
        )
        assertFalse(controller.flowOfSnapshot().value.isHoldingConnection)
    }

    @Test
    fun `background with no live session never starts the FGS (short-background class coverage)`() = runTest {
        // Class coverage (G2): a backgrounded app with NO interactive tmux session must never
        // start the hold — the flow gates on isHoldingConnection.
        val activeClients = ActiveTmuxClients()
        val controller = controller(activeClients, testScheduler)
        val observer = SessionForegroundServiceActivityObserver(controller)

        controller.observeActiveSessions()
        runCurrent()
        observer.onActivityStarted(activity())
        runCurrent()
        observer.onActivityStopped(activity())
        runCurrent()
        testScheduler.advanceTimeBy(debounce() * 2)
        runCurrent()

        assertNull(shadow.nextStartedService)
        assertFalse(controller.flowOfSnapshot().value.isHoldingConnection)
    }

    @Test
    fun `foreground-eligible start holds the transport across the whole grace window (within-grace)`() = runTest {
        // Class coverage (G2): a longer background WITHIN grace. The FGS started at the
        // foreground-eligible background hint; the subsequent ON_STOP stamps the count-down
        // deadline and the hold stays active for the whole grace window (transport held → silent
        // reseed on return instead of a redial). Beyond-grace teardown is unchanged (the last
        // live client unregisters and drops the hold — covered by the controller suite).
        val activeClients = ActiveTmuxClients()
        val controller = controller(activeClients, testScheduler)
        val observer = SessionForegroundServiceActivityObserver(controller)

        controller.observeActiveSessions()
        runCurrent()
        registerLive(activeClients, "alpha")
        runCurrent()
        observer.onActivityStarted(activity())
        runCurrent()
        drainStartedServices()

        observer.onActivityStopped(activity())
        runCurrent()
        testScheduler.advanceTimeBy(debounce() + 1)
        runCurrent()
        assertEquals(SessionConnectionService.ACTION_START, shadow.nextStartedService?.action)

        // ON_STOP arrives ~700ms later and stamps the bounded deadline.
        val deadline = 90_000L
        controller.onAppBackgrounded(disconnectAtWallClockMillis = deadline)
        runCurrent()

        val snapshot = controller.flowOfSnapshot().value
        assertTrueMsg(
            "the transport hold must remain active across the grace window (silent reseed on return)",
            snapshot.isHoldingConnection,
        )
        assertEquals(
            "the ON_STOP stamps the bounded count-down deadline onto the already-held snapshot",
            deadline,
            snapshot.disconnectAtWallClockMillis,
        )
        // No duplicate start intent — the FGS was already up from the foreground-eligible hint.
        assertNull(
            "the ON_STOP must not re-issue a start intent — the FGS is already holding",
            shadow.nextStartedService,
        )
    }

    private fun debounce(): Long = SessionServiceController.PAUSE_START_DEBOUNCE_MILLIS

    private fun assertTrueMsg(message: String, condition: Boolean) {
        org.junit.Assert.assertTrue(message, condition)
    }

    private fun activity(): Activity =
        Robolectric.buildActivity(Activity::class.java).get()

    private fun configChangeActivity(): Activity =
        Robolectric.buildActivity(ConfigChangeActivity::class.java).get()

    /** An activity that reports it is being recreated for a configuration change. */
    class ConfigChangeActivity : Activity() {
        override fun isChangingConfigurations(): Boolean = true
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
            nowMillis = { scheduler.currentTime }
        }
    }

    private fun drainStartedServices() {
        while (shadow.nextStartedService != null) {
            // Drain all queued intents.
        }
    }
}
