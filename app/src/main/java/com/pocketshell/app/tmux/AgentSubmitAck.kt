package com.pocketshell.app.tmux

import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.tmux.CommandResponse
import com.pocketshell.core.tmux.TmuxClient
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

/** Identity-complete observation exposed only to the #1739 connected-test hook. */
internal data class AgentSubmitCaptureObservation(
    val captureId: Long,
    val response: CommandResponse,
    val scrollbackLines: Int,
    val timeoutMs: Long,
    val paneId: String,
    val clientHash: Int,
    val generation: Long,
    val session: String,
)

/**
 * Issue #1739 synthetic-injection seam for connected emulator proof.
 *
 * The hook runs only AFTER a real `capture-pane` response returns from the real
 * sshj/tmux transport. Tests can then model the production cancellation-cleanup
 * wedge without replacing the transport or its pane evidence. [invokeAfterRealCapture]
 * pins the synthetic teardown to [Dispatchers.IO], matching the real
 * `RealSshSession.exec` cancellation path: channel-close writes run on the
 * dedicated `TransportDispatcher` worker while the cancelling coroutine waits
 * off Main. A test must never park this seam on Android Main, because that would
 * manufacture an ANR which the real transport path does not have. Null in
 * production; one-shot ownership/reset belongs to the test.
 */
internal object AgentSubmitCaptureSeams {
    private val captureIds = AtomicLong()

    @Volatile
    var afterRealCapture:
        (suspend (observation: AgentSubmitCaptureObservation) -> Unit)? = null

    /**
     * Mutation oracle for the original #1739 defect. When armed by the hard
     * integration gate, the capture becomes a structured child of the bounded
     * caller; its non-cancellable transport cleanup can therefore hold the
     * caller past the deadline. Production and ordinary tests leave this false.
     */
    @Volatile
    var structuredChildMutation: Boolean = false

    suspend fun invokeAfterRealCapture(observation: AgentSubmitCaptureObservation) {
        val hook = afterRealCapture ?: return
        withContext(Dispatchers.IO) {
            hook(observation)
        }
    }

    fun nextCaptureId(): Long = captureIds.incrementAndGet()

    fun reset() {
        afterRealCapture = null
        structuredChildMutation = false
    }
}

/**
 * Issue #869: ack-gate the submit Enter on the pasted composer text actually
 * landing in the agent's input, rather than relying only on a blind fixed sleep.
 */
internal const val AGENT_SUBMIT_ACK_POLL_INTERVAL_MS: Long = 40L

/**
 * Issue #1687: the ack poll window. Was 2s — but a collapsed multi-line paste
 * (see [agentSubmitCollapsedPasteMarkerCount]) used to MISS the needle on every
 * send, so every normal multi-line message paid the FULL 2s window plus the
 * fallback floor before the submit Enter ("takes too long to send even when the
 * connection is okay"). With the collapsed-paste chip now recognised the ack
 * fires in ~1 RTT on the happy path. Issue #1739 makes the 800ms window a hard
 * no-blind-Enter boundary: a genuinely unrecognised render remains retryable,
 * and verify-before-resend later decides whether Enter-only is safe.
 */
internal const val AGENT_SUBMIT_ACK_TIMEOUT_MS: Long = 800L

/**
 * Issue #1944: after tmux accepts Enter, wait for the exact pane to render a
 * different frame before declaring the durable row delivered. A successful
 * `send-keys Enter` only acknowledges tmux's command parser; it does not prove
 * the agent consumed the submit or reopened its input loop.
 */
internal const val AGENT_SUBMIT_TURNOVER_TIMEOUT_MS: Long = 800L

/**
 * Issue #1944: an authoritative JSONL acknowledgement rides remote `tail -F`.
 * BusyBox tail polls file growth once per second, then the conversation bridge
 * batches for 60ms. Two seconds covers one full poll phase plus batching, SSH
 * RTT, and device scheduling. This larger ceiling is selected only when the
 * pane has a transcript authority candidate; generic screen turnover retains
 * the 800ms bound above.
 */
