package com.pocketshell.app.composer

import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.app.diagnostics.DiagnosticPrivacy

/**
 * Issue #1682 (Track A + Track C): the composer outbound queue's diagnostic
 * surface — the missing-logging half of the "the queue clogs because it thinks
 * the connection is gone" diagnosis.
 *
 * Before this, the entire drain-gate decision path recorded NOTHING (the queue
 * store logged zero; the drain gate emitted zero), so the clog was invisible in
 * a real device trace. Track C found the clog is caused by the drain being
 * hard-gated on the `ConnectionStatus` enum (`TmuxSessionScreen.kt:425`) — so the
 * SMOKING-GUN signal is **enum-vs-transport disagreement**: the enum says
 * not-Connected while the wire is actually alive. [windowFlip] captures exactly
 * that pairing (the drain-window `sessionLive` boolean AND the `ConnectionStatus`
 * that drove it) so, cross-referenced with the `connection`-category transport
 * events on the SAME correlated timeline, the disagreement becomes readable.
 *
 * ## Category — `queue`, mirrored to the host (like `connection`, unlike `action`)
 *
 * These events use the [CATEGORY] `queue`, which [com.pocketshell.app.diagnostics.MirroredDiagnostics]
 * mirrors to the host connection log (`~/.pocketshell/connection-log.jsonl`) as a
 * WHOLE category — so a real-world clog reaches the maintainer's box alongside the
 * connection lifecycle it disagreed with. It is deliberately NOT the `action`
 * category (67 device-only user-behaviour events that would burn mobile data for
 * zero connection diagnosis).
 *
 * ## Redaction — ids and sizes only, never raw content
 *
 * Consistent with the existing log (`DiagnosticRedactor`): NO raw message text is
 * ever recorded. A session key is a directory path by construction
 * ([com.pocketshell.app.tmux.SessionNameDerivation]) so it is passed through
 * [DiagnosticPrivacy.stableFingerprint] here — the SAME fingerprint the
 * `connection`-category events use, so the queue timeline correlates to the
 * connection timeline by `sessionFingerprint`. The message payload is reduced to
 * its `itemId` (a random UUID, not content-derived) and `textLength` (a size).
 *
 * Diagnostics-only: this object records the queue's behaviour, it never changes
 * it (issue #1682 is the VISIBILITY half; the enum-decoupling FIX is separate).
 */
internal object ComposerQueueDiagnostics {

    /** The mirrored queue-diagnostic category (see [com.pocketshell.app.diagnostics.MirroredDiagnostics]). */
    const val CATEGORY: String = "queue"

    /**
     * A message durably committed `Queued` (write-ahead), pre-drain. Ids/sizes
     * only — NEVER the raw prompt text. A `#961` coalesced re-Send onto an
     * existing un-delivered row surfaces here as a REPEATED [OutboundItem.id]
     * (the kept row's id), so coalescing is readable from the timeline without a
     * separate flag.
     */
    fun enqueue(item: OutboundItem) {
        record(
            "enqueue",
            "itemId" to item.id,
            "sessionFingerprint" to fingerprint(item.sessionKey),
            "textLength" to item.cleanText.length,
            "attachmentCount" to item.attachments.size,
            "route" to item.route.name,
            "agentKind" to item.agentKind,
            "attemptCount" to item.attemptCount,
        )
    }

    /**
     * The drain window opening/closing, WITH [sessionLive] AND the
     * [connectionStatusLabel] that drove it — the enum-vs-transport disagreement
     * capture. Recorded only on a genuine flip (the controller dedups), so it
     * marks the exact edge at which queued rows start/stop being attempted.
     */
    fun windowFlip(
        sessionLive: Boolean,
        connectionStatusLabel: String,
        targetSessionKey: String,
    ) {
        record(
            "window_flip",
            "sessionLive" to sessionLive,
            "connectionStatus" to connectionStatusLabel,
            "sessionFingerprint" to fingerprint(targetSessionKey),
        )
    }

