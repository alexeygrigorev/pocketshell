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

    /**
     * A structured metadata block emitted by an agent's JSONL feed inside
     * what would otherwise be a `Message` text payload. Claude Code in
     * particular wraps non-user-authored notes (date-change reminders,
     * slash-command echoes, captured stdout) in XML-style tags such as
     * `<system-reminder>`, `<command-name>`, `<command-args>`,
     * `<command-stdout>`, and `<local-command-stdout>`. Issue #176 lifts
     * them out of the surrounding markdown so the conversation pane can
     * render them in a muted, collapsible style without competing with
     * the user/assistant prose.
     *
     * The [tag] is the raw XML element name (without `<>`); [content] is
     * the inner text exactly as the agent emitted it (no trim, no markdown
     * processing — the renderer treats it as preformatted text).
     */
    public data class SystemNote(
        override val id: String,
        override val agent: AgentKind,
        override val atMillis: Long? = null,
        val tag: String,
        val content: String,
    ) : ConversationEvent
}

public interface ConversationParser {
    public fun parseLine(line: String): List<ConversationEvent>
}