internal const val AGENT_TRANSCRIPT_TURNOVER_TIMEOUT_MS: Long = 2_000L

/**
 * The proof required after tmux accepts the submit Enter. Normal prompts must
 * advance an authoritative agent surface/transcript before their durable row is
 * pruned. Catalog-approved TUI-only controls intentionally create no transcript
 * turn; for those, the acknowledged tmux Enter is the terminal delivery proof
 * and the write-ahead submit ledger still prevents a blind resend after a crash.
 */
internal enum class AgentSubmitDeliveryProof {
    AgentTurnover,
    TmuxEnterAccepted,
}

/**
 * Issue #869: how many whitespace-stripped tail characters of the pasted prompt
 * the ack needle matches against `capture-pane`.
 */
internal const val AGENT_SUBMIT_ACK_NEEDLE_TAIL_CHARS: Int = 24

/** Immutable authority snapshot for one agent-send attempt. */
internal data class AgentSendRuntimeIdentity(
    val client: TmuxClient,
    val generation: Long,
    val target: TmuxSessionViewModel.ConnectionTarget?,
)

internal enum class AgentPaneCaptureStatus {
    Captured,
    TimedOut,
    Failed,
    Disconnected,
    StaleRuntime,
}

internal data class AgentPaneCaptureResult(
    val status: AgentPaneCaptureStatus,
    val response: CommandResponse? = null,
)

/**
 * Caller-bounded capture for #1739. The capture is a VM-scope sibling, so the
 * caller's deadline never joins a cancelled SSH exec's non-cancellable cleanup.
 */
internal suspend fun captureAgentPaneWithDeadline(
    scope: CoroutineScope,
    identity: AgentSendRuntimeIdentity,
    paneId: String,
    timeoutMs: Long,
    scrollbackLines: Int,
    isCurrent: (AgentSendRuntimeIdentity, String) -> Boolean,
    nowMs: () -> Long,
    currentClientHash: () -> Int?,
    currentGeneration: () -> Long,
): AgentPaneCaptureResult =
    if (AgentSubmitCaptureSeams.structuredChildMutation) {
        coroutineScope {
            captureAgentPaneWithDeadlineInScope(
                scope = this,
                identity = identity,
                paneId = paneId,
                timeoutMs = timeoutMs,
                scrollbackLines = scrollbackLines,
                isCurrent = isCurrent,
                nowMs = nowMs,
                currentClientHash = currentClientHash,
                currentGeneration = currentGeneration,
            )
        }
    } else {
        captureAgentPaneWithDeadlineInScope(
            scope = scope,
            identity = identity,
            paneId = paneId,
            timeoutMs = timeoutMs,
            scrollbackLines = scrollbackLines,
            isCurrent = isCurrent,
            nowMs = nowMs,
            currentClientHash = currentClientHash,
            currentGeneration = currentGeneration,
        )
    }

