package com.pocketshell.core.voice

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class CommandPlannerTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun requestBuilderIncludesTranscriptMetadataAndSafetyConstraints() {
        val request = sampleRequest()

        val json = buildOpenAiCommandPlannerRequest(request, model = "test-model")

        assertEquals("test-model", json.getString("model"))
        assertEquals("json_object", json.getJSONObject("response_format").getString("type"))
        val messages = json.getJSONArray("messages")
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        val userContent = JSONObject(messages.getJSONObject(1).getString("content"))
        assertEquals("show git status", userContent.getString("transcript"))
        val session = userContent.getJSONObject("session")
        assertEquals("devbox", session.getString("host_label"))
        assertEquals("alexey", session.getString("username"))
        assertEquals("/work/pocketshell", session.getString("current_directory"))
        assertEquals("/work/pocketshell", session.getJSONArray("project_roots").getString(0))
        assertEquals("zsh", session.getString("shell_type"))
        val safety = userContent.getJSONObject("safety_constraints")
        assertTrue(safety.getBoolean("require_review_before_execution"))
        assertFalse(safety.getBoolean("allow_auto_send"))
        assertEquals(2, safety.getInt("max_commands"))
        assertEquals("sudo", safety.getJSONArray("forbidden_patterns").getString(0))
    }

    @Test
    fun parserAcceptsValidCommandList() {
        val result = parseCommandPlannerContent(
            """{"commands":[{"command":"git status --short","rationale":"check changes"}]}""",
        )

        assertTrue(result.isSuccess)
        val plan = result.getOrThrow()
        assertEquals(listOf("git status --short"), plan.commands.map { it.command })
        assertEquals("check changes", plan.commands[0].rationale)
        assertEquals("git status --short\n", plan.asTerminalPayload(sendWithEnter = true))
    }

    @Test
    fun parserReturnsRejectedForModelErrorObject() {
        val result = parseCommandPlannerContent("""{"error":{"message":"need more context"}}""")

        val ex = result.exceptionOrNull()
        assertTrue(ex is CommandPlannerException.Rejected)
        assertTrue(ex!!.message!!.contains("need more context"))
    }

    @Test
    fun parserRejectsMalformedJson() {
        val result = parseCommandPlannerContent("""{"commands": [""")

        assertTrue(result.exceptionOrNull() is CommandPlannerException.Parse)
    }

    @Test
    fun parserRejectsUnsafeCommand() {
        val result = parseCommandPlannerContent(
            """{"commands":[{"command":"rm -rf /tmp/build"}]}""",
        )

        val ex = result.exceptionOrNull()
        assertTrue(ex is CommandPlannerException.Rejected)
        assertTrue(ex!!.message!!.contains("unsafe"))
    }

    @Test
    fun parserRejectsInvalidConfiguredUnsafePatternWithoutThrowing() {
        val result = parseCommandPlannerContent(
            """{"commands":[{"command":"git status"}]}""",
            safety = CommandPlannerSafetyConstraints(forbiddenPatterns = listOf("[")),
        )

        val ex = result.exceptionOrNull()
        assertTrue(ex is CommandPlannerException.Rejected)
        assertTrue(ex!!.message!!.contains("invalid forbidden pattern"))
    }

    @Test
    fun parserRejectsCommandWithNewline() {
        val result = parseCommandPlannerContent(
            JSONObject()
                .put(
                    "commands",
                    org.json.JSONArray()
                        .put(JSONObject().put("command", "git status\nwhoami")),
                )
                .toString(),
        )

        val ex = result.exceptionOrNull()
        assertTrue(ex is CommandPlannerException.Rejected)
        assertTrue(ex!!.message!!.contains("control"))
    }

    @Test
    fun parserRejectsTooManyCommands() {
        val result = parseCommandPlannerContent(
            """{"commands":[{"command":"pwd"},{"command":"ls"}]}""",
            safety = CommandPlannerSafetyConstraints(maxCommands = 1),
        )

        val ex = result.exceptionOrNull()
        assertTrue(ex is CommandPlannerException.Rejected)
        assertTrue(ex!!.message!!.contains("maximum"))
    }

    @Test
    fun clientUsesFakeEndpointAndParsesCommandPlan() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    JSONObject()
                        .put(
                            "choices",
                            org.json.JSONArray()
                                .put(
                                    JSONObject()
                                        .put(
                                            "message",
                                            JSONObject()
                                                .put(
                                                    "content",
                                                    """{"commands":[{"command":"git status --short"}]}""",
                                                ),
                                        ),
                                ),
                        )
                        .toString(),
                ),
        )
        val client = newClient()

        val result = client.plan(sampleRequest())

        assertTrue(result.isSuccess)
        assertEquals("git status --short", result.getOrThrow().commands.single().command)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/chat/completions", recorded.path)
        assertEquals("Bearer sk-test", recorded.getHeader("Authorization"))
        val rawBody = recorded.body.readUtf8()
        val body = JSONObject(rawBody)
        assertEquals("planner-test-model", body.getString("model"))
        val userContent = JSONObject(body.getJSONArray("messages").getJSONObject(1).getString("content"))
        assertEquals("show git status", userContent.getString("transcript"))
        assertFalse(rawBody.contains("sk-test"))
    }

    @Test
    fun clientMapsMalformedModelContentToParseError() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"choices":[{"message":{"content":"not-json"}}]}"""),
        )
        val client = newClient()

        val result = client.plan(sampleRequest())

        assertTrue(result.exceptionOrNull() is CommandPlannerException.Parse)
    }

    @Test
    fun clientMapsHttp429ToRateLimited() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", "7")
                .setBody("""{"error":{"message":"slow down"}}"""),
        )
        val client = newClient()

        val result = client.plan(sampleRequest())

        val ex = result.exceptionOrNull()
        assertTrue(ex is CommandPlannerException.RateLimited)
        assertEquals(7L, (ex as CommandPlannerException.RateLimited).retryAfterSeconds)
    }

    private fun newClient(): OkHttpOpenAiCommandPlannerClient {
        val http = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .writeTimeout(2, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .build()
        return OkHttpOpenAiCommandPlannerClient(
            config = CommandPlannerConfig(
                apiKey = "sk-test".toCharArray(),
                model = "planner-test-model",
                baseUrl = server.url("/v1").toString(),
            ),
            client = http,
        )
    }

    private fun sampleRequest(): CommandPlannerRequest =
        CommandPlannerRequest(
            transcript = "show git status",
            session = CommandPlannerSessionMetadata(
                hostLabel = "devbox",
                username = "alexey",
                currentDirectory = "/work/pocketshell",
                projectRoots = listOf("/work/pocketshell"),
                shellType = "zsh",
            ),
            safety = CommandPlannerSafetyConstraints(
                maxCommands = 2,
                forbiddenPatterns = listOf("sudo"),
            ),
        )
}
