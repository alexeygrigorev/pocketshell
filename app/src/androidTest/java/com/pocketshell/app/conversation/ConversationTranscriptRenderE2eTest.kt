package com.pocketshell.app.conversation

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.app.session.AgentConversationRepository
import com.pocketshell.app.session.ConversationPane
import com.pocketshell.app.session.SESSION_CONVERSATION_PANE_TAG
import com.pocketshell.app.session.SESSION_CONVERSATION_TOOL_ROW_TAG_PREFIX
import com.pocketshell.app.session.SessionViewModel
import com.pocketshell.core.agents.AgentDetection
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.agents.ConversationRole
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #670 — Conversation-tab content E2E. From the #657 journey-suite
 * audit (candidate #2).
 *
 * Agent *detection* is well-covered by
 * [com.pocketshell.app.proof.AgentDetectionAcrossEnginesE2eTest] and
 * [com.pocketshell.app.proof.PerWindowAgentDetectionE2eTest], which prove
 * the Conversation tab becomes *reachable*. But nothing guarded the
 * Conversation *transcript render itself*: a regression that blanked or
 * garbled the rendered transcript (e.g. a parser drift, a row-filter bug,
 * or a tool-card rendering break) would not be caught, because every
 * existing guard stops at "a detection came back / the tab exists".
 *
 * This test closes that gap. For Claude Code and Codex it:
 *
 *  1. Connects to the deterministic Docker `agents` fixture on
 *     `10.0.2.2:2222` over SSH.
 *  2. Synthesises the agent detection via the existing
 *     [SessionViewModel.startAgentConversationForTest] seam (so the pane
 *     behaves exactly as it does after a real detection) and starts the
 *     **production** `session.tail` follow loop via
 *     [SessionViewModel.startAgentTailForTest] from the current seed
 *     boundary, so subsequent JSONL appends replay back through the real
 *     parser → conversation-feed path.
 *  3. Appends, over SSH, three real-shaped JSONL rows the engine's own CLI
 *     would emit: an assistant prose message, an assistant `tool_use` /
 *     `function_call`, and a `tool_result` / `function_call_output`. Each
 *     carries a unique per-run marker so the assertions can be sure they
 *     matched *this* run's content and not stale fixture noise.
 *  4. Renders the real [ConversationPane] bound to the same ViewModel.
 *  5. **Asserts the rendered transcript TEXT** — not merely that the tab
 *     exists:
 *      - the assistant prose marker is a visible Compose text node,
 *      - the tool-call row renders (its `session:conversation:tool:<id>`
 *        testTag node is displayed) and shows the tool name + command
 *        preview,
 *      - the tool result content renders (the card auto-expands while the
 *        call is paired, and the test taps to reveal the `output` section,
 *        which contains the result marker).
 *  6. Captures an authoritative viewport screenshot of the Conversation
 *     pane plus a `visible-transcript.txt` text artifact of the asserted
 *     markers from the same run.
 *
 * Un-gated on CI (no `assumeFalse(isRunningOnCi())`) so the #659 nightly
 * extensive suite runs it in its journey/E2E phase — the class is not on
 * the `nightly-extensive-suite.sh` `JOURNEY_EXCLUDED_CLASSES` denylist.
 */
@RunWith(AndroidJUnit4::class)
class ConversationTranscriptRenderE2eTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun claudeTranscriptRendersMessageAndToolRowsInConversationPane() = runRenderTest(
        kind = AgentKind.ClaudeCode,
        remoteLogPath = CLAUDE_PATH,
        engineLabel = "claude",
        jsonlRows = ::claudeRows,
    )

    @Test
    fun codexTranscriptRendersMessageAndToolRowsInConversationPane() = runRenderTest(
        kind = AgentKind.Codex,
        remoteLogPath = CODEX_PATH,
        engineLabel = "codex",
        jsonlRows = ::codexRows,
    )

    private fun runRenderTest(
        kind: AgentKind,
        remoteLogPath: String,
        engineLabel: String,
        jsonlRows: (TranscriptMarkers) -> List<String>,
    ) = runBlocking<Unit> {
        val key = readFixtureKey()
        waitForSshFixtureReady(SshKey.Pem(key))

        val markers = TranscriptMarkers(engineLabel)

        val tailSession = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).also { result ->
            assertTrue(
                "expected tail-SSH connection to Docker agents target for $engineLabel, got ${result.exceptionOrNull()}",
                result.isSuccess,
            )
        }.getOrThrow()

        val viewModel = SessionViewModel(
            applicationContext = InstrumentationRegistry.getInstrumentation().targetContext,
        )

        try {
            val detection = AgentDetection(
                agent = kind,
                sourcePath = remoteLogPath,
                sessionId = remoteLogPath.substringAfterLast('/').substringBeforeLast('.'),
                confidence = AgentDetection.Confidence.RecentFile,
            )
            viewModel.startAgentConversationForTest(
                detection = detection,
                initialEvents = emptyList(),
            )

            // Start the production tail from the current seed boundary so the
            // rows we append below are the only events this run replays.
            val seedLineCount = AgentTranscriptRepo.lineCount(tailSession, detection)
            assertTrue(
                "expected seeded JSONL at $remoteLogPath to have at least one line for $kind, got $seedLineCount",
                seedLineCount > 0,
            )
            val tailJob = viewModel.startAgentTailForTest(
                session = tailSession,
                detection = detection,
                fromLineExclusive = seedLineCount,
            )
            assertTrue("expected tail job for $kind", tailJob != null)

            // Render the real Conversation pane bound to the same ViewModel.
            compose.setContent {
                PocketShellTheme {
                    val state by viewModel.agentConversation.collectAsState()
                    ConversationPane(
                        events = state.events,
                        onSendToAgent = { text -> viewModel.sendToAgentResult(text) },
                        query = state.searchQuery,
                        onQueryChange = viewModel::setAgentSearchQuery,
                        agentName = detection.agent.displayName,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(PocketShellColors.Background)
                            .testTag(SESSION_CONVERSATION_PANE_TAG),
                    )
                }
            }
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithTag(SESSION_CONVERSATION_PANE_TAG)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithTag(SESSION_CONVERSATION_PANE_TAG).assertIsDisplayed()

            // Append the engine-shaped transcript rows over SSH — the same
            // mechanism the agent CLI uses (the deterministic shims are not
            // interactive, see tests/docker/agent-bin/*).
            for (row in jsonlRows(markers)) {
                val appendResult = tailSession.exec(
                    "printf '%s\\n' " + shellQuote(row) + " >> " + shellQuote(remoteLogPath),
                )
                assertEquals(
                    "expected transcript JSONL append for $engineLabel to succeed: stderr=${appendResult.stderr}",
                    0,
                    appendResult.exitCode,
                )
            }

            // 1. Assistant prose message text must reach the ViewModel feed
            //    AND render as a visible Compose node.
            waitForConversationEvent(viewModel, engineLabel, "assistant prose") { events ->
                events.any {
                    it is ConversationEvent.Message &&
                        it.role == ConversationRole.Assistant &&
                        it.text.contains(markers.assistantText)
                }
            }
            compose.waitUntil(timeoutMillis = RENDER_DEADLINE_MS) {
                compose.onAllNodesWithText(markers.assistantText, substring = true, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }

            // 2. Tool call row must reach the feed and render: its row testTag
            //    is present and the tool name + command preview are visible.
            val toolCallId = waitForToolCallId(viewModel, engineLabel)
            compose.waitUntil(timeoutMillis = RENDER_DEADLINE_MS) {
                compose.onAllNodesWithTag(SESSION_CONVERSATION_TOOL_ROW_TAG_PREFIX + toolCallId)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithTag(SESSION_CONVERSATION_TOOL_ROW_TAG_PREFIX + toolCallId)
                .assertIsDisplayed()
            compose.waitUntil(timeoutMillis = RENDER_DEADLINE_MS) {
                compose.onAllNodesWithText(TOOL_NAME, substring = true, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            // The collapsed card preview is the Bash command one-liner, which
            // carries the call marker.
            compose.waitUntil(timeoutMillis = RENDER_DEADLINE_MS) {
                compose.onAllNodesWithText(markers.toolCallMarker, substring = true, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }

            // 3. Tool result content must render. The paired card may collapse
            //    once the result arrives; tap the row to reveal the output
            //    section and assert the result marker is a visible node.
            waitForConversationEvent(viewModel, engineLabel, "tool result") { events ->
                events.any {
                    it is ConversationEvent.ToolResult && it.output.contains(markers.toolResultMarker)
                }
            }
            compose.onNodeWithTag(SESSION_CONVERSATION_TOOL_ROW_TAG_PREFIX + toolCallId).performClick()
            compose.waitForIdle()
            compose.waitUntil(timeoutMillis = RENDER_DEADLINE_MS) {
                compose.onAllNodesWithText(markers.toolResultMarker, substring = true, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onAllNodesWithText(markers.toolResultMarker, substring = true, useUnmergedTree = true)
                .assertCountAtLeast(1)

            // Authoritative artifacts: visible-transcript text + viewport PNG.
            writeVisibleTranscript(engineLabel, markers, toolCallId)
            captureViewport("issue-670-conversation-transcript-$engineLabel-viewport.png", engineLabel)

            tailJob?.cancel()
        } finally {
            // Intentionally do NOT call tailSession.close(): sshj raises a
            // BY_APPLICATION transport exception when a session with a still
            // attached tail channel is torn down, which can crash the
            // instrumentation process from an internal sshj thread. The next
            // test re-opens its own session; leave this one for the OS to
            // reap. Same rationale as ConversationInteractE2eTest.
        }
    }

    // --------------------------------------------------------------- Rows

    /**
     * Real Claude Code JSONL rows. Claude wraps tool calls in an assistant
     * message whose `content` is an array of typed blocks
     * (`text` / `tool_use` / `tool_result`) — see
     * [com.pocketshell.core.agents.ClaudeCodeParser].
     */
    private fun claudeRows(m: TranscriptMarkers): List<String> = listOf(
        """{"type":"assistant","uuid":"e2e-670-claude-msg-${m.run}","timestamp":"2026-06-09T10:00:00Z","message":{"role":"assistant","content":[{"type":"text","text":${m.assistantText.json()}}]}}""",
        """{"type":"assistant","uuid":"e2e-670-claude-call-${m.run}","timestamp":"2026-06-09T10:00:01Z","message":{"role":"assistant","content":[{"type":"tool_use","id":"${m.toolUseId}","name":"$TOOL_NAME","input":{"command":${m.toolCallCommand.json()}}}]}}""",
        """{"type":"user","uuid":"e2e-670-claude-result-${m.run}","timestamp":"2026-06-09T10:00:02Z","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"${m.toolUseId}","content":${m.toolResultMarker.json()}}]}}""",
    )

    /**
     * Real Codex JSONL rows. Codex emits flat `event_msg` payloads with a
     * `function_call` / `function_call_output` type — see
     * [com.pocketshell.core.agents.CodexParser].
     */
    private fun codexRows(m: TranscriptMarkers): List<String> = listOf(
        """{"id":"e2e-670-codex-msg-${m.run}","type":"event_msg","timestamp":"2026-06-09T10:00:00Z","payload":{"type":"agent_message","message":${m.assistantText.json()}}}""",
        """{"id":"e2e-670-codex-call-${m.run}","type":"event_msg","timestamp":"2026-06-09T10:00:01Z","payload":{"type":"function_call","call_id":"${m.toolUseId}","name":"$TOOL_NAME","arguments":${("{\"command\":\"" + m.toolCallCommand + "\"}").json()}}}""",
        """{"id":"e2e-670-codex-result-${m.run}","type":"event_msg","timestamp":"2026-06-09T10:00:02Z","payload":{"type":"function_call_output","call_id":"${m.toolUseId}","output":${m.toolResultMarker.json()}}}""",
    )

    // ------------------------------------------------------------- Helpers

    private fun waitForConversationEvent(
        viewModel: SessionViewModel,
        engineLabel: String,
        label: String,
        predicate: (List<ConversationEvent>) -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + RENDER_DEADLINE_MS
        var last: List<ConversationEvent> = emptyList()
        while (SystemClock.elapsedRealtime() < deadline) {
            last = viewModel.agentConversation.value.events
            if (predicate(last)) return
            SystemClock.sleep(100)
        }
        error("expected $engineLabel $label to reach the conversation feed; lastEvents=$last")
    }

    private fun waitForToolCallId(viewModel: SessionViewModel, engineLabel: String): String {
        val deadline = SystemClock.elapsedRealtime() + RENDER_DEADLINE_MS
        var last: List<ConversationEvent> = emptyList()
        while (SystemClock.elapsedRealtime() < deadline) {
            last = viewModel.agentConversation.value.events
            val call = last.filterIsInstance<ConversationEvent.ToolCall>()
                .firstOrNull { it.name == TOOL_NAME }
            if (call != null) return call.id
            SystemClock.sleep(100)
        }
        error("expected $engineLabel ToolCall(name=$TOOL_NAME) to reach the feed; lastEvents=$last")
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountAtLeast(min: Int) {
        val count = fetchSemanticsNodes().size
        check(count >= min) { "expected at least $min matching nodes; found $count" }
    }

    private fun writeVisibleTranscript(
        engineLabel: String,
        markers: TranscriptMarkers,
        toolCallId: String,
    ) {
        val dir = artifactDir(engineLabel)
        val file = File(dir, "$engineLabel-visible-transcript.txt")
        val body = buildString {
            appendLine("CONVERSATION_TRANSCRIPT_RENDER engine=$engineLabel")
            appendLine("assistant_message=${markers.assistantText}")
            appendLine("tool_call_row_tag=${SESSION_CONVERSATION_TOOL_ROW_TAG_PREFIX}$toolCallId")
            appendLine("tool_name=$TOOL_NAME")
            appendLine("tool_call_marker=${markers.toolCallMarker}")
            appendLine("tool_result_marker=${markers.toolResultMarker}")
        }
        file.writeText(body)
        println("CONVERSATION_TRANSCRIPT_RENDER_VISIBLE ${file.absolutePath}")
    }

    private fun captureViewport(name: String, engineLabel: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(300)
        val bitmap: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        try {
            val file = File(artifactDir(engineLabel), name)
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "Could not write conversation transcript viewport: ${file.absolutePath}"
                }
            }
            println("CONVERSATION_TRANSCRIPT_RENDER_VIEWPORT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }

    private fun artifactDir(engineLabel: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/conversation-transcript-render-e2e/$engineLabel")
        check(dir.exists() || dir.mkdirs()) {
            "Could not create conversation-transcript artifact dir: ${dir.absolutePath}"
        }
        return dir
    }

    private fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    /** Minimal JSON-string encoder for the printable markers we embed. */
    private fun String.json(): String = buildString {
        append('"')
        for (ch in this@json) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(ch)
            }
        }
        append('"')
    }

    /**
     * Per-run unique transcript markers. Every assertion checks for `run`
     * embedded in the text so a stale fixture row or a sibling test cannot
     * masquerade as this run's content.
     */
    private class TranscriptMarkers(engineLabel: String) {
        val run: String = "${engineLabel}-${System.currentTimeMillis()}"
        val assistantText: String = "transcript-render-assistant-$run"
        val toolCallMarker: String = "render670call-$run"
        val toolResultMarker: String = "transcript-render-result-$run"
        val toolUseId: String = "e2e-670-tooluse-$run"

        // The Bash command preview (ToolCallSummary) is the collapsed-row
        // text the user sees; embed the call marker so it is assertable.
        val toolCallCommand: String = "echo $toolCallMarker"
    }

    private companion object {
        // Deterministic agent fixture JSONL paths (tests/docker/agent-entrypoint.sh).
        const val CLAUDE_PATH: String =
            "/home/testuser/.claude/projects/-workspace-pocketshell/pocketshell-claude.jsonl"
        const val CODEX_PATH: String =
            "/home/testuser/.codex/sessions/2026/05/22/pocketshell-codex.jsonl"

        const val TOOL_NAME: String = "Bash"

        // CI-aware deadline: the JSONL → tail → parse → render round-trip is
        // sub-second on local dev but slower on the shared CI emulator.
        val RENDER_DEADLINE_MS: Long =
            com.pocketshell.app.proof.TerminalTestTimeouts.terminalVisibilityTimeoutMs()
    }
}

/**
 * Connected-test access to the internal repository's remote JSONL line-count,
 * mirroring the boxed helper in `ConversationInteractE2eTest`.
 */
private object AgentTranscriptRepo {
    private val repo = AgentConversationRepository()

    suspend fun lineCount(session: SshSession, detection: AgentDetection): Long =
        repo.lineCount(session, detection)
}
