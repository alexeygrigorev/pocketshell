package com.pocketshell.next.usage

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.pocketshell.core.usage.UsageStatus
import com.pocketshell.core.usage.UsageThresholdState
import com.pocketshell.core.usage.UsageWindow
import com.pocketshell.uikit.components.Banner
import com.pocketshell.uikit.components.BannerRole
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.EmptyState
import com.pocketshell.uikit.components.Kebab
import com.pocketshell.uikit.components.KebabItem
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.Pill
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.ProgressBar
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.model.PillKind
import com.pocketshell.uikit.model.ProgressKind
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellShapes
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
    modifier: Modifier = Modifier,
    viewModel: UsageViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.refresh() }
    UsageScreen(
        state = state,
        onBack = onBack,
        onRefresh = viewModel::refresh,
        modifier = modifier,
    )
}

/**
 * The provider quota panel: compact strip first, full card on tap.
 *
 * Default paint is the cross-host summary (one line per provider) plus last-sync,
 * counts, and the reset banner. Tapping a compact row mounts that provider's
 * existing [UsageProviderCard]; tapping again collapses it. Other providers stay
 * collapsed unless tapped. [initiallyExpandedProviders] is the render/test seam
 * for a first-paint expanded card; production always starts collapsed.
 *
 * Ported from the pre-rewrite `usage/UsageScreen.kt`, minus the deleted
 * server-side capture cache and the surfaces that belonged to screens app2
 * does not have yet.
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
        UsageHeader(onBack = onBack, onRefresh = onRefresh)

        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            UsageMeta(state = state)

            state.resetBanner?.let { banner -> UsageResetBanner(state = banner) }

            // The cross-host summary is the primary list: one line per provider,
            // worst window, soonest reset. Full cards stay unmounted until the
            // matching compact row is tapped (#2534).
            UsageDashboardStrip(
                rows = state.dashboardRows(),
                now = now,
                onRowClick = { provider ->
                    expandedProviders = if (provider in expandedProviders) {
                        expandedProviders - provider
                    } else {
                        expandedProviders + provider
                    }
                },
            )

            state.hosts.forEach { host ->
                host.records.forEach { record ->
                    if (record.displayName in expandedProviders) {
                        UsageProviderCard(record = record, now = now)
                    }
                }
            }

            state.missingToolHosts.forEach { host -> UsageEmptyHost(host = host) }
            state.failedHosts.forEach { host -> UsageFailedHostPanel(host = host) }

            Spacer(modifier = Modifier.height(PocketShellSpacing.lg))
        }

        if (state.isEmptyWithNoConnectedHosts) {
            EmptyState(
                title = "No connected host",
                description = "PocketShell reads quotas from a host you are connected to. " +
                    "Open a host from the list, then come back.",
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(USAGE_NO_HOSTS_TAG),
            )
        } else if (state.isEmptyWithConnectedHosts) {
            EmptyState(
                title = "No providers reported",
                description = "The host answered `pocketshell usage --json` with no provider " +
                    "records.",
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(USAGE_NO_PROVIDERS_TAG),
            )
        }
    }
}

@Composable
private fun UsageHeader(
    onBack: () -> Unit,
    onRefresh: () -> Unit,
) {
    ScreenHeader(
        title = "Usage",
        modifier = Modifier.border(width = 1.dp, color = PocketShellColors.BorderSoft),
        leading = {
            PocketShellButton(
                text = "‹",
                onClick = onBack,
                variant = ButtonVariant.Text,
                compact = true,
                modifier = Modifier.testTag(USAGE_BACK_TAG),
            )
        },
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
            text = "${state.providerCount} providers · ${state.hostCount} hosts",
            color = PocketShellColors.TextMuted,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.testTag(USAGE_COUNTS_TAG),
        )
    }
}

/**
 * The cross-host summary strip: one row per provider, tinted by its threshold
 * state, with the soonest reset on the right. Each row is tappable and toggles
 * that provider's full card below the strip.
 *
 * Ported from the pre-rewrite host-list strip. It lives INSIDE the panel here
 * rather than on the host list, because app2's host list is a pre-connection
 * screen and this data only exists for hosts that are already connected.
 */
@Composable
internal fun UsageDashboardStrip(
    rows: List<UsageDashboardRow>,
    onRowClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    now: Instant = Instant.now(),
) {
    if (rows.isEmpty()) return
    val zone = ZoneId.systemDefault()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PocketShellSpacing.md)
            .background(PocketShellColors.Surface, PocketShellShapes.extraSmall)
            .border(1.dp, PocketShellColors.BorderSoft, PocketShellShapes.extraSmall)
            .padding(horizontal = PocketShellSpacing.xs, vertical = PocketShellSpacing.xs)
            .testTag(USAGE_SUMMARY_STRIP_TAG),
    ) {
        rows.forEach { row ->
            ListRow(
                title = row.provider,
                modifier = Modifier.testTag(usageSummaryRowTag(row.provider)),
                leading = { ProviderDot(kind = dotKindForThreshold(row.thresholdState)) },
                trailing = {
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
                onClick = { onRowClick(row.provider) },
            )
        }
    }
}

