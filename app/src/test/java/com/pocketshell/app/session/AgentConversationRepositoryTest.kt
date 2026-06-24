package com.pocketshell.app.session

import com.pocketshell.core.agents.AgentDetection
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.agents.CodexParser
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.agents.ConversationRole
import com.pocketshell.core.agents.MessageSendState
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshException
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream

@OptIn(ExperimentalCoroutinesApi::class)
class AgentConversationRepositoryTest {
    @Test
    fun codexReadInitialEventsUsesAgentLogJsonEnvelope() = runTest {
        val codexLines = listOf(
            """{"type":"session_meta","payload":{"id":"pocketshell-codex","cwd":"/workspace/pocketshell"}}""",
            """{"type":"event_msg","payload":{"type":"user_message","message":"add a smoke test"}}""",
            """{"type":"response_item","payload":{"type":"message","id":"msg_1","role":"assistant","content":[{"type":"output_text","text":"Done"}]}}""",
        )
        val session = FakeSshSession(
            agentLogOutput = JSONObject(
                mapOf(
                    "count" to codexLines.size,
                    "engine" to "codex",
                    "lines" to JSONArray(codexLines),
                    "path" to "/home/testuser/.codex/sessions/2026/05/22/pocketshell-codex.jsonl",
                    "session" to "pocketshell-codex",
                ),
            ).toString(),
        )

        val events = AgentConversationRepository().readInitialEvents(
            session = session,
            detection = AgentDetection(
                agent = AgentKind.Codex,
                sourcePath = "/home/testuser/.codex/sessions/2026/05/22/pocketshell-codex.jsonl",
                sessionId = "pocketshell-codex",
                confidence = AgentDetection.Confidence.ProcessConfirmed,
            ),
            maxLines = 20,
        )

        assertEquals(listOf("add a smoke test", "Done"), events.map { (it as ConversationEvent.Message).text })
        assertEquals(1, session.execCommands.size)
        assertTrue(session.execCommands.single().contains("pocketshell agent-log --engine codex"))
        assertTrue(session.execCommands.single().contains("--session 'pocketshell-codex'"))
        assertTrue(session.execCommands.single().contains("--json --tail 160"))
        assertFalse(session.execCommands.single().contains("tail -n"))
    }

    @Test
    fun tailEventsFromLineReturnsNullWhenSshTailStartThrowsDisconnected() = runTest {
        val session = FakeSshSession(
            tailFailure = SshException("SSH session is not connected"),
        )
        val detection = AgentDetection(
            agent = AgentKind.ClaudeCode,
            sourcePath = "/home/alexey/.claude/projects/pocketshell/session.jsonl",
            sessionId = "session",
            confidence = AgentDetection.Confidence.ProcessConfirmed,
        )

        val job = AgentConversationRepository().tailEventsFromLine(
            session = session,
            detection = detection,
            fromLineExclusive = 42L,
        ) {
            error("no events should be emitted when tail cannot start")
        }

        assertEquals(null, job)
        assertEquals(
            listOf("/home/alexey/.claude/projects/pocketshell/session.jsonl" to 42L),
            session.tailFromLineCalls,
        )
    }

    // ----------------------------------------------------------------
    // Issue #460: the Conversation tab dropped the user's own messages.
    // Both the parsed feed AND the bounded-distinct reconciliation must
    // surface user prose alongside assistant prose, in order, while tool
    // calls/results keep rendering.
    // ----------------------------------------------------------------

    @Test
    fun claudeReadInitialEventsSurfacesBothUserAndAssistantTurnsInOrder() = runTest {
        // A realistic Claude Code JSONL slice: a genuine user prompt, an
        // assistant turn (prose + tool_use), a user-role tool_result line,
        // then a second user prompt and a second assistant reply.
        val jsonl = listOf(
            """{"type":"user","uuid":"u1","message":{"role":"user","content":"inspect the failing tests"}}""",
            """{"type":"assistant","uuid":"a1","message":{"role":"assistant","content":[{"type":"text","text":"I will run the checks."},{"type":"tool_use","id":"toolu_1","name":"Bash","input":{"command":"./gradlew test"}}]}}""",
            """{"type":"user","uuid":"u2","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_1","content":"BUILD FAILED"}]}}""",
            """{"type":"user","uuid":"u3","message":{"role":"user","content":"why did it fail?"}}""",
            """{"type":"assistant","uuid":"a2","message":{"role":"assistant","content":"A dependency is missing."}}""",
        ).joinToString("\n")
        val session = FakeSshSession(jsonlTailOutput = jsonl)
        val detection = AgentDetection(
            agent = AgentKind.ClaudeCode,
            sourcePath = "/home/testuser/.claude/projects/-workspace/c.jsonl",
            sessionId = "c",
            confidence = AgentDetection.Confidence.ProcessConfirmed,
        )

        val events = AgentConversationRepository().readInitialEvents(session, detection)

        val messages = events.filterIsInstance<ConversationEvent.Message>()
        // Both user prompts AND both assistant replies are present, in
        // document order — the bug was that only the assistant side showed.
        assertEquals(
            listOf(
                ConversationRole.User to "inspect the failing tests",
                ConversationRole.Assistant to "I will run the checks.",
                ConversationRole.User to "why did it fail?",
                ConversationRole.Assistant to "A dependency is missing.",
            ),
            messages.map { it.role to it.text },
        )
        // Tool calls/results still render.
        assertTrue(events.any { it is ConversationEvent.ToolCall && it.name == "Bash" })
        assertTrue(events.any { it is ConversationEvent.ToolResult && it.output.contains("BUILD FAILED") })
        // The raw JSONL read is widened past the event budget so a
        // tool-heavy final turn cannot crowd out the user's prompts.
        val tailCommand = session.execCommands.single { it.trimStart().startsWith("tail -n") }
        assertTrue("expected a widened raw-line tail; got $tailCommand", tailCommand.contains("tail -n 1600"))
    }

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
        // transcript entry with the same text must render as ONE turn —
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
        // collapse the optimistic turn — both are real, distinct turns.
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
        // the SAME assistant text twice within one session — once as a
        // streaming `event_msg`/`agent_message` (NO id → CodexParser falls
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
        // the SAME logical turn — the echo+record pair), not a global
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

    // ----------------------------------------------------------------
    // Issue #576: the conversation-tail performance hole.
    //
    // A Codex `/new` replays thousands of JSONL lines; the old per-line
    // ingest ran one O(n^2) reconcile + one StateFlow emit PER line, an
    // ~O(N^3)/N-emit storm. The fix is (1) a LINEAR reconcile (text-keyed
    // optimistic index instead of a nested scan) and (2) a batched/
    // debounced tail that coalesces a burst into one reconcile + one emit
    // per window. Correctness — the final event set, order, and dedup —
    // must be identical to the per-event behaviour.
    // ----------------------------------------------------------------

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
        // batch must produce the SAME final list — same set, same order,
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
        // O(window^2) here — it re-scanned the WHOLE accumulator on every real
        // user message to find the optimistic turn to collapse.
        //
        // Issue #831: this assertion is on a DETERMINISTIC operation counter
        // (ReconcileOpCounter.candidateInspections), NOT wall-clock time. A
        // wall-clock ceiling — or even a best-of-samples timing RATIO — is
        // inherently flaky on a contended CI runner / dev box: load inflates
        // elapsed time even though the algorithm is still linear, so the test
        // flakes (the failure that filed #831). The candidate-inspection count
        // is derived purely from control flow: it counts every accumulated
        // candidate the matching path examines while collapsing an optimistic
        // turn. That is exactly the work the old quadratic scan exploded.
        //
        //  - linear (current) impl: each optimistic id is inspected at most
        //    once across the whole reconcile  => count is O(window)
        //  - quadratic (old) impl: re-scans the whole accumulator per real
        //    user message                       => count is O(window^2)
        //
        // The count is identical on every run regardless of machine load, so
        // the assertion is contention-immune AND still fails a genuine
        // quadratic regression. The `verifies...` sibling test below proves
        // the SAME counter explodes super-linearly under the old scan, so this
        // signal really does catch the regression it guards (red->green).

