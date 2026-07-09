package com.pocketshell.uikit.render

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketshell.uikit.components.DisclosureIcon
import com.pocketshell.uikit.components.SectionHeader
import com.pocketshell.uikit.components.SegmentedToggle
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellType

@Composable
internal fun ConversationTimelineRender() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        // USER message block
        ConversationChatBlock(
            roleLabel = "USER",
            roleColor = PocketShellColors.Accent,
            timeLabel = "· 4m ago",
            bodyContent = {
                ConversationChatBody(
                    text = "check the deploy log and tell me what failed in the last run",
                )
            },
        )

        // Spacer between message blocks (22dp per mockup .msg { margin-bottom })
        Spacer(modifier = Modifier.height(22.dp))

        // ASSISTANT message block with inline tool call card
        ConversationChatBlock(
            roleLabel = "ASSISTANT",
            roleColor = PocketShellColors.Purple,
            timeLabel = "· 3m ago",
            bodyContent = {
                Text(
                    text = "I'll check the deploy logs.",
                    color = PocketShellColors.Text,
                    fontSize = 14.sp,
                    lineHeight = (14.sp * 1.55f),
                    fontFamily = FontFamily.SansSerif,
                )
                Spacer(modifier = Modifier.height(10.dp))
                ConversationToolCallCard(
                    toolName = "Bash",
                    command = "kubectl logs -n prod deploy-7d9",
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "The deploy failed because the database migration timed out at step 4. The query ALTER TABLE users ADD COLUMN... took longer than the 30s limit.",
                    color = PocketShellColors.Text,
                    fontSize = 14.sp,
                    lineHeight = (14.sp * 1.55f),
                    fontFamily = FontFamily.SansSerif,
                )
            },
        )

        Spacer(modifier = Modifier.height(22.dp))

        // Second USER message
        ConversationChatBlock(
            roleLabel = "USER",
            roleColor = PocketShellColors.Accent,
            timeLabel = "· 1m ago",
            bodyContent = {
                ConversationChatBody(
                    text = "show me the migration",
                )
            },
        )

        Spacer(modifier = Modifier.height(22.dp))

        // Second ASSISTANT with streaming indicator
        ConversationChatBlock(
            roleLabel = "ASSISTANT",
            roleColor = PocketShellColors.Purple,
            timeLabel = "· streaming",
            bodyContent = {
                Text(
                    text = "Here's the migration that timed out:",
                    color = PocketShellColors.Text,
                    fontSize = 14.sp,
                    lineHeight = (14.sp * 1.55f),
                    fontFamily = FontFamily.SansSerif,
                )
                Spacer(modifier = Modifier.height(10.dp))
                ConversationToolCallCard(
                    toolName = "Read",
                    command = "migrations/0042_add_users.sql",
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "The migration adds three new columns to a 50M-row users table without batching",
                    color = PocketShellColors.Text,
                    fontSize = 14.sp,
                    lineHeight = (14.sp * 1.55f),
                    fontFamily = FontFamily.SansSerif,
                )
                Text(
                    text = "▌",
                    color = PocketShellColors.Purple,
                    fontSize = 14.sp,
                )
            },
        )
    }
}

@Composable
internal fun SessionTerminalConversationTabPillRender() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(label = "Terminal selected")
        SegmentedToggle(
            labels = listOf("Terminal", "Conversation"),
            selectedIndex = 0,
            onSelected = {},
            fillSegments = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )
        SectionHeader(label = "Conversation selected (now reachable)")
        SegmentedToggle(
            labels = listOf("Terminal", "Conversation"),
            selectedIndex = 1,
            onSelected = {},
            fillSegments = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )
    }
}

