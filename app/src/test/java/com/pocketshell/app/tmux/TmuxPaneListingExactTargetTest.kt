package com.pocketshell.app.tmux

import com.pocketshell.core.tmux.TmuxTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1820 (round 3) — the reconcile `list-panes` must carry the EXACT
 * **pane** target.
 *
 * ## The bug this pins
 *
 * `list-panes -s` reads like a target-session verb, so `-t '<name>'` looks
 * harmless. It is not: `-s` widens what gets *listed*, it does not change how
 * `-t` is *resolved*, so `list-panes` keeps a pane/window lookup and the bare
 * name prefix-matches. Verified on tmux 3.4 with `aaa` current (so this is a
 * genuine prefix match, not a fall-back to the current session):
 *
 * ```
 * $ tmux list-panes -s -t 'zzz'   -F '#{session_name}'  -> zzz-2   exit 0
 * $ tmux list-panes -s -t '=zzz'  -F '#{session_name}'  -> zzz-2   exit 0   # `=` IGNORED
 * $ tmux list-panes -s -t '=zzz:' -F '#{session_name}'  -> can't find session: zzz   exit 1
 * ```
 *
 * With `<base>` gone and `<base>-2` alive (the ROUTINE outcome of a second
 * same-folder create since #1820), the bare form returns the SIBLING's rows.
 * That is worse than reading the wrong session, because of how the caller is
 * written:
 *
 *  - `TmuxSessionViewModel.reconcilePanes` guards "preserve last known state"
 *    on the PRE-filter parse being EMPTY. The sibling's rows are non-empty, so
 *    the guard does not fire;
 *  - `applyParsedPanes` then drops every row on its exact-session-name filter.
 *
 * Net effect: `_panes` is **silently cleared** — a blank terminal with no error
 * and nothing to retry — instead of the named, retryable
 * `PaneReconcileResult.Failed` an errored `list-panes` produces.
 *
 * ## Why these are only shape assertions
 *
 * What real tmux does with the string is the actual contract, and a `contains
 * "="` check cannot fail for the reason the user's blank terminal happened. The
 * load-bearing proof is
 * `app/src/androidTest/.../SiblingSuffixExactTargetDockerTest#paneReconcileOnAGoneSessionFailsLoudlyInsteadOfReturningTheSiblingsPanes`,
 * which drives THIS command through the real `listPanesViaExec` lane against a
 * real tmux in the real `<base>` gone / `<base>-2` alive state. These cheap
 * assertions exist so a regression at the builder is caught in the per-PR Unit
 * job rather than only in the batched emulator lane.
 */
class TmuxPaneListingExactTargetTest {

    @Test
    fun reconcileTargetsTheExactPaneFormNotTheBareSessionName() {
        val command = buildTmuxPaneListingCommand("git-pocketshell")
        assertTrue(
            "the reconcile target must be the EXACT pane form; got: $command",
            "-s -t '${TmuxTarget.pane("git-pocketshell")}' " in command,
        )
        assertFalse(
            "a bare `-t '<name>'` prefix-matches a live `<name>-2`; got: $command",
            "-t 'git-pocketshell'" in command,
        )
    }

    @Test
    fun theSessionFormAloneIsNotEnoughForListPanes() {
        // The trap the KDoc rule exists to close: `=<name>` (no separator) is
        // silently IGNORED by list-panes and still prefix-matches. If the builder
        // ever regresses to `TmuxTarget.session`, this fails.
        val command = buildTmuxPaneListingCommand("git-pocketshell")
        assertFalse(
            "list-panes needs the pane form (`=<name>:`), not the session form; got: $command",
            "-t '${TmuxTarget.session("git-pocketshell")}' " in command,
        )
    }

    @Test
    fun theWholeSessionFlagIsPreservedAlongsideTheExactTarget() {
        // #158: `-s` lists panes across every window in the session. Verified on
        // tmux 3.4 that the pane form keeps that: with `foo-2` spanning two
        // windows, `-t 'foo-2'` and `-t '=foo-2:'` both return all three panes.
        val command = buildTmuxPaneListingCommand("work")
        assertTrue("`-s` must survive the exact-target change; got: $command", "-s " in command)
    }

    @Test
    fun aQuoteInTheSessionNameIsStillEscapedAroundTheExactMarkers() {
        // The `=`/`:` markers must not disturb the existing single-quote escaping
        // — a name with a quote still has to arrive as ONE shell argument.
        val command = buildTmuxPaneListingCommand("it's work")
        assertTrue(
            "the exact target must still be single-quote escaped; got: $command",
            "-s -t '=it'\\''s work:' " in command,
        )
    }

    @Test
    fun aNullSessionEmitsNoTargetAtAll() {
        // The server-wide form (no `-t`) is unchanged: there is no session name
        // to be exact about.
        val command = buildTmuxPaneListingCommand(null)
        assertFalse("a null session must emit no target; got: $command", "-t " in command)
        assertEquals(
            "…and no `-s` either — `-s` is only meaningful with a target",
            -1,
            command.indexOf("-s "),
        )
    }
}
