package com.pocketshell.app.projects

import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.app.portfwd.ForwardingController
import com.pocketshell.app.tmux.SessionLifecycleSignals
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.dao.ProjectRootDao
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.ProjectRootEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #471: folders with ≥1 active session auto-expand by default so running
 * sessions are visible at a glance — but auto-expand must RESPECT manual
 * collapse. A folder the user collapses stays collapsed across the 5 s
 * discovery poll / re-emission until the user expands it again. Empty folders
 * never auto-expand.
 *
 * The collapse-stickiness test (`pollDoesNotReExpandUserCollapsedFolder`) is the
 * whole point of the issue — the naive `union(activePaths)` on every emission
 * fought the user, which is why it was pulled from #470.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class FolderListAutoExpandTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val viewModelStore = ViewModelStore()
    private var nextViewModelKey: Int = 0

    @After
    fun tearDown() {
        viewModelStore.clear()
    }

    // --- Pure helper: the stickiness invariant in isolation -----------------

    @Test
    fun resolveExpanded_autoExpandsActiveFolders() {
        val expanded = FolderListViewModel.resolveExpandedProjectPaths(
            previousExpanded = emptySet(),
            visibleProjectPaths = setOf("/git/alpha", "/git/beta"),
            activeProjectPaths = setOf("/git/alpha", "/git/beta"),
            userCollapsedProjectPaths = emptySet(),
        )
        assertTrue("active folder alpha should auto-expand", "/git/alpha" in expanded)
        assertTrue("active folder beta should auto-expand", "/git/beta" in expanded)
    }

    @Test
    fun resolveExpanded_doesNotReExpandUserCollapsedActiveFolder() {
        // A folder with active sessions the user collapsed must NOT re-expand
        // on the next emission, even though it is still in activeProjectPaths.
        val expanded = FolderListViewModel.resolveExpandedProjectPaths(
            previousExpanded = setOf("/git/beta"),
            visibleProjectPaths = setOf("/git/alpha", "/git/beta"),
            activeProjectPaths = setOf("/git/alpha", "/git/beta"),
            userCollapsedProjectPaths = setOf("/git/alpha"),
        )
        assertFalse("user-collapsed active folder must stay collapsed", "/git/alpha" in expanded)
        assertTrue("other active folder still auto-expands", "/git/beta" in expanded)
    }

    @Test
    fun resolveExpanded_emptyFoldersNeverAutoExpand() {
        val expanded = FolderListViewModel.resolveExpandedProjectPaths(
            previousExpanded = emptySet(),
            visibleProjectPaths = setOf("/git/alpha", "/git/empty"),
            activeProjectPaths = setOf("/git/alpha"),
            userCollapsedProjectPaths = emptySet(),
        )
        assertTrue("active folder auto-expands", "/git/alpha" in expanded)
        assertFalse("empty folder stays collapsed", "/git/empty" in expanded)
    }

    @Test
    fun resolveExpanded_prunesVanishedPaths() {
        val expanded = FolderListViewModel.resolveExpandedProjectPaths(
            previousExpanded = setOf("/git/gone"),
            visibleProjectPaths = setOf("/git/alpha"),
            activeProjectPaths = setOf("/git/alpha"),
            userCollapsedProjectPaths = emptySet(),
        )
        assertFalse("path that disappeared is pruned", "/git/gone" in expanded)
        assertTrue("/git/alpha" in expanded)
    }

    // --- View-model path: first ready + collapse stickiness across a poll ----

    @Test
    fun folderWithActiveSessionAutoExpandsOnFirstReady() = runTest {
        val gateway = StubGateway(listOf(sessionRow("alpha"), sessionRow("beta")))
        val vm = newViewModel(gateway)
        try {
            bind(vm)
            runCurrent()
            assertTrue(
                "folder with an active session must auto-expand on first ready",
                folderPath("alpha") in expandedPaths(vm),
            )
            assertTrue(folderPath("beta") in expandedPaths(vm))
        } finally {
            vm.stopPolling()
        }
    }

    @Test
    fun pollDoesNotReExpandUserCollapsedFolder() = runTest {
        // THE issue #471 invariant: the user collapses a folder that has active
        // sessions; a subsequent discovery poll / re-emission must NOT re-open
        // it. Without the user-collapsed memory, the re-emit's auto-expand
        // would spring it back open and fight the user.
        val gateway = StubGateway(listOf(sessionRow("alpha"), sessionRow("beta")))
        val vm = newViewModel(gateway)
        try {
            bind(vm)
            runCurrent()
            val alphaPath = folderPath("alpha")
            assertTrue("auto-expanded on first ready", alphaPath in expandedPaths(vm))

            // User manually collapses the folder.
            vm.toggleProjectExpanded(alphaPath)
            runCurrent()
            assertFalse("collapses on user tap", alphaPath in expandedPaths(vm))

            // A discovery poll re-emits the SAME active sessions (re-using
            // refresh() to drive a fresh probe + emitReady, exactly like the
            // 5 s poll tick).
            vm.refresh()
            runCurrent()
            assertFalse(
                "poll/re-emit must NOT re-expand a user-collapsed active folder",
                alphaPath in expandedPaths(vm),
            )

            // The user can still re-open it, which restores auto-expand.
            vm.toggleProjectExpanded(alphaPath)
            runCurrent()
            assertTrue("user re-expands the folder", alphaPath in expandedPaths(vm))
            vm.refresh()
            runCurrent()
            assertTrue(
                "after the user re-expands, auto-expand applies again",
                alphaPath in expandedPaths(vm),
            )
        } finally {
            vm.stopPolling()
        }
    }

    // --- helpers ------------------------------------------------------------

    private fun bind(vm: FolderListViewModel) {
        vm.bind(
            hostId = HOST.id,
            hostName = HOST.name,
            hostname = HOST.hostname,
            port = HOST.port,
            username = HOST.username,
            keyPath = KEY_PATH,
            passphrase = null,
        )
    }

    private fun expandedPaths(vm: FolderListViewModel): Set<String> {
        val state = vm.state.value as FolderListUiState.Ready
        return state.expandedProjectPaths
    }

    private fun folderPath(name: String): String =
        FolderListViewModel.canonicalisePath("/home/alexey/git/$name")

    private fun sessionRow(name: String): FolderSessionRow =
        FolderSessionRow(
            sessionName = name,
            lastActivity = 1_000L,
            attached = true,
            cwd = "/home/alexey/git/$name",
        )

    private fun TestScope.newViewModel(gateway: FolderListGateway): FolderListViewModel {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        return FolderListViewModel(
            gateway = gateway,
            hostDao = FakeHostDao(HOST),
            projectRootDao = FakeProjectRootDao(),
            sshLeaseManager = SshLeaseManager(
                connector = SshLeaseConnector {
                    Result.failure(IllegalStateException("prewarm disabled for auto-expand test"))
                },
                scope = this,
                idleTtlMillis = 0L,
            ),
            forwardingController = ForwardingController(ApplicationProvider.getApplicationContext()),
            sessionLifecycleSignals = SessionLifecycleSignals(),
            attachLifecycle = false,
        ).also {
            it.ioDispatcher = dispatcher
            it.setProcessStartedForTest(true)
            viewModelStore.put("FolderListViewModel-${nextViewModelKey++}", it)
        }
    }

    private class StubGateway(
        @Volatile var rows: List<FolderSessionRow>,
    ) : FolderListGateway {
        override suspend fun listSessionsWithFolder(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            watchedRoots: List<ProjectRootEntity>,
        ): FolderListResult = FolderListResult.Sessions(rows = rows)

        override suspend fun createSession(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            sessionName: String,
            cwd: String,
            startCommand: String?,
        ): Result<String> = error("not used")

        override suspend fun createEmptyProject(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            parentPath: String,
            folderName: String,
        ): Result<String> = error("not used")

        override suspend fun importFile(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            folderPath: String,
            payload: FolderImportPayload,
        ): Result<String> = error("not used")

        override suspend fun killSession(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            sessionName: String,
        ): Result<Unit> = error("not used")
    }

    private class FakeHostDao(private val host: HostEntity) : HostDao {
        override fun getAll(): Flow<List<HostEntity>> = flowOf(listOf(host))
        override suspend fun getById(id: Long): HostEntity? = host.takeIf { it.id == id }
        override fun getEnabled(): Flow<List<HostEntity>> = flowOf(listOf(host))
        override suspend fun insert(host: HostEntity): Long = error("not used")
        override suspend fun update(host: HostEntity) = error("not used")
        override suspend fun delete(host: HostEntity) = error("not used")
        override suspend fun deleteById(id: Long) = error("not used")
    }

    private class FakeProjectRootDao : ProjectRootDao {
        private val roots = MutableStateFlow<List<ProjectRootEntity>>(emptyList())
        override fun getByHostId(hostId: Long): Flow<List<ProjectRootEntity>> = roots
        override suspend fun insert(root: ProjectRootEntity): Long = error("not used")
        override suspend fun update(root: ProjectRootEntity) = error("not used")
        override suspend fun delete(root: ProjectRootEntity) = error("not used")
    }

    private companion object {
        const val KEY_PATH: String = "/tmp/pocketshell-test-key"
        val HOST: HostEntity = HostEntity(
            id = 42L,
            name = "docker",
            hostname = "10.0.2.2",
            port = 2222,
            username = "testuser",
            keyId = 7L,
        )
    }
}
