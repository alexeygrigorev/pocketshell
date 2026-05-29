package com.pocketshell.core.assistant

/**
 * Unified, provider-independent conversation message.
 *
 * One message carries exactly the slice of state that matters for the
 * agent loop, decoupled from how Anthropic or OpenAI represent it on the
 * wire:
 *
 *  - A [Role.System] / [Role.User] message carries plain [text].
 *  - A [Role.Assistant] message carries optional [text] plus zero or more
 *    [toolCalls] the model wants the host to run.
 *  - A [Role.Tool] message carries one or more [toolResults] feeding the
 *    output of previously-requested tool calls back to the model.
 *
 * The provider clients translate these onto the right wire shape:
 * Anthropic packs tool calls into `tool_use` content blocks and tool
 * results into `tool_result` blocks inside a `user` message; OpenAI uses
 * `tool_calls` on the assistant message and separate `role: "tool"`
 * messages keyed by `tool_call_id`.
 *
 * @property role who authored the message.
 * @property text optional plain-text content. `null` for a pure tool-call
 *   or tool-result turn.
 * @property toolCalls tool invocations the assistant requested. Only
 *   meaningful on a [Role.Assistant] message.
 * @property toolResults outputs being fed back. Only meaningful on a
 *   [Role.Tool] message.
 */
public data class LlmMessage(
    val role: Role,
    val text: String? = null,
    val toolCalls: List<LlmToolCall> = emptyList(),
    val toolResults: List<LlmToolResult> = emptyList(),
) {
    public enum class Role { System, User, Assistant, Tool }

    public companion object {
        /** Convenience constructor for a plain system prompt. */
        public fun system(text: String): LlmMessage =
            LlmMessage(role = Role.System, text = text)

        /** Convenience constructor for a plain user turn. */
        public fun user(text: String): LlmMessage =
            LlmMessage(role = Role.User, text = text)

        /** Convenience constructor for a plain assistant text turn. */
        public fun assistant(text: String): LlmMessage =
            LlmMessage(role = Role.Assistant, text = text)

        /** Convenience constructor carrying tool results back to the model. */
        public fun toolResults(results: List<LlmToolResult>): LlmMessage =
            LlmMessage(role = Role.Tool, toolResults = results)
    }
}

/**
 * A single tool invocation the model decided to make.
 *
 * @property id the provider-assigned call id. Anthropic puts this on the
 *   `tool_use` block (`id`); OpenAI on the `tool_calls[].id`. The agent
 *   loop must echo it back on the matching [LlmToolResult] so the provider
 *   can pair the result to the call.
 * @property name the tool name, matching a [ToolSpec.name].
 * @property argumentsJson the tool arguments as a raw JSON object string.
 *   Kept as a string (not a parsed map) so the host owns parsing/validation
 *   against the tool's schema — different tools want different argument
 *   types and a generic `Map<String, Any?>` loses fidelity (numbers vs
 *   strings, nested objects).
 */
public data class LlmToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

/**
 * The output of running an [LlmToolCall], fed back to the model on the
 * next turn.
 *
 * @property toolCallId the [LlmToolCall.id] this result answers.
 * @property content the tool's textual output (stdout, JSON, an error
 *   string — the host decides). Sent verbatim to the provider.
 * @property isError true if the tool failed; lets the provider's
 *   tool-result block flag the error so the model can react. Anthropic
 *   has a dedicated `is_error` flag; OpenAI has no separate flag, so the
 *   error is conveyed in [content] there.
 */
public data class LlmToolResult(
    val toolCallId: String,
    val content: String,
    val isError: Boolean = false,
)

/**
 * Declares a tool the model may call.
 *
 * @property name unique tool name (function name). Must match the value
 *   echoed back on [LlmToolCall.name].
 * @property description natural-language description of when to use the
 *   tool. The model reads this to decide whether/how to call it.
 * @property parametersJsonSchema a JSON-Schema object (as a raw JSON
 *   string) describing the tool's parameters. Sent to Anthropic as
 *   `input_schema` and to OpenAI as `parameters`. Kept as a string so the
 *   caller can author the schema directly without an intermediate model
 *   that both wire formats would have to round-trip through.
 */
public data class ToolSpec(
    val name: String,
    val description: String,
    val parametersJsonSchema: String,
)

/**
 * The unified result of one [AssistantLlmClient.complete] turn.
 *
 * @property text the assistant's text output this turn, or `null` when the
 *   turn produced only tool calls.
 * @property toolCalls tool invocations the model requested. Empty when the
 *   model answered with text only.
 * @property stopReason why the provider stopped generating, normalised
 *   across providers (see [StopReason]).
 */
public data class LlmResponse(
    val text: String?,
    val toolCalls: List<LlmToolCall> = emptyList(),
    val stopReason: StopReason,
) {
    /** True when the model asked the host to run one or more tools. */
    public val hasToolCalls: Boolean get() = toolCalls.isNotEmpty()
}

/**
 * Why generation stopped, normalised across providers.
 *
 * Anthropic reports `stop_reason` of `end_turn` / `tool_use` /
 * `max_tokens` / `stop_sequence`; OpenAI reports `finish_reason` of `stop`
 * / `tool_calls` / `length`. We collapse to the cases the agent loop
 * actually branches on; anything unrecognised maps to [Other].
 */
public enum class StopReason {
    /** Natural end of the assistant turn (Anthropic `end_turn`, OpenAI `stop`). */
    EndTurn,

    /** The model wants to call a tool (Anthropic `tool_use`, OpenAI `tool_calls`). */
    ToolUse,

    /** Output hit the token cap (Anthropic `max_tokens`, OpenAI `length`). */
    MaxTokens,

    /** Anything else / unrecognised. */
    Other,
}

/**
 * Errors from an [AssistantLlmClient] call. Callers branch on the subtype
 * rather than raw HTTP codes — same convention as
 * `core-voice`'s `WhisperException` / `CommandPlannerException`.
 */
public sealed class AssistantLlmException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    /** 401 / 403 — the provider rejected the API key. */
    public class Auth(message: String) : AssistantLlmException(message)

    /** 429 — rate limited; [retryAfterSeconds] from the `Retry-After` header when present. */
    public class RateLimited(message: String, public val retryAfterSeconds: Long? = null) :
        AssistantLlmException(message)

    /** 5xx — provider-side failure. */
    public class Server(message: String, public val statusCode: Int) : AssistantLlmException(message)

    /** DNS / connect / TLS / timeout / cancellation. */
    public class Transport(message: String, cause: Throwable? = null) :
        AssistantLlmException(message, cause)

    /** The response was not the shape the provider documents. */
    public class Parse(message: String, cause: Throwable? = null) :
        AssistantLlmException(message, cause)
}
