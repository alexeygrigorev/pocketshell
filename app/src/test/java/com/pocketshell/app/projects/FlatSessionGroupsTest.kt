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
    fun attachedShellLandsInActive() {
        val groups = FlatSessionGroups.from(listOf(session("s", attached = true)))
        assertEquals(listOf("s"), groups.active.map { it.sessionName })
        assertEquals(emptyList<String>(), groups.idle.map { it.sessionName })
    }

    @Test
    fun detachedShellLandsInIdle() {
        val groups = FlatSessionGroups.from(listOf(session("s", attached = false)))
        assertEquals(emptyList<String>(), groups.active.map { it.sessionName })
        assertEquals(listOf("s"), groups.idle.map { it.sessionName })
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

        // Active section: every attached-or-agent session, in input order.
        assertEquals(
            listOf("claude-main", "codex-llm", "detached-agent"),
            groups.active.map { it.sessionName },
        )
        // Idle section: the plain detached shells, in input order.
        assertEquals(
            listOf("build-shell", "old-shell"),
            groups.idle.map { it.sessionName },
        )
    }

    @Test
    fun countsReflectTheSplit() {
        val groups = FlatSessionGroups.from(
            listOf(
                session("a", kind = SessionAgentKind.Claude),
                session("b", attached = true),
                session("c"),
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
                session("b", attached = true),
                session("c"),
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
