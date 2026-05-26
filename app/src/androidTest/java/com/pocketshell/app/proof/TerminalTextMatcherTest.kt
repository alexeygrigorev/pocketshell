package com.pocketshell.app.proof

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests for [TerminalTextMatcher].
 *
 * The matcher itself has no Android dependencies, but it lives in the
 * `androidTest` source set per #139 so connected tests can consume it
 * directly. We run with `AndroidJUnit4` to stay consistent with the rest
 * of `app/src/androidTest/`; the assertions themselves are pure JVM.
 */
@RunWith(AndroidJUnit4::class)
class TerminalTextMatcherTest {

    // --- normaliseWrap: edge cases --------------------------------------

    @Test
    fun normaliseWrap_emptyTranscript_returnsEmpty() {
        assertEquals("", TerminalTextMatcher.normaliseWrap("", terminalCols = 63))
    }

    @Test
    fun normaliseWrap_singleLine_returnsUnchanged() {
        val input = "hello world"
        assertEquals(input, TerminalTextMatcher.normaliseWrap(input, terminalCols = 63))
    }

    @Test
    fun normaliseWrap_allWhitespace_returnsUnchanged() {
        val input = "   \n   \n   "
        // Whitespace-only lines never hit terminalCols length (3 < 63),
        // so no collapse and the input is returned verbatim.
        assertEquals(input, TerminalTextMatcher.normaliseWrap(input, terminalCols = 63))
    }

    @Test
    fun normaliseWrap_nonPositiveCols_returnsUnchanged() {
        val input = "a".repeat(63) + "\n" + "continuation"
        assertEquals(input, TerminalTextMatcher.normaliseWrap(input, terminalCols = 0))
        assertEquals(input, TerminalTextMatcher.normaliseWrap(input, terminalCols = -1))
    }

    // --- containsWrapTolerant: plain text -------------------------------

    @Test
    fun containsWrapTolerant_plainText_substringMatches() {
        val transcript = "~ $ echo hello\nhello\n~ $ "
        assertTrue(
            TerminalTextMatcher.containsWrapTolerant(
                transcript,
                "echo hello",
                terminalCols = 63,
            ),
        )
        assertTrue(
            TerminalTextMatcher.containsWrapTolerant(
                transcript,
                "hello",
                terminalCols = 63,
            ),
        )
    }

    @Test
    fun containsWrapTolerant_plainText_missingSubstringFails() {
        val transcript = "~ $ echo hello\nhello\n~ $ "
        assertFalse(
            TerminalTextMatcher.containsWrapTolerant(
                transcript,
                "definitely-not-there",
                terminalCols = 63,
            ),
        )
    }

    // --- containsWrapTolerant: soft-wrap at column boundary -------------

    @Test
    fun containsWrapTolerant_softWrapAtColumnBoundary_matches() {
        val cols = 20
        // `firstHalf` is exactly `cols` chars long; the substring we look
        // for straddles the wrap point.
        val firstHalf = "echo aaaaaaaaaaaaaaa" // 20 chars
        assertEquals(cols, firstHalf.length)
        val secondHalf = "BBBBB-payload"
        val transcript = "$firstHalf\n$secondHalf\n~ $ "
        val substring = "aaaaaBBBBB"

        // Sanity: the naive contains check should fail because of the
        // injected newline — that's the bug we're tolerating.
        assertFalse(transcript.contains(substring))

        // Wrap-tolerant matcher collapses the soft-wrap and finds the
        // substring.
        assertTrue(
            TerminalTextMatcher.containsWrapTolerant(transcript, substring, terminalCols = cols),
        )
    }

    @Test
    fun containsWrapTolerant_softWrapWithLongCommand_matches() {
        // Mirrors the failure described in #139: a long typed command
        // appears in the transcript after a `~ $ ` prompt, the displayed
        // row reaches the right margin, and the next row holds the
        // command tail. The assertion is looking for the full command
        // text, which the naive `.contains` misses because of the
        // injected `\n`.
        val cols = 63
        val command =
            "claude --resume --print 'continue working on the long-task feature please'"
        val prompt = "~ $ "
        val fullLine = prompt + command
        // First displayed row is exactly `cols` chars: `~ $ ` plus the
        // leading slice of `command`.
        val firstRow = fullLine.substring(0, cols)
        val secondRow = fullLine.substring(cols)
        assertEquals(cols, firstRow.length)
        val transcript = "$firstRow\n$secondRow\n"

        assertTrue(
            TerminalTextMatcher.containsWrapTolerant(transcript, command, terminalCols = cols),
        )
        // And the naive check would have failed:
        assertFalse(transcript.contains(command))
    }

    // --- normaliseWrap: genuine multi-line output stays intact ----------

