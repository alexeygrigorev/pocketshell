package com.pocketshell.next.terminal

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
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.pocketshell.next.MainActivity
import com.pocketshell.next.R

/**
 * Keeps the process (and with it the SSH transport) alive for the bounded grace
 * window, and shows the user a live count-down to disconnect — rewrite task
 * U-8, decision D21.
 *
 * ## Only Android mechanics live here
 *
 * Foreground promotion, one ongoing notification, one `PARTIAL_WAKE_LOCK`. The
 * POLICY — when the window opens, how long it is, what closes at the end and
 * when the service stops — is entirely [GraceCoordinator]'s. Deliberately not
 * ported from the old client: its service tracked a live-client registry, a
 * snapshot type and a three-phase notification state machine; app2 has at most
 * one live session screen and a single deadline, so the service takes the
 * deadline as an intent extra and never observes anything.
 *
 * ## The count-down costs nothing
 *
 * The MM:SS is rendered by the SYSTEM, from a count-down chronometer anchored on
 * the wall-clock deadline (`setWhen` + `setUsesChronometer` +
 * `setChronometerCountDown`). The notification is posted ONCE and never updated,
 * so there is no per-second wakeup behind the launcher — which is what lets a
 * "live countdown" coexist with D21's no-background-work rule. Ported from the
 * old client's `SessionConnectionService` (issue #1123), minus the phase machine
 * that made it count past zero (#1440): here the service is stopped AT the
 * deadline by [GraceCoordinator], so the chronometer never reaches a negative
 * value in the first place.
 *
 * ## No Stop action
 *
 * The old notification carried one, and it was an accidental-disconnect footgun.
 * Tapping the notification opens [MainActivity], which is the action that
 * matters: it returns to the foreground, which cancels the grace and keeps the
 * session. Waiting is the other action, and it needs no button.
 *
 * ## The wake lock is bounded twice
 *
 * Released in [onDestroy] on the normal path, and acquired with a platform
 * timeout of the remaining window plus [WAKE_LOCK_MARGIN_MS] as defence in depth
 * — so even a teardown that never runs cannot leak a lock past the window.
 */
class GraceService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val deadlineMs = intent?.getLongExtra(EXTRA_DEADLINE_MS, 0L) ?: 0L
        if (deadlineMs <= System.currentTimeMillis()) {
            // A restart delivery with no extras, or a deadline that has already
            // passed: there is nothing to hold and nothing truthful to show.
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground(buildNotification(deadlineMs))
        acquireWakeLock(deadlineMs)
        // The service holds an already-live in-process transport. A recreated
        // process has no session to keep alive, so Android must not restart it.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    // --- notification ---------------------------------------------------------

    private fun buildNotification(deadlineMs: Long): Notification {
        val open = PendingIntent.getActivity(
            this,
            /* requestCode = */ 0,
            Intent(this, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_session)
            .setContentTitle(TITLE)
            .setContentText(BODY)
            .setContentIntent(open)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            // The three lines that make the system render a live MM:SS count-down
            // anchored on the deadline, with no work on our side.
            .setWhen(deadlineMs)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Session held",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description =
                    "Counts down how long a connected session is held while PocketShell " +
                    "is in the background."
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
            },
        )
    }

    private fun startInForeground(notification: Notification) {
        // Must happen within a few seconds of the start request or Android kills
        // the process.
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

    // --- wake lock ------------------------------------------------------------

    private fun acquireWakeLock(deadlineMs: Long) {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(PowerManager::class.java) ?: return
        val remaining = (deadlineMs - System.currentTimeMillis()).coerceAtLeast(0)
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(remaining + WAKE_LOCK_MARGIN_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) {
                runCatching { lock.release() }
                    .onFailure { Log.w(TAG, "grace wake lock release failed", it) }
            }
        }
        wakeLock = null
    }

    companion object {
        private const val TAG = "PsGraceService"

        internal const val CHANNEL_ID = "pocketshell_session_grace"

        /** "psGr". Distinct from [com.pocketshell.next.ports.ForwardService]'s id. */
        internal const val NOTIFICATION_ID = 0x70_73_47_72

        private const val WAKE_LOCK_TAG = "PocketShell:grace"

        /**
         * Head-room on the platform wake-lock timeout, so the normal
         * release-on-destroy always wins and the timeout is only ever the
         * backstop for a teardown that never ran.
         */
        internal const val WAKE_LOCK_MARGIN_MS = 30_000L

        internal const val EXTRA_DEADLINE_MS = "com.pocketshell.next.terminal.extra.DEADLINE_MS"

        internal const val TITLE = "Session held"

        /**
         * Reads as one sentence with the count-down the system paints beside it:
         * "Disconnecting in  01:29".
         */
        internal const val BODY = "Disconnecting in"

        /**
         * Brings the service up with a count-down to [deadlineMs].
         *
         * MUST be called while the app is still foreground-eligible — see
         * [GraceCoordinator]'s class doc for why that is the activity-stopped
         * boundary and not `ProcessLifecycleOwner`'s debounced `ON_STOP`.
         */
        fun start(context: Context, deadlineMs: Long) {
            val intent = Intent(context, GraceService::class.java)
                .putExtra(EXTRA_DEADLINE_MS, deadlineMs)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure {
                    // A rejected start (Android 12+ background restriction) must
                    // not take the process down: the session simply is not held,
                    // and the reconnect ladder covers the return.
                    Log.w(TAG, "grace foreground service start was rejected", it)
                }
        }

        /** Takes the service down. Safe when it is not running. */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, GraceService::class.java)) }
                .onFailure { Log.w(TAG, "grace foreground service stop was rejected", it) }
        }
    }
}

/**
 * The production [GraceServiceControl]: [GraceService], started and stopped
 * against the application context.
 */
class AndroidGraceServiceControl(private val context: Context) : GraceServiceControl {

    override fun start(deadlineMs: Long) = GraceService.start(context, deadlineMs)

    override fun stop() = GraceService.stop(context)
}
