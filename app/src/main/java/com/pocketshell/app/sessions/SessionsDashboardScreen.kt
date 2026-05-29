package com.pocketshell.app.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
internal const val DASHBOARD_SESSION_NAME_FIELD_TAG = "dashboard:sessions:session-name"
internal const val DASHBOARD_START_FOLDER_FIELD_TAG = "dashboard:sessions:start-folder"

/**
 * Tag for the create-session-failure banner — issue #204. Rendered
 * emitted by [sessionsDashboardItems] when [SessionsDashboardViewModel.createError]
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
 * Issues #268 / #269: the Sessions rows are now emitted as items of the
 * single screen-level `LazyColumn` owned by
 * [com.pocketshell.app.hosts.HostListScreen]. Previously the section was
 * a self-contained `Column` (no scroll, no height bound) plus its own
 * section-scoped `+` FAB. At high session counts that `Column` overflowed
 * the viewport: the lower session rows clipped with no scroll container,
 * the Hosts `LazyColumn` (`weight(1f)`) starved to ~0 height, and the
 * section's bottom-right FAB collided with the screen-level add-host FAB
 * (two `+` buttons). Folding everything into one `LazyColumn` makes the
 * whole dashboard scroll as one surface so every session row and the
 * Hosts list are always reachable, and removes the second FAB.
 *
 * State that must outlive individual list items (the selected session, the
 * lifecycle-dialog mode, the legend toggle, dialog field text) is hoisted
 * into [SessionsDashboardSectionState] via [rememberSessionsDashboardSectionState].
 * The host screen:
 *  - emits the section's list content with [LazyListScope.sessionsDashboardItems];
 *  - renders the lifecycle dialog overlay with [SessionsDashboardDialog]
 *    (outside the list, so it floats above the whole screen).
 *
 * The `+ New session` affordance is no longer a FAB. The screen keeps a
 * single bottom-right `+` FAB (add-host); creating a session is a button
 * in the Sessions header row (carrying [DASHBOARD_NEW_SESSION_TAG]) so
 * both actions stay reachable with exactly one FAB on screen.
 */

/**
 * Holder for the Sessions-section UI state that must survive across
 * individual `LazyColumn` items and the dialog overlay. Created with
 * [rememberSessionsDashboardSectionState] in the host screen and shared
 * between [LazyListScope.sessionsDashboardItems] and
 * [SessionsDashboardDialog].
 */
internal class SessionsDashboardSectionState {
    var selectedSession by mutableStateOf<SessionSummary?>(null)
    var dialogMode by mutableStateOf<DashboardDialogMode?>(null)
    var dialogText by mutableStateOf("")
    var dialogStartDirectory by mutableStateOf(DEFAULT_TMUX_START_DIRECTORY)

    // Per issue #202, the legend is closed by default so the chrome
    // stays quiet on the default path. First-time users tap the "?"
    // toggle to see what each indicator means.
    var legendExpanded by mutableStateOf(false)

    fun openDialog(
        mode: DashboardDialogMode,
        initialText: String = "",
        initialStartDirectory: String = DEFAULT_TMUX_START_DIRECTORY,
    ) {
        dialogMode = mode
        dialogText = initialText
        dialogStartDirectory = initialStartDirectory
    }

    fun dismissDialog() {
        dialogMode = null
        selectedSession = null
    }
}

@Composable
internal fun rememberSessionsDashboardSectionState(): SessionsDashboardSectionState =
    remember { SessionsDashboardSectionState() }

/**
 * Emit the Sessions section as items of the host screen's single
 * `LazyColumn` (issues #268 / #269). Renders, in order:
 *  - the legend toggle + `+ New session` button header row;
 *  - the expanded legend panel (when toggled);
 *  - the create-session error banner (issue #204);
 *  - one [SessionRow] per [SessionSummary].
 *
 * Returns nothing on an empty list with no pending create error — the
 * caller still emits the "Sessions" section label only when sessions are
 * present, matching the previous gating.
 *
 * @param onOpenTmuxSession invoked when the user taps a session row. The
 *   host screen resolves it through the navigator.
 */
