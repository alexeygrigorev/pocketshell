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
