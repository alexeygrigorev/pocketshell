package com.pocketshell.app.conversation

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketshell.core.agents.ConversationImage
import com.pocketshell.uikit.components.LoadingIndicator
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellShapes

/**
 * Issue #842: load image bytes referenced by a [ConversationImage] for inline
 * display in the Conversation transcript.
 *
 * The production implementation fetches a host-`path` image over the app-wide
 * WARM SSH lease — the SAME transport the file viewer / session / tmux screens
 * hold (D21, no new connection) — and an `http(s)` URL or inline base64 directly.
 * It is injected as a [CompositionLocal] at the screen level (where the host
 * credentials are available) so the deeply-nested conversation rows don't each
 * need a lease handle. Screenshot/preview/test callers get the
 * [NoConversationImageLoader] default, which always fails → the path-text
 * fallback renders, never a crash.
 */
fun interface ConversationImageLoader {
    /**
     * Resolve the image's bytes. Returns a failed [Result] when the image can't
     * be fetched (host unreachable, missing file, no loader wired) so the
     * caller renders the path-text fallback instead of a broken image.
     */
    suspend fun load(image: ConversationImage): Result<ByteArray>
}

/**
 * Default no-op loader: always fails so the conversation row falls back to the
 * path/url text. Used by screenshot renders and any caller that hasn't provided
 * a host-backed loader.
 */
object NoConversationImageLoader : ConversationImageLoader {
    override suspend fun load(image: ConversationImage): Result<ByteArray> =
        Result.failure(IllegalStateException("No image loader wired"))
}

/**
 * Screen-provided image loader (issue #842). Defaults to [NoConversationImageLoader].
 */
val LocalConversationImageLoader: ProvidableCompositionLocal<ConversationImageLoader> =
    staticCompositionLocalOf { NoConversationImageLoader }

/**
 * Render every image carried by a conversation event (a [ConversationImage]
 * list) inline. No-op when the list is empty.
 */
@Composable
internal fun ConversationImages(
    images: List<ConversationImage>,
    modifier: Modifier = Modifier,
) {
    if (images.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth()) {
        images.forEachIndexed { index, image ->
            ConversationImageContent(
                image = image,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = if (index == 0) ConversationImageTopGap else ConversationImageBetweenGap),
            )
        }
    }
}

private sealed interface ImageLoadState {
    data object Loading : ImageLoadState
    data class Loaded(val bytes: ByteArray) : ImageLoadState
    data object Failed : ImageLoadState
}

/**
 * Inline render of one [ConversationImage]:
 *  - a loading placeholder (spinner + reference text) while bytes are fetched;
 *  - the decoded bitmap (bounded sample) once loaded; and
 *  - a graceful path-text fallback (the reference + an "image unavailable"
 *    note) if the fetch or decode fails — the reference is NEVER dropped.
 */
@Composable
internal fun ConversationImageContent(
    image: ConversationImage,
    modifier: Modifier = Modifier,
) {
    val loader = LocalConversationImageLoader.current
    val reference = remember(image) { image.displayReference }

    val state by produceState<ImageLoadState>(initialValue = ImageLoadState.Loading, image, loader) {
        value = ImageLoadState.Loading
        val bytes = loader.load(image).getOrNull()
        value = if (bytes != null && bytes.isNotEmpty()) ImageLoadState.Loaded(bytes) else ImageLoadState.Failed
    }

    when (val s = state) {
        ImageLoadState.Loading -> ImagePlaceholder(reference = reference, modifier = modifier)
        is ImageLoadState.Loaded -> ImageOrFallback(bytes = s.bytes, reference = reference, modifier = modifier)
        ImageLoadState.Failed -> ImageFallback(reference = reference, modifier = modifier)
    }
}

@Composable
private fun ImageOrFallback(
    bytes: ByteArray,
    reference: String,
    modifier: Modifier,
) {
    val bitmap = remember(bytes) {
        runCatching {
            withDecodeOptions(bytes)
        }.getOrNull()
    }
    if (bitmap == null) {
        ImageFallback(reference = reference, modifier = modifier)
        return
    }
    var showFull by remember { mutableStateOf(false) }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "Image: $reference",
        contentScale = ContentScale.Fit,
        alignment = Alignment.TopStart,
        modifier = modifier
            .clickable { showFull = !showFull }
            .let { if (showFull) it else it.heightIn(max = ConversationImageMaxHeight) }
            .border(1.dp, PocketShellColors.BorderSoft, PocketShellShapes.small)
            .testTag(CONVERSATION_IMAGE_TAG),
    )
}

/** Decode bounded so a large host screenshot never decodes at full resolution. */
private fun withDecodeOptions(bytes: ByteArray): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val w = bounds.outWidth
    val h = bounds.outHeight
    if (w <= 0 || h <= 0) return null
    var sample = 1
    val maxPixels = 2_000_000
    while ((w / sample) * (h / sample) > maxPixels) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
}

@Composable
private fun ImagePlaceholder(reference: String, modifier: Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(PocketShellColors.TermBg, PocketShellShapes.small)
            .border(1.dp, PocketShellColors.BorderSoft, PocketShellShapes.small)
            .padding(horizontal = 10.dp, vertical = 10.dp)
            .testTag(CONVERSATION_IMAGE_PLACEHOLDER_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LoadingIndicator.Spinner(size = com.pocketshell.uikit.components.SpinnerSize.Small)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "Loading image — $reference",
            color = PocketShellColors.TextMuted,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ImageFallback(reference: String, modifier: Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(PocketShellColors.TermBg, PocketShellShapes.small)
            .border(1.dp, PocketShellColors.BorderSoft, PocketShellShapes.small)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag(CONVERSATION_IMAGE_FALLBACK_TAG),
    ) {
        Text(
            text = "🖼 $reference\n(image unavailable)",
            color = PocketShellColors.TextMuted,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

internal const val CONVERSATION_IMAGE_TAG: String = "conversation-image"
internal const val CONVERSATION_IMAGE_PLACEHOLDER_TAG: String = "conversation-image-placeholder"
internal const val CONVERSATION_IMAGE_FALLBACK_TAG: String = "conversation-image-fallback"

private val ConversationImageMaxHeight = 320.dp
private val ConversationImageTopGap = 8.dp
private val ConversationImageBetweenGap = 6.dp
