package com.pocketshell.app.tmux

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.pocketshell.app.composer.PromptComposerSheet
import com.pocketshell.app.session.AgentConversationUiState
import com.pocketshell.app.session.InlineDictationViewModel
import com.pocketshell.app.settings.SettingsViewModel
import com.pocketshell.app.session.KeyBarWithMic
import com.pocketshell.app.session.SessionTab
import com.pocketshell.app.sessions.DEFAULT_TMUX_START_DIRECTORY
import com.pocketshell.app.sessions.HostTmuxSessionPickerRequest
import com.pocketshell.app.sessions.HostTmuxSessionPickerState
import com.pocketshell.app.sessions.HostTmuxSessionPickerViewModel
import com.pocketshell.app.sessions.HostTmuxSessionRow
import com.pocketshell.app.sessions.resolveTmuxSessionCreation
import com.pocketshell.app.snippets.SnippetPickerSheet
import com.pocketshell.app.snippets.SnippetKind
import com.pocketshell.app.tmux.TmuxSessionViewModel.ConnectionStatus
import com.pocketshell.app.voice.BottomChipControls
import com.pocketshell.app.voice.DefaultSessionChips
import com.pocketshell.app.voice.InlineDictationErrorStrip
import com.pocketshell.app.voice.VoiceCommandReviewStrip
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.agents.ConversationRole
import com.pocketshell.core.agents.ToolCallSummary
import com.pocketshell.app.composer.MarkdownText
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.terminal.ui.TerminalSurface
import com.pocketshell.core.terminal.ui.showTerminalSoftKeyboard
import com.pocketshell.uikit.components.Breadcrumb
import com.pocketshell.uikit.components.KeyBar
import com.pocketshell.uikit.components.Tabs
import com.pocketshell.uikit.model.Crumb
import com.pocketshell.uikit.model.KeyBinding
import com.pocketshell.uikit.model.KeyKind
import com.pocketshell.uikit.theme.PocketShellColors
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
 * 4. **[KeyBar]** above the keyboard. Taps route to the currently visible
 *    pane via [TmuxSessionViewModel.onKeyBarKey].
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
    modifier: Modifier = Modifier,
    sessionPickerViewModel: HostTmuxSessionPickerViewModel = hiltViewModel(),
    inlineDictationViewModel: InlineDictationViewModel = hiltViewModel(),
    // Issue #176: needed for the Settings → Conversation → "Show system
    // notes" toggle to take effect inside the conversation pane without
    // restarting the session.
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
    onOpenTmuxSession: (sessionName: String, startDirectory: String?) -> Unit = { _, _ -> },
    onReplaceTmuxSession: (sessionName: String) -> Unit = {},
    onOpenJobs: () -> Unit = {},
    onOpenUsage: () -> Unit = {},
    /**
     * Issue #116 (usage-panel Fix B): same per-host worst-case
     * [com.pocketshell.core.usage.UsageProviderRecord] surface as
     * [com.pocketshell.app.session.SessionScreen], but for the
     * `tmux -CC` route. MainActivity passes the lookup for the active
     * host id; `null` when no chip should render.
     */
    usageBadgeProvider: com.pocketshell.core.usage.UsageProviderRecord? = null,
) {
    LaunchedEffect(hostId, hostName, host, port, user, keyPath, passphrase, sessionName, startDirectory) {
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
        )
    }

    val panes by viewModel.panes.collectAsState()
    val status by viewModel.connectionStatus.collectAsState()
    val agentConversations by viewModel.agentConversations.collectAsState()
    val sessionPickerState by sessionPickerViewModel.state.collectAsState()
    val voiceCommandReview by viewModel.voiceCommandReview.collectAsState()
    val dictationState by inlineDictationViewModel.uiState.collectAsState()
    // Issue #176: collected once at the screen root so the conversation
    // pane recomposes when the toggle flips in Settings.
    val appSettings by settingsViewModel.state.collectAsState()
    // Issue #145: gate the in-session Reconnect button on whether the
    // ViewModel has a target to reconnect to. Without this, the button
    // would render in a tight initial-connect-failure window where
    // `reconnect()` would silently no-op.
    val canReconnect by viewModel.canReconnect.collectAsState()

    val pagerState = rememberPagerState(pageCount = { panes.size })

    val isImeVisible = WindowInsets.ime.getBottom(
        LocalDensity.current,
    ) > 0
    // Issue #184: while the soft keyboard is up, the user is in
    // "typing-focus" mode — the breadcrumb / window-strip / tabs chrome
    // up top eats vertical room that the terminal viewport (and the
    // cursor row inside it) desperately needs. We drop the top chrome
    // when the IME is visible and restore it on hide. Mirrors Telegram's
    // chrome-while-typing behaviour and matches issue #184's Layer 2.
    val chromeCompressed = isImeVisible

    val currentPane = panes.getOrNull(pagerState.currentPage)
    val currentAgentConversation = currentPane?.paneId?.let { agentConversations[it] }
    val currentWindowId = currentPane?.windowId
    val windows = remember(panes) { panes.toWindowSummaries() }
    val crumbs = remember(host, sessionName, currentPane, panes, windows) {
        breadcrumbCrumbs(host, sessionName, currentPane, panes, windows)
    }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val verticalSwipeThresholdPx = with(LocalDensity.current) { VerticalSwipeThreshold.toPx() }
    var moreExpanded by remember { mutableStateOf(false) }
    var windowMenuFor by remember { mutableStateOf<WindowSummary?>(null) }
    var dialogMode by remember { mutableStateOf<TmuxDialogMode?>(null) }
    var dialogText by remember { mutableStateOf("") }
    var dialogStartDirectory by remember { mutableStateOf(DEFAULT_TMUX_START_DIRECTORY) }
    var showWindowSwitcher by remember { mutableStateOf(false) }
    var showSessionDrawer by remember { mutableStateOf(false) }
    // Voice/dictation surfaces — mirror SessionScreen so the tmux route
    // gets the prompt composer, the mic FAB, the inline-dictation key bar,
    // and the snippet picker. Without these the user can never dictate
    // into a tmux pane (see #123 — the primary user route was completely
    // dark for voice input).
    var showMicSheet by remember { mutableStateOf(false) }
    var showSnippetPicker by remember { mutableStateOf(false) }
    val context = LocalContext.current
    // Issue #131: same root-view handle as `SessionScreen`. The pager
    // renders one pane at a time, so the helper's recursive search lands
    // on the visible pane's `TerminalView`.
    val composeRootView = LocalView.current

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
    LaunchedEffect(isImeVisible) {
        if (isImeVisible) {
            com.pocketshell.core.terminal.ui.pinTerminalToBottom(composeRootView)
        }
    }

    // Route inline-dictation transcripts into the currently focused pane.
    // The collector re-binds whenever the focused pane or dictation mode
    // changes so we always write into the pane the user is looking at, not
    // some stale pane id captured at first composition. Prompt-mode bytes
    // go straight to the pane via `send-keys`; Command-mode bytes go
    // through the planner for explicit review (mirroring SessionScreen).
    val focusedPaneId = currentPane?.paneId
    LaunchedEffect(inlineDictationViewModel, dictationState.mode, focusedPaneId) {
        inlineDictationViewModel.transcriptions.collect { text ->
            val paneId = focusedPaneId ?: return@collect
            when (dictationState.mode) {
                InlineDictationViewModel.DictationMode.Prompt -> {
                    if (text.isNotEmpty()) {
                        viewModel.writeInputToPane(paneId, text.toByteArray(Charsets.UTF_8))
                    }
                }
                InlineDictationViewModel.DictationMode.Command -> viewModel.planVoiceCommand(text, paneId)
            }
        }
    }

    // Runtime RECORD_AUDIO gate for the inline-dictation path. Mirrors the
    // raw-SSH SessionScreen permission launcher; the OS only ever shows one
    // prompt because the grant is per-package.
    val inlinePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            inlineDictationViewModel.onMicTap()
        } else {
            inlineDictationViewModel.surfacePermissionDenied()
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

    fun openTextDialog(
        mode: TmuxDialogMode,
        initialText: String = "",
        initialStartDirectory: String = currentPane?.cwd?.takeIf { it.isNotBlank() }
            ?: DEFAULT_TMUX_START_DIRECTORY,
    ) {
        dialogMode = mode
        dialogText = initialText
        dialogStartDirectory = initialStartDirectory
    }

    fun selectWindow(window: WindowSummary) {
        viewModel.selectWindow(window.windowId)
        val page = panes.indexOfFirst { it.windowId == window.windowId }
        if (page >= 0) {
            scope.launch { pagerState.animateScrollToPage(page) }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(color = PocketShellColors.Background)
            .testTag(TMUX_SESSION_SCREEN_TAG),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalSwipeInput(
                        thresholdPx = verticalSwipeThresholdPx,
                        onBoundary = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                        onSwipeDown = { showSessionDrawer = true },
                    ),
            ) {
                // Issue #184 Layer 2: breadcrumb chrome flips between the
                // full four-segment chain (IME hidden) and the slim
                // session-name strip (IME visible). The pair is wrapped
                // in a Column so the AnimatedVisibility entries do not
                // overlap visually mid-transition — the hidden one
                // collapses to zero height and the other claims the row.
                // The kebab-menu owner remains local screen state so its
                // dropdown reaches into the session-lifecycle dialog
                // routes below.
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
                        Breadcrumb(
                            crumbs = crumbs,
                            onBack = onBack,
                            onMore = { moreExpanded = true },
                            modifier = Modifier.testTag(TMUX_FULL_BREADCRUMB_TAG),
                        )
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
                            modifier = Modifier.testTag(TMUX_COMPACT_BREADCRUMB_TAG),
                        )
                    }
                }
                TmuxMoreMenu(
                    expanded = moreExpanded,
                    currentWindowId = currentWindowId,
                    onDismiss = { moreExpanded = false },
                    onCreateSession = {
                        moreExpanded = false
                        openTextDialog(TmuxDialogMode.CreateSession)
                    },
                    onRenameSession = {
                        moreExpanded = false
                        openTextDialog(TmuxDialogMode.RenameSession, sessionName)
                    },
                    onKillSession = {
                        moreExpanded = false
                        dialogMode = TmuxDialogMode.KillSession
                    },
                    onSwitchSession = {
                        moreExpanded = false
                        showSessionDrawer = true
                    },
                    onOpenJobs = {
                        moreExpanded = false
                        onOpenJobs()
                    },
                    onOpenUsage = {
                        moreExpanded = false
                        onOpenUsage()
                    },
                    onNewWindow = {
                        moreExpanded = false
                        viewModel.newWindow()
                    },
                    onRenameWindow = {
                        moreExpanded = false
                        openTextDialog(TmuxDialogMode.RenameWindow, currentWindowId.orEmpty())
                    },
                    onKillWindow = {
                        moreExpanded = false
                        dialogMode = TmuxDialogMode.KillWindow
                    },
                )
            }

            (status as? ConnectionStatus.Connecting)?.let {
                StatusLine("connecting to ${it.user}@${it.host}:${it.port} (tmux $sessionName)")
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
            // Issue #116 (usage-panel Fix B): in-session blocked /
            // near-limit chip for the active host. Mirrors the
            // status-area placement on [com.pocketshell.app.session.SessionScreen]
            // so the user sees the same affordance regardless of
            // whether the route is plain SSH or tmux -CC.
            if (usageBadgeProvider != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(color = PocketShellColors.Surface)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .testTag(TMUX_SESSION_USAGE_BADGE_TAG),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    com.pocketshell.app.usage.UsageSessionBlockedBadge(
                        provider = usageBadgeProvider,
                    )
                }
            }

            val tabs = if (currentAgentConversation?.detection != null) {
                listOf("Terminal", "Conversation")
            } else {
                listOf("Terminal")
            }
            // Issue #184 Layer 2: Tabs and WindowStrip hide on IME up
            // because the user is mid-type — they cannot tap a tab pill
            // without first dismissing the keyboard anyway, and giving
            // those ~80dp back to the terminal viewport keeps the cursor
            // row visible. Both restore on IME-hide via the
            // AnimatedVisibility exit pass.
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
                Tabs(
                    labels = tabs,
                    selectedIndex = if (currentAgentConversation?.selectedTab == SessionTab.Conversation) 1 else 0,
                    onSelected = { index ->
                        currentPane?.let { pane ->
                            viewModel.selectSessionTab(
                                pane.paneId,
                                if (index == 1) SessionTab.Conversation else SessionTab.Terminal,
                            )
                        }
                    },
                    modifier = Modifier.testTag(TMUX_TABS_TAG),
                )
            }

            // Per #158: only surface the WindowStrip when there are
            // multiple windows in this session. In the (very common)
            // single-window case the strip was pure chrome and confused
            // users about what the trailing "+" creates (window vs
            // session). For the single-window case the "+ New window"
            // affordance remains reachable from the kebab dropdown.
            //
            // Issue #184 Layer 2: also hide while the IME is up — the
            // user has committed to typing into the current pane and
            // doesn't need a window-picker rail eating vertical space
            // above the cursor row.
            AnimatedVisibility(
                visible = windows.size > 1 && !chromeCompressed,
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
                WindowStrip(
                    windows = windows,
                    currentWindowId = currentWindowId,
                    onSelectWindow = ::selectWindow,
                    onOpenWindowMenu = { windowMenuFor = it },
                    onNewWindow = viewModel::newWindow,
                )
            }

            // Per [D6]: render exactly one pane at a time. The
            // HorizontalPager renders only the visible page eagerly by
            // default; sibling panes are pre-loaded into the off-screen
            // pages but kept lightweight because each TerminalSurface
            // owns its own (already-attached) TerminalSurfaceState.
            Box(
                modifier = Modifier
                    .weight(1f),
            ) {
                if (
                    currentPane != null &&
                    currentAgentConversation?.selectedTab == SessionTab.Conversation &&
                    currentAgentConversation.detection != null
                ) {
                    val paneIdForSend = currentPane.paneId
                    TmuxConversationPane(
                        events = currentAgentConversation.events,
                        onSendToAgent = { text ->
                            viewModel.sendToAgentPane(paneIdForSend, text)
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag(TMUX_CONVERSATION_PANE_TAG),
                        showSystemNotes = appSettings.showSystemNotes,
                    )
                } else if (panes.isEmpty()) {
                    EmptyPanesPlaceholder()
                } else {
                    HorizontalPager(
                        state = pagerState,
                        key = { pageIndex -> panes[pageIndex].paneId },
                        modifier = Modifier.fillMaxSize(),
                    ) { pageIndex ->
                        val pane = panes[pageIndex]
                        TerminalSurface(
                            state = pane.terminalState,
                            // Issue #102 (reopen): propagate the on-screen
                            // grid to the remote tmux pane so opencode /
                            // Codex / Claude Code render their UI for the
                            // grid the local emulator is actually painting.
                            // Without this, tmux keeps the pane at the
                            // SSH-PTY-time 80x24 default and the inner
                            // CLI's input box / cursor land at the wrong
                            // on-screen cells. The raw-SSH route has the
                            // equivalent wiring via SessionScreen ->
                            // SessionViewModel.resizeRemotePty.
                            onTerminalSizeChanged = viewModel::resizeRemotePty,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 2.dp, vertical = 4.dp),
                        )
                    }
                }
                TmuxAgentHintBanner(
                    pane = currentPane,
                    conversation = currentAgentConversation,
                    onOpen = { paneId ->
                        viewModel.selectSessionTab(paneId, SessionTab.Conversation)
                    },
                    onDismiss = viewModel::dismissAgentHint,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }

            // Voice-related strips sit above the input band so they remain
            // visible while the IME is up. Tapping the error strip clears
            // it; the planner review strip's buttons route through the
            // currently focused pane.
            dictationState.error?.let { msg ->
                InlineDictationErrorStrip(msg, onDismiss = inlineDictationViewModel::clearError)
            }
            VoiceCommandReviewStrip(
                state = voiceCommandReview,
                onInsert = {
                    currentPane?.let { pane ->
                        viewModel.approvePendingVoiceCommand(pane.paneId, withEnter = false)
                    }
                },
                onRun = {
                    currentPane?.let { pane ->
                        viewModel.approvePendingVoiceCommand(pane.paneId, withEnter = true)
                    }
                },
                onDismiss = viewModel::dismissVoiceCommandReview,
            )

            if (isImeVisible && currentPane != null) {
                KeyBarWithMic(
                    keys = KeyBarLayout,
                    onKey = { binding ->
                        viewModel.onKeyBarKey(currentPane.paneId, binding.label)
                    },
                    micState = dictationState.recording,
                    micAmplitude = dictationState.amplitude,
                    dictationMode = dictationState.mode,
                    onDictationModeSelected = inlineDictationViewModel::selectMode,
                    onMicTap = {
                        // Three-step gate identical to SessionScreen: runtime
                        // permission → stored API key → recorder. Without an
                        // API key we open the prompt composer (which hosts
                        // the key-entry dialog) rather than dead-ending in a
                        // silent banner.
                        val granted = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO,
                        ) == PackageManager.PERMISSION_GRANTED
                        if (!granted) {
                            inlinePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            return@KeyBarWithMic
                        }
                        if (!inlineDictationViewModel.hasApiKey()) {
                            showMicSheet = true
                            return@KeyBarWithMic
                        }
                        inlineDictationViewModel.onMicTap()
                    },
                )
            } else if (!isImeVisible && currentPane != null) {
                BottomChipControls(
                    chips = DefaultSessionChips,
                    onChipTap = { chip ->
                        // Chip taps run literal commands in the focused
                        // pane. Mirrors `SessionViewModel.onChipTap` which
                        // appends a CR; the tmux input bridge translates
                        // the trailing CR into a named `Enter` key.
                        currentPane?.let { pane ->
                            viewModel.writeInputToPane(
                                pane.paneId,
                                (chip + "\r").toByteArray(Charsets.UTF_8),
                            )
                        }
                    },
                    onDictateTap = { showMicSheet = true },
                    // Issue #131: surface the show-keyboard chip on the
                    // tmux route too. The helper looks up the
                    // `TerminalView` of the currently visible pane (the
                    // pager renders one pane at a time, so there is only
                    // ever a single attached `TerminalView` to find under
                    // the Compose root).
                    onShowKeyboardTap = { showTerminalSoftKeyboard(composeRootView) },
                    onAddSnippetTap = if (hostId != 0L) {
                        { showSnippetPicker = true }
                    } else null,
                    // Project navigation on tmux panes is a separate
                    // follow-up — see #123 notes on per-pane cwd /
                    // project-root wiring.
                    onProjectNavigationTap = null,
                )
            }

            // Pane pager dot indicator — a thin row of dots so the user
            // knows which pane is showing and how many siblings exist.
            // We only render when there's >1 pane; the indicator is
            // redundant in the single-pane case (which is the common one
            // for a freshly-attached session).
            if (panes.size > 1) {
                Box(
                    modifier = Modifier.verticalSwipeInput(
                        thresholdPx = verticalSwipeThresholdPx,
                        onBoundary = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                        onSwipeUp = {
                            if (windows.size > 1) {
                                showWindowSwitcher = true
                            }
                        },
                    ),
                ) {
                    PageIndicator(
                        pageCount = panes.size,
                        currentPage = pagerState.currentPage,
                    )
                }
            }
        }

        WindowSwitcherOverlay(
            visible = showWindowSwitcher,
            windows = windows,
            currentWindowId = currentWindowId,
            onDismiss = { showWindowSwitcher = false },
            onSelectWindow = { window ->
                selectWindow(window)
                showWindowSwitcher = false
            },
        )

        windowMenuFor?.let { window ->
            WindowContextMenu(
                window = window,
                onDismiss = { windowMenuFor = null },
                onRename = {
                    windowMenuFor = null
                    openTextDialog(TmuxDialogMode.RenameWindow, window.windowId)
                },
                onKill = {
                    windowMenuFor = null
                    dialogMode = TmuxDialogMode.KillWindowFor(window.windowId)
                },
            )
        }

        dialogMode?.let { mode ->
            TmuxLifecycleDialog(
                mode = mode,
                sessionName = sessionName,
                currentWindowId = currentWindowId,
                text = dialogText,
                onTextChange = { dialogText = it },
                startDirectory = dialogStartDirectory,
                onStartDirectoryChange = { dialogStartDirectory = it },
                onDismiss = { dialogMode = null },
                onConfirm = {
                    when (val currentMode = mode) {
                        TmuxDialogMode.CreateSession -> {
                            val creation = resolveTmuxSessionCreation(
                                rawName = dialogText,
                                rawStartDirectory = dialogStartDirectory,
                            )
                            onOpenTmuxSession(creation.sessionName, creation.startDirectory)
                        }
                        TmuxDialogMode.RenameSession -> {
                            val name = dialogText.trim()
                            viewModel.renameCurrentSession(name)
                            if (name.isNotEmpty()) onReplaceTmuxSession(name)
                        }
                        TmuxDialogMode.KillSession -> {
                            viewModel.killCurrentSession()
                            onBack()
                        }
                        TmuxDialogMode.RenameWindow -> {
                            viewModel.renameWindow(currentWindowId.orEmpty(), dialogText)
                        }
                        TmuxDialogMode.KillWindow -> {
                            viewModel.killWindow(currentWindowId.orEmpty())
                        }
                        is TmuxDialogMode.KillWindowFor -> {
                            viewModel.killWindow(currentMode.windowId)
                        }
                    }
                    dialogMode = null
                },
            )
        }

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
                showSessionDrawer = false
                sessionPickerViewModel.dismiss()
                if (selectedSessionName != sessionName) {
                    onReplaceTmuxSession(selectedSessionName)
                }
            },
            onCreate = {
                showSessionDrawer = false
                sessionPickerViewModel.dismiss()
                openTextDialog(TmuxDialogMode.CreateSession)
            },
        )
    }

    LaunchedEffect(showSessionDrawer, sessionPickerRequest) {
        if (showSessionDrawer) {
            sessionPickerViewModel.load(sessionPickerRequest)
        }
    }

    if (showMicSheet) {
        // PromptComposerSheet drives dictation + the one-field API-key
        // entry dialog (the inline-dictation path delegates the key entry
        // here too). `onSend` routes through writeInputToPane so the
        // composer's Send / Send+Enter buttons reach the focused tmux pane
        // via `send-keys`, identical to chip taps and snippet picks.
        PromptComposerSheet(
            onDismiss = { showMicSheet = false },
            onSend = { text, withEnter ->
                currentPane?.let { pane ->
                    val payload = if (withEnter) text + "\r" else text
                    viewModel.writeInputToPane(
                        pane.paneId,
                        payload.toByteArray(Charsets.UTF_8),
                    )
                }
                showMicSheet = false
            },
            hostId = hostId.takeIf { it != 0L },
        )
    }

    if (showSnippetPicker && hostId != 0L) {
        // Mirrors SessionScreen's snippet wiring. Command snippets execute
        // immediately (CR appended); Prompt snippets are inserted without
        // CR so the user can continue editing before pressing Enter.
        SnippetPickerSheet(
            hostId = hostId,
            onDismiss = { showSnippetPicker = false },
            onSnippetPicked = { snippet ->
                currentPane?.let { pane ->
                    val payload = when (SnippetKind.fromStorage(snippet.kind)) {
                        SnippetKind.Command -> snippet.body + "\r"
                        SnippetKind.Prompt -> snippet.body
                    }
                    viewModel.writeInputToPane(
                        pane.paneId,
                        payload.toByteArray(Charsets.UTF_8),
                    )
                }
                showSnippetPicker = false
            },
        )
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
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
                    TextButton(onClick = onDismiss) {
                        Text("Close")
                    }
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        // Per #158: disambiguate "session" vs "window"
                        // controls. The drawer is host-scoped (every
                        // session is a separate workspace owned by the
                        // tmux server on the remote host); the trailing
                        // strip's "+ window" is session-scoped. The
                        // wording here calls that out explicitly so the
                        // user doesn't accidentally fork a workspace
                        // when they meant to add a window.
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = onCreate,
                        ) {
                            Text("+ New tmux session (separate workspace)")
                        }
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
                TextButton(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    onClick = onRefresh,
                ) {
                    Text("Refresh")
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
        fontSize = 13.sp,
        modifier = Modifier
            .fillMaxWidth()
            .background(PocketShellColors.SurfaceElev, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp),
    )
}

