package com.pocketshell.app.tmux

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.uikit.theme.PocketShellTheme
import com.pocketshell.uikit.theme.PocketShellThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #165 — emulator coverage for the SSH-handshake progress overlay
 * surfaced above the terminal viewport while the screen is in
 * [TmuxSessionViewModel.ConnectionStatus.Connecting].
 *
 * The full [TmuxSessionScreen] is too heavy to mount in a Compose
 * androidTest (needs Hilt + a live tmux client), so this test exercises
 * the [ConnectingProgressOverlay] composable directly with the same
 * arguments the screen passes in production. Asserts:
 *
 *  1. At t=0 the progress bar + host label are visible immediately; the
 *     5s "still working" hint and the 15s Cancel affordance are not.
 *     This is the "progress UI visible from tap-attach" acceptance
 *     criterion — even for a 200ms localhost handshake the user sees
 *     the bar at least one frame.
 *  2. After advancing past the 5s mark the "still working" subline
 *     appears, but the Cancel button is still hidden.
 *  3. After advancing past the 15s mark the Cancel button becomes
 *     visible. Tapping it routes through the supplied `onCancel`
 *     callback (which the screen wires to
 *     [TmuxSessionViewModel.cancelConnect]).
 *
 * `compose.mainClock` (auto-advance disabled) drives time deterministically
 * so the 5s + 15s thresholds are exercised in a few hundred millis of
 * wall-clock, not a 15s wait.
 */
@RunWith(AndroidJUnit4::class)
class TmuxConnectingProgressOverlayTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun progressBarVisibleImmediatelyAndSlowHintHiddenAtZero() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                ConnectingProgressOverlay(
                    user = "alex",
                    host = "10.0.2.2",
                    port = 22,
                    sessionLabel = "tmux work",
                    onCancel = {},
                )
            }
        }

        // Acceptance criterion: progress UI visible from tap-attach.
        compose.onNodeWithTag(TMUX_CONNECTING_PROGRESS_TAG).assertIsDisplayed()
        compose.onNodeWithTag(TMUX_CONNECTING_PROGRESS_BAR_TAG).assertIsDisplayed()
        // The 5s + 15s affordances should NOT have triggered yet — the
        // overlay opens with both hidden.
        compose.onNodeWithTag(TMUX_CONNECTING_SLOW_HINT_TAG).assertDoesNotExist()
        compose.onNodeWithTag(TMUX_CONNECTING_CANCEL_TAG).assertDoesNotExist()
    }

    @Test
    fun slowHintAppearsAfterFiveSecondsWithoutCancel() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                ConnectingProgressOverlay(
                    user = "alex",
                    host = "10.0.2.2",
                    port = 22,
                    sessionLabel = "tmux work",
                    onCancel = {},
                )
            }
        }

        // Advance past the 5s threshold but stay well under 15s.
        compose.mainClock.advanceTimeBy(SLOW_CONNECT_HINT_AFTER_MS + 250)

        compose.onNodeWithTag(TMUX_CONNECTING_PROGRESS_TAG).assertIsDisplayed()
        compose.onNodeWithTag(TMUX_CONNECTING_SLOW_HINT_TAG).assertIsDisplayed()
        // Cancel must not appear until the 15s threshold.
        compose.onNodeWithTag(TMUX_CONNECTING_CANCEL_TAG).assertDoesNotExist()
    }

    @Test
    fun cancelAffordanceAppearsAfterFifteenSecondsAndRoutesToCallback() {
        compose.mainClock.autoAdvance = false
        var cancelCalls = 0
        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                ConnectingProgressOverlay(
                    user = "alex",
                    host = "10.0.2.2",
                    port = 22,
                    sessionLabel = "tmux work",
                    onCancel = { cancelCalls += 1 },
                )
            }
        }

        // Advance past the 15s threshold so both the slow hint AND the
        // cancel button are visible.
        compose.mainClock.advanceTimeBy(CANCEL_AVAILABLE_AFTER_MS + 250)

        compose.onNodeWithTag(TMUX_CONNECTING_PROGRESS_TAG).assertIsDisplayed()
        compose.onNodeWithTag(TMUX_CONNECTING_SLOW_HINT_TAG).assertIsDisplayed()
        compose.onNodeWithTag(TMUX_CONNECTING_CANCEL_TAG).assertIsDisplayed()

        // Tap the Cancel button — the callback must fire exactly once.
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag(TMUX_CONNECTING_CANCEL_TAG).performClick()

        assertEquals(
            "Cancel tap must route through the supplied onCancel callback",
            1,
            cancelCalls,
        )
    }

    /**
     * Issue #165 acceptance criterion: "simulate a slow connect (8s) and
     * assert progress UI is visible throughout". Drives the overlay
     * across an 8s window in 1s steps; at every step the progress bar
     * + host string must remain visible. After 5s the "still working"
     * subline is visible too; the Cancel button stays hidden for the
     * duration (since 8s < 15s).
     */
    @Test
    fun progressOverlayRemainsVisibleThroughoutEightSecondSlowConnect() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                ConnectingProgressOverlay(
                    user = "alex",
                    host = "10.0.2.2",
                    port = 22,
                    sessionLabel = "tmux work",
                    onCancel = {},
                )
            }
        }

        // 1s steps from t=0 through t=8s. The progress bar + host label
        // must remain displayed at every step. We assert the progress
        // root rather than the bar itself to allow Compose to optimise
        // the animated indicator across recompositions while still
        // proving the user-visible overlay has not collapsed.
        for (step in 0..8) {
            compose.onNodeWithTag(TMUX_CONNECTING_PROGRESS_TAG).assertIsDisplayed()
            compose.onNodeWithTag(TMUX_CONNECTING_PROGRESS_BAR_TAG).assertIsDisplayed()
            // Sanity: Cancel never appears in an 8s window (15s threshold).
            compose.onNodeWithTag(TMUX_CONNECTING_CANCEL_TAG).assertDoesNotExist()
            compose.mainClock.advanceTimeBy(1_000L)
        }

        // After 8s of accumulated advancement (>= 5s) the slow-hint
        // subline must be displayed.
        compose.onNodeWithTag(TMUX_CONNECTING_SLOW_HINT_TAG).assertIsDisplayed()
    }
}
