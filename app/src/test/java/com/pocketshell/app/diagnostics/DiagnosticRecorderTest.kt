package com.pocketshell.app.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.settings.SettingsRepository
import kotlinx.coroutines.test.runTest
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
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DiagnosticRecorderTest {
    private lateinit var context: Context
    private lateinit var settingsRepository: SettingsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
        File(context.filesDir, "diagnostics").deleteRecursively()
        File(context.cacheDir, DIAGNOSTICS_EXPORT_CACHE_DIR).deleteRecursively()
        settingsRepository = SettingsRepository(context)
        // Reset the process-global diagnostics sink so a prior test's installed
        // recorder never leaks reconnect-cause events into this one.
        DiagnosticEvents.install(DiagnosticEventSink.Noop)
    }

    @Test
    fun `recorder is ON by default and captures the first reconnect`() = runTest {
        // Issue #969: recording defaults ON so a FRESH install captures the
        // FIRST reconnect (the one that matters), previously lost under #549's
        // opt-in. The Settings → Diagnostics toggle still turns it off (covered
        // by `recorder skips new events when disabled`).
        assertTrue(
            "diagnostics recording must default ON (issue #969)",
            settingsRepository.settings.value.diagnosticsRecordingEnabled,
        )
        val recorder = DiagnosticRecorder(context, settingsRepository)

        // The very first event on a fresh install is captured — no opt-in needed.
        recorder.record("connection", "connect_start", mapOf("host" to "dev"))

        assertNotNull(recorder.exportSnapshot())
        assertEquals(1, recorder.readEvents().size)
    }

    @Test
    fun `connectionLogJsonl renders only the reconnect-cause trail as jsonl`() = runTest {
        // Issue #972: the payload the host mirror writes to
        // ~/.pocketshell/connection-log.jsonl. It must carry the reconnect-cause
        // breadcrumbs (incl. the named keepalive_dead cause) and NOTHING ELSE
        // (other diagnostics categories are filtered out), one JSON object per line.
        val recorder = DiagnosticRecorder(context, settingsRepository)
        // The reconnect-cause breadcrumbs route through the globally-installed
        // sink (ReconnectCauseTrail.record -> DiagnosticEvents -> this recorder).
        DiagnosticEvents.install(recorder)
        // Unrelated diagnostics noise that must NOT leak into the connection log.
        recorder.record("connection", "connect_start", mapOf("host" to "dev"))
        // The reconnect-cause breadcrumbs (what ReconnectCauseTrail records).
        ReconnectCauseTrail.record(stage = "lease_transport", outcome = "down", cause = "keepalive_dead")
        ReconnectCauseTrail.record(stage = "tmux_probe", outcome = "ok")

        val jsonl = recorder.connectionLogJsonl()

        val lines = jsonl.split("\n").filter { it.isNotBlank() }
        assertEquals("only the two reconnect-cause events, not the connect_start", 2, lines.size)
        lines.forEach { line ->
            val obj = JSONObject(line)
            assertEquals(ReconnectCauseTrail.CATEGORY, obj.getString("category"))
            assertEquals(ReconnectCauseTrail.NAME, obj.getString("name"))
        }
        assertTrue(
            "the named keepalive_dead cause must be carried to the host log",
            jsonl.contains("keepalive_dead"),
        )
    }

    @Test
    fun `connectionLogJsonl is blank when no reconnect cause recorded`() = runTest {
        // The mirror treats a blank payload as a no-op (never writes an empty host
        // file), so a fresh session with no reconnect must produce a blank string.
        val recorder = DiagnosticRecorder(context, settingsRepository)
        recorder.record("connection", "connect_start", mapOf("host" to "dev"))

        assertEquals("", recorder.connectionLogJsonl())
    }

    @Test
    fun `recorder exports ndjson events once enabled in settings`() = runTest {
        settingsRepository.setDiagnosticsRecordingEnabled(true)
        val recorder = DiagnosticRecorder(context, settingsRepository)

        recorder.record("connection", "connect_start", mapOf("host" to "dev"))
        val exported = recorder.exportSnapshot()

        assertNotNull(exported)
        assertTrue(exported!!.name.endsWith(".jsonl"))
        val lines = exported.readLines()
        // First line is the export_summary header (#549 slice d), then the event.
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
    fun `recorder skips new events when disabled`() = runTest {
        settingsRepository.setDiagnosticsRecordingEnabled(false)
        val recorder = DiagnosticRecorder(context, settingsRepository)

        recorder.record("connection", "connect_start", mapOf("host" to "dev"))

        assertEquals(null, recorder.exportSnapshot())
        assertEquals(emptyList<DiagnosticsEvent>(), recorder.readEvents())
    }

    @Test
    fun `readEvents returns recorded events in sequence order`() = runTest {
        settingsRepository.setDiagnosticsRecordingEnabled(true)
        val recorder = DiagnosticRecorder(context, settingsRepository)

        recorder.record("app", "created")
        recorder.record("app", "foreground")

        val events = recorder.readEvents()

        assertEquals(listOf(1L, 2L), events.map { it.sequence })
        assertEquals(listOf("created", "foreground"), events.map { it.name })
    }

    @Test
    fun `clear resets exported sequence window`() = runTest {
        settingsRepository.setDiagnosticsRecordingEnabled(true)
        val recorder = DiagnosticRecorder(context, settingsRepository)

        recorder.record("app", "created")
        recorder.clear()
        recorder.record("app", "foreground")

        val events = recorder.readEvents()

        assertEquals(listOf(1L), events.map { it.sequence })
        assertEquals(listOf("foreground"), events.map { it.name })
    }

    @Test
    fun `clearAndRecord resets exported sequence window and appends marker`() = runTest {
        settingsRepository.setDiagnosticsRecordingEnabled(true)
        val recorder = DiagnosticRecorder(context, settingsRepository)

        recorder.record("app", "created")
        recorder.clearAndRecord("diagnostics", "capture_started")

        val events = recorder.readEvents()

        assertEquals(listOf(1L), events.map { it.sequence })
        assertEquals(listOf("diagnostics"), events.map { it.category })
        assertEquals(listOf("capture_started"), events.map { it.name })
    }

    @Test
    fun `readEvents can return recent matching events`() = runTest {
        settingsRepository.setDiagnosticsRecordingEnabled(true)
        val recorder = DiagnosticRecorder(context, settingsRepository)

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
        settingsRepository.setDiagnosticsRecordingEnabled(true)
        val recorder = DiagnosticRecorder(context, settingsRepository)

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

        // Skip the export_summary header line (#549 slice d) and read the event.
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
