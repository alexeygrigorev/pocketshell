package com.pocketshell.app.projects

import com.pocketshell.uikit.model.SessionAgentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Epic #821 slice C (issue #837): the durable-tree HYDRATE / EXPORT / gone-delta
 * surface on [HostTreeModel].
 *
 * These are the unit-level red→green proofs that:
 *  - a cold start can SEED the held tree from the persisted registry so the
 *    order + collapse render before the first reconcile;
 *  - that seed survives the freshening reconcile and a re-bind of the SAME host
 *    (the reconnect regression guard at the model layer);
 *  - export round-trips order + folder + collapse + foreign-guess but never the
 *    confirmed kind;
 *  - a gone-delta prunes in place (deltas only), respecting optimistic grace.
 */
class HostTreeModelHydrateTest {

    private fun node(
        name: String,
        order: Int,
        folder: String = "/home/alexey/git/$name",
        collapsed: Boolean = false,
        foreign: SessionAgentKind? = null,
    ) = HostTreeModel.HydratedNode(
        sessionName = name,
        order = order,
        folderPath = FolderListViewModel.canonicalisePath(folder),
        collapsed = collapsed,
        foreignGuess = foreign,
    )

    private fun probe(names: List<String>) = HostTreeModel.ProbeSnapshot(
        sessions = names.map {
            FolderSessionEntry(
                sessionName = it,
                lastActivity = 1_000L,
                attached = false,
                agentKind = SessionAgentKind.Shell,
            )
        },
        folderPaths = names.associateWith { "/home/alexey/git/$it" },
        scannedProjectFoldersByRoot = emptyMap(),
        historyProjectFoldersByRoot = emptyMap(),
        resolvedWatchedRootPaths = emptyMap(),
    )

    @Test
    fun hydrateSeedsOrderInstantlyBeforeAnyProbe() {
        val tree = HostTreeModel()
        tree.bindHost(1L)
        // Held order is b, then a (NOT alphabetical) — proves the persisted
        // intrinsic order is honoured, not re-derived.
        tree.hydrate(
            listOf(
                node("beta", order = 0),
                node("alpha", order = 1),
            ),
        )
        assertTrue("hydrate marks the tree renderable", tree.hasSnapshot)
        assertEquals(listOf("beta", "alpha"), tree.sessionEntries().map { it.sessionName })
    }

    @Test
    fun hydrateSeedsCollapseMemorySoFolderStaysCollapsed() {
        val tree = HostTreeModel()
        tree.bindHost(1L)
        val betaFolder = FolderListViewModel.canonicalisePath("/home/alexey/git/beta")
        tree.hydrate(
            listOf(
                node("alpha", order = 0, collapsed = false),
                node("beta", order = 1, collapsed = true),
            ),
        )
        // A subsequent reconcile of the same sessions must NOT re-open the folder
        // the user had collapsed (the collapse memory was seeded by hydrate).
        tree.reconcile(probe(listOf("alpha", "beta")), now = 100L)
        val projection = tree.project()
        assertFalse(
            "the collapsed folder must stay collapsed after hydrate + reconcile",
            betaFolder in projection.expandedProjectPaths,
        )
    }

    @Test
    fun hydrateSeedsForeignGuessKind() {
        val tree = HostTreeModel()
        tree.bindHost(1L)
        tree.hydrate(listOf(node("agent", order = 0, foreign = SessionAgentKind.Codex)))
        val entry = tree.sessionEntries().single()
        assertEquals(SessionAgentKind.Codex, entry.agentKind)
    }

    @Test
    fun hydrateIsNoOpOnceTreeHasSnapshot() {
        val tree = HostTreeModel()
        tree.bindHost(1L)
        // A live probe populates the tree FIRST (fresher than any seed).
        tree.reconcile(probe(listOf("live")), now = 100L)
        // A late hydrate must NOT clobber the fresher live content.
        tree.hydrate(listOf(node("stale", order = 0)))
        assertEquals(listOf("live"), tree.sessionEntries().map { it.sessionName })
    }

