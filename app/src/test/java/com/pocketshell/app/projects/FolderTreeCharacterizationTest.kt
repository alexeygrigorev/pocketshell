package com.pocketshell.app.projects

import com.pocketshell.core.storage.entity.ProjectRootEntity
import com.pocketshell.uikit.model.SessionAgentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EPIC #679 — Slice 0 characterization (the gate).
 *
 * Pins the CURRENT project-tree enumeration + ordering + bucket placement that
 * the maintainer sees, expressed against the pure builders
 * ([FolderListViewModel.groupSessionsIntoFolders] /
 * [FolderListViewModel.buildFolderTree]) that BOTH the legacy `emitReady()`
 * rebuild and the new [HostTreeModel.project] feed. These are the visual-parity
 * invariants the maintained-tree rewrite must keep byte-identical: the same
 * folders, the same root buckets, the same "Other folders" placement, the same
 * within-folder/within-root order, the same multi-window declutter inputs.
 *
 * Slice 0 runs these green on the current behaviour; Slice 1 keeps them green by
 * routing the projection through the SAME builders. A regression here is a
 * regression in what the user sees on the host-detail screen.
 */
class FolderTreeCharacterizationTest {

    private fun session(
        name: String,
        agentKind: SessionAgentKind = SessionAgentKind.Shell,
        lastActivity: Long = 1_000L,
        windows: List<FolderSessionWindowEntry> = emptyList(),
    ): FolderSessionEntry =
        FolderSessionEntry(
            sessionName = name,
            lastActivity = lastActivity,
            attached = false,
            agentKind = agentKind,
            windows = windows,
        )

    private fun root(path: String, label: String): ProjectRootEntity =
        ProjectRootEntity(hostId = 1L, path = path, label = label)

    // --- Folder grouping (flat) ------------------------------------------

    @Test
    fun groupsSessionsByCwdAndSortsActiveByRecency() {
        val sessions = listOf(
            session("old", lastActivity = 10L),
            session("new", lastActivity = 99L),
        )
        val folders = FolderListViewModel.groupSessionsIntoFolders(
            sessions = sessions,
            sessionFolderPaths = mapOf(
                "old" to "/home/alexey/git/old",
                "new" to "/home/alexey/git/new",
            ),
            watchedFolders = emptyList(),
        )
        // Most-recent activity first.
        assertEquals(
            listOf("/home/alexey/git/new", "/home/alexey/git/old"),
            folders.map { it.path },
        )
    }

    @Test
    fun watchedEmptyFolderAppearsAfterActiveAndUntrackedLast() {
        val sessions = listOf(
            session("a", lastActivity = 50L),
            // A session with no cwd lands under the Untracked bucket.
            session("orphan", lastActivity = 90L),
        )
        val folders = FolderListViewModel.groupSessionsIntoFolders(
            sessions = sessions,
            sessionFolderPaths = mapOf("a" to "/home/alexey/git/a"),
            watchedFolders = listOf(root("/home/alexey/git/empty", "empty")),
        )
        val paths = folders.map { it.path }
        // Active folder first, watched-but-empty in the middle, untracked last.
        assertEquals("/home/alexey/git/a", paths.first())
        assertEquals(FolderListViewModel.UNTRACKED_PATH, paths.last())
        assertTrue("/home/alexey/git/empty" in paths)
        val watchedRow = folders.first { it.path == "/home/alexey/git/empty" }
        assertTrue("watched-but-empty folder marked isEmpty", watchedRow.isEmpty)
        assertTrue("watched folder marked isWatched", watchedRow.isWatched)
    }

    // --- Tree bucketing (watched roots + Other folders) ------------------

    @Test
    fun sessionsUnderAWatchedRootBucketIntoThatRootAsProjects() {
        val sessions = listOf(session("alpha"), session("beta"))
        val tree = FolderListViewModel.buildFolderTree(
            sessions = sessions,
            sessionFolderPaths = mapOf(
                "alpha" to "/home/alexey/git/alpha",
                "beta" to "/home/alexey/git/beta",
            ),
            watchedFolders = listOf(root("/home/alexey/git", "git")),
            scannedProjectFoldersByRoot = emptyMap(),
            resolvedWatchedRootPaths = mapOf("/home/alexey/git" to "/home/alexey/git"),
        )
        assertEquals(1, tree.size)
        val gitRoot = tree.single()
        assertEquals("/home/alexey/git", gitRoot.path)
        assertEquals(
            setOf("/home/alexey/git/alpha", "/home/alexey/git/beta"),
            gitRoot.folders.map { it.path }.toSet(),
        )
        assertTrue("a watched root is marked isWatched", gitRoot.isWatched)
    }

