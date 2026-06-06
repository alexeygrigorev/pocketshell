package com.pocketshell.core.assistant

import com.pocketshell.core.assistant.store.AssistantConfigStore

/**
 * Builds the right [AssistantLlmClient] for the user's currently-selected
 * provider, pulling the resolved [AssistantProviderConfig] from the
 * KeyStore-backed [AssistantConfigStore] at call time.
 *
 * Re-reading on each [create] mirrors `core-voice`'s
 * `WhisperClientFactory`: the latest key / base URL / model flows into the
 * next request without a stale-config footgun if the user edits Settings
 * mid-session.
 *
 * Returns `null` when the selected provider has no API key stored yet, so
 * the caller can route the user through key entry before retrying — same
 * contract as `WhisperClientFactory.create`.
 *
 * @param store the persisted provider config + secrets.
 * @param clientBuilder seam over the concrete client constructors so unit
 *   tests can assert which provider was selected without standing up a real
 *   OkHttp-backed client. Production uses the default, which builds the
 *   real [AnthropicLlmClient] / [OpenAiLlmClient].
 */
public class AssistantLlmClientFactory(
    private val store: AssistantConfigStore,
    private val clientBuilder: (AssistantProvider, AssistantProviderConfig) -> AssistantLlmClient =
        ::defaultClientBuilder,
) {

    /**
     * Build a client for the active provider, or `null` if its key is unset.
     * Zeroes the transient key copy before returning — the built client made
     * its own defensive copy.
     */
    public fun create(): AssistantLlmClient? {
        val settings = store.loadSettings()
        val provider = settings.provider
        val key = store.loadKey(provider) ?: return null
        return try {
            val config = configFor(provider, settings, key)
            clientBuilder(provider, config)
        } finally {
            java.util.Arrays.fill(key, ' ')
        }
    }

    /** The provider the factory will build a client for on the next [create]. */
    public fun activeProvider(): AssistantProvider = store.loadSettings().provider

    private fun configFor(
        provider: AssistantProvider,
        settings: AssistantSettings,
        key: CharArray,
    ): AssistantProviderConfig = when (provider) {
        AssistantProvider.OpenAi -> AssistantProviderConfig(
            apiKey = key,
            baseUrl = settings.openAiBaseUrl,
            model = settings.openAiModel,
        )
        AssistantProvider.Anthropic -> AssistantProviderConfig(
            apiKey = key,
            baseUrl = settings.anthropicBaseUrl,
            model = settings.anthropicModel,
        )
        AssistantProvider.Zai -> AssistantProviderConfig(
            apiKey = key,
            baseUrl = settings.zaiBaseUrl,
            model = settings.zaiModel,
        )
    }

    public companion object {
        /** Default production client builder: real OkHttp-backed clients. */
        public fun defaultClientBuilder(
            provider: AssistantProvider,
            config: AssistantProviderConfig,
        ): AssistantLlmClient = when (provider) {
            AssistantProvider.OpenAi -> OpenAiLlmClient(config)
            AssistantProvider.Anthropic -> AnthropicLlmClient(config)
            AssistantProvider.Zai -> AnthropicLlmClient(config)
        }
    }
}
