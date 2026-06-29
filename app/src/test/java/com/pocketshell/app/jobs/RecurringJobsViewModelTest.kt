package com.pocketshell.app.jobs

import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshLease
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseKey
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.math.max
import kotlin.system.measureTimeMillis

@OptIn(ExperimentalCoroutinesApi::class)
class RecurringJobsViewModelTest {

    // Issue #708: the SshLeaseManager's bounded cold connect (#687) runs under a
    // real `Dispatchers.IO` + wall clock by default. Share ONE virtual-clock
    // scheduler between `runTest`, `Dispatchers.Main`, and the lease so
    // `advanceUntilIdle()` actually drives the bounded dial instead of leaving it
    // stranded on a real thread.
    private val scheduler = TestCoroutineScheduler()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher(scheduler))

    private val remoteSource = PocketshellJobsRemoteSource(RecurringJobsParser())

    // Issue #1085 (F2): the off-Main hand-off must let onCleared return within a
    // small budget; on base the wedged release parks the calling thread ~1.8s.
    private val NAV_TEARDOWN_PARK_BUDGET_MS = 300L

    @Test
    fun loadConnectsAndFetchesJobsForSession() = runTest(scheduler) {
        val session = FakeSshSession(
            pathAware("pocketshell jobs list --session 'agent main'") to listOutput(id = 7, sessionName = "agent main"),
        )
        val connector = CountingConnector(session)
        val viewModel = newViewModel(session, connector)

        viewModel.load(
            hostId = 1L,
            hostName = "Lab",
            hostname = "lab.local",
            port = 22,
            username = "alexey",
            keyPath = "/tmp/key",
            passphrase = null,
            sessionName = "agent main",
        )
        advanceUntilIdle()

        assertEquals("Lab", viewModel.state.value.hostName)
        assertEquals("agent main", viewModel.state.value.sessionName)
        assertEquals(listOf(7), viewModel.state.value.jobs.map { it.id })
        assertFalse(viewModel.state.value.loading)
        assertEquals(null, viewModel.state.value.error)
        assertEquals(listOf(pathAware("pocketshell jobs list --session 'agent main'")), session.recorded)
        // Issue #699: the jobs screen borrowed ONE warm lease for the fetch —
        // a single underlying handshake, no fresh per-action dial.
        assertEquals(1, connector.connectCount)
    }

    @Test
    fun jobsActionsReuseTheSameWarmLeaseAcrossActions() = runTest(scheduler) {
        // Issue #699: load + add + remove must all ride ONE warm transport
        // (one acquire/connect), not a fresh handshake per action.
        val session = FakeSshSession(
            pathAware("pocketshell jobs list --session 'codex'") to listOf(
                listOutput(id = 1, sessionName = "codex"),
                listOutput(id = 1, sessionName = "codex"),
                listOutput(id = 1, sessionName = "codex"),
            ),
            pathAware("pocketshell jobs add 'codex' --every '5m' --message 'continue'") to ExecResult("Created job 1\n", "", 0),
            pathAware("pocketshell jobs remove 1") to ExecResult("Removed job 1\n", "", 0),
        )
        val connector = CountingConnector(session)
        val viewModel = newViewModel(session, connector)

        viewModel.load(
            hostId = 9L,
            hostName = "Lab",
            hostname = "lab.local",
            port = 22,
            username = "alexey",
            keyPath = "/tmp/key",
            passphrase = null,
            sessionName = "codex",
        )
        advanceUntilIdle()
        viewModel.add(RecurringJobDraft(sessionName = "codex", every = "5m", message = "continue"))
        advanceUntilIdle()
        viewModel.remove(1)
        advanceUntilIdle()

        // Three actions, exactly ONE underlying SSH handshake.
        assertEquals(1, connector.connectCount)
        // The warm session is still alive (reused), never closed mid-screen.
        assertFalse(session.closed)
    }

    @Test
    fun refreshMapsMissingPocketshellToErrorState() = runTest(scheduler) {
        val session = FakeSshSession(
            pathAware("pocketshell jobs list --session 'codex'") to ExecResult("", "pocketshell: not found", 127),
        )
        val viewModel = newViewModel(session)

        viewModel.load(
            hostId = 1L,
            hostName = "Lab",
            hostname = "lab.local",
            port = 22,
            username = "alexey",
            keyPath = "/tmp/key",
            passphrase = null,
            sessionName = "codex",
        )
        advanceUntilIdle()

        assertEquals("pocketshell is not installed on this host", viewModel.state.value.error)
        assertFalse(viewModel.state.value.loading)
    }

    @Test
    fun refreshMapsUnavailableJobsDaemonToTargetedSetupMessage() = runTest(scheduler) {
        val session = FakeSshSession(
            pathAware("pocketshell jobs list --session 'codex'") to ExecResult(
                "",
                "pocketshell jobs daemon is not running",
                2,
            ),
        )
        val viewModel = newViewModel(session)

        viewModel.load(
            hostId = 1L,
            hostName = "Lab",
            hostname = "lab.local",
            port = 22,
            username = "alexey",
            keyPath = "/tmp/key",
            passphrase = null,
            sessionName = "codex",
        )
        advanceUntilIdle()

        val error = viewModel.state.value.error.orEmpty()
        assertEquals(true, error.contains("Recurring jobs need the optional pocketshell jobs daemon"))
        assertEquals(true, error.contains("systemctl --user enable --now pocketshell-jobs.service"))
        assertFalse(viewModel.state.value.loading)
    }

    @Test
    fun addRefreshesTheBoundSessionAfterSuccess() = runTest(scheduler) {
        val session = FakeSshSession(
            pathAware("pocketshell jobs list --session 'codex'") to listOf(
                listOutput(id = 1, sessionName = "codex"),
                listOutput(id = 2, sessionName = "codex"),
            ),
            pathAware("pocketshell jobs add 'codex' --every '5m' --message 'continue'") to ExecResult("Created job 2\n", "", 0),
        )
        val viewModel = newViewModel(session)

        viewModel.load(
            hostId = 1L,
            hostName = "Lab",
            hostname = "lab.local",
            port = 22,
            username = "alexey",
            keyPath = "/tmp/key",
            passphrase = null,
            sessionName = "codex",
        )
        advanceUntilIdle()
        viewModel.add(RecurringJobDraft(sessionName = "codex", every = "5m", message = "continue"))
        advanceUntilIdle()

        assertEquals(listOf(2), viewModel.state.value.jobs.map { it.id })
        assertEquals(
            listOf(
                pathAware("pocketshell jobs list --session 'codex'"),
                pathAware("pocketshell jobs add 'codex' --every '5m' --message 'continue'"),
                pathAware("pocketshell jobs list --session 'codex'"),
            ),
            session.recorded,
        )
    }

    @Test
    fun staleRefreshResultCannotOverwriteNewlyBoundSession() = runTest(scheduler) {
        val oldListStarted = CompletableDeferred<Unit>()
        val releaseOldList = CompletableDeferred<Unit>()
        val oldSession = FakeSshSession(
            pathAware("pocketshell jobs list --session 'alpha'") to ScriptedExec(
                result = listOutput(id = 1, sessionName = "alpha"),
                started = oldListStarted,
                release = releaseOldList,
                waitNonCancellable = true,
            ),
        )
        val newSession = FakeSshSession(
            pathAware("pocketshell jobs list --session 'bravo'") to listOutput(id = 2, sessionName = "bravo"),
        )
        val connector = DirectLeaseConnector(
            "old.local" to oldSession,
            "new.local" to newSession,
        )
        val viewModel = RecurringJobsViewModel(
            remoteSource = remoteSource,
            connector = connector,
        )

        viewModel.load(
            hostId = 1L,
            hostName = "Old",
            hostname = "old.local",
            port = 22,
            username = "alexey",
            keyPath = "/tmp/key-old",
            passphrase = null,
            sessionName = "alpha",
        )
        advanceUntilIdle()
        oldListStarted.await()

        viewModel.load(
            hostId = 2L,
            hostName = "New",
            hostname = "new.local",
            port = 22,
            username = "alexey",
            keyPath = "/tmp/key-new",
            passphrase = null,
            sessionName = "bravo",
        )
        advanceUntilIdle()

        assertEquals("New", viewModel.state.value.hostName)
        assertEquals("bravo", viewModel.state.value.sessionName)
        assertEquals(listOf(2), viewModel.state.value.jobs.map { it.id })

        releaseOldList.complete(Unit)
        advanceUntilIdle()

        assertEquals("New", viewModel.state.value.hostName)
        assertEquals("bravo", viewModel.state.value.sessionName)
        assertEquals(listOf(2), viewModel.state.value.jobs.map { it.id })
    }

    @Test
    fun jobsCommandsAreSerializedAcrossRefreshAndMutation() = runTest(scheduler) {
        val refreshStarted = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val session = FakeSshSession(
            pathAware("pocketshell jobs list --session 'codex'") to listOf(
                listOutput(id = 1, sessionName = "codex"),
                ScriptedExec(
                    result = listOutput(id = 1, sessionName = "codex"),
                    started = refreshStarted,
                    release = releaseRefresh,
                    waitNonCancellable = true,
                ),
                listOutput(id = 2, sessionName = "codex"),
            ),
            pathAware("pocketshell jobs add 'codex' --every '5m' --message 'continue'") to
                ExecResult("Created job 2\n", "", 0),
        )
        val viewModel = newViewModel(session)

        viewModel.load(
            hostId = 1L,
            hostName = "Lab",
            hostname = "lab.local",
            port = 22,
            username = "alexey",
            keyPath = "/tmp/key",
            passphrase = null,
            sessionName = "codex",
        )
        advanceUntilIdle()

        viewModel.refresh()
        advanceUntilIdle()
        refreshStarted.await()

        viewModel.add(RecurringJobDraft(sessionName = "codex", every = "5m", message = "continue"))
        advanceUntilIdle()

        releaseRefresh.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, session.maxConcurrentExecs)
        assertEquals(listOf(2), viewModel.state.value.jobs.map { it.id })
    }

    /**
     * Issue #1085 (F2) reproduce-first. This screen's VM is `hiltViewModel()`
     * nav-scoped, so `onCleared` fires on an ordinary foreground back/
     * navigate-away ON the Main thread. On a half-open transport (post
     * WIFI↔cellular handoff) `lease.release()` does not return fast — it rides
     * its full ceiling — so releasing it synchronously parks the Main thread for
     * multiple seconds (the maintainer's "keeps freezing" backing out of a
     * screen).
     *
     * RED on base: `onCleared` ran
     * `runBlocking(Dispatchers.IO){ withTimeoutOrNull(2000){ release() } }`, so
     * `clearForTest()` parks the CALLING (Main) thread for the whole wedge
     * (~1.8s) — the elapsed assertion fails.
     *
     * GREEN with the fix: the release is handed to the application-scoped
     * [teardownScope], so `onCleared` returns immediately (elapsed well under
     * the budget) AND the release still runs to COMPLETION off-Main (the latch
     * fires) — no leak, no Main park.
     */
    @Test
    fun onClearedReleasesWarmLeaseOffMainAndNeverParksTheCallingThread() = runTest(scheduler) {
        val released = CountDownLatch(1)
        // A wedge that EXCEEDS the 2s release ceiling so the bound is provably
        // defeated on base: a non-suspending blocking park (mirroring a wedged
        // half-open socket close) a coroutine timeout cannot interrupt.
        val wedgeMs = 1_800L
        val session = FakeSshSession(
            pathAware("pocketshell jobs list --session 'codex'") to listOutput(id = 1, sessionName = "codex"),
        )
        val teardownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val viewModel = RecurringJobsViewModel(
            remoteSource = remoteSource,
            connector = WedgedLeaseConnector(session, wedgeMs) { released.countDown() },
        )
        viewModel.setTeardownScopeForTest(teardownScope)
        try {
            viewModel.load(
                hostId = 1L,
                hostName = "Lab",
                hostname = "lab.local",
                port = 22,
                username = "alexey",
                keyPath = "/tmp/key",
                passphrase = null,
                sessionName = "codex",
            )
            advanceUntilIdle()
            // The warm lease is now held — the next teardown must release it.

            val elapsedMs = measureTimeMillis { viewModel.clearForTest() }

            assertTrue(
                "onCleared parked the calling (Main) thread for ${elapsedMs}ms releasing a " +
                    "wedged lease — the #1085 F2 ANR. The release must be handed to the " +
                    "application-scoped teardown scope so onCleared returns immediately.",
                elapsedMs < NAV_TEARDOWN_PARK_BUDGET_MS,
            )
            assertTrue(
                "the warm-lease refcount-- must still run to COMPLETION off the Main thread " +
                    "(no leak) — the app-scoped release never fired.",
                released.await(8, TimeUnit.SECONDS),
            )
        } finally {
            // Issue #1085 (F2) test isolation: JOIN the launched teardown
            // coroutine to FULL completion before the test ends, then cancel.
            // The latch fires mid-coroutine (right after the wedge's
            // `Thread.sleep`), so awaiting it alone can let the real-IO
            // coroutine finish unwinding after `runTest`/`resetMain`. Joining
            // the scope's children makes tear-down fully quiescent so nothing
            // touches Main concurrently with the next class's `setMain`.
            runBlocking {
                withTimeoutOrNull(8_000) {
                    teardownScope.coroutineContext.job.children.forEach { it.join() }
                }
            }
            teardownScope.cancel()
        }
    }

    /**
     * A connector whose lease release WEDGES — its [SshLease.release] blocks the
     * calling thread for [wedgeMs] (a non-suspending park, like a half-open
     * socket close that a coroutine timeout cannot interrupt).
     */
    private class WedgedLeaseConnector(
        private val session: SshSession,
        private val wedgeMs: Long,
        private val onReleased: () -> Unit,
    ) : RecurringJobsViewModel.RecurringJobsSshConnector {
        override suspend fun acquire(target: RecurringJobsViewModel.Target): Result<SshLease> =
            Result.success(
                wedgedLeaseForTest(target.toLeaseTarget().leaseKey, session, wedgeMs, onReleased),
            )
    }

    /**
     * Build a VM whose connector borrows from a REAL [SshLeaseManager] wrapping
     * [connector]. This exercises the actual #699 warm-lease path: acquire →
     * reuse → release, so `connector.connectCount` proves the screen rides ONE
     * underlying handshake instead of dialing per action.
     */
    private fun newViewModel(
        session: SshSession,
        connector: SshLeaseConnector = CountingConnector(session),
    ): RecurringJobsViewModel =
        RecurringJobsViewModel(
            remoteSource = remoteSource,
            connector = RecurringJobsViewModel.DefaultRecurringJobsSshConnector(
                SshLeaseManager(
                    connector = connector,
                    // Issue #708: keep the bounded cold connect (#687) on the
                    // shared virtual clock so it resolves under `runTest`.
                    connectTimeoutContext = StandardTestDispatcher(scheduler),
                    nowMillis = { scheduler.currentTime },
                ),
            ),
        )

    /**
     * A lease connector that counts how many times the pool actually dialed a
     * fresh transport. The #699 acceptance is exactly one connect across the
     * whole screen session.
     */
    private class CountingConnector(
        private val session: SshSession,
    ) : SshLeaseConnector {
        var connectCount: Int = 0

        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
            connectCount += 1
            return Result.success(session)
        }
    }

    private class DirectLeaseConnector(
        vararg sessions: Pair<String, SshSession>,
    ) : RecurringJobsViewModel.RecurringJobsSshConnector {
        private val sessionsByHost = sessions.toMap()

        override suspend fun acquire(target: RecurringJobsViewModel.Target): Result<SshLease> {
            val session = sessionsByHost[target.hostname]
                ?: return Result.failure(IllegalArgumentException("No session for ${target.hostname}"))
            return Result.success(fakeLease(target.toLeaseTarget().leaseKey, session))
        }

        private fun fakeLease(key: SshLeaseKey, session: SshSession): SshLease {
            val releaseAction = { _: SshLeaseKey, _: Long, _: kotlin.coroutines.Continuation<Unit> -> Unit }
            val constructor = SshLease::class.java.declaredConstructors.single()
            return constructor.newInstance(key, session, false, 0L, releaseAction) as SshLease
        }
    }

    private data class ScriptedExec(
        val result: ExecResult,
        val started: CompletableDeferred<Unit>? = null,
        val release: CompletableDeferred<Unit>? = null,
        val waitNonCancellable: Boolean = false,
    )

    private class FakeSshSession(
        vararg scripted: Pair<String, Any>,
    ) : SshSession {
        private val canned: Map<String, ArrayDeque<ScriptedExec>> =
            scripted.associate { (command, value) ->
                val results = when (value) {
                    is ExecResult -> listOf(ScriptedExec(value))
                    is ScriptedExec -> listOf(value)
                    is List<*> -> value.map {
                        when (it) {
                            is ExecResult -> ScriptedExec(it)
                            is ScriptedExec -> it
                            else -> error("Unsupported fake result type: ${it?.javaClass?.simpleName}")
                        }
                    }
                    else -> error("Unsupported fake result type: ${value::class.java.simpleName}")
                }
                command to ArrayDeque(results)
            }

        val recorded = mutableListOf<String>()
        var closed: Boolean = false
        var maxConcurrentExecs: Int = 0
            private set
        private var activeExecs: Int = 0

        override val isConnected: Boolean
            get() = !closed

        override suspend fun exec(command: String): ExecResult {
            recorded += command
            val next = canned[command]?.removeFirstOrNull() ?: return ExecResult("", "missing stub for $command", 127)
            activeExecs += 1
            maxConcurrentExecs = max(maxConcurrentExecs, activeExecs)
            next.started?.complete(Unit)
            try {
                val release = next.release
                if (release != null) {
                    if (next.waitNonCancellable) {
                        withContext(NonCancellable) { release.await() }
                    } else {
                        release.await()
                    }
                }
                return next.result
            } finally {
                activeExecs -= 1
            }
        }

        override fun tail(path: String, onLine: (String) -> Unit): Job =
            error("tail not used")

        override fun openLocalPortForward(remoteHost: String, remotePort: Int, localPort: Int): SshPortForward =
            error("port forward not used")

        override fun startShell(): SshShell = error("shell not used")

        override suspend fun uploadFile(file: java.io.File, remotePath: String): String =
            error("uploadFile not used in this test")

        override suspend fun uploadStream(
            input: java.io.InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("uploadStream not used in this test")

        override fun close() {
            closed = true
        }
    }

    private fun listOutput(id: Int, sessionName: String): ExecResult =
        ExecResult(
            stdout = """
                ID  ENABLED  SESSION       EVERY  DELAY  SOURCE  NEXT RUN             DETAIL
                $id   yes      ${sessionName.padEnd(14)}15m    200    inline  2026-04-03 00:30:00 continue work
            """.trimIndent(),
            stderr = "",
            exitCode = 0,
        )

    // Delegate to the production wrapper so these stubs/assertions track the
    // real PATH-robust invocation (issue #484).
    private fun pathAware(command: String): String =
        PocketshellJobsRemoteSource.pathAwareCommand(command)
}

/**
 * Issue #1085 (F2): build an [SshLease] (internal constructor) whose
 * `releaseAction` blocks the calling thread for [wedgeMs] then signals
 * [onReleased]. Mirrors the reflection-built `fakeLease` already used in this
 * suite; the blocking `Thread.sleep` reproduces a wedged transport close whose
 * bounded ceiling is defeated because a coroutine cancel cannot unpark a parked
 * thread. Top-level so the static nested [WedgedLeaseConnector] can call it.
 */
private fun wedgedLeaseForTest(
    key: SshLeaseKey,
    session: SshSession,
    wedgeMs: Long,
    onReleased: () -> Unit,
): SshLease {
    val releaseAction = { _: SshLeaseKey, _: Long, _: Continuation<Unit> ->
        Thread.sleep(wedgeMs)
        onReleased()
        Unit
    }
    val constructor = SshLease::class.java.declaredConstructors.single()
    return constructor.newInstance(key, session, false, 0L, releaseAction) as SshLease
}
