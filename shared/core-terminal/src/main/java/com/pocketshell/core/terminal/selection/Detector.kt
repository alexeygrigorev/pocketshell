package com.pocketshell.core.terminal.selection

/**
 * A region of text detected on the terminal surface that the UI can offer as a
 * tap target.
 *
 * Variants:
 *
 * - [Path] — a filesystem path (absolute or relative). The PocketShell shell
 *   surfaces these so the user can tap to copy.
 * - [Url] — an `http(s)` URL. Tapping fires `Intent.ACTION_VIEW`.
 * - [Error] — a stack-trace frame, generic error keyword, or other text the
 *   user is likely to want to copy (paste into a bug report, search the web,
 *   …). Tapping copies the value to the clipboard.
 *
 * The [value] of each variant is the literal substring the matcher extracted
 * from the terminal buffer, with no normalisation — the consumer decides what
 * to do with it (open in browser, copy as-is, prefix `cd `, …).
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
 * Default [TerminalMatcher] implementation backed by a small set of regular
 * expressions. The patterns target the categories called out in
 * `docs/vision.md` §4 (paths, URLs, error keywords) without trying to be
 * exhaustive — the goal is "good enough to make tap-to-copy useful", not "a
 * complete grammar of every imaginable error format".
 *
 * Match precedence is deliberately ordered to avoid producing overlapping
 * matches:
 *
 * 1. URLs first — both http and https. Catches `https://example.com/x`
 *    cleanly so that the path-like tail is not also re-emitted as a
 *    [TerminalMatch.Path].
 * 2. Absolute Unix paths — `/usr/local/bin/foo`, `/etc/hosts`. A bare `/` on
 *    its own (with no following character) is rejected so we don't surface
 *    the root directory as a tap target.
 * 3. Relative paths — `src/main/kotlin/Foo.kt`. The pattern requires a
 *    word-character segment before the first `/` so we don't double-emit the
 *    tail of an absolute path or a URL.
 * 4. Stack-trace frames — `com.foo.Bar.baz(Bar.java:42)` or Python
 *    `traceback`-style `File "foo.py", line 42, in bar`. Surfaced as
 *    [TerminalMatch.Error] because the user almost always wants to copy the
 *    whole frame, not just the path inside it.
 * 5. Generic error keywords — `Error: …`, `Exception: …`, `Traceback (most
 *    recent call last):`. The full line containing the keyword is emitted so
 *    the copied text has context.
 *
 * The regexes are intentionally simple. Edge cases (paths with spaces,
 * Windows paths, IPv6 URLs) are out of scope for the default; downstream
 * callers wanting more can implement [TerminalMatcher] themselves.
 *
 * Trailing punctuation in URLs (`.`, `,`, `;`, `)`, `]`) is stripped: terminal
 * output frequently ends a sentence after a URL ("…see https://example.com.")
 * and the dot is almost never part of the URL.
 */
class DefaultTerminalMatcher : TerminalMatcher {

    override fun matches(text: String): List<TerminalMatch> {
        if (text.isEmpty()) return emptyList()

        val results = mutableListOf<TerminalMatch>()
        val claimed = BooleanArray(text.length)

        // 1. URLs — match first so any path-like tail is consumed.
        URL_PATTERN.findAll(text).forEach { match ->
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
            results += TerminalMatch.Url(trimmed)
        }

        // 2. Absolute Unix paths.
        ABS_PATH_PATTERN.findAll(text).forEach { match ->
            val raw = match.value
            // Reject the bare root `/` — it's never a useful tap target.
            if (raw == "/" || raw.length <= 1) return@forEach
            val start = match.range.first
            val end = match.range.last + 1
            if (!claim(claimed, start, end)) return@forEach
            results += TerminalMatch.Path(raw)
        }

        // 3. Relative paths.
        REL_PATH_PATTERN.findAll(text).forEach { match ->
            val raw = match.value
            val start = match.range.first
            val end = match.range.last + 1
            if (!claim(claimed, start, end)) return@forEach
            results += TerminalMatch.Path(raw)
        }

        // 4. Stack-trace frames (Java-style and Python-style).
        STACK_FRAME_PATTERN.findAll(text).forEach { match ->
            val raw = match.value
            val start = match.range.first
            val end = match.range.last + 1
            if (!claim(claimed, start, end)) return@forEach
            results += TerminalMatch.Error(raw)
        }

        // 5. Generic error keywords — emit the whole containing line so the
        //    copied text carries context.
        ERROR_KEYWORD_PATTERN.findAll(text).forEach { match ->
            val lineStart = text.lastIndexOf('\n', match.range.first).let {
                if (it == -1) 0 else it + 1
            }
            val lineEndCandidate = text.indexOf('\n', match.range.first)
            val lineEnd = if (lineEndCandidate == -1) text.length else lineEndCandidate
            val line = text.substring(lineStart, lineEnd).trim()
            if (line.isEmpty()) return@forEach
            if (!claim(claimed, lineStart, lineEnd)) return@forEach
            results += TerminalMatch.Error(line)
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

    private companion object {
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
         */
        private val ABS_PATH_PATTERN = Regex(
            "(?<![\\w/])/[\\w][\\w./-]*"
        )

        /**
         * Relative path: a word-character segment, a single `/`, and a path
         * tail. The leading word-character requirement keeps this from
         * stealing the tails of absolute paths or URLs (which are
         * higher-precedence and consume those bytes first).
         */
        private val REL_PATH_PATTERN = Regex(
            "(?<![\\w/.])[\\w-]+/[\\w./-]+"
        )

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
         * to be inside the keyword: Go panics print `panic: runtime error: …`
         * and we want any line starting with `panic` to surface.
         */
        private val ERROR_KEYWORD_PATTERN = Regex(
            "(?:\\b(?:Error|Traceback|FATAL|FAILED|panic)\\b|Exception\\b)"
        )

        /** Punctuation characters that get stripped from URL tails. */
        private val URL_TRAILING_PUNCTUATION = setOf('.', ',', ';', ':', ')', ']', '!', '?', '\'', '"', '>', '<')
    }
}
