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
 * @property startsWithOsc8Hyperlink whether column zero was emitted inside an
 *   OSC 8 hyperlink. This immutable terminal provenance is intentionally
 *   separate from visual SGR colour/style.
 * @property endsWithOsc8Hyperlink whether the final grid column was emitted
 *   inside an OSC 8 hyperlink.
 * @property startsNewOsc8Hyperlink whether column zero is the first cell after
 *   an OSC 8 opener. A true value distinguishes an adjacent independent OSC 8
 *   link from a continuation whose hyperlink remained active across the row
 *   boundary.
 */
public data class VisualRow(
    val row: Int,
    val text: String,
    val wrapsToNext: Boolean,
    val startsWithOsc8Hyperlink: Boolean = false,
    val endsWithOsc8Hyperlink: Boolean = false,
    val startsNewOsc8Hyperlink: Boolean = false,
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

/**
 * Restores URL continuation metadata for agent TUIs that hard-wrap an OSC 8
 * hyperlink but do not preserve Termux's [VisualRow.wrapsToNext] bit (#1955).
 *
 * This deliberately does **not** inspect a live terminal.  It runs over the
 * immutable, single-extraction viewport snapshot used by the off-main overlay
 * scanners (#796/#871/#1233). Text + width + a false wrap bit cannot distinguish
 * the reported `campaig` / `ns/...` split from a complete extensionless URL
 * followed by an independent path. Therefore this pass never guesses from text
 * shape. It repairs only a boundary whose last/first cells both carry the
 * emulator's OSC 8 provenance marker and whose next row does not start a newly
 * opened hyperlink. SGR colour continuity is deliberately not enough:
 * independent newline rows can use the same colour and attributes.
 */
internal fun markHardWrappedUrlContinuations(
    rows: List<VisualRow>,
    columns: Int,
): List<VisualRow> {
    if (rows.size < 2 || columns <= 0) return rows

    val out = rows.toMutableList()
    var changed = false
    var index = 0
    while (index < rows.lastIndex) {
        if (out[index].wrapsToNext) {
            index += 1
            continue
        }
        val continuationEnd = osc8HardWrappedUrlContinuationEnd(rows, index, columns)
        if (continuationEnd == null) {
            index += 1
            continue
        }
        for (joinIndex in index until continuationEnd) {
            if (!out[joinIndex].wrapsToNext) {
                out[joinIndex] = out[joinIndex].copy(wrapsToNext = true)
                changed = true
            }
        }
        index = continuationEnd
    }
    return if (changed) out else rows
}

private fun osc8HardWrappedUrlContinuationEnd(
    rows: List<VisualRow>,
    startIndex: Int,
    columns: Int,
): Int? {
    val first = rows[startIndex].text.take(columns)
    if (first.length != columns || first.lastOrNull()?.isWhitespace() != false) return null

    val httpStart = first.lastIndexOf("http://", ignoreCase = true)
    val httpsStart = first.lastIndexOf("https://", ignoreCase = true)
    val schemeStart = maxOf(httpStart, httpsStart)
    if (schemeStart < 0) return null
    val firstFragment = first.substring(schemeStart)
    if (firstFragment.any { it in OSC8_WRAP_URL_DELIMITERS }) return null

    var index = startIndex
    while (index < rows.lastIndex) {
        val current = rows[index]
        val next = rows[index + 1]
        if (
            !current.endsWithOsc8Hyperlink ||
            !next.startsWithOsc8Hyperlink ||
            next.startsNewOsc8Hyperlink
        ) {
            break
        }
        index += 1

        val continuation = next.text.takeWhile { it !in OSC8_WRAP_URL_DELIMITERS }
        val visualText = next.text.take(columns)
        val fillsGrid = visualText.length == columns && continuation.length >= columns
        if (!fillsGrid) break
    }
    return index.takeIf { it > startIndex }
}

private val OSC8_WRAP_URL_DELIMITERS: Set<Char> = setOf(
    ' ', '\t', '\n', '\r', '`', '"', '\'', '<', '>',
)
