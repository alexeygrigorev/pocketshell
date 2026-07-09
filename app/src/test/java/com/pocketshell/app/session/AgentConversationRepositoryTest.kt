package com.pocketshell.app.session

import com.pocketshell.core.agents.AgentDetection
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.agents.ClaudeCodeParser
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.agents.ConversationImage
import com.pocketshell.core.agents.ConversationRole
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
    fun readRecordedAgentSourceReadsSessionScopedTmuxOption() = runTest {
        val source = "/home/testuser/.claude/projects/-workspace-proj/own.jsonl"
        val session = FakeSshSession(recordedSourceOutput = "$source\n")

        val recordedSource = AgentConversationRepository().readRecordedAgentSource(session, "\$3")

        assertEquals(source, recordedSource)
        val command = session.execCommands.single()
        assertTrue(command.contains("show-options -v"))
        assertTrue(command.contains("@ps_agent_source"))
        assertTrue("must target the pane's session; got $command", command.contains("'\$3'"))
    }

    @Test
    fun readRecordedAgentSourceAcceptsSourceFromCurrentGeneration() = runTest {
        val source = "/home/testuser/.claude/projects/-workspace-proj/current.jsonl"
        val session = FakeSshSession(
            recordedSourceGenerationOutput = "launch-2\n",
            recordedSourceOutput = "launch-2\t$source\n",
        )

        val recordedSource = AgentConversationRepository().readRecordedAgentSource(session, "\$3")

        assertEquals(source, recordedSource)
    }

    @Test
    fun readRecordedAgentSourceIsNullWhenOptionIsBlank() = runTest {
        val session = FakeSshSession(recordedSourceOutput = "\n")

        assertEquals(null, AgentConversationRepository().readRecordedAgentSource(session, "\$9"))
    }

    @Test
    fun readRecordedAgentSourceIgnoresSourceFromStaleGeneration() = runTest {
        val source = "/home/testuser/.claude/projects/-workspace-proj/stale.jsonl"
        val session = FakeSshSession(
            recordedSourceGenerationOutput = "new-launch\n",
            recordedSourceOutput = "old-launch\t$source\n",
        )

        assertEquals(null, AgentConversationRepository().readRecordedAgentSource(session, "\$3"))
    }

    @Test
    fun readRecordedAgentSourceRejectsRawSourceWhenGenerationIsCurrent() = runTest {
        val source = "/home/testuser/.claude/projects/-workspace-proj/raw.jsonl"
        val session = FakeSshSession(
            recordedSourceGenerationOutput = "current-launch\n",
            recordedSourceOutput = "$source\n",
        )

        assertEquals(null, AgentConversationRepository().readRecordedAgentSource(session, "\$3"))
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
    fun recordedClaudeSessionPrefersRecordedSourceOverNewerSameKindSibling() = runTest {
        val now = System.currentTimeMillis() / 1000
        val ownPath = "/home/testuser/.claude/projects/-workspace-proj/own.jsonl"
        val siblingPath = "/home/testuser/.claude/projects/-workspace-proj/busier.jsonl"
        val session = FakeSshSession(
            detectionOutput = """
                claude|${now - 120}|/workspace/proj|$ownPath
                claude|$now|/workspace/proj|$siblingPath
            """.trimIndent(),
        )

        val detection = AgentConversationRepository().detectRecordedSessionForPane(
            session = session,
            cwd = "/workspace/proj",
            paneTty = "/dev/pts/7",
            paneCommand = "node",
            recordedKind = AgentKind.ClaudeCode,
            recordedSource = ownPath,
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
            cwd = "/workspace/proj",
            paneTty = "/dev/pts/7",
            paneCommand = "node",
            recordedKind = AgentKind.ClaudeCode,
            recordedSource = null,
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
    fun recordedOpenCodeSessionPrefersRecordedSourceOverNewerSameKindSibling() = runTest {
        val now = System.currentTimeMillis() / 1000
        val ownPath = "/home/testuser/.local/share/opencode/opencode.db#own"
        val siblingPath = "/home/testuser/.local/share/opencode/opencode.db#busier"
        val session = FakeSshSession(
            detectionOutput = """
                opencode|${now - 120}|/workspace/proj|$ownPath
                opencode|$now|/workspace/proj|$siblingPath
            """.trimIndent(),
        )

        val detection = AgentConversationRepository().detectRecordedSessionForPane(
            session = session,
            cwd = "/workspace/proj",
            paneTty = "/dev/pts/3",
            paneCommand = "node",
            recordedKind = AgentKind.OpenCode,
            recordedSource = ownPath,
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
    fun recordedCodexSessionDoesNotTrustExactSourceWithoutOwnershipEvidence() = runTest {
        val now = System.currentTimeMillis() / 1000
        val recordedButUnowned = "/home/testuser/.codex/sessions/2026/06/18/rollout-sibling.jsonl"
        val ownCandidate = "/home/testuser/.codex/sessions/2026/06/18/rollout-mine.jsonl"
        val session = FakeSshSession(
            detectionOutput = """
                codex|$now|/workspace/proj|$recordedButUnowned
                codex|${now - 60}|/workspace/proj|$ownCandidate
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
            recordedSource = recordedButUnowned,
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

    // ===================================================================
    // Issue #1225: the cold-open transcript read is bounded by LINE count
    // only, never by BYTES — one multi-MB JSONL line (an inline base64
    // image, the #842 path, or a huge tool_result) balloons the read into
    // the JVM heap → jank/OOM on the phone. A server-side per-line byte
    // clamp bounds the read; the oversized line degrades to a VISIBLE
    // truncation marker instead of crashing or vanishing.
    // ===================================================================

    @Test
    fun readInitialEventsByteClampsAMultiMegabyteLineToAVisibleTruncationMarker() = runTest {
        // The pathological transcript: a normal user turn, then ONE ~5 MB line
        // (an inline base64 image, far above the 256 KiB per-line cap), then a
        // normal assistant turn. Cold-open must not materialise the 5 MB line
        // into the heap.
        val hugeBase64 = "A".repeat(5 * 1024 * 1024) // ~5 MB, >> MAX_TRANSCRIPT_LINE_BYTES
        val jsonl = listOf(
            """{"type":"user","uuid":"u1","message":{"role":"user","content":"here is a screenshot"}}""",
            """{"type":"user","uuid":"u2","message":{"role":"user","content":[{"type":"image","source":{"type":"base64","media_type":"image/png","data":"$hugeBase64"}}]}}""",
            """{"type":"assistant","uuid":"a1","message":{"role":"assistant","content":"got it"}}""",
        ).joinToString("\n")
        val session = FakeSshSession(jsonlTailOutput = jsonl)
        val detection = AgentDetection(
            agent = AgentKind.ClaudeCode,
            sourcePath = "/home/testuser/.claude/projects/-workspace/c.jsonl",
            sessionId = "c",
            confidence = AgentDetection.Confidence.ProcessConfirmed,
        )

        val events = AgentConversationRepository().readInitialEvents(session, detection)

        // RED on base: the tail read has NO server-side byte clamp, so the 5 MB
        // line crosses SSH verbatim. GREEN with the fix: the command pipes the
        // tail through the awk clamp.
        val tailCommand = session.execCommands.single { it.trimStart().startsWith("tail -n") }
        assertTrue(
            "the cold-open tail must byte-clamp each line server-side; got: $tailCommand",
            tailCommand.contains(LINE_TRUNCATION_SENTINEL) && tailCommand.contains("awk"),
        )

        // The read is bounded: no event carries the multi-MB payload. RED on
        // base (the 5 MB base64 arrives as an image/text event far above cap).
        val maxEventBytes = events.maxOfOrNull { estimatedEventBytes(it) } ?: 0L
        assertTrue(
            "no cold-open event may exceed the per-line byte cap " +
                "($MAX_TRANSCRIPT_LINE_BYTES); largest was $maxEventBytes bytes",
            maxEventBytes <= MAX_TRANSCRIPT_LINE_BYTES.toLong(),
        )

        // The truncation is USER-VISIBLE (a marker), not a silently dropped
        // message. RED on base (no marker exists).
        val note = events.filterIsInstance<ConversationEvent.SystemNote>()
            .singleOrNull { it.tag == "truncated" }
        assertNotNull("the oversized line must degrade to a visible truncation note", note)
        assertTrue(
            "the marker must name the truncated byte size; got: ${note?.content}",
            note!!.content.contains("truncated"),
        )

        // The normal turns around the pathological line still render.
        val messages = events.filterIsInstance<ConversationEvent.Message>().map { it.text }
        assertTrue("the leading user turn survives; got $messages", messages.contains("here is a screenshot"))
        assertTrue("the trailing assistant turn survives; got $messages", messages.contains("got it"))
    }

    @Test
    fun readEventsWindowByteClampsAMultiMegabyteLineToAVisibleTruncationMarker() = runTest {
        // Class coverage: the windowed cold-open read (readEventsWindow) shares
        // the same balloon risk and must byte-clamp too.
        val hugeToolResult = "B".repeat(4 * 1024 * 1024) // ~4 MB huge tool_result
        val jsonl = listOf(
            """{"type":"user","uuid":"u1","message":{"role":"user","content":"dump the file"}}""",
            """{"type":"user","uuid":"u2","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"$hugeToolResult"}]}}""",
            """{"type":"assistant","uuid":"a1","message":{"role":"assistant","content":"done"}}""",
        ).joinToString("\n")
        val session = FakeSshSession(wcOutput = "12\n", jsonlTailOutput = jsonl)
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

        val windowCommand = session.execCommands.single { it.contains("@@PS_WINDOW@@") }
        assertTrue(
            "the windowed read must byte-clamp each line server-side; got: $windowCommand",
            windowCommand.contains(LINE_TRUNCATION_SENTINEL) && windowCommand.contains("awk"),
        )
        val maxEventBytes = window.events.maxOfOrNull { estimatedEventBytes(it) } ?: 0L
        assertTrue(
            "no windowed event may exceed the per-line byte cap; largest was $maxEventBytes bytes",
            maxEventBytes <= MAX_TRANSCRIPT_LINE_BYTES.toLong(),
        )
        assertNotNull(
            "the oversized tool_result must degrade to a visible truncation note",
            window.events.filterIsInstance<ConversationEvent.SystemNote>()
                .singleOrNull { it.tag == "truncated" },
        )
        val messages = window.events.filterIsInstance<ConversationEvent.Message>().map { it.text }
        assertTrue("normal turns survive; got $messages", messages.contains("dump the file"))
        assertTrue("normal turns survive; got $messages", messages.contains("done"))
    }

    @Test
    fun readInitialEventsLeavesANormalTranscriptUnchangedByTheByteClamp() = runTest {
        // Counter-pin: a normal transcript (every line well under the cap) must
        // be byte-clamped harmlessly — same events, NO spurious truncation
        // marker. Guards against over-clamping legitimate content.
        val jsonl = listOf(
            """{"type":"user","uuid":"u1","message":{"role":"user","content":"run the tests"}}""",
            """{"type":"assistant","uuid":"a1","message":{"role":"assistant","content":"all green"}}""",
        ).joinToString("\n")
        val session = FakeSshSession(jsonlTailOutput = jsonl)
        val detection = AgentDetection(
            agent = AgentKind.ClaudeCode,
            sourcePath = "/home/testuser/.claude/projects/-workspace/c.jsonl",
            sessionId = "c",
            confidence = AgentDetection.Confidence.ProcessConfirmed,
        )

        val events = AgentConversationRepository().readInitialEvents(session, detection)

        assertTrue(
            "a normal transcript must not produce any truncation marker",
            events.none { it is ConversationEvent.SystemNote && it.tag == "truncated" },
        )
        assertEquals(
            listOf(
                ConversationRole.User to "run the tests",
                ConversationRole.Assistant to "all green",
            ),
            events.filterIsInstance<ConversationEvent.Message>().map { it.role to it.text },
        )
        // The clamp is still present in the command — it is a byte CEILING, not a
        // transform of normal content.
        assertTrue(
            session.execCommands.single { it.trimStart().startsWith("tail -n") }
                .contains(LINE_TRUNCATION_SENTINEL),
        )
    }

    @Test
    fun resolveRecordedSessionOpenByteClampsTheFoldedClaudePrefetchWindow() = runTest {
        // Class coverage: the single-round-trip cold-open (resolveRecordedSessionOpen)
        // folds the FIRST Claude window into its exec, so it must byte-clamp that
        // prefetch too — a pathological line in the prefetch would otherwise
        // balloon the very first read.
        val now = System.currentTimeMillis() / 1000
        val sourcePath = "/home/testuser/.claude/projects/-workspace-proj/sess-huge.jsonl"
        val hugeBase64 = "C".repeat(3 * 1024 * 1024)
        val session = FakeSshSession(
            recordedKindOutput = "claude\n",
            recordedSourceOutput = "$sourcePath\n",
            detectionOutput = "claude|$now|/workspace/proj|$sourcePath",
            hostWideProcessOutput = "1001 1 pts/7 claude claude",
            foldedClaudeWcOutput = "3",
            foldedClaudeTail = listOf(
                """{"type":"user","uuid":"u1","message":{"role":"user","content":"look at this"}}""",
                """{"type":"user","uuid":"u2","message":{"role":"user","content":[{"type":"image","source":{"type":"base64","media_type":"image/png","data":"$hugeBase64"}}]}}""",
                """{"type":"assistant","uuid":"a1","message":{"role":"assistant","content":"seen"}}""",
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

        assertTrue(
            "the folded cold-open exec must byte-clamp the prefetch tail; got: " +
                session.execCommands.single(),
            session.execCommands.single().contains(LINE_TRUNCATION_SENTINEL) &&
                session.execCommands.single().contains("awk"),
        )
        val window = open.prefetchedWindow
        assertNotNull("recorded Claude open must prefetch a window", window)
        val maxEventBytes = window!!.events.maxOfOrNull { estimatedEventBytes(it) } ?: 0L
        assertTrue(
            "no prefetched event may exceed the per-line byte cap; largest was $maxEventBytes bytes",
            maxEventBytes <= MAX_TRANSCRIPT_LINE_BYTES.toLong(),
        )
        assertNotNull(
            "the oversized prefetch line must degrade to a visible truncation note",
            window.events.filterIsInstance<ConversationEvent.SystemNote>()
                .singleOrNull { it.tag == "truncated" },
        )
        val messages = window.events.filterIsInstance<ConversationEvent.Message>().map { it.text }
        assertTrue("normal prefetch turns survive; got $messages", messages.contains("look at this"))
        assertTrue("normal prefetch turns survive; got $messages", messages.contains("seen"))
    }

    @Test
    fun parseTranscriptTailLinesTurnsTheClampMarkerIntoAVisibleNoteAndParsesNormalLines() {
        // Unit-level: the parser helper maps a LINE_TRUNCATION_SENTINEL line to a
        // visible SystemNote (never silently dropped) and hands normal lines to
        // the real parser unchanged, with ordinal-stable placeholder ids.
        val lines = sequenceOf(
            """{"type":"user","uuid":"u1","message":{"role":"user","content":"first"}}""",
            "${LINE_TRUNCATION_SENTINEL}5242880",
            """{"type":"assistant","uuid":"a1","message":{"role":"assistant","content":"second"}}""",
            "${LINE_TRUNCATION_SENTINEL}9999999",
        )

        val events = parseTranscriptTailLines(ClaudeCodeParser(), AgentKind.ClaudeCode, lines)

        val notes = events.filterIsInstance<ConversationEvent.SystemNote>().filter { it.tag == "truncated" }
        assertEquals("both markers become visible notes", 2, notes.size)
        assertEquals(
            "placeholder ids are ordinal-stable within a read",
            listOf("ps-truncated-line-0", "ps-truncated-line-1"),
            notes.map { it.id },
        )
        assertTrue("the marker note names the byte size", notes.first().content.contains("5242880"))
        val messages = events.filterIsInstance<ConversationEvent.Message>().map { it.text }
        assertEquals(listOf("first", "second"), messages)
    }

    // ===================================================================
    // Issue #1267: extend the #1225 byte-bound to the Codex AND OpenCode
    // cold-open reads (a G2 class-coverage gap — they use different read
    // mechanisms than the Claude flat-JSONL tail). A multi-MB single line
    // (huge tool_result / inline image) must degrade to a VISIBLE marker,
    // per agent kind, instead of ballooning the read or vanishing.
    // ===================================================================

    @Test
    fun codexReadInitialEventsByteClampsAMultiMegabyteLineToAVisibleTruncationMarker() = runTest {
        // The Codex read goes through `pocketshell agent-log`, so the clamp is the
        // tool-side `--max-line-bytes` flag; the giant envelope line is degraded
        // server-side to a marker. FakeSshSession emulates that server clamp.
        val hugeText = "A".repeat(5 * 1024 * 1024) // ~5 MB, >> MAX_TRANSCRIPT_LINE_BYTES
        val codexLines = listOf(
            """{"type":"event_msg","payload":{"type":"user_message","message":"look at this"}}""",
            """{"type":"response_item","payload":{"type":"message","id":"m2","role":"assistant","content":[{"type":"output_text","text":"$hugeText"}]}}""",
            """{"type":"response_item","payload":{"type":"message","id":"m3","role":"assistant","content":[{"type":"output_text","text":"seen"}]}}""",
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

        // RED on base: the Codex read passes no byte cap to the tool.
        val command = session.execCommands.single { it.contains("pocketshell agent-log --engine codex") }
        assertTrue(
            "the Codex cold-open must byte-clamp server-side via --max-line-bytes; got: $command",
            command.contains("--max-line-bytes $MAX_TRANSCRIPT_LINE_BYTES"),
        )
        val maxEventBytes = events.maxOfOrNull { estimatedEventBytes(it) } ?: 0L
        assertTrue(
            "no Codex cold-open event may exceed the per-line byte cap; largest was $maxEventBytes bytes",
            maxEventBytes <= MAX_TRANSCRIPT_LINE_BYTES.toLong(),
        )
        assertNotNull(
            "the oversized Codex line must degrade to a visible truncation note",
            events.filterIsInstance<ConversationEvent.SystemNote>().singleOrNull { it.tag == "truncated" },
        )
        val messages = events.filterIsInstance<ConversationEvent.Message>().map { it.text }
        assertTrue("normal Codex turns survive; got $messages", messages.contains("look at this"))
        assertTrue("normal Codex turns survive; got $messages", messages.contains("seen"))
    }

    @Test
    fun codexReadEventsWindowByteClampsAMultiMegabyteLineToAVisibleTruncationMarker() = runTest {
        // Class coverage: the windowed Codex cold-open (readEventsWindow) folds
        // the agent-log call into its exec and must byte-clamp it too.
        val hugeText = "B".repeat(4 * 1024 * 1024)
        val codexLines = listOf(
            """{"type":"event_msg","payload":{"type":"user_message","message":"dump it"}}""",
            """{"type":"response_item","payload":{"type":"message","id":"m2","role":"assistant","content":[{"type":"output_text","text":"$hugeText"}]}}""",
            """{"type":"response_item","payload":{"type":"message","id":"m3","role":"assistant","content":[{"type":"output_text","text":"done"}]}}""",
        )
        val session = FakeSshSession(
            wcOutput = "3\n",
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

        val window = AgentConversationRepository().readEventsWindow(
            session = session,
            detection = AgentDetection(
                agent = AgentKind.Codex,
                sourcePath = "/home/testuser/.codex/sessions/2026/05/22/pocketshell-codex.jsonl",
                sessionId = "pocketshell-codex",
                confidence = AgentDetection.Confidence.ProcessConfirmed,
            ),
            maxMessages = FIRST_PAINT_MESSAGE_BUDGET,
        )

        val command = session.execCommands.single { it.contains("pocketshell agent-log --engine codex") }
        assertTrue(
            "the windowed Codex read must byte-clamp via --max-line-bytes; got: $command",
            command.contains("--max-line-bytes $MAX_TRANSCRIPT_LINE_BYTES"),
        )
        val maxEventBytes = window.events.maxOfOrNull { estimatedEventBytes(it) } ?: 0L
        assertTrue(
            "no windowed Codex event may exceed the per-line byte cap; largest was $maxEventBytes bytes",
            maxEventBytes <= MAX_TRANSCRIPT_LINE_BYTES.toLong(),
        )
        assertNotNull(
            "the oversized Codex line must degrade to a visible truncation note",
            window.events.filterIsInstance<ConversationEvent.SystemNote>().singleOrNull { it.tag == "truncated" },
        )
        val messages = window.events.filterIsInstance<ConversationEvent.Message>().map { it.text }
        assertTrue("normal Codex turns survive; got $messages", messages.contains("dump it"))
        assertTrue("normal Codex turns survive; got $messages", messages.contains("done"))
    }

    @Test
    fun openCodeReadInitialEventsByteClampsAMultiMegabyteRowToAVisibleTruncationMarker() = runTest {
        // The OpenCode read is a `sqlite3` export, one JSON object per line, so
        // the SAME per-line `awk` byte clamp #1225 used for Claude is the
        // format-appropriate bound. A row whose `part_data` is a multi-MB tool
        // result must degrade to a visible marker, not balloon the read.
        val hugePart = "D".repeat(4 * 1024 * 1024)
        val session = FakeSshSession(
            sqliteOutput = openCodeRows(
                listOf(
                    openCodeRow(1, "hello"),
                    openCodeRow(2, hugePart),
                    openCodeRow(3, "bye"),
                ),
            ),
        )

        val events = AgentConversationRepository().readInitialEvents(session, openCodeDetection())

        // RED on base: the OpenCode sqlite export is not piped through the clamp.
        val command = session.execCommands.single { it.contains("sqlite3 -readonly") }
        assertTrue(
            "the OpenCode cold-open must byte-clamp each row server-side; got: $command",
            command.contains(LINE_TRUNCATION_SENTINEL) && command.contains("awk"),
        )
        val maxEventBytes = events.maxOfOrNull { estimatedEventBytes(it) } ?: 0L
        assertTrue(
            "no OpenCode cold-open event may exceed the per-line byte cap; largest was $maxEventBytes bytes",
            maxEventBytes <= MAX_TRANSCRIPT_LINE_BYTES.toLong(),
        )
        val note = events.filterIsInstance<ConversationEvent.SystemNote>().singleOrNull { it.tag == "truncated" }
        assertNotNull("the oversized OpenCode row must degrade to a visible truncation note", note)
        assertEquals(AgentKind.OpenCode, note!!.agent)
        val messages = events.filterIsInstance<ConversationEvent.Message>().map { it.text }
        assertTrue("normal OpenCode messages survive; got $messages", messages.contains("hello"))
        assertTrue("normal OpenCode messages survive; got $messages", messages.contains("bye"))
    }

    @Test
    fun openCodeReadEventsWindowByteClampsAMultiMegabyteRowToAVisibleTruncationMarker() = runTest {
        // Class coverage: the windowed OpenCode cold-open (readEventsWindow) must
        // byte-clamp too.
        val hugePart = "E".repeat(4 * 1024 * 1024)
        val session = FakeSshSession(
            sqliteOutput = openCodeRows(
                listOf(
                    openCodeRow(1, "first"),
                    openCodeRow(2, hugePart),
                    openCodeRow(3, "third"),
                ),
            ),
        )

        val window = AgentConversationRepository().readEventsWindow(
            session = session,
            detection = openCodeDetection(),
            maxMessages = FIRST_PAINT_MESSAGE_BUDGET,
        )

        val command = session.execCommands.single { it.contains("sqlite3 -readonly") }
        assertTrue(
            "the windowed OpenCode read must byte-clamp each row server-side; got: $command",
            command.contains(LINE_TRUNCATION_SENTINEL) && command.contains("awk"),
        )
        val maxEventBytes = window.events.maxOfOrNull { estimatedEventBytes(it) } ?: 0L
        assertTrue(
            "no windowed OpenCode event may exceed the per-line byte cap; largest was $maxEventBytes bytes",
            maxEventBytes <= MAX_TRANSCRIPT_LINE_BYTES.toLong(),
        )
        assertNotNull(
            "the oversized OpenCode row must degrade to a visible truncation note",
            window.events.filterIsInstance<ConversationEvent.SystemNote>().singleOrNull { it.tag == "truncated" },
        )
        val messages = window.events.filterIsInstance<ConversationEvent.Message>().map { it.text }
        assertTrue("normal OpenCode messages survive; got $messages", messages.contains("first"))
        assertTrue("normal OpenCode messages survive; got $messages", messages.contains("third"))
    }

    @Test
    fun openCodeReadInitialEventsLeavesANormalTranscriptUnchangedByTheByteClamp() = runTest {
        // Counter-pin: a normal OpenCode transcript (every row well under the cap)
        // is byte-clamped harmlessly — same messages, NO spurious marker.
        val session = FakeSshSession(
            sqliteOutput = openCodeRows(
                listOf(
                    openCodeRow(1, "run the tests"),
                    openCodeRow(2, "all green"),
                ),
            ),
        )

        val events = AgentConversationRepository().readInitialEvents(session, openCodeDetection())

        assertTrue(
            "a normal OpenCode transcript must not produce any truncation marker",
            events.none { it is ConversationEvent.SystemNote && it.tag == "truncated" },
        )
        val messages = events.filterIsInstance<ConversationEvent.Message>().map { it.text }
        assertEquals(listOf("run the tests", "all green"), messages)
        assertTrue(
            session.execCommands.single { it.contains("sqlite3 -readonly") }.contains(LINE_TRUNCATION_SENTINEL),
        )
    }

    private fun estimatedEventBytes(event: ConversationEvent): Long = when (event) {
        is ConversationEvent.Message ->
            event.text.toByteArray(Charsets.UTF_8).size.toLong() +
                event.images.sumOf { imageBytes(it) }
        is ConversationEvent.ToolResult ->
            event.output.toByteArray(Charsets.UTF_8).size.toLong() +
                event.images.sumOf { imageBytes(it) }
        is ConversationEvent.ToolCall ->
            event.name.toByteArray(Charsets.UTF_8).size.toLong() +
                event.input.toByteArray(Charsets.UTF_8).size.toLong()
        is ConversationEvent.SystemNote ->
            event.content.toByteArray(Charsets.UTF_8).size.toLong()
    }

    private fun imageBytes(image: ConversationImage): Long =
        (image.base64Data?.length ?: 0).toLong() +
            (image.path?.length ?: 0).toLong() +
            (image.url?.length ?: 0).toLong()

    private fun openCodeDetection(): AgentDetection = AgentDetection(
        agent = AgentKind.OpenCode,
        sourcePath = "/home/alexey/.local/share/opencode/opencode.db#ses_123",
        sessionId = "ses_123",
        confidence = AgentDetection.Confidence.ProcessConfirmed,
    )

    private fun openCodeRows(rows: List<String>): String = rows.joinToString("\n")

    private fun openCodeRow(index: Int, text: String): String =
        """{"message_id":"m$index","message_data":"{\"role\":\"assistant\"}","message_time_created":$index,"message_time_updated":$index,"part_id":"p$index","part_data":"{\"type\":\"output_text\",\"text\":\"$text\"}","part_time_created":$index}"""

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
        private val recordedSourceGenerationOutput: String = "",
        private val recordedSourceOutput: String = "",
        private val procFdOutput: String = "",
        // Issue #828: when set, the single-round-trip recorded-open exec emits a
        // folded Claude window section (PATH=<path>, wc -l, sentinel, tail) after
        // the candidate enumeration — the shape the repository's window parse
        // expects. `foldedClaudePath` must equal the resolved source for the
        // prefetch to bind.
        private val foldedClaudePath: String = "",
        private val foldedClaudeWcOutput: String = "0",
        private val foldedClaudeTail: String = "",
        private val emulateFoldedClaudePathFromShell: Boolean = false,
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
                    append(recordedSourceGenerationOutput.trim())
                    append("\n@@PS_RECORDED_SOURCE_GENERATION@@\n")
                    append(recordedSourceOutput.trim())
                    append("\n@@PS_RECORDED_SOURCE@@\n")
                    append(detectionOutput)
                    append("\n@@PS_CLAUDE_WINDOW@@\n")
                    val emulatedFoldedPath = if (emulateFoldedClaudePathFromShell) {
                        emulatedFoldedClaudePath(command)
                    } else {
                        foldedClaudePath
                    }
                    if (emulatedFoldedPath.isNotBlank()) {
                        append("PATH=").append(emulatedFoldedPath).append("\n")
                        append(foldedClaudeWcOutput.trim()).append("\n")
                        append("@@PS_CLAUDE_WINDOW@@\n")
                        // Issue #1225: emulate the server-side per-line byte clamp
                        // on the folded prefetch tail so the fold reproduces what
                        // the real host would send (marker for oversized lines).
                        append(applyLineClamp(command, foldedClaudeTail))
                    }
                }
                command.contains("show-options -v") && command.contains("@ps_agent_kind") -> recordedKindOutput
                command.contains("@@PS_RECORDED_SOURCE_GENERATION@@") -> buildString {
                    append(recordedSourceGenerationOutput.trim())
                    append("\n@@PS_RECORDED_SOURCE_GENERATION@@\n")
                    append(recordedSourceOutput.trim())
                }
                command.contains("show-options -v") && command.contains("@ps_agent_source") -> recordedSourceOutput
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
                    "${wcOutput.trim()}\n@@PS_WINDOW@@\n${applyLineClamp(command, jsonlTailOutput)}"
                // Issue #817: the Codex windowed read folds wc -l + a sentinel +
                // the agent-log window into ONE round-trip so it carries the
                // raw-file line count (the follow cursor) without a separate
                // lineCount exec.
                command.contains("@@PS_CODEX_WINDOW@@") ->
                    "${wcOutput.trim()}\n@@PS_CODEX_WINDOW@@\n${applyAgentLogEnvelopeClamp(command, agentLogOutput)}"
                command.contains("wc -l < ") -> wcOutput
                command.contains("pocketshell agent-log") -> applyAgentLogEnvelopeClamp(command, agentLogOutput)
                command.trimStart().startsWith("tail -n") -> applyLineClamp(command, jsonlTailOutput)
                command.contains("sqlite3 -readonly") -> {
                    sqliteFailure?.let { throw it }
                    // Issue #1267: the OpenCode read now pipes the sqlite output
                    // through the same server-side per-line byte clamp; emulate it
                    // so an over-cap row degrades to the sentinel exactly as on the
                    // real host (identity for normal small rows).
                    applyLineClamp(command, sqliteOutputs.removeFirstOrNull() ?: sqliteOutput)
                }
                else -> ""
            }
            return ExecResult(stdout = stdout, stderr = "", exitCode = 0)
        }

        // Issue #1225: faithfully emulate the server-side per-line byte clamp
        // ([transcriptLineClampPipe]) so a byte-bound regression test is a
        // genuine red->green. If the repository's command does NOT pipe through
        // the awk clamp (base code), the raw text is returned unchanged and a
        // multi-MB line balloons the read exactly as on-device. If the command
        // DOES carry the clamp, each line whose UTF-8 byte length exceeds the
        // `-v m=<N>` cap is replaced by the sentinel + byte length, mirroring the
        // real host `LC_ALL=C awk` behaviour.
        private fun applyLineClamp(command: String, text: String): String {
            if (!command.contains("@@PS_LINE_TRUNCATED@@")) return text
            val cap = Regex("-v m=(\\d+)").find(command)?.groupValues?.get(1)?.toIntOrNull()
                ?: return text
            return text.split("\n").joinToString("\n") { line ->
                val bytes = line.toByteArray(Charsets.UTF_8).size
                if (bytes > cap) "@@PS_LINE_TRUNCATED@@$bytes" else line
            }
        }

        // Issue #1267: faithfully emulate the SERVER-SIDE `pocketshell agent-log
        // --max-line-bytes N` clamp (agent_log.py `_clamp_line_bytes`). The Codex
        // read goes through the tool, not the `awk` pipe, so the byte clamp lives
        // in the tool: each element of the envelope's `lines` array whose UTF-8
        // byte length exceeds N is replaced by `@@PS_LINE_TRUNCATED@@<bytes>`
        // before the envelope is serialised. When the command does NOT carry
        // `--max-line-bytes` (base code), the envelope is returned unchanged and a
        // multi-MB line balloons the read exactly as on-device — a genuine
        // red->green. A blank/unparseable envelope is passed through untouched.
        private fun applyAgentLogEnvelopeClamp(command: String, envelope: String): String {
            val cap = Regex("--max-line-bytes (\\d+)").find(command)
                ?.groupValues?.get(1)?.toIntOrNull() ?: return envelope
            val json = runCatching { JSONObject(envelope) }.getOrNull() ?: return envelope
            val lines = json.optJSONArray("lines") ?: return envelope
            val clamped = JSONArray()
            for (index in 0 until lines.length()) {
                val line = lines.optString(index)
                val bytes = line.toByteArray(Charsets.UTF_8).size
                clamped.put(if (bytes > cap) "@@PS_LINE_TRUNCATED@@$bytes" else line)
            }
            json.put("lines", clamped)
            return json.toString()
        }

        private fun emulatedFoldedClaudePath(command: String): String {
            val recordedSource = parsedRecordedSource()
            if (recordedSource.isNotBlank() && command.contains("ps_recorded_source_path")) {
                return recordedSource
            }
            return newestClaudeCandidatePath()
        }

        private fun parsedRecordedSource(): String {
            val raw = recordedSourceOutput.trim()
            if (raw.isBlank()) return ""
            val generation = recordedSourceGenerationOutput.trim()
            if (generation.isNotBlank()) {
                val prefix = "$generation\t"
                return raw.removePrefix(prefix)
                    .takeIf { it != raw }
                    ?.trim()
                    .orEmpty()
            }
            val tabIndex = raw.indexOf('\t')
            return if (tabIndex >= 0) {
                raw.substring(tabIndex + 1).trim()
            } else {
                raw
            }
        }

        private fun newestClaudeCandidatePath(): String =
            detectionOutput
                .lineSequence()
                .mapNotNull { line ->
                    val parts = line.trim().split("|", limit = 4)
                    if (parts.size == 4 && parts[0] == "claude") {
                        parts[1].toLongOrNull()?.let { modifiedAt -> modifiedAt to parts[3] }
                    } else {
                        null
                    }
                }
                .maxByOrNull { it.first }
                ?.second
                .orEmpty()

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