@Composable
internal fun ConversationMarkdownTableRender() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "Here are the open issues:",
            color = PocketShellColors.Text,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
        )
        MarkdownTableMirror(
            header = listOf("#", "Issue", "Align"),
            alignments = listOf(TextAlign.End, TextAlign.Start, TextAlign.Center),
            rows = listOf(
                listOf("1", "Render Markdown tables in the conversation pane", "ui"),
                listOf("2", "Voice composer", "feature"),
                listOf("10", "Reconnect grace window", "bug"),
            ),
        )
        Text(
            text = "Long cells wrap inside the column; a wide table scrolls horizontally as one unit.",
            color = PocketShellColors.TextSecondary,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
internal fun ConversationToolRowsCompactRender() {
    Column(modifier = Modifier.padding(horizontal = 18.dp)) {
        CompactToolRow("Bash", "cd /home/alexey/git/pocketshell ec…", expanded = false)
        CompactToolRow("Read", "20260611-174904-01-Screenshot_2026…", expanded = false)
        CompactToolRow("Agent", "implementer: Restore #690 wiring l…", expanded = false)
        CompactToolRow(
            tool = "Read",
            summary = "shot.png",
            expanded = true,
            input = "{\n  \"file_path\": \"/home/alexey/.pocketshell/attachments/host/shot.png\"\n}",
            output = "[image image/png · 39124 chars]",
        )
    }
}

@Composable
internal fun DisclosureIconRender() {
    Column(modifier = Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Bare DisclosureIcon", color = PocketShellColors.TextSecondary, style = PocketShellType.bodyDense)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                DisclosureIcon(expanded = false)
                Text("collapsed", color = PocketShellColors.TextMuted, fontSize = 11.sp)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                DisclosureIcon(expanded = true)
                Text("expanded", color = PocketShellColors.TextMuted, fontSize = 11.sp)
            }
        }

        Text("Surface 1 — conversation tool row", color = PocketShellColors.TextSecondary, style = PocketShellType.bodyDense)
        CompactToolRow("Bash", "cd /home/alexey/git/pocketshell ec…", expanded = false)
        CompactToolRow("Read", "shot.png", expanded = true, input = "{ }", output = "ok")

        Text("Surface 2 — composer pending queue", color = PocketShellColors.TextSecondary, style = PocketShellType.bodyDense)
        ComposerQueueToggleMirror(expanded = false)
        ComposerQueueToggleMirror(expanded = true)
    }
}

@Composable
internal fun DisclosureIconSlice2Render() {
    Column(modifier = Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Surface 3 — folder / session tree row", color = PocketShellColors.TextSecondary, style = PocketShellType.bodyDense)
        FolderTreeRowMirror(name = "cable-world · 3 agents", expanded = false)
        FolderTreeRowMirror(name = "cable-world · 3 agents", expanded = true)

        Text("Surface 4 — conversation system-note row", color = PocketShellColors.TextSecondary, style = PocketShellType.bodyDense)
        SystemNoteRowMirror(actor = "SYSTEM", preview = "context compacted · 12k tokens", expanded = false)
        SystemNoteRowMirror(actor = "SYSTEM", preview = "context compacted · 12k tokens", expanded = true)
    }
}

/**
 * Chat-style message block: role header + body content.
 * Mirrors the .msg / .msg-head / .msg-body structure from conversation.html.
 */
@Composable
private fun ConversationChatBlock(
    roleLabel: String,
    roleColor: Color,
    timeLabel: String,
    bodyContent: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // .msg-head: role label + time
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = roleLabel,
                color = roleColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.SansSerif,
                letterSpacing = 0.8.sp,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = timeLabel,
                color = PocketShellColors.TextMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
            )
        }
        // .msg-body
        bodyContent()
    }
}

/** Simple body text block matching .msg-body from the mockup. */
@Composable
private fun ConversationChatBody(
    text: String,
) {
    Text(
        text = text,
        color = PocketShellColors.Text,
        fontSize = 14.sp,
        lineHeight = (14.sp * 1.55f),
        fontFamily = FontFamily.SansSerif,
    )
}

/** Inline tool call card matching .tool-call from the mockup. */
@Composable
private fun ConversationToolCallCard(
    toolName: String,
    command: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = PocketShellColors.Surface,
                shape = RoundedCornerShape(10.dp),
            )
            .border(
                width = 1.dp,
                color = PocketShellColors.BorderSoft,
                shape = RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "›",
            color = PocketShellColors.TextMuted,
            fontSize = 14.sp,
        )
        Text(
            text = toolName,
            color = PocketShellColors.Accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = command,
            color = PocketShellColors.TextSecondary,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// Issue #840 slice 2: mirrors the folder-tree header row (FolderListScreen)
// — the shared DisclosureIcon (TextSecondary, 16dp) leads the status dot +
// name, replacing the deleted private filled-triangle indicator. Matches the
// #478 tree aesthetic: ▶ collapsed / ▼ expanded, one shape rotated.
@Composable
private fun FolderTreeRowMirror(name: String, expanded: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DisclosureIcon(expanded = expanded, tint = PocketShellColors.TextSecondary, size = 16.dp)
        Spacer(modifier = Modifier.width(5.dp))
        Box(modifier = Modifier.size(8.dp).background(PocketShellColors.Green, CircleShape))
        Spacer(modifier = Modifier.width(6.dp))
        Text(name, color = PocketShellColors.Text, style = PocketShellType.bodyDense, fontWeight = FontWeight.SemiBold)
    }
}

