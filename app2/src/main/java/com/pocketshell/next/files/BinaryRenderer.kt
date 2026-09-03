package com.pocketshell.next.files

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType

/** Test tags for the binary fallback. */
const val VIEWER_BINARY_TAG: String = "viewer-binary"
const val VIEWER_BINARY_NOTE_TAG: String = "viewer-binary-note"

/**
 * The fallback for a file that is neither text nor a decodable image (rewrite
 * task P-3b).
 *
 * A bounded hex dump rather than a bare "can't preview this" card, because the
 * first 4 KiB of an unknown file is usually enough to answer the question the
 * user actually had — is this a gzip, a sqlite database, an ELF binary, a
 * truncated download? The old client showed only the refusal message.
 *
 * [HEX_DUMP_BYTES] is the whole render budget: 256 rows of monospace, which
 * scrolls in a fraction of a frame. Dumping a 12 MiB blob at 16 bytes per row
 * would be 786,000 rows and would hang the composition, so the tail is stated
 * rather than drawn.
 */
@Composable
internal fun BinaryContent(bytes: ByteArray, modifier: Modifier = Modifier) {
    val dump = remember(bytes) { hexDump(bytes, HEX_DUMP_BYTES) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PocketShellColors.TermBg)
            .verticalScroll(rememberScrollState())
            .padding(PocketShellSpacing.md)
            .testTag(VIEWER_BINARY_TAG),
    ) {
        Text(
            text = binaryNote(bytes.size.toLong(), HEX_DUMP_BYTES),
            color = PocketShellColors.TextSecondary,
            style = PocketShellType.bodyDense,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = PocketShellSpacing.sm)
                .testTag(VIEWER_BINARY_NOTE_TAG),
        )
        Text(
            text = dump,
            color = PocketShellColors.TermText,
            style = PocketShellType.labelMono,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** How many bytes the dump shows. */
internal const val HEX_DUMP_BYTES: Int = 4096

/** Bytes per dump row — the classic `xxd` width. */
private const val ROW_BYTES = 16

internal fun binaryNote(sizeBytes: Long, shown: Int): String = when {
    sizeBytes <= shown -> "Not text or an image — showing all ${formatSize(sizeBytes)} as hex."
    else -> "Not text or an image — showing the first ${formatSize(shown.toLong())} " +
        "of ${formatSize(sizeBytes)} as hex."
}

/**
 * Classic `offset  hex  ascii` dump of the first [limit] bytes of [bytes].
 *
 * Non-printable bytes render as `.` in the ASCII column, the way `hexdump -C`
 * does — the column exists to make embedded strings (a magic number, a path, a
 * version banner) pop out, and control characters would destroy the alignment
 * that makes it scannable.
 */
internal fun hexDump(bytes: ByteArray, limit: Int): String {
    val end = minOf(bytes.size, limit)
    val out = StringBuilder()
    var offset = 0
    while (offset < end) {
        val rowEnd = minOf(offset + ROW_BYTES, end)
        out.append("%08x  ".format(offset))
        for (index in offset until offset + ROW_BYTES) {
            if (index < rowEnd) out.append("%02x ".format(bytes[index])) else out.append("   ")
            if (index - offset == ROW_BYTES / 2 - 1) out.append(' ')
        }
        out.append(" |")
        for (index in offset until rowEnd) {
            val byte = bytes[index].toInt() and 0xFF
            out.append(if (byte in 0x20..0x7E) byte.toChar() else '.')
        }
        out.append("|\n")
        offset = rowEnd
    }
    return out.toString().trimEnd('\n')
}
