package com.pocketshell.uikit.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.icons.PocketShellIcons
import com.pocketshell.uikit.theme.LocalPocketShellSemantic
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType

/** Stable tags so a journey can drive the strip without matching on copy. */
const val SESSION_TAB_STRIP_TAG: String = "session-tab-strip"
const val SESSION_TAB_NEW_TAG: String = "session-tab-new"

/**
 * The label on the "+" affordance, wherever it is painted.
 *
 * #2635 D3 hides the strip when there is one session and moves its "+" into
 * the screen header, so the two paint sites must say the same thing — a "New
 * session" that becomes "Add" when the strip disappears is the same
 * three-create-grammars drift the audit found on the adjacent screens.
 */
const val SESSION_TAB_NEW_DESCRIPTION: String = "New session"
const val SESSION_TAB_OVERFLOW_TAG: String = "session-tab-overflow"

/** Per-tab tag, keyed by the caller's stable session identity. */
fun sessionTabTag(id: String): String = "session-tab-$id"

/**
 * One switchable view in [SessionTabStrip].
 *
 * [id] is the caller's own stable identity (app2 passes the aplexer session
 * name, which is what the host CLI resolves against) — deliberately NOT an
 * index, so a listing that reorders between refreshes cannot move the
 * selection onto a different session.
 *
 * [state] is optional: a strip whose source has no per-view state renders
 * plain labels rather than inventing a dot colour.
 */
data class SessionTab(
    val id: String,
    val label: String,
    val state: SessionTabState? = null,
)

/**
 * What a tab's dot says about that view, in the three states the host actually
 * reports.
 *
 * A separate vocabulary from [com.pocketshell.uikit.model.ConnectionStatus] on
 * purpose. That enum is about a LINK (connected/connecting/error), and its
 * `Connecting` variant is the one the design language reserves the pulse for —
 * an unbounded animation is wrong in permanent chrome and is exactly what stops
 * a Compose test rule reaching idle. These roles are about a WORKLOAD, and map
 * onto the existing semantic status roles without animating: active green,
 * attention amber, idle muted.
 */
enum class SessionTabState {
    /** The agent is doing something. Green. */
    Working,

    /** The agent is blocked on the user. Amber — the one worth crossing tabs for. */
    NeedsInput,

    /** Alive, nothing happening. Muted. */
    Idle,
}

/**
 * A horizontal tab strip for the views of one session (issue #2632).
 *
 * The maintainer's ask was PocketShell Desktop's tab UX: "I click and I see
 * tabs and then I can select the tab I need". Before this, switching between
 * two terminals in a workspace cost two taps — a context row that opened a
 * modal sheet, then a row in that sheet. Here the sibling views are ON SCREEN,
 * so a switch is ONE tap, and "what else is running here" is answered without
 * any tap at all.
 *
 * The sheet is not deleted: [onOverflow] still reaches it, because the sheet
 * carries the things a 48dp tab cannot (per-session subtitles, status text,
 * stop). The strip is the fast path, the sheet is the full one.
 *
 * ## Why a `LazyRow` and not a `ScrollableTabRow`
 *
 * Material's `ScrollableTabRow` sizes every tab to the widest one and owns its
 * own indicator/ripple vocabulary — on a phone with four sessions that wastes
 * most of the strip on padding. These tabs are chips on the Quiet 4dp grid
 * instead: content-width, [PocketShellDensity.tapTargetMin] tall, selected one
 * filled with `SurfaceElev` and underlined in `Accent`.
 *
 * Selection scroll uses a non-animated `scrollToItem`: an animated scroll is an
 * open frame-loop that never lets a Compose test rule reach idle.
 */