private suspend fun captureAgentPaneWithDeadlineInScope(
    scope: CoroutineScope,
    identity: AgentSendRuntimeIdentity,
    paneId: String,
    timeoutMs: Long,
    scrollbackLines: Int,
    isCurrent: (AgentSendRuntimeIdentity, String) -> Boolean,
    nowMs: () -> Long,
    currentClientHash: () -> Int?,
    currentGeneration: () -> Long,
): AgentPaneCaptureResult {
    if (!isCurrent(identity, paneId)) return AgentPaneCaptureResult(AgentPaneCaptureStatus.StaleRuntime)
    if (identity.client.disconnected.value) return AgentPaneCaptureResult(AgentPaneCaptureStatus.Disconnected)

    val startedAtMs = nowMs()
    val captureId = AgentSubmitCaptureSeams.nextCaptureId()
    DiagnosticEvents.record(
        "action",
        "agent_submit_capture",
        "captureId" to captureId,
        "pane" to paneId,
        "result" to "started",
        "clientHash" to System.identityHashCode(identity.client),
        "generation" to identity.generation,
        "session" to (identity.target?.sessionName ?: "test"),
        "timeoutMs" to timeoutMs,
        "scrollbackLines" to scrollbackLines,
    )
    val capture = scope.async(start = CoroutineStart.UNDISPATCHED) {
        identity.client.capturePaneTextViaExec(paneId, timeoutMs, scrollbackLines).also {
            AgentSubmitCaptureSeams.invokeAfterRealCapture(
                AgentSubmitCaptureObservation(
                    captureId = captureId,
                    response = it,
                    scrollbackLines = scrollbackLines,
                    timeoutMs = timeoutMs,
                    paneId = paneId,
                    clientHash = System.identityHashCode(identity.client),
                    generation = identity.generation,
                    session = identity.target?.sessionName ?: "test",
                ),
            )
        }
    }
    val result = try {
        val response = withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) { capture.await() }
        when {
            response == null -> AgentPaneCaptureResult(AgentPaneCaptureStatus.TimedOut)
            !isCurrent(identity, paneId) -> AgentPaneCaptureResult(AgentPaneCaptureStatus.StaleRuntime)
            identity.client.disconnected.value -> AgentPaneCaptureResult(AgentPaneCaptureStatus.Disconnected)
            else -> AgentPaneCaptureResult(AgentPaneCaptureStatus.Captured, response)
        }
    } catch (cancelled: CancellationException) {
        if (!currentCoroutineContext().isActive) throw cancelled
        AgentPaneCaptureResult(AgentPaneCaptureStatus.Failed)
    } catch (_: Throwable) {
        AgentPaneCaptureResult(AgentPaneCaptureStatus.Failed)
    } finally {
        if (!capture.isCompleted) {
            capture.cancel(CancellationException("agent submit capture deadline/scope ended"))
        }
    }
    DiagnosticEvents.record(
        "action",
        "agent_submit_capture",
        "captureId" to captureId,
        "pane" to paneId,
        "result" to result.status.name,
        "clientHash" to System.identityHashCode(identity.client),
        "currentClientHash" to currentClientHash(),
        "generation" to identity.generation,
        "currentGeneration" to currentGeneration(),
        "session" to (identity.target?.sessionName ?: "test"),
        "elapsedMs" to (nowMs() - startedAtMs),
    )
    return result
}

/**
 * Ack-gate Enter on proof from the exact current runtime. Any timeout, failed
 * capture, disconnect, or stale binding fails without blind Enter.
 */
