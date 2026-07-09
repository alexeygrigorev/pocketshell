package com.pocketshell.app.projects

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketshell.uikit.components.ConfirmDialog
import com.pocketshell.uikit.components.FormDialog
import com.pocketshell.uikit.model.SessionAgentKind
import com.pocketshell.uikit.theme.LocalPocketShellSemantic
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType

/**
 * Compact provider/profile label for the [ProfileChip] (issue #858). The
 * recorded profile is the host's human label (e.g. `"Claude (Z.AI)"`); the chip
 * wants just the distinguishing provider part, so when the label is of the form
 * `Kind (Provider)` it shows `Provider` (e.g. `"Z.AI"`). Any other shape (a bare
 * profile name) is shown verbatim. Returns `null` for a blank label so the
 * caller renders no chip.
 */
internal fun profileChipLabel(profile: String?): String? {
    val raw = profile?.trim().orEmpty()
    if (raw.isEmpty()) return null
    val open = raw.lastIndexOf('(')
    val close = raw.lastIndexOf(')')
    if (open in 0 until close) {
        val inner = raw.substring(open + 1, close).trim()
        if (inner.isNotEmpty()) return inner
    }
    return raw
}

/**
 * Compact host-detail tree gutter. The shared density token keeps the default
 * 16 dp workspace nesting step for generic tree rows, but this screen follows
 * the tighter terminal-style host-detail mockup (#565): project rows advance by
 * only one 8 dp rung and session connectors use a narrow 16 dp cell.
 */
internal val treeProjectIndent = PocketShellSpacing.sm

/**
 * Indent applied to the session-children column under an expanded project so the
 * `├─/└─` spine sits just under the project's compact chevron/dot lead (#503,
 * #565). The connector cell ([treeConnectorCellWidth]) lives inside this column
 * and the spine's vertical x ([treeSpineX]) is the visual left edge of the child
 * sub-tree.
 */
internal val treeChildIndent = PocketShellSpacing.sm

/** Extra offset that makes window rows read as children of a session row. */
internal val treeWindowChildIndent = PocketShellSpacing.lg

/** Width of the per-row connector cell that carries the spine + horizontal stub. */
private val treeConnectorCellWidth = PocketShellSpacing.lg

/** Horizontal position of the vertical spine inside the connector cell. */
private val treeSpineX = PocketShellSpacing.xs

/**
 * One session row hung off the project's tree spine (#503).
 *
 * The connector is split into a leading fixed-width [Canvas] cell plus the row
 * content so the whole child block reads as ONE continuous vertical spine with a
 * clean `├─` per row and a `└─` terminating the last child:
 *
 *  - **Continuous spine:** every non-last row draws the vertical from the very
 *    top edge (`y = 0`) to the very bottom edge (`y = height`) with a butt cap,
 *    and the child [androidx.compose.foundation.layout.Column] carries no
 *    inter-row gap, so adjacent cells abut pixel-to-pixel and the spine never
 *    seams. The last row draws the vertical only down to the stub's `y` (the
 *    `└` corner), so the spine stops exactly at the last child rather than
 *    dangling below it.
 *  - **No stray stripes:** the spine x and stub y are snapped to whole device
 *    pixels so a 1 dp hairline lands on a single column/row of pixels instead
 *    of smearing across two and reading as a broken/double line.
 *  - **Clean stub:** the horizontal stub runs from the spine to the cell's right
 *    edge (the session row's leading edge) at the row's vertical centre, where
 *    the session status dot sits.
 */
@Composable
internal fun TreeChildRow(
    last: Boolean,
    connectorTestTag: String,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
    ) {
        val connectorColor = PocketShellColors.Border
        Canvas(
            modifier = Modifier
                .width(treeConnectorCellWidth)
                .fillMaxHeight()
                .testTag(connectorTestTag),
        ) {
            val stroke = 1.dp.toPx()
            val x = snapToPixel(treeSpineX.toPx())
            val stubY = snapToPixel(size.height * 0.5f) + 0.5f
            val verticalEnd = if (last) stubY else size.height
            drawLine(
                color = connectorColor,
                start = Offset(x, 0f),
                end = Offset(x, verticalEnd),
                strokeWidth = stroke,
                cap = StrokeCap.Butt,
            )
            drawLine(
                color = connectorColor,
                start = Offset(x, stubY),
                end = Offset(size.width, stubY),
                strokeWidth = stroke,
                cap = StrokeCap.Butt,
            )
        }
        content()
    }
}

