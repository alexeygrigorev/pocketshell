package com.pocketshell.app.messaging

import android.content.Context
import android.content.SharedPreferences
import com.pocketshell.app.pocketshell.PocketshellCommand

/**
 * Owns the device's FCM token and the path that delivers it to the host (issue
 * #690).
 *
 * ## Why a registrar, not a background uploader
 *
 * D21 forbids app-side background work — PocketShell never holds a connection
 * of its own. So the token is NOT uploaded from a service in the background.
 * Instead:
 *
 * 1. [onTokenRefreshed] caches the freshest token locally (FCM may rotate it).
 * 2. [pendingToken] / [needsRegistration] let the FOREGROUND code path (a live
 *    SSH session — e.g. the usage refresh) deliver the token to the host with
 *    [registerCommand], then mark it delivered via [markRegistered].
 *
 * ## Token-delivery path to the host
 *
 * The token is written to the host's `pocketshell` config so the server-side
 * reset detector (merged) can target this device's FCM token via the FCM HTTP
 * v1 API. [registerCommand] builds the PATH-robust invocation
 * `pocketshell push register-token <token>` (run over an existing SSH `exec`).
 * The server-side `push register-token` subcommand (storing the token under the
 * pocketshell config dir) is a later server slice; THIS class defines the
 * client contract it must honour.
 */
public class FcmTokenRegistrar(
    private val prefs: SharedPreferences,
) {
    public constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    )

    /** Cache the freshest device token (called from FCM's `onNewToken`). */
    public fun onTokenRefreshed(token: String) {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) return
        val previous = prefs.getString(KEY_TOKEN, null)
        val editor = prefs.edit().putString(KEY_TOKEN, trimmed)
        // A genuinely new/rotated token must be re-delivered to the host.
        if (trimmed != previous) {
            editor.putBoolean(KEY_REGISTERED, false)
        }
        editor.apply()
    }

    /** The freshest cached token, or null if FCM hasn't minted one yet. */
    public fun pendingToken(): String? = prefs.getString(KEY_TOKEN, null)?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * True when there is a cached token that has not yet been delivered to the
     * host — the foreground SSH path should run [registerCommand] and then call
     * [markRegistered].
     */
    public fun needsRegistration(): Boolean =
        pendingToken() != null && !prefs.getBoolean(KEY_REGISTERED, false)

    /** Mark the cached token as successfully delivered to the host. */
    public fun markRegistered() {
        prefs.edit().putBoolean(KEY_REGISTERED, true).apply()
    }

    public companion object {
        public const val PREFS_NAME: String = "pocketshell_fcm"
        private const val KEY_TOKEN: String = "fcm_token"
        private const val KEY_REGISTERED: String = "fcm_token_registered"

        /** The `pocketshell` arguments for delivering a device token to the host. */
        public const val REGISTER_TOKEN_SUBCOMMAND: String = "push register-token"

        /**
         * Build the PATH-robust host command that registers [token] with the
         * host's `pocketshell` config (run over an existing foreground SSH
         * `exec`). The token is single-quoted; FCM tokens are URL-safe base64
         * (no single quotes), so plain single-quoting is sufficient.
         */
        public fun registerCommand(token: String): String =
            PocketshellCommand.wrap("$REGISTER_TOKEN_SUBCOMMAND '${token.trim()}'")
    }
}
