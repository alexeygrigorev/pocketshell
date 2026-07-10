package com.pocketshell.app.tmux

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketshell.app.sessions.HostTmuxSessionPickerViewModel
import com.pocketshell.uikit.components.KebabTrigger
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellType

private const val ChromeMotionDurationMs: Int = 200
private val ChromeMotionEasing = CubicBezierEasing(0f, 0f, 0.2f, 1f)

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
 * The pulse is keyed on the false -> true transition of [pulseVisible]
 * (which the call site drives off `showConversationTab`); once the
 * timer fires it does not re-trigger until the tab disappears and
 * reappears (e.g. after a fresh agent detection on a new session).
 *
 * The wrapper uses [wrapContentSize] so it does not stretch within the
 * 56dp consolidated chrome row - the pill claims its natural width and
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
    // since the screen mounted - without this, the initial composition
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
            // Tab disappeared (agent process died, etc.) - reset so a
            // future re-appearance fires the pulse again.
            hasSeenConversationTab = false
            showPulse = false
        }
    }
    Box(modifier = Modifier.wrapContentSize()) {
        content()
        AnimatedVisibility(
            visible = showPulse,
            enter = fadeIn(
                animationSpec = tween(durationMillis = ChromeMotionDurationMs, easing = ChromeMotionEasing),
            ),
            exit = fadeOut(
                animationSpec = tween(durationMillis = TAB_PULSE_DURATION_MS.toInt(), easing = ChromeMotionEasing),
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
 * Issues #189 / #192: a single-row 56dp consolidated top chrome on
 * [TmuxSessionScreen].
 *
 * Per decision D30 (issue #782) PocketShell no longer manages tmux windows,
 * so there is NO window-tab row: Terminal/Conversation is the only in-session
 * tab dimension. Issue #303 renders that toggle inline in this chrome so it
 * does not cost a separate row. Windows that already exist on the server are
 * surfaced in the host tree as separate `[wN]` switcher entries, not as an
 * in-session strip.
 *
 * Layout (left -> right inside one 56dp [Row]):
 * - 48dp back affordance (chevron).
 * - connection status dot + compact "Reconnecting"/"Disconnected" pill.
 * - `session` crumb (current destination; non-interactive) taking the
 *   remaining width.
 * - optional inline Terminal/Conversation pill when an agent or locked
 *   conversation is available.
 * - 48dp more affordance (kebab), which owns the dropdown anchor. Active
 *   port-forwarding status lives INSIDE that kebab menu (issue #601), not in
 *   the header row, so it never steals terminal chrome/content space.
 *
 * The host segment is intentionally not surfaced - the host name is
 * already visible on the host list, the pre-session status line, and on
 * [FailedConnectionRow] when not connected.
 */
@Composable
internal fun ConsolidatedTopChrome(
    sessionName: String,
    onBack: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    moreMenu: @Composable () -> Unit = {},
    // Issue #481: when the visible pane has a detected agent, the header
    // title is the agent/model name (e.g. `claude-3-5-sonnet`) per the
    // maintainer's terminal mockup, instead of the tmux session name. Null
    // (no agent / plain shell) falls back to [sessionName].
    agentName: String? = null,
    tabLabels: List<String> = emptyList(),
    selectedTabIndex: Int = 0,
    onTabSelected: (Int) -> Unit = {},
    pulseConversationTab: Boolean = false,
    // Issue #463: the in-session project switcher. When [projectLabel] is
    // non-null a tappable project/folder crumb renders at the leading edge
    // of the title slot. It shows a chevron and opens [projectSwitcher]'s
    // sibling-session dropdown only when there is at least one OTHER session
    // in the same project to switch to. Selecting a sibling fires
    // [onSwitchToSibling], which routes through the warm same-host switch.
    projectLabel: String? = null,
    projectSwitcher: HostTmuxSessionPickerViewModel.ProjectSwitcherState =
        HostTmuxSessionPickerViewModel.ProjectSwitcherState(),
    onProjectSwitcherOpen: () -> Unit = {},
    onSwitchToSibling: (String) -> Unit = {},
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
            // Issue #637: a single horizontal inset on both edges keeps the
            // back chevron and the kebab a consistent, comfortable distance
            // from the screen edges in BOTH the breadcrumb and the
            // agent-toggle layout, instead of the kebab looking like it
            // floats at an odd offset.
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Back chevron - 48dp touch target so edge taps land on the
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
        Spacer(modifier = Modifier.width(8.dp))

        // Issue #1320: the LEADING yielding region - the ONLY part of the
        // header that gives up width under pressure. It holds the project
        // crumb, the title, and the connection-status pill, all wrapped in a
        // single `weight(1f)` slot. Everything to its right (the
        // Terminal/Conversation toggle and the kebab) is a NON-weighted sibling
        // measured at its full intrinsic width FIRST, so the toggle can never be
        // squeezed/clipped - it is a primary control and must always be fully
        // visible + tappable.
        //
        // Why this shape (the 5x-recurrence root cause #962/#975/#1057/#1158):
        // the toggle used to live INSIDE a `weight(1f, fill = false)` trailing
        // slot that competed with the title's own `weight(1f)`. Two `weight(1f)`
        // slots split the remaining width, so a long agent/session title (e.g.
        // "pocketshell Claude Code") starved the toggle's slot and its
        // "Conversation" segment ellipsised away - leaving only "Terminal" and
        // no way to switch to the conversation view. Pulling the toggle OUT of
        // the weighted slot and reserving it at intrinsic width fixes the clip
        // at the layout level (not by adding another detection OR-term - the
        // detection gate was never the cause of this report).
        //
        // Within this region the yield order is: the title (its own
        // `weight(1f)` + ellipsis) shrinks FIRST; the crumb (capped <=120dp,
        // ellipsis) and the connection-status pill only clip under extreme
        // pressure - both are acceptable to shrink, the toggle is not.
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Issue #463: the tappable project/folder crumb. Opens a dropdown
            // of this project's sibling sessions; selecting one warm-switches
            // to it. Hidden entirely when we don't know the project; the
            // chevron is hidden when there's nothing to switch to.
            if (projectLabel != null) {
                ProjectSwitcherCrumb(
                    projectLabel = projectLabel,
                    switcher = projectSwitcher,
                    onOpen = onProjectSwitcherOpen,
                    onSwitchToSibling = onSwitchToSibling,
                )
                Spacer(modifier = Modifier.width(6.dp))
            }

            // Issue #481: the title - the agent/model name when a conversation
            // is detected (`claude-3-5-sonnet` in the mockup), otherwise the
            // tmux session name. It takes the inner `weight(1f)` slot so it is
            // the FIRST element to yield/ellipsise (issue #1320), keeping the
            // crumb, pill, toggle, and kebab intact. The 8dp end padding keeps
            // the name from butting straight against the pill/toggle.
            val sessionLabelModifier = Modifier
                .weight(1f)
                .padding(end = 8.dp)
                .testTag(TMUX_CONSOLIDATED_SESSION_LABEL_TAG)
            Text(
                text = agentName ?: sessionName,
                color = PocketShellColors.Text,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = sessionLabelModifier,
            )

            // Issues #177 / #249: the compact "Reconnecting"/"Disconnected"
            // pill. It sits at the right edge of the yielding region (adjacent
            // to the toggle) so, under extreme width pressure, it clips AFTER
            // the title but BEFORE the toggle (#1320 - the toggle never yields).
            ConnectionStatusPill(connectionStatus)
        }

        // Issue #1320: the Terminal/Conversation toggle - a PRIMARY control that
        // must have GUARANTEED width. It is a NON-weighted sibling of the outer
        // row rendered at its full intrinsic width, so it is measured before the
        // weighted leading region gets the remainder and can never be
        // clipped/ellipsised no matter how long the title is. The kebab stays a
        // fixed 48dp sibling laid out after it (#747), so both survive.
        if (tabLabels.size > 1) {
            Spacer(modifier = Modifier.width(8.dp))
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

        // The kebab - fixed 48dp, OUTSIDE every weighted slot, so it is always
        // reserved its full width at the right edge (#747).
        Box(modifier = Modifier.size(48.dp)) {
            KebabTrigger(
                contentDescription = "More session actions",
                onClick = onMore,
                triggerTestTag = TMUX_FULL_CHROME_MORE_BUTTON_TAG,
                triggerSize = 48.dp,
            )
            moreMenu()
        }
    }
}

/**
 * Issue #463: the tappable project/folder crumb in the session header that
 * opens an in-session, project-scoped session switcher.
 *
 * The crumb shows the current project's leaf folder label and a chevron.
 * Tapping it anchors a [DropdownMenu] listing the sibling sessions in the
 * same project (sourced from the warm live `-CC` client - never a fresh SSH
 * connect), each row showing the session name, a state chip, and a
 * [com.pocketshell.uikit.components.StatusDot] consistent with the folder
 * tree rows. Selecting a sibling fires [onSwitchToSibling], which routes
 * through the existing `onReplaceTmuxSession` -> warm same-host switch (no
 * reconnect; status flips to `Switching`, not `Connecting`).
 *
 * The chevron - and the whole tap affordance - is only shown when the
 * project has at least one OTHER session to switch to
 * ([HostTmuxSessionPickerViewModel.ProjectSwitcherState.hasSiblingsToSwitch]).
 * A single-session project renders a plain, non-interactive label so there
 * is zero Fitts/Hick cost when there is nothing to switch to.
 */
@Composable
private fun ProjectSwitcherCrumb(
    projectLabel: String,
    switcher: HostTmuxSessionPickerViewModel.ProjectSwitcherState,
    onOpen: () -> Unit,
    onSwitchToSibling: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val canSwitch = switcher.hasSiblingsToSwitch
    Box(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .then(
                    if (canSwitch) {
                        Modifier.clickable(
                            role = androidx.compose.ui.semantics.Role.Button,
                        ) {
                            onOpen()
                            expanded = true
                        }
                    } else {
                        Modifier
                    },
                )
                .background(
                    color = if (canSwitch) {
                        PocketShellColors.Accent.copy(alpha = 0.14f)
                    } else {
                        Color.Transparent
                    },
                    shape = PocketShellShapes.extraSmall,
                )
                .padding(horizontal = if (canSwitch) 8.dp else 0.dp, vertical = 4.dp)
                .testTag(TMUX_PROJECT_SWITCHER_TAG),
        ) {
            Text(
                text = projectLabel,
                color = if (canSwitch) PocketShellColors.Accent else PocketShellColors.TextSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 120.dp),
            )
            if (canSwitch) {
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                    text = "▾",
                    color = PocketShellColors.Accent,
                    style = PocketShellType.labelMono,
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.testTag(TMUX_PROJECT_SWITCHER_MENU_TAG),
        ) {
            val rows = switcher.siblings
            rows.forEach { row ->
                val isCurrent = row.name == switcher.currentSessionName
                DropdownMenuItem(
                    enabled = !isCurrent,
                    onClick = {
                        expanded = false
                        if (!isCurrent) onSwitchToSibling(row.name)
                    },
                    modifier = Modifier.testTag(TMUX_PROJECT_SWITCHER_ROW_TAG_PREFIX + row.name),
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            com.pocketshell.uikit.components.StatusDot(
                                status = if (isCurrent || row.attached) {
                                    com.pocketshell.uikit.model.ConnectionStatus.Connected
                                } else {
                                    com.pocketshell.uikit.model.ConnectionStatus.Idle
                                },
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = row.name,
                                    color = PocketShellColors.Text,
                                    fontSize = 14.sp,
                                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = when {
                                        isCurrent -> "Current"
                                        row.attached -> "Attached"
                                        else -> "Available"
                                    },
                                    color = PocketShellColors.TextSecondary,
                                    style = PocketShellType.labelMono,
                                )
                            }
                        }
                    },
                )
            }
        }
    }
}