@Composable
private fun TmuxSessionDrawerRow(
    row: HostTmuxSessionRow,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = if (selected) PocketShellColors.Accent else PocketShellColors.SurfaceElev,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.name,
                color = if (selected) PocketShellColors.Background else PocketShellColors.Text,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = when {
                    selected -> "current"
                    row.attached -> "attached"
                    else -> "available"
                },
                color = if (selected) PocketShellColors.Background else PocketShellColors.TextSecondary,
                fontSize = 12.sp,
            )
        }
        Text(
            text = if (selected) "Open" else "Attach",
            color = if (selected) PocketShellColors.Background else PocketShellColors.Accent,
            fontSize = 13.sp,
        )
    }
}

internal data class WindowSummary(
    val windowId: String,
    val title: String,
)

/**
 * Build [WindowSummary] rows for the WindowStrip / WindowSwitcher.
 *
 * Per #158: the previous implementation surfaced the raw tmux `@N`
 * window IDs as the visible label, which the user reported as cryptic.
 * We now number windows by their position in the pane order so the user
 * sees "Window 1", "Window 2", … — stable for the duration of a session
 * because [TmuxSessionViewModel] preserves pane row identity across
 * `%layout-change` notifications (the underlying tmux pane ID is the
 * key). Window renaming via the `%window-renamed` control-mode payload
 * is tracked in #47; once that lands the renamed title can be threaded
 * here without changing the index-based fallback.
 */
