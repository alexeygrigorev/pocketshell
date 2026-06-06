package com.pocketshell.app.fileviewer

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

const val FILE_VIEWER_SCREEN_TAG = "fileViewerScreen"
const val FILE_VIEWER_BACK_TAG = "fileViewerBack"
const val FILE_VIEWER_TITLE_TAG = "fileViewerTitle"
const val FILE_VIEWER_LOADING_TAG = "fileViewerLoading"
const val FILE_VIEWER_IMAGE_TAG = "fileViewerImage"
const val FILE_VIEWER_TEXT_TAG = "fileViewerText"
const val FILE_VIEWER_PDF_TAG = "fileViewerPdf"
const val FILE_VIEWER_PDF_PAGE_TAG = "fileViewerPdfPage"
const val FILE_VIEWER_PDF_PREV_TAG = "fileViewerPdfPrev"
const val FILE_VIEWER_PDF_NEXT_TAG = "fileViewerPdfNext"
const val FILE_VIEWER_PDF_PAGE_LABEL_TAG = "fileViewerPdfPageLabel"
const val FILE_VIEWER_AUDIO_TAG = "fileViewerAudio"
const val FILE_VIEWER_AUDIO_PLAY_PAUSE_TAG = "fileViewerAudioPlayPause"
const val FILE_VIEWER_AUDIO_SEEKBAR_TAG = "fileViewerAudioSeekbar"
const val FILE_VIEWER_AUDIO_CURRENT_TIME_TAG = "fileViewerAudioCurrentTime"
const val FILE_VIEWER_AUDIO_TOTAL_TIME_TAG = "fileViewerAudioTotalTime"
const val FILE_VIEWER_CANNOT_PREVIEW_TAG = "fileViewerCannotPreview"
const val FILE_VIEWER_RETRY_TAG = "fileViewerRetry"

/**
 * In-app file viewer — issue #497.
 *
 * Fetches a server file over the existing SSH/SFTP session and renders it:
 * images in a zoom/pan view, UTF-8 text in a scrollable monospace view, and
 * anything else (binary-non-image, too-large, missing) as a friendly
 * "can't preview" message. Read-only; directly through the app (not port
 * forwarding).
 */
@Composable
fun FileViewerScreen(
    hostName: String,
    hostname: String,
    port: Int,
    username: String,
    keyPath: String,
    passphrase: CharArray?,
    remotePath: String,
    cwd: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FileViewerViewModel = hiltViewModel(),
) {
    LaunchedEffect(hostname, port, username, keyPath, remotePath, cwd) {
        viewModel.bind(
            FileViewerViewModel.Request(
                hostname = hostname,
                port = port,
                username = username,
                keyPath = keyPath,
                passphrase = passphrase,
                path = remotePath,
                cwd = cwd,
            ),
        )
    }
    val state by viewModel.state.collectAsState()
    FileViewerScaffold(
        hostName = hostName,
        state = state,
        onBack = onBack,
        onRetry = viewModel::retry,
        modifier = modifier,
    )
}

/**
 * Stateless body — split from the view-model wiring so Compose tests can
 * drive every state (Loading, Image, Text, CannotPreview) without an SSH
 * session. Mirrors the [com.pocketshell.app.projects.RepoBrowserScaffold]
 * convention.
 */
@Composable
internal fun FileViewerScaffold(
    hostName: String,
    state: FileViewerUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PocketShellColors.Background)
            .testTag(FILE_VIEWER_SCREEN_TAG),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            FileViewerAppBar(
                hostName = hostName,
                displayPath = state.displayPath(),
                onBack = onBack,
            )
            when (state) {
                is FileViewerUiState.Loading -> LoadingPanel()
                is FileViewerUiState.Image -> ImagePanel(state.cacheFile)
                is FileViewerUiState.TextContent -> TextPanel(state.content)
                is FileViewerUiState.Pdf -> PdfPanel(state.cacheFile)
                is FileViewerUiState.Audio -> AudioPanel(state.cacheFile)
                is FileViewerUiState.CannotPreview -> CannotPreviewPanel(
                    message = state.message,
                    onRetry = onRetry,
                )
            }
        }
    }
}

