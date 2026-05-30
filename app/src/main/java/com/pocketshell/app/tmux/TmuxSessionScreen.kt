package com.pocketshell.app.tmux

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.pager.HorizontalPager
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.core.content.ContextCompat
import com.pocketshell.app.conversation.ConversationMessageTurn
import com.pocketshell.app.composer.PromptComposerSheet
import com.pocketshell.app.session.AgentConversationUiState
import com.pocketshell.app.session.InlineDictationViewModel
import com.pocketshell.app.session.KeyBarWithMic
import com.pocketshell.app.settings.SettingsViewModel
import com.pocketshell.app.session.SessionTab
import com.pocketshell.app.sessions.DEFAULT_TMUX_START_DIRECTORY
import com.pocketshell.app.sessions.HostTmuxSessionPickerRequest
import com.pocketshell.app.sessions.HostTmuxSessionPickerState
import com.pocketshell.app.sessions.HostTmuxSessionPickerViewModel
import com.pocketshell.app.sessions.HostTmuxSessionRow
import com.pocketshell.app.sessions.StartDirectoryAutocompleteController
import com.pocketshell.app.sessions.StartDirectoryAutocompleteField
import com.pocketshell.app.sessions.rememberStartDirectoryAutocompleteController
import com.pocketshell.app.sessions.resolveTmuxSessionCreation
import com.pocketshell.app.snippets.SnippetKind
import com.pocketshell.app.snippets.SnippetPickerSheet
import com.pocketshell.app.startup.StartupTiming
import com.pocketshell.app.tmux.TmuxSessionViewModel.ConnectionStatus
import com.pocketshell.app.voice.ADD_COMMAND_CHIP_LABEL
import com.pocketshell.app.voice.ADD_PROMPT_CHIP_LABEL
import com.pocketshell.app.voice.BottomChipControls
import com.pocketshell.app.voice.DefaultSessionChips
import com.pocketshell.app.voice.InlineDictationErrorStrip
import com.pocketshell.app.voice.AssistantStrip
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.agents.ToolCallSummary
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.terminal.ui.TerminalSurface
import com.pocketshell.core.terminal.ui.showTerminalSoftKeyboard
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
    /** Route an assistant-requested navigation (issue #266). */
    onAssistantNavigate: (com.pocketshell.app.nav.AppDestination) -> Unit = {},
    /**
     * Issue #116 (usage-panel Fix B): same per-host worst-case
     * [com.pocketshell.core.usage.UsageProviderRecord] surface as
     * [com.pocketshell.app.session.SessionScreen], but for the
     * `tmux -CC` route. MainActivity passes the lookup for the active
     * host id; `null` when no chip should render.
     */
    usageBadgeProvider: com.pocketshell.core.usage.UsageProviderRecord? = null,
    // Issue #177: composer-draft persistence for fast resume. The
    // restored draft (from the persisted last session) seeds the agent
    // composer once; edits are reported back up so the next `onStop`
    // persists them. Defaults keep direct callers / unit tests unchanged.
    initialComposerDraft: String = "",
    onInitialComposerDraftConsumed: () -> Unit = {},
    onComposerDraftChanged: (String) -> Unit = {},
    suggestStartDirectories: (suspend (String) -> List<String>)? = null,
    connectTrigger: TmuxConnectTrigger = TmuxConnectTrigger.UserTap,
) {
    LaunchedEffect(Unit) {
        StartupTiming.markOnce(
            "tmux-screen-composed",
            "hostId" to hostId,
            "session" to sessionName,
            "hasStartDirectory" to (startDirectory != null),
        )
    }
    LaunchedEffect(hostId, hostName, host, port, user, keyPath, passphrase, sessionName, startDirectory, connectTrigger) {
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
            trigger = connectTrigger,
        )
    }

    val panes by viewModel.panes.collectAsState()
    val status by viewModel.connectionStatus.collectAsState()
    // Issue #249: the single source of truth that gates the composer /
    // send / dictation path. Input only reaches the remote when the tmux
    // `-CC` control channel is live (`Connected`). While Connecting (the
    // background-detach reattach handshake of #177) or Failed (a dropped
    // socket) a chip tap / key press / dictation would be written into a
    // dead bridge and silently lost — exactly the data-loss the user
    // reported. We disable those affordances and surface a visible
    // "Reconnecting" / "Disconnected" pill instead.
    val sessionLive = status is ConnectionStatus.Connected
    // Issue #177: a one-shot holder for the restored composer draft. The
    // conversation pane mounts/unmounts as the user flips the Terminal ↔
    // Conversation tab, so we cannot rely on the pane's own `remember` to
    // consume the draft exactly once — we consume it here at the screen
    // level (returns the draft the first time, "" after) and notify the
    // activity it was consumed so a later launch won't re-seed it.
    var restoredDraftRemaining by rememberSaveable { mutableStateOf(initialComposerDraft) }
    val consumeRestoredDraft: () -> String = {
        val draft = restoredDraftRemaining
        if (draft.isNotEmpty()) {
            restoredDraftRemaining = ""
            onInitialComposerDraftConsumed()
        }
        draft
    }
    val startDirectoryAutocompleteController =
        rememberStartDirectoryAutocompleteController(suggestStartDirectories)
    val agentConversations by viewModel.agentConversations.collectAsState()
    val sessionPickerState by sessionPickerViewModel.state.collectAsState()
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
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val verticalSwipeThresholdPx = with(LocalDensity.current) { VerticalSwipeThreshold.toPx() }
    var moreExpanded by remember { mutableStateOf(false) }
    var windowMenuFor by remember { mutableStateOf<WindowSummary?>(null) }
    var dialogMode by remember { mutableStateOf<TmuxDialogMode?>(null) }
    var dialogText by remember { mutableStateOf("") }
    var dialogStartDirectory by remember { mutableStateOf(DEFAULT_TMUX_START_DIRECTORY) }
    var showWindowSwitcher by remember { mutableStateOf(false) }
    var showSessionSwitcher by remember { mutableStateOf(false) }
    var showSessionDrawer by remember { mutableStateOf(false) }
    // Voice/dictation surfaces — mirror SessionScreen so the tmux route
    // gets the prompt composer, the mic FAB, the inline-dictation key bar,
    // and the snippet picker. Without these the user can never dictate
    // into a tmux pane (see #123 — the primary user route was completely
    // dark for voice input).
    var showMicSheet by remember { mutableStateOf(false) }
    var showSnippetPicker by remember { mutableStateOf(false) }

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
        windowSwitcherOpen = showWindowSwitcher,
        micSheetOpen = showMicSheet,
        snippetPickerOpen = showSnippetPicker,
        onDismissDialog = { dialogMode = null },
        onDismissSessionDrawer = {
            showSessionSwitcher = false
            showSessionDrawer = false
            sessionPickerViewModel.dismiss()
        },
        onDismissWindowSwitcher = { showWindowSwitcher = false },
        onDismissMicSheet = { showMicSheet = false },
        onDismissSnippetPicker = { showSnippetPicker = false },
        onBack = onBack,
    )

    // Issue #131: same root-view handle as `SessionScreen`. The pager
    // renders one pane at a time, so the helper's recursive search lands
    // on the visible pane's `TerminalView`.
    val composeRootView = LocalView.current
    val context = LocalContext.current

    // Runtime RECORD_AUDIO permission flow for the inline-dictation path.
    // Mirrors SessionScreen so the tmux route uses the same right-side mic
    // affordance instead of falling back to a separate terminal-only path.
    val inlinePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            inlineDictationViewModel.onMicTap()
        } else {
            inlineDictationViewModel.surfacePermissionDenied()
        }
    }

    fun onInlineMicTap() {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            inlinePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (!inlineDictationViewModel.hasApiKey()) {
            showMicSheet = true
            return
        }
        inlineDictationViewModel.onMicTap()
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
    LaunchedEffect(isImeVisible) {
        if (isImeVisible) {
            com.pocketshell.core.terminal.ui.pinTerminalToBottom(composeRootView)
        }
    }

    // Issue #266: route assistant-requested navigation through the app nav.
    LaunchedEffect(viewModel) {
        viewModel.assistantNavRequests.collect { onAssistantNavigate(it) }
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

    @Composable
    fun AnchoredTmuxMoreMenu() {
        TmuxMoreMenu(
            expanded = moreExpanded,
            currentWindowId = currentWindowId,
            multipleWindows = windows.size > 1,
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
            onSwitchWindow = {
                moreExpanded = false
                showWindowSwitcher = true
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
                .fillMaxSize()
                .imePadding(),
        ) {
            // Issue #256: gate the inline Conversation tab on the
            // currently visible pane only. A sibling pane/window's agent
            // no longer keeps Conversation mounted or routes sends away
            // from what the user is looking at.
            val tabState = tmuxSessionTabState(currentAgentConversation)
            val onTabSelected: (Int) -> Unit = { index ->
                if (index == 1) {
                    currentPane
                        ?.takeIf { agentConversations[it.paneId]?.detection != null }
                        ?.let { pane ->
                        viewModel.selectSessionTab(pane.paneId, SessionTab.Conversation)
                    }
                } else {
                    currentPane?.let { pane ->
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
                                hostLabel = host,
                                sessionName = sessionName,
                                tabLabels = tabState.labels,
                                selectedTabIndex = tabState.selectedIndex,
                                onTabSelected = onTabSelected,
                                pulseConversationTab = tabState.showsConversationTab,
                                onBack = onBack,
                                onMore = { moreExpanded = true },
                                moreMenu = { AnchoredTmuxMoreMenu() },
                                connectionStatus = status.toUiStatus(),
                                modifier = Modifier.testTag(TMUX_FULL_BREADCRUMB_TAG),
                            )
                            // Issue #192 / #156: per-window nav strip is
                            // the primary window switcher. Rendered only
                            // when there is more than one window — a
                            // single-window session has nothing to switch
                            // to, so the strip would be pure chrome. Kill
                            // / rename live on the pill itself (long-press
                            // menu + explicit ✕ on the active pill).
                            if (windows.size > 1) {
                                WindowStrip(
                                    windows = windows,
                                    currentWindowId = currentWindowId,
                                    onSelectWindow = ::selectWindow,
                                    onOpenWindowMenu = { window -> windowMenuFor = window },
                                    onKillWindow = { window ->
                                        dialogMode = TmuxDialogMode.KillWindowFor(window.windowId)
                                    },
                                    onNewWindow = { viewModel.newWindow() },
                                )
                            }
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

            // Issue #192: the [WindowStrip] (rendered above with the top
            // chrome when windows.size > 1) is the primary window
            // switcher and the home of the kill / rename affordances.
            // While the IME is up the strip is collapsed, so two fallback
            // paths stay available for switching windows mid-type:
            //  1. The page-indicator swipe-up gesture further down this
            //     Column (opens the WindowSwitcherOverlay).
            //  2. The kebab menu's "Switch window" item, surfaced only
            //     when windows.size > 1.

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
            val visibleConversation = currentPane?.paneId?.let { agentConversations[it] }
            val showConversation = currentPane != null &&
                visibleConversation?.detection != null &&
                visibleConversation.selectedTab == SessionTab.Conversation
            Box(
                modifier = Modifier
                    .weight(1f),
            ) {
                if (showConversation) {
                    val paneIdForSend = currentPane!!.paneId
                    TmuxConversationPane(
                        events = visibleConversation.events,
                        onSendToAgent = { text ->
                            viewModel.sendToAgentPane(paneIdForSend, text)
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag(TMUX_CONVERSATION_PANE_TAG),
                        showSystemNotes = appSettings.showSystemNotes,
                        // Issue #154 (acceptance criterion #5): hoist
                        // the search query into the ViewModel state so
                        // toggling to the Terminal tab and back does
                        // not clear the user's filter.
                        query = visibleConversation.searchQuery,
                        onQueryChange = { next ->
                            viewModel.setAgentSearchQuery(paneIdForSend, next)
                        },
                        // Issue #249: don't let the agent composer deliver
                        // -then-clear a message while the session is down.
                        sendEnabled = sessionLive,
                        // Issue #177: seed the restored draft once (the
                        // holder consumes it on first read so a tab toggle
                        // that re-mounts the pane doesn't re-seed a stale
                        // draft), and report edits up for re-persistence.
                        initialDraft = consumeRestoredDraft(),
                        onDraftChanged = onComposerDraftChanged,
                        agentName = visibleConversation.detection.agent.displayName,
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
                            // Issue #240: cache the phone grid so the
                            // view model can compare it with tmux's
                            // current window size and offer an explicit
                            // Resize prompt instead of resizing
                            // automatically on attach.
                            onTerminalSizeChanged = viewModel::resizeRemotePty,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 2.dp, vertical = 4.dp),
                        )
                    }
                }
            }

            // Voice-related strips sit above the input band so they remain
            // visible while the IME is up. Tapping the error strip clears
            // it; the planner review strip's buttons route through the
            // currently focused pane.
            dictationState.error?.let { msg ->
                InlineDictationErrorStrip(msg, onDismiss = inlineDictationViewModel::clearError)
            }
            AssistantStrip(
                state = assistantState,
                onConfirm = viewModel::confirmAssistantAction,
                onCorrect = viewModel::correctAssistantAction,
                onCancel = viewModel::cancelAssistantAction,
                onDismiss = viewModel::dismissAssistant,
            )

            if (isImeVisible && currentPane != null) {
                KeyBarWithMic(
                    keys = TmuxKeyBarLayout,
                    onKey = if (sessionLive) {
                        { binding -> viewModel.onKeyBarKey(currentPane.paneId, binding.label) }
                    } else {
                        { _ -> }
                    },
                    micState = dictationState.recording,
                    micAmplitude = dictationState.amplitude,
                    dictationMode = dictationState.mode,
                    onDictationModeSelected = inlineDictationViewModel::selectMode,
                    onMicTap = ::onInlineMicTap,
                    inputEnabled = sessionLive,
                )
            } else if (!isImeVisible && currentPane != null) {
                val isAgentPane = currentAgentConversation?.detection != null
                BottomChipControls(
                    chips = if (isAgentPane) AgentExitChips else DefaultSessionChips,
                    onChipTap = { chip ->
                        currentPane?.let { pane ->
                            when (chip) {
                                CtrlC2Chip -> viewModel.sendControlInputToPane(
                                    pane.paneId,
                                    CtrlCByte,
                                    repeatCount = 2,
                                )
                                CtrlD2Chip -> viewModel.sendControlInputToPane(
                                    pane.paneId,
                                    CtrlDByte,
                                    repeatCount = 2,
                                )
                                else -> {
                                    // Chip taps run literal commands in the
                                    // focused pane. Mirrors
                                    // `SessionViewModel.onChipTap` which
                                    // appends a CR; the tmux input bridge
                                    // translates the trailing CR into a named
                                    // `Enter` key.
                                    viewModel.writeInputToPane(
                                        pane.paneId,
                                        (chip + "\r").toByteArray(Charsets.UTF_8),
                                    )
                                }
                            }
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
                    addSnippetLabel = if (isAgentPane) ADD_PROMPT_CHIP_LABEL else ADD_COMMAND_CHIP_LABEL,
                    addSnippetIcon = null,
                    // Project navigation on tmux panes is a separate
                    // follow-up — see #123 notes on per-pane cwd /
                    // project-root wiring.
                    onProjectNavigationTap = null,
                    // Issue #249: gate chips on liveness.
                    inputEnabled = sessionLive,
                    modifier = Modifier.navigationBarsPadding(),
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
                    openTextDialog(TmuxDialogMode.RenameWindowFor(window.windowId), window.windowId)
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
                startDirectoryAutocompleteController = startDirectoryAutocompleteController,
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
                        is TmuxDialogMode.RenameWindowFor -> {
                            viewModel.renameWindow(currentMode.windowId, dialogText)
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
                sessionPickerViewModel.dismiss()
                openTextDialog(TmuxDialogMode.CreateSession)
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
                sessionPickerViewModel.dismiss()
                openTextDialog(TmuxDialogMode.CreateSession)
            },
        )
    }

    LaunchedEffect(showSessionSwitcher, showSessionDrawer, sessionPickerRequest) {
        if (showSessionSwitcher || showSessionDrawer) {
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
                // Issue #249: if the session dropped while the composer
                // sheet was open, do NOT write into the dead pane and do
                // NOT dismiss — keep the sheet (and the user's text) so
                // nothing is lost; the user re-sends once reconnected.
                if (sessionLive) {
                    currentPane?.let { pane ->
                        val payload = if (withEnter) text + "\r" else text
                        viewModel.writeInputToPane(
                            pane.paneId,
                            payload.toByteArray(Charsets.UTF_8),
                        )
                    }
                    showMicSheet = false
                }
            },
            hostId = hostId.takeIf { it != 0L },
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
                if (sessionLive) {
                    currentPane?.let { pane ->
                        val payload = if (withEnter) snippet.body + "\r" else snippet.body
                        viewModel.writeInputToPane(
                            pane.paneId,
                            payload.toByteArray(Charsets.UTF_8),
                        )
                    }
                    showSnippetPicker = false
                }
            },
        )
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
                                shape = RoundedCornerShape(8.dp),
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
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Issue #156 (4.1 / 4.2): group the host-scoped
                    // actions in a labelled "Options" card so they read
                    // as a distinct family from the available-sessions
                    // list below — not a flat stack of buttons.
                    item { TmuxSessionDrawerSectionHeader(text = "Options") }
                    item {
                        TmuxSessionDrawerOptionsCard(
                            onCreate = onCreate,
                            onRefresh = onRefresh,
                        )
                    }
                    item {
                        TmuxSessionDrawerSectionHeader(text = "Available sessions")
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
        fontSize = 13.sp,
        modifier = Modifier
            .fillMaxWidth()
            .background(PocketShellColors.SurfaceElev, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp),
    )
}

/**
 * Issue #156 (4.1 / 4.2): a small uppercase-ish section label that
 * groups the session drawer into "Options" and "Available sessions"
 * families so the hierarchy reads at a glance.
 */
@Composable
private fun TmuxSessionDrawerSectionHeader(text: String) {
    Text(
        text = text,
        color = PocketShellColors.TextSecondary,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
    )
}

/**
 * Issue #156 (4.2): the host-scoped actions ("+ New session" and
 * "Refresh sessions") grouped into one bordered card so they read as a
 * distinct "what can I do here" cluster, visually separated from the
 * tappable session rows below.
 */
@Composable
private fun TmuxSessionDrawerOptionsCard(
    onCreate: () -> Unit,
    onRefresh: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PocketShellColors.SurfaceElev, RoundedCornerShape(8.dp))
            .border(width = 1.dp, color = PocketShellColors.BorderSoft, shape = RoundedCornerShape(8.dp)),
    ) {
        TmuxSessionDrawerOptionRow(
            label = "+ New session",
            // Per #158: a tmux session is a separate workspace owned by
            // the remote tmux server — distinct from "+ window" inside
            // the current session. Spell that out so the user doesn't
            // fork a workspace when they meant to add a window.
            sublabel = "Separate workspace on this host",
            onClick = onCreate,
            modifier = Modifier.testTag(TMUX_SESSION_DRAWER_CREATE_TAG),
        )
        HorizontalDivider(color = PocketShellColors.BorderSoft)
        TmuxSessionDrawerOptionRow(
            label = "Refresh sessions",
            sublabel = null,
            onClick = onRefresh,
            modifier = Modifier.testTag(TMUX_SESSION_DRAWER_REFRESH_TAG),
        )
    }
}

@Composable
private fun TmuxSessionDrawerOptionRow(
    label: String,
    sublabel: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                role = androidx.compose.ui.semantics.Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            color = PocketShellColors.Accent,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
        if (sublabel != null) {
            Text(
                text = sublabel,
                color = PocketShellColors.TextSecondary,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun TmuxSessionDrawerRow(
    row: HostTmuxSessionRow,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = if (selected) PocketShellColors.Accent else PocketShellColors.SurfaceElev,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(
                enabled = enabled,
                role = androidx.compose.ui.semantics.Role.Button,
                onClick = onClick,
            )
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

internal fun tmuxSessionTabState(
    currentAgentConversation: AgentConversationUiState?,
): TmuxSessionTabState {
    val hasAgent = currentAgentConversation?.detection != null
    return TmuxSessionTabState(
        labels = if (hasAgent) listOf("Terminal", "Conversation") else listOf("Terminal"),
        selectedIndex = if (hasAgent && currentAgentConversation.selectedTab == SessionTab.Conversation) 1 else 0,
        showsConversationTab = hasAgent,
    )
}

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
    data class RenameWindowFor(val windowId: String) : TmuxDialogMode
    data object KillWindow : TmuxDialogMode
    data class KillWindowFor(val windowId: String) : TmuxDialogMode
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
internal const val TMUX_CONVERSATION_COMPOSER_INPUT_TAG = "tmux:conversation:composer-input"
internal const val TMUX_CONVERSATION_COMPOSER_SEND_TAG = "tmux:conversation:composer-send"
internal const val TMUX_CONVERSATION_TOOL_ROW_TAG_PREFIX = "tmux:conversation:tool:"
/** Issue #176: stable test tag prefix for a `SystemNote` row in the tmux conversation pane. */
internal const val TMUX_CONVERSATION_SYSTEM_NOTE_ROW_TAG_PREFIX = "tmux:conversation:system-note:"
/**
 * Issue #154: stable test tags for the conversation navigation polish:
 * - [TMUX_CONVERSATION_SEARCH_TAG] is on the search field inside
 *   [TmuxConversationPane]. The connected `TmuxConversationPaneNavigationUiTest`
 *   uses it to type a query, then asserts the value survives a tab
 *   round-trip.
 * - [TMUX_CONVERSATION_JUMP_TO_LATEST_TAG] is on the "↓ Latest"
 *   affordance pinned to the bottom-end of the conversation list when
 *   the user has scrolled away from the tail. The button hides itself
 *   when the user is already at the bottom.
 */
internal const val TMUX_CONVERSATION_SEARCH_TAG =
    "tmux:conversation:search"
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
/** Issue #116: stable test tag for the in-tmux-session blocked / near-limit chip. */
internal const val TMUX_SESSION_USAGE_BADGE_TAG = "tmux:usage-badge"

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
 * Issue #235: stable test tag for the kebab menu's Detach item. The
 * connected `TmuxDetachOnBackgroundE2eTest` does NOT click this tag —
 * it drives the lifecycle path via `UiDevice.pressHome` — but the
 * symmetric "manual detach + return to dashboard" assertion in the
 * same suite needs a deterministic handle on the menu item.
 */
internal const val TMUX_DETACH_BUTTON_TAG = "tmux:session:detach-button"
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
/**
 * Issue #192: prefix for the explicit ✕ kill affordance on the active
 * window-strip pill. 1-based to mirror
 * [TMUX_WINDOW_STRIP_PILL_TAG_PREFIX] — only the currently-selected
 * window's pill renders one, so exactly one tag in this family exists at
 * a time. Tapping it opens the kill-window confirmation for that window.
 */
internal const val TMUX_WINDOW_STRIP_KILL_TAG_PREFIX = "tmux:window-strip-kill:"
/** Issue #158: trailing "+ window" button inside the WindowStrip. */
internal const val TMUX_NEW_WINDOW_BUTTON_TAG = "tmux:new-window-button"
/**
 * Issue #189: stable test tag for the WindowSwitcherOverlay root, and
 * a prefix for per-page tap-targets inside the overlay's HorizontalPager.
 * Now that the WindowStrip is hidden in the default chrome, the overlay
 * is the primary tap-driven window switcher; the E2E navigation test
 * drives it via [TMUX_WINDOW_SWITCHER_PAGE_TAG_PREFIX] entries (1-based
 * to mirror the previous strip-pill convention).
 */
internal const val TMUX_WINDOW_SWITCHER_OVERLAY_TAG = "tmux:window-switcher"
internal const val TMUX_WINDOW_SWITCHER_PAGE_TAG_PREFIX = "tmux:window-switcher-page:"
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
                fontSize = 13.sp,
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
                fontSize = 11.sp,
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
                .padding(16.dp)
                .testTag(TMUX_WINDOW_SWITCHER_OVERLAY_TAG),
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
                    // Issue #216: compose every page so the semantic
                    // tree always contains all per-page tags. Without
                    // this, [HorizontalPager]'s default
                    // `beyondViewportPageCount = 0` only composes the
                    // currently-visible page; tests that drive the
                    // overlay by tapping `${TMUX_WINDOW_SWITCHER_PAGE_TAG_PREFIX}N`
                    // would race the swipe-and-settle path. Each page
                    // is a single Column with two short Text labels
                    // (<200 bytes), so composing all pages eagerly is
                    // a negligible memory cost even for tmux sessions
                    // with many windows (typical 2–10). It also makes
                    // the overlay swipe feel instant since no adjacent
                    // page has to compose mid-gesture.
                    beyondViewportPageCount = Int.MAX_VALUE,
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
                            .padding(16.dp)
                            // Issue #189: per-window stable test tag so
                            // the E2E switch test can address Window 2
                            // directly without depending on horizontal
                            // pager swipe gymnastics. Index is 1-based
                            // to match TMUX_WINDOW_STRIP_PILL_TAG_PREFIX.
                            .testTag("$TMUX_WINDOW_SWITCHER_PAGE_TAG_PREFIX${page + 1}"),
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
    // Issue #154 (acceptance criterion #5): hoist the search query so
    // it survives Terminal ↔ Conversation tab switches. The screen
    // wires these to the per-pane `AgentConversationUiState.searchQuery`
    // / `TmuxSessionViewModel.setAgentSearchQuery`. The defaults keep
    // direct callers (unit tests, the connected E2E test) running with
    // the previous "local remember" behaviour: when [onQueryChange] is
    // the no-op default we fall back to an internal `remember` so the
    // search field still types. See [rememberHoistedQuery] below.
    query: String = "",
    onQueryChange: (String) -> Unit = NoOpStringChange,
    // Issue #249: gate the "Send" button on whether the SSH/tmux session
    // is live. When false the button is disabled so a tap cannot route a
    // message into a dead pane and then clear the composer — the draft
    // stays in [composerText] (a `remember`, so it survives the
    // disconnected -> reconnect recomposition) and the user re-sends once
    // the session is live again. Defaults to true so direct callers
    // (unit tests, the connected E2E) keep the always-enabled behaviour.
    sendEnabled: Boolean = true,
    // Issue #177: seed the composer with a draft restored from a
    // persisted session, and report draft edits up so the activity can
    // re-persist them on the next `onStop`. The default empty seed +
    // no-op reporter keep direct callers unchanged.
    initialDraft: String = "",
    onDraftChanged: (String) -> Unit = {},
    agentName: String = "agent",
) {
    val (effectiveQuery, onEffectiveQueryChange) = rememberHoistedQuery(query, onQueryChange)
    var composerText by rememberSaveable { mutableStateOf(initialDraft) }
    val visibleEvents = remember(events, showSystemNotes) {
        if (showSystemNotes) events else events.filterNot { it is ConversationEvent.SystemNote }
    }
    val filteredEvents = remember(visibleEvents, effectiveQuery) {
        val q = effectiveQuery.trim()
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

    // Issue #154 (acceptance criteria #1 & #3): tail-follow + jump-to-latest.
    // The list state owns scroll position; we read it via snapshots so
    // recomposition tracks both the user's manual scroll and the
    // auto-scroll fired by new events. `atBottom` is derived once per
    // change rather than recomputed inside the LazyColumn item lambda.
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val atBottom by remember(filteredEvents) {
        derivedStateOf { listState.isScrolledToBottom(filteredEvents.size) }
    }
    // Auto-scroll on new events when the user is at the bottom. The
    // effect is keyed on `filteredEvents.size`, not the full list, so a
    // tail-follow scroll fires once per new event rather than on every
    // recomposition. If the user has scrolled up, we leave their
    // position alone and surface the jump-to-latest FAB instead.
    LaunchedEffect(filteredEvents.size) {
        if (filteredEvents.isEmpty()) return@LaunchedEffect
        if (atBottom) {
            listState.scrollToItem(filteredEvents.size - 1)
        }
    }

    Column(
        modifier = modifier
            .background(color = PocketShellColors.Background)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        OutlinedTextField(
            value = effectiveQuery,
            onValueChange = onEffectiveQueryChange,
            placeholder = { Text("Search in conversation") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TMUX_CONVERSATION_SEARCH_TAG),
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
            JumpToLatestOverlay(
                visible = !atBottom && filteredEvents.isNotEmpty(),
                onClick = {
                    coroutineScope.launch {
                        listState.animateScrollToItem(filteredEvents.size - 1)
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = 12.dp),
            )
        }
        AgentComposerRow(
            text = composerText,
            onTextChange = {
                composerText = it
                onDraftChanged(it)
            },
            onSend = {
                val trimmed = composerText.trim()
                if (trimmed.isNotEmpty()) {
                    onSendToAgent(trimmed)
                    composerText = ""
                    onDraftChanged("")
                }
            },
            sendEnabled = sendEnabled,
            agentName = agentName,
        )
    }
}

/**
 * Issue #154 (acceptance criterion #5): when callers wire the conversation
 * pane to a real hoisted `(query, onQueryChange)` pair the pane uses those
 * values directly. When callers leave the defaults in place — which is the
 * case for the connected `ConversationInteractE2eTest` and the
 * `TmuxConversationSendTargetUiTest` that exist before this change — we
 * keep the previous local-`remember` behaviour so the search field still
 * types into something. The sentinel [NoOpStringChange] is identity-checked
 * (not value-checked) so a caller that wires a real lambda is always
 * treated as hoisted, even if their lambda happens to be empty for a frame.
 */
@Composable
private fun rememberHoistedQuery(
    hoistedQuery: String,
    onHoistedChange: (String) -> Unit,
): Pair<String, (String) -> Unit> {
    if (onHoistedChange !== NoOpStringChange) {
        return hoistedQuery to onHoistedChange
    }
    var local by remember { mutableStateOf("") }
    return local to { next: String -> local = next }
}

private val NoOpStringChange: (String) -> Unit = {}

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
    val shape = RoundedCornerShape(20.dp)
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
    ConversationMessageTurn(event = event)
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
    // Issue #249: extra gate on top of the non-blank check so a
    // disconnected session can't deliver-then-clear the draft.
    sendEnabled: Boolean = true,
    agentName: String = "agent",
) {
    // Issue #196: the agent-pane composer now uses the shared
    // [com.pocketshell.app.composer.AgentComposerSurface] so it has the
    // identical styled draft box (surface-elev fill, accent cursor, muted
    // placeholder) and the identical accent primary Send button as the
    // terminal-shell prompt composer. The surface-specific test tags keep
    // the existing tmux connected tests resolving the same nodes.
    com.pocketshell.app.composer.AgentComposerSurface(
        value = text,
        onValueChange = onTextChange,
        onSend = onSend,
        inputFieldTag = TMUX_CONVERSATION_COMPOSER_INPUT_TAG,
        sendButtonTag = TMUX_CONVERSATION_COMPOSER_SEND_TAG,
        sendEnabled = sendEnabled,
        placeholder = "Message ${agentName.ifBlank { "agent" }}",
    )
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
internal fun TmuxMoreMenu(
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
    // Issue #235: user-driven detach. Tears the `-CC` control client
    // down (server-clean — uses the same `detach-client` round-trip
    // [TmuxSessionViewModel.detachAndExit] runs internally) and pops
    // back to the sessions dashboard. The session itself stays alive
    // on the remote; reattach via the normal sessions-list path.
    onDetach: () -> Unit = {},
    // Issue #189: now that the WindowStrip is hidden in the default
    // chrome, the kebab is one of the discoverable paths into the
    // window switcher. Surfaced only when the session actually has
    // more than one window — single-window sessions get nothing to
    // switch to.
    multipleWindows: Boolean = false,
    onSwitchWindow: () -> Unit = {},
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
        if (multipleWindows) {
            // Issue #189: WindowStrip is gone, so the kebab is one
            // of the three paths into the switcher. Only shown when
            // there is actually somewhere to switch to.
            DropdownMenuItem(
                text = { Text("Switch window") },
                onClick = onSwitchWindow,
            )
        }
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
        // Issue #235: explicit "I'm done with this session for now"
        // affordance — frees the tmux server-side window-size lock
        // (max(phone, desktop) -> desktop dimensions) without
        // killing the session. Placed at the top of the cross-host
        // section so it sits next to the back-to-host-list mental
        // model (Detach -> sessions dashboard) without crowding the
        // destructive Kill session item.
        DropdownMenuItem(
            text = { Text("Detach") },
            onClick = onDetach,
            modifier = Modifier.testTag(TMUX_DETACH_BUTTON_TAG),
        )
        DropdownMenuItem(text = { Text("Recurring jobs") }, onClick = onOpenJobs)
        // Issue #114 Fix A: jump to the cross-host Usage / quota
        // panel from inside a live tmux session.
        DropdownMenuItem(text = { Text("Usage") }, onClick = onOpenUsage)
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
 * Per the #192 information-architecture rework the window is the user's
 * primary mental unit, so window switching lives on the dedicated
 * [WindowStrip] row below this chrome — NOT inside this breadcrumb. The
 * Terminal/Conversation toggle remains per-window in state, but issue
 * #303 renders it inline here so it does not cost a separate row.
 *
 * Layout (left → right inside one 56dp [Row]):
 * - 48dp back affordance (chevron).
 * - connection status dot + compact "Reconnecting"/"Disconnected" pill.
 * - `session` crumb (current destination; non-interactive) taking the
 *   remaining width.
 * - optional inline Terminal/Conversation pill when an agent or locked
 *   conversation is available.
 * - 48dp more affordance (kebab), which owns the dropdown anchor.
 *
 * The host segment is intentionally not surfaced — the host name is
 * already visible on the host list, the pre-session status line, and on
 * [StatusLine]/[FailedConnectionRow] when not connected.
 */
@Composable
internal fun ConsolidatedTopChrome(
    hostLabel: String,
    sessionName: String,
    onBack: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    moreMenu: @Composable () -> Unit = {},
    tabLabels: List<String> = emptyList(),
    selectedTabIndex: Int = 0,
    onTabSelected: (Int) -> Unit = {},
    pulseConversationTab: Boolean = false,
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
            .padding(start = 4.dp, end = 8.dp),
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
        Spacer(modifier = Modifier.width(6.dp))
        ConnectionStatusPill(connectionStatus)

        // Session crumb (current destination). Takes the remaining width
        // so the kebab sits flush right.
        Text(
            text = sessionName,
            color = PocketShellColors.Text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp)
                .testTag(TMUX_CONSOLIDATED_SESSION_LABEL_TAG),
        )

        if (tabLabels.size > 1) {
            Spacer(modifier = Modifier.width(4.dp))
            TabsRowWithPulse(pulseVisible = pulseConversationTab) {
                ConsolidatedTabPill(
                    labels = tabLabels,
                    selectedIndex = selectedTabIndex,
                    onSelected = onTabSelected,
                    modifier = Modifier.testTag(TMUX_TABS_TAG),
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
        }

        Box(modifier = Modifier.size(48.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onMore)
                    .testTag(TMUX_FULL_CHROME_MORE_BUTTON_TAG),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "⋮",
                    color = PocketShellColors.TextSecondary,
                    fontSize = 20.sp,
                )
            }
            moreMenu()
        }
        // Reference [hostLabel] so the parameter does not show as
        // unused — kept on the API for forward compatibility (and so
        // the screen can revive a host crumb segment cheaply if the
        // user feedback ever asks for it back).
        @Suppress("UNUSED_EXPRESSION") hostLabel
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
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = modifier
            .height(32.dp)
            .background(color = PocketShellColors.SurfaceElev, shape = shape)
            .border(width = 1.dp, color = PocketShellColors.BorderSoft, shape = shape)
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            // Issue #216: index 0 is conventionally the Terminal segment.
            // Apply [TMUX_TERMINAL_TAB_TAG] as a named alias for tests that
            // previously asserted on the visible "Terminal" text (now
            // suppressed in single-tab shell-only sessions); the per-index
            // prefix tag stays as the generic per-segment hook.
            val segmentTag = if (index == 0) {
                TMUX_TERMINAL_TAB_TAG
            } else {
                TMUX_CONSOLIDATED_TAB_PILL_TAG_PREFIX + index
            }
            Box(
                modifier = Modifier
                    .background(
                        color = if (selected) PocketShellColors.Accent else PocketShellColors.SurfaceElev,
                        shape = RoundedCornerShape(8.dp),
                    )
                    .clickable(
                        role = androidx.compose.ui.semantics.Role.Tab,
                        onClick = { onSelected(index) },
                    )
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .testTag(segmentTag),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = if (selected) PocketShellColors.Background else PocketShellColors.TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                )
            }
        }
    }
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
    windowSwitcherOpen: Boolean,
    micSheetOpen: Boolean,
    snippetPickerOpen: Boolean,
    onDismissDialog: () -> Unit,
    onDismissSessionDrawer: () -> Unit,
    onDismissWindowSwitcher: () -> Unit,
    onDismissMicSheet: () -> Unit,
    onDismissSnippetPicker: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler {
        when {
            dialogOpen -> onDismissDialog()
            sessionDrawerOpen -> onDismissSessionDrawer()
            windowSwitcherOpen -> onDismissWindowSwitcher()
            micSheetOpen -> onDismissMicSheet()
            snippetPickerOpen -> onDismissSnippetPicker()
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
        Text(
            text = sessionName,
            color = PocketShellColors.Text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        ConnectionStatusPill(connectionStatus)
        Spacer(modifier = Modifier.width(4.dp))
        Box(
            modifier = Modifier
                .width(48.dp)
                .fillMaxHeight(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onMore)
                    .testTag(TMUX_COMPACT_CHROME_MORE_BUTTON_TAG),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "⋮",
                    color = PocketShellColors.TextSecondary,
                    fontSize = 20.sp,
                )
            }
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
        is ConnectionStatus.Connecting -> com.pocketshell.uikit.model.ConnectionStatus.Connecting
        is ConnectionStatus.Failed -> com.pocketshell.uikit.model.ConnectionStatus.Error
        ConnectionStatus.Idle -> com.pocketshell.uikit.model.ConnectionStatus.Idle
    }

/**
 * Issues #192 / #156: the per-window navigation strip — the primary
 * window switcher on the tmux session screen.
 *
 * Each window is a rounded pill (Chrome-tab pattern). The active pill
 * carries an explicit ✕ that opens the kill-window confirmation in one
 * tap; every pill also long-presses into a [WindowContextMenu] for
 * Rename / Kill. So the kill control is always ≤ 1 tap (active pill ✕)
 * or 1 tap-and-hold (any pill) away from the window itself — never two
 * levels up in the kebab (#192).
 *
 * Issue #156 finding 6.1: the strip scrolls horizontally, so with many
 * windows the rightmost pills fall off-screen. We draw a right-edge
 * fade gradient ([Modifier.rightEdgeFade]) as the overflow indicator
 * that signals "scroll right for more" whenever the content extends
 * beyond the viewport.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun WindowStrip(
    windows: List<WindowSummary>,
    currentWindowId: String?,
    onSelectWindow: (WindowSummary) -> Unit,
    onOpenWindowMenu: (WindowSummary) -> Unit,
    onKillWindow: (WindowSummary) -> Unit,
    onNewWindow: () -> Unit,
) {
    val scrollState = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = PocketShellColors.Surface)
            // Issue #156 (6.1): right-edge fade signals horizontal
            // overflow. Drawn on the clipping container (before the
            // scroll modifier) so the fade stays pinned to the viewport
            // edge while the pills scroll underneath it.
            .rightEdgeFade(active = scrollState.canScrollForward)
            .horizontalScroll(scrollState)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .testTag(TMUX_WINDOW_STRIP_TAG),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        windows.forEachIndexed { index, window ->
            val selected = window.windowId == currentWindowId
            val pillShape = RoundedCornerShape(8.dp)
            Row(
                modifier = Modifier
                    .background(
                        color = if (selected) {
                            PocketShellColors.Accent
                        } else {
                            PocketShellColors.SurfaceElev
                        },
                        shape = pillShape,
                    )
                    .combinedClickable(
                        role = androidx.compose.ui.semantics.Role.Tab,
                        onClick = { onSelectWindow(window) },
                        onLongClick = { onOpenWindowMenu(window) },
                    )
                    .padding(start = 12.dp, end = if (selected) 4.dp else 12.dp, top = 5.dp, bottom = 5.dp)
                    // Per #158: each pill carries a stable, unique
                    // test tag so the E2E test can address it without
                    // colliding with the breadcrumb crumb that also
                    // reads "Window N".
                    .testTag("${TMUX_WINDOW_STRIP_PILL_TAG_PREFIX}${index + 1}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = window.title,
                    color = if (selected) PocketShellColors.Background else PocketShellColors.Text,
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                )
                // Issue #192: explicit ✕ on the active pill — the
                // Chrome-tab close pattern. One tap opens the
                // kill-window confirmation for THIS window. Only the
                // active pill carries it so the strip stays readable;
                // inactive windows are killed via the long-press menu.
                if (selected) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clickable(
                                role = androidx.compose.ui.semantics.Role.Button,
                                onClick = { onKillWindow(window) },
                            )
                            .testTag("${TMUX_WINDOW_STRIP_KILL_TAG_PREFIX}${index + 1}"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "×",
                            color = PocketShellColors.Background,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
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

/**
 * Issue #156 (6.1): right-edge fade-out gradient used as the
 * window-strip overflow indicator. When [active] is true (the strip can
 * still scroll forward, i.e. more pills exist past the right edge) the
 * rightmost ~28dp of the row fades into the strip background so the user
 * reads "there is more to the right". The fade is painted with
 * [BlendMode.DstIn] over the already-drawn content so it darkens the
 * pills toward transparent instead of overpainting a solid band; an
 * offscreen compositing layer keeps the blend scoped to this element.
 */
private fun Modifier.rightEdgeFade(active: Boolean): Modifier =
    if (!active) {
        this
    } else {
        this
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithContent {
                drawContent()
                val fadeWidth = 28.dp.toPx().coerceAtMost(size.width)
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color.Black, Color.Transparent),
                        startX = size.width - fadeWidth,
                        endX = size.width,
                    ),
                    blendMode = BlendMode.DstIn,
                )
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
    startDirectoryAutocompleteController: StartDirectoryAutocompleteController? = null,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val title = when (mode) {
        TmuxDialogMode.CreateSession -> "New session"
        TmuxDialogMode.RenameSession -> "Rename session"
        TmuxDialogMode.KillSession -> "Kill session"
        TmuxDialogMode.RenameWindow -> "Rename window"
        is TmuxDialogMode.RenameWindowFor -> "Rename window"
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
    val isRenameWindowMode = mode == TmuxDialogMode.RenameWindow ||
        mode is TmuxDialogMode.RenameWindowFor
    val isTextMode = mode == TmuxDialogMode.CreateSession ||
        mode == TmuxDialogMode.RenameSession ||
        isRenameWindowMode
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
                    StartDirectoryAutocompleteField(
                        value = startDirectory,
                        onValueChange = onStartDirectoryChange,
                        label = { Text("Start folder") },
                        autocompleteController = startDirectoryAutocompleteController,
                    )
                }
            } else if (isTextMode) {
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    singleLine = true,
                    label = { Text(if (isRenameWindowMode) "Window name" else "Session name") },
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
 * Tmux-specific 8-slot key bar. Ctrl/Alt modifier slots are intentionally
 * not mirrored from [com.pocketshell.app.session.SessionScreen]'s layout:
 * the tmux route sends pane input through `send-keys`, and these one-shot
 * Ctrl-C / Ctrl-D keys map directly to the control bytes agents expect.
 */
internal val TmuxKeyBarLayout: List<KeyBinding> = listOf(
    KeyBinding(label = "Esc", kind = KeyKind.Regular),
    KeyBinding(label = "Tab", kind = KeyKind.Regular),
    KeyBinding(label = "Ctrl-C", kind = KeyKind.Regular),
    KeyBinding(label = "Ctrl-D", kind = KeyKind.Regular),
    KeyBinding(label = "‹", kind = KeyKind.Arrow),
    KeyBinding(label = "⌃", kind = KeyKind.Arrow),
    KeyBinding(label = "⌄", kind = KeyKind.Arrow),
    KeyBinding(label = "›", kind = KeyKind.Arrow),
)

internal const val CtrlC2Chip: String = "Ctrl-C x2"
internal const val CtrlD2Chip: String = "Ctrl-D x2"
internal val AgentExitChips: List<String> = listOf(CtrlC2Chip, CtrlD2Chip)
