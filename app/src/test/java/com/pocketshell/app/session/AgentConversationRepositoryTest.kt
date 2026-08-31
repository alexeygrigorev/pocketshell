package com.pocketshell.app.session

import com.pocketshell.core.agents.AgentDetection
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.agents.ClaudeCodeParser
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.agents.ConversationImage
import com.pocketshell.core.agents.ConversationRole
import com.pocketshell.core.ssh.SshException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #2155: the tmux `-t` target every recorded-source-resolving exec reads
 * `@ps_agent_source_generation` / `@ps_agent_source` against. It is now a
 * required parameter of `detectRecordedSessionForPane` — the source is resolved
 * LIVE inside that exec instead of being handed in by a caller that may be
 * holding a path from a PREVIOUS agent launch in the same session.
 */
private const val RECORDED_SESSION_TARGET: String = "$3"

@OptIn(ExperimentalCoroutinesApi::class)
class AgentConversationRepositoryTest {
    @Test
    fun boundAtCap_discardsTrailingToolsAndPreservesAllMessages() {
        val messages = (0 until 5).map { index ->
            ConversationEvent.Message(
                id = "m$index", agent = AgentKind.Codex,
                role = ConversationRole.Assistant, text = "message $index",
            )
        }
        val tools = (0 until 5).map { index ->
            ConversationEvent.ToolResult(
                id = "t$index", agent = AgentKind.Codex, output = "tool $index",
            )
        }

        val bounded = reconcileAgentEvents(messages + tools, maxEvents = 5)

        assertEquals(messages, bounded)
    }

    @Test
    fun overCapMessages_keepsNewestMessagesInDocumentOrderAcrossInterleavedTools() {
        val events = (0 until 7).flatMap { index ->
            listOf(
                ConversationEvent.Message(
                    id = "m$index", agent = AgentKind.Codex,
                    role = ConversationRole.User, text = "message $index",
                ),
                ConversationEvent.ToolCall(
                    id = "t$index", agent = AgentKind.Codex, name = "shell", input = "$index",
                ),
            )
        }

        val bounded = reconcileAgentEvents(events, maxEvents = 5)

        assertEquals(listOf("m2", "m3", "m4", "m5", "m6"), bounded.map { it.id })
        assertTrue(bounded.all { it is ConversationEvent.Message })
    }

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

