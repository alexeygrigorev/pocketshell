package com.pocketshell.app.session

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.pocketshell.app.conversation.ConversationMessageTurn
import com.pocketshell.app.composer.PromptComposerSheet
import com.pocketshell.app.composer.UnsentPromptBanner
import com.pocketshell.app.session.SessionViewModel.ConnectionStatus
import com.pocketshell.app.snippets.SnippetKind
import com.pocketshell.app.snippets.SnippetPickerSheet
import com.pocketshell.app.voice.BottomChipControls
import com.pocketshell.app.voice.DefaultSessionChips
import com.pocketshell.app.voice.InlineDictationErrorStrip
import com.pocketshell.app.voice.AssistantStrip
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.agents.ToolCallSummary
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.font.FontFamily
import com.pocketshell.core.terminal.ui.TerminalSurface
import com.pocketshell.core.terminal.ui.showTerminalSoftKeyboard
import com.pocketshell.uikit.components.Breadcrumb
import com.pocketshell.uikit.components.Tabs
import com.pocketshell.uikit.model.Crumb
import com.pocketshell.uikit.model.KeyBinding
import com.pocketshell.uikit.model.KeyKind
import com.pocketshell.uikit.model.KeyModifierState
import com.pocketshell.uikit.theme.PocketShellColors

internal const val SESSION_SCREEN_TAG = "session:screen"
internal const val SESSION_CONVERSATION_PANE_TAG = "session:conversation"
internal const val SESSION_CONVERSATION_COMPOSER_INPUT_TAG = "session:conversation:composer-input"
internal const val SESSION_CONVERSATION_COMPOSER_SEND_TAG = "session:conversation:composer-send"
internal const val SESSION_CONVERSATION_TOOL_ROW_TAG_PREFIX = "session:conversation:tool:"
/** Issue #176: stable test tag prefix for a `SystemNote` row in the SessionScreen conversation pane. */
internal const val SESSION_CONVERSATION_SYSTEM_NOTE_ROW_TAG_PREFIX = "session:conversation:system-note:"
/**
 * Issue #154: stable test tags for the conversation navigation polish on
 * the raw-SSH pane. Mirror the tmux equivalents.
 */
internal const val SESSION_CONVERSATION_SEARCH_TAG = "session:conversation:search"
internal const val SESSION_CONVERSATION_JUMP_TO_LATEST_TAG =
    "session:conversation:jump-to-latest"
/** Issue #116: stable test tag for the in-session blocked / near-limit chip. */
internal const val SESSION_USAGE_BADGE_TAG = "session:usage-badge"
/**
 * Issue #165: stable test tags for the SSH-handshake progress overlay
 * on the raw-SSH session screen. Mirrors the
 * [com.pocketshell.app.tmux.TmuxSessionScreen] equivalents.
 */
internal const val SESSION_CONNECTING_PROGRESS_TAG = "session:connecting"
internal const val SESSION_CONNECTING_PROGRESS_BAR_TAG = "session:connecting:bar"
internal const val SESSION_CONNECTING_SLOW_HINT_TAG = "session:connecting:slow-hint"
internal const val SESSION_CONNECTING_CANCEL_TAG = "session:connecting:cancel"
internal const val SESSION_ERROR_TAG = "session:error"
internal const val SESSION_RECONNECT_TAG = "session:error:reconnect"
internal const val SESSION_ARMED_MODIFIER_STRIP_TAG = "session:armed-modifiers"

/**
 * Issue #165: timings for the SSH-handshake progress overlay. Mirrors
 * the tmux-route constants; raw-SSH handshakes have the same
 * 2-5s "feels frozen" window the audit flagged.
 */
internal const val SESSION_SLOW_CONNECT_HINT_AFTER_MS: Long = 5_000L
internal const val SESSION_CANCEL_AVAILABLE_AFTER_MS: Long = 15_000L

