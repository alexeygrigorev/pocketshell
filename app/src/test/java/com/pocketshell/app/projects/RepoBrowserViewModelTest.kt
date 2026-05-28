package com.pocketshell.app.projects

import com.pocketshell.app.repos.LocalRepoInfo
import com.pocketshell.app.repos.RemoteRepoInfo
import com.pocketshell.app.repos.RepoEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for the repos-browse merge + join — issue #230.
 *
 * The clone/open state machine opens a live SSH session inside the view
 * model, so its happy/failure paths are exercised by the connected E2E
 * suite. These tests pin the pure join logic that decides whether a repo
 * renders as a "Clone" or "Open" row, plus the cloned-first ordering.
 */
class RepoBrowserViewModelTest {

    private fun remoteEntry(
        owner: String?,
        name: String,
        fullName: String?,
        defaultBranch: String? = "main",
        updatedAt: String? = null,
    ) = RepoEntry(
        owner = owner,
        name = name,
        fullName = fullName,
        local = null,
        remote = RemoteRepoInfo(
            defaultBranch = defaultBranch,
            htmlUrl = null,
            sshUrl = null,
            updatedAt = updatedAt,
        ),
    )

    private fun localEntry(
        owner: String?,
        name: String,
        fullName: String?,
        path: String,
        head: String? = "abc123",
    ) = RepoEntry(
        owner = owner,
        name = name,
        fullName = fullName,
        local = LocalRepoInfo(path = path, head = head),
        remote = null,
    )

    @Test
    fun joinKey_prefersFullName_thenOwnerSlash_thenName() {
        assertEquals(
            "alexeygrigorev/pocketshell",
            RepoBrowserViewModel.joinKey(remoteEntry("alexeygrigorev", "pocketshell", "alexeygrigorev/pocketshell")),
        )
        assertEquals(
            "alexeygrigorev/pocketshell",
            RepoBrowserViewModel.joinKey(remoteEntry("alexeygrigorev", "pocketshell", fullName = null)),
        )
        assertEquals(
            "pocketshell",
            RepoBrowserViewModel.joinKey(remoteEntry(owner = null, name = "pocketshell", fullName = null)),
        )
    }

    @Test
    fun mergeRepos_marksRemoteRepoClonedWhenLocalCloneMatches() {
        val remote = listOf(
            remoteEntry("a", "cloned-repo", "a/cloned-repo"),
            remoteEntry("a", "github-only", "a/github-only"),
        )
        val local = listOf(
            localEntry("a", "cloned-repo", "a/cloned-repo", path = "/home/a/git/cloned-repo"),
        )

        val rows = RepoBrowserViewModel.mergeRepos(remote, local)

        val cloned = rows.single { it.fullName == "a/cloned-repo" }
        assertTrue(cloned.cloned)
        assertEquals("/home/a/git/cloned-repo", cloned.path)

        val githubOnly = rows.single { it.fullName == "a/github-only" }
        assertFalse(githubOnly.cloned)
        assertNull(githubOnly.path)
    }

    @Test
    fun mergeRepos_surfacesClonedOnlyRepoWithNoRemoteMatch() {
        val remote = listOf(remoteEntry("a", "public", "a/public"))
        val local = listOf(
            localEntry("a", "private-mirror", "a/private-mirror", path = "/home/a/git/private-mirror"),
        )

        val rows = RepoBrowserViewModel.mergeRepos(remote, local)

        assertEquals(2, rows.size)
        val mirror = rows.single { it.fullName == "a/private-mirror" }
        assertTrue(mirror.cloned)
        assertEquals("/home/a/git/private-mirror", mirror.path)
    }

    @Test
    fun mergeRepos_clonedRowsSortFirstThenByUpdatedAtDescending() {
        val remote = listOf(
            remoteEntry("a", "fresh-uncloned", "a/fresh-uncloned", updatedAt = "2026-05-28T00:00:00Z"),
            remoteEntry("a", "old-cloned", "a/old-cloned", updatedAt = "2020-01-01T00:00:00Z"),
            remoteEntry("a", "new-cloned", "a/new-cloned", updatedAt = "2026-05-27T00:00:00Z"),
        )
        val local = listOf(
            localEntry("a", "old-cloned", "a/old-cloned", path = "/git/old-cloned"),
            localEntry("a", "new-cloned", "a/new-cloned", path = "/git/new-cloned"),
        )

        val rows = RepoBrowserViewModel.mergeRepos(remote, local)

        // Cloned-first: the two cloned repos lead, sorted by updated_at desc.
        assertEquals(
            listOf("a/new-cloned", "a/old-cloned", "a/fresh-uncloned"),
            rows.map { it.fullName },
        )
        assertTrue(rows[0].cloned)
        assertTrue(rows[1].cloned)
        assertFalse(rows[2].cloned)
    }

    @Test
    fun mergeRepos_clonedLocalEntryWithoutLocalBlockIsSkipped() {
        // A defensive case: a local-scan row missing its `local` block
        // should not produce a cloned-only row (nothing to open).
        val local = listOf(
            RepoEntry(owner = "a", name = "broken", fullName = "a/broken", local = null, remote = null),
        )

        val rows = RepoBrowserViewModel.mergeRepos(remote = emptyList(), local = local)

        assertTrue(rows.isEmpty())
    }

    @Test
    fun mergeRepos_emptyInputsProduceEmptyList() {
        assertTrue(RepoBrowserViewModel.mergeRepos(emptyList(), emptyList()).isEmpty())
    }
}
