package com.pocketshell.uikit.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.theme.PocketShellColors

/**
 * The coarse visual class of a remote filesystem entry — issue #762.
 *
 * Six buckets, deliberately dev-tool-right granularity (a folder, a symlink, an
 * image, code/text, an archive, and a generic binary/other). This is the
 * scannable vocabulary a developer browses by; it intentionally avoids a
 * per-MIME explosion. [FileExplorerScreen][app] maps a [name] + the SFTP
 * `RemoteEntry.Type` onto one of these via `fileIconClass()`.
 */
enum class FileIconClass {
    FOLDER,
    SYMLINK,
    IMAGE,
    CODE,
    ARCHIVE,
    BINARY,
}

/**
 * The leading file-type icon for a file-explorer row — issue #762.
 *
 * Replaces the cramped `DIR`/`FILE` text glyph (the `FILE`-wrap bug) with a
 * proper at-a-glance icon, the universal scan affordance every mobile file
 * manager leads with (M3 Lists). Lives in `:shared:ui-kit` so the explorer and
 * the file viewer (#714) — and any future browser — render the exact same glyphs
 * (design-consistency principle).
 *
 * The glyphs are hand-traced [ImageVector]s rather than pulled from
 * `material-icons-extended` — the ui-kit classpath only carries
 * `material-icons-core`, and the established pattern across the ui-kit is to
 * trace small glyphs inline (see [MicGlyphIcon] / `KebabIcon`). Folders are
 * accent-tinted (the primary navigational affordance); files/links/archives are
 * muted so they read as content, not chrome.
 *
 * [size] defaults to the 20dp leading-icon size; the row keeps every title's
 * left edge aligned because [ListRow] centres the leading slot in a fixed box.
 */
@Composable
fun FileTypeIcon(
    iconClass: FileIconClass,
    modifier: Modifier = Modifier,
    sizeDp: Int = 20,
) {
    val tint = when (iconClass) {
        FileIconClass.FOLDER -> PocketShellColors.Accent
        FileIconClass.SYMLINK -> PocketShellColors.TextSecondary
        else -> PocketShellColors.TextSecondary
    }
    Box(
        modifier = modifier.size(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = iconClass.vector(),
            contentDescription = iconClass.contentDescription(),
            tint = tint,
            modifier = Modifier.size(sizeDp.dp),
        )
    }
}

/** Accessibility label for the icon class so the row reads its type to a11y. */
private fun FileIconClass.contentDescription(): String = when (this) {
    FileIconClass.FOLDER -> "Folder"
    FileIconClass.SYMLINK -> "Symbolic link"
    FileIconClass.IMAGE -> "Image file"
    FileIconClass.CODE -> "Code or text file"
    FileIconClass.ARCHIVE -> "Archive"
    FileIconClass.BINARY -> "File"
}

private fun FileIconClass.vector(): ImageVector = when (this) {
    FileIconClass.FOLDER -> FolderGlyph
    FileIconClass.SYMLINK -> SymlinkGlyph
    FileIconClass.IMAGE -> ImageGlyph
    FileIconClass.CODE -> CodeGlyph
    FileIconClass.ARCHIVE -> ArchiveGlyph
    FileIconClass.BINARY -> FileGlyph
}

private fun glyph(name: String, trace: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        val builder = PathBuilder()
        builder.trace()
        addPath(pathData = builder.nodes, fill = SolidColor(Color.White))
    }.build()

/** A classic Material folder silhouette: a tab + body, filled. */
private val FolderGlyph: ImageVector = glyph("PsFolder") {
    moveTo(4f, 5f)
    lineTo(10f, 5f)
    lineTo(12f, 7f)
    lineTo(20f, 7f)
    arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
    lineTo(22f, 18f)
    arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
    lineTo(4f, 20f)
    arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
    lineTo(2f, 7f)
    arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
    close()
}

/**
 * Generic file (document) sheet with a folded top-right corner — the BINARY /
 * other fallback.
 */
