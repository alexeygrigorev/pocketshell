package com.pocketshell.core.terminal.selection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage of [DefaultTerminalMatcher]. No Android, no Robolectric —
 * regexes only.
 *
 * The fixtures are grouped by match kind. Each group has at least one
 * positive case (the match is found with the expected value) and at least
 * one negative case (the matcher does NOT manufacture a false match for
 * superficially similar input).
 *
 * Precedence between match kinds (URLs swallowing path-like tails, etc.) has
 * its own block at the bottom.
 */
class DefaultTerminalMatcherTest {

    private val matcher = DefaultTerminalMatcher()

    // -------------------------------------------------------------------
    // URLs
    // -------------------------------------------------------------------

    @Test
    fun `matches simple https url`() {
        val text = "see https://example.com/docs for details"
        val urls = matcher.matches(text).filterIsInstance<TerminalMatch.Url>()
        assertEquals(1, urls.size)
        assertEquals("https://example.com/docs", urls.first().value)
    }

    @Test
    fun `strips trailing sentence punctuation from urls`() {
        val text = "open https://example.com."
        val urls = matcher.matches(text).filterIsInstance<TerminalMatch.Url>()
        assertEquals(1, urls.size)
        assertEquals("https://example.com", urls.first().value)
    }

    @Test
    fun `url span excludes trailing sentence punctuation`() {
        val text = "open https://example.com."
        val span = matcher.matchSpans(text).single { it.match is TerminalMatch.Url }
        assertEquals("https://example.com", text.substring(span.start, span.endExclusive))
        assertEquals(text.length - 1, span.endExclusive)
    }

    @Test
    fun `does not match scheme-less hostnames as urls`() {
        // No `http://` prefix — this must not be reported as a URL even
        // though it looks like a domain.
        val text = "example.com/path"
        val urls = matcher.matches(text).filterIsInstance<TerminalMatch.Url>()
        assertTrue("expected no urls, got $urls", urls.isEmpty())
    }

    // -------------------------------------------------------------------
    // Absolute paths
    // -------------------------------------------------------------------

    @Test
    fun `matches absolute unix path`() {
        val text = "ls -la /usr/local/bin"
        val paths = matcher.matches(text).filterIsInstance<TerminalMatch.Path>()
        assertNotNull(paths.find { it.value == "/usr/local/bin" })
    }

    @Test
    fun `matches multiple absolute paths on one line`() {
        val text = "cp /etc/hosts /tmp/backup-hosts"
        val paths = matcher.matches(text).filterIsInstance<TerminalMatch.Path>()
        assertEquals(2, paths.size)
        assertEquals(setOf("/etc/hosts", "/tmp/backup-hosts"), paths.map { it.value }.toSet())
    }

    @Test
    fun `path spans preserve multiple matches on one line`() {
        val text = "cp /etc/hosts /tmp/backup-hosts"
        val spans = matcher.matchSpans(text).filter { it.match is TerminalMatch.Path }
        assertEquals(2, spans.size)
        assertEquals("/etc/hosts", text.substring(spans[0].start, spans[0].endExclusive))
        assertEquals("/tmp/backup-hosts", text.substring(spans[1].start, spans[1].endExclusive))
    }

    @Test
    fun `duplicate path values keep distinct spans`() {
        val text = "diff /etc/hosts /etc/hosts"
        val spans = matcher.matchSpans(text).filter { it.match is TerminalMatch.Path }
        assertEquals(2, spans.size)
        assertEquals(5, spans[0].start)
        assertEquals(16, spans[1].start)
        assertEquals(listOf("/etc/hosts", "/etc/hosts"), spans.map { it.match.value })
    }

    @Test
    fun `rejects bare root slash`() {
        // A single `/` token is never a useful tap target — it would copy
        // the root directory and the regex must NOT include it as a Path.
        val text = "the / is busy"
        val paths = matcher.matches(text).filterIsInstance<TerminalMatch.Path>()
        assertTrue(
            "bare slash should not be classified as a path, got $paths",
            paths.none { it.value == "/" },
        )
    }

    @Test
    fun `matches local file uri as decoded path with raw uri span`() {
        val decoded =
            "/home/alexey/.codex/generated_images/" +
                "019e9d03-13bc-7280-8d97-40a592fbfcb0/" +
                "ig_04202f5df68d850a016a255d81c5d48191ad5bc191b780d5c1.png"
        val uri = "file://$decoded"
        val text = "generated image: $uri."

        val spans = matcher.matchSpans(text).filter { it.match is TerminalMatch.Path }

        assertEquals(1, spans.size)
        assertEquals(TerminalMatch.Path(decoded), spans.single().match)
        assertEquals(uri, text.substring(spans.single().start, spans.single().endExclusive))
    }

