package com.pocketshell.app.notifications

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.release.ReleaseInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for the issue #502 de-dupe ledger
 * ([UpdateNotificationStore]) and the production notifier
 * ([DefaultUpdateNotifier]) that wires it to the notification post.
 *
 * The de-dupe is the load-bearing acceptance criterion: the foreground
 * release check re-runs on every cold launch and pull-to-refresh, so
 * without per-version de-dupe the user would get the same notification
 * over and over until they updated.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class UpdateNotifierTest {

    private lateinit var context: Context

    private fun release(tag: String) = ReleaseInfo(
        tagName = tag,
        htmlUrl = "https://github.com/alexeygrigorev/pocketshell/releases/tag/$tag",
        apkUrl = "https://example.com/pocketshell-${tag.removePrefix("v")}-debug.apk",
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Start every test from an empty ledger.
        context.getSharedPreferences("update_notifications", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("update_notifications", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun store_reportsShouldNotify_untilVersionIsMarked() {
        val store = UpdateNotificationStore(context)

        // Fresh ledger: nothing notified yet.
        assertEquals(true, store.shouldNotify("v0.5.0"))

        store.markNotified("v0.5.0")
        // Same version: suppressed.
        assertEquals(false, store.shouldNotify("v0.5.0"))
        // A different (newer) version: still surfaced.
        assertEquals(true, store.shouldNotify("v0.6.0"))
    }

    @Test
    fun store_persistsLastNotifiedTagAcrossInstances() {
        UpdateNotificationStore(context).markNotified("v0.5.0")

        // A fresh instance (e.g. next cold launch) reads the same prefs file.
        val reopened = UpdateNotificationStore(context)
        assertEquals("v0.5.0", reopened.lastNotifiedTag())
        assertEquals(false, reopened.shouldNotify("v0.5.0"))
    }

    @Test
    fun notifier_posts_onceForAVersion_thenDeDupesRepeats() {
        val posted = mutableListOf<ReleaseInfo>()
        val notifier = DefaultUpdateNotifier(
            context = context,
            store = UpdateNotificationStore(context),
            poster = { posted.add(it) },
        )

        val v5 = release("v0.5.0")

        // First detection of v0.5.0 → posts.
        notifier.notifyUpdateAvailable(v5)
        // Re-detection of the SAME version (cold launch / pull-to-refresh)
        // → suppressed.
        notifier.notifyUpdateAvailable(v5)
        notifier.notifyUpdateAvailable(v5)

        assertEquals(listOf(v5), posted)
    }

    @Test
    fun notifier_postsAgain_forANewerVersion() {
        val posted = mutableListOf<ReleaseInfo>()
        val notifier = DefaultUpdateNotifier(
            context = context,
            store = UpdateNotificationStore(context),
            poster = { posted.add(it) },
        )

        val v5 = release("v0.5.0")
        val v6 = release("v0.6.0")

        notifier.notifyUpdateAvailable(v5)
        notifier.notifyUpdateAvailable(v5) // de-duped
        notifier.notifyUpdateAvailable(v6) // new version → posts again

        assertEquals(listOf(v5, v6), posted)
    }
}
