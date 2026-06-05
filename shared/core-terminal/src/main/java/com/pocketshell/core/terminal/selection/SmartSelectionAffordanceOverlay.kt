package com.pocketshell.core.terminal.selection

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
 * Compose overlay that paints lightweight affordances for smart-selection
 * tokens currently visible on the embedded [com.termux.view.TerminalView].
 *
 * The overlay is purely visual. It does not install pointer handlers and it
 * does not change which tokens are tappable; URL taps still route through the
 * vendored View's gesture pipeline, and path/error taps still route through
 * [SelectionOverlay] when the host supplies a smart-selection callback.
 *
 * The treatment deliberately stays close to the terminal renderer: URLs keep
 * the existing 2 px cyan underline, while paths and errors get quieter
 * 1 px hairlines. There are no filled backgrounds, chips, or red error
 * chrome, so dense terminal output remains readable.
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
 * for matches every time the supplied [renderRequests] flow emits — the same
 * signal that drives [TerminalView.onScreenUpdated] — and whenever
 * [viewportChangeKey] changes due to scroll or terminal resize. That keeps
 * affordance regions in sync with both rendered text and viewport-only
 * movement without spinning a render-rate animation loop.
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
 *   `state.renderRequests`. Each emission triggers a fresh match scan.
 * @param viewportChangeKey any value that changes when the visible grid
 *   changes without a render request, such as scrollback position or
 *   emulator size. Each key change triggers an immediate fresh URL scan.
 * @param matcher smart-selection matcher used only to derive visible
 *   affordance spans.
 * @param onMatchesChanged invoked with the latest visible match snapshot.
 *   Hosts may reuse the same snapshot for invisible smart-selection
 *   hit-testing; receiving it here does not make this overlay interactive.
 * @param modifier standard Compose modifier; the overlay sizes itself to the
 *   modifier's measured bounds.
 */
