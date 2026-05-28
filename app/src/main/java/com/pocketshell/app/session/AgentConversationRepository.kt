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
import com.pocketshell.core.ssh.SshException
import com.pocketshell.core.ssh.SshSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

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
    private val tailScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val openCodePollIntervalMillis: Long = 2_000L,
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

    /**
     * Issue #186: per-window agent detection. Scopes the process scan to
     * **this pane's TTY** (instead of doing a host-wide
     * `ps -eo pid,ppid,comm,args | grep -E 'claude|codex|opencode'`) so a
     * JSONL log written by an agent running in a sibling window does NOT
     * register as a detection on a plain-shell pane that just happens to
     * share the same cwd.
     *
     * Concretely: the maintainer's v0.2.8 feedback report had a 3-window
     * tmux session where only Window 1 ran Claude. Pre-#186, Windows 2
     * and 3 also saw the Conversation tab + "Claude Code session
     * detected" hint because [detect] runs a host-wide process scan and
     * the JSONL file is shared across the same cwd. This entry point
     * fixes that by:
     *
     *  1. Restricting the process scan to processes whose controlling
     *     terminal is [paneTty] (e.g. `/dev/pts/3`). `ps -t <tty>` is
     *     the standard POSIX selector for "processes on this TTY".
     *  2. Including the pane's foreground process name ([paneCommand],
     *     i.e. `#{pane_current_command}` from `list-panes`) so callers
     *     that have already paid for a `list-panes` query don't need a
     *     second round-trip for that signal.
     *  3. Passing `requireProcessMatch = true` to [AgentDetector.detect]
     *     so a recent JSONL alone is not enough — the agent process
     *     must actually be live on THIS pane's TTY.
     *
     * Callers that want session-scoped (looser) detection should keep
     * using [detect]; this method is intended for the tmux per-pane path
     * in [com.pocketshell.app.tmux.TmuxSessionViewModel.startAgentDetectionForPane].
     *
     * @param paneTty value of `#{pane_tty}` from tmux's `list-panes`,
     *   e.g. `/dev/pts/3`. When blank, the detection is suppressed
     *   entirely — without a TTY there is no way to scope the process
     *   scan, and a per-pane caller without a TTY signal is by
     *   construction not a candidate for agent attribution.
     * @param paneCommand value of `#{pane_current_command}` from tmux,
     *   forwarded as an additional process-name hint. Most Node-based
     *   CLIs report as `node` here, so the TTY-scoped `ps` is the
     *   primary signal; the pane command is best-effort.
     */
    suspend fun detectForPane(
        session: SshSession,
        cwd: String,
        paneTty: String,
        paneCommand: String,
    ): AgentDetection? {
        val normalizedCwd = cwd.trim().ifBlank { return null }
        val normalizedTty = paneTty.trim().ifBlank { return null }
        val nowMillis = System.currentTimeMillis()
        val candidates = session.exec(detectionCommand(normalizedCwd))
            .stdout
            .lineSequence()
            .mapNotNull(::parseCandidate)
            .toList()
        // Per-pane process list. We strip any leading `/dev/` from the
        // tty because `ps -t` accepts both `pts/3` and `/dev/pts/3` on
        // GNU/BSD `ps`, but the unprefixed form is portable across
        // every `ps` variant. Tmux usually reports the full path; we
        // normalise here to keep the contract loose for callers.
        val ttyArg = normalizedTty.removePrefix("/dev/")
        val paneProcesses = session.exec(
            "ps -t ${shellQuote(ttyArg)} -o pid,comm,args 2>/dev/null || true",
        )
            .stdout
            .lines()
            // Drop blank trailing rows and the ps header row.
            .filter { it.isNotBlank() && !it.trimStart().startsWith("PID") }
        // The pane's foreground process name is a cheap signal we
        // already have from `list-panes` — merge it in so callers that
        // wrap a JS-based agent in a shell wrapper still register
        // (the `comm` column on `ps -t` reports `node` for Claude /
        // Codex / OpenCode in their Node form, but `args` carries the
        // wrapper command name, which `namesAgent` already greps for).
        val processLines = if (paneCommand.isBlank()) {
            paneProcesses
        } else {
            paneProcesses + paneCommand
        }
        return detector.detect(
            cwd = normalizedCwd,
            nowMillis = nowMillis,
            candidates = candidates,
            processLines = processLines,
            requireProcessMatch = true,
        )
    }

    suspend fun readInitialEvents(
        session: SshSession,
        detection: AgentDetection,
        maxLines: Int = 200,
    ): List<ConversationEvent> {
        if (detection.agent == AgentKind.OpenCode) {
            val output = exportOpenCodeSqliteRows(session, detection, maxMessages = maxLines)
            return OpenCodeReader().parseSqliteJsonRows(output)
        }
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
            return tailEventsFromLine(session, detection, fromLineExclusive = 0L, onEvent)
        }
        val parser = parserFor(detection.agent) ?: return null
        return session.tail(detection.sourcePath) { line ->
            parser.parseLine(line).forEach(onEvent)
        }
    }

    suspend fun lineCount(session: SshSession, detection: AgentDetection): Long =
        session.exec(
            if (detection.agent == AgentKind.OpenCode) {
                "(stat -c '%Y' ${shellQuote(openCodeDbPath(detection))} 2>/dev/null || stat -f '%m' ${shellQuote(openCodeDbPath(detection))} 2>/dev/null || printf 0)"
            } else {
                "wc -l < ${shellQuote(detection.sourcePath)} 2>/dev/null || printf 0"
            },
        )
            .stdout
            .trim()
            .toLongOrNull() ?: 0L

    fun tailEventsFromLine(
        session: SshSession,
        detection: AgentDetection,
        fromLineExclusive: Long,
        onEvent: (ConversationEvent) -> Unit,
    ): Job? {
        if (detection.agent == AgentKind.OpenCode) {
            val sessionId = openCodeSessionId(detection) ?: return null
            return tailScope.launch {
                val reader = OpenCodeReader()
                val emittedEvents = linkedMapOf<String, ConversationEvent>()
                while (isActive) {
                    val output = try {
                        exportOpenCodeSqliteRows(
                            session = session,
                            detection = detection,
                            sessionId = sessionId,
                            maxMessages = DEFAULT_MAX_AGENT_EVENTS * 2,
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: SshException) {
                        return@launch
                    } catch (_: IOException) {
                        return@launch
                    }
                    val snapshotEvents = reader.parseSqliteJsonRows(output)
                    val snapshotIds = snapshotEvents.mapTo(mutableSetOf()) { it.id }
                    // Emit the first snapshot instead of seeding it as "seen":
                    // rows inserted between the initial read and tail startup
                    // must still reach the UI, where reconciliation replaces
                    // same-id events.
                    snapshotEvents.forEach { event ->
                        if (emittedEvents[event.id] != event) {
                            emittedEvents.remove(event.id)
                            emittedEvents[event.id] = event
                            onEvent(event)
                        }
                    }
                    val iterator = emittedEvents.entries.iterator()
                    while (iterator.hasNext()) {
                        if (iterator.next().key !in snapshotIds) {
                            iterator.remove()
                        }
                    }
                    delay(openCodePollIntervalMillis)
                }
            }
        }
        val parser = parserFor(detection.agent) ?: return null
        return session.tail(detection.sourcePath, fromLineExclusive) { line ->
            parser.parseLine(line).forEach(onEvent)
        }
    }

    private suspend fun exportOpenCodeSqliteRows(
        session: SshSession,
        detection: AgentDetection,
        maxMessages: Int,
    ): String {
        val sessionId = openCodeSessionId(detection) ?: return ""
        return exportOpenCodeSqliteRows(session, detection, sessionId, maxMessages)
    }

    private suspend fun exportOpenCodeSqliteRows(
        session: SshSession,
        detection: AgentDetection,
        sessionId: String,
        maxMessages: Int,
    ): String {
        val dbPath = openCodeDbPath(detection)
        val boundedMaxMessages = maxMessages.coerceAtLeast(1)
        val query = """
            WITH recent_messages AS (
              SELECT *
              FROM message
              WHERE session_id = ${sqlQuote(sessionId)}
              ORDER BY COALESCE(time_updated, time_created) DESC, time_created DESC, id DESC
              LIMIT $boundedMaxMessages
            )
            SELECT json_object(
              'message_id', m.id,
              'message_data', m.data,
              'message_time_created', m.time_created,
              'message_time_updated', m.time_updated,
              'part_id', p.id,
              'part_data', p.data,
              'part_time_created', p.time_created
            )
            FROM recent_messages m
            LEFT JOIN part p ON p.message_id = m.id
            ORDER BY m.time_created, m.id, p.time_created, p.id;
        """.trimIndent().replace("\n", " ")
        return session.exec("sqlite3 -readonly ${shellQuote(dbPath)} ${shellQuote(query)} 2>/dev/null || true")
            .stdout
    }

    private fun parserFor(agent: AgentKind): ConversationParser? = when (agent) {
        AgentKind.ClaudeCode -> ClaudeCodeParser()
        AgentKind.Codex -> CodexParser()
        AgentKind.OpenCode -> OpenCodeReader()
    }

    internal fun detectionCommand(cwd: String): String {
        val encodedClaudeCwd = detector.encodeClaudeCwd(cwd)
        val quotedCwd = shellQuote(cwd)
        val sqlCwd = sqlQuote(cwd.trim().trimEnd('/').ifBlank { "/" })
        val openCodeCwdWhere = """
            ((p.worktree IS NOT NULL AND p.worktree != '' AND ($sqlCwd = p.worktree OR substr($sqlCwd, 1, length(p.worktree) + 1) = p.worktree || '/')) OR (s.directory IS NOT NULL AND s.directory != '' AND ($sqlCwd = s.directory OR substr($sqlCwd, 1, length(s.directory) + 1) = s.directory || '/')))
        """.trimIndent().replace("\n", " ")
        val openCodeSessionQuery = """
            SELECT s.id, COALESCE(s.time_updated, s.time_created, strftime('%s','now') * 1000), COALESCE(p.worktree, ''), COALESCE(s.directory, '') FROM session s LEFT JOIN project p ON p.id = s.project_id WHERE $openCodeCwdWhere ORDER BY s.time_updated DESC;
        """.trimIndent()
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
        // find walks the full subtree. OpenCode uses a SQLite database
        // at `~/.local/share/opencode/opencode.db`; the command queries
        // recent sessions whose directory/worktree matches [cwd].
        // Each branch is best-effort: missing directories, missing
        // `sqlite3`, or absent OpenCode databases silently emit nothing.
        //
        // Issue #236: freshness windows differ per engine.
        //  - Claude (`-mmin -5`): Claude streams JSONL writes
        //    continuously while the CLI is active, so a tight window
        //    correctly rejects logs from a session that has already
        //    exited (no streaming heartbeats anymore).
        //  - Codex / OpenCode (`-mmin -120`): Codex flushes its rollout
        //    JSONL only on turn completion. A user attached to an idle
        //    Codex TUI between turns can easily sit beyond 5 minutes
        //    without any new write, but the agent is still live. The
        //    2-hour window matches the detector's recency gate
        //    ([AgentDetector.recentWindowMillis]) so a stale-yet-active
        //    Codex/OpenCode rollout survives both filters. The same
        //    bound also applies to OpenCode's SQLite session rows by
        //    emitting their `time_updated` as the candidate mtime. The
        //    candidate path uses `opencode.db#<session-id>` so the
        //    reader can re-query the selected session instead of trying
        //    to tail the database file.
        return """
            cwd=$quotedCwd
            claude_dir="${'$'}HOME/.claude/projects/$encodedClaudeCwd"
            codex_dir="${'$'}HOME/.codex/sessions"
            opencode_dir="${'$'}HOME/.local/share/opencode"
            opencode_db="${'$'}opencode_dir/opencode.db"
            find "${'$'}claude_dir" -maxdepth 1 -type f -name '*.jsonl' -mmin -5 -print 2>/dev/null | while IFS= read -r f; do
              mtime=${'$'}(stat -c '%Y' "${'$'}f" 2>/dev/null) || continue
              printf 'claude|%s|%s|%s\n' "${'$'}mtime" "${'$'}cwd" "${'$'}f"
            done
            find "${'$'}codex_dir" -type f -name '*.jsonl' -mmin -120 -print 2>/dev/null | while IFS= read -r f; do
              mtime=${'$'}(stat -c '%Y' "${'$'}f" 2>/dev/null) || continue
              printf 'codex|%s|%s|%s\n' "${'$'}mtime" "${'$'}cwd" "${'$'}f"
            done
            if [ -f "${'$'}opencode_db" ] && command -v sqlite3 >/dev/null 2>&1; then
              sqlite3 -readonly -separator '|' "${'$'}opencode_db" ${shellQuote(openCodeSessionQuery)} 2>/dev/null | while IFS='|' read -r sid updated _worktree _directory; do
                [ -n "${'$'}sid" ] || continue
                mtime=${'$'}(awk 'BEGIN { v = ARGV[1] + 0; if (v > 100000000000) printf "%.3f", v / 1000; else printf "%.3f", v }' "${'$'}updated")
                printf 'opencode|%s|%s|%s#%s\n' "${'$'}mtime" "${'$'}cwd" "${'$'}opencode_db" "${'$'}sid"
              done
            fi
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
            sessionId = when {
                agent == AgentKind.OpenCode && "#" in path -> path.substringAfter('#')
                else -> path.substringAfterLast('/').substringBeforeLast('.')
            },
            cwd = cwd,
        )
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private fun sqlQuote(value: String): String =
        "'" + value.replace("'", "''") + "'"

    private fun openCodeDbPath(detection: AgentDetection): String =
        detection.sourcePath.substringBefore('#')

    private fun openCodeSessionId(detection: AgentDetection): String? =
        detection.sessionId?.takeIf { it.isNotBlank() }
            ?: detection.sourcePath.substringAfter('#', "").takeIf { it.isNotBlank() }
}