internal fun List<TmuxPaneState>.toWindowSummaries(): List<WindowSummary> =
    distinctBy { it.windowId }
        .mapIndexed { index, pane ->
            WindowSummary(
                windowId = pane.windowId,
                title = "Window ${index + 1}",
            )
        }

private sealed interface TmuxDialogMode {
    data object CreateSession : TmuxDialogMode
    data object RenameSession : TmuxDialogMode
    data object KillSession : TmuxDialogMode
    data object RenameWindow : TmuxDialogMode
    data object KillWindow : TmuxDialogMode
    data class KillWindowFor(val windowId: String) : TmuxDialogMode
}

private val VerticalSwipeThreshold = 72.dp
private val SessionDrawerMaxWidth = 360.dp
private const val MotionDurationMs: Int = 200
private val MotionEasing = CubicBezierEasing(0f, 0f, 0.2f, 1f)
internal const val TMUX_SESSION_SCREEN_TAG = "tmux:session"
internal const val TMUX_SESSION_SWITCHER_TAG = "tmux:session-switcher"
internal const val TMUX_CONVERSATION_PANE_TAG = "tmux:conversation"
internal const val TMUX_CONVERSATION_COMPOSER_INPUT_TAG = "tmux:conversation:composer-input"
internal const val TMUX_CONVERSATION_COMPOSER_SEND_TAG = "tmux:conversation:composer-send"
internal const val TMUX_CONVERSATION_TOOL_ROW_TAG_PREFIX = "tmux:conversation:tool:"
/** Issue #176: stable test tag prefix for a `SystemNote` row in the tmux conversation pane. */
internal const val TMUX_CONVERSATION_SYSTEM_NOTE_ROW_TAG_PREFIX = "tmux:conversation:system-note:"
internal const val TMUX_AGENT_HINT_TAG = "tmux:agent-hint"
/** Issue #116: stable test tag for the in-tmux-session blocked / near-limit chip. */
internal const val TMUX_SESSION_USAGE_BADGE_TAG = "tmux:usage-badge"

