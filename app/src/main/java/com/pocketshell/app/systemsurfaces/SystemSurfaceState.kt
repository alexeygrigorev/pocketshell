package com.pocketshell.app.systemsurfaces

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

data class SessionWidgetState(
    val activeSessionCount: Int,
)

data class BootForwardingStatus(
    val requested: Boolean,
    val lastMessage: String?,
)

class SystemSurfaceStateStore(
    context: Context,
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun readSessionWidgetState(): SessionWidgetState =
        SessionWidgetState(
            activeSessionCount = prefs.getInt(KEY_ACTIVE_SESSION_COUNT, 0).coerceAtLeast(0),
        )

    fun readBootForwardingStatus(): BootForwardingStatus =
        BootForwardingStatus(
            requested = prefs.getBoolean(KEY_BOOT_FORWARDING_REQUESTED, false),
            lastMessage = prefs.getString(KEY_BOOT_FORWARDING_MESSAGE, null),
        )

    fun setActiveSessionCount(count: Int) {
        prefs.edit()
            .putInt(KEY_ACTIVE_SESSION_COUNT, count.coerceAtLeast(0))
            .apply()
    }

    fun recordBootForwardingRequest(message: String) {
        prefs.edit()
            .putBoolean(KEY_BOOT_FORWARDING_REQUESTED, true)
            .putString(KEY_BOOT_FORWARDING_MESSAGE, message)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "system_surfaces"
        const val KEY_ACTIVE_SESSION_COUNT = "active_session_count"
        const val KEY_BOOT_FORWARDING_REQUESTED = "boot_forwarding_requested"
        const val KEY_BOOT_FORWARDING_MESSAGE = "boot_forwarding_message"
    }
}

fun activeSessionCountText(count: Int): String =
    when (val safeCount = count.coerceAtLeast(0)) {
        1 -> "1 active session"
        else -> "$safeCount active sessions"
    }

fun bootForwardingMessage(enabledHostCount: Int): String =
    when (val safeCount = enabledHostCount.coerceAtLeast(0)) {
        0 -> "No enabled forwarding hosts queued for restore"
        1 -> "Forwarding restore pending for enabled hosts"
        else -> "Forwarding restore pending for $safeCount enabled hosts"
    }

object PendingBootForwardingNotification {
    private const val CHANNEL_ID = "port_forwarding_restore"
    private const val NOTIFICATION_ID = 26_003

    fun show(context: Context, message: String = "Forwarding restore is pending. Tap to reopen forwarding.") {
        val appContext = context.applicationContext
        if (!canPostNotifications(appContext)) return
        ensureChannel(appContext)
        val openIntent = Intent(appContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(ForwardingTileService.EXTRA_OPEN_PORT_FORWARDING, true)
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_qs_forwarding)
            .setContentTitle("PocketShell forwarding")
            .setContentText(message)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    appContext,
                    0,
                    openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

        appContext.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification)
    }

    private fun canPostNotifications(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Port forwarding restore",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
    }
}
