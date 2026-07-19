package com.pocketshell.app.tmux

import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.pocketshell.app.agentcommands.AgentCommandCatalog
import com.pocketshell.app.session.AgentConversationUiState
import com.pocketshell.app.session.SessionTab
import com.pocketshell.app.tmux.TmuxSessionViewModel.ConnectionStatus
import com.pocketshell.core.agents.AgentDetection
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.connection.RevealState
import com.pocketshell.core.connection.SessionId
import com.pocketshell.core.connection.SessionSurfaceState
import com.pocketshell.core.connection.sessionSurfaceState
import com.pocketshell.core.connection.showsCalmFailure
import com.pocketshell.core.connection.showsCenteredLoader
import com.pocketshell.core.connection.surfaceOwnsPrimary
import com.pocketshell.core.connection.terminalHeld
import com.pocketshell.uikit.model.SessionAgentKind

/**
 * Issue #1685 (ART VerifyError, recurrence #3): the connection/surface state
 * slab of [TmuxSessionScreen], derived in ITS OWN @Composable frame.
 *
 * `TmuxSessionScreen` is one enormous composable; each collectAsState +
 * derivation it holds inline is a long-lived register in its dex method frame,
 * and when that frame crosses ART's 256-register wide-register cliff the class
 * fails on-device verification (`java.lang.VerifyError`) and the main screen
 * crashes at load. This holder moves the whole connection-status / surface-state
 * cluster into a separate method frame so those registers leave the mega-method.
 *
 * Recomposition parity: every field here derives from state the screen body
 * ALREADY read at its ROOT restart group (the `by collectAsState()` reads of
 * `panes` / `displayConnectionStatus` / `connectionStatus` / `revealState`), so
 * moving the reads into this builder does not change WHICH state changes
 * recompose the screen — only where the reads physically live. The screen still
 * recomposes on exactly the same connection-state changes it did before.
 */
internal class TmuxSessionConnectionRuntime(
    val panes: List<TmuxPaneState>,
    val unifiedPanes: List<TmuxPaneState>,
    val status: ConnectionStatus,
    val rawStatus: ConnectionStatus,
    val revealState: RevealState,
    val targetSessionId: SessionId,
    val activeSessionCardsTargetKey: String,
    val surfaceState: SessionSurfaceState,
    val terminalHeld: Boolean,
    val panesEmpty: Boolean,
    val surfaceCalmFailure: Boolean,
    val surfaceCenteredLoader: Boolean,
    val surfaceOwnsPrimary: Boolean,
    val sessionLive: Boolean,
)

