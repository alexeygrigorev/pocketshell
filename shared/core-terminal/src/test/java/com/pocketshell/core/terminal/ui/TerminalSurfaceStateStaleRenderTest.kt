package com.pocketshell.core.terminal.ui

import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.OutputStream

/**
 * Issue #966/#967 — [TerminalSurfaceState.visibleScreenDivergesFromCapture] is the
 * "mostly-black / stale render on a LIVE transport" oracle that closes the gap the
 * v0.4.17 black-screen heal structurally misses.
 *
 * The maintainer's #966 pane is a connected Claude window rendering only a lone
 * cursor + a couple of scattered status fragments while tmux's authoritative grid
 * holds the full TUI. That reads NEITHER fully blank ([visibleScreenIsBlank]) NOR
 * cleanly partial-blank ([visibleScreenIsPartiallyBlank], ≤3 live lines) — so the
 * v0.4.17 heal SKIPS it. These tests pin:
 *
 *  - The RED gap: the scattered-fragment pane is NOT caught by the v0.4.17 oracle
 *    ([visibleScreenIsBlankOrPartiallyBlank] is false) — proving the heal would skip it.
 *  - The GREEN fix: the new divergence oracle catches it when diffed against tmux's
 *    authoritative capture, while NOT over-firing on a legitimately sparse-but-correct
 *    pane (a real shell whose render already matches tmux) — the over-heal guard.
 *
 * Class coverage (the #966 family on a live channel):
 *  - fully-blank against a real capture        → diverges (blank oracle already heals it too)
 *  - scattered fragments against a full TUI    → diverges (the #966 gap)
 *  - render matches a sparse capture           → does NOT diverge (sparse-but-correct, no thrash)
 *  - render matches a full capture             → does NOT diverge (healthy pane)
 *  - capture itself near-empty                 → does NOT diverge (no real frame to restore)
 */
@RunWith(RobolectricTestRunner::class)
class TerminalSurfaceStateStaleRenderTest {

    private class NoopOutputStream : OutputStream() {
        override fun write(b: Int) = Unit
        override fun write(b: ByteArray, off: Int, len: Int) = Unit
    }

    private fun withAttachedSurface(block: (TerminalSurfaceState) -> Unit) = runBlocking {
        val state = TerminalSurfaceState()
        val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val producerJob = state.attachExternalProducer(
            scope = producerScope,
            stdout = MutableSharedFlow(extraBufferCapacity = 1),
            remoteStdin = NoopOutputStream(),
            suppressQueryResponses = true,
        )
        try {
            block(state)
        } finally {
            producerJob.cancel()
            producerScope.cancel()
            state.detachExternalProducer()
        }
    }

    /** ESC, so the test feeds real control sequences (a literal `[2J` is just text). */
    private val esc = ""

    /** The full-screen TUI tmux says the pane SHOULD contain. */
    private fun fullTuiCapture(): String = buildString {
        repeat(24) { row -> append("status row $row : a lot of real agent TUI content here padding\n") }
    }

    @Test
    fun unattachedSurfaceNeverDiverges() {
        val state = TerminalSurfaceState()
        assertFalse(
            "an unattached surface has nothing rendered to judge as stale",
            state.visibleScreenDivergesFromCapture(fullTuiCapture()),
        )
    }

