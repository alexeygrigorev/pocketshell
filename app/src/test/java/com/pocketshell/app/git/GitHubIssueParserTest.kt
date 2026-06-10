package com.pocketshell.app.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests over the `gh issue list --json …` parser — issue #649
 * (epic #644 slice 5).
 *
 * gh emits a top-level JSON array; these tests pin the parse over realistic
 * fixtures (open + closed, labels, empty list, malformed / empty output) without
 * a live SSH session.
 */
class GitHubIssueParserTest {

    @Test
    fun `parses open and closed issues with labels`() {
        val raw = """
            [
              {
                "number": 649,
                "title": "view GitHub issues in-app",
                "state": "OPEN",
                "labels": [
                  {"name": "enhancement", "color": "a2eeef"},
                  {"name": "phase-4", "color": "111111"}
                ],
                "updatedAt": "2026-06-09T10:11:12Z"
              },
              {
                "number": 648,
                "title": "Open on GitHub action",
                "state": "CLOSED",
                "labels": [],
                "updatedAt": "2026-06-08T08:00:00Z"
              }
            ]
        """.trimIndent()

        val issues = GitHubIssueParser.parse(raw)

        assertEquals(2, issues.size)
        assertEquals(
            GitHubIssue(
                number = 649,
                title = "view GitHub issues in-app",
                state = GitHubIssueState.Open,
                labels = listOf("enhancement", "phase-4"),
                updatedAt = "2026-06-09T10:11:12Z",
            ),
            issues[0],
        )
        assertEquals(
            GitHubIssue(
                number = 648,
                title = "Open on GitHub action",
                state = GitHubIssueState.Closed,
                labels = emptyList(),
                updatedAt = "2026-06-08T08:00:00Z",
            ),
            issues[1],
        )
    }

    @Test
    fun `empty array yields no issues`() {
        assertTrue(GitHubIssueParser.parse("[]").isEmpty())
        assertTrue(GitHubIssueParser.parse("  []  ").isEmpty())
    }

    @Test
    fun `blank output yields no issues`() {
        assertTrue(GitHubIssueParser.parse("").isEmpty())
        assertTrue(GitHubIssueParser.parse("   \n  ").isEmpty())
    }

    @Test
    fun `malformed json yields empty list rather than throwing`() {
        assertTrue(GitHubIssueParser.parse("not json at all").isEmpty())
        assertTrue(GitHubIssueParser.parse("{ broken").isEmpty())
        // A JSON object (not the expected array) is also handled gracefully.
        assertTrue(GitHubIssueParser.parse("""{"number":1}""").isEmpty())
    }

    @Test
    fun `entry missing a number is skipped`() {
        val raw = """
            [
              {"title": "no number", "state": "OPEN", "labels": []},
              {"number": 12, "title": "good", "state": "OPEN", "labels": []}
            ]
        """.trimIndent()

        val issues = GitHubIssueParser.parse(raw)

        assertEquals(1, issues.size)
        assertEquals(12, issues[0].number)
    }

    @Test
    fun `unknown state maps to Unknown rather than dropping the row`() {
        val raw = """[{"number": 5, "title": "weird", "state": "MERGED", "labels": []}]"""
        val issues = GitHubIssueParser.parse(raw)
        assertEquals(1, issues.size)
        assertEquals(GitHubIssueState.Unknown, issues[0].state)
    }

    @Test
    fun `missing optional fields default gracefully`() {
        // Only number present; title/state/labels/updatedAt absent.
        val raw = """[{"number": 7}]"""
        val issues = GitHubIssueParser.parse(raw)
        assertEquals(1, issues.size)
        assertEquals(7, issues[0].number)
        assertEquals("", issues[0].title)
        assertEquals(GitHubIssueState.Unknown, issues[0].state)
        assertTrue(issues[0].labels.isEmpty())
        assertEquals(null, issues[0].updatedAt)
    }

    @Test
    fun `blank label names are dropped`() {
        val raw = """
            [
              {
                "number": 9,
                "title": "labels",
                "state": "OPEN",
                "labels": [{"name": ""}, {"name": "bug"}, {"color": "fff"}]
              }
            ]
        """.trimIndent()

        val issues = GitHubIssueParser.parse(raw)

        assertEquals(listOf("bug"), issues[0].labels)
    }
}
