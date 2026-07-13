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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #1541 (finding P9): the END-TO-END back-navigation duplicate — the common
 * real scenario, not process death. Send → tap **Back** mid-delivery → the volatile
 * exactly-once ledger (held on `TmuxSessionViewModel`) dies with the VM → reopen the
 * session → a FRESH VM/ledger with no memory blindly re-pastes → **server occurrence
 * 2**.
 *
 * This drives the real path: TWO `TmuxSessionViewModel` instances sharing the
 * process-cached `outbound_queue` SharedPreferences (exactly what a VM-clear
 * rebuild sees), with a [FakeTmuxClient] acting as the server (a recorded
 * `send-keys -l` command HAS pasted server-side). VM #1 sends and its submit-Enter
 * result is lost after the paste landed (the ambiguous mid-send drop); VM #1 is then
 * CLEARED (the #780 synthetic-injection model for back-navigation — NOT process
 * death); VM #2 rebuilds and re-dispatches the SAME row.
 *
 * RED on base (volatile ledger only): VM #2 re-pastes ⇒ [FakeTmuxClient.pasteCount]
 * == 1 (occurrence 2 across the two clients / one server).
 * GREEN with the durable `wireAttempted` flag: VM #2's rebuilt ledger reads the
 * durable flag, runs verify-before-resend, sees the payload already landed, and
 * submits Enter ONLY ⇒ pasteCount == 0 (occurrence stays 1).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class OutboundBackNavExactlyOnceTest : TmuxSessionViewModelTestBase() {

    private val context: Context = ApplicationProvider.getApplicationContext()

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

    /** A VM whose delivery ledger is durable-backed (real app context). */
    private fun newDurableConnectedVm(client: FakeTmuxClient): TmuxSessionViewModel {
        val vm = newVm(applicationContext = context)
        vm.attachClientForTest(client)
        vm.startAgentConversationForTest("%0", claudeDetection())
        vm.setAgentSubmitEnterDelayForTest(0)
        vm.setAgentSubmitAckTimeoutForTest(50)
        return vm
    }

    private fun clientShowing(vararg lines: String): FakeTmuxClient = FakeTmuxClient().apply {
        defaultCaptureResponse = CommandResponse(number = 0L, output = lines.toList(), isError = false)
    }

    /**
     * THE load-bearing back-nav case: the paste landed server-side, the submit
     * Enter's result was lost, the user tapped Back (VM cleared), and the reopened
     * session re-dispatched the row. The durable `wireAttempted` flag must make the
     * rebuilt VM verify-before-resend so the payload reaches the server EXACTLY once.
     */
    @Test
    fun backNavAfterAmbiguousSendDoesNotRepasteOnRebuiltVm() = runTest(scheduler) {
        val payload = "deploy the staging build now"
        // The composer enqueued the row that is being delivered (Queued, matching
        // pane + payload). Same prefs file the VMs' ledgers read.
        val store = SharedPrefsOutboundQueueStore(context)
        store.enqueue("sessA", payload, paneId = "%0", route = OutboundRoute.AgentConversation)

        // VM #1: attempt 1 pastes, the pane shows it (ack passes), then the submit
        // Enter's exec result is lost after the server ran the paste.
        val client1 = clientShowing("> $payload")
        val vm1 = newDurableConnectedVm(client1)
        client1.throwOnCommandPrefix = "send-keys -t %0 Enter"
        client1.throwOnCommandRemaining = 1
        val first = async { vm1.sendAgentPayloadToPaneResult("%0", payload, AgentKind.ClaudeCode) }
        advanceUntilIdle()
        assertTrue("attempt 1 must surface the ambiguous failure", first.await().isFailure)
        assertEquals("attempt 1 pastes exactly once", 1, client1.pasteCount(payload))
        // The wire attempt is now DURABLE on the row (survives the VM-clear).
        assertTrue(store.hasWireAttempt("%0", payload))

        // The user taps BACK mid-delivery → VM #1 is cleared (its volatile ledger
        // dies). #780 model: a synthetic VM-clear, not process death.
        vm1.clearForTest()

        // Reopen the session: a FRESH VM #2 rebuilds the ledger from the durable
        // flag. The reconnect auto-flush re-dispatches the SAME row; the pane still
        // shows the landed payload.
        val client2 = clientShowing("> $payload")
        val vm2 = newDurableConnectedVm(client2)
        val second = async { vm2.sendAgentPayloadToPaneResult("%0", payload, AgentKind.ClaudeCode) }
        advanceUntilIdle()
        assertTrue("the verified resend must succeed", second.await().isSuccess)

        // THE assertion (#1541 AC): the rebuilt VM must NOT re-paste. On base
        // (volatile ledger only) VM #2 has no memory and blindly re-pastes ⇒ 1
        // (server occurrence 2). With the durable flag ⇒ 0 (Enter-only, occurrence 1).
        assertEquals(
            "back-navigation mid-delivery must NOT duplicate: the rebuilt VM reads the " +
                "durable wireAttempted flag and submits Enter only (occurrence stays 1)",
            0,
            client2.pasteCount(payload),
        )
        assertTrue("the rebuilt VM must still submit the pending row (Enter-only)", client2.enterCount() >= 1)
    }

    /**
     * No LOST regression: a genuinely-UNDELIVERED row after back-navigation still
     * delivers on the rebuilt VM (the durable flag makes it VERIFY, and the probe
     * shows it never landed ⇒ a full re-paste, not a silent drop).
     */
    @Test
    fun backNavWithUndeliveredRowStillDeliversOnRebuiltVm() = runTest(scheduler) {
        val payload = "restart the worker pool"
        val store = SharedPrefsOutboundQueueStore(context)
        store.enqueue("sessB", payload, paneId = "%0", route = OutboundRoute.AgentConversation)

        // VM #1: the paste never lands (the send fails before it reaches the pane).
        val client1 = clientShowing("$ ")
        val vm1 = newDurableConnectedVm(client1)
        client1.throwOnCommandPrefix = "send-keys -l -t %0"
        client1.throwOnCommandRemaining = 1
        val first = async { vm1.sendAgentPayloadToPaneResult("%0", payload, AgentKind.ClaudeCode) }
        advanceUntilIdle()
        assertTrue(first.await().isFailure)
        // The attempt was still recorded durably (it reached the wire, outcome lost).
        assertTrue(store.hasWireAttempt("%0", payload))

        vm1.clearForTest()

        // VM #2 rebuilds: the probe shows the payload NEVER landed ⇒ full re-paste,
        // the row is NOT dropped.
        val client2 = clientShowing("$ ")
        val vm2 = newDurableConnectedVm(client2)
        val second = async { vm2.sendAgentPayloadToPaneResult("%0", payload, AgentKind.ClaudeCode) }
        advanceUntilIdle()
        assertTrue(second.await().isSuccess)
        assertEquals(
            "a genuinely-undelivered row after back-navigation must still deliver " +
                "(verify shows NOT landed ⇒ re-paste), never silently dropped",
            1,
            client2.pasteCount(payload),
        )
    }

    /**
     * Subscriber-alive (live-VM retry) path is preserved: WITHOUT a back-nav, a
     * deliberate repeat send after a SUCCESSFUL delivery (row delivered+pruned) is
     * a normal full send, not suppressed by a stale durable flag.
     */
    @Test
    fun deliberateRepeatAfterDeliveredRowIsNotSuppressed() = runTest(scheduler) {
        val payload = "run the smoke suite again"
        val store = SharedPrefsOutboundQueueStore(context)
        val row = store.enqueue("sessC", payload, paneId = "%0", route = OutboundRoute.AgentConversation)

        val client = clientShowing("> $payload")
        val vm = newDurableConnectedVm(client)

        val first = async { vm.sendAgentPayloadToPaneResult("%0", payload, AgentKind.ClaudeCode) }
        advanceUntilIdle()
        assertTrue(first.await().isSuccess)
        assertEquals(1, client.pasteCount(payload))

        // The composer acks delivery: the row is delivered+pruned, dropping the
        // durable flag so a later identical send is not falsely suppressed.
        store.markDelivered(row.id)
        assertFalse(store.hasWireAttempt("%0", payload))

        // A deliberate identical re-send is a normal full send (2 pastes total).
        val second = async { vm.sendAgentPayloadToPaneResult("%0", payload, AgentKind.ClaudeCode) }
        advanceUntilIdle()
        assertTrue(second.await().isSuccess)
        assertEquals(
            "a deliberate repeat after a delivered (pruned) row must not be suppressed",
            2,
            client.pasteCount(payload),
        )
    }
}
