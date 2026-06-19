package com.pocketshell.app.tmux.connection

/**
 * EPIC #792 Slice B — the ATTACH / same-host fast-SWITCH IO owner.
 *
 * Before Slice B the warm same-host session-switch IO (`runFastSessionSwitch`) was a
 * private method called INLINE from `TmuxSessionViewModel.connect()`'s `connectJob`
 * critical section. Slice B makes [TmuxAttachEffects] the SOLE owner of the fast-switch
 * effect dispatch; the deeply-coupled IO body stays as a [TmuxAttachIo] capability the
 * VM implements (the established seam pattern — the effect class owns the
 * *trigger/decision*, the VM owns the coupled primitive). The inline
 * `runFastSessionSwitch` twin is DELETED (D22 hard-cut — no dual-write, single path).
 *
 * ## Why the trigger is synchronous, not the async driver collector
 * Unlike the grace effects (which are inherently lifecycle-async — background detach /
 * within-grace foreground), the fast-switch runs inside `connect()`'s carefully-ordered
 * `connectJob` under `NonCancellable` (`previousConnectJob.cancelAndJoin()` →
 * `deactivateCurrentRuntimeToCache()` → the switch body). That synchronous ordering is
 * load-bearing for the no-flash / no-stale-frame / switch-latency contract (the
 * `MultiSessionSwitchJourney` guard). Routing the trigger through the driver's async
 * `state` collector would resume on a later Main turn and RACE that critical section —
 * the exact switch-latency / blank-pane regression Slice B's coverage gate forbids. So
 * [runFastSwitch] is invoked at the EXISTING synchronous trigger point inside the
 * connectJob; only the IO body moved behind this owner. The controller's `Switch` →
 * `Attaching` intent (slice A) already drives the displayed status; this owns the IO.
 *
 * Behaviour is byte-identical: [runFastSwitch] invokes the SAME [TmuxAttachIo] body the
 * inline `runFastSessionSwitch` performed, with the same suspension semantics. Only the
 * dispatch owner changed.
 *
 * The fast-switch args (`target`/`attempt`/`trigger`/`startedAtMs`) are VM-internal
 * types the connect() caller already holds locally, so the capability is a captured
 * `suspend` thunk rather than a typed-arg method — this keeps the effect class free of
 * VM-internal type coupling while still being the single dispatch owner.
 *
 * @param io the VM-implemented capability that performs the actual (deeply VM-coupled)
 *   warm-switch attach IO. [TmuxAttachEffects] owns only the effect dispatch.
 */
class TmuxAttachEffects(private val io: TmuxAttachIo) {

    /**
     * Run the warm same-host fast session switch: reuse the live SSH transport, swap the
     * `-CC` control client to the new tmux session, reconcile + seed its panes, and
     * reveal once the active pane is non-blank — skipping the full SSH handshake. The
     * SOLE owner of this IO now the inline `runFastSessionSwitch` call is deleted. The
     * caller (connect()'s connectJob) supplies the args via the captured [body] thunk.
     * Delegates to [TmuxAttachIo.runFastSwitch].
     */
    suspend fun runFastSwitch(body: suspend () -> Unit) {
        io.runFastSwitch(body)
    }

    /**
     * The narrow capability the VM implements so [TmuxAttachEffects] can own the
     * fast-switch effect dispatch without owning the deeply VM-coupled IO body (lease
     * acquisition, client create/attach, pane reconcile/seed, reveal machine, telemetry,
     * the stale-lease auto-recover fallback, ~25 private fields). The single method is
     * the SAME body the inline `runFastSessionSwitch` performed; only the dispatch owner
     * moved.
     */
    interface TmuxAttachIo {
        /**
         * Perform the warm same-host fast session switch — runs the supplied [body]
         * (the VM's `runFastSessionSwitch(target, attempt, trigger, startedAtMs)` closed
         * over the caller's args), the body of the deleted inline twin. A thin hook so a
         * test can pin that the switch IO flows through this single owner.
         */
        suspend fun runFastSwitch(body: suspend () -> Unit)
    }
}
