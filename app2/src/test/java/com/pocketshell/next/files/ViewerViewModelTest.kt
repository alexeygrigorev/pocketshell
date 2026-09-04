package com.pocketshell.next.files

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.core.transport.TransportState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [ViewerViewModel] over the real connect stack and `core-transport`'s
 * in-memory SFTP fixture (task P-3b).
 *
 * The edit/save assertions read the bytes back OUT of the fixture rather than
 * asserting a call was made, so "save round-trips" means the host really holds
 * the new content — the same property journey J10 proves against a real sshd.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ViewerViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var stack: TestFilesStack

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        stack = TestFilesStack()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        stack.close()
    }

    @Test
    fun `a text file loads as editable text`() = runTest(dispatcher) {
        val hostId = stack.seedHost()
        stack.seedSftp = { it.seedFile(TEXT_PATH, "hello\nworld\n") }
        val viewModel = viewer(hostId, TEXT_PATH)

        viewModel.load()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state.loaded)
        assertEquals(FileKind.TEXT, state.kind)
        assertEquals("hello\nworld\n", (state.content as ViewerContent.Text).text)
        assertTrue("text must be editable", state.editable)
        assertFalse("a plain .txt offers no markdown toggle", state.markdownCapable)
        assertNull(state.failure)
    }

    @Test
    fun `a PNG loads as an image and is not editable`() = runTest(dispatcher) {
        val hostId = stack.seedHost()
        // Real PNG magic bytes, so the CONTENT sniff is what routes this — the
        // name deliberately lies about the type.
        stack.seedSftp = { it.seedFile("$DIR/screenshot.txt", PNG_HEADER + ByteArray(64)) }
        val viewModel = viewer(hostId, "$DIR/screenshot.txt")

        viewModel.load()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(FileKind.IMAGE, state.kind)
        assertTrue(state.content is ViewerContent.Image)
        assertFalse("an image must not offer the text editor", state.editable)
    }

    @Test
    fun `undecodable bytes fall back to the binary renderer instead of failing`() =
        runTest(dispatcher) {
            val hostId = stack.seedHost()
            stack.seedSftp = { it.seedFile("$DIR/a.bin", byteArrayOf(0x00, 0x01, 0x02, 0x00, 0x7F)) }
            val viewModel = viewer(hostId, "$DIR/a.bin")

            viewModel.load()
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(FileKind.BINARY, state.kind)
            assertTrue(state.content is ViewerContent.Binary)
            assertNull("a binary file is not an error", state.failure)
        }

    @Test
    fun `editing and saving writes the buffer back to the same path`() = runTest(dispatcher) {
        val hostId = stack.seedHost()
        stack.seedSftp = { it.seedFile(TEXT_PATH, "before\n") }
        val viewModel = viewer(hostId, TEXT_PATH)
        viewModel.load()
        advanceUntilIdle()

        viewModel.startEditing()
        assertEquals("before\n", viewModel.state.value.draft)
        assertFalse("an untouched buffer is not dirty", viewModel.state.value.dirty)

        viewModel.onDraftChange("after the edit\n")
        assertTrue(viewModel.state.value.dirty)

        viewModel.save()
        advanceUntilIdle()

        // The host really holds the new content.
        assertEquals("after the edit\n", stack.sftp.textAt(TEXT_PATH))
        val state = viewModel.state.value
        assertFalse("a successful save closes the editor", state.editing)
        assertFalse(state.saving)
        assertEquals("after the edit\n", (state.content as ViewerContent.Text).text)
        assertEquals("Saved notes.txt", state.savedMessage)
        assertNull(state.failure)
    }

    @Test
    fun `a re-read after a save shows the edit`() = runTest(dispatcher) {
        val hostId = stack.seedHost()
        stack.seedSftp = { it.seedFile(TEXT_PATH, "before\n") }
        val viewModel = viewer(hostId, TEXT_PATH)
        viewModel.load()
        advanceUntilIdle()
        viewModel.startEditing()
        viewModel.onDraftChange("persisted\n")
        viewModel.save()
        advanceUntilIdle()

        // A SECOND ViewModel over the same host reads what was written, so the
        // green does not come from the first one's in-memory state.
        val reopened = viewer(hostId, TEXT_PATH)
        reopened.load()
        advanceUntilIdle()

        assertEquals("persisted\n", (reopened.state.value.content as ViewerContent.Text).text)
    }

    @Test
    fun `a failed save keeps the editor open with the buffer intact`() = runTest(dispatcher) {
        val hostId = stack.seedHost()
        stack.seedSftp = { it.seedFile(TEXT_PATH, "before\n") }
        val viewModel = viewer(hostId, TEXT_PATH)
        viewModel.load()
        advanceUntilIdle()
        viewModel.startEditing()
        viewModel.onDraftChange("would be lost\n")

        // The transport dies and the re-dial fails — the shape a save attempted
        // on a dropped connection has.
        stack.connection.setState(TransportState.Lost("network dropped"))
        stack.factory.failWith = "host unreachable"
        viewModel.save()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue("the editor must stay open so the edit is not thrown away", state.editing)
        assertEquals("would be lost\n", state.draft)
        assertFalse(state.saving)
        assertTrue(
            "expected the save failure to be reported, got ${state.failure}",
            state.failure.orEmpty().contains("host unreachable"),
        )
    }

    @Test
    fun `a re-read is refused while the user is editing`() = runTest(dispatcher) {
        val hostId = stack.seedHost()
        stack.seedSftp = { it.seedFile(TEXT_PATH, "on host\n") }
        val viewModel = viewer(hostId, TEXT_PATH)
        viewModel.load()
        advanceUntilIdle()
        viewModel.startEditing()
        viewModel.onDraftChange("in progress\n")

        // ON_START fires again (returning from the background). It must not
        // silently replace the buffer with what the host holds.
        viewModel.load()
        advanceUntilIdle()

        assertEquals("in progress\n", viewModel.state.value.draft)
        assertTrue(viewModel.state.value.editing)
    }

    @Test
    fun `cancelling an edit discards the buffer and leaves the file untouched`() =
        runTest(dispatcher) {
            val hostId = stack.seedHost()
            stack.seedSftp = { it.seedFile(TEXT_PATH, "original\n") }
            val viewModel = viewer(hostId, TEXT_PATH)
            viewModel.load()
            advanceUntilIdle()
            viewModel.startEditing()
            viewModel.onDraftChange("discard me\n")

            viewModel.cancelEditing()

            assertFalse(viewModel.state.value.editing)
            assertEquals("", viewModel.state.value.draft)
            assertEquals("original\n", stack.sftp.textAt(TEXT_PATH))
        }

    @Test
    fun `a markdown file opens rendered and toggles to source`() = runTest(dispatcher) {
        val hostId = stack.seedHost()
        stack.seedSftp = { it.seedFile(MARKDOWN_PATH, "# Title\n") }
        val viewModel = viewer(hostId, MARKDOWN_PATH)
        viewModel.load()
        advanceUntilIdle()

        assertTrue(viewModel.state.value.markdownCapable)
        assertTrue("markdown opens formatted", viewModel.state.value.renderMarkdown)

        viewModel.toggleMarkdownRendering()
        assertFalse(viewModel.state.value.renderMarkdown)
    }

    @Test
    fun `a non-markdown file has no render toggle to flip`() = runTest(dispatcher) {
        val hostId = stack.seedHost()
        stack.seedSftp = { it.seedFile(TEXT_PATH, "plain") }
        val viewModel = viewer(hostId, TEXT_PATH)
        viewModel.load()
        advanceUntilIdle()

        viewModel.toggleMarkdownRendering()

        assertFalse(viewModel.state.value.renderMarkdown)
    }

    @Test
    fun `a missing file reports which path could not be opened`() = runTest(dispatcher) {
        val hostId = stack.seedHost()
        stack.seedSftp = { it.seedDirectory(DIR) }
        val viewModel = viewer(hostId, "$DIR/gone.txt")

        viewModel.load()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertFalse(state.loaded)
        assertTrue(
            "the failure must name the file, got ${state.failure}",
            state.failure.orEmpty().contains("$DIR/gone.txt"),
        )
    }

    @Test
    fun `an empty file loads as empty text rather than as binary`() = runTest(dispatcher) {
        val hostId = stack.seedHost()
        stack.seedSftp = { it.seedFile(TEXT_PATH, "") }
        val viewModel = viewer(hostId, TEXT_PATH)

        viewModel.load()
        advanceUntilIdle()

        assertEquals(FileKind.TEXT, viewModel.state.value.kind)
        assertEquals("", (viewModel.state.value.content as ViewerContent.Text).text)
        assertTrue(viewModel.state.value.editable)
    }

    // --- helpers ----------------------------------------------------------

    private fun viewer(hostId: Long, path: String) =
        ViewerViewModel(savedStateHandle = stack.savedState(hostId, path), registry = stack.registry)

    private companion object {
        const val DIR = "/home/testuser/git/pocketshell"
        const val TEXT_PATH = "$DIR/notes.txt"
        const val MARKDOWN_PATH = "$DIR/README.md"

        val PNG_HEADER = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
    }
}
