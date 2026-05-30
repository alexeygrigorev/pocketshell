package com.pocketshell.app.assistant

import com.pocketshell.core.assistant.AssistantLlmClient
import com.pocketshell.core.assistant.LlmMessage
import com.pocketshell.core.assistant.LlmResponse
import com.pocketshell.core.assistant.LlmToolCall
import com.pocketshell.core.assistant.StopReason
import com.pocketshell.core.assistant.ToolChoice
import com.pocketshell.core.assistant.ToolSpec
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the confirm-or-correct UI state machine at the controller level
 * (issue #266): a mutating candidate parks in [AssistantUiState.Confirming],
 * a correction re-prompts with a revised candidate, and confirm runs it.
 */
class SessionAssistantControllerTest {

    private class ScriptedClient(private val q: ArrayDeque<LlmResponse>) : AssistantLlmClient {
        override suspend fun complete(
            messages: List<LlmMessage>,
            tools: List<ToolSpec>,
            toolChoice: ToolChoice?,
        ): Result<LlmResponse> =
            Result.success(q.removeFirstOrNull() ?: LlmResponse("done", stopReason = StopReason.EndTurn))
    }

    private class RecordingActions : AssistantActions {
        val ran = mutableListOf<String>()
        override suspend fun getContext() = "ctx"
        override suspend fun listHosts() = "dev"
        override suspend fun listFolders(host: String) = "f"
        override suspend fun listSessions(host: String) = "s"
        override suspend fun listDirectory(path: String) = "d"
        override suspend fun readFile(path: String) = "r"
        override suspend fun listRepos() = "repos"
        override suspend fun openFolder(host: String, path: String) = "open"
        override suspend fun openSession(sessionName: String) = "open"
        override suspend fun openScreen(destination: String) = "open"
        override suspend fun startSession(host: String, cwd: String, agent: String) = ActionResult.ok("ok")
        override suspend fun createProject(host: String, parentPath: String, folderName: String) =
            ActionResult.ok("ok")
        override suspend fun runCommand(command: String): ActionResult { ran += command; return ActionResult.ok("ok") }
        override suspend fun createFile(path: String, content: String) = ActionResult.ok("ok")
        override suspend fun cloneRepo(fullName: String, folder: String?) = ActionResult.ok("ok")
    }

    private fun toolCall(name: String, args: String, id: String = "c") =
        LlmResponse(null, listOf(LlmToolCall(id, name, args)), StopReason.ToolUse)

    @Test
    fun rejectThenCorrectThenConfirm_runsRevisedCandidate() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val actions = RecordingActions()
        val client = ScriptedClient(
            ArrayDeque(
                listOf(
                    toolCall(AssistantTools.RUN_COMMAND, """{"command":"ls"}""", "c1"),
                    toolCall(AssistantTools.RUN_COMMAND, """{"command":"git log -5"}""", "c2"),
                    LlmResponse("Showed commits.", stopReason = StopReason.EndTurn),
                ),
            ),
        )
        val controller = SessionAssistantController(
            scope = kotlinx.coroutines.CoroutineScope(dispatcher),
            sessionFactory = {
                SessionAssistantController.AssistantRunDeps(
                    client = client,
                    actions = actions,
                    traceSink = NoOpAssistantTraceSink,
                    installId = "i",
                    sessionId = null,
                )
            },
        )

        controller.start("show recent commits")
        advanceUntilIdle()

        // First candidate parked for confirmation.
        var state = controller.state.value
        assertTrue(state is AssistantUiState.Confirming)
        assertEquals("ls", (state as AssistantUiState.Confirming).candidate.summary)

        // Reject + correct.
        controller.correct("no, show the last 5 git commits")
        advanceUntilIdle()

        // Revised candidate parked.
        state = controller.state.value
        assertTrue(state is AssistantUiState.Confirming)
        assertEquals("git log -5", (state as AssistantUiState.Confirming).candidate.summary)

        // Confirm the revision.
        controller.confirm()
        advanceUntilIdle()

        assertTrue(controller.state.value is AssistantUiState.Done)
        assertEquals(listOf("git log -5"), actions.ran)
    }

    @Test
    fun missingConfig_surfacesError() = runTest {
        val controller = SessionAssistantController(
            scope = kotlinx.coroutines.CoroutineScope(StandardTestDispatcher(testScheduler)),
            sessionFactory = { null },
        )
        controller.start("do something")
        advanceUntilIdle()
        assertTrue(controller.state.value is AssistantUiState.Error)
    }
}
