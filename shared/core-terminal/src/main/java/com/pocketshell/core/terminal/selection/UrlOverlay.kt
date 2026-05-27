package com.pocketshell.core.terminal.selection

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import com.termux.view.TerminalView
import kotlinx.coroutines.flow.Flow

/**
 * Compose overlay that paints an underline + accent rectangle behind every
 * URL currently visible on the embedded [com.termux.view.TerminalView]. The
 * overlay is purely visual; the corresponding tap-routing happens in the
 * vendored View's gesture pipeline via
 * [com.pocketshell.core.terminal.ui.PocketShellTerminalViewClient.onSingleTapUp],
 * which consults the same scanner.
 *
 * ## Why pointer-handling lives in the View client, not here
 *
 * Initial drafts of this overlay handled URL taps in a Compose `pointerInput`
 * modifier that sat above the embedded `TerminalView`. The trouble is that
 * the vendored `TerminalView` uses an `OnGestureListener` (via
 * `GestureAndScaleRecognizer`) to detect single-taps after a confirmation
 * delay — and the Android touch dispatcher routes events straight to the
 * embedded `View` regardless of any Compose-level pointer modifier sitting
 * above it. The two channels run in parallel, so even when the overlay
 * consumed a pointer event the View's gesture detector would still fire
 * `onSingleTapUp` and the keyboard would pop up alongside the URL launch.
 *
 * The clean fix is to route URL taps through the same gesture pipeline the
 * View already uses: when the View confirms a single tap, ask the client
 * "is this a URL tap?" — if yes, swallow it; if no, summon the keyboard as
 * before. That logic lives in
 * [com.pocketshell.core.terminal.ui.PocketShellTerminalViewClient.onSingleTapUp],
 * which gets the most recent [UrlRegion] list from the surface composable
 * via the `onTapMaybeUrl` slot.
 *
 * This overlay's only job, then, is to paint the visual affordance — an
 * underline at the cell-row baseline, colored with the design-system
 * accent — so the user can see which substrings are tap-eligible.
 *
 * ## Coordinate math
 *
 * The overlay reads the renderer's `mFontWidth` and `mFontLineSpacing` from
 * the supplied [TerminalView] and converts grid (col, row) ranges to pixel
 * rectangles using the same arithmetic the renderer does:
 *
 *   x_left  = col_start * mFontWidth
 *   x_right = col_end_exclusive * mFontWidth
 *   y_top   = (external_row - mTopRow) * mFontLineSpacing
 *   y_bot   = y_top + mFontLineSpacing
 *
 * `mTopRow` is the view's current scroll offset (0 when stationary,
 * negative when scrolled into history). The overlay only paints rows whose
 * `y_top` and `y_bot` fall inside the canvas height, so URLs that scrolled
 * partially off-screen are clipped naturally.
 *
 * ## Refresh cadence
 *
 * The overlay does not poll continuously. It re-scans the visible viewport
 * for URLs every time the supplied [renderRequests] flow emits — the same
 * signal that drives [TerminalView.onScreenUpdated]. That keeps URL regions
 * in sync with the rendered text without spinning a render-rate animation
 * loop. The composable also emits the latest snapshot via [onUrlsChanged]
 * so the host can install the same list into the View client's tap-hook;
 * a single source of truth keeps the underline and tap-target always
 * agreeing about which rectangle is a URL.
 *
 * Why no dependency on `androidx.compose.foundation`: this module stays on
 * `androidx.compose.ui` only (`compose.foundation` is not a declared
 * dependency in `build.gradle.kts`) so the overlay uses [Layout] +
 * [Modifier.drawBehind] instead of `foundation.Canvas`. The visual result is
 * identical — a `DrawScope` either way — but the dependency footprint stays
 * exactly the same as [SelectionOverlay].
 *
 * @param view the embedded [TerminalView]. May be null before the first
 *   layout pass; the overlay composes an inert sized box in that case.
 * @param renderRequests the surface's redraw signal — typically
 *   `state.renderRequests`. Each emission triggers a fresh URL scan.
 * @param onUrlsChanged invoked with the latest URL snapshot whenever the
 *   scanner produces a new list. The host installs this list into the
 *   View client's tap-hook so single-tap hit-testing stays consistent with
 *   the visual affordance. Receives an empty list initially and whenever
 *   no URLs are visible.
 * @param accentColor the colour used to underline URL regions. Defaults to
 *   the design-system accent cyan (`#22D3EE`).
 * @param modifier standard Compose modifier; the overlay sizes itself to the
 *   modifier's measured bounds.
 */
