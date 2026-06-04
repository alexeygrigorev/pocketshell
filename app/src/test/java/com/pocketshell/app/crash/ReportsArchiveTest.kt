package com.pocketshell.app.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.zip.ZipFile

class ReportsArchiveTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun packIntoZipsAllReportFiles() {
        val dir = temporaryFolder.newFolder("reports")
        val a = File(dir, "20260601-101530-000_crash_report.txt").apply { writeText("crash A") }
        val b = File(dir, "20260601-111530-000_crash_report.txt").apply { writeText("crash B") }
        val c = File(dir, "diagnostics.txt").apply { writeText("diag C") }
        val dest = File(temporaryFolder.newFolder("out"), "bundle.zip")

        ReportsArchive.packInto(listOf(a, b, c), dest)

        assertTrue(dest.exists())
        val entries = ZipFile(dest).use { zip ->
            zip.entries().toList().associate { entry ->
                entry.name to zip.getInputStream(entry).readBytes().toString(Charsets.UTF_8)
            }
        }
        assertEquals(3, entries.size)
        assertEquals("crash A", entries["20260601-101530-000_crash_report.txt"])
        assertEquals("crash B", entries["20260601-111530-000_crash_report.txt"])
        assertEquals("diag C", entries["diagnostics.txt"])
    }

    @Test
    fun packIntoDeduplicatesCollidingEntryNames() {
        val dirOne = temporaryFolder.newFolder("one")
        val dirTwo = temporaryFolder.newFolder("two")
        val a = File(dirOne, "report.txt").apply { writeText("first") }
        val b = File(dirTwo, "report.txt").apply { writeText("second") }
        val dest = File(temporaryFolder.newFolder("out2"), "bundle.zip")

        ReportsArchive.packInto(listOf(a, b), dest)

        val names = ZipFile(dest).use { zip -> zip.entries().toList().map { it.name } }
        assertEquals(setOf("report.txt", "report-1.txt"), names.toSet())
    }

    @Test
    fun archiveFileNameIsDeterministicForFixedClock() {
        val clock = Clock.fixed(Instant.parse("2026-06-04T12:34:56Z"), ZoneOffset.UTC)

        val name = ReportsArchive.archiveFileName("Google Pixel 7", clock)

        assertEquals("pocketshell-reports-Google-Pixel-7-20260604-123456.zip", name)
    }

    @Test
    fun archiveFileNameFallsBackWhenLabelBlank() {
        val clock = Clock.fixed(Instant.parse("2026-06-04T00:00:00Z"), ZoneOffset.UTC)

        val name = ReportsArchive.archiveFileName("   ", clock)

        assertEquals("pocketshell-reports-device-20260604-000000.zip", name)
    }
}
