package com.pocketshell.app.tmux

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketshell.core.connection.ConnectionPhase
import com.pocketshell.core.connection.FailureReason
import com.pocketshell.core.connection.SessionSurfaceState
import com.pocketshell.core.connection.showsCalmFailure
import com.pocketshell.core.connection.terminalHeld
import com.pocketshell.app.tmux.TmuxSessionViewModel.ConnectionStatus
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.LoadingIndicator
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.SpinnerSize
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellType

/**
 * Issue #993: whether the kebab's "Reconnect" item is actionable right now.
 *
 * The manual reconnect escape hatch is the half-measure for a dropped session whose
 * auto-reconnect didn't fire. It is only meaningful when:
 *  - there IS a known target to reconnect to ([canReconnect] — the same gate the
 *    in-session Reconnect band uses, so a tap never silently no-ops), AND
 *  - a connect/reconnect is NOT already in flight (Connecting/Switching/Reconnecting),
 *    so the tap is never a redundant re-dial that would yank an already-recovering
 *    session.
 *
 * A `Connected` (possibly stale) or `Failed`/`Idle` session with a target stays
 * enabled — that is exactly the maintainer's "it dropped but the app still thinks it's
 * connected / it's sitting on Failed and nothing recovers it" case the button exists for.
 * Pure so the wiring is a unit-testable predicate rather than an inline boolean (G9).
 */
internal fun reconnectKebabEnabled(
    canReconnect: Boolean,
    surfaceState: SessionSurfaceState,
): Boolean =
    canReconnect &&
        surfaceState !is SessionSurfaceState.Connecting &&
        surfaceState !is SessionSurfaceState.Attaching &&
        surfaceState !is SessionSurfaceState.Reconnecting

/**
 * Issue #750 (4th occurrence — the beyond-grace RECONNECT path): the single
 * authoritative primary loading surface for the tmux session screen.
 *
 * The maintainer's recurring symptom is TWO loading surfaces at once on a
 * connect/reconnect. The screen has THREE mutually-exclusive primary loading
 * surfaces, and this reducer makes "two of them at once" TYPE-UNREPRESENTABLE —
 * it returns EXACTLY ONE (or [None]):
 *
 *  - [CenteredAttaching] — the centered "Attaching…" [SwitchingLoadingPlaceholder]
 *    hold, painted by the surface whenever the id-keyed [RevealStateMachine] holds
 *    the terminal (the reveal hold == true). This is the CANONICAL loader
 *    per #750's original decision and WINS over any top banner: every in-progress
 *    connect/switch/reattach/reconnect holds the terminal in [RevealState.Seeding],
 *    so the centered hold is the sole loader for all of them.
 *  - [ConnectingBanner] — the top [ConnectingProgressOverlay] ("Connecting to
 *    host…", + slow hint + Cancel). It renders ONLY when the terminal is NOT held
 *    (the rare live-frame-kept Connecting edge, e.g. the #178 dead-session-mid-
 *    switch fallback), so it can never stack on top of the centered hold.
 *  - [ReconnectingBand] — the top [ReconnectingProgressRow] (text + Retry now /
 *    Cancel). Same rule: only when the terminal is NOT held.
 *
 * The 4th recurrence (2026-07-03): a beyond-grace reconnect re-dials through the
 * controller's `Connecting` state, which projects to [ConnectionStatus.Connecting]
 * (the top [ConnectingProgressOverlay] banner). The previous #750 fix gated only
 * the [ReconnectingProgressRow] band on `!terminalHeld` — it never gated
 * the [ConnectingProgressOverlay], so on the reconnect re-dial BOTH the top
 * "Connecting to host…" banner AND the centered "Attaching…" hold rendered at
 * once. Routing BOTH banners through this reducer closes that gap: when the
 * terminal is held (which it always is on a reconnect re-dial), the reducer
 * returns [CenteredAttaching] and BOTH banners are suppressed.
 */
internal enum class PrimaryLoadingSurface {
    /** No primary loading surface (Connected / Idle / Failed steady states). */
    None,

    /** The centered "Attaching…" [SwitchingLoadingPlaceholder] hold. */
    CenteredAttaching,

    /** The top [ConnectingProgressOverlay] "Connecting to host…" banner. */
    ConnectingBanner,

    /** The top [ReconnectingProgressRow] "Reconnecting to host…" band. */
    ReconnectingBand,
}

/**
 * Issue #750: the SINGLE source of truth for which primary loading surface is
 * shown, so the screen can never paint two at once. See [PrimaryLoadingSurface].
 */
internal fun primaryLoadingSurface(
    surfaceState: SessionSurfaceState,
    panesEmpty: Boolean = false,
): PrimaryLoadingSurface = when {
    // Issue #1326: a settled failure ([Gone]/[Failed]) is NOT a loading state —
    // the calm failed placeholder + "Disconnected" pill + "Tap to reconnect" band
    // own it, so NO spinner and NO top banner (killing the #1321 contradiction).
    surfaceState.showsCalmFailure -> PrimaryLoadingSurface.None
    // Any HELD state → the surface paints the centered "Attaching…" hold, the
    // canonical SOLE loader; the top banners are suppressed so the maintainer never
    // sees a top banner AND the centered spinner at once (the #750 stack). The
    // reveal machine is the surface authority, so every in-progress connect / switch
    // / reconnect holds the terminal — the top ConnectingBanner/ReconnectingBand are
    // the (now unreachable) live-frame fallback, never returned here.
    surfaceState.terminalHeld -> PrimaryLoadingSurface.CenteredAttaching
    // Live but the active pane has no content yet — the "waiting for tmux panes…"
    // ring is the sole loader.
    panesEmpty -> PrimaryLoadingSurface.CenteredAttaching
    else -> PrimaryLoadingSurface.None
}

