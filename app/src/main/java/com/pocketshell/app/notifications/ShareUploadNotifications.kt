package com.pocketshell.app.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.pocketshell.app.R

/**
 * Notification channel for Android share-target upload failures
 * (issue #138). Kept as a small object with no DI dependency, callable
 * from a `ShareActivity` once a transfer resolves.
 *
 * Failure notifications surface a specific, human-readable error
 * string (set by the caller). Success is intentionally silent outside
 * the in-app result surface: the user initiated the share and does not
 * need a post-upload Android notification.
 */
object ShareUploadNotifications {
    private const val CHANNEL_ID: String = "share_upload_result"
    private const val CHANNEL_NAME: String = "Share-to-host upload errors"
    /**
     * Notification IDs collide deliberately so a fresh share upload
     * dismisses the previous status entry — the user only ever sees the
     * most recent share-target failure.
     */
    private const val NOTIFICATION_ID_FAILURE: Int = 26_102

    fun showFailure(context: Context, hostName: String, message: String) {
        val appContext = context.applicationContext
        ensureChannel(appContext)
        if (!canPostNotifications(appContext)) return

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_qs_forwarding)
            .setContentTitle("Could not upload to $hostName")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .build()

        appContext.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID_FAILURE, notification)
    }

    private fun canPostNotifications(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
    }
}
