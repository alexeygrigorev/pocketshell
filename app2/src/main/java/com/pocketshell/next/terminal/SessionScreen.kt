package com.pocketshell.next.terminal

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

/** Stable test tags for the session screen's own chrome. */
const val SESSION_SCREEN_TAG: String = "session-screen"
const val SESSION_TITLE_TAG: String = "session-title"
const val SESSION_CONNECTING_TAG: String = "session-connecting"
const val SESSION_ERROR_BANNER_TAG: String = "session-error-banner"
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
        onSend = viewModel::sendBytes,
        modifier = modifier,
    )
}

/**
 * One attached session: a title bar, a full-bleed terminal, and the key bar.
 *
 * ## Deliberately almost no chrome
 *
 * A name, a way back, the terminal, and four keys. No composer, no conversation
 * tab, no hotkey panel, no scroll affordances — every one of those is a later
 * task or a cut one, and each is a place the pre-rewrite screen accumulated
 * state that had to agree with the terminal's. The one thing the chrome does
 * say is WHICH session, because the tree can show several with similar names.
 *
 * ## The keyboard shrinks the terminal, it does not cover it
 *
 * [Modifier.imePadding] on the whole column is what makes `stty size` on the
 * remote track the keyboard: the column shrinks, so the terminal slot shrinks,
 * so the vendored view relayouts and reports its new cell count through
 * [onResized] — the single path to `pty.resize`. A keyboard that merely floated
 * over the terminal would leave the remote wrapping at rows the user cannot
 * see, which is exactly the "the last line is under the keyboard" complaint.
 * The same mechanism covers rotation, which reaches the view as an ordinary
 * layout-size change.
 *
 * ## The key bar is up before the terminal is
 *
 * It renders for `Connecting` as well as `Live` so the terminal slot has the
 * SAME height in both — the pre-attach size estimate below would otherwise
 * measure a slot two rows taller than the one the terminal eventually gets,
 * and the remote would be resized twice on every attach. It is hidden on
 * `Failed`, where there is nothing to send to.
 *
 * ## Failure has no retry button
 *
 * A dropped or ended session shows what happened and offers Back. Reconnect is
 * task U-7, and a Retry that silently re-ran `open()` without the backoff and
 * attempt accounting that task owns would be the thing the rewrite is trying
 * not to build again.
 *
 * @param onResized the terminal's size in character cells. Called by the
 *   vendored view once it exists, and — before it does — by the pre-attach
 *   estimate, so the PTY opens at the phone's real geometry instead of 80x24.
 * @param onSend raw bytes for the remote: key bar taps, and Ctrl+key
 *   combinations the key bar armed and the keyboard completed.
 * @param cellMetrics the rendered face's cell metrics, used only for the
 *   pre-attach estimate. A seam with the production default, like
 *   [com.pocketshell.next.AppNavHost]'s screens: Robolectric's `Paint` reports
 *   a 1 px glyph with no ascent, so a host-JVM test needs real numbers to see
 *   this path run.
 */
@Composable
fun SessionScreen(
    state: SessionUiState,
    sessionName: String,
    onBack: () -> Unit,
    onResized: (cols: Int, rows: Int) -> Unit,
    onSend: (ByteArray) -> Unit,
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
                        onSend(bytes)
                        // The key that consumed the modifier came from the
                        // keyboard, not from the bar, so the bar's own
                        // auto-clear never fires. A LOCKED Ctrl survives, which
                        // is the point of locking it.
                        if (ctrl == KeyModifierState.OneShot) ctrl = KeyModifierState.Off
                    },
                    modifier = Modifier.fillMaxSize(),
                )

                is SessionUiState.Failed -> Column(modifier = Modifier.fillMaxSize()) {
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

        if (state !is SessionUiState.Failed) {
            TerminalKeyBar(
                ctrl = ctrl,
                onCtrlChange = { ctrl = it },
                onSend = onSend,
            )
        }
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

private fun statusLine(state: SessionUiState): String = when (state) {
    SessionUiState.Connecting -> "attaching"
    is SessionUiState.Live -> "attached"
    is SessionUiState.Failed -> "not attached"
}
