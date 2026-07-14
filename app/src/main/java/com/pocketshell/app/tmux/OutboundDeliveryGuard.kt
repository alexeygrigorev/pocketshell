package com.pocketshell.app.tmux

import android.util.Log
import com.pocketshell.app.composer.OutboundWireAttemptDurableStore
import com.pocketshell.app.composer.SharedPrefsOutboundQueueStore
import com.pocketshell.app.composer.asWireAttemptDurableStore
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
    // Issue #1577: compare the CURRENT needle count against the pre-send baseline
    // captured at the first wire attempt. `AlreadyLanded` requires the count to have
    // INCREASED (our paste actually added an occurrence) — so a payload that was
    // already on the pane before we ever pushed (a Codex `Goal blocked (/goal resume)`
    // status line) is not a false-positive.
    // Issue #1577b: a NULL baseline (a legacy pre-baseline row, or a failed baseline
    // capture) is UNTRUSTWORTHY — it CANNOT distinguish "landed" from a permanent
    // footer occurrence, so it must NEVER resolve `AlreadyLanded` (which would submit
    // a bare Enter and silently drop the payload). [probeOutboundPayloadAlreadyLanded]
    // reports `NotLanded` for a null baseline instead, so the caller does a REAL gated
    // resend that records a fresh baseline (self-healing), never a bare Enter.
    val baseline = ledger.needleBaseline(paneId, payload)
    val outcome = probeOutboundPayloadAlreadyLanded(client, paneId, payload, baseline)
    DiagnosticEvents.record(
        "action",
        "outbound_verify_before_resend",
        "pane" to paneId,
        "outcome" to outcome.name,
        "baseline" to (baseline?.toString() ?: "none"),
    )
    return outcome
}

/**
 * Issue #1586 — RawBytes (shell/composer) lane exactly-once send. The agent-payload
 * lane ([TmuxSessionViewModel.sendAgentPayloadToPaneResult]) got the #1577
 * verify-before-resend ledger + tmux-error check, but the composer RawBytes lane
 * ([TmuxSessionViewModel.writeInputToPaneResult]) had NEITHER — two HIGH holes:
 *
 *  - H1a false-success: [send] must surface a tmux `%error` (dead/closed pane) as a
 *    real failure ([throwIfTmuxError] inside [send]), so a row is not marked
 *    Delivered with nothing delivered. That check lives in the caller's byte-send
 *    ([TmuxSessionViewModel.sendInputBytesToPane]); this wrapper just propagates it.
 *  - H1b blind duplicate: an ambiguous failure (bytes may have landed, exec result
 *    lost) followed by the composer auto-retry re-ran the shell command TWICE. This
 *    routes the SAME baseline-aware [ledger] the agent lane uses (via
 *    [verifyBeforeAgentResend] / [recordWireAttemptWithBaseline]) so a retry PROBES
 *    for the already-landed payload instead of blind-re-sending.
 *
 * On `AlreadyLanded` the payload TEXT landed server-side, but the ambiguous cut may
 * have dropped its TERMINATING submit — the #1586 H1b case is exactly "literal lands,
 * submit-Enter throws", which leaves the shell command TYPED-BUT-NEVER-RUN (the probe
 * needle cannot tell "typed on the prompt" from "already ran"). So — mirroring the
 * agent lane ([TmuxSessionViewModel.sendAgentPayloadToPaneResult], which submits
 * Enter-only on `AlreadyLanded`) — complete the delivery with an Enter-ONLY submit
 * ([submitEnter]), NEVER re-sending the payload text (no duplicate). The submit is
 * issued ONLY when the original send actually carried one (a trailing CR/LF the
 * [payload] key trimmed off); a text-only / non-submit RawBytes send (partial typed
 * input, a caret-position send) has nothing to complete, so no spurious Enter is
 * injected. Then clear the ledger and run [afterDelivered] (the post-send reconcile
 * heal). `Unknown` keeps the row queued WITHOUT resending (never "unknown ⇒ resend").
 * `NotLanded` / no-prior-attempt falls through to the full send.
 *
 * The ledger is keyed on the raw byte payload; a fire-and-forget keystroke send with
 * no matching durable queue row records only a volatile attempt that is cleared on
 * success (net zero — no durable pollution), exactly like the agent lane's transient
 * rows. The needle-count baseline (#1577) keeps short payloads safe from a
 * false-positive `AlreadyLanded` drop.
 */
