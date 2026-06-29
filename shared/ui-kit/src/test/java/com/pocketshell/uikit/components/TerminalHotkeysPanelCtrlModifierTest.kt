package com.pocketshell.uikit.components

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.pocketshell.uikit.model.KeyBinding
import com.pocketshell.uikit.model.KeyKind
import com.pocketshell.uikit.model.KeyModifierState
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Issue #1091: the sticky `Ctrl` modifier's ACTIVE (accent) state must be
 * visible in the terminal-hotkeys panel, and the modifier + letter keys must
 * fire `onKey`. This JVM Robolectric Compose test (Unit job, no emulator)
 * covers the "active state is visible" acceptance criterion via the
 * [hotkeyModifierActive] semantics flag the panel publishes only on the active
 * modifier slot, plus that the panel emits the taps the screen needs to arm /
 * compose the modifier.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
class TerminalHotkeysPanelCtrlModifierTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val sections = listOf(
        HotkeySection(
            title = "CTRL + LETTER",
            keys = listOf(KeyBinding("Ctrl", KeyKind.Modifier)),
            columns = 4,
        ),
        HotkeySection(
            title = "LETTERS",
            keys = ('a'..'z').map { KeyBinding(it.toString(), KeyKind.Regular) },
            columns = 7,
        ),
    )

    private fun activeModifierNodes() =
        composeRule.onAllNodes(
            SemanticsMatcher.expectValue(HotkeyModifierActiveKey, true),
        )

    private fun setPanel(state: KeyModifierState, onKey: (KeyBinding) -> Unit = {}) {
        composeRule.setContent {
            PocketShellTheme {
                Box {
                    TerminalHotkeysPanel(
                        sections = sections,
                        onKey = onKey,
                        onClose = {},
                        modifierState = state,
                    )
                }
            }
        }
    }

    @Test
    fun ctrlSlotIsInactiveWhenModifierOff() {
        setPanel(KeyModifierState.Off)
        // No slot publishes an ACTIVE modifier: the Ctrl key reads `false`,
        // letters never publish the flag at all.
        activeModifierNodes().assertCountEquals(0)
    }

    @Test
    fun ctrlSlotIsActiveAccentWhenOneShotArmed() {
        setPanel(KeyModifierState.OneShot)
        activeModifierNodes().assertCountEquals(1)
    }

    @Test
    fun ctrlSlotIsActiveAccentWhenLocked() {
        setPanel(KeyModifierState.Locked)
        activeModifierNodes().assertCountEquals(1)
    }

    @Test
    fun ctrlAndLetterKeysEmitTaps() {
        val taps = mutableListOf<String>()
        setPanel(KeyModifierState.Off) { taps += it.label }

        composeRule.onNodeWithText("Ctrl").performClick()
        composeRule.onNodeWithText("x").performClick()

        assertEquals(
            "tapping Ctrl then a letter must emit both labels so the screen can " +
                "arm and compose the sticky modifier",
            listOf("Ctrl", "x"),
            taps,
        )
    }
}
