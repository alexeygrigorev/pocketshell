package com.pocketshell.core.terminal.selection

/**
 * Smart-selection detector for terminal text.
 *
 * This file ships PocketShell's default text-classifier for the terminal
 * surface. It scans a flat string snapshot (the visible screen plus a slice of
 * scrollback) and reports back a list of [TerminalMatch] regions that the UI
 * may turn into tap targets — paths to copy, URLs to open, error lines to
 * paste into a bug report.
 *
 * ## Supported match kinds
 *
 * - [TerminalMatch.Url] — `http://` / `https://` URLs. Trailing sentence
 *   punctuation (`.`, `,`, `;`, `)`, `]`, ...) is stripped from the right edge.
 *   Non-HTTP schemes (`ftp://`, `git@github.com:...`, `ssh://`, IPv6 literals)
 *   are intentionally **not** matched — extend [TerminalMatcher] yourself if
 *   you need them.
 * - [TerminalMatch.Path] — absolute Unix paths (`/usr/local/bin/foo`) and
 *   relative paths (`src/main/kotlin/Foo.kt`, `./build.gradle`,
 *   `../README.md`, `~/projects/foo`). See "Known limitations" below for the
 *   false-positive cases the relative-path regex deliberately rejects.
 * - [TerminalMatch.Error] — stack-trace frames (Java/Kotlin `at
 *   pkg.Class.method(File.kt:42)`, Python `File "x.py", line 7, in foo`) and
 *   whole lines containing the keywords `Error`, `Exception`, `Traceback`,
 *   `FATAL`, `FAILED`, `panic`.
 *
 * Windows paths (`C:\Users\...`), git SSH refs, JIRA tickets, and other
 * domain-specific shapes are **out of scope** for the default — implement
 * [TerminalMatcher] in your downstream module if you need them.
 *
 * ## Precedence
 *
 * Matches are produced left-to-right within each regex pass, and the passes
 * run in this order. Earlier passes "claim" their byte range; later passes
 * skip any candidate whose range overlaps an earlier claim. This avoids
 * double-emitting (e.g. the path tail of a URL also becoming a Path match).
 *
 * 1. URLs — claim the full `https://host/path...` so the tail does not also
 *    surface as a Path.
 * 2. Absolute Unix paths — `/usr/local/bin/foo`, `/etc/hosts`. A bare `/` is
 *    rejected.
 * 3. Relative paths — must start with `./`, `../`, `~/`, or end in a known
 *    file extension (see [DefaultTerminalMatcher.REL_PATH_EXTENSIONS]). See
 *    "Known limitations" for what this excludes.
 * 4. Stack-trace frames — Java/Kotlin `at pkg.Cls.m(File.kt:42)` and Python
 *    `File "x.py", line N, in fn`. Reported as [TerminalMatch.Error].
 * 5. Generic error keyword lines — the **whole containing line** is emitted
 *    as [TerminalMatch.Error] so the copied text carries context.
 *
 * ## Snapshot windowing
 *
 * The matcher only scans the **last [DefaultTerminalMatcher.MAX_SCAN_CHARS]
 * characters** of the input. Terminal transcripts can be megabytes (think
 * `tail -f` on a busy log) and re-running every regex over the full buffer on
 * every flow tick is an O(n) CPU draw per keystroke. The window is small
 * enough to keep matching cheap and large enough to cover the visible screen
 * plus a generous slice of scrollback. Callers that want to scan more should
 * slice the input themselves before calling [TerminalMatcher.matches].
 *
 * ## Known limitations
 *
 * - **Relative-path false positives the regex deliberately rejects**: `5/2`,
 *   `22/7`, `n/a`, `y/n`, `TCP/IP`, `Bytes/sec`, `1/2 cup`. These look like
 *   paths but are fractions, common shorthand, or unit ratios. The regex
 *   requires either a directory-like prefix (`./`, `../`, `~/`) or a known
 *   file extension to consider a token a relative path.
 * - **Snapshot bound**: only the last
 *   [DefaultTerminalMatcher.MAX_SCAN_CHARS] characters are scanned (see
 *   "Snapshot windowing").
 * - **Visible-viewport hit-testing**: tap targets are derived from visible
 *   terminal rows. Off-screen transcript matches are still available through
 *   [TerminalMatcher.matches], but they are not tap targets until visible.
 * - **Windows paths, `ftp://` / `ssh://` URLs, IPv6 hosts**: not surfaced.
 * - **Paths with spaces**: not surfaced. (No way to disambiguate from prose.)
 *
 * ## Extending: adding a new match kind
 *
 * 1. Add a new `data class` to the [TerminalMatch] sealed hierarchy with the
 *    string `value` and any extra fields you want the action layer to see.
 * 2. Implement [TerminalMatcher] in your downstream module (do **not** modify
 *    [DefaultTerminalMatcher] — keep its surface frozen so consumers can rely
 *    on its behaviour). Compose it with `DefaultTerminalMatcher` by calling
 *    both and merging the results, claiming earlier matches' byte ranges if
 *    you need to avoid overlaps.
 * 3. Teach your downstream tap-action handler what to do on tap. Add a `when`
 *    branch for the new kind — the sealed hierarchy makes the compiler enforce
 *    exhaustiveness so you cannot forget.
 * 4. Add unit tests in
 *    `shared/core-terminal/src/test/java/com/pocketshell/core/terminal/selection/`
 *    covering at least one positive case and one false-positive case the new
 *    regex must reject.
 *
 * A region of text detected on the terminal surface that the UI can offer as
 * a tap target.
 *
 * Variants:
 *
 * - [Path] — a filesystem path (absolute or relative). The PocketShell shell
 *   surfaces these so the user can tap to copy.
 * - [Url] — an `http(s)` URL. Tapping fires `Intent.ACTION_VIEW`.
 * - [Error] — a stack-trace frame, generic error keyword, or other text the
 *   user is likely to want to copy (paste into a bug report, search the web,
 *   ...). Tapping copies the value to the clipboard.
 *
 * The [value] of each variant is the literal substring the matcher extracted
 * from the terminal buffer, with no normalisation — the consumer decides what
 * to do with it (open in browser, copy as-is, prefix `cd `, ...).
 */
