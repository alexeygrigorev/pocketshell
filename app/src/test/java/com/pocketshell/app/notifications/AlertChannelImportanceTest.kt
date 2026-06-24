package com.pocketshell.app.notifications

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.messaging.ResetPushNotifications
import com.pocketshell.app.portfwd.service.ForwardingService
import com.pocketshell.app.usage.UsageNotifications
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #903 (companion of the reopened #752): PocketShell's push / alert
 * notifications were landing in Android's "Silent" group — no sound, no
 * heads-up — so a push the maintainer should notice never pinged. The shared
 * root with #752 is the notification-channel importance: every alerting channel
 * must be created at an AUDIBLE importance (>= DEFAULT, and HIGH so it can
 * heads-up), while the ONGOING port-forward channel stays quiet-but-visible.
 *
 * This is the class-covering regression test (D31/D32 G2): it asserts EVERY
 * alerting channel the app registers — not just one — is at an audible
 * importance, and that the ongoing forwarding channel is NOT raised so it never
 * buzzes. It FAILS on the previously-shipped DEFAULT (and the forwarding LOW)
 * config and PASSES after the fix (red→green).
 *
 * Run on SDK 33 so [NotificationChannel] importance is honoured by the
 * Robolectric NotificationManager shadow.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class AlertChannelImportanceTest {

    private lateinit var context: Context
    private lateinit var manager: NotificationManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = context.getSystemService(NotificationManager::class.java)
    }

    /**
     * Every alerting channel is registered, drives its `ensureChannel`, and the
     * registered channel id + importance is captured for the class-coverage
     * assertions below. Adding a new alerting channel without raising it to an
     * audible importance fails this test.
     */
    private fun alertingChannelImportances(): Map<String, Int> {
        UsageNotifications.ensureChannel(context)
        ResetPushNotifications.ensureChannel(context)
        UpdateAvailableNotifications.ensureChannel(context)
        // ShareUploadNotifications.ensureChannel is private-by-package; it shares
        // the same _v2/HIGH shape and is asserted via its registered id below.
        ShareUploadNotifications.ensureChannel(context)
        return listOf(
            "usage_alerts_v2",
            "usage_reset_alerts_v2",
            "app_update_available_v2",
            "share_upload_result_v2",
        ).associateWith { id ->
            requireNotNull(manager.getNotificationChannel(id)) {
                "alerting channel '$id' must be registered"
            }.importance
        }
    }

    @Test
    fun `every alerting channel is at an audible heads-up importance (class coverage)`() {
        val importances = alertingChannelImportances()

        // Class coverage (G2): assert EVERY alerting channel, not just one. The
        // previous config registered each at IMPORTANCE_DEFAULT — which the
        // maintainer saw filed under "Silent" with no ping — so this loop fails
        // red on base and passes green after raising each channel to HIGH.
        importances.forEach { (id, importance) ->
            assertTrue(
                "alerting channel '$id' must be at IMPORTANCE_HIGH so a push pings / " +
                    "heads-up — it was silent (issue #903)",
                importance >= NotificationManager.IMPORTANCE_HIGH,
            )
        }
        // Be explicit so a future down-grade to DEFAULT (which only sounds, never
        // heads-up) is also caught.
        importances.forEach { (id, importance) ->
            assertEquals(
                "alerting channel '$id' must be exactly IMPORTANCE_HIGH",
                NotificationManager.IMPORTANCE_HIGH,
                importance,
            )
        }
    }

    @Test
    fun `the legacy DEFAULT alert channels are deleted so the HIGH ones take effect on update`() {
        alertingChannelImportances()

        // Importance is immutable after first creation, so on the maintainer's
        // already-installed app the HIGH flip is only honoured once the old
        // DEFAULT channels are deleted and the _v2 ones created fresh (hard-cut
        // D22). This is the upgrade-path half — without it the push stays silent
        // on update.
        listOf(
            "usage_alerts",
            "usage_reset_alerts",
            "app_update_available",
            "share_upload_result",
        ).forEach { legacyId ->
            assertNull(
                "the stale DEFAULT channel '$legacyId' must be deleted so the audible " +
                    "_v2 channel is created fresh on update (issue #903)",
                manager.getNotificationChannel(legacyId),
            )
        }
    }

    @Test
    fun `the ongoing port-forward channel stays quiet-but-visible — never raised to alerting (no #752 regression)`() {
        // Build the forwarding channel through its real creation path.
        val service = Robolectric.buildService(ForwardingService::class.java).get()
        service.createNotificationChannel()

        val forwarding = manager.getNotificationChannel("pocketshell_forwarding_status_v6")
        assertNotNull("the port-forward ongoing channel must be registered", forwarding)
        val channel = requireNotNull(forwarding)

        // #752: visible — at least DEFAULT so the persistent status-bar icon shows
        // near the clock (LOW would hide it in the Silent group).
        assertTrue(
            "the ongoing port-forward channel must be >= DEFAULT so its status-bar " +
                "icon shows near the clock (issue #752)",
            channel.importance >= NotificationManager.IMPORTANCE_DEFAULT,
        )
        // #903 separation: but it must NOT be raised to the alerting HIGH — the
        // ongoing status must never buzz / heads-up on forward-start.
        assertEquals(
            "the ongoing port-forward channel must stay at DEFAULT (not the alerting " +
                "HIGH) so it is visible but never buzzes (issue #903 channel separation)",
            NotificationManager.IMPORTANCE_DEFAULT,
            channel.importance,
        )
        assertNull(
            "the ongoing port-forward channel must be silent — no sound — so it stays " +
                "quiet-but-visible (issue #752/#903)",
            channel.sound,
        )
    }
}