internal suspend fun deliverRawInputWithGuard(
    ledger: OutboundDeliveryLedger,
    client: TmuxClient,
    paneId: String,
    bytes: ByteArray,
    localRenderText: String,
    send: suspend (TmuxClient, String, ByteArray) -> Unit,
    submitEnter: suspend (TmuxClient, String) -> Unit,
    afterDelivered: suspend (TmuxClient, String, ByteArray) -> Unit,
): Result<Unit> {
    // Key the ledger on the payload WITHOUT its trailing submit CR/LF: the composer
    // enqueues the durable row's `cleanText` without the CR it appends on the wire,
    // so trimming aligns the (pane, payload) key with the durable `wireAttempted`
    // row — the same VM-clear/back-nav durability the agent lane gets (#1541). The
    // full [bytes] (CR included) are still what [send] pushes. Pure control/Enter
    // input trims to empty — nothing meaningful to probe/dedupe, so it does a plain
    // error-checked send (H1a still applies via [send]).
    val fullText = String(bytes, Charsets.UTF_8)
    val payload = fullText.trimEnd('\r', '\n')
    if (payload.isEmpty()) {
        return runCatching {
            send(client, paneId, bytes)
            afterDelivered(client, paneId, bytes)
        }
    }
    when (verifyBeforeAgentResend(ledger, client, paneId, payload)) {
        DeliveryProbeOutcome.AlreadyLanded -> return runCatching {
            // Issue #1586 (H1b): the payload TEXT landed but the ambiguous cut may have
            // dropped its terminating submit — do NOT drop the delivery silently.
            // Mirror the agent lane: submit Enter-ONLY (never the payload text, so no
            // duplicate), and only when the original send actually carried a submit (a
            // trailing CR/LF); a non-submit send has nothing to complete, so no spurious
            // Enter is injected.
            if (fullText.length > payload.length) {
                submitEnter(client, paneId)
            }
            ledger.clear(paneId, payload)
            afterDelivered(client, paneId, bytes)
        }
        DeliveryProbeOutcome.Unknown -> return Result.failure(
            IllegalStateException("Prior shell send outcome unknown; kept queued without resend."),
        )
        DeliveryProbeOutcome.NotLanded, null -> Unit
    }
    return runCatching {
        ledger.recordWireAttemptWithBaseline(client, paneId, payload, localRenderText)
        send(client, paneId, bytes)
        ledger.clear(paneId, payload)
        afterDelivered(client, paneId, bytes)
    }
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
 *
 * ## Durability across VM-clear (issue #1541)
 *
 * The volatile in-memory set dies with the VM — which happens on a plain
 * **back-navigation** (VM clear), not only on process death. That let the P9
 * duplicate through: send → tap Back mid-delivery → the row is deferred → reopen
 * → the FRESH ledger has no memory of the in-flight attempt → the row is blindly
 * re-pasted → server occurrence 2. [durable] closes that: a wire attempt is
 * ALSO recorded on the durable [OutboundQueueStore][com.pocketshell.app.composer.OutboundQueueStore]
 * row (`wireAttempted`), so a ledger rebuilt after a VM-clear consults the durable
 * flag ([hasAmbiguousAttempt]) and runs verify-before-resend instead of a blind
 * re-paste. Null [durable] (unit-test constructors) ⇒ in-memory only (base S1).
 */
internal class OutboundDeliveryLedger(
    private val maxEntries: Int = 64,
    private val durable: OutboundWireAttemptDurableStore? = null,
) {
    private val lock = Any()
    private val entries = LinkedHashSet<String>()

    // Issue #1577: the pre-send needle occurrence count captured with the FIRST
    // wire attempt, keyed the same way. Bounded alongside [entries]. Backed durably
    // so a VM-clear-rebuilt ledger reads it back (via [needleBaseline]).
    private val baselines = HashMap<String, Int>()

    private fun key(paneId: String, payload: String): String =
        "$paneId|${payload.length}|${payload.hashCode()}"

    fun recordWireAttempt(paneId: String, payload: String, baselineCount: Int? = null): Unit = synchronized(lock) {
        val key = key(paneId, payload)
        entries.remove(key)
        entries.add(key)
        // Issue #1577: keep the FIRST captured baseline (idempotent) — a re-push after
        // a NotLanded probe must not overwrite the one pre-first-paste snapshot.
        if (baselineCount != null && key !in baselines) {
            baselines[key] = baselineCount
        }
        while (entries.size > maxEntries) {
            val evicted = entries.first()
            entries.remove(evicted)
            baselines.remove(evicted)
        }
        // Issue #1541: also persist on the durable row so the attempt survives a
        // VM-clear / back-navigation, not only this live VM. Issue #1577: the
        // baseline rides the same durable row.
        durable?.recordWireAttempt(paneId, payload, System.currentTimeMillis(), baselineCount)
    }

    fun clear(paneId: String, payload: String): Unit = synchronized(lock) {
        // Only clears the volatile set: the durable flag is tied to the row's
        // lifetime (dropped when the row is delivered-pruned / removed, PRESERVED
        // across requeue), so an as-yet-un-acked in-flight row keeps verifying.
        val key = key(paneId, payload)
        entries.remove(key)
        baselines.remove(key)
    }

    fun hasAmbiguousAttempt(paneId: String, payload: String): Boolean = synchronized(lock) {
        // Issue #1541: the durable flag makes a back-nav-rebuilt ledger (empty
        // in-memory set) still see a prior wire attempt.
        key(paneId, payload) in entries || durable?.hasWireAttempt(paneId, payload) == true
    }

    /**
     * Issue #1577: the pre-send needle baseline for (pane, payload) — the volatile
     * value first, else the durable one (a VM-clear-rebuilt ledger). `null` when
     * none was captured; the caller then falls back to presence-only verification.
     */
    fun needleBaseline(paneId: String, payload: String): Int? = synchronized(lock) {
        baselines[key(paneId, payload)] ?: durable?.wireNeedleBaseline(paneId, payload)
    }
}

/**
 * Issue #1541: build the production [OutboundDeliveryLedger] with its durable
 * wire-attempt backing wired to the persisted outbound queue rows. A fresh
 * [SharedPrefsOutboundQueueStore][com.pocketshell.app.composer.SharedPrefsOutboundQueueStore]
 * shares the process-cached `outbound_queue` SharedPreferences with the composer's
 * singleton, so the `wireAttempted` flag written on one instance is visible to the
 * ledger rebuilt on the next VM — surviving VM-clear / back-navigation. A null
 * [context] (narrow unit-test VM constructors) falls back to the in-memory-only
 * ledger (base S1 behaviour).
 */
internal fun outboundDeliveryLedgerFor(context: android.content.Context?): OutboundDeliveryLedger =
    OutboundDeliveryLedger(
        durable = context?.let { SharedPrefsOutboundQueueStore(it).asWireAttemptDurableStore() },
    )

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
    baselineCount: Int? = null,
): DeliveryProbeOutcome {
    val needle = agentSubmitAckNeedle(payload) ?: return DeliveryProbeOutcome.Unknown
    // Issue #1577b: kill the presence-only fallback. A null baseline cannot tell "our
    // paste landed" from a payload that was ALREADY on the pane (a Codex `Goal blocked
    // (/goal resume)` footer), so a presence match would false-`AlreadyLanded` → a
    // silent bare-Enter drop. Resolve `NotLanded` so the caller does a REAL gated
    // resend (which records a fresh baseline), never a bare Enter — deliver-safe.
    if (baselineCount == null) return DeliveryProbeOutcome.NotLanded
    val response = try {
        client.capturePaneTextViaExec(paneId, timeoutMs = OUTBOUND_DELIVERY_PROBE_TIMEOUT_MS)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        return DeliveryProbeOutcome.Unknown
    }
    if (response.isError) return DeliveryProbeOutcome.Unknown
    // Issue #1577: a captured baseline demands the count INCREASE (our paste landed).
    val count = agentSubmitVisibleTextNeedleCount(response.output, needle)
    return if (count > baselineCount) DeliveryProbeOutcome.AlreadyLanded else DeliveryProbeOutcome.NotLanded
}

