package com.pocketshell.app.projects

import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.app.portfwd.ForwardingController
import com.pocketshell.app.tmux.KilledSession
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
 * Issue #464: a confirmed Kill session broadcast by the per-session screen
 * must drop the dead row from the folder/session tree promptly, and a kill
 * for a different host (or a session the tree doesn't show) must leave the
 * tree untouched.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class FolderListViewModelKillSessionTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val viewModelStore = ViewModelStore()
    private var nextViewModelKey: Int = 0

    @After
    fun tearDown() {
        viewModelStore.clear()
    }

    @Test
    fun confirmedKillRemovesSessionFromTreePromptly() = runTest {
        // Models the real user journey: the user is on the per-session
        // screen when they confirm the kill, so the tree screen is paused
        // (stopPolling on nav-away). The optimistic drop must update the
        // tree without a competing probe, and survive until the user
        // returns.
        val signals = SessionLifecycleSignals()
        val gateway = StubGateway(listOf(sessionRow("alpha"), sessionRow("beta")))
        val vm = newViewModel(gateway = gateway, signals = signals)
        try {
            bind(vm)
            runCurrent()
            assertEquals(setOf("alpha", "beta"), readySessionNames(vm))

            // User navigated onward to the session screen -> tree poll paused.
            vm.stopPolling()
            runCurrent()

            // Kill confirmed on the per-session screen for the bound host.
            signals.emitKilled(HOST.id, "beta")
            runCurrent()

            assertTrue(
                "killed session should drop from the tree promptly",
                "beta" !in readySessionNames(vm),
            )
            assertEquals(setOf("alpha"), readySessionNames(vm))

            // When the user returns, the authoritative re-probe (remote no
            // longer reports beta) keeps it gone -- no phantom row resurrected.
            gateway.rows = listOf(sessionRow("alpha"))
            bind(vm)
            runCurrent()
            assertEquals(setOf("alpha"), readySessionNames(vm))
        } finally {
            vm.stopPolling()
        }
    }

    @Test
    fun killForOtherHostLeavesTreeUntouched() = runTest {
        val signals = SessionLifecycleSignals()
        val gateway = StubGateway(listOf(sessionRow("alpha"), sessionRow("beta")))
        val vm = newViewModel(gateway = gateway, signals = signals)
        try {
            bind(vm)
            runCurrent()
            vm.stopPolling()
            runCurrent()

            assertEquals(setOf("alpha", "beta"), readySessionNames(vm))

            // A kill of a same-named session on a DIFFERENT host must not
            // touch this host's tree.
            signals.emitKilled(HOST.id + 1, "beta")
            runCurrent()

            assertEquals(
                "kill on another host must not drop this host's row",
                setOf("alpha", "beta"),
                readySessionNames(vm),
            )
        } finally {
            vm.stopPolling()
        }
    }

    @Test
    fun runningPollReconcilesKillAgainstAuthoritativeProbe() = runTest {
        // When the tree screen is still composed (poll loop live), the kill
        // signal optimistically drops the row AND kicks an immediate
        // re-probe. If the remote agrees the session is gone, it stays
        // gone; if the remote still reports it (kill did not actually
        // land), the authoritative probe reconciles it back so we never
        // permanently hide a live session.
        val signals = SessionLifecycleSignals()
        val gateway = StubGateway(listOf(sessionRow("alpha"), sessionRow("beta")))
        val vm = newViewModel(gateway = gateway, signals = signals)
        try {
            bind(vm)
            runCurrent()
            assertEquals(setOf("alpha", "beta"), readySessionNames(vm))

            // Remote no longer reports beta -> kill agreed -> row gone.
            gateway.rows = listOf(sessionRow("alpha"))
            signals.emitKilled(HOST.id, "beta")
            runCurrent()
            assertEquals(setOf("alpha"), readySessionNames(vm))

            // Remote still reports gamma alive after a (failed) kill signal
            // -> re-probe reconciles it back so a live row is never lost.
            gateway.rows = listOf(sessionRow("alpha"), sessionRow("gamma"))
            runCurrent()
            signals.emitKilled(HOST.id, "gamma")
            runCurrent()
            assertEquals(setOf("alpha", "gamma"), readySessionNames(vm))
        } finally {
            vm.stopPolling()
        }
    }

    @Test
    fun killSessionFromTreeRemovesRowAndBroadcasts() = runTest {
        // Issue #518: stopping a session from the host-detail tree row must
        // run the gateway kill, drop the row, AND broadcast via the shared
        // lifecycle signals so other view models converge too.
        val signals = SessionLifecycleSignals()
        val killed = mutableListOf<KilledSession>()
        val collector = launchKilledCollector(signals, killed)
        val gateway = StubGateway(listOf(sessionRow("alpha"), sessionRow("beta")))
        val vm = newViewModel(gateway = gateway, signals = signals)
        try {
            bind(vm)
            runCurrent()
            vm.stopPolling()
            runCurrent()
            assertEquals(setOf("alpha", "beta"), readySessionNames(vm))

            // User confirmed "Stop session" on the beta row.
            vm.killSession("beta")
            runCurrent()

            assertEquals(
                "gateway must have been asked to kill the targeted session",
                listOf("beta"),
                gateway.killedSessionNames,
            )
            assertEquals(
                "killed row drops from the tree",
                setOf("alpha"),
                readySessionNames(vm),
            )
            assertTrue(
                "kill must broadcast via SessionLifecycleSignals so siblings converge",
                killed.any { it.hostId == HOST.id && it.sessionName == "beta" },
            )
        } finally {
            collector.cancel()
            vm.stopPolling()
        }
    }

    @Test
    fun failedKillKeepsRowOnTree() = runTest {
        // A failed kill (tmux still reports the session) must NOT drop the row
        // and must NOT broadcast — the tree keeps the still-live session so the
        // user is not misled into thinking it stopped.
        val signals = SessionLifecycleSignals()
        val killed = mutableListOf<KilledSession>()
        val collector = launchKilledCollector(signals, killed)
        val gateway = StubGateway(
            rows = listOf(sessionRow("alpha"), sessionRow("beta")),
            killSucceeds = false,
        )
        val vm = newViewModel(gateway = gateway, signals = signals)
        try {
            bind(vm)
            runCurrent()
            vm.stopPolling()
            runCurrent()
            assertEquals(setOf("alpha", "beta"), readySessionNames(vm))

            vm.killSession("beta")
            runCurrent()

            assertEquals(
                "a failed kill must leave the still-live row on the tree",
                setOf("alpha", "beta"),
                readySessionNames(vm),
            )
            assertTrue(
                "a failed kill must not broadcast a lifecycle signal",
                killed.none { it.sessionName == "beta" },
            )
            assertTrue(
                "a failed kill surfaces an error action status",
                vm.actionStatus.value is FolderActionStatus.Failed,
            )
        } finally {
            collector.cancel()
            vm.stopPolling()
        }
    }

    private fun TestScope.launchKilledCollector(
        signals: SessionLifecycleSignals,
        sink: MutableList<KilledSession>,
    ) = backgroundScope.launch {
        signals.killedSessions.collect { sink.add(it) }
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
                    Result.failure(IllegalStateException("prewarm disabled for kill-session test"))
                },
                scope = this,
                idleTtlMillis = 0L,
            ),
            forwardingController = ForwardingController(ApplicationProvider.getApplicationContext()),
            sessionLifecycleSignals = signals,
            attachLifecycle = false,
        ).also {
            it.ioDispatcher = dispatcher
            it.setProcessStartedForTest(true)
            viewModelStore.put("FolderListViewModel-${nextViewModelKey++}", it)
        }
    }

    private class StubGateway(
        @Volatile var rows: List<FolderSessionRow>,
        // Issue #518: when true, killSession succeeds and removes the row from
        // the gateway's reported set (the next probe agrees the kill landed);
        // when false it fails so the failure path can be asserted.
        @Volatile var killSucceeds: Boolean = true,
    ) : FolderListGateway {
        @Volatile
        var killedSessionNames: MutableList<String> = mutableListOf()

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
        ): Result<Unit> {
            if (!killSucceeds) {
                return Result.failure(RuntimeException("tmux session '$sessionName' is still running."))
            }
            killedSessionNames.add(sessionName)
            rows = rows.filterNot { it.sessionName == sessionName }
            return Result.success(Unit)
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