    @Test
    fun `rejects non local file uri without surfacing its path tail`() {
        val text = "remote uri file://example.com/home/alexey/out.png"

        val paths = matcher.matches(text).filterIsInstance<TerminalMatch.Path>()

        assertTrue("remote file URI must not create path matches, got $paths", paths.isEmpty())
    }

    // -------------------------------------------------------------------
    // Relative paths
    // -------------------------------------------------------------------

    @Test
    fun `matches relative path with subdirectory`() {
        val text = "vim src/main/kotlin/Foo.kt"
        val paths = matcher.matches(text).filterIsInstance<TerminalMatch.Path>()
        assertNotNull(paths.find { it.value == "src/main/kotlin/Foo.kt" })
    }

    @Test
    fun `does not classify plain words with no slash as paths`() {
        val text = "running tests for module foo"
        val paths = matcher.matches(text).filterIsInstance<TerminalMatch.Path>()
        assertTrue("expected no paths, got $paths", paths.isEmpty())
    }

    // -------------------------------------------------------------------
    // Stack-trace frames (java + python)
    // -------------------------------------------------------------------

    @Test
    fun `matches java-style stack frame`() {
        val text = "\tat com.foo.bar.Baz.doIt(Baz.java:42)"
        val errors = matcher.matches(text).filterIsInstance<TerminalMatch.Error>()
        val frames = errors.map { it.value }
        assertTrue(
            "no java-style frame matched in $frames",
            frames.any { it.startsWith("com.foo.bar.Baz.doIt(") },
        )
    }

    @Test
    fun `matches python traceback line`() {
        val text = "  File \"main.py\", line 7, in handler"
        val errors = matcher.matches(text).filterIsInstance<TerminalMatch.Error>()
        val frames = errors.map { it.value }
        assertTrue(
            "no python frame matched in $frames",
            frames.any { it.startsWith("File \"main.py\"") },
        )
    }

    // -------------------------------------------------------------------
    // Error keyword lines
    // -------------------------------------------------------------------

    @Test
    fun `matches whole line containing Error keyword`() {
        val text = "Building...\nError: cannot find symbol 'foo'\nDone."
        val errors = matcher.matches(text).filterIsInstance<TerminalMatch.Error>()
        val lines = errors.map { it.value }
        assertTrue(
            "expected the Error line to be captured, got $lines",
            lines.contains("Error: cannot find symbol 'foo'"),
        )
    }

    @Test
    fun `generic error span excludes surrounding row whitespace`() {
        val text = "   Error: cannot find symbol   "
        val span = matcher.matchSpans(text).single { it.match is TerminalMatch.Error }
        assertEquals("Error: cannot find symbol", span.match.value)
        assertEquals("Error: cannot find symbol", text.substring(span.start, span.endExclusive))
        assertEquals(3, span.start)
        assertEquals(text.length - 3, span.endExclusive)
    }

    @Test
    fun `matches Exception keyword line`() {
        val text = "NullPointerException at Foo.kt:12"
        val errors = matcher.matches(text).filterIsInstance<TerminalMatch.Error>()
        assertTrue(
            "expected an Exception-tagged error, got $errors",
            errors.any { it.value.contains("Exception") },
        )
    }

    @Test
    fun `does not surface lines that merely contain the substring error`() {
        // 'terrorism' contains 'error' but the regex is word-bounded.
        val text = "scanning for terrorism keywords completed"
        val errors = matcher.matches(text).filterIsInstance<TerminalMatch.Error>()
        assertTrue(
            "false positive on 'terrorism' substring: $errors",
            errors.none { it.value == text },
        )
    }

    // -------------------------------------------------------------------
    // Precedence: URLs swallow their path-like tail, paths don't double up
    // -------------------------------------------------------------------

    @Test
    fun `url consumes its path tail so no duplicate path is emitted`() {
        val text = "visit https://example.com/docs/api"
        val matches = matcher.matches(text)
        val urls = matches.filterIsInstance<TerminalMatch.Url>()
        val paths = matches.filterIsInstance<TerminalMatch.Path>()
        assertEquals(1, urls.size)
        assertEquals("https://example.com/docs/api", urls.first().value)
        assertTrue(
            "URL tail should not also surface as a Path, got $paths",
            paths.isEmpty(),
        )
    }

