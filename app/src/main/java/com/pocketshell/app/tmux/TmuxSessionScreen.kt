package com.pocketshell.app.tmux

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketshell.app.tmux.TmuxSessionViewModel.ConnectionStatus
import com.pocketshell.core.terminal.ui.TerminalSurface
import com.pocketshell.uikit.components.Breadcrumb
import com.pocketshell.uikit.components.KeyBar
import com.pocketshell.uikit.model.Crumb
import com.pocketshell.uikit.model.KeyBinding
import com.pocketshell.uikit.model.KeyKind
import com.pocketshell.uikit.theme.PocketShellColors

/**
 * Phase 2 session screen for `tmux -CC` hosts — the per-pane equivalent of
 * [com.pocketshell.app.session.SessionScreen] for plain SSH.
 *
 * Per [D6](../../../../../../../../docs/decisions.md), exactly ONE pane is
 * rendered at a time, wrapped in a [HorizontalPager] so a horizontal swipe
 * navigates left/right between panes inside the current window. No tiled
 * rendering — tmux's native split layout is unreadable at phone scale.
 *
 * Stacks four bands top-to-bottom, mirroring the structure of
 * [com.pocketshell.app.session.SessionScreen]:
 *
 * 1. **Breadcrumb** — `host › session › window › pane`. Back arrow
 *    "detaches" (i.e. tears down the [TmuxSessionViewModel]).
 * 2. **Status line** — surfaces `Connecting`/`Failed` until the breadcrumb
 *    live dot is wired up post-#18 patterns.
 * 3. **[HorizontalPager]** of [TerminalSurface]s — one page per pane in
 *    the current window order.
 * 4. **[KeyBar]** above the keyboard. Taps route to the currently visible
 *    pane via [TmuxSessionViewModel.onKeyBarKey].
 *
 * The screen does not own any business state — the panes list, the active
 * tmux client, and the per-pane terminal state holders all live in the
 * view model.
 */
@Composable
public fun TmuxSessionScreen(
    viewModel: TmuxSessionViewModel,
    hostId: Long,
    hostName: String,
    host: String,
    port: Int,
    user: String,
    keyPath: String,
    sessionName: String,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
) {
    LaunchedEffect(hostId, hostName, host, port, user, keyPath, sessionName) {
        viewModel.connect(hostId, hostName, host, port, user, keyPath, sessionName)
    }

    val panes by viewModel.panes.collectAsState()
    val status by viewModel.connectionStatus.collectAsState()

    val pagerState = rememberPagerState(pageCount = { panes.size })

    val isImeVisible = WindowInsets.ime.getBottom(
        androidx.compose.ui.platform.LocalDensity.current,
    ) > 0

    val currentPane = panes.getOrNull(pagerState.currentPage)
    val crumbs = remember(host, sessionName, currentPane) {
        breadcrumbCrumbs(host, sessionName, currentPane)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(color = PocketShellColors.Background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
        ) {
            Breadcrumb(
                crumbs = crumbs,
                onBack = onBack,
                onMore = { /* More menu — wiring lands with #48 / #23. */ },
            )

            (status as? ConnectionStatus.Connecting)?.let {
                StatusLine("connecting to ${it.user}@${it.host}:${it.port} (tmux $sessionName)")
            }
            (status as? ConnectionStatus.Failed)?.let {
                StatusLine(it.message)
            }

            // Per [D6]: render exactly one pane at a time. The
            // HorizontalPager renders only the visible page eagerly by
            // default; sibling panes are pre-loaded into the off-screen
            // pages but kept lightweight because each TerminalSurface
            // owns its own (already-attached) TerminalSurfaceState.
            Box(modifier = Modifier.weight(1f)) {
                if (panes.isEmpty()) {
                    EmptyPanesPlaceholder()
                } else {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                    ) { pageIndex ->
                        val pane = panes[pageIndex]
                        TerminalSurface(
                            state = pane.terminalState,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            if (isImeVisible && currentPane != null) {
                KeyBar(
                    keys = KeyBarLayout,
                    onKey = { binding ->
                        viewModel.onKeyBarKey(currentPane.paneId, binding.label)
                    },
                )
            }

            // Pane pager dot indicator — a thin row of dots so the user
            // knows which pane is showing and how many siblings exist.
            // We only render when there's >1 pane; the indicator is
            // redundant in the single-pane case (which is the common one
            // for a freshly-attached session).
            if (panes.size > 1) {
                PageIndicator(
                    pageCount = panes.size,
                    currentPage = pagerState.currentPage,
                )
            }
        }
    }
}

/**
 * Build the breadcrumb segments. With tmux we have a real four-level
 * chain (host → session → window → pane), so we surface all four — the
 * window crumb is keyed off [TmuxPaneState.windowId] for now (e.g.
 * "@0"); a follow-up issue (#47) replaces the bare ID with the
 * window name once we cache the `%window-renamed` payload.
 */
private fun breadcrumbCrumbs(
    host: String,
    sessionName: String,
    currentPane: TmuxPaneState?,
): List<Crumb> {
    val windowLabel = currentPane?.windowId ?: "—"
    val paneLabel = currentPane?.let { pane ->
        if (pane.title.isBlank()) pane.paneId else pane.title
    } ?: "—"
    return listOf(
        Crumb(label = host, isCurrent = false, onClick = { /* host root — #18 */ }),
        Crumb(label = sessionName, isCurrent = false, onClick = { /* session switcher — #47 */ }),
        Crumb(label = windowLabel, isCurrent = false, onClick = { /* window switcher — #47 */ }),
        Crumb(label = paneLabel, isCurrent = true, onClick = { /* current pane — no-op */ }),
    )
}

@Composable
private fun StatusLine(text: String) {
    Text(
        text = text,
        color = PocketShellColors.TextSecondary,
        fontSize = 12.sp,
        modifier = Modifier
            .fillMaxWidth()
            .background(color = PocketShellColors.Surface)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun EmptyPanesPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(color = PocketShellColors.Surface),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "waiting for tmux panes…",
            color = PocketShellColors.TextSecondary,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun PageIndicator(pageCount: Int, currentPage: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = PocketShellColors.Surface)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        for (i in 0 until pageCount) {
            val color = if (i == currentPage) {
                PocketShellColors.Accent
            } else {
                PocketShellColors.TextSecondary
            }
            Box(
                modifier = Modifier
                    .padding(horizontal = 3.dp)
                    .size(6.dp)
                    .background(color = color),
            )
        }
    }
}

/**
 * Same 8 bar slots as [com.pocketshell.app.session.SessionScreen]'s
 * `KeyBarLayout`. Re-declared here (rather than reaching across packages)
 * so the two screens can evolve independently — tmux pane input has
 * stricter wire encoding (`send-keys -t %N Escape`) than the
 * Ctrl-as-sticky-modifier dance the plain-SSH screen runs.
 */
private val KeyBarLayout: List<KeyBinding> = listOf(
    KeyBinding(label = "Esc", kind = KeyKind.Regular),
    KeyBinding(label = "Tab", kind = KeyKind.Regular),
    KeyBinding(label = "Ctrl", kind = KeyKind.Modifier),
    KeyBinding(label = "Alt", kind = KeyKind.Modifier),
    KeyBinding(label = "‹", kind = KeyKind.Arrow),
    KeyBinding(label = "⌃", kind = KeyKind.Arrow),
    KeyBinding(label = "⌄", kind = KeyKind.Arrow),
    KeyBinding(label = "›", kind = KeyKind.Arrow),
)
