package com.pocketshell.app.fileviewer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.RemoteListing
import com.pocketshell.core.ssh.SshLease
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Issue #697 — the file VIEWER's open path must reuse the app-wide warm
 * [SshLeaseManager] transport (the same one the session / folder / tmux /
 * explorer screens hold) instead of dialing a fresh ~3-4s SSH handshake per
 * file open, and must NEVER `close()` the shared connection. JVM-level, no
 * emulator: a counting connector proves the handshake count and a fake session
 * proves the lease is never closed; the content cache proves an instant
 * re-open paint.
 *
 * ## Issue #2339 — why there is not a single wall-clock wait in this class
 *
 * This class carried the same defect shape that made its sibling
 * [FileViewerWorkspaceTest] flaky by construction and reddened the required
 * `Unit tests` check on `main`: a `runBlocking` body polled `state.value`
 * behind a hand-rolled `System.currentTimeMillis() + 10_000` deadline while
 * every hop the ViewModel owns ran on a REAL thread pool. Such a pump returns
 * the instant ONE observable lands, so a sibling effect of the same turn is
 * sampled at an arbitrary moment and the outcome depends on how busy the box
 * is. (Unlike [FileViewerWorkspaceTest] this class was never OBSERVED red — it
 * is the identical construction on the identical seams, fixed structurally
 * rather than waiting for it to bite.)
 *
 * The cure is the convention's Shape A (pinned seam), not a bigger budget:
 * EVERY hop the code under test owns now resolves on ONE
 * [kotlinx.coroutines.test.TestCoroutineScheduler] —
 *  - `Dispatchers.Main` via [MainDispatcherRule] (so does `viewModelScope`),
 *  - the ViewModel's blocking SSH hop via [FileViewerViewModel.ioDispatcher],
 *  - its cpu hop via [FileViewerViewModel.computeDispatcher],
 *  - the workspace RPC via [FileWorkspaceRemoteSource.remoteExecDispatcher],
 *  - the process-scoped workspace write queue,
 *  - the lease manager's owned dial, its abort, and its idle-close scope.
 *
 * [settle] then drains that scheduler with `runCurrent()`, which runs every
 * task due at the current instant (including the ones those tasks schedule) and
 * NEVER advances virtual time. That last property is load-bearing: advancing
 * the clock would fire the lease's idle TTL and `LeaseSessionExec`'s 45 s block
 * bound, turning a deliberate in-flight state into a spurious timeout.
 *
 * Consequence for anyone extending this class: there is nothing to wait for.
 * Call [settle] and assert. Do NOT reintroduce a `System.currentTimeMillis()`
 * deadline, a `Thread.sleep`, or a `delay` — with the seams pinned they would
 * measure nothing except how busy the box is. `scripts/check-test-validity.sh`
 * hard-fails a hand-rolled deadline pump in this directory (#2339).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class FileViewerLeaseTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val scheduler get() = mainDispatcherRule.dispatcher.scheduler

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    /**
     * Runs every task the code under test has scheduled at the current instant,
     * transitively, on the ONE shared test scheduler. Never advances the virtual
     * clock — see the class KDoc.
     */
    private fun settle() {
        scheduler.runCurrent()
    }

    @Test
    fun openingAFileBorrowsTheLeaseOnceAndNeverClosesIt() {
        val session = FakeFileSession()
        val connector = CountingConnector(session)
        val leaseManager = leaseManager(connector)
        val vm = viewModel(leaseManager)

        vm.bind(request("/srv/readme.txt"))
        val text = vm.state.awaitText()
        assertEquals("hello viewer", text.content)

        assertEquals("file open must dial exactly one handshake", 1, connector.connectCount)
        assertFalse("viewer must NOT close the shared warm transport", session.closed)

        // Clearing the VM releases the lease but never closes it (the pool keeps
        // it warm for its idle TTL so a sibling screen reuses it).
        vm.callOnCleared()
        settle()
        assertFalse("onCleared must release, not close, the warm transport", session.closed)
        leaseManager.close()
    }

    @Test
    fun openingOnAPrewarmedHostReusesTheTransportWithNoExtraHandshake() {
        val session = FakeFileSession()
        val connector = CountingConnector(session)
        val leaseManager = leaseManager(connector)

        // A sibling screen already holds a warm lease keyed identically to what
        // the viewer will use ("$hostId:$keyPath").
        val warmLease: SshLease = runPinned {
            leaseManager.acquire(
                request("/srv/a.txt").toLeaseTarget().toSshLeaseTarget(),
            ).getOrThrow()
        }
        val afterWarm = connector.connectCount
        assertEquals("pre-warm dials exactly one handshake", 1, afterWarm)

        val vm = viewModel(leaseManager)
        vm.bind(request("/srv/a.txt"))
        vm.state.awaitText()

        assertEquals(
            "viewer open must reuse the warm lease, not handshake again",
            afterWarm,
            connector.connectCount,
        )
        runPinned { warmLease.release() }
        leaseManager.close()
    }

    @Test
    fun reopeningAJustViewedTextFilePaintsTheCachedContentInstantly() {
        val session = FakeFileSession()
        val leaseManager = leaseManager(CountingConnector(session))
        val vm = viewModel(leaseManager)

        val first = request("/srv/readme.txt")
        vm.bind(first)
        vm.state.awaitText { it.displayPath.endsWith("/readme.txt") }

        // Navigate away to another file (so lastRequest != first), then back to
        // the first: the re-open must paint the cached text immediately (not
        // Loading) before the fresh fetch reconciles.
        vm.bind(request("/srv/other.txt"))
        vm.state.awaitText { it.displayPath.endsWith("/other.txt") }

        // #2339: the property under test is an ORDER — the content cache paints
        // FIRST, the live re-fetch replaces it after. Sampling `state.value`
        // right after `bind` proved nothing while the fetch ran on a real IO
        // thread, because whether the cache or the fetch was showing depended on
        // which thread got there first. Gate the reopen's download so the live
        // body provably CANNOT have landed at the moment we sample the paint.
        session.blockNextDownload = true
        vm.bind(first)
        settle()
        assertTrue(
            "the reopen's live fetch must be in flight and still gated before the paint is sampled",
            session.downloadStarted.isCompleted && !session.releaseDownload.isCompleted,
        )
        val immediate = vm.state.value
        assertTrue(
            "re-open must paint cached text instantly, was $immediate",
            immediate is FileViewerUiState.TextContent &&
                immediate.displayPath.endsWith("/readme.txt"),
        )

        session.releaseDownload.complete(Unit)
        settle()
        leaseManager.close()
    }

    // --- harness ------------------------------------------------------------

    /**
     * A lease manager whose OWN coroutine work is pinned to the shared test
     * scheduler: the idle-close job, the bounded dial, and the dial's abort.
     * The idle-close `delay(idleTtlMillis)` therefore never fires, because
     * [settle] deliberately never advances virtual time — which is what we
     * want, since a lease that closed itself mid-test would be another source
     * of run-to-run divergence.
     */
    private fun leaseManager(connector: SshLeaseConnector): SshLeaseManager =
        SshLeaseManager(
            connector = connector,
            scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler)),
            idleTtlMillis = 30_000L,
            // The owned dial and its abort must share the caller's clock: the
            // acquire wraps `dial.await()` in `withTimeoutOrNull`, so a dial on
            // a REAL dispatcher raced against a virtual-time bound (see the
            // SshLeaseManager constructor KDoc).
            connectTimeoutContext = StandardTestDispatcher(scheduler),
            abortTimeoutContext = StandardTestDispatcher(scheduler),
        )

    /** Shape A: every dispatcher/scope the ViewModel owns on the test scheduler. */
    private fun viewModel(leaseManager: SshLeaseManager): FileViewerViewModel {
        val workspaceSource = FileWorkspaceRemoteSource().also {
            it.remoteExecDispatcher = StandardTestDispatcher(scheduler)
        }
        return FileViewerViewModel(
            context,
            leaseManager,
            workspaceSource = workspaceSource,
        ).also {
            it.ioDispatcher = StandardTestDispatcher(scheduler)
            it.computeDispatcher = StandardTestDispatcher(scheduler)
            it.workspaceWriteScope =
                CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        }
    }

    /**
     * Drives a suspend call on the shared test scheduler and HARD-FAILS if it
     * has not completed once the scheduler is drained. The load-bearing part is
     * the completion assertion: an unfinished coroutine can only mean the work
     * escaped to a dispatcher this class does not own, which is precisely the
     * defect the class is built to exclude.
     */
    private fun <T> runPinned(block: suspend CoroutineScope.() -> T): T {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        val deferred = scope.async(block = block)
        settle()
        assertTrue(
            "a pinned coroutine did not complete on the test scheduler — " +
                "some owned hop escaped to a real dispatcher",
            deferred.isCompleted,
        )
        return deferred.getCompleted()
    }

    private fun StateFlow<FileViewerUiState>.awaitText(
        predicate: (FileViewerUiState.TextContent) -> Boolean = { true },
    ): FileViewerUiState.TextContent {
        settle()
        val current = value
        assertTrue(
            "viewer never reached the expected TextContent state; was $current",
            current is FileViewerUiState.TextContent && predicate(current),
        )
        return current as FileViewerUiState.TextContent
    }

    private fun request(path: String) = FileViewerViewModel.Request(
        hostId = 1L,
        hostname = "10.0.2.2",
        port = 2222,
        username = "tester",
        keyPath = "/tmp/key",
        trustedHostKeySha256 = "SHA256:test",
        passphrase = null,
        path = path,
        cwd = null,
    )

    private fun FileViewerViewModel.callOnCleared() {
        val m = androidx.lifecycle.ViewModel::class.java.getDeclaredMethod("onCleared")
        m.isAccessible = true
        m.invoke(this)
    }

    private class CountingConnector(
        private val session: FakeFileSession,
    ) : SshLeaseConnector {
        var connectCount: Int = 0

        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
            connectCount += 1
            return Result.success(session)
        }
    }

    /**
     * Minimal in-memory remote: `$HOME` probes to /home/tester, and any
     * `cat`/SFTP read of a file returns a small UTF-8 body keyed by basename so
     * different paths are distinguishable.
     */
    private class FakeFileSession : SshSession {
        var closed: Boolean = false
        val downloads = AtomicInteger(0)

        /**
         * #2339 — the deterministic seam that makes "the cache paints before the
         * live fetch lands" an ORDER the test controls rather than a race it
         * hopes to win. Set it before the bind whose download must be observed
         * mid-flight; the download then parks on [releaseDownload] until the
         * test completes it.
         */
        var blockNextDownload: Boolean = false
        val downloadStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val releaseDownload = kotlinx.coroutines.CompletableDeferred<Unit>()

        override val isConnected: Boolean
            get() = !closed

        override suspend fun exec(command: String): ExecResult {
            // remoteHomeDirectory(): printf '%s\n' "$HOME"
            return ExecResult(stdout = "/home/tester\n", stderr = "", exitCode = 0)
        }

        override suspend fun downloadFile(remotePath: String, maxBytes: Long): ByteArray {
            downloads.incrementAndGet()
            if (blockNextDownload) {
                blockNextDownload = false
                downloadStarted.complete(Unit)
                releaseDownload.await()
            }
            val body = when {
                remotePath.endsWith("/readme.txt") -> "hello viewer"
                remotePath.endsWith("/a.txt") -> "alpha"
                else -> "other body"
            }
            return body.toByteArray(Charsets.UTF_8)
        }

        override suspend fun listDirectory(remotePath: String, maxEntries: Int): RemoteListing =
            error("not used")

        override fun tail(path: String, onLine: (String) -> Unit): Job = error("not used")

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