/**
 * Issue #184: stable test tags for the IME-aware top chrome on the tmux
 * session screen.
 *
 * - [TMUX_FULL_BREADCRUMB_TAG] is on the regular four-segment
 *   `host › session › window › pane` strip and is visible only while the
 *   soft keyboard is hidden.
 * - [TMUX_COMPACT_BREADCRUMB_TAG] is on the slimmed-down session-name
 *   strip that replaces the full breadcrumb while the IME is up so the
 *   terminal viewport can claim the freed vertical space.
 * - [TMUX_TABS_TAG] is on the Terminal/Conversation tab row. Hidden
 *   while the IME is up.
 *
 * The window-strip already carries [TMUX_WINDOW_STRIP_TAG] from #158; we
 * reuse it for the "WindowStrip hidden while IME up" assertion in
 * [TmuxSessionScreenImeChromeTest].
 */
internal const val TMUX_FULL_BREADCRUMB_TAG = "tmux:breadcrumb:full"
internal const val TMUX_COMPACT_BREADCRUMB_TAG = "tmux:breadcrumb:compact"
internal const val TMUX_TABS_TAG = "tmux:tabs"

/**
 * Issue #145: stable test tags for the mid-session SSH disconnect band
 * (root row + the Reconnect button). The connected disconnect+reconnect
 * test asserts both tags are present once the SSH transport drops, and
 * taps [TMUX_SESSION_RECONNECT_TAG] to drive the reconnect.
 */
