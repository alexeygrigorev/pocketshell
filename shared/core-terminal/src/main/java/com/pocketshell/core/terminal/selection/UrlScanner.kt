package com.pocketshell.core.terminal.selection

import android.util.Patterns
import com.termux.view.TerminalView

/**
 * A URL detected on the visible terminal viewport with the grid coordinates
 * needed to draw an affordance over it and hit-test taps.
 *
 * Coordinates are in the same "external" row space the vendored
 * [com.termux.terminal.TerminalBuffer] uses: `row` is the absolute row index
 * including the scrollback transcript above row 0, so `row` may be negative
 * when the user has scrolled the viewport up. `startCol` is inclusive and
 * `endColExclusive` is exclusive, matching half-open Kotlin range semantics.
 *
 * Callers convert to pixel rectangles by multiplying columns by the renderer's
 * `mFontWidth` and rows by `mFontLineSpacing`, anchored to the
 * [com.termux.view.TerminalView]'s current `mTopRow`. See
 * [SmartSelectionAffordanceOverlay] for the visible affordance conversion
 * path.
 *
 * @property url the literal URL substring as it appears on screen, with any
 *   trailing sentence punctuation already stripped. Safe to pass to
 *   `Intent.ACTION_VIEW` after `Uri.parse`.
 * @property row external row index where this region's fragment lives. Issue
 *   #558 bug 2: a URL that soft-wraps across rows is reassembled into one
 *   logical match, then re-emitted as one region per visual row it covers — all
 *   sharing the SAME full [url] — so a tap on any wrapped fragment opens the
 *   complete link while hit-testing stays per-row.
 * @property startCol inclusive column where the URL begins on [row].
 * @property endColExclusive exclusive column where the URL ends on [row].
 */
public data class UrlRegion(
    val url: String,
    val row: Int,
    val startCol: Int,
    val endColExclusive: Int,
)

/**
 * Scans the currently visible viewport (plus its scrollback offset) of a
 * [TerminalView] and returns every URL the scanner can spot, with grid
 * coordinates suitable for drawing an underline overlay and hit-testing
 * taps.
 *
 * ## Why this scanner exists, separate from [DefaultTerminalMatcher]
 *
 * [DefaultTerminalMatcher] returns string-only matches against the flat
 * transcript snapshot. It does not preserve `(row, col)` coordinates and it
 * scans the whole transcript window (8 KB tail), not just the visible
 * viewport. For tap-routing we need (a) precise pixel rectangles and (b) only
 * the URLs the user can currently see — anything in scrollback they have
 * scrolled past is not eligible for tap. Trying to recover grid coordinates
 * from the matcher's substring is a non-starter: terminal lines are
 * wrap-padded with spaces, and the matcher already strips line breaks for
 * paragraph-level detection.
 *
 * The trade-off is that this scanner duplicates the URL regex shape from
 * [DefaultTerminalMatcher]. We accept the duplication because the consumers
 * differ — the matcher feeds smart-selection chips (any kind of token), the
 * scanner feeds URL tap-routing (URLs only). Keeping them separate makes
 * each consumer simpler.
 *
 * ## URL detection
 *
 * Uses [Patterns.WEB_URL] from the Android framework. That pattern matches
 * `http://` / `https://` URLs as well as bare-domain `example.com/foo` and
 * `www.example.com`. We deliberately do NOT bare-domain match here — we only
 * surface URLs that already include a scheme — because tapping on
 * `example.com` printed in agent output is far more likely to be a domain
 * mentioned in passing than something the user wants to open in a browser.
 * Schemed URLs (with `http://` or `https://`) are the only candidates the
 * scanner emits, matching the matcher's [TerminalMatch.Url] contract.
 *
 * Issue #488/#582: a second loopback-literal pass also surfaces server-local
 * URLs / host-port references the framework pattern misses —
 * `http://localhost:3000`, `localhost:5173`, `0.0.0.0:8080`,
 * `http://[::1]:9000` (and `127.0.0.1`, already covered, de-duped). These are
 * the dev-server references that must be tappable so the host can route the tap
 * into the port-forward flow instead of a dead browser open.
 *
 * Trailing sentence punctuation (`.`, `,`, `;`, `)`, `]`, `!`, `?`,
 * single/double quotes) is stripped from the matched substring's end so the
 * URL the user taps is the URL the user intended (the framework regex over-
 * eagerly captures trailing punctuation when the URL appears mid-sentence).
 *
 * ## Visible viewport
 *
 * The scanner walks rows `[topRow, topRow + emulator.mRows)` where `topRow`
 * is `TerminalView.getTopRow()`. `topRow` is non-positive on a stationary
 * terminal (0 means "at the bottom"), so negative values mean the user has
 * scrolled into transcript. Reading each row via
 * [com.termux.terminal.TerminalBuffer.getSelectedText] returns the literal
 * row content with trailing spaces preserved, which keeps column indices in
 * lockstep with what the renderer paints.
 *
 * Returns an empty list when the view has no emulator attached yet (the
 * first layout pass before [TerminalView.attachSession]), the emulator's
 * column/row size is zero, or there are no URLs on the visible screen.
 *
 * Safe to call from the UI thread. Cheap: one regex pass per visible row,
 * typically <30 rows on a phone.
 */
