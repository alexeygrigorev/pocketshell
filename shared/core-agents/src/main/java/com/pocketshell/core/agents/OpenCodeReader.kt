package com.pocketshell.core.agents

import org.json.JSONObject

/**
 * Reader for rows exported from OpenCode's session log.
 *
 * Two entry points so the Android app can either:
 *
 * - Batch-convert a list of [OpenCodeRow]s ([parseRows]), useful when
 *   the rows arrive together (e.g. from a one-shot SQLite export).
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
 * Anything else (blank lines, comments, malformed JSON, unknown roles)
 * is silently skipped — `tail -F` semantics make it easy to surface
 * partial lines while the agent is mid-write and the renderer must
 * tolerate that without dropping the rest of the conversation.
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
