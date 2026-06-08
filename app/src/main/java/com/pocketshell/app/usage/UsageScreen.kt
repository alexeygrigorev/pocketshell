package com.pocketshell.app.usage

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketshell.app.pocketshell.PocketshellCommand
import com.pocketshell.core.usage.UsageProviderRecord
import com.pocketshell.core.usage.UsageStatus
import com.pocketshell.core.usage.UsageThresholdState
import com.pocketshell.core.usage.UsageWindow
import com.pocketshell.uikit.components.Kebab
import com.pocketshell.uikit.components.KebabItem
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.Pill
import com.pocketshell.uikit.components.ProgressBar
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.model.PillKind
import com.pocketshell.uikit.model.ProgressKind
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType
import java.time.Instant
import java.time.ZoneId

@Composable
fun UsageScreen(
    state: UsageScreenState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenSettings: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    now: Instant = Instant.now(),
) {
    Column(
        modifier = modifier
            .background(PocketShellColors.Background)
            .verticalScroll(rememberScrollState()),
    ) {
        UsageHeader(
            onBack = onBack,
            onRefresh = onRefresh,
            onOpenSettings = onOpenSettings,
        )

        UsageMeta(state = state)

        state.hosts.forEach { host ->
            host.records.forEach { record ->
                UsageProviderCard(record = record, now = now)
            }
        }

        state.missingToolHosts.forEach { host ->
            UsageEmptyHost(host = host)
        }

        state.failedHosts.forEach { host ->
            UsageFailedHostPanel(host = host)
        }

        Spacer(modifier = Modifier.height(30.dp))
    }
}

@Composable
private fun UsageHeader(
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenSettings: (() -> Unit)?,
) {
    ScreenHeader(
        title = "Usage",
        modifier = Modifier.border(width = 1.dp, color = PocketShellColors.BorderSoft),
        leading = {
            Box(
                modifier = Modifier
                    .size(PocketShellDensity.tapTargetMin)
                    .clickable(role = Role.Button, onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "‹",
                    color = PocketShellColors.TextSecondary,
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
        },
        trailing = {
            Kebab(
                triggerTestTag = USAGE_OVERFLOW_TAG,
                contentDescription = "Usage actions",
                items = buildList {
                    add(
                        KebabItem(
                            label = "Refresh usage",
                            onClick = onRefresh,
                            testTag = USAGE_REFRESH_ACTION_TAG,
                        ),
                    )
                    if (onOpenSettings != null) {
                        add(
                            KebabItem(
                                label = "Usage settings",
                                onClick = onOpenSettings,
                                testTag = USAGE_SETTINGS_ACTION_TAG,
                            ),
                        )
                    }
                },
            )
        },
    )
}

/**
 * Cross-host usage strip on the host list (issue #116) — threshold-aware
 * as of issue #214. Each row's dot AND percent text are tinted by the
 * row's [UsageDashboardRow.thresholdState], so a maintainer scanning
 * the list spots a 96% Claude row as red without expanding the panel.
 *
 * Rows are rendered in the order the caller supplies (the host list
 * view model sorts by provider so the same provider sits on the same
 * spatial slot across host changes).
 */
@Composable
fun UsageDashboardStrip(
    rows: List<UsageDashboardRow>,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    now: Instant = Instant.now(),
) {
    if (rows.isEmpty()) return
    val zone = ZoneId.systemDefault()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PocketShellSpacing.md)
            .background(PocketShellColors.Surface, RoundedCornerShape(8.dp))
            .border(1.dp, PocketShellColors.BorderSoft, RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = PocketShellSpacing.xs, vertical = PocketShellSpacing.xs),
    ) {
        rows.forEach { row ->
            ListRow(
                title = row.provider,
                leading = { ProviderDot(kind = dotKindForThreshold(row.thresholdState)) },
                trailing = {
                    // Issue #501: soonest reset, so a scan of the strip shows
                    // who has runway. Only rendered when the provider reports
                    // a reset time (the placeholder "—" would be noise here).
                    row.soonestReset?.let { reset ->
                        Text(
                            text = formatResetRelative(now, reset, zone),
                            color = PocketShellColors.TextMuted,
                            style = PocketShellType.labelMono,
                            modifier = Modifier.padding(end = PocketShellSpacing.sm),
                        )
                    }
                    Text(
                        text = row.percentLabel,
                        color = thresholdTextColor(row.thresholdState),
                        style = PocketShellType.labelMono,
                    )
                },
            )
        }
    }
}

