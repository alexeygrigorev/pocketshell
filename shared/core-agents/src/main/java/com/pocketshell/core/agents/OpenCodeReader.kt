package com.pocketshell.core.agents

import org.json.JSONArray
import org.json.JSONObject

/**
 * Reader for rows exported from OpenCode's session log.
 *
 * OpenCode's real conversation store is SQLite
 * (`~/.local/share/opencode/opencode.db`). This class deliberately does not
 * open SQLite directly: app-side SSH code should run a remote `sqlite3` query
 * that emits JSON rows, then pass the resulting text to
 * [parseSqliteJsonRows]. That keeps this shared module compatible with remote
 * command output and avoids bundling an Android SQLite client for the remote
 * database.
 *
 * Entry points:
 *
 * - Batch-convert legacy/simple [OpenCodeRow]s ([parseRows]).
 * - Batch-convert SQLite join rows ([parseSqliteRows]).
 * - Parse remote `sqlite3` JSON output ([parseSqliteJsonRows]).
 * - Convert a single JSONL line as it streams in
 *   ([parseLine] / [ConversationParser]), so the
 *   `AgentConversationRepository` can use the same `session.tail(...)`
 *   pipeline that Claude + Codex already use for real-time updates
 *   (issue #160, OpenCode parity piece).
 *
 * The JSONL row shape mirrors [OpenCodeRow]:
 *
 * ```
 * {"id":"u1","role":"user","content":"check the app","createdAtMillis":12345}
 * ```
 *
 * Anything else (blank lines, comments, malformed JSON, unknown roles/parts)
 * is silently skipped. Agent logs and remote command output are allowed to be
 * partial or schema-drifty without dropping the rest of the conversation.
 */
public class OpenCodeReader : ConversationParser {
    public fun parseRows(rows: List<OpenCodeRow>): List<ConversationEvent> =
        rows.flatMapIndexed { index, row ->
            val id = row.id.ifBlank { "opencode:$index" }
            val role = parseRole(row.role) ?: return@flatMapIndexed emptyList()
            if (row.content.isBlank()) {
                emptyList()
            } else {
                listOf(
                    ConversationEvent.Message(
                        id = id,
                        agent = AgentKind.OpenCode,
                        atMillis = row.createdAtMillis,
                        role = role,
                        text = row.content,
                    ),
                )
            }
        }

    public fun parseSqliteJsonRows(output: String): List<ConversationEvent> {
        val trimmed = output.trim()
        if (trimmed.isEmpty()) return emptyList()

        val rows = if (trimmed.startsWith("[")) {
            runCatching { JSONArray(trimmed) }
                .getOrNull()
                ?.objects()
                ?.mapNotNull { sqliteRowFromJson(it) }
                ?.toList()
                ?: emptyList()
        } else {
            trimmed.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull { line -> line.asJsonObjectOrNull()?.let { sqliteRowFromJson(it) } }
                .toList()
        }

        return parseSqliteRows(rows)
    }

    public fun parseSqliteRows(rows: List<OpenCodeSqliteRow>): List<ConversationEvent> {
        val grouped = linkedMapOf<String, OpenCodeSqliteMessage>()
        rows.forEachIndexed { index, row ->
            val messageId = row.messageId.ifBlank { "opencode:message:$index" }
            val message = grouped.getOrPut(messageId) {
                OpenCodeSqliteMessage(
                    id = messageId,
                    role = row.messageRole,
                    fallbackContent = row.messageContent,
                    createdAtMillis = row.messageCreatedAtMillis,
                    data = row.messageData.parseJsonValue(),
                )
            }
            if (!row.partData.isNullOrBlank() || !row.partId.isNullOrBlank()) {
                message.parts += OpenCodeSqlitePart(
                    id = row.partId?.takeIf { it.isNotBlank() } ?: "$messageId:part:${message.parts.size}",
                    createdAtMillis = row.partCreatedAtMillis ?: row.messageCreatedAtMillis,
                    data = row.partData.parseJsonValue(),
                )
            }
        }

        return grouped.values.flatMapIndexed { index, message ->
            val messageObject = message.data as? JSONObject
            val role = parseRole(message.role ?: messageObject?.stringOrNull("role").orEmpty())
                ?: return@flatMapIndexed emptyList()
            val fallbackText = message.fallbackContent
                ?: messageObject.extractText()
            val events = message.parts.flatMap { part ->
                eventsFromPart(message, part, role)
            }
            if (events.isNotEmpty()) {
                events
            } else {
                fallbackText?.takeIf { it.isNotBlank() }?.let { text ->
                    listOf(
                        ConversationEvent.Message(
                            id = message.id.ifBlank { "opencode:$index" },
                            agent = AgentKind.OpenCode,
                            atMillis = message.createdAtMillis,
                            role = role,
                            text = text,
                        ),
                    )
                } ?: emptyList()
            }
        }
    }