// Issue #840 slice 2: mirrors the conversation system-note row
// (ConversationSystemNoteRow) — it gains a LEADING shared DisclosureIcon
// (TextMuted) it previously lacked entirely, so the (clickable) row carries
// the same expand/collapse affordance as every other disclosure surface.
@Composable
private fun SystemNoteRowMirror(actor: String, preview: String, expanded: Boolean) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DisclosureIcon(expanded = expanded, tint = PocketShellColors.TextMuted)
            Text(actor, color = PocketShellColors.TextMuted, style = PocketShellType.labelMono, fontWeight = FontWeight.Bold)
        }
        Text(
            if (expanded) "context compacted · 12k tokens → 3k tokens (full body shown when expanded)" else preview,
            color = PocketShellColors.TextMuted,
            style = PocketShellType.bodyDense,
            maxLines = if (expanded) 3 else 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// Issue #840: mirrors the composer pending-transcription queue toggle row
// (PromptComposerSheet) using the same shared DisclosureIcon with the accent
// tint, so the render proves the second surface uses the identical icon.
@Composable
private fun ComposerQueueToggleMirror(expanded: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PocketShellColors.Accent.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "2 transcriptions pending",
            color = PocketShellColors.Accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        DisclosureIcon(expanded = expanded, tint = PocketShellColors.Accent)
    }
}

@Composable
private fun CompactToolRow(
    tool: String,
    summary: String,
    expanded: Boolean,
    input: String? = null,
    output: String? = null,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(PocketShellColors.Surface, RoundedCornerShape(8.dp))
                .border(1.dp, PocketShellColors.BorderSoft, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DisclosureIcon(expanded = expanded, tint = PocketShellColors.TextMuted)
            Text(tool, color = PocketShellColors.Accent, style = PocketShellType.bodyDense, fontWeight = FontWeight.SemiBold)
            Text(
                summary,
                color = PocketShellColors.TextSecondary,
                style = PocketShellType.labelMono,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text("✓", color = PocketShellColors.Green, style = PocketShellType.labelMono)
        }
        if (expanded) {
            Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                input?.let { CompactSection("input", it) }
                output?.let { CompactSection("output", it) }
            }
        }
    }
}

@Composable
private fun CompactSection(label: String, body: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, color = PocketShellColors.TextMuted, fontSize = 10.sp, modifier = Modifier.padding(bottom = 2.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(PocketShellColors.TermBg, RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Text(body, color = PocketShellColors.TermText, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

/**
 * Visual mirror of the real `MarkdownText.TableBlock` (#781). Same tokens:
 * Surface fill + Border outline on the whole table, a SurfaceElev bold
 * header row, BorderSoft hairline cell separators, per-column alignment, and
 * a fixed per-column max width so the table scrolls horizontally as a unit.
 */
@Composable
private fun MarkdownTableMirror(
    header: List<String>,
    alignments: List<TextAlign>,
    rows: List<List<String>>,
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(PocketShellColors.Surface)
            .border(1.dp, PocketShellColors.Border, RoundedCornerShape(6.dp)),
    ) {
        MarkdownTableMirrorRow(header, alignments, isHeader = true)
        rows.forEach { MarkdownTableMirrorRow(it, alignments, isHeader = false) }
    }
}

@Composable
private fun MarkdownTableMirrorRow(
    cells: List<String>,
    alignments: List<TextAlign>,
    isHeader: Boolean,
) {
    Row(
        modifier = Modifier
            .background(if (isHeader) PocketShellColors.SurfaceElev else Color.Transparent)
            .border(0.5.dp, PocketShellColors.BorderSoft),
    ) {
        alignments.forEachIndexed { index, align ->
            Box(
                modifier = Modifier
                    .width(110.dp)
                    .border(0.5.dp, PocketShellColors.BorderSoft)
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            ) {
                Text(
                    text = cells.getOrElse(index) { "" },
                    color = if (isHeader) PocketShellColors.Text else PocketShellColors.TextSecondary,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (isHeader) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = align,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
