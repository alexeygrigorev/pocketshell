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

    // -- quoteRemotePathForShell: the ~-expanding sibling (#777 G5) ------------
    //
    // The path-quoting variant used by the upload / download / listing paths in
    // RealSshSession. Unlike shellSingleQuote it deliberately leaves a LEADING
    // bare `~` or `~/` UNQUOTED so the remote shell still expands it to $HOME,
    // single-quoting only the remainder. These pin the four branches: bare `~`,
    // `~/`-prefixed, absolute, and the literal-`~` cases a POSIX shell would NOT
    // expand (mid-word `~`, `~user/...`). Previously only covered indirectly.

    @Test
    fun bareTildeIsLeftUnquotedSoTheShellExpandsItToHome() {
        // A bare `~` must stay an unquoted `~` so the shell expands it to $HOME.
        assertEquals("~", quoteRemotePathForShell("~"))
    }

    @Test
    fun tildeSlashPrefixExpandsButTheRemainderIsQuoted() {
        // `~/a b/c.png` → `~/'a b/c.png'`: the `~/` expands, the rest (which has
        // a space) is single-quoted so it can't break the command line.
        assertEquals("~/'a b/c.png'", quoteRemotePathForShell("~/a b/c.png"))
    }

    @Test
    fun tildeSlashWithEmptyRemainderStaysExpandable() {
        // `~/` with nothing after it stays `~/` (still expandable, nothing to
        // quote) — the empty-remainder branch.
        assertEquals("~/", quoteRemotePathForShell("~/"))
    }

    @Test
    fun absolutePathIsFullySingleQuoted() {
        // No leading tilde → the whole path is single-quoted exactly like
        // shellSingleQuote would.
        assertEquals("'/etc/hosts'", quoteRemotePathForShell("/etc/hosts"))
    }

    @Test
    fun namedTildeIsQuotedLiterallyNotExpanded() {
        // `~user/...` is NOT a bare-`~`/`~/` prefix, so it is quoted literally
        // (a POSIX shell expands `~user` to that user's home, but we only honour
        // the bare-`~`-to-$HOME shorthand and quote everything else verbatim).
        assertEquals("'~user/file'", quoteRemotePathForShell("~user/file"))
    }

    @Test
    fun midWordTildeIsQuotedLiterally() {
        // A `~` that is not the first character is just a literal tilde — the
        // shell never expands it there, and we single-quote the whole path.
        assertEquals("'/home/a~b/c'", quoteRemotePathForShell("/home/a~b/c"))
    }

    @Test
    fun tildeSlashRemainderWithAnEmbeddedSingleQuoteIsEscaped() {
        // The remainder after `~/` is run through the same single-quote escape,
        // so an embedded `'` becomes the canonical close-escape-reopen sequence
        // — the injection guard still applies past the expandable prefix.
        assertEquals("~/'o'\\''reilly/x'", quoteRemotePathForShell("~/o'reilly/x"))
    }
}
