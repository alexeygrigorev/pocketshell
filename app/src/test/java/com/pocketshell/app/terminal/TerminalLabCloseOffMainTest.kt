package com.pocketshell.app.terminal

import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Freeze sweep (follow-up to #1128 / #1085 freeze-hunt F-E): the campaign root
 * is that `RealSshSession.close()` BLOCKS its caller until the
 * `SSH_MSG_DISCONNECT` socket write finishes — on a wedged socket that pins the
 * caller thread. #1128 neutralised the AutoForwarderSupervisor Main caller. This
 * proves the OTHER surviving Main caller — [TerminalLabController.close], reached
 * on the Main thread from `TerminalLabActivity.onDestroy()` — no longer freezes
 * the caller.
 *
 * RED on base: `close()` calls `ignoreClose { session.close() }` inline on the
 * caller thread, so closing while the session's `close()` hangs blocks the caller
 * until the latch releases → the caller thread is still alive after the join.
 * GREEN with the fix: the socket-touching close is dispatched to the teardown
 * dispatcher, so `close()` returns promptly while the disconnect is still
 * dispatched (teardown not dropped).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class TerminalLabCloseOffMainTest {

    @Test
    fun `close does not block the caller when the session close hangs`() {
        val closeEntered = CountDownLatch(1)
        val closeRelease = CountDownLatch(1)
        val session = HangingCloseSession(closeEntered, closeRelease)

        val executor = Executors.newSingleThreadExecutor()
        val teardownDispatcher = executor.asCoroutineDispatcher()
        val target = TerminalLabTarget(
            host = "127.0.0.1",
            port = 2222,
            user = "app",
            key = SshKey.Pem("unused-pem"),
        )
        val controller = TerminalLabController(
            target = target,
            teardownDispatcher = teardownDispatcher,
        )
        controller.installTransportForTest(session = session, shell = null)

        try {
            // Drive close() from a dedicated "caller" thread (stands in for the
            // Main thread that runs `onDestroy`) and prove it returns without
            // waiting on the hung disconnect.
            val callerThread = Thread { controller.close() }
            callerThread.start()
            callerThread.join(2_000L)
            assertFalse(
                "close() must return without blocking on the hung session close",
                callerThread.isAlive,
            )
            assertTrue(
                "the session's close() must still be invoked (teardown not dropped)",
                closeEntered.await(2, TimeUnit.SECONDS),
            )
        } finally {
            closeRelease.countDown()
            teardownDispatcher.close()
            executor.shutdownNow()
        }
    }

    /**
     * [SshSession] fake whose [close] parks on a latch — reproducing a wedged
     * `SSH_MSG_DISCONNECT` socket write. Only the members [TerminalLabController]
     * touches are meaningful; the rest error out.
     */
    private class HangingCloseSession(
        private val closeEntered: CountDownLatch,
        private val closeRelease: CountDownLatch,
    ) : SshSession {
        @Volatile
        var closed: Boolean = false

        override val isConnected: Boolean get() = !closed

        override suspend fun exec(command: String): ExecResult = error("not used")
        override fun tail(path: String, onLine: (String) -> Unit): Job = error("not used")
        override fun openLocalPortForward(remoteHost: String, remotePort: Int, localPort: Int): SshPortForward =
            error("not used")
        override fun startShell(): SshShell = error("not used")
        override suspend fun uploadFile(file: File, remotePath: String): String = error("not used")
        override suspend fun uploadStream(input: InputStream, length: Long, name: String, remotePath: String): String =
            error("not used")

        override fun close() {
            closeEntered.countDown()
            // Park like a wedged disconnect socket until the test releases us.
            closeRelease.await()
            closed = true
        }
    }
}
