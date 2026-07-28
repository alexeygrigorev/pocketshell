package com.pocketshell.core.tmux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1820: pin tmux's two EXACT-target forms.
 *
 * These are cheap shape assertions and are deliberately NOT the load-bearing
 * proof — what real tmux does with these strings is proven on a real server by
 * `app/src/androidTest/.../SiblingSuffixExactTargetDockerTest`. What they DO
 * buy is the thing a reviewer cannot re-derive from a call site: the two forms
 * are different, and picking the wrong one is silently broken.
 *
 * Empirically verified on tmux 3.4 (isolated `-L` socket) while writing this:
 *
 * ```
 * has-session   -t '=foo'   -> exit 1 when only foo-2 lives (correct)
 * has-session   -t 'foo'    -> exit 0 when only foo-2 lives (prefix match)
 * send-keys     -t '=foo'   -> "can't find pane: =foo"     (INVALID form)
 * send-keys     -t '=foo:'  -> exit 0, lands on foo only   (correct)
 * show-options  -t '=foo'   -> "no such session: =foo"     (INVALID form)
 * show-options  -t '=foo:'  -> exit 0, reads foo only      (correct)
 * ```
 *
 * i.e. a target-**session** verb wants `=name`, and a target-pane / target-window
 * verb wants `=name:` — using `=name` there usually fails outright.
 *
 * The one verb where it does NOT fail outright is `list-panes`, and that is the
 * dangerous case, so it is re-verified here too (tmux 3.4, `aaa` current so this
 * is a real prefix match rather than a current-session fallback):
 *
 * ```
 * list-panes -s -t 'zzz'   -> exit 0, zzz-2's rows   (prefix match)
 * list-panes -s -t '=zzz'  -> exit 0, zzz-2's rows   (`=` silently IGNORED)
 * list-panes -s -t '=zzz:' -> exit 1 "can't find session: zzz"
 * ```
 *
 * The per-verb-class behaviour is pinned against a REAL tmux server by
 * `SiblingSuffixExactTargetDockerTest` — one contract test per row of the
 * [TmuxTarget] KDoc table. These JVM assertions only pin the strings.
 */
class TmuxTargetTest {

    @Test
    fun sessionTargetIsTheBareExactPrefixForm() {
        assertEquals("=git-pocketshell", TmuxTarget.session("git-pocketshell"))
    }

    @Test
    fun paneTargetCarriesTheWindowSeparatorTmuxRequires() {
        // The trailing `:` is NOT cosmetic — tmux rejects `=name` for a pane
        // target ("can't find pane: =name"), so dropping it breaks send-keys /
        // set-option / show-options rather than silently prefix-matching.
        assertEquals("=git-pocketshell:", TmuxTarget.pane("git-pocketshell"))
        assertTrue(
            "a pane target must end with the window separator",
            TmuxTarget.pane("anything").endsWith(":"),
        )
    }

    @Test
    fun windowTargetIsSessionColonIndex() {
        assertEquals("=git-pocketshell:0", TmuxTarget.window("git-pocketshell", 0))
        assertEquals("=git-pocketshell:7", TmuxTarget.window("git-pocketshell", 7))
    }

    @Test
    fun everyFormMarksTheNameExactSoASiblingSuffixCannotPrefixMatch() {
        // The whole point of #1820: with `<base>-2` alive, a bare `<base>` target
        // resolves to the sibling. Every builder must emit the `=` marker.
        val name = "tmp-issue898"
        listOf(
            TmuxTarget.session(name),
            TmuxTarget.pane(name),
            TmuxTarget.window(name, 3),
        ).forEach { target ->
            assertTrue("'$target' must be an exact-match target", target.startsWith("="))
            assertTrue("'$target' must still name '$name'", target.contains(name))
            // And it must not be satisfiable by the `-2` sibling: the session
            // component ends at the separator (or at the end of the string).
            val sessionComponent = target.removePrefix("=").substringBefore(':')
            assertEquals(name, sessionComponent)
        }
    }

    @Test
    fun theSessionFormIsDistinguishableFromThePaneFormSoAVerbCannotBeMisfiled() {
        // The #1820 round-3 finding: `list-panes -s` reads like a target-SESSION
        // verb (its `-s` means "every pane in the session") but resolves `-t` as
        // a pane/window, so [session] is silently insufficient there. That makes
        // "which builder does this verb take?" a real decision, not a formality —
        // so the two forms must never be interchangeable by accident.
        val name = "git-pocketshell"
        assertTrue(
            "the session form must NOT already carry the window separator, or a " +
                "misfiled pane verb would accidentally work and the rule would rot",
            !TmuxTarget.session(name).endsWith(":"),
        )
        assertEquals(
            "the pane form is exactly the session form plus the separator",
            TmuxTarget.session(name) + ":",
            TmuxTarget.pane(name),
        )
        assertEquals(
            "the window form is the pane form plus the index",
            TmuxTarget.pane(name) + "4",
            TmuxTarget.window(name, 4),
        )
    }

    @Test
    fun namesWithShellMetacharactersAreLeftIntactForTheCallerToQuote() {
        // TmuxTarget does NOT quote — every call site applies its own
        // shell/control-mode quoting. Mangling here would double-escape.
        assertEquals("=weird name", TmuxTarget.session("weird name"))
        assertEquals("=it's:", TmuxTarget.pane("it's"))
    }
}
