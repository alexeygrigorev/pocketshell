package com.pocketshell.app.composer

/**
 * Small, deterministic selection rules for the composer-facing outbound queue.
 * The ViewModel owns side effects; this file owns the row predicates shared by
 * retry, delete, and deferred-send recovery.
 */
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
