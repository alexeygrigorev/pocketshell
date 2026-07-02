package com.pocketshell.app.tmux

import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.core.connection.RevealState
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshLeaseConnectCoalescedCancelException
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseKey
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.tmux.TmuxClientFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream

/**
 * Issue #1185: the maintainer, dogfooding, created a NEW session then immediately
 * SELECTED an existing session in a folder on the SAME host. The select
 * superseded (cancelled) the create/attach flow's in-flight SSH connect that the
 * selected session merely COALESCED (#620) onto. On the buggy code the lease woke
 * the selected session's acquire with a bare `CancellationException`, which the
 * consumer misclassified as a terminal failure → the selected session stranded on
 * a red "Disconnected" pill AND a live "Attaching…" spinner with no re-dial and no
 * working Retry.
 *
 * These tests drive the REAL path deterministically: a real [SshLeaseManager]
 * with a gated connector, a genuinely in-flight OWNER connect the test cancels
 * (the supersede), and the VM's real `acquireLeaseForTmux`/`failConnectAttempt`/
 * re-dial machinery. RED on base (Failed band + stranded Seeding reveal), GREEN
 * with the fix (the selected session re-dials its own fresh connect and reaches
 * Connected/Live; the reveal + pill agree; a genuine unreachable still surfaces
 * the honest terminal error without looping).
 *
 * The gated OWNER connect must stay PARKED across the coalescing window, so the
 * window is stepped with [runCurrent] (which never advances the virtual clock)
 * rather than [advanceUntilIdle] (which would advance past the lease's 35s connect
 * timeout and time the owner out before the selected session can coalesce). Only
 * AFTER the re-dial is released is [advanceUntilIdle] safe.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class TmuxSessionCoalescedCancelRedialTest {

    private val factoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        factoryScope.cancel()
    }

    private fun runVmTest(body: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        LivenessProbeTestOverride.setAutoStartEnabledForTest(false)
        try {
            body()
        } finally {
            LivenessProbeTestOverride.clear()
            Dispatchers.resetMain()
        }
    }

    private fun TestScope.coalescingLeaseManager(connector: SshLeaseConnector): SshLeaseManager =
        SshLeaseManager(
            connector = connector,
            scope = this,
            idleTtlMillis = 60_000L,
            connectTimeoutContext = StandardTestDispatcher(testScheduler),
            // Pin the owned-dial ABORT to the same virtual scheduler as the dial so
            // cancelling the parked owner is deterministic under runCurrent().
            abortTimeoutContext = StandardTestDispatcher(testScheduler),
            nowMillis = { testScheduler.currentTime },
        )

    private fun TestNewVm(
        registry: ActiveTmuxClients,
        sshLeaseManager: SshLeaseManager,
    ): TmuxSessionViewModel = TmuxSessionViewModel(
        tmuxClientFactory = TmuxClientFactory(factoryScope),
        activeTmuxClients = registry,
        runtimeCache = TmuxSessionRuntimeCache(),
        sshLeaseManager = sshLeaseManager,
        sessionLifecycleSignals = null,
    ).also {
        it.setSeedIoDispatcherForTest(Dispatchers.Main)
    }

    /**
     * The lease key the VM mints for its [connect] target — must match the owner's
     * key so the selected session COALESCES onto the owner's in-flight connect
     * (VM: `credentialId = "$hostId:$keyPath"`).
     */
    private fun vmLeaseTarget(hostId: Long, host: String, port: Int, user: String, keyPath: String) =
        SshLeaseTarget(
            leaseKey = SshLeaseKey(
                host = host,
                port = port,
                user = user,
                credentialId = "$hostId:$keyPath",
                knownHostsId = "accept-all",
            ),
            key = SshKey.Path(File(keyPath)),
        )

    /**
     * Run the reported create→switch scenario for [sessionName] and return the VM +
     * captured status stream once the re-dial has been released and driven to
     * completion.
     */
    private suspend fun TestScope.driveCreateThenSwitchCoalescedCancel(
        sessionName: String,
        paneId: String,
    ): Pair<TmuxSessionViewModel, List<TmuxSessionViewModel.ConnectionStatus>> {
        val freshSession = AlwaysConnectedSession("fresh")
        // slot #0 = the owner (create flow) connect, parked then cancelled;
        // slot #1 = the selected session's OWN fresh re-dial, released to succeed.
        val connector = GatedConnector(ownerSlot = null, redialSlot = freshSession)
        val registry = ActiveTmuxClients()
        val leaseManager = coalescingLeaseManager(connector)
        val vm = TestNewVm(registry, leaseManager)
        vm.setAutoReconnectDelaysForTest(listOf(0L))

        val recovered = fakeClientForSession(sessionName, paneId)
        vm.setTmuxClientFactoryForTest { _, requested, _ ->
            assertEquals(sessionName, requested)
            recovered
        }

        // Step 1: the create-new-session flow OWNS an in-flight cold connect.
        val owner = launch {
            leaseManager.acquire(vmLeaseTarget(1L, "alpha.example", 22, "alex", "/keys/a"))
        }
        runCurrent()
        assertEquals("the create flow owns the only in-flight connect", 1, connector.startedConnects)

        // Step 2: the user selects a session on the SAME host — it coalesces.
        val statuses = mutableListOf<TmuxSessionViewModel.ConnectionStatus>()
        val collector = launch { vm.connectionStatus.collect { statuses.add(it) } }
        vm.connect(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            passphrase = null,
            sessionName = sessionName,
        )
        runCurrent()
        assertEquals(
            "the selected session coalesced onto the in-flight connect (no 2nd dial yet)",
            1,
            connector.startedConnects,
        )

        // Step 3: the select supersedes the create flow — cancel the owner. The
        // lease wakes the selected session's acquire with the typed coalesced-cancel.
        owner.cancel()
        runCurrent()

        // Step 4: the selected session's OWN fresh re-dial should now be parked in
        // the gate — release it and let the attach complete.
        assertEquals(
            "the selected session re-dialed its OWN fresh connect after the coalesced cancel",
            2,
            connector.startedConnects,
        )
        connector.releaseRedial()
        advanceUntilIdle()
        collector.cancel()
        owner.cancel()
        return vm to statuses
    }

    @Test
    fun createThenSwitchExistingCoalescedCancelRedialsSelectedSessionToLive() = runVmTest {
        val (vm, statuses) = driveCreateThenSwitchCoalescedCancel("git-rds-export", "%1")

        val terminal = vm.connectionStatus.value
        assertTrue(
            "a coalesced-cancel supersede must NOT strand the selected session on Disconnected; got $terminal",
            terminal is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        assertTrue(
            "the reveal surface must reach Live (not a stranded Attaching…/Seeding spinner); got " +
                vm.revealState.value,
            vm.revealState.value is RevealState.Live,
        )
        assertFalse(
            "no Disconnected/Failed band may surface on a coalesced-cancel supersede; saw: " +
                statuses.joinToString { it::class.simpleName ?: "?" },
            statuses.any { it is TmuxSessionViewModel.ConnectionStatus.Failed },
        )
    }

    @Test
    fun createThenSwitchAnotherNewSessionSurvivorDoesNotStrand() = runVmTest {
        // Same coalesced-cancel mechanism, but the surviving selection is a
        // DIFFERENT (newly-created) session name on the same host.
        val (vm, statuses) = driveCreateThenSwitchCoalescedCancel("brand-new", "%9")

        assertTrue(
            "the surviving new session must reach Connected, got ${vm.connectionStatus.value}",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        assertTrue(vm.revealState.value is RevealState.Live)
        assertFalse(
            "no Failed band may surface for the survivor; saw: " +
                statuses.joinToString { it::class.simpleName ?: "?" },
            statuses.any { it is TmuxSessionViewModel.ConnectionStatus.Failed },
        )
    }

    @Test
    fun genuineUnreachableStillSurfacesTerminalErrorAndDoesNotRedialLoop() = runVmTest {
        // The distinction test: a GENUINE connect failure (host unreachable) must
        // NOT be treated as a coalesced-cancel — it surfaces the honest terminal
        // error (reveal + pill AGREE), and does NOT loop re-dialing.
        val connector = AlwaysFailsConnector()
        val registry = ActiveTmuxClients()
        val leaseManager = coalescingLeaseManager(connector)
        val vm = TestNewVm(registry, leaseManager)
        vm.setAutoReconnectDelaysForTest(listOf(0L))

        vm.connect(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            passphrase = null,
            sessionName = "work",
        )
        advanceUntilIdle()

        assertTrue(
            "a genuine unreachable must surface the honest Failed band, got ${vm.connectionStatus.value}",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Failed,
        )
        // The reveal must AGREE with the pill — an honest terminal error, never a
        // stranded Seeding spinner (the #1185 two-holder safety net covers this
        // path too).
        assertTrue(
            "the reveal must be a terminal Error, not a stranded Seeding spinner; got ${vm.revealState.value}",
            vm.revealState.value is RevealState.Error,
        )
        assertTrue(
            "a genuine unreachable must not endlessly re-dial (bounded); saw ${connector.connectCount} dials",
            connector.connectCount <= 2,
        )
    }

    @Test
    fun coalescedCancelRedialIsBoundedByTheCapThenSurfacesRetryableError() = runVmTest {
        // Item 3 (bounded re-dial cap): a coalesced-cancel that RECURS on every
        // re-dial (the re-dial's OWN connect also coalesces-and-cancels) must NOT
        // loop forever — the per-chain budget COALESCED_CANCEL_REDIAL_MAX (2) stops
        // it, then the honest terminal error surfaces WITH a working Retry.
        //
        // Deterministic recurrence: the first hop is a REAL coalesce (a parked owner
        // the test cancels). Each subsequent re-dial becomes its own owner and its
        // connector dial returns the SAME typed [SshLeaseConnectCoalescedCancelException]
        // the lease would raise if that re-dial had itself coalesced onto another
        // just-cancelled owner — i.e. the consumer sees the exact cause it sees in a
        // real recurring supersede, so [failConnectAttempt]'s cap branch is driven
        // faithfully. The connector only SUCCEEDS at a dial index the cap can never
        // reach, so a broken cap is observable (Connected + more dials) rather than an
        // infinite hang.
        val connector = RecurringCoalescedCancelConnector(succeedAtIndex = 5)
        val registry = ActiveTmuxClients()
        val leaseManager = coalescingLeaseManager(connector)
        val vm = TestNewVm(registry, leaseManager)
        vm.setAutoReconnectDelaysForTest(listOf(0L))

        // Step 1: the create flow owns the in-flight cold connect (dial #0, parked).
        val owner = launch {
            leaseManager.acquire(vmLeaseTarget(1L, "alpha.example", 22, "alex", "/keys/a"))
        }
        runCurrent()
        assertEquals("the create flow owns the only in-flight connect", 1, connector.startedConnects)

        // Step 2: the user selects a session on the same host — it coalesces.
        val statuses = mutableListOf<TmuxSessionViewModel.ConnectionStatus>()
        val collector = launch { vm.connectionStatus.collect { statuses.add(it) } }
        vm.connect(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            passphrase = null,
            sessionName = "git-rds-export",
        )
        runCurrent()
        assertEquals("the selected session coalesced (no 2nd dial yet)", 1, connector.startedConnects)

        // Step 3: supersede — cancel the owner. The selected session re-dials, and
        // every re-dial's OWN connect coalesced-cancels again, recurring until the
        // cap stops it. No parked dial after the owner, so advancing is safe.
        owner.cancel()
        advanceUntilIdle()
        collector.cancel()

        // The re-dial fired exactly COALESCED_CANCEL_REDIAL_MAX (2) times: owner
        // dial + 2 bounded re-dials = 3 total. It did NOT loop forever (never reached
        // the connector's succeed-at index 5).
        assertEquals(
            "coalesced-cancel re-dial must be bounded at the cap (owner dial + 2 re-dials)",
            3,
            connector.startedConnects,
        )
        // After the budget is exhausted the honest terminal error surfaces...
        assertTrue(
            "a pathological recurring coalesced-cancel must surface the honest terminal error " +
                "once the cap is hit, got ${vm.connectionStatus.value}",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Failed,
        )
        // ...with a WORKING Retry — the reconnect target is preserved so the user is
        // never dead-ended on a Disconnected pill with no Reconnect affordance.
        assertTrue(
            "the terminal coalesced-cancel fallback must leave a working Retry (canReconnect)",
            vm.canReconnect.value,
        )
        // ...and the reveal surface AGREES (terminal Error, never a stranded spinner).
        assertTrue(
            "the reveal must be a terminal Error in lockstep with the pill, got ${vm.revealState.value}",
            vm.revealState.value is RevealState.Error,
        )
    }

    @Test
    fun coalescedCancelDoesNotRedialASessionTheUserNavigatedAwayFrom() = runVmTest {
        // Item 3 (navigated-away guard — the spike's flagged top risk): a
        // coalesced-cancel that arrives AFTER the user navigated to a different
        // session identity must NOT re-dial the abandoned session. The guard is
        // [shouldRedialAfterCoalescedCancel]'s `latestConnectIntent` +
        // `sameSessionIdentity` clause; this drives the REAL intent-setter (`connect`)
        // and asserts the decision both ways.
        val connector = AlwaysFailsConnector()
        val registry = ActiveTmuxClients()
        val leaseManager = coalescingLeaseManager(connector)
        val vm = TestNewVm(registry, leaseManager)
        vm.setAutoReconnectDelaysForTest(listOf(0L))

        val targetA = TmuxSessionViewModel.ConnectionTarget(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            passphrase = null,
            sessionName = "git-rds-export",
            startDirectory = null,
        )
        val targetB = targetA.copy(sessionName = "other-session")

        // The user is on session A: intent=A, so a coalesced-cancel on A re-dials A.
        // (Assert BEFORE advancing — intent is set synchronously at connect() entry.)
        vm.connect(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            passphrase = null,
            sessionName = "git-rds-export",
        )
        assertTrue(
            "still on A → a coalesced-cancel must re-dial A",
            vm.shouldRedialAfterCoalescedCancel(true, targetA),
        )

        // The user navigates away to a DIFFERENT session B (a newer intent supersedes
        // A).
        vm.connect(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            passphrase = null,
            sessionName = "other-session",
        )
        assertFalse(
            "a coalesced-cancel for the ABANDONED session A must NOT re-dial it after " +
                "the user navigated away (re-dialing an abandoned session is the flagged risk)",
            vm.shouldRedialAfterCoalescedCancel(true, targetA),
        )
        assertTrue(
            "the now-selected session B still re-dials on its own coalesced-cancel",
            vm.shouldRedialAfterCoalescedCancel(true, targetB),
        )

        // Drain the failing connect jobs cleanly (genuine failure → terminal, no loop).
        advanceUntilIdle()
    }

    @Test
    fun rapidSwitchAToBToACoalescedCancelDoesNotStrandTheSelectedSession() = runVmTest {
        // Item 5 (explicit class-coverage AC / G9): rapid A→B→A switching on one host
        // where the final selection coalesces onto a cancelled in-flight connect must
        // land the SELECTED session Live — none of the churned sessions may strand.
        val freshSession = AlwaysConnectedSession("fresh")
        val connector = GatedConnector(ownerSlot = null, redialSlot = freshSession)
        val registry = ActiveTmuxClients()
        val leaseManager = coalescingLeaseManager(connector)
        val vm = TestNewVm(registry, leaseManager)
        vm.setAutoReconnectDelaysForTest(listOf(0L))

        // A tolerant factory: hand back a durable per-session pane fixture for
        // WHATEVER session the attach asks for (the A→B→A churn advances the reveal
        // generation several times so the reconcile loop can iterate; a durable stream
        // keeps the fake from running dry mid-reconcile). Record the attaches so the
        // test can assert the FINAL selection (session-a) is the one that lands.
        val attachedSessions = mutableListOf<String>()
        vm.setTmuxClientFactoryForTest { _, requested, _ ->
            attachedSessions.add(requested)
            durableFakeClientForSession(requested, "%1")
        }

        // The create flow owns the in-flight cold connect.
        val owner = launch {
            leaseManager.acquire(vmLeaseTarget(1L, "alpha.example", 22, "alex", "/keys/a"))
        }
        runCurrent()
        assertEquals("the create flow owns the only in-flight connect", 1, connector.startedConnects)

        val statuses = mutableListOf<TmuxSessionViewModel.ConnectionStatus>()
        val collector = launch { vm.connectionStatus.collect { statuses.add(it) } }

        // Rapid A → B → A on the same host — each selection coalesces onto the one
        // in-flight owner (no new dial).
        vm.connect(1L, "alpha", "alpha.example", 22, "alex", "/keys/a", null, "session-a")
        runCurrent()
        vm.connect(1L, "alpha", "alpha.example", 22, "alex", "/keys/a", null, "session-b")
        runCurrent()
        vm.connect(1L, "alpha", "alpha.example", 22, "alex", "/keys/a", null, "session-a")
        runCurrent()
        assertEquals(
            "every rapid switch coalesced onto the one in-flight connect",
            1,
            connector.startedConnects,
        )

        // Supersede the create flow — cancel the owner. The FINAL selection (A) wakes
        // with the typed coalesced-cancel and re-dials its own fresh connect.
        owner.cancel()
        runCurrent()
        assertEquals(
            "the final selection re-dialed its OWN fresh connect after the coalesced cancel",
            2,
            connector.startedConnects,
        )
        connector.releaseRedial()
        advanceUntilIdle()
        collector.cancel()
        owner.cancel()

        assertTrue(
            "rapid A→B→A that cancels an in-flight connect must NOT strand the selected " +
                "session; it recovers to Connected, got ${vm.connectionStatus.value}",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        assertEquals(
            "the FINALLY-selected session (session-a) is the one that lands active",
            "session-a",
            attachedSessions.lastOrNull(),
        )
        assertTrue(
            "the reveal surface must reach Live (not a stranded Attaching…/Seeding spinner); got " +
                vm.revealState.value,
            vm.revealState.value is RevealState.Live,
        )
        assertFalse(
            "no Disconnected/Failed band may surface for the selected session; saw: " +
                statuses.joinToString { it::class.simpleName ?: "?" },
            statuses.any { it is TmuxSessionViewModel.ConnectionStatus.Failed },
        )
    }

    // ---- fakes ----

    private fun fakeClientForSession(sessionName: String, paneId: String): FakeTmuxClient =
        FakeTmuxClient().apply {
            responses.addLast(
                com.pocketshell.core.tmux.CommandResponse(
                    number = 1L,
                    output = listOf("$paneId\t@0\t\$0\t$sessionName\t$sessionName\t0"),
                    isError = false,
                ),
            )
            capturePaneResponses.addLast(
                com.pocketshell.core.tmux.CommandResponse(
                    number = 2L,
                    output = listOf("$sessionName ready"),
                    isError = false,
                ),
            )
            cursorQueryResponses.addLast(
                com.pocketshell.core.tmux.CommandResponse(number = 3L, output = listOf("0,0"), isError = false),
            )
        }

    /**
     * Like [fakeClientForSession] but seeds a DURABLE stream of identical pane
     * fixtures so the attach's list-panes/reconcile loop can iterate repeatedly (as it
     * does after the A→B→A reveal-generation churn) without the fake running dry — a
     * single response would exhaust and surface as a spurious pane-wait timeout that
     * masks the real "does the selected session recover" question under test.
     */
    private fun durableFakeClientForSession(sessionName: String, paneId: String): FakeTmuxClient =
        FakeTmuxClient().apply {
            repeat(16) {
                responses.addLast(
                    com.pocketshell.core.tmux.CommandResponse(
                        number = 1L,
                        output = listOf("$paneId\t@0\t\$0\t$sessionName\t$sessionName\t0"),
                        isError = false,
                    ),
                )
                capturePaneResponses.addLast(
                    com.pocketshell.core.tmux.CommandResponse(
                        number = 2L,
                        output = listOf("$sessionName ready"),
                        isError = false,
                    ),
                )
                cursorQueryResponses.addLast(
                    com.pocketshell.core.tmux.CommandResponse(number = 3L, output = listOf("0,0"), isError = false),
                )
            }
        }

    /**
     * Connector where the FIRST connect (the create-flow owner) parks forever
     * until cancelled, and the SECOND (the selected session's own re-dial) parks
     * until [releaseRedial] resolves it with [redialSlot].
     */
    private class GatedConnector(
        private val ownerSlot: SshSession?,
        private val redialSlot: SshSession,
    ) : SshLeaseConnector {
        var startedConnects: Int = 0
            private set
        private val gates: ArrayDeque<CompletableDeferred<Unit>> = ArrayDeque()

        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
            val index = startedConnects
            startedConnects += 1
            val gate = CompletableDeferred<Unit>()
            gates.addLast(gate)
            try {
                gate.await()
            } finally {
                gates.remove(gate)
            }
            val session = if (index == 0) ownerSlot else redialSlot
            return session?.let { Result.success(it) }
                ?: Result.failure(java.io.IOException("connect $index failed"))
        }

        fun releaseRedial() {
            val gate = gates.firstOrNull() ?: error("no in-flight re-dial to release")
            gate.complete(Unit)
        }
    }

    /** Every dial fails immediately — a genuinely unreachable host. */
    private class AlwaysFailsConnector : SshLeaseConnector {
        var connectCount: Int = 0
            private set

        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
            connectCount += 1
            return Result.failure(com.pocketshell.core.ssh.SshException("connection refused"))
        }
    }

    /**
     * Connector for the bounded-cap test: dial #0 PARKS (the create-flow owner the
     * test cancels to trigger the first REAL coalesced-cancel). Every subsequent dial
     * (the selected session's own re-dials, each now its own owner) returns the SAME
     * typed [SshLeaseConnectCoalescedCancelException] the lease raises on a recurring
     * supersede — so the consumer sees a coalesced-cancel on EVERY re-dial and the
     * per-chain cap is what must stop it. A dial only SUCCEEDS at [succeedAtIndex],
     * chosen beyond what the cap can reach, so a broken/removed cap is observable
     * (Connected + more dials) instead of hanging forever.
     */
    private class RecurringCoalescedCancelConnector(
        private val succeedAtIndex: Int,
    ) : SshLeaseConnector {
        var startedConnects: Int = 0
            private set
        private val ownerGate = CompletableDeferred<Unit>()
        private val successSession = AlwaysConnectedSession("recovered")

        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
            val index = startedConnects
            startedConnects += 1
            if (index == 0) {
                // The create-flow owner parks until cancelled (the real supersede).
                ownerGate.await()
                error("owner dial is only ever cancelled, never released")
            }
            if (index >= succeedAtIndex) {
                return Result.success(successSession)
            }
            // The re-dial's own connect coalesced-cancelled again — hand the consumer
            // the exact typed failure a recurring supersede produces.
            return Result.failure(SshLeaseConnectCoalescedCancelException(target.leaseKey))
        }
    }

    private class AlwaysConnectedSession(val id: String) : SshSession {
        @Volatile
        var closed: Boolean = false

        override val isConnected: Boolean get() = !closed

        override suspend fun exec(command: String): ExecResult =
            ExecResult(stdout = "", stderr = "", exitCode = 0)

        override fun tail(path: String, onLine: (String) -> Unit): Job =
            Job().apply { complete() }

        override fun tail(path: String, fromLineExclusive: Long, onLine: (String) -> Unit): Job =
            Job().apply { complete() }

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
}
