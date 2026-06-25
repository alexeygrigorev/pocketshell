package com.pocketshell.app.fileviewer

import android.content.ClipboardManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.proof.signals.assertNodeFullyWithinRoot
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Stateless UI tests for the file viewer scaffold (issue #497) — drive every
 * render state without an SSH session.
 */
@RunWith(AndroidJUnit4::class)
class FileViewerScaffoldTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun loadingStateShowsSpinner() {
        compose.setContent {
            PocketShellTheme {
                FileViewerScaffold(
                    hostName = "agents",
                    state = FileViewerUiState.Loading("/tmp/a.png"),
                    onBack = {},
                    onRetry = {},
                )
            }
        }
        compose.assertNodeFullyWithinRoot(FILE_VIEWER_LOADING_TAG)
    }

    @Test
    fun textStateShowsContent() {
        compose.setContent {
            PocketShellTheme {
                FileViewerScaffold(
                    hostName = "agents",
                    state = FileViewerUiState.TextContent(
                        displayPath = "/tmp/notes.txt",
                        content = "hello from issue 497",
                        sizeBytes = 20,
                    ),
                    onBack = {},
                    onRetry = {},
                )
            }
        }
        compose.assertNodeFullyWithinRoot(FILE_VIEWER_TEXT_TAG)
        compose.onNodeWithText("hello from issue 497").assertIsDisplayed()
    }

    @Test
    fun cannotPreviewStateShowsMessageAndRetry() {
        var retries = 0
        compose.setContent {
            PocketShellTheme {
                FileViewerScaffold(
                    hostName = "agents",
                    state = FileViewerUiState.CannotPreview(
                        displayPath = "/tmp/big.bin",
                        message = "File is too large to preview (limit 20 MB).",
                    ),
                    onBack = {},
                    onRetry = { retries++ },
                )
            }
        }
        compose.assertNodeFullyWithinRoot(FILE_VIEWER_CANNOT_PREVIEW_TAG)
        compose.onNodeWithText("File is too large to preview (limit 20 MB).").assertIsDisplayed()
        compose.onNodeWithTag(FILE_VIEWER_RETRY_TAG).performClick()
        compose.waitForIdle()
        assertEquals(1, retries)
    }

    @Test
    fun cannotPreviewWithLocateCandidatesOffersOpenRows() {
        // Issue #748 — a relative path missing under the session cwd but found
        // deeper in the tree offers candidate rows; tapping one opens it.
        val candidate =
            "/home/alexey/git/3d-models/matchbox-mattel-atv-6x6/renders/white_bathtub_3d_preview.png"
        var opened: String? = null
        compose.setContent {
            PocketShellTheme {
                FileViewerScaffold(
                    hostName = "agents",
                    state = FileViewerUiState.CannotPreview(
                        displayPath = "/home/alexey/git/3d-models/renders/white_bathtub_3d_preview.png",
                        message = "Couldn't find white_bathtub_3d_preview.png where the agent " +
                            "referenced it, but it exists elsewhere in this project. Open it instead?",
                        locateCandidates = listOf(candidate),
                    ),
                    onBack = {},
                    onRetry = {},
                    onOpenLocated = { opened = it },
                )
            }
        }
        compose.assertNodeFullyWithinRoot(FILE_VIEWER_CANNOT_PREVIEW_TAG)
        compose.onNodeWithText(candidate).assertIsDisplayed()
        compose.assertNodeFullyWithinRoot(FILE_VIEWER_LOCATE_CANDIDATE_TAG)
        compose.onNodeWithTag(FILE_VIEWER_LOCATE_CANDIDATE_TAG).performClick()
        compose.waitForIdle()
        assertEquals(candidate, opened)
    }

    @Test
    fun audioStateShowsPlayerControls() {
        // Seed a real, valid (silent PCM) WAV so MediaPlayer prepares cleanly
        // and the panel renders its play/pause control, seekbar, and time
        // labels (issue #499).
        val cacheDir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val audioFile = File(cacheDir, "issue499-scaffold-audio.wav").apply {
            if (exists()) delete()
            writeBytes(silentWavBytes(millis = 500))
        }
        compose.setContent {
            PocketShellTheme {
                FileViewerScaffold(
                    hostName = "agents",
                    state = FileViewerUiState.Audio(
                        displayPath = "/tmp/clip.wav",
                        cacheFile = audioFile,
                        sizeBytes = audioFile.length(),
                    ),
                    onBack = {},
                    onRetry = {},
                )
            }
        }
        compose.waitForIdle()
        compose.assertNodeFullyWithinRoot(FILE_VIEWER_AUDIO_TAG)
        compose.assertNodeFullyWithinRoot(FILE_VIEWER_AUDIO_PLAY_PAUSE_TAG)
        compose.onNodeWithTag(FILE_VIEWER_AUDIO_SEEKBAR_TAG).assertExists()
        audioFile.delete()
    }

    // ---- Issue #696: word-wrap toggle + Markdown render-vs-raw ----

    @Test
    fun textStateShowsWrapToggleButNotMarkdownToggle() {
        compose.setContent {
            PocketShellTheme {
                FileViewerScaffold(
                    hostName = "agents",
                    state = FileViewerUiState.TextContent(
                        displayPath = "/tmp/main.kt",
                        content = "fun main() {}",
                        sizeBytes = 13,
                    ),
                    onBack = {},
                    onRetry = {},
                )
            }
        }
        compose.assertNodeFullyWithinRoot(FILE_VIEWER_WRAP_TAG)
        compose.onNodeWithTag(FILE_VIEWER_RENDER_MD_TAG).assertDoesNotExist()
    }

    @Test
    fun wrapToggleInvokesCallback() {
        var toggles = 0
        compose.setContent {
            PocketShellTheme {
                FileViewerScaffold(
                    hostName = "agents",
                    state = FileViewerUiState.TextContent(
                        displayPath = "/tmp/long.log",
                        content = "a very long line ".repeat(40),
                        sizeBytes = 700,
                    ),
                    readingPrefs = FileViewerReadingPrefs(wordWrap = false, renderMarkdown = true),
                    onBack = {},
                    onRetry = {},
                    onToggleWordWrap = { toggles++ },
                )
            }
        }
        compose.onNodeWithTag(FILE_VIEWER_WRAP_TAG).performClick()
        compose.waitForIdle()
        assertEquals(1, toggles)
    }

    @Test
    fun markdownFileRenderedShowsFormattedAndMarkdownToggle() {
        compose.setContent {
            PocketShellTheme {
                FileViewerScaffold(
                    hostName = "agents",
                    state = FileViewerUiState.TextContent(
                        displayPath = "/tmp/README.md",
                        content = "# Heading One\n\nSome **bold** body text.\n\n" +
                            "```python\nprint('hi')\n```",
                        sizeBytes = 60,
                    ),
                    readingPrefs = FileViewerReadingPrefs(wordWrap = false, renderMarkdown = true),
                    onBack = {},
                    onRetry = {},
                )
            }
        }
        // Markdown toggle is offered for a .md file.
        compose.assertNodeFullyWithinRoot(FILE_VIEWER_RENDER_MD_TAG)
        // The rendered Markdown surface is shown (heading text is present).
        compose.assertNodeFullyWithinRoot(FILE_VIEWER_MARKDOWN_TAG)
        compose.onNodeWithText("Heading One").assertIsDisplayed()
    }

    @Test
    fun markdownFileRawShowsSourceNotRendered() {
        compose.setContent {
            PocketShellTheme {
                FileViewerScaffold(
                    hostName = "agents",
                    state = FileViewerUiState.TextContent(
                        displayPath = "/tmp/README.md",
                        content = "# Heading One\n\nbody",
                        sizeBytes = 20,
                    ),
                    readingPrefs = FileViewerReadingPrefs(wordWrap = false, renderMarkdown = false),
                    onBack = {},
                    onRetry = {},
                )
            }
        }
        // Raw mode: the monospace text surface shows the literal `#` source and
        // the rendered Markdown surface is absent.
        compose.onNodeWithTag(FILE_VIEWER_MARKDOWN_TAG).assertDoesNotExist()
        compose.onNodeWithText("# Heading One\n\nbody").assertIsDisplayed()
    }

    /**
     * Issue #921: a GitHub-flavored pipe table in rendered Markdown mode shows
     * laid-out table cells, NOT the raw `|---|---|` delimiter text. Before the
     * fix the whole table parsed as a raw paragraph, so the literal delimiter
     * row "|------|-------|" appeared on screen as text.
     */
    @Test
    fun markdownRenderedPipeTableShowsCellsNotRawDelimiter() {
        compose.setContent {
            PocketShellTheme {
                FileViewerScaffold(
                    hostName = "agents",
                    state = FileViewerUiState.TextContent(
                        displayPath = "/tmp/report.md",
                        content = "# Benchmark\n\n" +
                            "| Model | Score |\n" +
                            "|-------|-------|\n" +
                            "| gpt-4 | 92 |\n" +
                            "| haiku | 81 |\n",
                        sizeBytes = 90,
                    ),
                    readingPrefs = FileViewerReadingPrefs(wordWrap = false, renderMarkdown = true),
                    onBack = {},
                    onRetry = {},
                )
            }
        }
        // The rendered table surface is present and visible.
        compose.assertNodeFullyWithinRoot(FILE_VIEWER_MARKDOWN_TABLE_TAG)
        // The header + body cells render as their own text nodes.
        compose.onNodeWithText("Model").assertIsDisplayed()
        compose.onNodeWithText("Score").assertIsDisplayed()
        compose.onNodeWithText("gpt-4").assertIsDisplayed()
        compose.onNodeWithText("92").assertIsDisplayed()
        compose.onNodeWithText("haiku").assertIsDisplayed()
        // The raw delimiter row must NOT appear as literal text.
        compose.onNodeWithText("|-------|-------|").assertDoesNotExist()
        compose.onNodeWithText("| Model | Score |").assertDoesNotExist()
    }

    /**
     * Build a minimal valid PCM WAV of [millis] of silence (16-bit mono, 8 kHz)
     * so MediaPlayer can prepare it without a codec — enough to exercise the
     * panel's control surface in a unit-scope Compose test.
     */
    private fun silentWavBytes(millis: Int): ByteArray {
        val sampleRate = 8000
        val numSamples = sampleRate * millis / 1000
        val dataSize = numSamples * 2 // 16-bit mono
        val out = java.io.ByteArrayOutputStream()
        fun writeIntLE(v: Int) {
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
            out.write((v shr 16) and 0xFF)
            out.write((v shr 24) and 0xFF)
        }
        fun writeShortLE(v: Int) {
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
        }
        out.write("RIFF".toByteArray())
        writeIntLE(36 + dataSize)
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        writeIntLE(16) // PCM fmt chunk size
        writeShortLE(1) // PCM
        writeShortLE(1) // mono
        writeIntLE(sampleRate)
        writeIntLE(sampleRate * 2) // byte rate
        writeShortLE(2) // block align
        writeShortLE(16) // bits per sample
        out.write("data".toByteArray())
        writeIntLE(dataSize)
        repeat(dataSize) { out.write(0) }
        return out.toByteArray()
    }

    // ---- Issue #559: "act on the opened file" (Share / Copy / selectable text) ----

    @Test
    fun textStateShowsShareCopyAndCopyAllActions() {
        compose.setContent {
            PocketShellTheme {
                FileViewerScaffold(
                    hostName = "agents",
                    state = FileViewerUiState.TextContent(
                        displayPath = "/tmp/notes.txt",
                        content = "selectable body for issue 559",
                        sizeBytes = 29,
                    ),
                    onBack = {},
                    onRetry = {},
                )
            }
        }
        // Header Save + Share + Copy (file URI) actions are present for a previewable file.
        compose.assertNodeFullyWithinRoot(FILE_VIEWER_SAVE_TAG)
        compose.assertNodeFullyWithinRoot(FILE_VIEWER_SHARE_TAG)
        compose.assertNodeFullyWithinRoot(FILE_VIEWER_COPY_TAG)
        // The text viewer exposes a one-tap "Copy all".
        compose.assertNodeFullyWithinRoot(FILE_VIEWER_COPY_ALL_TAG)
        compose.onNodeWithText("selectable body for issue 559").assertIsDisplayed()
    }

    @Test
    fun copyAllPutsTextOnTheClipboard() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        compose.setContent {
            PocketShellTheme {
                FileViewerScaffold(
                    hostName = "agents",
                    state = FileViewerUiState.TextContent(
                        displayPath = "/tmp/copyme.txt",
                        content = "clipboard payload 559",
                        sizeBytes = 21,
                    ),
                    onBack = {},
                    onRetry = {},
                )
            }
        }
        compose.onNodeWithTag(FILE_VIEWER_COPY_ALL_TAG).performClick()
        compose.waitForIdle()
        // Read the primary clip back from the app context's clipboard.
        var clipText: String? = null
        instrumentation.runOnMainSync {
            val cm = instrumentation.targetContext
                .getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipText = cm.primaryClip?.getItemAt(0)?.text?.toString()
        }
        assertEquals("clipboard payload 559", clipText)
    }

    @Test
    fun imageStateOffersTheMarkupToggle() {
        // Issue #764 — an image preview gets the Markup header action.
        val cacheDir = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "file-viewer",
        ).apply { mkdirs() }
        val imageFile = File(cacheDir, "issue764-toggle.png").apply {
            if (exists()) delete()
            writeBytes(onePixelPngBytes())
        }
        compose.setContent {
            PocketShellTheme {
                FileViewerScaffold(
                    hostName = "agents",
                    state = FileViewerUiState.Image(
                        displayPath = "/tmp/shot.png",
                        cacheFile = imageFile,
                        sizeBytes = imageFile.length(),
                    ),
                    onBack = {},
                    onRetry = {},
                )
            }
        }
        compose.assertNodeFullyWithinRoot(FILE_VIEWER_ANNOTATE_TOGGLE_TAG)
        imageFile.delete()
    }

    @Test
    fun annotateModeShowsTheToolbarAndSubmitGatesOnAnnotations() {
        // Issue #764 — in annotate mode the toolbar (tools + swatches + Undo +
        // Done) is shown; Submit fires only with at least one annotation.
        val cacheDir = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "file-viewer",
        ).apply { mkdirs() }
        val imageFile = File(cacheDir, "issue764-toolbar.png").apply {
            if (exists()) delete()
            writeBytes(onePixelPngBytes())
        }
        var submits = 0
        compose.setContent {
            PocketShellTheme {
                FileViewerScaffold(
                    hostName = "agents",
                    state = FileViewerUiState.Image(
                        displayPath = "/tmp/shot.png",
                        cacheFile = imageFile,
                        sizeBytes = imageFile.length(),
                    ),
                    // Active annotate mode WITH one annotation so Submit is enabled.
                    annotationState = ImageAnnotationState(
                        active = true,
                        tool = AnnotationTool.Pen,
                        annotations = listOf(
                            Annotation.Freehand(
                                points = listOf(ImagePoint(0f, 0f), ImagePoint(1f, 1f)),
                                colorArgb = ImageAnnotationState.DEFAULT_COLOR_ARGB,
                                strokeWidthPx = 2f,
                            ),
                        ),
                    ),
                    onBack = {},
                    onRetry = {},
                    onSubmitAnnotation = { submits++ },
                )
            }
        }
        compose.assertNodeFullyWithinRoot(FILE_VIEWER_ANNOTATE_TOOL_PAN_TAG)
        compose.assertNodeFullyWithinRoot(FILE_VIEWER_ANNOTATE_TOOL_PEN_TAG)
        compose.assertNodeFullyWithinRoot(FILE_VIEWER_ANNOTATE_TOOL_ARROW_TAG)
        compose.assertNodeFullyWithinRoot(FILE_VIEWER_ANNOTATE_UNDO_TAG)
        compose.assertNodeFullyWithinRoot(FILE_VIEWER_ANNOTATE_CANVAS_TAG)
        compose.assertNodeFullyWithinRoot(
            fileViewerAnnotateSwatchTag(ImageAnnotationState.DEFAULT_COLOR_ARGB),
        )
        compose.onNodeWithTag(FILE_VIEWER_ANNOTATE_SUBMIT_TAG).performClick()
        compose.waitForIdle()
        assertEquals(1, submits)
        imageFile.delete()
    }

    @Test
    fun annotateSubmitDisabledWithNoAnnotations() {
        val cacheDir = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "file-viewer",
        ).apply { mkdirs() }
        val imageFile = File(cacheDir, "issue764-empty.png").apply {
            if (exists()) delete()
            writeBytes(onePixelPngBytes())
        }
        var submits = 0
        compose.setContent {
            PocketShellTheme {
                FileViewerScaffold(
                    hostName = "agents",
                    state = FileViewerUiState.Image(
                        displayPath = "/tmp/shot.png",
                        cacheFile = imageFile,
                        sizeBytes = imageFile.length(),
                    ),
                    annotationState = ImageAnnotationState(active = true, tool = AnnotationTool.Pen),
                    onBack = {},
                    onRetry = {},
                    onSubmitAnnotation = { submits++ },
                )
            }
        }
        // Done is present but disabled — clicking does nothing.
        compose.onNodeWithTag(FILE_VIEWER_ANNOTATE_SUBMIT_TAG).performClick()
        compose.waitForIdle()
        assertEquals(0, submits)
        imageFile.delete()
    }

    @Test
    fun annotationSavedSheetShowsCopyablePath() {
        // Issue #764 — the post-submit confirmation sheet surfaces the saved PNG
        // path and copying it puts it on the clipboard.
        val cacheDir = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "file-viewer",
        ).apply { mkdirs() }
        val imageFile = File(cacheDir, "issue764-saved.png").apply {
            if (exists()) delete()
            writeBytes(onePixelPngBytes())
        }
        val savedPath = "/home/alexey/inbox/pocketshell/annotations/shot-20260614-101010.png"
        var copied = 0
        compose.setContent {
            PocketShellTheme {
                FileViewerScaffold(
                    hostName = "agents",
                    state = FileViewerUiState.Image(
                        displayPath = "/tmp/shot.png",
                        cacheFile = imageFile,
                        sizeBytes = imageFile.length(),
                    ),
                    submittedAnnotation = AnnotationSubmitEvent.Success(host = "agents", remotePath = savedPath),
                    onBack = {},
                    onRetry = {},
                    onCopyAnnotationPath = { copied++ },
                )
            }
        }
        compose.assertNodeFullyWithinRoot(FILE_VIEWER_ANNOTATE_SAVED_SHEET_TAG)
        compose.onNodeWithText(savedPath).assertIsDisplayed()
        // The path Text is inside a merged clickable row; click via the unmerged
        // tree so the tagged node is addressable.
        compose.onNodeWithTag(FILE_VIEWER_ANNOTATE_SAVED_PATH_TAG, useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertEquals(1, copied)
        imageFile.delete()
    }

    @Test
    fun imageStateShowsShareAndCopyActions() {
        val cacheDir = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "file-viewer",
        ).apply { mkdirs() }
        val imageFile = File(cacheDir, "issue559-scaffold.png").apply {
            if (exists()) delete()
            writeBytes(onePixelPngBytes())
        }
        compose.setContent {
            PocketShellTheme {
                FileViewerScaffold(
                    hostName = "agents",
                    state = FileViewerUiState.Image(
                        displayPath = "/tmp/shot.png",
                        cacheFile = imageFile,
                        sizeBytes = imageFile.length(),
                    ),
                    onBack = {},
                    onRetry = {},
                )
            }
        }
        compose.assertNodeFullyWithinRoot(FILE_VIEWER_SAVE_TAG)
        compose.assertNodeFullyWithinRoot(FILE_VIEWER_SHARE_TAG)
        compose.assertNodeFullyWithinRoot(FILE_VIEWER_COPY_TAG)
        imageFile.delete()
    }

    @Test
    fun loadingStateHidesShareAndCopy() {
        compose.setContent {
            PocketShellTheme {
                FileViewerScaffold(
                    hostName = "agents",
                    state = FileViewerUiState.Loading("/tmp/a.png"),
                    onBack = {},
                    onRetry = {},
                )
            }
        }
        compose.onNodeWithTag(FILE_VIEWER_SAVE_TAG).assertDoesNotExist()
        compose.onNodeWithTag(FILE_VIEWER_SHARE_TAG).assertDoesNotExist()
        compose.onNodeWithTag(FILE_VIEWER_COPY_TAG).assertDoesNotExist()
    }

    @Test
    fun cannotPreviewStateHidesShareAndCopy() {
        compose.setContent {
            PocketShellTheme {
                FileViewerScaffold(
                    hostName = "agents",
                    state = FileViewerUiState.CannotPreview(
                        displayPath = "/tmp/big.bin",
                        message = "File is too large to preview (limit 20 MB).",
                    ),
                    onBack = {},
                    onRetry = {},
                )
            }
        }
        compose.onNodeWithTag(FILE_VIEWER_SAVE_TAG).assertDoesNotExist()
        compose.onNodeWithTag(FILE_VIEWER_SHARE_TAG).assertDoesNotExist()
        compose.onNodeWithTag(FILE_VIEWER_COPY_TAG).assertDoesNotExist()
    }

    // ---- Issue #623: download-only panel for unsupported/binary files ----

    @Test
    fun cannotPreviewWithCacheShowsDownloadOnlyPanel() {
        val cacheDir = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "file-viewer",
        ).apply { mkdirs() }
        val binaryFile = File(cacheDir, "issue623-scaffold-data.tar.gz").apply {
            if (exists()) delete()
            writeBytes(ByteArray(4096)) // 4 KB binary file
        }
        compose.setContent {
            PocketShellTheme {
                FileViewerScaffold(
                    hostName = "agents",
                    state = FileViewerUiState.CannotPreview(
                        displayPath = "/tmp/data.tar.gz",
                        message = "Can't preview this file type.",
                        sizeBytes = binaryFile.length(),
                        cacheFile = binaryFile,
                    ),
                    onBack = {},
                    onRetry = {},
                )
            }
        }
        // Download-only panel is shown.
        compose.assertNodeFullyWithinRoot(FILE_VIEWER_DOWNLOAD_ONLY_TAG)
        compose.assertNodeFullyWithinRoot(FILE_VIEWER_FILE_NAME_TAG)
        compose.assertNodeFullyWithinRoot(FILE_VIEWER_FILE_SIZE_TAG)
        compose.assertNodeFullyWithinRoot(FILE_VIEWER_DOWNLOAD_BUTTON_TAG)
        // Header Save action is visible (shareable with cached file).
        compose.assertNodeFullyWithinRoot(FILE_VIEWER_SAVE_TAG)
        // Share and Copy are also available since the file is cached locally.
        compose.assertNodeFullyWithinRoot(FILE_VIEWER_SHARE_TAG)
        compose.assertNodeFullyWithinRoot(FILE_VIEWER_COPY_TAG)
        binaryFile.delete()
    }

    @Test
    fun downloadOnlyPanelShowsFileSize() {
        val cacheDir = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "file-viewer",
        ).apply { mkdirs() }
        val binaryFile = File(cacheDir, "issue623-size-test.zip").apply {
            if (exists()) delete()
            writeBytes(ByteArray(1536 * 1024)) // 1.5 MB
        }
        compose.setContent {
            PocketShellTheme {
                FileViewerScaffold(
                    hostName = "agents",
                    state = FileViewerUiState.CannotPreview(
                        displayPath = "/tmp/archive.zip",
                        message = "Can't preview this file type.",
                        sizeBytes = binaryFile.length(),
                        cacheFile = binaryFile,
                    ),
                    onBack = {},
                    onRetry = {},
                )
            }
        }
        compose.onNodeWithText("1.5 MB").assertIsDisplayed()
        binaryFile.delete()
    }

    /** Minimal valid 1x1 PNG so the image panel decodes a real bitmap. */
    private fun onePixelPngBytes(): ByteArray {
        val bmp = android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }

    @Test
    fun backButtonInvokesCallback() {
        var backs = 0
        compose.setContent {
            PocketShellTheme {
                FileViewerScaffold(
                    hostName = "agents",
                    state = FileViewerUiState.Loading("/tmp/a.png"),
                    onBack = { backs++ },
                    onRetry = {},
                )
            }
        }
        compose.onNodeWithTag(FILE_VIEWER_BACK_TAG).performClick()
        compose.waitForIdle()
        assertEquals(1, backs)
    }

    // Issue #763 — post-Submit confirmation: saved path (copyable) + attach.

    private val savedPath = "/home/alexey/inbox/pocketshell/reviews/README.md-20260614-025147.yaml"

    private fun submittedReviewState() = ReviewSubmitEvent.Success(
        host = "agents",
        count = 2,
        remotePath = savedPath,
    )

    @Test
    fun submittedReviewSheetShowsSavedPathAndActions() {
        compose.setContent {
            PocketShellTheme {
                FileViewerScaffold(
                    hostName = "agents",
                    state = FileViewerUiState.TextContent(
                        displayPath = "/home/alexey/README.md",
                        content = "# readme",
                        sizeBytes = 8,
                    ),
                    reviewState = ReviewState(active = true),
                    submittedReview = submittedReviewState(),
                    onBack = {},
                    onRetry = {},
                )
            }
        }
        compose.waitForIdle()
        compose.assertNodeFullyWithinRoot(FILE_VIEWER_REVIEW_SAVED_SHEET_TAG)
        compose.onNodeWithText(savedPath).assertIsDisplayed()
        compose.assertNodeFullyWithinRoot(FILE_VIEWER_REVIEW_ATTACH_TAG)
    }

    @Test
    fun tappingSavedPathCopiesItToClipboard() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        compose.setContent {
            PocketShellTheme {
                FileViewerScaffold(
                    hostName = "agents",
                    state = FileViewerUiState.TextContent(
                        displayPath = "/home/alexey/README.md",
                        content = "# readme",
                        sizeBytes = 8,
                    ),
                    reviewState = ReviewState(active = true),
                    submittedReview = submittedReviewState(),
                    onBack = {},
                    onRetry = {},
                    onCopyReviewPath = { path ->
                        val cm = instrumentation.targetContext
                            .getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("review path", path))
                    },
                )
            }
        }
        compose.waitForIdle()
        // The path text owns its own click semantics inside the (merged) Copy
        // row, so target it in the unmerged tree.
        compose.onNodeWithTag(FILE_VIEWER_REVIEW_SAVED_PATH_TAG, useUnmergedTree = true)
            .performClick()
        compose.waitForIdle()
        var clipText: String? = null
        instrumentation.runOnMainSync {
            val cm = instrumentation.targetContext
                .getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipText = cm.primaryClip?.getItemAt(0)?.text?.toString()
        }
        assertEquals(savedPath, clipText)
    }

    @Test
    fun attachToCurrentSessionInvokesCallbackWithSavedPath() {
        var attached: String? = null
        compose.setContent {
            PocketShellTheme {
                FileViewerScaffold(
                    hostName = "agents",
                    state = FileViewerUiState.TextContent(
                        displayPath = "/home/alexey/README.md",
                        content = "# readme",
                        sizeBytes = 8,
                    ),
                    reviewState = ReviewState(active = true),
                    submittedReview = submittedReviewState(),
                    onBack = {},
                    onRetry = {},
                    onAttachReview = { path -> attached = path },
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(FILE_VIEWER_REVIEW_ATTACH_TAG).performClick()
        compose.waitForIdle()
        assertEquals(savedPath, attached)
    }
}
