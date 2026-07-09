package com.pocketshell.app.tmux

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow

@Composable
internal fun TmuxUnifiedPagerSettleEffects(
    pagerState: PagerState,
    unifiedPanes: List<TmuxPaneState>,
    sessionName: String,
    terminalHeld: Boolean,
    viewModel: TmuxSessionViewModel,
) {
    // Issue #652 (epic #636): the pager remembers its page index across a
    // session switch, but `unifiedPanes` reorders when the active session
    // changes (active session always heads the list — see
    // [TmuxSessionViewModel.rebuildUnifiedPanes]). A deliberate tap on session A
    // makes A the nav target + active session; until the pager re-aligns to A's
    // first page, a stale settled index can resolve to a DIFFERENT session and
    // (pre-fix) auto-fire `onReplaceTmuxSession(thatOther)`, yanking the user
    // into the wrong project and routing their next prompt there. We track
    // whether the pager has realigned to the current nav target and suppress
    // settle-driven switches until it has, so the explicit tap always wins.
    var pagerAlignedSession by remember { mutableStateOf<String?>(null) }
    // Issue #634: did the user physically DRAG the pager since it last aligned
    // to the current nav target? Only a real drag makes a cross-session settle
    // a genuine swipe; the app's own realignment scroll and any stale-index
    // recomposition echo (which is what bled session C back into A on the
    // return-to-origin switch) never raise a drag interaction. Reset to false
    // every time the pager (re)aligns to the nav target, so each deliberate
    // switch starts from a clean "no swipe yet" state.
    var userDraggedSinceAlignment by remember { mutableStateOf(false) }
    LaunchedEffect(pagerState) {
        pagerState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                userDraggedSinceAlignment = true
            }
        }
    }
    // A new nav target invalidates the previous alignment immediately (before
    // the list even rebuilds) so the settle collector below suppresses any
    // stale-index event from the moment the tap is observed.
    LaunchedEffect(sessionName) {
        if (pagerAlignedSession != sessionName) {
            pagerAlignedSession = null
            // A deliberate switch is starting; any drag from before it must
            // not count toward the new session's swipe detection.
            userDraggedSinceAlignment = false
        }
    }
    LaunchedEffect(sessionName, unifiedPanes) {
        // Snap the pager to the nav target's first page. The active session
        // always heads `unifiedPanes` (see
        // [TmuxSessionViewModel.rebuildUnifiedPanes]), so for a freshly-opened
        // session this is page 0. We keep re-snapping until the page the pager
        // actually sits on resolves to the nav-target session — that is the
        // signal the pager has caught up with the deliberate choice, at which
        // point cross-session swipe detection is safe to re-arm.
        val targetPage = unifiedPanes.indexOfFirst {
            viewModel.sessionNameForUnifiedPane(it) == sessionName
        }
        if (targetPage < 0) return@LaunchedEffect
        val currentSession = unifiedPanes.getOrNull(pagerState.currentPage)
            ?.let { viewModel.sessionNameForUnifiedPane(it) }
        if (currentSession != sessionName) {
            pagerState.scrollToPage(targetPage)
        }
    }

    // Issue #661 / #634 / #636: re-arm cross-session swipe detection ONLY once
    // the pager has GENUINELY come to rest on the nav target's page. The old
    // code marked the pager "aligned" the instant the snap effect *issued* a
    // [scrollToPage] — but the scroll had not landed, so
    // [pagerState.settledPage] could still report the previous session's stale
    // page while we had already declared alignment. The settle collector then
    // treated that stale page as a deliberate cross-session swipe and yanked
    // the user back to the session they just left (the wrong/stale-session +
    // content-bleed regression). Driving alignment off the *settled* page,
    // reactively, means a deliberate tap can never be undone by a lagging
    // settle: until the pager actually settles on the target session, settles
    // stay suppressed.
    LaunchedEffect(sessionName, unifiedPanes) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            val settledSession = unifiedPanes.getOrNull(page)
                ?.let { viewModel.sessionNameForUnifiedPane(it) }
            if (settledSession == sessionName) {
                pagerAlignedSession = sessionName
                // Issue #634: whenever the pager comes to rest on the nav
                // target, the user is now sitting on the target's page, so
                // any prior drag is consumed. Clear the drag flag so the NEXT
                // cross-session settle is only honored if it follows a FRESH
                // user drag away from the target. This makes a stale settle
                // echo (no fresh drag) arriving just after the return-to-A
                // alignment impossible to mistake for a swipe back to C.
                userDraggedSinceAlignment = false
            }
        }
    }

    // Issue #626/#652: detect a genuine user-driven cross-session swipe and
    // notify the ViewModel so it can emit sessionSwitchRequest. Settles that
    // arrive before the pager has realigned to the nav target are stale-index
    // artifacts of a just-completed switch and are ignored.
    //
    // Issue #797: ALSO promote a settled CACHED pane that the user is genuinely
    // parked on even WITHOUT a fresh drag. The drag-gated swipe path
    // ([settleSessionSwitchTarget]) requires a recorded drag to avoid the
    // #661/#634/#636 stale-settle yank, but that left the maintainer's
    // PERSISTENT-STALL hole: a cached pane the user is sitting on (input dead,
    // not detected as an agent, composer follows the cached state) never
    // promotes, so it stays half-attached "until I switch sessions several
    // times". Promotion is what rebinds `clientRef`/`_panes.value` (and thus
    // input + detection) to the visible pane. Safe against stale-bleed because
    // (a) it fires only when the pager is ALIGNED and at rest on a genuinely
    // OTHER (cached) session's pane and (b) the VM side
    // ([onUnifiedPageSettled]) still independently suppresses a promote toward
    // anything other than an in-flight deliberate connect destination
    // (`connectingTarget`/`connectJob`). See
    // [tmuxSessionShouldPromoteSettledCachedPane].
    LaunchedEffect(unifiedPanes, sessionName) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            val aligned = pagerAlignedSession == sessionName
            val settledPane = unifiedPanes.getOrNull(page)
            val settledSession = settledPane
                ?.let { viewModel.sessionNameForUnifiedPane(it) }
            val settledPaneIsActive = settledPane
                ?.let { viewModel.isActiveSessionPane(it) } ?: true
            val switchTo = settleSessionSwitchTarget(
                settledPaneSession = settledSession,
                navTargetSession = sessionName,
                pagerAlignedToNavTarget = aligned,
                userDraggedSinceAlignment = userDraggedSinceAlignment,
            )
            val shouldPromoteStalledCachedPane =
                tmuxSessionShouldPromoteSettledCachedPane(
                    settledPaneSession = settledSession,
                    navTargetSession = sessionName,
                    settledPaneIsActiveSession = settledPaneIsActive,
                    pagerAlignedToNavTarget = aligned,
                    switchHidesTerminal = terminalHeld,
                )
            if (switchTo != null || shouldPromoteStalledCachedPane) {
                viewModel.onUnifiedPageSettled(page)
            }
        }
    }

    // Issue #662: when the user switches windows (the pager settles on a
    // different pane), re-seed that pane from `capture-pane` if its local
    // emulator is rendering BLACK. tmux `-CC` never re-emits an idle window's
    // existing content, so a window whose attach-time seed was missing or wiped
    // would otherwise stay black no matter how many times the user switches to
    // it — exactly the maintainer's "switching Window 1 <-> Window 2 does not
    // recover" report. A no-op when the settled pane already shows content.
    LaunchedEffect(unifiedPanes, sessionName) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            val settledPane = unifiedPanes.getOrNull(page) ?: return@collect
            viewModel.reseedVisiblePaneIfBlank(settledPane.paneId)
        }
    }
}
