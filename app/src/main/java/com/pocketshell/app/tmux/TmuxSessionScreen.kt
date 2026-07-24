package com.pocketshell.app.tmux

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.pocketshell.app.composer.OutboundRoute
import com.pocketshell.app.composer.PromptComposerSendDispatcher
import com.pocketshell.app.conversation.ConversationImageViewModel
import com.pocketshell.app.conversation.LocalConversationImageLoader
import com.pocketshell.app.conversation.rememberConversationToTerminalSwapLatch
import com.pocketshell.app.composer.PromptComposerViewModel
import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.app.layout.rememberTmuxImeLayoutState
import com.pocketshell.app.projects.conventionalRemoteHome
import com.pocketshell.app.projects.defaultSessionBaseName
import com.pocketshell.app.projects.derivedSessionName
import com.pocketshell.app.session.ConversationLoadState
import com.pocketshell.app.session.ConversationLinkAction
import com.pocketshell.app.session.InlineDictationViewModel
import com.pocketshell.app.settings.SettingsViewModel
import com.pocketshell.app.session.SessionTab
import com.pocketshell.app.session.conversationLinkAction
import com.pocketshell.app.session.cwdForDetectedFilePath
import com.pocketshell.app.sessions.HostTmuxSessionPickerRequest
import com.pocketshell.app.sessions.HostTmuxSessionPickerState
import com.pocketshell.app.sessions.HostTmuxSessionPickerViewModel
import com.pocketshell.app.cards.SessionCardFeedChip
import com.pocketshell.app.cards.SessionCardInteractions
import com.pocketshell.app.cards.cardFeedChipState
import com.pocketshell.app.snippets.SnippetKind
import com.pocketshell.app.snippets.snippetDispatchText
import com.pocketshell.app.startup.StartupTiming
import com.pocketshell.app.tmux.TmuxSessionViewModel.ConnectionStatus
import com.pocketshell.app.voice.AssistantStrip
import com.pocketshell.core.terminal.selection.LocalhostUrl
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.terminal.ui.showTerminalSoftKeyboard
import com.pocketshell.uikit.model.SessionAgentKind
import com.pocketshell.uikit.theme.PocketShellColors

