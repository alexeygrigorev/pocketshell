package com.pocketshell.app.composer

import androidx.lifecycle.viewModelScope
import com.pocketshell.app.composer.PromptComposerViewModel.AttachmentUploadState
import com.pocketshell.app.composer.PromptComposerViewModel.SendRequest
import com.pocketshell.app.composer.PromptComposerViewModel.SendTargetSnapshot
import com.pocketshell.app.composer.PromptComposerViewModel.StagedAttachment
import com.pocketshell.app.diagnostics.DiagnosticEvents
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class ComposerHandoffSnapshot(
    val target: String,
    val revision: Long,
)

internal class ComposerRevisionTracker {
    private val revisions: MutableMap<String, Long> = mutableMapOf()

    fun revision(target: String): Long = revisions[target] ?: 0L

    fun record(target: String?) {
        val key = target?.takeIf { it.isNotBlank() } ?: return
        revisions[key] = revision(key) + 1L
    }
}

internal fun PromptComposerViewModel.composerRevision(target: String): Long =
    composerRevisionTracker.revision(target)

internal fun PromptComposerViewModel.recordComposerMutation(target: String? = composerTarget) {
    composerRevisionTracker.record(target)
    composerInteractionEpoch++
}

internal fun PromptComposerViewModel.onComposerOpened() {
    composerInteractionEpoch++
}

internal fun PromptComposerViewModel.finishOutboundHandoff(
    owner: Job,
    error: String? = null,
): Boolean {
    if (outboundHandoffJob !== owner) return false
    watchdogs.disarmHandoff()
    outboundHandoffJob = null
    outboundHandoffAttachmentJob = null
    outboundHandoffInProgress = false
    _uiState.update {
        it.copy(
            outboundHandoffInProgress = false,
            error = error ?: it.error,
        )
    }
    return true
}

/** Wait for attachment staging without claiming the wire-delivery latch. */
internal fun PromptComposerViewModel.dispatchSendAfterUpload(
    withEnter: Boolean,
    sendTarget: SendTargetSnapshot,
) {
    DiagnosticEvents.record("action", "composer_send_wait_upload")
    outboundHandoffInProgress = true
    _uiState.update { it.copy(outboundHandoffInProgress = true, error = null) }
    val attachment = attachmentJob ?: run {
        outboundHandoffInProgress = false
        _uiState.update { it.copy(outboundHandoffInProgress = false) }
        return emitSendRequest(withEnter, sendTarget)
    }
    outboundHandoffAttachmentJob = attachment
    lateinit var owner: Job
    owner = viewModelScope.launch(start = CoroutineStart.LAZY) {
        try {
            attachment.join()
            if (_uiState.value.attachments.isEmpty()) {
                // Never silently downgrade a failed attachment upload to text-only.
                DiagnosticEvents.record("action", "composer_send_wait_upload_no_attachment")
                finishOutboundHandoff(owner)
                return@launch
            }
            if (finishOutboundHandoff(owner)) emitSendRequest(withEnter, sendTarget)
        } catch (cancelled: CancellationException) {
            finishOutboundHandoff(owner)
            throw cancelled
        }
    }
    outboundHandoffJob = owner
    watchdogs.armHandoff()
    owner.start()
}