    override fun parseLine(line: String): List<ConversationEvent> {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return emptyList()
        val json = trimmed.asJsonObjectOrNull() ?: return emptyList()
        val row = rowFromJson(json) ?: return emptyList()
        return parseRows(listOf(row))
    }

    private fun rowFromJson(json: JSONObject): OpenCodeRow? {
        val role = json.stringOrNull("role") ?: return null
        val content = json.stringOrNull("content") ?: json.stringOrNull("text") ?: ""
        val id = json.stringOrNull("id")
            ?: json.stringOrNull("messageId")
            ?: json.stringOrNull("uuid")
            ?: ""
        return OpenCodeRow(
            id = id,
            role = role,
            content = content,
            createdAtMillis = json.longOrNull("createdAtMillis")
                ?: json.longOrNull("created_at_ms")
                ?: json.timestampMillis(),
        )
    }

    private fun sqliteRowFromJson(json: JSONObject): OpenCodeSqliteRow? {
        val messageId = json.stringOrNull("message_id")
            ?: json.stringOrNull("messageId")
            ?: json.stringOrNull("msg_id")
            ?: json.stringOrNull("id")
            ?: return null

        return OpenCodeSqliteRow(
            messageId = messageId,
            messageData = json.stringOrNull("message_data")
                ?: json.stringOrNull("messageData")
                ?: json.stringOrNull("msg_data")
                ?: json.stringOrNull("data"),
            messageCreatedAtMillis = json.longOrNull("message_time_created")
                ?: json.longOrNull("messageCreatedAtMillis")
                ?: json.longOrNull("time_created")
                ?: json.longOrNull("createdAtMillis")
                ?: json.timestampMillis(),
            messageUpdatedAtMillis = json.longOrNull("message_time_updated")
                ?: json.longOrNull("messageUpdatedAtMillis")
                ?: json.longOrNull("time_updated")
                ?: json.longOrNull("updatedAtMillis"),
            messageRole = json.stringOrNull("message_role")
                ?: json.stringOrNull("messageRole")
                ?: json.stringOrNull("role"),
            messageContent = json.stringOrNull("message_content")
                ?: json.stringOrNull("messageContent")
                ?: json.stringOrNull("content")
                ?: json.stringOrNull("text"),
            partId = json.stringOrNull("part_id")
                ?: json.stringOrNull("partId"),
            partData = json.stringOrNull("part_data")
                ?: json.stringOrNull("partData"),
            partCreatedAtMillis = json.longOrNull("part_time_created")
                ?: json.longOrNull("partCreatedAtMillis"),
        )
    }

    private fun eventsFromPart(
        message: OpenCodeSqliteMessage,
        part: OpenCodeSqlitePart,
        role: ConversationRole,
    ): List<ConversationEvent> {
        val json = part.data as? JSONObject
        if (json == null) return emptyList()

        return when (json.stringOrNull("type")) {
            "input_text", "output_text", "text" ->
                json.stringOrNull("text")?.let { listOf(messageEvent(message, part, role, it)) }
                    ?: emptyList()

            "reasoning" ->
                json.stringOrNull("text")?.let { text ->
                    listOf(
                        ConversationEvent.SystemNote(
                            id = part.id,
                            agent = AgentKind.OpenCode,
                            atMillis = part.createdAtMillis,
                            tag = "reasoning",
                            content = text,
                        ),
                    )
                } ?: emptyList()

            "tool_use" ->
                listOf(
                    ConversationEvent.ToolCall(
                        id = json.stringOrNull("id") ?: part.id,
                        agent = AgentKind.OpenCode,
                        atMillis = part.createdAtMillis,
                        name = json.stringOrNull("name") ?: "tool",
                        input = json.opt("input").stringValue(),
                    ),
                )

            "tool" ->
                listOf(
                    ConversationEvent.ToolCall(
                        id = json.stringOrNull("id") ?: part.id,
                        agent = AgentKind.OpenCode,
                        atMillis = part.createdAtMillis,
                        name = json.stringOrNull("tool") ?: json.stringOrNull("name") ?: "tool",
                        input = json.opt("state").stringValue(),
                    ),
                )

            "tool_result", "function_call_output" -> toolResultEvent(part, json)

            else -> json.extractText()?.let { text ->
                listOf(messageEvent(message, part, role, text))
            } ?: emptyList()
        }
    }

