package com.pocketshell.core.terminal.session

import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicReference

/**
 * The contract of PocketShell's own [TerminalSession] (issue #2566).
 *
 * This class replaced upstream Termux's local-pty session wholesale, so nothing
 * upstream's test suite covers applies to it: what it owes its callers is a
 * small, exact surface. The vendored `TerminalView` needs `getEmulator`,
 * `updateSize`, `write`, `writeCodePoint` and the two clipboard callbacks; the
 * vendored `TerminalEmulator` needs the `TerminalOutput` contract; app2 needs
 * `append`, `setInputSink` and `updateTerminalSessionClient`.
 *
 * Every case here drives the REAL emulator — there is no seam between the two
 * and inventing one would test the seam instead of the terminal. Robolectric
 * because [TerminalSession.append] asserts the main thread, which is the whole
 * point of that method, and because the emulator logs through Android's `Log`.
 */
@RunWith(RobolectricTestRunner::class)
class TerminalSessionTest {

    // --- write / the input sink ----------------------------------------------

    @Test
    fun `write hands the exact bytes to the installed sink, in order`() {
        val sink = RecordingSink()
        val session = session()
        session.setInputSink(sink)

        session.write("ls -la\r")
        session.write(byteArrayOf(0x03), 0, 1)
        // An offset write, the shape `writeCodePoint` and the vendored view use.
        session.write(byteArrayOf(9, 9, 'h'.code.toByte(), 'i'.code.toByte(), 9), 2, 2)

        assertEquals(
            listOf("ls -la\r", "", "hi"),
            sink.chunks.map { String(it, Charsets.ISO_8859_1) },
        )
        assertEquals("ls -la\rhi", sink.text())
    }

    /**
     * The reconnect case, at its smallest (issue #2566).
     *
     * `SessionViewModel.releaseChannel()` clears the sink the moment a channel
     * dies, and the next bridge installs its own only after a full SSH dial and
     * a `sessions attach` — seconds during which the user is still typing at the
     * "Reconnecting" banner. Upstream's terminal-to-process ring buffer used to
     * carry those bytes; this buffer is what replaces it, and journey J06 is
     * what fails without it.
     */
    @Test
    fun `bytes written with no sink reach the sink installed later, in order and exactly once`() {
        val session = session()

        // Nothing is listening: the state between one bridge stopping and the
        // next one starting (and the state a fresh session starts in).
        session.write("echo typed-while-reconnecting")
        session.writeCodePoint(false, '\r'.code)

        val sink = RecordingSink()
        session.setInputSink(sink)
        session.write("typed after the reattach")

        assertEquals(
            "what was typed while detached must arrive first, then what came after",
            "echo typed-while-reconnecting\rtyped after the reattach",
            sink.text(),
        )

        // Exactly once: the flush emptied the buffer, so a later install gets
        // nothing and nobody sees the command twice.
        val later = RecordingSink()
        session.setInputSink(later)
        assertEquals("held bytes must be handed over once, not replayed", "", later.text())
        assertEquals("echo typed-while-reconnecting\rtyped after the reattach", sink.text())
    }

    @Test
    fun `a sink installed after another was removed gets only what was written in between`() {
        val first = RecordingSink()
        val second = RecordingSink()
        val session = session()

        session.setInputSink(first)
        session.write("before the drop")
        session.setInputSink(null)
        session.write("typed at the banner")
        session.setInputSink(second)
        session.write("after the reattach")

        assertEquals("a removed sink must stop receiving", "before the drop", first.text())
        assertEquals(
            "the new sink gets the gap's bytes, then the live ones — and nothing " +
                "the previous sink already had",
            "typed at the bannerafter the reattach",
            second.text(),
        )
        assertEquals(
            listOf("typed at the banner", "after the reattach"),
            second.chunks.map { String(it, Charsets.ISO_8859_1) },
        )
    }