@Composable
internal fun rememberTmuxSessionConnectionRuntime(
    viewModel: TmuxSessionViewModel,
    hostId: Long,
    hostName: String,
    host: String,
    port: Int,
    user: String,
    keyPath: String,
    sessionName: String,
    tmuxSessionId: String?,
    sessionCreated: Long?,
): TmuxSessionConnectionRuntime {
    val panes by viewModel.panes.collectAsState()
    // Issue #626: unified pane list for the cross-session pager.
    val unifiedPanes by viewModel.unifiedPanes.collectAsState()
    // Issue #876: collect the DEBOUNCED display status so a sub-1s reconnect blip
    // never flashes the "Reconnecting" band/spinner. Input gating reads the raw
    // status below so a held display state cannot leave controls live during a
    // real transport drop.
    val status by viewModel.displayConnectionStatus.collectAsState()
    val rawStatus by viewModel.connectionStatus.collectAsState()
    // EPIC #687 P1 (#686/#658): the screen is keyed to the TARGET session id —
    // the rendered screen state is a pure function of that id (`RevealStateMachine`),
    // so a late/stale frame from the previous session can NEVER paint.
    val revealState by viewModel.revealState.collectAsState()
    val targetSessionId = remember(hostId, sessionName, tmuxSessionId, sessionCreated) {
        tmuxTargetSessionId(hostId, sessionName, tmuxSessionId, sessionCreated)
    }
    val activeSessionCardsTargetKey = remember(hostId, host, port, user, keyPath, sessionName) {
        sessionCardsTargetKey(
            hostId = hostId,
            host = host,
            port = port,
            user = user,
            keyPath = keyPath,
            sessionName = sessionName,
        )
    }
    // Issue #1326 (S3): the SINGLE fused view state. The header pill, the surface
    // and the under-header band all derive from THIS one state via `when(state)`.
    val surfaceState = remember(revealState, status, targetSessionId) {
        sessionSurfaceState(revealState, connectionPhaseOf(status), targetSessionId)
    }
    val panesEmpty = unifiedPanes.isEmpty()
    // Issue #249: the single source of truth that gates the composer / send /
    // dictation path — input only reaches the remote when the `-CC` control
    // channel is `Connected`.
    val sessionLive = rawStatus is ConnectionStatus.Connected
    return TmuxSessionConnectionRuntime(
        panes = panes,
        unifiedPanes = unifiedPanes,
        status = status,
        rawStatus = rawStatus,
        revealState = revealState,
        targetSessionId = targetSessionId,
        activeSessionCardsTargetKey = activeSessionCardsTargetKey,
        surfaceState = surfaceState,
        terminalHeld = surfaceState.terminalHeld,
        panesEmpty = panesEmpty,
        surfaceCalmFailure = surfaceState.showsCalmFailure,
        surfaceCenteredLoader = surfaceState.showsCenteredLoader(panesEmpty),
        surfaceOwnsPrimary = surfaceState.surfaceOwnsPrimary(panesEmpty),
        sessionLive = sessionLive,
    )
}

/**
 * Issue #1685: the pager-relative pane selection of [TmuxSessionScreen], computed
 * in its own @Composable frame. These are plain per-recomposition derivations
 * (NOT `remember`ed — they follow `pagerState.currentPage` every frame), matched
 * verbatim to the inline originals; extracting them sheds their registers from
 * the mega-method's dex frame.
 */
internal class TmuxSessionPaneSelection(
    val currentUnifiedPane: TmuxPaneState?,
    val isActiveSessionPane: Boolean,
    val currentPane: TmuxPaneState?,
    val activeSessionPane: TmuxPaneState?,
    val surfacePane: TmuxPaneState?,
    val currentUnifiedPaneSessionName: String?,
    val surfaceConversationPaneId: String?,
)

@Composable
internal fun rememberTmuxSessionPaneSelection(
    conn: TmuxSessionConnectionRuntime,
    viewModel: TmuxSessionViewModel,
    pagerState: PagerState,
): TmuxSessionPaneSelection {
    // Issue #626: the unified pager shows panes from all sessions.
    val currentUnifiedPane = conn.unifiedPanes.getOrNull(pagerState.currentPage)
    val isActiveSessionPane = currentUnifiedPane?.let { viewModel.isActiveSessionPane(it) } ?: true
    val currentPane = if (isActiveSessionPane) currentUnifiedPane else null
    val activeSessionPane = conn.panes.firstOrNull()
    // Issue #797 (Shape A): the pane that drives the COMPOSER SURFACE +
    // Conversation tab + detection lookup + send/input routing. Follows the
    // VISIBLE pane (including a settled cached pane), suppressed only while a
    // cross-session switch is hiding the terminal (#661/#634/#636 stale-bleed).
    val surfacePane = tmuxSessionSurfacePane(
        visibleUnifiedPane = currentUnifiedPane,
        switchHidesTerminal = conn.terminalHeld,
    )
    val currentUnifiedPaneSessionName = currentUnifiedPane?.let {
        viewModel.sessionNameForUnifiedPane(it)
    }
    val surfaceConversationPaneId = surfacePane?.paneId
    return TmuxSessionPaneSelection(
        currentUnifiedPane = currentUnifiedPane,
        isActiveSessionPane = isActiveSessionPane,
        currentPane = currentPane,
        activeSessionPane = activeSessionPane,
        surfacePane = surfacePane,
        currentUnifiedPaneSessionName = currentUnifiedPaneSessionName,
        surfaceConversationPaneId = surfaceConversationPaneId,
    )
}