/**
 * Phase 2 session screen for `tmux -CC` hosts, rendering one pane at a time.
 *
 * Per [D6](../../../../../../../../docs/decisions.md), exactly ONE pane is
 * rendered at a time, wrapped in a [HorizontalPager] so a horizontal swipe
 * navigates left/right between panes inside the current window. No tiled
 * rendering — tmux's native split layout is unreadable at phone scale.
 *
 * ## Issue #1685 — decomposition (ART VerifyError, recurrence #3)
 *
 * This screen used to be ONE ~2,450-line composable method whose compiled dex
 * frame (≥305 registers) crossed ART's 256-register wide-register cliff, so the
 * class failed on-device verification (`java.lang.VerifyError`) and the app's
 * MAIN screen crashed at load. This has recurred three times (#1158 v273, #1362
 * v300, #1685 v304), each prior fix hoisting just enough to duck back under 256
 * and leaving the method AT the cliff.
 *
 * The durable fix decomposes the god-method into a thin coordinator that wires a
 * few state HOLDERS (in `TmuxSessionScreenRuntime.kt`) into region composables —
 * effects, header, surface, overlays, sheets — each of which gets its OWN dex
 * frame with a hard margin below the cliff. Behaviour is IDENTICAL: every block
 * is the same code, only relocated; each region unpacks the holders it needs
 * into the SAME local names the inline code used, so the moved blocks are
 * verbatim. A per-PR dex register-pressure ratchet
 * (`scripts/check-dex-register-pressure.sh`) now fails any `TmuxSessionScreen*`
 * method that drifts back toward the cliff, so recurrence #4 is a per-PR failure
 * instead of a phone crash. The recomposition-scope invariants of #1085/#796
 * (agent-streaming flush off the root, deferred IME read) are preserved: the
 * high-frequency `agentConversations` map stays a `State`, `surfaceChrome` stays
 * a `derivedStateOf` projection, and the terminal pager's inputs stay stable.
 *
 * The screen does not own any business state — the panes list, the active tmux
 * client, and the per-pane terminal state holders all live in the view model.
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
    // Issue #487: per-host "port forwarding is active for THIS host" chip.
    sessionForwardingIndicatorViewModel:
        com.pocketshell.app.portfwd.SessionForwardingIndicatorViewModel = hiltViewModel(),
    // Issue #176: Settings → Conversation → "Show system notes" toggle.
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    // Issue #842: loads transcript-referenced images for inline display.
    conversationImageViewModel: ConversationImageViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
    // Issue #666: the (re)attached session no longer exists on the server.
    onSessionEnded: (sessionName: String) -> Unit = { onBack() },
    onOpenTmuxSession: (sessionName: String, startDirectory: String?) -> Unit = { _, _ -> },
    onReplaceTmuxSession: (sessionName: String) -> Unit = {},
    onOpenJobs: () -> Unit = {},
    onOpenUsage: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    // Issue #445 (epic #432 slice A): open the per-host port-forward panel.
    onOpenPortForwarding: () -> Unit = {},
    // Issue #448 (epic #432 slice C): forward the detected remote port.
    onOpenPortForwardingWithPort: (remotePort: Int, autoOpenLocalhostUrl: LocalhostUrl?) -> Unit = { _, _ -> },
    /** Route an assistant-requested navigation (issue #266). */
    onAssistantNavigate: (com.pocketshell.app.nav.AppDestination) -> Unit = {},
    /** Issue #497: open the in-app file viewer for a remote path. */
    onOpenFile: (path: String, cwd: String?) -> Unit = { _, _ -> },
    /** Issue #528: open the browsable file explorer. */
    onBrowseFiles: (startDir: String) -> Unit = {},
    // Issue #177 / #459: composer-draft persistence params — INERT here since
    // #459 collapsed the Conversation composer onto the shared unified composer
    // (which persists its own draft). MainActivity still passes them; they are
    // intentionally unused in this screen. Kept so the sole production call site
    // (MainActivity) is unchanged.
    @Suppress("UNUSED_PARAMETER") initialComposerDraft: String = "",
    @Suppress("UNUSED_PARAMETER") onInitialComposerDraftConsumed: () -> Unit = {},
    @Suppress("UNUSED_PARAMETER") onComposerDraftChanged: (String) -> Unit = {},
    // Issue #560: share-into-session staged attachment path(s).
    initialComposerAttachments: List<String> = emptyList(),
    onInitialComposerAttachmentsConsumed: () -> Unit = {},
    // Issue #763: a ready review prompt routed from the file viewer.
    initialComposerPrompt: String = "",
    onInitialComposerPromptConsumed: () -> Unit = {},
    // Issue #560: the shared composer VM, obtained at the screen level.
    promptComposerViewModel: PromptComposerViewModel = hiltViewModel(),
    suggestStartDirectories: (suspend (String) -> List<String>)? = null,
    connectTrigger: TmuxConnectTrigger = TmuxConnectTrigger.UserTap,
) {
    // Issue #1685: local UI-overlay visibility collapsed into ONE holder.
    val overlay = remember { TmuxSessionOverlayState() }
    // Issue #1685: the connection/surface state slab in its own @Composable frame.
    val conn = rememberTmuxSessionConnectionRuntime(
        viewModel = viewModel,
        hostId = hostId,
        hostName = hostName,
        host = host,
        port = port,
        user = user,
        keyPath = keyPath,
        sessionName = sessionName,
        tmuxSessionId = tmuxSessionId,
        sessionCreated = sessionCreated,
    )
    val pagerState = rememberPagerState(pageCount = { conn.unifiedPanes.size })
    // Issue #1685: the pager-relative pane selection in its own frame.
    val panesSel = rememberTmuxSessionPaneSelection(conn, viewModel, pagerState)
    // Epic #821 Slice 1: the active session's RECORDED `@ps_agent_kind`.
    val currentSessionRecordedKind by viewModel.currentSessionRecordedKind.collectAsState()
    // Issue #1085 (freeze F3 / R2): held as a `State<Map<...>>` (NOT a delegated
    // `by` read) so the screen root is never subscribed to the 60ms flush; the
    // high-frequency `events` list is read only inside `surfaceContent`.
    val agentConversationsState = viewModel.agentConversations.collectAsState()
    // Issue #1685: the agent-detection / presumed-agent signal cluster in its own
    // frame (holding the #462 sticky-agent map + its recorder effect).
    val agent = rememberTmuxSessionAgentSignals(
        viewModel = viewModel,
        conn = conn,
        panes = panesSel,
        agentConversationsState = agentConversationsState,
        currentSessionRecordedKind = currentSessionRecordedKind,
    )
    // Issue #145: gate the in-session Reconnect on whether the VM has a target.
    val canReconnect by viewModel.canReconnect.collectAsState()
    val sessionCardsState by viewModel.sessionCards.collectAsState()
    val sessionCards = remember(sessionCardsState, conn.activeSessionCardsTargetKey) {
        if (sessionCardsState.targetKey == conn.activeSessionCardsTargetKey) {
            sessionCardsState.feed.cards
        } else {
            emptyList()
        }
    }
    // #1531 (RC1): DOCKED launcher unsent badge.
    val outboundLauncherBadge = rememberOutboundLauncherBadge(promptComposerViewModel, conn.targetSessionId.value)
    // Issue #1158: the Terminal/Conversation tab state derived in its OWN frame.
    val tabState by rememberTmuxSessionTabState(
        viewModel = viewModel,
        surfaceConversationPaneId = panesSel.surfaceConversationPaneId,
        presumedAgent = agent.presumedAgent,
        currentSessionRecordedKind = currentSessionRecordedKind,
        agentConversationsState = agentConversationsState,
    )

    val context = LocalContext.current
    // Issue #131: same root-view handle as `SessionScreen`.
    val composeRootView = LocalView.current
    val density = LocalDensity.current
    val navBarBottomPx = WindowInsets.navigationBars.getBottom(density)
    // Issue #616/#615/#796/#887: deferred host-window IME read via the holder.
    val imeLayout = rememberTmuxImeLayoutState(navBarBottomPx)
    val isImeVisible = imeLayout.isImeVisible
    // Issue #184 Layer 2: drop the top chrome while the IME is up.
    val chromeCompressed = isImeVisible

    // Issue #796 (H4): count re-executions of the TmuxSessionScreen ROOT restart
    // group. Kept at the coordinator root so the counter still measures the real
    // screen root recompositions. Pure counter, no behaviour change.
    TmuxRenderRecompositionProbe.Record()

    // Issue #463 / #626: the current session's project path.
    val projectPath = (panesSel.currentPane ?: panesSel.currentUnifiedPane)?.cwd?.takeIf { it.isNotBlank() }

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

    // Issue #1207: an inline TUI-only-command notice, reset per send target.
    var tuiCommandNotice by remember(conn.targetSessionId.value) { mutableStateOf<String?>(null) }
    var showCardFeedSheet by remember(conn.activeSessionCardsTargetKey) { mutableStateOf(false) }

    // Issue #993/#1521: the single reconnect-with-feedback action.
    val reconnectWithFeedback: () -> Unit = {
        val started = viewModel.reconnect()
        Toast.makeText(
            context,
            if (started) "Reconnecting…" else "Nothing to reconnect to — reopen the session",
            Toast.LENGTH_SHORT,
        ).show()
    }

    // Issue #898: the single entry point for the rich "+ New session" sheet.
    val openNewSessionSheet: () -> Unit = {
        viewModel.fetchProfilesForActiveSession()
        sessionPickerViewModel.load(sessionPickerRequest)
        overlay.showNewSessionSheet = true
    }

    TmuxSessionScreenEffects(
        viewModel = viewModel,
        promptComposerViewModel = promptComposerViewModel,
        sessionPickerViewModel = sessionPickerViewModel,
        inlineDictationViewModel = inlineDictationViewModel,
        conn = conn,
        panesSel = panesSel,
        overlay = overlay,
        pagerState = pagerState,
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
        initialWindowIndex = initialWindowIndex,
        connectTrigger = connectTrigger,
        canReconnect = canReconnect,
        projectPath = projectPath,
        sessionPickerRequest = sessionPickerRequest,
        isImeVisible = isImeVisible,
        composeRootView = composeRootView,
        context = context,
        showCardFeedSheet = showCardFeedSheet,
        onShowCardFeedSheet = { showCardFeedSheet = it },
        initialComposerAttachments = initialComposerAttachments,
        onInitialComposerAttachmentsConsumed = onInitialComposerAttachmentsConsumed,
        initialComposerPrompt = initialComposerPrompt,
        onInitialComposerPromptConsumed = onInitialComposerPromptConsumed,
        onAssistantNavigate = onAssistantNavigate,
        onReplaceTmuxSession = onReplaceTmuxSession,
        onSessionEnded = onSessionEnded,
        onBack = onBack,
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(color = PocketShellColors.Background)
            .testTag(TMUX_SESSION_SCREEN_TAG),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize(),
        ) {
            TmuxSessionHeaderRegion(
                viewModel = viewModel,
                sessionPickerViewModel = sessionPickerViewModel,
                sessionForwardingIndicatorViewModel = sessionForwardingIndicatorViewModel,
                hostId = hostId,
                conn = conn,
                panesSel = panesSel,
                agent = agent,
                overlay = overlay,
                tabState = tabState,
                chromeCompressed = chromeCompressed,
                sessionName = sessionName,
                projectPath = projectPath,
                sessionPickerRequest = sessionPickerRequest,
                currentSessionRecordedKind = currentSessionRecordedKind,
                canReconnect = canReconnect,
                reconnectWithFeedback = reconnectWithFeedback,
                openNewSessionSheet = openNewSessionSheet,
                onBack = onBack,
                onOpenJobs = onOpenJobs,
                onOpenUsage = onOpenUsage,
                onOpenSettings = onOpenSettings,
                onOpenPortForwarding = onOpenPortForwarding,
                onBrowseFiles = onBrowseFiles,
                onReplaceTmuxSession = onReplaceTmuxSession,
            )

            TmuxSessionSurfaceRegion(
                viewModel = viewModel,
                promptComposerViewModel = promptComposerViewModel,
                sessionForwardingIndicatorViewModel = sessionForwardingIndicatorViewModel,
                settingsViewModel = settingsViewModel,
                conversationImageViewModel = conversationImageViewModel,
                conn = conn,
                panesSel = panesSel,
                agent = agent,
                overlay = overlay,
                pagerState = pagerState,
                tabState = tabState,
                agentConversationsState = agentConversationsState,
                sessionCards = sessionCards,
                outboundLauncherBadge = outboundLauncherBadge,
                canReconnect = canReconnect,
                isImeVisible = isImeVisible,
                composeRootView = composeRootView,
                context = context,
                hostId = hostId,
                host = host,
                port = port,
                user = user,
                keyPath = keyPath,
                passphrase = passphrase,
                sessionName = sessionName,
                tuiCommandNotice = tuiCommandNotice,
                onTuiCommandNoticeChange = { tuiCommandNotice = it },
                onShowCardFeedSheet = { showCardFeedSheet = it },
                reconnectWithFeedback = reconnectWithFeedback,
                onOpenFile = onOpenFile,
                onBrowseFiles = onBrowseFiles,
            )
        }

        TmuxSessionOverlaysRegion(
            viewModel = viewModel,
            sessionPickerViewModel = sessionPickerViewModel,
            panesSel = panesSel,
            overlay = overlay,
            context = context,
            hostName = hostName,
            host = host,
            user = user,
            sessionName = sessionName,
            sessionPickerRequest = sessionPickerRequest,
            suggestStartDirectories = suggestStartDirectories,
            currentSessionRecordedKind = currentSessionRecordedKind,
            openNewSessionSheet = openNewSessionSheet,
            onOpenFile = onOpenFile,
            onOpenTmuxSession = onOpenTmuxSession,
            onReplaceTmuxSession = onReplaceTmuxSession,
            onBack = onBack,
            onOpenPortForwardingWithPort = onOpenPortForwardingWithPort,
        )
    }

    TmuxSessionSheetsRegion(
        viewModel = viewModel,
        promptComposerViewModel = promptComposerViewModel,
        conn = conn,
        panesSel = panesSel,
        agent = agent,
        overlay = overlay,
        sessionCards = sessionCards,
        hostId = hostId,
        onTuiCommandNoticeChange = { tuiCommandNotice = it },
        showCardFeedSheet = showCardFeedSheet,
        onShowCardFeedSheet = { showCardFeedSheet = it },
    )
}

