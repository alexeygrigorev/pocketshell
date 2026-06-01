package com.pocketshell.uikit.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketshell.uikit.model.HostSetupState
import com.pocketshell.uikit.model.HostStatus
import com.pocketshell.uikit.theme.JetBrainsMonoFamily
import com.pocketshell.uikit.theme.PocketShellColors

/**
 * Row item for the "Hosts" section of the dashboard. Originally matched
 * `.host-row` in `docs/mockups/styles.css`; updated under issue #113 to:
 *
 * - Pull the deterministic avatar colour from [HostAvatarColor] so
 *   several hosts beginning with the same letter are visually
 *   distinguishable in the list.
 * - Replace the trailing 8dp [StatusDot] with a small colour + text
 *   "status chip" — easier to read at a glance, no learned-glyph
 *   knowledge required.
 * - Drop the fixed `maxLines = 1` on the `user@host:port` subtitle so
 *   the card grows vertically when the system font scale truncates the
 *   line — the disambiguating target string stays visible exactly for
 *   the users who need it most.
 * - Accept an optional [trailingContent] slot (used by the caller for
 *   the kebab overflow button) and an optional [onLongClick] callback
 *   used by the same caller to surface the overflow menu via long-press.
 *
 * Issue #120 added a per-host setup-state badge rendered between the
 * host name and the `user@host:port` subtitle. [setupState] drives the
 * badge label + colour (Ready / Needs setup / Unknown). When the badge
 * is `NeedsSetup`, tapping it invokes [onSetupBadgeClick] so the caller
 * can open the bootstrap sheet — the badge itself is presentational and
 * the action mapping lives at the call site.
 *
 * Layout (top-to-bottom, left-to-right):
 * - 40dp circular avatar with the host's initial letter on a hashed
 *   per-host hue (`HostAvatarColor.colorFor(name)`).
 * - 12dp gap.
 * - Two-line info: bright name (+ setup badge inline) and muted
 *   monospace `user@host:port` that wraps to as many lines as the font
 *   scale needs.
 * - Trailing status chip (colour + label).
 * - Optional trailing slot for caller-supplied affordances (e.g. the
 *   overflow kebab in `HostListScreen`).
 *
 * The outer card keeps `surface` background, `border-soft` 1dp border,
 * 14dp corner radius. Vertical padding stays 14dp; horizontal margin is
 * the caller's choice.
 *
 * The primary tap target is the whole card. Long-press is forwarded to
 * the caller via [onLongClick] — there is no built-in menu, by design
 * the card is presentational and the caller wires the menu against the
 * trailing slot.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HostCard(
    name: String,
    subtitle: String,
    status: HostStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    setupState: HostSetupState = HostSetupState.Unknown,
    onSetupBadgeClick: (() -> Unit)? = null,
    connectingLabel: String? = null,
    // Issue #116 (usage-panel Fix B): optional caller-supplied chip
    // rendered to the right of the setup-state badge from #120. The
    // host list call site uses this slot for the cross-host
    // [com.pocketshell.app.usage.UsageSessionBlockedBadge] chip when
    // the most-recent `pocketshell usage` poll reports the host is blocked or
    // near-limit. The slot is intentionally generic so a future
    // surface (e.g. a "session live" pulse from #22) can reuse it
    // without re-shaping the API.
    usageBadge: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(color = PocketShellColors.Surface, shape = RoundedCornerShape(14.dp))
            .border(
                width = 1.dp,
                color = PocketShellColors.BorderSoft,
                shape = RoundedCornerShape(14.dp),
            )
            .combinedClickable(
                role = Role.Button,
                onClick = onClick,
                onLongClick = if (connectingLabel == null) onLongClick else null,
                enabled = connectingLabel == null,
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Avatar: 40dp circle filled with the deterministic per-host
        // colour. `remember(name)` so we don't re-hash on every
        // recomposition.
        val avatarColor = remember(name) { HostAvatarColor.colorFor(name) }
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(color = avatarColor, shape = CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                // Empty-name safety: an empty avatar is fine; we don't
                // want to crash on a host with a blank label.
                text = name.take(1).uppercase(),
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.5).sp,
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Name + subtitle. Column takes the flex space between the
        // avatar and the trailing status chip / overflow slot.
        //
        // Issue #113: the subtitle used to be hard-capped at one line
        // with `TextOverflow.Ellipsis`. At the largest system font
        // scale this truncated the disambiguating target string (e.g.
        // "test@127.0.0..."). We drop both caps so the card grows
        // vertically and the full `user@host:port` is visible — the
        // outer LazyColumn handles the extra height.
        Column(modifier = Modifier.weight(1f)) {
            // Issue #120: render the host name + setup-state badge inline
            // so the badge sits to the right of the name, consistent
            // with the AC. The name takes the flex space via `weight(1f)`
            // (which fills) so a long name truncates gracefully, leaving
            // the badge at its intrinsic width on the trailing edge.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = name,
                    color = PocketShellColors.Text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(modifier = Modifier.width(8.dp))
                HostSetupBadge(
                    state = setupState,
                    onClick = onSetupBadgeClick,
                )
                // Issue #116: the usage chip coexists with the
                // setup-state badge from #120; both can be present at
                // once (e.g. a Ready host whose Claude window is at
                // 92% renders "ready" + "Near limit"). A small gap
                // separates the two so they don't read as one chip.
                if (usageBadge != null) {
                    Spacer(modifier = Modifier.width(6.dp))
                    usageBadge()
                }
            }
            Spacer(modifier = Modifier.size(2.dp))
            Text(
                text = subtitle,
                color = PocketShellColors.TextMuted,
                fontFamily = JetBrainsMonoFamily,
                fontSize = 12.sp,
            )
            connectingLabel?.let { label ->
                Spacer(modifier = Modifier.height(8.dp))
                HostConnectingRow(label = label)
            }
        }

        // Issue #201: the trailing chip is the host's at-a-glance
        // status indicator (session count, attachment, connection
        // error, or a quiet spinner while the state is unverified).
        // It is suppressed entirely when the inline `HostSetupBadge`
        // already calls out NeedsSetup — the AC requires
        // setup-required to take precedence over session-count
        // display, and showing both pills would re-create the
        // ambiguous "needs setup AND idle" combination the issue
        // exists to fix.
        if (setupState != HostSetupState.NeedsSetup && setupState != HostSetupState.CliUpdateNeeded) {
            Spacer(modifier = Modifier.width(12.dp))
            HostStatusChip(status = status)
        }

        if (trailingContent != null) {
            Spacer(modifier = Modifier.width(8.dp))
            trailingContent()
        }
    }
}

@Composable
private fun HostConnectingRow(label: String) {
    Row(
        modifier = Modifier.testTag(HOST_CONNECTING_ROW_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier
                .size(14.dp)
                .testTag(HOST_CONNECTING_SPINNER_TAG),
            color = PocketShellColors.Amber,
            strokeWidth = 1.5.dp,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            color = PocketShellColors.Amber,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

const val HOST_CONNECTING_ROW_TAG: String = "host-connecting-row"
const val HOST_CONNECTING_SPINNER_TAG: String = "host-connecting-row:spinner"

/**
 * Small colour + text chip rendering the host's at-a-glance status.
 *
 * Issue #201 replaces the previous flat-enum mapping (which surfaced the
 * ambiguous label "idle" for any not-connected host). Each label now
 * maps to exactly one trigger condition; see the KDoc on [HostStatus]
 * for the contract.
 *
 * Mapping:
 *
 * - [HostStatus.Unknown]         -> quiet spinner (no text)
 * - [HostStatus.NoActiveSessions] -> muted grey, "No active sessions"
 * - [HostStatus.ActiveSessions]  -> accent cyan, "N session(s)"
 * - [HostStatus.Attached]        -> green, "Attached"
 * - [HostStatus.NeedsSetup]      -> not rendered here; the inline
 *   `HostSetupBadge` already calls it out, and the caller hides the
 *   trailing chip in that case.
 * - [HostStatus.ConnectionError] -> red, "Connection error"
 *
 * Visually, the chip uses an accent-soft fill at low alpha derived from
 * the same colour so it reads on the dark surface without painting a
 * solid block. Border, text colour, and an inner dot use the
 * full-strength colour for legibility. The [Unknown] case substitutes a
 * 14dp [CircularProgressIndicator] so first-load / probe-in-flight
 * surfaces never carry a stale label — the AC explicitly bans showing
 * an indicator when the underlying state has not been verified yet.
 *
 * The chip carries a stable [HOST_STATUS_CHIP_TAG] test tag so
 * instrumentation can find it regardless of which state is rendered.
 * Spinner-state ([Unknown]) carries the additional
 * [HOST_STATUS_SPINNER_TAG] so a test can distinguish "still loading"
 * from "verified-but-empty" without depending on label wording.
 */
