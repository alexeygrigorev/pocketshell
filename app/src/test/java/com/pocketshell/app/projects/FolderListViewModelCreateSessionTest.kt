package com.pocketshell.app.projects

import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.app.portfwd.ForwardingController
import com.pocketshell.app.tmux.SessionLifecycleSignals
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.dao.ProjectRootDao
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.ProjectRootEntity
import com.pocketshell.uikit.model.SessionAgentKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream

/**
 * EPIC #679 (#678 create side): wiring [HostTreeModel.insertSession] at the
 * create-session call site.
 *
 * A session the app just created must appear in the maintained tree
 * IMMEDIATELY by id (optimistically) — not after waiting for the next probe to
 * discover it ("created session/window slow-to-appear"). The create must mutate
 * the held tree incrementally: the existing rows keep their slots (no
 * blank-and-rebuild), the new row is appended, and the optimistic node survives
 * the reconcile that immediately follows the create even when that probe has
 * not yet observed the new session.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class FolderListViewModelCreateSessionTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val viewModelStore = ViewModelStore()
    private var nextViewModelKey: Int = 0

    @After
    fun tearDown() {
        viewModelStore.clear()
    }

    @Test
    fun createSessionAppearsOptimisticallyByIdWithoutBlankRebuild() = runTest {
        // The gateway accepts the create (returns the resolved name) but its
        // session probe still reports ONLY the pre-existing session — i.e. the
        // probe has not observed the newly-created session yet (the real race
        // a fresh tmux session loses against the immediately-following probe).
        val gateway = StubGateway(rows = listOf(sessionRow("alpha")))
        val vm = newViewModel(gateway)
        // Track every Ready emission so we can prove the existing row is NEVER
        // dropped across the create — there is no visible blank-and-rebuild.
        val alphaEverMissing = mutableListOf<Boolean>()
        val collector = backgroundScope.launch {
            vm.state.collect { state ->
                if (state is FolderListUiState.Ready) {
                    alphaEverMissing.add(state.flatSessions.none { it.sessionName == "alpha" })
                }
            }
        }
        try {
            bind(vm)
            runCurrent()
            assertEquals(setOf("alpha"), readySessionNames(vm))

            var resolved: String? = null
            vm.createSession(
                sessionName = "beta",
                cwd = "/home/alexey/git/beta",
                startCommand = null,
                onResolved = { resolved = it },
            )
            runCurrent()

            assertEquals("the create callback fires with the resolved name", "beta", resolved)
            // #678: beta appears IMMEDIATELY by id even though the gateway probe
            // (which the create's follow-up refresh just ran) still reports only
            // alpha — the optimistic node survives via the optimistic grace.
            assertEquals(
                "the just-created session appears optimistically by id",
                setOf("alpha", "beta"),
                readySessionNames(vm),
            )
            // No visible full refresh / blank-rebuild: the existing alpha row was
            // present in EVERY Ready emission across the create.
            assertTrue(
                "alpha must never disappear across the create (no blank-and-rebuild)",
                alphaEverMissing.none { it },
            )
        } finally {
            collector.cancel()
            vm.stopPolling()
        }
    }

    @Test
    fun createdSessionStaysWhenProbeConfirmsIt() = runTest {
        // The gateway accepts the create AND its probe now reports the new
        // session too (the probe observed it). The confirmed node stays put.
        val gateway = StubGateway(
            rows = listOf(sessionRow("alpha")),
            // On create success the gateway begins reporting the new session.
            reportCreatedSession = true,
        )
        val vm = newViewModel(gateway)
        try {
            bind(vm)
            runCurrent()
            assertEquals(setOf("alpha"), readySessionNames(vm))

            vm.createSession(
                sessionName = "beta",
                cwd = "/home/alexey/git/beta",
                startCommand = null,
                onResolved = {},
            )
            runCurrent()

            assertEquals(
                "the created session is present (optimistic + then confirmed by the probe)",
                setOf("alpha", "beta"),
                readySessionNames(vm),
            )
        } finally {
            vm.stopPolling()
        }
    }

    @Test
    fun createSessionStampsChosenAgentKindNotProbing() = runTest {
        // Epic #821 Workstream A: the picker knows the kind it just launched,
        // so the optimistically-inserted node must carry THAT kind — not the
        // old `Probing` placeholder that waited for detection. The gateway
        // probe here still reports only the pre-existing session (it has not
        // observed the new one yet), proving the kind comes from the chosen
        // value, not a probe round-trip.
        val gateway = StubGateway(rows = listOf(sessionRow("alpha")))
        val vm = newViewModel(gateway)
        try {
            bind(vm)
            runCurrent()

            vm.createSession(
                sessionName = "git-beta",
                cwd = "/home/alexey/git/beta",
                startCommand = "pocketshell agent codex --dir '/home/alexey/git/beta'",
                chosenKind = SessionAgentKind.Codex,
                onResolved = {},
            )
            runCurrent()

            val state = vm.state.value as FolderListUiState.Ready
            val beta = state.flatSessions.first { it.sessionName == "git-beta" }
            assertEquals(
                "the just-created session shows the chosen kind, not Probing",
                SessionAgentKind.Codex,
                beta.agentKind,
            )
        } finally {
            vm.stopPolling()
        }
    }

    @Test
    fun createShellSessionWithoutChosenKindStaysProbingUntilProbe() = runTest {
        // A shell session (no agent kind) keeps the optimistic Probing
        // placeholder — there is no kind to record — until the reconcile
        // confirms it (Shell). This guards that A1 did not regress the
        // shell-session create path.
        val gateway = StubGateway(rows = listOf(sessionRow("alpha")))
        val vm = newViewModel(gateway)
        try {
            bind(vm)
            runCurrent()

            vm.createSession(
                sessionName = "build-shell",
                cwd = "/home/alexey/git/beta",
                startCommand = null,
                chosenKind = null,
                onResolved = {},
            )
            runCurrent()

            val state = vm.state.value as FolderListUiState.Ready
            val shell = state.flatSessions.first { it.sessionName == "build-shell" }
            assertEquals(
                "a shell create stays Probing optimistically (no kind to record)",
                SessionAgentKind.Probing,
                shell.agentKind,
            )
        } finally {
            vm.stopPolling()
        }
    }

    @Test
    fun failedCreateDoesNotInsertARow() = runTest {
        val gateway = StubGateway(rows = listOf(sessionRow("alpha")), createSucceeds = false)
        val vm = newViewModel(gateway)
        try {
            bind(vm)
            runCurrent()
            assertEquals(setOf("alpha"), readySessionNames(vm))

            var resolved: String? = null
            vm.createSession(
                sessionName = "beta",
                cwd = "/home/alexey/git/beta",
                startCommand = null,
                onResolved = { resolved = it },
            )
            runCurrent()

            assertEquals("a failed create must not fire onResolved", null, resolved)
            assertTrue(
                "a failed create surfaces the Failed state",
                vm.state.value is FolderListUiState.Failed,
            )
        } finally {
            vm.stopPolling()
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
            agentKind = SessionAgentKind.Shell,
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
                    Result.failure(IllegalStateException("prewarm disabled for create-session test"))
                },
                scope = this,
                idleTtlMillis = 0L,
                // Issue #708/#847: warm connect on the virtual clock so the #847
                // connect-await settles deterministically under runCurrent.
                connectTimeoutContext = dispatcher,
                nowMillis = { testScheduler.currentTime },
            ),
            forwardingController = ForwardingController(ApplicationProvider.getApplicationContext()),
            sessionLifecycleSignals = SessionLifecycleSignals(),
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
        @Volatile var createSucceeds: Boolean = true,
        // When true, a successful create makes the gateway's probe start
        // reporting the created session (i.e. the probe observed it).
        @Volatile var reportCreatedSession: Boolean = false,
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
        ): Result<String> {
            if (!createSucceeds) {
                return Result.failure(RuntimeException("tmux refused to create '$sessionName'"))
            }
            if (reportCreatedSession) {
                rows = rows + FolderSessionRow(
                    sessionName = sessionName,
                    lastActivity = 2_000L,
                    attached = false,
                    cwd = cwd,
                    agentKind = SessionAgentKind.Shell,
                )
            }
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

        override suspend fun renameSession(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            oldName: String,
            newName: String,
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

    private class FakeSshSession : SshSession {
        var closed: Boolean = false
        override val isConnected: Boolean get() = !closed
        override suspend fun exec(command: String): ExecResult = error("not used")
        override fun tail(path: String, onLine: (String) -> Unit) = error("not used")
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
