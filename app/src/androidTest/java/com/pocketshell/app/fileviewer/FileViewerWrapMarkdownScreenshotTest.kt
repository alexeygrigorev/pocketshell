package com.pocketshell.app.fileviewer

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #696 evidence — capture, on a real emulator, the word-wrap toggle and
 * Markdown render-vs-raw behaviour against a realistic `.md` like the one in
 * the maintainer's screenshot (ATX headers + a fenced python code block + a
 * long code line):
 *
 *  - `file-viewer-md-rendered.png`: a `.md` rendered as formatted Markdown
 *    (headers scaled, code block monospaced, bold/links styled).
 *  - `file-viewer-md-raw.png`: the same file after toggling to raw source.
 *  - `file-viewer-wrap-off.png` / `file-viewer-wrap-on.png`: a long-line code
 *    file with wrap off (horizontal scroll) then wrap on (lines wrapped).
 */
@RunWith(AndroidJUnit4::class)
class FileViewerWrapMarkdownScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun markdownRenderedThenRaw() {
        var prefs by mutableStateOf(
            FileViewerReadingPrefs(wordWrap = false, renderMarkdown = true),
        )
        compose.setContent {
            PocketShellTheme {
                FileViewerScaffold(
                    hostName = "agents",
                    state = FileViewerUiState.TextContent(
                        displayPath = "/home/agent/project/README.md",
                        content = MARKDOWN_BODY,
                        sizeBytes = MARKDOWN_BODY.length.toLong(),
                    ),
                    readingPrefs = prefs,
                    onBack = {},
                    onRetry = {},
                    onToggleRenderMarkdown = {
                        prefs = prefs.copy(renderMarkdown = !prefs.renderMarkdown)
                    },
                )
            }
        }
        compose.onNodeWithTag(FILE_VIEWER_MARKDOWN_TAG).assertExists()
        compose.waitForIdle()
        SystemClock.sleep(300)
        capture("file-viewer-md-rendered.png")

        // Toggle to raw source.
        compose.onNodeWithTag(FILE_VIEWER_RENDER_MD_TAG).performClick()
        compose.waitForIdle()
        SystemClock.sleep(300)
        capture("file-viewer-md-raw.png")
    }

    @Test
    fun longLineCodeWrapOffThenOn() {
        var prefs by mutableStateOf(
            FileViewerReadingPrefs(wordWrap = false, renderMarkdown = true),
        )
        compose.setContent {
            PocketShellTheme {
                FileViewerScaffold(
                    hostName = "agents",
                    state = FileViewerUiState.TextContent(
                        displayPath = "/home/agent/project/train.py",
                        content = LONG_CODE_BODY,
                        sizeBytes = LONG_CODE_BODY.length.toLong(),
                    ),
                    readingPrefs = prefs,
                    onBack = {},
                    onRetry = {},
                    onToggleWordWrap = {
                        prefs = prefs.copy(wordWrap = !prefs.wordWrap)
                    },
                )
            }
        }
        compose.onNodeWithTag(FILE_VIEWER_TEXT_TAG).assertExists()
        compose.waitForIdle()
        SystemClock.sleep(300)
        capture("file-viewer-wrap-off.png")

        compose.onNodeWithTag(FILE_VIEWER_WRAP_TAG).performClick()
        compose.waitForIdle()
        SystemClock.sleep(300)
        capture("file-viewer-wrap-on.png")
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(200)
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/file-viewer-wrap-md").apply { mkdirs() }
        val file = File(dir, name)
        val bitmap: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        try {
            FileOutputStream(file).use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    "Could not write screenshot: ${file.absolutePath}"
                }
            }
            println("FILE_VIEWER_WRAP_MD_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        val MARKDOWN_BODY: String = buildString {
            appendLine("# PocketShell")
            appendLine()
            appendLine("Voice-first, tmux-native, agent-aware Android SSH client.")
            appendLine()
            appendLine("## Quick start")
            appendLine()
            appendLine("1. Add a **host** with its SSH key.")
            appendLine("2. Open a *session* and start typing.")
            appendLine("3. See the [docs](https://example.com/docs) for more.")
            appendLine()
            appendLine("### Benchmark")
            appendLine()
            appendLine("| mode | avgms | p99ms | recall |")
            appendLine("|---|---:|---:|---:|")
            appendLine("| LSH | 1.2 | 2.4 | 0.91 |")
            appendLine("| HNSW | 0.8 | 1.6 | 0.97 |")
            appendLine()
            appendLine("### Example")
            appendLine()
            appendLine("```python")
            appendLine("def train(model, data, epochs=10):")
            appendLine("    for epoch in range(epochs):")
            appendLine("        loss = model.fit(data)  # a fairly long inline comment to show scroll")
            appendLine("    return model")
            appendLine("```")
            appendLine()
            appendLine("> Tip: toggle raw source to copy the original Markdown.")
            appendLine()
            appendLine("- wrap toggle for long lines")
            appendLine("- render Markdown for `.md` files")
        }

        val LONG_CODE_BODY: String = buildString {
            appendLine("import os, sys, json, logging, argparse, itertools, functools, collections")
            appendLine(
                "result = some_module.run_pipeline(config=load_config(path), " +
                    "verbose=True, retries=5, timeout=120, on_error=lambda e: " +
                    "logging.error('pipeline failed with %s while processing the very long input row', e))",
            )
            appendLine("# A short line")
            appendLine(
                "another_very_long_line = " +
                    "\"this is a deliberately long string literal that runs well past the " +
                    "right edge of a phone viewport so the wrap toggle has something to wrap\"",
            )
        }
    }
}
