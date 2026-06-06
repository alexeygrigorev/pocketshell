package com.pocketshell.app.jobs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketshell.uikit.components.Breadcrumb
import com.pocketshell.uikit.components.Kebab
import com.pocketshell.uikit.components.KebabItem
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.StatusDot
import com.pocketshell.uikit.model.ConnectionStatus
import com.pocketshell.uikit.model.Crumb
import com.pocketshell.uikit.theme.PocketShellColors

public data class RecurringJobsScreenState(
    val hostName: String,
    val sessionName: String?,
    val jobs: List<RecurringJob>,
    val loading: Boolean = false,
    val error: String? = null,
)

@Composable
public fun RecurringJobsScreen(
    state: RecurringJobsScreenState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onAdd: (RecurringJobDraft) -> Unit,
    onEdit: (jobId: Int, draft: RecurringJobDraft, enabled: Boolean) -> Unit,
    onRemove: (jobId: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dialogJob by remember { mutableStateOf<RecurringJob?>(null) }
    var showCreate by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .background(PocketShellColors.Background)
            .verticalScroll(rememberScrollState()),
    ) {
        Breadcrumb(
            crumbs = listOf(
                Crumb(label = state.hostName, isCurrent = false, onClick = {}),
                Crumb(label = state.sessionName ?: "Jobs", isCurrent = true, onClick = {}),
            ),
            onBack = onBack,
            onMore = onRefresh,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Scheduled",
                    color = PocketShellColors.Text,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (state.loading) "Syncing..." else "${state.jobs.size} jobs",
                    color = PocketShellColors.TextMuted,
                    fontSize = 12.sp,
                )
            }
            TextButton(onClick = { showCreate = true }) {
                Text("+ Job")
            }
        }

        state.error?.let { error ->
            Text(
                text = error,
                color = PocketShellColors.Red,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp),
            )
        }

        if (state.jobs.isEmpty() && !state.loading) {
            Text(
                text = "No scheduled jobs",
                color = PocketShellColors.TextMuted,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 18.dp),
            )
        }

        state.jobs.forEach { job ->
            RecurringJobRow(
                job = job,
                onEdit = { dialogJob = job },
                onRemove = { onRemove(job.id) },
            )
        }

        Spacer(modifier = Modifier.height(28.dp))
    }

    if (showCreate) {
        JobEditorDialog(
            title = "New job",
            initialSessionName = state.sessionName.orEmpty(),
            initialEvery = "15m",
            initialMessage = "",
            initialEnabled = true,
            showEnabled = false,
            onDismiss = { showCreate = false },
            onSave = { draft, _ ->
                showCreate = false
                onAdd(draft)
            },
            onRemove = null,
        )
    }

    dialogJob?.let { job ->
        JobEditorDialog(
            title = "Edit job ${job.id}",
            initialSessionName = job.sessionName,
            initialEvery = job.every,
            initialMessage = if (job.source == RecurringJobSource.Inline) job.detail else "",
            initialEnabled = job.enabled,
            showEnabled = true,
            onDismiss = { dialogJob = null },
            onSave = { draft, enabled ->
                dialogJob = null
                onEdit(job.id, draft, enabled)
            },
            onRemove = {
                dialogJob = null
                onRemove(job.id)
            },
        )
    }
}

/**
 * One scheduled-job row. Routes through the shared [ListRow] (#479 Slice C1):
 *
 *  - **leading** — a semantic [StatusDot]: green/[ConnectionStatus.Connected]
 *    when the job is enabled, muted/[ConnectionStatus.Idle] when paused. This
 *    replaces the old on/paused [com.pocketshell.uikit.components.Pill] (§4
 *    enabled/last-run → dot).
 *  - **title** — the job detail (or `Job N` fallback).
 *  - **subtitle** — the `session | every X | next Y` schedule, rendered on the
 *    [ListRow]'s `bodyMono` rung (the row's mono subtitle slot is exactly the
 *    schedule/path vocabulary).
 *  - **trailing** — a per-row [Kebab] carrying Edit / Remove (§4 decision 4:
 *    edit/remove move off inline buttons into the one overflow affordance).
 */
@Composable
private fun RecurringJobRow(
    job: RecurringJob,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    ListRow(
        title = job.detail.ifBlank { "Job ${job.id}" },
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .background(PocketShellColors.Surface, RoundedCornerShape(8.dp))
            .border(1.dp, PocketShellColors.BorderSoft, RoundedCornerShape(8.dp)),
        subtitle = "${job.sessionName} | every ${job.every} | next ${job.nextRun}",
        leading = {
            StatusDot(
                status = if (job.enabled) {
                    ConnectionStatus.Connected
                } else {
                    ConnectionStatus.Idle
                },
            )
        },
        trailing = {
            Kebab(
                items = listOf(
                    KebabItem(label = "Edit", onClick = onEdit),
                    KebabItem(label = "Remove", onClick = onRemove),
                ),
            )
        },
    )
}

@Composable
private fun JobEditorDialog(
    title: String,
    initialSessionName: String,
    initialEvery: String,
    initialMessage: String,
    initialEnabled: Boolean,
    showEnabled: Boolean,
    onDismiss: () -> Unit,
    onSave: (RecurringJobDraft, enabled: Boolean) -> Unit,
    onRemove: (() -> Unit)?,
) {
    var sessionName by remember { mutableStateOf(initialSessionName) }
    var every by remember { mutableStateOf(initialEvery) }
    var message by remember { mutableStateOf(initialMessage) }
    var enabled by remember { mutableStateOf(initialEnabled) }
    val messageRequired = title.startsWith("New") || initialMessage.isNotBlank()
    val canSave = sessionName.trim().isNotEmpty() &&
        every.trim().isNotEmpty() &&
        (!messageRequired || message.trim().isNotEmpty())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = sessionName,
                    onValueChange = { sessionName = it },
                    singleLine = true,
                    label = { Text("Session") },
                )
                OutlinedTextField(
                    value = every,
                    onValueChange = { every = it },
                    singleLine = true,
                    label = { Text("Every") },
                )
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    minLines = 3,
                    label = { Text("Message") },
                )
                if (showEnabled) {
                    TextButton(onClick = { enabled = !enabled }) {
                        Text(if (enabled) "Pause job" else "Resume job")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        RecurringJobDraft(
                            sessionName = sessionName.trim(),
                            every = every.trim(),
                            message = message.takeIf { it.isNotBlank() },
                        ),
                        enabled,
                    )
                },
                enabled = canSave,
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            Row {
                onRemove?.let {
                    TextButton(onClick = it) {
                        Text("Remove")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        },
    )
}
