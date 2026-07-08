package com.pocketshell.app.tmux

import com.pocketshell.core.terminal.ui.TerminalSurfaceState
import com.pocketshell.uikit.model.KeyKind
import com.pocketshell.uikit.model.KeyModifierState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [TmuxSessionViewModel] that exercise the per-pane
 * reconciliation and command dispatch via a [FakeTmuxClient].
 *
 * Robolectric is required because [TerminalSurfaceState.attachExternalProducer]
 * spins up a [com.pocketshell.core.terminal.bridge.SshTerminalBridge]
 * whose constructor builds a [com.termux.terminal.TerminalSession] that
 * needs a working `Looper` / `Handler` to construct (mirrors the rationale
 * already documented in [com.pocketshell.app.session.SessionViewModelTest]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class TmuxSessionHotkeyTest : TmuxSessionViewModelTestBase() {
    // ---------------------------------------------------------------------
    // Issue #1091: terminal hotkeys — Ctrl+<any key> (sticky Ctrl modifier) +
    // fill the missing control keys (nano's ^X/^O/^K/^W/^G/^T/^J/^\ …).
    // ---------------------------------------------------------------------

    private fun TmuxSessionViewModel.attachWithShellPaneForHotkeys(): FakeTmuxClient {
        val client = FakeTmuxClient()
        attachClientForTest(client)
        applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%0", "@0", "\$0", "shell", paneIndex = 0)),
        )
        return client
    }

    private fun FakeTmuxClient.lastControlHexAfter(before: Int): String? =
        sentCommands.drop(before)
            .lastOrNull { it.startsWith("send-keys -H -t %0 ") }
            ?.removePrefix("send-keys -H -t %0 ")

    /**
     * Issue #1091 — REPRODUCE-FIRST (D33/G10). Uses ONLY the existing public
     * symbols (string labels + `sentCommands`) so it compiles against the
     * UNFIXED code and fails at runtime:
     *  - nano's essential control keys (`^K`/`^W`/`^G`/`^U`/`^T`/`^J`/`^\`)
     *    are not mapped in `onKeyBarKey`, so tapping them sends nothing.
     *  - there is no Ctrl modifier, so arming `Ctrl` then tapping a letter
     *    sends nothing.
     * RED on base, GREEN with the fix.
     */
    @Test
    fun reproduceNanoControlKeysAndStickyCtrlGap() = runTest(scheduler) {
        val vm = newVm()
        val client = vm.attachWithShellPaneForHotkeys()
        advanceUntilIdle()

        val nano = mapOf(
            "^K" to "0b", "^W" to "17", "^G" to "07", "^U" to "15",
            "^T" to "14", "^J" to "0a", "^\\" to "1c",
        )
        nano.forEach { (label, hex) ->
            val before = client.sentCommands.size
            vm.onKeyBarKey("%0", label)
            advanceUntilIdle()
            assertEquals(
                "hotkey $label must emit control byte 0x$hex; got ${client.sentCommands.drop(before)}",
                hex,
                client.lastControlHexAfter(before),
            )
        }

        val before = client.sentCommands.size
        vm.onKeyBarKey("%0", "Ctrl")
        vm.onKeyBarKey("%0", "x")
        advanceUntilIdle()
        assertEquals(
            "sticky Ctrl + x must emit ^X (0x18); got ${client.sentCommands.drop(before)}",
            "18",
            client.lastControlHexAfter(before),
        )
    }

    /**
     * Issue #1091 acceptance: the panel OFFERS the previously-missing control
     * keys, keeps the existing 8 + the interrupt/EOF chords, and adds the
     * sticky `Ctrl` modifier + the a–z LETTERS section. This is the "fill the
     * missing control keys" + "restore a Ctrl modifier" UI-offering gap (the
     * send path already mapped `^X`/`^O`, the panel just never showed them).
     */
    @Test
    fun hotkeyPanelOffersNanoKeysStickyCtrlAndLetters() {
        val labels = TmuxHotkeyPanelSections.flatMap { it.keys }.map { it.label }

        listOf("^X", "^O", "^K", "^W", "^G", "^U", "^T", "^J", "^\\").forEach {
            assertTrue("panel must offer the filled control key $it; has $labels", labels.contains(it))
        }
        listOf("^A", "^B", "^C", "^D", "^E", "^L", "^R", "^Z").forEach {
            assertTrue("panel must keep existing control key $it", labels.contains(it))
        }
        assertTrue("panel must keep ^C×2", labels.contains(TmuxHotkeyInterruptX2Label))
        assertTrue("panel must keep ^D×2", labels.contains(TmuxHotkeyEofX2Label))

        val ctrl = TmuxHotkeyPanelSections.flatMap { it.keys }
            .firstOrNull { it.label == TmuxHotkeyCtrlModifierLabel }
        assertNotNull("panel must offer a sticky Ctrl modifier", ctrl)
        assertEquals("Ctrl must be a Modifier key", KeyKind.Modifier, ctrl!!.kind)

        ('a'..'z').forEach { c ->
            assertTrue("panel must offer letter '$c' for Ctrl composition", labels.contains(c.toString()))
        }
    }

    /**
     * Issue #1091 acceptance: every direct control hotkey emits its exact
     * control byte through the `send-keys -H` overlay (class coverage over the
     * whole filled set, not just the reported `^X`/`^O`).
     */
    @Test
    fun everyDirectControlHotkeyEmitsItsByte() = runTest(scheduler) {
        val vm = newVm()
        val client = vm.attachWithShellPaneForHotkeys()
        advanceUntilIdle()

        val expected = mapOf(
            "^A" to "01", "^B" to "02", "^C" to "03", "^D" to "04", "^E" to "05",
            "^G" to "07", "^J" to "0a", "^K" to "0b", "^L" to "0c", "^O" to "0f",
            "^R" to "12", "^T" to "14", "^U" to "15", "^W" to "17", "^X" to "18",
            "^Z" to "1a", "^\\" to "1c",
        )
        expected.forEach { (label, hex) ->
            val before = client.sentCommands.size
            vm.onKeyBarKey("%0", label)
            advanceUntilIdle()
            assertEquals(
                "hotkey $label must emit 0x$hex; got ${client.sentCommands.drop(before)}",
                "send-keys -H -t %0 $hex",
                client.sentCommands.drop(before).lastOrNull { it.startsWith("send-keys -H -t %0 ") },
            )
        }
    }

    /**
     * Issue #1091 acceptance: a one-shot sticky `Ctrl` composes with ANY letter
     * a–z to its correct control byte (0x01–0x1A) and then AUTO-RELEASES after
     * the single key.
     */
    @Test
    fun stickyCtrlOneShotComposesWithAnyLetterThenAutoReleases() = runTest(scheduler) {
        val vm = newVm()
        val client = vm.attachWithShellPaneForHotkeys()
        advanceUntilIdle()

        val cases = mapOf(
            'a' to "01", 'c' to "03", 'k' to "0b", 'o' to "0f",
            'x' to "18", 'z' to "1a",
        )
        cases.forEach { (letter, hex) ->
            assertEquals("Ctrl must start Off", KeyModifierState.Off, vm.ctrlModifier.value)
            vm.onKeyBarKey("%0", "Ctrl")
            assertEquals("single tap arms one-shot", KeyModifierState.OneShot, vm.ctrlModifier.value)
            val before = client.sentCommands.size
            vm.onKeyBarKey("%0", letter.toString())
            advanceUntilIdle()
            assertEquals(
                "Ctrl+$letter must emit 0x$hex; got ${client.sentCommands.drop(before)}",
                hex,
                client.lastControlHexAfter(before),
            )
            assertEquals("one-shot Ctrl must auto-release", KeyModifierState.Off, vm.ctrlModifier.value)
        }

        // With Ctrl OFF a letter is typed literally — NOT a control byte.
        val before = client.sentCommands.size
        vm.onKeyBarKey("%0", "a")
        advanceUntilIdle()
        val after = client.sentCommands.drop(before)
        assertTrue(
            "Ctrl off: letter must be a literal send-keys; got $after",
            after.any { it == "send-keys -l -t %0 -- 'a'" },
        )
        assertFalse(
            "Ctrl off: letter must NOT be a control byte; got $after",
            after.any { it == "send-keys -H -t %0 01" },
        )
    }

    /**
     * Issue #1091 acceptance: a double tap LOCKS `Ctrl` sticky — it survives
     * each composed key and stays until tapped off.
     */
    @Test
    fun stickyCtrlDoubleTapLocksAndPersists() = runTest(scheduler) {
        val vm = newVm()
        val client = vm.attachWithShellPaneForHotkeys()
        advanceUntilIdle()

        vm.onKeyBarKey("%0", "Ctrl")
        vm.onKeyBarKey("%0", "Ctrl")
        assertEquals("double tap locks", KeyModifierState.Locked, vm.ctrlModifier.value)

        var before = client.sentCommands.size
        vm.onKeyBarKey("%0", "x")
        advanceUntilIdle()
        assertEquals("locked Ctrl+x -> 0x18", "18", client.lastControlHexAfter(before))
        assertEquals("locked Ctrl survives the key", KeyModifierState.Locked, vm.ctrlModifier.value)

        before = client.sentCommands.size
        vm.onKeyBarKey("%0", "k")
        advanceUntilIdle()
        assertEquals("locked Ctrl+k -> 0x0b", "0b", client.lastControlHexAfter(before))
        assertEquals("locked Ctrl still survives", KeyModifierState.Locked, vm.ctrlModifier.value)

        // A third tap on the modifier releases it.
        vm.onKeyBarKey("%0", "Ctrl")
        assertEquals("tapping a locked Ctrl releases it", KeyModifierState.Off, vm.ctrlModifier.value)
    }

    /**
     * Issue #1091 acceptance: the active modifier state is exposed (drives the
     * panel accent) — Off -> OneShot -> Locked -> Off on consecutive taps.
     */
    @Test
    fun ctrlModifierActiveStateCyclesForAccentRendering() = runTest(scheduler) {
        val vm = newVm()
        vm.attachWithShellPaneForHotkeys()
        advanceUntilIdle()

        assertEquals(KeyModifierState.Off, vm.ctrlModifier.value)
        vm.onKeyBarKey("%0", "Ctrl")
        assertEquals(KeyModifierState.OneShot, vm.ctrlModifier.value)
        vm.onKeyBarKey("%0", "Ctrl")
        assertEquals(KeyModifierState.Locked, vm.ctrlModifier.value)
        vm.onKeyBarKey("%0", "Ctrl")
        assertEquals(KeyModifierState.Off, vm.ctrlModifier.value)
    }

    /**
     * Issue #1091 acceptance: the existing single `^C`/`^D`/`^Z` and the
     * doubled `^C×2`/`^D×2` interrupt/EOF chords are unchanged (no #787/#784
     * regression).
     */
    @Test
    fun interruptAndEofChordsUnchanged() = runTest(scheduler) {
        val vm = newVm()
        val client = vm.attachWithShellPaneForHotkeys()
        advanceUntilIdle()

        var before = client.sentCommands.size
        vm.onKeyBarKey("%0", "^C")
        advanceUntilIdle()
        assertEquals("single ^C -> 0x03", "03", client.lastControlHexAfter(before))

        before = client.sentCommands.size
        vm.onKeyBarKey("%0", "^D")
        advanceUntilIdle()
        assertEquals("single ^D -> 0x04", "04", client.lastControlHexAfter(before))

        before = client.sentCommands.size
        vm.onKeyBarKey("%0", "^Z")
        advanceUntilIdle()
        assertEquals("single ^Z -> 0x1a", "1a", client.lastControlHexAfter(before))

        before = client.sentCommands.size
        vm.onKeyBarKey("%0", TmuxHotkeyInterruptX2Label)
        advanceUntilIdle()
        assertEquals("^C×2 -> doubled 0x03 byte", "03 03", client.lastControlHexAfter(before))

        before = client.sentCommands.size
        vm.onKeyBarKey("%0", TmuxHotkeyEofX2Label)
        advanceUntilIdle()
        assertEquals("^D×2 -> doubled 0x04 byte", "04 04", client.lastControlHexAfter(before))
    }
}
