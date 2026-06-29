package com.pocketshell.app.git

import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshLease
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseKey
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
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.system.measureTimeMillis

/**
 * Issue #1085 (F2) reproduce-first for the OTHER nav-scoped screen that copies
 * the same teardown shape ([RecurringJobsViewModel] is the sibling). The git
 * history VM is `hiltViewModel()` nav-scoped, so `onCleared` fires on an
 * ordinary foreground back/navigate-away ON the Main thread; on a half-open
 * transport the warm-lease release rides its full ceiling.
 *
 * RED on base: `onCleared` ran
 * `runBlocking(Dispatchers.IO){ withTimeoutOrNull(2000){ release() } }`, so
 * backing out of git history parked the Main thread for the whole wedge (~1.8s)
 * — a multi-second UI freeze.
 *
 * GREEN with the fix: the release is handed to the application-scoped teardown
 * scope, so `onCleared` returns immediately while the release still runs to
 * completion off-Main (no leak, no Main park).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GitHistoryViewModelTest {

    private val scheduler = TestCoroutineScheduler()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher(scheduler))

    // The off-Main hand-off must let onCleared return within a small budget; on
    // base the wedged release parks the calling thread ~1.8s.
    private val parkBudgetMs = 300L

    @Test
    fun onClearedReleasesWarmLeaseOffMainAndNeverParksTheCallingThread() = runTest(scheduler) {
        val released = CountDownLatch(1)
        // A wedge that EXCEEDS the 2s release ceiling so the bound is provably
        // defeated on base: a non-suspending blocking park (a wedged half-open
        // socket close) a coroutine timeout cannot interrupt.
        val wedgeMs = 1_800L
        val teardownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val viewModel = GitHistoryViewModel(
            sshLeaseManager = neverConnectingLeaseManager(),
            ioDispatcher = StandardTestDispatcher(scheduler),
        )
        viewModel.setTeardownScopeForTest(teardownScope)
        viewModel.setLeaseForTest(
            wedgedLease(
                key = SshLeaseKey(
                    host = "lab.local",
                    port = 22,
                    user = "alexey",
                    credentialId = "1:/tmp/key",
                    knownHostsId = "accept-all",
                ),
                session = NoopSshSession(),
                wedgeMs = wedgeMs,
                onReleased = { released.countDown() },
            ),
        )
        try {
            val elapsedMs = measureTimeMillis { viewModel.clearForTest() }

            assertTrue(
                "onCleared parked the calling (Main) thread for ${elapsedMs}ms releasing a " +
                    "wedged lease — the #1085 F2 ANR. The release must be handed to the " +
                    "application-scoped teardown scope so onCleared returns immediately.",
                elapsedMs < parkBudgetMs,
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

    private fun neverConnectingLeaseManager(): SshLeaseManager =
        SshLeaseManager(
            connector = SshLeaseConnector { target ->
                error("unexpected SSH lease connect for ${target.leaseKey}")
            },
            connectTimeoutContext = StandardTestDispatcher(scheduler),
            nowMillis = { scheduler.currentTime },
        )

    /**
     * Build an [SshLease] (internal constructor) whose `releaseAction` blocks
     * the calling thread for [wedgeMs] then signals [onReleased] — a wedged
     * transport close whose bounded ceiling is defeated because a coroutine
     * cancel cannot unpark a parked thread.
     */
    private fun wedgedLease(
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

    private class NoopSshSession : SshSession {
        override val isConnected: Boolean = true

        override suspend fun exec(command: String): ExecResult = ExecResult("", "", 0)

        override fun tail(path: String, onLine: (String) -> Unit): Job = error("tail not used")

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = error("port forward not used")

        override fun startShell(): SshShell = error("shell not used")

        override suspend fun uploadFile(file: File, remotePath: String): String =
            error("uploadFile not used")

        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("uploadStream not used")

        override fun close() = Unit
    }
}
