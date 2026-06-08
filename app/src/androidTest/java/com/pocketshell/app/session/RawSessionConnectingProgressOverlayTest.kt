package com.pocketshell.app.session

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Focused raw-SSH coverage for the SessionScreen connecting status band. The
 * full screen needs Hilt + SSH wiring, so this exercises the visible composable
 * directly while preserving the delayed hint/cancel contract.
 */
@RunWith(AndroidJUnit4::class)
class RawSessionConnectingProgressOverlayTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun progressBarVisibleImmediatelyAndSlowHintHiddenAtZero() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            PocketShellTheme {
                ConnectingProgressOverlay(
                    user = "alex",
                    host = "10.0.2.2",
                    port = 22,
                    onCancel = {},
                )
            }
        }

        compose.onNodeWithTag(SESSION_CONNECTING_PROGRESS_TAG).assertIsDisplayed()
        compose.onNodeWithTag(SESSION_CONNECTING_PROGRESS_BAR_TAG).assertIsDisplayed()
        compose.onNodeWithTag(SESSION_CONNECTING_SLOW_HINT_TAG).assertDoesNotExist()
        compose.onNodeWithTag(SESSION_CONNECTING_CANCEL_TAG).assertDoesNotExist()
    }

    @Test
    fun slowHintAppearsAfterFiveSecondsWithoutCancel() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            PocketShellTheme {
                ConnectingProgressOverlay(
                    user = "alex",
                    host = "10.0.2.2",
                    port = 22,
                    onCancel = {},
                )
            }
        }

        compose.mainClock.advanceTimeBy(SESSION_SLOW_CONNECT_HINT_AFTER_MS + 250)

        compose.onNodeWithTag(SESSION_CONNECTING_PROGRESS_TAG).assertIsDisplayed()
        compose.onNodeWithTag(SESSION_CONNECTING_SLOW_HINT_TAG).assertIsDisplayed()
        compose.onNodeWithTag(SESSION_CONNECTING_CANCEL_TAG).assertDoesNotExist()
    }

    @Test
    fun cancelAppearsAfterFifteenSecondsAndRoutesToCallback() {
        compose.mainClock.autoAdvance = false
        var cancelCalls = 0
        compose.setContent {
            PocketShellTheme {
                ConnectingProgressOverlay(
                    user = "alex",
                    host = "10.0.2.2",
                    port = 22,
                    onCancel = { cancelCalls += 1 },
                )
            }
        }

        compose.mainClock.advanceTimeBy(SESSION_CANCEL_AVAILABLE_AFTER_MS + 250)

        compose.onNodeWithTag(SESSION_CONNECTING_PROGRESS_TAG).assertIsDisplayed()
        compose.onNodeWithTag(SESSION_CONNECTING_SLOW_HINT_TAG).assertIsDisplayed()
        compose.onNodeWithTag(SESSION_CONNECTING_CANCEL_TAG).assertIsDisplayed()

        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag(SESSION_CONNECTING_CANCEL_TAG).performClick()

        assertEquals(1, cancelCalls)
    }
}
