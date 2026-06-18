package com.pocketshell.app.session

import com.pocketshell.core.agents.AgentDetection
import com.pocketshell.core.agents.AgentKind
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
import kotlin.system.measureNanoTime

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
        // Regression guard for the O(n^2)-per-reconcile hole: a burst of
        // thousands of distinct user messages must reconcile in roughly
        // LINEAR time. The old nested `byId.entries.firstOrNull` scan ran
        // O(window^2) here — doubling the input quadrupled the time. We
        // assert the larger input is well under the quadratic projection of
        // the smaller one, which the old implementation would blow past.
        //
        // Issue #715: the assertion is on the RELATIVE RATIO of the two
        // sizes, measured as the MINIMUM over several samples — NOT an
        // absolute wall-clock threshold. An absolute "should take < X ns"
        // ceiling is inherently flaky on the contended dev box: heavy box
        // load inflates the absolute time past the ceiling even though the
        // algorithm is still linear. Taking the MIN of several samples is
        // contention-immune in the way that matters here: contention can
        // only ever make a sample SLOWER, so the fastest sample for each
        // size reflects its least-contended run, and the ratio of those two
        // best-case timings is stable under load while still being ~4x for
        // a linear reconcile and ~16x for the old quadratic one.
        fun userHeavyBurst(count: Int): List<ConversationEvent> = buildList {
            repeat(count) { index ->
                add(
                    ConversationEvent.Message(
                        id = "u$index",
                        agent = AgentKind.Codex,
                        role = ConversationRole.User,
                        text = "prompt number $index",
                    ),
                )
            }
        }

        val small = userHeavyBurst(1_000)
        val large = userHeavyBurst(4_000)

        // Warm up so JIT compilation isn't charged to the timed runs.
        repeat(5) {
            reconcileAgentEvents(small, maxEvents = 10_000)
            reconcileAgentEvents(large, maxEvents = 10_000)
        }

        // Take the MIN over several samples per size. Contention only makes
        // a sample slower, so the best (fastest) sample per size is the one
        // least disturbed by box load; comparing two best-case timings keeps
        // the ratio stable under contention.
        fun bestNanos(burst: List<ConversationEvent>): Long {
            var best = Long.MAX_VALUE
            repeat(7) {
                val nanos = measureNanoTime { reconcileAgentEvents(burst, maxEvents = 10_000) }
                if (nanos < best) best = nanos
            }
            return best.coerceAtLeast(1)
        }

        val smallNanos = bestNanos(small)
        val largeNanos = bestNanos(large)
        val ratio = largeNanos.toDouble() / smallNanos

        println(
            "[#576/#715 reconcile linearity] small(1000)=${smallNanos / 1000}us " +
                "large(4000)=${largeNanos / 1000}us ratio=${"%.2f".format(ratio)} " +
                "(linear~4x, quadratic~16x; min-of-samples, contention-immune)",
        )

        // 4x the input. Linear => ~4x the time. Quadratic => ~16x. Assert
        // the best-case RATIO is far below quadratic: allow up to 9x (well
        // under 16x) to tolerate per-run JIT/GC noise while still failing the
        // old O(n^2) implementation, which would be ~16x. This is a relative
        // ratio, never an absolute ns ceiling, so it does not trip under load.
        assertTrue(
            "reconcile time grew super-linearly: small=${smallNanos}ns large=${largeNanos}ns " +
                "ratio=$ratio (best-of-samples; expected ~4x linear, ~16x quadratic)",
            ratio <= 9.0,
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
    fun detectForPanePrefersOpenCodeSqliteOverStaleClaudeJsonlForOpenCodeProcess() = runTest {
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

        val detection = AgentConversationRepository().detectForPane(
            session = session,
            cwd = "/workspace/pocketshell",
            paneTty = "/dev/pts/3",
            paneCommand = "opencode",
        )

        assertEquals(AgentKind.OpenCode, detection?.agent)
        assertEquals("opencode-1", detection?.sessionId)
        assertTrue(session.execCommands.any { it.contains("ps -eo pid,ppid,tty,comm,args") })
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
    fun detectForPaneResolvesIdleClaudeTranscriptOlderThanFiveMinutes() = runTest {
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

        val detection = AgentConversationRepository().detectForPane(
            session = session,
            cwd = "/workspace/pocketshell",
            paneTty = "/dev/pts/1",
            paneCommand = "claude",
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
    fun detectForPaneResolvesClaudeTranscriptWhenCwdContainsADot() = runTest {
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

        val detection = AgentConversationRepository().detectForPane(
            session = session,
            cwd = "/home/alexey/git/.claude",
            paneTty = "/dev/pts/1",
            paneCommand = "claude",
        )

        assertNotNull(
            "a Claude transcript whose cwd contains a dot must resolve once the cwd is " +
                "encoded like Claude's real projects dir (#820)",
            detection,
        )
        assertEquals(AgentKind.ClaudeCode, detection?.agent)
        assertEquals("dot", detection?.sessionId)
    }

    // ----------------------------------------------------------------
    // Issue #252 latency follow-up: detectForPanes must classify the
    // whole list from a CONSTANT number of round-trips (one candidate
    // enumeration + one host-wide ps), not 2 per session.
    // ----------------------------------------------------------------

    @Test
    fun detectForPanesClassifiesMultiplePanesAndStaysAtTwoRoundTrips() = runTest {
        val nowSeconds = System.currentTimeMillis() / 1000
        // Five sessions: a Claude pane, a Codex pane, and three plain
        // shells (different cwds, no agent JSONL for their cwd). This is
        // the realistic "several sessions" list the maintainer flagged.
        val session = FakeSshSession(
            detectionOutput = """
                claude|$nowSeconds|/workspace/claude|/home/testuser/.claude/projects/-workspace-claude/c.jsonl
                codex|$nowSeconds|/workspace/codex|/home/testuser/.codex/sessions/2026/05/28/rollout-x.jsonl
            """.trimIndent(),
            // One host-wide `ps -eo pid,ppid,tty,comm,args` snapshot. The
            // claude process is on pts/1, the codex process on pts/2.
            // The plain shells (pts/3, pts/4, pts/5) carry no agent rows.
            hostWideProcessOutput = """
                1001 pts/1 00:00:01 claude
                2002 pts/2 00:00:01 codex
            """.trimIndent(),
        )

        val panes = listOf(
            AgentConversationRepository.PaneProbe("claude-sess", "/workspace/claude", "/dev/pts/1", "node"),
            AgentConversationRepository.PaneProbe("codex-sess", "/workspace/codex", "/dev/pts/2", "node"),
            AgentConversationRepository.PaneProbe("plain-1", "/workspace/plain1", "/dev/pts/3", "bash"),
            AgentConversationRepository.PaneProbe("plain-2", "/workspace/plain2", "/dev/pts/4", "zsh"),
            AgentConversationRepository.PaneProbe("plain-3", "/workspace/plain3", "/dev/pts/5", "sleep"),
        )

        val detections = AgentConversationRepository().detectForPanes(session, panes)

        assertEquals(AgentKind.ClaudeCode, detections["claude-sess"]?.agent)
        assertEquals(AgentKind.Codex, detections["codex-sess"]?.agent)
        // The three plain shells must not classify — no JSONL for their
        // cwd, no agent process on their TTY.
        assertFalse(detections.containsKey("plain-1"))
        assertFalse(detections.containsKey("plain-2"))
        assertFalse(detections.containsKey("plain-3"))

        // The latency contract: exactly TWO host-wide execs total for
        // FIVE sessions — one candidate enumeration, one host-wide ps.
        // A per-session detectForPane loop would have issued 2 * 5 = 10.
        assertEquals(
            "detectForPanes must issue a constant 2 execs regardless of session " +
                "count; got ${session.execCommands}",
            2,
            session.execCommands.size,
        )
        assertEquals(1, session.execCommands.count { it.contains("claude_dir=") })
        assertEquals(1, session.execCommands.count { it.contains("ps -eo pid,ppid,tty,comm,args") })
        // It must NOT fall back to the per-pane `ps -t` round-trip.
        assertFalse(session.execCommands.any { it.contains("ps -t ") })
    }

    @Test
    fun detectForPanesClassifiesCodexWhenNodeWrapperOwnsPaneAndAgentChildHasNoTty() = runTest {
        val nowSeconds = System.currentTimeMillis() / 1000
        val session = FakeSshSession(
            detectionOutput = """
                codex|$nowSeconds|/home/alexey/git/pocketshell|/home/alexey/.codex/sessions/2026/05/30/rollout-abc.jsonl
            """.trimIndent(),
            hostWideProcessOutput = """
                3145219 1781663 pts/84 MainThread node /tmp/npm-wrapper.js --agent-session rollout-abc
                3145228 3145219 ? codex /home/alexey/.nvm/versions/node/v24.13.1/lib/node_modules/@openai/codex-linux-x64/bin/codex --dangerously-bypass-approvals-and-sandbox
            """.trimIndent(),
        )

        val detections = AgentConversationRepository().detectForPanes(
            session,
            listOf(
                AgentConversationRepository.PaneProbe(
                    key = "git-pocketshell-c",
                    cwd = "/home/alexey/git/pocketshell",
                    paneTty = "/dev/pts/84",
                    paneCommand = "node",
                ),
            ),
        )

        assertEquals(AgentKind.Codex, detections["git-pocketshell-c"]?.agent)
        assertTrue(
            "host-wide scan must keep node wrapper rows so the pane-owned " +
                "parent can pull in an agent child whose own TTY is `?`; got ${session.execCommands}",
            session.execCommands.any { it.contains("claude|codex|opencode|node") },
        )
    }

    @Test
    fun detectForPanesDoesNotClassifyPlainNodeDevServerAsCodex() = runTest {
        val nowSeconds = System.currentTimeMillis() / 1000
        val session = FakeSshSession(
            detectionOutput = """
                codex|$nowSeconds|/workspace/app|/home/testuser/.codex/sessions/2026/05/30/rollout-abc.jsonl
            """.trimIndent(),
            hostWideProcessOutput = """
                4200 1 pts/9 node node /workspace/app/server.js
                4201 4200 ? node /usr/bin/node /workspace/app/worker.js
            """.trimIndent(),
        )

        val detections = AgentConversationRepository().detectForPanes(
            session,
            listOf(
                AgentConversationRepository.PaneProbe(
                    key = "plain-node",
                    cwd = "/workspace/app",
                    paneTty = "/dev/pts/9",
                    paneCommand = "node",
                ),
            ),
        )

        assertFalse(
            "plain node rows must not classify unless the process args name " +
                "an agent command token",
            detections.containsKey("plain-node"),
        )
    }

    @Test
    fun detectForPanesScopesProcessScanPerTtySoSiblingPaneDoesNotBleed() = runTest {
        val nowSeconds = System.currentTimeMillis() / 1000
        // Two sessions in the SAME cwd. Only pts/1 runs Claude; pts/2 is a
        // plain shell. The shared cwd means the same JSONL candidate
        // applies to both — the per-TTY process slice is what keeps pts/2
        // from lighting up (the #186 discipline, preserved in the batch).
        val session = FakeSshSession(
            detectionOutput = """
                claude|$nowSeconds|/workspace/shared|/home/testuser/.claude/projects/-workspace-shared/c.jsonl
            """.trimIndent(),
            hostWideProcessOutput = """
                1001 pts/1 00:00:01 claude
            """.trimIndent(),
        )

        val panes = listOf(
            AgentConversationRepository.PaneProbe("agent-pane", "/workspace/shared", "/dev/pts/1", "node"),
            AgentConversationRepository.PaneProbe("sibling-pane", "/workspace/shared", "/dev/pts/2", "bash"),
        )

        val detections = AgentConversationRepository().detectForPanes(session, panes)

        assertEquals(AgentKind.ClaudeCode, detections["agent-pane"]?.agent)
        assertFalse(
            "a sibling pane sharing the cwd but with no agent on its own TTY must NOT " +
                "classify — the per-TTY slice preserves the #186 per-window discipline",
            detections.containsKey("sibling-pane"),
        )
    }

    @Test
    fun detectForPanesSkipsPanesWithBlankCwdOrTtyWithoutExtraRoundTrips() = runTest {
        // All panes are unattributable (blank cwd or blank tty). The
        // method must short-circuit to zero round-trips — no candidate
        // enumeration, no ps.
        val session = FakeSshSession()
        val panes = listOf(
            AgentConversationRepository.PaneProbe("no-cwd", "", "/dev/pts/1", "node"),
            AgentConversationRepository.PaneProbe("no-tty", "/workspace/x", "", "node"),
        )

        val detections = AgentConversationRepository().detectForPanes(session, panes)

        assertTrue(detections.isEmpty())
        assertTrue(
            "unattributable panes must not trigger any SSH round-trip; got ${session.execCommands}",
            session.execCommands.isEmpty(),
        )
    }

    @Test
    fun detectForPanesEnumeratesEveryUniqueCwdInOneCandidateCommand() = runTest {
        val nowSeconds = System.currentTimeMillis() / 1000
        val session = FakeSshSession(
            detectionOutput = """
                claude|$nowSeconds|/workspace/a|/home/testuser/.claude/projects/-workspace-a/c.jsonl
            """.trimIndent(),
            hostWideProcessOutput = "1001 pts/1 00:00:01 claude",
        )
        val panes = listOf(
            AgentConversationRepository.PaneProbe("a", "/workspace/a", "/dev/pts/1", "node"),
            AgentConversationRepository.PaneProbe("b", "/workspace/b", "/dev/pts/2", "bash"),
        )

        AgentConversationRepository().detectForPanes(session, panes)

        val candidateCommand = session.execCommands.single { it.contains("claude_dir=") }
        // The candidate enumeration must cover BOTH distinct cwds in the
        // single round-trip — the per-cwd detectionCommand blocks are
        // concatenated, so both cwd anchors appear in the one command.
        assertTrue(candidateCommand.contains("cwd='/workspace/a'"))
        assertTrue(candidateCommand.contains("cwd='/workspace/b'"))
        // Each cwd's block is wrapped in its own subshell so state cannot
        // leak between them.
        assertTrue(candidateCommand.contains("("))
        assertTrue(candidateCommand.contains(")"))
    }

    @Test
    fun detectForPanesDoesNotBleedOpenCodeAcrossCwds() = runTest {
        val nowSeconds = System.currentTimeMillis() / 1000
        // An OpenCode session exists for /workspace/oc only. OpenCode's
        // path-hint (the opencode.db) is cwd-AGNOSTIC, so without the
        // per-cwd candidate slice the same candidate would bleed onto the
        // plain pane in /workspace/other. The batch tags each candidate
        // with the cwd that produced it and filters per pane, so it must
        // not bleed.
        val session = FakeSshSession(
            detectionOutput = """
                opencode|$nowSeconds|/workspace/oc|/home/testuser/.local/share/opencode/opencode.db#oc-1
            """.trimIndent(),
            hostWideProcessOutput = """
                3003 pts/1 00:00:01 opencode
                4004 pts/2 00:00:01 opencode
            """.trimIndent(),
        )
        val panes = listOf(
            AgentConversationRepository.PaneProbe("oc-sess", "/workspace/oc", "/dev/pts/1", "node"),
            // Same agent process on pts/2 (e.g. another opencode elsewhere)
            // but no candidate tagged for /workspace/other.
            AgentConversationRepository.PaneProbe("other-sess", "/workspace/other", "/dev/pts/2", "node"),
        )

        val detections = AgentConversationRepository().detectForPanes(session, panes)

        assertEquals(AgentKind.OpenCode, detections["oc-sess"]?.agent)
        assertFalse(
            "an OpenCode candidate discovered for /workspace/oc must not bleed onto a " +
                "pane in /workspace/other — the per-cwd candidate slice replaces the " +
                "shell-side detectionCommand(cwd) scoping the single-pane path gets",
            detections.containsKey("other-sess"),
        )
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
    ) : SshSession {
        val execCommands = mutableListOf<String>()
        val tailFromLineCalls = mutableListOf<Pair<String, Long>>()
        var tailCalls = 0

        override val isConnected: Boolean = true

        override suspend fun exec(command: String): ExecResult {
            execCommands += command
            val stdout = when {
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
