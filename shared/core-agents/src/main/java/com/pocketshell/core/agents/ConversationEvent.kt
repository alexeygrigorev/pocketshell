package com.pocketshell.core.agents

public enum class AgentKind(public val displayName: String) {
    ClaudeCode("Claude Code"),
    Codex("Codex"),
    OpenCode("OpenCode"),
}

public enum class ConversationRole {
    User,
    Assistant,
}

public sealed interface ConversationEvent {
    public val id: String
    public val agent: AgentKind
    public val atMillis: Long?

    public data class Message(
        override val id: String,
        override val agent: AgentKind,
        override val atMillis: Long? = null,
        val role: ConversationRole,
        val text: String,
        val streaming: Boolean = false,
    ) : ConversationEvent

    public data class ToolCall(
        override val id: String,
        override val agent: AgentKind,
        override val atMillis: Long? = null,
        val name: String,
        val input: String,
    ) : ConversationEvent

    public data class ToolResult(
        override val id: String,
        override val agent: AgentKind,
        override val atMillis: Long? = null,
        val toolCallId: String? = null,
        val output: String,
        val isError: Boolean = false,
    ) : ConversationEvent
}

public interface ConversationParser {
    public fun parseLine(line: String): List<ConversationEvent>
}
