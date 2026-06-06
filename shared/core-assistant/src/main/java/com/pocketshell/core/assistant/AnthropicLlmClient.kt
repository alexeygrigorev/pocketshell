package com.pocketshell.core.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * [AssistantLlmClient] backed by the Anthropic Messages API
 * (`POST <baseUrl>/messages`).
 *
 * The same wire implementation serves both real Anthropic and ZAI. ZAI is
 * product-configured as its own provider, then routed here because its API
 * exposes the Anthropic-compatible Messages protocol at
 * `https://api.z.ai/api/anthropic` with `glm-*` models.
 *
 * Wire mapping:
 *  - System messages → top-level `system` string (Anthropic does not accept
 *    a `system` role inside `messages`).
 *  - User / assistant text → `messages[]` with `content` blocks.
 *  - Assistant tool calls → `tool_use` content blocks.
 *  - Tool results → a `user` message whose content is `tool_result` blocks
 *    keyed by `tool_use_id`.
 *  - [ToolSpec] → `tools[]` with `input_schema`.
 *  - [ToolChoice] → `tool_choice` object.
 *
 * Single-shot: no streaming (`stream` defaults to false).
 *
 * @param config provider config: API key, base URL, model. The key is
 *   defensively copied on construction so the caller can zero its own
 *   buffer; the [String] header value lives only for one call.
 * @param client optional OkHttp client; the default mirrors core-voice's
 *   timeouts (15s connect, 60s call) since a tool-using turn can take a
 *   while.
 */
public class AnthropicLlmClient(
    config: AssistantProviderConfig,
    private val client: OkHttpClient = defaultHttpClient(),
) : AssistantLlmClient {

    private val apiKey: CharArray = config.apiKey.copyOf()
    private val baseUrl: String = config.baseUrl
    private val model: String = config.model
    private val maxTokens: Int = config.maxTokens

    override suspend fun complete(
        messages: List<LlmMessage>,
        tools: List<ToolSpec>,
        toolChoice: ToolChoice?,
    ): Result<LlmResponse> = withContext(Dispatchers.IO) {
        val payload = buildAnthropicRequest(messages, tools, toolChoice, model, maxTokens)
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)

        // Build the key header value in a local StringBuilder so the
        // plaintext key only materialises for the duration of this call.
        val keyHeader = StringBuilder().append(apiKey).toString()
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/messages")
            .addHeader("x-api-key", keyHeader)
            .addHeader("anthropic-version", ANTHROPIC_VERSION)
            .post(payload)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        classifyHttpFailure(response.code, response.header("Retry-After"), body),
                    )
                }
                parseAnthropicResponse(body)
            }
        } catch (io: IOException) {
            Result.failure(AssistantLlmException.Transport(io.message ?: "Transport failure", io))
        }
    }

    public companion object {
        public const val ANTHROPIC_VERSION: String = "2023-06-01"

        public fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * Build the JSON body for the Anthropic Messages API. Exposed at file
 * scope (and `public`) so a unit test can assert the request shape
 * directly without a mock server.
 */
public fun buildAnthropicRequest(
    messages: List<LlmMessage>,
    tools: List<ToolSpec>,
    toolChoice: ToolChoice?,
    model: String,
    maxTokens: Int,
): JSONObject {
    val root = JSONObject()
        .put("model", model)
        .put("max_tokens", maxTokens)

    // Anthropic carries the system prompt as a top-level field, not a
    // message role. Concatenate every System message (newline-joined) so
    // callers can split a long prompt across messages if they like.
    val systemText = messages
        .filter { it.role == LlmMessage.Role.System }
        .mapNotNull { it.text }
        .joinToString("\n")
    if (systemText.isNotBlank()) {
        root.put("system", systemText)
    }

    val wireMessages = JSONArray()
    for (message in messages) {
        when (message.role) {
            LlmMessage.Role.System -> Unit // handled above
            LlmMessage.Role.User -> {
                wireMessages.put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", textContentBlocks(message.text)),
                )
            }
            LlmMessage.Role.Assistant -> {
                val content = JSONArray()
                if (!message.text.isNullOrEmpty()) {
                    content.put(JSONObject().put("type", "text").put("text", message.text))
                }
                for (call in message.toolCalls) {
                    content.put(
                        JSONObject()
                            .put("type", "tool_use")
                            .put("id", call.id)
                            .put("name", call.name)
                            .put("input", parseArgumentsObject(call.argumentsJson)),
                    )
                }
                wireMessages.put(JSONObject().put("role", "assistant").put("content", content))
            }
            LlmMessage.Role.Tool -> {
                // Anthropic feeds tool results back inside a `user` message.
                val content = JSONArray()
                for (result in message.toolResults) {
                    content.put(
                        JSONObject()
                            .put("type", "tool_result")
                            .put("tool_use_id", result.toolCallId)
                            .put("content", result.content)
                            .put("is_error", result.isError),
                    )
                }
                wireMessages.put(JSONObject().put("role", "user").put("content", content))
            }
        }
    }
    root.put("messages", wireMessages)

    if (tools.isNotEmpty()) {
        val toolsArray = JSONArray()
        for (tool in tools) {
            toolsArray.put(
                JSONObject()
                    .put("name", tool.name)
                    .put("description", tool.description)
                    .put("input_schema", JSONObject(tool.parametersJsonSchema)),
            )
        }
        root.put("tools", toolsArray)
    }

    when (toolChoice) {
        null -> Unit
        ToolChoice.Auto -> root.put("tool_choice", JSONObject().put("type", "auto"))
        ToolChoice.None -> root.put("tool_choice", JSONObject().put("type", "none"))
        ToolChoice.Required -> root.put("tool_choice", JSONObject().put("type", "any"))
        is ToolChoice.Specific -> root.put(
            "tool_choice",
            JSONObject().put("type", "tool").put("name", toolChoice.toolName),
        )
    }

    return root
}