/**
 * Phase 1 session screen — the visual target is `docs/mockups/session.html`.
 *
 * Stacks four bands top-to-bottom:
 *
 * 1. **Breadcrumb** (ui-kit's `Breadcrumb`) — back arrow signals "detach",
 *    `host > session > pane` chain, `⋮` for the (future) more menu.
 * 2. **Tabs row** — `Terminal` is the only tab in Phase 1 (Conversation
 *    is hidden until Phase 3 / #23).
 * 3. **`TerminalSurface`** filling the remaining vertical space.
 * 4. **Input strip** — either the [KeyBar] (when the IME is showing) or
 *    the [CommandChip] row (when it is hidden), plus the mic FAB anchored
 *    bottom-right per `docs/input-methods.md` §"Screen real estate".
 *
 * The screen does not own any business state — everything lives in
 * [SessionViewModel]. The composable is a thin renderer that wires its
 * callbacks to the ViewModel's intent functions.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
public fun SessionScreen(
    viewModel: SessionViewModel,
    modifier: Modifier = Modifier,
    host: String = SessionDefaults.HOST,
    port: Int = SessionDefaults.PORT,
    user: String = SessionDefaults.USER,
    keyPath: String? = null,
    passphrase: CharArray? = null,
    hostId: Long? = null,
    onBack: () -> Unit = {},
    onOpenJobs: () -> Unit = {},
    onOpenUsage: () -> Unit = {},
    /** Route an assistant-requested navigation (issue #266). */
    onAssistantNavigate: (com.pocketshell.app.nav.AppDestination) -> Unit = {},
    /**
     * Issue #116 (usage-panel Fix B): the most-concerning
     * [com.pocketshell.core.usage.UsageProviderRecord] for this
     * session's host as reported by [com.pocketshell.app.usage.UsageScheduler].
     * `null` when no record warrants a chip — the badge composable
     * still short-circuits on `null` so passing it through is safe.
     * MainActivity computes the lookup from the scheduler's snapshot
     * map for the active session's `hostId`.
     */
    usageBadgeProvider: com.pocketshell.core.usage.UsageProviderRecord? = null,
    inlineDictationViewModel: InlineDictationViewModel = hiltViewModel(),
    // Issue #176: pulled in via Hilt so the Settings → Conversation →
    // "Show system notes" toggle reaches the in-session conversation pane
    // without restarting.
    settingsViewModel: com.pocketshell.app.settings.SettingsViewModel = hiltViewModel(),
) {
    LaunchedEffect(host, port, user, keyPath, passphrase, hostId) {
        viewModel.connect(host, port, user, keyPath, passphrase, hostId)
    }
    LaunchedEffect(hostId) {
        viewModel.bindProjectNavigationHost(hostId)
    }
    // Issue #266: give the assistant the SSH params for host-scoped tools.
    LaunchedEffect(hostId, host, port, user, keyPath) {
        viewModel.bindAssistant(hostId, host, host, port, user, keyPath, passphrase)
    }
    // Issue #266: route assistant-requested navigation through the app nav.
    LaunchedEffect(viewModel) {
        viewModel.assistantNavRequests.collect { onAssistantNavigate(it) }
    }

    val status by viewModel.connectionStatus.collectAsState()
    val canReconnect by viewModel.canReconnect.collectAsState()
    // Issue #249: gate the composer / send / dictation surfaces on a live
    // SSH session. Off the live state a chip / key / dictation would be
    // written into a dead PTY and silently lost.
    val sessionLive = status is ConnectionStatus.Connected
    val modifierStates by viewModel.modifierStates.collectAsState()
    val agentConversation by viewModel.agentConversation.collectAsState()
    val assistantState by viewModel.assistantState.collectAsState()
    val projectNavigation by viewModel.projectNavigation.collectAsState()
    val dictationState by inlineDictationViewModel.uiState.collectAsState()
    val appSettings by settingsViewModel.state.collectAsState()
    val keyBarModifierStates = remember(modifierStates) {
        modifierStates.mapKeys { (modifier, _) -> modifier.keyBarLabel }
    }

    var showMicSheet by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showProjectNavigation by remember { mutableStateOf(false) }
    // Issue #17: the chip-row "+" entry opens the snippet picker. The
    // picker is only meaningful when we know which host's library to
    // render — at the Phase 0 / proof-of-life entry point hostId is null
    // and we hide the "+" chip rather than open an empty sheet.
    var showSnippetPicker by remember { mutableStateOf(false) }

    val context = LocalContext.current
    // Issue #131: the show-keyboard chip needs the Compose root view so it
    // can locate the embedded `TerminalView` under it. `LocalView.current`
    // is the `AndroidComposeView` host attached to this window; descending
    // it finds the `AndroidView`-wrapped `TerminalView` inside the
    // `TerminalSurface`.
    val composeRootView = LocalView.current

    // Wire the inline-dictation transcription stream to the terminal. The
    // collector lives as long as the screen does — every emission writes
    // its bytes directly to the live SSH PTY. The flow has `replay = 0`,
    // so re-collecting on recomposition will not replay old bytes.
    LaunchedEffect(inlineDictationViewModel, dictationState.mode) {
        inlineDictationViewModel.transcriptions.collect { text ->
            when (dictationState.mode) {
                InlineDictationViewModel.DictationMode.Prompt -> viewModel.sendText(text, withEnter = false)
                InlineDictationViewModel.DictationMode.Command -> viewModel.dictateToAssistant(text)
            }
        }
    }

    // Runtime RECORD_AUDIO permission flow for the inline-dictation path.
    // The composer (#15) owns its own launcher because the composer can be
    // opened without the IME being up; the inline path mirrors the same
    // gate so the user gets the system prompt on first tap of the key-bar
    // mic too. Both launchers reuse the manifest-level `RECORD_AUDIO`
    // declaration; only one prompt will ever fire because the OS
    // remembers the user's grant per package.
    val inlinePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            inlineDictationViewModel.onMicTap()
        } else {
            inlineDictationViewModel.surfacePermissionDenied()
        }
    }

    val isImeVisible = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(color = PocketShellColors.Background)
            .testTag(SESSION_SCREEN_TAG),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
        ) {
            Breadcrumb(
                crumbs = breadcrumbCrumbs(host, user),
                onBack = onBack,
                onMore = { showMoreMenu = true },
            )

            val tabs = if (agentConversation.detection != null) {
                listOf("Terminal", "Conversation")
            } else {
                listOf("Terminal")
            }
            Tabs(
                labels = tabs,
                selectedIndex = if (agentConversation.selectedTab == SessionTab.Conversation) 1 else 0,
                onSelected = { index ->
                    viewModel.selectSessionTab(if (index == 1) SessionTab.Conversation else SessionTab.Terminal)
                },
            )

            // Issue #165: replace the muted "connecting…" status line
            // with a visible progress overlay so a 2-5s SSH handshake
            // doesn't feel frozen. After 5s a "still working" subline
            // shows up; after 15s a Cancel affordance tears down the
            // in-flight [connectJob]. Mirrors the equivalent overlay on
            // [com.pocketshell.app.tmux.TmuxSessionScreen].
            (status as? ConnectionStatus.Connecting)?.let {
                ConnectingProgressOverlay(
                    user = it.user,
                    host = it.host,
                    port = it.port,
                    onCancel = { viewModel.cancelConnect() },
                )
            }
            (status as? ConnectionStatus.Failed)?.let {
                FailedConnectionRow(
                    message = it.message,
                    onReconnect = { viewModel.reconnect() },
                    canReconnect = canReconnect,
                )
            }
            // Issue #116 (usage-panel Fix B): in-session blocked /
            // near-limit chip for the active host. Rendered in the
            // status area so it sits above the terminal viewport
            // without competing with the breadcrumb's `host > session
            // > pane` chain. The badge composable already returns
            // early when neither `isBlocked` nor `isNearLimit` is
            // true, so passing a non-null provider that doesn't
            // warrant a chip still renders nothing.
            if (usageBadgeProvider != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(color = PocketShellColors.Surface)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .testTag(SESSION_USAGE_BADGE_TAG),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    com.pocketshell.app.usage.UsageSessionBlockedBadge(
                        provider = usageBadgeProvider,
                    )
                }
            }

            // The terminal surface owns the rest of the vertical band. The
            // weight pushes the bottom strip (key bar / chips / FAB) right
            // up against either the IME or the system nav, whichever is
            // present.
            Box(modifier = Modifier.weight(1f)) {
                val detection = agentConversation.detection
                if (agentConversation.selectedTab == SessionTab.Conversation && detection != null) {
                    ConversationPane(
                        events = agentConversation.events,
                        onSendToAgent = { text -> viewModel.sendToAgentResult(text) },
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag(SESSION_CONVERSATION_PANE_TAG),
                        showSystemNotes = appSettings.showSystemNotes,
                        // Issue #154 (acceptance criterion #5): the
                        // search query lives on the ViewModel so it
                        // survives Terminal ↔ Conversation tab flips.
                        query = agentConversation.searchQuery,
                        onQueryChange = viewModel::setAgentSearchQuery,
                        // Issue #249: don't deliver-then-clear while down.
                        sendEnabled = sessionLive,
                        agentName = detection.agent.displayName,
                        syncStatus = agentConversation.syncStatus,
                        onRetryAgentStream = viewModel::retryAgentConversationStream,
                    )
                } else {
                    TerminalSurface(
                        state = viewModel.terminalState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 2.dp, vertical = 4.dp),
                        onTerminalSizeChanged = viewModel::resizeRemotePty,
                    )
                }
            }

            if (modifierStates.isNotEmpty()) {
                ArmedModifierStrip(modifierStates)
            }

            // The inline-dictation error banner sits between the armed-
            // modifier strip and the key bar so it is visible while the
            // IME is up. Tapping anywhere on the bar clears it (the next
            // mic tap also clears it via the FSM).
            dictationState.error?.let { msg ->
                InlineDictationErrorStrip(msg, onDismiss = inlineDictationViewModel::clearError)
            }
            AssistantStrip(
                state = assistantState,
                onConfirm = viewModel::confirmAssistantAction,
                onCorrect = viewModel::correctAssistantAction,
                onCancel = viewModel::cancelAssistantAction,
                onDismiss = viewModel::dismissAssistant,
                onRetry = viewModel::retryAssistantAction,
            )

            if (isImeVisible) {
                KeyBarWithMic(
                    keys = KeyBarLayout,
                    onKey = { binding -> viewModel.onKeyBarKey(binding.label) },
                    modifierStates = keyBarModifierStates,
                    onModifierStateChange = { binding, state ->
                        viewModel.onKeyBarModifierState(binding.label, state)
                    },
                    micState = dictationState.recording,
                    micAmplitude = dictationState.amplitude,
                    dictationMode = dictationState.mode,
                    onDictationModeSelected = inlineDictationViewModel::selectMode,
                    onMicTap = {
                        // Same three-step gate as the prompt composer
                        // (#15): permission → API-key → recorder.
                        val granted = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO,
                        ) == PackageManager.PERMISSION_GRANTED
                        if (!granted) {
                            inlinePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            return@KeyBarWithMic
                        }
                        if (!inlineDictationViewModel.hasApiKey()) {
                            // Inline dictation does not own a key-entry
                            // dialog (the issue is explicit about "no
                            // sheet"). We route the user to the prompt
                            // composer, which already hosts the one-field
                            // dialog. Surfacing this as a banner avoids
                            // the dead-end "tap mic, nothing happens".
                            showMicSheet = true
                            return@KeyBarWithMic
                        }
                        inlineDictationViewModel.onMicTap()
                    },
                    // Issue #249: gate key bar + mic on liveness.
                    inputEnabled = sessionLive,
                )
            } else {
                BottomChipControls(
                    chips = DefaultSessionChips,
                    onChipTap = viewModel::onChipTap,
                    onDictateTap = { showMicSheet = true },
                    // Issue #131: the show-keyboard chip routes through the
                    // helper that finds the TerminalView under the current
                    // Compose root and requests focus + IMM. `showSoftInput`
                    // is documented as an idempotent no-op when the keyboard
                    // is already up, which matches the "no-op when shown"
                    // contract from the issue.
                    onShowKeyboardTap = { showTerminalSoftKeyboard(composeRootView) },
                    onAddSnippetTap = if (hostId != null) {
                        { showSnippetPicker = true }
                    } else null,
                    onProjectNavigationTap = { showProjectNavigation = true },
                    // Issue #249: gate chips + dictate mic on liveness.
                    inputEnabled = sessionLive,
                )
            }
        }

        SessionMoreMenu(
            expanded = showMoreMenu,
            onDismiss = { showMoreMenu = false },
            onOpenJobs = {
                showMoreMenu = false
                onOpenJobs()
            },
            onOpenUsage = {
                showMoreMenu = false
                onOpenUsage()
            },
        )

    }

    if (showMicSheet) {
        // Wires the issue #15 prompt composer. `onSend` is the contract
        // the composer drives:
        //  - `withEnter = false` -> insert the prompt bytes only
        //  - `withEnter = true`  -> send the prompt bytes + Enter
        // Either way we dismiss the sheet so the user lands back on the
        // live terminal with their submission visible.
        PromptComposerSheet(
            onDismiss = { showMicSheet = false },
            onSend = { text, withEnter ->
                // Issue #249: if the session dropped while the sheet was
                // open, don't write into a dead PTY and don't dismiss —
                // keep the sheet (and the user's text) so nothing is lost.
                if (!sessionLive) {
                    false
                } else {
                    viewModel.sendText(text, withEnter)
                    showMicSheet = false
                    true
                }
            },
            hostId = hostId,
        )
    }

    if (showSnippetPicker && hostId != null) {
        // Issue #17 / #187 / #227: chip-row entry to the snippet library.
        //  - Explicit `Send` / `Send + ↵` chips route through
        //    `sendSnippet(snippet, withEnter)` so the user's overt Enter
        //    intent is honoured directly. This is the production wiring
        //    for issue #187 — tapping `Send + ↵` on a prompt snippet
        //    now actually presses Enter.
        //  - Per D22 (issue #227) the legacy row-body smart-default tap
        //    surface was removed; the picker only routes through the
        //    explicit-intent chip callback.
        SnippetPickerSheet(
            hostId = hostId,
            onDismiss = { showSnippetPicker = false },
            kindFilter = SnippetKind.Command,
            onSnippetSend = { snippet, withEnter ->
                // Issue #249: same liveness guard as the prompt composer.
                if (sessionLive) {
                    viewModel.sendSnippet(snippet, withEnter)
                    showSnippetPicker = false
                }
            },
        )
    }

    if (showProjectNavigation) {
        ProjectNavigationSheet(
            state = projectNavigation,
            onDismiss = {
                showProjectNavigation = false
                viewModel.clearProjectNavigationFeedback()
            },
            onDirectoryTap = viewModel::navigateToDirectory,
            onAddRoot = viewModel::addProjectRoot,
            onCreateFolder = viewModel::createFolderAndCd,
            onClone = viewModel::cloneRepositoryAndCd,
        )
    }
}

