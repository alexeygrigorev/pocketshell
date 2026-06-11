package com.pocketshell.app.fileviewer

import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellSpacing
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

// Issue #559 — "act on the opened file" header actions.
const val FILE_VIEWER_SHARE_TAG = "fileViewerShare"
const val FILE_VIEWER_COPY_TAG = "fileViewerCopy"
const val FILE_VIEWER_COPY_ALL_TAG = "fileViewerCopyAll"

// Issue #623 — "Save" action to download the remote file locally.
const val FILE_VIEWER_SAVE_TAG = "fileViewerSave"

// Issue #696 — text reading toggles: word wrap + Markdown render-vs-raw.
const val FILE_VIEWER_WRAP_TAG = "fileViewerWrap"
const val FILE_VIEWER_RENDER_MD_TAG = "fileViewerRenderMarkdown"

// Issue #623 — download-only panel for unsupported file types (binary, archives, etc.).
const val FILE_VIEWER_DOWNLOAD_ONLY_TAG = "fileViewerDownloadOnly"
const val FILE_VIEWER_DOWNLOAD_BUTTON_TAG = "fileViewerDownloadButton"
const val FILE_VIEWER_FILE_SIZE_TAG = "fileViewerFileSize"
const val FILE_VIEWER_FILE_NAME_TAG = "fileViewerFileName"

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
    val readingPrefs by viewModel.readingPrefs.collectAsState()
    FileViewerScaffold(
        hostName = hostName,
        state = state,
        readingPrefs = readingPrefs,
        onBack = onBack,
        onRetry = viewModel::retry,
        onToggleWordWrap = viewModel::toggleWordWrap,
        onToggleRenderMarkdown = viewModel::toggleRenderMarkdown,
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
    readingPrefs: FileViewerReadingPrefs = FileViewerReadingPrefs(wordWrap = false, renderMarkdown = true),
    onToggleWordWrap: () -> Unit = {},
    onToggleRenderMarkdown: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // The wrap / Markdown-render toggles only apply to the text reading
    // surface, so the app bar offers them only for a TextContent state.
    val textState = state as? FileViewerUiState.TextContent
    val isMarkdown = textState != null && MarkdownParser.isMarkdownPath(textState.displayPath)
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
                shareable = state.shareable(),
                onBack = onBack,
            )
            if (textState != null) {
                TextReadingToggleBar(
                    wordWrap = readingPrefs.wordWrap,
                    showMarkdownToggle = isMarkdown,
                    renderMarkdown = readingPrefs.renderMarkdown,
                    onToggleWordWrap = onToggleWordWrap,
                    onToggleRenderMarkdown = onToggleRenderMarkdown,
                )
            }
            when (state) {
                is FileViewerUiState.Loading -> LoadingPanel()
                is FileViewerUiState.Image -> ImagePanel(state.cacheFile)
                is FileViewerUiState.TextContent -> TextPanel(
                    content = state.content,
                    wordWrap = readingPrefs.wordWrap,
                    isMarkdown = isMarkdown,
                    renderMarkdown = readingPrefs.renderMarkdown,
                )
                is FileViewerUiState.Pdf -> PdfPanel(state.cacheFile)
                is FileViewerUiState.Audio -> AudioPanel(state.cacheFile)
                is FileViewerUiState.CannotPreview -> if (state.cacheFile != null) {
                    DownloadOnlyPanel(
                        displayPath = state.displayPath,
                        sizeBytes = state.sizeBytes,
                        cacheFile = state.cacheFile,
                    )
                } else {
                    CannotPreviewPanel(
                        message = state.message,
                        onRetry = onRetry,
                    )
                }
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

/**
 * Issue #559 — what the viewer can hand to the share sheet / clipboard for the
 * currently-opened file. Every previewable state maps to a [Shareable]; the
 * [FileViewerUiState.Loading] and [FileViewerUiState.CannotPreview] (without a
 * cached file) states have nothing to act on yet, so the Share/Copy/Save actions
 * are hidden for them.
 *
 * Issue #623 — [CannotPreview] with a cached file (binary/unsupported file that
 * was downloaded but can't be previewed) produces a [Shareable.FileBacked] so
 * the header Save button can download it to the Android Downloads directory.
 *
 * Image/PDF/Audio already cached the bytes to a local [File] for their preview,
 * so the URI is built straight from that cache file. Text is held in memory
 * ([FileViewerUiState.TextContent.content]); for a file URI we materialise it to
 * the same `file-viewer` cache dir on demand (see [Shareable.Text.materialize]),
 * and we also keep the raw text so "Copy all" can put it on the clipboard
 * directly.
 */
internal sealed interface Shareable {
    /** Remote path of the opened file — drives the share name + MIME type. */
    val displayPath: String

    /** MIME type so a receiving app (Telegram/Gmail) accepts the file. */
    val mimeType: String

    data class FileBacked(
        override val displayPath: String,
        val cacheFile: File,
        override val mimeType: String,
    ) : Shareable

    data class Text(
        override val displayPath: String,
        val content: String,
    ) : Shareable {
        override val mimeType: String get() = "text/plain"

        /**
         * Write the in-memory text to the shared `file-viewer` cache dir so the
         * FileProvider can serve it as a content URI (for Share / Copy-as-file).
         * The "Copy all" action does not need this — it copies [content]
         * directly to the clipboard.
         */
        fun materialize(context: Context): File {
            val dir = File(context.cacheDir, FileViewerViewModel.CACHE_SUBDIR).apply { mkdirs() }
            val name = shareFileName(displayPath, fallbackExtension = "txt")
            return File(dir, name).apply { writeText(content) }
        }
    }
}

private fun FileViewerUiState.shareable(): Shareable? = when (this) {
    is FileViewerUiState.Loading -> null
    is FileViewerUiState.CannotPreview -> cacheFile?.let {
        Shareable.FileBacked(
            displayPath = displayPath,
            cacheFile = it,
            mimeType = "application/octet-stream",
        )
    }
    is FileViewerUiState.Image -> Shareable.FileBacked(
        displayPath = displayPath,
        cacheFile = cacheFile,
        mimeType = imageMimeFor(displayPath),
    )
    is FileViewerUiState.Pdf -> Shareable.FileBacked(
        displayPath = displayPath,
        cacheFile = cacheFile,
        mimeType = "application/pdf",
    )
    is FileViewerUiState.Audio -> Shareable.FileBacked(
        displayPath = displayPath,
        cacheFile = cacheFile,
        mimeType = audioMimeFor(displayPath),
    )
    is FileViewerUiState.TextContent -> Shareable.Text(
        displayPath = displayPath,
        content = content,
    )
}

/**
 * MIME type for an image preview, derived from the path extension so a
 * receiving app gets the right type (a JPEG isn't `image/png`). Falls back to
 * the `image` wildcard type when the extension is missing/unknown (content was
 * image-sniffed).
 */
internal fun imageMimeFor(displayPath: String): String =
    when (FileTypeDetector.extensionOf(displayPath)) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        else -> "image/*"
    }

