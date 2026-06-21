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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #653: when a single tmux WINDOW is closed remotely (another terminal, an
 * agent, a `kill-window`) while its session stays alive, the live `tmux -CC`
 * client emits `%window-close @<id>` ([ControlEvent.WindowClose]). The host-detail
 * picker must prune exactly that window node from the maintained tree by window
 * id — leaving sibling windows and the parent session intact — WITHOUT a full
 * gateway re-probe.
 *
 * These tests drive the [FakeTmuxClient]'s event bus and assert the by-id prune
 * is a DIRECT, in-place mutation (gateway call count does not change), the
 * window-level analogue of the #706 `%sessions-changed` whole-session reconcile.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class FolderListViewModelWindowCloseTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var context: Context
    private val viewModelStore = ViewModelStore()
    private var nextViewModelKey: Int = 0

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        viewModelStore.clear()
    }

    @Test
    fun remoteWindowClosePrunesExactlyThatWindowWithoutAReProbe() = runTest {
        // A multi-window session plus an unrelated sibling.
        val gateway = SteppingGateway(
            listOf(
                FolderListResult.Sessions(
                    rows = listOf(
                        row("multi", windows = listOf(win(0, "@0"), win(1, "@1"), win(2, "@2"))),
                        row("solo", windows = listOf(win(0, "@9"))),
                    ),
                ),
            ),
        )
        val client = FakeTmuxClient()
        val registry = registryWith(client)
        val vm = newViewModel(gateway, registry, processStarted = true)

        try {
            bind(vm)
            runCurrent()

            val first = vm.state.value as FolderListUiState.Ready
            assertEquals(1, gateway.callCount)
            assertEquals(
                listOf("@0", "@1", "@2"),
                windowIds(first, "multi"),
            )

            // Window @1 of `multi` closes remotely.
            client.emittedEvents.emit(ControlEvent.WindowClose(sessionId = "\$0", windowId = "@1"))
            runCurrent()

            val after = vm.state.value as FolderListUiState.Ready
            assertEquals(
                "the by-id window prune is a DIRECT mutation, not a gateway re-probe",
                1,
                gateway.callCount,
            )
            assertEquals(
                "only the closed window is removed; siblings keep their slots",
                listOf("@0", "@2"),
                windowIds(after, "multi"),
            )
            assertEquals(
                "the parent session and the unrelated sibling session are intact",
                listOf("multi", "solo"),
                after.flatSessions.map { it.sessionName },
            )
            assertEquals(
                "the sibling session's window is untouched",
                listOf("@9"),
                windowIds(after, "solo"),
            )
        } finally {
            // #783: stopPolling cancels the periodic reconcile loop so runTest's
            // auto-advance reaches idle (the loop would otherwise reschedule the
            // ~5-min heartbeat forever).
            vm.stopPolling()
        }
    }

    @Test
    fun windowCloseForUnknownIdLeavesTreeUnchanged() = runTest {
        val gateway = SteppingGateway(
            listOf(
                FolderListResult.Sessions(
                    rows = listOf(row("multi", windows = listOf(win(0, "@0"), win(1, "@1")))),
                ),
            ),
        )
        val client = FakeTmuxClient()
        val registry = registryWith(client)
        val vm = newViewModel(gateway, registry, processStarted = true)

        try {
            bind(vm)
            runCurrent()
            assertEquals(1, gateway.callCount)

            // An id no held window carries → no prune, no re-probe.
            client.emittedEvents.emit(ControlEvent.WindowClose(sessionId = "\$0", windowId = "@404"))
            runCurrent()

            val after = vm.state.value as FolderListUiState.Ready
            assertEquals(1, gateway.callCount)
            assertEquals(listOf("@0", "@1"), windowIds(after, "multi"))
        } finally {
            // #783: stopPolling cancels the periodic reconcile loop so runTest's
            // auto-advance reaches idle (the loop would otherwise reschedule the
            // ~5-min heartbeat forever).
            vm.stopPolling()
        }
    }

    @Test
    fun windowCloseWhileBackgroundedDoesNotMutateTheTree() = runTest {
        // D21 / foreground-only: a %window-close that lands while backgrounded
        // must not mutate the tree — consistent with the #706 gating.
        val gateway = SteppingGateway(
            listOf(
                FolderListResult.Sessions(
                    rows = listOf(row("multi", windows = listOf(win(0, "@0"), win(1, "@1")))),
                ),
            ),
        )
        val client = FakeTmuxClient()
        val registry = registryWith(client)
        val vm = newViewModel(gateway, registry, processStarted = true)

        try {
            bind(vm)
            runCurrent()

            vm.setProcessStartedForTest(false)
            client.emittedEvents.emit(ControlEvent.WindowClose(sessionId = "\$0", windowId = "@1"))
            advanceTimeBy(500L)
            runCurrent()

            val after = vm.state.value as FolderListUiState.Ready
            assertEquals(
                "a window close while backgrounded must not prune the tree (D21)",
                listOf("@0", "@1"),
                windowIds(after, "multi"),
            )
        } finally {
            // #783: stopPolling cancels the periodic reconcile loop so runTest's
            // auto-advance reaches idle (the loop would otherwise reschedule the
            // ~5-min heartbeat forever).
            vm.stopPolling()
        }
    }

    @Test
    fun windowClosedWhileOnSessionScreenStillPrunesTreeOnReturn() = runTest {
        // ISSUE #783 regression journey (FAILS on base): the user navigates from
        // the tree into the session screen, which disposes `FolderListScreen` and
        // calls `stopPolling()`. On base that cancelled the `%window-close`
        // subscription, so a window closed ON THE HOST while away landed on a
        // dead collector and was dropped — the stale `[wN]` node lingered up to
        // ~15 min. The fix ties the subscription to the bound-host warm-lease
        // lifetime, so the prune still lands while the tree screen is disposed.
        val gateway = SteppingGateway(
            listOf(
                FolderListResult.Sessions(
                    rows = listOf(
                        row("multi", windows = listOf(win(0, "@0"), win(1, "@1"), win(2, "@2"))),
                    ),
                ),
            ),
        )
        val client = FakeTmuxClient()
        val registry = registryWith(client)
        val vm = newViewModel(gateway, registry, processStarted = true)

        try {
            bind(vm)
            runCurrent()
            assertEquals(1, gateway.callCount)
            assertEquals(
                listOf("@0", "@1", "@2"),
                windowIds(vm.state.value as FolderListUiState.Ready, "multi"),
            )

            // User taps a session row → navigates to the session screen →
            // `FolderListScreen` disposes → `stopPolling()`.
            vm.stopPolling()
            runCurrent()

            // While the tree screen is torn down, a window is closed on the host.
            client.emittedEvents.emit(ControlEvent.WindowClose(sessionId = "\$0", windowId = "@1"))
            runCurrent()

            // User returns to the tree (same host re-bind). NO manual pull.
            bind(vm)
            runCurrent()

            val after = vm.state.value as FolderListUiState.Ready
            assertEquals(
                "the closed window must be GONE on return without a manual refresh (#783)",
                listOf("@0", "@2"),
                windowIds(after, "multi"),
            )
            assertEquals(
                "the prune is a DIRECT by-id mutation — no gateway re-probe was needed",
                1,
                gateway.callCount,
            )
        } finally {
            // #783: stopPolling cancels the periodic reconcile loop so runTest's
            // auto-advance reaches idle (the loop would otherwise reschedule the
            // ~5-min heartbeat forever).
            vm.stopPolling()
        }
    }

    @Test
    fun sameHostReturnKeepsTheSubscriptionLiveAcrossStopPolling() = runTest {
        // #783: a stopPolling()→bind() round-trip (leave tree, come back) must
        // NOT drop a window-close that arrives AFTER the return — the live
        // subscription is kept across the screen dispose, so the very next event
        // still prunes.
        val gateway = SteppingGateway(
            listOf(
                FolderListResult.Sessions(
                    rows = listOf(row("multi", windows = listOf(win(0, "@0"), win(1, "@1")))),
                ),
            ),
        )
        val client = FakeTmuxClient()
        val registry = registryWith(client)
        val vm = newViewModel(gateway, registry, processStarted = true)

        try {
            bind(vm)
            runCurrent()
            vm.stopPolling()
            runCurrent()
            bind(vm)
            runCurrent()

            client.emittedEvents.emit(ControlEvent.WindowClose(sessionId = "\$0", windowId = "@1"))
            runCurrent()

            assertEquals(
                "a window close after a same-host return is still pruned (#783)",
                listOf("@0"),
                windowIds(vm.state.value as FolderListUiState.Ready, "multi"),
            )
            assertEquals(1, gateway.callCount)
        } finally {
            // #783: stopPolling cancels the periodic reconcile loop so runTest's
            // auto-advance reaches idle (the loop would otherwise reschedule the
            // ~5-min heartbeat forever).
            vm.stopPolling()
        }
    }

    @Test
    fun periodicReconcileFiresWhileBoundAndForegrounded() = runTest {
        // #783: the ~5-min foreground heartbeat reconciles host-side changes that
        // emitted no control event. After PERIODIC_RECONCILE_MS the gateway is
        // re-probed and the dropped session disappears.
        val gateway = SteppingGateway(
            listOf(
                FolderListResult.Sessions(
                    rows = listOf(
                        row("multi", windows = listOf(win(0, "@0"))),
                        row("solo", windows = listOf(win(0, "@9"))),
                    ),
                ),
                // Second probe (the periodic tick): `solo` was killed on the host.
                FolderListResult.Sessions(
                    rows = listOf(row("multi", windows = listOf(win(0, "@0")))),
                ),
            ),
        )
        val client = FakeTmuxClient()
        val registry = registryWith(client)
        val vm = newViewModel(gateway, registry, processStarted = true)
        // #783: the heartbeat is OFF by default for directly-constructed VMs;
        // this test specifically exercises it, so enable it before bind.
        vm.setPeriodicReconcileEnabledForTest(true)

        try {
            bind(vm)
            runCurrent()
            assertEquals(1, gateway.callCount)
            assertEquals(
                listOf("multi", "solo"),
                (vm.state.value as FolderListUiState.Ready).flatSessions.map { it.sessionName },
            )

            // Advance past the ~5-min heartbeat — the tick fires a reconcile.
            advanceTimeBy(FolderListViewModel.PERIODIC_RECONCILE_MS + 100L)
            runCurrent()

            assertEquals(
                "the periodic ~5-min heartbeat re-probes the host (#783)",
                2,
                gateway.callCount,
            )
            assertEquals(
                "the host-killed session is gone after the periodic reconcile",
                listOf("multi"),
                (vm.state.value as FolderListUiState.Ready).flatSessions.map { it.sessionName },
            )
        } finally {
            // #783: stopPolling cancels the periodic reconcile loop so runTest's
            // auto-advance reaches idle (the loop would otherwise reschedule the
            // ~5-min heartbeat forever).
            vm.stopPolling()
        }
    }

    @Test
    fun inSessionWindowStopPrunesOnlyThatWindowRow() = runTest {
        // Issue #883: the in-session "Stop session" on a `[wN]` window row kills
        // just that window (kill-window) and — when a sibling window survived —
        // the per-session screen broadcasts a ClosedWindow over
        // SessionLifecycleSignals, which the tree consumes via [onWindowClosed].
        // The tree must drop ONLY that window's row by id; the sibling window +
        // the parent session + an unrelated session stay. Pre-fix there was no
        // ClosedWindow path at all (in-session Stop always killed the whole
        // session). We drive [onWindowClosed] directly (the handler the
        // collector invokes) so the assertion is deterministic and independent
        // of cross-test Main-dispatcher ordering; a separate test proves the
        // signal→handler wiring.
        val gateway = SteppingGateway(
            listOf(
                FolderListResult.Sessions(
                    rows = listOf(
                        row("multi", windows = listOf(win(0, "@30"), win(1, "@31"))),
                        row("solo", windows = listOf(win(0, "@39"))),
                    ),
                ),
            ),
        )
        val vm = newViewModel(gateway, registryWith(FakeTmuxClient()), processStarted = true)
        try {
            bind(vm)
            runCurrent()
            assertEquals(
                listOf("@30", "@31"),
                windowIds(vm.state.value as FolderListUiState.Ready, "multi"),
            )

            // Real journey: the user navigated to the session screen → the tree
            // screen's probe is paused (stopPolling), so the optimistic by-id
            // drop must stand on its own. Window @30 was killed; the session
            // survived → ClosedWindow(@30).
            vm.stopPolling()
            runCurrent()
            vm.onWindowClosed(com.pocketshell.app.tmux.ClosedWindow(HOST.id, "@30"))
            runCurrent()

            val after = vm.state.value as FolderListUiState.Ready
            assertEquals(
                "only the killed window @30 is removed; the sibling window @31 survives",
                listOf("@31"),
                windowIds(after, "multi"),
            )
            assertEquals(
                "the parent session and the unrelated session stay on the tree",
                listOf("multi", "solo"),
                after.flatSessions.map { it.sessionName },
            )
            assertEquals(
                "the unrelated session's window is untouched",
                listOf("@39"),
                windowIds(after, "solo"),
            )
        } finally {
            vm.stopPolling()
        }
    }

    @Test
    fun closedWindowForOtherHostLeavesTreeUntouched() = runTest {
        // Issue #883 class coverage: a ClosedWindow for a DIFFERENT host must
        // not mutate this host's tree.
        val gateway = SteppingGateway(
            listOf(
                FolderListResult.Sessions(
                    rows = listOf(row("multi", windows = listOf(win(0, "@40"), win(1, "@41")))),
                ),
            ),
        )
        val vm = newViewModel(gateway, registryWith(FakeTmuxClient()), processStarted = true)
        try {
            bind(vm)
            runCurrent()
            vm.stopPolling()
            runCurrent()

            vm.onWindowClosed(com.pocketshell.app.tmux.ClosedWindow(HOST.id + 1, "@40"))
            runCurrent()

            assertEquals(
                "a window close on another host must not touch this host's tree",
                listOf("@40", "@41"),
                windowIds(vm.state.value as FolderListUiState.Ready, "multi"),
            )
        } finally {
            vm.stopPolling()
        }
    }

    @Test
    fun closedWindowsSignalIsWiredToOnWindowClosed() = runTest {
        // Issue #883: prove the per-session screen's ClosedWindow broadcast is
        // actually subscribed to and routed into the tree prune. We construct
        // the VM WITH the shared SessionLifecycleSignals (the Hilt singleton),
        // emit a ClosedWindow, and confirm the window row is pruned — i.e. the
        // init wired signals.closedWindows.collect -> onWindowClosed.
        val signals = com.pocketshell.app.tmux.SessionLifecycleSignals()
        val gateway = SteppingGateway(
            listOf(
                FolderListResult.Sessions(
                    rows = listOf(row("multi", windows = listOf(win(0, "@50"), win(1, "@51")))),
                ),
            ),
        )
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val vm = FolderListViewModel(
            gateway = gateway,
            hostDao = FakeHostDao(HOST),
            projectRootDao = FakeProjectRootDao(),
            sshLeaseManager = SshLeaseManager(
                connector = SshLeaseConnector {
                    Result.failure(IllegalStateException("prewarm disabled for window-close test"))
                },
                scope = this,
                idleTtlMillis = 0L,
            ),
            applicationContext = context,
            forwardingController = ForwardingController(context),
            sessionLifecycleSignals = signals,
            attachLifecycle = false,
        ).also {
            it.ioDispatcher = dispatcher
            it.setProcessStartedForTest(true)
            viewModelStore.put("FolderListViewModel-${nextViewModelKey++}", it)
        }
        try {
            bind(vm)
            // Settle the cold-bind reconcile until Ready (bounded; heartbeat off).
            repeat(50) {
                if (vm.state.value is FolderListUiState.Ready) return@repeat
                testScheduler.advanceTimeBy(50)
                runCurrent()
            }
            check(vm.state.value is FolderListUiState.Ready) {
                "tree never reached Ready; state=${vm.state.value}"
            }
            vm.stopPolling()
            runCurrent()

            // Emit on the SHARED signals bus — must reach onWindowClosed.
            signals.emitWindowClosed(HOST.id, "@50")
            repeat(10) { runCurrent() }

            assertEquals(
                "the ClosedWindow signal must be wired to prune the window row",
                listOf("@51"),
                windowIds(vm.state.value as FolderListUiState.Ready, "multi"),
            )
        } finally {
            vm.stopPolling()
        }
    }

    private fun windowIds(state: FolderListUiState.Ready, sessionName: String): List<String?> =
        state.flatSessions.first { it.sessionName == sessionName }.windows.map { it.windowId }

    private fun registryWith(client: FakeTmuxClient): ActiveTmuxClients =
        ActiveTmuxClients().also {
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
                    Result.failure(IllegalStateException("prewarm disabled for window-close test"))
                },
                scope = this,
                idleTtlMillis = 0L,
            ),
            applicationContext = context,
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
        windows: List<FolderSessionWindowRow> = emptyList(),
    ): FolderSessionRow =
        FolderSessionRow(
            sessionName = name,
            lastActivity = lastActivity,
            attached = false,
            cwd = cwd,
            agentKind = SessionAgentKind.Shell,
            windows = windows,
        )

    private fun win(
        index: Int,
        windowId: String,
        sessionName: String = "",
        active: Boolean = index == 0,
    ): FolderSessionWindowRow =
        FolderSessionWindowRow(
            sessionName = sessionName,
            index = index,
            name = "win$index",
            active = active,
            cwd = null,
            tty = null,
            command = null,
            agentKind = SessionAgentKind.Shell,
            windowId = windowId,
        )

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
            id = 653L,
            name = "docker",
            hostname = "10.0.2.2",
            port = 2222,
            username = "testuser",
            keyId = 7L,
        )
    }
}
