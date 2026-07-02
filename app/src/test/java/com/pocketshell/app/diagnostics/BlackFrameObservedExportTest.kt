package com.pocketshell.app.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.settings.SettingsRepository
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

/**
 * Issue #1175 — the `black_frame_observed` fingerprint must reach the SAME exportable
 * JSONL the Settings → Diagnostics "Share log" path reads (acceptance criterion 3).
 *
 * `SettingsViewModel.shareDiagnosticsLog()` calls `DiagnosticRecorder.exportSnapshot()`,
 * which is exactly what this test exercises: record a `black_frame_observed` event, then
 * assert the exported `*.jsonl` file carries it — the `class` discriminator and the
 * geometry/lifecycle fields intact — so a shared log fingerprints the black-screen class.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class BlackFrameObservedExportTest {
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
        DiagnosticEvents.install(DiagnosticEventSink.Noop)
    }

    @Test
    fun `black_frame_observed lands in the shareable diagnostics export`() = runTest {
        val recorder = DiagnosticRecorder(context, settingsRepository)

        recorder.record(
            "terminal",
            "black_frame_observed",
            mapOf(
                "class" to "reveal_gate_gave_up_still_blank",
                "paneId" to "%1",
                "renderedChars" to 0,
                "captureBytes" to 0,
                "visibleRows" to 40,
                "msSinceLastSeed" to 1_200L,
                "partialBlank" to false,
            ),
        )

        // The exact path Settings → Diagnostics → "Share log" uses.
        val exported = recorder.exportSnapshot()
        assertNotNull("the exportable JSONL must be produced", exported)
        assertTrue(exported!!.name.endsWith(".jsonl"))

        val eventLine = exported.readLines()
            .last { JSONObject(it).getString("name") == "black_frame_observed" }
        val json = JSONObject(eventLine)
        assertEquals("terminal", json.getString("category"))
        val metadata = json.getJSONObject("metadata")
        assertEquals("reveal_gate_gave_up_still_blank", metadata.getString("class"))
        assertEquals(0, metadata.getInt("renderedChars"))
        assertEquals(40, metadata.getInt("visibleRows"))
        assertEquals(1_200L, metadata.getLong("msSinceLastSeed"))
        assertEquals(false, metadata.getBoolean("partialBlank"))
    }

    @Test
    fun `export summary counts the terminal black-frame category`() = runTest {
        val recorder = DiagnosticRecorder(context, settingsRepository)
        recorder.record("terminal", "black_frame_observed", mapOf("class" to "capture_empty"))

        val exported = recorder.exportSnapshot()
        assertNotNull(exported)
        // The one-line export_summary header (#549) indexes the new event's category.
        val header = JSONObject(exported!!.readLines().first())
        assertEquals("export_summary", header.getString("name"))
        assertEquals(
            1,
            header.getJSONObject("metadata").getJSONObject("categories").getInt("terminal"),
        )
    }
}
