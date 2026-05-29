package com.pocketshell.core.assistant

/**
 * Which provider family the in-app action assistant talks to.
 *
 * `Anthropic` covers both real Anthropic and ZAI/GLM — they share the
 * Messages wire format and differ only by base URL + model (decision D25).
 * `OpenAi` is the chat-completions family. Default selection is [OpenAi]
 * (see [AssistantSettings.DEFAULT_PROVIDER]).
 */
public enum class AssistantProvider {
    OpenAi,
    Anthropic,
    ;

    public companion object {
        /**
         * Parse a persisted provider name, defaulting to
         * [AssistantSettings.DEFAULT_PROVIDER] for unknown / missing values
         * so a hand-edited prefs blob can never crash the factory.
         */
        public fun fromName(name: String?): AssistantProvider =
            entries.firstOrNull { it.name == name } ?: AssistantSettings.DEFAULT_PROVIDER
    }
}

/**
 * Resolved configuration for one provider: the API key plus the
 * base URL and model. Passed into [AnthropicLlmClient] / [OpenAiLlmClient].
 *
 * The key is a [CharArray] (not [String]) so callers can zero their buffer
 * after construction — same hygiene as `core-voice`'s clients, which make
 * a defensive copy and only build a transient header [String] per call.
 *
 * @property apiKey provider API key.
 * @property baseUrl API base URL (no trailing `/messages` etc. — the client
 *   appends the path). For Anthropic: `https://api.anthropic.com/v1` or the
 *   ZAI/GLM URL `https://api.z.ai/api/anthropic`. For OpenAI:
 *   `https://api.openai.com/v1`.
 * @property model model id, e.g. `gpt-4o`, `claude-3-5-sonnet-latest`,
 *   `glm-4.6`.
 * @property maxTokens output token cap per turn. Both wire formats require /
 *   accept a `max_tokens` field; a shared default keeps single-shot turns
 *   bounded.
 */
public data class AssistantProviderConfig(
    val apiKey: CharArray,
    val baseUrl: String,
    val model: String,
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
) {
    // data class on a CharArray needs hand-written equals/hashCode so two
    // configs with the same key contents compare equal (array identity
    // would otherwise leak into equality and surprise tests).
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AssistantProviderConfig) return false
        return apiKey.contentEquals(other.apiKey) &&
            baseUrl == other.baseUrl &&
            model == other.model &&
            maxTokens == other.maxTokens
    }

    override fun hashCode(): Int {
        var result = apiKey.contentHashCode()
        result = 31 * result + baseUrl.hashCode()
        result = 31 * result + model.hashCode()
        result = 31 * result + maxTokens
        return result
    }

    public companion object {
        public const val DEFAULT_MAX_TOKENS: Int = 4096
    }
}

/**
 * Per-provider, non-secret settings the user configures in Settings →
 * Assistant. The API key lives separately in the KeyStore-backed
 * [com.pocketshell.core.assistant.store.AssistantConfigStore]; this data
 * class carries only the selectable / display-safe fields plus the active
 * provider selector.
 *
 * @property provider which provider the factory should build a client for.
 * @property openAiBaseUrl OpenAI base URL.
 * @property openAiModel OpenAI model id.
 * @property anthropicBaseUrl Anthropic-compatible base URL (Anthropic or
 *   ZAI/GLM).
 * @property anthropicModel Anthropic-compatible model id.
 */
public data class AssistantSettings(
    val provider: AssistantProvider = DEFAULT_PROVIDER,
    val openAiBaseUrl: String = DEFAULT_OPENAI_BASE_URL,
    val openAiModel: String = DEFAULT_OPENAI_MODEL,
    val anthropicBaseUrl: String = DEFAULT_ANTHROPIC_BASE_URL,
    val anthropicModel: String = DEFAULT_ANTHROPIC_MODEL,
) {
    public companion object {
        /** Default provider on a fresh install. Decision D25: OpenAI. */
        public val DEFAULT_PROVIDER: AssistantProvider = AssistantProvider.OpenAi

        public const val DEFAULT_OPENAI_BASE_URL: String = "https://api.openai.com/v1"
        public const val DEFAULT_OPENAI_MODEL: String = "gpt-4o"

        public const val DEFAULT_ANTHROPIC_BASE_URL: String = "https://api.anthropic.com/v1"
        public const val DEFAULT_ANTHROPIC_MODEL: String = "claude-3-5-sonnet-latest"

        /**
         * ZAI/GLM is the Anthropic wire format pointed at a different base
         * URL. Surfaced as a constant so the Settings UI can offer a
         * one-tap "use ZAI/GLM" preset for the Anthropic-compatible slot.
         */
        public const val ZAI_GLM_BASE_URL: String = "https://api.z.ai/api/anthropic"
    }
}
