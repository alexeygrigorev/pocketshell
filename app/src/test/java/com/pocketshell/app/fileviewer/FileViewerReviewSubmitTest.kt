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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 * Issue #714 slice 2 — submit a review over the REUSED viewer SSH lease.
 *
 * JVM-level (no emulator): a fake [SshSession] captures the `mkdir -p` exec +
 * the `uploadStream` (`cat > '<path>'`) write and proves the submit:
 *  - writes ONE `.yaml` to `<home>/inbox/pocketshell/reviews/` with the built
 *    `pocketshell_review` body;
 *  - reuses the warm viewer lease (no fresh connection — the same counting
 *    connector that proves the viewer's open dials exactly once);
 *  - clears the pending set + emits a Success event on success;
 *  - KEEPS the pending set + emits a Failure event on failure (nothing lost).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class FileViewerReviewSubmitTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun submitWritesTheReviewYamlToTheReviewsInboxOverTheReusedLease() = runBlocking {
        val session = RecordingFileSession()
        val connector = CountingConnector(session)
        val leaseManager = SshLeaseManager(connector = connector, idleTtlMillis = 30_000L)
        val vm = FileViewerViewModel(context, leaseManager)

        vm.bind(request("/srv/Foo.kt"))
        vm.state.awaitText()
        val openHandshakes = connector.connectCount

        vm.toggleReviewMode()
        vm.setLineComment(1, "this allocation is on the hot path")
        vm.setFileComment("overall structure is good")

        val event = vm.submitAndAwaitEvent("hetzner")

        // One mkdir + one uploadStream, no second one.
        assertEquals("expected exactly one mkdir -p", 1, session.mkdirCommands.size)
        assertTrue(
            "mkdir must target the reviews inbox, was ${session.mkdirCommands.first()}",
            session.mkdirCommands.first().contains("/home/tester/inbox/pocketshell/reviews"),
        )
        assertEquals("expected exactly one YAML upload", 1, session.uploads.size)

        val upload = session.uploads.first()
        assertTrue(
            "remote path must land in the reviews inbox, was ${upload.remotePath}",
            upload.remotePath.startsWith("/home/tester/inbox/pocketshell/reviews/"),
        )
        assertTrue(
            "remote file must be a single .yaml, was ${upload.remotePath}",
            upload.remotePath.endsWith(".yaml"),
        )
        assertTrue(
            "filename must carry the sanitised source basename, was ${upload.name}",
            upload.name.startsWith("Foo.kt-") && upload.name.endsWith(".yaml"),
        )

        // The uploaded body is the built pocketshell_review YAML.
        val body = upload.body
        assertTrue("body must be pocketshell_review YAML, was:\n$body", body.contains("type: pocketshell_review"))
        assertTrue(body.contains("host: hetzner"))
        assertTrue(body.contains("file: /srv/Foo.kt"))
        assertTrue(body.contains("this allocation is on the hot path"))
        assertTrue(body.contains("overall structure is good"))
        // The line's verbatim code anchor (line 1 of the fetched body).
        assertTrue("body must carry the verbatim line code, was:\n$body", body.contains("line one"))

        // Reused the warm lease — no extra handshake beyond the viewer open.
        assertEquals("submit must reuse the warm lease, not dial again", openHandshakes, connector.connectCount)
        assertFalse("submit must NOT close the shared transport", session.closed)

        // Success: pending cleared, review mode stays active, not submitting.
        assertTrue("expected Success, was $event", event is ReviewSubmitEvent.Success)
        val success = event as ReviewSubmitEvent.Success
        assertEquals(2, success.count)
        assertEquals("hetzner", success.host)
        val cleared = vm.reviewState.value
        assertFalse("pending set must be cleared on success", cleared.hasPending)
        assertTrue("review mode stays active after submit", cleared.active)
        assertFalse("submitting flag must reset", cleared.submitting)

        leaseManager.close()
    }

    /**
     * Issue #775 finding F1 — the `mkdir -p '<dir>'` directory prefix must be
     * single-quote-escaped like the upload path, so a `$HOME` that contains a
     * literal `'` cannot break out of the quotes (defense-in-depth: the input
     * is the maintainer's own host config, but the whole write chain should be
     * uniformly escaped). A hostile `$HOME` of `/home/o'reilly` must produce a
     * `mkdir` command where the quote is neutralised as `'\''`, never a raw
     * unbalanced `'`.
     */
    @Test
    fun submitEscapesASingleQuoteInTheRemoteHomeForTheMkdirPrefix() = runBlocking {
        val session = RecordingFileSession(remoteHome = "/home/o'reilly")
        val leaseManager = SshLeaseManager(
            connector = CountingConnector(session),
            idleTtlMillis = 30_000L,
        )
        val vm = FileViewerViewModel(context, leaseManager)

        vm.bind(request("/srv/Foo.kt"))
        vm.state.awaitText()
        vm.toggleReviewMode()
        vm.setFileComment("looks good")

        val event = vm.submitAndAwaitEvent("hetzner")
        assertTrue("expected Success, was $event", event is ReviewSubmitEvent.Success)

        assertEquals("expected exactly one mkdir -p", 1, session.mkdirCommands.size)
        val mkdir = session.mkdirCommands.first()
        // The whole directory argument is one fully single-quoted token with the
        // embedded quote escaped as the canonical close-escape-reopen sequence.
        assertEquals(
            "mkdir prefix must be uniformly single-quote-escaped, was: $mkdir",
            "mkdir -p '/home/o'\\''reilly/inbox/pocketshell/reviews'",
            mkdir,
        )
        // No raw unbalanced single quote that would let the dir prefix break out
        // of the quotes: every `'` is part of the inert `'\''` escape sequence.
        assertEquals(
            "every single quote must be balanced inside the escape, was: $mkdir",
            0,
            countUnescapedBreakouts(mkdir),
        )

        leaseManager.close()
    }

    @Test
    fun submitMkdirPrefixIsUnchangedForANormalHome() = runBlocking {
        val session = RecordingFileSession()
        val leaseManager = SshLeaseManager(
            connector = CountingConnector(session),
            idleTtlMillis = 30_000L,
        )
        val vm = FileViewerViewModel(context, leaseManager)

        vm.bind(request("/srv/Foo.kt"))
        vm.state.awaitText()
        vm.toggleReviewMode()
        vm.setFileComment("looks good")
        vm.submitAndAwaitEvent("hetzner")

        assertEquals("expected exactly one mkdir -p", 1, session.mkdirCommands.size)
        // No behaviour change for a normal quote-free home: a plain single-quoted
        // directory token (no `'\''` escapes appear because there's nothing to
        // escape).
        assertEquals(
            "mkdir -p '/home/tester/inbox/pocketshell/reviews'",
            session.mkdirCommands.first(),
        )

        leaseManager.close()
    }

    @Test
    fun submitFailureKeepsThePendingCommentsAndReportsTheError() = runBlocking {
        val session = RecordingFileSession(failUpload = true)
        val leaseManager = SshLeaseManager(
            connector = CountingConnector(session),
            idleTtlMillis = 30_000L,
        )
        val vm = FileViewerViewModel(context, leaseManager)

        vm.bind(request("/srv/Foo.kt"))
        vm.state.awaitText()

        vm.toggleReviewMode()
        vm.setLineComment(1, "keep me")
        vm.setFileComment("and me")

        val event = vm.submitAndAwaitEvent("hetzner")

        assertTrue("expected Failure, was $event", event is ReviewSubmitEvent.Failure)
        val kept = vm.reviewState.value
        assertTrue("pending comments must survive a failed submit", kept.hasPending)
        assertEquals("line comment must be kept", "keep me", kept.lineComments[1])
        assertEquals("file comment must be kept", "and me", kept.fileComment)
        assertFalse("submitting flag must reset after failure", kept.submitting)

        leaseManager.close()
    }

    @Test
    fun submitIsANoOpWhenThereAreNoPendingComments() = runBlocking {
        val session = RecordingFileSession()
        val leaseManager = SshLeaseManager(
            connector = CountingConnector(session),
            idleTtlMillis = 30_000L,
        )
        val vm = FileViewerViewModel(context, leaseManager)

        vm.bind(request("/srv/Foo.kt"))
        vm.state.awaitText()
        vm.toggleReviewMode()

        // No comments added — submit must do nothing (no upload, no event).
        vm.submitReview("hetzner")
        delay(100)

        assertEquals("no upload without pending comments", 0, session.uploads.size)
        assertEquals("no mkdir without pending comments", 0, session.mkdirCommands.size)

        leaseManager.close()
    }

    // --- helpers ------------------------------------------------------------

    /**
     * Counts single quotes in [command] that are NOT part of a `'\''`
     * close-escape-reopen sequence and are not the surrounding token quotes.
     * A correctly escaped command leaves zero such "breakout" quotes. Used to
     * assert the `mkdir` prefix can't escape its single-quoted argument.
     */
    private fun countUnescapedBreakouts(command: String): Int {
        // Strip the canonical escape sequence first; any single quote left over
        // that isn't a balanced wrapping quote would be a breakout.
        val withoutEscapes = command.replace("'\\''", "")
        // Remaining quotes must form balanced open/close pairs (even count).
        val remaining = withoutEscapes.count { it == '\'' }
        return remaining % 2
    }

    private suspend fun FileViewerViewModel.submitAndAwaitEvent(host: String): ReviewSubmitEvent {
        val captured = java.util.concurrent.atomic.AtomicReference<ReviewSubmitEvent?>(null)
        // Subscribe on the Main test dispatcher BEFORE submitting so the
        // buffered SharedFlow emit is observed (replay 0 needs a live collector
        // OR the extra buffer; the buffer covers the race, this covers delivery).
        val collector = CoroutineScope(Dispatchers.Main).launch {
            reviewEvents.collect { captured.set(it) }
        }
        yield()
        submitReview(host)
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && captured.get() == null) {
            delay(20)
        }
        collector.cancel()
        assertNotNull("submit never emitted a ReviewSubmitEvent", captured.get())
        return captured.get()!!
    }

    private suspend fun StateFlow<FileViewerUiState>.awaitText(): FileViewerUiState.TextContent {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val s = value
            if (s is FileViewerUiState.TextContent) return s
            delay(20)
        }
        error("viewer never reached TextContent; was ${value}")
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
        private val session: RecordingFileSession,
    ) : SshLeaseConnector {
        var connectCount: Int = 0

        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
            connectCount += 1
            return Result.success(session)
        }
    }

    data class CapturedUpload(val name: String, val remotePath: String, val body: String)

    /**
     * In-memory remote: `$HOME` probes to /home/tester; the fetched file body's
     * first line is "line one" (so the review's verbatim `code` anchor is
     * checkable); `mkdir -p` + `uploadStream` are recorded.
     */
    private class RecordingFileSession(
        private val failUpload: Boolean = false,
        private val remoteHome: String = "/home/tester",
    ) : SshSession {
        var closed: Boolean = false
        val mkdirCommands = mutableListOf<String>()
        val uploads = mutableListOf<CapturedUpload>()
        val downloads = AtomicInteger(0)

        override val isConnected: Boolean
            get() = !closed

        override suspend fun exec(command: String): ExecResult {
            if (command.startsWith("mkdir -p")) {
                mkdirCommands += command
                return ExecResult(stdout = "", stderr = "", exitCode = 0)
            }
            // remoteHomeDirectory(): printf '%s\n' "$HOME"
            return ExecResult(stdout = "$remoteHome\n", stderr = "", exitCode = 0)
        }

        override suspend fun downloadFile(remotePath: String, maxBytes: Long): ByteArray {
            downloads.incrementAndGet()
            return "line one\nline two".toByteArray(Charsets.UTF_8)
        }

        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String {
            if (failUpload) throw com.pocketshell.core.ssh.SshException("Permission denied")
            val body = input.readBytes().toString(Charsets.UTF_8)
            uploads += CapturedUpload(name = name, remotePath = remotePath, body = body)
            return remotePath
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

        override fun close() {
            closed = true
        }
    }
}
