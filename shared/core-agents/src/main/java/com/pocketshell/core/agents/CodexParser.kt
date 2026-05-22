package com.pocketshell.core.agents

public class CodexParser : ConversationParser {
    override fun parseLine(line: String): List<ConversationEvent> {
        val json = line.asJsonObjectOrNull() ?: return emptyList()
        val item = json.objectOrNull("item") ?: json
        val agent = AgentKind.Codex
        val baseId = item.stringOrNull("id")
            ?: item.stringOrNull("call_id")
            ?: json.stringOrNull("id")
            ?: line.hashCode().toString()
        val atMillis = json.timestampMillis() ?: item.timestampMillis()

        return when (item.stringOrNull("type") ?: json.stringOrNull("type")) {
            "message" -> parseMessage(item, baseId, agent, atMillis)
            "user_message" -> listOfNotNull(
                item.stringOrNull("message")?.let {
                    ConversationEvent.Message(baseId, agent, atMillis, ConversationRole.User, it)
                },
            )
            "assistant_message" -> listOfNotNull(
                item.stringOrNull("message")?.let {
                    ConversationEvent.Message(baseId, agent, atMillis, ConversationRole.Assistant, it)
                },
            )
            "function_call" -> listOf(
                ConversationEvent.ToolCall(
                    id = "call:$baseId",
                    agent = agent,
                    atMillis = atMillis,
                    name = item.stringOrNull("name") ?: "tool",
                    input = item.stringOrNull("arguments") ?: item.opt("input").stringValue(),
                ),
            )
            "function_call_output" -> listOf(
                ConversationEvent.ToolResult(
                    id = "result:$baseId",
                    agent = agent,
                    atMillis = atMillis,
                    toolCallId = item.stringOrNull("call_id"),
                    output = item.opt("output").stringValue(),
                ),
            )
            else -> emptyList()
        }
    }

    private fun parseMessage(
        item: org.json.JSONObject,
        baseId: String,
        agent: AgentKind,
        atMillis: Long?,
    ): List<ConversationEvent> {
        val role = when (item.stringOrNull("role")) {
            "user" -> ConversationRole.User
            "assistant" -> ConversationRole.Assistant
            else -> return emptyList()
        }
        val content = item.opt("content")
        val text = when (content) {
            is org.json.JSONArray -> content.textParts()
            else -> content.stringValue()
        }
        return if (text.isBlank()) {
            emptyList()
        } else {
            listOf(ConversationEvent.Message(baseId, agent, atMillis, role, text))
        }
    }
}
