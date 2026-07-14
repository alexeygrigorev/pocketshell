package com.pocketshell.app.composer

import androidx.lifecycle.viewModelScope
import com.pocketshell.app.composer.PromptComposerViewModel.AttachmentUploadState
import com.pocketshell.app.composer.PromptComposerViewModel.SendRequest
import com.pocketshell.app.composer.PromptComposerViewModel.SendTargetSnapshot
import com.pocketshell.app.composer.PromptComposerViewModel.StagedAttachment
import com.pocketshell.app.diagnostics.DiagnosticEvents
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Issue #971 / #1540: the outbound-send dispatch surface of
 * [PromptComposerViewModel], split out of the god-object VM (D28 / file-size
 * hygiene ratchet) into cohesive same-module `internal` extensions.
 *
 * This is the write-ahead handoff path: [emitSendRequest] flips the composer
 * in-flight, and the durable outbound-queue row ([enqueueOutboundSend] /
 * [enqueueAndDispatchSidecarBackedSend]) is committed BEFORE the editable draft
 * is cleared ([clearComposerForHandoff]) — closing the #1540 (L9) silent-LOST
 * window where a process death between draft-clear and row-commit lost the
 * prompt from both stores.
 */
/**
 * Issue #211 / #745: compose + emit the [SendRequest] from the CURRENT draft
 * and staged tiles, flipping the composer into the in-flight state. Split out
 * of [dispatchSendNow] so the upload-await path ([dispatchSendAfterUpload])
 * shares the exact same emission semantics once the upload has resolved.
 */
