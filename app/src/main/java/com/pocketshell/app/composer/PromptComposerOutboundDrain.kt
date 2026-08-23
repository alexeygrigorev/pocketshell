package com.pocketshell.app.composer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update

internal fun PromptComposerViewModel.approveStaleOutboundItem(id: String): OutboundItem? {
    val previousState = outboundQueueStore.item(id)?.state?.name
    val approved = outboundQueueStore.approveStaleForSend(id, nowMillis = clock()) ?: return null
    approved.recordQueueRowState(previousState, approved.state.name, "stale_send_now")
    refreshOutboundQueueItemsFor(approved.sessionKey)
    return approved
}

private data class PendingApprovedDrainAttempt(
    val relevantToTarget: Boolean,
    val dispatchedId: String?,
)

internal fun PromptComposerViewModel.retainApprovedOutboundItemForDrain(id: String) {
    outboundDrainApprovals.retain(id)
}

internal fun PromptComposerViewModel.clearApprovedOutboundItemForDrain(id: String?) {
    if (id != null) outboundDrainApprovals.remove(id)
}

/**
 * Try an explicit Send-now approval before ordinary FIFO planning. It still
 * enters [dispatchOutboundItem], so the physical ownership lease, exact queue
 * claim, consumer generation, sidecar leg, and terminal callback remain one
 * shared path. A relevant approval that is currently blocked must stop the
 * planner from selecting a different row; the owner-resolution wake retries it
 * later with the same durable id.
 */
private fun PromptComposerViewModel.tryDispatchApprovedOutboundItem(
    target: String,
    excludingIds: Set<String> = emptySet(),
): PendingApprovedDrainAttempt {
    outboundDrainApprovals.snapshot().forEach { id ->
        if (id in excludingIds) return@forEach
        val item = outboundQueueStore.item(id)
        when {
            item == null || item.state == OutboundState.Delivered -> {
                outboundDrainApprovals.remove(id)
            }
            item.sessionKey != target -> Unit
            !item.isComposerQueueRetryable() ->
                return PendingApprovedDrainAttempt(relevantToTarget = true, dispatchedId = null)
            else -> {
                val dispatched = dispatchOutboundItem(id)
                if (dispatched) outboundDrainApprovals.remove(id)
                return PendingApprovedDrainAttempt(
                    relevantToTarget = true,
                    dispatchedId = id.takeIf { dispatched },
                )
            }
        }
    }
    return PendingApprovedDrainAttempt(relevantToTarget = false, dispatchedId = null)
}

/**
 * Wake the ordinary drain after the current physical owner has resolved.
 *
 * A pre-handoff failure must not immediately select the same row again: doing
 * so turns a real failure into a tight retry loop. The row is excluded for
 * this wake only, so the normal FIFO planner can still advance to a healthy
 * tail. An explicitly approved stale row remains eligible when a different
 * physical owner releases the wire, which is the #1700 Send now contract.
 */
internal fun PromptComposerViewModel.wakeOutboundDrainAfterOwnerResolution(
    excludingId: String? = null,
    approvedOnly: Boolean = false,
) {
    if (outboundHandoffInProgress) return
    val excludingIds = excludingId?.let(::setOf).orEmpty()
    if (approvedOnly) {
        val target = composerTarget?.takeIf { it.isNotBlank() } ?: return
        val hasApprovedRetry = outboundDrainApprovals.snapshot().any { id ->
            id !in excludingIds && outboundQueueStore.item(id)?.let { item ->
                item.sessionKey == target && item.isComposerQueueRetryable()
            } == true
        }
        if (!hasApprovedRetry) return
    }
    retryNextOutboundItem(excludingIds = excludingIds)
}

/**
 * Issue #1700: the row-level HeldForReview action is an explicit approval of
 * THIS durable row, not a generic re-enqueue. Persist that approval first, then
 * hand the row back to the normal drain so it keeps the same claim, lease, and
 * delivery callback path as a fresh queued prompt.
 */
internal fun PromptComposerViewModel.sendHeldOutboundItemNow(id: String) {
    val approved = approveStaleOutboundItem(id) ?: return
    // Retain the exact approval BEFORE attempting dispatch. The active owner
    // can resolve on another coroutine as soon as the first gate rejects;
    // publishing it first prevents that terminal wake from racing this tap.
    retainApprovedOutboundItemForDrain(approved.id)
    if (!isSendTransportWritable()) {
        _uiState.update { current ->
            current.copy(error = "Waiting for connection — Retry when the session is online.")
        }
        return
    }

    // Explicit Send now may deliberately overtake a younger fresh tail. The
    // approval is already persisted on this exact id, so hand that id directly
    // to the ordinary drain rather than re-running the snapshot planner. The
    // planner is allowed to return no row while a healthy send/handoff owns the
    // gates, which would otherwise leave this newly-approved row queued with no
    // request to wake it. The ordinary drain still owns claim/emit/delivery and
    // rejects an unsafe concurrent owner.
    markOutboundRetrying(approved.id)
    val dispatched = dispatchOutboundItem(approved.id)
    if (!dispatched) {
        clearOutboundRetrying(approved.id)
        _uiState.update { current ->
            current.copy(
                error = when {
                    current.sendInFlight ->
                        "Waiting — another prompt is still sending. This prompt will follow it."
                    !isSendTransportWritable() ->
                        "Waiting for connection — Retry when the session is online."
                    else -> current.error
                },
            )
        }
    }
    if (dispatched) clearApprovedOutboundItemForDrain(approved.id)
}