sealed class TerminalMatch {
    abstract val value: String

    /** Filesystem path detected in the terminal buffer. */
    data class Path(override val value: String) : TerminalMatch()

    /** HTTP/HTTPS URL detected in the terminal buffer. */
    data class Url(override val value: String) : TerminalMatch()

    /** Error-like text (stack frame, exception keyword, traceback marker). */
    data class Error(override val value: String) : TerminalMatch()
}

/**
 * Strategy interface for extracting [TerminalMatch] instances from a flat
 * string snapshot of the terminal screen.
 *
 * Implementations are expected to be cheap and side-effect free. The state
 * holder calls [matches] on a debounced cadence from a background dispatcher
 * (see `TerminalSurfaceState.flowOfMatches`), so blocking or allocating
 * heavily here is a tax on every keystroke.
 *
 * Bring-your-own implementations are encouraged for niche surfaces (e.g. a
 * Hilt-injected matcher that recognises git refs, JIRA tickets, or
 * project-specific identifiers). The shipped default ([DefaultTerminalMatcher])
 * covers the categories called out in `docs/vision.md` §4.
 */
interface TerminalMatcher {
    /**
     * Run the matcher across [text] and return every match found. Multiple
     * matches per line are allowed; overlapping matches are not deduped here
     * — the caller can rank or filter as needed.
     *
     * @param text the text snapshot (typically the visible screen plus
     *   transcript) to scan. May be empty; implementations must handle that
     *   without throwing.
     */
    fun matches(text: String): List<TerminalMatch>
}

/**
 * A [TerminalMatch] plus its half-open character range inside the scanned
 * string. Span-aware matchers let the terminal surface convert visible-row
 * matches to grid rectangles without guessing from duplicate string values.
 */
data class TerminalMatchSpan(
    val match: TerminalMatch,
    val start: Int,
    val endExclusive: Int,
)

/**
 * Optional extension for matchers that can preserve exact source spans.
 * Precise smart-selection hit-testing requires this contract.
 */
