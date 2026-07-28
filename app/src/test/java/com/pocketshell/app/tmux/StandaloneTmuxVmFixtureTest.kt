package com.pocketshell.app.tmux

import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.tmux.CommandResponse
import com.pocketshell.core.tmux.TmuxClientFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Issue #1765 — the shared standalone-fixture seam's own proof.
 *
 * Each test here pins one clause of the acceptance criteria for
 * [StandaloneTmuxVmFixture], the helper that replaced 28 bespoke
 * `runTest { setMain(...) ... finally { clear; advanceUntilIdle; resetMain } }`
 * teardowns.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class StandaloneTmuxVmFixtureTest {

    // ---------------------------------------------------------------------
    // Ordering: clear -> cancel -> drain, all while Main is still installed.
    // ---------------------------------------------------------------------

    @Test
    fun clearsTrackedVmsInReverseRegistrationOrderBeforeCancelling() {
        val events = mutableListOf<String>()
        val fixture = StandaloneTmuxVmFixture(timeoutMs = 1_000L)
        fixture.runVmTest {
            fixture.trackForTest(
                clear = { events += "clear-first" },
                cancelOwnScopes = { events += "cancel-first" },
                activeOwnScopeChildCount = { 0 },
            )
            fixture.trackForTest(
                clear = { events += "clear-second" },
                cancelOwnScopes = { events += "cancel-second" },
                activeOwnScopeChildCount = { 0 },
            )
        }

        // Reverse-order clear (the second VM constructed is torn down first),
        // then cancellation for every VM in registration order. The trailing
        // cancel pair is the bounded drain's first (and only needed) sweep.
        assertEquals(
            listOf(
                "clear-second",
                "clear-first",
                "cancel-first",
                "cancel-second",
                "cancel-first",
                "cancel-second",
            ),
            events,
        )
    }

    @Test
    fun vmDrainRunsWhileMainIsStillInstalledAndMainIsResetOnlyAfterwards() {
        val ranOnInstalledMainDuringDrain = AtomicBoolean(false)
        val fixture = StandaloneTmuxVmFixture(timeoutMs = 1_000L)
        fixture.runVmTest {
            fixture.trackForTest(
                clear = {},
                cancelOwnScopes = {
                    // If Main had already been reset this would post to the real
                    // Android main looper and the fixture scheduler would never
                    // run it.
                    CoroutineScope(Dispatchers.Main).launch {
                        ranOnInstalledMainDuringDrain.set(true)
                    }
                    fixture.scheduler.runCurrent()
                },
                activeOwnScopeChildCount = { 0 },
            )
        }

        assertTrue(
            "the VM drain must run while Dispatchers.Main is still the fixture dispatcher",
            ranOnInstalledMainDuringDrain.get(),
        )
        assertEquals(
            MainResetPhaseOracle.Phase.MAIN_RESET_COMPLETE,
            fixture.phaseOracleForTest().phase,
        )

        // And Main really is reset once runVmTest returns: work posted to
        // Dispatchers.Main no longer lands on the fixture's scheduler.
        val ranAfterReset = AtomicBoolean(false)
        CoroutineScope(Dispatchers.Main).launch { ranAfterReset.set(true) }
        fixture.scheduler.runCurrent()
        assertFalse(
            "Dispatchers.Main must be reset once runVmTest returns",
            ranAfterReset.get(),
        )
    }

    // ---------------------------------------------------------------------
    // The premature-reset oracle — deterministic RED, no dispatch required.
    // ---------------------------------------------------------------------

    @Test
    fun resetMainBeforeTheDrainCompletesIsADeterministicHardFailure() {
        val oracle = MainResetPhaseOracle()
        oracle.beginDrain()
        var reset = false

        val failure = runCatching { oracle.resetMain { reset = true } }.exceptionOrNull()

        assertTrue(
            "resetting Main before the drain completes must hard-fail, got $failure",
            failure is IllegalStateException,
        )
        assertTrue(
            "the failure must name the race it prevents: ${failure?.message}",
            failure?.message.orEmpty().contains("TestMainDispatcher:72"),
        )
        assertFalse("Dispatchers.resetMain() must not have run", reset)
        assertEquals(MainResetPhaseOracle.Phase.MAIN_INSTALLED, oracle.phase)
    }

    @Test
    fun drainMustBeginWhileMainIsStillInstalled() {
        val oracle = MainResetPhaseOracle()
        oracle.drainComplete()
        oracle.resetMain { }

        val failure = runCatching { oracle.beginDrain() }.exceptionOrNull()

        assertTrue(
            "draining after Main was reset must hard-fail, got $failure",
            failure is IllegalStateException,
        )
    }

    @Test
    fun resetMainRunsExactlyOnceAfterTheDrainCompletes() {
        val oracle = MainResetPhaseOracle()
        var resets = 0
        oracle.beginDrain()
        oracle.drainComplete()

        oracle.resetMain { resets++ }

        assertEquals(1, resets)
        assertEquals(MainResetPhaseOracle.Phase.MAIN_RESET_COMPLETE, oracle.phase)
    }

    // ---------------------------------------------------------------------
    // The drain is load-bearing: a non-quiescing VM child is a hard RED.
    // ---------------------------------------------------------------------

    @Test
    fun aVmChildThatNeverQuiescesHardFailsInsteadOfLeakingPastMainReset() {
        val fixture = StandaloneTmuxVmFixture(timeoutMs = 50L)

        val failure = runCatching {
            fixture.runVmTest {
                fixture.trackForTest(
                    clear = {},
                    cancelOwnScopes = {},
                    // Models a VM launch that hopped to a REAL dispatcher and is
                    // still unwinding: cancel() returned but the child has not.
                    activeOwnScopeChildCount = { 1 },
                )
            }
        }.exceptionOrNull()

        assertTrue(
            "an un-drained VM child must be a deterministic AssertionError, got $failure",
            failure is AssertionError,
        )
        assertTrue(
            "the failure must explain the resetMain race: ${failure?.message}",
            failure?.message.orEmpty().contains("TestMainDispatcher:72"),
        )
        // Even on that failure the fixture still resets global Main, so the
        // failure does not cascade into every following test class.
        assertEquals(
            MainResetPhaseOracle.Phase.MAIN_RESET_COMPLETE,
            fixture.phaseOracleForTest().phase,
        )
    }

    // ---------------------------------------------------------------------
    // Owned real-IO roots are cancelled AND joined, not merely cancelled.
    // ---------------------------------------------------------------------

    @Test
    fun ownedFactoryAndLeaseRootsAreCancelledAndJoined() {
        val fixture = StandaloneTmuxVmFixture(timeoutMs = 5_000L)
        val factoryStarted = AtomicBoolean(false)
        val leaseStarted = AtomicBoolean(false)

        fixture.runVmTest {
            fixture.factoryScope.launch {
                factoryStarted.set(true)
                awaitCancellation()
            }
            fixture.leaseScope.launch {
                leaseStarted.set(true)
                awaitCancellation()
            }
            while (!factoryStarted.get() || !leaseStarted.get()) {
                Thread.yield()
            }
        }

        assertTrue(
            "the fixture-owned factoryScope root must be COMPLETED, not merely cancellation-requested",
            fixture.factoryScope.coroutineContext[Job]!!.isCompleted,
        )
        assertTrue(
            "the fixture-owned leaseScope root must be COMPLETED, not merely cancellation-requested",
            fixture.leaseScope.coroutineContext[Job]!!.isCompleted,
        )
    }

    @Test
    fun extraOwnedRootsRegisteredWithTrackRootAreAlsoJoined() {
        val fixture = StandaloneTmuxVmFixture(timeoutMs = 5_000L)
        val extra = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val started = AtomicBoolean(false)

        assertSame(extra, fixture.trackRoot("extra", extra))
        fixture.runVmTest {
            extra.launch {
                started.set(true)
                awaitCancellation()
            }
            while (!started.get()) {
                Thread.yield()
            }
        }

        assertTrue(extra.coroutineContext[Job]!!.isCompleted)
    }

    // ---------------------------------------------------------------------
    // Body cleanups, failure precedence, and clock neutrality.
    // ---------------------------------------------------------------------

    @Test
    fun bodyCleanupsRunBeforeVmClearInReverseRegistrationOrder() {
        val events = mutableListOf<String>()
        val fixture = StandaloneTmuxVmFixture(timeoutMs = 1_000L)

        fixture.runVmTest {
            fixture.onTeardown { events += "cleanup-first" }
            fixture.onTeardown { events += "cleanup-second" }
            fixture.trackForTest(
                clear = { events += "clear" },
                cancelOwnScopes = {},
                activeOwnScopeChildCount = { 0 },
            )
        }

        assertEquals(listOf("cleanup-second", "cleanup-first", "clear"), events)
    }

    @Test
    fun theBodyFailureStaysPrimaryAndTeardownFailuresAreSuppressed() {
        val fixture = StandaloneTmuxVmFixture(timeoutMs = 50L)
        val bodyFailure = IllegalArgumentException("the assertion the test actually proved")

        val thrown = runCatching {
            fixture.runVmTest {
                fixture.onTeardown { error("cleanup blew up") }
                fixture.trackForTest(
                    clear = {},
                    cancelOwnScopes = {},
                    activeOwnScopeChildCount = { 1 },
                )
                throw bodyFailure
            }
        }.exceptionOrNull()

        assertSame(
            "the body failure must stay PRIMARY so the real defect is what the report shows",
            bodyFailure,
            thrown,
        )
        val suppressed = thrown!!.suppressed.map { it::class.java.simpleName to it.message }
        assertTrue(
            "the cleanup failure must be attached, not lost: $suppressed",
            thrown.suppressed.any { it.message.orEmpty().contains("cleanup blew up") },
        )
        assertTrue(
            "the un-drained VM failure must be attached, not lost: $suppressed",
            thrown.suppressed.any { it is AssertionError },
        )
    }

    @Test
    fun teardownNeverAdvancesTheVirtualClock() {
        val fixture = StandaloneTmuxVmFixture(timeoutMs = 1_000L)
        var timeAtBodyEnd = -1L
        var timeAtClear = -1L
        var timeAtDrain = -1L

        fixture.runVmTest {
            // A pending delayed task the drain must NOT run: only a virtual-time
            // advance would reach it, and that is exactly what would trip the
            // #793 re-seed watchdog in a migrated fixture.
            backgroundScope.launch { kotlinx.coroutines.delay(60_000L) }
            fixture.factoryScope.launch { awaitCancellation() }
            fixture.trackForTest(
                clear = { timeAtClear = fixture.scheduler.currentTime },
                cancelOwnScopes = { timeAtDrain = fixture.scheduler.currentTime },
                activeOwnScopeChildCount = { 0 },
            )
            timeAtBodyEnd = fixture.scheduler.currentTime
        }

        assertEquals(
            "clearing the tracked VMs must not advance the virtual clock",
            timeAtBodyEnd,
            timeAtClear,
        )
        assertEquals(
            "the #1765 drain is runCurrent-only: advancing virtual time would trip " +
                "the #793 re-seed watchdog and perturb every body timing oracle",
            timeAtBodyEnd,
            timeAtDrain,
        )
    }

    @Test
    fun aSecondRunVmTestOnTheSameFixtureIsRejected() {
        val fixture = StandaloneTmuxVmFixture(timeoutMs = 1_000L)
        fixture.runVmTest { }

        try {
            fixture.runVmTest { }
            fail("a StandaloneTmuxVmFixture must drive exactly one test method")
        } catch (expected: IllegalStateException) {
            assertTrue(
                expected.message.orEmpty().contains("exactly one test method"),
            )
        }
    }

    // ---------------------------------------------------------------------
    // End-to-end with a REAL TmuxSessionViewModel — the property the 28
    // migrated fixtures depend on.
    // ---------------------------------------------------------------------

    @Test
    fun aRealConnectedViewModelIsFullyQuiescentByTheTimeMainIsReset() {
        val fixture = StandaloneTmuxVmFixture()
        lateinit var vm: TmuxSessionViewModel

        fixture.runVmTest {
            val client = FakeTmuxClient().apply {
                responses.addLast(
                    CommandResponse(
                        number = 1L,
                        output = listOf("%1\t@0\t\$0\twork\twork\t0"),
                        isError = false,
                    ),
                )
                capturePaneResponses.addLast(
                    CommandResponse(number = 2L, output = listOf("work ready"), isError = false),
                )
                cursorQueryResponses.addLast(
                    CommandResponse(number = 3L, output = listOf("0,0"), isError = false),
                )
            }
            vm = fixture.track(
                TmuxSessionViewModel(
                    tmuxClientFactory = TmuxClientFactory(fixture.factoryScope),
                    activeTmuxClients = ActiveTmuxClients(),
                    runtimeCache = TmuxSessionRuntimeCache(),
                    sshLeaseManager = testLeaseManager(
                        connector = SingleLiveSessionConnector(),
                        scope = this,
                        idleTtlMillis = 60_000L,
                    ),
                    sessionLifecycleSignals = null,
                ),
            ).also {
                it.setSeedIoDispatcherForTest(StandardTestDispatcher(testScheduler))
                it.setStaleRenderWatchdogAutoArmEnabledForTest(false)
                it.setConnectedBlankWatchdogAutoArmEnabledForTest(false)
                it.setTmuxClientFactoryForTest { _, _, _ -> client }
            }
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
                "precondition: the VM actually connected and seeded a pane",
                vm.panes.value.any { it.paneId == "%1" },
            )
        }

        assertEquals(
            "no TmuxSessionViewModel-owned coroutine may survive the Main reset (#1355/#1765)",
            0,
            vm.activeOwnScopeChildCountForTest(),
        )
        assertTrue(
            "the fixture-owned factory root must be joined, not merely cancelled",
            fixture.factoryScope.coroutineContext[Job]!!.isCompleted,
        )
        assertEquals(1, fixture.trackedVmCountForTest())
    }

    // ------------------------------------------------------------------ Fakes

    private class SingleLiveSessionConnector : SshLeaseConnector {
        private val session = AlwaysLiveSession()
        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> =
            Result.success(session)
    }

    private class AlwaysLiveSession : SshSession {
        @Volatile
        private var closed = false

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