internal suspend fun awaitAgentPasteIngested(
    identity: AgentSendRuntimeIdentity,
    paneId: String,
    payload: String,
    agent: AgentKind,
    configuredFloorMs: Long,
    ackTimeoutMs: Long,
    baselineNeedleCount: Int,
    collapsedMarkerBaseline: Int,
    capture: suspend (timeoutMs: Long, scrollbackLines: Int) -> AgentPaneCaptureResult,
    currentClientHash: () -> Int?,
    currentGeneration: () -> Long,
): CommandResponse {
    val floorMs = if (agent == AgentKind.Codex) {
        maxOf(configuredFloorMs, CODEX_AGENT_SUBMIT_DELAY_MS)
    } else {
        configuredFloorMs
    }
    val needle = agentSubmitAckNeedle(payload)
    if (needle == null) {
        if (floorMs > 0L) delay(floorMs)
        val pane = capture(ackTimeoutMs, 0)
        return pane.response?.takeIf {
            pane.status == AgentPaneCaptureStatus.Captured && !it.isError
        } ?: throw IllegalStateException("Agent paste acknowledgement frame was unavailable; kept queued.")
    }

    val multiline = agentSubmitPayloadIsMultiLine(payload)
    var polls = 0
    var terminalResult = "ack_timeout"
    var observedResponse: CommandResponse? = null
    val observed = withTimeoutOrNull(ackTimeoutMs.coerceAtLeast(1L)) {
        while (true) {
            val pane = capture(ackTimeoutMs, 0)
            when (pane.status) {
                AgentPaneCaptureStatus.Captured -> {
                    if (
                        pane.response?.let {
                            agentPaneShowsPayload(
                                it,
                                needle,
                                baselineNeedleCount,
                                multiline,
                                collapsedMarkerBaseline,
                            )
                        } == true
                    ) {
                        recordAgentSubmitAck(
                            identity = identity,
                            paneId = paneId,
                            result = "ack_observed",
                            polls = polls,
                            currentClientHash = currentClientHash,
                            currentGeneration = currentGeneration,
                        )
                        observedResponse = pane.response
                        return@withTimeoutOrNull true
                    }
                }
                AgentPaneCaptureStatus.TimedOut -> {
                    terminalResult = "capture_timeout"
                    return@withTimeoutOrNull false
                }
                AgentPaneCaptureStatus.Failed -> Unit
                AgentPaneCaptureStatus.Disconnected -> {
                    terminalResult = "disconnected"
                    return@withTimeoutOrNull false
                }
                AgentPaneCaptureStatus.StaleRuntime -> {
                    terminalResult = "stale_runtime"
                    return@withTimeoutOrNull false
                }
            }
            polls += 1
            delay(AGENT_SUBMIT_ACK_POLL_INTERVAL_MS)
        }
    } == true
    if (observed) return requireNotNull(observedResponse)

    recordAgentSubmitAck(
        identity = identity,
        paneId = paneId,
        result = terminalResult,
        polls = polls,
        currentClientHash = currentClientHash,
        currentGeneration = currentGeneration,
    )
    throw IllegalStateException("Agent paste acknowledgement was not proven ($terminalResult); kept queued.")
}

/**
 * Issue #1944: prove that the agent-side submit turn advanced after Enter. The
 * pre-Enter frame came from [awaitAgentPasteIngested], bound to the immutable
 * client/generation/session/pane identity. A changed authoritative capture is
 * the narrow generic signal shared by full-screen agent TUIs and the readline
 * fixture: the input/placeholder frame was consumed and the agent rendered its
 * submitted/working/ready state. Timeout is ambiguous and MUST remain a durable
 * row; callers must not translate tmux write completion into Delivered.
 */
internal suspend fun awaitAgentSubmitTurnover(
    identity: AgentSendRuntimeIdentity,
    paneId: String,
    payload: String,
    preEnterFrame: CommandResponse,
    timeoutMs: Long,
    transcriptAuthorityPresent: () -> Boolean = { false },
    transcriptAcknowledged: () -> Boolean = { false },
    capture: suspend (timeoutMs: Long, scrollbackLines: Int) -> AgentPaneCaptureResult,
    currentClientHash: () -> Int?,
    currentGeneration: () -> Long,
) {
    var polls = 0
    var terminalResult = "turnover_timeout"
    var consecutiveReadyCaptures = 0
    val observed = withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) {
        while (true) {
            if (
                transcriptAuthorityPresent() &&
                currentClientHash() == System.identityHashCode(identity.client) &&
                currentGeneration() == identity.generation &&
                transcriptAcknowledged()
            ) {
                recordAgentSubmitTurnover(
                    identity, paneId, "transcript_ack_observed", polls,
                    currentClientHash, currentGeneration,
                )
                return@withTimeoutOrNull true
            }
            val pane = capture(timeoutMs, 0)
            when (pane.status) {
                AgentPaneCaptureStatus.Captured -> {
                    val response = pane.response
                    val inputState = response?.let { agentInputSurfaceState(it, payload) }
                        ?: AgentInputSurfaceState.Unknown
                    recordAgentSubmitTurnoverCapture(
                        identity = identity,
                        paneId = paneId,
                        polls = polls,
                        response = response,
                        inputState = inputState,
                        currentClientHash = currentClientHash,
                        currentGeneration = currentGeneration,
                    )
                    consecutiveReadyCaptures = if (
                        !transcriptAuthorityPresent() &&
                        response != null &&
                        !response.isError &&
                        response.output != preEnterFrame.output &&
                        inputState == AgentInputSurfaceState.Ready
                    ) {
                        consecutiveReadyCaptures + 1
                    } else {
                        0
                    }
                    if (consecutiveReadyCaptures >= 2) {
                        recordAgentSubmitTurnover(
                            identity, paneId, "ready_surface_observed", polls,
                            currentClientHash, currentGeneration,
                        )
                        return@withTimeoutOrNull true
                    }
                }
                AgentPaneCaptureStatus.TimedOut -> {
                    terminalResult = "capture_timeout"
                    return@withTimeoutOrNull false
                }
                AgentPaneCaptureStatus.Failed -> Unit
                AgentPaneCaptureStatus.Disconnected -> {
                    terminalResult = "disconnected"
                    return@withTimeoutOrNull false
                }
                AgentPaneCaptureStatus.StaleRuntime -> {
                    terminalResult = "stale_runtime"
                    return@withTimeoutOrNull false
                }
            }
            polls += 1
            delay(AGENT_SUBMIT_ACK_POLL_INTERVAL_MS)
        }
    } == true
    if (observed) return
    recordAgentSubmitTurnover(
        identity, paneId, terminalResult, polls, currentClientHash, currentGeneration,
    )
    throw AgentSubmitTurnoverNotProvenException(terminalResult)
}

