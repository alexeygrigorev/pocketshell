package com.pocketshell.app.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember

/**
 * Issue #796 (H4) — the IME-inset layout state for the tmux session screen,
 * scoped so the soft-keyboard show/hide animation's BURST of interpolated inset
 * frames does NOT recompose the render-critical subtree.
 *
 * ## Why this is a separate type (the H4 fix)
 *
 * The host-IME inset ([rememberHostImeBottomPx]) is written on EVERY inset
 * change, and the keyboard-show animation emits a burst of interpolated inset
 * frames. If a composable reads the raw `imeBottomPx` int DIRECTLY in its body
 * (the pre-fix `val imeBottomPx by rememberHostImeBottomPx()` delegate), the
 * whole enclosing restart group — the `TmuxSessionScreen` body that hosts the
 * `HorizontalPager` of terminal surfaces — re-runs on EVERY one of those frames.
 * On the main thread, stacked on a Codex `%output` render storm, that amplifies
 * toward the keyboard-up ANR (#796 §3).
 *
 * This holder applies the Compose-recommended deferred-read pattern for animated
 * insets so the per-frame inset churn never recomposes the render-critical
 * subtree:
 *
 *  - [isImeVisible] is a boolean derived via `derivedStateOf`, so reading it
 *    invalidates the reader ONLY when the inset crosses the 0 boundary (keyboard
 *    show/hide) — NOT on every interpolation frame. The chrome / composer /
 *    KeyBar that genuinely depend on keyboard-up recompose once per transition.
 *  - [panOffsetPx] is a LAMBDA that reads the raw inset at CALL time. Callers
 *    invoke it inside a `graphicsLayer { ... }` block (deferred to layout/draw),
 *    so the burst of interpolated inset frames re-runs only that GPU layer block,
 *    never a recomposition of the screen body.
 *
 * The raw [State] is intentionally NOT exposed for a direct composition read —
 * exposing only [isImeVisible] (boundary-gated) and [panOffsetPx] (deferred
 * lambda) is what keeps the render-critical subtree off the per-inset-frame
 * invalidation path. That is the H4 invariant; the
 * `Issue796ImeRecompositionProofTest` regression proof composes THIS production
 * holder and asserts a multi-frame inset burst recomposes the subtree O(1), not
 * O(N).
 */
internal class TmuxImeLayoutState(
    private val imeBottomPxState: State<Int>,
    private val navBarBottomPx: Int,
) {
    private val imeVisibleState: State<Boolean> =
        derivedStateOf { imeBottomPxState.value > 0 }

    /**
     * `true` while the soft keyboard is up. Backed by `derivedStateOf`, so reading
     * it recomposes the reader only on the keyboard show/hide boundary, never on
     * an interpolation frame.
     */
    val isImeVisible: Boolean
        get() = imeVisibleState.value

    /**
     * How far (px) to pan the terminal column up to clear the keyboard, read
     * DEFERRED. Call this inside a `graphicsLayer { ... }` block so the inset is
     * read at layout/draw time — the keyboard animation's interpolated frames then
     * re-run only the GPU pan layer, never a recomposition of the caller.
     */
    fun panOffsetPx(): Float =
        -imeKeyboardPanOffsetPx(imeBottomPxState.value, navBarBottomPx).toFloat()
}

/**
 * Builds a [TmuxImeLayoutState] from the host-window IME inset
 * ([rememberHostImeBottomPx]) and the supplied [navBarBottomPx]. The returned
 * holder is `remember`ed for the lifetime of the inset [State] so the
 * `derivedStateOf` is created once.
 */
@Composable
internal fun rememberTmuxImeLayoutState(navBarBottomPx: Int): TmuxImeLayoutState {
    val imeBottomPxState = rememberHostImeBottomPx()
    return remember(imeBottomPxState, navBarBottomPx) {
        TmuxImeLayoutState(imeBottomPxState, navBarBottomPx)
    }
}
