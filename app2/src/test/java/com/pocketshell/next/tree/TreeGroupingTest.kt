package com.pocketshell.next.tree

import com.pocketshell.core.hostapi.AgentState
import com.pocketshell.core.hostapi.AgentStateSource
import com.pocketshell.core.hostapi.Backend
import com.pocketshell.core.hostapi.SessionRow
import com.pocketshell.core.hostapi.SessionsJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [groupSessionsByWorkspace] and [relativeActivityLabel] are pure functions, so
 * this suite is plain JUnit — no Robolectric, no coroutines, no clock.
 *
 * The properties worth pinning are the ones a "looks right on my screen" pass
 * cannot see: that the ordering is total (so the same host state always renders
 * the same screen), that a row is never dropped, and that the "other" bucket is
 * the only place a workspace-less session can end up.
 */
class TreeGroupingTest {

    @Test
    fun `groups by the exact workspace string, never by name similarity`() {
        val groups = groupSessionsByWorkspace(
            listOf(
                row("git-pocketshell", workspace = "/home/alexey/git/pocketshell", activity = 300),
                row("git-pocketshell-2", workspace = "/home/alexey/git/pocketshell", activity = 200),
                // Same-looking project, DIFFERENT path: two workspaces, not one.
                row("worktree-run", workspace = "/data/worktrees/pocketshell", activity = 100),
            ),
        )

        assertEquals(
            listOf("/home/alexey/git/pocketshell", "/data/worktrees/pocketshell"),
            groups.map { it.label },
        )
        assertEquals(
            listOf("git-pocketshell", "git-pocketshell-2"),
            groups[0].rows.map { it.name },
        )
        assertEquals(listOf("worktree-run"), groups[1].rows.map { it.name })
    }

    @Test
    fun `two sessions whose names share a prefix do not merge into one group`() {
        // The old client derived a folder from the session NAME. If any of that
        // ever creeps back, these two land in one group and this fails.
        val groups = groupSessionsByWorkspace(
            listOf(
                row("dtc-website", workspace = "/home/a/git/dtc-website", activity = 20),
                row("dtc-website-2", workspace = "/home/a/git/dtc-website-fork", activity = 10),
            ),
        )

        assertEquals(2, groups.size)
    }

    @Test
    fun `rows inside a group sort by activity descending with nulls last`() {
        val groups = groupSessionsByWorkspace(
            listOf(
                row("stale", workspace = "/w", activity = 100),
                row("never-active", workspace = "/w", activity = null),
                row("freshest", workspace = "/w", activity = 900),
                row("middle", workspace = "/w", activity = 500),
            ),
        )

        assertEquals(
            listOf("freshest", "middle", "stale", "never-active"),
            groups.single().rows.map { it.name },
        )
    }

    @Test
    fun `rows with the same activity are ordered by name, so the output is deterministic`() {
        val forward = groupSessionsByWorkspace(
            listOf(
                row("beta", workspace = "/w", activity = 42),
                row("alpha", workspace = "/w", activity = 42),
            ),
        )
        val reversed = groupSessionsByWorkspace(
            listOf(
                row("alpha", workspace = "/w", activity = 42),
                row("beta", workspace = "/w", activity = 42),
            ),
        )

        assertEquals(listOf("alpha", "beta"), forward.single().rows.map { it.name })
        assertEquals(forward.single().rows.map { it.name }, reversed.single().rows.map { it.name })
    }

    @Test
    fun `groups are ordered by their most recent session, and quiet groups sink`() {
        val groups = groupSessionsByWorkspace(
            listOf(
                row("old-a", workspace = "/quiet", activity = 10),
                row("old-b", workspace = "/quiet", activity = 20),
                row("no-activity", workspace = "/unknown-activity", activity = null),
                // A single very fresh session pulls its whole workspace to the
                // top even though the workspace also holds an ancient session.
                row("ancient", workspace = "/busy", activity = 1),
                row("fresh", workspace = "/busy", activity = 999),
            ),
        )

        assertEquals(listOf("/busy", "/quiet", "/unknown-activity"), groups.map { it.label })
    }