internal fun PromptComposerViewModel.emitSendRequest(
    withEnter: Boolean,
    sendTarget: SendTargetSnapshot = SendTargetSnapshot(),
) {
    if (_uiState.value.sendInFlight) return
    // #1531 (RC2): a Send during a sidecar retry upload used to drop SILENTLY.
    if (outboundSidecarDispatchInFlight) {
        _uiState.update { it.copy(error = PromptComposerViewModel.SEND_BUSY_UPLOADING_MESSAGE) }
        return
    }
    val draft = _uiState.value.draft
    val attachments = _uiState.value.attachments
    // Issue #544: compose the outgoing prompt = the user's clean draft
    // + the "Attached files:" suffix appended at the END from whatever
    // tiles remain at send time. The draft stayed clean while composing;
    // the agent still receives the remote paths.
    val text = appendAttachmentPaths(draft, attachments.map { it.remotePath })
    // Send when there is either typed text or at least one attachment.
    // (A pure-attachment send still has a non-empty composed `text`.)
    if (text.isEmpty()) return
    DiagnosticEvents.record(
        "action",
        "composer_send",
        "textBytes" to text.toByteArray(Charsets.UTF_8).size,
        "attachmentCount" to attachments.size,
        "withEnter" to withEnter,
    )
    // Issue #971: HAND THE PROMPT OFF to the outbound queue so the prompt is
    // represented EXACTLY ONCE (the "Sending…" queue row), never as BOTH the
    // editor text AND a duplicate "1 unsent prompt" row. This reverses the
    // #745 keep-the-draft-visible behaviour (hard-cut per D22): the queue row
    // is the single in-flight source of truth, and the Send button reads
    // `sendInFlight` to disable itself + show "Sending…". The clean draft +
    // tiles still travel in the [SendRequest] / queue row, so a failed send
    // ([restoreFailedSend]) puts the EXACT prompt back into the (now-single)
    // composer, and a delivered send leaves it empty.
    //
    // Issue #1540 (L9 — the widest silent-LOST hole): the composer clear
    // ([clearComposerForHandoff]) USED to run HERE, synchronously, BEFORE the
    // durable outbound-queue row was committed (an async IO hop below; the
    // sidecar path staged bytes first, widening the window). If the process
    // died in that window the prompt existed in NEITHER the durable draft NOR
    // the durable queue — gone with zero trace. The fix WRITE-AHEADS the
    // durable row: the clear is deferred into each durable path and runs ONLY
    // AFTER the row commit succeeds ([enqueueOutboundSend] /
    // [enqueueAndDispatchSidecarBackedSend]), so a crash in the window always
    // leaves the prompt recoverable in EXACTLY ONE durable place — the draft
    // (before commit) or the queue row (after commit) — never lost. The
    // no-durable-store fallback has no row to write-ahead, so it clears
    // synchronously just before emitting (no IO hop, nothing to lose).
    _uiState.update { it.copy(sendInFlight = true, error = null) }
    // Issue #891: arm the overall-send watchdog the instant we go in-flight
    // so a host `onSend` that never resolves (wedged channel / dropped
    // emission with no live collector) can never leave the composer stuck on
    // "Sending…" forever.
    watchdogs.armSend()
    val handoffRequest = SendRequest(
        text = text,
        withEnter = withEnter,
        cleanDraft = draft,
        attachments = attachments,
        sendTarget = sendTarget,
    )
    inFlightSendRequest = handoffRequest
    if (sendTarget.sessionKey.isNotBlank() && hasLocalAttachmentsForSidecars(attachments)) {
        viewModelScope.launch(outboundQueueDispatcher) {
            try {
                enqueueAndDispatchSidecarBackedSend(
                    cleanDraft = draft,
                    attachments = attachments,
                    withEnter = withEnter,
                    sendTarget = sendTarget,
                )
            } catch (cancelled: CancellationException) {
                // Issue #929: a real cancellation (e.g. VM cleared) — clear
                // the in-flight gates so a recreated composer is not wedged,
                // then rethrow to honour structured concurrency.
                clearStrandedSendInFlight()
                throw cancelled
            } catch (t: Throwable) {
                // Issue #929: any unexpected failure in the sidecar dispatch
                // is still a non-delivering exit — clear the strand so the
                // pipeline self-heals instead of waiting on the watchdog.
                clearStrandedSendInFlight(
                    error = "Send failed: reconnect, then send again or discard the draft.",
                )
            }
        }
        return
    }
    if (outboundQueueStore !== DisabledOutboundQueueStore &&
        sendTarget.sessionKey.isNotBlank()
    ) {
        viewModelScope.launch(outboundQueueDispatcher) {
            try {
                val outboundItem = enqueueOutboundSend(
                    cleanDraft = draft,
                    attachments = attachments,
                    withEnter = withEnter,
                    sendTarget = sendTarget,
                )
                withContext(Dispatchers.Main.immediate) {
                    // Issue #1540 (L9): the durable row is committed — NOW it
                    // is safe to clear the editable draft + durable draft. A
                    // crash before this point leaves the prompt in the durable
                    // draft (nothing was cleared yet); a crash after it leaves
                    // the prompt in the just-committed queue row. Never both
                    // gone at once — the LOST window is closed.
                    clearComposerForHandoff()
                    emitPreparedSendRequest(
                        text = text,
                        withEnter = withEnter,
                        cleanDraft = draft,
                        attachments = attachments,
                        sendTarget = sendTarget,
                        outboundQueueItemId = outboundItem?.id,
                    )
                }
            } catch (cancelled: CancellationException) {
                clearStrandedSendInFlight()
                throw cancelled
            } catch (t: Throwable) {
                clearStrandedSendInFlight(
                    error = "Send failed: reconnect, then send again or discard the draft.",
                )
            }
        }
        return
    }
    // Issue #1540 (L9): the no-durable-store fallback (DisabledOutboundQueueStore
    // or a blank sessionKey) has no queue row to write-ahead, so there is no
    // durable-loss window — the prompt only ever travels in the in-memory
    // [SendRequest]. Clear synchronously here, just before emitting, exactly
    // as the pre-#1540 code did.
    clearComposerForHandoff()
    emitPreparedSendRequest(
        text = text,
        withEnter = withEnter,
        cleanDraft = draft,
        attachments = attachments,
        sendTarget = sendTarget,
        outboundQueueItemId = null,
    )
}

