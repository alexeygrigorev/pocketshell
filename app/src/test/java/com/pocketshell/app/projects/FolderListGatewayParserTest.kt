package com.pocketshell.app.projects

import com.pocketshell.uikit.model.SessionAgentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the tab-delimited `tmux list-sessions` /
 * `list-panes -a` parsers in [SshFolderListGateway] — issue #171.
 *
 * The parsers are intentionally narrow (they only need to read trusted
 * fields emitted with a literal `\t` separator), so the test focus is
 * correctness on representative wire shapes plus a couple of edge cases
 * (blank cwd, malformed row, multiple panes per session with only the
 * active one winning).
 *
 * Issue #899 + epic #821 Workstream A + issue #858 + issue #1237:
 * `list-sessions` carries tmux `session_id`, then FOUR recorded fields between
 * `session_attached` and `session_path` — `@ps_agent_kind` (the agent kind
 * PocketShell recorded at launch), `@ps_agent_profile` (the non-default profile
 * label, e.g. a z.ai Claude), `@ps_agent_state` (idle / waiting_for_input /
 * working, written by the stop/idle hook bus), and `@ps_agent_state_updated_at`
 * (that write's epoch timestamp). Field order:
 * `name::session_id::created::activity::attached::@ps_agent_kind::@ps_agent_profile::@ps_agent_state::@ps_agent_state_updated_at::path`.
 * The client emits this 10-field format; a tmux server with any of the recorded
 * options unset expands that column to an EMPTY string (not a missing column),
 * so it parses as null / Unknown and the path stays last.
 */
class FolderListGatewayParserTest {

    @Test
    fun parseListSessionsRowsReadsTmuxIdentityAndKeepsPathLast() {
        // Field order: name, session_id, created, activity, attached, kind,
        // profile, state, state_updated_at, path (path last, absorbs `::`).
        val rows = SshFolderListGateway.parseListSessionsRows(
            "git-pocketshell::\$3::1700000000::1700001000::1::codex::Codex Max::" +
                "idle::1700000900::/tmp/a::b\n",
        )

        assertEquals(1, rows.size)
        assertEquals("git-pocketshell", rows[0].sessionName)
        assertEquals("\$3", rows[0].tmuxSessionId)
        assertEquals(1700000000L, rows[0].sessionCreated)
        assertEquals(1700001000L, rows[0].lastActivity)
        assertEquals(true, rows[0].attached)
        assertEquals(SessionAgentKind.Codex, rows[0].recordedKind)
        assertEquals("Codex Max", rows[0].recordedProfile)
        assertEquals("idle", rows[0].agentStateRaw)
        assertEquals(1700000900L, rows[0].agentStateUpdatedAt)
        assertEquals("/tmp/a::b", rows[0].cwd)
    }

    // --- Issue #1237: @ps_agent_state read-back (class coverage) ----------

    @Test
    fun parseListSessionsRowsReadsIdleWaitingAndWorkingStates() {
        val stdout =
            "s-idle::\$1::100::200::1::claude::::idle::195::/srv/a\n" +
                "s-wait::\$2::100::200::1::codex::::waiting_for_input::198::/srv/b\n" +
                "s-work::\$3::100::200::1::opencode::::working::199::/srv/c\n"
        val rows = SshFolderListGateway.parseListSessionsRows(stdout)
        assertEquals(3, rows.size)
        assertEquals("idle", rows[0].agentStateRaw)
        assertEquals(195L, rows[0].agentStateUpdatedAt)
        assertEquals("waiting_for_input", rows[1].agentStateRaw)
        assertEquals(198L, rows[1].agentStateUpdatedAt)
        assertEquals("working", rows[2].agentStateRaw)
        assertEquals(199L, rows[2].agentStateUpdatedAt)
    }

    @Test
    fun parseListSessionsRowsParsesIsoStateUpdatedAtTheHostActuallyWrites() {
        // Issue #1570: the host stop/idle hook records @ps_agent_state_updated_at
        // as datetime.now(timezone.utc).isoformat() — an ISO-8601 string, NOT the
        // epoch int every prior fixture used. parseRow must convert it to epoch
        // seconds so the staleness rule can run. 2023-11-14T22:13:20+00:00 ==
        // 1_700_000_000.
        val rows = SshFolderListGateway.parseListSessionsRows(
            "codex-work::\$7::1699990000::1700010000::1::codex::::" +
                "idle::2023-11-14T22:13:20+00:00::/srv/labs\n",
        )
        assertEquals(1, rows.size)
        assertEquals("idle", rows[0].agentStateRaw)
        assertEquals(1_700_000_000L, rows[0].agentStateUpdatedAt)
        assertEquals("/srv/labs", rows[0].cwd)
    }

    @Test
    fun parseListSessionsRowsBlankStateOptionIsNullNotAWrongState() {
        // A never-hooked / foreign session leaves @ps_agent_state (and its
        // timestamp) empty; tmux expands them to blank columns, so the row keeps
        // its kind/profile/path and reports NO recorded state (→ Unknown chip).
        // 10 fields with BOTH state columns blank (empty @ps_agent_state and
        // @ps_agent_state_updated_at): profile then `::::::` then path.
        val rows = SshFolderListGateway.parseListSessionsRows(
            "s-blank::\$4::100::200::1::claude::Claude (Z.AI)::::::/srv/app\n",
        )
        assertEquals(1, rows.size)
        assertEquals(SessionAgentKind.Claude, rows[0].recordedKind)
        assertEquals("Claude (Z.AI)", rows[0].recordedProfile)
        assertNull(rows[0].agentStateRaw)
        assertNull(rows[0].agentStateUpdatedAt)
        assertEquals("/srv/app", rows[0].cwd)
    }

    @Test
    fun parseListSessionsRowsKeepsPathWithEmbeddedSeparatorAheadOfStateColumns() {
        // The state columns are controlled tokens; the path stays the absorbing
        // last column, so a path containing the rare `::` literal still parses
        // with the state read correctly ahead of it.
        val rows = SshFolderListGateway.parseListSessionsRows(
            "odd::\$9::100::200::1::claude::::waiting_for_input::150::/srv/weird::dir\n",
        )
        assertEquals(1, rows.size)
        assertEquals("waiting_for_input", rows[0].agentStateRaw)
        assertEquals(150L, rows[0].agentStateUpdatedAt)
        assertEquals("/srv/weird::dir", rows[0].cwd)
    }

    @Test
    fun parseListSessionsRowsLegacyNonIdentityRowHasNoStateColumns() {
        // A legacy non-identity row (numeric col2, pre-#899 shape without the
        // session_id / state columns) parses with NO recorded state (Unknown),
        // never misreading a later column as the state.
        val rows = SshFolderListGateway.parseListSessionsRows(
            "legacy::100::200::1::claude::::/srv/app\n",
        )
        assertEquals(1, rows.size)
        assertEquals(SessionAgentKind.Claude, rows[0].recordedKind)
        assertNull(rows[0].agentStateRaw)
        assertNull(rows[0].agentStateUpdatedAt)
        assertEquals("/srv/app", rows[0].cwd)
    }

    @Test
    fun parseListSessionsRowsExtractsAllFields() {
        // Foreign sessions (no `@ps_agent_kind`/`@ps_agent_profile`) leave the
        // 5th + 6th fields blank.
        val stdout =
            "claude-main::1700000000::1700001000::1::::::/home/alexey/git/pocketshell\n" +
                "build-shell::1700000100::1700000500::0::::::/home/alexey/git/pocketshell\n"
        val rows = SshFolderListGateway.parseListSessionsRows(stdout)
        assertEquals(2, rows.size)
        assertEquals("claude-main", rows[0].sessionName)
        assertEquals(1700001000L, rows[0].lastActivity)
        assertEquals(true, rows[0].attached)
        assertEquals("/home/alexey/git/pocketshell", rows[0].cwd)
        assertNull(rows[0].recordedProfile)
        assertEquals("build-shell", rows[1].sessionName)
        assertEquals(false, rows[1].attached)
    }

    @Test
    fun parseListSessionsRowsReadsRecordedAgentKind() {
        // Epic #821: a session we launched carries its `@ps_agent_kind` in
        // the 5th field; parseRow surfaces it as the authoritative kind. The
        // empty 6th field (no profile) parses to a null recordedProfile.
        val stdout =
            "git-pocketshell::1700000000::1700001000::1::codex::::/home/alexey/git/pocketshell\n" +
                "git-app::1700000100::1700000500::0::claude::::/home/alexey/git/app\n" +
                "git-web::1700000200::1700000600::0::opencode::::/home/alexey/git/web\n"
        val rows = SshFolderListGateway.parseListSessionsRows(stdout)
        assertEquals(3, rows.size)
        assertEquals(SessionAgentKind.Codex, rows[0].recordedKind)
        assertEquals(SessionAgentKind.Codex, rows[0].agentKind)
        assertEquals("/home/alexey/git/pocketshell", rows[0].cwd)
        assertNull(rows[0].recordedProfile)
        assertEquals(SessionAgentKind.Claude, rows[1].recordedKind)
        assertEquals(SessionAgentKind.Claude, rows[1].agentKind)
        assertEquals(SessionAgentKind.OpenCode, rows[2].recordedKind)
        assertEquals(SessionAgentKind.OpenCode, rows[2].agentKind)
    }

    // --- Issue #858: @ps_agent_profile read-back (class coverage) ----------

    @Test
    fun parseListSessionsRowsReadsRecordedProfile_zaiClaude() {
        // The z.ai case the maintainer reported: a Claude launched via the
        // named profile carries `@ps_agent_profile=Claude (Z.AI)`; parseRow
        // surfaces it on recordedProfile so the tree can distinguish it.
        val rows = SshFolderListGateway.parseListSessionsRows(
            "zai::100::200::1::claude::Claude (Z.AI)::/home/alexey/git/app\n",
        )
        assertEquals(1, rows.size)
        assertEquals(SessionAgentKind.Claude, rows[0].recordedKind)
        assertEquals("Claude (Z.AI)", rows[0].recordedProfile)
        assertEquals("/home/alexey/git/app", rows[0].cwd)
    }

    @Test
    fun parseListSessionsRowsDefaultClaudeHasNoProfile() {
        // A default Claude (no profile) records the kind but blank profile →
        // null recordedProfile, so no spurious chip.
        val rows = SshFolderListGateway.parseListSessionsRows(
            "default-claude::100::200::1::claude::::/home/alexey/git/app\n",
        )
        assertEquals(1, rows.size)
        assertEquals(SessionAgentKind.Claude, rows[0].recordedKind)
        assertNull(rows[0].recordedProfile)
    }

    @Test
    fun parseListSessionsRowsCodexCarriesItsProfile() {
        // Class coverage: a Codex launched via a named profile carries its
        // own profile label; a default Codex carries none.
        val rows = SshFolderListGateway.parseListSessionsRows(
            "codex-work::100::200::1::codex::Codex (Work)::/srv/work\n" +
                "codex-default::100::200::0::codex::::/srv/def\n",
        )
        assertEquals(2, rows.size)
        assertEquals(SessionAgentKind.Codex, rows[0].recordedKind)
        assertEquals("Codex (Work)", rows[0].recordedProfile)
        assertEquals(SessionAgentKind.Codex, rows[1].recordedKind)
        assertNull(rows[1].recordedProfile)
    }

    @Test
    fun parseListSessionsRowsLegacyMissingProfileColumnIsNullNoCrash() {
        // A legacy / pre-#858 row that predates the @ps_agent_profile column
        // (only the 6-field kind shape) must NOT crash, must NOT misread the
        // path as a profile, and must KEEP its cwd: a 6-part row has no profile
        // column, so its 6th field is still the session_path. (On a real legacy
        // host the live client emits the 7-field format with an empty profile
        // column; this bare-6-field shape only arises from a stale cache, and
        // parseRow handles it without dropping the cwd.)
        val rows = SshFolderListGateway.parseListSessionsRows(
            "legacy::100::200::1::claude::/srv/app\n",
        )
        assertEquals(1, rows.size)
        assertEquals("legacy", rows[0].sessionName)
        assertEquals(SessionAgentKind.Claude, rows[0].recordedKind)
        assertNull("a 6-field legacy row has no profile column", rows[0].recordedProfile)
        assertEquals("a 6-field legacy row keeps its path as the cwd", "/srv/app", rows[0].cwd)
    }

    @Test
    fun parseListSessionsRowsBlankKindIsForeignNotRecorded() {
        // A blank `@ps_agent_kind` means we did not launch this session
        // (foreign). recordedKind is null and the row keeps the Shell
        // default so the detection probe (still running in A1) can fill it
        // without being clobbered.
        val rows = SshFolderListGateway.parseListSessionsRows(
            "foreign::100::200::1::::::/srv/app\n",
        )
        assertEquals(1, rows.size)
        assertNull(rows[0].recordedKind)
        assertNull(rows[0].recordedProfile)
        assertEquals(SessionAgentKind.Shell, rows[0].agentKind)
        assertEquals("/srv/app", rows[0].cwd)
    }

    @Test
    fun parseListSessionsRowsUnknownKindFallsBackToShell() {
        // An unrecognised option value must NOT mislabel the session — it
        // falls back to Shell with no recorded kind.
        val rows = SshFolderListGateway.parseListSessionsRows(
            "weird::100::200::1::gemini::::/srv/app\n",
        )
        assertEquals(1, rows.size)
        assertNull(rows[0].recordedKind)
        assertEquals(SessionAgentKind.Shell, rows[0].agentKind)
    }

    @Test
    fun parseListSessionsRowsReadsManuallyRecordedShellAsRecordedKind() {
        // Epic #821 Slice 1: a user can manually classify a foreign session as
        // a plain shell — ManualKindWriter records `@ps_agent_kind shell`. That
        // verdict reads back as a RECORDED Shell (not null → default-Shell), so
        // the change-kind UI knows the session HAS a recorded kind and never
        // re-prompts it as Unknown.
        val rows = SshFolderListGateway.parseListSessionsRows(
            "classified::100::200::1::shell::::/srv/app\n",
        )
        assertEquals(1, rows.size)
        assertEquals(SessionAgentKind.Shell, rows[0].recordedKind)
        assertEquals(SessionAgentKind.Shell, rows[0].agentKind)
    }

    @Test
    fun parseListSessionsRowsKeepsPathWithEmbeddedSeparator() {
        // session_path is the absorbing last column; a path containing the
        // rare `::` literal still parses, and the recorded kind + profile
        // ahead of it are read correctly.
        val rows = SshFolderListGateway.parseListSessionsRows(
            "odd::100::200::1::claude::Claude (Z.AI)::/srv/weird::dir\n",
        )
        assertEquals(1, rows.size)
        assertEquals(SessionAgentKind.Claude, rows[0].recordedKind)
        assertEquals("Claude (Z.AI)", rows[0].recordedProfile)
        assertEquals("/srv/weird::dir", rows[0].cwd)
    }

    @Test
    fun parseListSessionsRowsPreservesHetznerCableWorldRawNames() {
        val stdout =
            "git-cable-world::1700000000::1700001000::0::::::/home/alexey/git/cable-world\n" +
                "git-cable-world-map::1700000100::1700002000::0::::::/home/alexey/git/cable-world\n"

        val rows = SshFolderListGateway.parseListSessionsRows(stdout)

        assertEquals(listOf("git-cable-world", "git-cable-world-map"), rows.map { it.sessionName })
        assertEquals(listOf("/home/alexey/git/cable-world", "/home/alexey/git/cable-world"), rows.map { it.cwd })
    }

    @Test
    fun parseListSessionsRowsHandlesBlankCwdAsNull() {
        // Some tmux setups leave session_path empty for sessions
        // created without `-c`; row must still parse.
        val rows = SshFolderListGateway.parseListSessionsRows("plain::100::200::0::::::\n")
        assertEquals(1, rows.size)
        assertNull(rows[0].cwd)
        assertNull(rows[0].recordedKind)
        assertNull(rows[0].recordedProfile)
    }

    @Test
    fun parseListSessionsRowsAcceptsFourFieldRow() {
        // session_path is the last column — if the format string is
        // missing on an older tmux, we still get a 4-field row.
        val rows = SshFolderListGateway.parseListSessionsRows("plain::100::200::0\n")
        assertEquals(1, rows.size)
        assertEquals("plain", rows[0].sessionName)
        assertNull(rows[0].cwd)
        assertNull(rows[0].recordedKind)
    }

    @Test
    fun parseListSessionsRowsRejectsShortRow() {
        val rows = SshFolderListGateway.parseListSessionsRows("plain::100\n")
        assertEquals(0, rows.size)
    }

    @Test
    fun parsePocketshellSessionsRowsKeepsDaemonSessionsReachableWithoutCwd() {
        val stdout = """
            IDX  SESSION               CREATED
              1  claude-main           2026-05-30 00:20:01
              2  codex                 2026-05-30 00:19:58
            Create a new one: pocketshell sessions new
        """.trimIndent()

        val rows = SshFolderListGateway.parsePocketshellSessionsRows(stdout)

        assertEquals(listOf("claude-main", "codex"), rows.map { it.sessionName })
        assertEquals(listOf(null, null), rows.map { it.cwd })
        assertEquals(listOf(false, false), rows.map { it.attached })
        assertEquals(listOf(true, true), rows.map { it.lastActivity != null })
    }

    @Test
    fun parsePocketshellProjectHistoryExtractsRecentUniqueCwds() {
        val stdout = """
            [
              {"ts":"2026-05-30T10:00:00Z","kind":"engine_event","cwd":"/home/alexey/git/old"},
              {"ts":"2026-05-30T10:05:00Z","kind":"agent_action","detail":{"cwd":"/home/alexey/git/pocketshell/app"}},
              {"ts":"2026-05-30T10:06:00Z","kind":"engine_event","cwd":"/home/alexey/git/old/"},
              {"ts":"2026-05-30T10:07:00Z","kind":"engine_event","cwd":""}
            ]
        """.trimIndent()

        assertEquals(
            listOf("/home/alexey/git/old", "/home/alexey/git/pocketshell/app"),
            SshFolderListGateway.parsePocketshellProjectHistory(stdout),
        )
    }

    @Test
    fun parsePocketshellProjectHistoryToleratesMissingLogsJson() {
        assertEquals(emptyList<String>(), SshFolderListGateway.parsePocketshellProjectHistory("not-json"))
        assertEquals(emptyList<String>(), SshFolderListGateway.parsePocketshellProjectHistory("{}"))
    }

    @Test
    fun parseActivePaneRowsReturnsOnlyActivePaneForEachSession() {
        // Two panes for `claude-main`, only the active one (pane_active=1)
        // wins. A second session `solo` has a single active pane.
        // 8-field shape: session::window_index::window_name::window_active::
        // pane_active::pane_current_path::pane_tty::pane_current_command
        val stdout = """
            claude-main::0::bash::1::0::/home/alexey/git/pocketshell/.subdir::/dev/pts/2::bash
            claude-main::0::bash::1::1::/home/alexey/git/pocketshell::/dev/pts/3::node
            solo::0::sh::1::1::/tmp::/dev/pts/4::sh

        """.trimIndent()
        val map = SshFolderListGateway.parseActivePaneRows(stdout)
        assertEquals(2, map.size)
        assertEquals("/home/alexey/git/pocketshell", map["claude-main"]?.cwd)
        assertEquals("/dev/pts/3", map["claude-main"]?.tty)
        assertEquals("node", map["claude-main"]?.command)
        assertEquals("/tmp", map["solo"]?.cwd)
    }

    @Test
    fun parseSessionWindowRowsKeepsWindowIdentityAndActiveWindowCwd() {
        // 9-field shape (#653): the trailing column is `#{window_id}` (`@N`).
        val stdout = """
            git-cable-world::0::node::1::1::/home/alexey/git/cable-world::/dev/pts/2::node::@0
            git-cable-world-map::0::claude::1::1::/home/alexey/git/cable-world::/dev/pts/3::claude::@1
            multi-agent::0::shell::0::1::/srv/app::/dev/pts/4::bash::@2
            multi-agent::1::claude::1::1::/srv/app::/dev/pts/5::claude::@3
            multi-agent::1::claude::1::0::/srv/app/ignored::/dev/pts/6::vim::@3
        """.trimIndent()

        val windows = SshFolderListGateway.parseSessionWindowRows(stdout)
        val activePanes = SshFolderListGateway.parseActivePaneRows(stdout)

        assertEquals(
            listOf("git-cable-world", "git-cable-world-map", "multi-agent", "multi-agent"),
            windows.map { it.sessionName },
        )
        assertEquals(listOf(0, 0, 0, 1), windows.map { it.index })
        assertEquals(listOf("node", "claude", "shell", "claude"), windows.map { it.name })
        // #653: the stable tmux window id (`@N`) is threaded onto each row.
        assertEquals(listOf("@0", "@1", "@2", "@3"), windows.map { it.windowId })
        assertEquals("/srv/app", activePanes["multi-agent"]?.cwd)
        assertEquals(1, activePanes["multi-agent"]?.windowIndex)
        assertEquals("claude", activePanes["multi-agent"]?.windowName)
    }

    @Test
    fun parseSessionWindowRowsCapturesPanePidForForeignKindGuess() {
        // Epic #821 A2: `#{pane_pid}` is the trailing 10th column, threaded onto
        // the row for the foreign-session one-shot daemon kind guess. A row that
        // predates the column (9-field, #653 shape) yields a null pid.
        val stdout = """
            with-pid::0::node::1::1::/work::/dev/pts/2::node::@0::4242
            no-pid::0::bash::1::1::/srv::/dev/pts/9::bash::@1
        """.trimIndent()
        val windows = SshFolderListGateway.parseSessionWindowRows(stdout)
        assertEquals(2, windows.size)
        assertEquals(4242L, windows[0].panePid)
        assertEquals("@0", windows[0].windowId)
        assertNull("a row without the pane_pid column must yield a null pid", windows[1].panePid)
        assertEquals("@1", windows[1].windowId)
    }

    @Test
    fun parseSessionWindowRowsTreatsMissingWindowIdColumnAsNull() {
        // Pre-#653 8-field shape (e.g. a row from an older cache or a tmux that
        // did not emit the id column): the parser still yields the row, with a
        // null windowId, instead of dropping it.
        val stdout = """
            legacy::0::bash::1::1::/srv::/dev/pts/9::bash
        """.trimIndent()
        val windows = SshFolderListGateway.parseSessionWindowRows(stdout)
        assertEquals(1, windows.size)
        assertEquals("legacy", windows[0].sessionName)
        assertNull(windows[0].windowId)
    }

    @Test
    fun parseActivePaneRowsToleratesEmptyOptionalFields() {
        // pane_tty + pane_current_command may render empty — the parser
        // must still surface the active row with the cwd and null the
        // empty optional columns.
        val stdout = """
            sessA::0::win::1::1::/something::::
        """.trimIndent()
        val map = SshFolderListGateway.parseActivePaneRows(stdout)
        assertEquals(1, map.size)
        assertEquals("/something", map["sessA"]?.cwd)
        assertNull(map["sessA"]?.tty)
        assertNull(map["sessA"]?.command)
    }

    @Test
    fun parseActivePaneRowsIgnoresInactivePanes() {
        val stdout = """
            sessA::0::win::1::0::/something::/dev/pts/0::bash
            sessB::1
        """.trimIndent()
        val map = SshFolderListGateway.parseActivePaneRows(stdout)
        // sessA's only pane is inactive (pane_active=0) → skipped.
        // sessB has only 2 fields → fails the 8-field size check → skipped.
        assertEquals(0, map.size)
    }

    // --- #716: default-optimistic agent fallback -------------------------

    @Test
    fun undetectedShellCommandResolvesToAffirmativeShell() {
        // A completed probe whose pane foreground command is an interactive
        // shell with no agent match is an AFFIRMATIVE shell verdict.
        for (shell in listOf("bash", "zsh", "fish", "sh", "/usr/bin/zsh", "-bash", "FISH")) {
            assertEquals(
                "interactive shell '$shell' → Shell",
                SessionAgentKind.Shell,
                SshFolderListGateway.resolveUndetectedKind(shell),
            )
            assertTrue("'$shell' is an affirmative shell", SshFolderListGateway.isAffirmativeShellCommand(shell))
        }
    }

    @Test
    fun undetectedNonShellCommandResolvesToProbingNotShell() {
        // No agent match + a NON-interactive-shell command (e.g. the probe
        // saw `node`/`python`, or the command is unknown) is presumed-agent.
        for (cmd in listOf("node", "python", "vim", "git", "claude")) {
            assertEquals(
                "non-shell '$cmd' → Probing (never default-Shell on absence)",
                SessionAgentKind.Probing,
                SshFolderListGateway.resolveUndetectedKind(cmd),
            )
            assertFalse("'$cmd' is not an affirmative shell", SshFolderListGateway.isAffirmativeShellCommand(cmd))
        }
    }

    @Test
    fun undetectedNullOrBlankCommandResolvesToProbingNotShell() {
        // The probe could not read a command yet → presumed-agent, NOT Shell.
        // This is the #716 #1 risk: never downgrade a real agent on absence.
        assertEquals(SessionAgentKind.Probing, SshFolderListGateway.resolveUndetectedKind(null))
        assertEquals(SessionAgentKind.Probing, SshFolderListGateway.resolveUndetectedKind(""))
        assertEquals(SessionAgentKind.Probing, SshFolderListGateway.resolveUndetectedKind("   "))
        assertFalse(SshFolderListGateway.isAffirmativeShellCommand(null))
        assertFalse(SshFolderListGateway.isAffirmativeShellCommand(""))
    }
}
