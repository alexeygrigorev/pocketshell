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
    fun `reconnect cause-trail classifies the reader_exception failure shape so it is diagnosable`() {
        // Issue #1610: the mobile -CC reader SSHException tears down the shared
        // lease transport, but the cause-trail dropped `message` to `[redacted]`
        // so we could not tell WHICH SSHException it was. The reconnect diagnosis
        // categories must SURFACE a bounded, allowlisted classification of the
        // failure shape (Option A) — WHICH SSHException — with zero chance of
        // leaking arbitrary secret text that a caller folded into the message.
        // RED on base (blanket redaction): `message` came out `[redacted]`.
        val reconnectFields = DiagnosticRedactor.redact(
            mapOf(
                "exceptionClass" to "SSHException",
                "message" to "SSHException: connection reset by peer",
                "transportDropSource" to "control_channel",
                "disconnectSource" to "read_failure",
            ),
            category = ReconnectCauseTrail.CATEGORY,
        )
        assertEquals("SSHException", reconnectFields["exceptionClass"])
        // The operator can see it was a connection-reset read failure.
        assertEquals("connection_reset", reconnectFields["message"])

        // Same surfacing for the `connection` lifecycle category (passive_disconnect):
        // a broken-pipe read failure classifies as `broken_pipe`.
        val connectionFields = DiagnosticRedactor.redact(
            mapOf("message" to "Broken pipe"),
            category = "connection",
        )
        assertEquals("broken_pipe", connectionFields["message"])

        // Class coverage over the reader-teardown failure shapes the -CC channel hits.
        assertEquals(
            "eof",
            DiagnosticRedactor.redact(
                mapOf("message" to "SSHException: Premature EOF"),
                category = ReconnectCauseTrail.CATEGORY,
            )["message"],
        )
        assertEquals(
            "timeout",
            DiagnosticRedactor.redact(
                mapOf("message" to "java.net.SocketTimeoutException: Read timed out"),
                category = ReconnectCauseTrail.CATEGORY,
            )["message"],
        )
        assertEquals(
            "channel_closed",
            DiagnosticRedactor.redact(
                mapOf("message" to "TransportException: Channel closed unexpectedly"),
                category = ReconnectCauseTrail.CATEGORY,
            )["message"],
        )
        // An unrecognised shape collapses to the safe `other` token — never raw text.
        assertEquals(
            "other",
            DiagnosticRedactor.redact(
                mapOf("message" to "Some unusual internal state 0xdeadbeef"),
                category = ReconnectCauseTrail.CATEGORY,
            )["message"],
        )
    }

    @Test
    fun `surfaced reconnect message never leaks any secret shape and is always an allowlisted token`() {
        // Issue #1610 load-bearing safety (reviewer round-1 BLOCKING finding): the
        // round-1 marker-abutment scrubber leaked every secret shape whose token
        // did not immediately abut one of 6 markers. Option A never emits ANY
        // substring of the input — the surfaced value is ALWAYS one of a fixed set
        // of lowercase `[a-z_]+` classification tokens — so NO secret shape can
        // ever leak. Each pair is (message, the-secret-that-must-not-appear).
        val tokenPattern = Regex("^[a-z_]+$")
        val leakingShapes = listOf(
            // Reviewer-named leak #1: standard HTTP header, SPACE after the colon —
            // round-1 masked only a token ABUTTING the marker, so this leaked.
            "authorization: aBcDeF0123456789tokenvalue" to "aBcDeF0123456789tokenvalue",
            // Reviewer-named leak #2: PEM private-key block body (only `RSA` was
            // masked by round-1; the base64 body surfaced verbatim).
            "SSHException while reading -----BEGIN RSA PRIVATE KEY-----\n" +
                "MIIEowIBAAKCAQEA7Xk2SecretKeyBodyLine1AbCdEf1234567890\n" +
                "MIIEowIBAAKCAQEA7Xk2SecretKeyBodyLine2GhIjKl0987654321\n" +
                "-----END RSA PRIVATE KEY-----" to "MIIEowIBAAKCAQEA7Xk2SecretKeyBodyLine1AbCdEf1234567890",
            // OpenSSH PEM block body.
            "-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaFNecretOpenSshBody0000\n" +
                "-----END OPENSSH PRIVATE KEY-----" to "b3BlbnNzaFNecretOpenSshBody0000",
            // Reviewer-named leak #3: no-marker HIGH-ENTROPY token (round-1 had no
            // entropy detection anywhere, so it surfaced in full).
            "connect failure near dGhpc0lzQVZlcnlIaWdoRW50cm9weVNlY3JldFRva2VuMDEyMzQ1Njc4OQ" to
                "dGhpc0lzQVZlcnlIaWdoRW50cm9weVNlY3JldFRva2VuMDEyMzQ1Njc4OQ",
            // Bare hex secret, no marker.
            "state 0a1b2c3d4e5f60718293a4b5c6d7e8f9012345678 dropped" to
                "0a1b2c3d4e5f60718293a4b5c6d7e8f9012345678",
            // Original marker shapes (these DID get masked by round-1, kept for coverage).
            "auth failed password=hunter2secretvalue" to "hunter2secretvalue",
            "using key sk-live-abc123def456ghi789" to "sk-live-abc123def456ghi789",
            "token github_pat_ABCDEF1234567890abcdef" to "github_pat_ABCDEF1234567890abcdef",
            "header bearer AAAAsecretbearervaluebbbb" to "AAAAsecretbearervaluebbbb",
        )

        for ((message, secret) in leakingShapes) {
            val surfaced = DiagnosticRedactor.redact(
                mapOf("message" to message),
                category = ReconnectCauseTrail.CATEGORY,
            )["message"] as String

            assertFalse(
                "secret leaked for message <$message>: surfaced=<$surfaced>",
                surfaced.contains(secret),
            )
            // Strongest guarantee: the output can ONLY be a fixed classification
            // token. Any raw message text (spaces, digits, uppercase, `:`/`-`) fails
            // this — so no arbitrary substring of the input can ever surface.
            assertTrue(
                "surfaced value is not an allowlisted classification token: <$surfaced>",
                tokenPattern.matches(surfaced),
            )
        }
    }

    @Test
    fun `message stays fully redacted outside the reconnect diagnosis categories`() {
        // Class coverage: the allowlist is narrow. A non-reconnect category (and
        // the no-category default) keeps `message` fully `[redacted]` — the
        // surfacing does NOT over-open the redactor everywhere.
        val actionFields = DiagnosticRedactor.redact(
            mapOf("message" to "failed with user prompt content"),
            category = "action",
        )
        assertEquals("[redacted]", actionFields["message"])

        val noCategory = DiagnosticRedactor.redact(
            mapOf("message" to "failed with user prompt content"),
        )
        assertEquals("[redacted]", noCategory["message"])
    }

    @Test
    fun `reconnect category does not open other sensitive keys`() {
        // Class coverage: opening `message` for reconnect must NOT open sibling
        // sensitive keys (password/token/command/prompt) — they stay redacted
        // even in the reconnect category.
        val fields = DiagnosticRedactor.redact(
            mapOf(
                "message" to "SSHException: timeout",
                "password" to "hunter2secret",
                "apiToken" to "sk-live-secret-value",
                "command" to "cat ~/.ssh/id_rsa",
                "prompt" to "please run the secret",
            ),
            category = ReconnectCauseTrail.CATEGORY,
        )
        assertEquals("timeout", fields["message"])
        assertEquals("[redacted]", fields["password"])
        assertEquals("[redacted]", fields["apiToken"])
        assertEquals("[redacted]", fields["command"])
        assertEquals("[redacted]", fields["prompt"])
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
