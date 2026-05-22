package com.pocketshell.core.agents

public data class AgentLogCandidate(
    val agent: AgentKind,
    val path: String,
    val modifiedAtMillis: Long,
    val sessionId: String? = null,
    val cwd: String? = null,
)

public data class AgentDetection(
    val agent: AgentKind,
    val sourcePath: String,
    val sessionId: String?,
    val confidence: Confidence,
) {
    public enum class Confidence { RecentFile, ProcessConfirmed }
}

public class AgentDetector(
    private val recentWindowMillis: Long = 5 * 60 * 1000L,
) {
    public fun detect(
        cwd: String,
        nowMillis: Long,
        candidates: List<AgentLogCandidate>,
        processLines: List<String>,
    ): AgentDetection? {
        val normalizedCwd = normalizeCwd(cwd)
        val expected = expectedPathHints(normalizedCwd)
        val recent = candidates
            .filter { nowMillis - it.modifiedAtMillis in 0..recentWindowMillis }
            .filter { candidate ->
                when (candidate.agent) {
                    AgentKind.Codex -> false
                    AgentKind.ClaudeCode -> expected[candidate.agent]?.any { candidate.path.contains(it) } ?: false
                    AgentKind.OpenCode -> false
                }
            }
            .maxByOrNull { it.modifiedAtMillis }
            ?: return null
        val confirmed = processLines.any { line -> line.namesAgent(recent.agent) }
        return AgentDetection(
            agent = recent.agent,
            sourcePath = recent.path,
            sessionId = recent.sessionId ?: recent.path.substringAfterLast('/').substringBeforeLast('.'),
            confidence = if (confirmed) {
                AgentDetection.Confidence.ProcessConfirmed
            } else {
                AgentDetection.Confidence.RecentFile
            },
        )
    }

    public fun expectedPathHints(cwd: String): Map<AgentKind, List<String>> = mapOf(
        AgentKind.ClaudeCode to listOf(".claude/projects/${encodeClaudeCwd(cwd)}"),
    )

    public fun encodeClaudeCwd(cwd: String): String =
        cwd.trim().replace('/', '-').ifBlank { "-" }

    private fun normalizeCwd(cwd: String): String =
        cwd.trim().trimEnd('/').ifBlank { "/" }

    private fun String.namesAgent(agent: AgentKind): Boolean {
        val lower = lowercase()
        return when (agent) {
            AgentKind.ClaudeCode -> lower.contains("claude")
            AgentKind.Codex -> lower.contains("codex")
            AgentKind.OpenCode -> lower.contains("opencode")
        }
    }
}