/**
 * Issue #1577: capture the pane's CURRENT needle occurrence count (via the exec
 * lane, the same lane the probe uses) so a wire attempt can be recorded WITH a
 * pre-send baseline. Returns `null` when the needle is underivable or the capture
 * failed — the caller records the attempt with a null baseline and the later probe
 * falls back to presence-only.
 */
internal suspend fun captureNeedleBaseline(
    client: TmuxClient,
    paneId: String,
    payload: String,
): Int? {
    val needle = agentSubmitAckNeedle(payload) ?: return null
    val response = try {
        client.capturePaneTextViaExec(paneId, timeoutMs = OUTBOUND_DELIVERY_PROBE_TIMEOUT_MS)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        return null
    }
    if (response.isError) return null
    return agentSubmitVisibleTextNeedleCount(response.output, needle)
}

/**
 * Issue #1577: record a wire attempt for (pane, payload) AND, on the FIRST push
 * (no baseline yet), record the pre-send needle occurrence count as the baseline
 * the later verify-before-resend compares against. Captured once — a re-push after
 * a NotLanded probe keeps the original baseline (idempotent in [recordWireAttempt]).
 *
 * Cost-aware: [localRenderText] is the pane's ALREADY-rendered visible text (a free,
 * in-memory read — no round-trip). When the payload's needle is NOT already on the
 * local render (the overwhelmingly common case — a fresh prompt), the baseline is 0
 * and NO capture is issued: a later probe finding the needle unambiguously means our
 * paste landed, so presence-only is correct and the hot send path pays nothing. Only
 * when the payload text is ALREADY visible (e.g. a Codex `Goal blocked (/goal resume)`
 * status line — the exact #1577 false-positive) is ONE bounded authoritative capture
 * taken to record the precise pre-send count, so a genuine resend requires the count
 * to INCREASE and is not falsely `AlreadyLanded`.
 */
internal suspend fun OutboundDeliveryLedger.recordWireAttemptWithBaseline(
    client: TmuxClient,
    paneId: String,
    payload: String,
    localRenderText: String,
) {
    if (needleBaseline(paneId, payload) != null) {
        // A prior push already captured the pre-send baseline; keep it (idempotent).
        recordWireAttempt(paneId, payload, null)
        return
    }
    val needle = agentSubmitAckNeedle(payload)
    val alreadyVisible = needle != null &&
        agentSubmitVisibleTextNeedleCount(listOf(localRenderText), needle) > 0
    val baseline = if (alreadyVisible) captureNeedleBaseline(client, paneId, payload) else 0
    recordWireAttempt(paneId, payload, baseline)
}

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
