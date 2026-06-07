package com.pocketshell.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class DiagnosticLogStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `formatter writes one sanitized metadata line without raw whitespace`() {
        val line = DiagnosticLogFormatter.format(
            timestamp = Instant.parse("2026-06-07T10:15:30Z"),
            category = "connection",
            event = "connect fail",
            fields = mapOf(
                "host" to "dev box",
                "cause" to "Socket timeout\nretry",
                "attempt" to 2,
            ),
        )

        assertEquals(
            "2026-06-07T10:15:30Z category=connection event=connect_fail " +
                "attempt=2 cause=Socket_timeout_retry host=dev_box",
            line,
        )
    }

    @Test
    fun `exportSnapshot copies current log to timestamped share file`() {
        val store = newStore()
        store.appendLine("2026-06-07T10:15:30Z category=app event=foreground")

        val exported = store.exportSnapshot("Pixel Test")

        assertNotNull(exported)
        assertTrue(exported!!.name.startsWith("pocketshell-diagnostics-pixel-test-20260607-101530"))
        assertEquals(store.readText(), exported.readText())
    }

    @Test
    fun `exportSnapshot returns null when no log exists`() {
        assertEquals(null, newStore().exportSnapshot("device"))
    }

    @Test
    fun `appendLine trims old complete lines when max size is exceeded`() {
        val store = newStore(maxBytes = 70L)
        store.appendLine("line-1 abcdefghijklmnopqrstuvwxyz")
        store.appendLine("line-2 abcdefghijklmnopqrstuvwxyz")
        store.appendLine("line-3 abcdefghijklmnopqrstuvwxyz")

        val text = store.readText()
        assertFalse(text.contains("line-1"))
        assertTrue(text.contains("line-3"))
        assertTrue(text.length <= 70)
    }

    private fun newStore(maxBytes: Long = DiagnosticLogStore.DEFAULT_MAX_BYTES): DiagnosticLogStore {
        val root = tmp.newFolder()
        return DiagnosticLogStore(
            logFile = File(root, "files/diagnostics.log"),
            exportDirectory = File(root, "cache/diagnostics-export"),
            clock = Clock.fixed(Instant.parse("2026-06-07T10:15:30Z"), ZoneOffset.UTC),
            maxBytes = maxBytes,
        )
    }
}
