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
import org.json.JSONObject
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
 * conversation-feed dedup contract used by
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
 *     Issue #460: the tail bound is **message-preserving**. A genuine
 *     conversation turn — `Message` (user OR assistant prose) — is never
 *     evicted to make room for tool activity. On a heavy agent session a
 *     single turn can emit dozens of `ToolCall`/`ToolResult`/`SystemNote`
 *     events between two user prompts, so a naive "keep the latest N
 *     events" bound silently drops every user message off the top of the
 *     window and the Conversation tab shows only the agent's most recent
 *     replies. To avoid that, when the event count exceeds [maxEvents] we
 *     keep ALL `Message` turns and only trim the non-message (tool /
 *     system-note) events, oldest first, back to the budget. Document
 *     order is preserved.
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
    // Issue #576: optimistic-turn reconciliation is kept O(1) per event
    // by indexing the live optimistic user turns by their text instead of
    // re-scanning the whole accumulator (`byId.entries.firstOrNull`) on
    // every real user message. A Codex `/new` replay feeds thousands of
    // tail lines through this reconcile; the old nested scan made each
    // pass O(window x map), so a burst was effectively O(N^2) inside one
    // reconcile (and O(N^3) across the unbatched per-line ingest). The
    // index makes the whole pass linear in the window size.
    //
    // The index maps optimistic user-message TEXT -> a FIFO queue of the
    // ids that produced it, in insertion order. That reproduces the prior
    // `firstOrNull` semantics exactly: when a real user message arrives we
    // collapse the OLDEST still-live optimistic turn with the same text
    // (back-to-back duplicate prompts each mint their own nanoTime-tagged
    // id, so a single real arrival only collapses one of them).
    val optimisticIdsByText = HashMap<String, ArrayDeque<String>>()
    // Mirror the pre-existing safety net: never inspect more than
    // 2 * maxEvents historic rows on a single reconcile call (callers
    // append new events at the tail; older events have already been
    // bounded on previous passes). Issue #460: the cap counts only
    // non-message events so a turn-heavy session whose recent lines are
    // all tool activity still keeps the user/assistant prose that
    // preceded it within view.
    val window = events.takeLastPreservingMessages(maxEvents * 2)
    for (event in window) {
        if (event is ConversationEvent.Message &&
            event.role == ConversationRole.User &&
            !event.isOptimistic()
        ) {
            // Drop the oldest prior optimistic entry with matching text.
            // The text index can carry stale ids (an optimistic turn that
            // was already overwritten by an id-dedup replace), so skip any
            // id that is no longer a live matching optimistic turn before
            // committing to a removal — preserving the exact "first live
            // match wins" behaviour of the previous linear scan.
            val queue = optimisticIdsByText[event.text]
            if (queue != null) {
                while (queue.isNotEmpty()) {
                    val candidateId = queue.removeFirst()
                    val candidate = byId[candidateId]
                    if (candidate is ConversationEvent.Message &&
                        candidate.role == ConversationRole.User &&
                        candidate.isOptimistic() &&
                        candidate.text == event.text
                    ) {
                        byId.remove(candidateId)
                        break
                    }
                }
                if (queue.isEmpty()) optimisticIdsByText.remove(event.text)
            }
        }
        byId[event.id] = event
        if (event is ConversationEvent.Message &&
            event.role == ConversationRole.User &&
            event.isOptimistic()
        ) {
            optimisticIdsByText.getOrPut(event.text) { ArrayDeque() }.addLast(event.id)
        }
    }
    return byId.values.toList().takeLastPreservingMessages(maxEvents)
}

/**
 * Issue #460: bound a conversation feed to at most [maxEvents] events
 * **without ever dropping a `Message` turn**. Tool calls, tool results,
 * and system notes are the trimmable surplus; conversation prose (user
 * questions + assistant answers) is the irreducible signal the
 * Conversation tab exists to show.
 *
 * Behaviour:
 *  - If the list already fits in [maxEvents], it is returned unchanged.
 *  - Otherwise the oldest non-message events are dropped, one at a time,
 *    until the list fits — preserving document order of everything that
 *    survives.
 *  - If the `Message` turns alone already exceed [maxEvents] (a pure
 *    chat with no tool activity longer than the cap), every non-message
 *    event is dropped and the most recent [maxEvents] messages are kept,
 *    matching the prior "latest N" semantics for that degenerate case.
 */
