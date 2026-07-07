package com.pocketshell.app.portfwd

import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.BackgroundGraceTestOverride
import com.pocketshell.app.MainActivity
import com.pocketshell.app.proof.PreGrantPermissionsRule
import com.pocketshell.app.proof.TerminalTestTimeouts
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.app.testaccess.TestAccessEntryPoint
import com.pocketshell.core.tmux.CommandResponse
import com.pocketshell.core.tmux.TmuxClient
import com.pocketshell.core.tmux.TmuxDisconnectEvent
import com.pocketshell.core.tmux.TmuxOutputBacklogOverflow
import com.pocketshell.core.tmux.protocol.ControlEvent
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader

/**
 * Emulator evidence for issue #487, part 1 (polished in #521): the D21
 * port-forward foreground service posts the always-on "Port forwarding running"
 * ongoing notification the moment a forward goes active — with a dedicated
 * recognizable status-bar icon and "Running in the background" wording — and it
 * clears when no tunnels remain.
 *
 * The maintainer reported this notification being absent or reduced to a tiny
 * silent shade row. This test grants POST_NOTIFICATIONS deterministically (the
 * production re-prompt on forward-start is covered separately by the launch
 * flow) and proves the ongoing notification actually lands in the status bar on
 * a default-importance channel.
 *
 * ## Issue #1202 — one forward notification whose Stop actually stops (this file)
 *
 * The maintainer reported on-device (v0.4.23): while a session is pinned for a
 * port-forward, TWO tray notifications stack — "Port forwarding running"
 * (ForwardingService) and "Port forwarding active" (SessionConnectionService) —
 * and tapping **Stop** on the "active" one only ended the session hold, leaving
 * the tunnels running (a no-op for forwarding). The fix (#1202/#1198, hard-cut
 * D22) SUPPRESSES the session FGS while a port-forward is active so
 * ForwardingService is the SOLE owner of the notification, and its Stop tears
 * down the tunnels. [sessionPinnedForForward_hasExactlyOneForwardNotification_andStopTearsDownTunnels]
 * is the on-device durable regression for that: it reproduces the maintainer's
 * exact reported state (a live session + an active hetzner forward, backgrounded)
 * on the REAL path (App.kt mirrors the active-host count into
 * `SessionServiceController.setPortForwardActive`, exactly as production), then
 * HARD-asserts the real notification tray — exactly ONE app foreground
 * notification for the forward (NOT the old running+active pair) — and fires the
 * Stop action's `PendingIntent`, asserting the forward is actually torn down
 * (active host count → 0) and the notification is gone. It runs on CI (no
 * screenshot, no Docker) so the double-notification / no-op-Stop class cannot
 * silently regress.
 *
 * Determinism mirrors `UpdateAvailableNotificationE2eTest` (#502): grant
 * propagation and `notify()`/`startForeground()` → `activeNotifications` are
 * both async, so we poll both. The status-bar SCREENSHOT capture in
 * [ongoingNotification_appearsWhileForwarding_andClearsWhenStopped] is skipped on
 * CI (`Assume.assumeFalse(isRunningOnCi())`) because the swiftshader emulator is
 * unstable under parallel connected-test load for `screencap`; the
 * `activeNotifications` post path is covered authoritatively by the JVM unit
 * tests (`ForwardingControllerTest`, `SessionForwardingIndicatorViewModelTest`)
 * AND — for the #1202 tray-count + Stop behaviour — by the on-CI regression below,
 * which does NOT self-skip.
 */
@RunWith(AndroidJUnit4::class)
class ForwardingNotificationE2eTest {

    @get:Rule
    val permissions = PreGrantPermissionsRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val notificationManager: NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    private fun controller(): ForwardingController =
        EntryPointAccessors
            .fromApplication(context.applicationContext, TestAccessEntryPoint::class.java)
            .forwardingController()

    private fun entryPoint(): TestAccessEntryPoint =
        EntryPointAccessors
            .fromApplication(context.applicationContext, TestAccessEntryPoint::class.java)

    private var registeredHostId: Long? = null

    // Issue #1202 harness state.
    private var scenario: ActivityScenario<MainActivity>? = null
    private var sessionClientRegistration: ActiveTmuxClients.Registration? = null
    private var sessionLifecycleRegistration: ActiveTmuxClients.LifecycleRegistration? = null

