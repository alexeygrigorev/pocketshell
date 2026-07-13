package com.pocketshell.app.tmux

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.pocketshell.app.composer.OutboundLauncherBadge
import com.pocketshell.app.composer.PromptComposerViewModel
import com.pocketshell.app.composer.outboundLauncherBadge
import com.pocketshell.app.session.AgentConversationUiState
import com.pocketshell.app.sessions.HostTmuxSessionPickerState
import com.pocketshell.uikit.model.SessionAgentKind
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Issue #1158 (R2 ART VerifyError fix): the session's Terminal/Conversation
 * [TmuxSessionTabState], derived in its OWN @Composable so the alt-buffer wiring
 * does not inflate the enormous [TmuxSessionScreen] method past ART's dex
 * verifier register limit.
 *
 * The reviewer found that adding the `altBufferAgentPaneIds` collectAsState, the
 * `altBufferAgent` local and the extra `remember(...)` key directly into the
 * mega-composable body tipped its method past the ART verifier's register
 * ceiling (`VerifyError ... copy1 v273<-...`), so the session screen - the app's
 * MAIN screen - crashed at class load on-device (invisible to the JVM verifier).
 * Extracting the derivation here moves ALL of that register pressure
 * (`recordedAgentKind` projection, `altBufferAgentPaneIds` collectAsState,
 * `altBufferAgent` derivation, the `derivedStateOf` remember) into this small
 * method's own frame, letting [TmuxSessionScreen] verify again.
 *
 * Behaviour is IDENTICAL to the previous inline block: it returns a
 * [State]<[TmuxSessionTabState]> which the caller reads via `by`, so the
 * derived-state read stays attributed to the caller's restart scope and the tab
 * chrome still stays OFF the 60ms agent-streaming flush (#1085). The alt-buffer
 * signal is a detection-INDEPENDENT positive agent signal (see
 * [TmuxSessionViewModel.altBufferAgentPaneIds]); a plain shell on the main buffer
 * never latches it, preserving the #894/#815 no-flap invariant.
 */
@Composable
internal fun rememberTmuxSessionTabState(
    viewModel: TmuxSessionViewModel,
    surfaceConversationPaneId: String?,
    presumedAgent: Boolean,
    currentSessionRecordedKind: SessionAgentKind?,
    agentConversationsState: State<Map<String, AgentConversationUiState>>,
): State<TmuxSessionTabState> {
    val recordedAgentKind = tmuxSessionRecordedAgentKind(currentSessionRecordedKind)
    // Issue #1158: the STICKY set of pane ids whose emulator has been seen on the
    // ALTERNATE screen buffer - the detection-independent positive agent signal
    // that keeps the Conversation tab reachable for an agent launched directly
    // inside a shell-recorded session (where `@ps_agent_kind=shell`, the
    // confirmed-shell verdict is never cleared, and live detection never binds).
    val altBufferAgentPaneIds by viewModel.altBufferAgentPaneIds.collectAsState()
    val altBufferAgent = surfaceConversationPaneId != null &&
        surfaceConversationPaneId in altBufferAgentPaneIds
    return remember(
        surfaceConversationPaneId,
        presumedAgent,
        recordedAgentKind,
        altBufferAgent,
    ) {
        derivedStateOf {
            tmuxSessionTabState(
                surfaceConversationPaneId?.let { agentConversationsState.value[it] },
                presumedAgent,
                recordedAgentKind,
                altBufferAgent,
            )
        }
    }
}

/**
 * Issue #1532 (RC-B / audit D2): the per-row backoff between successive
 * auto-flush re-dispatch attempts of the SAME deferred row inside one live
 * window. Long enough that a rapid burst of queue snapshots (a deferral emits
 * one immediately) can't hot-loop a just-tried row, short enough that a row
 * deferred during a SILENTLY-healed flap (status never leaves `Connected`, so
 * the `(sessionLive, target)` window never flips) still re-dispatches promptly
 * once the transport recovers — instead of parking "Will send when reconnected."
 * forever. Each re-dispatch funnels through the #1526 S1 verify-before-resend
 * ledger, so a silently-landed payload is never doubled.
 */
internal const val OUTBOUND_DEFERRED_REDISPATCH_BACKOFF_MS: Long = 3_000L

/**
 * Issue #1531 (audit RC1): the current session's undelivered-outbound summary for
 * the DOCKED composer launcher badge (null when nothing pending). Extracted here
 * (not the ratchet-guarded `TmuxSessionScreen` god-file) so the screen call site
 * is a single line.
 */
@Composable
internal fun rememberOutboundLauncherBadge(
    promptComposerViewModel: PromptComposerViewModel,
    targetSessionKey: String,
): OutboundLauncherBadge? {
    val items by promptComposerViewModel.outboundQueueItems.collectAsState()
    return remember(items, targetSessionKey) { items.outboundLauncherBadge(targetSessionKey) }
}

@Composable
internal fun TmuxOutboundQueueAutoFlushEffect(
    sessionLive: Boolean,
    targetSessionKey: String,
    promptComposerViewModel: PromptComposerViewModel,
    controller: OutboundQueueAutoFlushController,
) {
    LaunchedEffect(sessionLive, targetSessionKey, promptComposerViewModel, controller) {
        runOutboundQueueAutoFlush(
            sessionLive = sessionLive,
            outboundQueueItems = promptComposerViewModel.outboundQueueItems,
            controller = controller,
            retryNext = { excludingIds ->
                promptComposerViewModel.retryNextOutboundItem(excludingIds = excludingIds)
            },
            // Issue #1531 (audit RC3): un-park a row stranded in `Uploading`
            // (process death mid-upload, or an upload leg abandoned without a
            // requeue) on the SAME poll cadence — not only on a connection-window
            // flip. `requeueStaleOutboundInFlight` re-arms any Uploading/InFlight
            // row older than the stale cutoff back to `Queued` (a no-op for a
            // genuinely-active <cutoff upload), after which the retry lane can
            // claim it. Without this a stranded `Uploading` row was unretryable
            // by the user and unclaimable by auto-flush until the window flipped —
            // "Uploading attachments…" forever on a session that stayed live.
            sweepStaleInFlight = { promptComposerViewModel.requeueStaleOutboundInFlight() },
        )
    }
}

/**
 * Issue #1532 (RC-B / audit D2): the outbound auto-flush body, extracted from
 * [TmuxOutboundQueueAutoFlushEffect] so the LOAD-BEARING poll lane is reachable
 * under virtual time in a plain unit test (the composable just forwards to it —
 * no behaviour change). Two lanes race under one structured-concurrency scope:
 *
 *  - **Snapshot lane** — re-attempt one eligible row whenever the queue changes
 *    (the #900 path). A deferral emits a fresh snapshot, so a row whose attempt
 *    already outlasted the backoff (a slow connect-wait takes tens of seconds)
 *    re-dispatches on that emission.
 *  - **Poll lane** — re-attempt one eligible row on the
 *    [OUTBOUND_DEFERRED_REDISPATCH_BACKOFF_MS] cadence while the session is live.
 *    This is the ONLY thing that un-parks a row deferred inside an UNCHANGED live
 *    window (the #928/#822 silent-heal shape emits NO further snapshot once the
 *    row parks, so the snapshot lane alone would leave it stuck "Will send when
 *    reconnected." forever). No window flip required. `retryNext` self-gates on
 *    `sendInFlight` / `composerTarget`, so a poll with nothing to do is a cheap
 *    no-op.
 *
 * Bounded: [coroutineScope] returns only when both children complete, and both
 * are cancelled the moment the enclosing [LaunchedEffect] key set (sessionLive,
 * target, VM, controller) changes or the screen leaves composition.
 */
internal suspend fun runOutboundQueueAutoFlush(
    sessionLive: Boolean,
    outboundQueueItems: Flow<*>,
    controller: OutboundQueueAutoFlushController,
    retryNext: (excludingIds: Set<String>) -> String?,
    // Issue #1531 (audit RC3): re-arm a stranded `Uploading`/`InFlight` row past
    // the stale cutoff back to `Queued` so the retry lane can claim it WITHOUT a
    // connection-window flip. A no-op for genuinely-active uploads (their attempt
    // is younger than the cutoff). Defaulted to a no-op so existing unit tests
    // that only exercise the redispatch lanes keep compiling unchanged.
    sweepStaleInFlight: () -> Unit = {},
): Unit = coroutineScope {
    launch {
        outboundQueueItems.collect {
            controller.onQueueSnapshotChanged(sessionLive, retryNext)
        }
    }
    if (sessionLive) {
        // One sweep up front so a row already stranded when the live window opens
        // (e.g. an app restart that left an `Uploading` row) recovers promptly
        // instead of waiting a full backoff for the first poll tick.
        sweepStaleInFlight()
        while (isActive) {
            delay(OUTBOUND_DEFERRED_REDISPATCH_BACKOFF_MS)
            sweepStaleInFlight()
            controller.onQueueSnapshotChanged(sessionLive, retryNext)
        }
    }
}

/**
 * Issue #900 / #1532: state holder for the screen-level outbound queue
 * auto-flush effects. It requeues stale in-flight rows when a target becomes
 * live and retries at most one queue row per queue snapshot.
 *
 * Issue #1532 (RC-B / audit D2): the old design remembered a permanent
 * `autoRetriedIds` exclusion set that only reset on a `(sessionLive, target)`
 * WINDOW FLIP — so a row deferred during a SILENTLY-healed flap (status never
 * leaves `Connected`, the window never flips) was excluded FOREVER and parked
 * "Will send when reconnected." while the connection was actually fine (the
 * dominant "sent long after / never" mechanic). That once-per-window exclusion
 * is replaced by a PER-ROW time-bounded backoff: a dispatched row is suppressed
 * only for [OUTBOUND_DEFERRED_REDISPATCH_BACKOFF_MS] after its attempt (so a
 * snapshot burst can't hot-loop it), then becomes eligible to re-dispatch again
 * even within the SAME live window. Exactly-once is preserved by the #1526 S1
 * verify-before-resend ledger in the send path — a re-dispatch of an
 * already-landed payload is suppressed there, never doubled.
 */
internal class OutboundQueueAutoFlushController(
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private var windowKey: Pair<Boolean, String>? = null

    // Issue #1532 (RC-B): per-row timestamp of the last auto-flush dispatch
    // attempt. A row is suppressed from re-dispatch only while it is still within
    // its [OUTBOUND_DEFERRED_REDISPATCH_BACKOFF_MS] backoff; expired entries are
    // pruned each snapshot, which also bounds the map on a long-lived screen.
    private val lastAttemptAtMs = mutableMapOf<String, Long>()

    fun onConnectionWindowChanged(
        sessionLive: Boolean,
        targetSessionId: String,
        requeueStaleInFlight: () -> Unit,
    ) {
        val nextKey = sessionLive to targetSessionId
        if (nextKey == windowKey) return
        windowKey = nextKey
        // A genuine window flip is a fresh slate — clear all per-row backoffs so a
        // reconnect re-arms every row immediately (the existing #900/#993 path).
        lastAttemptAtMs.clear()
        if (sessionLive) requeueStaleInFlight()
    }

    fun onQueueSnapshotChanged(
        sessionLive: Boolean,
        retryNext: (excludingIds: Set<String>) -> String?,
    ): String? {
        if (!sessionLive) return null
        val now = clock()
        // Drop rows whose backoff has elapsed — they are eligible to re-dispatch
        // again (this is what un-parks a silently-healed deferred row) and pruning
        // keeps the map bounded.
        lastAttemptAtMs.entries.removeAll { now - it.value >= OUTBOUND_DEFERRED_REDISPATCH_BACKOFF_MS }
        val suppressed = lastAttemptAtMs.keys.toSet()
        val retriedId = retryNext(suppressed) ?: return null
        lastAttemptAtMs[retriedId] = now
        return retriedId
    }
}

/**
 * Issue #463: the short leaf label for the header project crumb, derived
 * from the active pane's working directory (the project path). Returns the
 * last path segment (e.g. `/home/alexey/git/pocketshell` -> `pocketshell`,
 * `~/work` -> `work`). The home directory and root collapse to `~` and `/`
 * respectively so the crumb still reads as a place, and a blank/odd path
 * falls back to the raw trimmed value.
 */
internal fun projectCrumbLabel(path: String): String {
    val trimmed = path.trim().trimEnd('/')
    if (trimmed.isEmpty()) return "/"
    if (trimmed == "~") return "~"
    val leaf = trimmed.substringAfterLast('/')
    return leaf.ifBlank { trimmed }
}

/**
 * Issue #686 (D28, reveal/session-identity slice 1): compute the header project
 * crumb label keyed to the SINGLE target session identity.
 *
 * The header is composed from two independently-timed sources: the session
 * label reads the nav-route TARGET `sessionName` (correct immediately), while
 * the project crumb is derived from the currently-visible pane's cwd. During a
 * cross-session switch (especially back->picker->open-B, which runs no teardown)
 * the VISIBLE pane is still the LEAVING session's for several frames, so its cwd
 * resolves to the LEAVING project. The two sources then DESYNC - the label
 * already shows the target while the crumb still wears the leaving session's
 * project folder, so the header paints TWO identities at once (the v0.3.34
 * dogfood report: `...-session-b` label + `...-proj-a` crumb over a blank pane).
 *
 * The fix keys the crumb to the SAME target session identity the label uses, via
 * TWO guards (a single boolean gate is not enough - back->open-B has sub-windows
 * where the gate is briefly false while the visible pane is still the leaving
 * one):
 *   1. while a switch is hiding the terminal ([switchHidesTerminal]) the crumb is
 *      suppressed (loading window), AND
 *   2. the crumb is suppressed whenever the VISIBLE pane's session
 *      ([visiblePaneSessionName]) does NOT match the nav-route TARGET
 *      ([targetSessionName]) - i.e. the crumb only renders when its cwd belongs
 *      to the session the header is keyed to.
 * Once the target's own pane is visible the crumb returns, keyed to the target.
 *
 * Pure so it can be unit-tested deterministically (the desync is a transient
 * mid-switch flash that is hard to sample reliably on an emulator).
 *
 * @param projectPath the visible pane's working directory (null when unknown).
 * @param switchHidesTerminal true while a cross-session switch is loading.
 * @param targetSessionName the nav-route TARGET session the header is keyed to.
 * @param visiblePaneSessionName the session the currently-visible pane belongs
 *   to (null when unknown). When it differs from [targetSessionName] the crumb
 *   would wear a NON-target session's project, so it is suppressed.
 * @return the crumb leaf label, or null when there should be NO crumb.
 */
internal fun keyedProjectCrumbLabel(
    projectPath: String?,
    switchHidesTerminal: Boolean,
    targetSessionName: String,
    visiblePaneSessionName: String?,
): String? {
    if (switchHidesTerminal) return null
    // Only show the crumb when the visible pane belongs to the target session.
    // A null visible-pane session (unknown) is allowed through so the steady
    // state still renders the crumb (the active pane is the target's), but a
    // KNOWN mismatch (the leaving session's pane is still shown) is suppressed.
    if (visiblePaneSessionName != null && visiblePaneSessionName != targetSessionName) {
        return null
    }
    return projectPath?.let(::projectCrumbLabel)
}

internal fun sessionSwitcherPages(
    state: HostTmuxSessionPickerState,
    currentSessionName: String,
): List<SessionSwitcherPage> {
    val current = SessionSwitcherPage(
        name = currentSessionName,
        statusLabel = "current",
        selectable = false,
    )
    return when (state) {
        HostTmuxSessionPickerState.Idle,
        is HostTmuxSessionPickerState.Loading,
        -> listOf(current.copy(statusLabel = "loading same-host sessions"))
        is HostTmuxSessionPickerState.Ready -> {
            val rows = state.rows.map { row ->
                SessionSwitcherPage(
                    name = row.name,
                    statusLabel = when {
                        row.name == currentSessionName -> "current"
                        row.attached -> "attached"
                        else -> "available"
                    },
                    selectable = true,
                )
            }
            val currentPage = rows.firstOrNull { it.name == currentSessionName }
                ?.copy(statusLabel = "current", selectable = false)
                ?: current
            listOf(currentPage) + rows.filterNot { it.name == currentSessionName }
        }
        is HostTmuxSessionPickerState.Fallback -> listOf(
            current.copy(statusLabel = state.message, selectable = false),
        )
        is HostTmuxSessionPickerState.ConnectError -> {
            val host = state.request.host
            listOf(
                current.copy(
                    statusLabel = "Couldn't reach ${host.username}@${host.hostname}:${host.port}.",
                    selectable = false,
                ),
            )
        }
    }
}

/**
 * Issue #652 (epic #636): decide whether a unified-pager settle event should
 * trigger a cross-session warm switch.
 *
 * The unified pager (#626) spans every open session on the host: the ACTIVE
 * session's panes come first, then each cached session's panes. The pager
 * remembers its page index across recompositions and across a session switch.
 * When the user deliberately opens session A (tap a row -> nav target session
 * becomes A -> `connect(A)` makes A active -> `rebuildUnifiedPanes()` reorders
 * the list so A heads it), the pager can still be sitting on a *stale* index
 * that now resolves to a DIFFERENT session's pane. The settle collector would
 * then fire `onReplaceTmuxSession(thatOtherSession)`, yanking the user out of
 * the session they just tapped and routing their next prompt to the wrong
 * project - the data-loss regression reported in #652.
 *
 * A settle is a genuine user-driven cross-session swipe ONLY when the pager is
 * already aligned with the deliberate nav target ([navTargetSession]); i.e. the
 * page the user is actually looking at agrees with the session the navigation
 * asked for. Until the pager re-aligns to a freshly-tapped target, settle
 * events are suppressed so the explicit tap always wins over a stale index.
 *
 * @param settledPaneSession the session name owning the pane the pager settled
 *   on (`null` when it can't be resolved - e.g. the list is mid-rebuild).
 * @param navTargetSession the session the current navigation destination asked
 *   to open (the user's explicit choice).
 * @param pagerAlignedToNavTarget whether the pager has already realigned to
 *   [navTargetSession] since the last nav-target change. Settles before
 *   realignment are stale-index artifacts and must not switch sessions.
 * @return the session to warm-switch to, or `null` to ignore the settle.
 */
internal fun settleSessionSwitchTarget(
    settledPaneSession: String?,
    navTargetSession: String,
    pagerAlignedToNavTarget: Boolean,
    // Issue #634 (C->A return-to-origin content-bleed): a settle is only a
    // GENUINE cross-session swipe if the user physically dragged the pager
    // since it last aligned to the nav target. The app's own
    // `scrollToPage` realignment after a switch - and any lagging
    // stale-index recomposition echo that resolves to the session we JUST
    // LEFT - produces a settle with NO preceding user drag. Honoring those
    // drag-less settles is exactly what intermittently warm-switched the
    // user back to session C right after they returned to A (both sessions'
    // frames then co-resident in the viewport). Requiring a real drag makes
    // the deliberate return-to-origin switch impossible to undo by a phantom
    // settle, while a real finger swipe (which always raises a drag
    // interaction) still switches.
    userDraggedSinceAlignment: Boolean,
): String? {
    if (!pagerAlignedToNavTarget) return null
    if (!userDraggedSinceAlignment) return null
    if (settledPaneSession == null) return null
    if (settledPaneSession == navTargetSession) return null
    return settledPaneSession
}
