package com.pocketshell.app.proof

import com.pocketshell.app.tmux.AgentInputSurfaceState
import com.pocketshell.app.tmux.agentInputSurfaceState
import com.pocketshell.core.tmux.CommandResponse
import org.junit.Assert.assertEquals

/**
 * Issue #2056 — DELIBERATELY INDUCED SUBMIT AMBIGUITY for the late-authority journeys
 * in [OutboundExactlyOnceAcrossFlapE2eTest] (D33: "add the fixture that creates the
 * non-happy state").
 *
 * ## Why this exists
 *
 * `manualRetryOfUnconfirmedSubmitWaitsForLateAuthority`,
 * `lateAckSurvivesCloseAndRecreateWithoutDuplicate` and
 * `busyAgentOnStableWritableWireQueuesFifoWithoutBurningAttemptBudget` all assert on the
 * LATE-AUTHORITY path: a submit whose outcome the bounded turnover wait could not prove
 * parks as a durable `wireSubmitAttempted` row, and only an authoritative transcript turn
 * arriving afterwards may resolve it.
 *
 * Until #2056 that ambiguity happened BY ACCIDENT — the product had no pane authority at
 * all, so a delayed transcript left the row unresolved by construction. #2056 gave the
 * product a real pane authority (an input surface that no longer holds the payload proves
 * the agent consumed the submit). On the readline `render_default` fake-agent surface
 * (`> ` prompt) that authority answers on the very first late-ack poll, the row is
 * acknowledged and pruned, and those three journeys never reach the parked state they
 * exist to exercise — they simply time out on `rows=[]`. Their protection disappears
 * rather than fails, which is strictly worse than a red test.
 *
 * ## How the ambiguity is induced — honestly
 *
 * The framed fake-agent surface (`┃ `, no marker glyph — the shape real Claude/Codex draw
 * while the agent is WORKING; `tests/docker/agent-bin/pocketshell-fake-agent`,
 * `POCKETSHELL_FAKE_AGENT_RENDER_MODE=issue1944-framed-input`) is genuinely UNDECIDABLE to
 * [agentInputSurfaceState] both before and after Enter: the box edge is stripped as
 * decoration and nothing behind it is a prompt marker. The late-authority chain therefore
 * fails CLOSED on its pane arm exactly as designed, the row stays parked, and the delayed
 * transcript remains the only authority that can resolve it.
 *
 * Nothing is weakened: every assertion those journeys make about exactly-once delivery,
 * FIFO order, the attempt budget and late-ack-across-recreate still runs, on the real
 * wire, against the real parked state.
 *
 * [assertPaneSubmitOracleIsUndecidable] is the HARD precondition that keeps this honest —
 * if a future fixture or matcher change made the surface decidable, the journeys fail
 * loudly with a named cause instead of quietly becoming happy paths.
 */
internal object Issue2056InducedSubmitAmbiguity {

    /** The fake-agent render mode whose input surface no oracle can read. */
    const val FRAMED_INPUT_RENDER_MODE: String = "issue1944-framed-input"

    /**
     * The events that explain an outbound row's fate. An outbound wait that times out
     * used to report only the last ten diagnostics, which on this journey are per-frame
     * render-heal churn — none of the row's state transitions, its route, the turnover
     * verdict, or the late-authority verdicts.
     */
    val DELIVERY_EVENT_NAMES: Set<String> = setOf(
        "row_state",
        "composer_send",
        "composer_tmux_send_route",
        "agent_submit_turnover",
        "agent_submit_transcript_baseline",
        "agent_submit_transcript_ack",
        "agent_submit_transcript_late_ack",
        "outbound_late_pane_submit_ack",
        "outbound_verify_before_resend",
    )

    /** The `env` assignment that seeds the undecidable surface, or "" for other tests. */
    fun renderModeEnvFor(methodName: String, inducedAmbiguityTests: Set<String>): String =
        if (methodName in inducedAmbiguityTests) {
            "POCKETSHELL_FAKE_AGENT_RENDER_MODE=$FRAMED_INPUT_RENDER_MODE "
        } else {
            ""
        }

    /**
     * Assert, against the exact production oracle and the REAL pane bytes in [capture],
     * that the pane cannot decide whether the submit was consumed.
     *
     * The caller must pass a capture it has already proven non-blank (a failed sidecar
     * read would answer Unknown for the wrong reason and hand this precondition a free
     * pass — #1819's rule that a blank capture is a read failure, never a pane state).
     */
    fun assertPaneSubmitOracleIsUndecidable(label: String, payload: String, capture: String) {
        assertEquals(
            "$label: this journey requires an UNDECIDABLE pane submit oracle so the " +
                "late transcript authority is the only resolver (#2056). The framed " +
                "fake-agent surface must read Unknown; capture:\n$capture",
            AgentInputSurfaceState.Unknown,
            agentInputSurfaceState(
                CommandResponse(number = 0L, output = capture.lines(), isError = false),
                payload,
            ),
        )
    }
}
