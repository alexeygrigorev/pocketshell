package com.pocketshell.app.session

import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.agents.CodexParser
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.agents.ConversationRole
import com.pocketshell.core.agents.MessageSendState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentConversationRepositoryReconciliationTest {
    @Test
    fun reconcilePreservesUserTurnsWhenToolEventsExceedTheBound() {
        // One user prompt, then a flood of tool events larger than the
        // cap, then an assistant reply. A naive "keep latest N" bound
        // would evict the user prompt off the top; the message-preserving
        // bound must keep it.
        val cap = 10
        val events = buildList {
            add(
                ConversationEvent.Message(
                    id = "user-prompt",
                    agent = AgentKind.ClaudeCode,
                    role = ConversationRole.User,
                    text = "fix the build",
                ),
            )
            repeat(cap * 3) { index ->
                add(
                    ConversationEvent.ToolResult(
                        id = "result-$index",
                        agent = AgentKind.ClaudeCode,
                        toolCallId = "tool-$index",
                        output = "line $index",
                    ),
                )
            }
            add(
                ConversationEvent.Message(
                    id = "assistant-reply",
                    agent = AgentKind.ClaudeCode,
                    role = ConversationRole.Assistant,
                    text = "done",
                ),
            )
        }

        val bounded = reconcileAgentEvents(events, maxEvents = cap)

        assertTrue(bounded.size <= cap)
        val messages = bounded.filterIsInstance<ConversationEvent.Message>()
        assertEquals(
            listOf(
                ConversationRole.User to "fix the build",
                ConversationRole.Assistant to "done",
            ),
            messages.map { it.role to it.text },
        )
        // The user prompt is first in order and the assistant reply last,
        // with surviving tool results in between.
        assertEquals("user-prompt", bounded.first().id)
        assertEquals("assistant-reply", bounded.last().id)
        assertTrue(bounded.any { it is ConversationEvent.ToolResult })
    }

    @Test
    fun reconcileCollapsesOptimisticPendingTurnIntoMatchingTranscriptEntry() {
        // Issue #494: an optimistic pending turn followed by the real
        // transcript entry with the same text must render as ONE turn -
        // the authoritative (non-optimistic, Confirmed) one.
        val optimistic = ConversationEvent.Message(
            id = "${OPTIMISTIC_USER_MESSAGE_ID_PREFIX}1",
            agent = AgentKind.ClaudeCode,
            role = ConversationRole.User,
            text = "run the tests",
            sendState = MessageSendState.Pending,
        )
        val real = ConversationEvent.Message(
            id = "claude-real-1",
            agent = AgentKind.ClaudeCode,
            role = ConversationRole.User,
            text = "run the tests",
        )

        val reconciled = reconcileAgentEvents(listOf(optimistic, real))

        val userMessages = reconciled.filterIsInstance<ConversationEvent.Message>()
            .filter { it.role == ConversationRole.User && it.text == "run the tests" }
        assertEquals(1, userMessages.size)
        val survivor = userMessages.single()
        assertEquals("claude-real-1", survivor.id)
        assertFalse(survivor.id.startsWith(OPTIMISTIC_USER_MESSAGE_ID_PREFIX))
        assertEquals(MessageSendState.Confirmed, survivor.sendState)
    }

    @Test
    fun reconcileKeepsOptimisticTurnWhenNoMatchingTranscriptEntryArrives() {
        // Issue #494: a transcript entry with DIFFERENT text must not
        // collapse the optimistic turn - both are real, distinct turns.
        val optimistic = ConversationEvent.Message(
            id = "${OPTIMISTIC_USER_MESSAGE_ID_PREFIX}1",
            agent = AgentKind.ClaudeCode,
            role = ConversationRole.User,
            text = "run the tests",
            sendState = MessageSendState.Pending,
        )
        val unrelated = ConversationEvent.Message(
            id = "claude-real-2",
            agent = AgentKind.ClaudeCode,
            role = ConversationRole.User,
            text = "now ship it",
        )

        val reconciled = reconcileAgentEvents(listOf(optimistic, unrelated))

        val userMessages = reconciled.filterIsInstance<ConversationEvent.Message>()
            .filter { it.role == ConversationRole.User }
        assertEquals(2, userMessages.size)
        assertTrue(userMessages.any { it.text == "run the tests" && it.sendState == MessageSendState.Pending })
        assertTrue(userMessages.any { it.text == "now ship it" })
    }

    @Test
    fun reconcileCollapsesCodexAssistantTurnEmittedAsBothAgentMessageAndResponseItem() {
        // Issue #819 (duplicate ASSISTANT turn): a real Codex rollout writes
        // the SAME assistant text twice within one session - once as a
        // streaming `event_msg`/`agent_message` (NO id -> CodexParser falls
        // back to line.hashCode()) and once as the authoritative
        // `response_item`/`message` (WITH a real id like "m1"). The two
        // events therefore carry DIFFERENT ids for identical prose, so the
        // id-keyed reconcile cannot collapse them and the turn renders twice
        // (the screenshot's repeated "Still green at 83%... I'll continue
        // polling.").
        //
        // Parse them through the REAL CodexParser (not hand-built events) so
        // this test exercises the actual id-collision path, then reconcile.
        // Exactly ONE assistant turn must survive.
        val parser = CodexParser()
        val text = "Still green at 83%. The run is long but healthy; I'll continue polling."
        val streamingEcho = parser.parseLine(
            """{"type":"event_msg","payload":{"type":"agent_message","message":${
                JSONObject.quote(text)
            }},"timestamp":"2026-06-18T16:38:00Z"}""",
        )
        val authoritative = parser.parseLine(
            """{"type":"response_item","item":{"type":"message","id":"m1","role":"assistant","content":[{"type":"output_text","text":${
                JSONObject.quote(text)
            }}]}}""",
        )

        // Sanity: the parser really does mint two different ids for the same
        // text (the precondition that makes the duplicate visible).
        val echoMsg = streamingEcho.single() as ConversationEvent.Message
        val authMsg = authoritative.single() as ConversationEvent.Message
        assertEquals(text, echoMsg.text)
        assertEquals(text, authMsg.text)
        assertEquals("m1", authMsg.id)
        assertFalse(
            "precondition: the agent_message echo must carry a different id " +
                "than the response_item record",
            echoMsg.id == authMsg.id,
        )

        val reconciled = reconcileAgentEvents(streamingEcho + authoritative)

        val assistantTurns = reconciled.filterIsInstance<ConversationEvent.Message>()
            .filter { it.role == ConversationRole.Assistant && it.text == text }
        assertEquals(
            "the same Codex assistant turn arriving as both an agent_message " +
                "echo and a response_item record must render ONCE (#819)",
            1,
            assistantTurns.size,
        )
    }

    @Test
    fun reconcileKeepsLegitimatelyRepeatedAssistantTurnsSeparatedByOtherTurns() {
        // Counter-pin to the #819 dedup: identical assistant text that is a
        // GENUINELY separate turn (e.g. the agent says the same status line
        // at two different points, with a user turn in between) must NOT be
        // collapsed. The dedup is adjacency-scoped (consecutive duplicate of
        // the SAME logical turn - the echo+record pair), not a global
        // "drop every repeat of this text".
        val first = ConversationEvent.Message(
            id = "codex-a-1",
            agent = AgentKind.Codex,
            role = ConversationRole.Assistant,
            text = "Working...",
        )
        val userBetween = ConversationEvent.Message(
            id = "codex-u-1",
            agent = AgentKind.Codex,
            role = ConversationRole.User,
            text = "any update?",
        )
        val second = ConversationEvent.Message(
            id = "codex-a-2",
            agent = AgentKind.Codex,
            role = ConversationRole.Assistant,
            text = "Working...",
        )

        val reconciled = reconcileAgentEvents(listOf(first, userBetween, second))

        val working = reconciled.filterIsInstance<ConversationEvent.Message>()
            .filter { it.role == ConversationRole.Assistant && it.text == "Working..." }
        assertEquals(
            "two legitimately-separate assistant turns with the same text " +
                "(separated by another turn) must both survive",
            2,
            working.size,
        )
    }

    @Test
    fun reconcileKeepsGenuinelyRepeatedAdjacentAssistantTurnsWithDistinctStableIds() {
        // Issue #1234 (item 5): the #819 echo-collapse over-reached. Its
        // adjacency check collapsed ANY two adjacent Messages with the same
        // role+text+agent, so a genuinely repeated SHORT turn the agent really
        // emitted twice back-to-back (a real "Done." / "ok") silently showed
        // once. The collapse must fire ONLY for the echo SHAPE - one side is
        // the streaming echo with a synthetic `line.hashCode()` id, the other
        // is the authoritative record with a stable id. Two ADJACENT records
        // that BOTH carry a distinct STABLE id are a real repeat and must both
        // survive.
        //
        // Before the fix this reconciles to a SINGLE "Done." (RED); after the
        // fix both survive (GREEN).
        val first = ConversationEvent.Message(
            id = "codex-real-done-1",
            agent = AgentKind.Codex,
            role = ConversationRole.Assistant,
            text = "Done.",
        )
        val second = ConversationEvent.Message(
            id = "codex-real-done-2",
            agent = AgentKind.Codex,
            role = ConversationRole.Assistant,
            text = "Done.",
        )

        val reconciled = reconcileAgentEvents(listOf(first, second))

        val dones = reconciled.filterIsInstance<ConversationEvent.Message>()
            .filter { it.role == ConversationRole.Assistant && it.text == "Done." }
        assertEquals(
            "two ADJACENT authoritative assistant records with the same text " +
                "but DISTINCT stable ids are a genuine repeat, not an echo - " +
                "both must survive (#1234 item 5)",
            2,
            dones.size,
        )
        assertTrue(dones.any { it.id == "codex-real-done-1" })
        assertTrue(dones.any { it.id == "codex-real-done-2" })
    }

    @Test
    fun reconcileKeepsGenuinelyRepeatedAdjacentUserTurnsWithDistinctStableIds() {
        // Issue #1234 (item 5), class coverage for the USER role: a user who
        // really sends "ok" twice in a row produces two authoritative user
        // records with DISTINCT stable ids and no intervening turn. Neither is
        // an `optimistic:` echo, so the #819 adjacency collapse (pre-fix) would
        // merge them into one - the same over-reach as the assistant case. With
        // the echo-shape guard both stable-id records survive.
        val first = ConversationEvent.Message(
            id = "codex-real-ok-1",
            agent = AgentKind.Codex,
            role = ConversationRole.User,
            text = "ok",
        )
        val second = ConversationEvent.Message(
            id = "codex-real-ok-2",
            agent = AgentKind.Codex,
            role = ConversationRole.User,
            text = "ok",
        )

        val reconciled = reconcileAgentEvents(listOf(first, second))

        val oks = reconciled.filterIsInstance<ConversationEvent.Message>()
            .filter { it.role == ConversationRole.User && it.text == "ok" }
        assertEquals(
            "two ADJACENT authoritative user records with the same text but " +
                "DISTINCT stable ids are a genuine repeat and must both survive " +
                "(#1234 item 5)",
            2,
            oks.size,
        )
        assertTrue(oks.any { it.id == "codex-real-ok-1" })
        assertTrue(oks.any { it.id == "codex-real-ok-2" })
    }

    @Test
    fun reconcileWithManyOptimisticDuplicatesCollapsesOldestMatchFirstLikeTheLinearScan() {
        // The text-keyed optimistic index must reproduce the prior
        // `firstOrNull` semantics exactly: back-to-back duplicate prompts
        // each mint their own optimistic id, and a single real arrival
        // collapses the OLDEST still-live optimistic turn with that text.
        val opt1 = ConversationEvent.Message(
            id = "${OPTIMISTIC_USER_MESSAGE_ID_PREFIX}1",
            agent = AgentKind.ClaudeCode,
            role = ConversationRole.User,
            text = "ping",
            sendState = MessageSendState.Pending,
        )
        val opt2 = ConversationEvent.Message(
            id = "${OPTIMISTIC_USER_MESSAGE_ID_PREFIX}2",
            agent = AgentKind.ClaudeCode,
            role = ConversationRole.User,
            text = "ping",
            sendState = MessageSendState.Pending,
        )
        val real = ConversationEvent.Message(
            id = "claude-real-ping",
            agent = AgentKind.ClaudeCode,
            role = ConversationRole.User,
            text = "ping",
        )

        val reconciled = reconcileAgentEvents(listOf(opt1, opt2, real))

        // Exactly one optimistic "ping" remains plus the real one: the
        // first-inserted optimistic (id ...1) was collapsed.
        val pings = reconciled.filterIsInstance<ConversationEvent.Message>()
            .filter { it.text == "ping" }
        assertEquals(2, pings.size)
        assertFalse(
            "the oldest optimistic ping must be collapsed",
            pings.any { it.id == "${OPTIMISTIC_USER_MESSAGE_ID_PREFIX}1" },
        )
        assertTrue(pings.any { it.id == "${OPTIMISTIC_USER_MESSAGE_ID_PREFIX}2" })
        assertTrue(pings.any { it.id == "claude-real-ping" })
    }

    @Test
    fun reconcileIsIdenticalWhetherEventsArrivePerEventOrAsOneBatch() {
        // The load-bearing correctness invariant for the batched tail:
        // reconciling a burst incrementally (one event at a time, the old
        // per-line shape) and reconciling the whole burst as a single
        // batch must produce the SAME final list - same set, same order,
        // same dedup. Without that, batching would change what the user
        // sees. The feed mixes user prompts, optimistic placeholders, their
        // real arrivals, assistant prose, and tool churn.
        val burst = buildMixedConversationBurst(turns = 40)

        // Per-event: rebuild the accumulator after every single event, the
        // exact shape of the old unbatched ingest.
        var perEvent = emptyList<ConversationEvent>()
        for (event in burst) {
            perEvent = reconcileAgentEvents(perEvent + event)
        }

        // Batched: one reconcile over the whole burst.
        val batched = reconcileAgentEvents(burst)

        assertEquals(
            "batched ingest must reconcile to the same list as per-event ingest",
            perEvent.map { it.id },
            batched.map { it.id },
        )
        // And dedup actually happened: no optimistic turn survives once its
        // real twin is present.
        assertFalse(
            batched.any {
                it is ConversationEvent.Message &&
                    it.id.startsWith(OPTIMISTIC_USER_MESSAGE_ID_PREFIX) &&
                    batched.any { other ->
                        other is ConversationEvent.Message &&
                            !other.id.startsWith(OPTIMISTIC_USER_MESSAGE_ID_PREFIX) &&
                            other.role == ConversationRole.User &&
                            other.text == it.text
                    }
            },
        )
    }

    @Test
    fun reconcileScalesLinearlyForLargeUserMessageHeavyBursts() {
        // Regression guard for the O(n^2)-per-reconcile hole (#576): a burst
        // of thousands of optimistic+real user turns must reconcile in
        // LINEAR work. The old nested `byId.entries.firstOrNull` scan ran
        // O(window^2) here - it re-scanned the WHOLE accumulator on every real
        // user message to find the optimistic turn to collapse.
        //
        // Issue #831: this assertion is on a DETERMINISTIC operation counter
        // (ReconcileOpCounter.candidateInspections), NOT wall-clock time. A
        // wall-clock ceiling - or even a best-of-samples timing RATIO - is
        // inherently flaky on a contended CI runner / dev box: load inflates
        // elapsed time even though the algorithm is still linear, so the test
        // flakes (the failure that filed #831). The candidate-inspection count
        // is derived purely from control flow: it counts every accumulated
        // candidate the matching path examines while collapsing an optimistic
        // turn. That is exactly the work the old quadratic scan exploded.
        //
        //  - linear (current) impl: each optimistic id is inspected at most
        //    once across the whole reconcile => count is O(window)
        //  - quadratic (old) impl: re-scans the whole accumulator per real
        //    user message                      => count is O(window^2)
        //
        // The count is identical on every run regardless of machine load, so
        // the assertion is contention-immune AND still fails a genuine
        // quadratic regression. The `verifies...` sibling test below proves
        // the SAME counter explodes super-linearly under the old scan, so this
        // signal really does catch the regression it guards (red->green).

        // A real Codex `/new` replay sends each turn optimistically (an
        // `optimistic:` echo) and then tails the authoritative real user
        // message back with the same text - the reconcile collapses each pair.
        // That is the exact workload the matching path exists for and the one
        // the old quadratic scan blew up on.
        fun optimisticHeavyBurst(turns: Int): List<ConversationEvent> = buildList {
            repeat(turns) { index ->
                val text = "prompt number $index"
                add(
                    ConversationEvent.Message(
                        id = "${OPTIMISTIC_USER_MESSAGE_ID_PREFIX}$index",
                        agent = AgentKind.Codex,
                        role = ConversationRole.User,
                        text = text,
                        sendState = MessageSendState.Pending,
                    ),
                )
                add(
                    ConversationEvent.Message(
                        id = "u$index",
                        agent = AgentKind.Codex,
                        role = ConversationRole.User,
                        text = text,
                    ),
                )
            }
        }

        val smallTurns = 1_000
        val largeTurns = 4_000
        val small = optimisticHeavyBurst(smallTurns)
        val large = optimisticHeavyBurst(largeTurns)

        val smallCounter = ReconcileOpCounter()
        val largeCounter = ReconcileOpCounter()
        reconcileAgentEvents(small, maxEvents = 100_000, opCounter = smallCounter)
        reconcileAgentEvents(large, maxEvents = 100_000, opCounter = largeCounter)

        val smallOps = smallCounter.candidateInspections
        val largeOps = largeCounter.candidateInspections
        val ratio = largeOps.toDouble() / smallOps.coerceAtLeast(1)

        println(
            "[#576/#831 reconcile linearity] small(${smallTurns}t)=$smallOps ops " +
                "large(${largeTurns}t)=$largeOps ops ratio=${"%.2f".format(ratio)} " +
                "(deterministic candidate-inspection count; linear~4x, quadratic~16x)",
        )

        // Sanity: the matching path must actually run (a vacuous 0/0 would
        // make the ratio meaningless and pass quadratic regressions silently).
        assertTrue(
            "matching path never ran - counter is vacuous (small=$smallOps large=$largeOps)",
            smallOps > 0 && largeOps > 0,
        )

        // 4x the input => ~4x the work for a linear reconcile, ~16x for the
        // old quadratic one. Allow up to 6x to absorb the constant-factor
        // bookkeeping while staying far below the 16x a quadratic scan would
        // produce. This is a deterministic integer ratio, never a timing, so
        // it does not move under machine load.
        assertTrue(
            "reconcile candidate inspections grew super-linearly: " +
                "small=$smallOps large=$largeOps ratio=$ratio " +
                "(expected ~4x linear, ~16x quadratic)",
            ratio <= 6.0,
        )
    }

    @Test
    fun reconcileLinearityCounterCatchesAQuadraticRegression() {
        // Issue #831 (red->green proof for the guard above): demonstrate that
        // the candidate-inspection counter - the deterministic signal the
        // linearity test asserts on - actually EXPLODES super-linearly when
        // the reconcile matching path reverts to the old O(window^2) scan.
        //
        // This re-implements the OLD quadratic collapse exactly: instead of
        // the O(1) text-indexed FIFO lookup, it re-scans the WHOLE accumulator
        // (`byId.values.firstOrNull { ... }`) on every real user message to
        // find the optimistic turn to drop, counting each candidate examined
        // into the SAME counter the production guard reads. If that count did
        // not blow past the linear guard's 6x ceiling, the guard would be
        // unable to catch a real regression - so this test fails the day the
        // guard goes blind.
        fun quadraticCandidateInspections(events: List<ConversationEvent>): Long {
            val byId = LinkedHashMap<String, ConversationEvent>()
            var inspections = 0L
            for (event in events) {
                val isRealUser = event is ConversationEvent.Message &&
                    event.role == ConversationRole.User &&
                    !event.id.startsWith(OPTIMISTIC_USER_MESSAGE_ID_PREFIX)
                if (isRealUser) {
                    val text = (event as ConversationEvent.Message).text
                    // The pre-#576 hole: a full linear scan of the accumulator
                    // per real user message -> O(window^2) over the burst.
                    var matchId: String? = null
                    for ((id, candidate) in byId) {
                        inspections++
                        if (candidate is ConversationEvent.Message &&
                            candidate.role == ConversationRole.User &&
                            candidate.id.startsWith(OPTIMISTIC_USER_MESSAGE_ID_PREFIX) &&
                            candidate.text == text
                        ) {
                            matchId = id
                            break
                        }
                    }
                    if (matchId != null) byId.remove(matchId)
                }
                byId[event.id] = event
            }
            return inspections
        }

        fun optimisticHeavyBurst(turns: Int): List<ConversationEvent> = buildList {
            repeat(turns) { index ->
                val text = "prompt number $index"
                add(
                    ConversationEvent.Message(
                        id = "${OPTIMISTIC_USER_MESSAGE_ID_PREFIX}$index",
                        agent = AgentKind.Codex,
                        role = ConversationRole.User,
                        text = text,
                        sendState = MessageSendState.Pending,
                    ),
                )
                add(
                    ConversationEvent.Message(
                        id = "u$index",
                        agent = AgentKind.Codex,
                        role = ConversationRole.User,
                        text = text,
                    ),
                )
            }
        }

        val smallTurns = 1_000
        val largeTurns = 4_000
        val smallOps = quadraticCandidateInspections(optimisticHeavyBurst(smallTurns))
        val largeOps = quadraticCandidateInspections(optimisticHeavyBurst(largeTurns))
        val ratio = largeOps.toDouble() / smallOps.coerceAtLeast(1)

        println(
            "[#831 quadratic-injection proof] small(${smallTurns}t)=$smallOps ops " +
                "large(${largeTurns}t)=$largeOps ops ratio=${"%.2f".format(ratio)} " +
                "(old O(n^2) scan; expected ~16x, MUST exceed the 6x linear ceiling)",
        )

        // The quadratic scan must blow PAST the 6.0 ceiling the linearity
        // guard enforces. ~16x is the theoretical quadratic ratio for a 4x
        // input; require comfortably above the guard's ceiling so the proof is
        // unambiguous: this is what makes the guard a real regression catcher
        // and not a vacuous always-pass.
        assertTrue(
            "quadratic injection did NOT exceed the linear guard's 6x ceiling " +
                "(small=$smallOps large=$largeOps ratio=$ratio) - the guard would be blind",
            ratio > 6.0,
        )
    }

    @Test
    fun markOptimisticFailedFlipsOnlyTheTargetTurn() {
        // Issue #494: marking a failed send flips exactly the target
        // optimistic turn to Failed and leaves every other event untouched.
        val target = ConversationEvent.Message(
            id = "${OPTIMISTIC_USER_MESSAGE_ID_PREFIX}99",
            agent = AgentKind.ClaudeCode,
            role = ConversationRole.User,
            text = "deliver this",
            sendState = MessageSendState.Pending,
        )
        val other = ConversationEvent.Message(
            id = "${OPTIMISTIC_USER_MESSAGE_ID_PREFIX}100",
            agent = AgentKind.ClaudeCode,
            role = ConversationRole.User,
            text = "another pending",
            sendState = MessageSendState.Pending,
        )
        val assistant = ConversationEvent.Message(
            id = "assistant-1",
            agent = AgentKind.ClaudeCode,
            role = ConversationRole.Assistant,
            text = "ok",
        )

        val result = listOf(target, other, assistant).markOptimisticFailed(target.id)

        val byId = result.filterIsInstance<ConversationEvent.Message>().associateBy { it.id }
        assertEquals(MessageSendState.Failed, byId.getValue(target.id).sendState)
        assertEquals(MessageSendState.Pending, byId.getValue(other.id).sendState)
        assertEquals(MessageSendState.Confirmed, byId.getValue("assistant-1").sendState)
    }

    @Test
    fun markOptimisticFailedIsNoOpForUnknownId() {
        // Issue #494: a defensive no-op when the id is not present (e.g.
        // the turn was already reconciled away by an in-flight tail).
        val pending = ConversationEvent.Message(
            id = "${OPTIMISTIC_USER_MESSAGE_ID_PREFIX}1",
            agent = AgentKind.ClaudeCode,
            role = ConversationRole.User,
            text = "deliver this",
            sendState = MessageSendState.Pending,
        )

        val result = listOf<ConversationEvent>(pending).markOptimisticFailed("does-not-exist")

        assertEquals(listOf(pending), result)
    }

    private fun buildMixedConversationBurst(turns: Int): List<ConversationEvent> = buildList {
        repeat(turns) { turn ->
            val prompt = "do task $turn"
            // Optimistic placeholder, as sendToAgent would insert it.
            add(
                ConversationEvent.Message(
                    id = "${OPTIMISTIC_USER_MESSAGE_ID_PREFIX}$turn",
                    agent = AgentKind.Codex,
                    role = ConversationRole.User,
                    text = prompt,
                    sendState = MessageSendState.Pending,
                ),
            )
            // Its authoritative twin arriving via the tail.
            add(
                ConversationEvent.Message(
                    id = "real-user-$turn",
                    agent = AgentKind.Codex,
                    role = ConversationRole.User,
                    text = prompt,
                ),
            )
            // A tool call + result.
            add(
                ConversationEvent.ToolCall(
                    id = "tool-$turn",
                    agent = AgentKind.Codex,
                    name = "Bash",
                    input = "run $turn",
                ),
            )
            add(
                ConversationEvent.ToolResult(
                    id = "result-$turn",
                    agent = AgentKind.Codex,
                    toolCallId = "tool-$turn",
                    output = "output $turn",
                ),
            )
            // Assistant prose.
            add(
                ConversationEvent.Message(
                    id = "assistant-$turn",
                    agent = AgentKind.Codex,
                    role = ConversationRole.Assistant,
                    text = "answer $turn",
                ),
            )
        }
    }
}
