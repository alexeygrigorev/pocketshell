package com.pocketshell.app.cards

import com.pocketshell.app.pocketshell.PocketshellCommand
import com.pocketshell.core.ssh.SshSession
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

/**
 * Issue #859: Android client seam for the host-side typed-card feed.
 *
 * The host CLI owns persistence (`pocketshell push checklist|get|check`).
 * This source only reads the current tmux session's cards and writes checklist
 * ticks back over the existing warm [SshSession] (D21: no new connection).
 */
public class SessionCardsRemoteSource @Inject constructor() {

    public data class Feed(
        val session: String,
        val cards: List<SessionCard>,
    ) {
        public companion object {
            public val Empty: Feed = Feed(session = "", cards = emptyList())
        }
    }

    public sealed interface SessionCard {
        public val id: String
        public val type: String
        public val title: String?
        public val createdAt: String?
        public val updatedAt: String?
    }

    public data class ChecklistCard(
        override val id: String,
        override val title: String?,
        override val createdAt: String?,
        override val updatedAt: String?,
        val items: List<ChecklistItem>,
        val checkedIds: Set<String>,
    ) : SessionCard {
        override val type: String = TYPE_CHECKLIST
    }

    public data class ChecklistItem(
        val id: String,
        val text: String,
    )

    public data class UnknownCard(
        override val id: String,
        override val type: String,
        override val title: String?,
        override val createdAt: String?,
        override val updatedAt: String?,
    ) : SessionCard

    /**
     * Read the card feed for [tmuxSessionName]. Returns [Feed.Empty] on any
     * non-cancellation failure so older hosts or missing tools do not disturb
     * the terminal session.
     */
    public suspend fun getCards(
        session: SshSession,
        tmuxSessionName: String,
    ): Feed {
        val target = tmuxSessionName.trim()
        if (target.isEmpty()) return Feed.Empty
        return try {
            val result = session.exec(
                PocketshellCommand.wrap(
                    "push get --json --session ${shellQuote(target)}",
                ),
            )
            if (result.exitCode != 0) return Feed.Empty
            parseFeed(result.stdout)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            Feed.Empty
        }
    }

    /**
     * Persist one checklist tick/untick. Returns `true` only when the host CLI
     * acknowledges the change.
     */
    public suspend fun setChecklistItemChecked(
        session: SshSession,
        tmuxSessionName: String,
        cardId: String,
        itemId: String,
        checked: Boolean,
    ): Boolean {
        val target = tmuxSessionName.trim()
        if (target.isEmpty() || cardId.isBlank() || itemId.isBlank()) return false
        val doneFlag = if (checked) "--done" else "--undone"
        return try {
            val result = session.exec(
                PocketshellCommand.wrap(
                    "push check --id ${shellQuote(cardId)} " +
                        "--item ${shellQuote(itemId)} " +
                        "$doneFlag --session ${shellQuote(target)}",
                ),
            )
            result.exitCode == 0
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            false
        }
    }

    internal fun parseFeed(stdout: String): Feed {
        val trimmed = stdout.trim().ifBlank { return Feed.Empty }
        val root = runCatching { JSONObject(trimmed) }.getOrNull() ?: return Feed.Empty
        val session = root.optString("session", "")
        val cards = root.optJSONArray("cards").toCards()
        return Feed(session = session, cards = cards)
    }

    private fun JSONArray?.toCards(): List<SessionCard> {
        if (this == null) return emptyList()
        val out = ArrayList<SessionCard>(length())
        for (i in 0 until length()) {
            val row = optJSONObject(i) ?: continue
            val id = row.optString("id").takeIf { it.isNotBlank() } ?: continue
            val type = row.optString("type").takeIf { it.isNotBlank() } ?: continue
            val title = row.optString("title", "").takeIf { it.isNotBlank() }
            val createdAt = row.optString("created_at", "").takeIf { it.isNotBlank() }
            val updatedAt = row.optString("updated_at", "").takeIf { it.isNotBlank() }
            out += when (type) {
                TYPE_CHECKLIST -> row.toChecklistCard(
                    id = id,
                    title = title,
                    createdAt = createdAt,
                    updatedAt = updatedAt,
                )
                else -> UnknownCard(
                    id = id,
                    type = type,
                    title = title,
                    createdAt = createdAt,
                    updatedAt = updatedAt,
                )
            }
        }
        return out
    }

    private fun JSONObject.toChecklistCard(
        id: String,
        title: String?,
        createdAt: String?,
        updatedAt: String?,
    ): ChecklistCard {
        val body = optJSONObject("body")
        val state = optJSONObject("state")
        val items = body
            ?.optJSONArray("items")
            .toChecklistItems()
        val checkedIds = state
            ?.optJSONArray("checked")
            .toStringSet()
        return ChecklistCard(
            id = id,
            title = title,
            createdAt = createdAt,
            updatedAt = updatedAt,
            items = items,
            checkedIds = checkedIds,
        )
    }

    private fun JSONArray?.toChecklistItems(): List<ChecklistItem> {
        if (this == null) return emptyList()
        val out = ArrayList<ChecklistItem>(length())
        for (i in 0 until length()) {
            val row = optJSONObject(i) ?: continue
            val id = row.optString("id").takeIf { it.isNotBlank() } ?: continue
            val text = row.optString("text").takeIf { it.isNotBlank() } ?: continue
            out += ChecklistItem(id = id, text = text)
        }
        return out
    }

    private fun JSONArray?.toStringSet(): Set<String> {
        if (this == null) return emptySet()
        val out = LinkedHashSet<String>()
        for (i in 0 until length()) {
            val value = optString(i, "").takeIf { it.isNotBlank() } ?: continue
            out += value
        }
        return out
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    public companion object {
        public const val TYPE_CHECKLIST: String = "checklist"
    }
}