internal class AgentSubmitTurnoverNotProvenException(result: String) :
    IllegalStateException("Agent submit turnover was not proven ($result); kept queued.")

/**
 * Row-correlated #1944 turnover oracle. Agent transcripts commonly keep the
 * submitted user text visible, so payload presence anywhere in the pane is not
 * enough. Prefer the last conventional agent input prompt (`>` / `›`) and its
 * wrapped tail. If no prompt can be identified, fail closed by treating payload
 * presence anywhere as still-in-input; false negatives preserve the durable row.
 */
internal enum class AgentInputSurfaceState { PendingPayload, Ready, Unknown }

/**
 * Issue #2056: characters an agent/shell prompt may be WRAPPED in before its
 * marker glyph. Claude Code draws its input inside a box, so the live prompt row
 * reads `│ > ` — its first non-blank character is the box edge, not the marker.
 * The pre-#2056 matcher only looked at `line.trimStart()`, so it never found
 * Claude's prompt, [agentInputSurfaceState] answered [AgentInputSurfaceState.Unknown]
 * on every Claude frame, and the generic submit-turnover proof
 * ([awaitAgentSubmitTurnover]) could therefore NEVER succeed for Claude Code —
 * every send fell into the ambiguous `wireSubmitAttempted` state.
 */
private val AGENT_INPUT_PROMPT_DECORATION: Set<Char> =
    setOf('│', '┃', '┆', '┊', '╎', '║', '▌', '|')

/**
 * Issue #2056: marker glyphs that introduce an interactive input line. `>` / `›`
 * are the agent TUIs; `❯` / `➜` / `❭` are the maintainer's shell prompt, needed so
 * a shell-hosted send (`csp`, which relaunches an agent) is decidable at all
 * instead of being permanently unknown.
 */
private val AGENT_INPUT_PROMPT_GLYPHS: Set<Char> = setOf('>', '›', '❯', '➜', '❭', '$')

/** The prompt line's content with any leading blanks/box decoration removed. */
private fun agentInputPromptBody(line: String): String? {
    var index = 0
    while (
        index < line.length &&
        (line[index].isWhitespace() || line[index] in AGENT_INPUT_PROMPT_DECORATION)
    ) {
        index += 1
    }
    return line.substring(index).takeIf { it.isNotEmpty() }
}

/** Issue #2056: does [line] carry the pane's interactive input marker? */
internal fun isAgentInputPromptLine(line: String): Boolean {
    val body = agentInputPromptBody(line) ?: return false
    if (body[0] !in AGENT_INPUT_PROMPT_GLYPHS) return false
    return body.length == 1 || body[1] == ' ' || body[1] == '\t'
}