    @Before
    fun grantNotificationPermission() {
        // Clear any stale registration / notification so a leftover can't
        // satisfy the assertion.
        controller().activeHostIdsSnapshot().forEach { controller().unregisterActiveHost(it) }
        notificationManager.cancelAll()

        val pkg = context.packageName
        runShellCommand("pm grant $pkg android.permission.POST_NOTIFICATIONS")

        val deadline = System.currentTimeMillis() + GRANT_PROPAGATION_TIMEOUT_MS
        var permissionGranted = false
        var notificationsEnabled = false
        while (System.currentTimeMillis() < deadline) {
            permissionGranted = context.checkSelfPermission(
                "android.permission.POST_NOTIFICATIONS",
            ) == PackageManager.PERMISSION_GRANTED
            notificationsEnabled = notificationManager.areNotificationsEnabled()
            if (permissionGranted && notificationsEnabled) break
            Thread.sleep(POLL_INTERVAL_MS)
        }

        assertEquals(
            "POST_NOTIFICATIONS must be GRANTED before forwarding starts, else the " +
                "foreground-service notification is silently suppressed",
            PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission("android.permission.POST_NOTIFICATIONS"),
        )
        assertTrue(
            "NotificationManager.areNotificationsEnabled() must be true before the " +
                "forward starts; pm grant updates this AppOps-backed signal async",
            notificationsEnabled,
        )
    }

