package com.pocketshell.uikit.render

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketshell.uikit.components.FileIconClass
import com.pocketshell.uikit.components.FileTypeIcon
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.NavigationChevron
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.components.SectionHeader
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellType

@Composable
internal fun FileExplorerScreenRender() {
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        ScreenHeader(
            title = "Files",
            subtitle = "hetzner",
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FileExplorerHeaderText("Sort")
                    Spacer(modifier = Modifier.width(12.dp))
                    FileExplorerHeaderText("Upload")
                    Spacer(modifier = Modifier.width(12.dp))
                    FileExplorerHeaderText("Go to…")
                }
            },
        )
        SectionHeader(label = "Entries", count = 6)
        // Parent folder.
        ListRow(
            title = "..",
            subtitle = "Parent folder",
            leading = { FileTypeIcon(iconClass = FileIconClass.FOLDER) },
            trailing = { FileExplorerChevron() },
            onClick = {},
        )
        // Folder with a modified date.
        ListRow(
            title = "agents-pool-untracked",
            subtitle = "Jun 12",
            leading = { FileTypeIcon(iconClass = FileIconClass.FOLDER) },
            trailing = { FileExplorerChevron() },
            onClick = {},
        )
        // Symlink.
        ListRow(
            title = "current-link",
            subtitle = "Jun 10",
            leading = { FileTypeIcon(iconClass = FileIconClass.SYMLINK) },
            trailing = { FileExplorerChevron() },
            onClick = {},
        )
        // Code/text file with size · modified.
        ListRow(
            title = "issue-103-starter.patch",
            subtitle = "8.6 KB · Jun 12",
            leading = { FileTypeIcon(iconClass = FileIconClass.CODE) },
            trailing = { FileExplorerDownload() },
            onClick = {},
        )
        // Image file.
        ListRow(
            title = "screenshot-2026-06-14.png",
            subtitle = "1.2 MB · Jun 14",
            leading = { FileTypeIcon(iconClass = FileIconClass.IMAGE) },
            trailing = { FileExplorerDownload() },
            onClick = {},
        )
        // Archive.
        ListRow(
            title = "release-bundle.tar.gz",
            subtitle = "44.0 MB · Jan 2025",
            leading = { FileTypeIcon(iconClass = FileIconClass.ARCHIVE) },
            trailing = { FileExplorerDownload() },
            onClick = {},
        )
        // A very long binary file name to show clean ellipsis above meta.
        ListRow(
            title = "a-deliberately-very-long-binary-artifact-filename-that-must-ellipsise.bin",
            subtitle = "256.0 MB · Mar 2024",
            leading = { FileTypeIcon(iconClass = FileIconClass.BINARY) },
            trailing = { FileExplorerDownload() },
            onClick = {},
        )
    }
}

@Composable
internal fun FileViewerHeaderTypeIconRender() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FileViewerHeaderRow("MainActivity.kt", "~/app/MainActivity.kt", FileIconClass.CODE)
        FileViewerHeaderRow("screenshot.png", "~/shots/screenshot.png", FileIconClass.IMAGE)
        FileViewerHeaderRow("release.tar.gz", "~/dist/release.tar.gz", FileIconClass.ARCHIVE)
        FileViewerHeaderRow("core.dump", "~/tmp/core.dump", FileIconClass.BINARY)
    }
}

/** Mirror of the explorer header's compact accent text action. */
@Composable
private fun FileExplorerHeaderText(label: String) {
    Text(
        text = label,
        color = PocketShellColors.Accent,
        style = PocketShellType.bodyDense,
        fontWeight = FontWeight.Medium,
    )
}

/** Mirror of the explorer's trailing navigational chevron (folder / link). */
@Composable
private fun FileExplorerChevron() {
    Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        NavigationChevron(tint = PocketShellColors.TextMuted)
    }
}

/** Mirror of the explorer's trailing per-file download action. */
@Composable
private fun FileExplorerDownload() {
    Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Text(
            text = "↓",
            color = PocketShellColors.Accent,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Mirror of the file viewer's `ScreenHeader` leading slot (back + type icon). */
@Composable
private fun FileViewerHeaderRow(name: String, path: String, iconClass: FileIconClass) {
    ScreenHeader(
        title = name,
        subtitle = path,
        leading = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = "‹",
                        color = PocketShellColors.TextSecondary,
                        fontSize = 22.sp,
                    )
                }
                FileTypeIcon(iconClass = iconClass)
            }
        },
        trailing = {
            FileExplorerHeaderText("Save")
            Spacer(modifier = Modifier.width(12.dp))
            FileExplorerHeaderText("Share")
        },
    )
}

/**
 * Issue #1715: the production tab strip lives in the app module, so this
 * mirrors it with ui-kit primitives — 48dp row, unique-suffix labels,
 * accent underline on the active tab, 48dp close hit box.
 */
@Composable
internal fun FileViewerTabStripRender() {
    Column {
        FileViewerHeaderRow("src/App.kt", "/home/u/src/App.kt", FileIconClass.CODE)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(PocketShellColors.Background),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FileViewerTabChip("src/App.kt", FileIconClass.CODE, active = true)
            FileViewerTabChip("test/App.kt", FileIconClass.CODE, active = false)
            FileViewerTabChip("screenshot.png", FileIconClass.IMAGE, active = false)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .background(PocketShellColors.Background)
                .padding(16.dp),
        ) {
            Text(
                text = "fun main() {\n  println(\"tabs\")\n}",
                color = PocketShellColors.Text,
                style = PocketShellType.bodyMono,
            )
        }
    }
}

@Composable
private fun FileViewerTabChip(label: String, iconClass: FileIconClass, active: Boolean) {
    Column(
        modifier = Modifier
            .width(144.dp)
            .height(48.dp),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(modifier = Modifier.width(8.dp))
            FileTypeIcon(iconClass = iconClass, sizeDp = 16)
            Text(
                text = label,
                color = if (active) PocketShellColors.Accent else PocketShellColors.TextSecondary,
                style = PocketShellType.bodyMono,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
            )
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "×", color = PocketShellColors.TextSecondary, style = PocketShellType.bodyDense)
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(if (active) PocketShellColors.Accent else PocketShellColors.Background),
        )
    }
}
