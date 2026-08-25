package com.pocketshell.app.composer

/**
 * Small, deterministic selection rules for the composer-facing outbound queue.
 * The ViewModel owns side effects; this file owns the row predicates shared by
 * retry, delete, and deferred-send recovery.
 */
/**
 * Issue #1602: the bounded auto-retry budget for one outbound row. The reconnect
 * auto-flush re-claims the oldest retryable row each cycle; a row that has
 * FAILED/wedged this many delivery attempts is a permanently-stuck head-of-line
 * entry and is PARKED (`Failed`, surfaced) so the drain skips it and the healthy
 * tail drains. A healthy send delivers + prunes within one or two attempts, so it
 * never reaches this bound; only a row that fails EVERY attempt does. A user's
 * per-row Retry bypasses the bound, and `resendAllQueued` resets it, so a parked
 * row is only auto-skipped, never permanently un-sendable (hard-cut D22).
 */
internal const val OUTBOUND_MAX_AUTO_ATTEMPTS: Int = 6

/**
 * Issue #1700: after this many milliseconds from [OutboundItem.createdAtMs], an
 * unapproved `Queued`/`Failed` row is held for explicit Send now / Delete.
 * Retries and [OutboundItem.lastAttemptAtMs] never rejuvenate the age.
 */
internal const val OUTBOUND_STALE_HOLD_MS: Long = 5L * 60_000L

/**
 * Issue #1700: existing tests stamp `createdAtMs = 1, 2, 10…` as a FIFO order
 * key, not a wall-clock enqueue epoch. Production [OutboundQueueStore.enqueue]
 * always stamps `System.currentTimeMillis()` / the VM clock (ms since 1970),
 * which is far above this floor (2001-09-09). Age-hold applies only to real
 * epochs so those fixtures keep their meaning.
 */
internal const val OUTBOUND_STALE_EPOCH_FLOOR_MS: Long = 1_000_000_000_000L

internal fun outboundIntentAgeMs(createdAtMs: Long, nowMillis: Long): Long =
    (nowMillis - createdAtMs).coerceAtLeast(0L)

internal fun OutboundItem.isComposerQueueHeldForReview(): Boolean =
    state == OutboundState.HeldForReview

/**
 * Unapproved intent whose enqueue epoch is at/over [OUTBOUND_STALE_HOLD_MS].
 * Active `Uploading`/`InFlight` attempts are never classified here — hold
 * applies before the next new wire step, not by interrupting a healthy claim.
 */
internal fun OutboundItem.isStaleUnapproved(nowMillis: Long): Boolean {
    if (staleApprovedAtMs != null) return false
    if (createdAtMs < OUTBOUND_STALE_EPOCH_FLOOR_MS) return false
    if (state != OutboundState.Queued && state != OutboundState.Failed) return false
    return outboundIntentAgeMs(createdAtMs, nowMillis) >= OUTBOUND_STALE_HOLD_MS
}

/** Issue #1602: surfaced "Failed — <this>" label a parked (budget-exhausted) row wears. */
internal const val OUTBOUND_AUTO_RETRY_EXHAUSTED_MESSAGE: String =
    "Couldn't send after several tries. Tap Retry."

/**
 * Issue #1635-A (design D4, #1331): the delivery window a send attempt was made
 * in — is the session LIVE, and WHICH live window is it.
 *
 * [epoch] increments on EVERY `(sessionLive, target)` flip
 * (`OutboundQueueAutoFlushController.onConnectionWindowChanged`), so a claim and
 * its failure resolution can be compared: same epoch + live at both ends means the
 * window never closed under the attempt. Liveness ALONE is not sufficient to
 * decide that — under the #1610 reconnect storm the link tears down and heals
 * several times inside one ~50s send timeout, so both the claim and the
 * resolution observe `live = true` while the window the attempt needed was
 * destroyed in between. The epoch is what makes that flip visible.
 */
internal data class OutboundDeliveryWindow(
    val live: Boolean,
    val epoch: Long,
)

