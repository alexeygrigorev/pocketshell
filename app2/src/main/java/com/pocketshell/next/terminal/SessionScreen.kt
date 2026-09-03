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

/** Stable test tags for the session screen's own chrome. */
const val SESSION_SCREEN_TAG: String = "session-screen"
const val SESSION_TITLE_TAG: String = "session-title"
const val SESSION_CONNECTING_TAG: String = "session-connecting"
const val SESSION_ERROR_BANNER_TAG: String = "session-error-banner"
const val SESSION_BACK_TAG: String = "session-back"

/**
 * Route-level entry point for `session/{hostId}/{sessionName}` (rewrite task
 * U-4).
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
 * ## Failure has no retry button
 *
 * A dropped or ended session shows what happened and offers Back. Reconnect is
 * task U-7, and a Retry that silently re-ran `open()` without the backoff and
 * attempt accounting that task owns would be the thing the rewrite is trying
 * not to build again.
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

            is SessionUiState.Live -> Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    // The terminal renders its own background per cell, but the
                    // view can be laid out taller than the grid it has drawn so
                    // far; without this the gap flashes the theme surface.
                    .background(PocketShellColors.Background),
            ) {
                TerminalHostView(
                    session = state.terminal,
                    onResized = onResized,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            is SessionUiState.Failed -> Column(modifier = Modifier.weight(1f)) {
                Banner(
                    text = state.message,
                    role = BannerRole.Error,
                    maxLines = 4,
                    trailingContent = {
                        PocketShellButton(
                            text = "Back",
                            onClick = onBack,
                            variant = ButtonVariant.Text,
                            compact = true,
                        )
                    },
                    modifier = Modifier
                        .padding(horizontal = PocketShellSpacing.md)
                        .padding(bottom = PocketShellSpacing.sm)
                        .testTag(SESSION_ERROR_BANNER_TAG),
                )
                EmptyState(
                    title = "Not attached",
                    description = "Go back to the session list to pick another session.",
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun statusLine(state: SessionUiState): String = when (state) {
    SessionUiState.Connecting -> "attaching"
    is SessionUiState.Live -> "attached"
    is SessionUiState.Failed -> "not attached"
}
