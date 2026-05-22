package com.pocketshell.app.session

import com.pocketshell.core.agents.AgentDetection
import com.pocketshell.core.agents.AgentDetector
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.agents.AgentLogCandidate
import com.pocketshell.core.agents.ClaudeCodeParser
import com.pocketshell.core.agents.CodexParser
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.agents.ConversationParser
import com.pocketshell.core.ssh.SshSession
import kotlinx.coroutines.Job

internal class AgentConversationRepository(
    private val detector: AgentDetector = AgentDetector(),
) {
    suspend fun detect(
        session: SshSession,
        cwd: String,
        processHints: List<String> = emptyList(),
    ): AgentDetection? {
        val normalizedCwd = cwd.trim().ifBlank { return null }
        val nowMillis = System.currentTimeMillis()
        val candidates = session.exec(detectionCommand(normalizedCwd))
            .stdout
            .lineSequence()
            .mapNotNull(::parseCandidate)
            .toList()
        val processLines = processHints + session.exec(
            "ps -eo pid,ppid,comm,args 2>/dev/null | grep -E 'claude' | grep -v grep || true",
        )
            .stdout
            .lines()

        return detector.detect(normalizedCwd, nowMillis, candidates, processLines)
    }

    suspend fun detect(session: SshSession): AgentDetection? {
        // A fresh SSH exec channel cannot observe the live interactive
        // shell's current directory after the user has cd'd. Non-tmux
        // sessions therefore have no reliable cwd source for #23's
        // cwd-correlated agent detection. Tmux callers pass
        // #{pane_current_path} to detect(session, cwd, ...), which remains
        // supported.
        return null
    }

    suspend fun readInitialEvents(
        session: SshSession,
        detection: AgentDetection,
        maxLines: Int = 200,
    ): List<ConversationEvent> {
        val parser = parserFor(detection.agent) ?: return emptyList()
        val result = session.exec("tail -n $maxLines ${shellQuote(detection.sourcePath)} 2>/dev/null || true")
        return result.stdout.lineSequence().flatMap { parser.parseLine(it) }.toList()
    }

    fun tailEvents(
        session: SshSession,
        detection: AgentDetection,
        onEvent: (ConversationEvent) -> Unit,
    ): Job? {
        if (detection.agent == AgentKind.OpenCode) {
            return null
        }
        val parser = parserFor(detection.agent) ?: return null
        return session.tail(detection.sourcePath) { line ->
            parser.parseLine(line).forEach(onEvent)
        }
    }

    suspend fun lineCount(session: SshSession, detection: AgentDetection): Long =
        if (detection.agent == AgentKind.OpenCode) {
            0L
        } else {
            session.exec("wc -l < ${shellQuote(detection.sourcePath)} 2>/dev/null || printf 0")
                .stdout
                .trim()
                .toLongOrNull() ?: 0L
        }

    fun tailEventsFromLine(
        session: SshSession,
        detection: AgentDetection,
        fromLineExclusive: Long,
        onEvent: (ConversationEvent) -> Unit,
    ): Job? {
        val parser = parserFor(detection.agent) ?: return null
        return session.tail(detection.sourcePath, fromLineExclusive) { line ->
            parser.parseLine(line).forEach(onEvent)
        }
    }

    suspend fun pollOpenCodeEvents(
        session: SshSession,
        detection: AgentDetection,
        maxLines: Int = 200,
    ): List<ConversationEvent> = emptyList()

    private fun parserFor(agent: AgentKind): ConversationParser? = when (agent) {
        AgentKind.ClaudeCode -> ClaudeCodeParser()
        AgentKind.Codex -> CodexParser()
        AgentKind.OpenCode -> null
    }

    internal fun detectionCommand(cwd: String): String {
        val encodedClaudeCwd = detector.encodeClaudeCwd(cwd)
        val quotedCwd = shellQuote(cwd)
        return """
            cwd=$quotedCwd
            claude_dir="${'$'}HOME/.claude/projects/$encodedClaudeCwd"
            find "${'$'}claude_dir" -maxdepth 1 -type f -name '*.jsonl' -mmin -5 -print 2>/dev/null | while IFS= read -r f; do
              mtime=${'$'}(stat -c '%Y' "${'$'}f" 2>/dev/null) || continue
              printf 'claude|%s|%s|%s\n' "${'$'}mtime" "${'$'}cwd" "${'$'}f"
            done
        """.trimIndent()
    }

    private fun parseCandidate(line: String): AgentLogCandidate? {
        val parts = line.split("|", limit = 4)
        if (parts.size != 4) return null
        val agent = when (parts[0]) {
            "claude" -> AgentKind.ClaudeCode
            "codex" -> return null
            "opencode" -> return null
            else -> return null
        }
        val seconds = parts[1].toDoubleOrNull() ?: return null
        val cwd = parts[2]
        val path = parts[3]
        return AgentLogCandidate(
            agent = agent,
            path = path,
            modifiedAtMillis = (seconds * 1000).toLong(),
            sessionId = path.substringAfterLast('/').substringBeforeLast('.'),
            cwd = cwd,
        )
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
