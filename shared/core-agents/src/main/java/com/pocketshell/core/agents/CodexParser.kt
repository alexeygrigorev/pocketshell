package com.pocketshell.core.agents

public class CodexParser : ConversationParser {
    override fun parseLine(line: String): List<ConversationEvent> {
        val json = line.asJsonObjectOrNull() ?: return emptyList()
        val item = json.objectOrNull("payload")
            ?: json.objectOrNull("item")
            ?: json
        val agent = AgentKind.Codex
        val baseId = item.stringOrNull("id")
            ?: item.stringOrNull("call_id")
            ?: json.stringOrNull("id")
            ?: line.hashCode().toString()
        val atMillis = json.timestampMillis() ?: item.timestampMillis()

        return when (item.stringOrNull("type") ?: json.stringOrNull("type")) {
            "message" -> parseMessage(item, baseId, agent, atMillis)
            "user_message" -> parseSimpleMessage(item, baseId, agent, atMillis, ConversationRole.User)
            "assistant_message" -> parseSimpleMessage(item, baseId, agent, atMillis, ConversationRole.Assistant)
            "agent_message" -> parseSimpleMessage(item, baseId, agent, atMillis, ConversationRole.Assistant)
            "function_call" -> {
                val callId = item.stringOrNull("call_id") ?: baseId
                listOf(
                    ConversationEvent.ToolCall(
                        id = "call:$callId",
                        agent = agent,
                        atMillis = atMillis,
                        name = item.stringOrNull("name") ?: "tool",
                        input = item.stringOrNull("arguments") ?: item.opt("input").stringValue(),
                    ),
                )
            }
            "function_call_output" -> {
                val callId = item.stringOrNull("call_id") ?: baseId
                listOf(
                    ConversationEvent.ToolResult(
                        id = "result:$callId",
                        agent = agent,
                        atMillis = atMillis,
                        toolCallId = item.stringOrNull("call_id"),
                        output = item.opt("output").stringValue(),
                    ),
                )
            }
            "reasoning" -> listOfNotNull(
                reasoningText(item).takeIf { it.isNotBlank() }?.let {
                    ConversationEvent.SystemNote(
                        id = "reasoning:$baseId",
                        agent = agent,
                        atMillis = atMillis,
                        tag = "reasoning",
                        content = it,
                    )
                },
            )
            else -> emptyList()
        }
    }

    private fun parseSimpleMessage(
        item: org.json.JSONObject,
        baseId: String,
        agent: AgentKind,
        atMillis: Long?,
        role: ConversationRole,
    ): List<ConversationEvent> {
        val text = messageText(item)
        return if (text.isBlank()) {
            emptyList()
        } else {
            listOf(ConversationEvent.Message(baseId, agent, atMillis, role, text))
        }
    }

    private fun messageText(item: org.json.JSONObject): String =
        item.stringOrNull("message")
            ?: item.stringOrNull("text")
            ?: when (val content = item.opt("content")) {
                is org.json.JSONArray -> content.textParts()
                else -> content.stringValue()
            }

    private fun reasoningText(item: org.json.JSONObject): String =
        item.stringOrNull("text")
            ?: item.stringOrNull("content")
            ?: item.arrayOrNull("summary")?.textParts()
            ?: item.opt("summary").stringValue()

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
        val text = messageText(item)
        return if (text.isBlank()) {
            emptyList()
        } else {
            listOf(ConversationEvent.Message(baseId, agent, atMillis, role, text))
        }
    }
}
