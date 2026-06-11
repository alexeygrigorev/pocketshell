package com.pocketshell.app.portfwd

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.core.app.NotificationCompat
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.portfwd.service.ForwardingService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowApplication

/**
 * Unit tests for [ForwardingController] — the singleton that bridges
 * [PortForwardPanelViewModel] and [ForwardingService] (issue #203
 * expanded scope, foreground-service slice).
 *
 * The controller is responsible for starting / stopping the service
 * on the 0 → 1 / 1 → 0 active-host transitions and for fanning out
 * the network-recovery reconnect hint to every registered host's
 * supervisor.
 *
 * We assert the intent fan-out via [ShadowApplication.getNextStartedService]
 * which records every `startService` / `startForegroundService` call
 * made through the Robolectric application context.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class ForwardingControllerTest {

    private lateinit var context: Context
    private lateinit var shadow: ShadowApplication

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shadow = Shadows.shadowOf(context.applicationContext as android.app.Application)
        // Drain anything Robolectric queued from prior tests.
        drainStartedServices()
    }

    @Test
    fun `first registerActiveHost starts the foreground service`() {
        val controller = ForwardingController(context)

        controller.registerActiveHost(hostId = 1, hostName = "alpha")

        val started = shadow.nextStartedService
        assertNotNull("ForwardingController must start ForwardingService on first registration", started)
        assertEquals(
            ForwardingService::class.java.name,
            started.component?.className,
        )
        assertEquals(ForwardingService.ACTION_START, started.action)
        assertEquals(1, controller.flowOfActiveHostCount().value)
        assertEquals("alpha", controller.flowOfPrimaryHostName().value)
    }

    @Test
    fun `second registerActiveHost does not start the service again`() {
        val controller = ForwardingController(context)

        controller.registerActiveHost(hostId = 1, hostName = "alpha")
        drainStartedServices()
        controller.registerActiveHost(hostId = 2, hostName = "beta")

        // No new service intent — only the host count + sum change.
        assertNull(
            "second registration must not re-issue startForegroundService",
            shadow.nextStartedService,
        )
        assertEquals(2, controller.flowOfActiveHostCount().value)
        // primaryHostName stays on the first registered host so the
        // notification text is stable.
        assertEquals("alpha", controller.flowOfPrimaryHostName().value)
    }

    @Test
    fun `unregisterActiveHost stops service when last host departs`() {
        val controller = ForwardingController(context)

        controller.registerActiveHost(hostId = 1, hostName = "alpha")
        drainStartedServices()

        controller.unregisterActiveHost(hostId = 1)

        val stopped = shadow.nextStartedService
        assertNotNull("last unregister must request service stop", stopped)
        assertEquals(ForwardingService.ACTION_STOP, stopped.action)
        assertEquals(0, controller.flowOfActiveHostCount().value)
        assertEquals(0, controller.flowOfTotalTunnelCount().value)
        assertEquals("", controller.flowOfPrimaryHostName().value)
    }

    @Test
    fun `unregisterActiveHost keeps service running while other hosts remain`() {
        val controller = ForwardingController(context)

        controller.registerActiveHost(hostId = 1, hostName = "alpha")
        controller.registerActiveHost(hostId = 2, hostName = "beta")
        drainStartedServices()

        controller.unregisterActiveHost(hostId = 1)

        assertNull(
            "unregistering one of two hosts must not stop the service",
            shadow.nextStartedService,
        )
        assertEquals(1, controller.flowOfActiveHostCount().value)
        // Primary host name should follow the remaining registration.
        assertEquals("beta", controller.flowOfPrimaryHostName().value)
    }

    @Test
    fun `unregisterActiveHost is idempotent on an unknown id`() {
        val controller = ForwardingController(context)

        // No registrations. Unregistering nothing must not crash and
        // must not start/stop the service.
        controller.unregisterActiveHost(hostId = 99)

        assertNull(shadow.nextStartedService)
        assertEquals(0, controller.flowOfActiveHostCount().value)
    }

    @Test
    fun `foreground service start and stop rejections do not crash callers`() {
        val rejectingContext = RejectingServiceContext(context)

        ForwardingService.start(rejectingContext)
        ForwardingService.stop(rejectingContext)
    }

    @Test
    fun `updateTunnelCount surfaces sum across registered hosts`() {
        val controller = ForwardingController(context)

        controller.registerActiveHost(hostId = 1, hostName = "alpha")
        controller.registerActiveHost(hostId = 2, hostName = "beta")
        controller.updateTunnelCount(hostId = 1, count = 3)
        controller.updateTunnelCount(hostId = 2, count = 5)

        assertEquals(8, controller.flowOfTotalTunnelCount().value)

        controller.updateTunnelCount(hostId = 1, count = 0)
        assertEquals(5, controller.flowOfTotalTunnelCount().value)
    }

    @Test
    fun `host snapshots expose per-host active state and tunnel counts`() {
        val controller = ForwardingController(context)

        controller.registerActiveHost(hostId = 1, hostName = "alpha")
        controller.registerActiveHost(hostId = 2, hostName = "beta")
        controller.updateTunnelCount(hostId = 1, count = 2)

        assertEquals(
            mapOf(
                1L to ForwardingHostSnapshot(active = true, tunnelCount = 2),
                2L to ForwardingHostSnapshot(active = true, tunnelCount = 0),
            ),
            controller.flowOfHostSnapshots().value,
        )

        controller.unregisterActiveHost(hostId = 1)

        assertEquals(
            mapOf(2L to ForwardingHostSnapshot(active = true, tunnelCount = 0)),
            controller.flowOfHostSnapshots().value,
        )
    }

    @Test
    fun `host snapshots expose active remote ports and local mapping when known`() {
        val controller = ForwardingController(context)

        controller.registerActiveHost(hostId = 1, hostName = "alpha")
        // Issue #488: remote -> local mapping; 3000 maps straight through,
        // 8080 is remapped to a different local port.
        controller.updateActiveTunnels(hostId = 1, tunnels = mapOf(8080 to 18080, 3000 to 3000))

        assertEquals(
            ForwardingHostSnapshot(
                active = true,
                tunnelCount = 2,
                activeRemotePorts = setOf(3000, 8080),
                forwardedPortMap = mapOf(3000 to 3000, 8080 to 18080),
            ),
            controller.flowOfHostSnapshots().value[1L],
        )
        assertEquals(2, controller.flowOfTotalTunnelCount().value)
    }

    @Test
    fun `reconnectNow fans out hints to every registered host hook`() {
        val controller = ForwardingController(context)
        val calls1 = java.util.concurrent.atomic.AtomicInteger(0)
        val calls2 = java.util.concurrent.atomic.AtomicInteger(0)

        controller.registerActiveHost(
            hostId = 1,
            hostName = "alpha",
            reconnectHook = { calls1.incrementAndGet() },
        )
        controller.registerActiveHost(
            hostId = 2,
            hostName = "beta",
            reconnectHook = { calls2.incrementAndGet() },
        )

        controller.reconnectNow()

        assertEquals(1, calls1.get())
        assertEquals(1, calls2.get())

        // Unregister one; reconnectNow now only fires the remaining hook.
        controller.unregisterActiveHost(hostId = 1)
        controller.reconnectNow()
        assertEquals(1, calls1.get())
        assertEquals(2, calls2.get())
    }

    @Test
    fun `forceReconnectNow uses force hooks without disturbing normal reconnect hook`() {
        val controller = ForwardingController(context)
        val normalCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val forceCalls = java.util.concurrent.atomic.AtomicInteger(0)

        controller.registerActiveHost(
            hostId = 1,
            hostName = "alpha",
            reconnectHook = { normalCalls.incrementAndGet() },
            forceReconnectHook = { forceCalls.incrementAndGet() },
        )

        controller.reconnectNow()
        assertEquals(1, normalCalls.get())
        assertEquals(0, forceCalls.get())

        controller.forceReconnectNow()
        assertEquals(1, normalCalls.get())
        assertEquals(1, forceCalls.get())
    }

    @Test
    fun `validated network change forces a reconnect on every active host`() {
        // Issue #329 / #439: the port-forward sessions are independent
        // transports from the terminal/tmux flow, so a wifi↔cellular
        // handoff (which can leave sshj reporting "connected" while the
        // forwards are dead) must reach the supervisor force-reconnect
        // hook. The controller subscribes to the validated-default-network
        // change signal and forces a rebuild on each emission.
        val networkChanges = MutableSharedFlow<Any?>(extraBufferCapacity = 16)
        val controller = ForwardingController(
            appContext = context,
            connector = TestUnavailableConnector,
            portRemappingDao = TestEmptyRemappingDao,
            validatedNetworkChanges = networkChanges,
        )
        idleMainLooper()
        val normalCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val forceCalls = java.util.concurrent.atomic.AtomicInteger(0)
        controller.registerActiveHost(
            hostId = 1,
            hostName = "alpha",
            reconnectHook = { normalCalls.incrementAndGet() },
            forceReconnectHook = { forceCalls.incrementAndGet() },
        )

        // A validated-network change forces a reconnect, not a no-churn one.
        assertTrue(networkChanges.tryEmit(NetworkChangeMarker))
        idleMainLooper()
        assertEquals("validated-network change must force a reconnect", 1, forceCalls.get())
        assertEquals("validated-network change must not use the no-churn hook", 0, normalCalls.get())

        // A second handoff forces again — the subscription is durable.
        assertTrue(networkChanges.tryEmit(NetworkChangeMarker))
        idleMainLooper()
        assertEquals(2, forceCalls.get())
        assertEquals(0, normalCalls.get())
    }

    @Test
    fun `validated network change with no active hosts is a no-op`() {
        // Before any host is auto-forwarding, a network change must not
        // crash or start the service — the fan-out is over an empty set.
        val networkChanges = MutableSharedFlow<Any?>(extraBufferCapacity = 16)
        val controller = ForwardingController(
            appContext = context,
            connector = TestUnavailableConnector,
            portRemappingDao = TestEmptyRemappingDao,
            validatedNetworkChanges = networkChanges,
        )
        idleMainLooper()
        drainStartedServices()

        assertTrue(networkChanges.tryEmit(NetworkChangeMarker))
        idleMainLooper()

        assertEquals(0, controller.flowOfActiveHostCount().value)
        assertNull("no service should start on a network change with no active hosts", shadow.nextStartedService)
    }

    @Test
    fun `registerActiveHost twice with same id updates fields and does not duplicate`() {
        val controller = ForwardingController(context)
        controller.registerActiveHost(hostId = 1, hostName = "alpha")
        controller.updateActiveTunnels(1, mapOf(3000 to 9000, 8080 to 18080))
        controller.registerActiveHost(hostId = 1, hostName = "alpha-renamed")

        assertEquals(1, controller.flowOfActiveHostCount().value)
        // Renamed host's name reflected in the primary name.
        assertEquals("alpha-renamed", controller.flowOfPrimaryHostName().value)
        // Re-registration must preserve the cached tunnel count (we
        // don't want a panel re-build to reset the count to zero between
        // the register and the first tunnel snapshot).
        assertEquals(2, controller.flowOfTotalTunnelCount().value)
        assertEquals(
            mapOf(3000 to 9000, 8080 to 18080),
            controller.flowOfHostSnapshots().value.getValue(1L).forwardedPortMap,
        )
    }

    @Test
    fun `setHostRestoring surfaces a transient restoring count and flag`() {
        val controller = ForwardingController(context)
        controller.registerActiveHost(hostId = 1, hostName = "alpha")
        controller.updateTunnelCount(1, 2)

        assertEquals(0, controller.flowOfRestoringHostCount().value)

        // Transport drops -> mark restoring. The host stays registered
        // (count stays 1) so the indicator/notification reads "restoring"
        // rather than "removed".
        controller.setHostRestoring(hostId = 1, restoring = true)
        assertEquals(1, controller.flowOfRestoringHostCount().value)
        assertEquals(1, controller.flowOfActiveHostCount().value)
        assertTrue(controller.flowOfHostSnapshots().value.getValue(1L).restoring)

        // Reconnected -> clear restoring.
        controller.setHostRestoring(hostId = 1, restoring = false)
        assertEquals(0, controller.flowOfRestoringHostCount().value)
        assertTrue(!controller.flowOfHostSnapshots().value.getValue(1L).restoring)
    }

    @Test
    fun `setHostRestoring is a no-op for an unregistered host`() {
        val controller = ForwardingController(context)
        controller.setHostRestoring(hostId = 99, restoring = true)
        assertEquals(0, controller.flowOfRestoringHostCount().value)
    }

    @Test
    fun `re-registering a restoring host preserves the restoring flag`() {
        val controller = ForwardingController(context)
        controller.registerActiveHost(hostId = 1, hostName = "alpha")
        controller.setHostRestoring(hostId = 1, restoring = true)

        // Panel rebuild re-registers the same host with a new hook.
        controller.registerActiveHost(hostId = 1, hostName = "alpha", reconnectHook = {})

        assertEquals(1, controller.flowOfRestoringHostCount().value)
        assertTrue(controller.flowOfHostSnapshots().value.getValue(1L).restoring)
    }

    // --- Issue #521: the D21 foreground-service notification copy + icon ----
    //
    // The maintainer wants the ongoing notification to read like Google
    // Recorder's persistent "running now" status: a recognizable status-bar
    // icon and explicit "running in the background" wording. The notification
    // is built by ForwardingService.buildNotification, which only needs the
    // service Context (not the Hilt-injected controller), so we can build the
    // service via Robolectric and assert the notification directly here on the
    // JVM. These are the authoritative CI assertions (the connected
    // ForwardingNotificationE2eTest is Assume-gated off CI for swiftshader
    // status-bar flakiness).

    private fun buildServiceNotification(
        hostName: String,
        hostCount: Int,
        tunnelCount: Int,
        restoringHostCount: Int = 0,
    ): android.app.Notification {
        // .get() (not .create()) so onCreate/Hilt-injection never runs;
        // buildNotification only touches the Context.
        val service = Robolectric.buildService(ForwardingService::class.java).get()
        return service.buildNotification(
            hostName = hostName,
            hostCount = hostCount,
            tunnelCount = tunnelCount,
            restoringHostCount = restoringHostCount,
        )
    }

    @Test
    fun `notification title says forwarding is running, not just active`() {
        val notification = buildServiceNotification(
            hostName = "alpha",
            hostCount = 1,
            tunnelCount = 2,
        )

        assertEquals(
            "Port forwarding running",
            notification.extras.getCharSequence("android.title")?.toString(),
        )
    }

    @Test
    fun `notification body says running in background and lists host plus tunnels`() {
        val notification = buildServiceNotification(
            hostName = "alpha",
            hostCount = 1,
            tunnelCount = 2,
        )

        val body = notification.extras.getCharSequence("android.text")?.toString().orEmpty()
        assertTrue(
            "body must explicitly say it's running in the background: '$body'",
            body.contains("Running in the background"),
        )
        assertTrue(
            "body must list the host + tunnel count: '$body'",
            body.contains("alpha") && body.contains("2 tunnels"),
        )
        // BigText carries the same detail for the expanded shade.
        val bigText = notification.extras.getCharSequence("android.bigText")?.toString().orEmpty()
        assertTrue(
            "expanded BigText must keep the live detail: '$bigText'",
            bigText.contains("Running in the background") &&
                bigText.contains("alpha") && bigText.contains("2 tunnels"),
        )
    }

    @Test
    fun `notification body collapses multiple hosts and pluralises tunnels`() {
        val single = buildServiceNotification(
            hostName = "alpha",
            hostCount = 1,
            tunnelCount = 1,
        ).extras.getCharSequence("android.text")?.toString().orEmpty()
        assertTrue(
            "single tunnel must not be pluralised: '$single'",
            single.contains("1 tunnel") && !single.contains("1 tunnels"),
        )

        val multi = buildServiceNotification(
            hostName = "alpha",
            hostCount = 3,
            tunnelCount = 5,
        ).extras.getCharSequence("android.text")?.toString().orEmpty()
        assertTrue(
            "multiple hosts must collapse to '+ N more': '$multi'",
            multi.contains("alpha + 2 more") && multi.contains("5 tunnels"),
        )
    }

    @Test
    fun `notification body shows restoring while transport is down`() {
        val body = buildServiceNotification(
            hostName = "alpha",
            hostCount = 1,
            tunnelCount = 0,
            restoringHostCount = 1,
        ).extras.getCharSequence("android.text")?.toString().orEmpty()
        assertTrue(
            "a restoring host must read 'Restoring…', not '0 tunnels': '$body'",
            body.contains("Restoring…") && !body.contains("0 tunnel"),
        )
    }

    @Test
    fun `service forces reconnect only after observed default network loss`() {
        val controller = ForwardingController(context)
        val normalCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val forceCalls = java.util.concurrent.atomic.AtomicInteger(0)
        controller.registerActiveHost(
            hostId = 1,
            hostName = "alpha",
            reconnectHook = { normalCalls.incrementAndGet() },
            forceReconnectHook = { forceCalls.incrementAndGet() },
        )
        val service = Robolectric.buildService(ForwardingService::class.java).get()
        service.controller = controller

        service.handleDefaultNetworkAvailable()
        assertEquals(
            "initial onAvailable during callback registration must not churn a healthy tunnel",
            1,
            normalCalls.get(),
        )
        assertEquals(0, forceCalls.get())

        service.handleDefaultNetworkLost()
        service.handleDefaultNetworkAvailable()
        assertEquals(1, normalCalls.get())
        assertEquals(
            "onAvailable after an observed loss must force rebuild stale connected transports",
            1,
            forceCalls.get(),
        )
    }

    @Test
    fun `notification uses the dedicated status-bar icon and stays ongoing`() {
        val notification = buildServiceNotification(
            hostName = "alpha",
            hostCount = 1,
            tunnelCount = 2,
        )

        assertEquals(
            "must use the dedicated ic_stat_forwarding glyph, not generic ic_dialog_info",
            com.pocketshell.app.R.drawable.ic_stat_forwarding,
            notification.smallIcon?.resId,
        )
        assertTrue(
            "notification must be ongoing (non-swipeable while forwarding)",
            notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0,
        )
        assertTrue(
            "notification must be non-clearable (FLAG_NO_CLEAR) so a tray clear-all " +
                "does not sweep the ongoing forwarding status away (issue #487 reopen)",
            notification.flags and android.app.Notification.FLAG_NO_CLEAR != 0,
        )
        assertNotNull(
            "notification must be tappable to open the panel",
            notification.contentIntent,
        )
        assertTrue(
            "notification must keep a Stop action",
            notification.actions?.any { it.title?.toString() == "Stop" } == true,
        )
    }

    @Test
    fun `notification uses a silent low-importance status channel that does not buzz`() {
        val service = Robolectric.buildService(ForwardingService::class.java).get()
        service.createNotificationChannel()
        val notification = service.buildNotification(
            hostName = "alpha",
            hostCount = 1,
            tunnelCount = 2,
        )
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(notification.channelId)

        assertEquals("pocketshell_forwarding_status_v4", notification.channelId)
        assertNotNull("foreground-service notification channel must be registered", channel)
        val forwardingChannel = requireNotNull(channel)
        // Issue #487 (reopened): the maintainer asked for a QUIET persistent
        // status (Recorder/Spotify-style), "not an alert that buzzes". The
        // channel must be LOW importance (silent, no heads-up, no buzz) — NOT
        // HIGH. Sweep-resistance comes from the NO_CLEAR/ongoing flags (asserted
        // separately), not from importance.
        assertEquals(
            "channel must be LOW importance so the ongoing status is silent — no " +
                "heads-up, no buzz — like Recorder/Spotify (issue #487 reopen)",
            NotificationManager.IMPORTANCE_LOW,
            forwardingChannel.importance,
        )
        assertEquals(
            "pre-O notification priority should match the low-importance silent channel",
            NotificationCompat.PRIORITY_LOW,
            @Suppress("DEPRECATION")
            notification.priority,
        )
        assertNull(
            "forwarding channel must be silent — no sound — so it does not buzz when " +
                "a forward starts (issue #487 reopen)",
            forwardingChannel.sound,
        )
        assertFalse(
            "forwarding channel must not vibrate so it does not buzz on forward-start",
            forwardingChannel.shouldVibrate(),
        )
        // Issue #487 (reopened): the stale higher/lower-importance channels must
        // be deleted so no install keeps the buzzing HIGH (_v3) or swipe-away
        // DEFAULT (_v2) presentation.
        assertNull(
            "the stale v3 (HIGH/buzzing) channel must be removed so it can't linger",
            manager.getNotificationChannel("pocketshell_forwarding_status_v3"),
        )
        assertNull(
            "the legacy v2 channel must be removed so it can't linger at DEFAULT importance",
            manager.getNotificationChannel("pocketshell_forwarding_status_v2"),
        )
        runCatching {
            android.app.Notification::class.java
                .getMethod("getForegroundServiceBehavior")
                .invoke(notification) as Int
        }.onSuccess { behavior ->
            assertEquals(
                "FGS notification should request immediate status-area display where supported",
                NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE,
                behavior,
            )
        }
    }

    private fun drainStartedServices() {
        while (shadow.nextStartedService != null) {
            // Iterate to clear the queue.
        }
    }

    /**
     * Run any work the controller's network-change collector posted to the
     * main looper. The test constructor collects on `Dispatchers.Main.immediate`,
     * so emissions are delivered through Robolectric's main looper.
     */
    private fun idleMainLooper() {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    /** Marker payload for the validated-network-change signal in tests. */
    private object NetworkChangeMarker

    private object TestUnavailableConnector : PortForwardConnector {
        override suspend fun connect(
            host: com.pocketshell.core.storage.entity.HostEntity,
            keyPath: String,
            passphrase: CharArray?,
        ): Result<com.pocketshell.core.ssh.SshSession> =
            Result.failure(IllegalStateException("connector unavailable in test"))
    }

    private object TestEmptyRemappingDao :
        com.pocketshell.core.storage.dao.PortRemappingDao {
        override fun getByHostId(hostId: Long) =
            kotlinx.coroutines.flow.flowOf(
                emptyList<com.pocketshell.core.storage.entity.PortRemappingEntity>(),
            )

        override suspend fun getByRemotePort(
            hostId: Long,
            remotePort: Int,
        ): com.pocketshell.core.storage.entity.PortRemappingEntity? = null

        override suspend fun insert(
            remapping: com.pocketshell.core.storage.entity.PortRemappingEntity,
        ): Long = remapping.id

        override suspend fun deleteByRemotePort(hostId: Long, remotePort: Int) = Unit

        override suspend fun deleteByHostId(hostId: Long) = Unit
    }

    private class RejectingServiceContext(base: Context) : ContextWrapper(base) {
        override fun startForegroundService(service: Intent?): ComponentName {
            throw IllegalStateException("foreground services are not allowed now")
        }

        override fun startService(service: Intent?): ComponentName {
            throw IllegalStateException("services are not allowed now")
        }
    }

    @Suppress("unused")
    private fun assertActionIs(intent: Intent?, expected: String) {
        assertEquals(expected, intent?.action)
    }

    @Suppress("unused")
    private fun debugSize() {
        assertTrue("placeholder", true)
    }
}
