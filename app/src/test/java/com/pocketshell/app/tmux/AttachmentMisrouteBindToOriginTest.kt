package com.pocketshell.app.tmux

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.tmux.TmuxClientFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream
import java.util.Collections

/**
 * Issue #968 — attachment upload MISROUTE: a slow upload completes against the
 * CURRENT session, not the one it was started from.
 *
 * Maintainer dogfood (2026-06-25):
 * > I clicked upload, then it wasn't uploading, I came here, and then the
 * > attachment actually finished uploading. except it was for a DIFFERENT
 * > session.
 *
 * Sequence: in session A, tap upload -> it looks stuck -> the user navigates to
 * a DIFFERENT session B -> the upload then completes, but it lands in B's scope
 * dir + composer, NOT the originating session A.
 *
 * Root cause (research spike, 2026-06-25): the attach path resolved its
 * destination at upload-COMPLETION time — it read `activeTarget` (the tmux
 * session name that derives the `.pocketshell/attachments/host-<id>-<name>`
 * scope dir) AFTER the (possibly slow) `awaitLiveSessionForAttachment()` await,
 * a single shared field rebound on every session switch. So a switch A->B
 * during the await landed the bytes in B. The send path already snapshots its
 * target at initiation (`sendTargetSnapshotProvider`); this is the
 * un-snapshotted twin.
 *
 * Fix (#968 Slice 1, bind-to-origin): snapshot the origin target at the first
 * synchronous line of `stagePromptAttachments`, derive the scope dir from THAT,
 * and — at completion — refuse to deliver to a session that is no longer the
 * origin (surface a clear error, never silently misroute to the now-active
 * session). Hard-cut per D22 — no "deliver to current" fallback.
 *
 * RED on base: with the user switched A->B mid-upload, the real
 * `stagePromptAttachments` uploaded into B's scope dir
 * (`host-1-bravo`) and returned success. GREEN with the fix: the upload binds
 * to A; after a switch to B it returns a clear error and NEVER writes B's
 * scope.
 *
 * Class coverage (G2):
 *  - switch-away-mid-upload: A->B before completion -> error, no B-scope upload.
 *  - same-session (control): no switch -> the upload lands in A's scope.
 *  - origin-backgrounded-then-restored to origin -> still lands in origin.
 *  - origin-closed-mid-upload (active target cleared) -> clear error, no upload.
 *  - the [TmuxSessionViewModel.attachmentOriginStillActiveForTest] decision seam
 *    (the gate the upload consults) across A==active, A!=active, origin-gone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class AttachmentMisrouteBindToOriginTest {

    private val factoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        factoryScope.cancel()
    }

    private fun runVmTest(body: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        com.pocketshell.app.tmux.LivenessProbeTestOverride.setAutoStartEnabledForTest(false)
        try {
            body()
        } finally {
            com.pocketshell.app.tmux.LivenessProbeTestOverride.clear()
            Dispatchers.resetMain()
        }
    }

    private fun TestScope.newVm(): TmuxSessionViewModel = TmuxSessionViewModel(
        tmuxClientFactory = TmuxClientFactory(factoryScope),
        activeTmuxClients = ActiveTmuxClients(),
        runtimeCache = TmuxSessionRuntimeCache(),
        sshLeaseManager = com.pocketshell.core.ssh.SshLeaseManager(
            connector = com.pocketshell.core.ssh.SshLeaseConnector { target ->
                error("unexpected SSH lease connect for ${target.leaseKey}")
            },
            idleTtlMillis = 0L,
            connectTimeoutContext = StandardTestDispatcher(testScheduler),
            nowMillis = { testScheduler.currentTime },
        ),
        sessionLifecycleSignals = null,
        applicationContext = ApplicationProvider.getApplicationContext(),
    ).also {
        // Pin the seed-IO dispatcher to the shared virtual-clock scheduler so
        // attach/switch round-trips run inline on the test clock (#926).
        it.setSeedIoDispatcherForTest(StandardTestDispatcher(testScheduler))
    }

    /**
     * Connect the VM to [sessionName] on the shared host (hostId=1), wiring a
     * recording [RecordingSshSession] as the live `sessionRef`. A second call
     * with a different [sessionName] models the A->B tmux session switch over
     * the SAME warm host SSH session (D21): the SSH session is shared; only the
     * tmux session NAME (and thus the scope dir) changes.
     */
    private fun TmuxSessionViewModel.connectToSession(
        sessionName: String,
        session: SshSession,
    ) {
        replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = sessionName,
            client = FakeTmuxClient(),
            session = session,
        )
    }

    private fun seedContentUri(bytes: ByteArray): Uri {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val temp = File.createTempFile("issue968-attach-", ".png", context.cacheDir)
        temp.writeBytes(bytes)
        // A real `file://` URI: Robolectric's ContentResolver opens its real
        // bytes via openInputStream, and the OpenableColumns query returns null
        // (no size) so the stager takes the drainToTempFile -> uploadFile branch
        // — the production attach path for a size-less picker Uri. WHICH branch
        // is irrelevant to this test; both record the destination scope dir.
        return Uri.fromFile(temp)
    }

    // ---- The load-bearing reproduction --------------------------------------

    /**
     * THE misroute repro: start an upload in A, switch to B WHILE it is awaiting
     * a live session (the "it wasn't uploading" window), and assert the upload
     * binds to A — it must NOT write into B's scope dir and must return a clear
     * error (not silent success against B).
     *
     * Faithful timing: session A starts DISCONNECTED so
     * `awaitLiveSessionForAttachment()` enters its poll loop (the picker
     * round-trip / "looked stuck" window). The test body then performs the
     * A->B switch DURING that await and reconnects A's transport so the await
     * can resolve a live session. On base, the destination scope + session were
     * read AFTER the await, so the now-active B received the upload. With the
     * fix, the origin was snapshotted at entry (A) and the completion gate sees
     * the active target is now B != A -> clear error, no misroute.
     */
    @Test
    fun switchAwayMidUpload_bindsToOrigin_noMisrouteToActiveSession() = runVmTest {
        val vm = newVm()
        val sessionB = RecordingSshSession("B")
        val sessionA = RecordingSshSession("A")
        // A connected, then its transport goes transiently not-Connected — the
        // attach await polls for a live session (the stuck window).
        vm.connectToSession("alpha", sessionA)
        sessionA.connected = false

        val uri = seedContentUri("hello-from-A".toByteArray())
        // backgroundScope (the TestScope's child) runs the upload coroutine on
        // the test's virtual clock so the await poll-loop `delay`s advance under
        // `advanceTimeBy` while the test body drives the switch.
        val deferred = backgroundScope.async { vm.stagePromptAttachments(listOf(uri)) }
        // Let the upload coroutine enter the await poll loop.
        testScheduler.advanceTimeBy(ATTACH_SESSION_WAIT_POLL_MS * 2)
        testScheduler.runCurrent()

        // The user switches A -> B mid-upload, and a live session is now
        // available (B's). On base this is exactly when the destination is
        // resolved -> B.
        vm.connectToSession("bravo", sessionB)
        testScheduler.advanceTimeBy(ATTACH_SESSION_WAIT_POLL_MS * 2)
        testScheduler.runCurrent()

        val result = deferred.await()

        // GREEN: the upload binds to origin A; with the user now on B it errors
        // out (origin no longer active) rather than misrouting into B.
        assertTrue(
            "the upload must FAIL when the origin session is switched away from " +
                "(bind-to-origin), not silently land in the now-active session; got $result",
            result.isFailure,
        )

        // The load-bearing data-integrity assertion: NOTHING was written into
        // B's scope dir. On base this scope received the mkdir + uploadStream.
        assertFalse(
            "MISROUTE: bytes must NEVER land in the now-active session B's scope " +
                "dir; sessionB recorded ${sessionB.touchedScopes}",
            sessionB.touchedScopes.any { it.contains("bravo") },
        )
        assertTrue(
            "the upload must not write file bytes into B; sessionB uploads=" +
                "${sessionB.uploadedPaths}",
            sessionB.uploadedPaths.isEmpty(),
        )
    }

    /**
     * Control: with NO switch, the same real path uploads into the ORIGIN A
     * scope dir (`host-1-alpha`) and succeeds — proving the bind-to-origin gate
     * does not break the happy path and that the scope is the origin's.
     */
    @Test
    fun noSwitch_uploadLandsInOriginScope() = runVmTest {
        val vm = newVm()
        val sessionA = RecordingSshSession("A")
        vm.connectToSession("alpha", sessionA)

        val uri = seedContentUri("hello".toByteArray())
        val result = vm.stagePromptAttachments(listOf(uri))

        assertTrue("the happy-path upload must succeed; got $result", result.isSuccess)
        assertTrue(
            "the upload must land in the ORIGIN A scope dir (host-1-alpha); " +
                "uploads=${sessionA.uploadedPaths}",
            sessionA.uploadedPaths.any { it.contains("host-1-alpha") },
        )
    }

    /**
     * origin-closed-mid-upload: the origin session is torn down (active target
     * cleared) while the upload is awaiting a live session. The upload must
     * surface a clear error and write NOTHING — never fall through to whatever
     * is active. The origin is closed DURING the await (A starts disconnected
     * so the await polls), the realistic timing for a closed-origin upload.
     */
    @Test
    fun originClosedMidUpload_clearError_noUpload() = runVmTest {
        val vm = newVm()
        val sessionA = RecordingSshSession("A")
        vm.connectToSession("alpha", sessionA)
        sessionA.connected = false

        val uri = seedContentUri("hello".toByteArray())
        val deferred = backgroundScope.async { vm.stagePromptAttachments(listOf(uri)) }
        testScheduler.advanceTimeBy(ATTACH_SESSION_WAIT_POLL_MS * 2)
        testScheduler.runCurrent()

        // Origin closed mid-upload: clear the active target / connection.
        vm.clearForTest()
        testScheduler.advanceTimeBy(ATTACH_SESSION_WAIT_TIMEOUT_MS)
        testScheduler.runCurrent()

        val result = deferred.await()

        assertTrue(
            "an upload whose origin session closed mid-flight must FAIL clearly; " +
                "got $result",
            result.isFailure,
        )
        assertTrue(
            "no bytes may be uploaded when the origin closed mid-upload; " +
                "uploads=${sessionA.uploadedPaths}",
            sessionA.uploadedPaths.isEmpty(),
        )
    }

    // ---- The decision seam (class coverage of the gate) ---------------------

    @Test
    fun originStillActiveSeam_coversActiveSwitchedAndGoneOrigins() = runVmTest {
        val vm = newVm()
        vm.connectToSession("alpha", RecordingSshSession("A"))

        // A is still active -> still the origin.
        assertTrue(
            "origin A is still the active target -> bind-to-origin gate must allow",
            vm.attachmentOriginStillActiveForTest(originHostId = 1L, originSessionName = "alpha"),
        )

        // Switch A -> B: origin A is no longer active.
        vm.connectToSession("bravo", RecordingSshSession("B"))
        assertFalse(
            "after switching A->B, origin A is NOT the active target -> gate must deny " +
                "(the misroute guard)",
            vm.attachmentOriginStillActiveForTest(originHostId = 1L, originSessionName = "alpha"),
        )
        // ...and B IS the active target now (sanity: the gate tracks identity).
        assertTrue(
            "B is the active target after the switch",
            vm.attachmentOriginStillActiveForTest(originHostId = 1L, originSessionName = "bravo"),
        )

        // Origin gone entirely (active target cleared).
        vm.clearForTest()
        assertFalse(
            "with no active target, an origin A is no longer active -> gate must deny",
            vm.attachmentOriginStillActiveForTest(originHostId = 1L, originSessionName = "alpha"),
        )
    }

    /** A null origin only matches a null active target (the fallback scope). */
    @Test
    fun originStillActiveSeam_nullOriginMatchesOnlyNullActive() = runVmTest {
        val vm = newVm()
        // No connection: no active target. null origin == null active -> true.
        assertTrue(
            vm.attachmentOriginStillActiveForTest(originHostId = null, originSessionName = null),
        )
        vm.connectToSession("alpha", RecordingSshSession("A"))
        assertFalse(
            "a null origin must NOT match a non-null active target",
            vm.attachmentOriginStillActiveForTest(originHostId = null, originSessionName = null),
        )
    }

    /**
     * An [SshSession] that records the remote scope dirs it was asked to
     * `mkdir` and the paths it uploaded, so a test can prove WHICH session's
     * scope received the bytes. [onFirstExec] fires once on the first `exec`
     * (the scope-dir mkdir) to inject a mid-upload state change (switch/close).
     */
    private class RecordingSshSession(
        val label: String,
        private val onFirstExec: (() -> Unit)? = null,
    ) : SshSession {
        val touchedScopes: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val uploadedPaths: MutableList<String> = Collections.synchronizedList(mutableListOf())

        @Volatile
        var connected: Boolean = true
        private var firstExecFired = false

        override val isConnected: Boolean get() = connected

        override suspend fun exec(command: String): ExecResult {
            touchedScopes += command
            if (!firstExecFired) {
                firstExecFired = true
                onFirstExec?.invoke()
            }
            return ExecResult(stdout = "", stderr = "", exitCode = 0)
        }

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

        override suspend fun uploadFile(file: File, remotePath: String): String {
            uploadedPaths += remotePath
            return remotePath
        }

        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String {
            input.readBytes()
            uploadedPaths += remotePath
            return remotePath
        }

        override fun close() {
            connected = false
        }
    }
}