/** Round a px value to the nearest whole device pixel (for crisp 1 dp hairlines). */
private fun snapToPixel(px: Float): Float = kotlin.math.round(px)

// docs/design-system.md: this compact tree chrome keeps sub-ladder glyph geometry
// that predates the token sweep, but names it so the drift guard can ratchet by file.
private val CompactTreeAddGlyphSize = 18.sp

@Composable
internal fun CompactTreeIconButton(
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null,
    accent: Boolean = false,
    size: Dp = PocketShellDensity.tapTargetMin,
) {
    val background = if (accent) {
        PocketShellColors.AccentSoft
    } else {
        PocketShellColors.SurfaceElev.copy(alpha = 0.72f)
    }
    val foreground = if (accent) PocketShellColors.Accent else PocketShellColors.TextSecondary
    val pillSize = (size.value - 4f).coerceAtLeast(24f).dp
    Box(
        modifier = modifier
            .size(size)
            .semantics { this.contentDescription = contentDescription }
            .clickable(role = Role.Button, onClick = onClick)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(pillSize)
                .background(background, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                color = foreground,
                fontSize = if (label == "+") CompactTreeAddGlyphSize else 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun StatusDot(active: Boolean, modifier: Modifier = Modifier) {
    val semantic = LocalPocketShellSemantic.current
    val color = if (active) semantic.statusActive else semantic.statusAttention
    Box(
        modifier = modifier
            .size(8.dp)
            .background(color = color, shape = CircleShape),
    )
}

// docs/design-system.md: the terminal tile mirrors the host-detail mockup's
// compact glyph shape; keep it named until the broader screen token sweep lands.
private val SessionTileRadius = 4.dp

/**
 * Leading terminal tile glyph on a session row (#522 item 3). Mockup #489 leads
 * every session row with a rounded `>_` terminal tile before the project name.
 */
@Composable
internal fun SessionTileGlyph(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(18.dp)
            .background(
                color = PocketShellColors.SurfaceElev.copy(alpha = 0.72f),
                shape = RoundedCornerShape(SessionTileRadius),
            )
            .border(
                width = 1.dp,
                color = PocketShellColors.BorderSoft,
                shape = RoundedCornerShape(SessionTileRadius),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = ">_",
            color = PocketShellColors.TextSecondary,
            style = PocketShellType.labelMono,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/**
 * A project folder is "active" when it has at least one attached session or a
 * live agent (Claude/Codex/OpenCode/probing/exited shell that just ran one).
 */
internal val FolderRow.isActive: Boolean
    get() = sessions.any { it.attached || it.agentKind.isAgent() }

internal fun projectCountText(folder: FolderRow): String {
    val sessions = folder.sessions.size
    val agents = folderAgentWindowCount(folder)
    val allSessionsAreAgents = folder.sessions.isNotEmpty() &&
        folder.sessions.all { it.agentKind.isAgent() }
    return when {
        agents > 0 && allSessionsAreAgents -> agents.countLabel("agent")
        agents > 0 -> "${sessions.countLabel("session")} · ${agents.countLabel("agent")}"
        else -> sessions.countLabel("session")
    }
}

internal fun folderAgentWindowCount(folder: FolderRow): Int =
    folder.sessions.sumOf { session ->
        if (session.windows.size > 1) {
            session.windows.count { it.agentKind.isAgent() }
        } else if (session.agentKind.isAgent()) {
            1
        } else {
            0
        }
    }

internal fun Int.countLabel(noun: String): String =
    if (this == 1) "$this $noun" else "$this ${noun}s"

internal fun sessionBadgeLabel(session: FolderSessionEntry): String = when (session.agentKind) {
    SessionAgentKind.Claude -> "Claude"
    SessionAgentKind.Codex -> "Codex"
    SessionAgentKind.OpenCode -> "OpenCode"
    SessionAgentKind.Probing -> "Detecting"
    SessionAgentKind.Exited -> "Shell"
    SessionAgentKind.Shell -> "Shell"
    SessionAgentKind.Unknown -> "Unknown"
}

internal fun sessionKindLabel(session: FolderSessionEntry): String = when (session.agentKind) {
    SessionAgentKind.Claude -> "Claude · ${agentStateLabel(session)}"
    SessionAgentKind.Codex -> "Codex · ${agentStateLabel(session)}"
    SessionAgentKind.OpenCode -> "OpenCode · ${agentStateLabel(session)}"
    SessionAgentKind.Probing -> "Detecting"
    SessionAgentKind.Exited -> "Shell"
    SessionAgentKind.Shell -> "Shell"
    SessionAgentKind.Unknown -> "Unknown"
}

// Issue #1237: the agent resting-state word for the `Kind · State` secondary
// label. Uses the real resolved `@ps_agent_state` (idle / waiting / working) and
// falls back to "Idle" only when the state is unknown, preserving the prior
// default for a session with no recorded hook state.
private fun agentStateLabel(session: FolderSessionEntry): String =
    session.agentState.chipLabel ?: "Idle"

internal fun sessionDisplayTitle(session: FolderSessionEntry): String {
    val raw = session.sessionName.trim()
    if (raw.isBlank()) return "Tmux session"
    return raw
}

internal fun sortedSessionWindows(session: FolderSessionEntry): List<FolderSessionWindowEntry> =
    session.windows.sortedWith(
        compareBy<FolderSessionWindowEntry> { it.index ?: Int.MAX_VALUE }
            .thenBy { it.name.orEmpty() },
    )

internal fun sessionWindowEntryTitle(
    sessionName: String,
    window: FolderSessionWindowEntry,
): String {
    val suffix = window.index?.let { "[w$it]" } ?: "[window]"
    val hint = window.command?.trim()?.takeIf { it.isNotEmpty() }
        ?: window.name?.trim()?.takeIf { it.isNotEmpty() }
    val base = "$sessionName $suffix"
    return if (hint == null) base else "$base $hint"
}

// docs/design-system.md: the watched pill is a dense row annotation, so its
// sub-ladder radius and caption size stay named while this screen is migrated.
private val WatchedPinRadius = 6.dp
private val WatchedPinFontSize = 10.sp

@Composable
internal fun WatchedPin() {
    Box(
        modifier = Modifier
            .background(
                color = PocketShellColors.Purple.copy(alpha = 0.12f),
                shape = RoundedCornerShape(WatchedPinRadius),
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = "Watched",
            color = PocketShellColors.Purple,
            fontSize = WatchedPinFontSize,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

internal fun SessionAgentKind.isAgent(): Boolean = when (this) {
    SessionAgentKind.Claude,
    SessionAgentKind.Codex,
    SessionAgentKind.OpenCode,
    SessionAgentKind.Probing,
    SessionAgentKind.Exited,
    -> true
    SessionAgentKind.Shell,
    SessionAgentKind.Unknown,
    -> false
}

@Composable
internal fun RenameSessionDialog(
    sessionName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var newName by remember(sessionName) { mutableStateOf(sessionName) }
    val trimmed = newName.trim()
    val canRename = trimmed.isNotEmpty() && trimmed != sessionName
    FormDialog(
        title = "Rename session",
        confirmLabel = "Rename",
        onConfirm = { onConfirm(trimmed) },
        onDismiss = onDismiss,
        modifier = Modifier.testTag(RENAME_SESSION_DIALOG_TAG),
        confirmEnabled = canRename,
        confirmTestTag = RENAME_SESSION_CONFIRM_TAG,
        dismissTestTag = RENAME_SESSION_CANCEL_TAG,
    ) {
        Text(
            text = "Rename $sessionName on this host.",
            color = PocketShellColors.TextSecondary,
            style = PocketShellType.bodyDense,
        )
        OutlinedTextField(
            value = newName,
            onValueChange = { newName = it },
            singleLine = true,
            label = { Text("Session name") },
            keyboardActions = KeyboardActions(onDone = {
                if (canRename) onConfirm(trimmed)
            }),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(RENAME_SESSION_FIELD_TAG),
        )
    }
}

@Composable
internal fun StopSessionDialog(
    sessionName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    ConfirmDialog(
        title = "Stop this session?",
        message = "This ends the tmux session “$sessionName” on the host.",
        confirmLabel = "Stop",
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        destructive = true,
        modifier = Modifier.testTag(STOP_SESSION_DIALOG_TAG),
        confirmTestTag = STOP_SESSION_CONFIRM_TAG,
        dismissTestTag = STOP_SESSION_CANCEL_TAG,
    )
}
