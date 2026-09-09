package com.pocketshell.next.crash

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketshell.uikit.components.Banner
import com.pocketshell.uikit.components.BannerRole
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.ConfirmDialog
import com.pocketshell.uikit.components.DisclosureIcon
import com.pocketshell.uikit.components.EmptyState
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.LoadingIndicator
import com.pocketshell.uikit.components.NavigationChevron
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.components.SectionHeader
import com.pocketshell.uikit.components.SpinnerSize
import com.pocketshell.uikit.icons.PocketShellIcons
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal const val CRASH_REPORTS_SHARE_ALL_TAG = "crash:shareAll"
internal const val CRASH_REPORTS_DELETE_ALL_TAG = "crash:deleteAll"
internal const val CRASH_REPORTS_DELETE_ALL_CONFIRM_TAG = "crash:deleteAll:confirm"
internal const val CRASH_REPORTS_DELETE_ALL_CANCEL_TAG = "crash:deleteAll:cancel"
internal const val CRASH_REPORTS_BACK_TAG = "crash:back"
internal const val CRASH_REPORT_SHARE_TAG = "crash:share"
internal const val CRASH_REPORTS_EXPORT_LATEST_TAG = "crash:exportLatest"
internal const val CRASH_REPORTS_CLEAR_TAG = CRASH_REPORTS_DELETE_ALL_TAG
internal const val CRASH_REPORTS_CLEAR_CONFIRM_TAG = CRASH_REPORTS_DELETE_ALL_CONFIRM_TAG
internal const val CRASH_REPORTS_CLEAR_CANCEL_TAG = CRASH_REPORTS_DELETE_ALL_CANCEL_TAG
internal const val CRASH_REPORT_TECHNICAL_DETAILS_TAG = "crash:technicalDetails"
internal const val CRASH_REPORT_PRIVACY_TAG = "crash:privacy"
internal const val DIAGNOSTICS_PAGE_TAG = "diagnostics-page"
internal const val DIAGNOSTIC_REPORT_PAGE_TAG = "diagnostic-report-page"

fun diagnosticReportRowTag(reportId: String): String = "diagnostics-report-$reportId"

/**
 * Production Diagnostics route. It reads the installation's actual
 * [CrashReportStore] through [CrashReportsViewModel]; the screen never invents
 * reports or provider data.
 */
@Composable
internal fun DiagnosticsScreen(
    onBack: () -> Unit,
    onOpenReport: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CrashReportsViewModel = hiltViewModel(),
) {
    val reports by viewModel.reports.collectAsStateWithLifecycle()
    val loadState by viewModel.loadState.collectAsStateWithLifecycle()
    var confirmDeleteAll by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.reload() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PocketShellColors.Background),
    ) {
        DiagnosticsHeader(onBack = onBack)
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag(DIAGNOSTICS_PAGE_TAG),
            contentPadding = PaddingValues(bottom = PocketShellSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.md),
        ) {
            when (val state = loadState) {
                CrashReportsLoadState.Loading -> item {
                    LoadingIndicator.Spinner(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(PocketShellSpacing.lg),
                        size = SpinnerSize.Medium,
                        label = "Loading local reports…",
                    )
                }

                is CrashReportsLoadState.Failed -> {
                    item {
                        Banner(
                            text = state.message,
                            role = BannerRole.Error,
                            leadingIcon = PocketShellIcons.Warning,
                            modifier = Modifier.padding(horizontal = PocketShellDensity.rowPadH),
                        )
                    }
                    item {
                        PocketShellButton(
                            text = "Retry loading reports",
                            onClick = viewModel::reload,
                            variant = ButtonVariant.Primary,
                            modifier = Modifier.padding(horizontal = PocketShellDensity.rowPadH),
                        )
                    }
                }

                CrashReportsLoadState.Ready -> {
                    item {
                        DiagnosticsIntro(reportCount = reports.size)
                    }
                    if (reports.isEmpty()) {
                        item {
                            EmptyState(
                                title = "No diagnostic reports",
                                description = "Uncaught crashes are kept locally until you choose to share them.",
                                icon = PocketShellIcons.File,
                                modifier = Modifier.height(220.dp),
                            )
                        }
                    } else {
                        item { SectionHeader(label = "Reports", count = reports.size) }
                        itemsIndexed(reports, key = { _, report -> report.id }) { index, report ->
                            ListRow(
                                title = if (index == 0) "Latest report" else "Earlier report",
                                subtitle = diagnosticReportListSubtitle(report),
                                leading = {
                                    androidx.compose.material3.Icon(
                                        PocketShellIcons.File,
                                        contentDescription = null,
                                        tint = PocketShellColors.TextSecondary,
                                    )
                                },
                                trailing = { NavigationChevron() },
                                onClick = { onOpenReport(report.id) },
                                modifier = Modifier.testTag(diagnosticReportRowTag(report.id)),
                            )
                        }
                        item {
                            ListRow(
                                title = "Export latest report",
                                subtitle = "Review before sharing",
                                leading = {
                                    androidx.compose.material3.Icon(
                                        PocketShellIcons.Upload,
                                        contentDescription = null,
                                        tint = PocketShellColors.TextSecondary,
                                    )
                                },
                                trailing = { NavigationChevron() },
                                onClick = { onOpenReport(reports.first().id) },
                                modifier = Modifier.testTag(CRASH_REPORTS_EXPORT_LATEST_TAG),
                            )
                        }
                    }
                    item {
                        ListRow(
                            title = "Clear local reports…",
                            subtitle = "Remove saved diagnostics from this device",
                            leading = {
                                androidx.compose.material3.Icon(
                                    PocketShellIcons.Trash,
                                    contentDescription = null,
                                    tint = PocketShellColors.TextSecondary,
                                )
                            },
                            trailing = if (reports.isNotEmpty()) {
                                { NavigationChevron() }
                            } else {
                                null
                            },
                            onClick = if (reports.isNotEmpty()) {
                                { confirmDeleteAll = true }
                            } else {
                                null
                            },
                            modifier = Modifier.testTag(CRASH_REPORTS_CLEAR_TAG),
                        )
                    }
                }
            }
        }
    }

    if (confirmDeleteAll) {
        ConfirmDialog(
            title = "Clear local reports?",
            message = "This removes saved diagnostic reports from this device. Your hosts, keys and remote sessions are unchanged.",
            confirmLabel = "Clear reports",
            destructive = true,
            confirmTestTag = CRASH_REPORTS_DELETE_ALL_CONFIRM_TAG,
            dismissTestTag = CRASH_REPORTS_DELETE_ALL_CANCEL_TAG,
            onConfirm = {
                viewModel.deleteAll()
                confirmDeleteAll = false
            },
            onDismiss = { confirmDeleteAll = false },
        )
    }
}

