package com.termux.view

import android.app.Application
import android.graphics.Typeface
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What a finger drag on the terminal is allowed to send to the remote (#2555).
 *
 * ## The bug this pins
 *
 * The maintainer, from the phone: *"when I try to scroll up or down with my
 * finger it gives me up/down. I want to scroll the screen not see previous
 * commands in agents."* Upstream Termux's [TerminalView.doScroll] answered a
 * drag on the ALTERNATE screen — when mouse tracking is off — by synthesising
 * `KEYCODE_DPAD_UP`/`KEYCODE_DPAD_DOWN` and writing the resulting `ESC [ A` /
 * `ESC [ B` to the session. That is aimed at `less`; in an agent TUI or a
 * shell, arrow-up is prompt/command history, so trying to scroll back rewrote
 * what the user was typing.
 *
 * ## Why the fixtures are real captures
 *
 * The state that triggers it — "alternate buffer active AND mouse tracking
 * inactive" — is not something PocketShell chooses; it is what a specific host
 * hands the app on attach. So the emulator here is driven by verbatim PTY
 * captures of the attach paths the app really opens (see
 * `src/test/resources/pocketshell/scroll/README.md`), not by a hand-written
 * escape string that could encode the wrong assumption. The two non-happy ones
 * are the fixtures #2555 was missing:
 *
 *  - a **stock-config tmux** attach — `mouse` defaults to `off` in tmux, so any
 *    host without `set -g mouse on` puts the app's emulator on the alt screen
 *    with no mouse tracking;
 *  - an **aplexer** attach onto an alt-screen workload — aplexer forwards the
 *    workload's `CSI ? 1049 h` and never enables mouse tracking of its own.
 *
 * ## Scope of the assertions
 *
 * [TerminalView.doScroll] is called directly rather than through a synthetic
 * touch sequence: `onScroll` converts pixels to rows with
 * `mRenderer.mFontLineSpacing`, which Robolectric's shadow `Paint` reports as
 * zero, so a gesture-level drive here would divide by zero and prove nothing
 * about the branch under test. `doScroll(event, rows)` is the exact call
 * `GestureAndScaleRecognizer.Listener.onScroll` makes, with the exact
 * arguments. The pixels-to-rows half, and the whole thing against a real host,
 * is covered by `J15TerminalScrollJourney` on a device.
 */
@RunWith(RobolectricTestRunner::class)
class TerminalScrollGestureTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    private val events = mutableListOf<MotionEvent>()

    @After
    fun recycleEvents() {
        events.forEach { it.recycle() }
        events.clear()
    }

    // --- the reported defect -------------------------------------------------

    @Test
    fun `stock-config tmux attach - a drag sends no arrow keys`() {
        val view = attachedView("tmux-default-config-attach.bin")

        assertTrue(
            "fixture must reproduce the reported host state: alternate buffer active",
            view.mEmulator.isAlternateBufferActive,
        )
        assertFalse(
            "fixture must reproduce the reported host state: mouse tracking inactive",
            view.mEmulator.isMouseTrackingActive,
        )

        view.doScroll(dragEvent(), -3)
        view.doScroll(dragEvent(), 3)

        assertEquals("", view.drainSessionOutput())
    }

    @Test
    fun `aplexer attach to an alt-screen workload - a drag sends no arrow keys`() {
        val view = attachedView("aplexer-altscreen-attach.bin")

        assertTrue(view.mEmulator.isAlternateBufferActive)
        assertFalse(view.mEmulator.isMouseTrackingActive)

        view.doScroll(dragEvent(), -3)
        view.doScroll(dragEvent(), 3)

        assertEquals("", view.drainSessionOutput())
    }

    /**
     * The class, not the instance: whatever put the emulator on the alternate
     * screen, an alt screen with no mouse tracking must never turn a drag into
     * a keystroke. `ESC [ A` and `ESC O A` are both spellings of "up" — the
     * second is what the same code path emits once an application has turned on
     * DECCKM (`CSI ? 1 h`), which tmux does on every attach.
     */
    @Test
    fun `no alt-screen drag emits a cursor key in either cursor-key mode`() {
        listOf(
            "tmux-default-config-attach.bin",
            "aplexer-altscreen-attach.bin",
        ).forEach { fixture ->
            listOf(false, true).forEach { applicationCursorKeys ->
                val view = attachedView(fixture)
                view.mEmulator.append(
                    if (applicationCursorKeys) CSI_APP_CURSOR_KEYS else CSI_NORMAL_CURSOR_KEYS,
                )
                view.drainSessionOutput()

                view.doScroll(dragEvent(), -5)
                view.doScroll(dragEvent(), 5)

                assertEquals(
                    "$fixture (applicationCursorKeys=$applicationCursorKeys) must send nothing",
                    "",
                    view.drainSessionOutput(),
                )
            }
        }
    }

    // --- the paths that must NOT change -------------------------------------

    @Test
    fun `tmux with mouse on still gets wheel events, not arrow keys`() {
        val view = attachedView("tmux-mouse-on-attach.bin")

        assertTrue(view.mEmulator.isAlternateBufferActive)
        assertTrue(
            "fixture must reproduce a mouse-tracking host",
            view.mEmulator.isMouseTrackingActive,
        )

        view.doScroll(dragEvent(), -2)
        val up = view.drainSessionOutput()
        view.doScroll(dragEvent(), 2)
        val down = view.drainSessionOutput()

        // SGR mouse (CSI ? 1006 h, which tmux sets): button 64 = wheel up,
        // 65 = wheel down, at the 1-based cell under the finger.
        assertEquals(WHEEL_UP + WHEEL_UP, up)
        assertEquals(WHEEL_DOWN + WHEEL_DOWN, down)
    }

    @Test
    fun `normal buffer still scrolls the local transcript and sends nothing`() {
        val view = attachedView("aplexer-normal-attach.bin")

        assertFalse(view.mEmulator.isAlternateBufferActive)
        assertFalse(view.mEmulator.isMouseTrackingActive)

        // Give the transcript something to scroll back into.
        view.mEmulator.append((1..80).joinToString("") { "line $it\r\n" }.toByteArray())
        view.drainSessionOutput()

        view.doScroll(dragEvent(), -4)
        assertEquals("viewport must move up through the transcript", -4, view.mTopRow)
        assertEquals("", view.drainSessionOutput())

        view.doScroll(dragEvent(), 4)
        assertEquals("viewport must come back down", 0, view.mTopRow)
        assertEquals("", view.drainSessionOutput())
    }

    // --- harness -------------------------------------------------------------

    private fun attachedView(fixture: String): TerminalView {
        val session = remoteSession()
        val view = TerminalView(context, null).apply {
            setTextSize(TEXT_SIZE_PX)
            setTypeface(Typeface.MONOSPACE)
            setTerminalViewClient(SilentViewClient())
            mTermSession = session
            mEmulator = session.emulator
        }
        view.mEmulator.append(readFixture(fixture))
        // The attach prologue is host chatter, not something the drag caused.
        view.drainSessionOutput()
        return view
    }

    private fun dragEvent(): MotionEvent =
        MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_MOVE, 0f, 0f, 0)
            .also { events += it }

    private fun readFixture(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/pocketshell/scroll/$name")) {
            "missing capture fixture $name"
        }.use { it.readBytes() }

    private fun TerminalEmulator.append(bytes: ByteArray) = append(bytes, bytes.size)

    /**
     * Everything the view wrote to the session since the last call, as text.
     *
     * The vendored [TerminalSession.write] parks bytes in the package-private
     * `mTerminalToProcessIOQueue` — the same queue app2's `TerminalPtyBridge`
     * drains onto the SSH channel — so this reads exactly what would have
     * reached the remote.
     */
    private fun TerminalView.drainSessionOutput(): String {
        val queue = TerminalSession::class.java
            .getDeclaredField("mTerminalToProcessIOQueue")
            .apply { isAccessible = true }
            .get(mTermSession)
        val read = queue.javaClass
            .getDeclaredMethod("read", ByteArray::class.java, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val buffer = ByteArray(4096)
        val count = read.invoke(queue, buffer, false) as Int
        return if (count <= 0) "" else String(buffer, 0, count, Charsets.UTF_8)
    }

    /**
     * A session with a pre-installed emulator and a positive shell pid — the
     * shape app2's `createRemoteTerminalSession` builds, so the write path under
     * test is production's. Reflection rather than a vendored patch, for the
     * reason `VENDORED.md` gives.
     */
    private fun remoteSession(): TerminalSession {
        val client = SilentSessionClient()
        val session = TerminalSession(
            "/system/bin/sh",
            "/",
            emptyArray(),
            emptyArray(),
            TRANSCRIPT_ROWS,
            client,
        )
        val emulator = TerminalEmulator(session, COLUMNS, ROWS, 13, 15, TRANSCRIPT_ROWS, client)
        TerminalSession::class.java.getDeclaredField("mEmulator")
            .apply { isAccessible = true }
            .set(session, emulator)
        TerminalSession::class.java.getDeclaredField("mShellPid")
            .apply { isAccessible = true }
            .setInt(session, FAKE_SHELL_PID)
        return session
    }

    private class SilentSessionClient : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) = Unit
        override fun onTitleChanged(changedSession: TerminalSession) = Unit
        override fun onSessionFinished(finishedSession: TerminalSession) = Unit
        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) = Unit
        override fun onPasteTextFromClipboard(session: TerminalSession?) = Unit
        override fun onBell(session: TerminalSession) = Unit
        override fun onColorsChanged(session: TerminalSession) = Unit
        override fun onTerminalCursorStateChange(state: Boolean) = Unit
        override fun setTerminalShellPid(session: TerminalSession, pid: Int) = Unit
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String?, message: String?) = Unit
        override fun logWarn(tag: String?, message: String?) = Unit
        override fun logInfo(tag: String?, message: String?) = Unit
        override fun logDebug(tag: String?, message: String?) = Unit
        override fun logVerbose(tag: String?, message: String?) = Unit
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
        override fun logStackTrace(tag: String?, e: Exception?) = Unit
    }

    private class SilentViewClient : TerminalViewClient {
        override fun onScale(scale: Float): Float = 1.0f
        override fun onSingleTapUp(e: MotionEvent?) = Unit
        override fun shouldBackButtonBeMappedToEscape(): Boolean = false
        override fun shouldEnforceCharBasedInput(): Boolean = true
        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
        override fun isTerminalViewSelected(): Boolean = true
        override fun copyModeChanged(copyMode: Boolean) = Unit
        override fun onKeyDown(keyCode: Int, e: android.view.KeyEvent?, session: TerminalSession?) =
            false

        override fun onKeyUp(keyCode: Int, e: android.view.KeyEvent?): Boolean = false
        override fun onLongPress(event: MotionEvent?): Boolean = false
        override fun readControlKey(): Boolean = false
        override fun readAltKey(): Boolean = false
        override fun readShiftKey(): Boolean = false
        override fun readFnKey(): Boolean = false
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?) =
            false

        override fun onEmulatorSet() = Unit
        override fun logError(tag: String?, message: String?) = Unit
        override fun logWarn(tag: String?, message: String?) = Unit
        override fun logInfo(tag: String?, message: String?) = Unit
        override fun logDebug(tag: String?, message: String?) = Unit
        override fun logVerbose(tag: String?, message: String?) = Unit
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
        override fun logStackTrace(tag: String?, e: Exception?) = Unit
    }

    private companion object {
        const val COLUMNS = 100
        const val ROWS = 30
        const val TEXT_SIZE_PX = 28
        const val TRANSCRIPT_ROWS = 2000
        const val FAKE_SHELL_PID = 12345

        private const val ESC = "\u001b"

        /** `CSI ? 1 h` — DECCKM on: cursor keys become `ESC O A` / `ESC O B`. */
        val CSI_APP_CURSOR_KEYS = "$ESC[?1h".toByteArray()

        /** `CSI ? 1 l` — DECCKM off: cursor keys are `ESC [ A` / `ESC [ B`. */
        val CSI_NORMAL_CURSOR_KEYS = "$ESC[?1l".toByteArray()

        /** SGR-encoded wheel-up press at the top-left cell. */
        const val WHEEL_UP = ESC + "[<64;1;1M"

        /** SGR-encoded wheel-down press at the top-left cell. */
        const val WHEEL_DOWN = ESC + "[<65;1;1M"
    }
}