internal fun PromptComposerViewModel.emitPreparedSendRequest(
    text: String,
    withEnter: Boolean,
    cleanDraft: String,
    attachments: List<StagedAttachment>,
    sendTarget: SendTargetSnapshot,
    outboundQueueItemId: String?,
) {
    // Issue #254: a `Channel.trySend` buffers the item until a collector
    // consumes it, so a send dispatched while the sheet's collector is
    // mid-recreate (dismiss → re-open) is delivered to the next collector.
    val request = SendRequest(
        text = text,
        withEnter = withEnter,
        cleanDraft = cleanDraft,
        attachments = attachments,
        sendTarget = sendTarget,
        outboundQueueItemId = outboundQueueItemId,
    )
    // Issue #971: remember the in-flight prompt so wedged/cancelled send
    // recovery restores the exact draft + tiles to the now-cleared composer.
    inFlightSendRequest = request
    if (_sendRequests.trySend(request).isFailure) {
        // Issue #971/#987: a buffer-full enqueue is a transient dispatch
        // failure, not a permanent rejection — defer to the queue so the row
        // stays queued and auto-retries on the next flush (Option A). Falls
        // back to a composer-restore only when there is no durable row.
        markOutboundSendDeferred(request)
    }
}

/**
 * Issue #971: hand the prompt off to the outbound queue — empty the editable
 * composer (draft text + staged attachment tiles + the persisted draft slots)
 * so an in-flight send is represented EXACTLY ONCE by its "Sending…" queue
 * row, never as BOTH the editor AND a duplicate "1 unsent prompt" row.
 *
 * This clears the in-memory draft/tiles, the [SavedStateHandle] draft slots
 * (so a process-death recreate mid-send does not resurrect the handed-off
 * text into the editor alongside the queue row), and the durable per-session
 * draft store (so a session switch away-and-back mid-send does not reload the
 * handed-off draft into the editor next to its queue row). The prompt is NOT
 * lost: it travels in the [SendRequest] + the durable queue row, so a failed
 * send ([restoreFailedSend]) restores the exact draft + tiles back into the
 * (now-single) composer, and a delivered send leaves the composer empty.
 */
internal fun PromptComposerViewModel.clearComposerForHandoff() {
    savedStateHandle[PromptComposerViewModel.KEY_DRAFT] = ""
    savedStateHandle[PromptComposerViewModel.KEY_DRAFT_OWNER] = null
    composerTarget?.let { target ->
        clearComposerDraft(target)
        composerDraftStore.clearAttachments(target)
        // Issue #1569 (U1): the retained-on-failure bytes have been handed off to
        // the queue row's own sidecars on Send — drop the now-orphaned draft-scoped
        // durable bytes so they don't linger.
        clearDraftAttachmentSidecars(target)
    }
    _uiState.update { current ->
        current.copy(
            draft = "",
            attachments = emptyList(),
            attachmentUpload = AttachmentUploadState.Idle,
        )
    }
    // Issue #1540 (L9): synthetic process-death seam (#780 model). Fires the
    // INSTANT the durable draft has been cleared. A crash right here is the
    // exact LOST window: the durable draft is gone, and — with the WRITE-AHEAD
    // fix — the durable queue row is ALREADY committed (this runs after the
    // commit in every durable path), so the test snapshots the durable stores
    // and asserts the prompt survived in the queue row. On the pre-fix base
    // this ran BEFORE the commit, so the snapshot showed the prompt gone from
    // both stores (LOST). Production never sets this (null → no-op).
    onDurableDraftClearedForHandoffTest?.invoke()
}

internal fun PromptComposerViewModel.hasLocalAttachmentsForSidecars(attachments: List<StagedAttachment>): Boolean =
    outboundQueueStore !== DisabledOutboundQueueStore &&
        outboundAttachmentSidecarStore != null &&
        outboundAttachmentUploader != null &&
        attachments.any { it.previewUri != null }

