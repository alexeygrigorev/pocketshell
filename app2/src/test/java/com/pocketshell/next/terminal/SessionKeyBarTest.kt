package com.pocketshell.next.terminal

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.next.composer.ComposerUiState
import com.pocketshell.uikit.components.SESSION_COMPOSER_LAUNCHER_TAG
import com.pocketshell.uikit.components.SESSION_HOTKEYS_LAUNCHER_TAG
import com.pocketshell.uikit.components.SESSION_LAUNCHER_BAR_TAG
import com.pocketshell.uikit.components.TERMINAL_HOTKEYS_PANEL_TAG
import com.pocketshell.uikit.components.TerminalHotkeysPanel
import com.pocketshell.uikit.components.TerminalHotkeysPage
import com.pocketshell.uikit.model.KeyBinding
import com.pocketshell.uikit.theme.PocketShellTheme
import com.termux.view.TerminalView
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The hotkeys panel wired into the session screen (#2521).
 *
 * `KeyBytesTest` pins what each key MEANS. This pins that the real ui-kit
 * `TerminalHotkeysPanel` is opened from the compact launcher, that a tap
 * reaches [keyBarBytes], and that the panel stays open afterwards.
 *
 * J03 proves the same round trip end to end against a real host: `sleep`,
 * `^C` from the panel, prompt back.
 */
@RunWith(AndroidJUnit4::class)
class SessionKeyBarTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var rootView: View? = null

    @Test
    fun `the compact launcher is present while still attaching`() {
        setContent(SessionUiState.Connecting)

        composeRule.onNodeWithTag(SESSION_LAUNCHER_BAR_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SESSION_HOTKEYS_LAUNCHER_TAG).assertIsDisplayed()
    }

    @Test
    fun `the compact launcher is present once the session is live`() {
        setContent(SessionUiState.Live(createRemoteTerminalSession()))

        composeRule.onNodeWithTag(SESSION_LAUNCHER_BAR_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SESSION_HOTKEYS_LAUNCHER_TAG).assertIsDisplayed()
    }

    @Test
    fun `closed chrome has no always-visible key bar`() {
        setContent(SessionUiState.Live(createRemoteTerminalSession()))

        listOf("Ctrl", "Esc", "Tab", "Enter").forEach { label ->
            composeRule.onNodeWithText(label).assertDoesNotExist()
        }
    }

    @Test
    fun `a failed session has no hotkeys launcher`() {
        setContent(SessionUiState.Failed("Session \"$SESSION\" ended (exit 3)."))

        composeRule.onNodeWithTag(SESSION_HOTKEYS_LAUNCHER_TAG).assertDoesNotExist()
    }

    @Test
    fun `tapping the hotkeys chip opens the panel`() {
        setContent(SessionUiState.Live(createRemoteTerminalSession()))

        composeRule.onNodeWithTag(SESSION_HOTKEYS_LAUNCHER_TAG).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(TERMINAL_HOTKEYS_PANEL_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("^C").assertIsDisplayed()
        composeRule.onNodeWithText(KEY_LABEL_ENTER).assertIsDisplayed()
        composeRule.onNodeWithText(KEY_LABEL_SHIFT_TAB).assertIsDisplayed()
    }

    @Test
    fun `tapping escape on the panel sends the escape byte and stays open`() {
        val sent = tapHotkey(KEY_LABEL_ESC)
        assertEquals(1, sent.size)
        assertArrayEquals(byteArrayOf(0x1B), sent.single())
        composeRule.onNodeWithTag(TERMINAL_HOTKEYS_PANEL_TAG).assertIsDisplayed()
    }

    @Test
    fun `tapping enter on the panel sends carriage return`() {
        val sent = tapHotkey(KEY_LABEL_ENTER)
        assertArrayEquals(byteArrayOf(0x0D), sent.single())
        composeRule.onNodeWithTag(TERMINAL_HOTKEYS_PANEL_TAG).assertIsDisplayed()
    }

    @Test
    fun `tapping caret-C on the panel sends the interrupt byte`() {
        val sent = tapHotkey("^C")
        assertArrayEquals(byteArrayOf(0x03), sent.single())
        composeRule.onNodeWithTag(TERMINAL_HOTKEYS_PANEL_TAG).assertIsDisplayed()
    }

    @Test
    fun `opening the composer overlay does not change the terminal slot size`() {
        val sizes = mutableListOf<Pair<Int, Int>>()
        setContent(
            SessionUiState.Connecting,
            onResized = { c, r -> sizes += c to r },
            cellMetrics = NARROW_CELLS,
        )
        composeRule.waitForIdle()
        assertTrue("no size was reported while attaching", sizes.isNotEmpty())
        val closedSize = sizes.last()

        composeRule.onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG).performClick()
        composeRule.waitForIdle()

        assertEquals(
            "opening the composer sheet must not steal terminal rows",
            closedSize,
            sizes.last(),
        )
    }

    @Test
    fun `opening the hotkeys overlay does not change the terminal slot size`() {
        val sizes = mutableListOf<Pair<Int, Int>>()
        setContent(
            SessionUiState.Connecting,
            onResized = { c, r -> sizes += c to r },
            cellMetrics = NARROW_CELLS,
        )
        composeRule.waitForIdle()
        val closedSize = sizes.last()

        composeRule.onNodeWithTag(SESSION_HOTKEYS_LAUNCHER_TAG).performClick()
        composeRule.waitForIdle()

        assertEquals(
            "opening the hotkeys panel must not steal terminal rows",
            closedSize,
            sizes.last(),
        )
    }

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

    @Test
    fun `the screen stops estimating the size once the terminal exists`() {
        val sizes = mutableListOf<Pair<Int, Int>>()
        setContent(
            SessionUiState.Live(createRemoteTerminalSession()),
            onResized = { c, r -> sizes += c to r },
        )

        composeRule.waitForIdle()
        assertTrue("the estimate must not run while live, got $sizes", sizes.isEmpty())
    }

    @Test
    fun `an unmodified keystroke does not go through the hotkey send path`() {
        val sent = mutableListOf<ByteArray>()
        setContent(SessionUiState.Live(createRemoteTerminalSession()), onSend = { sent += it })

        typeCodePoint('c')

        assertTrue("unmodified typing must not be re-routed, got $sent", sent.isEmpty())
    }

    /**
     * Drive the production panel + [keyBarBytes] without the modal window.
     * Robolectric drops clicks inside `ModalBottomSheet`; J03 covers the
     * sheet on a real device.
     */
    private fun tapHotkey(label: String): List<ByteArray> {
        val sent = mutableListOf<ByteArray>()
        composeRule.setContent {
            PocketShellTheme {
                TerminalHotkeysPanel(
                    sections = HOTKEY_MAIN_SECTIONS,
                    page = TerminalHotkeysPage.Main,
                    onKey = { binding: KeyBinding ->
                        keyBarBytes(binding.label)?.let { sent += it }
                    },
                    onClose = {},
                    modifier = Modifier.testTag(TERMINAL_HOTKEYS_PANEL_TAG),
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(label).performClick()
        composeRule.waitForIdle()
        return sent
    }

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
        initiallyShowComposer: Boolean = false,
        initiallyShowHotkeys: Boolean = false,
    ) {
        composeRule.setContent {
            rootView = LocalView.current
            PocketShellTheme {
                SessionScreen(
                    state = state,
                    composerState = ComposerUiState(),
                    sessionName = SESSION,
                    onBack = {},
                    onResized = onResized,
                    onRetry = {},
                    onHotkeySend = onSend,
                    onDraftChange = {},
                    onSend = { true },
                    onInsert = {},
                    onAttach = {},
                    onMicTap = {},
                    onCancelRecording = {},
                    onToggleHistory = {},
                    onTogglePreview = {},
                    onRemoveAttachment = {},
                    onDismissNotice = {},
                    onDiscardDraft = {},
                    onUseHistoryEntry = {},
                    cellMetrics = cellMetrics,
                    initiallyShowComposer = initiallyShowComposer,
                    initiallyShowHotkeys = initiallyShowHotkeys,
                )
            }
        }
        composeRule.waitForIdle()
    }

    private companion object {
        const val SESSION = "git-pocketshell"

        val NARROW_CELLS = TerminalCellMetrics(
            cellWidthPx = 16.8f,
            lineHeightPx = 31,
            lineSpacingAndAscentPx = 4,
        )
    }
}