private fun FileViewerUiState.displayPath(): String = when (this) {
    is FileViewerUiState.Loading -> displayPath
    is FileViewerUiState.Image -> displayPath
    is FileViewerUiState.TextContent -> displayPath
    is FileViewerUiState.Pdf -> displayPath
    is FileViewerUiState.Audio -> displayPath
    is FileViewerUiState.CannotPreview -> displayPath
}

@Composable
private fun FileViewerAppBar(
    hostName: String,
    displayPath: String,
    onBack: () -> Unit,
) {
    // Slice E1b (#539): the bespoke 60dp bar + raw `sp` title/breadcrumb adopt
    // the shared `ScreenHeader`. The file name is the title; the full remote
    // path is the breadcrumb subtitle (mono, since it is path data). The mono
    // file-reading surface below is left untouched.
    ScreenHeader(
        title = displayPath.substringAfterLast('/').ifEmpty { "File" },
        subtitle = displayPath.ifEmpty { hostName },
        titleTestTag = FILE_VIEWER_TITLE_TAG,
        modifier = Modifier.border(width = 1.dp, color = PocketShellColors.BorderSoft),
        leading = {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clickable(role = Role.Button, onClick = onBack)
                    .testTag(FILE_VIEWER_BACK_TAG),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "‹",
                    color = PocketShellColors.TextSecondary,
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
        },
    )
}

@Composable
private fun LoadingPanel() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag(FILE_VIEWER_LOADING_TAG),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = PocketShellColors.Accent)
    }
}

/**
 * Zoom/pan image view. Decodes the cached file to a bitmap and binds pinch
 * (scale) + drag (translate) transform gestures via `graphicsLayer`.
 */
@Composable
private fun ImagePanel(cacheFile: File) {
    val bitmap = remember(cacheFile.path) {
        runCatching { BitmapFactory.decodeFile(cacheFile.path)?.asImageBitmap() }.getOrNull()
    }
    if (bitmap == null) {
        CannotPreviewPanel(
            message = "Couldn't decode the image.",
            onRetry = {},
            showRetry = false,
        )
        return
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PocketShellColors.TermBg)
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 8f)
                    if (scale > 1f) {
                        offsetX += pan.x
                        offsetY += pan.y
                    } else {
                        offsetX = 0f
                        offsetY = 0f
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = "Remote image preview",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY,
                )
                .testTag(FILE_VIEWER_IMAGE_TAG),
        )
    }
}

/** Scrollable read-only monospace text view (vertical + horizontal scroll). */
@Composable
private fun TextPanel(content: String) {
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PocketShellColors.TermBg)
            .verticalScroll(vScroll)
            .testTag(FILE_VIEWER_TEXT_TAG),
    ) {
        Text(
            text = content,
            color = PocketShellColors.TermText,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            modifier = Modifier
                .horizontalScroll(hScroll)
                .padding(12.dp),
        )
    }
}

/**
 * Paged, zoomable PDF view. Opens the cached file with [PdfPageRenderer]
 * (platform [android.graphics.pdf.PdfRenderer] — no third-party dep) and
 * renders the current page to a bitmap on [Dispatchers.Default]. Prev/Next
 * page through the document; pinch (scale) + drag (translate) zoom the current
 * page, mirroring the image view's gesture pattern.
 */