@Composable
private fun HostStatusChip(status: HostStatus) {
    // Unknown: render a quiet spinner so the row doesn't carry a stale
    // label while the underlying probe is still resolving. Sized so the
    // visual footprint matches the chip we'd render in any other state.
    if (status is HostStatus.Unknown) {
        Box(
            modifier = Modifier
                .testTag(HOST_STATUS_CHIP_TAG)
                .size(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(14.dp)
                    .testTag(HOST_STATUS_SPINNER_TAG),
                color = PocketShellColors.TextMuted,
                strokeWidth = 1.5.dp,
            )
        }
        return
    }
    // NeedsSetup is suppressed by the caller (see the precedence comment
    // in HostCard above) — guarding here too so a misuse from a future
    // call site doesn't paint a duplicate "needs setup" pill next to
    // the inline `HostSetupBadge`.
    if (status is HostStatus.NeedsSetup) return

    val (color, label) = when (status) {
        HostStatus.NoActiveSessions -> PocketShellColors.TextMuted to "No active sessions"
        is HostStatus.ActiveSessions -> PocketShellColors.Accent to
            if (status.count == 1) "1 session" else "${status.count} sessions"
        HostStatus.Attached -> PocketShellColors.Green to "Attached"
        HostStatus.ConnectionError -> PocketShellColors.Red to "Connection error"
        // Handled above; included so the `when` is exhaustive against the
        // sealed hierarchy without resorting to an `else` branch.
        HostStatus.Unknown, HostStatus.NeedsSetup -> return
    }
    Row(
        modifier = Modifier
            .testTag(HOST_STATUS_CHIP_TAG)
            .background(
                color = color.copy(alpha = 0.16f),
                shape = RoundedCornerShape(999.dp),
            )
            .border(
                width = 1.dp,
                color = color.copy(alpha = 0.5f),
                shape = RoundedCornerShape(999.dp),
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(color = color, shape = CircleShape),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Stable test tags for the trailing status chip / spinner. Used by
 * instrumentation and Compose UI tests to find the chip regardless of
 * which [HostStatus] variant is being rendered (and therefore which
 * label).
 */
const val HOST_STATUS_CHIP_TAG: String = "host-status-chip"
const val HOST_STATUS_SPINNER_TAG: String = "host-status-chip:spinner"

/**
 * Small colour + text chip rendering the per-host bootstrap state.
 * Mirrors the visual language of [HostStatusChip] (pill outline, inner
 * dot, accent-soft fill) so the two badges read as siblings — one
 * reports connection state, this one reports server-side setup state.
 *
 * Mapping (issue #120):
 *
 * - [HostSetupState.Ready]      -> green, "ready"
 * - [HostSetupState.NeedsSetup] -> amber, "needs setup"
 * - [HostSetupState.CliUpdateNeeded] -> amber, "CLI update needed"
 * - [HostSetupState.OptionalUnavailable] -> muted grey, "optional unavailable"
 * - [HostSetupState.DaemonDisabled] -> amber, "daemon disabled"
 * - [HostSetupState.Unknown]    -> muted grey, "unknown"
 *
 * When [onClick] is non-null the entire pill is tappable. Callers wire a
 * click handler only for the `NeedsSetup` state — the AC explicitly says
 * tapping a `needs setup` badge opens the bootstrap sheet. For other
 * states the badge stays informational.
 *
 * The pill carries a stable [HOST_SETUP_BADGE_TAG] test tag so
 * instrumentation tests can find it regardless of language / wording.
 */
@Composable
private fun HostSetupBadge(state: HostSetupState, onClick: (() -> Unit)?) {
    val (color, label) = when (state) {
        HostSetupState.Ready -> PocketShellColors.Green to "ready"
        HostSetupState.NeedsSetup -> PocketShellColors.Amber to "needs setup"
        HostSetupState.CliUpdateNeeded -> PocketShellColors.Amber to "CLI update needed"
        HostSetupState.OptionalUnavailable -> PocketShellColors.TextMuted to "optional unavailable"
        HostSetupState.DaemonDisabled -> PocketShellColors.Amber to "daemon disabled"
        HostSetupState.Unknown -> PocketShellColors.TextMuted to "unknown"
    }
    val baseModifier = Modifier
        .testTag(HOST_SETUP_BADGE_TAG)
        .background(
            color = color.copy(alpha = 0.16f),
            shape = RoundedCornerShape(999.dp),
        )
        .border(
            width = 1.dp,
            color = color.copy(alpha = 0.5f),
            shape = RoundedCornerShape(999.dp),
        )
    val pillModifier = if (onClick != null) {
        baseModifier.clickable(role = Role.Button, onClick = onClick)
    } else {
        baseModifier
    }
    Row(
        modifier = pillModifier.padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(color = color, shape = CircleShape),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Test tag carried by the [HostSetupBadge] pill — used by instrumentation. */
const val HOST_SETUP_BADGE_TAG: String = "host-setup-badge"
