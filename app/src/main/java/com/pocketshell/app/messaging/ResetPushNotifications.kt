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
 * Posts the "limits just reset" push notification (issue #690) and routes a tap
 * to the usage screen.
 *
 * Built to mirror the existing usage-warning notification path
 * (`com.pocketshell.app.usage.UsageNotifications`): a dedicated channel, a
 * permission gate for Android 13+ `POST_NOTIFICATIONS`, and a content intent
 * that re-uses [MainActivity.EXTRA_OPEN_USAGE] so the deep-link opens the Usage
 * screen — the same target the in-app banner lives on.
 *
 * De-dup (#619 don't-renotify) is the caller's job: a reset push carries the
 * server-side `reset_key`, and [PushDedupStore] suppresses a key that already
 * notified, so one reset → one notification regardless of FCM retries.
 */
public object ResetPushNotifications {
    // Issue #903: the FCM "limits just reset" push is an ALERTING notification
    // the maintainer should notice, so the channel must be IMPORTANCE_HIGH (sound
    // + heads-up), not silent/DEFAULT. Importance is immutable after first
    // creation, so the previously-shipped DEFAULT channel id is bumped (`_v2`) and
    // the stale one deleted (hard-cut D22) — an in-place flip is ignored on the
    // installed app.
    public const val CHANNEL_ID: String = "usage_reset_alerts_v2"
    private const val LEGACY_CHANNEL_ID: String = "usage_reset_alerts"
    private const val CHANNEL_NAME: String = "Usage limit resets"

    /**
     * Build a stable notification id from the reset key so a re-delivery of the
     * SAME reset updates the existing notification rather than stacking a new
     * one. Distinct resets (distinct keys) get distinct ids.
     */
    public fun notificationIdFor(resetKey: String): Int =
        28_000 + (resetKey.hashCode() and 0x0fff)

    public fun show(
        context: Context,
        title: String,
        body: String,
        resetKey: String,
    ) {
        val appContext = context.applicationContext
        ensureChannel(appContext)
        if (!canPostNotifications(appContext)) return

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_qs_forwarding)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            // Issue #903: HIGH priority so the push heads-up on pre-O and matches
            // the HIGH channel importance — the reset push should ping.
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(usagePendingIntent(appContext, resetKey))
            .build()

        appContext.getSystemService(NotificationManager::class.java)
            .notify(notificationIdFor(resetKey), notification)
    }

    private fun usagePendingIntent(context: Context, resetKey: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_USAGE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            notificationIdFor(resetKey),
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
        // Hard-cut (D22): drop the stale DEFAULT channel so the new HIGH channel
        // (#903) is created fresh — importance is immutable on an existing id.
        runCatching { manager.deleteNotificationChannel(LEGACY_CHANNEL_ID) }
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
    }
}
