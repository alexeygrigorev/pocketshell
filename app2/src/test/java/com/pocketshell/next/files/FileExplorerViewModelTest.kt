package com.pocketshell.next.files

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.core.transport.SftpEntry
import com.pocketshell.core.transport.TransportState
import java.io.ByteArrayInputStream
import java.io.InputStream
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
 * [FileExplorerViewModel] over the REAL connect stack — a real Room database,
 * the real [com.pocketshell.next.connect.ConnectionsRegistry] and
 * `core-transport`'s in-memory [com.pocketshell.core.transport.FakeSftpChannel]
 * — with only the sshj dial swapped out.
 *
 * The `FakeSftpChannel` is what makes the transfer assertions meaningful: an
 * upload is proven by reading the bytes back out of the same filesystem the
 * screen wrote to, not by counting calls on a hand-written double.
 *
 * Robolectric (`AndroidJUnit4`) is needed only for the in-memory Room database
 * [TestFilesStack] builds; the ViewModel itself touches no Android API beyond
 * `SavedStateHandle`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class FileExplorerViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var stack: TestFilesStack

    @Before
    fun setUp() {
        // viewModelScope is hard-wired to Dispatchers.Main.
        Dispatchers.setMain(dispatcher)
        stack = TestFilesStack()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        stack.close()
    }

    @Test
    fun `the route path is listed, directories first then names`() = runTest(dispatcher) {
        val hostId = stack.seedHost()
        stack.seedSftp = { sftp ->
            sftp.seedDirectory("$WORKSPACE/src")
            sftp.seedDirectory("$WORKSPACE/Assets")
            sftp.seedFile("$WORKSPACE/zebra.txt", "z")
            sftp.seedFile("$WORKSPACE/README.md", "# hi")
        }
        val viewModel = explorer(hostId, WORKSPACE)

        viewModel.refresh()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue("a successful listing must mark the screen loaded", state.loaded)
        assertNull(state.failure)
        assertEquals(WORKSPACE, state.path)
        assertEquals(
            listOf("Assets", "src", "README.md", "zebra.txt"),
            state.entries.map { it.name },
        )
    }

    @Test
    fun `with no path argument the home directory the host reports is opened`() =
        runTest(dispatcher) {
            val hostId = stack.seedHost()
            stack.seedSftp = { it.seedFile("$HOME/.bashrc", "export PS1=") }
            val viewModel = explorer(hostId, path = null)

            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(HOME, viewModel.state.value.path)
            assertEquals(listOf(".bashrc"), viewModel.state.value.entries.map { it.name })
            // The home directory came from the HOST, not from a client-side guess.
            assertEquals(
                listOf("pwd"),
                stack.connection.execCalls.map { it.command },
            )
        }

    @Test
    fun `opening a directory lists it and going up returns to the parent`() = runTest(dispatcher) {
        val hostId = stack.seedHost()
        stack.seedSftp = { sftp ->
            sftp.seedFile("$WORKSPACE/src/main.kt", "fun main() {}")
            sftp.seedFile("$WORKSPACE/build.gradle.kts", "plugins {}")
        }
        val viewModel = explorer(hostId, WORKSPACE)
        viewModel.refresh()
        advanceUntilIdle()

        val src = viewModel.state.value.entries.single { it.name == "src" }
        viewModel.openDirectory(src)
        advanceUntilIdle()

        assertEquals("$WORKSPACE/src", viewModel.state.value.path)
        assertEquals(listOf("main.kt"), viewModel.state.value.entries.map { it.name })

        viewModel.goUp()
        advanceUntilIdle()

        assertEquals(WORKSPACE, viewModel.state.value.path)
        assertEquals(listOf("src", "build.gradle.kts"), viewModel.state.value.entries.map { it.name })
    }

    @Test
    fun `tapping a file row does not navigate`() = runTest(dispatcher) {
        val hostId = stack.seedHost()
        stack.seedSftp = { it.seedFile("$WORKSPACE/notes.md", "# hi") }
        val viewModel = explorer(hostId, WORKSPACE)
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.openDirectory(viewModel.state.value.entries.single())
        advanceUntilIdle()

        assertEquals(WORKSPACE, viewModel.state.value.path)
    }

    @Test
    fun `an empty directory is empty AND healthy, not an error`() = runTest(dispatcher) {
        val hostId = stack.seedHost()
        stack.seedSftp = { it.seedDirectory(WORKSPACE) }
        val viewModel = explorer(hostId, WORKSPACE)

        viewModel.refresh()
        advanceUntilIdle()

        assertTrue(viewModel.state.value.isEmptyAndHealthy)
        assertNull(viewModel.state.value.failure)
    }

    @Test
    fun `an unreadable directory reports the failure and keeps the last listing`() =
        runTest(dispatcher) {
            val hostId = stack.seedHost()
            stack.seedSftp = { it.seedFile("$WORKSPACE/keep.txt", "keep") }
            val viewModel = explorer(hostId, WORKSPACE)
            viewModel.refresh()
            advanceUntilIdle()

            viewModel.navigateTo("$WORKSPACE/does-not-exist")
            advanceUntilIdle()

            val state = viewModel.state.value
            assertTrue(
                "the failure must name the directory, got ${state.failure}",
                state.failure.orEmpty().contains("$WORKSPACE/does-not-exist"),
            )
            // "empty" and "broken" must not render identically: the rows the
            // last good listing produced are still on screen under the banner.
            assertEquals(listOf("keep.txt"), state.entries.map { it.name })
            assertFalse(state.isEmptyAndHealthy)
        }

    @Test
    fun `an unconfirmed host key is reported as a failure, never as an empty folder`() =
        runTest(dispatcher) {
            // The factory presents a fingerprint the host row has never trusted,
            // which is the shape a first connect from a deep link has.
            stack.close()
            stack = TestFilesStack(presentedFingerprint = "SHA256:brand-new")
            val hostId = stack.seedHost(trustedHostKeySha256 = null)
            val viewModel = explorer(hostId, WORKSPACE)

            viewModel.refresh()
            advanceUntilIdle()

            val state = viewModel.state.value
            assertTrue(
                "the user must be pointed at the screen that owns trust, got ${state.failure}",
                state.failure.orEmpty().contains("host list"),
            )
            assertFalse(state.loaded)
        }

    @Test
    fun `upload writes the picked bytes into the current directory and re-lists`() =
        runTest(dispatcher) {
            val hostId = stack.seedHost()
            stack.seedSftp = { it.seedDirectory(WORKSPACE) }
            val viewModel = explorer(hostId, WORKSPACE)
            viewModel.refresh()
            advanceUntilIdle()

            viewModel.upload("notes.txt", declaredSize = 5, openStream = stream("hello"))
            advanceUntilIdle()

            // The bytes really landed on the host's filesystem...
            assertEquals("hello", stack.sftp.textAt("$WORKSPACE/notes.txt"))
            // ...and the screen re-listed, so the new file is visible without a
            // manual refresh.
            assertEquals(listOf("notes.txt"), viewModel.state.value.entries.map { it.name })
            val transfer = viewModel.state.value.transfer
            assertTrue("expected a success banner, got $transfer", transfer is TransferState.Done)
        }

    @Test
    fun `an upload name with path separators cannot escape the current directory`() =
        runTest(dispatcher) {
            val hostId = stack.seedHost()
            stack.seedSftp = { it.seedDirectory(WORKSPACE) }
            val viewModel = explorer(hostId, WORKSPACE)
            viewModel.refresh()
            advanceUntilIdle()

            viewModel.upload("../../.ssh/authorized_keys", declaredSize = 3, openStream = stream("pwn"))
            advanceUntilIdle()

            assertNull(stack.sftp.textAt("$HOME/.ssh/authorized_keys"))
            assertEquals("pwn", stack.sftp.textAt("$WORKSPACE/authorized_keys"))
        }

    @Test
    fun `an over-size upload is refused before a byte is read`() = runTest(dispatcher) {
        val hostId = stack.seedHost()
        stack.seedSftp = { it.seedDirectory(WORKSPACE) }
        val viewModel = explorer(hostId, WORKSPACE)
        viewModel.refresh()
        advanceUntilIdle()

        var opened = false
        viewModel.upload(
            displayName = "huge.bin",
            declaredSize = TransferLimits.MAX_UPLOAD_BYTES + 1,
            openStream = {
                opened = true
                ByteArrayInputStream(ByteArray(0))
            },
        )
        advanceUntilIdle()

        assertFalse("the stream must not be opened for a refused upload", opened)
        assertNull(stack.sftp.textAt("$WORKSPACE/huge.bin"))
        val transfer = viewModel.state.value.transfer
        assertTrue("expected a failure banner, got $transfer", transfer is TransferState.Failed)
        assertTrue(
            (transfer as TransferState.Failed).message.contains("too big to upload"),
        )
    }

    @Test
    fun `download reads the entry and hands the bytes to the destination sink`() =
        runTest(dispatcher) {
            val hostId = stack.seedHost()
            stack.seedSftp = { it.seedFile("$WORKSPACE/log.txt", "line one\nline two\n") }
            val viewModel = explorer(hostId, WORKSPACE)
            viewModel.refresh()
            advanceUntilIdle()

            var written: ByteArray? = null
            viewModel.download(viewModel.state.value.entries.single()) { written = it }
            advanceUntilIdle()

            assertEquals("line one\nline two\n", written?.toString(Charsets.UTF_8))
            val transfer = viewModel.state.value.transfer
            assertTrue("expected a success banner, got $transfer", transfer is TransferState.Done)
        }

    @Test
    fun `a download whose destination cannot be written reports a failure`() = runTest(dispatcher) {
        val hostId = stack.seedHost()
        stack.seedSftp = { it.seedFile("$WORKSPACE/log.txt", "content") }
        val viewModel = explorer(hostId, WORKSPACE)
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.download(viewModel.state.value.entries.single()) {
            throw java.io.IOException("could not open the chosen destination")
        }
        advanceUntilIdle()

        val transfer = viewModel.state.value.transfer
        assertTrue("expected a failure banner, got $transfer", transfer is TransferState.Failed)
        assertTrue(
            (transfer as TransferState.Failed).message.contains("could not open the chosen destination"),
        )
    }

    @Test
    fun `downloading a directory is a no-op`() = runTest(dispatcher) {
        val hostId = stack.seedHost()
        stack.seedSftp = { it.seedDirectory("$WORKSPACE/src") }
        val viewModel = explorer(hostId, WORKSPACE)
        viewModel.refresh()
        advanceUntilIdle()

        var called = false
        viewModel.download(viewModel.state.value.entries.single { it.isDirectory }) { called = true }
        advanceUntilIdle()

        assertFalse(called)
        assertEquals(TransferState.Idle, viewModel.state.value.transfer)
    }

    @Test
    fun `a connection that died while the screen was backgrounded is re-dialled`() =
        runTest(dispatcher) {
            val hostId = stack.seedHost()
            stack.seedSftp = { it.seedFile("$WORKSPACE/a.txt", "a") }
            val viewModel = explorer(hostId, WORKSPACE)
            viewModel.refresh()
            advanceUntilIdle()
            assertEquals(1, stack.factory.dialCount)

            stack.connection.setState(TransportState.Lost("network dropped"))
            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(2, stack.factory.dialCount)
            assertEquals(listOf("a.txt"), viewModel.state.value.entries.map { it.name })
            assertNull(viewModel.state.value.failure)
        }

    @Test
    fun `sortEntries puts folders first and is case-insensitive within a group`() {
        val sorted = sortEntries(
            listOf(
                entry("/w/beta.txt", directory = false),
                entry("/w/Zulu", directory = true),
                entry("/w/Alpha.txt", directory = false),
                entry("/w/alpha", directory = true),
            ),
        ).map { it.name }

        assertEquals(listOf("alpha", "Zulu", "Alpha.txt", "beta.txt"), sorted)
    }

    // --- helpers ----------------------------------------------------------

    private fun explorer(hostId: Long, path: String?) = FileExplorerViewModel(
        savedStateHandle = stack.savedState(hostId, path),
        registry = stack.registry,
        dispatcher = dispatcher,
    )

    private fun stream(text: String): () -> InputStream =
        { ByteArrayInputStream(text.toByteArray()) }

    private fun entry(path: String, directory: Boolean) = SftpEntry(
        path = path,
        isDirectory = directory,
        sizeBytes = 0,
        modifiedEpochMs = 0,
    )

    private companion object {
        const val HOME = "/home/testuser"
        const val WORKSPACE = "/home/testuser/git/pocketshell"
    }
}
