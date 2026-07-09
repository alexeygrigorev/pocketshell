package com.pocketshell.app.tmux

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketshell.app.conversation.CONVERSATION_TOOL_COPY_TAG_PREFIX
import com.pocketshell.app.conversation.ConversationDiagnostics
import com.pocketshell.app.conversation.ConversationImages
import com.pocketshell.app.conversation.ConversationInteractionCleanupEffect
import com.pocketshell.app.conversation.ConversationMessageTurn
import com.pocketshell.app.conversation.ConversationTextSection
import com.pocketshell.app.conversation.ConversationToolArgsSection
import com.pocketshell.app.conversation.ConversationToolCardExpansion
import com.pocketshell.app.conversation.ToolResultPairing
import com.pocketshell.app.conversation.conversationTimelineVisibleEvents
import com.pocketshell.app.conversation.filterConversationRows
import com.pocketshell.app.conversation.runningToolCallIds
import com.pocketshell.app.conversation.timelineActorLabel
import com.pocketshell.app.conversation.timelinePreview
import com.pocketshell.app.conversation.timelineTimestamp
import com.pocketshell.app.conversation.toolResultPairing
import com.pocketshell.app.session.AgentConversationSyncStatus
import com.pocketshell.app.session.ConversationSyncStatusRow
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.agents.ToolArgsView
import com.pocketshell.core.agents.ToolCallSummary
import com.pocketshell.core.agents.ToolPayloadFormatter
import com.pocketshell.core.terminal.selection.ConversationLink
import com.pocketshell.uikit.components.DisclosureIcon
import com.pocketshell.uikit.components.LoadingIndicator
import com.pocketshell.uikit.components.SpinnerSize
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellType
import kotlinx.coroutines.launch

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
    // Issue #176/#1267: honour the system-notes preference, but the byte-clamp
    // truncation marker (#1225) stays visible even when notes are off — see
    // [conversationTimelineVisibleEvents].
    val visibleEvents = remember(events, showSystemNotes) {
        conversationTimelineVisibleEvents(events, showSystemNotes)
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
            animationSpec = tween(durationMillis = ConversationPaneMotionDurationMs, easing = ConversationPaneMotionEasing),
        ),
        exit = fadeOut(
            animationSpec = tween(durationMillis = ConversationPaneMotionDurationMs, easing = ConversationPaneMotionEasing),
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
            fontSize = JumpToLatestFontSize,
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

private val JumpToLatestFontSize = 12.sp
private val SystemNoteHeadFontSize = 10.sp

/** System note header style (10sp uppercase matching .msg-head) */
private val SystemNoteHeadStyle = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontSize = SystemNoteHeadFontSize,
    fontWeight = FontWeight.Bold,
)

private const val ConversationPaneMotionDurationMs: Int = 200
private val ConversationPaneMotionEasing = CubicBezierEasing(0f, 0f, 0.2f, 1f)