/**
 * Issue #890 / #1322 (screenshot A): whether the VISIBLE bottom "Reconnect" button
 * ([SessionSurfaceReconnectWrapper]) is shown for this status.
 *
 *  - #890: hidden WHILE a connect/attach/reconnect is in progress
 *    (`Connecting`/`Switching`/`Reconnecting`) — the progress indicator already
 *    shows the system is trying, so the button would be redundant chrome.
 *  - #1322: ALSO hidden on `Failed` — the calm [FailedConnectionRow] "Tap to
 *    reconnect" band already offers the SINGLE reconnect affordance for that
 *    state, so showing this button too is the "TWO reconnect controls at once"
 *    duplicate (a regression of #720's single tappable "Tap to reconnect").
 *
 * It therefore renders as the SOLE affordance only on the remaining `Idle`
 * (dropped/never-attached) state, which has no band. Pure (G9).
 */
internal fun surfaceReconnectButtonVisible(surfaceState: SessionSurfaceState): Boolean =
    surfaceState !is SessionSurfaceState.Connecting &&
        surfaceState !is SessionSurfaceState.Attaching &&
        surfaceState !is SessionSurfaceState.Reconnecting &&
        surfaceState !is SessionSurfaceState.Failed &&
        surfaceState !is SessionSurfaceState.Gone

/**
 * Issue #750/#1326: the single-indicator gate for the top under-header
 * [ConnectingProgressOverlay] "Connecting to host…" banner. Derived from the ONE
 * [SessionSurfaceState] so it can never coexist with the centered "Attaching…"
 * hold. The reveal machine is the surface authority, so every in-progress connect
 * holds the terminal and the CENTERED hold is the sole loader — this top banner is
 * the (now unreachable) live-frame fallback and is never shown, but the gate is
 * kept so a future live-frame connect edge cannot be left with zero indicators.
 */
internal fun shouldShowConnectingProgressOverlay(
    surfaceState: SessionSurfaceState,
    surfaceOwnsPrimary: Boolean,
): Boolean =
    !surfaceOwnsPrimary && surfaceState is SessionSurfaceState.Connecting

/**
 * Issue #750/#1326: the single-indicator gate for the top under-header
 * [ReconnectingProgressRow] progress line. Same rule as
 * [shouldShowConnectingProgressOverlay] — derived from the ONE state; the centered
 * "Attaching…" hold is the sole reattach affordance for every real reconnect (the
 * terminal is held), so this top bar is the unreachable live-frame fallback.
 */
internal fun shouldShowReconnectingProgressRow(
    surfaceState: SessionSurfaceState,
    surfaceOwnsPrimary: Boolean,
): Boolean =
    !surfaceOwnsPrimary && surfaceState is SessionSurfaceState.Reconnecting

/**
 * Issue #145: stable test tags for the mid-session SSH disconnect band
 * (root row + the Reconnect button). The connected disconnect+reconnect
 * test asserts both tags are present once the SSH transport drops, and
 * taps [TMUX_SESSION_RECONNECT_TAG] to drive the reconnect.
 */
internal const val TMUX_SESSION_ERROR_TAG = "tmux:session:error"
internal const val TMUX_SESSION_RECONNECT_TAG = "tmux:session:reconnect"

/**
 * Issue #823 (Slice 1): test tag for the pull-to-reconnect [PullToRefreshBox]
 * that wraps the session surface while the session is NOT
 * [ConnectionStatus.Connected]. A pull-down fires the existing
 * [TmuxSessionViewModel.reconnect] entrypoint (no new connection logic).
 */
internal const val TMUX_PULL_TO_RECONNECT_TAG = "tmux:session:pull-to-reconnect"

/**
 * Issue #823: test tag for the visible "Reconnect" button overlaid on the
 * session surface while the session is dropped / Reconnecting (a tappable
 * affordance, so the manual reconnect is not gesture-only). Like the pull
 * gesture, the button calls the EXISTING [TmuxSessionViewModel.reconnect]
 * entrypoint — no new connection logic (D28).
 */
internal const val TMUX_SURFACE_RECONNECT_BUTTON_TAG = "tmux:session:surface-reconnect-button"

