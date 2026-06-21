package com.pocketshell.app.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #872: the durable per-session attachment-ref encoding (the
 * SharedPreferences serialisation [SharedPrefsComposerDraftStore] uses) must
 * round-trip losslessly, including paths/names that contain tabs or newlines,
 * so a failed-send staged attachment survives process death and a session
 * switch. Also covers the [InMemoryComposerDraftStore] contract used by the
 * composer VM tests.
 */
class ComposerDraftStoreAttachmentTest {

    @Test
    fun encodeDecodeRoundTripsRemotePathDisplayNameAndMime() {
        val refs = listOf(
            DurableAttachmentRef(
                remotePath = "~/.pocketshell/attachments/host-1/shot.png",
                displayName = "shot.png",
                mimeType = "image/png",
            ),
            DurableAttachmentRef(
                remotePath = "~/.pocketshell/attachments/host-1/report.txt",
                displayName = "report.txt",
                mimeType = null,
            ),
        )
        val decoded = decodeAttachments(encodeAttachments(refs))
        assertEquals(refs, decoded)
    }

    @Test
    fun encodeDecodeSurvivesTabsAndNewlinesInFields() {
        val refs = listOf(
            DurableAttachmentRef(
                remotePath = "~/dir\twith\ttabs/and\nnewline.png",
                displayName = "and\nnewline.png",
                mimeType = "image/png",
            ),
        )
        val decoded = decodeAttachments(encodeAttachments(refs))
        assertEquals(refs, decoded)
    }

    @Test
    fun decodeEmptyStringIsEmptyList() {
        assertTrue(decodeAttachments("").isEmpty())
    }

    @Test
    fun decodeDropsRowsWithBlankRemotePath() {
        // A malformed row (no remote path) is dropped defensively rather than
        // producing a bogus tile.
        val decoded = decodeAttachments("\t\timage/png")
        assertTrue(decoded.isEmpty())
    }

    @Test
    fun decodeFallsBackDisplayNameToLastPathSegment() {
        // A row with an empty display-name field rehydrates the name from the
        // remote path's last segment so the tile is never blank.
        val decoded = decodeAttachments("~/a/b/photo.jpg\t\t")
        assertEquals(1, decoded.size)
        assertEquals("photo.jpg", decoded.single().displayName)
        assertNull(decoded.single().mimeType)
    }

    @Test
    fun inMemoryStoreSavesLoadsAndClearsAttachmentsPerSession() {
        val store = InMemoryComposerDraftStore()
        val refs = listOf(DurableAttachmentRef("~/a.png", "a.png", "image/png"))
        store.saveAttachments("1/a", refs)
        assertEquals(refs, store.loadAttachments("1/a"))
        // No bleed across sessions.
        assertTrue(store.loadAttachments("1/b").isEmpty())
        // Saving an empty list clears the slot.
        store.saveAttachments("1/a", emptyList())
        assertTrue(store.loadAttachments("1/a").isEmpty())
        // Explicit clear is idempotent.
        store.saveAttachments("1/a", refs)
        store.clearAttachments("1/a")
        assertTrue(store.loadAttachments("1/a").isEmpty())
    }
}
