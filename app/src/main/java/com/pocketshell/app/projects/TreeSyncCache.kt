package com.pocketshell.app.projects

/**
 * The advisory client-side cache used by [TreeSyncCoordinator].
 *
 * Keeping this seam separate from the file-backed implementation lets the
 * coordinator prove hydrate/persist ordering without making tests touch disk.
 * The host-side tree registry remains the authoritative source.
 */
internal interface TreeSyncCache {
    fun peek(host: String): TreeClientCache.CachedTree?

    fun read(host: String): TreeClientCache.CachedTree

    fun write(host: String, tree: TreeClientCache.CachedTree)
}
