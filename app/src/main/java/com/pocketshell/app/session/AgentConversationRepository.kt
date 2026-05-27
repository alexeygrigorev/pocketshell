package com.pocketshell.app.session

import com.pocketshell.core.agents.AgentDetection
import com.pocketshell.core.agents.AgentDetector
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.agents.AgentLogCandidate
import com.pocketshell.core.agents.ClaudeCodeParser
import com.pocketshell.core.agents.CodexParser
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.agents.ConversationParser
import com.pocketshell.core.agents.ConversationRole
import com.pocketshell.core.agents.OpenCodeReader
import com.pocketshell.core.ssh.SshSession
import kotlinx.coroutines.Job

/**
 * Issue #160 (review round 2): every locally-inserted "optimistic" user
 * message — added by `sendToAgent` so the conversation pane updates
 * before the agent's JSONL has been appended and tailed back — uses an
 * id that starts with this prefix. The dedup pass in
 * [reconcileAgentEvents] uses that to recognise the placeholder so it
 * can drop it when the real `Message(role=User)` event eventually
 * arrives via the tail.
 *
 * Strategy B from the issue brief: optimistic events are tagged at
 * insertion time and removed when the next non-optimistic user message
 * with the same text arrives. This is robust to:
 *
 *  - id-format drift across CLI updates (Claude / Codex / OpenCode all
 *    mint their own ids; we don't try to predict them),
 *  - back-to-back duplicate prompts (each optimistic gets its own
 *    nanoTime-tagged id, so a real arrival only collapses one),
 *  - parser additions (the dedup contract only inspects the role + text
 *    + presence of the optimistic prefix).
 */
internal const val OPTIMISTIC_USER_MESSAGE_ID_PREFIX: String = "optimistic:"

/**
 * Issue #160 (review round 2): single source of truth for the
 * conversation-feed dedup contract used by both
 * [com.pocketshell.app.session.SessionViewModel] (raw-SSH session) and
 * [com.pocketshell.app.tmux.TmuxSessionViewModel] (tmux pane).
 *
 * Combines three rules in a single pass over [events] (in insertion
 * order):
 *
 *  1. **Optimistic reconciliation.** When a non-optimistic
 *     `Message(role=User)` arrives and the accumulator already contains
 *     an optimistic `Message(role=User)` (id starts with
 *     [OPTIMISTIC_USER_MESSAGE_ID_PREFIX]) with the same text, that
 *     optimistic entry is dropped — the real event is the authoritative
 *     record now that the agent's JSONL has it.
 *  2. **Id dedup.** Subsequent events with the same id replace earlier
 *     ones (the legacy [LinkedHashMap] semantics — useful for
 *     streamed `Message`s whose text gets updated incrementally and
 *     for re-emitted tool-call rows).
 *  3. **Tail bound.** The result is bounded to the latest [maxEvents]
 *     events (default [DEFAULT_MAX_AGENT_EVENTS]). This keeps the
 *     conversation pane from growing without limit on long-lived
 *     sessions and matches the previous in-VM bound.
 *
 * Time-windowing is intentionally NOT used. Optimistic events are
 * always inserted *before* the real one (the round trip cannot complete
 * faster than the local synchronous append), so order-based matching is
 * correct and simpler than chasing wall-clock skew between the Android
 * device and the remote.
 */
internal fun reconcileAgentEvents(
    events: List<ConversationEvent>,
    maxEvents: Int = DEFAULT_MAX_AGENT_EVENTS,
): List<ConversationEvent> {
    if (events.isEmpty()) return events
    val byId = LinkedHashMap<String, ConversationEvent>()
    // Mirror the pre-existing safety net: never inspect more than
    // 2 * maxEvents historic rows on a single reconcile call (callers
    // append new events at the tail; older events have already been
    // bounded on previous passes).
    val window = events.takeLast(maxEvents * 2)
    for (event in window) {
        if (event is ConversationEvent.Message &&
            event.role == ConversationRole.User &&
            !event.isOptimistic()
        ) {
            // Drop any prior optimistic entry with matching text.
            val matchingOptimistic = byId.entries.firstOrNull { (_, candidate) ->
                candidate is ConversationEvent.Message &&
                    candidate.role == ConversationRole.User &&
                    candidate.isOptimistic() &&
                    candidate.text == event.text
            }
            if (matchingOptimistic != null) {
                byId.remove(matchingOptimistic.key)
            }
        }
        byId[event.id] = event
    }
    val distinct = byId.values.toList()
    return if (distinct.size <= maxEvents) {
        distinct
    } else {
        distinct.subList(distinct.size - maxEvents, distinct.size)
    }
}

private fun ConversationEvent.Message.isOptimistic(): Boolean =
    id.startsWith(OPTIMISTIC_USER_MESSAGE_ID_PREFIX)

/**
 * Default upper bound on the events kept by the conversation feed.
 * The constant matches the legacy in-VM `MaxAgentEvents` value so the
 * bounded-distinct contract stays unchanged for non-optimistic callers.
 */
