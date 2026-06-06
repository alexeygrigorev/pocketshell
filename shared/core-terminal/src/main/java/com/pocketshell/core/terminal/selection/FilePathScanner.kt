package com.pocketshell.core.terminal.selection

import android.util.Patterns
import com.termux.view.TerminalView

/**
 * A file path detected on the visible terminal viewport with the grid
 * coordinates needed to draw an affordance over it and hit-test taps.
 *
 * Coordinate semantics mirror [UrlRegion]: `row` is the absolute external row
 * index including scrollback (negative when scrolled up), `startCol` is
 * inclusive, and `endColExclusive` is exclusive.
 *
 * @property path the literal path substring as it appears on screen, with any
 *   trailing sentence punctuation already stripped. Absolute (`/home/...`),
 *   home-relative (`~/...`), and explicit-relative (`./...`, `../...`) shapes
 *   are surfaced verbatim; project-relative shapes (`out/report.png`) are also
 *   surfaced verbatim and the consumer resolves them against the session cwd.
 * @property row external row index where the path begins.
 * @property startCol inclusive column where the path begins on [row].
 * @property endColExclusive exclusive column where the path ends on [row].
 */
public data class FilePathRegion(
    val path: String,
    val row: Int,
    val startCol: Int,
    val endColExclusive: Int,
)

/**
 * A file path detected inside a single line of text, with the character span
 * the match occupies. Pure data, no terminal/grid coupling — the JVM unit
 * tests exercise [detectFilePathsInLine] directly through this type.
 *
 * @property path the matched path with trailing punctuation stripped.
 * @property start inclusive character index in the scanned line.
 * @property endExclusive exclusive character index in the scanned line.
 */
public data class DetectedFilePath(
    val path: String,
    val start: Int,
    val endExclusive: Int,
)

/**
 * Scans the currently visible viewport of a [TerminalView] for file paths the
 * user can tap to open in the in-app file viewer (issue #500).
 *
 * ## Why a separate scanner from the URL scanner and the smart-selection matcher
 *
 * [findVisibleUrls] surfaces only `http(s)` URLs (tap → browser).
 * [DefaultTerminalMatcher] surfaces paths/errors/urls as smart-selection
 * *copy* affordances over the flat transcript snapshot, and its absolute-path
 * rule deliberately allows extension-less directories (`/usr/local/bin`) so
 * the user can copy any path. Neither contract is what tap-to-open needs.
 *
 * Tap-to-open wants a **conservative** set: only tokens that look like a real
 * *file* (have a recognised file extension), so the user does not get a screen
 * full of false links over bare words, flags, fractions (`5/2`), or directory
 * names. This scanner therefore requires a file extension on every candidate,
 * which is stricter than [DefaultTerminalMatcher.ABS_PATH_PATTERN].
 *
 * ## What is matched
 *
 * Per visible row, after excluding any `http(s)` URL ranges so a URL's path
 * tail is never re-surfaced as a file path, the scanner emits:
 *
 * - Absolute paths that end in a known extension: `/home/me/out/report.png`,
 *   `/var/log/app.log`.
 * - Home-relative paths: `~/projects/foo/main.kt`.
 * - Explicit-relative paths: `./build.gradle.kts`, `../docs/readme.md`.
 * - Project-relative paths with at least one `/` and a known extension:
 *   `out/report.png`, `src/main/kotlin/Foo.kt`,
 *   `tmp/terrain/alpine-ground-hex-sheet-b03.png`. These resolve against the
 *   session cwd at tap time.
 *
 * ## What is deliberately NOT matched (false-positive avoidance)
 *
 * - Bare words and flags (`make`, `--verbose`, `-rf`) — no extension, no `/`.
 * - Bare filenames with no directory (`Foo.kt`, `report.png`) — too likely to
 *   be a word mentioned in prose; a project-relative path needs at least one
 *   `/`. (Absolute/`~`/`./` forms are exempt because the prefix already proves
 *   path intent.)
 * - Fractions / ratios / shorthand (`5/2`, `22/7`, `n/a`, `TCP/IP`,
 *   `Bytes/sec`) — no known file extension on the final segment.
 * - Unknown extensions (`foo.zzz`, `a.b`) — only the curated allowlist
 *   triggers a match.
 * - URLs and their path tails — excluded first and skipped.
 *
 * Safe to call from the UI thread. One regex pass per visible row.
 */
