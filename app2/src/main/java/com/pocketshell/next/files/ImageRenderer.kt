package com.pocketshell.next.files

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import com.pocketshell.uikit.components.EmptyState
import com.pocketshell.uikit.theme.PocketShellColors

/** Test tags for the image surface. */
const val VIEWER_IMAGE_TAG: String = "viewer-image"
const val VIEWER_IMAGE_UNDECODABLE_TAG: String = "viewer-image-undecodable"

/**
 * Renders image bytes under a bounded decode (rewrite task P-3b).
 *
 * The decode is [remember]ed on the byte array so a recomposition — a pinch, a
 * banner appearing, a rotation of the parent — never re-decodes. That matters
 * more than it looks: a decode is the single most expensive thing this screen
 * does, and doing it per frame while pinching is what makes an image viewer
 * feel broken.
 *
 * Undecodable bytes are a normal outcome, not an error: content sniffing routes
 * an exotic or corrupt format here optimistically, so a null decode falls back
 * to a plain message rather than a crash or a blank screen.
 *
 * Pinch-zoom and pan are the whole interaction. Scale is clamped so the image
 * cannot be shrunk to nothing or blown up past a useful magnification, and pan
 * resets with the scale when the user pinches back to fit — otherwise a
 * zoomed-and-dragged image can be left translated off-screen at 1×.
 */
@Composable
internal fun ImageContent(bytes: ByteArray, modifier: Modifier = Modifier) {
    val bitmap = remember(bytes) { BoundedImageDecoder.decode(bytes) }
    if (bitmap == null) {
        EmptyState(
            title = "Can't display this image",
            description = "The bytes are not a format this device can decode.",
            modifier = modifier
                .fillMaxSize()
                .testTag(VIEWER_IMAGE_UNDECODABLE_TAG),
        )
        return
    }

    var scale by remember(bytes) { mutableFloatStateOf(1f) }
    var offsetX by remember(bytes) { mutableFloatStateOf(0f) }
    var offsetY by remember(bytes) { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PocketShellColors.TermBg)
            .pointerInput(bytes) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                    if (scale <= MIN_SCALE) {
                        offsetX = 0f
                        offsetY = 0f
                    } else {
                        offsetX += pan.x
                        offsetY += pan.y
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Image preview",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY,
                )
                .testTag(VIEWER_IMAGE_TAG),
        )
    }
}

private const val MIN_SCALE = 1f
private const val MAX_SCALE = 8f
