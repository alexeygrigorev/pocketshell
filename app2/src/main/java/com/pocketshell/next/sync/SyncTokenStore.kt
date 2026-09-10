// EncryptedSharedPreferences + MasterKey are marked @Deprecated in
// security-crypto 1.1.0 — Google's long-term direction is for callers to drive
// Tink directly. They remain the recommended Jetpack helper for at-rest
// secrets on Android today, and `:shared:core-voice`'s API-key storage makes
// the same call for the same reason. Suppressed so the warnings don't drown
// out actionable signal.
@file:Suppress("DEPRECATION")

package com.pocketshell.next.sync

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONException
import org.json.JSONObject

/**
 * The OAuth tokens at rest (issue #2633).
 *
 * The desktop app keeps its tokens in the MAIN process, encrypted with
 * Electron `safeStorage` (the OS keychain), and the renderer never sees one.
 * [AndroidKeystoreSyncTokenStore] is the same property on Android: the record
 * lives in [EncryptedSharedPreferences], whose Tink AEAD is keyed off a
 * Keystore-resident master key that cannot leave the device, and nothing above
 * [GoogleAuth] is ever handed a token — the UI layer sees
 * [SyncAuthStatus] (signed in? which email?) and nothing else.
 *
 * The interface exists so every JVM test in this package can drive the auth
 * logic against an in-memory store, rather than the auth logic having to know
 * about Android at all.
 */
interface SyncTokenStore {
    fun load(): StoredAuth?
    fun save(auth: StoredAuth)
    fun clear()
}

/**
 * The persisted sign-in. [expiresInS] is Google's `expires_in` for
 * [idToken]; [refreshToken] is absent when Google did not hand one out, and
 * then expiry means signing in again rather than a silent refresh.
 */
data class StoredAuth(
    val sub: String,
    val email: String?,
    val idToken: String,
    val refreshToken: String?,
    val obtainedAtMs: Long,
    val expiresInS: Long,
)

/** Keystore-backed store. The only place a token is written to disk. */
class AndroidKeystoreSyncTokenStore(
    context: Context,
    private val fileName: String = DEFAULT_PREFERENCES_FILE,
) : SyncTokenStore {

    private val appContext: Context = context.applicationContext
    private val prefs: SharedPreferences by lazy { openPrefsWithRepair(appContext, fileName) }

    override fun load(): StoredAuth? = runCatching {
        val raw = prefs.getString(KEY_AUTH_RECORD, null) ?: return null
        val json = JSONObject(raw)
        val idToken = json.optString("idToken").takeIf { it.isNotEmpty() } ?: return null
        StoredAuth(
            sub = json.optString("sub"),
            email = json.optString("email").takeIf { it.isNotEmpty() },
            idToken = idToken,
            refreshToken = json.optString("refreshToken").takeIf { it.isNotEmpty() },
            obtainedAtMs = json.optLong("obtainedAtMs"),
            expiresInS = json.optLong("expiresInS"),
        )
    }.getOrElse { error ->
        // A record we cannot read is a record we cannot use. Dropping it puts
        // the user back at "signed out", which is a state the UI can act on;
        // rethrowing would make one bad write a permanent settings-screen
        // crash (the same posture SettingsRepository takes on a bad prefs key).
        if (error is JSONException || error is ClassCastException) {
            Log.w(TAG, "stored sync auth could not be read; clearing", error)
            runCatching { clear() }
            null
        } else {
            Log.w(TAG, "stored sync auth could not be read", error)
            null
        }
    }

    override fun save(auth: StoredAuth) {
        val json = JSONObject()
            .put("sub", auth.sub)
            .put("email", auth.email ?: "")
            .put("idToken", auth.idToken)
            .put("refreshToken", auth.refreshToken ?: "")
            .put("obtainedAtMs", auth.obtainedAtMs)
            .put("expiresInS", auth.expiresInS)
        runCatching { prefs.edit().putString(KEY_AUTH_RECORD, json.toString()).apply() }
    }

    override fun clear() {
        runCatching { prefs.edit().remove(KEY_AUTH_RECORD).apply() }
    }

    companion object {
        /** Its OWN file: sync tokens do not share a keyset with the voice API key. */
        const val DEFAULT_PREFERENCES_FILE: String = "pocketshell-sync-auth"

        /** Field name inside the encrypted prefs. Exposed for the on-disk test. */
        const val KEY_AUTH_RECORD: String = "google_sync_auth"

        private const val TAG = "PsSyncTokens"

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

        private fun openPrefsWithRepair(context: Context, fileName: String): SharedPreferences =
            runCatching { buildPrefs(context, fileName) }
                .getOrElse {
                    Log.w(TAG, "sync auth prefs could not be opened; clearing", it)
                    context.deleteSharedPreferences(fileName)
                    context.deleteSharedPreferences("__androidx_security_crypto_encrypted_prefs_key_keyset__")
                    context.deleteSharedPreferences("__androidx_security_crypto_encrypted_prefs_value_keyset__")
                    buildPrefs(context, fileName)
                }
    }
}