/**
 * Issue #823 (Slice 1): wraps the session surface (terminal pager /
 * conversation / placeholders) with a discoverable pull-to-reconnect gesture,
 * mirroring the session-tree's pull-to-refresh affordance
 * ([com.pocketshell.app.projects.FolderListScreen]'s [PullToRefreshBox]).
 *
 * The wrapper is a pure UI affordance over the EXISTING reconnect entrypoint —
 * [onReconnect] is wired by the caller to [TmuxSessionViewModel.reconnect]. It
 * adds NO new connection logic and is NOT a second writer on the reconnect path
 * (D28).
 *
 * The pull gesture is mounted ONLY while [pullToReconnectActive] is true (the
 * session is NOT [ConnectionStatus.Connected] AND there is a reconnect target).
 * On a live session the surface is a gesture-hungry Termux `TerminalView` inside
 * a `HorizontalPager`; wrapping it in a pull-to-refresh would fight scrollback /
 * selection / horizontal-paging gestures, so when not active the content renders
 * bare with no pull wrapper at all.
 *
 * [isReconnecting] drives the [PullToRefreshBox] spinner so an in-flight
 * reconnect (or the auto-reconnect ladder) is visible after the pull.
 *
 * Issue #750 (3rd occurrence): the surface content ITSELF renders a centered
 * loading indicator during the attach/reattach hold (the "Attaching…"
 * [SwitchingLoadingPlaceholder], driven by the reveal hold). When that
 * overlay is up, the [PullToRefreshBox]'s own circular indicator would stack a
 * SECOND spinner on top of it (the maintainer's reported cyan ring + gray
 * spinner-in-a-chip). [surfaceShowsCenteredLoader] tells the wrapper the surface
 * is already showing the sole indicator, so the box renders with `isRefreshing =
 * false` — the pull GESTURE stays mounted (pull-to-reconnect still fires
 * [onReconnect]) but the box spinner is suppressed. The instant the surface
 * settles into a steady non-loader state (e.g. a Disconnected/Reconnecting
 * placeholder with no centered "Attaching…"), [surfaceShowsCenteredLoader] flips
 * false and the box spinner returns as the SOLE affordance — #823 is preserved.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun SessionSurfaceReconnectWrapper(
    pullToReconnectActive: Boolean,
    isReconnecting: Boolean,
    onReconnect: () -> Unit,
    surfaceShowsCenteredLoader: Boolean = false,
    // Issue #890: gate the VISIBLE "Reconnect" button on a SETTLED failed/dropped
    // state. While a connect/attach/reconnect is actively IN PROGRESS (progress
    // bar + "Connecting…/Attaching…/Reconnecting…" showing) offering "Reconnect"
    // is nonsensical — the system is already trying — so the button is hidden.
    // It reappears only once the attach/connect has FAILED or is STUCK (an honest
    // error/dropped state where retrying makes sense). The pull GESTURE stays
    // mounted for the whole non-Connected window (it is an explicit user action,
    // not chrome that competes with anything) — only the discoverable button is
    // gated, matching the maintainer's "no button while I'm connecting" ask.
    showReconnectButton: Boolean = true,
    content: @Composable () -> Unit,
) {
    if (pullToReconnectActive) {
        val state = rememberPullToRefreshState()
        PullToRefreshBox(
            // Issue #750: never run the box's own spinner while the surface
            // already shows the centered "Attaching…" loader — exactly one
            // indicator during the attach/reattach hold. The pull GESTURE stays
            // live (suppressing the spinner does not disable the drag-to-refresh).
            isRefreshing = isReconnecting && !surfaceShowsCenteredLoader,
            onRefresh = onReconnect,
            state = state,
            modifier = Modifier
                .fillMaxSize()
                .testTag(TMUX_PULL_TO_RECONNECT_TAG),
        ) {
            // [PullToRefreshBox] drives its pull gesture through the nested-scroll
            // connection of its content. While `pullToReconnectActive` the surface
            // shows only a STATIC placeholder (Attaching… / waiting / empty — never
            // a live terminal), which is not itself scrollable, so we host it in a
            // `verticalScroll` container. That gives the pull-to-refresh a
            // nested-scroll source to consume the drag-down (mirroring the tree's
            // scrollable LazyColumn content) without changing what the user sees.
            // [BoxWithConstraints] pins the scrollable child to at least the
            // viewport height so the centered placeholder still fills the surface
            // (a bare `fillMaxSize` collapses to 0 under a scroll's unbounded
            // height constraint) and there is nothing to actually scroll.
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val viewportHeight = maxHeight
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = viewportHeight),
                    ) {
                        content()
                    }
                }
                // Issue #823: a VISIBLE, tappable "Reconnect" button — not only the
                // pull gesture. The maintainer's "there's not even a button to
                // reconnect" ask needs a discoverable control: the pull-down is
                // easy to miss (it has no chrome until you drag), so a labelled
                // button is overlaid on the surface.
                //
                // Issue #890 (reverses #823's "stays visible during the attach
                // hold"): the button is shown ONLY when the connect/attach has
                // SETTLED into a failed/dropped/stuck state — NOT while a
                // connect/reconnect/attach is actively in progress. Offering
                // "Reconnect" while the progress bar + "Attaching…/Reconnecting…"
                // is showing is nonsensical (the system is already trying); the
                // maintainer reported it as confusing chrome. `showReconnectButton`
                // is false during Connecting/Switching/Reconnecting and true on the
                // settled Failed/dropped (Idle) state where retrying makes sense.
                // The button routes to the SAME existing [onReconnect]
                // ([TmuxSessionViewModel.reconnect]) — no new connection logic, no
                // second writer on the reconnect path (D28).
                if (showReconnectButton) {
                    PocketShellButton(
                        text = "Reconnect",
                        onClick = onReconnect,
                        variant = ButtonVariant.Secondary,
                        compact = true,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 24.dp)
                            .testTag(TMUX_SURFACE_RECONNECT_BUTTON_TAG),
                    )
                }
            }
        }
    } else {
        content()
    }
}

/**
 * Issue #165: stable test tags for the SSH-handshake progress overlay
 * rendered while [TmuxSessionViewModel.ConnectionStatus] is Connecting.
 *
 * - [TMUX_CONNECTING_PROGRESS_TAG] is on the overlay container — the
 *   connected slow-connect test asserts this is visible from tap-attach
 *   through to Connected (or to the Cancel tap).
 * - [TMUX_CONNECTING_PROGRESS_BAR_TAG] is on the linear indeterminate
 *   progress bar inside the overlay.
 * - [TMUX_CONNECTING_SLOW_HINT_TAG] is on the 5s "still working" line
 *   so the overlay can be inspected at different stages without
 *   relying on translatable text.
 * - [TMUX_CONNECTING_CANCEL_TAG] is on the 15s Cancel button.
 */
