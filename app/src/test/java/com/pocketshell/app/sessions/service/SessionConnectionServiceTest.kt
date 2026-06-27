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
