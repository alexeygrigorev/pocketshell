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
import com.pocketshell.core.ssh.SortField
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream

/**
 * Issue #762 (slice 2) — the header Sort menu re-orders the listing through the
 * real [FileExplorerViewModel.setSort]. The comparator itself is unit-tested in
 * core-ssh ([com.pocketshell.core.ssh.RemoteEntryTest]) and the menu's
 * tag/callback wiring is covered on-device ([FileExplorerScaffoldTest]). This
 * fills the gap between them: that `setSort` applied to a LIVE `Ready` state
 * actually re-orders the shown entries for each field/direction AND does it as a
 * pure in-memory reorder with NO extra `listDirectory` round-trip (the
 * acceptance "re-sorts in memory, no re-list").
 *
 * JVM-level, no emulator: a counting fake session proves the list-call count
 * does not change across re-sorts, and the shown entry order is asserted per
 * mode against a fixture with distinct names, sizes, and mtimes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class FileExplorerSortTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun setSortReordersEachModeInMemoryWithoutReListing() = runBlocking {
        val session = SortFixtureSession()
        val leaseManager = SshLeaseManager(
            connector = SingleSessionConnector(session),
            idleTtlMillis = 30_000L,
        )
        val vm = FileExplorerViewModel(leaseManager)

        vm.start(request("/srv"))
        val ready = vm.state.awaitReady()
        val listsAfterInitialLoad = session.listCount

        // Default = NAME ascending: folders first (case-insensitive), then files
        // by name. Mirrors the pre-Sort-menu FOLDERS_FIRST default.
        assertEquals(
            "default sort is Name-ascending, folders first",
            listOf("dproj", "Zdir", "alpha.txt", "Beta.log", "zoo.png"),
            ready.entries.map { it.name },
        )

        // NAME descending: folders still first; the whole within-group order
        // (including the case-insensitive name) is reversed.
        vm.setSort(SortField.NAME, ascending = false)
        assertEquals(
            listOf("Zdir", "dproj", "zoo.png", "Beta.log", "alpha.txt"),
            vm.requireReady().entries.map { it.name },
        )

        // SIZE ascending: folders first (tie at size 0 → name tie-break asc),
        // then files smallest→largest (10 < 500 < 9000).
        vm.setSort(SortField.SIZE, ascending = true)
        assertEquals(
            listOf("dproj", "Zdir", "Beta.log", "alpha.txt", "zoo.png"),
            vm.requireReady().entries.map { it.name },
        )

        // SIZE descending: the within-group order is reversed (folder name
        // tie-break flips too), files largest→smallest, folders still first.
        vm.setSort(SortField.SIZE, ascending = false)
        assertEquals(
            listOf("Zdir", "dproj", "zoo.png", "alpha.txt", "Beta.log"),
            vm.requireReady().entries.map { it.name },
        )

        // MODIFIED ascending: ordered by mtime; folders 50<60, files oldest→
        // newest with the no-mtime file (Beta.log) sinking last within files.
        vm.setSort(SortField.MODIFIED, ascending = true)
        assertEquals(
            listOf("Zdir", "dproj", "alpha.txt", "zoo.png", "Beta.log"),
            vm.requireReady().entries.map { it.name },
        )

        // MODIFIED descending: newest→oldest (folders 60>50), but the null mtime
        // STILL sinks last — a missing date is "unknown", not "newest/oldest".
        vm.setSort(SortField.MODIFIED, ascending = false)
        assertEquals(
            listOf("dproj", "Zdir", "zoo.png", "alpha.txt", "Beta.log"),
            vm.requireReady().entries.map { it.name },
        )

        // The acceptance: every one of those six re-sorts was a pure in-memory
        // reorder — not one of them triggered another listDirectory round-trip.
        assertEquals(
            "Sort must re-order in memory with no re-list",
            listsAfterInitialLoad,
            session.listCount,
        )

        leaseManager.close()
    }

    @Test
    fun setSortToTheActiveModeIsANoOpAndDoesNotReList() = runBlocking {
        val session = SortFixtureSession()
        val leaseManager = SshLeaseManager(
            connector = SingleSessionConnector(session),
            idleTtlMillis = 30_000L,
        )
        val vm = FileExplorerViewModel(leaseManager)

        vm.start(request("/srv"))
        vm.state.awaitReady()
        val before = session.listCount

        // Re-selecting the already-active sort (the default Name-asc) is a no-op.
        vm.setSort(SortField.NAME, ascending = true)
        assertEquals("re-selecting the active sort must not re-list", before, session.listCount)

        leaseManager.close()
    }

    private fun FileExplorerViewModel.requireReady(): FileExplorerUiState.Ready {
        val s = state.value
        check(s is FileExplorerUiState.Ready) { "expected Ready, was $s" }
        return s
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
        error("explorer never reached the expected Ready state; was $value")
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

    private class SingleSessionConnector(
        private val session: SshSession,
    ) : SshLeaseConnector {
        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> =
            Result.success(session)
    }

    /**
     * Fixture filesystem with two folders (one mixed-case) and three files with
     * distinct sizes + mtimes (one file with no server mtime) so each sort field
     * and direction produces a distinct, unambiguous order. Counts
     * `listDirectory` calls so the test can prove a re-sort never re-lists.
     */
    private class SortFixtureSession : SshSession {
        var listCount: Int = 0
        private var closed: Boolean = false

        override val isConnected: Boolean
            get() = !closed

        override suspend fun exec(command: String): ExecResult {
            val path = Regex("cd '(.*?)' 2>/dev/null").find(command)?.groupValues?.get(1)
            return if (path != null && path.startsWith("/")) {
                ExecResult(stdout = "$path\n", stderr = "", exitCode = 0)
            } else {
                ExecResult(stdout = "", stderr = "", exitCode = 1)
            }
        }

        override suspend fun listDirectory(remotePath: String, maxEntries: Int): RemoteListing {
            listCount += 1
            return RemoteListing(
                entries = listOf(
                    RemoteEntry("Zdir", RemoteEntry.Type.DIRECTORY, 0L, 50L),
                    RemoteEntry("dproj", RemoteEntry.Type.DIRECTORY, 0L, 60L),
                    RemoteEntry("alpha.txt", RemoteEntry.Type.FILE, 500L, 100L),
                    RemoteEntry("Beta.log", RemoteEntry.Type.FILE, 10L, null),
                    RemoteEntry("zoo.png", RemoteEntry.Type.FILE, 9000L, 300L),
                ),
                truncated = false,
            )
        }

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