private fun List<ConversationEvent>.takeLastPreservingMessages(
    maxEvents: Int,
): List<ConversationEvent> {
    if (size <= maxEvents) return this
    val messageCount = count { it is ConversationEvent.Message }
    if (messageCount >= maxEvents) {
        // Even the prose alone overflows the budget: fall back to the
        // most recent [maxEvents] events regardless of type.
        return subList(size - maxEvents, size)
    }
    // Drop the oldest non-message events until we fit. We have room for
    // (maxEvents - messageCount) non-message events; keep the newest of
    // them and all messages, in document order.
    val nonMessageBudget = maxEvents - messageCount
    val nonMessageTotal = count { it !is ConversationEvent.Message }
    var dropRemaining = nonMessageTotal - nonMessageBudget
    val kept = ArrayList<ConversationEvent>(maxEvents)
    for (event in this) {
        if (event !is ConversationEvent.Message && dropRemaining > 0) {
            dropRemaining--
            continue
        }
        kept += event
    }
    return kept
}

private fun ConversationEvent.Message.isOptimistic(): Boolean =
    id.startsWith(OPTIMISTIC_USER_MESSAGE_ID_PREFIX)

/**
 * Issue #494: return a copy of this feed where the optimistic user turn
 * with id [optimisticId] is flipped to
 * [com.pocketshell.core.agents.MessageSendState.Failed]. Used by both the
 * raw-SSH and tmux send paths to mark a send that could not be delivered,
 * so the conversation pane shows a failed turn with a retry affordance
 * instead of a turn stuck on "sending…" forever or no turn at all.
 *
 * Non-matching events are returned unchanged; if no optimistic turn with
 * that id exists the list is returned as-is.
 */
internal fun List<ConversationEvent>.markOptimisticFailed(
    optimisticId: String,
): List<ConversationEvent> = map { event ->
    if (event is ConversationEvent.Message && event.id == optimisticId) {
        event.copy(sendState = com.pocketshell.core.agents.MessageSendState.Failed)
    } else {
        event
    }
}

/**
 * Default upper bound on the events kept by the conversation feed.
 * The constant matches the legacy in-VM `MaxAgentEvents` value so the
 * bounded-distinct contract stays unchanged for non-optimistic callers.
 */
internal const val DEFAULT_MAX_AGENT_EVENTS: Int = 500

/**
 * Issue #460: multiplier applied to the requested event budget when
 * tailing a raw JSONL transcript (Claude Code; Codex).
 * One conversation turn there is not one line — an active agent turn
 * emits many `tool_use` / `tool_result` lines per user prompt — so a
 * `tail -n <events>` over the raw file captures only the most recent
 * turn's tool churn and none of the user's own prompts. Widening the raw
 * read by this factor pulls several real turns into the window; the
 * message-preserving bound in [reconcileAgentEvents] then keeps the
 * user/assistant prose and trims the surplus tool events. Sized from
 * observed maintainer transcripts where a single turn ran ~30-100 raw
 * JSONL lines.
 */
internal const val JSONL_RAW_LINES_PER_EVENT: Int = 8

/**
 * Issue #793: first-paint message budget for the tail-first conversation
 * load. Opening the Conversation tab reads only the most recent
 * [FIRST_PAINT_MESSAGE_BUDGET] messages so the tail paints quickly instead of
 * blocking on the whole history (the old [DEFAULT_MAX_AGENT_EVENTS]-wide read
 * pulled ~500 messages × [JSONL_RAW_LINES_PER_EVENT] raw lines before showing
 * a single row). Older messages are paged in lazily on upward scroll via
 * [AgentConversationRepository.readEventsWindow]. Sized to comfortably fill
 * the viewport plus a scroll buffer on a phone.
 */
internal const val FIRST_PAINT_MESSAGE_BUDGET: Int = 30

/**
 * Issue #793: message budget for a stream RE-FETCH (the user tapped Retry on a
 * Stale/LogUnavailable feed). A retry re-reads a transcript the user was
 * already viewing, so it uses the legacy 200-message window (not the small
 * first-paint tail) to preserve the prior #460 behaviour of keeping user turns
 * visible amidst a heavy tool flood. The first-OPEN path stays on the small
 * [FIRST_PAINT_MESSAGE_BUDGET].
 */
internal const val DEFAULT_RETRY_MESSAGE_BUDGET: Int = 200

/**
 * Issue #793: how much the loaded window grows on each upward-scroll page.
 * Each page roughly triples the message budget (capped at
 * [DEFAULT_MAX_AGENT_EVENTS]) so a user scrolling up reaches older turns
 * quickly without ever paying for the full history up front.
 */
internal const val OLDER_PAGE_GROWTH_FACTOR: Int = 3

/**
 * Issue #793: result of a windowed tail read. Carries the parsed [events] for
 * the requested window plus [hasMoreOlder] — whether the on-server transcript
 * holds messages OLDER than the oldest event returned, i.e. whether an upward
 * scroll can page in more. The flag is derived from the raw line/row count the
 * read scanned vs. the total available, so the UI never shows a dead "load
 * older" affordance once the whole history is in the window.
 */
