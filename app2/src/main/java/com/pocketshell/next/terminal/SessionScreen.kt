package com.pocketshell.next.terminal

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.pocketshell.next.composer.ComposerUiState
import com.pocketshell.next.composer.ComposerViewModel
import com.pocketshell.next.composer.MessageHistorySheet
import com.pocketshell.next.composer.PromptComposerContent
import com.pocketshell.next.composer.PromptComposerSheet
import com.pocketshell.next.composer.SentMessage
import com.pocketshell.next.composer.SessionSink
import com.pocketshell.next.usage.UsageGlancePill
import com.pocketshell.next.usage.UsageGlancePillState
import com.pocketshell.next.usage.UsageGlanceViewModel
import com.pocketshell.uikit.components.Banner
import com.pocketshell.uikit.components.BannerRole
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.EmptyState
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.components.SessionLauncherBar
import com.pocketshell.uikit.model.KeyBinding
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
/** Fallback Usage control when the glance pill has no reading (issue #2532). */
const val SESSION_USAGE_TAG: String = "session-usage"

/**
 * Route-level entry point for `session/{hostId}/{sessionName}` (rewrite tasks
 * U-4, U-5, U-7, P-1, and #2521).
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
 *
 * Hotkeys-panel bytes go STRAIGHT to [SessionViewModel.sendBytes] — they are
 * not composed messages and have no business in the composer's
 * draft/history/attachment machinery.
 */
@Composable
fun SessionRoute(
    hostId: Long,
    sessionName: String,
    onBack: () -> Unit,
    onOpenUsage: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SessionViewModel = hiltViewModel(),
    composerViewModel: ComposerViewModel = hiltViewModel(),
    usageGlanceViewModel: UsageGlanceViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val composerState by composerViewModel.state.collectAsState()
    val usagePillState by usageGlanceViewModel.state.collectAsState()

    LaunchedEffect(hostId, sessionName) { viewModel.open(hostId, sessionName) }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { usageGlanceViewModel.refresh() }

    val sink = remember(viewModel) {
        object : SessionSink {
            override val isLive: Boolean get() = viewModel.uiState.value is SessionUiState.Live
            override fun sendBytes(bytes: ByteArray) = viewModel.sendBytes(bytes)
        }
    }
    LaunchedEffect(hostId, sessionName, sink) {
        composerViewModel.bind(hostId, sessionName, sink)
    }

    LifecycleEventEffect(Lifecycle.Event.ON_START) { composerViewModel.onForegroundResume() }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris: List<Uri> -> composerViewModel.attach(uris) }

    SessionScreen(
        state = state,
        composerState = composerState,
        sessionName = sessionName,
        onBack = onBack,
        usagePillState = usagePillState,
        onOpenUsage = onOpenUsage,
        onResized = viewModel::onResized,
        onRetry = viewModel::retryNow,
        onHotkeySend = viewModel::sendBytes,
        onDraftChange = composerViewModel::onDraftChange,
        onSend = { composerViewModel.send() },
        onInsert = composerViewModel::insert,
        onAttach = { picker.launch(arrayOf("*/*")) },
        onMicTap = composerViewModel::onMicTap,
        onCancelRecording = composerViewModel::cancelRecording,
        onToggleHistory = composerViewModel::toggleHistory,
        onTogglePreview = composerViewModel::togglePreview,
        onRemoveAttachment = composerViewModel::removeAttachment,
        onDismissNotice = composerViewModel::dismissNotice,
        onDiscardDraft = composerViewModel::discard,
        onUseHistoryEntry = composerViewModel::useHistoryEntry,
        onPermissionDenied = composerViewModel::surfacePermissionDenied,
        modifier = modifier,
    )
}

