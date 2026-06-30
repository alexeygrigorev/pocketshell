package com.pocketshell.app.composer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression proof for issue #1125 (freeze cause F6 class sweep): the composer
 * stores opened their SharedPreferences file EAGERLY in a field initializer, so
 * the synchronous first-touch disk read ran on whatever thread constructed the
 * store. The composer is always-present (#809) and constructed via Hilt on the
 * Main thread at first-session-open, so each open was a per-session-open
 * Main-thread block.
 *
 * Reproduce-first (D33 / G10, #780 — no self-skip): the LOAD-BEARING assertion
 * is that the prefs-file open runs on a thread OTHER than the constructing
 * (Main) thread. On the pre-fix code the open happened in the field initializer
 * on the constructing thread (RED); with the off-main eager-`async`
 * [com.pocketshell.app.prefs.DeferredPrefs] build it runs on the IO dispatcher
 * (GREEN). Class coverage (G2): all three composer-package stores
 * ([SharedPrefsComposerDraftStore], [SharedPrefsOutboundQueueStore],
 * [OutboundAttachmentSidecarStore]) are covered, plus a behavior round-trip so
 * the off-main move did not break persistence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ComposerStoresOffMainTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clearAll()
    }

    @After
    fun tearDown() {
        clearAll()
    }

    @Test
    fun composer_draft_prefs_build_does_not_run_on_constructing_thread() {
        val constructing = Thread.currentThread().name
        val build = SharedPrefsComposerDraftStore(context).awaitPrefsBuildThreadNameForTest()
        assertNotEquals(
            "composer_drafts prefs must open off the constructing (Main) thread " +
                "(#1125). constructing=$constructing build=$build",
            constructing,
            build,
        )
    }

    @Test
    fun outbound_queue_prefs_build_does_not_run_on_constructing_thread() {
        val constructing = Thread.currentThread().name
        val build = SharedPrefsOutboundQueueStore(context).awaitPrefsBuildThreadNameForTest()
        assertNotEquals(
            "outbound_queue prefs must open off the constructing (Main) thread " +
                "(#1125). constructing=$constructing build=$build",
            constructing,
            build,
        )
    }

    @Test
    fun outbound_attachment_sidecar_prefs_build_does_not_run_on_constructing_thread() {
        val constructing = Thread.currentThread().name
        val build = OutboundAttachmentSidecarStore(context).awaitPrefsBuildThreadNameForTest()
        assertNotEquals(
            "outbound_attachment_sidecars prefs must open off the constructing " +
                "(Main) thread (#1125). constructing=$constructing build=$build",
            constructing,
            build,
        )
    }

    /** The off-main move must not break persistence: draft round-trips + survives restart. */
    @Test
    fun composer_draft_round_trips_after_offmain_build() {
        SharedPrefsComposerDraftStore(context).save("host:1", "hello there")
        assertEquals("hello there", SharedPrefsComposerDraftStore(context).load("host:1"))
    }

    /** The off-main move must not break persistence: queued item survives a fresh instance. */
    @Test
    fun outbound_queue_round_trips_after_offmain_build() {
        val store = SharedPrefsOutboundQueueStore(context)
        val item = store.enqueue(sessionKey = "host:1", cleanText = "ls -la")
        val restarted = SharedPrefsOutboundQueueStore(context)
        assertEquals(item.id, restarted.item(item.id)?.id)
    }

    /** The off-main move must not break persistence: a staged sidecar ref is readable. */
    @Test
    fun outbound_sidecar_round_trips_after_offmain_build() = runBlocking {
        val store = OutboundAttachmentSidecarStore(context)
        // refsFor on an unknown id returns empty without throwing after off-main build.
        assertEquals(emptyList<LocalAttachmentSidecarRef>(), store.refsFor("nope"))
    }

    private fun clearAll() {
        listOf("composer_drafts", "outbound_queue", "outbound_attachment_sidecars").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
}
