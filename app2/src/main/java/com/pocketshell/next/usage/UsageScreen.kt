package com.pocketshell.next.usage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import com.pocketshell.core.usage.UsageProviderRecord
import com.pocketshell.core.usage.UsageResetCredits
import com.pocketshell.core.usage.UsageThresholdState
import com.pocketshell.core.usage.UsageWindow
import com.pocketshell.uikit.components.Banner
import com.pocketshell.uikit.components.BannerRole
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.EmptyState
import com.pocketshell.uikit.components.Kebab
import com.pocketshell.uikit.components.KebabItem
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.ProgressBar
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.model.ProgressKind
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType
import java.time.Instant
import java.time.ZoneId

/**
 * Route-level entry point for `usage` (rewrite task P-5, journey J12).
 *
 * Fetch-on-view: `ON_START` triggers exactly one refresh pass, which is one
 * `pocketshell usage --json` exec per CONNECTED host. There is no poll loop, no
 * scheduler and no stale-while-revalidate tier — the pre-rewrite client's
 * `UsageScheduler` (564 lines of cadence, active-host tracking and lease
 * fan-out) is deliberately not ported. What is on screen is what the host said
 * when the panel was opened or when the user pulled Refresh.
 */
@Composable
fun UsageRoute(
    onBack: () -> Unit,
    selectedHostId: Long? = null,
    modifier: Modifier = Modifier,
    viewModel: UsageViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.refresh(selectedHostId) }
    UsageScreen(
        state = state,
        onBack = onBack,
        onRefresh = { viewModel.refresh(selectedHostId) },
        modifier = modifier,
    )
}

/**
 * The host-scoped provider quota panel. Each provider is a shared Quiet row;
 * tapping it reveals the real windows, reset timing, credits, and provider
 * messages inline. The screen contains no cross-host dashboard or nested card
 * chrome, so the selected host remains the only data scope the user sees.
 */
@Composable
fun UsageScreen(
    state: UsageScreenState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    now: Instant = Instant.now(),
    initiallyExpandedProviders: Set<String> = emptySet(),
) {
    var expandedProviders by remember { mutableStateOf(initiallyExpandedProviders) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PocketShellColors.Background)
            .testTag(USAGE_SCREEN_TAG),
    ) {
        UsageHeader(
            hostName = state.selectedHostName,
            onBack = onBack,
            onRefresh = onRefresh,
        )

        if (state.isEmptyWithNoConnectedHosts) {
            EmptyState(
                title = "No connected host",
                description = "PocketShell reads quotas from a host you are connected to. " +
                    "Open a host from the list, then come back.",
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag(USAGE_NO_HOSTS_TAG),
            )
        } else if (state.isEmptyWithConnectedHosts) {
            EmptyState(
                title = "No providers reported",
                description = "The host answered `pocketshell usage --json` with no provider " +
                    "records.",
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag(USAGE_NO_PROVIDERS_TAG),
            )
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .testTag(USAGE_PROVIDER_LIST_TAG),
            ) {
                UsageMeta(state = state)

                state.resetBanner?.let { banner -> UsageResetBanner(state = banner) }

                state.hosts.forEach { host ->
                    host.records.forEach { record ->
                        UsageProviderRow(
                            record = record,
                            expanded = record.displayName in expandedProviders,
                            now = now,
                            warnPercent = state.warnPercent,
                            onToggle = {
                                expandedProviders = if (record.displayName in expandedProviders) {
                                    expandedProviders - record.displayName
                                } else {
                                    expandedProviders + record.displayName
                                }
                            },
                        )
                    }
                }

                state.missingToolHosts.forEach { host -> UsageEmptyHost(host = host) }
                state.failedHosts.forEach { host -> UsageFailedHostPanel(host = host) }

                Spacer(modifier = Modifier.height(PocketShellSpacing.lg))
            }
        }
    }
}

