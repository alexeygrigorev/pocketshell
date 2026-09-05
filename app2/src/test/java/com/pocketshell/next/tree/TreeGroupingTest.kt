package com.pocketshell.next.tree

import com.pocketshell.core.hostapi.AgentState
import com.pocketshell.core.hostapi.AgentStateSource
import com.pocketshell.core.hostapi.Backend
import com.pocketshell.core.hostapi.SessionRow
import com.pocketshell.core.hostapi.SessionsJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [groupSessionsIntoRoots] and [relativeActivityLabel] are pure functions, so
 * this suite is plain JUnit — no Robolectric, no coroutines, no clock.
 *
 * The properties worth pinning are the ones a "looks right on my screen" pass
 * cannot see: root → folder → session (never exact workspace paths as headers),
 * creation order that does not move under an activity bump, 1:1 folders that
 * still occupy a folder row, registered roots with `other` for the rest, and
 * that a row is never dropped.
 */
class TreeGroupingTest {

    /**
     * Issue #2530: `/home/x/git/a` and `/home/x/tmp/b` must become two ROOTS
     * (`~/git` / `~/tmp`), not two section headers that print the full cwd.
     * Was RED on exact-workspace-path grouping.
     */
    @Test
    fun `git and tmp under home become two roots not two full-path headers`() {
        val roots = groupSessionsIntoRoots(
            listOf(
                row("sess-a", workspace = "/home/x/git/a", activity = 50, created = 10),
                row("sess-b", workspace = "/home/x/tmp/b", activity = 90, created = 20),
            ),
        )
        val labels = roots.map { it.headerLabel }
        assertEquals(listOf("~/git", "~/tmp"), labels)
        assertTrue(
            "must not use the exact workspace path as the section header, got $labels",
            labels.none { it.contains("/home/x/") },
        )
        assertEquals(listOf("a"), roots[0].folders.map { it.label })
        assertEquals(listOf("b"), roots[1].folders.map { it.label })
    }

    /**
     * Issue #2530: bumping `activityEpoch` must not move rows. Creation order
     * (oldest first) is the only key. Was RED on the recency sort.
     */
    @Test
    fun `activityEpoch bump does not reorder rows`() {
        fun listing(oldActivity: Long) = listOf(
            row("older", workspace = "/home/x/git/a", activity = oldActivity, created = 1),
            row("newer", workspace = "/home/x/git/a", activity = 50, created = 2),
        )
        val before = leafNames(groupSessionsIntoRoots(listing(10)))
        val after = leafNames(groupSessionsIntoRoots(listing(999)))
        assertEquals("creation order must survive an activity bump", before, after)
        assertEquals(listOf("older", "newer"), before)
    }

    @Test
    fun `a newer session appends in creation order`() {
        val roots = groupSessionsIntoRoots(
            listOf(
                row("first", workspace = "/home/x/git/a", activity = 9, created = 10),
                row("second", workspace = "/home/x/git/a", activity = 1, created = 20),
            ),
        )
        assertEquals(listOf("first", "second"), roots.single().folders.single().rows.map { it.name })
    }

    @Test
    fun `two sessions in the same cwd sit under one folder`() {
        val roots = groupSessionsIntoRoots(
            listOf(
                row("git-pocketshell", workspace = "/home/x/git/pocketshell", activity = 30, created = 1),
                row("git-pocketshell-2", workspace = "/home/x/git/pocketshell", activity = 10, created = 2),
            ),
        )
        val git = roots.single()
        assertEquals("~/git", git.headerLabel)
        assertEquals(1, git.folders.size)
        assertEquals("pocketshell", git.folders.single().label)
        assertEquals(
            listOf("git-pocketshell", "git-pocketshell-2"),
            git.folders.single().rows.map { it.name },
        )
    }

    @Test
    fun `a 1-to-1 folder still has a folder row then a session row`() {
        val roots = groupSessionsIntoRoots(
            listOf(row("git-aplexer", workspace = "/home/x/git/aplexer", activity = 5, created = 1)),
        )
        val git = roots.single()
        assertEquals("~/git", git.headerLabel)
        assertEquals(listOf("aplexer"), git.folders.map { it.label })
        assertFalse("a 1:1 folder must not collapse into the session row", git.folders.single().untracked)
        assertEquals(listOf("git-aplexer"), git.folders.single().rows.map { it.name })
    }

