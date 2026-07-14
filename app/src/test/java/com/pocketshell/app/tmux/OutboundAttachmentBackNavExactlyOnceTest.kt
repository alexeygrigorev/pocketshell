package com.pocketshell.app.tmux

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.composer.DurableAttachmentRef
import com.pocketshell.app.composer.OutboundRoute
import com.pocketshell.app.composer.SharedPrefsOutboundQueueStore
import com.pocketshell.app.composer.appendAttachmentPaths
import com.pocketshell.core.agents.AgentDetection
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.terminal.input.BracketedPaste
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
 * Issue #1554: the attachment-payload variant of the #1541 back-navigation
 * duplicate. #1541 closed the text-only back-nav duplicate with a durable
 * `wireAttempted` flag + verify-before-resend, but the verify-before-resend
 * needle keyed the wire attempt on the ACTUAL on-wire payload while the durable
 * row's match was `cleanText == payload`. For an **attachment-backed send** the
 * on-wire payload is `cleanText` PLUS an appended `"Attached files:"` block of
 * the attachment remote paths ([appendAttachmentPaths] — the exact composition
 * `PromptComposerOutboundSend` uses for its `SendRequest.text`), so it differs
 * from `cleanText`.
 *
 * That mismatch reopened the #1541 duplicate for the attachment lane: the
 * rebuilt ledger read `hasWireAttempt(pane, composedPayload)`, found no row whose
 * `cleanText` equalled the composed payload, concluded "no prior attempt", and
 * blindly re-pasted the attachment prompt (server occurrence 2).
 *
 * This drives the SAME real path as [OutboundBackNavExactlyOnceTest] (two
 * `TmuxSessionViewModel` instances sharing the process-cached `outbound_queue`
 * SharedPreferences a VM-clear rebuild reads) but with an attachment-backed row
 * whose on-wire payload differs from `cleanText`. The #780 synthetic-injection
 * model: a VM-clear ([clearForTest]) stands in for back-navigation, not process
 * death. Class coverage: text-only (no #1541 regression),
 * attachment-differs-from-cleanText, sidecar/multi-attachment, plus the no-LOST
 * guard.
 *
 * RED on base (`matchesWireTarget` = `cleanText == payload`): VM #2 re-pastes the
 * attachment payload ⇒ occurrence 2.
 * GREEN with the attachment-aware needle: VM #2's rebuilt ledger recognizes the
 * landed composed payload, verifies, and submits Enter ONLY ⇒ occurrence stays 1.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class OutboundAttachmentBackNavExactlyOnceTest : TmuxSessionViewModelTestBase() {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun claudeDetection(): AgentDetection = AgentDetection(
        agent = AgentKind.ClaudeCode,
        sourcePath = "/home/u/.claude/sessions/abc.jsonl",
        sessionId = "abc",
        confidence = AgentDetection.Confidence.ProcessConfirmed,
    )

    /**
     * Count the bracketed-paste BODY chunks that carried [payload] to the pane.
     * An attachment payload is multi-line, so the send path frames it as a
     * bracketed paste ([sendBracketedPaste] → `send-keys -H`), NOT a literal
     * `send-keys -l`. The body chunk command is `send-keys -H -t %0 <hex>` where
     * `<hex>` is the space-separated hex of the payload bytes (single chunk while
     * ≤ [BracketedPaste.BODY_CHUNK_BYTES]).
     */
    private fun FakeTmuxClient.bracketedPasteCount(payload: String): Int {
        val bodyHex = BracketedPaste.hex(payload.toByteArray(Charsets.UTF_8))
        return sentCommands.count { it.startsWith("send-keys -H -t %0") && it.contains(bodyHex) }
    }

    private fun FakeTmuxClient.literalPasteCount(payload: String): Int =
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

    /** A client whose `capture-pane` shows [composed]'s lines (ack + probe pass). */
    private fun clientShowingComposed(composed: String): FakeTmuxClient = FakeTmuxClient().apply {
        defaultCaptureResponse = CommandResponse(
            number = 0L,
            output = composed.split('\n'),
            isError = false,
        )
    }

    private fun ref(path: String): DurableAttachmentRef =
        DurableAttachmentRef(remotePath = path, displayName = path.substringAfterLast('/'))

    /**
     * THE load-bearing #1554 case: a single-attachment send whose on-wire payload
     * (`cleanText` + the appended `"Attached files:"` path block) differs from the
     * row's `cleanText`. The paste landed server-side, the submit Enter's result
     * was lost, the user tapped Back (VM cleared), and the reopened session
     * re-dispatched the row. The attachment-aware needle must make the rebuilt VM
     * recognize the landed composed payload and verify-before-resend, so the
     * attachment prompt reaches the server EXACTLY once.
     */
    @Test
    fun backNavAfterAmbiguousAttachmentSendDoesNotRepasteOnRebuiltVm() = runTest(scheduler) {
        val cleanDraft = "please review the attached crash log"
        val attachments = listOf(ref("/home/u/uploads/crash-2026.log"))
        val composed = appendAttachmentPaths(cleanDraft, attachments.map { it.remotePath })
        // The composed on-wire payload MUST differ from the row's cleanText,
        // otherwise this is not exercising the #1554 class.
        assertTrue("attachment payload must differ from cleanText", composed != cleanDraft)

        val store = SharedPrefsOutboundQueueStore(context)
        store.enqueue(
            "sessA",
            cleanDraft,
            attachments = attachments,
            paneId = "%0",
            route = OutboundRoute.AgentConversation,
        )

        // VM #1: attempt 1 pastes the composed payload, the pane shows it (ack
        // passes), then the submit Enter's exec result is lost after the server
        // ran the paste (the ambiguous mid-send drop).
        val client1 = clientShowingComposed(composed)
        val vm1 = newDurableConnectedVm(client1)
        client1.throwOnCommandPrefix = "send-keys -t %0 Enter"
        client1.throwOnCommandRemaining = 1
        val first = async { vm1.sendAgentPayloadToPaneResult("%0", composed, AgentKind.ClaudeCode) }
        advanceUntilIdle()
        assertTrue("attempt 1 must surface the ambiguous failure", first.await().isFailure)
        assertEquals("attempt 1 pastes exactly once", 1, client1.bracketedPasteCount(composed))
        // The wire attempt is now DURABLE on the attachment row (survives VM-clear).
        // RED on base: matchesWireTarget only compares cleanText, so the composed
        // payload never matches the row and this is false.
        assertTrue(
            "the durable wire attempt must be recorded for the composed attachment payload",
            store.hasWireAttempt("%0", composed),
        )

        // Back-nav mid-delivery: VM #1 is cleared (its volatile ledger dies).
        vm1.clearForTest()

        // Reopen: a FRESH VM #2 rebuilds the ledger from the durable flag and
        // re-dispatches the SAME composed payload; the pane still shows it landed.
        val client2 = clientShowingComposed(composed)
        val vm2 = newDurableConnectedVm(client2)
        val second = async { vm2.sendAgentPayloadToPaneResult("%0", composed, AgentKind.ClaudeCode) }
        advanceUntilIdle()
        assertTrue("the verified resend must succeed", second.await().isSuccess)

        // THE assertion (#1554 AC): the rebuilt VM must NOT re-paste the attachment
        // payload. On base (cleanText-only needle) VM #2 has no matching durable
        // record and blindly re-pastes ⇒ 1 (server occurrence 2). With the
        // attachment-aware needle ⇒ 0 (Enter-only, occurrence 1).
        assertEquals(
            "attachment back-navigation mid-delivery must NOT duplicate: the rebuilt " +
                "VM recognizes the landed composed payload and submits Enter only",
            0,
            client2.bracketedPasteCount(composed),
        )
        assertTrue("the rebuilt VM must still submit the pending row (Enter-only)", client2.enterCount() >= 1)
    }

    /**
     * Class coverage — sidecar / MULTI-attachment: the composed payload appends a
     * multi-path `"Attached files:"` block, differing further from `cleanText`.
     * The attachment-aware needle must still recognize the landed multi-attachment
     * payload after a back-nav ⇒ occurrence 1.
     */
    @Test
    fun backNavAfterAmbiguousMultiAttachmentSendDoesNotRepasteOnRebuiltVm() = runTest(scheduler) {
        val cleanDraft = "here are the two screenshots"
        val attachments = listOf(
            ref("/home/u/uploads/20260714-090000-01-before.png"),
            ref("/home/u/uploads/20260714-090001-02-after.png"),
        )
        val composed = appendAttachmentPaths(cleanDraft, attachments.map { it.remotePath })
        assertTrue("multi-attachment payload must differ from cleanText", composed != cleanDraft)

        val store = SharedPrefsOutboundQueueStore(context)
        store.enqueue(
            "sessM",
            cleanDraft,
            attachments = attachments,
            paneId = "%0",
            route = OutboundRoute.AgentConversation,
        )

        val client1 = clientShowingComposed(composed)
        val vm1 = newDurableConnectedVm(client1)
        client1.throwOnCommandPrefix = "send-keys -t %0 Enter"
        client1.throwOnCommandRemaining = 1
        val first = async { vm1.sendAgentPayloadToPaneResult("%0", composed, AgentKind.ClaudeCode) }
        advanceUntilIdle()
        assertTrue(first.await().isFailure)
        assertEquals(1, client1.bracketedPasteCount(composed))
        assertTrue(store.hasWireAttempt("%0", composed))

        vm1.clearForTest()

        val client2 = clientShowingComposed(composed)
        val vm2 = newDurableConnectedVm(client2)
        val second = async { vm2.sendAgentPayloadToPaneResult("%0", composed, AgentKind.ClaudeCode) }
        advanceUntilIdle()
        assertTrue(second.await().isSuccess)
        assertEquals(
            "multi-attachment back-navigation must NOT duplicate the composed payload",
            0,
            client2.bracketedPasteCount(composed),
        )
        assertTrue(client2.enterCount() >= 1)
    }

    /**
     * No LOST regression: a genuinely-UNDELIVERED attachment row after
     * back-navigation still delivers on the rebuilt VM. The attachment-aware
     * needle makes it VERIFY, the probe shows it never landed ⇒ a full re-paste of
     * the composed payload, not a silent drop.
     */
    @Test
    fun backNavWithUndeliveredAttachmentRowStillDeliversOnRebuiltVm() = runTest(scheduler) {
        val cleanDraft = "apply this patch"
        val attachments = listOf(ref("/home/u/uploads/fix.patch"))
        val composed = appendAttachmentPaths(cleanDraft, attachments.map { it.remotePath })

        val store = SharedPrefsOutboundQueueStore(context)
        store.enqueue(
            "sessL",
            cleanDraft,
            attachments = attachments,
            paneId = "%0",
            route = OutboundRoute.AgentConversation,
        )

        // VM #1: the paste never lands (the bracketed-paste body send fails before
        // it reaches the pane). The pane shows only a bare prompt.
        val client1 = clientShowingComposed("$ ")
        val vm1 = newDurableConnectedVm(client1)
        client1.throwOnCommandPrefix = "send-keys -H -t %0"
        client1.throwOnCommandRemaining = 1
        val first = async { vm1.sendAgentPayloadToPaneResult("%0", composed, AgentKind.ClaudeCode) }
        advanceUntilIdle()
        assertTrue(first.await().isFailure)
        // The attempt still reached the wire (outcome lost) ⇒ durable record exists.
        assertTrue(store.hasWireAttempt("%0", composed))

        vm1.clearForTest()

        // VM #2 rebuilds: the pane still shows only a bare prompt, so the verify
        // probe sees the composed payload NEVER landed ⇒ full re-paste, the
        // attachment row is NOT dropped.
        val client2 = clientShowingComposed("$ ")
        val vm2 = newDurableConnectedVm(client2)
        val second = async { vm2.sendAgentPayloadToPaneResult("%0", composed, AgentKind.ClaudeCode) }
        advanceUntilIdle()
        assertTrue(second.await().isSuccess)
        assertEquals(
            "a genuinely-undelivered attachment row after back-navigation must still " +
                "deliver (verify shows NOT landed ⇒ re-paste), never silently dropped",
            1,
            client2.bracketedPasteCount(composed),
        )
    }

    /**
     * No #1541 regression: a TEXT-ONLY back-nav (on-wire payload == `cleanText`,
     * no attachments) must STILL be exactly-once under the attachment-aware needle
     * — the added wire-form branch must not disturb the text-only match.
     */
    @Test
    fun textOnlyBackNavRemainsExactlyOnce() = runTest(scheduler) {
        val payload = "restart the metrics collector"
        val store = SharedPrefsOutboundQueueStore(context)
        store.enqueue("sessT", payload, paneId = "%0", route = OutboundRoute.AgentConversation)

        val client1 = clientShowingComposed("> $payload")
        val vm1 = newDurableConnectedVm(client1)
        client1.throwOnCommandPrefix = "send-keys -t %0 Enter"
        client1.throwOnCommandRemaining = 1
        val first = async { vm1.sendAgentPayloadToPaneResult("%0", payload, AgentKind.ClaudeCode) }
        advanceUntilIdle()
        assertTrue(first.await().isFailure)
        assertEquals(1, client1.literalPasteCount(payload))
        assertTrue(store.hasWireAttempt("%0", payload))

        vm1.clearForTest()

        val client2 = clientShowingComposed("> $payload")
        val vm2 = newDurableConnectedVm(client2)
        val second = async { vm2.sendAgentPayloadToPaneResult("%0", payload, AgentKind.ClaudeCode) }
        advanceUntilIdle()
        assertTrue(second.await().isSuccess)
        assertEquals(
            "text-only back-navigation must remain exactly-once (no #1541 regression)",
            0,
            client2.literalPasteCount(payload),
        )
        assertTrue(client2.enterCount() >= 1)
        // And a delivered+pruned text row drops the durable flag (not falsely suppressed later).
        assertFalse(store.hasWireAttempt("%0", "some other unsent text"))
    }
}
