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
 * Issue #662 — [TerminalSurfaceState.visibleScreenIsBlank] is the predicate the
 * tmux ViewModel uses to decide a live pane is BLACK and needs re-seeding from
 * `capture-pane`. The maintainer's symptom is every window of a live session
 * rendering a black pane (just a cursor at home): the local emulator's grid was
 * cleared (the seed never landed, or a reflow/resize wiped it) and the idle
 * remote app emits no fresh `%output` to repaint it. These tests pin the
 * predicate against the exact states that distinguish "black pane" from
 * "has content":
 *
 *  - No producer attached yet -> NOT blank (re-seeding would be premature).
 *  - Producer attached, nothing rendered -> blank.
 *  - A clear-only frame (`ESC[2J ESC[H`, no content) -> blank: this is the
 *    precise black-pane shape, where the grid is wiped and the cursor homed.
 *  - After real content is appended -> NOT blank.
 *  - After content, then a fresh clear-only frame -> blank again (a reflow that
 *    wipes a previously-painted pane).
 */
@RunWith(RobolectricTestRunner::class)
class TerminalSurfaceStateBlankScreenTest {

    private class NoopOutputStream : OutputStream() {
        override fun write(b: Int) = Unit
        override fun write(b: ByteArray, off: Int, len: Int) = Unit
    }

    @Test
    fun unattachedSurfaceIsNotReportedBlank() {
        val state = TerminalSurfaceState()
        // No producer/emulator attached: an unattached surface is "not yet a
        // black pane", so re-seeding it would be premature.
        assertFalse(
            "an unattached surface must not be reported blank",
            state.visibleScreenIsBlank(),
        )
    }

    @Test
    fun attachedButUnpaintedSurfaceIsBlank() = runBlocking {
        val state = TerminalSurfaceState()
        val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val producerJob = state.attachExternalProducer(
            scope = producerScope,
            stdout = MutableSharedFlow(extraBufferCapacity = 1),
            remoteStdin = NoopOutputStream(),
            suppressQueryResponses = true,
        )
        try {
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(
                "an attached surface that has rendered nothing must be blank",
                state.visibleScreenIsBlank(),
            )
        } finally {
            producerJob.cancel()
            producerScope.cancel()
            state.detachExternalProducer()
        }
    }

    @Test
    fun clearOnlyFrameIsBlank() = runBlocking {
        val state = TerminalSurfaceState()
        val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val producerJob = state.attachExternalProducer(
            scope = producerScope,
            stdout = MutableSharedFlow(extraBufferCapacity = 1),
            remoteStdin = NoopOutputStream(),
            suppressQueryResponses = true,
        )
        try {
            // The exact black-pane wire shape: clear the screen, home the
            // cursor, paint NOTHING. This is what a capture-pane seed of an
            // all-blank grid (or a reflow that wiped the pane) leaves behind.
            state.appendRemoteOutput("[2J[H".toByteArray(Charsets.US_ASCII))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(
                "a clear-only frame (no content) must be reported blank",
                state.visibleScreenIsBlank(),
            )
        } finally {
            producerJob.cancel()
            producerScope.cancel()
            state.detachExternalProducer()
        }
    }

    @Test
    fun surfaceWithContentIsNotBlank() = runBlocking {
        val state = TerminalSurfaceState()
        val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val producerJob = state.attachExternalProducer(
            scope = producerScope,
            stdout = MutableSharedFlow(extraBufferCapacity = 1),
            remoteStdin = NoopOutputStream(),
            suppressQueryResponses = true,
        )
        try {
            state.appendRemoteOutput(
                "[2J[HWINDOW-CONTENT-VISIBLE\r\n".toByteArray(Charsets.US_ASCII),
            )
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(
                "a surface that rendered content must NOT be reported blank",
                state.visibleScreenIsBlank(),
            )
        } finally {
            producerJob.cancel()
            producerScope.cancel()
            state.detachExternalProducer()
        }
    }

    @Test
    fun contentThenReflowClearGoesBlankAgain() = runBlocking {
        val state = TerminalSurfaceState()
        val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val producerJob = state.attachExternalProducer(
            scope = producerScope,
            stdout = MutableSharedFlow(extraBufferCapacity = 1),
            remoteStdin = NoopOutputStream(),
            suppressQueryResponses = true,
        )
        try {
            state.appendRemoteOutput(
                "[2J[HSEEDED-FRAME\r\n".toByteArray(Charsets.US_ASCII),
            )
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(
                "precondition: seeded frame must render content",
                state.visibleScreenIsBlank(),
            )
            // A reflow/resize wipes the previously-painted frame and the idle
            // remote app emits no fresh redraw -> the pane is black again. The
            // ViewModel's blank-pane safety net must see this and re-seed.
            state.appendRemoteOutput("[2J[H".toByteArray(Charsets.US_ASCII))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(
                "a wiped frame with no fresh content must be reported blank",
                state.visibleScreenIsBlank(),
            )
        } finally {
            producerJob.cancel()
            producerScope.cancel()
            state.detachExternalProducer()
        }
    }
}
