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
import java.time.ZoneId

public interface UsageNotifier {
    public fun onSnapshotsChanged(snapshots: Map<Long, UsageSnapshot>)

    public object Noop : UsageNotifier {
        override fun onSnapshotsChanged(snapshots: Map<Long, UsageSnapshot>) = Unit
    }
}

public class DefaultUsageNotifier(
    context: Context,
    private val settingsRepository: SettingsRepository,
    private val stateStore: UsageNotificationStateStore,
    private val now: () -> Instant = { Instant.now() },
    private val zoneId: () -> ZoneId = { ZoneId.systemDefault() },
    private val poster: (UsageNotificationEvent) -> Unit = { event ->
        UsageNotifications.show(context.applicationContext, event)
    },
) : UsageNotifier {

    override fun onSnapshotsChanged(snapshots: Map<Long, UsageSnapshot>) {
        val warnPercent = settingsRepository.settings.value.usageWarnThresholdPercent.toDouble()
        // The persisted set is the source of truth for de-dupe so a crossing
        // notifies exactly once across process death (issue #619, D21
        // foreground-only). It is pruned each tick to only the crossings that
        // still warrant a warning so a cleared crossing re-arms.
        val alreadyNotified = stateStore.notifiedKeys()
        val current = mutableSetOf<UsageNotificationKey>()
        snapshots.values.forEach { snapshot ->
            if (snapshot !is UsageSnapshot.Records) return@forEach
            snapshot.records.forEach { record ->
                val state = record.thresholdState(warnPercent = warnPercent)
                if (!state.warrantsWarning) return@forEach
                val key = usageNotificationKey(snapshot.hostId, record, state)
                current += key
                if (key !in alreadyNotified) {
                    poster(
                        usageNotificationEvent(
                            record = record,
                            state = state,
                            warnPercent = warnPercent,
                            now = now(),
                            zoneId = zoneId(),
                            hostName = snapshot.hostName,
                            hostId = snapshot.hostId,
                            key = key,
                        ),
                    )
                }
            }
        }
        stateStore.setNotifiedKeys(current)
    }
}

public data class UsageNotificationEvent(
    val title: String,
    val text: String,
    val notificationId: Int,
    val key: UsageNotificationKey,
)

internal fun usageNotificationEvent(
    record: UsageProviderRecord,
    state: UsageThresholdState,
    warnPercent: Double,
    now: Instant = Instant.now(),
    zoneId: ZoneId = ZoneId.systemDefault(),
    hostName: String? = null,
    hostId: Long? = null,
    key: UsageNotificationKey = usageNotificationKey(hostId ?: 0L, record, state),
): UsageNotificationEvent {
    val percentLabel = record.mostConstrainedWindow?.percent?.let(::formatPercentUsed)
    val title = when (state) {
        UsageThresholdState.Approaching ->
            "${record.displayName} usage: ${percentLabel ?: formatPercentUsed(warnPercent)}"
        UsageThresholdState.Critical ->
            "${record.displayName} usage: ${percentLabel ?: formatPercentUsed(UsageProviderRecord.CRITICAL_PERCENT)}"
        UsageThresholdState.Exceeded ->
            "${record.displayName} ${exceededQuotaLabel(record)}"
        UsageThresholdState.Ok ->
            "${record.displayName} usage"
    }
    val resetText = record.mostConstrainedWindow?.resetAt?.let { reset ->
        "resets ${formatResetRelative(now, reset, zoneId)}"
    }
    val hostText = hostName?.trim()?.takeIf { it.isNotEmpty() }
    val stateText = when (state) {
        UsageThresholdState.Approaching -> "Approaching limit"
        UsageThresholdState.Critical -> "Critical usage"
        UsageThresholdState.Exceeded -> percentLabel ?: "Quota exceeded"
        UsageThresholdState.Ok -> null
    }
    val body = listOfNotNull(hostText, stateText, resetText, "Tap to open Usage.")
        .joinToString(" · ")
    return UsageNotificationEvent(
        title = title,
        text = body,
        notificationId = 27_000 + "${record.provider.lowercase()}:${hostId ?: 0L}".hashCode().and(0x0fff),
        key = key,
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
    // Issue #903: usage warnings are an ALERTING notification the maintainer
    // needs to notice (approaching/critical/exceeded quota), so the channel must
    // be IMPORTANCE_HIGH (sound + heads-up), not silent/DEFAULT. Channel
    // importance is immutable after first creation, so the previously-shipped
    // DEFAULT channel id is bumped (`_v2`) and the stale one deleted (hard-cut
    // D22) — raising importance in place is silently ignored on the installed app.
    private const val CHANNEL_ID: String = "usage_alerts_v2"
    private const val LEGACY_CHANNEL_ID: String = "usage_alerts"
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
            // Issue #903: HIGH priority so the alert heads-up on pre-O and matches
            // the HIGH channel importance — a usage warning should ping.
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(usagePendingIntent(appContext))
            .setDeleteIntent(dismissPendingIntent(appContext, event.key))
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

    /**
     * Delete-intent (issue #619): a user swipe durably records the crossing
     * as dismissed in the persistent store via
     * [UsageNotificationDismissReceiver], so a dismissed "quota exceeded"
     * notification does not re-fire until the crossing re-arms.
     */
    private fun dismissPendingIntent(
        context: Context,
        key: UsageNotificationKey,
    ): PendingIntent {
        val encoded = key.encode()
        val intent = Intent(context, UsageNotificationDismissReceiver::class.java)
            .setAction(UsageNotificationDismissReceiver.ACTION_DISMISS)
            .putExtra(UsageNotificationDismissReceiver.EXTRA_NOTIFICATION_KEY, encoded)
        return PendingIntent.getBroadcast(
            context,
            encoded.hashCode(),
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

    @androidx.annotation.VisibleForTesting
    internal fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        // Hard-cut (D22): drop the stale DEFAULT channel so the new HIGH channel
        // (#903) is created fresh — importance is immutable, so an in-place flip
        // on the old id is ignored on already-installed apps.
        runCatching { manager.deleteNotificationChannel(LEGACY_CHANNEL_ID) }
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
    }
}