    @Test
    fun `span matching keeps url path tail claimed`() {
        val text = "visit https://example.com/docs/api"
        val spans = matcher.matchSpans(text)
        assertEquals(1, spans.count { it.match is TerminalMatch.Url })
        assertTrue(
            "URL tail should not also surface as a Path span, got $spans",
            spans.none { it.match is TerminalMatch.Path },
        )
    }

    @Test
    fun `empty input returns empty list`() {
        assertTrue(matcher.matches("").isEmpty())
    }

    @Test
    fun `multiple match kinds coexist in one snapshot`() {
        val text = """
            $ ls /var/log
            $ curl https://api.example.com/v1/health
            Error: timeout after 30s
            $ python main.py
              File "main.py", line 11, in main
        """.trimIndent()
        val matches = matcher.matches(text)
        val kinds = matches.map { it::class.simpleName }.toSet()
        // All four classes should appear at least once.
        assertTrue("expected Path matches, got $matches", kinds.contains("Path"))
        assertTrue("expected Url matches, got $matches", kinds.contains("Url"))
        assertTrue("expected Error matches, got $matches", kinds.contains("Error"))
    }

    @Test
    fun `panic keyword surfaces an error line`() {
        val text = "goroutine 1 panic: runtime error: index out of range"
        val errors = matcher.matches(text).filterIsInstance<TerminalMatch.Error>()
        assertFalse(
            "expected at least one error match for panic line, got $errors",
            errors.isEmpty(),
        )
    }

    // -------------------------------------------------------------------
    // Relative-path false positives — these MUST NOT be classified as Path.
    //
    // See `Detector.kt`'s file-level KDoc, "Known limitations" section.
    // The relative-path regex requires either a directory-like prefix
    // (`./`, `../`, `~/`) or a known file extension. Fractions, common
    // shorthand, and unit ratios are not paths.
    // -------------------------------------------------------------------

    @Test
    fun `rejects fraction like 5 over 2 as a relative path`() {
        val text = "ratio is 5/2 for the test"
        val paths = matcher.matches(text).filterIsInstance<TerminalMatch.Path>()
        assertTrue("'5/2' should not match as a Path, got $paths", paths.isEmpty())
    }

    @Test
    fun `rejects fraction 22 over 7 as a relative path`() {
        val text = "pi is roughly 22/7"
        val paths = matcher.matches(text).filterIsInstance<TerminalMatch.Path>()
        assertTrue("'22/7' should not match as a Path, got $paths", paths.isEmpty())
    }

    @Test
    fun `rejects shorthand n over a as a relative path`() {
        val text = "status: n/a"
        val paths = matcher.matches(text).filterIsInstance<TerminalMatch.Path>()
        assertTrue("'n/a' should not match as a Path, got $paths", paths.isEmpty())
    }

    @Test
    fun `rejects yes or no shorthand y over n as a relative path`() {
        val text = "continue? y/n"
        val paths = matcher.matches(text).filterIsInstance<TerminalMatch.Path>()
        assertTrue("'y/n' should not match as a Path, got $paths", paths.isEmpty())
    }

    @Test
    fun `rejects protocol shorthand TCP over IP as a relative path`() {
        val text = "running over TCP/IP transport"
        val paths = matcher.matches(text).filterIsInstance<TerminalMatch.Path>()
        assertTrue("'TCP/IP' should not match as a Path, got $paths", paths.isEmpty())
    }

    @Test
    fun `rejects unit ratio Bytes over sec as a relative path`() {
        val text = "throughput 1234 Bytes/sec sustained"
        val paths = matcher.matches(text).filterIsInstance<TerminalMatch.Path>()
        assertTrue("'Bytes/sec' should not match as a Path, got $paths", paths.isEmpty())
    }

    @Test
    fun `rejects recipe-style 1 over 2 cup as a relative path`() {
        val text = "add 1/2 cup of flour"
        val paths = matcher.matches(text).filterIsInstance<TerminalMatch.Path>()
        assertTrue("'1/2' should not match as a Path, got $paths", paths.isEmpty())
    }

    // -------------------------------------------------------------------
    // Relative-path positive shapes — directory-prefixed and
    // extension-based forms both work.
    // -------------------------------------------------------------------

