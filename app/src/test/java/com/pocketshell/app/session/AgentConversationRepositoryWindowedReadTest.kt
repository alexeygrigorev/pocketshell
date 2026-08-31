package com.pocketshell.app.session

import com.pocketshell.core.agents.AgentDetection
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.agents.ClaudeCodeParser
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.agents.ConversationImage
import com.pocketshell.core.agents.ConversationRole
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #793/#817/#1225/#1267: the windowed-read (`readEventsWindow`) and
 * server-side per-line byte-clamp slice of [AgentConversationRepository]'s
 * unit coverage, split out of [AgentConversationRepositoryTest] into this
 * sibling file (scripts/check-file-size-hygiene.sh's oversized-file ratchet
 * — the original single file crossed the 128 KiB threshold). Shares the
 * [FakeSshSession] fixture with the parent suite; no behavioural change.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentConversationRepositoryWindowedReadTest {
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

}