@Composable
private fun SessionMoreMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onOpenJobs: () -> Unit,
    onOpenUsage: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopEnd,
    ) {
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismiss,
        ) {
            DropdownMenuItem(text = { Text("Recurring jobs") }, onClick = onOpenJobs)
            // Issue #114 Fix A: in-session entry to the cross-host
            // Usage / quota panel. The panel is host-agnostic for now —
            // a per-host filter and "Open Usage from bootstrap" follow
            // in Fix B / Fix C.
            DropdownMenuItem(text = { Text("Usage") }, onClick = onOpenUsage)
        }
    }
}

/**
 * Build the breadcrumb segments. v1 has a flat `host > session > pane`
 * triplet; #18 / Phase 2 fill in real session + pane names once tmux
 * awareness lands.
 */
private fun breadcrumbCrumbs(host: String, user: String): List<Crumb> = listOf(
    Crumb(label = host, isCurrent = false, onClick = { /* host root — #18 */ }),
    Crumb(label = "$user@$host", isCurrent = true, onClick = { /* current — no-op */ }),
    Crumb(label = "pane 1", isCurrent = false, onClick = { /* pane switcher — Phase 2 */ }),
)

// Issue #160 round 2: `internal` visibility (was `private`) so the
// connected `ConversationInteractE2eTest` can drive the composer
// directly without spinning up the whole [SessionScreen] surface —
// the composer journey is the unit under test and the rest of the
// session screen is incidental.
@Composable
internal fun ConversationPane(
    events: List<ConversationEvent>,
    onSendToAgent: suspend (String) -> Boolean,
    modifier: Modifier = Modifier,
    // Issue #176: same opt-in as the tmux pane — when false, XML-tagged
    // SystemNote events are filtered out entirely; default is true so the
    // existing ConversationInteractE2eTest call site keeps showing them.
    showSystemNotes: Boolean = true,
    // Issue #154 (acceptance criterion #5): hoist the search query so it
    // survives Terminal ↔ Conversation tab flips. Defaults keep the old
    // local-`remember` behaviour for direct callers that haven't migrated
    // (notably the `ConversationInteractE2eTest`).
    query: String = "",
    onQueryChange: (String) -> Unit = NoOpStringChange,
    // Issue #249: gate "Send" on whether the SSH session is live so a
    // disconnected send can't deliver-then-clear the draft. Default true
    // keeps direct callers (the connected `ConversationInteractE2eTest`)
    // running with the always-enabled behaviour.
    sendEnabled: Boolean = true,
    agentName: String = "agent",
    syncStatus: AgentConversationSyncStatus = AgentConversationSyncStatus.Live,
    onRetryAgentStream: () -> Unit = {},
) {
    val (effectiveQuery, onEffectiveQueryChange) = rememberHoistedQuery(query, onQueryChange)
    var composerText by remember { mutableStateOf("") }
    val visibleEvents = remember(events, showSystemNotes) {
        if (showSystemNotes) events else events.filterNot { it is ConversationEvent.SystemNote }
    }
    val filteredEvents = remember(visibleEvents, effectiveQuery) {
        val q = effectiveQuery.trim()
        if (q.isBlank()) visibleEvents else visibleEvents.filter { it.searchText().contains(q, ignoreCase = true) }
    }
    val expandedToolCalls = remember { mutableStateOf(setOf<String>()) }
    // Issue #176: SystemNote expand state — sticky for the pane lifetime.
    val expandedSystemNotes = remember { mutableStateOf(setOf<String>()) }
    val runningToolIds = remember(events) { runningToolCallIds(events) }
    val eventsById = remember(events) { events.associateBy { it.id } }

    // Issue #154 (acceptance criteria #1 & #3): tail-follow + jump-to-latest.
    // Same model as the tmux pane: hold the list state at this level,
    // auto-scroll on new events when the user is at the bottom, and
    // surface a "↓ Latest" pill when they have scrolled away.
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var sendInFlight by remember { mutableStateOf(false) }
    var hasUnsentPrompt by rememberSaveable { mutableStateOf(false) }
    val atBottom by remember(filteredEvents) {
        derivedStateOf { listState.isScrolledToBottom(filteredEvents.size) }
    }
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
                .testTag(SESSION_CONVERSATION_SEARCH_TAG),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
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
                        eventsById = eventsById,
                        isExplicitlyExpanded = expandedToolCalls.value.contains(event.id),
                        onToggleExpand = { id ->
                            val current = expandedToolCalls.value
                            expandedToolCalls.value = if (current.contains(id)) current - id else current + id
                        },
                        isSystemNoteExpanded = expandedSystemNotes.value.contains(event.id),
                        onToggleSystemNoteExpand = { id ->
                            val current = expandedSystemNotes.value
                            expandedSystemNotes.value = if (current.contains(id)) current - id else current + id
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
        UnsentPromptBanner(
            visible = hasUnsentPrompt || (!sendEnabled && composerText.isNotBlank()),
            canRetry = sendEnabled && !sendInFlight,
            onRetry = {
                val trimmed = composerText.trim()
                if (trimmed.isNotEmpty() && !sendInFlight) {
                    coroutineScope.launch {
                        sendInFlight = true
                        try {
                            if (onSendToAgent(trimmed)) {
                                composerText = ""
                                hasUnsentPrompt = false
                            }
                        } finally {
                            sendInFlight = false
                        }
                    }
                }
            },
            onDiscard = {
                composerText = ""
                hasUnsentPrompt = false
            },
        )
        // Issue #196: the raw-SSH agent composer uses the same shared
        // [com.pocketshell.app.composer.AgentComposerSurface] as the tmux
        // agent pane and the terminal-shell prompt composer, so all three
        // surfaces share the styled draft box and accent primary Send
        // button. The surface-specific test tags keep the existing
        // raw-SSH connected tests resolving the same nodes.
        com.pocketshell.app.composer.AgentComposerSurface(
            value = composerText,
            onValueChange = { composerText = it },
            onSend = {
                val trimmed = composerText.trim()
                if (trimmed.isNotEmpty() && !sendInFlight) {
                    coroutineScope.launch {
                        sendInFlight = true
                        try {
                            if (onSendToAgent(trimmed)) {
                                composerText = ""
                                hasUnsentPrompt = false
                            } else {
                                hasUnsentPrompt = true
                            }
                        } finally {
                            sendInFlight = false
                        }
                    }
                }
            },
            inputFieldTag = SESSION_CONVERSATION_COMPOSER_INPUT_TAG,
            sendButtonTag = SESSION_CONVERSATION_COMPOSER_SEND_TAG,
            sendEnabled = sendEnabled && !sendInFlight,
            placeholder = "Message ${agentName.ifBlank { "agent" }}",
        )
        ConversationSyncStatusRow(
            syncStatus = syncStatus,
            onRetry = onRetryAgentStream,
        )
    }
}

