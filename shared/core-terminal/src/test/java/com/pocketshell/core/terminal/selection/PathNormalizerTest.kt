package com.pocketshell.core.terminal.selection

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for `.`/`..` collapse used by the file viewer's resolution +
 * breadcrumb display (issue #558 bug 1).
 */
class PathNormalizerTest {

    private fun norm(p: String) = PathNormalizer.normalize(p)

    @Test
    fun `collapses dotdot against an absolute base`() {
        // The maintainer's motivating case: a `../`-relative path resolved into
        // an absolute one with literal `..` segments must canonicalise. Four
        // `..` from /home/alexey/git/pocketshell climb past the root remainder
        // to leave /tmp/x.md.
        assertEquals(
            "/tmp/x.md",
            norm("/home/alexey/git/pocketshell/../../../../tmp/x.md"),
        )
        // Three `..` from the same base leaves /home, so /home/tmp/x.md.
        assertEquals(
            "/home/tmp/x.md",
            norm("/home/alexey/git/pocketshell/../../../tmp/x.md"),
        )
    }

    @Test
    fun `collapses single dot segments`() {
        assertEquals("/a/b/c.txt", norm("/a/./b/./c.txt"))
    }

    @Test
    fun `dotdot past the root clamps at root`() {
        assertEquals("/x", norm("/../../x"))
        assertEquals("/", norm("/.."))
        assertEquals("/", norm("/a/../.."))
    }

    @Test
    fun `already canonical absolute path is unchanged`() {
        assertEquals("/var/log/app.log", norm("/var/log/app.log"))
    }

    @Test
    fun `collapses duplicate slashes`() {
        assertEquals("/a/b.txt", norm("/a//b.txt"))
    }

    @Test
    fun `tilde prefix is preserved while the body collapses`() {
        assertEquals("~/b.txt", norm("~/a/../b.txt"))
        assertEquals("~/git/repo", norm("~/git/foo/../repo"))
    }

    @Test
    fun `bare tilde is unchanged`() {
        assertEquals("~", norm("~"))
        assertEquals("~", norm("~/a/.."))
    }

    @Test
    fun `tilde dotdot past home clamps at home`() {
        assertEquals("~", norm("~/.."))
        assertEquals("~/x", norm("~/../x"))
    }

    @Test
    fun `relative path with no root passes through unchanged`() {
        // No base to collapse against here — the resolver joins it onto cwd
        // first, then normalises the absolute result.
        assertEquals("out/report.png", norm("out/report.png"))
        assertEquals("../docs/readme.md", norm("../docs/readme.md"))
    }

    @Test
    fun `directory trailing slash is preserved`() {
        assertEquals("/var/log/", norm("/var/log/"))
        assertEquals("~/git/repo/", norm("~/git/foo/../repo/"))
    }

    @Test
    fun `input is trimmed`() {
        assertEquals("/etc/hosts", norm("  /etc/hosts  "))
    }
}
