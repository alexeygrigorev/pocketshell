package com.pocketshell.app.diagnostics

import org.json.JSONObject
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
    fun `event json writes one compact ndjson object`() {
        val line = DiagnosticEventJson.encode(
            DiagnosticsEvent(
                sequence = 7L,
                wallClockTime = Instant.parse("2026-06-07T10:15:30Z"),
                monotonicTimestampNanos = 123_456L,
                category = "connection",
                name = "connect fail",
                metadata = mapOf(
                    "host" to "dev box",
                    "attempt" to 2,
                    "foreground" to true,
                ),
            ),
        )

        assertFalse(line.contains('\n'))
        val json = JSONObject(line)
        assertEquals(7L, json.getLong("sequence"))
        assertEquals("2026-06-07T10:15:30Z", json.getString("wallClockTime"))
        assertEquals(123_456L, json.getLong("monotonicTimestampNanos"))
        assertEquals("connection", json.getString("category"))
        assertEquals("connect fail", json.getString("name"))
        val metadata = json.getJSONObject("metadata")
        assertEquals("dev box", metadata.getString("host"))
        assertEquals(2, metadata.getInt("attempt"))
        assertEquals(true, metadata.getBoolean("foreground"))
    }

    @Test
    fun `redactor removes command prompts and secrets but keeps coarse counters`() {
        val fields = DiagnosticRedactor.redact(
            mapOf(
                "command" to "rm -rf /private/project",
                "prompt" to "fix my production secret",
                "apiToken" to "sk-test-secret-value",
                "message" to "failed while running user content",
                "textBytes" to 42,
                "attachmentCount" to 3,
                "cause" to "TimeoutException",
            ),
        )

        assertEquals("[redacted]", fields["command"])
        assertEquals("[redacted]", fields["prompt"])
        assertEquals("[redacted]", fields["apiToken"])
        assertEquals("[redacted]", fields["message"])
        assertEquals(42, fields["textBytes"])
        assertEquals(3, fields["attachmentCount"])
        assertEquals("TimeoutException", fields["cause"])
    }

    @Test
    fun `event json decodes event for read api`() {
        val event = DiagnosticsEvent(
            sequence = 1L,
            wallClockTime = Instant.parse("2026-06-07T10:15:30Z"),
            monotonicTimestampNanos = 99L,
            category = "connection",
            name = "connect_start",
            metadata = mapOf("attempt" to 2),
        )

        assertEquals(event, DiagnosticEventJson.decode(DiagnosticEventJson.encode(event)))
    }

    @Test
    fun `exportSnapshot copies current log to timestamped share file`() {
        val store = newStore()
        val line = DiagnosticEventJson.encode(
            DiagnosticsEvent(
                sequence = 1L,
                wallClockTime = Instant.parse("2026-06-07T10:15:30Z"),
                monotonicTimestampNanos = 1L,
                category = "app",
                name = "foreground",
            ),
        )
        store.appendLine(line)

        val exported = store.exportSnapshot("Pixel Test")

        assertNotNull(exported)
        assertTrue(exported!!.name.startsWith("pocketshell-diagnostics-pixel-test-20260607-101530"))
        assertTrue(exported.name.endsWith(".jsonl"))
        assertEquals(store.readText(), exported.readText())
    }

    @Test
    fun `exportSnapshot returns null when no log exists`() {
        assertEquals(null, newStore().exportSnapshot("device"))
    }

    @Test
    fun `appendLine trims old complete lines when max size is exceeded`() {
        val store = newStore(maxBytes = 70L)
        store.appendLine("""{"sequence":1,"metadata":{"value":"abcdefghijklmnopqrstuvwxyz"}}""")
        store.appendLine("""{"sequence":2,"metadata":{"value":"abcdefghijklmnopqrstuvwxyz"}}""")
        store.appendLine("""{"sequence":3,"metadata":{"value":"abcdefghijklmnopqrstuvwxyz"}}""")

        val text = store.readText()
        assertFalse(text.contains(""""sequence":1"""))
        assertTrue(text.contains(""""sequence":3"""))
        assertTrue(text.length <= 70)
    }

    @Test
    fun `appendLine trims old events when max event count is exceeded`() {
        val store = newStore(maxEvents = 2)
        store.appendLine(eventLine(sequence = 1L))
        store.appendLine(eventLine(sequence = 2L))
        store.appendLine(eventLine(sequence = 3L))

        val events = store.readEvents()

        assertEquals(listOf(2L, 3L), events.map { it.sequence })
        assertEquals(3L, store.lastSequence())
    }

    @Test
    fun `readEvents filters by recent category name and sequence floor`() {
        val store = newStore()
        store.appendLine(eventLine(sequence = 1L, category = "app", name = "foreground"))
        store.appendLine(eventLine(sequence = 2L, category = "connection", name = "connect_start"))
        store.appendLine(eventLine(sequence = 3L, category = "connection", name = "connect_fail"))
        store.appendLine(eventLine(sequence = 4L, category = "connection", name = "connect_start"))
        store.appendLine(eventLine(sequence = 5L, category = "app", name = "background"))
        store.appendLine(eventLine(sequence = 6L, category = "connection", name = "connect_start"))

        val events = store.readEvents(
            DiagnosticEventFilter(
                category = "connection",
                name = "connect_start",
                sinceSequenceExclusive = 2L,
                maxEvents = 2,
            ),
        )

        assertEquals(listOf(4L, 6L), events.map { it.sequence })
    }

    @Test
    fun `exportSnapshot can write only recent matching events`() {
        val store = newStore()
        store.appendLine(eventLine(sequence = 1L, category = "app", name = "foreground"))
        store.appendLine(eventLine(sequence = 2L, category = "connection", name = "connect_start"))
        store.appendLine(eventLine(sequence = 3L, category = "connection", name = "connect_fail"))
        store.appendLine(eventLine(sequence = 4L, category = "connection", name = "connect_start"))

        val exported = store.exportSnapshot(
            deviceLabel = "Pixel Test",
            filter = DiagnosticEventFilter(category = "connection", maxEvents = 2),
        )

        assertNotNull(exported)
        val exportedEvents = exported!!.readLines().mapNotNull(DiagnosticEventJson::decode)
        assertEquals(listOf(3L, 4L), exportedEvents.map { it.sequence })
        assertTrue(exportedEvents.all { it.category == "connection" })
    }

    private fun eventLine(
        sequence: Long,
        category: String = "app",
        name: String = "event",
    ): String =
        DiagnosticEventJson.encode(
            DiagnosticsEvent(
                sequence = sequence,
                wallClockTime = Instant.parse("2026-06-07T10:15:30Z"),
                monotonicTimestampNanos = sequence,
                category = category,
                name = name,
            ),
        )

    private fun newStore(
        maxBytes: Long = DiagnosticLogStore.DEFAULT_MAX_BYTES,
        maxEvents: Int = DiagnosticLogStore.DEFAULT_MAX_EVENTS,
    ): DiagnosticLogStore {
        val root = tmp.newFolder()
        return DiagnosticLogStore(
            logFile = File(root, "files/diagnostics.log"),
            exportDirectory = File(root, "cache/diagnostics-export"),
            clock = Clock.fixed(Instant.parse("2026-06-07T10:15:30Z"), ZoneOffset.UTC),
            maxBytes = maxBytes,
            maxEvents = maxEvents,
        )
    }
}
