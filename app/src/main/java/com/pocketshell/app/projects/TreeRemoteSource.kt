package com.pocketshell.app.projects

import com.pocketshell.app.pocketshell.PocketshellCommand
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
    internal var remoteExecTimeoutMs: Long = REMOTE_EXEC_TIMEOUT_MS
    internal var remoteExecDispatcher: CoroutineDispatcher = Dispatchers.IO

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
        /**
         * Issue #885: the server-side `pocketshell` CLI version stamped into the
         * `tree.reconcile` envelope (`cli_version`), for the passive
         * version-mismatch check. `null` when an OLD CLI omits the field — that
         * is "no signal", never a false mismatch.
         */
        val cliVersion: String? = null,
    )

    /**
     * The `tree.get` result: the persisted node list plus the server CLI version
     * the payload carries (issue #885). [cliVersion] is `null` when an old CLI
     * omits it (no passive signal).
     */
    public data class TreeResult(
        val nodes: List<TreeNode>,
        val cliVersion: String? = null,
    ) {
        public companion object {
            public val Empty: TreeResult = TreeResult(nodes = emptyList(), cliVersion = null)
        }
    }

    /**
     * Fetch the persisted node list for [host] (the cold-start HYDRATE read),
     * plus the server CLI version stamped into the payload (issue #885 — the
     * passive version-mismatch signal). An empty node list (no registry yet) is
     * a valid fresh-seed state. Returns [TreeResult.Empty] on any failure.
     */
    public suspend fun getTree(session: SshSession, host: String): TreeResult {
        return try {
            val request = JSONObject().put("host", host).toString()
            val command = pipeJsonToWrapped(request, "tree get")
            val result = session.execTreeRpcBounded(command) ?: return TreeResult.Empty
            if (result.exitCode != 0) return TreeResult.Empty
            parseTreeResult(result.stdout)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            TreeResult.Empty
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
            val result = session.execTreeRpcBounded(command) ?: return false
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
            val result = session.execTreeRpcBounded(command) ?: return null
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

    private fun parseTreeResult(stdout: String): TreeResult {
        val trimmed = stdout.trim().ifBlank { return TreeResult.Empty }
        val root = runCatching { JSONObject(trimmed) }.getOrNull() ?: return TreeResult.Empty
        val cliVersion = root.optString("cli_version", "").takeIf { it.isNotBlank() }
        val nodes = root.optJSONArray("nodes")
            ?: return TreeResult(nodes = emptyList(), cliVersion = cliVersion)
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
        return TreeResult(nodes = out.sortedBy { it.order }, cliVersion = cliVersion)
    }

    private fun parseReconcile(stdout: String): ReconcileDelta? {
        val trimmed = stdout.trim().ifBlank { return null }
        val root = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
        return ReconcileDelta(
            alive = root.optJSONArray("alive").toStringList(),
            gone = root.optJSONArray("gone").toStringList(),
            added = root.optJSONArray("added").toStringList(),
            cliVersion = root.optString("cli_version", "").takeIf { it.isNotBlank() },
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
     * Bound the tree CLI/RPC exec read itself.
     *
     * The connect path already bounds the caller's hydrate/reconcile work, but
     * `SshSession.exec` can park inside a blocking stdout/stderr read. Run the
     * exec in a child coroutine and await that child with a timeout so this seam
     * degrades to its normal empty/no-op result instead of letting `tree get`,
     * `tree upsert`, or `tree reconcile` pin the warm-session path forever.
     *
     * Cancellation alone cannot interrupt that blocking read, so timeout also
     * closes the session. The warm lease path will discard the disconnected
     * session and reconnect on the next acquire.
     */
    private suspend fun SshSession.execTreeRpcBounded(command: String): ExecResult? =
        withContext(remoteExecDispatcher) {
            val deferred = async { exec(command) }
            withTimeoutOrNull(remoteExecTimeoutMs) { deferred.await() }
                ?: run {
                    deferred.cancel()
                    withContext(NonCancellable) {
                        runCatching { close() }
                    }
                    null
                }
        }

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

    private companion object {
        const val REMOTE_EXEC_TIMEOUT_MS: Long = 12_000L
    }
}
