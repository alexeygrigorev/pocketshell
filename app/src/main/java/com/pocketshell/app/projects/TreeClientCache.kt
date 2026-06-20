package com.pocketshell.app.projects

import android.content.Context
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
 * ## ADVISORY, never the source of truth (D22 / #679 stale-type guard)
 *
 * This cache is purely a presentation accelerator. It seeds the held tree's
 * ORDER, FOLDER PLACEMENT, COLLAPSE memory, and the one-shot FOREIGN-GUESS hint
 * — the same small durable presentation shape [HostTreeModel.HydratedNode]
 * carries — and NOTHING authoritative:
 *  - it stores NO confirmed agent kind (that lives only in `@ps_agent_kind`,
 *    one source of truth — the same rule [TreeRemoteSource] follows);
 *  - the silent reconcile (daemon delta + gateway probe) is authoritative and
 *    OVERWRITES the seeded placeholders in place (keyed diff, no rebuild). A
 *    session in the cache that the probe no longer reports is pruned by the
 *    first reconcile, so a stale cache entry can never linger past the first
 *    refresh. [HostTreeModel.hydrate] also skips clobbering an
 *    already-populated tree, so a probe that beats the cache read wins.
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
     * Read the cached node list for [host] (the cold-start instant-seed read).
     * Returns an empty list when there is no cache yet, or on any IO/parse
     * failure — both collapse to "no instant seed", and the caller shows the
     * brief Loading until the first reconcile, exactly as before the cache.
     *
     * Carries the same small public presentation shape [TreeRemoteSource.TreeNode]
     * the durable host registry uses — so the view model reuses its existing
     * [TreeRemoteSource.TreeNode] ↔ [HostTreeModel.HydratedNode] mapping and the
     * cache never reaches the `internal` [HostTreeModel] type.
     */
    public fun read(host: String): List<TreeRemoteSource.TreeNode> {
        val file = fileFor(host)
        if (!file.exists()) return emptyList()
        return try {
            parse(file.readText())
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * Write [nodes] as the new cached tree for [host] (called after a successful
     * reconcile / mutation so the NEXT cold start seeds the just-rendered tree).
     * An empty list deletes the cache file. Any IO failure is swallowed (the
     * tree is still correct in memory; the next write re-persists).
     */
    public fun write(host: String, nodes: List<TreeRemoteSource.TreeNode>) {
        val file = fileFor(host)
        try {
            if (nodes.isEmpty()) {
                file.delete()
                return
            }
            file.writeText(serialise(nodes))
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

    private fun serialise(nodes: List<TreeRemoteSource.TreeNode>): String {
        val arr = JSONArray()
        nodes.forEach { node ->
            val obj = JSONObject()
                .put(KEY_SESSION, node.session)
                .put(KEY_ORDER, node.order)
                .put(KEY_FOLDER_PATH, node.folderPath)
                .put(KEY_COLLAPSED, node.collapsed)
            node.foreignKind?.takeIf { it.isNotBlank() }?.let { obj.put(KEY_FOREIGN_KIND, it) }
            arr.put(obj)
        }
        return JSONObject().put(KEY_NODES, arr).toString()
    }

    private fun parse(text: String): List<TreeRemoteSource.TreeNode> {
        val root = JSONObject(text)
        val arr = root.optJSONArray(KEY_NODES) ?: return emptyList()
        val out = ArrayList<TreeRemoteSource.TreeNode>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val session = obj.optString(KEY_SESSION).takeIf { it.isNotBlank() } ?: continue
            out.add(
                TreeRemoteSource.TreeNode(
                    session = session,
                    order = obj.optInt(KEY_ORDER, i),
                    folderPath = obj.optString(KEY_FOLDER_PATH, ""),
                    collapsed = obj.optBoolean(KEY_COLLAPSED, false),
                    foreignKind = obj.optString(KEY_FOREIGN_KIND, "").takeIf { it.isNotBlank() },
                ),
            )
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
    }
}
