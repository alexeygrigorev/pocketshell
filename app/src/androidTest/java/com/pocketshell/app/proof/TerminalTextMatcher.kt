package com.pocketshell.app.proof

/**
 * Wrap-tolerant matcher for terminal transcript text.
 *
 * Background (#139): connected tests under `androidTest` use
 * `visibleTerminalText().contains(substring)` to assert that a command
 * landed on the remote shell, the agent CLI emitted some marker, or
 * tmux echoed something specific. After #102 (`resize-window`), the
 * remote pane sizes to the actual Compose grid (~63 cols on a Pixel 7
 * emulator), so any typed command longer than the grid width wraps
 * inside the terminal emulator and the transcript text contains a real
 * `\n` mid-substring. The naive `contains` check then fails even though
 * the user-visible content is correct.
 *
 * The matcher heuristically collapses those soft-wrap newlines so the
 * caller can run a plain `contains` afterwards. It is intentionally a
 * **heuristic, not a parser**:
 *
 * - It does **not** understand ANSI escape sequences. Inputs are
 *   expected to be the already-decoded visible transcript (e.g. what
 *   `TerminalEmulator.screen.transcriptText` returns), not a raw byte
 *   stream.
 * - It collapses a newline only when the preceding line has length
 *   exactly equal to `terminalCols` (the working assumption being that
 *   the emulator inserted the `\n` because the line reached the
 *   right margin) and the next line does **not** begin with a known
 *   prompt sentinel (see [PROMPT_SENTINELS]) or look like a tmux status
 *   line (see [looksLikeTmuxStatusLine]).
 * - It therefore does the wrong thing on rare but possible cases:
 *   - A real line of output whose natural length happens to equal
 *     `terminalCols` and whose next line is regular output (not a
 *     prompt). The matcher will collapse the boundary. In practice this
 *     hurts only assertions that depend on the line break being
 *     preserved, which we explicitly do not have in the connected test
 *     suite today.
 *   - A custom prompt that does not match [PROMPT_SENTINELS] (e.g. a
 *     coloured zsh prompt with a unicode arrow). Callers that need
 *     those should extend the sentinel list rather than reach for a
 *     general parser here.
 * - `terminalCols <= 0` disables wrap collapsing entirely; the
 *   transcript is returned unchanged so callers can still pass `0` or a
 *   negative value when they don't actually know the grid width.
 *
 * Scope reminder: this matcher exists so downstream tests (e.g. the
 * skipped `EmulatorWorkflowE2eTest.realAppTmuxJourneyAttachesSessionAndAcceptsTerminalInput`
 * from #134) can opt into wrap-tolerant assertions one by one. This PR
 * does NOT migrate existing `.contains(...)` call sites.
 */
object TerminalTextMatcher {

    /**
     * Line prefixes that indicate the next line is a real new line of
     * output, not the continuation of a wrapped one. Order does not
     * matter — they are checked with `startsWith`.
     *
     * - `"~ $"` matches the typical home-directory shell prompt used by
     *   the deterministic test container: `~ $ `.
     * - `"# "` matches a root prompt.
     * - `"$ "` matches a generic non-root prompt.
     */
    private val PROMPT_SENTINELS: List<String> = listOf("~ $", "# ", "$ ")

    /**
     * Collapse newlines that look like soft-wraps inside [transcript].
     *
     * A newline between line `i` and line `i + 1` is treated as a
     * soft-wrap and removed when:
     * 1. `terminalCols > 0`, AND
     * 2. `lines[i].length == terminalCols` (the line filled the grid
     *    exactly), AND
     * 3. `lines[i + 1]` does NOT start with any string in
     *    [PROMPT_SENTINELS] and does NOT look like a tmux status line
     *    (see [looksLikeTmuxStatusLine]).
     *
     * Empty input, single-line input, and `terminalCols <= 0` all
     * short-circuit to returning [transcript] verbatim. The
     * normalisation is non-destructive: it does not trim, lowercase, or
     * otherwise rewrite line content — it only deletes specific `\n`
     * characters.
     *
     * Limitations: see the class-level kdoc on [TerminalTextMatcher].
     */
    fun normaliseWrap(transcript: String, terminalCols: Int): String {
        if (terminalCols <= 0) return transcript
        if (transcript.isEmpty()) return transcript
        // `split('\n')` preserves trailing empty segments, which is what
        // we need so a trailing newline survives the join below.
        val lines = transcript.split('\n')
        if (lines.size < 2) return transcript

        val out = StringBuilder(transcript.length)
        for (i in lines.indices) {
            out.append(lines[i])
            if (i == lines.lastIndex) continue
            val current = lines[i]
            val next = lines[i + 1]
            val isSoftWrap = current.length == terminalCols &&
                !startsWithPromptSentinel(next) &&
                !looksLikeTmuxStatusLine(next)
            if (!isSoftWrap) {
                out.append('\n')
            }
        }
        return out.toString()
    }

    /**
     * Return `true` when [substring] appears in [transcript] after
     * soft-wrap newlines have been collapsed.
     *
     * Equivalent to `normaliseWrap(transcript, terminalCols).contains(substring)`.
     * Use this instead of `transcript.contains(substring)` when the
     * substring you are looking for could plausibly straddle the
     * terminal's right margin (long typed commands, long agent
     * markers, etc.). See the class-level kdoc on [TerminalTextMatcher]
     * for the heuristic's limits.
     */
    fun containsWrapTolerant(
        transcript: String,
        substring: String,
        terminalCols: Int,
    ): Boolean {
        if (substring.isEmpty()) return true
        return normaliseWrap(transcript, terminalCols).contains(substring)
    }

    /**
     * Return `true` only when every entry in [substrings] appears in
     * [transcript] after wrap normalisation.
     *
     * This is for assertions that already split their expectation into
     * several short fragments (e.g. an agent marker plus a result
     * keyword) and want each fragment to land somewhere in the visible
     * transcript without caring about line order. It is intentionally
     * `containsAll`, not "contains in this order" — the connected test
     * suite already has more specific assertions for ordering when it
     * needs them.
     *
     * Empty `substrings` returns `true`. See the class-level kdoc on
     * [TerminalTextMatcher] for the heuristic's limits.
     */
    fun containsAllWrapTolerant(
        transcript: String,
        vararg substrings: String,
        terminalCols: Int,
    ): Boolean {
        if (substrings.isEmpty()) return true
        val normalised = normaliseWrap(transcript, terminalCols)
        return substrings.all { it.isEmpty() || normalised.contains(it) }
    }

    private fun startsWithPromptSentinel(line: String): Boolean {
        for (sentinel in PROMPT_SENTINELS) {
            if (line.startsWith(sentinel)) return true
        }
        return false
    }

    /**
     * Heuristic: a tmux status line typically looks like
     * `[session-name] 0:bash*                "host" 12:34 26-May-26`,
     * i.e. it starts with `[`, contains a closing `]` near the start,
     * and the bracketed content is the session name. We treat any line
     * whose first non-space character is `[` and which contains a `]`
     * within the first 64 characters as a tmux status line.
     *
     * False positives here are far better than false negatives: a false
     * positive prevents a soft-wrap collapse (so two real lines stay
     * separated), while a false negative would glue a real status line
     * into the preceding output.
     */
    private fun looksLikeTmuxStatusLine(line: String): Boolean {
        if (line.isEmpty()) return false
        val first = line.indexOfFirst { !it.isWhitespace() }
        if (first < 0) return false
        if (line[first] != '[') return false
        val close = line.indexOf(']', startIndex = first + 1)
        return close in (first + 1)..(first + 64)
    }
}
