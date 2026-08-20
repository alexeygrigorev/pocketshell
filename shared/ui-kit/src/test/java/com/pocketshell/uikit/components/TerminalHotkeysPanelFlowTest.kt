package com.pocketshell.uikit.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.model.KeyBinding
import com.pocketshell.uikit.model.KeyKind
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Mutation-capable interaction and geometry proof for issue #1662. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-night-xxhdpi")
class TerminalHotkeysPanelFlowTest {

    @get:Rule
    val compose = createComposeRule()

    private val mainLabels = listOf(
        "←", "↑", "↓", "→",
        "Esc", "Tab", "⇧Tab", "Enter",
        "^B", "^C", "^D", "^Q", "^X",
    )
    private val ctrlLabels = ('A'..'Z').map { "^$it" } + "^\\"
    private val main = listOf(
        HotkeySection(
            "ARROWS",
            listOf("←", "↑", "↓", "→").map {
                KeyBinding(it, KeyKind.Arrow)
            },
            4,
        ),
        HotkeySection(
            "KEYS",
            listOf("Esc", "Tab", "⇧Tab", "Enter").map {
                KeyBinding(it, KeyKind.Regular)
            },
            4,
        ),
        HotkeySection(
            "CTRL",
            listOf("^B", "^C", "^D", "^Q", "^X").map {
                KeyBinding(it, KeyKind.Regular)
            },
            5,
        ),
    )
    private val ctrl = listOf(
        HotkeySection(
            "CTRL + KEY",
            listOf("QWERT", "YUIOP", "ASDFG", "HJKL", "ZXCVB", "NM\\")
                .flatMap { row -> row.map { KeyBinding("^$it", KeyKind.Regular) } },
            5,
            rows = listOf("QWERT", "YUIOP", "ASDFG", "HJKL", "ZXCVB", "NM\\")
                .map { row -> row.map { KeyBinding("^$it", KeyKind.Regular) } },
        ),
    )

    @Test
    fun ctrlFlowIsVisibleStaysOpenAfterSendAndBackReturnsToMain() {
        val sent = mutableListOf<String>()
        setFlow(sent = sent)

        compose.onNodeWithTag(TERMINAL_HOTKEYS_CTRL_FLOW_TAG).performClick()
        compose.onNodeWithText("Ctrl + …").assertExists()
        compose.onNodeWithTag(TERMINAL_HOTKEYS_PANEL_CLOSE_TAG).assertExists()
        compose.onNodeWithText("^R").performClick()
        compose.onNodeWithText("^R").performClick()

        assertEquals(listOf("^R", "^R"), sent)
        compose.onNodeWithText("Ctrl + …").assertExists()
        compose.onNodeWithTag(TERMINAL_HOTKEYS_PANEL_BACK_TAG).performClick()
        compose.onNodeWithText("Terminal hotkeys").assertExists()
        compose.onNodeWithTag(TERMINAL_HOTKEYS_CTRL_FLOW_TAG).assertExists()
    }

    @Test
    fun ordinaryTwoTapFallbackDispatchesCtrlCAndCtrlDAsIndependentActions() {
        val sent = mutableListOf<String>()
        setFlow(sent = sent)

        compose.onNodeWithText("^C").performClick()
        compose.onNodeWithText("^C").performClick()
        compose.onNodeWithText("^D").performClick()
        compose.onNodeWithText("^D").performClick()

        assertEquals(listOf("^C", "^C", "^D", "^D"), sent)
    }

