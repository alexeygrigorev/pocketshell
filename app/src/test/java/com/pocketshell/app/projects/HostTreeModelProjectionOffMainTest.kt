package com.pocketshell.app.projects

import com.pocketshell.core.storage.entity.ProjectRootEntity
import com.pocketshell.uikit.model.SessionAgentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #965 (ANR off-Main) — the JVM fast-first check for the projection split.
 *
 * The folder-list ANR at the maintainer's scale (71 projects / 12 sessions) has
 * three Main-thread contributors; the dominant CPU one is the projection —
 * `buildFolderTree` is O(roots × projects), and at 71 projects it composes a
 * multi-frame Main-thread stall. The fix moves that derivation OFF Main by
 * splitting [HostTreeModel.project] into:
 *
 *  - [HostTreeModel.snapshotForProjection] — a CHEAP immutable copy taken on the
 *    model's owning thread,
 *  - [HostTreeModel.buildProjection] — the PURE, heavy derivation that runs on a
 *    worker dispatcher off Main, and
 *  - [HostTreeModel.applyProjection] — the owning-thread write-back of expansion.
 *
 * This suite pins, at the MAINTAINER'S SCALE, that (a) the heavy half is a pure
 * function of the snapshot — it does NOT touch the model, so it is safe to run
 * off the owning thread; (b) the snapshot-split projection is byte-identical to
 * the inline `project()`; and (c) it stays within a generous wall-time budget so
 * the O(roots × projects) cost is regression-pinned. The load-bearing on-device
 * red→green (the Main-thread `disk_read` the cache read used to trip) lives in
 * the connected `FolderListScaleAnrStrictModeDockerTest`.
 */
class HostTreeModelProjectionOffMainTest {

    private val gitRoot = FolderListViewModel.canonicalisePath("/home/alexey/git")

    private fun projectPath(i: Int): String =
        FolderListViewModel.canonicalisePath("/home/alexey/git/project-$i")

    private val agentKinds = listOf(
        SessionAgentKind.Claude,
        SessionAgentKind.Codex,
        SessionAgentKind.OpenCode,
        SessionAgentKind.Shell,
    )

    /** A model seeded at the maintainer's reported scale: ≥71 projects / ≥12 sessions, mixed kinds. */
    private fun seedScaleModel(projects: Int = 71, sessions: Int = 12): HostTreeModel {
        val tree = HostTreeModel()
        tree.bindHost(1L)
        tree.setWatchedFolders(listOf(ProjectRootEntity(hostId = 1L, label = "git", path = gitRoot)))
        val scanned = (0 until projects).map { projectPath(it) }
        // 12 sessions, each in one of the project folders, mixed agent kinds.
        val sessionEntries = (0 until sessions).map { i ->
            FolderSessionEntry(
                sessionName = "session-$i",
                lastActivity = 1_000L + i,
                attached = i == 0,
                agentKind = agentKinds[i % agentKinds.size],
                windows = emptyList(),
            )
        }
        val folderPaths = sessionEntries.associate { it.sessionName to projectPath(it.sessionName.substringAfter('-').toInt()) }
        tree.reconcile(
            HostTreeModel.ProbeSnapshot(
                sessions = sessionEntries,
                folderPaths = folderPaths,
                scannedProjectFoldersByRoot = mapOf(gitRoot to scanned),
                historyProjectFoldersByRoot = emptyMap(),
                resolvedWatchedRootPaths = mapOf(gitRoot to gitRoot),
            ),
        )
        return tree
    }