    @Test
    fun sessionsOutsideAnyWatchedRootBucketIntoOtherFolders() {
        val sessions = listOf(session("inside"), session("outside"))
        val tree = FolderListViewModel.buildFolderTree(
            sessions = sessions,
            sessionFolderPaths = mapOf(
                "inside" to "/home/alexey/git/inside",
                "outside" to "/var/tmp/scratch",
            ),
            watchedFolders = listOf(root("/home/alexey/git", "git")),
            scannedProjectFoldersByRoot = emptyMap(),
            resolvedWatchedRootPaths = mapOf("/home/alexey/git" to "/home/alexey/git"),
        )
        val gitRoot = tree.first { it.path == "/home/alexey/git" }
        val other = tree.first { it.path == FolderListViewModel.OTHER_ROOT_PATH }
        assertEquals(FolderListViewModel.OTHER_ROOT_LABEL, other.label)
        assertFalse("Other folders is not a watched root", other.isWatched)
        assertEquals(setOf("/home/alexey/git/inside"), gitRoot.folders.map { it.path }.toSet())
        assertEquals(setOf("/var/tmp/scratch"), other.folders.map { it.path }.toSet())
    }

    @Test
    fun withNoWatchedRootsEverythingFallsIntoOtherFolders() {
        val sessions = listOf(session("a"), session("b"))
        val tree = FolderListViewModel.buildFolderTree(
            sessions = sessions,
            sessionFolderPaths = mapOf(
                "a" to "/home/alexey/git/a",
                "b" to "/home/alexey/git/b",
            ),
            watchedFolders = emptyList(),
            scannedProjectFoldersByRoot = emptyMap(),
        )
        assertEquals(listOf(FolderListViewModel.OTHER_ROOT_PATH), tree.map { it.path })
        val other = tree.single()
        assertEquals(
            setOf("/home/alexey/git/a", "/home/alexey/git/b"),
            other.folders.map { it.path }.toSet(),
        )
    }

    // --- Multi-window declutter inputs (#675) ----------------------------

    @Test
    fun sessionWindowsArePreservedThroughTheTreeProjection() {
        val windows = listOf(
            FolderSessionWindowEntry(index = 0, name = "shell", active = false, command = "bash", agentKind = SessionAgentKind.Shell),
            FolderSessionWindowEntry(index = 1, name = "agent", active = true, command = "claude", agentKind = SessionAgentKind.Claude),
        )
        val tree = FolderListViewModel.buildFolderTree(
            sessions = listOf(session("multi", agentKind = SessionAgentKind.Claude, windows = windows)),
            sessionFolderPaths = mapOf("multi" to "/home/alexey/git/multi"),
            watchedFolders = listOf(root("/home/alexey/git", "git")),
            scannedProjectFoldersByRoot = emptyMap(),
            resolvedWatchedRootPaths = mapOf("/home/alexey/git" to "/home/alexey/git"),
        )
        val sessionEntry = tree.single().folders.single().sessions.single()
        assertEquals(2, sessionEntry.windows.size)
        assertEquals(listOf(0, 1), sessionEntry.windows.map { it.index })
        assertEquals(SessionAgentKind.Claude, sessionEntry.windows[1].agentKind)
    }

    // --- Flat active/idle partition (#489/#663) --------------------------

    @Test
    fun flatPartitionSplitsAgentsActiveAndShellsIdle() {
        val groups = FlatSessionGroups.from(
            listOf(
                session("claude", agentKind = SessionAgentKind.Claude),
                session("shell", agentKind = SessionAgentKind.Shell),
                session("codex", agentKind = SessionAgentKind.Codex),
            ),
        )
        assertEquals(listOf("claude", "codex"), groups.active.map { it.sessionName })
        assertEquals(listOf("shell"), groups.idle.map { it.sessionName })
    }
}
