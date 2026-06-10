package com.pocketshell.app.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests for the repo-overview parsers — issue #647 (epic #644 slice 3).
 *
 * Pin the parse of `git branch --format`, `git worktree list --porcelain`, and
 * `git status --porcelain=v2 --branch` without a live SSH session.
 */
class GitRepoOverviewParseTest {

    private val unit = ''
    private val record = ''

    private fun branchRec(head: String, name: String, upstream: String, subject: String): String =
        "$head$unit$name$unit$upstream$unit$subject$record"

    // ---- branches -------------------------------------------------------

    @Test
    fun `parses branches and moves current to front`() {
        val raw =
            branchRec(" ", "feature/x", "origin/feature/x", "WIP feature") +
                branchRec("*", "main", "origin/main", "Release v1") +
                branchRec(" ", "dev", "", "Local only")

        val branches = GitHistoryGateway.parseBranches(raw)

        assertEquals(3, branches.size)
        assertEquals("main", branches[0].name)
        assertTrue(branches[0].current)
        assertEquals("origin/main", branches[0].upstream)
        assertEquals("Release v1", branches[0].subject)
        // remaining branches keep git's order
        assertEquals(listOf("feature/x", "dev"), branches.drop(1).map { it.name })
        assertFalse(branches[1].current)
        assertNull(branches[2].upstream)
    }

    @Test
    fun `branch subject with pipes and spaces survives`() {
        val subject = "fix: a|b|c and spaces here"
        val raw = branchRec("*", "main", "origin/main", subject)
        val branches = GitHistoryGateway.parseBranches(raw)
        assertEquals(1, branches.size)
        assertEquals(subject, branches[0].subject)
    }

    @Test
    fun `blank branch output yields empty`() {
        assertTrue(GitHistoryGateway.parseBranches("").isEmpty())
        assertTrue(GitHistoryGateway.parseBranches("  \n ").isEmpty())
    }

    // ---- worktrees ------------------------------------------------------

    @Test
    fun `parses multiple worktrees with branch and detached`() {
        val raw = """
            worktree /home/u/git/proj
            HEAD a1b2c3d4e5f6
            branch refs/heads/main

            worktree /home/u/git/proj-feature
            HEAD 9f8e7d6c5b4a
            branch refs/heads/feature/x

            worktree /home/u/git/proj-detached
            HEAD 1122334455667
            detached

        """.trimIndent()

        val worktrees = GitHistoryGateway.parseWorktrees(raw)

        assertEquals(3, worktrees.size)
        assertEquals("/home/u/git/proj", worktrees[0].path)
        assertEquals("main", worktrees[0].branch)
        assertEquals("a1b2c3d", worktrees[0].head)
        assertFalse(worktrees[0].detached)

        assertEquals("feature/x", worktrees[1].branch)

        assertEquals("/home/u/git/proj-detached", worktrees[2].path)
        assertNull(worktrees[2].branch)
        assertTrue(worktrees[2].detached)
    }

    @Test
    fun `parses bare worktree`() {
        val raw = """
            worktree /home/u/git/proj.git
            bare
        """.trimIndent()
        val worktrees = GitHistoryGateway.parseWorktrees(raw)
        assertEquals(1, worktrees.size)
        assertTrue(worktrees[0].bare)
        assertNull(worktrees[0].branch)
    }

    @Test
    fun `single worktree without trailing blank line`() {
        val raw = "worktree /home/u/git/proj\nHEAD abcdef1234567\nbranch refs/heads/main"
        val worktrees = GitHistoryGateway.parseWorktrees(raw)
        assertEquals(1, worktrees.size)
        assertEquals("main", worktrees[0].branch)
    }

    // ---- status ---------------------------------------------------------

    @Test
    fun `parses clean status with upstream up to date`() {
        val raw = """
            # branch.oid 0123456789abcdef0123456789abcdef01234567
            # branch.head main
            # branch.upstream origin/main
            # branch.ab +0 -0
        """.trimIndent()

        val status = GitHistoryGateway.parseStatus(raw, "abc1234 Latest commit")

        assertEquals("main", status.currentBranch)
        assertEquals("origin/main", status.upstream)
        assertEquals(0, status.ahead)
        assertEquals(0, status.behind)
        assertFalse(status.dirty)
        assertEquals(0, status.changedFiles)
        assertEquals("abc1234 Latest commit", status.lastCommit)
        assertFalse(status.hasNoCommits)
        assertTrue(status.hasUpstream)
    }

    @Test
    fun `parses dirty status with ahead behind and counts changed paths`() {
        val raw = """
            # branch.oid 0123456789abcdef0123456789abcdef01234567
            # branch.head feature/x
            # branch.upstream origin/feature/x
            # branch.ab +2 -3
            1 .M N... 100644 100644 100644 aaa bbb modified.kt
            ? untracked.txt
            ! ignored.log
        """.trimIndent()

        val status = GitHistoryGateway.parseStatus(raw, "def5678 WIP")

        assertEquals("feature/x", status.currentBranch)
        assertEquals(2, status.ahead)
        assertEquals(3, status.behind)
        assertTrue(status.dirty)
        // modified + untracked counted; ignored excluded.
        assertEquals(2, status.changedFiles)
    }

    @Test
    fun `parses detached head and no upstream`() {
        val raw = """
            # branch.oid 0123456789abcdef0123456789abcdef01234567
            # branch.head (detached)
        """.trimIndent()

        val status = GitHistoryGateway.parseStatus(raw, "abc1234 c")

        assertNull(status.currentBranch)
        assertNull(status.upstream)
        assertFalse(status.hasUpstream)
        assertFalse(status.dirty)
    }

    @Test
    fun `parses repo with no commits yet`() {
        val raw = """
            # branch.oid (initial)
            # branch.head main
        """.trimIndent()

        val status = GitHistoryGateway.parseStatus(raw, null)

        assertTrue(status.hasNoCommits)
        assertEquals("main", status.currentBranch)
        assertNull(status.lastCommit)
    }
}
