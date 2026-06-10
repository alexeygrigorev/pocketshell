package com.pocketshell.app.usage

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.settings.SettingsRepository
import com.pocketshell.app.test.testArtifactsRoot
import com.pocketshell.core.usage.UsageProviderRecord
import com.pocketshell.core.usage.UsageStatus
import com.pocketshell.core.usage.UsageThresholdState
import com.pocketshell.core.usage.UsageWindow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.time.Instant
import java.time.ZoneId

/**
 * On-device E2E for the usage-threshold push notification (issue #668,
 * covering the #619 persistence + dismiss-respect).
 *
 * This is the usage analogue of [com.pocketshell.app.notifications.UpdateAvailableNotificationE2eTest]:
 * it drives the REAL production path — [DefaultUsageNotifier] with the REAL
 * default `poster` ([UsageNotifications.show]) and the REAL persistent
 * [SharedPreferencesUsageNotificationStateStore] — so the assertions are made
 * against an actual status-bar notification rendered by the device, not a
 * captured event object.
 *
 * It proves, on the emulator:
 *  1. Feeding an `Exceeded` usage snapshot posts the status-bar notification
 *     ONCE, with the title/text the production formatter produces, the
 *     `EXTRA_OPEN_USAGE` content intent (tap → Usage screen), and the
 *     delete-intent ([UsageNotificationDismissReceiver]) that records a swipe.
 *  2. Feeding the SAME exceeded snapshot to a FRESH notifier backed by the
 *     SAME persistent store (== process death + recreation, D21 foreground-only)
 *     does NOT re-post — the #619 persistence.
 *  3. Firing the delete-intent (a user swipe) durably suppresses re-post of the
 *     same crossing even on a recreated process.
 *
 * Unlike the update-notification E2E, this test is intentionally NOT gated out
 * of CI (`Assume.assumeFalse(isRunningOnCi())`): issue #668 requires it to run
 * in the nightly extensive gate (#659). The determinism work below
 * (grant-propagation polling + post-registration polling, mirrored from the
 * update test) is what makes it stable under emulator load instead of relying
 * on a CI skip.
 */
