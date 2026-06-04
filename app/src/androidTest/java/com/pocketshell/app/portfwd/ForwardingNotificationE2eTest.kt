package com.pocketshell.app.portfwd

import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.proof.TerminalTestTimeouts
import com.pocketshell.app.testaccess.TestAccessEntryPoint
import dagger.hilt.android.EntryPointAccessors
import org.junit.After
import org.junit.Assert.assertEquals
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
 * Emulator evidence for issue #487, part 1: the D21 port-forward foreground
 * service posts the always-on "Port forwarding active" ongoing notification
 * the moment a forward goes active, and it clears when no tunnels remain.
 *
 * The maintainer reported never seeing this notification. The root cause was
 * that POST_NOTIFICATIONS (Android 13+) was only requested once at first
 * launch; a user who dismissed it (or first forwards much later) never got the
 * grant, so the foreground-service notification was silently suppressed. This
 * test grants the permission deterministically (the production re-prompt on
 * forward-start is covered separately by the launch flow) and proves the
 * ongoing notification actually lands in the status bar.
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

        val deadline = System.currentTimeMillis() + POST_APPEARS_TIMEOUT_MS
        var posted = forwardingNotification()
        while (posted == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_INTERVAL_MS)
            posted = forwardingNotification()
        }
        assertNotNull(
            "ongoing 'Port forwarding active' notification did not appear within " +
                "${POST_APPEARS_TIMEOUT_MS}ms; active titles=" +
                notificationManager.activeNotifications.map {
                    it.notification.extras.getCharSequence("android.title")
                },
            posted,
        )

        // It is ongoing (non-dismissable while active).
        val flags = posted!!.notification.flags
        assertTrue(
            "forwarding notification must be ongoing (FLAG_ONGOING_EVENT)",
            flags and android.app.Notification.FLAG_ONGOING_EVENT != 0,
        )
        // Body reflects the host + tunnel count (Spotify/Recorder feel).
        val body = posted.notification.extras
            .getCharSequence("android.text")?.toString().orEmpty()
        assertTrue(
            "notification body should show the host + tunnel count: '$body'",
            body.contains("Forward Host") && body.contains("2 tunnels"),
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
                ?.contains("Port forwarding active") == true
        }

    private fun captureShade(name: String) {
        instrumentation.uiAutomation.executeShellCommand("cmd statusbar expand-notifications").close()
        Thread.sleep(1_500)
        val mediaRoot = context.externalMediaDirs.firstOrNull { it != null }
            ?: context.getExternalFilesDir(null)
        val artifactsDir = File(mediaRoot, "additional_test_output/forwarding-notification")
        assertTrue(
            "could not create artifact dir ${artifactsDir.absolutePath}",
            artifactsDir.exists() || artifactsDir.mkdirs(),
        )
        val shot = File(artifactsDir, "$name-viewport.png")
        val bitmap: Bitmap? = instrumentation.uiAutomation.takeScreenshot()
        assertNotNull("could not capture a screenshot of the notification shade", bitmap)
        FileOutputStream(shot).use { bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("notification-shade screenshot was not written", shot.exists() && shot.length() > 0)
        println("FORWARDING_NOTIFICATION_SCREENSHOT ${shot.absolutePath}")
        instrumentation.uiAutomation.executeShellCommand("cmd statusbar collapse").close()
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