    @Test
    fun `groups with equally recent sessions are ordered by label`() {
        val groups = groupSessionsByWorkspace(
            listOf(
                row("z", workspace = "/zulu", activity = 7),
                row("a", workspace = "/alpha", activity = 7),
            ),
        )

        assertEquals(listOf("/alpha", "/zulu"), groups.map { it.label })
    }

    @Test
    fun `a null workspace lands in the other bucket, not in a nameless group`() {
        val groups = groupSessionsByWorkspace(
            listOf(
                row("homeless", workspace = null, activity = 50),
                row("placed", workspace = "/w", activity = 10),
            ),
        )

        val other = groups.single { it.workspace == null }
        assertEquals(OTHER_WORKSPACE_LABEL, other.label)
        assertEquals(listOf("homeless"), other.rows.map { it.name })
        // And it is ordered by recency like any other group — a busy
        // unattributed session is not banished to the bottom.
        assertEquals(OTHER_WORKSPACE_LABEL, groups.first().label)
    }

    @Test
    fun `a blank workspace joins the other bucket rather than rendering an empty header`() {
        val groups = groupSessionsByWorkspace(
            listOf(
                row("blank", workspace = "   ", activity = 5),
                row("null", workspace = null, activity = 6),
            ),
        )

        assertEquals(1, groups.size)
        assertEquals(OTHER_WORKSPACE_LABEL, groups.single().label)
        assertNull(groups.single().workspace)
        assertEquals(listOf("null", "blank"), groups.single().rows.map { it.name })
    }

    @Test
    fun `an UNKNOWN-backend row is grouped and kept, never dropped`() {
        val groups = groupSessionsByWorkspace(
            listOf(
                row("tmux-one", workspace = "/w", activity = 10, backend = Backend.TMUX),
                // A manager this build has never heard of.
                row(
                    "from-the-future",
                    workspace = "/w",
                    activity = 20,
                    backend = Backend.fromWire("warpdrive"),
                ),
                row("no-workspace-future", workspace = null, activity = 30, backend = Backend.UNKNOWN),
            ),
        )

        assertEquals(3, groups.sumOf { it.rows.size })
        val workspaceGroup = groups.single { it.label == "/w" }
        assertEquals(listOf("from-the-future", "tmux-one"), workspaceGroup.rows.map { it.name })
        assertEquals(
            listOf("no-workspace-future"),
            groups.single { it.label == OTHER_WORKSPACE_LABEL }.rows.map { it.name },
        )
        assertTrue(
            "an unrecognised manager must survive as UNKNOWN",
            workspaceGroup.rows.any { it.backend == Backend.UNKNOWN },
        )
    }

    @Test
    fun `every input row survives grouping, tmux and aplexer alike`() {
        val input = listOf(
            row("t1", workspace = "/a", activity = 1, backend = Backend.TMUX),
            row("t2", workspace = null, activity = null, backend = Backend.TMUX),
            row("a1", workspace = "/b", activity = 3, backend = Backend.APLEXER),
            row("a2", workspace = "/a", activity = 4, backend = Backend.APLEXER),
            row("u1", workspace = "/b", activity = null, backend = Backend.UNKNOWN),
        )

        val out = groupSessionsByWorkspace(input)

        assertEquals(
            input.map { it.name }.toSet(),
            out.flatMap { group -> group.rows.map { it.name } }.toSet(),
        )
        assertEquals(input.size, out.sumOf { it.rows.size })
    }

    @Test
    fun `an empty listing produces no groups`() {
        assertEquals(emptyList<WorkspaceGroup>(), groupSessionsByWorkspace(emptyList()))
    }

    @Test
    fun `latestActivityEpoch is the newest member, ignoring nulls`() {
        val group = groupSessionsByWorkspace(
            listOf(
                row("a", workspace = "/w", activity = null),
                row("b", workspace = "/w", activity = 5),
                row("c", workspace = "/w", activity = 77),
            ),
        ).single()

        assertEquals(77L, group.latestActivityEpoch)
    }

    // --- relativeActivityLabel -------------------------------------------

