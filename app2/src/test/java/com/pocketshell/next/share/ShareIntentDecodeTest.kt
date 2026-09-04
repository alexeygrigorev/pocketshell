package com.pocketshell.next.share

import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * [decodeShareIntent] against the intent shapes Android actually delivers, plus
 * the ones a hostile app can deliver.
 *
 * [ShareActivity] is exported, so "decodes a malformed intent into nothing"
 * is a security property, not a nicety: the alternative is a share target any
 * installed app can crash on demand.
 */
@RunWith(AndroidJUnit4::class)
class ShareIntentDecodeTest {

    /**
     * Robolectric's `MimeTypeMap` starts EMPTY — the real one is populated by
     * the platform. The mapping is registered here so the extension assertion
     * tests our code rather than the shadow's blank table.
     */
    @Before
    fun seedMimeTypes() {
        shadowOf(MimeTypeMap.getSingleton())
            .addExtensionMimeTypeMapping("png", "image/png")
    }

    @Test
    fun `a single file share becomes one uri item`() {
        val uri = Uri.parse("content://media/external/images/42")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
        }

        val items = decodeShareIntent(intent)

        val item = items.single() as ShareableItem.UriItem
        assertEquals(uri, item.uri)
        assertEquals("42", item.displayName)
        assertEquals("image/png", item.mimeType)
        assertEquals("png", item.fallbackExtension)
    }

    @Test
    fun `EXTRA_TITLE beats the uri tail`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, Uri.parse("content://downloads/9182"))
            putExtra(Intent.EXTRA_TITLE, "invoice.pdf")
        }

        val item = decodeShareIntent(intent).single() as ShareableItem.UriItem

        assertEquals("invoice.pdf", item.displayName)
    }

    @Test
    fun `a multi-file share keeps every uri`() {
        val uris = arrayListOf(
            Uri.parse("content://media/1"),
            Uri.parse("content://media/2"),
            Uri.parse("content://media/3"),
        )
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }

        val items = decodeShareIntent(intent)

        // The shipping client took only the first URI here for months: sharing
        // four screenshots uploaded one and silently dropped three.
        assertEquals(uris, items.map { (it as ShareableItem.UriItem).uri })
    }

    @Test
    fun `a text share becomes a text item named after the subject`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "https://example.com/article")
            putExtra(Intent.EXTRA_SUBJECT, "An article")
        }

        val item = decodeShareIntent(intent).single() as ShareableItem.TextItem

        assertEquals("https://example.com/article", item.text)
        assertEquals("An article", item.displayName)
        assertEquals("txt", item.fallbackExtension)
    }

    @Test
    fun `a text share without a subject still has a usable name`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "just some text")
        }

        val item = decodeShareIntent(intent).single() as ShareableItem.TextItem

        assertTrue("expected a non-blank fallback name", !item.displayName.isNullOrBlank())
    }

    @Test
    fun `a stream wins over text when both are present`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, Uri.parse("content://media/7"))
            putExtra(Intent.EXTRA_TEXT, "some caption the gallery attached")
        }

        assertTrue(decodeShareIntent(intent).single() is ShareableItem.UriItem)
    }

    @Test
    fun `intents carrying nothing routable decode to nothing`() {
        assertEquals(emptyList<ShareableItem>(), decodeShareIntent(null))
        assertEquals(emptyList<ShareableItem>(), decodeShareIntent(Intent(Intent.ACTION_VIEW)))
        assertEquals(
            emptyList<ShareableItem>(),
            decodeShareIntent(Intent(Intent.ACTION_SEND).apply { type = "text/plain" }),
        )
        assertEquals(
            emptyList<ShareableItem>(),
            decodeShareIntent(Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_TEXT, "") }),
        )
        assertEquals(
            emptyList<ShareableItem>(),
            decodeShareIntent(Intent(Intent.ACTION_SEND_MULTIPLE).apply { type = "image/*" }),
        )
    }

    @Test
    fun `an EXTRA_STREAM of the wrong type is refused, not crashed on`() {
        // Any app can send this. The platform's untyped getter throws a
        // ClassCastException while unmarshalling it — a share target that lets
        // that escape can be crashed by any installed app.
        val hostile = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, 42)
        }

        assertEquals(emptyList<ShareableItem>(), decodeShareIntent(hostile))
    }
}
