package com.pocketshell.app.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.pocketshell.uikit.components.SessionRow
import kotlin.math.max

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
    onOpenTmuxSession: (ActiveTmuxClients.Entry, sessionName: String) -> Unit = { _, _ -> },
) {
    val sessions by viewModel.sessions.collectAsState()
    if (sessions.isEmpty()) return

    val nowSec = System.currentTimeMillis() / 1000L

    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        sessions.forEach { summary ->
            SessionRow(
                badge = summary.sessionName,
                name = summary.sessionName,
                host = summary.hostName,
                // No preview text in v1 — the tmux protocol does not
                // surface "last line written to the session" cheaply.
                // The mockup's preview lines are aspirational and will
                // arrive with the agent-aware conversation view in
                // Phase 3 (#23 / #14).
                preview = "",
                time = formatRelativeTime(nowSec = nowSec, thenSec = summary.lastActivity),
                tags = emptyList(),
                onClick = {
                    // Resolve the navigation tuple via the view model's
                    // entry lookup — the row stays light, the view
                    // model owns the registry handle. If the host has
                    // unregistered between render and tap we drop the
                    // tap silently; the row will disappear on the next
                    // poll cycle.
                    val entry = viewModel.entryFor(summary.hostId) ?: return@SessionRow
                    onOpenTmuxSession(entry, summary.sessionName)
                },
            )
        }
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
