package com.pocketshell.next.usage

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
import androidx.compose.material3.Text
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
 * The glanceable usage pill (rewrite task P-5).
 *
 * "How close am I to the nearest limit" is the routine, NON-warning question —
 * the per-provider warning surfaces only appear once a threshold is crossed, so
 * a healthy 40% Claude would otherwise be invisible without opening a screen.
 * The pill answers it with one number, one provider attribution, and one tap
 * into the full panel.
 *
 * The rewrite MOVES it: the pre-rewrite pill sat on the host-list app bar,
 * which in app2 is a pre-connection screen with no usage data. It now rides the
 * terminal session's top bar, where the plan's lean-menu design puts it, next
 * to the session title.
 *
 * It renders whatever [UsageStore] last read — it starts NO fetch of its own.
 */
data class UsageGlancePillState(
    /** Most-constraining provider percent across every known host, rounded. */
    val percent: Int,
    /**
     * Compact display label of the provider that OWNS this percent ("Claude",
     * "Codex", …). A bare "77%" is ambiguous — "77% of what?" — so the pill
     * names the provider the most-constraining number belongs to.
     */
    val provider: String,
    /**
     * Compact hint for WHICH window drove the percent ("5h", "7d", "weekly"),
     * or null when the winning window has no clean short token.
     */
    val window: String?,
    /** Severity tint for the leading dot, from the winning provider's state. */
    val kind: PillKind,
    /**
     * True when the reading behind this percent is older than
     * [USAGE_GLANCE_STALE_AFTER]. Rendered honestly (muted + "HH:mm") so the
     * number is never silently presented as live.
     */
    val stale: Boolean,
    /** Local "HH:mm" fetch clock, shown only while [stale]. */
    val fetchedClock: String,
) {
    /** Muted attribution before the percent: "Codex 7d" or "Claude". */
    val attribution: String
        get() = if (window != null) "$provider $window" else provider

    /** Full glanceable label: "Codex 7d 72%" / "Claude 60%". */
    val label: String get() = "$attribution $percent%"

    /**
     * Accessibility / test-visible description. A stale pill spells out its
     * provenance so TalkBack users get the same signal the muted clock gives
     * sighted users.
     */
    val contentDescription: String
        get() = if (stale) "Usage $label, read at $fetchedClock" else "Usage $label"
}

/**
 * Compact provider label for the pill — shorter than
 * [com.pocketshell.core.usage.UsageProviderRecord.displayName] ("Claude Code",
 * "GitHub Copilot") so it fits a session top bar without crowding the title.
 */
internal fun glanceProviderLabel(provider: String): String = when (provider.lowercase()) {
    "claude" -> "Claude"
    "codex" -> "Codex"
    "opencode", "open_code", "open-code" -> "OpenCode"
    "copilot", "github_copilot", "github-copilot" -> "Copilot"
    "zai", "z.ai", "z-ai" -> "Z.AI"
    else -> provider
        .split('-', '_', ' ')
        .firstOrNull { it.isNotBlank() }
        ?.replaceFirstChar { it.uppercase() }
        ?: provider
}

/**
 * Compact window hint. Only a CLEAN short token ("5h", "7d", "weekly") is
 * surfaced; internal-looking keys ("short_term") and long names are dropped so
 * the pill stays legible. The provider label alone still answers "which
 * provider" when the window is dropped.
 */
internal fun glanceWindowHint(name: String?): String? {
    val trimmed = name?.trim().orEmpty()
    return trimmed.takeIf { it.isNotEmpty() && '_' !in it && it.length <= 6 }
}

/**
 * How old a reading may be before the pill flips to its honest "stale" look.
 *
 * There is no poll cadence any more — a reading is refreshed when a screen is
 * opened — so this is a plain "you last looked a while ago" threshold rather
 * than a multiple of a scheduler interval.
 */
val USAGE_GLANCE_STALE_AFTER: Duration = Duration.ofMinutes(10)