    @Test
    fun cAndDExposePersistentCueLongClickLabelAndOneGestureCallback() {
        val sent = mutableListOf<String>()
        val held = mutableListOf<String>()
        setFlow(sent = sent, held = held)

        assertEquals(2, compose.onAllNodesWithText("hold ×2").fetchSemanticsNodes().size)
        val ctrlC = compose.onNodeWithText("^C")
        val ctrlD = compose.onNodeWithText("^D")
        assertEquals(
            "Send Ctrl-C twice",
            ctrlC.fetchSemanticsNode().config[SemanticsActions.OnLongClick].label,
        )
        assertEquals(
            "Send Ctrl-D twice",
            ctrlD.fetchSemanticsNode().config[SemanticsActions.OnLongClick].label,
        )
        ctrlC.performTouchInput { longClick() }
        ctrlD.performTouchInput { longClick() }

        assertEquals(emptyList<String>(), sent)
        assertEquals(
            "each physical long-press must dispatch its doubled-control callback exactly once",
            listOf("^C", "^D"),
            held,
        )
    }

    @Test
    fun everyTargetOnBothPagesIsContainedAndAtLeast48DpAt320Dp() {
        assertEveryTargetGeometry(widthDp = 320, fontScale = 1f)
    }

    @Test
    fun everyTargetOnBothPagesIsContainedAndAtLeast48DpAtPixel7Width() {
        assertEveryTargetGeometry(widthDp = 411, fontScale = 1f)
    }

    @Test
    fun everyTargetIncludingAllCtrlLettersIsContainedAt320DpLargeFont() {
        assertEveryTargetGeometry(widthDp = 320, fontScale = LARGE_FONT_SCALE)
    }

    @Test
    fun everyTargetIncludingAllCtrlLettersIsContainedAtPixel7LargeFont() {
        assertEveryTargetGeometry(widthDp = 411, fontScale = LARGE_FONT_SCALE)
    }