internal const val DEFAULT_MAX_AGENT_EVENTS: Int = 500

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
        // Issue #183: extend the process scan to recognise Codex and
        // OpenCode foreground processes in addition to Claude. The
        // detector only promotes a candidate to `ProcessConfirmed`
        // when the engine's command name appears in the scan output;
        // before this fix the scan was Claude-only, so Codex/OpenCode
        // detections silently stayed at `RecentFile` even when the
        // agent was actively running.
        val processLines = processHints + session.exec(
            "ps -eo pid,ppid,comm,args 2>/dev/null | grep -E 'claude|codex|opencode' | grep -v grep || true",
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
        // Issue #160: OpenCode now tails its JSONL via `session.tail`
        // identically to Claude and Codex. Polling has been removed —
        // the row shape is the same per-line JSON envelope used by the
        // batch [OpenCodeReader.parseRows] path (see [parserFor]).
        val parser = parserFor(detection.agent) ?: return null
        return session.tail(detection.sourcePath) { line ->
            parser.parseLine(line).forEach(onEvent)
        }
    }

    suspend fun lineCount(session: SshSession, detection: AgentDetection): Long =
        session.exec("wc -l < ${shellQuote(detection.sourcePath)} 2>/dev/null || printf 0")
            .stdout
            .trim()
            .toLongOrNull() ?: 0L

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

    /**
     * Issue #160 (OpenCode parity): retained as a deprecated no-op
     * shim while callers in
     * [com.pocketshell.app.session.SessionViewModel] and
     * [com.pocketshell.app.tmux.TmuxSessionViewModel] migrate to the
     * unified [tailEventsFromLine] path. Production code should not
     * poll OpenCode any more — the `session.tail` route catches up on
     * appended lines in real time.
     */
    @Deprecated(
        message = "Use tailEventsFromLine — OpenCode now tails like Claude + Codex.",
        replaceWith = ReplaceWith("tailEventsFromLine(session, detection, 0, onEvent)"),
    )
    suspend fun pollOpenCodeEvents(
        session: SshSession,
        detection: AgentDetection,
        maxLines: Int = 200,
    ): List<ConversationEvent> = emptyList()

    private fun parserFor(agent: AgentKind): ConversationParser? = when (agent) {
        AgentKind.ClaudeCode -> ClaudeCodeParser()
        AgentKind.Codex -> CodexParser()
        AgentKind.OpenCode -> OpenCodeReader()
    }

    internal fun detectionCommand(cwd: String): String {
        val encodedClaudeCwd = detector.encodeClaudeCwd(cwd)
        val quotedCwd = shellQuote(cwd)
        // Issue #183: enumerate JSONL candidates for every supported
        // engine. Each engine's discovery walks its conventional log
        // directory and emits one PSV row per recently-modified file
        // (`agent|epoch-seconds|cwd|path`). The detector then runs the
        // engine-specific path-hint filter (see
        // [AgentDetector.expectedPathHints]) to pick the most recent
        // matching candidate.
        //
        // Codex's `.codex/sessions/` tree is date-keyed (e.g.
        // `~/.codex/sessions/2026/05/22/rollout-<uuid>.jsonl`), so the
        // find walks the full subtree. OpenCode rows live one level
        // deep under `~/.local/share/opencode/`, including the legacy
        // `opencode.db` SQLite (filtered out — only `*.jsonl` are
        // tailable). Each branch is best-effort: missing directories
        // (e.g. user has never run Codex) silently emit nothing.
        return """
            cwd=$quotedCwd
            claude_dir="${'$'}HOME/.claude/projects/$encodedClaudeCwd"
            codex_dir="${'$'}HOME/.codex/sessions"
            opencode_dir="${'$'}HOME/.local/share/opencode"
            find "${'$'}claude_dir" -maxdepth 1 -type f -name '*.jsonl' -mmin -5 -print 2>/dev/null | while IFS= read -r f; do
              mtime=${'$'}(stat -c '%Y' "${'$'}f" 2>/dev/null) || continue
              printf 'claude|%s|%s|%s\n' "${'$'}mtime" "${'$'}cwd" "${'$'}f"
            done
            find "${'$'}codex_dir" -type f -name '*.jsonl' -mmin -5 -print 2>/dev/null | while IFS= read -r f; do
              mtime=${'$'}(stat -c '%Y' "${'$'}f" 2>/dev/null) || continue
              printf 'codex|%s|%s|%s\n' "${'$'}mtime" "${'$'}cwd" "${'$'}f"
            done
            find "${'$'}opencode_dir" -maxdepth 1 -type f -name '*.jsonl' -mmin -5 -print 2>/dev/null | while IFS= read -r f; do
              mtime=${'$'}(stat -c '%Y' "${'$'}f" 2>/dev/null) || continue
              printf 'opencode|%s|%s|%s\n' "${'$'}mtime" "${'$'}cwd" "${'$'}f"
            done
        """.trimIndent()
    }

    private fun parseCandidate(line: String): AgentLogCandidate? {
        val parts = line.split("|", limit = 4)
        if (parts.size != 4) return null
        // Issue #183: accept rows for every supported engine. The
        // engine-specific path-hint filter inside [AgentDetector.detect]
        // continues to reject rows that don't live under the right
        // directory tree (e.g. a stray `*.jsonl` outside
        // `~/.codex/sessions/`).
        val agent = when (parts[0]) {
            "claude" -> AgentKind.ClaudeCode
            "codex" -> AgentKind.Codex
            "opencode" -> AgentKind.OpenCode
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
