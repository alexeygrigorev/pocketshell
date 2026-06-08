package com.pocketshell.app.crash

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketshell.uikit.components.Badge
import com.pocketshell.uikit.components.BadgeRole
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.components.SectionHeader
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DialogScrimColor = Color(0xCC000000)

internal const val CRASH_REPORTS_SHARE_ALL_TAG = "crash:shareAll"
internal const val CRASH_REPORTS_DELETE_ALL_TAG = "crash:deleteAll"
internal const val CRASH_REPORTS_DELETE_ALL_CONFIRM_TAG = "crash:deleteAll:confirm"

@Composable
fun CrashReportsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: CrashReportsViewModel = hiltViewModel()
    val context = LocalContext.current
    val reports by viewModel.reports.collectAsStateWithLifecycle()
    val shareAllState by viewModel.shareAllState.collectAsStateWithLifecycle()

    var selectedId by remember(reports) { mutableStateOf(reports.firstOrNull()?.id) }
    val selected = reports.firstOrNull { it.id == selectedId } ?: reports.firstOrNull()
    val selectedBody = remember(selected) { selected?.let { viewModel.read(it) }.orEmpty() }
    var confirmDeleteAll by remember { mutableStateOf(false) }

    // Re-list whenever the screen is (re)composed onto the back stack so a
    // crash that happened mid-session shows up without a manual refresh.
    LaunchedEffect(Unit) { viewModel.reload() }

    LaunchedEffect(shareAllState) {
        val state = shareAllState as? ShareAllState.Prepared ?: return@LaunchedEffect
        val result = runCatching {
            shareReportsArchive(context, state.archive)
        }
        result.fold(
            onSuccess = { viewModel.markShareAllLaunched() },
            onFailure = { error ->
                viewModel.shareAllLaunchFailed(
                    error.message ?: "Could not open the Android share sheet.",
                )
            },
        )
    }

    fun shareSelected() {
        val body = selectedBody.takeIf { it.isNotBlank() } ?: return
        val report = selected ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, crashReportShareSubject(report))
            putExtra(Intent.EXTRA_TEXT, body)
        }
        context.startActivity(Intent.createChooser(intent, "Share crash report"))
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PocketShellColors.Background),
    ) {
        CrashReportsAppBar(onBack = onBack)

        BulkActionsBar(
            reportCount = reports.size,
            shareAllState = shareAllState,
            onShareAll = { viewModel.shareAll() },
            onDeleteAll = { confirmDeleteAll = true },
        )

        if (reports.isEmpty()) {
            EmptyCrashReports(modifier = Modifier.weight(1f))
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(
                    horizontal = PocketShellDensity.rowPadH,
                    vertical = PocketShellSpacing.md,
                ),
                verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm),
            ) {
                item {
                    Text(
                        text = "Reports are stored only on this device. " +
                            "\"Share all\" zips every report and opens " +
                            "Android's share sheet.",
                        color = PocketShellColors.TextSecondary,
                        style = PocketShellType.bodyDense,
                    )
                }

                item {
                    SectionHeader(label = "Reports", count = reports.size)
                }

                items(reports, key = { it.id }) { report ->
                    CrashReportRow(
                        report = report,
                        selected = report.id == selected?.id,
                        onClick = { selectedId = report.id },
                    )
                }

                item {
                    selected?.let { report ->
                        CrashReportDetail(
                            report = report,
                            body = selectedBody,
                            onShare = ::shareSelected,
                            onDelete = { viewModel.deleteOne(report) },
                        )
                    }
                }
            }
        }
    }

    when (val state = shareAllState) {
        is ShareAllState.Failed -> ShareAllResultDialog(
            title = "Share failed",
            message = "${state.message}\n\nReports were kept on this device.",
            onDismiss = { viewModel.clearShareAllState() },
        )
        else -> Unit
    }

    if (confirmDeleteAll) {
        ConfirmDeleteAllDialog(
            count = reports.size,
            onConfirm = {
                viewModel.deleteAll()
                confirmDeleteAll = false
            },
            onDismiss = { confirmDeleteAll = false },
        )
    }
}

@Composable
private fun BulkActionsBar(
    reportCount: Int,
    shareAllState: ShareAllState,
    onShareAll: () -> Unit,
    onDeleteAll: () -> Unit,
) {
    val preparing = shareAllState is ShareAllState.Preparing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PocketShellColors.Background)
            .padding(horizontal = PocketShellDensity.rowPadH, vertical = PocketShellSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val label = when {
            preparing -> "Preparing..."
            else -> "Share all ($reportCount)"
        }
        ActionButton(
            label = label,
            onClick = onShareAll,
            enabled = reportCount > 0 && !preparing,
            modifier = Modifier.testTag(CRASH_REPORTS_SHARE_ALL_TAG),
        )
        Spacer(modifier = Modifier.width(8.dp))
        ActionButton(
            label = "Delete all",
            onClick = onDeleteAll,
            enabled = reportCount > 0 && !preparing,
            modifier = Modifier.testTag(CRASH_REPORTS_DELETE_ALL_TAG),
        )
        Spacer(modifier = Modifier.weight(1f))
        Badge(
            label = "$reportCount report(s)",
            role = BadgeRole.Idle,
            mono = false,
        )
    }
}

