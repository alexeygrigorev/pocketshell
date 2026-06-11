package com.pocketshell.app.projects

import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.app.portfwd.ForwardingController
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.app.tmux.FakeTmuxClient
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.dao.ProjectRootDao
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.ProjectRootEntity
import com.pocketshell.core.tmux.protocol.ControlEvent
import com.pocketshell.uikit.model.SessionAgentKind
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #706: a session created OUT-OF-BAND (another terminal, an agent
 * spawning a tmux session — anything that does NOT route through the app's own
 * `createSession`/`insertSession`) must appear in the host-detail picker within
 * seconds, not after the 15-min held-tree staleness gate.
 *
 * The fix subscribes the held tree ([FolderListViewModel]) to the bound host's
 * live `tmux -CC` client's `%sessions-changed`
 * ([ControlEvent.SessionsChanged]) event as a DEBOUNCED, foreground-only
 * reconcile trigger. These tests drive the [FakeTmuxClient]'s event bus and
 * assert the reconcile fires (a fresh gateway probe that picks up the new row),
 * confirming the D21-clean event-driven path (no poll/Timer).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class FolderListViewModelSessionsChangedTest {
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
    fun outOfBandSessionsChangedTriggersReconcileSoNewSessionAppears() = runTest {
        // First probe sees only the existing session; a session is created
        // out-of-band (not via the app), tmux emits `%sessions-changed`, and the
        // re-probe must pick up the new row.
        val gateway = SteppingGateway(
            listOf(
                FolderListResult.Sessions(rows = listOf(row("alpha"))),
                FolderListResult.Sessions(rows = listOf(row("alpha"), row("beta-out-of-band"))),
            ),
        )
        val client = FakeTmuxClient()
        val registry = ActiveTmuxClients().also {
            it.register(
                hostId = HOST.id,
                hostName = HOST.name,
                hostname = HOST.hostname,
                port = HOST.port,
                username = HOST.username,
                keyPath = KEY_PATH,
                client = client,
            )
        }
        val vm = newViewModel(gateway, registry, processStarted = true)

        try {
            bind(vm)
            runCurrent()

            // Cold open reconcile: only the pre-existing session is shown.
            val first = vm.state.value as FolderListUiState.Ready
            assertEquals(listOf("alpha"), first.flatSessions.map { it.sessionName })
            assertEquals(1, gateway.callCount)

            // Out-of-band create: the live `-CC` client emits `%sessions-changed`.
            client.emittedEvents.emit(ControlEvent.SessionsChanged)
            // Let the debounce window elapse + the reconcile run.
            advanceTimeBy(FolderListViewModel.SESSIONS_CHANGED_DEBOUNCE_MS + 50L)
            runCurrent()

            assertEquals(
                "a %sessions-changed event must trigger exactly one (debounced) reconcile",
                2,
                gateway.callCount,
            )
            val after = vm.state.value as FolderListUiState.Ready
            assertEquals(
                "the out-of-band session must now appear in the picker",
                listOf("alpha", "beta-out-of-band"),
                after.flatSessions.map { it.sessionName },
            )
        } finally {
            vm.stopPolling()
        }
    }

    @Test
    fun sessionsChangedBurstIsDebouncedIntoOneReconcile() = runTest {
        val gateway = SteppingGateway(
            listOf(
                FolderListResult.Sessions(rows = listOf(row("alpha"))),
                FolderListResult.Sessions(rows = listOf(row("alpha"), row("beta"))),
            ),
        )
        val client = FakeTmuxClient()
        val registry = ActiveTmuxClients().also {
            it.register(
                hostId = HOST.id,
                hostName = HOST.name,
                hostname = HOST.hostname,
                port = HOST.port,
                username = HOST.username,
                keyPath = KEY_PATH,
                client = client,
            )
        }
        val vm = newViewModel(gateway, registry, processStarted = true)

        try {
            bind(vm)
            runCurrent()
            assertEquals(1, gateway.callCount)

            // A rapid burst (create + window-add etc.) within the debounce window.
            client.emittedEvents.emit(ControlEvent.SessionsChanged)
            client.emittedEvents.emit(ControlEvent.SessionsChanged)
            client.emittedEvents.emit(ControlEvent.SessionsChanged)
            advanceTimeBy(FolderListViewModel.SESSIONS_CHANGED_DEBOUNCE_MS + 50L)
            runCurrent()

            assertEquals(
                "a burst of %sessions-changed must coalesce into a single reconcile",
                2,
                gateway.callCount,
            )
        } finally {
            vm.stopPolling()
        }
    }

    @Test
    fun sessionsChangedDoesNotReconcileWhileBackgrounded() = runTest {
        // D21 / foreground-only: a %sessions-changed that lands while
        // backgrounded must NOT re-probe — the reconcile is gated on the
        // process-foreground signal and runs only after the next foreground.
        val gateway = SteppingGateway(
            listOf(
                FolderListResult.Sessions(rows = listOf(row("alpha"))),
                FolderListResult.Sessions(rows = listOf(row("alpha"), row("beta"))),
            ),
        )
        val client = FakeTmuxClient()
        val registry = ActiveTmuxClients().also {
            it.register(
                hostId = HOST.id,
                hostName = HOST.name,
                hostname = HOST.hostname,
                port = HOST.port,
                username = HOST.username,
                keyPath = KEY_PATH,
                client = client,
            )
        }
        val vm = newViewModel(gateway, registry, processStarted = true)

        try {
            bind(vm)
            runCurrent()
            assertEquals(1, gateway.callCount)

            // Background, then a %sessions-changed lands.
            vm.setProcessStartedForTest(false)
            client.emittedEvents.emit(ControlEvent.SessionsChanged)
            advanceTimeBy(FolderListViewModel.SESSIONS_CHANGED_DEBOUNCE_MS + 50L)
            runCurrent()

            assertEquals(
                "no SSH reconcile may run while backgrounded (D21)",
                1,
                gateway.callCount,
            )

            // Foregrounding releases the gated reconcile.
            vm.setProcessStartedForTest(true)
            runCurrent()
            assertEquals(
                "the gated reconcile runs once on the next foreground",
                2,
                gateway.callCount,
            )
            val after = vm.state.value as FolderListUiState.Ready
            assertTrue(after.flatSessions.map { it.sessionName }.contains("beta"))
        } finally {
            vm.stopPolling()
        }
    }

    private fun TestScope.newViewModel(
        gateway: FolderListGateway,
        registry: ActiveTmuxClients,
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
                    Result.failure(IllegalStateException("prewarm disabled for sessions-changed test"))
                },
                scope = this,
                idleTtlMillis = 0L,
            ),
            applicationContext = context,
            hostSessionListCache = cache,
            forwardingController = ForwardingController(context),
            activeTmuxClients = registry,
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
    ): FolderSessionRow =
        FolderSessionRow(
            sessionName = name,
            lastActivity = lastActivity,
            attached = false,
            cwd = cwd,
            agentKind = SessionAgentKind.Shell,
            windows = emptyList(),
        )

    /**
     * Returns each scripted result in order, then repeats the last one. Lets a
     * test assert that the N-th probe sees a different (post-out-of-band)
     * session set than the cold-open probe.
     */
    private class SteppingGateway(
        private val results: List<FolderListResult>,
    ) : BaseGateway() {
        var callCount: Int = 0
            private set

        override suspend fun listSessionsWithFolder(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            watchedRoots: List<ProjectRootEntity>,
        ): FolderListResult {
            val index = callCount.coerceAtMost(results.lastIndex)
            callCount += 1
            return results[index]
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
            id = 706L,
            name = "docker",
            hostname = "10.0.2.2",
            port = 2222,
            username = "testuser",
            keyId = 7L,
        )
    }
}
