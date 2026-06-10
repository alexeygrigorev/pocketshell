package com.pocketshell.app.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure unit tests for [GitHubRemote.webUrl] — issue #648 (epic #644 slice 4).
 *
 * Pin the remote-URL → GitHub-web-page conversion across every transport shape
 * (scp-like SSH, ssh://, https), with/without `.git`, with trailing slashes, a
 * mixed-case host, and the non-GitHub remotes that must return null.
 */
class GitHubRemoteTest {

    private val canonical = "https://github.com/owner/repo"

    // ---- SSH (scp-like) -------------------------------------------------

    @Test
    fun `scp-like ssh with dot git`() {
        assertEquals(canonical, GitHubRemote.webUrl("git@github.com:owner/repo.git"))
    }

    @Test
    fun `scp-like ssh without dot git`() {
        assertEquals(canonical, GitHubRemote.webUrl("git@github.com:owner/repo"))
    }

    @Test
    fun `scp-like ssh without user prefix`() {
        assertEquals(canonical, GitHubRemote.webUrl("github.com:owner/repo.git"))
    }

    // ---- ssh:// ---------------------------------------------------------

    @Test
    fun `ssh scheme with dot git`() {
        assertEquals(canonical, GitHubRemote.webUrl("ssh://git@github.com/owner/repo.git"))
    }

    @Test
    fun `ssh scheme without dot git`() {
        assertEquals(canonical, GitHubRemote.webUrl("ssh://git@github.com/owner/repo"))
    }

    @Test
    fun `ssh scheme with explicit port`() {
        assertEquals(canonical, GitHubRemote.webUrl("ssh://git@github.com:22/owner/repo.git"))
    }

    // ---- https ----------------------------------------------------------

    @Test
    fun `https with dot git`() {
        assertEquals(canonical, GitHubRemote.webUrl("https://github.com/owner/repo.git"))
    }

    @Test
    fun `https without dot git`() {
        assertEquals(canonical, GitHubRemote.webUrl("https://github.com/owner/repo"))
    }

    @Test
    fun `https with embedded credentials`() {
        assertEquals(
            canonical,
            GitHubRemote.webUrl("https://user:token@github.com/owner/repo.git"),
        )
    }

    // ---- trailing slash / whitespace / case -----------------------------

    @Test
    fun `https with trailing slash`() {
        assertEquals(canonical, GitHubRemote.webUrl("https://github.com/owner/repo/"))
    }

    @Test
    fun `https dot git with trailing slash`() {
        assertEquals(canonical, GitHubRemote.webUrl("https://github.com/owner/repo.git/"))
    }

    @Test
    fun `surrounding whitespace and newline are trimmed`() {
        assertEquals(canonical, GitHubRemote.webUrl("  https://github.com/owner/repo.git\n"))
    }

    @Test
    fun `uppercase host is matched case-insensitively`() {
        assertEquals(canonical, GitHubRemote.webUrl("git@GitHub.com:owner/repo.git"))
        assertEquals(canonical, GitHubRemote.webUrl("https://GITHUB.COM/owner/repo.git"))
    }

    @Test
    fun `owner and repo casing is preserved`() {
        assertEquals(
            "https://github.com/Alexey/PocketShell",
            GitHubRemote.webUrl("git@github.com:Alexey/PocketShell.git"),
        )
    }

    @Test
    fun `repo name containing dot is kept (only trailing dot git stripped)`() {
        assertEquals(
            "https://github.com/owner/my.repo",
            GitHubRemote.webUrl("git@github.com:owner/my.repo.git"),
        )
    }

    // ---- non-GitHub / invalid → null ------------------------------------

    @Test
    fun `gitlab ssh returns null`() {
        assertNull(GitHubRemote.webUrl("git@gitlab.com:owner/repo.git"))
    }

    @Test
    fun `gitlab https returns null`() {
        assertNull(GitHubRemote.webUrl("https://gitlab.com/owner/repo.git"))
    }

    @Test
    fun `bitbucket returns null`() {
        assertNull(GitHubRemote.webUrl("git@bitbucket.org:owner/repo.git"))
    }

    @Test
    fun `plain ssh host returns null`() {
        assertNull(GitHubRemote.webUrl("git@example.com:owner/repo.git"))
        assertNull(GitHubRemote.webUrl("ssh://git@my-server.internal/owner/repo.git"))
    }

    @Test
    fun `github-lookalike host returns null`() {
        // A different host that merely contains the substring must NOT match.
        assertNull(GitHubRemote.webUrl("git@github.com.evil.example:owner/repo.git"))
        assertNull(GitHubRemote.webUrl("https://notgithub.com/owner/repo.git"))
    }

    @Test
    fun `null and blank return null`() {
        assertNull(GitHubRemote.webUrl(null))
        assertNull(GitHubRemote.webUrl(""))
        assertNull(GitHubRemote.webUrl("   "))
    }

    @Test
    fun `path without repo returns null`() {
        assertNull(GitHubRemote.webUrl("https://github.com/owner"))
        assertNull(GitHubRemote.webUrl("git@github.com:owner"))
    }

    @Test
    fun `path with extra segments returns null`() {
        // GitHub Enterprise-style or sub-paths aren't owner/repo; be strict.
        assertNull(GitHubRemote.webUrl("https://github.com/owner/repo/extra"))
    }

    @Test
    fun `garbage input returns null`() {
        assertNull(GitHubRemote.webUrl("not-a-url"))
        assertNull(GitHubRemote.webUrl("https://github.com/"))
        assertNull(GitHubRemote.webUrl("https://github.com"))
    }
}
