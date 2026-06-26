package com.pocketshell.app.messaging

/**
 * The data-message payload an **agent-card** push carries (epic #859, Slice D).
 *
 * Sibling to [ResetPushPayload] (hard-cut D22 — the `usage_reset` payload is
 * untouched; this is a NEW parallel `type=agent_card`). When a running agent on
 * the host calls `pocketshell push checklist`, the host fires an FCM **data**
 * message so the app — not the OS — builds a heads-up notification that
 * deep-links to that session's card feed.
 *
 * Expected data keys (mirrors `pocketshell.agent_card_push.card_to_data`):
 * - `type` = `agent_card`
 * - `session` = the tmux session the card belongs to (REQUIRED — the deep-link
 *   target; without it there is nothing to route to, so the push is dropped)
 * - `host` = best-effort host hostname for host resolution (may be empty — the
 *   host CLI does not know the app's host alias, so the app resolves the host
 *   from its own store and falls back to home on no/ambiguous match)
 * - `card_id` = the card id
 * - `card_type` = e.g. `checklist`
 * - `title` = pre-rendered card title (falls back to a type-derived label)
 * - `summary` = the per-type one-line summary (e.g. `checklist 1/3 checked`)
 * - `card_key` = the server-side de-dup identity (also the app dedup key); when
 *   absent the app falls back to `session|card_id` so a re-delivery of the same
 *   card still de-dups.
 */
public data class AgentCardPushPayload(
    val session: String,
    val host: String,
    val cardId: String,
    val cardType: String,
    val title: String,
    val summary: String,
    val cardKey: String,
) {
    public companion object {
        public const val TYPE_AGENT_CARD: String = "agent_card"

        public const val KEY_TYPE: String = "type"
        public const val KEY_SESSION: String = "session"
        public const val KEY_HOST: String = "host"
        public const val KEY_CARD_ID: String = "card_id"
        public const val KEY_CARD_TYPE: String = "card_type"
        public const val KEY_TITLE: String = "title"
        public const val KEY_SUMMARY: String = "summary"
        public const val KEY_CARD_KEY: String = "card_key"

        /**
         * Parse an `agent_card` data message into an [AgentCardPushPayload], or
         * null when the message isn't an agent-card push or is missing the
         * session (the deep-link target). Permissive on copy (falls back to
         * type-derived defaults) but strict on identity: without a `session` the
         * notification can't deep-link, so it's dropped.
         */
        public fun fromData(data: Map<String, String>): AgentCardPushPayload? {
            if (data[KEY_TYPE]?.trim() != TYPE_AGENT_CARD) return null
            val session = data[KEY_SESSION]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val host = data[KEY_HOST]?.trim().orEmpty()
            val cardId = data[KEY_CARD_ID]?.trim().orEmpty()
            val cardType = data[KEY_CARD_TYPE]?.trim()?.takeIf { it.isNotEmpty() } ?: "card"
            val title = data[KEY_TITLE]?.trim()?.takeIf { it.isNotEmpty() }
                ?: defaultTitle(cardType)
            val summary = data[KEY_SUMMARY]?.trim()?.takeIf { it.isNotEmpty() }
                ?: "Tap to open the session feed."
            val cardKey = data[KEY_CARD_KEY]?.trim()?.takeIf { it.isNotEmpty() }
                ?: "$session|$cardId"
            return AgentCardPushPayload(
                session = session,
                host = host,
                cardId = cardId,
                cardType = cardType,
                title = title,
                summary = summary,
                cardKey = cardKey,
            )
        }

        private fun defaultTitle(cardType: String): String = when (cardType.lowercase()) {
            "checklist" -> "Checklist"
            "note" -> "Note"
            else -> cardType.replaceFirstChar { if (it.isLowerCase()) it.uppercaseChar() else it }
        }
    }
}
