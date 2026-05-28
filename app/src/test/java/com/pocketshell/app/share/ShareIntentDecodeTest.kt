package com.pocketshell.app.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [decodeShareIntent] — the pure share-intent parser that
 * [ShareActivity] forwards its `contentResolver` to.
 *
 * Issue #258: the bug was that an `ACTION_SEND_MULTIPLE` intent dropped
 * every URI past the first. These tests pin the fix (extract ALL URIs)
 * while proving the single-file `ACTION_SEND` paths (URI + text) still
 * decode to exactly one item.
 *
 * Robolectric supplies a real `ContentResolver`; the bare `content://`
 * URIs used here aren't backed by a provider, so `queryUriDisplayName`
 * returns null and the decoder falls back to `EXTRA_TITLE` / the URI's
 * last path segment — which is exactly what we assert on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ShareIntentDecodeTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun sendMultipleExtractsEveryUri() {
        val uris = arrayListOf(
            Uri.parse("content://media/external/images/media/1"),
            Uri.parse("content://media/external/images/media/2"),
            Uri.parse("content://media/external/images/media/3"),
        )
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }

        val items = decodeShareIntent(intent, context.contentResolver)

        assertEquals("expected one staged item per shared URI", 3, items.size)
        val stagedUris = items.map { (it as ShareableItem.UriItem).uri }
        assertEquals(uris.toList(), stagedUris)
    }

    @Test
    fun sendMultipleWithSingleUriStagesOneItem() {
        val uris = arrayListOf(Uri.parse("content://media/external/images/media/9"))
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }

        val items = decodeShareIntent(intent, context.contentResolver)

        assertEquals(1, items.size)
        assertTrue(items.single() is ShareableItem.UriItem)
    }

    @Test
    fun sendMultipleWithNoStreamReturnsEmpty() {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply { type = "image/*" }

        val items = decodeShareIntent(intent, context.contentResolver)

        assertTrue("a SEND_MULTIPLE with no EXTRA_STREAM yields nothing", items.isEmpty())
    }

    @Test
    fun singleSendUriStagesOneUriItem() {
        val uri = Uri.parse("content://media/external/images/media/42")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
        }

        val items = decodeShareIntent(intent, context.contentResolver)

        assertEquals(1, items.size)
        val item = items.single() as ShareableItem.UriItem
        assertEquals(uri, item.uri)
    }

    @Test
    fun singleSendTextStagesOneTextItem() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "hello from the clipboard")
            putExtra(Intent.EXTRA_SUBJECT, "note title")
        }

        val items = decodeShareIntent(intent, context.contentResolver)

        assertEquals(1, items.size)
        val item = items.single() as ShareableItem.TextItem
        assertEquals("hello from the clipboard", item.text)
        assertEquals("note title", item.displayName)
    }

    @Test
    fun nullIntentReturnsEmpty() {
        assertTrue(decodeShareIntent(null, context.contentResolver).isEmpty())
    }

    @Test
    fun unsupportedActionReturnsEmpty() {
        val intent = Intent(Intent.ACTION_VIEW).apply { type = "image/*" }
        assertTrue(decodeShareIntent(intent, context.contentResolver).isEmpty())
    }
}