public data class ConversationEventsWindow(
    val events: List<ConversationEvent>,
    val hasMoreOlder: Boolean,
    /**
     * Issue #817 (slice 1): the line offset the live tail-follow must start
     * from so it emits only lines appended AFTER this window — i.e. the file's
     * line count at read time. The window read already learns this for free
     * (Claude: the sentinel `wc -l`; Codex: a folded `wc -l` of the raw source
     * file), so the cold-open path threads it through instead of paying a
     * separate `lineCount` SSH round-trip before the read. For OpenCode the
     * batched tail polls SQLite snapshots and ignores the line offset, so this
     * is `0` there (and is never used as a line cursor).
     */
    val tailStartLine: Long = 0L,
)

private const val PROCESS_TREE_SCAN_COMMAND: String =
    "ps -eo pid,ppid,tty,comm,args 2>/dev/null | " +
        "grep -E 'claude|codex|opencode|node' | grep -v grep || true"

// Issue #576: public type (the TmuxSessionViewModel constructor now takes it
// as an injectable parameter so a burst-ingest test can wire a repository
// whose tail drain runs on the test scheduler), but the constructor stays
// `internal` so only this module constructs it.
public class AgentConversationRepository internal constructor(
    private val detector: AgentDetector = AgentDetector(),
    private val tailScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val openCodePollIntervalMillis: Long = 2_000L,
    /**
     * Issue #576: coalescing window for the batched JSONL tail
     * ([tailEventsBatchedFromLine]). Parsed events that arrive within this
     * window are flushed as ONE batch to the caller, so a Codex `/new`
     * replay of thousands of lines collapses into a handful of
     * reconcile/emit cycles instead of one per line. Kept small so the
     * normal one-line-at-a-time live stream still feels immediate.
     */
    private val tailBatchWindowMillis: Long = 60L,
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
     *  1. Restricting the process scan to the process subtree rooted at
     *     rows whose controlling terminal is [paneTty] (e.g.
     *     `/dev/pts/3`). This includes agent children whose own TTY is
     *     `?`, as long as their parent belongs to this pane.
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
     *   CLIs report as `node` here, so the process-tree scan is the
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
        // tty because `ps` reports the controlling TTY as `pts/3`.
        // Tmux usually reports the full path; we normalise here to keep
        // the contract loose for callers. The scan is host-wide so we
        // can preserve child rows whose TTY is `?` but whose parent is a
        // pane-owned Node wrapper.
        val ttyArg = normalizedTty.removePrefix("/dev/")
        val processSnapshot = session.exec(
            PROCESS_TREE_SCAN_COMMAND,
        )
            .stdout
            .lines()
            // Drop blank trailing rows and the ps header row.
            .filter { it.isNotBlank() && !it.trimStart().startsWith("PID") }
        val paneProcesses = processLinesForPane(processSnapshot, ttyArg)
        // The pane's foreground process name is a cheap signal we
        // already have from `list-panes` — merge it in so callers that
        // wrap a JS-based agent in a shell wrapper still register
        // (the `comm` column on `ps` reports `node` for Claude /
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

    /**
     * A single pane to classify in [detectForPanes]. Carries the same
     * three per-pane signals [detectForPane] takes, plus the caller's
     * own key so the batched result can be mapped back to the caller's
     * domain (e.g. the session name in the folder list).
     */
    data class PaneProbe(
        val key: String,
        val cwd: String,
        val paneTty: String,
        val paneCommand: String,
    )

    /**
     * Issue #252 (latency follow-up): batched, host-wide equivalent of
     * [detectForPane] for callers that need to classify MANY panes at
     * once (the session list). [detectForPane] costs 2 SSH round-trips
     * per call — the cwd-scoped candidate enumeration plus the TTY-scoped
     * `ps`. Calling it once per session made the session-list load scale
     * as ~2N sequential round-trips, a real latency regression on a list
     * with several sessions.
     *
     * This method collapses that to a CONSTANT 2 round-trips regardless of
     * N:
     *
     *  1. ONE candidate-enumeration exec covering every unique cwd. The
     *     per-cwd shell block is the SAME [detectionCommand] used by
     *     [detectForPane] / [detect] — concatenated in subshells, one per
     *     cwd — so each row stays tagged with the cwd it was discovered
     *     for (Claude's path-hint is cwd-encoded; Codex's tree is
     *     host-wide; OpenCode's SQL is cwd-scoped). No shell logic is
     *     forked.
     *  2. ONE host-wide `ps` exec carrying the controlling TTY column, so
     *     each pane's process list can be sliced by its own TTY in-memory
     *     — preserving the per-pane `requireProcessMatch = true`
     *     discipline (a sibling pane's agent must not light up a
     *     plain-shell pane that shares the cwd).
     *
     * Classification itself is delegated to the SAME [AgentDetector.detect]
     * the single-pane path uses, with the same `requireProcessMatch = true`
     * gate and the same per-cwd candidate slice
     * (`candidate.cwd == pane.cwd`) the shell-side `detectionCommand(cwd)`
     * scoping enforces for [detectForPane]. So the list and the
     * Conversation tab still agree by construction — only the remote I/O
     * is hoisted out of the loop.
     *
     * Panes with a blank cwd or blank TTY are skipped (no per-pane
     * attribution is possible), exactly as [detectForPane] returns null
     * for them. Returned map only contains keys that produced a detection.
     */
    suspend fun detectForPanes(
        session: SshSession,
        panes: List<PaneProbe>,
    ): Map<String, AgentDetection> {
        // Normalise + drop panes that cannot be attributed (no cwd / no
        // TTY) up front — same guards detectForPane applies per call.
        val normalizedPanes = panes.mapNotNull { pane ->
            val cwd = pane.cwd.trim().ifBlank { return@mapNotNull null }
            val tty = pane.paneTty.trim().ifBlank { return@mapNotNull null }
            NormalizedPaneProbe(
                key = pane.key,
                cwd = cwd,
                ttyArg = tty.removePrefix("/dev/"),
                paneCommand = pane.paneCommand,
            )
        }
        if (normalizedPanes.isEmpty()) return emptyMap()

        val nowMillis = System.currentTimeMillis()
        val uniqueCwds = normalizedPanes.map { it.cwd }.distinct()

        // Round-trip 1: one candidate enumeration across every cwd.
        val candidates = session.exec(detectionCommandForCwds(uniqueCwds))
            .stdout
            .lineSequence()
            .mapNotNull(::parseCandidate)
            .toList()

        // Round-trip 2: one host-wide process scan. The `pid` / `ppid`
        // / `tty` columns let us slice per pane in-memory rather than
        // paying a `ps -t` round trip per session. Keep `node` rows so
        // wrapper launches can seed the pane-owned process subtree even
        // when the real agent child reports TTY `?`; AgentDetector still
        // requires an agent command token from the merged subtree.
        val processLines = session.exec(
            PROCESS_TREE_SCAN_COMMAND,
        )
            .stdout
            .lines()
            .filter { it.isNotBlank() }

        val result = mutableMapOf<String, AgentDetection>()
        for (pane in normalizedPanes) {
            // Per-cwd candidate slice — mirrors the shell-side
            // detectionCommand(cwd) scoping that detectForPane gets for
            // free by enumerating a single cwd. Without this, an OpenCode
            // session whose path-hint is cwd-agnostic could bleed onto a
            // pane in a different cwd.
            val paneCandidates = candidates.filter { it.cwd == pane.cwd }
            if (paneCandidates.isEmpty()) continue

            // Slice the host-wide ps output to THIS pane's process
            // subtree. Exact TTY rows seed the subtree; descendants stay
            // included even if their own TTY is `?`, which covers Node
            // wrappers that own the pane while the native agent child is
            // detached from the controlling terminal.
            val ttyFiltered = processLinesForPane(processLines, pane.ttyArg)
            val merged = if (pane.paneCommand.isBlank()) {
                ttyFiltered
            } else {
                ttyFiltered + pane.paneCommand
            }

            val detection = detector.detect(
                cwd = pane.cwd,
                nowMillis = nowMillis,
                candidates = paneCandidates,
                processLines = merged,
                requireProcessMatch = true,
            )
            if (detection != null) {
                result[pane.key] = detection
            }
        }
        return result
    }

    private data class NormalizedPaneProbe(
        val key: String,
        val cwd: String,
        val ttyArg: String,
        val paneCommand: String,
    )

    private data class ProcessRow(
        val pid: Long,
        val ppid: Long,
        val tty: String,
        val raw: String,
    )

    private fun processLinesForPane(lines: List<String>, ttyArg: String): List<String> {
        val rows = lines.mapNotNull(::parseProcessRow)
        if (rows.isEmpty()) {
            return lines.filter { line ->
                val tokens = line.trim().split(Regex("\\s+"), limit = 3)
                tokens.size >= 2 && tokens[1] == ttyArg
            }
        }

        val includedPids = rows
            .asSequence()
            .filter { it.tty == ttyArg }
            .mapTo(mutableSetOf()) { it.pid }
        if (includedPids.isEmpty()) return emptyList()

        var changed: Boolean
        do {
            changed = false
            for (row in rows) {
                if (row.pid !in includedPids && row.ppid in includedPids) {
                    includedPids += row.pid
                    changed = true
                }
            }
        } while (changed)

        return rows
            .filter { it.pid in includedPids }
            .map { it.raw }
    }

    private fun parseProcessRow(line: String): ProcessRow? {
        val tokens = line.trim().split(Regex("\\s+"), limit = 5)
        if (tokens.size < 4) return null
        val pid = tokens[0].toLongOrNull() ?: return null
        val ppid = tokens[1].toLongOrNull() ?: return null
        return ProcessRow(
            pid = pid,
            ppid = ppid,
            tty = tokens[2],
            raw = line,
        )
    }

    /**
     * Concatenate the per-cwd [detectionCommand] into one script, each cwd
     * in its own subshell so a `set`/`cd`/variable from one block cannot
     * leak into the next. The whole thing runs in a single SSH round-trip
     * and emits the same `agent|epoch|cwd|path` rows [parseCandidate]
     * already understands, each tagged with the cwd that produced it.
     *
     * Reusing [detectionCommand] verbatim keeps it the single source of
     * truth for the candidate-enumeration shell — no forked heuristic.
     */
    internal fun detectionCommandForCwds(cwds: List<String>): String =
        cwds.joinToString(separator = "\n") { cwd ->
            "(\n${detectionCommand(cwd)}\n)"
        }

    suspend fun readInitialEvents(
        session: SshSession,
        detection: AgentDetection,
        maxLines: Int = 200,
    ): List<ConversationEvent> {
        if (detection.isOpenCodeSqlite()) {
            val output = exportOpenCodeSqliteRows(session, detection, maxMessages = maxLines)
            return OpenCodeReader().parseSqliteJsonRows(output)
        }
        if (detection.agent == AgentKind.Codex) {
            return readCodexInitialEvents(session, detection, maxLines)
        }
        val parser = parserFor(detection.agent) ?: return emptyList()
        // Issue #460: [maxLines] is a *message/event* budget, but a
        // line-tailed JSONL transcript (Claude Code; Codex)
        // interleaves one user turn with potentially hundreds of
        // tool_use / tool_result lines. A `tail -n <maxLines>` over the
        // raw file therefore routinely captures only the final agent
        // turn — all tool activity, zero of the user's own prompts — so
        // the Conversation tab opens showing only the agent's replies.
        // Read a much wider raw-line window so several real turns are
        // present; the message-preserving bound in [reconcileAgentEvents]
        // then keeps the user/assistant prose and trims surplus tool
        // events back to budget.
        val rawLineBudget = (maxLines * JSONL_RAW_LINES_PER_EVENT)
            .coerceAtLeast(maxLines)
        val result = session.exec(
            "tail -n $rawLineBudget ${shellQuote(detection.sourcePath)} 2>/dev/null || true",
        )
        return result.stdout.lineSequence().flatMap { parser.parseLine(it) }.toList()
    }

    /**
     * Issue #793: tail-first windowed read. Returns the most recent
     * [maxMessages] messages of the transcript PLUS whether older messages
     * exist before the window (so the UI can offer upward paging). This is the
     * fast-open primitive: the Conversation tab calls it with the small
     * [FIRST_PAINT_MESSAGE_BUDGET] for first paint, then re-calls it with a
     * larger budget as the user scrolls up — never reading the whole history
     * up front.
     *
     * The `hasMoreOlder` signal is derived per engine from the raw line/row
     * count the read scanned vs. the total available, so a window that already
     * covers the entire transcript reports `hasMoreOlder = false` and the pane
     * stops trying to page.
     */
    suspend fun readEventsWindow(
        session: SshSession,
        detection: AgentDetection,
        maxMessages: Int,
    ): ConversationEventsWindow {
        val budget = maxMessages.coerceAtLeast(1)
        if (detection.isOpenCodeSqlite()) {
            val output = exportOpenCodeSqliteRows(session, detection, maxMessages = budget)
            val events = OpenCodeReader().parseSqliteJsonRows(output)
            // The SQL LIMIT is on the `message` table; distinct message ids in
            // the window approximate "rows read". If we filled the limit there
            // are (very likely) older messages we did not pull.
            val distinctMessages = events.asSequence().map { it.id }.distinct().count()
            return ConversationEventsWindow(
                events = events,
                hasMoreOlder = distinctMessages >= budget,
            )
        }
        if (detection.agent == AgentKind.Codex) {
            val rawBudget = (budget * JSONL_RAW_LINES_PER_EVENT).coerceAtLeast(budget)
            val codex = readCodexWindow(session, detection, budget)
            return ConversationEventsWindow(
                events = codex.events,
                hasMoreOlder = codex.rawLineCount >= rawBudget,
                // Issue #817: the live tail follows the raw `sourcePath` from a
                // line offset, so it needs the file's `wc -l` (NOT the
                // agent-log envelope line count). The Codex window read folds
                // that count into its own exec via a sentinel, so the cold-open
                // path no longer needs the separate `lineCount` round-trip.
                tailStartLine = codex.sourceLineCount,
            )
        }
        val parser = parserFor(detection.agent)
            ?: return ConversationEventsWindow(emptyList(), hasMoreOlder = false)
        val rawLineBudget = (budget * JSONL_RAW_LINES_PER_EVENT).coerceAtLeast(budget)
        // ONE round-trip: emit the total line count, a sentinel, then the tail
        // window. We compare the total against the window size to know whether
        // older raw lines (hence older turns) exist before the window.
        val sentinel = "@@PS_WINDOW@@"
        val result = session.exec(
            "wc -l < ${shellQuote(detection.sourcePath)} 2>/dev/null || printf 0; " +
                "printf '%s\\n' $sentinel; " +
                "tail -n $rawLineBudget ${shellQuote(detection.sourcePath)} 2>/dev/null || true",
        )
        val lines = result.stdout.split("\n")
        val sentinelIndex = lines.indexOf(sentinel)
        val totalLines = if (sentinelIndex >= 0) {
            lines.take(sentinelIndex).joinToString("").trim().toLongOrNull() ?: 0L
        } else {
            0L
        }
        val tailRawLines = if (sentinelIndex >= 0) {
            lines.drop(sentinelIndex + 1)
        } else {
            lines
        }
        val events = tailRawLines.asSequence().flatMap { parser.parseLine(it) }.toList()
        return ConversationEventsWindow(
            events = events,
            hasMoreOlder = totalLines > rawLineBudget,
            // Issue #817: the file's line count at read time is exactly the
            // `fromLineExclusive` cursor the follow-tail needs — it is the same
            // value the separate `lineCount` exec used to fetch. Thread it
            // through so the cold-open path no longer pays that round-trip.
            tailStartLine = totalLines,
        )
    }

    /**
     * Issue #817: the Codex window read now also reports [sourceLineCount] —
     * the `wc -l` of the raw transcript file the live tail follows — so the
     * cold-open path derives the follow cursor from this single exec instead of
     * a separate `lineCount` round-trip. [rawLineCount] stays the agent-log
     * envelope line count used for the `hasMoreOlder` derivation.
     */
    private data class CodexWindow(
        val events: List<ConversationEvent>,
        val rawLineCount: Int,
        val sourceLineCount: Long,
    )

    private suspend fun readCodexWindow(
        session: SshSession,
        detection: AgentDetection,
        maxMessages: Int,
    ): CodexWindow {
        val sessionId = detection.sessionId?.takeIf { it.isNotBlank() }
            ?: detection.sourcePath.substringAfterLast('/').substringBeforeLast('.')
        if (sessionId.isBlank()) return CodexWindow(emptyList(), 0, 0L)
        val boundedMaxLines = (maxMessages * JSONL_RAW_LINES_PER_EVENT)
            .coerceAtLeast(maxMessages)
            .coerceAtLeast(1)
        // ONE round-trip: emit the raw-file `wc -l` (the follow-tail cursor),
        // a sentinel, then the agent-log window. Folding the count into the
        // same exec is what lets the cold-open path drop the standalone
        // `lineCount` round-trip without losing the tail-start position.
        val sentinel = "@@PS_CODEX_WINDOW@@"
        val output = session.exec(
            "wc -l < ${shellQuote(detection.sourcePath)} 2>/dev/null || printf 0; " +
                "printf '%s\\n' $sentinel; " +
                "pocketshell agent-log --engine codex --session ${shellQuote(sessionId)} " +
                "--json --tail $boundedMaxLines 2>/dev/null || true",
        ).stdout
        val splitLines = output.split("\n")
        val sentinelIndex = splitLines.indexOf(sentinel)
        val sourceLineCount = if (sentinelIndex >= 0) {
            splitLines.take(sentinelIndex).joinToString("").trim().toLongOrNull() ?: 0L
        } else {
            0L
        }
        val envelopeOutput = if (sentinelIndex >= 0) {
            splitLines.drop(sentinelIndex + 1).joinToString("\n")
        } else {
            output
        }
        val lines = parseAgentLogEnvelopeLines(envelopeOutput)
        val parser = CodexParser()
        val events = lines.asSequence().flatMap { parser.parseLine(it) }.toList()
        return CodexWindow(events = events, rawLineCount = lines.size, sourceLineCount = sourceLineCount)
    }

    fun tailEvents(
        session: SshSession,
        detection: AgentDetection,
        onEvent: (ConversationEvent) -> Unit,
    ): Job? {
        if (detection.isOpenCodeSqlite()) {
            return tailEventsFromLine(session, detection, fromLineExclusive = 0L, onEvent)
        }
        val parser = parserFor(detection.agent) ?: return null
        return try {
            session.tail(detection.sourcePath) { line ->
                parser.parseLine(line).forEach(onEvent)
            }
        } catch (_: SshException) {
            null
        } catch (_: IOException) {
            null
        }
    }

    suspend fun lineCount(session: SshSession, detection: AgentDetection): Long =
        session.exec(
            if (detection.isOpenCodeSqlite()) {
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
        if (detection.isOpenCodeSqlite()) {
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
        return try {
            session.tail(detection.sourcePath, fromLineExclusive) { line ->
                parser.parseLine(line).forEach(onEvent)
            }
        } catch (_: SshException) {
            null
        } catch (_: IOException) {
            null
        }
    }

    /**
     * Issue #576: batched / debounced variant of [tailEventsFromLine].
     *
     * A Codex `/new` (or any large JSONL replay) rewrites the rollout file
     * with thousands of lines; the per-line [tailEventsFromLine] fires its
     * callback once per parsed event, so the conversation feed runs N
     * reconcile passes and N StateFlow emissions for a single burst — an
     * O(N^3)/N-emit storm that starves the device. This variant coalesces
     * the events that arrive within [tailBatchWindowMillis] into ONE
     * [onEvents] callback, so the burst collapses to a handful of
     * reconcile + emit cycles. The final event set, order, and dedup are
     * identical to feeding every event one at a time — the reconcile pass
     * in [reconcileAgentEvents] is order-preserving and idempotent over
     * batch boundaries, so `[a],[b],[c]` and `[a,b,c]` reconcile to the
     * same list.
     *
     * The returned [Job] owns both the underlying tail and the drain
     * coroutine; cancelling it stops both and flushes nothing further.
     */
    fun tailEventsBatchedFromLine(
        session: SshSession,
        detection: AgentDetection,
        fromLineExclusive: Long,
        onEvents: (List<ConversationEvent>) -> Unit,
    ): Job? {
        if (detection.isOpenCodeSqlite()) {
            // OpenCode already produces a coherent per-poll snapshot; emit
            // each poll's new rows as a single batch instead of per row so
            // a large snapshot still costs one reconcile/emit per cycle.
            return tailEventsFromLineOpenCodeBatched(session, detection, onEvents)
        }
        val parser = parserFor(detection.agent) ?: return null

        // Buffer parsed events arriving from the tail reader and flush them
        // on a short debounce window. The buffer is guarded by its own
        // monitor because the tail callback runs on the SSH reader thread
        // while the drain runs on [tailScope].
        val pending = ArrayList<ConversationEvent>()
        val lock = Any()

        val drainJob = tailScope.launch {
            while (isActive) {
                delay(tailBatchWindowMillis)
                val batch = synchronized(lock) {
                    if (pending.isEmpty()) {
                        null
                    } else {
                        ArrayList(pending).also { pending.clear() }
                    }
                }
                if (batch != null) onEvents(batch)
            }
        }

        val tailJob = try {
            session.tail(detection.sourcePath, fromLineExclusive) { line ->
                val parsed = parser.parseLine(line)
                if (parsed.isNotEmpty()) {
                    synchronized(lock) { pending.addAll(parsed) }
                }
            }
        } catch (_: SshException) {
            drainJob.cancel()
            return null
        } catch (_: IOException) {
            drainJob.cancel()
            return null
        }

        // The umbrella Job the caller observes must carry the SAME terminal
        // lifecycle semantics as the per-line [tailEventsFromLine] it
        // replaces: the underlying tail's normal completion (EOF → the
        // conversation feed is Stale) propagates as a normal completion, and
        // its failure (transport drop → LogUnavailable) propagates as that
        // failure cause. The ViewModel's `invokeOnCompletion` consults
        // `cause` to mark Stale vs LogUnavailable and ignores
        // CancellationException (an intentional switch/reattach teardown), so
        // we must NOT collapse a real tail EOF into a blanket `cancel()` —
        // doing so would silently drop the Stale/LogUnavailable marking the
        // UI relies on.
        //
        // The TAIL is the authoritative lifecycle signal; the drain is a
        // bookkeeping coroutine. So:
        //  - tail terminates  -> complete the umbrella with the tail's cause
        //    (null = normal/Stale, throwable = failure/LogUnavailable), then
        //    stop the drain.
        //  - caller cancels the umbrella -> cancel both tail and drain (the
        //    completion handler still fires with a CancellationException, which
        //    the caller ignores).
        //  - drain dies on its own (scope cancelled) -> tear the tail down;
        //    that path completes the umbrella via the tail's resulting
        //    cancellation, again ignored by the caller.
        //
        // A final synchronous flush on cancellation would race the reader, so
        // any residue that lands after the last drain tick is intentionally
        // dropped — the conversation feed is reseeded from the initial read
        // on the next attach, and the tail bound trims replay surplus anyway.
        val umbrella = Job()
        tailJob.invokeOnCompletion { cause ->
            when (cause) {
                null -> umbrella.complete()
                is CancellationException -> umbrella.cancel(cause)
                else -> umbrella.completeExceptionally(cause)
            }
            drainJob.cancel()
        }
        drainJob.invokeOnCompletion { tailJob.cancel() }
        umbrella.invokeOnCompletion {
            drainJob.cancel()
            tailJob.cancel()
        }
        return umbrella
    }

    private fun tailEventsFromLineOpenCodeBatched(
        session: SshSession,
        detection: AgentDetection,
        onEvents: (List<ConversationEvent>) -> Unit,
    ): Job? {
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
                val batch = ArrayList<ConversationEvent>()
                snapshotEvents.forEach { event ->
                    if (emittedEvents[event.id] != event) {
                        emittedEvents.remove(event.id)
                        emittedEvents[event.id] = event
                        batch += event
                    }
                }
                if (batch.isNotEmpty()) onEvents(batch)
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

    private suspend fun readCodexInitialEvents(
        session: SshSession,
        detection: AgentDetection,
        maxLines: Int,
    ): List<ConversationEvent> {
        val sessionId = detection.sessionId?.takeIf { it.isNotBlank() }
            ?: detection.sourcePath.substringAfterLast('/').substringBeforeLast('.')
        if (sessionId.isBlank()) return emptyList()
        val boundedMaxLines = (maxLines * JSONL_RAW_LINES_PER_EVENT)
            .coerceAtLeast(maxLines)
            .coerceAtLeast(1)
        val output = session.exec(
            "pocketshell agent-log --engine codex --session ${shellQuote(sessionId)} " +
                "--json --tail $boundedMaxLines 2>/dev/null || true",
        ).stdout
        val lines = parseAgentLogEnvelopeLines(output)
        val parser = CodexParser()
        return lines.asSequence().flatMap { parser.parseLine(it) }.toList()
    }

    private fun parseAgentLogEnvelopeLines(output: String): List<String> {
        val envelope = output.lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return emptyList()
        val lines = envelope.optJSONArray("lines") ?: return emptyList()
        return buildList {
            for (index in 0 until lines.length()) {
                lines.optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun parserFor(agent: AgentKind): ConversationParser? = when (agent) {
        AgentKind.ClaudeCode -> ClaudeCodeParser()
        AgentKind.Codex -> CodexParser()
        // OpenCode is read exclusively from its SQLite database via the
        // [isOpenCodeSqlite] branches; it has no JSONL-tailing parser.
        AgentKind.OpenCode -> null
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
        // Issue #236 / #820: every engine uses a 2-hour freshness window
        // (`-mmin -120`) that matches the detector's recency gate
        // ([AgentDetector.recentWindowMillis]) — the shell-side `find`
        // pre-filter and the JVM-side recency filter agree.
        //  - Claude originally used a tight `-mmin -5` window on the theory
        //    that it streams JSONL continuously. In practice (#820) an idle
        //    Claude session between turns, or one whose GLM/Z.AI response
        //    hasn't flushed the JSONL in the last 5 minutes, had its only
        //    transcript excluded by the pre-filter even though the agent
        //    was visibly alive on screen. Detection then returned null and
        //    the Conversation tab hard-failed ("Couldn't load this
        //    conversation.") once the 12 s watchdog tripped. Widening to
        //    `-mmin -120` lets an idle-but-real Claude session through; the
        //    per-pane `requireProcessMatch` guard (#186) still prevents a
        //    stale sibling JSONL in the same cwd from mis-lighting a pane
        //    where no agent is actually running.
        //  - Codex flushes its rollout JSONL only on turn completion. A
        //    user attached to an idle Codex TUI between turns can easily sit
        //    beyond 5 minutes without any new write, but the agent is still
        //    live. The same 2-hour bound covers it. The bound also applies
        //    to OpenCode's SQLite session rows by emitting their
        //    `time_updated` as the candidate mtime. The candidate path uses
        //    `opencode.db#<session-id>` so the reader can re-query the
        //    selected session instead of trying to tail the database file.
        return """
            cwd=$quotedCwd
            claude_dir="${'$'}HOME/.claude/projects/$encodedClaudeCwd"
            codex_dir="${'$'}HOME/.codex/sessions"
            opencode_dir="${'$'}HOME/.local/share/opencode"
            opencode_db="${'$'}opencode_dir/opencode.db"
            find "${'$'}claude_dir" -maxdepth 1 -type f -name '*.jsonl' -mmin -120 -print 2>/dev/null | while IFS= read -r f; do
              mtime=${'$'}(stat -c '%Y' "${'$'}f" 2>/dev/null) || continue
              printf 'claude|%s|%s|%s\n' "${'$'}mtime" "${'$'}cwd" "${'$'}f"
            done
            find "${'$'}codex_dir" -type f -name '*.jsonl' -mmin -120 -print 2>/dev/null | while IFS= read -r f; do
              codex_cwd=${'$'}(
                grep -m 1 '"type"[[:space:]]*:[[:space:]]*"session_meta"' "${'$'}f" 2>/dev/null |
                  sed -n 's/.*"payload"[[:space:]]*:[[:space:]]*{[^}]*"cwd"[[:space:]]*:[[:space:]]*"\([^"\\]*\)".*/\1/p' |
                  head -n 1
              )
              [ "${'$'}codex_cwd" = "${'$'}cwd" ] || continue
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

    private fun AgentDetection.isOpenCodeSqlite(): Boolean =
        agent == AgentKind.OpenCode &&
            sourcePath.substringBefore('#').endsWith("/.local/share/opencode/opencode.db")
}
