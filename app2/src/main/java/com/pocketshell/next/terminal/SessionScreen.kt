package com.pocketshell.next.terminal

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
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
import com.pocketshell.next.composer.ComposerBar
import com.pocketshell.next.composer.ComposerUiState
import com.pocketshell.next.composer.ComposerViewModel
import com.pocketshell.next.composer.MessageHistorySheet
import com.pocketshell.next.composer.SentMessage
import com.pocketshell.next.composer.SessionSink
import com.pocketshell.next.usage.UsageGlancePill
import com.pocketshell.next.usage.UsageGlancePillState
import com.pocketshell.next.usage.UsageGlanceViewModel
import com.pocketshell.uikit.components.Banner
import com.pocketshell.uikit.components.BannerRole
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.EmptyState
import com.pocketshell.uikit.components.KeyBar
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.model.KeyBinding
import com.pocketshell.uikit.model.KeyKind
import com.pocketshell.uikit.model.KeyModifierState
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
const val SESSION_KEY_BAR_TAG: String = "session-key-bar"

/**
 * The key bar's slots (rewrite task U-5).
 *
 * Four, not D18's eight. The maintainer cut the scope on 2026-09-03 — "I need
 * Ctrl+C, Ctrl+D, Escape, Enter, this kind of thing; most of the shortcuts I
 * don't really use" — so the arrows and Alt are gone and the bar carries only
 * what a phone keyboard cannot produce at all. Tab is in despite not being
 * named because shell completion is near-universal and no soft keyboard sends
 * it into a terminal.
 *
 * Ctrl is the one modifier, and it is deliberately first: it is the slot the
 * user reaches for before typing a letter, and a thumb travels left to right.
 */
val TERMINAL_KEY_BAR_KEYS: List<KeyBinding> = listOf(
    KeyBinding(label = KEY_LABEL_CTRL, kind = KeyKind.Modifier),
    KeyBinding(label = KEY_LABEL_ESC, kind = KeyKind.Regular),
    KeyBinding(label = KEY_LABEL_TAB, kind = KeyKind.Regular),
    KeyBinding(label = KEY_LABEL_ENTER, kind = KeyKind.Regular),
)

