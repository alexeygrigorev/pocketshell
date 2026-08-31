package com.pocketshell.app.fileviewer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.RemoteListing
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
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
import java.util.concurrent.atomic.AtomicReference

/**
 * Issue #1713 — reopening a file whose host content changed must show the FRESH
 * content. `bind()` used to return early whenever `request == lastRequest`, so a
 * genuine reopen of the same `(host, path)` never re-ran `load()` and the viewer
 * kept showing the STALE body. JVM-level with a fake, MUTABLE remote whose body
 * can change between reads — no emulator.
 *
 * The Docker sibling ([FileViewerDockerTest.reopeningAChangedTextFileShowsTheFreshHostContent])
 * proves the same red→green on the real SSH path.
 *
 * ## Issue #2339 — why there is not a single wall-clock wait in this class
 *
 * This class carried the same defect shape that made its sibling
 * [FileViewerWorkspaceTest] flaky by construction and reddened the required
 * `Unit tests` check on `main`: a `runBlocking` body polled `state.value` (and
 * `downloadStarted`) behind hand-rolled `System.currentTimeMillis() + 10_000`
 * deadlines while every hop the ViewModel owns ran on a REAL thread pool. Such
 * a pump returns the instant ONE observable lands, so a sibling effect of the
 * same turn is sampled at an arbitrary moment. (Unlike [FileViewerWorkspaceTest]
 * this class was never OBSERVED red — it is the identical construction on the
 * identical seams, fixed structurally rather than waiting for it to bite.)
 *
 * The cure is the convention's Shape A (pinned seam), not a bigger budget:
 * EVERY hop the code under test owns now resolves on ONE
 * [kotlinx.coroutines.test.TestCoroutineScheduler] — `Dispatchers.Main` via
 * [MainDispatcherRule], the ViewModel's blocking SSH hop
 * ([FileViewerViewModel.ioDispatcher]) and cpu hop
 * ([FileViewerViewModel.computeDispatcher]), the workspace RPC
 * ([FileWorkspaceRemoteSource.remoteExecDispatcher]), the process-scoped
 * workspace write queue, and the lease manager's dial / abort / idle-close.
 *
 * [settle] then drains that scheduler with `runCurrent()`, which runs every task
 * due at the current instant (including the ones those tasks schedule) and NEVER
 * advances virtual time. That last property is load-bearing:
 * [reBindingWhileTheFirstFetchIsInFlightDoesNotStackADuplicateDownload] holds a
 * fetch deliberately parked on a gate, and advancing the clock would instead
 * fire `LeaseSessionExec`'s 45 s block bound and turn the park into a timeout.
 *
 * Consequence for anyone extending this class: there is nothing to wait for.
 * Call [settle] and assert. Do NOT reintroduce a `System.currentTimeMillis()`
 * deadline, a `Thread.sleep`, or a `delay` — with the seams pinned they would
 * measure nothing except how busy the box is.
 * `scripts/check-test-validity.sh` hard-fails a hand-rolled deadline pump in
 * this directory (#2339).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class FileViewerReopenReconcileTest {

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

    /**
     * The reported bug, on the reused-VM-instance reopen path: open a text file,
     * change its host content, reopen the SAME request — the viewer must
     * reconcile to the NEW body (not keep the stale one).
     */
    @Test
    fun reopeningTheSameRequestReconcilesToTheChangedHostContent() {
        val session = MutableFileSession(body = "original body v1")
        val leaseManager = leaseManager(CountingConnector(session))
        val vm = viewModel(leaseManager)
        val req = request("/srv/notes.txt")

        vm.bind(req)
        val first = vm.state.awaitText()
        assertEquals("original body v1", first.content)

        // The host file changes (an agent rewrote it) while the viewer is open.
        session.body.set("CHANGED ON HOST v2")

        // Reopen the IDENTICAL request (what a navigate-away-and-back does with a
        // surviving VM). The fix must run a fresh fetch so the new body wins.
        vm.bind(req)
        val reopened = vm.state.awaitText { it.content == "CHANGED ON HOST v2" }
        assertEquals(
            "reopen must show the fresh host content, not the stale body",
            "CHANGED ON HOST v2",
            reopened.content,
        )
        assertEquals("reopen must issue a fresh SFTP read", 2, session.downloads.get())
        leaseManager.close()
    }

    /**
     * Class coverage (G2), fresh-navigation path: A → B → A reopen. After the
     * host changes A, coming back to A must reconcile to A's new content (and the
     * viewer must show A, not B).
     */
    @Test
    fun reopeningAfterNavigatingAwayReconcilesTheFreshContent() {
        val session = MutableFileSession(body = "unused")
        val leaseManager = leaseManager(CountingConnector(session))
        val vm = viewModel(leaseManager)

        session.pathBodies["/srv/a.txt"] = "A original"
        session.pathBodies["/srv/b.txt"] = "B body"

        vm.bind(request("/srv/a.txt"))
        assertEquals("A original", vm.state.awaitText { it.displayPath.endsWith("/a.txt") }.content)

        vm.bind(request("/srv/b.txt"))
        vm.state.awaitText { it.displayPath.endsWith("/b.txt") }

        // A changes on the host while we're viewing B.
        session.pathBodies["/srv/a.txt"] = "A CHANGED"

        vm.bind(request("/srv/a.txt"))
        val backToA = vm.state.awaitText { it.displayPath.endsWith("/a.txt") && it.content == "A CHANGED" }
        assertEquals("A CHANGED", backToA.content)
        leaseManager.close()
    }

    /**
     * A true no-op recompose — a re-bind of the identical request while the first
     * fetch is still IN FLIGHT — must NOT stack a duplicate download (the #697
     * behaviour the old guard protected). We hold the first fetch open, fire a
     * second identical bind, then release: exactly one download runs.
     */
    @Test
    fun reBindingWhileTheFirstFetchIsInFlightDoesNotStackADuplicateDownload() {
        val gate = CompletableDeferred<Unit>()
        val session = MutableFileSession(body = "gated body", gate = gate)
        val leaseManager = leaseManager(CountingConnector(session))
        val vm = viewModel(leaseManager)
        val req = request("/srv/gated.txt")

        vm.bind(req)
        settle()
        // The gated download has actually started and is still parked, so the
        // re-binds below provably land on an IN-FLIGHT fetch (the state the test
        // exists to cover) rather than on an already-finished one.
        assertEquals(
            "the first fetch must have started before the re-binds",
            1,
            session.downloadStarted.get(),
        )
        assertEquals("the first fetch must still be parked on the gate", 0, session.downloads.get())

        // Recomposition re-bind while the first fetch is still suspended on the
        // gate — this must be suppressed, not restart the load.
        vm.bind(req)
        vm.bind(req)
        settle()

        gate.complete(Unit)
        val settled = vm.state.awaitText()
        assertEquals("gated body", settled.content)
        assertEquals(
            "an in-flight re-bind must not stack a duplicate download",
            1,
            session.downloads.get(),
        )
        leaseManager.close()
    }

    // --- harness ------------------------------------------------------------

    /**
     * A lease manager whose OWN coroutine work is pinned to the shared test
     * scheduler: the idle-close job, the bounded dial, and the dial's abort.
     * The idle-close `delay(idleTtlMillis)` therefore never fires, because
     * [settle] deliberately never advances virtual time.
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

    private class CountingConnector(
        private val session: MutableFileSession,
    ) : SshLeaseConnector {
        var connectCount: Int = 0

        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
            connectCount += 1
            return Result.success(session)
        }
    }

    /**
     * In-memory remote whose file body can change between reads. [body] is the
     * default returned for any path; [pathBodies] overrides per absolute path so
     * an A/B navigation is distinguishable. An optional [gate] suspends each
     * download until completed, so a test can hold a fetch in flight.
     */
    private class MutableFileSession(
        body: String,
        private val gate: CompletableDeferred<Unit>? = null,
    ) : SshSession {
        var closed: Boolean = false
        val body = AtomicReference(body)
        val pathBodies = mutableMapOf<String, String>()
        val downloads = AtomicInteger(0)
        val downloadStarted = AtomicInteger(0)

        override val isConnected: Boolean
            get() = !closed

        override suspend fun exec(command: String): ExecResult =
            ExecResult(stdout = "/home/tester\n", stderr = "", exitCode = 0)

        override suspend fun downloadFile(remotePath: String, maxBytes: Long): ByteArray {
            downloadStarted.incrementAndGet()
            gate?.await()
            downloads.incrementAndGet()
            val text = pathBodies[remotePath] ?: body.get()
            return text.toByteArray(Charsets.UTF_8)
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
