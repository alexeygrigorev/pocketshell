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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #1155: when a persisted session is confirmed GENUINELY GONE on attach
 * ([SessionLifecycleSignals.staleSessions], emitted only from the
 * `TmuxSessionNotFoundException` path — NOT a transient reconnect), the bound
 * folder tree drops the dead row from its list so the list stays accurate.
 *
 * The USER-FACING "This session no longer exists — create in this folder, or go
 * home?" recovery PROMPT is owned app-level by
 * [com.pocketshell.app.tmux.StaleSessionPromptController] (so it also surfaces on
 * the cold-restore path where this view model never exists), covered by
 * `StaleSessionPromptControllerTest`; the genuinely-gone-vs-transient distinction
 * is proven on the emitter side in `TmuxSessionWarmOpenTest`
 * (`goneSessionBroadcastsStaleSignal…` / `liveSessionDoesNotBroadcastStale…`).
 * This test covers only the tree's row-accuracy behaviour.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class FolderListViewModelStaleSessionTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val viewModelStore = ViewModelStore()
    private var nextViewModelKey: Int = 0

    @After
    fun tearDown() {
        viewModelStore.clear()
    }

    private val goneFolder = "/home/alexey/git/pocketshell"

    // ---- a genuinely-gone session drops its row from the bound tree -----------

    @Test
    fun staleSessionDropsDeadRowForBoundHost() = runTest {
        val signals = SessionLifecycleSignals()
        val gateway = StubGateway(listOf(sessionRow("git-pocketshell"), sessionRow("beta")))
        val vm = newViewModel(gateway = gateway, signals = signals)
        try {
            bind(vm)
            runCurrent()
            // The user navigated onward to the (now gone) session -> tree paused.
            vm.stopPolling()
            runCurrent()
            assertTrue(
                "the row is present before the gone-session signal",
                "git-pocketshell" in readySessionNames(vm),
            )

            // The session was confirmed genuinely gone on attach. The USER-FACING
            // recovery prompt is owned app-level (StaleSessionPromptController);
            // the tree just keeps its list accurate by dropping the dead row.
            signals.emitStaleSession(HOST.id, "git-pocketshell", goneFolder)
            runCurrent()

            assertTrue(
                "the confirmed-gone session drops from the tree",
                "git-pocketshell" !in readySessionNames(vm),
            )
            assertTrue(
                "an unrelated live row is untouched",
                "beta" in readySessionNames(vm),
            )
        } finally {
            vm.stopPolling()
        }
    }

    // ---- host filter: a gone session on ANOTHER host must not drop OUR row -----

    @Test
    fun staleSessionForOtherHostDoesNotDropRow() = runTest {
        val signals = SessionLifecycleSignals()
        val gateway = StubGateway(listOf(sessionRow("git-pocketshell")))
        val vm = newViewModel(gateway = gateway, signals = signals)
        try {
            bind(vm)
            runCurrent()

            signals.emitStaleSession(HOST.id + 1, "git-pocketshell", goneFolder)
            runCurrent()

            assertTrue(
                "a gone session on another host must not drop this host's row",
                "git-pocketshell" in readySessionNames(vm),
            )
        } finally {
            vm.stopPolling()
        }
    }

    // ---- class coverage: app-created removal (via the app) also drops the row --

    @Test
    fun appCreatedRemovalDropsRow() = runTest {
        val signals = SessionLifecycleSignals()
        val gateway = StubGateway(listOf(sessionRow("git-pocketshell"), sessionRow("beta")))
        val vm = newViewModel(gateway = gateway, signals = signals)
        try {
            bind(vm)
            runCurrent()
            vm.stopPolling()
            runCurrent()

            // The COMMON case: the user removed the session THROUGH the app — the
            // tree drops the row immediately (external removal is the gone-on-open
            // case the app-level recovery prompt covers).
            signals.emitKilled(HOST.id, "git-pocketshell")
            runCurrent()

            assertTrue("app-created removal drops the row", "git-pocketshell" !in readySessionNames(vm))
            assertTrue("the sibling row survives", "beta" in readySessionNames(vm))
        } finally {
            vm.stopPolling()
        }
    }

    // --- helpers ---------------------------------------------------------------

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

    private fun readySessionNames(vm: FolderListViewModel): Set<String> {
        val state = vm.state.value as FolderListUiState.Ready
        return state.flatSessions.map { it.sessionName }.toSet()
    }

    private fun sessionRow(name: String): FolderSessionRow =
        FolderSessionRow(
            sessionName = name,
            lastActivity = 1_000L,
            attached = false,
            cwd = "/home/alexey/git/$name",
        )

    private fun TestScope.newViewModel(
        gateway: FolderListGateway,
        signals: SessionLifecycleSignals,
    ): FolderListViewModel {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        return FolderListViewModel(
            gateway = gateway,
            hostDao = FakeHostDao(HOST),
            projectRootDao = FakeProjectRootDao(),
            sshLeaseManager = SshLeaseManager(
                connector = SshLeaseConnector {
                    Result.failure(IllegalStateException("prewarm disabled for stale-session test"))
                },
                scope = this,
                idleTtlMillis = 0L,
                connectTimeoutContext = dispatcher,
                nowMillis = { testScheduler.currentTime },
            ),
            forwardingController = ForwardingController(ApplicationProvider.getApplicationContext()),
            sessionLifecycleSignals = signals,
            attachLifecycle = false,
        ).also {
            it.ioDispatcher = dispatcher
            it.treeDispatcher = dispatcher
            it.setProcessStartedForTest(true)
            viewModelStore.put("FolderListViewModel-${nextViewModelKey++}", it)
        }
    }

    private class StubGateway(
        @Volatile var rows: List<FolderSessionRow>,
    ) : FolderListGateway {
        val createdSessions: MutableList<Pair<String, String>> = mutableListOf()

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
        ): Result<String> {
            createdSessions.add(sessionName to cwd)
            return Result.success(sessionName)
        }

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
