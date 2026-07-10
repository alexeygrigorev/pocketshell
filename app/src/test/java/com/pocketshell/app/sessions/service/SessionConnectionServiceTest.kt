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
    fun `grace-expired past deadline shows reconnecting copy and NO count-down (issue 1440)`() {
        // Issue #1440 — the reported frame: the maintainer returned to the app while it was
        // RECONNECTING (expected), but the FGS notification was frozen on the grace-hold copy
        // with a system count-down chronometer that had drifted PAST ZERO into a NEGATIVE timer
        // (−06:51), still titled "Session connected" / "disconnecting in". Reproduce it: a
        // holding snapshot whose bounded-grace deadline is now ~6:51 in the PAST.
        val deadline = 1_000_000L
        val now = deadline + (6 * 60_000L + 51_000L) // 06:51 past the deadline — the −06:51 frame
        val notification = buildServiceNotification(
            SessionConnectionSnapshot(
                liveSessionCount = 1,
                primaryHostName = "alpha",
                disconnectAtWallClockMillis = deadline,
            ),
            nowMillis = now,
        )

        // No count-down chronometer at all — the system must not render a negative timer.
        assertFalse(
            "an elapsed grace deadline must NOT render a count-down chronometer (it would go " +
                "negative — the reported −06:51)",
            notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER),
        )
        assertFalse(
            notification.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN),
        )
        // Copy tracks the lifecycle phase: reconnecting, not the frozen grace-hold.
        assertEquals(
            "Reconnecting…",
            notification.extras.getCharSequence("android.title")?.toString(),
        )
        val body = notification.extras.getCharSequence("android.text")?.toString().orEmpty()
        assertFalse(
            "an elapsed deadline must NOT keep the frozen 'disconnecting in' grace-hold copy: '$body'",
            body.contains("disconnecting in"),
        )
        assertTrue(
            "reconnecting body must read as reconnecting: '$body'",
            body.contains("Reconnecting to alpha"),
        )
    }

    @Test
    fun `explicit reconnecting flag shows reconnecting copy even before the deadline elapses (issue 1440)`() {
        // Class coverage (G2): the controller flips the reconnecting flag at the scheduled
        // deadline. Even if the wall-clock `when` anchor were still nominally in the future, the
        // explicit flag forces RECONNECTING — the display never depends on a single racy `now`.
        val deadline = 5_000_000L
        val now = deadline - 30_000L // still 30s before the anchor
        val notification = buildServiceNotification(
            SessionConnectionSnapshot(
                liveSessionCount = 1,
                primaryHostName = "alpha",
                disconnectAtWallClockMillis = deadline,
                reconnecting = true,
            ),
            nowMillis = now,
        )

        assertFalse(
            "reconnecting must never render a count-down chronometer",
            notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER),
        )
        assertEquals(
            "Reconnecting…",
            notification.extras.getCharSequence("android.title")?.toString(),
        )
        val body = notification.extras.getCharSequence("android.text")?.toString().orEmpty()
        assertFalse("no grace-hold copy while reconnecting: '$body'", body.contains("disconnecting in"))
    }

    @Test
    fun `holding within grace with a future deadline still shows the live count-down (no 1123 regression)`() {
        // Regression guard for #1123: a STRICTLY FUTURE deadline is a live count-down.
        val deadline = 2_000_000L
        val now = deadline - 90_000L // 90s before the deadline — comfortably in grace
        val notification = buildServiceNotification(
            SessionConnectionSnapshot(
                liveSessionCount = 1,
                primaryHostName = "alpha",
                disconnectAtWallClockMillis = deadline,
            ),
            nowMillis = now,
        )

        assertTrue(
            "an in-grace future deadline must still render the count-down chronometer",
            notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER),
        )
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
        assertEquals(deadline, notification.`when`)
        assertEquals(
            "Session connected",
            notification.extras.getCharSequence("android.title")?.toString(),
        )
        val body = notification.extras.getCharSequence("android.text")?.toString().orEmpty()
        assertTrue("in-grace body counts down: '$body'", body.contains("disconnecting in"))
    }

    @Test
    fun `connected with no deadline shows the plain background hold and NO count-down (issue 1440 phase)`() {
        // Class coverage (G2): the null-deadline / missing-data phase.
        val notification = buildServiceNotification(
            SessionConnectionSnapshot(liveSessionCount = 1, primaryHostName = "alpha"),
            nowMillis = 5_000_000L,
        )

        assertFalse(
            notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER),
        )
        assertEquals(
            "Session connected",
            notification.extras.getCharSequence("android.title")?.toString(),
        )
        val body = notification.extras.getCharSequence("android.text")?.toString().orEmpty()
        assertTrue(
            "no-deadline body reads as a plain background hold: '$body'",
            body.contains("Keeping alpha connected in the background"),
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
    fun `port-forward wording is never shown by the session FGS (single owner is ForwardingService)`() {
        // Issue #1202 + #1198 (hard-cut, D22): the session FGS is SUPPRESSED while a
        // port-forward is active — the ForwardingService FGS is the single owner of the
        // port-forward notification, so this notification NEVER shows the port-forward wording.
        // Even if a stale portForwardActive flag leaked into the snapshot, the title stays the
        // plain "Session connected" hold (the #1159 Part 3 "Port forwarding active" branch is
        // deleted). A live count-down still renders from the deadline.
        val deadline = System.currentTimeMillis() + 90_000L
        val notification = buildServiceNotification(
            SessionConnectionSnapshot(
                liveSessionCount = 1,
                primaryHostName = "alpha",
                disconnectAtWallClockMillis = deadline,
                portForwardActive = true,
            ),
        )

        assertEquals(
            "Session connected",
            notification.extras.getCharSequence("android.title")?.toString(),
        )
        assertTrue(
            "with a deadline the held session notification still counts down (the forward " +
                "wording is owned by ForwardingService, not this FGS)",
            notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER),
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

    private fun buildServiceNotification(
        snapshot: SessionConnectionSnapshot,
        nowMillis: Long = System.currentTimeMillis(),
    ): Notification {
        val service = Robolectric.buildService(SessionConnectionService::class.java).get()
        return service.buildNotification(snapshot, nowMillis = nowMillis)
    }
}
