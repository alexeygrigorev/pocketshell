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
import androidx.compose.ui.layout.Layout
import com.termux.view.TerminalView
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * The four affordance-region snapshots a shell / non-agent terminal pane needs,
 * all produced from ONE [ViewportRowsSnapshot] per coalesced frame (issue #1233).
 *
 * @property urls tappable URL regions (hit-test only; the URL hairline is drawn
 *   from the matcher's [TerminalMatch.Url] matches, mirroring the pre-#1233 split
 *   where [findVisibleUrls] fed hit-testing and the smart-selection matcher fed
 *   the underline).
 * @property filePaths tappable file-path regions (hit-test + hairline).
 * @property matches smart-selection matcher regions (hairline + optional
 *   [SelectionOverlay] tap routing).
 * @property engineCommands tappable engine-command regions (hit-test + underline).
 */
internal data class ShellPaneAffordanceRegions(
    val urls: List<UrlRegion> = emptyList(),
    val filePaths: List<FilePathRegion> = emptyList(),
    val matches: List<TerminalMatchRegion> = emptyList(),
    val engineCommands: List<EngineCommandRegion> = emptyList(),
)

/**
 * Issue #1233: the consolidated shell-pane affordance scan loop, split out of the
 * [ShellPaneAffordanceOverlay] composable so it is directly unit-testable with an
 * injected [extractSnapshot] counter and test dispatchers.
 *
 * ## What this fixes
 *
 * Before #1233 a shell / non-agent pane ran FOUR independent per-frame
 * full-viewport scanners — the URL scan plus the SmartSelection, FilePath and
 * EngineCommand overlays — each of which INDEPENDENTLY re-extracted the entire
 * visible viewport (`getSelectedText` per row) AND ran its own regex pass, all on
 * the MAIN thread, on every coalesced render frame. On a high-throughput
 * streaming shell pane with the keyboard up that ~4× redundant per-frame
 * extraction + regex kept the main thread busy enough to drop input
 * responsiveness — a milder cousin of the #796 ANR.
 *
 * ## How the single-snapshot + off-main split works (mirrors #871's
 * [AgentPaneAffordanceOverlay])
 *
 * Per coalesced frame (debounced with [conflate] so a `%output` burst collapses
 * to the latest settled snapshot):
 *  1. **On [mainContext]**: extract the visible viewport ONCE into a thread-safe
 *     [ViewportRowsSnapshot] via [extractSnapshot] (the live emulator/screen is
 *     not thread-safe, so this read stays on Main).
 *  2. **On [scanContext]** (off Main): run the enabled regex passes
 *     ([urlRegionsForRows] / [filePathRegionsForRows] / [terminalMatchRegionsForRows]
 *     / [engineCommandRegionsForRows]) against that ONE snapshot.
 *  3. **On [mainContext]**: publish the four region lists to [onResult].
 *
 * A pass is skipped (empty list, no work) when its consumer is disabled: [matcher]
 * `null`, empty [knownCommands], `scanUrls`/`scanFilePaths` `false`.
 *
 * The load-bearing invariant (issue #1233 acceptance): [extractSnapshot] is
 * invoked AT MOST ONCE per coalesced frame regardless of how many of the four
 * passes are enabled — verified with a counting fake in
 * `ShellPaneAffordanceScanCountTest`.
 */
internal suspend fun collectShellPaneAffordances(
    renderRequests: Flow<Unit>,
    extractSnapshot: () -> ViewportRowsSnapshot,
    matcher: TerminalMatcher?,
    knownCommands: Set<String>,
    scanUrls: Boolean,
    scanFilePaths: Boolean,
    mainContext: CoroutineContext,
    scanContext: CoroutineContext,
    onResult: (ShellPaneAffordanceRegions) -> Unit,
) {
    withContext(mainContext) {
        // `onStart { emit(Unit) }` runs the initial scan before the first render
        // signal arrives; `conflate()` drops intermediate emissions while a scan is
        // in flight so a `%output` burst re-extracts only the freshest settled
        // viewport (never a backlog of N scans).
        renderRequests.onStart { emit(Unit) }.conflate().collect {
            // CHEAP, on Main: ONE viewport extraction shared by all four passes.
            val snapshot = withContext(mainContext) { extractSnapshot() }
            // EXPENSIVE, OFF Main: the regex/reassembly passes that used to run
            // ~4× per frame on the UI thread.
            val result = withContext(scanContext) {
                ShellPaneAffordanceRegions(
                    urls = if (scanUrls) {
                        runCatching { urlRegionsForRows(snapshot.rows, snapshot.columns) }.getOrDefault(emptyList())
                    } else {
                        emptyList()
                    },
                    filePaths = if (scanFilePaths) {
                        runCatching { filePathRegionsForRows(snapshot.rows, snapshot.columns) }.getOrDefault(emptyList())
                    } else {
                        emptyList()
                    },
                    matches = if (matcher != null) {
                        runCatching { terminalMatchRegionsForRows(snapshot.rows, snapshot.columns, matcher) }
                            .getOrDefault(emptyList())
                    } else {
                        emptyList()
                    },
                    engineCommands = if (knownCommands.isNotEmpty()) {
                        runCatching { engineCommandRegionsForRows(snapshot.rows, snapshot.columns, knownCommands) }
                            .getOrDefault(emptyList())
                    } else {
                        emptyList()
                    },
                )
            }
            // Publish back ON Main so the recomposer / host hit-test snapshots
            // pick it up reliably.
            withContext(mainContext) { onResult(result) }
        }
    }
}

/**
 * Issue #1233: tappable URLs, file paths, smart-selection matches AND engine
 * commands on a shell / non-agent terminal pane, produced from a SINGLE
 * viewport extraction per coalesced frame, off the main thread — replacing the
 * four independent per-frame on-main scanners
 * (the old `SmartSelectionAffordanceOverlay` + `FilePathOverlay` +
 * `EngineCommandOverlay` + the URL scan) that each re-extracted the full
 * viewport and ran their regex on Main every frame.
 *
 * This is the shell-pane sibling of [AgentPaneAffordanceOverlay] (#871), which
 * applies the same single-snapshot + off-main split for the two affordances an
 * agent pane keeps (path + URL). A shell pane keeps all four.
 *
 * Each pass is gated independently:
 *  - [scanUrls] — run [urlRegionsForRows] for the URL hit-test snapshot.
 *  - [matcher] non-null — run the smart-selection matcher (draws the hairlines,
 *    including URL underlines, and feeds [SelectionOverlay] tap routing).
 *  - [scanFilePaths] — run [filePathRegionsForRows].
 *  - non-empty [knownCommands] — run [engineCommandRegionsForRows].
 *
 * The overlay draws the union of the matcher matches, file-path regions and
 * engine-command regions as affordance hairlines (identical to what the three
 * deleted overlays drew); URL regions are hit-test only (their underline comes
 * from the matcher's [TerminalMatch.Url] matches).
 *
 * @param scanDispatcher dispatcher the regex passes run on; defaults to
 *   [Dispatchers.Default]. Injected by tests to observe the dispatch thread.
 */
@Composable
public fun ShellPaneAffordanceOverlay(
    view: TerminalView?,
    renderRequests: Flow<Unit>,
    viewportChangeKey: Any? = Unit,
    matcher: TerminalMatcher? = null,
    knownCommands: Set<String> = emptySet(),
    scanUrls: Boolean = false,
    scanFilePaths: Boolean = false,
    onUrlsChanged: (List<UrlRegion>) -> Unit = {},
    onFilePathsChanged: (List<FilePathRegion>) -> Unit = {},
    onMatchesChanged: (List<TerminalMatchRegion>) -> Unit = {},
    onEngineCommandsChanged: (List<EngineCommandRegion>) -> Unit = {},
    scanDispatcher: CoroutineDispatcher = Dispatchers.Default,
    modifier: Modifier = Modifier,
) {
    var urls by remember { mutableStateOf<List<UrlRegion>>(emptyList()) }
    var paths by remember { mutableStateOf<List<FilePathRegion>>(emptyList()) }
    var matches by remember { mutableStateOf<List<TerminalMatchRegion>>(emptyList()) }
    var commands by remember { mutableStateOf<List<EngineCommandRegion>>(emptyList()) }
    val latestOnUrls by rememberUpdatedState(onUrlsChanged)
    val latestOnPaths by rememberUpdatedState(onFilePathsChanged)
    val latestOnMatches by rememberUpdatedState(onMatchesChanged)
    val latestOnCommands by rememberUpdatedState(onEngineCommandsChanged)

    LaunchedEffect(
        view,
        renderRequests,
        viewportChangeKey,
        matcher,
        knownCommands,
        scanUrls,
        scanFilePaths,
        scanDispatcher,
    ) {
        if (view == null) {
            urls = emptyList()
            paths = emptyList()
            matches = emptyList()
            commands = emptyList()
            latestOnUrls(emptyList())
            latestOnPaths(emptyList())
            latestOnMatches(emptyList())
            latestOnCommands(emptyList())
            return@LaunchedEffect
        }
        // Issue #1260 (mirrored from [AgentPaneAffordanceOverlay]): pin the collect
        // + the extract/publish to the Handler-based [Dispatchers.Main.immediate],
        // NOT the LaunchedEffect's default frame-gated AndroidUiDispatcher.Main —
        // otherwise the coalescer's `delay` window stalls for a whole `%output`
        // burst on a non-invalidating surface and the affordance never repopulates.
        val mainDispatcher = Dispatchers.Main.immediate
        collectShellPaneAffordances(
            renderRequests = renderRequests,
            extractSnapshot = { extractVisibleViewportRows(view) },
            matcher = matcher,
            knownCommands = knownCommands,
            scanUrls = scanUrls,
            scanFilePaths = scanFilePaths,
            mainContext = mainDispatcher,
            scanContext = scanDispatcher,
        ) { result ->
            if (result.urls != urls) {
                urls = result.urls
                latestOnUrls(result.urls)
            }
            if (result.filePaths != paths) {
                paths = result.filePaths
                latestOnPaths(result.filePaths)
            }
            if (result.matches != matches) {
                matches = result.matches
                latestOnMatches(result.matches)
            }
            if (result.engineCommands != commands) {
                commands = result.engineCommands
                latestOnCommands(result.engineCommands)
            }
        }
    }

    val regions = remember(matches, paths, commands) {
        buildList {
            addAll(matches)
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
            commands.forEach { region ->
                add(
                    TerminalMatchRegion(
                        match = TerminalMatch.EngineCommand(region.command),
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
        // Draw-only overlay sized to the pane, robust to the pager/lookahead's
        // intermittent UNBOUNDED-height measure pass (v0.4.17 crash) — see
        // [layoutOverlayBounded].
        layoutOverlayBounded(constraints)
    }
}