    // ----------------------------------------------------------------
    // Issue #1467: a payload-wrapped Codex JSONL transcript (the exact
    // `tests/docker/agent-fixtures/codex-session.jsonl` shape the
    // EmulatorDockerSshSmokeTest seeds, timestamps and all) must parse to
    // non-empty conversation events. The v0.4.25 release gate caught
    // `readInitialEvents(... Codex ...)` returning [] for this form, so a
    // Codex session whose transcript is payload-wrapped rendered an EMPTY
    // conversation view. Reproduce it at the JVM level so the per-push Unit
    // gate catches this class WITHOUT the emulator (#1458 blind spot).
    // ----------------------------------------------------------------
    @Test
    fun codexReadInitialEventsParsesTheExactDockerPayloadWrappedFixture() = runTest {
        // Byte-identical to tests/docker/agent-fixtures/codex-session.jsonl.
        val codexLines = listOf(
            """{"type":"session_meta","timestamp":"2026-05-22T10:00:59Z","payload":{"id":"pocketshell-codex","cwd":"/workspace/pocketshell"}}""",
            """{"type":"event_msg","timestamp":"2026-05-22T10:01:00Z","payload":{"type":"user_message","message":"add a smoke test"}}""",
            """{"type":"response_item","timestamp":"2026-05-22T10:01:01Z","payload":{"type":"function_call","call_id":"codex-call-1","name":"shell","arguments":"./gradlew test"}}""",
            """{"type":"response_item","timestamp":"2026-05-22T10:01:02Z","payload":{"type":"function_call_output","call_id":"codex-call-1","output":"BUILD SUCCESSFUL"}}""",
            """{"type":"response_item","timestamp":"2026-05-22T10:01:03Z","payload":{"type":"message","id":"codex-message-1","role":"assistant","content":[{"type":"output_text","text":"The deterministic Codex fixture is ready."}]}}""",
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

        assertTrue(
            "expected the payload-wrapped Codex fixture to parse into non-empty events, got $events",
            events.isNotEmpty(),
        )
        assertTrue(
            "expected the assistant message text, got ${events.map { it }}",
            events.any {
                it is ConversationEvent.Message &&
                    it.text.contains("deterministic Codex fixture")
            },
        )
        // The user prompt + the assistant reply both surface, in order.
        assertEquals(
            listOf(
                ConversationRole.User to "add a smoke test",
                ConversationRole.Assistant to "The deterministic Codex fixture is ready.",
            ),
            events.filterIsInstance<ConversationEvent.Message>().map { it.role to it.text },
        )
        // The function-call + its output render too.
        assertTrue(events.any { it is ConversationEvent.ToolCall && it.name == "shell" })
        assertTrue(events.any { it is ConversationEvent.ToolResult && it.output.contains("BUILD SUCCESSFUL") })
    }

    // ----------------------------------------------------------------
    // Issue #1467 (ROOT CAUSE / red->green): #1267 added `--max-line-bytes`
    // to the Codex agent-log read, but a host CLI OLDER than #1267 rejects
    // that unknown option and (behind `2>/dev/null || true`) returns EMPTY
    // stdout — so `readInitialEvents` parsed [] and the whole Codex
    // conversation view blanked. This is the exact failure the v0.4.25
    // release gate caught end-to-end against the stale Docker fixture CLI.
    // The repository must retry the read WITHOUT the clamp flag so a
    // version-skewed host still renders its conversation.
    // Without the fallback this test is RED (events == []).
    // ----------------------------------------------------------------
    @Test
    fun codexReadInitialEventsFallsBackWhenHostCliRejectsMaxLineBytes() = runTest {
        val codexLines = listOf(
            """{"type":"session_meta","timestamp":"2026-05-22T10:00:59Z","payload":{"id":"pocketshell-codex","cwd":"/workspace/pocketshell"}}""",
            """{"type":"event_msg","timestamp":"2026-05-22T10:01:00Z","payload":{"type":"user_message","message":"add a smoke test"}}""",
            """{"type":"response_item","timestamp":"2026-05-22T10:01:03Z","payload":{"type":"message","id":"codex-message-1","role":"assistant","content":[{"type":"output_text","text":"The deterministic Codex fixture is ready."}]}}""",
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
            agentLogRejectsMaxLineBytes = true,
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

        assertTrue(
            "expected the version-skew fallback to still parse the Codex conversation, got $events",
            events.any {
                it is ConversationEvent.Message &&
                    it.text.contains("deterministic Codex fixture")
            },
        )
        // The fallback: the clamped read (rejected → empty) THEN a retry
        // without `--max-line-bytes` (the pre-#1267 shape the stale CLI parses).
        val agentLogCommands = session.execCommands.filter { it.contains("pocketshell agent-log") }
        assertEquals(2, agentLogCommands.size)
        assertTrue(agentLogCommands.first().contains("--max-line-bytes"))
        assertFalse(agentLogCommands.last().contains("--max-line-bytes"))
    }

    @Test
    fun codexReadEventsWindowFallsBackWhenHostCliRejectsMaxLineBytes() = runTest {
        // Class coverage (G2): the PAGED window read (readEventsWindow) shares
        // the same `--max-line-bytes` agent-log invocation and the same
        // version-skew blank; the fallback must protect it too.
        val codexLines = listOf(
            """{"type":"event_msg","timestamp":"2026-05-22T10:01:00Z","payload":{"type":"user_message","message":"add a smoke test"}}""",
            """{"type":"response_item","timestamp":"2026-05-22T10:01:03Z","payload":{"type":"message","id":"codex-message-1","role":"assistant","content":[{"type":"output_text","text":"The deterministic Codex fixture is ready."}]}}""",
        )
        val session = FakeSshSession(
            wcOutput = "2\n",
            agentLogOutput = JSONObject(
                mapOf(
                    "count" to codexLines.size,
                    "engine" to "codex",
                    "lines" to JSONArray(codexLines),
                    "path" to "/home/testuser/.codex/sessions/2026/05/22/pocketshell-codex.jsonl",
                    "session" to "pocketshell-codex",
                ),
            ).toString(),
            agentLogRejectsMaxLineBytes = true,
        )

        val window = AgentConversationRepository().readEventsWindow(
            session = session,
            detection = AgentDetection(
                agent = AgentKind.Codex,
                sourcePath = "/home/testuser/.codex/sessions/2026/05/22/pocketshell-codex.jsonl",
                sessionId = "pocketshell-codex",
                confidence = AgentDetection.Confidence.ProcessConfirmed,
            ),
            maxMessages = 20,
        )

        assertTrue(
            "expected the paged window fallback to still parse the Codex conversation, got ${window.events}",
            window.events.any {
                it is ConversationEvent.Message &&
                    it.text.contains("deterministic Codex fixture")
            },
        )
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
        // Issue #1820: the EXACT pane target (`=<session>:`) — a bare `-t` would
        // prefix-match a `<session>-2` sibling and read ITS recorded kind/source
        // (the #819/#825 wrong-source class). tmux accepts a session ID here too.
        assertTrue("must target the pane's session exactly; got $command", command.contains("'=\$3:'"))
    }

    @Test
    fun readRecordedAgentKindIsNullForForeignSessionWithNoOption() = runTest {
        // A foreign session (one we did not launch) has no @ps_agent_kind, so
        // `show-options -v` prints nothing → null → caller keeps detection.
        val session = FakeSshSession(recordedKindOutput = "")

        assertEquals(null, AgentConversationRepository().readRecordedAgentKind(session, "\$9"))
    }

    /**
     * Issue #2155: the standalone `readRecordedAgentSource` round-trip is DELETED
     * (D22) — detection always reads the option folded into the exec it already
     * issues. Its parse semantics survive here, asserted directly on the one
     * shared parser, and the #1820 exact-target property it carried is asserted
     * on the FOLDED read below (the only remaining reader).
     */
    @Test
    fun recordedAgentSourceFromRawAcceptsSourceFromCurrentGeneration() {
        val source = "/home/testuser/.claude/projects/-workspace-proj/current.jsonl"

        assertEquals(
            source,
            AgentConversationRepository().recordedAgentSourceFromRaw("launch-2\t$source\n", "launch-2"),
        )
    }

    @Test
    fun recordedAgentSourceFromRawIgnoresSourceFromStaleGeneration() {
        val source = "/home/testuser/.claude/projects/-workspace-proj/stale.jsonl"

        assertEquals(
            "a source minted under a PREVIOUS generation is the previous agent's " +
                "transcript and must expire (#2155)",
            null,
            AgentConversationRepository().recordedAgentSourceFromRaw("old-launch\t$source\n", "new-launch"),
        )
    }

    @Test
    fun recordedAgentSourceFromRawRejectsRawSourceWhenGenerationIsCurrent() {
        val source = "/home/testuser/.claude/projects/-workspace-proj/raw.jsonl"

        assertEquals(
            "a bare path WHILE a generation is live is unattributable",
            null,
            AgentConversationRepository().recordedAgentSourceFromRaw("$source\n", "current-launch"),
        )
    }

    @Test
    fun recordedAgentSourceFromRawAcceptsLegacyBarePathWithNoGeneration() {
        val source = "/home/testuser/.claude/projects/-workspace-proj/own.jsonl"

        assertEquals(source, AgentConversationRepository().recordedAgentSourceFromRaw("$source\n", ""))
    }

    @Test
    fun recordedAgentSourceFromRawIsNullWhenOptionIsBlank() {
        assertEquals(null, AgentConversationRepository().recordedAgentSourceFromRaw("\n", "launch-2"))
    }

    @Test
    fun foldedRecordedSourceReadTargetsThePanesSessionExactly() = runTest {
        val now = System.currentTimeMillis() / 1000
        val own = "/home/testuser/.claude/projects/-workspace-proj/own.jsonl"
        val session = FakeSshSession(
            recordedSourceGenerationOutput = "launch-2\n",
            recordedSourceOutput = "launch-2\t$own\n",
            detectionOutput = "claude|$now|/workspace/proj|$own",
        )

        AgentConversationRepository().detectRecordedSessionForPane(
            session = session,
            sessionTarget = RECORDED_SESSION_TARGET,
            cwd = "/workspace/proj",
            paneTty = "/dev/pts/7",
            paneCommand = "node",
            recordedKind = AgentKind.ClaudeCode,
        )

        val command = session.execCommands.single()
        assertTrue(command.contains("show-options -v"))
        assertTrue(command.contains("@ps_agent_source"))
        // Issue #1820: the EXACT pane target (`=<session>:`) — a bare `-t` would
        // prefix-match a `<session>-2` sibling and read ITS recorded kind/source
        // (the #819/#825 wrong-source class). tmux accepts a session ID here too.
        assertTrue("must target the pane's session exactly; got $command", command.contains("'=\$3:'"))
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
            sessionTarget = RECORDED_SESSION_TARGET,
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
    fun recordedClaudeSessionPrefersRecordedSourceOverNewerSameKindSibling() = runTest {
        val now = System.currentTimeMillis() / 1000
        val ownPath = "/home/testuser/.claude/projects/-workspace-proj/own.jsonl"
        val siblingPath = "/home/testuser/.claude/projects/-workspace-proj/busier.jsonl"
        val session = FakeSshSession(
            // Issue #2155: the recorded source is read LIVE from the tmux
            // options in the SAME exec as the enumeration (no caller-supplied
            // path), scoped to the current generation.
            recordedSourceGenerationOutput = "launch-1\n",
            recordedSourceOutput = "launch-1\t$ownPath\n",
            detectionOutput = """
                claude|${now - 120}|/workspace/proj|$ownPath
                claude|$now|/workspace/proj|$siblingPath
            """.trimIndent(),
        )

        val detection = AgentConversationRepository().detectRecordedSessionForPane(
            session = session,
            sessionTarget = RECORDED_SESSION_TARGET,
            cwd = "/workspace/proj",
            paneTty = "/dev/pts/7",
            paneCommand = "node",
            recordedKind = AgentKind.ClaudeCode,
        )

        assertEquals(
            "an exact @ps_agent_source match must beat same-kind mtime selection",
            ownPath,
            detection?.sourcePath,
        )
        assertEquals("own", detection?.sessionId)
        assertFalse(
            "the exact-source shortcut should not need a process scan",
            session.execCommands.any { it.contains("ps -eo pid,ppid,tty,comm,args") },
        )
    }

    @Test
    fun recordedClaudeSessionFallsBackToNewestSameKindSiblingWhenRecordedSourceIsAbsent() = runTest {
        val now = System.currentTimeMillis() / 1000
        val ownPath = "/home/testuser/.claude/projects/-workspace-proj/older.jsonl"
        val siblingPath = "/home/testuser/.claude/projects/-workspace-proj/newer.jsonl"
        val session = FakeSshSession(
            detectionOutput = """
                claude|${now - 120}|/workspace/proj|$ownPath
                claude|$now|/workspace/proj|$siblingPath
            """.trimIndent(),
        )

        val detection = AgentConversationRepository().detectRecordedSessionForPane(
            session = session,
            sessionTarget = RECORDED_SESSION_TARGET,
            cwd = "/workspace/proj",
            paneTty = "/dev/pts/7",
            paneCommand = "node",
            recordedKind = AgentKind.ClaudeCode,
        )

        assertEquals(
            "legacy/foreign sessions with no @ps_agent_source must keep the " +
                "existing same-kind mtime selector",
            siblingPath,
            detection?.sourcePath,
        )
        assertEquals("newer", detection?.sessionId)
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
            sessionTarget = RECORDED_SESSION_TARGET,
            cwd = "/workspace/proj",
            paneTty = "/dev/pts/7",
            paneCommand = "bash",
            recordedKind = AgentKind.ClaudeCode,
        )

        assertEquals(AgentKind.ClaudeCode, detection?.agent)
        assertEquals("idle", detection?.sessionId)
    }

    @Test
    fun detectLiveTranscriptForPaneBindsTheFdOwnedKindWithoutAKnownKind() = runTest {
        // Issue #975 (B1) + #1228: the kind-agnostic transcript fallback. With NO
        // known kind (the daemon returned `unknown`) and MORE THAN ONE engine's
        // transcript live in the cwd, the kind must come from the pane's OWN
        // process identity (`/proc/<pid>/fd`), NEVER a cross-kind mtime race. Here
        // a busier Codex sibling flushed MORE RECENTLY, but the pane's own claude
        // (node) process holds the Claude transcript open, so Claude binds.
        val now = System.currentTimeMillis() / 1000
        val ownClaude = "/home/testuser/.claude/projects/-workspace-proj/live.jsonl"
        val session = FakeSshSession(
            detectionOutput = """
                claude|${now - 600}|/workspace/proj|$ownClaude
                codex|$now|/workspace/proj|/home/testuser/.codex/sessions/2026/06/18/rollout-busier.jsonl
            """.trimIndent(),
            hostWideProcessOutput = "1001 1 pts/7 node node",
            // The pane's own node/claude process (pid 1001) holds the Claude
            // transcript fd open — the identity signal that beats the busier
            // Codex sibling's newer mtime.
            procFdOutput = ownClaude,
        )

        val detection = AgentConversationRepository().detectLiveTranscriptForPane(
            session = session,
            cwd = "/workspace/proj",
            paneTty = "/dev/pts/7",
            paneCommand = "node",
        )

        assertEquals(
            "#1228: the kind-agnostic fallback binds the fd-OWNED kind (Claude), " +
                "never the busier same-cwd Codex sibling picked by cross-kind mtime",
            AgentKind.ClaudeCode,
            detection?.agent,
        )
        assertEquals("live", detection?.sessionId)
    }

    @Test
    fun detectLiveTranscriptForPaneReturnsNullWhenNoTranscriptExists() = runTest {
        // Issue #975 (B1 no-flap): a genuine shell with NO recent transcript in
        // the cwd enumerates nothing → null. The fallback is evidence-driven, so a
        // plain shell never resurrects a Conversation surface.
        val session = FakeSshSession(detectionOutput = "")

        val detection = AgentConversationRepository().detectLiveTranscriptForPane(
            session = session,
            cwd = "/workspace/proj",
            paneTty = "/dev/pts/7",
            paneCommand = "bash",
        )

        assertEquals(
            "#975 (B1 no-flap): no live transcript in the cwd binds nothing",
            null,
            detection,
        )
    }

    @Test
    fun detectLiveTranscriptForPaneReturnsNullForBlankCwdOrTty() = runTest {
        // Boundary: blank cwd/tty cannot scope an enumeration → null, never a crash.
        val session = FakeSshSession(
            detectionOutput =
                "claude|1|/workspace/proj|/home/testuser/.claude/projects/-workspace-proj/x.jsonl",
        )
        assertEquals(
            null,
            AgentConversationRepository().detectLiveTranscriptForPane(
                session = session,
                cwd = "   ",
                paneTty = "/dev/pts/7",
                paneCommand = "bash",
            ),
        )
        assertEquals(
            null,
            AgentConversationRepository().detectLiveTranscriptForPane(
                session = session,
                cwd = "/workspace/proj",
                paneTty = "",
                paneCommand = "bash",
            ),
        )
    }

    // ----------------------------------------------------------------
    // Issue #1228: cross-kind mtime wrong-binding (#819/#807 class). When TWO
    // engines' transcripts share one cwd, the masked-agent fallback must pick the
    // kind from the pane's OWN `/proc/<pid>/fd` ownership — NEVER a cross-kind
    // mtime race won by a busier sibling — and REFUSE to bind when no ownership
    // signal is present. Reproduce-first (G10): each two-kind case is RED on the
    // base `candidates.maxByOrNull { it.modifiedAtMillis }?.agent` pick.
    // ----------------------------------------------------------------

    @Test
    fun detectLiveTranscriptForPaneNeverBindsBusierCodexSiblingOverFdOwnedClaude() = runTest {
        // THE reported instance: pane A runs a MASKED Claude; a sibling pane runs a
        // busier Codex that flushed its rollout 3 s ago. Both live in the same cwd.
        // Base code: recordedKind = maxByOrNull(mtime).agent = Codex (newer) →
        // pane A shows the OTHER agent's transcript (wrong-pane foreign content).
        // Fix: the pane's own claude process holds the Claude fd open → bind Claude.
        val now = System.currentTimeMillis() / 1000
        val ownClaude = "/home/testuser/.claude/projects/-workspace-proj/paneA.jsonl"
        val session = FakeSshSession(
            detectionOutput = """
                claude|${now - 400}|/workspace/proj|$ownClaude
                codex|$now|/workspace/proj|/home/testuser/.codex/sessions/2026/07/03/rollout-busier.jsonl
            """.trimIndent(),
            hostWideProcessOutput = "3100 1 pts/5 node node",
            procFdOutput = ownClaude,
        )

        val detection = AgentConversationRepository().detectLiveTranscriptForPane(
            session = session,
            cwd = "/workspace/proj",
            paneTty = "/dev/pts/5",
            paneCommand = "node",
        )

        assertEquals(
            "#1228: a masked-Claude pane must bind Claude via fd ownership, never " +
                "the busier same-cwd Codex sibling the mtime pick would choose",
            AgentKind.ClaudeCode,
            detection?.agent,
        )
        assertEquals("paneA", detection?.sessionId)
        assertEquals(ownClaude, detection?.sourcePath)
    }

    @Test
    fun detectLiveTranscriptForPaneBindsFdOwnedCodexOverBusierClaudeSibling() = runTest {
        // Class coverage — the SYMMETRIC direction: the pane runs Codex while a
        // busier Claude sibling flushed more recently in the same cwd. Base code
        // picks Claude (newer mtime); the fd-owned Codex rollout must win.
        val now = System.currentTimeMillis() / 1000
        val ownCodex = "/home/testuser/.codex/sessions/2026/07/03/rollout-paneB.jsonl"
        val session = FakeSshSession(
            detectionOutput = """
                claude|$now|/workspace/proj|/home/testuser/.claude/projects/-workspace-proj/busier.jsonl
                codex|${now - 400}|/workspace/proj|$ownCodex
            """.trimIndent(),
            hostWideProcessOutput = "3200 1 pts/6 codex /usr/local/bin/codex",
            procFdOutput = ownCodex,
        )

        val detection = AgentConversationRepository().detectLiveTranscriptForPane(
            session = session,
            cwd = "/workspace/proj",
            paneTty = "/dev/pts/6",
            paneCommand = "codex",
        )

        assertEquals(
            "#1228: a Codex pane must bind Codex via fd ownership, never the busier " +
                "same-cwd Claude sibling the mtime pick would choose",
            AgentKind.Codex,
            detection?.agent,
        )
        assertEquals("rollout-paneB", detection?.sessionId)
    }

    @Test
    fun detectLiveTranscriptForPaneRefusesToBindWhenTwoKindsShareCwdWithoutFdOwnership() = runTest {
        // Missing-data class case: two engines' transcripts share the cwd but the
        // pane's process holds NO resolvable transcript fd (older CLI build,
        // non-Linux host, permission error). Base code guesses the newer kind by
        // mtime — here the busier sibling is CLAUDE, which base binds with
        // requireProcessMatch=false → wrong-pane foreign content. The fix REFUSES
        // to bind (null) and surfaces a diagnostic instead.
        val now = System.currentTimeMillis() / 1000
        val diagnostics = mutableListOf<String>()
        val session = FakeSshSession(
            detectionOutput = """
                claude|$now|/workspace/proj|/home/testuser/.claude/projects/-workspace-proj/c.jsonl
                codex|${now - 400}|/workspace/proj|/home/testuser/.codex/sessions/2026/07/03/rollout-x.jsonl
            """.trimIndent(),
            hostWideProcessOutput = "3300 1 pts/7 node node",
            // No fd resolvable → cannot prove which kind this pane runs.
            procFdOutput = "",
        )

        val detection = AgentConversationRepository(diagnostic = { diagnostics += it })
            .detectLiveTranscriptForPane(
                session = session,
                cwd = "/workspace/proj",
                paneTty = "/dev/pts/7",
                paneCommand = "node",
            )

        assertEquals(
            "#1228: two kinds share the cwd with NO fd-ownership signal — must " +
                "refuse to bind, never guess by cross-kind mtime",
            null,
            detection,
        )
        assertTrue(
            "#1228: the refusal must surface a diagnostic naming the ambiguity; got $diagnostics",
            diagnostics.any {
                it.contains("refusing to bind by cross-kind mtime") &&
                    it.contains("Conversation will not bind")
            },
        )
    }

    @Test
    fun detectLiveTranscriptForPaneResolvesFdOwnershipThroughPaneSubtree() = runTest {
        // Nested/sub-agent class case: the pane's tty leader is a shell; the agent
        // (node/claude) runs as a CHILD on a different tty, reachable only through
        // the ppid subtree walk. The fd-ownership scan must include the child pid,
        // so the Claude fd it holds still resolves the kind (over a busier Codex
        // sibling). Base code would pick Codex by mtime.
        val now = System.currentTimeMillis() / 1000
        val ownClaude = "/home/testuser/.claude/projects/-workspace-proj/child.jsonl"
        val session = FakeSshSession(
            detectionOutput = """
                claude|${now - 400}|/workspace/proj|$ownClaude
                codex|$now|/workspace/proj|/home/testuser/.codex/sessions/2026/07/03/rollout-busier.jsonl
            """.trimIndent(),
            hostWideProcessOutput = """
                4000 1 pts/9 bash -bash
                4001 4000 ? node node
            """.trimIndent(),
            procFdOutput = ownClaude,
        )

        val detection = AgentConversationRepository().detectLiveTranscriptForPane(
            session = session,
            cwd = "/workspace/proj",
            paneTty = "/dev/pts/9",
            paneCommand = "bash",
        )

        assertEquals(
            "#1228: fd ownership must resolve through the pane's process SUBTREE " +
                "(the child agent pid), binding Claude over the busier Codex sibling",
            AgentKind.ClaudeCode,
            detection?.agent,
        )
        assertEquals("child", detection?.sessionId)
        assertTrue(
            "#1228: the /proc fd scan must cover the child pid reached via ppid walk",
            session.execCommands.any { it.contains("/proc/") && it.contains(" 4001") },
        )
    }

    @Test
    fun detectLiveTranscriptForPaneBindsOnlyKindWithoutFdOwnershipWhenSingleKindPresent() = runTest {
        // Boundary: only ONE engine's transcript is live in the cwd. There is no
        // cross-kind guess to make, so the fallback binds it even WITHOUT an
        // fd-ownership signal — the #1228 refusal is scoped strictly to the
        // >1-kind case and must NOT regress the #975 single-kind masked-agent bind.
        val now = System.currentTimeMillis() / 1000
        val session = FakeSshSession(
            detectionOutput = """
                claude|$now|/workspace/proj|/home/testuser/.claude/projects/-workspace-proj/solo.jsonl
            """.trimIndent(),
            hostWideProcessOutput = "5000 1 pts/3 node node",
            procFdOutput = "",
        )

        val detection = AgentConversationRepository().detectLiveTranscriptForPane(
            session = session,
            cwd = "/workspace/proj",
            paneTty = "/dev/pts/3",
            paneCommand = "node",
        )

        assertEquals(
            "#1228: a single-kind cwd must still bind (no cross-kind ambiguity)",
            AgentKind.ClaudeCode,
            detection?.agent,
        )
        assertEquals("solo", detection?.sessionId)
    }

    @Test
    fun detectLiveTranscriptForPaneRefusesWhenPaneOwnsNeitherOfTheTwoForeignKinds() = runTest {
        // Foreign-session class case: two engines' transcripts share the cwd (the
        // busier sibling is CLAUDE, which base would bind with
        // requireProcessMatch=false → wrong foreign content), and the pane's own
        // process holds open a transcript that belongs to NEITHER enumerated kind
        // (a stray fd, or a rollout for a cwd not in-window). ownedKinds
        // intersected with presentKinds is empty → refuse to bind rather than
        // mis-attribute a foreign sibling.
        val now = System.currentTimeMillis() / 1000
        val diagnostics = mutableListOf<String>()
        val session = FakeSshSession(
            detectionOutput = """
                claude|$now|/workspace/proj|/home/testuser/.claude/projects/-workspace-proj/c.jsonl
                codex|${now - 400}|/workspace/proj|/home/testuser/.codex/sessions/2026/07/03/rollout-x.jsonl
            """.trimIndent(),
            hostWideProcessOutput = "3400 1 pts/8 node node",
            // A fd that is NOT a recognised transcript convention → classifies to
            // no kind → provides no usable ownership signal.
            procFdOutput = "/home/testuser/somewhere/unrelated.log",
        )

        val detection = AgentConversationRepository(diagnostic = { diagnostics += it })
            .detectLiveTranscriptForPane(
                session = session,
                cwd = "/workspace/proj",
                paneTty = "/dev/pts/8",
                paneCommand = "node",
            )

        assertEquals(
            "#1228: an fd that maps to no enumerated kind is not an ownership " +
                "signal — must refuse to bind, never fall back to mtime",
            null,
            detection,
        )
        assertTrue(
            "#1228: the refusal must be surfaced; got $diagnostics",
            diagnostics.any { it.contains("Conversation will not bind") },
        )
    }

    // ----------------------------------------------------------------
    // Issue #1227: version-skew fragility (#847 class). A drifted/mismatched
    // Codex CLI or a host-helper preamble must NOT silently blank the
    // Conversation view. These reproduce the non-happy state first (G10) and
    // prove the fd fallback / diagnostic / tolerant parse recover it.
    // ----------------------------------------------------------------

    @Test
    fun detectLiveTranscriptForPaneBindsCodexViaFdWhenCwdExtractionYieldsNothing() = runTest {
        // #1227 site 1 (version-skew, reproduce-first): a LIVE Codex pane whose
        // rollout has a drifted/moved `session_meta` cwd field yields ZERO
        // candidate rows from the shell enumeration (the shell-side cwd
        // extraction silently drops it — see
        // detectionCommandTolerantlyExtractsCwd* below for the real-shell proof
        // of that drop). Base code returns null here (candidates empty → no kind
        // → give up), silently blanking Conversation. The fd-owned fallback must
        // degrade to fd-identity: bind the rollout the pane's OWN codex process
        // holds open via /proc/<pid>/fd, never trusting the drifted cwd field.
        val ownPath = "/home/testuser/.codex/sessions/2026/07/03/rollout-drift.jsonl"
        val session = FakeSshSession(
            // Empty enumeration == the version-skew drop the shell would produce.
            detectionOutput = "",
            hostWideProcessOutput = "4242 1 pts/5 codex /usr/local/bin/codex",
            // The pane's own codex process (pid 4242) holds the drifted rollout open.
            procFdOutput = ownPath,
        )

        val detection = AgentConversationRepository().detectLiveTranscriptForPane(
            session = session,
            cwd = "/workspace/proj",
            paneTty = "/dev/pts/5",
            paneCommand = "codex",
        )

        assertEquals(
            "#1227: a live Codex pane whose cwd extraction drifted must still bind " +
                "via the /proc fd owned-rollout fallback, not silently blank",
            AgentKind.Codex,
            detection?.agent,
        )
        assertEquals(ownPath, detection?.sourcePath)
        assertEquals("rollout-drift", detection?.sessionId)
        assertTrue(
            "the fd fallback must resolve the owned rollout via /proc fd",
            session.execCommands.any { it.contains("/proc/") && it.contains(".codex/sessions/") },
        )
    }

    @Test
    fun detectLiveTranscriptForPaneEmitsDiagnosticWhenNothingBinds() = runTest {
        // #1227 criterion 3: a failure to bind must be surfaced as a DIAGNOSTIC,
        // not a silent empty Conversation view. A live-looking Codex pane whose
        // enumeration yields nothing AND whose process holds no resolvable
        // rollout fd cannot bind — the repository must log WHY.
        val diagnostics = mutableListOf<String>()
        val session = FakeSshSession(
            detectionOutput = "",
            hostWideProcessOutput = "4242 1 pts/5 codex /usr/local/bin/codex",
            // No fd-owned rollout resolvable (older Codex build that doesn't hold
            // the fd, non-Linux host, permission error) → genuinely cannot bind.
            procFdOutput = "",
        )

        val detection = AgentConversationRepository(diagnostic = { diagnostics += it })
            .detectLiveTranscriptForPane(
                session = session,
                cwd = "/workspace/proj",
                paneTty = "/dev/pts/5",
                paneCommand = "codex",
            )

        assertEquals(null, detection)
        assertTrue(
            "#1227: a bind failure must surface a diagnostic, not silently blank " +
                "the view; got $diagnostics",
            diagnostics.any { it.contains("Conversation will not bind") },
        )
    }

    @Test
    fun parseAgentLogEnvelopeLinesSkipsPreambleBeforeEnvelope() = runTest {
        // #1227 site 2 (version-skew, reproduce-first): a host-helper preamble
        // line printed BEFORE the JSON envelope (an update banner / warning /
        // MOTD leak) must not blank the whole window. Base code commits to the
        // first non-blank line being the envelope → JSONObject(preamble) fails →
        // returns empty (indistinguishable from "no messages yet"). The parser
        // must scan past non-JSON preamble to the first JSON object carrying
        // `lines`.
        val envelope = JSONObject(
            mapOf(
                "count" to 2,
                "engine" to "codex",
                "lines" to JSONArray(listOf("first line", "second line")),
            ),
        ).toString()
        val outputWithPreamble = buildString {
            appendLine("pocketshell: a newer version is available (run `pocketshell self-update`)")
            appendLine(envelope)
        }

        assertEquals(
            "#1227: an agent-log preamble ahead of the envelope must not blank the window",
            listOf("first line", "second line"),
            AgentConversationRepository().parseAgentLogEnvelopeLines(outputWithPreamble),
        )
    }

    @Test
    fun parseAgentLogEnvelopeLinesSkipsMultiLineAndNonEnvelopeJsonPreamble() = runTest {
        // #1227 class coverage (G2) for site 2: multiple preamble lines, AND a
        // JSON object that is NOT the envelope (no `lines` key) ahead of the real
        // envelope, must all be skipped — not just a single non-JSON banner.
        val envelope = JSONObject(
            mapOf("engine" to "codex", "lines" to JSONArray(listOf("only line"))),
        ).toString()
        val output = buildString {
            appendLine("Warning: locale not set")
            appendLine("")
            // A JSON object that is not the envelope (a stray status line).
            appendLine("""{"status":"ok","note":"warming up"}""")
            appendLine(envelope)
        }

        assertEquals(
            listOf("only line"),
            AgentConversationRepository().parseAgentLogEnvelopeLines(output),
        )
    }

    @Test
    fun parseAgentLogEnvelopeLinesStaysSilentAndEmptyForNoOutput() = runTest {
        // #1227 missing-data case (G2): genuinely no output ("no messages yet")
        // must return empty WITHOUT emitting a drift diagnostic — the diagnostic
        // is reserved for the non-blank-but-unparseable version-skew case.
        val diagnostics = mutableListOf<String>()
        val repo = AgentConversationRepository(diagnostic = { diagnostics += it })

        assertEquals(emptyList<String>(), repo.parseAgentLogEnvelopeLines(""))
        assertEquals(emptyList<String>(), repo.parseAgentLogEnvelopeLines("   \n  \n"))
        assertTrue(
            "blank output is 'no messages yet', not a drift — must stay silent; got $diagnostics",
            diagnostics.isEmpty(),
        )
    }

    @Test
    fun parseAgentLogEnvelopeLinesEmitsDiagnosticForUnparseableOutput() = runTest {
        // #1227 criterion 3 for site 2: non-blank output that carries no JSON
        // envelope with `lines` (total format drift) must surface a diagnostic
        // instead of a silent empty view.
        val diagnostics = mutableListOf<String>()
        val repo = AgentConversationRepository(diagnostic = { diagnostics += it })

        val result = repo.parseAgentLogEnvelopeLines(
            "pocketshell: unknown flag --json\nusage: pocketshell agent-log ...\n",
        )

        assertEquals(emptyList<String>(), result)
        assertTrue(
            "#1227: unparseable agent-log output must surface a diagnostic; got $diagnostics",
            diagnostics.any { it.contains("no JSON envelope") },
        )
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
            sessionTarget = RECORDED_SESSION_TARGET,
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
            sessionTarget = RECORDED_SESSION_TARGET,
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
            sessionTarget = RECORDED_SESSION_TARGET,
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
    fun recordedOpenCodeSessionPrefersRecordedSourceOverNewerSameKindSibling() = runTest {
        val now = System.currentTimeMillis() / 1000
        val ownPath = "/home/testuser/.local/share/opencode/opencode.db#own"
        val siblingPath = "/home/testuser/.local/share/opencode/opencode.db#busier"
        val session = FakeSshSession(
            // Issue #2155: recorded source read live in the detection exec.
            recordedSourceGenerationOutput = "launch-1\n",
            recordedSourceOutput = "launch-1\t$ownPath\n",
            detectionOutput = """
                opencode|${now - 120}|/workspace/proj|$ownPath
                opencode|$now|/workspace/proj|$siblingPath
            """.trimIndent(),
        )

        val detection = AgentConversationRepository().detectRecordedSessionForPane(
            session = session,
            sessionTarget = RECORDED_SESSION_TARGET,
            cwd = "/workspace/proj",
            paneTty = "/dev/pts/3",
            paneCommand = "node",
            recordedKind = AgentKind.OpenCode,
        )

        assertEquals(AgentKind.OpenCode, detection?.agent)
        assertEquals(ownPath, detection?.sourcePath)
        assertEquals("own", detection?.sessionId)
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
            sessionTarget = RECORDED_SESSION_TARGET,
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
            sessionTarget = RECORDED_SESSION_TARGET,
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
    fun recordedCodexSessionDoesNotTrustExactSourceWithoutOwnershipEvidence() = runTest {
        val now = System.currentTimeMillis() / 1000
        val recordedButUnowned = "/home/testuser/.codex/sessions/2026/06/18/rollout-sibling.jsonl"
        val ownCandidate = "/home/testuser/.codex/sessions/2026/06/18/rollout-mine.jsonl"
        val session = FakeSshSession(
            // Issue #2155: recorded source read live in the detection exec.
            recordedSourceGenerationOutput = "launch-1\n",
            recordedSourceOutput = "launch-1\t$recordedButUnowned\n",
            detectionOutput = """
                codex|$now|/workspace/proj|$recordedButUnowned
                codex|${now - 60}|/workspace/proj|$ownCandidate
            """.trimIndent(),
            hostWideProcessOutput = "4242 1 pts/5 codex /usr/local/bin/codex --here",
            procFdOutput = "",
        )

        val detection = AgentConversationRepository().detectRecordedSessionForPane(
            session = session,
            sessionTarget = RECORDED_SESSION_TARGET,
            cwd = "/workspace/proj",
            paneTty = "/dev/pts/5",
            paneCommand = "codex",
            recordedKind = AgentKind.Codex,
        )

        assertEquals(
            "an exact @ps_agent_source must not bypass Codex /proc fd ownership; " +
                "with ambiguous same-cwd rollouts and no owner signal, refuse to bind",
            null,
            detection,
        )
        assertTrue(
            "Codex must still run the process-owned rollout check",
            session.execCommands.any { it.contains("/proc/") && it.contains(".codex/sessions/") },
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
            sessionTarget = RECORDED_SESSION_TARGET,
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
            recordedSourceOutput = "$sourcePath\n",
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
        assertEquals(sourcePath, open.recordedSource)
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
                session.execCommands.single().contains("@ps_agent_source") &&
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
    fun resolveRecordedSessionOpenPrefetchesGenerationScopedRecordedClaudeSourceOverNewerSibling() = runTest {
        val now = System.currentTimeMillis() / 1000
        val ownPath = "/home/testuser/.claude/projects/-workspace-proj/own.jsonl"
        val siblingPath = "/home/testuser/.claude/projects/-workspace-proj/busier.jsonl"
        val session = FakeSshSession(
            recordedKindOutput = "claude\n",
            recordedSourceGenerationOutput = "launch-2\n",
            recordedSourceOutput = "launch-2\t$ownPath\n",
            detectionOutput = """
                claude|${now - 120}|/workspace/proj|$ownPath
                claude|$now|/workspace/proj|$siblingPath
            """.trimIndent(),
            foldedClaudeWcOutput = "2",
            foldedClaudeTail = listOf(
                """{"type":"user","uuid":"u1","message":{"role":"user","content":"older own"}}""",
                """{"type":"assistant","uuid":"a1","message":{"role":"assistant","content":"selected"}}""",
            ).joinToString("\n"),
            emulateFoldedClaudePathFromShell = true,
        )

        val open = AgentConversationRepository().resolveRecordedSessionOpen(
            session = session,
            sessionTarget = "\$3",
            cwd = "/workspace/proj",
            paneTty = "/dev/pts/7",
            paneCommand = "node",
        )

        assertEquals(AgentKind.ClaudeCode, open.recordedKind)
        assertEquals(ownPath, open.recordedSource)
        assertEquals(
            "the generation-scoped exact @ps_agent_source must beat newer " +
                "same-kind mtime during source selection",
            ownPath,
            open.detection?.sourcePath,
        )
        val window = open.prefetchedWindow
        assertNotNull(
            "the folded Claude window must be read from the parsed recorded " +
                "source path, not the newer same-kind sibling",
            window,
        )
        assertEquals(
            listOf("older own", "selected"),
            window!!.events.filterIsInstance<ConversationEvent.Message>().map { it.text },
        )
        val command = session.execCommands.single()
        assertTrue(
            "the combined open command must parse @ps_agent_source into a clean " +
                "path before folding the Claude window; got $command",
            command.contains("ps_recorded_source_path"),
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
            sessionTarget = RECORDED_SESSION_TARGET,
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
            sessionTarget = RECORDED_SESSION_TARGET,
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
            sessionTarget = RECORDED_SESSION_TARGET,
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
}
