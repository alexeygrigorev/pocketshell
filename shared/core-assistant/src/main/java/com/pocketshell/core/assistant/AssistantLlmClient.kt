package com.pocketshell.core.assistant

/**
 * Provider-agnostic single-shot chat-completion client for the in-app
 * action assistant (#266 builds the agent loop + tools on top of this).
 *
 * The whole point of this interface is that the **caller never sees a
 * provider's wire format**. Anthropic encodes tool calls as `tool_use`
 * content blocks and tool results as `tool_result` blocks; OpenAI encodes
 * the same concepts as `tool_calls` on an assistant message and `role:
 * "tool"` messages. Both collapse into the unified shapes in this package
 * ([LlmMessage], [ToolSpec], [LlmResponse], [LlmToolCall]) so the agent
 * loop can be written once and run against either provider.
 *
 * Single-shot only for v1: there is no streaming surface. The agent loop
 * drives multi-turn behaviour by calling [complete] repeatedly with the
 * growing message list — this client just maps one request to one
 * response.
 */
public interface AssistantLlmClient {

    /**
     * Run one completion turn.
     *
     * @param messages the full conversation so far, oldest first. Tool
     *   results from a previous turn are carried as [LlmMessage]s with
     *   [LlmMessage.Role.Tool] (see [LlmMessage.toolResults]); the client
     *   maps them onto whatever shape the provider expects.
     * @param tools the tools the model is allowed to call this turn. Empty
     *   means "plain completion, no tools offered".
     * @param toolChoice optional constraint on tool usage. `null` lets the
     *   provider decide (its default — usually "auto").
     * @return [Result.success] with an [LlmResponse], or [Result.failure]
     *   carrying an [AssistantLlmException] subtype. Callers branch on the
     *   exception class, not on raw HTTP codes.
     */
    public suspend fun complete(
        messages: List<LlmMessage>,
        tools: List<ToolSpec> = emptyList(),
        toolChoice: ToolChoice? = null,
    ): Result<LlmResponse>
}

/**
 * How the model is allowed to use the supplied [ToolSpec]s this turn.
 *
 * Maps to Anthropic's `tool_choice` object and OpenAI's `tool_choice`
 * field. Kept as a small sealed hierarchy rather than a string so callers
 * can't pass a value one provider understands and the other doesn't.
 */
public sealed interface ToolChoice {
    /** Provider decides whether to call a tool (the usual default). */
    public data object Auto : ToolChoice

    /** Model must not call any tool — return text only. */
    public data object None : ToolChoice

    /** Model must call at least one of the supplied tools. */
    public data object Required : ToolChoice

    /** Model must call exactly the named tool. */
    public data class Specific(val toolName: String) : ToolChoice
}