    @Test
    fun `matches dot-slash relative path`() {
        val text = "edit ./build.gradle for changes"
        val paths = matcher.matches(text).filterIsInstance<TerminalMatch.Path>()
        assertNotNull(
            "expected ./build.gradle to match, got $paths",
            paths.find { it.value == "./build.gradle" },
        )
    }

    @Test
    fun `matches dot-dot-slash relative path`() {
        val text = "see ../README.md for context"
        val paths = matcher.matches(text).filterIsInstance<TerminalMatch.Path>()
        assertNotNull(
            "expected ../README.md to match, got $paths",
            paths.find { it.value == "../README.md" },
        )
    }

    @Test
    fun `matches tilde-prefixed home relative path`() {
        val text = "look in ~/projects/foo today"
        val paths = matcher.matches(text).filterIsInstance<TerminalMatch.Path>()
        assertNotNull(
            "expected ~/projects/foo to match, got $paths",
            paths.find { it.value == "~/projects/foo" },
        )
    }

    @Test
    fun `matches dotted filename like build dot gradle dot kts`() {
        val text = "open app/build.gradle.kts"
        val paths = matcher.matches(text).filterIsInstance<TerminalMatch.Path>()
        assertNotNull(
            "expected app/build.gradle.kts to match, got $paths",
            paths.find { it.value == "app/build.gradle.kts" },
        )
    }

    // -------------------------------------------------------------------
    // Snapshot windowing — see `Detector.kt`, MAX_SCAN_CHARS.
    //
    // When the input exceeds the bound, matches that fall in the leading
    // (older) part of the text are dropped. The matcher MUST still find
    // matches that fall in the trailing window.
    // -------------------------------------------------------------------

    @Test
    fun `windows the input to MAX_SCAN_CHARS and drops older matches`() {
        // Stuff a URL at the very beginning of the input. With a leading
        // padding strictly larger than MAX_SCAN_CHARS, the URL falls
        // outside the scan window and must NOT be reported. A second URL
        // appended at the very end MUST still match.
        val urlOutsideWindow = "https://outside-window.example.com/old"
        val urlInsideWindow = "https://inside-window.example.com/new"
        val padding = " ".repeat(DefaultTerminalMatcher.MAX_SCAN_CHARS + 100)
        val text = "$urlOutsideWindow$padding$urlInsideWindow"

        val urls = matcher.matches(text).filterIsInstance<TerminalMatch.Url>().map { it.value }
        assertTrue(
            "URL beyond MAX_SCAN_CHARS should be dropped, got $urls",
            urls.none { it == urlOutsideWindow },
        )
        assertTrue(
            "URL inside MAX_SCAN_CHARS should still match, got $urls",
            urls.any { it == urlInsideWindow },
        )
    }

    @Test
    fun `MAX_SCAN_CHARS is a documented public constant`() {
        // The bound MUST be exposed so external callers and the file's KDoc
        // can reference it. Sanity-check it is in a sensible range — too
        // small loses the visible screen; too large defeats the windowing.
        val bound = DefaultTerminalMatcher.MAX_SCAN_CHARS
        assertTrue("MAX_SCAN_CHARS too small ($bound)", bound >= 2_000)
        assertTrue("MAX_SCAN_CHARS too large ($bound)", bound <= 64_000)
    }

    @Test
    fun `visible-row fallback maps legacy value-only matcher results to spans`() {
        val line = "copy ticket PS-354 now"
        val spans = matchSpansForLine(line, LegacyTicketMatcher)

        assertEquals(1, spans.size)
        assertEquals(TerminalMatch.Error("PS-354"), spans.single().match)
        assertEquals(12, spans.single().start)
        assertEquals(18, spans.single().endExclusive)
    }

    @Test
    fun `visible-row fallback keeps duplicate legacy matcher results distinct`() {
        val line = "PS-354 then PS-354"
        val spans = matchSpansForLine(line, LegacyDuplicateTicketMatcher)

        assertEquals(listOf(0, 12), spans.map { it.start })
        assertEquals(listOf(6, 18), spans.map { it.endExclusive })
    }

    private object LegacyTicketMatcher : TerminalMatcher {
        override fun matches(text: String): List<TerminalMatch> =
            if ("PS-354" in text) listOf(TerminalMatch.Error("PS-354")) else emptyList()
    }

    private object LegacyDuplicateTicketMatcher : TerminalMatcher {
        override fun matches(text: String): List<TerminalMatch> =
            List(2) { TerminalMatch.Error("PS-354") }.takeIf { "PS-354" in text }.orEmpty()
    }
}
