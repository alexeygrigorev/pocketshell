package com.pocketshell.app.session

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.pocketshell.app.composer.PromptComposerSheet
import com.pocketshell.app.session.SessionViewModel.ConnectionStatus
import com.pocketshell.app.snippets.SnippetKind
import com.pocketshell.app.snippets.SnippetPickerSheet
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.agents.ConversationRole
import com.pocketshell.core.terminal.ui.TerminalSurface
import com.pocketshell.uikit.components.Breadcrumb
import com.pocketshell.uikit.components.CommandChip
import com.pocketshell.uikit.components.MicButton
import com.pocketshell.uikit.components.Tabs
import com.pocketshell.uikit.model.Crumb
import com.pocketshell.uikit.model.KeyBinding
import com.pocketshell.uikit.model.KeyKind
import com.pocketshell.uikit.model.KeyModifierState
import com.pocketshell.uikit.model.MicButtonState
import com.pocketshell.uikit.theme.PocketShellColors

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
    hostId: Long? = null,
    onBack: () -> Unit = {},
    onOpenJobs: () -> Unit = {},
    inlineDictationViewModel: InlineDictationViewModel = hiltViewModel(),
) {
    LaunchedEffect(host, port, user, keyPath) {
        viewModel.connect(host, port, user, keyPath)
    }

    val status by viewModel.connectionStatus.collectAsState()
    val modifierStates by viewModel.modifierStates.collectAsState()
    val agentConversation by viewModel.agentConversation.collectAsState()
    val dictationState by inlineDictationViewModel.uiState.collectAsState()
    val keyBarModifierStates = remember(modifierStates) {
        modifierStates.mapKeys { (modifier, _) -> modifier.keyBarLabel }
    }

    var showMicSheet by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    // Issue #17: the chip-row "+" entry opens the snippet picker. The
    // picker is only meaningful when we know which host's library to
    // render — at the Phase 0 / proof-of-life entry point hostId is null
    // and we hide the "+" chip rather than open an empty sheet.
    var showSnippetPicker by remember { mutableStateOf(false) }

    val context = LocalContext.current

    // Wire the inline-dictation transcription stream to the terminal. The
    // collector lives as long as the screen does — every emission writes
    // its bytes directly to the live SSH PTY. The flow has `replay = 0`,
    // so re-collecting on recomposition will not replay old bytes.
    LaunchedEffect(inlineDictationViewModel) {
        inlineDictationViewModel.transcriptions.collect { text ->
            viewModel.terminalState.writeInput(text.toByteArray(Charsets.UTF_8))
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
            .background(color = PocketShellColors.Background),
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

            // The terminal surface owns the rest of the vertical band. The
            // weight pushes the bottom strip (key bar / chips / FAB) right
            // up against either the IME or the system nav, whichever is
            // present.
            Box(modifier = Modifier.weight(1f)) {
                if (agentConversation.selectedTab == SessionTab.Conversation && agentConversation.detection != null) {
                    ConversationPane(
                        events = agentConversation.events,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    TerminalSurface(
                        state = viewModel.terminalState,
                        modifier = Modifier.fillMaxSize(),
                    )
                    val detection = agentConversation.detection
                    if (agentConversation.hintVisible && detection != null) {
                        AgentHintChip(
                            label = "${detection.agent.displayName} session detected",
                            onOpen = { viewModel.selectSessionTab(SessionTab.Conversation) },
                            onDismiss = viewModel::dismissAgentHint,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(12.dp),
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

            if (isImeVisible) {
                KeyBarWithMic(
                    keys = KeyBarLayout,
                    onKey = { binding -> viewModel.onKeyBarKey(binding.label) },
                    modifierStates = keyBarModifierStates,
                    onModifierStateChange = { binding, state ->
                        viewModel.onKeyBarModifierState(binding.label, state)
                    },
                    micState = dictationState.recording,
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
                ChipRow(
                    chips = DefaultChips,
                    onChipTap = viewModel::onChipTap,
                    onDictateTap = { showMicSheet = true },
                    onAddSnippetTap = if (hostId != null) {
                        { showSnippetPicker = true }
                    } else null,
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
        )

        // Bottom-right mic FAB. Always visible (per `docs/input-methods.md`
        // §"Screen real estate" → keyboard down) — the keyboard-up path
        // surfaces the dictate icon-chip via the chip row instead.
        if (!isImeVisible) {
            MicButton(
                state = MicButtonState.Idle,
                onClick = { showMicSheet = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 24.dp),
            )
        }
    }

    if (showMicSheet) {
        // Wires the issue #15 prompt composer. `onSend` is the contract
        // the composer drives:
        //  - `withEnter = false` -> write the prompt bytes only (Send)
        //  - `withEnter = true`  -> write the prompt bytes + '\n' (Send + ↵)
        // Either way we dismiss the sheet so the user lands back on the
        // live terminal with their submission visible.
        PromptComposerSheet(
            onDismiss = { showMicSheet = false },
            onSend = { text, withEnter ->
                val payload = if (withEnter) text + "\n" else text
                viewModel.terminalState.writeInput(payload.toByteArray(Charsets.UTF_8))
                showMicSheet = false
            },
            hostId = hostId,
        )
    }

    if (showSnippetPicker && hostId != null) {
        // Issue #17: chip-row entry to the snippet library. Picking a
        // snippet writes its body to the terminal stdin. Commands get a
        // trailing newline (Enter is implied — the user picked a "run
        // this" shortcut); prompt templates are sent verbatim so the
        // user can keep typing context before pressing Enter via the key
        // bar / system keyboard.
        SnippetPickerSheet(
            hostId = hostId,
            onDismiss = { showSnippetPicker = false },
            onSnippetPicked = { snippet ->
                val payload = when (SnippetKind.fromStorage(snippet.kind)) {
                    SnippetKind.Command -> snippet.body + "\n"
                    SnippetKind.Prompt -> snippet.body
                }
                viewModel.terminalState.writeInput(payload.toByteArray(Charsets.UTF_8))
                showSnippetPicker = false
            },
        )
    }
}

@Composable
private fun SessionMoreMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onOpenJobs: () -> Unit,
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
 * Compact one-line error strip surfaced when the inline-dictation FSM
 * reports a permission / API-key / Whisper failure. Tapping the strip
 * dismisses the banner; the next mic tap also clears it via the
 * ViewModel's `clearError()`. Visually distinct from the armed-modifier
 * strip — accent-soft fill with an accent-dim top border, full-width.
 */
@Composable
private fun InlineDictationErrorStrip(message: String, onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = PocketShellColors.AccentSoft)
            .border(width = 1.dp, color = PocketShellColors.AccentDim)
            .clickable(onClick = onDismiss)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = message,
            color = PocketShellColors.Accent,
            fontSize = 11.sp,
        )
    }
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

/**
 * Always-visible chip row (only when the IME is hidden, per
 * `docs/input-methods.md` §"Screen real estate"). The first chip is the
 * `dictate` icon chip — tapping it opens the composer placeholder; the
 * rest write their literal text + `\n` into the terminal.
 *
 * Issue #17: a trailing `+` chip opens the snippet picker when a host
 * is bound (see [onAddSnippetTap]). It is rendered with the accent
 * `icon-chip` treatment to mirror the dictate entry's "actionable"
 * visual weight.
 */
@Composable
private fun ChipRow(
    chips: List<String>,
    onChipTap: (String) -> Unit,
    onDictateTap: () -> Unit,
    onAddSnippetTap: (() -> Unit)? = null,
) {
    val scrollState = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = PocketShellColors.Surface)
            .border(width = 1.dp, color = PocketShellColors.Border)
            .horizontalScroll(scrollState)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The dictate chip uses the accent treatment via the ui-kit's
        // `icon-chip` mode. We pass a small filled-circle marker so the
        // ui-kit picks the accent style without us having to ship a real
        // icon set yet (the ui-kit's CommandChip swaps `ImageVector` once
        // the design language gets a proper icon ramp).
        CommandChip(
            label = "dictate",
            onClick = onDictateTap,
            icon = DictateDotIcon,
        )
        if (onAddSnippetTap != null) {
            // Issue #17: "+" icon-chip routes the user into the snippet
            // picker. Reuses the accent `icon-chip` treatment so the
            // affordance reads as an action, not a literal text payload.
            CommandChip(
                label = "+ snippet",
                onClick = onAddSnippetTap,
                icon = DictateDotIcon,
            )
        }
        chips.forEach { chip ->
            CommandChip(
                label = chip,
                onClick = { onChipTap(chip) },
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
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

/**
 * A 24x24 filled dot used as the dictate chip's leading icon. We build
 * the [ImageVector] inline rather than pull in `material-icons-extended`
 * for one glyph — the icon set already in the classpath
 * (`material-icons-core`, transitively from material3) does not ship a
 * standalone `Filled.Circle`.
 */
private val DictateDotIcon: ImageVector = ImageVector.Builder(
    name = "DictateDot",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).addCirclePath(
    fill = SolidColor(androidx.compose.ui.graphics.Color.White),
).build()

/**
 * Build a filled circle centred at (12, 12) with radius 6 in the path
 * data, then append it to this [ImageVector.Builder]. Kept as a private
 * extension so the inline icon definition stays declarative.
 *
 * Uses two relative arcs (the standard SVG idiom for a circle: a half-arc
 * down, then a half-arc back up) so we do not need an `Oval` primitive in
 * the path-node vocabulary.
 */
private fun ImageVector.Builder.addCirclePath(fill: SolidColor): ImageVector.Builder {
    val builder = PathBuilder()
    builder.moveTo(12f, 6f)
    // arcToRelative(a, b, theta, isMoreThanHalf, isPositiveArc, dx1, dy1)
    builder.arcToRelative(6f, 6f, 0f, true, true, 0f, 12f)
    builder.arcToRelative(6f, 6f, 0f, true, true, 0f, -12f)
    builder.close()
    addPath(pathData = builder.nodes, fill = fill)
    return this
}

/**
 * v1 chip set — matches `docs/mockups/session.html`'s `.chip-row`
 * (without the dictate entry, which the screen renders separately as the
 * icon chip). Phase 2 / #18 will source these from per-host storage.
 */
private val DefaultChips: List<String> = listOf(
    "git status",
    "tmux ls",
    "k logs",
    "clear",
)

private val SessionViewModel.Modifier.keyBarLabel: String
    get() = when (this) {
        SessionViewModel.Modifier.Ctrl -> "Ctrl"
        SessionViewModel.Modifier.Alt -> "Alt"
    }
