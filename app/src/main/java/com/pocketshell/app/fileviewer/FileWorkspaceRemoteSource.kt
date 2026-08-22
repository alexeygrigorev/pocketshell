package com.pocketshell.app.fileviewer

import com.pocketshell.app.pocketshell.PocketshellCommand
import com.pocketshell.app.ssh.BoundedSessionExec
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

/**
 * Issue #1715: the Kotlin client seam over `pocketshell tree workspace-get`
 * / `workspace-upsert` (`tree.workspace.get` / `tree.workspace.upsert` in
 * `tools/pocketshell/src/pocketshell/tree.py`).
 *
 * Mirrors [com.pocketshell.app.projects.TreeRemoteSource]: execs over the
 * SAME warm SSH session (D21 — no new connection), pipes request JSON as
 * stdin, and degrades to a safe empty/unavailable result on any failure.
 * NO POLLING: the caller hydrates once on bind and upserts on
 * open/activate/close.
 *
 * An old host CLI (no workspace verbs) is [FileWorkspaceResult.Unavailable]
 * so the viewer can still open a requested path, but restored-workspace UI
 * shows a retryable "update PocketShell on this host" state. There is no
 * phone-side tab store (D22).
 */
open class FileWorkspaceRemoteSource @Inject constructor() {
    internal var remoteExecTimeoutMs: Long = REMOTE_EXEC_TIMEOUT_MS
    internal var remoteExecDispatcher: CoroutineDispatcher = Dispatchers.IO

    /**
     * Hydrate this Unix account's file workspace. Empty tabs are a valid
     * fresh-seed state ([FileWorkspaceResult.Empty]). A missing/old CLI or
     * parse failure is [FileWorkspaceResult.Unavailable].
     */
    open suspend fun getWorkspace(session: SshSession): FileWorkspaceResult {
        return try {
            val command = pipeJsonToWrapped("{}", "tree workspace-get")
            val result = session.execWorkspaceRpcBounded(command)
                ?: return FileWorkspaceResult.Unavailable
            if (result.exitCode != 0) return FileWorkspaceResult.Unavailable
            parseWorkspace(result.stdout) ?: FileWorkspaceResult.Unavailable
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            FileWorkspaceResult.Unavailable
        }
    }

    /**
     * Persist [workspace] (fire-and-forget after a mutation). Returns true
     * when the host acknowledged the write.
     */
    open suspend fun upsertWorkspace(
        session: SshSession,
        workspace: FileWorkspace,
    ): Boolean {
        return try {
            val command = pipeJsonToWrapped(buildUpsertRequest(workspace), "tree workspace-upsert")
            val result = session.execWorkspaceRpcBounded(command) ?: return false
            if (result.exitCode != 0) return false
            val root = runCatching { JSONObject(result.stdout.trim()) }.getOrNull() ?: return false
            root.optString("status") == "ok"
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            false
        }
    }

    internal fun parseWorkspace(stdout: String): FileWorkspaceResult? {
        val trimmed = stdout.trim().ifBlank { return null }
        val root = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
        // A valid host response always carries an array, including the empty
        // array. Treat a missing/wrongly-typed field as unavailable so an old
        // or malformed CLI response can never erase a durable workspace as
        // "no open files".
        if (!root.has("tabs") || root.isNull("tabs")) return null
        val tabsArray = root.optJSONArray("tabs") ?: return null
        val tabs = ArrayList<OpenFileTab>(tabsArray.length())
        for (i in 0 until tabsArray.length()) {
            val row = tabsArray.optJSONObject(i) ?: continue
            val path = FileWorkspaceReducer.normalizeAbsolutePath(row.optString("path"))
                ?: continue
            tabs.add(
                OpenFileTab(
                    absolutePath = path,
                    lastActivatedAtMillis = row.optLong("last_activated_ms", 0L),
                ),
            )
        }
        val active = FileWorkspaceReducer.normalizeAbsolutePath(
            root.optString("active_path", "").takeIf { it.isNotBlank() },
        )
        return FileWorkspaceResult(
            workspace = FileWorkspaceReducer.recover(tabs, active),
            available = true,
        )
    }

    internal fun buildUpsertRequest(workspace: FileWorkspace): String {
        val tabs = JSONArray()
        workspace.orderedTabs.forEach { tab ->
            tabs.put(
                JSONObject()
                    .put("path", tab.absolutePath)
                    .put("last_activated_ms", tab.lastActivatedAtMillis),
            )
        }
        val root = JSONObject().put("tabs", tabs)
        if (workspace.activePath != null) {
            root.put("active_path", workspace.activePath)
        } else {
            root.put("active_path", JSONObject.NULL)
        }
        return root.toString()
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private suspend fun SshSession.execWorkspaceRpcBounded(command: String): ExecResult? =
        BoundedSessionExec.execBounded(
            session = this,
            command = command,
            timeoutMs = remoteExecTimeoutMs,
            dispatcher = remoteExecDispatcher,
            callerSite = TRAIL_CALLER_SITE,
        )

    /**
     * Build `printf %s '<json>' | { <wrapped pocketshell <args>> ; }`.
     *
     * Issue #847: [PocketshellCommand.wrap] is multi-statement. Group the
     * wrapper so the JSON pipe reaches the real `pocketshell` invocation.
     */
    private fun pipeJsonToWrapped(json: String, args: String): String =
        "printf %s ${shellQuote(json)} | { " + PocketshellCommand.wrap(args) + " ; }"

    private companion object {
        const val REMOTE_EXEC_TIMEOUT_MS: Long = 12_000L
        const val TRAIL_CALLER_SITE: String = "file_workspace_rpc"
    }
}
