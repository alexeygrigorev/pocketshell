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
 * Verifies [OpenAiLlmClient] builds chat-completions tool-call requests,
 * parses `tool_calls`, feeds `role: "tool"` results back, and honours the
 * injected base URL.
 */
class OpenAiLlmClientTest {

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

    private fun clientFor(baseUrl: String, model: String = "gpt-4o") =
        OpenAiLlmClient(
            AssistantProviderConfig(
                apiKey = "openai-key".toCharArray(),
                baseUrl = baseUrl,
                model = model,
            ),
        )

    @Test
    fun request_includes_tools_and_tool_choice() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"content":"hi"},"finish_reason":"stop"}]}""",
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
        assertEquals("/v1/chat/completions", recorded.path)
        assertEquals("Bearer openai-key", recorded.getHeader("Authorization"))

        val body = JSONObject(recorded.body.readUtf8())
        assertEquals("gpt-4o", body.getString("model"))
        val messages = body.getJSONArray("messages")
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
        val tool = body.getJSONArray("tools").getJSONObject(0)
        assertEquals("function", tool.getString("type"))
        assertEquals("get_weather", tool.getJSONObject("function").getString("name"))
        assertTrue(tool.getJSONObject("function").has("parameters"))
        assertEquals("required", body.getString("tool_choice"))
    }

    @Test
    fun parses_tool_calls_into_unified_tool_calls() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {"choices":[{"message":{"content":null,"tool_calls":[
                  {"id":"call_1","type":"function","function":{"name":"get_weather","arguments":"{\"city\":\"Paris\"}"}}
                ]},"finish_reason":"tool_calls"}]}
                """.trimIndent(),
            ),
        )
        val client = clientFor(server.url("/v1").toString())

        val response = client.complete(messages = listOf(LlmMessage.user("weather?"))).getOrThrow()

        assertNull(response.text)
        assertEquals(StopReason.ToolUse, response.stopReason)
        val call = response.toolCalls.single()
        assertEquals("call_1", call.id)
        assertEquals("get_weather", call.name)
        assertEquals("Paris", JSONObject(call.argumentsJson).getString("city"))
    }

    @Test
    fun feeds_tool_result_back_as_tool_role_message() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"content":"sunny"},"finish_reason":"stop"}]}""",
            ),
        )
        val client = clientFor(server.url("/v1").toString())

        client.complete(
            messages = listOf(
                LlmMessage.user("weather?"),
                LlmMessage(
                    role = LlmMessage.Role.Assistant,
                    toolCalls = listOf(LlmToolCall("call_1", "get_weather", """{"city":"Paris"}""")),
                ),
                LlmMessage.toolResults(listOf(LlmToolResult("call_1", "sunny, 21C"))),
            ),
        )

        val body = JSONObject(server.takeRequest().body.readUtf8())
        val messages = body.getJSONArray("messages")
        assertEquals(3, messages.length())
        val assistant = messages.getJSONObject(1)
        assertEquals("assistant", assistant.getString("role"))
        val toolCall = assistant.getJSONArray("tool_calls").getJSONObject(0)
        assertEquals("call_1", toolCall.getString("id"))
        assertEquals("get_weather", toolCall.getJSONObject("function").getString("name"))
        val toolMsg = messages.getJSONObject(2)
        assertEquals("tool", toolMsg.getString("role"))
        assertEquals("call_1", toolMsg.getString("tool_call_id"))
        assertEquals("sunny, 21C", toolMsg.getString("content"))
    }

    @Test
    fun base_url_injection_targets_custom_gateway() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}]}""",
            ),
        )
        val client = clientFor(server.url("/gateway/openai").toString(), model = "gpt-4o-mini")

        client.complete(messages = listOf(LlmMessage.user("hi")))

        val recorded = server.takeRequest()
        assertEquals("/gateway/openai/chat/completions", recorded.path)
        assertEquals("gpt-4o-mini", JSONObject(recorded.body.readUtf8()).getString("model"))
    }

    @Test
    fun rate_limit_maps_to_rate_limited_with_retry_after() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(429).addHeader("Retry-After", "30").setBody("slow down"),
        )
        val client = clientFor(server.url("/v1").toString())

        val result = client.complete(messages = listOf(LlmMessage.user("hi")))

        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue(ex is AssistantLlmException.RateLimited)
        assertEquals(30L, (ex as AssistantLlmException.RateLimited).retryAfterSeconds)
    }

    @Test
    fun assistant_tool_only_turn_sends_null_content() {
        val body = buildOpenAiRequest(
            messages = listOf(
                LlmMessage(
                    role = LlmMessage.Role.Assistant,
                    toolCalls = listOf(LlmToolCall("call_1", "f", "{}")),
                ),
            ),
            tools = emptyList(),
            toolChoice = null,
            model = "gpt-4o",
            maxTokens = 256,
        )
        val assistant = body.getJSONArray("messages").getJSONObject(0)
        assertTrue(assistant.isNull("content"))
        assertFalse(body.has("tool_choice"))
    }
}
