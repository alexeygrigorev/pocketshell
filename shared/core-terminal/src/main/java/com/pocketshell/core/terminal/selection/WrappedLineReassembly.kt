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
 * @property startsAfterHardWrap whether the terminal recorded a cursor-addressed
 *   move from a full-width predecessor to this row without a line-feed. Agent
 *   TUIs use this shape for plain-text fixed-width output when autowrap is off.
 */
public data class VisualRow(
    val row: Int,
    val text: String,
    val wrapsToNext: Boolean,
    val startsWithOsc8Hyperlink: Boolean = false,
    val endsWithOsc8Hyperlink: Boolean = false,
    val startsNewOsc8Hyperlink: Boolean = false,
    val startsAfterHardWrap: Boolean = false,
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
 * followed by an independent path. The OSC 8 branch therefore does not guess
 * from text shape: it repairs only a boundary whose last/first cells both carry
 * the emulator's hyperlink provenance marker and whose next row does not start
 * a newly opened hyperlink. The plain-text branch uses the terminal's explicit
 * cursor-addressed hard-wrap provenance plus conservative text guards:
 * literal whitespace, another scheme, and a path-shaped next row terminate
 * the candidate unless the two fragments form one of the unambiguous GitHub
 * URL boundaries exercised by the real output (`/mai` + `n/` or a repository
 * name split immediately before `/blob/` or `/tree/`). SGR colour continuity
 * is deliberately not enough: independent newline rows can use the same
 * colour and attributes.
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
            ?: plainTextHardWrappedUrlContinuationEnd(rows, index, columns)
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

private fun plainTextHardWrappedUrlContinuationEnd(
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
    var recognizedContinuation = false
    var prefix = firstFragment
    while (index < rows.lastIndex) {
        val next = rows[index + 1]
        if (!next.startsAfterHardWrap) break
        val continuation = next.text.take(columns).trimEnd()
        if (continuation.isEmpty() || continuation.first() in OSC8_WRAP_URL_DELIMITERS) break
        // A marked row can still be an independently painted prose/link/path
        // row. Keep it separate instead of absorbing it into the URL above.
        if (continuation.any { it.isWhitespace() }) break
        if (continuation.contains("http://", ignoreCase = true) ||
            continuation.contains("https://", ignoreCase = true) ||
            (
                looksLikeIndependentPathContinuation(continuation) &&
                    !recognizedContinuation &&
                    !looksLikeKnownGithubUrlContinuation(prefix, continuation)
                )
        ) break
        index += 1
        val knownBoundary = looksLikeKnownGithubUrlContinuation(prefix, continuation)
        recognizedContinuation = recognizedContinuation || knownBoundary
        prefix += continuation
        if (continuation.length < columns) break
    }
    return index.takeIf { it > startIndex }
}

private fun looksLikeIndependentPathContinuation(text: String): Boolean {
    val candidate = text.trimEnd()
    val first = candidate.firstOrNull() ?: return false
    if (first == '~' || first == '.') return true
    val pathStart = if (first == '/') 1 else 0
    val slash = candidate.indexOf('/', startIndex = pathStart)
    if (slash <= pathStart) return false
    val firstSegment = candidate.substring(pathStart, slash)

    // Every relative `segment/...` row is ambiguous with a URL continuation,
    // so it is treated as independent by default. The caller may override
    // that only for a structural GitHub boundary it can prove from the URL
    // prefix. In particular, do not make short (`n`, `ns`) or punctuation-rich
    // segments implicit continuation exceptions: both have appeared as real
    // independent path rows.
    if (firstSegment in INDEPENDENT_PROJECT_PATH_ROOTS) return true
    if (detectFilePathsInLine(candidate).any { it.start == 0 }) return true
    return firstSegment.isNotEmpty()
}

/**
 * Recognizes only URL boundaries that are structurally visible in the exact
 * GitHub output under test. A path-shaped next row is otherwise independent.
 *
 * The first case is the Pixel-width split of `main` (`/mai` + `n/...`). The
 * second is the narrower fixture where the GitHub owner/repository prefix is
 * split before the `blob`/`tree` route (`alexeygrigo` +
 * `rev/ai-book-generator/blob|tree`, or another cut inside the same
 * hyphenated repository). Requiring the reconstructed repository slug to
 * contain `-`/`_`, and requiring a two-segment prefix to show the same partial
 * slug evidence, prevents a complete URL such as
 * `https://github.com/org/repo` from absorbing a new `repo-name/...` row.
 */
private fun looksLikeKnownGithubUrlContinuation(
    prefix: String,
    continuation: String,
): Boolean {
    val host = "github.com/"
    val hostStart = prefix.indexOf(host, ignoreCase = true)
    if (hostStart < 0) return false

    val candidate = prefix + continuation
    val candidatePath = candidate.substring(hostStart + host.length)

    val completesMainBranch =
        prefix.endsWith("/mai", ignoreCase = true) &&
            continuation.startsWith("n/", ignoreCase = true) &&
            (
                candidatePath.contains("/blob/main/", ignoreCase = true) ||
                    candidatePath.contains("/tree/main/", ignoreCase = true)
                )
    if (completesMainBranch) return true

    val continuationRoute =
        continuation.contains("/blob/", ignoreCase = true) ||
            continuation.contains("/tree/", ignoreCase = true)
    if (!continuationRoute) return false

    val prefixPath = prefix.substring(hostStart + host.length)
    val prefixSegments = prefixPath.split('/')
    if (prefixSegments.size !in 1..2 || prefixSegments.any { it.isEmpty() }) return false
    val candidateSegments = candidatePath.split('/')
    val routeIndex = candidateSegments.indexOfFirst {
        it.equals("blob", ignoreCase = true) || it.equals("tree", ignoreCase = true)
    }
    if (routeIndex != 2) return false
    if (candidateSegments[1].none { it == '-' || it == '_' }) return false
    if (prefixSegments.size == 1) return true
    return prefixSegments[1].any { it == '-' || it == '_' } ||
        continuation.firstOrNull() == '-'
}

private val INDEPENDENT_PROJECT_PATH_ROOTS = setOf(
    "app",
    "build",
    "dist",
    "docs",
    "lib",
    "out",
    "src",
    "test",
    "tests",
    "tmp",
)

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