internal const val TMUX_SESSION_ERROR_TAG = "tmux:session:error"
internal const val TMUX_SESSION_RECONNECT_TAG = "tmux:session:reconnect"
/** Issue #158: the WindowStrip is the per-session window tabs row. Hidden when only one window exists. */
internal const val TMUX_WINDOW_STRIP_TAG = "tmux:window-strip"
/**
 * Issue #158: prefix for the per-pill test tag inside the WindowStrip.
 * The pill index is 1-based, so the first window's pill carries
 * "tmux:window-strip-pill:1", the second "tmux:window-strip-pill:2",
 * etc. Used by [TmuxSessionWindowNavigationE2eTest] so the click
 * does not collide with the same "Window N" string in the breadcrumb.
 */
internal const val TMUX_WINDOW_STRIP_PILL_TAG_PREFIX = "tmux:window-strip-pill:"
/** Issue #158: trailing "+ window" button inside the WindowStrip. */
internal const val TMUX_NEW_WINDOW_BUTTON_TAG = "tmux:new-window-button"

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

/**
 * Build the breadcrumb segments. With tmux we have a real four-level
 * chain (host → session → window → pane), so we surface all four.
 *
 * Per #158: the window + pane labels used to surface the raw tmux
 * `@N` / `%N` IDs, which the user reported as cryptic ("can't find my
 * way back"). We now derive a 1-based ordinal from the
 * [panes] / [windows] lists so the crumb reads
 * `host › session › Window 2 › Pane 3`. The non-blank pane title from
 * `display-message -p '#{pane_title}'` still wins when present.
 * Window renaming via the `%window-renamed` control-mode payload is
 * tracked in #47; until then the ordinal is what we show.
 */