interface TerminalSpanMatcher : TerminalMatcher {
    fun matchSpans(text: String): List<TerminalMatchSpan>
}

/**
 * Default [TerminalMatcher] implementation backed by a small set of regular
 * expressions. The patterns target the categories called out in
 * `docs/vision.md` §4 (paths, URLs, error keywords) without trying to be
 * exhaustive — the goal is "good enough to make tap-to-copy useful", not "a
 * complete grammar of every imaginable error format".
 *
 * See the file-level KDoc above for supported kinds, precedence, snapshot
 * bound, known limitations, and the extension recipe.
 */
class DefaultTerminalMatcher : TerminalSpanMatcher {

    override fun matches(text: String): List<TerminalMatch> =
        matchSpans(text).map { it.match }

    override fun matchSpans(text: String): List<TerminalMatchSpan> {
        if (text.isEmpty()) return emptyList()

        // Window the input. Terminal transcripts can be megabytes when the
        // user is tailing a log; running every regex over the full buffer on
        // every flow tick is wasted CPU. Keep the last MAX_SCAN_CHARS — that
        // covers the visible screen and a generous slice of scrollback,
        // which is what the user can actually see and tap on. Callers that
        // need to scan further should slice the input themselves.
        val scanned = if (text.length > MAX_SCAN_CHARS) {
            text.substring(text.length - MAX_SCAN_CHARS)
        } else {
            text
        }
        val scannedOffset = text.length - scanned.length

        val results = mutableListOf<TerminalMatchSpan>()
        val claimed = BooleanArray(scanned.length)

        // 1. URLs — match first so any path-like tail is consumed.
        URL_PATTERN.findAll(scanned).forEach { match ->
            val raw = match.value
            // Strip trailing sentence punctuation that almost never belongs
            // to the URL itself. We strip from the end of the matched string,
            // not from the original text, so the claimed-range below shrinks
            // accordingly.
            val trimmed = raw.trimEndOfUrlPunctuation()
            if (trimmed.isEmpty()) return@forEach
            val start = match.range.first
            val end = start + trimmed.length // exclusive
            if (!claim(claimed, start, end)) return@forEach
            results += TerminalMatchSpan(
                TerminalMatch.Url(trimmed),
                scannedOffset + start,
                scannedOffset + end,
            )
        }

        // 2. Absolute Unix paths.
        ABS_PATH_PATTERN.findAll(scanned).forEach { match ->
            val raw = match.value
            // Reject the bare root `/` — it's never a useful tap target.
            if (raw == "/" || raw.length <= 1) return@forEach
            val start = match.range.first
            val end = match.range.last + 1
            if (!claim(claimed, start, end)) return@forEach
            results += TerminalMatchSpan(
                TerminalMatch.Path(raw),
                scannedOffset + start,
                scannedOffset + end,
            )
        }

        // 3. Relative paths.
        REL_PATH_PATTERN.findAll(scanned).forEach { match ->
            val raw = match.value
            val start = match.range.first
            val end = match.range.last + 1
            if (!claim(claimed, start, end)) return@forEach
            results += TerminalMatchSpan(
                TerminalMatch.Path(raw),
                scannedOffset + start,
                scannedOffset + end,
            )
        }

        // 4. Stack-trace frames (Java-style and Python-style).
        STACK_FRAME_PATTERN.findAll(scanned).forEach { match ->
            val raw = match.value
            val start = match.range.first
            val end = match.range.last + 1
            if (!claim(claimed, start, end)) return@forEach
            results += TerminalMatchSpan(
                TerminalMatch.Error(raw),
                scannedOffset + start,
                scannedOffset + end,
            )
        }

        // 5. Generic error keywords — emit the whole containing line so the
        //    copied text carries context.
        ERROR_KEYWORD_PATTERN.findAll(scanned).forEach { match ->
            val lineStart = scanned.lastIndexOf('\n', match.range.first).let {
                if (it == -1) 0 else it + 1
            }
            val lineEndCandidate = scanned.indexOf('\n', match.range.first)
            val lineEnd = if (lineEndCandidate == -1) scanned.length else lineEndCandidate
            val line = scanned.substring(lineStart, lineEnd).trim()
            if (line.isEmpty()) return@forEach
            if (!claim(claimed, lineStart, lineEnd)) return@forEach
            val visualStart = scanned.indexOfFirstNonWhitespace(lineStart, lineEnd)
            val visualEnd = scanned.indexAfterLastNonWhitespace(lineStart, lineEnd)
            if (visualStart < visualEnd) {
                results += TerminalMatchSpan(
                    TerminalMatch.Error(line),
                    scannedOffset + visualStart,
                    scannedOffset + visualEnd,
                )
            }
        }

        return results
    }

