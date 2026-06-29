package com.pocketshell.core.assistant

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.core.assistant.store.AndroidKeystoreAssistantConfigStore
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Regression proof for issue #1085 freeze cause F1: the
 * `EncryptedSharedPreferences` / Android-Keystore (Tink) build used to run
 * synchronously in [AndroidKeystoreAssistantConfigStore]'s constructor — and
 * Hilt constructs that `@Singleton` inside the first Compose frame (Main
 * thread), so every cold launch froze the UI thread for ~1.2-1.3s while Tink
 * initialised.
 *
 * Reproduce-first (D33 / G10): the load-bearing assertion is that the keystore
 * build runs on a thread OTHER than the constructing (Main) thread. On the
 * pre-fix code `buildPrefs` ran in `<init>` on the constructing thread, so
 * [no_keystore_build_on_constructing_thread] FAILS RED; with the off-main
 * build it runs on the IO dispatcher and PASSES GREEN.
 *
 * Class coverage (G2): the remaining tests prove the off-main init does not
 * introduce an empty/racey first read — defaults, key round-trip, and a
 * fresh-instance ("process restart") read all return the correct values after
 * the build is awaited.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class AssistantConfigStoreOffMainTest {

    private lateinit var context: Context
    private val prefsFile = "test-assistant-offmain-secrets"

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

    /**
     * LOAD-BEARING (#1085 F1): the keystore-backed prefs must NOT be built on
     * the thread that constructs the store (which, in production, is the Main
     * thread during the first Compose frame).
     */
    @Test
    fun no_keystore_build_on_constructing_thread() {
        val constructingThread = Thread.currentThread().name
        val store = newStore()

        val buildThread = store.awaitPrefsBuildThreadNameForTest()

        assertNotEquals(
            "EncryptedSharedPreferences/Keystore must be built off the " +
                "constructing (Main) thread, not on it (#1085 F1). " +
                "constructing=$constructingThread build=$buildThread",
            constructingThread,
            buildThread,
        )
    }

    /** No empty/racey first read: defaults are correct after off-main build. */
    @Test
    fun defaults_are_correct_after_offmain_build() {
        val settings = newStore().loadSettings()
        assertEquals(AssistantProvider.OpenAi, settings.provider)
        assertEquals(AssistantSettings.DEFAULT_OPENAI_BASE_URL, settings.openAiBaseUrl)
        assertEquals(AssistantSettings.DEFAULT_OPENAI_MODEL, settings.openAiModel)
        assertNull(newStore().loadKey(AssistantProvider.OpenAi))
    }

    /** Round-trip + fresh-instance read survives the off-main build. */
    @Test
    fun config_round_trips_and_survives_restart_after_offmain_build() {
        newStore().apply {
            setProvider(AssistantProvider.Anthropic)
            setEndpoint(AssistantProvider.Anthropic, "https://anth.example/v1", "claude-test")
            saveKey(AssistantProvider.Anthropic, "sk-offmain-123".toCharArray())
        }

        val restarted = newStore()
        val settings = restarted.loadSettings()
        assertEquals(AssistantProvider.Anthropic, settings.provider)
        assertEquals("https://anth.example/v1", settings.anthropicBaseUrl)
        assertEquals("claude-test", settings.anthropicModel)
        assertArrayEquals(
            "sk-offmain-123".toCharArray(),
            restarted.loadKey(AssistantProvider.Anthropic),
        )
    }

    private fun clearPrefs() {
        context.getSharedPreferences(prefsFile, Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir.parentFile, "shared_prefs/$prefsFile.xml").delete()
    }
}
