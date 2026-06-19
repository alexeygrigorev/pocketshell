package com.pocketshell.app.tmux.connection

/**
 * EPIC #792 Slice C — the RECONNECT-LADDER IO owner (the #822 wedge surface).
 *
 * Before Slice C the reconnect re-dial IO lived inline on
 * [com.pocketshell.app.tmux.TmuxSessionViewModel] as TWO entrypoints invoked from FIVE
 * sites:
 *  - `scheduleAutoReconnect(...)` — the auto-reconnect ladder (the delays loop that walks
 *    `Reconnecting(attempt=1..N)` and re-dials each attempt). Called inline from the
 *    passive-disconnect surface, the network-handoff reconnect, and the within-grace heal
 *    fallback.
 *  - `startReconnectForSend()` — the MANUAL "Tap Reconnect" / send-triggered reconnect.
 *    Called inline from `reconnect()` (the screen's Reconnect button) and the
 *    send-while-not-Connected path (`awaitLiveTmuxClientForSend`).
 *
 * Those two inline entrypoints WERE the reconnect path. Per D28(4) "single active path"
 * + D22 hard-cut, Slice C collapses every reconnect trigger to ONE owner: [TransportEffects]
 * is the SOLE dispatcher of the reconnect IO. The deeply-VM-coupled IO bodies stay as
 * [ReconnectIo] capability methods the VM implements (the established slice-B seam pattern
 * — the effect class owns the *trigger/decision/single-entrypoint contract*, the VM owns
 * the coupled primitive: lease manager, generation counter, diagnostic trails, the
 * non-retryable-failure ladder, ~dozen private fields). Every former inline direct call
 * now routes through this one object, so there is exactly ONE reconnect entrypoint — the
 * one the future #823 pull-to-reconnect / Reconnect affordance (Slice D) will also call,
 * never a third writer on `scheduleAutoReconnect`.
 *
 * ## The #822 wedge fix (the point of Slice C)
 * The wedge is NOT in the dispatch — it is in the ladder's lease handling, which is why
 * the fix lives in the [ReconnectIo.runAutoReconnectLadder] body the VM implements (see
 * its KDoc + the VM's `shouldForceFreshLease`). The auto-reconnect ladder used the
 * `AutoReconnect` trigger, which did NOT force a fresh SSH lease, so on a silent half-open
 * drop (sshj's `isConnected` lies until the ~60s keep-alive trips) every ladder attempt
 * reused the SAME poisoned warm lease entry from the pool and never recovered — the
 * "Reconnecting(1/4) stuck ~45s, only recovers via the switch dance" symptom. The switch
 * dance recovered only because re-entering `connect()` to another host eventually evicted
 * the poisoned lease. The fix evicts the stale lease on every auto-reconnect attempt
 * (force-fresh-lease, like the manual `Reconnect`/`NetworkReconnect`/`LifecycleReattach`
 * triggers already did), so the SAME session auto-recovers with NO switch dance.
 *
 * Behaviour is byte-identical for the DISPATCH: each method invokes the SAME [ReconnectIo]
 * body the inline entrypoint invoked, in the same order, with the same guards. Only the
 * dispatch owner changed (plus the lease-eviction fix inside the auto body).
 *
 * @param io the VM-implemented capability that performs the actual (deeply VM-coupled)
 *   reconnect ladder IO. [TransportEffects] never touches transport/lease/client state
 *   directly — it owns only the single-entrypoint reconnect dispatch.
 */
class TransportEffects(private val io: ReconnectIo) {

    /**
     * The AUTO-reconnect ladder — a silent (no scary "Tap Reconnect" band) re-dial walk
     * across `Reconnecting(attempt=1..N)`. Fired for a passive control-channel drop, a
     * network handoff, and the within-grace heal fallback — all three former inline
     * `scheduleAutoReconnect(...)` sites now route here, so there is one ladder owner.
     * Delegates to [ReconnectIo.runAutoReconnectLadder], which holds the lease-eviction
     * wedge fix. The caller supplies the target/reason/trigger/diagnostics via the captured
     * [body] thunk (those are VM-internal types the caller already holds locally), keeping
     * this effect class free of VM-internal type coupling while still being the single
     * dispatch owner.
     */
    fun onAutoReconnect(body: () -> Unit) {
        io.runAutoReconnectLadder(body)
    }

    /**
     * The MANUAL / send-triggered reconnect — cancels any in-flight auto-ladder and
     * re-enters `connect(Reconnect)` (the force-fresh-lease trigger). Fired by the
     * screen's Reconnect button (`reconnect()`) and the send-while-not-Connected path.
     * Both former inline `startReconnectForSend()` callers now route here, so the manual
     * reconnect has ONE owner too. Delegates to [ReconnectIo.runManualReconnect], which
     * returns the connect [kotlinx.coroutines.Job] (or null when there is no target to
     * reconnect to) so the send path can `join()` it.
     */
    fun onManualReconnect(): ManualReconnectResult = io.runManualReconnect()

    /**
     * The narrow capability the VM implements so [TransportEffects] can own the reconnect
     * dispatch without owning the deeply-VM-coupled IO bodies. Each method is the SAME body
     * the inline entrypoint invoked (the auto body additionally carries the #822
     * lease-eviction fix); only the dispatch owner moved.
     */
    interface ReconnectIo {
        /**
         * Run the auto-reconnect ladder — the body of the deleted inline
         * `scheduleAutoReconnect(target, reason, trigger, diagnosticFields)`, closed over
         * the caller's args in [body]. The body MUST evict the stale/poisoned warm lease
         * before each attempt's re-dial (the #822 wedge fix — see the VM's
         * `shouldForceFreshLease`), so a silent half-open drop auto-recovers without the
         * switch dance.
         */
        fun runAutoReconnectLadder(body: () -> Unit)

        /**
         * Run the manual / send-triggered reconnect — the body of the deleted inline
         * `startReconnectForSend()`. Returns the connect job to join (or `null` when there
         * is no target to reconnect to).
         */
        fun runManualReconnect(): ManualReconnectResult
    }

    /**
     * The result of a manual reconnect dispatch: the connect [kotlinx.coroutines.Job]
     * the send path joins, or `null` when there was no target to reconnect to.
     */
    class ManualReconnectResult(val job: kotlinx.coroutines.Job?)
}