@Composable
private fun PdfPanel(cacheFile: File) {
    val renderer = remember(cacheFile.path) {
        runCatching { PdfPageRenderer.open(cacheFile) }.getOrNull()
    }
    DisposableEffect(renderer) {
        onDispose { renderer?.close() }
    }
    if (renderer == null) {
        CannotPreviewPanel(
            message = "Couldn't open the PDF — the file may be corrupt or password-protected.",
            onRetry = {},
            showRetry = false,
        )
        return
    }

    val pageCount = renderer.pageCount
    var pageIndex by remember(cacheFile.path) { mutableIntStateOf(0) }
    var bitmap by remember(cacheFile.path) { mutableStateOf<ImageBitmap?>(null) }
    var rendering by remember(cacheFile.path) { mutableStateOf(true) }
    var renderError by remember(cacheFile.path) { mutableStateOf(false) }

    // Render the current page off the main thread whenever the page changes.
    LaunchedEffect(renderer, pageIndex) {
        rendering = true
        renderError = false
        bitmap = null
        val rendered = withContext(Dispatchers.Default) {
            runCatching { renderer.renderPage(pageIndex) }.getOrNull()
        }
        if (rendered != null) {
            bitmap = rendered.asImageBitmap()
        } else {
            renderError = true
        }
        rendering = false
    }

    // Reset zoom/pan when paging to a new page.
    var scale by remember(pageIndex, cacheFile.path) { mutableFloatStateOf(1f) }
    var offsetX by remember(pageIndex, cacheFile.path) { mutableFloatStateOf(0f) }
    var offsetY by remember(pageIndex, cacheFile.path) { mutableFloatStateOf(0f) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PocketShellColors.TermBg)
            .testTag(FILE_VIEWER_PDF_TAG),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .pointerInput(pageIndex) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 8f)
                        if (scale > 1f) {
                            offsetX += pan.x
                            offsetY += pan.y
                        } else {
                            offsetX = 0f
                            offsetY = 0f
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            val current = bitmap
            when {
                current != null -> Image(
                    bitmap = current,
                    contentDescription = "PDF page ${pageIndex + 1}",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offsetX,
                            translationY = offsetY,
                        )
                        .testTag(FILE_VIEWER_PDF_PAGE_TAG),
                )
                renderError -> Text(
                    text = "Couldn't render this page.",
                    color = PocketShellColors.TextSecondary,
                    fontSize = 14.sp,
                )
                rendering -> CircularProgressIndicator(color = PocketShellColors.Accent)
            }
        }
        PdfPagerBar(
            pageIndex = pageIndex,
            pageCount = pageCount,
            onPrev = { if (pageIndex > 0) pageIndex-- },
            onNext = { if (pageIndex < pageCount - 1) pageIndex++ },
        )
    }
}

@Composable
private fun PdfPagerBar(
    pageIndex: Int,
    pageCount: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(PocketShellColors.Background)
            .border(width = 1.dp, color = PocketShellColors.BorderSoft)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        PdfPagerButton(
            label = "‹ Prev",
            enabled = pageIndex > 0,
            onClick = onPrev,
            testTag = FILE_VIEWER_PDF_PREV_TAG,
        )
        Text(
            text = "Page ${pageIndex + 1} / $pageCount",
            color = PocketShellColors.Text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.testTag(FILE_VIEWER_PDF_PAGE_LABEL_TAG),
        )
        PdfPagerButton(
            label = "Next ›",
            enabled = pageIndex < pageCount - 1,
            onClick = onNext,
            testTag = FILE_VIEWER_PDF_NEXT_TAG,
        )
    }
}

