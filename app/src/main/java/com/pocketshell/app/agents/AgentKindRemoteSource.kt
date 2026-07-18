package com.pocketshell.app.agents

import com.pocketshell.app.pocketshell.PocketshellCommand
import com.pocketshell.app.ssh.BoundedSessionExec
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import javax.inject.Inject

/**
 * Epic #821 slice A2: the Kotlin client seam over the host-side
 * `pocketshell agents kind` CLI (#827), which itself wraps the daemon RPC
 * `agents.kind_for_panes` (cgroup-v2 + `/proc` agent-kind detection — see
 * `tools/pocketshell/src/pocketshell/cgroup_agents.py`).
 *
 * This is the ONE-SHOT FOREIGN-SESSION GUESS path. Sessions PocketShell
 * launched carry a recorded `@ps_agent_kind` and are the sole authority for
 * their kind (no detection round-trip at all). Sessions we did NOT launch
 * (`recordedKind == null`) have no recorded kind; rather than re-introducing
 * the deleted output-parsing detector, we ask the host daemon ONCE — "we
 * think it's X" — and let the user confirm / pick. The result then sticks
 * exactly like a recorded kind (the caller caches it per session id; this
 * source itself is stateless).
 *
 * Mirrors [com.pocketshell.app.jobs.PocketshellJobsRemoteSource]: it execs
 * `pocketshell agents kind` over the SAME warm SSH session (D21 — no new
 * connection) and parses the CLI's stable JSON envelope. The pane snapshot is
 * piped as stdin JSON (`{"panes": [{"pane_id", "pane_pid"}, ...]}`), the
 * byte-for-byte RPC request shape.
 */
public class AgentKindRemoteSource @Inject constructor() {
    private var execReadTimeoutMs: Long = EXEC_READ_TIMEOUT_MS

    /**
     * Issue #1001 (CI-flake fix): the dispatcher the bounded daemon RPC exec
     * runs on. Defaults to [Dispatchers.IO] so the potentially-wedged blocking
     * SSH read never parks a UI/caller thread in production. A JVM unit test
     * driving the detection chain under `runTest(scheduler)` pins this to a
     * `StandardTestDispatcher` on the SHARED virtual-clock scheduler via
     * [setExecDispatcherForTest], so the exec + its `withTimeoutOrNull` resolve
     * on the test scheduler — deterministically drained by `runCurrent()` /
     * `advanceUntilIdle()` instead of racing a real `Dispatchers.IO` background
     * thread (the b1Masked* detection-bind race, ~1/3 release-variant failures).
     * Production behaviour is unchanged.
     */
    private var execDispatcher: CoroutineDispatcher = Dispatchers.IO

    internal constructor(execReadTimeoutMs: Long) : this() {
        this.execReadTimeoutMs = execReadTimeoutMs
    }

    /**
     * Issue #1001: confine the bounded-exec dispatcher to a test scheduler so a
     * `runTest`-driven detection test awaits the daemon RPC deterministically.
     * Test-only; never called in production.
     */
    @androidx.annotation.VisibleForTesting
    internal fun setExecDispatcherForTest(dispatcher: CoroutineDispatcher) {
        execDispatcher = dispatcher
    }

    /**
     * One pane to classify. [paneId] is the tmux pane id (`%N`) used only to
     * correlate the result back to the caller's row; [panePid] is the pane's
     * `#{pane_pid}`, the signal the daemon resolves to a cgroup scope.
     */
    public data class PaneRef(
        val paneId: String,
        val panePid: Long,
    )

    /**
     * The daemon's verdict for one pane. [kind] is the resolved agent engine,
     * or `null` for `none` (a readable scope with no agent — i.e. a plain
     * shell) or `unknown` (the pane pid/cgroup was unreadable). [isShell]
     * distinguishes the confirmed-shell (`none`) case from the genuinely
     * unknown (`unknown`) case so the caller can present "it's a shell" vs
     * "we don't know — pick".
     */
    public data class PaneKind(
        val paneId: String,
        val kind: AgentKind?,
        val isShell: Boolean,
    )

