package com.pocketshell.core.terminal.selection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for soft-wrap reassembly + span mapping used to detect a URL/path
 * wrapped across terminal rows as one logical target (issue #558 bug 2).
 */
@RunWith(RobolectricTestRunner::class)
class WrappedLineReassemblyTest {

    /**
     * Issue #2269: the agent rendered ordinary text (not OSC 8) into two
     * full-width physical rows.  This is the exact pair photographed on the
     * maintainer's Pixel 7.  The terminal metadata for this agent shape has no
     * soft-wrap bit or OSC 8 hyperlink provenance; the terminal's separate
     * cursor-addressed hard-wrap bit is the scanner's boundary signal instead
     * of treating the second fragment as a fresh local path.
     */
    @Test
    fun `issue 2269 exact screenshot URLs stay complete across plain text hard wraps`() {
        val blobUrl =
            "https://github.com/alexeygrigorev/ai-book-generator/" +
                "blob/main/books/mountains-ru/part_01/01_chapter.md"
        val treeUrl =
            "https://github.com/alexeygrigorev/ai-book-generator/" +
                "tree/main/books/mountains-ru"
        val columns = 60
        val rows = listOf(
            VisualRow(row = 100, text = blobUrl.take(columns), wrapsToNext = false),
            VisualRow(
                row = 101,
                text = blobUrl.drop(columns),
                wrapsToNext = false,
                startsAfterHardWrap = true,
            ),
            VisualRow(row = 102, text = treeUrl.take(columns), wrapsToNext = false),
            VisualRow(
                row = 103,
                text = treeUrl.drop(columns),
                wrapsToNext = false,
                startsAfterHardWrap = true,
            ),
        )

        assertEquals(
            "plain agent output must have no OSC 8 boundary provenance",
            List(8) { false },
            rows.flatMap { listOf(it.startsWithOsc8Hyperlink, it.endsWithOsc8Hyperlink) },
        )
        assertEquals(
            "plain agent output records only the two continuation row boundaries as hard wraps",
            listOf(false, true, false, true),
            rows.map { it.startsAfterHardWrap },
        )
        assertEquals(
            "plain agent output has no native soft-wrap flags",
            List(4) { false },
            rows.map { it.wrapsToNext },
        )

        val urls = urlRegionsForRows(rows, columns)
        assertEquals(
            "every visual fragment must be exposed",
            listOf(100, 101, 102, 103),
            urls.map { it.row },
        )
        assertEquals(
            "every visible fragment must dispatch its exact complete URL",
            listOf(blobUrl, blobUrl, treeUrl, treeUrl),
            urls.map { it.url },
        )

        val paths = filePathRegionsForRows(rows, columns)
        assertTrue(
            "a URL continuation must never become a local path: $paths",
            paths.isEmpty(),
        )

        val matches = terminalMatchRegionsForRows(rows, columns, DefaultTerminalMatcher())
        val matchedUrls = matches.filter { it.match is TerminalMatch.Url }
        assertEquals(listOf(100, 101, 102, 103), matchedUrls.map { it.row })
        assertEquals(
            listOf(blobUrl, blobUrl, treeUrl, treeUrl),
            matchedUrls.map { it.match.value },
        )
        assertTrue(
            "smart selection must not classify the continuation as a path: $matches",
            matches.none { it.match is TerminalMatch.Path },
        )
    }

    @Test
    fun `issue 2269 exact URLs survive narrower multi-row hard wraps`() {
        val blobUrl =
            "https://github.com/alexeygrigorev/ai-book-generator/" +
                "blob/main/books/mountains-ru/part_01/01_chapter.md"
        val treeUrl =
            "https://github.com/alexeygrigorev/ai-book-generator/" +
                "tree/main/books/mountains-ru"
        val columns = 37
        val blobParts = blobUrl.chunked(columns)
        val treeParts = treeUrl.chunked(columns)
        val rows = buildList {
            blobParts.forEachIndexed { index, part ->
                add(
                    VisualRow(
                        row = size,
                        text = part,
                        wrapsToNext = false,
                        startsAfterHardWrap = index > 0,
                    ),
                )
            }
            add(VisualRow(row = size, text = "", wrapsToNext = false))
            treeParts.forEachIndexed { index, part ->
                add(
                    VisualRow(
                        row = size,
                        text = part,
                        wrapsToNext = false,
                        startsAfterHardWrap = index > 0,
                    ),
                )
            }
        }

        val urls = urlRegionsForRows(rows, columns)
        assertEquals(
            blobParts.map { blobUrl } + treeParts.map { treeUrl },
            urls.map { it.url },
        )
        assertEquals(
            "narrow legitimate URL chunks must not become local paths",
            emptyList<FilePathRegion>(),
            filePathRegionsForRows(rows, columns),
        )
    }

    /**
     * Issue #1955 exact real-device row shape: the agent TUI painted a URL to
     * the final grid column, continued it at column zero, but neither row kept
     * Termux's soft-wrap bit.  The screenshot split `campaigns` as
     * `campaig` / `ns/...` in a 60-column viewport.
     */
    @Test
    fun `issue 1955 hard wrapped exact github URL is one target and not a path`() {
        val url =
            "https://github.com/DataTalksClub/playbooks/blob/main/" +
                "campaigns/ml-zoomcamp-2026/copy-bank/events/pre-course-live-qa.md"
        val splitAt = url.indexOf("campaigns") + "campaig".length
        val columns = splitAt
        val rows = listOf(
            VisualRow(
                row = 0,
                text = url.take(splitAt),
                wrapsToNext = false,
                endsWithOsc8Hyperlink = true,
            ),
            VisualRow(
                row = 1,
                text = url.drop(splitAt),
                wrapsToNext = false,
                startsWithOsc8Hyperlink = true,
            ),
        )

        assertEquals("the screenshot's first fragment must fill the grid", columns, rows[0].text.length)
        assertEquals("campaig", rows[0].text.takeLast("campaig".length))
        assertTrue(rows[1].text.startsWith("ns/ml-zoomcamp"))

        val urlRegions = urlRegionsForRows(rows, columns)
        assertEquals("both visible fragments must be tappable", listOf(0, 1), urlRegions.map { it.row })
        assertTrue(
            "both fragments must carry the complete exact URI: $urlRegions",
            urlRegions.all { it.url == url },
        )

        val decorated = terminalMatchRegionsForRows(rows, columns, DefaultTerminalMatcher())
        val decoratedUrls = decorated.filter { it.match is TerminalMatch.Url }
        assertEquals("both fragments must have URL decoration", listOf(0, 1), decoratedUrls.map { it.row })
        assertTrue(
            "every decoration must carry the same complete URI: $decoratedUrls",
            decoratedUrls.all { it.match.value == url },
        )
        assertTrue(
            "the continuation must not become a local-file target: $decorated",
            decorated.none { it.match is TerminalMatch.Path },
        )
        assertTrue(
            "the continuation must not be exposed by the file viewer overlay",
            filePathRegionsForRows(rows, columns).isEmpty(),
        )
    }

    @Test
    fun `issue 1955 second reported course launch URL follows the same hard wrap contract`() {
        val url =
            "https://github.com/DataTalksClub/playbooks/blob/main/" +
                "campaigns/ml-zoomcamp-2026/copy-bank/events/course-launch.md"
        val splitAt = url.indexOf("campaigns") + "campaig".length
        val rows = listOf(
            VisualRow(
                row = 2,
                text = url.take(splitAt),
                wrapsToNext = false,
                endsWithOsc8Hyperlink = true,
            ),
            VisualRow(
                row = 3,
                text = url.drop(splitAt),
                wrapsToNext = false,
                startsWithOsc8Hyperlink = true,
            ),
        )

        val urls = urlRegionsForRows(rows, columns = splitAt)
        assertEquals(listOf(2, 3), urls.map { it.row })
        assertTrue(urls.all { it.url == url })
        assertTrue(filePathRegionsForRows(rows, columns = splitAt).isEmpty())
    }

    @Test
    fun `hard wrap inference does not join a full-width URL to newline prose`() {
        val first = "https://example.com/" + "a".repeat(40)
        val rows = listOf(
            VisualRow(row = 4, text = first, wrapsToNext = false),
            VisualRow(row = 5, text = "follow-up prose is a genuine new paragraph.", wrapsToNext = false),
        )

        val urls = urlRegionsForRows(rows, columns = first.length)

        assertEquals(listOf(UrlRegion(first, row = 4, startCol = 0, endColExclusive = first.length)), urls)
    }

    @Test
    fun `hard wrap inference does not join adjacent independent links`() {
        val first = "https://example.com/" + "a".repeat(40)
        val second = "https://github.com/DataTalksClub/playbooks"
        val rows = listOf(
            VisualRow(row = 6, text = first, wrapsToNext = false),
            VisualRow(row = 7, text = second, wrapsToNext = false, startsAfterHardWrap = true),
        )

        val urls = urlRegionsForRows(rows, columns = first.length)

        assertEquals(listOf(first, second), urls.map { it.url })
        assertEquals(listOf(6, 7), urls.map { it.row })
    }

    @Test
    fun `marked unrelated path stays separate from a preceding URL`() {
        val first = "https://example.com/" + "a".repeat(40)
        val second = "docs/readme.md"
        val rows = listOf(
            VisualRow(row = 60, text = first, wrapsToNext = false),
            VisualRow(
                row = 61,
                text = second,
                wrapsToNext = false,
                startsAfterHardWrap = true,
            ),
        )

        val urls = urlRegionsForRows(rows, columns = first.length)
        assertEquals(listOf(first), urls.map { it.url })
        assertEquals(listOf(60), urls.map { it.row })
        assertEquals(listOf("docs/readme.md"), filePathRegionsForRows(rows, first.length).map { it.path })
    }

    @Test
    fun `marked newline prose stays separate from a preceding URL`() {
        val first = "https://example.com/" + "a".repeat(40)
        val second = "follow-up prose is a genuine new paragraph"
        val rows = listOf(
            VisualRow(row = 62, text = first, wrapsToNext = false),
            VisualRow(row = 63, text = second, wrapsToNext = false, startsAfterHardWrap = true),
        )

        val repaired = markHardWrappedUrlContinuations(rows, first.length)
        assertEquals(listOf(false, false), repaired.map { it.wrapsToNext })
        assertEquals(listOf(first, second), reassemble(repaired).map { it.text })
        assertEquals(listOf(first), urlRegionsForRows(rows, first.length).map { it.url })
    }

    @Test
    fun `marked rooted and deep project paths stay separate from a preceding URL`() {
        val first = "https://example.com/" + "a".repeat(40)
        for (second in listOf("/tmp/readme.md", "src/main.kt", "docs/readme")) {
            val rows = listOf(
                VisualRow(row = 64, text = first, wrapsToNext = false),
                VisualRow(row = 65, text = second, wrapsToNext = false, startsAfterHardWrap = true),
            )

            val repaired = markHardWrappedUrlContinuations(rows, first.length)
            assertEquals("path $second must not be inferred as a URL continuation", listOf(false, false), repaired.map { it.wrapsToNext })
            assertEquals(listOf(first), urlRegionsForRows(rows, first.length).map { it.url })
        }
    }

    @Test
    fun `adjacent independent OSC8 links do not become one target`() {
        val first = "https://example.com/" + "a".repeat(40)
        val second = "https://github.com/DataTalksClub/playbooks"
        val rows = listOf(
            VisualRow(
                row = 8,
                text = first,
                wrapsToNext = false,
                endsWithOsc8Hyperlink = true,
            ),
            VisualRow(
                row = 9,
                text = second,
                wrapsToNext = false,
                startsWithOsc8Hyperlink = true,
                startsNewOsc8Hyperlink = true,
            ),
        )

        assertEquals(listOf(first, second), urlRegionsForRows(rows, first.length).map { it.url })
        assertEquals(listOf(8, 9), urlRegionsForRows(rows, first.length).map { it.row })
    }

    @Test
    fun `issue 1955 does not join a full width URL to newline docs path prose`() {
        assertHardWrapRowsStayIndependent(
            second = "docs/readme.md is the next paragraph",
            expectedPaths = listOf("docs/readme.md"),
        )
    }

    @Test
    fun `issue 1955 does not join a full width URL to newline namespace prose`() {
        assertHardWrapRowsStayIndependent(
            second = "ns/ml-zoomcamp is prose but looks path-like",
            expectedPaths = emptyList(),
        )
    }

    @Test
    fun `issue 1955 does not join punctuation led newline path prose`() {
        assertHardWrapRowsStayIndependent(
            second = "(docs/readme.md) begins with punctuation",
            expectedPaths = listOf("docs/readme.md"),
        )
    }

    @Test
    fun `issue 1955 does not join an unrelated full width path row`() {
        val first = fullWidthStandaloneUrl()
        val second = "unrelated/full-width/row.md".padEnd(first.length, 'x')
        assertHardWrapRowsStayIndependent(
            second = second,
            expectedPaths = emptyList(),
        )
    }

    @Test
    fun `issue 1955 complete github blob URL does not absorb a deep newline path`() {
        val first = "https://github.com/org/repo/blob/main/docs/readme.md"
        assertHardWrapRowsStayIndependent(
            first = first,
            second = "ns/a/b/next-file.md is a separate row",
            expectedPaths = listOf("ns/a/b/next-file.md"),
        )
    }

    @Test
    fun `issue 1955 short newline path is not inferred as a continuation`() {
        val first = "https://github.com/org/repo/blob/main/guide"
        assertHardWrapRowsStayIndependent(
            first = first,
            second = "ns/file.md is a separate row",
            expectedPaths = listOf("ns/file.md"),
        )
    }

    @Test
    fun `issue 1955 extensionless blob URL does not absorb a deep newline path`() {
        assertHardWrapRowsStayIndependent(
            first = "https://github.com/org/repo/blob/main/guide",
            second = "ns/a/b/next-file.md is a separate row",
            expectedPaths = listOf("ns/a/b/next-file.md"),
        )
    }

    @Test
    fun `issue 1955 extensionless blob URL does not absorb a path-only row`() {
        assertHardWrapRowsStayIndependent(
            first = "https://github.com/org/repo/blob/main/campaign",
            second = "ns/a/b/c.txt",
            expectedPaths = listOf("ns/a/b/c.txt"),
        )
    }

    @Test
    fun `issue 1955 query-bearing URL does not absorb a deep newline path`() {
        assertHardWrapRowsStayIndependent(
            first = "https://github.com/org/repo/blob/main/guide?x",
            second = "ns/a/b/next-file.md is separate",
            expectedPaths = listOf("ns/a/b/next-file.md"),
        )
    }

    @Test
    fun `issue 1955 extensionless blob URL does not absorb a mixed-case path`() {
        assertHardWrapRowsStayIndependent(
            first = "https://github.com/org/repo/blob/main/guide",
            second = "ns/a/b/README.md",
            expectedPaths = listOf("ns/a/b/README.md"),
        )
    }

    @Test
    fun `matching visual style without OSC8 provenance does not join newline rows`() {
        // SGR colour/effect equality is intentionally absent from VisualRow's
        // continuation contract: two independent rows can have identical SGR.
        assertHardWrapRowsStayIndependent(
            first = "https://github.com/org/repo/blob/main/guide",
            second = "ns/a/b/README.md",
            expectedPaths = listOf("ns/a/b/README.md"),
        )
    }

    @Test
    fun `non-wrapping rows become one logical line each`() {
        val rows = listOf(
            VisualRow(0, "first", wrapsToNext = false),
            VisualRow(1, "second", wrapsToNext = false),
        )
        val logical = reassemble(rows)
        assertEquals(2, logical.size)
        assertEquals("first", logical[0].text)
        assertEquals("second", logical[1].text)
    }

    private fun assertHardWrapRowsStayIndependent(
        first: String = fullWidthStandaloneUrl(),
        second: String,
        expectedPaths: List<String>,
    ) {
        val rows = listOf(
            VisualRow(row = 80, text = first, wrapsToNext = false),
            VisualRow(row = 81, text = second, wrapsToNext = false),
        )

        val repaired = markHardWrappedUrlContinuations(rows, columns = first.length)
        assertEquals("genuine newline metadata must remain unchanged", listOf(false, false), repaired.map { it.wrapsToNext })
        assertEquals("genuine newline rows must remain separate", listOf(first, second), reassemble(repaired).map { it.text })
        assertEquals(
            "the first row must retain only its own complete URL",
            listOf(UrlRegion(first, row = 80, startCol = 0, endColExclusive = first.length)),
            urlRegionsForRows(rows, columns = first.length),
        )
        assertEquals(
            "the second row must retain its independent file target or no target",
            expectedPaths,
            filePathRegionsForRows(rows, columns = first.length).map { it.path },
        )
    }

    private fun fullWidthStandaloneUrl(): String = "https://example.com/" + "a".repeat(40)

    @Test
    fun `wrapped rows join into one logical line`() {
        val rows = listOf(
            VisualRow(0, "https://github.com/owner/", wrapsToNext = true),
            VisualRow(1, "very/long/path", wrapsToNext = false),
        )
        val logical = reassemble(rows)
        assertEquals(1, logical.size)
        assertEquals("https://github.com/owner/very/long/path", logical[0].text)
    }

    @Test
    fun `three-row wrap joins fully`() {
        val rows = listOf(
            VisualRow(0, "aaaa", wrapsToNext = true),
            VisualRow(1, "bbbb", wrapsToNext = true),
            VisualRow(2, "cccc", wrapsToNext = false),
        )
        val logical = reassemble(rows)
        assertEquals(1, logical.size)
        assertEquals("aaaabbbbcccc", logical[0].text)
    }

    @Test
    fun `single-row span maps to one row segment`() {
        val logical = LogicalLine(listOf(VisualRow(5, "see /etc/hosts now", wrapsToNext = false)))
        // span covering "/etc/hosts" = indices 4..14
        val spans = logical.mapSpanToRows(4, 14)
        assertEquals(listOf(RowSpan(row = 5, startCol = 4, endColExclusive = 14)), spans)
    }

    @Test
    fun `wrapped span maps to one segment per visual row`() {
        val rows = listOf(
            VisualRow(0, "https://github.com/owner/", wrapsToNext = true),
            VisualRow(1, "very/long/path", wrapsToNext = false),
        )
        val logical = LogicalLine(rows)
        // The whole URL spans the entire logical line: 0..39.
        val spans = logical.mapSpanToRows(0, logical.text.length)
        assertEquals(
            listOf(
                RowSpan(row = 0, startCol = 0, endColExclusive = 25),
                RowSpan(row = 1, startCol = 0, endColExclusive = 14),
            ),
            spans,
        )
    }

    @Test
    fun `span starting mid-first-row across the wrap maps both rows`() {
        val rows = listOf(
            VisualRow(0, "open https://a.com/b/", wrapsToNext = true),
            VisualRow(1, "c/d", wrapsToNext = false),
        )
        val logical = LogicalLine(rows)
        // URL begins at index 5 and runs to end (length 24).
        val spans = logical.mapSpanToRows(5, logical.text.length)
        assertEquals(
            listOf(
                RowSpan(row = 0, startCol = 5, endColExclusive = 21),
                RowSpan(row = 1, startCol = 0, endColExclusive = 3),
            ),
            spans,
        )
    }

    @Test
    fun `wrapped github issue URL emits one full-target decoration region per visual row`() {
        val url =
            "https://github.com/alexeygrigorev/pocketshell/issues/558" +
                "#issuecomment-4638326371"
        val splitAt = url.indexOf("#issuecomment") + 4
        val rows = listOf(
            VisualRow(10, "open ${url.take(splitAt)}", wrapsToNext = true),
            VisualRow(11, url.drop(splitAt), wrapsToNext = false),
        )

        val regions = terminalMatchRegionsForRows(rows, columns = 80, matcher = DefaultTerminalMatcher())
        val urlRegions = regions.filter { it.match is TerminalMatch.Url }

        assertEquals(2, urlRegions.size)
        assertEquals(listOf(10, 11), urlRegions.map { it.row })
        assertTrue(
            "every visual fragment should carry the complete URL: $urlRegions",
            urlRegions.all { it.match.value == url },
        )
        assertEquals(5, urlRegions[0].startCol)
        assertEquals(rows[0].text.length, urlRegions[0].endColExclusive)
        assertEquals(0, urlRegions[1].startCol)
        assertEquals(rows[1].text.length, urlRegions[1].endColExclusive)
    }

    @Test
    fun `single-line URL still emits one full-target decoration region`() {
        val url = "https://github.com/alexeygrigorev/pocketshell/issues/558"
        val rows = listOf(VisualRow(3, "see $url", wrapsToNext = false))

        val regions = terminalMatchRegionsForRows(rows, columns = 100, matcher = DefaultTerminalMatcher())
        val region = regions.single { it.match is TerminalMatch.Url }

        assertEquals(url, region.match.value)
        assertEquals(3, region.row)
        assertEquals(4, region.startCol)
        assertEquals(4 + url.length, region.endColExclusive)
    }

    @Test
    fun `wrapped tilde path emits one full-target decoration region per visual row`() {
        val path =
            "~/projects/pocketshell/shared/core-terminal/src/main/java/" +
                "com/pocketshell/core/terminal/selection/SelectionScanner.kt"
        val splitAt = path.indexOf("com/pocketshell")
        val rows = listOf(
            VisualRow(20, "file ${path.take(splitAt)}", wrapsToNext = true),
            VisualRow(21, path.drop(splitAt), wrapsToNext = false),
        )

        val regions = terminalMatchRegionsForRows(rows, columns = 96, matcher = DefaultTerminalMatcher())
        val pathRegions = regions.filter { it.match is TerminalMatch.Path }

        assertEquals(2, pathRegions.size)
        assertEquals(listOf(20, 21), pathRegions.map { it.row })
        assertTrue(
            "every visual fragment should carry the complete path: $pathRegions",
            pathRegions.all { it.match.value == path },
        )
        assertEquals(5, pathRegions[0].startCol)
        assertEquals(rows[0].text.length, pathRegions[0].endColExclusive)
        assertEquals(0, pathRegions[1].startCol)
        assertEquals(rows[1].text.length, pathRegions[1].endColExclusive)
    }

    @Test
    fun `wrapped generated image absolute path emits full file target per visual row`() {
        val path =
            "/home/alexey/.codex/generated_images/" +
                "019e9d03-13bc-7280-8d97-40a592fbfcb0/" +
                "ig_04202f5df68d850a016a255de6bac8819197d2528102528ee2.png"
        val firstSplit = path.indexOf("019e9d03")
        val secondSplit = path.indexOf("ig_")
        val rows = listOf(
            VisualRow(30, "image ${path.take(firstSplit)}", wrapsToNext = false),
            VisualRow(31, path.substring(firstSplit, secondSplit), wrapsToNext = false),
            VisualRow(32, path.substring(secondSplit), wrapsToNext = false),
        )

        val regions = filePathRegionsForRows(rows, columns = 120)

        assertEquals(3, regions.size)
        assertEquals(listOf(30, 31, 32), regions.map { it.row })
        assertTrue(
            "every visual fragment should carry the complete path: $regions",
            regions.all { it.path == path },
        )
        assertEquals(6, regions[0].startCol)
        assertEquals(rows[0].text.length, regions[0].endColExclusive)
        assertEquals(0, regions[1].startCol)
        assertEquals(rows[1].text.length, regions[1].endColExclusive)
        assertEquals(0, regions[2].startCol)
        assertEquals(rows[2].text.length, regions[2].endColExclusive)
    }

    @Test
    fun `wrapped generated image absolute path emits full smart selection target per visual row`() {
        val path =
            "/home/alexey/.codex/generated_images/" +
                "019e9d03-13bc-7280-8d97-40a592fbfcb0/" +
                "ig_04202f5df68d850a016a255de6bac8819197d2528102528ee2.png"
        val firstSplit = path.indexOf("019e9d03")
        val secondSplit = path.indexOf("ig_")
        val rows = listOf(
            VisualRow(33, "image ${path.take(firstSplit)}", wrapsToNext = false),
            VisualRow(34, path.substring(firstSplit, secondSplit), wrapsToNext = false),
            VisualRow(35, path.substring(secondSplit), wrapsToNext = false),
        )

        val regions = terminalMatchRegionsForRows(rows, columns = 120, matcher = DefaultTerminalMatcher())
        val pathRegions = regions.filter { it.match is TerminalMatch.Path }

        assertEquals(3, pathRegions.size)
        assertEquals(listOf(33, 34, 35), pathRegions.map { it.row })
        assertTrue(
            "every visual fragment should carry the complete path: $pathRegions",
            pathRegions.all { it.match.value == path },
        )
        assertEquals(6, pathRegions[0].startCol)
        assertEquals(rows[0].text.length, pathRegions[0].endColExclusive)
        assertEquals(0, pathRegions[1].startCol)
        assertEquals(rows[1].text.length, pathRegions[1].endColExclusive)
        assertEquals(0, pathRegions[2].startCol)
        assertEquals(rows[2].text.length, pathRegions[2].endColExclusive)
    }

    @Test
    fun `wrapped issue 611 generated image file uri emits decoded file target per visual row`() {
        val decoded =
            "/home/alexey/.codex/generated_images/" +
                "019e9d03-13bc-7280-8d97-40a592fbfcb0/" +
                "ig_04202f5df68d850a016a255d81c5d48191ad5bc191b780d5c1.png"
        val uri = "file://$decoded"
        val firstSplit = uri.indexOf("019e9d03")
        val secondSplit = uri.indexOf("ig_")
        val rows = listOf(
            VisualRow(40, uri.take(firstSplit), wrapsToNext = false),
            VisualRow(41, uri.substring(firstSplit, secondSplit), wrapsToNext = false),
            VisualRow(42, uri.substring(secondSplit), wrapsToNext = false),
        )

        val regions = filePathRegionsForRows(rows, columns = 120)

        assertEquals(3, regions.size)
        assertEquals(listOf(40, 41, 42), regions.map { it.row })
        assertTrue(
            "every visual fragment should carry the decoded complete path: $regions",
            regions.all { it.path == decoded },
        )
        assertEquals(0, regions[0].startCol)
        assertEquals(rows[0].text.length, regions[0].endColExclusive)
        assertEquals(0, regions[1].startCol)
        assertEquals(rows[1].text.length, regions[1].endColExclusive)
        assertEquals(0, regions[2].startCol)
        assertEquals(rows[2].text.length, regions[2].endColExclusive)
    }

    @Test
    fun `wrapped percent encoded generated image file uri emits decoded smart selection target per visual row`() {
        val decoded =
            "/home/alexey/.codex/generated_images/" +
                "019e9d03-13bc-7280-8d97-40a592fbfcb0/" +
                "ig_04202f5df68d850a016a255d81c5d48191ad5bc191b780d5c1 final.png"
        val uri = decoded.replace(" ", "%20").let { "file://$it" }
        val firstSplit = uri.indexOf("019e9d03")
        val secondSplit = uri.indexOf("ig_")
        val rows = listOf(
            VisualRow(43, uri.take(firstSplit), wrapsToNext = false),
            VisualRow(44, uri.substring(firstSplit, secondSplit), wrapsToNext = false),
            VisualRow(45, uri.substring(secondSplit), wrapsToNext = false),
        )

        val regions = terminalMatchRegionsForRows(rows, columns = 120, matcher = DefaultTerminalMatcher())
        val pathRegions = regions.filter { it.match is TerminalMatch.Path }

        assertEquals(3, pathRegions.size)
        assertEquals(listOf(43, 44, 45), pathRegions.map { it.row })
        assertTrue(
            "every visual fragment should carry the decoded complete path: $pathRegions",
            pathRegions.all { it.match.value == decoded },
        )
        assertEquals(0, pathRegions[0].startCol)
        assertEquals(rows[0].text.length, pathRegions[0].endColExclusive)
        assertEquals(0, pathRegions[1].startCol)
        assertEquals(rows[1].text.length, pathRegions[1].endColExclusive)
        assertEquals(0, pathRegions[2].startCol)
        assertEquals(rows[2].text.length, pathRegions[2].endColExclusive)
    }

    @Test
    fun `wrapped PocketShell attachment path emits full smart selection target per visual row`() {
        val attachment =
            "~/.pocketshell/attachments/host-1-git-course-management-platform/" +
                "20260607-115723-01-Screenshot_20260607-115718.png"
        val rows = listOf(
            VisualRow(
                row = 50,
                text = "- ~/.pocketshell/attachments/host-1-git-course-management-",
                wrapsToNext = false,
            ),
            VisualRow(
                row = 51,
                text = "platform/20260607-115723-01-Screenshot_20260607-115718.png",
                wrapsToNext = false,
            ),
        )

        val regions = terminalMatchRegionsForRows(rows, columns = 120, matcher = DefaultTerminalMatcher())
        val pathRegions = regions.filter { it.match is TerminalMatch.Path }

        assertEquals(2, pathRegions.size)
        assertEquals(listOf(50, 51), pathRegions.map { it.row })
        assertTrue(
            "every visual fragment should carry the complete attachment path: $pathRegions",
            pathRegions.all { it.match.value == attachment },
        )
        assertEquals(2, pathRegions[0].startCol)
        assertEquals(rows[0].text.length, pathRegions[0].endColExclusive)
        assertEquals(0, pathRegions[1].startCol)
        assertEquals(rows[1].text.length, pathRegions[1].endColExclusive)
    }

    @Test
    fun `three-row PocketShell attachment path emits full smart selection target per visual row`() {
        val attachment =
            "~/.pocketshell/attachments/host-1-git-course-management-platform/" +
                "20260607-115723-01-Screenshot_20260607-115718.png"
        val rows = listOf(
            VisualRow(
                row = 52,
                text = "- ~/.pocketshell/attachments/host-1-git-course-",
                wrapsToNext = false,
            ),
            VisualRow(
                row = 53,
                text = "management-platform/",
                wrapsToNext = false,
            ),
            VisualRow(
                row = 54,
                text = "20260607-115723-01-Screenshot_20260607-115718.png",
                wrapsToNext = false,
            ),
        )

        val regions = terminalMatchRegionsForRows(rows, columns = 120, matcher = DefaultTerminalMatcher())
        val pathRegions = regions.filter { it.match is TerminalMatch.Path }

        assertEquals(3, pathRegions.size)
        assertEquals(listOf(52, 53, 54), pathRegions.map { it.row })
        assertTrue(
            "every visual fragment should carry the complete attachment path: $pathRegions",
            pathRegions.all { it.match.value == attachment },
        )
        assertEquals(2, pathRegions[0].startCol)
        assertEquals(rows[0].text.length, pathRegions[0].endColExclusive)
        assertEquals(0, pathRegions[1].startCol)
        assertEquals(rows[1].text.length, pathRegions[1].endColExclusive)
        assertEquals(0, pathRegions[2].startCol)
        assertEquals(rows[2].text.length, pathRegions[2].endColExclusive)
    }

    @Test
    fun `unfinished generated image root does not join unrelated prose row`() {
        val unfinished = "/home/alexey/.codex/generated_images/"
        val rows = listOf(
            VisualRow(60, "image $unfinished", wrapsToNext = false),
            VisualRow(61, "done rendering", wrapsToNext = false),
        )

        val marked = markFilePathContinuationWraps(rows)
        val matchRegions = terminalMatchRegionsForRows(rows, columns = 120, matcher = DefaultTerminalMatcher())
        val pathRegions = matchRegions.filter { it.match is TerminalMatch.Path }

        assertEquals(rows, marked)
        assertTrue(
            "unrelated prose must not become part of a generated image path: $pathRegions",
            pathRegions.none { it.row == 61 || it.match.value.contains("done") },
        )
        assertTrue(filePathRegionsForRows(rows, columns = 120).isEmpty())
    }

    @Test
    fun `unfinished PocketShell attachment root does not join unrelated prose row`() {
        val unfinished = "~/.pocketshell/attachments/host-1-git-course-"
        val rows = listOf(
            VisualRow(70, "attached $unfinished", wrapsToNext = false),
            VisualRow(71, "this line is status text", wrapsToNext = false),
        )

        val marked = markFilePathContinuationWraps(rows)
        val matchRegions = terminalMatchRegionsForRows(rows, columns = 120, matcher = DefaultTerminalMatcher())
        val pathRegions = matchRegions.filter { it.match is TerminalMatch.Path }

        assertEquals(rows, marked)
        assertTrue(
            "unrelated prose must not become part of an attachment path: $pathRegions",
            pathRegions.none { it.row == 71 || it.match.value.contains("status") },
        )
        assertTrue(filePathRegionsForRows(rows, columns = 120).isEmpty())
    }
}
