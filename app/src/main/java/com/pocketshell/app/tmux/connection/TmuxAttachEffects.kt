package com.pocketshell.app.tmux.connection

import com.pocketshell.app.tmux.TmuxConnectTrigger
import com.pocketshell.app.tmux.TmuxSessionViewModel.ConnectionTarget

/**
 * EPIC #792 Slice B/E — the ATTACH / same-host fast-SWITCH effect owner.
 *
 * Before Slice B the warm same-host session-switch IO (`runFastSessionSwitch`) was a private
 * method called INLINE from `TmuxSessionViewModel.connect()`'s `connectJob` critical section.
 * Slice B made [TmuxAttachEffects] the SOLE owner of the fast-switch effect dispatch.
 *
 * ## Slice E — the typed contract (the body relocation)
 * Slice B parked the dispatch behind an opaque `body: () -> Unit` thunk (the orchestration
 * contract still lived inline in the VM's connect()). Slice E finishes the god-object
 * extraction: the SWITCH CONTRACT — the typed `(target, attempt, trigger, startedAtMs)` call
 * the switch is — now lives HERE, on the effect owner, not as a closed-over thunk in the
 * 13k-line VM. [runFastSwitch] takes the typed args and delegates to the [TmuxAttachIo.attach]
 * capability the VM implements (the deeply-VM-coupled IO body: lease acquisition, client
 * create/attach, pane reconcile/seed, reveal machine, the stale-lease auto-recover fallback,
 * telemetry, ~30 private fields). The effect class owns the *contract + the single dispatch
 * point*; the VM owns the coupled primitive — the established Slice B/C/D seam, now expressed
 * as a typed method rather than an opaque thunk so the switch's shape is visible on the owner.
 *
 * ## Why the trigger is synchronous, not the async driver collector
 * Unlike the grace effects (inherently lifecycle-async), the fast-switch runs inside
 * `connect()`'s carefully-ordered `connectJob` under `NonCancellable`
 * (`previousConnectJob.cancelAndJoin()` → `deactivateCurrentRuntimeToCache()` → the switch
 * body). That synchronous ordering is load-bearing for the no-flash / no-stale-frame /
 * switch-latency contract (the `MultiSessionSwitchJourney` guard). Routing the trigger through
 * the driver's async `state` collector would resume on a later Main turn and RACE that
 * critical section — the exact switch-latency / blank-pane regression Slice B's coverage gate
 * forbids. So [runFastSwitch] is invoked at the EXISTING synchronous trigger point inside the
 * connectJob; only the dispatch ownership (and now the typed contract) moved.
 *
 * Behaviour is byte-identical: [runFastSwitch] invokes the SAME [TmuxAttachIo.attach] body the
 * inline `runFastSessionSwitch` performed, with the same args and suspension semantics. Only
 * the dispatch owner + the contract location changed.
 *
 * @param io the VM-implemented capability that performs the actual (deeply VM-coupled) warm
 *   fast-switch attach IO. [TmuxAttachEffects] owns the contract + dispatch.
 */
internal class TmuxAttachEffects(private val io: TmuxAttachIo) {

    /**
     * Run the warm same-host fast session switch: reuse the live SSH transport, swap the `-CC`
     * control client to the new tmux session, reconcile + seed its panes, and reveal once the
     * active pane is non-blank — skipping the full SSH handshake. The SOLE owner of this switch
     * now the inline `runFastSessionSwitch` call is deleted. Delegates to [TmuxAttachIo.attach]
     * with the typed switch args (formerly closed over in an opaque thunk).
     */
    suspend fun runFastSwitch(
        target: ConnectionTarget,
        attempt: Int,
        trigger: TmuxConnectTrigger,
        startedAtMs: Long,
    ) {
        io.attach(target, attempt, trigger, startedAtMs)
    }

    /**
     * The capability the VM implements so [TmuxAttachEffects] can own the fast-switch contract
     * + dispatch without owning the deeply VM-coupled IO body (lease acquisition, client
     * create/attach, pane reconcile/seed, reveal machine, telemetry, the stale-lease
     * auto-recover fallback, ~30 private fields). [attach] is the SAME body the inline
     * `runFastSessionSwitch(target, attempt, trigger, startedAtMs)` performed; only the
     * dispatch owner + contract location moved.
     */
    internal interface TmuxAttachIo {
        /**
         * Perform the warm same-host fast session switch — the body of the deleted inline
         * `runFastSessionSwitch`, now driven through this single owner with the typed args.
         */
        suspend fun attach(
            target: ConnectionTarget,
            attempt: Int,
            trigger: TmuxConnectTrigger,
            startedAtMs: Long,
        )
    }
}
