package com.pocketshell.app.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AgentConversationRepositoryDetectionCommandTest {
    @Test
    fun detectionCommandTolerantlyExtractsCwdFromPrettyPrintedCodexRollout() {
        // #1227 site 1 (G10 non-happy fixture, REAL shell): the Codex candidate
        // enumeration extracts each rollout's cwd from its session_meta record.
        // If Codex PRETTY-PRINTS the rollout JSONL (session_meta split across
        // lines, cwd on its own line), the OLD extraction (grep the session_meta
        // line, sed the cwd off the SAME line) yields nothing -> the rollout is
        // dropped -> zero candidates -> Conversation never binds. Run the REAL
        // detectionCommand shell against a drifted fixture and prove a `codex|`
        // row is emitted (RED on base: no codex row).
        val prettyRollout = """
            {
              "type": "session_meta",
              "payload": {
                "id": "rollout-pretty",
                "cwd": "/workspace/proj"
              }
            }
        """.trimIndent()

        val row = runCodexDetectionRow(rolloutContent = prettyRollout, cwd = "/workspace/proj")

        assertTrue(
            "#1227: a pretty-printed Codex rollout must still emit a codex candidate " +
                "row (tolerant cwd extraction); got: $row",
            row != null && row.startsWith("codex|") && row.endsWith("rollout-pretty.jsonl"),
        )
    }

    @Test
    fun detectionCommandTolerantlyExtractsCwdWhenFieldMovedOutsidePayload() {
        // #1227 site 1 class coverage (G2): a rollout whose `cwd` field moved
        // OUTSIDE `payload` (a plausible schema drift) also broke the old
        // `"payload":{...,"cwd"...}`-on-one-line extraction. The tolerant scan
        // must still find it (RED on base: no codex row).
        val movedFieldRollout =
            """{"type":"session_meta","cwd":"/workspace/proj","payload":{"id":"rollout-moved"}}"""

        val row = runCodexDetectionRow(rolloutContent = movedFieldRollout, cwd = "/workspace/proj")

        assertTrue(
            "#1227: a rollout with cwd moved outside payload must still emit a codex " +
                "candidate row; got: $row",
            row != null && row.startsWith("codex|") && row.endsWith("rollout-moved.jsonl"),
        )
    }

    @Test
    fun detectionCommandUsesA120MinuteFreshnessWindowForClaude() {
        val command = AgentConversationRepository().detectionCommand("/workspace/pocketshell")

        // The Claude `find` walks the cwd-scoped projects dir. It must use
        // the 120-minute window, NOT the old 5-minute one that excluded
        // idle/slow-flush Claude sessions (#820).
        val claudeFindLine = command.lineSequence()
            .first { it.contains("\$claude_dir") && it.contains("find") }
        assertTrue(
            "Claude find must use -mmin -120 (idle-session freshness, #820); " +
                "got: $claudeFindLine",
            claudeFindLine.contains("-mmin -120"),
        )
        assertFalse(
            "Claude find must NOT use the old tight -mmin -5 window that hard-failed " +
                "idle Claude conversations (#820); got: $claudeFindLine",
            claudeFindLine.contains("-mmin -5 "),
        )
    }

    @Test
    fun detectionCommandDoesNotMatchBlankOpenCodeCwdColumns() {
        val query = openCodeSqliteQuery(
            AgentConversationRepository().detectionCommand("/home/alexey/git/pocketshell"),
        )

        assertTrue(query.contains("p.worktree IS NOT NULL AND p.worktree != ''"))
        assertTrue(query.contains("s.directory IS NOT NULL AND s.directory != ''"))
        assertFalse(query.contains("LIKE COALESCE(p.worktree, '') || '/%'"))
        assertFalse(query.contains("LIKE COALESCE(s.directory, '') || '/%'"))
    }

    @Test
    fun detectionCommandTolerantlyExtractsCodexCwdAndStillGatesOnCwdMatch() {
        // Issue #1227 (site 1, version-skew): the Codex cwd extraction is now
        // tolerant of drift - it scans the rollout's metadata region (first 50
        // lines) for any `"cwd":"..."` instead of requiring
        // `"payload":{...,"cwd":"..."}` all on the FIRST session_meta line - so a
        // pretty-printed or moved cwd field no longer silently drops the rollout.
        // The cwd-equality gate is preserved so a different-cwd rollout is still
        // filtered out (the fd fallback binds the true-cwd-unknown case).
        val command = AgentConversationRepository().detectionCommand("/home/alexey/git/pocketshell")

        assertTrue("must still extract the cwd field", command.contains("\"cwd\""))
        assertTrue(
            "must scan the metadata region tolerantly, not just the first session_meta line",
            command.contains("head -n 50"),
        )
        assertFalse(
            "must not re-couple extraction to a payload-scoped single-line match (#1227)",
            command.contains("\"payload\"[[:space:]]*:[[:space:]]*{[^}]*\"cwd\""),
        )
        assertTrue(
            "must still gate on cwd equality so a foreign-cwd rollout is filtered out",
            command.contains("[ \"${'$'}codex_cwd\" = \"${'$'}cwd\" ] || continue"),
        )
    }

    @Test
    fun detectionCommandUsesLiteralOpenCodeCwdPrefixChecks() {
        val query = openCodeSqliteQuery(
            AgentConversationRepository().detectionCommand("/home/alexey/git/pocket_shell%"),
        )
        val sqlCwd = "'/home/alexey/git/pocket_shell%'"

        assertFalse(query.contains(" LIKE "))
        assertTrue(query.contains("$sqlCwd = p.worktree"))
        assertTrue(
            query.contains(
                "substr($sqlCwd, 1, length(p.worktree) + 1) = " +
                    "p.worktree || '/'",
            ),
        )
        assertTrue(query.contains("$sqlCwd = s.directory"))
        assertTrue(
            query.contains(
                "substr($sqlCwd, 1, length(s.directory) + 1) = " +
                    "s.directory || '/'",
            ),
        )
    }

    @Test
    fun detectionCommandShellQuotesOpenCodeSqliteQueryForDollarInCwd() {
        assertOpenCodeSqliteQueryIsShellSingleQuoted("/tmp/pocketshell-${'$'}USER")
    }

    @Test
    fun detectionCommandShellQuotesOpenCodeSqliteQueryForBacktickInCwd() {
        assertOpenCodeSqliteQueryIsShellSingleQuoted("/tmp/pocketshell-`uname`")
    }

    @Test
    fun detectionCommandShellQuotesOpenCodeSqliteQueryForDoubleQuoteInCwd() {
        assertOpenCodeSqliteQueryIsShellSingleQuoted("/tmp/pocketshell-\"quoted\"")
    }

    private fun assertOpenCodeSqliteQueryIsShellSingleQuoted(cwd: String) {
        val sqliteLine = openCodeSqliteLine(AgentConversationRepository().detectionCommand(cwd))
        val normalizedCwd = cwd.trim().trimEnd('/').ifBlank { "/" }

        assertFalse(sqliteLine.contains("\"SELECT "))
        assertTrue(sqliteLine.contains("sqlite3 -readonly -separator '|' \"${'$'}opencode_db\" 'SELECT "))
        assertTrue(sqliteLine.contains(shellEscapedSqlLiteral(normalizedCwd)))
    }

    private fun openCodeSqliteLine(command: String): String =
        command.lines().single { it.trimStart().startsWith("sqlite3 -readonly -separator") }.trim()

    private fun openCodeSqliteQuery(command: String): String {
        val sqliteLine = openCodeSqliteLine(command)
        val prefix = "sqlite3 -readonly -separator '|' \"${'$'}opencode_db\" "
        val suffix = " 2>/dev/null | while IFS='|' read -r sid updated _worktree _directory; do"
        val quotedQuery = sqliteLine.removePrefix(prefix).removeSuffix(suffix)
        return quotedQuery.removeSurrounding("'").replace("'\\''", "'")
    }

    private fun shellEscapedSqlLiteral(value: String): String {
        val sqlLiteral = "'" + value.replace("'", "''") + "'"
        return sqlLiteral.replace("'", "'\\''")
    }

    /**
     * Issue #1227 (site 1, G10 real-shell fixture): run the REAL
     * [AgentConversationRepository.detectionCommand] shell script against a
     * temporary `$HOME` that contains a single Codex rollout whose content is
     * [rolloutContent], and return the first `codex|...` enumeration row the
     * shell emits (or `null` if none). This exercises the actual grep/sed/head
     * cwd extraction - the exact code path that silently drops a rollout on a
     * version-skew drift - rather than a mocked enumeration. The rollout file is
     * named after its `"id"` so the caller can assert on the emitted path.
     */
    private fun runCodexDetectionRow(rolloutContent: String, cwd: String): String? {
        val id = Regex(""""id"\s*:\s*"([^"]+)"""").find(rolloutContent)?.groupValues?.get(1)
            ?: "rollout-fixture"
        val home = File.createTempFile("ps-codex-home", "").let { tmp ->
            tmp.delete()
            tmp.mkdirs()
            tmp
        }
        try {
            val rolloutDir = File(home, ".codex/sessions/2026/07/03")
            rolloutDir.mkdirs()
            File(rolloutDir, "$id.jsonl").writeText(rolloutContent)

            val script = AgentConversationRepository().detectionCommand(cwd)
            val process = ProcessBuilder("sh", "-c", script)
                .apply { environment()["HOME"] = home.absolutePath }
                .redirectErrorStream(false)
                .start()
            val stdout = process.inputStream.bufferedReader().readText()
            process.waitFor()
            return stdout.lineSequence().firstOrNull { it.startsWith("codex|") }
        } finally {
            home.deleteRecursively()
        }
    }
}