        // A real Codex `/new` replay sends each turn optimistically (an
        // `optimistic:` echo) and then tails the authoritative real user
        // message back with the same text — the reconcile collapses each pair.
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
            "matching path never ran — counter is vacuous (small=$smallOps large=$largeOps)",
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
        // the candidate-inspection counter — the deterministic signal the
        // linearity test asserts on — actually EXPLODES super-linearly when
        // the reconcile matching path reverts to the old O(window^2) scan.
        //
        // This re-implements the OLD quadratic collapse exactly: instead of
        // the O(1) text-indexed FIFO lookup, it re-scans the WHOLE accumulator
        // (`byId.values.firstOrNull { ... }`) on every real user message to
        // find the optimistic turn to drop, counting each candidate examined
        // into the SAME counter the production guard reads. If that count did
        // not blow past the linear guard's 6x ceiling, the guard would be
        // unable to catch a real regression — so this test fails the day the
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
                "(small=$smallOps large=$largeOps ratio=$ratio) — the guard would be blind",
            ratio > 6.0,
        )
    }

    @Test
    fun batchedTailCoalescesACodexNewReplayIntoFewBatchesNotPerLine() = runTest {
        // The headline #576 scenario: a Codex `/new` rewrites the rollout
        // JSONL with thousands of lines. The batched tail must deliver them
        // as a HANDFUL of batches (one per debounce window), not one
        // callback per line — that is what collapses the N-reconcile /
        // N-emit storm.
        val lineCount = 3_000
        val replay = (0 until lineCount).joinToString("\n") { index ->
            """{"type":"user","uuid":"u$index","message":{"role":"user","content":"replayed line $index"}}"""
        }
        val session = FakeSshSession(tailLines = replay.lines())
        val repository = AgentConversationRepository(
            tailScope = backgroundScope,
            tailBatchWindowMillis = 50L,
        )
        val detection = AgentDetection(
            agent = AgentKind.ClaudeCode,
            sourcePath = "/home/testuser/.claude/projects/-workspace/c.jsonl",
            sessionId = "c",
            confidence = AgentDetection.Confidence.ProcessConfirmed,
        )

        val batches = mutableListOf<List<ConversationEvent>>()
        val job = repository.tailEventsBatchedFromLine(
            session = session,
            detection = detection,
            fromLineExclusive = 0L,
        ) { batch ->
            batches += batch
        }
        assertNotNull(job)
        // Let the drain coroutine fire its window(s). All lines arrive in
        // one synchronous burst from the fake tail, so they coalesce into a
        // single batch.
        advanceTimeBy(200L)
        runCurrent()
        job?.cancel()

        // The whole replay reached the caller...
        val totalEvents = batches.sumOf { it.size }
        assertEquals(lineCount, totalEvents)
        // ...but in a tiny number of batches, NOT one per line. The old
        // per-event shape would have been [lineCount] callbacks.
        assertTrue(
            "expected a handful of batches, got ${batches.size}",
            batches.size <= 5,
        )
        assertEquals(1, session.tailCalls)

        // And the batched events reconcile to exactly the same feed as
        // feeding every line's event individually — correctness preserved.
        val allEvents = batches.flatten()
        var perEvent = emptyList<ConversationEvent>()
        for (event in allEvents) {
            perEvent = reconcileAgentEvents(perEvent + event)
        }
        val batchedReconciled = reconcileAgentEvents(allEvents)
        assertEquals(perEvent.map { it.id }, batchedReconciled.map { it.id })
    }

    // ===================================================================
    // Epic #821 slice #3 (#825): bind the Conversation source to the
    // RECORDED session identity (@ps_agent_kind), not detection. For a
    // session PocketShell launched, the source is computed from
    // (recordedKind, sessionId, cwd) with NO cross-kind path-hint / mtime
    // race — killing the #807/#819/#820 mis-detected-source cluster.
    // ===================================================================

    @Test
    fun readRecordedAgentKindReadsSessionScopedTmuxOption() = runTest {
        val session = FakeSshSession(recordedKindOutput = "claude\n")

        val kind = AgentConversationRepository().readRecordedAgentKind(session, "\$3")

        assertEquals(AgentKind.ClaudeCode, kind)
        val command = session.execCommands.single()
        assertTrue("must read the session-scoped option value; got $command", command.contains("show-options -v"))
        assertTrue(command.contains("@ps_agent_kind"))
        assertTrue("must target the pane's session; got $command", command.contains("'\$3'"))
    }

    @Test
    fun readRecordedAgentKindIsNullForForeignSessionWithNoOption() = runTest {
        // A foreign session (one we did not launch) has no @ps_agent_kind, so
        // `show-options -v` prints nothing → null → caller keeps detection.
        val session = FakeSshSession(recordedKindOutput = "")

        assertEquals(null, AgentConversationRepository().readRecordedAgentKind(session, "\$9"))
    }

    @Test
    fun recordedClaudeSessionBindsToRecordedKindEvenWhenABusierCodexSiblingExists() = runTest {
        // The maintainer's #807/#819/#820 cluster: a session PocketShell
        // launched as CLAUDE, but a busier Codex rollout in the SAME cwd flushed
        // more recently. The deleted cross-kind detector would have a Codex
        // candidate (newer mtime) competing with the Claude candidate; with a
        // live `codex` process on the pane TTY it would bind the Conversation
        // view to CODEX — the wrong kind.
        //
        // detectRecordedSessionForPane must ignore the Codex candidate entirely
        // (recordedKind = Claude) and bind to the Claude transcript computed from
        // (recordedKind, sessionId, cwd). This FAILS on base (detection picks the
        // busier Codex sibling) and passes after the recorded-kind rewire.
        val now = System.currentTimeMillis() / 1000
        val session = FakeSshSession(
            detectionOutput = """
                claude|${now - 600}|/workspace/proj|/home/testuser/.claude/projects/-workspace-proj/claude-sess.jsonl
                codex|$now|/workspace/proj|/home/testuser/.codex/sessions/2026/06/18/rollout-busier.jsonl
            """.trimIndent(),
            // Both a claude and a codex process are live on the pane TTY, so
            // cross-kind detection would happily confirm + pick the newer Codex.
            hostWideProcessOutput = """
                1001 1000 pts/7 codex /usr/local/bin/codex --busy
                1002 1000 pts/7 claude /usr/local/bin/claude
            """.trimIndent(),
        )

        val detection = AgentConversationRepository().detectRecordedSessionForPane(
            session = session,
            cwd = "/workspace/proj",
            paneTty = "/dev/pts/7",
            paneCommand = "node",
            recordedKind = AgentKind.ClaudeCode,
        )

        assertEquals(
            "a recorded Claude session must bind to Claude, never the busier " +
                "same-cwd Codex sibling detection would have picked (#807/#819/#820)",
            AgentKind.ClaudeCode,
            detection?.agent,
        )
        assertEquals("claude-sess", detection?.sessionId)
        assertEquals(
            "/home/testuser/.claude/projects/-workspace-proj/claude-sess.jsonl",
            detection?.sourcePath,
        )
    }

    @Test
    fun recordedClaudeSessionResolvesEvenWhenNoAgentProcessIsObservable() = runTest {
        // The recorded kind is authoritative: an idle recorded Claude session
        // (no live `claude` process visible on the TTY right now) must STILL
        // resolve its source, instead of flapping to null the way foreign
        // per-pane detection (requireProcessMatch) would.
        val now = System.currentTimeMillis() / 1000
        val session = FakeSshSession(
            detectionOutput = """
                claude|$now|/workspace/proj|/home/testuser/.claude/projects/-workspace-proj/idle.jsonl
            """.trimIndent(),
            // No agent process at all on the pane TTY.
            hostWideProcessOutput = "5005 1 pts/7 bash -bash",
        )

        val detection = AgentConversationRepository().detectRecordedSessionForPane(
            session = session,
            cwd = "/workspace/proj",
            paneTty = "/dev/pts/7",
            paneCommand = "bash",
            recordedKind = AgentKind.ClaudeCode,
        )

        assertEquals(AgentKind.ClaudeCode, detection?.agent)
        assertEquals("idle", detection?.sessionId)
    }

    @Test
    fun recordedClaudeSessionResolvesWithoutTheHostWideProcessScan() = runTest {
        // Issue #828 (perf): the recorded-Claude path selects on the cwd-encoded
        // session-id-in-path with requireProcessMatch = false, so the host-wide
        // `ps` round-trip is never consulted for selection. It must therefore NOT
        // be issued — the open path is candidate-enum + window-read only. This is
        // the dropped serial SSH round-trip that helps the cold open clear the
        // <0.3s gate at realistic RTT.
        val now = System.currentTimeMillis() / 1000
        val session = FakeSshSession(
            detectionOutput = """
                claude|$now|/workspace/proj|/home/testuser/.claude/projects/-workspace-proj/c.jsonl
            """.trimIndent(),
            hostWideProcessOutput = "1001 1 pts/7 claude claude",
        )

        val detection = AgentConversationRepository().detectRecordedSessionForPane(
            session = session,
            cwd = "/workspace/proj",
            paneTty = "/dev/pts/7",
            paneCommand = "node",
            recordedKind = AgentKind.ClaudeCode,
        )

        assertEquals(AgentKind.ClaudeCode, detection?.agent)
        assertEquals("c", detection?.sessionId)
        assertEquals(1, session.execCommands.size)
        assertFalse(
            "the recorded-Claude open path must NOT issue the host-wide ps scan " +
                "(selection ignores it for requireProcessMatch=false); got ${session.execCommands}",
            session.execCommands.any { it.contains("ps -eo pid,ppid,tty,comm,args") },
        )
        assertTrue(
            "the recorded-Claude open path is exactly the candidate enumeration; " +
                "got ${session.execCommands}",
            session.execCommands.single().contains("claude_dir="),
        )
    }

    @Test
    fun recordedOpenCodeSessionResolvesWithoutTheHostWideProcessScan() = runTest {
        // Issue #828 (perf): same as the recorded-Claude case — OpenCode carries
        // the session id in its `opencode.db#<id>` candidate path and selects on
        // requireProcessMatch = false, so the host-wide ps scan is skipped.
        val now = System.currentTimeMillis() / 1000
        val session = FakeSshSession(
            detectionOutput = """
                opencode|$now|/workspace/proj|/home/testuser/.local/share/opencode/opencode.db#oc-7
            """.trimIndent(),
            hostWideProcessOutput = "2002 1 pts/3 node opencode",
        )

        val detection = AgentConversationRepository().detectRecordedSessionForPane(
            session = session,
            cwd = "/workspace/proj",
            paneTty = "/dev/pts/3",
            paneCommand = "node",
            recordedKind = AgentKind.OpenCode,
        )

        assertEquals(AgentKind.OpenCode, detection?.agent)
        assertEquals("oc-7", detection?.sessionId)
        assertEquals(1, session.execCommands.size)
        assertFalse(
            "the recorded-OpenCode open path must NOT issue the host-wide ps scan; " +
                "got ${session.execCommands}",
            session.execCommands.any { it.contains("ps -eo pid,ppid,tty,comm,args") },
        )
    }

    @Test
    fun recordedOpenCodeSessionBindsToOpenCodeOverANewerClaudeSibling() = runTest {
        val now = System.currentTimeMillis() / 1000
        val session = FakeSshSession(
            detectionOutput = """
                claude|$now|/workspace/proj|/home/testuser/.claude/projects/-workspace-proj/newer.jsonl
                opencode|${now - 300}|/workspace/proj|/home/testuser/.local/share/opencode/opencode.db#oc-42
            """.trimIndent(),
            hostWideProcessOutput = "2002 1 pts/3 node opencode",
        )

        val detection = AgentConversationRepository().detectRecordedSessionForPane(
            session = session,
            cwd = "/workspace/proj",
            paneTty = "/dev/pts/3",
            paneCommand = "node",
            recordedKind = AgentKind.OpenCode,
        )

        assertEquals(AgentKind.OpenCode, detection?.agent)
        assertEquals("oc-42", detection?.sessionId)
        assertEquals(
            "/home/testuser/.local/share/opencode/opencode.db#oc-42",
            detection?.sourcePath,
        )
    }

    @Test
    fun recordedCodexSessionPicksProcessOwnedRolloutNotTheBusierSibling() = runTest {
        // Codex has no session-id-in-path, so even within the recorded Codex
        // kind a busier same-cwd sibling rollout would win an mtime race. The
        // recorded-Codex path must bind to the rollout THIS pane's own process
        // holds open (`/proc/<pid>/fd`, the #819 mechanism), not the sibling
        // that flushed most recently. FAILS without the process-owned scoping
        // (mtime would pick rollout-busier).
        val now = System.currentTimeMillis() / 1000
        val ownPath = "/home/testuser/.codex/sessions/2026/06/18/rollout-mine.jsonl"
        val busierPath = "/home/testuser/.codex/sessions/2026/06/18/rollout-busier.jsonl"
        val session = FakeSshSession(
            detectionOutput = """
                codex|${now - 120}|/workspace/proj|$ownPath
                codex|$now|/workspace/proj|$busierPath
            """.trimIndent(),
            hostWideProcessOutput = """
                4242 1 pts/5 codex /usr/local/bin/codex --here
            """.trimIndent(),
            // The pane's own codex process (pid 4242) holds rollout-mine open.
            procFdOutput = ownPath,
        )

        val detection = AgentConversationRepository().detectRecordedSessionForPane(
            session = session,
            cwd = "/workspace/proj",
            paneTty = "/dev/pts/5",
            paneCommand = "codex",
            recordedKind = AgentKind.Codex,
        )

        assertEquals(AgentKind.Codex, detection?.agent)
        assertEquals(
            "a recorded Codex session must bind to the rollout its OWN process " +
                "holds open, not the busier same-cwd sibling (#819)",
            ownPath,
            detection?.sourcePath,
        )
        assertEquals("rollout-mine", detection?.sessionId)
        assertTrue(
            "the recorded-Codex path must resolve the process-owned rollout via /proc fd",
            session.execCommands.any { it.contains("/proc/") && it.contains(".codex/sessions/") },
        )
    }

    @Test
    fun recordedCodexSessionReturnsNullWhenOwnershipEvidenceIsAbsentAndCandidatesAreAmbiguous() = runTest {
        // Issue #819 follow-up: with multiple same-cwd Codex rollouts and no
        // fd-owned source path, choosing the newest rollout is only a sibling
        // guess. The recorded-Codex path must decline instead of binding the
        // Conversation tab to whichever rollout flushed last.
        val now = System.currentTimeMillis() / 1000
        val session = FakeSshSession(
            detectionOutput = """
                codex|$now|/workspace/proj|/home/testuser/.codex/sessions/2026/06/18/rollout-newer.jsonl
                codex|${now - 60}|/workspace/proj|/home/testuser/.codex/sessions/2026/06/18/rollout-older.jsonl
            """.trimIndent(),
            hostWideProcessOutput = "4242 1 pts/5 codex /usr/local/bin/codex --here",
            procFdOutput = "",
        )

        val detection = AgentConversationRepository().detectRecordedSessionForPane(
            session = session,
            cwd = "/workspace/proj",
            paneTty = "/dev/pts/5",
            paneCommand = "codex",
            recordedKind = AgentKind.Codex,
        )

        assertEquals(
            "without a process-owned rollout, ambiguous recorded-Codex source " +
                "resolution must not guess the newest same-cwd rollout (#819)",
            null,
            detection,
        )
    }

    @Test
    fun recordedCodexSessionConsidersProcessOwnedRolloutOutsideMminEnumeration() = runTest {
        // Issue #819 follow-up: Codex can keep a live rollout fd open after the
        // JSONL mtime has aged beyond the `find -mmin -120` candidate window.
        // The fd-owned path is the pane identity signal, so it must be added to
        // the JVM candidate set even though detectionOutput does not include it.
        val now = System.currentTimeMillis() / 1000
        val ownedOldPath = "/home/testuser/.codex/sessions/2026/06/18/rollout-live-but-old.jsonl"
        val enumeratedSibling = "/home/testuser/.codex/sessions/2026/06/18/rollout-enumerated-sibling.jsonl"
        val session = FakeSshSession(
            detectionOutput = """
                codex|$now|/workspace/proj|$enumeratedSibling
            """.trimIndent(),
            hostWideProcessOutput = "4242 1 pts/5 codex /usr/local/bin/codex --here",
            procFdOutput = ownedOldPath,
        )

        val detection = AgentConversationRepository().detectRecordedSessionForPane(
            session = session,
            cwd = "/workspace/proj",
            paneTty = "/dev/pts/5",
            paneCommand = "codex",
            recordedKind = AgentKind.Codex,
        )

        assertEquals(AgentKind.Codex, detection?.agent)
        assertEquals(
            "the process-owned rollout must be selectable even when the mmin " +
                "candidate enumeration did not emit it (#819)",
            ownedOldPath,
            detection?.sourcePath,
        )
        assertEquals("rollout-live-but-old", detection?.sessionId)
    }

    @Test
    fun resolveRecordedSessionOpenReadsKindResolvesClaudeAndPrefetchesWindowInOneRoundTrip() = runTest {
        // Issue #828 (perf): the cold-open lever — the `@ps_agent_kind` read, the
        // candidate enumeration, AND the first transcript window are folded into
        // ONE SSH exec for a recorded Claude session. The #825 split path paid
        // THREE serial round-trips (readRecordedAgentKind, enumerate, window read);
        // this is one, so the cold open ≈ the warm switch at realistic RTT.
        val now = System.currentTimeMillis() / 1000
        val sourcePath = "/home/testuser/.claude/projects/-workspace-proj/sess-abc.jsonl"
        val session = FakeSshSession(
            recordedKindOutput = "claude\n",
            detectionOutput = "claude|$now|/workspace/proj|$sourcePath",
            hostWideProcessOutput = "1001 1 pts/7 claude claude",
            // The folded window section: PATH must equal the resolved source so
            // the prefetch binds; wc -l = total lines; tail = the raw JSONL.
            foldedClaudePath = sourcePath,
            foldedClaudeWcOutput = "2",
            foldedClaudeTail = listOf(
                """{"type":"user","uuid":"u1","message":{"role":"user","content":"hello agent"}}""",
                """{"type":"assistant","uuid":"a1","message":{"role":"assistant","content":[{"type":"text","text":"hi back"}]}}""",
            ).joinToString("\n"),
        )

        val open = AgentConversationRepository().resolveRecordedSessionOpen(
            session = session,
            sessionTarget = "\$3",
            cwd = "/workspace/proj",
            paneTty = "/dev/pts/7",
            paneCommand = "node",
        )

        assertEquals(AgentKind.ClaudeCode, open.recordedKind)
        assertFalse("Claude resolves fully in one round-trip; no Codex pass", open.needsCodexResolution)
        assertEquals(AgentKind.ClaudeCode, open.detection?.agent)
        assertEquals("sess-abc", open.detection?.sessionId)
        assertEquals(sourcePath, open.detection?.sourcePath)
        assertEquals(
            "recorded Claude open must be a SINGLE SSH round-trip: kind + candidates " +
                "+ window folded into one exec, no separate readRecordedAgentKind, no ps scan, " +
                "no window-read; got ${session.execCommands}",
            1,
            session.execCommands.size,
        )
        assertTrue(
            "the one exec must carry the @ps_agent_kind read, the candidate enumeration, " +
                "and the Claude window fold; got ${session.execCommands}",
            session.execCommands.single().contains("@ps_agent_kind") &&
                session.execCommands.single().contains("claude_dir=") &&
                session.execCommands.single().contains("@@PS_CLAUDE_WINDOW@@"),
        )
        // The first window is prefetched in the SAME exec — the caller skips its
        // window-read round-trip.
        val window = open.prefetchedWindow
        assertNotNull("recorded Claude open must prefetch the first window", window)
        assertEquals(2L, window!!.tailStartLine)
        assertEquals(
            listOf("hello agent", "hi back"),
            window.events.filterIsInstance<ConversationEvent.Message>().map { it.text },
        )
    }

    @Test
    fun resolveRecordedSessionOpenDropsPrefetchWhenFoldedPathDisagreesWithSelection() = runTest {
        // Issue #828: correctness over the saved round-trip — if the shell's
        // most-recent jsonl differs from the JVM-selected source (a race, or a
        // different file), the prefetch is dropped (null) and the caller does the
        // normal window read against the SELECTED source. Detection still resolves.
        val now = System.currentTimeMillis() / 1000
        val sourcePath = "/home/testuser/.claude/projects/-workspace-proj/sess-abc.jsonl"
        val session = FakeSshSession(
            recordedKindOutput = "claude\n",
            detectionOutput = "claude|$now|/workspace/proj|$sourcePath",
            // The folded section names a DIFFERENT file than the selected source.
            foldedClaudePath = "/home/testuser/.claude/projects/-workspace-proj/some-other.jsonl",
            foldedClaudeWcOutput = "9",
            foldedClaudeTail = """{"type":"user","uuid":"x","message":{"role":"user","content":"stale"}}""",
        )

        val open = AgentConversationRepository().resolveRecordedSessionOpen(
            session = session,
            sessionTarget = "\$3",
            cwd = "/workspace/proj",
            paneTty = "/dev/pts/7",
            paneCommand = "node",
        )

        assertEquals(AgentKind.ClaudeCode, open.detection?.agent)
        assertEquals(sourcePath, open.detection?.sourcePath)
        assertEquals(
            "a folded window from a path that disagrees with the selected source " +
                "must be dropped — no wrong-file transcript",
            null,
            open.prefetchedWindow,
        )
    }

    @Test
    fun resolveRecordedSessionOpenReturnsForeignWhenNoRecordedKind() = runTest {
        // A FOREIGN session (no `@ps_agent_kind`) resolves to recordedKind = null
        // in the one exec; the caller then falls back to foreign detection. The
        // candidate rows in the same exec are ignored — the recorded path is only
        // for sessions PocketShell launched.
        val now = System.currentTimeMillis() / 1000
        val session = FakeSshSession(
            recordedKindOutput = "",
            detectionOutput = """
                claude|$now|/workspace/proj|/home/testuser/.claude/projects/-workspace-proj/c.jsonl
            """.trimIndent(),
        )

        val open = AgentConversationRepository().resolveRecordedSessionOpen(
            session = session,
            sessionTarget = "\$9",
            cwd = "/workspace/proj",
            paneTty = "/dev/pts/2",
            paneCommand = "bash",
        )

        assertEquals(null, open.recordedKind)
        assertEquals(null, open.detection)
        assertFalse(open.needsCodexResolution)
    }

    @Test
    fun resolveRecordedSessionOpenDefersCodexToTheOwnedRolloutPass() = runTest {
        // Codex has no session-id-in-path, so the one-round-trip resolve cannot
        // bind its source without the `/proc/<pid>/fd` owned-rollout pass. It
        // returns recordedKind = Codex + needsCodexResolution = true (no detection
        // yet) so the caller completes it via detectRecordedSessionForPane — the
        // #819 owned-rollout binding stays in exactly one place.
        val now = System.currentTimeMillis() / 1000
        val session = FakeSshSession(
            recordedKindOutput = "codex\n",
            detectionOutput = """
                codex|$now|/workspace/proj|/home/testuser/.codex/sessions/2026/06/18/rollout-x.jsonl
            """.trimIndent(),
        )

        val open = AgentConversationRepository().resolveRecordedSessionOpen(
            session = session,
            sessionTarget = "\$5",
            cwd = "/workspace/proj",
            paneTty = "/dev/pts/5",
            paneCommand = "codex",
        )

        assertEquals(AgentKind.Codex, open.recordedKind)
        assertTrue("Codex needs the second owned-rollout pass", open.needsCodexResolution)
        assertEquals(null, open.detection)
    }

    @Test
    fun resolveRecordedSessionOpenShortCircuitsBlankCwdWithoutIo() = runTest {
        // A blank cwd / tty is unattributable — no recorded kind to act on and no
        // round-trip, exactly like the per-pane detection contract.
        val session = FakeSshSession(recordedKindOutput = "claude\n")

        val open = AgentConversationRepository().resolveRecordedSessionOpen(
            session = session,
            sessionTarget = "\$1",
            cwd = "",
            paneTty = "/dev/pts/1",
            paneCommand = "node",
        )

        assertEquals(null, open.recordedKind)
        assertTrue(
            "an unattributable pane must not trigger any SSH round-trip; got ${session.execCommands}",
            session.execCommands.isEmpty(),
        )
    }

    @Test
    fun recordedSessionWithNoCandidateOfRecordedKindResolvesNull() = runTest {
        // The recorded kind is Codex but only a Claude candidate exists for this
        // cwd (e.g. the Codex rollout has not been written yet). We must NOT
        // fall back to the Claude candidate — the recorded kind is fixed.
        val now = System.currentTimeMillis() / 1000
        val session = FakeSshSession(
            detectionOutput = """
                claude|$now|/workspace/proj|/home/testuser/.claude/projects/-workspace-proj/c.jsonl
            """.trimIndent(),
            hostWideProcessOutput = "1001 1 pts/2 claude claude",
        )

        val detection = AgentConversationRepository().detectRecordedSessionForPane(
            session = session,
            cwd = "/workspace/proj",
            paneTty = "/dev/pts/2",
            paneCommand = "claude",
            recordedKind = AgentKind.Codex,
        )

        assertEquals(
            "a recorded Codex session must not bind to a Claude candidate just " +
                "because no Codex log exists yet",
            null,
            detection,
        )
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

    @Test
    fun openCodeReadInitialEventsExportsSqliteRowsForDetectedSession() = runTest {
        val session = FakeSshSession(
            sqliteOutput = """
                {"message_id":"m1","message_data":"{\"role\":\"user\"}","message_time_created":100,"part_id":"p1","part_data":"{\"type\":\"text\",\"text\":\"check this\"}","part_time_created":101}
            """.trimIndent(),
        )
        val events = AgentConversationRepository().readInitialEvents(session, openCodeDetection())

        val message = events.single() as ConversationEvent.Message
        assertEquals(ConversationRole.User, message.role)
        assertEquals("check this", message.text)
        assertTrue(session.execCommands.single().contains("sqlite3 -readonly"))
        assertTrue(session.execCommands.single().contains("/home/alexey/.local/share/opencode/opencode.db"))
        assertTrue(session.execCommands.single().contains("WHERE session_id = "))
        assertTrue(session.execCommands.single().contains("ses_123"))
    }

    @Test
    fun openCodeReadInitialEventsLimitsMessagesBeforeJoiningParts() = runTest {
        val session = FakeSshSession(
            sqliteOutput = """
                {"message_id":"m2","message_data":"{\"role\":\"assistant\"}","message_time_created":200,"message_time_updated":200,"part_id":"p2a","part_data":"{\"type\":\"output_text\",\"text\":\"new a\"}","part_time_created":201}
                {"message_id":"m2","message_data":"{\"role\":\"assistant\"}","message_time_created":200,"message_time_updated":200,"part_id":"p2b","part_data":"{\"type\":\"output_text\",\"text\":\"new b\"}","part_time_created":202}
            """.trimIndent(),
        )

        val events = AgentConversationRepository().readInitialEvents(
            session = session,
            detection = openCodeDetection(),
            maxLines = 1,
        )

        assertEquals(listOf("new a", "new b"), events.map { (it as ConversationEvent.Message).text })
        val command = session.execCommands.single()
        assertTrue(command.contains("WITH recent_messages AS"))
        assertTrue(command.contains("FROM recent_messages m LEFT JOIN part p"))
        assertTrue(command.contains("LIMIT 1"))
    }

    @Test
    fun openCodeLineCountUsesDbMtimeCursor() = runTest {
        val session = FakeSshSession(statOutputs = ArrayDeque(listOf("1710000000\n")))

        val count = AgentConversationRepository().lineCount(session, openCodeDetection())

        assertEquals(1_710_000_000L, count)
        assertTrue(session.execCommands.single().contains("stat -c '%Y' "))
        assertTrue(session.execCommands.single().contains("stat -f '%m' "))
        assertTrue(session.execCommands.single().contains("opencode.db"))
        assertFalse(session.execCommands.single().contains("wc -l"))
    }

    @Test
    fun openCodeTailExportsSnapshotAndEmitsNewRowsWithoutStartupSeed() = runTest {
        val initialOutput = """
            {"message_id":"m1","message_data":"{\"role\":\"user\"}","message_time_created":100,"part_id":"p1","part_data":"{\"type\":\"text\",\"text\":\"old\"}","part_time_created":101}
        """.trimIndent()
        val racedOutput = """
            {"message_id":"m1","message_data":"{\"role\":\"user\"}","message_time_created":100,"part_id":"p1","part_data":"{\"type\":\"text\",\"text\":\"old\"}","part_time_created":101}
            {"message_id":"m2","message_data":"{\"role\":\"assistant\"}","message_time_created":200,"part_id":"p2","part_data":"{\"type\":\"output_text\",\"text\":\"arrived during startup\"}","part_time_created":201}
        """.trimIndent()
        val session = FakeSshSession(
            sqliteOutputs = ArrayDeque(listOf(initialOutput, racedOutput)),
        )
        val repository = AgentConversationRepository(
            tailScope = backgroundScope,
            openCodePollIntervalMillis = 10L,
        )

        val initialEvents = repository.readInitialEvents(session, openCodeDetection())
        val events = mutableListOf<ConversationEvent>()
        val job = repository.tailEventsFromLine(session, openCodeDetection(), fromLineExclusive = 10L) {
            events += it
        }
        advanceTimeBy(1L)
        job?.cancel()

        assertEquals(listOf("old"), initialEvents.map { (it as ConversationEvent.Message).text })
        assertEquals(
            listOf("old", "arrived during startup"),
            events.map { (it as ConversationEvent.Message).text },
        )
        assertEquals(0, session.tailCalls)
        assertEquals(2, session.execCommands.count { it.contains("sqlite3 -readonly") })
    }

    @Test
    fun openCodeTailEmitsSameIdWhenContentChanges() = runTest {
        val session = FakeSshSession(
            sqliteOutputs = ArrayDeque(
                listOf(
                    """
                        {"message_id":"m1","message_data":"{\"role\":\"assistant\"}","message_time_created":100,"part_id":"p1","part_data":"{\"type\":\"output_text\",\"text\":\"draft\"}","part_time_created":101}
                    """.trimIndent(),
                    """
                        {"message_id":"m1","message_data":"{\"role\":\"assistant\"}","message_time_created":100,"part_id":"p1","part_data":"{\"type\":\"output_text\",\"text\":\"final\"}","part_time_created":101}
                    """.trimIndent(),
                ),
            ),
        )
        val events = mutableListOf<ConversationEvent>()
        val repository = AgentConversationRepository(
            tailScope = backgroundScope,
            openCodePollIntervalMillis = 10L,
        )

        val job = repository.tailEventsFromLine(session, openCodeDetection(), fromLineExclusive = 10L) {
            events += it
        }
        advanceTimeBy(25L)
        job?.cancel()

        assertEquals(listOf("draft", "final"), events.map { (it as ConversationEvent.Message).text })
        assertEquals(listOf("p1", "p1"), events.map { it.id })
        assertEquals(0, session.tailCalls)
        assertFalse(session.execCommands.any { it.contains("stat -c '%Y' ") })
    }

    @Test
    fun openCodeTailDoesNotMissWritesWhenDbMtimeStaysTheSame() = runTest {
        val session = FakeSshSession(
            statOutputs = ArrayDeque(listOf("10\n", "10\n", "10\n")),
            sqliteOutputs = ArrayDeque(
                listOf(
                    """
                        {"message_id":"m1","message_data":"{\"role\":\"user\"}","message_time_created":100,"part_id":"p1","part_data":"{\"type\":\"text\",\"text\":\"old\"}","part_time_created":101}
                    """.trimIndent(),
                    """
                        {"message_id":"m1","message_data":"{\"role\":\"user\"}","message_time_created":100,"part_id":"p1","part_data":"{\"type\":\"text\",\"text\":\"old\"}","part_time_created":101}
                        {"message_id":"m2","message_data":"{\"role\":\"assistant\"}","message_time_created":200,"part_id":"p2","part_data":"{\"type\":\"output_text\",\"text\":\"same-mtime write\"}","part_time_created":201}
                    """.trimIndent(),
                ),
            ),
        )
        val events = mutableListOf<ConversationEvent>()
        val repository = AgentConversationRepository(
            tailScope = backgroundScope,
            openCodePollIntervalMillis = 10L,
        )

        val job = repository.tailEventsFromLine(session, openCodeDetection(), fromLineExclusive = 10L) {
            events += it
        }
        advanceTimeBy(25L)
        job?.cancel()

        assertEquals(
            listOf("old", "same-mtime write"),
            events.map { (it as ConversationEvent.Message).text },
        )
        assertFalse(session.execCommands.any { it.contains("stat -c '%Y' ") })
    }

    @Test
    fun openCodeTailDoesNotReemitChangedOldIdAfterCacheTrim() = runTest {
        val initialRows = openCodeRows(
            (1..DEFAULT_MAX_AGENT_EVENTS * 2).map { index ->
                openCodeRow(index = index, text = "message $index")
            },
        )
        val changedRows = openCodeRows(
            listOf(openCodeRow(index = 1, text = "message 1 updated")) +
                (3..DEFAULT_MAX_AGENT_EVENTS * 2 + 1).map { index ->
                    openCodeRow(index = index, text = "message $index")
                },
        )
        val session = FakeSshSession(
            sqliteOutputs = ArrayDeque(listOf(initialRows, changedRows, changedRows)),
        )
        val events = mutableListOf<ConversationEvent>()
        val repository = AgentConversationRepository(
            tailScope = backgroundScope,
            openCodePollIntervalMillis = 10L,
        )

        val job = repository.tailEventsFromLine(session, openCodeDetection(), fromLineExclusive = 10L) {
            events += it
        }
        advanceTimeBy(25L)
        job?.cancel()

        val texts = events.map { (it as ConversationEvent.Message).text }
        assertEquals(1, texts.count { it == "message 1 updated" })
        assertEquals(DEFAULT_MAX_AGENT_EVENTS * 2 + 2, events.size)
        assertEquals(3, session.execCommands.count { it.contains("sqlite3 -readonly") })
    }

    @Test
    fun openCodeTailDoesNotReemitStableRowsWhenSnapshotHasMoreEventsThanMessages() = runTest {
        val snapshot = openCodeRows(
            (1..DEFAULT_MAX_AGENT_EVENTS * 2).flatMap { index ->
                listOf(
                    openCodePartRow(index = index, partId = "p${index}a", text = "message $index part a"),
                    openCodePartRow(index = index, partId = "p${index}b", text = "message $index part b"),
                )
            },
        )
        val session = FakeSshSession(
            sqliteOutputs = ArrayDeque(listOf(snapshot, snapshot)),
        )
        val events = mutableListOf<ConversationEvent>()
        val repository = AgentConversationRepository(
            tailScope = backgroundScope,
            openCodePollIntervalMillis = 10L,
        )

        val job = repository.tailEventsFromLine(session, openCodeDetection(), fromLineExclusive = 10L) {
            events += it
        }
        advanceTimeBy(15L)
        job?.cancel()

        assertEquals(DEFAULT_MAX_AGENT_EVENTS * 4, events.size)
        assertEquals(events.map { it.id }.distinct(), events.map { it.id })
        assertEquals(2, session.execCommands.count { it.contains("sqlite3 -readonly") })
    }

    @Test
    fun openCodeTailEndsCleanlyWhenSqliteExportThrows() = runTest {
        val session = FakeSshSession(sqliteFailure = SshException("transport closed"))
        val events = mutableListOf<ConversationEvent>()
        val repository = AgentConversationRepository(
            tailScope = backgroundScope,
            openCodePollIntervalMillis = 10L,
        )

        val job = repository.tailEventsFromLine(session, openCodeDetection(), fromLineExclusive = 10L) {
            events += it
        } ?: error("OpenCode detection should start a polling job")
        job.join()

        assertTrue(job.isCompleted)
        assertFalse(job.isCancelled)
        assertTrue(events.isEmpty())
        assertEquals(1, session.execCommands.count { it.contains("sqlite3 -readonly") })
    }

    @Test
    fun recordedOpenCodeSourceBindsSqliteOverStaleClaudeJsonl() = runTest {
        val nowSeconds = System.currentTimeMillis() / 1000
        val session = FakeSshSession(
            detectionOutput = """
                claude|${nowSeconds}|/workspace/pocketshell|/home/testuser/.claude/projects/-workspace-pocketshell/pocketshell-claude.jsonl
                opencode|${nowSeconds - 60}|/workspace/pocketshell|/home/testuser/.local/share/opencode/opencode.db#opencode-1
            """.trimIndent(),
            hostWideProcessOutput = """
                4242 4000 pts/3 node node /usr/local/bin/opencode --fixture /home/testuser/.claude/projects/-workspace-pocketshell/pocketshell-claude.jsonl
            """.trimIndent(),
        )

        // Epic #821 A2: source resolution for a KNOWN (recorded/guessed)
        // OpenCode kind binds to the OpenCode SQLite session, not the stale
        // Claude JSONL. (Kind-guessing is hard-cut; the kind is passed in.)
        val detection = AgentConversationRepository().detectRecordedSessionForPane(
            session = session,
            cwd = "/workspace/pocketshell",
            paneTty = "/dev/pts/3",
            paneCommand = "opencode",
            recordedKind = AgentKind.OpenCode,
        )

        assertEquals(AgentKind.OpenCode, detection?.agent)
        assertEquals("opencode-1", detection?.sessionId)
    }

    // ----------------------------------------------------------------
    // Issue #820: the Conversation tab hard-failed ("Couldn't load this
    // conversation.") for a connected, idle Claude session because the
    // Claude branch of detectionCommand pre-filtered candidates with
    // `find ... -mmin -5`. An idle session (or one with slow JSONL
    // flushing) had its only transcript excluded by that 5-minute gate,
    // so detection returned null and the 12 s watchdog tripped to Failed.
    // The fix widens the Claude window to `-mmin -120` so it agrees with
    // AgentDetector.recentWindowMillis. These tests pin the new window in
    // the generated command and prove an idle Claude pane still resolves.
    // ----------------------------------------------------------------

    @Test
    fun detectionCommandUsesA120MinuteFreshnessWindowForClaude() {
        val command = AgentConversationRepository().detectionCommand("/workspace/pocketshell")

        // The Claude `find` walks the cwd-scoped projects dir. It must use
        // the 120-minute window, NOT the old 5-minute one that excluded
        // idle/slow-flush Claude sessions (#820).
        val claudeFindLine = command.lineSequence()
            .first { it.contains("\$claude_dir") && it.contains("find") }
        assertTrue(
            "Claude find must use -mmin -120 (idle-session freshness, #820); " +
                "got: $claudeFindLine",
            claudeFindLine.contains("-mmin -120"),
        )
        assertFalse(
            "Claude find must NOT use the old tight -mmin -5 window that hard-failed " +
                "idle Claude conversations (#820); got: $claudeFindLine",
            claudeFindLine.contains("-mmin -5 "),
        )
    }

    @Test
    fun recordedClaudeSourceResolvesIdleTranscriptOlderThanFiveMinutes() = runTest {
        // The transcript's mtime is 30 minutes old — beyond the old
        // 5-minute pre-filter but well inside the 120-minute window. The
        // shell `find -mmin -120` (production) keeps emitting it, so the
        // candidate reaches the detector and the pane resolves instead of
        // hard-failing in the Conversation tab. The FakeSshSession returns
        // exactly what the production `find` would emit.
        //
        // NOTE on coverage: the `-mmin -5` -> `-mmin -120` widening is
        // SHELL-side (inside detectionCommand), so this JVM test (which
        // injects the candidate the shell would emit) only proves the
        // detector's own 120-minute recency window accepts a 30-min-old
        // candidate — it is NOT the red->green proof for the shell-filter
        // bug itself. Two siblings cover the actual fix:
        //   * detectionCommandUsesA120MinuteFreshnessWindowForClaude
        //     asserts the generated shell command no longer uses -mmin -5
        //     (FAILS on origin/main).
        //   * The connected E2E
        //     AgentDetectionAcrossEnginesE2eTest
        //       .claudeDetectionFiresWhenJsonlMtimeIsThirtyMinutesAgo
        //     runs the real `find` against a 30-min-stale Claude JSONL on
        //     the Docker fixture (FAILS on origin/main).
        val thirtyMinAgoSeconds = (System.currentTimeMillis() - 30 * 60 * 1000L) / 1000
        val session = FakeSshSession(
            detectionOutput = """
                claude|$thirtyMinAgoSeconds|/workspace/pocketshell|/home/testuser/.claude/projects/-workspace-pocketshell/idle.jsonl
            """.trimIndent(),
            hostWideProcessOutput = "1001 pts/1 00:00:01 claude",
        )

        val detection = AgentConversationRepository().detectRecordedSessionForPane(
            session = session,
            cwd = "/workspace/pocketshell",
            paneTty = "/dev/pts/1",
            paneCommand = "claude",
            recordedKind = AgentKind.ClaudeCode,
        )

        assertNotNull(
            "an idle Claude transcript (mtime 30 min ago) must still resolve so the " +
                "Conversation tab loads instead of hard-failing (#820)",
            detection,
        )
        assertEquals(AgentKind.ClaudeCode, detection?.agent)
        assertEquals("idle", detection?.sessionId)
    }

    @Test
    fun recordedClaudeSourceResolvesTranscriptWhenCwdContainsADot() = runTest {
        // #820 encoding bug: a cwd containing a dot is encoded by Claude as
        // `-...-with-dots-as-dashes`. The detectionCommand emits the same
        // dot-encoded claude_dir, and the path-hint filter must agree, or
        // the candidate is rejected and the pane hard-fails. The session
        // returns a transcript under the correctly dot-encoded directory.
        val nowSeconds = System.currentTimeMillis() / 1000
        val session = FakeSshSession(
            detectionOutput = """
                claude|$nowSeconds|/home/alexey/git/.claude|/home/alexey/.claude/projects/-home-alexey-git--claude/dot.jsonl
            """.trimIndent(),
            hostWideProcessOutput = "1001 pts/1 00:00:01 claude",
        )

        // The production detectionCommand must encode the dot cwd the same
        // way (double-dash) so the seeded path is actually found on a real
        // host; assert that too.
        val command = AgentConversationRepository().detectionCommand("/home/alexey/git/.claude")
        assertTrue(
            "detectionCommand must encode a dot cwd as a dash to match Claude's real " +
                "projects dir (#820); got claude_dir line in: $command",
            command.contains(".claude/projects/-home-alexey-git--claude"),
        )

        val detection = AgentConversationRepository().detectRecordedSessionForPane(
            session = session,
            cwd = "/home/alexey/git/.claude",
            paneTty = "/dev/pts/1",
            paneCommand = "claude",
            recordedKind = AgentKind.ClaudeCode,
        )

        assertNotNull(
            "a Claude transcript whose cwd contains a dot must resolve once the cwd is " +
                "encoded like Claude's real projects dir (#820)",
            detection,
        )
        assertEquals(AgentKind.ClaudeCode, detection?.agent)
        assertEquals("dot", detection?.sessionId)
    }

    @Test
    fun detectionCommandDoesNotMatchBlankOpenCodeCwdColumns() {
        val query = openCodeSqliteQuery(
            AgentConversationRepository().detectionCommand("/home/alexey/git/pocketshell"),
        )

        assertTrue(query.contains("p.worktree IS NOT NULL AND p.worktree != ''"))
        assertTrue(query.contains("s.directory IS NOT NULL AND s.directory != ''"))
        assertFalse(query.contains("LIKE COALESCE(p.worktree, '') || '/%'"))
        assertFalse(query.contains("LIKE COALESCE(s.directory, '') || '/%'"))
    }

    @Test
    fun detectionCommandFiltersCodexCandidatesBySessionMetaPayloadCwd() {
        val command = AgentConversationRepository().detectionCommand("/home/alexey/git/pocketshell")

        assertTrue(command.contains("\"session_meta\""))
        assertTrue(command.contains("\"payload\""))
        assertTrue(command.contains("\"cwd\""))
        assertTrue(command.contains("[ \"${'$'}codex_cwd\" = \"${'$'}cwd\" ] || continue"))
    }

    @Test
    fun detectionCommandUsesLiteralOpenCodeCwdPrefixChecks() {
        val query = openCodeSqliteQuery(
            AgentConversationRepository().detectionCommand("/home/alexey/git/pocket_shell%"),
        )
        val sqlCwd = "'/home/alexey/git/pocket_shell%'"

        assertFalse(query.contains(" LIKE "))
        assertTrue(query.contains("$sqlCwd = p.worktree"))
        assertTrue(
            query.contains(
                "substr($sqlCwd, 1, length(p.worktree) + 1) = " +
                    "p.worktree || '/'",
            ),
        )
        assertTrue(query.contains("$sqlCwd = s.directory"))
        assertTrue(
            query.contains(
                "substr($sqlCwd, 1, length(s.directory) + 1) = " +
                    "s.directory || '/'",
            ),
        )
    }

    @Test
    fun detectionCommandShellQuotesOpenCodeSqliteQueryForDollarInCwd() {
        assertOpenCodeSqliteQueryIsShellSingleQuoted("/tmp/pocketshell-${'$'}USER")
    }

    @Test
    fun detectionCommandShellQuotesOpenCodeSqliteQueryForBacktickInCwd() {
        assertOpenCodeSqliteQueryIsShellSingleQuoted("/tmp/pocketshell-`uname`")
    }

    @Test
    fun detectionCommandShellQuotesOpenCodeSqliteQueryForDoubleQuoteInCwd() {
        assertOpenCodeSqliteQueryIsShellSingleQuoted("/tmp/pocketshell-\"quoted\"")
    }

    // ===================================================================
    // Issue #793: tail-first windowed read (readEventsWindow).
    // ===================================================================

    @Test
    fun readEventsWindowClaudeReadsTailAndReportsMoreOlderWhenFileExceedsBudget() = runTest {
        // The file has 5000 lines total but the first-paint window only reads
        // FIRST_PAINT_MESSAGE_BUDGET * JSONL_RAW_LINES_PER_EVENT (= 240) raw
        // lines. Since 5000 > 240, hasMoreOlder must be true so the UI offers
        // upward paging — WITHOUT having fetched the whole 5000-line history.
        val tailJsonl = listOf(
            """{"type":"user","uuid":"u1","message":{"role":"user","content":"latest question"}}""",
            """{"type":"assistant","uuid":"a1","message":{"role":"assistant","content":"latest answer"}}""",
        ).joinToString("\n")
        val session = FakeSshSession(wcOutput = "5000\n", jsonlTailOutput = tailJsonl)
        val detection = AgentDetection(
            agent = AgentKind.ClaudeCode,
            sourcePath = "/home/testuser/.claude/projects/-workspace/c.jsonl",
            sessionId = "c",
            confidence = AgentDetection.Confidence.ProcessConfirmed,
        )

        val window = AgentConversationRepository().readEventsWindow(
            session = session,
            detection = detection,
            maxMessages = FIRST_PAINT_MESSAGE_BUDGET,
        )

        assertTrue("older messages must remain to page in", window.hasMoreOlder)
        assertEquals(
            listOf("latest question", "latest answer"),
            window.events.filterIsInstance<ConversationEvent.Message>().map { it.text },
        )
        // Tail-first: ONE combined round-trip, capped at the first-paint raw
        // budget — NOT a read of the whole 5000-line history.
        val windowCommand = session.execCommands.single { it.contains("@@PS_WINDOW@@") }
        val rawBudget = FIRST_PAINT_MESSAGE_BUDGET * JSONL_RAW_LINES_PER_EVENT
        assertTrue(
            "expected tail capped at the first-paint budget; got $windowCommand",
            windowCommand.contains("tail -n $rawBudget "),
        )
    }

    @Test
    fun readEventsWindowClaudeReportsNoMoreOlderWhenWholeFileFitsInWindow() = runTest {
        val tailJsonl =
            """{"type":"user","uuid":"u1","message":{"role":"user","content":"only question"}}"""
        val session = FakeSshSession(wcOutput = "3\n", jsonlTailOutput = tailJsonl)
        val detection = AgentDetection(
            agent = AgentKind.ClaudeCode,
            sourcePath = "/home/testuser/.claude/projects/-workspace/c.jsonl",
            sessionId = "c",
            confidence = AgentDetection.Confidence.ProcessConfirmed,
        )

        val window = AgentConversationRepository().readEventsWindow(
            session = session,
            detection = detection,
            maxMessages = FIRST_PAINT_MESSAGE_BUDGET,
        )

        assertFalse("the whole file is in the window", window.hasMoreOlder)
        assertEquals(
            listOf("only question"),
            window.events.filterIsInstance<ConversationEvent.Message>().map { it.text },
        )
    }

    // ===================================================================
    // Issue #817 (slice 1): the windowed read now also reports the
    // follow-tail cursor (tailStartLine) so the cold-open path no longer
    // needs a separate lineCount round-trip before the read.
    // ===================================================================

    @Test
    fun readEventsWindowClaudeReportsFileLineCountAsTailStartLineInOneExec() = runTest {
        val tailJsonl = listOf(
            """{"type":"user","uuid":"u1","message":{"role":"user","content":"q"}}""",
            """{"type":"assistant","uuid":"a1","message":{"role":"assistant","content":"a"}}""",
        ).joinToString("\n")
        val session = FakeSshSession(wcOutput = "4200\n", jsonlTailOutput = tailJsonl)
        val detection = AgentDetection(
            agent = AgentKind.ClaudeCode,
            sourcePath = "/home/testuser/.claude/projects/-workspace/c.jsonl",
            sessionId = "c",
            confidence = AgentDetection.Confidence.ProcessConfirmed,
        )

        val window = AgentConversationRepository().readEventsWindow(
            session = session,
            detection = detection,
            maxMessages = FIRST_PAINT_MESSAGE_BUDGET,
        )

        // The window carries the file's wc -l as the follow cursor — exactly
        // what a separate lineCount exec used to return.
        assertEquals(4200L, window.tailStartLine)
        // And it was derived from the SAME single windowed exec: no standalone
        // `wc -l < ...` round-trip (that would be the redundant lineCount call
        // the cold-open path dropped). The only exec is the sentinel window.
        assertEquals(listOf(true), session.execCommands.map { it.contains("@@PS_WINDOW@@") })
    }

    @Test
    fun readEventsWindowCodexReportsRawFileLineCountAsTailStartLineInOneExec() = runTest {
        val codexLines = listOf(
            """{"type":"session_meta","payload":{"id":"pocketshell-codex","cwd":"/workspace/pocketshell"}}""",
            """{"type":"event_msg","payload":{"type":"user_message","message":"hello"}}""",
            """{"type":"response_item","payload":{"type":"message","id":"m1","role":"assistant","content":[{"type":"output_text","text":"hi"}]}}""",
        )
        val session = FakeSshSession(
            wcOutput = "777\n",
            agentLogOutput = JSONObject(
                mapOf(
                    "count" to codexLines.size,
                    "engine" to "codex",
                    "lines" to JSONArray(codexLines),
                    "path" to "/home/testuser/.codex/sessions/2026/05/22/pocketshell-codex.jsonl",
                    "session" to "pocketshell-codex",
                ),
            ).toString(),
        )
        val detection = AgentDetection(
            agent = AgentKind.Codex,
            sourcePath = "/home/testuser/.codex/sessions/2026/05/22/pocketshell-codex.jsonl",
            sessionId = "pocketshell-codex",
            confidence = AgentDetection.Confidence.ProcessConfirmed,
        )

        val window = AgentConversationRepository().readEventsWindow(
            session = session,
            detection = detection,
            maxMessages = FIRST_PAINT_MESSAGE_BUDGET,
        )

        // The follow tail follows the raw sourcePath, so tailStartLine must be
        // the raw FILE's wc -l (777), NOT the agent-log envelope line count.
        assertEquals(777L, window.tailStartLine)
        assertEquals(
            listOf("hello", "hi"),
            window.events.filterIsInstance<ConversationEvent.Message>().map { it.text },
        )
        // One combined round-trip carried both the raw line count and the
        // agent-log window — no separate lineCount exec.
        assertEquals(1, session.execCommands.size)
        val command = session.execCommands.single()
        assertTrue("expected folded wc -l in the codex window exec", command.contains("wc -l < "))
        assertTrue("expected codex sentinel", command.contains("@@PS_CODEX_WINDOW@@"))
        assertTrue("expected the agent-log window in the same exec", command.contains("pocketshell agent-log --engine codex"))
    }

    private fun assertOpenCodeSqliteQueryIsShellSingleQuoted(cwd: String) {
        val sqliteLine = openCodeSqliteLine(AgentConversationRepository().detectionCommand(cwd))
        val normalizedCwd = cwd.trim().trimEnd('/').ifBlank { "/" }

        assertFalse(sqliteLine.contains("\"SELECT "))
        assertTrue(sqliteLine.contains("sqlite3 -readonly -separator '|' \"${'$'}opencode_db\" 'SELECT "))
        assertTrue(sqliteLine.contains(shellEscapedSqlLiteral(normalizedCwd)))
    }

    private fun openCodeSqliteLine(command: String): String =
        command.lines().single { it.trimStart().startsWith("sqlite3 -readonly -separator") }.trim()

    private fun openCodeSqliteQuery(command: String): String {
        val sqliteLine = openCodeSqliteLine(command)
        val prefix = "sqlite3 -readonly -separator '|' \"${'$'}opencode_db\" "
        val suffix = " 2>/dev/null | while IFS='|' read -r sid updated _worktree _directory; do"
        val quotedQuery = sqliteLine.removePrefix(prefix).removeSuffix(suffix)
        return quotedQuery.removeSurrounding("'").replace("'\\''", "'")
    }

    private fun shellEscapedSqlLiteral(value: String): String {
        val sqlLiteral = "'" + value.replace("'", "''") + "'"
        return sqlLiteral.replace("'", "'\\''")
    }

    private fun openCodeDetection(): AgentDetection = AgentDetection(
        agent = AgentKind.OpenCode,
        sourcePath = "/home/alexey/.local/share/opencode/opencode.db#ses_123",
        sessionId = "ses_123",
        confidence = AgentDetection.Confidence.ProcessConfirmed,
    )

    private fun openCodeRows(rows: List<String>): String = rows.joinToString("\n")

    private fun openCodeRow(index: Int, text: String): String =
        """{"message_id":"m$index","message_data":"{\"role\":\"assistant\"}","message_time_created":$index,"message_time_updated":$index,"part_id":"p$index","part_data":"{\"type\":\"output_text\",\"text\":\"$text\"}","part_time_created":$index}"""

    private fun openCodePartRow(index: Int, partId: String, text: String): String =
        """{"message_id":"m$index","message_data":"{\"role\":\"assistant\"}","message_time_created":$index,"message_time_updated":$index,"part_id":"$partId","part_data":"{\"type\":\"output_text\",\"text\":\"$text\"}","part_time_created":$index}"""

    private class FakeSshSession(
        private val sqliteOutput: String = "",
        private val statOutputs: ArrayDeque<String> = ArrayDeque(),
        private val sqliteOutputs: ArrayDeque<String> = ArrayDeque(),
        private val sqliteFailure: Throwable? = null,
        private val detectionOutput: String = "",
        private val paneProcessOutput: String = "",
        private val hostWideProcessOutput: String = "",
        private val wcOutput: String = "0\n",
        private val tailLines: List<String> = emptyList(),
        private val tailFailure: Throwable? = null,
        private val agentLogOutput: String = "",
        private val jsonlTailOutput: String = "",
        private val recordedKindOutput: String = "",
        private val procFdOutput: String = "",
        // Issue #828: when set, the single-round-trip recorded-open exec emits a
        // folded Claude window section (PATH=<path>, wc -l, sentinel, tail) after
        // the candidate enumeration — the shape the repository's window parse
        // expects. `foldedClaudePath` must equal the resolved source for the
        // prefetch to bind.
        private val foldedClaudePath: String = "",
        private val foldedClaudeWcOutput: String = "0",
        private val foldedClaudeTail: String = "",
    ) : SshSession {
        val execCommands = mutableListOf<String>()
        val tailFromLineCalls = mutableListOf<Pair<String, Long>>()
        var tailCalls = 0

        override val isConnected: Boolean = true

        override suspend fun exec(command: String): ExecResult {
            execCommands += command
            val stdout = when {
                // Issue #828: the single-round-trip recorded-open exec folds the
                // `@ps_agent_kind` read + a sentinel + the candidate enumeration
                // (+ for Claude, a window section) into ONE command. Emit them in
                // that shape so the repository can split the kind, the candidate
                // rows, and the prefetched window.
                command.contains("@@PS_RECORDED_KIND@@") -> buildString {
                    append(recordedKindOutput.trim())
                    append("\n@@PS_RECORDED_KIND@@\n")
                    append(detectionOutput)
                    append("\n@@PS_CLAUDE_WINDOW@@\n")
                    if (foldedClaudePath.isNotBlank()) {
                        append("PATH=").append(foldedClaudePath).append("\n")
                        append(foldedClaudeWcOutput.trim()).append("\n")
                        append("@@PS_CLAUDE_WINDOW@@\n")
                        append(foldedClaudeTail)
                    }
                }
                command.contains("show-options -v") && command.contains("@ps_agent_kind") -> recordedKindOutput
                command.contains("/proc/") && command.contains(".codex/sessions/") -> procFdOutput
                command.contains("claude_dir=") -> detectionOutput
                command.contains("ps -eo pid,ppid,tty,comm,args") -> hostWideProcessOutput
                command.contains("ps -eo pid,tty,comm,args") -> hostWideProcessOutput
                command.contains("ps -t ") -> paneProcessOutput
                command.contains("stat -c '%Y' ") -> statOutputs.removeFirstOrNull() ?: statOutputs.lastOrNull() ?: "0\n"
                // Issue #793: the windowed read combines wc -l + a sentinel + the
                // tail into ONE round-trip. Emit them in that shape so the
                // repository can split total-lines from the tail window.
                command.contains("@@PS_WINDOW@@") ->
                    "${wcOutput.trim()}\n@@PS_WINDOW@@\n$jsonlTailOutput"
                // Issue #817: the Codex windowed read folds wc -l + a sentinel +
                // the agent-log window into ONE round-trip so it carries the
                // raw-file line count (the follow cursor) without a separate
                // lineCount exec.
                command.contains("@@PS_CODEX_WINDOW@@") ->
                    "${wcOutput.trim()}\n@@PS_CODEX_WINDOW@@\n$agentLogOutput"
                command.contains("wc -l < ") -> wcOutput
                command.contains("pocketshell agent-log") -> agentLogOutput
                command.trimStart().startsWith("tail -n") -> jsonlTailOutput
                command.contains("sqlite3 -readonly") -> {
                    sqliteFailure?.let { throw it }
                    sqliteOutputs.removeFirstOrNull() ?: sqliteOutput
                }
                else -> ""
            }
            return ExecResult(stdout = stdout, stderr = "", exitCode = 0)
        }

        override fun tail(path: String, onLine: (String) -> Unit): Job {
            tailCalls += 1
            tailFailure?.let { throw it }
            tailLines.forEach(onLine)
            return Job()
        }

        override fun tail(path: String, fromLineExclusive: Long, onLine: (String) -> Unit): Job {
            tailFromLineCalls += path to fromLineExclusive
            tailCalls += 1
            tailFailure?.let { throw it }
            tailLines.forEach(onLine)
            return Job()
        }

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward {
            throw NotImplementedError()
        }

        override fun startShell(): SshShell {
            throw NotImplementedError()
        }

        override suspend fun uploadFile(file: File, remotePath: String): String =
            error("uploadFile not used in this test")

        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("uploadStream not used in this test")

        override fun close() = Unit
    }
}