internal const val TMUX_CONNECTING_PROGRESS_TAG = "tmux:session:connecting"
internal const val TMUX_CONNECTING_PROGRESS_BAR_TAG = "tmux:session:connecting:bar"
internal const val TMUX_CONNECTING_SLOW_HINT_TAG = "tmux:session:connecting:slow-hint"
internal const val TMUX_CONNECTING_CANCEL_TAG = "tmux:session:connecting:cancel"
internal const val TMUX_RECONNECTING_RETRY_NOW_TAG = "tmux:session:reconnecting:retry-now"

// Issue #661: the full-surface "Attaching" loading placeholder shown in place
// of the terminal during a cross-session switch, so the leaving session's
// content is never painted while the new session attaches.
internal const val TMUX_SWITCHING_LOADING_TAG = "tmux:session:switching-loading"

// Issue #1322: the full-surface CALM failed placeholder shown in place of the
// terminal on a HARD reveal failure (target Gone / retry-exhausted Error). It
// renders DISTINCTLY from the "Attaching…" hold — no spinner over a dead session
// — so the surface agrees with the "Disconnected" pill and the "Tap to
// reconnect" [FailedConnectionRow] band. The test tag lets the #1321 regression
// test assert the surface resolved to this state, not the Attaching spinner.
internal const val TMUX_REVEAL_FAILURE_TAG = "tmux:session:reveal-failure"

/**
 * Issue #165: timings for the SSH-handshake progress overlay. A
 * 2-5s handshake is the common case the audit flagged as "feels
 * frozen"; the 5s subline lets the user know the app is still
 * working past the typical window, and the 15s Cancel affordance
 * gives them an exit when something is clearly wrong.
 *
 * Internal so unit tests can drive the same constants the production
 * composable uses (otherwise a 15s wait would dominate the test
 * runtime).
 */
internal const val SLOW_CONNECT_HINT_AFTER_MS: Long = 5_000L
internal const val CANCEL_AVAILABLE_AFTER_MS: Long = 15_000L

/**
 * Issue #750 (4th occurrence): the top under-header connecting / reconnecting
 * banner region — the SINGLE render site for both top loading banners.
 *
 * Both the cold-dial [ConnectingProgressOverlay] ("Connecting to host…") and the
 * recovery [ReconnectingProgressRow] band are gated through [primaryLoadingSurface]
 * (via [shouldShowConnectingProgressOverlay] / [shouldShowReconnectingProgressRow]),
 * so NEITHER can render while the terminal is held (the reveal hold ==
 * true) — that is exactly when the surface Box paints the centered "Attaching…"
 * hold. This makes the maintainer's recurring "top connecting banner AND centered
 * spinner at once" symptom structurally impossible: while the terminal is held the
 * reducer resolves to [PrimaryLoadingSurface.CenteredAttaching] and this whole
 * region renders nothing.
 *
 * The two banners are mutually exclusive by status ([ConnectionStatus.Connecting]
 * vs [ConnectionStatus.Reconnecting]), so at most one ever renders here — and only
 * in the (currently unreached) live-frame-kept edge where the terminal is NOT held.
 *
 * Extracting this as a composable (rather than inlining the two `if` blocks in the
 * screen body) makes it a genuine WIRING guard: the #750 regression test drives
 * this exact composable in the beyond-grace state and hard-asserts a single loader,
 * so a future re-introduction of an ungated banner is caught in CI.
 */
@Composable
internal fun TmuxTopConnectingBanner(
    surfaceState: SessionSurfaceState,
    // Issue #1322/#1326: the caller passes the broadened "surface owns the primary
    // indicator" gate here — true for the "Attaching…" hold, the "waiting for tmux
    // panes…" ring, AND the calm failure placeholder — so the top banner is
    // suppressed in ALL of them (never stacked over a surface loader/failure).
    surfaceOwnsPrimary: Boolean,
    sessionName: String,
    onCancelConnect: () -> Unit,
    onRetryNow: () -> Unit,
) {
    // Issue #165/#1326: the cold-dial progress overlay (linear bar + host string +
    // slow hint + Cancel). Gated on [shouldShowConnectingProgressOverlay] (a pure
    // read of the ONE state) so it never stacks on top of the centered "Attaching…"
    // hold. The reveal machine holds the terminal for every real connect, so this
    // renders ONLY in the (now unreachable) live-frame-kept Connecting edge.
    if (shouldShowConnectingProgressOverlay(surfaceState, surfaceOwnsPrimary)) {
        (surfaceState as SessionSurfaceState.Connecting).let {
            ConnectingProgressOverlay(
                user = it.user,
                host = it.host,
                port = it.port,
                sessionLabel = "tmux $sessionName",
                onCancel = onCancelConnect,
            )
        }
    }
    // Issue #750/#1326: the recovery band. Every reconnect/reattach holds the
    // terminal and paints the centered "Attaching…" [SwitchingLoadingPlaceholder]
    // (the SOLE attach affordance), so the band is suppressed while held. It renders
    // ONLY for a [SessionSurfaceState.Reconnecting] that keeps a live frame painted
    // (surface owns nothing) — the fallback so a reconnect is never left with zero
    // indicators. (Reconnect behaviour is untouched — this is presentation-only.)
    if (shouldShowReconnectingProgressRow(surfaceState, surfaceOwnsPrimary)) {
        (surfaceState as SessionSurfaceState.Reconnecting).let {
            ReconnectingProgressRow(
                user = it.user,
                host = it.host,
                port = it.port,
                attempt = it.attempt,
                maxAttempts = it.maxAttempts,
                retryDelayMs = it.retryDelayMs,
                sessionLabel = "tmux $sessionName",
                onRetryNow = onRetryNow,
                onCancel = onCancelConnect,
            )
        }
    }
}

