package com.pocketshell.next.workspaces

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the order of remote workspace memberships without making the host
 * CLI own presentation preferences. Paths are newline-safe POSIX identities,
 * so a small SharedPreferences value is enough and survives process death.
 */
@Singleton
class WorkspaceOrderStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun get(hostId: Long, rootPath: String): List<String> =
        (preferences.getString(identityKey(hostId, rootPath), null)
            ?: preferences.getString(rawKey(hostId, rootPath), null)
            ?: preferences.getString(canonicalKey(hostId, rootPath), null))
            ?.lineSequence()
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.toList()
            .orEmpty()

    fun put(hostId: Long, rootPath: String, paths: List<String>) {
        val value = paths.distinct().joinToString("\n")
        preferences.edit()
            // A root may be saved as ~/git while the host reports its identity
            // as /home/testuser/git. Keep one home-relative identity key so a
            // refresh cannot lose the user's order when the spelling changes.
            .putString(identityKey(hostId, rootPath), value)
            // Keep the entered spelling and its lexical canonical spelling so
            // a root saved as ~/git remains linked after the projection learns
            // the host's absolute home path.
            .putString(rawKey(hostId, rootPath), value)
            .putString(canonicalKey(hostId, rootPath), value)
            .apply()
    }

    private fun rawKey(hostId: Long, rootPath: String): String =
        "$KEY_PREFIX$hostId:raw:$rootPath"

    private fun canonicalKey(hostId: Long, rootPath: String): String =
        "$KEY_PREFIX$hostId:canonical:${canonicalRemotePath(rootPath) ?: rootPath}"

    private fun identityKey(hostId: Long, rootPath: String): String =
        "$KEY_PREFIX$hostId:identity:${stableRootIdentity(rootPath)}"

    private fun stableRootIdentity(rootPath: String): String {
        val canonical = canonicalRemotePath(rootPath) ?: rootPath.trim()
        return when {
            canonical == "/root" -> "~"
            canonical.startsWith("/root/") -> homeRelative(canonical.removePrefix("/root/"))
            canonical.startsWith("/home/") -> homeRelative(canonical.removePrefix("/home/"))
            canonical.startsWith("/Users/") -> homeRelative(canonical.removePrefix("/Users/"))
            canonical.startsWith("/var/home/") -> homeRelative(canonical.removePrefix("/var/home/"))
            else -> canonical
        }
    }

    private fun homeRelative(userAndPath: String): String {
        val path = userAndPath.substringAfter('/', missingDelimiterValue = "")
        return if (path.isEmpty()) "~" else "~/$path"
    }

    private companion object {
        const val PREFERENCES = "workspace_order"
        const val KEY_PREFIX = "host-root-"
    }
}
