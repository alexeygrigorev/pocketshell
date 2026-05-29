package com.pocketshell.core.assistant

import com.pocketshell.core.assistant.store.AssistantConfigStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the factory selects the impl from the persisted provider
 * (default OpenAI), passes the resolved per-provider config through, and
 * returns null when the active provider has no key.
 */
class AssistantLlmClientFactoryTest {

    /** In-memory [AssistantConfigStore]; no KeyStore / Robolectric needed. */
    private class FakeStore(
        var settings: AssistantSettings = AssistantSettings(),
    ) : AssistantConfigStore {
        val keys = mutableMapOf<AssistantProvider, CharArray>()
        override fun loadSettings(): AssistantSettings = settings
        override fun setProvider(provider: AssistantProvider) {
            settings = settings.copy(provider = provider)
        }
        override fun setEndpoint(provider: AssistantProvider, baseUrl: String, model: String) {
            settings = when (provider) {
                AssistantProvider.OpenAi -> settings.copy(openAiBaseUrl = baseUrl, openAiModel = model)
                AssistantProvider.Anthropic ->
                    settings.copy(anthropicBaseUrl = baseUrl, anthropicModel = model)
            }
        }
        override fun saveKey(provider: AssistantProvider, key: CharArray) {
            keys[provider] = key.copyOf()
        }
        override fun loadKey(provider: AssistantProvider): CharArray? = keys[provider]?.copyOf()
        override fun clearKey(provider: AssistantProvider) { keys.remove(provider) }
    }

    /** Records which (provider, config) the factory asked to build. */
    private class RecordingBuilder {
        var lastProvider: AssistantProvider? = null
        var lastConfig: AssistantProviderConfig? = null
        val build: (AssistantProvider, AssistantProviderConfig) -> AssistantLlmClient =
            { provider, config ->
                lastProvider = provider
                lastConfig = config
                object : AssistantLlmClient {
                    override suspend fun complete(
                        messages: List<LlmMessage>,
                        tools: List<ToolSpec>,
                        toolChoice: ToolChoice?,
                    ): Result<LlmResponse> = error("not invoked in this test")
                }
            }
    }

    @Test
    fun default_provider_is_openai() {
        val store = FakeStore()
        assertEquals(AssistantProvider.OpenAi, AssistantLlmClientFactory(store).activeProvider())
    }

    @Test
    fun returns_null_when_active_provider_has_no_key() {
        val store = FakeStore()
        assertNull(AssistantLlmClientFactory(store).create())
    }

    @Test
    fun builds_openai_client_with_openai_config_by_default() {
        val store = FakeStore(
            AssistantSettings(
                provider = AssistantProvider.OpenAi,
                openAiBaseUrl = "https://oai.example/v1",
                openAiModel = "gpt-4o-mini",
            ),
        )
        store.saveKey(AssistantProvider.OpenAi, "sk-openai".toCharArray())
        val recorder = RecordingBuilder()

        val client = AssistantLlmClientFactory(store, recorder.build).create()

        assertTrue(client != null)
        assertEquals(AssistantProvider.OpenAi, recorder.lastProvider)
        assertEquals("https://oai.example/v1", recorder.lastConfig!!.baseUrl)
        assertEquals("gpt-4o-mini", recorder.lastConfig!!.model)
    }

    @Test
    fun switching_provider_changes_which_client_is_built() {
        val store = FakeStore()
        store.saveKey(AssistantProvider.OpenAi, "sk-openai".toCharArray())
        store.saveKey(AssistantProvider.Anthropic, "sk-anthropic".toCharArray())
        store.setEndpoint(AssistantProvider.Anthropic, AssistantSettings.ZAI_GLM_BASE_URL, "glm-4.6")
        val recorder = RecordingBuilder()
        val factory = AssistantLlmClientFactory(store, recorder.build)

        factory.create()
        assertEquals(AssistantProvider.OpenAi, recorder.lastProvider)

        store.setProvider(AssistantProvider.Anthropic)
        factory.create()
        assertEquals(AssistantProvider.Anthropic, recorder.lastProvider)
        assertEquals(AssistantSettings.ZAI_GLM_BASE_URL, recorder.lastConfig!!.baseUrl)
        assertEquals("glm-4.6", recorder.lastConfig!!.model)
        assertEquals(AssistantProvider.Anthropic, factory.activeProvider())
    }

    @Test
    fun default_builder_picks_concrete_impl_per_provider() {
        val openAi = AssistantLlmClientFactory.defaultClientBuilder(
            AssistantProvider.OpenAi,
            AssistantProviderConfig("k".toCharArray(), "https://oai/v1", "gpt-4o"),
        )
        val anthropic = AssistantLlmClientFactory.defaultClientBuilder(
            AssistantProvider.Anthropic,
            AssistantProviderConfig("k".toCharArray(), "https://anth/v1", "claude"),
        )
        assertTrue(openAi is OpenAiLlmClient)
        assertTrue(anthropic is AnthropicLlmClient)
    }
}
