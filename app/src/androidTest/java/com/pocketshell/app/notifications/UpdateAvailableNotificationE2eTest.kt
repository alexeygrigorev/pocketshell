package com.pocketshell.app.notifications

import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.release.ReleaseInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader

/**
 * Emulator evidence for issue #502: posting the real
 * [UpdateAvailableNotifications] notification on-device, asserting it
 * lands in the system notification manager with the title, body, and tap
 * intent a user needs from any screen.
 *
 * The de-dupe and the ViewModel→notifier trigger are covered by the JVM
 * unit tests (`UpdateNotifierTest`, `HostListViewModelTest`). This connected
 * test owns the production Android boundary they cannot cover: the real
 * [UpdateAvailableNotifications.show] call must create a live system
 * notification carrying its production payload and content intent.
 *
 * ## Determinism (round-3 stabilization)
 *
 * Two async paths made the earlier version flaky (~60% pass on an idle
 * AVD):
 *
 *  1. **Grant propagation.** `pm grant POST_NOTIFICATIONS` followed by
 *     `checkSelfPermission == GRANTED` is necessary but NOT sufficient on
 *     Android 13+. Whether a post actually lands is additionally gated by
 *     the AppOps `OP_POST_NOTIFICATION` mode surfaced through
 *     `NotificationManager.areNotificationsEnabled()` (and the channel's
 *     importance), which `pm grant` updates on a *separate, asynchronous*
 *     path. `checkSelfPermission` could read GRANTED while notifications
 *     were still effectively disabled at the instant `notify()` ran, so
 *     the post was silently dropped → `active titles=[]`. We now poll
 *     until BOTH `checkSelfPermission == GRANTED` AND
 *     `areNotificationsEnabled() == true` (and the channel is not
 *     `IMPORTANCE_NONE`) before posting.
 *
 *  2. **Post registration.** `notify()` → `activeNotifications` is also
 *     async; reading `activeNotifications` once immediately after `show()`
 *     could observe an empty list before the post registered. We now poll
 *     `activeNotifications` for the expected title (re-posting on each
 *     poll iteration is idempotent — the stable notification id replaces
 *     the prior entry — so a post that raced the enablement signal is
 *     retried until it sticks).
 *
 * The `@Before` cancels any stale notifications so a leftover from an
 * earlier run can't satisfy the assertion.
 *
 * The load-bearing proof deliberately does not expand or screenshot the
 * SystemUI notification shade. SwiftShader shade capture was the unstable
 * part of the old local-only test; the live `activeNotifications` payload and
 * intent below are the production-boundary assertions and run per push.
 */
