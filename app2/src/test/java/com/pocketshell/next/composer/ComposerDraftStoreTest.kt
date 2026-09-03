package com.pocketshell.next.composer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The durable draft slot (rewrite task P-1), over real SharedPreferences.
 *
 * The promise this backs is the one the send contract makes: a send that did
 * not leave keeps the text. That promise has to survive a session switch and a
 * process death, so the assertions read through a SECOND store instance — a
 * test that reused the first would prove only that the in-memory mirror works.
 */
@RunWith(AndroidJUnit4::class)
class ComposerDraftStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun store() = ComposerDraftStore(context, Dispatchers.Unconfined)

    @Before
    @After
    fun clearPrefs() {
        context.getSharedPreferences("composer_drafts", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `a saved draft reads back from a fresh store`() = runTest {
        store().save("7/devbox", ComposerDraft("half a thought"))

        assertEquals("half a thought", store().load("7/devbox").text)
    }

    @Test
    fun `attachments round-trip with their names and types`() = runTest {
        val attachments = listOf(
            StagedAttachment("~/a/one.png", "one.png", "image/png"),
            StagedAttachment("~/a/two.bin", "two.bin", null),
        )

        store().save("7/devbox", ComposerDraft("text", attachments))

        assertEquals(attachments, store().load("7/devbox").attachments)
    }

    /**
     * A file name with a tab or a newline in it must not shift every following
     * field by one — a shape that is silently wrong without escaping.
     */
    @Test
    fun `a name containing separators survives the round trip`() {
        val awkward = StagedAttachment("~/a/we\tird\nname", "we\tird\nname", "text/plain")
        assertEquals(listOf(awkward), decodeAttachments(encodeAttachments(listOf(awkward))))
    }

    @Test
    fun `a malformed row is dropped and the readable ones survive`() {
        assertEquals(emptyList<StagedAttachment>(), decodeAttachments("\n\n"))
        assertEquals(emptyList<StagedAttachment>(), decodeAttachments(null))

        // Two good rows around an empty one: the empty row disappears, the
        // others are unaffected — a corrupted slot must degrade, not wipe.
        assertEquals(
            listOf("~/a/x", "~/a/y"),
            decodeAttachments("~/a/x\tx\t\n\n~/a/y\ty\t").map { it.remotePath },
        )
    }

    @Test
    fun `drafts are scoped per session`() = runTest {
        val store = store()
        store.save("7/one", ComposerDraft("first"))
        store.save("7/two", ComposerDraft("second"))

        assertEquals("first", store().load("7/one").text)
        assertEquals("second", store().load("7/two").text)
    }

    @Test
    fun `clearing removes both the text and the attachments`() = runTest {
        val key = "7/devbox"
        store().save(key, ComposerDraft("gone", listOf(StagedAttachment("~/a/x", "x"))))

        store().clear(key)

        val reloaded = store().load(key)
        assertTrue(reloaded.isEmpty)
        assertEquals("", reloaded.text)
        assertTrue(reloaded.attachments.isEmpty())
    }

    @Test
    fun `an unknown session loads an empty draft rather than failing`() = runTest {
        assertTrue(store().load("99/never-used").isEmpty)
    }

    @Test
    fun `a blank session key is a no-op in both directions`() = runTest {
        store().save("", ComposerDraft("nowhere"))
        assertTrue(store().load("").isEmpty)
    }

    /**
     * The write-then-read race the in-memory mirror exists to close: both
     * operations hop to a dispatcher, so without the mirror a read issued
     * immediately after a write could observe the pre-write value.
     */
    @Test
    fun `a read immediately after a write sees the write`() = runTest {
        val store = store()
        store.save("7/devbox", ComposerDraft("just typed"))
        assertEquals("just typed", store.load("7/devbox").text)
    }
}