/**
 * Issue #165: progress overlay rendered above the terminal viewport
 * while the screen is in [ConnectionStatus.Connecting].
 *
 * SSH handshakes take 2-5s on real networks; before this overlay the
 * screen surfaced a single muted text line which the user-journey
 * audit (#163, Breakage 5) flagged as "looks frozen". The overlay
 * stack is:
 *
 * 1. A linear indeterminate [LinearProgressIndicator] (Material 3
 *    default — animates a moving sliver so the user reads
 *    "something is happening" at-a-glance).
 * 2. A primary label `Connecting to user@host:port (sessionLabel)…`
 *    — same information the previous `StatusLine` carried, just
 *    rendered as the foreground line of a visible affordance instead
 *    of an easily-missed status strip.
 * 3. After [SLOW_CONNECT_HINT_AFTER_MS] (5s) a subtle muted
 *    "Still working, this may be slow…" subline so the user knows
 *    the app has not silently stalled.
 * 4. After [CANCEL_AVAILABLE_AFTER_MS] (15s) a "Cancel" affordance
 *    appears. Tapping it invokes [onCancel] (the screen wires this
 *    to [TmuxSessionViewModel.cancelConnect] which cancels the
 *    in-flight [connectJob] cleanly via #151's join-on-cancel
 *    machinery).
 *
 * Auto-dismisses on success (the overlay is gated on
 * `ConnectionStatus.Connecting`); on failure the screen surfaces the
 * existing [FailedConnectionRow] error sheet which carries the user
 * forward without changing the failure UX from #145.
 *
 * The 5s / 15s timers run as suspending [LaunchedEffect]s keyed on
 * the visible host string so a same-screen target swap (e.g. the user
 * tapped a different host) re-arms both timers from zero — otherwise
 * a slow first attempt could carry its 15s timer into a fresh attempt
 * and surface Cancel immediately on the second host.
 *
 * Test tags:
 * - [TMUX_CONNECTING_PROGRESS_TAG] on the overlay root.
 * - [TMUX_CONNECTING_PROGRESS_BAR_TAG] on the linear progress bar.
 * - [TMUX_CONNECTING_SLOW_HINT_TAG] on the 5s "still working" line.
 * - [TMUX_CONNECTING_CANCEL_TAG] on the 15s Cancel button.
 */
@Composable
internal fun ConnectingProgressOverlay(
    user: String,
    host: String,
    port: Int,
    sessionLabel: String,
    onCancel: () -> Unit,
) {
    val targetKey = "$user@$host:$port|$sessionLabel"
    var showSlowHint by remember(targetKey) { mutableStateOf(false) }
    var showCancel by remember(targetKey) { mutableStateOf(false) }
    LaunchedEffect(targetKey) {
        // Re-key on the visible target so a swap (different host /
        // session) restarts both timers from zero. Without the key
        // change a same-screen retry would inherit the previous
        // attempt's elapsed time and surface Cancel near-instantly.
        showSlowHint = false
        showCancel = false
        kotlinx.coroutines.delay(SLOW_CONNECT_HINT_AFTER_MS)
        showSlowHint = true
        kotlinx.coroutines.delay(CANCEL_AVAILABLE_AFTER_MS - SLOW_CONNECT_HINT_AFTER_MS)
        showCancel = true
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = PocketShellColors.Surface)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag(TMUX_CONNECTING_PROGRESS_TAG),
    ) {
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .testTag(TMUX_CONNECTING_PROGRESS_BAR_TAG),
            color = PocketShellColors.Accent,
            trackColor = PocketShellColors.SurfaceElev,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Connecting to $user@$host:$port ($sessionLabel)…",
                color = PocketShellColors.Text,
                style = PocketShellType.bodyDense,
                modifier = Modifier.weight(1f),
            )
            if (showCancel) {
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.testTag(TMUX_CONNECTING_CANCEL_TAG),
                ) {
                    Text("Cancel")
                }
            }
        }
        if (showSlowHint) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Still working, this may be slow…",
                color = PocketShellColors.TextSecondary,
                style = PocketShellType.labelMono,
                modifier = Modifier.testTag(TMUX_CONNECTING_SLOW_HINT_TAG),
            )
        }
    }
}

/**
 * Issue #750 (3rd occurrence — class-wide single-indicator fix): the
 * under-header Reconnecting band. It is now a pure TEXT + ACTIONS affordance
 * ("Reconnecting to …", Retry now, Cancel) and intentionally carries NO
 * [LinearProgressIndicator].
 *
 * The reconnect SURFACE already owns the canonical animated indicator for every
 * non-Connected reconnect state:
 *  - while the terminal is HELD (the terminal is held) the centered
 *    "Attaching…" [SwitchingLoadingPlaceholder] spinner is shown, and this band
 *    is suppressed entirely by [shouldShowReconnectingProgressRow];
 *  - in the STEADY reconnect state (the terminal is shown) the
 *    [SessionSurfaceReconnectWrapper]'s [PullToRefreshBox] circular spinner is
 *    shown (`isRefreshing = isReconnecting`).
 *
 * Before this fix the steady state painted BOTH the surface's circular spinner
 * AND this band's linear bar at once — the maintainer's reported "two loading
 * indicators" that #750 keeps regressing on. Dropping the linear bar here makes
 * the surface spinner the SOLE animated indicator per state (hard-cut, D22),
 * while the text + Retry now / Cancel actions stay as the always-useful band.
 */