/**
 * Issue #154: shared helper that decides whether the conversation pane
 * uses the caller's hoisted `(query, onQueryChange)` pair or falls back
 * to a local `remember`. See the tmux equivalent for full rationale.
 * Lives in this file (not the tmux file) so the raw-SSH and tmux
 * conversation panes can each import it without a cyclic dependency.
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
 * Issue #154: raw-SSH mirror of the tmux jump-to-latest pill.
 */
@Composable
private fun JumpToLatestOverlay(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(durationMillis = 200)),
        exit = fadeOut(animationSpec = tween(durationMillis = 200)),
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
            .testTag(SESSION_CONVERSATION_JUMP_TO_LATEST_TAG),
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
 * Issue #154 (acceptance criterion #3): true when the LazyColumn is at
 * the bottom of its content. Empty lists are treated as bottom-ed so
 * the FAB never flashes during the first-event transition.
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
        is ConversationEvent.ToolResult -> ConversationToolResultRow(event)
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
            .testTag(SESSION_CONVERSATION_TOOL_ROW_TAG_PREFIX + toolCall.id),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
 * Issue #176: SessionScreen mirror of the tmux variant. Renders an
 * XML-tagged [ConversationEvent.SystemNote] as a muted collapsible row
 * so Claude Code's `<system-reminder>`, `<command-name>`,
 * `<local-command-stdout>`, … blocks no longer compete for attention
 * with user/assistant prose. See [ConversationSystemNoteRow] in
 * `TmuxSessionScreen.kt` for the full design rationale.
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
            .testTag(SESSION_CONVERSATION_SYSTEM_NOTE_ROW_TAG_PREFIX + note.id),
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
        Text(
            text = if (result.isError) "tool result (error)" else "tool result",
            color = PocketShellColors.TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
        if (result.output.isNotEmpty()) {
            ToolCallSection(label = "output", body = result.output)
        }
    }
}

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
        val base = Modifier
            .fillMaxWidth()
            .background(color = PocketShellColors.TermBg, shape = RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .let { if (tooLong) it.heightIn(max = 240.dp) else it }
        val scrollState = rememberScrollState()
        val finalModifier = if (tooLong) base.verticalScroll(scrollState) else base
        Column(modifier = finalModifier) {
            Text(
                text = body,
                color = PocketShellColors.TermText,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

private fun Map<String, ConversationEvent>.findToolResultFor(
    toolCallId: String,
): ConversationEvent.ToolResult? =
    values.firstOrNull { it is ConversationEvent.ToolResult && it.toolCallId == toolCallId }
        as? ConversationEvent.ToolResult

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

private fun ConversationEvent.searchText(): String = when (this) {
    is ConversationEvent.Message -> text
    is ConversationEvent.ToolCall -> "$name $input"
    is ConversationEvent.ToolResult -> output
    is ConversationEvent.SystemNote -> "$tag $content"
}

@Composable
private fun StatusLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = PocketShellColors.TextSecondary,
        modifier = Modifier
            .fillMaxWidth()
            .background(color = PocketShellColors.Surface)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

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
            .testTag(SESSION_ERROR_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = PocketShellColors.Red,
            modifier = Modifier.weight(1f),
        )
        if (canReconnect) {
            TextButton(
                onClick = onReconnect,
                modifier = Modifier.testTag(SESSION_RECONNECT_TAG),
            ) {
                Text("Reconnect")
            }
        }
    }
}

/**
 * Issue #165: SSH-handshake progress overlay for the raw-SSH session
 * screen. Mirrors the equivalent overlay on
 * [com.pocketshell.app.tmux.TmuxSessionScreen]; see that composable for
 * the full design rationale (linear indeterminate bar + host string at
 * t=0, "Still working…" subline at 5s, Cancel affordance at 15s).
 *
 * The Cancel button is wired to [SessionViewModel.cancelConnect] which
 * cancels the in-flight [connectJob] and flips status to Failed so the
 * existing error sheet path renders the post-cancel state. The screen
 * dismisses the overlay automatically when the status flips to
 * Connected (the overlay is gated on `ConnectionStatus.Connecting`).
 */
@Composable
internal fun ConnectingProgressOverlay(
    user: String,
    host: String,
    port: Int,
    onCancel: () -> Unit,
) {
    val targetKey = "$user@$host:$port"
    var showSlowHint by remember(targetKey) { mutableStateOf(false) }
    var showCancel by remember(targetKey) { mutableStateOf(false) }
    LaunchedEffect(targetKey) {
        showSlowHint = false
        showCancel = false
        kotlinx.coroutines.delay(SESSION_SLOW_CONNECT_HINT_AFTER_MS)
        showSlowHint = true
        kotlinx.coroutines.delay(
            SESSION_CANCEL_AVAILABLE_AFTER_MS - SESSION_SLOW_CONNECT_HINT_AFTER_MS,
        )
        showCancel = true
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = PocketShellColors.Surface)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag(SESSION_CONNECTING_PROGRESS_TAG),
    ) {
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .testTag(SESSION_CONNECTING_PROGRESS_BAR_TAG),
            color = PocketShellColors.Accent,
            trackColor = PocketShellColors.SurfaceElev,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Connecting to $user@$host:$port…",
                color = PocketShellColors.Text,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            if (showCancel) {
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.testTag(SESSION_CONNECTING_CANCEL_TAG),
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
                modifier = Modifier.testTag(SESSION_CONNECTING_SLOW_HINT_TAG),
            )
        }
    }
}