/** Compatibility wrapper for tests and callers that still use the old name. */
@Composable
internal fun CrashReportsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CrashReportsViewModel = hiltViewModel(),
) {
    DiagnosticsScreen(
        onBack = onBack,
        onOpenReport = {},
        modifier = modifier,
        viewModel = viewModel,
    )
}

@Composable
internal fun DiagnosticReportScreen(
    reportId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CrashReportsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val reports by viewModel.reports.collectAsStateWithLifecycle()
    val loadState by viewModel.loadState.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    var technicalDetailsOpen by remember(reportId) { mutableStateOf(false) }
    val report = reports.firstOrNull { it.id == reportId }
    val body = remember(report) { report?.let(viewModel::read).orEmpty() }

    LaunchedEffect(reportId) { viewModel.reload() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PocketShellColors.Background),
    ) {
        DiagnosticsHeader(title = "Connection report", onBack = onBack)
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag(DIAGNOSTIC_REPORT_PAGE_TAG),
            contentPadding = PaddingValues(bottom = PocketShellSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.md),
        ) {
            when {
                loadState is CrashReportsLoadState.Loading -> item {
                    LoadingIndicator.Spinner(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(PocketShellSpacing.lg),
                        size = SpinnerSize.Medium,
                        label = "Loading report…",
                    )
                }

                loadState is CrashReportsLoadState.Failed -> item {
                    Banner(
                        text = (loadState as CrashReportsLoadState.Failed).message,
                        role = BannerRole.Error,
                        leadingIcon = PocketShellIcons.Warning,
                        modifier = Modifier.padding(horizontal = PocketShellDensity.rowPadH),
                    )
                }

                report == null -> item {
                    EmptyState(
                        title = "Report unavailable",
                        description = "This local report may have been deleted.",
                        icon = PocketShellIcons.File,
                        modifier = Modifier.height(220.dp),
                    )
                }

                else -> {
                    item { ReportSummaryRows(report = report) }
                    item {
                        ListRow(
                            title = "Technical details",
                            subtitle = if (technicalDetailsOpen) "Hide report contents" else "Show report contents",
                            leading = {
                                androidx.compose.material3.Icon(
                                    PocketShellIcons.File,
                                    contentDescription = null,
                                    tint = PocketShellColors.TextSecondary,
                                )
                            },
                            trailing = {
                                DisclosureIcon(expanded = technicalDetailsOpen)
                            },
                            onClick = { technicalDetailsOpen = !technicalDetailsOpen },
                            modifier = Modifier.testTag(CRASH_REPORT_TECHNICAL_DETAILS_TAG),
                        )
                    }
                    item {
                        AnimatedVisibility(visible = technicalDetailsOpen) {
                            Text(
                                text = body.ifBlank { "Report contents are unavailable." },
                                color = PocketShellColors.TextSecondary,
                                style = PocketShellType.bodyMono,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = PocketShellDensity.rowPadH),
                            )
                        }
                    }
                    item {
                        Text(
                            text = "Review before sharing. Reports may contain hostnames, paths or terminal excerpts.",
                            color = PocketShellColors.TextSecondary,
                            style = PocketShellType.body,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = PocketShellDensity.rowPadH)
                                .testTag(CRASH_REPORT_PRIVACY_TAG),
                        )
                    }
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = PocketShellDensity.rowPadH),
                            verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm),
                        ) {
                            PocketShellButton(
                                text = "Share report",
                                onClick = {
                                    shareReport(
                                        context,
                                        report,
                                        CrashReportFormatter.redactForSharing(body),
                                    )
                                },
                                variant = ButtonVariant.Primary,
                                modifier = Modifier.testTag(CRASH_REPORT_SHARE_TAG),
                            )
                            PocketShellButton(
                                text = "Delete report",
                                onClick = { confirmDelete = true },
                                variant = ButtonVariant.Destructive,
                                modifier = Modifier.testTag("crash:delete"),
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete && report != null) {
        ConfirmDialog(
            title = "Delete this report?",
            message = "This permanently removes the local diagnostic report.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                viewModel.deleteOne(report)
                confirmDelete = false
                onBack()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun DiagnosticsHeader(
    onBack: () -> Unit,
    title: String = "Diagnostics",
) {
    ScreenHeader(
        title = title,
        onBack = onBack,
        backTestTag = CRASH_REPORTS_BACK_TAG,
    )
}

@Composable
private fun DiagnosticsIntro(reportCount: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PocketShellDensity.rowPadH),
        verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.xs),
    ) {
        Text(
            text = "Local reports",
            color = PocketShellColors.Text,
            style = PocketShellType.bodyDense,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = if (reportCount == 0) {
                "Reports stay on this device until you choose to share them."
            } else {
                "Reports stay on this device. Share them with support when needed."
            },
            color = PocketShellColors.TextSecondary,
            style = PocketShellType.bodyDense,
        )
    }
}

