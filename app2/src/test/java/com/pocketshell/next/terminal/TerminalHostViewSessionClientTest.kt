package com.pocketshell.next.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.uikit.theme.PocketShellColors
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TextStyle
import com.termux.view.TerminalView
import com.termux.view.textselection.TextSelectionCursorController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The emulator callbacks the pre-0.5.0 client answered and the rewrite's
 * host view left as no-ops: the palette after a terminal reset, and the
 * selection menu's Copy and Paste.
 *
 * ## Palette
 *
 * The rewrite patched PocketShell's colours onto the live emulator in
 * `onEmulatorSet`. `ESC c` (what the `reset` command sends) and `OSC 104` /
 * `OSC 110`–`112` (an app undoing its own colours) copy the vendored DEFAULT
 * scheme back over the live palette, so after any of them the grid was
 * Termux's pure black on white against the app's near-black chrome, and
 * nothing ever put it back. The pre-rewrite client re-patched on every
 * `onColorsChanged`, which also clobbered colours an app had asked for.
 * [installTerminalPalette] fixes both by making the tokens the default scheme.
 *
 * ## Clipboard
 *
 * Long-press → drag → Copy in the vendored selection action mode calls
 * `session.onCopyTextToClipboard`, and Paste calls
 * `session.onPasteTextFromClipboard`; both reach the session's client. With a
 * no-op client the menu items did nothing, silently. `OSC 52` (terminal
 * `set-clipboard`, an agent copying a snippet) lands on the same copy path.
 *
 * These tests drive the vendored entry points the menu itself calls, on the
 * REAL emulator and the REAL hosted view under Robolectric, and read the
 * result from the system clipboard, the view's background drawable and an
 * installed [TerminalSession.InputSink] — the bytes the bridge would put on
 * the channel, i.e. exactly what the remote would receive.
 */
