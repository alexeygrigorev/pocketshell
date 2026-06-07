package com.pocketshell.core.terminal.selection

import android.util.Patterns

/**
 * Pure, Android-light link detection for the conversation view (issue #557),
 * giving the conversation tab tap-parity with the terminal tab.
 *
 * The terminal tab detects file paths ([detectFilePathsInLine]) and URLs
 * ([findVisibleUrls]) on the live grid and routes a tap to the file viewer /
 * port-forward / browser. The conversation tab renders agent text as Markdown,
 * so it has no grid — it works on plain strings. This detector reuses the SAME
 * path regex + URL pattern as the terminal so behaviour is consistent across
 * both tabs, and adds a conservative *directory* shape (terminal tap-to-view is
 * file-only; the conversation issue also wants a directory to open the remote
 * file browser).
 *
 * Only the detection lives here (Android-free apart from [Patterns.WEB_URL]);
 * the Compose annotation + tap wiring lives in the app's conversation
 * composable, and path resolution/normalisation is shared via
 * [RemotePathResolver]/[PathNormalizer].
 */

/** What a detected conversation link points at, deciding the tap sink. */
public enum class ConversationLinkKind {
    /** A file → open the in-app file viewer. */
    FILE,

    /** A directory → open the remote file browser at that path. */
    DIRECTORY,

    /** An `http(s)` URL → open externally / in the port-forward flow. */
    URL,
}

/**
 * A link detected inside a conversation text run.
 *
 * @property text the literal target (path or URL) with trailing sentence
 *   punctuation stripped.
 * @property start inclusive char index in the scanned string.
 * @property endExclusive exclusive char index in the scanned string.
 * @property kind the tap sink to route to.
 */
public data class ConversationLink(
    val text: String,
    val start: Int,
    val endExclusive: Int,
    val kind: ConversationLinkKind,
)

/**
 * Detect every tappable URL / file path / directory in [line], left-to-right
 * and non-overlapping. URLs are detected first and claim their span so a URL's
 * `/path` tail is never re-surfaced as a file path; file paths are detected next
 * (reusing the terminal's [detectFilePathsInLine]); directories last, over the
 * spans neither claimed.
 *
 * Conservative by design — the same false-positive guards as the terminal
 * scanners apply, and directories require an explicit root prefix (`/`, `~`,
 * `./`, `../`) so a bare prose word like `make/clean` is not surfaced.
 */
public fun detectConversationLinks(line: String): List<ConversationLink> {
    if (line.isEmpty()) return emptyList()
    val claimed = mutableListOf<IntRange>()
    val out = mutableListOf<ConversationLink>()

    fun overlapsClaimed(start: Int, endExclusive: Int): Boolean =
        claimed.any { start < it.last + 1 && it.first < endExclusive }

    fun addCandidate(candidate: ConversationScanText) {
        // 1. URLs (schemed http/https + loopback literals), reusing the
        //    terminal URL shapes.
        for (url in detectUrlsInLine(candidate.text)) {
            val start = candidate.originalOffset(url.start) ?: continue
            val endExclusive = candidate.originalEndExclusive(url.endExclusive) ?: continue
            if (overlapsClaimed(start, endExclusive)) continue
            claimed += start until endExclusive
            out += ConversationLink(url.text, start, endExclusive, ConversationLinkKind.URL)
        }

        // 2. File paths — exactly the terminal's conservative detector,
        //    excluding any URL spans already claimed.
        val urlSpans = out.filter { it.kind == ConversationLinkKind.URL }
            .map { it.start until it.endExclusive }
        for (path in detectFilePathsInLine(candidate.text, candidate.toScannedRanges(urlSpans))) {
            val start = candidate.originalOffset(path.start) ?: continue
            val endExclusive = candidate.originalEndExclusive(path.endExclusive) ?: continue
            if (overlapsClaimed(start, endExclusive)) continue
            claimed += start until endExclusive
            out += ConversationLink(path.path, start, endExclusive, ConversationLinkKind.FILE)
        }

        // 3. Directories — explicit-root paths without a known file extension.
        for (dir in detectDirectoriesInLine(candidate.text)) {
            val start = candidate.originalOffset(dir.start) ?: continue
            val endExclusive = candidate.originalEndExclusive(dir.endExclusive) ?: continue
            if (overlapsClaimed(start, endExclusive)) continue
            claimed += start until endExclusive
            out += ConversationLink(dir.path, start, endExclusive, ConversationLinkKind.DIRECTORY)
        }
    }

    val compacted = ConversationScanText.compactSoftBreaks(line)
    if (compacted != null) {
        addCandidate(compacted)
    }
    addCandidate(ConversationScanText.original(line))

    return out.sortedBy { it.start }
}