internal fun agentInputSurfaceState(
    response: CommandResponse,
    payload: String,
): AgentInputSurfaceState {
    if (response.isError) return AgentInputSurfaceState.Unknown
    val needle = agentSubmitAckNeedle(payload) ?: return AgentInputSurfaceState.Unknown
    val promptIndex = response.output.indexOfLast(::isAgentInputPromptLine)
    if (promptIndex < 0) return AgentInputSurfaceState.Unknown
    val candidate = response.output.drop(promptIndex)
    val pending = agentSubmitVisibleTextContainsNeedle(candidate, needle) ||
        (
            agentSubmitPayloadIsMultiLine(payload) &&
                agentSubmitCollapsedPasteMarkerCount(candidate) > 0
            )
    return if (pending) AgentInputSurfaceState.PendingPayload else AgentInputSurfaceState.Ready
}

private fun recordAgentSubmitTurnoverCapture(
    identity: AgentSendRuntimeIdentity,
    paneId: String,
    polls: Int,
    response: CommandResponse?,
    inputState: AgentInputSurfaceState,
    currentClientHash: () -> Int?,
    currentGeneration: () -> Long,
) {
    DiagnosticEvents.record(
        "action",
        "agent_submit_turnover_capture",
        "pane" to paneId,
        "poll" to polls,
        "promptFound" to (inputState != AgentInputSurfaceState.Unknown),
        "inputState" to inputState.name,
        "frameFingerprint" to response?.output?.joinToString("\n").hashCode().toUInt().toString(16),
        "clientHash" to System.identityHashCode(identity.client),
        "currentClientHash" to currentClientHash(),
        "generation" to identity.generation,
        "currentGeneration" to currentGeneration(),
    )
}

private fun recordAgentSubmitTurnover(
    identity: AgentSendRuntimeIdentity,
    paneId: String,
    result: String,
    polls: Int,
    currentClientHash: () -> Int?,
    currentGeneration: () -> Long,
) {
    DiagnosticEvents.record(
        "action",
        "agent_submit_turnover",
        "pane" to paneId,
        "result" to result,
        "polls" to polls,
        "clientHash" to System.identityHashCode(identity.client),
        "currentClientHash" to currentClientHash(),
        "generation" to identity.generation,
        "currentGeneration" to currentGeneration(),
        "session" to (identity.target?.sessionName ?: "test"),
    )
}

private fun recordAgentSubmitAck(
    identity: AgentSendRuntimeIdentity,
    paneId: String,
    result: String,
    polls: Int,
    currentClientHash: () -> Int?,
    currentGeneration: () -> Long,
) {
    DiagnosticEvents.record(
        "action",
        "agent_submit_ack",
        "pane" to paneId,
        "result" to result,
        "polls" to polls,
        "clientHash" to System.identityHashCode(identity.client),
        "currentClientHash" to currentClientHash(),
        "generation" to identity.generation,
        "currentGeneration" to currentGeneration(),
        "session" to (identity.target?.sessionName ?: "test"),
    )
}

private fun agentPaneShowsPayload(
    response: CommandResponse,
    needle: String,
    baselineNeedleCount: Int,
    payloadIsMultiLine: Boolean,
    collapsedMarkerBaseline: Int,
): Boolean {
    if (response.isError) return false
    return agentSubmitVisibleTextNeedleCount(response.output, needle) > baselineNeedleCount ||
        (
            payloadIsMultiLine &&
                agentSubmitCollapsedPasteMarkerCount(response.output) > collapsedMarkerBaseline
            )
}

internal fun agentSubmitAckNeedle(payload: String): String? {
    val lastLine = payload
        .split('\n')
        .map { it.trim() }
        .lastOrNull { it.isNotEmpty() }
        ?: return null
    val stripped = lastLine.replace(WHITESPACE_RUN_REGEX, "")
    if (stripped.isEmpty()) return null
    return stripped.takeLast(AGENT_SUBMIT_ACK_NEEDLE_TAIL_CHARS)
}

