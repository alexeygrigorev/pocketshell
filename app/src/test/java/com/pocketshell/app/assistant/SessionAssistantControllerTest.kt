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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the confirm-or-correct UI state machine at the controller level
 * (issue #266): a mutating candidate parks in [AssistantUiState.Confirming],
 * a correction re-prompts with a revised candidate, and confirm runs it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
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
        val startedSessions = mutableListOf<String>()
        override suspend fun getContext() = "ctx"
        override suspend fun listHosts() = "dev"
        override suspend fun listFolders(host: String) = "f"
        var folderResolution: FolderResolutionResult =
            FolderResolutionResult.Resolved(
                FolderResolution.Confident(FolderCandidate("/home/dev/proj", "proj")),
            )
        override suspend fun resolveFolder(host: String, query: String) = folderResolution
        override suspend fun listSessions(host: String) = "s"
        override suspend fun listDirectory(path: String) = "d"
        override suspend fun readFile(path: String) = "r"
        override suspend fun listRepos() = "repos"
        override suspend fun openFolder(host: String, path: String) = "open"
        override suspend fun openSession(sessionName: String) = "open"
        override suspend fun openScreen(destination: String) = "open"
        override suspend fun startSession(host: String, cwd: String, agent: String): ActionResult {
            startedSessions += "start_session($host,$cwd,$agent)"; return ActionResult.ok("ok")
        }
        override suspend fun sendPromptToSession(sessionName: String, prompt: String) = ActionResult.ok("ok")
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
    fun ambiguousFolder_parksInChoosing_thenPickRunsStartSession() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
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
        val client = ScriptedClient(
            ArrayDeque(
                listOf(
                    toolCall(AssistantTools.RESOLVE_FOLDER, """{"host":"dev","query":"workshop"}""", "r1"),
                    toolCall(
                        AssistantTools.START_SESSION,
                        """{"host":"dev","cwd":"/home/dev/rov/workshop","agent":"claude"}""",
                        "c1",
                    ),
                    LlmResponse("Started Claude.", stopReason = StopReason.EndTurn),
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

        controller.start("open claude in the workshop folder")
        advanceUntilIdle()

        val choosing = controller.state.value
        assertTrue("expected Choosing, got $choosing", choosing is AssistantUiState.Choosing)
        choosing as AssistantUiState.Choosing
        assertEquals("workshop", choosing.query)
        assertEquals(2, choosing.candidates.size)

        controller.choose(choosing.candidates.first { it.path == "/home/dev/rov/workshop" })
        advanceUntilIdle()

        // After the folder pick the model proposes start_session, which is a
        // mutating action — it still parks in the confirm gate (disambiguation
        // chose the folder, confirm still authorizes the create).
        val confirming = controller.state.value
        assertTrue("expected Confirming, got $confirming", confirming is AssistantUiState.Confirming)
        confirming as AssistantUiState.Confirming
        assertTrue(confirming.candidate.summary.contains("/home/dev/rov/workshop"))

        controller.confirm()
        advanceUntilIdle()

        assertTrue(controller.state.value is AssistantUiState.Done)
        assertEquals(listOf("start_session(dev,/home/dev/rov/workshop,claude)"), actions.startedSessions)
    }

    @Test
    fun ambiguousFolder_cancelChoice_endsRun() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
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
        val client = ScriptedClient(
            ArrayDeque(
                listOf(
                    toolCall(AssistantTools.RESOLVE_FOLDER, """{"host":"dev","query":"workshop"}""", "r1"),
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

        controller.start("open claude in the workshop folder")
        advanceUntilIdle()
        assertTrue(controller.state.value is AssistantUiState.Choosing)

        controller.cancelChoice()
        advanceUntilIdle()

        assertTrue(controller.state.value is AssistantUiState.Done)
        assertTrue(actions.startedSessions.isEmpty())
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

    @Test
    fun modelTimeout_surfacesRetryableErrorState() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val neverReturns = object : AssistantLlmClient {
            override suspend fun complete(
                messages: List<LlmMessage>,
                tools: List<ToolSpec>,
                toolChoice: ToolChoice?,
            ): Result<LlmResponse> = awaitCancellation()
        }
        val controller = SessionAssistantController(
            scope = kotlinx.coroutines.CoroutineScope(dispatcher),
            sessionFactory = {
                SessionAssistantController.AssistantRunDeps(
                    client = neverReturns,
                    actions = RecordingActions(),
                    traceSink = NoOpAssistantTraceSink,
                    installId = "i",
                    sessionId = null,
                )
            },
            modelTurnTimeoutMs = 1_000,
        )

        controller.start("do something")
        assertTrue(controller.state.value is AssistantUiState.Thinking)

        runCurrent()
        advanceTimeBy(1_001)
        advanceUntilIdle()

        val state = controller.state.value
        assertTrue(state is AssistantUiState.Error)
        state as AssistantUiState.Error
        assertEquals(AssistantFailureReason.ModelTimeout, state.reason)
        assertTrue(state.retryable)
    }

    @Test
    fun retryAfterTransportFailureReusesLastTranscript() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val attempts = mutableListOf<String>()
        val client = object : AssistantLlmClient {
            override suspend fun complete(
                messages: List<LlmMessage>,
                tools: List<ToolSpec>,
                toolChoice: ToolChoice?,
            ): Result<LlmResponse> {
                attempts += messages.last { it.role == LlmMessage.Role.User }.text.orEmpty()
                return if (attempts.size == 1) {
                    Result.failure(AssistantLlmException.Transport("network read failed"))
                } else {
                    Result.success(LlmResponse("done", stopReason = StopReason.EndTurn))
                }
            }
        }
        val controller = SessionAssistantController(
            scope = kotlinx.coroutines.CoroutineScope(dispatcher),
            sessionFactory = {
                SessionAssistantController.AssistantRunDeps(
                    client = client,
                    actions = RecordingActions(),
                    traceSink = NoOpAssistantTraceSink,
                    installId = "i",
                    sessionId = null,
                )
            },
        )

        controller.start("  do something  ")
        advanceUntilIdle()

        val error = controller.state.value
        assertTrue(error is AssistantUiState.Error)
        error as AssistantUiState.Error
        assertEquals(AssistantFailureReason.ModelTransport, error.reason)
        assertTrue(error.retryable)

        controller.retry()
        assertEquals(AssistantUiState.Thinking("do something"), controller.state.value)
        advanceUntilIdle()

        assertEquals(listOf("do something", "do something"), attempts)
        assertEquals(AssistantUiState.Done("done"), controller.state.value)
    }

    @Test
    fun retryDoesNothingForNonRetryableError() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var attempts = 0
        val client = object : AssistantLlmClient {
            override suspend fun complete(
                messages: List<LlmMessage>,
                tools: List<ToolSpec>,
                toolChoice: ToolChoice?,
            ): Result<LlmResponse> {
                attempts += 1
                return Result.failure(AssistantLlmException.Auth("bad key"))
            }
        }
        val controller = SessionAssistantController(
            scope = kotlinx.coroutines.CoroutineScope(dispatcher),
            sessionFactory = {
                SessionAssistantController.AssistantRunDeps(
                    client = client,
                    actions = RecordingActions(),
                    traceSink = NoOpAssistantTraceSink,
                    installId = "i",
                    sessionId = null,
                )
            },
        )

        controller.start("do something")
        advanceUntilIdle()
        val before = controller.state.value
        assertTrue(before is AssistantUiState.Error)
        controller.retry()
        advanceUntilIdle()

        assertEquals(1, attempts)
        assertEquals(before, controller.state.value)
    }
}
