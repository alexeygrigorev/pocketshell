package com.pocketshell.next.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.hilt.navigation.compose.hiltViewModel
import com.pocketshell.uikit.components.Banner
import com.pocketshell.uikit.components.BannerRole
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.EmptyState
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.termux.terminal.TerminalSession

/** Stable test tags for the session screen's own chrome. */
const val SESSION_SCREEN_TAG: String = "session-screen"
const val SESSION_TITLE_TAG: String = "session-title"
const val SESSION_CONNECTING_TAG: String = "session-connecting"
const val SESSION_ERROR_BANNER_TAG: String = "session-error-banner"
const val SESSION_RECONNECT_BANNER_TAG: String = "session-reconnect-banner"
const val SESSION_RETRY_TAG: String = "session-retry"
const val SESSION_BACK_TAG: String = "session-back"

/**
 * Route-level entry point for `session/{hostId}/{sessionName}` (rewrite tasks
 * U-4 and U-7).
 *
 * The attach is started from a `LaunchedEffect` keyed on the route arguments —
 * [SessionViewModel.open] is idempotent, so a recomposition or a configuration
 * change cannot open a second PTY on the same tmux session.
 */
@Composable
fun SessionRoute(
    hostId: Long,
    sessionName: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SessionViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(hostId, sessionName) { viewModel.open(hostId, sessionName) }

    SessionScreen(
        state = state,
        sessionName = sessionName,
        onBack = onBack,
        onResized = viewModel::onResized,
        onRetry = viewModel::retryNow,
        modifier = modifier,
    )
}

/**
 * One attached session: a title bar and a full-bleed terminal.
 *
 * ## Deliberately almost no chrome
 *
 * A name, a way back, and the terminal. No key bar, no composer, no
 * conversation tab, no hotkey panel, no scroll affordances — every one of those
 * is a later task, and each is a place the pre-rewrite screen accumulated state
 * that had to agree with the terminal's. The one thing the chrome does say is
 * WHICH session, because the tree can show several with similar names.
 *
 * ## Reconnecting keeps the terminal
 *
 * [SessionUiState.Reconnecting] renders the SAME terminal surface as
 * [SessionUiState.Live], with a banner above it. Blanking the pane while the
 * link is down would throw away the last thing the user was reading for no
 * reason: the emulator is simply not being fed, and tmux repaints it on
 * reattach. The banner carries the attempt number and a live countdown so the
 * wait is legible rather than a spinner.
 *
 * ## A failure offers Retry
 *
 * The ladder gives up eventually (task U-7), and the user gets the manual retry
 * the give-up message names, next to the way back.
 *
 * Stateless: the whole screen is a function of [state], so a design render and
 * a journey see the same pixels.
 */
@Composable
fun SessionScreen(
    state: SessionUiState,
    sessionName: String,
    onBack: () -> Unit,
    onResized: (cols: Int, rows: Int) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(SESSION_SCREEN_TAG),
    ) {
        ScreenHeader(
            title = sessionName,
            subtitle = statusLine(state),
            titleTestTag = SESSION_TITLE_TAG,
            leading = {
                PocketShellButton(
                    text = "‹",
                    onClick = onBack,
                    variant = ButtonVariant.Text,
                    compact = true,
                    modifier = Modifier.testTag(SESSION_BACK_TAG),
                )
            },
        )

        when (state) {
            SessionUiState.Connecting -> EmptyState(
                title = "Attaching…",
                description = "Opening a terminal on \"$sessionName\".",
                modifier = Modifier
                    .weight(1f)
                    .testTag(SESSION_CONNECTING_TAG),
            )

            is SessionUiState.Live -> Terminal(
                session = state.terminal,
                onResized = onResized,
                modifier = Modifier.weight(1f),
            )

            is SessionUiState.Reconnecting -> Column(modifier = Modifier.weight(1f)) {
                Banner(
                    text = reconnectingMessage(state),
                    role = BannerRole.Warning,
                    maxLines = 2,
                    trailingContent = {
                        PocketShellButton(
                            text = "Retry",
                            onClick = onRetry,
                            variant = ButtonVariant.Text,
                            compact = true,
                            modifier = Modifier.testTag(SESSION_RETRY_TAG),
                        )
                    },
                    modifier = Modifier
                        .padding(horizontal = PocketShellSpacing.md)
                        .padding(bottom = PocketShellSpacing.sm)
                        .testTag(SESSION_RECONNECT_BANNER_TAG),
                )
                // The last frame stays exactly where it was, under the banner.
                Terminal(
                    session = state.terminal,
                    onResized = onResized,
                    modifier = Modifier.weight(1f),
                )
            }

            is SessionUiState.Failed -> Column(modifier = Modifier.weight(1f)) {
                Banner(
                    text = state.message,
                    role = BannerRole.Error,
                    maxLines = 4,
                    trailingContent = {
                        PocketShellButton(
                            text = "Retry",
                            onClick = onRetry,
                            variant = ButtonVariant.Text,
                            compact = true,
                            modifier = Modifier.testTag(SESSION_RETRY_TAG),
                        )
                    },
                    modifier = Modifier
                        .padding(horizontal = PocketShellSpacing.md)
                        .padding(bottom = PocketShellSpacing.sm)
                        .testTag(SESSION_ERROR_BANNER_TAG),
                )
                EmptyState(
                    title = "Not attached",
                    description = "Tap Retry, or go back to the session list to pick another " +
                        "session.",
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun Terminal(
    session: TerminalSession,
    onResized: (cols: Int, rows: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            // The terminal renders its own background per cell, but the view can
            // be laid out taller than the grid it has drawn so far; without this
            // the gap flashes the theme surface.
            .background(PocketShellColors.Background),
    ) {
        TerminalHostView(
            session = session,
            onResized = onResized,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * The reconnect banner's text.
 *
 * Both numbers are load bearing: the attempt says the app is still working
 * rather than stuck, and the countdown says how long the wait is instead of
 * making the user guess whether anything will happen. The attempt is rendered
 * 1-based — [ReconnectController] counts from 0, users do not.
 */
private fun reconnectingMessage(state: SessionUiState.Reconnecting): String {
    val attempt = state.attempt + 1
    if (state.retryInMs <= 0) return "Reconnecting… attempt $attempt · retrying now"
    // Rounded UP, so a countdown never displays "0s" while it is still waiting.
    val seconds = (state.retryInMs + 999) / 1000
    return "Reconnecting… attempt $attempt · retrying in ${seconds}s"
}

private fun statusLine(state: SessionUiState): String = when (state) {
    SessionUiState.Connecting -> "attaching"
    is SessionUiState.Live -> "attached"
    is SessionUiState.Reconnecting -> "reconnecting"
    is SessionUiState.Failed -> "not attached"
}