@Composable
private fun UsageHeader(
    hostName: String?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
) {
    ScreenHeader(
        title = "Usage",
        subtitle = hostName ?: "Connected hosts",
        onBack = onBack,
        backTestTag = USAGE_BACK_TAG,
        trailing = {
            Kebab(
                triggerTestTag = USAGE_OVERFLOW_TAG,
                contentDescription = "Usage actions",
                items = listOf(
                    KebabItem(
                        label = "Refresh usage",
                        onClick = onRefresh,
                        testTag = USAGE_REFRESH_ACTION_TAG,
                    ),
                ),
            )
        },
    )
}

@Composable
private fun UsageMeta(state: UsageScreenState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = PocketShellDensity.rowPadH + PocketShellSpacing.sm,
                vertical = PocketShellSpacing.md,
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = usageSyncLabel(state),
            color = PocketShellColors.TextMuted,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.testTag(USAGE_SYNC_TAG),
        )
        Text(
            text = if (state.selectedHostId != null) {
                "${state.providerCount} providers"
            } else {
                "${state.providerCount} providers · ${state.hostCount} hosts"
            },
            color = PocketShellColors.TextMuted,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.testTag(USAGE_COUNTS_TAG),
        )
    }
}

/** One real provider record rendered as a Quiet row with optional inline detail. */
@Composable
private fun UsageProviderRow(
    record: UsageProviderRecord,
    expanded: Boolean,
    now: Instant,
    warnPercent: Double,
    onToggle: () -> Unit,
) {
    val constrained = record.mostConstrainedWindow
    val summary = listOfNotNull(
        constrained?.let { "${formatPercentUsed(it.percent)} · ${windowLabel(it.name)}" },
        statusLabel(record, warnPercent).takeIf { constrained == null },
    ).joinToString(" · ")
    val hasDetails = record.windows.isNotEmpty() ||
        record.resetCredits != null ||
        record.blockReason != null ||
        record.lastError != null
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(usageProviderRowTag(record.provider)),
    ) {
        ListRow(
            title = record.displayName,
            subtitle = summary,
            modifier = Modifier.testTag(usageProviderToggleTag(record.provider)),
            trailing = if (hasDetails) {
                {
                    Text(
                        text = if (expanded) "Hide" else "Details",
                        color = PocketShellColors.TextSecondary,
                        style = PocketShellType.metadata,
                    )
                }
            } else {
                null
            },
            onClick = onToggle.takeIf { hasDetails },
        )
        if (!expanded && constrained != null) {
            ProgressBar(
                progress = (constrained.percent / 100.0).toFloat(),
                kind = progressKind(constrained.percent, record.isBlocked, warnPercent),
                modifier = Modifier
                    .padding(horizontal = PocketShellDensity.rowPadH)
                    .padding(bottom = PocketShellSpacing.sm)
                    .testTag("${usageProviderToggleTag(record.provider)}-summary"),
            )
        }
        if (expanded) {
            UsageProviderDetails(record = record, now = now, warnPercent = warnPercent)
        }
    }
}

@Composable
private fun UsageProviderDetails(
    record: UsageProviderRecord,
    now: Instant,
    warnPercent: Double,
) {
    val status = record.thresholdState(warnPercent = warnPercent)
    val messages = listOfNotNull(
        record.blockReason.takeIf { record.windows.isEmpty() },
        usageTelemetryMessageForDisplay(record.lastError),
    ).distinct()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = PocketShellDensity.rowPadH,
                end = PocketShellDensity.rowPadH,
                bottom = PocketShellSpacing.md,
            )
            .testTag(usageProviderDetailsTag(record.provider)),
        verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm),
    ) {
        Text(
            text = "Status · ${statusLabel(record, warnPercent)}",
            color = thresholdTextColor(status),
            style = PocketShellType.metadata,
        )
        record.windows.forEach { window ->
            UsageWindowRow(window = window, record = record, now = now, warnPercent = warnPercent)
        }
        record.resetCredits?.let { resetCredits ->
            UsageResetCreditsSection(resetCredits = resetCredits, now = now)
        }
        messages.forEach { message ->
            Text(
                text = message,
                color = PocketShellColors.TextMuted,
                style = PocketShellType.metadata,
            )
        }
    }
}

