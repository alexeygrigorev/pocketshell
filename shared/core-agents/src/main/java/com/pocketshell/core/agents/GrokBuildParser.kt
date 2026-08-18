package com.pocketshell.core.agents

import org.json.JSONObject

/**
 * Parser for Grok Build ACP `updates.jsonl` lines (`session/update`).
 *
 * [parseLine] is per-line. Assistant chunks share a stable [promptId] and
 * accumulate text so same-id replacement keeps the concatenated reply, not
 * the last chunk alone. User turns are one chunk each with a unique
 * [eventId] (no promptId on live rows). [GrokBuildReader.parseLines]
 * collapses those replacements into one event per turn.
 */
public class GrokBuildParser : ConversationParser {
    private val accumulatedText = LinkedHashMap<String, String>()

    override fun parseLine(line: String): List<ConversationEvent> {
        val json = line.asJsonObjectOrNull() ?: return emptyList()
        val params = json.objectOrNull("params") ?: return emptyList()
        val update = params.objectOrNull("update") ?: return emptyList()
        val kind = update.stringOrNull("sessionUpdate") ?: return emptyList()
        val atMillis = timestampOf(json, params)
        val sessionId = params.stringOrNull("sessionId").orEmpty()
        val meta = params.objectOrNull("_meta") ?: update.objectOrNull("_meta")

        return when (kind) {
            "user_message_chunk" -> messageChunk(
                update = update,
                meta = meta,
                sessionId = sessionId,
                atMillis = atMillis,
                role = ConversationRole.User,
            )
            "agent_message_chunk" -> messageChunk(
                update = update,
                meta = meta,
                sessionId = sessionId,
                atMillis = atMillis,
                role = ConversationRole.Assistant,
            )
            "tool_call" -> toolCall(update, atMillis)
            "tool_call_update" -> toolResult(update, atMillis)
            "agent_thought_chunk", "hook_execution" -> emptyList()
            else -> emptyList()
        }
    }

    private fun messageChunk(
        update: JSONObject,
        meta: JSONObject?,
        sessionId: String,
        atMillis: Long?,
        role: ConversationRole,
    ): List<ConversationEvent> {
        val text = chunkText(update) ?: return emptyList()
        val id = messageId(role, sessionId, meta)
        val combined = (accumulatedText[id] ?: "") + text
        accumulatedText[id] = combined
        return listOf(
            ConversationEvent.Message(
                id = id,
                agent = AgentKind.GrokBuild,
                atMillis = atMillis,
                role = role,
                text = combined,
            ),
        )
    }

    private fun toolCall(update: JSONObject, atMillis: Long?): List<ConversationEvent> {
        val id = update.stringOrNull("toolCallId") ?: return emptyList()
        return listOf(
            ConversationEvent.ToolCall(
                id = id,
                agent = AgentKind.GrokBuild,
                atMillis = atMillis,
                name = update.stringOrNull("title") ?: "tool",
                input = update.opt("rawInput").stringValue(),
            ),
        )
    }

    private fun toolResult(update: JSONObject, atMillis: Long?): List<ConversationEvent> {
        val status = update.stringOrNull("status")?.lowercase()
        if (status != null && status != "completed") return emptyList()
        val toolCallId = update.stringOrNull("toolCallId") ?: return emptyList()
        val output = toolResultText(update)
        if (output.isEmpty() && status != "completed") return emptyList()
        return listOf(
            ConversationEvent.ToolResult(
                id = "$toolCallId:result",
                agent = AgentKind.GrokBuild,
                atMillis = atMillis,
                toolCallId = toolCallId,
                output = output,
            ),
        )
    }

    private var userTurnSeq = 0

    private fun messageId(
        role: ConversationRole,
        sessionId: String,
        meta: JSONObject?,
    ): String {
        val promptId = meta?.stringOrNull("promptId")
        val promptIndex = meta?.longOrNull("promptIndex")
        val eventId = meta?.stringOrNull("eventId")
        return when (role) {
            // Live user_message_chunk rows have neither promptId nor
            // promptIndex — only eventId. One chunk per turn; do not
            // collapse every user turn onto literal "0".
            ConversationRole.User ->
                "grok:user:$sessionId:${promptIndex ?: promptId ?: eventId ?: nextUserTurnId()}"
            ConversationRole.Assistant ->
                "grok:assistant:$sessionId:${promptId ?: promptIndex ?: "0"}"
        }
    }

    private fun nextUserTurnId(): String = "u${userTurnSeq++}"

    private fun chunkText(update: JSONObject): String? {
        val content = update.opt("content")
        val text = when (content) {
            is String -> content
            is JSONObject -> content.stringOrNull("text")
            else -> null
        }?.takeIf { it.isNotEmpty() }
        return text
    }

    private fun toolResultText(update: JSONObject): String {
        val parts = ArrayList<String>()
        update.arrayOrNull("content")?.objects()?.forEach { item ->
            val inner = item.objectOrNull("content") ?: item
            inner.stringOrNull("text")?.takeIf { it.isNotEmpty() }?.let { parts += it }
        }
        if (parts.isNotEmpty()) return parts.joinToString("\n")
        return update.opt("rawOutput").stringValue()
    }

    private fun timestampOf(json: JSONObject, params: JSONObject): Long? {
        val seconds = json.longOrNull("timestamp")
        if (seconds != null) {
            return if (seconds < 10_000_000_000L) seconds * 1000 else seconds
        }
        return params.objectOrNull("_meta")?.longOrNull("agentTimestampMs")
    }
}

/** File-level reader that collapses same-id Grok chunk replacements. */
public class GrokBuildReader {
    public fun parseLines(lines: Iterable<String>): List<ConversationEvent> {
        val parser = GrokBuildParser()
        val byId = LinkedHashMap<String, ConversationEvent>()
        for (line in lines) {
            for (event in parser.parseLine(line)) {
                byId[event.id] = event
            }
        }
        return byId.values.toList()
    }

    public companion object {
        public fun coalesce(events: List<ConversationEvent>): List<ConversationEvent> {
            val byId = LinkedHashMap<String, ConversationEvent>()
            for (event in events) {
                byId[event.id] = event
            }
            return byId.values.toList()
        }
    }
}
