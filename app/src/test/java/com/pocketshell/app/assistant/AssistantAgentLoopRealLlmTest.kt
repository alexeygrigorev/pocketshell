package com.pocketshell.app.assistant

import com.pocketshell.core.assistant.AnthropicLlmClient
import com.pocketshell.core.assistant.AssistantLlmClient
import com.pocketshell.core.assistant.AssistantProviderConfig
import com.pocketshell.core.assistant.AssistantSettings
import com.pocketshell.core.assistant.LlmMessage
import com.pocketshell.core.assistant.LlmResponse
import com.pocketshell.core.assistant.LlmToolCall
import com.pocketshell.core.assistant.OpenAiLlmClient
import com.pocketshell.core.assistant.ToolChoice
import com.pocketshell.core.assistant.ToolSpec
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Opt-in product-level integration tests for the in-app assistant's
 * structured action sequence output.
 *
 * These tests are skipped unless POCKETSHELL_REAL_LLM_TESTS=1 is present.
 * Credentials are read only from process environment and the PocketShell repo
 * root `.env`; sibling repos and unrelated dotenv files are never inspected.
 */
class AssistantAgentLoopRealLlmTest {

    @Test(timeout = 240_000L)
    fun zai_courseManagementAgentSequence_callsExpectedTools() = runBlocking {
        runCourseManagementSequence(providerOrSkip(ProviderKind.Zai), correctionMode = false)
    }

    @Test(timeout = 240_000L)
    fun openAi_courseManagementAgentSequence_callsExpectedTools() = runBlocking {
        runCourseManagementSequence(providerOrSkip(ProviderKind.OpenAi), correctionMode = false)
    }

    @Test(timeout = 240_000L)
    fun zai_courseManagementAgentSequence_revisesAfterCorrection() = runBlocking {
        runCourseManagementSequence(providerOrSkip(ProviderKind.Zai), correctionMode = true)
    }

    @Test(timeout = 240_000L)
    fun openAi_courseManagementAgentSequence_revisesAfterCorrection() = runBlocking {
        runCourseManagementSequence(providerOrSkip(ProviderKind.OpenAi), correctionMode = true)
    }

    @Test(timeout = 240_000L)
    fun zai_llmZoomcampEmojiSequence_callsExpectedTools() = runBlocking {
        runLlmZoomcampEmojiSequence(providerOrSkip(ProviderKind.Zai))
    }

    @Test(timeout = 240_000L)
    fun openAi_llmZoomcampEmojiSequence_callsExpectedTools() = runBlocking {
        runLlmZoomcampEmojiSequence(providerOrSkip(ProviderKind.OpenAi))
    }

    private suspend fun runCourseManagementSequence(provider: ProviderCase, correctionMode: Boolean) {
        val actions = RecordingActions()
        val loop = AssistantAgentLoop(
            client = provider.client,
            actions = actions,
            maxSteps = 10,
            modelTurnTimeoutMs = 90_000L,
        )

        var correctedStartCandidate = false
        val candidates = mutableListOf<AssistantAgentLoop.Candidate>()
        val outcome = loop.run(
            transcript = "Start a Codex session on host dev in the course-management-agent project " +
                "and send it this prompt: $REQUESTED_PROMPT. Use project/folder lookup tools; " +
                "do not invent the project path.",
            confirmGate = { candidate ->
                candidates += candidate
                when {
                    correctionMode && !correctedStartCandidate &&
                        candidate.toolName == AssistantTools.START_SESSION -> {
                        correctedStartCandidate = true
                        AssistantAgentLoop.Decision.Correct(
                            "Use the course-management-agent project at " +
                                "$COURSE_PROJECT_PATH, launch codex, then send the original prompt.",
                        )
                    }
                    else -> AssistantAgentLoop.Decision.Confirm
                }
            },
        )

        assertTrue("expected successful answer from ${provider.name}, got $outcome", outcome is AssistantAgentLoop.Outcome.Answer)
        if (correctionMode) {
            assertTrue("correction flow did not reach a start_session candidate", correctedStartCandidate)
        }

        val toolCalls = provider.client.toolCalls
        assertStructuredToolSequence(
            toolCalls = toolCalls,
            expected = CourseManagementScenario,
            correctionMode = correctionMode,
        )
        assertExecutedActionSequence(
            actions = actions,
            expected = CourseManagementScenario,
            correctionMode = correctionMode,
        )
        assertTrue("expected send_prompt_to_session confirmation candidate", candidates.any {
            it.toolName == AssistantTools.SEND_PROMPT_TO_SESSION && it.summary.contains(REQUESTED_PROMPT)
        })
    }

