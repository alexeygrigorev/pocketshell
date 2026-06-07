package com.pocketshell.core.terminal.selection

import androidx.compose.ui.geometry.Offset
import com.termux.view.TerminalView

/**
 * A smart-selection match detected on a visible terminal row.
 *
 * Coordinates use the same external row space as [UrlRegion], and columns are
 * half-open terminal grid cells suitable for renderer-metric hit-testing.
 */
data class TerminalMatchRegion(
    val match: TerminalMatch,
    val row: Int,
    val startCol: Int,
    val endColExclusive: Int,
)

/**
 * Scans only the visible terminal viewport and returns match regions.
 * Span-aware matchers provide exact geometry; older value-only matchers use a
 * literal fallback so existing custom implementations remain tappable.
 *
 * Soft-wrapped rows are reassembled before matching, then each match is mapped
 * back to every visual row fragment it covers. This keeps underline decoration
 * and smart-selection hit regions aligned with the URL/path scanners: tapping
 * or recognizing a wrapped continuation row uses the same complete target as
 * the first row.
 */
fun findVisibleTerminalMatches(
    view: TerminalView,
    matcher: TerminalMatcher = DefaultTerminalMatcher(),
): List<TerminalMatchRegion> {
    val emulator = view.mEmulator ?: return emptyList()
    val screen = emulator.screen ?: return emptyList()
    val columns = emulator.mColumns
    val rows = emulator.mRows
    if (columns <= 0 || rows <= 0) return emptyList()

    val topRow = view.topRow
    val visualRows = mutableListOf<VisualRow>()
    for (row in topRow until topRow + rows) {
        val line: String = try {
            screen.getSelectedText(0, row, columns, row)
        } catch (_: Throwable) {
            visualRows += VisualRow(row = row, text = "", wrapsToNext = false)
            continue
        }
        val wraps = try {
            row + 1 < topRow + rows && screen.getLineWrap(row)
        } catch (_: Throwable) {
            false
        }
        visualRows += VisualRow(row = row, text = line, wrapsToNext = wraps)
    }
    return terminalMatchRegionsForRows(visualRows, columns, matcher)
}

internal fun terminalMatchRegionsForRows(
    visualRows: List<VisualRow>,
    columns: Int,
    matcher: TerminalMatcher,
): List<TerminalMatchRegion> {
    if (columns <= 0 || visualRows.isEmpty()) return emptyList()

    val out = mutableListOf<TerminalMatchRegion>()
    // The terminal's wrap flag is authoritative when present, but some
    // agent-emitted file paths have shown up as adjacent visual rows without a
    // wrap marker. Keep generic smart-selection affordances aligned with the
    // file-path tap overlay for the conservative generated-image/attachment
    // shapes that scanner already knows how to join.
    for (logical in reassemble(markFilePathContinuationWraps(visualRows))) {
        for (span in matchSpansForLine(logical.text, matcher)) {
            for (rowSpan in logical.mapSpanToRows(span.start, span.endExclusive)) {
                val startCol = rowSpan.startCol.coerceAtLeast(0)
                if (startCol >= columns) continue
                val endCol = rowSpan.endColExclusive.coerceAtMost(columns)
                if (endCol <= startCol) continue
                out += TerminalMatchRegion(
                    match = span.match,
                    row = rowSpan.row,
                    startCol = startCol,
                    endColExclusive = endCol,
                )
            }
        }
    }
    return out
}

internal fun matchSpansForLine(
    line: String,
    matcher: TerminalMatcher,
): List<TerminalMatchSpan> {
    if (line.isEmpty()) return emptyList()
    val spanMatcher = matcher as? TerminalSpanMatcher
    if (spanMatcher != null) return spanMatcher.matchSpans(line)

    var searchStart = 0
    return matcher.matches(line).mapNotNull { match ->
        val value = match.value
        if (value.isEmpty()) return@mapNotNull null
        var start = line.indexOf(value, searchStart)
        if (start < 0) start = line.indexOf(value)
        if (start < 0) return@mapNotNull null
        val end = start + value.length
        searchStart = end
        TerminalMatchSpan(match, start, end)
    }
}

fun hitTestTerminalMatch(
    view: TerminalView,
    regions: List<TerminalMatchRegion>,
    tapX: Float,
    tapY: Float,
): TerminalMatchRegion? =
    hitTestGridRegion(
        view = view,
        regions = regions,
        tapX = tapX,
        tapY = tapY,
        rowOf = { it.row },
        startColOf = { it.startCol },
        endColExclusiveOf = { it.endColExclusive },
    )

internal fun hitTestTerminalMatch(
    view: TerminalView,
    regions: List<TerminalMatchRegion>,
    pos: Offset,
): TerminalMatchRegion? = hitTestTerminalMatch(view, regions, pos.x, pos.y)