private data class ConversationScanText(
    val text: String,
    val originalOffsets: IntArray,
) {
    fun originalOffset(scannedOffset: Int): Int? =
        originalOffsets.getOrNull(scannedOffset)

    fun originalEndExclusive(scannedEndExclusive: Int): Int? {
        if (scannedEndExclusive <= 0) return null
        return originalOffsets.getOrNull(scannedEndExclusive - 1)?.plus(1)
    }

    fun toScannedRanges(originalRanges: List<IntRange>): List<IntRange> =
        originalRanges.mapNotNull { range ->
            val start = originalOffsets.indexOfFirst { it >= range.first }
            if (start < 0) return@mapNotNull null
            val endExclusive = originalOffsets.indexOfFirst { it >= range.last + 1 }
                .let { if (it < 0) originalOffsets.size else it }
            if (start >= endExclusive) null else start until endExclusive
        }

    companion object {
        fun original(text: String): ConversationScanText =
            ConversationScanText(text, IntArray(text.length) { it })

        fun compactSoftBreaks(text: String): ConversationScanText? {
            if (text.indexOf('\n') < 0 && text.indexOf('\r') < 0) return null
            val compacted = StringBuilder(text.length)
            val offsets = ArrayList<Int>(text.length)
            var index = 0
            var changed = false
            while (index < text.length) {
                val ch = text[index]
                if (ch == '\r' || ch == '\n') {
                    var next = index + 1
                    if (ch == '\r' && text.getOrNull(next) == '\n') next += 1
                    var afterIndent = next
                    while (text.getOrNull(afterIndent) == ' ' || text.getOrNull(afterIndent) == '\t') {
                        afterIndent += 1
                    }
                    if (afterIndent > next) {
                        changed = true
                        index = afterIndent
                        continue
                    }
                }
                compacted.append(ch)
                offsets += index
                index += 1
            }
            return if (changed) ConversationScanText(compacted.toString(), offsets.toIntArray()) else null
        }
    }
}

/** A `(text, start, endExclusive)` URL match on a plain string. */
private data class DetectedUrl(val text: String, val start: Int, val endExclusive: Int)

/**
 * Detect schemed `http(s)` URLs (framework pattern) plus loopback-literal
 * host/port references (`http://localhost:3000`, `localhost:5173`,
 * `http://[::1]:9000`, ...) on [line], with trailing sentence punctuation
 * stripped — the same set the terminal's [findVisibleUrls] surfaces, but over a
 * plain string instead of the grid.
 */
private fun detectUrlsInLine(line: String): List<DetectedUrl> {
    val out = mutableListOf<DetectedUrl>()
    val claimedStarts = mutableSetOf<Int>()

    val matcher = Patterns.WEB_URL.matcher(line)
    while (matcher.find()) {
        val start = matcher.start()
        var raw = line.substring(start, extendSchemedUrlEnd(line, matcher.end()))
        if (!(raw.startsWith("http://", ignoreCase = true) ||
                raw.startsWith("https://", ignoreCase = true))
        ) {
            continue
        }
        var endTrim = raw.length
        while (endTrim > 0 && raw[endTrim - 1] in CONVO_URL_TRAILING_PUNCTUATION) endTrim--
        if (endTrim <= 0) continue
        if (endTrim != raw.length) raw = raw.substring(0, endTrim)
        claimedStarts += start
        out += DetectedUrl(raw, start, start + raw.length)
    }

    for (reference in detectLocalhostPortReferences(line)) {
        val start = reference.start
        if (start in claimedStarts) continue
        out += DetectedUrl(reference.text, start, reference.endExclusive)
    }
    return out
}

