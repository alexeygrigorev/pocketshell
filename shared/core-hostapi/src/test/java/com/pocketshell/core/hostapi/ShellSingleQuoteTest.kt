package com.pocketshell.core.hostapi

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [shellSingleQuote] is the only thing standing between a session name and the
 * remote shell: the attach/transcript verbs are command STRINGS, not argv
 * lists, so a name with an apostrophe in it is either quoted right or it is a
 * command-injection hole.
 *
 * Two layers of proof here. The byte assertions pin the exact output so a
 * "harmless" refactor cannot change it silently; the round-trip tests hand the
 * result to a REAL `/bin/sh` and check the argument comes back byte-identical,
 * which is the only oracle that actually knows shell quoting rules.
 */
class ShellSingleQuoteTest {

    // --- exact output -----------------------------------------------------

    @Test
    fun `a plain word is wrapped in single quotes`() {
        assertEquals("'work'", shellSingleQuote("work"))
    }

    @Test
    fun `spaces are contained by the quotes, not escaped`() {
        assertEquals("'my session'", shellSingleQuote("my session"))
    }

    @Test
    fun `an embedded apostrophe closes, escapes and reopens`() {
        assertEquals("'it'\\''s'", shellSingleQuote("it's"))
    }

    @Test
    fun `an apostrophe at both ends still produces one balanced word`() {
        // The leading/trailing case is where a naive escaper emits an empty
        // '' pair it then loses: the result must still be ONE shell word.
        assertEquals("''\\''q'\\'''", shellSingleQuote("'q'"))
    }

    @Test
    fun `an empty string becomes an explicit empty argument`() {
        // Not "" -> "": dropping the quotes would delete the argument and
        // shift every later one along.
        assertEquals("''", shellSingleQuote(""))
    }

    @Test
    fun `unicode passes through untouched`() {
        assertEquals("'ünïcødé пример 🚀'", shellSingleQuote("ünïcødé пример 🚀"))
    }

    @Test
    fun `shell metacharacters are inert inside the quotes`() {
        assertEquals(
            "'\$(rm -rf ~) `id` ; | & > < * ? \\'",
            shellSingleQuote("\$(rm -rf ~) `id` ; | & > < * ? \\"),
        )
    }

    @Test
    fun `a newline survives as part of the same word`() {
        assertEquals("'two\nlines'", shellSingleQuote("two\nlines"))
    }

    // --- real-shell round trip -------------------------------------------

    @Test
    fun `every hostile name round-trips through a real shell unchanged`() {
        val names = listOf(
            "work",
            "my session",
            "it's",
            "'q'",
            "''",
            "",
            "ünïcødé пример 🚀",
            "\$(rm -rf ~) `id` ; | & > < * ? \\",
            "two\nlines",
            "tab\there",
            "--help",
            "name with \"double\" quotes",
            "\$HOME",
            "a'b'c'd",
        )

        for (name in names) {
            assertEquals(
                "round-trip mismatch for ${name.toByteArray().toList()}",
                name,
                shellRoundTrip(name),
            )
        }
    }

    @Test
    fun `the round-trip oracle can fail`() {
        // Guards the guard: if `shellRoundTrip` silently returned its input,
        // or /bin/sh quietly did nothing, every assertion above would pass
        // vacuously. Naive double-quoting really does mangle these inputs.
        val naivelyQuoted = "\"\$HOME\""
        assertTrue(
            "expected a naive double-quote to expand, but the oracle returned it verbatim",
            runShellPrintf(naivelyQuoted) != "\$HOME",
        )
    }

    /** `printf %s <quoted>` in a real `/bin/sh`, i.e. what the host would receive. */
    private fun shellRoundTrip(raw: String): String = runShellPrintf(shellSingleQuote(raw))

    private fun runShellPrintf(quotedWord: String): String {
        // A skip here would be a vacuous pass, so a missing /bin/sh fails the
        // test: every machine this repo's suite runs on is a POSIX one.
        assertTrue("/bin/sh is missing; this suite requires a POSIX shell", File(SH).exists())

        val process = ProcessBuilder(SH, "-c", "printf %s $quotedWord")
            .redirectErrorStream(false)
            .start()
        val stdout = process.inputStream.readBytes().toString(Charsets.UTF_8)
        assertTrue("the shell did not exit in time", process.waitFor(30, TimeUnit.SECONDS))
        assertEquals("the shell rejected: printf %s $quotedWord", 0, process.exitValue())
        return stdout
    }

    private companion object {
        const val SH = "/bin/sh"
    }
}
