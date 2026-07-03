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
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.unit.Constraints
import com.termux.view.TerminalView
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext

/**
 * Issue #871: tappable file paths AND URLs on an interactive-agent pane
 * (Codex/Claude), WITHOUT the per-frame main-thread regex cost that caused the
 * #803/#866/#796 ANR.
 *
 * ## Why a dedicated overlay instead of the per-frame on-main scan
 *
 * The #796-REOPENED gate turned ALL per-frame, full-viewport, **on-main**
 * affordance scanners OFF for an agent pane to kill the ANR. This overlay
 * restores the two affordances the maintainer actually wants on an agent pane
 * (file path + URL) by splitting the work:
 *
 * 1. **On the main thread, debounced on viewport-settle** (NOT every frame): read
 *    the visible rows into a thread-safe [ViewportRowsSnapshot] via
 *    [extractVisibleViewportRows] — cheap (one `getSelectedText` per visible row).
 *    [renderRequests] is [conflate]d so a `%output` burst collapses to the latest
 *    settled snapshot instead of one scan per emulator tick.
 * 2. **Off the main thread** ([scanDispatcher], default [Dispatchers.Default]):
 *    run the expensive regex/reassembly passes ([filePathRegionsForRows] +
 *    [urlRegionsForRows]) against the snapshot. This is the cost that previously
 *    stalled the UI thread; it never runs on Main here.
 * 3. Back on Main: publish the regions for the affordance hairline (drawn below)
 *    and the host's hit-test snapshots.
 *
 * The heavier/less-valuable smart-selection match + engine-command scanners stay
 * OFF for an agent pane (the conversation view, #818, is the richer agent
 * surface); only path + URL are restored here, which is the scope of #871. Issue
 * #1233 applies the SAME single-snapshot + off-main split to the SHELL-pane path
 * (all four scanners) — see [ShellPaneAffordanceOverlay].
 *
 * @param onFilePathsChanged latest visible file-path snapshot for hit-testing.
 * @param onUrlsChanged latest visible URL snapshot for hit-testing.
 * @param scanDispatcher dispatcher the regex passes run on; defaults to
 *   [Dispatchers.Default]. Injected by tests to observe the dispatch thread.
 */
