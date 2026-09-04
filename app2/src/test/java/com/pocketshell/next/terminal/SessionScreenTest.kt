package com.pocketshell.next.terminal

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.next.composer.COMPOSER_TAG
import com.pocketshell.next.composer.COMPOSER_UNDELIVERED_TAG
import com.pocketshell.next.composer.ComposerNotice
import com.pocketshell.next.composer.ComposerUiState
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

    @Test
    fun `back is reachable from a failure`() {
        var backs = 0
        setContent(SessionUiState.Failed("no route to host"), onBack = { backs += 1 })

        composeRule.onNodeWithTag(SESSION_BACK_TAG).performClick()

        assertEquals(1, backs)
    }

    /**
     * The give-up state's other affordance (task U-7): the ladder stops, the
     * user does not have to. A failure with no way to try again would send
     * every transient outage back through the session tree.
     */
    @Test
    fun `a failure offers a retry that calls back`() {
        var retries = 0
        setContent(SessionUiState.Failed("Could not reconnect."), onRetry = { retries += 1 })

        composeRule.onNodeWithTag(SESSION_RETRY_TAG).performClick()

        assertEquals(1, retries)
    }

    /**
     * The reconnect banner renders the two numbers that make the wait legible —
     * which attempt this is (1-based for a human) and how long until the next
     * one.
     */
    @Test
    fun `reconnecting shows the attempt and the countdown`() {
        setContent(
            SessionUiState.Reconnecting(
                attempt = 2,
                retryInMs = 5_000,
                terminal = createRemoteTerminalSession(),
            ),
        )

        composeRule.onNodeWithTag(SESSION_RECONNECT_BANNER_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Reconnecting… attempt 3 · retrying in 5s").assertIsDisplayed()
        // Not the same thing as a failure, and not the same thing as attaching.
        composeRule.onNodeWithTag(SESSION_ERROR_BANNER_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(SESSION_CONNECTING_TAG).assertDoesNotExist()
    }

    /**
     * The countdown rounds UP, so the banner never says "0s" while it is still
     * waiting.
     */
    @Test
    fun `the countdown rounds up`() {
        setContent(
            SessionUiState.Reconnecting(
                attempt = 1,
                retryInMs = 1,
                terminal = createRemoteTerminalSession(),
            ),
        )

        composeRule.onNodeWithText("Reconnecting… attempt 2 · retrying in 1s").assertIsDisplayed()
    }

    /** The ladder's first rung has no wait at all, and says so rather than "in 0s". */
    @Test
    fun `the zero wait says it is retrying now`() {
        setContent(
            SessionUiState.Reconnecting(
                attempt = 0,
                retryInMs = 0,
                terminal = createRemoteTerminalSession(),
            ),
        )

        composeRule.onNodeWithText("Reconnecting… attempt 1 · retrying now").assertIsDisplayed()
    }

    /**
     * The whole point of keeping the emulator across a drop: the pane the user
     * was reading stays on screen under the banner. A reconnect state without a
     * terminal surface is a cleared screen.
     */
    @Test
    fun `reconnecting keeps the terminal surface on screen`() {
        setContent(
            SessionUiState.Reconnecting(
                attempt = 0,
                retryInMs = 0,
                terminal = createRemoteTerminalSession(),
            ),
        )

        composeRule.onNodeWithTag(SESSION_TERMINAL_TAG).assertIsDisplayed()
    }

    @Test
    fun `retry is reachable from the reconnect banner`() {
        var retries = 0
        setContent(
            SessionUiState.Reconnecting(
                attempt = 4,
                retryInMs = 10_000,
                terminal = createRemoteTerminalSession(),
            ),
            onRetry = { retries += 1 },
        )

        composeRule.onNodeWithTag(SESSION_RETRY_TAG).performClick()

        assertEquals(1, retries)
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

    /**
     * The composer stays mounted through a failure ON PURPOSE (task P-1): that
     * is the state in which a kept draft matters most, and hiding it would
     * delete the one thing the send contract promises.
     */
    @Test
    fun `the composer is present while connecting`() {
        setContent(SessionUiState.Connecting)

        composeRule.onNodeWithTag(COMPOSER_TAG).assertIsDisplayed()
    }

    @Test
    fun `the composer is present on a live session`() {
        setContent(SessionUiState.Live(createRemoteTerminalSession()))

        composeRule.onNodeWithTag(COMPOSER_TAG).assertIsDisplayed()
    }

    @Test
    fun `the composer survives a failure, because that is when a draft matters`() {
        setContent(SessionUiState.Failed("no route to host"))

        composeRule.onNodeWithTag(SESSION_ERROR_BANNER_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(COMPOSER_TAG).assertIsDisplayed()
    }

    @Test
    fun `an undelivered draft is visible above the terminal`() {
        setContent(
            SessionUiState.Live(createRemoteTerminalSession()),
            composerState = ComposerUiState(
                draft = "kept text",
                notice = ComposerNotice.Undelivered,
            ),
        )

        composeRule.onNodeWithTag(COMPOSER_UNDELIVERED_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("kept text").assertIsDisplayed()
    }

    private fun setContent(
        state: SessionUiState,
        composerState: ComposerUiState = ComposerUiState(),
        onBack: () -> Unit = {},
        onResized: (Int, Int) -> Unit = { _, _ -> },
        onRetry: () -> Unit = {},
        onKeyBarSend: (ByteArray) -> Unit = {},
    ) {
        composeRule.setContent {
            PocketShellTheme {
                SessionScreen(
                    state = state,
                    composerState = composerState,
                    sessionName = SESSION,
                    onBack = onBack,
                    onResized = onResized,
                    onRetry = onRetry,
                    onKeyBarSend = onKeyBarSend,
                    onDraftChange = {},
                    onSend = {},
                    onAttach = {},
                    onMicTap = {},
                    onCancelRecording = {},
                    onToggleHistory = {},
                    onTogglePreview = {},
                    onRemoveAttachment = {},
                    onDismissNotice = {},
                    onDiscardDraft = {},
                    onUseHistoryEntry = {},
                )
            }
        }
    }

    private companion object {
        const val SESSION = "git-pocketshell"
    }
}
