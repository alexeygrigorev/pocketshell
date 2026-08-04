package com.pocketshell.app.projects

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.core.ssh.SshSession
import java.io.File

/**
 * Raw authority trail for issue #1973's cross-class agent-kind contamination.
 *
 * This deliberately observes the fixture independently of the gateway result:
 * tmux identity/options come from `list-panes`, process identity comes from
 * `/proc`, and the foreign-kind verdict comes from the host CLI itself. The
 * gateway rows are appended only after those raw signals, making it possible to
 * locate the first disagreement without treating the product under test as its
 * own oracle.
 */
internal object Issue1973AgentKindAuthorityDiagnostics {
    private const val TAG = "Issue1973Authority"
    private const val SEP = "|#1973|"

    suspend fun capture(
        session: SshSession,
        phase: String,
        sessionNames: Collection<String>,
        rows: List<FolderSessionRow> = emptyList(),
    ): String {
        val targets = sessionNames.toSet()
        val rawPanes = session.exec(
            "tmux list-panes -a -F " + shellQuote(
                listOf(
                    "#{session_name}",
                    "#{session_id}",
                    "#{session_created}",
                    "#{window_index}",
                    "#{window_id}",
                    "#{pane_id}",
                    "#{pane_pid}",
                    "#{pane_current_command}",
                    "#{@ps_agent_kind}",
                ).joinToString(SEP),
            ) + " 2>&1 || true",
        ).stdout
        val panes = rawPanes.lineSequence()
            .mapNotNull(::parsePane)
            .filter { it.sessionName in targets }
            .toList()

        return buildString {
            appendLine("phase=$phase")
            appendLine("targets=${targets.sorted().joinToString()}")
            val found = panes.mapTo(linkedSetOf()) { it.sessionName }
            for (missing in targets - found) appendLine("session=$missing status=MISSING")
            for (pane in panes) {
                val cacheKey = "${pane.sessionName}::${pane.windowIndex}"
                appendLine(
                    "tmux session=${pane.sessionName} session_id=${pane.sessionId} " +
                        "session_created=${pane.sessionCreated} window_index=${pane.windowIndex} " +
                        "window_id=${pane.windowId} pane_id=${pane.paneId} pane_pid=${pane.panePid} " +
                        "command=${pane.command} raw_ps_agent_kind=${pane.rawRecordedKind.ifBlank { "<absent>" }}",
                )
                val process = session.exec(
                    "ps -o pid=,ppid=,comm=,args= -p ${pane.panePid} 2>&1 || true; " +
                        "printf 'cgroup='; " +
                        "tr '\\n' ',' < /proc/${pane.panePid}/cgroup 2>/dev/null || true; printf '\\n'",
                ).stdout.trim()
                appendLine("process cache_key=$cacheKey $process")

                val request =
                    "{\"panes\":[{\"pane_id\":\"$cacheKey\",\"pane_pid\":${pane.panePid}}]}"
                val detection = session.exec(
                    "printf %s ${shellQuote(request)} | pocketshell agents kind 2>&1 || true",
                ).stdout.trim()
                val source = if (pane.rawRecordedKind.isBlank()) {
                    "foreign:pocketshell-agents-kind"
                } else {
                    "recorded:@ps_agent_kind"
                }
                appendLine("authority cache_key=$cacheKey source=$source detection=$detection")
            }
            for (row in rows.filter { it.sessionName in targets }) {
                appendLine(
                    "gateway session=${row.sessionName} tmux_session_id=${row.tmuxSessionId} " +
                        "session_created=${row.sessionCreated} recorded_kind=${row.recordedKind} " +
                        "resolved_kind=${row.agentKind} authority_source=" +
                        if (row.recordedKind == null) {
                            "foreign:pocketshell-agents-kind"
                        } else {
                            "recorded:@ps_agent_kind"
                        },
                )
                for (window in row.windows) {
                    appendLine(
                        "gateway_window cache_key=${row.sessionName}::${window.index ?: "active"} " +
                            "window_id=${window.windowId} pane_pid=${window.panePid} " +
                            "command=${window.command} resolved_kind=${window.agentKind}",
                    )
                }
            }
        }.also {
            Log.i(TAG, it)
        }
    }

    fun writeArtifact(fileName: String, content: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "issue1973-agent-kind-isolation")
            .apply { mkdirs() }
            .resolve(fileName)
            .writeText(content)
    }

    fun logEvidence(label: String, content: String) {
        Log.i(TAG, "$label\n$content")
    }

    private fun parsePane(line: String): RawPane? {
        val parts = line.split(SEP)
        if (parts.size != 9) return null
        val panePid = parts[6].trim().toLongOrNull() ?: return null
        return RawPane(
            sessionName = parts[0].trim(),
            sessionId = parts[1].trim(),
            sessionCreated = parts[2].trim(),
            windowIndex = parts[3].trim(),
            windowId = parts[4].trim(),
            paneId = parts[5].trim(),
            panePid = panePid,
            command = parts[7].trim(),
            rawRecordedKind = parts[8].trim(),
        )
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private data class RawPane(
        val sessionName: String,
        val sessionId: String,
        val sessionCreated: String,
        val windowIndex: String,
        val windowId: String,
        val paneId: String,
        val panePid: Long,
        val command: String,
        val rawRecordedKind: String,
    )
}
