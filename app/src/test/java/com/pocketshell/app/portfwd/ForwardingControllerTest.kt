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
    fun `notification body says running in background and lists host plus forwarded ports`() {
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
        // Issue #752: the forwarded PORT COUNT must be in the body wording.
        assertTrue(
            "body must list the host + forwarded port count: '$body'",
            body.contains("alpha") && body.contains("2 ports forwarded"),
        )
        // BigText carries the same detail for the expanded shade.
        val bigText = notification.extras.getCharSequence("android.bigText")?.toString().orEmpty()
        assertTrue(
            "expanded BigText must keep the live detail: '$bigText'",
            bigText.contains("Running in the background") &&
                bigText.contains("alpha") && bigText.contains("2 ports forwarded"),
        )
    }

    @Test
    fun `notification conveys forwarded port count as the badge number`() {
        // Issue #752: the forwarded PORT COUNT is conveyed as setNumber so the
        // status-bar badge / launcher dot shows the count (Google-Recorder feel).
        val three = buildServiceNotification(
            hostName = "alpha",
            hostCount = 1,
            tunnelCount = 3,
        )
        assertEquals(
            "notification number must equal the forwarded port (tunnel) count",
            3,
            three.number,
        )

        // No forwarded ports yet → no badge number.
        val none = buildServiceNotification(
            hostName = "alpha",
            hostCount = 1,
            tunnelCount = 0,
            restoringHostCount = 1,
        )
        assertEquals(
            "a restoring host with 0 forwarded ports must not draw a badge number",
            0,
            none.number,
        )
    }

    @Test
    fun `notification body collapses multiple hosts and pluralises forwarded ports`() {
        val single = buildServiceNotification(
            hostName = "alpha",
            hostCount = 1,
            tunnelCount = 1,
        ).extras.getCharSequence("android.text")?.toString().orEmpty()
        assertTrue(
            "single forwarded port must not be pluralised: '$single'",
            single.contains("1 port forwarded") && !single.contains("1 ports forwarded"),
        )

        val multi = buildServiceNotification(
            hostName = "alpha",
            hostCount = 3,
            tunnelCount = 5,
        ).extras.getCharSequence("android.text")?.toString().orEmpty()
        assertTrue(
            "multiple hosts must collapse to '+ N more': '$multi'",
            multi.contains("alpha + 2 more") && multi.contains("5 ports forwarded"),
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
            "a restoring host must read 'Restoring…', not '0 ports forwarded': '$body'",
            body.contains("Restoring…") && !body.contains("0 port"),
        )
    }

    @Test
    fun `service registers no raw default-network callback so it cannot force-tear a live transport on stable-wifi jitter`() {
        // Issue #980 (defect 2) — regression proof.
        //
        // The ForwardingService used to register its OWN raw
        // `registerDefaultNetworkCallback` that called forceReconnectNow() on ANY
        // raw onLost→onAvailable pair — including a same-AP band-steer / mesh-node
        // roam / momentary RF re-association the link survives — force-closing a
        // transport the keepalive window said was still alive (the #974
        // stable-wifi self-inflicted drop). It bypassed the same-SSID-reassoc
        // hardening (TerminalNetworkObserver.hasSameNetworkIdentityAs, #875) the
        // rest of the app uses.
        //
        // The fix (hard-cut, D22): the service registers NO network callback at
        // all — network-driven reconnect is owned SOLELY by ForwardingController's
        // hardened TerminalNetworkObserver.changes subscription. This asserts the
        // service holds no raw network path, so a raw stable-wifi blip can never
        // reach forceReconnectNow through the service.
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as android.net.ConnectivityManager
        val shadowCm = Shadows.shadowOf(cm)
        val callbacksBefore = shadowCm.networkCallbacks.toSet()

        // buildService(...).get() returns the service WITHOUT running onCreate
        // (so we don't trigger Hilt injection in this non-Hilt test app); we wire
        // the controller manually, exactly like the sibling notification tests.
        val service = Robolectric.buildService(ForwardingService::class.java).get()
        service.controller = ForwardingController(context)
        // Drive the START path that USED to register the raw default-network
        // callback (the deleted `registerNetworkCallback()` call lived here).
        service.onStartCommand(
            Intent(context, ForwardingService::class.java).setAction(ForwardingService.ACTION_START),
            0,
            0,
        )

        val callbacksAfter = shadowCm.networkCallbacks.toSet()
        assertEquals(
            "ForwardingService must register NO raw default-network callback (#980): " +
                "network-driven reconnect is owned solely by the controller's hardened " +
                "TerminalNetworkObserver.changes signal, so a same-AP reassoc cannot " +
                "force-tear a live transport on stable wifi",
            callbacksBefore,
            callbacksAfter,
        )
    }

    @Test
    fun `a raw stable-wifi reassoc routed through the hardened observer does NOT force-reconnect, but a real handoff does`() {
        // Issue #980 (defect 2) — class coverage: the ONLY network path that can
        // reach forceReconnectNow is the controller's hardened
        // TerminalNetworkObserver.changes subscription, which suppresses the
        // same-SSID/same-handle reassoc (#875) and only emits on a REAL validated
        // default-network handoff. This drives the REAL TerminalNetworkObserver
        // detector pipeline (not a marker) so the suppression is exercised end to
        // end: a same-pure-WIFI-transport reassoc is suppressed (no force), while a
        // genuine WIFI→CELLULAR handoff forces a rebuild.
        val observer = com.pocketshell.app.connectivity.TerminalNetworkObserver(context)
        val controller = ForwardingController(
            appContext = context,
            connector = TestUnavailableConnector,
            portRemappingDao = TestEmptyRemappingDao,
            validatedNetworkChanges = observer.changes,
        )
        idleMainLooper()
        val forceCalls = java.util.concurrent.atomic.AtomicInteger(0)
        controller.registerActiveHost(
            hostId = 1,
            hostName = "alpha",
            forceReconnectHook = { forceCalls.incrementAndGet() },
        )

        // Seed a validated pure-WIFI network as the baseline.
        observer.emitSyntheticSnapshotForTest(
            com.pocketshell.app.connectivity.TerminalNetworkSnapshot.Validated(
                networkHandle = "wifi-1",
                transports = setOf("WIFI"),
            ),
            reason = "baseline",
        )
        idleMainLooper()
        forceCalls.set(0)

        // A same-SSID band-steer / mesh reassoc mints a NEW handle but the same
        // pure-WIFI transport set — the #875 hardening must SUPPRESS it, so no
        // force-reconnect reaches the supervisor (the stable-wifi false handoff).
        observer.emitSyntheticSnapshotForTest(
            com.pocketshell.app.connectivity.TerminalNetworkSnapshot.Validated(
                networkHandle = "wifi-2-reassoc",
                transports = setOf("WIFI"),
            ),
            reason = "same-ssid-reassoc",
        )
        idleMainLooper()
        assertEquals(
            "a same-AP pure-WIFI reassociation must NOT force-tear the forward transport " +
                "(#980/#875 — this is the stable-wifi self-inflicted drop)",
            0,
            forceCalls.get(),
        )

        // A genuine cross-transport handoff (WIFI→CELLULAR) is a real change the
        // forward transport cannot survive — it MUST force a rebuild.
        observer.emitSyntheticSnapshotForTest(
            com.pocketshell.app.connectivity.TerminalNetworkSnapshot.Validated(
                networkHandle = "cell-1",
                transports = setOf("CELLULAR"),
            ),
            reason = "wifi-to-cellular-handoff",
        )
        idleMainLooper()
        assertEquals(
            "a genuine WIFI→CELLULAR handoff must force a reconnect on the forward transport",
            1,
            forceCalls.get(),
        )

        observer.close()
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
    fun `forwarding channel is visible-but-silent — DEFAULT importance shows the status-bar icon yet never buzzes`() {
        val service = Robolectric.buildService(ForwardingService::class.java).get()
        service.createNotificationChannel()
        val notification = service.buildNotification(
            hostName = "alpha",
            hostCount = 1,
            tunnelCount = 2,
        )
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(notification.channelId)

        // Issue #752 channel id bump (importance is immutable, so the silent-group
        // LOW `_v5` had to be replaced by the DEFAULT `_v6`).
        assertEquals("pocketshell_forwarding_status_v6", notification.channelId)
        assertNotNull("foreground-service notification channel must be registered", channel)
        val forwardingChannel = requireNotNull(channel)
        // Issue #752 (REOPENED): the regression was IMPORTANCE_LOW — a below-DEFAULT
        // channel is filed under "Silent" on a real device and its persistent
        // status-bar icon near the clock is SUPPRESSED (the maintainer had 18 ports
        // forwarding and saw no ⇄ icon). The channel MUST be at least DEFAULT so the
        // ongoing notification leaves the "Silent" group and the icon shows near the
        // clock. This assertion FAILS on the regressed LOW config (red→green) — it
        // is the durable test that would have caught #752.
        assertTrue(
            "channel must be >= IMPORTANCE_DEFAULT so the persistent status-bar icon " +
                "shows near the clock — IMPORTANCE_LOW lands in the Silent group with " +
                "no icon (issue #752 regression)",
            forwardingChannel.importance >= NotificationManager.IMPORTANCE_DEFAULT,
        )
        assertEquals(
            "channel must be exactly DEFAULT (not HIGH) — DEFAULT surfaces the icon " +
                "without a heads-up; the quiet feel comes from null sound + setSilent",
            NotificationManager.IMPORTANCE_DEFAULT,
            forwardingChannel.importance,
        )
        assertEquals(
            "pre-O notification priority should match the DEFAULT-importance channel " +
                "so the status-bar icon surfaces (PRIORITY_LOW kept it out of the bar)",
            NotificationCompat.PRIORITY_DEFAULT,
            @Suppress("DEPRECATION")
            notification.priority,
        )
        // Issue #487/#752: visible but QUIET — DEFAULT importance must still be
        // forced silent so it never buzzes or pops a heads-up on forward-start.
        assertNull(
            "forwarding channel must be silent — no sound — so it does not buzz when " +
                "a forward starts (issue #487; kept under #752's DEFAULT importance)",
            forwardingChannel.sound,
        )
        assertFalse(
            "forwarding channel must not vibrate so it does not buzz on forward-start",
            forwardingChannel.shouldVibrate(),
        )
        // Issue #752: the notification itself is silenced (setSilent(true)) so the
        // DEFAULT channel never produces a sound — visible-but-quiet contract.
        assertTrue(
            "notification must be silent (no sound/vibration) even on the DEFAULT " +
                "channel so it never buzzes — setSilent(true) (issue #752)",
            notification.flags and android.app.Notification.FLAG_ONLY_ALERT_ONCE != 0 ||
                notification.sound == null,
        )
        // Hard-cut (D22): every stale channel id must be deleted so no install
        // keeps the silent-group LOW (`_v5` — the #752 regression), the buzzing
        // HIGH (`_v3`), the swipe-away DEFAULT (`_v2`), or the no-badge (`_v4`)
        // presentation. Importance/badge are immutable, so the fresh `_v6` only
        // takes effect once the old ids are dropped.
        assertNull(
            "the stale v5 (silent-group LOW, no status-bar icon) channel must be " +
                "removed so the visible DEFAULT v6 is created fresh on update (issue #752)",
            manager.getNotificationChannel("pocketshell_forwarding_status_v5"),
        )
        assertNull(
            "the stale v4 (no-badge) channel must be removed so the badge-enabled v6 " +
                "channel is created fresh on update (issue #752)",
            manager.getNotificationChannel("pocketshell_forwarding_status_v4"),
        )
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
