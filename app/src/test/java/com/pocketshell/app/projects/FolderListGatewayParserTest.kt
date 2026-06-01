package com.pocketshell.app.projects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the tab-delimited `tmux list-sessions` /
 * `list-panes -a` parsers in [SshFolderListGateway] — issue #171.
 *
 * The parsers are intentionally narrow (they only need to read four /
 * five trusted fields emitted with a literal `\t` separator), so the
 * test focus is correctness on representative wire shapes plus a
 * couple of edge cases (blank cwd, malformed row, multiple panes per
 * session with only the active one winning).
 */
class FolderListGatewayParserTest {

    @Test
    fun parseListSessionsRowsExtractsAllFiveFields() {
        val stdout =
            "claude-main::1700000000::1700001000::1::/home/alexey/git/pocketshell\n" +
                "build-shell::1700000100::1700000500::0::/home/alexey/git/pocketshell\n"
        val rows = SshFolderListGateway.parseListSessionsRows(stdout)
        assertEquals(2, rows.size)
        assertEquals("claude-main", rows[0].sessionName)
        assertEquals(1700001000L, rows[0].lastActivity)
        assertEquals(true, rows[0].attached)
        assertEquals("/home/alexey/git/pocketshell", rows[0].cwd)
        assertEquals("build-shell", rows[1].sessionName)
        assertEquals(false, rows[1].attached)
    }

    @Test
    fun parseListSessionsRowsPreservesHetznerCableWorldRawNames() {
        val stdout =
            "git-cable-world::1700000000::1700001000::0::/home/alexey/git/cable-world\n" +
                "git-cable-world-map::1700000100::1700002000::0::/home/alexey/git/cable-world\n"

        val rows = SshFolderListGateway.parseListSessionsRows(stdout)

        assertEquals(listOf("git-cable-world", "git-cable-world-map"), rows.map { it.sessionName })
        assertEquals(listOf("/home/alexey/git/cable-world", "/home/alexey/git/cable-world"), rows.map { it.cwd })
    }

    @Test
    fun parseListSessionsRowsHandlesBlankCwdAsNull() {
        // Some tmux setups leave session_path empty for sessions
        // created without `-c`; row must still parse.
        val rows = SshFolderListGateway.parseListSessionsRows("plain::100::200::0::\n")
        assertEquals(1, rows.size)
        assertNull(rows[0].cwd)
    }

    @Test
    fun parseListSessionsRowsAcceptsFourFieldRow() {
        // session_path is the last column — if the format string is
        // missing on an older tmux, we still get a 4-field row.
        val rows = SshFolderListGateway.parseListSessionsRows("plain::100::200::0\n")
        assertEquals(1, rows.size)
        assertEquals("plain", rows[0].sessionName)
        assertNull(rows[0].cwd)
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
        val stdout = """
            claude-main::0::/home/alexey/git/pocketshell/.subdir::/dev/pts/2::bash
            claude-main::1::/home/alexey/git/pocketshell::/dev/pts/3::node
            solo::1::/tmp::/dev/pts/4::sh

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
        val stdout = """
            git-cable-world::0::node::1::1::/home/alexey/git/cable-world::/dev/pts/2::node
            git-cable-world-map::0::claude::1::1::/home/alexey/git/cable-world::/dev/pts/3::claude
            multi-agent::0::shell::0::1::/srv/app::/dev/pts/4::bash
            multi-agent::1::claude::1::1::/srv/app::/dev/pts/5::claude
            multi-agent::1::claude::1::0::/srv/app/ignored::/dev/pts/6::vim
        """.trimIndent()

        val windows = SshFolderListGateway.parseSessionWindowRows(stdout)
        val activePanes = SshFolderListGateway.parseActivePaneRows(stdout)

        assertEquals(
            listOf("git-cable-world", "git-cable-world-map", "multi-agent", "multi-agent"),
            windows.map { it.sessionName },
        )
        assertEquals(listOf(0, 0, 0, 1), windows.map { it.index })
        assertEquals(listOf("node", "claude", "shell", "claude"), windows.map { it.name })
        assertEquals("/srv/app", activePanes["multi-agent"]?.cwd)
        assertEquals(1, activePanes["multi-agent"]?.windowIndex)
        assertEquals("claude", activePanes["multi-agent"]?.windowName)
    }

    @Test
    fun parseActivePaneRowsToleratesMissingOptionalFields() {
        // pane_tty + pane_current_command may be absent on older tmux —
        // the parser must still surface the active row with the cwd.
        val stdout = """
            sessA::1::/something
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
            sessA::0::/something::/dev/pts/0::bash
            sessB::1
        """.trimIndent()
        val map = SshFolderListGateway.parseActivePaneRows(stdout)
        // sessA is inactive (pane_active=0) → skipped.
        // sessB has only 2 fields → fails the size check → skipped.
        assertEquals(0, map.size)
    }
}
