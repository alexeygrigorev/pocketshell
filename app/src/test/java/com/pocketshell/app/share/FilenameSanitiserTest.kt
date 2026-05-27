package com.pocketshell.app.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [FilenameSanitiser] (issue #138 acceptance: filename
 * sanitisation must cover path traversal, null bytes, very long names,
 * Unicode).
 *
 * The sanitiser is consumed by the share-target upload pipeline, so
 * defects here mean we'd land files outside the inbox or under shell-
 * unsafe names. Coverage targets are framed around concrete bad
 * inputs an Android share sheet might hand us.
 */
class FilenameSanitiserTest {

    @Test
    fun pathTraversalSegmentsAreStripped() {
        val result = FilenameSanitiser.sanitise("../../../etc/passwd")
        // The basename of `../../../etc/passwd` is `passwd`; the
        // ascending `../` segments are dropped wholesale.
        assertEquals("passwd", result.render())
        assertTrue(
            "expected no `..` to survive sanitisation",
            !result.render().contains(".."),
        )
        assertTrue(
            "expected no `/` to survive sanitisation",
            !result.render().contains('/'),
        )
    }

    @Test
    fun backslashesAreTreatedAsPathSeparators() {
        val result = FilenameSanitiser.sanitise("..\\..\\Windows\\System32\\drivers\\hosts")
        assertEquals("hosts", result.render())
    }

    @Test
    fun nullBytesAreStripped() {
        // Inject literal NUL bytes into the middle of an otherwise
        // plain filename. The sanitiser must drop them. Unicode
        // escapes keep the test source ASCII-safe so editors and diff
        // viewers don't choke on raw control bytes.
        val nul = '\u0000'
        val withNull = "safe${nul}name${nul}.txt"
        val result = FilenameSanitiser.sanitise(withNull)
        assertEquals("safename.txt", result.render())
        assertTrue(
            "expected NUL bytes to be stripped, got ${result.render()}",
            !result.render().contains(nul),
        )
    }

    @Test
    fun controlCharactersAreStripped() {
        // BEL (0x07), ESC (0x1B), and DEL (0x7F) injected into the
        // middle of a normal filename. The sanitiser should drop them
        // all while leaving the printable characters intact.
        val bel = '\u0007'
        val esc = '\u001b'
        val del = '\u007f'
        val withControls = "foo${bel}bar${esc}baz${del}.txt"
        val result = FilenameSanitiser.sanitise(withControls)
        assertEquals("foobarbaz.txt", result.render())
    }

    @Test
    fun spacesBecomeUnderscores() {
        val result = FilenameSanitiser.sanitise("Recording 2024-05-14 09 30 12.m4a")
        assertEquals("Recording_2024-05-14_09_30_12.m4a", result.render())
    }

    @Test
    fun verylongNamesAreCapped() {
        val longStem = "a".repeat(500)
        val result = FilenameSanitiser.sanitise("$longStem.txt")
        assertTrue(
            "expected rendered name to fit under ${FilenameSanitiser.MAX_LENGTH + 1}, got length=${result.render().length}",
            result.render().length <= FilenameSanitiser.MAX_LENGTH,
        )
        assertTrue(
            "expected `.txt` extension to survive the length cap",
            result.render().endsWith(".txt"),
        )
    }

    @Test
    fun extremelyLongNamesPreserveExtension() {
        val result = FilenameSanitiser.sanitise("y".repeat(10_000) + ".pdf")
        assertTrue("expected ext preserved", result.ext == "pdf")
        assertTrue(
            "expected total length capped at ${FilenameSanitiser.MAX_LENGTH}",
            result.render().length <= FilenameSanitiser.MAX_LENGTH,
        )
    }

    @Test
    fun unicodeLettersAreRetained() {
        // Cyrillic + accented Latin + CJK characters survive — they
        // are valid filename codepoints on Linux and there is no
        // reason to strip them. Spaces still become `_`.
        val result = FilenameSanitiser.sanitise("Заметка ñ 笔记.txt")
        assertEquals("Заметка_ñ_笔记.txt", result.render())
    }

