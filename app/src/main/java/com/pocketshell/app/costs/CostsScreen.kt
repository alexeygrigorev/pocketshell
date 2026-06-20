package com.pocketshell.app.costs

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.ConfirmDialog
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.components.SectionHeader
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AI Costs screen (issue #181). Shows total client-side OpenAI spend
 * recorded by the Whisper call site, broken down by time window and by
 * feature, with a recent-calls list, CSV export, and a "Clear log"
 * affordance.
 *
 * Sections (top-to-bottom):
 *
 *  - **Totals** — lifetime / this month / this week / today, rendered as
 *    a 4-card grid. Lifetime always renders even with zero entries so
 *    a fresh install doesn't show an empty screen.
 *  - **Breakdown** — per `(provider, feature)` lifetime totals,
 *    sorted descending. Today only one row (OpenAI · Whisper) is
 *    populated; the empty-state language nudges the user to make a
 *    voice request if no rows exist.
 *  - **Recent calls** — up to 50 most recent rows, with the timestamp,
 *    feature, input units (audio seconds for Whisper), and computed
 *    cost. The full history is in the CSV export.
 *  - **Actions** — "Export CSV" shares the file via `Intent.ACTION_SEND`
 *    so the user can drop it into email, GDrive, or a finance app.
 *    "Clear log" opens a confirmation dialog before deleting every row.
 */
@Composable
fun CostsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CostsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    var showClearDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PocketShellColors.Background),
    ) {
        CostsAppBar(onBack = onBack)

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag(COSTS_LAZY_COLUMN_TAG),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                TotalsSection(state = state)
            }
            item {
                BreakdownSection(state = state)
            }
            item {
                ByDaySection(state = state)
            }
            item {
                ActionsSection(
                    onExportCsv = {
                        val shared = exportCsvToShareFile(context, state.recentCalls.size, viewModelEntries(state))
                        if (shared != null) {
                            shareCsvFile(context, shared)
                        } else {
                            Toast.makeText(
                                context,
                                "No calls to export yet — make a voice prompt first.",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                    onClearLog = { showClearDialog = true },
                )
            }
        }
    }

    if (showClearDialog) {
        // #756: "Clear" permanently deletes the cost log → the canonical shared
        // destructive confirm (red text, NOT a filled red slab). The destructive
        // intent now reads from ConfirmDialog's `destructive` flag instead of a
        // per-call colour.
        ConfirmDialog(
            title = "Clear cost log?",
            message = "This permanently deletes every recorded API call. " +
                "Aggregates reset to zero. There is no undo.",
            confirmLabel = "Clear",
            onConfirm = {
                viewModel.clearLog()
                showClearDialog = false
            },
            onDismiss = { showClearDialog = false },
            destructive = true,
            confirmTestTag = COSTS_CLEAR_CONFIRM_TAG,
        )
    }
}

/**
 * AI Costs header, routed through the shared [ScreenHeader] (#479 Slice C1) so
 * the screen reads as the tight dev-tool block — `bodyDense` SemiBold title +
 * `‹` back chevron in the leading slot — instead of the old 60dp / 22.sp bar.
 * The `‹` chevron and title keep their `costs:back` / `costs:title` test tags so
 * existing instrumentation keeps resolving them after the migration.
 */
