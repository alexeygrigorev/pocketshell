package com.pocketshell.app.messaging

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.pocketshell.app.MainActivity
import com.pocketshell.app.R

/**
 * Posts the "an agent pushed a card" heads-up notification (epic #859, Slice D)
 * and routes a tap to the card's session feed.
 *
 * Built to mirror [ResetPushNotifications]: a dedicated channel, a permission
 * gate for Android 13+ `POST_NOTIFICATIONS`, and a content intent that carries
 * the session + host so the deep-link opens the right host's session screen
 * (where the card-feed chip lives — [MainActivity.EXTRA_OPEN_SESSION_FEED]).
 *
 * ## Audible channel (#903)
 *
 * Per #903 the agent-card push is an ALERTING notification the maintainer
 * should notice, so the channel is `IMPORTANCE_HIGH` (sound + heads-up), not
 * silent/DEFAULT — a separate channel from the usage-reset one so the user can
 * tune them independently.
 *
 * De-dup is the caller's job ([PocketShellMessagingService] consults
 * [PushDedupStore] on the `card_key`), so one card update → one notification
 * regardless of FCM retries; a re-delivery of the same `card_key` updates the
 * existing notification rather than stacking.
 */
public object AgentCardPushNotifications {
    public const val CHANNEL_ID: String = "agent_card_alerts"
    private const val CHANNEL_NAME: String = "Agent cards"

    /**
     * Build a stable notification id from the card key so a re-delivery of the
     * SAME card updates the existing notification rather than stacking a new
     * one. Distinct cards (distinct keys) get distinct ids.
     */
    public fun notificationIdFor(cardKey: String): Int =
        29_000 + (cardKey.hashCode() and 0x0fff)

    public fun show(
        context: Context,
        payload: AgentCardPushPayload,
    ) {
        val appContext = context.applicationContext
        ensureChannel(appContext)
        if (!canPostNotifications(appContext)) return

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_qs_forwarding)
            .setContentTitle(payload.title)
            .setContentText(payload.summary)
            .setAutoCancel(true)
            // #903: HIGH priority so the push heads-up on pre-O and matches the
            // HIGH channel importance — the agent-card push should ping.
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(feedPendingIntent(appContext, payload))
            .build()

        appContext.getSystemService(NotificationManager::class.java)
            .notify(notificationIdFor(payload.cardKey), notification)
    }

    /**
     * The deep-link [PendingIntent] that opens the card's session feed. Carries
     * the session + host so [MainActivity] can resolve the right host from its
     * store and route to that session; on no/ambiguous host match the activity
     * falls back to the home screen (never the wrong host).
     */
    @androidx.annotation.VisibleForTesting
    internal fun feedPendingIntent(context: Context, payload: AgentCardPushPayload): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_SESSION_FEED, true)
            .putExtra(MainActivity.EXTRA_OPEN_SESSION_FEED_SESSION, payload.session)
            .putExtra(MainActivity.EXTRA_OPEN_SESSION_FEED_HOST, payload.host)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            notificationIdFor(payload.cardKey),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun canPostNotifications(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    @androidx.annotation.VisibleForTesting
    internal fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
    }
}