@Composable
fun SmartSelectionAffordanceOverlay(
    view: TerminalView?,
    renderRequests: Flow<Unit>,
    viewportChangeKey: Any? = Unit,
    matcher: TerminalMatcher = DefaultTerminalMatcher(),
    onMatchesChanged: (List<TerminalMatchRegion>) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var regions by remember { mutableStateOf<List<TerminalMatchRegion>>(emptyList()) }
    val latestOnMatchesChanged by rememberUpdatedState(onMatchesChanged)

    LaunchedEffect(view, renderRequests, viewportChangeKey, matcher) {
        if (view == null) {
            latestOnMatchesChanged(emptyList())
            return@LaunchedEffect
        }
        // Initial scan so the host gets the match list before the first
        // post-render signal arrives.
        val initial = findVisibleTerminalMatches(view, matcher)
        regions = initial
        latestOnMatchesChanged(initial)
        renderRequests.collect {
            val fresh = findVisibleTerminalMatches(view, matcher)
            // Only churn the host when the list shape changes; equal lists
            // are no-ops both visually and for tap routing.
            if (fresh != regions) {
                regions = fresh
                latestOnMatchesChanged(fresh)
            }
        }
    }

    Layout(
        content = {},
        modifier = modifier.drawBehind {
            for (segment in smartSelectionAffordanceSegments(view, regions, size.width, size.height)) {
                drawRect(
                    color = segment.color,
                    topLeft = Offset(segment.left, segment.top),
                    size = Size(
                        width = segment.right - segment.left,
                        height = segment.thicknessPx,
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
 * Issue #500: paints a quiet path hairline under every tappable file path the
 * [findVisibleFilePaths] scanner reports on the visible viewport, and keeps the
 * host's [FilePathRegion] snapshot in sync for hit-testing. The actual tap
 * routing happens in the View's gesture pipeline via
 * [com.pocketshell.core.terminal.ui.PocketShellTerminalViewClient.onTapMaybeUrl].
 */
@Composable
public fun FilePathOverlay(
    view: TerminalView?,
    renderRequests: Flow<Unit>,
    viewportChangeKey: Any? = Unit,
    onFilePathsChanged: (List<FilePathRegion>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var paths by remember { mutableStateOf<List<FilePathRegion>>(emptyList()) }
    val latestOnPathsChanged by rememberUpdatedState(onFilePathsChanged)

    LaunchedEffect(view, renderRequests, viewportChangeKey) {
        if (view == null) {
            latestOnPathsChanged(emptyList())
            return@LaunchedEffect
        }
        val initial = findVisibleFilePaths(view)
        paths = initial
        latestOnPathsChanged(initial)
        renderRequests.collect {
            val fresh = findVisibleFilePaths(view)
            if (fresh != paths) {
                paths = fresh
                latestOnPathsChanged(fresh)
            }
        }
    }

    val regions = remember(paths) {
        paths.map { region ->
            TerminalMatchRegion(
                match = TerminalMatch.Path(region.path),
                row = region.row,
                startCol = region.startCol,
                endColExclusive = region.endColExclusive,
            )
        }
    }

    Layout(
        content = {},
        modifier = modifier.drawBehind {
            for (segment in smartSelectionAffordanceSegments(view, regions, size.width, size.height)) {
                drawRect(
                    color = segment.color,
                    topLeft = Offset(segment.left, segment.top),
                    size = Size(
                        width = segment.right - segment.left,
                        height = segment.thicknessPx,
                    ),
                )
            }
        },
    ) { _, constraints ->
        layout(
            constraints.maxWidth.coerceAtLeast(0),
            constraints.maxHeight.coerceAtLeast(0),
        ) {}
    }
}

internal data class SmartSelectionAffordanceSegment(
    val match: TerminalMatch,
    val left: Float,
    val top: Float,
    val right: Float,
    val thicknessPx: Float,
    val color: Color,
)

internal fun smartSelectionAffordanceSegments(
    view: TerminalView?,
    regions: List<TerminalMatchRegion>,
    canvasWidthPx: Float,
    canvasHeightPx: Float,
): List<SmartSelectionAffordanceSegment> {
    if (view == null) return emptyList()
    val renderer = view.mRenderer ?: return emptyList()
    val emulator = view.mEmulator ?: return emptyList()
    if (emulator.mColumns <= 0 || emulator.mRows <= 0) return emptyList()

    val fontWidth = renderer.fontWidth
    val lineSpacing = renderer.fontLineSpacing.toFloat()
    val rowOffsetPx = renderer.fontLineSpacingAndAscent.toFloat()
    val topRow = view.topRow
    return regions.mapNotNull { region ->
        val rowOnScreen = region.row - topRow
        if (rowOnScreen < 0) return@mapNotNull null
        val left = (region.startCol * fontWidth).coerceAtLeast(0f)
        val right = (region.endColExclusive * fontWidth).coerceAtMost(canvasWidthPx)
        if (right <= left) return@mapNotNull null
        // Match the renderer's row offset (heightOffset starts at
        // mFontLineSpacingAndAscent before the first row increment) so the
        // hairline lands on the same row the user can see.
        val rowTop = rowOffsetPx + rowOnScreen * lineSpacing
        val rowBottom = rowTop + lineSpacing
        if (rowBottom <= 0f || rowTop >= canvasHeightPx) return@mapNotNull null
        val style = affordanceStyleFor(region.match)
        val underlineY = (rowBottom - style.thicknessPx).coerceAtLeast(0f)
        SmartSelectionAffordanceSegment(
            match = region.match,
            left = left,
            top = underlineY,
            right = right,
            thicknessPx = style.thicknessPx,
            color = style.color,
        )
    }
}

internal data class SmartSelectionAffordanceStyle(
    val thicknessPx: Float,
    val color: Color,
)

internal fun affordanceStyleFor(match: TerminalMatch): SmartSelectionAffordanceStyle =
    when (match) {
        is TerminalMatch.Url -> SmartSelectionAffordanceStyle(URL_UNDERLINE_THICKNESS_PX, URL_AFFORDANCE_COLOR)
        is TerminalMatch.Path -> SmartSelectionAffordanceStyle(HAIRLINE_THICKNESS_PX, PATH_AFFORDANCE_COLOR)
        is TerminalMatch.Error -> SmartSelectionAffordanceStyle(HAIRLINE_THICKNESS_PX, ERROR_AFFORDANCE_COLOR)
    }

private val URL_AFFORDANCE_COLOR = Color(0xFF22D3EE)
private val PATH_AFFORDANCE_COLOR = Color(0x99E6EDF3)
private val ERROR_AFFORDANCE_COLOR = Color(0x66E6EDF3)

/** Thickness of the underline drawn beneath each URL region, in device pixels. */
internal const val URL_UNDERLINE_THICKNESS_PX: Float = 2f

/** Thickness of quieter token hairlines, in device pixels. */
internal const val HAIRLINE_THICKNESS_PX: Float = 1f

/**
 * Returns the [UrlRegion] whose pixel bounding box contains the tap at
 * `(tapX, tapY)` in view-local pixels, or `null` if no URL is under the
 * pointer.
 *
 * The bounding box is inclusive on the left/top edges and exclusive on the
 * right/bottom edges, matching [UrlRegion.endColExclusive] and row-cell
 * geometry.
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
): UrlRegion? =
    hitTestGridRegion(
        view = view,
        regions = urls,
        tapX = tapX,
        tapY = tapY,
        rowOf = { it.row },
        startColOf = { it.startCol },
        endColExclusiveOf = { it.endColExclusive },
    )
