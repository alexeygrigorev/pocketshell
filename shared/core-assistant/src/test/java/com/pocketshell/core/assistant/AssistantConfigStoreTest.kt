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
        assertEquals(AssistantSettings.DEFAULT_ZAI_BASE_URL, settings.zaiBaseUrl)
        assertEquals(AssistantSettings.DEFAULT_ZAI_MODEL, settings.zaiModel)
    }

    @Test
    fun key_is_null_before_first_save() {
        assertNull(newStore().loadKey(AssistantProvider.OpenAi))
        assertNull(newStore().loadKey(AssistantProvider.Anthropic))
        assertNull(newStore().loadKey(AssistantProvider.Zai))
    }

    @Test
    fun per_provider_config_round_trips() {
        val store = newStore()
        store.setProvider(AssistantProvider.Zai)
        store.setEndpoint(AssistantProvider.OpenAi, "https://oai.example/v1", "gpt-4o-mini")
        store.setEndpoint(AssistantProvider.Anthropic, "https://anth.example/v1", "claude-test")
        store.setEndpoint(AssistantProvider.Zai, AssistantSettings.DEFAULT_ZAI_BASE_URL, "glm-4.6")
        store.saveKey(AssistantProvider.OpenAi, "sk-openai-123".toCharArray())
        store.saveKey(AssistantProvider.Anthropic, "sk-anthropic-456".toCharArray())
        store.saveKey(AssistantProvider.Zai, "sk-zai-789".toCharArray())

        val settings = store.loadSettings()
        assertEquals(AssistantProvider.Zai, settings.provider)
        assertEquals("https://oai.example/v1", settings.openAiBaseUrl)
        assertEquals("gpt-4o-mini", settings.openAiModel)
        assertEquals("https://anth.example/v1", settings.anthropicBaseUrl)
        assertEquals("claude-test", settings.anthropicModel)
        assertEquals(AssistantSettings.DEFAULT_ZAI_BASE_URL, settings.zaiBaseUrl)
        assertEquals("glm-4.6", settings.zaiModel)
        assertArrayEquals("sk-openai-123".toCharArray(), store.loadKey(AssistantProvider.OpenAi))
        assertArrayEquals("sk-anthropic-456".toCharArray(), store.loadKey(AssistantProvider.Anthropic))
        assertArrayEquals("sk-zai-789".toCharArray(), store.loadKey(AssistantProvider.Zai))
    }

    @Test
    fun config_survives_process_restart() {
        newStore().apply {
            setProvider(AssistantProvider.Zai)
            setEndpoint(AssistantProvider.Zai, AssistantSettings.DEFAULT_ZAI_BASE_URL, "glm-4.6")
            saveKey(AssistantProvider.Zai, "sk-restart".toCharArray())
        }

        // Fresh instance == simulated process restart re-reading disk.
        val restarted = newStore()
        val settings = restarted.loadSettings()
        assertEquals(AssistantProvider.Zai, settings.provider)
        assertEquals(AssistantSettings.DEFAULT_ZAI_BASE_URL, settings.zaiBaseUrl)
        assertEquals("glm-4.6", settings.zaiModel)
        assertArrayEquals("sk-restart".toCharArray(), restarted.loadKey(AssistantProvider.Zai))
    }

    @Test
    fun clear_key_removes_only_that_provider() {
        val store = newStore()
        store.saveKey(AssistantProvider.OpenAi, "sk-openai".toCharArray())
        store.saveKey(AssistantProvider.Anthropic, "sk-anthropic".toCharArray())
        store.saveKey(AssistantProvider.Zai, "sk-zai".toCharArray())

        store.clearKey(AssistantProvider.OpenAi)

        assertNull(store.loadKey(AssistantProvider.OpenAi))
        assertArrayEquals("sk-anthropic".toCharArray(), store.loadKey(AssistantProvider.Anthropic))
        assertArrayEquals("sk-zai".toCharArray(), store.loadKey(AssistantProvider.Zai))
    }

    private fun clearPrefs() {
        context.getSharedPreferences(prefsFile, Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir.parentFile, "shared_prefs/$prefsFile.xml").delete()
    }
}
