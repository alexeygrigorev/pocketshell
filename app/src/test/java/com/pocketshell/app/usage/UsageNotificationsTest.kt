package com.pocketshell.app.usage

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.MainActivity
import com.pocketshell.app.initialDestinationFromIntent
import com.pocketshell.app.nav.AppDestination
import com.pocketshell.app.settings.SettingsRepository
import com.pocketshell.core.usage.UsageProviderRecord
import com.pocketshell.core.usage.UsageStatus
import com.pocketshell.core.usage.UsageThresholdState
import com.pocketshell.core.usage.UsageWindow
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class UsageNotificationsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun approachingUsageNotificationUsesConfiguredThresholdCopy() {
        val event = usageNotificationEvent(
            record = UsageProviderRecord(
                provider = "codex",
                status = UsageStatus.Ok,
                rawStatus = "ok",
                windows = listOf(UsageWindow("7d", 80.0, 100.0, "percent", null)),
            ),
            state = UsageThresholdState.Approaching,
            warnPercent = 80.0,
        )

        assertEquals("Codex usage reached 80%", event.title)
        assertFalse(event.title.contains("blocked", ignoreCase = true))
    }

    @Test
    fun exceededWeeklyNotificationAvoidsBlockedWording() {
        val event = usageNotificationEvent(
            record = UsageProviderRecord(
                provider = "codex",
                status = UsageStatus.Blocked,
                rawStatus = "quota_exhausted",
                blockReason = "codex quota exhausted (weekly window at 80%)",
                windows = listOf(UsageWindow("7d", 100.0, 100.0, "percent", null)),
            ),
            state = UsageThresholdState.Exceeded,
            warnPercent = 80.0,
        )

        assertEquals("Codex weekly quota exceeded", event.title)
        assertFalse(event.title.contains("provider " + "blocked", ignoreCase = true))
        assertFalse(event.title.contains("blocked", ignoreCase = true))
    }

    @Test
    fun criticalUsageNotificationUsesCriticalThresholdCopy() {
        val event = usageNotificationEvent(
            record = UsageProviderRecord(
                provider = "claude",
                status = UsageStatus.Ok,
                rawStatus = "ok",
                windows = listOf(UsageWindow("5h", 96.0, 100.0, "percent", null)),
            ),
            state = UsageThresholdState.Critical,
            warnPercent = 80.0,
        )

        assertEquals("Claude Code usage reached 95%", event.title)
        assertFalse(event.title.contains("provider " + "blocked", ignoreCase = true))
        assertFalse(event.title.contains("blocked", ignoreCase = true))
    }

    @Test
    fun defaultNotifierDedupesUntilSeverityChanges() {
        val events = mutableListOf<UsageNotificationEvent>()
        val notifier = DefaultUsageNotifier(
            context = context,
            settingsRepository = SettingsRepository(context),
            poster = { events += it },
        )
        val approaching = UsageSnapshot.Records(
            hostId = 1L,
            hostName = "agent-box",
            records = listOf(
                UsageProviderRecord(
                    provider = "codex",
                    status = UsageStatus.Ok,
                    rawStatus = "ok",
                    windows = listOf(UsageWindow("7d", 80.0, 100.0, "percent", null)),
                ),
            ),
            fetchedAt = Instant.parse("2026-06-08T10:00:00Z"),
            command = UsageRemoteSource.defaultUsageCommand,
        )
        val exceeded = approaching.copy(
            records = listOf(
                UsageProviderRecord(
                    provider = "codex",
                    status = UsageStatus.Blocked,
                    rawStatus = "quota_exhausted",
                    blockReason = "codex quota exhausted (weekly window at 80%)",
                    windows = listOf(UsageWindow("7d", 100.0, 100.0, "percent", null)),
                ),
            ),
        )

        notifier.onSnapshotsChanged(mapOf(1L to approaching))
        notifier.onSnapshotsChanged(mapOf(1L to approaching))
        notifier.onSnapshotsChanged(mapOf(1L to exceeded))

        assertEquals(
            listOf("Codex usage reached 80%", "Codex weekly quota exceeded"),
            events.map { it.title },
        )
    }

    @Test
    fun defaultNotifierRenotifiesAfterThresholdDropsThenCrossesAgain() {
        val events = mutableListOf<UsageNotificationEvent>()
        val notifier = DefaultUsageNotifier(
            context = context,
            settingsRepository = SettingsRepository(context),
            poster = { events += it },
        )

        notifier.onSnapshotsChanged(mapOf(1L to snapshot(percent = 80.0)))
        notifier.onSnapshotsChanged(mapOf(1L to snapshot(percent = 70.0)))
        notifier.onSnapshotsChanged(mapOf(1L to snapshot(percent = 80.0)))

        assertEquals(
            listOf("Codex usage reached 80%", "Codex usage reached 80%"),
            events.map { it.title },
        )
    }

    @Test
    fun defaultNotifierRenotifiesWhenSameSeverityWindowMovesToNextReset() {
        val events = mutableListOf<UsageNotificationEvent>()
        val notifier = DefaultUsageNotifier(
            context = context,
            settingsRepository = SettingsRepository(context),
            poster = { events += it },
        )

        notifier.onSnapshotsChanged(
            mapOf(
                1L to snapshot(
                    percent = 80.0,
                    resetAt = Instant.parse("2026-06-08T15:00:00Z"),
                ),
            ),
        )
        notifier.onSnapshotsChanged(
            mapOf(
                1L to snapshot(
                    percent = 80.0,
                    resetAt = Instant.parse("2026-06-08T15:00:00Z"),
                ),
            ),
        )
        notifier.onSnapshotsChanged(
            mapOf(
                1L to snapshot(
                    percent = 80.0,
                    resetAt = Instant.parse("2026-06-09T15:00:00Z"),
                ),
            ),
        )

        assertEquals(
            listOf("Codex usage reached 80%", "Codex usage reached 80%"),
            events.map { it.title },
        )
    }

    @Test
    fun notificationUsageExtraDeepLinksToUsageDestination() {
        val intent = Intent().putExtra(MainActivity.EXTRA_OPEN_USAGE, true)

        assertEquals(AppDestination.Usage, initialDestinationFromIntent(intent))
    }

    private fun snapshot(
        percent: Double,
        resetAt: Instant? = null,
    ): UsageSnapshot.Records = UsageSnapshot.Records(
        hostId = 1L,
        hostName = "agent-box",
        records = listOf(
            UsageProviderRecord(
                provider = "codex",
                status = if (percent >= 100.0) UsageStatus.Blocked else UsageStatus.Ok,
                rawStatus = if (percent >= 100.0) "quota_exhausted" else "ok",
                windows = listOf(UsageWindow("7d", percent, 100.0, "percent", resetAt)),
            ),
        ),
        fetchedAt = Instant.parse("2026-06-08T10:00:00Z"),
        command = UsageRemoteSource.defaultUsageCommand,
    )
}