/**
 * One attached session: a title bar, a full-bleed terminal, and a compact
 * launcher bar. Prompt Composer and the hotkeys panel open as floating
 * overlays (#2521) and do not sit in this column.
 *
 * ## The keyboard overlays the terminal; it must not resize it (#887/#2533)
 *
 * The session column is a plain [Modifier.fillMaxSize] — no `imePadding`, no
 * pan. The window is `SOFT_INPUT_ADJUST_NOTHING` (see [com.pocketshell.next.MainActivity]),
 * so the OS neither resizes nor pans when the keyboard shows. The grid stays
 * put; [onResized] does not fire; tmux does not reflow. The composer and
 * hotkeys sheets are [androidx.compose.material3.ModalBottomSheet]s with their
 * own IME policy, so Send/mic stay above the keyboard independently of this
 * column.
 *
 * @param onResized the terminal's size in character cells.
 * @param onHotkeySend raw bytes for the remote from the hotkeys panel.
 * @param initiallyShowComposer test seam: start with the composer sheet open.
 * @param initiallyShowHotkeys test seam: start with the hotkeys panel open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(
    state: SessionUiState,
    composerState: ComposerUiState,
    sessionName: String,
    onBack: () -> Unit,
    onResized: (cols: Int, rows: Int) -> Unit,
    usagePillState: UsageGlancePillState? = null,
    onOpenUsage: () -> Unit = {},
    onRetry: () -> Unit,
    onHotkeySend: (ByteArray) -> Unit,
    onDraftChange: (String) -> Unit,
    /**
     * Production Send. Returns true when the message left (close the sheet);
     * false when the draft was kept (undelivered — leave the sheet open).
     */
    onSend: () -> Boolean,
    onInsert: () -> Unit,
    onAttach: () -> Unit,
    onMicTap: () -> Unit,
    onCancelRecording: () -> Unit,
    onToggleHistory: () -> Unit,
    onTogglePreview: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onDismissNotice: () -> Unit,
    onDiscardDraft: () -> Unit,
    onUseHistoryEntry: (SentMessage) -> Unit,
    onPermissionDenied: () -> Unit = {},
    modifier: Modifier = Modifier,
    cellMetrics: TerminalCellMetrics = rememberTerminalCellMetrics(),
    initiallyShowComposer: Boolean = false,
    initiallyShowHotkeys: Boolean = false,
    /**
     * Test seam: Robolectric drops clicks on a `ModalBottomSheet`. Host-JVM
     * tests that drive Insert/Send pass false so [PromptComposerContent] is
     * composed in-place; production always uses the floating sheet.
     */
    embedComposerInWindow: Boolean = true,
) {
    var composerOpen by remember { mutableStateOf(initiallyShowComposer) }
    var hotkeysOpen by remember { mutableStateOf(initiallyShowHotkeys) }

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
                    text = "Back",
                    onClick = onBack,
                    variant = ButtonVariant.Text,
                    compact = true,
                    modifier = Modifier.testTag(SESSION_BACK_TAG),
                )
            },
            trailing = {
                if (usagePillState != null) {
                    UsageGlancePill(state = usagePillState, onClick = onOpenUsage)
                } else {
                    PocketShellButton(
                        text = "Usage",
                        onClick = onOpenUsage,
                        variant = ButtonVariant.Text,
                        compact = true,
                        modifier = Modifier.testTag(SESSION_USAGE_TAG),
                    )
                }
            },
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(PocketShellColors.Background)
                .onSizeChanged { size ->
                    if (state !is SessionUiState.Live) {
                        terminalCells(size.width, size.height, cellMetrics)?.let { cells ->
                            onResized(cells.cols, cells.rows)
                        }
                    }
                },
        ) {
            when (state) {
                SessionUiState.Connecting -> EmptyState(
                    title = "Attaching…",
                    description = "Opening a terminal on \"$sessionName\".",
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(SESSION_CONNECTING_TAG),
                )

                is SessionUiState.Live -> TerminalHostView(
                    session = state.terminal,
                    onResized = onResized,
                    modifier = Modifier.fillMaxSize(),
                )

                is SessionUiState.Reconnecting -> Column(modifier = Modifier.fillMaxSize()) {
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
                    Terminal(
                        session = state.terminal,
                        onResized = onResized,
                        modifier = Modifier.weight(1f),
                    )
                }

                is SessionUiState.Failed -> Column(modifier = Modifier.fillMaxSize()) {
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

        SessionLauncherBar(
            onOpenComposer = {
                hotkeysOpen = false
                composerOpen = true
            },
            onOpenHotkeys = if (state is SessionUiState.Failed) {
                null
            } else {
                {
                    composerOpen = false
                    hotkeysOpen = true
                }
            },
        )
    }

    if (composerOpen) {
        val sendAndMaybeDismiss: () -> Unit = {
            if (onSend()) composerOpen = false
        }
        val dismiss = {
            onCancelRecording()
            composerOpen = false
        }
        if (embedComposerInWindow) {
            PromptComposerSheet(
                state = composerState,
                onDismiss = dismiss,
                onDraftChange = onDraftChange,
                onSend = sendAndMaybeDismiss,
                onInsert = onInsert,
                onAttach = onAttach,
                onMicTap = onMicTap,
                onCancelRecording = onCancelRecording,
                onToggleHistory = onToggleHistory,
                onTogglePreview = onTogglePreview,
                onRemoveAttachment = onRemoveAttachment,
                onDismissNotice = onDismissNotice,
                onDiscard = onDiscardDraft,
                onPermissionDenied = onPermissionDenied,
            )
        } else {
            PromptComposerContent(
                state = composerState,
                onClose = dismiss,
                onDraftChange = onDraftChange,
                onSend = sendAndMaybeDismiss,
                onInsert = onInsert,
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
    }

    if (hotkeysOpen) {
        TerminalHotkeysSheet(
            onKey = { binding: KeyBinding ->
                keyBarBytes(binding.label)?.let(onHotkeySend)
            },
            onDismiss = { hotkeysOpen = false },
            enabled = state is SessionUiState.Live,
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

@Composable
private fun Terminal(
    session: TerminalSession,
    onResized: (cols: Int, rows: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PocketShellColors.Background),
    ) {
        TerminalHostView(
            session = session,
            onResized = onResized,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun reconnectingMessage(state: SessionUiState.Reconnecting): String {
    val attempt = state.attempt + 1
    if (state.retryInMs <= 0) return "Reconnecting… attempt $attempt · retrying now"
    val seconds = (state.retryInMs + 999) / 1000
    return "Reconnecting… attempt $attempt · retrying in ${seconds}s"
}

private fun statusLine(state: SessionUiState): String = when (state) {
    SessionUiState.Connecting -> "attaching"
    is SessionUiState.Live -> "attached"
    is SessionUiState.Reconnecting -> "reconnecting"
    is SessionUiState.Failed -> "not attached"
}
