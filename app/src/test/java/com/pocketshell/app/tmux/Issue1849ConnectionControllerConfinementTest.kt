package com.pocketshell.app.tmux

import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.core.agents.AgentDetection
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.tmux.CommandResponse
import com.pocketshell.core.tmux.TmuxClientFactory
import java.io.File
import java.io.InputStream
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #1849 — THE class-covering confinement guard for [com.pocketshell.core.connection.ConnectionController].
 *
 * ## What broke, three times
 *
 * `TmuxSessionAgentSendTest`'s reconnect stress guards kept failing at
 * `ConnectionController.kt:1093` — the #1234 single-mutator confinement guard —
 * once every couple of CI runs. #1615 and #1617 each chased the ONE
 * real-dispatcher root they happened to trip over (`agentTailScope` /
 * `factoryScope` / the off-Main teardown scope) and pinned it. A third signature
 * appeared anyway, because the CLASS was never covered.
 *
 * ## The class
 *
 * `TmuxSessionViewModelTestBase` installs an **`UnconfinedTestDispatcher` as
 * `Dispatchers.Main`** (`isDispatchNeeded == false`). Under that harness, ANY
 * VM-owned coroutine that resolves on a REAL dispatcher resumes its Main-rooted
 * awaiter **inline on that foreign thread** — and the awaiter then carries the
 * whole connect path with it:
 *
 * ```
 * <owned job completes on a real dispatcher>
 *   -> closeCurrentConnectionAndJoin()'s cancelAndJoin resumes INLINE off-thread
 *   -> connect -> runConnect -> acquireLeaseForTmux
 *   -> SshLeaseManager.acquire -> emitStateLocked -> _stateEvents.tryEmit
 *   -> ConnectionEffectDriver.collectTransportEvents  (also unconfined Main)
 *   -> ConnectionController.submit()   ON A REAL BACKGROUND THREAD
 * ```
 *
 * Racing the test thread's own scheduler pump, two threads then sit inside a
 * mutator of a reducer documented as non-thread-safe, and the guard fires.
 *
 * The specific #1849 root was `TerminalSurfaceState()`'s no-arg constructor
 * pinning the terminal external-producer feed job to real `Dispatchers.IO`; the
 * VM stores it in `paneProducerJobs` and `closeCurrentConnectionAndJoin`
 * `cancelAndJoin()`s it at the head of EVERY connect/reconnect. It is now pinned
 * in the base like every other owned seam.
 *
 * ## Why this test covers the class rather than that instance
 *
 * It does not assert anything about the terminal producer. It drives the real
 * production journey (live panes with real producers -> passive drop ->
 * send-triggered reconnect, i.e. `closeCurrentConnectionAndJoin` -> `runConnect`
 * -> lease acquire) and asserts the **invariant**: every observable step of the
 * connect path, and every `ConnectionController` state transition it produces,
 * is seen on the ONE confining thread. Any future owned root that resolves on a
 * real dispatcher re-opens the escape and fails this test on its first run —
 * deterministically, with no CPU contention required.
 *
 * ## Why the observation is deterministic (and why it is not the guard itself)
 *
 * The #1234 guard fires only on the rare OVERLAP: two threads inside a mutator at
 * the same instant. The ESCAPE underneath it is not rare at all. Measured on
 * `origin/main` (`633318ea`) over an 800-iteration instrumented soak of the two
 * stress variants: **760 of 763** `SshLeaseManager.emitStateLocked` calls ran on a
 * real `DefaultDispatcher-worker`, while only 2 reached a mutator (the lease's
 * `Connecting` state is `mapNotNull`-ed away by `SshLeaseTransportPort`; only the
 * `Connected`/`Closed` EDGES reach `submit`). So asserting on the confinement of
 * the path — not on the overlap — converts a ~1-in-2-CI-runs heisenbug into a
 * deterministic red.
 *
 * The observers below collect on an `UnconfinedTestDispatcher`, so each collector
 * body runs on the thread that PRODUCED the value; recording
 * `Thread.currentThread()` there is a faithful reading of the producing thread.
 *
 * ## This is a TEST-harness invariant, not a production claim
 *
 * Production `viewModelScope` is `Dispatchers.Main.immediate`, whose
 * `isDispatchNeeded()` is false ONLY when already on the Android main looper
 * thread; the same join re-posts to Main there, so the reducer is confined and
 * was never at risk. This test guards the harness so the guard keeps telling the
 * truth instead of flaking.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class Issue1849ConnectionControllerConfinementTest : TmuxSessionViewModelTestBase() {

    private class QueueLeaseConnector(
        private vararg val sessions: FakeSshSession,
    ) : SshLeaseConnector {
        var connectCount: Int = 0
            private set

        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
            val next = sessions.getOrNull(connectCount)
                ?: error("unexpected lease connect $connectCount for ${target.leaseKey}")
            connectCount += 1
            return Result.success(next)
        }
    }

    private class FakeSshSession : SshSession {
        @Volatile
        private var closed: Boolean = false

        override val isConnected: Boolean
            get() = !closed

        override suspend fun exec(command: String): ExecResult =
            ExecResult(stdout = "", stderr = "", exitCode = 0)

        override fun tail(path: String, onLine: (String) -> Unit): Job = Job()

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = throw NotImplementedError("not needed")

        override fun startShell(): SshShell = throw NotImplementedError("not needed")

        override suspend fun uploadFile(file: File, remotePath: String): String = remotePath

        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = remotePath

        override fun close() {
            closed = true
        }
    }

    private fun FakeTmuxClient.withSinglePane(
        sessionName: String,
        paneId: String,
    ): FakeTmuxClient = apply {
        responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf("$paneId\t@0\t\$0\t$sessionName\t$sessionName\t0"),
                isError = false,
            ),
        )
        capturePaneResponses.addLast(
            CommandResponse(number = 2L, output = listOf("$sessionName ready"), isError = false),
        )
        cursorQueryResponses.addLast(
            CommandResponse(number = 3L, output = listOf("0,0"), isError = false),
        )
    }

    /**
     * The load-bearing assertion of #1849: the send-triggered reconnect journey —
     * the exact journey whose stress guard reported the guard trip — must drive
     * every lease-state emission and every controller transition on the single
     * confining thread.
     *
     * Red on `origin/main` (`633318ea`): `SshLeaseConnectionState.Connecting` is
     * emitted on a `DefaultDispatcher-worker`, because the terminal producer job
     * `cancelAndJoin`ed at the head of the reconnect completes on real
     * `Dispatchers.IO` and the unconfined Main resumes the connect path there.
     */
    @Test
    fun reconnectJourneyKeepsEveryConnectionControllerMutationOnTheConfiningThread() =
        runTest(scheduler) {
            val confining = Thread.currentThread()
            val leaseEmitThreads = Collections.synchronizedList(mutableListOf<Thread>())
            val controllerThreads = Collections.synchronizedList(mutableListOf<Thread>())
            // Collect UNCONFINED so each observer body runs on the thread that
            // produced the value — the reading is of the producer, not of us.
            val observerScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(scheduler))

            val scenarioScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
            val registry = ActiveTmuxClients()
            val connector = QueueLeaseConnector(FakeSshSession())
            val leaseManager = testLeaseManager(
                connector = connector,
                scope = scenarioScope,
                idleTtlMillis = 0L,
            )
            observerScope.launch {
                leaseManager.stateEvents.collect { leaseEmitThreads += Thread.currentThread() }
            }

            val reconnectClient = FakeTmuxClient().withSinglePane("work", "%0").apply {
                defaultCaptureResponse = CommandResponse(
                    number = 0L,
                    output = listOf("> work ready"),
                    isError = false,
                )
                onCommandSent = { command ->
                    if (command == "send-keys -l -t %0 -- 'confinement probe'") {
                        defaultCaptureResponse = CommandResponse(
                            number = 0L,
                            output = listOf("> confinement probe"),
                            isError = false,
                        )
                    }
                }
            }
            val vm = newVm(
                registry = registry,
                sshLeaseManager = leaseManager,
                tmuxClientFactory = TmuxClientFactory(scenarioScope),
            )
            // The VM projects the controller's state into `connectionStatus`
            // SYNCHRONOUSLY after every `ConnectionController.submit`
            // (`projectStatusFromController()`), on the submitting thread — so an
            // unconfined observer here reads the mutator's own thread. (A direct
            // `ConnectionManager.stateFlow` seam would have to be added to the
            // byte-ratcheted `TmuxSessionViewModel`; this public projection is the
            // same signal without growing that file.)
            observerScope.launch {
                vm.connectionStatus.collect { controllerThreads += Thread.currentThread() }
            }
            vm.setTeardownScopeForTest(scenarioScope)
            vm.setPassiveDisconnectRecoveryForTest(graceMs = 0L, silentReattachTimeoutMs = 1L)
            vm.setTmuxClientFactoryForTest { _, sessionName, _ ->
                assertEquals("work", sessionName)
                reconnectClient
            }
            val deadClient = FakeTmuxClient()
            vm.replaceClientForTest(
                hostId = 7L,
                hostName = "alpha",
                host = "alpha.example",
                port = 22,
                user = "alex",
                keyPath = "/keys/a",
                sessionName = "work",
                client = deadClient,
            )
            // A real pane row => a real TerminalSurfaceState external producer job
            // in `paneProducerJobs`, which is what `closeCurrentConnectionAndJoin`
            // joins at the head of the reconnect below. Without a live pane the
            // journey would never exercise the join fence at all.
            vm.applyParsedPanesForTest(
                listOf(
                    TmuxSessionViewModel.ParsedPane(
                        paneId = "%0",
                        windowId = "@0",
                        sessionId = "\$0",
                        title = "claude",
                        paneIndex = 0,
                        sessionName = "work",
                    ),
                ),
            )
            vm.startAgentConversationForTest(
                "%0",
                AgentDetection(
                    agent = AgentKind.ClaudeCode,
                    sourcePath = "/home/u/.claude/sessions/abc.jsonl",
                    sessionId = "abc",
                    confidence = AgentDetection.Confidence.ProcessConfirmed,
                ),
            )
            assertTrue(
                "precondition: the journey must own a live pane producer, otherwise " +
                    "closeCurrentConnectionAndJoin has no owned job to join and the " +
                    "escape under test cannot occur",
                vm.paneProducerActiveForTest("%0"),
            )

            deadClient.disconnectedSignal.value = true
            runCurrent()

            // Drive the send from an UNCONFINED Main coroutine, which is the
            // PRODUCTION shape: on-device the composer's send runs on
            // `Dispatchers.Main.immediate`, and the reconnect it triggers runs in
            // `viewModelScope`. Launching it from the `runTest` body instead would
            // put it on a StandardTestDispatcher, whose every resume is
            // re-dispatched back onto the scheduler — that would mask the escape
            // and make this a vacuous green (F2: reproduce the reported state, not
            // a convenient one).
            val sendDriver = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(scheduler))
            val send = sendDriver.async { vm.sendToAgentPaneResult("%0", "confinement probe") }
            awaitSendCompletion(send)
            val result = send.await()

            assertTrue(
                "precondition: the send must actually reconnect (that is the journey " +
                    "under test) — failure=${result.exceptionOrNull()}",
                result.isSuccess,
            )
            assertEquals(
                "precondition: the journey must have driven a real lease dial",
                1,
                connector.connectCount,
            )
            assertTrue(
                "precondition: the lease must have published transport state to observe",
                leaseEmitThreads.isNotEmpty(),
            )
            assertTrue(
                "precondition: the controller-projected status must have moved to observe",
                controllerThreads.isNotEmpty(),
            )

            val offThreadLease = leaseEmitThreads.toList().filter { it !== confining }
            val offThreadController = controllerThreads.toList().filter { it !== confining }

            assertEquals(
                "issue #1849: the connect path escaped its confining dispatcher — " +
                    "${offThreadLease.size} of ${leaseEmitThreads.size} lease transport-state " +
                    "emissions ran on a foreign thread " +
                    "(${offThreadLease.map { it.name }.distinct()}), so every " +
                    "ConnectionController mutator downstream of them can race the test " +
                    "thread and trip the #1234 single-mutator guard. Some VM-owned " +
                    "coroutine is resolving on a REAL dispatcher: pin it to the shared " +
                    "test scheduler (Shape A of the de-flake convention) — do not relax " +
                    "the guard.",
                emptyList<String>(),
                offThreadLease.map { it.name },
            )
            assertEquals(
                "issue #1849: a ConnectionController state transition was observed on a " +
                    "foreign thread (${offThreadController.map { it.name }.distinct()}). " +
                    "The reducer is non-thread-safe and must be driven from one " +
                    "serializing dispatcher (#1234, item 6).",
                emptyList<String>(),
                offThreadController.map { it.name },
            )

            sendDriver.cancel()
            observerScope.cancel()
            vm.clearForTest()
            runCurrent()
            scenarioScope.cancel()
            runCurrent()
        }

    /**
     * Class coverage beyond the reconnect journey: the SAME invariant on the
     * plain teardown fence. `closeCurrentConnectionAndJoin` is the single place
     * every connect/reconnect/switch joins the VM's owned per-pane jobs, so it is
     * the choke point where an unpinned owned root hands the caller to a real
     * dispatcher. Driving it directly proves the fence itself returns confined,
     * independently of which journey reached it.
     */
    @Test
    fun teardownJoinFenceReturnsOnTheConfiningThread() = runTest(scheduler) {
        val confining = Thread.currentThread()
        val resumeThreads = Collections.synchronizedList(mutableListOf<Thread>())
        val scenarioScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        val vm = newVm(tmuxClientFactory = TmuxClientFactory(scenarioScope))
        vm.setTeardownScopeForTest(scenarioScope)
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
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%0",
                    windowId = "@0",
                    sessionId = "\$0",
                    title = "claude",
                    paneIndex = 0,
                    sessionName = "work",
                ),
            ),
        )
        assertTrue(
            "precondition: a live pane producer must exist for the fence to join",
            vm.paneProducerActiveForTest("%0"),
        )

        // Drive the fence from an UNCONFINED Main coroutine, exactly as production
        // does (`connect` is a `viewModelScope.launch`). A `runTest`-body call
        // would be re-dispatched back onto the scheduler and could never observe
        // the escape — that would be a vacuous green.
        val driver = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(scheduler))
        val fence = driver.launch {
            vm.closeCurrentConnectionAndJoinForTest()
            resumeThreads += Thread.currentThread()
        }
        var ticks = 0
        while (!fence.isCompleted && ticks < 2_000) {
            runCurrent()
            if (!fence.isCompleted) {
                advanceTimeBy(10L)
                Thread.sleep(1)
            }
            ticks += 1
        }
        assertTrue("teardown join fence did not settle within the bounded pump", fence.isCompleted)

        val offThread = resumeThreads.toList().filter { it !== confining }
        assertEquals(
            "issue #1849: closeCurrentConnectionAndJoin resumed its Main-rooted caller " +
                "on a foreign thread (${offThread.map { it.name }.distinct()}). One of the " +
                "owned jobs it cancelAndJoins is resolving on a REAL dispatcher; under the " +
                "harness's UnconfinedTestDispatcher Main that carries the whole connect path " +
                "— and the ConnectionController mutators on it — off-confinement.",
            emptyList<String>(),
            offThread.map { it.name },
        )

        driver.cancel()
        vm.clearForTest()
        runCurrent()
        scenarioScope.cancel()
        runCurrent()
    }

    /**
     * Bounded wall-clock settle pump (Shape B of the de-flake convention). The
     * load-bearing assertion is the pump's own exit condition; it never weakens
     * the confinement assertions above.
     */
    private fun TestScope.awaitSendCompletion(send: Job) {
        repeat(2_000) {
            if (send.isCompleted) return
            runCurrent()
            if (!send.isCompleted) {
                advanceTimeBy(10L)
                Thread.sleep(1)
            }
        }
        assertTrue(
            "reconnect send did not complete within 2,000 settle ticks",
            send.isCompleted,
        )
    }
}
