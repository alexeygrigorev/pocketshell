package com.pocketshell.core.terminal.selection

/**
 * Reassembles soft-wrapped terminal rows into logical lines so a URL or file
 * path that the emulator wrapped across two (or more) visual rows is detected
 * as ONE target (issue #558 bug 2).
 *
 * The terminal marks a row's "line wrap" flag when the cursor ran off the right
 * edge and the next physical row is a continuation of the same logical line
 * (not a fresh `\n`). The URL/path scanners read one visual row at a time, so a
 * wrapped `https://github.com/owner/very/long/path` is split at the wrap point
 * and only the tapped fragment is captured. Joining the continued rows first,
 * matching on the logical line, then mapping each match back to the visual rows
 * it covers fixes that: any visual fragment becomes tappable and opens the FULL
 * target.
 *
 * Pure / Android-free so it is unit-tested on the JVM. The terminal scanners
 * adapt the live `TerminalView`/buffer into [VisualRow]s and consume
 * [reassemble].
 */

/**
 * One visual row of the viewport.
 *
 * @property row the external (scrollback-inclusive) row index.
 * @property text the row's literal text content (trailing spaces preserved, as
 *   `getSelectedText` returns it).
 * @property wrapsToNext `true` when the emulator's line-wrap flag is set for
 *   this row — i.e. the next visual row continues the same logical line.
 */
public data class VisualRow(
    val row: Int,
    val text: String,
    val wrapsToNext: Boolean,
)

/**
 * A contiguous group of [VisualRow]s that form one logical line, with the
 * concatenated [text] and a map from logical-line character offset back to the
 * visual `(row, col)` it lives on.
 *
 * @property rows the visual rows in top-to-bottom order.
 * @property text the rows' text concatenated with no separator (a soft wrap has
 *   no newline between the joined pieces — the next row resumes exactly where
 *   the previous one ran off the edge). Trailing padding spaces are kept so the
 *   offsets line up with the original columns; a detector strips them as usual.
 */
public data class LogicalLine(
    val rows: List<VisualRow>,
)  {
    public val text: String = rows.joinToString(separator = "") { it.text }

    /**
     * Map a half-open logical character span `[start, endExclusive)` onto the
     * visual rows it covers, returning one `(row, startCol, endColExclusive)`
     * segment per touched row. A single-row match yields one segment; a wrapped
     * match yields one segment per visual row so an overlay/hit-test can be
     * drawn on every fragment.
     */
    public fun mapSpanToRows(start: Int, endExclusive: Int): List<RowSpan> {
        if (endExclusive <= start) return emptyList()
        val out = mutableListOf<RowSpan>()
        var rowBase = 0
        for (visual in rows) {
            val rowStart = rowBase
            val rowEndExclusive = rowBase + visual.text.length
            val segStart = maxOf(start, rowStart)
            val segEnd = minOf(endExclusive, rowEndExclusive)
            if (segStart < segEnd) {
                out += RowSpan(
                    row = visual.row,
                    startCol = segStart - rowStart,
                    endColExclusive = segEnd - rowStart,
                )
            }
            rowBase = rowEndExclusive
        }
        return out
    }
}

/**
 * A per-visual-row slice of a logical-line match. Columns are within the
 * originating [row]'s text.
 */
public data class RowSpan(
    val row: Int,
    val startCol: Int,
    val endColExclusive: Int,
)

/**
 * Group [rows] into [LogicalLine]s by following the [VisualRow.wrapsToNext]
 * flag: a run of rows where each but the last wraps to the next forms a single
 * logical line. A row whose `wrapsToNext` is false ends the current logical
 * line.
 */
public fun reassemble(rows: List<VisualRow>): List<LogicalLine> {
    if (rows.isEmpty()) return emptyList()
    val out = mutableListOf<LogicalLine>()
    var current = mutableListOf<VisualRow>()
    for (visual in rows) {
        current.add(visual)
        if (!visual.wrapsToNext) {
            out += LogicalLine(current)
            current = mutableListOf()
        }
    }
    // A trailing run still marked "wraps to next" (e.g. the viewport cut off
    // mid-wrap) is emitted as its own logical line so its content is not lost.
    if (current.isNotEmpty()) out += LogicalLine(current)
    return out
}
