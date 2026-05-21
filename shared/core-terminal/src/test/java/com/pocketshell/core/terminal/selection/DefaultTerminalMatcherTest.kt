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
}