private fun breadcrumbCrumbs(
    host: String,
    sessionName: String,
    currentPane: TmuxPaneState?,
    panes: List<TmuxPaneState>,
    windows: List<WindowSummary>,
): List<Crumb> {
    val windowLabel = currentPane?.let { pane ->
        val match = windows.firstOrNull { it.windowId == pane.windowId }
        match?.title ?: "Window ?"
    } ?: "—"
    val paneLabel = currentPane?.let { pane ->
        if (pane.title.isNotBlank()) {
            pane.title
        } else {
            // 1-based index within the current window; "Pane 1" is the
            // first pane of the window the user is looking at.
            val paneIndexInWindow = panes
                .filter { it.windowId == pane.windowId }
                .indexOfFirst { it.paneId == pane.paneId }
            if (paneIndexInWindow >= 0) "Pane ${paneIndexInWindow + 1}" else "Pane ?"
        }
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
private fun FailedConnectionRow(
    message: String,
    onReconnect: () -> Unit,
    canReconnect: Boolean,
) {
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
            // Design-system error token (`docs/design-system.md` §1).
            // The disconnect band is the canonical "error status"
            // surface for the tmux route and so reaches for [Red]
            // rather than the muted `TextSecondary` of the generic
            // StatusLine.
            color = PocketShellColors.Red,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
        )
        if (canReconnect) {
            TextButton(
                onClick = onReconnect,
                modifier = Modifier.testTag(TMUX_SESSION_RECONNECT_TAG),
            ) {
                Text("Reconnect")
            }
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
        Text(
            text = "waiting for tmux panes…",
            color = PocketShellColors.TextSecondary,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun WindowSwitcherOverlay(
    visible: Boolean,
    windows: List<WindowSummary>,
    currentWindowId: String?,
    onDismiss: () -> Unit,
    onSelectWindow: (WindowSummary) -> Unit,
) {
    val initialPage = windows.indexOfFirst { it.windowId == currentWindowId }.coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { windows.size })

    LaunchedEffect(visible, currentWindowId, windows) {
        if (visible) {
            val page = windows.indexOfFirst { it.windowId == currentWindowId }
            if (page >= 0) pagerState.scrollToPage(page)
        }
    }

    LaunchedEffect(visible, pagerState, windows, currentWindowId) {
        if (!visible) return@LaunchedEffect
        snapshotFlow { pagerState.settledPage }.collect { page ->
            val window = windows.getOrNull(page) ?: return@collect
            if (window.windowId != currentWindowId) {
                onSelectWindow(window)
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
                .padding(16.dp),
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .clickable(onClick = {})
                    .background(PocketShellColors.Surface, RoundedCornerShape(8.dp))
                    .border(
                        width = 1.dp,
                        color = PocketShellColors.BorderSoft,
                        shape = RoundedCornerShape(8.dp),
                    )
                    .padding(14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Windows",
                        color = PocketShellColors.Text,
                        fontSize = 15.sp,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) {
                        Text("Close")
                    }
                }

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(104.dp)
                        .padding(top = 8.dp),
                ) { page ->
                    val window = windows[page]
                    val selected = window.windowId == currentWindowId
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 4.dp)
                            .background(
                                color = if (selected) PocketShellColors.Accent else PocketShellColors.SurfaceElev,
                                shape = RoundedCornerShape(8.dp),
                            )
                            .clickable(
                                role = androidx.compose.ui.semantics.Role.Tab,
                                onClick = { onSelectWindow(window) },
                            )
                            .padding(16.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = window.title,
                            color = if (selected) PocketShellColors.Background else PocketShellColors.Text,
                            fontSize = 18.sp,
                        )
                        Text(
                            text = "${page + 1} / ${windows.size}",
                            color = if (selected) PocketShellColors.Background else PocketShellColors.TextSecondary,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Issue #179: chip-display block extracted out of [TmuxSessionScreen]'s
 * main column so the dismiss state machine is unit-testable in
 * isolation. The composable owns the auto-dismiss [LaunchedEffect] keyed
 * on (paneId, detectionKey) — when [conversation]'s `hintVisible` flips
 * to false (explicit dismiss, visit-to-dismiss, or auto-dismiss), the
 * effect is torn down with the chip.
 *
 * Public visibility is [internal] so an instrumentation test in the
 * same module can mount the chip against a real [TmuxSessionViewModel]
 * and the production dismissed-set wiring without standing up the full
 * tmux-CC connection.
 */
@Composable
internal fun TmuxAgentHintBanner(
    pane: TmuxPaneState?,
    conversation: AgentConversationUiState?,
    onOpen: (paneId: String) -> Unit,
    onDismiss: (paneId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val detection = conversation?.detection
    val visible = pane != null &&
        conversation?.hintVisible == true &&
        detection != null &&
        conversation.selectedTab == SessionTab.Terminal
    if (!visible) return
    val hintPaneId = pane!!.paneId
    val hintDetectionKey = detection!!.sessionId ?: detection.sourcePath
    // Auto-dismiss after [AGENT_HINT_AUTO_DISMISS_MS] of continuous
    // visibility for the same (pane, detection) pair. The keyed
    // LaunchedEffect restarts only when the user navigates to a
    // different pane or the detection changes — within a single
    // sighting the timer runs once and either fires (auto-dismiss) or
    // is cancelled by the user explicitly dismissing / visiting the
    // Conversation tab (both clear `hintVisible`, which makes
    // `visible` false above and removes the composable + its
    // LaunchedEffect).
    LaunchedEffect(hintPaneId, hintDetectionKey) {
        kotlinx.coroutines.delay(AGENT_HINT_AUTO_DISMISS_MS)
        onDismiss(hintPaneId)
    }
    TmuxAgentHintChip(
        label = "${detection.agent.displayName} session detected",
        onOpen = { onOpen(hintPaneId) },
        onDismiss = { onDismiss(hintPaneId) },
        modifier = modifier
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .testTag(TMUX_AGENT_HINT_TAG),
    )
}

@Composable
private fun TmuxAgentHintChip(
    label: String,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Issue #179: muted bottom banner per docs/design-system.md §6.3.
    // Replaces the previous opaque top-center overlay. AccentSoft fill +
    // 1 dp AccentDim border + 10 dp corner radius keeps the chip visible
    // without dominating the terminal viewport.
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(color = PocketShellColors.AccentSoft, shape = shape)
            .border(width = 1.dp, color = PocketShellColors.AccentDim, shape = shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onOpen),
        ) {
            Text(
                text = label,
                color = PocketShellColors.Text,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "Tap to see full conversation >",
                color = PocketShellColors.TextSecondary,
                fontSize = 11.sp,
            )
        }
        TextButton(onClick = onDismiss) {
            Text("X")
        }
    }
}

/**
 * Issue #179: auto-dismiss delay for the agent hint banner. 8 seconds
 * matches the UX audit (#154 finding 9.1) and the design-system spec
 * (`docs/design-system.md` §6.3).
 */
internal const val AGENT_HINT_AUTO_DISMISS_MS: Long = 8_000L

@Composable
internal fun TmuxConversationPane(
    events: List<ConversationEvent>,
    onSendToAgent: (String) -> Unit,
    modifier: Modifier = Modifier,
    // Issue #176: when false, XML-tagged SystemNote events are filtered
    // from the visible feed entirely. The default keeps the existing
    // behaviour (notes visible but muted) so direct callers that did
    // not opt into the setting wire-up still see system notes.
    showSystemNotes: Boolean = true,
) {
    var query by remember { mutableStateOf("") }
    var composerText by remember { mutableStateOf("") }
    val visibleEvents = remember(events, showSystemNotes) {
        if (showSystemNotes) events else events.filterNot { it is ConversationEvent.SystemNote }
    }
    val filteredEvents = remember(visibleEvents, query) {
        val q = query.trim()
        if (q.isBlank()) visibleEvents else visibleEvents.filter { it.searchText().contains(q, ignoreCase = true) }
    }
    // Tool-call expansion state per event-id. Persisted at the pane
    // level (not inside the row composable) so a row scrolling out and
    // back in remembers the user's decision until the session detaches.
    val expandedToolCalls = remember { mutableStateOf(setOf<String>()) }
    // Issue #176: SystemNote expand state — same idea as tool-call expand,
    // collapsed by default, the user's choice is sticky for the lifetime
    // of the conversation pane.
    val expandedSystemNotes = remember { mutableStateOf(setOf<String>()) }
    val runningToolIds = remember(events) { runningToolCallIds(events) }
    val rowMap = remember(events) { events.associateBy { it.id } }
    Column(
        modifier = modifier
            .background(color = PocketShellColors.Background)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search in conversation") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (filteredEvents.isEmpty()) {
                item {
                    Text(
                        text = if (events.isEmpty()) "No conversation events yet." else "No matching events.",
                        color = PocketShellColors.TextSecondary,
                        fontSize = 13.sp,
                    )
                }
            }
            items(filteredEvents, key = { it.id }) { event ->
                ConversationEventRow(
                    event = event,
                    runningToolIds = runningToolIds,
                    eventsById = rowMap,
                    isExplicitlyExpanded = expandedToolCalls.value.contains(event.id),
                    onToggleExpand = { id ->
                        expandedToolCalls.value = expandedToolCalls.value.toggle(id)
                    },
                    isSystemNoteExpanded = expandedSystemNotes.value.contains(event.id),
                    onToggleSystemNoteExpand = { id ->
                        expandedSystemNotes.value = expandedSystemNotes.value.toggle(id)
                    },
                )
            }
        }
        AgentComposerRow(
            text = composerText,
            onTextChange = { composerText = it },
            onSend = {
                val trimmed = composerText.trim()
                if (trimmed.isNotEmpty()) {
                    onSendToAgent(trimmed)
                    composerText = ""
                }
            },
        )
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
    eventsById: Map<String, ConversationEvent>,
    isExplicitlyExpanded: Boolean,
    onToggleExpand: (String) -> Unit,
    isSystemNoteExpanded: Boolean,
    onToggleSystemNoteExpand: (String) -> Unit,
) {
    when (event) {
        is ConversationEvent.Message -> ConversationMessageRow(event)
        is ConversationEvent.ToolCall -> ConversationToolCallRow(
            toolCall = event,
            result = eventsById.findToolResultFor(event.id),
            isRunning = event.id in runningToolIds,
            isExplicitlyExpanded = isExplicitlyExpanded,
            onToggle = { onToggleExpand(event.id) },
        )
        is ConversationEvent.ToolResult -> {
            // Orphan tool result with no parent ToolCall — render as a
            // standalone, very subtle row.
            ConversationToolResultRow(event)
        }
        is ConversationEvent.SystemNote -> ConversationSystemNoteRow(
            note = event,
            isExpanded = isSystemNoteExpanded,
            onToggle = { onToggleSystemNoteExpand(event.id) },
        )
    }
}

@Composable
private fun ConversationMessageRow(event: ConversationEvent.Message) {
    val isUser = event.role == ConversationRole.User
    val title = when (event.role) {
        ConversationRole.User -> "USER"
        ConversationRole.Assistant -> if (event.streaming) "ASSISTANT - streaming" else "ASSISTANT"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = if (isUser) PocketShellColors.SurfaceElev else PocketShellColors.Surface)
            .border(width = 1.dp, color = PocketShellColors.BorderSoft)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = title,
            color = if (isUser) PocketShellColors.Accent else PocketShellColors.TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
        MarkdownText(text = event.text)
    }
}

private fun Map<String, ConversationEvent>.findToolResultFor(
    toolCallId: String,
): ConversationEvent.ToolResult? =
    values.firstOrNull { it is ConversationEvent.ToolResult && it.toolCallId == toolCallId }
        as? ConversationEvent.ToolResult

/**
 * Identify every tool call that has no matching tool result yet — the
 * scope addition asks for these to auto-expand so the user can see
 * what's currently happening live.
 */
internal fun runningToolCallIds(events: List<ConversationEvent>): Set<String> {
    val resolved = events
        .filterIsInstance<ConversationEvent.ToolResult>()
        .mapNotNullTo(mutableSetOf()) { it.toolCallId }
    return events
        .filterIsInstance<ConversationEvent.ToolCall>()
        .map { it.id }
        .filter { it !in resolved }
        .toSet()
}

private fun Set<String>.toggle(id: String): Set<String> =
    if (contains(id)) this - id else this + id

private fun ConversationEvent.searchText(): String = when (this) {
    is ConversationEvent.Message -> text
    is ConversationEvent.ToolCall -> "$name $input"
    is ConversationEvent.ToolResult -> output
    is ConversationEvent.SystemNote -> "$tag $content"
}

@Composable
private fun AgentComposerRow(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier
                .weight(1f)
                .testTag(TMUX_CONVERSATION_COMPOSER_INPUT_TAG),
            placeholder = { Text("Message agent") },
        )
        TextButton(
            onClick = onSend,
            enabled = text.isNotBlank(),
            modifier = Modifier.testTag(TMUX_CONVERSATION_COMPOSER_SEND_TAG),
        ) {
            Text("Send")
        }
    }
}

/**
 * Render a single tool-call invocation as either a one-line collapsed
 * row (issue #160 scope addition: subtle / minimal) or an expanded
 * card with input + output bounded by a scroll container for very long
 * payloads.
 *
 * Auto-expand policy: a tool call without a matching tool result is
 * considered "running" and starts expanded so the user sees what's
 * happening live. The user's explicit expand/collapse via [onToggle]
 * overrides the auto-state — that's why we track the explicit flag
 * separately at the pane level rather than relying purely on the
 * derived running state.
 */
@Composable
private fun ConversationToolCallRow(
    toolCall: ConversationEvent.ToolCall,
    result: ConversationEvent.ToolResult?,
    isRunning: Boolean,
    isExplicitlyExpanded: Boolean,
    onToggle: () -> Unit,
) {
    val expanded = isExplicitlyExpanded || (isRunning && result == null)
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
            .clickable(onClick = onToggle)
            .padding(vertical = 2.dp)
            .testTag(TMUX_CONVERSATION_TOOL_ROW_TAG_PREFIX + toolCall.id),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = toolCall.name,
                color = PocketShellColors.TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = summary,
                color = PocketShellColors.TextMuted,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            if (statusGlyph.isNotEmpty()) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = statusGlyph, color = statusColor, fontSize = 12.sp)
            }
        }
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, start = 8.dp, end = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ToolCallSection(label = "input", body = toolCall.input)
                if (result != null) {
                    ToolCallSection(
                        label = if (result.isError) "output (error)" else "output",
                        body = result.output,
                    )
                }
            }
        }
    }
}

