package com.pocketshell.app.sessions.service

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.diagnostics.DiagnosticEventSink
import com.pocketshell.app.diagnostics.DiagnosticRecorder
import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.app.settings.SettingsRepository
import com.pocketshell.app.sessions.ActiveTmuxClients
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #1595 (diagnosability slice, red→green).
 *
 * Device-log root cause (Fable audit on #1562): the session FGS was started from the BACKGROUND
 * (`ON_STOP`), where Android 12+ rejects the start with `ForegroundServiceStartNotAllowedException`.
 * BOTH FGS failure paths — the `startForegroundService()` request and the `startForeground()`
 * promotion — were swallowed with a bare `Log.w` and emitted NO DiagnosticEvent, so the
 * connection-log was structurally BLIND to the mechanism and the ~4.4s-after-background transport
 * death could not be attributed.
 *
 * Issue #1598 closes the remaining fidelity gap end-to-end: these tests inject only the platform
 * request/promotion outcome, then drive the real producer through the real [DiagnosticRecorder]
 * and assert the exact JSONL shape uploaded by `connectionLogJsonl()`.
 *
 * RED on the #1598 base: the captured ACTION_START intent loses `hold_active`, so both promotion
 * shapes omit it. GREEN: request and promotion carry the same authoritative value, including
 * false and missing-extra coverage, and denial uses the production `error` key.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SessionConnectionServiceFgsDiagnosticsTest {

    private lateinit var context: Context
    private lateinit var recorder: DiagnosticRecorder

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
        File(context.filesDir, "diagnostics").deleteRecursively()
        recorder = DiagnosticRecorder(context, SettingsRepository(context))
        DiagnosticEvents.install(recorder)
    }

    @After
    fun tearDown() {
        DiagnosticEvents.install(DiagnosticEventSink.Noop)
        SessionConnectionService.startForegroundServiceForTest = null
    }

    @Test
    fun `a rejected startForegroundService emits its exact denied producer shape`() = runTest {
        // The on-device background-FGS-start restriction: startForegroundService() throws.
        SessionConnectionService.startForegroundServiceForTest = { _, _ ->
            throw android.app.ForegroundServiceStartNotAllowedException("bg restricted")
        }

        val started = SessionConnectionService.start(context, holdActive = true)

        assertEquals("a rejected start must return false, not crash", false, started)
        val denied = recorder.sessionFgsEvents()
            .single { it.metadataString("phase") == "request" }
        assertEquals(
            "the diagnostic must capture the exception CLASS so the device log can tell " +
                "ForegroundServiceStartNotAllowedException from a real socket error",
            "ForegroundServiceStartNotAllowedException",
            denied.metadataString("error"),
        )
        assertTrue(denied.metadataBoolean("hold_active"))
        assertFalse(
            "production records denial under `error`; the mirror proof must not fabricate " +
                "`exceptionClass`",
            denied.getJSONObject("metadata").has("exceptionClass"),
        )
    }

    @Test
    fun `the captured true start intent drives exact request and promotion success fields`() = runTest {
        var capturedIntent: Intent? = null
        SessionConnectionService.startForegroundServiceForTest = { _, intent ->
            capturedIntent = intent
        }

        val started = SessionConnectionService.start(context, holdActive = true)

        assertTrue(started)
        val request = recorder.sessionFgsEvents().single()
        assertEquals("request", request.metadataString("phase"))
        assertEquals("ok", request.metadataString("outcome"))
        assertTrue(request.metadataBoolean("hold_active"))

        val service = serviceForPromotion()
        service.onStartCommand(requireNotNull(capturedIntent), 0, 1)

        val promoted = recorder.sessionFgsEvents()
            .single { it.metadataString("phase") == "promote" }
        assertEquals("ok", promoted.metadataString("outcome"))
        assertTrue(promoted.metadataBoolean("hold_active"))
        service.onDestroy()
    }

    @Test
    fun `the captured true start intent drives exact promotion denial fields`() = runTest {
        var capturedIntent: Intent? = null
        SessionConnectionService.startForegroundServiceForTest = { _, intent ->
            capturedIntent = intent
        }
        assertTrue(SessionConnectionService.start(context, holdActive = true))

        val service = serviceForPromotion()
        service.promoteForegroundForTest = {
            throw android.app.ForegroundServiceStartNotAllowedException("promote restricted")
        }

        service.onStartCommand(requireNotNull(capturedIntent), 0, 1)

        val denied = recorder.sessionFgsEvents()
            .single { it.metadataString("phase") == "promote" }
        assertEquals("denied", denied.metadataString("outcome"))
        assertEquals(
            "ForegroundServiceStartNotAllowedException",
            denied.metadataString("error"),
        )
        assertTrue(denied.metadataBoolean("hold_active"))
        assertFalse(denied.getJSONObject("metadata").has("exceptionClass"))
    }

    @Test
    fun `false and missing hold extras both produce false rather than a hard coded value`() = runTest {
        var capturedFalseIntent: Intent? = null
        SessionConnectionService.startForegroundServiceForTest = { _, intent ->
            capturedFalseIntent = intent
        }
        assertTrue(SessionConnectionService.start(context, holdActive = false))
        val falseService = serviceForPromotion()
        falseService.onStartCommand(requireNotNull(capturedFalseIntent), 0, 1)

        val falseEvents = recorder.sessionFgsEvents()
        assertEquals(2, falseEvents.size)
        assertTrue(falseEvents.all { !it.metadataBoolean("hold_active") })
        falseService.onDestroy()

        val missingService = serviceForPromotion()
        missingService.onStartCommand(
            Intent(context, SessionConnectionService::class.java).apply {
                action = SessionConnectionService.ACTION_START
            },
            0,
            2,
        )

        val lastPromotion = recorder.sessionFgsEvents()
            .last { it.metadataString("phase") == "promote" }
        assertFalse(lastPromotion.metadataBoolean("hold_active"))
        missingService.onDestroy()
    }

    private fun serviceForPromotion(): SessionConnectionService =
        Robolectric.buildService(SessionConnectionService::class.java).get().apply {
            createNotificationChannel()
            controller = SessionServiceController(context, ActiveTmuxClients())
            observeDispatcher = Dispatchers.Unconfined
        }

    private suspend fun DiagnosticRecorder.sessionFgsEvents(): List<JSONObject> =
        connectionLogJsonl()
            .lineSequence()
            .filter(String::isNotBlank)
            .map(::JSONObject)
            .filter { it.getString("category") == "connection" && it.getString("name") == "session_fgs" }
            .toList()

    private fun JSONObject.metadataString(key: String): String =
        getJSONObject("metadata").getString(key)

    private fun JSONObject.metadataBoolean(key: String): Boolean =
        getJSONObject("metadata").getBoolean(key)
}