@Composable
private fun PdfPagerButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    testTag: String,
) {
    Box(
        modifier = Modifier
            .width(72.dp)
            .height(40.dp)
            .then(if (enabled) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (enabled) PocketShellColors.Accent else PocketShellColors.TextSecondary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * In-app audio player — issue #499.
 *
 * Plays the cached audio file with [AudioPlayerController] (platform
 * [android.media.MediaPlayer] — no third-party dep). The controller prepares
 * asynchronously and is released in [DisposableEffect.onDispose] so the native
 * player is never leaked. A play/pause button drives playback; a [Slider]
 * shows current/total position and scrubs to seek. While playing, a polling
 * loop advances the slider; dragging the slider seeks the track.
 */
@Composable
private fun AudioPanel(cacheFile: File) {
    var phase by remember(cacheFile.path) {
        mutableStateOf(AudioPlayerController.Phase.PREPARING)
    }
    var durationMs by remember(cacheFile.path) { mutableIntStateOf(0) }
    var positionMs by remember(cacheFile.path) { mutableIntStateOf(0) }
    var errorMessage by remember(cacheFile.path) { mutableStateOf<String?>(null) }
    // While the user drags the thumb we show their drag position and suppress
    // the polling update so the thumb doesn't jump back under their finger.
    var scrubbing by remember(cacheFile.path) { mutableStateOf(false) }
    var scrubMs by remember(cacheFile.path) { mutableFloatStateOf(0f) }

    val controller = remember(cacheFile.path) {
        AudioPlayerController(
            file = cacheFile,
            listener = object : AudioPlayerController.Listener {
                override fun onPhase(p: AudioPlayerController.Phase) {
                    phase = p
                }

                override fun onDuration(d: Int) {
                    durationMs = d.coerceAtLeast(0)
                }

                override fun onError(message: String) {
                    errorMessage = message
                }
            },
        )
    }

    DisposableEffect(controller) {
        controller.prepare()
        onDispose { controller.release() }
    }

    // Poll the playback position while playing so the slider tracks the audio.
    LaunchedEffect(controller, phase) {
        if (phase == AudioPlayerController.Phase.PLAYING) {
            while (true) {
                if (!scrubbing) positionMs = controller.currentPositionMs()
                delay(200)
            }
        }
    }

    val message = errorMessage
    if (phase == AudioPlayerController.Phase.ERROR && message != null) {
        CannotPreviewPanel(message = message, onRetry = {}, showRetry = false)
        return
    }

    val isPlaying = phase == AudioPlayerController.Phase.PLAYING
    val ready = phase != AudioPlayerController.Phase.PREPARING
    val sliderMax = durationMs.coerceAtLeast(1).toFloat()
    val sliderValue = if (scrubbing) scrubMs else positionMs.toFloat().coerceIn(0f, sliderMax)
    val displayedPositionMs = if (scrubbing) scrubMs.toInt() else positionMs

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PocketShellColors.TermBg)
            .padding(horizontal = 24.dp)
            .testTag(FILE_VIEWER_AUDIO_TAG),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .then(
                    if (ready) {
                        Modifier.clickable(role = Role.Button) {
                            if (isPlaying) controller.pause() else controller.play()
                        }
                    } else {
                        Modifier
                    },
                )
                .testTag(FILE_VIEWER_AUDIO_PLAY_PAUSE_TAG),
            contentAlignment = Alignment.Center,
        ) {
            if (!ready) {
                CircularProgressIndicator(color = PocketShellColors.Accent)
            } else {
                Text(
                    text = if (isPlaying) "❚❚" else "▶",
                    color = PocketShellColors.Accent,
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        Slider(
            value = sliderValue,
            valueRange = 0f..sliderMax,
            enabled = ready,
            onValueChange = {
                scrubbing = true
                scrubMs = it
            },
            onValueChangeFinished = {
                controller.seekTo(scrubMs.toInt())
                positionMs = scrubMs.toInt()
                scrubbing = false
            },
            colors = audioSliderColors(),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(FILE_VIEWER_AUDIO_SEEKBAR_TAG),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatAudioTime(displayedPositionMs),
                color = PocketShellColors.TextSecondary,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.testTag(FILE_VIEWER_AUDIO_CURRENT_TIME_TAG),
            )
            Text(
                text = formatAudioTime(durationMs),
                color = PocketShellColors.TextSecondary,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.testTag(FILE_VIEWER_AUDIO_TOTAL_TIME_TAG),
            )
        }
    }
}

@Composable
private fun audioSliderColors(): SliderColors = SliderDefaults.colors(
    thumbColor = PocketShellColors.Accent,
    activeTrackColor = PocketShellColors.Accent,
    inactiveTrackColor = PocketShellColors.BorderSoft,
)

/**
 * Format a millisecond position as `m:ss` (or `h:mm:ss` past an hour). Pure —
 * unit-tested. A negative/unknown value renders as `0:00`.
 */
internal fun formatAudioTime(positionMs: Int): String {
    val totalSeconds = (positionMs.coerceAtLeast(0)) / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

@Composable
private fun CannotPreviewPanel(
    message: String,
    onRetry: () -> Unit,
    showRetry: Boolean = true,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 24.dp)
            .testTag(FILE_VIEWER_CANNOT_PREVIEW_TAG),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Can't preview",
            color = PocketShellColors.Text,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = message,
            color = PocketShellColors.TextSecondary,
            fontSize = 14.sp,
        )
        if (showRetry) {
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onRetry, modifier = Modifier.testTag(FILE_VIEWER_RETRY_TAG)) {
                Text("Retry", color = PocketShellColors.Accent)
            }
        }
    }
}
