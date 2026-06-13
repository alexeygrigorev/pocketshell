package com.pocketshell.core.terminal.selection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the conservative file-path detection used by issue #500's
 * tap-to-open feature. Exercises [detectFilePathsInLine] directly (the pure,
 * Android-free core of [findVisibleFilePaths]).
 */
class FilePathScannerTest {

    private fun paths(line: String, excluded: List<IntRange> = emptyList()): List<String> =
        detectFilePathsInLine(line, excluded).map { it.path }

    // --- Positive: real file paths are detected -----------------------------

    @Test
    fun detectsProjectRelativePngPathTheAgentEmitted() {
        val line = "Wrote tmp/terrain-textures/alpine-ground-hex-sheet-b03.png"
        assertEquals(
            listOf("tmp/terrain-textures/alpine-ground-hex-sheet-b03.png"),
            paths(line),
        )
    }

    @Test
    fun detectsAbsolutePathWithExtension() {
        assertEquals(
            listOf("/home/me/out/report.txt"),
            paths("saved to /home/me/out/report.txt now"),
        )
    }

    @Test
    fun detectsGeneratedImageAbsolutePathFromIssue611() {
        val generated =
            "/home/alexey/.codex/generated_images/" +
                "019e9d03-13bc-7280-8d97-40a592fbfcb0/" +
                "ig_04202f5df68d850a016a255de6bac8819197d2528102528ee2.png"

        assertEquals(listOf(generated), paths("generated image: $generated"))
    }

    @Test
    fun detectsFileUriAsDecodedAbsolutePathFromIssue611() {
        val decoded =
            "/home/alexey/.codex/generated_images/" +
                "019e9d03-13bc-7280-8d97-40a592fbfcb0/" +
                "ig_04202f5df68d850a016a255d81c5d48191ad5bc191b780d5c1.png"
        val uri = "file://$decoded"

        assertEquals(listOf(decoded), paths("generated image: $uri"))
    }

    @Test
    fun detectsLocalhostFileUriAsDecodedAbsolutePath() {
        val decoded =
            "/home/alexey/.codex/generated_images/" +
                "019e9d03-13bc-7280-8d97-40a592fbfcb0/" +
                "ig_04202f5df68d850a016a255d81c5d48191ad5bc191b780d5c1.png"
        val uri = "file://localhost$decoded"

        assertEquals(listOf(decoded), paths("generated image: $uri"))
    }

    @Test
    fun fileUriPathIsPercentDecodedForViewerRoute() {
        val uri = "file:///home/alexey/.codex/generated_images/a%20b/out%20image.png"

        assertEquals(
            listOf("/home/alexey/.codex/generated_images/a b/out image.png"),
            paths("generated image: $uri"),
        )
    }

    @Test
    fun detectsHomeRelativePath() {
        assertEquals(
            listOf("~/projects/foo/main.kt"),
            paths("open ~/projects/foo/main.kt please"),
        )
    }

    @Test
    fun detectsPocketShellAttachmentPathAsOneHomeRelativeFile() {
        val attachment =
            "~/.pocketshell/attachments/host-1-git-pocketshell-c/" +
                "20260606-153324-01-Screenshot_20260606-153310.png"

        assertEquals(listOf(attachment), paths("Attached files:\n- $attachment"))
    }

    @Test
    fun detectsReportedIssue609AttachmentPathAsHomeRelativeFile() {
        val attachment =
            "~/.pocketshell/attachments/host-1-git-course-management-platform/" +
                "20260607-115723-01-Screenshot_20260607-115718.png"

        assertEquals(listOf(attachment), paths("Attached files:\n- $attachment"))
    }

    @Test
    fun detectsLineBrokenPocketShellAttachmentPathAsOneTarget() {
        val attachment =
            "~/.pocketshell/attachments/host-1-git-pocketshell-c/" +
                "20260606-153324-01-Screenshot_20260606-153310.png"
        val broken =
            "Attached files:\n" +
                "- ~/.pocketshell/attachments/host-1-git-pocketshell-\n" +
                "  c/20260606-153324-01-Screenshot_20260606-153310.png"

        val detected = detectFilePathsInLine(broken)

        assertEquals(listOf(attachment), detected.map { it.path })
    }