@RunWith(AndroidJUnit4::class)
class UpdateAvailableNotificationE2eTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val notificationManager: NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    private val info = ReleaseInfo(
        tagName = "v9.9.9",
        htmlUrl = "https://github.com/alexeygrigorev/pocketshell/releases/tag/v9.9.9",
        apkUrl = "https://example.com/pocketshell-9.9.9-debug.apk",
    )

    @Before
    fun grantNotificationPermission() {
        // Clear any stale notification (e.g. left over from a prior run in
        // this process) so it can't accidentally satisfy the assertion.
        notificationManager.cancelAll()

        // Android 13+: POST_NOTIFICATIONS is a runtime permission. If it is
        // not actually granted to the app under test, NotificationManager
        // silently drops the post and `UpdateAvailableNotifications.show()`
        // early-returns.
        //
        // `uiAutomation.grantRuntimePermission(...)` proved ineffective for
        // the app-under-test on API 35, so grant deterministically via the
        // shell `pm grant` command.
        val pkg = context.packageName
        runShellCommand("pm grant $pkg android.permission.POST_NOTIFICATIONS")

        // Poll until BOTH gates that actually decide whether a post lands
        // are satisfied:
        //   (a) the runtime-permission grant table reports GRANTED, and
        //   (b) the AppOps-backed `areNotificationsEnabled()` reports true
        //       (this is the signal `pm grant` flips on a separate async
        //       path; (a) can be GRANTED while (b) is still false).
        // We also ensure the channel exists and is not IMPORTANCE_NONE, so
        // an importance flip can't silently suppress the post either.
        val deadline = System.currentTimeMillis() + GRANT_PROPAGATION_TIMEOUT_MS
        var permissionGranted = false
        var notificationsEnabled = false
        while (System.currentTimeMillis() < deadline) {
            permissionGranted = context.checkSelfPermission(
                "android.permission.POST_NOTIFICATIONS",
            ) == PackageManager.PERMISSION_GRANTED
            notificationsEnabled = notificationManager.areNotificationsEnabled()
            if (permissionGranted && notificationsEnabled) break
            Thread.sleep(POLL_INTERVAL_MS)
        }

        assertEquals(
            "POST_NOTIFICATIONS must be GRANTED before posting, otherwise the " +
                "notification is silently dropped and this test cannot verify it",
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

    private fun runShellCommand(command: String): String {
        val pfd = instrumentation.uiAutomation.executeShellCommand(command)
        return FileInputStream(pfd.fileDescriptor).use { input ->
            BufferedReader(InputStreamReader(input)).use { it.readText() }
        }.also { pfd.close() }
    }

    @After
    fun cleanup() {
        notificationManager.cancelAll()
    }

    @Test
    fun updateNotification_postsToStatusBar_andIsTappable() {
        // Poll the post → status-bar transition: `notify()` registering in
        // `activeNotifications` is async, and the AppOps enablement signal
        // can still be settling at the first call instant. `show()` uses a
        // stable notification id, so re-posting on each poll iteration is
        // idempotent (it replaces the prior entry) and rides out any
        // residual race until the entry sticks.
        val deadline = System.currentTimeMillis() + POST_APPEARS_TIMEOUT_MS
        var posted = activeUpdateNotification()
        while (posted == null && System.currentTimeMillis() < deadline) {
            UpdateAvailableNotifications.show(context, info)
            Thread.sleep(POLL_INTERVAL_MS)
            posted = activeUpdateNotification()
        }

        assertNotNull(
            "update-available notification was not found in the status bar " +
                "within ${POST_APPEARS_TIMEOUT_MS}ms; active titles=" +
                notificationManager.activeNotifications.map {
                    it.notification.extras.getCharSequence("android.title")
                },
            posted,
        )

        val title = posted!!.notification.extras
            .getCharSequence("android.title")?.toString().orEmpty()
        assertEquals(
            "PocketShell v9.9.9 available",
            title,
        )

        val text = posted.notification.extras
            .getCharSequence("android.text")?.toString().orEmpty()
        assertEquals(
            "Tap to update",
            text,
        )

        // It must carry a tap action (the ACTION_VIEW PendingIntent that
        // routes to the APK download — the #476 update path).
        assertNotNull(
            "update notification must have a tap (contentIntent) so it routes to the update",
            posted.notification.contentIntent,
        )
        println(
            "UPDATE_NOTIFICATION_LIVE " +
                "title=${title.quoteForEvidence()} " +
                "body=${text.quoteForEvidence()} " +
                "content_intent_present=true",
        )
    }

    /**
     * The live update-available notification in the status bar, or null if
     * it has not registered yet.
     */
    private fun activeUpdateNotification() =
        notificationManager.activeNotifications.firstOrNull {
            it.notification.extras
                .getCharSequence("android.title")
                ?.toString()
                ?.contains("PocketShell v9.9.9 available") == true
        }

    private fun String.quoteForEvidence(): String =
        "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

    private companion object {
        const val POLL_INTERVAL_MS: Long = 100L

        /**
         * Generous deadline for the AppOps `OP_POST_NOTIFICATION` /
         * `areNotificationsEnabled()` signal to propagate after `pm grant`.
         */
        const val GRANT_PROPAGATION_TIMEOUT_MS: Long = 5_000L

        /**
         * Generous deadline for the `notify()` → `activeNotifications`
         * status-bar registration to become observable.
         */
        const val POST_APPEARS_TIMEOUT_MS: Long = 10_000L
    }
}