/**
 * Issue #176: muted, collapsible renderer for
 * [ConversationEvent.SystemNote] — the XML-tagged metadata blocks
 * Claude Code emits inside Message text (`<system-reminder>`,
 * `<command-name>`, `<local-command-stdout>`, …).
 *
 * Collapsed (default): a single muted row showing the tag label and a
 * one-line preview of the content so the user can scan past it quickly.
 * Tapping the row toggles to the expanded state which shows the full
 * raw content in monospace — no markdown processing because the body
 * is structured / code-like.
 *
 * Styling uses [PocketShellColors.TextMuted] / [PocketShellColors.TextSecondary]
 * so the row reads as "background metadata" rather than a peer message.
 */
@Composable
private fun ConversationSystemNoteRow(
    note: ConversationEvent.SystemNote,
    isExpanded: Boolean,
    onToggle: () -> Unit,
) {
    val preview = remember(note.content) { note.content.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty() }
    val chevron = if (isExpanded) "v" else "›"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 2.dp)
            .testTag(TMUX_CONVERSATION_SYSTEM_NOTE_ROW_TAG_PREFIX + note.id),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = note.tag,
                color = PocketShellColors.TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = preview,
                color = PocketShellColors.TextMuted,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = chevron,
                color = PocketShellColors.TextMuted,
                fontSize = 12.sp,
            )
        }
        if (isExpanded && note.content.isNotEmpty()) {
            // Raw monospace body so the XML-like structure stays readable.
            // Reuses ToolCallSection so very long blocks fall into the
            // bounded scrollable container rather than swallowing the
            // viewport.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, start = 8.dp, end = 4.dp),
            ) {
                ToolCallSection(label = "content", body = note.content)
            }
        }
    }
}

@Composable
private fun ConversationToolResultRow(result: ConversationEvent.ToolResult) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (result.isError) "tool result (error)" else "tool result",
                color = PocketShellColors.TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        if (result.output.isNotEmpty()) {
            ToolCallSection(label = "output", body = result.output)
        }
    }
}

/**
 * Inner labelled block of the expanded tool-call card. Very long bodies
 * (>200 lines OR >5000 chars per the scope addition) collapse into a
 * bounded-height scrollable container so the conversation pane can't
 * have a single tool eat the whole viewport.
 */
