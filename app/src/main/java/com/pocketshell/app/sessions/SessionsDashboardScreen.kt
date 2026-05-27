package com.pocketshell.app.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.pocketshell.app.projects.WatchedFoldersChipRow
import com.pocketshell.uikit.components.SessionRow
import com.pocketshell.uikit.model.Tag
import com.pocketshell.uikit.model.TagKind
import com.pocketshell.uikit.theme.JetBrainsMonoFamily
import com.pocketshell.uikit.theme.PocketShellColors
import kotlin.math.max

internal const val DASHBOARD_SESSIONS_SECTION_TAG = "dashboard:sessions"
internal const val DASHBOARD_NEW_SESSION_TAG = "dashboard:sessions:new"
internal const val DASHBOARD_SESSION_ROW_TAG_PREFIX = "dashboard:sessions:row:"

/**
 * Tag for the create-session-failure banner — issue #204. Rendered
 * inside [SessionsSection] when [SessionsDashboardViewModel.createError]
 * is non-null so users see a duplicate-name rejection or transport
 * failure rather than wondering why the new row never appeared.
 */
const val DASHBOARD_CREATE_ERROR_BANNER_TAG: String = "dashboard:sessions:create-error"

/**
 * Tag for the "what do these icons mean?" legend toggle that sits at
 * the top of the dashboard's Sessions section — issue #202. The legend
 * itself is gated behind this toggle so the chrome stays quiet on the
 * default path; first-time users tap to expand and read each indicator.
 */
const val DASHBOARD_SESSIONS_LEGEND_TOGGLE_TAG: String = "dashboard:sessions:legend:toggle"

/** Tag for the expanded legend panel — issue #202. */
const val DASHBOARD_SESSIONS_LEGEND_PANEL_TAG: String = "dashboard:sessions:legend:panel"

/**
 * Tag for the one-shot kill-failure banner — issue #168. The banner is
 * rendered inside `HostListScreen` (which owns the Scaffold-shaped
 * column) but the tag lives next to the dashboard's other tags so test
 * code referring to it has one obvious import path.
 */
const val DASHBOARD_KILL_ERROR_BANNER_TAG: String = "dashboard:sessions:kill-error"

/**
 * Sessions section of the dashboard — issue #46.
 *
 * Inlined into [com.pocketshell.app.hosts.HostListScreen] above the
 * "Hosts" section per the mockup at `docs/mockups/dashboard.html`. Renders
 * one [SessionRow] per [SessionSummary] from the view model, sorted by
 * recency (most-recent first — handled inside the view model, not here).
 *
 * The section composable itself is responsible for nothing more than
 * fan-out: it asks the view model for the current list, and for each
 * entry it asks the view model to resolve a navigation tuple at tap
 * time. Tap handling delegates to [onOpenTmuxSession], which the host
 * screen passes through to the navigator.
 *
 * If the view model's session list is empty the section renders nothing
 * — the host screen gates on `sessions.isNotEmpty()` for the
 * surrounding section label so the chrome doesn't appear above an
 * empty list. The section is also rendered inside a normal Compose
 * column (no `LazyColumn`) — the expected session count is small
 * (single digits per host, a handful of hosts) so the recycling cost of
 * a LazyColumn is not worth the layout complexity here.
 *
 * @param onOpenTmuxSession invoked when the user taps a session row.
 *   The host screen resolves it through the navigator. Default no-op
 *   so unit tests / previews can compose the section without setting
 *   up a navigator stub.
 */
