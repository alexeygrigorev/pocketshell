package com.pocketshell.app.projects

import com.pocketshell.core.storage.entity.ProjectRootEntity
import com.pocketshell.uikit.model.SessionAgentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EPIC #679 — Slice 1: the maintained in-memory [HostTreeModel].
 *
 * Covers the held-tree contract the maintainer asked for: held across opens
 * (only a host change resets), incremental reconcile (diff add/remove/update —
 * never blank-and-rebuild), by-id optimistic mutations (#653 stop→remove,
 * #678 create→insert) with the optimistic-grace race guard, intrinsic order +
 * expansion, and the infrequent-reconcile staleness gate.
 */
class HostTreeModelTest {

    private fun session(
        name: String,
        cwd: String = "/home/alexey/git/$name",
        lastActivity: Long = 1_000L,
        agentKind: SessionAgentKind = SessionAgentKind.Shell,
        attached: Boolean = false,
        windows: List<FolderSessionWindowEntry> = emptyList(),
    ): FolderSessionEntry =
        FolderSessionEntry(
            sessionName = name,
            lastActivity = lastActivity,
            attached = attached,
            agentKind = agentKind,
            windows = windows,
        )

    private fun window(
        index: Int,
        windowId: String,
        name: String = "win$index",
        active: Boolean = index == 0,
        agentKind: SessionAgentKind = SessionAgentKind.Shell,
    ): FolderSessionWindowEntry =
        FolderSessionWindowEntry(
            index = index,
            name = name,
            active = active,
            command = null,
            agentKind = agentKind,
            windowId = windowId,
        )

    private fun snapshot(
        sessions: List<FolderSessionEntry>,
        resolvedRoots: Map<String, String> = emptyMap(),
    ): HostTreeModel.ProbeSnapshot =
        HostTreeModel.ProbeSnapshot(
            sessions = sessions,
            folderPaths = sessions.associate { it.sessionName to "/home/alexey/git/${it.sessionName}" },
            scannedProjectFoldersByRoot = emptyMap(),
            historyProjectFoldersByRoot = emptyMap(),
            resolvedWatchedRootPaths = resolvedRoots,
        )

    // --- Held across opens -----------------------------------------------

    @Test
    fun bindHostReturnsTrueForNewHostAndFalseForSameHost() {
        val tree = HostTreeModel()
        assertTrue("first bind to a host is a fresh load", tree.bindHost(1L))
        tree.reconcile(snapshot(listOf(session("alpha"))), now = 100L)
        // Re-binding the SAME host must NOT reset — the held tree is reused.
        assertFalse("re-opening the same host reuses the held tree", tree.bindHost(1L))
        assertEquals(listOf("alpha"), tree.sessionEntries().map { it.sessionName })
        // A DIFFERENT host resets.
        assertTrue("a different host is a fresh load", tree.bindHost(2L))
        assertTrue("the new host starts empty", tree.sessionEntries().isEmpty())
    }

    @Test
    fun reOpeningSameHostKeepsSnapshotWithoutReconcile() {
        val tree = HostTreeModel()
        tree.bindHost(1L)
        tree.reconcile(snapshot(listOf(session("alpha"))), now = 100L)
        // Simulate leaving + returning (bind same host again).
        assertFalse(tree.bindHost(1L))
        assertTrue("held tree still has its snapshot on re-open", tree.hasSnapshot)
        assertEquals(100L, tree.lastReconciledAt)
    }

    // --- Incremental reconcile (no blank-and-rebuild) --------------------

    @Test
    fun reconcileAddsNewRemovesGoneAndUpdatesExistingInPlace() {
        val tree = HostTreeModel()
        tree.bindHost(1L)
        tree.reconcile(
            snapshot(listOf(session("alpha", lastActivity = 10L), session("beta", lastActivity = 20L))),
            now = 100L,
        )
        assertEquals(listOf("alpha", "beta"), tree.sessionEntries().map { it.sessionName })

        // beta gone, gamma added, alpha updated (new activity + agent).
        tree.reconcile(
            snapshot(
                listOf(
                    session("alpha", lastActivity = 50L, agentKind = SessionAgentKind.Claude),
                    session("gamma", lastActivity = 30L),
                ),
            ),
            now = 200L,
        )
        val names = tree.sessionEntries().map { it.sessionName }
        // alpha keeps its leading slot (updated in place, not re-added); gamma appended.
        assertEquals(listOf("alpha", "gamma"), names)
        val alpha = tree.sessionEntries().first { it.sessionName == "alpha" }
        assertEquals(50L, alpha.lastActivity)
        assertEquals(SessionAgentKind.Claude, alpha.agentKind)
    }

    @Test
    fun reconcileKeepsExistingSessionSlotsStableAcrossRepeatedProbes() {
        val tree = HostTreeModel()
        tree.bindHost(1L)
        val probe = snapshot(listOf(session("a"), session("b"), session("c")))
        tree.reconcile(probe, now = 100L)
        val order1 = tree.sessionEntries().map { it.sessionName }
        // A routine refresh returning the same set must not reorder.
        tree.reconcile(probe, now = 200L)
        val order2 = tree.sessionEntries().map { it.sessionName }
        assertEquals(order1, order2)
        assertEquals(listOf("a", "b", "c"), order2)
    }

    // --- Sticky agent-ness (#716) ----------------------------------------

    @Test
    fun reconcileKeepsKnownAgentWhenProbeReReportsProbing() {
        val tree = HostTreeModel()
        tree.bindHost(1L)
        // First probe: alpha is a confirmed Claude agent.
        tree.reconcile(
            snapshot(listOf(session("alpha", agentKind = SessionAgentKind.Claude))),
            now = 100L,
        )
        assertEquals(
            SessionAgentKind.Claude,
            tree.sessionEntries().first { it.sessionName == "alpha" }.agentKind,
        )
        // A later (slow/incomplete) probe re-reports it as Probing — sticky:
        // the known agent must NOT be clobbered back to uncertain.
        tree.reconcile(
            snapshot(listOf(session("alpha", agentKind = SessionAgentKind.Probing))),
            now = 200L,
        )
        assertEquals(
            "an incoming Probing must not downgrade a known agent",
            SessionAgentKind.Claude,
            tree.sessionEntries().first { it.sessionName == "alpha" }.agentKind,
        )
    }

    @Test
    fun reconcileDowngradesKnownAgentOnlyOnConfirmedShell() {
        val tree = HostTreeModel()
        tree.bindHost(1L)
        tree.reconcile(
            snapshot(listOf(session("alpha", agentKind = SessionAgentKind.Claude))),
            now = 100L,
        )
        // A CONFIRMED Shell verdict (affirmative interactive-shell pane) is the
        // one explicit signal that downgrades a known agent.
        tree.reconcile(
            snapshot(listOf(session("alpha", agentKind = SessionAgentKind.Shell))),
            now = 200L,
        )
        assertEquals(
            "a confirmed Shell downgrades a known agent",
            SessionAgentKind.Shell,
            tree.sessionEntries().first { it.sessionName == "alpha" }.agentKind,
        )
    }

    @Test
    fun reconcileUpgradesProbingSessionToDetectedAgent() {
        val tree = HostTreeModel()
        tree.bindHost(1L)
        // alpha starts presumed-agent / detecting.
        tree.reconcile(
            snapshot(listOf(session("alpha", agentKind = SessionAgentKind.Probing))),
            now = 100L,
        )
        // Detection completes: it IS a Codex agent — Probing must upgrade.
        tree.reconcile(
            snapshot(listOf(session("alpha", agentKind = SessionAgentKind.Codex))),
            now = 200L,
        )
        assertEquals(
            "a Probing session upgrades to a detected agent",
            SessionAgentKind.Codex,
            tree.sessionEntries().first { it.sessionName == "alpha" }.agentKind,
        )
    }

    @Test
    fun reconcileHonoursAgentToAgentChange() {
        val tree = HostTreeModel()
        tree.bindHost(1L)
        tree.reconcile(
            snapshot(listOf(session("alpha", agentKind = SessionAgentKind.Claude))),
            now = 100L,
        )
        tree.reconcile(
            snapshot(listOf(session("alpha", agentKind = SessionAgentKind.OpenCode))),
            now = 200L,
        )
        assertEquals(
            "an agent→agent change is honoured",
            SessionAgentKind.OpenCode,
            tree.sessionEntries().first { it.sessionName == "alpha" }.agentKind,
        )
    }

    @Test
    fun reconcileKeepsKnownWindowAgentWhenProbeReReportsProbing() {
        val tree = HostTreeModel()
        tree.bindHost(1L)
        tree.reconcile(
            snapshot(
                listOf(
                    session(
                        "alpha",
                        agentKind = SessionAgentKind.Claude,
                        windows = listOf(window(0, "@1", agentKind = SessionAgentKind.Claude)),
                    ),
                ),
            ),
            now = 100L,
        )
        // The window re-reports Probing — the held agent window stays sticky.
        tree.reconcile(
            snapshot(
                listOf(
                    session(
                        "alpha",
                        agentKind = SessionAgentKind.Probing,
                        windows = listOf(window(0, "@1", agentKind = SessionAgentKind.Probing)),
                    ),
                ),
            ),
            now = 200L,
        )
        val win = tree.sessionEntries().first { it.sessionName == "alpha" }.windows.first()
        assertEquals(
            "a known agent WINDOW is not clobbered by an incoming Probing",
            SessionAgentKind.Claude,
            win.agentKind,
        )
    }

    // --- By-id optimistic mutations (#653/#678) --------------------------

    @Test
    fun removeSessionDropsRowImmediatelyById() {
        val tree = HostTreeModel()
        tree.bindHost(1L)
        tree.reconcile(snapshot(listOf(session("alpha"), session("beta"))), now = 100L)
        assertTrue(tree.removeSession("beta"))
        assertEquals(listOf("alpha"), tree.sessionEntries().map { it.sessionName })
        assertFalse("removing an unknown session is a no-op", tree.removeSession("ghost"))
    }

    @Test
    fun insertSessionAppearsImmediatelyAndSurvivesProbeThatHasNotObservedIt() {
        val tree = HostTreeModel()
        tree.bindHost(1L)
        tree.reconcile(snapshot(listOf(session("alpha"))), now = 100L)
        // App creates a window/session optimistically.
        tree.insertSession(session("created"), folderPath = "/home/alexey/git/created", now = 200L)
        assertTrue("created" in tree.sessionEntries().map { it.sessionName })

        // The very next reconcile has NOT observed it yet (within grace) — it must NOT be pruned.
        tree.reconcile(snapshot(listOf(session("alpha"))), now = 205L)
        assertTrue(
            "a just-created node within grace survives a reconcile that has not observed it",
            "created" in tree.sessionEntries().map { it.sessionName },
        )

        // A later reconcile beyond the grace window that still doesn't see it prunes it.
        tree.reconcile(
            snapshot(listOf(session("alpha"))),
            now = 200L + HostTreeModel.OPTIMISTIC_GRACE_MS + 1L,
        )
        assertFalse(
            "beyond grace, an unconfirmed optimistic node is pruned",
            "created" in tree.sessionEntries().map { it.sessionName },
        )
    }

    @Test
    fun probeThatConfirmsOptimisticNodeClearsItsGraceSoItPrunesNormallyAfter() {
        val tree = HostTreeModel()
        tree.bindHost(1L)
        tree.insertSession(session("created"), folderPath = "/home/alexey/git/created", now = 100L)
        // A probe confirms it.
        tree.reconcile(snapshot(listOf(session("created"))), now = 110L)
        assertTrue("created" in tree.sessionEntries().map { it.sessionName })
        // Now that it is confirmed (no longer optimistic), the next probe that
        // drops it removes it immediately — even within the original grace.
        tree.reconcile(snapshot(emptyList()), now = 115L)
        assertFalse(
            "a confirmed node is no longer optimistic and prunes on the next probe",
            "created" in tree.sessionEntries().map { it.sessionName },
        )
    }

    // --- By-id window prune on remote window-close (#653) ----------------

    @Test
    fun removeWindowPrunesExactlyThatWindowLeavingSiblingsAndSessionIntact() {
        val tree = HostTreeModel()
        tree.bindHost(1L)
        // A multi-window session plus an unrelated sibling session.
        tree.reconcile(
            snapshot(
                listOf(
                    session(
                        "multi",
                        windows = listOf(
                            window(0, "@0"),
                            window(1, "@1"),
                            window(2, "@2"),
                        ),
                    ),
                    session("solo", windows = listOf(window(0, "@9"))),
                ),
            ),
            now = 100L,
        )

        // Window @1 of `multi` closes remotely (`%window-close @1`).
        assertTrue("a held window is pruned by id", tree.removeWindow("@1"))

        val multi = tree.sessionEntries().first { it.sessionName == "multi" }
        // Exactly @1 is gone; @0 and @2 (and their order) remain.
        assertEquals(
            "only the closed window is removed; siblings keep their slots",
            listOf("@0", "@2"),
            multi.windows.map { it.windowId },
        )
        // The parent session and the unrelated sibling session are untouched.
        assertEquals(
            listOf("multi", "solo"),
            tree.sessionEntries().map { it.sessionName },
        )
        assertEquals(
            "the sibling session's window is untouched",
            listOf("@9"),
            tree.sessionEntries().first { it.sessionName == "solo" }.windows.map { it.windowId },
        )
    }

    @Test
    fun removeWindowIsANoOpForUnknownOrBlankId() {
        val tree = HostTreeModel()
        tree.bindHost(1L)
        tree.reconcile(
            snapshot(listOf(session("multi", windows = listOf(window(0, "@0"), window(1, "@1"))))),
            now = 100L,
        )
        assertFalse("an id no held window carries is a no-op", tree.removeWindow("@404"))
        assertFalse("a blank id is a no-op", tree.removeWindow(""))
        // Nothing was dropped.
        assertEquals(
            listOf("@0", "@1"),
            tree.sessionEntries().first().windows.map { it.windowId },
        )
        // A second close of the same id (already pruned) is also a no-op.
        assertTrue(tree.removeWindow("@1"))
        assertFalse("a re-delivered window-close for an already-pruned id is a no-op", tree.removeWindow("@1"))
    }

    @Test
    fun removeWindowDoesNotRebuildOrReorderSessions() {
        val tree = HostTreeModel()
        tree.bindHost(1L)
        tree.reconcile(
            snapshot(
                listOf(
                    session("a", windows = listOf(window(0, "@0"))),
                    session("b", windows = listOf(window(0, "@1"), window(1, "@2"))),
                    session("c", windows = listOf(window(0, "@3"))),
                ),
            ),
            now = 100L,
        )
        val orderBefore = tree.sessionEntries().map { it.sessionName }
        tree.removeWindow("@2")
        // Session order is intrinsic and untouched by a window-level prune.
        assertEquals(orderBefore, tree.sessionEntries().map { it.sessionName })
        assertEquals(listOf("a", "b", "c"), orderBefore)
        // The reconcile timestamp is NOT bumped — a window prune is not a probe.
        assertEquals(100L, tree.lastReconciledAt)
    }

    @Test
    fun renameSessionPreservesSlot() {
        val tree = HostTreeModel()
        tree.bindHost(1L)
        tree.reconcile(snapshot(listOf(session("a"), session("b"), session("c"))), now = 100L)
        assertTrue(tree.renameSession("b", "b2"))
        assertEquals(listOf("a", "b2", "c"), tree.sessionEntries().map { it.sessionName })
    }

    // --- Intrinsic expansion (#471) --------------------------------------

    @Test
    fun expansionIsIntrinsicAndSurvivesReconcile() {
        val tree = HostTreeModel()
        tree.bindHost(1L)
        tree.setWatchedFolders(listOf(ProjectRootEntity(hostId = 1L, path = "/home/alexey/git", label = "git")))
        val probe = snapshot(
            listOf(session("alpha"), session("beta")),
            resolvedRoots = mapOf("/home/alexey/git" to "/home/alexey/git"),
        )
        tree.reconcile(probe, now = 100L)
        tree.project()
        val alphaPath = FolderListViewModel.canonicalisePath("/home/alexey/git/alpha")
        assertTrue("active folder auto-expands", alphaPath in tree.expandedPaths())

        // User collapses it.
        tree.toggleProjectExpanded(alphaPath)
        tree.project()
        assertFalse("collapses on user tap", alphaPath in tree.expandedPaths())

        // A reconcile with the same active sessions must NOT re-open it.
        tree.reconcile(probe, now = 200L)
        tree.project()
        assertFalse(
            "reconcile does not re-expand a user-collapsed folder",
            alphaPath in tree.expandedPaths(),
        )
    }

    // --- Infrequent reconcile staleness gate (#679 req #2) ---------------

    @Test
    fun reconcileDueWhenNeverReconciledOrStale() {
        val tree = HostTreeModel()
        tree.bindHost(1L)
        // Never reconciled -> always due.
        assertTrue(tree.reconcileDue(now = 0L, staleAfterMs = HostTreeModel.RECONCILE_STALENESS_MS))
        tree.reconcile(snapshot(listOf(session("alpha"))), now = 1_000L)
        // Fresh -> not due.
        assertFalse(
            tree.reconcileDue(now = 1_000L + 1L, staleAfterMs = HostTreeModel.RECONCILE_STALENESS_MS),
        )
        // Beyond the staleness window -> due.
        assertTrue(
            tree.reconcileDue(
                now = 1_000L + HostTreeModel.RECONCILE_STALENESS_MS,
                staleAfterMs = HostTreeModel.RECONCILE_STALENESS_MS,
            ),
        )
    }

    // --- Optimistic folder overlay (#653 create) -------------------------

    @Test
    fun optimisticFolderAppearsThenRetiresWhenObserved() {
        val tree = HostTreeModel()
        tree.bindHost(1L)
        tree.setWatchedFolders(listOf(ProjectRootEntity(hostId = 1L, path = "/home/alexey/git", label = "git")))
        tree.reconcile(
            snapshot(emptyList(), resolvedRoots = mapOf("/home/alexey/git" to "/home/alexey/git")),
            now = 100L,
        )
        tree.insertOptimisticFolder("/home/alexey/git/fresh", "fresh")
        val withOptimistic = tree.project()
        val gitRoot = withOptimistic.treeRoots.first { it.path == "/home/alexey/git" }
        assertTrue(
            "optimistic folder shows as an add-sheet candidate before a probe confirms it",
            gitRoot.addSheetProjects.any { it.path == "/home/alexey/git/fresh" },
        )

        // A reconcile that now SEES a session in that folder retires the overlay.
        tree.reconcile(
            snapshot(
                listOf(session("fresh", cwd = "/home/alexey/git/fresh")),
                resolvedRoots = mapOf("/home/alexey/git" to "/home/alexey/git"),
            ),
            now = 200L,
        )
        val confirmed = tree.project()
        val gitRoot2 = confirmed.treeRoots.first { it.path == "/home/alexey/git" }
        assertTrue(
            "the folder now renders as an active project",
            gitRoot2.folders.any { it.path == "/home/alexey/git/fresh" },
        )
    }
}