/**
 * Issue #1635-A (design D4): the default window for any caller that has not wired
 * a real one (a test VM, a composer with no session screen attached). A STABLE
 * LIVE window, so an un-wired path charges attempts exactly as it did before —
 * the #1602 park is preserved by default and only a PROVEN window flip relaxes it.
 */
internal val OUTBOUND_ASSUMED_STABLE_WINDOW: OutboundDeliveryWindow =
    OutboundDeliveryWindow(live = true, epoch = 0L)

/**
 * Issue #1635-A (design D4, maintainer-delegated): is a FAILED delivery attempt
 * chargeable against the bounded auto-retry budget ([OUTBOUND_MAX_AUTO_ATTEMPTS])?
 *
 * **Only when the delivery window stayed live, end to end.** The #1602 budget was
 * designed for a row that wedges on a HEALTHY link ("only a row that fails EVERY
 * attempt does" — see [OUTBOUND_MAX_AUTO_ATTEMPTS]); under the #1610 reconnect
 * storm EVERY row fails EVERY attempt for reasons that are not the row's fault, so
 * the whole queue burned its budget in ~30s and PARKED — and no genuine reconnect
 * ever gave it back, so nothing auto-sent after the link recovered (the
 * maintainer's "it got delivered long after, when I did something"). D4: a failure
 * that happened because the connection was down burns ZERO attempts.
 *
 * Chargeable requires ALL of:
 *  - the claim window is KNOWN ([claimWindow] non-null). An unknown claim charges —
 *    fail-safe toward the #1602 park rather than toward an unbounded retry loop.
 *  - the window was LIVE when the attempt was claimed, and STILL LIVE when it
 *    failed (a failure with the link visibly down is never the row's fault),
 *  - the epoch did NOT change (no teardown/heal happened UNDER the attempt).
 *
 * The G6 negative this must preserve: a row that genuinely fails on its own merits
 * against a proven-good link — bad payload, server rejection, a wedged head — sees
 * `claimWindow == resolveWindow` on every attempt, charges every time, and still
 * parks at the bound. Relaxing the budget too broadly would replace #1602's stuck
 * head with a row that retries FOREVER: a new infinite loop for the old one.
 */
internal fun outboundAttemptChargeable(
    claimWindow: OutboundDeliveryWindow?,
    resolveWindow: OutboundDeliveryWindow,
): Boolean {
    if (claimWindow == null) return true
    return claimWindow.live && resolveWindow.live && claimWindow.epoch == resolveWindow.epoch
}

/**
 * Issue #1635-A (design D4): the whole send-leg budget policy in one place — which
 * delivery window each in-flight row was CLAIMED in, and therefore whether its
 * FAILURE is chargeable ([outboundAttemptChargeable]).
 *
 * The budget is charged at claim, but only the failure knows whether the attempt was
 * the row's fault, so the claim window must be remembered until it resolves. The
 * ViewModel owns the side effects; this owns the decision.
 */
internal class OutboundAttemptBudgetTracker {
    /**
     * The CURRENT delivery window. BOUND by `OutboundQueueAutoFlushController.boundTo`
     * (the controller owns the `(sessionLive, target)` flip and therefore the epoch),
     * which READS this tracker off the owning `PromptComposerViewModel` rather than
     * accepting one — the controller's constructor is private, so no call site can
     * build a controller without this wiring, nor point it at a tracker the VM does not
     * consume. Do NOT re-introduce a separate "assign the window" statement at a call
     * site (deletable in silence — that is how the #1635 fix was unwired with the whole
     * suite green), and do NOT re-expose a `budget` parameter on the controller (a
     * required argument enforces only that AN argument is passed: a fresh
     * `OutboundAttemptBudgetTracker()` satisfied it, compiled, kept 3,968 tests green,
     * and fully restored the reported bug).
     *
     * The default is a STABLE LIVE window, so a composer with no controller attached
     * (a narrow test VM) charges attempts exactly as it did before the fix — the #1602
     * park is the fail-safe default, and only a PROVEN window flip relaxes it.
     */
    var window: () -> OutboundDeliveryWindow = { OUTBOUND_ASSUMED_STABLE_WINDOW }

