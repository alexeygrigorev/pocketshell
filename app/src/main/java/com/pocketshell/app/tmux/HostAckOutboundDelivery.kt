package com.pocketshell.app.tmux

import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.app.pocketshell.PocketshellCommand
import com.pocketshell.core.ssh.ExecResult
import java.util.concurrent.atomic.AtomicLong

/**
 * Issue #2124 (step 1b of epic #2121) — the ACKNOWLEDGED outbound delivery lane.
 *
 * ## What changed, in one sentence
 *
 * Delivery used to be *inferred* from bounded-time terminal observation (a
 * paste-ack needle within 800 ms, an agent-turnover proof within 800 ms/2 s, a
 * six-poll late-ack reconciler). It is now *acknowledged*: the app pipes the
 * payload into `pocketshell send --pane <pane> --token <rowId> [--enter]` over
 * the warm lease, and **that exec's exit status IS the answer** (#2122). One
 * round trip, no windows, no needles, no screen scraping.
 *
 * ## The definition this file encodes
 *
 * **`Delivered` = the host CLI acknowledged injection into the exact pane.**
 * Acceptance — whether the agent produced a turn — is no longer part of a row's
 * lifecycle (it stays an opportunistic checkmark in the Conversation view). The
 * row state machine is therefore `Queued → InFlight → Delivered | Failed` and
 * `OutboundItem.wireOutcomeUnknown` is UNREACHABLE on this path: a timed-out or
 * failed exec is a plain retryable `Failed`, safe to retry because the token
 * makes the host side idempotent.
 *
 * ## Reading the exit table correctly (issue #2136 — this is subtle)
 *
 * #2136 corrected the CLI's own table and the correction lands here:
 *
 *  - **exit 4 (`tmux-failed`) means "this call put NOTHING into the pane"** —
 *    the boundary is the *paste*, not the command. It does NOT mean "the token
 *    is free": under `--resend-interrupted` a rolled-back pre-existing record is
 *    restored byte-for-byte, so the journal is *unchanged*, not *empty*. "A
 *    retry cannot duplicate" holds; "a retry will inject" does not.
 *  - **This client NEVER passes `--resend-interrupted`.** That is the single
 *    decision that keeps every retry safe: the duplicate-injection path #2136's
 *    reproduction exercises is only reachable by opting in, and we never do.
 *    A plain retry either delivers, reports `already-delivered`, or reports an
 *    honest failure — it can never paste twice.
 *  - **exit 5 (`send-interrupted`) is not a new "unknown" state.** It is a
 *    terminal, honest `Failed` naming a partial delivery (payload in the pane,
 *    Enter not delivered / delivery genuinely unknown). The row stays a normal
 *    retryable `Failed` row the user can retry or discard; every retry is a real
 *    host round trip that returns a real answer. It is deliberately NOT mapped
 *    to anything the auto-flush skips — that is exactly the absorbing state this
 *    epic exists to delete.
 *  - **exit 8 (`send-in-progress`) is retryable and bounded by owner liveness.**
 *    Nothing is unknown: a live sibling owns the outcome. Retry reads it.
 *
 * ## Runtime lockstep — fail CLOSED, never hang, never invent a state
 *
 * Host-CLI/client version lockstep is a RUNTIME dependency and it broke a
 * release before (v0.4.10, #847). So:
 *
 *  - the exec is timeout-bounded by [HOST_ACK_SEND_EXEC_TIMEOUT_MS] and rides
 *    [com.pocketshell.app.ssh.BoundedSessionExec], which ABANDONS a slow exec
 *    and never closes the shared lease transport;
 *  - an **old host CLI without `send`** (Click's `Error: No such command
 *    'send'.` → exit 2 with an empty stdout reason) and a **genuinely absent**
 *    `pocketshell` (the [PocketshellCommand.wrap] resolver's exit 127) both map
 *    to a plain retryable `Failed` carrying [HOST_CLI_UPGRADE_HINT];
 *  - nothing on this path can produce a hang or a new "unknown" row state.
 *
 * ## The stdin-pipe trap (issue #847)
 *
 * [PocketshellCommand.wrap] returns a MULTI-statement shell sequence. A bare
 * `printf … | <wrap>` binds the pipe to wrap()'s FIRST statement, so the real
 * CLI inherits the SSH exec channel's stdin and blocks on `read()` forever. The
 * wrapper is grouped in `{ …; }` here for exactly that reason — it is the same
 * bug that hung v0.4.10's connect, and it only bites when `pocketshell` lives in
 * `~/.local/bin` (where wrap() is multi-statement), which is why Docker masked it.
 */

