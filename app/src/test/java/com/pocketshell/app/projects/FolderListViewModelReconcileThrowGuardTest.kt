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
 * Issue #939 / audit #935 S5-1 (reproduce-first, D33/G10).
 *
 * [FolderListViewModel.runReconcile] sets `sessionRefreshInFlight = true` then
 * runs an UNGUARDED suspend body. Before the fix, an UNEXPECTED throw inside the
 * body — e.g. an `SQLiteException` from the host read, a `persistTree` IO error,
 * or a `tree.reconcile` projection fault — escaped without ever clearing the
 * flag, so the session-picker refresh bar
 * ([FolderListUiState.Ready.isRefreshing], #639) spun FOREVER with no error band,
 * and the in-flight guard at `setSessionRefreshInFlight` made a re-tap a no-op:
 * the user could not retry without leaving + re-binding the host.
 *
 * RED on base: after a throwing refresh, `isRefreshing` stays `true` and no error
 * surfaces → permanent stuck spinner.
 * GREEN with the fix: `isRefreshing` clears, an escapable refresh-failure banner
 * surfaces, and a subsequent refresh recovers cleanly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class FolderListViewModelReconcileThrowGuardTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val viewModelStore = ViewModelStore()
    private var nextViewModelKey: Int = 0

    @After
    fun tearDown() {
        viewModelStore.clear()
    }

    @Test
    fun throwInReconcileBodyClearsInFlightFlagAndSurfacesEscapableError() = runTest {
        val gateway = ThrowOnDemandGateway()
        val vm = newViewModel(gateway)
        try {
            bind(vm)
            runCurrent()

            // First reconcile is healthy: the picker reaches Ready with a
            // snapshot, so the refresh bar (isRefreshing) is observable and false.
            val ready = vm.state.value
            assertTrue(
                "first reconcile must reach Ready; was $ready",
                ready is FolderListUiState.Ready,
            )
            assertFalse(
                "a settled Ready picker is not refreshing",
                (ready as FolderListUiState.Ready).isRefreshing,
            )

            // Now arm the gateway to THROW on the next reconcile (the unguarded
            // mid-body throw class — a raw exception, NOT a FolderListResult.Failed
            // arm) and trigger a manual refresh.
            gateway.throwNext = true
            vm.refreshSessions()
            runCurrent()

            // GREEN: the flag is released — the refresh bar is NOT stuck spinning.
            val after = vm.state.value
            assertTrue(
                "after a throwing reconcile the picker must remain Ready (held tree); was $after",
                after is FolderListUiState.Ready,
            )
            assertFalse(
                "the unguarded throw must clear sessionRefreshInFlight — before the fix " +
                    "the refresh bar spun forever; isRefreshing was true",
                (after as FolderListUiState.Ready).isRefreshing,
            )

            // GREEN: an ESCAPABLE error surfaced — the user sees it failed and is
            // not silently wedged. Before the fix there was no error band at all.
            val status = vm.actionStatus.value
            assertTrue(
                "a throwing reconcile must surface an escapable refresh-failure banner; was $status",
                status is FolderActionStatus.Failed && status.isRefreshFailure,
            )

            // The picker is usable again: a healthy retry recovers and clears the
            // banner (re-tap is NOT a no-op).
            gateway.throwNext = false
            vm.refreshSessions()
            runCurrent()

            assertTrue(
                "retry after the throw must recover; banner cleared and not refreshing",
                vm.state.value is FolderListUiState.Ready &&
                    !(vm.state.value as FolderListUiState.Ready).isRefreshing,
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
                    Result.failure(IllegalStateException("prewarm disabled for throw-guard test"))
                },
                scope = this,
                idleTtlMillis = 0L,
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
     * Returns a healthy single-session list, but THROWS a raw exception from
     * [listSessionsWithFolder] when [throwNext] is set — modelling the unguarded
     * mid-body throw class (e.g. an `SQLiteException`/IO error). A raw throw
     * deliberately bypasses every `FolderListResult.*` arm, so before the fix it
     * escaped `runReconcile` without clearing `sessionRefreshInFlight`.
     */
    private inner class ThrowOnDemandGateway : FolderListGateway {
        var throwNext: Boolean = false

        override suspend fun listSessionsWithFolder(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            watchedRoots: List<ProjectRootEntity>,
        ): FolderListResult {
            if (throwNext) {
                throw IllegalStateException("simulated SQLite/IO fault inside reconcile body")
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
