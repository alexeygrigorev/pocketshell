package com.pocketshell.app.messaging

/**
 * The data-message payload a reset push carries (issue #690).
 *
 * The server's `pocketshell` reset-detection sends an FCM **data** message (not
 * a `notification` message) so the app — not the OS — builds the notification.
 * That keeps the deep-link to the usage screen and the `reset_key` de-dup under
 * the app's control, and means the push is processed even when the data fields
 * arrive while the app is foregrounded.
 *
 * Expected data keys:
 * - `type` = `usage_reset` (the only push type today; others are ignored)
 * - `provider` = e.g. `codex` / `claude`
 * - `reset_key` = the server-side de-dup identity (`provider|window|reset_at`)
 * - `title` / `body` = optional pre-rendered copy; the app falls back to a
 *   default "<Provider> limits reset" when absent.
 */
public data class ResetPushPayload(
    val provider: String,
    val resetKey: String,
    val title: String,
    val body: String,
) {
    public companion object {
        public const val TYPE_USAGE_RESET: String = "usage_reset"

        public const val KEY_TYPE: String = "type"
        public const val KEY_PROVIDER: String = "provider"
        public const val KEY_RESET_KEY: String = "reset_key"
        public const val KEY_TITLE: String = "title"
        public const val KEY_BODY: String = "body"

        /**
         * Parse a `usage_reset` data message into a [ResetPushPayload], or null
         * when the message isn't a reset push or is missing the de-dup key.
         * Permissive on copy (falls back to a default) but strict on identity:
         * without a `reset_key` the push can't be de-dup'd, so it's dropped.
         */
        public fun fromData(data: Map<String, String>): ResetPushPayload? {
            if (data[KEY_TYPE]?.trim() != TYPE_USAGE_RESET) return null
            val resetKey = data[KEY_RESET_KEY]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val provider = data[KEY_PROVIDER]?.trim()?.takeIf { it.isNotEmpty() } ?: "Provider"
            val displayProvider = providerDisplayName(provider)
            val title = data[KEY_TITLE]?.trim()?.takeIf { it.isNotEmpty() }
                ?: "$displayProvider limits reset"
            val body = data[KEY_BODY]?.trim()?.takeIf { it.isNotEmpty() }
                ?: "Your $displayProvider usage limits just reset. Heavy work can resume."
            return ResetPushPayload(
                provider = provider,
                resetKey = resetKey,
                title = title,
                body = body,
            )
        }

        private fun providerDisplayName(provider: String): String = when (provider.lowercase()) {
            "codex", "openai", "chatgpt" -> "Codex"
            "claude", "anthropic" -> "Claude"
            else -> provider.replaceFirstChar { if (it.isLowerCase()) it.uppercaseChar() else it }
        }
    }
}
