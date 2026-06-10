package com.pocketshell.app.projects

import com.pocketshell.uikit.model.SessionAgentKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-logic unit tests for the flat-view Active/Idle grouping + header counts
 * (#489). No Android / Robolectric needed — [FlatSessionGroups.from],
 * [flatHostCountText], and [flatSessionFolderLabel] are all pure functions.
 */
class FlatSessionGroupsTest {

    private fun session(
        name: String,
        attached: Boolean = false,
        kind: SessionAgentKind = SessionAgentKind.Shell,
    ) = FolderSessionEntry(
        sessionName = name,
        lastActivity = null,
        attached = attached,
        agentKind = kind,
    )

    @Test
    fun attachedShellStaysInIdle() {
        // #663: a plain shell is Idle whether or not the user has it attached.
        // `attached` is the user's own viewing action, not agent activity, so it
        // must never move the row to Active.
        val groups = FlatSessionGroups.from(listOf(session("s", attached = true)))
        assertEquals(emptyList<String>(), groups.active.map { it.sessionName })
        assertEquals(listOf("s"), groups.idle.map { it.sessionName })
    }

    @Test
    fun detachedShellLandsInIdle() {
        val groups = FlatSessionGroups.from(listOf(session("s", attached = false)))
        assertEquals(emptyList<String>(), groups.active.map { it.sessionName })
        assertEquals(listOf("s"), groups.idle.map { it.sessionName })
    }

    /**
     * Regression for #663: opening (attaching) a plain shell must NOT change its
     * section or its index in the list. Before vs after the `attached` flip the
     * partition puts the same shell in the same place, so the row never jumps
     * under the user's finger and they don't mis-tap.
     */
    @Test
    fun attachingPlainShellDoesNotMoveItsRowOrReorderTheList() {
        val before = listOf(
            session("claude-main", attached = false, kind = SessionAgentKind.Claude),
            session("build-shell", attached = false, kind = SessionAgentKind.Shell),
            session("notes-shell", attached = false, kind = SessionAgentKind.Shell),
        )
        // Same list, but the user has now opened (attached) the middle shell.
        val after = listOf(
            session("claude-main", attached = false, kind = SessionAgentKind.Claude),
            session("build-shell", attached = true, kind = SessionAgentKind.Shell),
            session("notes-shell", attached = false, kind = SessionAgentKind.Shell),
        )

        val groupsBefore = FlatSessionGroups.from(before)
        val groupsAfter = FlatSessionGroups.from(after)

        // The agent stays Active; both plain shells stay Idle — identical before
        // and after the attach. The attached shell did NOT jump to Active.
        assertEquals(listOf("claude-main"), groupsBefore.active.map { it.sessionName })
        assertEquals(listOf("claude-main"), groupsAfter.active.map { it.sessionName })
        assertEquals(
            listOf("build-shell", "notes-shell"),
            groupsBefore.idle.map { it.sessionName },
        )
        assertEquals(
            listOf("build-shell", "notes-shell"),
            groupsAfter.idle.map { it.sessionName },
        )

        // The just-attached shell keeps the same section AND the same relative
        // index within that section (Idle index 0, ahead of notes-shell).
        assertEquals(
            "attached shell must keep its Idle index",
            groupsBefore.idle.indexOfFirst { it.sessionName == "build-shell" },
            groupsAfter.idle.indexOfFirst { it.sessionName == "build-shell" },
        )
        assertEquals(
            "attached shell stays at the front of Idle",
            0,
            groupsAfter.idle.indexOfFirst { it.sessionName == "build-shell" },
        )
        assertEquals(
            "attached shell must stay in Idle (not move to Active)",
            -1,
            groupsAfter.active.indexOfFirst { it.sessionName == "build-shell" },
        )

        // Section membership and order are byte-for-byte stable across the flip.
        assertEquals(
            groupsBefore.active.map { it.sessionName },
            groupsAfter.active.map { it.sessionName },
        )
        assertEquals(
            groupsBefore.idle.map { it.sessionName },
            groupsAfter.idle.map { it.sessionName },
        )
    }

    @Test
    fun agentKindsCountAsActiveEvenWhenDetached() {
        val agentKinds = listOf(
            SessionAgentKind.Claude,
            SessionAgentKind.Codex,
            SessionAgentKind.OpenCode,
            SessionAgentKind.Probing,
            SessionAgentKind.Exited,
        )
        agentKinds.forEach { kind ->
            val groups = FlatSessionGroups.from(listOf(session("a", attached = false, kind = kind)))
            assertEquals("$kind should be active", listOf("a"), groups.active.map { it.sessionName })
            assertEquals("$kind should not be idle", emptyList<String>(), groups.idle.map { it.sessionName })
        }
    }

    @Test
    fun splitMatchesStatusDotConditionAndPreservesInputOrder() {
        val input = listOf(
            session("claude-main", attached = true, kind = SessionAgentKind.Claude),
            session("build-shell", attached = false, kind = SessionAgentKind.Shell),
            session("codex-llm", attached = true, kind = SessionAgentKind.Codex),
            session("old-shell", attached = false, kind = SessionAgentKind.Shell),
            session("detached-agent", attached = false, kind = SessionAgentKind.OpenCode),
        )
        val groups = FlatSessionGroups.from(input)

        // Active section: agent sessions only (#663 — attached does NOT count),
        // in input order.
        assertEquals(
            listOf("claude-main", "codex-llm", "detached-agent"),
            groups.active.map { it.sessionName },
        )
        // Idle section: the plain shells, in input order — including the one the
        // user happens to have attached.
        assertEquals(
            listOf("build-shell", "old-shell"),
            groups.idle.map { it.sessionName },
        )
    }

    @Test
    fun attachedPlainShellAmongAgentsStaysIdle() {
        // #663: even when an attached shell sits between agents, it stays Idle.
        val input = listOf(
            session("claude-main", attached = false, kind = SessionAgentKind.Claude),
            session("attached-shell", attached = true, kind = SessionAgentKind.Shell),
            session("codex-llm", attached = false, kind = SessionAgentKind.Codex),
        )
        val groups = FlatSessionGroups.from(input)
        assertEquals(
            listOf("claude-main", "codex-llm"),
            groups.active.map { it.sessionName },
        )
        assertEquals(listOf("attached-shell"), groups.idle.map { it.sessionName })
    }

    @Test
    fun countsReflectTheSplit() {
        // Two agents → Active; two plain shells (one of them attached) → Idle.
        val groups = FlatSessionGroups.from(
            listOf(
                session("a", kind = SessionAgentKind.Claude),
                session("b", kind = SessionAgentKind.Codex),
                session("c", attached = true),
                session("d"),
            ),
        )
        assertEquals(2, groups.activeCount)
        assertEquals(2, groups.idleCount)
        assertEquals(4, groups.totalCount)
    }

    @Test
    fun emptyInputProducesEmptyGroups() {
        val groups = FlatSessionGroups.from(emptyList())
        assertEquals(0, groups.activeCount)
        assertEquals(0, groups.idleCount)
        assertEquals(0, groups.totalCount)
    }

    @Test
    fun headerCountTextShowsAllThreeFacets() {
        val groups = FlatSessionGroups.from(
            listOf(
                session("a", kind = SessionAgentKind.Claude),
                session("b", kind = SessionAgentKind.Codex),
                session("c", attached = true),
                session("d"),
            ),
        )
        assertEquals("2 active · 2 idle · 4 sessions", flatHostCountText(groups))
    }

    @Test
    fun headerCountTextSingularSessionAndZeroFacets() {
        val groups = FlatSessionGroups.from(listOf(session("only")))
        assertEquals("0 active · 1 idle · 1 session", flatHostCountText(groups))
    }

    @Test
    fun folderLabelResolvesFromMapsThenFallsBackToPathLabel() {
        val entry = session("claude-main", attached = true, kind = SessionAgentKind.Claude)
        // Resolves via the label map when present.
        assertEquals(
            "pocketshell",
            flatSessionFolderLabel(
                session = entry,
                sessionFolderPaths = mapOf("claude-main" to "/home/u/git/pocketshell"),
                sessionFolderLabels = mapOf("/home/u/git/pocketshell" to "pocketshell"),
            ),
        )
        // Falls back to a path-derived label when no label is mapped.
        assertEquals(
            "llm-zoomcamp",
            flatSessionFolderLabel(
                session = session("codex-llm"),
                sessionFolderPaths = mapOf("codex-llm" to "/home/u/git/llm-zoomcamp"),
                sessionFolderLabels = emptyMap(),
            ),
        )
        // Unknown session → untracked path → untracked label, never blank.
        val untrackedLabel = flatSessionFolderLabel(
            session = session("orphan"),
            sessionFolderPaths = emptyMap(),
            sessionFolderLabels = emptyMap(),
        )
        assertEquals(
            FolderListViewModel.defaultLabelForPath(FolderListViewModel.UNTRACKED_PATH),
            untrackedLabel,
        )
    }
}
