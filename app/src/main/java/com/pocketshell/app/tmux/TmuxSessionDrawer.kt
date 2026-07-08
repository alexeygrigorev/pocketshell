package com.pocketshell.app.tmux

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketshell.app.sessions.HostTmuxSessionPickerState
import com.pocketshell.app.sessions.HostTmuxSessionRow
import com.pocketshell.uikit.components.Badge
import com.pocketshell.uikit.components.BadgeRole
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.SectionHeader
import com.pocketshell.uikit.components.StatusDot
import com.pocketshell.uikit.model.ConnectionStatus as UiConnectionStatus
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType

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
        enter = fadeIn(animationSpec = tween(durationMillis = TmuxDrawerMotionDurationMs)),
        exit = fadeOut(animationSpec = tween(durationMillis = TmuxDrawerMotionDurationMs)),
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
            animationSpec = tween(durationMillis = TmuxDrawerMotionDurationMs, easing = TmuxDrawerMotionEasing),
            initialOffsetX = { it },
        ),
        exit = slideOutHorizontally(
            animationSpec = tween(durationMillis = TmuxDrawerMotionDurationMs, easing = TmuxDrawerMotionEasing),
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
                // Issue #156 (4.1): stack the header vertically with the close
                // affordance pinned top-right so it does not compress the title.
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
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(36.dp)
                            .testTag(TMUX_SESSION_DRAWER_CLOSE_TAG)
                            .clickable(
                                role = Role.Button,
                                onClick = onDismiss,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "\u00d7",
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
                    text = "\u203a",
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

private val SessionDrawerMaxWidth = 360.dp
private const val TmuxDrawerMotionDurationMs: Int = 200
private val TmuxDrawerMotionEasing = CubicBezierEasing(0f, 0f, 0.2f, 1f)

internal const val TMUX_SESSION_SWITCHER_TAG = "tmux:session-switcher"
internal const val TMUX_SESSION_DRAWER_CLOSE_TAG = "tmux:session-drawer:close"
internal const val TMUX_SESSION_DRAWER_CREATE_TAG = "tmux:session-drawer:create"
internal const val TMUX_SESSION_DRAWER_REFRESH_TAG = "tmux:session-drawer:refresh"
