package com.pocketshell.app.projects

import com.pocketshell.app.pocketshell.PocketshellCommand
import com.pocketshell.core.ssh.SshSession
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

/**
 * Epic #821 slice C (issue #837): the Kotlin client seam over the host-side
 * `pocketshell tree get|upsert|reconcile` CLI, which wraps the daemon RPC
 * `tree.get` / `tree.upsert` / `tree.reconcile`
 * (`tools/pocketshell/src/pocketshell/tree.py`).
 *
 * ## What this is for
 *
 * The maintained in-memory project tree ([HostTreeModel], #679) is volatile — a
 * process kill loses the session ORDERING, the folder EXPAND/COLLAPSE memory,
 * and the one-shot FOREIGN-GUESS cache, so a cold start shows a brief Loading
 * flash and can shuffle the order until the first probe re-seeds it. This source
 * persists that small presentation state host-side so a cold start renders the
 * held tree INSTANTLY.
 *
 * ## What it deliberately does NOT carry
 *
 * The per-session agent KIND (recorded AND confirmed-foreign) lives ONLY in the
 * tmux `@ps_agent_kind` user-option ([ManualKindWriter] writes it; the gateway
 * reads it back as the sole kind authority). This registry stores no confirmed
 * kind — a second kind writer would be the "third cache / two writers" smell the
 * design forbids (see `SessionKindPickerSheet.kt`). [TreeNode.foreignKind] is the
 * cheap one-shot foreign-GUESS hint, not the confirmed kind.
 *
 * ## How it talks to the host
 *
 * Mirrors [com.pocketshell.app.agents.AgentKindRemoteSource]: it execs
 * `pocketshell tree <verb>` over the SAME warm SSH session (D21 — no new
 * connection), piping the request JSON as stdin and parsing the CLI's stable
 * JSON envelope. Every failure (tool missing / daemon error / parse failure)
 * degrades to a safe empty/no-op result; it never throws except
 * [CancellationException].
 *
 * NO POLLING: the caller invokes these only on connect / cold-start, manual
 * refresh, resume-when-stale, and fire-and-forget after a mutation — never on a
 * timer.
 */
public class TreeRemoteSource @Inject constructor() {

    /**
     * One persisted tree node. [session] is the tmux session name (the key);
     * [order] is its intrinsic display position; [folderPath] is its
     * canonicalised bucket; [collapsed] is the user's expand/collapse choice for
     * its folder; [foreignKind] is the optional one-shot foreign-guess cache
     * (`claude` / `codex` / `opencode`), NOT the confirmed kind.
     */
    public data class TreeNode(
        val session: String,
        val order: Int,
        val folderPath: String,
        val collapsed: Boolean,
        val foreignKind: String? = null,
    )

    /** The `tree.reconcile` delta result. Deltas only — never a full reload. */
    public data class ReconcileDelta(
        val alive: List<String>,
        val gone: List<String>,
        val added: List<String>,
    )

