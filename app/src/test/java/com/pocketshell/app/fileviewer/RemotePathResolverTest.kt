package com.pocketshell.app.fileviewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `local file uri decodes to absolute path`() {
        assertEquals(
            "/home/alexey/.codex/generated_images/a b/out image.png",
            RemotePathResolver.resolve(
                "file:///home/alexey/.codex/generated_images/a%20b/out%20image.png",
                "/home/me/proj",
            ),
        )
    }

    @Test
    fun `tilde path passes through unchanged without remote home`() {
        assertEquals("~/notes.txt", RemotePathResolver.resolve("~/notes.txt", "/home/me/proj"))
        assertEquals("~", RemotePathResolver.resolve("~", "/home/me/proj"))
    }

    @Test
    fun `tilde path expands to normalized absolute remote home path`() {
        assertEquals(
            "/srv/users/me/notes.txt",
            RemotePathResolver.resolve("~/work/../notes.txt", "/home/me/proj", "/srv/users/me"),
        )
        assertEquals(
            "/srv/users/me",
            RemotePathResolver.resolve("~", "/home/me/proj", "/srv/users/me/"),
        )
    }

    @Test
    fun `pocketshell attachment path expands under home instead of cwd relative`() {
        val attachment =
            "~/.pocketshell/attachments/host-1-git-pocketshell-c/" +
                "20260606-153324-01-Screenshot_20260606-153310.png"

        assertEquals(
            "/home/alexey/.pocketshell/attachments/host-1-git-pocketshell-c/" +
                "20260606-153324-01-Screenshot_20260606-153310.png",
            RemotePathResolver.resolve(attachment, "/home/alexey/git/pocketshell", "/home/alexey"),
        )
    }

    @Test
    fun `reported attachment path is not rebased onto project cwd`() {
        val attachment =
            "~/.pocketshell/attachments/host-1-git-course-management-platform/" +
                "20260607-115723-01-Screenshot_20260607-115718.png"

        assertEquals(
            "/home/alexey/.pocketshell/attachments/host-1-git-course-management-platform/" +
                "20260607-115723-01-Screenshot_20260607-115718.png",
            RemotePathResolver.resolve(
                attachment,
                "/home/alexey/git/course-management-platform/platform",
                "/home/alexey",
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
    fun `relative path joins onto expanded tilde cwd when remote home is known`() {
        assertEquals(
            "/home/me/proj/a.txt",
            RemotePathResolver.resolve("a.txt", "~/proj", "/home/me"),
        )
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

    @Test
    fun `tilde path expands and collapses dotdot segments when remote home is known`() {
        assertEquals(
            "/home/me/b.txt",
            RemotePathResolver.resolve("~/a/../b.txt", "/home/me", "/home/me"),
        )
    }

    // --- Issue #748: basename-search fallback plan ---------------------------

    @Test
    fun `search plan targets the session cwd tree for a relative path`() {
        // The maintainer's exact case: agent referenced renders/foo.png relative
        // to a subdirectory it cd'd into; resolution against the project root
        // missed, so the fallback searches the project tree for the basename.
        val plan = RemotePathResolver.searchPlan(
            "renders/white_bathtub_3d_preview.png",
            "/home/alexey/git/3d-models",
        )
        assertEquals("/home/alexey/git/3d-models", plan?.searchRoot)
        assertEquals("white_bathtub_3d_preview.png", plan?.basename)
        assertEquals("renders/white_bathtub_3d_preview.png", plan?.relativeSuffix)
    }

    @Test
    fun `search plan resolves a bare basename`() {
        val plan = RemotePathResolver.searchPlan("notes.txt", "/home/me/proj")
        assertEquals("/home/me/proj", plan?.searchRoot)
        assertEquals("notes.txt", plan?.basename)
        assertEquals("notes.txt", plan?.relativeSuffix)
    }

    @Test
    fun `search plan expands a tilde cwd against the known remote home`() {
        val plan = RemotePathResolver.searchPlan("out/a.png", "~/proj", "/home/me")
        assertEquals("/home/me/proj", plan?.searchRoot)
        assertEquals("a.png", plan?.basename)
        assertEquals("out/a.png", plan?.relativeSuffix)
    }

    @Test
    fun `search plan collapses leading dot-slash and dotdot in the suffix`() {
        val plan = RemotePathResolver.searchPlan("./sub/../out/a.png", "/home/me/proj")
        assertEquals("/home/me/proj", plan?.searchRoot)
        assertEquals("a.png", plan?.basename)
        assertEquals("out/a.png", plan?.relativeSuffix)
    }

    @Test
    fun `no search plan for an absolute path`() {
        // A rooted path was resolved exactly; a miss is a real miss, not a
        // wrong-base subdirectory problem.
        assertNull(RemotePathResolver.searchPlan("/etc/hosts", "/home/me/proj"))
    }

    @Test
    fun `no search plan for a tilde path`() {
        assertNull(RemotePathResolver.searchPlan("~/notes.txt", "/home/me/proj"))
        assertNull(RemotePathResolver.searchPlan("~", "/home/me/proj"))
    }

    @Test
    fun `no search plan for a local file uri`() {
        assertNull(
            RemotePathResolver.searchPlan(
                "file:///home/me/out.png",
                "/home/me/proj",
            ),
        )
    }

    @Test
    fun `no search plan without a usable rooted cwd`() {
        assertNull(RemotePathResolver.searchPlan("a.png", null))
        assertNull(RemotePathResolver.searchPlan("a.png", "  "))
        assertNull(RemotePathResolver.searchPlan("a.png", "some/relative/dir"))
    }

    @Test
    fun `no search plan for blank input`() {
        assertNull(RemotePathResolver.searchPlan("   ", "/home/me/proj"))
    }

    // --- Issue #748: choosing the best basename-search match -----------------

    @Test
    fun `choose match prefers the candidate whose tail matches the relative path`() {
        val plan = RemotePathResolver.SearchPlan(
            searchRoot = "/home/alexey/git/3d-models",
            basename = "white_bathtub_3d_preview.png",
            relativeSuffix = "renders/white_bathtub_3d_preview.png",
        )
        val candidates = listOf(
            "/home/alexey/git/3d-models/archive/white_bathtub_3d_preview.png",
            "/home/alexey/git/3d-models/matchbox-mattel-atv-6x6/renders/white_bathtub_3d_preview.png",
        )
        assertEquals(
            "/home/alexey/git/3d-models/matchbox-mattel-atv-6x6/renders/white_bathtub_3d_preview.png",
            RemotePathResolver.chooseSearchMatch(plan, candidates),
        )
    }

    @Test
    fun `choose match returns the only candidate when there is exactly one`() {
        val plan = RemotePathResolver.SearchPlan(
            searchRoot = "/home/me/proj",
            basename = "a.png",
            relativeSuffix = "out/a.png",
        )
        assertEquals(
            "/home/me/proj/deep/nested/a.png",
            RemotePathResolver.chooseSearchMatch(
                plan,
                listOf("/home/me/proj/deep/nested/a.png"),
            ),
        )
    }

    @Test
    fun `choose match returns null for multiple non-suffix candidates`() {
        val plan = RemotePathResolver.SearchPlan(
            searchRoot = "/home/me/proj",
            basename = "a.png",
            relativeSuffix = "out/a.png",
        )
        // Same basename, but neither path ends with `out/a.png` — ambiguous, so
        // the UI should offer the list rather than guess.
        assertNull(
            RemotePathResolver.chooseSearchMatch(
                plan,
                listOf("/home/me/proj/x/a.png", "/home/me/proj/y/a.png"),
            ),
        )
    }

    @Test
    fun `choose match picks the shallowest among multiple suffix matches`() {
        val plan = RemotePathResolver.SearchPlan(
            searchRoot = "/home/me/proj",
            basename = "a.png",
            relativeSuffix = "out/a.png",
        )
        assertEquals(
            "/home/me/proj/out/a.png",
            RemotePathResolver.chooseSearchMatch(
                plan,
                listOf(
                    "/home/me/proj/deep/sub/out/a.png",
                    "/home/me/proj/out/a.png",
                ),
            ),
        )
    }

    @Test
    fun `choose match returns null for empty candidates`() {
        val plan = RemotePathResolver.SearchPlan(
            searchRoot = "/home/me/proj",
            basename = "a.png",
            relativeSuffix = "out/a.png",
        )
        assertNull(RemotePathResolver.chooseSearchMatch(plan, emptyList()))
    }
}
