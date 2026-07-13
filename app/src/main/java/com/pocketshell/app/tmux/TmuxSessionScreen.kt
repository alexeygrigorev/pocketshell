package com.pocketshell.app.tmux

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.pocketshell.app.composer.OutboundRoute
import com.pocketshell.app.composer.PromptComposerSendDispatcher
import com.pocketshell.app.conversation.CONVERSATION_TOOL_COPY_TAG_PREFIX
import com.pocketshell.app.conversation.ConversationDiagnostics
import com.pocketshell.app.conversation.ConversationImageViewModel
import com.pocketshell.app.conversation.ConversationImages
import com.pocketshell.app.conversation.ConversationInteractionCleanupEffect
import com.pocketshell.app.conversation.LocalConversationImageLoader
import com.pocketshell.app.conversation.rememberConversationToTerminalSwapLatch
import com.pocketshell.app.conversation.ToolResultPairing
import com.pocketshell.app.conversation.ConversationMessageTurn
import com.pocketshell.app.conversation.ConversationTextSection
import com.pocketshell.app.conversation.ConversationToolArgsSection
import com.pocketshell.app.conversation.ConversationToolCardExpansion
import com.pocketshell.app.conversation.conversationTimelineVisibleEvents
import com.pocketshell.app.conversation.filterConversationRows
import com.pocketshell.app.conversation.isHiddenConversationTimelineRow
import com.pocketshell.app.conversation.runningToolCallIds
import com.pocketshell.app.conversation.timelineActorLabel
import com.pocketshell.app.conversation.timelinePreview
import com.pocketshell.app.conversation.timelineTimestamp
import com.pocketshell.app.conversation.toolResultPairing
import com.pocketshell.app.composer.PromptComposerViewModel
import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.app.layout.rememberTmuxImeLayoutState
import com.pocketshell.app.projects.conventionalRemoteHome
import com.pocketshell.app.projects.defaultSessionBaseName
import com.pocketshell.app.projects.derivedSessionName
import com.pocketshell.app.session.AgentConversationSyncStatus
import com.pocketshell.app.session.ConversationLoadState
import com.pocketshell.app.session.ConversationLinkAction
import com.pocketshell.app.session.ConversationSyncStatusRow
import com.pocketshell.app.session.InlineDictationViewModel
import com.pocketshell.app.settings.SettingsViewModel
import com.pocketshell.core.connection.ConnectionPhase
import com.pocketshell.core.connection.FailureReason
import com.pocketshell.core.connection.RevealState
import com.pocketshell.core.connection.SessionSurfaceState
import com.pocketshell.core.connection.sessionSurfaceState
import com.pocketshell.core.connection.showsCalmFailure
import com.pocketshell.core.connection.showsCenteredLoader
import com.pocketshell.core.connection.surfaceOwnsPrimary
import com.pocketshell.core.connection.terminalHeld
import com.pocketshell.core.connection.targetIdOrNull
import com.pocketshell.app.session.SessionTab
import com.pocketshell.app.session.conversationLinkAction
import com.pocketshell.app.session.conversationSyncStatusLabel
import com.pocketshell.app.session.cwdForDetectedFilePath
import com.pocketshell.app.sessions.HostTmuxSessionPickerRequest
import com.pocketshell.app.sessions.HostTmuxSessionPickerState
import com.pocketshell.app.sessions.HostTmuxSessionPickerViewModel
import com.pocketshell.app.agentcommands.AgentCommandCatalog
import com.pocketshell.app.cards.SessionCardFeedChip
import com.pocketshell.app.cards.SessionCardInteractions
import com.pocketshell.app.cards.cardFeedChipState
import com.pocketshell.app.snippets.SnippetKind
import com.pocketshell.app.snippets.snippetDispatchText
import com.pocketshell.app.startup.StartupTiming
import com.pocketshell.app.tmux.TmuxSessionViewModel.ConnectionStatus
import com.pocketshell.app.voice.AssistantStrip
import com.pocketshell.uikit.components.DisclosureIcon
import com.pocketshell.core.agents.AgentDetection
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.agents.ToolArgsView
import com.pocketshell.core.agents.ToolCallSummary
import com.pocketshell.core.agents.ToolPayloadFormatter
import com.pocketshell.core.terminal.selection.ConversationLink
import com.pocketshell.core.terminal.selection.LocalhostUrl
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.terminal.ui.showTerminalSoftKeyboard
import com.pocketshell.uikit.model.Crumb
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellSpacing
import kotlinx.coroutines.launch