    /**
     * Fetch the persisted node list for [host] (the cold-start HYDRATE read).
     * An empty list (no registry yet) is a valid fresh-seed state. Returns an
     * empty list on any failure.
     */
    public suspend fun getTree(session: SshSession, host: String): List<TreeNode> {
        return try {
            val request = JSONObject().put("host", host).toString()
            val command = pipeJsonToWrapped(request, "tree get")
            val result = session.exec(command)
            if (result.exitCode != 0) return emptyList()
            parseNodes(result.stdout)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * Persist [nodes] for [host] (fire-and-forget after a mutation). Returns
     * `true` when the upsert was acknowledged, `false` on any failure — the
     * caller treats a failed persist as a no-op (the tree is still correct
     * in memory; the next mutation re-persists).
     */
    public suspend fun upsertTree(
        session: SshSession,
        host: String,
        nodes: List<TreeNode>,
    ): Boolean {
        return try {
            val request = buildUpsertRequest(host, nodes)
            val command = pipeJsonToWrapped(request, "tree upsert")
            val result = session.exec(command)
            if (result.exitCode != 0) return false
            val root = runCatching { JSONObject(result.stdout.trim()) }.getOrNull() ?: return false
            root.optString("status") == "ok"
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Reconcile [host]'s registry against live `tmuxctl list` and return the
     * `{alive, gone, added}` DELTAS (never a full reload). Returns `null` on any
     * failure so the caller leaves the held tree untouched (a transient miss
     * must never wipe the tree).
     */
    public suspend fun reconcileTree(session: SshSession, host: String): ReconcileDelta? {
        return try {
            val request = JSONObject().put("host", host).toString()
            val command = pipeJsonToWrapped(request, "tree reconcile")
            val result = session.exec(command)
            if (result.exitCode != 0) return null
            parseReconcile(result.stdout)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
    }

    private fun buildUpsertRequest(host: String, nodes: List<TreeNode>): String {
        val nodesArray = JSONArray()
        nodes.forEach { node ->
            val obj = JSONObject()
                .put("session", node.session)
                .put("order", node.order)
                .put("folder_path", node.folderPath)
                .put("collapsed", node.collapsed)
            if (node.foreignKind != null && node.foreignKind.isNotBlank()) {
                obj.put("foreign_kind", node.foreignKind)
            }
            nodesArray.put(obj)
        }
        return JSONObject().put("host", host).put("nodes", nodesArray).toString()
    }

    private fun parseNodes(stdout: String): List<TreeNode> {
        val trimmed = stdout.trim().ifBlank { return emptyList() }
        val root = runCatching { JSONObject(trimmed) }.getOrNull() ?: return emptyList()
        val nodes = root.optJSONArray("nodes") ?: return emptyList()
        val out = ArrayList<TreeNode>(nodes.length())
        for (i in 0 until nodes.length()) {
            val row = nodes.optJSONObject(i) ?: continue
            val sessionName = row.optString("session").takeIf { it.isNotBlank() } ?: continue
            out.add(
                TreeNode(
                    session = sessionName,
                    order = row.optInt("order", i),
                    folderPath = row.optString("folder_path", ""),
                    collapsed = row.optBoolean("collapsed", false),
                    foreignKind = row.optString("foreign_kind", "").takeIf { it.isNotBlank() },
                ),
            )
        }
        // Stable display order: honour the persisted `order` field.
        return out.sortedBy { it.order }
    }

    private fun parseReconcile(stdout: String): ReconcileDelta? {
        val trimmed = stdout.trim().ifBlank { return null }
        val root = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
        return ReconcileDelta(
            alive = root.optJSONArray("alive").toStringList(),
            gone = root.optJSONArray("gone").toStringList(),
            added = root.optJSONArray("added").toStringList(),
        )
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        val out = ArrayList<String>(length())
        for (i in 0 until length()) {
            val value = optString(i, "").takeIf { it.isNotBlank() } ?: continue
            out.add(value)
        }
        return out
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    /**
     * Build `printf %s '<json>' | { <wrapped pocketshell <args>> ; }`.
     *
     * Issue #847: [PocketshellCommand.wrap] returns a MULTI-statement shell
     * sequence (`export PATH=...; __ps_bin=...; "$__ps_bin" <args>`). A bare
     * `printf … | <wrap>` binds the pipe ONLY to the FIRST statement
     * (`export PATH=…`), so the discovered `pocketshell <args>` inherits the SSH
     * exec channel's stdin instead of the JSON pipe — and since the app never
     * writes/closes that channel stdin, the CLI blocks on `read(stdin)` FOREVER.
     * For `tree get` (the cold-start HYDRATE read added in #837) this wedges the
     * connect-critical path: the enumeration never returns and the tree never
     * loads. Group the wrapper in `{ …; }` so the pipe reaches the real
     * `pocketshell <args>` invocation; it then receives the JSON + EOF and
     * returns promptly. Mirrors the same fix in
     * [com.pocketshell.app.agents.AgentKindRemoteSource].
     */
    private fun pipeJsonToWrapped(json: String, args: String): String =
        "printf %s ${shellQuote(json)} | { " + PocketshellCommand.wrap(args) + " ; }"
}
