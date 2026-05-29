package com.pocketshell.app.projects

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketshell.app.sessions.StartDirectoryAutocompleteController
import com.pocketshell.app.sessions.StartDirectoryAutocompleteField
import com.pocketshell.app.sessions.rememberStartDirectoryAutocompleteController
import com.pocketshell.uikit.theme.PocketShellColors

/**
 * Picker for "new session" type — issue #171 round 2.
 *
 * The maintainer's refinement comment requires that "+ New session"
 * prompts the user for the SESSION TYPE (agent vs shell) and, when
 * "Agent" is chosen, a sub-picker for the agent CLI
 * (`claude` / `codex` / `opencode`). The folder is the explicit `cwd`
 * for the new session.
 *
 * The sheet is presented from [FolderListScreen] when the user taps the
 * FAB or an empty-folder row. Confirming the sheet fires
 * [onCreate] with the chosen kind + cwd + (optional) agent CLI; the
 * caller routes to `AppDestination.TmuxSession` with the right
 * `startDirectory` and (for agent sessions) a `startCommand` that the
 * tmux create path invokes via `send-keys` so the agent CLI runs as
 * the first command in the new pane.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionTypePickerSheet(
    folderPath: String,
    folderLabel: String,
    onDismiss: () -> Unit,
    onCreate: (choice: SessionTypeChoice) -> Unit,
    suggestStartDirectories: (suspend (String) -> List<String>)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val autocompleteController = rememberStartDirectoryAutocompleteController(suggestStartDirectories)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = PocketShellColors.Surface,
        modifier = Modifier.testTag(SESSION_TYPE_PICKER_SHEET_TAG),
    ) {
        SessionTypePickerContent(
            folderPath = folderPath,
            folderLabel = folderLabel,
            onCancel = onDismiss,
            onCreate = onCreate,
            autocompleteController = autocompleteController,
        )
    }
}

/**
 * Pure content of the sheet — split out from the [ModalBottomSheet]
 * wrapper so Compose tests can drive the body without paying for the
 * sheet animation harness.
 */