public fun findVisibleUrls(view: TerminalView): List<UrlRegion> {
    val emulator = view.mEmulator ?: return emptyList()
    val screen = emulator.screen ?: return emptyList()
    val columns = emulator.mColumns
    val rows = emulator.mRows
    if (columns <= 0 || rows <= 0) return emptyList()

    val topRow = view.topRow
    val firstRow = topRow
    val lastRowExclusive = topRow + rows

    // Issue #558 bug 2: read every visible row WITH its line-wrap flag so a URL
    // that the emulator soft-wrapped across rows is reassembled into one logical
    // line before matching. `getLineWrap(row)` is true when the next row
    // continues this one.
    val visualRows = mutableListOf<VisualRow>()
    for (row in firstRow until lastRowExclusive) {
        val line: String = try {
            // (selX1, selY1, selX2, selY2): inclusive-inclusive rectangle.
            // (0, row, mColumns, row) → the full row, with trailing spaces
            // preserved (joinBackLines=true is the default but only joins
            // *back* lines, not pad the right edge with extra spaces).
            screen.getSelectedText(0, row, columns, row)
        } catch (_: Throwable) {
            // Mid-resize the vendored emulator occasionally throws AIOOBE.
            // Treat as a blank, non-wrapping row rather than failing the whole
            // overlay pass — the next render request will retry.
            visualRows += VisualRow(row = row, text = "", wrapsToNext = false)
            continue
        }
        val wraps = try {
            row + 1 < lastRowExclusive && screen.getLineWrap(row)
        } catch (_: Throwable) {
            false
        }
        visualRows += VisualRow(row = row, text = line, wrapsToNext = wraps)
    }

    val out = mutableListOf<UrlRegion>()
    for (logical in reassemble(visualRows)) {
        val line = logical.text

        // Columns already claimed by a URL on this logical line, so the loopback
        // pass (below) does not re-emit a `127.0.0.1` URL the framework matcher
        // already produced.
        val claimedStarts = mutableSetOf<Int>()

        val matcher = Patterns.WEB_URL.matcher(line)
        while (matcher.find()) {
            val start = matcher.start()
            var raw = line.substring(start, extendSchemedUrlEnd(line, matcher.end()))
            // Only schemed URLs. Bare-domain matches (`example.com`) are
            // skipped — they are almost always mentioned-in-prose, not a
            // tap-to-open target.
            if (!(raw.startsWith("http://", ignoreCase = true) ||
                    raw.startsWith("https://", ignoreCase = true))) {
                continue
            }
            // Strip trailing sentence punctuation from the URL end.
            var endTrim = raw.length
            while (endTrim > 0 && raw[endTrim - 1] in URL_TRAILING_PUNCTUATION) {
                endTrim--
            }
            if (endTrim <= 0) continue
            if (endTrim != raw.length) {
                raw = raw.substring(0, endTrim)
            }
            claimedStarts += start
            // Map the (possibly wrapped) logical span back onto every visual row
            // it covers, emitting one region per row — all carrying the FULL url
            // so a tap on any fragment opens the whole link.
            emitUrlRegions(logical, raw, start, columns, out)
        }

        // Issue #488/#582: the framework `Patterns.WEB_URL` does NOT match
        // `http://localhost:3000` — `localhost` has no TLD, and `0.0.0.0` /
        // `[::1]` are not matched either. It also does not match bare
        // `localhost:5173`. Those are exactly the server-local dev-server
        // references we most need tappable so a tap can route into the
        // port-forward flow. Run a shared loopback-host pass to surface them.
        // `127.0.0.1` may already be covered by the framework pass above, so we
        // skip any candidate whose start was already claimed.
        for (reference in detectLocalhostPortReferences(line)) {
            val start = reference.start
            if (start in claimedStarts) continue
            emitUrlRegions(logical, reference.text, start, columns, out)
        }
    }
    return out
}

/**
 * Map a detected [url] occupying `[start, start+url.length)` on [logical]'s
 * concatenated text onto per-visual-row [UrlRegion]s (one per row the match
 * covers), clipping each row's columns to the live grid width. Every emitted
 * region carries the complete [url] so a tap on any wrapped fragment opens the
 * whole link (issue #558 bug 2).
 */
private fun emitUrlRegions(
    logical: LogicalLine,
    url: String,
    start: Int,
    columns: Int,
    out: MutableList<UrlRegion>,
) {
    for (span in logical.mapSpanToRows(start, start + url.length)) {
        if (span.startCol >= columns) continue
        val clippedEnd = span.endColExclusive.coerceAtMost(columns)
        if (clippedEnd <= span.startCol) continue
        out += UrlRegion(
            url = url,
            row = span.row,
            startCol = span.startCol,
            endColExclusive = clippedEnd,
        )
    }
}

private fun extendSchemedUrlEnd(line: String, initialEnd: Int): Int {
    var end = initialEnd
    while (end < line.length && line[end] !in URL_HARD_DELIMITERS) {
        end += 1
    }
    return end
}

private val URL_HARD_DELIMITERS: Set<Char> = setOf(
    ' ', '\t', '\n', '\r', '`', '"', '\'', '<', '>',
)

/** Punctuation characters stripped from URL tails — matches the smart-selection matcher. */
private val URL_TRAILING_PUNCTUATION: Set<Char> = setOf(
    '.', ',', ';', ':', ')', ']', '!', '?', '\'', '"', '>', '<',
)