/**
 * Issue #971 / #1540: the outbound-send dispatch surface of
 * [PromptComposerViewModel], split out of the god-object VM (D28 / file-size
 * hygiene ratchet) into cohesive same-module `internal` extensions.
 *
 * This is the write-ahead handoff path: [emitSendRequest] serializes a durable
 * queue commit ([enqueueOutboundSend] / [enqueueSidecarBackedSend]) BEFORE the
 * editable draft is cleared ([clearComposerForHandoff]). The queue drain claims
 * wire delivery separately, allowing later prompts to enqueue while an older
 * row is active and closing the #1540 (L9) silent-LOST window where a process
 * death between draft-clear and row-commit lost the prompt from both stores.
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
    val durableTarget = sendTarget.sessionKey.takeIf { it.isNotBlank() }
        ?.takeIf { outboundQueueStore !== DisabledOutboundQueueStore }
    if (durableTarget != null) {
        // Issue #961/#1621: preserve the old singleton gate's exactly-once
        // boundary for an unchanged retry while that logical prompt owns the
        // wire. A different composition may pipeline behind it, but re-enqueueing
        // this one can race delivery/pruning and mint a second row.
        val activeRequest = inFlightSendRequest
        if (_uiState.value.sendInFlight && activeRequest != null &&
            computeSendKey(draft, attachments, withEnter, sendTarget) ==
            computeSendKey(
                activeRequest.cleanDraft,
                activeRequest.attachments,
                activeRequest.withEnter,
                activeRequest.sendTarget,
            )
        ) {
            val acknowledged = clearComposerForHandoff(
                ComposerHandoffSnapshot(durableTarget, composerRevision(durableTarget)),
            )
            if (acknowledged) {
                activeRequest.outboundQueueItemId?.let {
                    outboundAutoCloseEpochs[it] = composerInteractionEpoch
                }
            }
            return
        }
        val backgroundDelivery = backgroundDeliveredRequest
        if (backgroundDelivery != null &&
            computeSendKey(draft, attachments, withEnter, sendTarget) ==
            computeSendKey(
                backgroundDelivery.cleanDraft,
                backgroundDelivery.attachments,
                backgroundDelivery.withEnter,
                backgroundDelivery.sendTarget,
            )
        ) {
            clearComposerForHandoff(
                ComposerHandoffSnapshot(durableTarget, composerRevision(durableTarget)),
            )
            backgroundDeliveredRequest = null
            return
        }
        backgroundDeliveredRequest = null
        // Issue #1621: this latch covers only the write-ahead commit. A different
        // composition may be handed off while an older row owns wire delivery;
        // an unchanged double tap during this same commit remains a no-op.
        if (outboundHandoffInProgress) return
        outboundHandoffInProgress = true
        outboundHandoffAttachmentJob = null
        _uiState.update { it.copy(outboundHandoffInProgress = true, error = null) }
        val snapshot = ComposerHandoffSnapshot(
            target = durableTarget,
            revision = composerRevision(durableTarget),
        )
        lateinit var owner: Job
        owner = viewModelScope.launch(
            context = outboundQueueDispatcher,
            start = CoroutineStart.LAZY,
        ) {
            try {
                val queuedItem = if (hasLocalAttachmentsForSidecars(attachments)) {
                    enqueueSidecarBackedSend(
                        cleanDraft = draft,
                        attachments = attachments,
                        withEnter = withEnter,
                        sendTarget = sendTarget,
                    )
                } else {
                    enqueueOutboundSend(
                        cleanDraft = draft,
                        attachments = attachments,
                        withEnter = withEnter,
                        sendTarget = sendTarget,
                    )
                }
                withContext(Dispatchers.Main.immediate) {
                    if (outboundHandoffJob !== owner) return@withContext
                    if (clearComposerForHandoff(snapshot)) {
                        queuedItem?.let { outboundAutoCloseEpochs[it.id] = composerInteractionEpoch }
                    }
                    finishOutboundHandoff(owner)
                    refreshOutboundQueueItemsFor(durableTarget)
                    // The queue drain, never the handoff, claims delivery. If an
                    // older row is active this is intentionally a no-op; its
                    // completion refresh drives the next FIFO claim.
                    if (!_uiState.value.sendInFlight && composerTarget == durableTarget) {
                        retryNextOutboundItem()
                    }
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    finishOutboundHandoff(owner)
                }
                throw cancelled
            } catch (t: Throwable) {
                withContext(Dispatchers.Main.immediate) {
                    finishOutboundHandoff(
                        owner,
                        error = "Send failed: reconnect, then send again or discard the draft.",
                    )
                }
            }
        }
        outboundHandoffJob = owner
        watchdogs.armHandoff()
        owner.start()
        return
    }
    // Legacy no-store / blank-target path remains single-active because there
    // is no durable FIFO representation to own a second prompt.
    if (_uiState.value.sendInFlight) return
    if (outboundSidecarDispatchInFlight) {
        _uiState.update { it.copy(error = PromptComposerViewModel.SEND_BUSY_UPLOADING_MESSAGE) }
        return
    }
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
    // [enqueueSidecarBackedSend]), so a crash in the window always
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
    // Issue #1540 (L9): the no-durable-store fallback (DisabledOutboundQueueStore
    // or a blank sessionKey) has no queue row to write-ahead, so there is no
    // durable-loss window — the prompt only ever travels in the in-memory
    // [SendRequest]. Clear synchronously here, just before emitting, exactly
    // as the pre-#1540 code did.
    clearComposerForHandoff(
        ComposerHandoffSnapshot(
            target = composerTarget.orEmpty(),
            revision = composerTarget?.let(::composerRevision) ?: 0L,
        ),
    )
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
    if (outboundQueueItemId == null) legacyAutoCloseEpoch = composerInteractionEpoch
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
internal fun PromptComposerViewModel.clearComposerForHandoff(snapshot: ComposerHandoffSnapshot): Boolean {
    // Issue #1621: the IO commit may complete after the user has typed or
    // switched targets. Only the exact captured target revision is now owned by
    // the queue row; later input must never be cleared by this callback.
    if (composerRevision(snapshot.target) != snapshot.revision) return false
    val clearsLiveComposer = snapshot.target.isBlank() || composerTarget == snapshot.target
    if (clearsLiveComposer) {
        savedStateHandle[PromptComposerViewModel.KEY_DRAFT] = ""
        savedStateHandle[PromptComposerViewModel.KEY_DRAFT_OWNER] = null
    }
    snapshot.target.takeIf { it.isNotBlank() }?.let { target ->
        clearComposerDraft(target)
        composerDraftStore.clearAttachments(target)
        // Issue #1569 (U1): the retained-on-failure bytes have been handed off to
        // the queue row's own sidecars on Send — drop the now-orphaned draft-scoped
        // durable bytes so they don't linger.
        clearDraftAttachmentSidecars(target)
    }
    if (clearsLiveComposer) {
        _uiState.update { current ->
            current.copy(
                draft = "",
                attachments = emptyList(),
                attachmentUpload = AttachmentUploadState.Idle,
            )
        }
    }
    recordComposerMutation(snapshot.target)
    // Issue #1540 (L9): synthetic process-death seam (#780 model). Fires the
    // INSTANT the durable draft has been cleared. A crash right here is the
    // exact LOST window: the durable draft is gone, and — with the WRITE-AHEAD
    // fix — the durable queue row is ALREADY committed (this runs after the
    // commit in every durable path), so the test snapshots the durable stores
    // and asserts the prompt survived in the queue row. On the pre-fix base
    // this ran BEFORE the commit, so the snapshot showed the prompt gone from
    // both stores (LOST). Production never sets this (null → no-op).
    onDurableDraftClearedForHandoffTest?.invoke()
    return true
}

internal fun PromptComposerViewModel.hasLocalAttachmentsForSidecars(attachments: List<StagedAttachment>): Boolean =
    outboundQueueStore !== DisabledOutboundQueueStore &&
        outboundAttachmentSidecarStore != null &&
        outboundAttachmentUploader != null &&
        attachments.any { it.previewUri != null }

internal suspend fun PromptComposerViewModel.enqueueSidecarBackedSend(
    cleanDraft: String,
    attachments: List<StagedAttachment>,
    withEnter: Boolean,
    sendTarget: SendTargetSnapshot,
): OutboundItem? {
    // Issue #929/#1621: stage and durably enqueue the sidecars before delivery
    // owns `sendInFlight`. Every exit here must either commit one queue row or
    // leave the editable draft untouched for retry.
    val sessionKey = sendTarget.sessionKey.takeIf { it.isNotBlank() }
    val sidecarStore = outboundAttachmentSidecarStore ?: return null
    if (sessionKey == null) {
        return null
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
        error("Attachment upload failed: could not preserve selected file bytes")
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
    // Issue #1682: record the write-ahead commit on the mirrored `queue` timeline.
    ComposerQueueDiagnostics.enqueue(queuedItem)
    outboundHandoffCommitForTest?.invoke(queuedItem)
    // Enqueue only. The normal FIFO drain owns upload, claim, watchdog,
    // singleton request state, and wire emission.
    return queuedItem
}

internal suspend fun PromptComposerViewModel.enqueueOutboundSend(
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
    // Issue #1682: record the write-ahead commit on the mirrored `queue` timeline.
    ComposerQueueDiagnostics.enqueue(item)
    outboundHandoffCommitForTest?.invoke(item)
    return item
}