public fun findVisibleFilePaths(view: TerminalView): List<FilePathRegion> {
    val emulator = view.mEmulator ?: return emptyList()
    val screen = emulator.screen ?: return emptyList()
    val columns = emulator.mColumns
    val rows = emulator.mRows
    if (columns <= 0 || rows <= 0) return emptyList()

    val topRow = view.topRow
    val firstRow = topRow
    val lastRowExclusive = topRow + rows

    // Issue #558 bug 2: read every visible row WITH its line-wrap flag so a path
    // soft-wrapped across rows is reassembled into one logical line before
    // matching, then re-emitted per visual row sharing the full path string.
    val visualRows = mutableListOf<VisualRow>()
    for (row in firstRow until lastRowExclusive) {
        val line: String = try {
            screen.getSelectedText(0, row, columns, row)
        } catch (_: Throwable) {
            // Mid-resize the vendored emulator occasionally throws AIOOBE.
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

    val out = mutableListOf<FilePathRegion>()
    for (logical in reassemble(visualRows)) {
        val line = logical.text
        for (detected in detectFilePathsInLine(line, urlSpans(line))) {
            for (span in logical.mapSpanToRows(detected.start, detected.endExclusive)) {
                if (span.startCol >= columns) continue
                val clippedEnd = span.endColExclusive.coerceAtMost(columns)
                if (clippedEnd <= span.startCol) continue
                out += FilePathRegion(
                    path = detected.path,
                    row = span.row,
                    startCol = span.startCol,
                    endColExclusive = clippedEnd,
                )
            }
        }
    }
    return out
}

/**
 * Returns the [FilePathRegion] whose pixel bounding box contains the tap at
 * `(tapX, tapY)` in view-local pixels, or `null` if no path is under the
 * pointer. Mirrors [hitTestUrl].
 */
public fun hitTestFilePath(
    view: TerminalView,
    paths: List<FilePathRegion>,
    tapX: Float,
    tapY: Float,
): FilePathRegion? =
    hitTestGridRegion(
        view = view,
        regions = paths,
        tapX = tapX,
        tapY = tapY,
        rowOf = { it.row },
        startColOf = { it.startCol },
        endColExclusiveOf = { it.endColExclusive },
    )

/**
 * Pure, Android-free detection of file paths inside a single [line] of text.
 * Conservative by design — see [findVisibleFilePaths] for the full contract.
 *
 * @param line the text to scan.
 * @param excludedRanges half-open `[start, end)` character ranges to skip (e.g.
 *   spans already claimed by URL detection). A candidate whose start falls
 *   inside any excluded range is dropped.
 * @return the detected paths, left-to-right, with trailing punctuation stripped.
 */
public fun detectFilePathsInLine(
    line: String,
    excludedRanges: List<IntRange> = emptyList(),
): List<DetectedFilePath> {
    if (line.isEmpty()) return emptyList()
    val out = mutableListOf<DetectedFilePath>()
    val matcher = FILE_PATH_PATTERN.matcher(line)
    while (matcher.find()) {
        val start = matcher.start()
        if (excludedRanges.any { start in it }) continue
        var raw = matcher.group() ?: continue
        var endTrim = raw.length
        while (endTrim > 0 && raw[endTrim - 1] in PATH_TRAILING_PUNCTUATION) {
            endTrim--
        }
        if (endTrim <= 0) continue
        if (endTrim != raw.length) {
            raw = raw.substring(0, endTrim)
        }
        // After trimming, the token must still end in a known extension —
        // protects against e.g. a trailing `).` having masked the real end.
        if (!endsWithKnownExtension(raw)) continue
        out += DetectedFilePath(
            path = raw,
            start = start,
            endExclusive = start + raw.length,
        )
    }
    return out
}

/**
 * `http(s)` URL character ranges on [line], so [findVisibleFilePaths] can skip
 * a URL's `/path` tail. Uses the same Android [Patterns.WEB_URL] /
 * scheme-gate as [findVisibleUrls].
 */
private fun urlSpans(line: String): List<IntRange> {
    val spans = mutableListOf<IntRange>()
    val matcher = Patterns.WEB_URL.matcher(line)
    while (matcher.find()) {
        val raw = matcher.group() ?: continue
        if (raw.startsWith("http://", ignoreCase = true) ||
            raw.startsWith("https://", ignoreCase = true)
        ) {
            spans += matcher.start() until matcher.end()
        }
    }
    return spans
}

/**
 * Curated file-extension allowlist for tap-to-open detection. Images and
 * text-ish documents first (the viewer detects the concrete type itself), plus
 * common source/config/log shapes an agent is likely to reference. Keep this
 * conservative — every entry trades a false-negative for a possible
 * false-positive over prose.
 */
internal val FILE_PATH_EXTENSIONS: List<String> = listOf(
    // Images (the maintainer's motivating case: agent emits a PNG path).
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "ico", "tiff", "tif",
    // Text / docs / data
    "txt", "md", "rst", "log", "json", "yml", "yaml", "toml", "xml", "csv",
    "tsv", "html", "htm", "css", "scss", "ini", "conf", "cfg", "properties",
    "env", "diff", "patch", "lock", "out", "err",
    // Source
    "kt", "kts", "java", "py", "js", "jsx", "ts", "tsx", "go", "rs", "c",
    "cc", "cpp", "h", "hpp", "rb", "php", "swift", "sh", "bash", "zsh",
    "gradle", "sql", "pdf",
)

/**
 * Characters stripped from the tail of a matched path. Mirrors the URL tail
 * trimming so `out/a.png).` does not capture the closing punctuation.
 */
private val PATH_TRAILING_PUNCTUATION: Set<Char> = setOf(
    '.', ',', ';', ':', ')', ']', '}', '!', '?', '\'', '"', '>', '<',
)

private val EXTENSION_ALTERNATION: String =
    FILE_PATH_EXTENSIONS.joinToString("|") { Regex.escape(it) }

/**
 * Conservative file-path regex used by [detectFilePathsInLine].
 *
 * Three alternatives, all requiring a known file extension on the final
 * segment:
 *
 * 1. Absolute path: `/seg/seg/name.ext`.
 * 2. Prefixed relative: `~/...`, `./...`, `../...` followed by `name.ext`.
 * 3. Project-relative: `seg/.../name.ext` with at least one `/` (so a bare
 *    `Foo.kt` does not match — too prose-like).
 *
 * The leading negative lookbehind `(?<![\w/.~-])` keeps the matcher from
 * starting inside a larger token (e.g. the `path.png` tail of `x=path.png`,
 * or a URL's path that the caller also excludes separately). The trailing
 * `(?![\w])` ensures the extension is a real boundary so `app.json5` (unknown)
 * does not match as `app.json`.
 */
private val FILE_PATH_PATTERN: java.util.regex.Pattern = java.util.regex.Pattern.compile(
    "(?<![\\w/.~-])" +
        "(?:" +
        // 1. Absolute.
        "/(?:[\\w.-]+/)*[\\w.-]+\\.(?:$EXTENSION_ALTERNATION)" +
        "|" +
        // 2. ~/ ./ ../ prefixed.
        "(?:~|\\.{1,2})/(?:[\\w.-]+/)*[\\w.-]+\\.(?:$EXTENSION_ALTERNATION)" +
        "|" +
        // 3. Project-relative: at least one dir segment.
        "(?:[\\w.-]+/)+[\\w.-]+\\.(?:$EXTENSION_ALTERNATION)" +
        ")" +
        "(?![\\w])",
    // Extensions may be emitted uppercase (`.PNG`, `.JPG`); match either case.
    java.util.regex.Pattern.CASE_INSENSITIVE,
)

private fun endsWithKnownExtension(token: String): Boolean {
    val dot = token.lastIndexOf('.')
    if (dot < 0 || dot == token.length - 1) return false
    val ext = token.substring(dot + 1).lowercase()
    return ext in FILE_PATH_EXTENSIONS
}