@RunWith(AndroidJUnit4::class)
class TerminalHostViewSessionClientTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var rootView: View? = null

    /** Per-session record of everything an installed sink was handed. */
    private val sentToRemote = mutableMapOf<TerminalSession, StringBuilder>()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // --- palette -----------------------------------------------------------

    @Test
    fun `a fresh emulator starts on the PocketShell palette`() {
        val emulator = createRemoteTerminalSession().emulator

        assertPocketShellPalette(emulator)
    }

    @Test
    fun `a full terminal reset returns to the PocketShell palette, not Termux black`() {
        val emulator = createRemoteTerminalSession().emulator
        emulator.feed(OSC_SET_BACKGROUND_RED)
        assertEquals("an app may set its own background", RED, emulator.background())

        emulator.feed(RIS)

        assertPocketShellPalette(emulator)
        assertNotEquals(
            "the whole point: a reset must not land on Termux's default black",
            TERMUX_DEFAULT_BLACK,
            emulator.background(),
        )
    }

    @Test
    fun `an app resetting the colours it set lands on the PocketShell palette`() {
        val emulator = createRemoteTerminalSession().emulator
        emulator.feed(OSC_SET_BACKGROUND_RED)
        emulator.feed(OSC_SET_FOREGROUND_RED)

        emulator.feed(OSC_RESET_BACKGROUND)
        assertEquals(BACKGROUND, emulator.background())
        assertEquals("only the background was reset", RED, emulator.foreground())

        emulator.feed(OSC_RESET_ALL_COLOURS)
        assertPocketShellPalette(emulator)
    }

    @Test
    fun `the hosted view's background follows the emulator's palette`() {
        val session = host()
        assertEquals(BACKGROUND, requireTerminalView().backgroundColour())

        feedOnMain(session, OSC_SET_BACKGROUND_RED)
        assertEquals(
            "the renderer leaves default-background cells unpainted, so a " +
                "background an app sets is only visible if the View follows it",
            RED,
            requireTerminalView().backgroundColour(),
        )

        feedOnMain(session, RIS)
        assertEquals(BACKGROUND, requireTerminalView().backgroundColour())
    }

    // --- clipboard ---------------------------------------------------------

    @Test
    fun `copy from the selection menu lands the text on the system clipboard`() {
        val session = host()

        composeRule.runOnUiThread { session.onCopyTextToClipboard("echo copied") }

        assertEquals("echo copied", clipboardText())
    }

    @Test
    fun `copy action uses text retained when native selection opens more actions`() {
        val session = host()
        val view = requireTerminalView()

        composeRule.runOnUiThread {
            // ACTION_MORE in the vendored native selection controller stores
            // the selected text before handing control to PocketShell's
            // action sheet. Seed that real controller state, then invoke the
            // same internal action the sheet calls.
            val controller = TextSelectionCursorController(view)
            TerminalView::class.java.getDeclaredField("mTextSelectionCursorController")
                .apply { isAccessible = true }
                .set(view, controller)
            controller.javaClass.getDeclaredField("mStoredSelectedText")
                .apply { isAccessible = true }
                .set(controller, "echo copied")
        }
        composeRule.waitForIdle()

        assertTrue(view.copySelectionToClipboard())
        assertEquals("echo copied", clipboardText())
    }

    @Test
    fun `OSC 52 from the remote lands on the system clipboard`() {
        val session = host()
        val payload = Base64.encodeToString("from the remote".toByteArray(), Base64.NO_WRAP)

        feedOnMain(session, "]52;c;$payload")

        assertEquals("from the remote", clipboardText())
    }

    @Test
    fun `paste from the selection menu writes the clipboard text to the remote`() {
        val session = host()
        setClipboard("ls -la\n")

        composeRule.runOnUiThread { session.onPasteTextFromClipboard() }

        assertEquals("ls -la\r", session.bytesForRemote())
    }

    @Test
    fun `paste is bracketed when the remote asked for it`() {
        val session = host()
        feedOnMain(session, ENABLE_BRACKETED_PASTE)
        setClipboard("ls -la")

        composeRule.runOnUiThread { session.onPasteTextFromClipboard() }

        assertEquals("[200~ls -la[201~", session.bytesForRemote())
    }

    @Test
    fun `an empty clipboard pastes nothing`() {
        val session = host()

        composeRule.runOnUiThread { session.onPasteTextFromClipboard() }

        assertEquals("", session.bytesForRemote())
    }

    @Test
    fun `without a hosting view the clipboard callbacks are inert, not crashes`() {
        val session = createRemoteTerminalSession().recordingInput()
        setClipboard("never sent")

        session.onCopyTextToClipboard("never copied")
        session.onPasteTextFromClipboard()

        assertEquals("never sent", clipboardText())
        assertEquals("", session.bytesForRemote())
    }

    // --- helpers -------------------------------------------------------------

    private fun host(): TerminalSession {
        val session = createRemoteTerminalSession().recordingInput()
        composeRule.setContent {
            rootView = LocalView.current
            TerminalHostView(
                session = session,
                onResized = { _, _ -> },
                modifier = Modifier.fillMaxSize(),
            )
        }
        composeRule.waitForIdle()
        return session
    }

    /** Parses [sequence] on the main thread, the way the vendored drain does. */
    private fun feedOnMain(session: TerminalSession, sequence: String) {
        composeRule.runOnUiThread { session.emulator.feed(sequence) }
        composeRule.waitForIdle()
    }

    private fun TerminalEmulator.feed(sequence: String) {
        val bytes = sequence.toByteArray()
        append(bytes, bytes.size)
    }

    private fun TerminalEmulator.background(): Int =
        mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND]

    private fun TerminalEmulator.foreground(): Int =
        mColors.mCurrentColors[TextStyle.COLOR_INDEX_FOREGROUND]

    private fun assertPocketShellPalette(emulator: TerminalEmulator) {
        assertEquals(BACKGROUND, emulator.background())
        assertEquals(FOREGROUND, emulator.foreground())
        assertEquals(CURSOR, emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR])
    }

    /**
     * Installs the sink the bridge would install, recording what it is handed.
     *
     * This is the real production seam, not a test hook: [TerminalPtyBridge]
     * calls exactly this method in `start()` and forwards the bytes to the PTY
     * channel. Installed on EVERY session these tests build — including the
     * unhosted one — so "nothing was sent" is a genuine empty recording rather
     * than the absence of a recorder.
     */
    private fun TerminalSession.recordingInput(): TerminalSession = apply {
        val recorded = StringBuilder()
        sentToRemote[this] = recorded
        setInputSink { data, offset, count ->
            recorded.append(String(data, offset, count))
        }
    }

    /** Everything handed to the sink, i.e. what the bridge's input pump would send. */
    private fun TerminalSession.bytesForRemote(): String =
        sentToRemote[this]?.toString().orEmpty()

    private fun clipboard(): ClipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private fun setClipboard(text: String) {
        clipboard().setPrimaryClip(ClipData.newPlainText("test", text))
    }

    private fun clipboardText(): String? =
        clipboard().primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()

    private fun TerminalView.backgroundColour(): Int = (background as ColorDrawable).color

    private fun requireTerminalView(): TerminalView =
        requireNotNull(findTerminalView(requireNotNull(rootView).rootView)) {
            "no TerminalView in the composition — TerminalHostView did not host the vendored view"
        }

    private fun findTerminalView(view: View): TerminalView? {
        if (view is TerminalView) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            findTerminalView(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private companion object {
        val BACKGROUND: Int = PocketShellColors.Background.toArgb()
        val FOREGROUND: Int = PocketShellColors.Text.toArgb()
        val CURSOR: Int = PocketShellColors.Accent.toArgb()
        const val RED: Int = 0xFFFF0000.toInt()

        /** `TerminalColorScheme.DEFAULT_COLORSCHEME`'s background: what a reset used to land on. */
        const val TERMUX_DEFAULT_BLACK: Int = 0xFF000000.toInt()

        /** `ESC c` — RIS, what the `reset` command sends. */
        const val RIS = "c"
        const val OSC_SET_BACKGROUND_RED = "]11;#ff0000\\"
        const val OSC_SET_FOREGROUND_RED = "]10;#ff0000\\"
        const val OSC_RESET_BACKGROUND = "]111\\"
        const val OSC_RESET_ALL_COLOURS = "]104\\"
        const val ENABLE_BRACKETED_PASTE = "[?2004h"
    }
}
