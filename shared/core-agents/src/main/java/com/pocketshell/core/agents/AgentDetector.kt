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
    // Issue #236: the previous 5-minute recency window killed real-world
    // Codex/OpenCode detection. Codex flushes its rollout JSONL only on
    // turn completion, so a user attached to an idle Codex TUI for >5
    // minutes had zero candidates surviving the recency filter and the
    // Conversation tab never appeared. The Docker fixture papered over
    // this by `touch`ing the JSONL inside the test, so #183's "3/3 pass"
    // was real but unrealistic.
    //
    // 120 minutes (2 h) is long enough to cover the common attach-to-
    // existing scenarios (reattaching after a break, multi-tool turn
    // running for >5 min, long stretches of TUI idle without a turn
    // completion) while short enough that an abandoned tmux pane from
    // earlier in the day doesn't ghost-detect once its agent has long
    // since exited. The matching `-mmin -120` in the shell-side `find`
    // bounds the candidate set the same way.
    //
    // Claude Code is unaffected by this bump in practice — its candidate
    // discovery is already cwd-scoped under `~/.claude/projects/<cwd>/`,
    // and Claude streams JSONL writes continuously (not only at turn
    // completion). The wider window simply doesn't filter anything out
    // for Claude that the tighter window did.
    private val recentWindowMillis: Long = 120 * 60 * 1000L,
    private val futureSkewToleranceMillis: Long = 5 * 60 * 1000L,
) {
    /**
     * Returns the best matching log candidate as an [AgentDetection], or
     * `null` if no candidate satisfies the recency + path-hint filter.
     * For the default session-scoped path, the most-recent candidate
     * wins. When [requireProcessMatch] is `true`, process-confirmed
     * candidates are preferred over unconfirmed recent files; within
     * each group the most-recent candidate wins.
     *
     * Confidence is `ProcessConfirmed` when [processLines] contains a row
     * naming the same agent; otherwise `RecentFile`.
     *
     * Issue #186: when [requireProcessMatch] is `true`, the detector
     * additionally requires that the agent's command name appear in
     * [processLines] before returning a non-null detection. Per-pane
     * callers ([com.pocketshell.app.session.AgentConversationRepository.detectForPane])
     * pass a TTY-scoped process list and set this flag so a JSONL file
     * created by a sibling window (which shares the cwd) does not light
     * up the Conversation tab on a window where no agent is actually
     * running. Session-scoped callers pass `false` (default) and accept
     * the looser `RecentFile` confidence when the process scan misses.
     */
    public fun detect(
        cwd: String,
        nowMillis: Long,
        candidates: List<AgentLogCandidate>,
        processLines: List<String>,
        requireProcessMatch: Boolean = false,
    ): AgentDetection? {
        val normalizedCwd = normalizeCwd(cwd)
        val expected = expectedPathHints(normalizedCwd)
        val matchingCandidates = candidates
            .filter { nowMillis - it.modifiedAtMillis in -futureSkewToleranceMillis..recentWindowMillis }
            .filter { candidate ->
                expected[candidate.agent]?.any { candidate.path.contains(it) } ?: false
            }
        val ranked = matchingCandidates.map { candidate ->
            candidate to processLines.any { line -> line.namesAgent(candidate.agent) }
        }
        val selected = if (requireProcessMatch) {
            ranked
                .filter { (_, confirmed) -> confirmed }
                .maxByOrNull { (candidate, _) -> candidate.modifiedAtMillis }
                ?: return null
        } else {
            ranked
                .maxWithOrNull(
                    compareBy<Pair<AgentLogCandidate, Boolean>> { (_, confirmed) -> confirmed }
                        .thenBy { (candidate, _) -> candidate.modifiedAtMillis },
                )
        } ?: return null
        val recent = selected.first
        val confirmed = selected.second
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
     * - **OpenCode**: global SQLite at
     *   `~/.local/share/opencode/opencode.db`. The candidate emitter
     *   scopes rows by `session.directory` / `project.worktree` before
     *   passing a `opencode.db#<session-id>` source path to the detector.
     */
    public fun expectedPathHints(cwd: String): Map<AgentKind, List<String>> = mapOf(
        AgentKind.ClaudeCode to listOf(".claude/projects/${encodeClaudeCwd(cwd)}"),
        AgentKind.Codex to listOf(".codex/sessions/"),
        AgentKind.OpenCode to listOf(".local/share/opencode/opencode.db"),
    )

    /**
     * Encodes a working directory into the directory name Claude Code uses
     * under `~/.claude/projects/<encoded-cwd>/`.
     *
     * Issue #820: Claude replaces BOTH path separators (`/`) **and** dots
     * (`.`) with `-`. The previous implementation only replaced `/`, so a
     * cwd containing a dot (a dotdir like `/home/user/.claude`, a versioned
     * dir like `app-1.2`, or a worktree path with a dot) produced an
     * encoded name that diverged from Claude's real one — the resolver
     * looked in a directory that doesn't exist, found no transcript, and
     * the Conversation tab hard-failed ("Couldn't load this conversation.")
     * after the 12 s detection watchdog. Empirically on a real box,
     * `/home/alexey/git/.claude` → `-home-alexey-git--claude` (the dot
     * becomes a dash, yielding a double dash). Every other character
     * (letters, digits, underscores, existing hyphens) is preserved.
     */
    public fun encodeClaudeCwd(cwd: String): String =
        cwd.trim().replace('/', '-').replace('.', '-').ifBlank { "-" }

    private fun normalizeCwd(cwd: String): String =
        cwd.trim().trimEnd('/').ifBlank { "/" }

    private fun String.namesAgent(agent: AgentKind): Boolean {
        val lower = lowercase()
        return when (agent) {
            AgentKind.ClaudeCode -> lower.containsCommandToken("claude(?:-?code)?")
            AgentKind.Codex -> lower.containsCommandToken("codex")
            AgentKind.OpenCode -> lower.containsCommandToken("open[-_]?code(?:[-_][a-z0-9]+)?")
        }
    }

    private fun String.containsCommandToken(commandPattern: String): Boolean =
        Regex("(^|[\\s/|;&('\"`])$commandPattern(?=$|[\\s/|;&):'\"`])").containsMatchIn(this)
}
