package com.pocketshell.app.fileviewer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.FileIconClass
import com.pocketshell.uikit.components.FileTypeIcon
import com.pocketshell.uikit.components.LoadingIndicator
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.components.fileIconClassForName
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

const val FILE_VIEWER_SCREEN_TAG = "fileViewerScreen"
const val FILE_VIEWER_BACK_TAG = "fileViewerBack"
const val FILE_VIEWER_TITLE_TAG = "fileViewerTitle"

// Issue #762 slice C — the leading file-type icon in the viewer header.
const val FILE_VIEWER_TYPE_ICON_TAG = "fileViewerTypeIcon"
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

// Issue #748 — "open it where it actually is" candidate rows on the can't-preview
// panel when the relative path was missing under the session cwd but the agent
// worked in a subdirectory.
const val FILE_VIEWER_LOCATE_CANDIDATE_TAG = "fileViewerLocateCandidate"

// Issue #559 — "act on the opened file" header actions.
const val FILE_VIEWER_SHARE_TAG = "fileViewerShare"
const val FILE_VIEWER_COPY_TAG = "fileViewerCopy"
const val FILE_VIEWER_COPY_ALL_TAG = "fileViewerCopyAll"

// Issue #623 — "Save" action to download the remote file locally.
const val FILE_VIEWER_SAVE_TAG = "fileViewerSave"

// Issue #696 — text reading toggles: word wrap + Markdown render-vs-raw.
const val FILE_VIEWER_WRAP_TAG = "fileViewerWrap"
const val FILE_VIEWER_RENDER_MD_TAG = "fileViewerRenderMarkdown"

// Issue #714 — review-comments mode (per-line + whole-file feedback).
const val FILE_VIEWER_REVIEW_TOGGLE_TAG = "fileViewerReviewToggle"
const val FILE_VIEWER_REVIEW_FILE_NOTE_TAG = "fileViewerReviewFileNote"
const val FILE_VIEWER_REVIEW_TRAY_TAG = "fileViewerReviewTray"
const val FILE_VIEWER_COMMENTABLE_TEXT_TAG = "fileViewerCommentableText"
const val FILE_VIEWER_REVIEW_LINE_SHEET_TAG = "fileViewerReviewLineSheet"
const val FILE_VIEWER_REVIEW_FILE_SHEET_TAG = "fileViewerReviewFileSheet"
const val FILE_VIEWER_REVIEW_TRAY_SHEET_TAG = "fileViewerReviewTraySheet"
const val FILE_VIEWER_REVIEW_COMMENT_FIELD_TAG = "fileViewerReviewCommentField"
const val FILE_VIEWER_REVIEW_SAVE_TAG = "fileViewerReviewSave"
const val FILE_VIEWER_REVIEW_DELETE_TAG = "fileViewerReviewDelete"
const val FILE_VIEWER_REVIEW_SUBMIT_TAG = "fileViewerReviewSubmit"

// Issue #763 — post-Submit confirmation surface: the saved YAML path (copyable)
// plus an "Attach to current session" action.
const val FILE_VIEWER_REVIEW_SAVED_SHEET_TAG = "fileViewerReviewSavedSheet"
const val FILE_VIEWER_REVIEW_SAVED_PATH_TAG = "fileViewerReviewSavedPath"
const val FILE_VIEWER_REVIEW_COPY_PATH_TAG = "fileViewerReviewCopyPath"
const val FILE_VIEWER_REVIEW_ATTACH_TAG = "fileViewerReviewAttach"
const val FILE_VIEWER_REVIEW_SAVED_DONE_TAG = "fileViewerReviewSavedDone"

// Issue #764 — image annotation mode (Pen / Arrow / Rect / Circle / Text /
// colour / Undo / Submit + flatten).
const val FILE_VIEWER_ANNOTATE_TOGGLE_TAG = "fileViewerAnnotateToggle"
const val FILE_VIEWER_ANNOTATE_CANVAS_TAG = "fileViewerAnnotateCanvas"
const val FILE_VIEWER_ANNOTATE_TOOL_PAN_TAG = "fileViewerAnnotateToolPan"
const val FILE_VIEWER_ANNOTATE_TOOL_PEN_TAG = "fileViewerAnnotateToolPen"
const val FILE_VIEWER_ANNOTATE_TOOL_ARROW_TAG = "fileViewerAnnotateToolArrow"
const val FILE_VIEWER_ANNOTATE_TOOL_RECT_TAG = "fileViewerAnnotateToolRect"
const val FILE_VIEWER_ANNOTATE_TOOL_CIRCLE_TAG = "fileViewerAnnotateToolCircle"
const val FILE_VIEWER_ANNOTATE_TOOL_TEXT_TAG = "fileViewerAnnotateToolText"
const val FILE_VIEWER_ANNOTATE_TEXT_FIELD_TAG = "fileViewerAnnotateTextField"
const val FILE_VIEWER_ANNOTATE_TEXT_CONFIRM_TAG = "fileViewerAnnotateTextConfirm"
const val FILE_VIEWER_ANNOTATE_UNDO_TAG = "fileViewerAnnotateUndo"
const val FILE_VIEWER_ANNOTATE_SUBMIT_TAG = "fileViewerAnnotateSubmit"
const val FILE_VIEWER_ANNOTATE_SAVED_SHEET_TAG = "fileViewerAnnotateSavedSheet"
const val FILE_VIEWER_ANNOTATE_SAVED_PATH_TAG = "fileViewerAnnotateSavedPath"
const val FILE_VIEWER_ANNOTATE_ATTACH_TAG = "fileViewerAnnotateAttach"

/** Test tag for an annotation colour swatch (the packed ARGB int). */
fun fileViewerAnnotateSwatchTag(argb: Int): String = "fileViewerAnnotateSwatch-$argb"

/** Test tag for the gutter tap target of a 1-based [line] in the commentable panel. */
fun fileViewerLineGutterTag(line: Int): String = "fileViewerLineGutter-$line"

/** Test tag for the comment dot marker of a commented 1-based [line]. */
fun fileViewerLineDotTag(line: Int): String = "fileViewerLineDot-$line"

/** Test tag for a pending-tray row addressing a 1-based [line] (-1 = file note). */
fun fileViewerTrayRowTag(line: Int): String = "fileViewerTrayRow-$line"