/** Stable test tag for the session top bar's usage pill. */
const val USAGE_GLANCE_PILL_TAG: String = "session:usage-pill"

/**
 * Derive the pill state from the [snapshots] the store last read.
 *
 * Picks the single MOST-CONSTRAINING window across every host and provider (the
 * highest percent) so the pill answers "how close am I to the nearest limit"
 * with one number. A hard-blocked provider that reports no windows still
 * surfaces as 100%, so a block is never invisible.
 *
 * Returns null — the pill is HIDDEN — when there is no usable reading yet: no
 * [UsageSnapshot.Records] at all, or only records with no thresholdable window.
 */
fun usageGlancePillState(
    snapshots: Map<Long, UsageSnapshot>,
    warnPercent: Double,
    now: Instant = Instant.now(),
    staleAfter: Duration = USAGE_GLANCE_STALE_AFTER,
    zoneId: ZoneId = ZoneId.systemDefault(),
): UsageGlancePillState? {
    val worst = snapshots.values
        .filterIsInstance<UsageSnapshot.Records>()
        .flatMap { snapshot -> snapshot.records.map { snapshot to it } }
        .mapNotNull { (snapshot, record) ->
            val state = record.thresholdState(warnPercent = warnPercent)
            val winningWindow = record.mostConstrainedWindow
            val percent = winningWindow?.percent
                ?: if (state == UsageThresholdState.Exceeded) {
                    100.0
                } else {
                    return@mapNotNull null
                }
            GlanceCandidate(
                percent = percent,
                state = state,
                fetchedAt = snapshot.fetchedAt,
                provider = glanceProviderLabel(record.provider),
                window = glanceWindowHint(winningWindow?.name),
            )
        }
        // Tie-break: the FIRST-encountered candidate keeps the max, which is
        // deterministic over iteration order. The percent is what matters to the
        // user; the attribution just names whichever provider that peak is.
        .maxByOrNull { it.percent }
        ?: return null

    val kind = when (worst.state) {
        UsageThresholdState.Exceeded, UsageThresholdState.Critical -> PillKind.Blocked
        UsageThresholdState.Approaching -> PillKind.Warn
        UsageThresholdState.Ok -> PillKind.Ok
    }
    return UsageGlancePillState(
        percent = worst.percent.roundToInt(),
        provider = worst.provider,
        window = worst.window,
        kind = kind,
        stale = Duration.between(worst.fetchedAt, now) > staleAfter,
        fetchedClock = formatClock(worst.fetchedAt, zoneId),
    )
}

private data class GlanceCandidate(
    val percent: Double,
    val state: UsageThresholdState,
    val fetchedAt: Instant,
    val provider: String,
    val window: String?,
)

/**
 * The pill itself. A 32dp rounded elevated surface with a hairline border — the
 * same small-affordance chrome the rest of the app's top-bar controls use, so
 * it reads as chrome and not as a card.
 */
@Composable
fun UsageGlancePill(
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
        // Small severity dot — the tint reads even before the number does.
        Canvas(modifier = Modifier.size(8.dp)) {
            drawCircle(color = usageGlanceKindColor(state.kind).copy(alpha = contentAlpha))
        }
        Spacer(modifier = Modifier.width(PocketShellSpacing.xs + 2.dp))
        Text(
            text = state.attribution,
            color = PocketShellColors.TextSecondary.copy(alpha = contentAlpha),
            style = PocketShellType.bodyDense,
            maxLines = 1,
        )
        Spacer(modifier = Modifier.width(PocketShellSpacing.xs))
        Text(
            text = "${state.percent}%",
            color = PocketShellColors.Text.copy(alpha = contentAlpha),
            style = PocketShellType.bodyDense,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        if (state.stale) {
            Spacer(modifier = Modifier.width(PocketShellSpacing.xs + 2.dp))
            Text(
                text = state.fetchedClock,
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