/** Total budget for the one acknowledged-send exec round trip. */
internal const val HOST_ACK_SEND_EXEC_TIMEOUT_MS: Long = 15_000L

/**
 * The `--timeout` handed to the CLI for its own tmux calls. Deliberately BELOW
 * [HOST_ACK_SEND_EXEC_TIMEOUT_MS] so the host answers `timeout` (exit 6) — a
 * real, attributable acknowledgement — before our transport-level bound gives up
 * with no answer at all.
 */
internal const val HOST_ACK_SEND_CLI_TIMEOUT_SECONDS: Int = 10

/** Bounded-exec attribution token for the acknowledged send. */
internal const val HOST_ACK_SEND_CALLER_SITE: String = "outbound_host_ack_send"

/** Actionable message for a host whose `pocketshell` is missing or too old. */
internal const val HOST_CLI_UPGRADE_HINT: String =
    "This host's pocketshell CLI has no 'send' command. Upgrade it " +
        "(uv tool install --force pocketshell), then retry."

// --------------------------------------------------------------------------
// Exit codes — mirrored from the CLI's EXIT_CODE_TABLE (tools/pocketshell/
// src/pocketshell/send.py). Pinned against the CLI's own rendered `--help` by
// HostAckOutboundDeliveryTest so a transcribed copy cannot drift (#2136 §3).
// --------------------------------------------------------------------------

internal const val HOST_ACK_EXIT_OK: Int = 0
internal const val HOST_ACK_EXIT_BAD_USAGE: Int = 2
internal const val HOST_ACK_EXIT_PANE_NOT_FOUND: Int = 3
internal const val HOST_ACK_EXIT_TMUX_FAILED: Int = 4
internal const val HOST_ACK_EXIT_SEND_INTERRUPTED: Int = 5
internal const val HOST_ACK_EXIT_TIMEOUT: Int = 6
internal const val HOST_ACK_EXIT_JOURNAL_FAILED: Int = 7
internal const val HOST_ACK_EXIT_SEND_IN_PROGRESS: Int = 8

/** [PocketshellCommand.wrap]'s "no pocketshell binary anywhere" exit. */
internal const val HOST_ACK_EXIT_CLI_ABSENT: Int = 127

internal const val HOST_ACK_REASON_DELIVERED: String = "delivered"
internal const val HOST_ACK_REASON_ALREADY_DELIVERED: String = "already-delivered"
internal const val HOST_ACK_REASON_BAD_USAGE: String = "bad-usage"

/**
 * The verdict of ONE acknowledged send. There is no `Unknown` member, and that
 * absence is the whole point of the epic — see the file header.
 */
internal sealed interface HostAckSendOutcome {
    /** The host acknowledged injection into the exact pane by THIS call. */
    data object Delivered : HostAckSendOutcome

    /** The token was already journaled — exactly one occurrence exists. */
    data object AlreadyDelivered : HostAckSendOutcome

    /**
     * An honest, retryable failure. [reason] is the CLI's machine-readable
     * stdout token (or a synthesised one when the CLI never answered);
     * [message] is what the row surfaces.
     */
    data class Failed(val reason: String, val message: String) : HostAckSendOutcome

    val delivered: Boolean
        get() = this is Delivered || this is AlreadyDelivered
}

