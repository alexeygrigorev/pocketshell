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

    @Test
    fun buildFolderTreeGroupsSessionsUnderWatchedRootProjectFolder() {
        val sessions = listOf(
            entry("codex-api", 3_000L, kind = SessionAgentKind.Codex),
            entry("shell-api", 2_000L),
            entry("claude-docs", 1_000L, kind = SessionAgentKind.Claude),
        )
        val cwds = mapOf(
            "codex-api" to FolderListViewModel.canonicalisePath("/home/alexey/git/pocketshell/app"),
            "shell-api" to FolderListViewModel.canonicalisePath("/home/alexey/git/pocketshell"),
            "claude-docs" to FolderListViewModel.canonicalisePath("/home/alexey/git/docs/site"),
        )
        val watched = listOf(
            ProjectRootEntity(id = 1L, hostId = 7L, label = "[00] git", path = "/home/alexey/git"),
        )

        val roots = FolderListViewModel.buildFolderTree(
            sessions = sessions,
            sessionFolderPaths = cwds,
            watchedFolders = watched,
            scannedProjectFoldersByRoot = mapOf(
                "/home/alexey/git" to listOf(
                    "/home/alexey/git/pocketshell",
                    "/home/alexey/git/docs",
                    "/home/alexey/git/empty-project",
                ),
            ),
        )

        assertEquals(1, roots.size)
        assertEquals("/home/alexey/git", roots[0].path)
        assertEquals("git", roots[0].label)
        assertTrue(roots[0].isWatched)
        assertEquals(
            listOf("pocketshell", "docs"),
            roots[0].folders.map { it.label },
        )
        assertEquals(listOf("codex-api", "shell-api"), roots[0].folders[0].sessions.map { it.sessionName })
        assertEquals(listOf("claude-docs"), roots[0].folders[1].sessions.map { it.sessionName })
        assertEquals(
            listOf("pocketshell", "docs", "empty-project"),
            roots[0].addSheetProjects.map { it.label },
        )
        assertEquals(
            listOf(RootProjectSource.Active, RootProjectSource.Active, RootProjectSource.Scanned),
            roots[0].addSheetProjects.map { it.source },
        )
    }

    @Test
    fun buildFolderTreeMatchesTildeWatchedRootToAbsoluteProjectsAndSessions() {
        val sessions = listOf(
            entry("codex-pocketshell", 3_000L, kind = SessionAgentKind.Codex),
            entry("shell-docs", 2_000L),
        )
        val cwds = mapOf(
            "codex-pocketshell" to FolderListViewModel.canonicalisePath("/home/alexey/git/pocketshell/app"),
            "shell-docs" to FolderListViewModel.canonicalisePath("/home/alexey/git/docs"),
        )
        val watched = listOf(
            ProjectRootEntity(id = 1L, hostId = 7L, label = "[00] git", path = "~/git"),
        )

        val roots = FolderListViewModel.buildFolderTree(
            sessions = sessions,
            sessionFolderPaths = cwds,
            watchedFolders = watched,
            scannedProjectFoldersByRoot = mapOf(
                "~/git" to listOf(
                    "/home/alexey/git/pocketshell",
                    "/home/alexey/git/docs",
                    "/home/alexey/git/empty-project",
                ),
            ),
            resolvedWatchedRootPaths = mapOf("~/git" to "/home/alexey/git"),
        )

        assertEquals(1, roots.size)
        assertEquals("~/git", roots[0].path)
        assertEquals("git", roots[0].label)
        assertEquals(
            listOf("pocketshell", "docs"),
            roots[0].folders.map { it.label },
        )
        assertEquals(listOf("codex-pocketshell"), roots[0].folders[0].sessions.map { it.sessionName })
        assertEquals(listOf("shell-docs"), roots[0].folders[1].sessions.map { it.sessionName })
        assertEquals(
            listOf("pocketshell", "docs", "empty-project"),
            roots[0].addSheetProjects.map { it.label },
        )
    }

    @Test
    fun rootAddSheetCandidatesPutUsedBeforeInactiveBeforeScannedFolders() {
        val watched = listOf(
            ProjectRootEntity(id = 1L, hostId = 7L, label = "git", path = "/home/alexey/git"),
        )

        val roots = FolderListViewModel.buildFolderTree(
            sessions = emptyList(),
            sessionFolderPaths = emptyMap(),
            watchedFolders = watched,
            historyProjectFoldersByRoot = mapOf(
                "/home/alexey/git" to listOf(
                    "/home/alexey/git/zoomcamp",
                    "/home/alexey/git/pocketshell",
                ),
            ),
            scannedProjectFoldersByRoot = mapOf(
                "/home/alexey/git" to listOf(
                    "/home/alexey/git/alpha",
                    "/home/alexey/git/pocketshell",
                    "/home/alexey/git/zoomcamp",
                    "/home/alexey/git/beta",
                ),
            ),
        )

        assertTrue("inactive scanned folders should not flood the main tree", roots.single().folders.isEmpty())
        assertEquals(
            listOf("zoomcamp", "pocketshell", "alpha", "beta"),
            roots.single().addSheetProjects.map { it.label },
        )
        assertEquals(
            listOf(
                RootProjectSource.History,
                RootProjectSource.History,
                RootProjectSource.Scanned,
                RootProjectSource.Scanned,
            ),
            roots.single().addSheetProjects.map { it.source },
        )
    }

    @Test
    fun rootAddSheetCandidatesKeepActiveProjectsStartableAheadOfInactive() {
        val sessions = listOf(entry("main", 3_000L), entry("worker", 2_000L))
        val cwds = mapOf(
            "main" to FolderListViewModel.canonicalisePath("/home/alexey/git/pocketshell/app"),
            "worker" to FolderListViewModel.canonicalisePath("/home/alexey/git/pocketshell"),
        )
        val watched = listOf(
            ProjectRootEntity(id = 1L, hostId = 7L, label = "git", path = "/home/alexey/git"),
        )

        val roots = FolderListViewModel.buildFolderTree(
            sessions = sessions,
            sessionFolderPaths = cwds,
            watchedFolders = watched,
            historyProjectFoldersByRoot = mapOf("/home/alexey/git" to listOf("/home/alexey/git/old")),
            scannedProjectFoldersByRoot = mapOf("/home/alexey/git" to listOf("/home/alexey/git/alpha")),
        )

        assertEquals(listOf("pocketshell"), roots.single().folders.map { it.label })
        assertEquals(
            listOf("pocketshell", "old", "alpha"),
            roots.single().addSheetProjects.map { it.label },
        )
        assertEquals(2, roots.single().addSheetProjects.first().activeSessionCount)
    }

    @Test
    fun rootAddSheetCandidateFilteringMatchesLabelOrPathCaseInsensitively() {
        val candidates = listOf(
            RootProjectCandidate("/home/alexey/git/pocketshell", "pocketshell", RootProjectSource.Active, 1),
            RootProjectCandidate("/home/alexey/git/llm-zoomcamp", "llm-zoomcamp", RootProjectSource.History, 0),
            RootProjectCandidate("/srv/tools/beta", "beta", RootProjectSource.Scanned, 0),
        )

        assertEquals(
            listOf("llm-zoomcamp"),
            FolderListViewModel.filterRootProjectCandidates(candidates, "ZOOM").map { it.label },
        )
        assertEquals(
            listOf("beta"),
            FolderListViewModel.filterRootProjectCandidates(candidates, "/srv").map { it.label },
        )
        assertEquals(candidates, FolderListViewModel.filterRootProjectCandidates(candidates, " "))
    }

    @Test
    fun buildFolderTreeKeepsSessionsOutsideWatchedRootsReachable() {
        val sessions = listOf(
            entry("inside", 2_000L),
            entry("outside", 3_000L),
        )
        val cwds = mapOf(
            "inside" to FolderListViewModel.canonicalisePath("/home/alexey/git/pocketshell"),
            "outside" to FolderListViewModel.canonicalisePath("/tmp/scratch"),
        )
        val watched = listOf(
            ProjectRootEntity(id = 1L, hostId = 7L, label = "git", path = "/home/alexey/git"),
        )

        val roots = FolderListViewModel.buildFolderTree(
            sessions = sessions,
            sessionFolderPaths = cwds,
            watchedFolders = watched,
            scannedProjectFoldersByRoot = emptyMap(),
        )

        assertEquals(listOf("git", FolderListViewModel.OTHER_ROOT_LABEL), roots.map { it.label })
        assertEquals("pocketshell", roots[0].folders.single().label)
        assertEquals("scratch", roots[1].folders.single().label)
        assertFalse(roots[1].isWatched)
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
