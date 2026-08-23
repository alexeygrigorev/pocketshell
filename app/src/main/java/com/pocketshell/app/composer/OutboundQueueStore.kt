package com.pocketshell.app.composer

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import com.pocketshell.app.prefs.DeferredPrefs
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #900 — Slice A: the durable persistence + idempotency CORE of the
 * **per-session outbound send queue**.
 *
 * ## Why this exists
 *
 * The send path today is fire-and-forget with no item identity and no
 * idempotency (design spike on #900): each Send tap mints a brand-new
 * fire-and-forget request, the 12s app-side `SEND_TIMEOUT_MS` re-arms the Send
 * button while a slow-but-successful remote paste is still in flight, and
 * "delivered" is a volatile boolean that a flapping link can lose. Repeated
 * taps under a flapping link produced the maintainer's ~3× duplicate.
 *
 * The cure is to route every send through a durable, ordered, per-session
 * queue where each item carries a **stable id minted once at enqueue** and a
 * persisted state machine that only the delivery ack can advance to
 * `Delivered`. This file is the standalone CORE of that queue: the durable
 * store + the state machine + the exactly-once flush-claim API. The composer
 * Send→enqueue wiring (Slice B), the flush worker + ack-gate (Slice C), and
 * the end-to-end journey (Slice D) build on top of this and are OUT of scope
 * here.
 *
 * ## Idempotency / send-once contract (the heart of the 3× bug)
 *
 * 1. **Enqueue is keyed by the item id.** [enqueue] mints a stable
 *    [UUID][java.util.UUID] ONCE and persists it before any delivery attempt.
 *    [enqueueExisting] re-enqueuing an id that is already pending
 *    (`Queued`/`Uploading`/`InFlight`) is a **no-op** — the durable state, not
 *    a volatile in-memory flag, gates the repeat. This survives the 12s
 *    timeout re-arm AND process death.
 *
 * 2. **A single serial per-session flush-claim API guarantees exactly-once.**
 *    [claimNext] atomically moves the oldest `Queued` item for a session to
 *    `InFlight` and returns it; two concurrent flushers calling [claimNext]
 *    for the same session can never both receive the same item (one wins, the
 *    other gets either the next queued item or `null`). [markDelivered] /
 *    [markFailed] complete the transition. An item already `InFlight` is never
 *    re-claimed, so a reconnect-triggered flush racing an in-progress delivery
 *    cannot re-paste it.
 *
 * ## Per-session key (the #899 dependency)
 *
 * Items are keyed by an opaque [OutboundItem.sessionKey] **string param** that
 * the caller supplies. Slice A does NOT derive the key from a folder name —
 * the folder-derived `"${hostId}/${sessionName}"` is the exact collision #899
 * is fixing. In Slice C this param will be bound to the durable `SessionId`
 * #899 introduces. Until then it is just an opaque key: a flush for session A
 * never claims an item keyed to session B ([claimNext] filters by sessionKey).
 *
 * ## Why SharedPreferences (matching the #832 store family)
 *
 * Same trade-off [ComposerDraftStore] made: one user, a handful of queued
 * items, no relational queries needed. The queue is a JSON-ish blob per
 * session in a dedicated prefs file; a per-item state transition is a
 * read-modify-write of that session's small list (acceptable at this scale,
 * as the spike notes). A future issue is free to migrate to Room. The
 * [DurableAttachmentRef] reuse means the attachment encoding is shared with
 * [ComposerDraftStore].
 *
 * ## Foreground-only (D21)
 *
 * Pure on-disk state. It never holds a connection or schedules work. The flush
 * worker that consumes it (Slice C) runs only while foregrounded.
 *
 * ## Hard-cut (D22)
 *
 * Brand-new auxiliary store — no legacy shape to honour. It does NOT wire into
 * any existing send path in Slice A.
 */
public interface OutboundQueueStore {

    /**
     * Enqueue a new outbound item for [sessionKey], minting a stable [UUID] id
     * ONCE and persisting it in state [OutboundState.Queued] before any
     * delivery attempt. Returns the newly created item (its [OutboundItem.id]
     * is the durable idempotency key the caller holds onto).
     *
     * Position/ordering is by [OutboundItem.createdAtMs] (oldest-first), so
     * the flush loop delivers in the order the user committed to send.
     *
     * ## Logical-send coalesce (issue #961)
     *
     * When [sendKey] is non-empty AND an existing row for [sessionKey] carries
     * the SAME [sendKey] and is still **un-delivered** (`Queued`/`Uploading`/
     * `InFlight`/`Failed`), this does NOT mint a second row: it returns that
     * existing row (re-arming a `Failed` match back to `Queued`, since a
     * repeat enqueue is the explicit "send again" intent). This is the cure for
     * the drop+reconnect double-send: after a failed send the row is left
     * `Failed` (still queued/retryable) and the in-flight flag is reset, so a
     * user re-Send of the restored draft would otherwise mint a SECOND
     * deliverable row for one logical prompt and the reconnect auto-flush would
     * deliver BOTH. A delivered+pruned row is gone, so a genuinely-new send of
     * identical text after delivery still mints a fresh row (correct). An empty
     * [sendKey] never coalesces — each enqueue is its own row.
     */
    public fun enqueue(
        sessionKey: String,
        cleanText: String,
        attachments: List<DurableAttachmentRef> = emptyList(),
        withEnter: Boolean = true,
        createdAtMs: Long = System.currentTimeMillis(),
        paneId: String = "",
        route: OutboundRoute = OutboundRoute.RawBytes,
        agentKind: String? = null,
        sendKey: String = "",
        tmuxSessionId: String? = null,
        tmuxSessionCreated: Long? = null,
    ): OutboundItem

    /**
     * Idempotent re-enqueue of an item that already has an id (e.g. a retry
     * path that already knows the id). If an item with [item]'s id is already
     * present AND pending (`Queued`/`Uploading`/`InFlight`), this is a
     * **NO-OP** and returns the EXISTING persisted item — never a second copy.
     * A `Failed` item is re-armed to `Queued` (an explicit retry). A
     * `Delivered` id (already pruned in normal flow) is treated as
     * already-done and not re-added. If the id is unknown, the item is stored
     * as given.
     *
     * This is the structural send-once guard: a duplicate enqueue of a pending
     * id can never create a second deliverable item.
     *
     * ## Logical-send coalesce (issue #961)
     *
     * Beyond the id match, if [item] carries a non-empty [OutboundItem.sendKey]
     * and a DIFFERENT existing row for the same session has the SAME `sendKey`
     * and is still un-delivered (`Queued`/`Uploading`/`InFlight`/`Failed`), this
     * coalesces to that existing row (re-arming a `Failed` match to `Queued`)
     * instead of adding a sibling — so the sidecar/attachment re-send path is
     * covered by the same dedup as [enqueue].
     */
    public fun enqueueExisting(item: OutboundItem): OutboundItem

    /** All items for [sessionKey], ordered oldest-first by [OutboundItem.createdAtMs]. */
    public fun itemsFor(sessionKey: String): List<OutboundItem>

    /**
     * Issue #1589: every un-delivered row across all sessions, oldest-first.
     * Sidecar repair compares refs against [allLiveRowIds].
     */
    public fun allLiveItems(): List<OutboundItem>

    /** Stable ids of [allLiveItems]. */
    public fun allLiveRowIds(): Set<String> =
        allLiveItems().mapTo(mutableSetOf()) { it.id }

    /**
     * Atomically move undelivered rows from a temporary [fromSessionKey] to the
     * canonical [toSessionKey], but only when each row's tap-time pane belongs
     * to [livePaneIds]. This is the bounded identity-promotion path for a live
     * tmux generation first opened before its durable session id was known.
     *
     * Pane membership is mandatory: a session name alone is never proof, since
     * a same-name successor must not inherit the previous generation's prompts.
     * Stable row ids and every delivery/wire field are preserved.
     */
    public fun promoteSessionIdentity(
        fromSessionKey: String,
        toSessionKey: String,
        livePaneIds: Set<String>,
        tmuxSessionId: String,
        tmuxSessionCreated: Long,
    ): List<OutboundItem>

    /** The item with [id], or `null` if unknown (e.g. already delivered+pruned). */
    public fun item(id: String): OutboundItem?

    /**
     * Atomically claim the oldest `Queued` item for [sessionKey], transition it
     * to [OutboundState.InFlight], bump [OutboundItem.attemptCount], persist
     * that, and return it. Returns `null` when there is no `Queued` item for
     * that session.
     *
     * **Exactly-once guarantee:** the claim is serialized so two concurrent
     * callers for the same [sessionKey] can never both receive the same item.
     * An item NOT in `Queued` state (e.g. already `InFlight` from a prior
     * claim) is skipped, so a reconnect-triggered flush racing an in-progress
     * delivery does not re-claim it. Items keyed to other sessions are never
     * returned (per-session isolation).
     *
     * Slice C's flush worker is the single serial consumer per session; this
     * API is what makes a second concurrent flusher safe even if one is ever
     * started.
     */
    public fun claimNext(
        sessionKey: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): OutboundItem?

    /**
     * Atomically claim the exact `Queued`/`Failed` item with [id], transition it
     * to [OutboundState.InFlight], bump [OutboundItem.attemptCount], persist
     * that, and return it. Unlike [claimNext], this never picks an older row:
     * it is the manual retry/clicked-row primitive.
     *
     * Returns `null` when [id] is unknown, already delivered/pruned, already
     * [OutboundState.InFlight], [OutboundState.Uploading], or otherwise not
     * claimable.
     */
    public fun claim(
        id: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): OutboundItem?

    /**
     * Mark the item with [id] [OutboundState.InFlight] for a send attempt that
     * already knows its durable id. Unlike [claimNext], this does not pick the
     * oldest queued row; it transitions exactly the row that the caller just
     * enqueued and bumps [OutboundItem.attemptCount]. No-op for unknown,
     * delivered, or failed rows.
     */
    public fun markInFlight(id: String): OutboundItem?

    /**
     * Atomically claim [id] for attachment upload by moving a `Queued`/`Failed`
     * row to [OutboundState.Uploading]. Returns `null` if the id is unknown or
     * already owned by another upload/send attempt. [lastAttemptAtMs] stamps
     * upload ownership so stale recovery does not requeue a fresh upload whose
     * row carries an older delivery failure timestamp.
     */
    public fun markUploading(
        id: String,
        lastAttemptAtMs: Long = System.currentTimeMillis(),
        nowMillis: Long = lastAttemptAtMs,
    ): OutboundItem?

    /**
     * Replace [id]'s attachment refs after a queued attachment upload has
     * completed, then re-arm the row to [OutboundState.Queued] so the normal
     * exactly-once claim path can deliver it. This does NOT bump
     * [OutboundItem.attemptCount]; upload preparation is separate from a paste
     * attempt.
     *
     * Only `Queued`/`Uploading` rows are mutable here. Unknown rows return
     * `null`; `InFlight`/`Delivered`/`Failed` rows are returned unchanged so a
     * late upload callback cannot rewrite or resurrect an active/terminal send.
     */
    public fun markAttachmentsUploaded(
        id: String,
        attachments: List<DurableAttachmentRef>,
    ): OutboundItem?