/** Synthesised reasons for answers that did not come from the CLI's table. */
internal const val HOST_ACK_REASON_NO_ANSWER: String = "no-answer"
internal const val HOST_ACK_REASON_CLI_TOO_OLD: String = "host-cli-too-old"

/**
 * Build the single `/bin/sh` command that pipes [payload] into
 * `pocketshell send`.
 *
 * The payload travels through `printf %s '<quoted>'`: `%s` is the FORMAT and the
 * payload is a separate argument, so a `%` inside the payload is never
 * interpreted, and single-quote escaping round-trips arbitrary UTF-8 text.
 * `printf` (not `echo`) because `echo` appends a newline and some builtins
 * interpret backslashes — either would corrupt a payload the agent then reads.
 *
 * The `{ … ; }` group around [PocketshellCommand.wrap] is load-bearing — see the
 * file header's stdin-pipe trap.
 */
internal fun buildHostAckSendCommand(
    paneId: String,
    token: String,
    payload: String,
    withEnter: Boolean,
    cliTimeoutSeconds: Int = HOST_ACK_SEND_CLI_TIMEOUT_SECONDS,
): String {
    val args = buildString {
        append("send --pane ")
        append(hostAckShellQuote(paneId))
        append(" --token ")
        append(hostAckShellQuote(token))
        if (withEnter) append(" --enter")
        append(" --timeout ")
        append(cliTimeoutSeconds)
    }
    return "printf %s " + hostAckShellQuote(payload) + " | { " + PocketshellCommand.wrap(args) + " ; }"
}

internal fun hostAckShellQuote(value: String): String =
    "'" + value.replace("'", "'\"'\"'") + "'"

/**
 * Classify ONE `pocketshell send` exec result.
 *
 * [result] is null when the bounded exec never produced an answer (the abandoned
 * slow exec of [com.pocketshell.app.ssh.BoundedSessionExec]) — an honest
 * retryable failure, never a hang and never an "unknown" row state.
 */
internal fun classifyHostAckSend(result: ExecResult?): HostAckSendOutcome {
    if (result == null) {
        return HostAckSendOutcome.Failed(
            reason = HOST_ACK_REASON_NO_ANSWER,
            message = "The host did not answer the send within " +
                "${HOST_ACK_SEND_EXEC_TIMEOUT_MS / 1000}s. Retry.",
        )
    }
    val reason = result.stdout.trim().substringBefore('\n').trim().substringBefore(' ')
    val detail = result.stderr.trim().ifBlank { result.stdout.trim() }
    return when (result.exitCode) {
        HOST_ACK_EXIT_OK -> when (reason) {
            HOST_ACK_REASON_ALREADY_DELIVERED -> HostAckSendOutcome.AlreadyDelivered
            // `delivered` is the expected token; any other exit-0 answer is
            // still an acknowledgement from the CLI, so it is not laundered
            // into a failure (that would strand a delivered payload).
            else -> HostAckSendOutcome.Delivered
        }
        // Issue #847 lockstep: an OLD CLI has no `send` subcommand. Click
        // rejects it on STDERR ("Error: No such command 'send'.") and exits 2,
        // leaving stdout empty — whereas the real command's own usage errors
        // print `bad-usage` on stdout. That is the discriminator; both are a
        // plain retryable Failed, but only the mismatch names the upgrade.
        HOST_ACK_EXIT_BAD_USAGE -> if (reason == HOST_ACK_REASON_BAD_USAGE) {
            HostAckSendOutcome.Failed(HOST_ACK_REASON_BAD_USAGE, detail.ifBlank { "Send rejected by the host." })
        } else {
            HostAckSendOutcome.Failed(HOST_ACK_REASON_CLI_TOO_OLD, HOST_CLI_UPGRADE_HINT)
        }
        HOST_ACK_EXIT_CLI_ABSENT -> HostAckSendOutcome.Failed(
            HOST_ACK_REASON_CLI_TOO_OLD,
            HOST_CLI_UPGRADE_HINT,
        )
        HOST_ACK_EXIT_PANE_NOT_FOUND,
        HOST_ACK_EXIT_TMUX_FAILED,
        HOST_ACK_EXIT_SEND_INTERRUPTED,
        HOST_ACK_EXIT_TIMEOUT,
        HOST_ACK_EXIT_JOURNAL_FAILED,
        HOST_ACK_EXIT_SEND_IN_PROGRESS,
        -> HostAckSendOutcome.Failed(
            reason.ifBlank { "exit-${result.exitCode}" },
            detail.ifBlank { "Send failed on the host (exit ${result.exitCode})." },
        )
        else -> HostAckSendOutcome.Failed(
            reason.ifBlank { "exit-${result.exitCode}" },
            detail.ifBlank { "Send failed on the host (exit ${result.exitCode})." },
        )
    }
}

