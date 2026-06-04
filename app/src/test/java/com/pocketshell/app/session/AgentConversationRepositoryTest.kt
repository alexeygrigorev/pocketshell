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
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertTrue(session.execCommands.single().contains("--json --tail 20"))
        assertFalse(session.execCommands.single().contains("tail -n"))
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
    fun legacyOpenCodeJsonlDetectionUsesJsonlTailAndLineCount() = runTest {
        val session = FakeSshSession(
            wcOutput = "7\n",
            tailLines = listOf(
                """{"id":"real-user","role":"user","content":"type-back-e2e-opencode","createdAtMillis":12}""",
            ),
        )
        val detection = AgentDetection(
            agent = AgentKind.OpenCode,
            sourcePath = "/home/testuser/.local/share/opencode/pocketshell-rows.jsonl",
            sessionId = "pocketshell-rows",
            confidence = AgentDetection.Confidence.RecentFile,
        )
        val events = mutableListOf<ConversationEvent>()

        val count = AgentConversationRepository().lineCount(session, detection)
        val job = AgentConversationRepository().tailEventsFromLine(session, detection, fromLineExclusive = count) {
            events += it
        }
        job?.cancel()

        assertEquals(7L, count)
        assertEquals(listOf("type-back-e2e-opencode"), events.map { (it as ConversationEvent.Message).text })
        assertEquals(listOf("real-user"), events.map { it.id })
        assertEquals(1, session.tailCalls)
        assertEquals(listOf("/home/testuser/.local/share/opencode/pocketshell-rows.jsonl" to 7L), session.tailFromLineCalls)
        assertTrue(session.execCommands.single().contains("wc -l < "))
        assertFalse(session.execCommands.any { it.contains("sqlite3 -readonly") })
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
            tailLines.forEach(onLine)
            return Job()
        }

        override fun tail(path: String, fromLineExclusive: Long, onLine: (String) -> Unit): Job {
            tailFromLineCalls += path to fromLineExclusive
            tailCalls += 1
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
