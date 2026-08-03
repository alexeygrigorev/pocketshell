package com.pocketshell.app.composer

import kotlinx.coroutines.CancellationException

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
            if (!handedOff) outboundDrainOwnership.release(lease)
        }
    }
    return true
}
