package com.pocketshell.app.share

import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [decodeShareIntent] — the pure share-intent parser that
 * [ShareActivity] uses before it renders the host picker.
 *
 * Issue #258: the bug was that an `ACTION_SEND_MULTIPLE` intent dropped
 * every URI past the first. These tests pin the fix (extract ALL URIs)
 * while proving the single-file `ACTION_SEND` paths (URI + text) still
 * decode to exactly one item.
 *
 * The bare `content://` URIs used here aren't backed by a provider.
 * Decode must still return immediately with `EXTRA_TITLE` / URI path
 * fallbacks; slow provider metadata is resolved later on an async path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ShareIntentDecodeTest {

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

        val items = decodeShareIntent(intent)

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

        val items = decodeShareIntent(intent)

        assertEquals(1, items.size)
        assertTrue(items.single() is ShareableItem.UriItem)
    }

    @Test
    fun sendMultipleWithNoStreamReturnsEmpty() {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply { type = "image/*" }

        val items = decodeShareIntent(intent)

        assertTrue("a SEND_MULTIPLE with no EXTRA_STREAM yields nothing", items.isEmpty())
    }

    @Test
    fun singleSendUriStagesOneUriItem() {
        val uri = Uri.parse("content://media/external/images/media/42")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
        }

        val items = decodeShareIntent(intent)

        assertEquals(1, items.size)
        val item = items.single() as ShareableItem.UriItem
        assertEquals(uri, item.uri)
    }

    @Test
    fun singleSendTextPlainStreamStagesTxtAsFileItem() {
        val uri = Uri.parse("content://reports/crash-report.txt")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, "crash-report.txt")
        }

        val items = decodeShareIntent(intent)

        assertEquals(1, items.size)
        val item = items.single() as ShareableItem.UriItem
        assertEquals(uri, item.uri)
        assertEquals("crash-report.txt", item.displayName)
    }

    @Test
    fun uriDecodeUsesCheapFallbackNameWithoutResolverMetadata() {
        val uri = Uri.parse("content://slow-provider/exports/report.bin")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
        }

        val items = decodeShareIntent(intent)

        val item = items.single() as ShareableItem.UriItem
        assertEquals("report.bin", item.displayName)
    }

    @Test
    fun metadataResolutionRefinesUriDisplayNameWhenProviderResponds() = runTest {
        val uri = Uri.parse("content://media/external/images/media/42")
        val fallback = ShareableItem.UriItem(
            uri = uri,
            displayName = "42",
            size = null,
            mimeType = "image/png",
            fallbackExtension = "png",
        )

        val items = resolveShareUriDisplayNames(
            items = listOf(fallback),
            queryDisplayName = { "vacation.png" },
            perUriTimeoutMs = 100,
            totalTimeoutMs = 100,
        )

        val item = items.single() as ShareableItem.UriItem
        assertEquals("vacation.png", item.displayName)
    }

    @Test
    fun metadataResolutionTimeoutKeepsFallbackName() = runTest {
        val uri = Uri.parse("content://slow-provider/exports/report.bin")
        val fallback = ShareableItem.UriItem(
            uri = uri,
            displayName = "report.bin",
            size = null,
            mimeType = "application/octet-stream",
            fallbackExtension = "bin",
        )

        val items = resolveShareUriDisplayNames(
            items = listOf(fallback),
            queryDisplayName = {
                delay(1_000)
                "provider-name.bin"
            },
            perUriTimeoutMs = 100,
            totalTimeoutMs = 100,
        )

        val item = items.single() as ShareableItem.UriItem
        assertEquals("report.bin", item.displayName)
    }

    @Test
    fun singleSendTextStagesOneTextItem() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "hello from the clipboard")
            putExtra(Intent.EXTRA_SUBJECT, "note title")
        }

        val items = decodeShareIntent(intent)

        assertEquals(1, items.size)
        val item = items.single() as ShareableItem.TextItem
        assertEquals("hello from the clipboard", item.text)
        assertEquals("note title", item.displayName)
    }

    @Test
    fun nullIntentReturnsEmpty() {
        assertTrue(decodeShareIntent(null).isEmpty())
    }

    @Test
    fun unsupportedActionReturnsEmpty() {
        val intent = Intent(Intent.ACTION_VIEW).apply { type = "image/*" }
        assertTrue(decodeShareIntent(intent).isEmpty())
    }

    /**
     * The share activity is EXPORTED, so a malicious/broken sender can put a
     * non-`Uri` Parcelable under `EXTRA_STREAM`. On pre-33 the untyped
     * `getParcelableExtra` casts the stored value to `Uri`, throwing a
     * `ClassCastException` mid-`onCreate`. `decodeShareIntent` must swallow that
     * and return an empty list (the activity then finishes gracefully) instead
     * of crashing. Run under SDK 28 to exercise the untyped getter path.
     *
     * Without the `runCatching` guard in `decodeShareIntent` this test throws
     * (CCE) instead of asserting — the red→green pin for the fix.
     */
    @Test
    @Config(sdk = [28])
    fun malformedStreamExtraDecodesToEmptyInsteadOfCrashing() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            // A Bundle is a valid Parcelable but NOT a Uri — the exact "wrong
            // type in EXTRA_STREAM" shape that CCEs the pre-33 untyped getter.
            putExtra(Intent.EXTRA_STREAM, android.os.Bundle())
        }

        val items = decodeShareIntent(intent)

        assertTrue("a malformed EXTRA_STREAM must decode to nothing, not crash", items.isEmpty())
    }

    /**
     * Class coverage: the `ACTION_SEND_MULTIPLE` list path unmarshals + casts
     * each element too, so a wrong-type element there must also degrade to empty
     * rather than crash the exported activity.
     */
    @Test
    @Config(sdk = [28])
    fun malformedStreamListDecodesToEmptyInsteadOfCrashing() {
        val bogus = arrayListOf<android.os.Parcelable>(android.os.Bundle())
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, bogus)
        }

        val items = decodeShareIntent(intent)

        assertTrue("a malformed EXTRA_STREAM list must decode to nothing, not crash", items.isEmpty())
    }
}
