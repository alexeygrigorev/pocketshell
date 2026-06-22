package com.pocketshell.app.projects

import android.content.Context
import com.pocketshell.core.storage.entity.ProjectRootEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject

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
 */
public class TreeClientCache @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val cacheDir: File by lazy {
        File(context.filesDir, CACHE_DIR_NAME).apply { mkdirs() }
    }

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
     * Read the cached snapshot for [host] (the cold-start instant-seed read).
     * Returns an empty [CachedTree] when there is no cache yet, or on any
     * IO/parse failure — both collapse to "no instant seed", and the caller
     * shows the brief Loading until the first reconcile, exactly as before the
     * cache.
     */
    public fun read(host: String): CachedTree {
        val file = fileFor(host)
        if (!file.exists()) return EMPTY
        return try {
            parse(file.readText(), hostId = host)
        } catch (_: Throwable) {
            EMPTY
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
        val file = fileFor(host)
        try {
            if (tree.isEmpty) {
                file.delete()
                return
            }
            file.writeText(serialise(tree))
        } catch (_: Throwable) {
            // Best-effort cache; a failed write is a no-op.
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
