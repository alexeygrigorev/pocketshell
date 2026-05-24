package com.pocketshell.app

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #129: verify the deep-link extractor pulls the payload out of
 * a `pocketshell://import?payload=...` intent and ignores unrelated
 * intents. Robolectric is needed because [Uri.parse] is part of the
 * Android stdlib stubs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MainActivityDeepLinkTest {

    @Test
    fun importPayloadFromIntent_pullsPayloadFromQuery() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("pocketshell://import?payload=hello%20world")
        }
        assertEquals("hello world", importPayloadFromIntent(intent))
    }

    @Test
    fun importPayloadFromIntent_handlesJsonPayload() {
        val payload = """{"type":"pocketshell.ssh-import.v1","version":1}"""
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(
                "pocketshell://import?payload=" + Uri.encode(payload),
            )
        }
        assertEquals(payload, importPayloadFromIntent(intent))
    }

    @Test
    fun importPayloadFromIntent_ignoresOtherSchemes() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("https://example.com/?payload=x")
        }
        assertNull(importPayloadFromIntent(intent))
    }

    @Test
    fun importPayloadFromIntent_ignoresOtherHosts() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("pocketshell://session?payload=x")
        }
        assertNull(importPayloadFromIntent(intent))
    }

    @Test
    fun importPayloadFromIntent_ignoresNonViewIntent() {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            data = Uri.parse("pocketshell://import?payload=x")
        }
        assertNull(importPayloadFromIntent(intent))
    }

    @Test
    fun importPayloadFromIntent_returnsNullForMissingPayload() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("pocketshell://import")
        }
        assertNull(importPayloadFromIntent(intent))
    }
}