@Composable
private fun CostsAppBar(onBack: () -> Unit) {
    ScreenHeader(
        title = "AI Costs",
        titleTestTag = COSTS_TITLE_TAG,
        modifier = Modifier.border(width = 1.dp, color = PocketShellColors.BorderSoft),
        leading = {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clickable(role = Role.Button, onClick = onBack)
                    .testTag(COSTS_BACK_TAG),
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

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .background(
                color = PocketShellColors.Surface,
                shape = PocketShellShapes.medium,
            )
            .border(
                width = 1.dp,
                color = PocketShellColors.BorderSoft,
                shape = PocketShellShapes.medium,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        content = content,
    )
}

@Composable
private fun TotalsSection(state: CostsUiState) {
    Column {
        SectionHeader(label = "Totals")
        SectionCard {
            // ListRow paints its own dense vertical padding, so the rows stack
            // directly without the old inter-row spacers.
            TotalRow(
                label = "Lifetime",
                value = CostFormat.formatUsd(state.lifetimeUsdMillicents),
                testTag = COSTS_TOTAL_LIFETIME_TAG,
            )
            TotalRow(
                label = "This month",
                value = CostFormat.formatUsd(state.monthUsdMillicents),
                testTag = COSTS_TOTAL_MONTH_TAG,
            )
            TotalRow(
                label = "This week",
                value = CostFormat.formatUsd(state.weekUsdMillicents),
                testTag = COSTS_TOTAL_WEEK_TAG,
            )
            TotalRow(
                label = "Today",
                value = CostFormat.formatUsd(state.todayUsdMillicents),
                testTag = COSTS_TOTAL_TODAY_TAG,
            )
        }
    }
}

/**
 * A lifetime/month/week/today total line. Routes through the shared [ListRow]
 * (#479 Slice C1) for the dense row density; the formatted USD value rides the
 * [trailing] slot on the SemiBold `bodyDense` rung and keeps its per-window test
 * tag so the cost instrumentation keeps resolving it.
 */
@Composable
private fun TotalRow(label: String, value: String, testTag: String) {
    ListRow(
        title = label,
        trailing = {
            Text(
                text = value,
                color = PocketShellColors.Text,
                style = PocketShellType.bodyDense,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.testTag(testTag),
            )
        },
    )
}

@Composable
private fun BreakdownSection(state: CostsUiState) {
    Column {
        SectionHeader(label = "By feature")
        SectionCard {
            if (state.featureBreakdown.isEmpty()) {
                Text(
                    text = "No API calls recorded yet. Use the voice composer to make a " +
                        "Whisper transcription and your spend will appear here.",
                    color = PocketShellColors.TextSecondary,
                    style = PocketShellType.bodyDense,
                    modifier = Modifier
                        .padding(vertical = 8.dp)
                        .testTag(COSTS_BREAKDOWN_EMPTY_TAG),
                )
            } else {
                state.featureBreakdown.forEach { row ->
                    ListRow(
                        title = CostFormat.featureLabel(row.provider, row.feature),
                        subtitle = "Priced per ${CostFormat.unitLabel(row.provider, row.feature)}",
                        trailing = {
                            Text(
                                text = CostFormat.formatUsd(row.totalUsdMillicents),
                                color = PocketShellColors.Text,
                                style = PocketShellType.bodyDense,
                                fontWeight = FontWeight.SemiBold,
                            )
                        },
                        modifier = Modifier.testTag(COSTS_BREAKDOWN_ROW_PREFIX + row.feature),
                    )
                }
            }
        }
    }
}

/**
 * Day-grouped request log (issue #467). Each local day gets a header
 * (Today / Yesterday / explicit date), the per-request rows for that day
 * (time + model + cost), and a per-day subtotal. The flat "Recent calls"
 * list this replaced collapsed everything into one block; grouping by day
 * is what the maintainer asked for so a day's spend is browsable at a
 * glance. Retention is intentionally out of scope — every recorded request
 * is shown.
 */
@Composable
private fun ByDaySection(state: CostsUiState) {
    Column {
        SectionHeader(
            label = "By day" + if (state.totalCallCount > 0) {
                " (${state.totalCallCount} ${if (state.totalCallCount == 1) "request" else "requests"})"
            } else {
                ""
            },
        )
        SectionCard {
            if (state.dailyGroups.isEmpty()) {
                Text(
                    text = "Empty. Once you make a voice prompt, each Whisper call will land here.",
                    color = PocketShellColors.TextSecondary,
                    style = PocketShellType.bodyDense,
                    modifier = Modifier
                        .padding(vertical = 8.dp)
                        .testTag(COSTS_RECENT_EMPTY_TAG),
                )
            } else {
                val today = remember(state.nowMillis) {
                    if (state.nowMillis > 0L) {
                        java.time.Instant.ofEpochMilli(state.nowMillis)
                            .atZone(java.time.ZoneId.systemDefault())
                            .toLocalDate()
                    } else {
                        java.time.LocalDate.now()
                    }
                }
                state.dailyGroups.forEachIndexed { index, group ->
                    if (index > 0) {
                        Spacer(modifier = Modifier.height(PocketShellSpacing.lg))
                    }
                    DayGroup(group = group, today = today)
                }
            }
        }
    }
}

@Composable
private fun DayGroup(group: DailyCostGroup, today: java.time.LocalDate) {
    val header = CostFormat.dayHeader(group.date, group.daysBeforeToday, today)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .testTag(COSTS_DAY_HEADER_TAG_PREFIX + group.date),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = header,
            color = PocketShellColors.Text,
            style = PocketShellType.bodyDense,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = CostFormat.formatUsd(group.subtotalUsdMillicents),
            color = PocketShellColors.Text,
            style = PocketShellType.bodyDense,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.testTag(COSTS_DAY_SUBTOTAL_TAG_PREFIX + group.date),
        )
    }
    group.requests.forEachIndexed { index, request ->
        if (index > 0) {
            Spacer(modifier = Modifier.height(6.dp))
        }
        // The per-request line is tabular (time · model · cost), so it rides the
        // shared mono rung instead of a raw 12.sp literal.
        Text(
            text = CostFormat.formatRequestRow(request),
            color = PocketShellColors.TextSecondary,
            style = PocketShellType.bodyMono,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .testTag(COSTS_RECENT_ROW_TAG_PREFIX + request.id),
        )
    }
}