/**
 * The one exec seam the acknowledged lane needs. Production binds it to the warm
 * lease's [com.pocketshell.core.ssh.SshSession] through
 * [com.pocketshell.app.ssh.BoundedSessionExec]; a unit test binds a fake that
 * answers with the CLI's documented exit codes.
 */
internal fun interface HostAckSendExec {
    /** Returns null when the bounded exec produced no answer within its budget. */
    suspend fun run(command: String, timeoutMs: Long): ExecResult?
}

/**
 * Bracketed-paste frame [payload] under EXACTLY the condition the legacy lanes
 * framed it, and never twice.
 *
 * Both replaced lanes framed, and both must keep framing — this is the #209 /
 * #529 property (a multi-line dictation transcript is ONE prompt, not one prompt
 * per line):
 *
 *  - the agent lane framed a multi-line or over-one-chunk payload before
 *    `set-buffer`/`paste-buffer` (`TmuxSessionViewModel`'s `payloadBytes.size >
 *    TMUX_PASTE_BODY_CHUNK_BYTES || containsLineBreak`);
 *  - the raw/shell lane framed through `deliverPaneInputBytes`, which passes an
 *    ALREADY-framed block straight through (#1854) and `sendBracketedPaste`s
 *    anything containing a line break (#209).
 *
 * The CLI deliberately does not frame (the client owns framing) and pastes with
 * `paste-buffer -r`, so newlines stay newlines on the wire: without this the
 * receiving readline executes a multi-line shell send line by line, which is
 * precisely the closed #209/#529 symptom.
 *
 * [com.pocketshell.core.terminal.input.BracketedPaste.isFramed] is the #1854
 * guard: `TerminalView` frames a multi-line IME commit before it reaches the
 * sink, and framing it a second time puts the inner `\e[200~` markers into the
 * receiving program as literal content.
 */
internal fun frameForWire(payload: String): String {
    if (payload.isEmpty()) return payload
    val bytes = payload.toByteArray(Charsets.UTF_8)
    if (com.pocketshell.core.terminal.input.BracketedPaste.isFramed(bytes)) return payload
    val needsFraming = bytes.size > TMUX_PASTE_BODY_CHUNK_BYTES ||
        com.pocketshell.core.terminal.input.BracketedPaste.containsLineBreak(bytes)
    if (!needsFraming) return payload
    return String(
        com.pocketshell.core.terminal.input.BracketedPaste.frame(bytes),
        Charsets.UTF_8,
    )
}

/**
 * Run ONE acknowledged send and translate it into the row-lifecycle answer.
 *
 * Success (delivered or already-delivered) is `Result.success`; every failure is
 * `Result.failure(`[HostAckSendFailedException]`)`, which
 * [com.pocketshell.app.tmux.toComposerSendResult] maps to
 * [com.pocketshell.app.composer.ComposerSendResult.Failed]. Nothing here can
 * produce [com.pocketshell.app.composer.ComposerSendResult.AuthoritativeAckPending],
 * which is what makes `wireOutcomeUnknown` unreachable on this path.
 */
