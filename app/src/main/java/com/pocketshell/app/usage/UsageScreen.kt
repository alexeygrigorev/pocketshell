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
import com.pocketshell.core.usage.UsageProviderRecord
import com.pocketshell.core.usage.UsageStatus
import com.pocketshell.core.usage.UsageThresholdState
import com.pocketshell.core.usage.UsageWindow
import com.pocketshell.uikit.components.Breadcrumb
import com.pocketshell.uikit.components.Pill
import com.pocketshell.uikit.components.ProgressBar
import com.pocketshell.uikit.model.Crumb
import com.pocketshell.uikit.model.PillKind
import com.pocketshell.uikit.model.ProgressKind
import com.pocketshell.uikit.theme.JetBrainsMonoFamily
import com.pocketshell.uikit.theme.PocketShellColors
import java.time.Instant

@Composable
fun UsageScreen(
    state: UsageScreenState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    now: Instant = Instant.now(),
) {
    Column(
        modifier = modifier
            .background(PocketShellColors.Background)
            .verticalScroll(rememberScrollState()),
    ) {
        Breadcrumb(
            crumbs = listOf(Crumb(label = "Usage", isCurrent = true, onClick = {})),
            onBack = onBack,
            onMore = onRefresh,
            liveDot = false,
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

        Spacer(modifier = Modifier.height(30.dp))
    }
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
) {
    if (rows.isEmpty()) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .background(PocketShellColors.Surface, RoundedCornerShape(8.dp))
            .border(1.dp, PocketShellColors.BorderSoft, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProviderDot(kind = dotKindForThreshold(row.thresholdState))
                Text(
                    text = row.provider,
                    color = PocketShellColors.Text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Text(
                    text = formatPercent(row.percent),
                    color = thresholdTextColor(row.thresholdState),
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

/**
 * Inline threshold-aware pill rendered on the session screen and inside
 * the host card overflow menu (issue #116; rewritten in issue #214 to
 * read from [UsageProviderRecord.thresholdState] rather than the binary
 * blocked / near-limit pair). Returns nothing when the provider's
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
        fontSize = 10.sp,
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(usageProviderStateRowTag(record.provider)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProviderDot(kind = dotKindForThreshold(state))
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = record.displayName,
                color = PocketShellColors.Text,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (state.warrantsWarning) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = thresholdRowDescription(state),
                    color = thresholdAccentColor(state),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
            } else {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "OK",
                    color = PocketShellColors.TextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        if (percent != null) {
            Text(
                text = formatPercent(percent),
                color = thresholdTextColor(state),
                fontFamily = JetBrainsMonoFamily,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
        }
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
            append(formatPercent(percent))
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
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color = color, shape = RoundedCornerShape(4.dp)),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = headline,
            color = PocketShellColors.Text,
            fontSize = 13.sp,
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
                fontSize = 12.sp,
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
            fontSize = 12.sp,
        )
        Text(
            text = "${state.providerCount} providers · ${state.hostCount} hosts",
            color = PocketShellColors.TextMuted,
            fontSize = 12.sp,
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProviderDot(kind = dotKind(record.status, record.isNearLimit, record.isBlocked))
                Text(
                    text = record.displayName,
                    color = PocketShellColors.Text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp).weight(1f),
                )
            }
            Pill(label = statusLabel(record), kind = pillKind(record))
        }

        Spacer(modifier = Modifier.height(16.dp))

        record.windows.forEachIndexed { index, window ->
            UsageWindowRow(window = window, record = record, now = now)
            if (index != record.windows.lastIndex) Spacer(modifier = Modifier.height(14.dp))
        }

        record.lastError?.let { error ->
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = error,
                color = PocketShellColors.TextMuted,
                fontFamily = JetBrainsMonoFamily,
                fontSize = 11.sp,
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
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = formatPercent(window.percent),
                color = PocketShellColors.Text,
                fontFamily = JetBrainsMonoFamily,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        ProgressBar(
            progress = (window.percent / 100.0).toFloat(),
            kind = progressKind(window.percent, record.isBlocked),
        )
        val foot = formatWindowFoot(window, now, record.blockReason)
        if (foot.isNotBlank()) {
            Text(
                text = foot,
                color = PocketShellColors.TextMuted,
                fontFamily = JetBrainsMonoFamily,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
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
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "server-side usage tracking unavailable",
            color = PocketShellColors.TextSecondary,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 8.dp),
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
            .size(8.dp)
            .background(color = color, shape = RoundedCornerShape(4.dp)),
    )
}

internal enum class DotKind {
    Ok,
    Warn,
    Blocked,
    Neutral,
}

private fun dotKind(status: UsageStatus, nearLimit: Boolean, blocked: Boolean): DotKind = when {
    blocked -> DotKind.Blocked
    nearLimit || status == UsageStatus.Warn -> DotKind.Warn
    status == UsageStatus.Ok -> DotKind.Ok
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
    UsageThresholdState.Exceeded -> "Exceeded — provider blocked"
}

internal fun thresholdBannerSuffix(state: UsageThresholdState): String = when (state) {
    UsageThresholdState.Ok -> ""
    UsageThresholdState.Approaching -> "approaching limit"
    UsageThresholdState.Critical -> "critical"
    UsageThresholdState.Exceeded -> "limit reached"
}

private fun pillKind(record: UsageProviderRecord): PillKind = when {
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
    else -> name.replaceFirstChar { it.uppercase() }
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
