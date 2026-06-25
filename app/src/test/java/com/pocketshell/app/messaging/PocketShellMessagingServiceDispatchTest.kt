package com.pocketshell.app.messaging

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import com.google.firebase.messaging.RemoteMessage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Issue #859 (Slice D) — REPRODUCE-FIRST load-bearing test.
 *
 * Drives the REAL [PocketShellMessagingService.onMessageReceived] with an
 * `agent_card` FCM data message and proves, end-to-end through the production
 * receive path:
 *  1. A heads-up notification is posted on the alerting agent-card channel
 *     ([AgentCardPushNotifications.CHANNEL_ID], IMPORTANCE_HIGH per #903) with
 *     the card title + summary the host sent.
 *  2. Its tap (content) intent is the session-feed deep-link
 *     ([MainActivity.EXTRA_OPEN_SESSION_FEED] + session + host) so it routes to
 *     the right host's session screen.
 *  3. The `usage_reset` path STILL works (no regression from the new dispatch).
 *  4. A re-delivery of the SAME card_key is suppressed (PushDedupStore).
 *
 * Without the new `when(type)` dispatch + `agent_card` handling this test fails
 * red (the service ignored every non-`usage_reset` push and posted nothing).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PocketShellMessagingServiceDispatchTest {

    private lateinit var service: PocketShellMessagingService
    private lateinit var context: Context
    private lateinit var notificationManager: NotificationManager

    @Before
    fun setUp() {
        service = Robolectric.buildService(PocketShellMessagingService::class.java)
            .create()
            .get()
        context = service.applicationContext
        // Android 13+ POST_NOTIFICATIONS is a runtime permission; without it the
        // production `show()` early-returns (canPostNotifications == false) and
        // nothing posts. Grant it on the Robolectric app so the real post path runs.
        shadowOf(context.applicationContext as android.app.Application)
            .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.cancelAll()
        // Clear de-dup state so the first push always notifies.
        context.getSharedPreferences(PushDedupStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After
    fun tearDown() {
        notificationManager.cancelAll()
    }

    private fun agentCardMessage(
        session: String = "claude-main",
        host: String = "agent-box.example",
        cardKey: String = "claude-main|checklist|abcd",
    ): RemoteMessage = RemoteMessage.Builder("to").setData(
        mapOf(
            "type" to "agent_card",
            "session" to session,
            "host" to host,
            "card_id" to "checklist",
            "card_type" to "checklist",
            "title" to "Release steps",
            "summary" to "checklist 1/3 checked",
            "card_key" to cardKey,
        ),
    ).build()

    @Test
    fun agentCardPush_postsHeadsUpNotification_deepLinkingToSessionFeed() {
        service.onMessageReceived(agentCardMessage())

        val posted = shadowOf(notificationManager).allNotifications
        assertEquals("exactly one agent-card notification expected", 1, posted.size)
        val n: Notification = posted.single()

        // Title + summary from the host.
        assertEquals(
            "Release steps",
            n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
        )
        assertEquals(
            "checklist 1/3 checked",
            n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )

        // Posted on the alerting agent-card channel (#903 audible).
        assertEquals(AgentCardPushNotifications.CHANNEL_ID, n.channelId)
        val channel = notificationManager
            .getNotificationChannel(AgentCardPushNotifications.CHANNEL_ID)
        assertNotNull("agent-card channel must be created", channel)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel!!.importance)

        // The tap deep-links to the session feed (session + host carried).
        assertNotNull("notification must carry a content (tap) intent", n.contentIntent)
        val shadowPending = shadowOf(n.contentIntent)
        val tapIntent = shadowPending.savedIntent
        assertTrue(
            "tap must carry EXTRA_OPEN_SESSION_FEED",
            tapIntent.getBooleanExtra(MainActivity_EXTRA_OPEN_SESSION_FEED, false),
        )
        assertEquals(
            "claude-main",
            tapIntent.getStringExtra(MainActivity_EXTRA_OPEN_SESSION_FEED_SESSION),
        )
        assertEquals(
            "agent-box.example",
            tapIntent.getStringExtra(MainActivity_EXTRA_OPEN_SESSION_FEED_HOST),
        )
    }

    @Test
    fun agentCardPush_sameCardKeyReDelivery_isSuppressed() {
        service.onMessageReceived(agentCardMessage())
        notificationManager.cancelAll()
        // Same card_key again (an FCM retry) must NOT re-notify.
        service.onMessageReceived(agentCardMessage())
        assertEquals(0, shadowOf(notificationManager).allNotifications.size)
    }

    @Test
    fun agentCardPush_differentCardKey_notifiesAgain() {
        service.onMessageReceived(agentCardMessage(cardKey = "k1"))
        service.onMessageReceived(agentCardMessage(cardKey = "k2"))
        // Two distinct cards → two distinct notification ids, both live.
        assertEquals(2, shadowOf(notificationManager).allNotifications.size)
    }

    @Test
    fun usageResetPush_stillHandled_noRegressionFromDispatch() {
        val msg = RemoteMessage.Builder("to").setData(
            mapOf(
                "type" to "usage_reset",
                "provider" to "codex",
                "reset_key" to "codex|short_term|2026-06-25T00:00:00Z",
            ),
        ).build()
        service.onMessageReceived(msg)
        val posted = shadowOf(notificationManager).allNotifications
        assertEquals("usage_reset must still post a notification", 1, posted.size)
        assertEquals(
            ResetPushNotifications.CHANNEL_ID,
            posted.single().channelId,
        )
    }

    @Test
    fun unknownType_postsNothing() {
        val msg = RemoteMessage.Builder("to").setData(
            mapOf("type" to "something_else", "session" to "work"),
        ).build()
        service.onMessageReceived(msg)
        assertEquals(0, shadowOf(notificationManager).allNotifications.size)
    }

    private companion object {
        // Resolved against MainActivity's public extras (kept as local constants
        // to avoid a hard dependency on the activity class in this messaging test).
        const val MainActivity_EXTRA_OPEN_SESSION_FEED: String =
            "pocketshell.extra.OPEN_SESSION_FEED"
        const val MainActivity_EXTRA_OPEN_SESSION_FEED_SESSION: String =
            "pocketshell.extra.OPEN_SESSION_FEED_SESSION"
        const val MainActivity_EXTRA_OPEN_SESSION_FEED_HOST: String =
            "pocketshell.extra.OPEN_SESSION_FEED_HOST"
    }
}
