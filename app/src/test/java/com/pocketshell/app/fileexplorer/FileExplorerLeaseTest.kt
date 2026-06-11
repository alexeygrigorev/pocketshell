package com.pocketshell.app.fileexplorer

import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.RemoteEntry
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream

/**
 * Issue #697 — the explorer browse path must reuse the app-wide warm
 * [SshLeaseManager] transport (the same one the session / folder / tmux screens
 * hold) instead of dialing a fresh SSH handshake per open, and must never
 * `close()` the shared connection. JVM-level, no emulator: a counting connector
 * proves the handshake count, and a fake session proves the lease is never
 * closed and the listing cache short-circuits a re-enter.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class FileExplorerLeaseTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun browseAcquiresLeaseOnceAndNeverClosesIt() = runBlocking {
        val session = FakeSshSession()
        val connector = CountingConnector(session)
        val leaseManager = SshLeaseManager(
            connector = connector,
            idleTtlMillis = 30_000L,
        )
        val vm = FileExplorerViewModel(leaseManager)

        vm.start(request("/srv"))
        val ready = vm.state.awaitReady()
        // Descend into a child, then back up — three browse round-trips, all of
        // which must ride the ONE warm transport.
        vm.openDirectory(ready.entries.first { it.type == RemoteEntry.Type.DIRECTORY })
        vm.state.awaitReady { it.currentPath.endsWith("/sub") }
        vm.goUp()
        vm.state.awaitReady { it.currentPath == "/srv" }

        assertEquals("explorer browse must dial exactly one handshake", 1, connector.connectCount)
        assertFalse("explorer must NOT close the shared warm transport", session.closed)

        // Clearing the VM releases the lease but never closes it (the pool keeps
        // it warm for its idle TTL so a sibling screen reuses it).
        vm.callOnCleared()
        assertFalse("onCleared must release, not close, the warm transport", session.closed)
        leaseManager.close()
    }

    @Test
    fun reEnteringADirectoryPaintsTheCachedListingInstantly() = runBlocking {
        val session = FakeSshSession()
        val leaseManager = SshLeaseManager(
            connector = CountingConnector(session),
            idleTtlMillis = 30_000L,
        )
        val vm = FileExplorerViewModel(leaseManager)

        vm.start(request("/srv"))
        val root = vm.state.awaitReady()

        // Descend then go back up: re-entering /srv must show the cached entries
        // immediately (no Loading flicker) before the live reconcile lands.
        vm.openDirectory(root.entries.first { it.type == RemoteEntry.Type.DIRECTORY })
        vm.state.awaitReady { it.currentPath.endsWith("/sub") }
        vm.goUp()
        // Synchronously after goUp(), before the reconcile job runs, the state is
        // already Ready from cache — not Loading.
        val immediate = vm.state.value
        assertTrue(
            "re-enter must paint cached listing instantly, was $immediate",
            immediate is FileExplorerUiState.Ready && immediate.currentPath == "/srv",
        )
        val reconciled = vm.state.awaitReady { it.currentPath == "/srv" }
        assertEquals("/srv", reconciled.currentPath)
        leaseManager.close()
    }

    private suspend fun StateFlow<FileExplorerUiState>.awaitReady(
        predicate: (FileExplorerUiState.Ready) -> Boolean = { true },
    ): FileExplorerUiState.Ready {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val s = value
            if (s is FileExplorerUiState.Ready && predicate(s)) return s
            kotlinx.coroutines.delay(20)
        }
        error("explorer never reached the expected Ready state; was ${value}")
    }

    private fun request(startDir: String) = FileExplorerViewModel.Request(
        hostId = 1L,
        hostname = "10.0.2.2",
        port = 2222,
        username = "tester",
        keyPath = "/tmp/key",
        passphrase = null,
        startDir = startDir,
    )

    private fun FileExplorerViewModel.callOnCleared() {
        val m = androidx.lifecycle.ViewModel::class.java.getDeclaredMethod("onCleared")
        m.isAccessible = true
        m.invoke(this)
    }

    private class CountingConnector(
        private val session: FakeSshSession,
    ) : SshLeaseConnector {
        var connectCount: Int = 0

        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
            connectCount += 1
            return Result.success(session)
        }
    }

    /**
     * Minimal in-memory remote filesystem: any directory lists one subfolder +
     * one file; `cd … && pwd -P` echoes the requested absolute path so
     * canonicalize succeeds.
     */
    private class FakeSshSession : SshSession {
        var closed: Boolean = false

        override val isConnected: Boolean
            get() = !closed

        override suspend fun exec(command: String): ExecResult {
            // canonicalize(): "cd '<path>' 2>/dev/null && pwd -P"
            val path = Regex("cd '(.*?)' 2>/dev/null").find(command)?.groupValues?.get(1)
            return if (path != null && path.startsWith("/")) {
                ExecResult(stdout = "$path\n", stderr = "", exitCode = 0)
            } else {
                ExecResult(stdout = "", stderr = "", exitCode = 1)
            }
        }

        override suspend fun listDirectory(remotePath: String, maxEntries: Int): RemoteListing =
            RemoteListing(
                entries = listOf(
                    RemoteEntry(
                        name = "sub",
                        type = RemoteEntry.Type.DIRECTORY,
                        sizeBytes = 0L,
                        modifiedEpochSec = null,
                    ),
                    RemoteEntry(
                        name = "a.txt",
                        type = RemoteEntry.Type.FILE,
                        sizeBytes = 3L,
                        modifiedEpochSec = null,
                    ),
                ),
                truncated = false,
            )

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
