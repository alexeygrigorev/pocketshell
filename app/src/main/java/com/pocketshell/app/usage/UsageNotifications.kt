package com.pocketshell.app.usage

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
import com.pocketshell.app.settings.SettingsRepository
import com.pocketshell.core.usage.UsageProviderRecord
import com.pocketshell.core.usage.UsageThresholdState
import java.time.Instant

public interface UsageNotifier {
    public fun onSnapshotsChanged(snapshots: Map<Long, UsageSnapshot>)

    public object Noop : UsageNotifier {
        override fun onSnapshotsChanged(snapshots: Map<Long, UsageSnapshot>) = Unit
    }
}

public class DefaultUsageNotifier(
    context: Context,
    private val settingsRepository: SettingsRepository,
    private val poster: (UsageNotificationEvent) -> Unit = { event ->
        UsageNotifications.show(context.applicationContext, event)
    },
) : UsageNotifier {
    private var activeKeys: Set<UsageNotificationKey> = emptySet()

    override fun onSnapshotsChanged(snapshots: Map<Long, UsageSnapshot>) {
        val warnPercent = settingsRepository.settings.value.usageWarnThresholdPercent.toDouble()
        val current = mutableSetOf<UsageNotificationKey>()
        snapshots.values.forEach { snapshot ->
            if (snapshot !is UsageSnapshot.Records) return@forEach
            snapshot.records.forEach { record ->
                val state = record.thresholdState(warnPercent = warnPercent)
                if (!state.warrantsWarning) return@forEach
                val key = usageNotificationKey(snapshot.hostId, record, state)
                current += key
                if (key !in activeKeys) {
                    poster(usageNotificationEvent(record, state, warnPercent))
                }
            }
        }
        activeKeys = current
    }
}

public data class UsageNotificationEvent(
    val title: String,
    val text: String,
    val notificationId: Int,
)

private data class UsageNotificationKey(
    val hostId: Long,
    val provider: String,
    val state: UsageThresholdState,
    val windowName: String?,
    val resetAt: Instant?,
)

internal fun usageNotificationEvent(
    record: UsageProviderRecord,
    state: UsageThresholdState,
    warnPercent: Double,
): UsageNotificationEvent {
    val title = when (state) {
        UsageThresholdState.Approaching ->
            "${record.displayName} usage reached ${formatPercent(warnPercent)}"
        UsageThresholdState.Critical ->
            "${record.displayName} usage reached ${formatPercent(UsageProviderRecord.CRITICAL_PERCENT)}"
        UsageThresholdState.Exceeded ->
            "${record.displayName} ${exceededQuotaLabel(record)}"
        UsageThresholdState.Ok ->
            "${record.displayName} usage"
    }
    return UsageNotificationEvent(
        title = title,
        text = "Tap to open Usage.",
        notificationId = 27_000 + record.provider.lowercase().hashCode().and(0x0fff),
    )
}

private fun usageNotificationKey(
    hostId: Long,
    record: UsageProviderRecord,
    state: UsageThresholdState,
): UsageNotificationKey {
    val window = record.mostConstrainedWindow
    return UsageNotificationKey(
        hostId = hostId,
        provider = record.provider.lowercase(),
        state = state,
        windowName = window?.name,
        resetAt = window?.resetAt,
    )
}

private fun exceededQuotaLabel(record: UsageProviderRecord): String {
    val reason = record.blockReason.orEmpty().lowercase()
    val window = record.mostConstrainedWindow?.name.orEmpty().lowercase()
    val weekly = listOf("weekly", "week", "long_term", "long term", "7d", "seven_day", "seven day", "secondary")
        .any { it in reason || it in window }
    return if (weekly) "weekly quota exceeded" else "quota exceeded"
}

internal object UsageNotifications {
    private const val CHANNEL_ID: String = "usage_alerts"
    private const val CHANNEL_NAME: String = "Usage alerts"

    fun show(context: Context, event: UsageNotificationEvent) {
        val appContext = context.applicationContext
        ensureChannel(appContext)
        if (!canPostNotifications(appContext)) return

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_qs_forwarding)
            .setContentTitle(event.title)
            .setContentText(event.text)
            .setAutoCancel(true)
            .setContentIntent(usagePendingIntent(appContext))
            .build()

        appContext.getSystemService(NotificationManager::class.java)
            .notify(event.notificationId, notification)
    }

    private fun usagePendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_USAGE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            27_001,
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