    /**
     * Mark `[start, end)` of [claimed] as taken. Returns false if any byte in
     * that range is already claimed by an earlier (higher-precedence) match,
     * in which case the caller should drop the candidate.
     */
    private fun claim(claimed: BooleanArray, start: Int, end: Int): Boolean {
        if (start < 0 || end > claimed.size || start >= end) return false
        for (i in start until end) if (claimed[i]) return false
        for (i in start until end) claimed[i] = true
        return true
    }

    private fun String.trimEndOfUrlPunctuation(): String {
        var end = length
        while (end > 0 && this[end - 1] in URL_TRAILING_PUNCTUATION) end--
        return substring(0, end)
    }

    private fun String.indexOfFirstNonWhitespace(start: Int, end: Int): Int {
        for (i in start until end) {
            if (!this[i].isWhitespace()) return i
        }
        return end
    }

    private fun String.indexAfterLastNonWhitespace(start: Int, end: Int): Int {
        for (i in end - 1 downTo start) {
            if (!this[i].isWhitespace()) return i + 1
        }
        return start
    }

    companion object {
        /**
         * Maximum number of characters scanned per [matches] call. Terminal
         * transcripts can be megabytes when the user is tailing a log; we
         * window the input so the matcher's cost stays bounded. The window
         * covers roughly the last ~200 lines at 80 columns (≈ 16k chars) but
         * we cap a little tighter to keep the regex passes snappy on cheap
         * phones. If [matches] is called with a longer string, only the
         * trailing [MAX_SCAN_CHARS] are scanned and matches before that point
         * are silently dropped.
         */
        const val MAX_SCAN_CHARS: Int = 8_000

        /**
         * File-extension allowlist used by [REL_PATH_PATTERN]. Anything not
         * on this list does not trigger the extension-form match. Keep the
         * list short and conservative — every entry trades a false-negative
         * for a potential false-positive.
         */
        private val REL_PATH_EXTENSIONS: List<String> = listOf(
            // Programming languages
            "kt", "kts", "java", "py", "js", "jsx", "ts", "tsx", "go", "rs",
            "c", "cc", "cpp", "h", "hpp", "rb", "php", "swift", "sh", "bash",
            "zsh",
            // Markup / data
            "md", "txt", "rst", "json", "yml", "yaml", "toml", "xml", "html",
            "htm", "css", "scss", "csv", "tsv",
            // Build / config
            "gradle", "properties", "lock", "cfg", "ini", "conf",
            // Logs / misc
            "log", "out", "err", "pid",
        )

        /**
         * HTTP/HTTPS URL. Host: word chars, dots, dashes. Optional port,
         * path, query, fragment characters. Intentionally narrow — `ftp://`,
         * `git@` SSH-style refs, and IPv6 hosts are not currently surfaced.
         */
        private val URL_PATTERN = Regex(
            "https?://[\\w.-]+(?::\\d+)?(?:/[\\w./?=&%+#~@:!,;-]*)?"
        )

        /**
         * Absolute Unix path. Requires at least one character after the
         * leading slash; the matcher's caller still rejects bare `/`.
         * Characters allowed in the path body are conservative — letters,
         * digits, `.`, `_`, `-`, and additional path separators. Backslashes
         * are NOT allowed (we don't surface Windows paths today).
         *
         * The lookbehind excludes `\w`, `/`, `.` and `~` so we don't
         * pick up the `/foo` tail of a relative path written as `./foo`,
         * `../foo`, or `~/foo` — those are claimed by [REL_PATH_PATTERN].
         */
        private val ABS_PATH_PATTERN = Regex(
            "(?<![\\w/.~])/[\\w][\\w./-]*"
        )

        /**
         * Relative path. To avoid false positives on fractions and
         * shorthand like `5/2`, `n/a`, `TCP/IP`, `Bytes/sec`, the regex
         * requires the token to be EITHER:
         *
         * - A directory-like prefix — `./`, `../`, or `~/` followed by a
         *   path body (e.g. `./build.gradle`, `../README.md`,
         *   `~/projects/foo`), OR
         * - A path whose final segment ends in a known file extension from
         *   [REL_PATH_EXTENSIONS] (e.g. `src/main/kotlin/Foo.kt`,
         *   `app/build.gradle.kts`, `docs/vision.md`).
         *
         * The leading negative lookbehind `(?<![\w/.])` keeps this from
         * stealing the tails of absolute paths or URLs (which are
         * higher-precedence and consume those bytes first), and also
         * prevents fragments inside larger tokens (`21/2`, `foo.bar/baz`)
         * from matching.
         *
         * Both alternatives require at least one `/` somewhere — a bare word
         * with an extension (`Foo.kt`) is not a relative path candidate here.
         */
        private val REL_PATH_PATTERN: Regex = run {
            val ext = REL_PATH_EXTENSIONS.joinToString("|") { Regex.escape(it) }
            Regex(
                // Directory-prefix form: `./foo`, `../bar/baz`, `~/qux`.
                "(?<![\\w/.])(?:\\.{1,2}|~)/[\\w./-]+" +
                    "|" +
                    // Extension form: one or more `seg/` parts followed by a
                    // final segment that ends in a known extension. The
                    // intermediate `[\\w.-]+` permits dotted filenames like
                    // `build.gradle.kts`; regex backtracking lets the final
                    // `\\.(?:ext)\\b` peel off the extension correctly.
                    "(?<![\\w/.])(?:[\\w-]+/)+[\\w.-]+\\.(?:$ext)\\b"
            )
        }

        /**
         * Stack-trace frame. Matches:
         *
         * - Java/Kotlin: `package.Class.method(File.kt:123)` or
         *   `package.Class.method(Unknown Source)` etc.
         * - Generic dotted callable paths: `com.foo.bar.baz`.
         * - Python tracebacks: `File "path.py", line 42, in func`.
         *
         * The two alternatives are kept separate to keep each regex
         * understandable.
         */
        private val STACK_FRAME_PATTERN = Regex(
            "(?:" +
                // Java/Kotlin-style: dotted name optionally followed by
                // `(File.ext:line)` or `(Unknown Source)`.
                "[\\w$]+(?:\\.[\\w$]+){2,}\\((?:[\\w.$ ]+(?::\\d+)?|Unknown Source|Native Method)\\)" +
                "|" +
                // Python traceback line.
                "File \"[^\"]+\", line \\d+(?:, in \\w+)?" +
                ")"
        )

        /**
         * Whole-line error markers. The pattern is anchored only on the
         * keyword; the caller widens to the full surrounding line so the
         * emitted [TerminalMatch.Error] carries context like the error
         * message body.
         *
         * `Exception` is intentionally NOT word-boundary-prefixed: real-world
         * stack traces emit `NullPointerException`, `IllegalStateException`,
         * etc. — the keyword almost always lives in the middle of an
         * identifier. We anchor only on the trailing boundary to keep
         * `Exceptional` from triggering.
         *
         * `panic` (Go-style) is matched without requiring the trailing colon
         * to be inside the keyword: Go panics print `panic: runtime error: ...`
         * and we want any line starting with `panic` to surface.
         */
        private val ERROR_KEYWORD_PATTERN = Regex(
            "(?:\\b(?:Error|Traceback|FATAL|FAILED|panic)\\b|Exception\\b)"
        )

        /** Punctuation characters that get stripped from URL tails. */
        private val URL_TRAILING_PUNCTUATION = setOf('.', ',', ';', ':', ')', ']', '!', '?', '\'', '"', '>', '<')
    }
}