/**
 * Route-level entry point for `session/{hostId}/{sessionName}` (rewrite tasks
 * U-4, U-5, U-7 and P-1).
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
 * The key bar's raw control bytes (Ctrl+C, Esc, Tab, Enter) go STRAIGHT to
 * [SessionViewModel.sendBytes] — they are not composed messages and have no
 * business in the composer's draft/history/attachment machinery.
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
    // The pill does its own foreground-only fetch (task P-5): no scheduler, no
    // cache shared with the usage panel — a session open is itself a "view",
    // same as opening the panel is.
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

    // Task P-2: the composer becoming visible again is the trigger for
    // delivering anything dictated while the device was offline (the subway
    // case) — see `ComposerViewModel.onForegroundResume`'s KDoc. `ON_START`
    // rather than `ON_RESUME`: it also fires on the very first composition,
    // which is exactly when a queued dictation from the last session should
    // surface.
    LifecycleEventEffect(Lifecycle.Event.ON_START) { composerViewModel.onForegroundResume() }

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
        usagePillState = usagePillState,
        onOpenUsage = onOpenUsage,
        onResized = viewModel::onResized,
        onRetry = viewModel::retryNow,
        onKeyBarSend = viewModel::sendBytes,
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
 * One attached session: a title bar, a full-bleed terminal, the key bar, and
 * the composer.
 *
 * ## Chrome, and what is still absent
 *
 * A name, a way back, the terminal, four keys, and one composer. No
 * conversation tab, no hotkey panel, no scroll affordances — every one of
 * those is a cut task, and each is a place the pre-rewrite screen accumulated
 * state that had to agree with the terminal's. The one thing the chrome does
 * say is WHICH session, because the tree can show several with similar names.
 *
 * ## The keyboard shrinks the terminal, it does not cover it
 *
 * [imePadding] on the whole column is what makes `stty size` on the remote
 * track the keyboard: the column shrinks, so the terminal slot shrinks, so the
 * vendored view relayouts and reports its new cell count through [onResized]
 * — the single path to `pty.resize`. The composer is the bottom-most child, so
 * it rides up with the IME too. That is why the composer is inline rather than
 * a modal sheet: a sheet floats in its own window and has to be re-anchored
 * against the IME by hand, which is 289 lines of machinery the old client
 * carried and this screen does not need. The same mechanism covers rotation,
 * which reaches the view as an ordinary layout-size change.
 *
 * ## The key bar is up before the terminal is
 *
 * It renders for `Connecting` and `Reconnecting` as well as `Live` so the
 * terminal slot has the SAME height throughout — the pre-attach size estimate
 * below would otherwise measure a slot two rows taller than the one the
 * terminal eventually gets, and the remote would be resized twice on every
 * attach. It is hidden on `Failed`, where there is nothing to send to.
 *
 * ## Reconnecting keeps the terminal — and the composer stays mounted through
 * a failure, on purpose
 *
 * [SessionUiState.Reconnecting] renders the SAME terminal surface as
 * [SessionUiState.Live], with a banner above it — the emulator is simply not
 * being fed, and tmux repaints it on reattach. The composer never unmounts,
 * not even on [SessionUiState.Failed]: that is the state in which a draft is
 * most valuable, and hiding the composer would delete the one thing the send
 * contract promises to keep.
 *
 * ## A failure offers Retry
 *
 * The ladder gives up eventually (task U-7), and the user gets the manual
 * retry the give-up message names, next to the way back.
 *
 * @param onResized the terminal's size in character cells. Called by the
 *   vendored view once it exists, and — before it does — by the pre-attach
 *   estimate, so the PTY opens at the phone's real geometry instead of 80x24.
 * @param onRetry manual retry after the reconnect ladder gives up (task U-7).
 * @param onKeyBarSend raw bytes for the remote: key bar taps, and Ctrl+key
 *   combinations the key bar armed and the keyboard completed. Bypasses the
 *   composer entirely — these are not composed messages.
 * @param usagePillState the top bar's usage glance pill (task P-5), or null
 *   before its first foreground fetch has landed — the pill is simply absent
 *   until then, never a placeholder.
 * @param onOpenUsage navigates to the usage panel; the pill's tap target.
 * @param cellMetrics the rendered face's cell metrics, used only for the
 *   pre-attach estimate. A seam with the production default, like
 *   [com.pocketshell.next.AppNavHost]'s screens: Robolectric's `Paint` reports
 *   a 1 px glyph with no ascent, so a host-JVM test needs real numbers to see
 *   this path run.
 */
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
    onKeyBarSend: (ByteArray) -> Unit,
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
    cellMetrics: TerminalCellMetrics = rememberTerminalCellMetrics(),
) {
    // The bar's sticky state is mirrored here rather than left inside `KeyBar`
    // because two things need it: the bar itself (for the armed highlight) and
    // the terminal view (to encode the next typed letter). `KeyBar` prefers a
    // caller-supplied map over its own, so this is the single source.
    var ctrl by remember { mutableStateOf(KeyModifierState.Off) }

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
            // The usage glance pill (task P-5) rides the top bar next to the
            // session title, always visible once a reading exists — hidden
            // (not a placeholder) until the first foreground fetch lands.
            trailing = usagePillState?.let { pillState ->
                { UsageGlancePill(state = pillState, onClick = onOpenUsage) }
            },
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                // The terminal renders its own background per cell, but the
                // view can be laid out taller than the grid it has drawn so
                // far; without this the gap flashes the theme surface.
                .background(PocketShellColors.Background)
                .onSizeChanged { size ->
                    // Only until the terminal exists. From then on the view
                    // owns the number — it has the renderer, so it has the
                    // authoritative metrics — and a second reporter would fight
                    // it on every layout pass.
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
                    ctrlArmed = ctrl != KeyModifierState.Off,
                    onControlBytes = { bytes ->
                        onKeyBarSend(bytes)
                        // The key that consumed the modifier came from the
                        // keyboard, not from the bar, so the bar's own
                        // auto-clear never fires. A LOCKED Ctrl survives, which
                        // is the point of locking it.
                        if (ctrl == KeyModifierState.OneShot) ctrl = KeyModifierState.Off
                    },
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
                    // The last frame stays exactly where it was, under the banner.
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

        if (state !is SessionUiState.Failed) {
            TerminalKeyBar(
                ctrl = ctrl,
                onCtrlChange = { ctrl = it },
                onSend = onKeyBarSend,
            )
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
 * The ui-kit [KeyBar], wired to the wire.
 *
 * The component owns the tap gestures and the sticky one-shot/lock state
 * machine; this owns the semantics — which bytes a slot stands for, and where
 * they go. Modifier taps never reach [onSend]: `KeyBar` reports them through
 * `onModifierStateChange` only, which is what makes "arm Ctrl, then type a
 * letter on the keyboard" possible at all.
 */
@Composable
private fun TerminalKeyBar(
    ctrl: KeyModifierState,
    onCtrlChange: (KeyModifierState) -> Unit,
    onSend: (ByteArray) -> Unit,
) {
    KeyBar(
        keys = TERMINAL_KEY_BAR_KEYS,
        onKey = { binding ->
            keyBarBytes(binding.label, ctrlArmed = ctrl != KeyModifierState.Off)?.let(onSend)
        },
        modifierStates = mapOf(KEY_LABEL_CTRL to ctrl),
        onModifierStateChange = { binding, next ->
            if (binding.label == KEY_LABEL_CTRL) onCtrlChange(next)
        },
        modifier = Modifier.testTag(SESSION_KEY_BAR_TAG),
    )
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
