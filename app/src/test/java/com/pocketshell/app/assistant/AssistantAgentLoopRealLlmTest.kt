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
 * Opt-in real-LLM integration tests for the in-app assistant's structured
 * action output. These tests are excluded from the normal Gradle unit-test
 * tasks; run them only through :app:realLlmTest with
 * POCKETSHELL_REAL_LLM_TESTS=1.
 *
 * Credential hygiene: provider keys are read only from this checkout's repo
 * root .env. The harness does not inspect sibling repos, app-local dotenv
 * files, .env.local, shell history, or any other secret source.
 */
class AssistantAgentLoopRealLlmTest {

    @Test(timeout = 240_000L)
    fun openAi_llmZoomcampEmojiSequence_callsExpectedTools() = runBlocking {
        runLlmZoomcampEmojiSequence(openAiProviderOrSkip(), correctionMode = false)
    }

    @Test(timeout = 240_000L)
    fun zai_llmZoomcampEmojiSequence_callsExpectedTools() = runBlocking {
        runLlmZoomcampEmojiSequence(zaiProviderOrSkip(), correctionMode = false)
    }

    @Test(timeout = 240_000L)
    fun zai_llmZoomcampEmojiSequence_revisesAfterCorrection() = runBlocking {
        runLlmZoomcampEmojiSequence(zaiProviderOrSkip(), correctionMode = true)
    }

    private suspend fun runLlmZoomcampEmojiSequence(
        provider: ProviderCase,
        correctionMode: Boolean,
    ) {
        val actions = RecordingActions()
        val loop = AssistantAgentLoop(
            client = provider.client,
            actions = actions,
            maxSteps = if (correctionMode) 14 else 10,
            modelTurnTimeoutMs = 90_000L,
        )

        var correctedPromptCandidate = false
        val candidates = mutableListOf<AssistantAgentLoop.Candidate>()
        val outcome = loop.run(
            transcript = LLM_ZOOMCAMP_USER_REQUEST,
            confirmGate = { candidate ->
                candidates += candidate
                when {
                    correctionMode &&
                        candidate.toolName == AssistantTools.RUN_COMMAND ->
                        AssistantAgentLoop.Decision.Correct(
                            "Do not run shell commands or edit the project directly. " +
                                "For this code-editing request, resolve_folder for llm-zoomcamp on host dev, " +
                                "start a codex session in the resolved cwd, then send the task prompt to that session.",
                        )
                    correctionMode &&
                        candidate.toolName == AssistantTools.START_SESSION &&
                        actions.invocations.none { it.name == AssistantTools.RESOLVE_FOLDER } ->
                        AssistantAgentLoop.Decision.Correct(
                            "Before starting a session, call resolve_folder for llm-zoomcamp on host dev. " +
                                "Then start codex in the resolved cwd and send the task prompt.",
                        )
                    correctionMode &&
                        !correctedPromptCandidate &&
                        candidate.toolName == AssistantTools.SEND_PROMPT_TO_SESSION -> {
                        correctedPromptCandidate = true
                        AssistantAgentLoop.Decision.Correct(
                            "Keep the llm-zoomcamp session, but send this exact corrected prompt instead: " +
                                LLM_ZOOMCAMP_CORRECTED_AGENT_PROMPT,
                        )
                    }
                    else -> AssistantAgentLoop.Decision.Confirm
                }
            },
        )

        assertTrue(
            "expected successful answer from ${provider.name}, got $outcome; " +
                debugState(provider.client, actions, candidates),
            outcome is AssistantAgentLoop.Outcome.Answer,
        )
        if (correctionMode) {
            assertTrue("correction flow did not reach a send_prompt_to_session candidate", correctedPromptCandidate)
        }

        val expectedPrompt = if (correctionMode) {
            LLM_ZOOMCAMP_CORRECTED_AGENT_PROMPT
        } else {
            LLM_ZOOMCAMP_AGENT_PROMPT
        }
        assertStructuredToolSequence(provider.client.toolCalls, expectedPrompt, correctionMode)
        assertExecutedActionSequence(actions, expectedPrompt, correctionMode)
        assertTrue("expected a send_prompt_to_session confirmation candidate", candidates.any {
            it.toolName == AssistantTools.SEND_PROMPT_TO_SESSION &&
                promptMatches(it.summary, expectedPrompt)
        })
    }

    private fun debugState(
        client: RecordingClient,
        actions: RecordingActions,
        candidates: List<AssistantAgentLoop.Candidate>,
    ): String =
        "toolCalls=${client.toolCalls.map { it.name }}; " +
            "actions=${actions.invocations.map { it.name }}; " +
            "candidates=${candidates.map { it.toolName to it.summary }}"

