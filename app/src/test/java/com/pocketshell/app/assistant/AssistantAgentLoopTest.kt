package com.pocketshell.app.assistant

import com.pocketshell.core.assistant.AssistantLlmClient
import com.pocketshell.core.assistant.LlmMessage
import com.pocketshell.core.assistant.LlmResponse
import com.pocketshell.core.assistant.LlmToolCall
import com.pocketshell.core.assistant.StopReason
import com.pocketshell.core.assistant.ToolChoice
import com.pocketshell.core.assistant.ToolSpec
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the provider-agnostic [AssistantAgentLoop] (issue #266),
 * driven by a fake [AssistantLlmClient] that scripts tool calls — including a
 * reject → correct → confirm sequence and the safety gate.
 */
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
}
