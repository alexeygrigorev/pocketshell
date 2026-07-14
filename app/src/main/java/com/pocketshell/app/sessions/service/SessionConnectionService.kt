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
import com.pocketshell.app.diagnostics.DiagnosticEvents
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

        /**
         * Issue #1595: the connection-trail event recording every session-FGS start/promotion
         * outcome. Category `connection` so it lands on the same trail the device-log audit
         * reads (mirrors [com.pocketshell.core.tmux.TmuxClientDiagnostics], which the App wires
         * into `DiagnosticEvents.record("connection", …)`).
         */
        const val DIAG_EVENT_FGS = "session_fgs"

        /**
         * Issue #1595: test seam for the `startForegroundService()` call so a JVM test can inject
         * a background-restricted rejection (the on-device
         * `ForegroundServiceStartNotAllowedException`). Null → the real platform call.
         */
        @androidx.annotation.VisibleForTesting
        internal var startForegroundServiceForTest: ((Context, Intent) -> Unit)? = null

        fun start(context: Context, holdActive: Boolean = true): Boolean {
            val intent = Intent(context, SessionConnectionService::class.java).apply {
                action = ACTION_START
            }
            return runCatching {
                startForegroundServiceForTest?.invoke(context, intent)
                    ?: ContextCompat.startForegroundService(context, intent)
                // Issue #1595: record the SUCCESSFUL request too, so a device background proves
                // the foreground-eligible start actually fired (the audit could not see it —
                // both failure paths were swallowed with a bare Log.w and NO DiagnosticEvent).
                DiagnosticEvents.record(
                    "connection",
                    DIAG_EVENT_FGS,
                    "phase" to "request",
                    "outcome" to "ok",
                    "hold_active" to holdActive,
                )
                true
            }.getOrElse {
                // Issue #1595: was a swallowed Log.w. Emit a DiagnosticEvent capturing the
                // exception CLASS (`ForegroundServiceStartNotAllowedException` vs a real socket
                // error) so the connection-log can attribute the ~4.4s-after-background transport
                // death to the background-FGS-start restriction instead of staying structurally
                // blind to it.
                Log.w(TAG, "session foreground service start was rejected", it)
                DiagnosticEvents.record(
                    "connection",
                    DIAG_EVENT_FGS,
                    "phase" to "request",
                    "outcome" to "denied",
                    "error" to it.javaClass.simpleName,
                    "hold_active" to holdActive,
                )
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
        releaseWakeLock()
        stopSelf()
    }

    /**
     * Issue #1595: test seam for the `startForeground()` promotion so a JVM test can inject the
     * background-restricted `ForegroundServiceStartNotAllowedException` thrown at promotion time
     * (Android 12+; 14+ adds specialUse throw conditions). Null → the real platform call.
     */
    @androidx.annotation.VisibleForTesting
    internal var promoteForegroundForTest: ((Notification) -> Unit)? = null

    private fun promoteToForegroundIfNeeded(notification: Notification): Boolean {
        if (hasStartedForeground) return true
        return runCatching {
            val promoter = promoteForegroundForTest
            if (promoter != null) {
                promoter(notification)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            hasStartedForeground = true
            DiagnosticEvents.record(
                "connection",
                DIAG_EVENT_FGS,
                "phase" to "promote",
                "outcome" to "ok",
            )
            true
        }.getOrElse {
            // Issue #1595: was a swallowed Log.w. Emit a DiagnosticEvent with the exception CLASS
            // so a device background captures whether promotion was rejected by the
            // background-FGS-start restriction (`ForegroundServiceStartNotAllowedException`) —
            // previously invisible to the connection-log.
            Log.w(TAG, "session foreground service promotion failed", it)
            DiagnosticEvents.record(
                "connection",
                DIAG_EVENT_FGS,
                "phase" to "promote",
                "outcome" to "denied",
                "error" to it.javaClass.simpleName,
            )
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
        nowMillis: Long = System.currentTimeMillis(),
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

        val hostLabel = buildString {
            if (snapshot.primaryHostName.isNotEmpty()) {
                append(snapshot.primaryHostName)
                if (snapshot.liveSessionCount > 1) append(" + ${snapshot.liveSessionCount - 1} more")
            } else {
                append("session")
            }
        }

        // Issue #1123: while backgrounded within the grace window the notification shows a
        // LIVE count-down to disconnect. The SYSTEM renders the MM:SS via a count-down
        // chronometer anchored on the wall-clock deadline (`when`) — the app posts the
        // notification ONCE and never schedules a per-second update / wakeup. A
        // contentTextOverride (the initial "Connecting session") never counts down.
        //
        // Issue #1440: the count-down chronometer is used ONLY while the deadline is STRICTLY in
        // the FUTURE (Phase.HOLDING_GRACE). Once grace has elapsed the system chronometer would
        // otherwise count PAST ZERO into a NEGATIVE timer (the reported −06:51), and the frozen
        // "Session connected / disconnecting in" copy would misrepresent a connection that has
        // actually moved on to reconnecting. So a past/elapsed deadline (or the controller's
        // explicit reconnecting flag) resolves to Phase.RECONNECTING: reconnecting copy, NO
        // count-down. [SessionServiceController] re-posts at the deadline so this flip happens
        // at the right moment instead of the notification drifting negative.
        //
        // Issue #1202 + #1198 (hard-cut, D22): this session FGS is SUPPRESSED while a
        // port-forward is active (see [SessionServiceController.setPortForwardActive]) — the
        // ForwardingService FGS is the single owner of the port-forward notification. So this
        // notification NEVER renders the port-forward wording anymore; the #1159 Part 3
        // "Port forwarding active" branch is deleted.
        val phase = if (contentTextOverride == null) snapshot.phaseAt(nowMillis) else null

        val countdownDeadline =
            if (phase == SessionConnectionSnapshot.Phase.HOLDING_GRACE) {
                snapshot.disconnectAtWallClockMillis
            } else {
                null
            }

        val title = when (phase) {
            SessionConnectionSnapshot.Phase.RECONNECTING -> "Reconnecting…"
            else -> "Session connected"
        }

        val detail = contentTextOverride
            ?: when (phase) {
                SessionConnectionSnapshot.Phase.HOLDING_GRACE -> "Holding $hostLabel — disconnecting in"
                SessionConnectionSnapshot.Phase.RECONNECTING -> "Reconnecting to $hostLabel…"
                else -> "Keeping $hostLabel connected in the background"
            }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
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

        if (countdownDeadline != null) {
            builder
                .setWhen(countdownDeadline)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
        } else {
            builder.setShowWhen(false)
        }

        val notification = builder.build()

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
