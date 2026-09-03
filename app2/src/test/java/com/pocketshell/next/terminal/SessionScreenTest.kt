package com.pocketshell.next.terminal

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The rendered session screen on the host JVM (Robolectric).
 *
 * `J03AttachAndTypeJourney` proves the terminal really attaches, renders and
 * types against a real host on a real device; this suite pins the chrome rules
 * around it, which are the ones a device journey would only notice by
 * screenshot: that "attaching" and "not attached" do not render the same, that
 * the terminal surface exists exactly when the session is live, that the error
 * text is the ViewModel's own, and that Back is reachable from every state
 * (there is no retry in U-4, so Back is the only way out of a failure).
 */
@RunWith(AndroidJUnit4::class)
class SessionScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `connecting shows the attaching state and no terminal`() {
        setContent(SessionUiState.Connecting)

        composeRule.onNodeWithTag(SESSION_CONNECTING_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SESSION_TERMINAL_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(SESSION_ERROR_BANNER_TAG).assertDoesNotExist()
        // The screen always says WHICH session, because the tree can list
        // several with near-identical names.
        composeRule.onNodeWithTag(SESSION_TITLE_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(SESSION).assertIsDisplayed()
    }

    @Test
    fun `a failure shows the message it was given and never a terminal`() {
        setContent(SessionUiState.Failed("Session \"$SESSION\" ended (exit 3)."))

        composeRule.onNodeWithTag(SESSION_ERROR_BANNER_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Session \"$SESSION\" ended (exit 3).").assertIsDisplayed()
        // "attaching" and "not attached" must not look the same.
        composeRule.onNodeWithTag(SESSION_CONNECTING_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(SESSION_TERMINAL_TAG).assertDoesNotExist()
    }

    @Test
    fun `back is reachable while connecting`() {
        var backs = 0
        setContent(SessionUiState.Connecting, onBack = { backs += 1 })

        composeRule.onNodeWithTag(SESSION_BACK_TAG).performClick()

        assertEquals(1, backs)
    }

    /**
     * A failure is a dead end by design in U-4 (reconnect is task U-7), so the
     * ONE affordance it must carry is the way out.
     */
    @Test
    fun `back is reachable from a failure`() {
        var backs = 0
        setContent(SessionUiState.Failed("no route to host"), onBack = { backs += 1 })

        composeRule.onNodeWithText("Back").performClick()

        assertEquals(1, backs)
    }

    /**
     * The live state hosts the vendored terminal view.
     *
     * Robolectric cannot render the emulator's canvas (its `libtermux.so` is a
     * device artifact), so this asserts the surface is COMPOSED — that "the
     * session is live" and "there is a terminal on screen" agree. What the
     * canvas actually contains is J03's assertion, on a device, from the
     * emulator's own screen buffer.
     */
    @Test
    fun `a live session hosts the terminal surface`() {
        setContent(SessionUiState.Live(createRemoteTerminalSession()))

        composeRule.onNodeWithTag(SESSION_TERMINAL_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SESSION_CONNECTING_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(SESSION_ERROR_BANNER_TAG).assertDoesNotExist()
    }

    private fun setContent(
        state: SessionUiState,
        onBack: () -> Unit = {},
        onResized: (Int, Int) -> Unit = { _, _ -> },
        onSend: (ByteArray) -> Unit = {},
    ) {
        composeRule.setContent {
            PocketShellTheme {
                SessionScreen(
                    state = state,
                    sessionName = SESSION,
                    onBack = onBack,
                    onResized = onResized,
                    onSend = onSend,
                )
            }
        }
    }

    private companion object {
        const val SESSION = "git-pocketshell"
    }
}
