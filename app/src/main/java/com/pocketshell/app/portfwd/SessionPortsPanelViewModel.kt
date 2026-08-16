package com.pocketshell.app.portfwd

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Issue #2176: read surface for the per-session Ports panel.
 *
 * It owns no SSH and starts no scan. It is a pure projection of two flows that
 * already exist:
 *
 *  - [SessionPortsStore] — which ports THIS session mentioned (written by
 *    [SessionMentionedPortsTracker] off the session's own pane output);
 *  - [ForwardingController.flowOfHostSnapshots] — the single source of truth
 *    for what is currently forwarded, the same one the in-session forwarding
 *    pill reads.
 *
 * Reading the live forwarding state from the controller rather than caching a
 * copy is what makes acceptance criterion 7 hold: start or stop a forward from
 * the host-wide panel, the notification, or the terminal URL tap, and this panel
 * moves with it. A second cached copy is how a panel and an indicator come to
 * disagree.
 */
@HiltViewModel
public class SessionPortsPanelViewModel @Inject constructor(
    private val store: SessionPortsStore,
    private val controller: ForwardingController,
) : ViewModel() {

    /**
     * Rows for the session identified by [hostId] / [sessionName].
     *
     * Attribution is structural: the store is keyed by
     * [SessionPortsKey], so a port session A mentioned simply is not in session
     * B's bucket — there is no filter that could be forgotten.
     */
    public fun stateFor(hostId: Long, sessionName: String): StateFlow<SessionPortsPanelState> {
        val key = SessionPortsKey(hostId = hostId, sessionName = sessionName)
        return combine(store.mentions, controller.flowOfHostSnapshots()) { mentions, snapshots ->
            sessionPortsPanelState(
                mentions = mentions[key].orEmpty(),
                snapshot = snapshots[hostId],
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SessionPortsPanelState(),
        )
    }
}

/**
 * Build the panel state from this session's mentions and the host's live
 * forwarding snapshot. Pure, so every row rule is pinned by a JVM test.
 *
 * **No [InterestingPortFilter] here, deliberately.** That filter exists to
 * denoise a HOST-WIDE scan of everything listening on the box. These rows are
 * ports this session explicitly printed, so attribution has already solved the
 * noise problem — hiding a port the user's own session announced, because it
 * happens to sit outside `3000..10000`, would be the feature failing at its one
 * job.
 */
internal fun sessionPortsPanelState(
    mentions: List<SessionPortMention>,
    snapshot: ForwardingHostSnapshot?,
): SessionPortsPanelState {
    val active = snapshot?.takeIf { it.active }
    val forwarded = active?.forwardedPortMap.orEmpty()
    return SessionPortsPanelState(
        rows = mentions.map { mention ->
            SessionPortRow(
                port = mention.port,
                localPort = forwarded[mention.port],
                process = mention.process,
                matchedText = mention.matchedText,
                firstSeenAtEpochMs = mention.firstSeenAtEpochMs,
            )
        },
        hostTunnelCount = active?.tunnelCount ?: 0,
        hostRestoring = active?.restoring == true,
    )
}

/** Rendered state of the per-session Ports panel. */
public data class SessionPortsPanelState(
    public val rows: List<SessionPortRow> = emptyList(),
    /** Tunnels currently open on this HOST, across every session (#1487). */
    public val hostTunnelCount: Int = 0,
    /** True while the host's forwards are being re-established (#439). */
    public val hostRestoring: Boolean = false,
) {
    /**
     * True when this session has never announced a port. The panel shows an
     * honest explanation in that case, never a blank surface — an empty screen
     * reads as "broken", while "no ports yet" reads as "nothing has started".
     */
    public val isEmpty: Boolean get() = rows.isEmpty()

    /**
     * Label for the footer that opens the host-wide port-forward panel.
     *
     * The addendum's request is "let me look through the forwards manually", and
     * the pill that raises this panel is a host-wide tunnel count — so the
     * footer names that count, making it obvious this is the way to the full
     * list. While the host is RESTORING the count is transient and would be
     * misleading, so it is not shown; the route stays open either way.
     */
    public val hostPortsLabel: String
        get() = when {
            hostRestoring -> "All host ports — reconnecting…"
            hostTunnelCount == 1 -> "All host ports (1 forwarding)"
            hostTunnelCount > 1 -> "All host ports ($hostTunnelCount forwarding)"
            else -> "All host ports"
        }
}

/** One row: a port this session opened, plus its live forwarding state. */
public data class SessionPortRow(
    /** Remote (server-side) port. */
    public val port: Int,
    /** Phone-loopback port when forwarded right now; null when not forwarded. */
    public val localPort: Int?,
    /** Owning process from the `LISTEN` scan, or empty. */
    public val process: String,
    /** The terminal line that announced this port, or empty. */
    public val matchedText: String,
    public val firstSeenAtEpochMs: Long,
) {
    /** True while a tunnel for [port] is up on this host. */
    public val forwarded: Boolean get() = localPort != null

    /** Status text, matching the host-wide table's vocabulary. */
    public val statusLabel: String get() = if (forwarded) "Forwarding" else "Not forwarded"

    /** The `Local` column: the loopback port, or a dash when there is none. */
    public val localLabel: String get() = localPort?.toString() ?: "-"

    /**
     * The URL to open when the row is tapped and the port IS forwarded. Uses
     * `127.0.0.1` rather than the `localhost` literal for the same reason
     * [com.pocketshell.core.terminal.selection.LocalhostUrl.toLocalUrl] does:
     * some Android browsers resolve `localhost` oddly.
     */
    public val localUrl: String? get() = localPort?.let { "http://127.0.0.1:$it" }
}

/**
 * What tapping a row does. Two outcomes only, and the distinction is the whole
 * of acceptance criteria 5 and 6.
 */
public sealed interface SessionPortTapAction {
    /** The port is forwarded: open its loopback URL immediately. */
    public data class OpenLocalUrl(public val url: String) : SessionPortTapAction

    /**
     * The port is NOT forwarded: **ask first**. Opening `http://127.0.0.1:<port>`
     * without a tunnel would just show a connection error, and forwarding
     * silently would open a network path the user never agreed to — so the tap
     * raises the existing #488 confirm, which on accept forwards and then opens,
     * and on decline does nothing at all.
     */
    public data class ConfirmForward(public val remotePort: Int) : SessionPortTapAction
}

/**
 * Decide what a tap on [row] does. Pure, so both branches are pinned by a JVM
 * test rather than only by an emulator journey.
 */
public fun sessionPortTapAction(row: SessionPortRow): SessionPortTapAction {
    val url = row.localUrl
    return if (url != null) {
        SessionPortTapAction.OpenLocalUrl(url)
    } else {
        SessionPortTapAction.ConfirmForward(row.port)
    }
}
