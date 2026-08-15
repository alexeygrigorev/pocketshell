package com.pocketshell.core.terminal.ui

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Issue #2154 — `copyModeChanged` must no longer be inert, and the selection it
 * carries must be observable on [TerminalSurfaceState].
 *
 * Before #2154 the production client override was literally
 * `override fun copyModeChanged(copyMode: Boolean) = Unit`, so the vendored
 * view's selection lifecycle died at the seam and NO app-layer code could know a
 * selection was live — which is why every PocketShell viewport reset ran straight
 * through the user's drag.
 *
 * The MUTATION that reddens this file (G6): restoring that `= Unit` body reddens
 * [copyModeChangedForwardsBothSelectionEdges] and
 * [aClientWiredToASurfaceStatePublishesSelectionOntoIt], and nothing else. The
 * end-to-end proof that [TerminalSurface] actually performs this wiring on a real
 * device — a real long-press on the real composable — is
 * `com.termux.view.TerminalSelectionViewportStabilityInstrumentedTest`, which hard-
 * fails its precondition if the state flag does not flip.
 */
@RunWith(RobolectricTestRunner::class)
class TerminalSurfaceSelectionStateTest {

    @Test
    fun copyModeChangedForwardsBothSelectionEdges() {
        val client = PocketShellTerminalViewClient()
        val observed = mutableListOf<Boolean>()
        client.onCopyModeChanged = { observed.add(it) }

        // The vendored TerminalView fires this from startTextSelectionMode and
        // stopTextSelectionMode respectively.
        client.copyModeChanged(true)
        client.copyModeChanged(false)

        assertEquals(
            "copyModeChanged must forward BOTH selection edges — a start-only signal would " +
                "leave the viewport frozen forever after one selection",
            listOf(true, false),
            observed,
        )
    }

    @Test
    fun aClientWiredToASurfaceStatePublishesSelectionOntoIt() = runBlocking {
        val state = TerminalSurfaceState()
        val client = PocketShellTerminalViewClient()
        // Exactly the wiring TerminalSurface installs.
        client.onCopyModeChanged = { selecting -> state.setTextSelectionActive(selecting) }

        assertFalse(
            "a fresh surface has no selection",
            state.isTextSelectionActive(),
        )
        assertFalse(state.textSelectionActive.first())

        client.copyModeChanged(true)
        assertTrue(
            "a live selection must be observable from app-layer code",
            state.isTextSelectionActive(),
        )
        assertTrue(state.textSelectionActive.first())

        client.copyModeChanged(false)
        assertFalse(
            "ending the selection must release the viewport again — otherwise the #184 IME pin " +
                "would be dead for the rest of the session",
            state.isTextSelectionActive(),
        )
        assertFalse(state.textSelectionActive.first())
    }

    /** A throwing observer must never escape into the vendored view's selection path. */
    @Test
    fun aFailingObserverIsReportedNotPropagated() {
        val client = PocketShellTerminalViewClient()
        val failures = mutableListOf<Throwable>()
        client.onTerminalSurfaceError = { failures.add(it) }
        client.onCopyModeChanged = { error("boom") }

        client.copyModeChanged(true)

        assertEquals(
            "the failure must reach the surface's error sink instead of crashing the vendored " +
                "startTextSelectionMode call",
            1,
            failures.size,
        )
    }
}
