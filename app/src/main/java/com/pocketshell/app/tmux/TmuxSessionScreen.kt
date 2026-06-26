package com.pocketshell.app.tmux

import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import com.pocketshell.app.conversation.filterConversationRows
import com.pocketshell.app.conversation.isHiddenConversationTimelineRow
import com.pocketshell.app.conversation.runningToolCallIds
import com.pocketshell.app.conversation.timelineActorLabel
import com.pocketshell.app.conversation.timelinePreview
import com.pocketshell.app.conversation.timelineTimestamp
import com.pocketshell.app.conversation.toolResultPairing
import com.pocketshell.app.composer.PromptComposerSheet
import com.pocketshell.app.composer.PromptComposerViewModel
import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.app.diagnostics.ReconnectCauseTrail
import com.pocketshell.app.layout.rememberTmuxImeLayoutState
import com.pocketshell.app.projects.FolderListViewModel
import com.pocketshell.app.projects.SessionTypePickerSheet
import com.pocketshell.app.projects.conventionalRemoteHome
import com.pocketshell.app.projects.derivedSessionName
import com.pocketshell.app.session.AgentConversationSyncStatus
import com.pocketshell.app.session.ConversationLoadState
import com.pocketshell.app.session.AgentConversationUiState
import com.pocketshell.app.session.ConversationLinkAction
import com.pocketshell.app.session.ConversationSyncStatusRow
import com.pocketshell.app.session.InlineDictationViewModel
import com.pocketshell.app.settings.SettingsViewModel
import com.pocketshell.core.connection.RevealState
import com.pocketshell.core.connection.targetIdOrNull
import com.pocketshell.app.session.SessionTab
import com.pocketshell.app.session.conversationLinkAction
import com.pocketshell.app.session.conversationSyncStatusLabel
import com.pocketshell.app.session.cwdForDetectedFilePath
import com.pocketshell.app.sessions.DEFAULT_TMUX_START_DIRECTORY
import com.pocketshell.app.sessions.HostTmuxSessionPickerRequest
import com.pocketshell.app.sessions.HostTmuxSessionPickerState
import com.pocketshell.app.sessions.HostTmuxSessionPickerViewModel
import com.pocketshell.app.sessions.HostTmuxSessionRow
import com.pocketshell.app.agentcommands.AgentCommandCatalog
import com.pocketshell.app.cards.ChecklistCardsSheet
import com.pocketshell.app.cards.ChecklistChip
import com.pocketshell.app.cards.SessionCardsRemoteSource
import com.pocketshell.app.cards.checklistChipState
import com.pocketshell.app.snippets.SnippetKind
import com.pocketshell.app.snippets.SnippetPickerSheet
import com.pocketshell.app.snippets.snippetDispatchText
import com.pocketshell.app.startup.StartupTiming
import com.pocketshell.app.tmux.TmuxSessionViewModel.ConnectionStatus
import com.pocketshell.app.voice.ADD_COMMAND_CHIP_LABEL
import com.pocketshell.app.voice.BottomChipControls
import com.pocketshell.app.voice.ConversationComposerLauncherRow
import com.pocketshell.app.voice.DefaultSessionChips
import com.pocketshell.app.voice.HOTKEYS_CHIP_LABEL
import com.pocketshell.app.voice.HotkeysChipIcon
import com.pocketshell.app.voice.SnippetsChipIcon
import com.pocketshell.app.voice.AssistantStrip
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.CommandChip
import com.pocketshell.uikit.components.DisclosureIcon
import com.pocketshell.uikit.components.PocketShellButton
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
import com.pocketshell.core.terminal.ui.TerminalKeyboardMode
import com.pocketshell.core.terminal.ui.TerminalSurface
import com.pocketshell.core.terminal.ui.showTerminalSoftKeyboard
import com.pocketshell.uikit.components.Badge
import com.pocketshell.uikit.components.BadgeRole
import com.pocketshell.uikit.components.KebabTrigger
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.LoadingIndicator
import com.pocketshell.uikit.components.SectionHeader
import com.pocketshell.uikit.components.SpinnerSize
import com.pocketshell.uikit.components.StatusDot
import com.pocketshell.uikit.model.Crumb
import com.pocketshell.uikit.model.ConnectionStatus as UiConnectionStatus
import com.pocketshell.uikit.model.KeyBinding
import com.pocketshell.uikit.model.KeyKind
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType
import kotlinx.coroutines.launch
import kotlin.math.abs

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
    // never flashes the "Reconnecting" band/spinner. Steady states
    // (Connected/Connecting/Switching/Failed/Idle) still surface immediately, so
    // input-gating (`sessionLive`) and the connecting overlay are unaffected — only
    // the transient Reconnecting surfacing is held back ~1s.
    val status by viewModel.displayConnectionStatus.collectAsState()
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
    // Hold the terminal (loading placeholder) until the reveal machine confirms
    // RevealState.Live FOR THIS target — never paint the previous session's frame.
    val revealHoldsTerminal =
        !(revealState is RevealState.Live && revealState.targetIdOrNull() == targetSessionId)
    val effectiveHidesTerminal = revealHoldsTerminal
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
    val sessionLive = status is ConnectionStatus.Connected
    val outboundQueueItems by promptComposerViewModel.outboundQueueItems.collectAsState()
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
    LaunchedEffect(sessionLive, targetSessionId.value, outboundQueueItems) {
        outboundQueueAutoFlushController.onQueueSnapshotChanged(sessionLive) { excludingIds ->
            promptComposerViewModel.retryNextOutboundItem(excludingIds = excludingIds)
        }
    }
    val sessionCardsState by viewModel.sessionCards.collectAsState()
    val checklistCards = remember(sessionCardsState, activeSessionCardsTargetKey) {
        if (sessionCardsState.targetKey == activeSessionCardsTargetKey) {
            sessionCardsState.feed.cards.filterIsInstance<SessionCardsRemoteSource.ChecklistCard>()
        } else {
            emptyList()
        }
    }
    val sessionChecklistChipState = remember(checklistCards) {
        checklistChipState(checklistCards)
    }
    LaunchedEffect(sessionLive, activeSessionCardsTargetKey) {
        if (sessionLive) viewModel.refreshActiveSessionCards()
    }
    // Issue #459: the per-screen restored-draft holder used to seed the
    // bespoke in-pane Conversation composer is gone. The Conversation
    // bottom now shares the unified [PromptComposerSheet], whose draft
    // persists in [PromptComposerViewModel], so there is nothing to seed
    // here on the screen.
    val agentConversations by viewModel.agentConversations.collectAsState()
    val sessionPickerState by sessionPickerViewModel.state.collectAsState()
    // Issue #463: the in-session project switcher's sibling list, sourced
    // from the warm live `-CC` client only (no SSH handshake) so tapping the
    // header project crumb and switching stays instant.
    val projectSwitcherState by sessionPickerViewModel.projectSwitcher.collectAsState()
    val assistantState by viewModel.assistantState.collectAsState()
    val dictationState by inlineDictationViewModel.uiState.collectAsState()
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
        switchHidesTerminal = effectiveHidesTerminal,
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
    val currentAgentConversation = surfacePane?.paneId?.let { agentConversations[it] }
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
        effectiveHidesTerminal,
        sessionName,
        currentUnifiedPaneSessionName,
    ) {
        // EPIC #687 P1: under NEW the crumb keys off the reveal-driven
        // [effectiveHidesTerminal] (held until the target's pane is revealed); under
        // OLD it keys off the inline `switchHidesTerminal`. Either way the crumb is
        // suppressed while the target's own pane is not yet visible, so the header
        // shows the SINGLE target identity throughout a switch.
        keyedProjectCrumbLabel(
            projectPath = projectPath,
            switchHidesTerminal = effectiveHidesTerminal,
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
    var showSnippetPicker by remember { mutableStateOf(false) }
    var showChecklistSheet by remember(activeSessionCardsTargetKey) { mutableStateOf(false) }

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
    val liveAgentForPane = currentAgentConversation?.detection?.agent
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
            hasLiveDetection = currentAgentConversation?.detection != null,
            stickyAgent = paletteAgent,
            // Issue #894 (Slice C): the visible pane's durable confirmed-shell
            // verdict — true only when the tree recorded `@ps_agent_kind=shell`.
            confirmedShell = surfacePane.paneId in confirmedShellPaneIds,
        )
    // The agent kind to use when sending to a presumed-agent pane that has no
    // live detection/transcript yet: the sticky/last-known kind. Null when this
    // pane has never been an agent in the current attach (then a presumed send
    // falls back to raw bytes).
    val presumedAgentKind: AgentKind? = if (currentAgentConversation?.detection == null) {
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
                    viewModel.sendToAgentPaneResult(paneId, request.text).isSuccess
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
            }
            sent
        }
    }
    PromptComposerSendDispatcher(
        viewModel = promptComposerViewModel,
        onSend = composerSendHandler,
        onDelivered = { showMicSheet = false },
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
        checklistSheetOpen = showChecklistSheet,
        onDismissDialog = { dialogMode = null },
        onDismissSessionDrawer = {
            showSessionSwitcher = false
            showSessionDrawer = false
            sessionPickerViewModel.dismiss()
        },
        onDismissMicSheet = { showMicSheet = false },
        onDismissSnippetPicker = { showSnippetPicker = false },
        onDismissChecklistSheet = { showChecklistSheet = false },
        onBack = onBack,
    )

    // Issue #131: same root-view handle as `SessionScreen`. The pager
    // renders one pane at a time, so the helper's recursive search lands
    // on the visible pane's `TerminalView`.
    val composeRootView = LocalView.current
    val context = LocalContext.current

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
    LaunchedEffect(inlineDictationViewModel, dictationState.mode, focusedPaneId) {
        inlineDictationViewModel.transcriptions.collect { text ->
            val paneId = focusedPaneId ?: return@collect
            when (dictationState.mode) {
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
                    switchHidesTerminal = effectiveHidesTerminal,
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
                // Issue #993: close the menu, then force an immediate reconnect of THIS
                // session in place via the VM's single [TmuxSessionViewModel.reconnect]
                // /TransportEffects entrypoint — the SAME path a session switch uses to
                // recover a dropped session, so the user no longer has to switch away and
                // back. `reconnect()` returns false only when there is no target (the
                // `reconnectEnabled` gate already suppresses that), so the Toast confirms
                // the reconnect was actually kicked off (visible feedback, no silent
                // no-op). The terminal then shows the normal "Reconnecting…" band, and on
                // success the #900 outbound queue auto-flushes the pending message.
                moreExpanded = false
                val started = viewModel.reconnect()
                Toast.makeText(
                    context,
                    if (started) "Reconnecting…" else "Nothing to reconnect to",
                    Toast.LENGTH_SHORT,
                ).show()
            },
            // Issue #993: only actionable when there IS a target to reconnect to AND a
            // connect/reconnect is not already in flight — see [reconnectKebabEnabled].
            reconnectEnabled = reconnectKebabEnabled(canReconnect, status),
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
            val tabState = tmuxSessionTabState(currentAgentConversation, presumedAgent)
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
                                agentName = if (effectiveHidesTerminal) {
                                    null
                                } else {
                                    currentAgentConversation?.detection?.agent?.displayName
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
                                connectionStatus = status.toUiStatus(),
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
                            connectionStatus = status.toUiStatus(),
                            modifier = Modifier.testTag(TMUX_COMPACT_BREADCRUMB_TAG),
                        )
                    }
                }
            }

            // Issue #165: replace the bare one-line "connecting" status
            // with a visible progress overlay (linear indeterminate bar
            // + host string) so a 2-5s SSH handshake doesn't feel like
            // the app is frozen. After 5s a "Still working, this may
            // be slow" subline appears; after 15s a Cancel affordance
            // tears down the in-flight [connectJob] (#151's
            // join-on-cancel machinery makes the teardown deterministic).
            (status as? ConnectionStatus.Connecting)?.let {
                ConnectingProgressOverlay(
                    user = it.user,
                    host = it.host,
                    port = it.port,
                    sessionLabel = "tmux $sessionName",
                    onCancel = { viewModel.cancelConnect() },
                )
            }
            // Issue #750: a same-host session switch ([Switching]) no longer
            // renders a thin under-header progress line here. During a switch the
            // terminal surface is always replaced by a centered placeholder —
            // either the "Attaching…" [SwitchingLoadingPlaceholder]
            // (switchHidesTerminal) or the "waiting for tmux panes…"
            // [EmptyPanesPlaceholder] (warm open, panes emptied) — and each of
            // those now shows the canonical centered spinner (#757). Keeping the
            // top bar produced the maintainer's reported "two loading indicators"
            // on the reattach screen, so the centered spinner is the SOLE attach
            // affordance and the top line is removed. Input stays gated because
            // [Switching] is not [Connected].
            //
            // Issue #750 (post-#766 regression): the SAME two-loaders symptom came
            // back on the RECONNECT/REATTACH screen. The #766 connection migration
            // made the controller the authoritative status source: a recoverable
            // drop projects [ConnectionStatus.Reconnecting] (this top
            // [ReconnectingProgressRow] bar) WHILE the id-keyed [RevealStateMachine]
            // maps the controller's `Reattaching`/`Reconnecting` to
            // [RevealState.Seeding] → [effectiveHidesTerminal] is true → the
            // centered "Attaching…" [SwitchingLoadingPlaceholder] is already up in
            // the surface Box below. Two indicators at once. Gate the top bar on
            // `!effectiveHidesTerminal` so it is suppressed exactly while the
            // centered hold is showing — the centered "Attaching…" is then the SOLE
            // reattach affordance, matching the [Switching] fix above. The top bar
            // is NOT the sole indicator for any other state: it ONLY renders for
            // [ConnectionStatus.Reconnecting], and every reconnect/reattach keeps
            // the terminal held (the reveal machine never reveals Live for a
            // Reattaching/Reconnecting controller state — see RevealStateMachine
            // §"loading" mapping), so suppressing it here never leaves a reconnect
            // with zero indicators. (Reconnect speed/behaviour is untouched — this
            // is presentation-only.)
            if (shouldShowReconnectingProgressRow(status, effectiveHidesTerminal)) {
                (status as ConnectionStatus.Reconnecting).let {
                    ReconnectingProgressRow(
                        status = it,
                        sessionLabel = "tmux $sessionName",
                        onRetryNow = { viewModel.reconnect() },
                        onCancel = { viewModel.cancelConnect() },
                    )
                }
            }
            // Issue #145: render a user-facing error band (status text +
            // Reconnect affordance) when the SSH transport drops
            // mid-session. The view model's `client.disconnected`
            // observer (added in #173, rephrased in #145) flips status
            // to Failed when the underlying [TmuxClient.readerLoop]
            // exits; this band surfaces the failure and gives the user
            // a single-tap retry that re-runs the same connect() path.
            // The test tags [TMUX_SESSION_ERROR_TAG] and
            // [TMUX_SESSION_RECONNECT_TAG] make the elements grep-able
            // from connected disconnect+reconnect tests.
            (status as? ConnectionStatus.Failed)?.let { failed ->
                FailedConnectionRow(
                    message = failed.message,
                    onReconnect = { viewModel.reconnect() },
                    canReconnect = canReconnect,
                )
            }
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
            val visibleConversation = surfacePane?.paneId?.let { agentConversations[it] }
            // Whether to render the Conversation *content* (real transcript) in
            // place of the terminal pager. This is the actual content swap,
            // distinct from whether the Conversation *tab* exists (#716
            // presumed-agent — see [tmuxSessionTabState]). It still requires a
            // live detection because the heavyweight [TmuxConversationPane] needs
            // a real transcript — and keeping this detection-gated also preserves
            // the #605 Conversation→Terminal swap latch, which keys off the real
            // pane mounting/unmounting (the lightweight placeholder below never
            // mounts that pane, so it cannot trigger the same-frame teardown
            // race). Still gated on the ACTIVE `currentPane`: the transcript
            // mounts only for the promoted active runtime's pane (#797).
            val showConversation = currentPane != null &&
                visibleConversation?.detection != null &&
                visibleConversation.selectedTab == SessionTab.Conversation
            // Issue #778: when the user has tapped Conversation on a
            // presumed-agent pane but live detection has NOT landed yet
            // (`detection == null`), render a lightweight "waiting for agent"
            // placeholder INSTEAD of swallowing the tap and staying on the
            // terminal. The tap is now honoured end-to-end (onClick → VM records
            // `selectedTab = Conversation` on a detection-less row → this branch
            // paints the placeholder). The real [TmuxConversationPane]
            // (`showConversation`) takes over the instant detection seeds. Gated
            // on `presumedAgent` so a confirmed shell can never show this.
            // Issue #797: the placeholder follows the SURFACE pane so the
            // "waiting for agent" state is shown on a settled cached presumed-
            // agent pane too (the Conversation tab is no longer empty there).
            val showConversationPlaceholder = surfacePane != null &&
                presumedAgent &&
                visibleConversation?.detection == null &&
                visibleConversation?.selectedTab == SessionTab.Conversation
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
            // Issue #750 (3rd occurrence): during the attach/reattach hold the
            // surface content paints the centered "Attaching…"
            // [SwitchingLoadingPlaceholder] (driven by `effectiveHidesTerminal`).
            // That centered spinner is the SOLE indicator for this state, so the
            // pull-to-reconnect wrapper below must NOT also run its own
            // [PullToRefreshBox] spinner on top of it (the maintainer's reported
            // cyan ring + gray spinner-in-a-chip stacked over "Attaching…"). The
            // pull GESTURE stays mounted — only the duplicate box spinner is
            // suppressed — so #823's pull-to-reconnect is preserved, and the box
            // spinner returns as the SOLE affordance once the surface settles
            // into a steady non-loader state (no centered "Attaching…").
            val surfaceShowsCenteredLoader = effectiveHidesTerminal
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
                // `effectiveHidesTerminal` still takes precedence and paints ONLY
                // the switch placeholder: during a cross-session switch not a single
                // frame of the leaving session's terminal (or conversation) may leak
                // (#661 / EPIC #687 P1).
                val keepTerminalMounted = !effectiveHidesTerminal &&
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
                        hasLiveDetection = currentAgentConversation?.detection != null,
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
                if (effectiveHidesTerminal) {
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
                } else if (showConversation) {
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
                    ConversationDetectingPlaceholder(
                        loadState = visibleConversation?.loadState
                            ?: ConversationLoadState.Loading,
                        onRetry = {
                            surfacePane?.paneId?.let { viewModel.retryAgentConversationLoad(it) }
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
                    surfaceShowsCenteredLoader = surfaceShowsCenteredLoader,
                    // Issue #890: the visible "Reconnect" button shows ONLY once the
                    // connect/attach has SETTLED into a failed/dropped/stuck state —
                    // never WHILE a connect/reconnect/attach is in progress (the
                    // maintainer's "no reconnect button while I'm connecting"). The
                    // in-progress states (`Connecting` / `Switching`(=Attaching) /
                    // `Reconnecting`) already drive the progress bar + "Attaching…/
                    // Reconnecting…" indicator, so the system is plainly already
                    // trying; the button would be redundant chrome. We show it on
                    // the remaining non-Connected states — `Failed` (honest error)
                    // and `Idle` (dropped/never-attached, with a `canReconnect`
                    // target) — exactly where a manual retry is meaningful. (This
                    // stays compatible with #886's planned `RevealState.Error`
                    // stuck-attach band, which projects to `Failed` here.)
                    showReconnectButton = status !is ConnectionStatus.Connecting &&
                        status !is ConnectionStatus.Switching &&
                        status !is ConnectionStatus.Reconnecting,
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
                    hasLiveDetection = currentAgentConversation?.detection != null,
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
                    onDictateTap = { showMicSheet = true },
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
                            hasLiveDetection = currentAgentConversation?.detection != null,
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
                    leadingChipContent = if (controlsInputEnabled) sessionChecklistChipState?.let { state ->
                        {
                            ChecklistChip(
                                state = state,
                                onClick = {
                                    viewModel.refreshActiveSessionCards()
                                    showChecklistSheet = true
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

        // Issue #898: the in-session "+ New session" rich sheet — the SAME
        // SessionTypePickerSheet the host/session-list screen uses (Session type
        // Shell/Agent, Agent CLI claude/codex/opencode, Skip permissions,
        // Profile). The stripped-down name+folder dialog is gone (hard-cut D22 —
        // one New-session UI). The Start folder defaults to the current
        // session's pane cwd (preserving the one good behaviour of the old
        // dialog); Create routes through viewModel.createSession — the SAME
        // verified gateway path the host screen uses — so the created session
        // honours type/CLI/skip-perms/profile identically, then navigates to the
        // new session via onOpenTmuxSession (which attaches to the now-existing
        // session).
        if (showNewSessionSheet) {
            val folderPath = currentPane?.cwd?.takeIf { it.isNotBlank() }
                ?: DEFAULT_TMUX_START_DIRECTORY
            SessionTypePickerSheet(
                folderPath = folderPath,
                folderLabel = FolderListViewModel.defaultLabelForPath(folderPath),
                onDismiss = { showNewSessionSheet = false },
                suggestStartDirectories = suggestStartDirectories,
                claudeProfiles = newSessionClaudeProfiles,
                codexProfiles = newSessionCodexProfiles,
                onCreate = { choice ->
                    showNewSessionSheet = false
                    // Issue #898 (Finding 1): pass the host's already-known
                    // session names into the deriver so a second "New session"
                    // in the SAME folder gets a deterministic `-2`/`-3` suffix
                    // instead of colliding with the existing same-named session
                    // (the gateway/tmux has no server-side de-dupe, so a
                    // duplicate name fails the create). This mirrors the host
                    // screen, which passes `knownSessionNames(state)`.
                    //
                    // Issue #976: the de-dupe ONLY works when this list is
                    // populated. If the picker is NOT `Ready` (a #974 connection
                    // drop / still-loading list flips it to Loading/ConnectError),
                    // the list collapses to ∅, the `-2`/`-3` suffix is skipped,
                    // the derived name COLLIDES with the live same-folder session,
                    // and the launch send-keys would type into that existing pane.
                    // So do NOT proceed with a possibly-colliding name when the
                    // list is unknown — block with a clear message and let the
                    // user retry once the list is `Ready`. (The gateway's
                    // has-session guard is the server-side safety net; this avoids
                    // even attempting the collision-prone create.)
                    val readyPicker =
                        sessionPickerState as? HostTmuxSessionPickerState.Ready
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
            )
        }

        // Epic #821 Slice 1: the session-kind classify / change picker. Opens
        // in the "unknown" prompt mode for a foreign session (no recorded
        // kind), otherwise the "change kind" mode pre-selected on the current
        // recorded kind. On Save it writes the durable host-side
        // `@ps_agent_kind` via ManualKindWriter and re-reads it.
        if (showKindPicker) {
            com.pocketshell.app.projects.SessionKindPickerSheet(
                sessionName = sessionName,
                onDismiss = { showKindPicker = false },
                onPick = { kind ->
                    viewModel.setCurrentSessionKind(kind)
                    showKindPicker = false
                },
                isUnknown = currentSessionRecordedKind == null,
                currentKind = currentSessionRecordedKind,
                // Option B today: no guess. A follow-up (Option A) passes a
                // cgroup-based suggestion here with zero other change.
                suggestedKind = null,
                // Issue #858: the recorded non-default profile/provider, so a
                // z.ai Claude reads differently from a default Claude here.
                currentProfile = currentSessionRecordedProfile,
            )
        }

        // Issue #497: in-app file viewer path-entry dialog. The active
        // pane's cwd is threaded through so a relative path the agent
        // referenced resolves server-side in the viewer.
        if (showOpenFileDialog) {
            val paneCwd = currentPane?.cwd?.takeIf { it.isNotBlank() }
            AlertDialog(
                onDismissRequest = { showOpenFileDialog = false },
                title = { Text("Open file") },
                text = {
                    Column {
                        Text(
                            text = if (paneCwd != null) {
                                "Enter a path. Relative paths resolve against $paneCwd."
                            } else {
                                "Enter an absolute path, or a path relative to your home directory."
                            },
                            color = PocketShellColors.TextSecondary,
                            fontSize = 12.sp,
                        )
                        OutlinedTextField(
                            value = openFilePath,
                            onValueChange = { openFilePath = it },
                            singleLine = true,
                            placeholder = { Text("e.g. out/report.png") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                                .testTag(TMUX_OPEN_FILE_DIALOG_FIELD_TAG),
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = openFilePath.isNotBlank(),
                        onClick = {
                            val path = openFilePath.trim()
                            showOpenFileDialog = false
                            if (path.isNotEmpty()) onOpenFile(path, paneCwd)
                        },
                        modifier = Modifier.testTag(TMUX_OPEN_FILE_DIALOG_CONFIRM_TAG),
                    ) {
                        Text("Open")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showOpenFileDialog = false }) { Text("Cancel") }
                },
            )
        }

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

    // Issue #488: confirm dialog for a tapped server-local URL whose remote
    // port is not yet forwarded. Confirming routes through the existing
    // port-forward flow (#447/#448 prefill) which opens the panel and sets up
    // the tunnel, then opens the working local URL once the actual local port
    // is known.
    pendingLocalhostForward?.let { pending ->
        val targetHost = hostName.ifBlank { host }
        AlertDialog(
            onDismissRequest = { pendingLocalhostForward = null },
            title = { Text("Forward port ${pending.remotePort}?") },
            text = {
                Text(
                    "${pending.remotePort} is a port on $targetHost, " +
                        "not reachable directly from this phone. Forward it " +
                        "to open it here.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = acceptedLocalhostForwardNavigation(pending)
                        pendingLocalhostForward = null
                        onOpenPortForwardingWithPort(target.remotePort, target.autoOpenLocalhostUrl)
                    },
                ) {
                    Text("Forward")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingLocalhostForward = null }) {
                    Text("Cancel")
                }
            },
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

    if (showMicSheet) {
        // PromptComposerSheet drives dictation + the one-field API-key
        // entry dialog (the inline-dictation path delegates the key entry
        // here too). `onSend` routes through writeInputToPane so the
        // composer's Send / Send+Enter buttons reach the focused tmux pane
        // via `send-keys`, identical to chip taps and snippet picks.
        //
        // Issue #459: the Conversation tab now shares this same composer as
        // its only send affordance (the bespoke in-pane "Message …" field
        // is gone). When the focused pane is showing its Conversation tab we
        // route through `sendToAgentPaneResult`, which submits to the agent
        // AND appends the optimistic user Message into the conversation feed
        // so the sent prompt appears in the transcript — exactly what the
        // old in-pane composer did. Terminal-tab sends keep the raw
        // write-bytes path.
        // Issue #755: the terminal hotkey key bar now rides INSIDE the composer's
        // inset-anchored column (PR2 of the composer redesign), so it can never be
        // gone (hard-cut, D22). The terminal hotkeys now live in the dedicated
        // [TerminalHotkeysPanel] bottom sheet (issue #784), opened from the
        // terminal bottom controls — its own surface, never inside the composer,
        // never part of the soft keyboard. The composer keeps ONLY compose-field +
        // Send + mic + attach + snippets + `/`-autocomplete.
        PromptComposerSheet(
            viewModel = promptComposerViewModel,
            // Issue #767: the detected engine for the focused pane drives the
            // `/`-autocomplete command catalog in the composer. Reuse the same
            // flicker-resilient `paletteAgent` (live detection, or the sticky
            // last-known kind) the standalone command palette uses, falling back
            // to the optimistic `presumedAgentKind` during the slow-detection
            // window so `/` still offers commands the moment the user opens the
            // composer over a freshly-launched agent pane. Null on a shell pane,
            // where the dropdown is never shown.
            agentKind = paletteAgent ?: presumedAgentKind,
            // Issue #746/#899: scope the shared activity-level composer draft to
            // the exact target identity so a "Not sent" draft authored here never
            // bleeds into another session on a switch or same-name recreation.
            composerTargetKey = targetSessionId.value,
            onDismiss = { showMicSheet = false },
            // Issue #745: surface the live connection state in the composer so a
            // send while the SSH/tmux link is degraded shows a connection-lost
            // indicator immediately rather than leaving the user waiting blind.
            connectionLost = !sessionLive,
            sendTargetSnapshotProvider = { withEnter ->
                val pane = surfacePane
                val liveAgent = currentAgentConversation?.detection?.agent
                val viewingConversationNow = currentAgentConversation?.detection != null &&
                    currentAgentConversation.selectedTab == SessionTab.Conversation
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
            collectSendRequests = false,
            hostId = hostId.takeIf { it != 0L },
            onStageAttachments = { uris ->
                // Issue #451: Attach connects-on-action like Send. Do NOT
                // hard-fail here on `!sessionLive` — the file picker
                // backgrounds the app, so on return the session may be
                // briefly absent. `stagePromptAttachments` lazily
                // (re)connects and awaits the live session before uploading,
                // surfacing the error (with the draft kept) only if the
                // connect never lands within the bounded wait.
                viewModel.stagePromptAttachments(uris)
            },
        )
    }

    if (showSnippetPicker && hostId != 0L) {
        // Mirrors SessionScreen's snippet wiring.
        //  - Explicit `Send` / `Send + ↵` chips (`onSnippetSend`) honour
        //    the user's overt Enter intent for issue #187. The trailing
        //    `\r` is appended when `withEnter == true` and only then.
        //  - Per D22 (issue #227) the legacy row-body smart-default tap
        //    surface was removed; the picker only routes through the
        //    explicit-intent chip callback.
        SnippetPickerSheet(
            hostId = hostId,
            onDismiss = { showSnippetPicker = false },
            kindFilter = if (currentAgentConversation?.detection != null) {
                SnippetKind.Prompt
            } else {
                SnippetKind.Command
            },
            onSnippetSend = { snippet, withEnter ->
                // Issue #249: same liveness guard as the prompt composer —
                // never write a snippet into a dead pane and lose the tap.
                // Issue #797: route to the SURFACE (visible) pane like the
                // composer send, so a snippet on a settled (promoted) pane lands.
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
        )
    }

    if (showChecklistSheet) {
        ChecklistCardsSheet(
            cards = checklistCards,
            onToggle = { cardId, itemId, checked ->
                viewModel.toggleChecklistItem(
                    cardId = cardId,
                    itemId = itemId,
                    checked = checked,
                )
            },
            onDismiss = { showChecklistSheet = false },
        )
    }

    // Issue #787: the standalone agent slash-command palette (`AgentCommandSheet`)
    // and the bottom `/ commands` chip were a hard-cut here (D22). Slash-command
    // entry now lives ONLY in the composer (its `/` button + type-`/`
    // autocomplete). The palette's `Ctrl-C ×2` / `Ctrl-D ×2` interrupt/EOF
    // controls were re-homed into the hotkeys panel's "INTERRUPT / EOF" section
    // (see [TmuxHotkeyInterruptX2Label] / [TmuxHotkeyEofX2Label]) so no function
    // was lost.

    // Issue #784: the dedicated terminal-hotkeys panel — its OWN bottom-sheet
    // surface (not inside the composer, not part of the soft keyboard). Shows
    // EVERY key at once in a tidy grid (no `…` overflow, no horizontal scroll).
    // Terminal tab only (a raw pane to receive the control bytes); each key
    // routes through [TmuxSessionViewModel.onKeyBarKey]. The panel stays open
    // after a tap so the user can fire several keys (e.g. arrow navigation,
    // `^B ^B`) without re-opening; `×` / scrim / back dismiss it.
    // Issue #797: the hotkeys panel (opened from the surface-pane bottom
    // controls) routes its control bytes to the SURFACE pane so a `^C`/arrow on
    // a settled (promoted) pane reaches the session the user is looking at.
    val hotkeysPane = surfacePane
    if (showHotkeysPanel && hotkeysPane != null) {
        TerminalHotkeysSheet(
            sections = TmuxHotkeyPanelSections,
            enabled = sessionLive,
            onKey = { binding ->
                if (sessionLive) {
                    DiagnosticEvents.record(
                        "action",
                        "shortcut_sent",
                        "mode" to "tmux",
                        "paneId" to hotkeysPane.paneId,
                        "key" to binding.label,
                    )
                    viewModel.onKeyBarKey(hotkeysPane.paneId, binding.label)
                }
            },
            onDismiss = { showHotkeysPanel = false },
        )
    }

}

internal data class PortForwardNavigationTarget(
    val remotePort: Int,
    val autoOpenLocalhostUrl: LocalhostUrl?,
)

internal fun acceptedLocalhostForwardNavigation(localhostUrl: LocalhostUrl): PortForwardNavigationTarget =
    PortForwardNavigationTarget(
        remotePort = localhostUrl.remotePort,
        autoOpenLocalhostUrl = localhostUrl,
    )

internal fun detectedPortForwardNavigation(remotePort: Int): PortForwardNavigationTarget =
    PortForwardNavigationTarget(
        remotePort = remotePort,
        autoOpenLocalhostUrl = null,
    )

/**
 * Issue #448 (epic #432 slice C): the non-blocking "forward this newly
 * detected port?" overlay. Rendered as a bottom banner that floats over
 * the terminal — it deliberately does not cover the terminal viewport or
 * intercept terminal input, so the user can keep typing while deciding.
 * [port] non-null shows the banner; null hides it (with a fade).
 */
@Composable
internal fun DetectedPortOverlay(
    port: Int?,
    onForward: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Keep the last non-null port so the banner text stays painted through
    // the exit fade (the flow flips to null before the animation finishes).
    var shownPort by remember { mutableStateOf(0) }
    if (port != null) shownPort = port
    AnimatedVisibility(
        visible = port != null,
        modifier = modifier,
        enter = fadeIn(animationSpec = tween(durationMillis = MotionDurationMs)),
        exit = fadeOut(animationSpec = tween(durationMillis = MotionDurationMs)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .background(PocketShellColors.Surface, RoundedCornerShape(10.dp))
                .border(
                    width = 1.dp,
                    color = PocketShellColors.Accent,
                    shape = RoundedCornerShape(10.dp),
                )
                .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
                .testTag(TMUX_DETECTED_PORT_OVERLAY_TAG),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "New port $shownPort detected — forward it?",
                color = PocketShellColors.Text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(TMUX_DETECTED_PORT_DISMISS_TAG),
            ) {
                Text(
                    text = "Dismiss",
                    color = PocketShellColors.TextSecondary,
                    fontSize = 14.sp,
                )
            }
            TextButton(
                onClick = onForward,
                modifier = Modifier.testTag(TMUX_DETECTED_PORT_FORWARD_TAG),
            ) {
                Text(
                    text = "Forward",
                    color = PocketShellColors.Accent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun SessionSwitcherOverlay(
    visible: Boolean,
    state: HostTmuxSessionPickerState,
    hostName: String,
    currentSessionName: String,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
    onSelectSession: (String) -> Unit,
    onCreate: () -> Unit,
) {
    val pages = remember(state, currentSessionName) {
        sessionSwitcherPages(state, currentSessionName)
    }
    val initialPage = pages.indexOfFirst { it.name == currentSessionName }.coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { pages.size })

    LaunchedEffect(visible, currentSessionName, pages) {
        if (visible) {
            val page = pages.indexOfFirst { it.name == currentSessionName }
            if (page >= 0) pagerState.scrollToPage(page)
        }
    }

    LaunchedEffect(visible, pagerState, pages, currentSessionName) {
        if (!visible) return@LaunchedEffect
        snapshotFlow { pagerState.settledPage }.collect { page ->
            val session = pages.getOrNull(page) ?: return@collect
            if (session.name != currentSessionName && session.selectable) {
                onSelectSession(session.name)
            }
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(durationMillis = MotionDurationMs)) +
            slideInVertically(
                animationSpec = tween(durationMillis = MotionDurationMs, easing = MotionEasing),
                initialOffsetY = { it / 2 },
            ),
        exit = fadeOut(animationSpec = tween(durationMillis = MotionDurationMs)) +
            slideOutVertically(
                animationSpec = tween(durationMillis = MotionDurationMs, easing = MotionEasing),
                targetOffsetY = { it / 2 },
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(PocketShellColors.Background.copy(alpha = 0.92f))
                .clickable(onClick = onDismiss)
                .padding(16.dp)
                .testTag(TMUX_SESSION_PAGER_OVERLAY_TAG),
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .clickable(onClick = {})
                    .background(PocketShellColors.Surface, PocketShellShapes.extraSmall)
                    .border(
                        width = 1.dp,
                        color = PocketShellColors.BorderSoft,
                        shape = PocketShellShapes.extraSmall,
                    )
                    .padding(14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Sessions",
                            color = PocketShellColors.Text,
                            fontSize = 15.sp,
                        )
                        Text(
                            text = hostName,
                            color = PocketShellColors.TextSecondary,
                            fontSize = 12.sp,
                        )
                    }
                    TextButton(onClick = onCreate) {
                        Text("New")
                    }
                    TextButton(onClick = onDismiss) {
                        Text("Close")
                    }
                }

                HorizontalPager(
                    state = pagerState,
                    beyondViewportPageCount = Int.MAX_VALUE,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(118.dp)
                        .padding(top = 8.dp)
                        .testTag(TMUX_SESSION_PAGER_TAG),
                ) { page ->
                    val session = pages[page]
                    val selected = session.name == currentSessionName
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 4.dp)
                            .background(
                                color = if (selected) PocketShellColors.Accent else PocketShellColors.SurfaceElev,
                                shape = PocketShellShapes.extraSmall,
                            )
                            .clickable(
                                enabled = session.selectable,
                                role = androidx.compose.ui.semantics.Role.Tab,
                                onClick = { onSelectSession(session.name) },
                            )
                            .padding(16.dp)
                            .testTag("$TMUX_SESSION_PAGER_PAGE_TAG_PREFIX${page + 1}"),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = session.name,
                            color = if (selected) PocketShellColors.Background else PocketShellColors.Text,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = session.statusLabel,
                            color = if (selected) PocketShellColors.Background else PocketShellColors.TextSecondary,
                            fontSize = 12.sp,
                        )
                        Text(
                            text = "${page + 1} / ${pages.size}",
                            color = if (selected) PocketShellColors.Background else PocketShellColors.TextSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier.testTag(TMUX_SESSION_PAGER_INDICATOR_TAG),
                        )
                    }
                }

                if (state !is HostTmuxSessionPickerState.Ready) {
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onRefresh,
                    ) {
                        Text("Refresh")
                    }
                }
            }
        }
    }
}

@Composable
internal fun TmuxSessionDrawer(
    visible: Boolean,
    state: HostTmuxSessionPickerState,
    hostName: String,
    currentSessionName: String,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
    onAttach: (String) -> Unit,
    onCreate: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(durationMillis = MotionDurationMs)),
        exit = fadeOut(animationSpec = tween(durationMillis = MotionDurationMs)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(PocketShellColors.Background.copy(alpha = 0.72f))
                .clickable(onClick = onDismiss),
        )
    }
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(
            animationSpec = tween(durationMillis = MotionDurationMs, easing = MotionEasing),
            initialOffsetX = { it },
        ),
        exit = slideOutHorizontally(
            animationSpec = tween(durationMillis = MotionDurationMs, easing = MotionEasing),
            targetOffsetX = { it },
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.92f)
                    .widthIn(max = SessionDrawerMaxWidth)
                    .background(PocketShellColors.Surface)
                    .border(width = 1.dp, color = PocketShellColors.BorderSoft)
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .testTag(TMUX_SESSION_SWITCHER_TAG)
                    .clickable(onClick = {}),
            ) {
                // Issue #156 (4.1): stack the header vertically — title
                // row, subtitle row — with the close affordance pinned
                // top-right so it doesn't compress the title onto one
                // cramped line.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(end = 40.dp)) {
                        Text(
                            text = "Tmux sessions",
                            color = PocketShellColors.Text,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "$hostName / $currentSessionName",
                            color = PocketShellColors.TextSecondary,
                            fontSize = 12.sp,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(36.dp)
                            .testTag(TMUX_SESSION_DRAWER_CLOSE_TAG)
                            .clickable(
                                role = androidx.compose.ui.semantics.Role.Button,
                                onClick = onDismiss,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "×",
                            color = PocketShellColors.TextSecondary,
                            fontSize = 20.sp,
                        )
                    }
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = PocketShellSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm),
                ) {
                    item { SectionHeader(label = "Options") }
                    item(key = "create-session") {
                        TmuxSessionDrawerOptionRow(
                            label = "+ New session",
                            sublabel = "Separate workspace on this host",
                            onClick = onCreate,
                            modifier = Modifier.testTag(TMUX_SESSION_DRAWER_CREATE_TAG),
                        )
                    }
                    item(key = "refresh-sessions") {
                        TmuxSessionDrawerOptionRow(
                            label = "Refresh sessions",
                            sublabel = null,
                            onClick = onRefresh,
                            modifier = Modifier.testTag(TMUX_SESSION_DRAWER_REFRESH_TAG),
                        )
                    }
                    item {
                        SectionHeader(
                            label = "Available sessions",
                            count = (state as? HostTmuxSessionPickerState.Ready)?.rows?.size,
                        )
                    }
                    when (state) {
                        HostTmuxSessionPickerState.Idle,
                        is HostTmuxSessionPickerState.Loading,
                        -> item {
                            TmuxSessionDrawerMessage(
                                text = if (state is HostTmuxSessionPickerState.Loading) {
                                    "Loading sessions from ${state.hostName}..."
                                } else {
                                    "Loading sessions..."
                                },
                            )
                        }
                        is HostTmuxSessionPickerState.Ready -> {
                            state.message?.let { message ->
                                item { TmuxSessionDrawerMessage(text = message) }
                            }
                            items(state.rows, key = { it.name }) { row ->
                                TmuxSessionDrawerRow(
                                    row = row,
                                    selected = row.name == currentSessionName,
                                    enabled = row.name != currentSessionName,
                                    onClick = { onAttach(row.name) },
                                )
                            }
                        }
                        is HostTmuxSessionPickerState.Fallback -> item {
                            TmuxSessionDrawerMessage(text = state.message)
                        }
                        // Issue #109: in-session drawer also handles
                        // connect-error by rendering the same friendly
                        // body line. Retry/raw-shell affordances live on
                        // the host-list sheet only — from inside an
                        // already-open session the user typically wants
                        // the message and can dismiss the drawer to
                        // continue with the live connection.
                        is HostTmuxSessionPickerState.ConnectError -> item {
                            val host = state.request.host
                            TmuxSessionDrawerMessage(
                                text = "Couldn't reach ${host.username}@${host.hostname}:${host.port}. " +
                                    state.summary.shortReason,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TmuxSessionDrawerMessage(text: String) {
    Text(
        text = text,
        color = PocketShellColors.TextSecondary,
        style = PocketShellType.bodyDense,
        modifier = Modifier
            .fillMaxWidth()
            .background(PocketShellColors.SurfaceElev, PocketShellShapes.extraSmall)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    )
}

@Composable
private fun TmuxSessionDrawerOptionRow(
    label: String,
    sublabel: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(PocketShellColors.SurfaceElev, PocketShellShapes.small)
            .border(
                width = 1.dp,
                color = PocketShellColors.BorderSoft,
                shape = PocketShellShapes.small,
            ),
    ) {
        ListRow(
            title = label,
            subtitle = sublabel,
            onClick = onClick,
            modifier = modifier,
            trailing = {
                Text(
                    text = "›",
                    color = PocketShellColors.TextSecondary,
                    style = PocketShellType.bodyDense,
                    fontWeight = FontWeight.SemiBold,
                )
            },
        )
    }
}

@Composable
private fun TmuxDrawerRowContainer(
    selected: Boolean,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = if (selected) PocketShellColors.AccentSoft else PocketShellColors.SurfaceElev,
                shape = PocketShellShapes.small,
            )
            .border(
                width = 1.dp,
                color = if (selected) PocketShellColors.AccentDim else PocketShellColors.BorderSoft,
                shape = PocketShellShapes.small,
            ),
    ) {
        content()
    }
}

@Composable
private fun TmuxSessionDrawerRow(
    row: HostTmuxSessionRow,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TmuxDrawerRowContainer(selected = selected) {
        ListRow(
            title = row.name,
            subtitle = when {
                selected -> "current"
                row.attached -> "attached"
                else -> "available"
            },
            leading = {
                StatusDot(
                    status = if (row.attached || selected) {
                        UiConnectionStatus.Connected
                    } else {
                        UiConnectionStatus.Idle
                    },
                )
            },
            trailing = {
                Box(
                    modifier = Modifier.defaultMinSize(minWidth = PocketShellDensity.tapTargetMin),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Badge(
                        label = if (selected) "Open" else "Attach",
                        role = if (selected) BadgeRole.Active else BadgeRole.Idle,
                        mono = false,
                    )
                }
            },
            onClick = if (enabled) onClick else null,
            modifier = Modifier
                .padding(vertical = if (selected) 1.dp else 0.dp)
                .defaultMinSize(minHeight = PocketShellDensity.tapTargetMin),
        )
    }
}

internal data class SessionSwitcherPage(
    val name: String,
    val statusLabel: String,
    val selectable: Boolean,
)

internal data class TmuxSessionTabState(
    val labels: List<String>,
    val selectedIndex: Int,
    val showsConversationTab: Boolean,
)

/**
 * Issue #716 (Slice A): is the visible pane an agent OR a *presumed* agent?
 *
 * The maintainer's #1 complaint: agent-detection is slow/uncertain right after
 * attach/switch/send, so `detection == null` for a while even though the
 * session IS an agent. The old gating keyed the whole agent surface
 * (Conversation tab, agent-aware chips, agent send-routing) on the single
 * positive signal `detection != null` — so the composer/agent surface
 * *vanished* during that window and the user couldn't type to the agent.
 *
 * The fix is to default to **presumed-agent** during uncertainty: a pane is
 * presumed-agent when
 *  - live detection landed (`detection != null`), OR
 *  - this pane was/is known to be an agent (the #462 `stickyAgentForPane` /
 *    `paletteAgent` last-known kind), OR
 *  - detection simply has not positively confirmed a shell yet.
 *
 * The agent surface is hidden ONLY on a positively-confirmed shell verdict
 * ([confirmedShell]). Issue #894 (Slice C) WIRES that verdict: the call site
 * now passes a real `confirmedShell` from the durable per-session recorded
 * `@ps_agent_kind=shell` record (via `TmuxSessionViewModel.confirmedShellPaneIds`),
 * NOT the old "no agent matched → assume shell" absence. So a genuine shell
 * pane collapses the surface (and never flashes the #878 "Loading
 * conversation…" placeholder), while a foreign / not-yet-classified pane keeps
 * the presumed-agent surface available throughout the detection window — the
 * #878 black-screen cure stays intact.
 *
 * Net effect: the composer/agent surface is available unless/until a trustworthy
 * shell verdict exists. A foreign agent session may show an empty Conversation
 * tab in the interim — an accepted trade-off (the maintainer's explicit non-goal
 * is a per-session toggle).
 */
internal fun tmuxSessionPresumedAgent(
    hasLiveDetection: Boolean,
    stickyAgent: AgentKind?,
    confirmedShell: Boolean,
): Boolean {
    // A trustworthy confirmed-shell verdict is the ONLY thing that collapses
    // the agent surface. Live detection or a known agent kind obviously make
    // the pane presumed-agent; but even with neither, absence of a positive
    // shell verdict means "not yet known to be shell" — which #716 treats as
    // presumed-agent so the composer/agent surface stays available during the
    // slow-detection window. (Slice C, issue #894, supplies the real verdict
    // from the durable recorded `@ps_agent_kind=shell` record; Slice A passed a
    // hard-wired `false`.)
    //
    // Issue #962: a recorded `@ps_agent_kind=shell` session with a live agent
    // started INSIDE it is re-classified OUT of confirmed-shell by the VM the
    // instant live detection binds the pane's agent
    // (`TmuxSessionViewModel.markAgentTailLive` → `applyRecordedShellVerdict(
    // isShell = false)`), so `confirmedShell` here is already false for such a
    // pane and the Conversation toggle shows. (Driving the override off the
    // AUTHORITATIVE detection event — not the pane's `#{pane_current_command}`,
    // which is the wrapper shell `comm` for a node-wrapped agent — is what makes
    // it fire for a real node-wrapped claude.)
    if (confirmedShell) return false
    return true
}

/**
 * Issue #716 (Slice A): the visible pane wears agent-aware chrome (agent
 * chips, no snippet picker, agent-payload send) for a live-detected agent OR a
 * presumed agent. Equivalent to [presumedAgent] today since presumed-agent
 * already subsumes the live-detection case, but kept as a named helper so the
 * call site reads intentionally and so a future confirmed-shell signal flows
 * through one place.
 */
internal fun tmuxSessionIsAgentPane(
    hasLiveDetection: Boolean,
    presumedAgent: Boolean,
): Boolean = hasLiveDetection || presumedAgent

/**
 * Issue #805 (regression of #744/#716): whether the bottom-bar chrome wears its
 * CONVERSATION layout — i.e. drops the Terminal-tab chips (`Enter` /
 * `show keyboard` / `hotkeys`) so the composer launcher fits.
 *
 * This must follow the Conversation TAB, not the detection-gated transcript.
 * The screen has two distinct "conversation is showing" signals:
 *
 *  - [showConversationTranscript] — the heavyweight live transcript is mounted
 *    (detection has landed). The OLD bottom-bar predicate.
 *  - [showConversationDetectingPlaceholder] — the user is on the Conversation
 *    tab but the agent engine is still being detected (`detection == null`), so
 *    the "Loading conversation…" placeholder is shown instead.
 *
 * On v0.4.7 the bottom bar keyed its Conversation chrome off the transcript
 * signal ALONE. During detection (placeholder shown, transcript not yet mounted)
 * the bar fell back to the THREE Terminal chips, which overflowed the primary
 * cluster and pushed the composer launcher off the right edge — the maintainer's
 * "composer absent while the agent is being detected" symptom (#805). The #744
 * invariant is that the composer stays reachable throughout the detection
 * window; this helper restores it by treating the WHOLE Conversation tab
 * (detecting OR loaded) as conversation chrome.
 */
internal fun tmuxSessionBottomControlsShowsConversation(
    showConversationTranscript: Boolean,
    showConversationDetectingPlaceholder: Boolean,
): Boolean = showConversationTranscript || showConversationDetectingPlaceholder

/**
 * Issue #761 / #454: whether the bottom controls surface the saved-snippet
 * picker chip (`+ snippet`).
 *
 * The snippet chip is a SHELL-pane affordance (#454: "shell panes keep the
 * saved-snippet picker"); agent panes intentionally omit it because the
 * composer's `{}` affordance already inserts saved prompts (#453). The chip is
 * therefore the inverse of being a *known* agent pane.
 *
 * Crucially it is gated on the ACTUAL agent signal — live detection OR a sticky
 * known-agent kind ([paletteAgent]) — NOT on the optimistic presumed-agent
 * default from #716. #716 makes every freshly-attached tmux pane presumed-agent
 * so the *composer / conversation surface* never vanishes during the
 * slow-detection window; but reusing that optimistic flag to gate the snippet
 * chip suppressed it on EVERY tmux pane, including genuine shells that had
 * never hosted an agent (the bug behind #761: the `session:add-snippet-chip`
 * tag was never in the tree on a shell pane). Gating on real agent evidence
 * keeps the composer available on a fresh pane (#716) while still showing the
 * snippet chip there until/unless the pane is actually known to be an agent —
 * symmetric with how the `/ commands` chip is gated on `paletteAgent != null`.
 *
 * @param hasHost the visible host is persisted (snippets are host-scoped; a
 *   transient/zero host id has no snippets to pick).
 * @param hasLiveDetection a live agent detection landed for this pane.
 * @param hasStickyAgent a previously-detected agent kind is remembered for this
 *   pane ([paletteAgent] / #462 sticky resilience).
 */
internal fun tmuxSessionShowsSnippetChip(
    hasHost: Boolean,
    hasLiveDetection: Boolean,
    hasStickyAgent: Boolean,
): Boolean = hasHost && !hasLiveDetection && !hasStickyAgent

/**
 * Issue #797 (Shape A — surface-follows-visible-pane): the pane that drives the
 * COMPOSER SURFACE + Conversation tab + detection lookup + send/input routing.
 *
 * The bug: the old gate keyed every one of those off `currentPane`, which is
 * `null` whenever the unified pager is settled on a CACHED (non-active) session
 * pane (`TmuxSessionViewModel.isActiveSessionPane` checks only `_panes.value`).
 * When a warm switch had not yet promoted the target into `_panes.value`, the
 * user was parked on a cached pane with `currentPane == null` → the
 * presumed-agent default (#716/#744) short-circuited to false → the composer
 * launcher vanished AND the Conversation tab emptied (the maintainer's live
 * repro: "composer button gone, Conversation tab shows nothing, not detected as
 * an agent"). Both symptoms collapse to the single `currentPane == null`
 * condition.
 *
 * The surface must follow the VISIBLE pane (whether active or cached) so the
 * composer + Conversation tab never vanish while the user is settled on a real
 * pane. The ONE exception is while a cross-session switch is hiding the terminal
 * (`switchHidesTerminal` — the reveal-hold overlay): the visible pane is then
 * the LEAVING session and we must NOT route anything to it (#661/#634/#636
 * stale-session content-bleed hazard), so the surface is suppressed for that
 * brief switch-in-flight window only and reappears the instant the target is
 * revealed/promoted.
 *
 * Returning the visible cached pane here is SAFE for routing because the cached
 * pane is exactly the session the user is parked on (their intended target) and
 * the companion promotion path ([tmuxSessionShouldPromoteSettledCachedPane])
 * promotes it to active — once active, `clientRef`/`_panes.value` bind input +
 * detection to it. Routing always targets the surface pane's own `paneId`, never
 * a stale leaving-session pane.
 *
 * @param visibleUnifiedPane the pane the pager is actually showing
 *   (`unifiedPanes[currentPage]`), active or cached.
 * @param switchHidesTerminal true while a cross-session switch holds the reveal
 *   (the leaving session's content must not be routed to or surfaced).
 */
internal fun tmuxSessionSurfacePane(
    visibleUnifiedPane: TmuxPaneState?,
    switchHidesTerminal: Boolean,
): TmuxPaneState? = if (switchHidesTerminal) null else visibleUnifiedPane

/**
 * Issue #797 (promotion-on-settle): decide whether a unified-pager settle that
 * came to rest on a CACHED (non-active) pane should trigger a warm switch
 * (pane-promotion) so input routing + agent detection are RESTORED for the
 * visible session.
 *
 * Why this is needed beyond the surface fix: a cached pane's bytes/detection are
 * owned by a DIFFERENT tmux runtime client than the active `clientRef`, so
 * showing a composer over a not-yet-promoted cached pane would be the
 * "fixed-but-still-broken" trap — the composer reappears but Send can't reach
 * the session and detection never fires. Promotion (the existing warm switch,
 * `sessionSwitchRequest` → connect → `clientRef`/`_panes.value` adopt the
 * target) is what actually rebinds input + detection.
 *
 * The drag-gated swipe path ([settleSessionSwitchTarget]) intentionally requires
 * a fresh user drag to avoid the #661/#634/#636 stale-settle yank. But that left
 * the PERSISTENT-STALL hole the maintainer hit: a cached pane the user is
 * genuinely parked on (no recorded drag, e.g. it became cached via a reconcile /
 * reorder) never promotes, so input stays dead "until I switched sessions several
 * times". This helper closes that hole WITHOUT reintroducing the stale-settle
 * bleed: it fires only when the pager is ALIGNED to the nav target and at rest on
 * a genuinely-cached OTHER session's pane. The #661 stale-during-connect artifact
 * is still guarded independently on the VM side
 * ([TmuxSessionViewModel.onUnifiedPageSettled]'s `connectingTarget`/`connectJob`
 * check), which suppresses a promote toward anything other than an in-flight
 * deliberate destination — so a stale index observed while a connect to a
 * DIFFERENT session runs never promotes the wrong session.
 *
 * @param settledPaneSession the session owning the settled pane (`null` when
 *   unresolved / mid-rebuild).
 * @param navTargetSession the nav destination's session (the screen's target).
 * @param settledPaneIsActiveSession whether the settled pane already belongs to
 *   the active session (`_panes.value`); if so there is nothing to promote.
 * @param pagerAlignedToNavTarget whether the pager has realigned to the nav
 *   target since the last target change (settles before realignment are
 *   stale-index artifacts).
 * @param switchHidesTerminal true while a switch holds the reveal — never promote
 *   off a stale settle while a deliberate switch is already in flight.
 */
internal fun tmuxSessionShouldPromoteSettledCachedPane(
    settledPaneSession: String?,
    navTargetSession: String,
    settledPaneIsActiveSession: Boolean,
    pagerAlignedToNavTarget: Boolean,
    switchHidesTerminal: Boolean,
): Boolean {
    if (switchHidesTerminal) return false
    if (!pagerAlignedToNavTarget) return false
    if (settledPaneIsActiveSession) return false
    if (settledPaneSession == null) return false
    if (settledPaneSession == navTargetSession) return false
    return true
}

internal fun tmuxSessionTabState(
    currentAgentConversation: AgentConversationUiState?,
    presumedAgent: Boolean = false,
): TmuxSessionTabState {
    // The Conversation tab exists for a live-detected agent OR a presumed
    // agent (#716). Issue #778: the active index now follows the user's
    // `selectedTab` choice on ANY pane that shows the Conversation tab — a
    // presumed agent counts, even before live detection lands. The old gate
    // required `hasLiveDetection`, which made a Conversation tap a no-op during
    // the slow-detection window (the tab was drawn but the index could never
    // become 1). Honouring the presumed-agent selection lets the screen render
    // a "waiting for agent" placeholder instead of swallowing the tap; the real
    // transcript replaces it the instant detection seeds. The selection is still
    // gated on `showsConversationTab` so a confirmed shell (no tab) can never
    // land on the Conversation index from a stale row.
    val hasLiveDetection = currentAgentConversation?.detection != null
    val showsConversationTab = hasLiveDetection || presumedAgent
    return TmuxSessionTabState(
        labels = if (showsConversationTab) listOf("Terminal", "Conversation") else listOf("Terminal"),
        selectedIndex = if (showsConversationTab &&
            currentAgentConversation?.selectedTab == SessionTab.Conversation
        ) {
            1
        } else {
            0
        },
        showsConversationTab = showsConversationTab,
    )
}

/**
 * Issue #716 (Slice A): which send path the unified prompt composer uses for
 * the visible pane.
 *
 *  - [AgentConversation]: live-detected agent on the Conversation tab — submit
 *    to the agent AND echo the optimistic user turn into the transcript
 *    (`sendToAgentPaneResult`). Unchanged from before #716.
 *  - [AgentPayload]: route through the agent payload formatter
 *    (`sendAgentPayloadToPaneResult`) so the prompt reaches the agent without a
 *    raw-bytes fallthrough. Two cases land here, both preserving prior
 *    behaviour for confirmed agents:
 *      1. The pre-#716 Codex-on-Terminal-tab `withEnter` special case
 *         (`liveAgent == Codex`).
 *      2. #716: a *presumed* agent with NO live detection yet
 *         (`liveAgent == null`) but a known/last-known agent kind
 *         ([presumedAgentKind]) — the slow-detection window. The prompt still
 *         reaches the agent (no raw-bytes fallthrough); there is no transcript
 *         to echo into yet, so no optimistic turn is inserted.
 *  - [RawBytes]: the plain shell write path. A *confirmed* agent
 *    (`liveAgent != null`) on the Terminal tab keeps its pre-#716 raw-bytes
 *    behaviour here (except the Codex special case above), so a confirmed agent
 *    is unchanged. RawBytes is also the path for a genuine no-agent pane.
 */
internal enum class TmuxComposerSendRoute { AgentConversation, AgentPayload, RawBytes }

internal fun tmuxComposerSendRoute(
    viewingConversation: Boolean,
    liveAgent: AgentKind?,
    presumedAgentKind: AgentKind?,
    withEnter: Boolean,
): TmuxComposerSendRoute = when {
    // Conversation tab of a live-detected agent: agent submit + optimistic echo.
    viewingConversation -> TmuxComposerSendRoute.AgentConversation
    // Codex on the Terminal tab keeps its with-Enter payload formatting (the
    // pre-#716 special case) — route through the agent payload path.
    withEnter && liveAgent == AgentKind.Codex -> TmuxComposerSendRoute.AgentPayload
    // Confirmed agent (live detection) on the Terminal tab: unchanged pre-#716
    // raw-bytes behaviour. Only Codex (above) deviates.
    liveAgent != null -> TmuxComposerSendRoute.RawBytes
    // #716: presumed-agent without a live transcript yet — still route to the
    // agent, not raw bytes, using the known/last-known agent kind.
    presumedAgentKind != null -> TmuxComposerSendRoute.AgentPayload
    else -> TmuxComposerSendRoute.RawBytes
}

internal fun tmuxComposerOutboundRoute(route: TmuxComposerSendRoute): OutboundRoute = when (route) {
    TmuxComposerSendRoute.AgentConversation -> OutboundRoute.AgentConversation
    TmuxComposerSendRoute.AgentPayload -> OutboundRoute.AgentPayload
    TmuxComposerSendRoute.RawBytes -> OutboundRoute.RawBytes
}

internal fun tmuxComposerAgentToken(agent: AgentKind?): String? = when (agent) {
    AgentKind.ClaudeCode -> "claude"
    AgentKind.Codex -> "codex"
    AgentKind.OpenCode -> "opencode"
    null -> null
}

internal fun tmuxComposerAgentKindFromToken(token: String?): AgentKind? = when (token?.lowercase()) {
    "claude", "claudecode", "claude_code" -> AgentKind.ClaudeCode
    "codex" -> AgentKind.Codex
    "opencode", "open_code" -> AgentKind.OpenCode
    else -> null
}

internal fun tmuxComposerSendTargetSnapshot(
    sessionKey: String,
    paneId: String?,
    route: TmuxComposerSendRoute,
    agentKind: AgentKind?,
): PromptComposerViewModel.SendTargetSnapshot =
    PromptComposerViewModel.SendTargetSnapshot(
        sessionKey = sessionKey,
        paneId = paneId.orEmpty(),
        route = tmuxComposerOutboundRoute(route),
        agentKind = tmuxComposerAgentToken(agentKind),
    )

internal fun handleTmuxSessionSelection(
    currentSessionName: String,
    selectedSessionName: String,
    onDismiss: () -> Unit,
    onReplace: (String) -> Unit,
) {
    if (selectedSessionName == currentSessionName) return
    onDismiss()
    onReplace(selectedSessionName)
}

/**
 * Issue #900: state holder for the screen-level outbound queue auto-flush
 * effects. It resets the per-live-window failure exclusion set when the
 * connection window changes, requeues stale in-flight rows when a target becomes
 * live, and retries at most one queue row per queue snapshot.
 */
internal class OutboundQueueAutoFlushController {
    private var windowKey: Pair<Boolean, String>? = null
    private var autoRetriedIds: Set<String> = emptySet()

    fun onConnectionWindowChanged(
        sessionLive: Boolean,
        targetSessionId: String,
        requeueStaleInFlight: () -> Unit,
    ) {
        val nextKey = sessionLive to targetSessionId
        if (nextKey == windowKey) return
        windowKey = nextKey
        autoRetriedIds = emptySet()
        if (sessionLive) requeueStaleInFlight()
    }

    fun onQueueSnapshotChanged(
        sessionLive: Boolean,
        retryNext: (excludingIds: Set<String>) -> String?,
    ): String? {
        if (!sessionLive) return null
        val retriedId = retryNext(autoRetriedIds) ?: return null
        autoRetriedIds = autoRetriedIds + retriedId
        return retriedId
    }
}

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
    status: ConnectionStatus,
): Boolean =
    canReconnect &&
        status !is ConnectionStatus.Connecting &&
        status !is ConnectionStatus.Switching &&
        status !is ConnectionStatus.Reconnecting

/**
 * Issue #750: the single-indicator gate for the top under-header
 * [ReconnectingProgressRow] progress line.
 *
 * The reconnect/reattach screen must show EXACTLY ONE loading indicator — the
 * centered "Attaching…" [SwitchingLoadingPlaceholder]. After the #766
 * connection migration the controller projects [ConnectionStatus.Reconnecting]
 * (this top bar) at the SAME time the id-keyed [RevealStateMachine] holds the
 * terminal in [RevealState.Seeding] ([effectiveHidesTerminal] == true) and
 * paints the centered spinner — the two-loaders regression.
 *
 * This pure predicate makes the invariant a unit-testable wiring guard rather
 * than a comment: the top bar renders ONLY for a [ConnectionStatus.Reconnecting]
 * status AND only when the terminal is NOT held (so the centered spinner is not
 * up). Every real reconnect/reattach holds the terminal, so in practice the
 * centered spinner is the sole reattach affordance — but the predicate keeps the
 * top bar as a (currently unreached) fallback for any future reconnect that does
 * keep a live frame painted, so suppressing it can never leave a reconnect with
 * zero indicators.
 */
internal fun shouldShowReconnectingProgressRow(
    status: ConnectionStatus,
    effectiveHidesTerminal: Boolean,
): Boolean = status is ConnectionStatus.Reconnecting && !effectiveHidesTerminal

/**
 * Issue #463: the short leaf label for the header project crumb, derived
 * from the active pane's working directory (the project path). Returns the
 * last path segment (e.g. `/home/alexey/git/pocketshell` → `pocketshell`,
 * `~/work` → `work`). The home directory and root collapse to `~` and `/`
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
 * resolves to the LEAVING project. The two sources then DESYNC — the label
 * already shows the target while the crumb still wears the leaving session's
 * project folder, so the header paints TWO identities at once (the v0.3.34
 * dogfood report: `...-session-b` label + `...-proj-a` crumb over a blank pane).
 *
 * The fix keys the crumb to the SAME target session identity the label uses, via
 * TWO guards (a single boolean gate is not enough — back->open-B has sub-windows
 * where the gate is briefly false while the visible pane is still the leaving
 * one):
 *   1. while a switch is hiding the terminal ([switchHidesTerminal]) the crumb is
 *      suppressed (loading window), AND
 *   2. the crumb is suppressed whenever the VISIBLE pane's session
 *      ([visiblePaneSessionName]) does NOT match the nav-route TARGET
 *      ([targetSessionName]) — i.e. the crumb only renders when its cwd belongs
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
 * When the user deliberately opens session A (tap a row → nav target session
 * becomes A → `connect(A)` makes A active → `rebuildUnifiedPanes()` reorders
 * the list so A heads it), the pager can still be sitting on a *stale* index
 * that now resolves to a DIFFERENT session's pane. The settle collector would
 * then fire `onReplaceTmuxSession(thatOtherSession)`, yanking the user out of
 * the session they just tapped and routing their next prompt to the wrong
 * project — the data-loss regression reported in #652.
 *
 * A settle is a genuine user-driven cross-session swipe ONLY when the pager is
 * already aligned with the deliberate nav target ([navTargetSession]); i.e. the
 * page the user is actually looking at agrees with the session the navigation
 * asked for. Until the pager re-aligns to a freshly-tapped target, settle
 * events are suppressed so the explicit tap always wins over a stale index.
 *
 * @param settledPaneSession the session name owning the pane the pager settled
 *   on (`null` when it can't be resolved — e.g. the list is mid-rebuild).
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
    // `scrollToPage` realignment after a switch — and any lagging
    // stale-index recomposition echo that resolves to the session we JUST
    // LEFT — produces a settle with NO preceding user drag. Honoring those
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

private sealed interface TmuxDialogMode {
    data object RenameSession : TmuxDialogMode
    data object StopSession : TmuxDialogMode
}

private val VerticalSwipeThreshold = 72.dp
private val SessionDrawerMaxWidth = 360.dp
private const val MotionDurationMs: Int = 200
private val MotionEasing = CubicBezierEasing(0f, 0f, 0.2f, 1f)
internal const val TMUX_SESSION_SCREEN_TAG = "tmux:session"
internal const val TMUX_SESSION_SWITCHER_TAG = "tmux:session-switcher"
internal const val TMUX_SESSION_DRAWER_CLOSE_TAG = "tmux:session-drawer:close"
internal const val TMUX_SESSION_DRAWER_CREATE_TAG = "tmux:session-drawer:create"
internal const val TMUX_SESSION_DRAWER_REFRESH_TAG = "tmux:session-drawer:refresh"
internal const val TMUX_CONVERSATION_PANE_TAG = "tmux:conversation"

// Issue #778: test tag on the conversation-loading placeholder shown when the
// user taps Conversation before the transcript is ready. Issue #793 widened
// this placeholder to a full load state machine (Loading / Failed / Empty).
internal const val TMUX_CONVERSATION_DETECTING_TAG = "tmux:conversation:detecting"

// Issue #793: test tags on the terminal conversation-load states (no infinite
// spinner). Failed = clear error + retry; Empty = clear "no events" state.
internal const val TMUX_CONVERSATION_LOAD_FAILED_TAG = "tmux:conversation:load-failed"
internal const val TMUX_CONVERSATION_LOAD_RETRY_TAG = "tmux:conversation:load-retry"
internal const val TMUX_CONVERSATION_LOAD_EMPTY_TAG = "tmux:conversation:load-empty"
// Issue #793: top-of-list progress row shown while older messages page in on
// upward scroll (tail-first windowed load).
internal const val TMUX_CONVERSATION_PAGING_OLDER_TAG = "tmux:conversation:paging-older"

// Issue #423: actionable terminal-surface error state. Shown for a pane
// whose local Termux surface failed to recover after a recovery storm
// while SSH/tmux is still alive. The user taps the recreate control to
// rebuild the surface without reconnecting SSH.
internal const val TMUX_TERMINAL_SURFACE_ERROR_TAG = "tmux:terminal-surface-error"
internal const val TMUX_TERMINAL_SURFACE_RECREATE_TAG = "tmux:terminal-surface-recreate"
// Issue #459: the bespoke in-pane conversation composer (and its
// composer-input / composer-send test tags) was removed — the Conversation
// tab now shares the unified [PromptComposerSheet] at the screen level.
internal const val TMUX_CONVERSATION_TOOL_ROW_TAG_PREFIX = "tmux:conversation:tool:"
/** Issue #176: stable test tag prefix for a `SystemNote` row in the tmux conversation pane. */
internal const val TMUX_CONVERSATION_SYSTEM_NOTE_ROW_TAG_PREFIX = "tmux:conversation:system-note:"
/**
 * Issue #840 (slice 2): stable test tag prefix for the leading [DisclosureIcon]
 * on a `SystemNote` row's header — the shared expand/collapse affordance the row
 * previously lacked entirely.
 */
internal const val TMUX_CONVERSATION_SYSTEM_NOTE_DISCLOSURE_TAG_PREFIX =
    "tmux:conversation:system-note:disclosure:"
/**
 * Issue #154/#786: stable test tags for the conversation navigation polish.
 * Issue #786 (hard-cut, D22): the "Search in conversation" field was DELETED —
 * the maintainer wants the whole screen given to the transcript — so its
 * `TMUX_CONVERSATION_SEARCH_TAG` is gone with it.
 * - [TMUX_CONVERSATION_JUMP_TO_LATEST_TAG] is on the "↓ Latest"
 *   affordance pinned to the bottom-end of the conversation list when
 *   the user has scrolled away from the tail. The button hides itself
 *   when the user is already at the bottom.
 */
internal const val TMUX_CONVERSATION_JUMP_TO_LATEST_TAG =
    "tmux:conversation:jump-to-latest"
/**
 * Issue #154: test tag on the conversation feed's LazyColumn so the
 * navigation E2E test can drive scroll position directly via
 * `performScrollToIndex` (the indirection through a child text-row is
 * fragile because LazyColumn lazily disposes off-screen rows).
 */
internal const val TMUX_CONVERSATION_LIST_TAG =
    "tmux:conversation:list"
/**
 * Issue #154: stable test tag for the brief pulse highlight that fades
 * over the Tabs row when the Conversation tab newly appears. The pulse
 * is a sibling overlay sitting on top of [TMUX_TABS_TAG]; tests can
 * `assertExists()` on this tag while the pulse animation runs and
 * `assertDoesNotExist()` after it completes.
 */
internal const val TMUX_CONVERSATION_TAB_PULSE_TAG =
    "tmux:tabs:conversation-pulse"
internal fun sessionForwardingMenuStatusLabel(
    state: com.pocketshell.app.portfwd.SessionForwardingIndicatorState,
): String =
    when {
        !state.visible -> ""
        state.restoring -> "Restoring"
        state.tunnelCount == 1 -> "1 active port"
        state.tunnelCount > 1 -> "${state.tunnelCount} active ports"
        else -> "Active"
    }

/**
 * Issue #184 + #189: stable test tags for the IME-aware top chrome on
 * the tmux session screen.
 *
 * - [TMUX_FULL_BREADCRUMB_TAG] is on the IME-down consolidated 56dp
 *   toolbar (back chevron + status dot + `session › Window N` crumb +
 *   inline tab pill + kebab — all in one row, per issue #189). Pre-#189
 *   this tag was on the legacy 4-segment Breadcrumb that has been
 *   removed in favour of the consolidated row; the tag is preserved so
 *   the IME compression test in [TmuxSessionScreenImeChromeTest] keeps
 *   its "non-compressed chrome is displayed" assertion intact.
 * - [TMUX_COMPACT_BREADCRUMB_TAG] is on the slimmed-down session-name
 *   strip that replaces the consolidated row while the IME is up so the
 *   terminal viewport can claim the freed vertical space.
 * - [TMUX_TABS_TAG] is on the inline Terminal/Conversation toggle
 *   rendered inside [ConsolidatedTopChrome] (rendered only when an
 *   agent has been detected on the current window or a conversation is
 *   locked). Per #303 the toggle no longer consumes a dedicated row.
 * - [TMUX_CONSOLIDATED_SESSION_LABEL_TAG] tags the session crumb text
 *   inside the consolidated row so screenshot tests and future audits
 *   can find it without relying on translatable text.
 * - [TMUX_CONSOLIDATED_TAB_PILL_TAG_PREFIX] is the prefix for per-tab
 *   pill segments (index 0 = Terminal, index 1 = Conversation).
 * - [TMUX_TERMINAL_TAB_TAG] is a named, stable hook for the Terminal
 *   segment inside the toggle pill (equivalent to
 *   `TMUX_CONSOLIDATED_TAB_PILL_TAG_PREFIX + "0"`). Added for issue #216
 *   so connected E2E tests that previously asserted on the visible
 *   "Terminal" text can address the Terminal segment without depending
 *   on translatable strings. Note: the segment is only present when the
 *   pill itself is rendered (multi-tab case — i.e. an agent has been
 *   detected). For the universal "I am on the tmux session screen"
 *   sentinel, use [TMUX_SESSION_SCREEN_TAG] instead, since the pill is
 *   intentionally suppressed for shell-only / single-tab panes.
 */
internal const val TMUX_FULL_BREADCRUMB_TAG = "tmux:breadcrumb:full"
internal const val TMUX_COMPACT_BREADCRUMB_TAG = "tmux:breadcrumb:compact"
internal const val TMUX_TABS_TAG = "tmux:tabs"
internal const val TMUX_CONSOLIDATED_SESSION_LABEL_TAG = "tmux:chrome:session-label"
internal const val TMUX_CONSOLIDATED_TAB_PILL_TAG_PREFIX = "tmux:chrome:tab-pill:"
internal const val TMUX_TERMINAL_TAB_TAG = "tmux:chrome:tab-pill:terminal"
internal const val TMUX_FULL_CHROME_BACK_BUTTON_TAG = "tmux:chrome:full:back"
internal const val TMUX_FULL_CHROME_MORE_BUTTON_TAG = "tmux:chrome:full:more"
internal const val TMUX_COMPACT_CHROME_BACK_BUTTON_TAG = "tmux:chrome:compact:back"
internal const val TMUX_COMPACT_CHROME_MORE_BUTTON_TAG =
    "tmux:chrome:compact:more"

// Issue #462 regression sentinel: the old top-chrome command-palette button
// must not render in either the full or compact tmux chrome. Kept so tests can
// assert the previous affordance is gone from the header edge.
internal const val TMUX_COMMAND_PALETTE_BUTTON_TAG = "tmux:chrome:command-palette"

// Issue #463: the tappable project/folder crumb in the session header that
// opens the in-session project-scoped session switcher dropdown, and the
// dropdown's per-session rows.
internal const val TMUX_PROJECT_SWITCHER_TAG = "tmux:chrome:project-switcher"
internal const val TMUX_PROJECT_SWITCHER_MENU_TAG = "tmux:chrome:project-switcher:menu"
internal const val TMUX_PROJECT_SWITCHER_ROW_TAG_PREFIX = "tmux:chrome:project-switcher:row:"

/**
 * Issues #177 / #249: the "Reconnecting" / "Disconnected" breadcrumb pill
 * (see [ConnectionStatusPill]). Connected tests assert this appears while
 * the reattach handshake is in flight and disappears once the session is
 * live again.
 */
internal const val TMUX_CONNECTION_STATUS_PILL_TAG = "tmux:chrome:connection-pill"

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
 * [SwitchingLoadingPlaceholder], driven by `effectiveHidesTerminal`). When that
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
 * Issue #235: stable test tag for the kebab menu's Detach item. The
 * connected `TmuxDetachOnBackgroundE2eTest` does NOT click this tag —
 * it drives the lifecycle path via `UiDevice.pressHome` — but the
 * symmetric "manual detach + return to dashboard" assertion in the
 * same suite needs a deterministic handle on the menu item.
 */
internal const val TMUX_DETACH_BUTTON_TAG = "tmux:session:detach-button"
/**
 * Issue #445 (epic #432 slice A): stable test tag for the kebab menu's
 * "Port forwarding" item, used by the nav-route instrumentation to drive
 * kebab -> port-forward panel -> back-to-session.
 */
internal const val TMUX_PORT_FORWARDING_BUTTON_TAG = "tmux:session:port-forwarding-button"
/** Issue #592: stable test tag for the tmux kebab's global Settings item. */
internal const val TMUX_SETTINGS_BUTTON_TAG = "tmux:session:settings-button"
/**
 * Issue #892: stable test tag for the kebab's "Redraw" item. Tapping it forces a
 * full-viewport reseed of the active pane over the warm session (no reconnect /
 * detach / new lease) — the manual escape hatch from a black/partial-black terminal.
 */
internal const val TMUX_REDRAW_BUTTON_TAG = "tmux:session:redraw-button"
/**
 * Issue #993: stable test tag for the kebab's "Reconnect" item. Tapping it forces an
 * immediate reconnect of the CURRENT session in place (reusing the VM's single
 * [TmuxSessionViewModel.reconnect] / `TransportEffects.onManualReconnect` entrypoint) so
 * the user does NOT have to switch to another session and back to recover a dropped
 * session. On reconnect the #900 outbound queue auto-flushes the pending message. A
 * deliberate HALF-MEASURE escape hatch until auto-reconnect is bulletproof (#928).
 */
internal const val TMUX_RECONNECT_BUTTON_TAG = "tmux:session:reconnect-button"
/**
 * Issue #497: stable test tags for the kebab's "Open file…" item and the
 * path-entry dialog it opens, so instrumentation can drive
 * kebab -> enter path -> file viewer.
 */
internal const val TMUX_OPEN_FILE_BUTTON_TAG = "tmux:session:open-file-button"
internal const val TMUX_OPEN_FILE_DIALOG_FIELD_TAG = "tmux:session:open-file-field"
internal const val TMUX_OPEN_FILE_DIALOG_CONFIRM_TAG = "tmux:session:open-file-confirm"
internal const val TMUX_LIFECYCLE_DIALOG_CONFIRM_TAG = "tmux:session:lifecycle-dialog:confirm"
internal const val TMUX_LIFECYCLE_DIALOG_CANCEL_TAG = "tmux:session:lifecycle-dialog:cancel"
/**
 * Issue #528: stable test tag for the kebab's "Browse files…" item, used by
 * instrumentation to drive kebab -> file explorer.
 */
internal const val TMUX_BROWSE_FILES_BUTTON_TAG = "tmux:session:browse-files-button"
/**
 * Epic #821 Slice 1: stable test tag for the kebab's "Change kind" /
 * "What is this session?" item, used by instrumentation to open the
 * session-kind picker.
 */
internal const val TMUX_CHANGE_KIND_BUTTON_TAG = "tmux:session:change-kind-button"
/**
 * Issue #448 (epic #432 slice C): stable test tags for the new-port
 * detection overlay and its actions, so instrumentation can assert the
 * overlay appears and drive Forward / Dismiss.
 */
internal const val TMUX_DETECTED_PORT_OVERLAY_TAG = "tmux:session:detected-port-overlay"
internal const val TMUX_DETECTED_PORT_FORWARD_TAG = "tmux:session:detected-port-forward"
internal const val TMUX_DETECTED_PORT_DISMISS_TAG = "tmux:session:detected-port-dismiss"
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
internal const val TMUX_SESSION_PAGER_OVERLAY_TAG = "tmux:session-pager"
internal const val TMUX_SESSION_PAGER_TAG = "tmux:session-pager-control"
internal const val TMUX_SESSION_PAGER_PAGE_TAG_PREFIX = "tmux:session-pager-page:"
internal const val TMUX_SESSION_PAGER_INDICATOR_TAG = "tmux:session-pager-indicator"

private fun Modifier.verticalSwipeInput(
    thresholdPx: Float,
    onBoundary: () -> Unit,
    onSwipeUp: (() -> Unit)? = null,
    onSwipeDown: (() -> Unit)? = null,
) = pointerInput(thresholdPx, onBoundary, onSwipeUp, onSwipeDown) {
    var totalDrag = 0f
    var triggered = false
    detectVerticalDragGestures(
        onDragStart = {
            totalDrag = 0f
            triggered = false
        },
        onVerticalDrag = { change, dragAmount ->
            totalDrag += dragAmount
            if (triggered || abs(totalDrag) < thresholdPx) {
                return@detectVerticalDragGestures
            }
            val swipeUp = totalDrag < 0f
            val callback = if (swipeUp) onSwipeUp else onSwipeDown
            if (callback == null) return@detectVerticalDragGestures
            triggered = true
            onBoundary()
            callback()
            change.consume()
        },
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
 *  - while the terminal is HELD (`effectiveHidesTerminal == true`) the centered
 *    "Attaching…" [SwitchingLoadingPlaceholder] spinner is shown, and this band
 *    is suppressed entirely by [shouldShowReconnectingProgressRow];
 *  - in the STEADY reconnect state (`effectiveHidesTerminal == false`) the
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
    status: ConnectionStatus.Reconnecting,
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
                text = "Reconnecting to ${status.user}@${status.host}:${status.port} " +
                    "($sessionLabel, ${status.attempt}/${status.maxAttempts})…",
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
        if (status.retryDelayMs > 0) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Retrying in ${status.retryDelayMs / 1_000}s",
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
 * Rendered when [TmuxSessionViewModel.connectionStatus] is
 * [TmuxSessionViewModel.ConnectionStatus.Failed]. Surfaces the
 * disconnect message and a single-tap Reconnect button that calls back
 * into [TmuxSessionViewModel.reconnect].
 *
 * The message is rendered in the design-system error token
 * [PocketShellColors.Red] (see `docs/design-system.md` §1) so the band
 * reads as a real failure state instead of a muted hint.
 *
 * The Reconnect button is gated on [canReconnect] — when no target is
 * set (the ViewModel never opened) the button is hidden so the user
 * never sees a tap that silently no-ops.
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
    canReconnect: Boolean,
) {
    // EPIC #687 #720: the ONLY honest error (controller `Unreachable`) is a CALM,
    // tappable "Tap to reconnect" affordance — never raw `TransportException`/SSH
    // exception text, never the "Open the session again to reconnect" instruction.
    // The whole band is tappable (taps run the existing reconnect action) and the
    // text reads as a calm prompt, not a scary red failure. When there is genuinely
    // nothing to reconnect to (`!canReconnect`, the VM never opened) the band is
    // inert but still calm.
    val rowModifier = Modifier
        .fillMaxWidth()
        .background(color = PocketShellColors.Surface)
        .then(
            if (canReconnect) {
                Modifier.clickable(onClick = onReconnect)
            } else {
                Modifier
            },
        )
        .padding(horizontal = 12.dp, vertical = 6.dp)
        .testTag(TMUX_SESSION_ERROR_TAG)
    Row(
        modifier = rowModifier,
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
        if (canReconnect) {
            TextButton(
                onClick = onReconnect,
                modifier = Modifier.testTag(TMUX_SESSION_RECONNECT_TAG),
            ) {
                Text("Tap to reconnect")
            }
        }
    }
}

/**
 * Issue #778: lightweight Conversation-tab placeholder shown when the user has
 * tapped Conversation on a presumed-agent pane (#716) before live agent
 * detection has landed (`detection == null`). It honours the tap — the view
 * switches to the Conversation surface immediately — while being honest that the
 * transcript is not ready yet. The real [TmuxConversationPane] replaces this the
 * instant detection seeds a conversation row. Deliberately does NOT mount
 * [TmuxConversationPane] (which assumes a real detection/transcript), so there
 * is no empty-state crash and no #605 swap-latch interaction.
 *
 * `internal` so the #778 rendered-UI regression test
 * ([com.pocketshell.app.conversation.ConversationDetectingPlaceholderRenderTest])
 * can mount the REAL production placeholder and assert it is displayed when the
 * Conversation tap lands before detection.
 */
@Composable
internal fun ConversationDetectingPlaceholder(
    // Issue #793: the full-screen state shown BEFORE the transcript renders.
    // Loading → "Loading conversation…" (NOT "Waiting for agent…", which is
    // reserved for a genuinely-pending live agent turn). Failed → a clear
    // terminal error with a Retry, never an infinite spinner. Empty → a clear
    // "no messages yet" terminal state.
    loadState: ConversationLoadState = ConversationLoadState.Loading,
    onRetry: () -> Unit = {},
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(color = PocketShellColors.Background)
            .testTag(TMUX_CONVERSATION_DETECTING_TAG),
        contentAlignment = Alignment.Center,
    ) {
        when (loadState) {
            ConversationLoadState.Failed -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .testTag(TMUX_CONVERSATION_LOAD_FAILED_TAG),
            ) {
                Text(
                    text = "Couldn't load this conversation.",
                    color = PocketShellColors.Text,
                    fontSize = 16.sp,
                )
                Spacer(modifier = Modifier.height(12.dp))
                TextButton(
                    onClick = onRetry,
                    modifier = Modifier.testTag(TMUX_CONVERSATION_LOAD_RETRY_TAG),
                ) {
                    Text("Retry")
                }
            }
            ConversationLoadState.Empty -> Text(
                text = "No conversation events yet.",
                color = PocketShellColors.TextSecondary,
                fontSize = 14.sp,
                modifier = Modifier.testTag(TMUX_CONVERSATION_LOAD_EMPTY_TAG),
            )
            else -> LoadingIndicator.Spinner(
                size = SpinnerSize.Medium,
                label = "Loading conversation…",
            )
        }
    }
}

@Composable
private fun EmptyPanesPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(color = PocketShellColors.Surface),
        contentAlignment = Alignment.Center,
    ) {
        // Issue #757: the "waiting for tmux panes…" connecting state was static
        // text with no animated affordance, unlike the "Attaching…" placeholder.
        // Adopt the canonical centered [LoadingIndicator.Spinner] (#756) with the
        // label slot so this state shows the SAME spinner used everywhere else.
        LoadingIndicator.Spinner(
            size = SpinnerSize.Medium,
            label = "waiting for tmux panes…",
        )
    }
}