    @Test
    fun unicodeEmojiCollapseGracefully() {
        // Emoji are non-letter codepoints — sanitiser maps them to `_`
        // and the underscore-collapse rule yields a single underscore.
        val result = FilenameSanitiser.sanitise("happy😀.png")
        // The important properties are no emoji surrogate survives
        // and the extension is preserved.
        assertTrue(
            "expected emoji high surrogate stripped, got ${result.render()}",
            !result.render().contains('\uD83D'),
        )
        assertEquals("png", result.ext)
    }

    @Test
    fun trailingSeparatorsAreTrimmed() {
        val result = FilenameSanitiser.sanitise("__report__.txt")
        // Leading and trailing `_` are dropped; the dot before the
        // extension survives.
        assertEquals("report.txt", result.render())
    }

    @Test
    fun pureDotsFallBackToDefault() {
        val result = FilenameSanitiser.sanitise("...")
        assertEquals(FilenameSanitiser.DEFAULT_NAME, result.base)
        assertEquals("", result.ext)
    }

    @Test
    fun emptyInputFallsBackToDefault() {
        val result = FilenameSanitiser.sanitise("")
        assertEquals(FilenameSanitiser.DEFAULT_NAME, result.base)
        assertEquals("", result.ext)
    }

    @Test
    fun nullInputFallsBackToDefault() {
        val result = FilenameSanitiser.sanitise(null)
        assertEquals(FilenameSanitiser.DEFAULT_NAME, result.base)
        assertEquals("", result.ext)
    }

    @Test
    fun defaultExtensionAppliedWhenMissing() {
        val result = FilenameSanitiser.sanitise("notes", defaultExtension = "txt")
        assertEquals("notes.txt", result.render())
        assertEquals("txt", result.ext)
    }

    @Test
    fun defaultExtensionIgnoredWhenOriginalHasOne() {
        val result = FilenameSanitiser.sanitise("notes.md", defaultExtension = "txt")
        // The original extension wins — `defaultExtension` is for
        // share intents that arrive without a name at all.
        assertEquals("notes.md", result.render())
        assertEquals("md", result.ext)
    }

    @Test
    fun composeRemoteNamePrependsTimestamp() {
        val sanitised = FilenameSanitiser.sanitise("photo.png")
        val name = FilenameSanitiser.composeRemoteName("20240514-093012", sanitised)
        assertEquals("20240514-093012-photo.png", name)
    }

    @Test
    fun composeRemoteNameWithEmptyExtension() {
        val sanitised = FilenameSanitiser.sanitise("README")
        val name = FilenameSanitiser.composeRemoteName("20240514-093012", sanitised)
        assertEquals("20240514-093012-README", name)
    }

    @Test
    fun leadingDotFilenamesAreNotHidden() {
        // `.bashrc` would become a hidden file on the remote; we don't
        // want that for an inbox upload. The sanitiser maps the
        // leading dot away so the file is plainly visible.
        val result = FilenameSanitiser.sanitise(".bashrc")
        assertNotEquals("expected leading-dot files to not stay hidden", '.', result.render().firstOrNull())
        assertTrue("expected `bashrc` to survive", result.render().contains("bashrc"))
    }

    @Test
    fun shellMetacharactersAreReplaced() {
        // `$`, backtick, `;`, `|`, `&`, `>` and friends are all
        // shell-interpretable; they must not survive into the remote
        // filename even though SCP is supposed to insulate us.
        val result = FilenameSanitiser.sanitise("rm -rf \$HOME;`evil`.txt")
        val rendered = result.render()
        listOf('$', '`', ';', '|', '&', '>', '<', '*', '?', '!', '(', ')').forEach { bad ->
            assertTrue(
                "expected shell metacharacter `$bad` to be stripped from `$rendered`",
                !rendered.contains(bad),
            )
        }
        assertEquals("txt", result.ext)
    }

    @Test
    fun newlinesAreNormalisedAsWhitespace() {
        val result = FilenameSanitiser.sanitise("multi\nline\nname.txt")
        assertEquals("multi_line_name.txt", result.render())
    }
}
