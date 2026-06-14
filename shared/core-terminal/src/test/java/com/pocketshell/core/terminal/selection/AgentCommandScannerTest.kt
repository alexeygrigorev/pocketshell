package com.pocketshell.core.terminal.selection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for issue #770's engine-command detection — the pure,
 * Android-free core of [findVisibleEngineCommands]. The valid command set is
 * supplied by the caller (the app maps it from `AgentCommandCatalog` for the
 * detected engine); here we use a Claude-Code-shaped set.
 */
class AgentCommandScannerTest {

    // A representative slice of Claude Code's catalog commands.
    private val claude = setOf("/clear", "/compact", "/goal", "/rewind", "/model", "/init")

    private fun detect(line: String, known: Set<String> = claude): List<String> =
        detectEngineCommandsInLine(line, known).map { it.command }

    // --- Positive: a catalog command token is detected ----------------------

    @Test
    fun detectsClearInAStatusHintLine() {
        // The maintainer's motivating case: `/clear` in a Claude hint line.
        assertEquals(listOf("/clear"), detect("? for shortcuts  /clear to reset"))
    }

    @Test
    fun detectsBareClearToken() {
        assertEquals(listOf("/clear"), detect("/clear"))
    }

    @Test
    fun detectsCommandWrappedInBoxDrawingPunctuation() {
        // tmux/agent chrome around the token must not block detection.
        assertEquals(listOf("/compact"), detect("│ /compact │"))
    }

    @Test
    fun detectsMultipleDistinctCommandsLeftToRight() {
        assertEquals(
            listOf("/clear", "/compact"),
            detect("Try /clear or /compact now"),
        )
    }

    @Test
    fun reportsSpanCoveringTheCommandToken() {
        val line = "run /clear here"
        val detected = detectEngineCommandsInLine(line, claude).single()
        assertEquals(4, detected.start)
        assertEquals(10, detected.endExclusive)
        assertEquals("/clear", line.substring(detected.start, detected.endExclusive))
    }

    // --- Negative: only catalog commands; not every /word -------------------

    @Test
    fun doesNotDetectAWordThatIsNotInTheCatalog() {
        // `/help` is a plausible slash-command shape but NOT in this set.
        assertTrue(detect("/help").isEmpty())
        assertTrue(detect("see /foo for details").isEmpty())
    }

    @Test
    fun doesNotDetectFractionOrPathSlashes() {
        // The `/` in fractions / ratios / paths is a separator, not a sigil,
        // and the following token is not even a catalog command — doubly safe.
        assertTrue(detect("5/2 and n/a and TCP/IP").isEmpty())
        assertTrue(detect("~/clear ~/compact").isEmpty())
        assertTrue(detect("path/clear/file").isEmpty())
        assertTrue(detect("https://example.com/clear").isEmpty())
    }

    @Test
    fun doesNotDetectACommandPrefixThatIsPartOfALongerWord() {
        // `/clearcache` and `/clear-all` are not the catalog `/clear`.
        assertTrue(detect("/clearcache").isEmpty())
        assertTrue(detect("/clear-all").isEmpty())
    }

    @Test
    fun emptyKnownSetDetectsNothing() {
        assertTrue(detect("/clear /compact", emptySet()).isEmpty())
    }

    @Test
    fun emptyLineDetectsNothing() {
        assertTrue(detect("").isEmpty())
    }

    // --- Engine scoping: a command valid for ANOTHER engine is not matched --

    @Test
    fun onlyMatchesCommandsInTheSuppliedEngineSet() {
        // `/new` is Codex/OpenCode; not present in this Claude-shaped set, so it
        // is not surfaced even though it is a real command for a different engine.
        val codexish = setOf("/new", "/diff")
        assertEquals(listOf("/new"), detect("/new conversation", codexish))
        // The same line, scanned with the Claude set, surfaces nothing.
        assertTrue(detect("/new conversation", claude).isEmpty())
    }
}
