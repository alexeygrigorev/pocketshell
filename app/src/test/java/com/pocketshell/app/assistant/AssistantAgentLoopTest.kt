package com.pocketshell.app.assistant

import com.pocketshell.core.assistant.AssistantLlmClient
import com.pocketshell.core.assistant.AssistantLlmException
import com.pocketshell.core.assistant.LlmMessage
import com.pocketshell.core.assistant.LlmResponse
import com.pocketshell.core.assistant.LlmToolCall
import com.pocketshell.core.assistant.StopReason
import com.pocketshell.core.assistant.ToolChoice
import com.pocketshell.core.assistant.ToolSpec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the provider-agnostic [AssistantAgentLoop] (issue #266),
 * driven by a fake [AssistantLlmClient] that scripts tool calls — including a
 * reject → correct → confirm sequence and the safety gate.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AssistantAgentLoopTest {

    /**
     * Fake client that replays a queue of scripted [LlmResponse]s, one per
     * `complete` call, and records the message list it was handed each turn.
     */
    private class ScriptedClient(
        private val script: ArrayDeque<LlmResponse>,
    ) : AssistantLlmClient {
        val turns = mutableListOf<List<LlmMessage>>()

        override suspend fun complete(
            messages: List<LlmMessage>,
            tools: List<ToolSpec>,
            toolChoice: ToolChoice?,
        ): Result<LlmResponse> {
            turns += messages.toList()
            val next = script.removeFirstOrNull()
                ?: return Result.success(LlmResponse("(out of script)", stopReason = StopReason.EndTurn))
            return Result.success(next)
        }
    }

    /** Records every action invocation; returns canned strings/results. */
    private class RecordingActions : AssistantActions {
        val calls = mutableListOf<String>()
        var ranCommands = mutableListOf<String>()

        override suspend fun getContext(): String {
            calls += "get_context"
            return "screen: tmux\nactive_host: dev\ncwd: /home/dev/proj"
        }
        override suspend fun listHosts(): String { calls += "list_hosts"; return "dev" }
        override suspend fun listFolders(host: String): String { calls += "list_folders($host)"; return "/home/dev/proj" }
        var folderResolution: FolderResolutionResult =
            FolderResolutionResult.Resolved(
                FolderResolution.Confident(FolderCandidate("/home/dev/proj", "proj")),
            )
        override suspend fun resolveFolder(host: String, query: String): FolderResolutionResult {
            calls += "resolve_folder($host,$query)"; return folderResolution
        }
        override suspend fun listSessions(host: String): String { calls += "list_sessions($host)"; return "main" }
        override suspend fun listDirectory(path: String): String { calls += "list_directory($path)"; return "a\nb" }
        override suspend fun readFile(path: String): String { calls += "read_file($path)"; return "contents" }
        override suspend fun listRepos(): String { calls += "list_repos"; return "owner/repo" }
        override suspend fun openFolder(host: String, path: String): String {
            calls += "open_folder($host,$path)"; return "opened"
        }
        override suspend fun openSession(sessionName: String): String { calls += "open_session($sessionName)"; return "opening" }
        override suspend fun openScreen(destination: String): String { calls += "open_screen($destination)"; return "opened" }
        override suspend fun startSession(host: String, cwd: String, agent: String): ActionResult {
            calls += "start_session($host,$cwd,$agent)"; return ActionResult.ok("started")
        }
        override suspend fun sendPromptToSession(sessionName: String, prompt: String): ActionResult {
            calls += "send_prompt_to_session($sessionName,$prompt)"; return ActionResult.ok("sent")
        }
        override suspend fun createProject(host: String, parentPath: String, folderName: String): ActionResult {
            calls += "create_project($host,$parentPath,$folderName)"; return ActionResult.ok("created")
        }
        override suspend fun runCommand(command: String): ActionResult {
            calls += "run_command($command)"; ranCommands += command; return ActionResult.ok("ran")
        }
        override suspend fun createFile(path: String, content: String): ActionResult {
            calls += "create_file($path)"; return ActionResult.ok("created")
        }
        override suspend fun cloneRepo(fullName: String, folder: String?): ActionResult {
            calls += "clone_repo($fullName)"; return ActionResult.ok("cloned")
        }
    }

    private fun toolCall(name: String, args: String, id: String = "c1") =
        LlmResponse(text = null, toolCalls = listOf(LlmToolCall(id, name, args)), stopReason = StopReason.ToolUse)

    private fun toolCalls(vararg calls: LlmToolCall) =
        LlmResponse(text = null, toolCalls = calls.toList(), stopReason = StopReason.ToolUse)

    private fun answer(text: String) = LlmResponse(text = text, stopReason = StopReason.EndTurn)

    @Test
    fun multiTurn_inspect_then_answer() = runTest {
        val client = ScriptedClient(
            ArrayDeque(
                listOf(
                    toolCall(AssistantTools.GET_CONTEXT, "{}"),
                    answer("You're in /home/dev/proj on dev."),
                ),
            ),
        )
        val actions = RecordingActions()
        val loop = AssistantAgentLoop(client, actions)

        val outcome = loop.run("where am i") { AssistantAgentLoop.Decision.Confirm }

        assertTrue(outcome is AssistantAgentLoop.Outcome.Answer)
        assertEquals("You're in /home/dev/proj on dev.", (outcome as AssistantAgentLoop.Outcome.Answer).text)
        assertEquals(listOf("get_context"), actions.calls)
        // Two model turns: initial + post-tool-result.
        assertEquals(2, client.turns.size)
    }

    @Test
    fun openFolder_navigation_autoRuns_withoutConfirm() = runTest {
        var confirmCalled = false
        val client = ScriptedClient(
            ArrayDeque(
                listOf(
                    toolCall(AssistantTools.OPEN_FOLDER, """{"host":"dev","path":"/home/dev/proj"}"""),
                    answer("Opened."),
                ),
            ),
        )
        val actions = RecordingActions()
        val loop = AssistantAgentLoop(client, actions)

        loop.run("open this folder") { confirmCalled = true; AssistantAgentLoop.Decision.Confirm }

        assertEquals(listOf("open_folder(dev,/home/dev/proj)"), actions.calls)
        assertTrue("nav tools must not hit the confirm gate", !confirmCalled)
    }

    @Test
    fun startSession_withAgent_confirmGated_thenRuns() = runTest {
        val client = ScriptedClient(
            ArrayDeque(
                listOf(
                    toolCall(
                        AssistantTools.START_SESSION,
                        """{"host":"dev","cwd":"/home/dev/proj","agent":"codex"}""",
                    ),
                    answer("Started a Codex session."),
                ),
            ),
        )
        val actions = RecordingActions()
        var seen: AssistantAgentLoop.Candidate? = null
        val loop = AssistantAgentLoop(client, actions)

        val outcome = loop.run("start a session here with codex") { candidate ->
            seen = candidate
            AssistantAgentLoop.Decision.Confirm
        }

        assertTrue(outcome is AssistantAgentLoop.Outcome.Answer)
        assertEquals(AssistantTools.START_SESSION, seen!!.toolName)
        assertEquals(listOf("start_session(dev,/home/dev/proj,codex)"), actions.calls)
    }

    @Test
    fun resolveFolder_confident_relaysCwd_thenStartSession() = runTest {
        val client = ScriptedClient(
            ArrayDeque(
                listOf(
                    toolCall(AssistantTools.RESOLVE_FOLDER, """{"host":"dev","query":"workshops"}""", "r1"),
                    toolCall(
                        AssistantTools.START_SESSION,
                        """{"host":"dev","cwd":"/home/dev/proj","agent":"claude"}""",
                        "c1",
                    ),
                    answer("Started Claude in /home/dev/proj."),
                ),
            ),
        )
        val actions = RecordingActions()
        val loop = AssistantAgentLoop(client, actions)

        val outcome = loop.run("open claude in the workshops folder") { AssistantAgentLoop.Decision.Confirm }

        assertTrue(outcome is AssistantAgentLoop.Outcome.Answer)
        assertTrue(actions.calls.contains("resolve_folder(dev,workshops)"))
        assertTrue(actions.calls.contains("start_session(dev,/home/dev/proj,claude)"))
    }

    @Test
    fun resolveFolder_startSession_thenSendPrompt_sequenceRunsInOrder() = runTest {
        val client = ScriptedClient(
            ArrayDeque(
                listOf(
                    toolCall(AssistantTools.RESOLVE_FOLDER, """{"host":"dev","query":"course-management-agent"}""", "r1"),
                    toolCall(
                        AssistantTools.START_SESSION,
                        """{"host":"dev","cwd":"/home/dev/proj","agent":"codex"}""",
                        "s1",
                    ),
                    toolCall(
                        AssistantTools.SEND_PROMPT_TO_SESSION,
                        """{"session_name":"course-management-agent","prompt":"write the tests"}""",
                        "p1",
                    ),
                    answer("Started Codex and sent the prompt."),
                ),
            ),
        )
        val actions = RecordingActions()
        val loop = AssistantAgentLoop(client, actions)

        val outcome = loop.run("start Codex in course-management-agent and send a prompt") {
            AssistantAgentLoop.Decision.Confirm
        }

        assertTrue(outcome is AssistantAgentLoop.Outcome.Answer)
        assertEquals(
            listOf(
                "resolve_folder(dev,course-management-agent)",
                "start_session(dev,/home/dev/proj,codex)",
                "send_prompt_to_session(course-management-agent,write the tests)",
            ),
            actions.calls,
        )
    }

    @Test
    fun sendPrompt_normalizesKnownSpeechTypoBeforeConfirmAndExecute() = runTest {
        val client = ScriptedClient(
            ArrayDeque(
                listOf(
                    toolCall(
                        AssistantTools.SEND_PROMPT_TO_SESSION,
                        """{"session_name":"llm-zoomcamp","prompt":"убери все эможди в ллм зумкампе"}""",
                    ),
                    answer("Sent."),
                ),
            ),
        )
        val actions = RecordingActions()
        val candidates = mutableListOf<String>()
        val loop = AssistantAgentLoop(client, actions)

        val outcome = loop.run("send prompt") { candidate ->
            candidates += candidate.summary
            AssistantAgentLoop.Decision.Confirm
        }

        assertTrue(outcome is AssistantAgentLoop.Outcome.Answer)
        assertEquals(
            listOf("Send prompt to llm-zoomcamp: убери все эмоджи в ллм зумкампе"),
            candidates,
        )
        assertEquals(
            listOf("send_prompt_to_session(llm-zoomcamp,убери все эмоджи в ллм зумкампе)"),
            actions.calls,
        )
    }

    @Test
    fun correctionStopsLaterToolCallsFromSameModelTurn_untilModelReplans() = runTest {
        val client = ScriptedClient(
            ArrayDeque(
                listOf(
                    toolCalls(
                        LlmToolCall(
                            "s1",
                            AssistantTools.START_SESSION,
                            """{"host":"dev","cwd":"/home/dev/wrong","agent":"codex"}""",
                        ),
                        LlmToolCall(
                            "p1",
                            AssistantTools.SEND_PROMPT_TO_SESSION,
                            """{"session_name":"wrong","prompt":"write the tests"}""",
                        ),
                    ),
                    toolCalls(
                        LlmToolCall(
                            "s2",
                            AssistantTools.START_SESSION,
                            """{"host":"dev","cwd":"/home/dev/proj","agent":"codex"}""",
                        ),
                        LlmToolCall(
                            "p2",
                            AssistantTools.SEND_PROMPT_TO_SESSION,
                            """{"session_name":"course-management-agent","prompt":"write the tests"}""",
                        ),
                    ),
                    answer("Started Codex and sent the prompt."),
                ),
            ),
        )
        val actions = RecordingActions()
        val candidates = mutableListOf<String>()
        val loop = AssistantAgentLoop(client, actions)

        val outcome = loop.run("start Codex in course-management-agent and send a prompt") { candidate ->
            candidates += candidate.summary
            if (candidate.summary.contains("/home/dev/wrong")) {
                AssistantAgentLoop.Decision.Correct("use /home/dev/proj and the course-management-agent session")
            } else {
                AssistantAgentLoop.Decision.Confirm
            }
        }

        assertTrue(outcome is AssistantAgentLoop.Outcome.Answer)
        assertEquals(
            listOf(
                "Start codex session in /home/dev/wrong on dev",
                "Start codex session in /home/dev/proj on dev",
                "Send prompt to course-management-agent: write the tests",
            ),
            candidates,
        )
        assertEquals(
            listOf(
                "start_session(dev,/home/dev/proj,codex)",
                "send_prompt_to_session(course-management-agent,write the tests)",
            ),
            actions.calls,
        )
        val correctionResults = client.turns[1].last().toolResults
        assertEquals(2, correctionResults.size)
        assertTrue(correctionResults[0].content.contains("Their correction"))
        assertTrue(correctionResults[1].content.contains("Not executed"))
    }

    @Test
    fun resolveFolder_ambiguous_raisesChoiceGate_relaysPickedCwd() = runTest {
        val client = ScriptedClient(
            ArrayDeque(
                listOf(
                    toolCall(AssistantTools.RESOLVE_FOLDER, """{"host":"dev","query":"workshop"}""", "r1"),
                    toolCall(
                        AssistantTools.START_SESSION,
                        """{"host":"dev","cwd":"/home/dev/rov/workshop","agent":"claude"}""",
                        "c1",
                    ),
                    answer("Started Claude in the ROV workshop."),
                ),
            ),
        )
        val actions = RecordingActions().apply {
            folderResolution = FolderResolutionResult.Resolved(
                FolderResolution.Ambiguous(
                    listOf(
                        FolderCandidate("/home/dev/rov/workshop", "ROV workshop"),
                        FolderCandidate("/home/dev/notes/workshop", "workshop"),
                    ),
                ),
            )
        }
        val loop = AssistantAgentLoop(client, actions)
        var offered: List<FolderCandidate>? = null

        val outcome = loop.run(
            transcript = "open claude in the workshop folder",
            confirmGate = { AssistantAgentLoop.Decision.Confirm },
            choiceGate = { _, candidates ->
                offered = candidates
                AssistantAgentLoop.ChoiceDecision.Pick(candidates.first { it.path == "/home/dev/rov/workshop" })
            },
        )

        assertTrue(outcome is AssistantAgentLoop.Outcome.Answer)
        assertEquals(2, offered!!.size)
        assertTrue(actions.calls.contains("start_session(dev,/home/dev/rov/workshop,claude)"))
    }

    @Test
    fun resolveFolder_ambiguous_choiceCancel_abortsLoop() = runTest {
        val client = ScriptedClient(
            ArrayDeque(
                listOf(
                    toolCall(AssistantTools.RESOLVE_FOLDER, """{"host":"dev","query":"workshop"}""", "r1"),
                ),
            ),
        )
        val actions = RecordingActions().apply {
            folderResolution = FolderResolutionResult.Resolved(
                FolderResolution.Ambiguous(
                    listOf(
                        FolderCandidate("/home/dev/rov/workshop", "ROV workshop"),
                        FolderCandidate("/home/dev/notes/workshop", "workshop"),
                    ),
                ),
            )
        }
        val loop = AssistantAgentLoop(client, actions)

        val outcome = loop.run(
            transcript = "open claude in the workshop folder",
            confirmGate = { AssistantAgentLoop.Decision.Confirm },
            choiceGate = { _, _ -> AssistantAgentLoop.ChoiceDecision.Cancel },
        )

        assertTrue(outcome is AssistantAgentLoop.Outcome.Cancelled)
        assertTrue(actions.calls.none { it.startsWith("start_session") })
    }

    @Test
    fun resolveFolder_noMatch_doesNotRaiseChoice() = runTest {
        var choiceCalled = false
        val client = ScriptedClient(
            ArrayDeque(
                listOf(
                    toolCall(AssistantTools.RESOLVE_FOLDER, """{"host":"dev","query":"nope"}""", "r1"),
                    answer("I couldn't find that folder."),
                ),
            ),
        )
        val actions = RecordingActions().apply {
            folderResolution = FolderResolutionResult.Resolved(
                FolderResolution.NoMatch(listOf(FolderCandidate("/home/dev/git/pocketshell", "pocketshell"))),
            )
        }
        val loop = AssistantAgentLoop(client, actions)

        val outcome = loop.run(
            transcript = "open claude in nope",
            confirmGate = { AssistantAgentLoop.Decision.Confirm },
            choiceGate = { _, _ -> choiceCalled = true; AssistantAgentLoop.ChoiceDecision.Cancel },
        )

        assertTrue(outcome is AssistantAgentLoop.Outcome.Answer)
        assertTrue("no-match must not raise the chooser", !choiceCalled)
        assertTrue(actions.calls.none { it.startsWith("start_session") })
    }

    @Test
    fun runCommand_confirm_reachesTerminal() = runTest {
        val client = ScriptedClient(
            ArrayDeque(
                listOf(
                    toolCall(AssistantTools.RUN_COMMAND, """{"command":"git status"}"""),
                    answer("Ran git status."),
                ),
            ),
        )
        val actions = RecordingActions()
        val loop = AssistantAgentLoop(client, actions)

        loop.run("show git status") { AssistantAgentLoop.Decision.Confirm }

        assertEquals(listOf("git status"), actions.ranCommands)
    }

    @Test
    fun runCommand_reject_correct_confirm_producesRevisedCandidate() = runTest {
        // Turn 1: model proposes the wrong command.
        // User rejects with a correction; loop relays it as the tool result.
        // Turn 2: model proposes the corrected command.
        // User confirms; it runs.
        val client = ScriptedClient(
            ArrayDeque(
                listOf(
                    toolCall(AssistantTools.RUN_COMMAND, """{"command":"ls"}""", id = "c1"),
                    toolCall(AssistantTools.RUN_COMMAND, """{"command":"git log --oneline -5"}""", id = "c2"),
                    answer("Showed the last 5 commits."),
                ),
            ),
        )
        val actions = RecordingActions()
        val candidates = mutableListOf<String>()
        val loop = AssistantAgentLoop(client, actions)

        var first = true
        val outcome = loop.run("show recent commits") { candidate ->
            candidates += candidate.summary
            if (first) {
                first = false
                AssistantAgentLoop.Decision.Correct("no, show the last 5 git commits")
            } else {
                AssistantAgentLoop.Decision.Confirm
            }
        }

        assertTrue(outcome is AssistantAgentLoop.Outcome.Answer)
        // Two candidates were prompted; the second is the revision.
        assertEquals(listOf("ls", "git log --oneline -5"), candidates)
        // Only the confirmed command actually ran.
        assertEquals(listOf("git log --oneline -5"), actions.ranCommands)
    }

    @Test
    fun runCommand_cancel_abortsLoop_withoutRunning() = runTest {
        val client = ScriptedClient(
            ArrayDeque(
                listOf(toolCall(AssistantTools.RUN_COMMAND, """{"command":"git status"}""")),
            ),
        )
        val actions = RecordingActions()
        val loop = AssistantAgentLoop(client, actions)

        val outcome = loop.run("git status") { AssistantAgentLoop.Decision.Cancel }

        assertTrue(outcome is AssistantAgentLoop.Outcome.Cancelled)
        assertTrue(actions.ranCommands.isEmpty())
    }

    @Test
    fun runCommand_forbidden_isBlockedBeforeConfirm() = runTest {
        var confirmCalled = false
        val client = ScriptedClient(
            ArrayDeque(
                listOf(
                    toolCall(AssistantTools.RUN_COMMAND, """{"command":"sudo rm -rf /"}"""),
                    answer("I can't run that."),
                ),
            ),
        )
        val actions = RecordingActions()
        val loop = AssistantAgentLoop(client, actions)

        val outcome = loop.run("delete everything") { confirmCalled = true; AssistantAgentLoop.Decision.Confirm }

        assertTrue(outcome is AssistantAgentLoop.Outcome.Answer)
        assertTrue("forbidden command must never reach the confirm gate", !confirmCalled)
        assertTrue("forbidden command must never run", actions.ranCommands.isEmpty())
    }

    @Test
    fun stepCap_failsGracefully() = runTest {
        // Always returns a tool call → never terminates → hits the cap.
        val infinite = object : AssistantLlmClient {
            override suspend fun complete(
                messages: List<LlmMessage>,
                tools: List<ToolSpec>,
                toolChoice: ToolChoice?,
            ): Result<LlmResponse> = Result.success(
                LlmResponse(null, listOf(LlmToolCall("c", AssistantTools.LIST_HOSTS, "{}")), StopReason.ToolUse),
            )
        }
        val loop = AssistantAgentLoop(infinite, RecordingActions(), maxSteps = 3)

        val outcome = loop.run("loop forever") { AssistantAgentLoop.Decision.Confirm }

        assertTrue(outcome is AssistantAgentLoop.Outcome.Failed)
    }

    @Test
    fun neverReturningModelCall_timesOutAsRetryableFailure() = runTest {
        val neverReturns = object : AssistantLlmClient {
            override suspend fun complete(
                messages: List<LlmMessage>,
                tools: List<ToolSpec>,
                toolChoice: ToolChoice?,
            ): Result<LlmResponse> = awaitCancellation()
        }
        val loop = AssistantAgentLoop(
            neverReturns,
            RecordingActions(),
            modelTurnTimeoutMs = 1_000,
        )

        val pending = async {
            loop.run("hang forever") { AssistantAgentLoop.Decision.Confirm }
        }
        advanceTimeBy(1_001)

        val outcome = pending.await()
        assertTrue(outcome is AssistantAgentLoop.Outcome.RetryableError)
        outcome as AssistantAgentLoop.Outcome.RetryableError
        assertEquals(AssistantFailureReason.ModelTimeout, outcome.reason)
        assertTrue(outcome.reason.retryable)
    }

    @Test
    fun modelTransportFailure_isRetryableFailure() = runTest {
        val transportFails = object : AssistantLlmClient {
            override suspend fun complete(
                messages: List<LlmMessage>,
                tools: List<ToolSpec>,
                toolChoice: ToolChoice?,
            ): Result<LlmResponse> =
                Result.failure(AssistantLlmException.Transport("network read failed"))
        }
        val loop = AssistantAgentLoop(transportFails, RecordingActions())

        val outcome = loop.run("use model") { AssistantAgentLoop.Decision.Confirm }

        assertTrue(outcome is AssistantAgentLoop.Outcome.RetryableError)
        outcome as AssistantAgentLoop.Outcome.RetryableError
        assertEquals(AssistantFailureReason.ModelTransport, outcome.reason)
        assertTrue(outcome.reason.retryable)
    }
}
