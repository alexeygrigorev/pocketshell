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
import com.pocketshell.uikit.icons.PocketShellIcons
import com.pocketshell.uikit.theme.PocketShellColors
import java.util.Locale

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
 * Quiet-kit folder, image, code and file vectors come from
 * [PocketShellIcons]. Symlink and archive remain local because the Quiet kit
 * does not define those shapes. Folders are accent-tinted (the primary
 * navigational affordance); files/links/archives are muted so they read as
 * content, not chrome.
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

/**
 * The coarse 6-bucket icon class for a filesystem entry derived from its
 * **name** alone — issue #762, slice C.
 *
 * This is the shared, one-source-of-truth extension → [FileIconClass] map so the
 * file explorer's rows and the file viewer's header lead with the **same** glyph
 * for the same file (design-consistency principle). Folders / symlinks come off
 * the caller's known type; this name-based map covers regular files: a final
 * extension routes to image / code-or-text / archive, and everything else is a
 * generic [FileIconClass.BINARY].
 *
 * A dotfile (`.bashrc`), a no-extension name (`Makefile`), or a trailing-dot
 * name has no extension and falls through to [FileIconClass.BINARY] unless the
 * caller knows better (e.g. the viewer's content sniff — see the file-viewer
 * overload that maps a confirmed render type).
 */
fun fileIconClassForName(name: String): FileIconClass = when (extensionOf(name)) {
    in IMAGE_EXTENSIONS -> FileIconClass.IMAGE
    in CODE_EXTENSIONS -> FileIconClass.CODE
    in ARCHIVE_EXTENSIONS -> FileIconClass.ARCHIVE
    else -> FileIconClass.BINARY
}

/** Lower-cased final extension of a basename, or "" when there is none. */
private fun extensionOf(name: String): String {
    val base = name.substringAfterLast('/').substringAfterLast('\\')
    val dot = base.lastIndexOf('.')
    // No dot, leading-dot dotfile (".bashrc"), or trailing dot → no extension.
    if (dot <= 0 || dot == base.lastIndex) return ""
    return base.substring(dot + 1).lowercase(Locale.US)
}

private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "bmp", "ico")

private val CODE_EXTENSIONS = setOf(
    "kt", "java", "js", "ts", "tsx", "jsx", "py", "sh", "bash", "go", "rs",
    "c", "cpp", "cc", "h", "hpp", "json", "yaml", "yml", "toml", "xml", "html",
    "css", "scss", "md", "txt", "log", "patch", "diff", "rb", "php", "sql",
    "gradle", "kts", "properties", "ini", "cfg", "conf",
)

private val ARCHIVE_EXTENSIONS = setOf("zip", "tar", "gz", "tgz", "bz2", "xz", "7z", "rar", "jar")

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
    FileIconClass.FOLDER -> PocketShellIcons.Folder
    FileIconClass.SYMLINK -> SymlinkGlyph
    FileIconClass.IMAGE -> PocketShellIcons.Image
    FileIconClass.CODE -> PocketShellIcons.Code
    FileIconClass.ARCHIVE -> ArchiveGlyph
    FileIconClass.BINARY -> PocketShellIcons.File
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
