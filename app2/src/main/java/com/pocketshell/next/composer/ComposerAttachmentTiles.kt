package com.pocketshell.next.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketshell.uikit.theme.PocketShellColors
import java.util.Locale

/** Test tags for the staged-attachment strip. */
const val COMPOSER_ATTACHMENTS_TAG: String = "composer-attachments"

fun composerAttachmentTileTag(remotePath: String): String = "composer-attachment:$remotePath"

fun composerAttachmentRemoveTag(remotePath: String): String = "composer-attachment-remove:$remotePath"

/**
 * The staged-attachment tiles above the draft field (rewrite task P-1, ported
 * from the old client's `AttachmentTileGrid`).
 *
 * ## One deliberate trim: typed tiles, never thumbnails
 *
 * The old grid decoded a thumbnail from the picked file's local `Uri`. app2's
 * staged-attachment model has no local `Uri` on purpose — it is persisted
 * across process death, and a content-provider grant is not (see
 * [StagedAttachment]). Keeping a transient copy would mean a tile that shows a
 * thumbnail until the app is killed and a typed tile afterwards, for the same
 * attachment, which is a worse experience than one that always looks the same.
 * So every tile is the typed tile: extension badge, file name, remove control.
 *
 * The tile is 64dp square with a 48dp remove hit area, matching the old
 * geometry, so the strip costs the same room above the keyboard it always did.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AttachmentTiles(
    attachments: List<StagedAttachment>,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .testTag(COMPOSER_ATTACHMENTS_TAG),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        attachments.forEach { attachment -> AttachmentTile(attachment, onRemove) }
    }
}

@Composable
private fun AttachmentTile(attachment: StagedAttachment, onRemove: (String) -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .size(TILE_SIZE)
            .clip(shape)
            .background(color = PocketShellColors.SurfaceElev, shape = shape)
            .border(width = 1.dp, color = PocketShellColors.BorderSoft, shape = shape)
            // The full name stays in accessibility text while the visible label
            // is clipped to the tile, so a long name cannot stretch the strip.
            .semantics { contentDescription = "Attachment ${attachment.displayName}" }
            .testTag(composerAttachmentTileTag(attachment.remotePath)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 6.dp, end = 6.dp, top = 8.dp, bottom = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = extensionLabel(attachment.displayName),
                color = PocketShellColors.Accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Text(
            text = attachment.displayName,
            color = PocketShellColors.TextSecondary,
            fontSize = LABEL_FONT_SIZE,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = 5.dp, end = 5.dp, bottom = 4.dp),
        )

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(REMOVE_TOUCH_SIZE)
                .clickable(role = Role.Button) { onRemove(attachment.remotePath) }
                .semantics { contentDescription = "Remove ${attachment.displayName}" }
                .testTag(composerAttachmentRemoveTag(attachment.remotePath)),
            contentAlignment = Alignment.TopEnd,
        ) {
            Box(
                modifier = Modifier
                    .padding(2.dp)
                    .size(REMOVE_SIZE)
                    .background(color = PocketShellColors.Surface, shape = REMOVE_SHAPE)
                    .border(width = 1.dp, color = PocketShellColors.BorderSoft, shape = REMOVE_SHAPE),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "×", color = PocketShellColors.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** `PNG`, `PDF`, `KT`… or `FILE` when the name carries no extension. */
internal fun extensionLabel(displayName: String): String =
    displayName.substringAfterLast('.', missingDelimiterValue = "")
        .takeIf { it.isNotBlank() && it != displayName }
        ?.uppercase(Locale.ROOT)
        ?.take(5)
        ?: "FILE"

private val TILE_SIZE = 64.dp
private val REMOVE_TOUCH_SIZE = 48.dp
private val REMOVE_SIZE = 22.dp
private val REMOVE_SHAPE = RoundedCornerShape(11.dp)

// The caption sits inside a 64dp square; the 11sp label rung clips common
// attachment names, so this micro-label size stays named here.
private val LABEL_FONT_SIZE = 9.sp
