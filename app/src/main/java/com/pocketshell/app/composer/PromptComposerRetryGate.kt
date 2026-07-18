package com.pocketshell.app.composer

/**
 * Issue #1621: the manual-Retry eligibility gate of [PromptComposerViewModel],
 * split out of the god-object VM (D28 / file-size hygiene ratchet) into cohesive
 * same-module `internal` extensions — the sibling of `PromptComposerOutboundSend.kt`
 * and `PromptComposerWatchdogEffects.kt`.
 *
 * This file exists because PR-4 (send-while-sending pipelining) invalidated the
 * assumption the old recovery was built on. The locked invariant it protects:
 * **only the drain assigns `inFlightSendRequest`, arms/disarms the delivery
 * watchdog, or emits `_sendRequests`.** A user tap on the queue banner is not the
 * drain, so it may re-arm a row for the drain — never re-own delivery.
 */

/**
 * Issue #1621: is the ACTIVE delivery WEDGED (the #1602 clog) rather than a
 * healthy send that is simply still on the wire?
 *
 * This is the discriminator PR-4 forces us to draw. Before send-while-sending
 * pipelining, the composer's Send was DISABLED while `sendInFlight`, so
 * "`sendInFlight` AND a retryable row exists" could only mean a CLOG — and
 * breaking the strand was the correct #1602 recovery. PR-4 makes
 * `[A InFlight, B Queued]` a NORMAL, HEALTHY state, so bare `sendInFlight` no
 * longer implies a wedge and that recovery must be narrowed or it destroys the
 * live delivery it used to rescue.
 *
 * "Wedged" is defined against the DURABLE queue — the single source of truth
 * since #971 — using the SAME staleness rule the orphan sweep already owns
 * ([PromptComposerViewModel.requeueStaleOutboundInFlight] /
 * [PromptComposerViewModel.OUTBOUND_IN_FLIGHT_STALE_MS], #900/#1542):
 *
 *  - a dispatch still being set up (`outboundSidecarDispatchInFlight`) is OWNED
 *    and about to claim its row — never a wedge;
 *  - the gate held with NO `InFlight`/`Uploading` row behind it is a pure STRAND
 *    (#929/#1602: a non-delivering exit, a stale sweep that re-armed the row,
 *    process-death restore). Nothing will ever resolve it, so a manual Retry is
 *    the recovery;
 *  - an owning row whose last attempt is older than the stale window has
 *    genuinely wedged (the send watchdog window elapsed with no ack).
 *
 * Anything else is a live delivery that still owns its watchdog and its
 * `_sendRequests` emission, and MUST NOT be torn down by a non-owner.
 */
internal fun PromptComposerViewModel.activeSendIsWedged(nowMs: Long = clock()): Boolean {
    if (!_uiState.value.sendInFlight) return false
    if (outboundSidecarDispatchInFlight) return false
    val target = composerTarget?.takeIf { it.isNotBlank() }
    val owning = target
        ?.let { outboundQueueStore.itemsFor(it) }
        ?.filter { it.state == OutboundState.InFlight || it.state == OutboundState.Uploading }
        .orEmpty()
    if (owning.isEmpty()) return true
    return owning.all {
        nowMs - (it.lastAttemptAtMs ?: it.createdAtMs) >=
            PromptComposerViewModel.OUTBOUND_IN_FLIGHT_STALE_MS
    }
}

/**
 * Issue #1621: the body of [PromptComposerViewModel.retryOutboundItem].
 *
 * A Retry is "make THIS row deliverable again" — never "tear down whatever else
 * is on the wire". The queue banner renders an enabled Retry for the oldest
 * retryable row, so in PR-4's normal `[A InFlight, B Queued]` state the tap
 * targets the TAIL while a healthy HEAD owns delivery. Strand-clearing there
 * disarmed A's watchdog, deferred A back to `Queued` and let B overtake it on the
 * wire — FIFO broken, and A re-queued while its attempt may still land (the #1526
 * duplicate class). Only the drain may assign delivery ownership, so a non-owner
 * tap re-arms the row and lets the existing FIFO drain claim it after A resolves.
 */
internal fun PromptComposerViewModel.retryOutboundItemThroughGate(id: String) {
    val inFlight = _uiState.value.sendInFlight
    val wedged = inFlight && activeSendIsWedged()
    if (inFlight) {
        // Issue #1682: record the wedge discriminator's verdict at this recovery
        // decision (a wedged in-flight send is the #1602 clog the strand-clear rescues).
        val owningInFlight = composerTarget?.takeIf { it.isNotBlank() }
            ?.let { outboundQueueStore.itemsFor(it) }
            ?.count { it.state == OutboundState.InFlight || it.state == OutboundState.Uploading }
            ?: 0
        ComposerQueueDiagnostics.wedgeVerdict(
            wedged = wedged,
            owningInFlightCount = owningInFlight,
            sendInFlight = true,
        )
    }
    if (inFlight && !wedged) {
        rearmOutboundItemForDrain(id)
        return
    }
    // Issue #1602: an explicit Retry must re-drive THIS row. A genuinely CLOGGED
    // pipeline (a WEDGED in-flight send holding `sendInFlight` — see
    // [activeSendIsWedged]) makes a plain dispatch a SILENT NO-OP, so break the
    // strand first — the watchdog recovery that DEFERS the wedged row
    // (exactly-once via #1526/#1541). NOT the sidecar latch (#928).
    if (_uiState.value.sendInFlight) {
        clearStrandedSendInFlight()
    }
    dispatchOutboundItem(id)
}

/**
 * Issue #1621: the non-owner half of [retryOutboundItemThroughGate]. Re-arm a
 * retryable row (`Queued`/`Failed` → `Queued`, error cleared, bounded auto-retry
 * budget re-granted exactly as [PromptComposerViewModel.resendAllQueued] does) so
 * the FIFO drain claims it once the ACTIVE delivery resolves.
 *
 * This is what makes a banner Retry on a PARKED (`Failed`, #1602
 * auto-retry-exhausted) tail still work while a healthy head owns the wire: the
 * drain SKIPS an exhausted row, so declining to act at all would leave it
 * permanently un-sendable. Never touches the in-flight row (it is not
 * [isComposerQueueRetryable]), so delivery ownership is untouched.
 */
private fun PromptComposerViewModel.rearmOutboundItemForDrain(id: String) {
    val item = outboundQueueStore.item(id) ?: return
    if (!item.isComposerQueueRetryable()) return
    outboundQueueStore.requeueForRetry(id, resetAttempts = true)
    // Issue #1682: a user's manual Retry un-parks a row (esp. a budget-parked
    // Failed one) — the trace shows whether a stuck row recovered by hand vs the
    // connected edge (which does NOT auto-un-park it, the Track C clog signal).
    item.recordQueueRowState(item.state.name, "Queued", "manual_retry")
    refreshOutboundQueueItemsFor(item.sessionKey)
}
