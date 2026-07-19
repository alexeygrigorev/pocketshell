package com.pocketshell.app.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.settings.SettingsRepository
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Issue #1669: connection-log telemetry integrity. Today's forensics could not
 * tell WHICH build produced a storm — no event carried the app version, so it
 * took a `git ls-remote` to discover the phone was on v0.4.37. These tests pin:
 *
 *  - every emitted event (device store AND the host connection log) is
 *    version-stamped at the top level; and
 *  - the mirrored payload renders from the LOSSLESS part-store archive, so the
 *    curated connection lifecycle survives intact and versioned.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class Issue1669ConnectionLogTelemetryTest {
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
    fun `every exported event carries a non-empty versionName and numeric versionCode`() = runTest {
        // Real path: no override, so this exercises readAppVersion() end-to-end.
        // Load-bearing: without the #1669 stamp, versionName defaults to "" and
        // this fails.
        val recorder = DiagnosticRecorder(context, settingsRepository)
        recorder.record("connection", "connect_start", mapOf("host" to "dev"))
        recorder.record("action", "tap_send", mapOf("pane" to "%1"))

        val exported = recorder.exportSnapshot()!!.readLines()
            .filter { JSONObject(it).getString("name") != "export_summary" }
        assertTrue("expected persisted events", exported.isNotEmpty())
        exported.forEach { line ->
            val json = JSONObject(line)
            assertTrue(
                "every event must carry a non-empty versionName: $line",
                json.optString("versionName").isNotEmpty(),
            )
            // versionCode must be a NUMBER (getLong throws if it is a string/absent).
            json.getLong("versionCode")
        }
    }

    @Test
    fun `the version stamp is the app's build and survives the disk round-trip`() = runTest {
        val recorder = DiagnosticRecorder(context, settingsRepository)
        // Pin the build so the assertion is deterministic and proves the exact
        // name+code flow through encode -> disk -> decode.
        recorder.appVersionOverride = DiagnosticRecorder.AppVersion(name = "0.4.39", code = 86L)

        recorder.record("connection", "reconnect_fail", mapOf("cause" to "attach_not_ready"))
        val event = recorder.readEvents().single()

        assertEquals("0.4.39", event.versionName)
        assertEquals(86L, event.versionCode)
    }

    @Test
    fun `the host connection log stamps every line with the build`() = runTest {
        // Forensics read the HOST connection-log.jsonl. Every line it carries must
        // name the build, so a storm is attributable to a version without guessing.
        val recorder = DiagnosticRecorder(context, settingsRepository)
        DiagnosticEvents.install(recorder)
        recorder.appVersionOverride = DiagnosticRecorder.AppVersion(name = "0.4.39", code = 86L)

        recorder.record("connection", "connect_start", mapOf("host" to "dev"))
        ReconnectCauseTrail.record(stage = "lease_transport", outcome = "down", cause = "keepalive_dead")

        val lines = recorder.connectionLogJsonl().split("\n").filter { it.isNotBlank() }
        assertTrue("the host log must carry the mirrored events", lines.isNotEmpty())
        lines.forEach { line ->
            val json = JSONObject(line)
            assertEquals("0.4.39", json.getString("versionName"))
            assertEquals(86L, json.getLong("versionCode"))
        }
    }

    @Test
    fun `the mirrored payload renders from the lossless archive, not the ring buffer`() = runTest {
        // The archive is the SOURCE of connectionLogJsonl. Only mirrored
        // connection-lifecycle events reach it; device-only chatter never does.
        val recorder = DiagnosticRecorder(context, settingsRepository)
        DiagnosticEvents.install(recorder)

        repeat(40) { recorder.record("connection", "reconnect_fail", mapOf("marker" to it)) }
        repeat(40) { recorder.record("action", "tap_send", mapOf("pane" to "%$it")) }

        val archive = recorder.connectionLogArchive()
        assertEquals("all 40 connection events survive in the lossless archive", 40, archive.size)
        assertTrue("the archive is connection-log only", archive.all { it.category == "connection" })
        assertFalse(
            "device-only chatter must never enter the connection-log archive",
            archive.any { it.name == "tap_send" },
        )
    }
}
