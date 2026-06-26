package com.pocketshell.core.terminal.selection

import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.unit.Constraints

/**
 * Issues #958/#966/#967: lay out a draw-only / inert terminal overlay to the
 * available space, but NEVER at an unbounded dimension.
 *
 * Every terminal overlay ([SmartSelectionAffordanceOverlay], [FilePathOverlay],
 * [AgentPaneAffordanceOverlay], [EngineCommandOverlay], [SelectionOverlay]) is an
 * inert box that just needs to fill the terminal pane (a `drawBehind` overlay so
 * its hairlines paint, or a `pointerInput` overlay so it receives events). Each
 * naively called `layout(maxWidth.coerceAtLeast(0), maxHeight.coerceAtLeast(0))`,
 * which is fine under a normal bounded measure — but the overlay sits inside the
 * terminal pane, itself inside a
 * [androidx.compose.foundation.pager.Pager] (`TmuxTerminalPager`). The pager /
 * lookahead runs intermittent measure passes with an **unbounded**
 * (`Constraints.Infinity`, i.e. `Int.MAX_VALUE`) maximum dimension.
 * `coerceAtLeast(0)` leaves that `Int.MAX_VALUE` intact, so
 * `layout(width, Int.MAX_VALUE)` threw
 * `IllegalStateException: Size(<w> x 2147483647) is out of range. Each dimension
 * must be between 0 and 16777215.` — the v0.4.17 RELEASE-BLOCKING crash that tore
 * down the whole terminal/picker journey (issue #958, CI run 28184338389).
 *
 * That crash is in the SAME class as the maintainer's #967 hypothesis: an
 * uncaught render-layer exception that blanks the pane on a LIVE transport. #958
 * fixed it for the four affordance overlays only; this is the GENERAL guard so
 * any terminal measure site that can receive an unbounded constraint (e.g.
 * [SelectionOverlay], which had the identical latent `coerceAtLeast(0)` pattern)
 * cannot crash with `Size(W x Int.MAX_VALUE)`.
 *
 * When a dimension is unbounded there is no real space to fill, so we fall back
 * to the constraint's minimum (0 for a fully-loose overlay measure). A draw-only
 * overlay contributing a 0 dimension on a transient unbounded pass is correct —
 * it has nothing to paint there — and on the real bounded pass it still fills the
 * pane exactly as before.
 */
internal fun MeasureScope.layoutTerminalOverlayBounded(constraints: Constraints): MeasureResult {
    val width = if (constraints.hasBoundedWidth) {
        constraints.maxWidth
    } else {
        constraints.minWidth
    }.coerceAtLeast(0)
    val height = if (constraints.hasBoundedHeight) {
        constraints.maxHeight
    } else {
        constraints.minHeight
    }.coerceAtLeast(0)
    return layout(width, height) {}
}

/**
 * Issues #958/#966/#967: coerce a measured dimension that may be unbounded
 * (`Int.MAX_VALUE`) down to a safe `layout(width, height)` value. The terminal
 * surface's outer stacking [androidx.compose.ui.layout.Layout] takes
 * `placeables.maxOf { it.width/height }`; under the pager's unbounded lookahead
 * pass a child measured against an unbounded constraint can report a huge size,
 * which would then crash the outer `layout(...)`. Clamping each axis to the
 * Compose maximum (`0x00FFFFFF`) keeps the stack robust to that transient pass.
 */
internal fun safeLayoutDimension(value: Int): Int =
    value.coerceIn(0, MAX_LAYOUT_DIMENSION)

/**
 * The maximum dimension Compose's `layout(width, height)` accepts. A
 * `Constraints.Infinity` (`Int.MAX_VALUE`) maximum forwarded into `layout()`
 * throws `Size(W x 2147483647) is out of range. Each dimension must be between 0
 * and 16777215.` — so clamp to this ceiling.
 */
private const val MAX_LAYOUT_DIMENSION: Int = 0x00FF_FFFF