    @Test
    fun `two sessions whose names share a prefix do not merge into one folder`() {
        // The old client derived a folder from the session NAME. If any of that
        // ever creeps back, these two land in one folder and this fails.
        val roots = groupSessionsIntoRoots(
            listOf(
                row("dtc-website", workspace = "/home/a/git/dtc-website", activity = 20, created = 1),
                row("dtc-website-2", workspace = "/home/a/git/dtc-website-fork", activity = 10, created = 2),
            ),
        )
        assertEquals(listOf("dtc-website", "dtc-website-fork"), roots.single().folders.map { it.label })
    }

    @Test
    fun `same-looking project at a different path is a different folder, not a name merge`() {
        val roots = groupSessionsIntoRoots(
            listOf(
                row("git-pocketshell", workspace = "/home/alexey/git/pocketshell", activity = 300, created = 1),
                row("git-pocketshell-2", workspace = "/home/alexey/git/pocketshell", activity = 200, created = 2),
                row("worktree-run", workspace = "/data/worktrees/pocketshell", activity = 100, created = 3),
            ),
        )
        assertEquals(listOf("~/git", OTHER_ROOT_LABEL), roots.map { it.headerLabel })
        assertEquals(listOf("pocketshell"), roots[0].folders.map { it.label })
        assertEquals(listOf("pocketshell"), roots[1].folders.map { it.label })
        assertEquals(listOf("worktree-run"), roots[1].folders.single().rows.map { it.name })
    }

    @Test
    fun `folders in one root with the same basename grow a parent segment`() {
        val roots = groupSessionsIntoRoots(
            listOf(
                row("one", workspace = "/home/x/git/foo", activity = 1, created = 1),
                row("two", workspace = "/home/x/git/nested/foo", activity = 2, created = 2),
            ),
        )
        assertEquals(setOf("git/foo", "nested/foo"), roots.single().folders.map { it.label }.toSet())
    }

    @Test
    fun `a null workspace lands in the other bucket as an orphan, not a nameless folder`() {
        val roots = groupSessionsIntoRoots(
            listOf(
                row("homeless", workspace = null, activity = 50, created = 2),
                row("placed", workspace = "/home/x/git/a", activity = 10, created = 1),
            ),
        )
        assertEquals(listOf("~/git", OTHER_ROOT_LABEL), roots.map { it.headerLabel })
        val other = roots.single { it.other }
        assertEquals(OTHER_ROOT_LABEL, other.headerLabel)
        assertEquals(listOf("homeless"), other.folders.single().rows.map { it.name })
        assertTrue("an untracked session has no directory to name", other.folders.single().untracked)
        assertEquals("other is a bucket, pinned last", OTHER_ROOT_LABEL, roots.last().headerLabel)
    }

    @Test
    fun `a blank workspace joins the other bucket rather than rendering an empty header`() {
        val roots = groupSessionsIntoRoots(
            listOf(
                row("blank", workspace = "   ", activity = 5, created = 2),
                row("null", workspace = null, activity = 6, created = 1),
            ),
        )
        assertEquals(1, roots.size)
        assertEquals(OTHER_ROOT_LABEL, roots.single().headerLabel)
        assertTrue(roots.single().other)
        assertEquals(listOf("null", "blank"), leafNames(roots))
        assertTrue(roots.single().folders.all { it.untracked })
    }