    private val claims = mutableMapOf<String, OutboundDeliveryWindow>()

    /** Stamp the window a delivery attempt for [rowId] was just claimed/charged in. */
    fun onClaim(rowId: String) {
        claims[rowId] = window()
    }

    /**
     * The `attemptDelta` a FAILED resolution of [rowId] passes to
     * [OutboundQueueStore.requeueForRetry] — `0` when the attempt was the row's own
     * fault (charge it), `-1` to REFUND the claim's bump when the delivery window was
     * closed or flipped under it. The claim stamp is consumed either way: the attempt
     * has resolved, so the row no longer owns a window.
     */
    fun failureAttemptDelta(rowId: String): Int =
        if (outboundAttemptChargeable(claims.remove(rowId), window())) 0 else -1

    /** Bound the map by the live queue (the #1635 sibling of the auto-close-epoch GC). */
    fun retainRows(stillQueued: (String) -> Boolean) {
        claims.keys.retainAll(stillQueued)
    }
}

/**
 * Issue #1635-A: re-arm the durable row behind a DEFERRED (dropped/wedged) send back
 * to `Queued`, charging or refunding its attempt per [OutboundAttemptBudgetTracker].
 *
 * Resolves the row by [rowId] when the request carries one, else falls back to
 * matching the request against the target session's undelivered rows
 * ([deferredRetryCandidateFor]) — the #971/#987 Option-A path. Returns null when
 * there is no durable row to keep queued, which is the caller's signal to restore the
 * prompt to the composer rather than lose it.
 */
internal fun OutboundQueueStore.requeueDeferredSend(
    rowId: String?,
    sessionKey: String,
    request: PromptComposerViewModel.SendRequest,
    budget: OutboundAttemptBudgetTracker,
    // Issue #1686 (the wire is the oracle): when the transport was proven not-writable
    // at the failure point the caller passes `true` here to FULLY re-grant the budget —
    // a stronger, resolution-time signal than the epoch alone. It rides on top of the
    // epoch-aware refund below: the claim stamp is still consumed (the attempt resolved)
    // so the two policies never leak a stale claim, and `resetAttempts` wins over the
    // `-1` refund when it is set (see [OutboundItem.adjustedAttemptCount]).
    resetAttemptBudget: Boolean = false,
): OutboundItem? {
    // Try the request's own row first; a null result (row already delivered/pruned)
    // FALLS THROUGH to the candidate match, exactly as the pre-#1635 `?:` chain did.
    rowId?.let { id ->
        requeueForRetry(
            id,
            resetAttempts = resetAttemptBudget,
            attemptDelta = budget.failureAttemptDelta(id),
        )?.let { return it }
    }
    val candidate = sessionKey.takeIf { it.isNotBlank() }
        ?.let { itemsFor(it).deferredRetryCandidateFor(request) }
        ?: return null
    return requeueForRetry(
        candidate.id,
        resetAttempts = resetAttemptBudget,
        attemptDelta = budget.failureAttemptDelta(candidate.id),
    )
}

