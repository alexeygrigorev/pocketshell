// EncryptedSharedPreferences + MasterKey are marked @Deprecated in
// `security-crypto` 1.1.0 — Google's long-term direction is for callers to
// drive Tink directly. They remain the recommended Jetpack helper for
// at-rest secrets on Android today, mirroring the choice in
// `core-voice`'s AndroidKeystoreApiKeyStorage. We suppress here so the
// warnings don't drown out actionable signal.
@file:Suppress("DEPRECATION")

package com.pocketshell.core.assistant.store

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.pocketshell.core.assistant.AssistantProvider
import com.pocketshell.core.assistant.AssistantSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

/**
 * Secret-safe interface the app / view-model consumes. Mirrors the
 * `PromptComposerViewModel.ApiKeyVault` seam pattern in `core-voice` so the
 * consumer can be unit-tested with an in-memory fake.
 */
public interface AssistantConfigStore {
    /** Read the active provider + per-provider base URL / model (non-secret). */
    public fun loadSettings(): AssistantSettings

    /** Persist the active provider. */
    public fun setProvider(provider: AssistantProvider)

    /** Persist the base URL + model for [provider]. */
    public fun setEndpoint(provider: AssistantProvider, baseUrl: String, model: String)

    /**
     * Persist the API key for [provider]. The caller retains ownership of
     * [key] and should zero it after the call.
     */
    public fun saveKey(provider: AssistantProvider, key: CharArray)

    /**
     * Return the stored key for [provider] as a freshly allocated
     * [CharArray], or `null` if none. Callers may zero it after use.
     */
    public fun loadKey(provider: AssistantProvider): CharArray?

    /** Remove the stored key for [provider]. */
    public fun clearKey(provider: AssistantProvider)
}

/**
 * KeyStore-backed [AssistantConfigStore].
 *
 * Everything — keys, base URLs, model ids, the active provider — lives in a
 * single [EncryptedSharedPreferences] file (Jetpack `security-crypto`, Tink
 * AEAD over a Keystore-resident master key). Storing the non-secret fields
 * in the same encrypted file (rather than a second plaintext
 * SharedPreferences) keeps the assistant config in one place and costs
 * nothing — the payload is tiny.
 *
 * This is a **separate file** from `core-voice`'s
 * `pocketshell-voice-secrets`: the assistant provider config must not
 * disturb the Whisper key entry. Decision D25 + the issue's non-goal "no
 * change to voice transcription provider".
 *
 * ## Off-main construction (issue #1085, freeze cause F1)
 *
 * Building [EncryptedSharedPreferences] eagerly in the constructor used to
 * run Tink / Android-Keystore init (~1.2-1.3s cold) **on the Main thread**,
 * because Hilt constructs this `@Singleton` the moment the activity's
 * `TmuxSessionViewModel` is first dereferenced — and that deref happens
 * inside the first `setContent {}` composition frame. The keystore build
 * froze the first frame on every cold launch.
 *
 * The fix: never build the encrypted prefs on the calling thread. The
 * constructor only *kicks off* the build on [ioDispatcher] (an eager
 * [kotlinx.coroutines.async]); it returns immediately. The build runs on a
 * background thread, so neither `<init>` nor composition blocks the UI
 * thread. The first read warms-or-awaits that background result (the build
 * is started at launch, so by the time the user opens Settings / sends an
 * assistant message the value is ready and the read does not block). The
 * encrypted-at-rest guarantee is unchanged — it is still
 * [EncryptedSharedPreferences], just constructed off-main. Hard-cut (D22):
 * there is no synchronous fallback path.
 *
 * @param context any Context; the application context is held internally so
 *   an Activity isn't pinned.
 * @param fileName encrypted prefs file name; overridable for tests.
 * @param ioDispatcher dispatcher the keystore-backed prefs are built on;
 *   defaults to [Dispatchers.IO]. Overridable for tests.
 */