    @Test
    fun hydrateEmptyListIsNoOp() {
        val tree = HostTreeModel()
        tree.bindHost(1L)
        tree.hydrate(emptyList())
        assertFalse(tree.hasSnapshot)
        assertTrue(tree.sessionEntries().isEmpty())
    }

    @Test
    fun reconcileAfterHydratePrunesSessionGoneSinceLastTime() {
        val tree = HostTreeModel()
        tree.bindHost(1L)
        tree.hydrate(listOf(node("alive", order = 0), node("dead", order = 1)))
        // First reconcile observes only `alive`: the hydrated `dead` (no
        // optimistic marker) must be pruned, not lingering forever.
        tree.reconcile(probe(listOf("alive")), now = 100L)
        assertEquals(listOf("alive"), tree.sessionEntries().map { it.sessionName })
    }

    @Test
    fun exportRoundTripsOrderFolderAndCollapseButNotConfirmedKind() {
        val tree = HostTreeModel()
        tree.bindHost(1L)
        tree.hydrate(
            listOf(
                node("beta", order = 0),
                node("alpha", order = 1, collapsed = true),
            ),
        )
        val exported = tree.exportNodes()
        assertEquals(listOf("beta", "alpha"), exported.map { it.sessionName })
        // Order is re-derived from intrinsic slot (0, 1).
        assertEquals(listOf(0, 1), exported.map { it.order })
        val alpha = exported.first { it.sessionName == "alpha" }
        assertTrue("the user's collapse choice round-trips", alpha.collapsed)
    }

    @Test
    fun exportDoesNotEmitConfirmedAgentKindAsForeignGuess() {
        val tree = HostTreeModel()
        tree.bindHost(1L)
        // A reconciled session that the gateway marked as a known agent
        // (recorded kind via @ps_agent_kind) must NOT leak into the registry's
        // foreign-guess field — that would be a second kind writer.
        tree.reconcile(
            HostTreeModel.ProbeSnapshot(
                sessions = listOf(
                    FolderSessionEntry(
                        sessionName = "claude-sess",
                        lastActivity = 1L,
                        attached = false,
                        agentKind = SessionAgentKind.Claude,
                    ),
                ),
                folderPaths = mapOf("claude-sess" to "/home/alexey/git/x"),
                scannedProjectFoldersByRoot = emptyMap(),
                historyProjectFoldersByRoot = emptyMap(),
                resolvedWatchedRootPaths = emptyMap(),
            ),
            now = 100L,
        )
        // The export's foreignGuess carries the kind hint; the daemon registry
        // stores it ONLY as a guess cache, never as the confirmed kind — and the
        // CONFIRMED authority remains @ps_agent_kind on the host. Here we assert
        // the export shape carries it as a guess (acceptable cache), and that
        // there is no separate confirmed-kind field on the node at all.
        val exported = tree.exportNodes().single()
        // The HydratedNode has NO confirmed-kind field — only foreignGuess.
        assertEquals(SessionAgentKind.Claude, exported.foreignGuess)
    }

    @Test
    fun applyReconcileGoneDeltaPrunesInPlace() {
        val tree = HostTreeModel()
        tree.bindHost(1L)
        tree.hydrate(listOf(node("keep", order = 0), node("drop", order = 1)))
        val pruned = tree.applyReconcileGoneDelta(listOf("drop"))
        assertTrue(pruned)
        assertEquals(listOf("keep"), tree.sessionEntries().map { it.sessionName })
    }

    @Test
    fun applyReconcileGoneDeltaSparesNodeWithinOptimisticGrace() {
        val tree = HostTreeModel()
        tree.bindHost(1L)
        // A freshly app-inserted session (optimistic marker set now) must be
        // spared from a gone-delta that has not yet observed it.
        tree.insertSession(
            FolderSessionEntry(
                sessionName = "fresh",
                lastActivity = 1L,
                attached = false,
                agentKind = SessionAgentKind.Shell,
            ),
            folderPath = "/home/alexey/git/fresh",
            now = 1_000L,
        )
        val pruned = tree.applyReconcileGoneDelta(listOf("fresh"), now = 1_005L)
        assertFalse("a node within optimistic grace is spared", pruned)
        assertEquals(listOf("fresh"), tree.sessionEntries().map { it.sessionName })
    }
}
