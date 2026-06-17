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
 * @param windowMs the coalescing window. ~16ms ≈ one 60Hz display frame: a
 *   burst of redraw signals inside a single frame collapses to one repaint.
 *   Mirrors [com.pocketshell.core.tmux.LayoutChangeCoalescer.DEFAULT_WINDOW_MS].
 */
internal fun Flow<Unit>.coalescePerFrame(windowMs: Long = RENDER_FRAME_WINDOW_MS): Flow<Unit> {
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
                    if (windowMs > 0L) {
                        delay(windowMs)
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
