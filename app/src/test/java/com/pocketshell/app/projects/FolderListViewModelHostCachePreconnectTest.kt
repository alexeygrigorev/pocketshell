package com.pocketshell.app.projects

import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.app.portfwd.ForwardingController
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.dao.ProjectRootDao
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.ProjectRootEntity
import com.pocketshell.uikit.model.SessionAgentKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class FolderListViewModelHostCachePreconnectTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var context: Context
    private lateinit var cache: HostSessionListCache
    private val viewModelStore = ViewModelStore()
    private var nextViewModelKey: Int = 0

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("host_session_list_cache", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        cache = HostSessionListCache(context)
    }

    @After
    fun tearDown() {
        viewModelStore.clear()
    }

    @Test
    fun cachedSessionsRenderBeforeSshRefreshFinishes() = runTest {
        cache.save(HOST.id, listOf(row("cached", cwd = "/home/alexey/cached", lastActivity = 10L)))
        val liveResult = CompletableDeferred<FolderListResult>()
        val gateway = BlockingGateway(liveResult)
        val vm = newViewModel(gateway, processStarted = true)

        try {
            bind(vm)
            runCurrent()

            val cached = vm.state.value as FolderListUiState.Ready
            assertEquals(listOf("cached"), cached.flatSessions.map { it.sessionName })
            assertTrue("cached list should advertise the in-flight live refresh", cached.isRefreshing)
            assertEquals(1, gateway.callCount)

            liveResult.complete(
                FolderListResult.Sessions(rows = listOf(row("live", cwd = "/home/alexey/live", lastActivity = 20L))),
            )
            runCurrent()

            val live = vm.state.value as FolderListUiState.Ready
            assertEquals(listOf("live"), live.flatSessions.map { it.sessionName })
            assertFalse(live.isRefreshing)
            assertEquals(listOf("live"), cache.read(HOST.id)?.rows?.map { it.sessionName })
        } finally {
            vm.stopPolling()
        }
    }

    @Test
    fun liveRefreshReconcilesCachedRows() = runTest {
        cache.save(
            HOST.id,
            listOf(
                row("stale", cwd = "/home/alexey/stale", lastActivity = 1L),
                row("alpha", cwd = "/home/alexey/old-alpha", lastActivity = 2L),
            ),
        )
        val gateway = ScriptedGateway(
            FolderListResult.Sessions(
                rows = listOf(
                    row("alpha", cwd = "/home/alexey/new-alpha", lastActivity = 30L, attached = true),
                    row("beta", cwd = "/home/alexey/beta", lastActivity = 20L),
                ),
            ),
        )
        val vm = newViewModel(gateway, processStarted = true)

        try {
            bind(vm)
            runCurrent()

            val state = vm.state.value as FolderListUiState.Ready
            assertEquals(listOf("alpha", "beta"), state.flatSessions.map { it.sessionName })
            assertTrue(state.flatSessions.first { it.sessionName == "alpha" }.attached)
            assertEquals(listOf("alpha", "beta"), cache.read(HOST.id)?.rows?.map { it.sessionName })
        } finally {
            vm.stopPolling()
        }
    }

    @Test
    fun foregroundVisibilityStartsReconcileWithoutTappingSession() = runTest {
        // EPIC #679 requirement #2: a foreground/resume reconciles the held tree
        // ONLY when it is stale (or never reconciled), not on every transition.
        // The first foreground reconciles (the tree was never reconciled); a
        // rapid background->foreground bounce within the staleness window does
        // NOT re-probe (the held tree is still fresh).
        val gateway = ScriptedGateway(FolderListResult.Sessions(rows = listOf(row("alpha"))))
        val vm = newViewModel(gateway, processStarted = false)

        try {
            bind(vm)
            runCurrent()
            assertEquals("backgrounded host screen should not probe yet", 0, gateway.callCount)

            vm.setProcessStartedForTest(true)
            runCurrent()
            assertEquals(
                "foregrounding the visible host screen reconciles the never-reconciled tree",
                1,
                gateway.callCount,
            )

            // A quick background->foreground bounce must NOT re-probe — the held
            // tree was just reconciled and is not stale (requirement #2).
            vm.setProcessStartedForTest(false)
            vm.setProcessStartedForTest(true)
            advanceTimeBy(100L)
            runCurrent()

            assertEquals(
                "a non-stale held tree must NOT re-probe on a quick re-foreground",
                1,
                gateway.callCount,
            )
        } finally {
            vm.stopPolling()
        }
    }

    @Test
    fun cacheRoundTripsRowsAndWindows() {
        cache.save(
            HOST.id,
            listOf(
                row(
                    name = "agent",
                    cwd = "/work/app",
                    lastActivity = 42L,
                    attached = true,
                    agentKind = SessionAgentKind.Codex,
                    windows = listOf(
                        FolderSessionWindowRow(
                            sessionName = "agent",
                            index = 1,
                            name = "editor",
                            active = true,
                            cwd = "/work/app",
                            tty = "/dev/pts/1",
                            command = "codex",
                            agentKind = SessionAgentKind.Codex,
                        ),
                    ),
                ),
            ),
            savedAtMillis = 123L,
        )

        val snapshot = cache.read(HOST.id)

        assertNotNull(snapshot)
        requireNotNull(snapshot)
        assertEquals(123L, snapshot.savedAtMillis)
        assertEquals("agent", snapshot.rows.single().sessionName)
        assertEquals(SessionAgentKind.Codex, snapshot.rows.single().agentKind)
        assertEquals("editor", snapshot.rows.single().windows.single().name)
    }

    private fun TestScope.newViewModel(
        gateway: FolderListGateway,
        processStarted: Boolean,
    ): FolderListViewModel {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        return FolderListViewModel(
            gateway = gateway,
            hostDao = FakeHostDao(HOST),
            projectRootDao = FakeProjectRootDao(),
            sshLeaseManager = SshLeaseManager(
                connector = SshLeaseConnector {
                    Result.failure(IllegalStateException("prewarm disabled for cache preconnect test"))
                },
                scope = this,
                idleTtlMillis = 0L,
            ),
            applicationContext = context,
            hostSessionListCache = cache,
            forwardingController = ForwardingController(context),
            attachLifecycle = false,
        ).also {
            it.ioDispatcher = dispatcher
            it.setProcessStartedForTest(processStarted)
            viewModelStore.put("FolderListViewModel-${nextViewModelKey++}", it)
        }
    }

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

    private fun row(
        name: String,
        cwd: String = "/home/alexey/$name",
        lastActivity: Long = 1L,
        attached: Boolean = false,
        agentKind: SessionAgentKind = SessionAgentKind.Shell,
        windows: List<FolderSessionWindowRow> = emptyList(),
    ): FolderSessionRow =
        FolderSessionRow(
            sessionName = name,
            lastActivity = lastActivity,
            attached = attached,
            cwd = cwd,
            agentKind = agentKind,
            windows = windows,
        )

    private class BlockingGateway(
        private val result: CompletableDeferred<FolderListResult>,
    ) : BaseGateway() {
        var callCount: Int = 0
            private set

        override suspend fun listSessionsWithFolder(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            watchedRoots: List<ProjectRootEntity>,
        ): FolderListResult {
            callCount += 1
            return result.await()
        }
    }

    private class ScriptedGateway(
        private val result: FolderListResult,
    ) : BaseGateway() {
        var callCount: Int = 0
            private set

        override suspend fun listSessionsWithFolder(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            watchedRoots: List<ProjectRootEntity>,
        ): FolderListResult {
            callCount += 1
            return result
        }
    }

    private abstract class BaseGateway : FolderListGateway {
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
            id = 620L,
            name = "docker",
            hostname = "10.0.2.2",
            port = 2222,
            username = "testuser",
            keyId = 7L,
        )
    }
}
