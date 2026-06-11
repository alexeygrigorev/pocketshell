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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #465: after the user reopens a no-longer-existing (stale) session and
 * goes back, the host-detail folder screen lands on an `open failed`
 * connect/probe error. That error state must be RECOVERABLE — tapping Retry
 * (which calls [FolderListViewModel.refresh]) must re-run the probe, and once
 * the host is reachable again the folder tree must reload to [Ready]. Before
 * the fix the screen stranded the user on the error panel and Retry never
 * re-probed, forcing a force-close.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class FolderListViewModelOpenFailedRecoveryTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val viewModelStore = ViewModelStore()
    private var nextViewModelKey: Int = 0

    @After
    fun tearDown() {
        viewModelStore.clear()
    }

    @Test
    fun retryRecoversFromOpenFailedConnectError() = runTest {
        // Models the maintainer's journey: the first probe after returning
        // from a dead session hits an sshj channel "open failed" -> the screen
        // shows a ConnectError. The host is reachable on the next attempt, so
        // tapping Retry must reload the folder tree instead of dead-ending.
        val gateway = ScriptedGateway(
            results = listOf(
                FolderListResult.ConnectFailed(RuntimeException("open failed")),
                FolderListResult.Sessions(rows = listOf(sessionRow("alpha"))),
            ),
        )
        val vm = newViewModel(gateway)
        try {
            bind(vm)
            runCurrent()

            assertTrue(
                "first probe should surface the open-failed connect error",
                vm.state.value is FolderListUiState.ConnectError,
            )

            // User taps Retry on the error panel.
            vm.refresh()
            runCurrent()

            val state = vm.state.value
            assertTrue(
                "Retry must recover the folder tree, not strand the user on the error panel; was $state",
                state is FolderListUiState.Ready,
            )
            assertEquals(
                setOf("alpha"),
                (state as FolderListUiState.Ready).flatSessions.map { it.sessionName }.toSet(),
            )
        } finally {
            vm.stopPolling()
        }
    }

    @Test
    fun retryRecoversFromOpenFailedAfterReturningFromDeadSession() = runTest {
        // EPIC #679: the tree was Ready, the user drilled into a session
        // (stopPolling on nav-away), the session was dead, they came back.
        // Returning now renders the HELD maintained tree INSTANTLY with NO
        // probe (requirement #1 — no refresh on every open). The user then
        // pull-to-refreshes; that explicit reconcile fails with "open failed"
        // and surfaces the non-displacing banner while keeping the stale tree
        // usable (#620); a second refresh (Retry) recovers and drops the stale
        // ghost row by the authoritative reconcile.
        val gateway = ScriptedGateway(
            results = listOf(
                // initial healthy reconcile
                FolderListResult.Sessions(rows = listOf(sessionRow("alpha"), sessionRow("ghost"))),
                // the user's pull-to-refresh fails
                FolderListResult.ConnectFailed(RuntimeException("open failed")),
                // Retry succeeds; the ghost session is gone on the remote
                FolderListResult.Sessions(rows = listOf(sessionRow("alpha"))),
            ),
        )
        val vm = newViewModel(gateway)
        try {
            bind(vm)
            runCurrent()
            assertEquals(setOf("alpha", "ghost"), readySessionNames(vm))

            // Drill into the (dead) session: the screen stops the reconcile.
            vm.stopPolling()
            runCurrent()

            // Return to the tree -> re-bind same host renders the HELD tree
            // instantly, NO probe consumed (requirement #1). The fresh held tree
            // is not stale, so no auto-reconcile fires.
            bind(vm)
            runCurrent()
            assertEquals(
                "re-opening the same host shows the held tree instantly, no probe",
                setOf("alpha", "ghost"),
                readySessionNames(vm),
            )
            assertEquals(
                "no auto-reconcile on open means no failure banner yet",
                FolderActionStatus.Idle,
                vm.actionStatus.value,
            )

            // The user pulls-to-refresh; the explicit reconcile fails but keeps
            // the stale-but-usable tree visible (#620) and surfaces the banner.
            vm.refreshSessions()
            runCurrent()
            assertEquals(
                "failed pull-to-refresh keeps the stale-but-usable tree visible",
                setOf("alpha", "ghost"),
                readySessionNames(vm),
            )
            val failedStatus = vm.actionStatus.value
            when (failedStatus) {
                is FolderActionStatus.Failed -> assertTrue(failedStatus.message.contains("open failed"))
                else -> fail("expected a pull-to-refresh failure banner, got $failedStatus")
            }

            // Retry recovers; stale ghost row is dropped by the authoritative probe.
            vm.refresh()
            runCurrent()
            assertEquals(
                "Retry must reload the tree and drop the stale row",
                setOf("alpha"),
                readySessionNames(vm),
            )
        } finally {
            vm.stopPolling()
        }
    }

    @Test
    fun refreshSessionsFailurePreservesReadySnapshot() = runTest {
        val gateway = ScriptedGateway(
            results = listOf(
                FolderListResult.Sessions(rows = listOf(sessionRow("alpha"), sessionRow("beta"))),
                FolderListResult.ConnectFailed(RuntimeException("open failed")),
            ),
        )
        val vm = newViewModel(gateway)
        try {
            bind(vm)
            runCurrent()
            assertEquals(setOf("alpha", "beta"), readySessionNames(vm))

            vm.refreshSessions()
            runCurrent()

            assertEquals(
                "manual Refresh sessions must keep the last visible sessions when discovery fails",
                setOf("alpha", "beta"),
                readySessionNames(vm),
            )
            val actionStatus = vm.actionStatus.value
            when (actionStatus) {
                is FolderActionStatus.Failed -> assertTrue(actionStatus.message.contains("open failed"))
                else -> fail("expected a lightweight refresh failure banner, got $actionStatus")
            }
        } finally {
            vm.stopPolling()
        }
    }

    @Test
    fun refreshSessionsCommandFailurePreservesReadySnapshot() = runTest {
        val gateway = ScriptedGateway(
            results = listOf(
                FolderListResult.Sessions(rows = listOf(sessionRow("alpha"), sessionRow("beta"))),
                FolderListResult.Failed("tmux list-sessions failed"),
            ),
        )
        val vm = newViewModel(gateway)
        try {
            bind(vm)
            runCurrent()
            assertEquals(setOf("alpha", "beta"), readySessionNames(vm))

            vm.refreshSessions()
            runCurrent()

            assertEquals(
                "manual Refresh sessions must not discard visible sessions on a command failure",
                setOf("alpha", "beta"),
                readySessionNames(vm),
            )
            val actionStatus = vm.actionStatus.value
            when (actionStatus) {
                is FolderActionStatus.Failed -> {
                    assertTrue(actionStatus.message.contains("tmux list-sessions failed"))
                }
                else -> fail("expected a lightweight refresh failure banner, got $actionStatus")
            }
        } finally {
            vm.stopPolling()
        }
    }

    @Test
    fun refreshSessionsReloadsVisibleSessionSnapshot() = runTest {
        val gateway = ScriptedGateway(
            results = listOf(
                FolderListResult.Sessions(rows = listOf(sessionRow("alpha"))),
                FolderListResult.Sessions(rows = listOf(sessionRow("alpha"), sessionRow("beta"))),
            ),
        )
        val vm = newViewModel(gateway)
        try {
            bind(vm)
            runCurrent()
            assertEquals(setOf("alpha"), readySessionNames(vm))

            vm.refreshSessions()
            runCurrent()

            assertEquals(
                "manual Refresh sessions must reload sessions created outside PocketShell",
                setOf("alpha", "beta"),
                readySessionNames(vm),
            )
            assertEquals(
                "Issue #656: a successful manual refresh emits NO displacing banner — " +
                    "the reloaded list (and the cleared progress bar) is the feedback",
                FolderActionStatus.Idle,
                vm.actionStatus.value,
            )
        } finally {
            vm.stopPolling()
        }
    }

    @Test
    fun refreshSessionsShowsLoadingFeedbackWhileReloadIsInFlight() = runTest {
        val refreshResult = CompletableDeferred<FolderListResult>()
        val gateway = SuspendedRefreshGateway(
            refreshResult = refreshResult,
        )
        val vm = newViewModel(gateway)
        try {
            bind(vm)
            runCurrent()
            assertEquals(setOf("alpha"), readySessionNames(vm))
            assertFalse((vm.state.value as FolderListUiState.Ready).isRefreshing)

            vm.refreshSessions()
            runCurrent()

            val refreshingState = vm.state.value as FolderListUiState.Ready
            assertEquals(
                "manual Refresh sessions should keep the existing rows visible while reloading",
                setOf("alpha"),
                refreshingState.flatSessions.map { it.sessionName }.toSet(),
            )
            assertTrue("manual refresh should mark the ready snapshot as refreshing", refreshingState.isRefreshing)
            assertEquals(
                "Issue #656: the in-flight refresh feedback is the non-displacing " +
                    "progress bar (isRefreshing), NOT a displacing status banner",
                FolderActionStatus.Idle,
                vm.actionStatus.value,
            )

            refreshResult.complete(
                FolderListResult.Sessions(rows = listOf(sessionRow("alpha"), sessionRow("beta"))),
            )
            runCurrent()

            val refreshedState = vm.state.value as FolderListUiState.Ready
            assertFalse(refreshedState.isRefreshing)
            assertEquals(setOf("alpha", "beta"), refreshedState.flatSessions.map { it.sessionName }.toSet())
            assertEquals(
                "Issue #656: a successful manual refresh emits NO displacing banner",
                FolderActionStatus.Idle,
                vm.actionStatus.value,
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
                    Result.failure(IllegalStateException("prewarm disabled for open-failed recovery test"))
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

    /**
     * Returns each scripted [FolderListResult] in order on successive probes,
     * repeating the last one once the script is exhausted (so a still-live poll
     * loop converges on the recovered result rather than throwing).
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

    private inner class SuspendedRefreshGateway(
        private val refreshResult: CompletableDeferred<FolderListResult>,
    ) : FolderListGateway {
        private var callCount = 0

        override suspend fun listSessionsWithFolder(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            watchedRoots: List<ProjectRootEntity>,
        ): FolderListResult {
            callCount += 1
            return if (callCount == 1) {
                FolderListResult.Sessions(rows = listOf(sessionRow("alpha")))
            } else {
                refreshResult.await()
            }
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
