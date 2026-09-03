package com.pocketshell.next.terminal

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.uikit.theme.PocketShellTheme
import com.termux.view.TerminalView
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The key bar wired into the session screen (rewrite task U-5), on the host JVM.
 *
 * ## What this proves that the byte tables cannot
 *
 * `KeyBytesTest` pins what each key MEANS. This pins that the real ui-kit
 * `KeyBar` — its own sticky one-shot/lock state machine, unmodified — is wired
 * to those bytes and to the send path, including the interaction the whole
 * design turns on: **Ctrl is armed on the bar and the letter arrives from the
 * keyboard**, because a phone keyboard has every letter and no Ctrl.
 *
 * That letter is driven through the REAL vendored input path
 * (`TerminalView.inputCodePoint`, the method the IME and a hardware keyboard
 * both funnel into), not by calling the screen's callback directly — a test
 * that skipped it would still pass with `readControlKey()` left returning
 * `false`, which is precisely the bug that would make Ctrl+C do nothing on a
 * phone.
 *
 * J03 proves the same round trip end to end against a real host: `sleep 100`,
 * Ctrl, `c`, prompt back.
 */
@RunWith(AndroidJUnit4::class)
class SessionKeyBarTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var rootView: View? = null

    // --- presence ------------------------------------------------------------

    /**
     * The bar is up before the terminal is.
     *
     * Not cosmetic: the terminal slot must be the same height while attaching
     * and once attached, or the pre-attach size estimate measures a taller slot
     * than the terminal eventually gets and the remote is resized twice on
     * every single attach.
     */
    @Test
    fun `the key bar is present while still attaching`() {
        setContent(SessionUiState.Connecting)

        composeRule.onNodeWithTag(SESSION_KEY_BAR_TAG).assertIsDisplayed()
    }

    @Test
    fun `the key bar is present once the session is live`() {
        setContent(SessionUiState.Live(createRemoteTerminalSession()))

        composeRule.onNodeWithTag(SESSION_KEY_BAR_TAG).assertIsDisplayed()
    }

    /** The four slots the maintainer asked for, and nothing else. */
    @Test
    fun `the bar carries exactly ctrl esc tab and enter`() {
        setContent(SessionUiState.Live(createRemoteTerminalSession()))

        listOf(KEY_LABEL_CTRL, KEY_LABEL_ESC, KEY_LABEL_TAB, KEY_LABEL_ENTER).forEach { label ->
            composeRule.onNodeWithText(label).assertIsDisplayed()
        }
        assertEquals(4, TERMINAL_KEY_BAR_KEYS.size)
        // D18's arrows/Alt are explicitly out of scope (maintainer, 2026-09-03).
        listOf("Alt", "←", "→", "↑", "↓").forEach { absent ->
            composeRule.onNodeWithText(absent).assertDoesNotExist()
        }
    }

    /** Nothing to send to: a failed session hides the bar rather than lying. */
    @Test
    fun `a failed session has no key bar`() {
        setContent(SessionUiState.Failed("Session \"$SESSION\" ended (exit 3)."))

        composeRule.onNodeWithTag(SESSION_KEY_BAR_TAG).assertDoesNotExist()
    }

    // --- taps ----------------------------------------------------------------

    @Test
    fun `tapping escape sends the escape byte`() {
        val sent = mutableListOf<ByteArray>()
        setContent(SessionUiState.Live(createRemoteTerminalSession()), onSend = { sent += it })

        composeRule.onNodeWithText(KEY_LABEL_ESC).performClick()

        assertEquals(1, sent.size)
        assertArrayEquals(byteArrayOf(0x1B), sent.single())
    }

    @Test
    fun `tapping tab sends the tab byte`() {
        val sent = mutableListOf<ByteArray>()
        setContent(SessionUiState.Live(createRemoteTerminalSession()), onSend = { sent += it })

        composeRule.onNodeWithText(KEY_LABEL_TAB).performClick()

        assertArrayEquals(byteArrayOf(0x09), sent.single())
    }

    @Test
    fun `tapping enter sends carriage return`() {
        val sent = mutableListOf<ByteArray>()
        setContent(SessionUiState.Live(createRemoteTerminalSession()), onSend = { sent += it })

        composeRule.onNodeWithText(KEY_LABEL_ENTER).performClick()

        assertArrayEquals(byteArrayOf(0x0D), sent.single())
    }

    /**
     * A modifier is not a key. Tapping Ctrl must put NOTHING on the wire — if
     * it did, every Ctrl+C would send a stray byte to the shell first.
     */
    @Test
    fun `tapping ctrl sends nothing`() {
        val sent = mutableListOf<ByteArray>()
        setContent(SessionUiState.Live(createRemoteTerminalSession()), onSend = { sent += it })

        composeRule.onNodeWithText(KEY_LABEL_CTRL).performClick()

        assertTrue("a modifier tap must not reach the remote, got $sent", sent.isEmpty())
    }

    // --- the Ctrl round trip -------------------------------------------------

    /**
     * The headline interaction: arm Ctrl on the bar, type `c` on the keyboard,
     * get 0x03 — and Ctrl disarms itself afterwards.
     *
     * Every step runs through production code: the real `KeyBar` decides that
     * the tap arms a one-shot, the real `TerminalView.inputCodePoint` asks the
     * screen's client whether Ctrl is down, and the real client encodes the
     * combination. The disarm is asserted by sending a SECOND `c` and getting
     * a plain letter back — a one-shot that silently latched would make the
     * next thing the user typed disappear into control codes.
     */
    @Test
    fun `arming ctrl on the bar makes the next typed letter a control byte`() {
        val sent = mutableListOf<ByteArray>()
        setContent(SessionUiState.Live(createRemoteTerminalSession()), onSend = { sent += it })

        composeRule.onNodeWithText(KEY_LABEL_CTRL).performClick()
        composeRule.waitForIdle()

        typeCodePoint('c')

        assertEquals("exactly one send for one keystroke", 1, sent.size)
        assertArrayEquals(byteArrayOf(0x03), sent.single())

        // The one-shot cleared: the vendored path handles the plain letter
        // itself, so the screen's send callback is not called again.
        typeCodePoint('c')
        assertEquals("Ctrl must not have latched, got $sent", 1, sent.size)
    }

    /**
     * Double-tapping Ctrl locks it, and a locked modifier survives the key it
     * modified — that is what the lock is for (`^C^C` to a stubborn agent
     * without re-arming between them).
     */
    @Test
    fun `double-tapping ctrl locks it across several keystrokes`() {
        val sent = mutableListOf<ByteArray>()
        setContent(SessionUiState.Live(createRemoteTerminalSession()), onSend = { sent += it })

        // Two taps inside the bar's 350 ms double-tap window promote the
        // one-shot to a lock.
        composeRule.onNodeWithText(KEY_LABEL_CTRL).performClick()
        composeRule.onNodeWithText(KEY_LABEL_CTRL).performClick()
        composeRule.waitForIdle()

        typeCodePoint('c')
        typeCodePoint('d')

        assertEquals(2, sent.size)
        assertArrayEquals(byteArrayOf(0x03), sent[0])
        assertArrayEquals(byteArrayOf(0x04), sent[1])
    }

    /**
     * With no modifier armed the screen stays out of the way: a plain letter
     * goes through the vendored path into the session's own queue, exactly as
     * it did before U-5, and never through the byte-bar send path.
     */
    @Test
    fun `an unmodified keystroke does not go through the key bar send path`() {
        val sent = mutableListOf<ByteArray>()
        setContent(SessionUiState.Live(createRemoteTerminalSession()), onSend = { sent += it })

        typeCodePoint('c')

        assertTrue("unmodified typing must not be re-routed, got $sent", sent.isEmpty())
    }

    // --- pre-attach geometry -------------------------------------------------

    /**
     * The terminal slot's measured size is reported BEFORE the terminal
     * exists, which is what lets the PTY open at the phone's real geometry
     * instead of 80x24 and then reflow.
     *
     * The exact pixel arithmetic is `TerminalGeometryTest`'s business; what
     * this pins is that the screen measures its terminal slot, runs it through
     * that arithmetic with the metrics it was handed, and reports the result —
     * proven by handing it TWO different faces and getting two different
     * answers. A hard-coded 80x24 or a dropped report passes neither half.
     */
    @Test
    fun `the terminal slot reports its size while still attaching`() {
        val sizes = mutableListOf<Pair<Int, Int>>()
        setContent(
            SessionUiState.Connecting,
            onResized = { c, r -> sizes += c to r },
            cellMetrics = NARROW_CELLS,
        )

        assertTrue("no size was reported while attaching", sizes.isNotEmpty())
        val (cols, rows) = sizes.last()
        assertTrue("columns must be usable, got $cols", cols >= MIN_TERMINAL_CELLS)
        assertTrue("rows must be usable, got $rows", rows >= MIN_TERMINAL_CELLS)
    }

    /**
     * The reported columns are the REAL viewport divided by the REAL glyph
     * width, not a constant.
     *
     * The terminal slot is `fillMaxWidth`, so its width is the composition
     * root's — which makes the expected column count computable here from
     * numbers the test did not invent: Robolectric's display width and the
     * metrics handed in above. An 80x24 default, a dropped report or an
     * estimate that ignored the metrics all fail this.
     */
    @Test
    fun `the reported columns are the measured viewport divided by the glyph width`() {
        val sizes = mutableListOf<Pair<Int, Int>>()
        setContent(
            SessionUiState.Connecting,
            onResized = { c, r -> sizes += c to r },
            cellMetrics = NARROW_CELLS,
        )

        val viewportWidthPx = requireNotNull(rootView).width
        assertTrue("Robolectric gave the composition no width", viewportWidthPx > 0)
        val expected = maxOf(
            MIN_TERMINAL_CELLS,
            (viewportWidthPx / NARROW_CELLS.cellWidthPx).toInt(),
        )

        assertEquals(expected, sizes.last().first)
    }

    /**
     * Once the session is live the vendored view owns the size — it holds the
     * renderer, so it holds the real font metrics — and the screen's estimate
     * must go quiet. Two reporters would fight on every layout pass and push a
     * resize to the remote each time.
     */
    @Test
    fun `the screen stops estimating the size once the terminal exists`() {
        val sizes = mutableListOf<Pair<Int, Int>>()
        setContent(
            SessionUiState.Live(createRemoteTerminalSession()),
            onResized = { c, r -> sizes += c to r },
        )

        composeRule.waitForIdle()

        // Robolectric never lays the vendored view out to a non-zero size, so
        // the view itself reports nothing here either: any report at all would
        // have come from the estimate that must be off.
        assertTrue("the estimate must not run while live, got $sizes", sizes.isEmpty())
    }

    // --- helpers -------------------------------------------------------------

    /**
     * Types one character the way the IME does: straight into
     * `TerminalView.inputCodePoint`, which is where both the soft keyboard's
     * committed text and a hardware keyboard's key events end up.
     *
     * `eventSource = 0` is upstream's `KEY_EVENT_SOURCE_SOFT_KEYBOARD`.
     */
    private fun typeCodePoint(character: Char) {
        val view = requireNotNull(terminalView()) { "no TerminalView in the composition" }
        composeRule.runOnUiThread {
            view.inputCodePoint(0, character.code, false, false)
        }
        composeRule.waitForIdle()
    }

    private fun terminalView(): TerminalView? {
        val root = requireNotNull(rootView) { "the composition never reported its View" }
        return findTerminalView(root.rootView)
    }

    private fun findTerminalView(view: View): TerminalView? {
        if (view is TerminalView) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            findTerminalView(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun setContent(
        state: SessionUiState,
        onResized: (Int, Int) -> Unit = { _, _ -> },
        onSend: (ByteArray) -> Unit = {},
        cellMetrics: TerminalCellMetrics = NARROW_CELLS,
    ) {
        composeRule.setContent {
            rootView = LocalView.current
            PocketShellTheme {
                SessionScreen(
                    state = state,
                    sessionName = SESSION,
                    onBack = {},
                    onResized = onResized,
                    onRetry = {},
                    onSend = onSend,
                    cellMetrics = cellMetrics,
                )
            }
        }
        composeRule.waitForIdle()
    }

    private companion object {
        const val SESSION = "git-pocketshell"

        /**
         * JetBrainsMono Regular at the shipped 28 raw px, as a device measures
         * it. Supplied explicitly because Robolectric's `Paint` reports a 1 px
         * glyph with zero ascent and descent, which would make every size
         * estimate unmeasurable and the assertions above vacuous.
         */
        val NARROW_CELLS = TerminalCellMetrics(
            cellWidthPx = 16.8f,
            lineHeightPx = 31,
            lineSpacingAndAscentPx = 4,
        )
    }
}
