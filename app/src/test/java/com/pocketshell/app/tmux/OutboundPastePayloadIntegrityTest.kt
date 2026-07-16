package com.pocketshell.app.tmux

import com.pocketshell.core.agents.AgentDetection
import com.pocketshell.core.agents.AgentKind
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
 * Issue #1636 — a mid-paste teardown followed by a VERIFIED resend must not
 * accrete partial payload copies. The prompt reaches the agent once, with its
 * content BYTE-EXACT.
 *
 * ## The recurrence class this pins (D31/D32 G2/G6)
 *
 * Submit-level exactly-once already holds: the durable `wireAttempted` ledger +
 * count-baseline probe (#1541/#1577/#1587) closed the wire-duplicate family, and
 * `OutboundExactlyOnceDeliveryTest` / `OutboundExactlyOnceAcrossFlapE2eTest`
 * keep it closed. #1636 is the failure those proofs are STRUCTURALLY BLIND to,
 * because they assert an OCCURRENCE COUNT and the symptom is a corrupt PAYLOAD:
 *
 *  1. the paste was N sequential `send-keys -H` execs, so a teardown between
 *     chunk k and k+1 left chunks 1..k in the agent's input box server-side;
 *  2. the retry's probe keys on the tail of the payload's LAST line — which a
 *     partial prefix never contains — so it reported `NotLanded`;
 *  3. `NotLanded` re-pasted the FULL payload with no clearing of the input box,
 *     so the agent received `<partial-prefix><full-payload>` and submitted it as
 *     ONE prompt. Occurrence == 1. Ack fired. Row Delivered. Content garbage.
 *
 * Under the #1610 storm (a teardown every ~5 s, shorter than a multi-chunk paste
 * chain) repeated cuts accreted SEVERAL prefixes before an attempt completed.
 *
 * ## Why these assertions are the load-bearing ones (G6)
 *
 * Every assertion below is CONTENT EQUALITY of the prompt the server-side input
 * box actually submitted — `assertEquals(payload, submittedPrompts.single())` —
 * never needle presence and never a send count. The needle is precisely what
 * cannot see this corruption: it is present in the accreted text too.
 * [FakeTmuxPaneServer] models the tmux server (documented `send-keys` /
 * `set-buffer` / `paste-buffer` semantics, verified against tmux 3.4), so the
 * cut points are expressed shape-agnostically over the wire commands and these
 * tests are RED on the old `send-keys -H` chain and GREEN on the atomic
 * `set-buffer`-fill + `paste-buffer`-commit route ([sendBracketedPaste]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class OutboundPastePayloadIntegrityTest : TmuxSessionViewModelTestBase() {

    private fun claudeDetection(): AgentDetection = AgentDetection(
        agent = AgentKind.ClaudeCode,
        sourcePath = "/home/u/.claude/sessions/abc.jsonl",
        sessionId = "abc",
        confidence = AgentDetection.Confidence.ProcessConfirmed,
    )

    private fun newConnectedVm(client: FakeTmuxPaneServer): TmuxSessionViewModel {
        val vm = newVm()
        vm.attachClientForTest(client)
        vm.startAgentConversationForTest("%0", claudeDetection())
        vm.setAgentSubmitEnterDelayForTest(0)
        vm.setAgentSubmitAckTimeoutForTest(50)
        return vm
    }

    /** A multi-line payload of [chunks] paste-buffer chunks (the bracketed route). */
    private fun payloadOfChunks(chunks: Int, tag: String): String {
        val bodyBytes = TMUX_PASTE_BODY_CHUNK_BYTES * (chunks - 1) + TMUX_PASTE_BODY_CHUNK_BYTES / 2
        return "first line of $tag\n" + "x".repeat(bodyBytes) + "\ntail line of $tag"
    }

    /**
     * The wire commands that carry payload BYTES to the server, in order —
     * shape-agnostic, so the same predicate finds a chunk boundary in the old
     * `send-keys -H` chain and in the new `set-buffer` fill.
     */
    private fun isPayloadCarryingCommand(command: String): Boolean =
        command.startsWith("send-keys -H ") || command.startsWith("set-buffer ")

    /**
     * Cut the link at the Nth (0-based) payload-carrying command of the CURRENT
     * attempt — the #1526 S6 spec's cut point (b), a teardown at a CHUNK BOUNDARY
     * mid-paste. No fixture reproduced this before: the existing seam
     * ([OutboundDeliverySeams.failSendResultLostBeforeSubmitEnter]) models cut
     * point (c), AFTER the whole paste has landed.
     */
    private fun FakeTmuxPaneServer.cutAtPayloadChunk(index: Int) {
        var seen = 0
        failBeforeApplyAtCommand { command ->
            if (!isPayloadCarryingCommand(command)) false else seen++ == index
        }
    }

    /**
     * Cut the link HALFWAY through [payload]'s paste — the boundary that matters,
     * because at that point some of the payload's bytes are already on the server
     * and the rest never arrive. That is the state the old `send-keys -H` chain
     * turned into a partial prefix in the agent's input box, and it is exactly
     * what the #1610 storm's ~5 s teardown lands on for any multi-chunk prompt.
     *
     * The boundary is MEASURED (see [payloadCarryingCommandCount]) rather than
     * derived from the production chunker, so "halfway through the paste" means
     * the same thing for the old chain and the new fill — which is what lets one
     * test be red on base and green with the fix.
     */
    private suspend fun FakeTmuxPaneServer.cutMidPaste(payload: String) {
        val boundary = payloadCarryingCommandCount(payload) / 2
        assertTrue("cutMidPaste needs a multi-chunk payload, got boundary=$boundary", boundary >= 1)
        cutAtPayloadChunk(boundary)
    }

    private suspend fun TmuxSessionViewModel.deliver(payload: String, token: String) =
        sendAgentPayloadToPaneResult("%0", payload, AgentKind.ClaudeCode, token)

    /**
     * How many payload-carrying wire commands one clean send of [payload] emits —
     * MEASURED against a throwaway server rather than derived from the production
     * chunker, so a boundary index means the same thing whichever paste shape is
     * compiled in.
     */
    private suspend fun payloadCarryingCommandCount(payload: String): Int {
        val probe = FakeTmuxPaneServer()
        newConnectedVm(probe).deliver(payload, "s-measure")
        return probe.sentCommands.count { isPayloadCarryingCommand(it) }
    }

    // ------------------------------------------------------------------
    // AC1 — the reported defect: mid-paste teardown + verified resend
    // ------------------------------------------------------------------

    /**
     * THE #1636 reproduction. Attempt 1 is cut at a chunk boundary mid-paste;
     * the verified resend then delivers. The agent must receive the payload
     * EXACTLY — not `<partial-prefix><payload>`.
     *
     * RED on base: the input box holds chunk 1's prefix when the resend probes,
     * the probe reports `NotLanded` (the tail never landed), the full payload is
     * pasted on top, and the submitted prompt is the concatenation.
     */
    @Test
    fun midPasteTeardownThenVerifiedResendDeliversPayloadByteExact() = runTest(scheduler) {
        val client = FakeTmuxPaneServer()
        val vm = newConnectedVm(client)
        val payload = payloadOfChunks(chunks = 3, tag = "mid-paste teardown")

        client.cutMidPaste(payload)
        val first = async { vm.deliver(payload, "s-1636") }
        advanceUntilIdle()
        assertTrue("attempt 1 must surface the mid-paste teardown", first.await().isFailure)

        val second = async { vm.deliver(payload, "s-1636") }
        advanceUntilIdle()
        assertTrue("the verified resend must succeed", second.await().isSuccess)

        // THE assertion (G6): content equality of what the agent received. A send
        // count / needle-presence assertion is green on base while this is red.
        assertEquals(
            "the agent must receive the payload EXACTLY ONCE, BYTE-EXACT — a mid-paste " +
                "teardown must not leave a partial prefix for the verified resend to " +
                "accrete onto (base: `<partial-prefix><payload>`)",
            listOf(payload),
            client.submittedPrompts("%0"),
        )
        assertEquals("the input box must be left clean", "", client.inputBox("%0"))
    }

    // ------------------------------------------------------------------
    // AC2 — class coverage (G2)
    // ------------------------------------------------------------------

    /** The smallest multi-chunk payload (2 chunks), cut mid-paste. */
    @Test
    fun twoChunkPayloadCutMidPasteDeliversByteExact() = runTest(scheduler) {
        val client = FakeTmuxPaneServer()
        val vm = newConnectedVm(client)
        val payload = payloadOfChunks(chunks = 2, tag = "two chunk mid cut")

        client.cutMidPaste(payload)
        val first = async { vm.deliver(payload, "s-2c-mid") }
        advanceUntilIdle()
        assertTrue(first.await().isFailure)

        val second = async { vm.deliver(payload, "s-2c-mid") }
        advanceUntilIdle()
        assertTrue(second.await().isSuccess)

        assertEquals(listOf(payload), client.submittedPrompts("%0"))
    }

    /** A 3-chunk payload, cut at the LAST boundary (the most bytes already in flight). */
    @Test
    fun multiChunkPayloadCutAtLastPayloadBoundaryDeliversByteExact() = runTest(scheduler) {
        val client = FakeTmuxPaneServer()
        val vm = newConnectedVm(client)
        val payload = payloadOfChunks(chunks = 3, tag = "three chunk last cut")
        // Measured, not derived from the production chunker, so "the last boundary"
        // means the same thing for the old `send-keys -H` chain and the new fill.
        // The LAST boundary at which a partial can exist: everything before the
        // payload's final command has landed, so the pane holds the longest
        // possible prefix — and still not the tail the resend probe keys on.
        val lastBoundary = payloadCarryingCommandCount(payload) - 2
        assertTrue("the payload must be multi-chunk for this case", lastBoundary >= 1)

        client.cutAtPayloadChunk(lastBoundary)
        val first = async { vm.deliver(payload, "s-3c-last") }
        advanceUntilIdle()
        assertTrue(first.await().isFailure)

        val second = async { vm.deliver(payload, "s-3c-last") }
        advanceUntilIdle()
        assertTrue(second.await().isSuccess)

        assertEquals(listOf(payload), client.submittedPrompts("%0"))
    }

    /**
     * The #1610 STORM shape: repeated teardowns across consecutive cycles inside a
     * single paste. The audit's question was "does the payload converge, or
     * accrete?" — on base each cycle adds another prefix. It must converge.
     */
    @Test
    fun repeatedMidPasteTeardownsAcrossStormCyclesConvergeToOneByteExactPayload() = runTest(scheduler) {
        val client = FakeTmuxPaneServer()
        val vm = newConnectedVm(client)
        val payload = payloadOfChunks(chunks = 4, tag = "storm")

        // Four consecutive cut cycles, each landing inside the paste — the storm's
        // teardown-every-~5s against a multi-chunk paste chain. On base each cycle
        // adds another partial prefix to the input box.
        for (cycle in 0 until 4) {
            client.cutMidPaste(payload)
            val attempt = async { vm.deliver(payload, "s-storm") }
            advanceUntilIdle()
            assertTrue("storm cycle $cycle must surface the teardown", attempt.await().isFailure)
            assertEquals(
                "cycle $cycle: nothing may be submitted while the paste keeps being cut",
                emptyList<String>(),
                client.submittedPrompts("%0"),
            )
        }

        val healed = async { vm.deliver(payload, "s-storm") }
        advanceUntilIdle()
        assertTrue("the send after the storm must succeed", healed.await().isSuccess)

        assertEquals(
            "four cut cycles must CONVERGE to one byte-exact prompt, not accrete four " +
                "partial prefixes onto it",
            listOf(payload),
            client.submittedPrompts("%0"),
        )
    }

    /**
     * The AMBIGUOUS cut: the payload's first commit RAN server-side and only its
     * result was lost. Not a #1636 reproduction (it is green on base too — the
     * adjacency guard for the #1526 S1 cut point (c) family): it proves the atomic
     * route did not REGRESS the already-closed ambiguous-cut case, and asserts it
     * in BYTES rather than the occurrence count the S1 proofs use.
     */
    @Test
    fun pasteLandedButResultLostResendSubmitsByteExactPayloadOnce() = runTest(scheduler) {
        val client = FakeTmuxPaneServer()
        val vm = newConnectedVm(client)
        val payload = payloadOfChunks(chunks = 2, tag = "result lost")

        // The commit ran server-side; only its result was lost.
        client.failAfterApplyAtCommand { it.startsWith("paste-buffer ") || it.startsWith("send-keys -H ") }
        val first = async { vm.deliver(payload, "s-lost") }
        advanceUntilIdle()
        assertTrue(first.await().isFailure)

        val second = async { vm.deliver(payload, "s-lost") }
        advanceUntilIdle()
        assertTrue("the verified resend must succeed", second.await().isSuccess)

        assertEquals(
            "an already-landed paste must be submitted as-is, byte-exact and once",
            listOf(payload),
            client.submittedPrompts("%0"),
        )
    }

    /**
     * A LARGE multi-chunk payload (many chunks, non-ASCII, blank lines) cut
     * mid-paste. Guards the UTF-8-safe chunk splitting: a boundary that fell
     * inside a multi-byte sequence would corrupt the payload with U+FFFD even
     * with no teardown at all.
     */
    @Test
    fun largeMultiByteMultiChunkPayloadCutMidPasteDeliversByteExact() = runTest(scheduler) {
        val client = FakeTmuxPaneServer()
        val vm = newConnectedVm(client)
        val payload = buildString {
            append("Проверка: переустанови зависимости и запусти тесты\n\n")
            repeat(400) { append("строка $it — a long dictated segment with emoji 🎧 and ünïcode\n") }
            append("\nfinal tail line ✅")
        }

        client.cutMidPaste(payload)
        val first = async { vm.deliver(payload, "s-utf8") }
        advanceUntilIdle()
        assertTrue(first.await().isFailure)

        val second = async { vm.deliver(payload, "s-utf8") }
        advanceUntilIdle()
        assertTrue(second.await().isSuccess)

        assertEquals(
            "a large multi-byte payload must survive chunking + a mid-paste cut byte-exactly",
            listOf(payload),
            client.submittedPrompts("%0"),
        )
    }

    /**
     * The FIRST boundary — the link dies before ANY payload byte reaches the
     * server. Green on base too (there is no partial to accrete onto), so this is
     * coverage of the boundary's other end rather than a #1636 reproduction: it
     * pins that the atomic route did not turn a clean "nothing landed" retry into
     * a duplicate.
     */
    @Test
    fun payloadCutBeforeAnyByteLandsDeliversByteExactOnTheResend() = runTest(scheduler) {
        val client = FakeTmuxPaneServer()
        val vm = newConnectedVm(client)
        val payload = payloadOfChunks(chunks = 3, tag = "first boundary")

        client.cutAtPayloadChunk(0)
        val first = async { vm.deliver(payload, "s-first") }
        advanceUntilIdle()
        assertTrue(first.await().isFailure)
        assertEquals("no byte may have reached the pane", "", client.inputBox("%0"))

        val second = async { vm.deliver(payload, "s-first") }
        advanceUntilIdle()
        assertTrue(second.await().isSuccess)

        assertEquals(listOf(payload), client.submittedPrompts("%0"))
    }

    /** Single-chunk (short, one-line) payloads are unaffected — they never chunked. */
    @Test
    fun singleChunkPayloadIsUnaffectedAndDeliversByteExact() = runTest(scheduler) {
        val client = FakeTmuxPaneServer()
        val vm = newConnectedVm(client)
        val payload = "deploy the staging build now"

        val send = async { vm.deliver(payload, "s-short") }
        advanceUntilIdle()
        assertTrue(send.await().isSuccess)

        assertEquals(listOf(payload), client.submittedPrompts("%0"))
    }

    // ------------------------------------------------------------------
    // AC3 — the negative case (G6): the uninterrupted send must stay correct
    // ------------------------------------------------------------------

    /**
     * The NEGATIVE case: a normal, uninterrupted multi-chunk send still delivers
     * byte-exact, submits exactly once, and does NOT double-buffer the payload
     * into the pane. A fix that made the interrupted case pass by pasting twice,
     * or that left the payload in a tmux buffer, would be caught here.
     */
    @Test
    fun uninterruptedMultiChunkSendDeliversByteExactAndCommitsOnce() = runTest(scheduler) {
        val client = FakeTmuxPaneServer()
        val vm = newConnectedVm(client)
        val payload = payloadOfChunks(chunks = 3, tag = "uninterrupted")

        val send = async { vm.deliver(payload, "s-clean") }
        advanceUntilIdle()
        assertTrue("the uninterrupted send must succeed", send.await().isSuccess)

        assertEquals(
            "the uninterrupted send must deliver the payload byte-exact, exactly once",
            listOf(payload),
            client.submittedPrompts("%0"),
        )
        assertEquals("nothing may be left pending in the input box", "", client.inputBox("%0"))

        // Exactly ONE commit reaches the pane: the payload is not written twice
        // (once "to be safe" and once for real), and the fill is not a commit.
        val commits = client.appliedCommands.count {
            it.startsWith("paste-buffer ") || it.startsWith("send-keys -H ")
        }
        assertEquals(
            "the payload must reach the pane through exactly ONE commit: ${client.appliedCommands}",
            1,
            commits,
        )
    }

    /**
     * The tmux-server contract the atomic route depends on, asserted directly so a
     * later edit cannot silently break the byte-exactness the tests above ride on:
     * the fill NEVER touches the pane (so a teardown mid-fill leaves the pane
     * untouched), and the first fill chunk TRUNCATES (so a retry cannot append to
     * the partial its predecessor left).
     */
    @Test
    fun pasteFillNeverTouchesThePaneAndRetryTruncatesThePartialFill() = runTest(scheduler) {
        val client = FakeTmuxPaneServer()
        val vm = newConnectedVm(client)
        val payload = payloadOfChunks(chunks = 3, tag = "fill isolation")

        client.cutMidPaste(payload)
        val first = async { vm.deliver(payload, "s-fill") }
        advanceUntilIdle()
        assertTrue(first.await().isFailure)

        // The cut interrupted the paste AFTER two chunks of the payload were on the
        // wire — and yet the pane saw NOTHING. On base those two chunks are already
        // in the input box, which is the whole defect.
        assertEquals(
            "a teardown mid-paste must leave the pane untouched (the fill is not a commit)",
            "",
            client.inputBox("%0"),
        )
        assertEquals(emptyList<String>(), client.submittedPrompts("%0"))

        val second = async { vm.deliver(payload, "s-fill") }
        advanceUntilIdle()
        assertTrue(second.await().isSuccess)
        assertEquals(listOf(payload), client.submittedPrompts("%0"))
    }
}
