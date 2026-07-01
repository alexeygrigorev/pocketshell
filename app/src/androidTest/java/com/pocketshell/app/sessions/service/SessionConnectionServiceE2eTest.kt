package com.pocketshell.app.sessions.service

import android.app.Notification
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
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.app.testaccess.TestAccessEntryPoint
import com.pocketshell.core.tmux.CommandResponse
import com.pocketshell.core.tmux.TmuxClient
import com.pocketshell.core.tmux.TmuxDisconnectEvent
import com.pocketshell.core.tmux.TmuxOutputBacklogOverflow
import com.pocketshell.core.tmux.protocol.ControlEvent
import dagger.hilt.android.EntryPointAccessors
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * API-35 emulator proof for the session foreground-service envelope.
 *
 * This registers a live entry in the production [ActiveTmuxClients] singleton,
 * so [SessionServiceController] drives the real foreground service exactly as a
 * live session does. The client is an in-process [TmuxClient] double; this
 * on-device test proves the Android surface.
 *
 * Issue #1159 (Part 1): the foreground service now runs ONLY while the app is
 * BACKGROUNDED. In the foreground the Activity holds the connection, so there is
 * NO "Session connected" tray notification (and no Stop-action footgun). This test
 * reproduces the maintainer's reported scenario on-device:
 *   1. Foreground + live session → NO session notification.
 *   2. Background → the FGS starts, its notification + wakelock appear.
 *   3. Return to foreground within grace → the FGS stops, notification + wakelock
 *      clear, and the live connection is NOT dropped (starting/stopping the FGS
 *      never touches the connection).
 *
 * The grace-window teardown math (#1123) and the port-forward always-on hold
 * (#1159 Part 3) are covered deterministically by the JVM/Robolectric suites.
 */
@RunWith(AndroidJUnit4::class)
class SessionConnectionServiceE2eTest {

    @get:Rule
    val permissions = PreGrantPermissionsRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val notificationManager: NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    private var scenario: ActivityScenario<MainActivity>? = null
    private var clientRegistration: ActiveTmuxClients.Registration? = null
    private var lifecycleRegistration: ActiveTmuxClients.LifecycleRegistration? = null

    @After
    fun cleanup() {
        BackgroundGraceTestOverride.setForTest(null)
        runCatching { scenario?.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED) }
        scenario?.close()
        scenario = null
        val registry = entryPoint().activeTmuxClients()
        clientRegistration?.let { runCatching { registry.unregister(it) } }
        lifecycleRegistration?.let { runCatching { registry.unregisterLifecycleHooks(it) } }
        clientRegistration = null
        lifecycleRegistration = null
        notificationManager.cancelAll()
    }

    @Test
    fun sessionFgsRunsOnlyWhileBackgrounded_foregroundHasNoNotification_connectionSurvives() {
        assertEquals(
            "API 35 evidence must run on Android 15",
            35,
            android.os.Build.VERSION.SDK_INT,
        )
        grantNotifications()
        notificationManager.cancelAll()
        // A long grace so the backgrounded FGS stays up long enough to observe, and a
        // return to the foreground is comfortably WITHIN grace (no teardown).
        BackgroundGraceTestOverride.setForTest(LONG_GRACE_MS)
        scenario = ActivityScenario.launch(MainActivity::class.java)

        val registry = entryPoint().activeTmuxClients()
        val fakeClient = ConnectedTmuxClient()
        lifecycleRegistration = registry.registerLifecycleHooks(
            hostId = HOST_ID,
            hooks = ActiveTmuxClients.LifecycleHooks(
                onBackground = {},
                onForeground = {},
            ),
        )
        clientRegistration = registry.register(
            hostId = HOST_ID,
            hostName = "FGS Host",
            hostname = "fgs.example",
            port = 22,
            username = "alexey",
            keyPath = "/tmp/key",
            client = fakeClient,
        )

        // Part 1 — FOREGROUND: a live session in the foreground must NOT post the
        // "Session connected" notification (the Activity holds the connection; a Stop-able
        // tray status is a footgun). Assert it stays absent through a settle window.
        assertSessionNotificationAbsentWhileForeground()

        // Part 1 — BACKGROUND: moving to CREATED backgrounds the process
        // (ProcessLifecycleOwner ON_STOP), which starts the FGS + its notification + wakelock.
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        val posted = waitForSessionNotification()
        assertNotNull("backgrounding a live session must start the session foreground notification", posted)
        val notification = posted!!.notification
        assertEquals("pocketshell_session_status_v1", notification.channelId)
        val channel = notificationManager.getNotificationChannel(notification.channelId)
        assertNotNull("session notification channel must exist", channel)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel!!.importance)
        assertNull("session notification channel must be silent", channel.sound)
        assertFalse("session notification channel must not vibrate", channel.shouldVibrate())
        assertTrue(
            "session notification must be ongoing",
            notification.flags and Notification.FLAG_ONGOING_EVENT != 0,
        )
        assertTrue(
            "session notification must be non-clearable",
            notification.flags and Notification.FLAG_NO_CLEAR != 0,
        )
        waitForWakeLockHeld()

        // Part 1 — FOREGROUND AGAIN (within grace): returning to the foreground stops the
        // FGS (notification + wakelock clear) WITHOUT dropping the live connection —
        // starting/stopping the service never touches the connection itself.
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        waitForSessionNotificationGone()
        waitForWakeLockReleased()
        assertFalse(
            "returning to the foreground must NOT drop the live connection (FGS stop is envelope-only)",
            fakeClient.disconnected.value,
        )
    }

    private fun assertSessionNotificationAbsentWhileForeground() {
        val deadline = System.currentTimeMillis() + FOREGROUND_SETTLE_MS
        while (System.currentTimeMillis() < deadline) {
            assertNull(
                "a foregrounded live session must NOT post the session notification (#1159 Part 1); " +
                    "active titles=" + notificationManager.activeNotifications.map {
                        it.notification.extras.getCharSequence("android.title")
                    },
                sessionNotification(),
            )
            Thread.sleep(POLL_MS)
        }
    }

    private fun grantNotifications() {
        val pkg = context.packageName
        runShellCommand("pm grant $pkg android.permission.POST_NOTIFICATIONS")
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            if (
                context.checkSelfPermission("android.permission.POST_NOTIFICATIONS") ==
                    PackageManager.PERMISSION_GRANTED &&
                notificationManager.areNotificationsEnabled()
            ) {
                return
            }
            Thread.sleep(POLL_MS)
        }
        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission("android.permission.POST_NOTIFICATIONS"),
        )
        assertTrue(notificationManager.areNotificationsEnabled())
    }

    private fun waitForSessionNotification(): android.service.notification.StatusBarNotification? {
        val deadline = System.currentTimeMillis() + 10_000L
        var posted = sessionNotification()
        while (posted == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_MS)
            posted = sessionNotification()
        }
        return posted
    }

    private fun waitForSessionNotificationGone() {
        val deadline = System.currentTimeMillis() + 10_000L
        var posted = sessionNotification()
        while (posted != null && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_MS)
            posted = sessionNotification()
        }
        assertNull(
            "session notification must clear after Stop; active titles=" +
                notificationManager.activeNotifications.map {
                    it.notification.extras.getCharSequence("android.title")
                },
            posted,
        )
    }

    private fun sessionNotification() =
        notificationManager.activeNotifications.firstOrNull {
            it.notification.extras
                .getCharSequence("android.title")
                ?.toString() == "Session connected"
        }

    private fun waitForWakeLockHeld() {
        val deadline = System.currentTimeMillis() + 10_000L
        while (System.currentTimeMillis() < deadline) {
            if (currentWakeLocksDump().contains(WAKE_LOCK_TAG)) return
            Thread.sleep(POLL_MS)
        }
        error("session wakelock '$WAKE_LOCK_TAG' was not visible in dumpsys power")
    }

    private fun waitForWakeLockReleased() {
        val deadline = System.currentTimeMillis() + 10_000L
        var dump = currentWakeLocksDump()
        while (dump.contains(WAKE_LOCK_TAG) && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_MS)
            dump = currentWakeLocksDump()
        }
        assertFalse("session wakelock must be released after Stop", dump.contains(WAKE_LOCK_TAG))
    }

    private fun currentWakeLocksDump(): String {
        val dump = runShellCommand("dumpsys power")
        val start = dump.indexOf("Wake Locks:")
        if (start < 0) return dump
        val end = dump.indexOf("Suspend Blockers:", start).takeIf { it >= 0 } ?: dump.length
        return dump.substring(start, end)
    }

    private fun entryPoint(): TestAccessEntryPoint =
        EntryPointAccessors.fromApplication(context.applicationContext, TestAccessEntryPoint::class.java)

    private fun runShellCommand(command: String): String {
        val pfd = instrumentation.uiAutomation.executeShellCommand(command)
        return FileInputStream(pfd.fileDescriptor).use { input ->
            BufferedReader(InputStreamReader(input)).use { it.readText() }
        }.also { pfd.close() }
    }

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

        override fun close() {
            markDisconnected()
        }

        override suspend fun setWindowSizeLatest(sessionId: String): CommandResponse =
            CommandResponse(number = 0L, output = emptyList(), isError = false)

        override suspend fun refreshClientSize(cols: Int, rows: Int): CommandResponse =
            CommandResponse(number = 0L, output = emptyList(), isError = false)

        override suspend fun detachCleanly(timeoutMs: Long) {
            markDisconnected()
        }

        fun markDisconnected() {
            disconnectedState.value = true
        }
    }

    private companion object {
        const val HOST_ID: Long = 1_021_001L
        // Long enough that the backgrounded FGS stays up for observation and a return to
        // the foreground is comfortably within grace (no teardown).
        const val LONG_GRACE_MS: Long = 120_000L
        // Settle window over which the foreground must stay notification-free (#1159 Part 1).
        const val FOREGROUND_SETTLE_MS: Long = 3_000L
        const val POLL_MS: Long = 100L
        const val WAKE_LOCK_TAG: String = "PocketShell:session"
    }
}