    @Test
    fun terminalReportedIssue609AttachmentRowsReassembleBeforePathDetection() {
        val attachment =
            "~/.pocketshell/attachments/host-1-git-course-management-platform/" +
                "20260607-115723-01-Screenshot_20260607-115718.png"
        val rows = markAttachmentContinuationWraps(
            listOf(
                VisualRow(
                    row = 7,
                    text = "- ~/.pocketshell/attachments/host-1-git-course-management-",
                    wrapsToNext = false,
                ),
                VisualRow(
                    row = 8,
                    text = "platform/20260607-115723-01-Screenshot_20260607-115718.png",
                    wrapsToNext = false,
                ),
            ),
        )
        val logical = reassemble(rows).single()

        assertEquals(listOf(attachment), paths(logical.text))
    }

    @Test
    fun wrappedIssue609AttachmentRegionsKeepFullHomeRelativePathOnEachRow() {
        val attachment =
            "~/.pocketshell/attachments/host-1-git-course-management-platform/" +
                "20260607-115723-01-Screenshot_20260607-115718.png"

        val regions = filePathRegionsForRows(
            visualRows = listOf(
                VisualRow(
                    row = 7,
                    text = "- ~/.pocketshell/attachments/host-1-git-course-management-",
                    wrapsToNext = false,
                ),
                VisualRow(
                    row = 8,
                    text = "platform/20260607-115723-01-Screenshot_20260607-115718.png",
                    wrapsToNext = false,
                ),
            ),
            columns = 80,
        )

        assertEquals(listOf(attachment, attachment), regions.map { it.path })
        assertEquals(listOf(7, 8), regions.map { it.row })
    }

    @Test
    fun threeRowIssue609AttachmentRegionsKeepFullHomeRelativePathOnEachRow() {
        val attachment =
            "~/.pocketshell/attachments/host-1-git-course-management-platform/" +
                "20260607-115723-01-Screenshot_20260607-115718.png"

        val regions = filePathRegionsForRows(
            visualRows = listOf(
                VisualRow(
                    row = 7,
                    text = "- ~/.pocketshell/attachments/host-1-git-course-",
                    wrapsToNext = false,
                ),
                VisualRow(
                    row = 8,
                    text = "management-platform/",
                    wrapsToNext = false,
                ),
                VisualRow(
                    row = 9,
                    text = "20260607-115723-01-Screenshot_20260607-115718.png",
                    wrapsToNext = false,
                ),
            ),
            columns = 80,
        )

        assertEquals(listOf(attachment, attachment, attachment), regions.map { it.path })
        assertEquals(listOf(7, 8, 9), regions.map { it.row })
    }

    @Test
    fun terminalAttachmentContinuationRowsReassembleBeforePathDetection() {
        val rows = markAttachmentContinuationWraps(
            listOf(
                VisualRow(
                    row = 4,
                    text = "- ~/.pocketshell/attachments/host-1-git-pocketshell-",
                    wrapsToNext = false,
                ),
                VisualRow(
                    row = 5,
                    text = "c/20260606-153324-01-Screenshot_20260606-153310.png",
                    wrapsToNext = false,
                ),
            ),
        )
        val logical = reassemble(rows).single()

        assertEquals(
            listOf(
                "~/.pocketshell/attachments/host-1-git-pocketshell-c/" +
                    "20260606-153324-01-Screenshot_20260606-153310.png",
            ),
            paths(logical.text),
        )
    }

    @Test
    fun doesNotOpenAttachmentContinuationFragmentAsProjectRelativePath() {
        assertTrue(
            paths("platform/20260607-115723-01-Screenshot_20260607-115718.png")
                .isEmpty(),
        )
    }