internal suspend fun deliverViaHostAck(
    exec: HostAckSendExec,
    paneId: String,
    token: String,
    payload: String,
    withEnter: Boolean,
): Result<Unit> {
    val command = buildHostAckSendCommand(
        paneId = paneId,
        token = token,
        payload = payload,
        withEnter = withEnter,
    )
    val outcome = classifyHostAckSend(exec.run(command, HOST_ACK_SEND_EXEC_TIMEOUT_MS))
    DiagnosticEvents.record(
        "action",
        "outbound_host_ack_send",
        "pane" to paneId,
        "token" to token,
        "withEnter" to withEnter,
        "outcome" to when (outcome) {
            is HostAckSendOutcome.Delivered -> HOST_ACK_REASON_DELIVERED
            is HostAckSendOutcome.AlreadyDelivered -> HOST_ACK_REASON_ALREADY_DELIVERED
            is HostAckSendOutcome.Failed -> outcome.reason
        },
    )
    return if (outcome.delivered) {
        Result.success(Unit)
    } else {
        Result.failure(HostAckSendFailedException(outcome as HostAckSendOutcome.Failed))
    }
}

/**
 * An honest, retryable delivery failure. Deliberately NOT an
 * [AgentSubmitTurnoverNotProvenException]: that type is what
 * [toComposerSendResult] turns into `AuthoritativeAckPending`, i.e. the
 * "unconfirmed" absorbing state. On the acknowledged path there is no such
 * state, so a failure must never be able to reach it.
 */
internal class HostAckSendFailedException(
    val outcome: HostAckSendOutcome.Failed,
) : IllegalStateException(outcome.message)

/**
 * Issue #2124 — the ACKNOWLEDGED-lane counter, the mirror of
 * [OutboundLegacyStackProbe].
 *
 * The hard switch is only checkable if BOTH sides are observable. A connected
 * journey pinned to [com.pocketshell.app.settings.OutboundDeliveryAuthority.TerminalInference]
 * asserts this is ZERO — which is exactly the signal that exposed the round-1
 * authority pin as a silent no-op (the legacy-pinned classes had run four
 * acknowledged sends) — and the shipped-default journey asserts it is non-zero.
 *
 * Incremented once per acknowledged-lane delivery ATTEMPT, at the very top of
 * [HostAckDeliveryPort.deliver] — before the transport guard and before the
 * exec, so the answer, the transport state, and the exit code are all
 * irrelevant to the count. Counting the exec instead (the round-2 shape) left a
 * blind spot: a send that entered this lane while the session was down never
 * counted, so the class that deliberately cuts the link could read zero for a
 * reason other than "the legacy path owned it".
 */
internal object HostAckSendProbe {
    val sends: AtomicLong = AtomicLong(0)

    fun reset() = sends.set(0)

    fun count(): Long = sends.get()
}

/**
 * Issue #2124 — the HARD-SWITCH probe.
 *
 * The flag is only meaningful if "the old inference stack is not consulted at
 * all" is *checkable*, so every entry point of that stack increments a counter
 * here. A test running the acknowledged path asserts these are all ZERO; a test
 * running the legacy path asserts they are non-zero (so the probe itself cannot
 * pass vacuously by being wired to nothing).
 *
 * Cost is one uncontended atomic increment on a path that already does an SSH
 * round trip. #2125 deletes this object together with the stack it watches.
 */
internal object OutboundLegacyStackProbe {
    /** `verifyBeforeAgentResend` — the verify-before-resend ledger probe. */
    val verifyBeforeResend: AtomicLong = AtomicLong(0)

    /** `awaitAgentPasteIngested` — the 800 ms paste-ack needle gate. */
    val pasteAck: AtomicLong = AtomicLong(0)

    /** `AgentTranscriptAuthority.awaitTurnover` — the turnover proof. */
    val turnoverProof: AtomicLong = AtomicLong(0)