    /**
     * The bound exists so a write can never block the main thread: upstream's
     * queue parked the writer when it filled, which here would be the UI thread
     * inside a keystroke. Losing a burst nobody can see the effect of is the
     * better failure.
     */
    @Test
    fun `bytes past the pending capacity are dropped without throwing or blocking`() {
        val capacity = TerminalSession.PENDING_INPUT_CAPACITY_BYTES
        val session = session()

        val filler = "x".repeat(capacity - 4)
        session.write(filler)
        session.write("keep") // exactly fills the buffer
        session.write("overflow") // no room at all
        session.write("more")

        val sink = RecordingSink()
        session.setInputSink(sink)

        assertEquals(capacity, sink.text().length)
        assertEquals(filler + "keep", sink.text())

        // A write that straddles the bound is truncated, not rejected whole.
        val straddling = session()
        straddling.write("y".repeat(capacity - 3))
        straddling.write("ABCDEF")

        val tail = RecordingSink()
        straddling.setInputSink(tail)

        assertEquals(capacity, tail.text().length)
        assertTrue(
            "the part that fits is kept: " + tail.text().takeLast(6),
            tail.text().endsWith("ABC"),
        )
    }

    // --- writeCodePoint -------------------------------------------------------

    @Test
    fun `writeCodePoint encodes all four UTF-8 widths`() {
        val sink = RecordingSink()
        val session = session()
        session.setInputSink(sink)

        session.writeCodePoint(false, 'A'.code) // 1 byte
        session.writeCodePoint(false, 0x00E9) // e-acute, 2 bytes
        session.writeCodePoint(false, 0x20AC) // euro sign, 3 bytes
        session.writeCodePoint(false, 0x1F600) // grinning face, 4 bytes

        assertEquals(listOf(1, 2, 3, 4), sink.chunks.map { it.size })
        assertEquals("Aé€😀", sink.textUtf8())
    }

    @Test
    fun `prependEscape puts ESC in front of the encoded code point`() {
        val sink = RecordingSink()
        val session = session()
        session.setInputSink(sink)

        session.writeCodePoint(true, 'b'.code)

        assertArrayEquals(byteArrayOf(27, 'b'.code.toByte()), sink.chunks.single())
    }

    @Test
    fun `writeCodePoint rejects a surrogate or an out-of-range code point`() {
        val sink = RecordingSink()
        val session = session()
        session.setInputSink(sink)

        for (invalid in listOf(0xD800, 0xDC00, 0xDFFF, 1_114_112, Int.MAX_VALUE)) {
            try {
                session.writeCodePoint(false, invalid)
                fail("expected IllegalArgumentException for code point $invalid")
            } catch (expected: IllegalArgumentException) {
                assertTrue(
                    "the message should name the offending code point, got: ${expected.message}",
                    expected.message.orEmpty().contains(invalid.toString()),
                )
            }
        }
        assertEquals("a rejected code point must send nothing", "", sink.text())
    }

    // --- append ---------------------------------------------------------------

    @Test
    fun `append parses into the grid and reports the change on the main thread`() {
        val client = RecordingClient()
        val session = session(client = client)
        client.reset()

        val bytes = "hello world\r\n".toByteArray()
        session.append(bytes, 0, bytes.size)

        assertTrue(
            "the bytes must reach the emulator's screen, got: " +
                session.emulator.screen.transcriptText,
            session.emulator.screen.transcriptText.contains("hello world"),
        )
        assertEquals("append must report exactly one text change", 1, client.textChanges)
        assertSame("the callback must carry the session it came from", session, client.lastChanged)
    }

    @Test
    fun `append honours the offset and count it is given`() {
        val session = session()
        val frame = "XXXvisibleYYY".toByteArray()

        session.append(frame, 3, "visible".length)

        val transcript = session.emulator.screen.transcriptText
        assertTrue("got: $transcript", transcript.contains("visible"))
        assertTrue(
            "the slice bounds must be respected, got: $transcript",
            !transcript.contains("XXX"),
        )
        assertTrue(
            "the slice bounds must be respected, got: $transcript",
            !transcript.contains("YYY"),
        )
    }

    @Test
    fun `an empty append is a no-op, not a spurious text change`() {
        val client = RecordingClient()
        val session = session(client = client)
        client.reset()

        session.append(ByteArray(0), 0, 0)

        assertEquals(0, client.textChanges)
    }

