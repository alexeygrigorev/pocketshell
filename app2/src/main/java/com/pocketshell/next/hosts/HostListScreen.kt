package com.pocketshell.next.hosts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.hilt.navigation.compose.hiltViewModel
import com.pocketshell.uikit.components.EmptyState
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.NavigationChevron
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.components.SectionHeader
import com.pocketshell.uikit.theme.PocketShellSpacing

/**
 * Stable test tags. The list container plus one tag per row, keyed by host id,
 * so a journey can tap a specific host without matching on user-visible copy.
 */
const val HOST_LIST_TAG: String = "host-list"

fun hostRowTag(hostId: Long): String = "host-row-$hostId"

/**
 * Route-level entry point: binds the Hilt-provided [HostListViewModel] to the
 * stateless [HostListScreen].
 *
 * The split exists so the screen can be rendered from a test (or a design
 * render) with a hand-built state and no DI graph, which is what keeps the
 * screen itself free of `remember`-ed side state.
 */
@Composable
fun HostListRoute(
    onOpenHost: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HostListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    HostListScreen(state = state, onOpenHost = onOpenHost, modifier = modifier)
}

/**
 * The saved-host list — app2's landing screen (plan §U-1).
 *
 * Read-only by design: it paints the `hosts` table and routes a tap to
 * `Tree(hostId)`. It deliberately has no status dots, no bootstrap probe, no
 * update banner and no add/edit/delete affordance; those return in later plan
 * tasks on top of the connections registry, and their absence here is why this
 * screen is a projection with nothing to keep in sync.
 *
 * Composed entirely from ui-kit primitives ([ScreenHeader], [SectionHeader],
 * [ListRow], [EmptyState]) rather than local layout: [ListRow] already encodes
 * the compact-row density and the 48dp tap-target floor, so a screen cannot
 * regress either by accident. [ListRow] is used in preference to the ui-kit
 * `HostCard` precisely because the card always paints a connection status dot,
 * which this screen has no truthful value for — a permanently-spinning
 * "checking status" dot would be a lie, not a placeholder.
 */
@Composable
fun HostListScreen(
    state: HostListUiState,
    onOpenHost: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(title = "Hosts")

        when {
            // Nothing painted until Room's first emission: showing the empty
            // state during the query would flash "No hosts yet" at every cold
            // launch of an install that has hosts.
            !state.loaded -> Unit

            state.hosts.isEmpty() -> EmptyState(
                title = "No hosts yet",
                description = "Hosts you add appear here.",
            )

            else -> {
                SectionHeader(label = "Saved", count = state.hosts.size)
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(HOST_LIST_TAG),
                    contentPadding = PaddingValues(bottom = PocketShellSpacing.lg),
                ) {
                    items(items = state.hosts, key = { it.id }) { host ->
                        ListRow(
                            title = host.name,
                            subtitle = host.subtitle,
                            trailing = { NavigationChevron() },
                            onClick = { onOpenHost(host.id) },
                            modifier = Modifier.testTag(hostRowTag(host.id)),
                        )
                    }
                }
            }
        }
    }
}