    private fun toolResultEvent(
        part: OpenCodeSqlitePart,
        json: JSONObject,
    ): List<ConversationEvent> {
        val text = json.opt("content").extractText()
            ?: json.opt("output").extractText()
            ?: json.stringOrNull("output")
            ?: return emptyList()
        return listOf(
            ConversationEvent.ToolResult(
                id = part.id,
                agent = AgentKind.OpenCode,
                atMillis = part.createdAtMillis,
                toolCallId = json.stringOrNull("tool_use_id")
                    ?: json.stringOrNull("call_id")
                    ?: json.stringOrNull("toolCallId"),
                output = text,
                isError = json.optBoolean("is_error", false) || json.optBoolean("error", false),
            ),
        )
    }

    private fun messageEvent(
        message: OpenCodeSqliteMessage,
        part: OpenCodeSqlitePart,
        role: ConversationRole,
        text: String,
    ): ConversationEvent.Message =
        ConversationEvent.Message(
            id = part.id.ifBlank { message.id },
            agent = AgentKind.OpenCode,
            atMillis = part.createdAtMillis ?: message.createdAtMillis,
            role = role,
            text = text,
        )

    private fun parseRole(role: String): ConversationRole? = when (role.lowercase()) {
        "user" -> ConversationRole.User
        "assistant" -> ConversationRole.Assistant
        else -> null
    }
}

public data class OpenCodeRow(
    val id: String,
    val role: String,
    val content: String,
    val createdAtMillis: Long? = null,
)

public data class OpenCodeSqliteRow(
    val messageId: String,
    val messageData: String? = null,
    val messageCreatedAtMillis: Long? = null,
    val messageUpdatedAtMillis: Long? = null,
    val messageRole: String? = null,
    val messageContent: String? = null,
    val partId: String? = null,
    val partData: String? = null,
    val partCreatedAtMillis: Long? = null,
)

private data class OpenCodeSqliteMessage(
    val id: String,
    val role: String?,
    val fallbackContent: String?,
    val createdAtMillis: Long?,
    val data: Any?,
    val parts: MutableList<OpenCodeSqlitePart> = mutableListOf(),
)

private data class OpenCodeSqlitePart(
    val id: String,
    val createdAtMillis: Long?,
    val data: Any?,
)

private fun String?.parseJsonValue(): Any? {
    val trimmed = this?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return runCatching { JSONObject(trimmed) }.getOrNull()
        ?: runCatching { JSONArray(trimmed) }.getOrNull()
}

private fun Any?.extractText(): String? =
    extractTextParts(this).filter { it.isNotBlank() }.joinToString("\n\n").trim().takeIf { it.isNotBlank() }

private fun extractTextParts(value: Any?): Sequence<String> = sequence {
    when (value) {
        null, JSONObject.NULL -> Unit
        is String -> value.trim().takeIf { it.isNotEmpty() }?.let { yield(it) }
        is JSONArray -> {
            for (index in 0 until value.length()) {
                yieldAll(extractTextParts(value.opt(index)))
            }
        }
        is JSONObject -> {
            when (value.stringOrNull("type")) {
                "input_text", "output_text", "text", "reasoning" -> {
                    value.stringOrNull("text")?.trim()?.takeIf { it.isNotEmpty() }?.let { yield(it) }
                    return@sequence
                }
                "tool_result", "function_call_output" -> {
                    val content = value.opt("content")
                        .takeUnless { it == null || it == JSONObject.NULL }
                        ?: value.opt("output")
                    yieldAll(extractTextParts(content))
                    return@sequence
                }
            }
            for (key in listOf("text", "content", "message")) {
                if (value.has(key) && !value.isNull(key)) {
                    yieldAll(extractTextParts(value.opt(key)))
                    return@sequence
                }
            }
        }
        else -> value.toString().trim().takeIf { it.isNotEmpty() }?.let { yield(it) }
    }
}
