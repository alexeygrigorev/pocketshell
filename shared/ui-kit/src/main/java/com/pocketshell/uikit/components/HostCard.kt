package com.pocketshell.uikit.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.model.HostSetupState
import com.pocketshell.uikit.model.HostStatus
import com.pocketshell.uikit.theme.LocalPocketShellSemantic
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType

/**
 * Row item for the "Hosts" section of the dashboard. Matches the
 * `.host-row` shape in `docs/mockups/dashboard.html` and the host-card
 * re-spec in `docs/design-system.md` §8:
 *
 * ```
 * ┌──────────────────────────────────────────────┐
 * │ Avatar │  hetzner                  [●] [⋮]   │
 * │ (40dp) │  alex@65.108.42.11                   │
 * └──────────────────────────────────────────────┘
 * ```
 *
 * Issue #418 (mockup-conformance declutter): the shipped card had grown
 * to pack avatar + name + an inline setup-state **badge** + an optional
 * usage **badge** + a text **status pill** + the kebab into one row —
 * five trailing/inline elements competing for space, the exact crowding
 * design-system §8 calls out ("Usage badge competes with the status
 * chip"). The maintainer reported they could no longer see the host name
 * clearly. This card now renders the §8 / mockup target:
 *
 * - 40dp circular avatar with the host's initial on a hashed per-host hue.
 * - A flex Column with a bright host **name** (the primary scan target,
 *   no inline badge stealing its width) and a muted monospace
 *   `user@host:port` **subtitle**.
 * - Exactly **one** trailing status indicator: a small colour dot whose
 *   colour folds *both* the bootstrap/setup state and the live
 *   connection/session state (see [resolveHostDotState]). The dot carries
 *   an accessible [contentDescription] so TalkBack and instrumentation
 *   can read the state without a painted text pill competing with the
 *   name. While the underlying state is still being probed the dot slot
 *   renders a quiet spinner instead of a stale colour.
 * - The kebab overflow ([trailingContent]) — the only place secondary
 *   actions (Ports / Share / Re-check setup) and the per-host usage
 *   record live, per §8 ("Usage badge is demoted to a kebab overflow
 *   item, not a row sibling").
 *
 * Setup state is no longer a tappable inline badge. The single tap target
 * is the whole card; a `NeedsSetup` host still routes the tap through the
 * caller's connect path (which surfaces the bootstrap sheet), so
 * [onSetupBadgeClick] is retained for that wiring but no longer paints a
 * separate affordance.
 *
 * The outer card uses the always-dark `Surface` token background, the
 * `BorderSoft` 1dp border, and the `medium`(14) card shape from
 * [PocketShellShapes] — sourced from named tokens rather than freehand
 * shape/dp literals (#461 §3.5). The chrome colours intentionally stay on the
 * raw dark tokens (not the flippable M3 `colorScheme` slots) so the card reads
 * dark alongside every other raw-token screen regardless of the system light/
 * dark setting; the semantic status/accent roles come from
 * [LocalPocketShellSemantic]. Row density follows [PocketShellDensity] (44dp
 * floor, 8dp pad-v / 12dp pad-h). Long-press is forwarded to the caller via
 * [onLongClick]; the card stays presentational and the caller wires the
 * menu against the trailing slot.
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
    // Retained so the host-list call site can route a `needs setup` tap
    // through the bootstrap sheet. The card itself no longer paints a
    // tappable badge — the affordance is the whole card plus the amber
    // status dot — so this is wiring only.
    onSetupBadgeClick: (() -> Unit)? = null,
    connectingLabel: String? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            // Compact row floor (44dp) + a11y touch floor (48dp): the whole card
            // is the single tap target, so its min height is held at the touch
            // floor while the paint follows the compact density tokens below.
            .defaultMinSize(minHeight = PocketShellDensity.tapTargetMin)
            .background(
                color = PocketShellColors.Surface,
                shape = PocketShellShapes.medium,
            )
            .border(
                width = 1.dp,
                color = PocketShellColors.BorderSoft,
                shape = PocketShellShapes.medium,
            )
            .combinedClickable(
                role = Role.Button,
                onClick = onClick,
                onLongClick = if (connectingLabel == null) onLongClick else null,
                enabled = connectingLabel == null,
            )
            .padding(
                horizontal = PocketShellDensity.rowPadH,
                vertical = PocketShellDensity.rowPadV,
            ),
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
                // White-on-hashed-hue is structural to the avatar (not a theme
                // surface), so it stays a literal. The glyph size rides the
                // `titleMedium`(16) rung instead of the retired 15sp literal.
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Spacer(modifier = Modifier.width(PocketShellSpacing.md))

        // Name + subtitle. The Column takes the flex space between the
        // avatar and the single trailing status dot / overflow slot. The
        // host name is the primary scan target and now owns the full
        // line width — nothing competes inline with it.
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                color = PocketShellColors.Text,
                style = PocketShellType.bodyDense,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.size(2.dp))
            Text(
                text = subtitle,
                // Muted mono subtitle: `bodyMono`(13) on the always-dark muted
                // text token (stays dark alongside the rest of the screen).
                color = PocketShellColors.TextMuted,
                style = PocketShellType.bodyMono,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            connectingLabel?.let { label ->
                Spacer(modifier = Modifier.height(PocketShellSpacing.sm))
                HostConnectingRow(label = label)
            }
        }

        // Exactly one trailing status indicator (design-system §8): a
        // single colour dot folding setup + connection/session state.
        // While connecting we lean on the inline connecting row above and
        // drop the dot so the row doesn't show two competing progress
        // affordances.
        if (connectingLabel == null) {
            Spacer(modifier = Modifier.width(PocketShellSpacing.md))
            HostStatusDot(state = resolveHostDotState(status = status, setupState = setupState))
        }

        if (trailingContent != null) {
            Spacer(modifier = Modifier.width(PocketShellSpacing.xs))
            trailingContent()
        }
    }
}

@Composable
private fun HostConnectingRow(label: String) {
    // Connecting accent rides the semantic `statusConnecting` role (amber).
    val connectingColor = LocalPocketShellSemantic.current.statusConnecting
    Row(
        modifier = Modifier.testTag(HOST_CONNECTING_ROW_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier
                .size(14.dp)
                .testTag(HOST_CONNECTING_SPINNER_TAG),
            color = connectingColor,
            strokeWidth = 1.5.dp,
        )
        Spacer(modifier = Modifier.width(PocketShellSpacing.sm))
        Text(
            text = label,
            color = connectingColor,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

const val HOST_CONNECTING_ROW_TAG: String = "host-connecting-row"
const val HOST_CONNECTING_SPINNER_TAG: String = "host-connecting-row:spinner"

/**
 * The folded state the single trailing [HostStatusDot] renders. Issue
 * #418 collapses the old two-pill design (an inline setup badge + a text
 * status chip) into one dot, so this enum encodes the *resolved*
 * precedence between bootstrap-readiness and connection/session state:
 *
 * - [Attention] (amber) — the host needs server-side setup attention:
 *   `tmux` / `pocketshell` missing, a stale CLI, or the jobs daemon
 *   disabled. Installing / fixing the tooling is the only useful action,
 *   so it wins over session-count colour exactly as the old setup badge
 *   took precedence over the status chip.
 * - [Error] (red) — the last SSH attempt failed.
 * - [Active] (green) — the app is attached, or the host has at least one
 *   live tmux session.
 * - [Idle] (muted) — the host is reachable and verified but has no active
 *   sessions.
 * - [Unverified] — no verified state yet (cold launch / probe in flight);
 *   the dot slot renders a quiet spinner so the row never shows a stale
 *   colour.
 */
