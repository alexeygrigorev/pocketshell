package com.pocketshell.app.composer

/** Promote temporary host/name rows after exact live pane-generation proof. */
internal fun PromptComposerViewModel.promoteFallbackOutboundIdentity(
    fallbackSessionKey: String,
    durableSessionKey: String,
    livePaneIds: Set<String>,
    tmuxSessionId: String,
    tmuxSessionCreated: Long,
): List<OutboundItem> {
    // Reopened #1602/#2034: this exact pane-generation proof says the fallback
    // and durable keys name ONE live composer. Re-key the live draft before the
    // ordinary target-switch reducer can load an empty destination slot.
    promoteFallbackComposerIdentity(fallbackSessionKey, durableSessionKey)
    val sourceRows = outboundQueueStore.itemsFor(fallbackSessionKey)
    val promoted = outboundQueueStore.promoteSessionIdentity(
        fromSessionKey = fallbackSessionKey,
        toSessionKey = durableSessionKey,
        livePaneIds = livePaneIds,
        tmuxSessionId = tmuxSessionId,
        tmuxSessionCreated = tmuxSessionCreated,
    )
    if (promoted.isNotEmpty()) {
        ComposerQueueDiagnostics.identityPromotion(
            oldSessionKey = fallbackSessionKey,
            newSessionKey = durableSessionKey,
            sourceRows = sourceRows,
            rows = promoted,
            reason = "same_generation_pane_membership",
        )
        refreshOutboundQueueItemsFor(durableSessionKey)
    }
    return promoted
}