@Composable
internal fun ReconnectingProgressRow(
    user: String,
    host: String,
    port: Int,
    attempt: Int,
    maxAttempts: Int,
    retryDelayMs: Long,
    sessionLabel: String,
    onRetryNow: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = PocketShellColors.Surface)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag(TMUX_CONNECTING_PROGRESS_TAG),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Reconnecting to $user@$host:$port " +
                    "($sessionLabel, $attempt/$maxAttempts)…",
                color = PocketShellColors.Text,
                style = PocketShellType.bodyDense,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onRetryNow,
                modifier = Modifier.testTag(TMUX_RECONNECTING_RETRY_NOW_TAG),
            ) {
                Text("Retry now")
            }
            TextButton(
                onClick = onCancel,
                modifier = Modifier.testTag(TMUX_CONNECTING_CANCEL_TAG),
            ) {
                Text("Cancel")
            }
        }
        if (retryDelayMs > 0) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Retrying in ${retryDelayMs / 1_000}s",
                color = PocketShellColors.TextSecondary,
                style = PocketShellType.labelMono,
                modifier = Modifier.testTag(TMUX_CONNECTING_SLOW_HINT_TAG),
            )
        }
    }
}

/**
 * Issue #145: in-session SSH-disconnect error band.
 *
 * Rendered when the fused [SessionSurfaceState] is a settled
 * [SessionSurfaceState.Failed]. Surfaces the calm disconnect message and a
 * prominent, ALWAYS-present Reconnect button that calls back into
 * [TmuxSessionViewModel.reconnect].
 *
 * Issue #1521: the button is a prominent accent [PocketShellButton] (not a
 * borderless `TextButton`) and is ALWAYS shown in the failed band — it is no
 * longer gated on `canReconnect`. The prior gate hid the sole affordance whenever
 * the VM had not preserved a reconnect target, producing the maintainer's reported
 * dead-end (the copy said "Tap Reconnect to retry" while nothing on screen was
 * tappable). The caller ([TmuxSessionScreen]) surfaces honest feedback when there is
 * genuinely nothing to reconnect to, so an always-present button is never a silent
 * no-op.
 *
 * The row is tagged with [TMUX_SESSION_ERROR_TAG] (root) and
 * [TMUX_SESSION_RECONNECT_TAG] (button) so the connected
 * disconnect+reconnect test can locate both elements without relying
 * on the message string.
 */
@Composable
internal fun FailedConnectionRow(
    message: String,
    onReconnect: () -> Unit,
) {
    // EPIC #687 #720: the ONLY honest error (controller `Unreachable`) is a CALM
    // recoverable prompt — never raw `TransportException`/SSH exception text, never
    // the "Open the session again to reconnect" instruction. The message reads as a
    // calm prompt, not a scary red failure.
    //
    // Issue #1521 (maintainer dogfood — "there's nowhere to tap"): the disconnected
    // state MUST always expose an OBVIOUS, clearly-tappable Reconnect control. The
    // prior design rendered a borderless Material `TextButton` ("Tap to reconnect")
    // gated on `canReconnect`, so a dropped session whose reconnect target the VM did
    // not preserve (`canReconnect == false`) showed the "Tap Reconnect to retry" copy
    // with NO affordance at all — the reported dead-end where the copy tells the user
    // to reconnect but nothing reads as tappable, and the whole-row `clickable` was an
    // invisible tap zone the user could not discover. The band now ALWAYS renders a
    // prominent accent [PocketShellButton] (an obvious CTA, not muted text) wired to
    // the SAME existing reconnect action ([onReconnect] → [TmuxSessionViewModel.reconnect]):
    // no new reconnect path, no hidden whole-row tap zone. The caller surfaces honest
    // feedback if there is genuinely nothing to reconnect to, so a tap is never a
    // silent no-op.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = PocketShellColors.Surface)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag(TMUX_SESSION_ERROR_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            // #720: a calm, honest prompt — the muted secondary token, NOT the
            // alarming [Red] error band. The state is recoverable with one tap.
            color = PocketShellColors.TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(8.dp))
        PocketShellButton(
            text = "Reconnect",
            onClick = onReconnect,
            variant = ButtonVariant.Primary,
            compact = true,
            modifier = Modifier.testTag(TMUX_SESSION_RECONNECT_TAG),
        )
    }
}