    /** `resolveLateOutboundAck` — the six-poll late-ack reconciler. */
    val lateAckReconciler: AtomicLong = AtomicLong(0)

    /** `OutboundDeliveryLedger.recordWireAttempt` — the ambiguity ledger write. */
    val ledgerWireAttempt: AtomicLong = AtomicLong(0)

    /** `persistSubmitWriteAhead` — the pre-Enter write-ahead barrier. */
    val submitWriteAhead: AtomicLong = AtomicLong(0)

    private val all: List<AtomicLong>
        get() = listOf(
            verifyBeforeResend,
            pasteAck,
            turnoverProof,
            lateAckReconciler,
            ledgerWireAttempt,
            submitWriteAhead,
        )

    fun reset() = all.forEach { it.set(0) }

    fun total(): Long = all.sumOf { it.get() }

    /** Human-readable breakdown for an assertion message. */
    fun snapshot(): String =
        "verifyBeforeResend=${verifyBeforeResend.get()} " +
            "pasteAck=${pasteAck.get()} " +
            "turnoverProof=${turnoverProof.get()} " +
            "lateAckReconciler=${lateAckReconciler.get()} " +
            "ledgerWireAttempt=${ledgerWireAttempt.get()} " +
            "submitWriteAhead=${submitWriteAhead.get()}"
}

/**
 * Issue #2124 — the ViewModel-side PORT for the acknowledged lane.
 *
 * It exists so `TmuxSessionViewModel` (a hygiene-ratcheted god object, D28)
 * carries only the two branch points and the wiring, while the whole
 * acknowledged path — flag resolution, framing, exec, classification — lives
 * here and can be deleted or promoted wholesale by #2125.
 *
 * [active] is the HARD SWITCH. When it is true the legacy stack is not called,
 * not consulted, and cannot move a row; when it is false this port never runs
 * and behaviour is byte-for-byte the pre-#2124 path.
 */
