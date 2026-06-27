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
/**
 * Issue #831: a deterministic complexity probe for [reconcileAgentEvents].
 *
 * The reconcile linearity guard must NOT be a wall-clock assertion — raw
 * elapsed time inflates under load on the shared CI runner (and the dev box),
 * so a time-based ceiling flakes even though the algorithm is still linear.
 *
 * Instead the matching path increments [candidateInspections] every time it
 * examines one accumulated candidate while trying to collapse an optimistic
 * turn into its real counterpart. This is exactly the work that the old
 * `byId.entries.firstOrNull` nested scan blew up: that scan inspected the
 * whole accumulator on every real user message, so the count was O(window^2).
 * The current FIFO-queue index inspects each optimistic id at most once across
 * the entire reconcile, so the count is O(window).
 *
 * Asserting `candidateInspections` (a deterministic integer derived purely
 * from control flow, never from elapsed time) is therefore a sound,
 * contention-immune signal for "linear vs quadratic shape": doubling the input
 * roughly doubles a linear count and roughly quadruples a quadratic one,
 * regardless of how loaded the machine is.
 *
 * Pass an instance to capture the count; production callers pass `null` and pay
 * nothing.
 */
internal class ReconcileOpCounter {
    var candidateInspections: Long = 0L
        private set

    fun recordCandidateInspection() {
        candidateInspections++
    }
}

