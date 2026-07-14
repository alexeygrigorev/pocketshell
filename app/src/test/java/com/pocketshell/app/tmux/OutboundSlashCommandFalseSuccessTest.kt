package com.pocketshell.app.tmux

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.composer.OutboundRoute
import com.pocketshell.app.composer.SharedPrefsOutboundQueueStore
import com.pocketshell.core.agents.AgentDetection
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.tmux.CommandResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #1577 (reproduce-first, D33/G10) — the SILENT FALSE-SUCCESS drop of a
 * composer send whose payload text is already visible on the agent pane.
 *
 * The maintainer sent `/goal resume` (via the slash palette / composer, NOT the
 * keyboard) to a Codex session whose status line permanently renders
 * `Goal blocked (/goal resume)`. The composer cleared / the row marked Delivered,
 * but the command never reached the agent — a bare Enter was sent instead of the
 * payload.
 *
 * Root cause (proven by audit): the composer enqueues the row and immediately
 * `markInFlight`s it — and `claimedForAttempt()` USED TO stamp the durable
 * `wireAttempted` flag at CLAIM time, BEFORE any byte was pushed. So the #1526
 * verify-before-resend probe ran on the FIRST send, and its whole-viewport
 * needle (`/goalresume`) false-matched the ever-visible status line ⇒
 * `AlreadyLanded` ⇒ Enter-only, no paste, reported success.
 *
 * These tests drive the REAL composer path (`enqueue` + `markInFlight` on the
 * durable store, then `sendAgentPayloadToPaneResult` on a durable-backed VM),
 * against a [FakeTmuxClient] whose `capture-pane` shows the pre-existing payload
 * text — the exact on-device state.
 *
 * RED on base: the payload is NEVER pasted (only Enter), yet the send reports
 * success (`pasteCount == 0`).
 * GREEN with the fix (claim no longer stamps `wireAttempted`; the probe is
 * baseline-aware): the FIRST send pastes the payload onto the wire
 * (`pasteCount == 1`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class OutboundSlashCommandFalseSuccessTest : TmuxSessionViewModelTestBase() {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun codexDetection(): AgentDetection = AgentDetection(
        agent = AgentKind.Codex,
        sourcePath = "/home/u/.codex/sessions/rollout.jsonl",
        sessionId = "rollout",
        confidence = AgentDetection.Confidence.ProcessConfirmed,
    )

    private fun FakeTmuxClient.literalPasteCount(payload: String): Int =
        sentCommands.count { it.startsWith("send-keys -l -t %0") && it.contains(payload) }

    private fun FakeTmuxClient.enterCount(): Int =
        sentCommands.count { it == "send-keys -t %0 Enter" }

    /** A durable-backed VM (real app context ⇒ the store-backed delivery ledger). */
    private fun newDurableConnectedVm(client: FakeTmuxClient): TmuxSessionViewModel {
        val vm = newVm(applicationContext = context)
        vm.attachClientForTest(client)
        vm.startAgentConversationForTest("%0", codexDetection())
        vm.setAgentSubmitEnterDelayForTest(0)
        vm.setAgentSubmitAckTimeoutForTest(50)
        return vm
    }

    private fun clientShowing(vararg lines: String): FakeTmuxClient = FakeTmuxClient().apply {
        defaultCaptureResponse = CommandResponse(number = 0L, output = lines.toList(), isError = false)
    }

    /**
     * THE reported case: `/goal resume` to a Codex session whose status line shows
     * `Goal blocked (/goal resume)`. The composer enqueues + claims the row (the
     * markInFlight the real send path runs) and then delivers it.
     *
     * RED on base: `markInFlight` stamped the durable wire attempt ⇒ the first send
     * probes ⇒ the status-line needle false-matches ⇒ Enter-only, no paste (a
     * silent false-success). GREEN: the payload is pasted onto the wire.
     */
    @Test
    fun firstSendOfSlashCommandVisibleOnStatusLinePastesThePayload() = runTest(scheduler) {
        val payload = "/goal resume"
        // The composer path: enqueue the row, then markInFlight (exactly what
        // enqueueOutboundSend does before dispatch).
        val store = SharedPrefsOutboundQueueStore(context)
        val row = store.enqueue("sessGoal", payload, paneId = "%0", route = OutboundRoute.AgentPayload)
        store.markInFlight(row.id)

        // The Codex pane permanently advertises the command it wants sent.
        val client = clientShowing("gpt-5.6-sol medium · Context 42% · Goal blocked (/goal resume)")
        val vm = newDurableConnectedVm(client)

        val result = async { vm.sendAgentPayloadToPaneResult("%0", payload, AgentKind.Codex) }
        advanceUntilIdle()
        assertTrue("the send reports success either way (the drop is silent)", result.await().isSuccess)

        assertEquals(
            "the FIRST send of a slash command must PASTE the payload onto the wire — " +
                "not skip it as a false-`AlreadyLanded` because the status line already " +
                "shows the command (RED on base: pasteCount == 0, Enter-only)",
            1,
            client.literalPasteCount(payload),
        )
    }

    /**
     * Class coverage (a): the same via the Conversation `Echo` route
     * ([sendToAgentPaneResult] → the SAME `sendAgentPayloadToPaneResult` chokepoint).
     */
    @Test
    fun firstSendViaEchoRouteWithVisiblePayloadPastesThePayload() = runTest(scheduler) {
        val payload = "/goal resume"
        val store = SharedPrefsOutboundQueueStore(context)
        val row = store.enqueue("sessEcho", payload, paneId = "%0", route = OutboundRoute.AgentConversation)
        store.markInFlight(row.id)

        val client = clientShowing("Goal blocked (/goal resume)")
        val vm = newDurableConnectedVm(client)

        val result = async { vm.sendToAgentPaneResult("%0", payload) }
        advanceUntilIdle()
        assertTrue(result.await().isSuccess)

        assertEquals(
            "the Echo/Conversation route must also paste the payload on the first send",
            1,
            client.literalPasteCount(payload),
        )
    }

    /**
     * Class coverage (b): a SHORT payload ("ok") already visible on the pane. The
     * composer needle has no minimum length, so a presence-only probe would drop
     * every short confirmation whose text is on screen.
     */
    @Test
    fun firstSendOfShortPayloadAlreadyVisiblePastesThePayload() = runTest(scheduler) {
        val payload = "ok"
        val store = SharedPrefsOutboundQueueStore(context)
        val row = store.enqueue("sessOk", payload, paneId = "%0", route = OutboundRoute.AgentPayload)
        store.markInFlight(row.id)

        // "ok" is already visible (e.g. echoed in the transcript / a prior line).
        val client = clientShowing("> ok, running the smoke suite")
        val vm = newDurableConnectedVm(client)

        val result = async { vm.sendAgentPayloadToPaneResult("%0", payload, AgentKind.Codex) }
        advanceUntilIdle()
        assertTrue(result.await().isSuccess)

        assertEquals(
            "a short payload already visible on the pane must still be pasted on the first send",
            1,
            client.literalPasteCount(payload),
        )
    }

    /**
     * Class coverage (c) — the GENUINE dedupe still works (proves the fix did NOT
     * break #1526 exactly-once). A real resend whose payload DID land once (the
     * paste ADDED an occurrence over the pre-send baseline) must be suppressed to
     * Enter-only — the payload reaches the agent EXACTLY once across the ambiguous
     * failure + resend.
     */
    @Test
    fun genuineResendOfLandedPayloadIsDedupedToExactlyOnce() = runTest(scheduler) {
        val payload = "/goal resume"
        val store = SharedPrefsOutboundQueueStore(context)
        val row = store.enqueue("sessDedupe", payload, paneId = "%0", route = OutboundRoute.AgentPayload)
        store.markInFlight(row.id)

        // The unit VM has no attached emulator render, so the pre-send baseline is 0
        // (the payload is not on the local render) and NO baseline capture round-trip
        // is issued. After the paste lands the pane shows the payload ⇒ the resend
        // probe reads count 1 > baseline 0 ⇒ AlreadyLanded.
        val client = clientShowing("$ /goal resume")
        val vm = newDurableConnectedVm(client)

        // Attempt 1: the paste lands, the submit Enter's exec result is lost.
        client.throwOnCommandPrefix = "send-keys -t %0 Enter"
        client.throwOnCommandRemaining = 1
        val first = async { vm.sendAgentPayloadToPaneResult("%0", payload, AgentKind.Codex) }
        advanceUntilIdle()
        assertTrue("attempt 1 must surface the ambiguous failure", first.await().isFailure)
        assertEquals("attempt 1 pastes exactly once", 1, client.literalPasteCount(payload))

        // The resend (reconnect auto-flush / manual retry) probes: count (1) exceeds
        // the baseline (0) ⇒ AlreadyLanded ⇒ Enter-only, NO second paste.
        val second = async { vm.sendAgentPayloadToPaneResult("%0", payload, AgentKind.Codex) }
        advanceUntilIdle()
        assertTrue("the verified resend must succeed", second.await().isSuccess)
        assertEquals(
            "a genuine resend of a payload that already LANDED must be deduped to " +
                "exactly ONE paste (the baseline-aware probe still catches real duplicates)",
            1,
            client.literalPasteCount(payload),
        )
        assertTrue("the resend must still submit (Enter-only)", client.enterCount() >= 2)
    }
}