    private suspend fun runLlmZoomcampEmojiSequence(provider: ProviderCase) {
        val actions = RecordingActions()
        val loop = AssistantAgentLoop(
            client = provider.client,
            actions = actions,
            maxSteps = 10,
            modelTurnTimeoutMs = 90_000L,
        )

        val candidates = mutableListOf<AssistantAgentLoop.Candidate>()
        val outcome = loop.run(
            transcript = LLM_ZOOMCAMP_USER_REQUEST,
            confirmGate = { candidate ->
                candidates += candidate
                AssistantAgentLoop.Decision.Confirm
            },
        )

        assertTrue("expected successful answer from ${provider.name}, got $outcome", outcome is AssistantAgentLoop.Outcome.Answer)

        val toolCalls = provider.client.toolCalls
        assertStructuredToolSequence(
            toolCalls = toolCalls,
            expected = LlmZoomcampEmojiScenario,
            correctionMode = false,
        )
        assertExecutedActionSequence(
            actions = actions,
            expected = LlmZoomcampEmojiScenario,
            correctionMode = false,
        )
        assertTrue("expected send_prompt_to_session confirmation candidate", candidates.any {
            it.toolName == AssistantTools.SEND_PROMPT_TO_SESSION &&
                promptMatches(it.summary, LLM_ZOOMCAMP_AGENT_PROMPT)
        })
    }

    private fun assertStructuredToolSequence(
        toolCalls: List<LlmToolCall>,
        expected: ExpectedActionSequence,
        correctionMode: Boolean,
    ) {
        val names = toolCalls.map { it.name }
        val lookupIndex = names.indexOfFirst {
            it == AssistantTools.GET_CONTEXT ||
                it == AssistantTools.LIST_FOLDERS ||
                it == AssistantTools.LIST_SESSIONS ||
                it == AssistantTools.RESOLVE_FOLDER
        }
        val resolveIndex = names.indexOf(AssistantTools.RESOLVE_FOLDER)
        val startIndexes = names.withIndex()
            .filter { it.value == AssistantTools.START_SESSION }
            .map { it.index }
        val sendIndex = names.indexOf(AssistantTools.SEND_PROMPT_TO_SESSION)

        assertTrue("model did not call a project/folder lookup tool: $names", lookupIndex >= 0)
        assertTrue("model did not call ${AssistantTools.RESOLVE_FOLDER}: $names", resolveIndex >= 0)
        assertTrue("model did not call ${AssistantTools.START_SESSION}: $names", startIndexes.isNotEmpty())
        assertTrue("model did not call ${AssistantTools.SEND_PROMPT_TO_SESSION}: $names", sendIndex >= 0)
        assertTrue("project lookup must precede start_session: $names", lookupIndex < startIndexes.first())
        assertTrue("resolve_folder must precede start_session: $names", resolveIndex < startIndexes.first())
        assertTrue("send_prompt_to_session must follow final start_session: $names", startIndexes.last() < sendIndex)
        if (correctionMode) {
            assertTrue("correction should produce a revised start_session tool call: $names", startIndexes.size >= 2)
        }

        val resolveArgs = JSONObject(toolCalls[resolveIndex].argumentsJson)
        assertEquals("dev", resolveArgs.optString("host"))
        val resolveQuery = resolveArgs.optString("query")
        assertTrue(
            "resolve_folder query should mention the target project: $resolveQuery",
            expected.resolveQueryTerms.any { resolveQuery.contains(it, ignoreCase = true) },
        )

        val finalStartArgs = JSONObject(toolCalls[startIndexes.last()].argumentsJson)
        assertEquals("dev", finalStartArgs.optString("host"))
        assertEquals(expected.projectPath, finalStartArgs.optString("cwd"))
        assertEquals("codex", finalStartArgs.optString("agent"))

        val sendArgs = JSONObject(toolCalls[sendIndex].argumentsJson)
        assertEquals(expected.sessionName, sendArgs.optString("session_name"))
        assertTrue(
            "send_prompt_to_session prompt should contain the expected agent prompt",
            promptMatches(sendArgs.optString("prompt"), expected.agentPrompt),
        )
        expected.forbiddenPromptTerms.forEach { forbidden ->
            assertTrue(
                "send_prompt_to_session prompt should normalize '$forbidden': ${sendArgs.optString("prompt")}",
                !sendArgs.optString("prompt").contains(forbidden, ignoreCase = true),
            )
        }
    }

