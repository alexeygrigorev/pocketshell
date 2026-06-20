package com.pocketshell.app.projects

import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.app.portfwd.ForwardingController
import com.pocketshell.app.tmux.SessionLifecycleSignals
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshLease
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseKey
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.dao.ProjectRootDao
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.ProjectRootEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Issue #847 — the REAL root cause v0.4.11 did NOT fix: the **timeout inversion**
 * in [FolderListViewModel.runReconcile].
 *
 * The cold-start reconcile that renders the session list (and clears "Loading
 * workspace tree") used to wrap the gateway's WHOLE call — INCLUDING the SSH
 * lease acquire (a fresh cold sshj dial, ≤ ~35s) — in the 12s
 * [FolderListViewModel.RECONCILE_TIMEOUT_MS] enumeration bound. The 12s
 * reconcile budget was therefore SHORTER than the connect it absorbed:
 *
 *   reconcile-bound (12s)  <  cold-dial-bound (35s)
 *
 * When the bootstrap "Host setup needed" sheet is dismissed via Skip its warm
 * `warm-host-connect` lease is released, so the first reconcile needs a FRESH
 * cold dial. On a real/slow network that dial exceeds 12s, so the reconcile
 * timed out with `FolderReconcileTimeoutException` → `ConnectError` even though
 * the host was perfectly connectable — the maintainer's "Session list didn't
 * load within 12000ms. Tap to retry." with the app sitting ~28s on the spinner.
 *
 * This test reproduces that EXACTLY at the view-model level: a SLOW connector
 * (a [delay] longer than the 12s reconcile bound but shorter than the 35s
 * connect timeout) backs a real [SshLeaseManager]; a gateway that ACQUIRES from
 * that manager (like the production [SshFolderListGateway]) models the
 * connect-inside-enumeration; and the enumeration itself is fast once a warm
 * transport exists.
 *
 * - RED on the un-fixed code: the gateway's acquire is inside the 12s window, so
 *   the slow dial blows the budget → the picker surfaces a spurious ConnectError
 *   on a connectable host (the #847 hang).
 * - GREEN with the fix ([FolderListViewModel.ensureWarmConnectForReconcile]):
 *   the connect is established OUTSIDE the enumeration window (its own ~35s
 *   bound), so a slow-but-valid connect succeeds and only the fast enumeration
 *   is bounded by 12s → the picker reaches `Ready` with the live sessions.
 *
 * Deterministic under the virtual clock: the slow connect is a [delay] on the
 * lease manager's `connectTimeoutContext` test dispatcher, so [advanceTimeBy]
 * crosses it precisely.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class FolderListViewModelConnectTimeoutInversionTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val viewModelStore = ViewModelStore()
    private var nextViewModelKey: Int = 0

    @After
    fun tearDown() {
        viewModelStore.clear()
    }

    /**
     * The load-bearing red→green: a slow-but-valid cold dial (20s, > the 12s
     * reconcile bound, < the 35s connect timeout) for the reconcile lease
     * must NOT surface as the 12s "didn't load within 12000ms" error — the
     * picker must reach Ready with the live session once the connect lands.
     */
    @Test
    fun slowColdDialForReconcileLeaseReachesReadyNotTimeout() = runTest {
        val session = FakeSshSession()
        val connector = SlowLeaseConnector(session, connectDelayMs = SLOW_CONNECT_MS, scheduler = testScheduler)
        val manager = newManager(connector)
        val gateway = LeaseAcquiringGateway(manager, HOST)
        val vm = newViewModel(gateway, manager)
        try {
            bind(vm)
            // Cross the bind-time + first-snapshot machinery so the cold-start
            // reconcile is launched and parked on the slow connect.
            advanceTimeBy(1_000L)
            runCurrent()

            // While the cold dial is in flight (before the 12s bound would fire)
            // the picker is Loading — the user sees "Connecting…", not an error.
            assertTrue(
                "while the slow connect is in flight the picker is Loading; was ${vm.state.value}",
                vm.state.value is FolderListUiState.Loading,
            )

            // Advance PAST the 12s reconcile bound but BEFORE the slow connect
            // completes. The bug: the gateway acquire was inside the 12s window,
            // so here the un-fixed code has already surfaced a ConnectError.
            advanceTimeBy(FolderListViewModel.RECONCILE_TIMEOUT_MS + 1_000L)
            runCurrent()
            assertTrue(
                "a slow-but-valid connect must NOT be surfaced as the 12s reconcile " +
                    "timeout (the #847 inversion); the picker must still be Loading " +
                    "while the connect completes — was ${vm.state.value}",
                vm.state.value is FolderListUiState.Loading,
            )

            // Let the slow connect complete; the enumeration then runs fast over
            // the warm transport and the picker reaches Ready with the session.
            advanceUntilIdle()
            runCurrent()
            val ready = vm.state.value
            assertTrue(
                "after a slow-but-valid connect the picker must reach Ready, never " +
                    "a connect timeout on a connectable host; was $ready",
                ready is FolderListUiState.Ready,
            )
            assertEquals(
                setOf("alpha"),
                (ready as FolderListUiState.Ready).flatSessions.map { it.sessionName }.toSet(),
            )
        } finally {
            vm.stopPolling()
        }
    }

    /**
     * Class coverage: the WARM-REUSE fast path is unchanged. When the bind-time
     * warm lease is already established (a fast connect), the reconcile reuses it
     * and reaches Ready quickly — the fix must not slow the healthy / warm-reuse
     * journey or change its outcome.
     */
    @Test
    fun fastConnectWarmReuseStillReachesReadyQuickly() = runTest {
        val session = FakeSshSession()
        val connector = SlowLeaseConnector(session, connectDelayMs = 0L, scheduler = testScheduler)
        val manager = newManager(connector)
        val gateway = LeaseAcquiringGateway(manager, HOST)
        val vm = newViewModel(gateway, manager)
        try {
            bind(vm)
            advanceTimeBy(1_000L)
            runCurrent()
            // Well within the 12s budget — a fast connect + reuse reaches Ready.
            val ready = vm.state.value
            assertTrue(
                "a fast warm-reuse connect must reach Ready quickly; was $ready",
                ready is FolderListUiState.Ready,
            )
            assertEquals(
                setOf("alpha"),
                (ready as FolderListUiState.Ready).flatSessions.map { it.sessionName }.toSet(),
            )
            // Exactly one connect — the gateway acquire REUSED the warm transport
            // (no second cold dial inside the enumeration window).
            assertEquals(
                "the gateway acquire must reuse the warm transport, not cold-dial again",
                1,
                connector.connectCount,
            )
        } finally {
            vm.stopPolling()
        }
    }

    /**
     * Class coverage for the explicit bootstrap-Skip scenario: the warm
     * `warm-host-connect` lease has been RELEASED (so [FolderListViewModel] holds
     * no warm lease and its bind-time warm job is no longer in flight), and a
     * subsequent reconcile needs a FRESH slow cold dial. The fix's
     * `else if (warmLease == null)` branch must drive that dial OUTSIDE the 12s
     * enumeration window so the picker still reaches Ready, not a 12s timeout.
     */
    @Test
    fun slowDialAfterWarmLeaseReleasedReachesReadyNotTimeout() = runTest {
        val session = FakeSshSession()
        val connector = SlowLeaseConnector(session, connectDelayMs = SLOW_CONNECT_MS, scheduler = testScheduler)
        val manager = newManager(connector)
        val gateway = LeaseAcquiringGateway(manager, HOST)
        val vm = newViewModel(gateway, manager)
        try {
            bind(vm)
            // Drive the bind-time connect + first reconcile fully to Ready.
            advanceUntilIdle()
            runCurrent()
            assertTrue(
                "first open should reach Ready; was ${vm.state.value}",
                vm.state.value is FolderListUiState.Ready,
            )

            // Simulate the bootstrap Skip / step-away: release the warm lease and
            // let the lease manager's idle TTL close the transport, so the NEXT
            // reconcile must cold-dial again.
            vm.stopPolling()
            advanceTimeBy(FolderListViewModel.WARM_RELEASE_DELAY_MS + 60_000L + 1_000L)
            runCurrent()
            session.closed = true

            // Re-open (re-bind) the SAME host and refresh: the reconcile needs a
            // fresh slow dial. It must NOT surface the 12s timeout.
            bind(vm)
            vm.refresh()
            advanceTimeBy(FolderListViewModel.RECONCILE_TIMEOUT_MS + 1_000L)
            runCurrent()
            assertTrue(
                "a slow fresh cold dial after the warm lease was released must not " +
                    "surface as the 12s reconcile timeout; was ${vm.state.value}",
                vm.state.value !is FolderListUiState.ConnectError,
            )

            advanceUntilIdle()
            runCurrent()
            assertTrue(
                "after the slow fresh dial completes the picker must reach Ready; " +
                    "was ${vm.state.value}",
                vm.state.value is FolderListUiState.Ready,
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

    private fun TestScope.newManager(connector: SshLeaseConnector): SshLeaseManager =
        SshLeaseManager(
            connector = connector,
            scope = this,
            idleTtlMillis = 60_000L,
            // Issue #708: run the bounded cold connect on the same virtual clock
            // as `runTest`, not a real `Dispatchers.IO` thread.
            connectTimeoutContext = StandardTestDispatcher(testScheduler),
            nowMillis = { testScheduler.currentTime },
        )

    private fun sessionRow(name: String): FolderSessionRow =
        FolderSessionRow(
            sessionName = name,
            lastActivity = 1_000L,
            attached = false,
            cwd = "/home/alexey/git/$name",
        )

    private fun TestScope.newViewModel(
        gateway: FolderListGateway,
        sshLeaseManager: SshLeaseManager,
    ): FolderListViewModel {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        return FolderListViewModel(
            gateway = gateway,
            hostDao = FakeHostDao(HOST),
            projectRootDao = FakeProjectRootDao(),
            sshLeaseManager = sshLeaseManager,
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
     * Models the production [SshFolderListGateway]: it ACQUIRES an SSH lease from
     * the shared [SshLeaseManager] (which may need a cold dial) and only then
     * enumerates. The enumeration itself is trivial/fast.
     */
    private inner class LeaseAcquiringGateway(
        private val manager: SshLeaseManager,
        private val host: HostEntity,
    ) : FolderListGateway {
        override suspend fun listSessionsWithFolder(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            watchedRoots: List<ProjectRootEntity>,
        ): FolderListResult {
            // Build the SAME lease key the VM's warm-lease acquire uses (host /
            // port / user / "$hostId:$keyPath" / accept-all) so the pool
            // single-flights one connect and the gateway acquire REUSES the warm
            // transport — mirroring the production [SshFolderListGateway].
            val target = SshLeaseTarget(
                leaseKey = SshLeaseKey(
                    host = this.host.hostname,
                    port = this.host.port,
                    user = this.host.username,
                    credentialId = "${this.host.id}:$keyPath",
                    knownHostsId = "accept-all",
                ),
                key = SshKey.Path(File(keyPath)),
                passphrase = passphrase?.copyOf(),
                knownHosts = KnownHostsPolicy.AcceptAll,
            )
            val lease: SshLease = manager.acquire(target)
                .getOrElse { return FolderListResult.ConnectFailed(it) }
            return try {
                // Enumeration over the (now warm) transport is fast.
                FolderListResult.Sessions(rows = listOf(sessionRow("alpha")))
            } finally {
                lease.release()
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

    /**
     * A connector whose connect takes [connectDelayMs] of virtual time — models a
     * slow-but-valid cold sshj dial. The delay runs on the scheduler so
     * `advanceTimeBy` crosses it deterministically.
     */
    private class SlowLeaseConnector(
        private val session: FakeSshSession,
        private val connectDelayMs: Long,
        @Suppress("unused") private val scheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
    ) : SshLeaseConnector {
        private val count = AtomicInteger(0)
        val connectCount: Int get() = count.get()

        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
            val attempt = count.incrementAndGet()
            if (connectDelayMs > 0) delay(connectDelayMs)
            // First connect hands back the shared [session] the tests inspect; any
            // RE-dial (after the warm lease/transport was released + closed) mints
            // a fresh live session so the re-opened journey gets a connected
            // transport (mirrors a real reconnect).
            return Result.success(if (attempt == 1) session else FakeSshSession())
        }
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

        override val isConnected: Boolean
            get() = !closed

        override suspend fun exec(command: String): ExecResult =
            ExecResult(stdout = "", stderr = "", exitCode = 0)

        override fun tail(path: String, onLine: (String) -> Unit) =
            error("not used")

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = error("not used")

        override fun startShell(): SshShell = error("not used")

        override suspend fun uploadFile(file: File, remotePath: String): String =
            error("not used")

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

        /**
         * 20s — longer than the 12s [FolderListViewModel.RECONCILE_TIMEOUT_MS]
         * enumeration bound (so the inversion bites) but shorter than the 35s
         * [SshLeaseManager] connect timeout (so it is a valid, completing dial).
         */
        const val SLOW_CONNECT_MS: Long = 20_000L

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