@Composable
fun SessionsSection(
    modifier: Modifier = Modifier,
    viewModel: SessionsDashboardViewModel = hiltViewModel(),
    hostIdFilter: Long? = null,
    onOpenTmuxSession: (ActiveTmuxClients.Entry, sessionName: String, startDirectory: String?) -> Unit =
        { _, _, _ -> },
) {
    val allSessions by viewModel.sessions.collectAsState()
    val sessions = if (hostIdFilter == null) {
        allSessions
    } else {
        allSessions.filter { it.hostId == hostIdFilter }
    }
    // Issue #204: even when [sessions] is empty we still render the
    // section if the view model is currently surfacing a create-session
    // error. Without that, dismissing the dialog after a duplicate-name
    // rejection that ALSO happens to leave the host with zero sessions
    // would hide the banner before the user could read it. The
    // [createError] StateFlow is the source of truth and the section
    // collapses again the moment the user dismisses it.
    val createError by viewModel.createError.collectAsState()
    if (sessions.isEmpty() && createError == null) return

    val nowSec = System.currentTimeMillis() / 1000L
    var selectedSession by remember { mutableStateOf<SessionSummary?>(null) }
    var dialogMode by remember { mutableStateOf<DashboardDialogMode?>(null) }
    var dialogText by remember { mutableStateOf("") }
    var dialogStartDirectory by remember { mutableStateOf(DEFAULT_TMUX_START_DIRECTORY) }
    // Per issue #202, the legend is closed by default so the chrome
    // stays quiet on the default path. First-time users tap the "?"
    // toggle to see what each indicator means.
    var legendExpanded by remember { mutableStateOf(false) }

    fun openDialog(
        mode: DashboardDialogMode,
        initialText: String = "",
        initialStartDirectory: String = DEFAULT_TMUX_START_DIRECTORY,
    ) {
        dialogMode = mode
        dialogText = initialText
        dialogStartDirectory = initialStartDirectory
    }

    // Issue #224 (audit #208 row 18 follow-up): the `+ New session`
    // affordance was originally a `TextButton` anchored at the
    // top-left of the section header. The audit flagged it as the
    // lone non-right-aligned FAB across the app — every other primary
    // FAB (host-list `+`, tmux mic FAB, jump-to-latest) anchors to
    // `Alignment.BottomEnd`. The fix moves the affordance to a
    // circular FAB pinned at the bottom-right corner of the section's
    // content area so right-thumb reach for "create a new session"
    // matches the rest of the design system (see design-system.md §9
    // and ux-rules.md rule 3).
    //
    // Layout: a `Box` wraps the section content so the FAB can use
    // `Modifier.align(Alignment.BottomEnd)`. The Box's height wraps
    // the inner Column (no `fillMaxSize` — `SessionsSection` is one
    // item in `HostListScreen`'s vertical Column and must not consume
    // the slack the Hosts section below needs). The FAB therefore
    // anchors to the bottom-right of the section's bounds, not the
    // screen's bounds — that's intentional: anchoring to the screen
    // would collide with HostListScreen's existing host-list `+` FAB
    // (both at `Alignment.BottomEnd` of the same outer Box). Keeping
    // the new-session FAB section-scoped puts it inside the right-
    // thumb arc while preserving the host-list FAB as the bottom-of-
    // screen primary action.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .testTag(DASHBOARD_SESSIONS_SECTION_TAG),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Header row: the `+ New session` affordance is now a
            // bottom-right FAB (see the Box overlay below), so this
            // row only carries the legend toggle. Right-aligned via
            // `Arrangement.End` so the toggle sits opposite the
            // section's content rather than floating at the left
            // edge.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SessionsLegendToggle(
                    expanded = legendExpanded,
                    onClick = { legendExpanded = !legendExpanded },
                )
            }
            if (legendExpanded) {
                SessionsLegend()
            }
            // Issue #204: surface create-session failures (transport errors,
            // tmux %error such as duplicate-name rejection) inline. The
            // banner lives inside the section so it sits next to the
            // affordance that triggered it; HostListScreen owns the kill
            // banner instead, which is consistent with the original wiring
            // because the kill flow can fire from anywhere on the dashboard
            // surface.
            createError?.let { msg ->
                CreateErrorBanner(
                    message = msg,
                    onDismiss = { viewModel.clearCreateError() },
                )
            }
            sessions.forEach { summary ->
                Box(modifier = Modifier.fillMaxWidth()) {
                    val rowUi = summary.dashboardRowUi()
                    SessionRow(
                        modifier = Modifier.testTag(DASHBOARD_SESSION_ROW_TAG_PREFIX + summary.sessionName),
                        badge = rowUi.badge,
                        name = summary.sessionName,
                        host = summary.hostName,
                        preview = rowUi.preview,
                        time = formatRelativeTime(nowSec = nowSec, thenSec = summary.lastActivity),
                        tags = rowUi.tags,
                        onClick = {
                            // Resolve the navigation tuple via the view model's
                            // entry lookup — the row stays light, the view
                            // model owns the registry handle. If the host has
                            // unregistered between render and tap we drop the
                            // tap silently; the row will disappear on the next
                            // poll cycle.
                            val entry = viewModel.entryFor(summary.hostId) ?: return@SessionRow
                            onOpenTmuxSession(entry, summary.sessionName, null)
                        },
                        onLongClick = {
                            selectedSession = summary
                        },
                    )
                    DashboardSessionMenu(
                        expanded = selectedSession == summary && dialogMode == null,
                        onDismiss = { selectedSession = null },
                        onAttach = {
                            val entry = viewModel.entryFor(summary.hostId)
                            if (entry != null) {
                                selectedSession = null
                                onOpenTmuxSession(entry, summary.sessionName, null)
                            }
                        },
                        onRename = {
                            selectedSession = summary
                            openDialog(DashboardDialogMode.RenameSession, summary.sessionName)
                        },
                        onKill = {
                            selectedSession = summary
                            dialogMode = DashboardDialogMode.KillSession
                        },
                    )
                }
            }
        }
        // Bottom-right FAB — primary `+ New session` affordance.
        // Mirrors `HostListScreen.kt:589-605` (the gold-standard host-
        // list FAB cited in audit #208 row 20): 56 dp circle, `Accent`
        // background, `+` glyph, anchored to `Alignment.BottomEnd` of
        // its parent Box. The button is gated on at least one
        // session existing across the registered hosts (the section
        // returns early on empty), so the FAB is only reachable when
        // there is a host to create the session against — preserves
        // the original gating logic from the previous `TextButton`.
        // The `DASHBOARD_NEW_SESSION_TAG` tag is carried over so
        // `SessionCreateDashboardE2eTest` (#204) and any other tag-
        // driven tests keep matching.
        FloatingActionButton(
            onClick = {
                val entry = sessions.firstOrNull()?.let { viewModel.entryFor(it.hostId) }
                    ?: return@FloatingActionButton
                selectedSession = SessionSummary(
                    hostId = entry.hostId,
                    hostName = entry.hostName,
                    sessionName = "",
                    lastActivity = nowSec,
                    attached = false,
                )
                openDialog(DashboardDialogMode.CreateSession)
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 8.dp, bottom = 8.dp)
                .size(56.dp)
                .testTag(DASHBOARD_NEW_SESSION_TAG),
            shape = CircleShape,
            containerColor = PocketShellColors.Accent,
            contentColor = PocketShellColors.OnAccent,
        ) {
            Text(
                text = "+",
                fontSize = 28.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }

    val currentDialog = dialogMode
    val currentSession = selectedSession
    if (currentDialog != null && currentSession != null) {
        DashboardLifecycleDialog(
            mode = currentDialog,
            sessionName = currentSession.sessionName,
            // Issue #206: thread the host id so the create-session
            // dialog can render the watched-folders chip row above
            // the start-folder field. The id is null for rename /
            // kill modes which don't render the chip row anyway.
            hostId = currentSession.hostId,
            text = dialogText,
            onTextChange = { dialogText = it },
            startDirectory = dialogStartDirectory,
            onStartDirectoryChange = { dialogStartDirectory = it },
            onDismiss = {
                dialogMode = null
                selectedSession = null
            },
            onConfirm = {
                val entry = viewModel.entryFor(currentSession.hostId)
                if (entry != null) {
                    when (currentDialog) {
                        DashboardDialogMode.CreateSession -> {
                            // Issue #204: the previous wiring invoked
                            // `onOpenTmuxSession` here, which attaches an
                            // interactive client to a NEW tmux session
                            // (creating it on the way in). That hijacked
                            // the dashboard's "+ New session" affordance
                            // into a navigation move that conflated
                            // "create" and "open". Per the new
                            // acceptance criteria the button now runs
                            // `new-session -d` (detached) via the view
                            // model, refreshes the list, and leaves the
                            // user on the dashboard so they can decide
                            // whether to attach. Attach is a separate
                            // affordance (tap the row).
                            viewModel.createSession(
                                entry = entry,
                                name = dialogText,
                                startDirectory = dialogStartDirectory,
                            )
                        }
                        DashboardDialogMode.RenameSession -> {
                            viewModel.renameSession(
                                entry = entry,
                                oldName = currentSession.sessionName,
                                newName = dialogText,
                            )
                        }
                        DashboardDialogMode.KillSession -> {
                            viewModel.killSession(entry, currentSession.sessionName)
                        }
                    }
                }
                dialogMode = null
                selectedSession = null
            },
        )
    }
}

