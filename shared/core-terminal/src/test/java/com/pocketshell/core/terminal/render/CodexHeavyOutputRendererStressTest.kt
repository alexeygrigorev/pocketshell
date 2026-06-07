package com.pocketshell.core.terminal.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.view.TerminalRenderer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Issue #576: Codex `/new` can dump a large, dynamic workspace context into
 * the terminal. This reproduces the renderer shape without SSH/Docker:
 *
 * - about the same order of transcript lines as the dogfood report;
 * - long wrapped rows and fenced command blocks;
 * - in-place CR/erase status rewrites like agent TUIs;
 * - deliberately small chunk boundaries that split ANSI escapes.
 *
 * The assertion is intentionally behavioral rather than time-based: parsing
 * and rendering every chunk must complete, the final marker must remain
 * visible, and the last in-place status line must not retain stale text from
 * earlier longer frames.
 */
@RunWith(RobolectricTestRunner::class)
class CodexHeavyOutputRendererStressTest {

    private object SinkOutput : TerminalOutput() {
        override fun write(data: ByteArray?, offset: Int, count: Int) = Unit
        override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
        override fun onCopyTextToClipboard(text: String?) = Unit
        override fun onPasteTextFromClipboard() = Unit
        override fun onBell() = Unit
        override fun onColorsChanged() = Unit
    }

    @Test
    fun codexStyleHugeDynamicOutputRendersToStableFinalFrame() {
        val terminal = TerminalEmulator(
            SinkOutput,
            COLUMNS,
            ROWS,
            CELL_WIDTH_PX,
            CELL_HEIGHT_PX,
            TRANSCRIPT_ROWS,
            null,
        )
        val renderer = TerminalRenderer(TEXT_SIZE_PX, Typeface.MONOSPACE)
        val canvas = Canvas(Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888))

        renderer.render(terminal, canvas, 0, -1, -1, -1, -1)

        codexNewOutputChunks().forEach { chunk ->
            terminal.append(chunk, chunk.size)
            renderer.render(terminal, canvas, 0, -1, -1, -1, -1)
        }

        val transcript = terminal.screen.transcriptText
        assertTrue(
            "late transcript content should survive the Codex-scale flood; tail='${transcript.takeLast(240)}'",
            transcript.contains("changed-file-8844"),
        )
        assertTrue(
            "final Codex marker should be visible after the flood; tail='${transcript.takeLast(240)}'",
            transcript.endsWith(FINAL_MARKER),
        )
        assertFalse(
            "in-place status rewrite leaked stale suffix into the final frame",
            transcript.takeLast(240).contains(STALE_STATUS_SUFFIX),
        )
    }

    private fun codexNewOutputChunks(): List<ByteArray> {
        val bytes = buildString {
            append("Codex /new synthetic workspace context\r\n")
            append("git status --short --branch\r\n")
            repeat(HEAVY_TRANSCRIPT_LINES) { index ->
                if (index % 97 == 0) {
                    append("\r\u001B[K")
                    append("| collecting context ")
                    append(index)
                    append("/")
                    append(HEAVY_TRANSCRIPT_LINES)
                    append(" ")
                    append(STALE_STATUS_SUFFIX)
                    append("\r\u001B[K")
                    append("/ collecting context ")
                    append(index)
                    append("/")
                    append(HEAVY_TRANSCRIPT_LINES)
                    append("\r\n")
                }
                append("changed-file-")
                append(index.toString().padStart(4, '0'))
                append(" M app/src/main/java/com/pocketshell/issue576/File")
                append(index.toString().padStart(4, '0'))
                append(".kt ")
                append("large Codex transcript line with wrapped workspace context ".repeat(3))
                append("\r\n")
                if (index % 211 == 0) {
                    append("```sh\r\n")
                    append("printf 'issue576 chunk ")
                    append(index)
                    append("'; git status --short --branch\r\n")
                    append("```\r\n")
                }
            }
            append("\r\u001B[K")
            append(FINAL_MARKER)
        }.toByteArray(Charsets.UTF_8)

        val chunks = mutableListOf<ByteArray>()
        var offset = 0
        var chunkIndex = 0
        while (offset < bytes.size) {
            val size = CHUNK_SIZES[chunkIndex % CHUNK_SIZES.size]
            val end = minOf(bytes.size, offset + size)
            chunks += bytes.copyOfRange(offset, end)
            offset = end
            chunkIndex += 1
        }
        return chunks
    }

    private companion object {
        const val COLUMNS = 80
        const val ROWS = 24
        const val TRANSCRIPT_ROWS = 12_000
        const val CELL_WIDTH_PX = 13
        const val CELL_HEIGHT_PX = 15
        const val TEXT_SIZE_PX = 28
        const val HEAVY_TRANSCRIPT_LINES = 8_845
        const val STALE_STATUS_SUFFIX = "very-long-stale-status-suffix-that-must-be-erased"
        const val FINAL_MARKER = "ISSUE576-CODEX-NEW-READY"

        val CHUNK_SIZES = intArrayOf(
            1,
            2,
            5,
            13,
            34,
            89,
            233,
            610,
            1597,
            4096,
        )
    }
}