/**
 * Issue #661: full-surface loading state shown IN PLACE OF the terminal while a
 * cross-session switch attaches the new session. The leaving session's frame is
 * never painted here — the surface is a neutral background with a compact
 * "Attaching…" indicator — so the user never sees the previous session's
 * content for even one frame. The VM ([TmuxSessionViewModel.switchHidesTerminal])
 * reveals the real terminal the instant the new session's panes are seeded.
 */
@Composable
private fun SwitchingLoadingPlaceholder() {
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
 * Issue #423: actionable terminal-surface error state. Shown when the local
 * Termux surface for the focused pane fails to recover (an IME/resize/render
 * recovery storm) while the SSH/tmux transport is still alive. The user taps
 * "Recreate terminal" to rebuild the surface and reattach to the live tmux
 * pane — no SSH reconnect, no force-restart. The rest of the app stays
 * navigable because only this pane's surface is affected.
 */
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
        val isBoundary = paneSession != null &&
            paneSession != prevSession && paneSession != sessionName
        // Issue #626: session boundary marker above the
        // terminal surface for the first pane of a different
        // session.
        if (isBoundary && paneSession != null) {
            SessionBoundaryDivider(sessionName = paneSession)
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
                fontSize = 15.sp,
            )
            Text(
                text = "The connection is still active. Recreate the terminal to " +
                    "keep working in this tmux session.",
                color = PocketShellColors.TextSecondary,
                style = PocketShellType.bodyDense,
            )
            TextButton(
                onClick = onRecreate,
                modifier = Modifier.testTag(TMUX_TERMINAL_SURFACE_RECREATE_TAG),
            ) {
                Text(text = "Recreate terminal")
            }
        }
    }
}