/** A `(path, start, endExclusive)` directory match. */
private data class DetectedDirectory(val path: String, val start: Int, val endExclusive: Int)

/**
 * Detect directory paths in [line]: an explicit-root path (`/...`, `~`, `~/...`,
 * `./...`, `../...`) made of path segments that does NOT end in a known file
 * extension. A trailing slash is allowed and stripped from the matched span's
 * tail like any sentence punctuation.
 *
 * Requiring an explicit root keeps this conservative — a project-relative
 * `src/main` is too prose-like to surface as a directory, whereas `~/git/repo`
 * or `/var/log` clearly is a directory reference.
 */
private fun detectDirectoriesInLine(line: String): List<DetectedDirectory> {
    if (line.isEmpty()) return emptyList()
    val out = mutableListOf<DetectedDirectory>()
    val matcher = DIRECTORY_PATTERN.matcher(line)
    while (matcher.find()) {
        var raw = matcher.group() ?: continue
        var endTrim = raw.length
        while (endTrim > 0 && raw[endTrim - 1] in DIR_TRAILING_PUNCTUATION) endTrim--
        if (endTrim <= 0) continue
        if (endTrim != raw.length) raw = raw.substring(0, endTrim)
        // A bare root prefix with no body (`~`, `.`, `..`, `/`) is not a useful
        // directory link.
        if (raw == "~" || raw == "." || raw == ".." || raw == "/") continue
        // Defensive: never claim a token that ends in a known file extension —
        // that is a file, handled by the file detector.
        if (endsWithKnownFileExtension(raw)) continue
        out += DetectedDirectory(raw, matcher.start(), matcher.start() + raw.length)
    }
    return out
}

private fun endsWithKnownFileExtension(token: String): Boolean {
    val name = token.trimEnd('/')
    val dot = name.lastIndexOf('.')
    if (dot < 0 || dot == name.length - 1) return false
    val slash = name.lastIndexOf('/')
    if (dot < slash) return false // the dot is in a parent segment, not the leaf
    return name.substring(dot + 1).lowercase() in FILE_PATH_EXTENSIONS
}

private val CONVO_URL_TRAILING_PUNCTUATION: Set<Char> = setOf(
    '.', ',', ';', ':', ')', ']', '!', '?', '\'', '"', '>', '<',
)

private val DIR_TRAILING_PUNCTUATION: Set<Char> = setOf(
    '.', ',', ';', ':', ')', ']', '}', '!', '?', '\'', '"', '>', '<',
)

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

/**
 * Directory shape: an explicit-root path with at least one path segment.
 *
 * - Absolute: `/var/log`, `/home/me/git/repo`, `/`.
 * - `~`-rooted: `~`, `~/git/pocketshell`.
 * - `./` `../`-rooted: `./build`, `../docs`.
 *
 * Allows a trailing `/`. The leading negative lookbehind keeps it from starting
 * inside a larger token; the file-extension check in
 * [detectDirectoriesInLine] discards anything that is actually a file.
 */
private val DIRECTORY_PATTERN: java.util.regex.Pattern = java.util.regex.Pattern.compile(
    "(?<![\\w/.~-])" +
        "(?:" +
        // Absolute: a leading slash plus zero or more `/seg`.
        "/(?:[\\w.-]+(?:/[\\w.-]+)*/?)?" +
        "|" +
        // ~ / ~/seg/seg...
        "~(?:/[\\w.-]+)*/?" +
        "|" +
        // ./ ../ then at least one segment.
        "\\.{1,2}/[\\w.-]+(?:/[\\w.-]+)*/?" +
        ")",
)