    @After
    fun cleanup() {
        BackgroundGraceTestOverride.setForTest(null)
        runCatching { scenario?.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED) }
        scenario?.close()
        scenario = null
        val registry = entryPoint().activeTmuxClients()
        sessionClientRegistration?.let { runCatching { registry.unregister(it) } }
        sessionLifecycleRegistration?.let { runCatching { registry.unregisterLifecycleHooks(it) } }
        sessionClientRegistration = null
        sessionLifecycleRegistration = null
        registeredHostId?.let { runCatching { controller().unregisterActiveHost(it) } }
        registeredHostId = null
        // Also sweep any forward the #1202 test left active (Stop should have
        // cleared it, but a failed run must not leak an active host).
        controller().activeHostIdsSnapshot().forEach { runCatching { controller().unregisterActiveHost(it) } }
        notificationManager.cancelAll()
    }

    @Test
    fun ongoingNotification_appearsWhileForwarding_andClearsWhenStopped() {
        // The status-bar SCREENSHOT capture (screencap) is unstable on the CI
        // swiftshader AVD under parallel connected-test load. The tray post path
        // is covered on CI by the #1202 regression below (no screenshot); this
        // screenshot proof stays a dev-box artifact only.
        Assume.assumeFalse(
            "Skipping the on-device status-bar SCREENSHOT capture on CI; the " +
                "swiftshader emulator is too unstable under load for screencap. The " +
                "tray post path is covered on CI by the #1202 regression below.",
            TerminalTestTimeouts.isRunningOnCi(),
        )

        // 1. No forwards → no port-forward notification.
        assertNull(
            "no forwarding notification should exist before a forward starts",
            forwardingNotification(),
        )

        // 2. Register an active host with two tunnels — the D21 foreground
        // service starts and posts the ongoing notification.
        val hostId = 487_001L
        registeredHostId = hostId
        controller().registerActiveHost(hostId = hostId, hostName = "Forward Host")
        controller().updateTunnelCount(hostId, 2)

        // The service first posts an initial "Connecting…" foreground
        // notification (same "Port forwarding running" title) and then, once the
        // ForwardingController snapshot lands, updates it with the live host +
        // forwarded-port detail. Poll until the notification carries the SETTLED
        // detail (the "Forward Host" + port count) rather than the transient
        // "Connecting…" placeholder, so the body/number assertions below see the
        // controller-driven state, not the initial bootstrap.
        val deadline = System.currentTimeMillis() + POST_APPEARS_TIMEOUT_MS
        var posted = forwardingNotification()
        while (
            (posted == null || !notificationShowsForwardDetail(posted)) &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(POLL_INTERVAL_MS)
            posted = forwardingNotification()
        }
        assertNotNull(
            "ongoing 'Port forwarding running' notification with the forwarded-port " +
                "detail did not appear within ${POST_APPEARS_TIMEOUT_MS}ms; active " +
                "titles=" +
                notificationManager.activeNotifications.map {
                    it.notification.extras.getCharSequence("android.title")
                },
            posted,
        )

        // Issue #521: title explicitly says it's running (Recorder feel).
        val title = posted!!.notification.extras
            .getCharSequence("android.title")?.toString().orEmpty()
        assertEquals(
            "notification title must read 'Port forwarding running'",
            "Port forwarding running",
            title,
        )
        // It is ongoing (non-dismissable while active).
        val flags = posted.notification.flags
        assertTrue(
            "forwarding notification must be ongoing (FLAG_ONGOING_EVENT)",
            flags and android.app.Notification.FLAG_ONGOING_EVENT != 0,
        )
        // Issue #487 (reopened): FLAG_NO_CLEAR is what keeps a tray "clear all"
        // sweep from removing the ongoing status the maintainer asked to always
        // see while forwarding is active.
        assertTrue(
            "forwarding notification must be non-clearable (FLAG_NO_CLEAR) so a " +
                "tray clear-all does not sweep it away",
            flags and android.app.Notification.FLAG_NO_CLEAR != 0,
        )
        // Issue #521: dedicated status-bar icon (not the old generic
        // ic_dialog_info), so the status bar shows a recognizable mark.
        assertEquals(
            "forwarding notification must use the dedicated ic_stat_forwarding " +
                "status-bar icon (Recorder-style recognizable glyph)",
            com.pocketshell.app.R.drawable.ic_stat_forwarding,
            posted.notification.smallIcon?.resId,
        )
        val channel = notificationManager.getNotificationChannel(posted.notification.channelId)
        assertEquals(
            "foreground notification should use the upgrade-safe status channel",
            "pocketshell_forwarding_status_v6",
            posted.notification.channelId,
        )
        assertNotNull("foreground notification channel must be registered", channel)
        // Issue #752 (REOPENED): the channel must be >= DEFAULT importance so the
        // ongoing notification leaves the "Silent" group and its persistent ⇄
        // status-bar icon shows near the clock. IMPORTANCE_LOW was the regression
        // (Silent group, no status-bar icon on a real device). This is the
        // on-device half of the durable regression proof.
        assertTrue(
            "foreground notification channel must be >= DEFAULT importance so the " +
                "persistent status-bar icon shows near the clock — LOW lands in the " +
                "Silent group with no icon (issue #752 regression)",
            requireNotNull(channel).importance >= NotificationManager.IMPORTANCE_DEFAULT,
        )
        assertEquals(
            "foreground notification channel must be exactly DEFAULT (not the alerting " +
                "HIGH) — DEFAULT surfaces the icon without a heads-up; quiet comes from " +
                "null sound + setSilent",
            NotificationManager.IMPORTANCE_DEFAULT,
            requireNotNull(channel).importance,
        )
        assertNull(
            "foreground notification channel must be silent — no sound — so it does " +
                "not buzz when a forward starts (kept under #752's DEFAULT importance)",
            channel.sound,
        )
        assertFalse(
            "foreground notification channel must not vibrate on forward-start",
            channel.shouldVibrate(),
        )
        // Issue #487 (reopened): the stale v3 (HIGH/buzzing) channel must be gone
        // so no install keeps the alerting presentation.
        assertNull(
            "the stale v3 (HIGH/buzzing) channel must be deleted on upgrade",
            notificationManager.getNotificationChannel("pocketshell_forwarding_status_v3"),
        )
        // Issue #752: the stale v4 (no-badge) channel must be gone so the
        // badge-enabled channel is created fresh — showBadge is immutable
        // after first creation, so an in-place flip on v4 would be ignored.
        assertNull(
            "the stale v4 (no-badge) channel must be deleted on upgrade",
            notificationManager.getNotificationChannel("pocketshell_forwarding_status_v4"),
        )
        // Issue #752 (reopened): the stale v5 (silent-group LOW, no status-bar
        // icon) channel must be gone so the visible DEFAULT v6 is created fresh —
        // importance is immutable, so an in-place flip on v5 would be ignored.
        assertNull(
            "the stale v5 (silent-group LOW) channel must be deleted on upgrade so v6's " +
                "visible status-bar icon surfaces (issue #752 regression)",
            notificationManager.getNotificationChannel("pocketshell_forwarding_status_v5"),
        )
        // Issue #521: body says it's running in the background + the host +
        // tunnel count (Recorder "Recording now" feel).
        val body = posted.notification.extras
            .getCharSequence("android.text")?.toString().orEmpty()
        assertTrue(
            "notification body should say it's running in the background: '$body'",
            body.contains("Running in the background"),
        )
        assertTrue(
            "notification body should show the host + forwarded port count: '$body'",
            body.contains("Forward Host") && body.contains("2 ports forwarded"),
        )
        // Issue #752: the forwarded PORT COUNT is conveyed as the notification
        // number/badge (Google-Recorder-style circled count near the icon).
        assertEquals(
            "notification number must equal the forwarded port (tunnel) count so " +
                "the status-bar badge shows '2'",
            2,
            posted.notification.number,
        )
        // Issue #752: the channel must allow badging so the number can surface.
        assertTrue(
            "the forwarding channel must allow a badge so the port count can show",
            requireNotNull(channel).canShowBadge(),
        )
        // Tappable → routes to the port-forward panel.
        assertNotNull(
            "forwarding notification must have a tap (contentIntent) to the panel",
            posted.notification.contentIntent,
        )
        captureShade("forwarding-notification-active")

        // 3. Stop forwarding — the notification clears.
        controller().unregisterActiveHost(hostId)
        registeredHostId = null
        val clearDeadline = System.currentTimeMillis() + POST_APPEARS_TIMEOUT_MS
        var stillThere = forwardingNotification()
        while (stillThere != null && System.currentTimeMillis() < clearDeadline) {
            Thread.sleep(POLL_INTERVAL_MS)
            stillThere = forwardingNotification()
        }
        assertNull(
            "forwarding notification must clear when no tunnels remain; active titles=" +
                notificationManager.activeNotifications.map {
                    it.notification.extras.getCharSequence("android.title")
                },
            stillThere,
        )
    }

    /**
     * Issue #1202 (on-device durable regression, G1/G10) — the maintainer's exact
     * reported state, on the REAL path, asserting the REAL notification tray.
     *
     * Reproduces "a session pinned for a hetzner forward": a live tmux session is
     * held AND an active port-forward is running, then the app is backgrounded.
     * The active-host count is mirrored into
     * `SessionServiceController.setPortForwardActive(true)` by the production
     * `App.kt` collector (NOT called by the test) — the same wiring the device
     * uses. So this exercises the true pin path.
     *
     * BASE (revert the two production hunks in `SessionConnectionService.kt` +
     * `SessionServiceController.kt`): backgrounding a pinned session runs the
     * session FGS in parallel with the ForwardingService, posting a SECOND tray
     * notification ("Port forwarding active"), and its Stop only ends the session
     * hold — a no-op for the tunnels. → the tray-count assertion below FAILS RED
     * (two app FGS notifications, the session-channel one present).
     *
     * FIX: while pinned the session FGS is SUPPRESSED, so ForwardingService is the
     * sole owner of exactly ONE notification, and firing its Stop action tears
     * down the tunnels (`stopAllForwarding`) → active host count 0 + notification
     * gone. → GREEN.
     *
     * Load-bearing assertions (must run — no `assumeFalse(isRunningOnCi())`):
     *   - AC1: exactly ONE app foreground notification for the forward while
     *     pinned (the session-channel "Port forwarding active" second notification
     *     is ABSENT).
     *   - AC2: tapping Stop tears the forward down (active host count → 0) AND the
     *     forwarding notification clears; the forward is NOT re-established.
     *
     * No Docker fixture / SSH / port — in-process `ActiveTmuxClients` +
     * `ForwardingController` doubles — so it needs no `tests.yml` service change.
     */
    @Test
    fun sessionPinnedForForward_hasExactlyOneForwardNotification_andStopTearsDownTunnels() {
        // Runs on the per-push emulator-journey job (api-level 34) as well as the
        // dev-box API-35 AVD — the FGS notification/Stop behaviour is identical on
        // both (SPECIAL_USE FGS is API 34+), so no SDK-level gate here.
        notificationManager.cancelAll()

        // A long grace so the backgrounded session hold (on the BASE path) stays
        // up long enough to observe the second notification, and the return to the
        // foreground in @After is comfortably within grace (no teardown surprises).
        BackgroundGraceTestOverride.setForTest(LONG_GRACE_MS)
        scenario = ActivityScenario.launch(MainActivity::class.java)

        val registry = entryPoint().activeTmuxClients()

        // 1. A live tmux session is held (the App wires observeActiveSessions() in
        // Application.onCreate, so this drives the same start/stop signal as a real
        // attached session).
        sessionLifecycleRegistration = registry.registerLifecycleHooks(
            hostId = SESSION_HOST_ID,
            hooks = ActiveTmuxClients.LifecycleHooks(onBackground = {}, onForeground = {}),
        )
        val sessionClient = ConnectedTmuxClient()
        sessionClientRegistration = registry.register(
            hostId = SESSION_HOST_ID,
            hostName = "hetzner",
            hostname = "hetzner.example",
            port = 22,
            username = "alexey",
            keyPath = "/tmp/key",
            client = sessionClient,
        )

        // 2. An active port-forward on the SAME host style (hetzner) with two
        // tunnels → ForwardingService posts "Port forwarding running" AND App.kt's
        // production collector calls setPortForwardActive(true) — the real pin.
        registeredHostId = FORWARD_HOST_ID
        controller().registerActiveHost(hostId = FORWARD_HOST_ID, hostName = "hetzner")
        controller().updateTunnelCount(FORWARD_HOST_ID, 2)
        val forwarding = waitForForwardingNotification()
        assertNotNull(
            "the ForwardingService 'Port forwarding running' notification must post " +
                "when a forward goes active; active titles=" + activeTitles(),
            forwarding,
        )

        // 3. Background the process (ProcessLifecycleOwner ON_STOP) → the session
        // FGS is re-evaluated. On BASE it posts the SECOND "Port forwarding active"
        // notification (the reported double). On the FIX it is suppressed while
        // pinned.
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)

        // AC1 (LOAD-BEARING): across a settle window the forwarding notification is
        // present AND no SECOND app FGS (session-channel) notification ever appears
        // — exactly ONE app foreground notification for the forward. On BASE the
        // session FGS posts "Port forwarding active" on the session channel within
        // ~1-2s of backgrounding, so this fails RED.
        val settleDeadline = System.currentTimeMillis() + PINNED_SETTLE_MS
        while (System.currentTimeMillis() < settleDeadline) {
            assertNull(
                "while a session is pinned for a forward the session FGS must be " +
                    "SUPPRESSED — only ForwardingService's notification may show. A " +
                    "session-channel notification here is the #1202 second (stacked) " +
                    "notification whose Stop is a no-op. active titles=" + activeTitles(),
                sessionChannelNotification(),
            )
            assertNotNull(
                "the ForwardingService notification must stay present while pinned; " +
                    "active titles=" + activeTitles(),
                forwardingNotification(),
            )
            Thread.sleep(POLL_INTERVAL_MS)
        }
        assertEquals(
            "exactly ONE PocketShell foreground notification must exist for the forward " +
                "while pinned (the #1198 dedup) — NOT the old running+active pair. " +
                "active titles=" + activeTitles(),
            1,
            appForegroundNotificationCount(),
        )

        // 4. AC2: tap the Stop action on the (single) forwarding notification and
        // assert the forward is ACTUALLY torn down.
        val stopAction = requireNotNull(
            forwardingNotification()!!.notification.actions?.firstOrNull {
                it.title?.toString() == "Stop"
            },
        ) { "the forwarding notification must carry a 'Stop' action" }
        assertTrue(
            "before Stop, the forward host must be active",
            controller().activeHostIdsSnapshot().contains(FORWARD_HOST_ID),
        )
        stopAction.actionIntent.send()

        // Tunnels torn down: active host count → 0 (stopAllForwarding).
        val stopDeadline = System.currentTimeMillis() + STOP_TEARDOWN_TIMEOUT_MS
        while (
            controller().activeHostIdsSnapshot().isNotEmpty() &&
            System.currentTimeMillis() < stopDeadline
        ) {
            Thread.sleep(POLL_INTERVAL_MS)
        }
        assertTrue(
            "tapping Stop must tear down the forward (stopAllForwarding) — active hosts " +
                "must be empty, not " + controller().activeHostIdsSnapshot(),
            controller().activeHostIdsSnapshot().isEmpty(),
        )
        registeredHostId = null

        // The forwarding notification clears.
        val clearDeadline = System.currentTimeMillis() + STOP_TEARDOWN_TIMEOUT_MS
        while (forwardingNotification() != null && System.currentTimeMillis() < clearDeadline) {
            Thread.sleep(POLL_INTERVAL_MS)
        }
        assertNull(
            "the forwarding notification must clear after Stop tears down the tunnels; " +
                "active titles=" + activeTitles(),
            forwardingNotification(),
        )

        // The forward is NOT re-established (stays down over a settle window).
        val stableDeadline = System.currentTimeMillis() + REESTABLISH_SETTLE_MS
        while (System.currentTimeMillis() < stableDeadline) {
            assertTrue(
                "the forward must NOT re-establish after Stop; active hosts=" +
                    controller().activeHostIdsSnapshot(),
                controller().activeHostIdsSnapshot().isEmpty(),
            )
            Thread.sleep(POLL_INTERVAL_MS)
        }
    }

    private fun forwardingNotification() =
        notificationManager.activeNotifications.firstOrNull {
            it.notification.extras
                .getCharSequence("android.title")
                ?.toString()
                ?.contains("Port forwarding running") == true
        }

    /**
     * The SECOND (stacked) notification from the session FGS. Identified by the
     * session channel id — robust to its title, which was "Port forwarding active"
     * on base (#1159 Part 3) and would be "Session connected" for a plain hold.
     */
    private fun sessionChannelNotification() =
        notificationManager.activeNotifications.firstOrNull {
            it.notification.channelId == SESSION_CHANNEL_ID
        }

    /** Count of THIS app's foreground-service notifications (both FGS channels). */
    private fun appForegroundNotificationCount(): Int =
        notificationManager.activeNotifications.count {
            it.notification.channelId == FORWARDING_CHANNEL_ID ||
                it.notification.channelId == SESSION_CHANNEL_ID
        }

    private fun waitForForwardingNotification(): android.service.notification.StatusBarNotification? {
        val deadline = System.currentTimeMillis() + POST_APPEARS_TIMEOUT_MS
        var posted = forwardingNotification()
        while (posted == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_INTERVAL_MS)
            posted = forwardingNotification()
        }
        return posted
    }

    private fun activeTitles(): List<CharSequence?> =
        notificationManager.activeNotifications.map {
            it.notification.extras.getCharSequence("android.title")
        }

    // True once the notification reflects the controller's settled host +
    // forwarded-port detail, i.e. it has moved past the initial "Connecting…"
    // bootstrap body to "Forward Host · 2 ports forwarded".
    private fun notificationShowsForwardDetail(
        sbn: android.service.notification.StatusBarNotification,
    ): Boolean {
        val body = sbn.notification.extras
            .getCharSequence("android.text")?.toString().orEmpty()
        return body.contains("Forward Host") && body.contains("2 ports forwarded")
    }

    private fun captureShade(name: String) {
        // Issue #752: this artifact is the maintainer's visual proof of the
        // ⇄ icon + forwarded-port count, so it must be readable. Dismiss the
        // clearable clutter (update-available / "Serial console enabled" /
        // sibling-test notifications) first so the ongoing forwarding row is the
        // only PocketShell notification in frame. The forwarding notification is
        // FLAG_NO_CLEAR, so this does NOT remove it.
        runShellCommand("cmd notification clear")
        // Re-poll: the clear above can briefly race the foreground notification;
        // wait for the forwarding row to be present again before capturing.
        val reappearDeadline = System.currentTimeMillis() + 3_000L
        while (forwardingNotification() == null && System.currentTimeMillis() < reappearDeadline) {
            Thread.sleep(POLL_INTERVAL_MS)
        }
        instrumentation.uiAutomation.executeShellCommand("cmd statusbar expand-notifications").close()
        Thread.sleep(2_000)
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(context)
        val artifactsDir = File(mediaRoot, "additional_test_output/forwarding-notification")
        assertTrue(
            "could not create artifact dir ${artifactsDir.absolutePath}",
            artifactsDir.exists() || artifactsDir.mkdirs(),
        )
        val shot = File(artifactsDir, "$name-viewport.png")
        // Capture the actual displayed frame via screencap (the real system
        // notification shade) rather than UiAutomation.takeScreenshot(), which
        // under the swiftshader emulator can grab the instrumented app's own
        // window instead of the system shade.
        val png = runShellCommandBytes("screencap -p")
        assertTrue(
            "screencap returned no bytes for the notification shade",
            png.isNotEmpty(),
        )
        FileOutputStream(shot).use { it.write(png) }
        assertTrue("notification-shade screenshot was not written", shot.exists() && shot.length() > 0)
        println("FORWARDING_NOTIFICATION_SCREENSHOT ${shot.absolutePath}")
        instrumentation.uiAutomation.executeShellCommand("cmd statusbar collapse").close()
    }

    private fun runShellCommandBytes(command: String): ByteArray {
        val pfd = instrumentation.uiAutomation.executeShellCommand(command)
        return FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
            .also { pfd.close() }
    }

    private fun runShellCommand(command: String): String {
        val pfd = instrumentation.uiAutomation.executeShellCommand(command)
        return FileInputStream(pfd.fileDescriptor).use { input ->
            BufferedReader(InputStreamReader(input)).use { it.readText() }
        }.also { pfd.close() }
    }

    /** In-process live [TmuxClient] double so the session FGS drives exactly as a
     * live attached session (mirrors SessionConnectionServiceE2eTest). */
    private class ConnectedTmuxClient : TmuxClient {
        private val disconnectedState = MutableStateFlow(false)
        private val disconnectEventState = MutableStateFlow<TmuxDisconnectEvent?>(null)

        override val events: Flow<ControlEvent> = emptyFlow()
        override val disconnected: StateFlow<Boolean> = disconnectedState.asStateFlow()
        override val disconnectEvent: StateFlow<TmuxDisconnectEvent?> = disconnectEventState.asStateFlow()
        override val outputBacklogOverflows: Flow<TmuxOutputBacklogOverflow> = emptyFlow()

        override suspend fun connect() = Unit

        override suspend fun sendCommand(cmd: String): CommandResponse =
            CommandResponse(number = 0L, output = emptyList(), isError = false)

        override fun outputFor(paneId: String): Flow<ControlEvent.Output> = emptyFlow()
        override fun drainPaneOutputBacklog(paneId: String): Int = 0

        override fun close() {
            disconnectedState.value = true
        }

        override suspend fun setWindowSizeLatest(sessionId: String): CommandResponse =
            CommandResponse(number = 0L, output = emptyList(), isError = false)

        override suspend fun refreshClientSize(cols: Int, rows: Int): CommandResponse =
            CommandResponse(number = 0L, output = emptyList(), isError = false)

        override suspend fun detachCleanly(timeoutMs: Long) {
            disconnectedState.value = true
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS: Long = 100L
        const val GRANT_PROPAGATION_TIMEOUT_MS: Long = 5_000L
        const val POST_APPEARS_TIMEOUT_MS: Long = 10_000L

        // Issue #1202 harness.
        const val SESSION_HOST_ID: Long = 1_202_001L
        const val FORWARD_HOST_ID: Long = 1_202_777L
        const val SESSION_CHANNEL_ID: String = "pocketshell_session_status_v1"
        const val FORWARDING_CHANNEL_ID: String = "pocketshell_forwarding_status_v6"
        // Long enough that a backgrounded session hold stays up (base path) and a
        // foreground return in @After is comfortably within grace.
        const val LONG_GRACE_MS: Long = 120_000L
        // Window over which the session FGS must stay absent while pinned. The base
        // session FGS posts within ~1-2s of backgrounding, so this reliably catches it.
        const val PINNED_SETTLE_MS: Long = 6_000L
        const val STOP_TEARDOWN_TIMEOUT_MS: Long = 10_000L
        const val REESTABLISH_SETTLE_MS: Long = 2_000L
    }
}