    private fun assertStructuredToolSequence(
        toolCalls: List<LlmToolCall>,
        expectedPrompt: String,
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
        val sendIndexes = names.withIndex()
            .filter { it.value == AssistantTools.SEND_PROMPT_TO_SESSION }
            .map { it.index }
        val sendIndex = sendIndexes.lastOrNull() ?: -1

        assertTrue("model did not call a project/folder lookup tool: $names", lookupIndex >= 0)
        assertTrue("model did not call ${AssistantTools.RESOLVE_FOLDER}: $names", resolveIndex >= 0)
        assertTrue("model did not call ${AssistantTools.START_SESSION}: $names", startIndexes.isNotEmpty())
        assertTrue("model did not call ${AssistantTools.SEND_PROMPT_TO_SESSION}: $names", sendIndex >= 0)
        assertTrue("project lookup must precede final start_session: $names", lookupIndex < startIndexes.last())
        assertTrue("resolve_folder must precede final start_session: $names", resolveIndex < startIndexes.last())
        assertTrue("send_prompt_to_session must follow final start_session: $names", startIndexes.last() < sendIndex)
        if (correctionMode) {
            assertTrue("correction should produce a revised send_prompt_to_session call: $names", sendIndexes.size >= 2)
        }

        val resolveArgs = JSONObject(toolCalls[resolveIndex].argumentsJson)
        assertEquals("dev", resolveArgs.optString("host"))
        val resolveQuery = resolveArgs.optString("query")
        assertTrue(
            "resolve_folder query should mention LLM Zoomcamp: $resolveQuery",
            listOf("llm", "ллм", "zoom", "зум").any { resolveQuery.contains(it, ignoreCase = true) },
        )

        val finalStartArgs = JSONObject(toolCalls[startIndexes.last()].argumentsJson)
        assertEquals("dev", finalStartArgs.optString("host"))
        assertEquals(LLM_ZOOMCAMP_PROJECT_PATH, finalStartArgs.optString("cwd"))
        assertEquals("codex", finalStartArgs.optString("agent"))

        val sendArgs = JSONObject(toolCalls[sendIndex].argumentsJson)
        assertEquals(LLM_ZOOMCAMP_SESSION_NAME, sendArgs.optString("session_name"))
        assertTrue(
            "raw send_prompt_to_session prompt should not be blank",
            sendArgs.optString("prompt").isNotBlank(),
        )
    }

    private fun assertExecutedActionSequence(
        actions: RecordingActions,
        expectedPrompt: String,
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
        assertTrue("executed actions are out of order: $names", resolveIndex < startIndexes.last())
        assertTrue("executed actions are out of order: $names", startIndexes.last() < sendIndex)
        if (correctionMode) {
            assertEquals("start_session should execute once before the corrected prompt is sent", 1, startIndexes.size)
        }

        assertEquals(
            ActionInvocation(
                AssistantTools.START_SESSION,
                mapOf("host" to "dev", "cwd" to LLM_ZOOMCAMP_PROJECT_PATH, "agent" to "codex"),
            ),
            actions.invocations[startIndexes.last()],
        )
        val send = actions.invocations[sendIndex]
        assertEquals(AssistantTools.SEND_PROMPT_TO_SESSION, send.name)
        assertEquals(LLM_ZOOMCAMP_SESSION_NAME, send.args["session_name"])
        assertPromptNormalized(send.args["prompt"].orEmpty(), expectedPrompt)
    }

    private fun assertPromptNormalized(prompt: String, expectedPrompt: String) {
        assertTrue(
            "send_prompt_to_session prompt should contain '$expectedPrompt': $prompt",
            promptMatches(prompt, expectedPrompt),
        )
        assertTrue(
            "send_prompt_to_session prompt should normalize the speech typo 'эможди': $prompt",
            !prompt.contains("эможди", ignoreCase = true),
        )
    }

    private fun promptMatches(actual: String, expected: String): Boolean =
        actual.normalizedPrompt().contains(expected.normalizedPrompt())

