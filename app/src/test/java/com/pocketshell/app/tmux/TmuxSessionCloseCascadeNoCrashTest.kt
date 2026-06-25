package com.pocketshell.app.tmux

import com.pocketshell.app.projects.FolderListGateway
import com.pocketshell.app.session.AgentConversationRepository
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshException
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.tmux.TmuxClientFactory
import com.pocketshell.core.tmux.protocol.ControlEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicReference

/**
 * Issue #896 — App CRASHES (process death) when closing a session.
 *
 * Root cause (research spike #896): `TmuxSessionViewModel`'s `bridgeScope`
 * (and the `viewModelScope` grace / auto-reconnect launches on the same
 * cascade) carried NO [kotlinx.coroutines.CoroutineExceptionHandler]. A
 * SupervisorJob isolates SIBLING cancellation but does NOT swallow a child's
 * exception — so when closing the attached/last session triggers the gateway
 * kill → tmux destroyed → live `-CC` control client EOFs, the EOF fans out to
 * several `bridgeScope.launch {}` collectors AT THE SAME MOMENT the scopes are
 * being torn down, and any one of them firing IO against the now-dead transport
 * throws (the captured June-8 specimen: `SshException: SSH session is not
 * connected`). With no scope-level handler, that throw propagated to
 * `Thread.defaultUncaughtExceptionHandler` → process death.
 *
 * Reproduce-first (D33 / G1 / G10): [FakeTmuxClient.throwFromEventsCollectorOnNextEmit]
 * makes the `client.events` flow THROW on its next emission — faithfully
 * reproducing a real close/EOF-cascade collector (the
 * `bridgeScope.launch { client.events.collect {} }` in
 * `TmuxSessionViewModel.bindClientObservers`) firing against a dead transport.
 *
 * The load-bearing assertion (the #780 synthetic model): we install a probe on
 * `Thread.setDefaultUncaughtExceptionHandler` AND on the VM's
 * `bridgeCoroutineFailureProbe`. WITHOUT the fix the cascade throw reaches the
 * thread's uncaught handler (= process death on device). WITH the fix the throw
 * lands in the VM's scope-level handler instead and is recorded as a non-fatal —
 * the thread handler is NEVER hit. To watch this go RED on base, revert the
 * `bridgeExceptionHandler` wiring in TmuxSessionViewModel: every case below
 * then trips `uncaughtOnThread`.
 *
 * Class coverage (G2): whole/last-session close, one-of-many window close, a
 * close racing an in-flight reattach (the grace `viewModelScope.launch`
 * path), an AGENT pane, and a SHELL pane.
 *
 * Anti-#895-masking: [genuineTransportDropStillSurfacesEscapableBand] proves
 * the handler does NOT eat a real drop — a genuine `-CC` drop arrives as a
 * normal `client.disconnected` EMISSION (never a throw), so it still routes to
 * the lifecycle and surfaces the escapable "Tap Reconnect" band.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class TmuxSessionCloseCascadeNoCrashTest {

    private val scheduler = TestCoroutineScheduler()
    private val testMainDispatcher = UnconfinedTestDispatcher(scheduler)

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(testMainDispatcher)

    private val factoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val createdViewModels = mutableListOf<TmuxSessionViewModel>()

    // The thread-level uncaught-exception probe. On device this slot is the
    // CrashReporter handler that records then re-delegates to the platform
    // handler → process death. If a cascade throw reaches HERE, the user-visible
    // symptom (#896) is present.
    private var previousThreadHandler: Thread.UncaughtExceptionHandler? = null
    private val uncaughtOnThread = AtomicReference<Throwable?>(null)

    @org.junit.Before
    fun installThreadHandlerProbe() {
        previousThreadHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            uncaughtOnThread.set(throwable)
        }
    }

    private fun newVm(
        registry: ActiveTmuxClients = ActiveTmuxClients(),
        sessionLifecycleSignals: SessionLifecycleSignals? = null,
        folderListGateway: FolderListGateway? = null,
        hostDao: HostDao? = null,
        agentRepository: AgentConversationRepository = AgentConversationRepository(),
    ): TmuxSessionViewModel =
        TmuxSessionViewModel(
            tmuxClientFactory = TmuxClientFactory(factoryScope),
            activeTmuxClients = registry,
            hostDao = hostDao,
            folderListGateway = folderListGateway,
            sshLeaseManager = SshLeaseManager(
                connector = SshLeaseConnector { target ->
                    error("unexpected SSH lease connect for ${target.leaseKey}")
                },
                idleTtlMillis = 0L,
                connectTimeoutContext = StandardTestDispatcher(scheduler),
                nowMillis = { scheduler.currentTime },
            ),
            sessionLifecycleSignals = sessionLifecycleSignals,
            agentRepository = agentRepository,
        ).also {
            it.setReconcileDispatcherForTest(testMainDispatcher)
            it.setReconcileApplyDispatcherForTest(testMainDispatcher)
            // Issue #926: pin the seed-IO dispatcher (off-Main hop for the
            // attach/switch/reattach `capture-pane`/`list-panes` IO) to the
            // virtual-clock test Main so the round-trips run inline on the test
            // scheduler — a real `Dispatchers.IO` default would leak a thread the
            // `runTest` virtual clock cannot advance. Production defaults to
            // `Dispatchers.IO` (off the UI thread, so the seed never freezes it).
            it.setSeedIoDispatcherForTest(testMainDispatcher)
            it.setPortDetectionDispatcherForTest(testMainDispatcher)
            createdViewModels += it
        }

    @After
    fun tearDown() {
        previousThreadHandler?.let { Thread.setDefaultUncaughtExceptionHandler(it) }
        createdViewModels.asReversed().forEach { vm -> runCatching { vm.clearForTest() } }
        createdViewModels.clear()
        factoryScope.cancel()
        runBlocking {
            withTimeoutOrNull(5_000L) {
                factoryScope.coroutineContext.job.children.forEach { it.join() }
            }
        }
    }

    private fun attach(
        vm: TmuxSessionViewModel,
        client: FakeTmuxClient,
        hostId: Long = 7L,
        sessionName: String = "work",
    ) {
        vm.replaceClientForTest(
            hostId = hostId,
            hostName = "docker",
            host = "10.0.2.2",
            port = 2222,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = sessionName,
            client = client,
        )
    }

    private val theBoom = SshException("SSH session is not connected")

    /**
     * Assert the scope-level safety net caught the cascade throw (and the
     * thread handler / process is untouched).
     */
    private fun assertCaughtBySafetyNetNotThread(
        caughtBySafetyNet: AtomicReference<Throwable?>,
    ) {
        assertNull(
            "PROCESS-DEATH: a close/EOF-cascade throw reached the thread's " +
                "uncaught-exception handler (= app crash on device, #896). " +
                "It must be swallowed by the VM scope-level handler instead.",
            uncaughtOnThread.get(),
        )
        assertNotNull(
            "the cascade throw must be observed by the VM's scope-level safety " +
                "net (bridgeCoroutineFailureProbe), proving the handler is wired",
            caughtBySafetyNet.get(),
        )
        assertEquals(theBoom, caughtBySafetyNet.get())
    }

    // ------------------------------------------------------------ class case (a)
    @Test
    fun wholeSessionCloseCascadeThrowDoesNotCrash() = runTest(scheduler) {
        val caught = AtomicReference<Throwable?>(null)
        val signals = SessionLifecycleSignals()
        val gateway = RecordingKillGateway(killSucceeds = true)
        val vm = newVm(
            sessionLifecycleSignals = signals,
            folderListGateway = gateway,
            hostDao = SingleHostDao(hostId = 7L),
        )
        vm.bridgeCoroutineFailureProbe = { caught.set(it) }
        val client = FakeTmuxClient()
        attach(vm, client, sessionName = "doomed")
        runCurrent()

        // Close the attached/last session (the higher-crash-risk path).
        vm.killCurrentSession()
        advanceUntilIdle()

        // The kill→EOF cascade: a collector fires against the dead transport.
        client.throwFromEventsCollectorOnNextEmit = theBoom
        client.emittedEvents.emit(ControlEvent.Output("%1", "x".toByteArray()))
        advanceUntilIdle()

        assertCaughtBySafetyNetNotThread(caught)
    }

    // ------------------------------------------------------------ class case (b)
    @Test
    fun oneOfSeveralWindowCloseCascadeThrowDoesNotCrash() = runTest(scheduler) {
        val caught = AtomicReference<Throwable?>(null)
        val signals = SessionLifecycleSignals()
        val gateway = RecordingKillGateway(killSucceeds = true, windowKillSessionSurvived = true)
        val vm = newVm(
            sessionLifecycleSignals = signals,
            folderListGateway = gateway,
            hostDao = SingleHostDao(hostId = 7L),
        )
        vm.bridgeCoroutineFailureProbe = { caught.set(it) }
        val client = FakeTmuxClient()
        attach(vm, client, sessionName = "multi")
        runCurrent()

        // Close ONE window; the session (and control client) survives.
        vm.killCurrentSession(windowIndex = 1)
        advanceUntilIdle()

        client.throwFromEventsCollectorOnNextEmit = theBoom
        client.emittedEvents.emit(ControlEvent.Output("%1", "x".toByteArray()))
        advanceUntilIdle()

        assertCaughtBySafetyNetNotThread(caught)
    }

    // ------------------------------------------------------------ class case (c)
    @Test
    fun closeRacingInFlightReattachCascadeThrowDoesNotCrash() = runTest(scheduler) {
        val caught = AtomicReference<Throwable?>(null)
        val vm = newVm()
        vm.bridgeCoroutineFailureProbe = { caught.set(it) }
        // Make the silent-reattach grace path run (viewModelScope.launch(handler)).
        vm.setPassiveDisconnectRecoveryForTest(graceMs = 50L, silentReattachTimeoutMs = 1L)
        vm.setAutoReconnectDelaysForTest(listOf(0L))
        val client = FakeTmuxClient()
        attach(vm, client)
        runCurrent()

        // A genuine drop kicks the within-grace silent reattach loop (the
        // viewModelScope grace launch). While it is in flight, a sibling
        // cascade collector throws against the dead transport.
        vm.triggerCleanPassiveDropForTest()
        client.throwFromEventsCollectorOnNextEmit = theBoom
        client.emittedEvents.emit(ControlEvent.Output("%1", "x".toByteArray()))
        advanceUntilIdle()

        assertCaughtBySafetyNetNotThread(caught)
    }

    // ------------------------------------------------------------ class case (d)
    @Test
    fun agentPaneCloseCascadeThrowDoesNotCrash() = runTest(scheduler) {
        val caught = AtomicReference<Throwable?>(null)
        val vm = newVm()
        vm.bridgeCoroutineFailureProbe = { caught.set(it) }
        val client = FakeTmuxClient()
        attach(vm, client)
        runCurrent()

        // Bind an agent conversation on the pane so the higher-risk variant
        // (extra agent tail/detection collectors, all on the handled scope)
        // is active when the cascade throw fires — the June-8 specimen was an
        // agent tail read.
        vm.appendAgentEventsForTest(
            paneId = "%1",
            events = listOf(
                com.pocketshell.core.agents.ConversationEvent.Message(
                    id = "e1",
                    agent = AgentKind.ClaudeCode,
                    role = com.pocketshell.core.agents.ConversationRole.User,
                    text = "hi",
                ),
            ),
        )
        runCurrent()

        client.throwFromEventsCollectorOnNextEmit = theBoom
        client.emittedEvents.emit(ControlEvent.Output("%1", "x".toByteArray()))
        advanceUntilIdle()

        assertCaughtBySafetyNetNotThread(caught)
    }

    // ------------------------------------------------------------ class case (e)
    @Test
    fun shellPaneCloseCascadeThrowDoesNotCrash() = runTest(scheduler) {
        val caught = AtomicReference<Throwable?>(null)
        val vm = newVm()
        vm.bridgeCoroutineFailureProbe = { caught.set(it) }
        val client = FakeTmuxClient()
        attach(vm, client)
        runCurrent()

        // Plain shell pane (no agent conversation bound).
        client.throwFromEventsCollectorOnNextEmit = theBoom
        client.emittedEvents.emit(ControlEvent.Output("%1", "x".toByteArray()))
        advanceUntilIdle()

        assertCaughtBySafetyNetNotThread(caught)
    }

    // ------------------------------------------------------------ class case (f)
    @Test
    fun switchSessionCascadeThrowDoesNotCrash() = runTest(scheduler) {
        // The maintainer's ACTUALLY-reported #896 trigger: tapping the "Switch
        // session" kebab action crashed the app on the spot. A same-host fast
        // switch tears down the LEAVING session's `-CC` runtime (the kill/EOF of
        // the old control client) and re-binds the bridge collectors onto the
        // NEW client — i.e. it runs the very same EOF-cascade-through-no-handler
        // class as close, just on the switch teardown/re-bind boundary. This case
        // proves the scope-level safety net (the same `bridgeExceptionHandler`
        // the close cases assert) also nets a cascade throw that fires during the
        // switch, so the reported "Switch session → crash" instance is covered as
        // a first-class enumerated case (D31/G2), not only mechanistically.
        val caught = AtomicReference<Throwable?>(null)
        val registry = ActiveTmuxClients()
        val vm = newVm(registry = registry)
        vm.bridgeCoroutineFailureProbe = { caught.set(it) }

        // Be "connected on the same host" first (the switch precondition).
        val leavingClient = FakeTmuxClient()
        val session = SwitchSshSession()
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "docker",
            host = "10.0.2.2",
            port = 2222,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "leaving",
            client = leavingClient,
            session = session,
        )
        runCurrent()

        // Switch to a sibling session on the SAME host (the fast-switch path):
        // this deactivates the leaving runtime and binds the new client's
        // bridge collectors.
        val arrivingClient = FakeTmuxClient()
        vm.fastSwitchSessionForTest(
            hostId = 7L,
            hostName = "docker",
            host = "10.0.2.2",
            port = 2222,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "arriving",
            client = arrivingClient,
            session = session,
        )
        advanceUntilIdle()

        // During the switch cascade a re-bound collector fires against the dead
        // transport and throws — exactly the on-device #896 "Switch session"
        // crash. The scope-level net must catch it; the thread handler (= process
        // death) must never be hit.
        arrivingClient.throwFromEventsCollectorOnNextEmit = theBoom
        arrivingClient.emittedEvents.emit(ControlEvent.Output("%1", "x".toByteArray()))
        advanceUntilIdle()

        assertCaughtBySafetyNetNotThread(caught)
    }

    // ------------------------------------------------- anti-#895-masking guard
    @Test
    fun genuineTransportDropStillSurfacesEscapableBand() = runTest(scheduler) {
        // The handler must NOT swallow a genuine transport DROP. A real `-CC`
        // drop arrives as a normal `client.disconnected` EMISSION (not a
        // throw), so it still routes to the lifecycle → escapable "Tap
        // Reconnect" band. If the safety net ever ate the drop signal, the user
        // would be left in a silent no-band stall (#895). This proves it does
        // not: the band surfaces AND the safety net never fired.
        val caught = AtomicReference<Throwable?>(null)
        val registry = ActiveTmuxClients()
        val vm = newVm(registry = registry)
        vm.bridgeCoroutineFailureProbe = { caught.set(it) }
        // Force straight to the Failed band (no silent grace, slow auto-reconnect).
        vm.setPassiveDisconnectRecoveryForTest(graceMs = 0L, silentReattachTimeoutMs = 1L)
        vm.setAutoReconnectDelaysForTest(listOf(60_000L))
        val client = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )
        runCurrent()

        client.disconnectedSignal.value = true
        runCurrent()

        val status = vm.connectionStatus.value
        assertTrue(
            "a genuine drop must still surface the escapable Failed/Reconnect " +
                "band (anti-#895-masking); got $status",
            status is TmuxSessionViewModel.ConnectionStatus.Failed,
        )
        assertEquals(
            "Disconnected from alex@alpha.example:22. Tap Reconnect to retry.",
            (status as TmuxSessionViewModel.ConnectionStatus.Failed).message,
        )
        assertTrue("Reconnect must remain available after a drop", vm.canReconnect.value)
        assertNull(
            "a genuine drop is a normal emission, not a throw — the safety net " +
                "must NOT have fired (it would mean the drop was masked)",
            caught.get(),
        )
        assertNull("a drop must never reach the thread uncaught handler", uncaughtOnThread.get())
    }

    // --------------------------------------------------------------- helpers
    private class RecordingKillGateway(
        private val killSucceeds: Boolean,
        private val windowKillSessionSurvived: Boolean? = null,
    ) : FolderListGateway {
        override suspend fun listSessionsWithFolder(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            watchedRoots: List<com.pocketshell.core.storage.entity.ProjectRootEntity>,
        ): com.pocketshell.app.projects.FolderListResult =
            com.pocketshell.app.projects.FolderListResult.Sessions(rows = emptyList())

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
            payload: com.pocketshell.app.projects.FolderImportPayload,
        ): Result<String> = error("not used")

        override suspend fun killSession(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            sessionName: String,
        ): Result<Unit> =
            if (killSucceeds) Result.success(Unit)
            else Result.failure(RuntimeException("still running"))

        override suspend fun killWindow(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            sessionName: String,
            windowIndex: Int,
        ): Result<com.pocketshell.app.projects.WindowKillOutcome> =
            Result.success(
                com.pocketshell.app.projects.WindowKillOutcome(
                    sessionSurvived = windowKillSessionSurvived ?: false,
                ),
            )
    }

    /**
     * Minimal connected [SshSession] so [fastSwitchSessionForTest] (which needs
     * a non-null, connected session ref to mirror a same-host warm switch) can
     * run. The switch path stores it as `sessionRef` and re-binds the bridge
     * collectors onto the new fake [TmuxClient]; the cascade throw is driven via
     * the client's events-collector seam, so this session never needs to do real
     * exec/tail work.
     */
    private class SwitchSshSession : SshSession {
        @Volatile
        var closed: Boolean = false

        override val isConnected: Boolean
            get() = !closed

        override suspend fun exec(command: String): ExecResult =
            ExecResult(stdout = "", stderr = "", exitCode = 0)

        override fun tail(path: String, onLine: (String) -> Unit): Job = Job()

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = error("not used")

        override fun startShell(): SshShell = error("not used")

        override suspend fun uploadFile(file: java.io.File, remotePath: String): String =
            error("not used")

        override suspend fun uploadStream(
            input: java.io.InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("not used")

        override fun close() {
            closed = true
        }
    }

    private class SingleHostDao(private val hostId: Long) : HostDao {
        private val host = HostEntity(
            id = hostId,
            name = "docker",
            hostname = "10.0.2.2",
            port = 2222,
            username = "alex",
            keyId = 1L,
        )

        override fun getAll() = flowOf(listOf(host))
        override suspend fun getById(id: Long): HostEntity? = host.takeIf { it.id == id }
        override fun getEnabled() = flowOf(listOf(host))
        override suspend fun insert(host: HostEntity): Long = error("not used")
        override suspend fun update(host: HostEntity) = error("not used")
        override suspend fun delete(host: HostEntity) = error("not used")
        override suspend fun deleteById(id: Long) = error("not used")
    }
}
