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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.pocketshell.app.composer.PromptComposerSheet
import com.pocketshell.app.session.SessionViewModel.ConnectionStatus
import com.pocketshell.app.snippets.SnippetPickerSheet
import com.pocketshell.app.voice.BottomChipControls
import com.pocketshell.app.voice.DefaultSessionChips
import com.pocketshell.app.voice.InlineDictationErrorStrip
import com.pocketshell.app.voice.VoiceCommandReviewStrip
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.agents.ConversationRole
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
internal const val SESSION_AGENT_HINT_TAG = "session:agent-hint"
/** Issue #116: stable test tag for the in-session blocked / near-limit chip. */
internal const val SESSION_USAGE_BADGE_TAG = "session:usage-badge"

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
) {
    LaunchedEffect(host, port, user, keyPath, passphrase) {
        viewModel.connect(host, port, user, keyPath, passphrase)
    }
    LaunchedEffect(hostId) {
        viewModel.bindProjectNavigationHost(hostId)
    }

    val status by viewModel.connectionStatus.collectAsState()
    val modifierStates by viewModel.modifierStates.collectAsState()
    val agentConversation by viewModel.agentConversation.collectAsState()
    val voiceCommandReview by viewModel.voiceCommandReview.collectAsState()
    val projectNavigation by viewModel.projectNavigation.collectAsState()
    val dictationState by inlineDictationViewModel.uiState.collectAsState()
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
                InlineDictationViewModel.DictationMode.Command -> viewModel.planVoiceCommand(text)
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

            // Optional one-line status above the terminal until the
            // breadcrumb's live dot covers it post-#18.
            (status as? ConnectionStatus.Connecting)?.let {
                StatusLine("connecting to ${it.user}@${it.host}:${it.port}")
            }
            (status as? ConnectionStatus.Failed)?.let {
                StatusLine(it.message)
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
                if (agentConversation.selectedTab == SessionTab.Conversation && agentConversation.detection != null) {
                    ConversationPane(
                        events = agentConversation.events,
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag(SESSION_CONVERSATION_PANE_TAG),
                    )
                } else {
                    TerminalSurface(
                        state = viewModel.terminalState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 2.dp, vertical = 4.dp),
                        onTerminalSizeChanged = viewModel::resizeRemotePty,
                    )
                    val detection = agentConversation.detection
                    if (agentConversation.hintVisible && detection != null) {
                        AgentHintChip(
                            label = "${detection.agent.displayName} session detected",
                            onOpen = { viewModel.selectSessionTab(SessionTab.Conversation) },
                            onDismiss = viewModel::dismissAgentHint,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(12.dp)
                                .testTag(SESSION_AGENT_HINT_TAG),
                        )
                    }
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
            VoiceCommandReviewStrip(
                state = voiceCommandReview,
                onInsert = { viewModel.approvePendingVoiceCommand(withEnter = false) },
                onRun = { viewModel.approvePendingVoiceCommand(withEnter = true) },
                onDismiss = viewModel::dismissVoiceCommandReview,
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
        //  - `withEnter = false` -> write the prompt bytes only (Send)
        //  - `withEnter = true`  -> write the prompt bytes + Enter (Send + ↵)
        // Either way we dismiss the sheet so the user lands back on the
        // live terminal with their submission visible.
        PromptComposerSheet(
            onDismiss = { showMicSheet = false },
            onSend = { text, withEnter ->
                viewModel.sendText(text, withEnter)
                showMicSheet = false
            },
            hostId = hostId,
        )
    }

    if (showSnippetPicker && hostId != null) {
        // Issue #17: chip-row entry to the snippet library. Picking a
        // snippet routes through the ViewModel's terminal input path.
        // Commands send Enter explicitly; prompt templates paste only so the
        // user can keep typing context before pressing Enter manually.
        SnippetPickerSheet(
            hostId = hostId,
            onDismiss = { showSnippetPicker = false },
            onSnippetPicked = { snippet ->
                viewModel.onSnippetPicked(snippet)
                showSnippetPicker = false
            },
        )
    }

    if (showProjectNavigation) {
        ProjectNavigationSheet(
            state = projectNavigation,
            targetLabel = "$user@$host",
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

@Composable
private fun AgentHintChip(
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
private fun ConversationPane(
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
                ConversationEventRow(event)
            }
        }
    }
}

@Composable
private fun ConversationEventRow(event: ConversationEvent) {
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

/**
 * Small accent strip surfaced while one or more sticky modifiers are
 * active. It gives a textual hint alongside the key bar's active-key
 * treatment, especially for the locked state.
 */
@Composable
private fun ArmedModifierStrip(states: Map<SessionViewModel.Modifier, KeyModifierState>) {
    val label = states.entries.joinToString(" + ") { (modifier, state) ->
        if (state == KeyModifierState.Locked) "${modifier.name} locked" else "${modifier.name} armed"
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = PocketShellColors.AccentSoft)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = "$label - tap a bar key to send it wrapped",
            color = PocketShellColors.Accent,
            fontSize = 11.sp,
        )
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ProjectNavigationSheet(
    state: ProjectNavigationUiState,
    targetLabel: String,
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
                Text(
                    text = "Commands send to $targetLabel pane 1",
                    color = PocketShellColors.TextSecondary,
                    fontSize = 12.sp,
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