internal fun LazyListScope.sessionsDashboardItems(
    state: SessionsDashboardSectionState,
    sessions: List<SessionSummary>,
    createError: String?,
    nowSec: Long,
    entryFor: (hostId: Long) -> ActiveTmuxClients.Entry?,
    onClearCreateError: () -> Unit,
    onOpenTmuxSession: (ActiveTmuxClients.Entry, sessionName: String, startDirectory: String?) -> Unit,
) {
    if (sessions.isEmpty() && createError == null) return

    item(key = "sessions:header") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .testTag(DASHBOARD_SESSIONS_SECTION_TAG),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Issues #268 / #269: the `+ New session` affordance is a
            // header button (not a FAB) so the screen keeps exactly
            // one bottom-right `+` FAB (add-host). The button is gated
            // on at least one live session existing so it can resolve a
            // host to create the session against — same gate the old
            // section-scoped FAB used.
            NewSessionButton(
                enabled = sessions.isNotEmpty(),
                onClick = {
                    val entry = sessions.firstOrNull()?.let { entryFor(it.hostId) }
                        ?: return@NewSessionButton
                    state.selectedSession = SessionSummary(
                        hostId = entry.hostId,
                        hostName = entry.hostName,
                        sessionName = "",
                        lastActivity = nowSec,
                        attached = false,
                    )
                    state.openDialog(DashboardDialogMode.CreateSession)
                },
            )
            SessionsLegendToggle(
                expanded = state.legendExpanded,
                onClick = { state.legendExpanded = !state.legendExpanded },
            )
        }
    }

    if (state.legendExpanded) {
        item(key = "sessions:legend") {
            Box(modifier = Modifier.padding(horizontal = 12.dp)) {
                SessionsLegend()
            }
        }
    }

    // Issue #204: surface create-session failures (transport errors,
    // tmux %error such as duplicate-name rejection) inline. The banner
    // lives inside the section so it sits next to the affordance that
    // triggered it; HostListScreen owns the kill banner instead.
    createError?.let { msg ->
        item(key = "sessions:create-error") {
            Box(modifier = Modifier.padding(horizontal = 12.dp)) {
                CreateErrorBanner(message = msg, onDismiss = onClearCreateError)
            }
        }
    }

    items(
        items = sessions,
        key = { "sessions:row:" + it.sessionName },
    ) { summary ->
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
        ) {
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
                    // entry lookup — the row stays light, the view model
                    // owns the registry handle. If the host has
                    // unregistered between render and tap we drop the tap
                    // silently; the row will disappear on the next poll.
                    val entry = entryFor(summary.hostId) ?: return@SessionRow
                    onOpenTmuxSession(entry, summary.sessionName, null)
                },
                onLongClick = {
                    state.selectedSession = summary
                },
            )
            DashboardSessionMenu(
                expanded = state.selectedSession == summary && state.dialogMode == null,
                onDismiss = { state.selectedSession = null },
                onAttach = {
                    val entry = entryFor(summary.hostId)
                    if (entry != null) {
                        state.selectedSession = null
                        onOpenTmuxSession(entry, summary.sessionName, null)
                    }
                },
                onRename = {
                    state.selectedSession = summary
                    state.openDialog(DashboardDialogMode.RenameSession, summary.sessionName)
                },
                onKill = {
                    state.selectedSession = summary
                    state.dialogMode = DashboardDialogMode.KillSession
                },
            )
        }
    }
}

/**
 * Lifecycle dialog overlay for the Sessions section (create / rename /
 * kill). Rendered by the host screen outside the `LazyColumn` so it
 * floats above the whole screen. No-op when no dialog is pending.
 */
@Composable
internal fun SessionsDashboardDialog(
    state: SessionsDashboardSectionState,
    entryFor: (hostId: Long) -> ActiveTmuxClients.Entry?,
    onCreateSession: (ActiveTmuxClients.Entry, name: String, startDirectory: String) -> Unit,
    onRenameSession: (ActiveTmuxClients.Entry, oldName: String, newName: String) -> Unit,
    onKillSession: (ActiveTmuxClients.Entry, name: String) -> Unit,
) {
    val currentDialog = state.dialogMode
    val currentSession = state.selectedSession
    if (currentDialog == null || currentSession == null) return

    DashboardLifecycleDialog(
        mode = currentDialog,
        sessionName = currentSession.sessionName,
        // Issue #206: thread the host id so the create-session dialog can
        // render the watched-folders chip row above the start-folder field.
        hostId = currentSession.hostId,
        text = state.dialogText,
        onTextChange = { state.dialogText = it },
        startDirectory = state.dialogStartDirectory,
        onStartDirectoryChange = { state.dialogStartDirectory = it },
        onDismiss = { state.dismissDialog() },
        onConfirm = {
            val entry = entryFor(currentSession.hostId)
            if (entry != null) {
                when (currentDialog) {
                    DashboardDialogMode.CreateSession -> onCreateSession(
                        entry,
                        state.dialogText,
                        state.dialogStartDirectory,
                    )
                    DashboardDialogMode.RenameSession -> onRenameSession(
                        entry,
                        currentSession.sessionName,
                        state.dialogText,
                    )
                    DashboardDialogMode.KillSession -> onKillSession(
                        entry,
                        currentSession.sessionName,
                    )
                }
            }
            state.dismissDialog()
        },
    )
}

/**
 * Header `+ New session` affordance (issues #268 / #269). Replaces the
 * old section-scoped bottom-right FAB so the screen keeps exactly one
 * `+` FAB (the add-host FAB). Carries [DASHBOARD_NEW_SESSION_TAG] so the
 * existing tag-driven E2E tests keep matching. Pill-shaped accent button
 * to read as the section's primary action without competing with the
 * screen FAB's circular glyph.
 */
@Composable
private fun NewSessionButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(
                color = if (enabled) PocketShellColors.Accent else PocketShellColors.SurfaceElev,
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag(DASHBOARD_NEW_SESSION_TAG),
    ) {
        Text(
            text = "+ New session",
            color = if (enabled) PocketShellColors.OnAccent else PocketShellColors.TextMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

internal enum class DashboardDialogMode {
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
                        modifier = Modifier.testTag(DASHBOARD_SESSION_NAME_FIELD_TAG),
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
                        modifier = Modifier.testTag(DASHBOARD_START_FOLDER_FIELD_TAG),
                    )
                }
            } else if (isTextMode) {
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    singleLine = true,
                    label = { Text("Session name") },
                    modifier = Modifier.testTag(DASHBOARD_SESSION_NAME_FIELD_TAG),
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
