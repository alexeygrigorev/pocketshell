package com.pocketshell.app.tmux

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
 * Issue #1526 — Slice S1 (verify-before-resend), the composer/agent-payload lane
 * at the DELIVERY level.
 *
 * The recurrence class: #961 deduped the outbound QUEUE (one queued row per
 * logical prompt), but the store's exactly-once guarantee ends at the client —
 * when a send times out/drops AFTER the paste ran server-side (the ambiguous
 * outcome every flap manufactures — audit A1/A2 on #1526), the row requeues and
 * the reconnect auto-flush re-pasted the full payload with no check of what
 * already landed. The maintainer saw prompts delivered TWICE.
 *
 * These tests drive [TmuxSessionViewModel.sendAgentPayloadToPaneResult] — the
 * single delivery chokepoint every composer resend path funnels into (manual
 * retry, Resend all, the #900 reconnect auto-flush via `composerSendHandler`) —
 * against a [FakeTmuxClient] acting as the server: a command recorded in
 * [FakeTmuxClient.sentCommands] HAS run server-side (the fake records BEFORE
 * throwing, exactly like a real `send-keys` that executes before its exec
 * result is lost).
 *
 * RED on base: the resend re-pastes, server paste occurrence == 2.
 * GREEN with S1: the resend probes first (#869 ack needle over `capture-pane`)
 * and, finding the payload already landed, submits ONLY Enter — occurrence
 * stays 1.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class OutboundExactlyOnceDeliveryTest : TmuxSessionViewModelTestBase() {

    private fun claudeDetection(): AgentDetection = AgentDetection(
        agent = AgentKind.ClaudeCode,
        sourcePath = "/home/u/.claude/sessions/abc.jsonl",
        sessionId = "abc",
        confidence = AgentDetection.Confidence.ProcessConfirmed,
    )

    private fun FakeTmuxClient.pasteCount(payload: String): Int =
        sentCommands.count { it.startsWith("send-keys -l -t %0") && it.contains(payload) }

    private fun FakeTmuxClient.enterCount(): Int =
        sentCommands.count { it == "send-keys -t %0 Enter" }

    private fun newConnectedVm(client: FakeTmuxClient): TmuxSessionViewModel {
        val vm = newVm()
        vm.attachClientForTest(client)
        vm.startAgentConversationForTest("%0", claudeDetection())
        vm.setAgentSubmitEnterDelayForTest(0)
        vm.setAgentSubmitAckTimeoutForTest(50)
        return vm
    }

    /**
     * The load-bearing exactly-once case (audit cut point (c)): the paste LANDED
     * server-side, the submit Enter's exec result was lost (timeout), the row
     * requeued, and the flush re-dispatched the same payload. The resend must
     * PROBE and, seeing the payload already in the pane, NOT re-paste — the
     * server-side paste occurrence stays 1.
     */
    @Test
    fun resendAfterAmbiguousFailureDoesNotRepasteWhenPayloadAlreadyLanded() = runTest(scheduler) {
        val client = FakeTmuxClient()
        // The pane's visible text reflects the landed paste — what the #869
        // needle probe (and the ack gate) will see.
        client.defaultCaptureResponse = CommandResponse(
            number = 0L,
            output = listOf("> deploy the staging build now"),
            isError = false,
        )
        val vm = newConnectedVm(client)

        // Attempt 1: the Enter's exec result is LOST after the server ran the
        // paste (the fake records the command BEFORE throwing — it "ran").
        client.throwOnCommandPrefix = "send-keys -t %0 Enter"
        client.throwOnCommandRemaining = 1
        val first = async { vm.sendAgentPayloadToPaneResult("%0", "deploy the staging build now", AgentKind.ClaudeCode) }
        advanceUntilIdle()
        assertTrue("attempt 1 must surface the ambiguous failure", first.await().isFailure)
        assertEquals("attempt 1 pastes exactly once", 1, client.pasteCount("deploy the staging build now"))

        // The resend (what the reconnect auto-flush / manual retry dispatches).
        val second = async { vm.sendAgentPayloadToPaneResult("%0", "deploy the staging build now", AgentKind.ClaudeCode) }
        advanceUntilIdle()
        assertTrue("the verified resend must succeed", second.await().isSuccess)

        // THE delivery-level assertion (#1526 AC): server-side occurrence == 1.
        // On base (no verify-before-resend) the resend re-pastes and this is 2.
        assertEquals(
            "the payload must reach the server exactly ONCE across the ambiguous " +
                "failure + resend (verify-before-resend must suppress the re-paste)",
            1,
            client.pasteCount("deploy the staging build now"),
        )
        assertTrue("the resend must still submit (Enter-only)", client.enterCount() >= 2)
    }

    /**
     * No over-suppression: when the probe shows the payload never landed (the
     * failure happened before the paste reached the pane), the resend must go
     * out in full — verify-before-resend must not turn at-least-once into
     * at-most-once.
     */
    @Test
    fun resendRepastesInFullWhenProbeShowsPayloadNeverLanded() = runTest(scheduler) {
        val client = FakeTmuxClient()
        // The pane never shows the payload (it did NOT land).
        client.defaultCaptureResponse = CommandResponse(
            number = 0L,
            output = listOf("$ "),
            isError = false,
        )
        val vm = newConnectedVm(client)

        client.throwOnCommandPrefix = "send-keys -t %0 Enter"
        client.throwOnCommandRemaining = 1
        val first = async { vm.sendAgentPayloadToPaneResult("%0", "restart the worker pool", AgentKind.ClaudeCode) }
        advanceUntilIdle()
        assertTrue(first.await().isFailure)

        val second = async { vm.sendAgentPayloadToPaneResult("%0", "restart the worker pool", AgentKind.ClaudeCode) }
        advanceUntilIdle()
        assertTrue(second.await().isSuccess)

        assertEquals(
            "a payload the probe shows NOT landed must be re-pasted in full",
            2,
            client.pasteCount("restart the worker pool"),
        )
    }

    /**
     * Probe-unknown safety (the probe rides the same flaky lane): a resend whose
     * probe cannot decide must NOT resend — it fails so the durable row stays
     * queued for a later verified retry, never "unknown ⇒ resend".
     */
    @Test
    fun resendWithUnknownProbeOutcomeDoesNotResend() = runTest(scheduler) {
        val client = FakeTmuxClient()
        client.defaultCaptureResponse = CommandResponse(
            number = 0L,
            output = listOf("> ship the release notes draft"),
            isError = false,
        )
        val vm = newConnectedVm(client)

        client.throwOnCommandPrefix = "send-keys -t %0 Enter"
        client.throwOnCommandRemaining = 1
        val first = async { vm.sendAgentPayloadToPaneResult("%0", "ship the release notes draft", AgentKind.ClaudeCode) }
        advanceUntilIdle()
        assertTrue(first.await().isFailure)
        val sendKeysAfterFirst = client.sentCommands.count { it.startsWith("send-keys") }

        // The probe's capture-pane now FAILS (flaky lane) — outcome unknown.
        client.throwOnCommandPrefix = "capture-pane"
        client.throwOnCommandRemaining = 1
        val second = async { vm.sendAgentPayloadToPaneResult("%0", "ship the release notes draft", AgentKind.ClaudeCode) }
        advanceUntilIdle()
        assertTrue("an unknown probe outcome must FAIL the dispatch (row stays queued)", second.await().isFailure)
        assertEquals(
            "an unknown probe outcome must send NOTHING (no blind resend)",
            sendKeysAfterFirst,
            client.sentCommands.count { it.startsWith("send-keys") },
        )

        // A later retry whose probe CAN decide resolves it: payload visible ⇒
        // Enter-only, still exactly one paste.
        val third = async { vm.sendAgentPayloadToPaneResult("%0", "ship the release notes draft", AgentKind.ClaudeCode) }
        advanceUntilIdle()
        assertTrue(third.await().isSuccess)
        assertEquals(1, client.pasteCount("ship the release notes draft"))
    }

    /**
     * No false dedup of DELIBERATE repeat sends: a payload that delivered
     * successfully clears its ledger entry, so sending the same text again is a
     * normal full send (2 pastes), even though the pane still shows the text.
     */
    @Test
    fun deliberateRepeatSendAfterSuccessIsNotSuppressed() = runTest(scheduler) {
        val client = FakeTmuxClient()
        client.defaultCaptureResponse = CommandResponse(
            number = 0L,
            output = listOf("> run the smoke suite again"),
            isError = false,
        )
        val vm = newConnectedVm(client)

        val first = async { vm.sendAgentPayloadToPaneResult("%0", "run the smoke suite again", AgentKind.ClaudeCode) }
        advanceUntilIdle()
        assertTrue(first.await().isSuccess)

        val second = async { vm.sendAgentPayloadToPaneResult("%0", "run the smoke suite again", AgentKind.ClaudeCode) }
        advanceUntilIdle()
        assertTrue(second.await().isSuccess)

        assertEquals(
            "a deliberate repeat send after a SUCCESSFUL delivery must not be " +
                "suppressed by the ledger",
            2,
            client.pasteCount("run the smoke suite again"),
        )
    }
}