    @Test
    fun scatteredFragmentPaneIsMissedByV0417OracleButCaughtByDivergence() = withAttachedSurface { state ->
        // The #966 pane: a clear, then a handful of scattered glyphs across the grid
        // (a lone "3", a status fragment) — MORE than 3 live lines / scattered so it
        // is NOT cleanly partial-blank, and NOT fully blank.
        val fragments = buildString {
            append("$esc[2J$esc[H")
            append("3\r\n")
            append("$esc[10;1H")
            append("24m 3 / 8 / 4 / 3 / 31\r\n")
            append("$esc[15;40H")
            append("x\r\n")
            append("$esc[20;5H")
            append("y z\r\n")
        }
        state.appendRemoteOutput(fragments.toByteArray(Charsets.US_ASCII))
        shadowOf(Looper.getMainLooper()).idle()

        // RED: the v0.4.17 heal oracle does NOT catch this — it is the gap.
        assertFalse(
            "precondition: the scattered-fragment pane is NOT fully blank",
            state.visibleScreenIsBlank(),
        )
        // It may or may not read partial-blank depending on the scatter; the load-
        // bearing point is the COMBINED v0.4.17 oracle is FALSE (heal would skip it)
        // for THIS scatter. If this ever becomes true, the v0.4.17 oracle already
        // covers the case and the test's premise must be revisited.
        assertFalse(
            "RED: the v0.4.17 heal oracle (blank || partial-blank) SKIPS the " +
                "scattered-fragment pane — this is the #966 gap",
            state.visibleScreenIsBlankOrPartiallyBlank(),
        )

        // GREEN: the divergence oracle catches it against tmux's authoritative grid.
        assertTrue(
            "the divergence oracle must detect a mostly-black/stale render against a " +
                "full-screen capture (the #966 heal trigger)",
            state.visibleScreenDivergesFromCapture(fullTuiCapture()),
        )
    }

    @Test
    fun fullyBlankPaneDivergesFromAFullCapture() = withAttachedSurface { state ->
        state.appendRemoteOutput("$esc[2J$esc[H".toByteArray(Charsets.US_ASCII))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(
            "precondition: a clear-only frame is fully blank",
            state.visibleScreenIsBlank(),
        )
        assertTrue(
            "a fully-blank render against a full capture diverges (mostly-black)",
            state.visibleScreenDivergesFromCapture(fullTuiCapture()),
        )
    }

    @Test
    fun healthyPaneMatchingCaptureDoesNotDiverge() = withAttachedSurface { state ->
        // The render already carries the full TUI — re-seeding would be a no-op, so
        // the oracle must NOT fire (no reseed-thrash on a healthy pane).
        val frame = buildString {
            append("$esc[2J$esc[H")
            repeat(24) { row -> append("status row $row : a lot of real agent TUI content here padding\r\n") }
        }
        state.appendRemoteOutput(frame.toByteArray(Charsets.US_ASCII))
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(
            "a render that already matches tmux's full capture must NOT be judged stale",
            state.visibleScreenDivergesFromCapture(fullTuiCapture()),
        )
    }

    @Test
    fun sparseButCorrectPaneDoesNotDiverge() = withAttachedSurface { state ->
        // A real shell with little output: the render matches a SPARSE capture. The
        // oracle anchors on divergence-from-tmux, not "few glyphs", so it must NOT
        // over-fire here (the top risk the #967 spike called out).
        val sparse = "user@host:~$ ls\r\nfile-a  file-b\r\n"
        state.appendRemoteOutput("$esc[2J$esc[H$sparse".toByteArray(Charsets.US_ASCII))
        shadowOf(Looper.getMainLooper()).idle()
        // tmux's capture for a sparse pane is itself sparse — same content.
        val sparseCapture = "user@host:~$ ls\nfile-a  file-b\n"
        assertFalse(
            "a sparse-but-CORRECT pane (render matches tmux) must NOT be judged stale",
            state.visibleScreenDivergesFromCapture(sparseCapture),
        )
    }

    @Test
    fun nearEmptyCaptureNeverDiverges() = withAttachedSurface { state ->
        // tmux genuinely has (near) nothing for the pane — there is no real frame to
        // restore, so the blank oracle owns it; divergence must defer (return false).
        state.appendRemoteOutput("$esc[2J$esc[H".toByteArray(Charsets.US_ASCII))
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(
            "a near-empty capture has no real frame to restore — divergence must defer",
            state.visibleScreenDivergesFromCapture("$ \n"),
        )
    }
}
