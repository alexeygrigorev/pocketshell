package com.pocketshell.app.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketshell.core.agents.ToolArgsView
import com.pocketshell.uikit.theme.PocketShellColors

/**
 * Structured renderer for the EXPANDED tool-call argument block (#841).
 *
 * Replaces the old single raw/pretty JSON blob with a readable layout:
 * - the primary command (Codex `exec_command` `cmd`, Claude `Bash` `command`,
 *   …) as a prominent monospace command line, and
 * - the remaining argument fields (`timeout_ms`, `cwd`, …) as compact labeled
 *   key/value rows.
 *
 * Degrades gracefully: when the args are not a structurable JSON object the
 * model is [ToolArgsView.Raw] and we fall through to [ConversationTextSection]
 * with the same pretty-printed/elided text the card used before — never a
 * regression to the raw one-liner.
 */
@Composable
internal fun ConversationToolArgsSection(
    view: ToolArgsView,
    copyTestTag: String,
    rawCopyText: String,
    modifier: Modifier = Modifier,
) {
    when (view) {
        is ToolArgsView.Raw -> ConversationTextSection(
            label = "input",
            body = view.text,
            copyTestTag = copyTestTag,
            modifier = modifier,
        )
        is ToolArgsView.Structured -> StructuredArgs(
            view = view,
            copyTestTag = copyTestTag,
            rawCopyText = rawCopyText,
            modifier = modifier,
        )
    }
}

@Composable
private fun StructuredArgs(
    view: ToolArgsView.Structured,
    copyTestTag: String,
    rawCopyText: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "input",
                color = PocketShellColors.TextMuted,
                fontSize = 10.sp,
                modifier = Modifier.weight(1f),
            )
            // Copy still yields the full raw args so nothing is lost vs the blob.
            ConversationCopyAction(
                text = rawCopyText,
                testTag = copyTestTag,
                clipboardLabel = "conversation tool input",
            )
        }
        val command = view.command
        if (command != null) {
            CommandLine(
                command = command,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(copyTestTag + ":command"),
            )
        }
        view.fields.forEach { field ->
            FieldRow(
                label = field.label,
                value = field.value,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .testTag(copyTestTag + ":field:" + field.label),
            )
        }
    }
}

/** The emphasised, monospace primary command line. */
@Composable
private fun CommandLine(command: String, modifier: Modifier = Modifier) {
    SelectionContainer(
        modifier = modifier
            .background(color = PocketShellColors.TermBg, shape = RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(
            text = "$ $command",
            color = PocketShellColors.TermText,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** A compact labeled key/value row for a non-command argument field. */
@Composable
private fun FieldRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.Top) {
        Text(
            text = label,
            color = PocketShellColors.TextMuted,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(FieldLabelWidth),
        )
        SelectionContainer(modifier = Modifier.weight(1f)) {
            Text(
                text = value,
                color = PocketShellColors.TermText,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

private val FieldLabelWidth = 96.dp