@Composable
private fun ReportSummaryRows(report: CrashReport) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PocketShellDensity.rowPadH),
        verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.xs),
    ) {
        ListRow(
            title = "Time",
            subtitle = crashReportTimestamp(report),
            leading = {
                androidx.compose.material3.Icon(
                    PocketShellIcons.History,
                    contentDescription = null,
                    tint = PocketShellColors.TextSecondary,
                )
            },
        )
        ListRow(
            title = "Screen",
            subtitle = report.contextSummary.substringBefore(" · ").ifBlank { "Unknown screen" },
            leading = {
                androidx.compose.material3.Icon(
                    PocketShellIcons.Terminal,
                    contentDescription = null,
                    tint = PocketShellColors.TextSecondary,
                )
            },
        )
        ListRow(
            title = "Summary",
            subtitle = report.summary,
            leading = {
                androidx.compose.material3.Icon(
                    PocketShellIcons.Info,
                    contentDescription = null,
                    tint = PocketShellColors.TextSecondary,
                )
            },
            subtitleMaxLines = 3,
        )
    }
}

private fun diagnosticReportListSubtitle(report: CrashReport): String =
    listOf(
        crashReportTimestamp(report),
        report.summary,
    ).joinToString(" · ")

private fun shareReport(
    context: android.content.Context,
    report: CrashReport,
    body: String,
) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, crashReportShareSubject(report))
        putExtra(Intent.EXTRA_TEXT, body)
    }
    context.startActivity(Intent.createChooser(intent, "Share diagnostic report"))
}

private val ReportTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")

internal fun crashReportTimestamp(
    report: CrashReport,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String = ReportTimeFormatter.format(report.timestamp.atZone(zoneId))

internal fun crashReportRowTitle(
    report: CrashReport,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String = "${crashReportTimestamp(report, zoneId)} · ${report.summary}"

internal fun crashReportRowSubtitle(report: CrashReport): String =
    listOfNotNull(
        report.contextSummary.takeIf { it.isNotBlank() },
        report.appVersion?.takeIf { it.isNotBlank() }?.let { "app=$it" },
        report.topFrame?.takeIf { it.isNotBlank() }?.let { "top=${it.toCrashReportTopFrameLabel()}" },
    ).joinToString(" · ")
        .ifBlank { "Context unavailable" }

internal fun crashReportShareSubject(
    report: CrashReport,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String =
    "PocketShell crash report - " +
        listOfNotNull(
            crashReportTimestamp(report, zoneId),
            report.contextSummary.takeIf { it.isNotBlank() },
            report.summary.takeIf { it.isNotBlank() },
        ).joinToString(" - ")

private fun crashReportDetailMetadata(report: CrashReport): String =
    listOfNotNull(
        report.appVersion?.takeIf { it.isNotBlank() }?.let { "App version · $it" },
        report.topFrame?.takeIf { it.isNotBlank() }?.let { "Top frame · ${it.toCrashReportTopFrameLabel()}" },
    ).joinToString(" · ")
        .ifBlank { "Metadata unavailable" }

private fun String.toCrashReportTopFrameLabel(): String {
    val sourceLocation = substringAfterLast('(', missingDelimiterValue = "")
        .removeSuffix(")")
        .takeIf { it.isNotBlank() }
    if (sourceLocation != null) return sourceLocation
    return substringAfterLast('.').takeIf { it.isNotBlank() } ?: this
}

private fun shareReportsArchive(context: android.content.Context, archive: java.io.File) {
    val uri = FileProvider.getUriForFile(
        context,
        context.packageName + ".fileprovider",
        archive,
    )
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, archive.name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(intent, "Share diagnostic reports").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        },
    )
}
