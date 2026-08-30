package com.pocketshell.app.projects

import com.pocketshell.app.hosts.MainDispatcherOwnershipRule
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.app.hosts.MainDispatcherStragglers
import com.pocketshell.app.repos.ReposJsonParser
import com.pocketshell.app.repos.ReposRemoteSource
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Issue #2413 regression cover — a coroutine that escapes one test onto
 * `Dispatchers.Main` must never redden an unrelated sibling `runTest`.
 *
 * ## The reported failure
 *
 * The scheduled full suite reddened `JVM unit tests (Release)` with
 *
 * ```
 * TreeRemoteSourceTest > upsertTree_buildsRequestAndReturnsTrueOnOk FAILED
 *     kotlinx.coroutines.test.UncaughtExceptionsBeforeTest at TreeRemoteSourceTest.kt:133
 * ```
 *
 * The named test is a pure stubbed-stdout parse assertion with no concurrency;
 * it was simply the next `runTest` to enter the worker JVM. The CI artifact's
 * suppressed cause named the real mechanism:
 *
 * ```
 * IllegalStateException: Dispatchers.Main was accessed when the platform
 *   dispatcher was absent and the test dispatcher was unset
 *   … TestMainDispatcher.isDispatchNeeded
 *   … DispatchedCoroutine.afterResume            <- a withContext(…) returning
 *   … LimitedDispatcher$Worker.run               <- from Dispatchers.IO
 * Suppressed: DiagnosticCoroutineContextException:
 *   [CoroutineId(728), DispatchedCoroutine{Completed}, Dispatchers.IO]
 * ```
 *
 * i.e. a `Dispatchers.Main`-scoped coroutine hopped to the real IO dispatcher,
 * the owning test finished and `Dispatchers.resetMain()` ran, and the hop then
 * completed and tried to resume onto a Main that no longer existed.
 *
 * ## The real production path this test drives
 *
 * `RepoBrowserViewModel.refresh()` is exactly that shape —
 * `viewModelScope.launch { withContext(Dispatchers.IO) { runLoad(creds) } }` —
 * and `RepoBrowserViewModelTest` was the last `runTest`-using class to run
 * before `TreeRemoteSourceTest` in the failing worker. This test drives the
 * *real* view model through the *real* [MainDispatcherRule] lifecycle, holding
 * the exec inside the IO hop until after teardown so the straggler is
 * deterministic rather than a race.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Issue2413MainDispatcherStragglerLeakTest {

    @get:Rule
    val ownership = MainDispatcherOwnershipRule()

    @Test
    fun aStragglerFromAFinishedTestNeverPoisonsAnUnrelatedRunTest() {
        // A full-suite worker has always run some `runTest` before this point,
        // which is what arms kotlinx-coroutines-test's process-global
        // ExceptionCollector. Without it the collector ignores the escape and
        // the leak only prints to stderr, so this line is load-bearing for
        // reproducing the reported CI failure rather than a weaker cousin.
        runTest { }

        val session = leakOneStragglerThroughTheRule().session

        // Non-vacuity, asserted with an oracle that does NOT depend on the fix:
        // the production IO hop really ran and really returned, so a resume onto
        // Main really was attempted after teardown.
        assertTrue(
            "the repo-browser load never executed its remote enumeration, so nothing " +
                "could have resumed onto Main and this test would prove nothing",
            session.execCount > 0,
        )

        // The reported symptom: the very next unrelated `runTest` inherits the
        // escaped exception as UncaughtExceptionsBeforeTest. It must not.
        runTest { }
    }

    @Test
    fun aStragglerIsReportedAgainstTheTestThatLeakedIt() {
        val stragglers = leakOneStragglerThroughTheRule().stragglers

        assertEquals(
            "exactly one post-teardown Main dispatch was leaked: $stragglers",
            1,
            stragglers.size,
        )
        assertEquals(
            "the straggler must be attributed to the test that leaked it, " +
                "not to whichever sibling runs next: $stragglers",
            LEAKY_TEST,
            stragglers.single().owner.substringBefore('('),
        )
        val report = runCatching {
            MainDispatcherStragglers.record(
                owner = stragglers.single().owner,
                context = kotlin.coroutines.EmptyCoroutineContext,
                task = Runnable { },
            )
            MainDispatcherStragglers.failIfAnyRecorded("regression check")
        }.exceptionOrNull()
        assertTrue(
            "a leaked straggler must hard-fail with attribution, not warn: $report",
            report is AssertionError && report.message?.contains(LEAKY_TEST) == true,
        )
    }

    /**
     * Runs one [MainDispatcherRule]-guarded statement that starts the real
     * `RepoBrowserViewModel` load and leaves it parked inside its
     * `withContext(Dispatchers.IO)` hop, then releases the hop after teardown.
     *
     * @return the stragglers recorded for that statement, drained so they cannot
     *   leak into a sibling test, plus the fake session that proves the real
     *   production path ran.
     */
    private fun leakOneStragglerThroughTheRule(): LeakOutcome {
        MainDispatcherStragglers.drain()
        val enteredIoHop = CountDownLatch(1)
        val releaseIoHop = CountDownLatch(1)
        val session = ParkedSession(enteredIoHop, releaseIoHop)
        val leaseScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            MainDispatcherRule(UnconfinedTestDispatcher(TestCoroutineScheduler())).apply(
                object : Statement() {
                    override fun evaluate() {
                        val viewModel = RepoBrowserViewModel(
                            reposRemoteSource = ReposRemoteSource(ReposJsonParser()),
                            sshLeaseManager = SshLeaseManager(
                                connector = SshLeaseConnector { Result.success(session) },
                                scope = leaseScope,
                                idleTtlMillis = 30_000L,
                                connectTimeoutContext = Dispatchers.Unconfined,
                            ),
                        )
                        viewModel.bind(CREDENTIALS)
                        check(enteredIoHop.await(10, TimeUnit.SECONDS)) {
                            "the repo-browser load never reached its Dispatchers.IO hop"
                        }
                    }
                },
                Description.createTestDescription(javaClass, LEAKY_TEST),
            ).evaluate()
        } finally {
            releaseIoHop.countDown()
        }

        // Deliberately NOT a hard `check`: on unfixed code no straggler is ever
        // recorded (the resume throws instead), and the load-bearing assertion
        // has to be the one that reddens — not a helper precondition.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var stragglers = MainDispatcherStragglers.drain()
        while (stragglers.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(5L)
            stragglers = MainDispatcherStragglers.drain()
        }
        leaseScope.cancel()
        return LeakOutcome(stragglers = stragglers, session = session)
    }

    private class LeakOutcome(
        val stragglers: List<MainDispatcherStragglers.Straggler>,
        val session: ParkedSession,
    )

    /** Blocks inside `exec` — i.e. inside the view model's IO hop — until released. */
    private class ParkedSession(
        private val entered: CountDownLatch,
        private val release: CountDownLatch,
    ) : SshSession {
        @Volatile
        var execCount: Int = 0
            private set

        override val isConnected: Boolean get() = true

        override suspend fun exec(command: String): ExecResult {
            entered.countDown()
            check(release.await(30, TimeUnit.SECONDS)) { "parked exec was never released" }
            execCount += 1
            return ExecResult("[]", "", 0)
        }

        override fun tail(path: String, onLine: (String) -> Unit): Job = error("unused")

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = error("unused")

        override fun startShell(): SshShell = error("unused")

        override suspend fun uploadFile(file: File, remotePath: String): String = error("unused")

        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("unused")

        override fun close() = Unit
    }

    private companion object {
        const val LEAKY_TEST = "issue2413-leaky-owner"

        val CREDENTIALS = RepoBrowserViewModel.SshCredentials(
            hostId = 42L,
            hostname = "docker",
            port = 2222,
            username = "testuser",
            keyPath = "/tmp/pocketshell-test-key",
            passphrase = null,
        )
    }
}
