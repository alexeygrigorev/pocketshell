package com.pocketshell.next.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant

/**
 * Adapted from the old app's `DiagnosticRecorderTest` (rewrite task P-10):
 * the trimmed [DiagnosticRecorder] has no settings-repository on/off gate
 * (recording is unconditionally on — see the class doc) and no
 * `connectionLogJsonl`/`connectionJournalJsonl`/`ReconnectCauseTrail`
 * mirroring — those belonged to the connection-journal/mirror/part-store
 * stack this task does not port. What is asserted here is the generic
 * record/read/export/clear contract plus the redaction pass-through, which
 * IS ported.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DiagnosticRecorderTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "diagnostics").deleteRecursively()
        File(context.cacheDir, DIAGNOSTICS_EXPORT_CACHE_DIR).deleteRecursively()
        // Reset the process-global diagnostics sink so a prior test's installed
        // recorder never leaks events into this one.
        DiagnosticEvents.install(DiagnosticEventSink.Noop)
    }

    @Test
    fun `recorder is on unconditionally and captures the first event`() = runTest {
        // The trimmed recorder has no settings gate (issue #969's "recording
        // defaults ON" concern doesn't apply — there is no off state to
        // default away from), so the very first event on a fresh install is
        // captured with no opt-in step.
        val recorder = DiagnosticRecorder(context)

        recorder.record("connection", "connect_start", mapOf("host" to "dev"))

        assertNotNull(recorder.exportSnapshot())
        assertEquals(1, recorder.readEvents().size)
    }

    @Test
    fun `recorder exports ndjson events`() = runTest {
        val recorder = DiagnosticRecorder(context)

        recorder.record("connection", "connect_start", mapOf("host" to "dev"))
        val exported = recorder.exportSnapshot()

        assertNotNull(exported)
        assertTrue(exported!!.name.endsWith(".jsonl"))
        val lines = exported.readLines()
        // First line is the export_summary header, then the event.
        assertEquals(2, lines.size)
        val header = JSONObject(lines.first())
        assertEquals("diagnostics", header.getString("category"))
        assertEquals("export_summary", header.getString("name"))
        assertEquals(1, header.getJSONObject("metadata").getInt("events"))
        val json = JSONObject(lines[1])
        assertEquals(1L, json.getLong("sequence"))
        assertEquals("connection", json.getString("category"))
        assertEquals("connect_start", json.getString("name"))
        assertEquals(
            DiagnosticPrivacy.stableFingerprint("dev"),
            json.getJSONObject("metadata").getString("host"),
        )
        assertTrue(json.has("wallClockTime"))
        assertTrue(json.has("monotonicTimestampNanos"))
    }

    @Test
    fun `readEvents returns recorded events in sequence order`() = runTest {
        val recorder = DiagnosticRecorder(context)

        recorder.record("app", "created")
        recorder.record("app", "foreground")

        val events = recorder.readEvents()

        assertEquals(listOf(1L, 2L), events.map { it.sequence })
        assertEquals(listOf("created", "foreground"), events.map { it.name })
    }

    @Test
    fun `clear resets exported sequence window`() = runTest {
        val recorder = DiagnosticRecorder(context)

        recorder.record("app", "created")
        recorder.clear()
        recorder.record("app", "foreground")

        val events = recorder.readEvents()

        assertEquals(listOf(1L), events.map { it.sequence })
        assertEquals(listOf("foreground"), events.map { it.name })
    }

    @Test
    fun `clearAndRecord resets exported sequence window and appends marker`() = runTest {
        val recorder = DiagnosticRecorder(context)

        recorder.record("app", "created")
        recorder.clearAndRecord("diagnostics", "capture_started")

        val events = recorder.readEvents()

        assertEquals(listOf(1L), events.map { it.sequence })
        assertEquals(listOf("diagnostics"), events.map { it.category })
        assertEquals(listOf("capture_started"), events.map { it.name })
    }

    @Test
    fun `readEvents can return recent matching events`() = runTest {
        val recorder = DiagnosticRecorder(context)

        recorder.record("app", "created")
        recorder.record("connection", "connect_start")
        recorder.record("connection", "connect_fail")
        recorder.record("connection", "connect_start")

        val events = recorder.readEvents(
            DiagnosticEventFilter.recent(2).copy(category = "connection"),
        )

        assertEquals(listOf(3L, 4L), events.map { it.sequence })
        assertTrue(events.all { it.category == "connection" })
    }

    @Test
    fun `log store trims oldest events when ring buffer event limit is exceeded`() {
        val store = DiagnosticLogStore(
            logFile = File(context.filesDir, "diagnostics/ring-test.jsonl"),
            exportDirectory = File(context.cacheDir, DIAGNOSTICS_EXPORT_CACHE_DIR),
            maxBytes = 0L,
            maxEvents = 3,
        )

        (1L..5L).forEach { sequence ->
            store.appendLine(
                DiagnosticEventJson.encode(
                    DiagnosticsEvent(
                        sequence = sequence,
                        wallClockTime = Instant.EPOCH.plusSeconds(sequence),
                        monotonicTimestampNanos = sequence,
                        category = "action",
                        name = "tap_$sequence",
                    ),
                ),
            )
        }

        val events = store.readEvents()
        assertEquals(listOf(3L, 4L, 5L), events.map { it.sequence })
        assertEquals(listOf("tap_3", "tap_4", "tap_5"), events.map { it.name })
    }

    @Test
    fun `recorder redacts sensitive metadata before export`() = runTest {
        val recorder = DiagnosticRecorder(context)

        recorder.record(
            "action",
            "dangerous_test",
            mapOf(
                "prompt" to "please run sk-secret",
                "command" to "cat ~/.ssh/id_rsa",
                "message" to "failed with user prompt",
                "session" to "work-production",
                "cwd" to "/home/alexey/private/project",
                "textBytes" to 12,
            ),
        )

        // Skip the export_summary header line and read the event.
        val eventLine = recorder.exportSnapshot()!!.readLines()
            .last { JSONObject(it).getString("name") == "dangerous_test" }
        val metadata = JSONObject(eventLine).getJSONObject("metadata")
        assertEquals("[redacted]", metadata.getString("prompt"))
        assertEquals("[redacted]", metadata.getString("command"))
        assertEquals("[redacted]", metadata.getString("message"))
        assertEquals(
            DiagnosticPrivacy.stableFingerprint("work-production"),
            metadata.getString("session"),
        )
        assertEquals(
            DiagnosticPrivacy.stableFingerprint("/home/alexey/private/project"),
            metadata.getString("cwd"),
        )
        assertEquals(12, metadata.getInt("textBytes"))
    }
}
