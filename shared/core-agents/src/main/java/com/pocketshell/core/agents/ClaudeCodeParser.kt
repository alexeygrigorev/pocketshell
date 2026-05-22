package com.pocketshell.core.agents

import org.json.JSONObject

public class ClaudeCodeParser : ConversationParser {
    override fun parseLine(line: String): List<ConversationEvent> {
        val json = line.asJsonObjectOrNull() ?: return emptyList()
        val agent = AgentKind.ClaudeCode
        val atMillis = json.timestampMillis()
        val baseId = json.stringOrNull("uuid")
            ?: json.stringOrNull("id")
            ?: json.stringOrNull("requestId")
            ?: line.hashCode().toString()

        val message = json.objectOrNull("message")
        val role = json.stringOrNull("role")
            ?: message?.stringOrNull("role")
            ?: json.stringOrNull("type")
        val content = message?.opt("content") ?: json.opt("content")

        if (role == "user" || role == "assistant") {
            return parseContent(
                baseId = baseId,
                agent = agent,
                atMillis = atMillis,
                role = if (role == "user") ConversationRole.User else ConversationRole.Assistant,
                content = content,
            )
        }

        return emptyList()
    }

    private fun parseContent(
        baseId: String,
        agent: AgentKind,
        atMillis: Long?,
        role: ConversationRole,
        content: Any?,
    ): List<ConversationEvent> {
        val events = mutableListOf<ConversationEvent>()
        when (content) {
            is String -> if (content.isNotBlank()) {
                events += ConversationEvent.Message(baseId, agent, atMillis, role, content)
            }
            is org.json.JSONArray -> {
                var textIndex = 0
                content.objects().forEachIndexed { index, part ->
                    when (part.stringOrNull("type")) {
                        "text" -> part.stringOrNull("text")?.takeIf { it.isNotBlank() }?.let { text ->
                            events += ConversationEvent.Message(
                                id = "$baseId:text:${textIndex++}",
                                agent = agent,
                                atMillis = atMillis,
                                role = role,
                                text = text,
                            )
                        }
                        "tool_use" -> events += ConversationEvent.ToolCall(
                            id = part.stringOrNull("id") ?: "$baseId:tool:$index",
                            agent = agent,
                            atMillis = atMillis,
                            name = part.stringOrNull("name") ?: "tool",
                            input = part.opt("input").stringValue(),
                        )
                        "tool_result" -> events += ConversationEvent.ToolResult(
                            id = "$baseId:result:$index",
                            agent = agent,
                            atMillis = atMillis,
                            toolCallId = part.stringOrNull("tool_use_id"),
                            output = part.opt("content").stringValue(),
                            isError = part.optBoolean("is_error", false),
                        )
                    }
                }
            }
            is JSONObject -> content.stringOrNull("text")?.takeIf { it.isNotBlank() }?.let { text ->
                events += ConversationEvent.Message(baseId, agent, atMillis, role, text)
            }
        }
        return events
    }
}