internal enum class HostDotState { Attention, Error, Active, Idle, Unverified }

/**
 * Folds the bootstrap [setupState] and the live [status] into the single
 * dot state. Pure so it can be unit-tested without a UI; the precedence
 * mirrors the pre-#418 rule where setup-required outranked session-count.
 */
internal fun resolveHostDotState(status: HostStatus, setupState: HostSetupState): HostDotState =
    when (setupState) {
        HostSetupState.NeedsSetup,
        HostSetupState.CliUpdateNeeded,
        HostSetupState.DaemonDisabled,
        -> HostDotState.Attention
        HostSetupState.Ready,
        HostSetupState.OptionalUnavailable,
        HostSetupState.Unknown,
        -> when (status) {
            HostStatus.Unknown -> HostDotState.Unverified
            HostStatus.ConnectionError -> HostDotState.Error
            HostStatus.NeedsSetup -> HostDotState.Attention
            HostStatus.Attached -> HostDotState.Active
            is HostStatus.ActiveSessions -> HostDotState.Active
            HostStatus.NoActiveSessions -> HostDotState.Idle
        }
    }

/**
 * Single trailing status indicator for the host card. Replaces the old
 * `HostStatusChip` (a colour + **text** pill) and the inline
 * `HostSetupBadge` with one colour dot, matching the `.status-dot` in
 * `docs/mockups/dashboard.html` and design-system §6.2 ("No inline
 * labels"). The human-readable state lives in the dot's
 * [contentDescription] so accessibility services and instrumentation can
 * still read it — it is just no longer painted as a row sibling that
 * competes with the host name.
 *
 * The [Unverified] state renders a quiet spinner so a first-load / probe-
 * in-flight surface never carries a stale colour.
 *
 * The slot carries a stable [HOST_STATUS_DOT_TAG] so instrumentation can
 * find it regardless of which state is rendered.
 */
