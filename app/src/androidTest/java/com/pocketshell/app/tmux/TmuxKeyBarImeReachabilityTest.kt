package com.pocketshell.app.tmux

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.app.layout.rememberHostImeBottomPx
import com.pocketshell.app.proof.signals.waitForInputMethodVisible
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #616: the terminal hotkey [com.pocketshell.uikit.components.KeyBar]
 * (Ctrl/Tab/Esc/arrows) must remain VISIBLE directly above the soft keyboard
 * when the IME opens in a session — not collapse into the IME-hidden chip
 * strip. The maintainer hit this repeatedly (v0.3.33: "keyboard shortcuts are
 * still not visible"): only Gboard's own toolbar showed above the keys.
 *
 * Root cause (the #615 twin): [TmuxSessionScreen] derived `isImeVisible` from
 * Compose's `WindowInsets.ime`, which returns **0 on the maintainer's device**
 * while the keyboard is up. So `isImeVisible` stayed false and the chrome FSM
 * fell to the chip strip ([TmuxTerminalKeyboardChromeMode.HiddenImeControls])
 * — the KeyBar was never composed. The emulator reads `WindowInsets.ime`
 * correctly, which is why prior rounds shipped this unfixed.
 *
 * The fix reads the IME inset from the HOST activity window
 * ([rememberHostImeBottomPx]). This connected test raises a REAL soft keyboard
 * against the host window and asserts the production-shape gate flips to show
 * the KeyBar. The harness mounts the EXACT production gate + composable
 * ([TmuxTerminalBottomControls] driven by the FSM) so this is not a narrow
 * proxy. The full-device emulator keyboard-up screenshot in the issue thread is
 * the visual acceptance; this test is the regression guard.
 */
@RunWith(AndroidJUnit4::class)
class TmuxKeyBarImeReachabilityTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val draftTag = "ime-keybar-test:draft"

    @Test
    fun keyBarVisibleAboveKeyboardWhenImeUp() {
        compose.setContent {
            PocketShellTheme {
                KeyBarImeHarness(draftTag = draftTag)
            }
        }
        compose.waitForIdle()

        // Keyboard down: the gate shows the IME-hidden chip strip, NOT the
        // KeyBar. This is the baseline the host-window reader must flip.
        compose.onNodeWithTag(TMUX_KEY_BAR_TAG).assertDoesNotExist()

        // Raise the REAL soft keyboard against the host window — exactly what
        // tapping the terminal does in production.
        compose.onNodeWithTag(draftTag).performClick()
        compose.activity.runOnUiThread {
            val window = compose.activity.window
            WindowInsetsControllerCompat(window, window.decorView)
                .show(WindowInsetsCompat.Type.ime())
        }

        val imeVisible = waitForInputMethodVisible(
            scenario = compose.activityRule.scenario,
            expected = true,
            timeoutMs = 30_000L,
        )
        // The connected run shards across AVDs; some have no working soft IME
        // (e.g. `test-2`). SKIP rather than hard-fail there — matching
        // PromptComposerSheetImeReachabilityTest (#682). On AVDs where the IME
        // DOES come up we keep the real reachability assertions below.
        assumeTrue(
            "IME not available on this emulator; cannot validate issue #616 keybar reachability",
            imeVisible,
        )

        // The host-window IME inset must be positive (this is the read the fix
        // uses — and the exact value that was 0 on the device with the old
        // WindowInsets.ime read).
        assertTrue(
            "Host-window IME inset must be > 0 once the keyboard is up",
            imeBottomInsetPx() > 0,
        )

        // Keyboard up: the host-window reader fires the OpenImeTerminalHotkeys
        // mode, so the production KeyBar appears and is displayed (reachable
        // above the keyboard) — not the IME-hidden chip strip.
        compose.waitUntil(timeoutMillis = 8_000) {
            compose
                .onAllNodesWithTag(TMUX_KEY_BAR_TAG)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag(TMUX_KEY_BAR_TAG).assertIsDisplayed()
    }

    @Test
    fun chromeModeFsmMapsImeVisibleTerminalToHotkeys() {
        // Pure FSM contract (the gate the host-window inset feeds). Belt-and-
        // suspenders so a future refactor can't silently reroute IME-up
        // terminal panes away from the KeyBar.
        assertEquals(
            TmuxTerminalKeyboardChromeMode.HiddenImeControls,
            tmuxTerminalKeyboardChromeMode(isImeVisible = false, showConversation = false),
        )
        assertEquals(
            TmuxTerminalKeyboardChromeMode.OpenImeTerminalHotkeys,
            tmuxTerminalKeyboardChromeMode(isImeVisible = true, showConversation = false),
        )
        assertEquals(
            TmuxTerminalKeyboardChromeMode.OpenImeConversationNoAccessory,
            tmuxTerminalKeyboardChromeMode(isImeVisible = true, showConversation = true),
        )
    }

    private fun imeBottomInsetPx(): Int {
        var bottom = 0
        compose.activityRule.scenario.onActivity { activity ->
            val decor = activity.window.decorView
            bottom = ViewCompat.getRootWindowInsets(decor)
                ?.getInsets(WindowInsetsCompat.Type.ime())
                ?.bottom
                ?: 0
        }
        return bottom
    }

    /**
     * Mounts the production [TmuxTerminalBottomControls] gated on the
     * host-window IME inset ([rememberHostImeBottomPx]) — the same wiring
     * [TmuxSessionScreen] uses — above a focusable [TextField] that raises the
     * real soft keyboard. A weighted spacer pushes the controls to the bottom
     * so "displayed above the keyboard" is a meaningful reachability check.
     */
    @Composable
    private fun KeyBarImeHarness(draftTag: String) {
        val imeBottomPx by rememberHostImeBottomPx()
        val isImeVisible = imeBottomPx > 0
        var draft by remember { mutableStateOf("") }
        var keyBarExpanded by remember { mutableStateOf(false) }
        Column(modifier = Modifier.fillMaxSize()) {
            TextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(draftTag),
            )
            Spacer(modifier = Modifier.weight(1f))
            TmuxTerminalBottomControls(
                isImeVisible = isImeVisible,
                showConversation = false,
                sessionLive = true,
                isAgentPane = false,
                keyBarExpanded = keyBarExpanded,
                onKeyBarExpandedChange = { keyBarExpanded = it },
                onKey = {},
                onChipTap = {},
                onDictateTap = {},
                onEnterTap = {},
                onShowKeyboardTap = {},
                onAddSnippetTap = null,
            )
        }
    }
}
