package com.pocketshell.app.session

import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import kotlinx.coroutines.Job
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream

/**
 * Shared [SshSession] test double for [AgentConversationRepositoryTest] and
 * [AgentConversationRepositoryWindowedReadTest] (the #793/#817/#1225/#1267
 * windowed-read + byte-clamp slice split out of the original single test
 * file — scripts/check-file-size-hygiene.sh's oversized-file ratchet — so
 * both suites share ONE fixture instead of forking it.
 */
internal class FakeSshSession(
    private val sqliteOutput: String = "",
    private val statOutputs: ArrayDeque<String> = ArrayDeque(),
    private val sqliteOutputs: ArrayDeque<String> = ArrayDeque(),
    private val sqliteFailure: Throwable? = null,
    private val detectionOutput: String = "",
    private val paneProcessOutput: String = "",
    private val hostWideProcessOutput: String = "",
    private val wcOutput: String = "0\n",
    private val tailLines: List<String> = emptyList(),
    private val tailFailure: Throwable? = null,
    private val agentLogOutput: String = "",
    // Issue #1467: simulate a host `pocketshell` CLI OLDER than #1267 that
    // does not know `--max-line-bytes` — it rejects the unknown option and
    // exits non-zero, which behind the repository's `2>/dev/null || true`
    // surfaces as EMPTY stdout. The repository must retry the read WITHOUT
    // the clamp flag rather than silently blanking the Codex conversation.
    private val agentLogRejectsMaxLineBytes: Boolean = false,
    private val jsonlTailOutput: String = "",
    private val recordedKindOutput: String = "",
    private val recordedSourceGenerationOutput: String = "",
    private val recordedSourceOutput: String = "",
    private val procFdOutput: String = "",
    // Issue #828: when set, the single-round-trip recorded-open exec emits a
    // folded Claude window section (PATH=<path>, wc -l, sentinel, tail) after
    // the candidate enumeration — the shape the repository's window parse
    // expects. `foldedClaudePath` must equal the resolved source for the
    // prefetch to bind.
    private val foldedClaudePath: String = "",
    private val foldedClaudeWcOutput: String = "0",
    private val foldedClaudeTail: String = "",
    private val emulateFoldedClaudePathFromShell: Boolean = false,
) : SshSession {
    val execCommands = mutableListOf<String>()
    val tailFromLineCalls = mutableListOf<Pair<String, Long>>()
    var tailCalls = 0

    override val isConnected: Boolean = true

    override suspend fun exec(command: String): ExecResult {
        execCommands += command
        val stdout = when {
            // Issue #828: the single-round-trip recorded-open exec folds the
            // `@ps_agent_kind` read + a sentinel + the candidate enumeration
            // (+ for Claude, a window section) into ONE command. Emit them in
            // that shape so the repository can split the kind, the candidate
            // rows, and the prefetched window.
            command.contains("@@PS_RECORDED_KIND@@") -> buildString {
                append(recordedKindOutput.trim())
                append("\n@@PS_RECORDED_KIND@@\n")
                append(recordedSourceGenerationOutput.trim())
                append("\n@@PS_RECORDED_SOURCE_GENERATION@@\n")
                append(recordedSourceOutput.trim())
                append("\n@@PS_RECORDED_SOURCE@@\n")
                append(detectionOutput)
                append("\n@@PS_CLAUDE_WINDOW@@\n")
                val emulatedFoldedPath = if (emulateFoldedClaudePathFromShell) {
                    emulatedFoldedClaudePath(command)
                } else {
                    foldedClaudePath
                }
                if (emulatedFoldedPath.isNotBlank()) {
                    append("PATH=").append(emulatedFoldedPath).append("\n")
                    append(foldedClaudeWcOutput.trim()).append("\n")
                    append("@@PS_CLAUDE_WINDOW@@\n")
                    // Issue #1225: emulate the server-side per-line byte clamp
                    // on the folded prefetch tail so the fold reproduces what
                    // the real host would send (marker for oversized lines).
                    append(applyLineClamp(command, foldedClaudeTail))
                }
            }
            command.contains("show-options -v") && command.contains("@ps_agent_kind") -> recordedKindOutput
            // Issue #2155: the per-detection exec folds the live
            // `@ps_agent_source_generation` / `@ps_agent_source` read ahead
            // of the candidate enumeration, so the recorded source is
            // re-validated against the CURRENT generation with no extra
            // round-trip. Emit both sections in that order.
            command.contains("@@PS_RECORDED_SOURCE@@") &&
                command.contains("claude_dir=") -> buildString {
                append(recordedSourceGenerationOutput.trim())
                append("\n@@PS_RECORDED_SOURCE_GENERATION@@\n")
                append(recordedSourceOutput.trim())
                append("\n@@PS_RECORDED_SOURCE@@\n")
                append(detectionOutput)
            }
            command.contains("@@PS_RECORDED_SOURCE_GENERATION@@") -> buildString {
                append(recordedSourceGenerationOutput.trim())
                append("\n@@PS_RECORDED_SOURCE_GENERATION@@\n")
                append(recordedSourceOutput.trim())
                append("\n@@PS_RECORDED_SOURCE@@\n")
            }
            command.contains("show-options -v") && command.contains("@ps_agent_source") -> recordedSourceOutput
            command.contains("/proc/") && command.contains(".codex/sessions/") -> procFdOutput
            command.contains("claude_dir=") -> detectionOutput
            command.contains("ps -eo pid,ppid,tty,comm,args") -> hostWideProcessOutput
            command.contains("ps -eo pid,tty,comm,args") -> hostWideProcessOutput
            command.contains("ps -t ") -> paneProcessOutput
            command.contains("stat -c '%Y' ") -> statOutputs.removeFirstOrNull() ?: statOutputs.lastOrNull() ?: "0\n"
            // Issue #793: the windowed read combines wc -l + a sentinel + the
            // tail into ONE round-trip. Emit them in that shape so the
            // repository can split total-lines from the tail window.
            command.contains("@@PS_WINDOW@@") ->
                "${wcOutput.trim()}\n@@PS_WINDOW@@\n${applyLineClamp(command, jsonlTailOutput)}"
            // Issue #817: the Codex windowed read folds wc -l + a sentinel +
            // the agent-log window into ONE round-trip so it carries the
            // raw-file line count (the follow cursor) without a separate
            // lineCount exec.
            command.contains("@@PS_CODEX_WINDOW@@") ->
                "${wcOutput.trim()}\n@@PS_CODEX_WINDOW@@\n${agentLogEnvelopeFor(command)}"
            command.contains("wc -l < ") -> wcOutput
            command.contains("pocketshell agent-log") -> agentLogEnvelopeFor(command)
            command.trimStart().startsWith("tail -n") -> applyLineClamp(command, jsonlTailOutput)
            command.contains("sqlite3 -readonly") -> {
                sqliteFailure?.let { throw it }
                // Issue #1267: the OpenCode read now pipes the sqlite output
                // through the same server-side per-line byte clamp; emulate it
                // so an over-cap row degrades to the sentinel exactly as on the
                // real host (identity for normal small rows).
                applyLineClamp(command, sqliteOutputs.removeFirstOrNull() ?: sqliteOutput)
            }
            else -> ""
        }
        return ExecResult(stdout = stdout, stderr = "", exitCode = 0)
    }

    // Issue #1225: faithfully emulate the server-side per-line byte clamp
    // ([transcriptLineClampPipe]) so a byte-bound regression test is a
    // genuine red->green. If the repository's command does NOT pipe through
    // the awk clamp (base code), the raw text is returned unchanged and a
    // multi-MB line balloons the read exactly as on-device. If the command
    // DOES carry the clamp, each line whose UTF-8 byte length exceeds the
    // `-v m=<N>` cap is replaced by the sentinel + byte length, mirroring the
    // real host `LC_ALL=C awk` behaviour.
    private fun applyLineClamp(command: String, text: String): String {
        if (!command.contains("@@PS_LINE_TRUNCATED@@")) return text
        val cap = Regex("-v m=(\\d+)").find(command)?.groupValues?.get(1)?.toIntOrNull()
            ?: return text
        return text.split("\n").joinToString("\n") { line ->
            val bytes = line.toByteArray(Charsets.UTF_8).size
            if (bytes > cap) "@@PS_LINE_TRUNCATED@@$bytes" else line
        }
    }

    // Issue #1267: faithfully emulate the SERVER-SIDE `pocketshell agent-log
    // --max-line-bytes N` clamp (agent_log.py `_clamp_line_bytes`). The Codex
    // read goes through the tool, not the `awk` pipe, so the byte clamp lives
    // in the tool: each element of the envelope's `lines` array whose UTF-8
    // byte length exceeds N is replaced by `@@PS_LINE_TRUNCATED@@<bytes>`
    // before the envelope is serialised. When the command does NOT carry
    // `--max-line-bytes` (base code), the envelope is returned unchanged and a
    // multi-MB line balloons the read exactly as on-device — a genuine
    // red->green. A blank/unparseable envelope is passed through untouched.
    // Issue #1467: an OLD host CLI rejects `--max-line-bytes` and, behind
    // `2>/dev/null || true`, returns empty stdout. Otherwise the envelope is
    // emitted (with the server-side clamp applied when the flag is present).
    private fun agentLogEnvelopeFor(command: String): String =
        if (agentLogRejectsMaxLineBytes && command.contains("--max-line-bytes")) {
            ""
        } else {
            applyAgentLogEnvelopeClamp(command, agentLogOutput)
        }

    private fun applyAgentLogEnvelopeClamp(command: String, envelope: String): String {
        val cap = Regex("--max-line-bytes (\\d+)").find(command)
            ?.groupValues?.get(1)?.toIntOrNull() ?: return envelope
        val json = runCatching { JSONObject(envelope) }.getOrNull() ?: return envelope
        val lines = json.optJSONArray("lines") ?: return envelope
        val clamped = JSONArray()
        for (index in 0 until lines.length()) {
            val line = lines.optString(index)
            val bytes = line.toByteArray(Charsets.UTF_8).size
            clamped.put(if (bytes > cap) "@@PS_LINE_TRUNCATED@@$bytes" else line)
        }
        json.put("lines", clamped)
        return json.toString()
    }

    private fun emulatedFoldedClaudePath(command: String): String {
        val recordedSource = parsedRecordedSource()
        if (recordedSource.isNotBlank() && command.contains("ps_recorded_source_path")) {
            return recordedSource
        }
        return newestClaudeCandidatePath()
    }

    private fun parsedRecordedSource(): String {
        val raw = recordedSourceOutput.trim()
        if (raw.isBlank()) return ""
        val generation = recordedSourceGenerationOutput.trim()
        if (generation.isNotBlank()) {
            val prefix = "$generation\t"
            return raw.removePrefix(prefix)
                .takeIf { it != raw }
                ?.trim()
                .orEmpty()
        }
        val tabIndex = raw.indexOf('\t')
        return if (tabIndex >= 0) {
            raw.substring(tabIndex + 1).trim()
        } else {
            raw
        }
    }

    private fun newestClaudeCandidatePath(): String =
        detectionOutput
            .lineSequence()
            .mapNotNull { line ->
                val parts = line.trim().split("|", limit = 4)
                if (parts.size == 4 && parts[0] == "claude") {
                    parts[1].toLongOrNull()?.let { modifiedAt -> modifiedAt to parts[3] }
                } else {
                    null
                }
            }
            .maxByOrNull { it.first }
            ?.second
            .orEmpty()

    override fun tail(path: String, onLine: (String) -> Unit): Job {
        tailCalls += 1
        tailFailure?.let { throw it }
        tailLines.forEach(onLine)
        return Job()
    }

    override fun tail(path: String, fromLineExclusive: Long, onLine: (String) -> Unit): Job {
        tailFromLineCalls += path to fromLineExclusive
        tailCalls += 1
        tailFailure?.let { throw it }
        tailLines.forEach(onLine)
        return Job()
    }

    override fun openLocalPortForward(
        remoteHost: String,
        remotePort: Int,
        localPort: Int,
    ): SshPortForward {
        throw NotImplementedError()
    }

    override fun startShell(): SshShell {
        throw NotImplementedError()
    }

    override suspend fun uploadFile(file: File, remotePath: String): String =
        error("uploadFile not used in this test")

    override suspend fun uploadStream(
        input: InputStream,
        length: Long,
        name: String,
        remotePath: String,
    ): String = error("uploadStream not used in this test")

    override fun close() = Unit
}
