package com.pocketshell.app.portfwd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #2176: the two tmux commands behind the durable ports list.
 *
 * Both properties pinned here have already cost this repository real bugs
 * elsewhere, which is why they are gated cheaply rather than only by a Docker
 * journey:
 *
 *  - a **bare `tmux`** read is corrupted on any host whose SSH exec channel
 *    lacks a UTF-8 locale (issue #2160, measured on this repo's own `agents`
 *    fixture);
 *  - a **bare `-t <name>`** target prefix-matches, so with a `<base>` /
 *    `<base>-2` sibling pair — the routine outcome of #1820's unique-name
 *    resolution — it reads or WRITES the neighbour's list. In a feature whose
 *    entire purpose is attribution, that is the worst possible bug.
 */
class SessionPortsHostOptionsTest {

    private val option = "@ps_session_ports"

    @Test
    fun `read forces UTF-8 mode so a non-UTF-8 host cannot corrupt the value`() {
        val command = SessionPortsHostOptions.readCommand("work")

        assertTrue(
            "read must use the locale-proof `tmux -u` client (issue #2160); got: $command",
            isLocaleProofInvocation(command),
        )
    }

    /**
     * The mutation that must redden the assertion above: drop the `-u`. This
     * demonstrates the check is not decorative — a bare invocation is rejected
     * by the same predicate that accepts the real one.
     */
    @Test
    fun `the locale-proof predicate rejects a bare tmux read`() {
        assertFalse(isLocaleProofInvocation("tmux show-options -v -t '=work:' $option"))
        assertTrue(isLocaleProofInvocation("tmux -u show-options -v -t '=work:' $option"))
    }

    /**
     * `-u` is a CLIENT flag: tmux decides UTF-8 mode at client start-up, so the
     * flag has to precede the sub-command. `show-options -u` is an unknown flag
     * and the command fails outright.
     */
    @Test
    fun `the UTF-8 flag precedes the sub-command`() {
        val command = SessionPortsHostOptions.readCommand("work")

        assertTrue(command.startsWith("tmux -u show-options "))
    }

    @Test
    fun `read targets the session exactly, never by prefix`() {
        val command = SessionPortsHostOptions.readCommand("work")

        assertTrue("exact target form required; got: $command", "'=work:'" in command)
        assertFalse("a bare prefix-matching target must not appear", "-t 'work'" in command)
    }

    @Test
    fun `write targets the session exactly, never by prefix`() {
        val command = SessionPortsHostOptions.writeCommand("work", "v1,5173:1:node:x")

        assertTrue("exact target form required; got: $command", "'=work:'" in command)
        assertFalse("a bare prefix-matching target must not appear", "-t 'work'" in command)
    }

    /**
     * The whole feature rests on the option being SESSION-scoped. A stray `-g`
     * would make one global list shared by every session on the host — i.e. it
     * would silently rebuild the host-wide panel this feature exists to
     * replace.
     */
    @Test
    fun `write is session-scoped, not global`() {
        val command = SessionPortsHostOptions.writeCommand("work", "v1")

        assertFalse("`-g` would make the list host-global: $command", " -g " in command)
        assertTrue(command.startsWith("tmux set-option -t "))
    }

    @Test
    fun `two sessions produce two distinct targets`() {
        assertNotEquals(
            SessionPortsHostOptions.readCommand("alpha"),
            SessionPortsHostOptions.readCommand("beta"),
        )
        assertNotEquals(
            SessionPortsHostOptions.writeCommand("alpha", "v1"),
            SessionPortsHostOptions.writeCommand("beta", "v1"),
        )
    }

    /**
     * A missing option, a dead session, or an old tmux must read as "nothing
     * recorded", not as an error the panel has to render. The `|| true` is what
     * makes the exec's exit status irrelevant.
     */
    @Test
    fun `both commands are fail-safe`() {
        assertTrue(SessionPortsHostOptions.readCommand("work").endsWith("|| true"))
        assertTrue(SessionPortsHostOptions.writeCommand("work", "v1").endsWith("|| true"))
    }

    /** A session name with a quote must stay one shell argument. */
    @Test
    fun `quotes a hostile session name`() {
        val command = SessionPortsHostOptions.readCommand("it's mine")

        assertTrue(command, "'=it'\"'\"'s mine:'" in command)
    }

    @Test
    fun `writes the encoded value as a single quoted argument`() {
        val encoded = SessionPortMentionCodec.encode(
            listOf(SessionPortMention(5173, 1L, "node", "Local: http://localhost:5173/")),
        )

        val command = SessionPortsHostOptions.writeCommand("work", encoded)

        assertTrue(command, "$option '$encoded'" in command)
    }

    @Test
    fun `option name matches the ps_ user-option convention`() {
        assertEquals(option, SessionPortsHostOptions.OPTION)
    }

    /**
     * Mirrors `TmuxRead.isLocaleProofInvocation` from issue #2160, which is not
     * on this branch's base. When #2160 lands, this local copy and
     * [SessionPortsHostOptions.READ_CLIENT] both collapse onto `TmuxRead`.
     */
    private fun isLocaleProofInvocation(command: String): Boolean =
        Regex("""\btmux\s+-u\b""").containsMatchIn(command)
}