    @Test
    fun `relative activity labels are coarse and bucketed`() {
        val now = 1_000_000L
        assertEquals("just now", relativeActivityLabel(now, now))
        assertEquals("just now", relativeActivityLabel(now - 59, now))
        assertEquals("1m ago", relativeActivityLabel(now - 60, now))
        assertEquals("59m ago", relativeActivityLabel(now - 59 * 60, now))
        assertEquals("1h ago", relativeActivityLabel(now - 3_600, now))
        assertEquals("23h ago", relativeActivityLabel(now - 23 * 3_600, now))
        assertEquals("1d ago", relativeActivityLabel(now - 86_400, now))
        assertEquals("9d ago", relativeActivityLabel(now - 9 * 86_400, now))
    }

    @Test
    fun `no activity means no label, and a host clock ahead of ours does not go negative`() {
        assertNull(relativeActivityLabel(null, 1_000))
        assertEquals("just now", relativeActivityLabel(2_000, 1_000))
    }

    // --- a real dev-box listing ------------------------------------------

    /**
     * The maintainer's actual box, captured with the repository's own host CLI
     * (`pocketshell sessions list --json`, schema 2) and committed verbatim as
     * `fixtures/sessions-list-devbox.json`.
     *
     * Hand-written rows exercise the rules; this exercises the SHAPE — 13
     * sessions over 9 workspaces from BOTH managers, three of them sharing one
     * workspace, several aplexer rows carrying `workspace:tag` names. It runs
     * through the real `SessionsJson` parser, so a change to either side that
     * only breaks on real data breaks here.
     */
    @Test
    fun `a real dev-box listing groups into its workspaces with both managers`() {
        val listing = SessionsJson.parseSessionsList(readFixture("sessions-list-devbox.json"))
            .getOrThrow()

        assertTrue("the capture must carry backend errors as an empty list", listing.errors.isEmpty())
        val groups = groupSessionsByWorkspace(listing.sessions)

        // Nothing is lost and nothing is invented.
        assertEquals(listing.sessions.size, groups.sumOf { it.rows.size })
        assertEquals(
            listing.sessions.map { it.workspace }.toSet(),
            groups.map { it.workspace }.toSet(),
        )

        // BOTH managers really are represented — a tmux-only regression here
        // would be invisible to every hand-written case above.
        val backends = listing.sessions.map { it.backend }.toSet()
        assertTrue("expected tmux rows", Backend.TMUX in backends)
        assertTrue("expected aplexer rows", Backend.APLEXER in backends)

        // The workspace with three sessions is one group, not three.
        val busiest = groups.maxBy { it.rows.size }
        assertTrue("expected a workspace holding several sessions", busiest.rows.size >= 3)

        // Ordering holds on real data: every group's own rows are non-increasing
        // in activity, and the groups themselves are too.
        groups.forEach { group ->
            assertEquals(
                "rows in ${group.label} must be newest-first",
                group.rows.sortedWith(
                    compareBy<SessionRow> { it.activityEpoch == null }
                        .thenByDescending { it.activityEpoch ?: Long.MIN_VALUE },
                ).map { it.name },
                group.rows.map { it.name },
            )
        }
        val groupActivity = groups.map { it.latestActivityEpoch }
        assertEquals(
            "groups must be newest-first",
            groupActivity.sortedWith(
                compareBy<Long?> { it == null }.thenByDescending { it ?: Long.MIN_VALUE },
            ),
            groupActivity,
        )
    }

    private fun readFixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/$name")) {
            "missing test resource fixtures/$name"
        }.bufferedReader().use { it.readText() }

    private fun row(
        name: String,
        workspace: String?,
        activity: Long?,
        backend: Backend = Backend.TMUX,
    ): SessionRow = SessionRow(
        name = name,
        backend = backend,
        id = null,
        workspace = workspace,
        tag = null,
        engine = null,
        profile = null,
        agentState = null as AgentState?,
        agentStateSource = null as AgentStateSource?,
        attached = false,
        createdEpoch = null,
        activityEpoch = activity,
    )
}
