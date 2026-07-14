package com.pocketshell.app.tmux

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.composer.InMemoryOutboundQueueStore
import com.pocketshell.app.composer.OutboundItem
import com.pocketshell.core.agents.AgentDetection
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.tmux.CommandResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #1587 (H3) — the two-store lost-flag race, at the VM wiring level.
 *
 * `TmuxSessionViewModel`'s verify-before-resend ledger used to `new` up a SECOND
 * `SharedPrefsOutboundQueueStore` from the app context — a DISTINCT instance, with
 * its OWN lock, over the same `outbound_queue` SharedPreferences blob the composer
 * and the 15s orphan sweep already own via the Hilt @Singleton. A `markWireAttempted`
 * (this ledger) racing a `requeueStaleInFlight` orphan sweep (the singleton) is a
 * last-writer-wins lost update: the durable `wireAttempted` flag is clobbered and a
 * rebuilt ledger blind re-pastes after a VM-clear (the reopened duplicate class).
 *
 * The fix threads the INJECTED @Singleton store into the ledger (one instance, one
 * lock). This test proves the WIRING behaviourally: it injects a spy store (the SAME
 * object the sweep would read) and drives a real agent send; the VM's ledger MUST
 * record the wire attempt on THAT spy.
 *
 * RED on base (the VM news up its own SharedPrefsOutboundQueueStore from the context
 * — reproduce by pointing the ledger at a context-derived store instead of the
 * injected one): the spy is never touched ⇒ `wireMarks == 0` / `hasWireAttempt ==
 * false`. GREEN (injected singleton threaded through): the spy records the flag.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class Issue1587SingleStoreWiringTest : TmuxSessionViewModelTestBase() {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun claudeDetection(): AgentDetection = AgentDetection(
        agent = AgentKind.ClaudeCode,
        sourcePath = "/home/u/.claude/sessions/abc.jsonl",
        sessionId = "abc",
        confidence = AgentDetection.Confidence.ProcessConfirmed,
    )

    /** A spy over the real in-memory store that counts the durable wire-attempt writes. */
    private class CountingOutboundQueueStore : InMemoryOutboundQueueStore() {
        var wireMarks: Int = 0

        override fun markWireAttempted(
            paneId: String,
            payload: String,
            atMs: Long,
            baselineCount: Int?,
        ): OutboundItem? {
            wireMarks += 1
            return super.markWireAttempted(paneId, payload, atMs, baselineCount)
        }
    }

    @Test
    fun vmLedgerRecordsWireAttemptOnTheInjectedSingletonStoreTheSweepUses() = runTest(scheduler) {
        val store = CountingOutboundQueueStore()
        val paneId = "%0"
        val payload = "wire this exactly once please"
        // The composer's persisted row the sweep would also see.
        store.enqueue("sessA", payload, paneId = paneId)

        val client = FakeTmuxClient().apply {
            // The ack gate sees the pasted payload immediately (fresh payload ⇒ baseline 0).
            defaultCaptureResponse =
                CommandResponse(number = 0L, output = listOf("> $payload"), isError = false)
        }
        val vm = newVm(applicationContext = context, outboundQueueStore = store)
        vm.attachClientForTest(client)
        vm.startAgentConversationForTest(paneId, claudeDetection())
        vm.setAgentSubmitEnterDelayForTest(0)
        vm.setAgentSubmitAckTimeoutForTest(50)

        val result = async { vm.sendAgentPayloadToPaneResult(paneId, payload, AgentKind.ClaudeCode) }
        advanceUntilIdle()
        assertTrue("the agent send should succeed", result.await().isSuccess)

        assertTrue(
            "the VM's verify-before-resend ledger must record the wire attempt on the INJECTED " +
                "@Singleton store (the SAME instance the orphan sweep uses) — RED on base: the VM " +
                "news up a SECOND store from the context, so this spy is never written",
            store.wireMarks > 0,
        )
        assertTrue(
            "the durable wireAttempted flag must be readable via the SAME store the sweep reads " +
                "(one instance, one lock — no lost-update race)",
            store.hasWireAttempt(paneId, payload),
        )
    }
}
