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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #702: a [FolderListViewModel] reconcile whose gateway call wedges (never
 * returns — the production wedge was the live `-CC` enumeration parking on the
 * shared single-flight mutex) must NOT pin the session picker in `Loading`
 * forever. The view model bounds the whole reconcile with `withTimeoutOrNull`
 * and surfaces a RETRYABLE [FolderListUiState.ConnectError] on expiry, and a
 * subsequent successful reconcile recovers to [Ready].
 *
 * Deterministic under the test scheduler: the wedged gateway parks on an
 * unresolved [CompletableDeferred], so virtual time advances past the bound and
 * the timeout fires exactly when [advanceTimeBy] crosses it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class FolderListViewModelReconcileTimeoutTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val viewModelStore = ViewModelStore()
    private var nextViewModelKey: Int = 0

    @After
    fun tearDown() {
        viewModelStore.clear()
    }

    @Test
    fun wedgedReconcileSurfacesRetryableConnectErrorInsteadOfHangingInLoading() = runTest {
        val gateway = WedgingThenHealthyGateway()
        val vm = newViewModel(gateway)
        try {
            bind(vm)
            runCurrent()

            // The first reconcile is wedged: the gateway never returns. Before
            // the bound fires the picker is in Loading (the bug: it would stay
            // there forever without the outer timeout).
            assertTrue(
                "while the reconcile is in-flight the picker is Loading",
                vm.state.value is FolderListUiState.Loading,
            )

            // Advance past the reconcile bound: the timeout fires and the picker
            // surfaces a RETRYABLE ConnectError instead of an endless spinner.
            advanceTimeBy(FolderListViewModel.RECONCILE_TIMEOUT_MS + 1_000L)
            runCurrent()

            val errorState = vm.state.value
            assertTrue(
                "a wedged reconcile must surface a retryable ConnectError, not stay in Loading; was $errorState",
                errorState is FolderListUiState.ConnectError,
            )
            assertTrue(
                "the ConnectError cause is the reconcile timeout",
                (errorState as FolderListUiState.ConnectError).cause is FolderReconcileTimeoutException,
            )

            // Retry: the next reconcile is healthy and recovers to Ready.
            gateway.unwedge()
            vm.refresh()
            runCurrent()

            val recovered = vm.state.value
            assertTrue(
                "Retry after the timeout must recover the picker to Ready; was $recovered",
                recovered is FolderListUiState.Ready,
            )
            assertEquals(
                setOf("alpha"),
                (recovered as FolderListUiState.Ready).flatSessions.map { it.sessionName }.toSet(),
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
                    Result.failure(IllegalStateException("prewarm disabled for reconcile-timeout test"))
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
            it.setProcessStartedForTest(true)
            viewModelStore.put("FolderListViewModel-${nextViewModelKey++}", it)
        }
    }

    /**
     * First reconcile wedges (parks on an unresolved deferred) until
     * [unwedge]; after that, returns a healthy single-session list.
     */
    private inner class WedgingThenHealthyGateway : FolderListGateway {
        private val wedge = CompletableDeferred<Unit>()

        fun unwedge() {
            wedge.complete(Unit)
        }

        override suspend fun listSessionsWithFolder(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            watchedRoots: List<ProjectRootEntity>,
        ): FolderListResult {
            if (!wedge.isCompleted) {
                wedge.await()
            }
            return FolderListResult.Sessions(rows = listOf(sessionRow("alpha")))
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