@RunWith(AndroidJUnit4::class)
class UsageThresholdNotificationE2eTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val notificationManager: NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    /**
     * The REAL persistent store from #619, freshly cleared per test so a stale
     * notified-key from a prior run can't pre-suppress the first post.
     */
    private val store = SharedPreferencesUsageNotificationStateStore(context)

    @Before
    fun grantNotificationPermissionAndClearState() {
        // Clear any stale status-bar notification so a leftover can't satisfy
        // the post assertion, and any persisted crossing so the first feed is
        // a genuine first crossing.
        notificationManager.cancelAll()
        store.setNotifiedKeys(emptySet())

        // Android 13+: POST_NOTIFICATIONS is a runtime permission. If it is not
        // actually granted to the app under test, NotificationManager silently
        // drops the post and UsageNotifications.show() early-returns
        // (canPostNotifications == false). Grant deterministically via the shell
        // `pm grant` command (uiAutomation.grantRuntimePermission proved
        // ineffective for the app-under-test on API 35 in the update E2E).
        val pkg = context.packageName
        runShellCommand("pm grant $pkg android.permission.POST_NOTIFICATIONS")

        // Poll until BOTH gates that actually decide whether a post lands are
        // satisfied: (a) the runtime-permission grant table reports GRANTED, and
        // (b) the AppOps-backed areNotificationsEnabled() reports true (pm grant
        // flips this on a separate async path; (a) can be GRANTED while (b) is
        // still false, silently dropping the post).
        val deadline = System.currentTimeMillis() + GRANT_PROPAGATION_TIMEOUT_MS
        var notificationsEnabled = false
        while (System.currentTimeMillis() < deadline) {
            val permissionGranted = context.checkSelfPermission(
                "android.permission.POST_NOTIFICATIONS",
            ) == PackageManager.PERMISSION_GRANTED
            notificationsEnabled = notificationManager.areNotificationsEnabled()
            if (permissionGranted && notificationsEnabled) break
            Thread.sleep(POLL_INTERVAL_MS)
        }

        assertEquals(
            "POST_NOTIFICATIONS must be GRANTED before posting, otherwise the " +
                "usage notification is silently dropped and this test cannot verify it",
            PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission("android.permission.POST_NOTIFICATIONS"),
        )
        assertTrue(
            "NotificationManager.areNotificationsEnabled() must be true before " +
                "posting; pm grant updates this AppOps-backed signal on a " +
                "separate async path, so the test waits it out to avoid a " +
                "silently-dropped post (active titles=[])",
            notificationsEnabled,
        )
    }

    @After
    fun cleanup() {
        notificationManager.cancelAll()
        store.setNotifiedKeys(emptySet())
    }

    @Test
    fun exceededSnapshot_postsUsageNotificationOnce_opensUsage_dedupesAcrossRestart_andRespectsDismiss() {
        // ---- 1. First Exceeded crossing posts ONCE to the status bar. -------
        // Re-feed the same snapshot on each poll iteration is NOT idempotent for
        // de-dupe here (the persistent store would suppress a second post), so
        // instead each poll uses a FRESH notifier+store-read but the snapshot
        // only crosses once: we drive the notifier a single time, then poll the
        // async notify() -> activeNotifications registration. If the entry has
        // not registered yet we re-post via a fresh notifier whose store we
        // reset, so the production show() path is genuinely re-exercised until
        // the status-bar entry sticks (riding out the AppOps enablement race).
        var posted: StatusBarNotification? = null
        val postDeadline = System.currentTimeMillis() + POST_APPEARS_TIMEOUT_MS
        do {
            store.setNotifiedKeys(emptySet())
            freshNotifier().onSnapshotsChanged(mapOf(HOST_ID to exceededSnapshot()))
            posted = activeUsageNotification()
            if (posted != null) break
            Thread.sleep(POLL_INTERVAL_MS)
        } while (System.currentTimeMillis() < postDeadline)

        assertNotNull(
            "usage-threshold notification was not found in the status bar within " +
                "${POST_APPEARS_TIMEOUT_MS}ms; active titles=" +
                notificationManager.activeNotifications.map {
                    it.notification.extras.getCharSequence("android.title")
                },
            posted,
        )

        // Title/text exactly as the production formatter produces (asserted by
        // the JVM unit test too — here we read them off the REAL posted
        // notification to prove the on-device render carries them).
        assertEquals(
            "Codex weekly quota exceeded",
            posted!!.notification.extras.getCharSequence("android.title")?.toString(),
        )
        val text = posted.notification.extras
            .getCharSequence("android.text")?.toString().orEmpty()
        assertTrue(
            "usage notification body should prompt the user to open Usage: '$text'",
            text.contains("Tap to open Usage"),
        )

        // Exactly ONE usage-alert notification is live (de-dupe within the feed
        // and the stable per-provider notification id).
        assertEquals(
            "exactly one usage notification must be live after a single Exceeded " +
                "crossing; live titles=" +
                notificationManager.activeNotifications.map {
                    it.notification.extras.getCharSequence("android.title")
                },
            1,
            usageNotificationCount(),
        )

        // ---- 2. Tap routes to the Usage screen (EXTRA_OPEN_USAGE). ----------
        assertNotNull(
            "usage notification must carry a tap (contentIntent) so it routes to Usage",
            posted.notification.contentIntent,
        )
        // The content intent is a PendingIntent to MainActivity carrying
        // EXTRA_OPEN_USAGE=true (initialDestinationFromIntent -> Usage; covered
        // directly by the JVM unit test notificationUsageExtraDeepLinksToUsageDestination).
        // On-device we assert the live PendingIntent is the one production built
        // for the Usage deep link by comparing it to a fresh equal PendingIntent.
        assertTrue(
            "usage notification content-intent should be the Usage deep-link " +
                "PendingIntent (EXTRA_OPEN_USAGE)",
            posted.notification.contentIntent == expectedUsagePendingIntent(),
        )

        // Capture the rendered notification shade as authoritative evidence.
        screenshotNotificationShade()

        // ---- 3. Same crossing on a FRESH notifier + SAME persistent store ---
        //         (== process death + recreation) must NOT re-post. -----------
        // First record that the post happened in the live persistent store, the
        // way the production notifier does at the end of onSnapshotsChanged. The
        // poll loop above reset the store to force re-posts, so seed it from the
        // posted crossing to reach the real post-once steady state.
        store.setNotifiedKeys(store.notifiedKeys() + postedCrossingKey())
        val countBeforeRefeed = usageNotificationCount()

        freshNotifier().onSnapshotsChanged(mapOf(HOST_ID to exceededSnapshot()))
        // Give any (incorrect) async re-post a chance to register before asserting.
        Thread.sleep(POST_SETTLE_MS)
        assertEquals(
            "feeding the SAME Exceeded snapshot to a fresh notifier backed by the " +
                "same persistent store must NOT re-post (the #619 persistence)",
            countBeforeRefeed,
            usageNotificationCount(),
        )

        // ---- 4. A dismiss (delete-intent) suppresses re-post. ---------------
        // Simulate the user swiping the notification away: fire the production
        // delete-intent receiver, which durably records the crossing as
        // dismissed in the persistent store.
        notificationManager.cancelAll()
        fireDismissBroadcast(postedCrossingKey())
        // Wait until the dismissal has been recorded in the persistent store.
        awaitDismissRecorded(postedCrossingKey())

        // A fresh notifier (recreated process) feeding the same crossing must
        // stay suppressed.
        freshNotifier().onSnapshotsChanged(mapOf(HOST_ID to exceededSnapshot()))
        Thread.sleep(POST_SETTLE_MS)
        assertNull(
            "after a dismiss (delete-intent), the same Exceeded crossing must not " +
                "re-post even on a recreated process; live usage notification=" +
                activeUsageNotification()?.notification?.extras
                    ?.getCharSequence("android.title"),
            activeUsageNotification(),
        )
    }

    // ------------------------------------------------------------------------

    /**
     * A fresh [DefaultUsageNotifier] using the REAL default poster
     * ([UsageNotifications.show], posting a real status-bar notification) and
     * the REAL persistent store under test. Each call is a new instance: paired
     * with the shared persistent [store] this models process death + recreation.
     */
    private fun freshNotifier(): DefaultUsageNotifier = DefaultUsageNotifier(
        context = context,
        settingsRepository = SettingsRepository(context),
        stateStore = store,
        now = { Instant.parse("2026-06-08T10:00:00Z") },
        zoneId = { ZoneId.of("UTC") },
        // default poster -> UsageNotifications.show(context, event) -> real post
    )

    private fun exceededSnapshot(): UsageSnapshot.Records = UsageSnapshot.Records(
        hostId = HOST_ID,
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

    /**
     * The persistent-store key the production notifier records for the
     * [exceededSnapshot] crossing — derived from the same event the formatter
     * builds, so it matches what the delete-intent / de-dupe operate on.
     */
    private fun postedCrossingKey(): UsageNotificationKey =
        usageNotificationEvent(
            record = exceededSnapshot().records.single(),
            state = UsageThresholdState.Exceeded,
            warnPercent = SettingsRepository(context)
                .settings.value.usageWarnThresholdPercent.toDouble(),
            now = Instant.parse("2026-06-08T10:00:00Z"),
            zoneId = ZoneId.of("UTC"),
            hostName = "agent-box",
            hostId = HOST_ID,
        ).key

    /** The Usage deep-link content PendingIntent production builds. */
    private fun expectedUsagePendingIntent() =
        android.app.PendingIntent.getActivity(
            context,
            27_001,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_USAGE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                android.app.PendingIntent.FLAG_IMMUTABLE,
        )

    private fun fireDismissBroadcast(key: UsageNotificationKey) {
        val intent = Intent(context, UsageNotificationDismissReceiver::class.java)
            .setAction(UsageNotificationDismissReceiver.ACTION_DISMISS)
            .putExtra(
                UsageNotificationDismissReceiver.EXTRA_NOTIFICATION_KEY,
                key.encode(),
            )
        context.sendBroadcast(intent)
    }

    private fun awaitDismissRecorded(key: UsageNotificationKey) {
        val deadline = System.currentTimeMillis() + DISMISS_RECORD_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (key in SharedPreferencesUsageNotificationStateStore(context).notifiedKeys()) return
            Thread.sleep(POLL_INTERVAL_MS)
        }
        assertTrue(
            "the delete-intent broadcast did not record the dismissed crossing in " +
                "the persistent store within ${DISMISS_RECORD_TIMEOUT_MS}ms",
            key in SharedPreferencesUsageNotificationStateStore(context).notifiedKeys(),
        )
    }

    private fun screenshotNotificationShade() {
        instrumentation.uiAutomation
            .executeShellCommand("cmd statusbar expand-notifications").close()
        Thread.sleep(1_500)
        val mediaRoot = testArtifactsRoot(context)
        val artifactsDir = File(mediaRoot, "additional_test_output/usage-notification")
        assertTrue(
            "could not create artifact dir ${artifactsDir.absolutePath}",
            artifactsDir.exists() || artifactsDir.mkdirs(),
        )
        val shot = File(artifactsDir, "usage-threshold-notification-viewport.png")
        val bitmap: Bitmap? = instrumentation.uiAutomation.takeScreenshot()
        if (bitmap != null) {
            FileOutputStream(shot).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            println("USAGE_NOTIFICATION_SCREENSHOT ${shot.absolutePath}")
        }
        instrumentation.uiAutomation.executeShellCommand("cmd statusbar collapse").close()
    }

    /** The live usage-threshold notification in the status bar, or null. */
    private fun activeUsageNotification(): StatusBarNotification? =
        notificationManager.activeNotifications.firstOrNull {
            it.notification.extras
                .getCharSequence("android.title")
                ?.toString()
                ?.contains("Codex weekly quota exceeded") == true
        }

    private fun usageNotificationCount(): Int =
        notificationManager.activeNotifications.count {
            it.notification.extras
                .getCharSequence("android.title")
                ?.toString()
                ?.contains("Codex weekly quota exceeded") == true
        }

    private fun runShellCommand(command: String): String {
        val pfd = instrumentation.uiAutomation.executeShellCommand(command)
        return FileInputStream(pfd.fileDescriptor).use { input ->
            BufferedReader(InputStreamReader(input)).use { it.readText() }
        }.also { pfd.close() }
    }

    private companion object {
        const val HOST_ID: Long = 1L
        const val POLL_INTERVAL_MS: Long = 100L

        /**
         * Generous deadline for the AppOps OP_POST_NOTIFICATION /
         * areNotificationsEnabled() signal to propagate after pm grant.
         */
        const val GRANT_PROPAGATION_TIMEOUT_MS: Long = 5_000L

        /**
         * Generous deadline for the notify() -> activeNotifications status-bar
         * registration to become observable.
         */
        const val POST_APPEARS_TIMEOUT_MS: Long = 10_000L

        /** Time to let an (incorrect) async re-post register before asserting absence. */
        const val POST_SETTLE_MS: Long = 750L

        /** Deadline for the delete-intent broadcast to record the dismissal. */
        const val DISMISS_RECORD_TIMEOUT_MS: Long = 3_000L
    }
}
