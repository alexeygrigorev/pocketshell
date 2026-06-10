package com.pocketshell.app.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests over the `git log` parser — issue #646.
 *
 * The gateway runs `git log --pretty=format:'%h%an%ar%s'`
 * (unit-separator between fields, record-separator between commits). These tests
 * pin that parse without a live SSH session.
 */
class GitHistoryGatewayParseTest {

    private val unit = ''
    private val record = ''

    private fun rec(hash: String, author: String, rel: String, subject: String): String =
        "$hash$unit$author$unit$rel$unit$subject$record"

    @Test
    fun `parses multiple commits newest first`() {
        val raw = rec("a1b2c3d", "Ada Lovelace", "2 hours ago", "Add timeline view") +
            rec("9f8e7d6", "Alan Turing", "3 days ago", "Fix parser edge case")

        val commits = GitHistoryGateway.parseLog(raw)

        assertEquals(2, commits.size)
        assertEquals(
            GitCommit("a1b2c3d", "Ada Lovelace", "2 hours ago", "Add timeline view"),
            commits[0],
        )
        assertEquals(
            GitCommit("9f8e7d6", "Alan Turing", "3 days ago", "Fix parser edge case"),
            commits[1],
        )
    }

    @Test
    fun `subject containing pipes and special chars survives intact`() {
        val subject = "feat: handle a|b|c and \"quotes\" — done"
        val raw = rec("deadbee", "Grace Hopper", "5 minutes ago", subject)

        val commits = GitHistoryGateway.parseLog(raw)

        assertEquals(1, commits.size)
        assertEquals(subject, commits[0].subject)
    }

    @Test
    fun `blank output yields no commits`() {
        assertTrue(GitHistoryGateway.parseLog("").isEmpty())
        assertTrue(GitHistoryGateway.parseLog("   \n ").isEmpty())
    }

    @Test
    fun `trailing record separator does not create an empty commit`() {
        val raw = rec("abc1234", "Author", "1 day ago", "only commit")
        val commits = GitHistoryGateway.parseLog(raw)
        assertEquals(1, commits.size)
    }

    @Test
    fun `malformed record with too few fields is skipped`() {
        val good = rec("abc1234", "Author", "1 day ago", "good")
        val bad = "justahash${record}"
        val commits = GitHistoryGateway.parseLog(bad + good)
        assertEquals(1, commits.size)
        assertEquals("abc1234", commits[0].shortHash)
    }

    @Test
    fun `single-quote in a path is escaped for the remote shell`() {
        assertEquals("'/tmp/it'\\''s here'", GitHistoryGateway.quoteSingle("/tmp/it's here"))
        assertEquals("'/home/u/git/proj'", GitHistoryGateway.quoteSingle("/home/u/git/proj"))
    }

    // --- gh status gate parse (issue #649) ---

    @Test
    fun `gh status installed and authenticated is configured`() {
        val raw = """{"account":"alexeygrigorev","authenticated":true,"hint":null,"installed":true}"""
        val status = GitHistoryGateway.parseGhStatus(raw)
        assertTrue(status is GhConfigStatus.Configured)
        assertEquals("alexeygrigorev", (status as GhConfigStatus.Configured).account)
    }

    @Test
    fun `gh status not installed surfaces install hint`() {
        val raw = """
            {"account":null,"authenticated":false,
             "hint":"install gh (https://cli.github.com) and run `gh auth login`",
             "installed":false}
        """.trimIndent()
        val status = GitHistoryGateway.parseGhStatus(raw)
        assertTrue(status is GhConfigStatus.NotConfigured)
        assertEquals(
            "install gh (https://cli.github.com) and run `gh auth login`",
            (status as GhConfigStatus.NotConfigured).hint,
        )
    }

    @Test
    fun `gh status installed but unauthenticated surfaces auth hint`() {
        val raw = """
            {"account":null,"authenticated":false,
             "hint":"run `gh auth login` to authenticate the GitHub CLI",
             "installed":true}
        """.trimIndent()
        val status = GitHistoryGateway.parseGhStatus(raw)
        assertTrue(status is GhConfigStatus.NotConfigured)
        assertEquals(
            "run `gh auth login` to authenticate the GitHub CLI",
            (status as GhConfigStatus.NotConfigured).hint,
        )
    }

    @Test
    fun `gh status with blank or malformed output degrades to not-configured`() {
        assertTrue(GitHistoryGateway.parseGhStatus("") is GhConfigStatus.NotConfigured)
        assertTrue(GitHistoryGateway.parseGhStatus("not json") is GhConfigStatus.NotConfigured)
        // authenticated true but a missing hint still falls back to the default.
        val noHint = GitHistoryGateway.parseGhStatus(
            """{"installed":true,"authenticated":false}""",
        )
        assertEquals(
            GitHistoryGateway.DEFAULT_GH_HINT,
            (noHint as GhConfigStatus.NotConfigured).hint,
        )
    }

    @Test
    fun `gh status configured with unknown account is still configured`() {
        val raw = """{"installed":true,"authenticated":true}"""
        val status = GitHistoryGateway.parseGhStatus(raw)
        assertTrue(status is GhConfigStatus.Configured)
        assertEquals(null, (status as GhConfigStatus.Configured).account)
    }
}