/**
 * Parse a successful Anthropic Messages response into the unified
 * [LlmResponse]. Exposed at file scope for direct unit testing.
 */
public fun parseAnthropicResponse(body: String): Result<LlmResponse> {
    val root = try {
        JSONObject(body)
    } catch (json: JSONException) {
        return Result.failure(AssistantLlmException.Parse("Anthropic returned malformed JSON", json))
    }

    val contentArray = root.optJSONArray("content")
        ?: return Result.failure(
            AssistantLlmException.Parse("Anthropic response did not contain a content array"),
        )

    val textParts = StringBuilder()
    val toolCalls = mutableListOf<LlmToolCall>()
    for (i in 0 until contentArray.length()) {
        val block = contentArray.optJSONObject(i) ?: continue
        when (block.optString("type")) {
            "text" -> {
                val t = block.optString("text")
                if (t.isNotEmpty()) {
                    if (textParts.isNotEmpty()) textParts.append('\n')
                    textParts.append(t)
                }
            }
            "tool_use" -> {
                val input = block.optJSONObject("input") ?: JSONObject()
                toolCalls += LlmToolCall(
                    id = block.optString("id"),
                    name = block.optString("name"),
                    argumentsJson = input.toString(),
                )
            }
            else -> Unit
        }
    }

    val stopReason = when (root.optString("stop_reason")) {
        "end_turn", "stop_sequence" -> StopReason.EndTurn
        "tool_use" -> StopReason.ToolUse
        "max_tokens" -> StopReason.MaxTokens
        else -> StopReason.Other
    }

    return Result.success(
        LlmResponse(
            text = textParts.toString().ifEmpty { null },
            toolCalls = toolCalls,
            stopReason = stopReason,
        ),
    )
}

private fun textContentBlocks(text: String?): JSONArray =
    JSONArray().put(
        JSONObject().put("type", "text").put("text", text.orEmpty()),
    )

/**
 * Parse a tool-call arguments JSON string into a JSON object for the
 * `input` field. An empty / blank / malformed string degrades to `{}` so
 * a model that emitted no arguments still produces a valid request.
 */
private fun parseArgumentsObject(argumentsJson: String): JSONObject = try {
    if (argumentsJson.isBlank()) JSONObject() else JSONObject(argumentsJson)
} catch (_: JSONException) {
    JSONObject()
}

private fun classifyHttpFailure(
    code: Int,
    retryAfter: String?,
    body: String,
): AssistantLlmException = when (code) {
    401, 403 -> AssistantLlmException.Auth("Anthropic rejected credentials (HTTP $code): ${body.take(200)}")
    429 -> AssistantLlmException.RateLimited(
        message = "Anthropic rate limit hit (HTTP 429): ${body.take(200)}",
        retryAfterSeconds = retryAfter?.toLongOrNull(),
    )
    in 500..599 -> AssistantLlmException.Server(
        message = "Anthropic server error (HTTP $code): ${body.take(200)}",
        statusCode = code,
    )
    else -> AssistantLlmException.Transport("Anthropic returned unexpected HTTP $code: ${body.take(200)}")
}

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
