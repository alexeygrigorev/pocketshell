package com.pocketshell.app.composer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.diagnostics.DIAGNOSTICS_EXPORT_CACHE_DIR
import com.pocketshell.app.diagnostics.DiagnosticEventSink
import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.app.diagnostics.DiagnosticRecorder
import com.pocketshell.app.diagnostics.MirroredDiagnostics
import com.pocketshell.app.settings.SettingsRepository
import com.pocketshell.app.tmux.OutboundQueueAutoFlushController
import com.pocketshell.app.tmux.outboundBudgetTestComposer
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
 * Issue #1682 (Track A + Track C): the composer outbound queue emits NO
 * diagnostics today — the drain-gate decision path records either `action`-only
 * chatter or nothing at all — so "the queue clogs because it thinks the
 * connection is gone" is INVISIBLE in a real device trace. Track C found the
 * clog is caused by the drain being hard-gated on the `ConnectionStatus` enum
 * (`TmuxSessionScreen.kt:425`), so the smoking-gun signal is enum-vs-transport
 * disagreement.
 *
 * These tests assert the new mirrored `queue` category reaches the HOST
 * connection log — the exact payload `ConnectionLogHostMirror` uploads
 * ([DiagnosticRecorder.connectionLogJsonl]) — for the four load-bearing events:
 * `enqueue`, `window_flip` (with `sessionLive` + the `ConnectionStatus` that
 * drove it), `drain_attempt` (`dispatched` / `not_live` / `all_suppressed`),
 * and `row_state` (esp. the park-at-6 `budget_exhausted`).
 *
 * ## Why the assertions are what they are
 *
 * - **No proxy** — every assertion runs through the REAL global sink and the
 *   REAL production payload builder [DiagnosticRecorder.connectionLogJsonl], via
 *   the REAL emit sites ([ComposerQueueDiagnostics], the production
 *   [OutboundQueueAutoFlushController], the [PromptComposerViewModel] drain
 *   helper), not a hand-built event.
 * - **Redaction** — the enqueue/row_state events carry ids + sizes only; the
 *   test asserts the raw prompt text and the raw session key (a directory path
 *   by construction) NEVER appear in the host payload.
 * - **Category discipline** — a `queue` event reaches the host like `connection`
 *   does, while the excluded `action` chatter does NOT.
 * - **No `assumeTrue`** anywhere: every assertion is load-bearing on every runner.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ComposerQueueDiagnosticsHostMirrorTest {

    private lateinit var context: Context
    private lateinit var settingsRepository: SettingsRepository

    private val secretText = "deploy the prod key sk-supersecret-please-never-log-me"
    private val sessionPath = "alexey@dev/home/alexey/git/pocketshell"

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

    private fun newRecorder(): DiagnosticRecorder =
        DiagnosticRecorder(context, settingsRepository).also { DiagnosticEvents.install(it) }

    private fun String.mirroredLines(): List<JSONObject> =
        split("\n").filter { it.isNotBlank() }.map(::JSONObject)

    private fun item(
        id: String,
        state: OutboundState = OutboundState.Queued,
        attemptCount: Int = 0,
        text: String = secretText,
    ): OutboundItem = OutboundItem(
        id = id,
        sessionKey = sessionPath,
        cleanText = text,
        createdAtMs = 0L,
        state = state,
        attemptCount = attemptCount,
        route = OutboundRoute.AgentPayload,
        agentKind = "claude",
    )

    /** enqueue reaches the host log — with the id + size, and NEVER the raw text. */
    @Test
    fun `enqueue reaches the host connection log with ids and sizes only`() = runTest {
        val recorder = newRecorder()

        ComposerQueueDiagnostics.enqueue(item(id = "row-1"))

        val jsonl = recorder.connectionLogJsonl()
        val event = jsonl.mirroredLines().singleOrNull {
            it.getString("category") == MirroredDiagnostics.QUEUE_CATEGORY &&
                it.getString("name") == "enqueue"
        }
        assertTrue("queue/enqueue must reach the host log. Payload was: $jsonl", event != null)
        val metadata = event!!.getJSONObject("metadata")
        assertEquals("row-1", metadata.getString("itemId"))
        assertEquals(secretText.length, metadata.getInt("textLength"))
        assertEquals("AgentPayload", metadata.getString("route"))
        // Redaction: NO raw prompt text, NO raw session directory path.
        assertFalse("raw prompt text must never reach the host log: $jsonl", jsonl.contains(secretText))
        assertFalse("raw session path must never reach the host log: $jsonl", jsonl.contains(sessionPath))
        assertTrue("session must be a fingerprint: $jsonl", metadata.getString("sessionFingerprint").startsWith("sha256:"))
    }

    /**
     * window_flip carries `sessionLive` AND the `ConnectionStatus` that drove it —
     * the enum-vs-transport disagreement capture. This is the Track C smoking gun.
     */
    @Test
    fun `window_flip reaches the host log with sessionLive and the driving status`() = runTest {
        val recorder = newRecorder()

        val controller = OutboundQueueAutoFlushController.boundTo(outboundBudgetTestComposer())
        // The drain gate closes while the wire may still be alive (Reconnecting enum).
        controller.onConnectionWindowChanged(
            sessionLive = false,
            targetSessionId = sessionPath,
            connectionStatusLabel = "Reconnecting",
            requeueStaleInFlight = {},
        )

        val jsonl = recorder.connectionLogJsonl()
        val event = jsonl.mirroredLines().singleOrNull {
            it.getString("category") == MirroredDiagnostics.QUEUE_CATEGORY &&
                it.getString("name") == "window_flip"
        }
        assertTrue("queue/window_flip must reach the host log. Payload was: $jsonl", event != null)
        val metadata = event!!.getJSONObject("metadata")
        assertFalse("drain gate closed", metadata.getBoolean("sessionLive"))
        assertEquals("Reconnecting", metadata.getString("connectionStatus"))
        assertFalse("raw session path must never reach the host log: $jsonl", jsonl.contains(sessionPath))
    }

    /**
     * not_live drain: a row waits behind a shut gate and is NEVER attempted — the
     * clog's core symptom. Only recorded when there is actually pending work.
     */
    @Test
    fun `a drain tick against a shut gate records not_live`() = runTest {
        val recorder = newRecorder()

        val controller = OutboundQueueAutoFlushController.boundTo(outboundBudgetTestComposer())
        controller.onConnectionWindowChanged(
            sessionLive = false,
            targetSessionId = sessionPath,
            connectionStatusLabel = "Reconnecting",
            requeueStaleInFlight = {},
        )
        // Gate shut, a row is waiting → the drain does NOT attempt it.
        controller.onQueueSnapshotChanged(
            sessionLive = false,
            retryNext = { null },
            hasPendingWork = { true },
        )

        val jsonl = recorder.connectionLogJsonl()
        val drain = jsonl.mirroredLines().singleOrNull {
            it.getString("name") == "drain_attempt"
        }
        assertTrue("queue/drain_attempt not_live must reach the host log. Payload was: $jsonl", drain != null)
        assertEquals("not_live", drain!!.getJSONObject("metadata").getString("outcome"))
    }

    /** An idle shut gate (no pending work) records NO drain tick — no host spam. */
    @Test
    fun `an idle shut gate records no drain tick`() = runTest {
        val recorder = newRecorder()

        val controller = OutboundQueueAutoFlushController.boundTo(outboundBudgetTestComposer())
        controller.onConnectionWindowChanged(
            sessionLive = false,
            targetSessionId = sessionPath,
            connectionStatusLabel = "Reconnecting",
            requeueStaleInFlight = {},
        )
        controller.onQueueSnapshotChanged(
            sessionLive = false,
            retryNext = { null },
            hasPendingWork = { false },
        )

        val names = recorder.connectionLogJsonl().mirroredLines().map { it.getString("name") }
        assertFalse("an idle closed window must not record a drain tick: $names", "drain_attempt" in names)
    }

    /**
     * The drain cycle: a budget-exhausted head is PARKED (`row_state` →
     * `budget_exhausted`) and the fresh tail is `dispatched`. Class coverage for
     * the park-at-[OUTBOUND_MAX_AUTO_ATTEMPTS] the issue calls out.
     */
    @Test
    fun `the drain cycle records the park and the dispatched outcome`() = runTest {
        val recorder = newRecorder()

        val parked = item(id = "parked", attemptCount = OUTBOUND_MAX_AUTO_ATTEMPTS)
        val fresh = item(id = "fresh", attemptCount = 0)
        val items = listOf(parked, fresh)
        val plan = items.planComposerAutoFlush(sessionPath)
        // The plan parks the exhausted head and dispatches the fresh tail.
        assertEquals(listOf("parked"), plan.parkIds)
        assertEquals("fresh", plan.nextId)

        ComposerQueueDiagnostics.recordDrainCycle(
            sessionKey = sessionPath,
            items = items,
            plan = plan,
            suppressedCount = 0,
            dispatched = true,
        )

        val lines = recorder.connectionLogJsonl().mirroredLines()
        val park = lines.singleOrNull {
            it.getString("name") == "row_state" &&
                it.getJSONObject("metadata").getString("itemId") == "parked"
        }
        assertTrue("the park-at-6 row_state must reach the host log. Payload was: $lines", park != null)
        assertEquals("budget_exhausted", park!!.getJSONObject("metadata").getString("reason"))
        assertEquals("Failed", park.getJSONObject("metadata").getString("toState"))
        assertEquals(OUTBOUND_MAX_AUTO_ATTEMPTS.toLong(), park.getJSONObject("metadata").getLong("attemptCount"))

        val drain = lines.single { it.getString("name") == "drain_attempt" }
        assertEquals("dispatched", drain.getJSONObject("metadata").getString("outcome"))
        assertEquals("fresh", drain.getJSONObject("metadata").getString("dispatchedId"))
        assertEquals(1L, drain.getJSONObject("metadata").getLong("parkedCount"))
    }

    /** All eligible rows suppressed (within backoff) → all_suppressed, no dispatch. */
    @Test
    fun `a fully-suppressed drain cycle records all_suppressed`() = runTest {
        val recorder = newRecorder()

        val only = item(id = "only")
        val items = listOf(only)
        // Suppress the one eligible row → planComposerAutoFlush yields no next id.
        val plan = items.planComposerAutoFlush(sessionPath, excludingIds = setOf("only"))
        assertEquals(null, plan.nextId)

        ComposerQueueDiagnostics.recordDrainCycle(
            sessionKey = sessionPath,
            items = items,
            plan = plan,
            suppressedCount = 1,
            dispatched = false,
        )

        val drain = recorder.connectionLogJsonl().mirroredLines()
            .single { it.getString("name") == "drain_attempt" }
        assertEquals("all_suppressed", drain.getJSONObject("metadata").getString("outcome"))
        assertEquals(1L, drain.getJSONObject("metadata").getLong("suppressedCount"))
    }

    /** row_state transitions reach the host log with reason + post-transition budget. */
    @Test
    fun `a row_state transition reaches the host log`() = runTest {
        val recorder = newRecorder()

        item(id = "claimed-row", state = OutboundState.InFlight, attemptCount = 1)
            .recordQueueRowState("Queued", "InFlight", "claimed")

        val event = recorder.connectionLogJsonl().mirroredLines().single {
            it.getString("name") == "row_state"
        }
        val metadata = event.getJSONObject("metadata")
        assertEquals("claimed-row", metadata.getString("itemId"))
        assertEquals("Queued", metadata.getString("fromState"))
        assertEquals("InFlight", metadata.getString("toState"))
        assertEquals("claimed", metadata.getString("reason"))
        assertEquals(1L, metadata.getLong("attemptCount"))
    }

    /**
     * Category discipline: `queue` is mirrored WHOLE (like `connection`), while
     * the device-only `action` chatter is NOT — the whole point of a mirrored
     * category vs the excluded one.
     */
    @Test
    fun `the queue category is mirrored while action chatter is not`() = runTest {
        val recorder = newRecorder()

        ComposerQueueDiagnostics.enqueue(item(id = "row-a"))
        DiagnosticEvents.record("action", "composer_send_deferred_to_queue", "attachmentCount" to 0)

        val categories = recorder.connectionLogJsonl().mirroredLines().map { it.getString("category") }
        assertTrue("queue must be mirrored: $categories", MirroredDiagnostics.QUEUE_CATEGORY in categories)
        assertFalse("action chatter must stay device-only: $categories", "action" in categories)
    }
}
