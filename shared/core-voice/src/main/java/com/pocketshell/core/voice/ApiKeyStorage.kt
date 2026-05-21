// EncryptedSharedPreferences + MasterKey are marked @Deprecated in
// `security-crypto` 1.1.0 — Google's long-term direction is for callers to
// drive Tink directly. They remain the recommended Jetpack helper for
// at-rest secrets on Android today, and migrating to raw Tink can happen
// later without changing this module's public API (save/load/clear). We
// suppress here so the warnings don't drown out actionable signal.
@file:Suppress("DEPRECATION")

package com.pocketshell.core.voice

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted at-rest storage for the user's Whisper API key.
 *
 * Backed by [EncryptedSharedPreferences] (Jetpack `security-crypto`), which
 * wraps a Tink AEAD keyed off a Keystore-resident master key. The plaintext
 * key never touches the filesystem unencrypted, and the master key cannot
 * leave the device (TEE-backed on hardware that supports it).
 *
 * The interface is intentionally three methods — save, load, clear. No
 * `contains()`, no per-field options. The Whisper key is the only secret this
 * module stores; if the app later needs other secrets, those get their own
 * storage rather than a generic key-value soup.
 *
 * @param context any Context; we hold the application context internally so
 *   we don't pin an Activity.
 */
public class AndroidKeystoreApiKeyStorage(
    context: Context,
    fileName: String = DEFAULT_PREFERENCES_FILE,
) {

    // Holding the prefs reference (not the Context) once it's built avoids
    // the master-key recreation cost on each call. EncryptedSharedPreferences
    // is safe for concurrent reads; writes are also internally serialised.
    private val prefs: SharedPreferences = buildPrefs(context.applicationContext, fileName)

    /**
     * Persist [key]. Overwrites any previously stored value. The plaintext
     * is encrypted before being written to disk — verifiable by inspecting
     * the underlying prefs file directly (see the round-trip test).
     */
    public fun save(key: String) {
        prefs.edit().putString(KEY_API_KEY, key).apply()
    }

    /** Return the stored key or `null` if none has been saved. */
    public fun load(): String? = prefs.getString(KEY_API_KEY, null)

    /**
     * Remove the stored key. The encrypted value is dropped from disk; the
     * Keystore master key is left in place so subsequent [save] calls
     * don't have to re-derive it.
     */
    public fun clear() {
        prefs.edit().remove(KEY_API_KEY).apply()
    }

    public companion object {
        /** Default SharedPreferences file name. Exposed so tests can override. */
        public const val DEFAULT_PREFERENCES_FILE: String = "pocketshell-voice-secrets"

        /** Field name within the encrypted prefs. Exposed for the on-disk verification test. */
        public const val KEY_API_KEY: String = "openai_api_key"

        private fun buildPrefs(context: Context, fileName: String): SharedPreferences {
            // MasterKey.DEFAULT_MASTER_KEY_ALIAS + AES256_GCM is the
            // recommended Jetpack default. Keystore handles the actual
            // key material; we never see it.
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