    @Test
    fun detectsDotSlashAndDotDotSlashPaths() {
        assertEquals(listOf("./build.gradle.kts"), paths("see ./build.gradle.kts"))
        assertEquals(listOf("../docs/readme.md"), paths("cat ../docs/readme.md"))
    }

    @Test
    fun detectsDottedFilenameWithKnownFinalExtension() {
        // build.gradle.kts: dotted name, final ext `kts` is on the allowlist.
        assertEquals(
            listOf("app/build.gradle.kts"),
            paths("edit app/build.gradle.kts"),
        )
    }

    @Test
    fun detectsMultiplePathsOnOneLine() {
        val line = "diff src/a.kt and src/b.kt"
        assertEquals(listOf("src/a.kt", "src/b.kt"), paths(line))
    }

    @Test
    fun detectsImageExtensionsVariety() {
        listOf("out/a.png", "out/b.jpg", "out/c.jpeg", "out/d.gif", "out/e.webp", "out/f.svg")
            .forEach { p ->
                assertEquals(listOf(p), paths("file $p here"))
            }
    }

    // --- Trailing punctuation -----------------------------------------------

    @Test
    fun stripsTrailingSentencePunctuation() {
        assertEquals(listOf("out/report.png"), paths("It is at out/report.png."))
        assertEquals(listOf("out/report.png"), paths("(see out/report.png)"))
        assertEquals(listOf("out/report.png"), paths("at out/report.png, then"))
    }

    // --- Span correctness ---------------------------------------------------

    @Test
    fun reportsCorrectSpan() {
        val line = "x out/r.png y"
        val detected = detectFilePathsInLine(line).single()
        assertEquals("out/r.png", detected.path)
        assertEquals(line.indexOf("out/r.png"), detected.start)
        assertEquals(line.indexOf("out/r.png") + "out/r.png".length, detected.endExclusive)
    }

    // --- Negative: NOT over-matched -----------------------------------------

    @Test
    fun doesNotMatchBareWordsOrFlags() {
        assertTrue(paths("run make all now").isEmpty())
        assertTrue(paths("rm -rf --verbose --force").isEmpty())
        assertTrue(paths("the quick brown fox").isEmpty())
    }

    @Test
    fun doesNotMatchBareFilenameWithoutDirectory() {
        // Too prose-like; a project-relative path needs at least one slash.
        assertTrue(paths("the README.md mentions it").isEmpty())
        assertTrue(paths("see report.png").isEmpty())
    }

    @Test
    fun doesNotMatchFractionsRatiosOrShorthand() {
        assertTrue(paths("about 5/2 of them").isEmpty())
        assertTrue(paths("pi is 22/7").isEmpty())
        assertTrue(paths("status: n/a").isEmpty())
        assertTrue(paths("the TCP/IP stack").isEmpty())
        assertTrue(paths("at 1024 Bytes/sec").isEmpty())
        assertTrue(paths("choose y/n").isEmpty())
    }

    @Test
    fun doesNotMatchUnknownExtensions() {
        assertTrue(paths("blob out/data.zzz here").isEmpty())
        assertTrue(paths("ratio a/b.q value").isEmpty())
        // json5 (unknown) must not partial-match as json.
        assertTrue(paths("config app/settings.json5 used").isEmpty())
    }

    @Test
    fun doesNotMatchPlainNumbersOrVersions() {
        assertTrue(paths("version 1.2.3 released").isEmpty())
        assertTrue(paths("score 100/100 today").isEmpty())
    }

    // --- URL exclusion ------------------------------------------------------

    @Test
    fun skipsCandidateInsideExcludedUrlRange() {
        // Simulate a URL claim covering the whole http(s) span; the `/a.png`
        // tail must not surface as a file path.
        val line = "see https://example.com/a.png done"
        val urlStart = line.indexOf("https://")
        val urlEnd = line.indexOf(" done")
        assertTrue(paths(line, listOf(urlStart until urlEnd)).isEmpty())
    }

