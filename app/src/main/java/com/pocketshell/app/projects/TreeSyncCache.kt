package com.pocketshell.app.projects

/**
 * The advisory client-side cache used by [TreeSyncCoordinator].
 *
 * Keeping this seam separate from the file-backed implementation lets the
 * coordinator prove hydrate/persist ordering without making tests touch disk.
 * The host-side tree registry remains the authoritative source.
 */
internal interface TreeSyncCache {
    suspend fun migrateLegacy(hostId: Long, legacyHostName: String) = Unit

    fun peek(hostId: Long): TreeClientCache.CachedTree?

    fun read(hostId: Long): TreeClientCache.CachedTree

    fun write(hostId: Long, revision: Long, tree: TreeClientCache.CachedTree)
}
