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
