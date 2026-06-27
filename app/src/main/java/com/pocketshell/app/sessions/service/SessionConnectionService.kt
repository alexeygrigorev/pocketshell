package com.pocketshell.app.sessions.service

import android.annotation.SuppressLint
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
import com.pocketshell.app.MainActivity
import com.pocketshell.app.R
import com.pocketshell.app.portfwd.DefaultDispatcher
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps an attached SSH/tmux session alive while the app
 * is backgrounded.
 *
 * The service owns only Android process-survival mechanics: foreground
 * promotion, the visible ongoing notification, and a partial wakelock. Start and
 * stop policy is owned by [SessionServiceController].
 */
@AndroidEntryPoint
class SessionConnectionService : Service() {

    @Inject
    lateinit var controller: SessionServiceController

    @Inject
    @DefaultDispatcher
    @JvmField
    var observeDispatcher: CoroutineDispatcher = Dispatchers.Default

    private val scopeDelegate = lazy {
        CoroutineScope(SupervisorJob() + observeDispatcher)
    }
    private val scope: CoroutineScope by scopeDelegate
    private var observeJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var hasStartedForeground = false

    companion object {
        private const val TAG = "PsSessionService"
        private const val CHANNEL_ID = "pocketshell_session_status_v1"
        private const val NOTIFICATION_ID = 0x70_53_53_56 // "pSSV"
        private const val WAKE_LOCK_TAG = "PocketShell:session"

        const val ACTION_START = "com.pocketshell.app.sessions.action.START_SESSION_HOLD"
        const val ACTION_STOP = "com.pocketshell.app.sessions.action.STOP_SESSION_HOLD"

        fun start(context: Context): Boolean {
            val intent = Intent(context, SessionConnectionService::class.java).apply {
                action = ACTION_START
            }
            return runCatching {
                ContextCompat.startForegroundService(context, intent)
                true
            }.getOrElse {
                Log.w(TAG, "session foreground service start was rejected", it)
                false
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, SessionConnectionService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching {
                context.startService(intent)
            }.onFailure {
                Log.w(TAG, "session foreground service stop request was rejected", it)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                controller.stopHoldingFromNotification(requestServiceStop = false)
                stopSessionHold()
                return START_NOT_STICKY
            }
            else -> {
                if (!promoteToForegroundIfNeeded(initialNotification())) {
                    stopSessionHold()
                    return START_NOT_STICKY
                }
                acquireWakeLockIfNeeded()
                if (observeJob?.isActive != true) {
                    startObserving()
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        observeJob?.cancel()
        observeJob = null
        if (scopeDelegate.isInitialized()) scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Match the existing forwarding-service contract: an active hold is
        // controlled by the persistent notification / session close, not recents.
        super.onTaskRemoved(rootIntent)
    }

    private fun startObserving() {
        observeJob = scope.launch {
            controller.flowOfSnapshot()
                .collect { snapshot ->
                    if (!snapshot.isHoldingConnection) {
                        stopSessionHold()
                    } else {
                        updateNotification(snapshot)
                    }
                }
        }
    }

    private fun stopSessionHold() {
        observeJob?.cancel()
        observeJob = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        hasStartedForeground = false
        controller.onForegroundServiceStopped()
        releaseWakeLock()
        stopSelf()
    }

    private fun promoteToForegroundIfNeeded(notification: Notification): Boolean {
        if (hasStartedForeground) return true
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            hasStartedForeground = true
            controller.onForegroundServicePromoted()
            true
        }.getOrElse {
            Log.w(TAG, "session foreground service promotion failed", it)
            controller.onForegroundServiceStartFailed()
            false
        }
    }

    private fun updateNotification(snapshot: SessionConnectionSnapshot) {
        val notification = buildNotification(snapshot)
        if (!hasStartedForeground) {
            promoteToForegroundIfNeeded(notification)
            return
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun initialNotification(): Notification =
        buildNotification(SessionConnectionSnapshot.Empty, contentTextOverride = "Connecting session")

    @androidx.annotation.VisibleForTesting
    internal fun buildNotification(
        snapshot: SessionConnectionSnapshot,
        contentTextOverride: String? = null,
    ): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = Intent(this, SessionConnectionService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val detail = contentTextOverride ?: buildString {
            append("Keeping ")
            if (snapshot.primaryHostName.isNotEmpty()) {
                append(snapshot.primaryHostName)
                if (snapshot.liveSessionCount > 1) append(" + ${snapshot.liveSessionCount - 1} more")
            } else {
                append("session")
            }
            append(" connected in the background")
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Session connected")
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setSmallIcon(R.drawable.ic_stat_session)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                stopPendingIntent,
            )
            .build()

        notification.flags = notification.flags or
            Notification.FLAG_ONGOING_EVENT or
            Notification.FLAG_NO_CLEAR
        return notification
    }

    @androidx.annotation.VisibleForTesting
    internal fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "SSH sessions",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Always-on quiet status while an SSH session is connected"
            setShowBadge(true)
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
        }
        manager.createNotificationChannel(channel)
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLockIfNeeded() {
        val current = wakeLock
        if (current?.isHeld == true) return
        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) {
                runCatching { lock.release() }
                    .onFailure { Log.w(TAG, "session wakelock release failed", it) }
            }
        }
        wakeLock = null
    }
}
