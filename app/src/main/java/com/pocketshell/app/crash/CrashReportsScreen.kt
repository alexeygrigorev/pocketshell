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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketshell.uikit.theme.PocketShellColors
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun CrashReportsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val store = remember(context) { CrashReporter.store(context) }
    var reports by remember { mutableStateOf(store.list()) }
    var selected by remember(reports) { mutableStateOf(reports.firstOrNull()) }
    var selectedBody by remember(selected) {
        mutableStateOf(selected?.let { store.read(it) }.orEmpty())
    }

    fun reload() {
        reports = store.list()
    }

    fun shareSelected() {
        val body = selectedBody.takeIf { it.isNotBlank() } ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "PocketShell crash report")
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

        if (reports.isEmpty()) {
            EmptyCrashReports(modifier = Modifier.weight(1f))
            return@Column
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    text = "Reports are stored only on this device. Sharing opens Android's chooser.",
                    color = PocketShellColors.TextSecondary,
                    fontSize = 12.sp,
                )
            }

            items(reports, key = { it.id }) { report ->
                CrashReportRow(
                    report = report,
                    selected = report.id == selected?.id,
                    onClick = { selected = report },
                )
            }

            item {
                selected?.let { report ->
                    CrashReportDetail(
                        report = report,
                        body = selectedBody,
                        onShare = ::shareSelected,
                        onDelete = {
                            store.delete(report)
                            selectedBody = ""
                            reload()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CrashReportsAppBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .background(PocketShellColors.Background)
            .border(width = 1.dp, color = PocketShellColors.BorderSoft)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppBarTextButton(label = "Back", onClick = onBack)
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = "Crash reports",
            color = PocketShellColors.Text,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CrashReportRow(
    report: CrashReport,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val border = if (selected) PocketShellColors.Accent else PocketShellColors.BorderSoft
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PocketShellColors.Surface, RoundedCornerShape(8.dp))
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = report.summary,
            color = PocketShellColors.Text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = ReportTimeFormatter.format(report.timestamp.atZone(ZoneId.systemDefault())),
            color = PocketShellColors.TextMuted,
            fontSize = 11.sp,
        )
    }
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
                    text = "Selected report",
                    color = PocketShellColors.Text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = report.id,
                    color = PocketShellColors.TextMuted,
                    fontSize = 11.sp,
                )
            }
            ActionButton(label = "Share", onClick = onShare)
            Spacer(modifier = Modifier.width(8.dp))
            ActionButton(label = "Delete", onClick = onDelete)
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
            Text(
                text = body,
                color = PocketShellColors.TextSecondary,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                fontFamily = FontFamily.Monospace,
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
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ActionButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(36.dp)
            .background(PocketShellColors.Accent, RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = PocketShellColors.OnAccent,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private val ReportTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