@Composable
private fun HostStatusDot(state: HostDotState) {
    // The dot is decorative + semantic-only (no onClick) — the whole card is the
    // tap target — so the 8dp paint never needs the 48dp touch floor. Colours are
    // sourced from the semantic status roles via [LocalPocketShellSemantic].
    val semantic = LocalPocketShellSemantic.current
    if (state == HostDotState.Unverified) {
        Box(
            modifier = Modifier
                .testTag(HOST_STATUS_DOT_TAG)
                .size(20.dp)
                .semantics { contentDescription = HOST_STATUS_DESCRIPTION_UNVERIFIED },
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(14.dp)
                    .testTag(HOST_STATUS_SPINNER_TAG),
                color = semantic.statusIdle,
                strokeWidth = 1.5.dp,
            )
        }
        return
    }

    val (color, description) = when (state) {
        HostDotState.Attention -> semantic.statusAttention to HOST_STATUS_DESCRIPTION_ATTENTION
        HostDotState.Error -> semantic.statusError to HOST_STATUS_DESCRIPTION_ERROR
        HostDotState.Active -> semantic.statusActive to HOST_STATUS_DESCRIPTION_ACTIVE
        HostDotState.Idle -> semantic.statusIdle to HOST_STATUS_DESCRIPTION_IDLE
        // Handled above; included so the `when` is exhaustive.
        HostDotState.Unverified -> return
    }
    Box(
        modifier = Modifier
            .testTag(HOST_STATUS_DOT_TAG)
            .size(20.dp)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(PocketShellSpacing.sm)
                .background(color = color, shape = CircleShape),
        )
    }
}

/**
 * Stable test tags for the single trailing status dot / its loading
 * spinner. Used by instrumentation and Compose UI tests to find the dot
 * regardless of which [HostDotState] is rendered.
 */
const val HOST_STATUS_DOT_TAG: String = "host-status-dot"
const val HOST_STATUS_SPINNER_TAG: String = "host-status-dot:spinner"

/**
 * Accessible labels carried by the [HostStatusDot] `contentDescription`.
 * These replace the old painted status-pill / setup-badge text — the
 * vocabulary stays reachable for TalkBack and instrumentation without a
 * competing visual chip.
 */
const val HOST_STATUS_DESCRIPTION_ATTENTION: String = "Needs setup"
const val HOST_STATUS_DESCRIPTION_ERROR: String = "Connection error"
const val HOST_STATUS_DESCRIPTION_ACTIVE: String = "Active sessions"
const val HOST_STATUS_DESCRIPTION_IDLE: String = "No active sessions"
const val HOST_STATUS_DESCRIPTION_UNVERIFIED: String = "Checking status"