internal class HostAckDeliveryPort(
    private val liveSession: () -> com.pocketshell.core.ssh.SshSession?,
    private val execDispatcher: () -> kotlinx.coroutines.CoroutineDispatcher,
    private val onDelivered: (paneId: String) -> Unit,
    private val configuredAuthority: () -> com.pocketshell.app.settings.OutboundDeliveryAuthority?,
) {
    /**
     * Deterministic unit-test override of the flag. Production never sets it, so
     * the effective value is the persisted setting (default: the acknowledged
     * path). Tests set it EXPLICITLY on both sides so neither path is exercised
     * by accident.
     */
    @Volatile
    @androidx.annotation.VisibleForTesting
    internal var authorityOverrideForTest: com.pocketshell.app.settings.OutboundDeliveryAuthority? = null

    /**
     * Deterministic unit-test override of the host exec. Production binds the
     * warm lease through [com.pocketshell.app.ssh.BoundedSessionExec]; a test
     * binds a fake answering with the CLI's documented exit codes, including the
     * OLD-CLI shape (`Error: No such command 'send'.` on stderr, exit 2).
     */
    @Volatile
    @androidx.annotation.VisibleForTesting
    internal var execOverrideForTest: HostAckSendExec? = null

    val authority: com.pocketshell.app.settings.OutboundDeliveryAuthority
        get() = authorityOverrideForTest
            ?: configuredAuthority()
            ?: com.pocketshell.app.settings.AppSettings.DEFAULT_OUTBOUND_DELIVERY_AUTHORITY

    /** True when the host-CLI acknowledgement owns row lifecycle. */
    val active: Boolean
        get() = authority == com.pocketshell.app.settings.OutboundDeliveryAuthority.HostCliAck

    private suspend fun exec(command: String, timeoutMs: Long): ExecResult? {
        execOverrideForTest?.let { return it.run(command, timeoutMs) }
        val session = liveSession()?.takeIf { it.isConnected } ?: return null
        return com.pocketshell.app.ssh.BoundedSessionExec.execBounded(
            session = session,
            command = command,
            timeoutMs = timeoutMs,
            dispatcher = execDispatcher(),
            callerSite = HOST_ACK_SEND_CALLER_SITE,
        )
    }

    /**
     * Deliver one AGENT payload through the host-CLI acknowledgement.
     *
     * The payload is bracketed-paste framed under exactly the condition the
     * legacy lane framed it (multi-line or larger than one paste chunk), because
     * the CLI deliberately does NOT frame — the client owns framing, and framing
     * twice put the literal `\e[200~` markers into the receiving program (#1854).
     */
    suspend fun sendAgentPayload(paneId: String, payload: String, sendToken: String): Result<Unit> =
        deliver(paneId, sendToken, frameForWire(payload), withEnter = true)

    /**
     * Deliver one composer-owned RAW-BYTES (shell route) send, or `null` when
     * this port does not own the call.
     *
     * `null` on [durableRow] == null is deliberate and narrow: a keystroke sent
     * straight from the terminal keyboard has no durable row and therefore no
     * row lifecycle for an acknowledgement to own — routing every arrow key
     * through an SSH exec of a Python CLI would be a latency disaster and would
     * mangle control bytes. Every ROW-OWNING send goes through the ack.
     */
    suspend fun sendRawBytesOrNull(
        paneId: String,
        bytes: ByteArray,
        sendToken: String,
        durableRow: DurableOutboundRowIdentity?,
    ): Result<Unit>? {
        if (!active || durableRow == null) return null
        val full = String(bytes, Charsets.UTF_8)
        val payload = full.trimEnd('\r', '\n')
        // A submit-ONLY send on a durable row (bytes == "\r") trims to an empty
        // payload. It must still ride the acknowledgement: returning null here
        // dropped the row through the elvis into the LEGACY stack, which is the
        // one hole in the "zero legacy calls with the flag on new" contract. The
        // CLI accepts an empty payload WITH `--enter` (it rejects empty WITHOUT
        // one as `bad-usage`, "would inject nothing"), so the Enter-only submit
        // is a normal acknowledged send. `writeInputToPaneResult` already
        // returns early for genuinely empty bytes, so `payload.isEmpty() &&
        // !withEnter` is unreachable from production — it is still handled as an
        // acknowledged no-op rather than silently handing the row to the old
        // stack.
        val withEnter = full.length > payload.length
        if (payload.isEmpty() && !withEnter) return Result.success(Unit)
        return deliver(paneId, sendToken, frameForWire(payload), withEnter = withEnter)
    }

    private suspend fun deliver(
        paneId: String,
        sendToken: String,
        payload: String,
        withEnter: Boolean,
    ): Result<Unit> {
        // Issue #2124 (reviewer round 2): count the ATTEMPT, not the exec, and
        // count it BEFORE the transport guard. The probe is the safety net that
        // stops `assertNoAcknowledgedSendsWereRecorded`'s zero from silently
        // meaning "nothing happened"; with the increment below the guard, a send
        // that entered this lane while the session was down was invisible to it,
        // so a broken authority pin PLUS a dead transport could still read zero
        // in exactly the class (the flap journey) that cuts the link on purpose.
        HostAckSendProbe.sends.incrementAndGet()
        if (liveSession() == null && execOverrideForTest == null) {
            return Result.failure(
                HostAckSendFailedException(
                    HostAckSendOutcome.Failed("no-transport", "Session is disconnected."),
                ),
            )
        }
        val result = deliverViaHostAck(
            exec = { command, timeoutMs -> exec(command, timeoutMs) },
            paneId = paneId,
            token = sendToken,
            payload = payload,
            withEnter = withEnter,
        )
        // Issue #941/#1353 R4: after a submit a full-screen agent TUI can
        // overpaint the pane partial-black; the guarded heal re-checks and
        // re-seeds. That is a RENDER concern, not delivery inference, so it is
        // preserved verbatim on the acknowledged path.
        if (result.isSuccess) onDelivered(paneId)
        return result
    }
}
