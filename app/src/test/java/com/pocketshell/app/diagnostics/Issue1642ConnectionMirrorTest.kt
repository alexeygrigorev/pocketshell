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
 * Issue #1642 slice 1 (with #1598): the `connection` category must reach the
 * HOST-mirrored connection log.
 *
 * ## The reported problem this reproduces
 *
 * The maintainer could not use PocketShell on mobile data — it reconnected every
 * ~5s (#1610) — and it took SIX parallel investigations to name the mechanism.
 * The device already knew. The event that names the storm's engine,
 *
 * ```
 * connection/reconnect_fail cause=attach_not_ready elapsedMs≈5000
 * ```
 *
 * (emitted by the grace loop, `TmuxSessionViewModel.kt:8526-8545`) was recorded
 * on-device the whole time and NEVER reached the host, because
 * [DiagnosticRecorder.connectionLogJsonl] filtered the mirrored payload down to
 * `reconnect/cause_trail` ONLY. So did `connection/pane_input_send_failed` (the
 * #1635 storm amplifier) and `connection/session_fgs` (#1595/#1598, a diagnostic
 * added SPECIFICALLY so the FGS outcome could be confirmed remotely).
 *
 * These tests assert those events land in the mirrored payload. On the un-fixed
 * base every one of them is RED (the payload is `reconnect/cause_trail`-only).
 *
 * ## Why the assertions are what they are
 *
 * - **No proxy** — the assertion runs against the REAL production payload
 *   builder [DiagnosticRecorder.connectionLogJsonl] (the exact string
 *   `ConnectionLogHostMirror` uploads), routed through the REAL global sink, with
 *   the REAL field shapes the production emitters use.
 * - **Class coverage** (G2) — not just the one reported event: the storm-naming
 *   `reconnect_fail`, the amplifier `pane_input_send_failed`, the remote-
 *   confirmation `session_fgs`, the already-mirrored `reconnect/cause_trail`
 *   (no regression), the excluded chatter categories, and the missing-data case.
 * - **No `assumeTrue`** anywhere: every assertion is load-bearing on every runner.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class Issue1642ConnectionMirrorTest {
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
        // Reset the process-global sink so a prior test's recorder never leaks
        // events into this one.
        DiagnosticEvents.install(DiagnosticEventSink.Noop)
    }

    private fun newRecorder(): DiagnosticRecorder =
        DiagnosticRecorder(context, settingsRepository).also { DiagnosticEvents.install(it) }

    private fun String.mirroredLines(): List<JSONObject> =
        split("\n").filter { it.isNotBlank() }.map(::JSONObject)

    /**
     * The #1610 storm's engine, exactly as the grace loop records it
     * (`TmuxSessionViewModel.kt:8526-8545`). This is the reproduction: on base
     * the host mirror never carried it, so six investigations re-derived by hand
     * what the phone had already written down.
     */
    @Test
    fun `the storm-naming reconnect_fail attach_not_ready reaches the host connection log`() = runTest {
        val recorder = newRecorder()

        DiagnosticEvents.record(
            "connection",
            "reconnect_fail",
            "hostId" to 7L,
            "host" to "dev.example.internal",
            "port" to 22,
            "user" to "alexey",
            "session" to "work-pocketshell",
            "trigger" to "auto-reconnect",
            "source" to "silent_transport_reattach",
            "cause" to "attach_not_ready",
            "evictedLease" to true,
            "clientHash" to 123456,
            "elapsedMs" to 5004L,
        )

        val jsonl = recorder.connectionLogJsonl()

        val event = jsonl.mirroredLines().singleOrNull {
            it.getString("category") == "connection" && it.getString("name") == "reconnect_fail"
        }
        assertTrue(
            "connection/reconnect_fail must reach the host log — it NAMES the #1610 storm " +
                "and was invisible on the host while the maintainer could not use the app. " +
                "Payload was: $jsonl",
            event != null,
        )
        val metadata = event!!.getJSONObject("metadata")
        // The two fields that name the mechanism: WHICH failure, and the 5s budget floor.
        assertEquals("attach_not_ready", metadata.getString("cause"))
        assertEquals(5004L, metadata.getLong("elapsedMs"))
        assertEquals("silent_transport_reattach", metadata.getString("source"))
        assertTrue("the lease eviction must be attributable", metadata.getBoolean("evictedLease"))
    }

    /**
     * Class coverage (G2): the storm's OTHER already-recorded-but-invisible
     * events. One instance passing is not the fix — the whole `connection`
     * category must reach the host.
     */
    @Test
    fun `the storm amplifier and the FGS outcome reach the host connection log`() = runTest {
        val recorder = newRecorder()

        // #1635: the outbound-queue escalation that amplifies the storm.
        DiagnosticEvents.record(
            "connection",
            "pane_input_send_failed",
            "pane" to "%3",
            "attempt" to 2,
            "bytes" to 12,
            "exceptionClass" to "SSHException",
            "clientDisconnected" to true,
            "willRetry" to false,
        )
        // #1595/#1598: recorded PRECISELY so the FGS outcome could be confirmed
        // remotely from the synced log — and it never synced.
        DiagnosticEvents.record(
            "connection",
            "session_fgs",
            "phase" to "request",
            "outcome" to "denied",
            "hold_active" to true,
            "exceptionClass" to "ForegroundServiceStartNotAllowedException",
        )

        val jsonl = recorder.connectionLogJsonl()
        val names = jsonl.mirroredLines().map { it.getString("name") }

        assertTrue(
            "connection/pane_input_send_failed (the #1635 storm amplifier) must reach the " +
                "host log. Payload was: $jsonl",
            "pane_input_send_failed" in names,
        )
        assertTrue(
            "connection/session_fgs must reach the host log — #1598's whole point was " +
                "confirming the FGS outcome REMOTELY. Payload was: $jsonl",
            "session_fgs" in names,
        )
        val fgs = jsonl.mirroredLines().single { it.getString("name") == "session_fgs" }
        assertEquals("denied", fgs.getJSONObject("metadata").getString("outcome"))
    }

    /**
     * No regression: the trail that was ALREADY mirrored must still be mirrored.
     * Broadening the filter must add to the host log, never replace it.
     */
    @Test
    fun `the reconnect cause-trail is still mirrored alongside the connection category`() = runTest {
        val recorder = newRecorder()

        ReconnectCauseTrail.record(
            stage = "lease_transport",
            outcome = "down",
            cause = "keepalive_dead",
        )
        DiagnosticEvents.record("connection", "connect_start", "attempt" to 1)

        val lines = recorder.connectionLogJsonl().mirroredLines()

        assertEquals(
            listOf(
                ReconnectCauseTrail.CATEGORY to ReconnectCauseTrail.NAME,
                "connection" to "connect_start",
            ),
            lines.map { it.getString("category") to it.getString("name") },
        )
        assertTrue(
            "the named keepalive_dead cause must still be carried to the host log",
            lines.first().getJSONObject("metadata").getString("cause") == "keepalive_dead",
        )
    }

    /**
     * Bounding the volume (#1598's "do not flood"): the device-only chatter
     * categories stay device-only. the `action` category alone is 67 event names of
     * user-behaviour noise — mirroring it would burn the maintainer's mobile
     * data for no connection-diagnosis value.
     */
    @Test
    fun `device-only chatter categories never reach the host connection log`() = runTest {
        val recorder = newRecorder()

        DiagnosticEvents.record("connection", "connect_start", "attempt" to 1)
        DiagnosticEvents.record("action", "tap_send", "pane" to "%1")
        DiagnosticEvents.record("navigation", "screen_shown", "screen" to "hosts")
        DiagnosticEvents.record("strictmode", "violation", "detail" to "disk read on main")
        DiagnosticEvents.record("terminal", "frame_rendered", "rows" to 40)

        val categories = recorder.connectionLogJsonl().mirroredLines()
            .map { it.getString("category") }

        assertEquals(listOf("connection"), categories)
    }

    /**
     * The missing-data case: nothing mirrored ⇒ blank ⇒ the mirror no-ops rather
     * than writing an empty host file.
     */
    @Test
    fun `payload is blank when only device-only categories were recorded`() = runTest {
        val recorder = newRecorder()

        DiagnosticEvents.record("action", "tap_send", "pane" to "%1")

        assertEquals("", recorder.connectionLogJsonl())
    }

    /**
     * ### The #1639 M1 line, as a property over the whole document
     *
     * The host log is mirrored to a shared dev box AND routinely quoted into
     * PUBLIC GitHub issues by agents. #1639 found the redactor emitting RAW tmux
     * session names — which are directory paths by construction — through the
     * `pausedSession`/`currentSession`/`intentSession` keys, because the
     * fingerprint rule matched only the exact key `session`.
     *
     * This is asserted as a PROPERTY over the rendered document (not per-key), so
     * a NEW `*Session` vararg field added tomorrow cannot silently opt out of
     * redaction — the failure mode #1639 called out.
     */
    @Test
    fun `no raw session name host user or path appears anywhere in the mirrored payload`() = runTest {
        val recorder = newRecorder()

        val secretSession = "home-alexey-private-client-work"
        val secretHost = "prod-jumpbox.client.internal"
        val secretUser = "alexey-admin"
        val secretPath = "/home/alexey/private/client-work"

        // Every mirrored shape that carries an identity string, across BOTH
        // mirrored categories — the whole class, not one key.
        ReconnectCauseTrail.record(
            stage = "onScreenStarted",
            outcome = "cleared_stale_paused_reconnect",
            cause = "session_mismatch",
            "pausedSession" to secretSession,
            "currentSession" to secretSession,
        )
        ReconnectCauseTrail.record(
            stage = "handlePassiveClientDisconnect",
            outcome = "skipped_pause_in_app_navigation",
            cause = "different_session_target",
            "pausedSession" to secretSession,
            "intentSession" to secretSession,
            "hasActiveConnectJob" to true,
        )
        DiagnosticEvents.record(
            "connection",
            "reconnect_fail",
            "host" to secretHost,
            "user" to secretUser,
            "session" to secretSession,
            "activeSession" to secretSession,
            "originSession" to secretSession,
            "cwd" to secretPath,
            "cause" to "attach_not_ready",
        )

        val jsonl = recorder.connectionLogJsonl()

        listOf(secretSession, secretHost, secretUser, secretPath).forEach { secret ->
            assertFalse(
                "raw identity string '$secret' leaked into the host connection log, which is " +
                    "mirrored to the host AND quoted into public issues (#1639 M1). " +
                    "Payload was: $jsonl",
                jsonl.contains(secret),
            )
        }
        // ...and the diagnosis still works: the fingerprints are present and
        // JOINABLE (same session ⇒ same fingerprint across both categories).
        val fingerprint = DiagnosticPrivacy.stableFingerprint(secretSession)
        assertTrue(
            "the session fingerprint must still be present so drops stay attributable",
            jsonl.contains(fingerprint),
        )
    }

    /**
     * A fingerprinted BOOLEAN is both useless (two possible digests) and a lie —
     * it presents a yes/no as an opaque identity. `hasSession`/`hasX` booleans
     * must survive the `*Session` fingerprint rule as booleans.
     */
    @Test
    fun `a boolean field whose key ends in session stays a boolean`() = runTest {
        val recorder = newRecorder()

        DiagnosticEvents.record(
            "connection",
            "reconnect_fail",
            "hasSession" to false,
            "cause" to "attach_not_ready",
        )

        val metadata = recorder.connectionLogJsonl().mirroredLines().single()
            .getJSONObject("metadata")
        assertEquals(false, metadata.getBoolean("hasSession"))
    }

    /**
     * ### The mobile-data bound
     *
     * The maintainer is on mobile data, and the mirror is snapshot-overwrite: it
     * re-uploads the whole rendered window on EVERY transport-up (so ~100× on a
     * storm day). Today that payload is UNBOUNDED — it grows to whatever the
     * 512KB device store retains. Broadening the filter without a bound would
     * multiply the maintainer's storm-day upload.
     *
     * So the rendered payload is hard-capped, keeping the NEWEST events (the ones
     * describing the drop that just happened). The cap is what keeps slice 1's
     * bytes/upload at roughly today's observed ~56KB while carrying strictly more
     * diagnostic value per byte. (Slice 6's incremental append removes the
     * re-upload entirely; this is the interim bound.)
     */
    @Test
    fun `the mirrored payload is capped and keeps the newest events`() = runTest {
        val recorder = newRecorder()

        // Well past the budget: ~600 events at ~340B ≈ 204KB rendered.
        repeat(600) { i ->
            DiagnosticEvents.record(
                "connection",
                "reconnect_fail",
                "cause" to "attach_not_ready",
                "marker" to i,
                "host" to "dev.example.internal",
                "session" to "work-pocketshell",
                "source" to "silent_transport_reattach",
                "elapsedMs" to 5004L,
            )
        }

        val jsonl = recorder.connectionLogJsonl()
        val bytes = jsonl.toByteArray(Charsets.UTF_8).size
        val markers = jsonl.mirroredLines().map { it.getJSONObject("metadata").getInt("marker") }
        // What the recorder ACTUALLY persisted. NB: this fixture bursts 600 events
        // through the recorder's 256-slot `trySend` channel, so the recorder itself
        // legitimately drops some under backpressure (its documented overflow
        // behaviour — it records `diagnostics/recorder_overflow`). Asserting
        // against the recorded stream rather than against 0..599 keeps this test
        // about the CAP instead of about backpressure.
        val recorded = recorder.readEvents(DiagnosticEventFilter(category = "connection"))
            .map { it.metadata["marker"] as Int }

        assertTrue(
            "the payload is re-uploaded on every transport-up over the maintainer's mobile " +
                "data — it must stay within the budget, but was $bytes bytes",
            bytes <= MirroredDiagnostics.PAYLOAD_BUDGET_BYTES,
        )
        assertTrue("the cap must actually have engaged for this fixture", markers.size < recorded.size)
        assertTrue("something must survive the cap", markers.isNotEmpty())
        // Truncate OLDEST: what survives is exactly the NEWEST slice of the
        // recorded stream — the drop that just happened, which is the entire
        // point of the mirror.
        assertEquals(recorded.takeLast(markers.size), markers)
    }

    /**
     * The cap's exact semantics, deterministically — no recorder backpressure in
     * the way. [MirroredDiagnostics.render] is the real production renderer that
     * [DiagnosticRecorder.connectionLogJsonl] delegates to, not a proxy.
     */
    @Test
    fun `render keeps the contiguous newest slice that fits the budget`() {
        val events = (0 until 100).map { i ->
            DiagnosticsEvent(
                sequence = i.toLong(),
                wallClockTime = java.time.Instant.EPOCH.plusSeconds(i.toLong()),
                monotonicTimestampNanos = i.toLong(),
                category = "connection",
                name = "reconnect_fail",
                metadata = mapOf("cause" to "attach_not_ready", "marker" to i),
            )
        }
        // Size from a LATE event: markers/sequences 90..99 are all two digits, so
        // every line in the expected window is exactly this wide.
        val oneLine = DiagnosticEventJson.encode(events.last()).toByteArray(Charsets.UTF_8).size + 1

        // A budget with room for exactly 10 lines.
        val jsonl = MirroredDiagnostics.render(events, budgetBytes = oneLine * 10)
        val markers = jsonl.split("\n").filter { it.isNotBlank() }
            .map { JSONObject(it).getJSONObject("metadata").getInt("marker") }

        assertEquals("the contiguous NEWEST slice, oldest truncated", (90..99).toList(), markers)
    }

    /**
     * A single event larger than the entire budget must still be emitted: a blank
     * payload makes the mirror no-op, which would lose the event completely.
     * Better one oversized line than a silently-dropped drop.
     */
    @Test
    fun `render emits an oversized single event rather than a blank payload`() {
        val event = DiagnosticsEvent(
            sequence = 1L,
            wallClockTime = java.time.Instant.EPOCH,
            monotonicTimestampNanos = 1L,
            category = "connection",
            name = "reconnect_fail",
            metadata = mapOf("cause" to "attach_not_ready"),
        )

        val jsonl = MirroredDiagnostics.render(listOf(event), budgetBytes = 1)

        assertEquals(1, jsonl.split("\n").filter { it.isNotBlank() }.size)
        assertTrue(jsonl.contains("attach_not_ready"))
    }

    /**
     * Recording off ⇒ nothing recorded ⇒ nothing mirrored. The Settings toggle
     * still governs the host mirror.
     */
    @Test
    fun `nothing is mirrored when diagnostics recording is disabled`() = runTest {
        settingsRepository.setDiagnosticsRecordingEnabled(false)
        val recorder = newRecorder()

        DiagnosticEvents.record("connection", "reconnect_fail", "cause" to "attach_not_ready")
        ReconnectCauseTrail.record(stage = "lease_transport", outcome = "down")

        assertEquals("", recorder.connectionLogJsonl())
    }
}
