package com.pocketshell.uikit.render

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.components.Badge
import com.pocketshell.uikit.components.BadgeRole
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.components.SectionHeader
import com.pocketshell.uikit.components.SegmentedToggle
import com.pocketshell.uikit.components.StatusDot
import com.pocketshell.uikit.model.ConnectionStatus
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellType

@Composable
internal fun GitOverviewTabRender() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ScreenHeader(title = "Git history", subtitle = "agents")
        SegmentedToggle(
            labels = listOf("Overview", "History"),
            selectedIndex = 0,
            onSelected = {},
            fillSegments = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
        // Issue #648: "Open on GitHub" appears first when origin is a GitHub repo.
        SectionHeader(label = "Remote")
        ListRow(title = "Open on GitHub", subtitle = "github.com/owner/repo", onClick = {})
        SectionHeader(label = "Status")
        ListRow(
            title = "main",
            subtitle = "↑1 vs origin/main · 2 uncommitted changes\na1b2c3d Add overview tab",
            trailing = { Badge(label = "Dirty", role = BadgeRole.Error, mono = false) },
        )
        SectionHeader(label = "Branches", count = 2)
        ListRow(
            title = "main",
            subtitle = "Add overview tab",
            trailing = { Badge(label = "Current", role = BadgeRole.Active, mono = false) },
        )
        ListRow(title = "feature/x", subtitle = "tracks origin/feature/x")
        SectionHeader(label = "Worktrees", count = 2)
        ListRow(title = "/home/u/git/proj", subtitle = "main")
        ListRow(title = "/home/u/git/proj-feature", subtitle = "feature/x")
    }
}

@Composable
internal fun GitIssuesTabRender() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ScreenHeader(title = "Git history", subtitle = "pocketshell")
        SegmentedToggle(
            labels = listOf("Overview", "History", "Issues"),
            selectedIndex = 2,
            onSelected = {},
            fillSegments = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
        SectionHeader(label = "GitHub issues", count = 3)
        ListRow(
            title = "view GitHub issues in-app (gh issue list)",
            subtitle = "#649 · enhancement",
            leading = { StatusDot(status = ConnectionStatus.Connected) },
            trailing = { Badge(label = "Open", role = BadgeRole.Active, mono = false) },
        )
        ListRow(
            title = "Open on GitHub action",
            subtitle = "#648 · enhancement, slice-4",
            leading = { StatusDot(status = ConnectionStatus.Idle) },
            trailing = { Badge(label = "Closed", role = BadgeRole.Idle, mono = false) },
        )
        ListRow(
            title = "Read-only repo overview tab",
            subtitle = "#647",
            leading = { StatusDot(status = ConnectionStatus.Idle) },
            trailing = { Badge(label = "Closed", role = BadgeRole.Idle, mono = false) },
        )
    }
}

@Composable
internal fun GitIssuesConfigureGhHintRender() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ScreenHeader(title = "Git history", subtitle = "pocketshell")
        SegmentedToggle(
            labels = listOf("Overview", "History", "Issues"),
            selectedIndex = 2,
            onSelected = {},
            fillSegments = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
        SectionHeader(label = "GitHub issues")
        ListRow(
            title = "Configure gh to see issues",
            subtitle = "install gh (https://cli.github.com) and run `gh auth login`",
            trailing = { Badge(label = "Setup", role = BadgeRole.Idle, mono = false) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GitCreateIssueFormRender() {
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = PocketShellColors.Text,
        unfocusedTextColor = PocketShellColors.Text,
        focusedBorderColor = PocketShellColors.Accent,
        unfocusedBorderColor = PocketShellColors.BorderSoft,
        focusedLabelColor = PocketShellColors.Accent,
        unfocusedLabelColor = PocketShellColors.TextSecondary,
        cursorColor = PocketShellColors.Accent,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "New GitHub issue",
            color = PocketShellColors.Text,
            style = PocketShellType.bodyDense,
            fontWeight = FontWeight.SemiBold,
        )
        OutlinedTextField(
            value = "Voice: trailing words dropped",
            onValueChange = {},
            singleLine = true,
            label = { Text("Title") },
            colors = fieldColors,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = "Steps to reproduce:\n1. Open the composer\n2. Dictate a long note",
            onValueChange = {},
            label = { Text("Body (optional)") },
            colors = fieldColors,
            modifier = Modifier
                .fillMaxWidth()
                .height(132.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = {}) {
                Text(
                    "Cancel",
                    color = PocketShellColors.TextSecondary,
                    style = PocketShellType.bodyDense,
                )
            }
            Button(
                onClick = {},
                colors = ButtonDefaults.buttonColors(
                    containerColor = PocketShellColors.Accent,
                    contentColor = PocketShellColors.Background,
                ),
                modifier = Modifier.weight(1f),
            ) {
                Text("Create issue", style = PocketShellType.bodyDense)
            }
        }
    }
}