    /**
     * The assertion this method exists for: the emulator has no locking, so a
     * parse on a background thread while the renderer reads the same buffers is
     * a corrupted grid that surfaces days later with no stack trace. Failing
     * loudly at the call site is the whole point.
     */
    @Test
    fun `append off the main thread throws instead of racing the renderer`() {
        val client = RecordingClient()
        val session = session(client = client)
        client.reset()
        val bytes = "off-thread".toByteArray()
        val thrown = AtomicReference<Throwable?>()

        val worker = Thread {
            try {
                session.append(bytes, 0, bytes.size)
            } catch (failure: Throwable) {
                thrown.set(failure)
            }
        }
        worker.start()
        worker.join(5_000)

        val failure = thrown.get()
        assertNotNull("append off the main thread must throw", failure)
        assertTrue(
            "expected IllegalStateException, got ${failure!!::class.java.name}",
            failure is IllegalStateException,
        )
        assertTrue(
            "the message should say why, got: ${failure.message}",
            failure.message.orEmpty().contains("main thread"),
        )
        assertEquals(
            "nothing may have reached the grid",
            "",
            session.emulator.screen.transcriptText.trim(),
        )
        assertEquals("and nothing may have been reported", 0, client.textChanges)
    }

    // --- geometry -------------------------------------------------------------

    @Test
    fun `updateSize resizes the emulator`() {
        val session = session(columns = 80, rows = 24)
        assertEquals(80, session.emulator.mColumns)
        assertEquals(24, session.emulator.mRows)

        session.updateSize(100, 40, 10, 20)

        assertEquals(100, session.emulator.mColumns)
        assertEquals(40, session.emulator.mRows)
    }

    @Test
    fun `a resize reflows what is already on the grid instead of clearing it`() {
        val session = session(columns = 80, rows = 24)
        val bytes = "keep-me-across-the-resize".toByteArray()
        session.append(bytes, 0, bytes.size)

        session.updateSize(60, 30, 8, 16)

        assertTrue(
            "the last frame must survive a resize, got: " +
                session.emulator.screen.transcriptText,
            session.emulator.screen.transcriptText.contains("keep-me-across-the-resize"),
        )
    }

    // --- the client -----------------------------------------------------------

    @Test
    fun `updateTerminalSessionClient swaps the client for the session AND the emulator`() {
        val first = RecordingClient()
        val second = RecordingClient()
        val session = session(client = first)

        session.updateTerminalSessionClient(second)
        first.reset()
        second.reset()

        // `ESC c` (RIS) is what the `reset` command sends. The emulator's own
        // reset calls BOTH halves: `onColorsChanged` goes out through the
        // session (the TerminalOutput contract) and `getTerminalCursorStyle` is
        // asked of the emulator's own copy of the client reference.
        session.feed("\u001Bc")

        assertTrue(
            "the SESSION half must report to the new client",
            second.colorChanges > 0,
        )
        assertTrue(
            "the EMULATOR half must report to the new client too",
            second.cursorStyleQueries > 0,
        )
        assertEquals("nothing may still reach the replaced client", 0, first.colorChanges)
        assertEquals(0, first.cursorStyleQueries)
    }

    @Test
    fun `the title, the bell and the clipboard reach the client`() {
        val client = RecordingClient()
        val session = session(client = client)
        client.reset()

        session.feed("\u001B]0;pocketshell\u0007") // OSC 0: set the window title
        session.feed("\u0007") // BEL
        session.onCopyTextToClipboard("copied")
        session.onPasteTextFromClipboard()

        assertEquals("pocketshell", session.title)
        assertTrue("a title change must be reported", client.titleChanges > 0)
        assertEquals("only the bare BEL rings; the OSC terminator does not", 1, client.bells)
        assertEquals(listOf("copied"), client.clipboardPuts)
        assertEquals(1, client.pasteRequests)
    }

    @Test
    fun `a fresh session has no title until one is set`() {
        assertNull(session().title)
    }

    // --- query replies --------------------------------------------------------