// Issue #623 — download-only panel for unsupported file types (binary, archives, etc.).
const val FILE_VIEWER_DOWNLOAD_ONLY_TAG = "fileViewerDownloadOnly"
const val FILE_VIEWER_DOWNLOAD_BUTTON_TAG = "fileViewerDownloadButton"
const val FILE_VIEWER_FILE_SIZE_TAG = "fileViewerFileSize"
const val FILE_VIEWER_FILE_NAME_TAG = "fileViewerFileName"

// Issue #762 slice C — the file-type icon leading the download-only card.
const val FILE_VIEWER_DOWNLOAD_TYPE_ICON_TAG = "fileViewerDownloadTypeIcon"

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
    hostId: Long,
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
    // Issue #763: route a saved review into the active session composer. The
    // caller (MainActivity) seeds the templated prompt into the activity-scoped
    // PromptComposerViewModel and pops back to the session. A no-op default keeps
    // direct callers / unit tests unchanged.
    onAttachReviewToSession: (prompt: String) -> Unit = {},
    viewModel: FileViewerViewModel = hiltViewModel(),
) {
    LaunchedEffect(hostId, hostname, port, username, keyPath, remotePath, cwd) {
        viewModel.bind(
            FileViewerViewModel.Request(
                hostId = hostId,
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
    val reviewState by viewModel.reviewState.collectAsState()
    val annotationState by viewModel.annotationState.collectAsState()

    // Issue #714/#763 — surface the review-submit outcome. On SUCCESS we hold
    // the result so the scaffold renders the confirmation sheet (the saved path,
    // copyable, + an "Attach to current session" action — #763); a failure stays
    // a calm one-line toast (the pending comments are kept by the ViewModel so
    // nothing is lost).
    val context = LocalContext.current
    var submittedReview by remember { mutableStateOf<ReviewSubmitEvent.Success?>(null) }
    LaunchedEffect(viewModel) {
        viewModel.reviewEvents.collect { event ->
            when (event) {
                is ReviewSubmitEvent.Success -> submittedReview = event
                is ReviewSubmitEvent.Failure ->
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Issue #764 — surface the annotation-submit outcome. SUCCESS holds the
    // result so the scaffold renders the saved-path confirmation sheet (copyable
    // PNG path); a failure stays a calm one-line toast (the ViewModel keeps the
    // annotations so nothing is lost).
    var submittedAnnotation by remember { mutableStateOf<AnnotationSubmitEvent.Success?>(null) }
    LaunchedEffect(viewModel) {
        viewModel.annotationEvents.collect { event ->
            when (event) {
                is AnnotationSubmitEvent.Success -> submittedAnnotation = event
                is AnnotationSubmitEvent.Failure ->
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    FileViewerScaffold(
        hostName = hostName,
        state = state,
        readingPrefs = readingPrefs,
        reviewState = reviewState,
        submittedReview = submittedReview,
        annotationState = annotationState,
        submittedAnnotation = submittedAnnotation,
        onBack = onBack,
        onRetry = viewModel::retry,
        onOpenLocated = viewModel::openLocated,
        onToggleWordWrap = viewModel::toggleWordWrap,
        onToggleRenderMarkdown = viewModel::toggleRenderMarkdown,
        onToggleReviewMode = viewModel::toggleReviewMode,
        onSetLineComment = viewModel::setLineComment,
        onDeleteLineComment = viewModel::deleteLineComment,
        onSetFileComment = viewModel::setFileComment,
        onDeleteFileComment = viewModel::deleteFileComment,
        onSubmitReview = { viewModel.submitReview(hostName) },
        onCopyReviewPath = { path ->
            copyTextToClipboard(context, path, "Copied review path")
        },
        onAttachReview = { path ->
            submittedReview = null
            onAttachReviewToSession(reviewAttachPrompt(path))
        },
        onDismissSubmittedReview = { submittedReview = null },
        onToggleAnnotateMode = viewModel::toggleAnnotationMode,
        onSetAnnotationTool = viewModel::setAnnotationTool,
        onSetAnnotationColor = viewModel::setAnnotationColor,
        onAddAnnotation = viewModel::addAnnotation,
        onUndoAnnotation = viewModel::undoAnnotation,
        onSubmitAnnotation = { viewModel.submitAnnotation(hostName) },
        onCopyAnnotationPath = { path ->
            copyTextToClipboard(context, path, "Copied image path")
        },
        onAttachAnnotation = { path ->
            // Issue #764 v2 — reuse the #763 attach-to-session path: dismiss the
            // sheet, then seed the active session composer with an annotation
            // prompt referencing the saved PNG.
            submittedAnnotation = null
            onAttachReviewToSession(annotationAttachPrompt(path))
        },
        onDismissSubmittedAnnotation = { submittedAnnotation = null },
        modifier = modifier,
    )
}

/**
 * Stateless body — split from the view-model wiring so Compose tests can
 * drive every state (Loading, Image, Text, CannotPreview) without an SSH
 * session. Mirrors the [com.pocketshell.app.projects.RepoBrowserScaffold]
 * convention.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun FileViewerScaffold(
    hostName: String,
    state: FileViewerUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onOpenLocated: (String) -> Unit = {},
    readingPrefs: FileViewerReadingPrefs = FileViewerReadingPrefs(wordWrap = false, renderMarkdown = true),
    reviewState: ReviewState = ReviewState(),
    // Issue #763: the most recent successful submit (null when none pending) —
    // drives the post-Submit confirmation sheet (saved path + attach action).
    submittedReview: ReviewSubmitEvent.Success? = null,
    // Issue #764 — image annotation state + the most recent successful submit.
    annotationState: ImageAnnotationState = ImageAnnotationState(),
    submittedAnnotation: AnnotationSubmitEvent.Success? = null,
    onToggleWordWrap: () -> Unit = {},
    onToggleRenderMarkdown: () -> Unit = {},
    onToggleReviewMode: () -> Unit = {},
    onSetLineComment: (Int, String) -> Unit = { _, _ -> },
    onDeleteLineComment: (Int) -> Unit = {},
    onSetFileComment: (String) -> Unit = {},
    onDeleteFileComment: () -> Unit = {},
    onSubmitReview: () -> Unit = {},
    onCopyReviewPath: (String) -> Unit = {},
    onAttachReview: (String) -> Unit = {},
    onDismissSubmittedReview: () -> Unit = {},
    onToggleAnnotateMode: () -> Unit = {},
    onSetAnnotationTool: (AnnotationTool) -> Unit = {},
    onSetAnnotationColor: (Int) -> Unit = {},
    onAddAnnotation: (Annotation) -> Unit = {},
    onUndoAnnotation: () -> Unit = {},
    onSubmitAnnotation: () -> Unit = {},
    onCopyAnnotationPath: (String) -> Unit = {},
    onAttachAnnotation: (String) -> Unit = {},
    onDismissSubmittedAnnotation: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // The wrap / Markdown-render toggles only apply to the text reading
    // surface, so the app bar offers them only for a TextContent state.
    val textState = state as? FileViewerUiState.TextContent
    // Issue #764 — annotate mode applies only to an image preview.
    val imageState = state as? FileViewerUiState.Image
    val isMarkdown = textState != null && MarkdownParser.isMarkdownPath(textState.displayPath)

    // Issue #714 — which comment sheet (if any) is open: a line sheet, the
    // file-note sheet, or the pending-comments tray. Held here, not in the
    // ViewModel, since it is transient UI affordance state (the comments
    // themselves live in the ViewModel and survive config change).
    var activeSheet by remember { mutableStateOf<ReviewSheet?>(null) }
    // Close any open sheet if review mode is turned off.
    LaunchedEffect(reviewState.active) {
        if (!reviewState.active) activeSheet = null
    }

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
                fileIconClass = state.fileIconClass(),
                shareable = state.shareable(),
                onBack = onBack,
                // Review header actions only make sense for a text file in
                // review mode: a "File note" entry point and the pending tray.
                reviewActions = if (textState != null && reviewState.active) {
                    {
                        HeaderAction(
                            label = "File note",
                            testTag = FILE_VIEWER_REVIEW_FILE_NOTE_TAG,
                            onClick = { activeSheet = ReviewSheet.File },
                        )
                        HeaderAction(
                            label = "Review (${reviewState.pendingCount})",
                            testTag = FILE_VIEWER_REVIEW_TRAY_TAG,
                            onClick = { activeSheet = ReviewSheet.Tray },
                        )
                    }
                } else {
                    null
                },
                // Issue #764: an image preview gets a Markup toggle, shown next
                // to Save/Share/Copy (not replacing them).
                annotateAction = if (imageState != null) {
                    {
                        HeaderAction(
                            label = if (annotationState.active) "Markup: on" else "Markup",
                            testTag = FILE_VIEWER_ANNOTATE_TOGGLE_TAG,
                            onClick = onToggleAnnotateMode,
                        )
                    }
                } else {
                    null
                },
            )
            if (textState != null) {
                TextReadingToggleBar(
                    wordWrap = readingPrefs.wordWrap,
                    showMarkdownToggle = isMarkdown,
                    renderMarkdown = readingPrefs.renderMarkdown,
                    reviewActive = reviewState.active,
                    onToggleWordWrap = onToggleWordWrap,
                    onToggleRenderMarkdown = onToggleRenderMarkdown,
                    onToggleReviewMode = onToggleReviewMode,
                )
            }
            when (state) {
                is FileViewerUiState.Loading -> LoadingPanel()
                is FileViewerUiState.Image -> Column(modifier = Modifier.fillMaxSize()) {
                    ImagePanel(
                        cacheFile = state.cacheFile,
                        annotationState = annotationState,
                        onAddAnnotation = onAddAnnotation,
                        modifier = Modifier.weight(1f),
                    )
                    if (annotationState.active) {
                        AnnotationToolbar(
                            annotationState = annotationState,
                            onSetTool = onSetAnnotationTool,
                            onSetColor = onSetAnnotationColor,
                            onUndo = onUndoAnnotation,
                            onSubmit = onSubmitAnnotation,
                        )
                    }
                }
                is FileViewerUiState.TextContent -> if (reviewState.active) {
                    // Review mode forces the per-line source rows (markdown
                    // render is not line-addressable — #714 fallback); the
                    // file-level note still works from the header.
                    CommentableTextPanel(
                        content = state.content,
                        wordWrap = readingPrefs.wordWrap,
                        lineComments = reviewState.lineComments,
                        onLineTap = { line -> activeSheet = ReviewSheet.Line(line) },
                    )
                } else {
                    TextPanel(
                        content = state.content,
                        wordWrap = readingPrefs.wordWrap,
                        isMarkdown = isMarkdown,
                        renderMarkdown = readingPrefs.renderMarkdown,
                    )
                }
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
                        locateCandidates = state.locateCandidates,
                        onOpenLocated = onOpenLocated,
                    )
                }
            }
        }

        // Review sheets (issue #714). The content lines power the line sheet's
        // header snippet + the tray's per-row snippets.
        val lines = remember(textState?.content) {
            textState?.content?.split("\n") ?: emptyList()
        }
        ReviewSheets(
            activeSheet = activeSheet,
            reviewState = reviewState,
            lines = lines,
            onDismiss = { activeSheet = null },
            onSetLineComment = onSetLineComment,
            onDeleteLineComment = onDeleteLineComment,
            onSetFileComment = onSetFileComment,
            onDeleteFileComment = onDeleteFileComment,
            onOpenLine = { line -> activeSheet = ReviewSheet.Line(line) },
            onSubmitReview = {
                onSubmitReview()
                activeSheet = null
            },
        )

        // Issue #763 — post-Submit confirmation: the saved YAML path (copyable)
        // plus an "Attach to current session" action. Shown when the ViewModel
        // reports a successful submit; dismissing or attaching clears it.
        submittedReview?.let { success ->
            val savedSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ReviewSubmittedSheet(
                host = success.host,
                count = success.count,
                savedPath = success.remotePath,
                sheetState = savedSheetState,
                onCopyPath = { onCopyReviewPath(success.remotePath) },
                onAttach = { onAttachReview(success.remotePath) },
                onDismiss = onDismissSubmittedReview,
            )
        }

        // Issue #764 — post-Submit confirmation for an annotated image: the saved
        // PNG path (copyable) + Attach-to-session (#764 v2). The annotated PNG
        // landed in the host's annotations inbox; the orchestrator picks it up
        // like a screenshot, and Attach seeds the active session composer.
        submittedAnnotation?.let { success ->
            val savedSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            AnnotationSavedSheet(
                host = success.host,
                savedPath = success.remotePath,
                sheetState = savedSheetState,
                onCopyPath = { onCopyAnnotationPath(success.remotePath) },
                onAttach = { onAttachAnnotation(success.remotePath) },
                onDismiss = onDismissSubmittedAnnotation,
            )
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
 * Issue #762 slice C — the coarse [FileIconClass] for the viewer header's
 * leading icon. The viewer content-sniffs the bytes ([FileTypeDetector]), so for
 * a confirmed render type it knows the class better than the extension does: an
 * [FileViewerUiState.Image] is an IMAGE even if the suffix lied. For the rest
 * (text/pdf/audio/binary/still-loading) it falls back to the SAME shared
 * name-based map the explorer rows use ([fileIconClassForName]) so the glyph
 * matches the list. A text file maps to the CODE/text bucket, exactly as it does
 * in the explorer.
 */
private fun FileViewerUiState.fileIconClass(): FileIconClass = when (this) {
    is FileViewerUiState.Image -> FileIconClass.IMAGE
    is FileViewerUiState.TextContent -> FileIconClass.CODE
    else -> fileIconClassForName(displayPath())
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
    // Issue #762 slice C — the coarse file-type icon for the opened file, the
    // SAME shared [FileTypeIcon] the explorer rows lead with so a file reads
    // identically in the list and in the viewer (design-consistency).
    fileIconClass: FileIconClass,
    shareable: Shareable?,
    onBack: () -> Unit,
    reviewActions: (@Composable () -> Unit)? = null,
    // Issue #764 — the image Markup toggle, shown ALONGSIDE Save/Share/Copy for
    // an image (review mode's [reviewActions] still fully replaces the slot).
    annotateAction: (@Composable () -> Unit)? = null,
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
    // Issue #623: Save action added — writes the file to the Android Downloads
    // directory. Issue #719: via MediaStore.Downloads (scoped storage), since
    // DownloadManager.addCompletedDownload crashes on Android 13+.
    val context = LocalContext.current
    ScreenHeader(
        title = displayPath.substringAfterLast('/').ifEmpty { "File" },
        subtitle = displayPath.ifEmpty { hostName },
        titleTestTag = FILE_VIEWER_TITLE_TAG,
        modifier = Modifier.border(width = 1.dp, color = PocketShellColors.BorderSoft),
        leading = {
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                // Issue #762 slice C — the file-type icon sits between the back
                // chevron and the title, so the opened file leads with the same
                // glyph the explorer row showed for it.
                FileTypeIcon(
                    iconClass = fileIconClass,
                    modifier = Modifier.testTag(FILE_VIEWER_TYPE_ICON_TAG),
                )
            }
        },
        trailing = if (shareable == null && reviewActions == null && annotateAction == null) {
            null
        } else {
            {
                // Issue #714: in review mode the File-note + pending-tray
                // actions take the trailing slot (the share/copy/save chrome is
                // suppressed so the row stays uncrowded while reviewing).
                if (reviewActions != null) {
                    reviewActions()
                } else {
                    // Issue #764: for an image the Markup toggle sits next to the
                    // Save/Share/Copy actions (both stay reachable).
                    annotateAction?.invoke()
                    if (shareable != null) {
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
                }
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
    reviewActive: Boolean,
    onToggleWordWrap: () -> Unit,
    onToggleRenderMarkdown: () -> Unit,
    onToggleReviewMode: () -> Unit,
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
        // Issue #714: review-mode toggle. When on, the blob text view swaps to
        // the per-line commentable panel and the header gains the File-note +
        // pending-tray actions.
        ToggleChip(
            label = if (reviewActive) "Review: on" else "Review",
            active = reviewActive,
            testTag = FILE_VIEWER_REVIEW_TOGGLE_TAG,
            onClick = onToggleReviewMode,
        )
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
    val appContext = context.applicationContext
    saveScope.launch {
        val file = shareable.resolveFile(appContext)
        withContext(Dispatchers.Main) {
            if (file == null) {
                Toast.makeText(appContext, "Nothing to share", Toast.LENGTH_SHORT).show()
                return@withContext
            }
            val uri = runCatching {
                FileProvider.getUriForFile(appContext, appContext.packageName + ".fileprovider", file)
            }.getOrNull()
            if (uri == null) {
                Toast.makeText(appContext, "Couldn't prepare the file to share", Toast.LENGTH_SHORT).show()
                return@withContext
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = shareable.mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            appContext.startActivity(
                Intent.createChooser(intent, "Share ${file.name}").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }
    }
}

/**
 * Put the opened file on the clipboard as a content URI so it can be pasted
 * into apps that accept files (`ClipData.newUri`). The `ClipData` is built
 * through the `ContentResolver` so the receiving app inherits read access to
 * the `.fileprovider` URI.
 */
private fun copyFileToClipboard(context: Context, shareable: Shareable) {
    val appContext = context.applicationContext
    saveScope.launch {
        val file = shareable.resolveFile(appContext)
        withContext(Dispatchers.Main) {
            if (file == null) {
                Toast.makeText(appContext, "Nothing to copy", Toast.LENGTH_SHORT).show()
                return@withContext
            }
            val uri = runCatching {
                FileProvider.getUriForFile(appContext, appContext.packageName + ".fileprovider", file)
            }.getOrNull()
            if (uri == null) {
                Toast.makeText(appContext, "Couldn't prepare the file to copy", Toast.LENGTH_SHORT).show()
                return@withContext
            }
            val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (clipboard == null) {
                Toast.makeText(appContext, "Clipboard unavailable", Toast.LENGTH_SHORT).show()
                return@withContext
            }
            val clip = ClipData.newUri(appContext.contentResolver, file.name, uri)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(appContext, "Copied ${file.name} to clipboard", Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * Copy raw text to the clipboard as plain text. Used by the text viewer's
 * "Copy all" (default toast) and the #763 review-saved sheet's copyable path
 * (custom [toastLabel]).
 */
private fun copyTextToClipboard(
    context: Context,
    content: String,
    toastLabel: String = "Copied text to clipboard",
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    if (clipboard == null) {
        Toast.makeText(context, "Clipboard unavailable", Toast.LENGTH_SHORT).show()
        return
    }
    clipboard.setPrimaryClip(ClipData.newPlainText("file text", content))
    Toast.makeText(context, toastLabel, Toast.LENGTH_SHORT).show()
}

/** Background scope for the (fire-and-forget) Save IO — survives the click site. */
private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Issue #623 / #719 — save the viewed file to the public Downloads directory
 * via [MediaStore.Downloads] (scoped storage; no `WRITE_EXTERNAL_STORAGE`).
 *
 * The previous implementation used `DownloadManager.addCompletedDownload`,
 * which is deprecated since API 29 and **throws** on modern Android (the
 * internal MediaProvider insert rejects the legacy visibility value with
 * `SecurityException: Invalid value for visibility: 2`). It is removed
 * entirely (hard-cut, D22).
 *
 * The file content is already in memory: [Shareable.Text] carries the decoded
 * text, [Shareable.FileBacked] points at the already-cached preview file. We
 * reuse it — there is no re-fetch over SSH. The IO runs off the main thread on
 * [saveScope]; the success/failure toast is posted back on the main thread.
 *
 * On Q+ (API 29+) we insert into `MediaStore.Downloads.EXTERNAL_CONTENT_URI`
 * with `IS_PENDING=1`, stream the bytes to the returned URI, then clear
 * `IS_PENDING`. minSdk is 26, so a legacy
 * `Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)` path
 * covers pre-Q devices (no scoped storage there).
 */
private fun saveFileToLocal(context: Context, shareable: Shareable) {
    val fileName = downloadFileName(shareable.displayPath)
    val mimeType = shareable.mimeType
    val appContext = context.applicationContext
    saveScope.launch {
        val ok = runCatching {
            val bytes = shareable.readBytes()
                ?: error("nothing to save")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(appContext, fileName, mimeType, bytes)
            } else {
                saveToPublicDownloads(fileName, bytes)
            }
        }.isSuccess
        withContext(Dispatchers.Main) {
            val message =
                if (ok) "Saved to Downloads/$fileName" else "Couldn't save $fileName"
            Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * Q+ scoped-storage save: insert a pending Downloads entry, stream the bytes to
 * it, then mark it complete so it becomes visible to other apps. Throws on any
 * failure (a null insert URI, an unwritable stream) so the caller surfaces a
 * failure toast; the half-written pending row is cleaned up first.
 */
internal fun saveViaMediaStore(
    context: Context,
    fileName: String,
    mimeType: String,
    bytes: ByteArray,
): Uri {
    val resolver = context.contentResolver
    val values = downloadContentValues(fileName, mimeType, pending = true)
    val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ?: error("MediaStore insert returned null")
    try {
        resolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: error("openOutputStream returned null for $uri")
        resolver.update(uri, clearPendingValues(), null, null)
    } catch (t: Throwable) {
        runCatching { resolver.delete(uri, null, null) }
        throw t
    }
    return uri
}

/**
 * Pre-Q legacy save: write straight to the public Downloads directory. minSdk
 * is 26, so this branch only ever runs on API 26–28 where scoped storage does
 * not exist and the app holds `WRITE_EXTERNAL_STORAGE`.
 */
@Suppress("DEPRECATION")
private fun saveToPublicDownloads(fileName: String, bytes: ByteArray) {
    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        .apply { mkdirs() }
    File(dir, fileName).writeBytes(bytes)
}

/**
 * The MediaStore [ContentValues] for a Downloads insert. Pure (no Android
 * runtime) so it is unit-tested: the display name, MIME type, relative
 * Downloads path, and the pending flag. `MIME_TYPE` is omitted for the
 * octet-stream fallback so MediaStore can infer a type from the extension.
 */
internal fun downloadContentValues(
    fileName: String,
    mimeType: String,
    pending: Boolean,
): ContentValues = ContentValues().apply {
    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
    if (mimeType.isNotBlank() && mimeType != "application/octet-stream") {
        put(MediaStore.Downloads.MIME_TYPE, mimeType)
    }
    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
    put(MediaStore.Downloads.IS_PENDING, if (pending) 1 else 0)
}

/** The `IS_PENDING=0` update that publishes a finished Downloads entry. */
internal fun clearPendingValues(): ContentValues = ContentValues().apply {
    put(MediaStore.Downloads.IS_PENDING, 0)
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
 * Used by Share / Copy, which need a `File` to hand to the FileProvider.
 */
private fun Shareable.resolveFile(context: Context): File? = when (this) {
    is Shareable.FileBacked -> cacheFile.takeIf { it.exists() }
    is Shareable.Text -> runCatching { materialize(context) }.getOrNull()
}

/**
 * The bytes to save for a [Shareable]: the already-cached preview file's
 * contents for image/PDF/audio, or the in-memory text encoded as UTF-8 for a
 * text file (no re-fetch over SSH). Returns null only when the cache file is
 * missing (surfaced as a failure toast by the caller).
 */
private fun Shareable.readBytes(): ByteArray? = when (this) {
    is Shareable.FileBacked -> cacheFile.takeIf { it.exists() }?.readBytes()
    is Shareable.Text -> content.toByteArray(Charsets.UTF_8)
}

@Composable
private fun LoadingPanel() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag(FILE_VIEWER_LOADING_TAG),
        contentAlignment = Alignment.Center,
    ) {
        LoadingIndicator.Spinner()
    }
}

/**
 * Zoom/pan image view (with an annotation overlay — issue #764).
 *
 * Decodes the cached file to a bitmap and, in the default (non-annotate) mode,
 * binds pinch (scale) + drag (translate) transform gestures via `graphicsLayer`.
 *
 * Annotate mode (#764): the image is pinned to `ContentScale.Fit` at zoom=1 /
 * pan=0 (so the screen↔source coordinate mapping is exactly [ImageFitMapping] —
 * no zoom term), and a Compose `Canvas` overlay captures Pen/Arrow drags +
 * re-draws every committed annotation. Each captured screen point is mapped to
 * source-bitmap pixels via [ImageFitMapping] before commit, so the flattened
 * export matches what the user drew. The `Pan` tool re-enables pinch/pan.
 */
@Composable
private fun ImagePanel(
    cacheFile: File,
    annotationState: ImageAnnotationState = ImageAnnotationState(),
    onAddAnnotation: (Annotation) -> Unit = {},
    modifier: Modifier = Modifier,
) {
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

    val annotateActive = annotationState.active
    // Pen/Arrow/Rect/Circle draw with a drag; Text places with a tap; Pan pans.
    val dragDrawing = annotateActive &&
        annotationState.tool != AnnotationTool.Pan &&
        annotationState.tool != AnnotationTool.Text
    val textPlacing = annotateActive && annotationState.tool == AnnotationTool.Text

    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    // Entering annotate mode pins the view to fit-scale so the coordinate map is
    // the pure letterbox transform; the Pan tool can still pinch/zoom.
    LaunchedEffect(annotateActive) {
        if (annotateActive) {
            scale = 1f; offsetX = 0f; offsetY = 0f
        }
    }

    // Viewport size (px) — captured so the source↔screen mapping is exact.
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    val mapping = remember(viewportSize, bitmap.width, bitmap.height) {
        if (viewportSize.width > 0 && viewportSize.height > 0) {
            ImageFitMapping.of(
                sourceWidth = bitmap.width,
                sourceHeight = bitmap.height,
                viewportWidth = viewportSize.width.toFloat(),
                viewportHeight = viewportSize.height.toFloat(),
            )
        } else {
            null
        }
    }

    // The in-progress stroke (screen points), committed to source space on drag-end.
    val livePoints = remember { mutableStateListOf<Offset>() }

    // Issue #764 v2 — Text tool: a tap captures the source-space anchor, then a
    // dialog enters the label which is committed as an Annotation.Text.
    var pendingTextAnchor by remember { mutableStateOf<ImagePoint?>(null) }

    val gestureModifier = if (dragDrawing && mapping != null) {
        Modifier.pointerInput(annotationState.tool, mapping, annotationState.colorArgb) {
            detectDragGestures(
                onDragStart = { start ->
                    livePoints.clear()
                    livePoints.add(start)
                },
                onDrag = { change, _ ->
                    change.consume()
                    livePoints.add(change.position)
                },
                onDragEnd = {
                    commitStroke(annotationState, mapping, livePoints.toList(), onAddAnnotation)
                    livePoints.clear()
                },
                onDragCancel = { livePoints.clear() },
            )
        }
    } else if (textPlacing && mapping != null) {
        Modifier.pointerInput(mapping) {
            detectTapGestures { tap ->
                pendingTextAnchor = mapping.screenToSource(tap.x, tap.y)
            }
        }
    } else if (!annotateActive) {
        Modifier.pointerInput(Unit) {
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
        }
    } else {
        // Annotate mode + Pan tool: pinch/pan the pinned image.
        Modifier.pointerInput(Unit) {
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
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PocketShellColors.TermBg)
            .onSizeChanged { viewportSize = it }
            .then(gestureModifier),
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

        // Annotation overlay — committed annotations + the live stroke, drawn in
        // screen space (source → screen via the mapping) so it tracks the image.
        if (annotateActive && mapping != null) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(FILE_VIEWER_ANNOTATE_CANVAS_TAG),
            ) {
                annotationState.annotations.forEach { drawAnnotationOnCanvas(it, mapping) }
                drawLiveStroke(annotationState, livePoints)
            }
        }
    }

    // Issue #764 v2 — Text entry: once the user taps to anchor a label, prompt
    // for the text and commit it as an Annotation.Text in source-pixel space.
    val anchor = pendingTextAnchor
    if (anchor != null && mapping != null) {
        AnnotationTextDialog(
            onConfirm = { entered ->
                if (entered.isNotBlank()) {
                    onAddAnnotation(
                        Annotation.Text(
                            text = entered,
                            anchor = anchor,
                            textSizePx = mapping.screenToSourceLength(ANNOTATION_TEXT_SCREEN_SIZE),
                            colorArgb = annotationState.colorArgb,
                        ),
                    )
                }
                pendingTextAnchor = null
            },
            onDismiss = { pendingTextAnchor = null },
        )
    }
}

/**
 * Modal text-entry for a Text annotation (#764 v2). Returns the entered string
 * on confirm; an empty string is dropped by the caller.
 */
@Composable
private fun AnnotationTextDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PocketShellColors.Surface,
        title = {
            Text(
                text = "Add text",
                style = PocketShellType.bodyDense,
                fontWeight = FontWeight.SemiBold,
                color = PocketShellColors.Text,
            )
        },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = false,
                placeholder = {
                    Text(
                        text = "Label…",
                        style = PocketShellType.bodyDense,
                        color = PocketShellColors.TextMuted,
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(FILE_VIEWER_ANNOTATE_TEXT_FIELD_TAG),
            )
        },
        confirmButton = {
            PocketShellButton(
                text = "Add",
                onClick = { onConfirm(text) },
                variant = ButtonVariant.Primary,
                modifier = Modifier.testTag(FILE_VIEWER_ANNOTATE_TEXT_CONFIRM_TAG),
            )
        },
        dismissButton = {
            PocketShellButton(
                text = "Cancel",
                onClick = onDismiss,
                variant = ButtonVariant.Secondary,
            )
        },
    )
}

/**
 * Commit the captured [screenPoints] of a finished drag as an [Annotation] in
 * source-pixel space (issue #764). Pen → a [Annotation.Freehand] of every
 * sampled point; Arrow → a [Annotation.Arrow]; Rect/Circle → the bounding box of
 * the drag (first→last point) as a [Annotation.Rectangle]/[Annotation.Circle].
 * The stroke width is a fixed screen width converted to source pixels so it
 * scales with the image in the flattened export. A degenerate stroke (no points,
 * or a shape with start==end) is dropped. The Text tool commits on tap, not here.
 */
private fun commitStroke(
    state: ImageAnnotationState,
    mapping: ImageFitMapping,
    screenPoints: List<Offset>,
    onAddAnnotation: (Annotation) -> Unit,
) {
    if (screenPoints.isEmpty()) return
    val strokeWidthPx = mapping.screenToSourceLength(ANNOTATION_STROKE_SCREEN_WIDTH)
    val start = mapping.screenToSource(screenPoints.first().x, screenPoints.first().y)
    val end = mapping.screenToSource(screenPoints.last().x, screenPoints.last().y)
    when (state.tool) {
        AnnotationTool.Pen -> {
            val pts = screenPoints.map { mapping.screenToSource(it.x, it.y) }
            onAddAnnotation(Annotation.Freehand(points = pts, colorArgb = state.colorArgb, strokeWidthPx = strokeWidthPx))
        }
        AnnotationTool.Arrow ->
            if (start != end) {
                onAddAnnotation(Annotation.Arrow(start = start, end = end, colorArgb = state.colorArgb, strokeWidthPx = strokeWidthPx))
            }
        AnnotationTool.Rect ->
            if (start != end) {
                onAddAnnotation(Annotation.Rectangle(start = start, end = end, colorArgb = state.colorArgb, strokeWidthPx = strokeWidthPx))
            }
        AnnotationTool.Circle ->
            if (start != end) {
                onAddAnnotation(Annotation.Circle(start = start, end = end, colorArgb = state.colorArgb, strokeWidthPx = strokeWidthPx))
            }
        AnnotationTool.Text, AnnotationTool.Pan -> Unit
    }
}

/** Fixed on-screen annotation stroke width (dp-ish px); mapped to source pixels. */
private const val ANNOTATION_STROKE_SCREEN_WIDTH = 8f

/** Fixed on-screen text size for a Text annotation; mapped to source pixels. */
private const val ANNOTATION_TEXT_SCREEN_SIZE = 48f

/** Draw a committed [annotation] (in source space) onto the screen-space canvas. */
private fun DrawScope.drawAnnotationOnCanvas(annotation: Annotation, mapping: ImageFitMapping) {
    val color = ComposeColor(annotation.colorArgb)
    val widthPx = annotation.strokeWidthPx * mapping.scale
    when (annotation) {
        is Annotation.Freehand -> {
            val pts = annotation.points
            if (pts.isEmpty()) return
            if (pts.size == 1) {
                val (cx, cy) = mapping.sourceToScreen(pts[0])
                drawCircle(color = color, radius = widthPx / 2f, center = Offset(cx, cy))
                return
            }
            val path = Path()
            val (x0, y0) = mapping.sourceToScreen(pts[0])
            path.moveTo(x0, y0)
            for (i in 1 until pts.size) {
                val (x, y) = mapping.sourceToScreen(pts[i])
                path.lineTo(x, y)
            }
            drawPath(path, color = color, style = Stroke(width = widthPx, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        is Annotation.Arrow -> {
            val (sx, sy) = mapping.sourceToScreen(annotation.start)
            val (ex, ey) = mapping.sourceToScreen(annotation.end)
            drawArrowOnCanvas(Offset(sx, sy), Offset(ex, ey), color, widthPx)
        }
        is Annotation.Rectangle -> {
            val (sx, sy) = mapping.sourceToScreen(annotation.start)
            val (ex, ey) = mapping.sourceToScreen(annotation.end)
            drawRectOnCanvas(Offset(sx, sy), Offset(ex, ey), color, widthPx)
        }
        is Annotation.Circle -> {
            val (sx, sy) = mapping.sourceToScreen(annotation.start)
            val (ex, ey) = mapping.sourceToScreen(annotation.end)
            drawEllipseOnCanvas(Offset(sx, sy), Offset(ex, ey), color, widthPx)
        }
        is Annotation.Text -> {
            val (ax, ay) = mapping.sourceToScreen(annotation.anchor)
            drawTextOnCanvas(annotation.text, Offset(ax, ay), color, annotation.textSizePx * mapping.scale)
        }
    }
}

/** Draw the live (in-progress) stroke in screen space while the user drags. */
private fun DrawScope.drawLiveStroke(state: ImageAnnotationState, screenPoints: List<Offset>) {
    if (screenPoints.isEmpty()) return
    val color = ComposeColor(state.colorArgb)
    val widthPx = ANNOTATION_STROKE_SCREEN_WIDTH
    when (state.tool) {
        AnnotationTool.Pen -> {
            if (screenPoints.size == 1) {
                drawCircle(color = color, radius = widthPx / 2f, center = screenPoints[0])
                return
            }
            val path = Path().apply {
                moveTo(screenPoints[0].x, screenPoints[0].y)
                for (i in 1 until screenPoints.size) lineTo(screenPoints[i].x, screenPoints[i].y)
            }
            drawPath(path, color = color, style = Stroke(width = widthPx, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        AnnotationTool.Arrow -> {
            drawArrowOnCanvas(screenPoints.first(), screenPoints.last(), color, widthPx)
        }
        AnnotationTool.Rect -> {
            drawRectOnCanvas(screenPoints.first(), screenPoints.last(), color, widthPx)
        }
        AnnotationTool.Circle -> {
            drawEllipseOnCanvas(screenPoints.first(), screenPoints.last(), color, widthPx)
        }
        AnnotationTool.Text, AnnotationTool.Pan -> Unit
    }
}

/** Draw a rectangle outline (normalised corners) on the screen-space canvas. */
private fun DrawScope.drawRectOnCanvas(a: Offset, b: Offset, color: ComposeColor, widthPx: Float) {
    val left = minOf(a.x, b.x)
    val top = minOf(a.y, b.y)
    drawRect(
        color = color,
        topLeft = Offset(left, top),
        size = androidx.compose.ui.geometry.Size(kotlin.math.abs(b.x - a.x), kotlin.math.abs(b.y - a.y)),
        style = Stroke(width = widthPx, join = StrokeJoin.Round),
    )
}

/** Draw an ellipse outline inscribed in the corner box on the screen-space canvas. */
private fun DrawScope.drawEllipseOnCanvas(a: Offset, b: Offset, color: ComposeColor, widthPx: Float) {
    val left = minOf(a.x, b.x)
    val top = minOf(a.y, b.y)
    drawOval(
        color = color,
        topLeft = Offset(left, top),
        size = androidx.compose.ui.geometry.Size(kotlin.math.abs(b.x - a.x), kotlin.math.abs(b.y - a.y)),
        style = Stroke(width = widthPx, join = StrokeJoin.Round),
    )
}

/** Draw a text label (multi-line aware) at [anchor] on the screen-space canvas. */
private fun DrawScope.drawTextOnCanvas(text: String, anchor: Offset, color: ComposeColor, textSizePx: Float) {
    if (text.isEmpty()) return
    val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        this.color = color.toArgb()
        style = android.graphics.Paint.Style.FILL
        this.textSize = textSizePx
        isFakeBoldText = true
    }
    val baseline = anchor.y - paint.fontMetrics.ascent
    val lineHeight = paint.fontSpacing
    drawIntoCanvas { canvas ->
        text.split('\n').forEachIndexed { i, line ->
            canvas.nativeCanvas.drawText(line, anchor.x, baseline + i * lineHeight, paint)
        }
    }
}

/** Draw an arrow (shaft + two head wings) on the screen-space canvas. */
private fun DrawScope.drawArrowOnCanvas(start: Offset, end: Offset, color: ComposeColor, widthPx: Float) {
    drawLine(color = color, start = start, end = end, strokeWidth = widthPx, cap = StrokeCap.Round)
    val angle = kotlin.math.atan2((end.y - start.y).toDouble(), (end.x - start.x).toDouble())
    val wing = maxOf(widthPx * 4f, 28f)
    val a1 = angle + Math.PI - 0.5
    val a2 = angle + Math.PI + 0.5
    drawLine(
        color = color,
        start = end,
        end = Offset(end.x + (wing * kotlin.math.cos(a1)).toFloat(), end.y + (wing * kotlin.math.sin(a1)).toFloat()),
        strokeWidth = widthPx,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = color,
        start = end,
        end = Offset(end.x + (wing * kotlin.math.cos(a2)).toFloat(), end.y + (wing * kotlin.math.sin(a2)).toFloat()),
        strokeWidth = widthPx,
        cap = StrokeCap.Round,
    )
}

/**
 * The annotate-mode bottom toolbar (issue #764): tool toggles (Pan/Pen/Arrow/
 * Rect/Circle/Text), a fixed colour swatch row, Undo, and Submit. ui-kit-token
 * controls; the selected tool/colour gets an accent tint. Submit is enabled only
 * when there is at least one annotation and no submit is in flight.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnnotationToolbar(
    annotationState: ImageAnnotationState,
    onSetTool: (AnnotationTool) -> Unit,
    onSetColor: (Int) -> Unit,
    onUndo: () -> Unit,
    onSubmit: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PocketShellColors.Surface)
            .border(width = 1.dp, color = PocketShellColors.BorderSoft)
            // Keep Done + the tool row above the system navigation bar so every
            // control stays fully visible and tappable (#641 reachability).
            .navigationBarsPadding()
            .padding(horizontal = PocketShellSpacing.sm, vertical = PocketShellSpacing.xs),
        verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.xs),
    ) {
        // Tool row — six tools + the swatch palette. Wrap so every control stays
        // fully visible/tappable on a narrow phone (#641 reachability).
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(PocketShellSpacing.xs),
            verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.xs),
        ) {
            ToolToggle("Pan", annotationState.tool == AnnotationTool.Pan, FILE_VIEWER_ANNOTATE_TOOL_PAN_TAG) { onSetTool(AnnotationTool.Pan) }
            ToolToggle("Pen", annotationState.tool == AnnotationTool.Pen, FILE_VIEWER_ANNOTATE_TOOL_PEN_TAG) { onSetTool(AnnotationTool.Pen) }
            ToolToggle("Arrow", annotationState.tool == AnnotationTool.Arrow, FILE_VIEWER_ANNOTATE_TOOL_ARROW_TAG) { onSetTool(AnnotationTool.Arrow) }
            ToolToggle("Rect", annotationState.tool == AnnotationTool.Rect, FILE_VIEWER_ANNOTATE_TOOL_RECT_TAG) { onSetTool(AnnotationTool.Rect) }
            ToolToggle("Circle", annotationState.tool == AnnotationTool.Circle, FILE_VIEWER_ANNOTATE_TOOL_CIRCLE_TAG) { onSetTool(AnnotationTool.Circle) }
            ToolToggle("Text", annotationState.tool == AnnotationTool.Text, FILE_VIEWER_ANNOTATE_TOOL_TEXT_TAG) { onSetTool(AnnotationTool.Text) }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PocketShellSpacing.xs),
        ) {
            ImageAnnotationState.SWATCHES.forEach { argb ->
                ColorSwatch(argb = argb, selected = annotationState.colorArgb == argb) { onSetColor(argb) }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm),
        ) {
            HeaderAction(
                label = "Undo",
                testTag = FILE_VIEWER_ANNOTATE_UNDO_TAG,
                onClick = onUndo,
            )
            Spacer(modifier = Modifier.weight(1f))
            val canSubmit = annotationState.hasAnnotations && !annotationState.submitting
            Box(
                modifier = Modifier
                    .height(PocketShellDensity.tapTargetMin)
                    .background(
                        color = if (canSubmit) PocketShellColors.Accent else PocketShellColors.SurfaceElev,
                        shape = RoundedCornerShape(8.dp),
                    )
                    .clickable(enabled = canSubmit, role = Role.Button, onClick = onSubmit)
                    .padding(horizontal = PocketShellSpacing.lg)
                    .testTag(FILE_VIEWER_ANNOTATE_SUBMIT_TAG),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (annotationState.submitting) "Sending…" else "Done",
                    color = if (canSubmit) PocketShellColors.OnAccent else PocketShellColors.TextMuted,
                    style = PocketShellType.bodyDense,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** A tool toggle pill for the annotate toolbar. */
@Composable
private fun ToolToggle(label: String, active: Boolean, testTag: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(PocketShellDensity.tapTargetMin)
            .background(
                color = if (active) PocketShellColors.AccentSoft else PocketShellColors.SurfaceElev,
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

/** A colour swatch for the annotate toolbar; the selected one gets an accent ring. */
@Composable
private fun ColorSwatch(argb: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(fileViewerAnnotateSwatchTag(argb)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(if (selected) 24.dp else 20.dp)
                .background(color = ComposeColor(argb), shape = CircleShape)
                .then(
                    if (selected) {
                        Modifier.border(width = 2.dp, color = PocketShellColors.Text, shape = CircleShape)
                    } else {
                        Modifier
                    },
                ),
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
                rendering -> LoadingIndicator.Spinner()
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
                LoadingIndicator.Spinner()
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
 * saves the cached file to the Android Downloads directory (via
 * [saveFileToLocal] / MediaStore). This is shown instead of the "Can't preview"
 * message when the file was downloaded but can't be previewed (binary type).
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
        // Issue #762 slice C — the same shared file-type icon leads the
        // download-only card so the (un-previewable) file still reads by type.
        FileTypeIcon(
            iconClass = fileIconClassForName(fileName),
            sizeDp = 40,
            modifier = Modifier.testTag(FILE_VIEWER_DOWNLOAD_TYPE_ICON_TAG),
        )
        Spacer(Modifier.height(12.dp))
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
    locateCandidates: List<String> = emptyList(),
    onOpenLocated: (String) -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 24.dp)
            .verticalScroll(rememberScrollState())
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
        // Issue #748 — the agent referenced a relative path that doesn't exist
        // under the session cwd, but same-named files were found deeper in the
        // tree (it worked in a subdirectory). Offer to open them instead of a
        // dead-end Retry.
        if (locateCandidates.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            locateCandidates.forEach { candidate ->
                PocketShellButton(
                    onClick = { onOpenLocated(candidate) },
                    variant = ButtonVariant.Text,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(FILE_VIEWER_LOCATE_CANDIDATE_TAG),
                ) {
                    Text(
                        text = candidate,
                        color = PocketShellColors.Accent,
                        fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        if (showRetry) {
            Spacer(Modifier.height(4.dp))
            PocketShellButton(
                onClick = onRetry,
                variant = ButtonVariant.Text,
                modifier = Modifier.testTag(FILE_VIEWER_RETRY_TAG),
            ) {
                Text("Retry", color = PocketShellColors.Accent)
            }
        }
    }
}