/**
 * Issue #189: the inline Terminal / Conversation toggle inside
 * [ConsolidatedTopChrome]. Visually a slim segmented pill - both labels
 * inside one bordered, rounded container - so the user reads it as a
 * single co-located control rather than two separate buttons. The
 * selected segment takes [PocketShellColors.Accent] fill; the
 * unselected segments sit on transparent background and lean on
 * [PocketShellColors.TextSecondary].
 *
 * Sized to fit inside the 56dp toolbar row with room for two short
 * labels - the only call site today is the two-label "Terminal /
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
    // Issue #481: the inline Terminal/Conversation toggle now composes the
    // shared [com.pocketshell.uikit.components.SegmentedToggle] so it reads
    // identically to every other segmented control in the app (cyan-active,
    // dark-on-cyan label). The per-segment test tags stay: index 0 is the
    // Terminal segment ([TMUX_TERMINAL_TAB_TAG] - a named alias kept for
    // tests that previously asserted on the visible "Terminal" text, now
    // suppressed in single-tab shell-only sessions); other indices use the
    // generic per-index prefix hook.
    com.pocketshell.uikit.components.SegmentedToggle(
        labels = labels,
        selectedIndex = selectedIndex,
        onSelected = onSelected,
        modifier = modifier,
        segmentTag = { index ->
            if (index == 0) {
                TMUX_TERMINAL_TAB_TAG
            } else {
                TMUX_CONSOLIDATED_TAB_PILL_TAG_PREFIX + index
            }
        },
    )
}

/**
 * Issue #184 Layer 2: a slim replacement for the full
 * [com.pocketshell.uikit.components.Breadcrumb] that is shown while the
 * soft keyboard is up. The user is in typing-focus and does not need the
 * full `host > session > window > pane` chain - they already know what
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
 * - Session name only - no live dot, no separators, no host / window /
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
        val compactSessionLabelModifier = Modifier
            .weight(1f)
        Text(
            text = sessionName,
            color = PocketShellColors.Text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = compactSessionLabelModifier,
        )
        ConnectionStatusPill(connectionStatus)
        Spacer(modifier = Modifier.width(4.dp))
        Box(
            modifier = Modifier
                .width(48.dp)
                .fillMaxHeight(),
        ) {
            KebabTrigger(
                contentDescription = "More session actions",
                onClick = onMore,
                triggerTestTag = TMUX_COMPACT_CHROME_MORE_BUTTON_TAG,
                triggerSize = 48.dp,
            )
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
 * what the dot's colour signals - the user should never have to guess
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
                shape = PocketShellShapes.small,
            )
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .testTag(TMUX_CONNECTION_STATUS_PILL_TAG),
    )
}