/**
 * Codex's additive credit inventory. Credits are already available; their
 * timestamps are expiry information only and deliberately do not reuse quota
 * reset copy, reset actions, or any clickable surface.
 */
@Composable
private fun UsageResetCreditsSection(
    resetCredits: UsageResetCredits,
    now: Instant,
) {
    val zone = ZoneId.systemDefault()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(USAGE_RESET_CREDITS_SECTION_TAG),
        verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm),
    ) {
        if (resetCredits.unavailable) {
            Text(
                text = "Reset credits unavailable",
                color = PocketShellColors.TextMuted,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.testTag(USAGE_RESET_CREDITS_UNAVAILABLE_TAG),
            )
            return@Column
        }

        Text(
            text = "Reset credits · ${resetCredits.availableCount} available",
            color = PocketShellColors.TextSecondary,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.testTag(USAGE_RESET_CREDITS_HEADER_TAG),
        )
        resetCredits.credits.forEachIndexed { index, credit ->
            val expiry = formatCreditExpiry(now = now, expiresAt = credit.expiresAt, zoneId = zone)
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = credit.title,
                    color = PocketShellColors.Text,
                    style = PocketShellType.body,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(usageResetCreditTitleTag(index)),
                )
                Text(
                    text = expiry.primary,
                    color = PocketShellColors.TextMuted,
                    style = PocketShellType.metadata,
                    modifier = Modifier.testTag(usageResetCreditExpiryTag(index)),
                )
                expiry.absolute?.let { absolute ->
                    Text(
                        text = absolute,
                        color = PocketShellColors.TextSecondary,
                        style = PocketShellType.metadata,
                    )
                }
            }
        }
    }
}

@Composable
private fun UsageWindowRow(
    window: UsageWindow,
    record: UsageProviderRecord,
    now: Instant,
    warnPercent: Double,
) {
    Column(modifier = Modifier.testTag(usageWindowRowTag(record.provider, window.name))) {
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
                style = PocketShellType.metadata,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(modifier = Modifier.height(PocketShellSpacing.xs + 2.dp))
        ProgressBar(
            progress = (window.percent / 100.0).toFloat(),
            kind = progressKind(window.percent, record.isBlocked, warnPercent),
        )
        UsageResetFoot(
            window = window,
            now = now,
            blockReason = blockReasonForWindow(record, window),
        )
    }
}

/**
 * Per-window "time until reset" foot: the relative countdown, then the absolute
 * local date+time as a dimmer secondary line so providers stay scannable but
 * the exact moment is still available.
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
    val unavailable = if (window.resetAt == null) "Reset time unavailable." else null
    if (primary.isBlank() && absolute == null && unavailable == null) return
    Column(modifier = Modifier.padding(top = PocketShellSpacing.xs + 2.dp)) {
        if (primary.isNotBlank()) {
            Text(
                text = primary,
                color = PocketShellColors.TextMuted,
                style = PocketShellType.metadata,
            )
        }
        if (absolute != null) {
            Text(
                text = absolute,
                color = PocketShellColors.TextSecondary,
                style = PocketShellType.metadata,
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

@Composable
private fun UsageEmptyHost(host: UsageMissingToolHost) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PocketShellSpacing.lg, vertical = PocketShellSpacing.lg)
            .testTag(usageMissingToolTag(host.hostId)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "${host.hostName}: ${host.toolName} not installed",
            color = PocketShellColors.TextMuted,
            style = PocketShellType.body,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "server-side usage tracking unavailable",
            color = PocketShellColors.TextSecondary,
            style = PocketShellType.metadata,
            modifier = Modifier.padding(top = PocketShellSpacing.sm),
        )
        Text(
            text = POCKETSHELL_NOT_INSTALLED_HINT,
            color = PocketShellColors.TextMuted,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = PocketShellSpacing.sm),
        )
    }
}

@Composable
private fun UsageFailedHostPanel(host: UsageFailedHost) {
    Banner(
        text = "${host.hostName}: $REFRESH_USAGE_FAILED",
        role = BannerRole.Error,
        maxLines = 2,
        modifier = Modifier
            .padding(horizontal = PocketShellSpacing.md, vertical = PocketShellSpacing.sm)
            .testTag(usageFailedHostTag(host.hostId)),
        trailingContent = {
            Text(
                text = usageTelemetryMessageForDisplay(host.reason) ?: USAGE_DATA_UNAVAILABLE,
                color = PocketShellColors.TextSecondary,
                style = PocketShellType.metadata,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag(usageFailedHostReasonTag(host.hostId)),
            )
        },
    )
}

@Composable
internal fun thresholdTextColor(state: UsageThresholdState): Color = when (state) {
    UsageThresholdState.Ok -> PocketShellColors.TextSecondary
    UsageThresholdState.Approaching -> PocketShellColors.Amber
    UsageThresholdState.Critical -> PocketShellColors.Red
    UsageThresholdState.Exceeded -> PocketShellColors.Red
}

internal fun thresholdRowDescription(state: UsageThresholdState): String = when (state) {
    UsageThresholdState.Ok -> "OK"
    UsageThresholdState.Approaching -> "Approaching limit"
    UsageThresholdState.Critical -> "Critical — close to limit"
    UsageThresholdState.Exceeded -> exceededUsageDescription()
}

private fun progressKind(
    percent: Double,
    blocked: Boolean,
    warnPercent: Double = UsageProviderRecord.DEFAULT_WARN_PERCENT,
): ProgressKind = when {
    blocked || percent >= 100.0 -> ProgressKind.Danger
    percent >= warnPercent -> ProgressKind.Warn
    else -> ProgressKind.Default
}

/**
 * Human label for a producer window key. The producer owns the canonical keys
 * (`5h`, `7d`, `weekly`, `monthly`); anything else is humanised rather than
 * printed with its underscores.
 */
