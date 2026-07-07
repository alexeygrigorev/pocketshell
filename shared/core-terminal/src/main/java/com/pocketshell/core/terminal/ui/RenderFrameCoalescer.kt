package com.pocketshell.core.terminal.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/**
 * Issue #796 (Slice B of #792): collapse a burst of terminal redraw signals
 * ([com.pocketshell.core.terminal.ui.TerminalSurfaceState.renderRequests]) into
 * AT MOST one downstream emission per [windowMs] window, WITHOUT ever dropping
 * the final settled frame.
 *
 * ## Why this exists
 *
 * `28ef6681` (Slice A) coalesced the **structural** `%layout-change` storm via
 * [com.pocketshell.core.tmux.LayoutChangeCoalescer] but deliberately left the
 * `%output` path untouched — that path drives the terminal emulator append +
 * the [com.termux.view.TerminalView.onScreenUpdated] repaint and the
 * per-render full-viewport URL / file-path / engine-command scans, all on the
 * main thread, once per emulator tick. A Codex token-stream / alt-screen redraw
 * burst fires `renderRequests` uncoalesced, so N emulator ticks become N
 * repaints + N viewport scans back-to-back on the UI thread → the keyboard-up
 * ANR the maintainer still saw on v0.4.4 (#796).
 *
 * This operator sits between `renderRequests` and the UI-thread render
 * consumers in [TerminalSurface]. It mirrors the proven
 * [com.pocketshell.core.tmux.LayoutChangeCoalescer] shape:
 *
 *  - A conflated channel ([BufferOverflow.DROP_OLDEST], capacity 1) collapses a
 *    storm of source emissions arriving between two frame ticks into a single
 *    pending trigger.
 *  - The drain loop emits ONCE, then [delay]s [windowMs] (≈ one display frame).
 *    Any source emissions during the emit + delay are conflated into ONE pending
 *    trigger, so a continued burst yields at most ~1 downstream emission per
 *    window — never O(N).
 *  - The FINAL frame after a burst settles is never dropped: the conflated
 *    channel always retains the most-recent trigger, so after the last source
 *    emission the loop runs one more downstream emission. This is the
 *    last-frame-after-idle guarantee — the settled cursor / spinner state MUST
 *    still paint when the burst ends (mirrors `LayoutChangeCoalescer`'s
 *    "always reconcile after idle"). A dropped final frame would be a
 *    regression.
 *
 * The operator is pure (no `android.*`) and virtual-clock testable: collect it
 * inside a `runTest` and advance virtual time across windows to assert the
 * downstream emission count collapses while the final emission still fires.
 *
 * ## Drain-priority gate (issue #1286)
 *
 * The downstream repaint the emitted signal drives (`TerminalView.onScreenUpdated()`
 * → an on-main `onDraw` of the whole grid, plus the per-frame viewport affordance
 * extraction) runs on the SAME main looper as the frame-budgeted VT-append drain
 * ([com.pocketshell.core.terminal.bridge.MainThreadDrainScheduler]). During a Codex
 * `%output` burst those per-frame repaints STEAL main-thread slices from the drain,
 * so the append throughput falls and the burst tail never finishes within the frame
 * budget — the main thread pins for seconds (the #1286 ANR) AND the pane can be left
 * showing stale/partial content because the tail that would paint the settled frame
 * never drains.
 *
 * [backlogWindowMs] closes that WITHOUT freezing the screen or breaking affordances:
 * while the drain is backlogged it returns a WIDER coalescing window, so the repaint
 * (and the affordance extraction the same signal drives) fires LESS often — handing
 * the frame-budgeted drain more contiguous main-thread frames to finish the burst
 * (drain priority). It does NOT suppress repaints entirely: the screen keeps updating
 * during the burst (at the widened cadence, ~15fps instead of ~60fps) and tappable
 * URL/path/command affordances keep refreshing, so this fixes the freeze/ANR AND the
 * stale/blank face (the settled frame still paints) without introducing a
 * repaints-frozen-for-the-whole-burst or affordances-dead regression. Once the drain
 * catches up ([backlogWindowMs] returns the base [windowMs]) the ~60fps repaint
 * resumes. Default returns [windowMs] unconditionally — the exact pre-#1286 behaviour,
 * so plain-SSH surfaces and every existing test are UNCHANGED.
 *
 * @param windowMs the coalescing window. ~16ms ≈ one 60Hz display frame: a
 *   burst of redraw signals inside a single frame collapses to one repaint.
 *   Mirrors [com.pocketshell.core.tmux.LayoutChangeCoalescer.DEFAULT_WINDOW_MS].
 * @param backlogWindowMs issue #1286 drain-priority window. Evaluated after each
 *   emission to size the NEXT coalescing window: return a value WIDER than [windowMs]
 *   while the VT-append drain is backlogged (fewer repaints/sec → the drain gets more
 *   main-thread frames), and [windowMs] once it has caught up. Clamped to be never
 *   shorter than [windowMs]. Defaults to always [windowMs] (pre-#1286 behaviour).
 */
