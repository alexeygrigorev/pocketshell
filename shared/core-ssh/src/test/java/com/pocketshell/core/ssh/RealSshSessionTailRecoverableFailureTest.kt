package com.pocketshell.core.ssh

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.ConnectionException
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.common.DisconnectReason
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketException

/**
 * Issue #239 — `RealSshSession.tail()` must NOT propagate transport
 * failures to the coroutine root.
 *
 * The v0.2.8 maintainer device captured this crash on Pixel 7a:
 *
 * ```
 * com.pocketshell.core.ssh.SshException: Failed to start tail session for
 *   `/home/alexey/.claude/projects/.../<uuid>.jsonl`:
 *   Software caused connection abort
 *   at com.pocketshell.core.ssh.RealSshSession$tail$1.invokeSuspend(RealSshSession.kt:77)
 * Caused by: net.schmizz.sshj.connection.ConnectionException:
 *   Software caused connection abort
 * Caused by: java.net.SocketException: Software caused connection abort
 * ```
 *
 * Root cause: the SSH socket died on the read side mid-tail (or right
 * at `startSession()` time). `tail()` runs on a supervisor-scoped
 * `launch`, so a throw from inside the coroutine becomes an unhandled
 * exception that reaches the JVM default uncaught-exception handler →
 * `CrashReporter`.
 *
 * Per D21 (no background work) and the orthogonal reconnect state
 * machine in `TmuxSessionViewModel` (#145 + #173), the tail's own job
 * just needs to end cleanly on a transport drop. The tmux event-loop
 * coroutine will observe the same drop through its producer job and
 * route through the existing `_disconnected` -> reconnect path; once
 * reconnected, `reconcilePanes` will start a fresh `tail()` on the new
 * session.
 *
 * Coverage:
 *  1. `startSession()` throws `ConnectionException` (the captured shape
 *     from crash C) → tail job completes cleanly, no exception
 *     propagates.
 *  2. `startSession()` throws `SocketException` (the root cause in the
 *     `Caused by` chain) → tail job completes cleanly.
 *  3. `startSession()` throws `TransportException` → tail job completes
 *     cleanly.
 *  4. `startSession()` throws `IOException` (catch-all parent of the
 *     sshj/socket families) → tail job completes cleanly.
 *  5. Negative case: `startSession()` throws a genuine programming
 *     error (`IllegalStateException`) → wrapped in `SshException` and
 *     surfaced via the job's completion exception (so genuine bugs
 *     still get reported).
 *  6. `ensureConnected()` precondition still enforced: an unconnected
 *     client throws `SshException` synchronously from `tail()`.
 *
 * The test does NOT exercise the mid-stream `readLine()` IOException
 * branch — that requires a full `Session` / `Command` mock and the
 * module deliberately has no mocking framework. The startSession-throw
 * path covers the exact crash signature from the captured report; the
 * mid-stream catch is verified by inspection and shares the same
 * `logTailRecoverableFailure` swallow path.
 */
class RealSshSessionTailRecoverableFailureTest {

    @Test
    fun `tail swallows ConnectionException from startSession`() {
        // This is the exact shape captured in the v0.2.8 crash report C.
        runTailJobCompletesCleanly(
            ConnectionException("Software caused connection abort"),
        )
    }

    @Test
    fun `tail swallows SocketException from startSession`() {
        // Root cause from the crash report's `Caused by` chain.
        runTailJobCompletesCleanly(
            SocketException("Software caused connection abort"),
        )
    }

    @Test
    fun `tail swallows TransportException from startSession`() {
        // sshj's transport-layer abort flavour — same teardown story.
        runTailJobCompletesCleanly(
            TransportException(DisconnectReason.CONNECTION_LOST, "transport gone"),
        )
    }

    @Test
    fun `tail swallows raw IOException from startSession`() {
        // Belt-and-suspenders for any other sshj-wrapped or sdk-level
        // I/O failure that the `IOException` catch clause covers.
        runTailJobCompletesCleanly(
            IOException("generic transport I/O failure"),
        )
    }