@Composable
private fun UsageProviderCard(
    record: UsageProviderRecord,
    now: Instant,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PocketShellSpacing.md, vertical = PocketShellSpacing.sm)
            .background(PocketShellColors.Surface, PocketShellShapes.extraSmall)
            .border(1.dp, PocketShellColors.BorderSoft, PocketShellShapes.extraSmall)
            .padding(horizontal = PocketShellSpacing.lg, vertical = PocketShellSpacing.lg)
            .testTag(usageProviderCardTag(record.provider)),
    ) {
        ListRow(
            title = record.displayName,
            leading = { ProviderDot(kind = dotKind(record)) },
            trailing = { Pill(label = statusLabel(record), kind = pillKind(record)) },
        )

        Spacer(modifier = Modifier.height(PocketShellSpacing.md))

        record.windows.forEachIndexed { index, window ->
            UsageWindowRow(window = window, record = record, now = now)
            if (index != record.windows.lastIndex) {
                Spacer(modifier = Modifier.height(PocketShellSpacing.md))
            }
        }

        record.resetCredits?.let { resetCredits ->
            Spacer(modifier = Modifier.height(PocketShellSpacing.lg))
            UsageResetCreditsSection(resetCredits = resetCredits, now = now)
        }

        val messages = listOfNotNull(
            record.blockReason.takeIf { record.windows.isEmpty() },
            usageTelemetryMessageForDisplay(record.lastError),
        ).distinct()
        messages.forEachIndexed { index, message ->
            Spacer(
                modifier = Modifier.height(
                    if (index == 0) PocketShellSpacing.sm else PocketShellSpacing.xs,
                ),
            )
            Text(
                text = message,
                color = PocketShellColors.TextMuted,
                style = PocketShellType.labelMono,
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
                    style = PocketShellType.bodyDense,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(usageResetCreditTitleTag(index)),
                )
                Text(
                    text = expiry.primary,
                    color = PocketShellColors.TextMuted,
                    style = PocketShellType.labelMono,
                    modifier = Modifier.testTag(usageResetCreditExpiryTag(index)),
                )
                expiry.absolute?.let { absolute ->
                    Text(
                        text = absolute,
                        color = PocketShellColors.TextSecondary,
                        style = PocketShellType.labelMono,
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
                style = PocketShellType.labelMono,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(modifier = Modifier.height(PocketShellSpacing.xs + 2.dp))
        ProgressBar(
            progress = (window.percent / 100.0).toFloat(),
            kind = progressKind(window.percent, record.isBlocked),
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
            style = PocketShellType.bodyDense,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "server-side usage tracking unavailable",
            color = PocketShellColors.TextSecondary,
            style = PocketShellType.labelMono,
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
                style = PocketShellType.labelMono,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag(usageFailedHostReasonTag(host.hostId)),
            )
        },
    )
}

@Composable
internal fun ProviderDot(kind: DotKind) {
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

const val USAGE_SUMMARY_STRIP_TAG: String = "usage:summary"
fun usageSummaryRowTag(provider: String): String = "usage:summary:" + provider.lowercase()

const val USAGE_OVERFLOW_TAG: String = "usage:overflow"
const val USAGE_REFRESH_ACTION_TAG: String = "usage:overflow:refresh"

const val USAGE_RESET_CREDITS_SECTION_TAG: String = "usage:reset-credits"
const val USAGE_RESET_CREDITS_HEADER_TAG: String = "usage:reset-credits:header"
const val USAGE_RESET_CREDITS_UNAVAILABLE_TAG: String = "usage:reset-credits:unavailable"

fun usageResetCreditTitleTag(index: Int): String = "usage:reset-credits:$index:title"
fun usageResetCreditExpiryTag(index: Int): String = "usage:reset-credits:$index:expiry"

/** Per-provider card tag. Lowercased so a probe stays stable across casings. */
fun usageProviderCardTag(provider: String): String = "usage:provider:" + provider.lowercase()

fun usageWindowRowTag(provider: String, window: String): String =
    "usage:provider:" + provider.lowercase() + ":window:" + window.lowercase()

fun usageMissingToolTag(hostId: Long): String = "usage:missing-tool:$hostId"
fun usageFailedHostTag(hostId: Long): String = "usage:failed-host:$hostId"
fun usageFailedHostReasonTag(hostId: Long): String = "usage:failed-host-reason:$hostId"