    /**
     * Complete delivery of the item with [id]: the ack confirmed the paste was
     * ingested and Enter pressed. The row is transitioned to
     * [OutboundState.Delivered] and **pruned** so it never grows unbounded and
     * never appears in the UI again. A repeated [markDelivered] for an
     * already-delivered/pruned id is a NO-OP (a late duplicate ack cannot
     * cause a second delivery). Returns `true` if this call performed the
     * delivery transition, `false` if it was already delivered/unknown.
     */
    public fun markDelivered(id: String): Boolean

    /**
     * Issue #2037: terminally acknowledge one exact late-success attempt.
     * The compare-and-prune is atomic so a stale UI snapshot or a newer retry
     * generation cannot acknowledge the wrong row.
     */
    public fun acknowledgeLateDelivered(
        id: String,
        sendKey: String,
        wireAttemptGeneration: Int,
    ): Boolean

    /**
     * Mark the item with [id] [OutboundState.Failed] with [lastError] and stamp
     * [lastAttemptAtMs]. Attempt counts are bumped when a row is claimed for an
     * attempt ([claimNext], [claim], [markInFlight]), not when the failure is
     * recorded. The row is KEPT (visible, retryable) — never silently dropped.
     * No-op for an unknown/already-delivered id. Returns the updated item or
     * `null`.
     */
    public fun markFailed(
        id: String,
        lastError: String?,
        lastAttemptAtMs: Long = System.currentTimeMillis(),
    ): OutboundItem?

    /**
     * Issue #971/#987: re-arm exactly the row with [id] back to
     * [OutboundState.Queued] and CLEAR its [OutboundItem.lastError] so the
     * foreground/reconnect auto-flush can re-claim it. This is the canonical
     * drop-failure path (maintainer decision on #987 — Option A): a send that
     * failed because the connection dropped STAYS QUEUED and auto-retries on
     * reconnect, instead of returning to the composer for a manual resend. It is
     * distinct from [markFailed] (which leaves a terminal-looking `Failed —
     * <error>` row): a deferred row reads as the single coherent "Will send when
     * reconnected." status with no contradictory manual-resend messaging.
     *
     * Only an un-delivered row (`Queued`/`Uploading`/`InFlight`/`Failed`) is
     * re-armed; an unknown/`Delivered` id returns `null` (a late ack cannot
     * resurrect a pruned/delivered row). Attempt counts are not bumped here; the
     * next [claimNext] / [claim] records the next attempt.
     *
     * Issue #1602: when [resetAttempts] is true the row's
     * [OutboundItem.attemptCount] is CLEARED to 0, granting a FRESH bounded auto-
     * retry budget ([OUTBOUND_MAX_AUTO_ATTEMPTS]). This is
     * for the EXPLICIT user "resend all" action ([PromptComposerViewModel.resendAllQueued]),
     * so a row that was auto-parked (`Failed`, budget exhausted) is genuinely re-driven
     * by the auto-flush instead of being re-parked on the very next cycle. The
     * auto-defer path (a mid-flight drop) leaves it false so the bound still
     * accumulates across silent reconnect retries.
     *
     * Issue #1635 (design D4): [attemptDelta] adjusts [OutboundItem.attemptCount]
     * by an explicit signed amount (floored at 0, ignored when [resetAttempts]).
     * The budget is charged at CLAIM, but only the FAILURE knows whether the
     * attempt was the row's fault, so both corrections happen here:
     *  - `-1` **refunds** a send attempt that failed because the delivery window
     *    was closed/flipped under it (#1635-A). Storm failures are not the row's
     *    fault; charging them is what parked the whole queue and stopped it
     *    auto-sending after the link recovered.
     *  - `+1` **charges** an UPLOAD failure (#1635-B). The upload leg transitions
     *    via [markUploading], which does NOT claim, so an upload-failing row was
     *    entirely IMMUNE to the bound: it looped from byte 0 every 3s forever,
     *    starving the tail behind it and burning mobile data without limit.
     */
    public fun requeueForRetry(
        id: String,
        resetAttempts: Boolean = false,
        attemptDelta: Int = 0,
    ): OutboundItem?

    /**
     * Issue #1700: atomically move every unowned stale-unapproved row for
     * [sessionKey] to [OutboundState.HeldForReview]. Auto-flush cannot claim a
     * held row; [approveStaleForSend] is the only re-arm. Returns the held rows
     * oldest-first. Active `Uploading`/`InFlight` attempts are left alone.
     */
    public fun holdStaleUnapproved(
        sessionKey: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): List<OutboundItem>

    /**
     * Issue #1700: persist approval on the same row id and re-arm it to
     * [OutboundState.Queued] so the normal exactly-once claim path can deliver
     * it. Wire baselines and attachment checkpoints are preserved. Approval
     * survives process death and retry; it is not requested again. Returns
     * `null` for unknown/`Delivered` ids.
     */
    public fun approveStaleForSend(
        id: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): OutboundItem?

    /**
     * Requeue stale [OutboundState.InFlight] rows for [sessionKey] so a
     * foreground/reconnect flush can pick them up again after a prior delivery
     * attempt was abandoned by process death or a lost callback.
     *
     * Staleness is intentionally caller-controlled: a row is stale when
     * `(lastAttemptAtMs ?: createdAtMs) <= cutoffMs`. Rows with no
     * [OutboundItem.lastAttemptAtMs] fall back to [OutboundItem.createdAtMs]
     * for older persisted items. This method does not bump attempt counts or
     * clear error metadata; the next [claimNext] / [claim] records the next
     * attempt.
     *
     * Returns the updated rows, oldest-first by [OutboundItem.createdAtMs].
     */
    public fun requeueStaleInFlight(
        sessionKey: String,
        cutoffMs: Long,
    ): List<OutboundItem>

    /**
     * Remove the item with [id] regardless of state — the user explicitly
     * cancelled/deleted a pending item before it sent (AC4). Returns `true` if
     * an item was removed.
     */
    public fun remove(id: String): Boolean

    /**
     * Atomically remove an idle row owned by [id], or return `null` without
     * changing the store when the row is active or missing. The production
     * caller separately holds an [OutboundDisposalPermit], so a delivery worker
     * cannot begin ownership during this store transaction. The state check and removal happen
     * under the queue-store lock; a claim racing this operation therefore wins
     * exactly one side of the interleaving, never both.
     *
     * This is the only row-removal primitive used by authorized user disposal.
     * It intentionally permits `HeldForReview` because an explicit Delete is
     * an exact user action; `Uploading`/`InFlight` remain protected until their
     * owner has cancelled and joined.
     */
    public fun removeIfIdle(id: String): OutboundItem?

    /** Drop every item for [sessionKey]. */
    public fun clearSession(sessionKey: String)

    /**
     * Issue #1541/#1739: durably mark that the exact outbound row [itemId] in
     * [sessionKey] has been **pushed to the wire** — a delivery attempt started
     * that may have landed server-side. Queue row ids are the idempotency tokens
     * carried by the composer send request; pane ids and payload bytes are not
     * identities because every tmux session can own `%0` and two sessions can
     * legitimately send identical text.
     * The flag ([OutboundItem.wireAttempted]) is persisted ON THE ROW, so it
     * survives a plain **VM-clear / back-navigation** (which destroys the volatile
     * [OutboundDeliveryLedger][com.pocketshell.app.tmux.OutboundDeliveryLedger]),
     * not only live-VM retries. A rebuilt ledger reads it back via [hasWireAttempt]
     * and runs verify-before-resend instead of a blind re-paste (server occurrence
     * 2). No-op when no un-delivered row matches. Returns the updated row, or null.
     *
     * The flag lives and dies with the row: it is set here and by the InFlight
     * claim ([claimNext] / [claim] / [markInFlight]); it is CLEARED only when the
     * row leaves the queue ([markDelivered] prune / [remove] / [clearSession]), and
     * PRESERVED across [requeueForRetry] / [requeueStaleInFlight] so a requeued row
     * still verifies before re-pasting. That is what also closes the
     * `markDelivered`-lost-on-`apply()` corner: a delivered row whose prune write
     * was lost survives with the flag still set, so the rebuild verifies (already
     * landed ⇒ occurrence 1) instead of blindly re-pasting.
     */
    public fun markWireAttempted(
        sessionKey: String,
        itemId: String,
        atMs: Long = System.currentTimeMillis(),
        baselineCount: Int? = null,
        collapsedMarkerBaselineCount: Int? = null,
    ): OutboundItem?

    /** Whether the exact persisted row [itemId] in [sessionKey] has a wire attempt. */
    public fun hasWireAttempt(sessionKey: String, itemId: String): Boolean

    /**
     * Issue #1944: durably record that submit Enter was written, but agent-side
     * turnover has not yet been proven. A rebuilt sender must fail closed instead
     * of issuing another Enter or pruning the row from tmux write completion.
     */
    public fun markWireSubmitAttempted(
        sessionKey: String,
        itemId: String,
        transcriptBaseline: OutboundSubmitTranscriptBaseline? = null,
    ): OutboundItem?

    /** Whether Enter was attempted without a proven agent-side turnover. */
    public fun hasWireSubmitAttempt(sessionKey: String, itemId: String): Boolean

    /**
     * Issue #2056: durably record that the last delivery attempt for [id] resolved with
     * an UNPROVABLE outcome (see [OutboundItem.wireOutcomeUnknown]). No-op for an
     * unknown or already-delivered row. Returns the updated row, or `null`.
     */
    public fun markDeliveryOutcomeUnknown(id: String): OutboundItem?

    /** Transcript authority captured by the same synchronous pre-Enter write-ahead. */
    public fun wireSubmitTranscriptBaseline(
        sessionKey: String,
        itemId: String,
    ): OutboundSubmitTranscriptBaseline?

    /**
     * Issue #1577: the durable [OutboundItem.wireNeedleBaselineCount] recorded for
     * the exact row ([sessionKey], [itemId]), or `null` when none was captured. A
     * ledger rebuilt after a VM-clear reads this so a genuine resend can compare the
     * CURRENT pane needle count against the pre-send baseline (only an INCREASE means
     * "landed"), instead of a presence-only match that a pre-existing status line
     * would false-trip.
     */
    public fun wireNeedleBaseline(sessionKey: String, itemId: String): Int?

    /** Issue #1739: durable pre-send collapsed-paste-chip count for multiline payloads. */
    public fun wireCollapsedMarkerBaseline(sessionKey: String, itemId: String): Int?
}

/**
 * Issue #1541: the durable backing the verify-before-resend
 * [OutboundDeliveryLedger][com.pocketshell.app.tmux.OutboundDeliveryLedger] reads
 * so a wire attempt survives VM-clear (plain back-navigation) / process death.
 * Implemented over [OutboundQueueStore.markWireAttempted] / [OutboundQueueStore.hasWireAttempt]
 * (the flag lives on the persisted row); see [OutboundQueueStore.asWireAttemptDurableStore].
 */