    private fun assertExecutedActionSequence(
        actions: RecordingActions,
        expected: ExpectedActionSequence,
        correctionMode: Boolean,
    ) {
        val names = actions.invocations.map { it.name }
        val resolveIndex = names.indexOf(AssistantTools.RESOLVE_FOLDER)
        val startIndexes = names.withIndex()
            .filter { it.value == AssistantTools.START_SESSION }
            .map { it.index }
        val sendIndex = names.indexOf(AssistantTools.SEND_PROMPT_TO_SESSION)

        assertTrue("resolve_folder was not executed: $names", resolveIndex >= 0)
        assertTrue("start_session was not executed: $names", startIndexes.isNotEmpty())
        assertTrue("send_prompt_to_session was not executed: $names", sendIndex >= 0)
        assertTrue("executed actions are out of order: $names", resolveIndex < startIndexes.last() && startIndexes.last() < sendIndex)
        if (correctionMode) {
            assertEquals("only the confirmed revised start_session should execute", 1, startIndexes.size)
        }

        assertEquals(
            ActionInvocation(
                AssistantTools.START_SESSION,
                mapOf("host" to "dev", "cwd" to expected.projectPath, "agent" to "codex"),
            ),
            actions.invocations[startIndexes.last()],
        )
        val send = actions.invocations[sendIndex]
        assertEquals(AssistantTools.SEND_PROMPT_TO_SESSION, send.name)
        assertEquals(expected.sessionName, send.args["session_name"])
        assertTrue(
            "executed send-prompt action should contain expected agent prompt",
            promptMatches(send.args["prompt"].orEmpty(), expected.agentPrompt),
        )
        expected.forbiddenPromptTerms.forEach { forbidden ->
            assertTrue(
                "executed send-prompt action should normalize '$forbidden': ${send.args["prompt"]}",
                !send.args["prompt"].orEmpty().contains(forbidden, ignoreCase = true),
            )
        }
    }

    private fun promptMatches(actual: String, expected: String): Boolean =
        actual.normalizedPrompt().contains(expected.normalizedPrompt())

    private fun String.normalizedPrompt(): String =
        lowercase()
            .replace('ё', 'е')
            .replace(Regex("\\s+"), " ")
            .trim()
            .trimEnd('.', '!', '?', ':', ';')

    private class RecordingClient(private val delegate: AssistantLlmClient) : AssistantLlmClient {
        val turns = mutableListOf<ModelTurn>()

        val toolCalls: List<LlmToolCall>
            get() = turns.flatMap { it.response?.toolCalls.orEmpty() }

        override suspend fun complete(
            messages: List<LlmMessage>,
            tools: List<ToolSpec>,
            toolChoice: ToolChoice?,
        ): Result<LlmResponse> {
            val result = delegate.complete(messages, tools, toolChoice)
            turns += ModelTurn(
                messageRoles = messages.map { it.role },
                toolNames = tools.map { it.name },
                response = result.getOrNull(),
            )
            return result
        }
    }

    private data class ModelTurn(
        val messageRoles: List<LlmMessage.Role>,
        val toolNames: List<String>,
        val response: LlmResponse?,
    )

    private class RecordingActions : AssistantActions {
        val invocations = mutableListOf<ActionInvocation>()

        override suspend fun getContext(): String {
            invocations += ActionInvocation(AssistantTools.GET_CONTEXT, emptyMap())
            return """
                screen: host detail
                active_host: dev
                available_projects:
                - course-management-agent at $COURSE_PROJECT_PATH
                - llm-zoomcamp at $LLM_ZOOMCAMP_PROJECT_PATH
                - course-marketing-site at /home/dev/projects/course-marketing-site
                - pocketshell at /home/dev/projects/pocketshell
                sessions:
                - main cwd=/home/dev/projects/pocketshell agent=shell
            """.trimIndent()
        }

        override suspend fun listHosts(): String {
            invocations += ActionInvocation(AssistantTools.LIST_HOSTS, emptyMap())
            return "dev"
        }