    @Test
    fun disabledPanelNeitherNavigatesNorSends() {
        val sent = mutableListOf<String>()
        setFlow(enabled = false, sent = sent)

        compose.onNodeWithTag(TERMINAL_HOTKEYS_CTRL_FLOW_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(TERMINAL_HOTKEYS_CTRL_FLOW_TAG).performClick()
        compose.onNodeWithText("^C").performClick()

        compose.onNodeWithText("Ctrl + …").assertDoesNotExist()
        assertTrue(sent.isEmpty())
    }

    @Test
    fun liveToNonLiveTransitionDisablesAlreadyOpenCtrlPageWithoutSending() {
        val sent = mutableListOf<String>()
        var enabled by mutableStateOf(true)
        compose.setContent {
            PocketShellTheme {
                var page by remember { mutableStateOf(TerminalHotkeysPage.Main) }
                TerminalHotkeysPanel(
                    sections = if (page == TerminalHotkeysPage.Main) main else ctrl,
                    page = page,
                    onKey = { sent += it.label },
                    onOpenCtrlPage = { if (enabled) page = TerminalHotkeysPage.Ctrl },
                    onBackToMain = { if (enabled) page = TerminalHotkeysPage.Main },
                    onClose = {},
                    enabled = enabled,
                )
            }
        }
        compose.onNodeWithTag(TERMINAL_HOTKEYS_CTRL_FLOW_TAG).performClick()
        compose.onNodeWithText("Ctrl + …").assertExists()

        compose.runOnIdle { enabled = false }
        compose.waitForIdle()
        compose.onNodeWithTag(TERMINAL_HOTKEYS_PANEL_BACK_TAG).assertIsNotEnabled()
        compose.onNodeWithText("^Q").assertIsNotEnabled().performClick()
        compose.onNodeWithTag(TERMINAL_HOTKEYS_PANEL_BACK_TAG).performClick()

        compose.onNodeWithText("Ctrl + …").assertExists()
        assertTrue(sent.isEmpty())
    }

    private fun assertEveryTargetGeometry(widthDp: Int, fontScale: Float) {
        setFlow(widthDp = widthDp, fontScale = fontScale)
        assertPageGeometry(mainLabels, includeBack = false, includeCtrlFlow = true)

        compose.onNodeWithTag(TERMINAL_HOTKEYS_CTRL_FLOW_TAG).performClick()
        compose.waitForIdle()
        assertPageGeometry(ctrlLabels, includeBack = true, includeCtrlFlow = false)
    }

    private fun assertPageGeometry(
        labels: List<String>,
        includeBack: Boolean,
        includeCtrlFlow: Boolean,
    ) {
        val hostBounds = compose.onNodeWithTag(HOST_TAG).fetchSemanticsNode().boundsInRoot
        val targets = labels.map { label ->
            label to compose.onNode(
                hasText(label)
                    .and(hasClickAction())
                    .and(hasAnyAncestor(hasTestTag(HOST_TAG))),
            ).fetchSemanticsNode()
        }.toMutableList()
        targets += "close" to compose.onNodeWithTag(TERMINAL_HOTKEYS_PANEL_CLOSE_TAG)
            .fetchSemanticsNode()
        if (includeBack) {
            targets += "back" to compose.onNodeWithTag(TERMINAL_HOTKEYS_PANEL_BACK_TAG)
                .fetchSemanticsNode()
        }
        if (includeCtrlFlow) {
            targets += "Ctrl+…" to compose.onNodeWithTag(TERMINAL_HOTKEYS_CTRL_FLOW_TAG)
                .fetchSemanticsNode()
        }

        val minTargetPx = 48f * compose.density.density
        val slopPx = compose.density.density
        targets.forEach { (label, node) ->
            val bounds = node.boundsInRoot
            assertTrue(
                "$label target width must be >=48dp; width=${bounds.width}px min=${minTargetPx}px",
                bounds.width + slopPx >= minTargetPx,
            )
            assertTrue(
                "$label target height must be >=48dp; height=${bounds.height}px min=${minTargetPx}px",
                bounds.height + slopPx >= minTargetPx,
            )
            assertTrue(
                "$label target must be fully contained horizontally; target=$bounds host=$hostBounds",
                bounds.left + slopPx >= hostBounds.left &&
                    bounds.right <= hostBounds.right + slopPx,
            )
            assertTrue(
                "$label target must be fully contained vertically; target=$bounds host=$hostBounds",
                bounds.top + slopPx >= hostBounds.top &&
                    bounds.bottom <= hostBounds.bottom + slopPx,
            )
            if (label.startsWith("^")) {
                assertEquals(
                    "$label must not truncate at the requested width/font scale",
                    false,
                    node.config[HotkeyLabelTruncatedKey],
                )
            }
        }
    }

    private fun setFlow(
        enabled: Boolean = true,
        sent: MutableList<String> = mutableListOf(),
        held: MutableList<String> = mutableListOf(),
        widthDp: Int = 320,
        fontScale: Float = 1f,
    ) {
        compose.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(baseDensity.density, fontScale),
            ) {
                PocketShellTheme {
                    var page by remember { mutableStateOf(TerminalHotkeysPage.Main) }
                    Box(
                        modifier = Modifier
                            .width(widthDp.dp)
                            .fillMaxHeight()
                            .testTag(HOST_TAG),
                    ) {
                        TerminalHotkeysPanel(
                            sections = if (page == TerminalHotkeysPage.Main) main else ctrl,
                            page = page,
                            onKey = { sent += it.label },
                            onLongKey = { held += it.label },
                            onOpenCtrlPage = { if (enabled) page = TerminalHotkeysPage.Ctrl },
                            onBackToMain = { if (enabled) page = TerminalHotkeysPage.Main },
                            onClose = {},
                            enabled = enabled,
                            longPressActions = if (page == TerminalHotkeysPage.Main) {
                                mapOf(
                                    "^C" to HotkeyLongPressAction("hold ×2", "Send Ctrl-C twice"),
                                    "^D" to HotkeyLongPressAction("hold ×2", "Send Ctrl-D twice"),
                                )
                            } else {
                                emptyMap()
                            },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private companion object {
        const val HOST_TAG = "issue1662:hotkeys-geometry-host"
        const val LARGE_FONT_SCALE = 1.3f
    }
}
