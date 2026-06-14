package com.pocketshell.core.ssh

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #775 finding F1 — the shared POSIX single-quote escaping primitive
 * reused by both the upload path (`cat > '<path>'`) and the inbox
 * `mkdir -p '<dir>'` directory prefix. A literal `'` must become the canonical
 * `'\''` close-escape-reopen sequence so no value can break out of its quoted
 * shell token; quote-free values are just wrapped, unchanged otherwise.
 */
class ShellQuotingTest {

    @Test
    fun wrapsAPlainValueInSingleQuotes() {
        assertEquals("'hello'", shellSingleQuote("hello"))
    }

    @Test
    fun wrapsAnAbsolutePathUnchangedApartFromTheQuotes() {
        assertEquals("'/home/tester/inbox'", shellSingleQuote("/home/tester/inbox"))
    }

    @Test
    fun escapesAnEmbeddedSingleQuoteAsCloseEscapeReopen() {
        // /home/o'reilly → '/home/o'\''reilly'
        assertEquals("'/home/o'\\''reilly'", shellSingleQuote("/home/o'reilly"))
    }

    @Test
    fun neutralisesACommandInjectionAttemptIntoOneInertToken() {
        // A deliberately hostile value stays a single argument: the only way
        // out would be a raw `'`, and every `'` is rewritten to `'\''`.
        val hostile = "x';touch /tmp/pwned;'"
        val quoted = shellSingleQuote(hostile)
        assertEquals("'x'\\'';touch /tmp/pwned;'\\'''", quoted)
        // After removing the inert escape sequences, the only remaining quotes
        // are the balanced wrapping pair.
        val remaining = quoted.replace("'\\''", "").count { it == '\'' }
        assertEquals("wrapping quotes must stay balanced", 0, remaining % 2)
    }

    @Test
    fun handlesAnEmptyValue() {
        assertEquals("''", shellSingleQuote(""))
    }

    @Test
    fun escapesShellMetacharactersByQuotingNotByRewriting() {
        // $, `, ;, &, spaces etc. are inert inside single quotes — no rewrite
        // needed, just the wrapping quotes.
        assertEquals("'a b\$c;d&e`f'", shellSingleQuote("a b\$c;d&e`f"))
    }
}
