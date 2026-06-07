package com.pocketshell.app.fileviewer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Relative-vs-absolute path resolution for the file viewer (issue #497).
 */
class RemotePathResolverTest {

    @Test
    fun `absolute path passes through unchanged`() {
        assertEquals("/etc/hosts", RemotePathResolver.resolve("/etc/hosts", "/home/me/proj"))
    }

    @Test
    fun `tilde path passes through unchanged`() {
        assertEquals("~/notes.txt", RemotePathResolver.resolve("~/notes.txt", "/home/me/proj"))
        assertEquals("~", RemotePathResolver.resolve("~", "/home/me/proj"))
    }

    @Test
    fun `pocketshell attachment path stays home rooted instead of cwd relative`() {
        val attachment =
            "~/.pocketshell/attachments/host-1-git-pocketshell-c/" +
                "20260606-153324-01-Screenshot_20260606-153310.png"

        assertEquals(
            attachment,
            RemotePathResolver.resolve(attachment, "/home/alexey/git/pocketshell"),
        )
    }

    @Test
    fun `reported attachment path is not rebased onto project cwd`() {
        val attachment =
            "~/.pocketshell/attachments/host-1-git-course-management-platform/" +
                "20260607-115723-01-Screenshot_20260607-115718.png"

        assertEquals(
            attachment,
            RemotePathResolver.resolve(
                attachment,
                "/home/alexey/git/course-management-platform/platform",
            ),
        )
    }

    @Test
    fun `relative path joins onto cwd`() {
        assertEquals(
            "/home/me/proj/out/report.png",
            RemotePathResolver.resolve("out/report.png", "/home/me/proj"),
        )
    }

    @Test
    fun `relative path collapses leading dot-slash`() {
        assertEquals(
            "/home/me/proj/a.txt",
            RemotePathResolver.resolve("./a.txt", "/home/me/proj"),
        )
    }

    @Test
    fun `cwd trailing slash is collapsed`() {
        assertEquals(
            "/home/me/proj/a.txt",
            RemotePathResolver.resolve("a.txt", "/home/me/proj/"),
        )
    }

    @Test
    fun `relative path joins onto a tilde cwd`() {
        assertEquals("~/proj/a.txt", RemotePathResolver.resolve("a.txt", "~/proj"))
    }

    @Test
    fun `blank cwd leaves relative path for remote shell to resolve`() {
        assertEquals("a.txt", RemotePathResolver.resolve("a.txt", null))
        assertEquals("a.txt", RemotePathResolver.resolve("a.txt", "  "))
    }

    @Test
    fun `unusable relative cwd is not used as a base`() {
        // A non-rooted cwd would produce a still-relative path; pass through
        // so the remote shell resolves against $HOME instead of fabricating
        // a wrong nested path.
        assertEquals("a.txt", RemotePathResolver.resolve("a.txt", "some/relative/dir"))
    }

    @Test
    fun `input is trimmed`() {
        assertEquals("/etc/hosts", RemotePathResolver.resolve("  /etc/hosts  ", null))
    }

    @Test
    fun `relative dotdot path collapses against cwd for resolution and display`() {
        // Issue #558 bug 1: a `../`-relative tap must resolve AND display as the
        // canonical absolute path with no literal `..` segments. Four `..` from
        // /home/alexey/git/pocketshell climb to the root, leaving /tmp/x.md.
        assertEquals(
            "/tmp/x.md",
            RemotePathResolver.resolve(
                "../../../../tmp/x.md",
                "/home/alexey/git/pocketshell",
            ),
        )
    }

    @Test
    fun `absolute path with embedded dotdot is normalized for display`() {
        // No literal `..` segments survive in the resolved breadcrumb.
        assertEquals(
            "/tmp/x.md",
            RemotePathResolver.resolve(
                "/home/alexey/git/pocketshell/../../../../tmp/x.md",
                "/home/alexey/git/pocketshell",
            ),
        )
    }

    @Test
    fun `tilde path keeps tilde but collapses dotdot segments`() {
        assertEquals("~/b.txt", RemotePathResolver.resolve("~/a/../b.txt", "/home/me"))
    }
}
