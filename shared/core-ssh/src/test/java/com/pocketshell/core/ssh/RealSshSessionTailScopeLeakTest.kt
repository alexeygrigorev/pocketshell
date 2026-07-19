package com.pocketshell.core.ssh

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import net.schmizz.sshj.SSHClient
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Issue #1694 — the async-exception-leak CLASS, root-caused and closed.
 *
 * ## The class (not one instance)
 *
 * A `:shared:core-ssh:testDebugUnitTest` run intermittently reddens a DIFFERENT
 * sibling test each time with
 * `kotlinx.coroutines.test.UncaughtExceptionsBeforeTest`. #1689 removed ONE
 * source — `SshConnectionFailureTest`'s real TCP connect. But the SAME symptom
 * has a SECOND, still-live source in the current tree: a genuine-bug exception
 * that escapes a `RealSshSession` background-`launch` coroutine.
 *
 * `RealSshSession.tail()` (issue #239) deliberately lets a NON-transport
 * `Throwable` (a real programming bug) propagate to the coroutine root so
 * genuine bugs still reach the crash reporter. That root is
 * `RealSshSession.scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)` —
 * which, on the base code, carries **no `CoroutineExceptionHandler`**. So the
 * throw takes kotlinx-coroutines' *global* `handleUncaughtCoroutineException`
 * fallback, and that fallback is exactly where kotlinx-coroutines-test installs
 * its process-wide `ExceptionCollector`. Once ANY `runTest` in the JVM worker
 * has primed the collector, the escaped exception is recorded and reported as
 * `UncaughtExceptionsBeforeTest` against **whichever `runTest` runs next** — a
 * different, innocent sibling (seen striking `SshConnectionCancellationTest`).
 * The full-module suite is green on `main` only because a *lucky* test ordering
 * happens to place a non-`runTest` next; it is a latent, order-dependent flake
 * that any new `runTest`/timing shift (e.g. PR #1683's diagnostics) resurfaces.
 *
 * ## The durable fix (root, whole class)
 *
 * `RealSshSession`'s background scopes now carry a `CoroutineExceptionHandler`
 * that forwards an uncaught throw to the thread's default uncaught handler (so
 * the crash-reporter contract for genuine bugs is preserved) WITHOUT taking the
 * global fallback the collector listens on. A context handler short-circuits
 * `handleCoroutineException` before `handleUncaughtCoroutineException`, so the
 * collector never sees it — no ordering can resurface the leak.
 *
 * This test forces the exact unlucky ordering deterministically in ONE method
 * (prime the collector → make a genuine-bug tail exception escape → run a
 * sibling `runTest`) so it fails RED on the base and passes GREEN with the fix,
 * regardless of how the runner happens to order the rest of the module.
 */
class RealSshSessionTailScopeLeakTest {

    @Test
    fun `a genuine-bug tail exception never poisons a subsequent sibling runTest`() {
        // (1) Prime kotlinx-coroutines-test's process-wide ExceptionCollector,
        // exactly as any real sibling runTest in this module does. After this,
        // the collector is live for the rest of the JVM worker.
        runTest { assertTrue(true) }

        // (2) Force the leak source: a genuine programming bug thrown from a
        // RealSshSession.tail() background coroutine. Production forwards a
        // genuine bug to the crash reporter (the thread default uncaught
        // handler), so install a capturing handler to keep it from killing the
        // test JVM — and assert the crash-reporter contract still holds.
        val captured = CopyOnWriteArrayList<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, throwable -> captured += throwable }
        try {
            val session = RealSshSession(GenuineBugClient(IllegalStateException("genuine bug #1694")))
            val job = session.tail("/agent.jsonl") { /* unreachable */ }
            runBlocking {
                withTimeout(5_000) { job.join() }
                // Let the supervisor scope flush its uncaught-exception routing.
                delay(50)
            }
            session.close()

            // Crash-reporter contract preserved: the genuine bug is still
            // surfaced (wrapped) to the default uncaught handler.
            assertTrue(
                "genuine programming bugs from tail must still reach the crash reporter; captured=$captured",
                captured.any { it is SshException && it.cause is IllegalStateException },
            )
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }

        // (3) THE LOAD-BEARING ASSERTION. If the escaped tail exception was
        // captured by the global ExceptionCollector (base: no context handler
        // on RealSshSession.scope), THIS runTest throws
        // UncaughtExceptionsBeforeTest before its body runs — RED. With the fix
        // (context handler short-circuits the global fallback) the collector
        // never saw it, so this runs clean — GREEN.
        runTest {
            assertTrue("sibling runTest must not inherit a leaked tail exception", true)
        }
    }

    /**
     * A bare-constructed [SSHClient] (no socket, no network I/O) that reports
     * connected + authenticated so `tail()`'s precondition passes, then throws a
     * genuine programming bug from `startSession()`. Mirrors the sibling
     * `RealSshSessionTailRecoverableFailureTest.ConnectedThrowingClient`.
     */
    private class GenuineBugClient(
        private val bug: Throwable,
    ) : SSHClient() {
        override fun isConnected(): Boolean = true
        override fun isAuthenticated(): Boolean = true
        override fun startSession(): net.schmizz.sshj.connection.channel.direct.Session =
            throw bug
        override fun disconnect() { /* no-op: bare client has no socket */ }
    }
}