@Composable
fun SessionTabStrip(
    tabs: List<SessionTab>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    onNewTab: (() -> Unit)? = null,
    onOverflow: (() -> Unit)? = null,
) {
    if (tabs.isEmpty()) return
    val listState = rememberLazyListState()
    val selectedIndex = tabs.indexOfFirst { it.id == selectedId }
    // Bring the selected tab into view ONLY when it is not already fully on
    // screen. An unconditional scroll pins the selection to the left edge and
    // clips whatever sits before it, which is the opposite of what a tab bar
    // is for — you want to see the neighbours you might switch to.
    LaunchedEffect(selectedIndex, tabs.size) {
        if (selectedIndex < 0) return@LaunchedEffect
        val info = listState.layoutInfo
        // Nothing measured yet (first composition): the list starts at index 0
        // and will lay the selection out on its own if it fits. Scrolling here
        // would pin the selected tab to the left edge and clip its neighbours.
        if (info.visibleItemsInfo.isEmpty()) return@LaunchedEffect
        val fullyVisible = info.visibleItemsInfo.any { item ->
            item.index == selectedIndex &&
                item.offset >= info.viewportStartOffset &&
                item.offset + item.size <= info.viewportEndOffset
        }
        if (!fullyVisible) listState.scrollToItem(selectedIndex)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(SESSION_TAB_STRIP_TAG),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LazyRow(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(
                    start = PocketShellSpacing.md,
                    end = PocketShellSpacing.xs,
                ),
                horizontalArrangement = Arrangement.spacedBy(PocketShellSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(items = tabs, key = { it.id }) { tab ->
                    SessionTabChip(
                        tab = tab,
                        selected = tab.id == selectedId,
                        onClick = { onSelect(tab.id) },
                    )
                }
            }
            if (onNewTab != null) {
                IconButton(
                    onClick = onNewTab,
                    modifier = Modifier
                        .size(PocketShellDensity.tapTargetMin)
                        .testTag(SESSION_TAB_NEW_TAG),
                ) {
                    Icon(
                        imageVector = PocketShellIcons.Plus,
                        contentDescription = SESSION_TAB_NEW_DESCRIPTION,
                        tint = PocketShellColors.TextSecondary,
                    )
                }
            }
            if (onOverflow != null) {
                IconButton(
                    onClick = onOverflow,
                    modifier = Modifier
                        .size(PocketShellDensity.tapTargetMin)
                        .testTag(SESSION_TAB_OVERFLOW_TAG),
                ) {
                    Icon(
                        imageVector = PocketShellIcons.More,
                        contentDescription = "All sessions",
                        tint = PocketShellColors.TextSecondary,
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(PocketShellColors.BorderSoft),
        )
    }
}

/**
 * One tab. The selected chip carries BOTH a fill and a 2dp accent underline:
 * fill alone reads as "hover" on a dark surface, and an underline alone is
 * easy to lose next to a terminal's own bottom chrome.
 *
 * A single content-wrapping [Row], and the underline is DRAWN rather than laid
 * out. That is not a style preference: a `fillMaxWidth()` underline box (or a
 * `weight(1f)` content row inside a Column) resolves against the LazyRow's
 * viewport constraints, not against the label, so every chip inflates to the
 * full strip width and height. The first `scripts/render.sh sessionTabStrip`
 * of this component showed exactly that — one tab filling the screen.
 */
@Composable
private fun SessionTabChip(
    tab: SessionTab,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val underline = PocketShellColors.Accent
    Row(
        modifier = Modifier
            // Paint 40dp; `minimumInteractiveComponentSize` keeps the 48dp hit
            // area (#2635 D3). Shrink the ink, never the target.
            .heightIn(min = TAB_PAINT_HEIGHT)
            .minimumInteractiveComponentSize()
            .widthIn(max = TAB_MAX_WIDTH)
            .clip(PocketShellShapes.medium)
            .background(if (selected) PocketShellColors.SurfaceElev else PocketShellColors.Background)
            .drawBehind {
                if (!selected) return@drawBehind
                val thickness = UNDERLINE_THICKNESS.toPx()
                drawRect(
                    color = underline,
                    topLeft = Offset(0f, size.height - thickness),
                    size = Size(size.width, thickness),
                )
            }
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics {
                this.selected = selected
                contentDescription = if (selected) "${tab.label}, current session" else tab.label
            }
            .testTag(sessionTabTag(tab.id))
            .padding(horizontal = PocketShellDensity.chipPadH),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tab.state?.let { state ->
            SessionTabDot(state = state)
            Spacer(modifier = Modifier.width(PocketShellSpacing.sm))
        }
        Text(
            text = tab.label,
            color = if (selected) PocketShellColors.Text else PocketShellColors.TextSecondary,
            style = SESSION_TAB_LABEL_STYLE,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The tab label's rung (#2635 D3): `bodyDense` (13sp), NOT `metadata`.
 *
 * `metadata` was 16sp when #2632 designed this strip and became 11sp the moment
 * #2630's reconciled scale merged — Material's caption floor, on the app's
 * PRIMARY switching control, against the desktop's 13px tabs. That is exactly
 * the trap the audit named: three issues shipped in parallel against a type
 * scale a fourth was changing underneath them.
 *
 * Named rather than inlined so `SessionTabStripTest` can assert on the rung the
 * strip actually paints with. Robolectric's text metrics are degenerate (every
 * glyph measures the same), so a size assertion on the rendered node cannot
 * tell 11sp from 13sp — this symbol is the only honest oracle available on the
 * JVM, and re-pointing the label at another rung has to edit it.
 */
val SESSION_TAB_LABEL_STYLE: TextStyle = PocketShellType.bodyDense

/** Wide enough for a real workspace tag, short enough that four tabs fit. */
private val TAB_MAX_WIDTH = 160.dp

/** The strip's painted height; the touch floor stays 48dp. */
private val TAB_PAINT_HEIGHT = 40.dp
private val UNDERLINE_THICKNESS = 2.dp

/**
 * The 8dp workload dot. Static by construction — see [SessionTabState] for why
 * this does not reuse [StatusDot]'s pulsing `Connecting` variant.
 */
@Composable
private fun SessionTabDot(state: SessionTabState) {
    val semantic = LocalPocketShellSemantic.current
    val color = when (state) {
        SessionTabState.Working -> semantic.statusActive
        SessionTabState.NeedsInput -> semantic.statusAttention
        SessionTabState.Idle -> semantic.statusIdle
    }
    Box(
        modifier = Modifier
            .size(PocketShellSpacing.sm)
            .clip(CircleShape)
            .background(color),
    )
}
