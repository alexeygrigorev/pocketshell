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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.pocketshell.uikit.theme.PocketShellColors
import java.io.File

const val FILE_VIEWER_SCREEN_TAG = "fileViewerScreen"
const val FILE_VIEWER_BACK_TAG = "fileViewerBack"
const val FILE_VIEWER_TITLE_TAG = "fileViewerTitle"
const val FILE_VIEWER_LOADING_TAG = "fileViewerLoading"
const val FILE_VIEWER_IMAGE_TAG = "fileViewerImage"
const val FILE_VIEWER_TEXT_TAG = "fileViewerText"
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
    is FileViewerUiState.CannotPreview -> displayPath
}

@Composable
private fun FileViewerAppBar(
    hostName: String,
    displayPath: String,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .background(PocketShellColors.Background)
            .border(width = 1.dp, color = PocketShellColors.BorderSoft)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Column(modifier = Modifier.padding(start = 4.dp)) {
            Text(
                text = displayPath.substringAfterLast('/').ifEmpty { "File" },
                color = PocketShellColors.Text,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.testTag(FILE_VIEWER_TITLE_TAG),
            )
            Text(
                text = displayPath.ifEmpty { hostName },
                color = PocketShellColors.TextSecondary,
                fontSize = 11.sp,
            )
        }
    }
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