private val FileGlyph: ImageVector = glyph("PsFile") {
    moveTo(6f, 2f)
    lineTo(14f, 2f)
    lineTo(20f, 8f)
    lineTo(20f, 21f)
    arcToRelative(1f, 1f, 0f, false, true, -1f, 1f)
    lineTo(6f, 22f)
    arcToRelative(1f, 1f, 0f, false, true, -1f, -1f)
    lineTo(5f, 3f)
    arcToRelative(1f, 1f, 0f, false, true, 1f, -1f)
    close()
    // Folded corner (cut back so the dog-ear reads).
    moveTo(14f, 3f)
    lineTo(14f, 8f)
    lineTo(19f, 8f)
    close()
}

/**
 * Symlink: a diagonal arrow pointing up-right — the classic "shortcut / link /
 * redirect" affordance. Drawn as its own bold glyph (NOT a document sheet, so it
 * never reads as a plain file): a thick diagonal shaft from the lower-left to
 * the upper-right, capped by a corner arrowhead.
 */
private val SymlinkGlyph: ImageVector = glyph("PsSymlink") {
    // Diagonal shaft (thick), from lower-left (6,18) to upper-right (16,8).
    moveTo(5.6f, 16.6f)
    lineTo(14.6f, 7.6f)
    lineTo(16.4f, 9.4f)
    lineTo(7.4f, 18.4f)
    close()
    // Arrowhead: a right-triangle in the upper-right corner pointing up-right.
    moveTo(10f, 6f)
    lineTo(18f, 6f)
    lineTo(18f, 14f)
    lineTo(15.5f, 11.5f)
    lineTo(12.5f, 14.5f)
    lineTo(9.5f, 11.5f)
    lineTo(12.5f, 8.5f)
    close()
}

/** Image: a framed picture with a sun/mountain motif. */
private val ImageGlyph: ImageVector = glyph("PsImage") {
    // Frame.
    moveTo(4f, 4f)
    lineTo(20f, 4f)
    arcToRelative(1f, 1f, 0f, false, true, 1f, 1f)
    lineTo(21f, 19f)
    arcToRelative(1f, 1f, 0f, false, true, -1f, 1f)
    lineTo(4f, 20f)
    arcToRelative(1f, 1f, 0f, false, true, -1f, -1f)
    lineTo(3f, 5f)
    arcToRelative(1f, 1f, 0f, false, true, 1f, -1f)
    close()
    // Punch out interior so it reads as a frame, not a filled block.
    moveTo(5f, 6f)
    lineTo(5f, 18f)
    lineTo(19f, 18f)
    lineTo(19f, 6f)
    close()
    // Sun.
    moveTo(8f, 9.5f)
    arcToRelative(1.3f, 1.3f, 0f, true, true, 0.01f, 0f)
    close()
    // Mountain.
    moveTo(5.5f, 17f)
    lineTo(10f, 11.5f)
    lineTo(13f, 15f)
    lineTo(15.5f, 12f)
    lineTo(18.5f, 17f)
    close()
}

/** Code/text: angle brackets `< >`. */
private val CodeGlyph: ImageVector = glyph("PsCode") {
    // Left chevron.
    moveTo(8.5f, 6f)
    lineTo(10f, 7.3f)
    lineTo(5.8f, 12f)
    lineTo(10f, 16.7f)
    lineTo(8.5f, 18f)
    lineTo(3f, 12f)
    close()
    // Right chevron.
    moveTo(15.5f, 6f)
    lineTo(21f, 12f)
    lineTo(15.5f, 18f)
    lineTo(14f, 16.7f)
    lineTo(18.2f, 12f)
    lineTo(14f, 7.3f)
    close()
}

/** Archive: a box with a lid seam and a small latch. */
private val ArchiveGlyph: ImageVector = glyph("PsArchive") {
    // Lid.
    moveTo(3f, 4f)
    lineTo(21f, 4f)
    lineTo(21f, 8f)
    lineTo(3f, 8f)
    close()
    // Body.
    moveTo(4f, 9f)
    lineTo(20f, 9f)
    lineTo(20f, 20f)
    arcToRelative(1f, 1f, 0f, false, true, -1f, 1f)
    lineTo(5f, 21f)
    arcToRelative(1f, 1f, 0f, false, true, -1f, -1f)
    close()
    // Latch slot (punched out, centred).
    moveTo(10.5f, 11f)
    lineTo(13.5f, 11f)
    lineTo(13.5f, 14f)
    lineTo(10.5f, 14f)
    close()
}
