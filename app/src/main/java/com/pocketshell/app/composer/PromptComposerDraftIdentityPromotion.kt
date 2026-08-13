package com.pocketshell.app.composer

import kotlinx.coroutines.flow.update

/**
 * Reopened #1602/#2034: fallback host/name -> durable tmux key is an identity
 * refinement for one exact generation, not a session switch. Preserve the
 * byte-exact editor and attachment identities while its durable owner changes.
 */
internal fun PromptComposerViewModel.promoteFallbackComposerIdentity(
    fallbackSessionKey: String,
    durableSessionKey: String,
) {
    if (fallbackSessionKey.isBlank() || durableSessionKey.isBlank() ||
        fallbackSessionKey == durableSessionKey
    ) return

    val target = composerTarget
    val sourceIsVisible = target == fallbackSessionKey
    val draft = if (sourceIsVisible) {
        _uiState.value.draft
    } else {
        loadComposerDraft(fallbackSessionKey).orEmpty()
    }
    val liveAttachments = if (sourceIsVisible) _uiState.value.attachments else emptyList()
    val durableAttachments = if (sourceIsVisible) {
        liveAttachments.toDurableRefs()
    } else {
        composerDraftStore.loadAttachments(fallbackSessionKey)
    }

    if (draft.isEmpty() && durableAttachments.isEmpty()) {
        composerRevisionTracker.promoteIdentity(fallbackSessionKey, durableSessionKey)
        // No fallback draft to re-key. Preserve an already-loaded durable draft,
        // or use the ordinary target reducer to load it now.
        if (target == fallbackSessionKey) onComposerTargetChanged(durableSessionKey)
        return
    }

    draftPersistence.promoteIdentity(
        fromSessionKey = fallbackSessionKey,
        toSessionKey = durableSessionKey,
        draft = draft,
        attachments = durableAttachments,
    )
    composerRevisionTracker.promoteIdentity(fallbackSessionKey, durableSessionKey)

    // The ordinary target effect may have run first and blanked the editor.
    // Restore from the synchronous fallback override in that ordering; when the
    // fallback is still visible, retain its live preview Uris.
    if (target == fallbackSessionKey || target == durableSessionKey) {
        composerTarget = durableSessionKey
        savedStateHandle[PromptComposerViewModel.KEY_DRAFT] = draft
        savedStateHandle[PromptComposerViewModel.KEY_DRAFT_OWNER] =
            if (draft.isEmpty() && durableAttachments.isEmpty()) null else durableSessionKey
        _uiState.update { current ->
            current.copy(
                draft = draft,
                attachments = if (sourceIsVisible) {
                    liveAttachments
                } else {
                    durableAttachments.toStagedAttachments()
                },
            )
        }
        refreshOutboundQueueItems()
        rehydrateDraftAttachmentBytes(durableSessionKey)
    }
}