@Composable
private fun ShareAllResultDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
) {
    DialogScrim(onDismiss = onDismiss) {
        Text(
            text = title,
            color = PocketShellColors.Text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = message,
            color = PocketShellColors.TextSecondary,
            style = PocketShellType.bodyMono,
        )
        Spacer(modifier = Modifier.height(14.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            ActionButton(label = "OK", onClick = onDismiss, enabled = true)
        }
    }
}

@Composable
private fun ConfirmDeleteAllDialog(
    count: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    DialogScrim(onDismiss = onDismiss) {
        Text(
            text = "Delete all reports?",
            color = PocketShellColors.Text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "This permanently removes $count report(s) from this device. " +
                "Share them first if you want to keep a copy.",
            color = PocketShellColors.TextSecondary,
            style = PocketShellType.bodyDense,
        )
        Spacer(modifier = Modifier.height(14.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            AppBarTextButton(label = "Cancel", onClick = onDismiss)
            Spacer(modifier = Modifier.width(8.dp))
            ActionButton(
                label = "Delete all",
                onClick = onConfirm,
                enabled = true,
                modifier = Modifier.testTag(CRASH_REPORTS_DELETE_ALL_CONFIRM_TAG),
            )
        }
    }
}

@Composable
private fun DialogScrim(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DialogScrimColor)
            .clickable(role = Role.Button, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .background(PocketShellColors.SurfaceElev, RoundedCornerShape(12.dp))
                .border(1.dp, PocketShellColors.BorderSoft, RoundedCornerShape(12.dp))
                // Swallow clicks on the card so they don't dismiss the dialog.
                .clickable(enabled = false) {}
                .padding(16.dp),
        ) {
            content()
        }
    }
}

/**
 * Crash reports header, routed through the shared [ScreenHeader] (#479 Slice C1)
 * so the screen reads as the tight dev-tool block — `bodyDense` SemiBold title +
 * `‹` back chevron in the leading slot — instead of the old 60dp / 20.sp bar.
 */
@Composable
private fun CrashReportsAppBar(onBack: () -> Unit) {
    ScreenHeader(
        title = "Crash reports",
        modifier = Modifier.border(width = 1.dp, color = PocketShellColors.BorderSoft),
        leading = {
            Box(
                modifier = Modifier
                    .size(40.dp)
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
    )
}

/**
 * A single crash-report row. Routes through the shared [ListRow] (#479 Slice C1)
 * for the dense 44/8/12 row density + 48dp touch floor; the summary is the row
 * title with the crash timestamp in front, and the compact context summary
 * stays in the subtitle. The selected report keeps its accent-bordered card so
 * the user can still see which report the detail pane below reflects.
 */
@Composable
private fun CrashReportRow(
    report: CrashReport,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val border = if (selected) PocketShellColors.Accent else PocketShellColors.BorderSoft
    ListRow(
        title = crashReportRowTitle(report),
        subtitle = crashReportRowSubtitle(report),
        modifier = Modifier
            .background(PocketShellColors.Surface, RoundedCornerShape(8.dp))
            .border(1.dp, border, RoundedCornerShape(8.dp)),
        onClick = onClick,
    )
}

@Composable
private fun CrashReportDetail(
    report: CrashReport,
    body: String,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PocketShellColors.SurfaceElev, RoundedCornerShape(8.dp))
            .border(1.dp, PocketShellColors.BorderSoft, RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = crashReportRowTitle(report),
                    color = PocketShellColors.Text,
                    style = PocketShellType.bodyDense,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "id=${report.id}",
                    color = PocketShellColors.TextMuted,
                    style = PocketShellType.labelMono,
                )
                Text(
                    text = "context=${report.contextSummary}",
                    color = PocketShellColors.TextMuted,
                    style = PocketShellType.labelMono,
                )
                Text(
                    text = crashReportDetailMetadata(report),
                    color = PocketShellColors.TextMuted,
                    style = PocketShellType.labelMono,
                )
            }
            ActionButton(label = "Share", onClick = onShare, enabled = true)
            Spacer(modifier = Modifier.width(8.dp))
            ActionButton(label = "Delete", onClick = onDelete, enabled = true)
        }

        Spacer(modifier = Modifier.height(10.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
                .background(PocketShellColors.Background, RoundedCornerShape(6.dp))
                .border(1.dp, PocketShellColors.BorderSoft, RoundedCornerShape(6.dp))
                .verticalScroll(rememberScrollState())
                .padding(10.dp),
        ) {
            // The crash stack body is the screen's mono content (#479 Slice C1
            // "stack path → bodyMono"). The flat CrashReportRow only carries the
            // summary + timestamp, so the stack text lives here in the detail
            // pane; route it through the shared mono rung instead of a raw 11.sp
            // FontFamily.Monospace literal.
            Text(
                text = body,
                color = PocketShellColors.TextSecondary,
                style = PocketShellType.bodyMono,
            )
        }
    }
}

@Composable
private fun EmptyCrashReports(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "No crash reports",
                color = PocketShellColors.Text,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Uncaught crashes will be saved locally.",
                color = PocketShellColors.TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun AppBarTextButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(40.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = PocketShellColors.TextSecondary,
            style = PocketShellType.bodyDense,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ActionButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val background = if (enabled) PocketShellColors.Accent else PocketShellColors.BorderSoft
    Box(
        modifier = modifier
            .height(36.dp)
            .background(background, RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = PocketShellColors.OnAccent,
            style = PocketShellType.bodyDense,
            fontWeight = FontWeight.SemiBold,
        )
    }
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
        report.appVersion?.takeIf { it.isNotBlank() }?.let { "app=$it" },
        report.topFrame?.takeIf { it.isNotBlank() }?.let { "top=${it.toCrashReportTopFrameLabel()}" },
    ).joinToString(" · ")
        .ifBlank { "metadata unavailable" }

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
        Intent.createChooser(intent, "Share crash reports").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        },
    )
}
