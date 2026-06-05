package com.pocketshell.app.fileviewer

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
        compose.onNodeWithTag(FILE_VIEWER_LOADING_TAG).assertIsDisplayed()
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
        compose.onNodeWithTag(FILE_VIEWER_TEXT_TAG).assertIsDisplayed()
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
        compose.onNodeWithTag(FILE_VIEWER_CANNOT_PREVIEW_TAG).assertIsDisplayed()
        compose.onNodeWithText("File is too large to preview (limit 20 MB).").assertIsDisplayed()
        compose.onNodeWithTag(FILE_VIEWER_RETRY_TAG).performClick()
        compose.waitForIdle()
        assertEquals(1, retries)
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
        compose.onNodeWithTag(FILE_VIEWER_AUDIO_TAG).assertIsDisplayed()
        compose.onNodeWithTag(FILE_VIEWER_AUDIO_PLAY_PAUSE_TAG).assertIsDisplayed()
        compose.onNodeWithTag(FILE_VIEWER_AUDIO_SEEKBAR_TAG).assertExists()
        audioFile.delete()
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
}
