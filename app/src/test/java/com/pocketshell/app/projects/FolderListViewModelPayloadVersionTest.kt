package com.pocketshell.app.projects

import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.app.portfwd.ForwardingController
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
 * Issue #885 — RED→GREEN at the ViewModel layer for the PASSIVE host-CLI-version
 * mismatch check.
 *
 * The maintainer's dogfood: the old on-open version check was slow + only fired
 * via the home-list→open path. The fix makes it PASSIVE — the host CLI version
 * arrives in the `pocketshell tree` payload (`cli_version`) the client already
 * round-trips on EVERY host open (the FolderList cold-start hydrate runs
 * regardless of how the host was opened), and the client compares it against
 * the version it expects.
 *
 * These tests drive the real cold-start hydrate over a fake warm session whose
 * `tree get` / `tree reconcile` envelopes carry a `cli_version`, and assert the
 * VM raises (or does not raise) the passive `cliVersionMismatch` prompt — with
 * the expected version injected deterministically.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class FolderListViewModelPayloadVersionTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val viewModelStore = ViewModelStore()
    private var nextViewModelKey: Int = 0

    @After
    fun tearDown() {
        viewModelStore.clear()
    }

    @Test
    fun raisesPrompt_whenPayloadCliVersionOlderThanExpected() = runTest {
        // Host CLI is 0.4.9, the app expects 0.4.12 → passive mismatch on open.
        val session = TreePayloadSession(cliVersion = "0.4.9")
        val vm = newViewModel(session, expectedVersion = "0.4.12")
        bind(vm)
        advanceUntilIdle()

        val mismatch = vm.cliVersionMismatch.value
        assertTrue(
            "the passive payload-version check must raise the host-outdated prompt — was $mismatch",
            mismatch != null,
        )
        assertEquals("0.4.9", mismatch!!.hostVersion)
        assertEquals("0.4.12", mismatch.expectedVersion)
    }

    @Test
    fun noPrompt_whenPayloadCliVersionMatches() = runTest {
        val session = TreePayloadSession(cliVersion = "0.4.12")
        val vm = newViewModel(session, expectedVersion = "0.4.12")
        bind(vm)
        advanceUntilIdle()
        assertNull(vm.cliVersionMismatch.value)
    }

    @Test
    fun noPrompt_whenHostCliNewerThanApp() = runTest {
        // Host ahead of the app build: that is the app-behind case, NOT a
        // host-update prompt (mirrors #514 AppUpdateRequired policy).
        val session = TreePayloadSession(cliVersion = "0.5.0")
        val vm = newViewModel(session, expectedVersion = "0.4.12")
        bind(vm)
        advanceUntilIdle()
        assertNull(vm.cliVersionMismatch.value)
    }

    @Test
    fun noPrompt_whenPayloadOmitsCliVersion() = runTest {
        // An OLD CLI that predates the field omits cli_version → no signal,
        // never a false prompt.
        val session = TreePayloadSession(cliVersion = null)
        val vm = newViewModel(session, expectedVersion = "0.4.12")
        bind(vm)
        advanceUntilIdle()
        assertNull(vm.cliVersionMismatch.value)
    }

    @Test
    fun noPromptAndNoLoadingHang_whenTreeGetReturnsGarbageAndOmitsCliVersion() = runTest {
        val session = TreePayloadSession(
            cliVersion = null,
            treeGetResult = ExecResult("not json at all", "", 0),
        )
        val vm = newViewModel(session, expectedVersion = "0.4.12")
        bind(vm)
        advanceUntilIdle()

        assertNull(vm.cliVersionMismatch.value)
        val state = vm.state.value
        assertTrue(
            "garbage tree get with omitted cli_version must fall through to the live reconcile, not hang in Loading; was $state",
            state is FolderListUiState.Ready,
        )
        assertEquals(listOf("alpha"), (state as FolderListUiState.Ready).flatSessions.map { it.sessionName })
    }

    @Test
    fun dismiss_clearsPrompt() = runTest {
        val session = TreePayloadSession(cliVersion = "0.4.9")
        val vm = newViewModel(session, expectedVersion = "0.4.12")
        bind(vm)
        advanceUntilIdle()
        assertTrue(vm.cliVersionMismatch.value != null)
        vm.dismissCliVersionMismatch()
        assertNull(vm.cliVersionMismatch.value)
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

    private fun TestScope.newViewModel(
        session: SshSession,
        expectedVersion: String,
    ): FolderListViewModel {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val manager = SshLeaseManager(
            connector = SshLeaseConnector { Result.success(session) },
            scope = this,
            idleTtlMillis = 0L,
            connectTimeoutContext = dispatcher,
            nowMillis = { testScheduler.currentTime },
        )
        return FolderListViewModel(
            gateway = StubGateway(listOf(sessionRow("alpha"))),
            hostDao = FakeHostDao(HOST),
            projectRootDao = FakeProjectRootDao(),
            sshLeaseManager = manager,
            forwardingController = ForwardingController(ApplicationProvider.getApplicationContext()),
            treeRemoteSource = TreeRemoteSource().apply {
                remoteExecDispatcher = dispatcher
            },
            expectedPocketshellVersionProvider = { expectedVersion },
            attachLifecycle = false,
        ).also {
            it.ioDispatcher = dispatcher
            it.setProcessStartedForTest(true)
            viewModelStore.put("FolderListViewModel-${nextViewModelKey++}", it)
        }
    }

    private fun sessionRow(name: String): FolderSessionRow =
        FolderSessionRow(
            sessionName = name,
            lastActivity = 1_000L,
            attached = true,
            cwd = "/home/alexey/git/$name",
        )

    /**
     * A warm session whose `tree get` / `tree reconcile` envelopes carry a
     * [cliVersion] (or omit it when `null`, modelling an old CLI). Everything
     * else is benign — the gateway probe runs through the StubGateway.
     */
    private class TreePayloadSession(
        private val cliVersion: String?,
        private val treeGetResult: ExecResult? = null,
    ) : SshSession {
        override val isConnected: Boolean = true

        override suspend fun exec(command: String): ExecResult {
            val versionField = cliVersion?.let { ",\"cli_version\":\"$it\"" } ?: ""
            return when {
                command.contains("tree get") ->
                    treeGetResult ?: ExecResult("{\"nodes\":[],\"version\":0$versionField}", "", 0)
                command.contains("tree reconcile") ->
                    ExecResult("{\"alive\":[],\"gone\":[],\"added\":[]$versionField}", "", 0)
                else -> ExecResult("", "", 0)
            }
        }

        override fun tail(path: String, onLine: (String) -> Unit): Job = error("unused")
        override fun openLocalPortForward(remoteHost: String, remotePort: Int, localPort: Int): SshPortForward =
            error("unused")
        override fun startShell(): SshShell = error("unused")
        override suspend fun uploadFile(file: java.io.File, remotePath: String): String = error("unused")
        override suspend fun uploadStream(
            input: java.io.InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("unused")
        override fun close() = Unit
    }

    private class StubGateway(
        @Volatile var rows: List<FolderSessionRow>?,
    ) : FolderListGateway {
        override suspend fun listSessionsWithFolder(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            watchedRoots: List<ProjectRootEntity>,
        ): FolderListResult = FolderListResult.Sessions(rows = rows ?: error("gateway probe must not be called"))

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
            name = "hetzner",
            hostname = "10.0.2.2",
            port = 2222,
            username = "testuser",
            keyId = 7L,
        )
    }
}