@Composable
private fun ToolCallSection(label: String, body: String) {
    if (body.isEmpty()) return
    val lineCount = body.count { it == '\n' } + 1
    val tooLong = lineCount > 200 || body.length > 5000
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            color = PocketShellColors.TextMuted,
            fontSize = 10.sp,
            modifier = Modifier.padding(bottom = 2.dp),
        )
        val container = Modifier
            .fillMaxWidth()
            .background(color = PocketShellColors.TermBg, shape = RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .let { base ->
                if (tooLong) base.heightIn(max = 240.dp) else base
            }
        // The vertical-scroll container is only needed when the body is
        // very long — otherwise the natural line height of the Text is
        // already constrained by the parent LazyColumn.
        val scrollState = rememberScrollState()
        val finalModifier = if (tooLong) {
            container.verticalScroll(scrollState)
        } else {
            container
        }
        Column(modifier = finalModifier) {
            Text(
                text = body,
                color = PocketShellColors.TermText,
                fontSize = 12.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun TmuxMoreMenu(
    expanded: Boolean,
    currentWindowId: String?,
    onDismiss: () -> Unit,
    onCreateSession: () -> Unit,
    onRenameSession: () -> Unit,
    onKillSession: () -> Unit,
    onSwitchSession: () -> Unit,
    onOpenJobs: () -> Unit,
    onOpenUsage: () -> Unit,
    onNewWindow: () -> Unit,
    onRenameWindow: () -> Unit,
    onKillWindow: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopEnd,
    ) {
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismiss,
        ) {
            // Per #158: visually group the destructive / scope-confusing
            // affordances so "session" and "window" aren't a flat
            // alphabet soup. Header rows are not clickable; the dividers
            // separate the three families: in-this-session window ops,
            // on-this-host session ops, and cross-host shortcuts.
            DropdownMenuSectionHeader(text = "In this session")
            DropdownMenuItem(text = { Text("+ New window") }, onClick = onNewWindow)
            DropdownMenuItem(
                text = { Text("Rename window") },
                onClick = onRenameWindow,
                enabled = currentWindowId != null,
            )
            DropdownMenuItem(
                text = { Text("Kill window") },
                onClick = onKillWindow,
                enabled = currentWindowId != null,
            )
            HorizontalDivider()
            DropdownMenuSectionHeader(text = "On this host")
            DropdownMenuItem(text = { Text("+ New session") }, onClick = onCreateSession)
            DropdownMenuItem(text = { Text("Switch session") }, onClick = onSwitchSession)
            DropdownMenuItem(text = { Text("Rename session") }, onClick = onRenameSession)
            DropdownMenuItem(text = { Text("Kill session") }, onClick = onKillSession)
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Recurring jobs") }, onClick = onOpenJobs)
            // Issue #114 Fix A: jump to the cross-host Usage / quota
            // panel from inside a live tmux session.
            DropdownMenuItem(text = { Text("Usage") }, onClick = onOpenUsage)
        }
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
 * Issue #184 Layer 2: IME-aware top chrome — the breadcrumb + tabs +
 * window-strip cluster that sits above the terminal viewport on the tmux
 * session screen. Exposed as its own composable so the chrome's behaviour
 * under the soft keyboard (Layer 2 of issue #184) can be unit-tested
 * without spinning up a Hilt graph or a live tmux connect — the test
 * drives [chromeCompressed] directly and asserts the right elements are
 * present / hidden.
 *
 * When [chromeCompressed] is `false` (IME hidden) we render:
 * - The full [Breadcrumb] with `host › session › window › pane` and the
 *   tagged [TMUX_FULL_BREADCRUMB_TAG] root.
 * - The [Tabs] row tagged with [TMUX_TABS_TAG].
 * - The [WindowStrip] (tagged [TMUX_WINDOW_STRIP_TAG]), but only when
 *   the session has more than one window — single-window sessions never
 *   render the strip, regardless of IME state.
 *
 * When [chromeCompressed] is `true` (IME visible) we replace the full
 * breadcrumb with [CompactBreadcrumb] (tagged
 * [TMUX_COMPACT_BREADCRUMB_TAG]) and hide both the tabs and the window
 * strip so the freed vertical space goes to the terminal viewport.
 * Transitions are wrapped in [AnimatedVisibility] with the 200ms motion
 * tokens from `docs/design-system.md` §5.
 *
 * The screen passes [chromeCompressed] from `WindowInsets.isImeVisible`;
 * tests pass it directly.
 */
@Composable
internal fun TmuxImeAwareTopChrome(
    chromeCompressed: Boolean,
    crumbs: List<Crumb>,
    sessionName: String,
    onBack: () -> Unit,
    onMore: () -> Unit,
    tabLabels: List<String>,
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    windows: List<WindowSummary>,
    currentWindowId: String?,
    onSelectWindow: (WindowSummary) -> Unit,
    onOpenWindowMenu: (WindowSummary) -> Unit,
    onNewWindow: () -> Unit,
) {
    val animEnter = expandVertically(
        animationSpec = tween(durationMillis = MotionDurationMs, easing = MotionEasing),
    ) + fadeIn(
        animationSpec = tween(durationMillis = MotionDurationMs, easing = MotionEasing),
    )
    val animExit = shrinkVertically(
        animationSpec = tween(durationMillis = MotionDurationMs, easing = MotionEasing),
    ) + fadeOut(
        animationSpec = tween(durationMillis = MotionDurationMs, easing = MotionEasing),
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        AnimatedVisibility(visible = !chromeCompressed, enter = animEnter, exit = animExit) {
            Breadcrumb(
                crumbs = crumbs,
                onBack = onBack,
                onMore = onMore,
                modifier = Modifier.testTag(TMUX_FULL_BREADCRUMB_TAG),
            )
        }
        AnimatedVisibility(visible = chromeCompressed, enter = animEnter, exit = animExit) {
            CompactBreadcrumb(
                sessionName = sessionName,
                onBack = onBack,
                onMore = onMore,
                modifier = Modifier.testTag(TMUX_COMPACT_BREADCRUMB_TAG),
            )
        }
        AnimatedVisibility(visible = !chromeCompressed, enter = animEnter, exit = animExit) {
            Tabs(
                labels = tabLabels,
                selectedIndex = selectedTabIndex,
                onSelected = onTabSelected,
                modifier = Modifier.testTag(TMUX_TABS_TAG),
            )
        }
        AnimatedVisibility(
            visible = windows.size > 1 && !chromeCompressed,
            enter = animEnter,
            exit = animExit,
        ) {
            WindowStrip(
                windows = windows,
                currentWindowId = currentWindowId,
                onSelectWindow = onSelectWindow,
                onOpenWindowMenu = onOpenWindowMenu,
                onNewWindow = onNewWindow,
            )
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
private fun CompactBreadcrumb(
    sessionName: String,
    onBack: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
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
                .size(36.dp)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "‹",
                color = PocketShellColors.TextSecondary,
                fontSize = 20.sp,
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = sessionName,
            color = PocketShellColors.Text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .size(36.dp)
                .clickable(onClick = onMore),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "⋮",
                color = PocketShellColors.TextSecondary,
                fontSize = 20.sp,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WindowStrip(
    windows: List<WindowSummary>,
    currentWindowId: String?,
    onSelectWindow: (WindowSummary) -> Unit,
    onOpenWindowMenu: (WindowSummary) -> Unit,
    onNewWindow: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = PocketShellColors.Surface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .testTag(TMUX_WINDOW_STRIP_TAG),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        windows.forEachIndexed { index, window ->
            val selected = window.windowId == currentWindowId
            Text(
                text = window.title,
                color = if (selected) PocketShellColors.Background else PocketShellColors.Text,
                fontSize = 12.sp,
                modifier = Modifier
                    .background(
                        color = if (selected) {
                            PocketShellColors.Accent
                        } else {
                            PocketShellColors.SurfaceElev
                        },
                    )
                    .combinedClickable(
                        role = androidx.compose.ui.semantics.Role.Tab,
                        onClick = { onSelectWindow(window) },
                        onLongClick = { onOpenWindowMenu(window) },
                    )
                    .padding(horizontal = 12.dp, vertical = 7.dp)
                    // Per #158: each pill carries a stable, unique
                    // test tag so the E2E test can address it without
                    // colliding with the breadcrumb crumb that also
                    // reads "Window N".
                    .testTag("${TMUX_WINDOW_STRIP_PILL_TAG_PREFIX}${index + 1}"),
            )
        }
        // Per #158: the trailing "+" used to be ambiguous (window vs
        // session). The visible label is now "+ window" so the user can
        // tell at a glance this affordance adds a window to the current
        // session — host-level "+ New session" lives in the drawer.
        TextButton(
            onClick = onNewWindow,
            modifier = Modifier.testTag(TMUX_NEW_WINDOW_BUTTON_TAG),
        ) {
            Text("+ window")
        }
    }
}

@Composable
private fun WindowContextMenu(
    window: WindowSummary,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onKill: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopEnd,
    ) {
        DropdownMenu(
            expanded = true,
            onDismissRequest = onDismiss,
        ) {
            // Per #158: use the readable window title ("Window 2") instead
            // of the cryptic `@N` tmux ID. The user reached this menu by
            // long-pressing the strip's "Window N" pill, so the label
            // here must match what they tapped.
            DropdownMenuItem(text = { Text("Rename ${window.title}") }, onClick = onRename)
            DropdownMenuItem(text = { Text("Kill ${window.title}") }, onClick = onKill)
        }
    }
}

@Composable
private fun TmuxLifecycleDialog(
    mode: TmuxDialogMode,
    sessionName: String,
    currentWindowId: String?,
    text: String,
    onTextChange: (String) -> Unit,
    startDirectory: String,
    onStartDirectoryChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val title = when (mode) {
        TmuxDialogMode.CreateSession -> "New session"
        TmuxDialogMode.RenameSession -> "Rename session"
        TmuxDialogMode.KillSession -> "Kill session"
        TmuxDialogMode.RenameWindow -> "Rename window"
        TmuxDialogMode.KillWindow -> "Kill window"
        is TmuxDialogMode.KillWindowFor -> "Kill window"
    }
    val confirm = when (mode) {
        TmuxDialogMode.KillSession,
        TmuxDialogMode.KillWindow,
        is TmuxDialogMode.KillWindowFor,
        -> "Kill"
        else -> "Save"
    }
    val isTextMode = mode == TmuxDialogMode.CreateSession ||
        mode == TmuxDialogMode.RenameSession ||
        mode == TmuxDialogMode.RenameWindow
    val isCreateMode = mode == TmuxDialogMode.CreateSession
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (isCreateMode) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = onTextChange,
                        singleLine = true,
                        label = { Text("Session name") },
                    )
                    OutlinedTextField(
                        value = startDirectory,
                        onValueChange = onStartDirectoryChange,
                        singleLine = true,
                        label = { Text("Start folder") },
                    )
                }
            } else if (isTextMode) {
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    singleLine = true,
                    label = { Text(if (mode == TmuxDialogMode.RenameWindow) "Window name" else "Session name") },
                )
            } else {
                val target = when (mode) {
                    TmuxDialogMode.KillSession -> sessionName
                    TmuxDialogMode.KillWindow -> currentWindowId.orEmpty()
                    is TmuxDialogMode.KillWindowFor -> mode.windowId
                    else -> ""
                }
                Text("This will close $target.")
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = isCreateMode || !isTextMode || text.trim().isNotEmpty(),
            ) {
                Text(confirm)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
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
