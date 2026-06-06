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
 * [AssistantLlmClient] backed by the OpenAI Chat Completions API
 * (`POST <baseUrl>/chat/completions`) with function/tool calling.
 *
 * Base URL is injectable so the same impl can target OpenAI proper or any
 * OpenAI-compatible gateway. Anthropic and ZAI go through
 * [AnthropicLlmClient] instead because they use the Messages wire format
 * (decision D25).
 *
 * Wire mapping:
 *  - System / user / assistant text → `messages[]` with matching `role`.
 *  - Assistant tool calls → `tool_calls[]` on the assistant message.
 *  - Tool results → `role: "tool"` messages keyed by `tool_call_id`.
 *  - [ToolSpec] → `tools[]` of `type: "function"`.
 *  - [ToolChoice] → `tool_choice`.
 *
 * Single-shot: no streaming (`stream` omitted, defaults false).
 *
 * @param config provider config: API key, base URL, model. Key defensively
 *   copied on construction.
 * @param client optional OkHttp client; default mirrors core-voice timeouts.
 */
public class OpenAiLlmClient(
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
        val payload = buildOpenAiRequest(messages, tools, toolChoice, model, maxTokens)
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)

        val authHeader = StringBuilder("Bearer ").append(apiKey).toString()
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/chat/completions")
            .addHeader("Authorization", authHeader)
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
                parseOpenAiResponse(body)
            }
        } catch (io: IOException) {
            Result.failure(AssistantLlmException.Transport(io.message ?: "Transport failure", io))
        }
    }

    public companion object {
        public fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * Build the JSON body for the OpenAI Chat Completions API. Exposed at file
 * scope for direct request-shape unit testing.
 */
public fun buildOpenAiRequest(
    messages: List<LlmMessage>,
    tools: List<ToolSpec>,
    toolChoice: ToolChoice?,
    model: String,
    maxTokens: Int,
): JSONObject {
    val root = JSONObject()
        .put("model", model)
        .put("max_tokens", maxTokens)

    val wireMessages = JSONArray()
    for (message in messages) {
        when (message.role) {
            LlmMessage.Role.System -> wireMessages.put(
                JSONObject().put("role", "system").put("content", message.text.orEmpty()),
            )
            LlmMessage.Role.User -> wireMessages.put(
                JSONObject().put("role", "user").put("content", message.text.orEmpty()),
            )
            LlmMessage.Role.Assistant -> {
                val obj = JSONObject().put("role", "assistant")
                // OpenAI requires `content` present even when only tool calls
                // are made; null is the documented value for a pure-tool turn.
                obj.put("content", message.text ?: JSONObject.NULL)
                if (message.toolCalls.isNotEmpty()) {
                    val calls = JSONArray()
                    for (call in message.toolCalls) {
                        calls.put(
                            JSONObject()
                                .put("id", call.id)
                                .put("type", "function")
                                .put(
                                    "function",
                                    JSONObject()
                                        .put("name", call.name)
                                        .put("arguments", call.argumentsJson.ifBlank { "{}" }),
                                ),
                        )
                    }
                    obj.put("tool_calls", calls)
                }
                wireMessages.put(obj)
            }
            LlmMessage.Role.Tool -> {
                // OpenAI emits one `role: "tool"` message per result.
                for (result in message.toolResults) {
                    wireMessages.put(
                        JSONObject()
                            .put("role", "tool")
                            .put("tool_call_id", result.toolCallId)
                            .put("content", result.content),
                    )
                }
            }
        }
    }
    root.put("messages", wireMessages)

    if (tools.isNotEmpty()) {
        val toolsArray = JSONArray()
        for (tool in tools) {
            toolsArray.put(
                JSONObject()
                    .put("type", "function")
                    .put(
                        "function",
                        JSONObject()
                            .put("name", tool.name)
                            .put("description", tool.description)
                            .put("parameters", JSONObject(tool.parametersJsonSchema)),
                    ),
            )
        }
        root.put("tools", toolsArray)
    }

    when (toolChoice) {
        null -> Unit
        ToolChoice.Auto -> root.put("tool_choice", "auto")
        ToolChoice.None -> root.put("tool_choice", "none")
        ToolChoice.Required -> root.put("tool_choice", "required")
        is ToolChoice.Specific -> root.put(
            "tool_choice",
            JSONObject()
                .put("type", "function")
                .put("function", JSONObject().put("name", toolChoice.toolName)),
        )
    }

    return root
}

/**
 * Parse a successful OpenAI Chat Completions response into the unified
 * [LlmResponse]. Exposed at file scope for direct unit testing.
 */
public fun parseOpenAiResponse(body: String): Result<LlmResponse> {
    val root = try {
        JSONObject(body)
    } catch (json: JSONException) {
        return Result.failure(AssistantLlmException.Parse("OpenAI returned malformed JSON", json))
    }

    val choice = root.optJSONArray("choices")?.optJSONObject(0)
        ?: return Result.failure(AssistantLlmException.Parse("OpenAI response did not contain choices"))
    val messageObj = choice.optJSONObject("message")
        ?: return Result.failure(AssistantLlmException.Parse("OpenAI choice did not contain a message"))

    val text = messageObj.optString("content").takeIf { it.isNotEmpty() }

    val toolCalls = mutableListOf<LlmToolCall>()
    val callsArray = messageObj.optJSONArray("tool_calls")
    if (callsArray != null) {
        for (i in 0 until callsArray.length()) {
            val call = callsArray.optJSONObject(i) ?: continue
            val function = call.optJSONObject("function") ?: continue
            toolCalls += LlmToolCall(
                id = call.optString("id"),
                name = function.optString("name"),
                argumentsJson = function.optString("arguments").ifBlank { "{}" },
            )
        }
    }

    val stopReason = when (choice.optString("finish_reason")) {
        "stop" -> StopReason.EndTurn
        "tool_calls", "function_call" -> StopReason.ToolUse
        "length" -> StopReason.MaxTokens
        else -> StopReason.Other
    }

    return Result.success(
        LlmResponse(text = text, toolCalls = toolCalls, stopReason = stopReason),
    )
}

private fun classifyHttpFailure(
    code: Int,
    retryAfter: String?,
    body: String,
): AssistantLlmException = when (code) {
    401, 403 -> AssistantLlmException.Auth("OpenAI rejected credentials (HTTP $code): ${body.take(200)}")
    429 -> AssistantLlmException.RateLimited(
        message = "OpenAI rate limit hit (HTTP 429): ${body.take(200)}",
        retryAfterSeconds = retryAfter?.toLongOrNull(),
    )
    in 500..599 -> AssistantLlmException.Server(
        message = "OpenAI server error (HTTP $code): ${body.take(200)}",
        statusCode = code,
    )
    else -> AssistantLlmException.Transport("OpenAI returned unexpected HTTP $code: ${body.take(200)}")
}

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
