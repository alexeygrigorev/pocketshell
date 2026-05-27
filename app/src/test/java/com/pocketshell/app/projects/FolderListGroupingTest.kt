package com.pocketshell.app.projects

import com.pocketshell.core.storage.entity.ProjectRootEntity
import com.pocketshell.uikit.model.SessionAgentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure folder-grouping logic exposed on
 * [FolderListViewModel] — issue #171.
 *
 * The grouping function is the load-bearing piece of the folder
 * list — it takes the gateway's per-session `pane_current_path`
 * mapping and the user's per-host [ProjectRootEntity] overlay and
 * emits the sorted folder list the screen renders. Driving it
 * directly (without spinning up SSH / DAO) keeps the tests fast and
 * deterministic.
 */
class FolderListGroupingTest {

    @Test
    fun canonicalisePathStripsTrailingSlash() {
        assertEquals("/home/alexey/git/pocketshell", FolderListViewModel.canonicalisePath("/home/alexey/git/pocketshell/"))
        assertEquals("/home/alexey/git/pocketshell", FolderListViewModel.canonicalisePath("/home/alexey/git/pocketshell"))
    }

    @Test
    fun canonicalisePathReturnsUntrackedForBlank() {
        assertEquals(FolderListViewModel.UNTRACKED_PATH, FolderListViewModel.canonicalisePath(""))
        assertEquals(FolderListViewModel.UNTRACKED_PATH, FolderListViewModel.canonicalisePath("   "))
    }

    @Test
    fun defaultLabelTakesTrailingPathSegment() {
        assertEquals("pocketshell", FolderListViewModel.defaultLabelForPath("/home/alexey/git/pocketshell"))
        assertEquals("Untracked", FolderListViewModel.defaultLabelForPath(FolderListViewModel.UNTRACKED_PATH))
        // Single-segment / root paths fall back to the full value.
        assertEquals("/", FolderListViewModel.defaultLabelForPath("/"))
    }

    @Test
    fun groupSessionsIntoFoldersGroupsByCanonicalisedPath() {
        val sessions = listOf(
            entry("claude-main", 1_000L),
            entry("build-shell", 800L),
            entry("training", 1_500L),
        )
        val cwds = mapOf(
            "claude-main" to "/home/alexey/git/pocketshell",
            "build-shell" to "/home/alexey/git/pocketshell/", // trailing slash → collapses
            "training" to "/home/alexey/git/llm-zoomcamp",
        )
        val rows = FolderListViewModel.groupSessionsIntoFolders(
            sessions = sessions,
            sessionFolderPaths = cwds.mapValues { (_, v) -> FolderListViewModel.canonicalisePath(v) },
            watchedFolders = emptyList(),
        )
        assertEquals(2, rows.size)
        // Sorted by most-recent activity desc: llm-zoomcamp (1_500) > pocketshell (1_000)
        assertEquals("llm-zoomcamp", rows[0].label)
        assertEquals(1, rows[0].sessions.size)
        assertEquals("pocketshell", rows[1].label)
        assertEquals(2, rows[1].sessions.size)
        // Sessions within a folder sort by activity desc as well.
        assertEquals("claude-main", rows[1].sessions[0].sessionName)
        assertEquals("build-shell", rows[1].sessions[1].sessionName)
    }

    @Test
    fun groupSessionsIntoFoldersRoutesMissingCwdToUntracked() {
        val sessions = listOf(
            entry("claude-main", 1_000L),
            entry("orphan", 500L),
        )
        val cwds = mapOf("claude-main" to FolderListViewModel.canonicalisePath("/home/alexey/git/pocketshell"))
        val rows = FolderListViewModel.groupSessionsIntoFolders(
            sessions = sessions,
            sessionFolderPaths = cwds,
            watchedFolders = emptyList(),
        )
        // Active group first, untracked last.
        assertEquals(2, rows.size)
        assertEquals("pocketshell", rows[0].label)
        assertEquals(FolderListViewModel.UNTRACKED_PATH, rows[1].path)
        assertEquals(FolderListViewModel.UNTRACKED_LABEL, rows[1].label)
        assertEquals(1, rows[1].sessions.size)
        assertEquals("orphan", rows[1].sessions[0].sessionName)
    }

