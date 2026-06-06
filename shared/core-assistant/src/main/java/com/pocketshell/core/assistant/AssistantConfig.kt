package com.pocketshell.core.assistant

/**
 * Which provider family the in-app action assistant talks to.
 *
 * Product-facing providers are kept separate from wire protocols: [Zai]
 * uses the Anthropic-compatible Messages protocol internally, but it has
 * its own settings and secret slot.
 */
public enum class AssistantProvider {
    OpenAi,
    Anthropic,
    Zai,
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
 *   appends the path). For Anthropic: `https://api.anthropic.com/v1`.
 *   For ZAI: `https://api.z.ai/api/anthropic`. For OpenAI:
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
 * @property anthropicBaseUrl Anthropic base URL.
 * @property anthropicModel Anthropic model id.
 * @property zaiBaseUrl ZAI Anthropic-compatible Messages base URL.
 * @property zaiModel ZAI model id.
 */
public data class AssistantSettings(
    val provider: AssistantProvider = DEFAULT_PROVIDER,
    val openAiBaseUrl: String = DEFAULT_OPENAI_BASE_URL,
    val openAiModel: String = DEFAULT_OPENAI_MODEL,
    val anthropicBaseUrl: String = DEFAULT_ANTHROPIC_BASE_URL,
    val anthropicModel: String = DEFAULT_ANTHROPIC_MODEL,
    val zaiBaseUrl: String = DEFAULT_ZAI_BASE_URL,
    val zaiModel: String = DEFAULT_ZAI_MODEL,
) {
    public companion object {
        /** Default provider on a fresh install. Decision D25: OpenAI. */
        public val DEFAULT_PROVIDER: AssistantProvider = AssistantProvider.OpenAi

        public const val DEFAULT_OPENAI_BASE_URL: String = "https://api.openai.com/v1"
        public const val DEFAULT_OPENAI_MODEL: String = "gpt-4o"

        public const val DEFAULT_ANTHROPIC_BASE_URL: String = "https://api.anthropic.com/v1"
        public const val DEFAULT_ANTHROPIC_MODEL: String = "claude-3-5-sonnet-latest"

        public const val DEFAULT_ZAI_BASE_URL: String = "https://api.z.ai/api/anthropic"
        public const val DEFAULT_ZAI_MODEL: String = "glm-4.6"
    }
}
