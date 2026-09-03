package com.pocketshell.next.terminal

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.hilt.navigation.compose.hiltViewModel
import com.pocketshell.next.composer.ComposerBar
import com.pocketshell.next.composer.ComposerUiState
import com.pocketshell.next.composer.ComposerViewModel
import com.pocketshell.next.composer.MessageHistorySheet
import com.pocketshell.next.composer.SentMessage
import com.pocketshell.next.composer.SessionSink
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
 * Route-level entry point for `session/{hostId}/{sessionName}` (rewrite tasks
 * U-4 and P-1).
 *
 * Two ViewModels, one screen. [SessionViewModel] owns the transport and the
 * terminal; [ComposerViewModel] owns the draft, its attachments and its
 * history. They meet at exactly one place — the [SessionSink] built here — and
 * that seam is deliberately two members wide, so the composer can never grow a
 * second opinion about whether the session is attached.
 *
 * The sink reads `uiState.value` at call time rather than closing over the
 * collected state: a sink built from a snapshot would answer "live" from
 * whenever the screen last recomposed, which is precisely when a send would
 * vanish into a dead pane and the draft would be cleared for it.
 */
@Composable
fun SessionRoute(
    hostId: Long,
    sessionName: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SessionViewModel = hiltViewModel(),
    composerViewModel: ComposerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val composerState by composerViewModel.state.collectAsState()

    LaunchedEffect(hostId, sessionName) { viewModel.open(hostId, sessionName) }

    val sink = remember(viewModel) {
        object : SessionSink {
            override val isLive: Boolean get() = viewModel.uiState.value is SessionUiState.Live
            override fun sendBytes(bytes: ByteArray) = viewModel.sendBytes(bytes)
        }
    }
    LaunchedEffect(hostId, sessionName, sink) {
        composerViewModel.bind(hostId, sessionName, sink)
    }

    // `OpenMultipleDocuments` rather than a media picker: the maintainer
    // attaches logs, patches and screenshots, and the storage-access framework
    // is the one picker that reaches all three without a storage permission.
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris: List<Uri> -> composerViewModel.attach(uris) }

    SessionScreen(
        state = state,
        composerState = composerState,
        sessionName = sessionName,
        onBack = onBack,
        onResized = viewModel::onResized,
        onDraftChange = composerViewModel::onDraftChange,
        onSend = composerViewModel::send,
        onAttach = { picker.launch(arrayOf("*/*")) },
        onMicTap = composerViewModel::onMicTap,
        onCancelRecording = composerViewModel::cancelRecording,
        onToggleHistory = composerViewModel::toggleHistory,
        onTogglePreview = composerViewModel::togglePreview,
        onRemoveAttachment = composerViewModel::removeAttachment,
        onDismissNotice = composerViewModel::dismissNotice,
        onDiscardDraft = composerViewModel::discard,
        onUseHistoryEntry = composerViewModel::useHistoryEntry,
        modifier = modifier,
    )
}

/**
 * One attached session: a title bar, a full-bleed terminal, and the composer.
 *
 * ## Chrome, and what is still absent
 *
 * A name, a way back, the terminal, and one composer. Still no key bar, no
 * hotkey panel and no scroll affordances — those are task U-5. The composer is
 * here because it is the send path for any session and the maintainer's
 * confirmed daily surface, and because it is the one piece of chrome that
 * cannot disagree with the terminal: it holds no session state, it asks.
 *
 * ## Keyboard
 *
 * [imePadding] on the root is the whole keyboard story. The composer is the
 * bottom-most child, so it rides up with the IME, and the terminal above it
 * loses exactly the height the keyboard took — which fires [onResized] and
 * resizes the remote PTY, so the remote program reflows instead of drawing
 * under the keyboard. That is why the composer is inline rather than a modal
 * sheet: a sheet floats in its own window and has to be re-anchored against the
 * IME by hand, which is 289 lines of machinery the old client carried and this
 * screen does not need.
 *
 * ## Failure still has no retry button
 *
 * A dropped session shows what happened and offers Back; reconnect is task U-7.
 * The composer stays mounted through a failure ON PURPOSE — that is the state
 * in which a draft is most valuable, and hiding it would delete the one thing
 * the send contract promises to keep.
 *
 * Stateless: the whole screen is a function of its two states, so a design
 * render and a journey see the same pixels.
 */
@Composable
fun SessionScreen(
    state: SessionUiState,
    composerState: ComposerUiState,
    sessionName: String,
    onBack: () -> Unit,
    onResized: (cols: Int, rows: Int) -> Unit,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    onMicTap: () -> Unit,
    onCancelRecording: () -> Unit,
    onToggleHistory: () -> Unit,
    onTogglePreview: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onDismissNotice: () -> Unit,
    onDiscardDraft: () -> Unit,
    onUseHistoryEntry: (SentMessage) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
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

        ComposerBar(
            state = composerState,
            onDraftChange = onDraftChange,
            onSend = onSend,
            onAttach = onAttach,
            onMicTap = onMicTap,
            onCancelRecording = onCancelRecording,
            onToggleHistory = onToggleHistory,
            onTogglePreview = onTogglePreview,
            onRemoveAttachment = onRemoveAttachment,
            onDismissNotice = onDismissNotice,
            onDiscard = onDiscardDraft,
        )
    }

    if (composerState.historyOpen) {
        MessageHistorySheet(
            messages = composerState.history,
            onPick = onUseHistoryEntry,
            onDismiss = onToggleHistory,
        )
    }
}

private fun statusLine(state: SessionUiState): String = when (state) {
    SessionUiState.Connecting -> "attaching"
    is SessionUiState.Live -> "attached"
    is SessionUiState.Failed -> "not attached"
}
