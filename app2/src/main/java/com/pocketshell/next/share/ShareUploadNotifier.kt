package com.pocketshell.next.share

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.pocketshell.next.R

/**
 * The share upload's status-bar surface (rewrite task P-9).
 *
 * ## Why this exists at all
 *
 * [ShareActivity] is a one-shot surface the user is expected to walk away from:
 * the whole gesture is "share → pick host → go back to what I was doing". If the
 * upload's only feedback lived in that activity, a 20 MB screenshot shared over
 * a slow link would report its outcome to a screen nobody is looking at. So the
 * status bar carries the same three states the screen does.
 *
 * ## Deliberately not a foreground service
 *
 * Unlike `ports.ForwardService`, this is a plain notification. A share upload is
 * a bounded, user-initiated transfer that completes in seconds and dies with the
 * process if the system kills it — there is nothing to keep alive, and D21's
 * background-work carve-out does not stretch to cover it. If uploads ever need
 * to survive backgrounding, that is a service, and it is a separate decision.
 *
 * ## Best effort, by design
 *
 * Every call is wrapped: `POST_NOTIFICATIONS` may be denied (Android 13+ asks,
 * and this activity deliberately does not interrupt a share to beg for it), in
 * which case `notify` is a silent no-op. The upload does not depend on any of
 * this — the in-app surface is the primary one, the notification is the copy for
 * when the user has left.
 */
class ShareUploadNotifier(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    /** Ongoing, indeterminate progress. [detail] names the file being sent. */
    fun progress(hostName: String, detail: String) {
        post(
            builder()
                .setContentTitle("Uploading to $hostName")
                .setContentText(detail)
                .setProgress(0, 0, true)
                .setOngoing(true),
        )
    }

    fun success(hostName: String, detail: String) {
        post(
            builder()
                .setContentTitle("Sent to $hostName")
                .setContentText(detail)
                .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
                .setAutoCancel(true),
        )
    }

    fun failure(hostName: String, message: String) {
        post(
            builder()
                .setContentTitle("Upload to $hostName failed")
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setAutoCancel(true),
        )
    }

    /** Removes whatever is showing — the user dismissed the share surface. */
    fun clear() {
        runCatching { manager.cancel(NOTIFICATION_ID) }
    }

    private fun builder(): NotificationCompat.Builder {
        ensureChannel()
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_share_upload)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
    }

    private fun post(builder: NotificationCompat.Builder) {
        runCatching {
            if (!manager.areNotificationsEnabled()) return
            @Suppress("MissingPermission")
            manager.notify(NOTIFICATION_ID, builder.build())
        }
    }

    private fun ensureChannel() {
        runCatching {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Shared uploads",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Progress and result of files shared to a host."
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private companion object {
        const val CHANNEL_ID = "share_upload"

        /** Distinct from `ForwardService`'s 4201 so the two never overwrite. */
        const val NOTIFICATION_ID = 4301
    }
}