public interface OutboundWireAttemptDurableStore {
    /**
     * Persist that exact ([sessionKey], [itemId]) row has been pushed to the wire.
     * Issue #1577:
     * [baselineCount] is the pre-send occurrence count of the payload's verify needle
     * on the pane (or `null` when it could not be captured), stored once with the
     * first wire attempt so a rebuilt ledger can compare against it.
     */
    public fun recordWireAttempt(
        sessionKey: String,
        itemId: String,
        atMs: Long,
        baselineCount: Int? = null,
        collapsedMarkerBaselineCount: Int? = null,
    )

    /** Whether a durable wire attempt is recorded for exact ([sessionKey], [itemId]). */
    public fun hasWireAttempt(sessionKey: String, itemId: String): Boolean

    /** Returns true only after the submit latch is durably acknowledged. */
    public fun recordWireSubmitAttempt(
        sessionKey: String,
        itemId: String,
        transcriptBaseline: OutboundSubmitTranscriptBaseline? = null,
    ): Boolean

    public fun hasWireSubmitAttempt(sessionKey: String, itemId: String): Boolean

    public fun wireSubmitTranscriptBaseline(
        sessionKey: String,
        itemId: String,
    ): OutboundSubmitTranscriptBaseline?

    /** Issue #1577: durable pre-send needle baseline for exact row, or `null`. */
    public fun wireNeedleBaseline(sessionKey: String, itemId: String): Int?

    /** Issue #1739: durable pre-send collapsed-paste-chip count, or `null`. */
    public fun wireCollapsedMarkerBaseline(sessionKey: String, itemId: String): Int?
}

/** Issue #1541: adapt this store as the ledger's durable wire-attempt backing. */
public fun OutboundQueueStore.asWireAttemptDurableStore(): OutboundWireAttemptDurableStore =
    object : OutboundWireAttemptDurableStore {
        override fun recordWireAttempt(
            sessionKey: String,
            itemId: String,
            atMs: Long,
            baselineCount: Int?,
            collapsedMarkerBaselineCount: Int?,
        ) {
            markWireAttempted(
                sessionKey,
                itemId,
                atMs,
                baselineCount,
                collapsedMarkerBaselineCount,
            )
        }

        override fun hasWireAttempt(sessionKey: String, itemId: String): Boolean =
            this@asWireAttemptDurableStore.hasWireAttempt(sessionKey, itemId)

        override fun recordWireSubmitAttempt(
            sessionKey: String,
            itemId: String,
            transcriptBaseline: OutboundSubmitTranscriptBaseline?,
        ): Boolean =
            this@asWireAttemptDurableStore.markWireSubmitAttempted(
                sessionKey,
                itemId,
                transcriptBaseline,
            ) != null

        override fun hasWireSubmitAttempt(sessionKey: String, itemId: String): Boolean =
            this@asWireAttemptDurableStore.hasWireSubmitAttempt(sessionKey, itemId)

        override fun wireSubmitTranscriptBaseline(
            sessionKey: String,
            itemId: String,
        ): OutboundSubmitTranscriptBaseline? =
            this@asWireAttemptDurableStore.wireSubmitTranscriptBaseline(sessionKey, itemId)

        override fun wireNeedleBaseline(sessionKey: String, itemId: String): Int? =
            this@asWireAttemptDurableStore.wireNeedleBaseline(sessionKey, itemId)

        override fun wireCollapsedMarkerBaseline(sessionKey: String, itemId: String): Int? =
            this@asWireAttemptDurableStore.wireCollapsedMarkerBaseline(sessionKey, itemId)
    }

/**
 * Issue #900: the durable, process-death-survivable representation of one
 * committed-to-send outbound item.
 *
 * @property id stable idempotency key, minted ONCE at [OutboundQueueStore.enqueue].
 * @property sessionKey the opaque per-session key (the #899 durable SessionId
 *   in Slice C; an opaque param in Slice A — NOT folder-derived here).
 * @property cleanText the editable draft text (no appended "Attached files:" block).
 * @property attachments staged attachment refs (reuses [DurableAttachmentRef]).
 * @property withEnter whether delivery presses Enter after the paste.
 * @property state the persisted state-machine position.
 * @property createdAtMs enqueue time — the ordering key (oldest-first).
 * @property lastAttemptAtMs time of the last delivery attempt, or `null`. Issue
 *   #1542 (Q1): stamped when the row is claimed InFlight
 *   ([OutboundQueueStore.claimNext]/[OutboundQueueStore.claim]/[OutboundQueueStore.markInFlight]),
 *   as well as on [OutboundQueueStore.markUploading]/[OutboundQueueStore.markFailed].
 *   The stale-recovery sweep ([OutboundQueueStore.requeueStaleInFlight]) ages an
 *   InFlight/Uploading row against this so an orphaned in-flight attempt can be
 *   swept by its CLAIM age rather than its enqueue age. `null` only for a legacy
 *   row that was never attempted (aged by [createdAtMs] as a fallback).
 * @property attemptCount number of delivery attempts so far.
 * @property lastError the last delivery error, or `null`.
 * @property paneId pane targeted by the original send action. Empty for legacy
 *   rows that were persisted before route metadata existed.
 * @property route the delivery path selected when the item was enqueued.
 * @property agentKind optional agent token captured with agent-routed sends.
 * @property sendKey the logical-send identity (issue #961): a stable hash of
 *   `(sessionKey, cleanText, target pane/route, withEnter, attachment refs)`.
 *   Two `enqueue`/`enqueueExisting` calls that carry the SAME non-empty
 *   `sendKey` while a matching row is still un-delivered (`Queued`/`Uploading`/
 *   `InFlight`/`Failed`) COALESCE to one row — the cure for the drop+reconnect
 *   double-send (a post-failure re-Send of the restored draft must not create a
 *   second deliverable row). Empty for legacy rows / callers that do not supply
 *   a logical key; an empty `sendKey` never coalesces (each is its own row).
 * @property wireAttempted issue #1541: durably records that this row has been
 *   PUSHED TO THE WIRE at least once (a delivery attempt started that may have
 *   landed server-side). Set when the row is claimed InFlight / marked via
 *   [OutboundQueueStore.markWireAttempted]; PRESERVED across requeue so a rebuilt
 *   verify-before-resend ledger consults it after a VM-clear / back-navigation
 *   (survives the volatile ledger dying) and probes instead of blindly re-pasting.
 *   Cleared only when the row leaves the queue (delivered-prune / remove / clear).
 * @property wireAttemptedAtMs the time [wireAttempted] was first set, or `null`.
 * @property wireNeedleBaselineCount issue #1577: the pre-send occurrence count of
 *   the payload's verify needle on the pane, captured at the FIRST wire attempt
 *   (before the paste), or `null` when it was never captured (a legacy row / the
 *   baseline probe failed). The verify-before-resend probe reports `AlreadyLanded`
 *   only when the CURRENT occurrence count EXCEEDS this baseline — i.e. the paste
 *   actually added an occurrence — so a payload that was already visible on the
 *   pane (a Codex `Goal blocked (/goal resume)` status line) is not mistaken for a
 *   landed send. Persisted so the refinement survives a VM-clear / back-navigation
 *   (the rebuilt ledger reads it back); `null` falls back to presence-only (#1541).
 * @property wireCollapsedMarkerBaselineCount issue #1739: the pre-send count of
 *   Claude's collapsed multiline-paste chips. A retry may treat a higher current
 *   count as proof that the ambiguous paste landed, then submit Enter-only.
 * @property wireOutcomeUnknown issue #2056: the LAST delivery attempt resolved with a
 *   genuinely UNPROVABLE outcome — the payload may well have landed (it usually has),
 *   but no authority could confirm it. This is a first-class user-visible state, not a
 *   failure and not "still sending": the drain must not keep re-dispatching the row
 *   (every such dispatch can only reproduce the same unknown answer, and while it owns
 *   the FIFO head nothing behind it can drain), and the UI must say so instead of
 *   claiming "Queued — sending next". Cleared whenever a fresh attempt is claimed
 *   ([claimedForAttempt]) or the user explicitly re-drives the row
 *   ([requeueForRetry] with `resetAttempts`), so it always describes the latest
 *   resolved attempt.
 * @property wireSubmitAttempted issue #1944: durably records that Enter was sent
 *   after the payload paste. If turnover could not be confirmed, a rebuilt VM
 *   keeps the row unresolved and must not issue another blind Enter.
 * @property tmuxSessionId tap-time tmux session id used to keep queued delivery
 *   bound to the exact durable session generation.
 * @property tmuxSessionCreated tap-time tmux creation epoch paired with
 *   [tmuxSessionId]; both fields are required for identity promotion.
 */
public data class OutboundSubmitTranscriptBaseline(
    val sourcePath: String,
    val agentSessionId: String?,
    val agentKind: String,
    val confirmedMatchingIds: Set<String>,
)

public data class OutboundItem(
    val id: String,
    val sessionKey: String,
    val cleanText: String,
    val attachments: List<DurableAttachmentRef> = emptyList(),
    val withEnter: Boolean = true,
    val state: OutboundState = OutboundState.Queued,
    val createdAtMs: Long,
    val lastAttemptAtMs: Long? = null,
    val attemptCount: Int = 0,
    val lastError: String? = null,
    val paneId: String = "",
    val route: OutboundRoute = OutboundRoute.RawBytes,
    val agentKind: String? = null,
    val sendKey: String = "",
    val wireAttempted: Boolean = false,
    val wireAttemptedAtMs: Long? = null,
    val wireNeedleBaselineCount: Int? = null,
    val wireCollapsedMarkerBaselineCount: Int? = null,
    val wireSubmitAttempted: Boolean = false,
    val wireAttemptGeneration: Int = 0,
    val wireOutcomeUnknown: Boolean = false,
    val wireSubmitTranscriptBaseline: OutboundSubmitTranscriptBaseline? = null,
    /** Tap-time tmux generation. Both fields are required for identity promotion. */
    val tmuxSessionId: String? = null,
    val tmuxSessionCreated: Long? = null,
    /**
     * Issue #1700: wall-clock approval of a stale row. Non-null means the user
     * tapped Send now (or batch-approved) this exact id; auto-flush may claim
     * it again even after [OUTBOUND_STALE_HOLD_MS]. Never cleared by a retry.
     */
    val staleApprovedAtMs: Long? = null,
)

/** Issue #900: persisted send route selected before an item entered the durable queue. */
public enum class OutboundRoute {
    AgentConversation,
    AgentPayload,
    RawBytes,
}

/**
 * Issue #900: the per-item state machine.
 *
 * `Queued → Uploading → InFlight → Delivered` is the happy path; any attempt
 * can drop to `Failed`, which is retryable (back to `Queued`) and never
 * silently dropped. [isPending] is the set that gates a duplicate enqueue
 * no-op.
 */