public class AndroidKeystoreAssistantConfigStore @JvmOverloads constructor(
    context: Context,
    fileName: String = DEFAULT_PREFERENCES_FILE,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AssistantConfigStore {

    private val appContext = context.applicationContext

    // Once the background build completes, the result is cached here so reads
    // never re-enter runBlocking. @Volatile: written on [ioDispatcher],
    // read from any consumer thread.
    @Volatile
    private var cachedPrefs: SharedPreferences? = null

    // Test seam (issue #1085): records the name of the thread the keystore
    // build actually ran on, so a regression test can hard-assert it is NOT
    // the constructing/Main thread. Written on [ioDispatcher].
    @Volatile
    private var prefsBuildThreadName: String? = null

    // Eager async: the build STARTS at construction (i.e. at launch) but on a
    // background thread. `async` (DEFAULT start) dispatches immediately and
    // returns without blocking the caller.
    private val warmUpScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val prefsDeferred: Deferred<SharedPreferences> = warmUpScope.async {
        prefsBuildThreadName = Thread.currentThread().name
        buildPrefs(appContext, fileName).also { cachedPrefs = it }
    }

    private val prefs: SharedPreferences
        get() = cachedPrefs ?: runBlocking { prefsDeferred.await() }

    /**
     * Test-only: block until the off-main build completes and return the name
     * of the thread it ran on. Lets a regression test prove the keystore
     * construction did NOT happen on the constructing/Main thread (#1085).
     */
    internal fun awaitPrefsBuildThreadNameForTest(): String {
        runBlocking { prefsDeferred.await() }
        return prefsBuildThreadName
            ?: error("prefs build thread was not recorded")
    }

    override fun loadSettings(): AssistantSettings {
        val provider = AssistantProvider.fromName(prefs.getString(KEY_PROVIDER, null))
        return AssistantSettings(
            provider = provider,
            openAiBaseUrl = prefs.getString(KEY_OPENAI_BASE_URL, null)
                ?.takeIf { it.isNotBlank() }
                ?: AssistantSettings.DEFAULT_OPENAI_BASE_URL,
            openAiModel = prefs.getString(KEY_OPENAI_MODEL, null)
                ?.takeIf { it.isNotBlank() }
                ?: AssistantSettings.DEFAULT_OPENAI_MODEL,
            anthropicBaseUrl = prefs.getString(KEY_ANTHROPIC_BASE_URL, null)
                ?.takeIf { it.isNotBlank() }
                ?: AssistantSettings.DEFAULT_ANTHROPIC_BASE_URL,
            anthropicModel = prefs.getString(KEY_ANTHROPIC_MODEL, null)
                ?.takeIf { it.isNotBlank() }
                ?: AssistantSettings.DEFAULT_ANTHROPIC_MODEL,
            zaiBaseUrl = prefs.getString(KEY_ZAI_BASE_URL, null)
                ?.takeIf { it.isNotBlank() }
                ?: AssistantSettings.DEFAULT_ZAI_BASE_URL,
            zaiModel = prefs.getString(KEY_ZAI_MODEL, null)
                ?.takeIf { it.isNotBlank() }
                ?: AssistantSettings.DEFAULT_ZAI_MODEL,
        )
    }

    override fun setProvider(provider: AssistantProvider) {
        prefs.edit().putString(KEY_PROVIDER, provider.name).apply()
    }

    override fun setEndpoint(provider: AssistantProvider, baseUrl: String, model: String) {
        val (baseUrlKey, modelKey) = endpointKeys(provider)
        prefs.edit()
            .putString(baseUrlKey, baseUrl.trim())
            .putString(modelKey, model.trim())
            .apply()
    }

    override fun saveKey(provider: AssistantProvider, key: CharArray) {
        prefs.edit().putString(keyPref(provider), String(key)).apply()
    }

    override fun loadKey(provider: AssistantProvider): CharArray? =
        prefs.getString(keyPref(provider), null)?.toCharArray()

    override fun clearKey(provider: AssistantProvider) {
        prefs.edit().remove(keyPref(provider)).apply()
    }

    private fun endpointKeys(provider: AssistantProvider): Pair<String, String> =
        when (provider) {
            AssistantProvider.OpenAi -> KEY_OPENAI_BASE_URL to KEY_OPENAI_MODEL
            AssistantProvider.Anthropic -> KEY_ANTHROPIC_BASE_URL to KEY_ANTHROPIC_MODEL
            AssistantProvider.Zai -> KEY_ZAI_BASE_URL to KEY_ZAI_MODEL
        }

    private fun keyPref(provider: AssistantProvider): String = when (provider) {
        AssistantProvider.OpenAi -> KEY_OPENAI_API_KEY
        AssistantProvider.Anthropic -> KEY_ANTHROPIC_API_KEY
        AssistantProvider.Zai -> KEY_ZAI_API_KEY
    }

    public companion object {
        /** Default encrypted prefs file. Distinct from core-voice's file. */
        public const val DEFAULT_PREFERENCES_FILE: String = "pocketshell-assistant-secrets"

        public const val KEY_PROVIDER: String = "assistant_provider"
        public const val KEY_OPENAI_API_KEY: String = "openai_api_key"
        public const val KEY_OPENAI_BASE_URL: String = "openai_base_url"
        public const val KEY_OPENAI_MODEL: String = "openai_model"
        public const val KEY_ANTHROPIC_API_KEY: String = "anthropic_api_key"
        public const val KEY_ANTHROPIC_BASE_URL: String = "anthropic_base_url"
        public const val KEY_ANTHROPIC_MODEL: String = "anthropic_model"
        public const val KEY_ZAI_API_KEY: String = "zai_api_key"
        public const val KEY_ZAI_BASE_URL: String = "zai_base_url"
        public const val KEY_ZAI_MODEL: String = "zai_model"

        private fun buildPrefs(context: Context, fileName: String): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                fileName,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }
}