    @Test
    fun extensionAllowlistIsLowercase() {
        // Guard: the endsWithKnownExtension check lowercases, so an uppercase
        // extension on a real path still matches.
        assertEquals(listOf("out/Report.PNG"), paths("file out/Report.PNG here"))
    }

    // --- Issue #753: quoted paths containing whitespace ---------------------

    @Test
    fun detectsDoubleQuotedAbsolutePathWithSpaces() {
        assertEquals(
            listOf("/home/alexey/My Documents/report final.pdf"),
            paths("""open "/home/alexey/My Documents/report final.pdf" please"""),
        )
    }

    @Test
    fun detectsSingleQuotedAbsolutePathWithSpaces() {
        assertEquals(
            listOf("/var/log/My App/output log.txt"),
            paths("wrote '/var/log/My App/output log.txt' to disk"),
        )
    }

    @Test
    fun detectsQuotedTildePathWithSpaces() {
        assertEquals(
            listOf("~/My Screenshots/capture one.png"),
            paths("""saved to "~/My Screenshots/capture one.png""""),
        )
    }

    @Test
    fun quotedPathSpanIncludesTheQuotes() {
        val line = """open "/a b/c.png" now"""
        val detected = detectFilePathsInLine(line)
        assertEquals(1, detected.size)
        assertEquals("/a b/c.png", detected[0].path)
        assertEquals(line.indexOf('"'), detected[0].start)
        assertEquals(line.indexOf('"') + "\"/a b/c.png\"".length, detected[0].endExclusive)
    }

    @Test
    fun quotedInnerPathIsNotReSurfacedPiecewise() {
        // The unquoted matcher must not also emit the inner `/path` of a quoted
        // whitespace path — exactly one link spanning the quotes.
        assertEquals(1, detectFilePathsInLine("""x "/a b/c.png" y""").size)
    }

    @Test
    fun quotedProseStringIsNotAFilePath() {
        assertTrue(paths("""he said "hello there friend" loudly""").isEmpty())
    }

    @Test
    fun quotedNonFileTargetWithSpacesIsNotAFilePath() {
        // Quoting alone is not enough; the quoted token must look like a file
        // (rooted + known extension).
        assertTrue(paths("""run "/usr/bin/some command" now""").isEmpty())
    }

    @Test
    fun quotedRelativePathWithoutRootIsNotAFilePath() {
        // A quoted token must be explicitly rooted to claim its spaces.
        assertTrue(paths("""title is "my report.pdf draft" here""").isEmpty())
    }

    // --- Issue #753: terminal-grid parity -----------------------------------

    @Test
    fun terminalGridWrappedGeneralPathReassemblesToOnePathPerRow() {
        // A non-attachment absolute path the emulator wrapped across two rows
        // (the wrap flag is set) is detected as ONE path, surfaced on each row
        // fragment so any tapped fragment opens the full file (#558/#753).
        val full = "/home/alexey/inbox/pocketshell/screenshot-20260613-091500.png"
        val splitAt = full.indexOf("screenshot")
        val regions = filePathRegionsForRows(
            visualRows = listOf(
                VisualRow(row = 3, text = full.take(splitAt), wrapsToNext = true),
                VisualRow(row = 4, text = full.drop(splitAt), wrapsToNext = false),
            ),
            columns = 80,
        )

        assertEquals(listOf(full, full), regions.map { it.path })
        assertEquals(listOf(3, 4), regions.map { it.row })
    }

    @Test
    fun terminalGridQuotedWhitespacePathIsDetected() {
        val regions = filePathRegionsForRows(
            visualRows = listOf(
                VisualRow(
                    row = 2,
                    text = """saved "/home/alexey/My Docs/report final.pdf" ok""",
                    wrapsToNext = false,
                ),
            ),
            columns = 80,
        )

        assertEquals(
            listOf("/home/alexey/My Docs/report final.pdf"),
            regions.map { it.path },
        )
    }
}