    @Test
    fun watchedFolderWithoutMatchingSessionStillAppears() {
        val sessions = listOf(entry("claude-main", 1_000L))
        val cwds = mapOf("claude-main" to FolderListViewModel.canonicalisePath("/home/alexey/git/pocketshell"))
        val watched = listOf(
            ProjectRootEntity(id = 1L, hostId = 7L, label = "pocketshell", path = "/home/alexey/git/pocketshell"),
            ProjectRootEntity(id = 2L, hostId = 7L, label = "empty-pinned-folder", path = "/home/alexey/git/empty-pinned-folder"),
        )
        val rows = FolderListViewModel.groupSessionsIntoFolders(
            sessions = sessions,
            sessionFolderPaths = cwds,
            watchedFolders = watched,
        )
        assertEquals(2, rows.size)
        // Active group first: pocketshell with claude-main.
        assertEquals("pocketshell", rows[0].label)
        assertTrue(rows[0].isWatched)
        assertEquals(1, rows[0].sessions.size)
        // Watched-but-empty group last (before untracked).
        assertEquals("empty-pinned-folder", rows[1].label)
        assertTrue(rows[1].isEmpty)
        assertTrue(rows[1].isWatched)
    }

    @Test
    fun watchedFolderLabelStripOrderPrefix() {
        val sessions = listOf(entry("claude-main", 1_000L))
        val cwds = mapOf("claude-main" to FolderListViewModel.canonicalisePath("/home/alexey/git/pocketshell"))
        val watched = listOf(
            ProjectRootEntity(id = 1L, hostId = 7L, label = "[01] pocketshell", path = "/home/alexey/git/pocketshell"),
        )
        val rows = FolderListViewModel.groupSessionsIntoFolders(
            sessions = sessions,
            sessionFolderPaths = cwds,
            watchedFolders = watched,
        )
        assertEquals(1, rows.size)
        // The "[01] " order prefix is stripped per WatchedFoldersViewModel.stripOrderPrefix.
        assertEquals("pocketshell", rows[0].label)
        assertTrue(rows[0].isWatched)
    }

    @Test
    fun groupingPreservesSessionAgentKind() {
        val sessions = listOf(
            FolderSessionEntry("claude-main", 1_000L, attached = true, agentKind = SessionAgentKind.Claude),
            FolderSessionEntry("plain-shell", 800L, attached = false, agentKind = SessionAgentKind.Shell),
        )
        val cwds = mapOf(
            "claude-main" to FolderListViewModel.canonicalisePath("/home/alexey/git/pocketshell"),
            "plain-shell" to FolderListViewModel.canonicalisePath("/home/alexey/git/pocketshell"),
        )
        val rows = FolderListViewModel.groupSessionsIntoFolders(
            sessions = sessions,
            sessionFolderPaths = cwds,
            watchedFolders = emptyList(),
        )
        assertEquals(1, rows.size)
        assertEquals(SessionAgentKind.Claude, rows[0].sessions[0].agentKind)
        assertEquals(SessionAgentKind.Shell, rows[0].sessions[1].agentKind)
        assertTrue(rows[0].sessions[0].attached)
        assertFalse(rows[0].sessions[1].attached)
    }

    @Test
    fun threeSessionsAcrossTwoFoldersGroupAsTwoRows() {
        // Mirrors the connected E2E setup: three sessions, two folders.
        val sessions = listOf(
            entry("claude-pocketshell", 3_000L, kind = SessionAgentKind.Claude),
            entry("build-pocketshell", 2_500L, kind = SessionAgentKind.Shell),
            entry("codex-llm-zoomcamp", 1_000L, kind = SessionAgentKind.Codex),
        )
        val cwds = mapOf(
            "claude-pocketshell" to FolderListViewModel.canonicalisePath("/home/alexey/git/pocketshell"),
            "build-pocketshell" to FolderListViewModel.canonicalisePath("/home/alexey/git/pocketshell"),
            "codex-llm-zoomcamp" to FolderListViewModel.canonicalisePath("/home/alexey/git/llm-zoomcamp"),
        )
        val rows = FolderListViewModel.groupSessionsIntoFolders(
            sessions = sessions,
            sessionFolderPaths = cwds,
            watchedFolders = emptyList(),
        )
        assertEquals(2, rows.size)
        // pocketshell wins on max activity (3_000 > 1_000)
        assertEquals("pocketshell", rows[0].label)
        assertEquals(2, rows[0].sessions.size)
        assertEquals("llm-zoomcamp", rows[1].label)
        assertEquals(1, rows[1].sessions.size)
        // Agent-kind tints are routed all the way through.
        assertEquals(SessionAgentKind.Claude, rows[0].sessions[0].agentKind)
        assertEquals(SessionAgentKind.Shell, rows[0].sessions[1].agentKind)
        assertEquals(SessionAgentKind.Codex, rows[1].sessions[0].agentKind)
    }

    private fun entry(
        name: String,
        activity: Long,
        attached: Boolean = false,
        kind: SessionAgentKind = SessionAgentKind.Shell,
    ): FolderSessionEntry =
        FolderSessionEntry(
            sessionName = name,
            lastActivity = activity,
            attached = attached,
            agentKind = kind,
        )
}
