package com.pocketshell.app.fileviewer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the pure share/copy helpers from the file viewer (issue #559): the MIME
 * type derivation (so receiving apps accept the file) and the filesystem-safe
 * share-file name derivation.
 */
class FileViewerShareHelpersTest {

    @Test
    fun imageMimeMapsKnownExtensions() {
        assertEquals("image/png", imageMimeFor("/tmp/shot.png"))
        assertEquals("image/jpeg", imageMimeFor("/tmp/photo.jpg"))
        assertEquals("image/jpeg", imageMimeFor("/tmp/photo.JPEG"))
        assertEquals("image/webp", imageMimeFor("/tmp/a.webp"))
        assertEquals("image/gif", imageMimeFor("/tmp/a.gif"))
        assertEquals("image/bmp", imageMimeFor("/tmp/a.bmp"))
    }

    @Test
    fun imageMimeFallsBackToWildcardForUnknownExtension() {
        // Content-sniffed image with a misleading/absent suffix.
        assertEquals("image/*", imageMimeFor("/tmp/screenshot"))
        assertEquals("image/*", imageMimeFor("/tmp/data.bin"))
    }

    @Test
    fun audioMimeMapsKnownExtensions() {
        assertEquals("audio/mpeg", audioMimeFor("/tmp/note.mp3"))
        assertEquals("audio/wav", audioMimeFor("/tmp/clip.wav"))
        assertEquals("audio/mp4", audioMimeFor("/tmp/voice.m4a"))
        assertEquals("audio/mp4", audioMimeFor("/tmp/voice.aac"))
        assertEquals("audio/ogg", audioMimeFor("/tmp/a.ogg"))
        assertEquals("audio/flac", audioMimeFor("/tmp/a.flac"))
    }

    @Test
    fun audioMimeFallsBackToWildcardForUnknownExtension() {
        assertEquals("audio/*", audioMimeFor("/tmp/voicenote"))
    }

    @Test
    fun shareFileNameKeepsBasenameAndExtension() {
        assertEquals("notes.txt", shareFileName("/home/me/notes.txt", "txt"))
        assertEquals("shot.png", shareFileName("/tmp/shot.png", "txt"))
    }

    @Test
    fun shareFileNameAppliesFallbackExtensionWhenMissing() {
        assertEquals("README.txt", shareFileName("/repo/README", "txt"))
    }

    @Test
    fun shareFileNameSanitizesUnsafeCharacters() {
        // Spaces and shell-unfriendly chars collapse to underscores; the dot
        // (so the extension survives) and -_ are kept.
        val name = shareFileName("/tmp/my report (final).log", "txt")
        assertEquals("my_report__final_.log", name)
    }

    @Test
    fun shareFileNameHandlesBlankBasename() {
        assertEquals("file.txt", shareFileName("/some/dir/", "txt"))
    }
}
