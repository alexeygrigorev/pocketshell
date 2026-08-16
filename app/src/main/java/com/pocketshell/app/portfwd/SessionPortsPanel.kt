package com.pocketshell.app.portfwd

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.components.SectionHeader
import com.pocketshell.uikit.theme.LocalPocketShellSemantic
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType

/** Test tag for the per-session Ports overlay surface (#2176). */
public const val SESSION_PORTS_OVERLAY_TAG: String = "session_ports_overlay"

/** Test tag for the honest "nothing yet" state (#2176). */
public const val SESSION_PORTS_EMPTY_TAG: String = "session_ports_empty"

/** Test tag for the Ports panel's close affordance (#2176). */
public const val SESSION_PORTS_CLOSE_TAG: String = "session_ports_close"

/** Stable per-row test tag, keyed by the remote port (#2176). */
public fun sessionPortRowTag(port: Int): String = "session_ports_row_$port"

/** Stable per-row action-button test tag, keyed by the remote port (#2176). */
public fun sessionPortActionTag(port: Int): String = "session_ports_action_$port"

/** Test tag for the footer that opens the host-wide port-forward panel (#2176). */
public const val SESSION_PORTS_ALL_HOST_PORTS_TAG: String = "session_ports_all_host_ports"

/**
 * Issue #2176: the columns of the per-session Ports table.
 *
 * Deliberately the same shape and the same weights family as the host-wide
 * table's first four columns ([PortForwardPanelScreen]) so the two read as one
 * design. There is no Traffic column: these rows describe ports a session
 * announced, most of which are not forwarded at all, so a traffic figure would
 * be "-" on nearly every row — the same decluttering call issue #456 already
 * made for discovered rows in the host-wide table.
 */
private val SESSION_PORT_COLUMNS: List<PortColumn> = listOf(
    PortColumn("Remote", 0.18f),
    PortColumn("Local", 0.16f),
    PortColumn("Process", 0.28f),
    PortColumn("Status", 0.24f),
)

/**
 * The per-session Ports panel, shown over the live session screen.
 *
 * ## Why an in-session overlay rather than its own route
 *
 * The rows' two actions both need things the session screen already holds: the
 * live forwarding snapshot, and the #488 "this port is not forwarded — forward
 * it?" confirm plus its navigation into the prefilled port-forward panel. As an
 * overlay this panel reuses all of that, and — more importantly — it opens no
 * connection of its own (the "ONE TRANSPORT" rule, D21/D28). A separate
 * destination would have had to re-dial the host just to render a list the app
 * already knows.
 *
 * It also settles the addendum's "back must return to the session I came from,
 * still live and correctly bound" (#686) by construction: dismissing an overlay
 * performs no navigation and no session rebind, so there is no wrong-session
 * return to get wrong.
 *
 * ## One entry point, not two
 *
 * The forwarding pill in the session chrome and the kebab's "Session ports" both
 * open THIS panel. The maintainer asked for the pill to let him "look through
 * the forwards manually", and this issue asks for a curated per-session list;
 * shipping those as two separate chrome buttons leading to two different lists
 * is exactly what the issue rules out. So the curated list is the primary
 * content — it is the one that answers "which port is mine?" — and
 * [onOpenHostPorts] is a clearly-labelled footer onto the full host-wide
 * [PortForwardPanelScreen], carrying the same tunnel count the pill shows so the
 * route to the manual hunt is obvious rather than hidden.
 */
@Composable
public fun SessionPortsOverlay(
    visible: Boolean,
    hostName: String,
    sessionName: String,
    state: SessionPortsPanelState,
    onDismiss: () -> Unit,
    /** Row is forwarded: open its `http://127.0.0.1:<localPort>` URL now. */
    onOpenForwarded: (SessionPortRow) -> Unit,
    /** Row is NOT forwarded: ask before forwarding, then open (#488 flow). */
    onRequestForward: (SessionPortRow) -> Unit,
    /** Open the host-wide port-forward panel to browse every forward manually. */
    onOpenHostPorts: () -> Unit,
) {
    if (!visible) return
    BackHandler(onBack = onDismiss)
    // Rendered with a plain conditional rather than `AnimatedVisibility`: a modal
    // surface gains nothing from a 120ms fade, and an enter/exit transition adds
    // a frame-timing dimension to every on-device proof of this panel for no
    // user-visible benefit.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag(SESSION_PORTS_OVERLAY_TAG),
    ) {
        // The tap-to-dismiss scrim is its OWN sibling child, deliberately not
        // a `clickable` wrapped AROUND the panel. `clickable` sets
        // `mergeDescendants`, so a clickable container swallows every
        // descendant's test tag out of the merged semantics tree — the panel's
        // rows, its empty state and its footer all became unaddressable, and
        // the on-device proofs failed on exactly that. Keeping the scrim a
        // sibling also removes the need for a no-op `clickable {}` on the panel
        // body to swallow taps: the panel is simply drawn on top of the scrim,
        // so a tap that lands on it never reaches the scrim.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(PocketShellColors.Background.copy(alpha = 0.92f))
                .clickable(onClick = onDismiss),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // `MainActivity` runs edge-to-edge, so this overlay's
                // `fillMaxSize` Box extends BEHIND the system navigation bar and
                // a bottom-anchored child lands underneath it: the round-1 build
                // drew the "All host ports" footer under the Back triangle, where
                // taps go to the system bar and never reach the row. Every other
                // bottom-anchored surface in this app already applies these — most
                // pointedly `DetectedPortOverlay`, which sits in this very overlay
                // region — and `SessionTypePickerSheet` carries a comment recording
                // the same 126px drop. `imePadding()` is here for the same reason
                // the siblings carry it: the session screen underneath can have the
                // soft keyboard up when the panel is raised.
                .navigationBarsPadding()
                .imePadding()
                .background(PocketShellColors.Surface)
                .border(1.dp, PocketShellColors.BorderSoft),
        ) {
            ScreenHeader(
                title = "Ports",
                subtitle = sessionSubtitle(hostName, sessionName),
                modifier = Modifier
                    .background(PocketShellColors.Background)
                    .border(1.dp, PocketShellColors.BorderSoft),
                leading = {
                    CloseButtonBox(onClick = onDismiss)
                },
            )
            SectionHeader(label = "Opened by this session", count = state.rows.size)
            if (state.isEmpty) {
                EmptySessionPortsState()
            } else {
                PortTableHeader(SESSION_PORT_COLUMNS)
                LazyColumn(
                    modifier = Modifier.heightIn(max = 380.dp),
                    contentPadding = PaddingValues(bottom = PocketShellSpacing.xs),
                ) {
                    items(state.rows, key = { it.port }) { row ->
                        SessionPortRowItem(
                            row = row,
                            onClick = {
                                // The open-vs-ask decision lives in one pure
                                // function so both branches are pinned by a
                                // JVM test, not only by the emulator journey.
                                when (sessionPortTapAction(row)) {
                                    is SessionPortTapAction.OpenLocalUrl -> onOpenForwarded(row)
                                    is SessionPortTapAction.ConfirmForward -> onRequestForward(row)
                                }
                            },
                        )
                    }
                }
            }
            AllHostPortsRow(label = state.hostPortsLabel, onClick = onOpenHostPorts)
        }
    }
}

