package com.pocketshell.app.tmux

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketshell.app.sessions.HostTmuxSessionPickerState
import com.pocketshell.core.terminal.ui.TerminalSurfaceState
import com.pocketshell.uikit.components.CommandChip
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellSpacing

private const val OverlayMotionDurationMs: Int = 200
private val OverlayMotionEasing = CubicBezierEasing(0f, 0f, 0.2f, 1f)

/**
 * Issue #1235: one-tap agent approval replies. This is a regular bottom band,
 * not an overlay, so it reserves its own height above the composer/bottom
 * controls and never covers terminal content or the input controls.
 */
@Composable
internal fun AgentQuickReplyBand(
    terminalState: TerminalSurfaceState,
    enabled: Boolean,
    onReply: (AgentQuickReply) -> Unit,
    modifier: Modifier = Modifier,
) {
    val replies by remember(terminalState, enabled) {
        agentQuickRepliesForVisibleTextFlow(
            visibleText = terminalState.flowOfVisibleScreenText,
            enabled = enabled,
        )
    }.collectAsState(initial = emptyList())
    if (replies.isNotEmpty()) {
        AgentQuickReplyRow(replies = replies, onReply = onReply, modifier = modifier)
    }
}

@Composable
internal fun AgentQuickReplyRow(
    replies: List<AgentQuickReply>,
    onReply: (AgentQuickReply) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (replies.isEmpty()) return
    val scrollState = rememberScrollState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(color = PocketShellColors.Surface)
            .border(width = 1.dp, color = PocketShellColors.Border)
            .horizontalScroll(scrollState)
            .padding(horizontal = PocketShellSpacing.sm, vertical = PocketShellSpacing.xs)
            .testTag(TMUX_AGENT_QUICK_REPLY_ROW_TAG),
        horizontalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        replies.forEachIndexed { index, reply ->
            CommandChip(
                label = reply.label,
                onClick = { onReply(reply) },
                modifier = Modifier.testTag(TMUX_AGENT_QUICK_REPLY_CHIP_TAG_PREFIX + index),
            )
        }
    }
}

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
        enter = fadeIn(animationSpec = tween(durationMillis = OverlayMotionDurationMs)),
        exit = fadeOut(animationSpec = tween(durationMillis = OverlayMotionDurationMs)),
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
internal fun SessionSwitcherOverlay(
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
        enter = fadeIn(animationSpec = tween(durationMillis = OverlayMotionDurationMs)) +
            slideInVertically(
                animationSpec = tween(durationMillis = OverlayMotionDurationMs, easing = OverlayMotionEasing),
                initialOffsetY = { it / 2 },
            ),
        exit = fadeOut(animationSpec = tween(durationMillis = OverlayMotionDurationMs)) +
            slideOutVertically(
                animationSpec = tween(durationMillis = OverlayMotionDurationMs, easing = OverlayMotionEasing),
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

internal data class SessionSwitcherPage(
    val name: String,
    val statusLabel: String,
    val selectable: Boolean,
)