private enum class DashboardDialogMode {
    CreateSession,
    RenameSession,
    KillSession,
}

@Composable
private fun DashboardSessionMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onAttach: () -> Unit,
    onRename: () -> Unit,
    onKill: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        DropdownMenuItem(text = { Text("Attach") }, onClick = onAttach)
        DropdownMenuItem(text = { Text("Rename") }, onClick = onRename)
        DropdownMenuItem(text = { Text("Kill") }, onClick = onKill)
    }
}

@Composable
private fun DashboardLifecycleDialog(
    mode: DashboardDialogMode,
    sessionName: String,
    hostId: Long? = null,
    text: String,
    onTextChange: (String) -> Unit,
    startDirectory: String,
    onStartDirectoryChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val isTextMode = mode != DashboardDialogMode.KillSession
    val isCreateMode = mode == DashboardDialogMode.CreateSession
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (mode) {
                    DashboardDialogMode.CreateSession -> "New session"
                    DashboardDialogMode.RenameSession -> "Rename session"
                    DashboardDialogMode.KillSession -> "Kill session"
                },
            )
        },
        text = {
            if (isCreateMode) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = onTextChange,
                        singleLine = true,
                        label = { Text("Session name") },
                    )
                    // Issue #206 + #204: watched-folders chip row.
                    // Lives above the start-folder field so tapping a
                    // chip pre-populates the field — no further input
                    // needed before pressing Save.
                    WatchedFoldersChipRow(
                        hostId = hostId,
                        onChipTap = { path -> onStartDirectoryChange(path) },
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
                    label = { Text("Session name") },
                )
            } else {
                Text("This will close $sessionName.")
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = isCreateMode || !isTextMode || text.trim().isNotEmpty(),
            ) {
                Text(if (mode == DashboardDialogMode.KillSession) "Kill" else "Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

/**
 * Inline banner used for the create-session failure path — issue #204.
 *
 * Visual treatment matches `HostListScreen`'s `ShareMessageBanner` so
 * the dashboard's banner vocabulary stays consistent across the
 * kill-error / share / create-error surfaces. The banner is fully
 * self-contained inside the sessions module so adding a new error
 * surface here doesn't force a sibling edit on `HostListScreen`
 * (parallel-issue file ownership concern called out in the issue
 * brief).
 */
@Composable
private fun CreateErrorBanner(message: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PocketShellColors.Surface, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag(DASHBOARD_CREATE_ERROR_BANNER_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            color = PocketShellColors.TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss) {
            Text("Dismiss", color = PocketShellColors.Accent, fontSize = 12.sp)
        }
    }
}

/**
 * Compact "?" toggle that opens / closes the indicator legend — issue
 * #202. Originally sat opposite the top-left `+ New session`
 * TextButton; after issue #224 moved the new-session affordance to a
 * bottom-right FAB (audit #208 row 18), the toggle is the lone
 * header occupant and renders right-aligned via the parent Row's
 * `Arrangement.End`.
 *
 * The toggle is intentionally text-only ("? Legend" / "Hide legend")
 * rather than icon-only because the goal is "first-time-user
 * clarity" — an icon would just push the discoverability problem one
 * level deeper.
 */
@Composable
private fun SessionsLegendToggle(
    expanded: Boolean,
    onClick: () -> Unit,
) {
    TextButton(
        modifier = Modifier.testTag(DASHBOARD_SESSIONS_LEGEND_TOGGLE_TAG),
        onClick = onClick,
    ) {
        Text(if (expanded) "Hide legend" else "? Legend")
    }
}

/**
 * Legend panel — issue #202. Lists every indicator that
 * [dashboardRowUi] can emit so a first-time user can decode the row
 * chips without external help. Driven by [SESSIONS_LEGEND_ENTRIES] so
 * the user-facing copy stays in sync with the renderable kinds
 * (covered by [com.pocketshell.app.sessions.dashboardRowUiAndLegendStayInSync]).
 *
 * The panel includes both classifier and activity-state entries to
 * make the visual split between the two slots obvious: classifier
 * chips are plain coloured pills, activity-state chips lead with a
 * coloured dot. The legend renders the same chip shape so the user
 * sees exactly what they'll meet in the row.
 *
 * Also documents the leading 38dp accent badge (which is purely the
 * session-name initial, not an agent indicator) — without that note,
 * the cyan badge can be read as redundant with the cyan agent chip.
 */
@Composable
internal fun SessionsLegend(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = PocketShellColors.Surface,
                shape = RoundedCornerShape(10.dp),
            )
            .border(
                width = 1.dp,
                color = PocketShellColors.BorderSoft,
                shape = RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag(DASHBOARD_SESSIONS_LEGEND_PANEL_TAG),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "What the indicators mean",
            color = PocketShellColors.Text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        // Badge explainer first — without it, the cyan-on-cyan
        // badge / agent-chip pairing reads as a duplicate indicator
        // rather than two distinct affordances.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(
                        color = PocketShellColors.AccentSoft,
                        shape = RoundedCornerShape(8.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "A",
                    color = PocketShellColors.Accent,
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "First letter of the session name (visual anchor only)",
                color = PocketShellColors.TextSecondary,
                fontSize = 12.sp,
            )
        }
        // One row per emittable Tag kind so the user sees the chip
        // they'll meet in a row and the meaning side by side.
        SESSIONS_LEGEND_ENTRIES.forEach { entry ->
            LegendRow(entry = entry)
        }
    }
}