/**
 * Issue #1685: all of [TmuxSessionScreen]'s top-level effects in their own
 * @Composable frame. Every block is verbatim; this method just unpacks the
 * holders into the same local names the inline code used.
 */
@Composable
private fun TmuxSessionScreenEffects(
    viewModel: TmuxSessionViewModel,
    promptComposerViewModel: PromptComposerViewModel,
    sessionPickerViewModel: HostTmuxSessionPickerViewModel,
    inlineDictationViewModel: InlineDictationViewModel,
    conn: TmuxSessionConnectionRuntime,
    panesSel: TmuxSessionPaneSelection,
    overlay: TmuxSessionOverlayState,
    pagerState: PagerState,
    hostId: Long,
    hostName: String,
    host: String,
    port: Int,
    user: String,
    keyPath: String,
    passphrase: CharArray?,
    sessionName: String,
    startDirectory: String?,
    tmuxSessionId: String?,
    sessionCreated: Long?,
    initialWindowIndex: Int?,
    connectTrigger: TmuxConnectTrigger,
    canReconnect: Boolean,
    projectPath: String?,
    sessionPickerRequest: HostTmuxSessionPickerRequest,
    isImeVisible: Boolean,
    composeRootView: android.view.View,
    context: android.content.Context,
    showCardFeedSheet: Boolean,
    onShowCardFeedSheet: (Boolean) -> Unit,
    initialComposerAttachments: List<String>,
    onInitialComposerAttachmentsConsumed: () -> Unit,
    initialComposerPrompt: String,
    onInitialComposerPromptConsumed: () -> Unit,
    onAssistantNavigate: (com.pocketshell.app.nav.AppDestination) -> Unit,
    onReplaceTmuxSession: (sessionName: String) -> Unit,
    onSessionEnded: (sessionName: String) -> Unit,
    onBack: () -> Unit,
) {
    val targetSessionId = conn.targetSessionId
    val sessionLive = conn.sessionLive
    val status = conn.status
    val unifiedPanes = conn.unifiedPanes
    val activeSessionCardsTargetKey = conn.activeSessionCardsTargetKey
    val surfacePane = panesSel.surfacePane

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

    val outboundQueueAutoFlushController = remember(targetSessionId.value, promptComposerViewModel) {
        // #1635-A (D4): `boundTo` BINDS the retry budget to this screen's delivery
        // window, so a send that failed with the window closed burns zero attempts. It
        // reads the budget off the composer itself — the screen never names a tracker —
        // so the wiring can be neither dropped nor misdirected.
        OutboundQueueAutoFlushController.boundTo(promptComposerViewModel)
    }
    LaunchedEffect(targetSessionId.value) {
        promptComposerViewModel.onComposerTargetChanged(targetSessionId.value)
    }
    DisposableEffect(promptComposerViewModel, viewModel) {
        promptComposerViewModel.setOutboundAttachmentSidecarUploader { refs ->
            viewModel.uploadQueuedAttachmentSidecars(refs)
        }
        // Issue #1686: wire the WIRE-oracle probe so the failure taxonomy + drain gate
        // read the transport's own truth instead of the ConnectionStatus enum.
        promptComposerViewModel.setTransportWritableProbe { viewModel.isSendTransportWritable() }
        onDispose {
            promptComposerViewModel.setOutboundAttachmentSidecarUploader(null)
            promptComposerViewModel.setTransportWritableProbe { false }
        }
    }
    LaunchedEffect(sessionLive, targetSessionId.value) {
        promptComposerViewModel.setConnectionDegraded(!sessionLive)
        outboundQueueAutoFlushController.onConnectionWindowChanged(
            sessionLive = sessionLive,
            targetSessionId = targetSessionId.value,
            connectionStatusLabel = connectionStatusDiagnosticLabel(conn.rawStatus), // #1682
        ) {
            promptComposerViewModel.requeueStaleOutboundInFlight()
            // Issue #1686: on the connected edge the wire (re)became available — un-park
            // the auto-parked backlog so it self-heals instead of stranding Failed.
            promptComposerViewModel.unparkTransportFailedRows()
        }
    }
    TmuxOutboundQueueAutoFlushEffect(
        sessionLive = sessionLive,
        targetSessionKey = targetSessionId.value,
        promptComposerViewModel = promptComposerViewModel,
        controller = outboundQueueAutoFlushController,
        transportWritable = { viewModel.isSendTransportWritable() }, // #1686
    )
    LaunchedEffect(sessionLive, activeSessionCardsTargetKey) {
        if (sessionLive) viewModel.refreshActiveSessionCards()
    }
    LaunchedEffect(status, canReconnect) {
        recordTmuxReconnectUiStateRendered(
            status = status,
            hostId = hostId,
            canReconnect = canReconnect,
        )
    }
    // Epic #821 Slice 1: read the active session's recorded `@ps_agent_kind`.
    LaunchedEffect(sessionName, status) {
        if (status is ConnectionStatus.Connected) {
            viewModel.refreshCurrentSessionRecordedKind()
        }
    }

    // Issue #560: seed staged share attachments into the composer.
    LaunchedEffect(initialComposerAttachments) {
        if (initialComposerAttachments.isNotEmpty()) {
            initialComposerAttachments.forEach { promptComposerViewModel.seedAttachment(it) }
            overlay.micSheetAutoStartRecording = false
            overlay.showMicSheet = true
            onInitialComposerAttachmentsConsumed()
        }
    }
    // Issue #763: seed a routed review prompt into the composer draft.
    LaunchedEffect(initialComposerPrompt) {
        if (initialComposerPrompt.isNotBlank()) {
            promptComposerViewModel.seedDraftPrompt(initialComposerPrompt)
            overlay.micSheetAutoStartRecording = false
            overlay.showMicSheet = true
            onInitialComposerPromptConsumed()
        }
    }

    // Issue #167: intercept system-back so the user returns to the host list.
    TmuxSessionBackHandler(
        dialogOpen = overlay.dialogMode != null,
        sessionDrawerOpen = overlay.showSessionSwitcher || overlay.showSessionDrawer,
        micSheetOpen = overlay.showMicSheet,
        snippetPickerOpen = overlay.showSnippetPicker,
        cardFeedSheetOpen = showCardFeedSheet,
        onDismissDialog = { overlay.dialogMode = null },
        onDismissSessionDrawer = {
            overlay.showSessionSwitcher = false
            overlay.showSessionDrawer = false
            sessionPickerViewModel.dismiss()
        },
        onDismissMicSheet = {
            overlay.showMicSheet = false
            overlay.micSheetAutoStartRecording = false
        },
        onDismissSnippetPicker = { overlay.showSnippetPicker = false },
        onDismissCardFeedSheet = { onShowCardFeedSheet(false) },
        onBack = onBack,
    )

    // Issue #184 Layer 1: snap the active pane to the bottom when the IME shows.
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

    // Issue #989: surface Redraw feedback as a Toast.
    LaunchedEffect(viewModel) {
        viewModel.redrawFeedback.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    // Route inline-dictation transcripts into the currently focused pane.
    val dictationUiState = inlineDictationViewModel.uiState.collectAsState()
    val dictationMode by remember(dictationUiState) {
        derivedStateOf { dictationUiState.value.mode }
    }
    val focusedPaneId = surfacePane?.paneId
    LaunchedEffect(inlineDictationViewModel, dictationMode, focusedPaneId) {
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

    // Issue #463: keep the project switcher's sibling list fresh.
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

    // Issue #782: scroll the warm unified pager to an externally-created window.
    var consumedInitialWindowTarget by remember(sessionName, initialWindowIndex) { mutableStateOf(false) }
    LaunchedEffect(unifiedPanes, initialWindowIndex, consumedInitialWindowTarget) {
        val requestedIndex = initialWindowIndex ?: return@LaunchedEffect
        if (consumedInitialWindowTarget) return@LaunchedEffect
        val targetPane = unifiedPanes.firstOrNull { it.windowIndex == requestedIndex } ?: return@LaunchedEffect
        consumedInitialWindowTarget = true
        val page = unifiedPanes.indexOfFirst { it.windowId == targetPane.windowId }
        if (page >= 0) {
            pagerState.scrollToPage(page)
        }
    }

    // Issue #626: warm-switch when the unified pager settles on another session.
    LaunchedEffect(Unit) {
        viewModel.sessionSwitchRequest.collect { targetSessionName ->
            onReplaceTmuxSession(targetSessionName)
        }
    }

    // Issue #666: cold-restore found the persisted last session gone.
    LaunchedEffect(Unit) {
        viewModel.sessionEnded.collect { endedSessionName ->
            onSessionEnded(endedSessionName)
        }
    }

    // Issue #976: surface a refused new-session LAUNCH.
    LaunchedEffect(Unit) {
        viewModel.sessionCreateError.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    TmuxUnifiedPagerSettleEffects(
        pagerState = pagerState,
        unifiedPanes = unifiedPanes,
        sessionName = sessionName,
        terminalHeld = conn.terminalHeld,
        viewModel = viewModel,
    )
}

/**
 * Issue #1685: the header chrome region in its own frame. The kebab
 * [AnchoredTmuxMoreMenu] stays a LOCAL composable (as before) so both the full
 * and the compact breadcrumb host the identical menu.
 */
@Composable
private fun TmuxSessionHeaderRegion(
    viewModel: TmuxSessionViewModel,
    sessionPickerViewModel: HostTmuxSessionPickerViewModel,
    sessionForwardingIndicatorViewModel: com.pocketshell.app.portfwd.SessionForwardingIndicatorViewModel,
    hostId: Long,
    conn: TmuxSessionConnectionRuntime,
    panesSel: TmuxSessionPaneSelection,
    agent: TmuxSessionAgentSignals,
    overlay: TmuxSessionOverlayState,
    tabState: TmuxSessionTabState,
    chromeCompressed: Boolean,
    sessionName: String,
    projectPath: String?,
    sessionPickerRequest: HostTmuxSessionPickerRequest,
    currentSessionRecordedKind: SessionAgentKind?,
    canReconnect: Boolean,
    reconnectWithFeedback: () -> Unit,
    openNewSessionSheet: () -> Unit,
    onBack: () -> Unit,
    onOpenJobs: () -> Unit,
    onOpenUsage: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPortForwarding: () -> Unit,
    onBrowseFiles: (startDir: String) -> Unit,
    onReplaceTmuxSession: (sessionName: String) -> Unit,
) {
    val terminalHeld = conn.terminalHeld
    val surfaceState = conn.surfaceState
    val surfacePane = panesSel.surfacePane
    val currentPane = panesSel.currentPane
    val currentUnifiedPaneSessionName = panesSel.currentUnifiedPaneSessionName
    val currentDetection = agent.currentDetection

    val haptics = LocalHapticFeedback.current
    val verticalSwipeThresholdPx = with(LocalDensity.current) { VerticalSwipeThreshold.toPx() }
    // Issue #487: active-forwarding state for the host this session belongs to.
    val sessionForwardingState by remember(hostId) {
        sessionForwardingIndicatorViewModel.stateFor(hostId)
    }.collectAsState()
    // Issue #463: the in-session project switcher's sibling list.
    val projectSwitcherState by sessionPickerViewModel.projectSwitcher.collectAsState()

    // Issue #686: header project crumb keyed to the SINGLE target session id.
    val projectLabel = remember(
        projectPath,
        terminalHeld,
        sessionName,
        currentUnifiedPaneSessionName,
    ) {
        keyedProjectCrumbLabel(
            projectPath = projectPath,
            switchHidesTerminal = terminalHeld,
            targetSessionName = sessionName,
            visiblePaneSessionName = currentUnifiedPaneSessionName,
        )
    }

    fun openTextDialog(
        mode: TmuxDialogMode,
        initialText: String = "",
    ) {
        overlay.dialogMode = mode
        overlay.dialogText = initialText
    }

    val onTabSelected: (Int) -> Unit = { index ->
        if (index == 1) {
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

    @Composable
    fun AnchoredTmuxMoreMenu() {
        TmuxMoreMenu(
            expanded = overlay.moreExpanded,
            forwardingState = sessionForwardingState,
            onDismiss = { overlay.moreExpanded = false },
            onCreateSession = {
                overlay.moreExpanded = false
                openNewSessionSheet()
            },
            onRenameSession = {
                overlay.moreExpanded = false
                openTextDialog(TmuxDialogMode.RenameSession, sessionName)
            },
            onKillSession = {
                overlay.moreExpanded = false
                overlay.dialogMode = TmuxDialogMode.StopSession
            },
            onChangeKind = {
                overlay.moreExpanded = false
                viewModel.refreshCurrentSessionRecordedKind()
                overlay.showKindPicker = true
            },
            changeKindIsUnknown = currentSessionRecordedKind == null,
            onSwitchSession = {
                overlay.moreExpanded = false
                overlay.showSessionSwitcher = true
            },
            onOpenJobs = {
                overlay.moreExpanded = false
                onOpenJobs()
            },
            onOpenUsage = {
                overlay.moreExpanded = false
                onOpenUsage()
            },
            onOpenSettings = {
                overlay.moreExpanded = false
                onOpenSettings()
            },
            onOpenPortForwarding = {
                overlay.moreExpanded = false
                onOpenPortForwarding()
            },
            onOpenFile = {
                overlay.moreExpanded = false
                overlay.openFilePath = ""
                overlay.showOpenFileDialog = true
            },
            onBrowseFiles = {
                overlay.moreExpanded = false
                val paneCwd = currentPane?.cwd?.takeIf { it.isNotBlank() }
                onBrowseFiles(paneCwd ?: "~")
            },
            onDetach = {
                overlay.moreExpanded = false
                viewModel.detachAndExit()
                onBack()
            },
            onRedraw = {
                overlay.moreExpanded = false
                viewModel.redrawActivePane()
            },
            onReconnect = {
                overlay.moreExpanded = false
                reconnectWithFeedback()
            },
            reconnectEnabled = reconnectKebabEnabled(canReconnect, surfaceState),
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .verticalSwipeInput(
                thresholdPx = verticalSwipeThresholdPx,
                onBoundary = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                onSwipeDown = { overlay.showSessionSwitcher = true },
            ),
    ) {
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
                        onMore = { overlay.moreExpanded = true },
                        moreMenu = { AnchoredTmuxMoreMenu() },
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
                        // Issue #1487: surface the already-collected per-host
                        // forwarding state as the always-visible top-chrome pill.
                        forwardingState = sessionForwardingState,
                        modifier = Modifier.testTag(TMUX_FULL_BREADCRUMB_TAG),
                    )
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
                    onMore = { overlay.moreExpanded = true },
                    moreMenu = { AnchoredTmuxMoreMenu() },
                    connectionStatus = surfaceState.toUiStatus(),
                    modifier = Modifier.testTag(TMUX_COMPACT_BREADCRUMB_TAG),
                )
            }
        }
    }
}

/**
 * Issue #1685: the terminal/conversation surface, banners, assistant strip,
 * quick-reply band, bottom controls and page indicator in their own frame. Every
 * block is verbatim; the region unpacks the holders into the same local names.
 */
@Composable
private fun ColumnScope.TmuxSessionSurfaceRegion(
    viewModel: TmuxSessionViewModel,
    promptComposerViewModel: PromptComposerViewModel,
    sessionForwardingIndicatorViewModel: com.pocketshell.app.portfwd.SessionForwardingIndicatorViewModel,
    settingsViewModel: SettingsViewModel,
    conversationImageViewModel: ConversationImageViewModel,
    conn: TmuxSessionConnectionRuntime,
    panesSel: TmuxSessionPaneSelection,
    agent: TmuxSessionAgentSignals,
    overlay: TmuxSessionOverlayState,
    pagerState: PagerState,
    tabState: TmuxSessionTabState,
    agentConversationsState: androidx.compose.runtime.State<Map<String, com.pocketshell.app.session.AgentConversationUiState>>,
    sessionCards: List<com.pocketshell.app.cards.SessionCardsRemoteSource.SessionCard>,
    outboundLauncherBadge: com.pocketshell.app.composer.OutboundLauncherBadge?,
    canReconnect: Boolean,
    isImeVisible: Boolean,
    composeRootView: android.view.View,
    context: android.content.Context,
    hostId: Long,
    host: String,
    port: Int,
    user: String,
    keyPath: String,
    passphrase: CharArray?,
    sessionName: String,
    tuiCommandNotice: String?,
    onTuiCommandNoticeChange: (String?) -> Unit,
    onShowCardFeedSheet: (Boolean) -> Unit,
    reconnectWithFeedback: () -> Unit,
    onOpenFile: (path: String, cwd: String?) -> Unit,
    onBrowseFiles: (startDir: String) -> Unit,
) {
    val terminalHeld = conn.terminalHeld
    val unifiedPanes = conn.unifiedPanes
    val surfaceState = conn.surfaceState
    val surfaceOwnsPrimary = conn.surfaceOwnsPrimary
    val surfaceCalmFailure = conn.surfaceCalmFailure
    val surfaceCenteredLoader = conn.surfaceCenteredLoader
    val sessionLive = conn.sessionLive
    val status = conn.status
    val surfacePane = panesSel.surfacePane
    val currentPane = panesSel.currentPane
    val surfaceConversationPaneId = panesSel.surfaceConversationPaneId
    val currentDetection = agent.currentDetection
    val currentSelectedTab = agent.currentSelectedTab
    val presumedAgent = agent.presumedAgent
    val paletteAgent = agent.paletteAgent
    val engineCommandSet = agent.engineCommandSet
    val quickReplyInputEligible = agent.quickReplyInputEligible
    val surfaceChrome = agent.surfaceChrome

    val appSettings by settingsViewModel.state.collectAsState()
    val assistantState by viewModel.assistantState.collectAsState()
    val sessionCardFeedChipState = remember(sessionCards) {
        cardFeedChipState(sessionCards)
    }

    // Issue #796 (H3): STABLE callback instances for the hoisted, skippable
    // [TmuxTerminalPager], so the terminal subtree stays skipped across overlay
    // toggles.
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
                overlay.showMicSheet = true
            }
        }

    // Issue #750 (4th occurrence): the top connecting / reconnecting banner.
    TmuxTopConnectingBanner(
        surfaceState = surfaceState,
        surfaceOwnsPrimary = surfaceOwnsPrimary,
        sessionName = sessionName,
        onCancelConnect = { viewModel.cancelConnect() },
        onRetryNow = { viewModel.reconnect() },
    )
    // Issue #145/#1326/#1362: the settled-failure band.
    SessionFailureBand(
        surfaceState = surfaceState,
        user = user,
        host = host,
        port = port,
        onReconnect = reconnectWithFeedback,
    )
    // Issue #797/#1057/#1085: conversation-surface routing from STABLE inputs.
    val conversationSurface = tmuxSessionConversationSurface(
        showsConversationTab = tabState.showsConversationTab,
        isActivePane = currentPane != null,
        hasSurfacePane = surfacePane != null,
        selectedTab = currentSelectedTab,
        hasDetection = currentDetection != null,
        hasEvents = surfaceChrome.hasEvents,
    )
    val showConversation = surfaceChrome.exists &&
        conversationSurface == TmuxConversationSurface.Transcript
    val showConversationPlaceholder =
        conversationSurface == TmuxConversationSurface.Placeholder
    // Issue #605: hold the terminal AndroidView re-attach one frame on the
    // Conversation → Terminal edge.
    val deferTerminalAttachForSwap by rememberConversationToTerminalSwapLatch(
        showConversation = showConversation,
    )
    // Issue #488/#557/#796: shared URL-tap routing, `remember`ed so the SAME
    // lambda is fed to [TmuxTerminalPager] across recompositions.
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
                    overlay.pendingLocalhostForward = local
                }
            }
        }
    }
    // Issue #823: pull-to-reconnect, scoped to non-Connected states only.
    val pullToReconnectActive = !sessionLive && canReconnect
    val surfaceContent: @Composable () -> Unit = {
        val keepTerminalMounted = !terminalHeld &&
            !deferTerminalAttachForSwap &&
            unifiedPanes.isNotEmpty()
        if (keepTerminalMounted) {
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
        val visibleConversation =
            surfaceConversationPaneId?.let { agentConversationsState.value[it] }
        if (surfaceCalmFailure) {
            RevealFailurePlaceholder()
        } else if (terminalHeld) {
            SessionSurfaceMaskPlaceholder()
        } else if (deferTerminalAttachForSwap) {
            SessionSurfaceMaskPlaceholder()
        } else if (showConversation &&
            visibleConversation != null &&
            visibleConversation.events.isEmpty() &&
            visibleConversation.loadState == ConversationLoadState.Loading
        ) {
            ConversationDetectingPlaceholder(
                loadState = ConversationLoadState.Loading,
            )
        } else if (showConversation &&
            visibleConversation != null &&
            visibleConversation.events.isEmpty() &&
            visibleConversation.loadState == ConversationLoadState.Failed
        ) {
            val paneIdForSend = currentPane!!.paneId
            ConversationDetectingPlaceholder(
                loadState = ConversationLoadState.Failed,
                onRetry = { viewModel.retryAgentConversationLoad(paneIdForSend) },
            )
        } else if (showConversation && visibleConversation != null) {
            val paneIdForSend = currentPane!!.paneId
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
                    hasMoreOlderEvents = visibleConversation.hasMoreOlderEvents,
                    isPagingOlder = visibleConversation.isPagingOlder,
                    onLoadOlderEvents = {
                        viewModel.loadOlderAgentConversationEvents(paneIdForSend)
                    },
                    onRetryAgentStream = {
                        viewModel.retryAgentConversationStreamForPane(paneIdForSend)
                    },
                    onRetryFailedSend = { id ->
                        viewModel.retryFailedAgentSend(paneIdForSend, id)
                    },
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
                    onTuiCommandNoticeChange(null)
                },
            )
        } else if (unifiedPanes.isEmpty()) {
            SessionSurfaceMaskPlaceholder()
        }

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
                        onTuiCommandNoticeChange(null)
                    },
                    onDismiss = { onTuiCommandNoticeChange(null) },
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
            isReconnecting = status is ConnectionStatus.Reconnecting ||
                status is ConnectionStatus.Connecting ||
                status is ConnectionStatus.Switching,
            onReconnect = { viewModel.reconnect() },
            surfaceShowsCenteredLoader = surfaceCenteredLoader,
            showReconnectButton = surfaceReconnectButtonVisible(surfaceState),
            content = surfaceContent,
        )
        // Issue #1684 / #750 recurrence 5: the primary loading indicator has
        // exactly ONE fixed screen-level mount, outside the reconnect wrapper's
        // vertically-unbounded scroll geometry. Connecting, Attaching and
        // Reattaching therefore keep identical bounds instead of moving from a
        // short pager page near the toolbar to the full-surface center. The
        // content branches above and TmuxTerminalPager paint neutral masks only.
        if (surfaceCenteredLoader) {
            if (terminalHeld) {
                SwitchingLoadingPlaceholder()
            } else {
                EmptyPanesPlaceholder()
            }
        }
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
            enabled = quickReplyInputEligible && !overlay.showMicSheet && !showConversation,
            onReply = { reply ->
                viewModel.writeInputToPane(
                    pane.paneId,
                    reply.payload.toByteArray(Charsets.UTF_8),
                )
            },
        )
    }

    // Issue #810 (hard-cut, D22): the composer launcher is ALWAYS present.
    run {
        val pane = surfacePane
        val isAgentPane = tmuxSessionIsAgentPane(
            hasLiveDetection = currentDetection != null,
            presumedAgent = presumedAgent,
        )
        val bottomControlsModifier = if (isImeVisible) {
            Modifier
        } else {
            Modifier.navigationBarsPadding()
        }
        val controlsInputEnabled = sessionLive && pane != null
        TmuxSessionBottomControlsCallSite(
            isImeVisible = isImeVisible,
            showConversationTranscript = showConversation,
            showConversationDetectingPlaceholder = showConversationPlaceholder,
            sessionLive = controlsInputEnabled,
            terminalHeld = terminalHeld,
            isAgentPane = isAgentPane,
            onChipTap = { chip ->
                pane?.let {
                    viewModel.writeInputToPane(
                        it.paneId,
                        (chip + "\r").toByteArray(Charsets.UTF_8),
                    )
                }
            },
            onDictateTap = {
                overlay.micSheetAutoStartRecording = false
                overlay.showMicSheet = true
            },
            onDictateHoldSwipeUp = {
                overlay.micSheetAutoStartRecording = true
                overlay.showMicSheet = true
            },
            onEnterTap = pane?.let { p -> { viewModel.onKeyBarKey(p.paneId, "Enter") } },
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
            onAddSnippetTap = if (
                pane != null &&
                tmuxSessionShowsSnippetChip(
                    hasHost = hostId != 0L,
                    hasLiveDetection = currentDetection != null,
                    hasStickyAgent = paletteAgent != null,
                )
            ) {
                { overlay.showSnippetPicker = true }
            } else null,
            onShowHotkeysTap = pane?.let { p ->
                {
                    DiagnosticEvents.record(
                        "action",
                        "hotkey_panel_show",
                        "mode" to "tmux",
                        "paneId" to p.paneId,
                    )
                    overlay.showHotkeysPanel = true
                }
            },
            leadingChipContent = if (controlsInputEnabled) sessionCardFeedChipState?.let { state ->
                {
                    SessionCardFeedChip(
                        state = state,
                        onClick = {
                            viewModel.refreshActiveSessionCards()
                            onShowCardFeedSheet(true)
                        },
                    )
                }
            } else null,
            unsentCount = outboundLauncherBadge?.count ?: 0, // #1531 (RC1)
            unsentHasFailure = outboundLauncherBadge?.hasFailure ?: false,
            modifier = bottomControlsModifier,
        )
    }

    // Pane pager dot indicator (only when >1 pane).
    if (unifiedPanes.size > 1) {
        PageIndicator(
            pageCount = unifiedPanes.size,
            currentPage = pagerState.currentPage,
        )
    }
}