internal fun Flow<Unit>.coalescePerFrame(
    windowMs: Long = RENDER_FRAME_WINDOW_MS,
    backlogWindowMs: () -> Long = { windowMs },
): Flow<Unit> {
    val source = this
    return flow {
        // Conflated trigger channel: a storm of upstream emissions between two
        // frame windows collapses to a single pending trigger (DROP_OLDEST keeps
        // the latest), so the drain loop can never emit more than once per window
        // it observes. capacity 1 + DROP_OLDEST is exactly the conflation
        // LayoutChangeCoalescer uses.
        val triggers = Channel<Unit>(
            capacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

        coroutineScope {
            // Feed every upstream redraw signal into the conflated channel from a
            // child coroutine, so the drain loop below can suspend in delay()
            // while the burst keeps arriving (exactly the production
            // background-vs-storm shape). When the upstream completes we close
            // the channel so the drain loop drains the final pending trigger and
            // ends cleanly.
            val pump = launch {
                try {
                    source.collect { triggers.trySend(Unit) }
                } finally {
                    triggers.close()
                }
            }

            // Drain loop: emit at most once per window, never dropping the final.
            try {
                while (true) {
                    // Step 1: wait for at least one trigger. The conflated channel
                    // returns immediately if a trigger is already pending, so the
                    // first signal after idle has no added latency.
                    // receiveCatching returns a closed result once the pump has
                    // finished AND the channel is drained — the clean-completion
                    // exit that still delivers the last pending trigger first.
                    val received = triggers.receiveCatching()
                    if (received.isClosed) break

                    // Step 2: emit exactly one downstream frame for this window.
                    emit(Unit)

                    // Step 3: hold the window open. Emissions arriving during the
                    // emit (step 2) or this delay are conflated into ONE pending
                    // trigger, so a continued burst yields at most one more
                    // emission next window — never O(N). The pending trigger that
                    // survives the burst is what guarantees the final settled
                    // frame paints.
                    //
                    // Issue #1286: size this window by [backlogWindowMs]. While the
                    // VT-append drain is backlogged it returns a WIDER window, so the
                    // repaint/affordance-extraction this signal drives fires less often
                    // and the frame-budgeted drain gets more contiguous main-thread
                    // frames (drain priority) — without ever suppressing the repaint, so
                    // the screen stays live and affordances keep refreshing during the
                    // burst. Clamp to at least [windowMs] so a bad predicate can never
                    // TIGHTEN the window into an O(N) storm.
                    val holdMs = backlogWindowMs().coerceAtLeast(windowMs)
                    if (holdMs > 0L) {
                        delay(holdMs)
                    }
                }
            } catch (t: CancellationException) {
                throw t
            } finally {
                pump.cancel()
            }
        }
    }
}

/**
 * One render-coalescing window. ~16ms ≈ one display frame at 60Hz: a burst of
 * redraw signals inside a single frame collapses to one repaint, which is the
 * worst-case Codex-`%output`-storm shape. Mirrors
 * [com.pocketshell.core.tmux.LayoutChangeCoalescer.DEFAULT_WINDOW_MS].
 */
internal const val RENDER_FRAME_WINDOW_MS: Long = 16L

/**
 * Issue #1286 — the WIDENED render-coalescing window used while the frame-budgeted
 * VT-append drain is backlogged (a Codex `%output` burst is still draining). At 64 ms
 * (~15 fps) the repaint + affordance extraction the render signal drives fires ~4× less
 * often than the base 16 ms window, so the frame-budgeted drain gets ~4× more contiguous
 * main-thread frames to finish the burst (drain priority) — enough to erase the ~25%
 * append-throughput drop that #1260's per-frame repaint introduced and that, with the
 * composer/IME amplifier, tipped into the >5 s ANR. Still repaints ~15 fps, so the
 * screen stays live and tappable affordances keep refreshing during the burst (no
 * frozen/blank pane, no dead links). Reverts to [RENDER_FRAME_WINDOW_MS] the instant the
 * drain catches up.
 */
internal const val DRAIN_PRIORITY_WINDOW_MS: Long = 64L