        override suspend fun listFolders(host: String): String {
            invocations += ActionInvocation(AssistantTools.LIST_FOLDERS, mapOf("host" to host))
            return """
                $COURSE_PROJECT_PATH
                $LLM_ZOOMCAMP_PROJECT_PATH
                /home/dev/projects/course-marketing-site
                /home/dev/projects/pocketshell
            """.trimIndent()
        }

        override suspend fun resolveFolder(host: String, query: String): FolderResolutionResult {
            invocations += ActionInvocation(AssistantTools.RESOLVE_FOLDER, mapOf("host" to host, "query" to query))
            val project = projectForQuery(query)
            return FolderResolutionResult.Resolved(
                FolderResolution.Confident(
                    FolderCandidate(path = project.projectPath, label = project.sessionName),
                ),
            )
        }

        override suspend fun listSessions(host: String): String {
            invocations += ActionInvocation(AssistantTools.LIST_SESSIONS, mapOf("host" to host))
            return "main cwd=/home/dev/projects/pocketshell agent=shell"
        }

        override suspend fun listDirectory(path: String): String = "README.md\napp\nshared"

        override suspend fun readFile(path: String): String = "test file contents"

        override suspend fun listRepos(): String = "alexeygrigorev/pocketshell"

        override suspend fun openFolder(host: String, path: String): String = "opened $path on $host"

        override suspend fun openSession(sessionName: String): String = "opening $sessionName"

        override suspend fun openScreen(destination: String): String = "opened $destination"

        override suspend fun startSession(host: String, cwd: String, agent: String): ActionResult {
            invocations += ActionInvocation(
                AssistantTools.START_SESSION,
                mapOf("host" to host, "cwd" to cwd, "agent" to agent),
            )
            val sessionName = sessionNameForPath(cwd)
            return ActionResult.ok(
                "Started $agent session \"$sessionName\" in $cwd on $host. " +
                    "Use session_name=$sessionName for send_prompt_to_session.",
            )
        }

        override suspend fun sendPromptToSession(sessionName: String, prompt: String): ActionResult {
            invocations += ActionInvocation(
                AssistantTools.SEND_PROMPT_TO_SESSION,
                mapOf("session_name" to sessionName, "prompt" to prompt),
            )
            return ActionResult.ok("Sent prompt to $sessionName.")
        }

        override suspend fun createProject(host: String, parentPath: String, folderName: String): ActionResult =
            ActionResult.error("not used by this test")

        override suspend fun runCommand(command: String): ActionResult =
            ActionResult.error("not used by this test")

        override suspend fun createFile(path: String, content: String): ActionResult =
            ActionResult.error("not used by this test")

        override suspend fun cloneRepo(fullName: String, folder: String?): ActionResult =
            ActionResult.error("not used by this test")

        private fun projectForQuery(query: String): ExpectedActionSequence =
            if (
                query.contains("zoom", ignoreCase = true) ||
                query.contains("зум", ignoreCase = true) ||
                query.contains("llm", ignoreCase = true) ||
                query.contains("ллм", ignoreCase = true)
            ) {
                LlmZoomcampEmojiScenario
            } else {
                CourseManagementScenario
            }

        private fun sessionNameForPath(path: String): String =
            if (path == LLM_ZOOMCAMP_PROJECT_PATH) LLM_ZOOMCAMP_SESSION_NAME else COURSE_SESSION_NAME
    }

    private data class ActionInvocation(val name: String, val args: Map<String, String>)

    private data class ExpectedActionSequence(
        val projectPath: String,
        val sessionName: String,
        val agentPrompt: String,
        val resolveQueryTerms: List<String>,
        val forbiddenPromptTerms: List<String> = emptyList(),
    )

    private data class ProviderCase(
        val name: String,
        val client: RecordingClient,
    )

    private enum class ProviderKind {
        Zai,
        OpenAi,
    }

    private fun providerOrSkip(kind: ProviderKind): ProviderCase {
        val env = repoEnv()
        assumeTrue(
            "set POCKETSHELL_REAL_LLM_TESTS=1 to run real LLM tests",
            env["POCKETSHELL_REAL_LLM_TESTS"] == "1",
        )
        return when (kind) {
            ProviderKind.Zai -> zaiProviderOrSkip(env)
            ProviderKind.OpenAi -> openAiProviderOrSkip(env)
        }
    }