@Composable
internal fun TmuxConversationPane(
    events: List<ConversationEvent>,
    modifier: Modifier = Modifier,
    // Issue #176: when false, XML-tagged SystemNote events are filtered
    // from the visible feed entirely. The default keeps the existing
    // behaviour (notes visible but muted) so direct callers that did
    // not opt into the setting wire-up still see system notes.
    showSystemNotes: Boolean = true,
    paneId: String? = null,
    syncStatus: AgentConversationSyncStatus = AgentConversationSyncStatus.Live,
    // Issue #793: tail-first paging. [hasMoreOlderEvents] is true when older
    // messages exist before the loaded window; the pane fires
    // [onLoadOlderEvents] when the user scrolls near the top to page them in,
    // and shows a top-of-list progress row while [isPagingOlder] is true.
    // Defaults keep direct/screenshot callers (no paging) rendering as before.
    hasMoreOlderEvents: Boolean = false,
    isPagingOlder: Boolean = false,
    onLoadOlderEvents: () -> Unit = {},
    onRetryAgentStream: () -> Unit = {},
    // Issue #494: retry a failed optimistic user send (passes its optimistic
    // id). Default no-op for screenshot/legacy callers.
    onRetryFailedSend: (String) -> Unit = {},
    // Issue #557: a file/dir/URL detected in a message body was tapped. The
    // screen routes it (file → viewer, directory → file browser, URL → open).
    // Null default keeps direct callers/screenshot tests rendering plain text.
    onConversationLinkTap: ((ConversationLink) -> Unit)? = null,
) {
    ConversationInteractionCleanupEffect()

    // Issue #459: this pane is read-only chrome — just the conversation feed.
    // Sending is owned by the shared unified composer ([PromptComposerSheet])
    // mounted at the screen level, identical to the Terminal tab's bottom. The
    // bespoke in-pane "Message …" field, its draft/unsent-prompt state, and the
    // `onSendToAgent` callback are gone.
    // Issue #786 (hard-cut, D22): the "Search in conversation" field is GONE —
    // the maintainer wants the whole screen given to the transcript. The
    // [filterConversationRows] call STAYS (it also does tool-result pairing +
    // the searched-tool-call expansion merge) but is fed an empty query, so it
    // never filters out rows — every event is shown.
    val visibleEvents = remember(events, showSystemNotes) {
        val timelineEvents = events.filterNot { it.isHiddenConversationTimelineRow() }
        if (showSystemNotes) timelineEvents else timelineEvents.filterNot { it is ConversationEvent.SystemNote }
    }
    val toolResultPairing = remember(visibleEvents) { visibleEvents.toolResultPairing() }
    val filteredConversation = remember(visibleEvents, toolResultPairing) {
        filterConversationRows(
            events = visibleEvents,
            query = "",
            pairing = toolResultPairing,
        )
    }
    val filteredEvents = filteredConversation.events
    // Issue #824: tool-call expansion OVERRIDE per event-id. `true` = the user
    // expanded it, `false` = the user collapsed it, absent = follow the
    // auto-expand defaults (running card / search hit). Persisted at the pane
    // level (not inside the row composable) so a row scrolling out and back in
    // remembers the user's decision, and so a still-running card the user
    // collapsed does NOT silently re-expand when the next event arrives. This
    // replaces the old "explicitly-expanded Set" whose `isRunning && no-result`
    // term could never be overridden by a tap — the #824 stuck-open bug.
    val toolCallExpandOverrides = remember { mutableStateOf(mapOf<String, Boolean>()) }
    // Issue #176: SystemNote expand state — same idea as tool-call expand,
    // collapsed by default, the user's choice is sticky for the lifetime
    // of the conversation pane.
    val expandedSystemNotes = remember { mutableStateOf(setOf<String>()) }
    val runningToolIds = remember(visibleEvents, toolResultPairing) {
        runningToolCallIds(visibleEvents, toolResultPairing)
    }
    // Issue #573: scrolling upward through a large Codex transcript can
    // compose many older ToolCall rows in quick succession. Looking up each
    // ToolCall's result with a full event-list scan turns that scroll into
    // repeated O(n) work on the UI thread. Index once per event snapshot.
    // Issue #604: the same index also owns deterministic adjacent fallback
    // pairing for parser outputs that do not carry a reliable toolCallId.

    // Issue #401: terminal-style tail-follow. The pane opens at the newest
    // row and follows appended events until the user intentionally scrolls
    // away from the tail. Returning to the bottom resumes following.
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var userScrolledAwayFromTail by remember { mutableStateOf(false) }
    val atBottom by remember(filteredEvents) {
        derivedStateOf { listState.isScrolledToBottom(filteredEvents.size) }
    }
    LaunchedEffect(listState, filteredEvents.size) {
        snapshotFlow {
            listState.isScrollInProgress to listState.isScrolledToBottom(filteredEvents.size)
        }.collect { (scrolling, scrolledToBottom) ->
            when {
                scrolledToBottom -> userScrolledAwayFromTail = false
                scrolling -> userScrolledAwayFromTail = true
            }
        }
    }
    LaunchedEffect(filteredEvents.lastOrNull()?.id) {
        if (filteredEvents.isEmpty()) return@LaunchedEffect
        if (!userScrolledAwayFromTail) {
            listState.scrollToItem(filteredEvents.size - 1)
        }
    }

    // Issue #793: tail-first upward paging. When the user scrolls near the TOP
    // of the loaded window and older messages exist, fire [onLoadOlderEvents] so
    // the VM widens the window and prepends older turns. Gated on an unfiltered
    // view (paging a filtered list is confusing) and on not already paging. The
    // VM merge preserves the already-loaded tail, so the user's scroll position
    // holds — the newly-prepended older rows simply extend the list upward.
    if (hasMoreOlderEvents) {
        val nearTop by remember {
            derivedStateOf { listState.firstVisibleItemIndex <= 2 }
        }
        LaunchedEffect(nearTop, hasMoreOlderEvents, isPagingOlder) {
            if (nearTop && hasMoreOlderEvents && !isPagingOlder) {
                onLoadOlderEvents()
            }
        }
    }

    Column(
        modifier = modifier
            .background(color = PocketShellColors.Background)
            .padding(horizontal = ChatPaneHPadding, vertical = ChatPaneVPadding),
    ) {
        // Issue #786: the "Search in conversation" field used to live here as the
        // first child of the Column. It is GONE (hard-cut, D22) — the transcript
        // Box below holds `weight(1f)`, so removing the field hands its vertical
        // space straight to the transcript for the full-height conversation the
        // maintainer asked for.
        ConversationSyncStatusRow(
            syncStatus = syncStatus,
            onRetry = onRetryAgentStream,
        )
        // Wrap the LazyColumn in a Box so the jump-to-latest FAB can
        // overlay the bottom-end of the scrollable area. The Box claims
        // the flex weight; the FAB is a sibling pinned to BottomEnd.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(TMUX_CONVERSATION_LIST_TAG),
                contentPadding = PaddingValues(start = 0.dp, end = 0.dp, top = 8.dp, bottom = 72.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                // Issue #793: top-of-list progress row while older messages are
                // being paged in on upward scroll.
                if (isPagingOlder) {
                    item(key = "ps-paging-older") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp)
                                .testTag(TMUX_CONVERSATION_PAGING_OLDER_TAG),
                            contentAlignment = Alignment.Center,
                        ) {
                            LoadingIndicator.Spinner(
                                size = SpinnerSize.Small,
                                label = "Loading earlier messages…",
                            )
                        }
                    }
                }
                if (filteredEvents.isEmpty()) {
                    item {
                        Text(
                            text = if (events.isEmpty()) "No conversation events yet." else "No matching events.",
                            color = PocketShellColors.TextSecondary,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                }
                items(filteredEvents, key = { it.id }) { event ->
                    // Issue #824: resolve the card's expanded state from the
                    // user's explicit override (if any) on top of the
                    // auto-expand defaults (running card / search hit). A tap
                    // records the OPPOSITE of the currently-shown state, so even
                    // the most-recent running card always toggles closed.
                    val toolCallExpanded = ConversationToolCardExpansion.isExpanded(
                        userOverride = toolCallExpandOverrides.value[event.id],
                        isRunning = event.id in runningToolIds,
                        hasResult = (event as? ConversationEvent.ToolCall)?.let { call ->
                            toolResultPairing.resultsByCallId.containsKey(call.id)
                        } ?: false,
                        isSearchExpanded = event.id in filteredConversation.searchExpandedToolCallIds,
                    )
                    ConversationEventRow(
                        event = event,
                        runningToolIds = runningToolIds,
                        toolResultPairing = toolResultPairing,
                        isExpanded = toolCallExpanded,
                        onToggleExpand = { id ->
                            ConversationDiagnostics.recordRowToggle(
                                mode = "tmux",
                                paneId = paneId,
                                event = event,
                                expanded = !toolCallExpanded,
                                pairedToolResult = (event as? ConversationEvent.ToolCall)?.let { call ->
                                    toolResultPairing.resultsByCallId[call.id]
                                },
                            )
                            toolCallExpandOverrides.value = ConversationToolCardExpansion.toggle(
                                overrides = toolCallExpandOverrides.value,
                                id = id,
                                currentlyExpanded = toolCallExpanded,
                            )
                        },
                        isSystemNoteExpanded = expandedSystemNotes.value.contains(event.id),
                        onToggleSystemNoteExpand = { id ->
                            ConversationDiagnostics.recordRowToggle(
                                mode = "tmux",
                                paneId = paneId,
                                event = event,
                                expanded = !expandedSystemNotes.value.contains(id),
                            )
                            expandedSystemNotes.value = expandedSystemNotes.value.toggle(id)
                        },
                        onRetryFailedSend = onRetryFailedSend,
                        onLinkTap = onConversationLinkTap,
                    )
                }
            }
            JumpToLatestOverlay(
                visible = userScrolledAwayFromTail && !atBottom && filteredEvents.isNotEmpty(),
                onClick = {
                    userScrolledAwayFromTail = false
                    coroutineScope.launch {
                        listState.animateScrollToItem(filteredEvents.size - 1)
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = 12.dp),
            )
        }
        // Issue #459: no in-pane composer / unsent-prompt banner any more —
        // the shared unified composer band at the screen level owns sending.
    }
}