internal fun PromptComposerViewModel.approveAllHeldOutboundItems(): List<String> {
    val target = composerTarget?.takeIf { it.isNotBlank() } ?: return emptyList()
    val approvedIds = outboundQueueStore.itemsFor(target)
        .filter { it.isComposerQueueHeldForReview() }
        .mapNotNull { approveStaleOutboundItem(it.id)?.id }
    return approvedIds
}

internal fun PromptComposerViewModel.resendAllQueuedOrApproveHeld(): List<String> {
    val approved = approveAllHeldOutboundItems()
    val rearmed = resendAllQueued()
    return approved + rearmed
}

/** Connected-test hold after durable claim but before the real screen consumer. */
internal object PromptComposerOutboundDrainTestSeams {
    @Volatile
    var beforeEmit: ((OutboundItem) -> Unit)? = null
}

/**
 * Issue #1602 / #1700: one auto-flush cycle. Parks an exhausted head, holds a
 * stale unapproved row so it cannot silently flush, then dispatches the oldest
 * still-eligible row. Classification is persisted before the claim so the
 * queue banner can show Needs review without a race at the age boundary.
 */
internal fun PromptComposerViewModel.retryNextOutboundItemThroughPlan(
    excludingIds: Set<String>,
): String? {
    if (uiState.value.sendInFlight) return null
    if (outboundHandoffInProgress) return null
    val target = composerTarget?.takeIf { it.isNotBlank() } ?: return null
    val held = outboundQueueStore.holdStaleUnapproved(target, clock())
    if (held.isNotEmpty()) refreshOutboundQueueItemsFor(target)
    val itemsSnapshot = outboundQueueItems.value
    if (itemsSnapshot.hasGenerationBoundRowsAwaitingPromotion(target)) return null
    val pendingAttempt = tryDispatchApprovedOutboundItem(target, excludingIds)
    if (pendingAttempt.relevantToTarget) return pendingAttempt.dispatchedId
    val plan = itemsSnapshot.planComposerAutoFlush(target, excludingIds, nowMillis = clock())
    if (plan.parkIds.isNotEmpty()) {
        plan.parkIds.forEach {
            outboundQueueStore.markFailed(it, lastError = OUTBOUND_AUTO_RETRY_EXHAUSTED_MESSAGE)
        }
        refreshOutboundQueueItemsFor(target)
    }
    val nextId = plan.nextId
    val dispatched = nextId != null && dispatchOutboundItem(nextId)
    ComposerQueueDiagnostics.recordDrainCycle(target, itemsSnapshot, plan, excludingIds.size, dispatched)
    return if (dispatched) nextId else null
}

/**
 * Acquires and transfers the single physical composer-drain lease.
 *
 * The ViewModel remains the state owner; this coordinator owns the dispatch
 * race: reject while another send/sidecar owns the pipe, acquire one exact
 * row-token, prepare sidecars, and release every pre-handoff failure. Once a
 * [PromptComposerViewModel.SendRequest] is emitted, its terminal callback owns
 * the lease instead.
 */
internal fun PromptComposerViewModel.dispatchOutboundItemThroughDrain(id: String): Boolean {
    fun reject(reason: String): Boolean {
        ComposerQueueDiagnostics.dispatchRejected(
            itemId = id,
            reason = reason,
            activeOwnerId = outboundDrainOwnership.activeRowId(),
            itemSessionKey = outboundQueueStore.item(id)?.sessionKey,
            targetSessionKey = composerTarget,
            sendInFlight = uiState.value.sendInFlight,
            sidecarInFlight = outboundSidecarDispatchInFlight,
        )
        return false
    }
    if (uiState.value.sendInFlight) return reject("send_in_flight")
    if (outboundHandoffInProgress) return reject("handoff_in_progress")
    if (outboundSidecarDispatchInFlight) return reject("sidecar_in_flight")
    if (!outboundSendConsumers.canDispatch()) return reject("no_send_consumer")
    val consumerGeneration = outboundSendConsumers.activeGenerationForDispatch()
    val lease = outboundDrainOwnership.tryAcquire(id) ?: return reject("ownership_held")
    if (uiState.value.sendInFlight || outboundSidecarDispatchInFlight) {
        outboundDrainOwnership.release(lease)
        return reject("gate_changed_after_acquire")
    }
    outboundSidecarDispatchInFlight = true
    launchOutboundDrain {
        var handedOff = false
        try {
            val hasSidecars = outboundAttachmentSidecarStore?.refsFor(id)?.isNotEmpty() == true
            handedOff = if (hasSidecars) {
                dispatchPreparedOutboundItem(id, lease, consumerGeneration)
            } else {
                claimAndEmitOutboundItem(id, lease, consumerGeneration)
            }
        } catch (cancelled: CancellationException) {
            clearStrandedSendInFlight()
            throw cancelled
        } catch (_: Throwable) {
            clearStrandedSendInFlight(
                error = "Send failed: reconnect, then send again or discard the draft.",
            )
        } finally {
            outboundSidecarDispatchInFlight = false
            if (!handedOff) {
                outboundDrainOwnership.release(lease)
                clearOutboundRetrying(id)
                wakeOutboundDrainAfterOwnerResolution(
                    excludingId = id,
                    approvedOnly = true,
                )
            }
        }
    }
    return true
}
