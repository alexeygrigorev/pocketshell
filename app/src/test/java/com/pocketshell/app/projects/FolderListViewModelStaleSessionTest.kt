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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #1155 (Part B): the folder tree's "This session no longer exists —
 * create a new session in this folder?" recovery prompt.
 *
 * When a persisted session the user tried to open is confirmed GENUINELY GONE on
 * attach ([SessionLifecycleSignals.staleSessions], emitted only from the
 * `TmuxSessionNotFoundException` path — NOT a transient reconnect), the tree
 * raises a [StaleSessionRecreatePrompt] instead of leaving the user on a blank
 * list. Confirming reuses the SAME folder's create-session path; dismissing
 * clears it. The genuinely-gone-vs-transient distinction itself is proven on the
 * emitter side in `TmuxSessionWarmOpenTest`
 * (`goneSessionBroadcastsStaleSignal…` / `liveSessionDoesNotBroadcastStale…`).
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

    // ---- a genuinely-gone session raises the recreate prompt (bound host) -----

    @Test
    fun staleSessionRaisesRecreatePromptForBoundHost() = runTest {
        val signals = SessionLifecycleSignals()
        val gateway = StubGateway(listOf(sessionRow("git-pocketshell"), sessionRow("beta")))
        val vm = newViewModel(gateway = gateway, signals = signals)
        try {
            bind(vm)
            runCurrent()
            // The user navigated onward to the (now gone) session -> tree paused.
            vm.stopPolling()
            runCurrent()
            assertNull("no prompt before the gone-session signal", vm.staleSessionPrompt.value)

            // The session was confirmed genuinely gone on attach.
            signals.emitStaleSession(HOST.id, "git-pocketshell", goneFolder)
            runCurrent()

            val prompt = vm.staleSessionPrompt.value
            assertTrue("a genuinely-gone session must raise the recreate prompt", prompt != null)
            assertEquals("git-pocketshell", prompt!!.sessionName)
            assertEquals(goneFolder, prompt.folderPath)
            // The confirmed-gone row is also dropped from the tree so the list the
            // prompt sits over is accurate.
            assertTrue(
                "the confirmed-gone session drops from the tree",
                "git-pocketshell" !in readySessionNames(vm),
            )
        } finally {
            vm.stopPolling()
        }
    }

    // ---- host filter: a gone session on ANOTHER host must not prompt ----------

    @Test
    fun staleSessionForOtherHostDoesNotPrompt() = runTest {
        val signals = SessionLifecycleSignals()
        val gateway = StubGateway(listOf(sessionRow("git-pocketshell")))
        val vm = newViewModel(gateway = gateway, signals = signals)
        try {
            bind(vm)
            runCurrent()

            signals.emitStaleSession(HOST.id + 1, "git-pocketshell", goneFolder)
            runCurrent()

            assertNull(
                "a gone session on another host must not raise this host's prompt",
                vm.staleSessionPrompt.value,
            )
        } finally {
            vm.stopPolling()
        }
    }

    // ---- confirm recreates in the SAME folder and routes to the new session ---

    @Test
    fun confirmRecreatesSessionInSameFolderAndRoutes() = runTest {
        val signals = SessionLifecycleSignals()
        val gateway = StubGateway(listOf(sessionRow("beta")))
        val vm = newViewModel(gateway = gateway, signals = signals)
        try {
            bind(vm)
            runCurrent()
            signals.emitStaleSession(HOST.id, "git-pocketshell", goneFolder)
            runCurrent()
            assertTrue(vm.staleSessionPrompt.value != null)

            val routed = mutableListOf<String>()
            vm.confirmRecreateStaleSession(onResolved = { routed.add(it) })
            runCurrent()

            assertEquals(
                "confirm must recreate the session in the SAME folder",
                listOf("git-pocketshell" to goneFolder),
                gateway.createdSessions,
            )
            assertEquals("confirm routes to the recreated session", listOf("git-pocketshell"), routed)
            assertNull("confirm clears the prompt", vm.staleSessionPrompt.value)
        } finally {
            vm.stopPolling()
        }
    }

    // ---- dismiss clears the prompt (no create) --------------------------------

    @Test
    fun dismissClearsPromptWithoutCreating() = runTest {
        val signals = SessionLifecycleSignals()
        val gateway = StubGateway(listOf(sessionRow("beta")))
        val vm = newViewModel(gateway = gateway, signals = signals)
        try {
            bind(vm)
            runCurrent()
            signals.emitStaleSession(HOST.id, "git-pocketshell", goneFolder)
            runCurrent()
            assertTrue(vm.staleSessionPrompt.value != null)

            vm.dismissStaleSessionPrompt()
            runCurrent()

            assertNull("dismiss clears the prompt", vm.staleSessionPrompt.value)
            assertTrue("dismiss must NOT create a session", gateway.createdSessions.isEmpty())
        } finally {
            vm.stopPolling()
        }
    }

    // ---- missing-data: a gone session with no known folder falls back to ~ -----

    @Test
    fun staleSessionWithNullFolderFallsBackToHomeOnRecreate() = runTest {
        val signals = SessionLifecycleSignals()
        val gateway = StubGateway(listOf(sessionRow("beta")))
        val vm = newViewModel(gateway = gateway, signals = signals)
        try {
            bind(vm)
            runCurrent()
            // The gone session carried no start directory (missing-data case).
            signals.emitStaleSession(HOST.id, "orphan", folderPath = null)
            runCurrent()

            val prompt = vm.staleSessionPrompt.value
            assertTrue("a null-folder gone session must STILL prompt (never blank/error)", prompt != null)
            assertNull(prompt!!.folderPath)

            vm.confirmRecreateStaleSession(onResolved = {})
            runCurrent()
            assertEquals(
                "a null-folder recreate falls back to the host home directory",
                listOf("orphan" to "~"),
                gateway.createdSessions,
            )
        } finally {
            vm.stopPolling()
        }
    }

    // ---- class coverage: app-created removal (via the app) drops the row and --
    // ---- does NOT prompt (the tree stays accurate) — only a gone-on-open does -

    @Test
    fun appCreatedRemovalDropsRowWithoutRecreatePrompt() = runTest {
        val signals = SessionLifecycleSignals()
        val gateway = StubGateway(listOf(sessionRow("git-pocketshell"), sessionRow("beta")))
        val vm = newViewModel(gateway = gateway, signals = signals)
        try {
            bind(vm)
            runCurrent()
            vm.stopPolling()
            runCurrent()

            // The COMMON case: the user removed the session THROUGH the app — the
            // tree drops the row immediately and there is nothing to recover, so
            // NO recreate prompt (that is only for opening a persisted-but-gone
            // session — the external-removal / missed-sync case).
            signals.emitKilled(HOST.id, "git-pocketshell")
            runCurrent()

            assertTrue("app-created removal drops the row", "git-pocketshell" !in readySessionNames(vm))
            assertNull("app-created removal must NOT raise the recreate prompt", vm.staleSessionPrompt.value)
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