internal suspend fun PromptComposerViewModel.enqueueAndDispatchSidecarBackedSend(
    cleanDraft: String,
    attachments: List<StagedAttachment>,
    withEnter: Boolean,
    sendTarget: SendTargetSnapshot,
) {
    // Issue #929: this whole dispatch runs while `sendInFlight = true` (set by
    // [emitSendRequest] before launching us). EVERY exit that does not deliver
    // must clear the in-flight gates, or the pipeline wedges for the watchdog
    // window. The early config-missing returns below are non-delivering exits.
    val sessionKey = sendTarget.sessionKey.takeIf { it.isNotBlank() }
    val sidecarStore = outboundAttachmentSidecarStore ?: run {
        clearStrandedSendInFlight()
        return
    }
    if (sessionKey == null) {
        clearStrandedSendInFlight()
        return
    }
    val localAttachments = attachments.mapIndexedNotNull { index, attachment ->
        attachment.previewUri?.let { index to it }
    }
    val itemId = UUID.randomUUID().toString()
    val sidecars = sidecarStore.stage(
        outboundItemId = itemId,
        uris = localAttachments.map { it.second },
        attachmentIndices = localAttachments.map { it.first },
    )
    if (sidecars.size != localAttachments.size) {
        clearStrandedSendInFlight(
            error = "Attachment upload failed: could not preserve selected file bytes. Your draft was kept.",
        )
        return
    }
    val item = OutboundItem(
        id = itemId,
        sessionKey = sessionKey,
        cleanText = cleanDraft,
        attachments = attachments.toSidecarAwareDurableRefs(sidecars),
        withEnter = withEnter,
        state = OutboundState.Queued,
        createdAtMs = clock(),
        paneId = sendTarget.paneId,
        route = sendTarget.route,
        agentKind = sendTarget.agentKind,
        // Issue #961: coalesce a re-Send of the SAME logical prompt onto the
        // existing un-delivered row instead of minting a duplicate.
        sendKey = computeSendKey(cleanDraft, attachments, withEnter, sendTarget),
    )
    val queuedItem = outboundQueueStore.enqueueExisting(item)
    // Issue #961: if this coalesced onto an existing un-delivered row, the
    // freshly-staged sidecars under our throwaway `itemId` are orphaned —
    // the existing row keeps its own staged bytes. Drop them and dispatch
    // the row the queue actually kept, not our discarded id.
    if (queuedItem.id != itemId) {
        outboundAttachmentSidecarStore?.removeOutboundItem(itemId)
    }
    // Issue #1540 (L9): the durable row is committed (with its attachment
    // refs AND the staged sidecar bytes above) — NOW it is safe to clear the
    // editable draft + durable draft. This is the widest LOST window: the
    // attachment path stages bytes BEFORE the row commit, so clearing the
    // draft up front (the old order) meant a death between stage and commit
    // lost the prompt AND orphaned the sidecar bytes with no owning row. The
    // clear runs on Main ([clearComposerForHandoff] writes the SavedStateHandle
    // draft slots, which require the Main thread).
    withContext(Dispatchers.Main.immediate) { clearComposerForHandoff() }
    inFlightSendRequest = SendRequest(
        text = appendAttachmentPaths(cleanDraft, attachments.map { it.remotePath }),
        withEnter = withEnter,
        cleanDraft = cleanDraft,
        attachments = attachments,
        sendTarget = sendTarget,
        outboundQueueItemId = queuedItem.id,
    )
    refreshOutboundQueueItemsFor(sessionKey)
    dispatchPreparedOutboundItem(queuedItem.id)
}

internal fun PromptComposerViewModel.enqueueOutboundSend(
    cleanDraft: String,
    attachments: List<StagedAttachment>,
    withEnter: Boolean,
    sendTarget: SendTargetSnapshot,
): OutboundItem? {
    if (outboundQueueStore === DisabledOutboundQueueStore) return null
    val sessionKey = sendTarget.sessionKey.takeIf { it.isNotBlank() } ?: return null
    val item = outboundQueueStore.enqueue(
        sessionKey = sessionKey,
        cleanText = cleanDraft,
        attachments = attachments.toDurableRefs(),
        withEnter = withEnter,
        paneId = sendTarget.paneId,
        route = sendTarget.route,
        agentKind = sendTarget.agentKind,
        // Issue #961: coalesce a re-Send of the SAME logical prompt onto the
        // existing un-delivered row instead of minting a duplicate.
        sendKey = computeSendKey(cleanDraft, attachments, withEnter, sendTarget),
    )
    val activeItem = outboundQueueStore.markInFlight(item.id) ?: item
    refreshOutboundQueueItemsFor(sessionKey)
    return activeItem
}