public enum class OutboundState {
    Queued,
    Uploading,
    InFlight,
    Delivered,
    Failed,
    /**
     * Issue #1700: unapproved intent older than [OUTBOUND_STALE_HOLD_MS].
     * Auto-flush cannot claim it. Send now re-arms the same id; Delete
     * removes it. Never silently dropped.
     */
    HeldForReview,
    ;

    /**
     * Whether an item in this state is still "committed and not yet delivered"
     * — a duplicate enqueue of an id in a pending state is a no-op. `Failed` is
     * NOT pending (it is re-armable to `Queued` by an explicit retry).
     */
    public val isPending: Boolean
        get() = this == Queued || this == Uploading || this == InFlight

    /** Explicit Delete may remove idle/held intent, never an active attempt. */
    public val isExplicitlyDiscardable: Boolean
        get() = this == Queued || this == Failed || this == HeldForReview
}

/**
 * Issue #900: in-memory [OutboundQueueStore] — the production store's test
 * double AND the default for connected tests that need real per-session queue
 * semantics within one process without touching SharedPreferences. Mirrors
 * [InMemoryComposerDraftStore].
 *
 * **Thread-safe by design** — the exactly-once flush-claim guarantee
 * ([claimNext]) is the whole point of this store, so every mutation is
 * serialized under a single [lock]. Two concurrent flushers calling
 * [claimNext] for the same session therefore cannot both win the same item.
 */
// `open` so unit tests can override a single store method to deterministically
// drive an internal early-return strand (issue #929 regression coverage) without
// minting a whole parallel fake store.
public open class InMemoryOutboundQueueStore : OutboundQueueStore {

    private val lock = Any()

    /** id -> item. A single map is enough; per-session views filter by [OutboundItem.sessionKey]. */
    private val items: MutableMap<String, OutboundItem> = LinkedHashMap()

    override fun enqueue(
        sessionKey: String,
        cleanText: String,
        attachments: List<DurableAttachmentRef>,
        withEnter: Boolean,
        createdAtMs: Long,
        paneId: String,
        route: OutboundRoute,
        agentKind: String?,
        sendKey: String,
        tmuxSessionId: String?,
        tmuxSessionCreated: Long?,
    ): OutboundItem = synchronized(lock) {
        // Issue #961: coalesce a re-Send of the SAME logical prompt while a
        // matching un-delivered row still exists, instead of minting a second
        // deliverable row (the drop+reconnect double-send).
        val coalesce = items.values.toList().coalesceTargetForSendKey(sessionKey, sendKey)
        if (coalesce != null) {
            val rearmed = coalesce.reArmedForCoalesce()
            items[rearmed.id] = rearmed
            return@synchronized rearmed
        }
        val item = OutboundItem(
            id = UUID.randomUUID().toString(),
            sessionKey = sessionKey,
            cleanText = cleanText,
            attachments = attachments,
            withEnter = withEnter,
            state = OutboundState.Queued,
            createdAtMs = createdAtMs,
            paneId = paneId,
            route = route,
            agentKind = agentKind,
            sendKey = sendKey,
            tmuxSessionId = tmuxSessionId,
            tmuxSessionCreated = tmuxSessionCreated,
        )
        items[item.id] = item
        item
    }

    override fun enqueueExisting(item: OutboundItem): OutboundItem = synchronized(lock) {
        val existing = items[item.id]
        when {
            // Already pending — duplicate enqueue is a strict no-op.
            existing != null && existing.state.isPending -> existing
            // Already delivered (and not yet pruned) — do not resurrect.
            existing != null && existing.state == OutboundState.Delivered -> existing
            // Failed → explicit retry re-arms to Queued.
            existing != null && existing.state == OutboundState.Failed -> {
                val rearmed = existing.copy(state = OutboundState.Queued)
                items[rearmed.id] = rearmed
                rearmed
            }
            // Held → still needs review; a duplicate enqueue of the same id is a no-op.
            existing != null && existing.state == OutboundState.HeldForReview -> existing
            // Unknown id but a same-sendKey un-delivered sibling exists — coalesce
            // (issue #961: the sidecar/attachment re-send path mints a fresh id,
            // so the id branch above never fires; dedup on the logical key here).
            else -> {
                val coalesce = items.values.toList()
                    .coalesceTargetForSendKey(item.sessionKey, item.sendKey, excludeId = item.id)
                if (coalesce != null) {
                    val rearmed = coalesce.reArmedForCoalesce()
                    items[rearmed.id] = rearmed
                    rearmed
                } else {
                    items[item.id] = item
                    item
                }
            }
        }
    }

    override fun itemsFor(sessionKey: String): List<OutboundItem> = synchronized(lock) {
        items.values
            .filter { it.sessionKey == sessionKey }
            .sortedBy { it.createdAtMs }
    }

    override fun allLiveItems(): List<OutboundItem> = synchronized(lock) {
        items.values
            .filter { it.state != OutboundState.Delivered }
            .sortedBy { it.createdAtMs }
    }

    override fun promoteSessionIdentity(
        fromSessionKey: String,
        toSessionKey: String,
        livePaneIds: Set<String>,
        tmuxSessionId: String,
        tmuxSessionCreated: Long,
    ): List<OutboundItem> = synchronized(lock) {
        if (fromSessionKey.isBlank() || toSessionKey.isBlank() ||
            fromSessionKey == toSessionKey || livePaneIds.isEmpty()
        ) return emptyList()
        val promoted = items.values
            .filter {
                it.sessionKey == fromSessionKey &&
                    it.state != OutboundState.Delivered &&
                    it.paneId.isNotBlank() &&
                    it.paneId in livePaneIds &&
                    it.tmuxSessionId == tmuxSessionId &&
                    it.tmuxSessionCreated == tmuxSessionCreated
            }
            .sortedBy { it.createdAtMs }
            .map { it.copy(sessionKey = toSessionKey) }
        promoted.forEach { items[it.id] = it }
        promoted
    }

    override fun item(id: String): OutboundItem? = synchronized(lock) { items[id] }

    override fun claimNext(
        sessionKey: String,
        nowMillis: Long,
    ): OutboundItem? = synchronized(lock) {
        holdStaleLocked(sessionKey, nowMillis)
        val next = items.values
            .filter {
                it.sessionKey == sessionKey &&
                    it.state == OutboundState.Queued &&
                    !it.isStaleUnapproved(nowMillis)
            }
            .minByOrNull { it.createdAtMs }
            ?: return null
        val claimed = next.claimedForAttempt()
        items[claimed.id] = claimed
        claimed
    }

    override open fun claim(id: String, nowMillis: Long): OutboundItem? = synchronized(lock) {
        val existing = items[id] ?: return null
        if (existing.isStaleUnapproved(nowMillis)) {
            items[existing.id] = existing.heldForReview()
            return null
        }
        if (!existing.state.isExactClaimable) return null
        val claimed = existing.claimedForAttempt()
        items[claimed.id] = claimed
        claimed
    }

    override fun markInFlight(id: String): OutboundItem? = synchronized(lock) {
        val existing = items[id] ?: return null
        if (existing.state == OutboundState.Delivered ||
            existing.state == OutboundState.Failed ||
            existing.state == OutboundState.HeldForReview
        ) {
            return existing
        }
        val updated = existing.claimedForAttempt()
        items[updated.id] = updated
        updated
    }

    override open fun markUploading(
        id: String,
        lastAttemptAtMs: Long,
        nowMillis: Long,
    ): OutboundItem? = synchronized(lock) {
        val existing = items[id] ?: return null
        if (existing.isStaleUnapproved(nowMillis)) {
            val held = existing.heldForReview()
            items[held.id] = held
            return null
        }
        if (!existing.state.isExactClaimable) return null
        val updated = existing.copy(
            state = OutboundState.Uploading,
            lastAttemptAtMs = lastAttemptAtMs,
        )
        items[updated.id] = updated
        updated
    }

    override fun markAttachmentsUploaded(
        id: String,
        attachments: List<DurableAttachmentRef>,
    ): OutboundItem? = synchronized(lock) {
        val existing = items[id] ?: return null
        if (existing.state != OutboundState.Queued && existing.state != OutboundState.Uploading) {
            return existing
        }
        val updated = existing.copy(
            attachments = attachments,
            state = OutboundState.Queued,
            lastError = null,
        )
        items[updated.id] = updated
        updated
    }

    override fun markDelivered(id: String): Boolean = synchronized(lock) {
        val existing = items[id] ?: return false
        if (existing.state == OutboundState.Delivered) return false
        // Prune on delivery so the store never grows unbounded.
        items.remove(id)
        true
    }

    override fun acknowledgeLateDelivered(id: String, sendKey: String, wireAttemptGeneration: Int): Boolean =
        synchronized(lock) {
            val current = items[id] ?: return@synchronized false
            if (
                (current.state != OutboundState.Queued && current.state != OutboundState.Failed) ||
                current.sendKey != sendKey ||
                current.wireAttemptGeneration != wireAttemptGeneration ||
                !current.wireSubmitAttempted
            ) {
                return@synchronized false
            }
            items.remove(id)
            true
        }

    override fun markFailed(id: String, lastError: String?, lastAttemptAtMs: Long): OutboundItem? =
        synchronized(lock) {
            val existing = items[id] ?: return null
            if (existing.state == OutboundState.Delivered) return existing
            val updated = existing.copy(
                state = OutboundState.Failed,
                lastError = lastError,
                lastAttemptAtMs = lastAttemptAtMs,
            )
            items[updated.id] = updated
            updated
        }

    override fun requeueForRetry(
        id: String,
        resetAttempts: Boolean,
        attemptDelta: Int,
    ): OutboundItem? = synchronized(lock) {
        val existing = items[id] ?: return null
        if (existing.state == OutboundState.Delivered) return null
        if (existing.state == OutboundState.HeldForReview) return existing
        val updated = existing.copy(
            state = OutboundState.Queued,
            lastError = null,
            attemptCount = existing.adjustedAttemptCount(resetAttempts, attemptDelta),
            // Issue #2056: an explicit user re-drive ("Resend all" / per-row Retry
            // budget reset) discards the previous attempt's unprovable verdict so the
            // row is genuinely re-dispatched instead of staying permanently unknown.
            wireOutcomeUnknown = if (resetAttempts) false else existing.wireOutcomeUnknown,
        )
        items[updated.id] = updated
        updated
    }

    override fun holdStaleUnapproved(
        sessionKey: String,
        nowMillis: Long,
    ): List<OutboundItem> = synchronized(lock) { holdStaleLocked(sessionKey, nowMillis) }

    override fun approveStaleForSend(id: String, nowMillis: Long): OutboundItem? =
        synchronized(lock) {
            val existing = items[id] ?: return null
            if (existing.state == OutboundState.Delivered) return null
            val updated = existing.copy(
                state = OutboundState.Queued,
                lastError = null,
                staleApprovedAtMs = existing.staleApprovedAtMs ?: nowMillis,
            )
            items[updated.id] = updated
            updated
        }

