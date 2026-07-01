package com.pocketshell.app.sessions.service

import android.app.Notification
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SessionConnectionServiceTest {

    @Test
    fun `notification says the session is connected in the background`() {
        val notification = buildServiceNotification(
            SessionConnectionSnapshot(liveSessionCount = 1, primaryHostName = "alpha"),
        )

        assertEquals(
            "Session connected",
            notification.extras.getCharSequence("android.title")?.toString(),
        )
        val body = notification.extras.getCharSequence("android.text")?.toString().orEmpty()
        assertTrue(
            "body must describe the active background hold: '$body'",
            body.contains("Keeping alpha connected in the background"),
        )
    }

    @Test
    fun `backgrounded notification shows a live count-down to disconnect`() {
        val deadline = System.currentTimeMillis() + 5 * 60_000L
        val notification = buildServiceNotification(
            SessionConnectionSnapshot(
                liveSessionCount = 1,
                primaryHostName = "alpha",
                disconnectAtWallClockMillis = deadline,
            ),
        )

        // The SYSTEM renders MM:SS via a count-down chronometer anchored on `when` — the
        // app posts once, no per-second update.
        assertTrue(
            "backgrounded hold notification must be a count-down chronometer",
            notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER),
        )
        assertTrue(
            "the chronometer must count DOWN to the disconnect deadline",
            notification.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN),
        )
        assertEquals(
            "the count-down anchor (when) must be the grace disconnect deadline",
            deadline,
            notification.`when`,
        )
        val body = notification.extras.getCharSequence("android.text")?.toString().orEmpty()
        assertTrue(
            "body must read as a count-down to disconnect: '$body'",
            body.contains("disconnecting in"),
        )
    }

    @Test
    fun `foreground notification has no count-down chronometer`() {
        val notification = buildServiceNotification(
            SessionConnectionSnapshot(liveSessionCount = 1, primaryHostName = "alpha"),
        )

        assertFalse(
            "with no disconnect deadline the notification must not show a chronometer",
            notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER),
        )
    }

    @Test
    fun `port-forward active notification reads Port forwarding active with no count-down`() {
        // Issue #1159 (Part 3): a port-forward pins the connection always-on — the snapshot
        // carries portForwardActive=true and a null deadline, and the notification is worded
        // for the forward with NO count-down chronometer (there is no teardown to count to).
        val notification = buildServiceNotification(
            SessionConnectionSnapshot(
                liveSessionCount = 1,
                primaryHostName = "alpha",
                disconnectAtWallClockMillis = null,
                portForwardActive = true,
            ),
        )

        assertEquals(
            "Port forwarding active",
            notification.extras.getCharSequence("android.title")?.toString(),
        )
        val body = notification.extras.getCharSequence("android.text")?.toString().orEmpty()
        assertTrue(
            "body must be worded for the forward, not a count-down: '$body'",
            body.contains("port forwarding"),
        )
        assertFalse(
            "a port-forward-pinned hold must NOT show a count-down chronometer",
            notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER),
        )
    }

    @Test
    fun `port-forward active suppresses the count-down even when a deadline is present`() {
        // Defensive: even if a stale deadline leaks into the snapshot, port-forward-active
        // must win and suppress the chronometer (the connection is pinned, not counting down).
        val notification = buildServiceNotification(
            SessionConnectionSnapshot(
                liveSessionCount = 1,
                primaryHostName = "alpha",
                disconnectAtWallClockMillis = System.currentTimeMillis() + 90_000L,
                portForwardActive = true,
            ),
        )

        assertFalse(
            "port-forward-active must suppress the count-down chronometer",
            notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER),
        )
        assertEquals(
            "Port forwarding active",
            notification.extras.getCharSequence("android.title")?.toString(),
        )
    }

    @Test
    fun `notification collapses multiple hosts`() {
        val notification = buildServiceNotification(
            SessionConnectionSnapshot(liveSessionCount = 3, primaryHostName = "alpha"),
        )

        val body = notification.extras.getCharSequence("android.text")?.toString().orEmpty()
        assertTrue(
            "multiple sessions must collapse to '+ N more': '$body'",
            body.contains("alpha + 2 more"),
        )
    }

    @Test
    fun `notification is ongoing non clearable and has a stop action`() {
        val notification = buildServiceNotification(
            SessionConnectionSnapshot(liveSessionCount = 1, primaryHostName = "alpha"),
        )

        assertEquals(R.drawable.ic_stat_session, notification.smallIcon?.resId)
        assertTrue(
            "session notification must be ongoing while the foreground service holds the SSH connection",
            notification.flags and Notification.FLAG_ONGOING_EVENT != 0,
        )
        assertTrue(
            "session notification must survive tray clear-all while the connection is held",
            notification.flags and Notification.FLAG_NO_CLEAR != 0,
        )
        assertNotNull(notification.contentIntent)
        assertTrue(notification.actions?.any { it.title?.toString() == "Stop" } == true)
    }

    @Test
    fun `session channel is visible but silent`() {
        val service = Robolectric.buildService(SessionConnectionService::class.java).get()
        service.createNotificationChannel()
        val notification = service.buildNotification(
            SessionConnectionSnapshot(liveSessionCount = 1, primaryHostName = "alpha"),
        )
        val manager = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(notification.channelId)

        assertEquals("pocketshell_session_status_v1", notification.channelId)
        assertNotNull(channel)
        val sessionChannel = requireNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, sessionChannel.importance)
        assertNull(sessionChannel.sound)
        assertFalse(sessionChannel.shouldVibrate())
        assertEquals(
            NotificationCompat.PRIORITY_DEFAULT,
            @Suppress("DEPRECATION")
            notification.priority,
        )
    }

    private fun buildServiceNotification(snapshot: SessionConnectionSnapshot): Notification {
        val service = Robolectric.buildService(SessionConnectionService::class.java).get()
        return service.buildNotification(snapshot)
    }
}
