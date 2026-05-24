package com.pocketshell.app.tmux

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.navigation.compose.hiltViewModel
import com.pocketshell.app.session.SessionTab
import com.pocketshell.app.sessions.DEFAULT_TMUX_START_DIRECTORY
import com.pocketshell.app.sessions.HostTmuxSessionPickerRequest
import com.pocketshell.app.sessions.HostTmuxSessionPickerState
import com.pocketshell.app.sessions.HostTmuxSessionPickerViewModel
import com.pocketshell.app.sessions.HostTmuxSessionRow
import com.pocketshell.app.sessions.resolveTmuxSessionCreation
import com.pocketshell.app.tmux.TmuxSessionViewModel.ConnectionStatus
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.agents.ConversationRole
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.terminal.ui.TerminalSurface
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
    onBack: () -> Unit = {},
    onOpenTmuxSession: (sessionName: String, startDirectory: String?) -> Unit = { _, _ -> },
    onReplaceTmuxSession: (sessionName: String) -> Unit = {},
    onOpenJobs: () -> Unit = {},
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

    val pagerState = rememberPagerState(pageCount = { panes.size })

    val isImeVisible = WindowInsets.ime.getBottom(
        LocalDensity.current,
    ) > 0

    val currentPane = panes.getOrNull(pagerState.currentPage)
    val currentAgentConversation = currentPane?.paneId?.let { agentConversations[it] }
    val currentWindowId = currentPane?.windowId
    val windows = remember(panes) { panes.toWindowSummaries() }
    val crumbs = remember(host, sessionName, currentPane) {
        breadcrumbCrumbs(host, sessionName, currentPane)
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
                Breadcrumb(
                    crumbs = crumbs,
                    onBack = onBack,
                    onMore = { moreExpanded = true },
                )
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
            (status as? ConnectionStatus.Failed)?.let {
                StatusLine(it.message)
            }

            val tabs = if (currentAgentConversation?.detection != null) {
                listOf("Terminal", "Conversation")
            } else {
                listOf("Terminal")
            }
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
            )

            if (windows.isNotEmpty()) {
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
                    TmuxConversationPane(
                        events = currentAgentConversation.events,
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag(TMUX_CONVERSATION_PANE_TAG),
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
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 2.dp, vertical = 4.dp),
                        )
                    }
                }
                if (
                    currentPane != null &&
                    currentAgentConversation?.hintVisible == true &&
                    currentAgentConversation.detection != null &&
                    currentAgentConversation.selectedTab == SessionTab.Terminal
                ) {
                    TmuxAgentHintChip(
                        label = "${currentAgentConversation.detection.agent.displayName} session detected",
                        onOpen = {
                            viewModel.selectSessionTab(currentPane.paneId, SessionTab.Conversation)
                        },
                        onDismiss = {
                            viewModel.dismissAgentHint(currentPane.paneId)
                        },
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(12.dp)
                            .testTag(TMUX_AGENT_HINT_TAG),
                    )
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
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = onCreate,
                        ) {
                            Text("+ New session")
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

internal fun List<TmuxPaneState>.toWindowSummaries(): List<WindowSummary> =
    distinctBy { it.windowId }
        .map { pane -> WindowSummary(windowId = pane.windowId, title = pane.windowId) }

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
internal const val TMUX_AGENT_HINT_TAG = "tmux:agent-hint"

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

@Composable
private fun TmuxAgentHintChip(
    label: String,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(color = PocketShellColors.Surface)
            .border(width = 1.dp, color = PocketShellColors.AccentDim)
            .padding(horizontal = 12.dp, vertical = 8.dp),
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

@Composable
private fun TmuxConversationPane(
    events: List<ConversationEvent>,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    val filteredEvents = remember(events, query) {
        val q = query.trim()
        if (q.isBlank()) events else events.filter { it.searchText().contains(q, ignoreCase = true) }
    }
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
                TmuxConversationEventRow(event)
            }
        }
    }
}

@Composable
private fun TmuxConversationEventRow(event: ConversationEvent) {
    var expanded by remember(event.id) { mutableStateOf(false) }
    val title = when (event) {
        is ConversationEvent.Message -> when (event.role) {
            ConversationRole.User -> "USER"
            ConversationRole.Assistant -> if (event.streaming) "ASSISTANT - streaming" else "ASSISTANT"
        }
        is ConversationEvent.ToolCall -> "Tool: ${event.name}"
        is ConversationEvent.ToolResult -> if (event.isError) "Tool result - error" else "Tool result"
    }
    val body = when (event) {
        is ConversationEvent.Message -> event.text
        is ConversationEvent.ToolCall -> event.input
        is ConversationEvent.ToolResult -> event.output
    }
    val isTool = event is ConversationEvent.ToolCall || event is ConversationEvent.ToolResult
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = PocketShellColors.Surface)
            .border(width = 1.dp, color = PocketShellColors.Border)
            .clickable(enabled = isTool) { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = if (isTool && !expanded) "$title (collapsed)" else title,
            color = if (isTool) PocketShellColors.Accent else PocketShellColors.TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
        if (!isTool || expanded) {
            Text(
                text = body,
                color = PocketShellColors.Text,
                fontSize = 13.sp,
            )
        }
    }
}

private fun ConversationEvent.searchText(): String = when (this) {
    is ConversationEvent.Message -> text
    is ConversationEvent.ToolCall -> "$name $input"
    is ConversationEvent.ToolResult -> output
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
            DropdownMenuItem(text = { Text("New session") }, onClick = onCreateSession)
            DropdownMenuItem(text = { Text("Switch session") }, onClick = onSwitchSession)
            DropdownMenuItem(text = { Text("Rename session") }, onClick = onRenameSession)
            DropdownMenuItem(text = { Text("Kill session") }, onClick = onKillSession)
            DropdownMenuItem(text = { Text("Recurring jobs") }, onClick = onOpenJobs)
            DropdownMenuItem(text = { Text("New window") }, onClick = onNewWindow)
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
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        windows.forEach { window ->
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
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
        TextButton(onClick = onNewWindow) {
            Text("+")
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
            DropdownMenuItem(text = { Text("Rename ${window.windowId}") }, onClick = onRename)
            DropdownMenuItem(text = { Text("Kill ${window.windowId}") }, onClick = onKill)
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
