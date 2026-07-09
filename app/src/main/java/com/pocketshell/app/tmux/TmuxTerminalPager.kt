package com.pocketshell.app.tmux

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketshell.core.terminal.ui.TerminalKeyboardMode
import com.pocketshell.core.terminal.ui.TerminalSurface
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellType

private val TerminalSurfaceErrorTitleSize = 15.sp

/**
 * Issue #626: thin horizontal divider + session name label shown above the
 * first pane that belongs to a different tmux session than the active one.
 * Provides a visual boundary marker in the unified cross-session pager.
 */
@Composable
private fun SessionBoundaryDivider(sessionName: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = PocketShellColors.Surface)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        HorizontalDivider(
            color = PocketShellColors.Border,
            thickness = 1.dp,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Text(
            text = sessionName,
            color = PocketShellColors.TextSecondary,
            style = PocketShellType.bodyDense,
        )
    }
}

/**
 * Issue #796 (H3) — the terminal-render [HorizontalPager], hoisted out of the
 * [TmuxSessionScreen] body into its OWN restart group so that toggling an
 * overlay-visibility flag in the body (opening the Prompt Composer flips
 * `showMicSheet`; the snippet picker, dialogs, the kebab menu, etc.) does NOT
 * recompose the heavy terminal subtree.
 *
 * ## Why this is the H3 fix the maintainer's repro needs
 *
 * The maintainer pinpointed the freeze: a Codex pane streaming a `%output` burst
 * + tapping the composer launcher = ANR. Tapping the launcher flips `showMicSheet`,
 * which is read in the `TmuxSessionScreen` body root group. So the whole body
 * re-executes. In the OLD inline pager, the `TerminalSurface` callbacks
 * (`onTerminalSizeChanged`, `onLocalTerminalError`, `onUrlTap`, `onFilePathTap`,
 * `onEngineCommandTap`) were allocated FRESH on every body recomposition, which
 * made `TerminalSurface` un-skippable — so the composer-open body recomposition
 * dragged `TerminalSurface` (its `AndroidView` update + the per-render viewport
 * URL / file-path / engine-command scanners, ALL main-thread) through a
 * recomposition. Stacked on the in-flight `%output` burst, the main thread blocks
 * past the ANR threshold.
 *
 * `aff7ac45` (H4) decoupled only the IME-INSET frame burst (deferred-read of the
 * pan offset). It did NOT stop the composer-open `showMicSheet` body recomposition
 * from re-running the inline pager.
 *
 * ## How extracting it fixes it (Compose recomposition scoping)
 *
 * A child `@Composable` is its own restart group: it is SKIPPED when all its
 * arguments compare equal to the previous composition. Every parameter here is
 * stable — the [TmuxPaneState] data classes, the value types, and the callbacks
 * which the caller builds ONCE via `remember` (see the `stable*` lambdas in
 * [TmuxSessionScreen]). So when only an overlay-visibility flag toggles in the
 * parent body, none of this composable's inputs change → it is skipped → ZERO
 * main-thread terminal recomposition work while the composer opens over a
 * bursting pane. (D22 hard-cut: the old inline fresh-lambda pager is deleted, not
 * kept as a fallback.)
 *
 * [TmuxTerminalPagerRecompositionProbe.Record] is invoked here so the #796 H3
 * regression proof can assert this subtree recomposes O(1), not once per overlay
 * toggle.
 */