@Composable
internal fun SessionTypePickerContent(
    folderPath: String,
    folderLabel: String,
    onCancel: () -> Unit,
    onCreate: (choice: SessionTypeChoice) -> Unit,
    autocompleteController: StartDirectoryAutocompleteController? = null,
) {
    var sessionType by remember { mutableStateOf(SessionType.Agent) }
    var agentKind by remember { mutableStateOf(AgentCli.Claude) }
    var startDirectory by remember { mutableStateOf(folderPath) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "New session",
            color = PocketShellColors.Text,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "in $folderLabel",
            color = PocketShellColors.TextSecondary,
            fontSize = 13.sp,
        )

        // Segmented control: Shell vs Agent.
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionTitle("Session type")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .background(PocketShellColors.SurfaceElev, RoundedCornerShape(10.dp))
                    .border(1.dp, PocketShellColors.BorderSoft, RoundedCornerShape(10.dp)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SegmentButton(
                    label = "Shell",
                    selected = sessionType == SessionType.Shell,
                    onClick = { sessionType = SessionType.Shell },
                    testTag = SESSION_TYPE_PICKER_SHELL_TAG,
                    modifier = Modifier.weight(1f),
                )
                SegmentButton(
                    label = "Agent",
                    selected = sessionType == SessionType.Agent,
                    onClick = { sessionType = SessionType.Agent },
                    testTag = SESSION_TYPE_PICKER_AGENT_TAG,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // Conditional agent CLI sub-picker.
        if (sessionType == SessionType.Agent) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionTitle("Agent CLI")
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(PocketShellColors.SurfaceElev, RoundedCornerShape(10.dp))
                        .border(1.dp, PocketShellColors.BorderSoft, RoundedCornerShape(10.dp)),
                ) {
                    AgentCliRow(
                        label = "claude",
                        cli = AgentCli.Claude,
                        selected = agentKind == AgentCli.Claude,
                        onClick = { agentKind = AgentCli.Claude },
                        testTag = SESSION_TYPE_PICKER_AGENT_CLAUDE_TAG,
                    )
                    AgentCliRow(
                        label = "codex",
                        cli = AgentCli.Codex,
                        selected = agentKind == AgentCli.Codex,
                        onClick = { agentKind = AgentCli.Codex },
                        testTag = SESSION_TYPE_PICKER_AGENT_CODEX_TAG,
                    )
                    AgentCliRow(
                        label = "opencode",
                        cli = AgentCli.OpenCode,
                        selected = agentKind == AgentCli.OpenCode,
                        onClick = { agentKind = AgentCli.OpenCode },
                        testTag = SESSION_TYPE_PICKER_AGENT_OPENCODE_TAG,
                    )
                }
                Text(
                    text = "The CLI will auto-start in the new pane.",
                    color = PocketShellColors.TextMuted,
                    fontSize = 11.sp,
                )
            }
        }

        // Start folder — pre-filled, editable.
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionTitle("Start folder")
            StartDirectoryAutocompleteField(
                value = startDirectory,
                onValueChange = { startDirectory = it },
                modifier = Modifier
                    .fillMaxWidth(),
                textFieldTestTag = SESSION_TYPE_PICKER_CWD_TAG,
                autocompleteController = autocompleteController,
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(
                onClick = onCancel,
                modifier = Modifier.testTag(SESSION_TYPE_PICKER_CANCEL_TAG),
            ) {
                Text("Cancel", color = PocketShellColors.TextSecondary)
            }
            Spacer(modifier = Modifier.padding(end = 8.dp))
            Button(
                onClick = {
                    onCreate(
                        SessionTypeChoice(
                            type = sessionType,
                            agent = if (sessionType == SessionType.Agent) agentKind else null,
                            startDirectory = startDirectory.trim().ifBlank { folderPath },
                        ),
                    )
                },
                enabled = startDirectory.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PocketShellColors.Accent,
                    contentColor = PocketShellColors.OnAccent,
                ),
                modifier = Modifier.testTag(SESSION_TYPE_PICKER_CREATE_TAG),
            ) {
                Text("Create")
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = PocketShellColors.TextMuted,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun SegmentButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    val bg = if (selected) PocketShellColors.AccentSoft else PocketShellColors.SurfaceElev
    val fg = if (selected) PocketShellColors.Accent else PocketShellColors.TextSecondary
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .padding(2.dp)
            .background(bg, RoundedCornerShape(8.dp))
            .clickable(role = Role.Tab, onClick = onClick)
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = fg,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

@Composable
private fun AgentCliRow(
    label: String,
    @Suppress("UNUSED_PARAMETER") cli: AgentCli,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String,
) {
    val bg = if (selected) PocketShellColors.AccentSoft else PocketShellColors.SurfaceElev
    val fg = if (selected) PocketShellColors.Accent else PocketShellColors.Text
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = fg,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Text(
                text = "✓",
                color = PocketShellColors.Accent,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** What the picker emits when the user confirms. */
data class SessionTypeChoice(
    val type: SessionType,
    val agent: AgentCli?,
    val startDirectory: String,
) {
    /**
     * The start command to invoke inside the new tmux pane after
     * creation. `null` for plain shell sessions (which need no extra
     * command beyond the user's default shell).
     */
    fun startCommand(): String? = when (type) {
        SessionType.Shell -> null
        SessionType.Agent -> agent?.command
    }
}

enum class SessionType { Shell, Agent }

enum class AgentCli(val command: String) {
    Claude("claude"),
    Codex("codex"),
    OpenCode("opencode"),
}

// Test tags exposed for unit / connected tests.
const val SESSION_TYPE_PICKER_SHEET_TAG: String = "session-type-picker:sheet"
const val SESSION_TYPE_PICKER_SHELL_TAG: String = "session-type-picker:shell"
const val SESSION_TYPE_PICKER_AGENT_TAG: String = "session-type-picker:agent"
const val SESSION_TYPE_PICKER_AGENT_CLAUDE_TAG: String = "session-type-picker:agent:claude"
const val SESSION_TYPE_PICKER_AGENT_CODEX_TAG: String = "session-type-picker:agent:codex"
const val SESSION_TYPE_PICKER_AGENT_OPENCODE_TAG: String = "session-type-picker:agent:opencode"
const val SESSION_TYPE_PICKER_CWD_TAG: String = "session-type-picker:cwd"
const val SESSION_TYPE_PICKER_CANCEL_TAG: String = "session-type-picker:cancel"
const val SESSION_TYPE_PICKER_CREATE_TAG: String = "session-type-picker:create"
