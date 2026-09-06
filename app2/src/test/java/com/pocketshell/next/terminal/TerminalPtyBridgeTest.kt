package com.pocketshell.next.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.core.transport.FakeHostConnection
import com.pocketshell.core.transport.FakePtyChannel
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [TerminalPtyBridge] against a scripted [FakePtyChannel] and a REAL
 * [TerminalSession] (issue #2566).
 *
 * The bridge is two coroutines and nothing else, so what it owes its caller is
 * exactly four properties, one per case below: a frame arrives whole and in
 * order however it has to be sliced; keystrokes leave in the order they were
 * typed; a stopped bridge is genuinely inert and does not report an end it
 * caused itself; and a fresh bridge can adopt a session a stopped one was
 * driving, carrying over what was typed in the gap between the two. That last
 * one is the reconnect case the deleted `detach()` used to exist for — the
 * session survives the channel, which is what keeps the last frame on screen
 * under the reconnect banner and what makes typing at that banner work.
 *
 * One [StandardTestDispatcher] is both the pump scope's dispatcher and the
 * bridge's main dispatcher, so the whole thing runs in virtual time.
 * Robolectric runs the test body on the main looper's thread, so
 * [TerminalSession.append]'s main-thread assertion is satisfied for real rather
 * than bypassed — a bridge that fed the emulator off the main thread would fail
 * these tests, which is the point.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class TerminalPtyBridgeTest {

    private val dispatcher = StandardTestDispatcher()

    /**
     * The scope the pumps live on, standing in for the ViewModel's own.
     *
     * Deliberately NOT `runTest`'s `backgroundScope`: since coroutines 1.9,
     * `advanceUntilIdle()` only advances the test's foreground work, so a pump
     * parked on `backgroundScope` never runs at all and every assertion below
     * would pass or fail for the wrong reason. A plain scope on the same test
     * dispatcher IS advanced, and [tearDown] ends it the way `onCleared` ends
     * the real one.
     */
    private val pumpScope = CoroutineScope(SupervisorJob() + dispatcher)

    @After
    fun tearDown() {
        pumpScope.cancel()
    }

    @Test
    fun `a frame larger than the slice size reaches the emulator whole and in order`() =
        runTest(dispatcher) {
            val session = session()
            val pty = openPty()
            bridge(pty, session).start()

            // Comfortably more than three slices, with markers at the two ends
            // and the middle so a lost or reordered slice cannot pass.
            val frame = buildString {
                append("HEAD-MARKER\r\n")
                repeat(45) { append("x".repeat(70)).append("\r\n") }
                append("MID-MARKER\r\n")
                repeat(45) { append("y".repeat(70)).append("\r\n") }
                append("TAIL-MARKER\r\n")
            }
            assertTrue(
                "the frame must span several slices to be worth asserting, was ${frame.length}",
                frame.length > 3 * TerminalPtyBridge.DRAIN_SLICE_BYTES,
            )
            pty.emitText(frame)
            advanceUntilIdle()

            val transcript = session.emulator.screen.transcriptText
            val head = transcript.indexOf("HEAD-MARKER")
            val mid = transcript.indexOf("MID-MARKER")
            val tail = transcript.indexOf("TAIL-MARKER")
            assertTrue("the start of the frame is missing: $transcript", head >= 0)
            assertTrue("the middle of the frame is missing", mid >= 0)
            assertTrue("the tail of the frame is missing", tail >= 0)
            assertTrue("the slices must land in order, got $head/$mid/$tail", head < mid)
            assertTrue("the slices must land in order, got $head/$mid/$tail", mid < tail)
        }

    @Test
    fun `typed bytes reach the pty in the order they were written`() = runTest(dispatcher) {
        val session = session()
        val pty = openPty()
        bridge(pty, session).start()
        advanceUntilIdle()

        // Exactly what the vendored TerminalView does per keystroke.
        session.write("echo one\r")
        session.write("echo two\r")
        session.writeCodePoint(false, 'z'.code)
        advanceUntilIdle()

        assertEquals("echo one\recho two\rz", pty.writtenText)
        assertEquals(
            "each write must reach the channel as its own frame, in order",
            listOf("echo one\r", "echo two\r", "z"),
            pty.writes.map { String(it, Charsets.UTF_8) },
        )
    }

    @Test
    fun `stop retires both pumps, keeps later input off the dead channel and reports no end of its own`() =
        runTest(dispatcher) {
            val session = session()
            val pty = openPty()
            var endedCount = 0
            val bridge = bridge(pty, session) { endedCount++ }
            bridge.start()
            session.write("before-stop\r")
            pty.emitText("output-before-stop\r\n")
            advanceUntilIdle()
            assertEquals("before-stop\r", pty.writtenText)

            bridge.stop()
            advanceUntilIdle()

            session.write("after-stop\r")
            pty.emitText("output-after-stop\r\n")
            advanceUntilIdle()

            assertEquals(
                "input typed after stop must not reach the spent channel — the session " +
                    "holds it for whichever bridge attaches next",
                "before-stop\r",
                pty.writtenText,
            )
            val transcript = session.emulator.screen.transcriptText
            assertTrue(
                "what arrived before the stop stays on the grid, got: $transcript",
                transcript.contains("output-before-stop"),
            )
            assertTrue(
                "a stopped bridge must not keep feeding the emulator, got: $transcript",
                !transcript.contains("output-after-stop"),
            )
            assertEquals(
                "stop is the caller's own doing; reporting it back as an ended output " +
                    "stream would restart the reconnect ladder over a teardown",
                0,
                endedCount,
            )
        }

    @Test
    fun `remote EOF reports the output as ended exactly once`() = runTest(dispatcher) {
        val session = session()
        val pty = openPty()
        var endedCount = 0
        bridge(pty, session) { endedCount++ }.start()
        advanceUntilIdle()
        assertEquals(0, endedCount)

        pty.finish(0)
        advanceUntilIdle()

        assertEquals("remote EOF must report exactly once", 1, endedCount)
    }

    @Test
    fun `a dropped transport reports the output as ended`() = runTest(dispatcher) {
        val session = session()
        val pty = openPty()
        var endedCount = 0
        bridge(pty, session) { endedCount++ }.start()
        advanceUntilIdle()

        // A link that goes away under a live session: the channel ends with no
        // exit status at all, which is the reconnect case.
        pty.finish(null)
        advanceUntilIdle()

        assertEquals(1, endedCount)
    }

    /**
     * The reconnect case, and the reason `detach()` no longer has to exist: a
     * session outlives the channel it was attached through, so a second bridge
     * has to be able to adopt it — in BOTH directions — after the first one
     * stopped.
     *
     * Including the gap in between: what the user types at the "Reconnecting"
     * banner, while NO bridge owns the session's sink, has to leave through the
     * channel that attaches next (issue #2566, journey J06). The session holds
     * those bytes; this asserts the bridge actually receives them on `start()`.
     */
    @Test
    fun `a fresh bridge on the same session carries input and output after the old one stopped`() =
        runTest(dispatcher) {
            val session = session()
            val connection = FakeHostConnection()
            val first = openPty(connection)
            val firstBridge = bridge(first, session)
            firstBridge.start()
            session.write("before-the-drop\r")
            first.emitText("frame-from-the-first-attach\r\n")
            advanceUntilIdle()

            // The channel is spent; the screen keeps the session (and its grid).
            firstBridge.stop()
            advanceUntilIdle()

            // Typed at the banner, with nothing attached — the real gap spans a
            // whole SSH dial.
            session.write("typed-at-the-banner\r")
            advanceUntilIdle()

            val second = openPty(connection)
            bridge(second, session).start()
            session.write("after-the-reattach\r")
            second.emitText("frame-from-the-second-attach\r\n")
            advanceUntilIdle()

            assertEquals(
                "what was typed at the banner must leave through the NEW channel, " +
                    "ahead of what was typed after it",
                "typed-at-the-banner\rafter-the-reattach\r",
                second.writtenText,
            )
            assertEquals(
                "and none of them may reach the spent one",
                "before-the-drop\r",
                first.writtenText,
            )
            val transcript = session.emulator.screen.transcriptText
            assertTrue(
                "the last frame from before the drop must survive, got: $transcript",
                transcript.contains("frame-from-the-first-attach"),
            )
            assertTrue(
                "and the reattached channel's output must reach the same grid, got: $transcript",
                transcript.contains("frame-from-the-second-attach"),
            )
        }

    @Test
    fun `resize applies to the emulator and the remote, and stops doing either once stopped`() =
        runTest(dispatcher) {
            val session = session()
            val pty = openPty()
            val bridge = bridge(pty, session)
            bridge.start()

            bridge.resize(100, 40)
            advanceUntilIdle()

            assertEquals(100, session.emulator.mColumns)
            assertEquals(40, session.emulator.mRows)
            assertEquals(listOf(100 to 40), pty.resizes)

            bridge.stop()
            bridge.resize(120, 50)
            advanceUntilIdle()

            assertEquals("a stopped bridge resizes nothing", 100, session.emulator.mColumns)
            assertEquals(listOf(100 to 40), pty.resizes)
        }

    // --- helpers --------------------------------------------------------------

    private fun session(): TerminalSession = createRemoteTerminalSession()

    private suspend fun openPty(
        connection: FakeHostConnection = FakeHostConnection(),
    ): FakePtyChannel {
        connection.enqueuePty(completeAfterFrames = false, exitCode = null)
        return connection.openPty("exec pocketshell sessions attach", 80, 24) as FakePtyChannel
    }

    private fun bridge(
        pty: FakePtyChannel,
        session: TerminalSession,
        onOutputEnded: () -> Unit = {},
    ): TerminalPtyBridge = TerminalPtyBridge(
        pty = pty,
        emulator = session,
        scope = pumpScope,
        mainDispatcher = dispatcher,
        onOutputEnded = onOutputEnded,
    )
}
