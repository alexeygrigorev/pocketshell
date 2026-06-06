package com.pocketshell.app.assistant

import com.pocketshell.core.assistant.AssistantLlmClient
import com.pocketshell.core.assistant.LlmMessage
import com.pocketshell.core.assistant.LlmResponse
import com.pocketshell.core.assistant.LlmToolCall
import com.pocketshell.core.assistant.StopReason
import com.pocketshell.core.assistant.ToolChoice
import com.pocketshell.core.assistant.ToolSpec
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies trace emission for tool dispatches (issue #266 / #270): every
 * mutating dispatch emits a trace with `result`, secret values never appear
 * in `args`, and a stable `install_id` is included.
 */
class AssistantTraceTest {

    private class CapturingSink : AssistantTraceSink {
        val events = mutableListOf<AssistantTraceEvent>()
        override suspend fun emit(event: AssistantTraceEvent) { events += event }
    }

    private class StubActions : AssistantActions {
        override suspend fun getContext() = "ctx"
        override suspend fun listHosts() = "dev"
        override suspend fun listFolders(host: String) = "f"
        override suspend fun resolveFolder(host: String, query: String) =
            FolderResolutionResult.Resolved(
                FolderResolution.Confident(FolderCandidate("/home/dev/proj", "proj")),
            )
        override suspend fun listSessions(host: String) = "s"
        override suspend fun listDirectory(path: String) = "d"
        override suspend fun readFile(path: String) = "r"
        override suspend fun listRepos() = "repos"
        override suspend fun openFolder(host: String, path: String) = "open"
        override suspend fun openSession(sessionName: String) = "open"
        override suspend fun openScreen(destination: String) = "open"
        override suspend fun startSession(host: String, cwd: String, agent: String) = ActionResult.ok("ok")
        override suspend fun sendPromptToSession(sessionName: String, prompt: String) = ActionResult.ok("ok")
        override suspend fun createProject(host: String, parentPath: String, folderName: String) =
            ActionResult.ok("ok")
        override suspend fun runCommand(command: String) = ActionResult.ok("ok")
        override suspend fun createFile(path: String, content: String) = ActionResult.ok("ok")
        override suspend fun cloneRepo(fullName: String, folder: String?) = ActionResult.ok("ok")
    }

    private fun client(vararg responses: LlmResponse): AssistantLlmClient {
        val q = ArrayDeque(responses.toList())
        return object : AssistantLlmClient {
            override suspend fun complete(
                messages: List<LlmMessage>,
                tools: List<ToolSpec>,
                toolChoice: ToolChoice?,
            ): Result<LlmResponse> = Result.success(
                q.removeFirstOrNull() ?: LlmResponse("done", stopReason = StopReason.EndTurn),
            )
        }
    }

    private fun toolCall(name: String, args: String) =
        LlmResponse(null, listOf(LlmToolCall("c", name, args)), StopReason.ToolUse)

    @Test
    fun mutatingDispatch_emitsTrace_withResultAndInstallId() = runTest {
        val sink = CapturingSink()
        val loop = AssistantAgentLoop(
            client = client(
                toolCall(AssistantTools.RUN_COMMAND, """{"command":"git status"}"""),
                LlmResponse("done", stopReason = StopReason.EndTurn),
            ),
            actions = StubActions(),
            traceSink = sink,
            installId = "install-123",
        )

        loop.run("git status", confirmGate = { AssistantAgentLoop.Decision.Confirm })

        val event = sink.events.single { it.action == AssistantTools.RUN_COMMAND }
        assertEquals("ok", event.result)
        assertEquals("install-123", event.installId)
        assertEquals("git status", event.args["command"])
    }

    @Test
    fun createFile_neverPutsContentInTraceArgs() = runTest {
        val sink = CapturingSink()
        val loop = AssistantAgentLoop(
            client = client(
                toolCall(
                    AssistantTools.CREATE_FILE,
                    """{"path":"/home/dev/secret.env","content":"API_KEY=sk-supersecret"}""",
                ),
                LlmResponse("done", stopReason = StopReason.EndTurn),
            ),
            actions = StubActions(),
            traceSink = sink,
            installId = "i",
        )

        loop.run("write the env file", confirmGate = { AssistantAgentLoop.Decision.Confirm })

        val event = sink.events.single { it.action == AssistantTools.CREATE_FILE }
        assertEquals(AssistantAgentLoop.REDACTED, event.args["content"])
        // The secret must not appear anywhere in the emitted args.
        assertFalse(event.args.values.any { it.contains("sk-supersecret") })
        assertFalse(event.toJson().contains("sk-supersecret"))
    }

    @Test
    fun traceJson_hasCanonicalShape() {
        val event = AssistantTraceEvent(
            action = "run_command",
            targetHost = "dev",
            cwd = "/home/dev",
            args = mapOf("command" to "ls"),
            result = "ok",
            installId = "i-1",
            sessionId = "sess-9",
            timestampMillis = 1000L,
        )
        val json = JSONObject(event.toJson())
        assertEquals(1000L, json.getLong("ts"))
        assertEquals("phone", json.getString("source"))
        assertEquals("agent_action", json.getString("kind"))
        assertEquals("run_command", json.getString("action"))
        assertEquals("dev", json.getString("target_host"))
        assertEquals("/home/dev", json.getString("cwd"))
        assertEquals("ok", json.getString("result"))
        assertEquals("i-1", json.getString("install_id"))
        assertEquals("sess-9", json.getString("session_id"))
        assertEquals("ls", json.getJSONObject("args").getString("command"))
    }

    @Test
    fun cloneRepo_emitsTrace() = runTest {
        val sink = CapturingSink()
        val loop = AssistantAgentLoop(
            client = client(
                toolCall(AssistantTools.CLONE_REPO, """{"full_name":"owner/repo"}"""),
                LlmResponse("done", stopReason = StopReason.EndTurn),
            ),
            actions = StubActions(),
            traceSink = sink,
            installId = "i",
        )

        loop.run("clone owner/repo", confirmGate = { AssistantAgentLoop.Decision.Confirm })

        assertTrue(sink.events.any { it.action == AssistantTools.CLONE_REPO && it.result == "ok" })
    }
}