@Composable
public fun UrlOverlay(
    view: TerminalView?,
    renderRequests: Flow<Unit>,
    onUrlsChanged: (List<UrlRegion>) -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = Color(0xFF22D3EE),
) {
    var urls by remember { mutableStateOf<List<UrlRegion>>(emptyList()) }

    LaunchedEffect(view, renderRequests, onUrlsChanged) {
        if (view == null) {
            onUrlsChanged(emptyList())
            return@LaunchedEffect
        }
        // Initial scan so the host gets the URL list before the first
        // post-render signal arrives.
        val initial = findVisibleUrls(view)
        urls = initial
        onUrlsChanged(initial)
        renderRequests.collect {
            val fresh = findVisibleUrls(view)
            // Only churn the host when the list shape changes; equal lists
            // are no-ops both visually and for tap routing.
            if (fresh != urls) {
                urls = fresh
                onUrlsChanged(fresh)
            }
        }
    }

    Layout(
        content = {},
        modifier = modifier.drawBehind {
            if (view == null) return@drawBehind
            val renderer = view.mRenderer ?: return@drawBehind
            val emulator = view.mEmulator ?: return@drawBehind
            if (emulator.mColumns <= 0 || emulator.mRows <= 0) return@drawBehind
            val fontWidth = renderer.fontWidth
            val lineSpacing = renderer.fontLineSpacing.toFloat()
            val rowOffsetPx = renderer.fontLineSpacingAndAscent.toFloat()
            val topRow = view.topRow
            val canvasHeightPx = size.height
            val canvasWidthPx = size.width
            for (region in urls) {
                val rowOnScreen = region.row - topRow
                if (rowOnScreen < 0) continue
                val left = (region.startCol * fontWidth).coerceAtLeast(0f)
                val right = (region.endColExclusive * fontWidth).coerceAtMost(canvasWidthPx)
                if (right <= left) continue
                // Match the renderer's row offset (heightOffset starts at
                // mFontLineSpacingAndAscent before the first row increment)
                // so the underline lands on the same row the user can see.
                val rowTop = rowOffsetPx + rowOnScreen * lineSpacing
                val rowBottom = rowTop + lineSpacing
                if (rowBottom <= 0f || rowTop >= canvasHeightPx) continue
                val underlineY = (rowBottom - UNDERLINE_THICKNESS_PX).coerceAtLeast(0f)
                drawRect(
                    color = accentColor,
                    topLeft = Offset(left, underlineY),
                    size = Size(
                        width = right - left,
                        height = UNDERLINE_THICKNESS_PX,
                    ),
                )
            }
        },
    ) { _, constraints ->
        // Inert sized box matching the available space. drawBehind requires
        // non-zero bounds to actually paint.
        layout(
            constraints.maxWidth.coerceAtLeast(0),
            constraints.maxHeight.coerceAtLeast(0),
        ) {}
    }
}

/**
 * Returns the [UrlRegion] whose pixel bounding box contains the tap at
 * `(tapX, tapY)` in view-local pixels, or `null` if no URL is under the
 * pointer.
 *
 * The bounding box is inclusive on the left/top edges and inclusive on the
 * right/bottom — a tap exactly on the right edge of a URL still counts as
 * hitting it, which keeps small targets tappable on a phone.
 *
 * Used both by the connected test (to assert the math) and at runtime by
 * [com.pocketshell.core.terminal.ui.PocketShellTerminalViewClient.onSingleTapUp]
 * to decide whether to swallow a tap or fall through to the keyboard summon.
 */
public fun hitTestUrl(
    view: TerminalView,
    urls: List<UrlRegion>,
    tapX: Float,
    tapY: Float,
): UrlRegion? {
    val renderer = view.mRenderer ?: return null
    val emulator = view.mEmulator ?: return null
    if (emulator.mColumns <= 0 || emulator.mRows <= 0) return null
    val fontWidth = renderer.fontWidth
    val lineSpacing = renderer.fontLineSpacing.toFloat()
    val rowOffsetPx = renderer.fontLineSpacingAndAscent.toFloat()
    val topRow = view.topRow
    for (region in urls) {
        val rowOnScreen = region.row - topRow
        if (rowOnScreen < 0) continue
        val left = region.startCol * fontWidth
        val right = region.endColExclusive * fontWidth
        val top = rowOffsetPx + rowOnScreen * lineSpacing
        val bottom = top + lineSpacing
        if (tapX in left..right && tapY in top..bottom) {
            return region
        }
    }
    return null
}

/**
 * Compose-friendly overload of [hitTestUrl] that takes an [Offset] instead
 * of separate `(x, y)` floats. Convenient for tests and Compose pointer
 * handlers that already have an [Offset] in hand.
 */
internal fun hitTestUrl(
    view: TerminalView,
    urls: List<UrlRegion>,
    pos: Offset,
): UrlRegion? = hitTestUrl(view, urls, pos.x, pos.y)

/** Thickness of the underline drawn beneath each URL region, in device pixels. */
internal const val UNDERLINE_THICKNESS_PX: Float = 2f
