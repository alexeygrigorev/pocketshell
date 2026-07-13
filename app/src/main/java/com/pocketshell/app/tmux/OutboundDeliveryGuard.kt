package com.pocketshell.app.tmux

import android.util.Log
import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.core.tmux.TmuxClient
import com.pocketshell.core.tmux.TmuxDisconnectEvent
import com.pocketshell.core.tmux.TmuxDisconnectReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Issue #1526 — Slice S1: verify-before-resend for exactly-once OUTBOUND delivery.
 *
 * Every outbound lane used to be at-least-once with CLIENT-judged delivery: a
 * timed-out/dropped `send-keys` exec was interpreted as "not delivered" and
 * replayed in full — but cancelling/timing out the exec only closes the exec's
 * OWN channel; the server-side `tmux send-keys` may already have run
 * (`TmuxClient.sendKeysViaExec`). Under a connection flap that ambiguity is the
 * norm, so the reconnect auto-flush / retry re-pasted payloads that HAD landed —
 * the maintainer's "messages/keystrokes delivered multiple times" (#1526, audit
 * failure modes A1/A2/B2; the recurrence class of #961, whose enqueue-level
 * dedup cannot see the wire).
 *
 * This file is the delivery-level idempotency guard:
 *
 *  - [OutboundDeliveryLedger] remembers which (pane, payload) pairs have a
 *    PRIOR wire attempt whose outcome is ambiguous (bytes may have landed).
 *  - [probeOutboundPayloadAlreadyLanded] answers "did it actually land?" by
 *    reusing the #869 ack-needle mechanism: `capture-pane` (exec lane) +
 *    whitespace-stripped needle presence ([agentSubmitAckNeedle] /
 *    [agentSubmitVisibleTextContainsNeedle]).
 *  - [deliverDequeuedInputBatch] is the keystroke-lane (per-pane input queue)
 *    delivery loop, extracted from [TmuxSessionViewModel] with its blind
 *    2-attempt retry (audit B2) made probe-gated.
 *
 * A probe that cannot decide (needle underivable, capture failed/errored — the
 * probe rides the same flaky lane) reports [DeliveryProbeOutcome.Unknown]; the
 * composer lane then keeps the row QUEUED without resending (never "unknown ⇒
 * resend"), while the keystroke lane falls back to its pre-existing retry
 * behaviour (never MORE duplication than base, never new silent drops — the
 * stricter bounded-replay semantics are slice S3).
 */
internal enum class DeliveryProbeOutcome { AlreadyLanded, NotLanded, Unknown }

/**
 * Issue #1526 test seams (#780 synthetic-injection model). Production never arms
 * them (both default false); each is consumed once.
 *
 * - [failSendResultLostBeforeSubmitEnter]: the next agent-payload send drops the
 *   transport after the paste landed and throws before the submit Enter — the
 *   exact audit cut point (c) a real mid-send flap produces (payload on the
 *   server, outcome lost to the client).
 * - [failInputSendResultLostOnce]: the next pane-input batch send reports
 *   failure AFTER its bytes landed (result lost) — the ambiguous B2 cut.
 */
internal object OutboundDeliverySeams {
    @Volatile
    var failSendResultLostBeforeSubmitEnter: Boolean = false

    @Volatile
    var failInputSendResultLostOnce: Boolean = false

    fun consumeSendResultLostBeforeSubmitEnter(): Boolean =
        failSendResultLostBeforeSubmitEnter.also {
            if (it) failSendResultLostBeforeSubmitEnter = false
        }

    fun consumeInputSendResultLostOnce(): Boolean =
        failInputSendResultLostOnce.also {
            if (it) failInputSendResultLostOnce = false
        }
}

/**
 * Composer-lane verify-before-resend gate: null when this (pane, payload) has no
 * ambiguous prior wire attempt (the common first-send case — no probe cost);
 * otherwise the probe's verdict, recorded to diagnostics.
 */
internal suspend fun verifyBeforeAgentResend(
    ledger: OutboundDeliveryLedger,
    client: TmuxClient,
    paneId: String,
    payload: String,
): DeliveryProbeOutcome? {
    if (!ledger.hasAmbiguousAttempt(paneId, payload)) return null
    val outcome = probeOutboundPayloadAlreadyLanded(client, paneId, payload)
    DiagnosticEvents.record(
        "action",
        "outbound_verify_before_resend",
        "pane" to paneId,
        "outcome" to outcome.name,
    )
    return outcome
}

/** Bounded round-trip for a verify-before-resend `capture-pane` probe. */
internal const val OUTBOUND_DELIVERY_PROBE_TIMEOUT_MS: Long = 4_000L

/**
 * Minimum printable needle length for a keystroke-batch probe. Below this the
 * needle is too generic for a visible-viewport presence check (a lone `y` or an
 * arrow key would false-positive on almost any pane), so the probe reports
 * [DeliveryProbeOutcome.Unknown] and the caller keeps its base behaviour.
 */
internal const val INPUT_BATCH_PROBE_MIN_NEEDLE_CHARS: Int = 8

/**
 * Delivery-attempt ledger: which (pane, payload) pairs have an in-flight or
 * failed-with-AMBIGUOUS-outcome wire attempt. Recorded immediately BEFORE the
 * first payload byte can reach the wire and cleared when the delivery chain
 * completes (or when a verify-probe resolves the ambiguity), so a later re-send
 * of the SAME payload to the SAME pane knows it must verify before re-pasting.
 *
 * Keyed by pane + payload identity, NOT by queue-row id: the durable queue's
 * exactly-once guarantees end at the client store (#961), and the watchdog /
 * strand paths can re-mint requests for the same payload. Bounded LRU so a
 * long-lived VM cannot accumulate stale entries.
 */
internal class OutboundDeliveryLedger(private val maxEntries: Int = 64) {
    private val lock = Any()
    private val entries = LinkedHashSet<String>()

    private fun key(paneId: String, payload: String): String =
        "$paneId|${payload.length}|${payload.hashCode()}"

    fun recordWireAttempt(paneId: String, payload: String): Unit = synchronized(lock) {
        val key = key(paneId, payload)
        entries.remove(key)
        entries.add(key)
        while (entries.size > maxEntries) {
            entries.remove(entries.first())
        }
    }

    fun clear(paneId: String, payload: String): Unit = synchronized(lock) {
        entries.remove(key(paneId, payload))
    }

    fun hasAmbiguousAttempt(paneId: String, payload: String): Boolean = synchronized(lock) {
        key(paneId, payload) in entries
    }
}

/**
 * Probe whether [payload] is ALREADY visible in [paneId] — i.e. a prior,
 * ambiguously-failed attempt actually landed server-side. Reuses the #869
 * ack-needle: the whitespace-stripped tail of the payload's last line matched
 * against the whitespace-stripped visible `capture-pane` text (exec lane, so it
 * does not head-of-line-block behind a `-CC` burst).
 *
 * Never throws (except cancellation): a failed/errored capture is [DeliveryProbeOutcome.Unknown]
 * — the caller must treat "unknown" as "do NOT blindly resend", never as "resend".
 */
internal suspend fun probeOutboundPayloadAlreadyLanded(
    client: TmuxClient,
    paneId: String,
    payload: String,
): DeliveryProbeOutcome = probeNeedleAlreadyLanded(client, paneId, agentSubmitAckNeedle(payload))

internal suspend fun probeNeedleAlreadyLanded(
    client: TmuxClient,
    paneId: String,
    needle: String?,
): DeliveryProbeOutcome {
    if (needle == null) return DeliveryProbeOutcome.Unknown
    val response = try {
        client.capturePaneTextViaExec(paneId, timeoutMs = OUTBOUND_DELIVERY_PROBE_TIMEOUT_MS)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        return DeliveryProbeOutcome.Unknown
    }
    if (response.isError) return DeliveryProbeOutcome.Unknown
    return if (agentSubmitVisibleTextContainsNeedle(response.output, needle)) {
        DeliveryProbeOutcome.AlreadyLanded
    } else {
        DeliveryProbeOutcome.NotLanded
    }
}

/**
 * Derive a `capture-pane` probe needle from a raw keystroke batch: the
 * whitespace-stripped tail of the batch's PRINTABLE text (ESC/CSI sequences and
 * control bytes stripped — they never echo as-is). Returns null when the batch
 * has too little printable content to probe safely
 * ([INPUT_BATCH_PROBE_MIN_NEEDLE_CHARS]).
 */
internal fun inputBatchProbeNeedle(bytes: ByteArray): String? {
    val text = String(bytes, Charsets.UTF_8)
    val printable = buildString {
        var index = 0
        while (index < text.length) {
            val ch = text[index]
            if (ch == '\u001b') {
                index += 1
                if (index < text.length && text[index] == '[') {
                    index += 1
                    while (index < text.length && !text[index].isLetter() && text[index] != '~') {
                        index += 1
                    }
                    if (index < text.length) index += 1
                } else if (index < text.length) {
                    index += 1
                }
                continue
            }
            if (!ch.isISOControl()) append(ch)
            index += 1
        }
    }
    val stripped = printable.replace(WHITESPACE_RUN, "")
    if (stripped.length < INPUT_BATCH_PROBE_MIN_NEEDLE_CHARS) return null
    return stripped.takeLast(AGENT_SUBMIT_ACK_NEEDLE_TAIL_CHARS)
}

private val WHITESPACE_RUN = Regex("\\s+")

/**
 * Keystroke-lane (per-pane input queue) batch delivery — extracted verbatim
 * from `TmuxSessionViewModel.sendDequeuedInputBatch` (#1526 S1), with the blind
 * second attempt (audit B2) made PROBE-GATED:
 *
 *  - Attempt 1 failing is an AMBIGUOUS outcome (the exec may have run
 *    server-side before its result was lost). Before attempt 2, probe the pane
 *    for the batch's printable tail: already landed ⇒ record sent, NO resend
 *    (server occurrence stays 1); not landed ⇒ retry exactly as before; unknown
 *    (no derivable needle / capture failed) ⇒ keep the base retry behaviour.
 *  - The `client_superseded` requeue (the OLD client may have delivered before
 *    the NEW one replays the batch) gets the same probe against the CURRENT
 *    client when this call already made an ambiguous wire attempt.
 *
 * Returns true when the batch is resolved (sent, suppressed-as-already-landed,
 * or dropped after exhausting attempts); false when it was requeued for a later
 * client/window.
 */
internal suspend fun deliverDequeuedInputBatch(
    client: TmuxClient,
    paneId: String,
    batch: TmuxPaneInputBatch,
    queue: TmuxPaneInputQueue,
    currentClient: () -> TmuxClient?,
    sendBytes: suspend (TmuxClient, String, ByteArray) -> Unit,
    onPersistentFailureOfCurrentClient: suspend (TmuxDisconnectEvent) -> Unit,
): Boolean {
    var lastFailure: Throwable? = null
    var ambiguousWireAttempt = false
    repeat(TMUX_INPUT_SEND_MAX_ATTEMPTS) { attempt ->
        if (attempt > 0) {
            delay(TMUX_INPUT_SEND_RETRY_DELAY_MS)
            // Issue #1526 S1: verify-before-resend. Attempt 1's failure is
            // ambiguous — probe (after the retry delay, so a just-landed echo
            // has settled) before re-sending the same bytes (B2).
            if (probeInputBatchAlreadyLanded(client, paneId, batch, "retry")) {
                queue.recordSent(batch)
                return true
            }
        }
        val current = currentClient()
        if (client !== current && !client.disconnected.value) {
            // Issue #1526 S1: the batch is about to be requeued for the NEW
            // client — but a prior ambiguous attempt on THIS client may already
            // have delivered it. Probe with the current client before letting
            // the replay duplicate it (audit B2, superseded half).
            if (ambiguousWireAttempt && current != null &&
                probeInputBatchAlreadyLanded(current, paneId, batch, "superseded")
            ) {
                queue.recordSent(batch)
                return true
            }
            DiagnosticEvents.record(
                "connection",
                "pane_input_send_abandoned",
                "pane" to paneId,
                "bytes" to batch.bytes.size,
                "attempt" to (attempt + 1),
                "reason" to "client_superseded",
                "clientHash" to System.identityHashCode(client),
                "currentClientHash" to current?.let { System.identityHashCode(it) },
            )
            queue.requeueFront(batch)
            return false
        }
        val outcome = runCatching {
            sendBytes(client, paneId, batch.bytes)
            if (OutboundDeliverySeams.consumeInputSendResultLostOnce()) {
                // Test seam (#780 synthetic-injection model): the bytes DID land
                // server-side but the exec result was lost — the exact ambiguous
                // cut a real mid-send drop produces.
                throw IllegalStateException("test-seam: input send result lost after landing")
            }
        }
        if (outcome.isSuccess) {
            queue.recordSent(batch)
            return true
        }
        ambiguousWireAttempt = true
        lastFailure = outcome.exceptionOrNull()
        (lastFailure as? CancellationException)?.let { throw it }
        DiagnosticEvents.record(
            "connection",
            "pane_input_send_failed",
            "pane" to paneId,
            "bytes" to batch.bytes.size,
            "attempt" to (attempt + 1),
            "maxAttempts" to TMUX_INPUT_SEND_MAX_ATTEMPTS,
            "willRetry" to (attempt + 1 < TMUX_INPUT_SEND_MAX_ATTEMPTS),
            "clientHash" to System.identityHashCode(client),
            "currentClient" to (client === currentClient()),
            "clientDisconnected" to client.disconnected.value,
            "exceptionClass" to lastFailure?.javaClass?.simpleName,
            "message" to lastFailure?.message,
        )
    }
    val failure = lastFailure
    Log.w(
        ISSUE_145_RECONNECT_TAG,
        "tmux-pane-input-send-failed pane=$paneId bytes=${batch.bytes.size} " +
            "clientCurrent=${client === currentClient()} clientDisconnected=${client.disconnected.value}",
        failure,
    )
    if (client === currentClient()) {
        onPersistentFailureOfCurrentClient(
            TmuxDisconnectEvent(
                reason = if (client.disconnected.value) {
                    TmuxDisconnectReason.ReaderEof
                } else {
                    TmuxDisconnectReason.ReaderException
                },
                source = "pane_input_send",
                intent = "input_send_failure",
                commandKind = "send-keys",
                exceptionClass = failure?.javaClass?.simpleName,
                message = failure?.message,
            ),
        )
    }
    queue.recordDropped(batch)
    return true
}

private suspend fun probeInputBatchAlreadyLanded(
    client: TmuxClient,
    paneId: String,
    batch: TmuxPaneInputBatch,
    stage: String,
): Boolean {
    val outcome = probeNeedleAlreadyLanded(client, paneId, inputBatchProbeNeedle(batch.bytes))
    DiagnosticEvents.record(
        "connection",
        "pane_input_verify_before_resend",
        "pane" to paneId,
        "bytes" to batch.bytes.size,
        "stage" to stage,
        "outcome" to outcome.name,
    )
    return outcome == DeliveryProbeOutcome.AlreadyLanded
}
