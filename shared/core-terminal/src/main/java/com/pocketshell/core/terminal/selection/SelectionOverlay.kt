package com.pocketshell.core.terminal.selection

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout

/**
 * Transparent overlay that draws no visible chrome of its own but turns each
 * [TerminalMatch] into an invisible tap target sized to a fixed grid cell.
 *
 * The overlay is intentionally minimal: it does not measure text, does not
 * compute glyph positions, and does not highlight matches with colour. Visual
 * affordances (underlines, chip badges) belong to a later iteration once we
 * have a cheap way to query the underlying [com.termux.view.TerminalView]'s
 * character grid. Until then, the overlay's job is purely to wire taps from
 * the host composable to [onTap] callbacks — tests and UI integration can
 * compose a real tap-target layout on top of this.
 *
 * The overlay fills its parent and consumes any tap that lands inside it,
 * routing to [onTap] with the single best-match candidate. Selection of the
 * "best" candidate is left to the caller: this composable takes a flat
 * [matches] list and currently treats the first item as the active match.
 * Once the surface measures glyph positions (issue follow-up), this contract
 * will tighten so that the hit-tested match is returned.
 *
 * Why no `Box`/`clickable`: those primitives live in
 * `androidx.compose.foundation`, which is not a declared dependency of this
 * module. We stay strictly inside `androidx.compose.ui` so the module
 * compiles without `compose-foundation` on the classpath.
 *
 * @param matches the matches the surface most recently detected. May be empty,
 *   in which case the overlay still composes but is inert (no tap handler).
 * @param onTap invoked when the overlay is tapped, with the match that should
 *   be activated. Today the first element of [matches] is used; future
 *   revisions will hit-test the tap coordinate.
 * @param modifier the standard Compose modifier. Defaults to filling the
 *   parent so the overlay covers the same area as the terminal surface.
 */
@Composable
fun SelectionOverlay(
    matches: List<TerminalMatch>,
    onTap: (TerminalMatch) -> Unit,
    modifier: Modifier = Modifier,
) {
    // `remember` the latest matches+callback so the pointer-input lambda does
    // not allocate a new closure every recomposition. Without this the
    // pointerInput coroutine restarts whenever the parent recomposes.
    val latest = remember(matches, onTap) { LatestSelection(matches, onTap) }

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
                        latest.matches.firstOrNull()?.let { latest.onTap(it) }
                    }
                }
            }
        },
    ) { _, constraints ->
        // Zero-content layout sized to the available space. The pointerInput
        // modifier requires non-zero bounds to receive events, so we always
        // take the maximum constraints.
        layout(constraints.maxWidth.coerceAtLeast(0), constraints.maxHeight.coerceAtLeast(0)) {}
    }
}

/**
 * Internal helper class held by `remember` so the pointer-input key changes
 * (and thus the coroutine restarts) only when the surface-supplied matches or
 * callback actually change identity, not on every recomposition.
 */
private class LatestSelection(
    val matches: List<TerminalMatch>,
    val onTap: (TerminalMatch) -> Unit,
)

/**
 * Mirror of `androidx.compose.foundation.gestures.PointerEventKt.changedToDown`
 * — that helper is in `compose-foundation`, which we cannot depend on. The
 * check is: pointer was up in the previous event and is now down.
 */
private fun androidx.compose.ui.input.pointer.PointerInputChange.changedToDown(): Boolean =
    !previousPressed && pressed
