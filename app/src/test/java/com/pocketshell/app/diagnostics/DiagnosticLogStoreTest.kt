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
    fun `redactor fingerprints shareable host user session and path context`() {
        val fields = DiagnosticRedactor.redact(
            mapOf(
                "host" to "prod.example.com",
                "username" to "alexey",
                "session" to "customer-migration",
                "cwd" to "/home/alexey/private/customer",
                "parent_path" to "/home/alexey/private",
                "hostKind" to "dns",
            ),
        )

        assertEquals(
            DiagnosticPrivacy.stableFingerprint("prod.example.com"),
            fields["host"],
        )
        assertEquals(DiagnosticPrivacy.stableFingerprint("alexey"), fields["username"])
        assertEquals(
            DiagnosticPrivacy.stableFingerprint("customer-migration"),
            fields["session"],
        )
        assertEquals(
            DiagnosticPrivacy.stableFingerprint("/home/alexey/private/customer"),
            fields["cwd"],
        )
        assertEquals(
            DiagnosticPrivacy.stableFingerprint("/home/alexey/private"),
            fields["parent_path"],
        )
        assertEquals("dns", fields["hostKind"])
    }

    @Test
    fun `connection context emits stable fingerprints and coarse host kind`() {
        val privateHost = DiagnosticPrivacy.connectionContextFields(
            host = "192.168.1.42",
            user = "alexey",
            session = "pocketshell",
        ).toMap()

        assertEquals(
            DiagnosticPrivacy.stableFingerprint("192.168.1.42"),
            privateHost["hostFingerprint"],
        )
        assertEquals("private_ipv4", privateHost["hostKind"])
        assertEquals(
            DiagnosticPrivacy.stableFingerprint("alexey"),
            privateHost["userFingerprint"],
        )
        assertEquals(
            DiagnosticPrivacy.stableFingerprint("pocketshell"),
            privateHost["sessionFingerprint"],
        )
        assertEquals("dns", DiagnosticPrivacy.hostKind("prod.example.com"))
        assertEquals("loopback", DiagnosticPrivacy.hostKind("localhost"))
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
    fun `exportSnapshot writes summary header then the full log`() {
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

        val lines = exported.readLines().filter { it.isNotBlank() }
        // First line is the export_summary header, then the original log line.
        val header = JSONObject(lines.first())
        assertEquals("diagnostics", header.getString("category"))
        assertEquals("export_summary", header.getString("name"))
        assertEquals(line, lines[1])
        assertEquals(store.readText().trim(), lines.drop(1).joinToString("\n"))
    }

    @Test
    fun `exportSnapshot summary header indexes counts seq and time range`() {
        val store = newStore()
        store.appendLine(
            DiagnosticEventJson.encode(
                DiagnosticsEvent(
                    sequence = 4L,
                    wallClockTime = Instant.parse("2026-06-07T10:15:30Z"),
                    monotonicTimestampNanos = 4L,
                    category = "app",
                    name = "foreground",
                ),
            ),
        )
        store.appendLine(
            DiagnosticEventJson.encode(
                DiagnosticsEvent(
                    sequence = 5L,
                    wallClockTime = Instant.parse("2026-06-07T10:15:31Z"),
                    monotonicTimestampNanos = 5L,
                    category = "connection",
                    name = "connect_start",
                ),
            ),
        )
        store.appendLine(
            DiagnosticEventJson.encode(
                DiagnosticsEvent(
                    sequence = 9L,
                    wallClockTime = Instant.parse("2026-06-07T10:15:35Z"),
                    monotonicTimestampNanos = 9L,
                    category = "connection",
                    name = "connect_fail",
                ),
            ),
        )

        val exported = store.exportSnapshot(deviceLabel = "Pixel Test", appVersion = "0.3.32 (332)")

        assertNotNull(exported)
        // The summary header must parse back as a normal diagnostics event so
        // jq/rg/tail and the existing reader can consume it.
        val summary = DiagnosticEventJson.decode(exported!!.readLines().first())
        assertNotNull(summary)
        assertEquals("diagnostics", summary!!.category)
        assertEquals("export_summary", summary.name)

        val metadata = summary.metadata
        assertEquals(3, metadata["events"])
        assertEquals(4, metadata["firstSeq"])
        assertEquals(9, metadata["lastSeq"])
        assertEquals("2026-06-07T10:15:30Z", metadata["firstWallClock"])
        assertEquals("2026-06-07T10:15:35Z", metadata["lastWallClock"])
        assertEquals(5_000L.toInt(), (metadata["windowMs"] as Number).toInt())
        assertEquals("0.3.32 (332)", metadata["appVersion"])
        assertEquals("Pixel Test", metadata["device"])

        val header = JSONObject(exported.readLines().first())
        val categories = header.getJSONObject("metadata").getJSONObject("categories")
        assertEquals(1, categories.getInt("app"))
        assertEquals(2, categories.getInt("connection"))
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
        val exportedEvents = exported!!.readLines()
            .mapNotNull(DiagnosticEventJson::decode)
            .filterNot { it.category == "diagnostics" && it.name == "export_summary" }
        assertEquals(listOf(3L, 4L), exportedEvents.map { it.sequence })
        assertTrue(exportedEvents.all { it.category == "connection" })

        // The prepended summary still indexes only the filtered window.
        val summary = DiagnosticEventJson.decode(exported.readLines().first())!!
        assertEquals("export_summary", summary.name)
        assertEquals(2, summary.metadata["events"])
        assertEquals(3, summary.metadata["firstSeq"])
        assertEquals(4, summary.metadata["lastSeq"])
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