    @Test
    fun `registered roots become the root list and unmatched paths go to other`() {
        val roots = groupSessionsIntoRoots(
            sessions = listOf(
                row("in-git", workspace = "/home/x/git/pocketshell", activity = 10, created = 1),
                row("in-tmp", workspace = "/home/x/tmp/scratch", activity = 20, created = 2),
                row("stray", workspace = "/var/log", activity = 30, created = 3),
                row("homeless", workspace = null, activity = 40, created = 4),
            ),
            home = "/home/x",
            registeredRoots = listOf("~/tmp", "~/git"),
        )
        assertEquals(listOf("~/tmp", "~/git", OTHER_ROOT_LABEL), roots.map { it.headerLabel })
        assertEquals(listOf("scratch"), roots[0].folders.map { it.label })
        assertEquals(listOf("pocketshell"), roots[1].folders.map { it.label })
        assertEquals(listOf("log"), roots[2].folders.filterNot { it.untracked }.map { it.label })
        assertEquals(listOf("homeless"), roots[2].folders.filter { it.untracked }.flatMap { it.rows.map { row -> row.name } })
        assertTrue(roots[0].configured)
        assertTrue(roots[1].configured)
        assertFalse(roots[2].configured)
    }

    @Test
    fun `an empty registered root still renders, ahead of other`() {
        val roots = groupSessionsIntoRoots(
            sessions = listOf(
                row("stray", workspace = "/var/log", activity = 1, created = 1),
            ),
            home = "/home/x",
            registeredRoots = listOf("~/git"),
        )
        assertEquals(listOf("~/git", OTHER_ROOT_LABEL), roots.map { it.headerLabel })
        assertEquals(0, roots[0].sessionCount)
        assertTrue(roots[0].configured)
        assertEquals(emptyList<SessionFolderNode>(), roots[0].folders)
    }

    @Test
    fun `tilde and absolute spellings of one directory fold into a single folder`() {
        val roots = groupSessionsIntoRoots(
            listOf(
                row("abs", workspace = "/home/x/git/pocketshell", activity = 1, created = 1),
                row("tilde", workspace = "~/git/pocketshell", activity = 2, created = 2),
            ),
            home = "/home/x",
        )
        assertEquals(1, roots.single().folders.size)
        assertEquals(
            listOf("abs", "tilde"),
            roots.single().folders.single().rows.map { it.name },
        )
    }

    @Test
    fun `folders under a root order by their oldest session, not by recency`() {
        val roots = groupSessionsIntoRoots(
            listOf(
                row("new-in-old-folder", workspace = "/home/x/git/alpha", activity = 999, created = 50),
                row("first-in-old-folder", workspace = "/home/x/git/alpha", activity = 1, created = 10),
                row("only-in-newer-folder", workspace = "/home/x/git/zeta", activity = 500, created = 20),
            ),
        )
        assertEquals(listOf("alpha", "zeta"), roots.single().folders.map { it.label })
    }

    @Test
    fun `an UNKNOWN-backend row is grouped and kept, never dropped`() {
        val roots = groupSessionsIntoRoots(
            listOf(
                row("tmux-one", workspace = "/home/x/git/w", activity = 10, backend = Backend.TMUX, created = 1),
                row(
                    "from-the-future",
                    workspace = "/home/x/git/w",
                    activity = 20,
                    backend = Backend.fromWire("warpdrive"),
                    created = 2,
                ),
                row(
                    "no-workspace-future",
                    workspace = null,
                    activity = 30,
                    backend = Backend.UNKNOWN,
                    created = 3,
                ),
            ),
        )
        assertEquals(3, roots.sumOf { it.sessionCount })
        val workspaceFolder = roots.single { it.headerLabel == "~/git" }.folders.single()
        assertEquals(listOf("tmux-one", "from-the-future"), workspaceFolder.rows.map { it.name })
        assertEquals(
            listOf("no-workspace-future"),
            roots.single { it.other }.folders.single().rows.map { it.name },
        )
        assertTrue(
            "an unrecognised manager must survive as UNKNOWN",
            workspaceFolder.rows.any { it.backend == Backend.UNKNOWN },
        )
    }

    @Test
    fun `every input row survives grouping, tmux and aplexer alike`() {
        val input = listOf(
            row("t1", workspace = "/home/x/git/a", activity = 1, backend = Backend.TMUX, created = 1),
            row("t2", workspace = null, activity = null, backend = Backend.TMUX, created = 2),
            row("a1", workspace = "/home/x/tmp/b", activity = 3, backend = Backend.APLEXER, created = 3),
            row("a2", workspace = "/home/x/git/a", activity = 4, backend = Backend.APLEXER, created = 4),
            row("u1", workspace = "/home/x/tmp/b", activity = null, backend = Backend.UNKNOWN, created = 5),
        )

        val out = groupSessionsIntoRoots(input)

        assertEquals(
            input.map { it.name }.toSet(),
            out.flatMap { root -> root.folders.flatMap { it.rows.map { row -> row.name } } }.toSet(),
        )
        assertEquals(input.size, out.sumOf { it.sessionCount })
    }

