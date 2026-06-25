package com.pocketshell.core.terminal.selection

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import com.termux.view.TerminalView

/**
 * Transparent overlay that draws no visible chrome of its own but turns each
 * [TerminalMatchRegion] into an invisible tap target.
 *
 * The overlay is intentionally minimal: it does not draw highlights or other
 * visible affordances. It only hit-tests Compose pointer coordinates against
 * grid regions already derived from the underlying
 * [com.termux.view.TerminalView]'s visible viewport.
 *
 * Why no `Box`/`clickable`: those primitives live in
 * `androidx.compose.foundation`, which is not a declared dependency of this
 * module. We stay strictly inside `androidx.compose.ui` so the module
 * compiles without `compose-foundation` on the classpath.
 *
 * @param view the embedded terminal view whose renderer metrics define the
 *   regions' pixel bounds. Null before the first layout pass, making the
 *   overlay inert.
 * @param regions visible match regions. May be empty,
 *   in which case the overlay still composes but is inert (no tap handler).
 * @param onTap invoked when the overlay is tapped, with the match that should
 *   be activated.
 * @param modifier the standard Compose modifier. Defaults to filling the
 *   parent so the overlay covers the same area as the terminal surface.
 */
@Composable
fun SelectionOverlay(
    view: TerminalView?,
    regions: List<TerminalMatchRegion>,
    onTap: (TerminalMatch) -> Unit,
    modifier: Modifier = Modifier,
) {
    // `remember` the latest regions+callback so the pointer-input lambda does
    // not allocate a new closure every recomposition. Without this the
    // pointerInput coroutine restarts whenever the parent recomposes.
    val latest = remember(view, regions, onTap) { LatestSelection(view, regions, onTap) }

    Layout(
        content = {},
        modifier = modifier.pointerInput(latest) {
            awaitPointerEventScope {
                while (true) {
                    val down = awaitPointerEvent(PointerEventPass.Main)
                    // Pick the first pointer that just transitioned to
                    // pressed. We do not consume the event — the underlying
                    // TerminalView still needs to see touches for its own
                    // selection logic.
                    val pressed = down.changes.firstOrNull { it.pressed && it.changedToDown() }
                    if (pressed != null) {
                        val viewSnapshot = latest.view
                        if (viewSnapshot != null) {
                            hitTestTerminalMatch(viewSnapshot, latest.regions, pressed.position)
                                ?.let { latest.onTap(it.match) }
                        }
                    }
                }
            }
        },
    ) { _, constraints ->
        // Zero-content layout sized to the available space. The pointerInput
        // modifier requires non-zero bounds to receive events, so we take the
        // maximum constraints — but via the shared unbounded-safe guard. This
        // overlay sits inside the pager (`TmuxTerminalPager`), whose lookahead
        // runs intermittent UNBOUNDED-dimension measure passes; the old
        // `coerceAtLeast(0)` left `Int.MAX_VALUE` intact, so `layout(W, MAX)`
        // would throw the v0.4.17 `Size(W x 2147483647)` crash (#958/#966/#967 —
        // the identical latent crash, generalized).
        layoutTerminalOverlayBounded(constraints)
    }
}

/**
 * Internal helper class held by `remember` so the pointer-input key changes
 * (and thus the coroutine restarts) only when the surface-supplied matches or
 * callback actually change identity, not on every recomposition.
 */
private class LatestSelection(
    val view: TerminalView?,
    val regions: List<TerminalMatchRegion>,
    val onTap: (TerminalMatch) -> Unit,
)

/**
 * Mirror of `androidx.compose.foundation.gestures.PointerEventKt.changedToDown`
 * — that helper is in `compose-foundation`, which we cannot depend on. The
 * check is: pointer was up in the previous event and is now down.
 */
private fun androidx.compose.ui.input.pointer.PointerInputChange.changedToDown(): Boolean =
    !previousPressed && pressed
