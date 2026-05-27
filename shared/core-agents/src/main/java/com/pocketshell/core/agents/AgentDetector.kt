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
                expected[candidate.agent]?.any { candidate.path.contains(it) } ?: false
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

    /**
     * Per-engine substrings that a candidate's `path` must contain to be
     * considered a plausible JSONL log for that engine. The detector's
     * filter only allows candidates whose path matches one of these hints;
     * unrelated files (e.g. an OpenCode global SQLite, a sibling project's
     * Claude project directory) are rejected.
     *
     * Engine path conventions:
     *
     * - **Claude Code**: `~/.claude/projects/<encoded-cwd>/<session>.jsonl`.
     *   The cwd-encoding pins the candidate to the active pane's directory.
     * - **Codex (OpenAI)**: `~/.codex/sessions/.../<rollout>.jsonl`. The
     *   Codex CLI organises rollouts under a date-keyed tree (e.g.
     *   `~/.codex/sessions/2026/05/22/rollout-<uuid>.jsonl`) but the
     *   `.codex/sessions/` prefix is stable across versions. Codex stores
     *   the originating cwd as part of the rollout payload; the upstream
     *   candidate-emission step is responsible for filtering to the active
     *   pane's project before handing rows to the detector.
     * - **OpenCode**: historically a global SQLite at
     *   `~/.local/share/opencode/opencode.db`, but the deterministic
     *   PocketShell fixture and tail-friendly newer releases use
     *   `~/.local/share/opencode/<name>.jsonl` rows. The
     *   `.local/share/opencode/` prefix matches both. As with Codex, the
     *   candidate emitter must verify the row's project/cwd before
     *   treating it as the active pane's conversation log.
     */
    public fun expectedPathHints(cwd: String): Map<AgentKind, List<String>> = mapOf(
        AgentKind.ClaudeCode to listOf(".claude/projects/${encodeClaudeCwd(cwd)}"),
        AgentKind.Codex to listOf(".codex/sessions/"),
        AgentKind.OpenCode to listOf(".local/share/opencode/"),
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