/**
 * Phase 2 session screen for `tmux -CC` hosts, rendering one pane at a time.
 *
 * Per [D6](../../../../../../../../docs/decisions.md), exactly ONE pane is
 * rendered at a time, wrapped in a [HorizontalPager] so a horizontal swipe
 * navigates left/right between panes inside the current window. No tiled
 * rendering — tmux's native split layout is unreadable at phone scale.
 *
 * Stacks four bands top-to-bottom:
 *
 * 1. **Breadcrumb** — `host › session › window › pane`. Back arrow
 *    "detaches" (i.e. tears down the [TmuxSessionViewModel]).
 * 2. **Status line** — surfaces `Connecting`/`Failed` until the breadcrumb
 *    live dot is wired up post-#18 patterns.
 * 3. **[HorizontalPager]** of [TerminalSurface]s — one page per pane in
 *    the current window order.
 * 4. **[KeyBar]** above the keyboard (Terminal tab only — issue #459). Taps
 *    route raw keys to the currently visible pane via
 *    [TmuxSessionViewModel.onKeyBarKey]. The Conversation tab never shows the
 *    key bar; it shares the unified composer band
 *    ([com.pocketshell.app.composer.PromptComposerSheet]) with Terminal as
 *    its only send affordance.
 *
 * The screen does not own any business state — the panes list, the active
 * tmux client, and the per-pane terminal state holders all live in the
 * view model.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
public fun TmuxSessionScreen(
    viewModel: TmuxSessionViewModel,
    hostId: Long,
    hostName: String,
    host: String,
    port: Int,
    user: String,
    keyPath: String,
    passphrase: CharArray? = null,
    sessionName: String,
    startDirectory: String? = null,
    initialWindowIndex: Int? = null,
    tmuxSessionId: String? = null,
    sessionCreated: Long? = null,
    modifier: Modifier = Modifier,
    sessionPickerViewModel: HostTmuxSessionPickerViewModel = hiltViewModel(),
    inlineDictationViewModel: InlineDictationViewModel = hiltViewModel(),
    // Issue #487: per-host "port forwarding is active for THIS host" chip in
    // the in-session chrome, so the user doesn't forget a tunnel is open on
    // the server they're looking at. Pure read surface over the forwarding
    // controller; the chip taps through to the same per-host panel as the
    // kebab's "Port forwarding" action.
    sessionForwardingIndicatorViewModel:
        com.pocketshell.app.portfwd.SessionForwardingIndicatorViewModel = hiltViewModel(),
    // Issue #176: needed for the Settings → Conversation → "Show system
    // notes" toggle to take effect inside the conversation pane without
    // restarting the session.
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    // Issue #842: loads transcript-referenced images (host-path over the warm
    // SSH lease, base64 inline, or an http(s) URL) for inline display in the
    // Conversation view. Defaulted via hiltViewModel() so direct callers / unit
    // tests are unchanged.
    conversationImageViewModel: ConversationImageViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
    // Issue #666: the (re)attached session no longer exists on the server (it
    // was killed elsewhere while the app was backgrounded). On a cold-restore
    // the ViewModel surfaces this instead of resurrecting it; MainActivity
    // wires this to clear the persisted last-session snapshot and drop to the
    // host/session list. Defaults to [onBack] so the user always lands
    // somewhere usable even if a caller does not override it.
    onSessionEnded: (sessionName: String) -> Unit = { onBack() },
    onOpenTmuxSession: (sessionName: String, startDirectory: String?) -> Unit = { _, _ -> },
    onReplaceTmuxSession: (sessionName: String) -> Unit = {},
    onOpenJobs: () -> Unit = {},
    onOpenUsage: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    // Issue #445 (epic #432 slice A): open the per-host port-forward panel
    // from inside a live tmux session. MainActivity wires this to
    // navigate(AppDestination.PortForwardPanel(...)); the hand-rolled
    // back-stack restores this exact session/window on back.
    onOpenPortForwarding: () -> Unit = {},
    // Issue #448 (epic #432 slice C): the detection overlay's "Forward"
    // action opens the same panel pre-filled with the detected remote
    // port (#447 prefillRemotePort). MainActivity navigates with the
    // port; back returns to this exact session. Issue #608 extends this
    // callback for accepted localhost URL taps: those carry the tapped URL so
    // the port-forward panel can open the browser after the tunnel succeeds.
    onOpenPortForwardingWithPort: (remotePort: Int, autoOpenLocalhostUrl: LocalhostUrl?) -> Unit = { _, _ -> },
    /** Route an assistant-requested navigation (issue #266). */
    onAssistantNavigate: (com.pocketshell.app.nav.AppDestination) -> Unit = {},
    /**
     * Issue #497: open the in-app file viewer for a remote path. [cwd] is
     * supplied by the screen as the active pane's working directory so a
     * relative path the agent referenced resolves server-side. MainActivity
     * wires this to navigate(AppDestination.FileViewer(...)); back returns to
     * this exact session/window via the hand-rolled back-stack.
     */
    onOpenFile: (path: String, cwd: String?) -> Unit = { _, _ -> },
    /**
     * Issue #528: open the browsable file explorer. [startDir] is the active
     * pane's working directory so browsing starts where the agent is working
     * (or `~` when unknown). MainActivity wires this to
     * navigate(AppDestination.FileExplorer(...)); back returns to this exact
     * session/window via the hand-rolled back-stack.
     */
    onBrowseFiles: (startDir: String) -> Unit = {},
    // Issue #177 / #459: composer-draft persistence for fast resume.
    // Historically these seeded + reported the bespoke in-pane
    // Conversation composer's draft. Issue #459 collapsed the Conversation
    // bottom onto the shared unified composer ([PromptComposerSheet]),
    // whose draft now persists inside its own [PromptComposerViewModel].
    // The params remain on the screen signature because MainActivity still
    // passes them; they are intentionally inert here now. Defaults keep
    // direct callers / unit tests unchanged.
    @Suppress("UNUSED_PARAMETER") initialComposerDraft: String = "",
    @Suppress("UNUSED_PARAMETER") onInitialComposerDraftConsumed: () -> Unit = {},
    @Suppress("UNUSED_PARAMETER") onComposerDraftChanged: (String) -> Unit = {},
    // Issue #560: share-into-session staged attachment path(s). When
    // non-empty, the screen seeds them into the shared
    // [PromptComposerViewModel] as #544 chips and opens the composer sheet
    // focused so the user lands ready to type a message + Send. Consumed
    // once via [onInitialComposerAttachmentsConsumed].
    initialComposerAttachments: List<String> = emptyList(),
    onInitialComposerAttachmentsConsumed: () -> Unit = {},
    // Issue #763: a ready review prompt routed from the file viewer's "Attach to
    // current session" action. When non-blank, the screen seeds it into the
    // shared [PromptComposerViewModel] draft and opens the composer focused so
    // the user lands ready to edit + Send. Consumed once via
    // [onInitialComposerPromptConsumed].
    initialComposerPrompt: String = "",
    onInitialComposerPromptConsumed: () -> Unit = {},
    // Issue #560: the shared composer VM, obtained at the screen level so the
    // staged attachment chips can be seeded before the sheet first opens. The
    // same instance is handed to [PromptComposerSheet] below so the sheet
    // renders the seeded chips. Defaulted via `hiltViewModel()` (activity
    // scope) so direct callers / unit tests are unchanged.
    promptComposerViewModel: PromptComposerViewModel = hiltViewModel(),
    suggestStartDirectories: (suspend (String) -> List<String>)? = null,
    connectTrigger: TmuxConnectTrigger = TmuxConnectTrigger.UserTap,
) {
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        viewModel.onScreenStarted(sessionName)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        viewModel.onScreenStopped()
    }

    LaunchedEffect(Unit) {
        StartupTiming.markOnce(
            "tmux-screen-composed",
            "hostId" to hostId,
            "session" to sessionName,
            "hasStartDirectory" to (startDirectory != null),
        )
    }
    LaunchedEffect(
        hostId,
        hostName,
        host,
        port,
        user,
        keyPath,
        passphrase,
        sessionName,
        startDirectory,
        tmuxSessionId,
        sessionCreated,
        connectTrigger,
    ) {
        StartupTiming.mark(
            "tmux-connect-effect-start",
            "hostId" to hostId,
            "session" to sessionName,
            "hasStartDirectory" to (startDirectory != null),
            "trigger" to connectTrigger.logValue,
        )
        viewModel.connect(
            hostId = hostId,
            hostName = hostName,
            host = host,
            port = port,
            user = user,
            keyPath = keyPath,
            passphrase = passphrase,
            sessionName = sessionName,
            startDirectory = startDirectory,
            tmuxSessionId = tmuxSessionId,
            sessionCreated = sessionCreated,
            trigger = connectTrigger,
        )
    }

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
    // The id the controller / reveal machine key on for THIS screen's target:
    // durable tmux identity when the folder route provided it, with the legacy
    // "$hostId/$sessionName" fallback. The screen's drop-by-id and draft scoping
    // must match the controller's target.
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
    // and the under-header band all derive from THIS one state via `when(state)`,
    // so a contradictory (pill, surface, band) triple ("Disconnected" pill +
    // "Attaching…" spinner + raw exception — #641/#750/#1185/#1321) is no longer
    // type-representable. The id-keyed [RevealStateMachine] is the SURFACE authority
    // (hold / hard-failure / within-grace live-frame hold); [connectionPhaseOf]
    // projects the connection status into the PILL / progress payload. This is the
    // only place [revealState] and [status] are read for these three regions — every
    // region below reads [surfaceState].
    val surfaceState = remember(revealState, status, targetSessionId) {
        sessionSurfaceState(revealState, connectionPhaseOf(status), targetSessionId)
    }
    // Derived surface signals — all pure reads of the ONE state (they SUPERSEDE the
    // deleted independent revealHoldsTerminal / effectiveHidesTerminal /
    // revealHardFailure signals, #1326 AC-6).
    val terminalHeld = surfaceState.terminalHeld
    val panesEmpty = unifiedPanes.isEmpty()
    // A CALM FAILURE placeholder ([RevealFailurePlaceholder]) — Gone/Failed — carries
    // NO spinner (no "Attaching…" over Disconnected); the "Tap to reconnect" band
    // owns the single reconnect affordance.
    val surfaceCalmFailure = surfaceState.showsCalmFailure
    // A centered LOADER spinner — the "Attaching…" hold OR the "waiting for tmux
    // panes…" ring (reveal live but no panes yet). EITHER is the SOLE loader, so the
    // top connecting/reconnecting banner + the pull box spinner are both suppressed.
    val surfaceCenteredLoader = surfaceState.showsCenteredLoader(panesEmpty)
    // The single "the surface owns the primary indicator; the top banner + box
    // spinner are the redundant duplicates" gate (#750/#1322).
    val surfaceOwnsPrimary = surfaceState.surfaceOwnsPrimary(panesEmpty)
    // Issue #487: active-forwarding state for the host this session belongs
    // to. `remember(hostId)` re-subscribes if the screen is reused for a
    // different host. Drives the in-session forwarding chip in the chrome.
    val sessionForwardingState by remember(hostId) {
        sessionForwardingIndicatorViewModel.stateFor(hostId)
    }.collectAsState()
    // Issue #448 (epic #432 slice C): a confirmed newly-listening remote
    // port (regex over output + `ss` confirm). Non-null drives the
    // non-blocking forward overlay rendered over the terminal.
    val detectedPort by viewModel.detectedPort.collectAsState()
    // Issue #249: the single source of truth that gates the composer /
    // send / dictation path. Input only reaches the remote when the tmux
    // `-CC` control channel is live (`Connected`). While Connecting (the
    // background-detach reattach handshake of #177) or Failed (a dropped
    // socket) a chip tap / key press / dictation would be written into a
    // dead bridge and silently lost — exactly the data-loss the user
    // reported. We disable those affordances and surface a visible
    // "Reconnecting" / "Disconnected" pill instead.
    val sessionLive = rawStatus is ConnectionStatus.Connected
    val outboundQueueAutoFlushController = remember(targetSessionId.value) {
        OutboundQueueAutoFlushController()
    }
    LaunchedEffect(targetSessionId.value) {
        promptComposerViewModel.onComposerTargetChanged(targetSessionId.value)
    }
    DisposableEffect(promptComposerViewModel, viewModel) {
        promptComposerViewModel.setOutboundAttachmentSidecarUploader { refs ->
            viewModel.uploadQueuedAttachmentSidecars(refs)
        }
        onDispose {
            promptComposerViewModel.setOutboundAttachmentSidecarUploader(null)
        }
    }
    LaunchedEffect(sessionLive, targetSessionId.value) {
        promptComposerViewModel.setConnectionDegraded(!sessionLive)
        outboundQueueAutoFlushController.onConnectionWindowChanged(
            sessionLive = sessionLive,
            targetSessionId = targetSessionId.value,
        ) {
            promptComposerViewModel.requeueStaleOutboundInFlight()
        }
    }
    TmuxOutboundQueueAutoFlushEffect(
        sessionLive = sessionLive,
        targetSessionKey = targetSessionId.value,
        promptComposerViewModel = promptComposerViewModel,
        controller = outboundQueueAutoFlushController,
    )
    val sessionCardsState by viewModel.sessionCards.collectAsState()
    val sessionCards = remember(sessionCardsState, activeSessionCardsTargetKey) {
        if (sessionCardsState.targetKey == activeSessionCardsTargetKey) {
            sessionCardsState.feed.cards
        } else {
            emptyList()
        }
    }
    val sessionCardFeedChipState = remember(sessionCards) {
        cardFeedChipState(sessionCards)
    }
    LaunchedEffect(sessionLive, activeSessionCardsTargetKey) {
        if (sessionLive) viewModel.refreshActiveSessionCards()
    }
    // Issue #459: the per-screen restored-draft holder used to seed the
    // bespoke in-pane Conversation composer is gone. The Conversation
    // bottom now shares the unified [PromptComposerSheet], whose draft
    // persists in [PromptComposerViewModel], so there is nothing to seed
    // here on the screen.
    // Issue #1085 (freeze F3 / R2 — agent-streaming recomposition jank): the
    // agent transcript tail flushes a fresh `agentConversations` map every
    // `tailBatchWindowMillis`=60ms while an agent is streaming (~16 Hz). Held as
    // a `State<Map<...>>` (NOT a delegated `by` read) so the body's ROOT restart
    // group is never subscribed to that 16 Hz churn directly: the body reads
    // only STABLE projections of the surface pane's conversation via
    // `derivedStateOf` (below), and the high-frequency `events` list is read
    // inside the `surfaceContent` child scope — its OWN restart group — so a
    // streaming flush recomposes ONLY the transcript view, not the chrome /
    // composer / terminal scaffolding.
    val agentConversationsState = viewModel.agentConversations.collectAsState()
    val sessionPickerState by sessionPickerViewModel.state.collectAsState()
    // Issue #463: the in-session project switcher's sibling list, sourced
    // from the warm live `-CC` client only (no SSH handshake) so tapping the
    // header project crumb and switching stays instant.
    val projectSwitcherState by sessionPickerViewModel.projectSwitcher.collectAsState()
    val assistantState by viewModel.assistantState.collectAsState()
    // Issue #1085 (freeze F3 / R1 — dictation amplitude recomposition jank):
    // the inline-dictation silence watchdog rewrites `uiState` with a fresh
    // amplitude sample every SAMPLE_INTERVAL_MS=50ms (20 Hz) for the whole
    // recording. Reading the WHOLE `uiState` State in this body (the old
    // `val dictationState by ...collectAsState()`) subscribed the
    // `TmuxSessionScreen` ROOT restart group — the group that hosts the
    // terminal-render subtree — to that 20 Hz churn, so the entire ~7k-line body
    // recomposed 20×/s while the user dictates (the voice-first jank vector).
    // The body never reads the amplitude; it only needs the (low-frequency)
    // dictation MODE (the transcript-routing LaunchedEffect below). Deriving
    // just `mode` via `derivedStateOf` keeps the body OFF the 20 Hz path: the
    // derived value only changes when the mode flips, so the amplitude emissions
    // no longer recompose the body. The amplitude is consumed by the leaf mic UI
    // ([KeyBarWithMic]), not here.
    val dictationUiState = inlineDictationViewModel.uiState.collectAsState()
    val dictationMode by remember(dictationUiState) {
        derivedStateOf { dictationUiState.value.mode }
    }
    // Issue #176: collected once at the screen root so the conversation
    // pane recomposes when the toggle flips in Settings.
    val appSettings by settingsViewModel.state.collectAsState()
    // Issue #145: gate the in-session Reconnect button on whether the
    // ViewModel has a target to reconnect to. Without this, the button
    // would render in a tight initial-connect-failure window where
    // `reconnect()` would silently no-op.
    val canReconnect by viewModel.canReconnect.collectAsState()
    // Epic #821 Slice 1: the active session's RECORDED `@ps_agent_kind`. `null`
    // means foreign / not-yet-classified → the change-kind menu reads "What is
    // this session?" and the picker opens in the unknown ("we don't know this
    // session — choose") mode.
    val currentSessionRecordedKind by viewModel.currentSessionRecordedKind.collectAsState()
    // Issue #894 (epic #821 "Slice C"): the set of CONFIRMED-SHELL pane ids,
    // sourced from the durable per-session recorded `@ps_agent_kind=shell`
    // verdict (NOT the unreliable "no agent matched → assume shell" absence).
    // The visible pane's membership wires `confirmedShell` below so a genuine
    // shell pane collapses the presumed-agent surface (and never flashes the
    // #878 "Loading conversation…" placeholder) from the start.
    val confirmedShellPaneIds by viewModel.confirmedShellPaneIds.collectAsState()
    // Issue #858: the recorded non-default profile (e.g. z.ai Claude), shown in
    // the "What is this session?" sheet so a profiled session is distinguishable.
    val currentSessionRecordedProfile by viewModel.currentSessionRecordedProfile.collectAsState()
    // Issue #898: the host-discovered agent profiles for the in-session
    // "+ New session" rich sheet's Profile selector — the SAME flows the host
    // screen uses. Fetched on demand when the sheet opens.
    val newSessionClaudeProfiles by viewModel.claudeProfiles.collectAsState()
    val newSessionCodexProfiles by viewModel.codexProfiles.collectAsState()
    LaunchedEffect(status, canReconnect) {
        recordTmuxReconnectUiStateRendered(
            status = status,
            hostId = hostId,
            canReconnect = canReconnect,
        )
    }
    // Epic #821 Slice 1: read the active session's recorded `@ps_agent_kind`
    // once it is connected (and on a session switch) so the change-kind menu
    // label and the picker mode reflect the host. Re-read when the session name
    // changes or the connection re-establishes.
    LaunchedEffect(sessionName, status) {
        if (status is ConnectionStatus.Connected) {
            viewModel.refreshCurrentSessionRecordedKind()
        }
    }

    val pagerState = rememberPagerState(pageCount = { unifiedPanes.size })

    val density = LocalDensity.current
    // Issue #616 / #615: read the IME inset from the HOST activity window, not
    // from Compose's `WindowInsets.ime`. The local read returns 0 on the
    // maintainer's real device even while the soft keyboard is up, so
    // `isImeVisible` stayed false and the terminal hotkey KeyBar collapsed into
    // the IME-hidden chip strip exactly when the user was typing. The host
    // window's IME inset is reliable; `rememberHostImeBottomPx` is the single
    // shared source (composer + terminal) so they can never drift. Hard-cut: the
    // old `WindowInsets.ime` read is gone (D22, no fallback).
    // Issue #796 (H4): the host-IME inset is written on EVERY inset change, and
    // the keyboard-show/hide animation emits a BURST of interpolated inset
    // frames. Reading the `State<Int>` value DIRECTLY in this composable body
    // (the old `val imeBottomPx by rememberHostImeBottomPx()` delegate) made the
    // whole `TmuxSessionScreen` function — including the `HorizontalPager` of
    // `TerminalSurface`s (the terminal-render subtree) — a subscriber, so every
    // inset frame recomposed a large slice of the screen on the main thread.
    // When a Codex `%output` burst is in flight at the same moment, that
    // IME-recomposition storm and the terminal render share one main thread and
    // stack toward the ANR threshold (the maintainer's keyboard-up ANR).
    //
    // Fix (the Compose-recommended deferred-read pattern for animated insets),
    // encapsulated in [TmuxImeLayoutState]:
    //  - Hold the `State<Int>` inside the holder WITHOUT a delegated read here,
    //    so the screen body never subscribes to the raw int.
    //  - Issue #887: the terminal column no longer pans, so there is no raw
    //    pixel-inset read on the render path at all. The screen body never
    //    subscribes to the raw int — keeping it off the per-inset-frame
    //    invalidation path (the #796 H4 invariant) for free.
    //  - `imeLayout.isImeVisible` is `derivedStateOf`-backed, so reading it only
    //    invalidates the reader when the inset CROSSES the 0 boundary (keyboard
    //    show/hide), not on every interpolation frame. The chrome / composer /
    //    KeyBar that genuinely depend on keyboard-up recompose once per
    //    transition, never per frame.
    val navBarBottomPx = WindowInsets.navigationBars.getBottom(density)
    val imeLayout = rememberTmuxImeLayoutState(navBarBottomPx)
    val isImeVisible = imeLayout.isImeVisible
    // Issue #457 (Part 1) + #887: the terminal column stays FIXED when the soft
    // keyboard shows — NEITHER resized NOR panned. The embedded TerminalView
    // never shrinks (no tmux pane resize / full reflow + redraw — the #457
    // invariant) AND it never moves up (the #887 fix: the keyboard simply
    // overlays the bottom rows). `MainActivity` sets the activity window to
    // `SOFT_INPUT_ADJUST_NOTHING` so the OS never pans/resizes the window, and
    // the in-app GPU pan #457 used (`graphicsLayer { translationY = ... }`) is
    // gone. `imeLayout.isImeVisible` is still read here (`derivedStateOf`-backed,
    // boundary-gated per #796 H4) only to collapse the top chrome and drive the
    // KeyBar while typing — it never moves the terminal grid.
    // Issue #184: while the soft keyboard is up, the user is in
    // "typing-focus" mode — the breadcrumb / window-strip / tabs chrome
    // up top eats vertical room that the terminal viewport (and the
    // cursor row inside it) desperately needs. We drop the top chrome
    // when the IME is visible and restore it on hide. Mirrors Telegram's
    // chrome-while-typing behaviour and matches issue #184's Layer 2.
    val chromeCompressed = isImeVisible

    // Issue #796 (H4): count re-executions of the TmuxSessionScreen function
    // ROOT restart group — the group that reads the IME state and HOSTS the
    // terminal-render subtree (the `HorizontalPager` of `TerminalSurface`s).
    // This is the "large slice" the research spike (#796 §3) flagged. The
    // deferred-read fix above (via [TmuxImeLayoutState]) keeps this root group
    // OFF the per-inset-frame invalidation path. The #796 H4 full-journey
    // regression proof reads this counter as a production-side diagnostic of how
    // many times the real screen root group recomposes; the deterministic
    // per-frame recomposition-scoping assertion lives in the structural proof
    // (which composes the production [TmuxImeLayoutState] and drives a controlled
    // inset burst). Pure counter, no behaviour change; production never reads it.
    TmuxRenderRecompositionProbe.Record()

    // Issue #626: the unified pager shows panes from all sessions.
    // currentUnifiedPane is what the pager is actually displaying.
    val currentUnifiedPane = unifiedPanes.getOrNull(pagerState.currentPage)
    // Issue #626: determine whether the current unified pane belongs to
    // the active session or a cached session.
    val isActiveSessionPane = currentUnifiedPane?.let { viewModel.isActiveSessionPane(it) } ?: true
    // The active-session pane. `currentPane` is non-null ONLY when the visible
    // pager page belongs to the active session (its panes are in `_panes.value`,
    // so the active `clientRef` owns its bytes/detection). Kept for the places
    // that genuinely require the active runtime to already own the pane.
    val currentPane = if (isActiveSessionPane) currentUnifiedPane else null
    // Keep a reference to the actual active session pane for cases that
    // need it regardless of what the pager is showing (e.g. session switch).
    val activeSessionPane = panes.firstOrNull()
    // Issue #797 (Shape A): the pane that drives the COMPOSER SURFACE +
    // Conversation tab + detection lookup + send/input routing. Unlike
    // `currentPane` (active-only), this follows the VISIBLE pane — including a
    // settled CACHED pane — so the composer launcher and Conversation tab never
    // vanish while the user is parked on a real pane that the warm switch has
    // not yet promoted to active. Suppressed only while a cross-session switch
    // is hiding the terminal (never surface/route to the leaving session — the
    // #661/#634/#636 stale-bleed guard). Routing always targets THIS pane's own
    // `paneId`, never a stale leaving-session pane; the companion
    // promotion-on-settle path rebinds `clientRef`/`_panes.value` to it so input
    // + detection actually work. See [tmuxSessionSurfacePane].
    val surfacePane = tmuxSessionSurfacePane(
        visibleUnifiedPane = currentUnifiedPane,
        switchHidesTerminal = terminalHeld,
    )
    // Issue #626: derive session name for the unified pane, used to detect
    // cross-session swipes.
    val currentUnifiedPaneSessionName = currentUnifiedPane?.let {
        viewModel.sessionNameForUnifiedPane(it)
    }
    // Issue #797: detection/conversation lookup follows the SURFACE pane (the
    // visible pane), not the active-only `currentPane` — so a cached-pane
    // detection (once promotion fires) and the presumed-agent default both reach
    // the surface the user is looking at.
    val surfaceConversationPaneId = surfacePane?.paneId
    // Issue #1085 (freeze F3 / R2): the body chrome needs only the STABLE
    // projections of the surface pane's conversation — its detection, its
    // selected tab, whether it has any events, and whether a row exists. The
    // agent-streaming flush (60ms) changes the conversation's `events` LIST but
    // NOT these projections (detection/selectedTab are unchanged mid-stream;
    // `hasEvents`/`exists` only flip ONCE, on the first event / first row).
    // Reading them through ONE `derivedStateOf` keeps the body's ROOT restart
    // group OFF the per-flush invalidation path: `derivedStateOf` re-runs its
    // (cheap) projection on each flush but only invalidates the body when the
    // projected value actually changes — so the 16 Hz transcript stream no
    // longer recomposes the chrome. The high-frequency `events` list itself is
    // read inside `surfaceContent` (its own restart scope) for the transcript.
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
    val scope = rememberCoroutineScope()

    // Issue #463: the current session's project path (the active pane's
    // working directory) and the short leaf label shown on the header crumb.
    // Null cwd → no crumb (we don't know the project to scope siblings to).
    // Issue #626: fall back to the unified pane's cwd when the active pane
    // is null (viewing a cached-session pane).
    val projectPath = (currentPane ?: currentUnifiedPane)?.cwd?.takeIf { it.isNotBlank() }
    // Issue #686 (D28, reveal/session-identity slice 1): the header must be
    // keyed to ONE target session id and never show a STALE/DUPLICATED identity
    // during a switch. The project crumb is derived from the currently-visible
    // (leaving) pane's cwd — a DIFFERENT, separately-timed source than the
    // session label, which already reads the nav-route TARGET `sessionName`.
    // During a cross-session switch those two sources DESYNC: the label
    // correctly flips to the target while the crumb still wears the LEAVING
    // session's project folder, so the header paints two identities at once (the
    // v0.3.34 "pocketshell ▾ + git-ai-shipping-labs + stray git-3d-models over a
    // blank pane" report). [keyedProjectCrumbLabel] keys the crumb to the SAME
    // target identity the label uses via two guards — suppressed while the switch
    // hides the terminal AND whenever the visible pane belongs to a non-target
    // session — so the header shows the SINGLE target identity throughout the
    // switch, then the crumb returns once the target's own pane is visible. This
    // unifies both header identity sources behind one target-keyed gate (the
    // screen-identity-keying half of #686; full RevealStateMachine wiring is a
    // later slice).
    val projectLabel = remember(
        projectPath,
        terminalHeld,
        sessionName,
        currentUnifiedPaneSessionName,
    ) {
        // EPIC #687 P1: under NEW the crumb keys off the reveal-driven
        // [terminalHeld] (held until the target's pane is revealed); under
        // OLD it keys off the inline `switchHidesTerminal`. Either way the crumb is
        // suppressed while the target's own pane is not yet visible, so the header
        // shows the SINGLE target identity throughout a switch.
        keyedProjectCrumbLabel(
            projectPath = projectPath,
            switchHidesTerminal = terminalHeld,
            targetSessionName = sessionName,
            visiblePaneSessionName = currentUnifiedPaneSessionName,
        )
    }
    val haptics = LocalHapticFeedback.current
    val verticalSwipeThresholdPx = with(LocalDensity.current) { VerticalSwipeThreshold.toPx() }
    var moreExpanded by remember { mutableStateOf(false) }
    var dialogMode by remember { mutableStateOf<TmuxDialogMode?>(null) }
    var dialogText by remember { mutableStateOf("") }
    var showSessionSwitcher by remember { mutableStateOf(false) }
    var showSessionDrawer by remember { mutableStateOf(false) }
    // Issue #898: the in-session "+ New session" now opens the SAME rich
    // SessionTypePickerSheet the host/session-list screen uses (Session type,
    // Agent CLI, Skip permissions, Profile) — not the old stripped-down
    // name+folder dialog (hard-cut D22). The folder pre-fills from the current
    // session's pane cwd.
    var showNewSessionSheet by remember { mutableStateOf(false) }
    // Issue #497: in-app file viewer path-entry dialog (kebab "Open file…").
    var showOpenFileDialog by remember { mutableStateOf(false) }
    var openFilePath by remember { mutableStateOf("") }
    // Epic #821 Slice 1: the session-kind classify / change picker.
    var showKindPicker by remember { mutableStateOf(false) }
    // Voice/dictation surfaces — mirror SessionScreen so the tmux route
    // gets the prompt composer, the mic FAB, the inline-dictation key bar,
    // and the snippet picker. Without these the user can never dictate
    // into a tmux pane (see #123 — the primary user route was completely
    // dark for voice input).
    var showMicSheet by remember { mutableStateOf(false) }
    // Issue #585: when the composer is opened via the launcher's hold+swipe-up
    // ENTRY gesture, it must open WITH recording already started + locked
    // hands-free. A plain launcher tap opens the composer with NO recording. This
    // flag carries that intent into the sheet; it is reset every time the sheet
    // closes so the next plain-tap open never inherits a stale auto-record.
    var micSheetAutoStartRecording by remember { mutableStateOf(false) }
    var showSnippetPicker by remember { mutableStateOf(false) }
    // Issue #1207: when a TUI-only slash-command (/model, /config, a picker) is
    // sent from the Conversation composer, the picker opens in the covered
    // alt-screen Terminal and writes NOTHING to the transcript — so the
    // Conversation surface can never show it. Instead of the misleading
    // optimistic bubble + silent nothing, we raise an inline notice holding the
    // command text with a one-tap "Open in Terminal" action. Null when no notice
    // is up. Reset whenever the send target identity changes so a stale notice
    // never bleeds into another session on a switch/recreation.
    var tuiCommandNotice by remember(targetSessionId.value) { mutableStateOf<String?>(null) }
    var showCardFeedSheet by remember(activeSessionCardsTargetKey) { mutableStateOf(false) }
    val sessionCardInteractions = remember(viewModel) {
        object : SessionCardInteractions {
            override fun onToggleChecklistItem(cardId: String, itemId: String, checked: Boolean) {
                viewModel.toggleChecklistItem(
                    cardId = cardId,
                    itemId = itemId,
                    checked = checked,
                )
            }

            override fun onSetNoteRead(cardId: String, read: Boolean) = Unit
        }
    }

    // Issue #560: a share-into-session launch carries staged remote
    // attachment path(s). Seed them into the shared composer VM as #544
    // chips and open the composer focused so the user lands ready to type a
    // message + Send. Keyed on the path list so a re-delivered intent
    // (configuration change) re-seeds correctly; the consume callback clears
    // the activity-side state so a later in-session navigation does not
    // re-open the composer.
    LaunchedEffect(initialComposerAttachments) {
        if (initialComposerAttachments.isNotEmpty()) {
            initialComposerAttachments.forEach { promptComposerViewModel.seedAttachment(it) }
            // Issue #585: a share/attach intent opens the composer to edit + Send,
            // never to auto-record — keep the flag false so no stale swipe-open
            // intent bleeds into this entry.
            micSheetAutoStartRecording = false
            showMicSheet = true
            onInitialComposerAttachmentsConsumed()
        }
    }
    // Issue #763: a review prompt routed from the file viewer's "Attach to
    // current session" action. Seed it into the shared composer draft and open
    // the composer focused so the user lands ready to edit + Send. Keyed on the
    // prompt so the back-navigation that returns to this session re-seeds it;
    // the consume callback clears the activity-side state so a later navigation
    // does not re-seed a stale prompt.
    LaunchedEffect(initialComposerPrompt) {
        if (initialComposerPrompt.isNotBlank()) {
            promptComposerViewModel.seedDraftPrompt(initialComposerPrompt)
            // Issue #585: a routed review-prompt opens the composer to edit + Send,
            // not to auto-record.
            micSheetAutoStartRecording = false
            showMicSheet = true
            onInitialComposerPromptConsumed()
        }
    }
    // Issue #784: whether the dedicated terminal-hotkeys panel is open. It is
    // its OWN bottom-sheet surface (NOT inside the composer, NOT part of the
    // soft keyboard) toggled from the terminal bottom controls; survives
    // recomposition only (a fresh attach starts closed, the right default).
    var showHotkeysPanel by remember { mutableStateOf(false) }
    // Issue #462: a "sticky" last-known agent for the visible pane, so the
    // always-visible "/ commands" header affordance does not flicker out on a
    // transient detection miss. Live agent detection round-trips can briefly
    // report `detection == null` mid-session (a list-panes refresh, a pane-tty
    // reassignment, a re-attach before re-detection completes). If the palette
    // button were gated purely on the live `currentAgentConversation`, it would
    // blink away during those gaps — exactly the "buried / sometimes absent"
    // complaint this issue is about. We therefore remember the last non-null
    // detected agent keyed by the visible `paneId`: the button stays reachable
    // across flicker, and the sticky resets when the user moves to a different
    // pane (a genuinely different shell/window). See the issue notes for the
    // deliberate trade-off (a benign over-show after a real agent exit beats a
    // vanishing affordance, and sending a slash command into a plain shell is
    // harmless).
    val stickyAgentForPane = remember { mutableStateMapOf<String, AgentKind>() }
    // Whenever live detection lands a non-null agent for the visible pane,
    // record it as the sticky value for that pane id. A subsequent transient
    // `detection == null` leaves the recorded value intact, so the header
    // affordance and the palette keep working through the gap.
    val liveAgentForPane = currentDetection?.agent
    // Issue #797: the sticky/palette agent key follows the SURFACE pane so the
    // last-known agent kind sticks to the visible pane (active OR cached),
    // keeping the agent affordances and presumed-agent default reachable across
    // a switch onto a cached pane.
    val currentPaneId = surfacePane?.paneId
    LaunchedEffect(currentPaneId, liveAgentForPane) {
        val paneId = currentPaneId
        if (paneId != null && liveAgentForPane != null) {
            stickyAgentForPane[paneId] = liveAgentForPane
        }
    }
    // The agent used for the "/ commands" palette: the live detection if
    // present, else the sticky last-known agent for this pane (issue #462
    // detection-flicker resilience). Null only when this pane has never been an
    // agent pane in the current attach.
    val paletteAgent: AgentKind? = liveAgentForPane
        ?: currentPaneId?.let { stickyAgentForPane[it] }
    val surfaceTerminalState = surfacePane?.terminalState
    val quickReplyInputEligible = sessionLive &&
        surfaceTerminalState != null &&
        currentSelectedTab != SessionTab.Conversation &&
        tmuxSessionHasPositiveAgentEvidence(
            hasLiveDetection = currentDetection != null,
            hasStickyAgent = paletteAgent != null,
            recordedAgentKind = tmuxSessionRecordedAgentKind(currentSessionRecordedKind),
        )
    // Issue #770: the set of engine slash-commands the terminal should make
    // tappable for the visible pane. Sourced verbatim from [AgentCommandCatalog]
    // for the detected/sticky engine — so only commands that actually exist for
    // THIS engine (e.g. Claude Code's `/clear`) are surfaced, and a plain shell
    // pane (null agent) yields an empty set, disabling the affordance. Tapping
    // one opens the composer pre-filled with it (see [onEngineCommandTap] below).
    val engineCommandSet: Set<String> = remember(paletteAgent) {
        paletteAgent?.let { agent ->
            AgentCommandCatalog.commandsFor(agent).map { it.command }.toSet()
        } ?: emptySet()
    }

    // Issue #796 (H3): STABLE callback instances for the hoisted, skippable
    // [TmuxTerminalPager]. Each is `remember`ed (keyed on its stable captured
    // references) so the SAME instance is passed on every body recomposition. If
    // these were re-allocated each composition (the old inline-pager shape),
    // `TerminalSurface` would be non-skippable and a composer-open
    // (`showMicSheet`) body recomposition would drag the heavy terminal subtree
    // (AndroidView update + viewport scanners, all main-thread) through a
    // recomposition — the exact ANR collision the maintainer pinpointed (bursting
    // Codex pane + opening the Prompt Composer). `viewModel` /
    // `promptComposerViewModel` / `onOpenFile` are stable references and the
    // `showMicSheet` setter is stable, so these lambdas never change identity for
    // the life of the screen — the pager stays skipped across overlay toggles.
    val stableSessionNameForUnifiedPane: (TmuxPaneState) -> String? =
        remember(viewModel) { viewModel::sessionNameForUnifiedPane }
    val stableResizeRemotePty: (Int, Int) -> Unit =
        remember(viewModel) { viewModel::resizeRemotePty }
    val stableReportTerminalSurfaceFailure: (String, Throwable) -> Unit =
        remember(viewModel) {
            { paneId, cause -> viewModel.reportTerminalSurfaceFailure(paneId, cause) }
        }
    val stableRecreateTerminalSurface: (String) -> Unit =
        remember(viewModel) {
            { paneId -> viewModel.recreateTerminalSurface(paneId) }
        }
    val stableFilePathTap: (path: String, paneCwd: String) -> Unit =
        remember(onOpenFile) {
            { path, paneCwd ->
                val cwd = cwdForDetectedFilePath(path, paneCwd)
                onOpenFile(path, cwd)
            }
        }
    val stableEngineCommandTap: (String) -> Unit =
        remember(promptComposerViewModel) {
            { command ->
                promptComposerViewModel.prefillEngineCommand(command)
                showMicSheet = true
            }
        }

    // Issue #716 (Slice A): default to presumed-agent during detection
    // uncertainty so the composer / Conversation tab / agent-aware chips never
    // vanish while detection is slow or null right after attach/switch/send.
    // The agent surface collapses ONLY on a positively-confirmed shell verdict.
    //
    // Issue #894 (Slice C): that confirmed-shell verdict is now WIRED — sourced
    // from the durable per-session recorded `@ps_agent_kind=shell` record (a
    // session PocketShell launched as a plain shell, or one the user manually
    // classified as Shell) via [confirmedShellPaneIds]. This is the trustworthy
    // signal Slice A lacked: it is NOT the old "no agent matched → assume shell"
    // absence, so a foreign / not-yet-classified pane stays presumed-agent (the
    // #878 black-screen cure is intact) while a genuine shell collapses the
    // surface and never flashes the "Loading conversation…" placeholder.
    // See [tmuxSessionPresumedAgent].
    // Issue #797: gate on the SURFACE pane (visible pane), not the active-only
    // `currentPane`. The previous `currentPane != null` short-circuit forced
    // `presumedAgent == false` whenever the user was settled on a cached pane
    // (`currentPane == null`), which emptied the Conversation tab AND — paired
    // with the `surfacePane?.let` composer wrapper below — hid the composer
    // launcher. Keying off the visible pane lets the #716/#744 optimistic
    // default keep the composer/Conversation surface available on a cached pane
    // exactly as it does on a fresh active pane.
    val presumedAgent = surfacePane != null &&
        tmuxSessionPresumedAgent(
            hasLiveDetection = currentDetection != null,
            stickyAgent = paletteAgent,
            // Issue #894 (Slice C): the visible pane's durable confirmed-shell
            // verdict — true only when the tree recorded `@ps_agent_kind=shell`.
            confirmedShell = surfacePane.paneId in confirmedShellPaneIds,
        )
    // The agent kind to use when sending to a presumed-agent pane that has no
    // live detection/transcript yet: the sticky/last-known kind. Null when this
    // pane has never been an agent in the current attach (then a presumed send
    // falls back to raw bytes).
    val presumedAgentKind: AgentKind? = if (currentDetection == null) {
        paletteAgent
    } else {
        null
    }
    val composerSendHandler: suspend (PromptComposerViewModel.SendRequest) -> Boolean = { request ->
        // Issue #548: send is a connect-on-action path. If the control channel
        // dropped while the composer was open, let the ViewModel kick/reuse
        // reconnect and wait for the live client before returning failure to the
        // composer.
        //
        // Issue #797: route the send to the SURFACE (visible) pane, NOT the
        // active-only `currentPane`. The visible pane IS the user's intended
        // session (they are parked on it); the promotion-on-settle path has made
        // it the active runtime's pane, so `paneId`-targeted sends reach it.
        val target = request.sendTarget
        if (target.sessionKey.isNotBlank() && target.sessionKey != targetSessionId.value) {
            false
        } else {
            val paneId = target.paneId.ifBlank { surfacePane?.paneId.orEmpty() }
            val sent = if (paneId.isBlank()) {
                false
            } else when (target.route) {
                OutboundRoute.AgentConversation ->
                    // Issue #1207: a TUI-only slash-command (/model, /config, any
                    // picker) writes NOTHING to the transcript — it drives an
                    // alt-screen picker in the covered Terminal pane. Echoing an
                    // optimistic "/model" user bubble (the old `sendToAgentPaneResult`
                    // path) is actively misleading: the bubble sits there as if a
                    // normal turn ran while the picker is invisible on the
                    // Conversation surface by construction. So for a slash-command we
                    // send the keystrokes to the pane WITHOUT the optimistic echo (the
                    // same raw text+Enter delivery a confirmed agent uses on the
                    // Terminal tab) and raise the inline "Open in Terminal" notice.
                    when (tmuxAgentConversationSend(request.text)) {
                        TmuxAgentConversationSend.TuiCommandNoEcho -> {
                            val ok = viewModel.writeInputToPaneResult(
                                paneId,
                                (request.text.trimEnd('\n') + "\r").toByteArray(Charsets.UTF_8),
                            ).isSuccess
                            if (ok) {
                                tuiCommandNotice = request.text.trim()
                            }
                            ok
                        }
                        TmuxAgentConversationSend.Echo ->
                            viewModel.sendToAgentPaneResult(paneId, request.text).isSuccess
                    }
                OutboundRoute.AgentPayload ->
                    tmuxComposerAgentKindFromToken(target.agentKind)?.let { agentKind ->
                        viewModel.sendAgentPayloadToPaneResult(
                            paneId,
                            request.text,
                            agentKind,
                        ).isSuccess
                    } ?: false
                OutboundRoute.RawBytes -> {
                    val payload = if (request.withEnter) request.text + "\r" else request.text
                    viewModel.writeInputToPaneResult(
                        paneId,
                        payload.toByteArray(Charsets.UTF_8),
                    ).isSuccess
                }
            }
            if (sent) {
                showMicSheet = false
                micSheetAutoStartRecording = false
            }
            sent
        }
    }
    PromptComposerSendDispatcher(
        viewModel = promptComposerViewModel,
        onSend = composerSendHandler,
        onDelivered = {
            showMicSheet = false
            micSheetAutoStartRecording = false
        },
    )

    // Issue #167: intercept system-back so the user returns to the host
    // list instead of exiting the activity. Without this `BackHandler`
    // Android's default activity-back finishes the task — the user-visible
    // symptom on real devices was "tap back from inside a tmux session and
    // the whole app is gone instead of the host list I came from".
    //
    // The actual SSH/tmux teardown happens implicitly: when [onBack] pops
    // the in-app back stack, this composable leaves composition, the
    // hosted `TmuxSessionViewModel` is cleared, and its `onCleared()`
    // runs `closeCurrentConnection()` to cancel coroutines, close the SSH
    // transport, and unregister from `ActiveTmuxClients` (idempotently —
    // coordinates with #151's join-on-close lifecycle so we do not race
    // the event loop here either).
    //
    // The dispatch lives in [TmuxSessionBackHandler] so the back-routing
    // behaviour can be regression-tested without spinning up Hilt + a live
    // tmux client.
    TmuxSessionBackHandler(
        dialogOpen = dialogMode != null,
        sessionDrawerOpen = showSessionSwitcher || showSessionDrawer,
        micSheetOpen = showMicSheet,
        snippetPickerOpen = showSnippetPicker,
        cardFeedSheetOpen = showCardFeedSheet,
        onDismissDialog = { dialogMode = null },
        onDismissSessionDrawer = {
            showSessionSwitcher = false
            showSessionDrawer = false
            sessionPickerViewModel.dismiss()
        },
        onDismissMicSheet = {
            showMicSheet = false
            micSheetAutoStartRecording = false
        },
        onDismissSnippetPicker = { showSnippetPicker = false },
        onDismissCardFeedSheet = { showCardFeedSheet = false },
        onBack = onBack,
    )

    // Issue #131: same root-view handle as `SessionScreen`. The pager
    // renders one pane at a time, so the helper's recursive search lands
    // on the visible pane's `TerminalView`.
    val composeRootView = LocalView.current
    val context = LocalContext.current

    // Issue #993/#1521: the single reconnect-with-feedback action shared by the kebab
    // "Reconnect" item and the disconnected band's always-present Reconnect button.
    // Routes through the VM's single [TmuxSessionViewModel.reconnect]; a false return
    // (no target) gives honest Toast feedback, never a silent no-op dead-end.
    val reconnectWithFeedback: () -> Unit = {
        val started = viewModel.reconnect()
        Toast.makeText(
            context,
            if (started) "Reconnecting…" else "Nothing to reconnect to — reopen the session",
            Toast.LENGTH_SHORT,
        ).show()
    }

    // Issue #488: a tapped server-local (loopback) URL whose remote port is not
    // yet forwarded for this host. Non-null drives the "Forward port N from
    // <host>?" confirm dialog; confirming routes through the existing
    // port-forward flow (#447/#448 prefill), which opens the panel and sets up
    // the tunnel. If this prompt is accepted, the panel opens the working local
    // URL once the tunnel reports its actual device-side port.
    var pendingLocalhostForward by remember {
        mutableStateOf<LocalhostUrl?>(null)
    }

    // Issue #184 Layer 1: when the IME comes up, snap the active
    // pane's terminal viewport back to the bottom so the cursor row
    // (which lives at the latest line of the emulator buffer) is
    // guaranteed to be inside the visible window. Without this, a
    // user who scrolled the transcript up and then opened the
    // keyboard would type into a viewport that no longer shows the
    // prompt they're typing into — the canonical "where am I
    // typing?" failure. We reach the [TerminalView] through the
    // [pinTerminalToBottom] helper which lives in core-terminal next
    // to the show-keyboard helper so the [TerminalView] import does
    // not leak into the app module.
    LaunchedEffect(isImeVisible, surfacePane?.paneId) {
        if (isImeVisible) {
            com.pocketshell.core.terminal.ui.pinTerminalToBottom(
                composeRootView,
                onLocalTerminalError = { cause ->
                    surfacePane?.paneId?.let { paneId ->
                        viewModel.reportTerminalSurfaceFailure(paneId, cause)
                    }
                },
            )
        }
    }

    // Issue #266: route assistant-requested navigation through the app nav.
    LaunchedEffect(viewModel) {
        viewModel.assistantNavRequests.collect { onAssistantNavigate(it) }
    }

    // Issue #989: surface Redraw feedback as a Toast so tapping Redraw with no
    // live client (dropped / reconnecting) tells the user it can't act right now
    // instead of silently doing nothing ("I clicked it three times and nothing
    // happened"). On the happy path Redraw emits NO feedback — it just reseeds.
    LaunchedEffect(viewModel) {
        viewModel.redrawFeedback.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    // Route inline-dictation transcripts into the currently focused pane.
    // The collector re-binds whenever the focused pane or dictation mode
    // changes so we always write into the pane the user is looking at, not
    // some stale pane id captured at first composition. Prompt-mode bytes
    // go straight to the pane via `send-keys`; Command-mode bytes go
    // through the planner for explicit review (mirroring SessionScreen).
    // Issue #797: inline-dictation routes to the SURFACE (visible) pane so a
    // dictation while parked on a settled cached pane reaches the session the
    // user is looking at once promotion makes it active — not a no-op.
    val focusedPaneId = surfacePane?.paneId
    LaunchedEffect(inlineDictationViewModel, dictationMode, focusedPaneId) {
        // Issue #1226: do NOT drain the durable delivery channel while there
        // is no pane to receive the text. The old code collected the flow even
        // when `focusedPaneId` was null and then `return@collect`-discarded the
        // value — so a transcript that resolved during a brief drop/reconnect
        // (pane flipped to null mid Whisper round-trip) was pulled out of the
        // channel and thrown away, silently losing the user's command. By
        // returning early here we leave the transcript buffered in the
        // ViewModel's channel; when the session re-attaches and the pane
        // re-keys to a live id, this effect re-runs and delivers the buffered
        // transcript into the pane the user is now looking at.
        val paneId = focusedPaneId ?: return@LaunchedEffect
        inlineDictationViewModel.transcriptions.collect { text ->
            when (dictationMode) {
                InlineDictationViewModel.DictationMode.Prompt -> {
                    if (text.isNotEmpty()) {
                        viewModel.writeInputToPane(paneId, text.toByteArray(Charsets.UTF_8))
                    }
                }
                InlineDictationViewModel.DictationMode.Command -> viewModel.dictateToAssistant(text, paneId)
            }
        }
    }

    val sessionPickerRequest = remember(hostId, hostName, host, port, user, keyPath, passphrase) {
        HostTmuxSessionPickerRequest(
            host = HostEntity(
                id = hostId,
                name = hostName.ifBlank { host },
                hostname = host,
                port = port,
                username = user,
                keyId = 0L,
            ),
            keyPath = keyPath,
            passphrase = passphrase,
        )
    }

    // Issue #463: keep the project switcher's sibling list fresh from the
    // warm live `-CC` client while the session is connected. Re-runs when the
    // active pane's project path changes (window/pane switch) or the session
    // becomes live, so the dropdown reflects the live session list without an
    // SSH handshake. Cleared on dispose so a destroyed screen doesn't leak a
    // stale list into the singleton-scoped picker VM.
    LaunchedEffect(sessionPickerRequest, projectPath, sessionName, sessionLive) {
        if (sessionLive) {
            sessionPickerViewModel.refreshProjectSiblings(
                host = sessionPickerRequest.host,
                keyPath = sessionPickerRequest.keyPath,
                currentSessionName = sessionName,
                projectPath = projectPath,
            )
        }
    }
    DisposableEffect(Unit) {
        onDispose { sessionPickerViewModel.clearProjectSiblings() }
    }

    fun openTextDialog(
        mode: TmuxDialogMode,
        initialText: String = "",
    ) {
        dialogMode = mode
        dialogText = initialText
    }

    // Issue #898: the single entry point for opening the rich in-session
    // "+ New session" SessionTypePickerSheet. ALL three triggers (kebab,
    // session switcher, session drawer) MUST go through this so they are
    // provably identical and can't drift (the switcher/drawer used to
    // `dismiss()` the picker and open the sheet WITHOUT reloading, leaving
    // `sessionPickerState` Idle so the deriver saw no known names and a
    // same-folder second session collided onto the existing one via
    // `new-session -A` — the reviewer's Blocker A). This helper:
    //  1. refreshes the host-discovered agent profiles so the Profile
    //     selector matches the host screen, and
    //  2. (re)loads the host's live tmux session list so `sessionPickerState`
    //     carries the already-known names by the time the user taps Create —
    //     the deriver needs those to give a same-folder second session a
    //     deterministic `-2`/`-3` suffix instead of colliding. The host screen
    //     gets these names from its always-loaded FolderListUiState; the
    //     in-session screen must trigger the load itself.
    fun openNewSessionSheet() {
        viewModel.fetchProfilesForActiveSession()
        sessionPickerViewModel.load(sessionPickerRequest)
        showNewSessionSheet = true
    }

    // Issue #782: when a `[wN]` switcher entry is tapped, the host tree
    // routes here with [initialWindowIndex] set to that externally-created
    // window's tmux index. We scroll the warm unified pager straight to the
    // matching pane — no reconnect, no server-side window management — so the
    // attach renders THAT window's content instantly (the warm-lease /
    // instant-switch contract, D21 / #636 / #687). PocketShell never creates,
    // switches, renames, or closes tmux windows itself (#782 hard-cut); it only
    // re-presents windows that already exist on the server.
    var consumedInitialWindowTarget by remember(sessionName, initialWindowIndex) { mutableStateOf(false) }
    LaunchedEffect(unifiedPanes, initialWindowIndex, consumedInitialWindowTarget) {
        val requestedIndex = initialWindowIndex ?: return@LaunchedEffect
        if (consumedInitialWindowTarget) return@LaunchedEffect
        val targetPane = unifiedPanes.firstOrNull { it.windowIndex == requestedIndex } ?: return@LaunchedEffect
        consumedInitialWindowTarget = true
        // Issue #626: search the unified pane list.
        val page = unifiedPanes.indexOfFirst { it.windowId == targetPane.windowId }
        if (page >= 0) {
            pagerState.scrollToPage(page)
        }
    }

    // Issue #626: when the unified pager settles on a pane from a
    // different session, trigger the warm switch via onReplaceTmuxSession.
    LaunchedEffect(Unit) {
        viewModel.sessionSwitchRequest.collect { targetSessionName ->
            onReplaceTmuxSession(targetSessionName)
        }
    }

    // Issue #666: the cold-restore attach found the persisted last session
    // gone (killed elsewhere while backgrounded). Drop to the host/session
    // list instead of resurrecting it. MainActivity wires onSessionEnded to
    // also clear the persisted last-session snapshot so the next foreground
    // does not retry the same gone session.
    LaunchedEffect(Unit) {
        viewModel.sessionEnded.collect { endedSessionName ->
            onSessionEnded(endedSessionName)
        }
    }

    // Issue #976: surface a refused new-session LAUNCH (e.g. the gateway's
    // name-collision guard) so it fails VISIBLY. The launch line was NOT typed
    // into the current pane and no navigation happened; a toast tells the user
    // why nothing opened so they can retry once the session list is known.
    LaunchedEffect(Unit) {
        viewModel.sessionCreateError.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    TmuxUnifiedPagerSettleEffects(
        pagerState = pagerState,
        unifiedPanes = unifiedPanes,
        sessionName = sessionName,
        terminalHeld = terminalHeld,
        viewModel = viewModel,
    )

    @Composable
    fun AnchoredTmuxMoreMenu() {
        TmuxMoreMenu(
            expanded = moreExpanded,
            forwardingState = sessionForwardingState,
            onDismiss = { moreExpanded = false },
            onCreateSession = {
                moreExpanded = false
                // Issue #898: open the rich SessionTypePickerSheet (same as the
                // host screen) instead of the old name+folder dialog, via the
                // shared helper that also loads the host's known session names
                // so a same-folder second session disambiguates (see
                // openNewSessionSheet).
                openNewSessionSheet()
            },
            onRenameSession = {
                moreExpanded = false
                openTextDialog(TmuxDialogMode.RenameSession, sessionName)
            },
            onKillSession = {
                moreExpanded = false
                dialogMode = TmuxDialogMode.StopSession
            },
            onChangeKind = {
                moreExpanded = false
                // Re-read fresh before opening so the picker reflects the
                // current host option (e.g. after an out-of-band change).
                viewModel.refreshCurrentSessionRecordedKind()
                showKindPicker = true
            },
            changeKindIsUnknown = currentSessionRecordedKind == null,
            onSwitchSession = {
                moreExpanded = false
                showSessionSwitcher = true
            },
            onOpenJobs = {
                moreExpanded = false
                onOpenJobs()
            },
            onOpenUsage = {
                moreExpanded = false
                onOpenUsage()
            },
            onOpenSettings = {
                moreExpanded = false
                onOpenSettings()
            },
            onOpenPortForwarding = {
                moreExpanded = false
                onOpenPortForwarding()
            },
            onOpenFile = {
                moreExpanded = false
                openFilePath = ""
                showOpenFileDialog = true
            },
            onBrowseFiles = {
                moreExpanded = false
                // Seed the explorer at the active pane's cwd; `~` when unknown
                // so the remote shell expands it to the login home.
                val paneCwd = currentPane?.cwd?.takeIf { it.isNotBlank() }
                onBrowseFiles(paneCwd ?: "~")
            },
            onDetach = {
                // Issue #235: detach the tmux `-CC` client
                // server-clean and pop back to the sessions
                // dashboard. The session stays alive on the
                // remote; reattach via the normal sessions
                // list. We close the menu first so the back
                // navigation animates from a clean state.
                moreExpanded = false
                viewModel.detachAndExit()
                onBack()
            },
            onRedraw = {
                // Issue #892: close the menu, then force a full-viewport reseed of the
                // active pane over the warm session. No reconnect/detach/new lease — the
                // VM reuses the #553/#879 full-reseed path (capture-pane -> full repaint).
                moreExpanded = false
                viewModel.redrawActivePane()
            },
            onReconnect = {
                // Issue #993: close the menu, then reconnect THIS session in place via
                // the shared [reconnectWithFeedback] action — the SAME path a session
                // switch uses. On success the #900 outbound queue auto-flushes.
                moreExpanded = false
                reconnectWithFeedback()
            },
            // Issue #993: only actionable when there IS a target to reconnect to AND a
            // connect/reconnect is not already in flight — see [reconnectKebabEnabled].
            reconnectEnabled = reconnectKebabEnabled(canReconnect, surfaceState),
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(color = PocketShellColors.Background)
            .testTag(TMUX_SESSION_SCREEN_TAG),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize(),
            // Issue #887: the terminal column stays FIXED when the soft keyboard
            // shows — NEITHER resized NOR panned. The activity window is set to
            // `SOFT_INPUT_ADJUST_NOTHING` (see [MainActivity.onCreate]) so the OS
            // never pans/resizes the window, and we no longer apply the in-app
            // `graphicsLayer { translationY = imeLayout.panOffsetPx() }` pan that
            // #457 used. The keyboard simply OVERLAYS the bottom rows of the
            // terminal (the maintainer's accepted trade-off in #887), and the
            // composer (`PromptComposerSheet`, its own dialog window) is what
            // floats above the keyboard. `imeLayout.isImeVisible` is still read
            // (below) to collapse the top chrome / drive the KeyBar while typing,
            // but the terminal grid + the embedded `TerminalView` never move,
            // preserving the #457 no-resize invariant (no `updateSize()` / tmux
            // pane resize / reflow) AND adding the #887 no-pan invariant.
        ) {
            // Issue #796 (H4): count recompositions of the terminal-render
            // COLUMN subtree (this Column hosts the top chrome, the
            // `HorizontalPager` of `TerminalSurface`s, and the bottom controls —
            // the heavy render-critical subtree). The H4 regression proof asserts
            // a soft-keyboard show animation (a burst of interpolated IME inset
            // frames) does NOT recompose this subtree on each frame. On the
            // pre-fix delegated `val imeBottomPx by rememberHostImeBottomPx()`
            // read the whole TmuxSessionScreen body — this Column included —
            // re-ran once per inset frame; the deferred-read fix keeps it off the
            // per-frame inset-invalidation path.
            // Issue #256: gate the inline Conversation tab on the
            // currently visible pane only. A sibling pane/window's agent
            // no longer keeps Conversation mounted or routes sends away
            // from what the user is looking at.
            // Issue #1085 (freeze F3 / R2): derive `tabState` via `derivedStateOf`
            // rather than reading the surface pane's conversation directly here.
            // `tmuxSessionTabState` only consults `events.isNotEmpty()` (flips
            // once), the placeholder flags, detection and selectedTab — all
            // stable mid-stream — so deriving it keeps the body's tab-bar chrome
            // OFF the 60ms agent-streaming flush. The projection re-runs each
            // flush but the resulting [TmuxSessionTabState] is structurally equal,
            // so the body is not invalidated.
            // Issue #1158: the active session's RECORDED agent kind
            // (`@ps_agent_kind`) AND the sticky alt-buffer agent signal are both
            // folded into [rememberTmuxSessionTabState], an extracted @Composable,
            // so the `recordedAgentKind` projection, the `altBufferAgentPaneIds`
            // collectAsState, the `altBufferAgent` derivation and the
            // `derivedStateOf` remember all live in THAT small method's register
            // frame instead of this enormous body. That is the R2 fix: the extra
            // locals had inflated [TmuxSessionScreen]'s method past ART's dex
            // verifier register limit (`v273` VerifyError) and crashed the session
            // screen at class load. Same recomposition semantics as the previous
            // inline block — the returned [State] is read via `by` here so the
            // derived-state read stays attributed to this caller's restart scope.
            val tabState by rememberTmuxSessionTabState(
                viewModel = viewModel,
                surfaceConversationPaneId = surfaceConversationPaneId,
                presumedAgent = presumedAgent,
                currentSessionRecordedKind = currentSessionRecordedKind,
                agentConversationsState = agentConversationsState,
            )
            val onTabSelected: (Int) -> Unit = { index ->
                if (index == 1) {
                    // Issue #778: honour a Conversation tap whenever the tab is
                    // shown — i.e. on a presumed-agent pane (#716), not only once
                    // live detection has landed. The previous `detection != null`
                    // gate made the tap a no-op during the slow-detection window
                    // (the tab was visible but inert). The ViewModel records the
                    // intent on a detection-less row and the render branch below
                    // shows a "waiting for agent" placeholder until the transcript
                    // seeds. Gated on `showsConversationTab` so a confirmed shell
                    // (no tab) can never reach this path.
                    // Issue #797: record the tab choice on the SURFACE pane so a
                    // Conversation tap on a settled cached presumed-agent pane is
                    // honoured (the row's `selectedTab` drives the placeholder /
                    // transcript via the surface-keyed `currentAgentConversation`).
                    surfacePane
                        ?.takeIf { tabState.showsConversationTab }
                        ?.let { pane ->
                        viewModel.selectSessionTab(pane.paneId, SessionTab.Conversation)
                    }
                } else {
                    surfacePane?.let { pane ->
                        viewModel.selectSessionTab(pane.paneId, SessionTab.Terminal)
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalSwipeInput(
                        thresholdPx = verticalSwipeThresholdPx,
                        onBoundary = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                        onSwipeDown = { showSessionSwitcher = true },
                    ),
            ) {
                // Issue #189 / #192 / #303: the IME-down chrome is a
                // stable 56dp header row (back + status + session crumb
                // + inline Terminal/Conversation pill + kebab) followed
                // only by the per-window navigation strip when multiple
                // windows exist. The toggle stays per-window in state,
                // but it renders inside the fixed toolbar row so agent
                // sessions do not pay for a dedicated tab row.
                //
                // Issue #184 Layer 2 continues to apply on top: the
                // IME-up state collapses the whole stack to
                // [CompactBreadcrumb] (40dp, session-name only) so the
                // user has even more room while typing.
                Column(modifier = Modifier.fillMaxWidth()) {
                    AnimatedVisibility(
                        visible = !chromeCompressed,
                        enter = expandVertically(
                            animationSpec = tween(durationMillis = MotionDurationMs, easing = MotionEasing),
                        ) + fadeIn(
                            animationSpec = tween(durationMillis = MotionDurationMs, easing = MotionEasing),
                        ),
                        exit = shrinkVertically(
                            animationSpec = tween(durationMillis = MotionDurationMs, easing = MotionEasing),
                        ) + fadeOut(
                            animationSpec = tween(durationMillis = MotionDurationMs, easing = MotionEasing),
                        ),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            ConsolidatedTopChrome(
                                sessionName = sessionName,
                                // Issue #481: surface the detected agent/model
                                // name as the header title when the visible
                                // pane has a conversation (mockup shows
                                // `claude-3-5-sonnet`), else fall back to the
                                // session name.
                                // Issue #661: during a cross-session switch the
                                // header must NEVER wear the LEAVING session's
                                // identity for even one frame. `sessionName` is
                                // already the TARGET (the nav `replace()` updated
                                // it before connect runs), but `agentName` is
                                // derived from the currently-rendered pane's
                                // detected agent — which is the leaving session's
                                // until the new pane seeds. Suppress it while the
                                // switch is hiding the terminal so the header
                                // shows the target session name (a neutral label),
                                // never the old agent's display name.
                                agentName = if (terminalHeld) {
                                    null
                                } else {
                                    currentDetection?.agent?.displayName
                                },
                                tabLabels = tabState.labels,
                                selectedTabIndex = tabState.selectedIndex,
                                onTabSelected = onTabSelected,
                                pulseConversationTab = tabState.showsConversationTab,
                                onBack = onBack,
                                onMore = { moreExpanded = true },
                                moreMenu = { AnchoredTmuxMoreMenu() },
                                // Issue #463: the tappable project crumb +
                                // sibling-session dropdown. Refresh the warm
                                // sibling list on open, and route a selection
                                // through the same warm same-host switch the
                                // session-switcher overlay uses.
                                projectLabel = projectLabel,
                                projectSwitcher = projectSwitcherState,
                                onProjectSwitcherOpen = {
                                    sessionPickerViewModel.refreshProjectSiblings(
                                        host = sessionPickerRequest.host,
                                        keyPath = sessionPickerRequest.keyPath,
                                        currentSessionName = sessionName,
                                        projectPath = projectPath,
                                    )
                                },
                                onSwitchToSibling = { selected ->
                                    handleTmuxSessionSelection(
                                        currentSessionName = sessionName,
                                        selectedSessionName = selected,
                                        onDismiss = {},
                                        onReplace = onReplaceTmuxSession,
                                    )
                                },
                                connectionStatus = surfaceState.toUiStatus(),
                                modifier = Modifier.testTag(TMUX_FULL_BREADCRUMB_TAG),
                            )
                            // Issue #782: PocketShell no longer manages tmux
                            // windows. The in-session window-tab row (WindowStrip)
                            // is gone — windows created outside PocketShell are
                            // surfaced as separate `[wN]` switcher entries in the
                            // host tree instead, so the only in-session tab
                            // dimension is Terminal/Conversation.
                        }
                    }
                    AnimatedVisibility(
                        visible = chromeCompressed,
                        enter = expandVertically(
                            animationSpec = tween(durationMillis = MotionDurationMs, easing = MotionEasing),
                        ) + fadeIn(
                            animationSpec = tween(durationMillis = MotionDurationMs, easing = MotionEasing),
                        ),
                        exit = shrinkVertically(
                            animationSpec = tween(durationMillis = MotionDurationMs, easing = MotionEasing),
                        ) + fadeOut(
                            animationSpec = tween(durationMillis = MotionDurationMs, easing = MotionEasing),
                        ),
                    ) {
                        CompactBreadcrumb(
                            sessionName = sessionName,
                            onBack = onBack,
                            onMore = { moreExpanded = true },
                            moreMenu = { AnchoredTmuxMoreMenu() },
                            connectionStatus = surfaceState.toUiStatus(),
                            modifier = Modifier.testTag(TMUX_COMPACT_BREADCRUMB_TAG),
                        )
                    }
                }
            }

            // Issue #750 (4th occurrence): the top under-header connecting /
            // reconnecting banner region. Both banners are routed through the
            // single [primaryLoadingSurface] reducer so neither can ever stack on
            // top of the centered "Attaching…" hold painted by the surface Box
            // below (the maintainer's recurring "two loaders at once" symptom). See
            // [TmuxTopConnectingBanner] for the per-banner rationale.
            TmuxTopConnectingBanner(
                surfaceState = surfaceState,
                // Issue #1322/#1326: suppress the top banner whenever the surface
                // owns the primary indicator — the "Attaching…" hold, the "waiting
                // for tmux panes…" ring, OR the calm failure placeholder — so a state
                // is never left with two loaders. Derived from the ONE state.
                surfaceOwnsPrimary = surfaceOwnsPrimary,
                sessionName = sessionName,
                onCancelConnect = { viewModel.cancelConnect() },
                onRetryNow = { viewModel.reconnect() },
            )
            // Issue #145/#1326: render a user-facing error band when the session has
            // SETTLED into an honest failure ([SessionSurfaceState.Failed]). The band
            // reads the TYPED [FailureReason] and maps it to ONE calm curated
            // sentence — never a raw exception string. The test tags
            // [TMUX_SESSION_ERROR_TAG] and [TMUX_SESSION_RECONNECT_TAG] make the
            // elements grep-able from connected disconnect+reconnect tests.
            //
            // Issue #1362 (v0.4.25 release blocker): the band is rendered by the
            // dedicated [SessionFailureBand] sub-composable rather than inline here.
            // The #1344 endpoint/message computation (the nested
            // `disconnectEndpointLabel(user, host, port)` -> `failureReasonSentence`
            // call) used to live inline in THIS mega-composable, which tipped
            // TmuxSessionScreen's dex register frame into the API-33 ART verifier's
            // v256+ wide-operand danger zone — a `move-object/16` of a MutableState
            // from a v256+ register that the verifier rejected (VerifyError, crashing
            // AppNavigator on every session-screen open). Hoisting the computation into
            // its own composable frame keeps the #1344 "Disconnected from <endpoint>"
            // wording verbatim while dropping the mega-method's register pressure. See
            // [SessionFailureBand].
            SessionFailureBand(
                surfaceState = surfaceState,
                user = user,
                host = host,
                port = port,
                // Issue #1521: the band's prominent Reconnect button is ALWAYS shown so
                // the disconnected state never dead-ends with "nowhere to tap". It routes
                // to the SAME shared [reconnectWithFeedback] action the kebab uses.
                onReconnect = reconnectWithFeedback,
            )
            // Per [D6]: render exactly one pane at a time. The
            // HorizontalPager renders only the visible page eagerly by
            // default; sibling panes are pre-loaded into the off-screen
            // pages but kept lightweight because each TerminalSurface
            // owns its own (already-attached) TerminalSurfaceState.
            //
            // Issue #256: Conversation is current-pane-only. If the
            // visible pane has no detected agent, this falls back to the
            // terminal pager with no cross-window follow or explanation
            // banner.
            // Issue #797: the conversation-row lookup follows the SURFACE pane
            // (visible pane) so a Conversation-tab tap / "waiting for agent"
            // placeholder is honoured on a settled cached pane too, not only on
            // an active pane. The heavyweight transcript ([showConversation])
            // still additionally requires the pane to be ACTIVE (`currentPane`)
            // because a real transcript only exists once the warm switch
            // promoted the pane and detection seeded on the active runtime.
            // Whether to render the Conversation *content* (real transcript) in
            // place of the terminal pager. This is the actual content swap,
            // distinct from whether the Conversation *tab* exists (#716
            // presumed-agent — see [tmuxSessionTabState]). Routing is centralised
            // in [tmuxSessionConversationSurface] (#1057) so a Conversation tap on
            // a pane whose agent was mis-classified as a shell still surfaces the
            // existing transcript (or the loading placeholder) instead of leaving
            // the user stuck on the Terminal — the maintainer's "can't see
            // conversation" symptom. Transcript still mounts only for the ACTIVE
            // `currentPane` (preserves the #797 active-runtime gate and the #605
            // Conversation→Terminal swap latch); it now also fires for an existing
            // events transcript whose detection is currently null (an agent that
            // exited / a recorded-shell pane), not solely live detection.
            //
            // Issue #1085 (freeze F3 / R2): the routing inputs come from the
            // STABLE [surfaceChrome] derivation, NOT a direct read of the surface
            // pane's conversation here. Routing only depends on selectedTab,
            // whether detection exists, and whether ANY events exist — all stable
            // mid-stream — so computing it from `surfaceChrome` keeps this body
            // group OFF the 60ms agent-streaming flush. The actual `events` LIST
            // is read inside `surfaceContent` (its own restart scope) for the
            // transcript render below.
            val conversationSurface = tmuxSessionConversationSurface(
                showsConversationTab = tabState.showsConversationTab,
                isActivePane = currentPane != null,
                hasSurfacePane = surfacePane != null,
                selectedTab = currentSelectedTab,
                hasDetection = currentDetection != null,
                hasEvents = surfaceChrome.hasEvents,
            )
            // `surfaceChrome.exists` mirrors the old `visibleConversation != null`
            // guard (a row exists for the surface pane) so the Transcript branch
            // is only chosen when there is a real conversation row to render.
            val showConversation = surfaceChrome.exists &&
                conversationSurface == TmuxConversationSurface.Transcript
            // Issue #778/#1057: a lightweight "Loading conversation…" / Failed
            // placeholder when the user opened the Conversation tab on a pane with
            // no transcript yet (detection null AND no events). No longer gated on
            // `presumedAgent` — a confirmed-shell pane the user deliberately
            // switched to Conversation must show this placeholder (with a way back
            // to Terminal), not silently render the Terminal.
            val showConversationPlaceholder =
                conversationSurface == TmuxConversationSurface.Placeholder
            // Issue #605: hold the heavyweight terminal AndroidView re-attach
            // for exactly one frame on the Conversation → Terminal edge so the
            // leaving conversation pane's selection-toolbar/focus teardown
            // (ConversationInteractionCleanupEffect's onDispose) does not
            // contend with the embedded TerminalView's input-connection attach
            // in the same frame — the residual same-frame race behind the
            // post-transcript-interaction switch hang.
            val deferTerminalAttachForSwap by rememberConversationToTerminalSwapLatch(
                showConversation = showConversation,
            )
            // Issue #488 / #557: shared URL-tap routing — a server-local
            // (loopback) link goes through the port-forward flow; a real-host
            // link opens in the browser. Used by both the Terminal surface and
            // the Conversation link-tap sink so the two tabs behave identically.
            //
            // Issue #796 (H3): `remember`ed so the SAME lambda instance is fed to
            // [TmuxTerminalPager] across body recompositions. A fresh lambda each
            // composition would make `TerminalSurface` non-skippable, so toggling
            // the composer (`showMicSheet`) would recompose the terminal subtree —
            // the exact main-thread work this slice removes. Keyed on the stable
            // captured references; `context`, the VM, and the `pendingLocalhostForward`
            // setter are all stable.
            val handleUrlTap: (String) -> Unit = remember(
                context,
                sessionForwardingIndicatorViewModel,
                hostId,
            ) {
                { url: String ->
                    val local = com.pocketshell.core.terminal.selection
                        .classifyLocalhostUrl(url)
                    if (local == null) {
                        DiagnosticEvents.record(
                            "action",
                            "open_url",
                            "mode" to "tmux",
                            "kind" to "external",
                        )
                        com.pocketshell.core.terminal.ui
                            .openUrlWithFallback(context, url)
                    } else {
                        val localPort = sessionForwardingIndicatorViewModel
                            .forwardedLocalPortFor(hostId, local.remotePort)
                        if (localPort != null) {
                            DiagnosticEvents.record(
                                "action",
                                "open_url",
                                "mode" to "tmux",
                                "kind" to "localhost_forwarded",
                                "remotePort" to local.remotePort,
                            )
                            com.pocketshell.core.terminal.ui
                                .openUrlWithFallback(context, local.toLocalUrl(localPort))
                        } else {
                            DiagnosticEvents.record(
                                "action",
                                "open_url",
                                "mode" to "tmux",
                                "kind" to "localhost_needs_forward",
                                "remotePort" to local.remotePort,
                            )
                            pendingLocalhostForward = local
                        }
                    }
                }
            }
            // Issue #823 (Slice 1): a pull-down on the session/terminal surface
            // is a discoverable manual-reconnect affordance that mirrors the
            // session-tree's pull-to-refresh (FolderListScreen's
            // [PullToRefreshBox]). It calls the EXISTING reconnect entrypoint
            // ([TmuxSessionViewModel.reconnect]) — it adds NO new connection
            // logic and is NOT a second writer on the reconnect path (D28).
            //
            // The gesture is scoped to non-Connected states only (`!sessionLive`
            // + `canReconnect`). On a live (`Connected`) session the terminal
            // surface is a gesture-hungry Termux `TerminalView` in a
            // `HorizontalPager` (scrollback, selection, horizontal paging);
            // wrapping the live surface in a pull-to-refresh would fight those
            // gestures. While not live the surface shows only a static
            // placeholder (Attaching… / waiting / empty), so there is no live
            // terminal to compete with — and "pull to recover a dropped session"
            // matches the maintainer's mental model. When live, the surface
            // content renders directly with no pull wrapper.
            //
            // NOTE (Slice 2 / #822): wiring the gesture to today's `reconnect()`
            // improves discoverability + recovers the common transient-drop
            // case, but it does NOT by itself break the #822 wedge — a wedged
            // in-flight `connectJob` makes `reconnect()` no-op at the dedup
            // guard. Breaking the wedge is connection-core logic owned by epic
            // #792 (Slice 2); this slice deliberately does not touch it.
            val pullToReconnectActive = !sessionLive && canReconnect
            // Issue #750 (3rd occurrence) / #1322 (screenshot B): the surface
            // content paints the centered loader in TWO shapes — the "Attaching…"
            // [SwitchingLoadingPlaceholder] while the terminal is held, and the
            // "waiting for tmux panes…" [EmptyPanesPlaceholder] ring when the reveal
            // is live but no panes have arrived yet. EITHER is the SOLE indicator for
            // its state, so the pull-to-reconnect wrapper below must NOT also run its
            // own [PullToRefreshBox] spinner on top of it (the maintainer's reported
            // cyan ring + gray spinner-in-a-chip). The pull GESTURE stays mounted —
            // only the duplicate box spinner is suppressed — so #823's
            // pull-to-reconnect is preserved. [surfaceShowsCenteredLoader] is
            // computed once at the top of the screen (broadened for #1322 to cover
            // the waiting-for-panes ring, and false for the calm failure placeholder
            // which carries no spinner).
            // The surface content (terminal pager / conversation / placeholders)
            // is captured once so it can render either inside the pull-to-reconnect
            // wrapper (not live) or bare (live) without duplicating the tree.
            val surfaceContent: @Composable () -> Unit = {
                // Issue #810 (regression of #807): the terminal-render pager is
                // kept MOUNTED underneath the Conversation content for a live,
                // panes-present session — it is no longer REPLACED by the
                // conversation. After #807 made a detected agent default to the
                // Conversation view, the agent session never mounted a
                // TerminalView again on a switch-back (the conversation pane took
                // the whole Box), so the embedded Termux `TerminalView` was absent
                // from the view tree. That broke the load-bearing #810 multi-
                // session switch journey: its switch-landing confirmation reads the
                // attached terminal's transcript, and with no TerminalView it hung
                // (and the user lost the warm terminal surface behind the agent
                // view entirely). Keeping the pager mounted and drawing the
                // Conversation content ON TOP of it preserves the #807 black-screen
                // fix (the user still SEES the parsed conversation, the raw terminal
                // is covered) while the terminal surface stays warm + attached. This
                // also makes the #605 Conversation→Terminal swap race structurally
                // impossible (the terminal never unmounts/re-attaches across the
                // tab swap), and #796's skippable-pager guarantee is unchanged (the
                // pager's inputs are still all stable, so an overlay-visibility
                // toggle still skips it).
                //
                // `terminalHeld` still takes precedence and paints ONLY
                // the switch placeholder: during a cross-session switch not a single
                // frame of the leaving session's terminal (or conversation) may leak
                // (#661 / EPIC #687 P1).
                val keepTerminalMounted = !terminalHeld &&
                    !deferTerminalAttachForSwap &&
                    unifiedPanes.isNotEmpty()
                if (keepTerminalMounted) {
                    // Issue #796 (REOPENED): the #679 session-tree agentKind signal
                    // for the visible pane. The raw Terminal surface skips its four
                    // per-frame viewport affordance scanners for an agent pane (the
                    // Codex `%output` keyboard-up ANR cost); affordances live in the
                    // Conversation view (#809/#818). Same `tmuxSessionIsAgentPane`
                    // signal that drives the agent chips below — a stable Boolean, so
                    // the pager stays skippable across overlay toggles (#796 H3).
                    val terminalIsAgentPane = tmuxSessionIsAgentPane(
                        hasLiveDetection = currentDetection != null,
                        presumedAgent = presumedAgent,
                    )
                    TmuxTerminalPager(
                        unifiedPanes = unifiedPanes,
                        pagerState = pagerState,
                        sessionName = sessionName,
                        terminalKeyboardMode = appSettings.terminalKeyboardMode,
                        engineCommands = engineCommandSet,
                        isAgentPane = terminalIsAgentPane,
                        sessionNameForUnifiedPane = stableSessionNameForUnifiedPane,
                        onTerminalSizeChanged = stableResizeRemotePty,
                        onSurfaceError = stableReportTerminalSurfaceFailure,
                        onRecreateSurface = stableRecreateTerminalSurface,
                        onUrlTap = handleUrlTap,
                        onFilePathTap = stableFilePathTap,
                        onEngineCommandTap = stableEngineCommandTap,
                    )
                }
                // Issue #1085 (freeze F3 / R2): read the surface pane's FULL
                // conversation — including the high-frequency `events` list — HERE,
                // inside `surfaceContent`. This lambda is a non-inline
                // `@Composable () -> Unit` passed as `content` to
                // [SessionSurfaceReconnectWrapper], so it is its OWN Compose restart
                // group: subscribing to the 60ms agent-streaming flush at THIS read
                // recomposes ONLY the transcript subtree, never the
                // `TmuxSessionScreen` body (chrome/composer/terminal). The body
                // reads only the STABLE [surfaceChrome] projection (above). Within a
                // single recomposition this read sees the same snapshot the chrome
                // derivation did, so `showConversation` ⇒ a non-null row (the
                // `!= null` guards below are for the compiler's smart-cast).
                val visibleConversation =
                    surfaceConversationPaneId?.let { agentConversationsState.value[it] }
                if (surfaceCalmFailure) {
                    // Issue #1322: the terminal is held AND the connection has
                    // SETTLED into a failure — a HARD reveal failure (target Gone, or
                    // an Error whose retry is exhausted) OR a [ConnectionStatus.Failed]
                    // status. Painting the centered "Attaching…" spinner over a dead
                    // session is the #1321/#1320-screenshot-A desync (a spinner that
                    // will never resolve, contradicting the "Disconnected" pill and
                    // the "Tap to reconnect" band). Render the calm failed placeholder
                    // instead — NO spinner — so the surface AGREES with the pill and
                    // the single [FailedConnectionRow] band above.
                    RevealFailurePlaceholder()
                } else if (terminalHeld) {
                    // Issue #661 / EPIC #687 P1: a cross-session switch is in flight —
                    // never paint the leaving session's terminal (or its agent
                    // conversation). Show a compact "Attaching" loading state until
                    // the target session's pane is revealed. Under the NEW connection
                    // path this is driven by the id-keyed RevealStateMachine (held
                    // until RevealState.Live for THIS target); under OLD it is the
                    // inline `switchHidesTerminal` flag. Takes precedence over the
                    // conversation / pager branches so not a single frame of the
                    // previous session can leak.
                    SwitchingLoadingPlaceholder()
                } else if (deferTerminalAttachForSwap) {
                    // Issue #605: one-frame hold on the Conversation → Terminal
                    // edge. The conversation pane has just been disposed (its
                    // selection-toolbar/focus teardown runs this frame); paint
                    // the lightweight placeholder so the terminal AndroidView
                    // attaches on the NEXT frame, never sharing a frame with the
                    // teardown. The latch self-clears after one frame.
                    SwitchingLoadingPlaceholder()
                } else if (showConversation &&
                    visibleConversation != null &&
                    visibleConversation.events.isEmpty() &&
                    visibleConversation.loadState == ConversationLoadState.Loading
                ) {
                    // Issue #793: detection landed but the first-paint tail read
                    // is still in flight and no events are in yet — show
                    // "Loading conversation…" instead of a misleading
                    // "No conversation events yet." for a transcript that DOES
                    // have history. The pane takes over the instant the tail
                    // seeds (or the load resolves Empty/Failed below).
                    ConversationDetectingPlaceholder(
                        loadState = ConversationLoadState.Loading,
                    )
                } else if (showConversation &&
                    visibleConversation != null &&
                    visibleConversation.events.isEmpty() &&
                    visibleConversation.loadState == ConversationLoadState.Failed
                ) {
                    // Issue #793: the initial load could not complete — a clear
                    // terminal error with Retry, never an infinite spinner.
                    val paneIdForSend = currentPane!!.paneId
                    ConversationDetectingPlaceholder(
                        loadState = ConversationLoadState.Failed,
                        onRetry = { viewModel.retryAgentConversationLoad(paneIdForSend) },
                    )
                } else if (showConversation && visibleConversation != null) {
                    val paneIdForSend = currentPane!!.paneId
                    // Issue #842: bind the image loader to THIS host + the active
                    // pane cwd (a relative pasted path resolves where the agent
                    // worked). Built off the same credentials the screen holds so
                    // the borrow reuses the warm lease (D21, no new connection).
                    val conversationPaneCwd = currentPane!!.cwd.takeIf { it.isNotBlank() }
                    val conversationImageLoader = remember(
                        hostId, host, port, user, keyPath, conversationPaneCwd,
                    ) {
                        conversationImageViewModel.loaderFor(
                            target = com.pocketshell.app.sessions.LeaseSessionTarget(
                                hostId = hostId,
                                hostname = host,
                                port = port,
                                username = user,
                                keyPath = keyPath,
                                passphrase = passphrase,
                            ),
                            cwd = conversationPaneCwd,
                        )
                    }
                    // Issue #459: the Conversation pane is now read-only
                    // chrome — search field + conversation feed. Sending is
                    // owned by the shared unified composer band below (the
                    // same [PromptComposerSheet] the Terminal uses), so the
                    // pane no longer hosts a bespoke "Message …" field. The
                    // composer's mic FAB opens the sheet; its Send routes to
                    // the focused agent pane via the screen's `onSend`
                    // wiring (see the [PromptComposerSheet] call below).
                    androidx.compose.runtime.CompositionLocalProvider(
                        LocalConversationImageLoader provides conversationImageLoader,
                    ) {
                    TmuxConversationPane(
                        events = visibleConversation.events,
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag(TMUX_CONVERSATION_PANE_TAG),
                        showSystemNotes = appSettings.showSystemNotes,
                        paneId = paneIdForSend,
                        syncStatus = visibleConversation.syncStatus,
                        // Issue #793: tail-first paging. The pane fires this when
                        // the user scrolls near the top; the VM widens the loaded
                        // window and prepends older messages, preserving position.
                        hasMoreOlderEvents = visibleConversation.hasMoreOlderEvents,
                        isPagingOlder = visibleConversation.isPagingOlder,
                        onLoadOlderEvents = {
                            viewModel.loadOlderAgentConversationEvents(paneIdForSend)
                        },
                        onRetryAgentStream = {
                            viewModel.retryAgentConversationStreamForPane(paneIdForSend)
                        },
                        // Issue #494: retry a failed optimistic send.
                        onRetryFailedSend = { id ->
                            viewModel.retryFailedAgentSend(paneIdForSend, id)
                        },
                        // Issue #557: a path/dir/URL tapped in a message body is
                        // routed here (parity with the Terminal tab). The active
                        // pane's cwd resolves project-relative file/dir targets.
                        onConversationLinkTap = { link ->
                            val cwd = currentPane!!.cwd.takeIf { it.isNotBlank() }
                            when (val action = conversationLinkAction(link, cwd)) {
                                is ConversationLinkAction.OpenFile -> {
                                    DiagnosticEvents.record(
                                        "action",
                                        "conversation_link_open",
                                        "mode" to "tmux",
                                        "kind" to "file",
                                        "paneId" to paneIdForSend,
                                    )
                                    onOpenFile(action.path, action.cwd)
                                }
                                is ConversationLinkAction.BrowseDirectory -> {
                                    DiagnosticEvents.record(
                                        "action",
                                        "conversation_link_open",
                                        "mode" to "tmux",
                                        "kind" to "directory",
                                        "paneId" to paneIdForSend,
                                    )
                                    onBrowseFiles(action.startDir)
                                }
                                is ConversationLinkAction.OpenUrl -> {
                                    DiagnosticEvents.record(
                                        "action",
                                        "conversation_link_open",
                                        "mode" to "tmux",
                                        "kind" to "url",
                                        "paneId" to paneIdForSend,
                                    )
                                    handleUrlTap(action.url)
                                }
                            }
                        },
                    )
                    }
                } else if (showConversationPlaceholder) {
                    // Issue #778: the user tapped Conversation on a presumed-agent
                    // pane before live detection landed. Render a lightweight
                    // loading placeholder (no [TmuxConversationPane], so no
                    // empty-transcript crash and no #605 teardown race). The real
                    // conversation replaces this the instant detection seeds.
                    // Issue #793: surface the row's load state — "Loading
                    // conversation…" while detection/transcript is in flight, or
                    // a clear Failed terminal state (with Retry) once the
                    // watchdog trips, instead of an infinite "Waiting for agent…"
                    // spinner.
                    // Issue #1207 (stranded-spinner race): when the conversation
                    // row is GONE (`visibleConversation == null`) — e.g. the
                    // 2-consecutive-null detection teardown removed it BEFORE the
                    // 12s load watchdog fired — there is no watchdog left behind
                    // this placeholder, so the old `?: Loading` fallback spins
                    // FOREVER. [tmuxConversationPlaceholderLoadState] resolves a
                    // missing row to a terminal, legible Empty state ("No
                    // conversation yet — the agent is live in the Terminal tab")
                    // with the same one-tap Open-in-Terminal action, never Loading.
                    ConversationDetectingPlaceholder(
                        loadState = tmuxConversationPlaceholderLoadState(
                            visibleConversation?.loadState,
                        ),
                        onRetry = {
                            surfacePane?.paneId?.let { viewModel.retryAgentConversationLoad(it) }
                        },
                        onOpenTerminal = {
                            surfacePane?.paneId?.let {
                                viewModel.selectSessionTab(it, SessionTab.Terminal)
                            }
                            tuiCommandNotice = null
                        },
                    )
                } else if (unifiedPanes.isEmpty()) {
                    EmptyPanesPlaceholder()
                }
                // Issue #810: the plain-Terminal case (no conversation overlay) is
                // now served by the always-mounted [TmuxTerminalPager] above —
                // there is no trailing `else` branch that mounts a SECOND pager.
                // The pager above keeps #796's skippable guarantee (its inputs are
                // all stable, so an overlay-visibility toggle skips it) and supplies
                // the warm, attached terminal surface for BOTH the raw-Terminal tab
                // and (covered, underneath) the Conversation tab.

                // Issue #1207: a TUI-only slash-command (/model, /config, a
                // permission picker) sent from the Conversation composer opens its
                // picker in the covered alt-screen Terminal and writes NOTHING to
                // the transcript — so nothing appears here. Instead of the old
                // misleading optimistic bubble + silent nothing, an inline notice
                // over the Conversation surface tells the user the picker is live in
                // the Terminal and offers a one-tap jump. Only on the Conversation
                // tab (the Terminal already shows the picker). The notice self-clears
                // on the jump, on dismiss, and on a session switch (state key).
                val activeTuiCommandNotice = tuiCommandNotice
                if (activeTuiCommandNotice != null &&
                    currentSelectedTab == SessionTab.Conversation
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        ConversationTuiCommandNotice(
                            command = activeTuiCommandNotice,
                            onOpenTerminal = {
                                surfacePane?.paneId?.let {
                                    viewModel.selectSessionTab(it, SessionTab.Terminal)
                                }
                                tuiCommandNotice = null
                            },
                            onDismiss = { tuiCommandNotice = null },
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f),
            ) {
                SessionSurfaceReconnectWrapper(
                    pullToReconnectActive = pullToReconnectActive,
                    // The spinner reflects an in-flight reconnect: a
                    // Reconnecting/Connecting/Switching status means the reconnect
                    // the pull triggered (or the auto-ladder) is working; it clears
                    // the instant we reach Connected (the wrapper falls back to the
                    // bare surface when `pullToReconnectActive` flips false) or
                    // settle on a Failed band.
                    isReconnecting = status is ConnectionStatus.Reconnecting ||
                        status is ConnectionStatus.Connecting ||
                        status is ConnectionStatus.Switching,
                    onReconnect = { viewModel.reconnect() },
                    // Issue #750: suppress the box's own spinner while the surface
                    // already shows the centered "Attaching…" hold — one indicator.
                    surfaceShowsCenteredLoader = surfaceCenteredLoader,
                    // Issue #890: the visible "Reconnect" button shows ONLY once the
                    // connect/attach has SETTLED into a dropped/stuck state — never
                    // WHILE a connect/reconnect/attach is in progress (the
                    // maintainer's "no reconnect button while I'm connecting"). The
                    // in-progress states (`Connecting` / `Switching`(=Attaching) /
                    // `Reconnecting`) already drive the progress bar + "Attaching…/
                    // Reconnecting…" indicator, so the button would be redundant.
                    //
                    // Issue #1322 (screenshot A): the button is ALSO suppressed on a
                    // `Failed` status, because the calm [FailedConnectionRow] "Tap to
                    // reconnect" band (rendered above when the status is `Failed`)
                    // already offers the SINGLE reconnect affordance. Showing both the
                    // band link AND this bottom button is the "TWO reconnect controls
                    // at once" duplicate the maintainer reported (a regression of
                    // #720's single tappable "Tap to reconnect"). This leaves the
                    // bottom button as the SOLE affordance ONLY on the remaining
                    // `Idle` (dropped/never-attached, with a `canReconnect` target)
                    // state, which has no band.
                    showReconnectButton = surfaceReconnectButtonVisible(surfaceState),
                    content = surfaceContent,
                )
            }

            // Assistant review sits above the input band.
            AssistantStrip(
                state = assistantState,
                onConfirm = viewModel::confirmAssistantAction,
                onCorrect = viewModel::correctAssistantAction,
                onCancel = viewModel::cancelAssistantAction,
                onDismiss = viewModel::dismissAssistant,
                onRetry = viewModel::retryAssistantAction,
                onChoose = viewModel::chooseAssistantFolder,
                onCancelChoice = viewModel::cancelAssistantChoice,
            )

            surfacePane?.let { pane ->
                AgentQuickReplyBand(
                    terminalState = pane.terminalState,
                    enabled = quickReplyInputEligible && !showMicSheet && !showConversation,
                    onReply = { reply ->
                        viewModel.writeInputToPane(
                            pane.paneId,
                            reply.payload.toByteArray(Charsets.UTF_8),
                        )
                    },
                )
            }

            // Issue #810 (hard-cut, D22) — the prompt composer affordance is
            // ALWAYS present on every live session, structurally independent of
            // agent-detection state, pane-cache state (`surfacePane == null` /
            // the #797 visible-pane swap), the selected tab, and session-switch
            // transitions. The bottom controls (which HOST the composer launcher)
            // are therefore rendered UNCONDITIONALLY — never wrapped in a
            // `surfacePane?.let { ... }` that drops them from the tree whenever
            // the surface pane is momentarily null.
            //
            // This DELETES the multi-release regression source: every prior
            // composer-disappearance fix (#797 visible-pane following, #744
            // detection uncertainty, #801 squish, #805 detecting-state chrome)
            // gated the composer on some piece of state, and a different state
            // path kept removing it. The cure is to stop gating PRESENCE at all.
            //
            // The composer launcher itself only flips `showMicSheet`; it needs no
            // pane. The CONTENT/behaviour (voice, snippets, send) is unchanged —
            // only PRESENCE becomes unconditional. The pane-dependent chip / key
            // callbacks below are null/no-op when there is no routable surface
            // pane (a switch is hiding the terminal — the #661/#634/#636
            // stale-bleed guard), and `controlsInputEnabled` disables them, so a
            // tap never routes bytes to a stale leaving session. They reappear the
            // instant the target pane is revealed. The `onSend` path already
            // returns false when `surfacePane == null`, so opening the composer
            // over a null surface is safe.
            run {
                val pane = surfacePane
                // Issue #716: agent-aware chips/affordances follow presumed-agent,
                // not just live detection, so they don't flip to shell chips
                // during the slow-detection window.
                val isAgentPane = tmuxSessionIsAgentPane(
                    hasLiveDetection = currentDetection != null,
                    presumedAgent = presumedAgent,
                )
                val bottomControlsModifier = if (isImeVisible) {
                    Modifier
                } else {
                    Modifier.navigationBarsPadding()
                }
                // Issue #810: input routing is live only when there is a routable
                // surface pane AND the SSH/tmux link is up. With no surface pane
                // (a switch is hiding the terminal) the chips are inert; the
                // composer launcher stays present and tappable regardless.
                val controlsInputEnabled = sessionLive && pane != null
                // Issue #673: staged composer attachments are NOT shown in the
                // session/terminal bottom area. They live only inside the
                // Prompt Composer sheet (state persists in the composer
                // ViewModel across session switches), so the terminal controls
                // never receive the staged-attachment list.
                // Issue #784 (hard-cut, D22): the terminal key bar no longer
                // lives in the composer (or here). The terminal hotkeys are in
                // the dedicated [TerminalHotkeysSheet] panel, opened from this
                // surface's hotkeys launcher (`onShowHotkeysTap`).
                TmuxSessionBottomControlsCallSite(
                    isImeVisible = isImeVisible,
                    showConversationTranscript = showConversation,
                    showConversationDetectingPlaceholder = showConversationPlaceholder,
                    sessionLive = controlsInputEnabled,
                    isAgentPane = isAgentPane,
                    onChipTap = { chip ->
                        // Agent slash commands are opened through the primary
                        // bottom-control affordance, not the scrollable chip
                        // list, so chips reaching this handler are shell-pane
                        // quick-run commands ([DefaultSessionChips]). Each
                        // runs literally in the focused pane, appending a CR
                        // that the tmux input bridge translates into Enter.
                        // Issue #810: no-op when there is no routable surface pane.
                        pane?.let {
                            viewModel.writeInputToPane(
                                it.paneId,
                                (chip + "\r").toByteArray(Charsets.UTF_8),
                            )
                        }
                    },
                    // Issue #810: the composer launcher is UNCONDITIONAL — it only
                    // opens the Prompt Composer sheet (no pane needed), so it is
                    // never gated on `surfacePane`.
                    onDictateTap = {
                        // Plain tap: open the composer with NO recording (unchanged).
                        micSheetAutoStartRecording = false
                        showMicSheet = true
                    },
                    // Issue #585: hold the launcher + swipe UP → open the composer
                    // AND start recording immediately (locked hands-free), one
                    // gesture. The sheet consumes `autoStartRecording` on open.
                    onDictateHoldSwipeUp = {
                        micSheetAutoStartRecording = true
                        showMicSheet = true
                    },
                    onEnterTap = pane?.let { p -> { viewModel.onKeyBarKey(p.paneId, "Enter") } },
                    // Issue #131: surface the show-keyboard chip on the tmux
                    // route too. The helper looks up the TerminalView of the
                    // currently visible pane.
                    onShowKeyboardTap = pane?.let { p ->
                        {
                            DiagnosticEvents.record(
                                "action",
                                "keyboard_panel_show",
                                "mode" to "tmux",
                                "paneId" to p.paneId,
                            )
                            showTerminalSoftKeyboard(
                                composeRootView,
                                onLocalTerminalError = { cause ->
                                    viewModel.reportTerminalSurfaceFailure(p.paneId, cause)
                                },
                            )
                        }
                    },
                    // Issue #453: no snippet chip on agent panes — the
                    // composer's `{}` affordance already inserts saved prompts.
                    // Issue #454: shell panes keep the saved-snippet picker.
                    // Issue #761: gate on the ACTUAL agent signal (live
                    // detection or the #462 sticky kind), NOT the optimistic
                    // presumed-agent default. #716's presumed-agent keeps the
                    // composer surface available on a fresh pane, but it must
                    // not suppress the snippet chip on a genuine shell pane that
                    // has never hosted an agent — that suppression is the #761
                    // bug (the chip was never in the semantics tree on a tmux
                    // shell). See [tmuxSessionShowsSnippetChip].
                    // Issue #810: also null when there is no surface pane to route
                    // a picked snippet to.
                    onAddSnippetTap = if (
                        pane != null &&
                        tmuxSessionShowsSnippetChip(
                            hasHost = hostId != 0L,
                            hasLiveDetection = currentDetection != null,
                            hasStickyAgent = paletteAgent != null,
                        )
                    ) {
                        { showSnippetPicker = true }
                    } else null,
                    // Issue #784: open the dedicated terminal-hotkeys panel.
                    // Terminal tab only (a raw pane to receive control bytes).
                    onShowHotkeysTap = pane?.let { p ->
                        {
                            DiagnosticEvents.record(
                                "action",
                                "hotkey_panel_show",
                                "mode" to "tmux",
                                "paneId" to p.paneId,
                            )
                            showHotkeysPanel = true
                        }
                    },
                    leadingChipContent = if (controlsInputEnabled) sessionCardFeedChipState?.let { state ->
                        {
                            SessionCardFeedChip(
                                state = state,
                                onClick = {
                                    viewModel.refreshActiveSessionCards()
                                    showCardFeedSheet = true
                                },
                            )
                        }
                    } else null,
                    modifier = bottomControlsModifier,
                )
            }

            // Pane pager dot indicator — a thin row of dots so the user
            // knows which pane is showing and how many siblings exist.
            // We only render when there's >1 pane; the indicator is
            // redundant in the single-pane case (which is the common one
            // for a freshly-attached session).
            // Issue #626: use unifiedPanes for the dot indicator count
            // since the pager now spans all sessions.
            if (unifiedPanes.size > 1) {
                PageIndicator(
                    pageCount = unifiedPanes.size,
                    currentPage = pagerState.currentPage,
                )
            }
        }

        dialogMode?.let { mode ->
            TmuxLifecycleDialog(
                mode = mode,
                sessionName = sessionName,
                text = dialogText,
                onTextChange = { dialogText = it },
                onDismiss = { dialogMode = null },
                onConfirm = {
                    when (mode) {
                        TmuxDialogMode.RenameSession -> {
                            val name = dialogText.trim()
                            viewModel.renameCurrentSession(name)
                            if (name.isNotEmpty()) onReplaceTmuxSession(name)
                        }
                        TmuxDialogMode.StopSession -> {
                            // Issue #883: "Stop session" on a `[wN]` window row
                            // must remove only the window the pager is currently
                            // showing — pass that window's tmux index so the VM
                            // runs `kill-window` (the session + siblings survive)
                            // rather than `kill-session` (the whole session). The
                            // VM kills `activeTarget.sessionName:windowIndex`, so
                            // only pass the index when the visible pane belongs to
                            // the ACTIVE session (`currentPane` is non-null only
                            // then); otherwise fall back to the whole-session kill
                            // so we never kill a window of the wrong session.
                            viewModel.killCurrentSession(currentPane?.windowIndex)
                            onBack()
                        }
                    }
                    dialogMode = null
                },
            )
        }

        TmuxSessionAuxiliaryModals(
            showNewSessionSheet = showNewSessionSheet,
            currentPaneCwd = currentPane?.cwd,
            suggestStartDirectories = suggestStartDirectories,
            claudeProfiles = newSessionClaudeProfiles,
            codexProfiles = newSessionCodexProfiles,
            // Issue #1184: prefill the editable "Session name" field with the
            // directory-derived default for the chosen start folder.
            deriveDefaultName = { dir ->
                defaultSessionBaseName(dir, conventionalRemoteHome(user))
            },
            onDismissNewSessionSheet = { showNewSessionSheet = false },
            onCreateNewSession = { choice ->
                showNewSessionSheet = false
                // Issue #898/#976: do not create with an unknown session list;
                // without known names the derived name can collide with the
                // current same-folder session and route launch keys wrongly.
                val readyPicker = sessionPickerState as? HostTmuxSessionPickerState.Ready
                if (readyPicker == null) {
                    Toast.makeText(
                        context,
                        "Session list isn't loaded yet — reconnect or wait " +
                            "for it to finish, then start the new session again.",
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    val knownNames = readyPicker.rows.map { it.name }.toSet()
                    val newName = derivedSessionName(
                        choice = choice,
                        homeDirectory = conventionalRemoteHome(user),
                        existingNames = knownNames,
                    )
                    viewModel.createSession(
                        name = newName,
                        cwd = choice.startDirectory,
                        startCommand = choice.startCommand(
                            newSessionClaudeProfiles,
                            newSessionCodexProfiles,
                        ),
                        chosenKind = choice.sessionAgentKind,
                        onResolved = { resolved ->
                            onOpenTmuxSession(resolved, choice.startDirectory)
                        },
                    )
                }
            },
            showKindPicker = showKindPicker,
            sessionName = sessionName,
            currentSessionRecordedKind = currentSessionRecordedKind,
            currentSessionRecordedProfile = currentSessionRecordedProfile,
            onDismissKindPicker = { showKindPicker = false },
            onPickKind = { kind ->
                viewModel.setCurrentSessionKind(kind)
                showKindPicker = false
            },
            showOpenFileDialog = showOpenFileDialog,
            openFilePath = openFilePath,
            onOpenFilePathChange = { openFilePath = it },
            onDismissOpenFileDialog = { showOpenFileDialog = false },
            onOpenFileConfirmed = onOpenFile,
            pendingLocalhostForward = pendingLocalhostForward,
            localhostTargetHost = hostName.ifBlank { host },
            onDismissLocalhostForward = { pendingLocalhostForward = null },
            onConfirmLocalhostForward = { pending ->
                val target = acceptedLocalhostForwardNavigation(pending)
                pendingLocalhostForward = null
                onOpenPortForwardingWithPort(target.remotePort, target.autoOpenLocalhostUrl)
            },
        )

        SessionSwitcherOverlay(
            visible = showSessionSwitcher,
            state = sessionPickerState,
            hostName = hostName.ifBlank { host },
            currentSessionName = sessionName,
            onRefresh = { sessionPickerViewModel.load(sessionPickerRequest) },
            onDismiss = {
                showSessionSwitcher = false
                sessionPickerViewModel.dismiss()
            },
            onSelectSession = { selectedSessionName ->
                handleTmuxSessionSelection(
                    currentSessionName = sessionName,
                    selectedSessionName = selectedSessionName,
                    onDismiss = {
                        showSessionSwitcher = false
                        sessionPickerViewModel.dismiss()
                    },
                    onReplace = onReplaceTmuxSession,
                )
            },
            onCreate = {
                showSessionSwitcher = false
                // Issue #898 (Blocker A): go through the shared helper so this
                // entry point loads the host's known session names too —
                // otherwise the closing switcher's `dismiss()` would leave the
                // picker Idle and the same-folder create would collide. The
                // helper's `load()` re-populates the names for the sheet.
                openNewSessionSheet()
            },
        )

        TmuxSessionDrawer(
            visible = showSessionDrawer,
            state = sessionPickerState,
            hostName = hostName.ifBlank { host },
            currentSessionName = sessionName,
            onRefresh = { sessionPickerViewModel.load(sessionPickerRequest) },
            onDismiss = {
                showSessionDrawer = false
                sessionPickerViewModel.dismiss()
            },
            onAttach = { selectedSessionName ->
                handleTmuxSessionSelection(
                    currentSessionName = sessionName,
                    selectedSessionName = selectedSessionName,
                    onDismiss = {
                        showSessionDrawer = false
                        sessionPickerViewModel.dismiss()
                    },
                    onReplace = onReplaceTmuxSession,
                )
            },
            onCreate = {
                showSessionDrawer = false
                // Issue #898 (Blocker A): same as the switcher — go through the
                // shared helper so the drawer's "+ New session" loads the known
                // session names and a same-folder create disambiguates instead
                // of colliding via `new-session -A`.
                openNewSessionSheet()
            },
        )

        // Issue #448 (epic #432 slice C): non-blocking "forward this new
        // port?" overlay. Anchored to the bottom of the screen, above the
        // composer/key bar, and it does NOT cover the terminal — the user
        // can keep typing while it is shown. Forward navigates to the
        // pre-filled port-forward panel (#447); back returns here.
        DetectedPortOverlay(
            port = detectedPort,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .imePadding(),
            onForward = {
                val accepted = viewModel.acceptDetectedPort()
                if (accepted != null) {
                    detectedPortForwardNavigation(accepted).let { target ->
                        onOpenPortForwardingWithPort(target.remotePort, target.autoOpenLocalhostUrl)
                    }
                }
            },
            onDismiss = { viewModel.dismissDetectedPort() },
        )
    }

    LaunchedEffect(showSessionSwitcher, showSessionDrawer, sessionPickerRequest) {
        if (showSessionSwitcher || showSessionDrawer) {
            sessionPickerViewModel.load(sessionPickerRequest)
        }
    }
    LaunchedEffect(showSessionSwitcher, showSessionDrawer, sessionPickerState) {
        val pickerVisible = showSessionSwitcher || showSessionDrawer
        val ready = sessionPickerState as? HostTmuxSessionPickerState.Ready
        if (pickerVisible && ready != null) {
            viewModel.prewarmLikelySwitchTargets(ready.rows.map { it.name })
        } else {
            viewModel.cancelTmuxSessionPrewarm()
        }
    }

    val ctrlModifierState by viewModel.ctrlModifier.collectAsState()
    TmuxSessionSheets(
        showMicSheet = showMicSheet,
        promptComposerViewModel = promptComposerViewModel,
        composerAgentKind = paletteAgent ?: presumedAgentKind,
        composerTargetKey = targetSessionId.value,
        micSheetAutoStartRecording = micSheetAutoStartRecording,
        onDismissMicSheet = {
            showMicSheet = false
            micSheetAutoStartRecording = false
        },
        connectionLost = !sessionLive,
        sendTargetSnapshotProvider = { withEnter ->
            val pane = surfacePane
            val liveAgent = currentDetection?.agent
            val viewingConversationNow = currentDetection != null &&
                currentSelectedTab == SessionTab.Conversation
            val route = tmuxComposerSendRoute(
                viewingConversation = viewingConversationNow,
                liveAgent = liveAgent,
                presumedAgentKind = presumedAgentKind,
                withEnter = withEnter,
            )
            tmuxComposerSendTargetSnapshot(
                sessionKey = targetSessionId.value,
                paneId = pane?.paneId,
                route = route,
                agentKind = liveAgent ?: presumedAgentKind,
            )
        },
        onSend = composerSendHandler,
        composerHostId = hostId.takeIf { it != 0L },
        onStageAttachments = viewModel::stagePromptAttachments,
        showSnippetPicker = showSnippetPicker,
        snippetsHostId = hostId,
        snippetKindFilter = if (currentDetection != null) {
            SnippetKind.Prompt
        } else {
            SnippetKind.Command
        },
        onDismissSnippetPicker = { showSnippetPicker = false },
        onSnippetSend = { snippet, withEnter ->
            if (sessionLive) {
                surfacePane?.let { pane ->
                    viewModel.writeInputToPane(
                        pane.paneId,
                        snippetDispatchText(snippet, withEnter).toByteArray(Charsets.UTF_8),
                    )
                }
                showSnippetPicker = false
            }
        },
        showCardFeedSheet = showCardFeedSheet,
        sessionCards = sessionCards,
        sessionCardInteractions = sessionCardInteractions,
        onDismissCardFeedSheet = { showCardFeedSheet = false },
        showHotkeysPanel = showHotkeysPanel,
        hotkeysPaneId = surfacePane?.paneId,
        sessionLive = sessionLive,
        ctrlModifierState = ctrlModifierState,
        onHotkey = { paneId, binding ->
            if (sessionLive) {
                DiagnosticEvents.record(
                    "action",
                    "shortcut_sent",
                    "mode" to "tmux",
                    "paneId" to paneId,
                    "key" to binding.label,
                )
                viewModel.onKeyBarKey(paneId, binding.label)
            }
        },
        onDismissHotkeys = { showHotkeysPanel = false },
    )

}

private const val MotionDurationMs: Int = 200
private val MotionEasing = CubicBezierEasing(0f, 0f, 0.2f, 1f)
internal const val TMUX_SESSION_SCREEN_TAG = "tmux:session"
