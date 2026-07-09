package com.pocketshell.app.composer

import android.content.ContentResolver
import android.net.Uri
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketshell.app.fileviewer.BoundedImageDecoder
import com.pocketshell.uikit.theme.PocketShellColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Issue #566: compact ChatGPT/Claude-style staged attachment tiles. Each
 * attachment is a fixed square: image selections get a thumbnail when the
 * local preview Uri is still readable; all other files get a typed file tile.
 * The full file name stays in accessibility text while the visible label is
 * constrained to the tile width, so long names cannot stretch the composer.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AttachmentTileGrid(
    attachments: List<PromptComposerViewModel.StagedAttachment>,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .testTag(COMPOSER_ATTACHMENT_CHIPS_TAG),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        attachments.forEach { attachment ->
            AttachmentTile(
                attachment = attachment,
                onRemove = onRemove,
            )
        }
    }
}

@Composable
private fun AttachmentTile(
    attachment: PromptComposerViewModel.StagedAttachment,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    val isImage = attachment.isImageAttachment()
    val context = LocalContext.current
    val thumbnail by produceState<ImageBitmap?>(
        initialValue = null,
        key1 = attachment.previewUri,
        key2 = attachment.mimeType,
    ) {
        val uri = attachment.previewUri
        value = if (isImage && uri != null) {
            withContext(Dispatchers.IO) {
                decodeAttachmentThumbnail(context.contentResolver, uri)
            }
        } else {
            null
        }
    }

    Box(
        modifier = modifier
            .size(ATTACHMENT_TILE_SIZE)
            .clip(shape)
            .background(
                color = PocketShellColors.SurfaceElev,
                shape = shape,
            )
            .border(
                width = 1.dp,
                color = PocketShellColors.BorderSoft,
                shape = shape,
            )
            .semantics {
                contentDescription = "Attachment ${attachment.displayName}"
            }
            .testTag(composerAttachmentChipTestTag(attachment.remotePath)),
    ) {
        if (thumbnail != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(composerAttachmentThumbnailTestTag(attachment.remotePath)),
            ) {
                Image(
                    bitmap = thumbnail!!,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
            AttachmentTypeTile(
                attachment = attachment,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(composerAttachmentTypeTileTestTag(attachment.remotePath)),
            )
        }

        AttachmentTileLabel(
            label = attachment.displayName,
            imageBacked = thumbnail != null,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(),
        )

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(ATTACHMENT_TILE_REMOVE_TOUCH_SIZE)
                .clickable(role = Role.Button) { onRemove(attachment.remotePath) }
                .semantics { contentDescription = "Remove ${attachment.displayName}" }
                .testTag(composerAttachmentRemoveTestTag(attachment.remotePath)),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
                    .size(ATTACHMENT_TILE_REMOVE_SIZE)
                    .background(
                        color = PocketShellColors.Surface,
                        shape = ATTACHMENT_TILE_REMOVE_SHAPE,
                    )
                    .border(
                        width = 1.dp,
                        color = PocketShellColors.BorderSoft,
                        shape = ATTACHMENT_TILE_REMOVE_SHAPE,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "×",
                    color = PocketShellColors.Text,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun AttachmentTypeTile(
    attachment: PromptComposerViewModel.StagedAttachment,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.padding(start = 6.dp, end = 6.dp, top = 8.dp, bottom = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = attachment.fileExtensionLabel(),
            color = PocketShellColors.Accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AttachmentTileLabel(
    label: String,
    imageBacked: Boolean,
    modifier: Modifier = Modifier,
) {
    val background = if (imageBacked) {
        PocketShellColors.TermBg.copy(alpha = 0.72f)
    } else {
        Color.Transparent
    }
    Box(
        modifier = modifier
            .background(background)
            .padding(start = 5.dp, end = 5.dp, bottom = 4.dp, top = 2.dp),
    ) {
        Text(
            text = label,
            color = if (imageBacked) PocketShellColors.TermText else PocketShellColors.TextSecondary,
            fontSize = ATTACHMENT_TILE_LABEL_FONT_SIZE,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun decodeAttachmentThumbnail(
    resolver: ContentResolver,
    uri: Uri,
): ImageBitmap? {
    return runCatching {
        BoundedImageDecoder.decodeStream(
            openInputStream = {
                if (uri.scheme == "file") {
                    uri.path?.let { File(it).inputStream() }
                } else {
                    resolver.openInputStream(uri)
                }
            },
            maxPixels = ATTACHMENT_THUMBNAIL_MAX_PIXELS,
        )?.asImageBitmap()
    }.getOrNull()
}

private fun PromptComposerViewModel.StagedAttachment.isImageAttachment(): Boolean {
    if (mimeType?.startsWith("image/") == true) return true
    return displayName.substringAfterLast('.', missingDelimiterValue = "")
        .lowercase(Locale.ROOT) in IMAGE_ATTACHMENT_EXTENSIONS
}

private fun PromptComposerViewModel.StagedAttachment.fileExtensionLabel(): String {
    val extension = displayName.substringAfterLast('.', missingDelimiterValue = "")
        .takeIf { it.isNotBlank() }
        ?.uppercase(Locale.ROOT)
        ?.take(5)
    return extension ?: "FILE"
}

private val IMAGE_ATTACHMENT_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif")
private const val ATTACHMENT_THUMBNAIL_MAX_PIXELS = 192 * 192
private val ATTACHMENT_TILE_SIZE = 64.dp
private val ATTACHMENT_TILE_REMOVE_TOUCH_SIZE = 48.dp
private val ATTACHMENT_TILE_REMOVE_SIZE = 22.dp
// Genuine sub-ladder component geometry: this tiny overlay button is half of
// the 22dp visual remove control, not a container radius rung.
private val ATTACHMENT_TILE_REMOVE_RADIUS = 11.dp
private val ATTACHMENT_TILE_REMOVE_SHAPE = RoundedCornerShape(ATTACHMENT_TILE_REMOVE_RADIUS)
// The tile caption fits inside a 64dp square; the normal 11sp label rung clips
// common attachment names, so the local micro-label size stays named here.
private val ATTACHMENT_TILE_LABEL_FONT_SIZE = 9.sp

/**
 * Historic tag for the staged-attachment tile grid (FlowRow) at the bottom of
 * the composer. Present only while at least one attachment is staged.
 */
internal const val COMPOSER_ATTACHMENT_CHIPS_TAG = "prompt-composer-attachment-chips"

/** Historic per-tile tag, keyed by the attachment's remote path. */
internal fun composerAttachmentChipTestTag(remotePath: String): String =
    "prompt-composer-attachment-chip:$remotePath"

/** Historic per-tile remove button tag, keyed by remote path. */
internal fun composerAttachmentRemoveTestTag(remotePath: String): String =
    "prompt-composer-attachment-remove:$remotePath"

/** Per-image tile thumbnail tag, keyed by remote path. */
internal fun composerAttachmentThumbnailTestTag(remotePath: String): String =
    "prompt-composer-attachment-thumbnail:$remotePath"

/** Per non-image typed tile tag, keyed by remote path. */
internal fun composerAttachmentTypeTileTestTag(remotePath: String): String =
    "prompt-composer-attachment-type-tile:$remotePath"