/**
 * Issue #1362 (v0.4.25 release blocker): the settled-failure band, hoisted out of
 * the [TmuxSessionScreen] mega-composable into its OWN composable frame.
 *
 * This renders exactly what the inline `(surfaceState as? SessionSurfaceState.Failed)`
 * block used to: the calm [FailedConnectionRow] with the #145/#1344 "Disconnected from
 * <user>@<host>:<port>." wording (via [disconnectEndpointLabel] + [failureReasonSentence])
 * and the #1322 Error/Gone-distinct curated text for the config-level reasons. Nothing
 * about the produced UI changes — same band, same text, same tags.
 *
 * WHY it is a separate composable and not inline: the #1344 fix added the nested
 * `disconnectEndpointLabel(user, host, port)` -> `failureReasonSentence(...)` computation
 * INSIDE the ~2800-line `TmuxSessionScreen` method. `TmuxSessionScreen` dexes to a method
 * that already uses >256 registers, so D8 must address some locals with wide (v256+)
 * operands; the #1344 inline computation shifted D8's register allocation so that a
 * `MutableState` reference landed in a wide register with a `move-object/from16 v19<-v300`
 * that the API-33 ART bytecode verifier REJECTS (`VerifyError [0x39D8] copy1 v19<-v300
 * type=Reference: androidx.compose.runtime.MutableState`), crashing `AppNavigator` the
 * instant any journey opened a session screen (emulator tag-gate hard-red on all shards).
 * The register COUNT is not itself the trigger — the pre-#1344 method verified fine at an
 * even higher count — but the #1344 inline locals produced the fatal wide-register
 * MutableState move. Giving the computation its own composable frame removes those locals
 * from the giant method and restores an allocation the verifier accepts.
 * [com.pocketshell.app.proof.TmuxSessionScreenArtVerifyE2eTest] pins this: it resolves +
 * verifies this exact production class on an API-33 device and goes RED (VerifyError) on
 * the broken build, GREEN with this hoist.
 */
@Composable
internal fun SessionFailureBand(
    surfaceState: SessionSurfaceState,
    user: String,
    host: String,
    port: Int,
    onReconnect: () -> Unit,
) {
    val failed = surfaceState as? SessionSurfaceState.Failed ?: return
    FailedConnectionRow(
        // Issue #1344: host-qualify the generic disconnect so the band reads the
        // unified #145 "Disconnected from <user>@<host>:<port>." wording (the
        // maintainer's clear disconnected indicator) — restored after the S3 fuse
        // dropped it. The endpoint comes from this screen's target coordinates,
        // matching the VM's historical message exactly.
        message = failureReasonSentence(
            failed.reason,
            endpoint = disconnectEndpointLabel(user = user, host = host, port = port),
        ),
        onReconnect = onReconnect,
    )
}

/**
 * Issue #661/#1684: the primary full-surface loading overlay while a
 * cross-session switch attaches the new session. It is mounted exactly once in
 * the fixed screen-level surface slot; pager pages and swap-latch content use
 * [SessionSurfaceMaskPlaceholder] and can never relocate this indicator.
 */
@Composable
internal fun SwitchingLoadingPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(color = PocketShellColors.Background)
            .testTag(TMUX_SWITCHING_LOADING_TAG),
        contentAlignment = Alignment.Center,
    ) {
        // Issue #750/#756: adopt the canonical centered [LoadingIndicator.Spinner]
        // with the label slot — the ONE indicator for the attach/reattach state.
        // The thin under-header [SwitchingIndicatorRow] that previously also
        // rendered during a [Switching] switch (giving the maintainer's reported
        // "two loading indicators") is gone; this centered spinner is the sole
        // attach affordance.
        LoadingIndicator.Spinner(
            size = SpinnerSize.Medium,
            label = "Attaching…",
        )
    }
}

/**
 * Issue #1322/#1326: the full-surface CALM failed placeholder shown in place of the
 * terminal when the fused [SessionSurfaceState] is a settled failure
 * ([SessionSurfaceState.Gone] or [SessionSurfaceState.Failed]).
 *
 * The #1321 screenshot showed the honest failure state painted as the centered
 * "Attaching…" spinner because the surface hold special-cased ONLY the live reveal
 * (every other reveal collapsed to the same loading hold). A spinner over a dead
 * session is a lie — it will never resolve — and it desynced the surface from the
 * "Disconnected" pill and the "Tap to reconnect" band.
 *
 * This placeholder renders DISTINCTLY from [SwitchingLoadingPlaceholder]: NO
 * spinner, a calm muted status line. The surface, the pill, and the error band now
 * agree for the failure state — all derived from the ONE [SessionSurfaceState].
 * Deliberately does not itself carry a Reconnect button — the [FailedConnectionRow]
 * band owns the SINGLE, prominent, always-present reconnect affordance (#1521), so
 * there is exactly one obvious tappable Reconnect control on screen (no duplicate).
 */
@Composable
internal fun RevealFailurePlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(color = PocketShellColors.Background)
            .testTag(TMUX_REVEAL_FAILURE_TAG),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            // #720/#1322: a calm, honest prompt — the muted secondary token, NOT the
            // alarming error band and NOT a spinner. Issue #1521: dropped the
            // misleading "tap to reconnect above." pointer — the recovery affordance
            // is now the prominent, always-present "Reconnect" button in the
            // [FailedConnectionRow] band, an obvious CTA (not a hidden target the copy
            // vaguely gestured at), so the placeholder just states the calm status.
            text = "Disconnected.",
            color = PocketShellColors.TextSecondary,
            fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

/**
 * Issue #1326 (S3): project the tmux view-model [ConnectionStatus] into the neutral
 * [ConnectionPhase] the [sessionSurfaceState] fusion consumes for its pill/progress
 * payload. This is the SINGLE point where [ConnectionStatus] is read for the three
 * screen regions — every region reads the fused [SessionSurfaceState] instead.
 */