    private fun holdStaleLocked(sessionKey: String, nowMillis: Long): List<OutboundItem> {
        val held = items.values
            .filter { it.sessionKey == sessionKey && it.isStaleUnapproved(nowMillis) }
            .sortedBy { it.createdAtMs }
            .map { it.heldForReview() }
        held.forEach { items[it.id] = it }
        return held
    }

    override fun requeueStaleInFlight(sessionKey: String, cutoffMs: Long): List<OutboundItem> =
        synchronized(lock) {
            val updated = items.values
                .filter { it.sessionKey == sessionKey && it.isStaleRecoverableAttempt(cutoffMs) }
                .sortedBy { it.createdAtMs }
                .map { it.copy(state = OutboundState.Queued) }
            updated.forEach { items[it.id] = it }
            updated
        }

    override fun remove(id: String): Boolean = synchronized(lock) { items.remove(id) != null }

    override fun removeIfIdle(id: String): OutboundItem? = synchronized(lock) {
        val existing = items[id] ?: return@synchronized null
        if (!existing.state.isExplicitlyDiscardable) {
            return@synchronized null
        }
        items.remove(id)
    }

    override fun clearSession(sessionKey: String): Unit = synchronized(lock) {
        items.values.removeAll { it.sessionKey == sessionKey }
    }

    override fun markWireAttempted(
        sessionKey: String,
        itemId: String,
        atMs: Long,
        baselineCount: Int?,
        collapsedMarkerBaselineCount: Int?,
    ): OutboundItem? =
        synchronized(lock) {
            val target = items[itemId]
                ?.takeIf { it.sessionKey == sessionKey && it.state != OutboundState.Delivered }
                ?: return null
            val updated = target.markedWireAttempted(
                atMs,
                baselineCount,
                collapsedMarkerBaselineCount,
            )
            items[updated.id] = updated
            updated
        }

    override fun hasWireAttempt(sessionKey: String, itemId: String): Boolean = synchronized(lock) {
        items[itemId]?.let { it.sessionKey == sessionKey && it.wireAttempted } == true
    }

    override fun markWireSubmitAttempted(
        sessionKey: String,
        itemId: String,
        transcriptBaseline: OutboundSubmitTranscriptBaseline?,
    ): OutboundItem? = synchronized(lock) {
        val target = items[itemId]
            ?.takeIf { it.sessionKey == sessionKey && it.state != OutboundState.Delivered }
            ?: return null
        val updated = target.copy(
            wireSubmitAttempted = true,
            wireAttemptGeneration = target.wireAttemptGeneration + 1,
            wireSubmitTranscriptBaseline = transcriptBaseline,
        )
        items[itemId] = updated
        updated
    }

    override fun hasWireSubmitAttempt(sessionKey: String, itemId: String): Boolean = synchronized(lock) {
        items[itemId]?.let { it.sessionKey == sessionKey && it.wireSubmitAttempted } == true
    }

    override fun markDeliveryOutcomeUnknown(id: String): OutboundItem? = synchronized(lock) {
        val existing = items[id] ?: return null
        if (existing.state == OutboundState.Delivered) return existing
        val updated = existing.copy(wireOutcomeUnknown = true)
        items[updated.id] = updated
        updated
    }

    override fun wireSubmitTranscriptBaseline(
        sessionKey: String,
        itemId: String,
    ): OutboundSubmitTranscriptBaseline? = synchronized(lock) {
        items[itemId]
            ?.takeIf { it.sessionKey == sessionKey && it.wireSubmitAttempted }
            ?.wireSubmitTranscriptBaseline
    }

    override fun wireNeedleBaseline(sessionKey: String, itemId: String): Int? = synchronized(lock) {
        items[itemId]
            ?.takeIf { it.sessionKey == sessionKey && it.wireAttempted }
            ?.wireNeedleBaselineCount
    }

    override fun wireCollapsedMarkerBaseline(sessionKey: String, itemId: String): Int? = synchronized(lock) {
        items[itemId]
            ?.takeIf { it.sessionKey == sessionKey && it.wireAttempted }
            ?.wireCollapsedMarkerBaselineCount
    }
}

/**
 * Issue #900: no-op [OutboundQueueStore] used when a ViewModel/test is
 * constructed without the real store. Mirrors [DisabledComposerDraftStore].
 */
public object DisabledOutboundQueueStore : OutboundQueueStore {
    override fun enqueue(
        sessionKey: String,
        cleanText: String,
        attachments: List<DurableAttachmentRef>,
        withEnter: Boolean,
        createdAtMs: Long,
        paneId: String,
        route: OutboundRoute,
        agentKind: String?,
        sendKey: String,
        tmuxSessionId: String?,
        tmuxSessionCreated: Long?,
    ): OutboundItem = OutboundItem(
        id = UUID.randomUUID().toString(),
        sessionKey = sessionKey,
        cleanText = cleanText,
        attachments = attachments,
        withEnter = withEnter,
        createdAtMs = createdAtMs,
        paneId = paneId,
        route = route,
        agentKind = agentKind,
        sendKey = sendKey,
        tmuxSessionId = tmuxSessionId,
        tmuxSessionCreated = tmuxSessionCreated,
    )

    override fun enqueueExisting(item: OutboundItem): OutboundItem = item
    override fun itemsFor(sessionKey: String): List<OutboundItem> = emptyList()
    override fun allLiveItems(): List<OutboundItem> = emptyList()
    override fun promoteSessionIdentity(
        fromSessionKey: String,
        toSessionKey: String,
        livePaneIds: Set<String>,
        tmuxSessionId: String,
        tmuxSessionCreated: Long,
    ): List<OutboundItem> = emptyList()
    override fun item(id: String): OutboundItem? = null
    override fun claimNext(sessionKey: String, nowMillis: Long): OutboundItem? = null
    override fun claim(id: String, nowMillis: Long): OutboundItem? = null
    override fun markInFlight(id: String): OutboundItem? = null
    override fun markUploading(id: String, lastAttemptAtMs: Long, nowMillis: Long): OutboundItem? = null
    override fun markAttachmentsUploaded(id: String, attachments: List<DurableAttachmentRef>): OutboundItem? = null
    override fun markDelivered(id: String): Boolean = false
    override fun acknowledgeLateDelivered(id: String, sendKey: String, wireAttemptGeneration: Int): Boolean = false
    override fun markFailed(id: String, lastError: String?, lastAttemptAtMs: Long): OutboundItem? = null
    override fun requeueForRetry(id: String, resetAttempts: Boolean, attemptDelta: Int): OutboundItem? = null
    override fun holdStaleUnapproved(sessionKey: String, nowMillis: Long): List<OutboundItem> = emptyList()
    override fun approveStaleForSend(id: String, nowMillis: Long): OutboundItem? = null
    override fun requeueStaleInFlight(sessionKey: String, cutoffMs: Long): List<OutboundItem> = emptyList()
    override fun remove(id: String): Boolean = false
    override fun removeIfIdle(id: String): OutboundItem? = null
    override fun clearSession(sessionKey: String) = Unit
    override fun markWireAttempted(
        sessionKey: String,
        itemId: String,
        atMs: Long,
        baselineCount: Int?,
        collapsedMarkerBaselineCount: Int?,
    ): OutboundItem? = null
    override fun hasWireAttempt(sessionKey: String, itemId: String): Boolean = false

    override fun markWireSubmitAttempted(
        sessionKey: String,
        itemId: String,
        transcriptBaseline: OutboundSubmitTranscriptBaseline?,
    ): OutboundItem? = null

    override fun hasWireSubmitAttempt(sessionKey: String, itemId: String): Boolean = false
    override fun markDeliveryOutcomeUnknown(id: String): OutboundItem? = null
    override fun wireSubmitTranscriptBaseline(
        sessionKey: String,
        itemId: String,
    ): OutboundSubmitTranscriptBaseline? = null
    override fun wireNeedleBaseline(sessionKey: String, itemId: String): Int? = null
    override fun wireCollapsedMarkerBaseline(sessionKey: String, itemId: String): Int? = null
}

/**
 * Issue #900: SharedPreferences-backed production [OutboundQueueStore]. One
 * small prefs file (`outbound_queue`) with one JSON-ish blob per session key,
 * so the queue survives process death and app restart (AC1). Singleton so
 * every consumer over the app's lifetime shares the one prefs file.
 *
 * A per-item state transition is a read-modify-write of that session's small
 * item list (the spike's accepted trade-off at this scale). All mutations are
 * serialized under [lock] so the exactly-once [claimNext] guarantee holds
 * across threads within the process; the durable blob is the cross-process /
 * cross-restart guarantee.
 *
 * The blob format reuses the [ComposerDraftStore] attachment encoding for the
 * attachment field; the item rows themselves are tab-delimited and
 * newline-separated, with the same `\`-escaping so text/paths containing
 * tabs/newlines round-trip losslessly.
 */
