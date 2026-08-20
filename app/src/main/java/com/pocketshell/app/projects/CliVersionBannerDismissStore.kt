package com.pocketshell.app.projects

import android.content.Context
import android.content.SharedPreferences

/**
 * Issue #2033: persist a dismiss of the host-CLI version-mismatch banner so
 * the same unpublished (or otherwise unchanged) nag does not reappear on
 * every launch.
 *
 * The key is `(hostId, hostVersion, expectedVersion)`. A later app bump or
 * a host that actually upgraded produces a different key and the banner
 * is allowed to return.
 *
 * Prefs are opened lazily on first use — never in the constructor — so a
 * FolderListViewModel constructed on Main does not pay a disk read at
 * inject time (#1087 / #1292).
 */
class CliVersionBannerDismissStore(
    private val openPrefs: (() -> SharedPreferences)? = null,
) {
    private val memory = linkedSetOf<String>()

    fun isDismissed(hostId: Long?, hostVersion: String, expectedVersion: String): Boolean {
        val key = key(hostId, hostVersion, expectedVersion)
        if (key in memory) return true
        val persisted = runCatching { openPrefs?.invoke()?.getBoolean(key, false) == true }
            .getOrDefault(false)
        if (persisted) memory.add(key)
        return persisted
    }

    fun dismiss(hostId: Long?, hostVersion: String, expectedVersion: String) {
        val key = key(hostId, hostVersion, expectedVersion)
        memory.add(key)
        runCatching {
            openPrefs?.invoke()?.edit()?.putBoolean(key, true)?.apply()
        }
    }

    fun persist(
        hostId: Long?,
        mismatch: PayloadVersionCheck.Verdict.HostOutdated?,
    ) {
        if (mismatch != null) {
            dismiss(hostId, mismatch.hostVersion, mismatch.expectedVersion)
        }
    }

    fun takeIfNotDismissed(
        hostId: Long?,
        verdict: PayloadVersionCheck.Verdict.HostOutdated,
    ): PayloadVersionCheck.Verdict.HostOutdated? =
        if (isDismissed(hostId, verdict.hostVersion, verdict.expectedVersion)) null else verdict

    companion object {
        internal const val PREFS_NAME: String = "cli_version_banner_dismiss"

        fun from(context: Context?): CliVersionBannerDismissStore {
            val app = context?.applicationContext ?: return CliVersionBannerDismissStore()
            return CliVersionBannerDismissStore(
                openPrefs = {
                    app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                },
            )
        }

        internal fun key(hostId: Long?, hostVersion: String, expectedVersion: String): String =
            "${hostId ?: "none"}|$hostVersion|$expectedVersion"
    }
}