/**
 * Issue #154: jump-to-latest affordance. A small accent-tinted pill that
 * sits in the bottom-right of the conversation pane while the user has
 * scrolled the feed away from its tail. Tapping the pill smooth-scrolls
 * to the last event and resumes tail-follow on the next message. The
 * styling mirrors the muted-accent pattern used by the agent hint banner
 * (#179) so the pane stays visually cohesive. Extracted as a top-level
 * composable (not nested inside the pane's `Box`) so the
 * [AnimatedVisibility] call resolves against the package-level variant
 * rather than the outer `ColumnScope.AnimatedVisibility` extension.
 */
@Composable
private fun JumpToLatestOverlay(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(
            animationSpec = tween(durationMillis = MotionDurationMs, easing = MotionEasing),
        ),
        exit = fadeOut(
            animationSpec = tween(durationMillis = MotionDurationMs, easing = MotionEasing),
        ),
        modifier = modifier,
    ) {
        JumpToLatestButton(onClick = onClick)
    }
}

@Composable
private fun JumpToLatestButton(
    onClick: () -> Unit,
) {
    val shape = PocketShellShapes.large
    Row(
        modifier = Modifier
            .background(color = PocketShellColors.Accent, shape = shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .testTag(TMUX_CONVERSATION_JUMP_TO_LATEST_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "↓ Latest",
            color = PocketShellColors.Background,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * Issue #154 (acceptance criterion #3): a LazyList is "at the bottom"
 * when the last item index is visible and its bottom edge sits inside
 * the viewport. We treat an empty list and a list whose only item is
 * fully visible as bottom-ed so the FAB never flashes during the
 * "first event arrives" transition. The function lives at file scope
 * so unit tests can hit it directly.
 */
internal fun androidx.compose.foundation.lazy.LazyListState.isScrolledToBottom(
    itemCount: Int,
): Boolean {
    if (itemCount == 0) return true
    val info = layoutInfo
    val lastVisible = info.visibleItemsInfo.lastOrNull() ?: return true
    if (lastVisible.index < itemCount - 1) return false
    val viewportEnd = info.viewportEndOffset - info.afterContentPadding
    return lastVisible.offset + lastVisible.size <= viewportEnd + 1
}

/**
 * Issue #154 (acceptance criterion #2): how long the Conversation-tab
 * pulse overlay stays visible after the tab newly appears. Long enough
 * for the user to register the new affordance, short enough that it
 * does not feel like sticky chrome.
 */
internal const val TAB_PULSE_DURATION_MS: Long = 2_000L

/**
 * Wraps the inline Conversation tab pill ([ConsolidatedTabPill]) and
 * overlays a brief accent ring + fade-in highlight the first time the
 * Conversation tab joins the row. The overlay is a sibling Box sized to
 * the pill (`matchParentSize`); it does not intercept taps because the
 * underlying pill sits in the same Box and gets pointer input first.
 * The pulse is keyed on the false → true transition of [pulseVisible]
 * (which the call site drives off `showConversationTab`); once the
 * timer fires it does not re-trigger until the tab disappears and
 * reappears (e.g. after a fresh agent detection on a new session).
 *
 * The wrapper uses [wrapContentSize] so it does not stretch within the
 * 56dp consolidated chrome row — the pill claims its natural width and
 * the pulse matches it. (#189 inlined the tab pill into the toolbar; in
 * the previous standalone Tabs row this wrapper used `fillMaxWidth`.)
 */
@Composable
private fun TabsRowWithPulse(
    pulseVisible: Boolean,
    content: @Composable () -> Unit,
) {
    var showPulse by remember { mutableStateOf(false) }
    // Track whether we have *seen* the conversation tab at least once
    // since the screen mounted — without this, the initial composition
    // would fire the pulse for every session that boots with a live
    // agent (i.e. a reconnect to an already-running Claude pane).
    // Initial-load animation belongs to the entry transition; the pulse
    // is reserved for the "new agent detected mid-session" transition.
    var hasSeenConversationTab by remember { mutableStateOf(pulseVisible) }
    LaunchedEffect(pulseVisible) {
        if (pulseVisible && !hasSeenConversationTab) {
            hasSeenConversationTab = true
            showPulse = true
            kotlinx.coroutines.delay(TAB_PULSE_DURATION_MS)
            showPulse = false
        } else if (!pulseVisible) {
            // Tab disappeared (agent process died, etc.) — reset so a
            // future re-appearance fires the pulse again.
            hasSeenConversationTab = false
            showPulse = false
        }
    }
    Box(modifier = Modifier.wrapContentSize()) {
        content()
        androidx.compose.animation.AnimatedVisibility(
            visible = showPulse,
            enter = fadeIn(
                animationSpec = tween(durationMillis = MotionDurationMs, easing = MotionEasing),
            ),
            exit = fadeOut(
                animationSpec = tween(durationMillis = TAB_PULSE_DURATION_MS.toInt(), easing = MotionEasing),
            ),
            modifier = Modifier.matchParentSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(
                        width = 2.dp,
                        color = PocketShellColors.Accent,
                    )
                    .testTag(TMUX_CONVERSATION_TAB_PULSE_TAG),
            )
        }
    }
}

/**
 * Conversation row dispatcher — picks between the message renderer
 * (Markdown for `Message`, optimistic + assistant) and the polished
 * tool-call renderer ([ConversationToolCallRow]).
 *
 * `ToolResult` events are folded into their parent `ToolCall` row when
 * possible (via [toolCallId]) so the user sees one collapsible card per
 * tool invocation rather than two separate rows.
 */
@Composable
private fun ConversationEventRow(
    event: ConversationEvent,
    runningToolIds: Set<String>,
    toolResultPairing: ToolResultPairing,
    isExpanded: Boolean,
    onToggleExpand: (String) -> Unit,
    isSystemNoteExpanded: Boolean,
    onToggleSystemNoteExpand: (String) -> Unit,
    onRetryFailedSend: (String) -> Unit = {},
    onLinkTap: ((com.pocketshell.core.terminal.selection.ConversationLink) -> Unit)? = null,
) {
    when (event) {
        is ConversationEvent.Message -> ConversationMessageRow(
            event = event,
            onRetryFailedSend = onRetryFailedSend,
            onLinkTap = onLinkTap,
        )
        is ConversationEvent.ToolCall -> ConversationToolCallChatCard(
            toolCall = event,
            result = toolResultPairing.resultsByCallId[event.id],
            isRunning = event.id in runningToolIds,
            expanded = isExpanded,
            onToggle = { onToggleExpand(event.id) },
        )
        is ConversationEvent.ToolResult -> {
            if (event.id !in toolResultPairing.pairedResultIds) {
                // Orphan tool result with no parent ToolCall — render as a
                // standalone, very subtle row.
                ConversationToolResultRow(event)
            }
        }
        is ConversationEvent.SystemNote -> ConversationSystemNoteRow(
            note = event,
            isExpanded = isSystemNoteExpanded,
            onToggle = { onToggleSystemNoteExpand(event.id) },
        )
    }
}

@Composable
private fun ConversationMessageRow(
    event: ConversationEvent.Message,
    onRetryFailedSend: (String) -> Unit = {},
    onLinkTap: ((com.pocketshell.core.terminal.selection.ConversationLink) -> Unit)? = null,
) {
    ConversationMessageTurn(
        event = event,
        onRetrySend = onRetryFailedSend,
        onLinkTap = onLinkTap,
    )
}

private fun Set<String>.toggle(id: String): Set<String> =
    if (contains(id)) this - id else this + id

// Issue #459: `AgentComposerRow` (the bespoke in-pane "Message …" field +
// Send) was removed. The Conversation tab now shares the unified
// [com.pocketshell.app.composer.PromptComposerSheet] mounted at the screen
// level, identical to the Terminal tab's bottom.

/**
 * Issue #561: Chat-style tool call card. Renders as an inline card within
 * the conversation transcript (not a dense timeline row). The card shows
 * the tool name, command preview, and an expand chevron. When expanded,
 * shows input/output sections.
 */
@Composable
private fun ConversationToolCallChatCard(
    toolCall: ConversationEvent.ToolCall,
    result: ConversationEvent.ToolResult?,
    isRunning: Boolean,
    // Issue #824: the fully-resolved expand state (user override applied over
    // the auto-expand defaults), computed by the pane via
    // [ConversationToolCardExpansion]. The card no longer re-derives it, so a
    // running card with no result can be collapsed by tap.
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val summary = remember(toolCall.id, toolCall.input) { ToolCallSummary.forToolCall(toolCall) }
    val statusGlyph = when {
        result?.isError == true -> "!"
        result != null -> "✓"
        isRunning -> "…"
        else -> ""
    }
    val statusColor = when {
        result?.isError == true -> PocketShellColors.Red
        result != null -> PocketShellColors.Green
        else -> PocketShellColors.TextMuted
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = ToolCallChatCardBottomMargin)
            .testTag(TMUX_CONVERSATION_TOOL_ROW_TAG_PREFIX + toolCall.id),
    ) {
        // Inline tool call card (matching .tool-call from mockup)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = PocketShellColors.Surface,
                    shape = RoundedCornerShape(ToolCallCardRadius),
                )
                .border(
                    width = 1.dp,
                    color = PocketShellColors.BorderSoft,
                    shape = RoundedCornerShape(ToolCallCardRadius),
                )
                .clickable(onClick = onToggle)
                .padding(horizontal = ToolCallCardHPadding, vertical = ToolCallCardVPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ToolCallCardItemGap),
        ) {
            DisclosureIcon(
                expanded = expanded,
                tint = PocketShellColors.TextMuted,
            )
            Text(
                text = toolCall.name,
                color = PocketShellColors.Accent,
                style = PocketShellType.bodyDense,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = summary,
                color = PocketShellColors.TextSecondary,
                style = PocketShellType.labelMono,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (statusGlyph.isNotEmpty()) {
                Text(text = statusGlyph, color = statusColor, style = PocketShellType.labelMono)
            }
        }
        // Expanded detail sections
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, start = 0.dp, end = 0.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ConversationToolArgsSection(
                    view = remember(toolCall.id, toolCall.name, toolCall.input) {
                        ToolArgsView.forInput(toolCall.name, toolCall.input)
                    },
                    copyTestTag = CONVERSATION_TOOL_COPY_TAG_PREFIX + toolCall.id + ":input",
                    rawCopyText = toolCall.input,
                )
                if (result != null) {
                    if (result.output.isNotEmpty()) {
                        ToolCallSection(
                            label = if (result.isError) "output (error)" else "output",
                            body = remember(result.id, result.output) {
                                ToolPayloadFormatter.formatOutput(result.output)
                            },
                            copyTestTag = CONVERSATION_TOOL_COPY_TAG_PREFIX + toolCall.id + ":output",
                        )
                    }
                    // Issue #842: image(s) returned by the tool result (e.g. a
                    // screenshot tool) render inline under the text output.
                    ConversationImages(
                        images = result.images,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/**
 * Issue #176 / #561: Chat-style system note row. Renders as a muted collapsible
 * block with a chat-style header (role label + time) and expandable body,
 * matching the conversation mockup paradigm instead of the old dense timeline.
 */
@Composable
private fun ConversationSystemNoteRow(
    note: ConversationEvent.SystemNote,
    isExpanded: Boolean,
    onToggle: () -> Unit,
) {
    val actorLabel = remember(note.tag) { note.timelineActorLabel() }
    val preview = remember(note.tag, note.content) { note.timelinePreview() }
    val timestamp = remember(note.atMillis) { note.timelineTimestamp() }
    val timeLabel = timestamp?.let { "· $it" }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(bottom = SystemNoteBlockBottomPadding)
            .testTag(TMUX_CONVERSATION_SYSTEM_NOTE_ROW_TAG_PREFIX + note.id),
    ) {
        // Chat-style header matching message blocks. #840 slice 2: lead with the
        // shared rotating [DisclosureIcon] so the (clickable) row carries the same
        // expand/collapse affordance as every other disclosure surface — it had
        // none before, which was its own inconsistency.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = MessageHeadBottomPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SystemNoteHeadGap),
        ) {
            DisclosureIcon(
                expanded = isExpanded,
                tint = PocketShellColors.TextMuted,
                modifier = Modifier.testTag(
                    TMUX_CONVERSATION_SYSTEM_NOTE_DISCLOSURE_TAG_PREFIX + note.id,
                ),
            )
            Text(
                text = actorLabel,
                color = PocketShellColors.TextMuted,
                style = SystemNoteHeadStyle,
                fontWeight = FontWeight.Bold,
                letterSpacing = MessageHeadLetterSpacing,
            )
            Spacer(modifier = Modifier.weight(1f))
            if (timeLabel != null) {
                Text(
                    text = timeLabel,
                    color = PocketShellColors.TextMuted,
                    style = PocketShellType.labelMono,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // Preview / expanded body
        if (isExpanded && note.content.isNotEmpty()) {
            ToolCallSection(
                label = "content",
                body = note.content,
                copyTestTag = CONVERSATION_TOOL_COPY_TAG_PREFIX + note.id + ":content",
            )
        } else {
            Text(
                text = preview,
                color = PocketShellColors.TextMuted,
                style = PocketShellType.bodyDense,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Issue #561: Chat-style standalone tool result row (unpaired results only).
 * Renders as a muted card matching the mockup paradigm.
 */
@Composable
private fun ConversationToolResultRow(result: ConversationEvent.ToolResult) {
    val timestamp = remember(result.atMillis) { result.timelineTimestamp() }
    val timeLabel = timestamp?.let { "· $it" }
    val labelColor = if (result.isError) PocketShellColors.Red else PocketShellColors.TextMuted

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = ToolCallChatCardBottomMargin),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = MessageHeadBottomPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (result.isError) "ERROR" else "RESULT",
                color = labelColor,
                style = SystemNoteHeadStyle,
                fontWeight = FontWeight.Bold,
                letterSpacing = MessageHeadLetterSpacing,
            )
            Spacer(modifier = Modifier.weight(1f))
            if (timeLabel != null) {
                Text(
                    text = timeLabel,
                    color = PocketShellColors.TextMuted,
                    style = PocketShellType.labelMono,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (result.output.isNotEmpty()) {
            ToolCallSection(
                label = "output",
                body = remember(result.id, result.output) {
                    ToolPayloadFormatter.formatOutput(result.output)
                },
                copyTestTag = CONVERSATION_TOOL_COPY_TAG_PREFIX + result.id + ":output",
            )
        }
        // Issue #842: image(s) on a standalone (unpaired) tool result.
        ConversationImages(
            images = result.images,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Inner labelled block of the expanded tool-call card. Very long bodies
 * (>200 lines OR >5000 chars per the scope addition) collapse into a
 * bounded-height scrollable container so the conversation pane can't
 * have a single tool eat the whole viewport.
 */
@Composable
private fun ToolCallSection(
    label: String,
    body: String,
    copyTestTag: String,
) {
    ConversationTextSection(
        label = label,
        body = body,
        copyTestTag = copyTestTag,
    )
}

// --- Issue #561 design tokens from conversation.html mockup ---

/** .conv { padding: 16px 18px 72px } */
private val ChatPaneHPadding = 18.dp
private val ChatPaneVPadding = 8.dp

/** .msg { margin-bottom: 22px } */
private val MessageHeadBottomPadding = 8.dp
private val MessageHeadLetterSpacing = 0.8.sp
private val SystemNoteBlockBottomPadding = 22.dp

/** #840 slice 2: gap between the leading disclosure icon and the actor label. */
private val SystemNoteHeadGap = 8.dp

/**
 * .tool-call card tokens.
 *
 * #704 req #3 ("make it more compact"): the Agent/Read/Bash tool-call rows ate
 * too much vertical space. Tighter per-row vertical padding (10 -> 6dp) and a
 * much smaller inter-row margin (22 -> 8dp) pack more of the transcript on
 * screen without losing the card framing.
 */
private val ToolCallCardRadius = 8.dp
private val ToolCallCardHPadding = 10.dp
private val ToolCallCardVPadding = 6.dp
private val ToolCallCardItemGap = 8.dp
private val ToolCallChatCardBottomMargin = 8.dp

/** System note header style (10sp uppercase matching .msg-head) */
private val SystemNoteHeadStyle = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontSize = 10.sp,
    fontWeight = FontWeight.Bold,
)

@Composable
internal fun TmuxMoreMenu(
    expanded: Boolean,
    forwardingState: com.pocketshell.app.portfwd.SessionForwardingIndicatorState =
        com.pocketshell.app.portfwd.SessionForwardingIndicatorState(),
    onDismiss: () -> Unit,
    onCreateSession: () -> Unit,
    onRenameSession: () -> Unit,
    onKillSession: () -> Unit,
    // Epic #821 Slice 1: classify / re-classify this session's agent kind.
    // The label adapts to whether the session has a recorded kind: an
    // unclassified (foreign) session reads "What is this session?" and a
    // classified one "Change kind". Defaulted so existing direct callers /
    // tests of TmuxMoreMenu stay source-compatible.
    onChangeKind: () -> Unit = {},
    changeKindIsUnknown: Boolean = false,
    onSwitchSession: () -> Unit,
    onOpenJobs: () -> Unit,
    onOpenUsage: () -> Unit,
    // Issue #592: direct global Settings shortcut from live session chrome.
    // Defaulted so existing direct callers / tests stay source-compatible.
    onOpenSettings: () -> Unit = {},
    // Issue #497: "Open file…" kebab item — opens the in-app file viewer
    // path-entry dialog. Defaulted so existing direct callers / tests of
    // TmuxMoreMenu stay source-compatible.
    onOpenFile: () -> Unit = {},
    // Issue #528: "Browse files…" kebab item — opens the browsable file
    // explorer. Defaulted so existing direct callers / tests stay
    // source-compatible.
    onBrowseFiles: () -> Unit = {},
    // Issue #445: "Port forwarding" kebab item — opens the per-host
    // port-forward panel. Defaulted so existing direct callers / tests
    // of TmuxMoreMenu stay source-compatible.
    onOpenPortForwarding: () -> Unit = {},
    // Issue #235: user-driven detach. Tears the `-CC` control client
    // down (server-clean — uses the same `detach-client` round-trip
    // [TmuxSessionViewModel.detachAndExit] runs internally) and pops
    // back to the sessions dashboard. The session itself stays alive
    // on the remote; reattach via the normal sessions-list path.
    onDetach: () -> Unit = {},
    // Issue #892: "Redraw" — force a full-viewport reseed of the active pane over the
    // warm session (no reconnect/detach/new lease) to recover from a black/partial
    // terminal. Defaulted so existing direct callers / tests of TmuxMoreMenu stay
    // source-compatible.
    onRedraw: () -> Unit = {},
    // Issue #993: "Reconnect" — force an immediate reconnect of the CURRENT session in
    // place (the manual escape hatch when auto-reconnect doesn't fire). Defaulted so
    // existing direct callers / tests of TmuxMoreMenu stay source-compatible.
    onReconnect: () -> Unit = {},
    // Issue #993: gate the "Reconnect" item — disabled while there is no target to
    // reconnect to OR a connect/reconnect is already in flight, so a tap is never a
    // silent no-op nor a redundant re-dial. Defaulted true so existing callers/tests
    // stay source-compatible.
    reconnectEnabled: Boolean = true,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        // Issue #857: the kebab was a flat, ungrouped list that mixed
        // current-session actions, an identify/classify item, file actions,
        // connection, and app settings. It is now grouped into logical sections
        // (header + divider per section). Every item keeps its exact onClick and
        // test tag — this is ordering/grouping only, no behaviour change.
        // (Issue #782 already removed the tmux-window group; the only session-
        // scoped ops left are session lifecycle + host shortcuts.)
        //
        // --- This session: act on the session you're looking at right now. ---
        DropdownMenuSectionHeader(text = "This session")
        DropdownMenuItem(text = { Text("Rename session") }, onClick = onRenameSession)
        // Epic #821 Slice 1: classify a foreign session ("What is this
        // session?") or re-classify any session ("Change kind"). Writes the
        // durable host-side `@ps_agent_kind` option via ManualKindWriter.
        // Issue #857: moved up next to Rename — it identifies/changes *this*
        // session, so it belongs with the current-session actions rather than
        // buried mid-lifecycle between Rename and Stop.
        DropdownMenuItem(
            text = {
                Text(
                    if (changeKindIsUnknown) "What is this session?" else "Change kind",
                )
            },
            onClick = onChangeKind,
            modifier = Modifier.testTag(TMUX_CHANGE_KIND_BUTTON_TAG),
        )
        DropdownMenuItem(text = { Text("Stop session") }, onClick = onKillSession)
        // Issue #235: explicit "I'm done with this session for now" affordance —
        // frees the tmux server-side window-size lock (max(phone, desktop) ->
        // desktop dimensions) without killing the session. Still a current-
        // session action (Detach -> sessions dashboard), so it lives in the
        // "This session" group next to the lifecycle items.
        DropdownMenuItem(
            text = { Text("Detach") },
            onClick = onDetach,
            modifier = Modifier.testTag(TMUX_DETACH_BUTTON_TAG),
        )
        // Issue #892: manual escape hatch from a black/partial-black terminal — force a
        // full-viewport reseed of the pane you're looking at, over the warm session (no
        // reconnect / detach / new lease). Lives in the "This session" group because it
        // acts on the current pane's display only.
        DropdownMenuItem(
            text = { Text("Redraw") },
            onClick = onRedraw,
            modifier = Modifier.testTag(TMUX_REDRAW_BUTTON_TAG),
        )
        // Issue #993: manual escape hatch from a dropped session whose auto-reconnect
        // didn't fire — force an immediate reconnect of THIS session in place (no
        // session-switch dance), reusing the VM's single TransportEffects reconnect
        // entrypoint. On reconnect the #900 outbound queue auto-flushes the pending
        // message. Disabled (greyed) when there is no target or a reconnect is already
        // in flight so the tap is never a silent no-op / redundant re-dial.
        DropdownMenuItem(
            text = { Text("Reconnect") },
            onClick = onReconnect,
            enabled = reconnectEnabled,
            modifier = Modifier.testTag(TMUX_RECONNECT_BUTTON_TAG),
        )

        // --- Sessions: move between / create sessions on this host. ---
        HorizontalDivider()
        DropdownMenuSectionHeader(text = "Sessions")
        DropdownMenuItem(text = { Text("+ New session") }, onClick = onCreateSession)
        DropdownMenuItem(text = { Text("Switch session") }, onClick = onSwitchSession)

        // --- Files: open / browse files on this host. ---
        HorizontalDivider()
        DropdownMenuSectionHeader(text = "Files")
        // Issue #528: browse the remote filesystem and tap a file to open it in
        // the viewer.
        DropdownMenuItem(
            text = { Text("Browse files…") },
            onClick = onBrowseFiles,
            modifier = Modifier.testTag(TMUX_BROWSE_FILES_BUTTON_TAG),
        )
        // Issue #497: open a server file (image / text) in the in-app viewer.
        DropdownMenuItem(
            text = { Text("Open file…") },
            onClick = onOpenFile,
            modifier = Modifier.testTag(TMUX_OPEN_FILE_BUTTON_TAG),
        )

        // --- Connection: per-host networking. ---
        HorizontalDivider()
        DropdownMenuSectionHeader(text = "Connection")
        // Issue #445 (epic #432 slice A): per-host port-forward panel. Navigating
        // away pushes onto the hand-rolled back-stack; back returns to this exact
        // session/window.
        DropdownMenuItem(
            text = {
                if (forwardingState.visible) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(
                            status = if (forwardingState.restoring) {
                                com.pocketshell.uikit.model.ConnectionStatus.Connecting
                            } else {
                                com.pocketshell.uikit.model.ConnectionStatus.Connected
                            },
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text("Port forwarding")
                            Text(
                                text = sessionForwardingMenuStatusLabel(forwardingState),
                                color = PocketShellColors.TextSecondary,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                } else {
                    Text("Port forwarding")
                }
            },
            onClick = onOpenPortForwarding,
            modifier = Modifier
                .semantics {
                    if (forwardingState.visible) {
                        contentDescription = forwardingState.contentDescription
                    }
                }
                .testTag(TMUX_PORT_FORWARDING_BUTTON_TAG),
        )

        // --- Host & app: cross-host / global affordances. ---
        HorizontalDivider()
        DropdownMenuSectionHeader(text = "Host & app")
        DropdownMenuItem(text = { Text("Recurring jobs") }, onClick = onOpenJobs)
        // Issue #114 Fix A: jump to the cross-host Usage / quota panel from
        // inside a live tmux session.
        DropdownMenuItem(text = { Text("Usage") }, onClick = onOpenUsage)
        DropdownMenuItem(
            text = { Text("Settings") },
            onClick = onOpenSettings,
            modifier = Modifier.testTag(TMUX_SETTINGS_BUTTON_TAG),
        )
    }
}

@Composable
private fun DropdownMenuSectionHeader(text: String) {
    Text(
        text = text,
        color = PocketShellColors.TextSecondary,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

/**
 * Issues #189 / #192: a single-row 56dp consolidated top chrome on
 * [TmuxSessionScreen].
 *
 * Per decision D30 (issue #782) PocketShell no longer manages tmux windows,
 * so there is NO window-tab row: Terminal/Conversation is the only in-session
 * tab dimension. Issue #303 renders that toggle inline in this chrome so it
 * does not cost a separate row. Windows that already exist on the server are
 * surfaced in the host tree as separate `[wN]` switcher entries, not as an
 * in-session strip.
 *
 * Layout (left → right inside one 56dp [Row]):
 * - 48dp back affordance (chevron).
 * - connection status dot + compact "Reconnecting"/"Disconnected" pill.
 * - `session` crumb (current destination; non-interactive) taking the
 *   remaining width.
 * - optional inline Terminal/Conversation pill when an agent or locked
 *   conversation is available.
 * - 48dp more affordance (kebab), which owns the dropdown anchor. Active
 *   port-forwarding status lives INSIDE that kebab menu (issue #601), not in
 *   the header row, so it never steals terminal chrome/content space.
 *
 * The host segment is intentionally not surfaced — the host name is
 * already visible on the host list, the pre-session status line, and on
 * [StatusLine]/[FailedConnectionRow] when not connected.
 */
@Composable
internal fun ConsolidatedTopChrome(
    sessionName: String,
    onBack: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    moreMenu: @Composable () -> Unit = {},
    // Issue #481: when the visible pane has a detected agent, the header
    // title is the agent/model name (e.g. `claude-3-5-sonnet`) per the
    // maintainer's terminal mockup, instead of the tmux session name. Null
    // (no agent / plain shell) falls back to [sessionName].
    agentName: String? = null,
    tabLabels: List<String> = emptyList(),
    selectedTabIndex: Int = 0,
    onTabSelected: (Int) -> Unit = {},
    pulseConversationTab: Boolean = false,
    // Issue #463: the in-session project switcher. When [projectLabel] is
    // non-null a tappable project/folder crumb renders at the leading edge
    // of the title slot. It shows a ▾ chevron and opens [projectSwitcher]'s
    // sibling-session dropdown only when there is at least one OTHER session
    // in the same project to switch to. Selecting a sibling fires
    // [onSwitchToSibling], which routes through the warm same-host switch.
    projectLabel: String? = null,
    projectSwitcher: HostTmuxSessionPickerViewModel.ProjectSwitcherState =
        HostTmuxSessionPickerViewModel.ProjectSwitcherState(),
    onProjectSwitcherOpen: () -> Unit = {},
    onSwitchToSibling: (String) -> Unit = {},
    // Issues #177 / #249: the live connection state, surfaced through the
    // breadcrumb's status dot (amber pulse while reconnecting, red while
    // disconnected) plus a compact "Reconnecting" / "Disconnected" pill.
    // This is the always-visible, unmissable indicator the user needs so
    // they never type into a session they think is live. Default
    // `Connected` keeps the screenshot harness / unit tests rendering the
    // steady-state breadcrumb.
    connectionStatus: com.pocketshell.uikit.model.ConnectionStatus =
        com.pocketshell.uikit.model.ConnectionStatus.Connected,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(color = PocketShellColors.Background)
            .height(56.dp)
            // Issue #637: a single horizontal inset on both edges keeps the
            // back chevron and the kebab a consistent, comfortable distance
            // from the screen edges in BOTH the breadcrumb and the
            // agent-toggle layout, instead of the kebab looking like it
            // floats at an odd offset.
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Back chevron — 48dp touch target so edge taps land on the
        // visible affordance consistently across the IME transition.
        Box(
            modifier = Modifier
                .size(48.dp)
                .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onBack)
                .testTag(TMUX_FULL_CHROME_BACK_BUTTON_TAG),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "‹",
                color = PocketShellColors.TextSecondary,
                fontSize = 20.sp,
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        com.pocketshell.uikit.components.StatusDot(
            status = connectionStatus,
        )
        Spacer(modifier = Modifier.width(8.dp))

        // Issue #463: the tappable project/folder crumb. Opens a dropdown of
        // this project's sibling sessions; selecting one warm-switches to it.
        // Hidden entirely when we don't know the project; the chevron is
        // hidden when there's nothing to switch to (single-session project).
        if (projectLabel != null) {
            ProjectSwitcherCrumb(
                projectLabel = projectLabel,
                switcher = projectSwitcher,
                onOpen = onProjectSwitcherOpen,
                onSwitchToSibling = onSwitchToSibling,
            )
            Spacer(modifier = Modifier.width(6.dp))
        }

        // Issue #481: the title — the agent/model name when a conversation
        // is detected (`claude-3-5-sonnet` in the mockup), otherwise the
        // tmux session name.
        //
        // Issue #637: the title takes the full weighted slot (`weight(1f)`,
        // fill = true) so it consumes ALL remaining width and pushes the
        // trailing control cluster flush against the right edge. This is what
        // gives the kebab a CONSISTENT right-anchored position in both
        // states — the previous `fill = false` left the title hugging its
        // own text, so the kebab floated in the middle of the row next to a
        // short name instead of sitting at the edge ("⋮ position looks off").
        // Because the title is the only element that yields width, a long
        // host/session name ellipsises inside this slot WITHOUT squeezing the
        // toggle or pushing the kebab off screen. The 8dp end padding
        // guarantees the name never butts straight against the trailing
        // controls.
        val sessionLabelModifier = Modifier
            .weight(1f)
            .padding(end = 8.dp)
            .testTag(TMUX_CONSOLIDATED_SESSION_LABEL_TAG)
        Text(
            text = agentName ?: sessionName,
            color = PocketShellColors.Text,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = sessionLabelModifier,
        )

        // Issue #637 / #747: the trailing controls. The SHRINKABLE middle group
        // (connection-status pill + Terminal/Conversation toggle) sits in its
        // OWN weighted slot — `weight(1f, fill = false)` — so it yields width
        // before anything else, while the kebab is a FIXED 48dp sibling of the
        // outer row that is laid out AFTER the weighted slots and so can never
        // be displaced.
        //
        // Why this matters (#747): previously the whole trailing cluster was a
        // single non-shrinking [Row]. When that cluster was wide (forwarding
        // active -> agent present -> the wide "Terminal Conversation" toggle, a
        // non-live "Disconnected"/"Reconnecting" pill, and a project crumb all
        // competing for width), the cluster overflowed the 56dp row and Compose
        // shoved its LAST child — the kebab — past the right edge. The
        // maintainer saw the kebab "can't be selected" because it was
        // off-screen. Putting the kebab outside the weighted slot reserves its
        // 48dp unconditionally; the toggle's segment labels ellipsise (they are
        // `maxLines = 1`) instead of pushing the kebab off-screen.
        Row(
            modifier = Modifier
                .weight(1f, fill = false)
                .padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ConnectionStatusPill(connectionStatus)

            if (tabLabels.size > 1) {
                TabsRowWithPulse(pulseVisible = pulseConversationTab) {
                    ConsolidatedTabPill(
                        labels = tabLabels,
                        selectedIndex = selectedTabIndex,
                        onSelected = onTabSelected,
                        modifier = Modifier.testTag(TMUX_TABS_TAG),
                    )
                }
            }
        }

        // The kebab — fixed 48dp, OUTSIDE every weighted slot, so it is always
        // reserved its full width at the right edge (#747).
        Box(modifier = Modifier.size(48.dp)) {
            KebabTrigger(
                contentDescription = "More session actions",
                onClick = onMore,
                triggerTestTag = TMUX_FULL_CHROME_MORE_BUTTON_TAG,
                triggerSize = 48.dp,
            )
            moreMenu()
        }
    }
}

/**
 * Issue #463: the tappable project/folder crumb in the session header that
 * opens an in-session, project-scoped session switcher.
 *
 * The crumb shows the current project's leaf folder label and a ▾ chevron.
 * Tapping it anchors a [DropdownMenu] listing the sibling sessions in the
 * same project (sourced from the warm live `-CC` client — never a fresh SSH
 * connect), each row showing the session name, a state chip, and a
 * [com.pocketshell.uikit.components.StatusDot] consistent with the folder
 * tree rows. Selecting a sibling fires [onSwitchToSibling], which routes
 * through the existing `onReplaceTmuxSession` → warm same-host switch (no
 * reconnect; status flips to `Switching`, not `Connecting`).
 *
 * The chevron — and the whole tap affordance — is only shown when the
 * project has at least one OTHER session to switch to
 * ([HostTmuxSessionPickerViewModel.ProjectSwitcherState.hasSiblingsToSwitch]).
 * A single-session project renders a plain, non-interactive label so there
 * is zero Fitts/Hick cost when there is nothing to switch to.
 */
@Composable
private fun ProjectSwitcherCrumb(
    projectLabel: String,
    switcher: HostTmuxSessionPickerViewModel.ProjectSwitcherState,
    onOpen: () -> Unit,
    onSwitchToSibling: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val canSwitch = switcher.hasSiblingsToSwitch
    Box(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .then(
                    if (canSwitch) {
                        Modifier.clickable(
                            role = androidx.compose.ui.semantics.Role.Button,
                        ) {
                            onOpen()
                            expanded = true
                        }
                    } else {
                        Modifier
                    },
                )
                .background(
                    color = if (canSwitch) {
                        PocketShellColors.Accent.copy(alpha = 0.14f)
                    } else {
                        androidx.compose.ui.graphics.Color.Transparent
                    },
                    shape = PocketShellShapes.extraSmall,
                )
                .padding(horizontal = if (canSwitch) 8.dp else 0.dp, vertical = 4.dp)
                .testTag(TMUX_PROJECT_SWITCHER_TAG),
        ) {
            Text(
                text = projectLabel,
                color = if (canSwitch) PocketShellColors.Accent else PocketShellColors.TextSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 120.dp),
            )
            if (canSwitch) {
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                    text = "▾",
                    color = PocketShellColors.Accent,
                    style = PocketShellType.labelMono,
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.testTag(TMUX_PROJECT_SWITCHER_MENU_TAG),
        ) {
            val rows = switcher.siblings
            rows.forEach { row ->
                val isCurrent = row.name == switcher.currentSessionName
                DropdownMenuItem(
                    enabled = !isCurrent,
                    onClick = {
                        expanded = false
                        if (!isCurrent) onSwitchToSibling(row.name)
                    },
                    modifier = Modifier.testTag(TMUX_PROJECT_SWITCHER_ROW_TAG_PREFIX + row.name),
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            com.pocketshell.uikit.components.StatusDot(
                                status = if (isCurrent || row.attached) {
                                    com.pocketshell.uikit.model.ConnectionStatus.Connected
                                } else {
                                    com.pocketshell.uikit.model.ConnectionStatus.Idle
                                },
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = row.name,
                                    color = PocketShellColors.Text,
                                    fontSize = 14.sp,
                                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = when {
                                        isCurrent -> "Current"
                                        row.attached -> "Attached"
                                        else -> "Available"
                                    },
                                    color = PocketShellColors.TextSecondary,
                                    style = PocketShellType.labelMono,
                                )
                            }
                        }
                    },
                )
            }
        }
    }
}

/**
 * Issue #189: the inline Terminal / Conversation toggle inside
 * [ConsolidatedTopChrome]. Visually a slim segmented pill — both labels
 * inside one bordered, rounded container — so the user reads it as a
 * single co-located control rather than two separate buttons. The
 * selected segment takes [PocketShellColors.Accent] fill; the
 * unselected segments sit on transparent background and lean on
 * [PocketShellColors.TextSecondary].
 *
 * Sized to fit inside the 56dp toolbar row with room for two short
 * labels — the only call site today is the two-label "Terminal /
 * Conversation" toggle. Three or more labels keep working but will
 * push the layout wider.
 */
@Composable
private fun ConsolidatedTabPill(
    labels: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Issue #481: the inline Terminal/Conversation toggle now composes the
    // shared [com.pocketshell.uikit.components.SegmentedToggle] so it reads
    // identically to every other segmented control in the app (cyan-active,
    // dark-on-cyan label). The per-segment test tags stay: index 0 is the
    // Terminal segment ([TMUX_TERMINAL_TAB_TAG] — a named alias kept for
    // tests that previously asserted on the visible "Terminal" text, now
    // suppressed in single-tab shell-only sessions); other indices use the
    // generic per-index prefix hook.
    com.pocketshell.uikit.components.SegmentedToggle(
        labels = labels,
        selectedIndex = selectedIndex,
        onSelected = onSelected,
        modifier = modifier,
        segmentTag = { index ->
            if (index == 0) {
                TMUX_TERMINAL_TAB_TAG
            } else {
                TMUX_CONSOLIDATED_TAB_PILL_TAG_PREFIX + index
            }
        },
    )
}

/**
 * Issue #167: a thin wrapper around [BackHandler] that codifies the back
 * routing on [TmuxSessionScreen].
 *
 * Order of precedence:
 *  1. If a lifecycle dialog is open ([dialogOpen]), back closes the
 *     dialog (mirroring the Cancel button).
 *  2. Otherwise, if the session drawer is open ([sessionDrawerOpen]),
 *     back closes the drawer.
 *  3. Otherwise, the window switcher / mic sheet / snippet picker
 *     overlays close in turn.
 *  4. Otherwise [onBack] runs — which pops the in-app back stack to the
 *     host list. The hosted `TmuxSessionViewModel` is then cleared by
 *     Compose's lifecycle owner, and its `onCleared()` tears the SSH +
 *     tmux client state down (via `closeCurrentConnection`).
 *
 * Extracted as its own composable so the routing behaviour can be
 * regression-tested without a Hilt graph or a live tmux client — the
 * AVD-side reviewer evidence is the user journey "attach -> system back ->
 * host list" against the deterministic Docker fixture, which exercises
 * the implicit teardown via the ViewModel's `onCleared`.
 *
 * `DropdownMenu` and `AlertDialog` already intercept system-back
 * themselves in front of any `BackHandler` registered by the screen, so
 * the more-menu and window-context-menu dropdowns are not surfaced here;
 * back on those closes the popup first by the dropdown's own handling.
 */
@Composable
internal fun TmuxSessionBackHandler(
    dialogOpen: Boolean,
    sessionDrawerOpen: Boolean,
    micSheetOpen: Boolean,
    snippetPickerOpen: Boolean,
    checklistSheetOpen: Boolean = false,
    onDismissDialog: () -> Unit,
    onDismissSessionDrawer: () -> Unit,
    onDismissMicSheet: () -> Unit,
    onDismissSnippetPicker: () -> Unit,
    onDismissChecklistSheet: () -> Unit = {},
    onBack: () -> Unit,
) {
    BackHandler {
        when {
            dialogOpen -> onDismissDialog()
            sessionDrawerOpen -> onDismissSessionDrawer()
            micSheetOpen -> onDismissMicSheet()
            snippetPickerOpen -> onDismissSnippetPicker()
            checklistSheetOpen -> onDismissChecklistSheet()
            else -> onBack()
        }
    }
}

/**
 * Issue #184 Layer 2: a slim replacement for the full
 * [com.pocketshell.uikit.components.Breadcrumb] that is shown while the
 * soft keyboard is up. The user is in typing-focus and does not need the
 * full `host › session › window › pane` chain — they already know what
 * pane they are typing into. We surface just the session name so the
 * screen still answers "where am I?" at a glance, and keep the back and
 * kebab-menu affordances so the user can bail out or open the more-menu
 * without first dismissing the IME.
 *
 * Layout matches the chrome-tightening rules from issue #184:
 * - 40dp tall (vs. 56dp for the full breadcrumb), recovering 16dp of
 *   vertical space for the terminal viewport.
 * - Same back + more icon buttons at the leading / trailing edges as the
 *   full breadcrumb so the touch targets stay consistent across the
 *   IME-open / IME-closed transition (no surprise reflow of tap zones).
 * - Session name only — no live dot, no separators, no host / window /
 *   pane crumbs. The full breadcrumb returns on IME-hide.
 */
@Composable
internal fun CompactBreadcrumb(
    sessionName: String,
    onBack: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    moreMenu: @Composable () -> Unit = {},
    // Issues #177 / #249: even in the IME-up compact chrome the user must
    // be able to tell the session is not live before they dictate into it.
    connectionStatus: com.pocketshell.uikit.model.ConnectionStatus =
        com.pocketshell.uikit.model.ConnectionStatus.Connected,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(color = PocketShellColors.Background)
            .height(40.dp)
            .padding(start = 4.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(48.dp)
                .fillMaxHeight()
                .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onBack)
                .testTag(TMUX_COMPACT_CHROME_BACK_BUTTON_TAG),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "‹",
                color = PocketShellColors.TextSecondary,
                fontSize = 20.sp,
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        com.pocketshell.uikit.components.StatusDot(status = connectionStatus)
        Spacer(modifier = Modifier.width(6.dp))
        val compactSessionLabelModifier = Modifier
            .weight(1f)
        Text(
            text = sessionName,
            color = PocketShellColors.Text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = compactSessionLabelModifier,
        )
        ConnectionStatusPill(connectionStatus)
        Spacer(modifier = Modifier.width(4.dp))
        Box(
            modifier = Modifier
                .width(48.dp)
                .fillMaxHeight(),
        ) {
            KebabTrigger(
                contentDescription = "More session actions",
                onClick = onMore,
                triggerTestTag = TMUX_COMPACT_CHROME_MORE_BUTTON_TAG,
                triggerSize = 48.dp,
            )
            moreMenu()
        }
    }
}

/**
 * Issues #177 / #249: compact "Reconnecting" / "Disconnected" pill shown
 * in the breadcrumb next to the [com.pocketshell.uikit.components.StatusDot].
 *
 * Rendered only for the non-live states so the steady-state breadcrumb
 * stays uncluttered. It is the always-visible textual confirmation of
 * what the dot's colour signals — the user should never have to guess
 * whether the session is live before they dictate into it. Tagged with
 * [TMUX_CONNECTION_STATUS_PILL_TAG] so connected tests can assert it
 * appears while a reattach handshake is in flight and clears when live.
 */
@Composable
private fun ConnectionStatusPill(
    status: com.pocketshell.uikit.model.ConnectionStatus,
) {
    val (label, color) = when (status) {
        com.pocketshell.uikit.model.ConnectionStatus.Connected -> return
        com.pocketshell.uikit.model.ConnectionStatus.Connecting ->
            "Reconnecting" to PocketShellColors.Amber
        com.pocketshell.uikit.model.ConnectionStatus.Error ->
            "Disconnected" to PocketShellColors.Red
        com.pocketshell.uikit.model.ConnectionStatus.Idle ->
            "Connecting" to PocketShellColors.TextMuted
    }
    Text(
        text = label,
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        modifier = Modifier
            .background(
                color = color.copy(alpha = 0.14f),
                shape = RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .testTag(TMUX_CONNECTION_STATUS_PILL_TAG),
    )
}

/**
 * Issues #177 / #249: map the tmux view-model connection state onto the
 * design-system [com.pocketshell.uikit.model.ConnectionStatus] used by the
 * breadcrumb dot + pill. `Failed` maps to `Error` (red, "Disconnected");
 * `Connecting` maps to `Connecting` (amber pulse, "Reconnecting").
 */
internal fun ConnectionStatus.toUiStatus(): com.pocketshell.uikit.model.ConnectionStatus =
    when (this) {
        is ConnectionStatus.Connected -> com.pocketshell.uikit.model.ConnectionStatus.Connected
        // Issue #437 (slice A): a same-host session switch keeps the
        // terminal frame on screen and only swaps the active `-CC` control
        // client behind the scenes. Map it to `Connected` so the
        // breadcrumb dot stays green (no alarming amber "Reconnecting"
        // flash) — the session is up; we are just changing which session
        // is rendered.
        is ConnectionStatus.Switching -> com.pocketshell.uikit.model.ConnectionStatus.Connected
        is ConnectionStatus.Connecting -> com.pocketshell.uikit.model.ConnectionStatus.Connecting
        is ConnectionStatus.Reconnecting -> com.pocketshell.uikit.model.ConnectionStatus.Connecting
        is ConnectionStatus.Failed -> com.pocketshell.uikit.model.ConnectionStatus.Error
        ConnectionStatus.Idle -> com.pocketshell.uikit.model.ConnectionStatus.Idle
    }

internal fun recordTmuxReconnectUiStateRendered(
    status: ConnectionStatus,
    hostId: Long,
    canReconnect: Boolean,
) {
    when (status) {
        is ConnectionStatus.Reconnecting -> ReconnectCauseTrail.record(
            stage = "ui_reconnect_state",
            outcome = "rendered",
            cause = "connection_status_reconnecting",
            "hostId" to hostId,
            "attempt" to status.attempt,
            "maxAttempts" to status.maxAttempts,
            "retryDelayMs" to status.retryDelayMs,
            "canReconnect" to canReconnect,
        )
        is ConnectionStatus.Failed -> ReconnectCauseTrail.record(
            stage = "ui_reconnect_state",
            outcome = "rendered",
            cause = "connection_status_failed",
            "hostId" to hostId,
            "canReconnect" to canReconnect,
        )
        else -> Unit
    }
}

@Composable
private fun TmuxLifecycleDialog(
    mode: TmuxDialogMode,
    sessionName: String,
    text: String,
    onTextChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    // Issue #898: the create-session branch is gone — "+ New session" now opens
    // the rich SessionTypePickerSheet (hard-cut D22). This dialog only handles
    // the remaining text/confirm lifecycle actions: Rename and Stop.
    val title = when (mode) {
        TmuxDialogMode.RenameSession -> "Rename session"
        TmuxDialogMode.StopSession -> "Stop session"
    }
    val confirm = when (mode) {
        TmuxDialogMode.StopSession -> "Stop"
        else -> "Save"
    }
    val isTextMode = mode == TmuxDialogMode.RenameSession
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .navigationBarsPadding()
            .imePadding(),
        title = { Text(title) },
        text = {
            if (isTextMode) {
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    singleLine = true,
                    label = { Text("Session name") },
                )
            } else {
                val target = when (mode) {
                    TmuxDialogMode.StopSession -> sessionName
                    else -> ""
                }
                Text("This will close $target.")
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !isTextMode || text.trim().isNotEmpty(),
                modifier = Modifier.testTag(TMUX_LIFECYCLE_DIALOG_CONFIRM_TAG),
            ) {
                Text(confirm)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(TMUX_LIFECYCLE_DIALOG_CANCEL_TAG),
            ) {
                Text("Cancel")
            }
        },
    )
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
 * Bottom terminal controls for tmux panes.
 *
 * Issue #588: once the terminal keyboard is up, this area is strictly a
 * terminal-control accessory. Prompt text belongs in [PromptComposerSheet],
 * opened from the IME-hidden bottom band.
 *
 * Issue #784 (composer/hotkeys redesign — D22 hard-cut): the terminal hotkey
 * key bar no longer lives HERE or in the composer. #755 had relocated it into
 * the composer, where it ate the space above the keyboard, hid keys behind a
 * `…` expander, and squished the compose field. It is now the dedicated
 * [com.pocketshell.uikit.components.TerminalHotkeysPanel] in its OWN bottom
 * sheet ([TerminalHotkeysSheet]), opened from this surface's hotkeys launcher.
 *
 * Issue #789 (D22 hard-cut): the launcher is a COMPACT chip, not the deleted
 * full-width bar — its row of vertical space wasted too much room. With the
 * keyboard UP this control area renders a single right-pinned `hotkeys` chip
 * above the IME (one tap to open the panel); with the keyboard DOWN the same
 * compact chip lives inline in the [BottomChipControls] primary cluster.
 *
 * Issue #673: staged composer attachments are NOT rendered here. They are
 * visible only inside the Prompt Composer sheet; the staged-attachment STATE
 * still lives in the composer ViewModel (persisting across session switches),
 * so re-opening the composer shows them again. The session/terminal bottom
 * area never surfaces an attachment chip/grid.
 */
@Composable
internal fun TmuxSessionBottomControlsCallSite(
    isImeVisible: Boolean,
    showConversationTranscript: Boolean,
    showConversationDetectingPlaceholder: Boolean,
    sessionLive: Boolean,
    isAgentPane: Boolean,
    onChipTap: (String) -> Unit,
    onDictateTap: (() -> Unit)?,
    onEnterTap: (() -> Unit)?,
    onShowKeyboardTap: (() -> Unit)?,
    onAddSnippetTap: (() -> Unit)?,
    onShowHotkeysTap: (() -> Unit)? = null,
    leadingChipContent: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // Issue #805 (regression of #744/#716): bottom-bar chrome follows the
    // Conversation TAB, not only the detection-gated transcript. During
    // agent-engine detection the transcript is absent but the placeholder is
    // shown, so this must still drop the Terminal chips that can push the
    // composer launcher off-screen.
    val onConversationTab = tmuxSessionBottomControlsShowsConversation(
        showConversationTranscript = showConversationTranscript,
        showConversationDetectingPlaceholder = showConversationDetectingPlaceholder,
    )
    TmuxTerminalBottomControls(
        isImeVisible = isImeVisible,
        showConversation = onConversationTab,
        sessionLive = sessionLive,
        isAgentPane = isAgentPane,
        onChipTap = onChipTap,
        onDictateTap = onDictateTap,
        onEnterTap = onEnterTap,
        onShowKeyboardTap = onShowKeyboardTap,
        onAddSnippetTap = onAddSnippetTap,
        onShowHotkeysTap = onShowHotkeysTap,
        leadingChipContent = leadingChipContent,
        modifier = modifier,
    )
}

@Composable
internal fun TmuxTerminalBottomControls(
    isImeVisible: Boolean,
    showConversation: Boolean,
    sessionLive: Boolean,
    isAgentPane: Boolean,
    onChipTap: (String) -> Unit,
    onDictateTap: (() -> Unit)?,
    onEnterTap: (() -> Unit)?,
    onShowKeyboardTap: (() -> Unit)?,
    onAddSnippetTap: (() -> Unit)?,
    // Issue #784/#789: open the dedicated terminal-hotkeys panel. Reachable as a
    // COMPACT chip both with the keyboard down (inline in the chip cluster) and
    // with the keyboard UP (a right-pinned chip above the IME), so the user can
    // summon the full hotkey grid whenever they are interacting with the
    // terminal. Null on surfaces with no pane to receive control bytes (e.g. the
    // Conversation tab).
    onShowHotkeysTap: (() -> Unit)? = null,
    leadingChipContent: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val chromeMode = tmuxTerminalKeyboardChromeMode(
        isImeVisible = isImeVisible,
        showConversation = showConversation,
    )
    when (chromeMode) {
        // Issue #673: the conversation IME-open mode renders no accessory at
        // all. Staged attachments used to surface here; they now live only
        // inside the Prompt Composer sheet.
        TmuxTerminalKeyboardChromeMode.OpenImeConversationNoAccessory -> Unit
        // Issue #784: with the keyboard up on the Terminal tab, render a SLIM
        // launcher bar above the IME so the hotkeys panel is one tap away while
        // typing. The full hotkey grid lives in the dedicated
        // [TerminalHotkeysPanel] bottom sheet — never crammed above the keyboard
        // (the #755/#784 occlusion + cram complaints), just a single launcher.
        TmuxTerminalKeyboardChromeMode.OpenImeTerminalHotkeys -> {
            if (onShowHotkeysTap != null) {
                // Issue #789 (hard-cut, D22): above the soft keyboard the
                // launcher is a COMPACT chip pinned to the right edge — NOT the
                // deleted full-width bar — so it occupies a single short row and
                // reclaims the vertical space the bar wasted. It sits on the
                // surface band so it reads above the IME, and opens the same
                // dedicated [TerminalHotkeysSheet]. The host's layout modifier
                // (which carries `imePadding()` upstream) keeps it above the
                // keyboard. The chip carries the stable `tmux:hotkeys-launcher`
                // tag so existing tests still find it.
                Row(
                    modifier = modifier
                        .fillMaxWidth()
                        .background(color = PocketShellColors.Surface)
                        .border(width = 1.dp, color = PocketShellColors.Border)
                        .padding(
                            horizontal = PocketShellSpacing.sm,
                            vertical = PocketShellSpacing.sm,
                        ),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CommandChip(
                        label = HOTKEYS_CHIP_LABEL,
                        onClick = onShowHotkeysTap,
                        icon = HotkeysChipIcon,
                        modifier = Modifier.testTag(TERMINAL_HOTKEYS_LAUNCHER_TAG),
                    )
                }
            }
        }
        TmuxTerminalKeyboardChromeMode.HiddenImeControls -> {
            if (showConversation) {
                // Issue #786 (hard-cut, D22): the Conversation tab's bottom band
                // is JUST the composer launcher — no static command chips, no
                // #628 previous-session toggle chip (the `› <project>` pill the
                // maintainer circled and didn't recognise), no snippets `{}` chip,
                // no primary cluster. Everything the bar offered stays reachable:
                // fast session-switch on the top breadcrumb, snippets in the
                // composer's `{}`, slash commands via the composer (`/`
                // autocomplete). The launcher keeps its #810 unconditional
                // presence. The Terminal tab (the `else` below) is untouched and
                // keeps its full chip band / key chips.
                if (onDictateTap != null) {
                    ConversationComposerLauncherRow(
                        onDictateTap = onDictateTap,
                        inputEnabled = sessionLive,
                        modifier = modifier,
                    )
                }
            } else {
                // Issue #789 (hard-cut, D22): the full-width
                // `TerminalHotkeysLauncherBar` (#784) is GONE. The launcher is now
                // a COMPACT chip inline in the [BottomChipControls] primary
                // cluster, so the dedicated bar row's vertical space is reclaimed.
                // The chip opens the same dedicated [TerminalHotkeysSheet].
                // Terminal tab only — the panel writes control bytes to the raw
                // pane.
                BottomChipControls(
                    chips = if (isAgentPane) AgentExitChips else DefaultSessionChips,
                    onChipTap = onChipTap,
                    onDictateTap = onDictateTap,
                    onEnterTap = onEnterTap,
                    onShowKeyboardTap = onShowKeyboardTap,
                    onAddSnippetTap = onAddSnippetTap,
                    // Issue #789: the compact hotkeys launcher chip (terminal tab
                    // only). Reclaims the deleted full-width bar's row.
                    onShowHotkeysTap = onShowHotkeysTap,
                    addSnippetLabel = ADD_COMMAND_CHIP_LABEL,
                    addSnippetIcon = SnippetsChipIcon,
                    leadingContent = leadingChipContent,
                    // Project navigation on tmux panes is a separate
                    // follow-up — see #123 notes on per-pane cwd /
                    // project-root wiring.
                    onProjectNavigationTap = null,
                    inputEnabled = sessionLive,
                    modifier = modifier,
                )
            }
        }
    }
}

/**
 * Issue #789 (hard-cut, D22): the full-width `TerminalHotkeysLauncherBar` (#784)
 * was DELETED — the maintainer reported the dedicated bar row wasted vertical
 * space. The launcher is now a COMPACT chip ([com.pocketshell.app.voice.HOTKEYS_CHIP_LABEL])
 * rendered inline in the primary chip cluster ([com.pocketshell.app.voice.BottomChipControls])
 * with the keyboard down, and as a compact right-pinned chip above the IME with
 * the keyboard up — both opening the same dedicated [TerminalHotkeysSheet].
 *
 * The stable test tag is kept as an alias of
 * [com.pocketshell.app.voice.HOTKEYS_CHIP_TAG] (`"tmux:hotkeys-launcher"`) so the
 * existing connected tests that locate the launcher by this tag keep working
 * unchanged.
 */
internal const val TERMINAL_HOTKEYS_LAUNCHER_TAG: String =
    com.pocketshell.app.voice.HOTKEYS_CHIP_TAG

internal enum class TmuxTerminalKeyboardChromeMode {
    HiddenImeControls,
    OpenImeTerminalHotkeys,
    OpenImeConversationNoAccessory,
}

internal fun tmuxTerminalKeyboardChromeMode(
    isImeVisible: Boolean,
    showConversation: Boolean,
): TmuxTerminalKeyboardChromeMode = when {
    !isImeVisible -> TmuxTerminalKeyboardChromeMode.HiddenImeControls
    showConversation -> TmuxTerminalKeyboardChromeMode.OpenImeConversationNoAccessory
    else -> TmuxTerminalKeyboardChromeMode.OpenImeTerminalHotkeys
}

/**
 * Issue #784: the dedicated terminal-hotkeys panel key set.
 *
 * This replaces the cramped, `…`-overflowing in-composer key bar (#458/#755,
 * hard-cut per D22). The panel shows EVERY key at once in a tidy multi-row grid
 * — no `…` expander, no horizontal scroll, no lone `Ctrl` modifier, no
 * duplicate `/`. Each label is audited so the visible glyph equals the byte
 * sent ([TmuxSessionViewModel.onKeyBarKey]):
 *
 *  - Keys section: `Esc` (0x1B), `Tab`, `Enter` (#527).
 *  - Ctrl combos section: the useful chords as DIRECT buttons (no modifier to
 *    arm first) — `^A`(0x01) `^B`(0x02, tmux prefix / Claude "ctrl-b ctrl-b",
 *    #677) `^C`(0x03) `^D`(0x04) `^E`(0x05) `^L`(0x0C) `^R`(0x12) `^Z`(0x1A).
 *    `^[` (0x1B) is intentionally omitted: it equals `Esc`, which is already in
 *    the Keys section — exposing both would re-introduce the duplicate the
 *    maintainer flagged.
 *  - Arrows section: `←` `↑` `↓` `→` with clean, legible glyphs (replacing the
 *    old hard-to-read `‹ ⌃ ⌄ ›`).
 *
 * Every key routes through [TmuxSessionViewModel.onKeyBarKey], which maps the
 * label to its control byte (`send-keys -H` overlay) or tmux named key — no
 * terminal resize/redraw.
 */
internal const val TmuxHotkeyEnterLabel: String = "Enter"

// Issue #787: the DOUBLED interrupt/EOF controls, re-homed into the hotkeys
// panel from the deleted `/ commands` palette (where they were the only home —
// originally #453/#543). The double-press is a DISTINCT sequence from the single
// `^C`/`^D` above: Claude Code (and many REPLs) treat the first `^C`/`^D` as
// "press again to interrupt / exit", so the doubled byte is what actually stops
// the running agent / sends EOF. `onKeyBarKey` maps these two labels to
// `sendControlInputToPane(..., repeatCount = 2)`.
internal const val TmuxHotkeyInterruptX2Label: String = "^C×2"
internal const val TmuxHotkeyEofX2Label: String = "^D×2"

internal val TmuxHotkeyPanelSections: List<com.pocketshell.uikit.components.HotkeySection> = listOf(
    com.pocketshell.uikit.components.HotkeySection(
        title = "KEYS",
        keys = listOf(
            KeyBinding(label = "Esc", kind = KeyKind.Regular),
            KeyBinding(label = "Tab", kind = KeyKind.Regular),
            // Issue #893: ⇧Tab (back-tab / Shift+Tab) sends tmux's `BTab` named
            // key (ESC [ Z) so the maintainer can cycle Claude Code's
            // permission/plan mode from the phone. 4 columns keep every label
            // fully readable (Esc / Tab / ⇧Tab / Enter).
            KeyBinding(label = "⇧Tab", kind = KeyKind.Regular),
            KeyBinding(label = TmuxHotkeyEnterLabel, kind = KeyKind.Regular),
        ),
        columns = 4,
    ),
    com.pocketshell.uikit.components.HotkeySection(
        title = "CTRL COMBOS",
        keys = listOf(
            KeyBinding(label = "^A", kind = KeyKind.Regular),
            KeyBinding(label = "^B", kind = KeyKind.Regular),
            KeyBinding(label = "^C", kind = KeyKind.Regular),
            KeyBinding(label = "^D", kind = KeyKind.Regular),
            KeyBinding(label = "^E", kind = KeyKind.Regular),
            KeyBinding(label = "^L", kind = KeyKind.Regular),
            KeyBinding(label = "^R", kind = KeyKind.Regular),
            KeyBinding(label = "^Z", kind = KeyKind.Regular),
        ),
        columns = 4,
    ),
    // Issue #787: interrupt / EOF doubled chords (re-homed from the deleted
    // palette). Distinct from the single `^C`/`^D` above — these send the byte
    // TWICE so they actually stop the running agent / exit the REPL.
    com.pocketshell.uikit.components.HotkeySection(
        title = "INTERRUPT / EOF",
        keys = listOf(
            KeyBinding(label = TmuxHotkeyInterruptX2Label, kind = KeyKind.Regular),
            KeyBinding(label = TmuxHotkeyEofX2Label, kind = KeyKind.Regular),
        ),
        columns = 2,
    ),
    com.pocketshell.uikit.components.HotkeySection(
        title = "ARROWS",
        keys = listOf(
            KeyBinding(label = "←", kind = KeyKind.Arrow),
            KeyBinding(label = "↑", kind = KeyKind.Arrow),
            KeyBinding(label = "↓", kind = KeyKind.Arrow),
            KeyBinding(label = "→", kind = KeyKind.Arrow),
        ),
        columns = 4,
    ),
)

// Issue #454: the agent-pane bottom band is decluttered to the composer
// launcher plus primary controls. Slash commands are no longer part of the
// scrollable chip list — and as of #787 the standalone slash palette + bottom
// `/ commands` chip are gone entirely (the only slash entry now lives in the
// composer: its `/` button + type-`/` autocomplete). The former `Ctrl-C ×2` /
// `Ctrl-D ×2` interrupt/EOF chips' function is preserved in the hotkeys panel's
// "INTERRUPT / EOF" section (see [TmuxHotkeyInterruptX2Label] /
// [TmuxHotkeyEofX2Label] and [onKeyBarKey]).
internal val AgentExitChips: List<String> = emptyList()
