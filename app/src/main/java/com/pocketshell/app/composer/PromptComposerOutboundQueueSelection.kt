package com.pocketshell.app.composer

/**
 * Small, deterministic selection rules for the composer-facing outbound queue.
 * The ViewModel owns side effects; this file owns the row predicates shared by
 * retry, delete, and deferred-send recovery.
 */
/**
 * Issue #1602: the bounded auto-retry budget for one outbound row. The reconnect
 * auto-flush re-claims the oldest retryable row each cycle; a row that has
 * FAILED/wedged this many delivery attempts is a permanently-stuck head-of-line
 * entry and is PARKED (`Failed`, surfaced) so the drain skips it and the healthy
 * tail drains. A healthy send delivers + prunes within one or two attempts, so it
 * never reaches this bound; only a row that fails EVERY attempt does. A user's
 * per-row Retry bypasses the bound, and `resendAllQueued` resets it, so a parked
 * row is only auto-skipped, never permanently un-sendable (hard-cut D22).
 */
internal const val OUTBOUND_MAX_AUTO_ATTEMPTS: Int = 6

/** Issue #1602: surfaced "Failed — <this>" label a parked (budget-exhausted) row wears. */
internal const val OUTBOUND_AUTO_RETRY_EXHAUSTED_MESSAGE: String =
    "Couldn't send after several tries. Tap Retry."

internal fun OutboundItem.isComposerQueueRetryable(): Boolean =
    state == OutboundState.Queued || state == OutboundState.Failed

internal fun OutboundItem.isComposerQueueUndelivered(): Boolean =
    state != OutboundState.Delivered

internal fun Iterable<OutboundItem>.firstComposerQueueRetryable(
    sessionKey: String,
    excludingIds: Set<String> = emptySet(),
): OutboundItem? =
    firstOrNull { item ->
        item.sessionKey == sessionKey &&
            item.id !in excludingIds &&
            item.isComposerQueueRetryable()
    }

/**
 * Issue #1602: the AUTO-flush selection — the oldest retryable row for
 * [sessionKey] whose bounded auto-retry budget is NOT yet exhausted
 * ([OutboundItem.attemptCount] < [maxAutoAttempts]). A row that has failed/wedged
 * this many delivery attempts is a permanently-stuck HEAD; skipping it here (it is
 * parked as `Failed` + surfaced by [autoRetryExhaustedComposerRows]) lets the
 * healthy TAIL drain instead of the drain re-picking the stuck head forever (the
 * head-of-line clog the #1562 audit reported after a reconnect). A user's explicit
 * per-row Retry bypasses this bound (it dispatches the row directly), so a parked
 * row is never permanently un-sendable — only auto-skipped.
 */
internal fun Iterable<OutboundItem>.firstComposerAutoFlushable(
    sessionKey: String,
    excludingIds: Set<String> = emptySet(),
    maxAutoAttempts: Int,
): OutboundItem? =
    firstOrNull { item ->
        item.sessionKey == sessionKey &&
            item.id !in excludingIds &&
            item.isComposerQueueRetryable() &&
            item.attemptCount < maxAutoAttempts
    }

/**
 * Issue #1602: retryable rows for [sessionKey] whose bounded auto-retry budget is
 * exhausted ([OutboundItem.attemptCount] >= [maxAutoAttempts]) and that are still
 * sitting `Queued` (a wedged head re-armed by the deferred/auto-retry path). The
 * auto-flush parks these as `Failed` so they are SURFACED (the launcher badge's
 * failure state + a "Failed — …" label) instead of silently blocking the tail —
 * skip-AND-surface, never a silent drop.
 */
internal fun Iterable<OutboundItem>.autoRetryExhaustedComposerRows(
    sessionKey: String,
    excludingIds: Set<String> = emptySet(),
    maxAutoAttempts: Int,
): List<OutboundItem> =
    filter { item ->
        item.sessionKey == sessionKey &&
            item.id !in excludingIds &&
            item.state == OutboundState.Queued &&
            item.attemptCount >= maxAutoAttempts
    }

/** Issue #1602: the auto-flush decision — ids to park (exhausted heads) + the next dispatchable id. */
internal data class ComposerAutoFlushPlan(val parkIds: List<String>, val nextId: String?)

/**
 * Issue #1602: plan one auto-flush cycle for [sessionKey]. Parking (Queued→Failed)
 * an exhausted head does not change whether it is dispatchable (the attempt-bound
 * filter already excludes it), so [nextId] is computed from the same snapshot.
 */
internal fun Iterable<OutboundItem>.planComposerAutoFlush(
    sessionKey: String,
    excludingIds: Set<String> = emptySet(),
    maxAutoAttempts: Int = OUTBOUND_MAX_AUTO_ATTEMPTS,
): ComposerAutoFlushPlan = ComposerAutoFlushPlan(
    parkIds = autoRetryExhaustedComposerRows(sessionKey, excludingIds, maxAutoAttempts).map { it.id },
    nextId = firstComposerAutoFlushable(sessionKey, excludingIds, maxAutoAttempts)?.id,
)

internal fun Iterable<OutboundItem>.composerQueueRetryableItems(): List<OutboundItem> =
    filter { it.isComposerQueueRetryable() }

internal fun Iterable<OutboundItem>.deferredRetryCandidateFor(
    request: PromptComposerViewModel.SendRequest,
): OutboundItem? {
    val candidates = filter { it.isComposerQueueUndelivered() }
    return candidates.firstOrNull { it.cleanText == request.cleanDraft }
        ?: candidates.firstOrNull()
}

/**
 * Issue #1531 (audit RC1): the compact unsent-queue summary the DOCKED composer
 * launcher renders so a queued / deferred / uploading / failed send is VISIBLE
 * on the session screen — not only inside the opened composer sheet. Before
 * this, the launcher had zero queue reference, so a send stuck behind a silent
 * transport flap looked identical to "nothing pending" — the maintainer's
 * "silently dropped" symptom. [count] is every undelivered row for the current
 * session (Queued / Uploading / InFlight / Failed); [hasFailure] flags any row
 * the auto-flush has escalated to [OutboundState.Failed] so the badge can read
 * as an ERROR (needs attention / retry) rather than a calm "in-flight" pending.
 */
internal data class OutboundLauncherBadge(
    val count: Int,
    val hasFailure: Boolean,
)

/**
 * Issue #1531 (audit RC1): summarise the current session's undelivered outbound
 * rows for the docked launcher badge. Returns `null` when nothing is pending
 * (no badge), so an empty queue leaves the launcher unchanged.
 */
internal fun Iterable<OutboundItem>.outboundLauncherBadge(
    sessionKey: String,
): OutboundLauncherBadge? {
    val undelivered = filter { it.sessionKey == sessionKey && it.isComposerQueueUndelivered() }
    if (undelivered.isEmpty()) return null
    return OutboundLauncherBadge(
        count = undelivered.size,
        hasFailure = undelivered.any { it.state == OutboundState.Failed },
    )
}
