package com.pocketshell.core.tmux

import com.pocketshell.core.tmux.protocol.ControlEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Issue #576 (Slice A of #792): collapse a burst of structural `tmux -CC`
 * control events into AT MOST one reconcile per [windowMs] window, and run
 * that reconcile OFF the caller's (UI) thread.
 *
 * ## Why this exists
 *
 * The control-event collector in `TmuxSessionViewModel` historically ran
 * `client.events.collect { onControlEvent(event) }` on `Dispatchers.Main.immediate`
 * and, for every structural event
 * ([ControlEvent.LayoutChange] / [ControlEvent.WindowAdd] /
 * [ControlEvent.WindowClose] / [ControlEvent.PaneModeChanged]), synchronously ran
 * a `reconcilePanes()` round-trip (`list-panes` + active-pane `capture-pane`
 * seed + `_panes`/recompose churn) on the UI thread. A Codex `/new`
 * full-screen redraw emits a STORM of `%layout-change`; each event
 * head-of-line-blocked the main looper behind a `list-panes`/`capture-pane`
 * round-trip on the single `-CC` channel, so input/frames could not be
 * serviced → ANR / full freeze (the regression reported on #576 after the
 * #766 connection migration).
 *
 * This coalescer sits between the event collector and `reconcilePanes`:
 *
 *  - [offer] is non-blocking and safe to call from any thread (notably the
 *    event collector). It never suspends and never throws.
 *  - At most ONE [reconcile] runs per [windowMs] window: N storm events
 *    collapse to ~1 reconcile per frame instead of N. The internal channel
 *    is conflated, so a burst of offers between two reconciles is collapsed
 *    to a single pending trigger.
 *  - The FINAL state is never dropped: the conflated channel always retains
 *    the most-recent trigger, so the reconcile that runs after a burst
 *    settles reflects the latest layout. (The [reconcile] callback itself
 *    re-reads the authoritative pane list from tmux via `list-panes`, so the
 *    coalescer only needs to guarantee that a reconcile runs *after* the last
 *    offer — not to carry per-event payloads.)
 *  - [reconcile] runs inside the [scope] this coalescer is started in. In
 *    production that scope is dispatched on `Dispatchers.Default`, so the
 *    `list-panes`/`capture-pane` round-trips and `_panes`/recompose churn no
 *    longer block the UI thread. The reconcile body is responsible for
 *    marshalling any UI-state publish back to the main thread itself.
 *  - A reconcile that could NOT reach authority is RETRIED, bounded (issue
 *    #2469 — see "Bounded repair" below). Before that, such a structural
 *    event was silently lost forever.
 *
 * ## Bounded repair when the reconcile cannot reach authority (issue #2469)
 *
 * `reconcilePanes()` enumerates panes with a bounded `list-panes` exec
 * (`RECONCILE_LIST_PANES_EXEC_TIMEOUT_MS`, 6 s). On a degraded mobile link
 * (RTT seconds, not milliseconds) that bound is genuinely reachable, and the
 * reconcile then returns `PaneReconcileResult.Failed` — WITHOUT throwing. The
 * coalescer used to treat "the reconcile returned" as "the structural change
 * was applied", so a `%layout-change` whose enumeration timed out was
 * **dropped permanently**: the app's pane model stayed stale until some later,
 * unrelated structural event happened to arrive and reconcile successfully.
 * On a phone that is the user-visible "I split a pane / opened a window on the
 * desktop and PocketShell never showed it" staleness — and, because
 * `applyParsedPanes` is what re-runs per-pane agent detection, it also drops
 * the re-classify that a pane-input change should have triggered.
 *
 * That is a delivery gap, not a timeout-calibration problem: widening the 6 s
 * bound would only trade a lost event for a longer UI-thread-free stall while
 * still losing it on a slower link. Instead [reconcile] now REPORTS whether it
 * reached authority, and a non-authoritative result schedules an
 * [offerAuthoritativeRepair] after a backoff, up to [repairBackoffMs]`.size`
 * attempts per structural event. It is the exact analogue of the existing
 * dropped-structural-event repair the host already wires from
 * `TmuxClient.structuralEventOverflowGeneration`: there the event never
 * reached us, here the enumeration it triggered never landed. Both mean "the
 * pane model may be stale — go re-read it".
 *
 * The retry budget is deliberately small and self-limiting: after the last
 * attempt the loop stops scheduling and parks on the trigger channel again, so
 * a genuinely dead link costs a bounded handful of bounded execs, never a hot
 * loop. The attempt counter resets on the first authoritative reconcile.
 *
 * ## Coalescing contract (precise)
 *
 * After [start], a single drain loop runs:
 *  1. Suspend until at least one trigger has been [offer]ed (the conflated
 *     channel delivers the latest).
 *  2. Run [reconcile] exactly once.
 *  3. [delay] [windowMs]. Any offers that arrived during steps 2–3 are
 *     conflated into a single pending trigger; the loop returns to step 1 and
 *     that single trigger drives exactly one more reconcile.
 *
 * So for a burst of N offers arriving within one [windowMs] window the loop
 * runs ~1 reconcile (the leading one) plus at most ~1 trailing reconcile per
 * window of continued bursting — never O(N). For an idle stream the per-event
 * latency is just the [reconcile] cost (no added delay on the first offer
 * after idle, because step 1 returns immediately when a trigger is waiting).
 *
 * The class is pure (no `android.*`) and virtual-clock testable: pass a
 * [CoroutineScope] backed by a `TestScope`/`TestDispatcher` and assert the
 * reconcile count under [delay]-driven virtual time.
 */
public class LayoutChangeCoalescer(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    /**
     * The structural reconcile to run. Invoked at most once per [windowMs]
     * window, on the dispatcher of the [scope] passed to [start]. Must be
     * idempotent w.r.t. the authoritative pane list (it re-reads tmux), and
     * must marshal any UI-thread publish itself.
     *
     * Returns whether the reconcile reached AUTHORITY — i.e. the pane list was
     * re-read and applied, or the reconcile is moot (the client this coalescer
     * belongs to was replaced). Returning `false` means the structural change
     * could not be applied — on the production path, a bounded `list-panes`
     * exec that timed out on a degraded link (`PaneReconcileResult.Failed`) —
     * and schedules a bounded repair retry (issue #2469). Before #2469 this
     * returned `Unit` and a `Failed` reconcile was indistinguishable from a
     * successful one, so the structural change was dropped permanently.
     *
     * Exceptions other than [CancellationException] are swallowed by the
     * drain loop (logged via [onReconcileError]) so one failed reconcile does
     * not tear down the coalescer — matching the existing `reconcilePanes`
     * "transient list-panes failure leaves the pane list intact" contract. A
     * throw counts as non-authoritative and is repaired the same way.
     */
    private val reconcile: suspend () -> Boolean,
    /**
     * Optional hook invoked when [reconcile] throws a non-cancellation
     * error. Lets the host log without coupling this pure module to a logger.
     */
    private val onReconcileError: (Throwable) -> Unit = {},
    /**
     * Issue #2469 — the per-structural-event repair backoff ladder. One entry
     * per retry attempt, in order; an empty list disables repair entirely.
     * Sized so the FIRST repair lands promptly (a stale pane list is visible
     * to the user within a second) while the tail backs off, and so the whole
     * ladder still fits inside a few seconds of a degraded-link episode rather
     * than hammering it. Injectable so a virtual-clock test can pin the exact
     * schedule.
     */
    private val repairBackoffMs: List<Long> = DEFAULT_REPAIR_BACKOFF_MS,
) {
    /**
     * Conflated trigger channel. A burst of [offer]s collapses to a single
     * pending element (DROP_OLDEST keeps the latest), so the drain loop can
     * never run more reconciles than the number of windows it observes.
     */
    private val triggers = Channel<Unit>(
        // capacity 1 + DROP_OLDEST is conflation: a storm of offers between
        // two reconciles collapses to a single pending trigger (Channel.CONFLATED
        // would imply this but forbids an explicit onBufferOverflow, so we
        // spell it out).
        capacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val reconcileCountState = MutableStateFlow(0L)

    /**
     * Visible-for-test: the number of times [reconcile] has been invoked.
     * Lets a test assert that an N-event storm collapsed to ~1 reconcile per
     * window rather than O(N).
     */
    public val reconcileCount: StateFlow<Long> = reconcileCountState.asStateFlow()

    private val repairCountState = MutableStateFlow(0L)

    /**
     * Issue #2469 — how many bounded REPAIR retries have been scheduled after a
     * non-authoritative reconcile. Visible-for-test so a test can assert the
     * repair ladder ran and that it is bounded (never a hot loop).
     */
    public val repairCount: StateFlow<Long> = repairCountState.asStateFlow()

    private var drainJob: Job? = null

    /**
     * True after a structural event has been offered and is waiting for (or
     * is inside) a reconcile. Visible-for-test so a test can assert the burst
     * fully drained.
     */
    @Volatile
    public var hasPending: Boolean = false
        private set

    /**
     * Start the drain loop in [scope]. In production [scope] is dispatched on
     * a background dispatcher (`Dispatchers.Default`) so the reconcile work
     * runs off the main thread. Idempotent: a second call cancels the prior
     * drain and starts a fresh one (used when the host re-binds clients).
     */
    public fun start(scope: CoroutineScope) {
        drainJob?.cancel()
        drainJob = scope.launch {
            // Issue #2469: repair attempts consumed for the structural change
            // currently being chased. Reset by the first AUTHORITATIVE reconcile
            // (and when the ladder is exhausted), so the budget is per-event and
            // a persistently failing link cannot spin.
            var repairAttempt = 0
            while (isActive) {
                // Step 1: wait for at least one trigger. The conflated channel
                // returns immediately if a trigger is already pending.
                triggers.receive()
                hasPending = false
                // Step 2: run the reconcile exactly once for this window.
                val authoritative = try {
                    reconcile()
                } catch (t: CancellationException) {
                    throw t
                } catch (t: Throwable) {
                    onReconcileError(t)
                    false
                } finally {
                    reconcileCountState.value = reconcileCountState.value + 1
                }
                // Step 3: hold the window open. Offers arriving during the
                // reconcile (step 2) or this delay are conflated into ONE
                // pending trigger, so a continued burst yields at most one
                // more reconcile next window — never O(N).
                if (windowMs > 0L) {
                    delay(windowMs)
                }
                // Step 4 (issue #2469): the reconcile did NOT reach authority —
                // on the production path the bounded `list-panes` exec timed out
                // on a degraded link. Do not drop the structural change: back off
                // and re-offer an authoritative repair, up to the ladder's size.
                // Exhausting the ladder resets the budget and parks on step 1
                // again, so this is bounded per event, never a hot retry loop.
                if (authoritative) {
                    repairAttempt = 0
                } else if (repairAttempt < repairBackoffMs.size) {
                    delay(repairBackoffMs[repairAttempt])
                    repairAttempt += 1
                    repairCountState.value = repairCountState.value + 1
                    offerAuthoritativeRepair()
                } else {
                    repairAttempt = 0
                }
            }
        }
    }

    /**
     * Offer a structural event for coalesced reconciliation. Non-blocking,
     * never suspends, never throws — safe to call from the (UI-thread) event
     * collector. Only the structural variants drive a reconcile; everything
     * else (notably [ControlEvent.Output]) is ignored here and must be
     * handled by the caller's own per-event path.
     *
     * @return true if the event was structural and a reconcile was scheduled.
     */
    public fun offer(event: ControlEvent): Boolean {
        if (!isStructural(event)) return false
        offerAuthoritativeRepair()
        return true
    }

    /** Schedule a repair when the triggering structural event itself was dropped. */
    public fun offerAuthoritativeRepair() {
        hasPending = true
        // CONFLATED + DROP_OLDEST: trySend always succeeds and the channel
        // holds at most one pending trigger, so a storm of offers does not
        // queue N reconciles.
        triggers.trySend(Unit)
    }

    /** Stop the drain loop. Safe to call multiple times. */
    public fun stop() {
        drainJob?.cancel()
        drainJob = null
    }

    public companion object {
        /**
         * One coalescing window. ~16ms ≈ one display frame at 60Hz: a burst
         * of structural events inside a single frame collapses to one
         * reconcile, which is the worst-case Codex-storm shape. Kept inside
         * the 16–60ms band the #792 design calls for and mirrors the proven
         * 60ms `tailEventsBatchedFromLine` conversation-ingest batch window.
         */
        public const val DEFAULT_WINDOW_MS: Long = 16L

        /**
         * Issue #2469 — the default bounded repair ladder for a reconcile that
         * could not reach authority (a `list-panes` enumeration that overran its
         * 6 s exec bound on a degraded link).
         *
         * Three attempts, front-loaded: the first repair lands ~0.5 s later so a
         * transient blip barely shows as staleness, and the tail (1.5 s, 4 s)
         * gives a sustained multi-second-RTT link several more chances without
         * hammering it. Worst case for ONE lost structural event is therefore
         * 4 bounded enumerations spread over ~30 s — bounded, off the UI thread,
         * on the dedicated exec lane — versus the pre-#2469 behaviour of losing
         * the event outright.
         */
        public val DEFAULT_REPAIR_BACKOFF_MS: List<Long> = listOf(500L, 1_500L, 4_000L)

        /**
         * The structural control events that require a `list-panes`
         * reconcile. Mirrors the `when` in
         * `TmuxSessionViewModel.onControlEvent`. [ControlEvent.Output] is
         * deliberately excluded — it flows straight to the terminal bridge
         * and must not be coalesced or dropped.
         */
        public fun isStructural(event: ControlEvent): Boolean = when (event) {
            is ControlEvent.WindowAdd,
            is ControlEvent.WindowClose,
            is ControlEvent.LayoutChange,
            is ControlEvent.PaneModeChanged,
            -> true

            else -> false
        }
    }
}