    @Test
    fun `tail propagates genuine programming errors from startSession`() {
        // Negative case: a non-IOException Throwable is still wrapped in
        // SshException and surfaces to the coroutine root. We do NOT
        // want to silently swallow genuine bugs (NPE, IAE, ISE, ...).
        val client = ConnectedThrowingClient(
            startSessionToThrow = IllegalStateException("genuine bug"),
        )
        val session = RealSshSession(client)
        val capturedExceptions = mutableListOf<Throwable>()

        // Install a thread-default uncaught handler the supervisor will
        // route to — restore the previous handler in `finally` so we
        // never leak state between tests.
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            capturedExceptions += throwable
        }
        try {
            val job = session.tail("/some/path") { /* unreachable */ }
            runBlocking {
                withTimeout(5_000) {
                    job.join()
                }
            }
            // The supervisor scope catches the throw and routes it
            // through the default uncaught handler — which is exactly
            // the path that ends up at the crash reporter. We assert
            // the throw shape matches the wrap so a future regression
            // that silently swallows ISE is caught by this test.
            assertTrue(
                "expected the tail job to surface the wrapped SshException for a non-IO Throwable; " +
                    "captured=$capturedExceptions",
                capturedExceptions.any { e ->
                    e is SshException && (e.cause is IllegalStateException)
                },
            )
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
            session.close()
        }
    }

    @Test
    fun `tail throws SshException synchronously when the session is not connected`() {
        // Precondition guard from `ensureConnected()` is unchanged —
        // calling `tail` on a disconnected session is a programmer
        // error, not a recoverable runtime fault. This test pins that
        // synchronous-throw contract so the #239 fix does not
        // accidentally swallow the pre-flight check.
        val client = DisconnectedClient()
        val session = RealSshSession(client)

        try {
            session.tail("/path") { /* unreachable */ }
            assertTrue("expected SshException for disconnected session", false)
        } catch (e: SshException) {
            assertTrue(
                "expected message to flag the disconnected precondition",
                e.message?.contains("not connected") == true,
            )
        } finally {
            session.close()
        }
    }

    /**
     * Shared body for the recoverable-failure cases. Installs a
     * thread-default uncaught handler so we'd notice if the fix
     * regressed and an exception leaked to the JVM default handler
     * (which is the route the crash reporter listens on).
     */
    private fun runTailJobCompletesCleanly(toThrow: Throwable) {
        val client = ConnectedThrowingClient(startSessionToThrow = toThrow)
        val session = RealSshSession(client)
        val capturedExceptions = mutableListOf<Throwable>()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            capturedExceptions += throwable
        }
        try {
            val job = session.tail("/agent.jsonl") { /* unreachable */ }
            runBlocking {
                withTimeout(5_000) {
                    job.join()
                }
                // Give the supervisor scope a tick to flush any
                // pending uncaught-exception routing before we check
                // that nothing slipped through.
                delay(10)
            }
            assertTrue("tail job should complete cleanly on $toThrow", job.isCompleted)
            assertFalse("tail job should NOT complete with an exception on $toThrow", job.isCancelled)
            assertTrue(
                "no uncaught exception must reach the default handler on $toThrow; " +
                    "captured=$capturedExceptions",
                capturedExceptions.isEmpty(),
            )
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
            session.close()
        }
    }

    /**
     * `SSHClient` that reports as connected + authenticated (so the
     * `ensureConnected()` precondition passes) and throws the
     * configured exception from `startSession()`. Same pattern as
     * [RealSshSessionCloseIdempotencyTest]'s `ThrowingDisconnectClient`:
     * the bare `SSHClient()` constructor does not open a socket, so
     * this is safe for unit-test instantiation with no network I/O.
     */
    private class ConnectedThrowingClient(
        private val startSessionToThrow: Throwable,
    ) : SSHClient() {

        override fun isConnected(): Boolean = true

        override fun isAuthenticated(): Boolean = true

        override fun startSession(): net.schmizz.sshj.connection.channel.direct.Session {
            throw startSessionToThrow
        }

        override fun disconnect() {
            // No-op: bare-constructed client has no socket to tear
            // down. The tail test's `session.close()` path otherwise
            // invokes `super.disconnect()` which would NPE walking
            // uninitialised connection state.
        }
    }

    /**
     * `SSHClient` that reports as disconnected so the
     * `RealSshSession.tail()` precondition check fires.
     */
    private class DisconnectedClient : SSHClient() {
        override fun isConnected(): Boolean = false
        override fun isAuthenticated(): Boolean = false
        override fun disconnect() { /* no-op */ }
    }
}
