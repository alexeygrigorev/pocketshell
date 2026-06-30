package com.pocketshell.app.projects

import android.content.Context
import com.pocketshell.core.storage.entity.ProjectRootEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #867: the per-host CLIENT-SIDE cold cache of the last-rendered project
 * tree, so a fresh connect / cold app start paints the last-known tree
 * INSTANTLY instead of flashing the empty rebuild ("No folders yet / 0
 * projects", everything dumped in "Other folders", a spinner) while the daemon
 * round-trip + first probe run.
 *
 * ## Why a client cache at all (vs the #837 host daemon)
 *
 * The maintained in-memory tree ([HostTreeModel], #679) dies with the process,
 * and #837's durability is HOST-side — reading it back requires the warm SSH
 * session, which is the very round-trip whose gap produces the empty flash. A
 * small client-local snapshot closes that gap: it is read from local storage
 * (no SSH) the instant [FolderListViewModel.bind] runs, hydrated into the held
 * tree, and rendered immediately.
 *
 * ## Issue #867 REOPEN — cache the STRUCTURE, not just the sessions
 *
 * The original #867 cache stored ONLY the per-session nodes (name + cwd +
 * collapse + foreign-guess). That is enough to render the FLAT session list, but
 * NOT the grouped tree: [FolderListViewModel.buildFolderTree] also needs the
 * watched-root overlay, the resolved watched-root match paths, and the scanned /
 * history project folders to (a) bucket a session under its watched root
 * ("git → 74 projects") rather than dumping it into "Other folders", and (b)
 * show the project subfolders + the "X projects" count. With none of that
 * cached, the cold-start render showed every watched root at "0 projects" and
 * every session in "Other folders" — exactly the spinner / empty-rebuild state
 * the maintainer re-reported on v0.4.14. The shipped unit test only asserted the
 * FLAT session list (which renders fine without any structure), so the broken
 * grouping passed review (the G6/F2 proxy gap). This cache now persists the full
 * presentation snapshot: the session nodes PLUS the watched roots and the three
 * structural maps, so the cold-start instant render reproduces the SETTLED
 * grouping.
 *
 * ## ADVISORY, never the source of truth (D22 / #679 stale-type guard)
 *
 * This cache is purely a presentation accelerator. It seeds the held tree's
 * ORDER, FOLDER PLACEMENT, COLLAPSE memory, the one-shot FOREIGN-GUESS hint, the
 * watched-root overlay, and the directory-layout maps — and NOTHING
 * authoritative:
 *  - it stores NO confirmed agent kind (that lives only in `@ps_agent_kind`,
 *    one source of truth — the same rule [TreeRemoteSource] follows);
 *  - the silent reconcile (daemon delta + gateway probe) is authoritative and
 *    OVERWRITES the seeded placeholders + structural maps in place. A session in
 *    the cache that the probe no longer reports is pruned by the first reconcile,
 *    and the structural maps are replaced wholesale, so a stale cache entry can
 *    never linger past the first refresh. [HostTreeModel.hydrate] /
 *    [HostTreeModel.hydrateStructure] also skip clobbering an already-populated
 *    tree, so a probe that beats the cache read wins.
 *  - the cached watched roots are advisory too: the authoritative Room
 *    `project_roots` Flow overwrites them via [HostTreeModel.setWatchedFolders]
 *    the moment it emits (typically within the same frame), so a since-edited
 *    root list self-corrects.
 *
 * ## Storage
 *
 * One small JSON file per host under `<filesDir>/tree-cache/`, keyed by a
 * sanitised host name. Synchronous, tiny reads/writes on a background
 * dispatcher; any IO failure degrades to "no cache" (the screen falls back to
 * the brief Loading exactly as before) and is never surfaced to the user.
 *
 * ## Issue #1109 — an in-memory PARSED snapshot so the seed never reads disk on Main
 *
 * The cold-connect instant render needs the cached tree SYNCHRONOUSLY inside
 * [FolderListViewModel.bind] (the first painted frame must already be Ready, or the
 * empty rebuild flashes). But the file read + full `JSONObject` parse is exactly the
 * Main-thread `disk_read` that produced the #965 folder-list ANR at scale (71
 * projects / 12 sessions). The two are reconciled by DECOUPLING the parse from the
 * hydrate: every persisted snapshot is also held PARSED in an in-memory map, keyed
 * by the same sanitised host. [peek] reads that map SYNCHRONOUSLY with NO disk I/O —
 * so `bind` hydrates instantly and Main never reads the file. The map is populated
 * OFF Main by [warmAll] (the cold-start pre-warm) and synchronously by [write]
 * (which already holds the parsed tree, so the just-rendered snapshot is hot for the
 * rest of the process). A genuine cold MISS (the entry was not warmed in time) is
 * the caller's signal to fall back to the brief Loading and read OFF Main via [read]
 * — never a Main-thread file read. This is why the type is `@Singleton`: one
 * process-wide parsed map shared across every [FolderListViewModel] instance.
 */