/**
 * MIME type for an audio preview, derived from the path extension. Falls back
 * to the `audio` wildcard type for an unknown/missing extension (content was
 * magic-sniffed).
 */
internal fun audioMimeFor(displayPath: String): String =
    when (FileTypeDetector.extensionOf(displayPath)) {
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "m4a", "aac" -> "audio/mp4"
        "ogg", "oga" -> "audio/ogg"
        "flac" -> "audio/flac"
        else -> "audio/*"
    }

/**
 * A filesystem-safe share file name from the remote path's basename, keeping
 * the original extension so receiving apps see the right type. Falls back to
 * [fallbackExtension] when the basename has no extension.
 */
internal fun shareFileName(displayPath: String, fallbackExtension: String): String {
    val base = displayPath.substringAfterLast('/').substringAfterLast('\\')
        .ifBlank { "file" }
    val safe = base.replace(Regex("[^A-Za-z0-9._-]"), "_")
    return if (safe.contains('.')) safe else "$safe.$fallbackExtension"
}

@Composable
private fun FileViewerAppBar(
    hostName: String,
    displayPath: String,
    shareable: Shareable?,
    onBack: () -> Unit,
) {
    // Slice E1b (#539): the bespoke 60dp bar + raw `sp` title/breadcrumb adopt
    // the shared `ScreenHeader`. The file name is the title; the full remote
    // path is the breadcrumb subtitle (mono, since it is path data). The mono
    // file-reading surface below is left untouched.
    //
    // Issue #559: the trailing slot carries the "act on the opened file"
    // actions — Share (system share sheet) and Copy (file URI to the
    // clipboard). They appear only once there is something to act on (a
    // previewable state with a cached file or text content).
    //
    // Issue #623: Save action added — downloads the file to the Android
    // Downloads directory via DownloadManager.
    val context = LocalContext.current
    ScreenHeader(
        title = displayPath.substringAfterLast('/').ifEmpty { "File" },
        subtitle = displayPath.ifEmpty { hostName },
        titleTestTag = FILE_VIEWER_TITLE_TAG,
        modifier = Modifier.border(width = 1.dp, color = PocketShellColors.BorderSoft),
        leading = {
            Box(
                modifier = Modifier
                    .size(PocketShellDensity.tapTargetMin)
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
        trailing = if (shareable == null) {
            null
        } else {
            {
                HeaderAction(
                    label = "Save",
                    testTag = FILE_VIEWER_SAVE_TAG,
                    onClick = { saveFileToLocal(context, shareable) },
                )
                HeaderAction(
                    label = "Share",
                    testTag = FILE_VIEWER_SHARE_TAG,
                    onClick = { shareFile(context, shareable) },
                )
                HeaderAction(
                    label = "Copy",
                    testTag = FILE_VIEWER_COPY_TAG,
                    onClick = { copyFileToClipboard(context, shareable) },
                )
            }
        },
    )
}

/**
 * Issue #696 — the text reading toggle strip shown directly under the header
 * for a text file: a word-wrap toggle (always) and, for Markdown files, a
 * render-vs-raw toggle. Kept as its own row (not crammed into the header
 * trailing slot next to Save/Share/Copy) so every control stays fully visible
 * and tappable rather than occluded when the header gets crowded.
 */
@Composable
private fun TextReadingToggleBar(
    wordWrap: Boolean,
    showMarkdownToggle: Boolean,
    renderMarkdown: Boolean,
    onToggleWordWrap: () -> Unit,
    onToggleRenderMarkdown: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PocketShellColors.Background)
            .border(width = 1.dp, color = PocketShellColors.BorderSoft)
            .padding(horizontal = PocketShellSpacing.sm, vertical = PocketShellSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PocketShellSpacing.xs),
    ) {
        ToggleChip(
            label = if (wordWrap) "Wrap: on" else "Wrap: off",
            active = wordWrap,
            testTag = FILE_VIEWER_WRAP_TAG,
            onClick = onToggleWordWrap,
        )
        if (showMarkdownToggle) {
            ToggleChip(
                label = if (renderMarkdown) "View: rendered" else "View: raw",
                active = renderMarkdown,
                testTag = FILE_VIEWER_RENDER_MD_TAG,
                onClick = onToggleRenderMarkdown,
            )
        }
    }
}

