package com.pocketshell.next.ports

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.pocketshell.next.MainActivity
import com.pocketshell.next.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps port forwards alive while the app is backgrounded (rewrite task P-4).
 *
 * The D21 background-work carve-out: PocketShell runs no background work except a
 * bounded grace timer AND this service, which exists because a forwarded port the
 * user opened has to keep answering after they leave the screen — that is the
 * entire feature.
 *
 * ## The whole design
 *
 * - ONE notification channel, created once in [onCreate].
 * - ONE collector coroutine on the main dispatcher reading
 *   [ForwardingController.snapshot] and calling [updateNotification]. Because it
 *   is a single coroutine on a single-threaded dispatcher, notification updates
 *   are serialised BY CONSTRUCTION — no mutation authority, no close barrier, no
 *   observe-generation fencing (the ~900 lines the rewrite deletes). A rapid
 *   toggle produces a rapid sequence of snapshots, and the last one wins because
 *   it arrives last.
 * - Resume-on-foreground is [ForwardingController.resumeEnabled]: re-read the
 *   hosts whose `enabled` column is set. The service holds no registry of its
 *   own, so it has nothing that can disagree with Room.
 * - When the snapshot goes empty the service stops itself, so an idle process is
 *   never held alive by a notification about nothing.
 */
@AndroidEntryPoint
class ForwardService : Service() {

    @Inject
    lateinit var controller: ForwardingController

    /**
     * Main-dispatcher scope. `Dispatchers.Main.immediate` is deliberate: the
     * notification updates must be serialised, and the single-threaded main
     * dispatcher is the simplest thing that guarantees it.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // Must happen within a few seconds of the start request or Android kills
        // the process, so it is posted before any suspend work runs.
        startInForeground(buildNotification(emptyList()))
        scope.launch {
            controller.snapshot.collect { snapshot ->
                updateNotification(snapshot)
                if (snapshot.isEmpty()) stopSelf()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_ALL -> scope.launch {
                controller.stopAll()
                stopSelf()
            }

            // ACTION_RESUME and a null intent (a restart delivery) are the same
            // request: make what Room says is enabled actually be running.
            else -> scope.launch {
                if (controller.resumeEnabled() == 0) stopSelf()
            }
        }
        // Not sticky: a restarted process must not resurrect forwards without the
        // user asking. The durable `enabled` intent is honoured when the app is
        // next opened, which is when a re-dial can actually reach a trust prompt.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** The ONE notification mutation site. Serialised by its single caller. */
    private fun updateNotification(snapshot: List<ForwardingController.HostForwarding>) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(snapshot))
    }

    private fun buildNotification(
        snapshot: List<ForwardingController.HostForwarding>,
    ): Notification {
        val open = PendingIntent.getActivity(
            this,
            /* requestCode = */ 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            /* requestCode = */ 1,
            Intent(this, ForwardService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val body = ForwardNotificationText.body(snapshot)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_forwarding)
            .setContentTitle(ForwardNotificationText.title(snapshot))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .addAction(0, "Stop all", stop)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Port forwarding",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows which ports are being forwarded from your hosts."
                setShowBadge(false)
            },
        )
    }

    companion object {
        internal const val CHANNEL_ID = "port_forwarding"
        internal const val NOTIFICATION_ID = 4201

        internal const val ACTION_RESUME = "com.pocketshell.next.ports.action.RESUME"
        internal const val ACTION_STOP_ALL = "com.pocketshell.next.ports.action.STOP_ALL"

        /**
         * Starts (or re-triggers) the service so every host with the durable
         * `enabled` intent is forwarding. Safe to call repeatedly — the controller
         * treats an already-running host as a no-op.
         */
        fun resume(context: Context) {
            context.startForegroundService(
                Intent(context, ForwardService::class.java).setAction(ACTION_RESUME),
            )
        }
    }
}