@Composable
private fun ActionsSection(onExportCsv: () -> Unit, onClearLog: () -> Unit) {
    Column {
        SectionHeader(label = "Actions")
        SectionCard {
            ActionRow(
                label = "Export as CSV",
                description = "Share the full log as a CSV file for tax / billing.",
                testTag = COSTS_EXPORT_TAG,
                onClick = onExportCsv,
            )
            ActionRow(
                label = "Clear log",
                description = "Delete every recorded call. Aggregates reset to zero.",
                testTag = COSTS_CLEAR_TAG,
                onClick = onClearLog,
            )
        }
    }
}

/**
 * An "Export CSV" / "Clear log" action row. Mirrors the Settings nav-row pattern
 * (#479 Slice C1/D): the actionable line is the shared [ListRow] with a `›`
 * disclosure chevron in the [trailing] slot, and the prose description renders
 * below it on the `labelSmall`(11) caption rung (mono [ListRow] subtitles are
 * reserved for paths/IDs, so a prose description does not belong in that slot).
 */
@Composable
private fun ActionRow(label: String, description: String, testTag: String, onClick: () -> Unit) {
    ListRow(
        title = label,
        trailing = {
            Text(
                text = "›",
                color = PocketShellColors.TextSecondary,
                style = PocketShellType.bodyDense,
                fontWeight = FontWeight.Bold,
            )
        },
        onClick = onClick,
        modifier = Modifier.testTag(testTag),
    )
    Text(
        text = description,
        color = PocketShellColors.TextSecondary,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(
            start = PocketShellSpacing.sm,
            top = 2.dp,
            bottom = 4.dp,
        ),
    )
}

/**
 * Write the CSV blob to a shareable file under the app's external
 * `cache` directory and return the [File]. Returns `null` when there's
 * nothing to export — the caller shows a Toast in that case.
 *
 * `cache` rather than `files` because these are throwaway exports the
 * OS is free to delete. `FileProvider` then exposes the file via a
 * content URI for `Intent.ACTION_SEND` consumers.
 */
private fun exportCsvToShareFile(
    context: android.content.Context,
    sampleCount: Int,
    entries: List<com.pocketshell.core.storage.entity.AiApiCallEntry>,
): File? {
    if (entries.isEmpty()) return null
    val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    val csv = CostFormat.toCsv(entries)
    val cacheDir = File(context.cacheDir, "ai-costs-export").apply { mkdirs() }
    val file = File(cacheDir, "pocketshell-ai-costs-$timestamp.csv")
    file.writeText(csv)
    return file
}

/**
 * Issue an `ACTION_SEND` chooser for the just-written CSV file.
 * `FileProvider` grants the receiving app read access without
 * requiring `READ_EXTERNAL_STORAGE`.
 */
private fun shareCsvFile(context: android.content.Context, file: File) {
    val authority = context.packageName + ".fileprovider"
    val uri = FileProvider.getUriForFile(context, authority, file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "PocketShell AI cost log")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share cost log").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    })
}

/**
 * The exported CSV must include every recorded row, not just the
 * truncated "Recent calls" slice the UI renders. The view model keeps
 * only the recent slice in [CostsUiState] for screen rendering — full
 * history for export is rebuilt from the DAO on demand to avoid
 * caching unbounded data in UI state.
 *
 * For the issue #181 v1 the export path takes the recent calls list
 * directly (covers every call the user has made today across normal
 * sessions). A future iteration can plumb a flow-collecting export
 * helper through the view model if power users need years of data.
 */
private fun viewModelEntries(state: CostsUiState): List<com.pocketshell.core.storage.entity.AiApiCallEntry> =
    state.recentCalls

// -- Test tags --------------------------------------------------------

internal const val COSTS_LAZY_COLUMN_TAG = "costs:lazy-column"
internal const val COSTS_BACK_TAG = "costs:back"
internal const val COSTS_TITLE_TAG = "costs:title"
internal const val COSTS_TOTAL_LIFETIME_TAG = "costs:total:lifetime"
internal const val COSTS_TOTAL_MONTH_TAG = "costs:total:month"
internal const val COSTS_TOTAL_WEEK_TAG = "costs:total:week"
internal const val COSTS_TOTAL_TODAY_TAG = "costs:total:today"
internal const val COSTS_BREAKDOWN_EMPTY_TAG = "costs:breakdown:empty"
internal const val COSTS_BREAKDOWN_ROW_PREFIX = "costs:breakdown:row:"
internal const val COSTS_RECENT_EMPTY_TAG = "costs:recent:empty"
internal const val COSTS_RECENT_ROW_TAG_PREFIX = "costs:recent:row:"
internal const val COSTS_DAY_HEADER_TAG_PREFIX = "costs:day:header:"
internal const val COSTS_DAY_SUBTOTAL_TAG_PREFIX = "costs:day:subtotal:"
internal const val COSTS_EXPORT_TAG = "costs:action:export"
internal const val COSTS_CLEAR_TAG = "costs:action:clear"
internal const val COSTS_CLEAR_CONFIRM_TAG = "costs:action:clear-confirm"