/**
 * Issue #1635-B: the `attemptDelta` an UPLOAD failure passes to
 * [OutboundQueueStore.requeueForRetry]. An upload failure ALWAYS charges — this is
 * deliberately the OPPOSITE of the send leg's window-closed refund
 * ([outboundAttemptChargeable]), and the asymmetry is the point:
 *
 *  - `markUploading` does not claim, so before this an upload-failing row was
 *    entirely IMMUNE to the [OUTBOUND_MAX_AUTO_ATTEMPTS] bound. The 3s poll
 *    re-picked it as the oldest retryable row forever, it never parked, the younger
 *    rows behind it never got a delivery attempt (head-of-line starvation), and each
 *    retry re-transferred the file FROM BYTE 0 — there is no resume (#1604). A file
 *    whose transfer time exceeds the live-window duration never completes, so under
 *    a storm this burns storm-duration x bandwidth of the maintainer's mobile data.
 *  - A failed SEND is refunded when the window closed under it because it cost
 *    almost nothing — a few execs. A failed UPLOAD is NEVER free: it already SPENT
 *    the live window it had, and those megabytes are gone whether or not the
 *    teardown was the row's fault. "The connection was down" excuses the row from
 *    blame; it does not refund the data.
 *
 * Charging bounds the waste at `OUTBOUND_MAX_AUTO_ATTEMPTS x filesize` and lets the
 * auto-flush park-and-surface the row exactly like a wedged send head, so the tail
 * drains. Mid-file offset-resume — which would make retries nearly free and is the
 * real cure — stays #1604's scope.
 */
internal const val OUTBOUND_UPLOAD_FAILURE_ATTEMPT_DELTA: Int = 1

internal fun OutboundItem.isComposerQueueRetryable(): Boolean =
    (state == OutboundState.Queued || state == OutboundState.Failed) &&
        isHostAckOrdinaryRetryAllowed()

/** Issue #2240: HostAck exit 5/ambiguous timeout requires an explicit decision. */
internal fun OutboundItem.isComposerQueueHostAckUnknown(): Boolean =
    hostAckOutcome == OutboundDeliveryOutcome.UnknownMayHaveLanded

/**
 * Issue #2056: this row's last delivery attempt resolved with a genuinely UNPROVABLE
 * outcome — the payload very probably landed, but no authority could confirm it.
 *
 * Such a row is NOT auto-dispatchable. Its retry is ledger-gated
 * (`verifyBeforeAgentResend` refuses to re-paste once the submit was attempted), so
 * every auto-flush dispatch can only reproduce the same unknown answer — while
 * occupying the FIFO head and starving every prompt behind it. On base that starve
 * was permanent, because the deferral re-granted the row's whole retry budget so it
 * could never even park: the maintainer's "once the first message gets clogged,
 * every following message gets clogged too". It is skipped here (never dropped, and
 * still surfaced + user-retryable) so the tail drains on its own evidence.
 */
internal fun OutboundItem.isComposerQueueDeliveryUnconfirmed(): Boolean =
    (state == OutboundState.Queued || state == OutboundState.Failed) && wireOutcomeUnknown

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

/**
 * Issue #1602: the AUTO-flush selection — the oldest retryable row for
 * [sessionKey] whose bounded auto-retry budget is NOT yet exhausted
 * ([OutboundItem.attemptCount] < [maxAutoAttempts]). A row that has failed/wedged
 * this many delivery attempts is a permanently-stuck HEAD; skipping it here (it is
 * parked as `Failed` + surfaced by [autoRetryExhaustedComposerRows]) lets the
 * healthy TAIL drain instead of the drain re-picking the stuck head forever (the
 * head-of-line clog the #1562 audit reported after a reconnect). A user's explicit
 * per-row Retry bypasses this bound (it dispatches the row directly), so a parked
 * row is never permanently un-sendable — only auto-skipped.
 */
internal fun Iterable<OutboundItem>.firstComposerAutoFlushable(
    sessionKey: String,
    excludingIds: Set<String> = emptySet(),
    maxAutoAttempts: Int,
    nowMillis: Long = System.currentTimeMillis(),
): OutboundItem? {
    // Preserve physical FIFO across identity promotion. An older InFlight or
    // Uploading row may still own bytes already pasted into the same pane; it
    // blocks every younger row until its terminal callback. Skip-and-surface
    // exceptions (never silent drops): an exhausted row (#1602), a delivery-
    // unconfirmed row (#2056), and a stale unapproved / held-for-review row
    // (#1700 — old intent must not block a clearly-current tail).
    val head = firstOrNull { item ->
        item.sessionKey == sessionKey &&
            item.isComposerQueueUndelivered() &&
            !(item.isComposerQueueRetryable() && item.attemptCount >= maxAutoAttempts) &&
            !item.isComposerQueueDeliveryUnconfirmed() &&
            !item.isComposerQueueHostAckUnknown() &&
            !item.isComposerQueueHeldForReview() &&
            !item.isStaleUnapproved(nowMillis)
    } ?: return null
    return head.takeIf {
        it.isComposerQueueRetryable() && it.id !in excludingIds
    }
}