internal fun windowLabel(name: String): String = when (name.lowercase()) {
    "5h" -> "5h window"
    "7d" -> "7d window"
    "weekly" -> "Weekly limit"
    "monthly" -> "Monthly limit"
    else -> name
        .split('_')
        .filter { it.isNotBlank() }
        .joinToString(" ") { it.lowercase() }
        .replaceFirstChar { it.uppercase() }
}

// --- test tags ------------------------------------------------------------

const val USAGE_SCREEN_TAG: String = "usage:screen"
const val USAGE_BACK_TAG: String = "usage:back"
const val USAGE_SYNC_TAG: String = "usage:sync"
const val USAGE_COUNTS_TAG: String = "usage:counts"
const val USAGE_NO_HOSTS_TAG: String = "usage:no-connected-hosts"
const val USAGE_NO_PROVIDERS_TAG: String = "usage:no-providers"

const val USAGE_PROVIDER_LIST_TAG: String = "usage:providers"
fun usageProviderRowTag(provider: String): String = "usage:provider-row:" + provider.lowercase()
fun usageProviderToggleTag(provider: String): String = "usage:provider-toggle:" + provider.lowercase()
fun usageProviderDetailsTag(provider: String): String =
    "usage:provider-details:" + provider.lowercase()

const val USAGE_OVERFLOW_TAG: String = "usage:overflow"
const val USAGE_REFRESH_ACTION_TAG: String = "usage:overflow:refresh"

const val USAGE_RESET_CREDITS_SECTION_TAG: String = "usage:reset-credits"
const val USAGE_RESET_CREDITS_HEADER_TAG: String = "usage:reset-credits:header"
const val USAGE_RESET_CREDITS_UNAVAILABLE_TAG: String = "usage:reset-credits:unavailable"

fun usageResetCreditTitleTag(index: Int): String = "usage:reset-credits:$index:title"
fun usageResetCreditExpiryTag(index: Int): String = "usage:reset-credits:$index:expiry"

fun usageWindowRowTag(provider: String, window: String): String =
    "usage:provider:" + provider.lowercase() + ":window:" + window.lowercase()

fun usageMissingToolTag(hostId: Long): String = "usage:missing-tool:$hostId"
fun usageFailedHostTag(hostId: Long): String = "usage:failed-host:$hostId"
fun usageFailedHostReasonTag(hostId: Long): String = "usage:failed-host-reason:$hostId"
