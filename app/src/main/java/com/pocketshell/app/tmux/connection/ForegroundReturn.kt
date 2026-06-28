package com.pocketshell.app.tmux.connection

/**
 * EPIC #687 Slice 0 (#1047) — the FOREGROUND-RETURN decision, the FIRST of the four
 * inline `TmuxSessionViewModel` `reduce*` selectors retired into the connection core
 * under the maintainer's Path-A D28 consolidation.
 *
 * Before this slice the beyond-grace foreground arm (replay-vs-resume) was decided by the
 * inline `TmuxSessionViewModel.reduceForeground()` selector — a SECOND decision authority
 * alongside the [com.pocketshell.core.connection.ConnectionController], the exact
 * dual-authority condition D28 exists to end. The decision reads ONLY two effect payloads
 * (`pendingReattach` / `pausedAutoReconnect`), so it needs ZERO new controller context:
 * the VM passes the two payload predicates + the arm IO bodies and this connection-core
 * type OWNS the decision. The inline `reduceForeground()` and its two `ConnectionDecision`
 * variants (`ReplayPendingReattach` / `ResumePausedReconnect`) are DELETED in the same
 * change (D22 hard-cut — no shadow selector, no coexistence).
 */
enum class ForegroundReturnArm {
    /** Replay the stashed `pendingReattach` (a fresh connect to the detached session). */
    ReplayPendingReattach,

    /** Resume an auto-reconnect that was paused while backgrounded. */
    ResumePausedReconnect,

    /** No pending arm — the foreground is a no-op (the old inline `Ignore`). */
    None,
}

/**
 * The PURE foreground-return selector — the connection-core replacement for the deleted
 * inline `reduceForeground()` predicate. The arm ORDER is byte-identical to the inline
 * selector: a stashed `pendingReattach` replay takes precedence over a
 * `pausedAutoReconnect` resume (the #685 predicate-order trap — keep the EXACT inline
 * precedence so behavior is unchanged).
 */
fun selectForegroundReturnArm(
    hasPendingReattach: Boolean,
    hasPausedAutoReconnect: Boolean,
): ForegroundReturnArm = when {
    hasPendingReattach -> ForegroundReturnArm.ReplayPendingReattach
    hasPausedAutoReconnect -> ForegroundReturnArm.ResumePausedReconnect
    else -> ForegroundReturnArm.None
}

/**
 * The connection-core foreground-return dispatcher: the SINGLE authority for the
 * beyond-grace foreground decision. The driver's `Backgrounded -> Reconnecting` edge
 * (and the App-grace post-grace hook) call [dispatch]; it [selectForegroundReturnArm]s
 * from the wired payload predicates and fires the matching VM-supplied IO body.
 *
 * The payload predicates ([hasPendingReattach] / [hasPausedAutoReconnect]) re-read the
 * VM's live state at dispatch time (the #685 trap: the decision must read CURRENT state,
 * not a snapshot captured at construction), and the arm bodies are the VM's existing IO.
 * This type holds the DECISION; the VM holds the state + the IO.
 */
class ForegroundReturnEffects(
    private val hasPendingReattach: () -> Boolean,
    private val hasPausedAutoReconnect: () -> Boolean,
    private val replayPendingReattach: () -> Unit,
    private val resumePausedAutoReconnect: () -> Unit,
    private val onNoPendingArm: () -> Unit = {},
) {
    /**
     * Select the foreground-return arm from the live payloads and fire its body. Returns
     * the selected arm so the characterization test can assert on the decision directly.
     */
    fun dispatch(): ForegroundReturnArm {
        val arm = selectForegroundReturnArm(
            hasPendingReattach = hasPendingReattach(),
            hasPausedAutoReconnect = hasPausedAutoReconnect(),
        )
        when (arm) {
            ForegroundReturnArm.ReplayPendingReattach -> replayPendingReattach()
            ForegroundReturnArm.ResumePausedReconnect -> resumePausedAutoReconnect()
            ForegroundReturnArm.None -> onNoPendingArm()
        }
        return arm
    }
}