/**
 * The route to the host-wide panel. Rendered on BOTH the populated and the empty
 * state: a session with no ports of its own is exactly when the user is most
 * likely to want the full list, and the addendum's pill tap can land here.
 */
@Composable
private fun AllHostPortsRow(label: String, onClick: () -> Unit) {
    // ListRow's own `onClick` bakes in the 48dp a11y touch floor by contract, so
    // the route cannot ship with a sub-target hit area.
    ListRow(
        title = label,
        modifier = Modifier.testTag(SESSION_PORTS_ALL_HOST_PORTS_TAG),
        onClick = onClick,
        trailing = {
            Text(
                text = "›",
                color = PocketShellColors.TextSecondary,
                style = PocketShellType.bodyDense,
                fontWeight = FontWeight.SemiBold,
            )
        },
    )
}

internal fun sessionSubtitle(hostName: String, sessionName: String): String = when {
    hostName.isBlank() -> sessionName
    sessionName.isBlank() -> hostName
    else -> "$hostName · $sessionName"
}

@Composable
private fun SessionPortRowItem(row: SessionPortRow, onClick: () -> Unit) {
    val semantic = LocalPocketShellSemantic.current
    val statusColor: Color = if (row.forwarded) {
        semantic.statusActive
    } else {
        PocketShellColors.TextSecondary
    }
    Column(modifier = Modifier.testTag(sessionPortRowTag(row.port))) {
        PortTableRow(onClick = onClick) {
            PortBodyCell("${row.port}", 0.18f, monospace = true)
            PortBodyCell(row.localLabel, 0.16f, monospace = true)
            PortBodyCell(row.process.ifBlank { "-" }, 0.28f)
            PortBodyCell(row.statusLabel, 0.24f, color = statusColor)
            Spacer(Modifier.width(PocketShellSpacing.sm))
            // Canonical design-system button (#756). "Open" for a live tunnel;
            // "Forward" makes it explicit that tapping a not-yet-forwarded row
            // will ask to bring the tunnel up first, rather than silently
            // opening a dead localhost URL.
            PocketShellButton(
                text = if (row.forwarded) "Open" else "Forward",
                onClick = onClick,
                variant = if (row.forwarded) ButtonVariant.Primary else ButtonVariant.Text,
                modifier = Modifier.testTag(sessionPortActionTag(row.port)),
            )
        }
        if (row.matchedText.isNotBlank()) {
            // The line the user actually watched scroll past. A bare port number
            // is not recognisable months later; "Local: http://localhost:5173/"
            // is.
            Text(
                text = row.matchedText,
                modifier = Modifier.padding(
                    start = PocketShellDensity.rowPadH,
                    end = PocketShellDensity.rowPadH,
                    bottom = PocketShellDensity.rowPadV,
                ),
                color = PocketShellColors.TextMuted,
                style = PocketShellType.labelMono,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EmptySessionPortsState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(PocketShellSpacing.lg)
            .testTag(SESSION_PORTS_EMPTY_TAG),
    ) {
        Text(
            text = "No ports from this session yet.",
            color = PocketShellColors.Text,
            style = PocketShellType.bodyDense,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.padding(top = PocketShellSpacing.xs))
        Text(
            text = "Start a server here — a port shows up once this session " +
                "prints its address and it is confirmed listening.",
            color = PocketShellColors.TextSecondary,
            style = PocketShellType.bodyDense,
        )
    }
}

@Composable
private fun CloseButtonBox(onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .defaultMinSize(
                    minWidth = PocketShellDensity.tapTargetMin,
                    minHeight = PocketShellDensity.tapTargetMin,
                )
                .background(PocketShellColors.SurfaceElev, PocketShellShapes.small)
                .border(1.dp, PocketShellColors.BorderSoft, PocketShellShapes.small)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(
                    horizontal = PocketShellDensity.chipPadH,
                    vertical = PocketShellDensity.chipPadV,
                )
                .testTag(SESSION_PORTS_CLOSE_TAG),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "<",
                color = PocketShellColors.TextSecondary,
                style = PocketShellType.bodyDense,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