/**
 * Issue #1602: retryable rows for [sessionKey] whose bounded auto-retry budget is
 * exhausted ([OutboundItem.attemptCount] >= [maxAutoAttempts]) and that are still
 * sitting `Queued` (a wedged head re-armed by the deferred/auto-retry path). The
 * auto-flush parks these as `Failed` so they are SURFACED (the launcher badge's
 * failure state + a "Failed — …" label) instead of silently blocking the tail —
 * skip-AND-surface, never a silent drop.
 */
internal fun Iterable<OutboundItem>.autoRetryExhaustedComposerRows(
    sessionKey: String,
    excludingIds: Set<String> = emptySet(),
    maxAutoAttempts: Int,
): List<OutboundItem> =
    filter { item ->
        item.sessionKey == sessionKey &&
            item.id !in excludingIds &&
            item.state == OutboundState.Queued &&
            item.attemptCount >= maxAutoAttempts &&
            !item.isComposerQueueHostAckUnknown()
    }

/** Issue #1602: the auto-flush decision — ids to park (exhausted heads) + the next dispatchable id. */
internal data class ComposerAutoFlushPlan(val parkIds: List<String>, val nextId: String?)

/**
 * A host/name owner is only a parking identity for a row already stamped with
 * an exact tmux generation and pane. Let the live pane binding promote that row
 * to its durable `tmux:` owner before any auto-flush can claim it; otherwise a
 * Connected edge can race the pane-list update and drain the fallback row
 * without ever proving that it still belongs to this generation (#1944).
 */
internal fun Iterable<OutboundItem>.hasGenerationBoundRowsAwaitingPromotion(
    sessionKey: String,
): Boolean = !sessionKey.startsWith("tmux:") && any { item ->
    item.sessionKey == sessionKey &&
        item.isComposerQueueUndelivered() &&
        !item.isComposerQueueHostAckUnknown() &&
        !item.paneId.isNullOrBlank() &&
        !item.tmuxSessionId.isNullOrBlank() &&
        item.tmuxSessionCreated != null
}

/**
 * Issue #1602: plan one auto-flush cycle for [sessionKey]. Parking (Queued→Failed)
 * an exhausted head does not change whether it is dispatchable (the attempt-bound
 * filter already excludes it), so [nextId] is computed from the same snapshot.
 */
internal fun Iterable<OutboundItem>.planComposerAutoFlush(
    sessionKey: String,
    excludingIds: Set<String> = emptySet(),
    maxAutoAttempts: Int = OUTBOUND_MAX_AUTO_ATTEMPTS,
    nowMillis: Long = System.currentTimeMillis(),
): ComposerAutoFlushPlan = ComposerAutoFlushPlan(
    parkIds = autoRetryExhaustedComposerRows(sessionKey, excludingIds, maxAutoAttempts).map { it.id },
    nextId = firstComposerAutoFlushable(
        sessionKey,
        excludingIds,
        maxAutoAttempts,
        nowMillis,
    )?.id,
)

internal fun Iterable<OutboundItem>.composerQueueRetryableItems(): List<OutboundItem> =
    filter { it.isComposerQueueRetryable() }

internal fun Iterable<OutboundItem>.deferredRetryCandidateFor(
    request: PromptComposerViewModel.SendRequest,
): OutboundItem? {
    val candidates = filter {
        it.isComposerQueueUndelivered() && !it.isComposerQueueHostAckUnknown()
    }
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
        hasFailure = undelivered.any {
            it.state == OutboundState.Failed || it.isComposerQueueHeldForReview()
        },
    )
}
