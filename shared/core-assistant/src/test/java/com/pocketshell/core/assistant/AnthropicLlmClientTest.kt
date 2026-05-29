package com.pocketshell.core.assistant

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Verifies [AnthropicLlmClient] builds Messages-API requests with
 * `tools` / `tool_choice`, parses `tool_use` blocks, feeds `tool_result`
 * back, and honours the injected base URL (Anthropic + ZAI/GLM both ride
 * this client).
 */
class AnthropicLlmClientTest {

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

    private fun clientFor(baseUrl: String, model: String = "claude-3-5-sonnet-latest") =
        AnthropicLlmClient(
            AssistantProviderConfig(
                apiKey = "anthropic-key".toCharArray(),
                baseUrl = baseUrl,
                model = model,
            ),
        )

    @Test
    fun request_includes_system_tools_and_tool_choice() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"content":[{"type":"text","text":"hi"}],"stop_reason":"end_turn"}""",
            ),
        )
        val client = clientFor(server.url("/v1").toString())

        client.complete(
            messages = listOf(
                LlmMessage.system("be terse"),
                LlmMessage.user("call the weather tool"),
            ),
            tools = listOf(
                ToolSpec(
                    name = "get_weather",
                    description = "Get the weather",
                    parametersJsonSchema = """{"type":"object","properties":{"city":{"type":"string"}}}""",
                ),
            ),
            toolChoice = ToolChoice.Required,
        )

        val recorded = server.takeRequest()
        assertEquals("/v1/messages", recorded.path)
        assertEquals("anthropic-key", recorded.getHeader("x-api-key"))
        assertEquals(AnthropicLlmClient.ANTHROPIC_VERSION, recorded.getHeader("anthropic-version"))

        val body = JSONObject(recorded.body.readUtf8())
        assertEquals("claude-3-5-sonnet-latest", body.getString("model"))
        assertEquals("be terse", body.getString("system"))
        // system role is lifted out of messages
        val messages = body.getJSONArray("messages")
        assertEquals(1, messages.length())
        assertEquals("user", messages.getJSONObject(0).getString("role"))
        // tools mapped to input_schema
        val tool = body.getJSONArray("tools").getJSONObject(0)
        assertEquals("get_weather", tool.getString("name"))
        assertTrue(tool.has("input_schema"))
        // tool_choice Required -> "any" for Anthropic
        assertEquals("any", body.getJSONObject("tool_choice").getString("type"))
    }

    @Test
    fun parses_tool_use_blocks_into_unified_tool_calls() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {"content":[
                  {"type":"text","text":"Let me check."},
                  {"type":"tool_use","id":"toolu_1","name":"get_weather","input":{"city":"Paris"}}
                ],"stop_reason":"tool_use"}
                """.trimIndent(),
            ),
        )
        val client = clientFor(server.url("/v1").toString())

        val result = client.complete(
            messages = listOf(LlmMessage.user("weather in Paris?")),
        )

        val response = result.getOrThrow()
        assertEquals("Let me check.", response.text)
        assertEquals(StopReason.ToolUse, response.stopReason)
        assertTrue(response.hasToolCalls)
        val call = response.toolCalls.single()
        assertEquals("toolu_1", call.id)
        assertEquals("get_weather", call.name)
        assertEquals("Paris", JSONObject(call.argumentsJson).getString("city"))
    }

    @Test
    fun feeds_tool_result_back_as_user_tool_result_block() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"content":[{"type":"text","text":"It is sunny."}],"stop_reason":"end_turn"}""",
            ),
        )
        val client = clientFor(server.url("/v1").toString())

        client.complete(
            messages = listOf(
                LlmMessage.user("weather in Paris?"),
                LlmMessage(
                    role = LlmMessage.Role.Assistant,
                    toolCalls = listOf(
                        LlmToolCall("toolu_1", "get_weather", """{"city":"Paris"}"""),
                    ),
                ),
                LlmMessage.toolResults(
                    listOf(LlmToolResult("toolu_1", "sunny, 21C", isError = false)),
                ),
            ),
        )

        val body = JSONObject(server.takeRequest().body.readUtf8())
        val messages = body.getJSONArray("messages")
        // user, assistant(tool_use), user(tool_result)
        assertEquals(3, messages.length())
        val assistant = messages.getJSONObject(1)
        assertEquals("assistant", assistant.getString("role"))
        assertEquals("tool_use", assistant.getJSONArray("content").getJSONObject(0).getString("type"))
        val toolResultMsg = messages.getJSONObject(2)
        assertEquals("user", toolResultMsg.getString("role"))
        val resultBlock = toolResultMsg.getJSONArray("content").getJSONObject(0)
        assertEquals("tool_result", resultBlock.getString("type"))
        assertEquals("toolu_1", resultBlock.getString("tool_use_id"))
        assertEquals("sunny, 21C", resultBlock.getString("content"))
        assertFalse(resultBlock.getBoolean("is_error"))
    }

    @Test
    fun base_url_injection_targets_zai_glm_host() = runBlocking {
        // Same client impl, ZAI/GLM-style base URL pointed at the mock host.
        server.enqueue(
            MockResponse().setBody(
                """{"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn"}""",
            ),
        )
        val client = clientFor(server.url("/api/anthropic").toString(), model = "glm-4.6")

        client.complete(messages = listOf(LlmMessage.user("hello")))

        val recorded = server.takeRequest()
        assertEquals("/api/anthropic/messages", recorded.path)
        assertEquals("glm-4.6", JSONObject(recorded.body.readUtf8()).getString("model"))
    }

    @Test
    fun auth_failure_maps_to_auth_exception() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"bad key"}"""))
        val client = clientFor(server.url("/v1").toString())

        val result = client.complete(messages = listOf(LlmMessage.user("hi")))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is AssistantLlmException.Auth)
    }

    @Test
    fun text_only_response_has_null_tool_calls() {
        val response = parseAnthropicResponse(
            """{"content":[{"type":"text","text":"plain"}],"stop_reason":"end_turn"}""",
        ).getOrThrow()
        assertEquals("plain", response.text)
        assertFalse(response.hasToolCalls)
        assertEquals(StopReason.EndTurn, response.stopReason)
    }

    @Test
    fun tool_use_only_response_has_null_text() {
        val response = parseAnthropicResponse(
            """{"content":[{"type":"tool_use","id":"t1","name":"f","input":{}}],"stop_reason":"tool_use"}""",
        ).getOrThrow()
        assertNull(response.text)
        assertTrue(response.hasToolCalls)
    }
}
