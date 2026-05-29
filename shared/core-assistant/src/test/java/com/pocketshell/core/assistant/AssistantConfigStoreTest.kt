package com.pocketshell.core.assistant

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.core.assistant.store.AndroidKeystoreAssistantConfigStore
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Verifies the KeyStore-backed [AndroidKeystoreAssistantConfigStore]
 * round-trips per-provider key / base URL / model and the provider
 * selector, and that the config survives a "process restart" (a fresh
 * store instance reading the same encrypted file).
 *
 * Robolectric supplies an Android Context + a stub Keystore on the host
 * JVM. Mirrors `core-voice`'s ApiKeyStorageTest setup.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class AssistantConfigStoreTest {

    private lateinit var context: Context
    private val prefsFile = "test-assistant-secrets"

    @Before
    fun setUp() {
        FakeAndroidKeyStore.install()
        context = ApplicationProvider.getApplicationContext()
        clearPrefs()
    }

    @After
    fun tearDown() {
        clearPrefs()
    }

    private fun newStore() = AndroidKeystoreAssistantConfigStore(context, prefsFile)

    @Test
    fun defaults_to_openai_with_default_endpoints() {
        val settings = newStore().loadSettings()
        assertEquals(AssistantProvider.OpenAi, settings.provider)
        assertEquals(AssistantSettings.DEFAULT_OPENAI_BASE_URL, settings.openAiBaseUrl)
        assertEquals(AssistantSettings.DEFAULT_OPENAI_MODEL, settings.openAiModel)
        assertEquals(AssistantSettings.DEFAULT_ANTHROPIC_BASE_URL, settings.anthropicBaseUrl)
        assertEquals(AssistantSettings.DEFAULT_ANTHROPIC_MODEL, settings.anthropicModel)
    }

    @Test
    fun key_is_null_before_first_save() {
        assertNull(newStore().loadKey(AssistantProvider.OpenAi))
        assertNull(newStore().loadKey(AssistantProvider.Anthropic))
    }

    @Test
    fun per_provider_config_round_trips() {
        val store = newStore()
        store.setProvider(AssistantProvider.Anthropic)
        store.setEndpoint(AssistantProvider.OpenAi, "https://oai.example/v1", "gpt-4o-mini")
        store.setEndpoint(AssistantProvider.Anthropic, AssistantSettings.ZAI_GLM_BASE_URL, "glm-4.6")
        store.saveKey(AssistantProvider.OpenAi, "sk-openai-123".toCharArray())
        store.saveKey(AssistantProvider.Anthropic, "sk-anthropic-456".toCharArray())

        val settings = store.loadSettings()
        assertEquals(AssistantProvider.Anthropic, settings.provider)
        assertEquals("https://oai.example/v1", settings.openAiBaseUrl)
        assertEquals("gpt-4o-mini", settings.openAiModel)
        assertEquals(AssistantSettings.ZAI_GLM_BASE_URL, settings.anthropicBaseUrl)
        assertEquals("glm-4.6", settings.anthropicModel)
        assertArrayEquals("sk-openai-123".toCharArray(), store.loadKey(AssistantProvider.OpenAi))
        assertArrayEquals("sk-anthropic-456".toCharArray(), store.loadKey(AssistantProvider.Anthropic))
    }

    @Test
    fun config_survives_process_restart() {
        newStore().apply {
            setProvider(AssistantProvider.Anthropic)
            setEndpoint(AssistantProvider.Anthropic, AssistantSettings.ZAI_GLM_BASE_URL, "glm-4.6")
            saveKey(AssistantProvider.Anthropic, "sk-restart".toCharArray())
        }

        // Fresh instance == simulated process restart re-reading disk.
        val restarted = newStore()
        val settings = restarted.loadSettings()
        assertEquals(AssistantProvider.Anthropic, settings.provider)
        assertEquals(AssistantSettings.ZAI_GLM_BASE_URL, settings.anthropicBaseUrl)
        assertEquals("glm-4.6", settings.anthropicModel)
        assertArrayEquals("sk-restart".toCharArray(), restarted.loadKey(AssistantProvider.Anthropic))
    }

    @Test
    fun clear_key_removes_only_that_provider() {
        val store = newStore()
        store.saveKey(AssistantProvider.OpenAi, "sk-openai".toCharArray())
        store.saveKey(AssistantProvider.Anthropic, "sk-anthropic".toCharArray())

        store.clearKey(AssistantProvider.OpenAi)

        assertNull(store.loadKey(AssistantProvider.OpenAi))
        assertArrayEquals("sk-anthropic".toCharArray(), store.loadKey(AssistantProvider.Anthropic))
    }

    private fun clearPrefs() {
        context.getSharedPreferences(prefsFile, Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir.parentFile, "shared_prefs/$prefsFile.xml").delete()
    }
}