internal fun reconcileAgentEvents(
    events: List<ConversationEvent>,
    maxEvents: Int = DEFAULT_MAX_AGENT_EVENTS,
    opCounter: ReconcileOpCounter? = null,
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
    // Issue #819 (duplicate ASSISTANT turn): track the most recently
    // inserted NON-optimistic Message so a consecutive duplicate of the same
    // logical turn collapses. A real Codex rollout writes the same assistant
    // text twice in a row — once as a streaming `event_msg`/`agent_message`
    // (no id → CodexParser falls back to line.hashCode()) and once as the
    // authoritative `response_item`/`message` (with a real id) — so the
    // id-keyed dedup below cannot collapse them. The check is adjacency-scoped
    // (the immediately-preceding Message, ignoring intervening tool/reasoning/
    // system events) so legitimately-repeated turns separated by another turn
    // are preserved.
    var lastNonOptimisticMessage: ConversationEvent.Message? = null
    for (event in window) {
        if (event is ConversationEvent.Message &&
            !event.isOptimistic()
        ) {
            val previous = lastNonOptimisticMessage
            if (previous != null &&
                previous.role == event.role &&
                previous.text == event.text &&
                previous.agent == event.agent
            ) {
                // Consecutive duplicate of the same turn (the Codex echo +
                // record pair). Keep the one already inserted; skip this one.
                // The previous entry stays "last" so a third identical write
                // (rare) also collapses.
                continue
            }
        }
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
                    opCounter?.recordCandidateInspection()
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
        // Issue #819: advance the adjacency cursor only for non-optimistic
        // Message turns. Tool/reasoning/system events between an echo and its
        // record must not break the adjacency check; an optimistic user echo
        // is collapsed by the id/text path above, not here.
        if (event is ConversationEvent.Message && !event.isOptimistic()) {
            lastNonOptimisticMessage = event
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
    /**
     * Epic #821 slice #3 (#825): read the agent kind PocketShell recorded for
     * the tmux session [sessionTarget] at launch, from the host-side
     * `@ps_agent_kind` user option. Returns the [AgentKind] when the option is
     * set to a known engine, or `null` when the option is absent (a session we
     * did not launch — a FOREIGN session) or names an unknown value.
     *
     * [sessionTarget] is a `-t` target: the tmux session id (`$N`) or name. We
     * read the SESSION-scoped option exactly as the launch wrapper wrote it
     * (`tmux set-option @ps_agent_kind <kind>` with no `-g`), so the recorded
     * kind survives reconnect / app restart for the life of the session — the
     * same durable, authoritative signal [com.pocketshell.app.projects.FolderListGateway]
     * reads back via `list-sessions`. Best-effort: any failure / empty output
     * resolves to `null` so the caller degrades to foreign-session detection.
     */
    suspend fun readRecordedAgentKind(
        session: SshSession,
        sessionTarget: String,
    ): AgentKind? {
        val target = sessionTarget.trim().ifBlank { return null }
        val raw = runCatching {
            session.exec(
                "tmux show-options -v -t ${shellQuote(target)} @ps_agent_kind 2>/dev/null || true",
            ).stdout
        }.getOrNull() ?: return null
        return recordedAgentKindFromOption(raw)
    }

    /**
     * Issue #821 branch 1: read the exact transcript source PocketShell recorded
     * for this tmux session (`@ps_agent_source`). It is optional and best-effort:
     * legacy sessions and old host CLIs simply return `null`, which keeps the
     * existing source selector unchanged.
     */
    suspend fun readRecordedAgentSource(
        session: SshSession,
        sessionTarget: String,
    ): String? = readRecordedAgentSourceOption(session, sessionTarget).source

    data class RecordedAgentSourceOption(
        val source: String?,
        val generationScoped: Boolean,
    )

    suspend fun readRecordedAgentSourceOption(
        session: SshSession,
        sessionTarget: String,
    ): RecordedAgentSourceOption {
        val target = sessionTarget.trim().ifBlank {
            return RecordedAgentSourceOption(source = null, generationScoped = false)
        }
        val generationSentinel = "@@PS_RECORDED_SOURCE_GENERATION@@"
        val raw = runCatching {
            session.exec(
                "ps_recorded_source_generation=\$(" +
                    "tmux show-options -v -t ${shellQuote(target)} @ps_agent_source_generation 2>/dev/null || true" +
                    "); printf '%s\\n' \"\$ps_recorded_source_generation\"; " +
                    "printf '%s\\n' $generationSentinel; " +
                    "tmux show-options -v -t ${shellQuote(target)} @ps_agent_source 2>/dev/null || true",
            ).stdout
        }.getOrNull() ?: return RecordedAgentSourceOption(source = null, generationScoped = false)
        val lines = raw.split("\n")
        val generationIndex = lines.indexOf(generationSentinel)
        val generation = if (generationIndex >= 0) {
            lines.take(generationIndex).joinToString("\n").trim().ifBlank { null }
        } else {
            null
        }
        val sourceRaw = if (generationIndex >= 0) {
            lines.drop(generationIndex + 1).joinToString("\n")
        } else {
            raw
        }
        return recordedAgentSourceOptionFromRaw(sourceRaw, generation)
    }

    internal fun recordedAgentSourceOptionFromRaw(
        raw: String?,
        generation: String? = null,
    ): RecordedAgentSourceOption {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() }
            ?: return RecordedAgentSourceOption(source = null, generationScoped = false)
        val currentGeneration = generation?.trim().orEmpty()
        val parts = value.split("\t", limit = 2)
        if (parts.size == 2 && parts[0].isNotBlank()) {
            if (currentGeneration.isNotEmpty() && parts[0] != currentGeneration) {
                return RecordedAgentSourceOption(source = null, generationScoped = false)
            }
            val source = parts[1].trim().takeIf { it.isNotEmpty() }
            return RecordedAgentSourceOption(
                source = source,
                generationScoped = source != null && currentGeneration.isNotEmpty(),
            )
        }
        if (currentGeneration.isNotEmpty()) {
            return RecordedAgentSourceOption(source = null, generationScoped = false)
        }
        return RecordedAgentSourceOption(source = value, generationScoped = false)
    }

    internal fun recordedAgentSourceFromOption(raw: String?, generation: String? = null): String? =
        recordedAgentSourceOptionFromRaw(raw, generation).source

    /**
     * Epic #821 slice #3 (#825): map a raw `@ps_agent_kind` option value to an
     * [AgentKind]. The launch wrapper writes the lowercase engine token
     * (`claude` / `codex` / `opencode`); anything else (blank, a foreign
     * session with no option, an unknown value) maps to `null`. Kept in lockstep
     * with `FolderListGateway.recordedKindFromOption`.
     */
    internal fun recordedAgentKindFromOption(raw: String?): AgentKind? =
        when (raw?.trim()?.lowercase()) {
            "claude" -> AgentKind.ClaudeCode
            "codex" -> AgentKind.Codex
            "opencode" -> AgentKind.OpenCode
            else -> null
        }

    /**
     * Epic #821 slice #3 (#825): resolve the Conversation source for a session
     * whose agent kind PocketShell RECORDED at launch (`@ps_agent_kind` →
     * [recordedKind]). The kind is NOT guessed — it is fixed by [recordedKind],
     * and only candidates of that exact kind are handed to the selection step.
     * This kills the #807 / #819 / #820 mis-detected-source cluster where the
     * (now-deleted, epic #821 A2) output-parsing detector's cross-kind
     * path-hint / mtime race bound the Conversation view to the WRONG kind or a
     * busier sibling rollout of a different engine.
     *
     * Epic #821 slice A2: this is ALSO the source-resolution path for FOREIGN
     * sessions — once the one-shot daemon guess
     * ([com.pocketshell.app.agents.AgentKindRemoteSource]) names a kind, the
     * caller passes it here as [recordedKind] so the same kind-fixed selection
     * resolves the foreign transcript source. The KIND-guessing is hard-cut;
     * the SOURCE-path resolution surface this method provides is kept.
     *
     * Engine-specific resolution, scoped to the recorded kind:
     *  - **Claude**: pick the most-recent candidate under
     *    `~/.claude/projects/<encodeClaudeCwd(cwd)>/` — the source path is
     *    `~/.claude/projects/<encodeClaudeCwd(cwd)>/<sessionId>.jsonl`, computed
     *    from `(recordedKind, sessionId, cwd)` with no kind detection.
     *  - **OpenCode**: pick the most-recent `opencode.db#<sessionId>` session
     *    scoped to this cwd — `~/.local/share/opencode/opencode.db#<sessionId>`,
     *    computed from `(recordedKind, sessionId)`.
     *  - **Codex**: the rollout file has no session-id-in-path, so it is still
     *    selected by the process-owned `/proc/<pid>/fd` resolution
     *    ([resolveProcessOwnedCodexRollouts]) — but scoped as Codex by the
     *    recorded kind, NOT by an mtime race against a sibling of any kind.
     *
     * Returns `null` only when no candidate of the recorded kind exists for
     * this pane (the agent's log has not appeared yet / the agent exited) —
     * the caller treats that exactly like a null detection.
     */
    suspend fun detectRecordedSessionForPane(
        session: SshSession,
        cwd: String,
        paneTty: String,
        paneCommand: String,
        recordedKind: AgentKind,
        recordedSource: String? = null,
    ): AgentDetection? {
        val normalizedCwd = cwd.trim().ifBlank { return null }
        val normalizedTty = paneTty.trim().ifBlank { return null }
        // cwd-scoped candidate enumeration, keeping ONLY the candidates of the
        // recorded/guessed kind — the source selector never gets to pick the
        // kind from a cross-kind path-hint / mtime race.
        val candidates = session.exec(detectionCommand(normalizedCwd))
            .stdout
            .lineSequence()
            .mapNotNull(::parseCandidate)
            .filter { it.agent == recordedKind }
            .toList()
        return selectRecordedCandidate(
            session = session,
            normalizedCwd = normalizedCwd,
            normalizedTty = normalizedTty,
            paneCommand = paneCommand,
            recordedKind = recordedKind,
            recordedSource = recordedSource,
            candidates = candidates,
        )
    }

    /**
     * Issue #975 (B1, classify-miss): resolve the Conversation source for a pane
     * WITHOUT a known kind, by trusting a LIVE transcript scoped to the pane's
     * cwd. This is the trustworthy-live-agent-evidence fallback for a
     * CONFIRMED-SHELL session whose host agent-kind daemon returned `unknown`
     * (NOT `none`) — the node-wrapped / quiet `claude` the cgroup-v2/`/proc`
     * classify cannot see, while its `*.jsonl` transcript is plainly present in
     * the cwd-encoded log dir. The daemon `unknown` verdict means "we could not
     * read the scope", which is exactly the masked-agent state; a fresh,
     * recent transcript in the cwd is then strong enough evidence to bind the
     * agent and clear the stale recorded-shell verdict (so the Conversation
     * toggle returns — #962 recurrence).
     *
     * It enumerates ALL kinds' candidates for the cwd in ONE exec (the SAME
     * [detectionCommand] the kind-fixed path uses), picks the MOST-RECENT
     * candidate's kind, and runs the SAME [selectRecordedCandidate] discipline
     * for that kind (Codex `/proc/<pid>/fd` owned-rollout binding included). A
     * genuine shell with no recent agent transcript enumerates NOTHING → returns
     * null → no flap (the #894 invariant holds: only a real live transcript
     * binds, so a plain shell never resurrects a Conversation surface). The 2h
     * `-mmin -120` freshness window in [detectionCommand] is what makes the
     * transcript "live" rather than a stale leftover.
     *
     * This is deliberately a SCOPED fallback — it runs ONLY when the daemon said
     * `unknown` for a recorded-shell pane (the masked-agent case), never as a
     * blanket kind guesser (the deleted output-parsing detector stays deleted —
     * D22). The KIND still comes from the transcript that genuinely exists, not
     * from output parsing.
     */
    suspend fun detectLiveTranscriptForPane(
        session: SshSession,
        cwd: String,
        paneTty: String,
        paneCommand: String,
    ): AgentDetection? {
        val normalizedCwd = cwd.trim().ifBlank { return null }
        val normalizedTty = paneTty.trim().ifBlank { return null }
        val candidates = session.exec(detectionCommand(normalizedCwd))
            .stdout
            .lineSequence()
            .mapNotNull(::parseCandidate)
            .toList()
        if (candidates.isEmpty()) return null
        // Pick the kind of the MOST-RECENT candidate scoped to this cwd — the
        // transcript that is actually live now. Resolve that single kind through
        // the SAME selection discipline (the kind is taken FROM the present
        // transcript, never guessed by output parsing).
        val recordedKind = candidates.maxByOrNull { it.modifiedAtMillis }?.agent ?: return null
        return selectRecordedCandidate(
            session = session,
            normalizedCwd = normalizedCwd,
            normalizedTty = normalizedTty,
            paneCommand = paneCommand,
            recordedKind = recordedKind,
            recordedSource = null,
            candidates = candidates.filter { it.agent == recordedKind },
        )
    }

    /**
     * Epic #821 slice #3 (#825) / Issue #828: shared selection step for a
     * recorded session, given the already-enumerated [candidates] of the
     * recorded kind. Split out so BOTH the standalone
     * [detectRecordedSessionForPane] (kind already known/cached) AND the
     * single-round-trip [resolveRecordedSessionOpen] (kind + candidates read in
     * ONE exec) reuse the EXACT same #819/#554 selection discipline — the
     * recorded-Codex `/proc/<pid>/fd` owned-rollout binding, the Claude/OpenCode
     * no-process-match selection, and the host-wide `ps` scan that is issued
     * ONLY for Codex.
     */
    private suspend fun selectRecordedCandidate(
        session: SshSession,
        normalizedCwd: String,
        normalizedTty: String,
        paneCommand: String,
        recordedKind: AgentKind,
        recordedSource: String?,
        candidates: List<AgentLogCandidate>,
    ): AgentDetection? {
        val nowMillis = System.currentTimeMillis()
        if (recordedKind != AgentKind.Codex) {
            exactRecordedSourceDetection(
                recordedKind = recordedKind,
                recordedSource = recordedSource,
                candidates = candidates,
            )?.let { return it }
        }
        // Issue #828 (perf): the process scan is ONLY load-bearing for the
        // recorded-Codex path — it feeds the `/proc/<pid>/fd` owned-rollout
        // resolution that disambiguates same-cwd Codex siblings (#819) and the
        // `requireProcessMatch` gate below. Claude / OpenCode carry the session
        // id IN their candidate path and select on `requireProcessMatch = false`,
        // so [AgentDetector.detect] never consults `processLines` for them — the
        // host-wide `ps` round-trip was pure waste on the recorded-Claude /
        // -OpenCode open path the #818 default exercises. Skip it for those
        // kinds so the cold open drops from candidate-enum + ps + window-read to
        // candidate-enum + window-read (one fewer serial SSH round-trip at
        // realistic RTT). The recorded-Codex path keeps the scan unchanged.
        val needsProcessScan = recordedKind == AgentKind.Codex
        val paneProcesses = if (needsProcessScan) {
            val ttyArg = normalizedTty.removePrefix("/dev/")
            val processSnapshot = session.exec(PROCESS_TREE_SCAN_COMMAND)
                .stdout
                .lines()
                .filter { it.isNotBlank() && !it.trimStart().startsWith("PID") }
            processLinesForPane(processSnapshot, ttyArg)
        } else {
            emptyList()
        }
        val processLines = if (!needsProcessScan || paneCommand.isBlank()) {
            paneProcesses
        } else {
            paneProcesses + paneCommand
        }
        // Codex has no session-id-in-path, so a same-cwd sibling Codex rollout
        // would still win an mtime race even within the recorded kind. Bind to
        // the rollout the pane's OWN process holds open (the #819 mechanism),
        // scoped here to Codex by the recorded kind rather than detected kind.
        val processOwnedSourcePaths =
            if (recordedKind == AgentKind.Codex) {
                resolveProcessOwnedCodexRollouts(session, pidsFromProcessRows(paneProcesses))
            } else {
                emptySet()
            }
        val selectableCandidates = if (recordedKind == AgentKind.Codex) {
            candidates.withProcessOwnedCodexRollouts(
                ownedSourcePaths = processOwnedSourcePaths,
                normalizedCwd = normalizedCwd,
                nowMillis = nowMillis,
            )
        } else {
            candidates
        }
        if (selectableCandidates.isEmpty()) return null
        // Claude / OpenCode carry the session id IN their candidate path, so
        // the most-recent candidate of the recorded kind, scoped to this cwd, is
        // the right source — and we do NOT gate on a live process match: the
        // kind is recorded (authoritative), so requiring a process match would
        // re-introduce the null-detection flapping #554 fought for a kind we
        // already KNOW (idle agent between turns, slow ps).
        //
        // Codex has no session-id-in-path, so we DO use the process-confirmed
        // owned-rollout selection ([processOwnedSourcePaths] is consulted only
        // on the requireProcessMatch path) to bind to THIS pane's rollout rather
        // than a busier same-cwd sibling. With a single Codex candidate this
        // still resolves without an owner signal; with multiple candidates, the
        // detector requires the fd-owned path instead of guessing newest.
        val requireProcessMatch = recordedKind == AgentKind.Codex
        val requireProcessOwnedSourcePath =
            recordedKind == AgentKind.Codex && selectableCandidates.size >= 2
        return detector.detect(
            cwd = normalizedCwd,
            nowMillis = nowMillis,
            candidates = selectableCandidates,
            processLines = processLines,
            requireProcessMatch = requireProcessMatch,
            processOwnedSourcePaths = processOwnedSourcePaths,
            requireProcessOwnedSourcePath = requireProcessOwnedSourcePath,
        )
    }

    private fun exactRecordedSourceDetection(
        recordedKind: AgentKind,
        recordedSource: String?,
        candidates: List<AgentLogCandidate>,
    ): AgentDetection? {
        val source = recordedSource?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val candidate = candidates.firstOrNull { it.agent == recordedKind && it.path == source }
            ?: return null
        return AgentDetection(
            agent = recordedKind,
            sourcePath = candidate.path,
            sessionId = candidate.sessionId ?: candidate.path.substringAfterLast('/').substringBeforeLast('.'),
            confidence = AgentDetection.Confidence.RecentFile,
        )
    }

    /**
     * Issue #819: a pane-owned Codex process can hold open a rollout that the
     * normal `find -mmin -120` enumeration skipped because Codex has been idle
     * for longer than the freshness window. The open fd is stronger identity
     * evidence than mtime, so feed those paths into selection as live Codex
     * candidates instead of forcing the selector to choose among only the
     * mmin-enumerated siblings.
     */
    private fun List<AgentLogCandidate>.withProcessOwnedCodexRollouts(
        ownedSourcePaths: Set<String>,
        normalizedCwd: String,
        nowMillis: Long,
    ): List<AgentLogCandidate> {
        if (ownedSourcePaths.isEmpty()) return this
        val existingPaths = mapTo(mutableSetOf()) { it.path }
        val added = ownedSourcePaths
            .asSequence()
            .filter { it !in existingPaths }
            .map { path ->
                AgentLogCandidate(
                    agent = AgentKind.Codex,
                    path = path,
                    modifiedAtMillis = nowMillis,
                    sessionId = path.substringAfterLast('/').substringBeforeLast('.'),
                    cwd = normalizedCwd,
                )
            }
            .toList()
        if (added.isEmpty()) return this
        return this + added
    }

    /**
     * Issue #828 (perf): the outcome of the single-round-trip recorded-open
     * resolution. [recordedKind] is the `@ps_agent_kind` read in the SAME exec
     * as the candidate enumeration (`null` = a FOREIGN session with no recorded
     * kind — the caller then falls back to foreign detection). [detection] is
     * the resolved source for a recorded Claude/OpenCode session (or `null` when
     * no candidate of the recorded kind exists yet).
     *
     * [prefetchedWindow] is the FIRST transcript window, fetched in the SAME exec
     * for a recorded CLAUDE session (the #818 default): the Claude source is
     * the most-recent `*.jsonl` under `~/.claude/projects/<encoded-cwd>/`, so the
     * combined exec also `wc -l`s + tails it. When present the caller skips the
     * separate `readEventsWindow` round-trip entirely — the cold open is then ONE
     * SSH round-trip total (kind + source + first window), making it ≈ the warm
     * switch. `null` for OpenCode/Codex (their sources can't be tailed as a flat
     * JSONL in the same shell) — those keep the separate window read.
     *
     * For a recorded CODEX session [detection]/[prefetchedWindow] are `null` and
     * [needsCodexResolution] is `true`: Codex has no session-id-in-path, so its
     * source needs the `/proc/<pid>/fd` owned-rollout pass (a second exec) — the
     * caller completes it via [detectRecordedSessionForPane].
     */
    data class RecordedSessionOpen(
        val recordedKind: AgentKind?,
        val recordedSource: String?,
        val recordedSourceGenerationScoped: Boolean,
        val detection: AgentDetection?,
        val needsCodexResolution: Boolean,
        val prefetchedWindow: ConversationEventsWindow? = null,
    )

    /**
     * Issue #828 (perf): resolve a recorded session's kind, its Claude/OpenCode
     * source, AND (for Claude) prefetch its first transcript window — all in a
     * SINGLE SSH round-trip. This is THE cold-open lever.
     *
     * The #825 split path paid THREE serial SSH round-trips before content was
     * live: `readRecordedAgentKind`, then the candidate enumeration, then the
     * window read. Each `session.exec` opens a fresh SSH channel (a round-trip in
     * itself), so at realistic RTT three execs were ~690 ms (#817 measurement).
     * This folds them into ONE exec:
     *   1. `tmux show-options @ps_agent_kind` — the recorded kind.
     *   2. the SAME [detectionCommand] candidate enumeration (kind parse +
     *      candidate parse are byte-identical to the split path).
     *   3. for a recorded CLAUDE kind, the most-recent `*.jsonl` under the
     *      cwd-encoded project dir is the source, so the exec also emits its
     *      `wc -l` + a sentinel + the tail window — the SAME shape
     *      [readEventsWindow] reads, so parsing/`hasMoreOlder`/`tailStartLine`
     *      are identical. The most-recent-within-the-recency-window selection is
     *      exactly what [selectRecordedCandidate] picks for a single-cwd recorded
     *      Claude (the recorded kind is authoritative; no cross-kind race), so
     *      doing it shell-side does not change the chosen source.
     *
     * OpenCode (SQLite, not a flat JSONL) and Codex (`/proc/<pid>/fd` owned
     * rollout) cannot have their window folded into this shell, so they fall back
     * to the standard window read / second resolve pass; the kind read is still
     * saved for them.
     *
     * [sessionTarget] is the tmux `-t` target (session id `$N` or name) the kind
     * is recorded against; a blank [cwd]/[paneTty] short-circuits to a foreign
     * (`recordedKind = null`) result with no I/O, exactly like the per-pane
     * detection contract. [maxMessages] bounds the prefetched window's tail.
     */
    suspend fun resolveRecordedSessionOpen(
        session: SshSession,
        sessionTarget: String,
        cwd: String,
        paneTty: String,
        paneCommand: String,
        maxMessages: Int = FIRST_PAINT_MESSAGE_BUDGET,
    ): RecordedSessionOpen {
        val normalizedCwd = cwd.trim()
        val normalizedTty = paneTty.trim()
        if (normalizedCwd.isBlank() || normalizedTty.isBlank()) {
            return RecordedSessionOpen(
                recordedKind = null,
                recordedSource = null,
                recordedSourceGenerationScoped = false,
                detection = null,
                needsCodexResolution = false,
            )
        }
        val kindSentinel = "@@PS_RECORDED_KIND@@"
        val sourceGenerationSentinel = "@@PS_RECORDED_SOURCE_GENERATION@@"
        val sourceSentinel = "@@PS_RECORDED_SOURCE@@"
        val sourceGenerationSeparator = "\t"
        // The Claude window-fold tail budget mirrors [readEventsWindow]: raw
        // lines = messages * JSONL_RAW_LINES_PER_EVENT, floored at the message
        // count and at 1.
        val claudeRawLineBudget = (maxMessages * JSONL_RAW_LINES_PER_EVENT)
            .coerceAtLeast(maxMessages)
            .coerceAtLeast(1)
        val claudeWindowSentinel = "@@PS_CLAUDE_WINDOW@@"
        val encodedClaudeCwd = detector.encodeClaudeCwd(normalizedCwd)
        val combined = buildString {
            // 1. The recorded-kind read first, then a sentinel. `|| true` keeps
            //    the exec exit code 0 on a foreign session (no option set).
            append(
                "tmux show-options -v -t ${shellQuote(sessionTarget.trim())} @ps_agent_kind 2>/dev/null || true",
            )
            append("\n")
            append("printf '%s\\n' $kindSentinel")
            append("\n")
            // 1b. The exact source recorded by the host-side launch watcher.
            //     Empty on legacy/foreign sessions. Read in the same exec so it
            //     adds no round-trip to the cold-open path.
            append(
                "ps_recorded_source_generation=\$(" +
                    "tmux show-options -v -t ${shellQuote(sessionTarget.trim())} " +
                    "@ps_agent_source_generation 2>/dev/null || true" +
                    "); printf '%s\\n' \"\$ps_recorded_source_generation\"",
            )
            append("\n")
            append("printf '%s\\n' $sourceGenerationSentinel")
            append("\n")
            append(
                "ps_recorded_source=\$(" +
                    "tmux show-options -v -t ${shellQuote(sessionTarget.trim())} @ps_agent_source 2>/dev/null || true" +
                    "); printf '%s\\n' \"\$ps_recorded_source\"",
            )
            append("\n")
            append("printf '%s\\n' $sourceSentinel")
            append("\n")
            // Parse the raw tmux option into the exact path the host watcher
            // recorded. New host CLIs write "<generation><tab><path>"; older
            // hosts wrote just "<path>". Keep this shell-side parse in lockstep
            // with [recordedAgentSourceOptionFromRaw] so the folded Claude
            // window reads the exact recorded file instead of falling back to
            // the newest same-cwd sibling.
            append(
                "ps_recorded_source_path=; " +
                    "if [ -n \"\$ps_recorded_source_generation\" ]; then " +
                    "ps_recorded_source_prefix=\"\$ps_recorded_source_generation$sourceGenerationSeparator\"; " +
                    "case \"\$ps_recorded_source\" in " +
                    "\"\$ps_recorded_source_prefix\"*) " +
                    "ps_recorded_source_path=\${ps_recorded_source#\"\$ps_recorded_source_prefix\"};; " +
                    "esac; " +
                    "else " +
                    "case \"\$ps_recorded_source\" in " +
                    "*\"$sourceGenerationSeparator\"*) " +
                    "ps_recorded_source_path=\${ps_recorded_source#*$sourceGenerationSeparator};; " +
                    "*) ps_recorded_source_path=\$ps_recorded_source;; " +
                    "esac; " +
                    "fi",
            )
            append("\n")
            // 2. The SAME candidate enumeration the split path runs.
            append(detectionCommand(normalizedCwd))
            append("\n")
            // 3. The Claude window fold: pick the most-recent *.jsonl in the
            //    cwd-encoded Claude project dir (the recorded-Claude source) and
            //    emit its wc -l + a sentinel + the tail window — the SAME shape
            //    [readEventsWindow] reads. Best-effort: if the dir/file is
            //    missing (not yet a Claude session, or a recorded OpenCode/Codex
            //    kind) nothing useful is printed and the caller skips the prefetch.
            //    Uses the SAME `find -print` + `stat -c '%Y'` mechanics as
            //    [detectionCommand] (BusyBox-compatible — no `find -printf`), so
            //    the most-recent-within-the-2h-window pick equals the JVM
            //    selection for a single-cwd recorded Claude.
            append("printf '%s\\n' $claudeWindowSentinel")
            append("\n")
            append(
                    "claude_proj=\"\$HOME/.claude/projects/$encodedClaudeCwd\"; " +
                    "if [ -n \"\$ps_recorded_source_path\" ] && [ -f \"\$ps_recorded_source_path\" ]; then " +
                    "newest=\"\$ps_recorded_source_path\"; " +
                    "else " +
                    "newest=\$(" +
                    "find \"\$claude_proj\" -maxdepth 1 -type f -name '*.jsonl' -mmin -120 -print 2>/dev/null | " +
                    "while IFS= read -r f; do " +
                    "m=\$(stat -c '%Y' \"\$f\" 2>/dev/null) || continue; " +
                    "printf '%s %s\\n' \"\$m\" \"\$f\"; " +
                    "done | sort -rn | head -n 1 | cut -d' ' -f2-" +
                    "); fi; " +
                    "if [ -n \"\$newest\" ]; then " +
                    "printf 'PATH=%s\\n' \"\$newest\"; " +
                    "wc -l < \"\$newest\" 2>/dev/null || printf 0; " +
                    "printf '\\n%s\\n' $claudeWindowSentinel; " +
                    "tail -n $claudeRawLineBudget \"\$newest\" 2>/dev/null || true; " +
                    "fi",
            )
        }
        val out = session.exec(combined).stdout
        val lines = out.split("\n")
        val kindSentinelIndex = lines.indexOf(kindSentinel)
        val rawKind = if (kindSentinelIndex >= 0) {
            lines.take(kindSentinelIndex).joinToString("\n").trim().ifBlank { null }
        } else {
            null
        }
        val recordedKind = recordedAgentKindFromOption(rawKind)
            ?: return RecordedSessionOpen(
                recordedKind = null,
                recordedSource = null,
                recordedSourceGenerationScoped = false,
                detection = null,
                needsCodexResolution = false,
            )
        val afterKindRaw = if (kindSentinelIndex >= 0) lines.drop(kindSentinelIndex + 1) else lines
        val sourceGenerationSentinelIndex = afterKindRaw.indexOf(sourceGenerationSentinel)
        val rawSourceGeneration = if (sourceGenerationSentinelIndex >= 0) {
            afterKindRaw.take(sourceGenerationSentinelIndex).joinToString("\n").trim().ifBlank { null }
        } else {
            null
        }
        val afterSourceGeneration = if (sourceGenerationSentinelIndex >= 0) {
            afterKindRaw.drop(sourceGenerationSentinelIndex + 1)
        } else {
            afterKindRaw
        }
        val sourceSentinelIndex = afterSourceGeneration.indexOf(sourceSentinel)
        val rawSource = if (sourceSentinelIndex >= 0) {
            afterSourceGeneration.take(sourceSentinelIndex).joinToString("\n").trim().ifBlank { null }
        } else {
            null
        }
        val recordedSourceOption = recordedAgentSourceOptionFromRaw(rawSource, rawSourceGeneration)
        val recordedSource = recordedSourceOption.source
        // Candidate rows live between the source sentinel and the FIRST Claude
        // window sentinel (the section-2 enumeration). Everything from the
        // SECOND Claude window sentinel onward is the prefetched window.
        val afterKind = if (kindSentinelIndex >= 0) lines.drop(kindSentinelIndex + 1) else lines
        val afterSource = if (sourceSentinelIndex >= 0) {
            afterSourceGeneration.drop(sourceSentinelIndex + 1)
        } else {
            afterKind
        }
        val firstClaudeSentinel = afterSource.indexOf(claudeWindowSentinel)
        val candidateLines = if (firstClaudeSentinel >= 0) {
            afterSource.take(firstClaudeSentinel)
        } else {
            afterSource
        }
        val candidates = candidateLines
            .asSequence()
            .mapNotNull(::parseCandidate)
            .filter { it.agent == recordedKind }
            .toList()
        // Codex needs the second `/proc/<pid>/fd` pass; defer to the caller so
        // the owned-rollout binding (#819) stays in exactly one place.
        if (recordedKind == AgentKind.Codex) {
            return RecordedSessionOpen(
                recordedKind = recordedKind,
                recordedSource = recordedSource,
                recordedSourceGenerationScoped = recordedSourceOption.generationScoped,
                detection = null,
                needsCodexResolution = true,
            )
        }
        val detection = selectRecordedCandidate(
            session = session,
            normalizedCwd = normalizedCwd,
            normalizedTty = normalizedTty,
            paneCommand = paneCommand,
            recordedKind = recordedKind,
            recordedSource = recordedSource,
            candidates = candidates,
        ) ?: return RecordedSessionOpen(
            recordedKind = recordedKind,
            recordedSource = recordedSource,
            recordedSourceGenerationScoped = recordedSourceOption.generationScoped,
            detection = null,
            needsCodexResolution = false,
        )
        // Parse the folded Claude window (only Claude prefetches in-exec). The
        // selected source MUST match the shell's most-recent pick for the fold to
        // be valid; if the shell printed a different/blank path, drop the prefetch
        // and let the caller do the normal window read (correctness over the
        // saved round-trip).
        val prefetchedWindow = if (recordedKind == AgentKind.ClaudeCode) {
            parseFoldedClaudeWindow(
                lines = if (firstClaudeSentinel >= 0) {
                    afterSource.drop(firstClaudeSentinel + 1)
                } else {
                    emptyList()
                },
                expectedSourcePath = detection.sourcePath,
                sentinel = claudeWindowSentinel,
                rawLineBudget = claudeRawLineBudget,
            )
        } else {
            null
        }
        return RecordedSessionOpen(
            recordedKind = recordedKind,
            recordedSource = recordedSource,
            recordedSourceGenerationScoped = recordedSourceOption.generationScoped,
            detection = detection,
            needsCodexResolution = false,
            prefetchedWindow = prefetchedWindow,
        )
    }

    /**
     * Issue #828 (perf): parse the Claude transcript window folded into the
     * single-round-trip [resolveRecordedSessionOpen] exec. [lines] is the
     * remainder after the section-2 enumeration; its shape is
     * `PATH=<path>` then `<wc-l>` then the window sentinel then the raw tail.
     * Returns `null` (caller does the normal window read) when the shell printed
     * no path, or a path that does not match [expectedSourcePath] — so the
     * prefetch can never bind a window from the wrong file. Parsing/`hasMoreOlder`/
     * `tailStartLine` mirror [readEventsWindow]'s Claude branch exactly.
     */
    private fun parseFoldedClaudeWindow(
        lines: List<String>,
        expectedSourcePath: String,
        sentinel: String,
        rawLineBudget: Int,
    ): ConversationEventsWindow? {
        val pathLine = lines.firstOrNull { it.startsWith("PATH=") } ?: return null
        val foldedPath = pathLine.removePrefix("PATH=").trim()
        if (foldedPath.isBlank() || foldedPath != expectedSourcePath) return null
        val sentinelIndex = lines.indexOf(sentinel)
        if (sentinelIndex < 0) return null
        // The wc -l output sits between PATH= and the sentinel.
        val totalLines = lines
            .subList(lines.indexOf(pathLine) + 1, sentinelIndex)
            .joinToString("")
            .trim()
            .toLongOrNull() ?: 0L
        val tailRawLines = lines.drop(sentinelIndex + 1)
        val parser = ClaudeCodeParser()
        val events = tailRawLines.asSequence().flatMap { parser.parseLine(it) }.toList()
        return ConversationEventsWindow(
            events = events,
            hasMoreOlder = totalLines > rawLineBudget,
            tailStartLine = totalLines,
        )
    }

    /**
     * Issue #819: extract the PIDs of a pane's process subtree from the
     * `ps -eo pid,ppid,tty,comm,args` rows [processLinesForPane] returns. The
     * first whitespace-delimited token of each row is the PID.
     */
    private fun pidsFromProcessRows(rows: List<String>): List<Long> =
        rows.mapNotNull { row ->
            row.trim().substringBefore(' ').toLongOrNull()
        }.distinct()

    /**
     * Issue #819: resolve which `~/.codex/sessions/**/*.jsonl` rollout files
     * are held OPEN by the given pane-owned PIDs, via `/proc/<pid>/fd`. The
     * Codex CLI keeps its active rollout file descriptor open for the life of
     * the session, so the open fd uniquely identifies the rollout belonging
     * to THIS pane's Codex process — even when a sibling Codex shares the
     * same cwd and flushes more recently.
     *
     * Best-effort: on a non-Linux remote (no `/proc`), a Codex build that
     * doesn't hold the fd open, or any read failure, this returns an empty
     * set and the detector degrades to its previous mtime selection.
     */
    private suspend fun resolveProcessOwnedCodexRollouts(
        session: SshSession,
        pids: List<Long>,
    ): Set<String> {
        if (pids.isEmpty()) return emptySet()
        val pidList = pids.joinToString(" ")
        // `readlink` each open fd; keep only paths under .codex/sessions/
        // ending in .jsonl. `2>/dev/null` and `|| true` keep the command
        // silent on permission errors / missing /proc.
        val command =
            "for p in $pidList; do " +
                "for fd in /proc/\$p/fd/*; do " +
                "t=\$(readlink \"\$fd\" 2>/dev/null) || continue; " +
                "case \"\$t\" in */.codex/sessions/*.jsonl) printf '%s\\n' \"\$t\";; esac; " +
                "done; " +
                "done 2>/dev/null || true"
        return runCatching {
            session.exec(command).stdout
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && it.contains("/.codex/sessions/") && it.endsWith(".jsonl") }
                .toSet()
        }.getOrDefault(emptySet())
    }

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