    @Test
    fun `an empty listing produces no roots`() {
        assertEquals(emptyList<SessionRoot>(), groupSessionsIntoRoots(emptyList()))
    }

    @Test
    fun `rows with the same created epoch are ordered by name, so the output is deterministic`() {
        val forward = groupSessionsIntoRoots(
            listOf(
                row("beta", workspace = "/home/x/git/w", activity = 42, created = 7),
                row("alpha", workspace = "/home/x/git/w", activity = 42, created = 7),
            ),
        )
        val reversed = groupSessionsIntoRoots(
            listOf(
                row("alpha", workspace = "/home/x/git/w", activity = 42, created = 7),
                row("beta", workspace = "/home/x/git/w", activity = 42, created = 7),
            ),
        )
        assertEquals(listOf("alpha", "beta"), forward.single().folders.single().rows.map { it.name })
        assertEquals(
            forward.single().folders.single().rows.map { it.name },
            reversed.single().folders.single().rows.map { it.name },
        )
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
     * sessions over `$HOME/git/…` plus `/tmp/aplexer-follow` from BOTH managers.
     * It runs through the real `SessionsJson` parser, so a change to either side
     * that only breaks on real data breaks here.
     */
    @Test
    fun `a real dev-box listing groups into git and other with both managers`() {
        val listing = SessionsJson.parseSessionsList(readFixture("sessions-list-devbox.json"))
            .getOrThrow()

        assertTrue("the capture must carry backend errors as an empty list", listing.errors.isEmpty())
        val roots = groupSessionsIntoRoots(listing.sessions)

        assertEquals(listing.sessions.size, roots.sumOf { it.sessionCount })
        assertEquals(
            listing.sessions.map { it.name }.toSet(),
            roots.flatMap { it.folders.flatMap { folder -> folder.rows.map { it.name } } }.toSet(),
        )

        val backends = listing.sessions.map { it.backend }.toSet()
        assertTrue("expected tmux rows", Backend.TMUX in backends)
        assertTrue("expected aplexer rows", Backend.APLEXER in backends)

        assertEquals(listOf("~/git", OTHER_ROOT_LABEL), roots.map { it.headerLabel })
        val git = roots.single { it.headerLabel == "~/git" }
        assertTrue("git must hold more than one folder", git.folders.size > 1)
        git.folders.forEach { folder ->
            assertFalse("a reported cwd must keep its folder row", folder.untracked)
            assertTrue(folder.rows.isNotEmpty())
        }
        val busiest = git.folders.maxBy { it.rows.size }
        assertTrue("expected a folder holding several sessions", busiest.rows.size >= 3)

        // Creation order: never activity. An activity bump is not in this
        // fixture, but the order of each folder's rows must match createdEpoch.
        roots.forEach { root ->
            root.folders.forEach { folder ->
                val names = folder.rows.map { it.name }
                val expected = folder.rows.sortedWith(
                    compareBy<SessionRow> { it.createdEpoch ?: 0L }.thenBy { it.name },
                ).map { it.name }
                assertEquals("rows in ${folder.label} must be oldest-created first", expected, names)
            }
        }
    }

    private fun leafNames(roots: List<SessionRoot>): List<String> =
        roots.flatMap { root -> root.folders.flatMap { it.rows.map { row -> row.name } } }

    private fun readFixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/$name")) {
            "missing test resource fixtures/$name"
        }.bufferedReader().use { it.readText() }

    private fun row(
        name: String,
        workspace: String?,
        activity: Long?,
        backend: Backend = Backend.TMUX,
        created: Long? = null,
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
        createdEpoch = created,
        activityEpoch = activity,
    )
}