/**
 * Issue #1685: the agent-detection / presumed-agent signal cluster of
 * [TmuxSessionScreen], derived in its own @Composable frame (and holding the
 * sticky-agent map + its recorder effect) so the whole slab leaves the
 * mega-method's dex register frame.
 *
 * Recomposition parity is load-bearing here (#1085 / #796):
 *  - [surfaceChrome] is derived via `derivedStateOf` from the SAME
 *    [agentConversationsState] `State` the screen already holds, so the ~60ms
 *    agent-streaming flush still only invalidates a reader when one of the
 *    STABLE projected fields (detection / selectedTab / hasEvents / exists)
 *    actually changes — never once per flush. [agentConversationsState] stays a
 *    `State` (never resolved to a plain map here); its high-frequency `events`
 *    list is still read only inside the surface content's own restart scope.
 */
internal class TmuxSessionAgentSignals(
    val surfaceChrome: SurfaceConversationChrome,
    val currentDetection: AgentDetection?,
    val currentSelectedTab: SessionTab?,
    val liveAgentForPane: AgentKind?,
    val currentPaneId: String?,
    val paletteAgent: AgentKind?,
    val presumedAgent: Boolean,
    val presumedAgentKind: AgentKind?,
    val engineCommandSet: Set<String>,
    val quickReplyInputEligible: Boolean,
)

@Composable
internal fun rememberTmuxSessionAgentSignals(
    viewModel: TmuxSessionViewModel,
    conn: TmuxSessionConnectionRuntime,
    panes: TmuxSessionPaneSelection,
    agentConversationsState: State<Map<String, AgentConversationUiState>>,
    currentSessionRecordedKind: SessionAgentKind?,
): TmuxSessionAgentSignals {
    val surfaceConversationPaneId = panes.surfaceConversationPaneId
    val surfacePane = panes.surfacePane
    // Issue #894 (epic #821 Slice C): the set of CONFIRMED-SHELL pane ids.
    val confirmedShellPaneIds by viewModel.confirmedShellPaneIds.collectAsState()
    // Issue #1085 (freeze F3 / R2): read only STABLE projections of the surface
    // pane's conversation through ONE `derivedStateOf`, keeping the reader OFF the
    // 60ms agent-streaming flush.
    val surfaceChrome by remember(surfaceConversationPaneId) {
        derivedStateOf {
            val convo = surfaceConversationPaneId?.let { agentConversationsState.value[it] }
            SurfaceConversationChrome(
                detection = convo?.detection,
                selectedTab = convo?.selectedTab,
                hasEvents = convo?.events?.isNotEmpty() == true,
                exists = convo != null,
            )
        }
    }
    val currentDetection: AgentDetection? = surfaceChrome.detection
    val currentSelectedTab: SessionTab? = surfaceChrome.selectedTab
    // Issue #462: a "sticky" last-known agent for the visible pane so the agent
    // affordances do not flicker out on a transient detection miss.
    val stickyAgentForPane = remember { mutableStateMapOf<String, AgentKind>() }
    val liveAgentForPane = currentDetection?.agent
    // Issue #797: the sticky/palette agent key follows the SURFACE pane.
    val currentPaneId = surfacePane?.paneId
    LaunchedEffect(currentPaneId, liveAgentForPane) {
        val paneId = currentPaneId
        if (paneId != null && liveAgentForPane != null) {
            stickyAgentForPane[paneId] = liveAgentForPane
        }
    }
    val paletteAgent: AgentKind? = liveAgentForPane
        ?: currentPaneId?.let { stickyAgentForPane[it] }
    val surfaceTerminalState = surfacePane?.terminalState
    val quickReplyInputEligible = conn.sessionLive &&
        surfaceTerminalState != null &&
        currentSelectedTab != SessionTab.Conversation &&
        tmuxSessionHasPositiveAgentEvidence(
            hasLiveDetection = currentDetection != null,
            hasStickyAgent = paletteAgent != null,
            recordedAgentKind = tmuxSessionRecordedAgentKind(currentSessionRecordedKind),
        )
    // Issue #770: the engine slash-commands the terminal should make tappable.
    val engineCommandSet: Set<String> = remember(paletteAgent) {
        paletteAgent?.let { agent ->
            AgentCommandCatalog.commandsFor(agent).map { it.command }.toSet()
        } ?: emptySet()
    }
    // Issue #716 (Slice A) / #894 (Slice C) / #797: default to presumed-agent
    // during detection uncertainty, gated on the VISIBLE pane.
    val presumedAgent = surfacePane != null &&
        tmuxSessionPresumedAgent(
            hasLiveDetection = currentDetection != null,
            stickyAgent = paletteAgent,
            confirmedShell = surfacePane.paneId in confirmedShellPaneIds,
        )
    val presumedAgentKind: AgentKind? = if (currentDetection == null) {
        paletteAgent
    } else {
        null
    }
    return TmuxSessionAgentSignals(
        surfaceChrome = surfaceChrome,
        currentDetection = currentDetection,
        currentSelectedTab = currentSelectedTab,
        liveAgentForPane = liveAgentForPane,
        currentPaneId = currentPaneId,
        paletteAgent = paletteAgent,
        presumedAgent = presumedAgent,
        presumedAgentKind = presumedAgentKind,
        engineCommandSet = engineCommandSet,
        quickReplyInputEligible = quickReplyInputEligible,
    )
}

