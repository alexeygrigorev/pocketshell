package com.pocketshell.app.fileviewer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.RemoteListing
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Issue #697 — the file VIEWER's open path must reuse the app-wide warm
 * [SshLeaseManager] transport (the same one the session / folder / tmux /
 * explorer screens hold) instead of dialing a fresh ~3-4s SSH handshake per
 * file open, and must NEVER `close()` the shared connection. JVM-level, no
 * emulator: a counting connector proves the handshake count and a fake session
 * proves the lease is never closed; the content cache proves an instant
 * re-open paint.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class FileViewerLeaseTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun openingAFileBorrowsTheLeaseOnceAndNeverClosesIt() = runBlocking {
        val session = FakeFileSession()
        val connector = CountingConnector(session)
        val leaseManager = SshLeaseManager(connector = connector, idleTtlMillis = 30_000L)
        val vm = FileViewerViewModel(context, leaseManager)

        vm.bind(request("/srv/readme.txt"))
        val text = vm.state.awaitText()
        assertEquals("hello viewer", text.content)

        assertEquals("file open must dial exactly one handshake", 1, connector.connectCount)
        assertFalse("viewer must NOT close the shared warm transport", session.closed)

        // Clearing the VM releases the lease but never closes it (the pool keeps
        // it warm for its idle TTL so a sibling screen reuses it).
        vm.callOnCleared()
        assertFalse("onCleared must release, not close, the warm transport", session.closed)
        leaseManager.close()
    }

    @Test
    fun openingOnAPrewarmedHostReusesTheTransportWithNoExtraHandshake() = runBlocking {
        val session = FakeFileSession()
        val connector = CountingConnector(session)
        val leaseManager = SshLeaseManager(connector = connector, idleTtlMillis = 30_000L)

        // A sibling screen already holds a warm lease keyed identically to what
        // the viewer will use ("$hostId:$keyPath").
        val warmLease = leaseManager.acquire(
            request("/srv/a.txt").toLeaseTarget().toSshLeaseTarget(),
        ).getOrThrow()
        val afterWarm = connector.connectCount
        assertEquals("pre-warm dials exactly one handshake", 1, afterWarm)

        val vm = FileViewerViewModel(context, leaseManager)
        vm.bind(request("/srv/a.txt"))
        vm.state.awaitText()

        assertEquals(
            "viewer open must reuse the warm lease, not handshake again",
            afterWarm,
            connector.connectCount,
        )
        warmLease.release()
        leaseManager.close()
    }

    @Test
    fun reopeningAJustViewedTextFilePaintsTheCachedContentInstantly() = runBlocking {
        val session = FakeFileSession()
        val leaseManager = SshLeaseManager(
            connector = CountingConnector(session),
            idleTtlMillis = 30_000L,
        )
        val vm = FileViewerViewModel(context, leaseManager)

        val first = request("/srv/readme.txt")
        vm.bind(first)
        vm.state.awaitText { it.displayPath.endsWith("/readme.txt") }

        // Navigate away to another file (so lastRequest != first), then back to
        // the first: the re-open must paint the cached text immediately (not
        // Loading) before the fresh fetch reconciles.
        vm.bind(request("/srv/other.txt"))
        vm.state.awaitText { it.displayPath.endsWith("/other.txt") }

        vm.bind(first)
        val immediate = vm.state.value
        assertTrue(
            "re-open must paint cached text instantly, was $immediate",
            immediate is FileViewerUiState.TextContent &&
                immediate.displayPath.endsWith("/readme.txt"),
        )
        leaseManager.close()
    }

    private suspend fun StateFlow<FileViewerUiState>.awaitText(
        predicate: (FileViewerUiState.TextContent) -> Boolean = { true },
    ): FileViewerUiState.TextContent {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val s = value
            if (s is FileViewerUiState.TextContent && predicate(s)) return s
            kotlinx.coroutines.delay(20)
        }
        error("viewer never reached the expected TextContent state; was ${value}")
    }

    private fun request(path: String) = FileViewerViewModel.Request(
        hostId = 1L,
        hostname = "10.0.2.2",
        port = 2222,
        username = "tester",
        keyPath = "/tmp/key",
        passphrase = null,
        path = path,
        cwd = null,
    )

    private fun FileViewerViewModel.callOnCleared() {
        val m = androidx.lifecycle.ViewModel::class.java.getDeclaredMethod("onCleared")
        m.isAccessible = true
        m.invoke(this)
    }

    private class CountingConnector(
        private val session: FakeFileSession,
    ) : SshLeaseConnector {
        var connectCount: Int = 0

        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
            connectCount += 1
            return Result.success(session)
        }
    }

    /**
     * Minimal in-memory remote: `$HOME` probes to /home/tester, and any
     * `cat`/SFTP read of a file returns a small UTF-8 body keyed by basename so
     * different paths are distinguishable.
     */
    private class FakeFileSession : SshSession {
        var closed: Boolean = false
        val downloads = AtomicInteger(0)

        override val isConnected: Boolean
            get() = !closed

        override suspend fun exec(command: String): ExecResult {
            // remoteHomeDirectory(): printf '%s\n' "$HOME"
            return ExecResult(stdout = "/home/tester\n", stderr = "", exitCode = 0)
        }

        override suspend fun downloadFile(remotePath: String, maxBytes: Long): ByteArray {
            downloads.incrementAndGet()
            val body = when {
                remotePath.endsWith("/readme.txt") -> "hello viewer"
                remotePath.endsWith("/a.txt") -> "alpha"
                else -> "other body"
            }
            return body.toByteArray(Charsets.UTF_8)
        }

        override suspend fun listDirectory(remotePath: String, maxEntries: Int): RemoteListing =
            error("not used")

        override fun tail(path: String, onLine: (String) -> Unit): Job = error("not used")

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = error("not used")

        override fun startShell(): SshShell = error("not used")

        override suspend fun uploadFile(file: File, remotePath: String): String = error("not used")

        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("not used")

        override fun close() {
            closed = true
        }
    }
}