@Singleton
public class TreeClientCache @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val cacheDir: File by lazy {
        File(context.filesDir, CACHE_DIR_NAME).apply { mkdirs() }
    }

    /**
     * Issue #1109: the in-memory PARSED snapshot per host (keyed by the sanitised
     * host name, the same key the file uses). [peek] returns from here SYNCHRONOUSLY
     * with no disk I/O; [write] keeps it hot; [warmAll]/[read] populate it OFF Main.
     * Concurrent because the off-Main warm/read and the on-Main `peek` touch it from
     * different threads.
     */
    private val parsed = ConcurrentHashMap<String, CachedTree>()

    /**
     * The full advisory presentation snapshot persisted for one host: the
     * session nodes plus the structural inputs [FolderListViewModel.buildFolderTree]
     * needs to reproduce the grouped tree on a cold-start instant render.
     */
    public data class CachedTree(
        val nodes: List<TreeRemoteSource.TreeNode>,
        val watchedFolders: List<ProjectRootEntity> = emptyList(),
        val resolvedWatchedRootPaths: Map<String, String> = emptyMap(),
        val scannedProjectFoldersByRoot: Map<String, List<String>> = emptyMap(),
        val historyProjectFoldersByRoot: Map<String, List<String>> = emptyMap(),
    ) {
        val isEmpty: Boolean get() = nodes.isEmpty()
    }

    /**
     * Issue #1109: the SYNCHRONOUS, NO-DISK-I/O peek used by the cold-connect
     * instant seed ([FolderListViewModel.hydrateFromClientCache]). Returns the
     * in-memory parsed snapshot for [host] if it has been warmed (by [warmAll] or a
     * prior [write]/[read]), or `null` if it has not — the caller's signal to fall
     * back to the brief Loading and read OFF Main via [read]. Safe to call on the
     * Main thread: it only touches the in-memory map.
     */
    public fun peek(host: String): CachedTree? = parsed[sanitise(host)]

    /**
     * Read the cached snapshot for [host] (the OFF-Main cold-miss / warm read).
     * Returns the in-memory snapshot if already warmed, otherwise reads + parses the
     * file and caches it, or an empty [CachedTree] when there is no cache yet / on
     * any IO/parse failure. This DOES touch disk on a miss, so it must run off the
     * Main thread; the Main-thread seed uses [peek] instead (issue #1109 / #965).
     */
    public fun read(host: String): CachedTree {
        val key = sanitise(host)
        parsed[key]?.let { return it }
        val file = fileFor(host)
        if (!file.exists()) return EMPTY
        return try {
            val tree = parse(file.readText(), hostId = host)
            // Cache the parse, but never clobber a snapshot a concurrent [write]
            // already landed (the write is the authoritative just-rendered tree).
            if (!tree.isEmpty) parsed.putIfAbsent(key, tree)
            parsed[key] ?: tree
        } catch (_: Throwable) {
            EMPTY
        }
    }

    /**
     * Issue #1109: warm the in-memory parsed snapshot from every persisted host file
     * so the FIRST cold connect's [peek] hits without a Main-thread read. Best-effort
     * and OFF Main (a few small JSON files); a missing/corrupt file just stays a cold
     * miss. Never clobbers an entry a [write] already landed (`putIfAbsent`).
     */
    public fun warmAll() {
        val files = runCatching { cacheDir.listFiles() }.getOrNull() ?: return
        for (file in files) {
            if (!file.isFile || !file.name.endsWith(".json")) continue
            val key = file.name.removeSuffix(".json")
            if (parsed.containsKey(key)) continue
            val tree = runCatching { parse(file.readText(), hostId = key) }.getOrNull() ?: continue
            if (!tree.isEmpty) parsed.putIfAbsent(key, tree)
        }
    }

    /**
     * Write [tree] as the new cached snapshot for [host] (called after a
     * successful reconcile / mutation so the NEXT cold start seeds the
     * just-rendered tree, grouping and all). An empty node list deletes the
     * cache file. Any IO failure is swallowed (the tree is still correct in
     * memory; the next write re-persists).
     */
    public fun write(host: String, tree: CachedTree) {
        val key = sanitise(host)
        // Issue #1109: keep the in-memory parsed snapshot hot with the just-rendered
        // tree so the NEXT cold connect of this host in the same process [peek]s it
        // synchronously (no Main-thread re-read). An empty tree evicts the entry.
        if (tree.isEmpty) {
            parsed.remove(key)
        } else {
            parsed[key] = tree
        }
        val file = fileFor(host)
        try {
            if (tree.isEmpty) {
                file.delete()
                return
            }
            file.writeText(serialise(tree))
        } catch (_: Throwable) {
            // Best-effort cache; a failed write is a no-op (the in-memory snapshot
            // above is still correct; the next write re-persists).
        }
    }

    private fun fileFor(host: String): File = File(cacheDir, "${sanitise(host)}.json")

    private fun sanitise(host: String): String =
        host.map { ch -> if (ch.isLetterOrDigit() || ch == '-' || ch == '_') ch else '_' }
            .joinToString("")
            .ifBlank { "_" }
            .take(120)

    private fun serialise(tree: CachedTree): String {
        val nodesArray = JSONArray()
        tree.nodes.forEach { node ->
            val obj = JSONObject()
                .put(KEY_SESSION, node.session)
                .put(KEY_ORDER, node.order)
                .put(KEY_FOLDER_PATH, node.folderPath)
                .put(KEY_COLLAPSED, node.collapsed)
            node.foreignKind?.takeIf { it.isNotBlank() }?.let { obj.put(KEY_FOREIGN_KIND, it) }
            nodesArray.put(obj)
        }
        val watchedArray = JSONArray()
        tree.watchedFolders.forEach { root ->
            watchedArray.put(
                JSONObject()
                    .put(KEY_WATCHED_PATH, root.path)
                    .put(KEY_WATCHED_LABEL, root.label),
            )
        }
        return JSONObject()
            .put(KEY_NODES, nodesArray)
            .put(KEY_WATCHED_FOLDERS, watchedArray)
            .put(KEY_RESOLVED_ROOTS, stringMapToJson(tree.resolvedWatchedRootPaths))
            .put(KEY_SCANNED, stringListMapToJson(tree.scannedProjectFoldersByRoot))
            .put(KEY_HISTORY, stringListMapToJson(tree.historyProjectFoldersByRoot))
            .toString()
    }

    private fun parse(text: String, hostId: String): CachedTree {
        val root = JSONObject(text)
        val nodesJson = root.optJSONArray(KEY_NODES) ?: return EMPTY
        val nodes = ArrayList<TreeRemoteSource.TreeNode>(nodesJson.length())
        for (i in 0 until nodesJson.length()) {
            val obj = nodesJson.optJSONObject(i) ?: continue
            val session = obj.optString(KEY_SESSION).takeIf { it.isNotBlank() } ?: continue
            nodes.add(
                TreeRemoteSource.TreeNode(
                    session = session,
                    order = obj.optInt(KEY_ORDER, i),
                    folderPath = obj.optString(KEY_FOLDER_PATH, ""),
                    collapsed = obj.optBoolean(KEY_COLLAPSED, false),
                    foreignKind = obj.optString(KEY_FOREIGN_KIND, "").takeIf { it.isNotBlank() },
                ),
            )
        }
        if (nodes.isEmpty()) return EMPTY
        val watchedFolders = ArrayList<ProjectRootEntity>()
        root.optJSONArray(KEY_WATCHED_FOLDERS)?.let { arr ->
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val path = obj.optString(KEY_WATCHED_PATH).takeIf { it.isNotBlank() } ?: continue
                watchedFolders.add(
                    ProjectRootEntity(
                        hostId = 0L,
                        label = obj.optString(KEY_WATCHED_LABEL, ""),
                        path = path,
                    ),
                )
            }
        }
        return CachedTree(
            nodes = nodes,
            watchedFolders = watchedFolders,
            resolvedWatchedRootPaths = jsonToStringMap(root.optJSONObject(KEY_RESOLVED_ROOTS)),
            scannedProjectFoldersByRoot = jsonToStringListMap(root.optJSONObject(KEY_SCANNED)),
            historyProjectFoldersByRoot = jsonToStringListMap(root.optJSONObject(KEY_HISTORY)),
        )
    }

    private fun stringMapToJson(map: Map<String, String>): JSONObject {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        return obj
    }

    private fun stringListMapToJson(map: Map<String, List<String>>): JSONObject {
        val obj = JSONObject()
        map.forEach { (k, list) -> obj.put(k, JSONArray(list)) }
        return obj
    }

    private fun jsonToStringMap(obj: JSONObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val out = LinkedHashMap<String, String>()
        obj.keys().forEach { key -> out[key] = obj.optString(key, "") }
        return out
    }

    private fun jsonToStringListMap(obj: JSONObject?): Map<String, List<String>> {
        if (obj == null) return emptyMap()
        val out = LinkedHashMap<String, List<String>>()
        obj.keys().forEach { key ->
            val arr = obj.optJSONArray(key) ?: return@forEach
            val list = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                arr.optString(i, "").takeIf { it.isNotBlank() }?.let { list.add(it) }
            }
            out[key] = list
        }
        return out
    }

    private companion object {
        const val CACHE_DIR_NAME: String = "tree-cache"
        const val KEY_NODES: String = "nodes"
        const val KEY_SESSION: String = "session"
        const val KEY_ORDER: String = "order"
        const val KEY_FOLDER_PATH: String = "folder_path"
        const val KEY_COLLAPSED: String = "collapsed"
        const val KEY_FOREIGN_KIND: String = "foreign_kind"
        const val KEY_WATCHED_FOLDERS: String = "watched_folders"
        const val KEY_WATCHED_PATH: String = "path"
        const val KEY_WATCHED_LABEL: String = "label"
        const val KEY_RESOLVED_ROOTS: String = "resolved_roots"
        const val KEY_SCANNED: String = "scanned_by_root"
        const val KEY_HISTORY: String = "history_by_root"

        val EMPTY: CachedTree = CachedTree(nodes = emptyList())
    }
}
