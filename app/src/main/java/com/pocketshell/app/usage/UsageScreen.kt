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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketshell.core.usage.UsageProviderRecord
import com.pocketshell.core.usage.UsageStatus
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
                ProviderDot(kind = dotKind(row.status, row.nearLimit, row.blocked))
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
                    color = if (row.blocked) PocketShellColors.Red else PocketShellColors.TextSecondary,
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
fun UsageSessionBlockedBadge(
    provider: UsageProviderRecord?,
    modifier: Modifier = Modifier,
) {
    if (provider == null || (!provider.isBlocked && !provider.isNearLimit)) return
    val label = if (provider.isBlocked) "Blocked" else "Near limit"
    val color = if (provider.isBlocked) PocketShellColors.Red else PocketShellColors.Amber
    Text(
        text = label.uppercase(),
        color = color,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.5.sp,
        modifier = modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
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

private enum class DotKind {
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