    /**
     * The emulator is the real terminal the remote program talks to, so its
     * answers have to leave through the same sink a keystroke does — a session
     * that only forwarded typed bytes would leave `CSI 6 n` (what `stty size`
     * and every full-screen TUI ask) unanswered and the remote waiting.
     */
    @Test
    fun `the emulator's query replies leave through the input sink`() {
        val sink = RecordingSink()
        val session = session(columns = 5, rows = 5)
        session.setInputSink(sink)

        session.feed("\u001B[6n")
        assertEquals("\u001B[1;1R", sink.takeText())

        session.feed("\u001B[5n")
        assertEquals("\u001B[0n", sink.takeText())

        session.feed("\u001B[c")
        assertEquals("\u001B[?64;1;2;6;9;15;18;21;22c", sink.takeText())

        session.feed("\u001B[18t")
        assertEquals("\u001B[8;5;5t", sink.takeText())
    }

    /**
     * A query answered while nothing is attached is held like a keystroke: the
     * remote asked before the reattach landed and is still waiting for the
     * answer, so dropping it would hang whatever asked.
     */
    @Test
    fun `a query asked while no sink is installed is answered to the next one`() {
        val session = session(columns = 5, rows = 5)

        session.feed("\u001B[6n")

        val sink = RecordingSink()
        session.setInputSink(sink)
        assertEquals("\u001B[1;1R", sink.takeText())

        session.feed("\u001B[6n")
        assertEquals("\u001B[1;1R", sink.takeText())
    }

    // --- helpers --------------------------------------------------------------

    private fun session(
        columns: Int = 80,
        rows: Int = 24,
        client: TerminalSessionClient = RecordingClient(),
    ): TerminalSession = TerminalSession(columns, rows, 8, 16, 200, client)

    /** Parses [sequence] the way the bridge does — through the public entry point. */
    private fun TerminalSession.feed(sequence: String) {
        val bytes = sequence.toByteArray()
        append(bytes, 0, bytes.size)
    }

    private class RecordingSink : TerminalSession.InputSink {
        val chunks = mutableListOf<ByteArray>()

        override fun onInput(data: ByteArray, offset: Int, count: Int) {
            chunks += data.copyOfRange(offset, offset + count)
        }

        fun text(): String = String(joined(), Charsets.ISO_8859_1)

        fun textUtf8(): String = String(joined(), Charsets.UTF_8)

        /** [text] and clear, so a sequence of queries can be asserted one at a time. */
        fun takeText(): String = text().also { chunks.clear() }

        private fun joined(): ByteArray =
            chunks.fold(ByteArray(0)) { acc, bytes -> acc + bytes }
    }

    private class RecordingClient : TerminalSessionClient {
        var textChanges: Int = 0
        var lastChanged: TerminalSession? = null
        var titleChanges: Int = 0
        var colorChanges: Int = 0
        var bells: Int = 0
        var pasteRequests: Int = 0
        var cursorStyleQueries: Int = 0
        val clipboardPuts = mutableListOf<String?>()

        /** Zeroes the counters, so a case can assert what IT caused. */
        fun reset() {
            textChanges = 0
            lastChanged = null
            titleChanges = 0
            colorChanges = 0
            bells = 0
            pasteRequests = 0
            cursorStyleQueries = 0
            clipboardPuts.clear()
        }

        override fun onTextChanged(changedSession: TerminalSession) {
            textChanges++
            lastChanged = changedSession
        }

        override fun onTitleChanged(changedSession: TerminalSession) {
            titleChanges++
        }

        override fun onSessionFinished(finishedSession: TerminalSession) = Unit

        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
            clipboardPuts += text
        }

        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            pasteRequests++
        }

        override fun onBell(session: TerminalSession) {
            bells++
        }

        override fun onColorsChanged(session: TerminalSession) {
            colorChanges++
        }

        override fun onTerminalCursorStateChange(state: Boolean) = Unit

        override fun setTerminalShellPid(session: TerminalSession, pid: Int) = Unit

        override fun getTerminalCursorStyle(): Int? {
            cursorStyleQueries++
            return null
        }

        override fun logError(tag: String?, message: String?) = Unit
        override fun logWarn(tag: String?, message: String?) = Unit
        override fun logInfo(tag: String?, message: String?) = Unit
        override fun logDebug(tag: String?, message: String?) = Unit
        override fun logVerbose(tag: String?, message: String?) = Unit
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
        override fun logStackTrace(tag: String?, e: Exception?) = Unit
    }
}
