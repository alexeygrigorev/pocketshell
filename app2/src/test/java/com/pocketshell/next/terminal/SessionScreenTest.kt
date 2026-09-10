package com.pocketshell.next.terminal

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.next.composer.COMPOSER_INSERT_TAG
import com.pocketshell.next.composer.COMPOSER_DRAFT_TAG
import com.pocketshell.next.composer.COMPOSER_REVIEW_ACTION_TAG
import com.pocketshell.next.composer.COMPOSER_REVIEW_TAG
import com.pocketshell.next.composer.COMPOSER_SEND_TAG
import com.pocketshell.next.composer.COMPOSER_TAG
import com.pocketshell.next.composer.COMPOSER_TITLE_TAG
import com.pocketshell.next.composer.COMPOSER_UNDELIVERED_TAG
import com.pocketshell.next.composer.ComposerNotice
import com.pocketshell.next.composer.ComposerUiState
import com.pocketshell.next.tree.STOP_SESSION_CANCEL_TAG
import com.pocketshell.next.tree.STOP_SESSION_CONFIRM_TAG
import com.pocketshell.next.tree.STOP_SESSION_ITEM_TAG
import com.pocketshell.next.tree.STOP_SESSION_TITLE
import com.pocketshell.next.tree.stopSessionMessage
import com.pocketshell.next.usage.USAGE_GLANCE_PILL_TAG
import com.pocketshell.next.usage.UsageGlancePillState
import com.pocketshell.uikit.components.SESSION_COMPOSER_LAUNCHER_TAG
import com.pocketshell.uikit.model.PillKind
import com.pocketshell.uikit.components.SESSION_HOTKEYS_LAUNCHER_TAG
import com.pocketshell.uikit.components.SESSION_LAUNCHER_OVERLAY_TAG
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        // The Quiet terminal has the session context row as well as the
        // workspace header, so the session label intentionally appears twice.
        composeRule.onNodeWithTag(SESSION_TITLE_TAG).assertTextContains(SESSION)
    }

    @Test
    fun `an ended session shows the ended page and never a terminal`() {
        setContent(SessionUiState.Failed("Session \"$SESSION\" ended (exit 3)."))

        composeRule.onNodeWithTag(SESSION_ENDED_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Session ended").assertIsDisplayed()
        composeRule.onNodeWithTag(SESSION_ERROR_BANNER_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(SESSION_LAUNCHER_OVERLAY_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(SESSION_CONNECTING_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(SESSION_TERMINAL_TAG).assertDoesNotExist()
    }

    @Test
    fun `back is reachable while connecting`() {
        var backs = 0
        setContent(SessionUiState.Connecting, onBack = { backs += 1 })

        composeRule.onNodeWithTag(SESSION_BACK_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SESSION_BACK_TAG).performClick()

        assertEquals(1, backs)
    }

    @Test
    fun `usage fallback stays promoted out of the terminal actions sheet`() {
        setContent(SessionUiState.Connecting)

        composeRule.onNodeWithTag(SESSION_USAGE_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SESSION_HEADER_KEBAB_TAG).performClick()
        composeRule.onNodeWithTag(TERMINAL_ACTIONS_USAGE_TAG).assertDoesNotExist()
    }

    /**
     * Issue #2579: on the session screen the pill is about THIS session's
     * agent. A focused state carries no window, and the rendered chrome has to
     * show exactly that — "Claude 38%", not "Claude 7d 38%". Asserting on the
     * painted text (not on the data class) is the point: `attribution` is what
     * the pill draws, and a regression that re-added the token would be
     * invisible to a state-level assertion.
     */
    @Test
    fun `a focused pill renders the provider and percent with no window token`() {
        setContent(
            SessionUiState.Connecting,
            usagePillState = UsageGlancePillState(
                percent = 38,
                provider = "Claude",
                window = null,
                kind = PillKind.Ok,
                stale = false,
                fetchedClock = "13:40",
            ),
        )

        composeRule.onNodeWithTag(USAGE_GLANCE_PILL_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SESSION_HEADER_KEBAB_TAG).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Usage Claude 38%").assertIsDisplayed()
        composeRule.onNodeWithText("Claude 7d").assertDoesNotExist()
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

        composeRule.onNodeWithTag(SESSION_RECONNECT_BANNER_TAG)
            .assertIsDisplayed()
        composeRule.onNodeWithText("Connection lost", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText(
            "Last output is shown. Reconnecting… attempt 3 · retrying in 5s",
            substring = true,
        ).assertIsDisplayed()
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

        composeRule.onNodeWithText(
            "Last output is shown. Reconnecting… attempt 2 · retrying in 1s",
            substring = true,
        ).assertIsDisplayed()
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

        composeRule.onNodeWithText(
            "Last output is shown. Reconnecting… attempt 1 · retrying now",
            substring = true,
        ).assertIsDisplayed()
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

    @Test
    fun `reconnecting disables remote composer actions but keeps the draft editable`() {
        val drafts = mutableListOf<String>()
        setContent(
            SessionUiState.Reconnecting(
                attempt = 0,
                retryInMs = 5_000,
                terminal = createRemoteTerminalSession(),
            ),
            composerState = ComposerUiState(draft = "local draft"),
            initiallyShowComposer = true,
            embedComposerInWindow = false,
            onDraftChange = { drafts += it },
        )

        composeRule.onNodeWithTag(COMPOSER_SEND_TAG).assertIsNotEnabled()
        composeRule.onNodeWithTag(COMPOSER_INSERT_TAG).assertIsNotEnabled()
        composeRule.onNodeWithTag(COMPOSER_DRAFT_TAG).performTextInput(" more")

        assertEquals("local draft more", drafts.last())
    }

    @Test
    fun `uncertain delivery opens a review page with reconnect and inspect`() {
        var retries = 0
        var dismissed = 0
        var savedDraft = ""
        setContent(
            SessionUiState.Live(createRemoteTerminalSession()),
            composerState = ComposerUiState(
                draft = "Run the tests before committing.",
                notice = ComposerNotice.DeliveryUncertain,
            ),
            onRetry = { retries += 1 },
            onDraftChange = { savedDraft = it },
            onDismissNotice = { dismissed += 1 },
        )

        composeRule.onNodeWithText("Review before resending").assertIsDisplayed()
        composeRule.onNodeWithTag(COMPOSER_REVIEW_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Delivery could not be confirmed", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithTag(COMPOSER_REVIEW_ACTION_TAG).performClick()

        assertEquals("Run the tests before committing.", savedDraft)
        assertEquals(1, dismissed)
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
     * #2521: closed chrome is the compact launcher only. The circled stack
     * (Ctrl Esc Tab Enter + draft + Send + mic) must not sit in the session
     * column.
     */
    @Test
    fun `closed chrome is the compact launcher, not the composer or key bar`() {
        setContent(SessionUiState.Live(createRemoteTerminalSession()))

        composeRule.onNodeWithTag(SESSION_LAUNCHER_OVERLAY_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SESSION_HOTKEYS_LAUNCHER_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Ctrl").assertDoesNotExist()
        composeRule.onNodeWithText("Esc").assertDoesNotExist()
        composeRule.onNodeWithText("Tab").assertDoesNotExist()
        composeRule.onNodeWithText("Enter").assertDoesNotExist()
        composeRule.onNodeWithTag(COMPOSER_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(COMPOSER_SEND_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(COMPOSER_INSERT_TAG).assertDoesNotExist()
    }

    /**
     * #2631: the launcher must float OVER the terminal, not dock below it.
     *
     * Fails on the pre-#2631 `SessionLauncherBar`, whose full-width row sat
     * after the terminal `Box` in the session `Column` — the terminal then
     * stopped short of the screen bottom and the launcher's rectangle was
     * entirely below the terminal's. The three assertions are the whole
     * acceptance: the terminal owns the bottom edge, the launcher is drawn
     * inside the terminal's rectangle, and it is a corner control rather than
     * a full-width strip.
     */
    @Test
    fun `the launcher floats over the terminal instead of docking below it`() {
        setContent(SessionUiState.Live(createRemoteTerminalSession()))

        val root = composeRule.onRoot().getUnclippedBoundsInRoot()
        val terminal = composeRule.onNodeWithTag(SESSION_TERMINAL_TAG)
            .getUnclippedBoundsInRoot()
        val launcher = composeRule.onNodeWithTag(SESSION_LAUNCHER_OVERLAY_TAG)
            .getUnclippedBoundsInRoot()

        assertTrue(
            "the terminal must reach the screen bottom, got ${terminal.bottom} of ${root.bottom}",
            terminal.bottom >= root.bottom - 1.dp,
        )
        assertTrue(
            "the launcher must sit inside the terminal slot, got $launcher in $terminal",
            launcher.top >= terminal.top && launcher.bottom <= terminal.bottom,
        )
        assertTrue(
            "the launcher must not span the screen, got ${launcher.width} of ${root.width}",
            launcher.width < root.width / 2,
        )

        // #2631 follow-up: "right bottom corner, not middle". On the real
        // screen the launcher must sit exactly one 16dp inset in from the
        // terminal's bottom-right corner. A padded wrapper anywhere between
        // the session Column and this control would push these numbers up.
        assertEquals(
            "expected a 16dp inset from the terminal's end edge, got " +
                "${terminal.right - launcher.right} ($launcher in $terminal)",
            16f,
            (terminal.right - launcher.right).value,
            1f,
        )
        assertEquals(
            "expected a 16dp inset from the terminal's bottom edge, got " +
                "${terminal.bottom - launcher.bottom} ($launcher in $terminal)",
            16f,
            (terminal.bottom - launcher.bottom).value,
            1f,
        )
    }

    @Test
    fun `the compact launcher is present while connecting`() {
        setContent(SessionUiState.Connecting)

        composeRule.onNodeWithTag(SESSION_LAUNCHER_OVERLAY_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(COMPOSER_TAG).assertDoesNotExist()
    }

    @Test
    fun `an ended session has no composer launcher`() {
        setContent(SessionUiState.Failed("Session \"$SESSION\" ended (exit 3)."))

        composeRule.onNodeWithTag(SESSION_ENDED_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SESSION_ERROR_BANNER_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(SESSION_HOTKEYS_LAUNCHER_TAG).assertDoesNotExist()
    }

    @Test
    fun `opening the composer shows a Prompt Composer sheet, not an inline bar`() {
        setContent(
            SessionUiState.Live(createRemoteTerminalSession()),
            initiallyShowComposer = true,
        )

        composeRule.onNodeWithTag(COMPOSER_TITLE_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(COMPOSER_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(COMPOSER_INSERT_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(COMPOSER_SEND_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SESSION_LAUNCHER_OVERLAY_TAG).assertIsDisplayed()
    }

    @Test
    fun `header kebab End session confirms with host context`() {
        var stopped = 0
        setContent(
            SessionUiState.Live(createRemoteTerminalSession()),
            onStopSession = { stopped += 1 },
        )

        composeRule.onNodeWithTag(SESSION_HEADER_KEBAB_TAG).performClick()
        composeRule.onNodeWithTag(TERMINAL_ACTIONS_SHEET_TAG).performTouchInput { swipeUp() }
        composeRule.onNodeWithTag(STOP_SESSION_ITEM_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(STOP_SESSION_ITEM_TAG).performClick()
        composeRule.onNodeWithText(STOP_SESSION_TITLE).assertIsDisplayed()
        composeRule.onNodeWithText(stopSessionMessage(SESSION, host = "this host")).assertIsDisplayed()
        composeRule.onNodeWithTag(STOP_SESSION_CONFIRM_TAG).performClick()

        assertEquals(1, stopped)
    }

    @Test
    fun `cancelling Stop on the session screen does not kill`() {
        var stopped = 0
        setContent(
            SessionUiState.Live(createRemoteTerminalSession()),
            onStopSession = { stopped += 1 },
        )

        composeRule.onNodeWithTag(SESSION_HEADER_KEBAB_TAG).performClick()
        composeRule.onNodeWithTag(TERMINAL_ACTIONS_SHEET_TAG).performTouchInput { swipeUp() }
        composeRule.onNodeWithTag(STOP_SESSION_ITEM_TAG).performClick()
        composeRule.onNodeWithTag(STOP_SESSION_CANCEL_TAG).performClick()

        assertEquals(0, stopped)
        composeRule.onNodeWithText(STOP_SESSION_TITLE).assertDoesNotExist()
    }

    @Test
    fun `an undelivered draft is visible inside the composer sheet`() {
        setContent(
            SessionUiState.Live(createRemoteTerminalSession()),
            composerState = ComposerUiState(
                draft = "kept text",
                notice = ComposerNotice.Undelivered,
            ),
            initiallyShowComposer = true,
        )

        composeRule.onNodeWithTag(COMPOSER_UNDELIVERED_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("kept text").assertIsDisplayed()
    }

    @Test
    fun `a live send dismisses the composer sheet`() {
        setContent(
            SessionUiState.Live(createRemoteTerminalSession()),
            composerState = ComposerUiState(draft = "hello", micAvailable = true),
            initiallyShowComposer = true,
            onSend = { true },
            embedComposerInWindow = false,
        )

        composeRule.onNodeWithTag(COMPOSER_SEND_TAG).performClick()

        composeRule.onNodeWithTag(COMPOSER_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(COMPOSER_TITLE_TAG).assertDoesNotExist()
    }

    @Test
    fun `insert does not dismiss the composer sheet`() {
        var inserts = 0
        setContent(
            SessionUiState.Live(createRemoteTerminalSession()),
            composerState = ComposerUiState(draft = "hello", micAvailable = true),
            initiallyShowComposer = true,
            onInsert = { inserts += 1 },
            embedComposerInWindow = false,
        )

        composeRule.onNodeWithTag(COMPOSER_INSERT_TAG).performClick()

        assertEquals(1, inserts)
        composeRule.onNodeWithTag(COMPOSER_TAG).assertIsDisplayed()
    }

    @Test
    fun `an undelivered send keeps the composer sheet`() {
        setContent(
            SessionUiState.Live(createRemoteTerminalSession()),
            composerState = ComposerUiState(
                draft = "kept text",
                notice = ComposerNotice.Undelivered,
            ),
            initiallyShowComposer = true,
            onSend = { false },
            embedComposerInWindow = false,
        )

        composeRule.onNodeWithTag(COMPOSER_SEND_TAG).performClick()

        composeRule.onNodeWithTag(COMPOSER_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(COMPOSER_UNDELIVERED_TAG).assertIsDisplayed()
    }

    private fun setContent(
        state: SessionUiState,
        composerState: ComposerUiState = ComposerUiState(),
        onBack: () -> Unit = {},
        onResized: (Int, Int) -> Unit = { _, _ -> },
        onRetry: () -> Unit = {},
        onStopSession: () -> Unit = {},
        onHotkeySend: (ByteArray) -> Unit = {},
        usagePillState: UsageGlancePillState? = null,
        onOpenUsage: () -> Unit = {},
        onDraftChange: (String) -> Unit = {},
        onDismissNotice: () -> Unit = {},
        onSend: () -> Boolean = { true },
        onInsert: () -> Unit = {},
        initiallyShowComposer: Boolean = false,
        initiallyShowHotkeys: Boolean = false,
        embedComposerInWindow: Boolean = true,
    ) {
        composeRule.setContent {
            PocketShellTheme {
                SessionScreen(
                    state = state,
                    composerState = composerState,
                    sessionName = SESSION,
                    onBack = onBack,
                    usagePillState = usagePillState,
                    onOpenUsage = onOpenUsage,
                    onResized = onResized,
                    onRetry = onRetry,
                    onStopSession = onStopSession,
                    onHotkeySend = onHotkeySend,
                    onDraftChange = onDraftChange,
                    onSend = onSend,
                    onInsert = onInsert,
                    onAttach = {},
                    onMicTap = {},
                    onCancelRecording = {},
                    onToggleHistory = {},
                    onTogglePreview = {},
                    onRemoveAttachment = {},
                    onDismissNotice = onDismissNotice,
                    onDiscardDraft = {},
                    onUseHistoryEntry = {},
                    initiallyShowComposer = initiallyShowComposer,
                    initiallyShowHotkeys = initiallyShowHotkeys,
                    embedComposerInWindow = embedComposerInWindow,
                )
            }
        }
    }

    private companion object {
        const val SESSION = "git-pocketshell"
    }
}
