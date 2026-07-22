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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
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
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class FileViewerReopenReconcileTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    /**
     * The reported bug, on the reused-VM-instance reopen path: open a text file,
     * change its host content, reopen the SAME request — the viewer must
     * reconcile to the NEW body (not keep the stale one).
     */
    @Test
    fun reopeningTheSameRequestReconcilesToTheChangedHostContent() = runBlocking {
        val session = MutableFileSession(body = "original body v1")
        val leaseManager = SshLeaseManager(
            connector = CountingConnector(session),
            idleTtlMillis = 30_000L,
        )
        val vm = FileViewerViewModel(context, leaseManager)
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
    fun reopeningAfterNavigatingAwayReconcilesTheFreshContent() = runBlocking {
        val session = MutableFileSession(body = "unused")
        val leaseManager = SshLeaseManager(
            connector = CountingConnector(session),
            idleTtlMillis = 30_000L,
        )
        val vm = FileViewerViewModel(context, leaseManager)

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
    fun reBindingWhileTheFirstFetchIsInFlightDoesNotStackADuplicateDownload() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val session = MutableFileSession(body = "gated body", gate = gate)
        val leaseManager = SshLeaseManager(
            connector = CountingConnector(session),
            idleTtlMillis = 30_000L,
        )
        val vm = FileViewerViewModel(context, leaseManager)
        val req = request("/srv/gated.txt")

        vm.bind(req)
        // Wait until the (gated) download has actually started.
        awaitCondition { session.downloadStarted.get() == 1 }

        // Recomposition re-bind while the first fetch is still suspended on the
        // gate — this must be suppressed, not restart the load.
        vm.bind(req)
        vm.bind(req)

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

    private suspend fun StateFlow<FileViewerUiState>.awaitText(
        predicate: (FileViewerUiState.TextContent) -> Boolean = { true },
    ): FileViewerUiState.TextContent {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val s = value
            if (s is FileViewerUiState.TextContent && predicate(s)) return s
            kotlinx.coroutines.delay(20)
        }
        error("viewer never reached the expected TextContent state; was ${value}")
    }

    private suspend fun awaitCondition(predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            kotlinx.coroutines.delay(20)
        }
        error("condition never became true")
    }

    private fun request(path: String) = FileViewerViewModel.Request(
        hostId = 1L,
        hostname = "10.0.2.2",
        port = 2222,
        username = "tester",
        keyPath = "/tmp/key",
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