/**
 * One legend row: the chip on the left, the human-readable
 * explanation on the right. Chip rendering must match what
 * `SessionRow.TagChip` produces; we re-implement it here (rather than
 * reaching into ui-kit's private TagChip) so the legend stays a
 * self-contained Compose tree and the ui-kit API surface stays small.
 */
@Composable
private fun LegendRow(entry: LegendEntry) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LegendChip(label = entry.label, kind = entry.kind)
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = entry.description,
            color = PocketShellColors.TextSecondary,
            fontSize = 12.sp,
        )
    }
}

/**
 * Tag-chip mirror used by the legend. Matches `SessionRow.TagChip`
 * pixel-for-pixel; kept in sync by hand (the surface area is small
 * and a shared component would force exposing internal styling in
 * the ui-kit's public API).
 */
@Composable
private fun LegendChip(label: String, kind: TagKind) {
    val (textColor: Color, bgColor: Color) = when (kind) {
        TagKind.Default -> PocketShellColors.TextMuted to PocketShellColors.SurfaceElev
        TagKind.Agent -> PocketShellColors.Accent to PocketShellColors.AccentSoft
        TagKind.Deploy -> PocketShellColors.Amber to PocketShellColors.Amber.copy(alpha = 0.12f)
        TagKind.Ml -> PocketShellColors.Purple to PocketShellColors.Purple.copy(alpha = 0.12f)
        TagKind.Attached -> PocketShellColors.Green to PocketShellColors.Green.copy(alpha = 0.12f)
        TagKind.Detached -> PocketShellColors.TextMuted to PocketShellColors.SurfaceElev
    }
    val showLeadingDot: Boolean = kind == TagKind.Attached || kind == TagKind.Detached
    val dotColor: Color = if (kind == TagKind.Attached) {
        PocketShellColors.Green
    } else {
        PocketShellColors.TextMuted
    }
    Row(
        modifier = Modifier
            .background(color = bgColor, shape = RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showLeadingDot) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(color = dotColor, shape = CircleShape),
            )
            Spacer(modifier = Modifier.width(5.dp))
        }
        Text(
            text = label,
            color = textColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Format a tmux `session_activity` timestamp (seconds since epoch) as a
 * short relative duration string, matching the mockup's `2m / 8m / 14m
 * / 1h` cadence.
 *
 * Granularities:
 *  - `<1m` => `now`
 *  - `<60m` => `<n>m`
 *  - `<24h` => `<n>h`
 *  - else => `<n>d`
 *
 * Visible internal so the unit test can drive it with a fixed `nowSec`
 * — `System.currentTimeMillis()` would otherwise make the assertion
 * flaky.
 */
internal fun formatRelativeTime(nowSec: Long, thenSec: Long): String {
    val deltaSec = max(0L, nowSec - thenSec)
    return when {
        deltaSec < 60L -> "now"
        deltaSec < 3_600L -> "${deltaSec / 60L}m"
        deltaSec < 86_400L -> "${deltaSec / 3_600L}h"
        else -> "${deltaSec / 86_400L}d"
    }
}

internal data class DashboardSessionRowUi(
    val badge: String,
    val preview: String,
    val tags: List<Tag>,
)

/**
 * Build the [DashboardSessionRowUi] projection for one [SessionSummary]
 * — the per-row indicator vocabulary surfaced in the dashboard.
 *
 * ## Indicator vocabulary (issue #202)
 *
 * Two visually distinct slot categories live on every row:
 *
 *  - **Classifier tag** (cyan / amber / purple / neutral) — *what kind
 *    of session this is*. Derived heuristically from the tmux session
 *    name today: `claude*` → Claude, `codex*` → Codex, `opencode*` →
 *    OpenCode, `*agent*` → Agent, `*deploy* | *prod*` → Deploy, `*ml*
 *    | *gpu* | *train*` → ML. At most one classifier per row so a
 *    session called `claude-deploy` still picks the agent kind (the
 *    primary purpose of the session) rather than stacking two
 *    classifiers.
 *  - **Activity-state tag** (green dot "Attached" or muted dot
 *    "Detached") — *what state the session is in right now*. Derived
 *    from `#{session_attached}` (non-zero → Attached). Always present
 *    so a first-time user never sees a row without state context.
 *
 * The two categories are kept on separate slots in the [tags] list
 * with the classifier first and the activity-state last. The
 * [SessionRow][com.pocketshell.uikit.components.SessionRow] renders
 * them as visually distinct chips (activity-state chips lead with a
 * small dot) so colour-encoding alone does not have to carry meaning.
 *
 * Labels are mixed-case ("Claude", "Detached") — issue #202 explicitly
 * replaces the original uppercase letter-spaced styling ("CLAUDE
 * CODE", "ATTACHED") that the maintainer reported as cryptic during
 * active development on v0.2.8.
 *
 * The "Detached" wording was picked deliberately over the ambiguous
 * "Idle" that issue #201 is removing from the host-card vocabulary —
 * "detached" is the canonical tmux term for "no clients attached" and
 * reads unambiguously to first-time users.
 *
 * The synthetic preview ("claude conversation active", "tmux session
 * idle") stays — it is a hint about *what the row is*, not a real
 * terminal preview. Real terminal previews are future work.
 */
internal fun SessionSummary.dashboardRowUi(): DashboardSessionRowUi {
    val normalized = sessionName.lowercase()
    val agentTag = when {
        normalized.contains("claude") -> Tag("Claude", TagKind.Agent)
        normalized.contains("codex") -> Tag("Codex", TagKind.Agent)
        normalized.contains("opencode") -> Tag("OpenCode", TagKind.Agent)
        normalized.contains("agent") -> Tag("Agent", TagKind.Agent)
        else -> null
    }
    val domainTag = when {
        normalized.contains("deploy") || normalized.contains("prod") -> Tag("Deploy", TagKind.Deploy)
        normalized.contains("train") || normalized.contains("gpu") || normalized.contains("ml") -> Tag("ML", TagKind.Ml)
        else -> null
    }
    // Activity-state is always surfaced — first-time user never has to
    // wonder whether the session is in use. Distinct visual slot from
    // the classifier chip (see component-level docs above).
    val activityTag: Tag = if (attached) {
        Tag("Attached", TagKind.Attached)
    } else {
        Tag("Detached", TagKind.Detached)
    }
    val previewAgentWord: String? = agentTag?.label?.lowercase()
    val preview = when {
        previewAgentWord != null && attached -> "$previewAgentWord conversation active"
        previewAgentWord != null -> "$previewAgentWord workspace ready"
        attached -> "attached tmux client"
        else -> "tmux session detached"
    }
    return DashboardSessionRowUi(
        badge = sessionName.firstOrNull()?.uppercaseChar()?.toString().orEmpty(),
        preview = preview,
        tags = listOfNotNull(agentTag, domainTag, activityTag),
    )
}

/**
 * Lookup table for the legend panel — exposes the canonical
 * (label, kind, description) triple for every tag that
 * [dashboardRowUi] can emit so the user-facing copy stays in sync with
 * the rendered chips. Visible-for-test so the screenshot/legend test
 * asserts every emittable kind has a legend row.
 */
internal data class LegendEntry(
    val label: String,
    val kind: TagKind,
    val description: String,
)

internal val SESSIONS_LEGEND_ENTRIES: List<LegendEntry> = listOf(
    LegendEntry(
        label = "Claude",
        kind = TagKind.Agent,
        description = "Claude Code agent CLI session",
    ),
    LegendEntry(
        label = "Codex",
        kind = TagKind.Agent,
        description = "OpenAI Codex CLI session",
    ),
    LegendEntry(
        label = "OpenCode",
        kind = TagKind.Agent,
        description = "OpenCode agent CLI session",
    ),
    LegendEntry(
        label = "Deploy",
        kind = TagKind.Deploy,
        description = "Deploy / pipeline / prod session",
    ),
    LegendEntry(
        label = "ML",
        kind = TagKind.Ml,
        description = "ML training / GPU / inference session",
    ),
    LegendEntry(
        label = "Attached",
        kind = TagKind.Attached,
        description = "A tmux client is attached to the session right now",
    ),
    LegendEntry(
        label = "Detached",
        kind = TagKind.Detached,
        description = "No tmux clients are attached to the session",
    ),
)
