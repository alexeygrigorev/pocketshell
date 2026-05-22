package com.pocketshell.app.jobs

import org.junit.Assert.assertEquals
import org.junit.Test

class TmuxctlJobsParserTest {

    private val parser = TmuxctlJobsParser()

    @Test
    fun parseList_readsTmuxctlTable() {
        val output = """
            ID  ENABLED  SESSION  EVERY  DELAY  SOURCE  NEXT RUN             DETAIL
            1   yes      codex    15m    200    inline  2026-04-03 00:15:00 check status and continue
            2   no       train    1h     500    file    2026-04-03 01:00:00 prompts/progress.txt
        """.trimIndent()

        val jobs = parser.parseList(output)

        assertEquals(2, jobs.size)
        assertEquals(
            RecurringJob(
                id = 1,
                enabled = true,
                sessionName = "codex",
                every = "15m",
                enterDelayMs = 200,
                source = RecurringJobSource.Inline,
                nextRun = "2026-04-03 00:15:00",
                detail = "check status and continue",
            ),
            jobs[0],
        )
        assertEquals(false, jobs[1].enabled)
        assertEquals(RecurringJobSource.File, jobs[1].source)
        assertEquals("prompts/progress.txt", jobs[1].detail)
    }

    @Test
    fun parseList_acceptsEmptyOutput() {
        assertEquals(emptyList<RecurringJob>(), parser.parseList(""))
    }

    @Test
    fun parseList_skipsMalformedRows() {
        val output = """
            ID  ENABLED  SESSION  EVERY  DELAY  SOURCE  NEXT RUN             DETAIL
            bad row
            7   yes      main     30m    200    inline  2026-04-03 00:30:00 ok
        """.trimIndent()

        assertEquals(listOf(7), parser.parseList(output).map { it.id })
    }

    @Test
    fun parseList_acceptsSessionNamesWiderThanPrintedColumn() {
        val output = """
            ID  ENABLED  SESSION  EVERY  DELAY  SOURCE  NEXT RUN             DETAIL
            8   yes      agent-main 15m   200    inline  2026-04-03 00:30:00 continue work
        """.trimIndent()

        val job = parser.parseList(output).single()

        assertEquals("agent-main", job.sessionName)
        assertEquals("15m", job.every)
        assertEquals("continue work", job.detail)
    }

    @Test
    fun parseList_keepsSessionNamesWithSpacesFromFixedWidthTable() {
        val output = """
            ID  ENABLED  SESSION       EVERY  DELAY  SOURCE  NEXT RUN             DETAIL
            9   yes      agent main    15m    200    inline  2026-04-03 00:30:00 continue work
        """.trimIndent()

        val job = parser.parseList(output).single()

        assertEquals("agent main", job.sessionName)
        assertEquals("15m", job.every)
        assertEquals("continue work", job.detail)
    }
}