@Singleton
public class SharedPrefsOutboundQueueStore internal constructor(
    private val deferredPrefs: DeferredPrefs,
) : OutboundQueueStore {

    @Inject
    public constructor(@ApplicationContext context: Context) : this(
        DeferredPrefs(context, PREFS_NAME),
    )

    /** Direct prefs seam for crash-window durability tests. */
    @VisibleForTesting
    internal constructor(prefs: SharedPreferences) : this(DeferredPrefs(opener = { prefs }))

    // Issue #1125: open the prefs file off the Main thread (it is opened at
    // first-composer-open Hilt injection on Main otherwise — touched on every
    // session open via the always-present composer, #809).
    private val prefs: SharedPreferences get() = deferredPrefs.get()

    @VisibleForTesting
    internal fun awaitPrefsBuildThreadNameForTest(): String =
        deferredPrefs.awaitBuildThreadNameForTest()

    private val lock = Any()

    /** All session keys we have ever written, so [item]/[markX] by id can find the owning session. */
    private fun sessionKeys(): Set<String> =
        runCatching { prefs.getStringSet(SESSION_INDEX_KEY, emptySet()) }.getOrNull().orEmpty()

    private fun loadSession(sessionKey: String): MutableList<OutboundItem> {
        if (sessionKey.isEmpty()) return mutableListOf()
        val raw = runCatching { prefs.getString(blobKey(sessionKey), null) }.getOrNull()
            ?: return mutableListOf()
        return decodeOutboundItems(sessionKey, raw).toMutableList()
    }

    private fun storeSession(
        sessionKey: String,
        list: List<OutboundItem>,
        synchronous: Boolean = false,
    ): Boolean {
        if (sessionKey.isEmpty()) return false
        val editor = prefs.edit()
        if (list.isEmpty()) {
            editor.remove(blobKey(sessionKey))
            editor.putStringSet(SESSION_INDEX_KEY, sessionKeys() - sessionKey)
        } else {
            editor.putString(blobKey(sessionKey), encodeOutboundItems(list))
            editor.putStringSet(SESSION_INDEX_KEY, sessionKeys() + sessionKey)
        }
        return if (synchronous) {
            editor.commit()
        } else {
            editor.apply()
            true
        }
    }

    /** Find the session that owns [id], or `null`. */
    private fun sessionOf(id: String): String? =
        sessionKeys().firstOrNull { key -> loadSession(key).any { it.id == id } }

    override fun enqueue(
        sessionKey: String,
        cleanText: String,
        attachments: List<DurableAttachmentRef>,
        withEnter: Boolean,
        createdAtMs: Long,
        paneId: String,
        route: OutboundRoute,
        agentKind: String?,
        sendKey: String,
        tmuxSessionId: String?,
        tmuxSessionCreated: Long?,
    ): OutboundItem = synchronized(lock) {
        val list = loadSession(sessionKey)
        // Issue #961: coalesce a re-Send of the SAME logical prompt while a
        // matching un-delivered row still exists, instead of minting a second
        // deliverable row (the drop+reconnect double-send).
        val coalesce = list.coalesceTargetForSendKey(sessionKey, sendKey)
        if (coalesce != null) {
            val rearmed = coalesce.reArmedForCoalesce()
            replaceAndStore(sessionKey, list, rearmed)
            return@synchronized rearmed
        }
        val item = OutboundItem(
            id = UUID.randomUUID().toString(),
            sessionKey = sessionKey,
            cleanText = cleanText,
            attachments = attachments,
            withEnter = withEnter,
            state = OutboundState.Queued,
            createdAtMs = createdAtMs,
            paneId = paneId,
            route = route,
            agentKind = agentKind,
            sendKey = sendKey,
            tmuxSessionId = tmuxSessionId,
            tmuxSessionCreated = tmuxSessionCreated,
        )
        list.add(item)
        storeSession(sessionKey, list.sortedBy { it.createdAtMs })
        item
    }

    override fun enqueueExisting(item: OutboundItem): OutboundItem = synchronized(lock) {
        val list = loadSession(item.sessionKey)
        val existing = list.firstOrNull { it.id == item.id }
        when {
            existing != null && existing.state.isPending -> existing
            existing != null && existing.state == OutboundState.Delivered -> existing
            existing != null && existing.state == OutboundState.Failed -> {
                val rearmed = existing.copy(state = OutboundState.Queued)
                replaceAndStore(item.sessionKey, list, rearmed)
                rearmed
            }
            existing != null && existing.state == OutboundState.HeldForReview -> existing
            else -> {
                // Issue #961: a fresh-id re-send of the same logical prompt
                // coalesces onto the existing un-delivered sibling (the sidecar
                // path mints a new id every send, so the id branch never fires).
                val coalesce = list.coalesceTargetForSendKey(item.sessionKey, item.sendKey, excludeId = item.id)
                if (coalesce != null) {
                    val rearmed = coalesce.reArmedForCoalesce()
                    replaceAndStore(item.sessionKey, list, rearmed)
                    rearmed
                } else {
                    list.add(item)
                    storeSession(item.sessionKey, list.sortedBy { it.createdAtMs })
                    item
                }
            }
        }
    }

    override fun itemsFor(sessionKey: String): List<OutboundItem> = synchronized(lock) {
        loadSession(sessionKey).sortedBy { it.createdAtMs }
    }

    override fun allLiveItems(): List<OutboundItem> = synchronized(lock) {
        sessionKeys()
            .flatMap { key -> loadSession(key) }
            .filter { it.state != OutboundState.Delivered }
            .sortedBy { it.createdAtMs }
    }

    override fun promoteSessionIdentity(
        fromSessionKey: String,
        toSessionKey: String,
        livePaneIds: Set<String>,
        tmuxSessionId: String,
        tmuxSessionCreated: Long,
    ): List<OutboundItem> = synchronized(lock) {
        if (fromSessionKey.isBlank() || toSessionKey.isBlank() ||
            fromSessionKey == toSessionKey || livePaneIds.isEmpty()
        ) return emptyList()

        val source = loadSession(fromSessionKey)
        val promotable = source.filter {
            it.state != OutboundState.Delivered &&
                it.paneId.isNotBlank() &&
                it.paneId in livePaneIds &&
                it.tmuxSessionId == tmuxSessionId &&
                it.tmuxSessionCreated == tmuxSessionCreated
        }
        if (promotable.isEmpty()) return emptyList()

        val promoted = promotable.map { it.copy(sessionKey = toSessionKey) }
        val promotedIds = promoted.mapTo(mutableSetOf()) { it.id }
        val sourceRemaining = source.filterNot { it.id in promotedIds }
        val target = loadSession(toSessionKey).filterNot { it.id in promotedIds }
        val mergedTarget = (target + promoted).sortedBy { it.createdAtMs }

        // One editor transaction updates both blobs and the ownership index, so
        // a process restart can never observe a row under neither identity.
        val index = sessionKeys().toMutableSet()
        val editor = prefs.edit()
        if (sourceRemaining.isEmpty()) {
            editor.remove(blobKey(fromSessionKey))
            index.remove(fromSessionKey)
        } else {
            editor.putString(blobKey(fromSessionKey), encodeOutboundItems(sourceRemaining))
            index.add(fromSessionKey)
        }
        editor.putString(blobKey(toSessionKey), encodeOutboundItems(mergedTarget))
        index.add(toSessionKey)
        editor.putStringSet(SESSION_INDEX_KEY, index)
        editor.apply()
        promoted
    }

    override fun item(id: String): OutboundItem? = synchronized(lock) {
        sessionKeys().firstNotNullOfOrNull { key -> loadSession(key).firstOrNull { it.id == id } }
    }

    override fun claimNext(sessionKey: String, nowMillis: Long): OutboundItem? = synchronized(lock) {
        val list = loadSession(sessionKey)
        holdStaleInList(sessionKey, list, nowMillis)
        val next = list
            .filter {
                it.state == OutboundState.Queued && !it.isStaleUnapproved(nowMillis)
            }
            .minByOrNull { it.createdAtMs }
            ?: return null
        val claimed = next.claimedForAttempt()
        replaceAndStore(sessionKey, list, claimed)
        claimed
    }

    override fun claim(id: String, nowMillis: Long): OutboundItem? = synchronized(lock) {
        val sessionKey = sessionOf(id) ?: return null
        val list = loadSession(sessionKey)
        val existing = list.firstOrNull { it.id == id } ?: return null
        if (existing.isStaleUnapproved(nowMillis)) {
            replaceAndStore(sessionKey, list, existing.heldForReview())
            return null
        }
        if (!existing.state.isExactClaimable) return null
        val claimed = existing.claimedForAttempt()
        replaceAndStore(sessionKey, list, claimed)
        claimed
    }

    override fun markInFlight(id: String): OutboundItem? = synchronized(lock) {
        val sessionKey = sessionOf(id) ?: return null
        val list = loadSession(sessionKey)
        val existing = list.firstOrNull { it.id == id } ?: return null
        if (existing.state == OutboundState.Delivered ||
            existing.state == OutboundState.Failed ||
            existing.state == OutboundState.HeldForReview
        ) {
            return existing
        }
        val updated = existing.claimedForAttempt()
        replaceAndStore(sessionKey, list, updated)
        updated
    }

    override fun markUploading(
        id: String,
        lastAttemptAtMs: Long,
        nowMillis: Long,
    ): OutboundItem? = synchronized(lock) {
        val sessionKey = sessionOf(id) ?: return null
        val list = loadSession(sessionKey)
        val existing = list.firstOrNull { it.id == id } ?: return null
        if (existing.isStaleUnapproved(nowMillis)) {
            replaceAndStore(sessionKey, list, existing.heldForReview())
            return null
        }
        if (!existing.state.isExactClaimable) return null
        val updated = existing.copy(
            state = OutboundState.Uploading,
            lastAttemptAtMs = lastAttemptAtMs,
        )
        replaceAndStore(sessionKey, list, updated)
        updated
    }

    override fun markAttachmentsUploaded(
        id: String,
        attachments: List<DurableAttachmentRef>,
    ): OutboundItem? = synchronized(lock) {
        val sessionKey = sessionOf(id) ?: return null
        val list = loadSession(sessionKey)
        val existing = list.firstOrNull { it.id == id } ?: return null
        if (existing.state != OutboundState.Queued && existing.state != OutboundState.Uploading) {
            return existing
        }
        val updated = existing.copy(
            attachments = attachments,
            state = OutboundState.Queued,
            lastError = null,
        )
        replaceAndStore(sessionKey, list, updated)
        updated
    }

    override fun markDelivered(id: String): Boolean = synchronized(lock) {
        val sessionKey = sessionOf(id) ?: return false
        val list = loadSession(sessionKey)
        val existing = list.firstOrNull { it.id == id } ?: return false
        if (existing.state == OutboundState.Delivered) return false
        // Prune on delivery.
        list.removeAll { it.id == id }
        storeSession(sessionKey, list)
        true
    }

    override fun acknowledgeLateDelivered(id: String, sendKey: String, wireAttemptGeneration: Int): Boolean =
        synchronized(lock) {
            val sessionKey = sessionOf(id) ?: return@synchronized false
            val list = loadSession(sessionKey)
            val current = list.firstOrNull { it.id == id } ?: return@synchronized false
            if (
                (current.state != OutboundState.Queued && current.state != OutboundState.Failed) ||
                current.sendKey != sendKey ||
                current.wireAttemptGeneration != wireAttemptGeneration ||
                !current.wireSubmitAttempted
            ) {
                return@synchronized false
            }
            list.removeAll { it.id == id }
            storeSession(sessionKey, list)
            true
        }

    override fun markFailed(id: String, lastError: String?, lastAttemptAtMs: Long): OutboundItem? =
        synchronized(lock) {
            val sessionKey = sessionOf(id) ?: return null
            val list = loadSession(sessionKey)
            val existing = list.firstOrNull { it.id == id } ?: return null
            if (existing.state == OutboundState.Delivered) return existing
            val updated = existing.copy(
                state = OutboundState.Failed,
                lastError = lastError,
                lastAttemptAtMs = lastAttemptAtMs,
            )
            replaceAndStore(sessionKey, list, updated)
            updated
        }

    override fun requeueForRetry(
        id: String,
        resetAttempts: Boolean,
        attemptDelta: Int,
    ): OutboundItem? = synchronized(lock) {
        val sessionKey = sessionOf(id) ?: return null
        val list = loadSession(sessionKey)
        val existing = list.firstOrNull { it.id == id } ?: return null
        if (existing.state == OutboundState.Delivered) return null
        if (existing.state == OutboundState.HeldForReview) return existing
        val updated = existing.copy(
            state = OutboundState.Queued,
            lastError = null,
            attemptCount = existing.adjustedAttemptCount(resetAttempts, attemptDelta),
            // Issue #2056: an explicit user re-drive ("Resend all" / per-row Retry
            // budget reset) discards the previous attempt's unprovable verdict so the
            // row is genuinely re-dispatched instead of staying permanently unknown.
            wireOutcomeUnknown = if (resetAttempts) false else existing.wireOutcomeUnknown,
        )
        replaceAndStore(sessionKey, list, updated)
        updated
    }

    override fun holdStaleUnapproved(
        sessionKey: String,
        nowMillis: Long,
    ): List<OutboundItem> = synchronized(lock) {
        val list = loadSession(sessionKey)
        holdStaleInList(sessionKey, list, nowMillis)
    }

    override fun approveStaleForSend(id: String, nowMillis: Long): OutboundItem? =
        synchronized(lock) {
            val sessionKey = sessionOf(id) ?: return null
            val list = loadSession(sessionKey)
            val existing = list.firstOrNull { it.id == id } ?: return null
            if (existing.state == OutboundState.Delivered) return null
            val updated = existing.copy(
                state = OutboundState.Queued,
                lastError = null,
                staleApprovedAtMs = existing.staleApprovedAtMs ?: nowMillis,
            )
            replaceAndStore(sessionKey, list, updated)
            updated
        }

    private fun holdStaleInList(
        sessionKey: String,
        list: MutableList<OutboundItem>,
        nowMillis: Long,
    ): List<OutboundItem> {
        val held = list
            .filter { it.isStaleUnapproved(nowMillis) }
            .map { it.heldForReview() }
        if (held.isEmpty()) return emptyList()
        val byId = held.associateBy { it.id }
        for (index in list.indices) {
            val replacement = byId[list[index].id] ?: continue
            list[index] = replacement
        }
        storeSession(sessionKey, list.sortedBy { it.createdAtMs })
        return held.sortedBy { it.createdAtMs }
    }

    override fun requeueStaleInFlight(sessionKey: String, cutoffMs: Long): List<OutboundItem> =
        synchronized(lock) {
            val list = loadSession(sessionKey)
            val updatedById = list
                .filter { it.isStaleRecoverableAttempt(cutoffMs) }
                .associate { it.id to it.copy(state = OutboundState.Queued) }
            if (updatedById.isEmpty()) return emptyList()
            val updatedList = list.map { updatedById[it.id] ?: it }
            storeSession(sessionKey, updatedList.sortedBy { it.createdAtMs })
            updatedById.values.sortedBy { it.createdAtMs }
        }

    override fun remove(id: String): Boolean = synchronized(lock) {
        val sessionKey = sessionOf(id) ?: return false
        val list = loadSession(sessionKey)
        val removed = list.removeAll { it.id == id }
        if (removed) storeSession(sessionKey, list)
        removed
    }

    override fun removeIfIdle(id: String): OutboundItem? = synchronized(lock) {
        val sessionKey = sessionOf(id) ?: return@synchronized null
        val list = loadSession(sessionKey)
        val target = list.firstOrNull { it.id == id }
            ?: return@synchronized null
        if (!target.state.isExplicitlyDiscardable) return@synchronized null
        val remaining = list.filterNot { it.id == id }
        storeSession(sessionKey, remaining)
        target
    }

    override fun clearSession(sessionKey: String): Unit = synchronized(lock) {
        storeSession(sessionKey, emptyList())
    }

    override fun markWireAttempted(
        sessionKey: String,
        itemId: String,
        atMs: Long,
        baselineCount: Int?,
        collapsedMarkerBaselineCount: Int?,
    ): OutboundItem? =
        synchronized(lock) {
            val list = loadSession(sessionKey)
            val target = list.firstOrNull {
                it.id == itemId &&
                    it.sessionKey == sessionKey &&
                    it.state != OutboundState.Delivered
            } ?: return null
            val updated = target.markedWireAttempted(
                atMs,
                baselineCount,
                collapsedMarkerBaselineCount,
            )
            replaceAndStore(sessionKey, list, updated)
            updated
        }

    override fun hasWireAttempt(sessionKey: String, itemId: String): Boolean = synchronized(lock) {
        loadSession(sessionKey).any {
            it.id == itemId && it.sessionKey == sessionKey && it.wireAttempted
        }
    }

    override fun markWireSubmitAttempted(
        sessionKey: String,
        itemId: String,
        transcriptBaseline: OutboundSubmitTranscriptBaseline?,
    ): OutboundItem? = synchronized(lock) {
        val list = loadSession(sessionKey)
        val target = list.firstOrNull {
            it.id == itemId && it.sessionKey == sessionKey && it.state != OutboundState.Delivered
        } ?: return null
        val updated = target.copy(
            wireSubmitAttempted = true,
            wireAttemptGeneration = target.wireAttemptGeneration + 1,
            wireSubmitTranscriptBaseline = transcriptBaseline,
        )
        val idx = list.indexOfFirst { it.id == updated.id }
        if (idx >= 0) list[idx] = updated else list.add(updated)
        // This one write is the pre-Enter write-ahead barrier. apply() is not an
        // acknowledgement: process death may drop it after Enter reached tmux.
        // commit() is called by the suspend send path on seedIoDispatcher, never Main.
        updated.takeIf { storeSession(sessionKey, list.sortedBy { it.createdAtMs }, synchronous = true) }
    }

    override fun hasWireSubmitAttempt(sessionKey: String, itemId: String): Boolean = synchronized(lock) {
        loadSession(sessionKey).any {
            it.id == itemId && it.sessionKey == sessionKey && it.wireSubmitAttempted
        }
    }

    override fun markDeliveryOutcomeUnknown(id: String): OutboundItem? = synchronized(lock) {
        val sessionKey = sessionOf(id) ?: return null
        val list = loadSession(sessionKey)
        val existing = list.firstOrNull { it.id == id } ?: return null
        if (existing.state == OutboundState.Delivered) return existing
        val updated = existing.copy(wireOutcomeUnknown = true)
        replaceAndStore(sessionKey, list, updated)
        updated
    }

    override fun wireSubmitTranscriptBaseline(
        sessionKey: String,
        itemId: String,
    ): OutboundSubmitTranscriptBaseline? = synchronized(lock) {
        loadSession(sessionKey).firstOrNull {
            it.id == itemId && it.sessionKey == sessionKey && it.wireSubmitAttempted
        }?.wireSubmitTranscriptBaseline
    }

    override fun wireNeedleBaseline(sessionKey: String, itemId: String): Int? = synchronized(lock) {
        loadSession(sessionKey)
            .firstOrNull { it.id == itemId && it.sessionKey == sessionKey && it.wireAttempted }
            ?.wireNeedleBaselineCount
    }

    override fun wireCollapsedMarkerBaseline(sessionKey: String, itemId: String): Int? = synchronized(lock) {
        loadSession(sessionKey)
            .firstOrNull { it.id == itemId && it.sessionKey == sessionKey && it.wireAttempted }
            ?.wireCollapsedMarkerBaselineCount
    }

    private fun replaceAndStore(sessionKey: String, list: MutableList<OutboundItem>, updated: OutboundItem) {
        val idx = list.indexOfFirst { it.id == updated.id }
        if (idx >= 0) list[idx] = updated else list.add(updated)
        storeSession(sessionKey, list.sortedBy { it.createdAtMs })
    }

    private companion object {
        const val PREFS_NAME = "outbound_queue"
        const val SESSION_INDEX_KEY = "@sessions"
    }
}

