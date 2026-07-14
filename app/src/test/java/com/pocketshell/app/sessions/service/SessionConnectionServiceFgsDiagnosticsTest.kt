package com.pocketshell.app.sessions.service

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.diagnostics.DiagnosticEventSink
import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.app.sessions.ActiveTmuxClients
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
 * These tests reproduce the failure paths synthetically (inject a rejecting starter/promoter) and
 * assert a DiagnosticEvent carrying the exception CLASS is emitted on the `connection` trail.
 *
 * RED on base: base swallows both failures with `Log.w` and records nothing → the recording sink
 * stays empty → every assertion below fails.
 * GREEN with the fix: each failure records `connection`/`session_fgs` with `outcome=denied` and
 * the exception class name.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SessionConnectionServiceFgsDiagnosticsTest {

    private lateinit var context: Context
    private val sink = RecordingSink()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DiagnosticEvents.install(sink)
    }

    @After
    fun tearDown() {
        DiagnosticEvents.install(DiagnosticEventSink.Noop)
        SessionConnectionService.startForegroundServiceForTest = null
    }

    @Test
    fun `a rejected startForegroundService emits a denied diagnostic with the exception class`() {
        // The on-device background-FGS-start restriction: startForegroundService() throws.
        SessionConnectionService.startForegroundServiceForTest = { _, _ ->
            throw android.app.ForegroundServiceStartNotAllowedException("bg restricted")
        }

        val started = SessionConnectionService.start(context, holdActive = true)

        assertEquals("a rejected start must return false, not crash", false, started)
        val denied = sink.find("session_fgs", phase = "request", outcome = "denied")
        assertNotNull(
            "the swallowed FGS start rejection must now emit a connection diagnostic (#1595)",
            denied,
        )
        assertEquals(
            "the diagnostic must capture the exception CLASS so the device log can tell " +
                "ForegroundServiceStartNotAllowedException from a real socket error",
            "ForegroundServiceStartNotAllowedException",
            denied!!["error"],
        )
        assertEquals(true, denied["hold_active"])
    }

    @Test
    fun `a successful startForegroundService emits an ok diagnostic so the device log proves it fired`() {
        // No injected starter → the real ShadowApplication start succeeds.
        val started = SessionConnectionService.start(context, holdActive = true)

        assertEquals(true, started)
        val ok = sink.find("session_fgs", phase = "request", outcome = "ok")
        assertNotNull(
            "a successful foreground-eligible start must also be recorded so the next device " +
                "background proves the start actually fired (#1595)",
            ok,
        )
        assertEquals(true, ok!!["hold_active"])
    }

    @Test
    fun `a rejected startForeground promotion emits a denied diagnostic with the exception class`() {
        val service = Robolectric.buildService(SessionConnectionService::class.java).get()
        service.createNotificationChannel()
        service.promoteForegroundForTest = {
            throw android.app.ForegroundServiceStartNotAllowedException("promote restricted")
        }

        // ACTION_START drives promoteToForegroundIfNeeded → throws → getOrElse records + returns
        // false → the service stops cleanly instead of crashing.
        service.onStartCommand(
            Intent(context, SessionConnectionService::class.java).apply {
                action = SessionConnectionService.ACTION_START
            },
            0,
            1,
        )

        val denied = sink.find("session_fgs", phase = "promote", outcome = "denied")
        assertNotNull(
            "the swallowed FGS promotion failure must now emit a connection diagnostic (#1595)",
            denied,
        )
        assertEquals(
            "ForegroundServiceStartNotAllowedException",
            denied!!["error"],
        )
    }

    @Test
    fun `a successful promotion emits an ok diagnostic`() {
        val service = Robolectric.buildService(SessionConnectionService::class.java).get()
        service.createNotificationChannel()
        // A successful promotion proceeds to startObserving(), which collects the controller's
        // snapshot flow on observeDispatcher. Robolectric.buildService does NOT run Hilt field
        // injection, so wire a real controller (else the collector touches the uninitialized
        // @Inject lateinit and throws uncaught on Dispatchers.Default — a leaked coroutine that
        // surfaces as UncaughtExceptionsBeforeTest in the next runTest). Run the collector on an
        // unconfined dispatcher so the Empty snapshot immediately drives a synchronous
        // stopSessionHold() — no leaked background coroutine.
        service.controller = SessionServiceController(context, ActiveTmuxClients())
        service.observeDispatcher = kotlinx.coroutines.Dispatchers.Unconfined

        service.onStartCommand(
            Intent(context, SessionConnectionService::class.java).apply {
                action = SessionConnectionService.ACTION_START
            },
            0,
            1,
        )

        val ok = sink.find("session_fgs", phase = "promote", outcome = "ok")
        assertNotNull("a successful promotion must be recorded on the connection trail", ok)
        service.onDestroy()
    }

    private class RecordingSink : DiagnosticEventSink {
        val events = mutableListOf<Triple<String, String, Map<String, Any?>>>()
        override fun record(category: String, event: String, fields: Map<String, Any?>) {
            events.add(Triple(category, event, fields))
        }

        fun find(event: String, phase: String, outcome: String): Map<String, Any?>? =
            events.firstOrNull { (category, e, fields) ->
                category == "connection" &&
                    e == event &&
                    fields["phase"] == phase &&
                    fields["outcome"] == outcome
            }?.third
    }
}