internal fun connectionPhaseOf(status: ConnectionStatus): ConnectionPhase =
    when (status) {
        is ConnectionStatus.Idle -> ConnectionPhase.Idle
        is ConnectionStatus.Connecting -> ConnectionPhase.Connecting(status.host, status.port, status.user)
        is ConnectionStatus.Switching -> ConnectionPhase.Warm(status.host, status.port, status.user)
        is ConnectionStatus.Connected -> ConnectionPhase.Live(status.host, status.port, status.user)
        is ConnectionStatus.Reconnecting -> ConnectionPhase.Reconnecting(
            host = status.host,
            port = status.port,
            user = status.user,
            attempt = status.attempt,
            maxAttempts = status.maxAttempts,
            retryDelayMs = status.retryDelayMs,
        )
        is ConnectionStatus.Failed -> ConnectionPhase.Failed
    }

/**
 * Issues #177 / #249 / #1326: map the fused [SessionSurfaceState] onto the
 * design-system [com.pocketshell.uikit.model.ConnectionStatus] used by the
 * breadcrumb dot + pill. Derived from the ONE state so the pill can NEVER disagree
 * with the surface/band: [SessionSurfaceState.Failed]/[SessionSurfaceState.Gone] →
 * `Error` (red, "Disconnected"), [SessionSurfaceState.Reconnecting]/
 * [SessionSurfaceState.Connecting] → `Connecting` (amber, "Reconnecting"),
 * [SessionSurfaceState.Attaching] (warm switch / live-unseeded) → `Connected`
 * (green, no pill flash), [SessionSurfaceState.Live] → `Connected`.
 */
internal fun SessionSurfaceState.toUiStatus(): com.pocketshell.uikit.model.ConnectionStatus =
    when (this) {
        is SessionSurfaceState.Live -> com.pocketshell.uikit.model.ConnectionStatus.Connected
        // A warm same-host switch (or a Live lifecycle whose pane has not seeded yet)
        // keeps the dot GREEN — the session is up; we are only revealing its pane.
        is SessionSurfaceState.Attaching -> com.pocketshell.uikit.model.ConnectionStatus.Connected
        is SessionSurfaceState.Connecting -> com.pocketshell.uikit.model.ConnectionStatus.Connecting
        is SessionSurfaceState.Reconnecting -> com.pocketshell.uikit.model.ConnectionStatus.Connecting
        is SessionSurfaceState.Failed -> com.pocketshell.uikit.model.ConnectionStatus.Error
        is SessionSurfaceState.Gone -> com.pocketshell.uikit.model.ConnectionStatus.Error
        is SessionSurfaceState.Navigating -> com.pocketshell.uikit.model.ConnectionStatus.Idle
        is SessionSurfaceState.Idle -> com.pocketshell.uikit.model.ConnectionStatus.Idle
    }

/**
 * Issue #1326 (S3): map the TYPED [FailureReason] to ONE calm, curated user-facing
 * sentence for the [FailedConnectionRow] band. The raw exception is NEVER shown
 * (it stays in the diagnostic logs); every failure surfaces as a recoverable "Tap
 * Reconnect" prompt, in the calm #720/#1322 tone. This is the ONLY place a failure
 * reason becomes display text.
 *
 * Issue #1344 (v0.4.25 regression): the generic connection-lost / socket-death /
 * ladder-exhaust case ([FailureReason.Unreachable]) renders the UNIFIED #145
 * "Disconnected from <user>@<host>:<port>. Tap Reconnect to retry." wording — the
 * clear, actionable disconnected indicator the maintainer relies on. The S3 fuse
 * dropped that phrase to a bare "Connection lost." here; this restores it while
 * keeping the fused [SessionSurfaceState] the single source (D22 hard-cut — no
 * legacy dual-read of the old `ConnectionStatus.Failed.message`). [endpoint] is the
 * `<user>@<host>:<port>` label built at the call site from the screen's target
 * coordinates (see [disconnectEndpointLabel]); when it is unknown (blank host in a
 * standalone render) the phrase degrades to a host-less "Disconnected. …" but the
 * #145 "Disconnected from"/"Disconnected" marker is always present. The config-level
 * reasons (auth/host/key) and the [FailureReason.ServerRestarted] / [FailureReason.SessionEnded]
 * cases keep their DISTINCT curated wording — the coherent #1322 Error/Gone
 * distinction is preserved, only the generic Unreachable disconnect is host-qualified.
 */
internal fun failureReasonSentence(reason: FailureReason, endpoint: String? = null): String =
    when (reason) {
        FailureReason.AuthFailed -> "Authentication failed — check your key. Tap Reconnect to retry."
        FailureReason.HostUnresolved -> "Host could not be resolved. Tap Reconnect to retry."
        FailureReason.ServerRestarted -> "The tmux server restarted — all sessions ended. Tap Reconnect."
        FailureReason.SessionEnded -> "This session ended. Tap Reconnect."
        FailureReason.KeyMissing -> "Private key file not found. Tap Reconnect to retry."
        is FailureReason.Unreachable ->
            if (endpoint.isNullOrBlank()) {
                "Disconnected. Tap Reconnect to retry."
            } else {
                "Disconnected from $endpoint. Tap Reconnect to retry."
            }
    }

/**
 * Issue #1344: build the `<user>@<host>:<port>` endpoint label for the unified #145
 * disconnect band wording ([failureReasonSentence]) from the session screen's target
 * coordinates. Returns null when the host is unknown/blank (a standalone render with
 * no target) so the band degrades to the host-less "Disconnected." rather than an
 * empty "Disconnected from ." — matching the historical VM-composed
 * "Disconnected from ${target.user}@${target.host}:${target.port}" wording exactly.
 */
internal fun disconnectEndpointLabel(user: String, host: String, port: Int): String? =
    if (host.isBlank()) null else "$user@$host:$port"