    @Test
    fun normaliseWrap_genuineMultiLineOutput_doesNotCollapse() {
        // `ls` style output: short lines that do not reach the column
        // width. None of these should be collapsed.
        val cols = 63
        val transcript = "~ $ ls\nfoo.txt\nbar.txt\nbaz.txt\n~ $ "
        val normalised = TerminalTextMatcher.normaliseWrap(transcript, cols)
        assertEquals(transcript, normalised)
    }

    @Test
    fun normaliseWrap_lineEqualToColsButNextIsPrompt_doesNotCollapse() {
        // Edge: a line of output that happens to be exactly `cols` long
        // but is followed by a prompt is a real line break. The prompt
        // sentinel rule must preserve the newline so command boundaries
        // stay legible.
        val cols = 20
        val outputLine = "a".repeat(cols) // length == cols
        val transcript = "~ $ run\n$outputLine\n~ $ next"
        val normalised = TerminalTextMatcher.normaliseWrap(transcript, cols)
        assertEquals(transcript, normalised)
        // And the substring "a$cols\n~ $" survives intact (i.e. the
        // boundary newline is not eaten):
        assertTrue(normalised.contains("$outputLine\n~ $"))
    }

    @Test
    fun normaliseWrap_lineEqualToColsButNextIsRootPrompt_doesNotCollapse() {
        val cols = 20
        val outputLine = "x".repeat(cols)
        val transcript = "$outputLine\n# next-root-command"
        // `# ` sentinel must keep the newline.
        val normalised = TerminalTextMatcher.normaliseWrap(transcript, cols)
        assertEquals(transcript, normalised)
    }

    @Test
    fun normaliseWrap_lineEqualToColsButNextIsDollarPrompt_doesNotCollapse() {
        val cols = 20
        val outputLine = "z".repeat(cols)
        val transcript = "$outputLine\n$ another-command"
        val normalised = TerminalTextMatcher.normaliseWrap(transcript, cols)
        assertEquals(transcript, normalised)
    }

    @Test
    fun normaliseWrap_lineEqualToColsButNextIsTmuxStatusLine_doesNotCollapse() {
        val cols = 20
        val outputLine = "y".repeat(cols)
        val statusLine = "[mysession] 0:bash*                \"host\" 12:34"
        val transcript = "$outputLine\n$statusLine"
        val normalised = TerminalTextMatcher.normaliseWrap(transcript, cols)
        // Tmux status line must NOT be glued to the preceding output.
        assertEquals(transcript, normalised)
    }

    // --- normaliseWrap: transcript shorter than substring ---------------

    @Test
    fun containsWrapTolerant_transcriptShorterThanSubstring_returnsFalse() {
        val transcript = "tiny"
        assertFalse(
            TerminalTextMatcher.containsWrapTolerant(
                transcript,
                substring = "a-substring-much-longer-than-the-transcript",
                terminalCols = 63,
            ),
        )
    }

    @Test
    fun containsWrapTolerant_emptySubstring_returnsTrue() {
        // Mirrors `String.contains("")` semantics; documents the choice.
        assertTrue(
            TerminalTextMatcher.containsWrapTolerant(
                "~ $ ",
                substring = "",
                terminalCols = 63,
            ),
        )
        assertTrue(
            TerminalTextMatcher.containsWrapTolerant(
                "",
                substring = "",
                terminalCols = 63,
            ),
        )
    }

    // --- containsAllWrapTolerant ---------------------------------------

    @Test
    fun containsAllWrapTolerant_allFragmentsPresent_returnsTrue() {
        val cols = 20
        val firstHalf = "echo aaaaaaaaaaaaaaa" // 20 chars
        val secondHalf = "BBBBB tail-marker"
        val transcript = "$firstHalf\n$secondHalf\n~ $ "

        assertTrue(
            TerminalTextMatcher.containsAllWrapTolerant(
                transcript,
                "aaaaaBBBBB",
                "tail-marker",
                terminalCols = cols,
            ),
        )
    }

    @Test
    fun containsAllWrapTolerant_oneFragmentMissing_returnsFalse() {
        val transcript = "~ $ echo hello\nhello\n~ $ "
        assertFalse(
            TerminalTextMatcher.containsAllWrapTolerant(
                transcript,
                "echo hello",
                "missing-marker",
                terminalCols = 63,
            ),
        )
    }

    @Test
    fun containsAllWrapTolerant_noFragments_returnsTrue() {
        // Vacuous-truth; documents the behaviour rather than letting it
        // drift.
        assertTrue(
            TerminalTextMatcher.containsAllWrapTolerant(
                "anything",
                terminalCols = 63,
            ),
        )
    }
}
