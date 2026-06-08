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
    }

    @Test
    fun `recorder is on by default and exports ndjson events`() = runTest {
        val recorder = DiagnosticRecorder(context, settingsRepository)

        recorder.record("connection", "connect_start", mapOf("host" to "dev"))
        val exported = recorder.exportSnapshot()

        assertNotNull(exported)
        assertTrue(exported!!.name.endsWith(".jsonl"))
        val lines = exported.readLines()
        assertEquals(1, lines.size)
        val json = JSONObject(lines.single())
        assertEquals(1L, json.getLong("sequence"))
        assertEquals("connection", json.getString("category"))
        assertEquals("connect_start", json.getString("name"))
        assertEquals("dev", json.getJSONObject("metadata").getString("host"))
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
        val recorder = DiagnosticRecorder(context, settingsRepository)

        recorder.record("app", "created")
        recorder.record("app", "foreground")

        val events = recorder.readEvents()

        assertEquals(listOf(1L, 2L), events.map { it.sequence })
        assertEquals(listOf("created", "foreground"), events.map { it.name })
    }

    @Test
    fun `clear resets exported sequence window`() = runTest {
        val recorder = DiagnosticRecorder(context, settingsRepository)

        recorder.record("app", "created")
        recorder.clear()
        recorder.record("app", "foreground")

        val events = recorder.readEvents()

        assertEquals(listOf(1L), events.map { it.sequence })
        assertEquals(listOf("foreground"), events.map { it.name })
    }

    @Test
    fun `readEvents can return recent matching events`() = runTest {
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
    fun `recorder redacts sensitive metadata before export`() = runTest {
        val recorder = DiagnosticRecorder(context, settingsRepository)

        recorder.record(
            "action",
            "dangerous_test",
            mapOf(
                "prompt" to "please run sk-secret",
                "command" to "cat ~/.ssh/id_rsa",
                "message" to "failed with user prompt",
                "textBytes" to 12,
            ),
        )

        val metadata = JSONObject(recorder.exportSnapshot()!!.readLines().single())
            .getJSONObject("metadata")
        assertEquals("[redacted]", metadata.getString("prompt"))
        assertEquals("[redacted]", metadata.getString("command"))
        assertEquals("[redacted]", metadata.getString("message"))
        assertEquals(12, metadata.getInt("textBytes"))
    }
}