/** A compact pill toggle for the reading toggle bar (#696). */
@Composable
private fun ToggleChip(
    label: String,
    active: Boolean,
    testTag: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .height(PocketShellDensity.tapTargetMin)
            .background(
                color = if (active) PocketShellColors.SurfaceElev else PocketShellColors.Surface,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = PocketShellSpacing.sm)
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (active) PocketShellColors.Accent else PocketShellColors.TextSecondary,
            style = PocketShellType.bodyDense,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** A compact text action button for the header trailing slot (#559). */
@Composable
private fun HeaderAction(
    label: String,
    testTag: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .height(PocketShellDensity.tapTargetMin)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = PocketShellSpacing.sm)
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = PocketShellColors.Accent,
            style = PocketShellType.bodyDense,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Build a `.fileprovider` content URI for the opened file and fire an
 * `ACTION_SEND` chooser. Mirrors the proven `CostsScreen` pattern: the same
 * authority (`${applicationId}.fileprovider`), `FLAG_GRANT_READ_URI_PERMISSION`
 * so the receiving app can read the file, and a `createChooser` so the system
 * lists Telegram / Gmail / Drive / etc. The MIME type is derived from the file
 * type/extension so the receiving app accepts it.
 */
private fun shareFile(context: Context, shareable: Shareable) {
    val file = shareable.resolveFile(context)
    if (file == null) {
        Toast.makeText(context, "Nothing to share", Toast.LENGTH_SHORT).show()
        return
    }
    val uri = runCatching {
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    }.getOrNull()
    if (uri == null) {
        Toast.makeText(context, "Couldn't prepare the file to share", Toast.LENGTH_SHORT).show()
        return
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = shareable.mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, file.name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(intent, "Share ${file.name}").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        },
    )
}

/**
 * Put the opened file on the clipboard as a content URI so it can be pasted
 * into apps that accept files (`ClipData.newUri`). The `ClipData` is built
 * through the `ContentResolver` so the receiving app inherits read access to
 * the `.fileprovider` URI.
 */
private fun copyFileToClipboard(context: Context, shareable: Shareable) {
    val file = shareable.resolveFile(context)
    if (file == null) {
        Toast.makeText(context, "Nothing to copy", Toast.LENGTH_SHORT).show()
        return
    }
    val uri = runCatching {
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    }.getOrNull()
    if (uri == null) {
        Toast.makeText(context, "Couldn't prepare the file to copy", Toast.LENGTH_SHORT).show()
        return
    }
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    if (clipboard == null) {
        Toast.makeText(context, "Clipboard unavailable", Toast.LENGTH_SHORT).show()
        return
    }
    val clip = ClipData.newUri(context.contentResolver, file.name, uri)
    clipboard.setPrimaryClip(clip)
    Toast.makeText(context, "Copied ${file.name} to clipboard", Toast.LENGTH_SHORT).show()
}

/** Copy raw text to the clipboard as plain text (text viewer "Copy all"). */
private fun copyTextToClipboard(context: Context, content: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    if (clipboard == null) {
        Toast.makeText(context, "Clipboard unavailable", Toast.LENGTH_SHORT).show()
        return
    }
    clipboard.setPrimaryClip(ClipData.newPlainText("file text", content))
    Toast.makeText(context, "Copied text to clipboard", Toast.LENGTH_SHORT).show()
}

/**
 * Issue #623 — save the viewed file to the Android Downloads directory via
 * [DownloadManager]. The file is registered as a completed download in the
 * public Downloads folder so the user can access it from any app (Files, file
 * managers, etc.). A Toast confirms the save location. The call is
 * fire-and-forget; [DownloadManager.addCompletedDownload] registers the file
 * with the media scanner and shows a system notification, so the viewer is
 * never blocked.
 *
 * For [Shareable.FileBacked] the already-cached preview file is registered
 * directly. For [Shareable.Text] the in-memory text is materialised to a
 * temp file first and then registered.
 */
private fun saveFileToLocal(context: Context, shareable: Shareable) {
    val file = shareable.resolveFile(context)
    if (file == null || !file.exists()) {
        Toast.makeText(context, "Nothing to save", Toast.LENGTH_SHORT).show()
        return
    }
    val fileName = downloadFileName(shareable.displayPath)
    val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
    if (dm == null) {
        Toast.makeText(context, "Download manager unavailable", Toast.LENGTH_SHORT).show()
        return
    }
    dm.addCompletedDownload(
        fileName,
        "Saved from PocketShell",
        true,
        shareable.mimeType,
        file.absolutePath,
        file.length(),
        false,
    )
    Toast.makeText(context, "Saved to Downloads/$fileName", Toast.LENGTH_SHORT).show()
}

/**
 * Derive a filesystem-safe download file name from the remote path. Keeps the
 * original basename and extension; falls back to "file" when the basename is
 * empty. Pure — unit-tested.
 */
internal fun downloadFileName(displayPath: String): String {
    val base = displayPath.substringAfterLast('/').substringAfterLast('\\')
        .ifBlank { "file" }
    return base.replace(Regex("[^A-Za-z0-9._-]"), "_")
}

/**
 * The local [File] backing a [Shareable]: the already-cached preview file for
 * image/PDF/audio, or a freshly-materialised cache file for text. Returns null
 * only if a text file fails to write (rare; surfaced as a toast by the caller).
 */
private fun Shareable.resolveFile(context: Context): File? = when (this) {
    is Shareable.FileBacked -> cacheFile.takeIf { it.exists() }
    is Shareable.Text -> runCatching { materialize(context) }.getOrNull()
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
        runCatching { BoundedImageDecoder.decodeFile(cacheFile)?.asImageBitmap() }.getOrNull()
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

/**
 * Scrollable read-only text view.
 *
 * Issue #559: the reading surface is wrapped in a [SelectionContainer] so the
 * user can long-press to select an arbitrary range (and use the system
 * copy/share handles), and a one-tap "Copy all" action above it puts the whole
 * body on the clipboard as plain text.
 *
 * Issue #696: two reading modes drive what is rendered below the "Copy all"
 * action.
 *  - **Word wrap** ([wordWrap]) — when on, long lines wrap to the viewport;
 *    when off, the monospace body keeps its own horizontal scroll so wide code
 *    lines stay aligned. The whole surface always scrolls vertically.
 *  - **Markdown** — when the file is Markdown ([isMarkdown]) and
 *    [renderMarkdown] is on, the body renders as formatted Markdown
 *    ([MarkdownView]); otherwise the raw source is shown in the monospace view.
 *    Wrap still applies to the raw source view; the rendered Markdown view
 *    always wraps (only its fenced code blocks scroll horizontally).
 */
@Composable
private fun TextPanel(
    content: String,
    wordWrap: Boolean,
    isMarkdown: Boolean,
    renderMarkdown: Boolean,
) {
    val context = LocalContext.current
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    val showRenderedMarkdown = isMarkdown && renderMarkdown
    val blocks = remember(content, showRenderedMarkdown) {
        if (showRenderedMarkdown) MarkdownParser.parse(content) else emptyList()
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PocketShellColors.TermBg),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Box(
                modifier = Modifier
                    .clickable(role = Role.Button) { copyTextToClipboard(context, content) }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .testTag(FILE_VIEWER_COPY_ALL_TAG),
            ) {
                Text(
                    text = "Copy all",
                    color = PocketShellColors.Accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        SelectionContainer(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(vScroll)
                .testTag(FILE_VIEWER_TEXT_TAG),
        ) {
            if (showRenderedMarkdown) {
                MarkdownView(blocks = blocks)
            } else {
                Text(
                    text = content,
                    color = PocketShellColors.TermText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .then(if (wordWrap) Modifier.fillMaxWidth() else Modifier.horizontalScroll(hScroll))
                        .padding(12.dp),
                )
            }
        }
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

/**
 * Download-only panel for unsupported file types (binary, archives, etc.) —
 * issue #623. Shows the file name, size, and a prominent Download button that
 * saves the cached file to the Android Downloads directory via
 * [DownloadManager]. This is shown instead of the "Can't preview" message when
 * the file was successfully downloaded but can't be previewed (binary type).
 */
@Composable
private fun DownloadOnlyPanel(
    displayPath: String,
    sizeBytes: Long,
    cacheFile: File,
) {
    val context = LocalContext.current
    val fileName = displayPath.substringAfterLast('/').ifEmpty { "file" }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 32.dp)
            .testTag(FILE_VIEWER_DOWNLOAD_ONLY_TAG),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "This file can't be previewed.",
            color = PocketShellColors.TextSecondary,
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = fileName,
            color = PocketShellColors.Text,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.testTag(FILE_VIEWER_FILE_NAME_TAG),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = formatFileSize(sizeBytes),
            color = PocketShellColors.TextSecondary,
            fontSize = 13.sp,
            modifier = Modifier.testTag(FILE_VIEWER_FILE_SIZE_TAG),
        )
        Spacer(Modifier.height(24.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(PocketShellDensity.tapTargetMin)
                .background(
                    color = PocketShellColors.Accent,
                    shape = RoundedCornerShape(8.dp),
                )
                .clickable(
                    role = Role.Button,
                    onClick = {
                        val shareable = Shareable.FileBacked(
                            displayPath = displayPath,
                            cacheFile = cacheFile,
                            mimeType = "application/octet-stream",
                        )
                        saveFileToLocal(context, shareable)
                    },
                )
                .testTag(FILE_VIEWER_DOWNLOAD_BUTTON_TAG),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Download",
                color = PocketShellColors.Background,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * Format a byte count as a human-readable string (e.g. "1.5 MB", "320 KB").
 * Pure — unit-tested.
 */
internal fun formatFileSize(sizeBytes: Long): String {
    if (sizeBytes < 1024) return "$sizeBytes B"
    val kb = sizeBytes / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.1f GB", gb)
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