    /**
     * Classify [panes] in ONE SSH exec. Returns a map keyed by pane id. A
     * tool-missing / daemon-error / parse failure resolves to an EMPTY map
     * (the caller treats an absent pane verdict the same as "don't know" —
     * the foreign session simply stays unclassified until the user picks),
     * never throwing for anything but cancellation.
     */
    public suspend fun classify(
        session: SshSession,
        panes: List<PaneRef>,
    ): Map<String, PaneKind> {
        if (panes.isEmpty()) return emptyMap()
        return try {
            val requestJson = buildRequestJson(panes)
            // Pipe the pane snapshot as stdin JSON (the primary CLI input form),
            // so a pane id containing odd characters never has to be shell-escaped
            // into an argv. `printf %s` avoids the trailing newline `echo` adds and
            // the backslash interpretation some `echo` builtins apply.
            // Issue #847: [PocketshellCommand.wrap] returns a MULTI-statement
            // shell sequence (`export PATH=...; __ps_bin=...; "$__ps_bin" agents
            // kind`). A bare `printf … | <wrap>` binds the pipe ONLY to the FIRST
            // statement (`export PATH=…`), so the discovered `pocketshell agents
            // kind` inherits the SSH exec channel's stdin instead of the JSON
            // pipe — and since the app never writes/closes that channel stdin, the
            // CLI blocks on `read(stdin)` FOREVER, wedging the whole folder
            // enumeration until the 12s reconcile bound trips ("Session list
            // didn't load within 12000ms") and the tree never loads. Group the
            // wrapper in `{ …; }` so the pipe reaches the actual `agents kind`
            // invocation; it then gets the JSON + EOF and returns promptly.
            val command =
                "printf %s ${shellQuote(requestJson)} | { " +
                    PocketshellCommand.wrap("agents kind") +
                    " ; }"
            val result = session.execBounded(command) ?: return emptyMap()
            if (result.exitCode != 0) return emptyMap()
            parseEnvelope(result.stdout)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            emptyMap()
        }
    }

    private fun buildRequestJson(panes: List<PaneRef>): String {
        val sb = StringBuilder()
        sb.append("{\"panes\":[")
        panes.forEachIndexed { index, pane ->
            if (index > 0) sb.append(',')
            sb.append("{\"pane_id\":")
            sb.append(JSONObject.quote(pane.paneId))
            sb.append(",\"pane_pid\":")
            sb.append(pane.panePid)
            sb.append('}')
        }
        sb.append("]}")
        return sb.toString()
    }

    /**
     * Parse the CLI envelope
     * `{"results": [{"pane_id", "agent_kind", "scope", "evidence_pid"?}]}`.
     * One malformed row never sinks the batch. `agent_kind` is one of
     * `claude` / `codex` / `opencode` / `none` / `unknown`.
     */
    private fun parseEnvelope(stdout: String): Map<String, PaneKind> {
        val trimmed = stdout.trim().ifBlank { return emptyMap() }
        val root = runCatching { JSONObject(trimmed) }.getOrNull() ?: return emptyMap()
        val results = root.optJSONArray("results") ?: return emptyMap()
        val out = LinkedHashMap<String, PaneKind>()
        for (i in 0 until results.length()) {
            val row = results.optJSONObject(i) ?: continue
            val paneId = row.optString("pane_id").takeIf { it.isNotBlank() } ?: continue
            val rawKind = row.optString("agent_kind").trim().lowercase()
            val kind = when (rawKind) {
                "claude" -> AgentKind.ClaudeCode
                "codex" -> AgentKind.Codex
                "opencode" -> AgentKind.OpenCode
                else -> null
            }
            out[paneId] = PaneKind(
                paneId = paneId,
                kind = kind,
                isShell = rawKind == "none",
            )
        }
        return out
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    /**
     * Bound the host-side daemon RPC exec — issue #1641.
     *
     * A timeout is best-effort "no verdict" for callers and NOTHING more: the
     * exec is abandoned and the SHARED per-host lease transport is left alone.
     *
     * This used to `close()` that shared transport on timeout, which made a
     * merely-SLOW foreign-pane classify (a cold host Python CLI over mobile RTT
     * routinely exceeds 3.5s) tear down the live `-CC` reader's transport and
     * enter the #1610 reconnect storm — silently, with no log or cause trail.
     * A confirmed-shell pane re-classifies on EVERY reconcile, so it re-fired
     * for the life of the session. Per #1567's contract, a starved exec is NOT
     * evidence of a dead link; only keepalive/liveness may close the session.
     * See [BoundedSessionExec] for the full rationale.
     */
    private suspend fun SshSession.execBounded(command: String): ExecResult? =
        BoundedSessionExec.execBounded(
            session = this,
            command = command,
            timeoutMs = execReadTimeoutMs,
            dispatcher = execDispatcher,
            callerSite = TRAIL_CALLER_SITE,
        )

    private companion object {
        const val EXEC_READ_TIMEOUT_MS: Long = 3_500L

        /** Stable, non-PII attribution token for the cause trail (#1641). */
        const val TRAIL_CALLER_SITE: String = "agent_kind_classify"
    }
}