/**
 * Issue #1685: the modal dialog + auxiliary modals + session switcher / drawer +
 * detected-port overlay + their picker-load effects, in their own BoxScope frame.
 */
@Composable
private fun BoxScope.TmuxSessionOverlaysRegion(
    viewModel: TmuxSessionViewModel,
    sessionPickerViewModel: HostTmuxSessionPickerViewModel,
    panesSel: TmuxSessionPaneSelection,
    overlay: TmuxSessionOverlayState,
    context: android.content.Context,
    hostName: String,
    host: String,
    user: String,
    sessionName: String,
    sessionPickerRequest: HostTmuxSessionPickerRequest,
    suggestStartDirectories: (suspend (String) -> List<String>)?,
    currentSessionRecordedKind: SessionAgentKind?,
    openNewSessionSheet: () -> Unit,
    onOpenFile: (path: String, cwd: String?) -> Unit,
    onOpenTmuxSession: (sessionName: String, startDirectory: String?) -> Unit,
    onReplaceTmuxSession: (sessionName: String) -> Unit,
    onBack: () -> Unit,
    onOpenPortForwardingWithPort: (remotePort: Int, autoOpenLocalhostUrl: LocalhostUrl?) -> Unit,
) {
    val currentPane = panesSel.currentPane
    val sessionPickerState by sessionPickerViewModel.state.collectAsState()
    val newSessionClaudeProfiles by viewModel.claudeProfiles.collectAsState()
    val newSessionCodexProfiles by viewModel.codexProfiles.collectAsState()
    val currentSessionRecordedProfile by viewModel.currentSessionRecordedProfile.collectAsState()
    val detectedPort by viewModel.detectedPort.collectAsState()

    overlay.dialogMode?.let { mode ->
        TmuxLifecycleDialog(
            mode = mode,
            sessionName = sessionName,
            text = overlay.dialogText,
            onTextChange = { overlay.dialogText = it },
            onDismiss = { overlay.dialogMode = null },
            onConfirm = {
                when (mode) {
                    TmuxDialogMode.RenameSession -> {
                        val name = overlay.dialogText.trim()
                        viewModel.renameCurrentSession(name)
                        if (name.isNotEmpty()) onReplaceTmuxSession(name)
                    }
                    TmuxDialogMode.StopSession -> {
                        viewModel.killCurrentSession(currentPane?.windowIndex)
                        onBack()
                    }
                }
                overlay.dialogMode = null
            },
        )
    }

    TmuxSessionAuxiliaryModals(
        showNewSessionSheet = overlay.showNewSessionSheet,
        currentPaneCwd = currentPane?.cwd,
        suggestStartDirectories = suggestStartDirectories,
        claudeProfiles = newSessionClaudeProfiles,
        codexProfiles = newSessionCodexProfiles,
        deriveDefaultName = { dir ->
            defaultSessionBaseName(dir, conventionalRemoteHome(user))
        },
        onDismissNewSessionSheet = { overlay.showNewSessionSheet = false },
        onCreateNewSession = { choice ->
            overlay.showNewSessionSheet = false
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
        showKindPicker = overlay.showKindPicker,
        sessionName = sessionName,
        currentSessionRecordedKind = currentSessionRecordedKind,
        currentSessionRecordedProfile = currentSessionRecordedProfile,
        onDismissKindPicker = { overlay.showKindPicker = false },
        onPickKind = { kind ->
            viewModel.setCurrentSessionKind(kind)
            overlay.showKindPicker = false
        },
        showOpenFileDialog = overlay.showOpenFileDialog,
        openFilePath = overlay.openFilePath,
        onOpenFilePathChange = { overlay.openFilePath = it },
        onDismissOpenFileDialog = { overlay.showOpenFileDialog = false },
        onOpenFileConfirmed = onOpenFile,
        pendingLocalhostForward = overlay.pendingLocalhostForward,
        localhostTargetHost = hostName.ifBlank { host },
        onDismissLocalhostForward = { overlay.pendingLocalhostForward = null },
        onConfirmLocalhostForward = { pending ->
            val target = acceptedLocalhostForwardNavigation(pending)
            overlay.pendingLocalhostForward = null
            onOpenPortForwardingWithPort(target.remotePort, target.autoOpenLocalhostUrl)
        },
    )

    SessionSwitcherOverlay(
        visible = overlay.showSessionSwitcher,
        state = sessionPickerState,
        hostName = hostName.ifBlank { host },
        currentSessionName = sessionName,
        onRefresh = { sessionPickerViewModel.load(sessionPickerRequest) },
        onDismiss = {
            overlay.showSessionSwitcher = false
            sessionPickerViewModel.dismiss()
        },
        onSelectSession = { selectedSessionName ->
            handleTmuxSessionSelection(
                currentSessionName = sessionName,
                selectedSessionName = selectedSessionName,
                onDismiss = {
                    overlay.showSessionSwitcher = false
                    sessionPickerViewModel.dismiss()
                },
                onReplace = onReplaceTmuxSession,
            )
        },
        onCreate = {
            overlay.showSessionSwitcher = false
            openNewSessionSheet()
        },
    )

    TmuxSessionDrawer(
        visible = overlay.showSessionDrawer,
        state = sessionPickerState,
        hostName = hostName.ifBlank { host },
        currentSessionName = sessionName,
        onRefresh = { sessionPickerViewModel.load(sessionPickerRequest) },
        onDismiss = {
            overlay.showSessionDrawer = false
            sessionPickerViewModel.dismiss()
        },
        onAttach = { selectedSessionName ->
            handleTmuxSessionSelection(
                currentSessionName = sessionName,
                selectedSessionName = selectedSessionName,
                onDismiss = {
                    overlay.showSessionDrawer = false
                    sessionPickerViewModel.dismiss()
                },
                onReplace = onReplaceTmuxSession,
            )
        },
        onCreate = {
            overlay.showSessionDrawer = false
            openNewSessionSheet()
        },
    )

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

    LaunchedEffect(overlay.showSessionSwitcher, overlay.showSessionDrawer, sessionPickerRequest) {
        if (overlay.showSessionSwitcher || overlay.showSessionDrawer) {
            sessionPickerViewModel.load(sessionPickerRequest)
        }
    }
    LaunchedEffect(overlay.showSessionSwitcher, overlay.showSessionDrawer, sessionPickerState) {
        val pickerVisible = overlay.showSessionSwitcher || overlay.showSessionDrawer
        val ready = sessionPickerState as? HostTmuxSessionPickerState.Ready
        if (pickerVisible && ready != null) {
            viewModel.prewarmLikelySwitchTargets(ready.rows.map { it.name })
        } else {
            viewModel.cancelTmuxSessionPrewarm()
        }
    }
}

/**
 * Issue #1685: the shared unified composer / snippet / card-feed / hotkeys sheets
 * plus the send handler + send dispatcher, in their own frame.
 */
@Composable
private fun TmuxSessionSheetsRegion(
    viewModel: TmuxSessionViewModel,
    promptComposerViewModel: PromptComposerViewModel,
    conn: TmuxSessionConnectionRuntime,
    panesSel: TmuxSessionPaneSelection,
    agent: TmuxSessionAgentSignals,
    overlay: TmuxSessionOverlayState,
    sessionCards: List<com.pocketshell.app.cards.SessionCardsRemoteSource.SessionCard>,
    hostId: Long,
    onTuiCommandNoticeChange: (String?) -> Unit,
    showCardFeedSheet: Boolean,
    onShowCardFeedSheet: (Boolean) -> Unit,
) {
    val targetSessionId = conn.targetSessionId
    val sessionLive = conn.sessionLive
    val surfacePane = panesSel.surfacePane
    val currentDetection = agent.currentDetection
    val currentSelectedTab = agent.currentSelectedTab
    val paletteAgent = agent.paletteAgent
    val presumedAgentKind = agent.presumedAgentKind

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

    val composerSendHandler: suspend (PromptComposerViewModel.SendRequest) -> Boolean = { request ->
        val target = request.sendTarget
        if (target.sessionKey.isNotBlank() && target.sessionKey != targetSessionId.value) {
            false
        } else {
            val paneId = target.paneId.ifBlank { surfacePane?.paneId.orEmpty() }
            val sent = if (paneId.isBlank()) {
                false
            } else when (target.route) {
                OutboundRoute.AgentConversation ->
                    tmuxAgentConversationSendResult(
                        request.text,
                        target.agentKind,
                        { t, k -> viewModel.sendAgentPayloadToPaneResult(paneId, t, k).isSuccess },
                        { t -> viewModel.sendToAgentPaneResult(paneId, t).isSuccess },
                    ) { onTuiCommandNoticeChange(it) }
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
            sent
        }
    }
    PromptComposerSendDispatcher(
        viewModel = promptComposerViewModel,
        onSend = composerSendHandler,
        onDelivered = {
            overlay.showMicSheet = false
            overlay.micSheetAutoStartRecording = false
        },
    )

    val ctrlModifierState by viewModel.ctrlModifier.collectAsState()
    TmuxSessionSheets(
        showMicSheet = overlay.showMicSheet,
        promptComposerViewModel = promptComposerViewModel,
        composerAgentKind = paletteAgent ?: presumedAgentKind,
        composerTargetKey = targetSessionId.value,
        micSheetAutoStartRecording = overlay.micSheetAutoStartRecording,
        onDismissMicSheet = {
            overlay.showMicSheet = false
            overlay.micSheetAutoStartRecording = false
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
        showSnippetPicker = overlay.showSnippetPicker,
        snippetsHostId = hostId,
        snippetKindFilter = if (currentDetection != null) {
            SnippetKind.Prompt
        } else {
            SnippetKind.Command
        },
        onDismissSnippetPicker = { overlay.showSnippetPicker = false },
        onSnippetSend = { snippet, withEnter ->
            if (sessionLive) {
                surfacePane?.let { pane ->
                    viewModel.writeInputToPane(
                        pane.paneId,
                        snippetDispatchText(snippet, withEnter).toByteArray(Charsets.UTF_8),
                    )
                }
                overlay.showSnippetPicker = false
            }
        },
        showCardFeedSheet = showCardFeedSheet,
        sessionCards = sessionCards,
        sessionCardInteractions = sessionCardInteractions,
        onDismissCardFeedSheet = { onShowCardFeedSheet(false) },
        showHotkeysPanel = overlay.showHotkeysPanel,
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
        onDismissHotkeys = { overlay.showHotkeysPanel = false },
    )
}

private const val MotionDurationMs: Int = 200
private val MotionEasing = CubicBezierEasing(0f, 0f, 0.2f, 1f)
internal const val TMUX_SESSION_SCREEN_TAG = "tmux:session"
