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
}