internal data class ArmedModifierPillUi(
    val label: String,
    val state: KeyModifierState,
)

internal fun armedModifierPills(
    states: Map<SessionViewModel.Modifier, KeyModifierState>,
): List<ArmedModifierPillUi> =
    states
        .filterValues { it != KeyModifierState.Off }
        .map { (modifier, state) ->
            ArmedModifierPillUi(
                label = modifier.name.uppercase(),
                state = state,
            )
        }

@Composable
private fun ArmedModifierStrip(states: Map<SessionViewModel.Modifier, KeyModifierState>) {
    val pills = armedModifierPills(states)
    if (pills.isEmpty()) return

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = PocketShellColors.AccentSoft)
            .testTag(SESSION_ARMED_MODIFIER_STRIP_TAG)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            pills.forEach { pill ->
                ArmedModifierPill(pill)
            }
        }
    }
}

@Composable
private fun ArmedModifierPill(pill: ArmedModifierPillUi) {
    Row(
        modifier = Modifier
            .background(
                color = PocketShellColors.Surface,
                shape = RoundedCornerShape(999.dp),
            )
            .border(
                width = 1.dp,
                color = PocketShellColors.AccentDim,
                shape = RoundedCornerShape(999.dp),
            )
            .padding(horizontal = 7.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(
                    color = PocketShellColors.AccentSoft,
                    shape = RoundedCornerShape(999.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (pill.state == KeyModifierState.Locked) "L" else "1",
                color = PocketShellColors.Accent,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            text = pill.label,
            color = PocketShellColors.Accent,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ProjectNavigationSheet(
    state: ProjectNavigationUiState,
    onDismiss: () -> Unit,
    onDirectoryTap: (String) -> Unit,
    onAddRoot: (path: String, label: String) -> Unit,
    onCreateFolder: (root: String, folder: String) -> Unit,
    onClone: (root: String, repository: String, folder: String?) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var rootPath by remember { mutableStateOf("") }
    var rootLabel by remember { mutableStateOf("") }
    var selectedRoot by remember(state.roots) { mutableStateOf(state.roots.firstOrNull()?.path ?: "~/projects") }
    var folderName by remember { mutableStateOf("") }
    var repoUrl by remember { mutableStateOf("") }
    var cloneFolder by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = PocketShellColors.Surface,
        contentColor = PocketShellColors.Text,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = "Project navigation",
                    color = PocketShellColors.Text,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            state.feedback?.let { feedback ->
                item {
                    Text(
                        text = feedback,
                        color = PocketShellColors.Accent,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(PocketShellColors.AccentSoft)
                            .padding(8.dp),
                    )
                }
            }
            item {
                Text("Directories", color = PocketShellColors.Text, fontWeight = FontWeight.Medium)
            }
            items(state.items, key = { "${it.kind}:${it.path}" }) { item ->
                DirectoryShortcutRow(item = item, onClick = { onDirectoryTap(item.path) })
            }
            item {
                Text("Add project root", color = PocketShellColors.Text, fontWeight = FontWeight.Medium)
                OutlinedTextField(
                    value = rootPath,
                    onValueChange = { rootPath = it },
                    placeholder = { Text("~/projects or /srv/work") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = rootLabel,
                    onValueChange = { rootLabel = it },
                    placeholder = { Text("Label") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { onAddRoot(rootPath, rootLabel) }) {
                    Text("Save root")
                }
            }
            item {
                Text("Root workflows", color = PocketShellColors.Text, fontWeight = FontWeight.Medium)
                OutlinedTextField(
                    value = selectedRoot,
                    onValueChange = { selectedRoot = it },
                    placeholder = { Text("Project root") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    placeholder = { Text("New folder") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { onCreateFolder(selectedRoot, folderName) }) {
                    Text("mkdir + cd")
                }
                OutlinedTextField(
                    value = repoUrl,
                    onValueChange = { repoUrl = it },
                    placeholder = { Text("Repository URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = cloneFolder,
                    onValueChange = { cloneFolder = it },
                    placeholder = { Text("Folder override") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    onClick = { onClone(selectedRoot, repoUrl, cloneFolder.takeIf { it.isNotBlank() }) },
                ) {
                    Text("git clone + cd")
                }
            }
        }
    }
}

@Composable
private fun DirectoryShortcutRow(item: ProjectNavigationItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(width = 1.dp, color = PocketShellColors.Border)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(item.label, color = PocketShellColors.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(item.path, color = PocketShellColors.TextSecondary, fontSize = 11.sp)
        }
        Text(item.kind.name.lowercase(), color = PocketShellColors.TextSecondary, fontSize = 11.sp)
    }
}

/**
 * The 8 bar slots from `docs/mockups/session.html`:
 *
 * - Esc, Tab, Ctrl, Alt, then four arrows.
 *
 * `Ctrl` and `Alt` are declared as `Modifier` so the ui-kit can render
 * one-shot and locked states while the screen mirrors those transitions
 * into [SessionViewModel].
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

private val SessionViewModel.Modifier.keyBarLabel: String
    get() = when (this) {
        SessionViewModel.Modifier.Ctrl -> "Ctrl"
        SessionViewModel.Modifier.Alt -> "Alt"
    }
