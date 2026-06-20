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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #711: a transient folder-tree refresh transport drop (EOF / broken
 * transport) must NOT flash a scary band carrying the raw enumeration command —
 * it heals QUIETLY (a bounded re-reconcile) and only a genuinely unrecoverable
 * error surfaces a COMPACT calm message. No raw shell command or raw transport
 * exception text is ever shown to the user.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class FolderListViewModelTransientEofHealTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val viewModelStore = ViewModelStore()
    private var nextViewModelKey: Int = 0

    /**
     * The exact dogfood message: a `Failed to open exec channel for <whole
     * enumeration command>` wrapping `Broken transport; encountered EOF`. The
     * raw command must never reach the user-facing surface.
     */
    private val rawEofMessage: String =
        "Failed to open exec channel for `PATH=\"\$HOME/.local/bin:\$PATH\"; " +
            "tmux list-sessions -F '#{session_name}::' ; printf '%s\\n' __pocketshell_enum__@@ ; " +
            "tmux list-panes -a -F '...'`: Broken transport; encountered EOF"

    @After
    fun tearDown() {
        viewModelStore.clear()
    }

    @Test
    fun transientEofDuringRefreshHealsQuietlyWithNoScaryBand() = runTest {
        // The tree is Ready; a refresh hits a transient EOF drop; the very next
        // reconcile recovers. The user must NEVER see the raw-command band — the
        // refresh quietly retries and lands back on a healthy tree.
        val gateway = ScriptedGateway(
            results = listOf(
                FolderListResult.Sessions(rows = listOf(sessionRow("alpha"))),
                FolderListResult.ConnectFailed(RuntimeException(rawEofMessage)),
                FolderListResult.Sessions(rows = listOf(sessionRow("alpha"), sessionRow("beta"))),
            ),
        )
        val vm = newViewModel(gateway)
        try {
            bind(vm)
            runCurrent()
            assertEquals(setOf("alpha"), readySessionNames(vm))

            // Pull-to-refresh: first hits the EOF drop, quietly retries, recovers.
            vm.refreshSessions()
            runCurrent()

            val state = vm.state.value
            assertTrue(
                "a transient EOF refresh must quietly heal to Ready, not strand on an error; was $state",
                state is FolderListUiState.Ready,
            )
            assertEquals(
                "the quiet retry reloaded the recovered tree",
                setOf("alpha", "beta"),
                readySessionNames(vm),
            )
            // No scary band at all — the transient drop never surfaced a failure.
            assertEquals(
                "a transient EOF that self-healed must NOT leave a failure banner",
                FolderActionStatus.Idle,
                vm.actionStatus.value,
            )
        } finally {
            vm.stopPolling()
        }
    }

    @Test
    fun unrecoverableEofShowsCalmCompactMessageNeverRawCommand() = runTest {
        // The EOF drop never recovers (every reconcile fails). After the bounded
        // quiet retries are exhausted, the user gets a COMPACT calm message —
        // and it contains NEITHER the raw shell command NOR the raw exception.
        val gateway = ScriptedGateway(
            results = listOf(
                FolderListResult.Sessions(rows = listOf(sessionRow("alpha"))),
                // every subsequent reconcile keeps failing with the raw EOF.
                FolderListResult.ConnectFailed(RuntimeException(rawEofMessage)),
            ),
        )
        val vm = newViewModel(gateway)
        try {
            bind(vm)
            runCurrent()
            assertEquals(setOf("alpha"), readySessionNames(vm))

            vm.refreshSessions()
            runCurrent()

            // The Ready tree is preserved (the non-displacing banner, #620), and
            // the banner is the calm copy — no raw command, no raw exception.
            assertEquals(
                "the stale-but-usable tree stays visible",
                setOf("alpha"),
                readySessionNames(vm),
            )
            val status = vm.actionStatus.value as FolderActionStatus.Failed
            assertEquals(FolderListViewModel.REFRESH_FAILED_MESSAGE, status.message)
            assertNoRawDeveloperText(status.message)
        } finally {
            vm.stopPolling()
        }
    }

    @Test
    fun unrecoverableEofOnColdLoadShowsCalmConnectErrorNeverRawCommand() = runTest {
        // No prior Ready snapshot: the first-ever reconcile keeps hitting the EOF
        // drop. After the bounded quiet retries the full-screen ConnectError must
        // carry the calm copy, never the raw command/exception.
        val gateway = ScriptedGateway(
            results = listOf(
                FolderListResult.ConnectFailed(RuntimeException(rawEofMessage)),
            ),
        )
        val vm = newViewModel(gateway)
        try {
            bind(vm)
            runCurrent()

            val state = vm.state.value
            assertTrue(
                "an unrecoverable EOF cold load surfaces a ConnectError; was $state",
                state is FolderListUiState.ConnectError,
            )
            val connectError = state as FolderListUiState.ConnectError
            assertEquals(FolderListViewModel.REFRESH_FAILED_MESSAGE, connectError.message)
            assertNoRawDeveloperText(connectError.message)
        } finally {
            vm.stopPolling()
        }
    }

    @Test
    fun genuineCommandFailureShowsCalmMessageNeverRawStderr() = runTest {
        // A non-transient command failure (NOT the EOF family) must also be calm:
        // the user-facing copy never carries the raw command/stderr text.
        val gateway = ScriptedGateway(
            results = listOf(
                FolderListResult.Sessions(rows = listOf(sessionRow("alpha"))),
                FolderListResult.Failed("tmux list-sessions -F '#{session_name}' failed: permission denied"),
            ),
        )
        val vm = newViewModel(gateway)
        try {
            bind(vm)
            runCurrent()
            assertEquals(setOf("alpha"), readySessionNames(vm))

            vm.refreshSessions()
            runCurrent()

            val status = vm.actionStatus.value as FolderActionStatus.Failed
            assertEquals(FolderListViewModel.REFRESH_FAILED_MESSAGE, status.message)
            assertFalse(
                "calm copy must not leak the raw tmux command",
                status.message.contains("tmux list-sessions"),
            )
        } finally {
            vm.stopPolling()
        }
    }

    @Test
    fun refreshFailureBannerAutoClearsOnLaterSuccessfulReconcile() = runTest {
        // Issue #711 / #656 regression: a refresh FAILS (calm banner shown), then
        // a LATER reconcile SUCCEEDS — the calm banner must auto-clear back to
        // Idle, NOT linger on the now-healthy tree. The old prefix-match auto-clear
        // gated on `startsWith("Couldn't refresh sessions:")`, which no longer
        // matches REFRESH_FAILED_MESSAGE, so the stale band lingered. The TYPE-flag
        // gate (FolderActionStatus.Failed.isRefreshFailure) recognises the band by
        // type, independent of the user-facing copy.
        //
        // NOTE: a non-transient command Failed (NOT the EOF family) is used so the
        // first failing refresh surfaces the calm banner immediately, without the
        // quiet transient-retry loop swallowing it.
        val gateway = ScriptedGateway(
            results = listOf(
                FolderListResult.Sessions(rows = listOf(sessionRow("alpha"))),
                FolderListResult.Failed("tmux list-sessions failed: permission denied"),
                FolderListResult.Sessions(rows = listOf(sessionRow("alpha"), sessionRow("beta"))),
            ),
        )
        val vm = newViewModel(gateway)
        try {
            bind(vm)
            runCurrent()
            assertEquals(setOf("alpha"), readySessionNames(vm))

            // First refresh fails -> the calm refresh-failure banner appears.
            vm.refreshSessions()
            runCurrent()
            val failed = vm.actionStatus.value as FolderActionStatus.Failed
            assertEquals(FolderListViewModel.REFRESH_FAILED_MESSAGE, failed.message)
            assertTrue(
                "the refresh-failure banner must carry the isRefreshFailure type flag",
                failed.isRefreshFailure,
            )

            // Second refresh succeeds -> the stale banner must auto-clear.
            vm.refreshSessions()
            runCurrent()

            assertEquals(
                "the recovered tree is shown after the successful reconcile",
                setOf("alpha", "beta"),
                readySessionNames(vm),
            )
            assertEquals(
                "a successful reconcile must auto-clear the stale refresh-failure banner",
                FolderActionStatus.Idle,
                vm.actionStatus.value,
            )
        } finally {
            vm.stopPolling()
        }
    }

    private fun assertNoRawDeveloperText(message: String) {
        assertFalse("must not leak the raw enumeration command", message.contains("tmux list-sessions"))
        assertFalse("must not leak the raw PATH= prefix", message.contains("PATH="))
        assertFalse("must not leak printf internals", message.contains("printf"))
        assertFalse("must not leak the raw transport exception", message.contains("encountered EOF"))
        assertFalse("must not leak 'Broken transport'", message.contains("Broken transport"))
        assertFalse("must not leak 'open exec channel'", message.contains("open exec channel"))
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
                    Result.failure(IllegalStateException("prewarm disabled for transient-eof heal test"))
                },
                scope = this,
                idleTtlMillis = 0L,
                // Issue #708/#847: run the warm connect on the SAME virtual clock
                // as `runTest` so the cold-start reconcile's #847 connect-await
                // settles deterministically under `runCurrent`.
                connectTimeoutContext = dispatcher,
                nowMillis = { testScheduler.currentTime },
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

    /**
     * Returns each scripted [FolderListResult] in order on successive probes,
     * repeating the last one once exhausted so a still-live poll loop / quiet
     * retry converges on the scripted terminal result.
     */
    private class ScriptedGateway(
        private val results: List<FolderListResult>,
    ) : FolderListGateway {
        private var index = 0

        override suspend fun listSessionsWithFolder(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            watchedRoots: List<ProjectRootEntity>,
        ): FolderListResult {
            val result = results[index.coerceAtMost(results.lastIndex)]
            if (index < results.lastIndex) index++
            return result
        }

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