private val OutboundState.isExactClaimable: Boolean
    get() = this == OutboundState.Queued || this == OutboundState.Failed

private fun OutboundItem.heldForReview(): OutboundItem =
    copy(state = OutboundState.HeldForReview)

/**
 * Issue #1541 / #1554: whether this row is the durable wire-attempt record for
 * (`paneId`, `payload`) — the verify-before-resend ledger keys a wire attempt by
 * pane + the ACTUAL ON-WIRE payload. `Delivered` rows are pruned so never seen
 * here; the state guard is belt-and-suspenders.
 *
 * ## Attachment-payload awareness (issue #1554)
 *
 * The on-wire payload is NOT always the row's [OutboundItem.cleanText]: an
 * attachment-backed send delivers `cleanText` with an `"Attached files:"` block
 * of the attachment remote paths appended (see [appendAttachmentPaths] — the same
 * composition the send path uses, `PromptComposerOutboundSend`). So the ledger's
 * key is the composed form, which differs from `cleanText`. Matching ONLY on
 * `cleanText == payload` (the #1541 shape) therefore failed to recognize an
 * already-landed attachment payload after a VM-clear / back-navigation: the
 * rebuilt ledger read `hasWireAttempt(pane, composedPayload)`, found no row whose
 * `cleanText` equalled the composed payload, concluded "no prior attempt", and
 * blindly re-pasted (server occurrence 2). Match on BOTH the raw `cleanText`
 * (text-only sends — no regression) AND the reconstructed on-wire form so an
 * attachment send that already reached the server is recognized and NOT re-sent.
 */
private fun OutboundItem.matchesWireTarget(paneId: String, payload: String): Boolean {
    if (this.paneId != paneId || state == OutboundState.Delivered) return false
    if (cleanText == payload) return true
    if (attachments.isEmpty()) return false
    return appendAttachmentPaths(cleanText, attachments.map { it.remotePath }) == payload
}

/**
 * Issue #961: a row that a same-[OutboundItem.sendKey] enqueue should COALESCE
 * into rather than mint a second deliverable row. Every non-terminal state
 * qualifies: `Queued`/`Uploading`/`InFlight` are still on their way out, and
 * `Failed` is retryable-but-still-queued (the drop+reconnect case — a re-Send
 * of the restored draft must re-arm THIS row, not add a sibling). A `Delivered`
 * row is pruned, so it is never seen here; a genuinely-new identical send after
 * delivery therefore correctly mints a fresh row.
 */
private val OutboundState.isCoalescibleForSendKey: Boolean
    get() = this != OutboundState.Delivered

/**
 * Issue #961: the un-delivered row this [sendKey] enqueue must coalesce into,
 * or `null` to mint a fresh row. Never coalesces on an empty [sendKey] (legacy
 * rows + callers without a logical key are each their own row). [excludeId] is
 * the id of the row being re-enqueued in [OutboundQueueStore.enqueueExisting]
 * so an id-keyed re-enqueue does not match itself here (its own id branch
 * handles that case).
 */
private fun List<OutboundItem>.coalesceTargetForSendKey(
    sessionKey: String,
    sendKey: String,
    excludeId: String? = null,
): OutboundItem? {
    if (sendKey.isEmpty()) return null
    return this
        .filter {
            it.sessionKey == sessionKey &&
                it.id != excludeId &&
                it.sendKey == sendKey &&
                it.state.isCoalescibleForSendKey
        }
        .minByOrNull { it.createdAtMs }
}

/**
 * Issue #961: re-arm a coalesced row for the explicit "send again" intent — a
 * `Failed` match goes back to `Queued`; an already-pending match is returned
 * unchanged (a strict no-op, exactly like the id-keyed pending guard).
 */
private fun OutboundItem.reArmedForCoalesce(): OutboundItem =
    if (state == OutboundState.Failed) copy(state = OutboundState.Queued, lastError = null) else this

