package com.pocketshell.app.usage

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketshell.core.usage.UsageThresholdState
import com.pocketshell.uikit.model.PillKind
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Issue #1241: the glanceable usage pill on the landing app bar.
 *
 * The routine, NON-warning question — "how much have I burned this week" —
 * was reachable only via Settings → Diagnostics or the in-session kebab (2–3
 * taps). The per-provider warning banners (#214) only surface when a provider
 * crosses the warn threshold, so a healthy 40% Claude never showed anywhere on
 * the landing surface. This small most-constraining-percent pill sits next to
 * the port-forward indicator + Settings gear and taps straight into
 * [UsageScreen], so the number is always one glance / one tap away.
 *
 * It reads the SAME cached [UsageScheduler.snapshots] the host-list warning
 * banners + per-card badges already consult — NO new fetch cadence, no extra
 * polling (D21-compliant). When the scheduler has no usable reading yet the
 * pill is hidden entirely (see [usageGlancePillState] returning null).
 */
public data class UsageGlancePillState(
    /** Most-constraining provider percent across every cached host, rounded. */
    val percent: Int,
    /**
     * Severity tint for the leading dot, derived from the winning provider's
     * threshold state so a blocked provider reads red at a glance without the
     * user opening the panel.
     */
    val kind: PillKind,
    /**
     * True when the freshest cached reading behind this percent is older than
     * [USAGE_GLANCE_STALE_AFTER]. Rendered honestly (muted + "cached from
     * HH:mm") so the number is never silently presented as live.
     */
    val stale: Boolean,
    /** Local "HH:mm" capture clock, shown only while [stale]. */
    val capturedClock: String,
) {
    val label: String get() = "$percent%"

    /**
     * Accessibility / test-visible description. Mirrors the visual: a fresh
     * pill is just "Usage N%"; a stale pill spells out the honest "cached from
     * HH:mm" provenance so TalkBack users get the same signal the muted clock
     * gives sighted users.
     */
    val contentDescription: String
        get() = if (stale) {
            "Usage $percent%, cached from $capturedClock"
        } else {
            "Usage $percent%"
        }
}

/**
 * How old the freshest cached reading may be before the glance pill flips to
 * its honest "stale / cached from HH:mm" treatment. The scheduler polls active
 * hosts on a 60s / 5m cadence (#117), so 10 minutes is comfortably past a
 * healthy refresh yet short enough that a genuinely stalled reading reads as
 * cached rather than live.
 */
public val USAGE_GLANCE_STALE_AFTER: Duration = Duration.ofMinutes(10)

/**
 * Stable test tag for the app-bar usage pill (#1241).
 */
public const val USAGE_GLANCE_PILL_TAG: String = "hosts:appbar:usage-pill"

/**
 * Derive the glance-pill state from the cached scheduler [snapshots].
 *
 * Picks the single MOST-CONSTRAINING window across every host + provider (the
 * highest percent) so the pill answers "how close am I to the nearest limit"
 * with one number. A hard-blocked provider that reports no windows still
 * surfaces as 100% (mirroring [dashboardRows]) so a block is never invisible.
 *
 * Returns null — i.e. the pill is HIDDEN — when there is no usable reading yet:
 * no [UsageSnapshot.Records], or only records without a thresholdable window.
 * This is the "hidden/neutral when no data" contract (AC1).
 */
internal fun usageGlancePillState(
    snapshots: Map<Long, UsageSnapshot>,
    warnPercent: Double,
    now: Instant = Instant.now(),
    staleAfter: Duration = USAGE_GLANCE_STALE_AFTER,
    zoneId: ZoneId = ZoneId.systemDefault(),
): UsageGlancePillState? {
    val worst = snapshots.values
        .filterIsInstance<UsageSnapshot.Records>()
        .flatMap { snap -> snap.records.map { snap to it } }
        .mapNotNull { (snap, record) ->
            val state = record.thresholdState(warnPercent = warnPercent)
            val percent = record.mostConstrainedWindow?.percent
                ?: if (state == UsageThresholdState.Exceeded) 100.0 else return@mapNotNull null
            GlanceCandidate(percent = percent, state = state, fetchedAt = snap.fetchedAt)
        }
        .maxByOrNull { it.percent }
        ?: return null

    val kind = when (worst.state) {
        UsageThresholdState.Exceeded, UsageThresholdState.Critical -> PillKind.Blocked
        UsageThresholdState.Approaching -> PillKind.Warn
        UsageThresholdState.Ok -> PillKind.Ok
    }
    val stale = Duration.between(worst.fetchedAt, now) > staleAfter
    return UsageGlancePillState(
        percent = worst.percent.roundToInt(),
        kind = kind,
        stale = stale,
        capturedClock = formatCapturedClock(worst.fetchedAt, zoneId),
    )
}

private data class GlanceCandidate(
    val percent: Double,
    val state: UsageThresholdState,
    val fetchedAt: Instant,
)

/**
 * The app-bar usage pill. Styled to match the sibling [ForwardingIndicatorPill]
 * chrome (32dp rounded [PocketShellColors.SurfaceElev] surface + 1dp
 * [PocketShellColors.BorderSoft] hairline) so it reads as one of the small
 * app-bar affordances, NOT a heavy card — the #418 declutter constraint. The
 * enclosing [ScreenHeader] trailing row spaces it from the Settings gear, so it
 * never crowds.
 */
@Composable
internal fun UsageGlancePill(
    state: UsageGlancePillState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentAlpha = if (state.stale) STALE_CONTENT_ALPHA else 1f
    Row(
        modifier = modifier
            .height(32.dp)
            .background(color = PocketShellColors.SurfaceElev, shape = PocketShellShapes.large)
            .border(
                width = 1.dp,
                color = PocketShellColors.BorderSoft,
                shape = PocketShellShapes.large,
            )
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = state.contentDescription }
            .testTag(USAGE_GLANCE_PILL_TAG)
            .padding(horizontal = PocketShellSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Small severity dot — the kind tint reads even before the number does.
        Canvas(modifier = Modifier.size(8.dp)) {
            drawCircle(color = usageGlanceKindColor(state.kind).copy(alpha = contentAlpha))
        }
        Spacer(modifier = Modifier.width(6.dp))
        androidx.compose.material3.Text(
            text = state.label,
            color = PocketShellColors.Text.copy(alpha = contentAlpha),
            style = PocketShellType.bodyDense,
            fontWeight = FontWeight.SemiBold,
        )
        if (state.stale) {
            // Honest provenance for a cached reading, consistent with the Usage
            // screen's "showing cached from HH:mm" pattern.
            Spacer(modifier = Modifier.width(6.dp))
            androidx.compose.material3.Text(
                text = state.capturedClock,
                color = PocketShellColors.TextMuted,
                style = PocketShellType.bodyDense,
            )
        }
    }
}

private const val STALE_CONTENT_ALPHA = 0.6f

internal fun usageGlanceKindColor(kind: PillKind): Color = when (kind) {
    PillKind.Ok -> PocketShellColors.Green
    PillKind.Warn -> PocketShellColors.Amber
    PillKind.Blocked -> PocketShellColors.Red
    PillKind.Error -> PocketShellColors.TextMuted
}
