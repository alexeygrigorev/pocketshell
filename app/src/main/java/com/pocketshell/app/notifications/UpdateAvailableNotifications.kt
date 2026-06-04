package com.pocketshell.app.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.pocketshell.app.R
import com.pocketshell.app.release.ReleaseChecker
import com.pocketshell.app.release.ReleaseInfo

/**
 * Local "a newer PocketShell release is available" notification
 * (issue #502). Posted from the foreground when the existing
 * [ReleaseChecker] (run from `HostListViewModel`) detects a strictly
 * newer GitHub Release than the installed APK.
 *
 * This is deliberately NOT a server push / FCM / WorkManager job —
 * PocketShell is foreground-only (no-background principle, D22 /
 * docs/decisions.md). The notification appears in the status bar like
 * any other; the user simply notices it even when they've skipped the
 * host-list screen (where the [com.pocketshell.app.hosts.UpdateBanner]
 * lives) and gone straight into a session.
 *
 * Mirrors [ShareUploadNotifications] and the port-forward
 * [com.pocketshell.app.portfwd.service.ForwardingService] channel
 * pattern: a small `object`, no DI, one channel, one notification id.
 *
 * Tapping the notification fires `Intent.ACTION_VIEW` against the APK
 * download URL — the same primary action as the #476 host-list update
 * tap — so the system download manager / browser handles the download.
 * We do NOT silently install (no `REQUEST_INSTALL_PACKAGES`).
 */
object UpdateAvailableNotifications {
    private const val CHANNEL_ID: String = "app_update_available"
    private const val CHANNEL_NAME: String = "App updates"

    /**
     * A single, stable id: re-posting for a newer release replaces the
     * previous entry so the status bar only ever shows the latest
     * available version.
     */
    private const val NOTIFICATION_ID: Int = 26_201

    private val versionLabeler = ReleaseChecker()

    /**
     * Post (or replace) the update-available notification for [info].
     * Best-effort: silently returns when the POST_NOTIFICATIONS runtime
     * grant is missing (Android 13+) — the host-list banner is still the
     * graceful-degradation path in that case.
     */
    fun show(context: Context, info: ReleaseInfo) {
        val appContext = context.applicationContext
        ensureChannel(appContext)
        if (!canPostNotifications(appContext)) return

        val versionLabel = versionLabeler.renderDottedVersionLabel(info.tagName)
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_qs_forwarding)
            .setContentTitle("PocketShell $versionLabel available")
            .setContentText("Tap to update")
            .setAutoCancel(true)
            .setContentIntent(updatePendingIntent(appContext, info))
            .build()

        appContext.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification)
    }

    /**
     * `ACTION_VIEW` against the APK download URL, the same primary route
     * the host-list "Update" tap uses (#476). Launched from a
     * notification (i.e. outside an Activity), so `FLAG_ACTIVITY_NEW_TASK`
     * is required. If no handler exists the system simply drops the tap;
     * the host-list banner's browser fallback remains available in-app.
     */
    private fun updatePendingIntent(context: Context, info: ReleaseInfo): PendingIntent {
        val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(info.apkUrl))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context,
            info.tagName.hashCode(),
            viewIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
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
