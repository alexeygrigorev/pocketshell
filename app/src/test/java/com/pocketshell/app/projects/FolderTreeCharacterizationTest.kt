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

    // --- Sticky bucketing on a degraded probe (#729, #679 Slice 2) -------

    /**
     * #729 characterization (healthy probe): a session whose cwd sits under a
     * watched root that the probe resolves via [resolvedWatchedRootPaths]
     * (the watched/Room path differs from the canonical/resolved path the cwd
     * lives under — e.g. a symlinked or alias root) buckets under that root.
     * This pins the correct placement the sticky fix must keep identical.
     */
    @Test
    fun sessionUnderAResolvedWatchedRootBucketsUnderThatRoot() {
        val sessions = listOf(session("alpha"))
        val tree = FolderListViewModel.buildFolderTree(
            sessions = sessions,
            sessionFolderPaths = mapOf("alpha" to "/canonical/git/alpha"),
            // Room stores the alias path; the probe resolved it to the
            // canonical path the session cwd actually lives under.
            watchedFolders = listOf(root("/alias/git", "git")),
            scannedProjectFoldersByRoot = emptyMap(),
            resolvedWatchedRootPaths = mapOf("/alias/git" to "/canonical/git"),
        )
        val gitRoot = tree.first { it.path == "/alias/git" }
        assertEquals(
            setOf("/canonical/git/alpha"),
            gitRoot.folders.map { it.path }.toSet(),
        )
        assertFalse(
            "no Other-folders bucket on a healthy probe",
            tree.any { it.path == FolderListViewModel.OTHER_ROOT_PATH },
        )
    }

    /**
     * #729 FAILING-FIRST: same session, but the next probe momentarily returns
     * an EMPTY/incomplete [resolvedWatchedRootPaths] (a transiently-degraded
     * resolution). Without sticky bucketing, [buildFolderTree] re-runs
     * `bestRootForPath` against the degraded roots and the session FLASHES into
     * "Other folders". With a sticky bucket assignment held by node id, the
     * session must STAY under the root it was previously placed under.
     */
    @Test
    fun degradedResolvedRootsKeepStickyBucketUnderPreviousRoot() {
        val sessions = listOf(session("alpha"))
        val degradedTree = FolderListViewModel.buildFolderTree(
            sessions = sessions,
            sessionFolderPaths = mapOf("alpha" to "/canonical/git/alpha"),
            watchedFolders = listOf(root("/alias/git", "git")),
            scannedProjectFoldersByRoot = emptyMap(),
            // Probe momentarily degraded: resolution map empty, so the raw
            // /alias/git no longer matches the /canonical/git/alpha cwd.
            resolvedWatchedRootPaths = emptyMap(),
            // Sticky: alpha was previously placed under the /alias/git root,
            // whose authoritative resolved (match) path was /canonical/git.
            stickyBuckets = mapOf("alpha" to "/canonical/git"),
        )
        assertFalse(
            "a degraded probe must not flash the session into Other folders",
            degradedTree.any { it.path == FolderListViewModel.OTHER_ROOT_PATH },
        )
        val gitRoot = degradedTree.first { it.path == "/alias/git" }
        assertEquals(
            "session stays bucketed under its previously-assigned root",
            setOf("/canonical/git/alpha"),
            gitRoot.folders.map { it.path }.toSet(),
        )
    }

    /**
     * #729: an AUTHORITATIVE move (the session's cwd actually changed to sit
     * outside the held root, and the current healthy probe confirms it has no
     * matching root) DOES re-bucket — stickiness must not pin a session to a
     * root it genuinely left.
     */
    @Test
    fun authoritativeMoveOutsideRootStillReBucketsToOtherFolders() {
        val sessions = listOf(session("alpha"))
        val tree = FolderListViewModel.buildFolderTree(
            sessions = sessions,
            sessionFolderPaths = mapOf("alpha" to "/var/tmp/scratch"),
            watchedFolders = listOf(root("/alias/git", "git")),
            scannedProjectFoldersByRoot = emptyMap(),
            // Healthy probe (resolution present) — the cwd genuinely no longer
            // sits under the watched root.
            resolvedWatchedRootPaths = mapOf("/alias/git" to "/canonical/git"),
            stickyBuckets = mapOf("alpha" to "/canonical/git"),
        )
        val other = tree.first { it.path == FolderListViewModel.OTHER_ROOT_PATH }
        assertEquals(setOf("/var/tmp/scratch"), other.folders.map { it.path }.toSet())
        val gitRoot = tree.first { it.path == "/alias/git" }
        assertTrue("the left root is now empty of sessions", gitRoot.folders.isEmpty())
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