/**
 * Inline threshold-aware pill rendered by compact usage affordances
 * (issue #116; rewritten in issue #214 to read from
 * [UsageProviderRecord.thresholdState] rather than the older two-state
 * quota check). Returns nothing when the provider's
 * threshold state is [UsageThresholdState.Ok].
 *
 * The pill copy + colour follow the four-state ladder:
 *
 * - [UsageThresholdState.Approaching] → "APPROACHING" / amber
 * - [UsageThresholdState.Critical]    → "CRITICAL" / red
 * - [UsageThresholdState.Exceeded]    → "EXCEEDED" / red
 *
 * The amber-only "Near limit" copy from #116 is replaced because the
 * new threshold ladder distinguishes "approaching" from "critical"
 * (both used to land under one tier).
 */
@Composable
fun UsageSessionBlockedBadge(
    provider: UsageProviderRecord?,
    modifier: Modifier = Modifier,
    warnPercent: Double = UsageProviderRecord.DEFAULT_WARN_PERCENT,
) {
    if (provider == null) return
    val state = provider.thresholdState(warnPercent = warnPercent)
    if (!state.warrantsWarning) return
    val label = thresholdBadgeLabel(state)
    val color = thresholdAccentColor(state)
    Text(
        text = label,
        color = color,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.5.sp,
        modifier = modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/**
 * Settings → Usage state list (issue #214 AC: "Settings → Usage section
 * shows per-provider state"). Renders one row per provider with the
 * provider name on the left, the worst-case percent in monospace on
 * the right, and a threshold-tinted pill underneath. Empty list →
 * nothing is rendered; the calling section composable already shows
 * the "no pocketshell hosts" empty hint in that case.
 */
@Composable
fun UsageProviderStateList(
    records: List<UsageProviderRecord>,
    modifier: Modifier = Modifier,
    warnPercent: Double = UsageProviderRecord.DEFAULT_WARN_PERCENT,
) {
    if (records.isEmpty()) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(USAGE_PROVIDER_STATE_LIST_TAG),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        records.forEach { record ->
            UsageProviderStateRow(record = record, warnPercent = warnPercent)
        }
    }
}

@Composable
private fun UsageProviderStateRow(
    record: UsageProviderRecord,
    warnPercent: Double,
) {
    val state = record.thresholdState(warnPercent = warnPercent)
    val percent = record.mostConstrainedWindow?.percent
    val descriptionColor = if (state.warrantsWarning || record.status == UsageStatus.Error) {
        thresholdAccentColor(
            if (state.warrantsWarning) state else UsageThresholdState.Critical,
        )
    } else {
        PocketShellColors.TextMuted
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        ListRow(
            title = record.displayName,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(usageProviderStateRowTag(record.provider)),
            leading = { ProviderDot(kind = dotKindForThreshold(state)) },
            trailing = {
                if (percent != null) {
                    Text(
                        text = formatPercentUsed(percent),
                        color = thresholdTextColor(state),
                        style = PocketShellType.bodyMono,
                        fontWeight = FontWeight.Medium,
                    )
                }
            },
        )
        Text(
            text = usageProviderStateDescription(record, state),
            color = descriptionColor,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(
                start = PocketShellDensity.rowPadH + PocketShellSpacing.sm + PocketShellSpacing.md,
                end = PocketShellDensity.rowPadH,
            ),
        )
    }
}

/**
 * Dismissible in-app banner for the issue #214 "usage approaching limit"
 * surface. Rendered above the host list / sessions dashboard for every
 * provider whose threshold state warrants a warning AND that the user
 * hasn't yet dismissed for this app session.
 *
 * Dismissals are in-memory only (the issue spec: "Dismissible; survives
 * until app restart OR until percentage drops back below threshold").
 * The state lives in [UsageWarningBannerState] which the host list
 * composable owns.
 */
@Composable
fun UsageWarningBanner(
    provider: UsageProviderRecord,
    onDismiss: () -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    warnPercent: Double = UsageProviderRecord.DEFAULT_WARN_PERCENT,
) {
    val state = provider.thresholdState(warnPercent = warnPercent)
    if (!state.warrantsWarning) return
    val color = thresholdAccentColor(state)
    val percent = provider.mostConstrainedWindow?.percent
    val headline = buildString {
        append(provider.displayName)
        append(" usage")
        if (percent != null) {
            append(": ")
            append(formatPercentUsed(percent))
        }
        append(" — ")
        append(thresholdBannerSuffix(state))
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClick = onTap)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag(usageBannerTagFor(provider.provider)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProviderDot(kind = dotKindForThreshold(state))
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = headline,
            color = PocketShellColors.Text,
            style = PocketShellType.bodyDense,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(
            onClick = onDismiss,
            modifier = Modifier.testTag(usageBannerDismissTagFor(provider.provider)),
        ) {
            Text(
                text = "Dismiss",
                color = PocketShellColors.TextSecondary,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun UsageMeta(state: UsageScreenState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, end = 22.dp, top = 12.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = if (state.isRefreshing) "Syncing..." else "Last sync: host data",
            color = PocketShellColors.TextMuted,
            style = MaterialTheme.typography.labelSmall,
        )
        Text(
            text = "${state.providerCount} providers · ${state.hostCount} hosts",
            color = PocketShellColors.TextMuted,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun UsageProviderCard(record: UsageProviderRecord, now: Instant) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .background(PocketShellColors.Surface, RoundedCornerShape(8.dp))
            .border(1.dp, PocketShellColors.BorderSoft, RoundedCornerShape(8.dp))
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        ListRow(
            title = record.displayName,
            leading = { ProviderDot(kind = dotKind(record)) },
            trailing = { Pill(label = statusLabel(record), kind = pillKind(record)) },
        )

        Spacer(modifier = Modifier.height(16.dp))

        record.windows.forEachIndexed { index, window ->
            UsageWindowRow(window = window, record = record, now = now)
            if (index != record.windows.lastIndex) Spacer(modifier = Modifier.height(14.dp))
        }

        val messages = listOfNotNull(
            record.blockReason.takeIf { record.windows.isEmpty() },
            usageTelemetryMessageForDisplay(record.lastError),
        ).distinct()
        messages.forEachIndexed { index, message ->
            Spacer(modifier = Modifier.height(if (index == 0) 10.dp else 6.dp))
            Text(
                text = message,
                color = PocketShellColors.TextMuted,
                style = PocketShellType.labelMono,
            )
        }
    }
}

@Composable
private fun UsageWindowRow(
    window: UsageWindow,
    record: UsageProviderRecord,
    now: Instant,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = windowLabel(window.name),
                color = PocketShellColors.TextSecondary,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = formatPercentUsed(window.percent),
                color = PocketShellColors.Text,
                style = PocketShellType.labelMono,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        ProgressBar(
            progress = (window.percent / 100.0).toFloat(),
            kind = progressKind(window.percent, record.isBlocked),
        )
        UsageResetFoot(window = window, now = now, blockReason = blockReasonForWindow(record, window))
    }
}

/**
 * Issue #501: per-window "time until reset" foot. Primary line is the
 * relative countdown ("resets in 2h 15m" / "resets in 3 days" / "resets
 * —" when the provider reports none); the absolute local date+time is a
 * dimmer secondary line so providers stay scannable but the exact moment
 * is still available. The block reason, if any, rides on the primary
 * line as it did before.
 */
@Composable
private fun UsageResetFoot(
    window: UsageWindow,
    now: Instant,
    blockReason: String?,
) {
    val zone = ZoneId.systemDefault()
    val primary = formatWindowFoot(window, now, blockReason, zone)
    val absolute = formatResetAbsolute(window.resetAt, zone)
    val unavailable = resetUnavailableText(window)
    if (primary.isBlank() && absolute == null && unavailable == null) return
    Column(modifier = Modifier.padding(top = 6.dp)) {
        if (primary.isNotBlank()) {
            Text(
                text = primary,
                color = PocketShellColors.TextMuted,
                style = PocketShellType.labelMono,
            )
        }
        if (absolute != null) {
            Text(
                text = absolute,
                color = PocketShellColors.TextSecondary,
                style = PocketShellType.labelMono,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
        if (unavailable != null) {
            Text(
                text = unavailable,
                color = PocketShellColors.TextSecondary,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

private fun resetUnavailableText(window: UsageWindow): String? {
    if (window.resetAt != null) return null
    return "Reset time unavailable."
}

@Composable
private fun UsageEmptyHost(host: UsageMissingToolHost) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "${host.hostName}: ${host.toolName} not installed",
            color = PocketShellColors.TextMuted,
            style = PocketShellType.bodyDense,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "server-side usage tracking unavailable",
            color = PocketShellColors.TextSecondary,
            style = PocketShellType.labelMono,
            modifier = Modifier.padding(top = 8.dp),
        )
        // Issue #484: this state now only appears when pocketshell is genuinely
        // absent (the PATH-robust resolver probed ~/.local/bin and the other
        // candidates and found nothing). Point the user at the server fix.
        Text(
            text = PocketshellCommand.NOT_INSTALLED_HINT,
            color = PocketShellColors.TextMuted,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun UsageFailedHostPanel(host: UsageFailedHost) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "${host.hostName}: $REFRESH_USAGE_FAILED",
            color = PocketShellColors.TextMuted,
            style = PocketShellType.bodyDense,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = usageTelemetryMessageForDisplay(host.reason) ?: USAGE_DATA_UNAVAILABLE,
            color = PocketShellColors.TextSecondary,
            style = PocketShellType.labelMono,
            modifier = Modifier.padding(top = 8.dp),
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ProviderDot(kind: DotKind) {
    val color = when (kind) {
        DotKind.Ok -> PocketShellColors.Green
        DotKind.Warn -> PocketShellColors.Amber
        DotKind.Blocked -> PocketShellColors.Red
        DotKind.Neutral -> PocketShellColors.TextMuted
    }
    Box(
        modifier = Modifier
            .size(PocketShellSpacing.sm)
            .background(color = color, shape = RoundedCornerShape(PocketShellSpacing.xs)),
    )
}

internal enum class DotKind {
    Ok,
    Warn,
    Blocked,
    Neutral,
}

private fun dotKind(record: UsageProviderRecord): DotKind = when {
    usageProviderStatusUi(record).needsAuthSetup -> DotKind.Neutral
    record.isBlocked -> DotKind.Blocked
    record.isNearLimit || record.status == UsageStatus.Warn -> DotKind.Warn
    record.status == UsageStatus.Ok -> DotKind.Ok
    else -> DotKind.Neutral
}

internal fun dotKindForThreshold(state: UsageThresholdState): DotKind = when (state) {
    UsageThresholdState.Ok -> DotKind.Ok
    UsageThresholdState.Approaching -> DotKind.Warn
    UsageThresholdState.Critical -> DotKind.Blocked
    UsageThresholdState.Exceeded -> DotKind.Blocked
}

@Composable
internal fun thresholdAccentColor(state: UsageThresholdState): Color = when (state) {
    UsageThresholdState.Ok -> PocketShellColors.Green
    UsageThresholdState.Approaching -> PocketShellColors.Amber
    UsageThresholdState.Critical -> PocketShellColors.Red
    UsageThresholdState.Exceeded -> PocketShellColors.Red
}

@Composable
internal fun thresholdTextColor(state: UsageThresholdState): Color = when (state) {
    UsageThresholdState.Ok -> PocketShellColors.TextSecondary
    UsageThresholdState.Approaching -> PocketShellColors.Amber
    UsageThresholdState.Critical -> PocketShellColors.Red
    UsageThresholdState.Exceeded -> PocketShellColors.Red
}

internal fun thresholdBadgeLabel(state: UsageThresholdState): String = when (state) {
    UsageThresholdState.Ok -> ""
    UsageThresholdState.Approaching -> "APPROACHING"
    UsageThresholdState.Critical -> "CRITICAL"
    UsageThresholdState.Exceeded -> "EXCEEDED"
}

internal fun thresholdRowDescription(state: UsageThresholdState): String = when (state) {
    UsageThresholdState.Ok -> "OK"
    UsageThresholdState.Approaching -> "Approaching limit"
    UsageThresholdState.Critical -> "Critical — close to limit"
    UsageThresholdState.Exceeded -> exceededUsageDescription()
}

internal fun thresholdBannerSuffix(state: UsageThresholdState): String = when (state) {
    UsageThresholdState.Ok -> ""
    UsageThresholdState.Approaching -> "approaching limit"
    UsageThresholdState.Critical -> "critical"
    UsageThresholdState.Exceeded -> "quota exceeded"
}

private fun pillKind(record: UsageProviderRecord): PillKind = when {
    usageProviderStatusUi(record).needsAuthSetup -> PillKind.Error
    record.isBlocked -> PillKind.Blocked
    record.isNearLimit || record.status == UsageStatus.Warn -> PillKind.Warn
    record.status == UsageStatus.Ok -> PillKind.Ok
    else -> PillKind.Error
}

private fun progressKind(percent: Double, blocked: Boolean): ProgressKind = when {
    blocked || percent >= 100.0 -> ProgressKind.Danger
    percent >= UsageProviderRecord.WARN_PERCENT -> ProgressKind.Warn
    else -> ProgressKind.Default
}

private fun windowLabel(name: String): String = when (name.lowercase()) {
    "5h" -> "5h window"
    "7d" -> "7d window"
    "weekly" -> "Weekly limit"
    // #522 item 4: humanize raw snake_case keys (e.g. `short_term` /
    // `long_term`) so the usage panel reads "Short term" / "Long term" rather
    // than carrying the underscore. Splits on `_`, sentence-cases the first
    // word, lowercases the rest so "Short term" reads as prose, not Title Case.
    else -> name
        .split('_')
        .filter { it.isNotBlank() }
        .joinToString(" ") { it.lowercase() }
        .replaceFirstChar { it.uppercase() }
}

/**
 * Stable test tag for the provider state list rendered in
 * Settings → Usage (issue #214 AC).
 */
public const val USAGE_PROVIDER_STATE_LIST_TAG: String =
    "usage:provider-state-list"

/**
 * Per-row tag for the provider state list. Lowercased so a probe by
 * provider id stays stable regardless of casing returned by
 * `pocketshell usage`.
 */
public fun usageProviderStateRowTag(provider: String): String =
    "usage:provider-state-row:" + provider.lowercase()

/**
 * Per-provider tag for the dismissible warning banner. The host list
 * composable surfaces one banner per provider that warrants a warning;
 * the per-provider tag lets tests target the specific row.
 */
public fun usageBannerTagFor(provider: String): String =
    "usage:warning-banner:" + provider.lowercase()

public fun usageBannerDismissTagFor(provider: String): String =
    "usage:warning-banner-dismiss:" + provider.lowercase()

public const val USAGE_OVERFLOW_TAG: String = "usage:overflow"
public const val USAGE_REFRESH_ACTION_TAG: String = "usage:overflow:refresh"
public const val USAGE_SETTINGS_ACTION_TAG: String = "usage:overflow:settings"