@Composable
internal fun TmuxTerminalPager(
    unifiedPanes: List<TmuxPaneState>,
    pagerState: PagerState,
    sessionName: String,
    terminalKeyboardMode: TerminalKeyboardMode,
    engineCommands: Set<String>,
    // Issue #796 (REOPENED): when this pane is an interactive-agent pane (the
    // #679 session-tree agentKind signal, same source that drives the agent
    // chips / Conversation default), the raw Terminal surface does NOT run the
    // four per-frame full-viewport affordance scanners (URL / smart-match /
    // file-path / engine-command). Those re-extract every visible row and run
    // regex passes on the MAIN thread every frame; during a live Codex `%output`
    // burst with the keyboard up that per-frame cost stalled the main thread into
    // a real ANR. The agent affordances live in the Conversation view (#809/#818,
    // the default agent view), so the raw Terminal tab is render-only for an agent
    // pane. A shell / non-agent pane keeps full tappability (the flag is false).
    isAgentPane: Boolean,
    sessionNameForUnifiedPane: (TmuxPaneState) -> String?,
    onTerminalSizeChanged: (columns: Int, rows: Int) -> Unit,
    onSurfaceError: (paneId: String, cause: Throwable) -> Unit,
    onRecreateSurface: (paneId: String) -> Unit,
    onUrlTap: (String) -> Unit,
    onFilePathTap: (path: String, paneCwd: String) -> Unit,
    onEngineCommandTap: (String) -> Unit,
) {
    // Issue #796 (H3): observe recompositions of THIS hoisted terminal-render
    // subtree so the regression proof can assert an overlay-visibility toggle
    // (composer open) does not recompose it. Pure counter, no behaviour change.
    TmuxTerminalPagerRecompositionProbe.Record()
    HorizontalPager(
        state = pagerState,
        key = { pageIndex -> unifiedPanes[pageIndex].paneId },
        modifier = Modifier.fillMaxSize(),
    ) { pageIndex ->
        val pane = unifiedPanes[pageIndex]
        // Issue #626: compute session boundary per-page.
        val paneSession = sessionNameForUnifiedPane(pane)
        // EPIC #687 P1 (#686/#658): the rendered screen is keyed
        // STRICTLY to the target session id — a pane belonging to ANY
        // non-target session must never paint its terminal surface OR
        // its `SessionBoundaryDivider` (the stray mid-pane label bearing
        // the leaving session's name was the maintainer's
        // wrong-session-on-switch symptom). Render the loading
        // placeholder for a non-target pane instead, so a late frame
        // from the previous session can never bleed into the shown pane.
        val paneIsForTarget = paneSession == null ||
            paneSession == sessionName
        if (!paneIsForTarget) {
            SwitchingLoadingPlaceholder()
            return@HorizontalPager
        }
        val prevSession = unifiedPanes.getOrNull(pageIndex - 1)
            ?.let { sessionNameForUnifiedPane(it) }
        val boundarySession = paneSession
            ?.takeIf { it != prevSession && it != sessionName }
        // Issue #626: session boundary marker above the
        // terminal surface for the first pane of a different
        // session.
        if (boundarySession != null) {
            SessionBoundaryDivider(sessionName = boundarySession)
        }
        if (pane.surfaceError) {
            // Issue #423: the local terminal surface kept
            // failing (IME/resize/render recovery storm) but
            // SSH/tmux is still alive. Render an actionable
            // error state instead of an indefinite reconnect
            // loop or a frozen, redrawing terminal. The
            // recreate control rebuilds the surface and
            // reattaches to the live tmux pane.
            TerminalSurfaceErrorState(
                onRecreate = { onRecreateSurface(pane.paneId) },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 2.dp, vertical = 4.dp),
            )
        } else {
            TerminalSurface(
                state = pane.terminalState,
                terminalKeyboardMode = terminalKeyboardMode,
                // Issue #240: cache the phone grid so the
                // view model can compare it with tmux's
                // current window size and offer an explicit
                // Resize prompt instead of resizing
                // automatically on attach.
                onTerminalSizeChanged = onTerminalSizeChanged,
                onLocalTerminalError = { cause ->
                    onSurfaceError(pane.paneId, cause)
                },
                // Issue #488: a tapped URL is routed through the
                // shared [onUrlTap] so server-local
                // (loopback) links go through the port-forward
                // flow instead of a dead browser open, while a
                // real-host URL opens in the browser.
                onUrlTap = onUrlTap,
                // Issue #500: detect file paths the agent
                // emits in the terminal and make them tappable
                // → open in the in-app file viewer (#497). The
                // pane's cwd resolves project-relative paths
                // (`out/report.png`) server-side in the viewer.
                onFilePathTap = { path ->
                    onFilePathTap(path, pane.cwd)
                },
                // Issue #770: engine slash-commands the agent
                // rendered (e.g. Claude Code's `/clear`) become
                // tappable. Tapping one pre-fills the prompt
                // composer with it (caret ready, leading slash
                // token) and opens the composer so the user
                // reviews + taps Send — instead of nothing
                // happening. The command set is the catalog for
                // the visible pane's detected engine; a shell
                // pane has an empty set and the affordance is off.
                engineCommands = engineCommands,
                onEngineCommandTap = onEngineCommandTap,
                // Issue #796 (REOPENED): an agent pane runs NO per-frame ON-MAIN
                // viewport affordance scanners — that per-frame regex cost was the
                // Codex `%output` keyboard-up ANR. Shell / non-agent panes keep the
                // full on-main scanners (URL + path + match + command).
                affordanceScannersEnabled = !isAgentPane,
                // Issue #871: an agent pane STILL gets tappable file paths + URLs —
                // agents emit file paths constantly and the maintainer taps them to
                // open the file viewer. But via the OFF-main, debounced overlay
                // (`AgentPaneAffordanceOverlay`), NOT the per-frame on-main scan, so
                // the #803/#866 ANR is not reintroduced. The #796 over-correction
                // had removed this affordance entirely from agent panes.
                agentPaneLinkAffordancesEnabled = isAgentPane,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 2.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * Issue #423: actionable terminal-surface error state. Shown when the local
 * Termux surface for the focused pane fails to recover (an IME/resize/render
 * recovery storm) while the SSH/tmux transport is still alive. The user taps
 * "Recreate terminal" to rebuild the surface and reattach to the live tmux
 * pane — no SSH reconnect, no force-restart. The rest of the app stays
 * navigable because only this pane's surface is affected.
 */
@Composable
private fun TerminalSurfaceErrorState(
    onRecreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(color = PocketShellColors.Surface)
            .testTag(TMUX_TERMINAL_SURFACE_ERROR_TAG),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            Text(
                text = "Terminal display stopped responding",
                color = PocketShellColors.Text,
                fontSize = TerminalSurfaceErrorTitleSize,
            )
            Text(
                text = "The connection is still active. Recreate the terminal to " +
                    "keep working in this tmux session.",
                color = PocketShellColors.TextSecondary,
                style = PocketShellType.bodyDense,
            )
            PocketShellButton(
                text = "Recreate terminal",
                onClick = onRecreate,
                variant = ButtonVariant.Text,
                modifier = Modifier.testTag(TMUX_TERMINAL_SURFACE_RECREATE_TAG),
            )
        }
    }
}
