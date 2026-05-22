package com.pocketshell.core.agents

/**
 * Reader for rows exported from OpenCode's SQLite store. The Android app
 * fetches/polls the db over SSH later; this class keeps the normalization
 * unit-testable without taking a SQLite dependency in the shared module.
 */
public class OpenCodeReader {
    public fun parseRows(rows: List<OpenCodeRow>): List<ConversationEvent> =
        rows.flatMapIndexed { index, row ->
            val id = row.id.ifBlank { "opencode:$index" }
            val role = when (row.role.lowercase()) {
                "user" -> ConversationRole.User
                "assistant" -> ConversationRole.Assistant
                else -> return@flatMapIndexed emptyList()
            }
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
}

public data class OpenCodeRow(
    val id: String,
    val role: String,
    val content: String,
    val createdAtMillis: Long? = null,
)
