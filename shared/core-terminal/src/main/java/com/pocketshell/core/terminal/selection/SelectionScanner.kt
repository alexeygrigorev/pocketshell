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
 * literal, per-line fallback so existing custom implementations remain
 * tappable.
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
    val out = mutableListOf<TerminalMatchRegion>()
    for (row in topRow until topRow + rows) {
        val line: String = try {
            screen.getSelectedText(0, row, columns, row)
        } catch (_: Throwable) {
            continue
        }
        for (span in matchSpansForLine(line, matcher)) {
            val startCol = span.start.coerceAtLeast(0)
            if (startCol >= columns) continue
            val endCol = span.endExclusive.coerceAtMost(columns)
            if (endCol <= startCol) continue
            out += TerminalMatchRegion(
                match = span.match,
                row = row,
                startCol = startCol,
                endColExclusive = endCol,
            )
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