    private fun zaiProviderOrSkip(env: Map<String, String>): ProviderCase {
        val key = env["ZAI_API_KEY"]?.takeIf { it.isNotBlank() }
        assumeTrue(
            "ZAI real LLM scenario skipped: missing ZAI_API_KEY in process env or repo root .env",
            key != null,
        )
        return ProviderCase(
            name = "ZAI",
            client = RecordingClient(
                AnthropicLlmClient(
                    AssistantProviderConfig(
                        apiKey = key!!.toCharArray(),
                        baseUrl = env["ZAI_BASE_URL"] ?: AssistantSettings.DEFAULT_ZAI_BASE_URL,
                        model = env["ZAI_MODEL"] ?: "glm-4.6",
                        maxTokens = env["POCKETSHELL_REAL_LLM_MAX_TOKENS"]?.toIntOrNull() ?: 2048,
                    ),
                ),
            ),
        )
    }

    private fun openAiProviderOrSkip(env: Map<String, String>): ProviderCase {
        val key = env["OPENAI_API_KEY"]?.takeIf { it.isNotBlank() }
        assumeTrue(
            "OpenAI real LLM scenario skipped: missing OPENAI_API_KEY in process env or repo root .env",
            key != null,
        )
        return ProviderCase(
            name = "OpenAI",
            client = RecordingClient(
                OpenAiLlmClient(
                    AssistantProviderConfig(
                        apiKey = key!!.toCharArray(),
                        baseUrl = env["OPENAI_BASE_URL"] ?: AssistantSettings.DEFAULT_OPENAI_BASE_URL,
                        model = env["OPENAI_MODEL"] ?: AssistantSettings.DEFAULT_OPENAI_MODEL,
                        maxTokens = env["POCKETSHELL_REAL_LLM_MAX_TOKENS"]?.toIntOrNull() ?: 2048,
                    ),
                ),
            ),
        )
    }

    private fun repoEnv(): Map<String, String> {
        val dotenv = projectRoot().resolve(".env")
        val fromFile = if (Files.isRegularFile(dotenv)) parseDotenv(dotenv) else emptyMap()
        return fromFile + System.getenv()
    }

    private fun projectRoot(): Path {
        var current = Paths.get("").toAbsolutePath()
        while (current.parent != null && !Files.exists(current.resolve("settings.gradle.kts"))) {
            current = current.parent
        }
        return current
    }

    private fun parseDotenv(path: Path): Map<String, String> {
        val values = mutableMapOf<String, String>()
        Files.readAllLines(path).forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val withoutExport = line.removePrefix("export ").trim()
            val splitAt = withoutExport.indexOf('=')
            if (splitAt <= 0) return@forEach
            val key = withoutExport.substring(0, splitAt).trim()
            val value = withoutExport.substring(splitAt + 1).trim().stripMatchingQuotes()
            if (key.isNotBlank()) values[key] = value
        }
        return values
    }

    private fun String.stripMatchingQuotes(): String =
        if (length >= 2 && ((first() == '"' && last() == '"') || (first() == '\'' && last() == '\''))) {
            substring(1, length - 1)
        } else {
            this
        }

    private companion object {
        const val COURSE_PROJECT_PATH = "/home/dev/projects/course-management-agent"
        const val COURSE_SESSION_NAME = "course-management-agent"
        const val REQUESTED_PROMPT = "Review the billing export code and list the first three missing tests."

        const val LLM_ZOOMCAMP_PROJECT_PATH = "/home/dev/projects/llm-zoomcamp"
        const val LLM_ZOOMCAMP_SESSION_NAME = "llm-zoomcamp"
        const val LLM_ZOOMCAMP_USER_REQUEST = "убери все эможди в ллм зумкампе"
        const val LLM_ZOOMCAMP_AGENT_PROMPT = "убери все эмоджи"

        val CourseManagementScenario = ExpectedActionSequence(
            projectPath = COURSE_PROJECT_PATH,
            sessionName = COURSE_SESSION_NAME,
            agentPrompt = REQUESTED_PROMPT,
            resolveQueryTerms = listOf("course", "management"),
        )
        val LlmZoomcampEmojiScenario = ExpectedActionSequence(
            projectPath = LLM_ZOOMCAMP_PROJECT_PATH,
            sessionName = LLM_ZOOMCAMP_SESSION_NAME,
            agentPrompt = LLM_ZOOMCAMP_AGENT_PROMPT,
            resolveQueryTerms = listOf("llm", "ллм", "zoom", "зум"),
            forbiddenPromptTerms = listOf("эможди"),
        )
    }
}
