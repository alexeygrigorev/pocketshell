package com.pocketshell.core.ssh

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reproduce-first (issue #937 / S1-F2 / D33 / G10): the bounded teardown
 * (#710) wraps `lease.release()` in `withTimeoutOrNull(SYNC_DETACH_TIMEOUT_MS)`
 * in `TmuxSessionViewModel`/`GitHistoryViewModel`/`RecurringJobsViewModel`. But
 * `SshLease.release` USED to run its whole body inside
 * `withContext(NonCancellable)`, which swallows cancellation — so the caller's
 * timeout could NOT interrupt a release that blocked behind a wedged transport
 * close on the contended lease mutex. That defeats the very ceiling meant to
 * stop the onCleared/activity-destroy ANR.
 *
 * These tests stand in a `releaseAction` that suspends "forever" (the wedged
 * close) and assert the caller's `withTimeoutOrNull` actually fires. On the
 * UNFIXED `release()` (NonCancellable) the timeout NEVER fires → the test hangs
 * / `withTimeoutOrNull` returns non-null after the action eventually completes.
 *
 * Pure JVM, Docker-free — runs in the per-push Unit gate via `./gradlew test`.
 */
class SshLeaseReleaseInterruptibleTest {

    private fun lease(action: suspend (SshLeaseKey, Long) -> Unit): SshLease =
        SshLease(
            key = SshLeaseKey(host = "h", port = 22, user = "u", credentialId = "/tmp/k"),
            session = NoopSshSession,
            isNewConnection = true,
            entryId = 1L,
            releaseAction = action,
        )

    /**
     * The caller's `withTimeoutOrNull` MUST fire when the release blocks — i.e.
     * `release()` is interruptible. RED on base (NonCancellable swallows the
     * cancellation, the timeout never fires).
     */
    @Test
    fun `caller timeout interrupts a wedged lease release`() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        val neverCompletes = CompletableDeferred<Unit>()
        val actionFinished = AtomicBoolean(false)

        val l = lease { _, _ ->
            started.complete(Unit)
            // The wedged transport-close stand-in: suspend until externally
            // released (which the test never does within the window).
            neverCompletes.await()
            actionFinished.set(true)
        }

        // Mirror the production teardown: a bounded wait around release().
        val result = withTimeoutOrNull(300L) {
            l.release()
            "released"
        }

        assertNull(
            "the bounded teardown's withTimeoutOrNull MUST fire while the " +
                "release is wedged — it does NOT when release() wraps the body " +
                "in NonCancellable (RED on base)",
            result,
        )
        assertTrue("the wedged release should have at least started", started.isCompleted)
        assertTrue(
            "the release action must NOT have run to completion (it was interrupted)",
            !actionFinished.get(),
        )

        neverCompletes.complete(Unit)
    }

    /**
     * Class coverage: a fast, healthy release still completes within the
     * timeout (the interruptibility change must not break the normal path).
     */
    @Test
    fun `healthy release completes within the caller timeout`() = runBlocking<Unit> {
        val ran = AtomicBoolean(false)
        val l = lease { _, _ -> ran.set(true) }
        val result = withTimeoutOrNull(2_000L) {
            l.release()
            "released"
        }
        assertNotNull("a healthy release must complete within budget", result)
        assertTrue("the release action must have run", ran.get())
    }

    /**
     * Class coverage: release() is idempotent — a second call after a
     * successful release is a no-op, and a cancelled (interrupted) release
     * leaves the lease un-released so a later retry/close still tears it down.
     */
    @Test
    fun `release is idempotent and a cancelled release leaves the lease releasable`() = runBlocking<Unit> {
        val callCount = java.util.concurrent.atomic.AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()
        val l = lease { _, _ ->
            callCount.incrementAndGet()
            // First call wedges until the gate opens; later calls run instantly.
            if (callCount.get() == 1) gate.await()
        }

        // First release: interrupted by the caller timeout (gate stays closed).
        val first = withTimeoutOrNull(200L) {
            l.release()
            "released"
        }
        assertNull("first (wedged) release is interrupted", first)

        // Open the gate so a retry can complete, then retry — the lease was NOT
        // marked released (the interrupted action never returned), so the retry
        // actually runs the action again and completes.
        gate.complete(Unit)
        val second = withTimeoutOrNull(2_000L) {
            l.release()
            "released"
        }
        assertNotNull("a retry after an interrupted release must complete", second)

        // A third release is now a no-op (released == true), action not re-run.
        val before = callCount.get()
        l.release()
        assertTrue("a release after success is a no-op", callCount.get() == before)
    }
}

/**
 * Minimal [SshSession] stand-in for lease-release tests that never touch the
 * transport (the release action is fully synthetic here). Mirrors the
 * `FakeSshSession` shape used across the other lease tests.
 */
private object NoopSshSession : SshSession {
    override val isConnected: Boolean get() = false
    override suspend fun exec(command: String): ExecResult =
        ExecResult(stdout = "", stderr = "", exitCode = 0)
    override fun tail(path: String, onLine: (String) -> Unit): Job = error("not used")
    override fun openLocalPortForward(
        remoteHost: String,
        remotePort: Int,
        localPort: Int,
    ): SshPortForward = error("not used")
    override fun startShell(): SshShell = object : SshShell {
        override val stdin = ByteArrayOutputStream()
        override val stdout = ByteArrayInputStream(ByteArray(0))
        override val stderr = ByteArrayInputStream(ByteArray(0))
        override fun close() = Unit
    }
    override suspend fun uploadFile(file: File, remotePath: String): String = error("not used")
    override suspend fun uploadStream(
        input: InputStream,
        length: Long,
        name: String,
        remotePath: String,
    ): String = error("not used")
    override fun close() {}
}
