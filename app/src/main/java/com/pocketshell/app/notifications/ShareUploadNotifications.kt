package com.pocketshell.app.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.pocketshell.app.R

/**
 * Notification channel + result toasts for the Android share-target
 * upload flow (issue #138). Kept as a small object with no DI dependency,
 * callable from a `ShareActivity` once a transfer resolves.
 *
 * Success notifications are tappable: pressing them launches the
 * [CopyRemotePathReceiver] which copies the full remote path to the
 * system clipboard. The notification body shows the remote path so
 * the user can see what landed without leaving the source app.
 *
 * Failure notifications surface a specific, human-readable error
 * string (set by the caller) and are non-tappable — there is nothing
 * actionable to route to in the first cut.
 */
object ShareUploadNotifications {
    private const val CHANNEL_ID: String = "share_upload_result"
    private const val CHANNEL_NAME: String = "Share-to-host uploads"
    /**
     * Notification IDs collide deliberately so a fresh share upload
     * dismisses the previous status entry — the user only ever sees the
     * most recent share-target result. Two separate IDs (success vs
     * failure) so a transient failure doesn't dismiss a long-running
     * success from another concurrent share.
     */
    private const val NOTIFICATION_ID_SUCCESS: Int = 26_101
    private const val NOTIFICATION_ID_FAILURE: Int = 26_102

    fun showSuccess(
        context: Context,
        hostName: String,
        remotePath: String,
        copyText: String = remotePath,
    ) {
        val appContext = context.applicationContext
        ensureChannel(appContext)
        if (!canPostNotifications(appContext)) return

        val copyIntent = Intent(appContext, CopyRemotePathReceiver::class.java).apply {
            action = CopyRemotePathReceiver.ACTION_COPY_PATH
            putExtra(CopyRemotePathReceiver.EXTRA_REMOTE_PATH, copyText)
            putExtra(CopyRemotePathReceiver.EXTRA_HOST_NAME, hostName)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            appContext,
            remotePath.hashCode(),
            copyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_qs_forwarding)
            .setContentTitle("Uploaded to $hostName")
            .setContentText(remotePath)
            .setStyle(NotificationCompat.BigTextStyle().bigText(remotePath))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        appContext.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID_SUCCESS, notification)
    }

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

/**
 * Copies the remote path stored in [EXTRA_REMOTE_PATH] to the system
 * clipboard when a share-result notification is tapped. Lives as a
 * separate file alongside the notifications helper so the manifest
 * only needs one entry.
 *
 * Declared in [com.pocketshell.app.AndroidManifest.xml] as an
 * unexported receiver. The receiver is internal to the app; only the
 * notification's PendingIntent triggers it.
 */
class CopyRemotePathReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_COPY_PATH) return
        val path = intent.getStringExtra(EXTRA_REMOTE_PATH) ?: return
        val hostName = intent.getStringExtra(EXTRA_HOST_NAME).orEmpty()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(
            ClipData.newPlainText(
                if (hostName.isNotEmpty()) "Remote path on $hostName" else "Remote path",
                path,
            ),
        )
    }

    companion object {
        const val ACTION_COPY_PATH: String =
            "com.pocketshell.app.notifications.action.COPY_REMOTE_PATH"
        const val EXTRA_REMOTE_PATH: String =
            "com.pocketshell.app.notifications.extra.REMOTE_PATH"
        const val EXTRA_HOST_NAME: String =
            "com.pocketshell.app.notifications.extra.HOST_NAME"
    }
}
