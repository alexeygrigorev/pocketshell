package com.pocketshell.app.tmux.connection

/**
 * EPIC #687 Slice 1 (#1047) — the BACKGROUND-TRANSITION decision, the SECOND of the four
 * inline `TmuxSessionViewModel` `reduce*` selectors retired into the connection core under
 * the maintainer's Path-A D28 consolidation (Slice 0 retired `reduceForeground`; this slice
 * follows the SAME proven pattern).
 *
 * Before this slice the background pause-vs-detach arm was decided by the inline
 * `TmuxSessionViewModel.reduceBackground()` selector — a SECOND decision authority alongside
 * the [com.pocketshell.core.connection.ConnectionController], the exact dual-authority
 * condition D28 exists to end. Unlike the foreground decision (which read only effect
 * payloads), this decision needs context the controller LACKS: the controller transitions to
 * `Backgrounded` whenever it holds a host (even from `Reconnecting`), so the arm selection
 * cannot trust `controller.state` — it needs the missing "is there actually a live client /
 * session to detach" fact. That fact is injected as the [hasLiveControlChannel] port
 * (`clientRef != null || sessionRef != null` for the current host — the #685 re-read trap).
 * The inline `reduceBackground()` and its two `ConnectionDecision` variants
 * (`PauseReconnectForBackground` / `DetachForBackground`) are DELETED in the same change
 * (D22 hard-cut — no shadow selector, no coexistence).
 */
enum class BackgroundArm {
    /** A reconnect was in flight; pause it until foreground (the old `PauseReconnectForBackground`). */
    PauseReconnect,

    /** Detach the live `-CC` control client and stash a pending reattach (the old `DetachForBackground`). */
    DetachForBackground,

    /** No target, or no live client/session to detach — a no-op (the old inline `Ignore`). */
    None,
}

/**
 * The PURE background selector — the connection-core replacement for the deleted inline
 * `reduceBackground()` predicate. The arm ORDER is byte-identical to the inline selector
 * (the #685 predicate-order trap — keep the EXACT inline precedence so behavior is
 * unchanged):
 *
 *   1. [isReconnecting] (an in-flight reconnect ladder)  -> [BackgroundArm.PauseReconnect]
 *   2. NOT [hasTarget] (no active and no connecting target) -> [BackgroundArm.None]
 *   3. NOT [hasLiveControlChannel] (no live client and no session) -> [BackgroundArm.None]
 *   4. else (a live channel exists)                       -> [BackgroundArm.DetachForBackground]
 *
 * The third predicate is the INJECTED context the controller lacks: a `Backgrounded`
 * transition does not imply a live control channel, so the detach arm gates on the VM's
 * `clientRef`/`sessionRef` liveness fed in as [hasLiveControlChannel].
 */
fun selectBackgroundArm(
    isReconnecting: Boolean,
    hasTarget: Boolean,
    hasLiveControlChannel: Boolean,
): BackgroundArm = when {
    isReconnecting -> BackgroundArm.PauseReconnect
    !hasTarget -> BackgroundArm.None
    !hasLiveControlChannel -> BackgroundArm.None
    else -> BackgroundArm.DetachForBackground
}

/**
 * The connection-core background-transition dispatcher: the SINGLE authority for the
 * background pause-vs-detach decision. The driver's `-> Backgrounded` edge calls [dispatch];
 * it [selectBackgroundArm]s from the wired predicates and fires the matching VM-supplied IO
 * body.
 *
 * The predicates ([isReconnecting] / [hasTarget] / [hasLiveControlChannel]) re-read the VM's
 * live state at dispatch time (the #685 trap: the decision must read CURRENT state, not a
 * snapshot captured at construction), and the arm bodies are the VM's existing IO. This type
 * holds the DECISION; the VM holds the state + the IO.
 */
class BackgroundEffects(
    private val isReconnecting: () -> Boolean,
    private val hasTarget: () -> Boolean,
    private val hasLiveControlChannel: () -> Boolean,
    private val pauseReconnectForBackground: () -> Unit,
    private val detachForBackground: () -> Unit,
    private val onNoArm: () -> Unit = {},
) {
    /**
     * Select the background arm from the live predicates and fire its body. Returns the
     * selected arm so the characterization test can assert on the decision directly.
     */
    fun dispatch(): BackgroundArm {
        val arm = selectBackgroundArm(
            isReconnecting = isReconnecting(),
            hasTarget = hasTarget(),
            hasLiveControlChannel = hasLiveControlChannel(),
        )
        when (arm) {
            BackgroundArm.PauseReconnect -> pauseReconnectForBackground()
            BackgroundArm.DetachForBackground -> detachForBackground()
            BackgroundArm.None -> onNoArm()
        }
        return arm
    }
}
