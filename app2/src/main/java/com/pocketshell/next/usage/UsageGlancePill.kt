package com.pocketshell.next.usage

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pocketshell.core.usage.UsageProviderRecord
import com.pocketshell.core.usage.UsageThresholdState
import com.pocketshell.uikit.model.PillKind
import com.pocketshell.uikit.theme.PocketShellColors
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
 * Which provider THIS screen is about (issue #2579).
 *
 * The session screen knows something the tree does not: the agent aplexer
 * detected running inside the session the user is looking at. "How close is
 * the agent I am talking to right now" is a different question from "how close
 * is the nearest limit anywhere", and on the session screen it is the one the
 * user is asking — the maintainer's report was a Claude session whose pill
 * read "Grok 7d 83%".
 *
 * [provider] is the RAW host vocabulary ("claude"), matched case-insensitively
 * against [UsageProviderRecord.provider]. Deliberately not a display label: a
 * future edit to [glanceProviderLabel] must not be able to break the match.
 */
data class GlanceFocus(
    val hostId: Long,
    val provider: String,
)

/** 24, 168 and 720 hours — the spans behind `Nd`, `weekly` and `monthly`. */
private const val HOURS_PER_DAY = 24
private const val HOURS_PER_WEEK = 168
private const val HOURS_PER_MONTH = 720

private val HOURS_WINDOW = Regex("^(\\d+)h$")
private val DAYS_WINDOW = Regex("^(\\d+)d$")

/**
 * Span of a provider window name in hours, for "show the LONGEST window".
 *
 * The names are the PRODUCER's own labels (`5h`, `7d`, `weekly`, `monthly`),
 * so this reads that vocabulary rather than inventing one. An unrecognised
 * name scores 0: it still competes — a provider whose only window is named
 * something new must not vanish from the pill — but never outranks a window
 * whose span is actually known.
 */
internal fun windowSpanHours(name: String?): Int {
    val trimmed = name?.trim()?.lowercase().orEmpty()
    HOURS_WINDOW.matchEntire(trimmed)?.let { return it.groupValues[1].toIntOrNull() ?: 0 }
    DAYS_WINDOW.matchEntire(trimmed)?.let {
        return (it.groupValues[1].toIntOrNull() ?: 0) * HOURS_PER_DAY
    }
    return when (trimmed) {
        "weekly" -> HOURS_PER_WEEK
        "monthly" -> HOURS_PER_MONTH
        else -> 0
    }
}

/**
 * Derive the pill state from the [snapshots] the store last read.
 *
 * Without a [focus] this picks the single MOST-CONSTRAINING window across
 * every host and provider (the highest percent) so the pill answers "how close
 * am I to the nearest limit" with one number. A hard-blocked provider that
 * reports no windows still surfaces as 100%, so a block is never invisible.
 *
 * With a usable [focus] — a host present in [snapshots] that has a record for
 * that provider — the question changes to "how close is THIS session's agent"
 * (issue #2579) and the pill shows that provider's LONGEST window. The window
 * token is dropped in that mode on purpose: the maintainer asked for
 * "Claude 38%", not "Claude 7d 38%", because once the provider is the
 * session's own agent the window name is noise, not information.
 *
 * A focus that resolves to nothing (a host that is not in [snapshots], a
 * provider with no record there) falls back to the cross-provider behaviour
 * byte-for-byte — a session whose agent has no quota data must not lose its
 * pill.
 *
 * Returns null — the pill is HIDDEN — when there is no usable reading yet: no
 * [UsageSnapshot.Records] at all, or only records with no thresholdable window.
 */
fun usageGlancePillState(
    snapshots: Map<Long, UsageSnapshot>,
    warnPercent: Double,
    focus: GlanceFocus? = null,
    now: Instant = Instant.now(),
    staleAfter: Duration = USAGE_GLANCE_STALE_AFTER,
    zoneId: ZoneId = ZoneId.systemDefault(),
): UsageGlancePillState? {
    focusedCandidate(snapshots, focus, warnPercent)
        ?.let { return it.toPillState(now = now, staleAfter = staleAfter, zoneId = zoneId) }

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

    return worst.toPillState(now = now, staleAfter = staleAfter, zoneId = zoneId)
}

/**
 * The [focus]ed provider's longest window on the focused host, or null when
 * the focus cannot be honoured and the caller must fall back.
 *
 * "Longest" is by [windowSpanHours], not by percent: the maintainer wants the
 * limit that actually governs a long working session (Claude's 7d), not
 * whichever bucket happens to be most drained right now.
 *
 * A blocked record with NO windows still answers, at 100% — the same "a block
 * is never invisible" rule the cross-provider path has. A record that exists
 * but has neither a window nor a block is NOT an answer: it carries no number
 * to show, so the caller falls back rather than painting a fake 0%.
 */
private fun focusedCandidate(
    snapshots: Map<Long, UsageSnapshot>,
    focus: GlanceFocus?,
    warnPercent: Double,
): GlanceCandidate? {
    if (focus == null) return null
    val snapshot = snapshots[focus.hostId] as? UsageSnapshot.Records ?: return null
    val wanted = focus.provider.trim()
    if (wanted.isEmpty()) return null
    val record = snapshot.records.firstOrNull { it.provider.equals(wanted, ignoreCase = true) }
        ?: return null

    val recordState = record.thresholdState(warnPercent = warnPercent)
    // Ties keep the FIRST window, which is the producer's own order — stable
    // across fetches, so the pill cannot flip between two equal spans.
    val longest = record.windows.maxByOrNull { windowSpanHours(it.name) }
    val percent = longest?.percent
        ?: if (recordState == UsageThresholdState.Exceeded) EXCEEDED_PERCENT else return null

    return GlanceCandidate(
        percent = percent,
        // A BLOCKED record stays blocked whatever the displayed window reads:
        // a Claude that is hard-blocked on its 5h bucket cannot run, and a
        // green dot over "Claude 38%" would say the opposite. Otherwise the
        // dot describes the number next to it — an amber dot over a 38% that
        // is nowhere near its own limit is just as misleading in reverse.
        state = if (recordState == UsageThresholdState.Exceeded) {
            UsageThresholdState.Exceeded
        } else {
            thresholdOf(percent, warnPercent)
        },
        fetchedAt = snapshot.fetchedAt,
        provider = glanceProviderLabel(record.provider),
        // No window token in focused mode (issue #2579): "Claude 38%".
        window = null,
    )
}

/**
 * [UsageProviderRecord.thresholdState]'s bands applied to ONE percent.
 *
 * The record's own method always thresholds its most-constrained window, which
 * is the wrong window here — the focused pill displays the LONGEST one.
 */
private fun thresholdOf(percent: Double, warnPercent: Double): UsageThresholdState = when {
    percent >= UsageProviderRecord.EXCEEDED_PERCENT -> UsageThresholdState.Exceeded
    percent >= UsageProviderRecord.CRITICAL_PERCENT -> UsageThresholdState.Critical
    percent >= warnPercent -> UsageThresholdState.Approaching
    else -> UsageThresholdState.Ok
}

private const val EXCEEDED_PERCENT = 100.0

private data class GlanceCandidate(
    val percent: Double,
    val state: UsageThresholdState,
    val fetchedAt: Instant,
    val provider: String,
    val window: String?,
) {
    /** Renders the candidate; [state] was already resolved by its producer. */
    fun toPillState(now: Instant, staleAfter: Duration, zoneId: ZoneId): UsageGlancePillState =
        UsageGlancePillState(
            percent = percent.roundToInt(),
            provider = provider,
            window = window,
            kind = when (state) {
                UsageThresholdState.Exceeded, UsageThresholdState.Critical -> PillKind.Blocked
                UsageThresholdState.Approaching -> PillKind.Warn
                UsageThresholdState.Ok -> PillKind.Ok
            },
            stale = Duration.between(fetchedAt, now) > staleAfter,
            fetchedClock = formatClock(fetchedAt, zoneId),
        )
}

/**
 * The usage glance is a neutral text affordance. Quiet keeps usage readable
 * without a colored chip, severity dot, elevated surface, or provider-specific
 * badge in the terminal header.
 */
@Composable
fun UsageGlancePill(
    state: UsageGlancePillState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = state.contentDescription }
            .testTag(USAGE_GLANCE_PILL_TAG)
            .padding(horizontal = PocketShellSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = state.attribution,
            color = PocketShellColors.TextSecondary,
            style = PocketShellType.metadata,
            maxLines = 1,
        )
        Spacer(modifier = Modifier.width(PocketShellSpacing.xs))
        Text(
            text = "${state.percent}%",
            color = PocketShellColors.Text,
            style = PocketShellType.metadata,
            maxLines = 1,
        )
        if (state.stale) {
            Spacer(modifier = Modifier.width(PocketShellSpacing.xs))
            Text(
                text = state.fetchedClock,
                color = PocketShellColors.TextMuted,
                style = PocketShellType.metadata,
            )
        }
    }
}
