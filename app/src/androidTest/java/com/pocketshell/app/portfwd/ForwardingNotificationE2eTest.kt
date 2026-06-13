package com.pocketshell.app.portfwd

import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.proof.TerminalTestTimeouts
import com.pocketshell.app.testaccess.TestAccessEntryPoint
import dagger.hilt.android.EntryPointAccessors
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
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
 * Determinism mirrors `UpdateAvailableNotificationE2eTest` (#502): grant
 * propagation and `notify()`/`startForeground()` → `activeNotifications` are
 * both async, so we poll both. On CI the connected status-bar assertion is
 * skipped (`Assume.assumeFalse(isRunningOnCi())`) because the swiftshader
 * emulator is unstable under parallel connected-test load; the post path's
 * logic is covered authoritatively by the JVM unit tests
 * (`ForwardingControllerTest`, `SessionForwardingIndicatorViewModelTest`).
 */
@RunWith(AndroidJUnit4::class)
class ForwardingNotificationE2eTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val notificationManager: NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    private fun controller(): ForwardingController =
        EntryPointAccessors
            .fromApplication(context.applicationContext, TestAccessEntryPoint::class.java)
            .forwardingController()

    private var registeredHostId: Long? = null

    @Before
    fun grantNotificationPermission() {
        Assume.assumeFalse(
            "Skipping the on-device status-bar assertion on CI; the post path " +
                "is covered by the JVM unit tests and the swiftshader emulator " +
                "is too unstable under load for this status-bar capture.",
            TerminalTestTimeouts.isRunningOnCi(),
        )

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
        registeredHostId?.let { runCatching { controller().unregisterActiveHost(it) } }
        registeredHostId = null
        notificationManager.cancelAll()
    }

    @Test
    fun ongoingNotification_appearsWhileForwarding_andClearsWhenStopped() {
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
            "pocketshell_forwarding_status_v5",
            posted.notification.channelId,
        )
        assertNotNull("foreground notification channel must be registered", channel)
        // Issue #487 (reopened): LOW importance so the ongoing status is SILENT
        // (no heads-up, no buzz) — the Recorder/Spotify quiet-status feel the
        // maintainer asked for. Sweep-resistance comes from FLAG_NO_CLEAR
        // (asserted above), NOT from importance.
        assertEquals(
            "foreground notification channel must be LOW importance so it is silent " +
                "(no heads-up, no buzz) like Recorder/Spotify",
            NotificationManager.IMPORTANCE_LOW,
            requireNotNull(channel).importance,
        )
        assertNull(
            "foreground notification channel must be silent — no sound — so it does " +
                "not buzz when a forward starts",
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
        // badge-enabled v5 channel is created fresh — showBadge is immutable
        // after first creation, so an in-place flip on v4 would be ignored.
        assertNull(
            "the stale v4 (no-badge) channel must be deleted on upgrade so v5's badge surfaces",
            notificationManager.getNotificationChannel("pocketshell_forwarding_status_v4"),
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

    private fun forwardingNotification() =
        notificationManager.activeNotifications.firstOrNull {
            it.notification.extras
                .getCharSequence("android.title")
                ?.toString()
                ?.contains("Port forwarding running") == true
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

    private companion object {
        const val POLL_INTERVAL_MS: Long = 100L
        const val GRANT_PROPAGATION_TIMEOUT_MS: Long = 5_000L
        const val POST_APPEARS_TIMEOUT_MS: Long = 10_000L
    }
}
