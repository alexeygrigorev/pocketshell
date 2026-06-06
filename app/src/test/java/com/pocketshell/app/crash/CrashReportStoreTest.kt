package com.pocketshell.app.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class CrashReportStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun saveWritesFormattedCrashReport() {
        val store = storeAt("2026-05-22T10:15:30Z")

        val report = store.save(
            throwable = IllegalArgumentException("bad host"),
            threadName = "worker",
            metadata = metadata,
            context = context,
        )

        assertEquals("20260522-101530-000", report.id)
        assertEquals("IllegalArgumentException: bad host", report.summary)
        assertEquals(
            "Tmux session · host=devbox · session=agent-main · cwd=/home/alexey/git/pocketshell",
            report.contextSummary,
        )
        assertEquals("0.1.0", report.appVersion)
        assertTrue(report.topFrame?.contains("CrashReportStoreTest") == true)
        assertTrue(report.file.exists())
        assertTrue(store.read(report).contains("Thread: worker"))
        assertTrue(store.read(report).contains("Screen: Tmux session"))
        assertTrue(store.read(report).contains("Session: agent-main"))
        assertTrue(store.read(report).contains("Top frame:"))
        assertTrue(store.read(report).contains("IllegalArgumentException: bad host"))
    }

    @Test
    fun saveUsesUniqueFilenameWhenReportsShareTimestamp() {
        val store = storeAt("2026-05-22T10:15:30Z")

        val first = store.save(RuntimeException("first"), "main", metadata)
        val second = store.save(RuntimeException("second"), "main", metadata)

        assertEquals("20260522-101530-000", first.id)
        assertEquals("20260522-101530-000-1", second.id)
        assertEquals(2, store.list().size)
    }

    @Test
    fun listReturnsNewestReportsFirst() {
        val directory = temporaryFolder.newFolder()
        CrashReportStore(
            directory = directory,
            clock = Clock.fixed(Instant.parse("2026-05-22T10:15:30Z"), ZoneOffset.UTC),
        ).save(RuntimeException("older"), "main", metadata)
        CrashReportStore(
            directory = directory,
            clock = Clock.fixed(Instant.parse("2026-05-22T11:15:30Z"), ZoneOffset.UTC),
        ).save(RuntimeException("newer"), "main", metadata)

        val reports = CrashReportStore(directory).list()

        assertEquals("RuntimeException: newer", reports[0].summary)
        assertEquals("RuntimeException: older", reports[1].summary)
    }

    @Test
    fun listReturnsCapturedContextAppVersionAndTopFrame() {
        val store = storeAt("2026-05-22T10:15:30Z")
        store.save(
            throwable = IllegalStateException("agent pane failed"),
            threadName = "main",
            metadata = metadata,
            context = context,
        )

        val report = store.list().single()

        assertEquals(
            "Tmux session · host=devbox · session=agent-main · cwd=/home/alexey/git/pocketshell",
            report.contextSummary,
        )
        assertEquals("0.1.0", report.appVersion)
        assertTrue(report.topFrame?.contains("CrashReportStoreTest") == true)
    }

    private fun storeAt(instant: String): CrashReportStore =
        CrashReportStore(
            directory = temporaryFolder.newFolder(),
            clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC),
        )

    private companion object {
        val metadata = CrashReportMetadata(
            appVersion = "0.1.0",
            androidRelease = "15",
            sdkInt = 35,
            device = "test-device",
        )
        val context = CrashReportContext(
            screen = "Tmux session",
            hostName = "devbox",
            hostname = "dev.example",
            username = "alexey",
            sessionName = "agent-main",
            startDirectory = "/home/alexey/git/pocketshell",
        )
    }
}