    private fun String.normalizedPrompt(): String =
        lowercase()
            .replace('ё', 'е')
            .replace("эмодзи", "эмоджи")
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
                available_project_names:
                - llm-zoomcamp
                - pocketshell
                - course-management-agent
                exact project paths: call resolve_folder with host=dev and the project query
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
                $LLM_ZOOMCAMP_PROJECT_PATH
                /home/dev/projects/pocketshell
                /home/dev/projects/course-management-agent
            """.trimIndent()
        }

        override suspend fun resolveFolder(host: String, query: String): FolderResolutionResult {
            invocations += ActionInvocation(AssistantTools.RESOLVE_FOLDER, mapOf("host" to host, "query" to query))
            return FolderResolutionResult.Resolved(
                FolderResolution.Confident(
                    FolderCandidate(path = LLM_ZOOMCAMP_PROJECT_PATH, label = LLM_ZOOMCAMP_SESSION_NAME),
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
            return ActionResult.ok(
                "Started $agent session \"$LLM_ZOOMCAMP_SESSION_NAME\" in $cwd on $host. " +
                    "Use session_name=$LLM_ZOOMCAMP_SESSION_NAME for send_prompt_to_session.",
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
    }

    private data class ActionInvocation(val name: String, val args: Map<String, String>)

    private data class ProviderCase(
        val name: String,
        val client: RecordingClient,
    )

    private fun openAiProviderOrSkip(): ProviderCase {
        assumeTrue(
            "set POCKETSHELL_REAL_LLM_TESTS=1 to run real LLM tests",
            System.getenv("POCKETSHELL_REAL_LLM_TESTS") == "1",
        )
        val env = repoDotenv()
        val key = env["OPENAI_API_KEY"]?.takeIf { it.isNotBlank() }
        assumeTrue(
            "OpenAI real LLM scenario skipped: missing OPENAI_API_KEY in repo root .env",
            key != null,
        )
        return ProviderCase(
            name = "OpenAI",
            client = RecordingClient(
                OpenAiLlmClient(
                    AssistantProviderConfig(
                        apiKey = key!!.toCharArray(),
                        baseUrl = env["OPENAI_BASE_URL"]?.takeIf { it.isNotBlank() }
                            ?: AssistantSettings.DEFAULT_OPENAI_BASE_URL,
                        model = env["OPENAI_MODEL"]?.takeIf { it.isNotBlank() }
                            ?: AssistantSettings.DEFAULT_OPENAI_MODEL,
                        maxTokens = env["POCKETSHELL_REAL_LLM_MAX_TOKENS"]?.toIntOrNull() ?: 2048,
                    ),
                ),
            ),
        )
    }

    private fun zaiProviderOrSkip(): ProviderCase {
        assumeTrue(
            "set POCKETSHELL_REAL_LLM_TESTS=1 to run real LLM tests",
            System.getenv("POCKETSHELL_REAL_LLM_TESTS") == "1",
        )
        val env = repoDotenv()
        val key = env["ZAI_API_KEY"]?.takeIf { it.isNotBlank() }
            ?: env["ANTHROPIC_API_KEY"]?.takeIf { it.isNotBlank() }
        val keySource = if (!env["ZAI_API_KEY"].isNullOrBlank()) "ZAI_API_KEY" else "ANTHROPIC_API_KEY"
        assumeTrue(
            "ZAI real LLM scenario skipped: missing ZAI_API_KEY or ANTHROPIC_API_KEY in repo root .env",
            key != null,
        )
        val baseUrl = when {
            !env["ZAI_BASE_URL"].isNullOrBlank() -> env.getValue("ZAI_BASE_URL")
            !env["ANTHROPIC_BASE_URL"].isNullOrBlank() -> env.getValue("ANTHROPIC_BASE_URL")
            else -> AssistantSettings.DEFAULT_ZAI_BASE_URL
        }
        val model = when {
            !env["ZAI_MODEL"].isNullOrBlank() -> env.getValue("ZAI_MODEL")
            !env["ANTHROPIC_MODEL"].isNullOrBlank() -> env.getValue("ANTHROPIC_MODEL")
            else -> AssistantSettings.DEFAULT_ZAI_MODEL
        }
        return ProviderCase(
            name = "ZAI ($keySource)",
            client = RecordingClient(
                AnthropicLlmClient(
                    AssistantProviderConfig(
                        apiKey = key!!.toCharArray(),
                        baseUrl = baseUrl,
                        model = model,
                        maxTokens = env["POCKETSHELL_REAL_LLM_MAX_TOKENS"]?.toIntOrNull() ?: 2048,
                    ),
                ),
            ),
        )
    }

    private fun repoDotenv(): Map<String, String> {
        val dotenv = projectRoot().resolve(".env")
        return if (Files.isRegularFile(dotenv)) parseDotenv(dotenv) else emptyMap()
    }

    private fun projectRoot(): Path {
        System.getProperty("pocketshell.realLlm.repoRoot")
            ?.takeIf { it.isNotBlank() }
            ?.let { Paths.get(it).toAbsolutePath().normalize() }
            ?.takeIf { Files.exists(it.resolve("settings.gradle.kts")) }
            ?.let { return it }

        var current = Paths.get("").toAbsolutePath()
        while (current.parent != null && !Files.exists(current.resolve("settings.gradle.kts"))) {
            current = current.parent
        }
        assumeTrue(
            "real LLM scenario skipped: could not identify PocketShell repo root for .env lookup",
            Files.exists(current.resolve("settings.gradle.kts")),
        )
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
        const val LLM_ZOOMCAMP_PROJECT_PATH = "/home/dev/projects/llm-zoomcamp"
        const val LLM_ZOOMCAMP_SESSION_NAME = "llm-zoomcamp"
        const val LLM_ZOOMCAMP_USER_REQUEST = "убери все эможди в ллм зумкампе"
        const val LLM_ZOOMCAMP_AGENT_PROMPT = "убери все эмоджи"
        const val LLM_ZOOMCAMP_CORRECTED_AGENT_PROMPT = "убери все эмоджи и оставь текст без изменений"
    }
}
