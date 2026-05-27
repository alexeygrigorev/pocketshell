package com.pocketshell.app.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostTmuxSessionListParserTest {
    private val parser = HostTmuxSessionListParser()

    @Test
    fun parseTmuxctlListSkipsHeaderAndHints() {
        val output = """
            IDX  SESSION               CREATED
            1    codex                 2026-05-23 10:00:00
            2    claude-main           2026-05-23 09:30:00
            
            Join a session: tmuxctl <id> or tmuxctl <session>
            Create a new one: tmuxctl :<session>
        """.trimIndent()

        val rows = parser.parseTmuxctlList(output)

        assertEquals(listOf("codex", "claude-main"), rows.map { it.name })
        assertFalse(rows.first().attached)
        assertEquals(rows.first().createdAt, rows.first().lastActivity)
    }

    @Test
    fun parseTmuxctlListKeepsNamesWiderThanPrintedColumn() {
        val row = parser.parseTmuxctlListRow(
            "12   very-long-agent-session-name  2026-05-23 08:00:00",
        )

        assertEquals("very-long-agent-session-name", row?.name)
    }

    /**
     * Issue #200 regression: when a session name overflows tmuxctl's
     * printf padding for the SESSION column, the row separator collapses
     * to a single space before the trailing CREATED timestamp. The
     * previous regex required `\s{2,}` between the name and the
     * timestamp, so these rows were silently dropped — the dogfooder
     * with 11 sessions on the host saw only the short-named subset.
     * This test pins the parser against the verbatim Hetzner output so
     * we don't regress on the same printf-padding edge.
     */
    @Test
    fun parseTmuxctlListReturnsAllRowsWhenNamesOverflowSessionColumn() {
        val output = """
            IDX  SESSION               CREATED
            1    git-pocketshell-c     2026-05-24 11:32:14
            2    git-llm-zoomcamp      2026-05-27 06:26:00
            3    git-datamailer        2026-05-24 13:54:50
            4    git-ai-shipping-labs  2026-05-13 20:27:06
            5    git-ai-shipping-labs-workshops-raw-guard 2026-05-20 17:41:29
            6    git-au-tomator-lambda 2026-05-26 19:34:08
            7    git-dtc-operations-yaml 2026-05-21 05:18:21
            8    git-ai-shipping-labs-workshops-raw 2026-05-20 15:23:07
            9    git-ai-shipping-labs-web 2026-05-13 17:33:22
            10   git-telegram-writing-assistant 2026-05-12 12:10:03
            11   v2-md                 2026-04-13 14:06:12

            Join a session: tmuxctl <id> or tmuxctl <session>
            Create a new one: tmuxctl :<session>
            Use current folder: tmuxctl - or tmuxctl -name
            Help: tmuxctl --help
        """.trimIndent()

        val rows = parser.parseTmuxctlList(output)

        assertEquals(11, rows.size)
        assertEquals(
            listOf(
                "git-pocketshell-c",
                "git-llm-zoomcamp",
                "git-datamailer",
                "git-ai-shipping-labs",
                "git-ai-shipping-labs-workshops-raw-guard",
                "git-au-tomator-lambda",
                "git-dtc-operations-yaml",
                "git-ai-shipping-labs-workshops-raw",
                "git-ai-shipping-labs-web",
                "git-telegram-writing-assistant",
                "v2-md",
            ),
            rows.map { it.name },
        )
    }

    /**
     * Issue #200 belt-and-braces: even when every row uses the
     * single-space separator (an extreme of the same printf overflow
     * pattern), the parser must still return every row. This is a
     * minimal pinning case for a 10-session host so the bug never
     * comes back as "9 of 10" because of a different column-width quirk.
     */
    @Test
    fun parseTmuxctlListReturnsAllTenRowsWhenEverySeparatorIsSingleSpace() {
        val sessions = (1..10).map { "session-with-a-fairly-long-name-$it" }
        val output = buildString {
            appendLine("IDX  SESSION               CREATED")
            sessions.forEachIndexed { index, name ->
                appendLine("${index + 1}    $name 2026-05-27 ${"%02d".format(index + 1)}:00:00")
            }
            appendLine()
            appendLine("Join a session: tmuxctl <id> or tmuxctl <session>")
        }

        val rows = parser.parseTmuxctlList(output)

        assertEquals(10, rows.size)
        assertEquals(sessions, rows.map { it.name })
    }

    @Test
    fun parseTmuxListSessionsReadsDeterministicFields() {
        val rows = parser.parseTmuxListSessions(
            "codex\t1779520800\t1779521400\t1\nidle\t1779510000\t1779510500\t0\n",
        )

        assertEquals(listOf("codex", "idle"), rows.map { it.name })
        assertEquals(1779520800L, rows[0].createdAt)
        assertEquals(1779521400L, rows[0].lastActivity)
        assertTrue(rows[0].attached)
        assertFalse(rows[1].attached)
    }

    @Test
    fun malformedRowsReturnNull() {
        assertNull(parser.parseTmuxctlListRow("IDX  SESSION               CREATED"))
        assertNull(parser.parseTmuxctlListRow("not a row"))
        assertNull(parser.parseTmuxListSessionsRow(""))
        assertNull(parser.parseTmuxListSessionsRow("name\tcreated"))
        assertNull(parser.parseTmuxListSessionsRow("\t1\t2\t0"))
    }
}