    @Test
    fun buildProjectionIsPure_doesNotTouchTheModel() {
        val tree = seedScaleModel()
        val snapshot = tree.snapshotForProjection()
        val expandedBefore = tree.expandedPaths()

        // Run the heavy half TWICE on the same snapshot WITHOUT applying it back.
        val a = HostTreeModel.buildProjection(snapshot)
        val b = HostTreeModel.buildProjection(snapshot)

        // The model's intrinsic expansion memory is UNCHANGED — buildProjection
        // touched no field, so it is safe to run on a worker thread while the
        // model is mutated by Main-confined reconciles.
        assertEquals(
            "buildProjection must NOT mutate the model (off-Main safety)",
            expandedBefore,
            tree.expandedPaths(),
        )
        // It is a deterministic function of the snapshot.
        assertEquals(
            "buildProjection must be a pure function of its snapshot",
            a.projection.treeRoots.map { it.path to it.folders.map { f -> f.path } },
            b.projection.treeRoots.map { it.path to it.folders.map { f -> f.path } },
        )
    }

    @Test
    fun snapshotSplitProjectionMatchesInlineProjectAtScale() {
        // Two independently-seeded identical models so the inline project() side
        // effects don't bleed into the split side.
        val viaInline = seedScaleModel().project()
        val split = seedScaleModel().let { tree ->
            val result = HostTreeModel.buildProjection(tree.snapshotForProjection())
            tree.applyProjection(result)
            result.projection
        }

        assertEquals(
            "the snapshot-split projection must render the same flat session list",
            viaInline.flatSessions.map { it.sessionName },
            split.flatSessions.map { it.sessionName },
        )
        assertEquals(
            "the snapshot-split projection must render the same grouped tree",
            viaInline.treeRoots.map { it.path to it.folders.map { f -> f.path to f.sessions.map { s -> s.sessionName } } },
            split.treeRoots.map { it.path to it.folders.map { f -> f.path to f.sessions.map { s -> s.sessionName } } },
        )
        assertEquals(
            "the snapshot-split projection must auto-expand the same folders",
            viaInline.expandedProjectPaths,
            split.expandedProjectPaths,
        )
        // The dominant "git" root must carry all 71 projects (the ANR scale) —
        // the 12 with a live session render as folders, the rest as add-sheet
        // candidates; together they are the "71 projects" the header counts.
        val gitTree = split.treeRoots.first { it.path == gitRoot }
        assertEquals(
            "the git root must account for all 71 projects at the ANR scale",
            71,
            gitTree.folders.size + gitTree.addSheetProjects.size,
        )
    }

    @Test
    fun heavyProjectionStaysWithinFrameBudgetAtScale() {
        val tree = seedScaleModel()
        val snapshot = tree.snapshotForProjection()
        // Warm up the JIT so the timing reflects steady-state, not first-call.
        repeat(5) { HostTreeModel.buildProjection(snapshot) }

        val iterations = 20
        val start = System.nanoTime()
        repeat(iterations) { HostTreeModel.buildProjection(snapshot) }
        val avgMs = (System.nanoTime() - start) / 1_000_000.0 / iterations

        // A GENEROUS ceiling — the point is to regression-pin the O(roots ×
        // projects) cost, not to micro-benchmark. Even a slow CI agent should
        // build the 71-project projection well under this. (On Main, this same
        // derivation — stacked with the cache parse + first composition — is what
        // crossed the ANR bar; off Main it never blocks a frame regardless.)
        assertTrue(
            "the 71-project projection must stay within a generous budget " +
                "(avg=${"%.2f".format(avgMs)}ms) — it is the O(roots × projects) cost",
            avgMs < 200.0,
        )
    }

    @Test
    fun snapshotIsAnImmutableCopy_modelMutationDoesNotLeakIntoIt() {
        val tree = seedScaleModel()
        val snapshot = tree.snapshotForProjection()
        val sizeBefore = snapshot.orderedSessions.size
        // Mutate the model AFTER taking the snapshot.
        tree.removeSession("session-0")
        // The snapshot is a copy — the heavy build over it still sees the
        // pre-mutation set, so an off-Main build can never observe a half-mutated
        // model.
        assertEquals(
            "the projection snapshot must be an immutable copy, isolated from later model mutations",
            sizeBefore,
            snapshot.orderedSessions.size,
        )
        val sameSnapshot = snapshot
        assertSame(snapshot.orderedSessions, sameSnapshot.orderedSessions)
    }
}
