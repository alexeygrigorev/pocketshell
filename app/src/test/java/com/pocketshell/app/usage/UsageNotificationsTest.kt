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
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    /**
     * In-memory [UsageNotificationStateStore] standing in for the real
     * SharedPreferences-backed store. A single instance shared across two
     * notifiers simulates persistence surviving a process restart.
     */
    private class FakeStateStore : UsageNotificationStateStore {
        private var keys: Set<UsageNotificationKey> = emptySet()

        override fun notifiedKeys(): Set<UsageNotificationKey> = keys

        override fun setNotifiedKeys(keys: Set<UsageNotificationKey>) {
            this.keys = keys
        }
    }

    @Test
    fun approachingUsageNotificationUsesConfiguredThresholdCopy() {
        val event = usageNotificationEvent(
            record = UsageProviderRecord(
                provider = "codex",
                status = UsageStatus.Ok,
                rawStatus = "ok",
                windows = listOf(
                    UsageWindow(
                        "7d",
                        86.0,
                        100.0,
                        "percent",
                        Instant.parse("2026-06-08T12:00:00Z"),
                    ),
                ),
            ),
            state = UsageThresholdState.Approaching,
            warnPercent = 80.0,
            now = Instant.parse("2026-06-08T10:00:00Z"),
            zoneId = ZoneId.of("UTC"),
        )

        assertEquals("Codex usage: 86% used", event.title)
        assertEquals(
            "Approaching limit · resets in 2h · Mon Jun 8, 12:00 · Tap to open Usage.",
            event.text,
        )
        assertFalse(event.title.contains("blocked", ignoreCase = true))
        assertFalse(event.text.contains("blocked", ignoreCase = true))
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
        assertEquals("100% used · Tap to open Usage.", event.text)
        assertFalse(event.title.contains("provider " + "blocked", ignoreCase = true))
        assertFalse(event.title.contains("blocked", ignoreCase = true))
        assertFalse(event.text.contains("provider " + "blocked", ignoreCase = true))
        assertFalse(event.text.contains("blocked", ignoreCase = true))
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

        assertEquals("Claude Code usage: 96% used", event.title)
        assertEquals("Critical usage · Tap to open Usage.", event.text)
        assertFalse(event.title.contains("provider " + "blocked", ignoreCase = true))
        assertFalse(event.title.contains("blocked", ignoreCase = true))
    }

    @Test
    fun defaultNotifierDedupesUntilSeverityChanges() {
        val events = mutableListOf<UsageNotificationEvent>()
        val notifier = DefaultUsageNotifier(
            context = context,
            settingsRepository = SettingsRepository(context),
            stateStore = FakeStateStore(),
            zoneId = { ZoneId.of("UTC") },
            now = { Instant.parse("2026-06-08T10:00:00Z") },
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
            listOf("Codex usage: 80% used", "Codex weekly quota exceeded"),
            events.map { it.title },
        )
        assertEquals(
            listOf(
                "agent-box · Approaching limit · Tap to open Usage.",
                "agent-box · 100% used · Tap to open Usage.",
            ),
            events.map { it.text },
        )
    }

    @Test
    fun defaultNotifierRenotifiesAfterThresholdDropsThenCrossesAgain() {
        val events = mutableListOf<UsageNotificationEvent>()
        val notifier = DefaultUsageNotifier(
            context = context,
            settingsRepository = SettingsRepository(context),
            stateStore = FakeStateStore(),
            zoneId = { ZoneId.of("UTC") },
            poster = { events += it },
        )

        notifier.onSnapshotsChanged(mapOf(1L to snapshot(percent = 80.0)))
        notifier.onSnapshotsChanged(mapOf(1L to snapshot(percent = 70.0)))
        notifier.onSnapshotsChanged(mapOf(1L to snapshot(percent = 80.0)))

        assertEquals(
            listOf("Codex usage: 80% used", "Codex usage: 80% used"),
            events.map { it.title },
        )
    }

    @Test
    fun defaultNotifierDoesNotRenotifyWhenSameSeverityWindowOnlyMovesReset() {
        val events = mutableListOf<UsageNotificationEvent>()
        val notifier = DefaultUsageNotifier(
            context = context,
            settingsRepository = SettingsRepository(context),
            stateStore = FakeStateStore(),
            zoneId = { ZoneId.of("UTC") },
            now = { Instant.parse("2026-06-08T10:00:00Z") },
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
            listOf("Codex usage: 80% used"),
            events.map { it.title },
        )
        assertEquals(
            listOf(
                "agent-box · Approaching limit · resets in 5h · Mon Jun 8, 15:00 · Tap to open Usage.",
            ),
            events.map { it.text },
        )
    }

    @Test
    fun notificationResetCopyIncludesRelativeCountdownAndAbsoluteTime() {
        val now = Instant.parse("2026-07-15T18:59:00Z")
        val event = usageNotificationEvent(
            record = UsageProviderRecord(
                provider = "codex",
                status = UsageStatus.Ok,
                rawStatus = "ok",
                windows = listOf(
                    UsageWindow(
                        "7d",
                        86.0,
                        100.0,
                        "percent",
                        Instant.parse("2026-07-16T17:59:00Z"),
                    ),
                ),
            ),
            state = UsageThresholdState.Approaching,
            warnPercent = 80.0,
            now = now,
            zoneId = ZoneId.of("UTC"),
        )

        assertEquals(
            "Approaching limit · resets in 23h · Thu Jul 16, 17:59 · Tap to open Usage.",
            event.text,
        )
    }

    @Test
    fun notificationResetCountdownCoversMinuteHourDayBoundariesFromFixedNow() {
        val now = Instant.parse("2026-07-15T12:00:00Z")
        val cases = listOf(
            Instant.parse("2026-07-15T12:30:00Z") to "in 30m",
            Instant.parse("2026-07-15T17:45:00Z") to "in 5h 45m",
            Instant.parse("2026-07-16T12:00:00Z") to "in 1 day",
            Instant.parse("2026-07-18T12:00:00Z") to "in 3 days",
        )

        cases.forEach { (resetAt, expectedRelative) ->
            val event = notificationEventForProvider("codex", now, resetAt)
            assertTrue(
                "expected '$expectedRelative' in notification body '${event.text}'",
                event.text.contains("resets $expectedRelative · "),
            )
        }
    }

    @Test
    fun codexClaudeAndCopilotNotificationsUseTheSameRelativeAndAbsoluteResetCopy() {
        val now = Instant.parse("2026-07-15T12:00:00Z")
        val resetAt = Instant.parse("2026-07-16T12:00:00Z")

        listOf("codex", "claude", "copilot").forEach { provider ->
            val event = notificationEventForProvider(provider, now, resetAt)
            assertTrue(
                "$provider notification must use shared relative + absolute copy: ${event.text}",
                event.text.contains("resets in 1 day · Thu Jul 16, 12:00"),
            )
        }
    }

    @Test
    fun resetClearsCrossingCancelsStaleNotificationAndReArmsLedger() {
        // Issue #1441 (stale lingering): a warning is posted, then the quota
        // RESETS (record no longer warrants a warning). The stale tray
        // notification must be cancelled — not left with a now-false "resets …"
        // line — and the persisted ledger entry cleared so a later breach
        // re-notifies. On base (no cancel path) the notification lingers.
        val store = FakeStateStore()
        val cancelled = mutableListOf<Int>()
        val events = mutableListOf<UsageNotificationEvent>()

        // T0: breach -> posts, records the crossing.
        notifier(store, cancelled) { events += it }
            .onSnapshotsChanged(mapOf(1L to exceededSnapshot()))
        assertEquals(1, events.size)
        val postedId = events.single().notificationId
        assertEquals(setOf(events.single().key), store.notifiedKeys())

        // T0+: quota reset -> provider back below threshold (no warning).
        notifier(store, cancelled) { events += it }
            .onSnapshotsChanged(mapOf(1L to snapshot(percent = 12.0)))

        // The stale notification is cancelled and the ledger re-armed.
        assertEquals(listOf(postedId), cancelled)
        assertEquals(emptySet<UsageNotificationKey>(), store.notifiedKeys())

        // A later genuine breach re-notifies (ledger really cleared).
        val reNotify = mutableListOf<UsageNotificationEvent>()
        notifier(store, cancelled) { reNotify += it }
            .onSnapshotsChanged(mapOf(1L to exceededSnapshot()))
        assertEquals(listOf("Codex weekly quota exceeded"), reNotify.map { it.title })
    }

    @Test
    fun crossingIntoHigherSeverityRePostsAndDoesNotCancelFreshNotification() {
        // Class coverage: Approaching -> Exceeded on the SAME host+provider
        // re-posts the new severity and reuses the SAME notificationId. The
        // cleared Approaching key must NOT cancel the freshly-posted Exceeded
        // notification (same slot).
        val store = FakeStateStore()
        val cancelled = mutableListOf<Int>()
        val events = mutableListOf<UsageNotificationEvent>()

        notifier(store, cancelled) { events += it }
            .onSnapshotsChanged(mapOf(1L to snapshot(percent = 80.0)))
        notifier(store, cancelled) { events += it }
            .onSnapshotsChanged(mapOf(1L to exceededSnapshot()))

        assertEquals(
            listOf("Codex usage: 80% used", "Codex weekly quota exceeded"),
            events.map { it.title },
        )
        // Same slot reused; the re-post replaces it, nothing is cancelled.
        assertEquals(events[0].notificationId, events[1].notificationId)
        assertEquals(emptyList<Int>(), cancelled)
    }

    @Test
    fun stillInSameWarningDoesNotRePostOrCancel() {
        // Class coverage: an unchanged same-severity crossing across ticks must
        // neither re-post (existing de-dupe) nor cancel the live notification.
        val store = FakeStateStore()
        val cancelled = mutableListOf<Int>()
        val events = mutableListOf<UsageNotificationEvent>()

        repeat(3) {
            notifier(store, cancelled) { events += it }
                .onSnapshotsChanged(mapOf(1L to exceededSnapshot()))
        }

        assertEquals(listOf("Codex weekly quota exceeded"), events.map { it.title })
        assertEquals(emptyList<Int>(), cancelled)
    }

    @Test
    fun clearingCrossingWithUnknownResetTimeStillCancels() {
        // Class coverage (missing-data): a provider with NO reset_at still posts
        // a warning (body omits the reset line), and clearing it must still
        // cancel the stale notification — the cancel path must not depend on a
        // reset time being present.
        val store = FakeStateStore()
        val cancelled = mutableListOf<Int>()
        val events = mutableListOf<UsageNotificationEvent>()

        // exceededSnapshot() has a null reset_at window.
        notifier(store, cancelled) { events += it }
            .onSnapshotsChanged(mapOf(1L to exceededSnapshot()))
        assertEquals(1, events.size)
        assertFalse(
            "no reset line when reset_at is unknown: ${events.single().text}",
            events.single().text.contains("resets"),
        )
        val postedId = events.single().notificationId

        notifier(store, cancelled) { events += it }
            .onSnapshotsChanged(mapOf(1L to snapshot(percent = 5.0)))

        assertEquals(listOf(postedId), cancelled)
        assertEquals(emptySet<UsageNotificationKey>(), store.notifiedKeys())
    }

    @Test
    fun notificationUsageExtraDeepLinksToUsageDestination() {
        val intent = Intent().putExtra(MainActivity.EXTRA_OPEN_USAGE, true)

        assertEquals(AppDestination.Usage, initialDestinationFromIntent(intent))
    }

    @Test
    fun exceededCrossingDoesNotReNotifyAfterProcessRestart() {
        // Issue #619: a persistent store shared across two notifier instances
        // simulates process death + recreation (D21 foreground-only). The same
        // Exceeded snapshot must NOT re-post on the recreated process.
        val store = FakeStateStore()
        val exceeded = exceededSnapshot()

        val firstEvents = mutableListOf<UsageNotificationEvent>()
        notifier(store) { firstEvents += it }
            .onSnapshotsChanged(mapOf(1L to exceeded))
        assertEquals(listOf("Codex weekly quota exceeded"), firstEvents.map { it.title })

        // Fresh notifier, same persistent store == process restart.
        val secondEvents = mutableListOf<UsageNotificationEvent>()
        notifier(store) { secondEvents += it }
            .onSnapshotsChanged(mapOf(1L to exceeded))

        assertEquals(emptyList<String>(), secondEvents.map { it.title })
    }

    @Test
    fun exceededCrossingReArmsAcrossRestartAfterReset() {
        // After notifying, a sync where the provider is back below threshold
        // prunes the persisted key; a later genuine crossing re-posts even on a
        // freshly recreated process.
        val store = FakeStateStore()

        val first = mutableListOf<UsageNotificationEvent>()
        notifier(store) { first += it }
            .onSnapshotsChanged(mapOf(1L to exceededSnapshot()))
        assertEquals(1, first.size)

        // Restart + below-threshold sync clears the crossing.
        notifier(store) {}.onSnapshotsChanged(mapOf(1L to snapshot(percent = 70.0)))

        // Restart + crosses again -> re-posts.
        val third = mutableListOf<UsageNotificationEvent>()
        notifier(store) { third += it }
            .onSnapshotsChanged(mapOf(1L to exceededSnapshot()))

        assertEquals(listOf("Codex weekly quota exceeded"), third.map { it.title })
    }

    @Test
    fun dismissalSuppressesReNotifyForSameCrossing() {
        // A user swipe records the crossing as dismissed in the persistent
        // store; the same Exceeded snapshot must not re-post afterwards.
        val store = FakeStateStore()
        val exceeded = exceededSnapshot()

        val events = mutableListOf<UsageNotificationEvent>()
        val first = notifier(store) { events += it }
        first.onSnapshotsChanged(mapOf(1L to exceeded))
        assertEquals(1, events.size)

        // Simulate the delete-intent: record the posted crossing as dismissed.
        val dismissedKey = events.single().key
        store.setNotifiedKeys(store.notifiedKeys() + dismissedKey)

        // Same crossing on a recreated process must stay suppressed.
        val afterDismiss = mutableListOf<UsageNotificationEvent>()
        notifier(store) { afterDismiss += it }
            .onSnapshotsChanged(mapOf(1L to exceeded))

        assertEquals(emptyList<String>(), afterDismiss.map { it.title })
    }

    @Test
    fun dismissReceiverRecordsCrossingInStore() {
        val store = FakeStateStore()
        UsageNotificationDismissReceiver.storeFactory = { store }
        try {
            val key = UsageNotificationKey(
                hostId = 1L,
                provider = "codex",
                state = UsageThresholdState.Exceeded,
                windowName = "7d",
            )
            val intent = Intent(UsageNotificationDismissReceiver.ACTION_DISMISS)
                .putExtra(
                    UsageNotificationDismissReceiver.EXTRA_NOTIFICATION_KEY,
                    key.encode(),
                )

            UsageNotificationDismissReceiver().onReceive(context, intent)

            assertEquals(setOf(key), store.notifiedKeys())
        } finally {
            UsageNotificationDismissReceiver.storeFactory = { ctx ->
                SharedPreferencesUsageNotificationStateStore(ctx)
            }
        }
    }

    private fun notifier(
        store: UsageNotificationStateStore,
        cancelled: MutableList<Int> = mutableListOf(),
        poster: (UsageNotificationEvent) -> Unit,
    ): DefaultUsageNotifier = DefaultUsageNotifier(
        context = context,
        settingsRepository = SettingsRepository(context),
        stateStore = store,
        zoneId = { ZoneId.of("UTC") },
        now = { Instant.parse("2026-06-08T10:00:00Z") },
        poster = poster,
        canceller = { cancelled += it },
    )

    private fun exceededSnapshot(): UsageSnapshot.Records = UsageSnapshot.Records(
        hostId = 1L,
        hostName = "agent-box",
        records = listOf(
            UsageProviderRecord(
                provider = "codex",
                status = UsageStatus.Blocked,
                rawStatus = "quota_exhausted",
                blockReason = "codex quota exhausted (weekly window at 80%)",
                windows = listOf(UsageWindow("7d", 100.0, 100.0, "percent", null)),
            ),
        ),
        fetchedAt = Instant.parse("2026-06-08T10:00:00Z"),
        command = UsageRemoteSource.defaultUsageCommand,
    )

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

    private fun notificationEventForProvider(
        provider: String,
        now: Instant,
        resetAt: Instant,
    ): UsageNotificationEvent = usageNotificationEvent(
        record = UsageProviderRecord(
            provider = provider,
            status = UsageStatus.Ok,
            rawStatus = "ok",
            windows = listOf(UsageWindow("7d", 86.0, 100.0, "percent", resetAt)),
        ),
        state = UsageThresholdState.Approaching,
        warnPercent = 80.0,
        now = now,
        zoneId = ZoneId.of("UTC"),
    )
}