/**
 * Issue #1685: the screen's local UI-overlay visibility (dialogs, sheets, menus,
 * pickers) collapsed into ONE `remember`ed holder instead of ~14 separate
 * `var ... by remember { mutableStateOf(...) }` locals. Each of those locals was
 * a long-lived register in [TmuxSessionScreen]'s dex frame (read near the top in
 * the back handler and written/read at the very bottom in the sheets), so
 * bundling them into a single holder register measurably lowers the mega-method's
 * register pressure. Backed by `mutableStateOf` fields, so read/write semantics
 * (and recomposition) are IDENTICAL to the individual `by` delegates.
 *
 * The three KEY-SCOPED toggles ([tuiCommandNotice] reset per send target,
 * `showCardFeedSheet` per cards target, `consumedInitialWindowTarget` per
 * session/window) are intentionally NOT here — they keep their `remember(key)`
 * reset semantics at the call site.
 */
internal class TmuxSessionOverlayState {
    var moreExpanded by mutableStateOf(false)
    var dialogMode by mutableStateOf<TmuxDialogMode?>(null)
    var dialogText by mutableStateOf("")
    var showSessionSwitcher by mutableStateOf(false)
    var showSessionDrawer by mutableStateOf(false)
    // Issue #898: the in-session "+ New session" rich SessionTypePickerSheet.
    var showNewSessionSheet by mutableStateOf(false)
    // Issue #497: in-app file viewer path-entry dialog.
    var showOpenFileDialog by mutableStateOf(false)
    var openFilePath by mutableStateOf("")
    // Epic #821 Slice 1: the session-kind classify / change picker.
    var showKindPicker by mutableStateOf(false)
    var showMicSheet by mutableStateOf(false)
    // Issue #585: composer opened via the launcher hold+swipe-up ENTRY gesture
    // opens WITH recording already started; reset every time the sheet closes.
    var micSheetAutoStartRecording by mutableStateOf(false)
    var showSnippetPicker by mutableStateOf(false)
    // Issue #784: the dedicated terminal-hotkeys panel.
    var showHotkeysPanel by mutableStateOf(false)
    // Issue #488: a tapped server-local URL whose remote port is not yet forwarded.
    var pendingLocalhostForward by mutableStateOf<com.pocketshell.core.terminal.selection.LocalhostUrl?>(null)
}
