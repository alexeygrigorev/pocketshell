package com.pocketshell.app.assistant

import android.content.Context
import java.util.UUID

/**
 * A stable per-install UUID, generated once and persisted in app prefs
 * (issue #266). Sent as `install_id` on every `pocketshell logs ingest`
 * trace so the canonical sink can attribute events to this device without
 * any phone-side account or PII.
 *
 * Plain (unencrypted) [android.content.SharedPreferences] is sufficient: the
 * id is a random opaque token, not a secret. It survives app restarts and is
 * cleared only on uninstall / data-clear, at which point a fresh id is fine.
 */
internal object AssistantInstallId {

    private const val PREFS = "pocketshell-assistant-install"
    private const val KEY = "install_id"

    /** Read the stored install id, generating and persisting one on first use. */
    fun get(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.let { return it }
        val fresh = UUID.randomUUID().toString()
        prefs.edit().putString(KEY, fresh).apply()
        return fresh
    }
}
