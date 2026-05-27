package com.pocketshell.app.proof

import android.os.SystemClock
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.session.ConversationPane
import com.pocketshell.app.session.OPTIMISTIC_USER_MESSAGE_ID_PREFIX
import com.pocketshell.app.session.SESSION_CONVERSATION_COMPOSER_INPUT_TAG
import com.pocketshell.app.session.SESSION_CONVERSATION_COMPOSER_SEND_TAG
import com.pocketshell.app.session.SessionViewModel
import com.pocketshell.core.agents.AgentDetection
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.agents.ConversationRole
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.uikit.theme.PocketShellTheme
import com.pocketshell.uikit.theme.PocketShellThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Issue #160 (round 2): end-to-end coverage for the in-app composer
 * journey for all three supported agent engines.
 *
 * Per-engine test path, driven through the real Compose composer and
 * the real [SessionViewModel.sendToAgent] code path:
 *
 *  1. Connect to the deterministic Docker `agents` fixture over SSH.
 *  2. Attach the SessionViewModel's terminal-input bridge to a live
 *     SSH shell so the composer's bytes reach a real remote PTY.
 *  3. Synthesise the agent detection via the existing test seam so
 *     the conversation pane behaves as if the engine was running.
 *  4. Start the production `session.tail` route via
 *     [SessionViewModel.startAgentTailForTest] so future JSONL appends
 *     replay back through the conversation feed.
 *  5. Render [ConversationPane] bound to the same ViewModel state.
 *  6. Drive the composer text field via its [testTag], tap Send.
 *  7. Assert the optimistic placeholder appears immediately (UI
 *     responsiveness — strategy B from the issue brief).
 *  8. Assert the prompt's bytes reached the remote PTY via a parallel
 *     SSH `exec` log redirect (the send-keys equivalent for the raw
 *     SSH session — proves the composer is wired through
 *     `sendText(payload, withEnter = true)`).
 *  9. Append a deterministic JSONL row for the engine to its seeded
 *     log path — this stands in for the agent shim's response, since
 *     the fixture shims under `tests/docker/agent-bin/` are
 *     non-interactive and only exist to make detection happy.
 * 10. Wait for the tail loop to deliver the parsed
 *     `Message(role=User)` event back into the ViewModel.
 * 11. Assert the conversation pane now contains exactly one user
 *     message with the prompt text — i.e. the optimistic placeholder
 *     has been collapsed into the real event by
 *     [com.pocketshell.app.session.reconcileAgentEvents] (the round-2
 *     dedup contract). Inserting a duplicate user message would be a
 *     regression of the original issue.
 *
 * Dedup strategy chosen (per the brief's option B): optimistic events
 * are tagged at insert time with the [OPTIMISTIC_USER_MESSAGE_ID_PREFIX]
 * id prefix and removed on tail arrival of a non-optimistic
 * `Message(role=User)` with the same text. Pinned by unit tests in
 * `SessionViewModelTest`.
 */
@RunWith(AndroidJUnit4::class)
class ConversationInteractE2eTest {

    @get:Rule
    val compose = createComposeRule()

    private val timings = mutableListOf<String>()

    @Test
    fun composerSendDeliversUserPromptToConversationPaneViaClaudeTail() =
        runComposerJourneyTest(
            kind = AgentKind.ClaudeCode,
            remoteLogPath = CLAUDE_PATH,
            engineLabel = "claude",
            jsonlForUserPrompt = { marker, prompt ->
                """{"type":"user","uuid":"e2e-claude-$marker","timestamp":"2026-05-27T10:00:00Z","message":{"role":"user","content":${prompt.jsonString()}}}"""
            },
        )

    @Test
    fun composerSendDeliversUserPromptToConversationPaneViaCodexTail() =
        runComposerJourneyTest(
            kind = AgentKind.Codex,
            remoteLogPath = CODEX_PATH,
            engineLabel = "codex",
            jsonlForUserPrompt = { marker, prompt ->
                """{"id":"e2e-codex-$marker","timestamp":"2026-05-27T10:00:00Z","item":{"type":"user_message","message":${prompt.jsonString()}}}"""
            },
        )

    @Test
    fun composerSendDeliversUserPromptToConversationPaneViaOpencodeTail() =
        runComposerJourneyTest(
            kind = AgentKind.OpenCode,
            remoteLogPath = OPENCODE_PATH,
            engineLabel = "opencode",
            jsonlForUserPrompt = { marker, prompt ->
                """{"id":"e2e-opencode-$marker","role":"user","content":${prompt.jsonString()},"createdAtMillis":${System.currentTimeMillis()}}"""
            },
        )

    private fun runComposerJourneyTest(
        kind: AgentKind,
        remoteLogPath: String,
        engineLabel: String,
        jsonlForUserPrompt: (marker: String, prompt: String) -> String,
    ) = runBlocking<Unit> {
        val journeyStart = SystemClock.elapsedRealtime()
        val key = readFixtureKey()
        waitForSshFixtureReady(SshKey.Pem(key))

        // The composer-side prompt has a unique marker so any subsequent
        // parsing (visible terminal text, JSONL search) can be sure
        // it found *this* run's prompt and not noise from a sibling
        // test or a stale fixture.
        val marker = "${engineLabel}-${System.currentTimeMillis()}"
        val prompt = "type-back-e2e-$marker"

        val tailSessionResult = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        )
        assertTrue(
            "expected tail-SSH connection to Docker agents target for $engineLabel, got ${tailSessionResult.exceptionOrNull()}",
            tailSessionResult.isSuccess,
        )
        val tailSession = tailSessionResult.getOrThrow()

        // A second shell channel hosts the composer's send-keys
        // equivalent. The ViewModel only owns one PTY at a time
        // (production: `connect()` opens one shell); we mirror that
        // shape and let `tailSession` stay an `exec`-only channel.
        val writerHandle = openShell(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
        )

        // Capture the bytes that reach the writer shell's PTY so we
        // can prove the composer's payload went through `sendText
        // → terminalState.writeInput → SSH stdin`. The fixture's
        // remote `sh` will not respond meaningfully to "type-back-e2e-…"
        // (it's not a command), but the bytes still hit the PTY and
        // can be inspected as the channel's echo.
        val receivedFromRemote = StringBuilder()

        val viewModel = SessionViewModel(
            applicationContext = InstrumentationRegistry.getInstrumentation().targetContext,
        )

        val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val outputCollectorJob = producerScope.launch {
            viewModel.terminalState.output.collect { bytes ->
                synchronized(receivedFromRemote) {
                    receivedFromRemote.append(bytes.toString(Charsets.UTF_8))
                }
            }
        }
        val producerJob = viewModel.terminalState.attachExternalProducer(
            scope = producerScope,
            stdout = createStdoutFlow(writerHandle.shell),
            remoteStdin = writerHandle.shell.outputStream,
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

            // Snapshot the seed line count and start the tail from
            // that boundary so the JSONL append below is the first
            // event the tail loop sees for this run.
            val seedLineCount = AgentTestRepo.lineCount(tailSession, detection)
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

            // Drive the composer through the real UI.
            compose.setContent {
                PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                    val state by viewModel.agentConversation.collectAsState()
                    ConversationPane(
                        events = state.events,
                        onSendToAgent = viewModel::sendToAgent,
                    )
                }
            }

            // Composer must be mounted and idle before we type into
            // it — the layout finishes once the LazyColumn for the
            // (empty) feed and the composer row have measured.
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithTag(SESSION_CONVERSATION_COMPOSER_INPUT_TAG)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithTag(SESSION_CONVERSATION_COMPOSER_INPUT_TAG)
                .assertExists()

            val typeStart = SystemClock.elapsedRealtime()
            compose.onNodeWithTag(SESSION_CONVERSATION_COMPOSER_INPUT_TAG)
                .performTextInput(prompt)
            compose.onNodeWithTag(SESSION_CONVERSATION_COMPOSER_SEND_TAG)
                .performClick()

            // Optimistic message must appear immediately so the user
            // sees their own send before the round-trip completes.
            val optimisticDeadline = SystemClock.elapsedRealtime() + 5_000
            var optimisticVisible = false
            while (SystemClock.elapsedRealtime() < optimisticDeadline) {
                val events = viewModel.agentConversation.value.events
                val match = events.firstOrNull { ev ->
                    ev is ConversationEvent.Message &&
                        ev.role == ConversationRole.User &&
                        ev.text == prompt &&
                        ev.id.startsWith(OPTIMISTIC_USER_MESSAGE_ID_PREFIX)
                }
                if (match != null) {
                    optimisticVisible = true
                    break
                }
                SystemClock.sleep(50)
            }
            assertTrue(
                "expected optimistic Message(role=User) for $engineLabel after composer Send; events=${viewModel.agentConversation.value.events}",
                optimisticVisible,
            )
            recordTiming("${engineLabel}_composer_to_optimistic_ms", SystemClock.elapsedRealtime() - typeStart)

            // The send-keys equivalent for the raw SSH session: the
            // composer payload must reach the writer shell's PTY.
            // The shell echoes typed bytes by default, so the prompt
            // text appears in [receivedFromRemote] once it lands.
            val sshDeadline = SystemClock.elapsedRealtime() + TAIL_DEADLINE_MS
            var sshSawPrompt = false
            while (SystemClock.elapsedRealtime() < sshDeadline) {
                val snapshot = synchronized(receivedFromRemote) { receivedFromRemote.toString() }
                if (prompt in snapshot) {
                    sshSawPrompt = true
                    break
                }
                SystemClock.sleep(100)
            }
            assertTrue(
                "expected $engineLabel composer payload `$prompt` to reach the remote PTY; got transcript snippet=`${
                    synchronized(receivedFromRemote) {
                        receivedFromRemote.toString().takeLast(400)
                    }
                }`",
                sshSawPrompt,
            )
            recordTiming("${engineLabel}_composer_to_remote_pty_ms", SystemClock.elapsedRealtime() - typeStart)

            // Simulate the agent's JSONL response. In production the
            // running CLI would append a `user_message`-shaped row
            // after it parses the prompt; the deterministic shim is
            // not interactive (see tests/docker/agent-bin/*), so the
            // test drives the append directly via SSH `exec` — same
            // mechanism the shim itself would use.
            val jsonlLine = jsonlForUserPrompt(marker, prompt)
            val appendCommand = "printf '%s\\n' " +
                shellQuote(jsonlLine) +
                " >> " +
                shellQuote(remoteLogPath)
            val appendStart = SystemClock.elapsedRealtime()
            val appendResult = tailSession.exec(appendCommand)
            assertEquals(
                "expected JSONL append for $engineLabel to succeed: stderr=${appendResult.stderr}",
                0,
                appendResult.exitCode,
            )

            // Wait for the tail to deliver the parsed event and the
            // dedup pass to collapse the optimistic placeholder into
            // the real event. The success condition is "exactly one
            // user-role message with the prompt text, and its id is
            // NOT the optimistic prefix" — i.e. the optimistic and
            // the real entry have merged into one row keyed by the
            // agent's id.
            val dedupDeadline = SystemClock.elapsedRealtime() + TAIL_DEADLINE_MS
            var dedupSeen = false
            var lastEvents: List<ConversationEvent> = emptyList()
            while (SystemClock.elapsedRealtime() < dedupDeadline) {
                val events = viewModel.agentConversation.value.events
                lastEvents = events
                val userMessages = events.filterIsInstance<ConversationEvent.Message>()
                    .filter { it.role == ConversationRole.User && it.text == prompt }
                val realUserMessages = userMessages.filter {
                    !it.id.startsWith(OPTIMISTIC_USER_MESSAGE_ID_PREFIX)
                }
                if (userMessages.size == 1 && realUserMessages.size == 1) {
                    dedupSeen = true
                    break
                }
                SystemClock.sleep(100)
            }
            assertTrue(
                "expected the optimistic+real user message for $engineLabel to dedupe to one " +
                    "Message(role=User) with the prompt and a non-optimistic id; lastEvents=$lastEvents",
                dedupSeen,
            )
            recordTiming("${engineLabel}_jsonl_append_to_dedup_ms", SystemClock.elapsedRealtime() - appendStart)

            tailJob?.cancel()
            withTimeoutOrNull(5_000) { tailJob?.join() }

            recordTiming("${engineLabel}_journey_total_ms", SystemClock.elapsedRealtime() - journeyStart)
        } finally {
            producerJob.cancel()
            outputCollectorJob.cancel()
            producerScope.cancel()
            viewModel.terminalState.detachExternalProducer()
            runCatching { writerHandle.shell.close() }
            runCatching { writerHandle.sessionChannel.close() }
            runCatching { writerHandle.client.disconnect() }
            // Intentionally do NOT call `tailSession.close()`: sshj
            // raises a `[BY_APPLICATION] Disconnected` transport
            // exception when a session with a still-attached tail
            // channel is torn down by the transport disconnect, and
            // even when wrapped in `runCatching` the exception
            // crashes the instrumentation process via an internal
            // sshj thread. The next test re-opens its own session,
            // so leaving this connection for the OS to reap is the
            // safest option. Same pattern as the previous round of
            // this test, preserved for the same reason.
        }

        writeTimings(engineLabel)
    }

    private fun recordTiming(label: String, value: Long) {
        val line = "CONVERSATION_INTERACT_E2E_TIMING $label=$value"
        timings += line
        println(line)
    }

    private fun writeTimings(engineLabel: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = instrumentation.targetContext.externalMediaDirs
            .firstOrNull { it != null }
            ?: instrumentation.targetContext.getExternalFilesDir(null)
        val dir = File(mediaRoot, "additional_test_output/conversation-interact-e2e")
        if (!dir.exists() && !dir.mkdirs()) return
        val file = File(dir, "$engineLabel-timings.txt")
        file.writeText(timings.joinToString(separator = "\n", postfix = "\n"))
        println("CONVERSATION_INTERACT_E2E_TIMINGS ${file.absolutePath}")
    }

    private fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }

    /**
     * Minimal JSON-string encoder for the prompt text we embed inside
     * the appended JSONL row. Covers the printable-ASCII payload the
     * tests actually send (`type-back-e2e-<timestamp>`) plus the four
     * meta-characters JSON requires to be escaped. A full JSON
     * library would be overkill — this matches the engines'
     * `Message` parsing contract for "ordinary text content".
     */
    private fun String.jsonString(): String = buildString {
        append('"')
        for (ch in this@jsonString) {
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

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private companion object {
        // The deterministic agent fixture pre-seeds these JSONL paths
        // for the testuser via `tests/docker/agent-entrypoint.sh`.
        const val CLAUDE_PATH: String =
            "/home/testuser/.claude/projects/-workspace-pocketshell/pocketshell-claude.jsonl"
        const val CODEX_PATH: String =
            "/home/testuser/.codex/sessions/2026/05/22/pocketshell-codex.jsonl"
        const val OPENCODE_PATH: String =
            "/home/testuser/.local/share/opencode/pocketshell-rows.jsonl"

        // CI-aware deadline — the composer → SSH stdin echo and the
        // JSONL → tail-callback round-trips both finish well under
        // 10 s on local Linux dev, but the GitHub Actions emulator
        // is slower under sibling-test contention.
        val TAIL_DEADLINE_MS: Long = TerminalTestTimeouts.terminalVisibilityTimeoutMs()
    }
}

/**
 * Issue #160 round 2: the connected test owns its own
 * [com.pocketshell.app.session.AgentConversationRepository] instance
 * to query the remote JSONL line count from the tail's SSH session.
 * Boxed in a private object so the test file does not need to import
 * the internal type directly into its top-level namespace.
 */
private object AgentTestRepo {
    private val repo = com.pocketshell.app.session.AgentConversationRepository()

    suspend fun lineCount(
        session: com.pocketshell.core.ssh.SshSession,
        detection: AgentDetection,
    ): Long = repo.lineCount(session, detection)
}