@Composable
public fun AgentPaneAffordanceOverlay(
    view: TerminalView?,
    renderRequests: Flow<Unit>,
    viewportChangeKey: Any? = Unit,
    onFilePathsChanged: (List<FilePathRegion>) -> Unit,
    onUrlsChanged: (List<UrlRegion>) -> Unit,
    scanDispatcher: CoroutineDispatcher = Dispatchers.Default,
    modifier: Modifier = Modifier,
) {
    var paths by remember { mutableStateOf<List<FilePathRegion>>(emptyList()) }
    var urls by remember { mutableStateOf<List<UrlRegion>>(emptyList()) }
    val latestOnPathsChanged by rememberUpdatedState(onFilePathsChanged)
    val latestOnUrlsChanged by rememberUpdatedState(onUrlsChanged)

    LaunchedEffect(view, renderRequests, viewportChangeKey, scanDispatcher) {
        if (view == null) {
            paths = emptyList()
            urls = emptyList()
            latestOnPathsChanged(emptyList())
            latestOnUrlsChanged(emptyList())
            return@LaunchedEffect
        }
        // The Main dispatcher. The cheap viewport extraction and the Compose-state
        // publish MUST run on Main (the live emulator is not thread-safe, and an
        // off-main snapshot-state write would not notify the recomposer reliably).
        // Only the regex pass is moved OFF it onto [scanDispatcher].
        //
        // Issue #1260: drive the collect on the Handler-based
        // [Dispatchers.Main.immediate], NOT the LaunchedEffect's default Compose
        // `AndroidUiDispatcher.Main`. AndroidUiDispatcher batches its dispatch to
        // Choreographer frames; during a `%output` burst on a non-invalidating
        // surface those frames stop, so this collect stalls for the whole burst and
        // the agent-pane file-path / URL affordance never populates (the #1260
        // agent-pane regression, exposed by #1216's `renderRequests replay = 1`).
        // The main-thread Handler dispatcher is not frame-gated, so the collect
        // frame is serviced regardless. This is ALSO why `mainDispatcher` is
        // Main.immediate: `conflate()` can run the collector off the LaunchedEffect
        // context, so the extract/publish are explicitly pinned to the main thread.
        val mainDispatcher = Dispatchers.Main.immediate
        // Each render-settle (or initial composition) triggers ONE off-main scan
        // of the latest snapshot. `conflate` drops intermediate emissions while a
        // scan is in flight, so a Codex `%output` burst never queues N scans — the
        // collector always re-extracts the freshest settled viewport, never a
        // backlog. `onStart { emit(Unit) }` runs the initial scan before the first
        // render signal arrives.
        withContext(mainDispatcher) {
            renderRequests.onStart { emit(Unit) }.conflate().collect {
                // CHEAP, on Main: copy the live, non-thread-safe viewport into a
                // plain-data snapshot.
                val snapshot = withContext(mainDispatcher) { extractVisibleViewportRows(view) }
                // EXPENSIVE, OFF Main: regex + wrapped-line reassembly. This is the
                // per-frame cost that caused the #803/#866 ANR; here it never touches
                // the UI thread.
                val (freshPaths, freshUrls) = withContext(scanDispatcher) {
                    val p = runCatching { filePathRegionsForRows(snapshot.rows, snapshot.columns) }
                        .getOrDefault(emptyList())
                    val u = runCatching { urlRegionsForRows(snapshot.rows, snapshot.columns) }
                        .getOrDefault(emptyList())
                    p to u
                }
                // Publish the Compose-state diff back ON Main so the recomposer reliably
                // picks it up (and the host's hit-test snapshot / hairline update).
                withContext(mainDispatcher) {
                    if (freshPaths != paths) {
                        paths = freshPaths
                        latestOnPathsChanged(freshPaths)
                    }
                    if (freshUrls != urls) {
                        urls = freshUrls
                        latestOnUrlsChanged(freshUrls)
                    }
                }
            }
        }
    }

    val regions = remember(paths, urls) {
        buildList {
            paths.forEach { region ->
                add(
                    TerminalMatchRegion(
                        match = TerminalMatch.Path(region.path),
                        row = region.row,
                        startCol = region.startCol,
                        endColExclusive = region.endColExclusive,
                    ),
                )
            }
            urls.forEach { region ->
                add(
                    TerminalMatchRegion(
                        match = TerminalMatch.Url(region.url),
                        row = region.row,
                        startCol = region.startCol,
                        endColExclusive = region.endColExclusive,
                    ),
                )
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
        // Issue: a draw-only overlay sized to the pane — but robust to the
        // pager/lookahead's intermittent UNBOUNDED-height measure pass (the
        // v0.4.17 `Size(W x Int.MAX_VALUE)` crash). See [layoutOverlayBounded].
        layoutOverlayBounded(constraints)
    }
}

/**
 * Lay out a draw-only terminal affordance overlay at the available size — but
 * NEVER at an unbounded dimension.
 *
 * These overlays ([ShellPaneAffordanceOverlay], [AgentPaneAffordanceOverlay]) are
 * inert `drawBehind` boxes that just need to fill the terminal pane so their
 * hairlines paint. Each previously called
 * `layout(maxWidth.coerceAtLeast(0), maxHeight.coerceAtLeast(0))`, which is fine
 * under a normal bounded measure — but the overlay sits inside the terminal pane,
 * which is itself inside a [androidx.compose.foundation.pager.Pager]
 * (`TmuxTerminalPager`). The pager / lookahead runs intermittent measure passes
 * with an **unbounded** (`Constraints.Infinity`, i.e. `Int.MAX_VALUE`) maximum
 * dimension. `coerceAtLeast(0)` left that `Int.MAX_VALUE` intact, so
 * `layout(width, Int.MAX_VALUE)` threw
 * `IllegalStateException: Size(<w> x 2147483647) is out of range. Each dimension
 * must be between 0 and 16777215.` — the v0.4.17 RELEASE-BLOCKING crash that
 * tore down the whole terminal/picker journey (issue #958, CI run 28184338389).
 *
 * When a dimension is unbounded there is no real space to fill, so we fall back
 * to the constraint's minimum (0 for a fully-loose overlay measure). A draw-only
 * overlay contributing a 0 dimension on a transient unbounded pass is correct —
 * it has nothing to paint there — and on the real bounded pass it still fills the
 * pane exactly as before.
 *
 * Issue #966/#967: the helper itself now lives in [layoutTerminalOverlayBounded]
 * (in `TerminalOverlayMeasure.kt`) so EVERY terminal measure site can share the
 * unbounded-safe guard, not just the affordance overlays here.
 */
internal fun MeasureScope.layoutOverlayBounded(constraints: Constraints): MeasureResult =
    layoutTerminalOverlayBounded(constraints)

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
        // Issue #770: engine commands get the same cyan underline weight as a
        // URL so a tappable `/clear` reads as a clear affordance (it routes to
        // the composer, not the browser), distinct from the quieter path/error
        // hairlines.
        is TerminalMatch.EngineCommand ->
            SmartSelectionAffordanceStyle(URL_UNDERLINE_THICKNESS_PX, ENGINE_COMMAND_AFFORDANCE_COLOR)
    }

private val URL_AFFORDANCE_COLOR = Color(0xFF22D3EE)
private val PATH_AFFORDANCE_COLOR = Color(0x99E6EDF3)
private val ERROR_AFFORDANCE_COLOR = Color(0x66E6EDF3)
private val ENGINE_COMMAND_AFFORDANCE_COLOR = Color(0xFFA371F7)

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
 * to decide whether to route a tap to a URL or leave it as a plain terminal tap.
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