/**
 * Issue #1635 (design D4): resolve the requeued row's attempt count. An explicit
 * user reset ([resetAttempts]) always wins and clears the budget outright;
 * otherwise the signed [attemptDelta] correction is applied and FLOORED AT 0 —
 * a refund can never drive the count negative and hand a row an unbounded budget
 * (which would resurrect the #1602 stuck head as a forever-retrying one).
 */
private fun OutboundItem.adjustedAttemptCount(resetAttempts: Boolean, attemptDelta: Int): Int =
    if (resetAttempts) 0 else (attemptCount + attemptDelta).coerceAtLeast(0)

private fun OutboundItem.claimedForAttempt(): OutboundItem {
    // Issue #1542 (enabler Q1): stamp the CLAIM time onto [OutboundItem.lastAttemptAtMs]
    // so an InFlight row can be AGED against its actual in-flight attempt. Before
    // this, a claim to InFlight left `lastAttemptAtMs` null, so the stale-recovery
    // sweep ([isStaleRecoverableAttempt]) had to fall back to [OutboundItem.createdAtMs]
    // — and an orphaned InFlight row (send coroutine died / VM churned / process
    // death mid-send) had no claim-age to sweep against, parking on "Sending…" until
    // only the slow ~160s watchdog re-armed it (finding D7). This is DISTINCT from
    // #1541's [OutboundItem.wireAttemptedAtMs], which records the FIRST wire attempt
    // ever and is preserved across re-claims; the orphan sweep needs the LATEST
    // attempt, so it needs its own timestamp.
    //
    // Issue #1577: the claim MUST NOT stamp the durable `wireAttempted` flag. That
    // was a #1542 regression: a queue row is claimed InFlight (`markInFlight` /
    // `claim`) BEFORE the composer send emits a single byte, so stamping here marked
    // every FIRST-ever send as a prior wire attempt. That forced the #1526
    // verify-before-resend probe on the first send, and the probe's whole-viewport
    // needle false-matched a Codex "Goal blocked (/goal resume)" status line ⇒
    // false-`AlreadyLanded` ⇒ the payload was never pasted (silent false-success).
    // The ONLY correct place to set `wireAttempted` is the actual wire push
    // ([OutboundQueueStore.markWireAttempted], driven by the ledger's
    // `recordWireAttempt` right before the first byte). A first send now has
    // `wireAttempted=false`, so it is delivered normally with no probe.
    //
    // Issue #2056: a FRESH attempt invalidates the previous attempt's unprovable
    // verdict ([OutboundItem.wireOutcomeUnknown]) — the new attempt gets to decide
    // for itself. Clearing it here (rather than at the deferral) is what makes a
    // user's explicit Retry a real re-decision instead of a permanent label.
    val atMs = System.currentTimeMillis()
    return if (state == OutboundState.InFlight) {
        copy(lastAttemptAtMs = atMs, wireOutcomeUnknown = false)
    } else {
        copy(
            state = OutboundState.InFlight,
            attemptCount = attemptCount + 1,
            lastAttemptAtMs = atMs,
            wireOutcomeUnknown = false,
        )
    }
}

/**
 * Issue #1541: stamp the durable [OutboundItem.wireAttempted] flag (idempotent —
 * the first-set timestamp is preserved). A claimed / wire-pushed row carries this
 * across requeue so a rebuilt verify-before-resend ledger consults it after a
 * VM-clear / back-navigation instead of blindly re-pasting a payload that may
 * already have landed.
 */
private fun OutboundItem.markedWireAttempted(
    atMs: Long,
    baselineCount: Int? = null,
    collapsedMarkerBaselineCount: Int? = null,
): OutboundItem =
    if (wireAttempted) {
        // Idempotent: the first wire attempt's timestamp AND baseline win. A later
        // re-push (after a NotLanded probe) must not overwrite the pre-send baseline
        // (issue #1577) — it is the ONE pre-first-paste snapshot the probe compares.
        this
    } else {
        copy(
            wireAttempted = true,
            wireAttemptedAtMs = atMs,
            wireNeedleBaselineCount = baselineCount,
            wireCollapsedMarkerBaselineCount = collapsedMarkerBaselineCount,
        )
    }

private fun OutboundItem.isStaleRecoverableAttempt(cutoffMs: Long): Boolean =
    (state == OutboundState.InFlight || state == OutboundState.Uploading) &&
        (lastAttemptAtMs ?: createdAtMs) <= cutoffMs

/** Issue #900: prefs key for the item-blob slot of [sessionKey]. */
internal fun blobKey(sessionKey: String): String = "@q/$sessionKey"

/**
 * Issue #900: encode a session's outbound items as newline-separated rows.
 * Each row is tab-delimited:
 * `id ... wireSubmitAttempted \t transcriptSource \t transcriptSession \t transcriptAgent \t transcriptBaselineIds`
 * with the same `\`-escaping [ComposerDraftStore] uses so text/paths containing
 * tabs/newlines round-trip losslessly. The attachments field reuses
 * [encodeAttachments], itself escaped as a single field. Trailing fields
 * (`sendKey` #961, `wireAttempted`/`wireAttemptedAtMs` #1541,
 * `wireNeedleBaselineCount` #1577, collapsed-marker baseline #1739,
 * durable tmux identity and `wireSubmitAttempted` #1944) are appended last so legacy rows without them
 * decode to their defaults (empty `sendKey`, `wireAttempted=false`,
 * `wireNeedleBaselineCount=null`, `wireSubmitAttempted=false`) rather than a malformed row.
 */
internal fun encodeOutboundItems(items: List<OutboundItem>): String =
    items.joinToString(separator = "\n") { item ->
        listOf(
            item.id,
            item.cleanText,
            if (item.withEnter) "1" else "0",
            item.state.name,
            item.createdAtMs.toString(),
            item.lastAttemptAtMs?.toString().orEmpty(),
            item.attemptCount.toString(),
            item.lastError.orEmpty(),
            encodeAttachments(item.attachments),
            item.paneId,
            item.route.name,
            item.agentKind.orEmpty(),
            item.sendKey,
            if (item.wireAttempted) "1" else "0",
            item.wireAttemptedAtMs?.toString().orEmpty(),
            item.wireNeedleBaselineCount?.toString().orEmpty(),
            item.wireCollapsedMarkerBaselineCount?.toString().orEmpty(),
            item.tmuxSessionId.orEmpty(),
            item.tmuxSessionCreated?.toString().orEmpty(),
            if (item.wireSubmitAttempted) "1" else "0",
            item.wireSubmitTranscriptBaseline?.sourcePath.orEmpty(),
            item.wireSubmitTranscriptBaseline?.agentSessionId.orEmpty(),
            item.wireSubmitTranscriptBaseline?.agentKind.orEmpty(),
            item.wireSubmitTranscriptBaseline?.confirmedMatchingIds
                ?.joinToString(separator = "\n")
                .orEmpty(),
            item.wireAttemptGeneration.toString(),
            if (item.wireOutcomeUnknown) "1" else "0",
            item.staleApprovedAtMs?.toString().orEmpty(),
        ).joinToString(separator = "\t") { escapeQueueField(it) }
    }

/** Issue #900: inverse of [encodeOutboundItems]. Drops malformed rows defensively. */
internal fun decodeOutboundItems(sessionKey: String, raw: String): List<OutboundItem> {
    if (raw.isEmpty()) return emptyList()
    return raw.split('\n').mapNotNull { row ->
        if (row.isEmpty()) return@mapNotNull null
        val f = row.split('\t').map { unescapeQueueField(it) }
        val id = f.getOrNull(0).orEmpty()
        if (id.isEmpty()) return@mapNotNull null
        val createdAtMs = f.getOrNull(4)?.toLongOrNull() ?: return@mapNotNull null
        val submitAttempted = f.getOrNull(19) == "1"
        OutboundItem(
            id = id,
            sessionKey = sessionKey,
            cleanText = f.getOrNull(1).orEmpty(),
            withEnter = f.getOrNull(2) != "0",
            state = runCatching { OutboundState.valueOf(f.getOrNull(3).orEmpty()) }
                .getOrDefault(OutboundState.Queued),
            createdAtMs = createdAtMs,
            lastAttemptAtMs = f.getOrNull(5)?.takeIf { it.isNotEmpty() }?.toLongOrNull(),
            attemptCount = f.getOrNull(6)?.toIntOrNull() ?: 0,
            lastError = f.getOrNull(7)?.takeIf { it.isNotEmpty() },
            attachments = decodeAttachments(f.getOrNull(8).orEmpty()),
            paneId = f.getOrNull(9).orEmpty(),
            route = runCatching { OutboundRoute.valueOf(f.getOrNull(10).orEmpty()) }
                .getOrDefault(OutboundRoute.RawBytes),
            agentKind = f.getOrNull(11)?.takeIf { it.isNotEmpty() },
            sendKey = f.getOrNull(12).orEmpty(),
            wireAttempted = f.getOrNull(13) == "1",
            wireAttemptedAtMs = f.getOrNull(14)?.takeIf { it.isNotEmpty() }?.toLongOrNull(),
            wireNeedleBaselineCount = f.getOrNull(15)?.takeIf { it.isNotEmpty() }?.toIntOrNull(),
            wireCollapsedMarkerBaselineCount =
                f.getOrNull(16)?.takeIf { it.isNotEmpty() }?.toIntOrNull(),
            tmuxSessionId = f.getOrNull(17)?.takeIf { it.isNotEmpty() },
            tmuxSessionCreated = f.getOrNull(18)?.takeIf { it.isNotEmpty() }?.toLongOrNull(),
            wireSubmitAttempted = submitAttempted,
            wireAttemptGeneration = f.getOrNull(24)?.toIntOrNull()
                ?: if (submitAttempted) 1 else 0,
            wireOutcomeUnknown = f.getOrNull(25) == "1",
            staleApprovedAtMs = f.getOrNull(26)?.takeIf { it.isNotEmpty() }?.toLongOrNull(),
            wireSubmitTranscriptBaseline = f.getOrNull(20)
                ?.takeIf { it.isNotEmpty() }
                ?.let { sourcePath ->
                    OutboundSubmitTranscriptBaseline(
                        sourcePath = sourcePath,
                        agentSessionId = f.getOrNull(21)?.takeIf { it.isNotEmpty() },
                        agentKind = f.getOrNull(22).orEmpty(),
                        confirmedMatchingIds = f.getOrNull(23)
                            ?.takeIf { it.isNotEmpty() }
                            ?.split('\n')
                            ?.toSet()
                            .orEmpty(),
                    )
                },
        )
    }
}

private fun escapeQueueField(field: String): String =
    field.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

private fun unescapeQueueField(field: String): String {
    val out = StringBuilder(field.length)
    var i = 0
    while (i < field.length) {
        val c = field[i]
        if (c == '\\' && i + 1 < field.length) {
            when (field[i + 1]) {
                't' -> { out.append('\t'); i += 2 }
                'n' -> { out.append('\n'); i += 2 }
                '\\' -> { out.append('\\'); i += 2 }
                else -> { out.append(c); i += 1 }
            }
        } else {
            out.append(c)
            i += 1
        }
    }
    return out.toString()
}
