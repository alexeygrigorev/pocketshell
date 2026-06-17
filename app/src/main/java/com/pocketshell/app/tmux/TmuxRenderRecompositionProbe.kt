package com.pocketshell.app.tmux

import androidx.compose.runtime.Composable
import androidx.compose.runtime.currentComposer
import java.util.concurrent.atomic.AtomicLong

/**
 * Issue #796 (H4) — an observability hook that counts how many times the
 * **`TmuxSessionScreen` function ROOT restart group** re-executes. That root
 * group reads the IME state and HOSTS the terminal-render subtree (the
 * `HorizontalPager` of `TerminalSurface`s / vendored `TerminalView`), so its
 * re-execution count is the faithful measure of "did an IME inset frame
 * recompose the large render-critical slice of the session screen?"
 *
 * ## Why this exists
 *
 * The H4 bug is a Compose *recomposition-scoping* defect: the host-IME inset
 * (`imeBottomPx`) was read with a delegated `by` read directly in the
 * `TmuxSessionScreen` function body, which subscribed the function ROOT group —
 * the group that hosts the terminal-render subtree — to every inset change. The
 * soft-keyboard show/hide animation emits a BURST of interpolated inset frames,
 * so every one of those frames re-ran the whole large function body on the main
 * thread; stacked on top of a Codex `%output` render storm that is the on-device
 * ANR amplifier the maintainer's keyboard-up screenshot captured.
 *
 * The fix scopes the inset read (hold the `State<Int>` object without a
 * delegated read, read it only in the pan `graphicsLayer` lambda deferred to
 * draw, derive `isImeVisible` via `derivedStateOf`) so an inset change re-runs
 * this root group ONLY on the keyboard show/hide boundary, never per
 * interpolation frame. That "the root group did NOT re-run per inset frame"
 * property is not externally observable from a Compose UI test — Compose exposes
 * no recomposition counter for an arbitrary production scope. This probe makes it
 * observable WITHOUT changing any behaviour: it is a pure counter the production
 * function body increments on each of its root-group re-executions, which the
 * #796 H4 regression proof reads to assert O(1) (not O(N)) re-executions across
 * an N-frame IME inset burst.
 *
 * ## Production cost
 *
 * One [AtomicLong.incrementAndGet] per root-group recomposition — a few
 * nanoseconds, no allocation, no behaviour change. Production code never reads
 * [count]; only instrumentation tests do. This is a deliberate, minimal
 * observability hook, not a feature flag or behaviour branch (D22-clean: there
 * is no alternate code path).
 */
internal object TmuxRenderRecompositionProbe {

    private val counter = AtomicLong(0L)

    /** The number of terminal-render-subtree recompositions observed so far. */
    val count: Long
        get() = counter.get()

    /** Reset the counter (test setup only). */
    fun reset() {
        counter.set(0L)
    }

    /**
     * Records one recomposition of the enclosing composable. Placed inside the
     * `TmuxSessionScreen` terminal-pane page so the count reflects exactly the
     * subtree the H4 fix must keep off the IME-inset invalidation path.
     *
     * Reads [currentComposer] so the Compose compiler cannot treat the call as
     * skippable/constant and elide it — the increment must run on every
     * recomposition of the caller to be a faithful counter.
     */
    @Composable
    fun Record() {
        @Suppress("UNUSED_EXPRESSION")
        currentComposer
        counter.incrementAndGet()
    }
}