internal fun agentSubmitVisibleTextContainsNeedle(
    visibleLines: List<String>,
    needle: String,
): Boolean {
    val visible = visibleLines.joinToString(separator = "")
        .replace(WHITESPACE_RUN_REGEX, "")
    return visible.contains(needle)
}

/**
 * Issue #1577: count how many times [needle] occurs (non-overlapping) in the
 * whitespace-stripped visible text. The verify-before-resend probe uses this to
 * tell "MY paste landed" (the occurrence count went UP vs the pre-send baseline)
 * from "the payload text was already on the pane" — e.g. a Codex status line that
 * permanently renders `Goal blocked (/goal resume)`, whose `/goalresume` needle a
 * presence-only check would always false-match, dropping the send as a
 * false-`AlreadyLanded`.
 */
internal fun agentSubmitVisibleTextNeedleCount(
    visibleLines: List<String>,
    needle: String,
): Int {
    if (needle.isEmpty()) return 0
    val visible = visibleLines.joinToString(separator = "")
        .replace(WHITESPACE_RUN_REGEX, "")
    if (visible.isEmpty()) return 0
    var count = 0
    var index = visible.indexOf(needle)
    while (index >= 0) {
        count += 1
        index = visible.indexOf(needle, index + needle.length)
    }
    return count
}

/**
 * Issue #1687: Claude Code COLLAPSES a multi-line composer paste into a single
 * placeholder chip — `[Pasted text #1 +5 lines]` — and does NOT render the pasted
 * body on the pane. So the payload-tail ack needle ([agentSubmitAckNeedle], derived
 * from the payload's last line) can NEVER match a collapsed paste, and the ack gate
 * polled to its full timeout + fallback floor on EVERY normal multi-line send — the
 * dominant "sending takes too long even on a healthy link" cost. When the payload is
 * multi-line the gate ALSO recognises this chip as proof of ingestion.
 *
 * The paste counter `#N` and line count `+M` are session-relative and unpredictable
 * (Claude increments `#N` across the whole session), so match the PATTERN, not a
 * literal. Like the literal needle (#1577b), the caller requires the chip's
 * occurrence count to INCREASE over its pre-paste baseline so an older chip already
 * scrolled onto the pane never false-confirms.
 *
 * Matched against the WHITESPACE-STRIPPED join of the visible rows (the same
 * normalisation the needle uses), so a chip wrapped/reflowed across rows still
 * matches: `[Pasted text #1 +5 lines]` normalises to `[Pastedtext#1+5lines]`.
 */
private val COLLAPSED_PASTE_MARKER_REGEX = Regex("""\[Pastedtext#\d+\+\d+lines?]""")

/**
 * Issue #1687: the payload spans multiple lines, so Claude Code will collapse it to
 * the `[Pasted text #N +M lines]` chip rather than echo the body. Only then does the
 * ack gate arm the collapsed-chip recogniser (a single-line paste renders inline and
 * the literal needle matches it directly).
 */
internal fun agentSubmitPayloadIsMultiLine(payload: String): Boolean = payload.contains('\n')

/**
 * Issue #1687: how many collapsed-paste chips ([COLLAPSED_PASTE_MARKER_REGEX]) are
 * present in the whitespace-stripped visible text. The ack fires when this count
 * INCREASES over the pre-paste baseline (our paste added a chip), never on mere
 * presence — mirroring the count-baseline discipline of
 * [agentSubmitVisibleTextNeedleCount].
 */
internal fun agentSubmitCollapsedPasteMarkerCount(visibleLines: List<String>): Int {
    val visible = visibleLines.joinToString(separator = "")
        .replace(WHITESPACE_RUN_REGEX, "")
    if (visible.isEmpty()) return 0
    return COLLAPSED_PASTE_MARKER_REGEX.findAll(visible).count()
}

private val WHITESPACE_RUN_REGEX = Regex("\\s+")