    /**
     * One drain tick's outcome: `dispatched` (a row went to the wire), `not_live`
     * (the gate was shut — enum not-Connected — so nothing was attempted, the
     * clog's core symptom), `all_suppressed` (every eligible row is within its
     * re-dispatch backoff or budget-parked), `in_flight` (a send already owns the
     * wire), `no_target` / `handoff` (transient self-gates).
     */
    fun drainAttempt(
        outcome: String,
        sessionKey: String?,
        dispatchedId: String? = null,
        queueDepth: Int = 0,
        suppressedCount: Int = 0,
        parkedCount: Int = 0,
        sendInFlight: Boolean = false,
    ) {
        record(
            "drain_attempt",
            "outcome" to outcome,
            "sessionFingerprint" to sessionKey?.let { fingerprint(it) },
            "dispatchedId" to dispatchedId,
            "queueDepth" to queueDepth,
            "suppressedCount" to suppressedCount,
            "parkedCount" to parkedCount,
            "sendInFlight" to sendInFlight,
        )
    }

    /**
     * A durable row state transition with its [reason] — especially the
     * park-at-[OUTBOUND_MAX_AUTO_ATTEMPTS] (`reason=budget_exhausted`) and whether
     * a connected-edge recovery (`reason=stale_inflight_requeue` / `resend_all` /
     * `manual_retry`) un-parks it. [attemptCount] is the row's post-transition
     * budget so the trace shows a row climbing toward the park.
     */
    fun rowState(
        itemId: String,
        sessionKey: String,
        from: String?,
        to: String,
        reason: String,
        attemptCount: Int,
    ) {
        record(
            "row_state",
            "itemId" to itemId,
            "sessionFingerprint" to fingerprint(sessionKey),
            "fromState" to from,
            "toState" to to,
            "reason" to reason,
            "attemptCount" to attemptCount,
        )
    }

    /**
     * Record one auto-flush drain cycle from the VM in a SINGLE call (keeps the
     * ratcheted VM tiny): the per-row PARK (→`Failed`, `reason=budget_exhausted`)
     * for each budget-exhausted head, then the tick outcome — `dispatched` (a row
     * went to the wire) or `all_suppressed` (every eligible row is within its
     * re-dispatch backoff or is budget-parked). A no-op when the target has no
     * undelivered row, so an idle poll tick records nothing.
     */
    fun recordDrainCycle(
        sessionKey: String,
        items: List<OutboundItem>,
        plan: ComposerAutoFlushPlan,
        suppressedCount: Int,
        dispatched: Boolean,
    ) {
        val queueDepth = items.count { it.sessionKey == sessionKey && it.isComposerQueueUndelivered() }
        if (queueDepth == 0) return
        val byId = items.associateBy { it.id }
        plan.parkIds.forEach { id ->
            byId[id]?.let { item ->
                item.recordQueueRowState(from = item.state.name, to = "Failed", reason = "budget_exhausted")
            }
        }
        drainAttempt(
            outcome = if (dispatched) "dispatched" else "all_suppressed",
            sessionKey = sessionKey,
            dispatchedId = plan.nextId.takeIf { dispatched },
            queueDepth = queueDepth,
            suppressedCount = suppressedCount,
            parkedCount = plan.parkIds.size,
        )
    }

    /** The wedge discriminator's verdict at a recovery decision (issue #1621 / #1602). */
    fun wedgeVerdict(wedged: Boolean, owningInFlightCount: Int, sendInFlight: Boolean) {
        record(
            "wedge_verdict",
            "wedged" to wedged,
            "owningInFlightCount" to owningInFlightCount,
            "sendInFlight" to sendInFlight,
        )
    }

    /** The overall-send watchdog fired — a delivery wedged past its budget (issue #891). */
    fun watchdogTimeout(attachmentCount: Int, hadInFlightRequest: Boolean) {
        record(
            "watchdog_timeout",
            "attachmentCount" to attachmentCount,
            "hadInFlightRequest" to hadInFlightRequest,
        )
    }

    private fun fingerprint(sessionKey: String): String =
        DiagnosticPrivacy.stableFingerprint(sessionKey)

    private fun record(event: String, vararg fields: Pair<String, Any?>) {
        DiagnosticEvents.record(CATEGORY, event, *fields)
    }
}

/**
 * Issue #1682: thin call-site shim so the byte-ratcheted `PromptComposerViewModel`
 * (a D28 god-object) grows as little as possible — each transition site is one
 * short call. Records THIS row's post-transition state + budget.
 */
internal fun OutboundItem.recordQueueRowState(from: String?, to: String, reason: String) {
    ComposerQueueDiagnostics.rowState(
        itemId = id,
        sessionKey = sessionKey,
        from = from,
        to = to,
        reason = reason,
        attemptCount = attemptCount,
    )
}
